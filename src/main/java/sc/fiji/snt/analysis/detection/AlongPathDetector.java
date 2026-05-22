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

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;

import java.util.*;

/**
 * Detects features (boutons, varicosities) <em>along</em> traced paths by
 * analyzing longitudinal radius and intensity profiles. This complements
 * {@link PeripathDetector}, which detects features in perpendicular
 * cross-sections <em>around</em> paths.
 *
 * <p>The detection algorithm is inspired by the bouton detection in the
 * <a href="https://doi.org/10.1038/s41592-024-02401-8">CAR platform</a>
 * (Zhang et al., <i>Nature Methods</i>, 2024). At each node, the radius is
 * compared to the average radius of its neighbors within a sliding window.
 * A node is flagged as a candidate if its radius exceeds the neighbor average
 * by a configurable factor (default 1.5×). Optionally, an intensity threshold
 * can be applied using on-skeleton intensity sampled from the image.
 * Adjacent candidates are merged via greedy non-maximum suppression.
 *
 * <p>Usage:
 * <pre>
 *   AlongPathDetector.Config cfg = new AlongPathDetector.Config()
 *       .swellingFactor(1.5)     // flag nodes ≥ 1.5× neighbor average
 *       .windowSize(5)           // compare against 5 neighbors each side
 *       .minIntensity(120)       // minimum on-skeleton intensity (8-bit)
 *       .mergingDistance(3.0);   // NMS radius in calibrated units
 *   List&lt;Detection&gt; boutons = AlongPathDetector.detect(paths, image, cfg);
 * </pre>
 *
 * @author Tiago Ferreira
 * @see PeripathDetector
 * @see Detection
 * @see DetectorUtils
 */
public class AlongPathDetector {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    private AlongPathDetector() {
        // static utility class
    }

    /**
     * Detects swellings (boutons/varicosities) along the given paths by
     * analyzing the longitudinal radius profile and, optionally, on-skeleton
     * image intensity.
     *
     * @param paths the paths to analyze (must have radii for radius-based
     *              detection)
     * @param img   the image for intensity filtering; may be {@code null} if
     *              intensity filtering is not needed ({@code minIntensity ≤ 0})
     * @param cfg   detection parameters
     * @return list of detections, deduplicated and optionally assigned to
     *         nearest path
     * @throws IllegalArgumentException if paths is null/empty, or if intensity
     *         filtering is requested but no image is provided
     */
    @SuppressWarnings("unchecked")
    public static <T extends RealType<T>> List<Detection> detect(
            final Collection<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> img,
            final Config cfg) {

        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("No paths provided");
        if (cfg.minIntensity > 0 && img == null)
            throw new IllegalArgumentException(
                    "Image required when minIntensity > 0");

        // Set up interpolated image access if intensity filtering is enabled
        final RealRandomAccessible<FloatType> interpolant;
        if (img != null) {
            RandomAccessibleInterval<FloatType> floatImage = Converters.convert(
                    (RandomAccessibleInterval<T>) img, new RealFloatConverter<>(),
                    new FloatType());
            interpolant = Views.interpolate(
                    Views.extendZero(floatImage),
                    new NLinearInterpolatorFactory<>());
        } else {
            interpolant = null;
        }

        final List<Detection> rawDetections = new ArrayList<>();

        for (final Path path : paths) {
            if (path == null || path.size() < 3) continue;
            if (!path.hasRadii()) {
                SNTUtils.log("AlongPathDetector: skipping " + path.getName()
                        + " (no radii)");
                continue;
            }

            final double[] radii = DetectorUtils.prepareRadii(path);
            final int halfW = cfg.windowSize;
            final int n = path.size();

            // Build exclusion set for fork/tip nodes if requested
            final Set<Integer> excluded = (cfg.excludeJunctions)
                    ? getExcludedIndices(path, halfW) : Collections.emptySet();

            // Sample on-skeleton intensities if needed
            final double[] intensities;
            if (interpolant != null) {
                intensities = sampleOnSkeletonIntensities(path, interpolant);
            } else {
                intensities = null;
            }

            SNTUtils.log("AlongPathDetector: processing " + path.getName()
                    + " (" + n + " nodes, window=" + halfW
                    + (excluded.isEmpty() ? "" : ", excluding "
                    + excluded.size() + " junction/tip nodes") + ")");

            for (int i = 0; i < n; i++) {
                if (!excluded.isEmpty() && excluded.contains(i)) continue;

                // Compute average radius of neighbors within the window
                double sumNeighbor = 0;
                int countNeighbor = 0;
                for (int j = Math.max(0, i - halfW); j <= Math.min(n - 1,
                        i + halfW); j++) {
                    if (j == i) continue;
                    sumNeighbor += radii[j];
                    countNeighbor++;
                }
                if (countNeighbor == 0) continue;
                final double avgNeighbor = sumNeighbor / countNeighbor;

                // Radius swelling check
                if (avgNeighbor <= 0 || radii[i] < avgNeighbor * cfg.swellingFactor)
                    continue;

                // Intensity check (if enabled)
                final double nodeIntensity;
                if (intensities != null) {
                    nodeIntensity = intensities[i];
                    if (cfg.minIntensity > 0 && nodeIntensity < cfg.minIntensity)
                        continue;
                } else {
                    nodeIntensity = radii[i]; // use radius as score when no image
                }

                final Path.PathNode node = path.getNode(i);
                rawDetections.add(new Detection(
                        node.x, node.y, node.z,
                        nodeIntensity, path, i,
                        0.0)); // on-skeleton → distance = 0
            }
        }

        if (rawDetections.isEmpty()) return Collections.emptyList();

        // Deduplicate adjacent detections
        final double effectiveMergingDist = cfg.getEffectiveMergingDistance(paths);
        final List<Detection> deduplicated = DetectorUtils.deduplicate(
                rawDetections, effectiveMergingDist);

        if (cfg.assignToNearestPath && paths.size() > 1) {
            return DetectorUtils.assignToNearestPaths(deduplicated, paths);
        }

        return deduplicated;
    }

