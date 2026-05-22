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

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

import java.io.File;
import java.util.List;

/**
 * Non-interactive command for file-based multi-soma GWDT autotracing with
 * support for single images or batch processing of a directory. Traced trees
 * are exported to disk as SWC files.
 *
 * @author Tiago Ferreira
 * @see GWDTMultiSomaCommonCmd
 * @see GWDTMultiSomaCmd
 * @see GWDTTracerFileCmd
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotracing Multiple Cells (GWDT)...")
public class GWDTMultiSomaFileCmd extends GWDTMultiSomaCommonCmd {

    @Parameter(label = "Export directory", style = FileWidget.DIRECTORY_STYLE,
            description = "<HTML>Directory for saving traced trees as SWC files.")
    private File exportDir;

    private boolean batchMode;
    private boolean batchImageFailed;

    @SuppressWarnings("unused")
    private void init() {
        initForFile();
        initMultiSoma();
        final boolean guiExists = SNTUtils.getInstance() != null && SNTUtils.getInstance().getUI() != null;
        final boolean pathsExistInGui = guiExists && !SNTUtils.getInstance().getPathAndFillManager().getPaths().isEmpty();
        if (!guiExists || pathsExistInGui) {
            resolveInput("afterTracingChoice");
            afterTracingChoice = AFTER_DO_NOTHING;
        }
    }

    @Override
    protected boolean isFileMode() {
        return true;
    }

    @Override
    public void run() {
        if (isCanceled()) return;
        if (exportDir == null || !exportDir.canWrite()) {
            error("Export directory is not valid, does not exist, or cannot be written to.");
            return;
        }
        if (imgFileChoice != null && imgFileChoice.isDirectory()) {
            runBatch(imgFileChoice);
        } else {
            runMultiSoma();
        }
    }

    private void runBatch(final File inputDir) {
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

            imgFileChoice = file;
            batchImageFailed = false;
            try {
                runMultiSoma();
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
    protected void handleTracedTrees(final List<Tree> trees) {
        // Export to disk
        int saved = 0;
        for (int i = 0; i < trees.size(); i++) {
            final Tree tree = trees.get(i);
            final String baseName = (imgFileChoice != null)
                    ? SNTUtils.stripExtension(imgFileChoice.getName())
                    : "autotraced";
            final String fileName = baseName + "_cell" + (i + 1) + ".swc";
            final File outFile = new File(exportDir, fileName);
            if (tree.saveAsSWC(outFile.getAbsolutePath())) {
                saved++;
            } else {
                SNTUtils.log("Failed to save: " + outFile.getAbsolutePath());
            }
        }
        SNTUtils.log("Exported " + saved + " of " + trees.size() + " tree(s) to " + exportDir.getAbsolutePath());

        if (!AFTER_DO_NOTHING.equals(afterTracingChoice)) {
            super.handleTracedTrees(trees);
        }
    }
}
