/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import net.imagej.ImgPlus;
import net.imagej.ops.OpService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.labeling.ConnectedComponents;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegion;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;

import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Utilities for automatic soma detection and ROI generation.
 * <p>
 * Provides methods to detect the soma (cell body) in fluorescent images using
 * a combined EDT-intensity approach, flood fill the region, extract contours,
 * and convert to ImageJ ROIs.
 * </p>
 * <p>
 * Three detection modes are available:
 * <ul>
 *   <li>{@link #detectSoma} - Detect single soma (brightest/thickest region)</li>
 *   <li>{@link #detectSomaAt} - Detect soma at a specific seed point</li>
 *   <li>{@link #detectAllSomas} - Detect all somas in the image</li>
 * </ul>
 * </p>
 *
 * @author Tiago Ferreira
 * @see AbstractAutoTracer#findRoot(RandomAccessibleInterval, double, double[])
 */
public class SomaUtils {

    /**
     * Output type: Point ROI at soma center
     */
    public static final String OUTPUT_POINT = "Point";
    /**
     * Output type: Area ROI from flood fill contour
     */
    public static final String OUTPUT_CONTOUR = "Contour";
    /**
     * Output type: Circular ROI with estimated radius
     */
    public static final String OUTPUT_CIRCLE = "Circle";

    /** Default minimum soma radius in voxels for filtering */
    private static final double DEFAULT_MIN_RADIUS = 3.0;

    private SomaUtils() {
        // Static utility class
    }

    // ==================== Single Soma Detection ====================

    /**
     * Full soma detection using ImgPlus with spacing extracted automatically.
     * <p>
     * Convenience method that extracts spacing from the ImgPlus calibration.
     * </p>
     *
     * @param source    input image (2D or 3D ImgPlus)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param zSlice    Z-slice to use (0-indexed), or -1 for middle slice
     * @return SomaResult containing center, mask, contour, and radius; or null if detection fails
     * @see #detectSoma(RandomAccessibleInterval, double, double[])
     */
    public static SomaResult detectSoma(final ImgPlus<?> source, final double threshold, final int zSlice) {
        if (source == null) {
            return null;
        }

        final double[] spacing = ImgUtils.getSpacing(source);
        final String units = ImgUtils.getSpacingUnits(source);

        final RandomAccessibleInterval<?> rai;
        final int effectiveZ;
        if (source.numDimensions() > 2) {
            // Use specified slice, or fall back to middle
            effectiveZ = (zSlice >= 0 && zSlice < source.dimension(2))
                    ? zSlice
                    : (int) (source.dimension(2) / 2);
            rai = Views.hyperSlice(source, 2, effectiveZ);
        } else {
            effectiveZ = -1;
            rai = source;
        }

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<? extends RealType<?>> typedRai =
                (RandomAccessibleInterval<? extends RealType<?>>) rai;

        // Call base method
        final SomaResult baseResult = detectSoma(typedRai, threshold, spacing);
        if (baseResult == null) return null;

        // Return enriched result with units and Z
        return new SomaResult(
                baseResult.center, baseResult.centroid, baseResult.mask, baseResult.contour,
                baseResult.radius, baseResult.threshold, effectiveZ, units
        );
    }

    /**
     * Full soma detection using ImgPlus with automatic thresholding.
     * <p>
     * Convenience method using Otsu's threshold and spacing from ImgPlus calibration.
     * </p>
     *
     * @param source input image (2D or 3D ImgPlus)
     * @return SomaResult containing center, mask, contour, and radius; or null if detection fails
     * @see #detectSoma(ImgPlus, double, int)
     */
    public static SomaResult detectSoma(final ImgPlus<?> source) {
        return detectSoma(source, -1d, -1); // -1 = middle slice
    }

    /**
     * Full soma detection: finds center, floods region, extracts contour.
     *
     * @param source    input image (2D)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param spacing   voxel spacing [x, y], or null for unit spacing
     * @return SomaResult containing center, mask, contour, and radius; or null if detection fails
     */
    public static SomaResult detectSoma(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {

        if (source == null || source.numDimensions() < 2) {
            return null;
        }

        // Effective spacing
        final double[] effectiveSpacing = (spacing != null && spacing.length >= 2)
                ? spacing : new double[]{1.0, 1.0};

        // Find soma center using EDT × intensity scoring
        final long[] center = AbstractAutoTracer.findRoot(source, threshold, effectiveSpacing);
        if (center == null) return null;

        // Determine effective threshold
        final double effectiveThreshold = resolveThreshold(source, threshold);

        return buildSomaResult(source, center[0], center[1], effectiveThreshold);
    }

    // ==================== Seed-based Detection ====================

    /**
     * Detects soma at a specific seed point.
     * <p>
     * Useful for interactive detection where user clicks near a soma.
     * </p>
     *
     * @param source    input image (2D)
     * @param seedX     X coordinate of seed point (voxels)
     * @param seedY     Y coordinate of seed point (voxels)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param spacing   voxel spacing [x, y], or null for unit spacing
     * @return SomaResult, or null if seed is in background or detection fails
     */
    public static SomaResult detectSomaAt(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final long seedX,
            final long seedY,
            final double threshold,
            final double[] spacing) {

        if (source == null || source.numDimensions() < 2) {
            return null;
        }

        // Bounds check
        if (seedX < 0 || seedX >= source.dimension(0) ||
                seedY < 0 || seedY >= source.dimension(1)) {
            return null;
        }

        final double effectiveThreshold = resolveThreshold(source, threshold);

        // Check seed is in foreground
        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();
        ra.setPosition(new long[]{seedX, seedY});
        if (ra.get().getRealDouble() <= effectiveThreshold) {
            return null;
        }

        return buildSomaResult(source, seedX, seedY, effectiveThreshold);
    }

    /**
     * Detects soma at a specific seed point using ImgPlus.
     *
     * @param source    input image (2D or 3D ImgPlus)
     * @param seedX     X coordinate of seed point (voxels)
     * @param seedY     Y coordinate of seed point (voxels)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param zSlice    Z-slice to use (0-indexed), or -1 for middle slice
     * @return SomaResult, or null if seed is in background or detection fails
     */
    public static SomaResult detectSomaAt(
            final ImgPlus<?> source,
            final long seedX,
            final long seedY,
            final double threshold,
            final int zSlice) {

        if (source == null) {
            return null;
        }

        final double[] spacing = ImgUtils.getSpacing(source);
        final String units = ImgUtils.getSpacingUnits(source);

        final RandomAccessibleInterval<?> rai;
        final int effectiveZ;
        if (source.numDimensions() > 2) {
            effectiveZ = (zSlice >= 0 && zSlice < source.dimension(2))
                    ? zSlice
                    : (int) (source.dimension(2) / 2);
            rai = Views.hyperSlice(source, 2, effectiveZ);
        } else {
            effectiveZ = -1;
            rai = source;
        }

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<? extends RealType<?>> typedRai =
                (RandomAccessibleInterval<? extends RealType<?>>) rai;

        final SomaResult baseResult = detectSomaAt(typedRai, seedX, seedY, threshold, spacing);
        if (baseResult == null) return null;

        return new SomaResult(
                baseResult.center, baseResult.centroid, baseResult.mask, baseResult.contour,
                baseResult.radius, baseResult.threshold, effectiveZ, units
        );
    }

    /**
     * Detects all somas using EDT local maxima approach.
     * Finds local maxima in the distance transform - these correspond to
     * centers of thick structures (somas), not thin structures (neurites).
     *
     * @param source    input image (2D)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param spacing   voxel spacing [x, y], or null for unit spacing
     * @param minRadius minimum soma radius in voxels to include (filters small debris)
     * @return list of SomaResult, sorted by radius (largest first); empty list if none found
     */
    public static List<SomaResult> detectAllSomas(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing,
            final double minRadius) {

        final List<SomaResult> results = new ArrayList<>();

        if (source == null || source.numDimensions() < 2) {
            return results;
        }

        final double effectiveThreshold = resolveThreshold(source, threshold);
        final long width = source.dimension(0);
        final long height = source.dimension(1);

        // 1. Create binary mask from threshold
        final Img<BitType> foreground = ArrayImgs.bits(width, height);
        final Cursor<? extends RealType<?>> srcCursor = Views.flatIterable(source).cursor();
        final Cursor<BitType> maskCursor = foreground.cursor();
        while (srcCursor.hasNext()) {
            srcCursor.fwd();
            maskCursor.fwd();
            maskCursor.get().set(srcCursor.get().getRealDouble() > effectiveThreshold);
        }

        // 2. Compute EDT (invert mask: TRUE = background for EDT)
        final Img<BitType> inverted = ArrayImgs.bits(width, height);
        final Cursor<BitType> fc = foreground.cursor();
        final Cursor<BitType> ic = inverted.cursor();
        while (fc.hasNext()) {
            fc.fwd();
            ic.fwd();
            ic.get().set(!fc.get().get());
        }

        final Img<FloatType> edt = ArrayImgs.floats(width, height);
        DistanceTransform.binaryTransform(inverted, edt, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

        // 3. Find local maxima in EDT with minimum value filter
        final List<long[]> somaCenters = findEDTLocalMaxima(edt, minRadius);

        // 4. Build SomaResult for each local maximum
        for (final long[] center : somaCenters) {
            final SomaResult result = buildSomaResult(source, center[0], center[1], effectiveThreshold);
            if (result != null && result.radius >= minRadius) {
                results.add(result);
            }
        }

        // Sort by radius (largest first)
        results.sort(Comparator.comparingDouble(SomaResult::radius).reversed());

        return results;
    }

    /**
     * Finds local maxima in EDT that exceed minimum radius.
     * A local maximum indicates a point maximally distant from background - i.e., center of a thick structure.
     */
    private static List<long[]> findEDTLocalMaxima(final Img<FloatType> edt, final double minRadius) {
        final List<long[]> maxima = new ArrayList<>();
        final long width = edt.dimension(0);
        final long height = edt.dimension(1);
        final RandomAccess<FloatType> ra = edt.randomAccess();

        // 8-connected neighborhood offsets
        final int[][] neighbors = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};

        for (long y = 1; y < height - 1; y++) {
            for (long x = 1; x < width - 1; x++) {
                ra.setPosition(new long[]{x, y});
                final double val = ra.get().getRealDouble();

                // Must exceed minimum radius (EDT value = distance to edge ≈ radius)
                if (val < minRadius) continue;

                // Check if local maximum
                boolean isMax = true;
                for (final int[] offset : neighbors) {
                    ra.setPosition(new long[]{x + offset[0], y + offset[1]});
                    if (ra.get().getRealDouble() >= val) {
                        isMax = false;
                        break;
                    }
                }

                if (isMax) {
                    maxima.add(new long[]{x, y});
                }
            }
        }

        return maxima;
    }

    /**
     * Detects all somas using default minimum radius.
     *
     * @param source    input image (2D)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param spacing   voxel spacing [x, y], or null for unit spacing
     * @return list of SomaResult, sorted by radius (largest first)
     */
    public static List<SomaResult> detectAllSomas(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {
        return detectAllSomas(source, threshold, spacing, DEFAULT_MIN_RADIUS);
    }

    /**
     * Detects all somas using ImgPlus with automatic spacing.
     *
     * @param source    input image (2D or 3D ImgPlus)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param zSlice    Z-slice to use (0-indexed), or -1 for middle slice
     * @param minRadius minimum soma radius in voxels
     * @return list of SomaResult, sorted by radius (largest first)
     */
    public static List<SomaResult> detectAllSomas(
            final ImgPlus<?> source,
            final double threshold,
            final int zSlice,
            final double minRadius) {

        if (source == null) {
            return new ArrayList<>();
        }

        final double[] spacing = ImgUtils.getSpacing(source);
        final String units = ImgUtils.getSpacingUnits(source);

        final RandomAccessibleInterval<?> rai;
        final int effectiveZ;
        if (source.numDimensions() > 2) {
            effectiveZ = (zSlice >= 0 && zSlice < source.dimension(2))
                    ? zSlice
                    : (int) (source.dimension(2) / 2);
            rai = Views.hyperSlice(source, 2, effectiveZ);
        } else {
            effectiveZ = -1;
            rai = source;
        }

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<? extends RealType<?>> typedRai =
                (RandomAccessibleInterval<? extends RealType<?>>) rai;

        final List<SomaResult> baseResults = detectAllSomas(typedRai, threshold, spacing, minRadius);

        // Enrich with units and Z
        final List<SomaResult> enrichedResults = new ArrayList<>();
        for (final SomaResult base : baseResults) {
            enrichedResults.add(new SomaResult(
                    base.center, base.centroid, base.mask, base.contour,
                    base.radius, base.threshold, effectiveZ, units
            ));
        }

        return enrichedResults;
    }

    /**
     * Detects all somas using ImgPlus with default settings.
     *
     * @param source input image (2D or 3D ImgPlus)
     * @return list of SomaResult, sorted by radius (largest first)
     */
    public static List<SomaResult> detectAllSomas(final ImgPlus<?> source) {
        return detectAllSomas(source, -1d, -1, DEFAULT_MIN_RADIUS);
    }

    /**
     * Detects soma within a specific labeled region.
     */
    private static SomaResult detectSomaInRegion(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final LabelRegion<?> region,
            final double threshold,
            final double[] spacing) {

        // Find the center of this region using EDT × intensity scoring
        // We iterate only within the region's bounding box for efficiency

        final long[] min = Intervals.minAsLongArray(region);
        final long[] max = Intervals.maxAsLongArray(region);

        // Create a mask for this region
        final long width = max[0] - min[0] + 1;
        final long height = max[1] - min[1] + 1;
        final Img<BitType> regionMask = ArrayImgs.bits(width, height);

        final Cursor<BoolType> regionCursor = region.cursor();
        final RandomAccess<BitType> maskRA = regionMask.randomAccess();

        while (regionCursor.hasNext()) {
            regionCursor.fwd();
            final long x = regionCursor.getLongPosition(0) - min[0];
            final long y = regionCursor.getLongPosition(1) - min[1];
            maskRA.setPosition(new long[]{x, y});
            maskRA.get().set(true);
        }

        // Compute EDT within region
        final Img<BitType> inverted = ArrayImgs.bits(width, height);
        final Cursor<BitType> mc = regionMask.cursor();
        final Cursor<BitType> ic = inverted.cursor();
        while (mc.hasNext()) {
            mc.fwd();
            ic.fwd();
            ic.get().set(!mc.get().get());
        }

        final Img<FloatType> edt = ArrayImgs.floats(width, height);
        DistanceTransform.binaryTransform(inverted, edt, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

        // Find max EDT × intensity within region
        final RandomAccess<? extends RealType<?>> srcRA = source.randomAccess();
        final Cursor<FloatType> edtCursor = edt.localizingCursor();

        final double[] stats = ImgUtils.computeIntensityStats(source);
        final double minIntensity = stats[0];
        final double maxIntensity = stats[1];
        final double intensityRange = maxIntensity - minIntensity;

        double maxScore = Double.NEGATIVE_INFINITY;
        long bestX = min[0], bestY = min[1];

        while (edtCursor.hasNext()) {
            edtCursor.fwd();
            final double edtVal = edtCursor.get().getRealDouble();
            if (edtVal <= 0) continue;

            final long localX = edtCursor.getLongPosition(0);
            final long localY = edtCursor.getLongPosition(1);
            final long globalX = localX + min[0];
            final long globalY = localY + min[1];

            srcRA.setPosition(new long[]{globalX, globalY});
            final double intensity = srcRA.get().getRealDouble();

            if (intensity <= threshold) continue;

            final double normIntensity = (intensityRange > 0)
                    ? (intensity - minIntensity) / intensityRange
                    : 1.0;
            final double score = edtVal * normIntensity;

            if (score > maxScore) {
                maxScore = score;
                bestX = globalX;
                bestY = globalY;
            }
        }

        if (maxScore <= 0) {
            return null;
        }

        return buildSomaResult(source, bestX, bestY, threshold);
    }

    /**
     * Builds a SomaResult from a seed point by flood filling and extracting contour.
     */
    private static SomaResult buildSomaResult(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final long seedX,
            final long seedY,
            final double threshold) {

        // Flood fill from center
        final Img<BitType> mask = floodFillSoma(source, seedX, seedY, threshold);
        if (mask == null) return null;

        // Prune narrow protrusions (neurites)
        pruneNarrowRegions(mask, 0.05);

        // Extract contour from pruned mask
        final Polygon contour = extractContour(mask);

        // Compute radius in voxels
        final double areaVoxels = computeMaskArea(mask, null);
        final double radiusVoxels = Math.sqrt(areaVoxels / Math.PI);

        // Compute centroid in voxel coords
        final double[] centroid = computeMaskCentroid(mask, null);

        return new SomaResult(
                new long[]{seedX, seedY}, centroid, mask, contour,
                radiusVoxels, threshold, -1, null
        );
    }

    /**
     * Resolves threshold value, computing Otsu or mean if needed.
     */
    private static double resolveThreshold(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold) {

        if (threshold < 0) {
            return computeOtsuThreshold(source);
        } else if (Double.isNaN(threshold)) {
            return ImgUtils.computeIntensityStats(source)[2]; // mean
        }
        return threshold;
    }

    // ==================== Flood Fill ====================

    /**
     * Creates a binary mask of the soma region using flood fill from seed point.
     *
     * @param source    the input image (2D)
     * @param seedX     seed X coordinate (voxels)
     * @param seedY     seed Y coordinate (voxels)
     * @param threshold pixels above this value are considered foreground
     * @return binary mask where TRUE = soma region, or null if seed is in background
     */
    public static Img<BitType> floodFillSoma(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final long seedX,
            final long seedY,
            final double threshold) {

        final long width = source.dimension(0);
        final long height = source.dimension(1);

        // Check seed is in foreground
        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();
        ra.setPosition(new long[]{seedX, seedY});
        if (ra.get().getRealDouble() <= threshold) {
            return null;
        }

        // Create output mask and perform flood fill
        final Img<BitType> mask = ArrayImgs.bits(width, height);
        floodFillThreshold(source, mask, seedX, seedY, threshold);

        return mask;
    }

    /**
     * Stack-based flood fill implementation (avoids recursion limits).
     */
    private static void floodFillThreshold(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final Img<BitType> output,
            final long seedX,
            final long seedY,
            final double threshold) {

        final long width = source.dimension(0);
        final long height = source.dimension(1);

        final RandomAccess<? extends RealType<?>> srcRA = source.randomAccess();
        final RandomAccess<BitType> outRA = output.randomAccess();

        final List<long[]> stack = new ArrayList<>();
        stack.add(new long[]{seedX, seedY});

        while (!stack.isEmpty()) {
            final long[] pos = stack.remove(stack.size() - 1);
            final long x = pos[0];
            final long y = pos[1];

            // Bounds check
            if (x < 0 || x >= width || y < 0 || y >= height) continue;

            // Already visited?
            outRA.setPosition(pos);
            if (outRA.get().get()) continue;

            // Check threshold
            srcRA.setPosition(pos);
            if (srcRA.get().getRealDouble() <= threshold) continue;

            // Mark as filled
            outRA.get().set(true);

            // Add 4-connected neighbors
            stack.add(new long[]{x + 1, y});
            stack.add(new long[]{x - 1, y});
            stack.add(new long[]{x, y + 1});
            stack.add(new long[]{x, y - 1});
        }
    }

    // ==================== Contour Extraction ====================

    /**
     * Extracts the boundary contour from a binary mask using Moore boundary tracing.
     *
     * @param mask binary mask (TRUE = foreground)
     * @return polygon contour, or null if mask is empty
     */
    public static Polygon extractContour(final Img<BitType> mask) {
        final long width = mask.dimension(0);
        final long height = mask.dimension(1);
        final RandomAccess<BitType> ra = mask.randomAccess();

        // Find starting point (first foreground pixel on boundary)
        long startX = -1, startY = -1;
        outer:
        for (long y = 0; y < height; y++) {
            for (long x = 0; x < width; x++) {
                ra.setPosition(new long[]{x, y});
                if (ra.get().get() && isBoundaryPixel(mask, x, y)) {
                    startX = x;
                    startY = y;
                    break outer;
                }
            }
        }

        if (startX < 0) return null;

        // Moore boundary tracing (8-connected)
        final List<int[]> contourPoints = new ArrayList<>();
        final int[][] directions = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

        long x = startX, y = startY;
        int dir = 0;

        do {
            contourPoints.add(new int[]{(int) x, (int) y});

            // Find next boundary pixel
            boolean found = false;
            final int startDir = (dir + 5) % 8; // Backtrack + 1

            for (int i = 0; i < 8; i++) {
                final int checkDir = (startDir + i) % 8;
                final long nx = x + directions[checkDir][0];
                final long ny = y + directions[checkDir][1];

                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    ra.setPosition(new long[]{nx, ny});
                    if (ra.get().get()) {
                        x = nx;
                        y = ny;
                        dir = checkDir;
                        found = true;
                        break;
                    }
                }
            }

            if (!found) break;

        } while ((x != startX || y != startY) && contourPoints.size() < width * height);

        if (contourPoints.size() < 3) return null;

        final int[] xPoints = new int[contourPoints.size()];
        final int[] yPoints = new int[contourPoints.size()];
        for (int i = 0; i < contourPoints.size(); i++) {
            xPoints[i] = contourPoints.get(i)[0];
            yPoints[i] = contourPoints.get(i)[1];
        }

        return new Polygon(xPoints, yPoints, contourPoints.size());
    }

    /**
     * Checks if a foreground pixel is on the boundary.
     */
    private static boolean isBoundaryPixel(final Img<BitType> mask, final long x, final long y) {
        final long width = mask.dimension(0);
        final long height = mask.dimension(1);
        final RandomAccess<BitType> ra = mask.randomAccess();

        final long[][] neighbors = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};

        for (final long[] n : neighbors) {
            if (n[0] < 0 || n[0] >= width || n[1] < 0 || n[1] >= height) {
                return true;
            }
            ra.setPosition(n);
            if (!ra.get().get()) {
                return true;
            }
        }
        return false;
    }

    // ==================== Mask Measurements ====================

    /**
     * Computes the centroid of a binary mask.
     *
     * @param mask    binary mask
     * @param spacing voxel spacing [x, y] for physical coordinates, or null for voxel coords
     * @return centroid as [x, y], or null if mask is empty
     */
    public static double[] computeMaskCentroid(final Img<BitType> mask, final double[] spacing) {
        long sumX = 0, sumY = 0, count = 0;

        final Cursor<BitType> cursor = mask.localizingCursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            if (cursor.get().get()) {
                sumX += cursor.getLongPosition(0);
                sumY += cursor.getLongPosition(1);
                count++;
            }
        }

        if (count == 0) return null;

        final double cx = (double) sumX / count;
        final double cy = (double) sumY / count;

        if (spacing != null && spacing.length >= 2) {
            return new double[]{cx * spacing[0], cy * spacing[1]};
        }
        return new double[]{cx, cy};
    }

    /**
     * Computes the area of a binary mask.
     *
     * @param mask    binary mask
     * @param spacing voxel spacing [x, y], or null for voxel count
     * @return area in physical units squared, or voxel count if spacing is null
     */
    public static double computeMaskArea(final Img<BitType> mask, final double[] spacing) {
        long count = 0;
        final Cursor<BitType> cursor = mask.cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            if (cursor.get().get()) count++;
        }

        if (spacing != null && spacing.length >= 2) {
            return count * spacing[0] * spacing[1];
        }
        return count;
    }

    // ==================== Pruning ====================

    /**
     * Prunes narrow protrusions from mask using EDT.
     * Removes pixels where local thickness is below a fraction of maximum thickness.
     */
    private static void pruneNarrowRegions(final Img<BitType> mask, final double minWidthFraction) {

        final Img<FloatType> edt = ArrayImgs.floats(Intervals.dimensionsAsLongArray(mask));

        // Invert mask for EDT (TRUE = background)
        final Img<BitType> inverted = ArrayImgs.bits(Intervals.dimensionsAsLongArray(mask));
        final Cursor<BitType> mc = mask.cursor();
        final Cursor<BitType> ic = inverted.cursor();
        while (mc.hasNext()) {
            mc.fwd();
            ic.fwd();
            ic.get().set(!mc.get().get());
        }

        DistanceTransform.binaryTransform(inverted, edt, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

        // Find max EDT
        double maxEdt = 0;
        for (FloatType t : edt) {
            if (t.getRealDouble() > maxEdt) maxEdt = t.getRealDouble();
        }

        // Prune pixels with EDT < threshold
        final double edtThreshold = maxEdt * minWidthFraction;
        final Cursor<BitType> maskCursor = mask.cursor();
        final Cursor<FloatType> edtCursor = edt.cursor();
        while (maskCursor.hasNext()) {
            maskCursor.fwd();
            edtCursor.fwd();
            if (edtCursor.get().getRealDouble() < edtThreshold) {
                maskCursor.get().set(false);
            }
        }
    }

    // ==================== Threshold ====================

    /**
     * Computes Otsu's threshold using ImageJ Ops.
     *
     * @param source the input image
     * @return Otsu threshold value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static double computeOtsuThreshold(final RandomAccessibleInterval<? extends RealType<?>> source) {
        final net.imagej.ops.OpService ops = SNTUtils.getContext().getService(net.imagej.ops.OpService.class);
        final net.imglib2.histogram.Histogram1d histogram = ops.image().histogram((Iterable) source);
        final RealType<?> thresholdObj = ops.threshold().otsu(histogram);
        return thresholdObj.getRealDouble();
    }

    // ==================== Result Container ====================

    /**
     * Container for soma detection results.
     *
     * @param center       Soma center in voxel coordinates (from findRoot or seed)
     * @param centroid     Mask centroid in voxel coordinates (may differ from center)
     * @param mask         Binary mask of soma region
     * @param contour      Boundary contour polygon
     * @param radius       Estimated soma radius (equivalent radius from area) in voxels
     * @param threshold    Threshold used for detection
     * @param zSlice       Z-slice where soma was detected (0-indexed), or -1 if 2D
     * @param spacingUnits Spacing units (e.g., "µm", "pixels"), or null if unset
     */
    public record SomaResult(long[] center, double[] centroid, Img<BitType> mask, Polygon contour, double radius,
                             double threshold, int zSlice, String spacingUnits) {

        /**
         * Checks if a valid contour was extracted.
         *
         * @return true if contour has at least 3 points
         */
        public boolean hasContour() {
            return contour != null && contour.npoints >= 3;
        }

        /**
         * Creates an ImageJ ROI of the specified type.
         *
         * @param outputType one of {@link #OUTPUT_POINT}, {@link #OUTPUT_CONTOUR}, {@link #OUTPUT_CIRCLE}
         * @return the ROI, never null (falls back to point if requested type unavailable)
         */
        public Roi toRoi(final String outputType) {
            return toRoiInternal(outputType, -1);
        }

        /**
         * Creates an ImageJ ROI using the stored Z-slice position.
         *
         * @param outputType one of {@link #OUTPUT_POINT}, {@link #OUTPUT_CONTOUR}, {@link #OUTPUT_CIRCLE}
         * @return the ROI with Z position set (1-indexed), or no position if zSlice is -1
         */
        public Roi toRoiAtDetectedZ(final String outputType) {
            return toRoiInternal(outputType, zSlice >= 0 ? zSlice + 1 : -1);
        }

        private Roi toRoiInternal(final String outputType, final int zPos) {
            final Roi roi;
            switch (outputType) {
                case OUTPUT_CONTOUR:
                    if (hasContour()) {
                        roi = new PolygonRoi(contour, Roi.POLYGON);
                    } else {
                        roi = createCircleRoi();
                    }
                    break;
                case OUTPUT_CIRCLE:
                    roi = createCircleRoi();
                    break;
                case OUTPUT_POINT:
                default:
                    roi = createPointRoi();
                    break;
            }
            if (zPos > 0) {
                roi.setPosition(zPos);
            }
            return roi;
        }

        /**
         * Creates a point ROI at the soma center.
         */
        public PointRoi createPointRoi() {
            return new PointRoi(center[0], center[1]);
        }

        /**
         * Creates a circular ROI using the estimated radius.
         */
        public OvalRoi createCircleRoi() {
            final double cx = (centroid != null) ? centroid[0] : center[0];
            final double cy = (centroid != null) ? centroid[1] : center[1];
            return new OvalRoi(cx - radius, cy - radius, radius * 2, radius * 2);
        }

        /**
         * Creates a polygon ROI from the contour.
         *
         * @return polygon ROI, or null if no valid contour
         */
        public PolygonRoi createContourRoi() {
            if (!hasContour()) return null;
            return new PolygonRoi(contour, Roi.POLYGON);
        }

        /** Returns the center X coordinate in voxels. */
        public int getCenterX() {
            return (int) center[0];
        }

        /** Returns the center Y coordinate in voxels. */
        public int getCenterY() {
            return (int) center[1];
        }

        /** Returns the center Z coordinate in voxels, or 0 if 2D. */
        public int getCenterZ() {
            return (center.length > 2) ? (int) center[2] : 0;
        }

        /** Returns the estimated soma diameter in voxels. */
        public double getDiameter() {
            return radius * 2;
        }

        /** Returns the soma area in voxels squared. */
        public double getArea() {
            return Math.PI * radius * radius;
        }

        /**
         * Creates a single-node Path representing the soma.
         *
         * @param spacing voxel spacing [x, y, z] for physical coordinates, or null for voxels
         * @return a single-node soma path
         */
        public Path toPath(final double[] spacing) {
            final double sx = (spacing != null && spacing.length > 0) ? spacing[0] : 1.0;
            final double sy = (spacing != null && spacing.length > 1) ? spacing[1] : 1.0;
            final double sz = (spacing != null && spacing.length > 2) ? spacing[2] : 1.0;
            final double x = center[0] * sx;
            final double y = center[1] * sy;
            final double z = (zSlice >= 0) ? zSlice * sz : ((center.length > 2) ? center[2] * sz : 0);
            final Path path = new Path(sx, sy, sz, getSpacingUnitsOrDefault());
            path.addNode(new PointInImage(x, y, z));
            path.setRadius(radius * (sx + sy) / 2);
            path.setSWCType(Path.SWC_SOMA);
            path.setName("Soma");
            return path;
        }

        /** Returns true if spacing units are defined. */
        public boolean hasSpacingUnits() {
            return spacingUnits != null && !spacingUnits.isEmpty();
        }

        private String getSpacingUnitsOrDefault() {
            return hasSpacingUnits() ? spacingUnits : SNTUtils.getSanitizedUnit("");
        }

        @Override
        public String toString() {
            return String.format("SomaResult[center=(%d,%d), radius=%.1f, threshold=%.1f, hasContour=%b]",
                    center[0], center[1], radius, threshold, hasContour());
        }
    }
}
