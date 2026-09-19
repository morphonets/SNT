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

import sc.fiji.snt.seed.SeedConfidence;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.util.PointInImage;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Generic node-density clustering + greedy non-max suppression: scores each node by the (weighted) number of
 * other nodes found within {@code radius} of it, optionally further weighted by thickness and/or intensity, then
 * greedily keeps the highest-scoring node of each mutually-suppressing neighborhood as a single representative
 * seed. Shared by {@link TuftDetector} (clustering terminal structure) and {@link RootDetector} (clustering
 * unconnected primary-path roots) so both rely on the exact same scoring/suppression code rather than two
 * independently-maintained copies.
 *
 * @author Tiago Ferreira
 */
final class DensityClusterer {

    private DensityClusterer() {} // static utility

    /**
     * One suppression-cluster's representative seed together with how many candidate nodes were folded into it
     * (including itself). {@link TuftDetector} ignores this; {@link RootDetector} uses it to require an actual
     * convergence between otherwise-unrelated candidates.
     */
    record Result(SeedPoint seed, int supportCount) {
    }

    /**
     * @param nodes             candidate nodes to cluster
     * @param nodeLabels        per-node {@link SeedPoint#type} label (parallel to {@code nodes})
     * @param nodeWeights       per-node base density weight (parallel to {@code nodes})
     * @param radius            neighborhood radius (physical units) defining a cluster
     * @param weightByThickness if true, each neighbor's {@link PointInImage#radius} is added to the score
     * @param weightByIntensity if true, each neighbor's min-max normalized {@link PointInImage#v} is added to the
     *                          score
     * @param minSupportCount   a cluster is only reported if at least this many candidates (including itself)
     *                          fell within {@code radius}. Applied before confidence normalization, so confidence
     *                          always reflects only the clusters actually being returned. Pass {@code 1} for no
     *                          filtering.
     * @param source            {@link SeedPoint#source} recorded on every returned seed
     * @param percentileClip    robustness margin (0-45) for the final confidence normalization; see
     *                          {@link SeedConfidence#percentileClipNormalize(double[], double)}
     * @return one {@link Result} per detected cluster meeting {@code minSupportCount}, sorted by descending
     * confidence. Empty if {@code nodes} is empty or nothing meets the threshold
     */
    static List<Result> cluster(final List<PointInImage> nodes, final List<String> nodeLabels,
                                 final List<Double> nodeWeights, final double radius,
                                 final boolean weightByThickness, final boolean weightByIntensity,
                                 final int minSupportCount, final String source, final double percentileClip) {
        if (nodes.isEmpty()) return List.of();
        if (nodes.size() == 1) {
            return (minSupportCount > 1) ? List.of()
                    : List.of(new Result(toSeed(nodes.getFirst(), 1.0, nodeLabels.getFirst(), source), 1));
        }

        final double[][] coords = new double[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            final PointInImage p = nodes.get(i);
            coords[i] = new double[]{p.getX(), p.getY(), p.getZ()};
        }
        final KDTree<PointInImage> kdtree = new KDTree<>(coords, nodes.toArray(new PointInImage[0]));

        // Score: local node density (each neighbor contributing its own weight), optionally further weighted by
        // thickness and/or intensity
        final double[] intensity = weightByIntensity ? normalizedIntensities(nodes) : null;
        final double[] scores = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            final List<Neighbor<double[], PointInImage>> neighbors = new ArrayList<>();
            kdtree.search(coords[i], radius, neighbors);
            double score = 0;
            for (final Neighbor<double[], PointInImage> n : neighbors) score += nodeWeights.get(n.index());
            if (weightByThickness) {
                for (final Neighbor<double[], PointInImage> n : neighbors) score += n.value().radius;
            }
            if (weightByIntensity) {
                for (final Neighbor<double[], PointInImage> n : neighbors) score += intensity[n.index()];
            }
            scores[i] = score;
        }

        // Rank nodes by score, then greedily suppress neighbors of kept nodes so each cluster is represented
        // by a single, highest-scoring seed
        final Integer[] order = new Integer[nodes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());

        final boolean[] suppressed = new boolean[nodes.size()];
        final List<PointInImage> kept = new ArrayList<>();
        final List<String> keptLabels = new ArrayList<>();
        final List<Double> keptScores = new ArrayList<>();
        final List<Integer> keptSupport = new ArrayList<>();
        for (final int i : order) {
            if (suppressed[i]) continue;
            final List<Neighbor<double[], PointInImage>> neighbors = new ArrayList<>();
            kdtree.search(coords[i], radius, neighbors);
            kept.add(nodes.get(i));
            keptLabels.add(nodeLabels.get(i));
            keptScores.add(scores[i]);
            keptSupport.add(neighbors.size());
            for (final Neighbor<double[], PointInImage> n : neighbors) suppressed[n.index()] = true;
        }

        // Drop clusters below threshold *before* normalizing, so confidence reflects only what's returned
        final List<PointInImage> finalNodes = new ArrayList<>();
        final List<String> finalLabels = new ArrayList<>();
        final List<Double> finalScores = new ArrayList<>();
        final List<Integer> finalSupport = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            if (keptSupport.get(i) < minSupportCount) continue;
            finalNodes.add(kept.get(i));
            finalLabels.add(keptLabels.get(i));
            finalScores.add(keptScores.get(i));
            finalSupport.add(keptSupport.get(i));
        }
        if (finalNodes.isEmpty()) return List.of();

        final double[] scoresArray = finalScores.stream().mapToDouble(Double::doubleValue).toArray();
        final double[] confidences = SeedConfidence.percentileClipNormalize(scoresArray, percentileClip);

        final List<Result> results = new ArrayList<>(finalNodes.size());
        for (int i = 0; i < finalNodes.size(); i++) {
            results.add(new Result(toSeed(finalNodes.get(i), confidences[i], finalLabels.get(i), source), finalSupport.get(i)));
        }
        // NaN confidence ("no basis to judge", see SeedConfidence) must not win "most
        // confident" via Double.compare's NaN-is-greatest convention; sort it last instead.
        results.sort(Comparator.comparingDouble((Result r) -> {
            final double c = r.seed().confidence;
            return Double.isNaN(c) ? Double.NEGATIVE_INFINITY : c;
        }).reversed());
        return results;
    }

    /**
     * Min-max normalizes {@code PointInImage#v} across {@code nodes} into {@code [0, 1]}. {@code NaN} values
     * (unassigned/unprofiled nodes) map to {@code 0}. If every value is {@code NaN}, or all equal, every result is
     * {@code 0}.
     */
    private static double[] normalizedIntensities(final List<PointInImage> nodes) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (final PointInImage p : nodes) {
            if (Double.isNaN(p.v)) continue;
            if (p.v < min) min = p.v;
            if (p.v > max) max = p.v;
        }
        final double range = (max > min) ? max - min : 1;
        final double[] out = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            final double v = nodes.get(i).v;
            out[i] = Double.isNaN(v) ? 0 : (v - min) / range;
        }
        return out;
    }

    private static SeedPoint toSeed(final PointInImage p, final double confidence, final String label,
                                     final String source) {
        return new SeedPoint(p.getX(), p.getY(), p.getZ(), confidence, p.radius,
                SeedPoint.CT_UNSET, SeedPoint.CT_UNSET, label, source);
    }
}
