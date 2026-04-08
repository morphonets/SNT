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

package sc.fiji.snt.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy non-maximum suppression (NMS) for scored 3D points. Given a set of
 * points with associated scores and a merging distance, returns the subset of
 * points that survive suppression: starting from the highest-scoring point,
 * each point is accepted only if no already-accepted point lies within the
 * merging distance.
 * <p>
 * This is useful for deduplicating detections that appear in overlapping
 * neighborhoods (e.g., the same varicosity detected in adjacent cross-sections).
 *
 * @author Tiago Ferreira
 */
public class PointDeduplicator {

    private final List<ScoredPoint> points;
    private final double mergingDistance;

    /**
     * Creates a new deduplicator.
     *
     * @param points         the points to deduplicate
     * @param scores         score for each point (same length as {@code points});
     *                       higher scores are preferred during suppression
     * @param mergingDistance points closer than this (in the same units as the
     *                       point coordinates) are considered duplicates
     * @throws IllegalArgumentException if array lengths differ or distance is negative
     */
    public PointDeduplicator(final List<? extends SNTPoint> points,
                             final double[] scores,
                             final double mergingDistance) {
        if (points.size() != scores.length) {
            throw new IllegalArgumentException(
                    "points and scores must have the same length");
        }
        if (mergingDistance < 0) {
            throw new IllegalArgumentException(
                    "mergingDistance must be non-negative");
        }
        this.mergingDistance = mergingDistance;
        this.points = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            this.points.add(new ScoredPoint(points.get(i), scores[i], i));
        }
    }

    /**
     * Runs the greedy NMS.
     *
     * @return the surviving points, ordered by descending score
     */
    public List<SNTPoint> run() {
        if (points.isEmpty()) return Collections.emptyList();

        // Sort by descending score
        points.sort(Comparator.comparingDouble((ScoredPoint sp) -> sp.score).reversed());

        final double dist2 = mergingDistance * mergingDistance;
        final List<SNTPoint> survivors = new ArrayList<>();
        final List<ScoredPoint> accepted = new ArrayList<>();

        for (final ScoredPoint candidate : points) {
            boolean suppressed = false;
            for (final ScoredPoint kept : accepted) {
                final double dx = candidate.point.getX() - kept.point.getX();
                final double dy = candidate.point.getY() - kept.point.getY();
                final double dz = candidate.point.getZ() - kept.point.getZ();
                if (dx * dx + dy * dy + dz * dz < dist2) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                accepted.add(candidate);
                survivors.add(candidate.point);
            }
        }

        return survivors;
    }

    /**
     * Runs the greedy NMS and returns the original indices of surviving points.
     *
     * @return indices into the original input list, ordered by descending score
     */
    public List<Integer> runIndices() {
        if (points.isEmpty()) return Collections.emptyList();

        points.sort(Comparator.comparingDouble((ScoredPoint sp) -> sp.score).reversed());

        final double dist2 = mergingDistance * mergingDistance;
        final List<Integer> survivorIndices = new ArrayList<>();
        final List<ScoredPoint> accepted = new ArrayList<>();

        for (final ScoredPoint candidate : points) {
            boolean suppressed = false;
            for (final ScoredPoint kept : accepted) {
                final double dx = candidate.point.getX() - kept.point.getX();
                final double dy = candidate.point.getY() - kept.point.getY();
                final double dz = candidate.point.getZ() - kept.point.getZ();
                if (dx * dx + dy * dy + dz * dz < dist2) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                accepted.add(candidate);
                survivorIndices.add(candidate.originalIndex);
            }
        }

        return survivorIndices;
    }

    private record ScoredPoint(SNTPoint point, double score, int originalIndex) {}
}
