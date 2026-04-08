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

package sc.fiji.snt.analysis;

import ij.plugin.filter.MaximumFinder;
import ij.process.FloatProcessor;
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
import sc.fiji.snt.tracing.CrossSectionUtils;
import sc.fiji.snt.util.PointDeduplicator;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Detects intensity maxima (varicosities, spines, synaptic puncta) in annular
 * cross-sections around traced paths. At each path node, a perpendicular
 * cross-section is sampled from the image (see {@link sc.fiji.snt.PathFitter}),
 * masked to an annulus defined by the node's radius (inner) and a configurable
 * outer radius, and then analyzed for local maxima with prominence filtering
 * via {@link ij.plugin.filter.MaximumFinder}. Detections from adjacent
 * cross-sections are deduplicated via greedy non-maximum suppression.
 *
 * <p>Usage:
 * <pre>
 *   PeripathDetector.Config cfg = new PeripathDetector.Config()
 *       .innerRadiusMultiplier(0.5)  // start at 0.5× node radius (closer to skeleton)
 *       .outerRadiusMultiplier(2.0)  // search up to 2× node radius
 *       .prominence(50)              // MaximumFinder noise tolerance
 *       .mergingDistance(1.5);        // NMS radius in calibrated units
 *   List&lt;PeripathDetector.Detection&gt; hits =
 *       PeripathDetector.detect(paths, image, cfg);
 * </pre>
 *
 * @author Tiago Ferreira
 * @see CrossSectionUtils
 * @see PointDeduplicator
 */
public class PeripathDetector {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private PeripathDetector() {
        // static utility class
    }

    /**
     * Detects intensity maxima along the given paths.
     *
     * @param paths the paths to analyze (must have calibrated spacing)
     * @param img   the detection image (single channel). Coordinates are in the
     *              same space as the paths.
     * @param cfg   detection parameters
     * @return list of detections, deduplicated and optionally assigned to
     * nearest path
     * @throws IllegalArgumentException if paths or image is null/empty
     */
    public static <T extends RealType<T>> List<Detection> detect(
            final Collection<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> img,
            final Config cfg) {

        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("No paths provided");
        if (img == null)
            throw new IllegalArgumentException("No image provided");

        // Prepare interpolated image access
        final RandomAccessibleInterval<FloatType> floatImage = Converters.convert(
                (RandomAccessibleInterval<T>) img, new RealFloatConverter<>(), new FloatType());
        final RealRandomAccessible<FloatType> interpolant = Views.interpolate(
                Views.extendZero(floatImage), new NLinearInterpolatorFactory<>());

        // Collect raw detections across all paths
        final List<Detection> rawDetections = new ArrayList<>();

        for (final Path path : paths) {
            if (path == null || path.size() < 2) continue;

            // Sanitize radii on a working copy of the radius data
            final double[] radii = prepareRadii(path);

            // Ensure tangents exist
            if (!path.hasTangents()) {
                path.setGuessedTangents(2);
            }

            final double xSp = path.getCalibration().pixelWidth;
            final double ySp = path.getCalibration().pixelHeight;
            final double zSp = path.getCalibration().pixelDepth;
            final double[] tangent = new double[3];
            final int pointsEitherSide = 4;

            // One RealRandomAccess per path (thread-safety if parallelized)
            final RealRandomAccess<FloatType> realAccess = interpolant.realRandomAccess();

            SNTUtils.log("PeripathDetector: processing " + path.getName() +
                    " (" + path.size() + " nodes)");

            for (int i = 0; i < path.size(); i++) {

                final Path.PathNode node = path.getNode(i);
                path.getTangent(i, pointsEitherSide, tangent);

                // Tangent plane basis
                final double[][] basis = CrossSectionUtils.computeTangentPlaneBasis(
                        tangent[0], tangent[1], tangent[2]);
                final double[] aBasis = basis[0];
                final double[] bBasis = basis[1];

                // Scales
                final double scaleIso = CrossSectionUtils.computeIsotropicScale(
                        aBasis, bBasis, xSp, ySp, zSp);

                // Determine radii for this node
                final double innerR = cfg.getEffectiveInnerRadius(radii[i]); // physical units
                final double outerR = cfg.getEffectiveOuterRadius(radii[i]);

                // Grid size
                final int side = CrossSectionUtils.computeGridSize(outerR, scaleIso, cfg.maxSectionSize);

                // Sample cross-section
                final FloatProcessor fp = CrossSectionUtils.sampleCrossSection(
                        side, scaleIso, scaleIso,
                        node.x, node.y, node.z,
                        aBasis, bBasis,
                        xSp, ySp, zSp,
                        realAccess);

                // Apply annular mask (convert physical radii to grid pixels)
                final double innerRGrid = innerR / scaleIso;
                final double outerRGrid = outerR / scaleIso;
                CrossSectionUtils.applyAnnularMask(fp, innerRGrid, outerRGrid);

                // Find maxima with prominence filtering
                final MaximumFinder mf = new MaximumFinder();
                final Polygon maxima = mf.getMaxima(fp, cfg.prominence, true, true);

                if (maxima == null || maxima.npoints == 0) continue;

                // Back-project each maximum to 3D world coordinates
                for (int m = 0; m < maxima.npoints; m++) {
                    final int gx = maxima.xpoints[m];
                    final int gy = maxima.ypoints[m];

                    final double[] worldPos = CrossSectionUtils.backProject(
                            gx, gy, side, scaleIso,
                            node.x, node.y, node.z,
                            aBasis, bBasis);

                    // For 2D images, clamp Z to the node's Z to avoid floating-point noise
                    if (img.numDimensions() == 2) {
                        worldPos[2] = node.z;
                    }

                    final float intensity = fp.getf(gx + gy * side);

                    // Distance from skeleton in physical units
                    final double centerGrid = (side - 1) / 2.0;
                    final double distGrid = Math.sqrt(
                            (gx - centerGrid) * (gx - centerGrid) +
                                    (gy - centerGrid) * (gy - centerGrid));
                    final double distPhysical = distGrid * scaleIso;

                    rawDetections.add(new Detection(
                            worldPos[0], worldPos[1], worldPos[2],
                            intensity, path, i, distPhysical));
                }
            }
        }

        if (rawDetections.isEmpty()) return Collections.emptyList();

        // Deduplicate across cross-sections
        final double effectiveMergingDist = cfg.getEffectiveMergingDistance(paths);
        final List<Detection> deduplicated = deduplicate(rawDetections, effectiveMergingDist);

        // Optionally reassign each detection to its nearest path
        if (cfg.assignToNearestPath && paths.size() > 1) {
            return assignToNearestPaths(deduplicated, paths);
        }

        return deduplicated;
    }

