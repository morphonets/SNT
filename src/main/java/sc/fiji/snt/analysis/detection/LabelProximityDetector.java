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

package sc.fiji.snt.analysis.detection;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;

import java.util.*;

/**
 * Detects contact points between traced paths and labeled surfaces (from a
 * segmentation/label image). For each unique label, a calibrated Euclidean
 * Distance Transform (EDT) is computed, and path nodes within a configurable
 * distance threshold are emitted as {@link Detection}s.
 * <p>
 * Two detection modes are supported:
 * <ul>
 *   <li><b>Threshold mode</b> ({@link Config#distanceThreshold} &gt; 0):
 *       all nodes whose EDT distance is &le; the threshold are detected.</li>
 *   <li><b>Closest-approach mode</b> ({@link Config#distanceThreshold} = 0):
 *       for each path–label pair, only the node closest to the label boundary
 *       is detected.</li>
 * </ul>
 *
 * @author Tiago Ferreira
 * @see DetectorUtils#computeEDT(RandomAccessibleInterval, int, double[])
 * @see DetectorUtils#sampleDistancesWithIndices(Path, Img)
 */
public final class LabelProximityDetector {

    private LabelProximityDetector() {} // static utility class

    /**
     * Detects proximity contacts between paths and labeled surfaces.
     *
     * @param paths    the paths to analyze
     * @param labelImg the label (segmentation) image
     * @param config   detection configuration
     * @return list of detections (may be empty)
     * @throws IllegalArgumentException if {@code labelImg} is not a valid label image
     */
    public static List<Detection> detect(final Collection<Path> paths,
                                         final RandomAccessibleInterval<? extends RealType<?>> labelImg,
                                         final Config config) {
        Objects.requireNonNull(paths, "paths must not be null");
        Objects.requireNonNull(labelImg, "labelImg must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (!ImgUtils.isLabelImage(labelImg)) {
            throw new IllegalArgumentException("The provided image does not appear to be a label image.");
        }

        // Collect unique non-zero labels
        final Set<Integer> uniqueLabels = collectLabels(labelImg);
        if (uniqueLabels.isEmpty()) {
            SNTUtils.log("LabelProximityDetector: no non-zero labels found in image");
            return Collections.emptyList();
        }

        final double[] spacing = ImgUtils.resolveSpacing(labelImg, config.spacing);
        final List<Detection> allDetections = new ArrayList<>();

        for (final int labelVal : uniqueLabels) {
            SNTUtils.log("LabelProximityDetector: computing EDT for label " + labelVal);
            final Img<FloatType> edt = DetectorUtils.computeEDT(labelImg, labelVal, spacing);
            if (edt == null) continue;

            for (final Path path : paths) {
                if (path == null || path.size() == 0) continue;
                final List<double[]> samples = DetectorUtils.sampleDistancesWithIndices(path, edt);

                if (config.distanceThreshold > 0) {
                    // Threshold mode: emit all nodes within threshold
                    for (final double[] entry : samples) {
                        final int nodeIdx = (int) entry[0];
                        final double dist = entry[1];
                        if (dist <= config.distanceThreshold) {
                            allDetections.add(createDetection(path, nodeIdx, dist, labelVal));
                        }
                    }
                } else {
                    // Closest-approach mode: emit only the closest node per path–label pair
                    double minDist = Double.MAX_VALUE;
                    int minIdx = -1;
                    for (final double[] entry : samples) {
                        if (entry[1] < minDist) {
                            minDist = entry[1];
                            minIdx = (int) entry[0];
                        }
                    }
                    if (minIdx >= 0) {
                        allDetections.add(createDetection(path, minIdx, minDist, labelVal));
                    }
                }
            }
            // Let GC reclaim EDT before next label
        }

        // Post-processing: deduplicate and optionally reassign
        List<Detection> results = allDetections;
        if (config.mergingDistance > 0 && results.size() > 1) {
            results = DetectorUtils.deduplicate(results, config.mergingDistance);
        }
        if (config.assignToNearestPath && paths.size() > 1) {
            results = DetectorUtils.assignToNearestPaths(results, paths);
        }

        SNTUtils.log("LabelProximityDetector: " + results.size() + " detections from "
                + uniqueLabels.size() + " labels");
        return results;
    }

    private static Detection createDetection(final Path path, final int nodeIdx,
                                             final double distance,
                                             final int labelVal) {
        final PointInImage node = path.getNode(nodeIdx);
        // NMS keeps the highest score, so negate distance so that the closest
        // contact point (smallest distance) survives deduplication
        return new Detection(node.x, node.y, node.z,
                -distance,
                path, nodeIdx, 0, // distanceFromSkeleton = 0 (detection is on-skeleton)
                labelVal);
    }

    /**
     * Collects unique non-zero, positive integer label values from the image.
     * Negative values and non-integer values are excluded, consistent with
     * the convention that 0 = background and positive integers = labels.
     */
    private static Set<Integer> collectLabels(final RandomAccessibleInterval<? extends RealType<?>> img) {
        final Set<Integer> labels = new LinkedHashSet<>();
        final Cursor<? extends RealType<?>> cursor = img.cursor();
        while (cursor.hasNext()) {
            final double val = cursor.next().getRealDouble();
            if (val > 0 && val == Math.floor(val)) {
                labels.add((int) val);
            }
        }
        return labels;
    }

    /**
     * Configuration for {@link LabelProximityDetector}.
     */
    public static class Config {

        double distanceThreshold = 0;
        double mergingDistance = 0;
        boolean assignToNearestPath = true;
        double[] spacing;

        /**
         * Sets the maximum distance (in calibrated units) from a label boundary
         * for a node to be considered a contact point. Set to 0 for
         * closest-approach mode (one detection per path–label pair).
         *
         * @param threshold distance threshold (&ge; 0)
         * @return this config for chaining
         */
        public Config distanceThreshold(final double threshold) {
            this.distanceThreshold = Math.max(0, threshold);
            return this;
        }

        /**
         * Sets the minimum distance between detections for NMS deduplication.
         * Set to 0 to disable merging.
         *
         * @param distance merging distance (&ge; 0)
         * @return this config for chaining
         */
        public Config mergingDistance(final double distance) {
            this.mergingDistance = Math.max(0, distance);
            return this;
        }

        /**
         * Whether to reassign each detection to the nearest path when
         * multiple paths are close together.
         *
         * @param assign true to enable reassignment
         * @return this config for chaining
         */
        public Config assignToNearestPath(final boolean assign) {
            this.assignToNearestPath = assign;
            return this;
        }

        /**
         * Sets explicit pixel spacing for the EDT computation. If null,
         * spacing is resolved from the label image metadata.
         *
         * @param spacing pixel spacing per dimension, or null
         * @return this config for chaining
         */
        public Config spacing(final double[] spacing) {
            this.spacing = spacing;
            return this;
        }

        @Override
        public String toString() {
            return String.format("LabelProximityDetector.Config[threshold=%.3f, merging=%.3f, reassign=%b]",
                    distanceThreshold, mergingDistance, assignToNearestPath);
        }
    }
}
