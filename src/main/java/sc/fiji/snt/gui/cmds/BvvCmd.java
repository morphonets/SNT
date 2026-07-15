/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui.cmds;

import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import net.imagej.ImgPlus;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.janelia.saalfeldlab.n5.bdv.N5ViewerTreeCellRenderer;
import org.janelia.saalfeldlab.n5.ij.N5Importer;
import org.janelia.saalfeldlab.n5.ui.DatasetSelectorDialog;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bdv;
import sc.fiji.snt.viewer.Bvv;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator.n5vGroupParsers;
import static org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator.n5vParsers;

/**
 * Convenience command for starting a standalone Bdv/Bvv instance.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Big Data")
public class BvvCmd extends ContextCommand {

    private static final String TOOLTIP =
            """
            Supports standard formats (e.g., TIFF), bio-formats supported files,
            and big data formats with lazy loading (N5, Zarr, HDF5, OME-TIFF,
            IMS, BDV .xml). Large datasets are opened virtually without loading
            the entire file into memory.""";

    private static final int GL_MAX_3D_TEXTURE_SIZE = 0x8073; // OpenGL constant
    private static final String ABORT = "Abort";
    private static final String DOWNSAMPLE = "Downsample to fit";
    private static final String CONVERT = "Show me how to convert to multi-resolution pyramid image";


    @Parameter(label = "Main volume", style = FileWidget.FILE_AND_DIRECTORY_STYLE,
            description = "Primary image volume.\n"+ TOOLTIP)
    File img1File;

    @Parameter(required = false, style = FileWidget.FILE_AND_DIRECTORY_STYLE,
            label = "Secondary volume (optional)", description = "Optional image volume.\n"+ TOOLTIP)
    File img2File;

    @Parameter(required = false, label = "Reconstruction files (optional)",
            description = "Either a single file (TRACES, SWC, JSON), or a folder containing multiple reconstruction files")
    File recFiles;

    @Parameter(required = false, label = "Markers file (optional)",
            description = "A CSV file containing bookmarked locations")
    File markerFile;

    @Parameter(label = "Viewer type", choices = {"2D: Big Data Viewer (BDV)", "3D: Big Volume Viewer (BVV)",
            "3D: Big Volume Viewer (BVV) with tracing capabilities"},
            description = "The type of viewer")
    String viewerChoice;

    @Override
    public void run() {
        SNTUtils.setDebugMode(true);
        if (img1File == null) {
            error("Main volume is required.");
            return;
        }
        final String[] filePaths = Stream.of(img1File, img2File)
                .filter(Objects::nonNull)
                .map(File::getAbsolutePath)
                .toArray(String[]::new);
        if (filePaths.length == 0) {
            error("No volume files specified.");
            return;
        }
        final boolean threeD = viewerChoice == null || viewerChoice.toLowerCase().contains("bvv")
                || viewerChoice.toLowerCase().contains("vol") || viewerChoice.toLowerCase().contains("3d");
        final boolean tracer = viewerChoice != null && viewerChoice.toLowerCase().contains("tracing");

        if (tracer && SNTUtils.getInstance() != null) {
            error("SNT seems to be already running. Please close current instance and re-run.");
            return;
        }

        try {
            SNTUtils.setIsLoading(true);
            if (tracer)
                runBvvWithTracing(filePaths);
            else if (threeD)
                runBvv(filePaths);
            else
                runBdv(filePaths);
        } catch (final Exception e) {
            error("An error occurred: " + e.getMessage());
        } finally {
            SNTUtils.setIsLoading(false);
        }
    }

    /** True if path is an existing .n5 or .zarr directory. */
    private static boolean isN5OrZarrDir(final String path) {
        final File f = new File(path);
        final String lower = f.getName().toLowerCase();
        return f.isDirectory() && (lower.endsWith(".n5") || lower.endsWith(".zarr"));
    }

    /**
     * Fallback UI for when {@link SpimDataUtils#resolvePathToSource(String)} cannot auto-discover a dataset in an
     * N5/Zarr container on its own (e.g. an ambiguous or unusually structured container). Lets the user pick a
     * dataset interactively.
     *
     * @param n5ZarrDir the {@code .n5} or {@code .zarr} directory
     * @param viewer    the already-created {@link Bvv} or {@link Bdv} to add the user's eventual selection to
     */
    private void datasetDialog(final String n5ZarrDir, final AbstractBigViewer viewer) {
        // n5-ij's DatasetSelectorDialog feeds this path straight into java.net.URI's single-string constructor (see
        // ImprovedFormattedTextField) to populate its "container path" text field. A raw Windows path (e.g.,
        // "E:\foo\bar") crashes that parser: "E:" is read as a URI scheme,  and the backslash right after it is illegal.
        // Forward slashes don't have this problem, so normalizing here should be safe.
        final String normalizedPath = n5ZarrDir.replace('\\', '/');
        final ExecutorService exec = Executors.newFixedThreadPool(SNTPrefs.getThreads());
        final DatasetSelectorDialog datasetDialog = getDatasetSelectorDialog(normalizedPath, exec);
        SwingUtilities.invokeLater(() -> {
            datasetDialog.run(selection -> {
                try {
                    final SpimDataUtils.N5Sources n5Sources =
                            SpimDataUtils.resolveN5Selection(selection, new File(normalizedPath).getName());
                    if (viewer instanceof Bvv bvv) bvv.show(n5Sources);
                    else if (viewer instanceof Bdv bdv) bdv.show(n5Sources);
                } catch (final Exception e) {
                    GuiUtils.errorPrompt("Could not open '" + n5ZarrDir + "': " + e.getMessage());
                } finally {
                    exec.shutdown();
                }
            });
            datasetDialog.openContainer(normalizedPath); // run() calls buildDialog() synchronously before returning
        });
    }

    private static DatasetSelectorDialog getDatasetSelectorDialog(final String n5ZarrDir, final ExecutorService exec) {
        final DatasetSelectorDialog datasetDialog = new DatasetSelectorDialog(
                new N5Importer.N5ViewerReaderFun(), new N5Importer.N5BasePathFun(), n5ZarrDir,
                n5vGroupParsers, n5vParsers);
        datasetDialog.setLoaderExecutor(exec);
        datasetDialog.setTreeRenderer(new N5ViewerTreeCellRenderer(false));
        datasetDialog.setContainerPathUpdateCallback(path -> {}); // required; NPEs otherwise
        return datasetDialog;
    }

    /** Resolves sources, enforces GPU texture limits, then opens BVV. */
    private void runBvv(final String[] filePaths) {
        final int maxTexSize = queryMaxTexture3DSize();
        SNTUtils.log("BVV: GL_MAX_3D_TEXTURE_SIZE = " + maxTexSize);
        final ResolvedSources resolved = resolveBvvSources(filePaths, maxTexSize);
        if (resolved == null) return; // user chose Abort (oversized image)
        final Bvv bvv = new Bvv();
        addSourcesToBvv(bvv, resolved);
        loadReconstructions(bvv);
        loadMarkers(bvv);
    }

    /**
     * Same as {@link #runBvv(String[])}, but tethers BVV to a full SNT instance (own SNTUI window,
     * Path Manager, etc.) so that {@code Bvv}'s tracing toggle (manual and/or A*) is functional.
     * <p>
     * SNT's own image state (used by A* search, via {@code getLoadedData()}) is populated from the
     * <i>primary</i> volume ({@link #img1File}) only when that file is safe/fast to also open
     * conventionally, i.e., not a lazily-loaded N5/Zarr container. In that case (or if opening the
     * primary volume conventionally fails for any other reason), SNT starts without image data:
     * manual tracing still works (segment tracing only needs spacing, which defaults to 1 regardless
     * of a loaded image), but A* has nothing real to search until an image is loaded via the SNTUI.
     */
    private void runBvvWithTracing(final String[] filePaths) {
        final int maxTexSize = queryMaxTexture3DSize();
        SNTUtils.log("BVV: GL_MAX_3D_TEXTURE_SIZE = " + maxTexSize);
        final ResolvedSources resolved = resolveBvvSources(filePaths, maxTexSize);
        if (resolved == null) return; // user chose Abort (oversized image)
        final SNT snt = startTracingSNT(img1File);
        final Bvv bvv = new Bvv(snt);
        addSourcesToBvv(bvv, resolved);
        loadReconstructions(bvv);
        loadMarkers(bvv);
        bvv.getViewerFrame().addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosed(final WindowEvent e) {
                if (snt.getUI() != null) snt.getUI().setBvv(null);
            }
        });
    }

    /** Holds the outcome of {@link #resolveBvvSources(String[], int)}. */
    private record ResolvedSources(List<Object> sources, List<String> deferredPaths) {}

    /**
     * Resolves each path to a BVV-displayable source (an {@link ImgPlus}, {@link AbstractSpimData},
     * or {@link SpimDataUtils.N5Sources}), enforcing the GPU's 3D texture size limit along the way.
     * N5/Zarr directories that can't be auto-discovered headlessly are collected into {@code
     * deferredPaths} instead, to be resolved later via the interactive {@link #datasetDialog}.
     *
     * @return the resolved sources, or {@code null} if the user chose to Abort when prompted about
     *         an oversized image (see {@link #handleOversizedImage})
     */
    private ResolvedSources resolveBvvSources(final String[] filePaths, final int maxTexSize) {
        final List<Object> sources = new ArrayList<>();
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (final String path : filePaths) {
            final Object source;
            try {
                source = SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BVV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof ImgPlus<?> img && ImgUtils.exceedsDimension(img, maxTexSize)) {
                final Object handled = handleOversizedImage(img, maxTexSize, path);
                if (handled == null) return null; // user chose Abort
                sources.add(handled);
            } else {
                sources.add(source);
            }
        }
        return new ResolvedSources(sources, deferredPaths);
    }

    /** Adds each resolved source to {@code bvv}, and opens the interactive dialog for deferred N5/Zarr paths. */
    private void addSourcesToBvv(final Bvv bvv, final ResolvedSources resolved) {
        for (final Object source : resolved.sources()) {
            if (source instanceof AbstractSpimData<?> spim) {
                bvv.show(spim);
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                bvv.show(n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bvv.show((ImgPlus) img);
            }
        }
        for (final String path : resolved.deferredPaths())
            datasetDialog(path, bvv);
    }

    /**
     * Starts a full SNT instance (SNTUI window included) without displaying an ImagePlus window
     * BVV is the only display; SNT exists here for its Path Manager (and, when possible, A* search).
     * <p>
     * When {@code primaryVolume} resolves headlessly to a plain {@link ImgPlus} (the common case:
     * TIFF, and often N5/Zarr too), it is wired directly into SNT via the {@code SNT(ImgPlus)}
     * "Tracing Mode" constructor: this sets SNT's own {@code ctSlice3d} (what A* search reads via
     * {@code getLoadedData()}) straight from the same object BVV renders, without ever assembling or
     * showing the classic 2D tracing canvas ({@code setFieldsFromImgPlus} sets {@code xy = null}).
     * {@link SNT#accessToValidImageData()} treats {@code ctSlice3d != null} as sufficient on its own
     * <p>
     * Since {@code ctSlice3d} only needs to be a {@code RandomAccessibleInterval}, this also works
     * transparently when the resolved {@code ImgPlus} is lazily backed (e.g. N5/Zarr): A* search's
     * random-access reads trigger on-demand chunk loading the same way BVV's own rendering does
     * <p>
     * Deliberately uses the <i>original</i>, full-resolution {@code ImgPlus} here, not whatever
     * (possibly downsampled) version {@link #resolveBvvSources} produced for BVV: SNT's A* search is
     * plain CPU-side iteration with no GPU texture-size constraint, so there's no reason to degrade it
     * to match BVV's rendering limits.
     * <p>
     * When the primary volume instead resolves to an {@link AbstractSpimData} (e.g. IMS, BDV .xml
     * multi-view containers) or {@link SpimDataUtils.N5Sources} (ambiguous N5/Zarr layouts still
     * pending the interactive dataset dialog), there is no {@code ImgPlus} to hand to the
     * {@code SNT(ImgPlus)} constructor. SNT instead starts blank ("Analysis Mode") and {@link
     * #applyFallbackCalibration} attempts to wire dimensions/calibration and the underlying pixel
     * data (via {@link SNT#setImageMetadata}/{@link SNT#setImageData}) directly from the
     * source's own {@code ImgLoader}/{@code Source}, timepoint 0. When that succeeds, both manual
     * tracing and A* search work as usual; if it fails for any reason (unexpected loader
     * implementation, etc.), tracing falls back to manual-only.
     */
    private SNT startTracingSNT(final File primaryVolume) {
        GuiUtils.setLookAndFeel(); // needs to be called here to set L&F of image's contextual menu!?
        if (getContext() == null && ij.IJ.getInstance() == null) {
            new net.imagej.ImageJ().ui().showUI();
        }
        ImgPlus<?> primaryImgPlus = null;
        Object primaryFallbackSource = null; // AbstractSpimData or SpimDataUtils.N5Sources, for calibration only
        if (primaryVolume != null) {
            try {
                final Object source = SpimDataUtils.resolvePathToSource(primaryVolume.getAbsolutePath());
                if (source instanceof ImgPlus<?> img) {
                    primaryImgPlus = img;
                } else {
                    SNTUtils.log("BVV: primary volume resolves to " + source.getClass().getSimpleName()
                            + " (not a plain ImgPlus); will attempt to wire dimensions/pixel data from it directly");
                    primaryFallbackSource = source;
                }
            } catch (final Exception e) {
                SNTUtils.log("BVV: could not resolve '" + primaryVolume.getName() + "' for SNT (" + e.getMessage()
                        + "); tracing will fall back to manual-only (no image data for A*)");
            }
        }

        final SNT snt;
        if (primaryImgPlus != null) {
            //noinspection unchecked,rawtypes
            snt = new SNT((ImgPlus) primaryImgPlus); // "Tracing Mode": no window; ctSlice3d set directly
        } else {
            final PathAndFillManager pathAndFillManager = new PathAndFillManager();
            snt = new SNT(getContext(), pathAndFillManager);
            snt.initialize(null);
            if (primaryFallbackSource != null) applyFallbackCalibration(snt, primaryFallbackSource);
        }
        try {
            snt.startUI(true); // self-dispatches to the EDT as needed; do not wrap in invokeAndWait here
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return snt;
    }

    /**
     * Extracts image dimensions/calibration and, when possible, the actual pixel data from a
     * resolved {@link AbstractSpimData} or {@link SpimDataUtils.N5Sources} and applies them to
     * {@code snt} via {@link SNT#setImageMetadata}/{@link SNT#setImageData}. Without at least the
     * metadata call, {@code snt} keeps its all-zero default dimensions, which breaks bounds checks.
     * <p>
     * Dimensions/calibration mirror the equivalent logic in {@link Bvv#show(AbstractSpimData)}/
     * {@link Bvv#show(SpimDataUtils.N5Sources)}, which populates BVV's own bounds for the same
     * reason. Pixel-data extraction is best-effort and wrapped separately: if it fails (e.g. an
     * unexpected {@code ImgLoader} implementation), tracing falls back to manual-only.
     * <p>
     * Caveat: this reads timepoint 0 (single-timepoint use case) directly from the loader/{@code
     * Source}, in the volume's own pixel grid. Any registration transform beyond plain
     * size/calibration (e.g. a non-identity {@code ViewRegistration}, or BVV's own manual-transform
     * mode) is <em>not</em> applied, so traced world coordinates could diverge from what's rendered
     * if such a transform is present.
     */
    private static void applyFallbackCalibration(final SNT snt, final Object source) {
        try {
            if (source instanceof AbstractSpimData<?> spimData) {
                final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
                if (setups.isEmpty()) return;
                final var setup = setups.getFirst();
                if (setup.hasSize() && setup.hasVoxelSize()) {
                    final var sz = setup.getSize();
                    final var vs = setup.getVoxelSize();
                    snt.setImageMetadata((int) sz.dimension(0), (int) sz.dimension(1), (int) sz.dimension(2),
                            vs.dimension(0), vs.dimension(1), vs.dimension(2), vs.unit());
                }
                try {
                    final var setupLoader = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader(setup.getId());
                    snt.setImageData(setupLoader.getImage(0)); // timepoint 0
                } catch (final Exception e) {
                    SNTUtils.log("BVV: could not access pixel data from SpimData ImgLoader for A* search ("
                            + e.getMessage() + "); manual tracing only");
                }
            } else if (source instanceof SpimDataUtils.N5Sources n5Sources && !n5Sources.sources.isEmpty()) {
                final var spimSource = n5Sources.sources.getFirst().getSpimSource();
                final var itvl = spimSource.getSource(0, 0); // timepoint 0, full-resolution level 0
                final var vd = spimSource.getVoxelDimensions();
                snt.setImageMetadata((int) itvl.dimension(0), (int) itvl.dimension(1), (int) itvl.dimension(2),
                        (vd == null) ? 0 : vd.dimension(0), (vd == null) ? 0 : vd.dimension(1),
                        (vd == null) ? 0 : vd.dimension(2), (vd == null) ? null : vd.unit());
                snt.setImageData(itvl); // same lazily-loaded interval backing BVV's own rendering
            }
        } catch (final Exception e) {
            SNTUtils.log("BVV: could not extract calibration for SNT (" + e.getMessage() + ")");
        }
    }

    /** Resolves sources (no texture-size constraint) then opens BDV. */
    private void runBdv(final String[] filePaths) {
        final Bdv bdv = new Bdv();
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (final String path : filePaths) {
            final Object source;
            try {
                source = SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BDV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof AbstractSpimData<?> spim) {
                bdv.show(spim, path); // path-aware overload populates spimDataFilePaths
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                bdv.show(n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bdv.show((ImgPlus) img);
            }
        }
        for (final String path : deferredPaths)
            datasetDialog(path, bdv);
        loadReconstructions(bdv);
        loadMarkers(bdv);
    }

    private void loadMarkers(final AbstractBigViewer viewer) {
        if (markerFile == null) return;
        if (!SNTUtils.fileAvailable(markerFile)) {
            error(String.format("%s does not exist or is not available.", markerFile.getName()));
            return;
        }
        viewer.getMarkerManager().showPanel();
        viewer.getMarkerManager().load(markerFile); // error if invalid file
    }

    /** Loads reconstruction files into the viewer, if any were specified. */
    private void loadReconstructions(final AbstractBigViewer viewer) {
        if (recFiles == null) return;
        if (!SNTUtils.fileAvailable(recFiles)) {
            error(String.format("%s does not exist or is not available.", recFiles.getName()));
            return;
        }
        final File[] files = SNTUtils.getReconstructionFiles(recFiles, null);
        final int fileCount = (files == null) ? 0 : files.length;
        SNTUtils.log(String.format("Loading %d reconstruction file(s) from %s.", fileCount, recFiles.getAbsolutePath()));
        if (fileCount == 0) {
            error(String.format("No reconstruction files found in %s.", recFiles.getName()));
            return;
        }
        viewer.add(files);
    }

    /**
     * Handles an ImgPlus whose spatial dimensions exceed the GPU's 3D texture
     * limit. Prompts the user to choose between aborting, downsampling, or
     * opening a conversion script.
     *
     * @return the (possibly downsampled) source to display, or {@code null} if
     *         the user chose to abort
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object handleOversizedImage(final ImgPlus<?> img, final int maxTexSize, final String path) {
        final String message = String.format("The image '%s' has spatial dimensions that exceed your " +
                        "GPU's 3D texture limit (%d texels). What would you like to do?",
                img.getName(), maxTexSize);
        final String choice = new GuiUtils(null).getChoice(message, "BVV: Volume Too Large",
                new String[]{DOWNSAMPLE, CONVERT, ABORT}, DOWNSAMPLE);

        if (choice == null || ABORT.equals(choice)) {
            cancel("");
            return null;
        }
        if (CONVERT.equals(choice)) {
            openConversionScript(path);
            cancel("");
            return null;
        }
        return ImgUtils.downsampleToFit((ImgPlus) img, maxTexSize);
    }

    /**
     * Opens the ConvertToN5 boilerplate script in Fiji's Script Editor with
     * the input path pre-filled.
     */
    private void openConversionScript(final String inputPath) {
        try {
            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
            final java.io.InputStream is = cl.getResourceAsStream(
                    "script_templates/Neuroanatomy/Boilerplate/ConvertToN5.groovy");
            if (is == null) {
                error("ConvertToN5.groovy template not found in resources.");
                return;
            }
            String script = new java.io.BufferedReader(new java.io.InputStreamReader(is))
                    .lines().collect(java.util.stream.Collectors.joining("\n"));
            script = script.replace("#{INPUT_PATH}", inputPath);
            ScriptInstaller.newScript(script, "ConvertToN5.groovy");
        } catch (final Exception e) {
            error("Could not open conversion script: " + e.getMessage());
        }
    }

    /**
     * Queries the GPU's {@code GL_MAX_3D_TEXTURE_SIZE} using an offscreen
     * JOGL drawable. Returns a conservative default of 2048 if the query fails.
     */
    private static int queryMaxTexture3DSize() {
        try {
            final GLProfile prof = GLProfile.getDefault();
            final GLCapabilities caps = new GLCapabilities(prof);
            final GLDrawableFactory factory = GLDrawableFactory.getFactory(prof);
            final GLOffscreenAutoDrawable drawable =
                    factory.createOffscreenAutoDrawable(null, caps, null, 1, 1);
            drawable.display();
            drawable.getContext().makeCurrent();
            try {
                final int[] val = new int[1];
                drawable.getContext().getGL().glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE, val, 0);
                SNTUtils.log("BVV: queried GL_MAX_3D_TEXTURE_SIZE = " + val[0]);
                return val[0] > 0 ? val[0] : 2048;
            } finally {
                drawable.getContext().release();
                drawable.destroy();
            }
        } catch (final Exception e) {
            SNTUtils.log("BVV: GL query failed (" + e.getMessage() + "), using default 2048");
            return 2048; // conservative fallback (default on macOS!?)
        }
    }

    private void error(final String msg) {
        GuiUtils.errorPrompt(msg);
        cancel("");
    }

}
