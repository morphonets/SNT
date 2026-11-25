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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.StepWizard.Step;
import sc.fiji.snt.tracing.auto.AutoTraceTrainer;
import sc.fiji.snt.tracing.auto.ImageProbe;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Guides the user through automated tracing: check of the data, example paths from which tracing parameters are
 * learned, training, selection of the region to be traced (streamed data), and the tracing prompt itself, which
 * opens pre-populated with the learned values. The wizard does not trace on its own
 *
 * @author Tiago Ferreira
 */
public final class AutoTraceWizard {

    private static final int MIN_PATHS = 3;
    private static final int MIN_NODES = 30;
    private static final int EX_ALL = 0;
    private static final int EX_SELECTED = 1;
    private static final int EX_FILE = 2;

    private static StepWizard instance;

    private AutoTraceWizard() {
    }

    /**
     * Shows the wizard, or brings it to front if it is already open
     *
     * @param ui  the SNT UI
     * @param snt the SNT instance
     */
    public static synchronized void show(final SNTUI ui, final SNT snt) {
        if (instance != null && instance.isDisplayable()) {
            instance.toFront();
            return;
        }
        final State st = new State();
        st.ui = ui;

        // Step 1: data
        final Step data = new Step("Your Data").advanceIf(snt::accessToValidImageData);
        data.choices(i -> st.multiCell = (i == 1), "A single neuron (one soma)", "Multiple neurons (several somata)");
        data.onEnter(w -> {
            if (!st.choiceDefaulted) {
                // Several disconnected trees suggest that several cells are of interest
                st.choiceDefaulted = true;
                st.multiCell = snt.getPathAndFillManager().getTrees().size() > 1;
                data.select(st.multiCell ? 1 : 0);
            }
            describeData(snt, data, st);
        });
        data.action("Re-check Image Data", "Examine the data again, e.g., after loading another image",
                IconFactory.GLYPH.REDO, w -> describeData(snt, data, st));

		// Step 2: examples
		final boolean bigDataWarn = snt.isStreamMode() && snt.getPathAndFillManager().getPaths().size() > 20;
		final Step examples = new Step("Example Paths")
				.applicableIf(() -> !st.binary)
				.body(String.format("Tracing parameters are learned from a few paths that you consider correct and representative. "
								+ "They should include the thinnest and the dimmest branches that are to be traced. Which paths "
								+ "would you like to use for this learning step?%s",
						(bigDataWarn) ? "<br><br>NB: Learning from many paths spread across the streamed volume could be slow.<br>" : ""))
				.advanceIf(() -> examplesReady(snt, st));
		examples.choices(i -> {
					st.examplesMode = i;
					st.note = "";
				}, "Use <b>all</b> paths in Path Manager", "Use only the paths <b>selected</b> in Path Manager",
				"Import paths from a reconstruction file");
		examples.action("Show Path Manager", "Lists the paths that can be used as examples", w -> {
			ui.getPathManager().setVisible(true);
			ui.getPathManager().toFront();
        });
        examples.action("Choose File(s)...", "Reconstructions (SWC, TRACES, JSON...) of this image", w -> {
            chooseFiles(ui, st);
            examples.select(EX_FILE);
            st.examplesMode = EX_FILE;
            st.note = "";
        });
        examples.enableActionIf("Choose File(s)...", () -> st.examplesMode == EX_FILE);
        examples.onEnter(w -> examples.status(examplesStatus(snt, st)));
        examples.poll(w -> examples.status(examplesStatus(snt, st)));

        // Step 3: training
        final Step training = new Step("Training").applicableIf(() -> !st.binary);
        training.onEnter(w -> train(snt, training, st, w));

        // Step 4: region (streamed data only)
        final Step region = new Step("Region")
                .applicableIf(() -> snt.isStreamMode() && !snt.isMaterializedCrop())
                .body("Streamed data is read piece by piece, which makes automated tracing of a large volume slow. "
                        + "Draw a box around the structures of interest in BDV/BVV: the region is then loaded into "
                        + "memory, and traced. To trace the entire volume instead, skip this step.");

        region.action("Select Region in Viewer...", "Draws a box on the data displayed in BDV/BVV",
				IconFactory.GLYPH.CROP,
                w -> {
                    // Busy: no further selections, or navigation, until the attempt is over
                    region.busy(true).status("Select the region in the viewer, then confirm it. The region "
                    + "is then loaded into memory.");
                    ui.materializeRegionInViewer(() -> {
                        st.cropFromWizard = true;
                        w.next(); // no longer applicable: moves to tracing
                    }, () -> region.busy(false).status(null));
                });

        // Step 5: prompt
        final Step trace = new Step("Auto-trace");
        trace.onEnter(w -> trace.body(promptText(snt, st)));
        trace.footer("What happens next?", "Traced paths are listed in Path Manager, where they can be inspected, "
                + "filtered, merged, renamed, or deleted. If results are not satisfactory, add or correct example "
                + "paths and press <i>Learn Parameters</i> in the prompt, then trace again.");
        trace.finish("Finish & Open Auto-Trace Prompt...", IconFactory.GLYPH.ROBOT, w -> {
            final boolean crop = st.cropFromWizard && snt.isMaterializedCrop();
            if (st.binary || st.trained == null) ui.runAutotracingWizard(crop);
            else ui.runAutotracingWithConfig(st.multiCell, st.trained.config(), crop);
        });

        instance = new StepWizard(ui, "Guided Auto-trace", List.of(data, examples, training, region, trace));
        instance.setVisible(true);
    }

