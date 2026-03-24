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
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.viewer.Bvv;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Convenience command for starting a standalone Bvv instance.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "BVV")
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

    @Parameter(label = "Main volume", description = "Primary image volume.\n"+ TOOLTIP)
    File img1File;

    @Parameter(required = false, label = "Secondary volume (optional)", description = "Optional image volume.\n"+ TOOLTIP)
    File img2File;

    @Parameter(required = false, label = "Reconstruction files (optional)",
            description = "Either a single file (TRACES, SWC, JSON), or a folder containing multiple reconstruction files.")
    File recFiles;

    @Override
    public void run() {
        SNTUtils.setDebugMode(true);
        if (img1File == null) {
            error("Main volume is required.");
            return;
        }
        try {
            final String[] filePaths = Stream.of(img1File, img2File)
                    .filter(Objects::nonNull)
                    .map(File::getAbsolutePath)
                    .toArray(String[]::new);
            if (filePaths.length == 0) {
                error("No volume files specified.");
                return;
            }
            SNTUtils.setIsLoading(true);

            // Resolve sources and check texture limits before creating BVV
            final int maxTexSize = queryMaxTexture3DSize();
            SNTUtils.log("BVV: GL_MAX_3D_TEXTURE_SIZE = " + maxTexSize);
            final List<Object> resolvedSources = new ArrayList<>();
            for (final String path : filePaths) {
                final Object source = SpimDataUtils.resolvePathToSource(path);
                if (source instanceof ImgPlus<?> img && ImgUtils.exceedsDimension(img, maxTexSize)) {
                    SNTUtils.setIsLoading(false);
                    final Object handled = handleOversizedImage(img, maxTexSize, path);
                    if (handled == null) return; // user chose Abort
                    resolvedSources.add(handled);
                } else {
                    resolvedSources.add(source);
                }
            }

            // All sources are ready: create BVV and show them
            final Bvv bvv = new Bvv();
            for (final Object source : resolvedSources) {
                if (source instanceof AbstractSpimData)
                    bvv.show((AbstractSpimData<?>) source);
                else {
                    //noinspection unchecked,rawtypes
                    bvv.show((ImgPlus) source);
                }
            }

            if (recFiles == null) return;
            if (!recFiles.exists()) {
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
            bvv.add(files);
        } catch (final Exception e) {
            error("An error occurred: " + e.getMessage());
        } finally {
            SNTUtils.setIsLoading(false);
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
