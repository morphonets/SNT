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

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.Bvv;

import java.io.File;
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
            Supports standard formats (TIFF) and big data formats with lazy loading
            (N5, Zarr, HDF5, OME-TIFF, IMS, BDV .xml). Large datasets are opened
            virtually without loading the entire file into memory.""";
    @Parameter(label = "Main volume", description = "Primary image volume.\n"+ TOOLTIP)
    File img1File;

    @Parameter(required = false, label = "Secondary volume (optional)", description = "Optional image volume.\n"+ TOOLTIP)
    File img2File;

    @Parameter(required = false, label = "Reconstruction files (optional)",
            description = "Either a single file (TRACES, SWC, JSON), or a folder containing multiple reconstruction files.")
    File recFiles;

    @Override
    public void run() {
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
            final Bvv bvv = Bvv.open(filePaths);
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
        }
    }

    private void error(final String msg) {
        GuiUtils.errorPrompt(msg);
        cancel("");
    }

}