    /**
     * Convenience overload that performs radius-only detection (no image).
     *
     * @param paths the paths to analyze
     * @param cfg   detection parameters (minIntensity should be ≤ 0)
     * @return list of detections
     */
    public static List<Detection> detect(final Collection<Path> paths,
                                          final Config cfg) {
        return detect(paths, null, cfg);
    }

    /**
     * Builds a set of node indices to exclude from detection. Excluded zones
     * are: (a) nodes within {@code margin} of any junction (fork point), and
     * (b) the first/last {@code margin} nodes (tips). Junction indices are
     * obtained via {@link Path#findJunctionIndices()}, which covers both
     * child branch points on this path and this path's own fork from its
     * parent.
     *
     * @param path   the path to analyze
     * @param margin number of nodes to exclude on each side of a junction/tip
     * @return set of indices to skip during detection
     */
    private static Set<Integer> getExcludedIndices(final Path path,
                                                    final int margin) {
        final int n = path.size();
        final Set<Integer> excluded = new HashSet<>();

        // Exclude nodes near junctions (branch points)
        for (final int jIdx : path.findJunctionIndices()) {
            for (int k = Math.max(0, jIdx - margin);
                 k <= Math.min(n - 1, jIdx + margin); k++) {
                excluded.add(k);
            }
        }

        // Exclude tip nodes (first and last margin nodes)
        for (int k = 0; k < Math.min(margin, n); k++) excluded.add(k);
        for (int k = Math.max(0, n - margin); k < n; k++) excluded.add(k);

        return excluded;
    }

    /**
     * Samples on-skeleton intensity at each node using tri-linear
     * interpolation.
     */
    private static double[] sampleOnSkeletonIntensities(
            final Path path,
            final RealRandomAccessible<FloatType> interpolant) {

        final double xSp = path.getCalibration().pixelWidth;
        final double ySp = path.getCalibration().pixelHeight;
        final double zSp = path.getCalibration().pixelDepth;
        final int nDim = interpolant.numDimensions();
        final RealRandomAccess<FloatType> access = interpolant.realRandomAccess();
        final double[] position = new double[nDim];
        final double[] intensities = new double[path.size()];

        for (int i = 0; i < path.size(); i++) {
            final Path.PathNode node = path.getNode(i);
            position[0] = node.x / xSp;
            position[1] = node.y / ySp;
            if (nDim > 2) position[2] = node.z / zSp;
            intensities[i] = access.setPositionAndGet(position).getRealDouble();
        }
        return intensities;
    }

