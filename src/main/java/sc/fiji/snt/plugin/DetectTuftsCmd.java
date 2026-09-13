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

import net.imglib2.RandomAccessibleInterval;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.TuftDetector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link TuftDetector} on a collection of {@link Tree}s adding the detected tufts to SNT's
 * {@link sc.fiji.snt.SeedManager}/{@link SeedOverlay}.
 *
 * @author Tiago Ferreira
 * @see TuftDetector
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Detect Tufts...")
public class DetectTuftsCmd extends CommonDynamicCmd {

    private static final String WEIGHT_RADIUS = "Node radii";
    private static final String WEIGHT_INTENSITY = "Sampled intensity";
    private static final String WEIGHT_BOTH = "Node radii and sampled intensity";
    private static final String WEIGHT_NONE = "None";

    @Parameter(label = "Detect tufts using",
            choices = {"Density of terminal branches", "Terminal branches", "Terminal branch points", "Tips",
                    "Terminal locations", "Branch points", "Branch density"},
            description = "<HTML>Where in the arbor(s) are tufts expected?<dl>"//
                    + "<dt><b>Density of terminal branches</b></dt><dd>" //
                    + "Every node of every terminal branch, weighted by the cable length<br>" //
                    + "it represents, i.e., amount of cable rather than a count of discrete<br>" //
                    + "nodes. Unlike <i>Terminal branches</i>' single centroid per branch,<br>" //
                    + "this is robust to a terminal branch fragmenting into several short,<br>" //
                    + "spatially-close pieces (e.g. from a discontiguous/beaded signal)</dd>"//
                    + "<dt><b>Terminal branches</b></dt><dd>" //
                    + "Tuftiness is inspected at the centroid of each terminal branch.<br>" //
                    + "Useful when a tuft is formed by several short converging twigs</dd>"//
                    + "<dt><b>Terminal branch points</b></dt><dd>"//
                    + "Tuftiness is inspected at Branch points that are the immediate<br>"//
                    + "parent of at least one terminal branch</dd>"//
                    + "<dt><b>Tips</b></dt><dd>Tuftiness is inspected at terminal end nodes</dd>"//
                    + "<dt><b>Terminal locations</b></dt><dd>Uses Terminal branches, Terminal branch points, and Tips<br>"//
                    + "pooled into a single detection pass so overlapping candidates are<br>"//
                    + "not reported twice. Does not include the density options, whose<br>"//
                    + "cable-length weights are on a different scale</dd>"
                    + "<dt><b>Branch points</b></dt><dd>Tuftiness is inspected at all bifurcation points (terminal and non-terminal)</dd>"//
                    + "<dt><b>Branch density</b></dt><dd>As <i>Density of terminal branches</i>, but internal<br>"//
                    + "(non-terminal) branches are scanned too. More permissive: a dense trunk/<br>"//
                    + "shaft can also score as a hotspot, even away from any terminal branch</dd></dl>")//
    private String scope;

    @Parameter(label = "Tuft radius", min = "0", stepSize = "0.5", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Distance threshold (in physical units) defining a tuft: acts as a<br>" +
                    "clustering radius, so nodes within this distance of each other<br>" +
                    "are treated as candidates for the same tuft.<br>" +
                    "0: Min voxel separation.")
    private double radius = 15.0;

    @Parameter(label = "Weight by", choices = {WEIGHT_RADIUS, WEIGHT_INTENSITY, WEIGHT_BOTH, WEIGHT_NONE},
            description = "<HTML>Besides how crowded a location is, what other evidence should count<br>"
                    + "toward a tuft?<dl>"
                    + "<dt><b>Node radius</b></dt><dd>Thicker structure counts more, using whatever<br>"
                    + "radii are already recorded. Nodes without a radius simply don't<br>"
                    + "contribute, so this is harmless but useless on paths without radii</dd>"
                    + "<dt><b>Sampled intensity</b></dt><dd>Brighter locations count more. Each tree<br>"
                    + "is profiled against image brightness first. Requires a valid image</dd>"
                    + "<dt><b>Node radius and sampled intensity</b></dt><dd>Both of the above, combined</dd>"
                    + "<dt><b>None</b></dt><dd>Only node crowding matters</dd></dl>")
    private String weighting = WEIGHT_BOTH;

    @Parameter(label = "Penalize short terminal branches",
            description = "<HTML>Applies only when <i>Detect tufts using</i> is <b>Terminal branches</b>.<br>" +
                    "A discontiguous (e.g. beaded) neurite can fragment into several short,<br>" +
                    "spatially-close but topologically-unrelated terminal branches, which would<br>" +
                    "otherwise look like a genuine dense tuft. If checked, \"short\" is judged<br>" +
                    "against the <i>Tuft radius</i> above: a branch shorter than that radius contributes<br>" +
                    "proportionally less (down to nothing as its length approaches 0), while a<br>" +
                    "branch at least as long as it contributes in full.")
    private boolean penalizeShortBranches = true;

