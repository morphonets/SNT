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
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;

import java.util.*;

/**
 * Single-cell skeleton autotracing wrapper that uses the soma ROI on the canvas (or its auto-detection) as the root and
 * the filtered seeds from SNT's {@link SeedOverlay} as tip targets. After {@link sc.fiji.snt.tracing.auto.BinaryTracer
 * BinaryTracer} produces the full skeleton tree, this command prunes it to keep only the paths that lie on a
 * root-to-tip route. i.e. the smallest sub-tree connecting the soma to every reachable tip.
 * <p>
 * Binary/skeleton counterpart of {@link AutotraceFromTipsCmd}. The algorithmic mechanism differs: GWDT walks parent
 * pointers from the Fast-Marching map, while here we operate post-hoc on the assembled skeleton tree, taking advantage
 * of the path-level parent relationships SNT already maintains.
 * <p>
 * Tips whose nearest skeleton node falls in a different connected component than the soma (i.e. the walk-up doesn't
 * reach the root path) are silently dropped from the output but logged to the console log
 * <p>
 * Inherits soma-ROI strategy and every binary-tracer knob from {@link BinaryTracerCommonCmd}; adds the same seed
 * source/type filters used by {@link AutotraceFromSeedsCmd}.
 *
 * @author Tiago Ferreira
 * @see BinaryTracerCommonCmd
 * @see AutotraceFromBinarySeedsCmd
 * @see AutotraceFromTipsCmd
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotrace Single Cell with Seeded Tips (Binary)...")
public class AutotraceFromBinaryTipsCmd extends BinaryTracerCommonCmd {

    // Source filter labels - kept package-private so SNTUI / SeedManager can
    // pass them via the SciJava input map when invoking the command headless-ish
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
    private static final String LOG_PREFIX = "Autotrace Binary from Tips: ";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String SEEDS_HEADER = "<HTML>&nbsp;<br><b>Tip Selection</b>";

    @Parameter(label = "Seed source",
            choices = {SOURCE_VISIBLE, SOURCE_ALL, SOURCE_SELECTION},
            description = "<HTML>Which seeds to use as tip targets:<dl>" +
                    "<dt><i>" + SOURCE_VISIBLE + "</i></dt>" +
                    "<dd>Seeds currently passing the confidence filter (Seeds tab)</dd>" +
                    "<dt><i>" + SOURCE_ALL + "</i></dt>" +
                    "<dd>Every seed in the overlay regardless of filter</dd>" +
                    "<dt><i>" + SOURCE_SELECTION + "</i></dt>" +
                    "<dd>Only the seeds currently selected in the Seeds table</dd>" +
                    "</dl>")
    private String sourceFilter = SOURCE_VISIBLE;

    @Parameter(label = "Seed type filter", choices = {TYPE_ANY},
            description = "<HTML>Restrict the tip set to seeds whose <i>type</i> field equals " +
                    "the chosen value. <i>(any)</i> matches all types; choices are " +
                    "populated from the distinct values currently in the overlay.")
    private String typeFilterChoice = TYPE_ANY;

    /**
     * Tips resolved at the start of {@link #runCommand}; consumed by the
     * overridden {@link #handleTracedTrees(List)} to prune the full skeleton
     * tree(s) before they're loaded into the Path Manager. Same
     * field-injection pattern as {@link AutotraceFromTipsCmd}.
     */
    private List<SeedPoint> filteredTips;

    /**
     * Initializer. Inherits image-related setup from {@link BinaryTracerCommonCmd#initForImage()} and keeps the
     * soma-ROI inputs visible. The soma ROI (or its auto-detection) is the root for the single-cell trace.
     */
    @SuppressWarnings("unused")
    protected void init() {
        initForImage();
        if (isCanceled()) return;

        // Dynamic type-filter choices populated from the distinct types currently in the overlay
        // (mirrors AutotraceFromBinarySeedsCmd)
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
        if (!isCanceled() && !abortRun) runCommand();
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    @Override
    protected void runCommand() {
        // Pre-flight: resolve the tip set before delegating to the parent's full single-cell flow. The parent
        // doesn't know about seeds, so failing here gives the user a focused error rather than running a trace whose
        // result would just be the full tree (because the post-hoc prune in handleTracedTrees would be a no-op)
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            error("Seed overlay is empty. Import or generate seeds first.");
            return;
        }
        filteredTips = applyFilters(overlay.list(), overlay);
        if (filteredTips.isEmpty()) {
            error("No seeds match the chosen filters (source=" + sourceFilter
                    + ", type=" + typeFilterChoice + ").");
            return;
        }
        SNTUtils.log(String.format(
                "%s%,d of %,d seed(s) selected as tips (source=%s, type=%s).",
                LOG_PREFIX, filteredTips.size(), overlay.size(),
                sourceFilter, typeFilterChoice));

        // Delegate to the parent's full single-cell flow (image resolution, soma-ROI strategy, auto-detection, tracing,
        // handleTracedTrees). The overridden handleTracedTrees below intercepts the full skeleton tree(s) and prunes
        // them to root-to-tip routes before the parent adds them to the Path Manager
        super.runCommand();
    }

    /**
     * Intercepts the trees produced by the parent's binary-tracer flow, prunes each one to keep only the paths on a
     * root-to-tip route, and  forwards the pruned result to the parent's standard tree-handling (which adds them to
     * the Path Manager, optionally with proofreading and color assignment, per the "After tracing" choice).
     */
    @Override
    protected void handleTracedTrees(final List<Tree> trees) {
        if (trees == null || trees.isEmpty()) {
            super.handleTracedTrees(trees);
            return;
        }
        final List<Tree> pruned = new ArrayList<>(trees.size());
        int totalReached = 0;
        int totalUnreachable = 0;
        for (final Tree tree : trees) {
            final PruneResult r = pruneToTips(tree, filteredTips);
            if (r.tree != null) pruned.add(r.tree);
            totalReached += r.reached;
            totalUnreachable += r.unreachable;
        }
        SNTUtils.log(String.format("%sprune result: %,d tip(s) reached, %,d unreachable, %,d tree(s) retained.",
                LOG_PREFIX, totalReached, totalUnreachable, pruned.size()));
        if (pruned.isEmpty()) {
            error("None of the traced trees contain reachable tip seeds.\n"
                    + "Check whether the tips lie on / near the skeleton "
                    + "and share a connected component with the soma.");
            return;
        }
        super.handleTracedTrees(pruned);
    }

    /**
     * Result of pruning a single tree against the tip set. {@link #tree} is  {@code null} when no tip reached this
     * tree's component.
     */
    private record PruneResult(Tree tree, int reached, int unreachable) {
    }

    /**
     * Returns the smallest sub-tree of {@code tree} that connects the soma (root path) to every tip whose nearest
     * skeleton node lives in this component. Tips whose nearest node belongs to a different connected component than
     * the root are dropped from the output (and counted in {@link PruneResult#unreachable}).
     * <p>
     * Algorithm: for each tip, find the nearest node across all of {@code tree}'s paths; from that node's path, walk up
     * via  {@link Path#getParentPath()} until either reaching a primary path  (no parent) or having already collected
     * every ancestor; the union of every ancestor chain is the pruned sub-tree.
     */
    private PruneResult pruneToTips(final Tree tree, final List<SeedPoint> tips) {
        if (tree == null || tree.isEmpty() || tips == null || tips.isEmpty()) {
            return new PruneResult(null, 0, (tips == null) ? 0 : tips.size());
        }
        final Path rootPath = findRootPath(tree);
        if (rootPath == null) return new PruneResult(null, 0, tips.size());

        final Set<Path> kept = new LinkedHashSet<>();
        int reached = 0;
        int unreachable = 0;
        for (final SeedPoint tip : tips) {
            final Path nearest = findNearestPath(tree, tip);
            if (nearest == null) {
                unreachable++;
                continue;
            }
            // Walk up the parent chain, collecting every path on the route.
            // If we never hit rootPath, the tip is in a disconnected component, and we discard its accumulated
            // walk so we don't mix it with the root's component
            final Set<Path> walk = new LinkedHashSet<>();
            Path p = nearest;
            boolean reachesRoot = false;
            while (p != null) {
                walk.add(p);
                if (p == rootPath) {
                    reachesRoot = true;
                    break;
                }
                p = p.getParentPath();
            }
            if (reachesRoot) {
                kept.addAll(walk);
                reached++;
            } else {
                unreachable++;
            }
        }
        if (kept.isEmpty()) return new PruneResult(null, reached, unreachable);

        final Tree result = new Tree();
        // Preserve the original tree's metadata where it's useful for downstream display (label, color, etc.)
        if (tree.getLabel() != null) result.setLabel(tree.getLabel());
        for (final Path p : kept) result.add(p);
        return new PruneResult(result, reached, unreachable);
    }

    /**
     * Returns the primary (root) path of {@code tree}. Returns {@code null} if every path has a parent (shouldn't
     * happen for a well-formed tree) or the tree is empty.
     */
    private static Path findRootPath(final Tree tree) {
        for (final Path p : tree.list()) {
            if (p.getParentPath() == null) return p;
        }
        return null;
    }

    /**
     * Returns the {@link Path} whose node lies closest to {@code tip} in Euclidean physical distance, or {@code null}
     * if {@code tree} has no nodes. O(N) over all node positions; for typical skeleton sizes (thousands of nodes,
     * dozens of tips) this is quick enough per tip and not worth a KDTree.
     */
    private static Path findNearestPath(final Tree tree, final SeedPoint tip) {
        Path best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (final Path p : tree.list()) {
            for (int i = 0, n = p.size(); i < n; i++) {
                final double dx = p.getNode(i).x - tip.x;
                final double dy = p.getNode(i).y - tip.y;
                final double dz = p.getNode(i).z - tip.z;
                final double d = dx * dx + dy * dy + dz * dz;
                if (d < bestSq) {
                    bestSq = d;
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * Applies the source-filter (Selection / Visible / All) and the type-filter to a snapshot of the overlay,
     * returning a fresh list. Logic mirrors the equivalent helper in {@link AutotraceFromBinarySeedsCmd}.
     */
    private List<SeedPoint> applyFilters(final Collection<SeedPoint> snapshot, final SeedOverlay overlay) {
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
}
