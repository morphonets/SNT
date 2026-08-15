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
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bdv;
import sc.fiji.snt.viewer.Bvv;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator.n5vGroupParsers;
import static org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator.n5vParsers;

/**
 * Convenience command for starting a standalone Bdv/Bvv instance, including SNT's Stream mode.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Big Data/SNT Stream", initializer = "init")
public class BigDataLoaderCmd extends ContextCommand {

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


    @Parameter(required = false, visibility= ItemVisibility.MESSAGE, persist = false)
    String msgHeader= "<HTML>All files can be specified by either local paths or remote URLs. Only <i>Main volume</i> is a mandatory field.";

    // NB: persist = false on all four File parameters below: SciJava's own generic File-parameter persistence restores
    // a value via `new File(persistedString)`, which mangles a remote URL (see toPathString()). Persistence is instead
    // handled manually below via PrefService, storing/restoring the repaired string (toPathString()) so URLs work

    @Parameter(label = "Main volume", style = FileWidget.FILE_AND_DIRECTORY_STYLE, persist = false,
            description = "Primary image volume.\n"+ TOOLTIP)
    File img1File;

    @Parameter(required = false, style = FileWidget.FILE_AND_DIRECTORY_STYLE, persist = false,
            label = "Secondary volume", description = "Optional image volume (e.g., a second channel saved separately).\n"+ TOOLTIP)
    File img2File;

    @Parameter(required = false, label = "Reconstruction(s)", persist = false,
            description = "Optional.\nEither a single file (TRACES, SWC, JSON, Neurolucida XML), or a\n" +
                    "folder/.zip archive of several such files. Coordinates are assumed\n" +
                    "to be properly scaled. If you need to apply an offset/scaling factor,\n" +
                    "use the \"Import\" menu commands instead once tracing starts.")
    File recFiles;

    @Parameter(required = false, label = "Markers", persist = false,
            description = "Optional.\nA CSV file containing bookmarked locations.")
    File markerFile;

    @Parameter
    private PrefService prefService;

    private static final String IMG1_KEY = "img1File";
    private static final String IMG2_KEY = "img2File";
    private static final String REC_KEY = "recFiles";
    private static final String MARKER_KEY = "markerFile";

    @Parameter(label = "Viewer type", description = "The type of viewer.\nTracing capabilities are provided by SNT Stream.",
            choices = {
            "Big Data Viewer (BDV): Interactive reslicing",
            "Big Data Viewer (BDV): Interactive reslicing w/ tracing capabilities",
            "Big Volume Viewer (BVV): 3D rendering",
            "Big Volume Viewer (BVV): 3D rendering w/ tracing capabilities"})
    String viewerChoice;

    @Parameter(required = false, visibility= ItemVisibility.MESSAGE, persist = false)
    String msg;

    @Parameter(label = "Load Remote Demo", callback = "loadDemo", persist = false, required = false)
    private Button demoButton;

    @SuppressWarnings("unused")
    private void loadDemo() {
        // fine to declare URL as file even though File collapses "//" -> "/": restoreUrlScheme() already handles that
        img1File = new File("https://ome-zarr-scivis.s3.us-east-1.amazonaws.com/v0.4/96x0/marmoset_neurons.ome.zarr");
        img2File = null;
        recFiles = new File("https://raw.githubusercontent.com/morphonets/misc/680ac2a9b2cb1dfe85c0b64f17fed816e3da1647/dataset-demos/marmoset_neurons/autotracings.traces");;
        markerFile = new File("https://raw.githubusercontent.com/morphonets/misc/680ac2a9b2cb1dfe85c0b64f17fed816e3da1647/dataset-demos/marmoset_neurons/soma_detections.csv");
        viewerChoice = "Big Data Viewer (BDV): Interactive reslicing w/ tracing capabilities";
    }

    @SuppressWarnings("unused")
    private void init() {
        msg = (SNTUtils.getInstance() == null)
                ? "" : "<HTML>NB: <i>SNT Stream</i> requires SNT to not already be running. Please close the active instance first.";
        populateLastUsed();
    }

    /** Restores the four File fields from PrefService, in place of SciJava's own (URL-mangling) persistence. */
    private void populateLastUsed() {
        final String lastImg1 = prefService.get(BigDataLoaderCmd.class, IMG1_KEY);
        if (lastImg1 != null) img1File = new File(lastImg1);
        final String lastImg2 = prefService.get(BigDataLoaderCmd.class, IMG2_KEY);
        if (lastImg2 != null) img2File = new File(lastImg2);
        final String lastRec = prefService.get(BigDataLoaderCmd.class, REC_KEY);
        if (lastRec != null) recFiles = new File(lastRec);
        final String lastMarker = prefService.get(BigDataLoaderCmd.class, MARKER_KEY);
        if (lastMarker != null) markerFile = new File(lastMarker);
    }

    /** Persists the four File fields as their repaired (toPathString()) string form. */
    private void saveLastUsed() {
        putOrRemove(IMG1_KEY, img1File);
        putOrRemove(IMG2_KEY, img2File);
        putOrRemove(REC_KEY, recFiles);
        putOrRemove(MARKER_KEY, markerFile);
    }

    private void putOrRemove(final String key, final File file) {
        if (file == null) prefService.remove(BigDataLoaderCmd.class, key);
        else prefService.put(BigDataLoaderCmd.class, key, toPathString(file));
    }

    @Override
    public void run() {
        SNTUtils.setDebugMode(true);
        if (img1File == null) {
            error("Main volume is required.");
            return;
        }
        saveLastUsed();
        final String[] filePaths = Stream.of(img1File, img2File)
                .filter(Objects::nonNull)
                .map(BigDataLoaderCmd::toPathString)
                .toArray(String[]::new);
        if (filePaths.length == 0) {
            error("No volume files specified.");
            return;
        }
        final boolean threeD = viewerChoice == null || viewerChoice.toLowerCase().contains("bvv")
                || viewerChoice.toLowerCase().contains("vol");
        final boolean tracer = viewerChoice != null && viewerChoice.toLowerCase().contains("tracing");

        if (tracer && SNTUtils.getInstance() != null) {
            error("SNT seems to be already running. Please close the current instance and re-run.");
            return;
        }

        try {
            SNTUtils.setIsLoading(true);
            if (tracer && threeD)
                runBvvWithTracing(filePaths);
            else if (tracer)
                runBdvWithTracing(filePaths);
            else if (threeD)
                runBvv(filePaths);
            else
                runBdv(filePaths);
            SNTUtils.setIsLoading(false);
        } catch (final Exception e) {
            SNTUtils.setIsLoading(false); // hide splashscreen behind error dialog
            error("An error occurred: " + e.getMessage());
        }
    }

    /**
     * Converts an (possibly user-typed) {@link File} parameter back to the string form
     * {@link SpimDataUtils#resolvePathToSource(String)} expects. {@code File#getAbsolutePath()} mangles
     * remote URLs (e.g. {@code https://.../dataset.ome.zarr}) by prepending the current working
     * directory, since a URL scheme isn't a recognized absolute-path prefix. Even {@code File#getPath()}
     * isn't a clean escape hatch here: by the time the typed text becomes a {@code File} at all, its own
     * constructor has already collapsed the URL's "://" down to ":/" (consecutive slashes are merged),
     * so that has to be repaired too (see {@link SpimDataUtils#restoreUrlScheme(String)}).
     */
    private static String toPathString(final File f) {
        return SpimDataUtils.isRemoteUrl(f.getPath())
                ? SpimDataUtils.restoreUrlScheme(f.getPath())
                : f.getAbsolutePath();
    }

    /** True if path is an existing .n5/.zarr directory, or a remote URL to one. */
    private static boolean isN5OrZarrDir(final String path) {
        if (SpimDataUtils.isRemoteUrl(path)) {
            final String lower = path.toLowerCase();
            return lower.endsWith(".n5") || lower.endsWith(".n5/") || lower.endsWith(".zarr") || lower.endsWith(".zarr/");
        }
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
                    // Same non-pyramidal-dataset risk as resolveBvvSources(), only relevant for BVV
                    if (viewer instanceof Bvv bvv) {
                        if (confirmPyramidOrAbort(n5Sources, n5ZarrDir)) bvv.show(n5Sources);
                    } else if (viewer instanceof Bdv bdv) {
                        bdv.show(n5Sources);
                    }
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
        if (resolved == null) return; // user chose Abort (oversized image or non-pyramidal dataset)
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
        // Resolve the primary volume (img1File, i.e. filePaths[0]) exactly once: startTracingSNT() needs
        // it for calibration/A* wiring, and the viewer needs the very same source. Doing this before
        // resolveBvvSources() lets that primary resolution be reused there instead of re-running N5/Zarr
        // discovery a second time for an unchanged path
        final TracingSetup setup = startTracingSNT(img1File);
        final ResolvedSources resolved = resolveBvvSources(filePaths, maxTexSize, setup.primarySource());
        if (resolved == null) return; // user chose Abort (oversized image or non-pyramidal dataset)
        final Bvv bvv = new Bvv(setup.snt());
        addSourcesToBvv(bvv, resolved);
        loadReconstructions(bvv);
        loadMarkers(bvv);
    }

    /** Holds the outcome of {@link #resolveBvvSources(String[], int, Object)}. */
    private record ResolvedSources(List<Object> sources, List<String> deferredPaths) {}

    /** {@link #resolveBvvSources(String[], int, Object)} for callers with no already-resolved primary source. */
    private ResolvedSources resolveBvvSources(final String[] filePaths, final int maxTexSize) {
        return resolveBvvSources(filePaths, maxTexSize, null);
    }

    /**
     * Resolves each path to a BVV-displayable source (an {@link ImgPlus}, {@link AbstractSpimData},
     * or {@link SpimDataUtils.N5Sources}), enforcing the GPU's 3D texture size limit along the way.
     * N5/Zarr directories that can't be auto-discovered headlessly are collected into {@code
     * deferredPaths} instead, to be resolved later via the interactive {@link #datasetDialog}.
     *
     * @param cachedPrimarySource {@code filePaths[0]}'s source, if already resolved elsewhere (see
     *                            {@link #startTracingSNT}), to avoid resolving it a second time; or
     *                            {@code null} to resolve every path here
     * @return the resolved sources, or {@code null} if the user chose to Abort when prompted about
     *         an oversized image (see {@link #handleOversizedImage}) or a non-pyramidal N5/Zarr
     *         source (see {@link #confirmPyramidOrAbort})
     */
    private ResolvedSources resolveBvvSources(final String[] filePaths, final int maxTexSize,
                                               final Object cachedPrimarySource) {
        final List<Object> sources = new ArrayList<>();
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (int i = 0; i < filePaths.length; i++) {
            final String path = filePaths[i];
            final Object source;
            try {
                source = (i == 0 && cachedPrimarySource != null) ? cachedPrimarySource
                        : SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BVV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof SpimDataUtils.N5Sources n5 && !confirmPyramidOrAbort(n5, path)) {
                return null; // user chose Abort
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
     * Outcome of {@link #startTracingSNT(File)}: the SNT instance it started, plus whatever
     * {@code primaryVolume} resolved to along the way (an {@link ImgPlus}, {@link AbstractSpimData},
     * {@link SpimDataUtils.N5Sources}, or {@code null} if resolution failed). Callers that also need
     * to display {@code primaryVolume} themselves (i.e. {@code filePaths[0]}) should reuse
     * {@link #primarySource} rather than resolving the same path a second time -- for a remote
     * (S3/HTTPS) container, N5/Zarr discovery is a real network round-trip, not a cheap local check.
     */
    private record TracingSetup(SNT snt, Object primarySource) {}

    /**
     * Starts a full SNT instance (SNTUI window included) without displaying an ImagePlus window;
     * the BVV/BDV viewer being opened is the only display, and SNT exists here for its Path Manager
     * (and, when possible, A* search). Shared by {@link #runBvvWithTracing(String[])} and
     * {@link #runBdvWithTracing(String[])}.
     * <p>
     * When {@code primaryVolume} resolves headlessly to a plain {@link ImgPlus} (the common case:
     * TIFF, and often N5/Zarr too), it is wired directly into SNT via the {@code SNT(ImgPlus)}
     * "Tracing Mode" constructor: this sets SNT's own {@code ctSlice3d} (what A* search reads via
     * {@code getLoadedData()}) straight from the same object the viewer renders, without ever
     * assembling or showing the classic 2D tracing canvas ({@code setFieldsFromImgPlus} sets
     * {@code xy = null}). {@link SNT#accessToValidImageData()} treats {@code ctSlice3d != null} as
     * sufficient on its own
     * <p>
     * Since {@code ctSlice3d} only needs to be a {@code RandomAccessibleInterval}, this also works
     * transparently when the resolved {@code ImgPlus} is lazily backed (e.g. N5/Zarr): A* search's
     * random-access reads trigger on-demand chunk loading the same way the viewer's own rendering does
     * <p>
     * Deliberately uses the <i>original</i>, full-resolution {@code ImgPlus} here, not whatever
     * (possibly downsampled) version {@link #resolveBvvSources} produces for BVV specifically (BDV has
     * no such downsampling step to begin with): SNT's A* search is plain CPU-side iteration with no GPU
     * texture-size constraint, so there's no reason to degrade it to match a viewer's rendering limits.
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
    private TracingSetup startTracingSNT(final File primaryVolume) {
        GuiUtils.setLookAndFeel(); // needs to be called here to set L&F of image's contextual menu!?
        if (getContext() == null && ij.IJ.getInstance() == null) {
            new net.imagej.ImageJ().ui().showUI();
        }
        Object primarySource = null; // ImgPlus, AbstractSpimData, or SpimDataUtils.N5Sources; null if resolution failed
        ImgPlus<?> primaryImgPlus = null;
        Object primaryFallbackSource = null; // AbstractSpimData or SpimDataUtils.N5Sources, for calibration only
        if (primaryVolume != null) {
            try {
                primarySource = SpimDataUtils.resolvePathToSource(toPathString(primaryVolume));
                if (primarySource instanceof ImgPlus<?> img) {
                    primaryImgPlus = img;
                } else {
                    SNTUtils.log("SNT: primary volume resolves to " + primarySource.getClass().getSimpleName()
                            + " (not a plain ImgPlus); will attempt to wire dimensions/pixel data from it directly");
                    primaryFallbackSource = primarySource;
                }
            } catch (final Exception e) {
                SNTUtils.log("SNT: could not resolve '" + primaryVolume.getName() + "' for SNT (" + e.getMessage()
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
        return new TracingSetup(snt, primarySource);
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
     * Source}, in the volume's own pixel grid. For the {@link AbstractSpimData} branch, any
     * registration transform beyond plain size/calibration (e.g. a non-identity {@code
     * ViewRegistration}, or BVV's own manual-transform mode) is <em>not</em> applied, so traced
     * world coordinates could diverge from what's rendered if such a transform is present. The
     * {@link SpimDataUtils.N5Sources} branch does compensate for its own {@code Source}'s
     * translation (see {@link #applyWorldOriginOffset}), since that is the fallback path actually
     * exercised for typical headless N5/Zarr loading.
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
            } else if (source instanceof SpimDataUtils.N5Sources n5Sources && !n5Sources.sources().isEmpty()) {
                final var spimSource = n5Sources.sources().getFirst().getSpimSource();
                final var itvl = spimSource.getSource(0, 0); // timepoint 0, full-resolution level 0
                final var vd = spimSource.getVoxelDimensions();
                snt.setImageMetadata((int) itvl.dimension(0), (int) itvl.dimension(1), (int) itvl.dimension(2),
                        (vd == null) ? 0 : vd.dimension(0), (vd == null) ? 0 : vd.dimension(1),
                        (vd == null) ? 0 : vd.dimension(2), (vd == null) ? null : vd.unit());
                snt.setImageData(itvl); // same lazily-loaded interval backing BVV's own rendering
                applyWorldOriginOffset(snt, spimSource);
            }
        } catch (final Exception e) {
            SNTUtils.log("SNT: could not extract calibration for tracing (" + e.getMessage() + ")");
        }
    }

    /**
     * Reads {@code spimSource}'s own {@code sourceTransform} (timepoint 0, full-resolution level 0)
     * and, if it carries a translation, records it on {@code snt} via {@link SNT#setWorldOriginOffset}
     * so that {@code GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny} can shift traced {@link Tree}s
     * to compensate.
     * <p>
     * {@link #applyFallbackCalibration} above wires {@code snt}'s dimensions/spacing/pixel data
     * directly from {@code spimSource.getSource(0, 0)}/{@code getVoxelDimensions()}, i.e. in that
     * Source's own raw voxel grid, with no notion of where that grid sits in world space. A
     * {@code sourceTransform} translation (common for N5/Zarr multiscale or BDV-registered
     * datasets - the same transform {@code Bvv} reads to render/align this Source correctly) would
     * otherwise be silently dropped, producing traced coordinates that are internally consistent
     * (correct shape/scale) but uniformly shifted relative to the volume's true position.
     * <p>
     * Only the translation component is applied (a rigid shift); a non-identity rotation/shear in
     * {@code sourceTransform} is not handled here and would require transforming the traced
     * geometry itself, not just its origin.
     */
    private static void applyWorldOriginOffset(final SNT snt, final bdv.viewer.Source<?> spimSource) {
        final net.imglib2.realtransform.AffineTransform3D t = new net.imglib2.realtransform.AffineTransform3D();
        spimSource.getSourceTransform(0, 0, t);
        final double dx = t.get(0, 3);
        final double dy = t.get(1, 3);
        final double dz = t.get(2, 3);
        if (dx != 0 || dy != 0 || dz != 0) {
            snt.setWorldOriginOffset(dx, dy, dz);
            SNTUtils.log("SNT: N5Sources sourceTransform carries a translation of (" + dx + ", " + dy + ", " + dz
                    + ") in calibrated units; will be applied to traced Trees to correct for it. "
                    + "(Any rotation/shear in the same transform is NOT compensated for.)");
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

    /** BDV counterpart to runBvvWithTracing(final String[] filePaths); */
    private void runBdvWithTracing(final String[] filePaths) {
        final TracingSetup setup = startTracingSNT(img1File);
        final Bdv bdv = new Bdv(setup.snt());
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (int i = 0; i < filePaths.length; i++) {
            final String path = filePaths[i];
            final Object source;
            try {
                // Reuse filePaths[0]'s already-resolved source (see startTracingSNT) instead of
                // re-running N5/Zarr discovery a second time for the same container.
                source = (i == 0 && setup.primarySource() != null) ? setup.primarySource()
                        : SpimDataUtils.resolvePathToSource(path);
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
        // Only pop open the standalone floating "Markers" dialog when this viewer has no SNT/SNTUI at all
        final boolean standalone = viewer.getSNT() == null || viewer.getSNT().getUI() == null;
        final String path = toPathString(markerFile);
        if (SpimDataUtils.isRemoteUrl(path)) {
            // fileAvailable() below only makes sense for local files: there is no cheap way to check
            // a remote URL's existence without a network round-trip, so just attempt the load directly
            // and let BookmarkManager#load(String) report a clear error if the URL turns out to be bad
            if (standalone) viewer.getMarkerManager().showPanel();
            viewer.getMarkerManager().load(path);
            return;
        }
        if (!SNTUtils.fileAvailable(markerFile)) {
            error(String.format("%s does not exist or is not available.", markerFile.getName()));
            return;
        }
        if (standalone) viewer.getMarkerManager().showPanel();
        viewer.getMarkerManager().load(markerFile); // error if invalid file
    }

    /**
     * Loads reconstruction files, if any were specified: rendered in the viewer's overlay (same as before) and also
     * registered with {@link PathAndFillManager} so they show up as regular, editable Paths in the Path Manager
     * <p>
     * Coordinates are taken at face value, with no {@link SNT#getWorldOriginOffset() world-origin offset} correction
     * applied: unlike a freshly-traced {@link Tree} (which is known to be in the fallback-loaded source's own raw
     * pixel*spacing frame, see {@code GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny}), an externally-supplied
     * SWC/reconstruction file carries no standardized way to say whether it needs that same correction or not.
     * Users who do need to correct for the offset should use the interactive "Import > SWC..." command instead
     * (once tracing has started), which supports specifying one manually (see {@code SWCImportDialog})
     */
    private void loadReconstructions(final AbstractBigViewer viewer) {
        if (recFiles == null) return;
        final String path = toPathString(recFiles);
        final Collection<Tree> trees = new ArrayList<>();
        if (SpimDataUtils.isRemoteUrl(path)) {
            // fileAvailable()/getReconstructionFiles() below are local-filesystem-only checks. Tree.listFromFile()
            // already knows how to handle a remote URL directly (a single reconstruction file is streamed, a .zip is
            // downloaded/extracted and treated as a irectory), so route straight through it instead for this case
            try {
                trees.addAll(Tree.listFromFile(path));
            } catch (final IllegalArgumentException e) {
                error("Could not load reconstructions from " + path + ": " + e.getMessage());
                return;
            }
            if (trees.isEmpty()) {
                error(String.format("No reconstructions found at %s.", path));
                return;
            }
            SNTUtils.log(String.format("Loading %d reconstruction(s) from %s.", trees.size(), path));
        } else {
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
            for (final File f : files) {
                try {
                    trees.addAll(Tree.listFromFile(f.getAbsolutePath()));
                } catch (final Exception ex) {
                    SNTUtils.log("Could not load " + f.getName() + ": " + ex.getMessage());
                }
            }
            if (trees.isEmpty()) {
                error(String.format("No reconstructions found in %s.", recFiles.getName()));
                return;
            }
        }
        final org.scijava.util.ColorRGB[] colors = SNTColor.getDistinctColors(trees.size());
        int i = 0;
        for (final Tree tree : trees) tree.setColor(colors[i++]);
        viewer.add(trees); // renders in the viewer's overlay
        if (viewer.getSNT() != null) { // tracing capabilities present
            final PathAndFillManager pafm = viewer.getSNT().getPathAndFillManager();
            trees.forEach(tree -> pafm.addTree(tree, tree.getLabel())); // registers as editable Paths
            // addTree() (unlike addTrees()) intentionally skips this check (see its javadoc), so trigger
            // it explicitly here. If a SNTUI exists, reuse its own persistent-warning dialog (same one
            // shown by traditional-mode reconstruction imports) instead of a one-off dialog of our own
            pafm.validateImageDimensions();
            if (viewer.getSNT().getUI() != null) {
                try {
                    viewer.getSNT().getUI().runCommand("validateImgDimensions");
                } catch (final IllegalArgumentException ignored) {
                    // command unavailable in the current UI state; RESIZE_REQUIRED (set above, if
                    // applicable) remains armed and will surface on the next reconstruction import
                }
            }
        } else {
            // No SNT/PathAndFillManager in this case (plain, non-tracing viewer): compare directly
            // against the viewer's own loaded volume instead
            warnIfOutOfBounds(viewer, trees);
        }
    }

    /**
     * Warns (once, with a permanent opt-out) if {@code trees} fall at least partially outside
     * {@code viewer}'s loaded volume -- typically a sign that the reconstruction and image files
     * specified are not a matching pair. Only used for the plain (non-tracing) viewer case; when
     * tracing capabilities are present, {@link PathAndFillManager#validateImageDimensions()} plus
     * {@code SNTUI}'s own dialog (see {@link #loadReconstructions}) is used instead
     */
    private void warnIfOutOfBounds(final AbstractBigViewer viewer, final Collection<Tree> trees) {
        final BoundingBox volumeBox = viewer.getBoundingBox();
        if (volumeBox == null) return;
        BoundingBox treesBox = null;
        for (final Tree tree : trees) {
            final BoundingBox tb = tree.getBoundingBox(true);
            if (tb == null) continue;
            if (treesBox == null) treesBox = tb.clone();
            else treesBox.combine(tb);
        }
        if (treesBox == null || volumeBox.contains(treesBox)
                || prefService.getBoolean(BigDataLoaderCmd.class, "oob-skipnag", false)) {
            return;
        }
        final Boolean skipNag = new GuiUtils(null).getPersistentWarning(
                "The loaded reconstruction(s) fall (at least partially) outside the loaded volume. "
                        + "This typically indicates the reconstruction and image are not a matching pair.",
                "Reconstruction Outside Image Bounds");
        if (skipNag != null) prefService.put(BigDataLoaderCmd.class, "oob-skipnag", skipNag);
    }

    /**
     * Warns before loading a non-pyramidal (single resolution level) N5/Zarr source into BVV. BVV's
     * GPU brick-cache/raycasting renderer assumes a proper multi-resolution pyramid; a single-level
     * dataset crashes BVV's {@code VolumeRenderer}!? (NB: BDV needs no pyramid)
     *
     * @param n5Sources the resolved N5/Zarr source(s)
     * @param path      the original path, used only for the warning message
     * @return true if there is a pyramid (nothing to warn about) or the user chose to continue anyway;
     *         false if the user chose to abort
     */
    private static boolean confirmPyramidOrAbort(final SpimDataUtils.N5Sources n5Sources, final String path) {
        if (n5Sources.sources().isEmpty()) return true; // nothing to check; downstream logic already handles this
        final int nLevels = n5Sources.sources().getFirst().getSpimSource().getNumMipmapLevels();
        if (nLevels > 1) return true;
        final String message = String.format(
                "'%s' has no multi-resolution pyramid (a single resolution level only). Big Volume "
                        + "Viewer's 3D renderer relies on such a pyramid and has been known to fail or "
                        + "become unresponsive without one. Continue loading it anyway?",
                new File(path).getName());
        // The loading splash screen (SNTUtils#setIsLoading(true), running since run() started) is an
        // always-on-top window that can end up rendered above this confirmation
        SNTUtils.setIsLoading(false);
        try {
            return new GuiUtils(null).getConfirmation(message, "Non-pyramidal N5/Zarr Dataset",
                    "Continue Anyway", "Cancel");
        } finally {
            SNTUtils.setIsLoading(true);
        }
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
        // See confirmPyramidOrAbort(): the loading splash screen can end up rendered on top of this
        // dialog, so hide it for the duration of the prompt and restore it afterward
        SNTUtils.setIsLoading(false);
        final String choice;
        try {
            choice = new GuiUtils(null).getChoice(message, "BVV: Volume Too Large",
                    new String[]{DOWNSAMPLE, CONVERT, ABORT}, DOWNSAMPLE);
        } finally {
            SNTUtils.setIsLoading(true);
        }

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
        GuiUtils.errorPrompt(msg, true);
        cancel("");
    }

}