    @Parameter(label = "Replace existing seeds",
            description = "<HTML>If checked, existing seeds are replaced.<br>"
                    + "If unchecked, detected tufts are appended.")
    private boolean replace;

    protected void init() {
        super.init(true);
        if (trees == null || trees.isEmpty()) {
            error("No structure to scan.");
        }
    }

    @Parameter
    private Collection<Tree> trees;

    private List<TuftDetector.Type> scopeToTypes(final String scope) {
        return switch (scope) {
            case "Branch points" -> List.of(TuftDetector.Type.BRANCH_POINTS);
            case "Tips" -> List.of(TuftDetector.Type.TIPS);
            case "Terminal branch points" -> List.of(TuftDetector.Type.TERMINAL_BRANCH_POINTS);
            case "Terminal branches" -> List.of(TuftDetector.Type.TERMINAL_BRANCHES);
            case "Branch density" -> List.of(TuftDetector.Type.BRANCH_DENSITY);
            case "Density of terminal branches" -> List.of(TuftDetector.Type.TERMINAL_BRANCH_DENSITY);
            default -> List.of(TuftDetector.Type.TIPS, TuftDetector.Type.TERMINAL_BRANCH_POINTS,
                    TuftDetector.Type.TERMINAL_BRANCHES);
        };
    }

    @Override
    public void run() {
        if (isCanceled()) return;
        if (trees == null || trees.isEmpty()) {
            error("No structure to scan.");
            return;
        }

        final boolean weightByThickness = WEIGHT_RADIUS.equals(weighting) || WEIGHT_BOTH.equals(weighting);
        final boolean weightByIntensity = WEIGHT_INTENSITY.equals(weighting) || WEIGHT_BOTH.equals(weighting);
        final RandomAccessibleInterval<?> rai = weightByIntensity ? snt.getBdvTracingData() : null;
        if (weightByIntensity && rai == null) {
            error("Weight by intensity requires an image; no image data is available.");
            return;
        }
        final double[] spacing = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};

        try {
            final List<SeedPoint> seeds = new ArrayList<>();
            for (final Tree tree : trees) {
                // Node values (PointInImage#v) are general-purpose: a tree may already carry them for
                // something unrelated (Sholl, color-coding, a previous profiling pass...). assignValues()
                // below overwrites them with no backup of its own, so snapshot per-path here and restore
                // once this tree's detection is done, regardless of how it ends
                Map<Path, double[]> originalNodeValues = null;
                try {
                    if (weightByIntensity) {
                        originalNodeValues = new HashMap<>();
                        for (final Path p : tree.list()) {
                            final double[] snapshot = new double[p.size()];
                            for (int i = 0; i < snapshot.length; i++) snapshot[i] = p.getNodeValue(i);
                            originalNodeValues.put(p, snapshot);
                        }
                        status("Sampling intensities for " + tree.getLabel() + "...", false);
                        try {
                            new PathProfiler(tree, rai, spacing, snt.getSpacingUnits()).assignValues();
                        } catch (final IllegalArgumentException ex) {
                            // Skip only this tree (e.g. channel mismatch); do not cancel the whole batch
                            SNTUtils.log("Could not sample intensities for " + tree.getLabel() + ": " + ex.getMessage());
                            continue;
                        }
                    }
                    status("Detecting tufts in " + tree.getLabel() + "...", false);
                    final TuftDetector detector = new TuftDetector(Math.max(radius, snt.getAverageSeparation()));
                    detector.setWeightByThickness(weightByThickness);
                    detector.setWeightByIntensity(weightByIntensity);
                    detector.setPenalizeShortBranches(penalizeShortBranches);
                    final int prevSize = seeds.size();
                    // Pooled single pass across all requested types, so overlapping  candidates (e.g. a fork right
                    // next to a tip) are cross-suppressed rather than reported once per type
                    seeds.addAll(detector.detect(tree, scopeToTypes(scope)));
                    status(String.format("Detected %d tuft(s) in %s", (seeds.size() - prevSize), tree.getLabel()), false);
                } finally {
                    if (originalNodeValues != null) {
                        originalNodeValues.forEach(Path::setNodeValues);
                    }
                }
            }
            if (!seeds.isEmpty()) {
                final SeedOverlay overlay = snt.getSeedOverlay();
                if (replace) overlay.clear();
                overlay.addAll(seeds);
            }
            msg(String.format("%d tuft(s) detected across %s structure(s).", seeds.size(), trees.size()), "Detection Complete");
        } finally {
            resetUI();
        }
    }
}
