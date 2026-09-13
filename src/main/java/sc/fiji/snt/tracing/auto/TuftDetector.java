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

package sc.fiji.snt.tracing.auto;

import org.apache.commons.lang3.StringUtils;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.util.PointInImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects tufts (clusters of nearby terminal structure) in a {@link Tree} and reports them as ranked {@link SeedPoint}s.
 * <p>
 * Four node populations can be scanned, selected via {@link Type}:
 * <ul>
 *   <li>{@link Type#TIPS}: the tree's end points ({@link DirectedWeightedGraph#getTips()})</li>
 *   <li>{@link Type#BRANCH_POINTS}: the tree's branch points ({@link DirectedWeightedGraph#getBPs()})</li>
 *   <li>{@link Type#TERMINAL_BRANCH_POINTS}: branch points that are the immediate parent of at least one terminal
 *   branch, i.e., forks where twigs actually converge, as opposed to internal forks anywhere in the tree. More specific
 *       (and topologically more robust) than {@link Type#BRANCH_POINTS} for tuft detection</li>
 *   <li>{@link Type#TERMINAL_BRANCHES}: the centroid of each terminal branch
 *       (a {@link TreeStatistics#getBranches()} segment with no children), i.e., the  last unbranched run of nodes
 *       leading to a tip. Useful when tufts are formed by several short terminal twigs converging in one spot rather
 *       than by a single  prominent tip/fork node. Because a discontiguous/beaded neurite can fragment into several
 *       short, spatially-close but topologically-unrelated terminal  branches (mimicking a genuine tuft), short
 *       branches are down-weighted by  default (see {@link #setPenalizeShortBranches(boolean)})</li>
 *   <li>{@link Type#BRANCH_DENSITY}: every node of every branch, weighted by the arc length it locally represents.
 *   Measures the 'amount of neurite' in a  neighborhood rather than a count of discrete tip/branch-point nodes, so
 *       it degrades gracefully under a discontiguous/beaded neurite instead of spiking (extra spurious tips) or
 *       dropping out (a lost branch point).  Scans the whole tree, so a dense non-terminal trunk/shaft can also
 *       score as a hotspot</li>
 *   <li>{@link Type#TERMINAL_BRANCH_DENSITY}: as {@link Type#BRANCH_DENSITY}, but restricted to terminal branches only
 *       keeping the measure focused on terminal structure like the other {@code Type}s</li>
 * </ul>
 * <p>
 * A node's score is the (weighted) number of other nodes found within {@link #radius} of it, optionally further
 * weighted by thickness and/or intensity. Nodes belonging to the same tuft mutually reinforce each other's score,
 * while isolated nodes score low. Candidates are then greedily suppressed by distance (highest score first) so that
 * each tuft is reported once, at the location of its highest-scoring node.
 * </p>
 *
 * @author Tiago Ferreira
 * @see DirectedWeightedGraph#getTips()
 * @see DirectedWeightedGraph#getBPs()
 * @see TreeStatistics#getBranches()
 */
public class TuftDetector {

    private double radius;
    private boolean weightByThickness = true;
    private boolean weightByIntensity = true;
    private boolean penalizeShortBranches = true;

    public enum Type {
        TIPS, BRANCH_POINTS, TERMINAL_BRANCH_POINTS, TERMINAL_BRANCHES, BRANCH_DENSITY, TERMINAL_BRANCH_DENSITY;

        @Override
        public String toString() {
            return StringUtils.capitalize(super.toString().toLowerCase().replace('_', ' '));
        }
    }

    /**
     * Creates a detector with the given tuft radius.
     *
     * @param radius neighborhood radius (physical units) defining a tuft. Nodes within this distance of each other are
     *               treated as candidates for the same tuft
     */
    public TuftDetector(final double radius) {
        this.radius = radius;
    }

    /**
     * Sets the tuft radius (physical units)
     */
    public void setRadius(final double radius) {
        this.radius = radius;
    }

    /**
     * Sets whether each node's thickness ({@link PointInImage#radius}, or its per-branch average for
     * {@link Type#TERMINAL_BRANCHES}) is added to its neighbor count when scoring. Default: true
     */
    public void setWeightByThickness(final boolean weightByThickness) {
        this.weightByThickness = weightByThickness;
    }

    /**
     * Sets whether each node's sampled intensity ({@link PointInImage#v}, min-max normalized across the scanned
     * population) is added to its neighbor count when scoring, in addition to (or instead of) thickness. Nodes with
     * unassigned/{@code NaN} values contribute {@code 0}. Requires the tree to have been profiled beforehand (e.g.
     * via {@code PathProfiler#assignValues()}); otherwise every node contributes {@code 0} and this has no effect.
     * Default: false
     */
    public void setWeightByIntensity(final boolean weightByIntensity) {
        this.weightByIntensity = weightByIntensity;
    }

    /**
     * Sets whether short terminal branches have their contribution to the density score down-weighted, for
     * {@link Type#TERMINAL_BRANCHES} only. A discontiguous (e.g. beaded) neurite can fragment into several short,
     * spatially-close but topologically-unrelated terminal branches, which would otherwise look like a  genuine dense
     * tuft. Weight is {@code min(1, branchLength / radius)}, so branches at least as long as {@link #radius} are
     * unaffected. Default: true
     */
    public void setPenalizeShortBranches(final boolean penalizeShortBranches) {
        this.penalizeShortBranches = penalizeShortBranches;
    }

    /**
     * Detects tufts of the given type in {@code tree}.
     *
     * @param tree the tree to scan
     * @param type which node population to scan (see {@link Type})
     * @return one {@link SeedPoint} per detected tuft, sorted by descending confidence. Empty if {@code tree} has no
     * matching nodes
     */
    public List<SeedPoint> detect(final Tree tree, final Type type) {
        return detect(tree, List.of(type));
    }

    /**
     * Detects tufts across several node populations in {@code tree} in a single pooled pass: candidates from every
     * requested {@link Type} are merged into one KDTree/scoring/suppression run, so a tuft that happens to show up in
     * more than one (e.g. a fork sitting right next to a tip) is still reported once, rather than once per type.
     *
     * @param tree  the tree to scan
     * @param types which node population(s) to scan (see {@link Type})
     * @return one {@link SeedPoint} per detected tuft, sorted by descending confidence. Empty if {@code tree} has no
     * matching nodes
     */
    public List<SeedPoint> detect(final Tree tree, final List<Type> types) {
        if (types == null || types.isEmpty()) return List.of();
        final List<PointInImage> nodes = new ArrayList<>();
        final List<Type> nodeTypes = new ArrayList<>();
        final List<Double> nodeWeights = new ArrayList<>();
        for (final Type type : types) {
            final Candidates candidates = getCandidates(tree, type);
            for (int i = 0; i < candidates.nodes().size(); i++) {
                nodes.add(candidates.nodes().get(i));
                nodeTypes.add(type);
                nodeWeights.add(candidates.weights()[i]);
            }
        }
        return detect(nodes, nodeTypes, nodeWeights);
    }

    private List<SeedPoint> detect(final List<PointInImage> nodes, final List<Type> nodeTypes,
                                   final List<Double> nodeWeights) {
        if (nodes.isEmpty()) return List.of();
        final List<String> labels = new ArrayList<>(nodeTypes.size());
        for (final Type type : nodeTypes) labels.add(type.toString().toLowerCase());
        final List<DensityClusterer.Result> results = DensityClusterer.cluster(
                nodes, labels, nodeWeights, radius, weightByThickness, weightByIntensity, 1, "tuft-detector");
        return results.stream().map(DensityClusterer.Result::seed).toList();
    }

    /**
     * A candidate node population together with its per-node density weight (see {@link #detect})
     */
    private record Candidates(List<PointInImage> nodes, double[] weights) {
    }

    private Candidates getCandidates(final Tree tree, final Type type) {
        return switch (type) {
            case TIPS -> uniform(new ArrayList<>(new DirectedWeightedGraph(tree).getTips()));
            case BRANCH_POINTS -> uniform(new ArrayList<>(new DirectedWeightedGraph(tree).getBPs()));
            case TERMINAL_BRANCH_POINTS -> uniform(terminalBranchPoints(tree));
            case TERMINAL_BRANCHES -> terminalBranchCentroids(tree);
            case BRANCH_DENSITY -> branchDensityNodes(tree, false);
            case TERMINAL_BRANCH_DENSITY -> branchDensityNodes(tree, true);
        };
    }

    private static Candidates uniform(final List<PointInImage> nodes) {
        final double[] weights = new double[nodes.size()];
        Arrays.fill(weights, 1.0);
        return new Candidates(nodes, weights);
    }

    /**
     * Branch points that are the immediate parent of at least one terminal branch, i.e., forks where twigs actually
     * converge, rather than any internal fork ({@link DirectedWeightedGraph#getBPs()} makes no such distinction). A
     * fork with several terminal children is still reported once here; local density scoring naturally rewards nearby
     * convergence once candidates are pooled.
     */
    private static List<PointInImage> terminalBranchPoints(final Tree tree) {
        final List<Path> branches = new TreeStatistics(tree).getBranches();
        final Set<PointInImage> seen = new LinkedHashSet<>();
        for (final Path branch : branches) {
            if (!branch.getChildren().isEmpty()) continue; // not terminal
            final PointInImage bp = branch.getBranchPoint();
            if (bp == null) continue; // branch starts at the root; no parent fork
            seen.add(bp);
        }
        return new ArrayList<>(seen);
    }

    /**
     * One centroid per terminal branch: a {@link TreeStatistics#getBranches()} segment with no children, i.e., the run
     * of nodes from the nearest branch point (or root) down to a tip. The centroid's radius is the average thickness
     * of the branch's nodes. Each centroid's weight is {@code min(1, branchLength / radius)} when
     * {@link #penalizeShortBranches} is set (the default), {@code 1} otherwise.
     */
    private Candidates terminalBranchCentroids(final Tree tree) {
        final List<Path> branches = new TreeStatistics(tree).getBranches();
        final List<PointInImage> centroids = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        for (final Path branch : branches) {
            if (!branch.getChildren().isEmpty()) continue; // not terminal
            final List<PointInImage> branchNodes = branch.getNodes();
            if (branchNodes.isEmpty()) continue;
            double sx = 0, sy = 0, sz = 0, sr = 0, sv = 0;
            int vCount = 0;
            for (final PointInImage n : branchNodes) {
                sx += n.getX();
                sy += n.getY();
                sz += n.getZ();
                sr += n.radius;
                if (!Double.isNaN(n.v)) {
                    sv += n.v;
                    vCount++;
                }
            }
            final int n = branchNodes.size();
            final PointInImage centroid = new PointInImage(sx / n, sy / n, sz / n);
            centroid.radius = sr / n;
            centroid.v = (vCount > 0) ? sv / vCount : Double.NaN;
            centroids.add(centroid);
            weights.add((penalizeShortBranches && radius > 0) ? Math.min(1.0, branch.getLength() / radius) : 1.0);
        }
        final double[] w = new double[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new Candidates(centroids, w);
    }

    /**
     * Every node of every branch, each weighted by the arc length it locally represents (average of the distances to
     * its immediate neighbors along the branch). Shared nodes at branch points are visited once per incident branch,
     * so their weight accumulates from every side, reflecting the extra material converging there.
     *
     * @param terminalOnly if true, only terminal branches are scanned (see {@link Type#TERMINAL_BRANCH_DENSITY});
     *                     otherwise internal branches are included too (see {@link Type#BRANCH_DENSITY})
     */
    private static Candidates branchDensityNodes(final Tree tree, final boolean terminalOnly) {
        final List<Path> branches = new TreeStatistics(tree).getBranches();
        final List<PointInImage> nodes = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        for (final Path branch : branches) {
            if (terminalOnly && !branch.getChildren().isEmpty()) continue; // not terminal
            final List<PointInImage> branchNodes = branch.getNodes();
            final int n = branchNodes.size();
            for (int i = 0; i < n; i++) {
                final double prev = (i > 0) ? branchNodes.get(i - 1).distanceTo(branchNodes.get(i)) : 0;
                final double next = (i < n - 1) ? branchNodes.get(i).distanceTo(branchNodes.get(i + 1)) : 0;
                nodes.add(branchNodes.get(i));
                weights.add((prev + next) / 2.0);
            }
        }
        final double[] w = new double[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new Candidates(nodes, w);
    }
}