    // Example paths usable for training: those of the active channel and frame
    private static List<Path> candidates(final SNT snt, final State st) {
        final int c = snt.getChannel();
        final int t = snt.getFrame();
        final List<Path> list = new ArrayList<>();
        final List<Path> source = (st.examplesMode == EX_SELECTED && st.ui != null)
                ? st.ui.getPathManager().getSelectedPaths(false) : snt.getPathAndFillManager().getPaths();
        for (final Path p : source) {
            if (p.getChannel() == c && p.getFrame() == t) list.add(p);
        }
        return list;
    }

    private static boolean examplesReady(final SNT snt, final State st) {
        if (st.examplesMode == EX_FILE) return !st.files.isEmpty();
        return !candidates(snt, st).isEmpty();
    }

    private static String examplesStatus(final SNT snt, final State st) {
        final StringBuilder sb = new StringBuilder();
        if (!st.note.isEmpty()) sb.append("<font color='").append(SNTColor.colorToString(GuiUtils.Colors.errorColor()))
                .append("'>").append(GuiUtils.Text.escapeHtml(st.note)).append("</font><br>");
        if (st.examplesMode == EX_FILE) {
            sb.append(st.files.isEmpty() ? "No file chosen." : st.files.size() + " file(s) chosen: "
                    + GuiUtils.Text.escapeHtml(st.files.stream().map(File::getName).limit(3)
                    .collect(Collectors.joining(", "))) + (st.files.size() > 3 ? "..." : ""));
            return sb.toString();
        }
        final List<Path> paths = candidates(snt, st);
        final int total = snt.getPathAndFillManager().size();
        final int nodes = paths.stream().mapToInt(Path::size).sum();
        if (paths.isEmpty() && st.examplesMode == EX_SELECTED) {
            sb.append("No paths are selected in Path Manager. Please select the paths that are representative "
                    + "and correct.");
            if (total > 0) sb.append(String.format(" (%d path(s) available.)", total));
            return sb.toString();
        }
        if (paths.isEmpty()) {
            sb.append("You haven't traced anything yet. Please trace a few representative paths: "
                    + "This step proceeds as soon as they exist.");
            if (total > 0) sb.append(String.format(" (the %d path(s) in Path Manager belong to other "
                    + "channels/frames.)", total));
            return sb.toString();
        }
        sb.append(String.format("%d %spath(s), %d nodes", paths.size(),
                (st.examplesMode == EX_SELECTED) ? "selected " : "", nodes));
        if (st.examplesMode == EX_ALL && total > paths.size())
            sb.append(String.format(" (%d path(s) of other channels/frames "
                    + "are ignored)", total - paths.size()));
        sb.append(".");
        if ((paths.size() < MIN_PATHS || nodes < MIN_NODES))
            sb.append("<br><i>Few examples: results improve with at least ").append(MIN_PATHS)
                    .append(" paths (").append(MIN_NODES).append("+ nodes).</i>");
        return sb.toString();
    }

    private static void chooseFiles(final SNTUI ui, final State st) {
        final File[] files = new GuiUtils(ui).getReconstructionFiles(null);
        if (files == null) return;
        st.files = new ArrayList<>(Arrays.asList(files));
    }

