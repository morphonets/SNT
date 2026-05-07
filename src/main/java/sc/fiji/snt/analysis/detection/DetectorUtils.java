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

import sc.fiji.snt.Path;
import sc.fiji.snt.util.PointDeduplicator;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

import java.util.*;

/**
 * Shared utilities for path-based detectors ({@link PeripathDetector},
 * {@link AlongPathDetector}).
 *
 * @author Tiago Ferreira
 */
public final class DetectorUtils {

    private DetectorUtils() {
        // static utility class
    }

    /**
     * Prepares a sanitized radius array for the given path. If the path has no
     * radii, returns an array filled with 2× minimum separation as fallback.
     *
     * @param path the path
     * @return sanitized radius array (one entry per node)
     */
    public static double[] prepareRadii(final Path path) {
        final double[] radii = new double[path.size()];

        if (path.hasRadii()) {
            for (int i = 0; i < path.size(); i++) {
                radii[i] = path.getNodeRadius(i);
            }
            final ij.measure.Calibration fallbackCal = path.getCalibration();
            final double fallback = Math.min(fallbackCal.pixelWidth,
                    Math.min(fallbackCal.pixelHeight, fallbackCal.pixelDepth)) * 2;
            for (int i = 0; i < radii.length; i++) {
                if (radii[i] <= 0 || Double.isNaN(radii[i])) {
                    radii[i] = interpolateRadius(radii, i, fallback);
                }
            }
        } else {
            final ij.measure.Calibration cal = path.getCalibration();
            final double minSep = Math.min(cal.pixelWidth,
                    Math.min(cal.pixelHeight, cal.pixelDepth));
            final double defaultR = minSep * 2;
            Arrays.fill(radii, defaultR);
        }
        return radii;
    }

    /**
     * Simple linear interpolation for a missing radius value at {@code idx},
     * using the nearest valid neighbors.
     *
     * @param radii    the radius array
     * @param idx      index of the missing value
     * @param fallback value to use if no valid neighbors exist
     * @return interpolated radius
     */
    public static double interpolateRadius(final double[] radii, final int idx,
                                           final double fallback) {
        int before = -1;
        for (int i = idx - 1; i >= 0; i--) {
            if (radii[i] > 0 && !Double.isNaN(radii[i])) {
                before = i;
                break;
            }
        }
        int after = -1;
        for (int i = idx + 1; i < radii.length; i++) {
            if (radii[i] > 0 && !Double.isNaN(radii[i])) {
                after = i;
                break;
            }
        }
        if (before >= 0 && after >= 0) {
            final double t = (double) (idx - before) / (after - before);
            return radii[before] + t * (radii[after] - radii[before]);
        } else if (before >= 0) {
            return radii[before];
        } else if (after >= 0) {
            return radii[after];
        }
        return fallback;
    }

    /**
     * Deduplicates detections using greedy non-maximum suppression by
     * intensity.
     *
     * @param detections     the raw detections
     * @param mergingDistance suppression radius in physical units
     * @return deduplicated list
     */
    public static List<Detection> deduplicate(final List<Detection> detections,
                                              final double mergingDistance) {
        final List<SNTPoint> points = new ArrayList<>(detections.size());
        final double[] scores = new double[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            final Detection v = detections.get(i);
            points.add(new PointInImage(v.x, v.y, v.z));
            scores[i] = v.intensity;
        }

        final PointDeduplicator dedup = new PointDeduplicator(points, scores, mergingDistance);
        final List<Integer> survivorIndices = dedup.runIndices();

        final List<Detection> result = new ArrayList<>(survivorIndices.size());
        for (final int idx : survivorIndices) {
            result.add(detections.get(idx));
        }
        return result;
    }

    /**
     * Reassigns each detection to the nearest path (by minimum node distance).
     *
     * @param detections the detections to reassign
     * @param allPaths   candidate paths
     * @return reassigned detections
     */
    public static List<Detection> assignToNearestPaths(final List<Detection> detections,
                                                       final Collection<Path> allPaths) {
        final List<Detection> reassigned = new ArrayList<>(detections.size());
        for (final Detection v : detections) {
            Path nearest = v.path;
            int nearestIdx = v.nodeIndex;
            double minDist2 = Double.MAX_VALUE;

            for (final Path p : allPaths) {
                if (p == null || p.size() == 0) continue;
                for (int i = 0; i < p.size(); i++) {
                    final PointInImage node = p.getNode(i);
                    final double dx = v.x - node.x;
                    final double dy = v.y - node.y;
                    final double dz = v.z - node.z;
                    final double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < minDist2) {
                        minDist2 = d2;
                        nearest = p;
                        nearestIdx = i;
                    }
                }
            }

            if (nearest != v.path || nearestIdx != v.nodeIndex) {
                reassigned.add(new Detection(v.x, v.y, v.z, v.intensity,
                        nearest, nearestIdx, Math.sqrt(minDist2)));
            } else {
                reassigned.add(v);
            }
        }
        return reassigned;
    }
}
