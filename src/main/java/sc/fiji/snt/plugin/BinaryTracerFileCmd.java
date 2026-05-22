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

package sc.fiji.snt.plugin;

import ij.gui.Roi;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

import java.io.File;
import java.util.List;

/**
 * Non-interactive command for batch {@link sc.fiji.snt.tracing.auto.BinaryTracer}
 * autotracing of all images in a directory. Each image is processed independently
 * and traced trees are exported to disk as SWC files.
 * <p>
 * Failures on individual images are logged but do not abort the batch. ROI-based
 * root detection is supported when images contain embedded ROIs (e.g., from TIFF
 * headers).
 *
 * @author Tiago Ferreira
 * @see BinaryTracerCommonCmd
 * @see BinaryTracerCmd
 */
@Plugin(type = Command.class, initializer = "init", label = "Automated Tracing: Tree(s) from Segmented Image File(s)...")
public class BinaryTracerFileCmd extends BinaryTracerCommonCmd {

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER_OUTPUT_SECTION = "<HTML><b>V. Output";

    @Parameter(label = "Export directory", style = "directory",
            description = "<HTML>Directory for saving traced trees as SWC files.")
    private File exportDir;

    private boolean batchMode;
    private boolean batchImageFailed;

    @SuppressWarnings("unused")
    private void init() {
        initForFile();
        // Hide interactive-only options
        resolveInput("HEADER5");
        resolveInput("debugMode"); // always on in batch
        resolveInput("afterTracingChoice"); // trees go to disk only
        afterTracingChoice = AFTER_DO_NOTHING;
    }

    @Override
    protected boolean isFileMode() {
        return true;
    }

    @Override
    public void run() {
        if (isCanceled()) return;
        if (inputDir == null || !inputDir.isDirectory() || !inputDir.canRead()) {
            error("Input directory is not valid, does not exist, or cannot be read from.");
            return;
        }
        if (exportDir == null || !exportDir.canWrite()) {
            error("Export directory is not valid, does not exist, or cannot be written to.");
            return;
        }
        runBatch();
    }

    private void runBatch() {
        final File[] files = inputDir.listFiles();
        if (files == null || files.length == 0) {
            error("Directory is empty: " + inputDir.getAbsolutePath());
            return;
        }

        batchMode = true;
        final boolean existingDebugMode = SNTUtils.isDebugMode();
        SNTUtils.setDebugMode(true);
        int processed = 0;
        int failed = 0;
        for (int i = 0; i < files.length; i++) {
            final File file = files[i];
            if (file.isDirectory() || file.isHidden()) continue;

            final String name = file.getName();
            SNTUtils.log(String.format("Batch [%d/%d]: %s", i + 1, files.length, name));
            status(String.format("Processing %d/%d: %s", i + 1, files.length, name), false);

            maskImgFileBeingProcessed = file;
            batchImageFailed = false;
            abortRun = false;
            try {
                runCommand();
                if (batchImageFailed) {
                    failed++;
                } else {
                    processed++;
                }
            } catch (final Exception ex) {
                SNTUtils.log("Failed: " + name + " — " + ex.getMessage());
                failed++;
            }
        }
        batchMode = false;

        final String summary = String.format("Batch complete: %d processed, %d failed out of %d files.",
                processed, failed, processed + failed);
        SNTUtils.log(summary);
        status(summary, true);
        SNTUtils.setDebugMode(existingDebugMode);
    }

    @Override
    protected void error(final String msg) {
        if (batchMode) {
            SNTUtils.log("ERROR: " + msg);
            batchImageFailed = true;
        } else {
            super.error(msg);
        }
    }

    @Override
    protected boolean validateBeforeTracing(final Roi roi, final boolean inferRootFromRoi,
                                            final boolean isSame, final boolean isSegmented,
                                            final boolean isValidOrigImg, final boolean isSameDim,
                                            final boolean isCompatible, final boolean isValidConnectDist,
                                            final boolean isValidRoi) {
        // In file/batch mode, log warnings but never block
        if (!isValidRoi && inferRootFromRoi) {
            SNTUtils.log("No ROI found in " + maskImgFileBeingProcessed.getName() + "; falling back to ROI_UNSET");
            rootChoice = ROI_UNSET;
        }
        if (!isSegmented) {
            SNTUtils.log("Info: Image is not thresholded: Non-zero intensities will be used as foreground");
        }
        if (!isValidConnectDist && connectComponents) {
            SNTUtils.log("Warning: Max. connection distance must be > 0. Connection of components will be disabled");
        }
        connectComponents = connectComponents && isValidConnectDist;
        return true;
    }

    @Override
    protected void handleTracedTrees(final List<Tree> trees) {
        int saved = 0;
        for (int i = 0; i < trees.size(); i++) {
            final Tree tree = trees.get(i);
            final String baseName = (maskImgFileBeingProcessed != null)
                    ? SNTUtils.stripExtension(maskImgFileBeingProcessed.getName())
                    : "autotraced";
            final String fileName = baseName + ((trees.size() > 1) ? "_" + (i + 1) : "") + ".swc";
            final File outFile = new File(exportDir, fileName);
            if (tree.saveAsSWC(outFile.getAbsolutePath())) {
                saved++;
            } else {
                SNTUtils.log("Failed to save: " + outFile.getAbsolutePath());
            }
        }
        SNTUtils.log("Exported " + saved + " of " + trees.size() + " tree(s) to " + exportDir.getAbsolutePath());
    }
}
