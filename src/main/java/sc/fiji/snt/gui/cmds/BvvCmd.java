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
import org.jspecify.annotations.NonNull;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;
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
@Plugin(type = Command.class, label = "BDV/BVV")
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

    @Parameter(label = "Viewer type", choices = {"2D: Big Data Viewer (BDV)", "3D: Big Volume Viewer (BVV)"},
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
        SNTUtils.setIsLoading(true);
        try {
            if (threeD)
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
     * Fallback UI for when {@link SpimDataUtils#resolvePathToSource(String)}
     * cannot auto-discover a dataset in an N5/Zarr container on its own (e.g.
     * an ambiguous or unusually structured container). Lets the user pick a
     * dataset interactively.
     * <p>
     * Non-blocking: {@code DatasetSelectorDialog} shows a plain (non-modal)
     * {@code JFrame}, so this method returns immediately, before the user has
     * interacted with it. The resolved source is only added to {@code viewer}
     * later, from the dialog's OK callback, once the user makes a selection.
     *
     * @param n5ZarrDir the {@code .n5} or {@code .zarr} directory
     * @param viewer    the already-created {@link Bvv} or {@link Bdv} to add
     *                  the user's eventual selection to
     */
    private void datasetDialog(final String n5ZarrDir, final AbstractBigViewer viewer) {
        final ExecutorService exec = Executors.newFixedThreadPool(SNTPrefs.getThreads());
        final DatasetSelectorDialog datasetDialog = getDatasetSelectorDialog(n5ZarrDir, exec);
        SwingUtilities.invokeLater(() -> {
            datasetDialog.run(selection -> {
                try {
                    final SpimDataUtils.N5Sources n5Sources =
                            SpimDataUtils.resolveN5Selection(selection, new File(n5ZarrDir).getName());
                    if (viewer instanceof Bvv bvv) bvv.show(n5Sources);
                    else if (viewer instanceof Bdv bdv) bdv.show(n5Sources);
                } catch (final Exception e) {
                    GuiUtils.errorPrompt("Could not open '" + n5ZarrDir + "': " + e.getMessage());
                } finally {
                    exec.shutdown();
                }
            });
            datasetDialog.openContainer(n5ZarrDir); // run() calls buildDialog() synchronously before returning
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
                SNTUtils.setIsLoading(false);
                final Object handled = handleOversizedImage(img, maxTexSize, path);
                if (handled == null) return; // user chose Abort
                sources.add(handled);
            } else {
                sources.add(source);
            }
        }
        final Bvv bvv = new Bvv();
        for (final Object source : sources) {
            if (source instanceof AbstractSpimData<?> spim) {
                bvv.show(spim);
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                bvv.show(n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bvv.show((ImgPlus) img);
            }
        }
        for (final String path : deferredPaths)
            datasetDialog(path, bvv);
        loadReconstructions(bvv);
        loadMarkers(bvv);
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
