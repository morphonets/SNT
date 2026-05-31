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

import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.util.ImgUtils;

import java.io.File;
import java.util.List;

/**
 * Non-interactive command for file-based GWDT autotracing with support for
 * single images or batch processing of an entire directory. Traced trees can
 * be exported to disk as SWC files.
 * <p>
 * When {@code imgFileChoice} points to a directory, each image is processed
 * independently with automatic soma detection. Failures on individual images
 * are logged but do not abort the batch operation.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see GWDTTracerCmd
 */
@Plugin(type = Command.class, initializer = "init", label="Autotracing Grayscale Data (GWDT)...")
public class GWDTTracerFileCmd extends GWDTTracerCommonCmd {

    @Parameter(label = "Export directory", style = "directory",
            description = "<HTML>Directory for saving traced trees as SWC files.")
    private File exportDir;

    @Parameter(label = "Secondary image suffix", required = false,
            description = "<HTML>Suffix used to locate a paired secondary image for each input.<br>" +
                    "Only used when <i>Score map filter</i> is set to '" + SCORE_MAP_OTHER + "'.<br>" +
                    "For input <i>foo.tif</i> and suffix <i>_probmap</i>, a sibling file<br>" +
                    "<i>foo_probmap.*</i> in the same directory is loaded as the score map.<br>" +
                    "If no match is found, the file cannot be opened, or its dimensions<br>" +
                    "do not match the input, score mapping is silently disabled for that<br>" +
                    "input and a warning is logged (the trace still runs).")
    private String secondaryImageSuffix = "_probmap";

    private boolean batchMode;
    private boolean batchImageFailed;

    @SuppressWarnings("unused")
    private void init() {
        initForFile();
        final boolean guiExists = SNTUtils.getInstance() != null && SNTUtils.getInstance().getUI() != null;
        final boolean pathsExistInGui = guiExists && !SNTUtils.getInstance().getPathAndFillManager().getPaths().isEmpty();
        if (!guiExists || pathsExistInGui) {
            // Allow to load results only when GUI exists w/ empty PathManager AND user picks an after-tracing action
            resolveInput("afterTracingChoice");
            afterTracingChoice = AFTER_DO_NOTHING;
        }
        // Apply initial visibility of secondaryImageSuffix based on current scoreMapFilter
        scoreMapFilterCallback();
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
            runBatch(imgFileChoice); // parse directory
        } else {
            runCommand(); // parse single file
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
        SNTUtils.setDebugMode(true); // or SNTUtils.log() calls won't occur
        int processed = 0;
        int failed = 0;
        final boolean skipSecondaries = SCORE_MAP_OTHER.equals(scoreMapFilter)
                && secondaryImageSuffix != null && !secondaryImageSuffix.isBlank();
        for (int i = 0; i < files.length; i++) {
            final File file = files[i];
            if (file.isDirectory() || file.isHidden()) continue;

            final String name = file.getName();
            if (skipSecondaries && SNTUtils.stripExtension(name).endsWith(secondaryImageSuffix)) {
                SNTUtils.log(String.format("Batch [%d/%d]: skipping likely secondary image %s", i + 1, files.length, name));
                continue;
            }
            SNTUtils.log(String.format("Batch [%d/%d]: %s", i + 1, files.length, name));
            status(String.format("Processing %d/%d: %s", i + 1, files.length, name), false);

            imgFileChoice = file;
            batchImageFailed = false;
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
            // In batch mode, log the error and continue to the next image
            // rather than showing a dialog or canceling the command
            SNTUtils.log("ERROR: " + msg);
            batchImageFailed = true;
        } else {
            super.error(msg);
        }
    }