    private static void train(final SNT snt, final Step step, final State st, final StepWizard w) {
        if (st.training) return;
        st.training = true;
        st.trained = null;
        step.body("Learning tracing parameters from the example paths...").status(null).busy(true);
        final boolean fromFiles = st.examplesMode == EX_FILE;
        final List<File> files = new ArrayList<>(st.files);
        final List<Path> examples = fromFiles ? null : candidates(snt, st);
        snt.syncWithActiveSource(); // source may have changed since the wizard opened
        new SwingWorker<AutoTraceTrainer.Result, Void>() {
            @Override
            protected AutoTraceTrainer.Result doInBackground() {
                if (!fromFiles) {
                    final double inside = AutoTraceTrainer.fractionInBounds(snt, examples);
                    if (Double.isNaN(inside) || inside < 0.5) {
                        throw new IllegalArgumentException(String.format("Only %.0f%% of the nodes of "
                                + "the paths fall within the image: they do not seem to match it (e.g., are they "
                                + "from another image or region?).", 100 * (Double.isNaN(inside) ? 0 : inside)));
                    }
                }
                return AutoTraceTrainer.train(snt, examples, fromFiles ? files : null);
            }

            @Override
            protected void done() {
                st.training = false;
                step.busy(false);
                try {
                    st.trained = get();
                    step.body(trainedText(st.trained));
                    final List<String> warnings = st.trained.config().getWarnings();
                    step.status(warnings.isEmpty() ? null : "<i>" + warnings.stream().map(GuiUtils.Text::escapeHtml)
                            .collect(Collectors.joining("<br>")) + "</i>");
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (final ExecutionException ex) {
                    final Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    SNTUtils.log("Guided training failed: " + cause);
                    st.note = "Training failed: " + cause.getMessage();
                    w.back(); // to the examples, which show the reason
                }
            }
        }.execute();
    }

    private static String trainedText(final AutoTraceTrainer.Result r) {
        return "Parameters were learned from <b>" + r.nPaths() + "</b> path(s). They are shown, and can be "
                + "adjusted, in the tracing prompt.<pre style='margin-top:4px'>"
                + GuiUtils.Text.escapeHtml(r.config().getSummary()) + "</pre>";
    }

    private static String promptText(final SNT snt, final State st) {
        final StringBuilder sb = new StringBuilder();
        if (snt.isStreamMode() && !snt.isMaterializedCrop())
            sb.append("The tracer will read the streamed data directly, which may be slow. ");
        if (st.binary || st.trained == null) {
            sb.append("The prompt opens the tracer for binary (segmented) images. Press <i>Run</i> to trace.");
            return sb.toString();
        }
        sb.append("The prompt opens the ").append(st.multiCell ? "multi-cell" : "single-cell")
                .append(" tracer, pre-populated with the learned values. Press <i>Learn Parameters...</i> in it ")
                .append("to assess them, and <i>Run</i> to trace.");
        return sb.toString();
    }

    private static void describeData(final SNT snt, final Step step, final State st) {
        if (snt.isStreamMode() && !snt.isMaterializedCrop()) {
            st.binary = false;
            step.choicesVisible(true);
            step.body("The data is streamed: it is read on demand and not held in memory. Auto-tracing works best "
                    + "on a region of it, which a later step helps to select.<br>How many cells are in your data?");
            return;
        }
        if (!snt.accessToValidImageData()) {
            step.choicesVisible(false);
            step.body("<b>No image data is available.</b> Load an image first (<i>File</i> menu): automated "
                    + "tracing requires bright structures on a dark background.");
            return;
        }
        step.choicesVisible(false); // only relevant for intensity images
        step.body("Examining the image...");
        new SwingWorker<ImageProbe.Result, Void>() {
            @Override
            protected ImageProbe.Result doInBackground() {
                return probe(snt);
            }

            @Override
            protected void done() {
                try {
                    final ImageProbe.Result r = get();
                    st.binary = r.binary();
                    step.choicesVisible(!st.binary);
                    step.body(describe(r, snt.getChannelCount()));
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (final ExecutionException ex) {
                    SNTUtils.log("Image check failed: " + ex.getCause());
                    step.body("The image could not be examined: " + GuiUtils.Text.escapeHtml(String.valueOf(
                            ex.getCause().getMessage())));
                }
            }
        }.execute();
    }

    private static <T extends RealType<T>> ImageProbe.Result probe(final SNT snt) {
        final RandomAccessibleInterval<T> data = snt.getLoadedData();
        if (data == null) throw new IllegalStateException("No image data");
        final var cal = snt.getCalibration();
        return ImageProbe.probe(data, new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth});
    }

    private static String describe(final ImageProbe.Result p, final int channels) {
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s, %s voxels", p.pixelType().replace("Type", ""),
                Arrays.stream(p.dimensions()).mapToObj(Long::toString).collect(Collectors.joining(" x "))));
        if (channels > 1) sb.append(String.format(", %d channels (only the active one is considered)", channels));
        sb.append(".<br>");
        if (p.binary()) {
            sb.append("The image is <b>binary</b>: a segmentation. It is traced by skeletonization, so there is "
                    + "nothing to learn: the next steps are skipped.<br>");
        } else {
            sb.append(String.format("Estimated SNR: %.1f. Estimated foreground: %.2f%%.<br>",
                    p.snr(), 100 * p.foregroundFraction()));
        }
        if (!p.binary()) sb.append("<i>These are rough estimates from a sample of voxels.</i>");
        final List<String> notes = new ArrayList<>(p.warnings());
        notes.addAll(p.recommendations());
        if (!notes.isEmpty()) {
            sb.append("<ul style='margin-top:4px;margin-bottom:0px'>");
            for (final String note : notes) sb.append("<li>").append(GuiUtils.Text.escapeHtml(note)).append("</li>");
            sb.append("</ul>");
        }
        if (!p.binary()) sb.append("<br>Does the image contain...");
        return sb.toString();
    }

    /**
     * What the user has chosen so far
     */
    private static final class State {
        boolean multiCell;
        boolean binary;
        boolean choiceDefaulted;
        int examplesMode = EX_ALL;
        SNTUI ui;
        List<File> files = new ArrayList<>();
        String note = ""; // why the previous training failed
        boolean training;
        AutoTraceTrainer.Result trained;
        boolean cropFromWizard;
    }

}
