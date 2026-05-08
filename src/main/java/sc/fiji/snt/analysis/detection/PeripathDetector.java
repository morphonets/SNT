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

import ij.ImagePlus;
import ij.plugin.filter.MaximumFinder;
import ij.process.FloatProcessor;
import net.imglib2.img.display.imagej.ImageJFunctions;
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

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Detects intensity maxima (varicosities, spines, synaptic puncta) in annular
 * cross-sections around traced paths. At each path node, a perpendicular
 * cross-section is sampled from the image (see {@link sc.fiji.snt.PathFitter}),
 * masked to an annulus defined by the node's radius (inner) and a configurable
 * outer radius, and then analyzed for local maxima with prominence filtering
 * via {@link MaximumFinder}. Detections from adjacent cross-sections are
 * deduplicated via greedy non-maximum suppression.
 *
 * <p>Usage:
 * <pre>
 *   PeripathDetector.Config cfg = new PeripathDetector.Config()
 *       .innerRadiusMultiplier(0.5)  // start at 0.5× node radius (closer to skeleton)
 *       .outerRadiusMultiplier(2.0)  // search up to 2× node radius
 *       .prominence(50)              // MaximumFinder noise tolerance
 *       .mergingDistance(1.5);        // NMS radius in calibrated units
 *   List&lt;Detection&gt; hits = PeripathDetector.detect(paths, image, cfg);
 * </pre>
 *
 * @author Tiago Ferreira
 * @see AlongPathDetector
 * @see Detection
 * @see DetectorUtils
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
    @SuppressWarnings("unchecked")
    public static <T extends RealType<T>> List<Detection> detect(
            final Collection<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> img,
            final Config cfg) {

        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("No paths provided");
        if (img == null)
            throw new IllegalArgumentException("No image provided");

        final boolean is2D = img.numDimensions() == 2 ||
                (img.numDimensions() >= 3 && img.dimension(2) <= 1);

        RandomAccessibleInterval<FloatType> floatImage = Converters.convert(
                (RandomAccessibleInterval<T>) img, new RealFloatConverter<>(), new FloatType());
        if (is2D && floatImage.numDimensions() > 2) {
            floatImage = Views.hyperSlice(floatImage, 2, 0);
        }
        final RealRandomAccessible<FloatType> interpolant = Views.interpolate(
                Views.extendZero(floatImage), new NLinearInterpolatorFactory<>());

        final List<Detection> rawDetections = new ArrayList<>();

        for (final Path path : paths) {
            if (path == null || path.size() < 2) continue;

            final double[] radii = DetectorUtils.prepareRadii(path);

            if (!path.hasTangents()) {
                path.setGuessedTangents(2);
            }

            final double xSp = path.getCalibration().pixelWidth;
            final double ySp = path.getCalibration().pixelHeight;
            final double zSp = path.getCalibration().pixelDepth;
            final double[] tangent = new double[3];
            final int pointsEitherSide = 4;

            final RealRandomAccess<FloatType> realAccess = interpolant.realRandomAccess();

            SNTUtils.log("PeripathDetector: processing " + path.getName() +
                    " (" + path.size() + " nodes)");

            for (int i = 0; i < path.size(); i++) {

                final Path.PathNode node = path.getNode(i);
                path.getTangent(i, pointsEitherSide, tangent);

                final double[][] basis = CrossSectionUtils.computeTangentPlaneBasis(
                        tangent[0], tangent[1], tangent[2]);
                final double[] aBasis = basis[0];
                final double[] bBasis = basis[1];

                final double innerR = cfg.getEffectiveInnerRadius(radii[i]);
                final double outerR = cfg.getEffectiveOuterRadius(radii[i]);

                if (is2D) {
                    final double scaleA = CrossSectionUtils.computeScaleAlongVector(
                            aBasis[0], aBasis[1], aBasis[2], xSp, ySp, zSp);

                    final int nSamples = CrossSectionUtils.computeGridSize(outerR, scaleA, cfg.maxSectionSize);

                    final float[] profile = CrossSectionUtils.sampleProfile(
                            nSamples, scaleA,
                            node.x, node.y,
                            aBasis, xSp, ySp,
                            realAccess);

                    final double innerRSamples = innerR / scaleA;
                    final double outerRSamples = outerR / scaleA;
                    CrossSectionUtils.applyAnnularMask1D(profile, innerRSamples, outerRSamples);

                    final int[] maximaIdx = CrossSectionUtils.findMaxima1D(profile, cfg.prominence);

                    if (maximaIdx.length == 0) continue;

                    final double mid = (nSamples - 1) / 2.0;
                    for (final int idx : maximaIdx) {
                        final double g = mid - idx;
                        final double wx = node.x + g * aBasis[0] * scaleA;
                        final double wy = node.y + g * aBasis[1] * scaleA;

                        final float intensity = profile[idx];
                        final double distPhysical = Math.abs(g) * scaleA;

                        rawDetections.add(new Detection(
                                wx, wy, node.z,
                                intensity, path, i, distPhysical));
                    }

                } else {
                    final double scaleIso = CrossSectionUtils.computeIsotropicScale(
                            aBasis, bBasis, xSp, ySp, zSp);
                    final int side = CrossSectionUtils.computeGridSize(outerR, scaleIso, cfg.maxSectionSize);

                    final FloatProcessor fp = CrossSectionUtils.sampleCrossSection(
                            side, scaleIso, scaleIso,
                            node.x, node.y, node.z,
                            aBasis, bBasis,
                            xSp, ySp, zSp,
                            realAccess);

                    final double innerRGrid = innerR / scaleIso;
                    final double outerRGrid = outerR / scaleIso;
                    CrossSectionUtils.applyAnnularMask(fp, innerRGrid, outerRGrid);

                    final MaximumFinder mf = new MaximumFinder();
                    final Polygon maxima = mf.getMaxima(fp, cfg.prominence, true, true);

                    if (maxima == null || maxima.npoints == 0) continue;

                    for (int m = 0; m < maxima.npoints; m++) {
                        final int gx = maxima.xpoints[m];
                        final int gy = maxima.ypoints[m];

                        final double[] worldPos = CrossSectionUtils.backProject(
                                gx, gy, side, scaleIso,
                                node.x, node.y, node.z,
                                aBasis, bBasis);

                        final float intensity = fp.getf(gx + gy * side);

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
        }

        if (rawDetections.isEmpty()) return Collections.emptyList();

        final double effectiveMergingDist = cfg.getEffectiveMergingDistance(paths);
        final List<Detection> deduplicated = DetectorUtils.deduplicate(rawDetections, effectiveMergingDist);

        if (cfg.assignToNearestPath && paths.size() > 1) {
            return DetectorUtils.assignToNearestPaths(deduplicated, paths);
        }

        return deduplicated;
    }

    /**
     * Creates a binary torus mask around the given paths. At each node, the
     * annular search region (as defined by the {@link Config}) is back-projected
     * into the output image, painting voxels inside the torus with the specified
     * fill value.
     *
     * @param <T>       pixel type of the output image
     * @param paths     the paths to generate the torus for
     * @param output    the output image to paint into
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

            final double[] radii = DetectorUtils.prepareRadii(path);

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
     * Creates a binary torus mask around the given paths, writing into an
     * {@link ImagePlus}.
     *
     * @param paths     the paths to generate the torus for
     * @param output    the output image to paint into
     * @param cfg       detection/annulus parameters
     * @param fillValue the value to write into torus voxels
     * @throws IllegalArgumentException if paths or output is null/empty
     * @see #createTorusMask(Collection, RandomAccessibleInterval, Config, double)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void createTorusMask(final Collection<Path> paths,
                                        final ImagePlus output,
                                        final Config cfg,
                                        final double fillValue) {
        if (output == null)
            throw new IllegalArgumentException("No output image provided");
        createTorusMask(paths, (RandomAccessibleInterval) ImageJFunctions.wrapReal(output), cfg, fillValue);
    }

    /**
     * Immutable configuration with builder-like setters.
     */
    public static class Config {

        double innerRadiusMultiplier = 1.0;
        double innerRadius = -1;
        double outerRadiusMultiplier = 2.0;
        double outerRadius = -1;
        double prominence = 10.0;
        double mergingDistance = -1;
        int maxSectionSize = 100;
        boolean assignToNearestPath = true;

        /** @param v multiplier applied to each node's radius; clamped to [0, outerRadiusMultiplier) */
        public Config innerRadiusMultiplier(final double v) {
            this.innerRadiusMultiplier = Math.max(0, v);
            return this;
        }

        /** @param v inner radius in physical units; {@code -1} to use multiplier mode */
        public Config innerRadius(final double v) {
            this.innerRadius = v;
            return this;
        }

        /** @param v multiplier applied to each node's radius; clamped to ≥ 1.01 */
        public Config outerRadiusMultiplier(final double v) {
            this.outerRadiusMultiplier = Math.max(1.01, v);
            return this;
        }

        /** @param v outer radius in physical units; {@code -1} to use multiplier mode */
        public Config outerRadius(final double v) {
            this.outerRadius = v;
            return this;
        }

        /** @param v noise tolerance for {@link MaximumFinder}; clamped to ≥ 0 */
        public Config prominence(final double v) {
            this.prominence = Math.max(0, v);
            return this;
        }

        /** @param v merging distance in physical units; {@code -1} for auto */
        public Config mergingDistance(final double v) {
            this.mergingDistance = v;
            return this;
        }

        /** @param v max grid dimension in pixels; clamped to [5, 200] */
        public Config maxSectionSize(final int v) {
            this.maxSectionSize = Math.clamp(v, 5, 200);
            return this;
        }

        /** @param b whether to reassign detections to nearest path */
        public Config assignToNearestPath(final boolean b) {
            this.assignToNearestPath = b;
            return this;
        }

        double getEffectiveInnerRadius(final double nodeRadius) {
            if (innerRadius > 0) return innerRadius;
            return nodeRadius * innerRadiusMultiplier;
        }

        double getEffectiveOuterRadius(final double nodeRadius) {
            if (outerRadius > 0) return outerRadius;
            return nodeRadius * outerRadiusMultiplier;
        }

        double getEffectiveMergingDistance(final Collection<Path> paths) {
            if (mergingDistance > 0) return mergingDistance;
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
}
