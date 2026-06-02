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

import ij.ImagePlus;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.AutoTracer;
import sc.fiji.snt.tracing.auto.BinaryTracer;

import java.util.*;

/**
 * Batch autotracing wrapper that iterates over the seeds currently held by SNT's {@link SeedOverlay} and runs the
 * {@link BinaryTracer} (skeleton-based autotracer) once per seed, each seed acting as the root of its own tree.
 * Trees are accumulated and loaded into the active path manager at the end.
 * <p>
 * This is the binary/skeleton counterpart of {@link AutotraceFromSeedsCmd}. The typical workflow is to start from a
 * labels image (e.g. cellpose / StarDist segmentations), generate one seed per labeled object via
 * {@link sc.fiji.snt.gui.cmds.LoadSeedsFromLabelsImageCmd Generate Seeds from Labels Image}, and then run this command
 * to produce per-cell skeleton trees in a single batch.
 * <p>
 * Inherits every binary-tracer knob from {@link BinaryTracerCommonCmd} (segmented-image choice, optional intensity
 * image, loop-resolution strategy, gap bridging, prune-by-length, etc.); adds the same seed ource/type filters used
 * by {@link AutotraceFromSeedsCmd}. Failures on individual seeds are logged and reported but do not abort the whole run.
 *
 * @author Tiago Ferreira
 * @see BinaryTracerCommonCmd
 * @see AutotraceFromSeedsCmd
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotrace Binary Image from Seeds...")
public class AutotraceFromBinarySeedsCmd extends BinaryTracerCommonCmd {

    // Source filter labels: package-private so SNTUI / SeedManager can pass them via the
    // SciJava input map when invoking the command headless-ish
    static final String SOURCE_ALL = "All seeds";
    static final String SOURCE_VISIBLE = "Visible (within confidence range)";
    public static final String SOURCE_SELECTION = "Selection only";

    static final String TYPE_ANY = "(any)";
    /**
     * Display label for the empty-string ({@link SeedPoint#TAG_UNSET}) type.
     */
    private static final String TYPE_UNSET_LABEL = "(unset)";

    /**
     * Common prefix for all log lines emitted by this command.
     */
    private static final String LOG_PREFIX = "Autotrace Binary from Seeds: ";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String SEEDS_HEADER = "<HTML>&nbsp;<br><b>Seed Selection</b>";

    @Parameter(label = "Seed source",
            choices = {SOURCE_VISIBLE, SOURCE_ALL, SOURCE_SELECTION},
            description = "<HTML>Which seeds to trace from:<dl>" +
                    "<dt><i>" + SOURCE_VISIBLE + "</i></dt>" +
                    "<dd>Seeds currently passing the confidence filter (Seeds tab)</dd>" +
                    "<dt><i>" + SOURCE_ALL + "</i></dt>" +
                    "<dd>Every seed in the overlay regardless of filter</dd>" +
                    "<dt><i>" + SOURCE_SELECTION + "</i></dt>" +
                    "<dd>Only the seeds currently selected in the Seeds table</dd>" +
                    "</dl>")
    private String sourceFilter = SOURCE_VISIBLE;

    @Parameter(label = "Seed type filter", choices = {TYPE_ANY},
            description = "<HTML>Restrict tracing to seeds whose <i>type</i> field equals " +
                    "the chosen value. <i>(any)</i> matches all types; choices are " +
                    "populated from the distinct values currently in the overlay.")
    private String typeFilterChoice = TYPE_ANY;

    /**
     * Initializer. Inherits image-related setup from {@link BinaryTracerCommonCmd#initForImage()}, hides root-strategy
     * and active-plane-only parameters (seeds drive root placement here, not an ROI), and populates the type-filter
     * choices from the distinct types currently on the overlay.
     */
    @SuppressWarnings("unused")
    protected void init() {
        initForImage();
        if (isCanceled()) return;

        // Seeds, not ROI strategies, drive root placement here
        resolveInput("HEADER2");
        resolveInput("rootChoice");
        resolveInput("roiPlane");
        // Dynamic type-filter choices populated from the distinct types  currently in the overlay
        // (mirrors AutotraceFromSeedsCmd).
        final SeedOverlay overlay = snt.getSeedOverlay();
        final List<String> typeChoices = new ArrayList<>();
        typeChoices.add(TYPE_ANY);
        if (overlay != null && !overlay.isEmpty()) {
            final Set<String> distinct = new TreeSet<>();
            for (final SeedPoint s : overlay.list()) distinct.add(s.type);
            for (final String t : distinct) {
                typeChoices.add(t.isEmpty() ? TYPE_UNSET_LABEL : t);
            }
        }
        final MutableModuleItem<String> typeItem = getInfo().getMutableInput("typeFilterChoice", String.class);
        typeItem.setChoices(typeChoices);
    }

    @Override
    public void run() {
        if (!isCanceled() && !abortRun) runCommand();
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    @Override
    protected void runCommand() {
        if (abortRun || isCanceled()) return;

        // Pre-flight: validate the overlay + tip set
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            error("Seed overlay is empty. Import or generate seeds first.");
            return;
        }
        final List<SeedPoint> filtered = applyFilters(overlay.list(), overlay);
        if (filtered.isEmpty()) {
            error("No seeds match the chosen filters (source=" + sourceFilter
                    + ", type=" + typeFilterChoice + ").");
            return;
        }

        try {
            // Resolve the mask + optional intensity images. v1 supports only  the active-image / duplicate /
            // secondary-layer choices; file mode is not exposed (isFileMode() returns false). If a future
            // user wants to feed external files, BinaryTracerCommonCmd's existing image-resolution block
            // in runCommand can be lifted into a shared protected helper
            final ImagePlus maskImp = resolveMaskImage();
            if (maskImp == null) return;
            // Seeds-driven runCommand bypasses BinaryTracerCommonCmd.runCommand so the "grayscale image"  confirmation
            // that protects the standard binary commands is added here
            if (!confirmIfNotSegmented(maskImp)) return;
            final ImagePlus origImp = resolveOptionalOrigImage();
            chosenMaskImp = maskImp; // exposed for parent helpers/handleTracedTrees

            SNTUtils.log(LOG_PREFIX + "mask='" + maskImp.getTitle()
                    + "', orig=" + (origImp == null ? "—" : "'" + origImp.getTitle() + "'"));
            SNTUtils.log(String.format(
                    "%s%,d of %,d seed(s) selected (source=%s, type=%s).",
                    LOG_PREFIX, filtered.size(), overlay.size(),
                    sourceFilter, typeFilterChoice));

            // Skeletonize once before the per-seed loop so each per-seed. BinaryTracer reads from the same
            // pre-skeletonized image
            snt.setCanvasLabelAllPanes("Skeletonizing...");
            BinaryTracer.skeletonize(maskImp, maskImp.getNSlices() == 1);

            // Per-seed iteration. Each seed yields one or more trees (the skeleton component connected to that seed);
            // we accumulate them and dispatch via handleTracedTrees at the end
            status(String.format("Tracing from %,d seed(s)...", filtered.size()), false);
            final List<Tree> allTrees = new ArrayList<>();
            final List<String> failures = new ArrayList<>();
            final boolean debug = SNTUtils.isDebugMode();
            int succeeded = 0;
            for (int i = 0; i < filtered.size(); i++) {
                if (isCanceled()) {
                    SNTUtils.log(LOG_PREFIX + "cancelled after " + i + " seed(s).");
                    break;
                }
                final SeedPoint seed = filtered.get(i);
                if (debug) SNTUtils.log(String.format(
                        "%s  seed %,d/%,d: x=%.3f y=%.3f z=%.3f type='%s'",
                        LOG_PREFIX, i + 1, filtered.size(),
                        seed.x, seed.y, seed.z, seed.type));
                status(String.format("Tracing seed %,d / %,d (type=%s)…",
                        i + 1, filtered.size(),
                        seed.type.isEmpty() ? "—" : seed.type), false);
                try {
                    final BinaryTracer converter = createAndConfigureConverter(maskImp, origImp);
                    if (!converter.honoredSeedRoles().contains(AutoTracer.SeedRole.ROOT)) {
                        failures.add(formatFailure(i, seed,
                                "tracer does not consume root seeds"));
                        continue;
                    }
                    converter.setRoots(Collections.singletonList(seed));
                    final List<Tree> trees = converter.getTrees();
                    if (trees == null || trees.isEmpty()) {
                        failures.add(formatFailure(i, seed, "no tree extracted"));
                        continue;
                    }
                    allTrees.addAll(trees);
                    succeeded++;
                } catch (final Throwable ex) {
                    failures.add(formatFailure(i, seed, ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : ": " + ex.getMessage())));
                    if (debug) ex.printStackTrace();
                }
            }

            for (final String f : failures) SNTUtils.log(LOG_PREFIX + f);
            if (allTrees.isEmpty()) {
                error("No traces could be produced from any of the " + filtered.size()
                        + " seed(s). " + failures.size() + " failure(s) logged to console.");
                return;
            }
            SNTUtils.log(String.format(
                    "%sloading %,d tree(s) into Path Manager (%,d ok / %,d failed).",
                    LOG_PREFIX, allTrees.size(), succeeded, failures.size()));
            handleTracedTrees(allTrees);
            status(String.format("Traced %,d of %,d seed(s); %,d failure(s)%s.",
                    succeeded, filtered.size(), failures.size(),
                    failures.isEmpty() ? "" : " — see Console"), true);
        } catch (final Throwable ex) {
            ex.printStackTrace();
            error("An exception occurred. See Console for details.");
        } finally {
            snt.setCanvasLabelAllPanes(null);
        }
    }

    /**
     * Resolves the segmented/mask image from {@code maskImgChoice}. Handles the three non-file-mode cases: active
     * image (duplicate), secondary layer, and other open image picked from the dropdown. Calls {@link #error(String)}
     * and returns {@code null} on failure.
     */
    private ImagePlus resolveMaskImage() {
        if (IMG_TRACED_DUP_CHOICE.equals(maskImgChoice)) {
            final ImagePlus dup = snt.getLoadedDataAsImp().duplicate();
            if (snt.getImagePlus() != null && snt.getImagePlus().isThreshold()) {
                dup.getProcessor().setThreshold(
                        snt.getImagePlus().getProcessor().getMinThreshold(),
                        snt.getImagePlus().getProcessor().getMaxThreshold());
            }
            return dup;
        }
        if (IMG_TRACED_SEC_LAYER_CHOICE.equals(maskImgChoice)) {
            final ImagePlus sec = snt.getSecondaryDataAsImp();
            if (sec == null) {
                error("No secondary layer image exists. Please load one first.");
                return null;
            }
            return sec;
        }
        final ImagePlus imp = (impMap == null) ? null : impMap.get(maskImgChoice);
        if (imp == null) {
            noImgError();
            return null;
        }
        return imp;
    }

    /**
     * Resolves the optional intensity image; returns {@code null} when none was chosen.
     */
    private ImagePlus resolveOptionalOrigImage() {
        if (originalImgChoice == null) return null;
        if (IMG_TRACED_CHOICE.equals(originalImgChoice)) return snt.getLoadedDataAsImp();
        return (impMap == null) ? null : impMap.get(originalImgChoice);
    }

    /**
     * Applies the source-filter (Selection / Visible / All) and the type-filter to a snapshot of the overlay,
     * returning a fresh list. Logic mirrors {@code AutotraceFromSeedsCmd.applyFilters}.
     */
    private List<SeedPoint> applyFilters(final Collection<SeedPoint> snapshot,
                                         final SeedOverlay overlay) {
        List<SeedPoint> stream;
        switch (sourceFilter) {
            case SOURCE_SELECTION -> {
                final Set<SeedPoint> sel = overlay.getSelectedSeeds();
                stream = new ArrayList<>(sel.size());
                for (final SeedPoint s : snapshot) if (sel.contains(s)) stream.add(s);
            }
            case SOURCE_ALL -> stream = new ArrayList<>(snapshot);
            default -> {
                final double low = overlay.getLowConfidence();
                final double high = overlay.getHighConfidence();
                stream = new ArrayList<>(snapshot.size());
                for (final SeedPoint s : snapshot) {
                    if (s.confidence >= low && s.confidence <= high) stream.add(s);
                }
            }
        }
        if (!TYPE_ANY.equals(typeFilterChoice)) {
            final String wanted = TYPE_UNSET_LABEL.equals(typeFilterChoice)
                    ? SeedPoint.TAG_UNSET : typeFilterChoice;
            final List<SeedPoint> typed = new ArrayList<>(stream.size());
            for (final SeedPoint s : stream) {
                if (wanted.equals(s.type)) typed.add(s);
            }
            stream = typed;
        }
        return stream;
    }

    private static String formatFailure(final int idx, final SeedPoint seed, final String why) {
        return String.format("Seed %d (x=%.3f y=%.3f z=%.3f, type='%s'): %s",
                idx + 1, seed.x, seed.y, seed.z, seed.type, why);
    }
}