    @Override
    protected void handleTracedTrees(final List<Tree> trees) {
        // Export to disk (exportDir is validated in run())
        int saved = 0;
        for (int i = 0; i < trees.size(); i++) {
            final Tree tree = trees.get(i);
            final String baseName = (imgFileChoice != null)
                    ? SNTUtils.stripExtension(imgFileChoice.getName())
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

        if (!AFTER_DO_NOTHING.equals(afterTracingChoice)) {  // Optionally load into SNT as well
            super.handleTracedTrees(trees);
        }
    }

    /**
     * Locates a paired secondary image on disk via {@link #secondaryImageSuffix}.
     * The expected file is {@code <inputBase><suffix>.<any-ext>} in the same
     * directory as {@link #imgFileChoice}.
     * <p>
     * Failures (missing file, unreadable image, dimension mismatch, exception)
     * are logged and result in score mapping being <b>disabled</b> for the
     * current input; the trace still proceeds. This keeps batch runs robust to
     * partial coverage of secondary maps.
     */
    @Override
    protected boolean configureSecondaryScoreMap(final AbstractGWDTTracer<?> tracer) {
        final File secFile = findSecondarySibling(imgFileChoice, secondaryImageSuffix);
        if (secFile == null) {
            SNTUtils.log("No secondary image matching suffix '" + secondaryImageSuffix +
                    "' found for " + (imgFileChoice != null ? imgFileChoice.getName() : "input") +
                    ". Disabling score map.");
            tracer.setScoreMapEnabled(false);
            return true;
        }
        try {
            final ImgPlus<?> secImg = ImgUtils.open(secFile);
            if (secImg == null) {
                SNTUtils.log("Could not open secondary image '" + secFile.getName() +
                        "'. Disabling score map.");
                tracer.setScoreMapEnabled(false);
                return true;
            }
            if (ImgUtils.isRGB(secImg) || ImgUtils.isMultiChannelRGB(secImg)) {
                SNTUtils.log("Secondary image '" + secFile.getName() + "' is RGB; only " +
                        "grayscale score maps are supported. Disabling score map.");
                tracer.setScoreMapEnabled(false);
                return true;
            }
            if (!dimensionsCompatible(secImg, chosenImp)) {
                SNTUtils.log("Secondary image '" + secFile.getName() + "' has incompatible " +
                        "dimensions with " + imgFileChoice.getName() + ". Disabling score map.");
                tracer.setScoreMapEnabled(false);
                return true;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            final RandomAccessibleInterval<? extends RealType<?>> scoreMap =
                    (RandomAccessibleInterval) secImg;
            tracer.setScoreMap(scoreMap);
            SNTUtils.log("Using secondary image: " + secFile.getName());
            return true;
        } catch (final Exception ex) {
            SNTUtils.log("Failed to load secondary image '" + secFile.getName() + "': " +
                    ex.getMessage() + ". Disabling score map.");
            tracer.setScoreMapEnabled(false);
            return true;
        }
    }

    /**
     * Finds a sibling file whose basename (without extension) equals
     * {@code stripExtension(inputFile.name) + suffix}. The input file itself is
     * always excluded. Returns {@code null} if no match is found or if any
     * argument is invalid.
     */
    private File findSecondarySibling(final File inputFile, final String suffix) {
        if (inputFile == null || suffix == null || suffix.isBlank()) return null;
        final File dir = inputFile.getParentFile();
        if (dir == null || !dir.isDirectory()) return null;
        final String targetBase = SNTUtils.stripExtension(inputFile.getName()) + suffix;
        final File[] candidates = dir.listFiles((d, name) -> {
            if (name.equals(inputFile.getName())) return false;
            return SNTUtils.stripExtension(name).equals(targetBase);
        });
        return (candidates != null && candidates.length > 0) ? candidates[0] : null;
    }

    private boolean dimensionsCompatible(final ImgPlus<?> a, final ImgPlus<?> b) {
        if (a == null || b == null) return false;
        if (a.numDimensions() != b.numDimensions()) return false;
        for (int d = 0; d < a.numDimensions(); d++) {
            if (a.dimension(d) != b.dimension(d)) return false;
        }
        return true;
    }
}