    /**
     * Creates a binary torus mask around the given paths. At each node, the
     * annular search region (as defined by the {@link Config}) is back-projected
     * into the output image, painting voxels inside the torus with the specified
     * fill value.
     *
     * <p>Usage:
     * <pre>
     *   // Create an empty mask matching the source image dimensions
     *   Img&lt;UnsignedByteType&gt; mask = ArrayImgs.unsignedBytes(width, height, depth);
     *   PeripathDetector.Config cfg = new PeripathDetector.Config()
     *       .innerRadiusMultiplier(0.8)
     *       .outerRadiusMultiplier(2.0);
     *   PeripathDetector.createTorusMask(paths, mask, cfg, 255);
     * </pre>
     *
     * @param <T>       pixel type of the output image
     * @param paths     the paths to generate the torus for
     * @param output    the output image to paint into (must be pre-allocated with
     *                  dimensions matching the source image in pixel space);
     *                  existing non-zero voxels are preserved
     * @param cfg       detection/annulus parameters (inner/outer radius settings)
     * @param fillValue the value to write into torus voxels
     * @throws IllegalArgumentException if paths or output is null/empty
     */
    public static <T extends RealType<T>> void createTorusMask(
            final Collection<Path> paths,
            final RandomAccessibleInterval<T> output,
            final Config cfg,
            final double fillValue) {

        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("No paths provided");
        if (output == null)
            throw new IllegalArgumentException("No output image provided");

        for (final Path path : paths) {
            if (path == null || path.size() < 2) continue;

            final double[] radii = prepareRadii(path);

            if (!path.hasTangents()) {
                path.setGuessedTangents(2);
            }

            final double xSp = path.getCalibration().pixelWidth;
            final double ySp = path.getCalibration().pixelHeight;
            final double zSp = path.getCalibration().pixelDepth;
            final double[] tangent = new double[3];
            final int pointsEitherSide = 4;

            SNTUtils.log("PeripathDetector: painting torus for " + path.getName() +
                    " (" + path.size() + " nodes)");

            for (int i = 0; i < path.size(); i++) {
                final Path.PathNode node = path.getNode(i);
                path.getTangent(i, pointsEitherSide, tangent);

                final double[][] basis = CrossSectionUtils.computeTangentPlaneBasis(
                        tangent[0], tangent[1], tangent[2]);
                final double[] aBasis = basis[0];
                final double[] bBasis = basis[1];

                final double scaleIso = CrossSectionUtils.computeIsotropicScale(
                        aBasis, bBasis, xSp, ySp, zSp);

                final double innerR = cfg.getEffectiveInnerRadius(radii[i]);
                final double outerR = cfg.getEffectiveOuterRadius(radii[i]);
                final int side = CrossSectionUtils.computeGridSize(outerR, scaleIso, cfg.maxSectionSize);
                final double innerRGrid = innerR / scaleIso;
                final double outerRGrid = outerR / scaleIso;

                CrossSectionUtils.paintAnnulus(output, side, scaleIso,
                        node.x, node.y, node.z,
                        aBasis, bBasis,
                        xSp, ySp, zSp,
                        innerRGrid, outerRGrid,
                        fillValue);
            }
        }
    }

