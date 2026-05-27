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

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.AutoTracer;

import java.util.*;

/**
 * Batch autotracing wrapper that iterates over the seeds currently held by
 * SNT's {@link SeedOverlay} and runs the GWDT tracer once per seed (each
 * seed acts as the root of a new {@link Tree}). Trees are accumulated and
 * loaded into the active {@link sc.fiji.snt.PathAndFillManager} at the end.
 * <p>
 * Inherits all GWDT tracing knobs from {@link GWDTTracerCommonCmd}; adds
 * three seed-source filter parameters (source, type, channel override).
 * Failures on individual seeds are logged and reported but do not abort
 * the whole run.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotrace from Seeds (GWDT)...")
public class AutotraceFromSeedsCmd extends GWDTTracerCommonCmd {

    // Source filter labels - kept package-private so SNTUI / SeedManager can
    // pass them via the SciJava input map when invoking the command headless-ish.
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
    private static final String LOG_PREFIX = "Autotrace from Seeds: ";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String SEEDS_HEADER = "<HTML><b>Seed Selection</b>";

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
     * Snapshot of the overlay taken at {@link #runCommand} start; subsequent
     * edits to the overlay don't affect this run.
     */
    private List<SeedPoint> seedSnapshot;

    /**
     * Initializer. Inherits image-related setup from
     * {@link GWDTTracerCommonCmd#initForImage()}, hides ROI-strategy parameters
     * that don't apply (seeds drive root placement), and populates the
     * type-filter choices from the distinct types currently on the overlay.
     */
    @SuppressWarnings("unused")
    protected void init() {
        initForImage();
        if (isCanceled()) return;

        // Seeds, not ROI strategies, drive root placement here.
        resolveInput("somaStrategyChoice");
        resolveInput("roiPlaneOnly");

        // Dynamic type-filter choices.
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
        final MutableModuleItem<String> typeItem =
                getInfo().getMutableInput("typeFilterChoice", String.class);
        typeItem.setChoices(typeChoices);
    }

    @Override
    public void run() {
        runCommand();
    }

    @Override
    protected void runCommand() {
        try {
            chosenImp = getImgFromImgChoice();
            if (chosenImp == null || abortRun) {
                SNTUtils.log(LOG_PREFIX + "aborted before tracing (image="
                        + (chosenImp == null ? "null" : "'" + chosenImp.getName() + "'")
                        + ", abortRun=" + abortRun + ").");
                return;
            }
            SNTUtils.log(LOG_PREFIX + "image='" + chosenImp.getName() + "'.");

            // Snapshot + filter the seeds.
            final SeedOverlay overlay = snt.getSeedOverlay();
            if (overlay == null || overlay.isEmpty()) {
                error("Seed overlay is empty. Import or generate seeds first.");
                return;
            }
            seedSnapshot = overlay.list(); // already a fresh snapshot post-refactor

            final List<SeedPoint> filtered = applyFilters(seedSnapshot, overlay);
            if (filtered.isEmpty()) {
                error("No seeds match the chosen filters (source=" + sourceFilter
                        + ", type=" + typeFilterChoice + ").");
                return;
            }
            SNTUtils.log(String.format(
                    "%s%,d of %,d seed(s) match filters (source=%s, type=%s).",
                    LOG_PREFIX, filtered.size(), seedSnapshot.size(),
                    sourceFilter, typeFilterChoice));

            // Iterate one tracer run per seed; aggregate trees.
            status(String.format("Tracing from %,d seed(s)...", filtered.size()), false);

            final List<Tree> allTrees = new ArrayList<>();
            final List<String> failures = new ArrayList<>();
            int succeeded = 0;
            final boolean debug = SNTUtils.isDebugMode();
            for (int i = 0; i < filtered.size(); i++) {
                if (isCanceled()) {
                    SNTUtils.log(LOG_PREFIX + "cancelled after " + i + " seed(s).");
                    break;
                }
                final SeedPoint seed = filtered.get(i);
                // Per-seed log is debug-only: at thousands of seeds it would
                // otherwise drown out the higher-signal summary lines
                if (debug) SNTUtils.log(String.format(
                        "%s  seed %,d/%,d: x=%.3f y=%.3f z=%.3f type='%s'",
                        LOG_PREFIX, i + 1, filtered.size(),
                        seed.x, seed.y, seed.z, seed.type));

                status(String.format("Tracing seed %,d / %,d (type=%s)…",
                        i + 1, filtered.size(),
                        seed.type.isEmpty() ? "—" : seed.type), false);
                try {
                    final AbstractGWDTTracer<?> tracer = createAndConfigureTracer(chosenImp);
                    if (tracer == null) {
                        failures.add(formatFailure(i, seed, "tracer config failed"));
                        continue;
                    }
                    // Cap-check: the tracer must honor ROOT seeds
                    if (!tracer.honoredSeedRoles().contains(AutoTracer.SeedRole.ROOT)) {
                        failures.add(formatFailure(i, seed, "tracer does not consume root seeds"));
                        continue;
                    }
                    tracer.setRoots(Collections.singletonList(seed));
                    final List<Tree> trees = tracer.traceTrees();
                    if (trees == null || trees.isEmpty()) {
                        failures.add(formatFailure(i, seed, "no path extracted"));
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

            // Summarize failures (each entry is already prefixed with its seed coords)
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

    @Override
    protected boolean isFileMode() {
        return false;
    }

    /**
     * Applies the source-filter (Selection / Visible / All) and the
     * type-filter to a snapshot of the overlay, returning a fresh list.
     */
    private List<SeedPoint> applyFilters(final Collection<SeedPoint> snapshot,
                                         final SeedOverlay overlay) {
        // Source filter first
        List<SeedPoint> stream;
        switch (sourceFilter) {
            case SOURCE_SELECTION -> {
                // Materialize selection into a list, then restrict to seeds
                // still present in the snapshot (defensive)
                final Set<SeedPoint> sel = overlay.getSelectedSeeds();
                stream = new ArrayList<>(sel.size());
                for (final SeedPoint s : snapshot) if (sel.contains(s)) stream.add(s);
            }
            case SOURCE_ALL -> stream = new ArrayList<>(snapshot);
            default -> {
                // SOURCE_VISIBLE
                final double low = overlay.getLowConfidence();
                final double high = overlay.getHighConfidence();
                stream = new ArrayList<>(snapshot.size());
                for (final SeedPoint s : snapshot) {
                    if (s.confidence >= low && s.confidence <= high) stream.add(s);
                }
            }
        }
        // Type filter
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
