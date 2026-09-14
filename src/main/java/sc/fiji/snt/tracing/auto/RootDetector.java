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

import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.util.PointInImage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Detects candidate locations where several unconnected primary paths - e.g. tracings whose own soma wasn't imaged, or
 * simply not yet joined to one - may converge on a shared, unimaged origin, and reports them as ranked {@link SeedPoint}s.
 * <p>
 * Mirrors {@link TuftDetector}, but at the opposite topological end: instead of clustering a tree's own terminal
 * structure (tips converging on a tuft), it clusters the root node of every unconnected, non-soma primary
 * {@link Path} pooled across the scanned {@link Tree}s.
 * <p>
 * Unlike tuft candidates - which already belong to the same tree, so proximity only corroborates something already
 * topologically true - root candidates come from independent fragments with no prior relationship: proximity is
 * the <em>only</em> evidence tying them together. So, unlike {@link TuftDetector}, a root with no other root
 * nearby is not reported: on its own it's most likely just an ordinary, complete reconstruction, not a genuine
 * unimaged-soma candidate.
 *
 * @author Tiago Ferreira
 * @see TuftDetector
 */
public class RootDetector {

    private double radius;
    private boolean weightByThickness = true;
    private double percentileClip = 10.0;

    /**
     * Creates a detector with the given convergence radius.
     *
     * @param radius neighborhood radius (physical units) within which two unconnected roots are considered
     *               candidates for the same unimaged origin
     */
    public RootDetector(final double radius) {
        this.radius = radius;
    }

    /** Sets the convergence radius (physical units). */
    public void setRadius(final double radius) {
        this.radius = radius;
    }

    /**
     * Sets whether each root's recorded thickness ({@link PointInImage#radius}) is added to its neighbor count
     * when scoring: a thicker root stub is more plausibly soma-adjacent than a thin one. Default: true
     */
    public void setWeightByThickness(final boolean weightByThickness) {
        this.weightByThickness = weightByThickness;
    }

    /**
     * Sets the robustness margin (0-45) for confidence normalization; see
     * {@link sc.fiji.snt.seed.SeedConfidence#percentileClipNormalize(double[], double)}. Default: 10
     */
    public void setPercentileClip(final double percentileClip) {
        this.percentileClip = percentileClip;
    }

    /**
     * Detects root-convergence candidates pooled across every {@link Tree} in {@code trees} - deliberately not
     * per-tree, since the whole point is catching fragments that live in structurally unrelated {@code Tree}s (or
     * unconnected primary paths within one).
     *
     * @param trees the trees to scan
     * @return one {@link SeedPoint} per detected convergence, sorted by descending confidence. Empty if fewer than
     * two unconnected, non-soma primary paths converge anywhere across {@code trees}
     */
    public List<SeedPoint> detect(final Collection<Tree> trees) {
        final List<PointInImage> nodes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                if (!path.isPrimary() || path.getSWCType() == Path.SWC_SOMA) continue; // already anchored
                nodes.add(path.getNode(0));
                labels.add("root");
                weights.add(subtreeLength(path));
            }
        }
        if (nodes.size() < 2) return List.of(); // nothing to converge

        final List<DensityClusterer.Result> results = DensityClusterer.cluster(
                nodes, labels, weights, radius, weightByThickness, false, 2, "root-detector", percentileClip);
        return results.stream().map(DensityClusterer.Result::seed).toList();
    }

    /** Total cable length of the fragment rooted at {@code path} (itself plus every descendant). */
    private static double subtreeLength(final Path path) {
        double len = path.getLength();
        for (final Path child : path.getChildren()) len += subtreeLength(child);
        return len;
    }
}