    /**
     * Configuration for along-path detection with builder-like setters.
     */
    public static final class Config {

        /**
         * Radius swelling factor. A node is flagged when its radius exceeds
         * the average of its neighbors by at least this factor.
         * <p>Default: {@code 1.5} (CAR paper default).</p>
         */
        double swellingFactor = 1.5;

        /**
         * Half-window size for neighbor radius averaging. The window extends
         * this many nodes on each side of the test node.
         * <p>Default: {@code 5}.</p>
         */
        int windowSize = 5;

        /**
         * Minimum on-skeleton intensity for a detection to be accepted. Set
         * to {@code 0} or negative to disable intensity filtering.
         * <p>Default: {@code 0} (disabled).</p>
         */
        double minIntensity = 0;

        /**
         * Distance for greedy non-maximum suppression in physical units.
         * If {@code ≤ 0}, defaults to 2× mean radius across all paths.
         * <p>Default: {@code -1} (auto).</p>
         */
        double mergingDistance = -1;

        /**
         * Whether to assign each detection to its nearest path, resolving
         * overlap between neighboring paths.
         * <p>Default: {@code true}.</p>
         */
        boolean assignToNearestPath = true;

        /**
         * Whether to exclude nodes near junctions (branch/fork points) and
         * tips from detection. These regions tend to have naturally enlarged
         * radii that produce false positives.
         * <p>Default: {@code true}.</p>
         */
        boolean excludeJunctions = true;

        /**
         * Sets {@link #swellingFactor}.
         *
         * @param v swelling threshold; clamped to ≥ 1.01
         * @return this config
         */
        public Config swellingFactor(final double v) {
            this.swellingFactor = Math.max(1.01, v);
            return this;
        }

        /**
         * Sets {@link #windowSize}.
         *
         * @param v half-window; clamped to [1, 50]
         * @return this config
         */
        public Config windowSize(final int v) {
            this.windowSize = Math.clamp(v, 1, 50);
            return this;
        }

        /**
         * Sets {@link #minIntensity}.
         *
         * @param v minimum intensity; 0 or negative disables filtering
         * @return this config
         */
        public Config minIntensity(final double v) {
            this.minIntensity = v;
            return this;
        }

        /**
         * Sets {@link #mergingDistance}.
         *
         * @param v merging distance in physical units; {@code -1} for auto
         * @return this config
         */
        public Config mergingDistance(final double v) {
            this.mergingDistance = v;
            return this;
        }

        /**
         * Sets {@link #assignToNearestPath}.
         *
         * @param b whether to reassign detections to nearest path
         * @return this config
         */
        public Config assignToNearestPath(final boolean b) {
            this.assignToNearestPath = b;
            return this;
        }

        /**
         * Sets {@link #excludeJunctions}.
         *
         * @param b whether to exclude junction/tip nodes from detection
         * @return this config
         */
        public Config excludeJunctions(final boolean b) {
            this.excludeJunctions = b;
            return this;
        }

        /**
         * Resolves the merging distance, falling back to 2× mean radius.
         */
        double getEffectiveMergingDistance(final Collection<Path> paths) {
            if (mergingDistance > 0) return mergingDistance;
            double sumR = 0;
            int count = 0;
            for (final Path p : paths) {
                if (p == null) continue;
                final double meanR = p.hasRadii() ? p.getMeanRadius()
                        : p.getMinimumSeparation() * 2;
                sumR += meanR;
                count++;
            }
            return count > 0 ? (sumR / count) * 2.0 : 1.0;
        }

        @Override
        public String toString() {
            return "Config{swellingFactor=" + swellingFactor +
                    ", windowSize=" + windowSize +
                    ", minIntensity=" + minIntensity +
                    ", mergingDistance=" + mergingDistance +
                    ", assignToNearestPath=" + assignToNearestPath +
                    ", excludeJunctions=" + excludeJunctions + "}";
        }
    }
}