    /**
     * Prepares a sanitized radius array for the given path. If the path has no
     * radii, returns an array filled with 2× minimum separation as fallback.
     */
    private static double[] prepareRadii(final Path path) {
        final double[] radii = new double[path.size()];

        if (path.hasRadii()) {
            // Copy existing radii
            for (int i = 0; i < path.size(); i++) {
                radii[i] = path.getNodeRadius(i);
            }
            // Interpolate missing/invalid values
            final ij.measure.Calibration fallbackCal = path.getCalibration();
            final double fallback = Math.min(fallbackCal.pixelWidth,
                    Math.min(fallbackCal.pixelHeight, fallbackCal.pixelDepth)) * 2;
            for (int i = 0; i < radii.length; i++) {
                if (radii[i] <= 0 || Double.isNaN(radii[i])) {
                    radii[i] = interpolateRadius(radii, i, fallback);
                }
            }
        } else {
            // No radii at all: use smallest voxel dimension as default
            final ij.measure.Calibration cal = path.getCalibration();
            final double minSep = Math.min(cal.pixelWidth,
                    Math.min(cal.pixelHeight, cal.pixelDepth));
            final double defaultR = minSep * 2;
            Arrays.fill(radii, defaultR);
        }
        return radii;
    }

    /**
     * Simple linear interpolation for a missing radius value at index {@code idx},
     * using the nearest valid neighbors. Falls back to {@code fallback} if no
     * valid neighbors exist.
     */
    private static double interpolateRadius(final double[] radii, final int idx, final double fallback) {
        // Find nearest valid neighbor before
        int before = -1;
        for (int i = idx - 1; i >= 0; i--) {
            if (radii[i] > 0 && !Double.isNaN(radii[i])) {
                before = i;
                break;
            }
        }
        // Find nearest valid neighbor after
        int after = -1;
        for (int i = idx + 1; i < radii.length; i++) {
            if (radii[i] > 0 && !Double.isNaN(radii[i])) {
                after = i;
                break;
            }
        }
        if (before >= 0 && after >= 0) {
            // Linear interpolation
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
     * Deduplicates detections using greedy NMS by intensity.
     */
    private static List<Detection> deduplicate(final List<Detection> detections,
                                                final double mergingDistance) {
        // Build SNTPoint and score arrays for PointDeduplicator
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
     * This resolves detections in overlapping torus regions between adjacent paths.
     */
    private static List<Detection> assignToNearestPaths(final List<Detection> detections,
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

    /**
     * Immutable configuration with builder-like setters.
     */
    public static final class Config {

        /**
         * Inner search radius as multiplier of per-node radius.
         * Ignored if {@link #innerRadius} is set explicitly ({@code > 0}).
         * Values &lt; 1.0 shrink the annulus inward (toward the skeleton),
         * exposing maxima near the neurite membrane.
         * <p>Default: {@code 1.0} (use node radius as-is).</p>
         */
        double innerRadiusMultiplier = 1.0;

        /**
         * Inner search radius in physical units. If {@code > 0}, overrides
         * {@link #innerRadiusMultiplier}.
         * <p>Default: {@code -1} (use multiplier).</p>
         */
        double innerRadius = -1;

        /**
         * Outer search radius as multiplier of per-node radius.
         * Ignored if {@link #outerRadius} is set explicitly ({@code > 0}).
         * <p>Default: {@code 2.0}.</p>
         */
        double outerRadiusMultiplier = 2.0;

        /**
         * Outer search radius in physical units. If {@code > 0}, overrides
         * {@link #outerRadiusMultiplier}.
         * <p>Default: {@code -1} (use multiplier).</p>
         */
        double outerRadius = -1;

        /**
         * {@link MaximumFinder} prominence (noise tolerance). Maxima must
         * protrude more than this value above the surrounding saddle to be
         * accepted.
         * <p>Default: {@code 10.0}.</p>
         */
        double prominence = 10.0;

        /**
         * Distance for greedy non-maximum suppression across adjacent
         * cross-sections, in physical units. If {@code ≤ 0}, defaults
         * to the effective outer radius.
         * <p>Default: {@code -1} (auto).</p>
         */
        double mergingDistance = -1;

        /**
         * Maximum cross-section grid size in pixels. Caps memory usage per
         * node. Larger values allow detection further from the skeleton.
         * <p>Default: {@code 100}.</p>
         */
        int maxSectionSize = 100;

        /**
         * Whether to assign each detection to its nearest path, resolving
         * overlap between neighboring paths' search annuli.
         * <p>Default: {@code true}.</p>
         */
        boolean assignToNearestPath = true;

        /**
         * Sets {@link #innerRadiusMultiplier}.
         *
         * @param v multiplier applied to each node's radius; clamped to [0, outerRadiusMultiplier)
         * @return this config (for chaining)
         */
        public Config innerRadiusMultiplier(final double v) {
            this.innerRadiusMultiplier = Math.max(0, v);
            return this;
        }

        /**
         * Sets {@link #innerRadius}. If {@code > 0}, this takes precedence over
         * {@link #innerRadiusMultiplier}.
         *
         * @param v inner radius in physical units; set to {@code -1} to use multiplier mode
         * @return this config (for chaining)
         */
        public Config innerRadius(final double v) {
            this.innerRadius = v;
            return this;
        }

        /**
         * Sets {@link #outerRadiusMultiplier}.
         *
         * @param v multiplier applied to each node's radius; clamped to {@code ≥ 1.01}
         * @return this config (for chaining)
         */
        public Config outerRadiusMultiplier(final double v) {
            this.outerRadiusMultiplier = Math.max(1.01, v);
            return this;
        }

        /**
         * Sets {@link #outerRadius}. If {@code > 0}, this takes precedence over
         * {@link #outerRadiusMultiplier}.
         *
         * @param v outer radius in physical units; set to {@code -1} to use multiplier mode
         * @return this config (for chaining)
         */
        public Config outerRadius(final double v) {
            this.outerRadius = v;
            return this;
        }

        /**
         * Sets {@link #prominence}.
         *
         * @param v noise tolerance for {@link MaximumFinder}; clamped to {@code ≥ 0}
         * @return this config (for chaining)
         */
        public Config prominence(final double v) {
            this.prominence = Math.max(0, v);
            return this;
        }

        /**
         * Sets {@link #mergingDistance}.
         *
         * @param v merging distance in physical units; set to {@code -1} for auto
         * @return this config (for chaining)
         */
        public Config mergingDistance(final double v) {
            this.mergingDistance = v;
            return this;
        }

        /**
         * Sets {@link #maxSectionSize}.
         *
         * @param v max grid dimension in pixels; clamped to [5, 200]
         * @return this config (for chaining)
         */
        public Config maxSectionSize(final int v) {
            this.maxSectionSize = Math.clamp(v, 5, 200);
            return this;
        }

        /**
         * Sets {@link #assignToNearestPath}.
         *
         * @param b whether to reassign detections to nearest path
         * @return this config (for chaining)
         */
        public Config assignToNearestPath(final boolean b) {
            this.assignToNearestPath = b;
            return this;
        }

        /**
         * Resolves the effective inner radius for a node with the given base
         * (fitted) radius.
         */
        double getEffectiveInnerRadius(final double nodeRadius) {
            if (innerRadius > 0) return innerRadius;
            return nodeRadius * innerRadiusMultiplier;
        }

        /**
         * Resolves the outer radius for a node with the given base radius.
         */
        double getEffectiveOuterRadius(final double nodeRadius) {
            if (outerRadius > 0) return outerRadius;
            return nodeRadius * outerRadiusMultiplier;
        }

        /**
         * Resolves the merging distance, falling back to mean outer radius.
         */
        double getEffectiveMergingDistance(final Collection<Path> paths) {
            if (mergingDistance > 0) return mergingDistance;
            // Fall back to mean outer radius across all paths
            double sumR = 0;
            int count = 0;
            for (final Path p : paths) {
                if (p == null) continue;
                final double meanR = p.hasRadii() ? p.getMeanRadius() : p.getMinimumSeparation() * 2;
                sumR += getEffectiveOuterRadius(meanR);
                count++;
            }
            return count > 0 ? sumR / count : 1.0;
        }

        @Override
        public String toString() {
            return "Config{" +
                    (innerRadius > 0 ? "innerRadius=" + innerRadius
                            : "innerRadiusMultiplier=" + innerRadiusMultiplier) +
                    ", " +
                    (outerRadius > 0 ? "outerRadius=" + outerRadius
                            : "outerRadiusMultiplier=" + outerRadiusMultiplier) +
                    ", prominence=" + prominence +
                    ", mergingDistance=" + mergingDistance +
                    ", maxSectionSize=" + maxSectionSize +
                    ", assignToNearestPath=" + assignToNearestPath + "}";
        }
    }

    /**
     * One detected maximum (varicosity, spine, punctum, etc.).
     */
    public static final class Detection {

        /**
         * X-coordinate of the detection in real-world units
         */
        public final double x;
        /**
         * Y-coordinate of the detection in real-world units
         */
        public final double y;
        /**
         * Z-coordinate of the detection in real-world units
         */
        public final double z;
        /**
         * Intensity at the maximum in the detection image
         */
        public final double intensity;
        /**
         * The path this detection is associated with
         */
        public final Path path;
        /**
         * Index of the nearest node on the associated path
         */
        public final int nodeIndex;
        /**
         * Distance from the path skeleton at the detection point (physical units)
         */
        public final double distanceFromSkeleton;

        Detection(final double x, final double y, final double z,
                   final double intensity, final Path path, final int nodeIndex,
                   final double distanceFromSkeleton) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.intensity = intensity;
            this.path = path;
            this.nodeIndex = nodeIndex;
            this.distanceFromSkeleton = distanceFromSkeleton;
        }

        /**
         * Returns this detection as an {@link SNTPoint}.
         *
         * @return a new {@link PointInImage} at this detection's coordinates
         */
        public PointInImage toSNTPoint() {
            final PointInImage pim = new PointInImage(x, y, z);
            pim.onPath = path;
            return pim;
        }

        /**
         * Returns the XYZCT coordinates for bookmark/ROI integration.
         * X, Y, Z are in pixel (uncalibrated) coordinates; C and T are
         * 1-based indices from the associated path.
         *
         * @return {@code double[5]}: {xPixel, yPixel, zPixel, channel, frame}
         */
        public double[] xyzct() {
            final ij.measure.Calibration cal = path.getCalibration();
            return new double[]{
                    cal.getRawX(x),
                    cal.getRawY(y),
                    cal.getRawZ(z),
                    path.getChannel(),
                    path.getFrame()
            };
        }

        @Override
        public String toString() {
            return String.format(
                    "Detection[x=%.3f,y=%.3f,z=%.3f; I=%.1f; dist=%.3f; path=%s; node=%d]",
                    x, y, z, intensity, distanceFromSkeleton,
                    path != null ? path.getName() : "null", nodeIndex);
        }
    }
}
