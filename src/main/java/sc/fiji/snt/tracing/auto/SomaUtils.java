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

import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import net.imagej.ImgPlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.roi.labeling.LabelRegion;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.jetbrains.annotations.UnknownNullability;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;

import smile.clustering.HierarchicalClustering;
import smile.clustering.linkage.Linkage;
import smile.clustering.linkage.SingleLinkage;

import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Utilities for automatic soma detection and ROI generation.
 * <p>
 * Provides methods to detect the soma (cell body) in fluorescent images using
 * a combined EDT-intensity approach, flood fill the region, extract contours,
 * and convert to ImageJ ROIs.
 * </p>
 * Three detection modes are available:
 * <ul>
 *   <li>{@link #detectSoma} - Detect single soma (brightest/thickest region)</li>
 *   <li>{@link #detectSomaAt} - Detect soma at a specific seed point</li>
 *   <li>{@link #detectAllSomas} - Detect all somas in the image</li>
 * </ul>
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
     * @return SomaResult, or null if seed is in background or detection fails
     */
    public static SomaResult detectSomaAt(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final long seedX,
            final long seedY,
            final double threshold) {

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

        final SomaResult baseResult = detectSomaAt(typedRai, seedX, seedY, threshold);
        if (baseResult == null) return null;

        return new SomaResult(
                baseResult.center, baseResult.centroid, baseResult.mask, baseResult.contour,
                baseResult.radius, baseResult.threshold, effectiveZ, units
        );
    }

    /**
     * Detects somas at the given seed coordinates. Each seed is passed to
     * {@link #detectSomaAt(RandomAccessibleInterval, long, long, double)}
     * with an auto-computed threshold (Otsu). Seeds that fall in the
     * background or fail detection are silently skipped.
     * <p>
     * This is useful when soma locations are already known (e.g., from manual
     * annotation, ROI centroids, or an external detector) and only the
     * contour/radius characterization is needed. The returned list is directly
     * compatible with {@link AbstractGWDTTracer#traceMultiSoma(List)}.
     * </p>
     * <p>
     * <b>Important:</b> Since seeds are user-provided (i.e., pre-curated),
     * callers should disable automatic soma filtering on the tracer before
     * passing the results to
     * {@link AbstractGWDTTracer#traceMultiSoma(List)}:
     * <pre>{@code
     * List<SomaResult> somas = SomaUtils.detectSomasAt(source, seeds);
     * tracer.setAutoFilter(false);
     * List<Tree> trees = tracer.traceMultiSoma(somas);
     * }</pre>
     * Otherwise, the tracer's heuristic reduction pipeline may discard valid
     * somas. See {@link AbstractGWDTTracer#setAutoFilter(boolean)}.
     * </p>
     *
     * @param source  input image (2D)
     * @param seeds   list of {@code long[]{x, y}} pixel coordinates
     * @return list of successfully detected SomaResults (may be smaller than
     *         {@code seeds} if some seeds are invalid); sorted by radius
     *         (largest first)
     * @see #detectSomaAt(RandomAccessibleInterval, long, long, double)
     * @see AbstractGWDTTracer#setAutoFilter(boolean)
     */
    public static List<SomaResult> detectSomasAt(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final List<long[]> seeds) {

        if (source == null || seeds == null || seeds.isEmpty()) {
            return new ArrayList<>();
        }
        final List<SomaResult> results = new ArrayList<>();
        for (final long[] seed : seeds) {
            if (seed == null || seed.length < 2) continue;
            final SomaResult result = detectSomaAt(source, seed[0], seed[1], -1);
            if (result != null) {
                results.add(result);
            }
        }
        // Sort by radius descending (consistent with detectAllSomas)
        results.sort((a, b) -> Double.compare(b.radius(), a.radius()));
        return results;
    }

    /**
     * Detects somas at the given seed coordinates using an ImgPlus.
     * <p>
     * Each seed can be either {@code long[]{x, y}} or
     * {@code long[]{x, y, z}}. For 3D images, the Z coordinate determines
     * which slice is used for soma characterization:
     * </p>
     * <ul>
     *   <li>{@code z >= 0}: the specified slice (0-indexed) is used directly</li>
     *   <li>{@code z < 0} (or seed has only 2 elements): Z is resolved
     *       automatically per soma using MIP-based intensity lookup</li>
     * </ul>
     * <p>
     * For 2D images, the Z component (if present) is ignored.
     * </p>
     * <p>
     * Since seeds are user-provided, callers should call
     * {@link AbstractGWDTTracer#setAutoFilter(boolean) setAutoFilter(false)}
     * on the tracer before passing the results to
     * {@link AbstractGWDTTracer#traceMultiSoma(List)}.
     * </p>
     *
     * @param source  input image (2D or 3D ImgPlus)
     * @param seeds   list of {@code long[]{x, y}} or {@code long[]{x, y, z}}
     *                pixel coordinates. Z is 0-indexed; {@code -1} means
     *                auto-resolve via MIP-based intensity lookup
     * @return list of successfully detected SomaResults, sorted by radius
     *         (largest first)
     * @see #detectSomasAt(RandomAccessibleInterval, List)
     * @see AbstractGWDTTracer#setAutoFilter(boolean)
     */
    public static List<SomaResult> detectSomasAt(
            final ImgPlus<?> source,
            final List<long[]> seeds) {

        if (source == null || seeds == null || seeds.isEmpty()) {
            return new ArrayList<>();
        }
        final boolean is3D = source.numDimensions() > 2;
        final List<SomaResult> results = new ArrayList<>();
        for (final long[] seed : seeds) {
            if (seed == null || seed.length < 2) continue;
            final int z;
            if (!is3D) {
                z = -1;
            } else if (seed.length >= 3 && seed[2] >= 0) {
                z = (int) seed[2];
            } else {
                z = resolveZForSoma((RandomAccessibleInterval<RealType<?>>) source, seed[0], seed[1]);
            }
            final SomaResult result = detectSomaAt(source, seed[0], seed[1], -1, z);
            if (result != null) {
                results.add(result);
            }
        }
        results.sort((a, b) -> Double.compare(b.radius(), a.radius()));
        return results;
    }

    /**
     * Detects somas at the centroids of the given ROIs. This is a convenience
     * method that extracts centroids via
     * {@link sc.fiji.snt.analysis.RoiConverter#getCentroids(java.util.Collection)}
     * and delegates to {@link #detectSomasAt(ImgPlus, List)}.
     * <p>
     * This supports any ROI type (point, area, line, composite). For area
     * ROIs, the contour centroid is used; for point ROIs, the point
     * coordinates are used directly. The Z coordinate for each seed is
     * derived from {@link ij.gui.Roi#getZPosition()}: ROIs associated with
     * a specific slice use that slice; ROIs without slice association have
     * their Z resolved automatically via MIP-based intensity lookup.
     * </p>
     * <p>
     * Since seeds are user-provided, callers should call
     * {@link AbstractGWDTTracer#setAutoFilter(boolean) setAutoFilter(false)}
     * on the tracer before passing the results to
     * {@link AbstractGWDTTracer#traceMultiSoma(List)}. Example:
     * <pre>{@code
     * List<SomaResult> somas = SomaUtils.detectSomasAt(imp, rois);
     * tracer.setAutoFilter(false);
     * List<Tree> trees = tracer.traceMultiSoma(somas);
     * }</pre>
     * </p>
     *
     * @param imp    the source image (ImagePlus)
     * @param rois   ROIs whose centroids serve as soma seeds
     * @return list of successfully detected SomaResults, sorted by radius
     *         (largest first)
     * @see sc.fiji.snt.analysis.RoiConverter#getCentroids(java.util.Collection)
     * @see AbstractGWDTTracer#setAutoFilter(boolean)
     */
    public static List<SomaResult> detectSomasAt(
            final ij.ImagePlus imp,
            final java.util.Collection<ij.gui.Roi> rois) {

        if (imp == null || rois == null || rois.isEmpty()) {
            return new ArrayList<>();
        }
        final List<long[]> seeds = sc.fiji.snt.analysis.RoiConverter.getCentroids(rois);
        if (seeds.isEmpty()) {
            return new ArrayList<>();
        }
        final net.imagej.ImgPlus<?> img = sc.fiji.snt.util.ImpUtils.toImgPlus(imp);
        return detectSomasAt(img, seeds);
    }

    /**
     * Detects all somas using EDT local maxima approach.
     * Finds local maxima in the distance transform - these correspond to
     * centers of thick structures (somas), not thin structures (neurites).
     *
     * @param source    input image (2D)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param minRadius minimum soma radius in voxels to include (filters small debris)
     * @return list of SomaResult, sorted by radius (largest first); empty list if none found
     */
    public static List<SomaResult> detectAllSomas(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double minRadius) {
        return detectAllSomas(source, threshold, minRadius, 0);
    }

    /**
     * Detects all somas in a 2D image using EDT + local maxima + NMS.
     * <p>
     * Pipeline: threshold → binary mask → EDT → local maxima (with minRadius
     * filter) → non-maximum suppression (with minSomaDistance) → flood fill
     * for each maximum → SomaResult.
     * </p>
     *
     * @param source           input image (2D)
     * @param threshold        intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param minRadius        minimum soma radius in voxels to include
     * @param minSomaDistance   minimum distance (in voxels) between soma centers.
     *                         If &gt; 0, non-maximum suppression is applied to
     *                         eliminate clusters of spurious detections. Should
     *                         reflect the minimum known distance between real
     *                         somas in the image.
     * @return list of SomaResult, sorted by radius (largest first); empty list if none found
     */
    public static List<SomaResult> detectAllSomas(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double minRadius,
            final double minSomaDistance) {

        final List<SomaResult> results = new ArrayList<>();

        if (source == null || source.numDimensions() < 2) {
            return results;
        }

        final long width = source.dimension(0);
        final long height = source.dimension(1);
        SNTUtils.log(String.format("detectAllSomas: %dx%d image, threshold=%s, minRadius=%.1f, minSomaDistance=%.1f",
                width, height, (threshold < 0 ? "auto" : String.format("%.1f", threshold)),
                minRadius, minSomaDistance));

        // 1. Compute threshold
        SNTUtils.log("  Computing threshold...");
        final double effectiveThreshold = resolveThreshold(source, threshold);
        SNTUtils.log(String.format("  Threshold: %.1f", effectiveThreshold));

        // 2. Create binary mask from threshold
        final Img<BitType> foreground = ArrayImgs.bits(width, height);
        final Cursor<? extends RealType<?>> srcCursor = Views.flatIterable(source).cursor();
        final Cursor<BitType> maskCursor = foreground.cursor();
        while (srcCursor.hasNext()) {
            srcCursor.fwd();
            maskCursor.fwd();
            maskCursor.get().set(srcCursor.get().getRealDouble() > effectiveThreshold);
        }

        // 3. Compute EDT (invert mask: TRUE = background for EDT)
        SNTUtils.log("  Computing EDT...");
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

        // 4. Find local maxima in EDT with minimum value filter and NMS
        SNTUtils.log("  Finding EDT local maxima...");
        final List<long[]> somaCenters = findEDTLocalMaxima(edt, minRadius, minSomaDistance);
        SNTUtils.log("  Found " + somaCenters.size() + " local maxima");

        // 5. Build SomaResult for each local maximum (flood fill per soma)
        SNTUtils.log("  Characterizing " + somaCenters.size() + " candidate somas...");
        for (final long[] center : somaCenters) {
            final SomaResult result = buildSomaResult(source, center[0], center[1], effectiveThreshold);
            if (result != null && result.radius >= minRadius) {
                results.add(result);
            }
        }

        // Sort by radius (largest first)
        results.sort(Comparator.comparingDouble(SomaResult::radius).reversed());

        SNTUtils.log(String.format("  detectAllSomas complete: %d somas detected (largest radius=%.1f)",
                results.size(), results.isEmpty() ? 0 : results.getFirst().radius()));

        return results;
    }

    /**
     * Finds local maxima in EDT that exceed minimum radius.
     * A local maximum indicates a point maximally distant from background - i.e., center of a thick structure.
     */
    @SuppressWarnings("unused")
    private static List<long[]> findEDTLocalMaxima(final Img<FloatType> edt, final double minRadius) {
        return findEDTLocalMaxima(edt, minRadius, 0);
    }

    /**
     * Finds local maxima in EDT exceeding {@code minRadius}, then applies
     * non-maximum suppression (NMS) if {@code minSomaDistance > 0}. NMS
     * iteratively keeps the highest-EDT-value maximum and suppresses any
     * others within {@code minSomaDistance} voxels. This prevents clusters
     * of false detections (e.g., from JPEG compression artifacts) from
     * producing hundreds of spurious somas.
     *
     * @param edt              the Euclidean Distance Transform image
     * @param minRadius        minimum EDT value to consider as a local maximum
     * @param minSomaDistance   minimum distance (in voxels) between retained
     *                         maxima. If &le; 0, NMS is skipped.
     * @return list of local maxima positions, sorted by EDT value (highest first)
     *         when NMS is applied
     */
    private static List<long[]> findEDTLocalMaxima(final Img<FloatType> edt, final double minRadius,
                                                    final double minSomaDistance) {
        final List<long[]> maxima = new ArrayList<>();
        final List<Double> edtValues = new ArrayList<>();
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
                    edtValues.add(val);
                }
            }
        }

        // Apply NMS if minSomaDistance is set
        if (minSomaDistance > 0 && maxima.size() > 1) {
            return applyNMS(maxima, edtValues, minSomaDistance);
        }

        return maxima;
    }

    /**
     * Non-maximum suppression: keep the highest-EDT-value maximum, suppress
     * all others within {@code minDist}, repeat until all maxima are either
     * kept or suppressed.
     */
    private static List<long[]> applyNMS(final List<long[]> maxima, final List<Double> values,
                                          final double minDist) {
        final int n = maxima.size();
        final double minDistSq = minDist * minDist;

        // Sort by EDT value descending (highest = most likely real soma)
        final Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(values.get(b), values.get(a)));

        final boolean[] suppressed = new boolean[n];
        final List<long[]> kept = new ArrayList<>();

        for (final int idx : indices) {
            if (suppressed[idx]) continue;

            kept.add(maxima.get(idx));

            // Suppress nearby maxima
            final long[] pos = maxima.get(idx);
            for (int j = 0; j < n; j++) {
                if (j == idx || suppressed[j]) continue;
                final long[] other = maxima.get(j);
                final double dx = pos[0] - other[0];
                final double dy = pos[1] - other[1];
                if (dx * dx + dy * dy <= minDistSq) {
                    suppressed[j] = true;
                }
            }
        }

        return kept;
    }

    /**
     * Detects all somas using default minimum radius.
     *
     * @param source    input image (2D)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @return list of SomaResult, sorted by radius (largest first)
     */
    public static List<SomaResult> detectAllSomas(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold) {
        return detectAllSomas(source, threshold, DEFAULT_MIN_RADIUS);
    }

    /**
     * Detects all somas using ImgPlus with automatic spacing.
     *
     * @param source    input image (2D or 3D ImgPlus)
     * @param threshold intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param zSlice    Z-slice to use (0-indexed), or -1 for automatic
     *                  detection. When -1 and the image is 3D, detection runs
     *                  on a max-intensity projection and Z is resolved per soma
     *                  from the peak intensity in the original stack. When
     *                  &ge; 0, detection runs on that single Z-plane only.
     *                  Ignored for 2D images.
     * @param minRadius minimum soma radius in voxels
     * @return list of SomaResult, sorted by radius (largest first)
     */
    public static List<SomaResult> detectAllSomas(
            final ImgPlus<?> source,
            final double threshold,
            final int zSlice,
            final double minRadius) {
        return detectAllSomas(source, threshold, zSlice, minRadius, 0);
    }

    /**
     * Detects all somas using ImgPlus with automatic spacing and NMS.
     * <p>
     * For 3D images, the behavior depends on {@code zSlice}:
     * <ul>
     *   <li><b>zSlice &ge; 0</b>: detection runs on the specified Z-plane only
     *       (useful for interactive GUI where the user is viewing a specific
     *       plane)</li>
     *   <li><b>zSlice &lt; 0</b>: a max-intensity projection (MIP) is computed
     *       and soma (x, y) centers are detected on it. For each detected soma,
     *       the Z coordinate is then resolved independently by finding the
     *       Z-plane with the peak intensity at that (x, y) column. This allows
     *       detection of somas spread across different Z-planes without
     *       requiring the user to specify one.</li>
     * </ul>
     * For 2D images, {@code zSlice} is ignored.
     * </p>
     *
     * @param source           input image (2D or 3D ImgPlus)
     * @param threshold        intensity threshold. Use -1 for Otsu, NaN for mean.
     * @param zSlice           Z-slice (0-indexed), or -1 for automatic MIP-based
     *                         detection. See above.
     * @param minRadius        minimum soma radius in voxels
     * @param minSomaDistance   minimum distance (in voxels) between soma centers
     *                         for non-maximum suppression. If &le; 0, NMS is skipped.
     * @return list of SomaResult, sorted by radius (largest first)
     */
    @SuppressWarnings("unchecked")
    public static List<SomaResult> detectAllSomas(
            final ImgPlus<?> source,
            final double threshold,
            final int zSlice,
            final double minRadius,
            final double minSomaDistance) {

        if (source == null) {
            return new ArrayList<>();
        }

        final String units = ImgUtils.getSpacingUnits(source);
        final boolean is3D = source.numDimensions() > 2;

        final RandomAccessibleInterval<? extends RealType<?>> detectionPlane;
        if (!is3D) {
            // 2D image: detect directly
            detectionPlane = (RandomAccessibleInterval<? extends RealType<?>>) source;
        } else if (zSlice >= 0 && zSlice < source.dimension(2)) {
            // Explicit Z-slice requested (e.g., from interactive GUI)
            SNTUtils.log("detectAllSomas: using explicit Z-slice " + zSlice);
            detectionPlane = (RandomAccessibleInterval<? extends RealType<?>>)
                    Views.hyperSlice(source, 2, zSlice);
        } else {
            // Auto mode: detect on max-intensity projection
            SNTUtils.log("detectAllSomas: computing MIP from " + source.dimension(2) + " Z-planes...");
            detectionPlane = ImgUtils.maxIntensityProjection((RandomAccessibleInterval<RealType<?>>) source);
        }

        final List<SomaResult> baseResults = detectAllSomas(detectionPlane, threshold, minRadius, minSomaDistance);

        // Enrich results with units and per-soma Z
        final List<SomaResult> enrichedResults = new ArrayList<>();
        for (final SomaResult base : baseResults) {
            final int somaZ;
            if (!is3D) {
                somaZ = -1;
            } else if (zSlice >= 0 && zSlice < source.dimension(2)) {
                // Explicit slice: all somas share the same Z
                somaZ = zSlice;
            } else {
                // MIP mode: resolve per-soma Z from peak intensity in the original stack
                somaZ = resolveZForSoma(
                        (RandomAccessibleInterval<RealType<?>>) source,
                        base.center[0], base.center[1]);
            }
            enrichedResults.add(new SomaResult(
                    base.center, base.centroid, base.mask, base.contour,
                    base.radius, base.threshold, somaZ, units
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
     * Computes the EDT (Euclidean Distance Transform) from the source image and
     * samples the value at each soma center, returning an array of EDT values
     * (one per soma, same order as input list).
     * <p>
     * <b>Binarization threshold:</b> Uses the Minimum auto-threshold (via
     * {@link #computeMinimumThreshold}) rather than Otsu on the full image.
     * Otsu can produce very low thresholds for images with large bright
     * regions, making nearly everything foreground and causing the EDT to
     * measure distance-to-nearest-dark-pixel rather than local structure
     * thickness. The Minimum method, which finds the valley between two
     * histogram peaks, produces a more restrictive threshold that better
     * isolates bright structures.
     * </p>
     * <p>
     * <b>Known limitation:</b> Even with the Minimum threshold, images that
     * have large contiguous regions above the threshold will still produce
     * high EDT values at points deep inside those regions, regardless of
     * whether a real soma body exists there. A fundamentally different
     * approach (e.g., local EDT windows or non-EDT-based ranking) may be
     * needed for such images.
     * </p>
     *
     * @param <T>    pixel type
     * @param somas  list of detected somas
     * @param source the original source image (2D)
     * @return array of EDT values at each soma center (same length and order as
     *         {@code somas})
     * @see #computeMinimumThreshold
     */
    private static <T extends RealType<T>> double[] computeEdtAtSomaCenters(
            final List<SomaResult> somas,
            final RandomAccessibleInterval<T> source) {

        final int count = somas.size();
        final long width = source.dimension(0);
        final long height = source.dimension(1);

        // Use the Minimum auto-threshold for EDT binarization. Unlike Otsu
        // (which can be very low for images with large bright regions, making
        // nearly everything foreground), the Minimum method finds the valley
        // between the two histogram peaks restricting foreground to bright
        // soma-like structures so the EDT correctly measures body half-width.
        final double edtThreshold = computeMinimumThreshold(source);

        SNTUtils.log(String.format("  computeEdtAtSomaCenters: Minimum threshold=%.1f",
                edtThreshold));

        // Create binary mask and compute EDT
        final Img<BitType> inverted = ArrayImgs.bits(width, height);
        final Cursor<? extends RealType<?>> srcCursor = Views.flatIterable(source).cursor();
        final Cursor<BitType> ic = inverted.cursor();
        while (srcCursor.hasNext()) {
            srcCursor.fwd();
            ic.fwd();
            // TRUE = background (EDT measures distance FROM background)
            ic.get().set(srcCursor.get().getRealDouble() <= edtThreshold);
        }

        final Img<FloatType> edt = ArrayImgs.floats(width, height);
        DistanceTransform.binaryTransform(inverted, edt, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

        // Sample EDT at each soma center
        final RandomAccess<FloatType> edtRA = edt.randomAccess();
        final double[] edtValues = new double[count];
        for (int i = 0; i < count; i++) {
            final long[] center = somas.get(i).center();
            final long x = Math.max(0, Math.min(width - 1, center[0]));
            final long y = Math.max(0, Math.min(height - 1, center.length > 1 ? center[1] : 0));
            edtRA.setPosition(new long[]{x, y});
            edtValues[i] = edtRA.get().getRealDouble();
        }

        return edtValues;
    }

    /**
     * Selects the top-N somas by EDT thickness (distance to nearest background
     * pixel at each soma's center), ranking by descending EDT value.
     * <p>
     * <b>Experimental — known limitations:</b> The EDT is computed from a
     * binary mask obtained by applying the Minimum auto-threshold to the
     * source image (see {@link #computeEdtAtSomaCenters}). For images with
     * large connected foreground regions (common in compressed formats like
     * JPEG, or images with dense neuropil), the EDT at a point measures the
     * distance to the nearest below-threshold pixel — <em>not</em> the local
     * soma body half-width. False detections located deep inside such regions
     * can receive artificially high EDT values (hundreds of pixels), ranking
     * higher than real somas whose EDT only reflects the soma's actual radius
     * (~15-20 px). In testing, this caused 4 of 5 selected somas to be false
     * detections.
     * </p>
     * <p>
     * For reliable soma selection, prefer using
     * {@link AbstractGWDTTracer#setMinSomaDistance(double)} instead, which
     * drives standard NMS and consolidation without depending on global EDT.
     * </p>
     *
     * @param <T>    pixel type
     * @param somas  list of detected somas
     * @param source the original source image (2D), used for EDT computation
     * @param n      number of somas to keep (must be &ge; 1)
     * @return the top-N somas sorted by EDT thickness (highest first), or the
     *         original list if {@code n >= somas.size()} or inputs are invalid
     * @see #computeEdtAtSomaCenters
     * @see #filterSomasByThickness
     */
    public static <T extends RealType<T>> List<SomaResult> selectTopSomasByThickness(
            final List<SomaResult> somas,
            final RandomAccessibleInterval<T> source,
            final int n) {

        if (somas == null || source == null || n < 1 || n >= somas.size()) {
            return somas;
        }

        final int count = somas.size();
        final double[] edtValues = computeEdtAtSomaCenters(somas, source);

        // Sort indices by EDT value descending
        final Integer[] indices = new Integer[count];
        for (int i = 0; i < count; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(edtValues[b], edtValues[a]));

        // Take top N
        final List<SomaResult> topN = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            topN.add(somas.get(indices[i]));
        }

        SNTUtils.log(String.format("selectTopSomasByThickness: %d → %d (EDT range of kept: [%.1f, %.1f])",
                count, n, edtValues[indices[n - 1]], edtValues[indices[0]]));

        return topN;
    }

    /**
     * Filters a list of detected somas by their local EDT thickness, using
     * Otsu's method on the EDT value distribution to separate thick (real
     * soma) from thin (artifact) detections.
     * <p>
     * The method computes an EDT using the Minimum auto-threshold for
     * binarization (see {@link #computeEdtAtSomaCenters}), samples the EDT
     * value at each soma center, then applies a second Otsu threshold on those
     * sampled values. Somas with EDT above the second Otsu are kept.
     * </p>
     * <p>
     * <b>Experimental: known limitations:</b> This method shares the same
     * fundamental limitation as {@link #selectTopSomasByThickness}: the EDT
     * depends on a global binarization threshold, which may not produce
     * meaningful local thickness measurements for images with large connected
     * foreground regions. The second Otsu (on the EDT values themselves)
     * partially compensates: it can separate the bimodal distribution of
     * "high EDT at false detections deep inside foreground" from "low EDT at
     * artifacts near boundaries": but the resulting threshold is image-
     * dependent and may be too aggressive (filtering out real somas) or too
     * lenient (keeping false detections). In testing with a JPEG image, this
     * method reduced 495 detections to 18, losing 1 of 5 real somas.
     * </p>
     * <p>
     * Used internally by the auto-estimation branch of
     * {@link AbstractGWDTTracer#traceMultiSoma(List)} when neither
     * {@code nSomas} nor {@code minSomaDistance} is specified.
     * </p>
     *
     * @param <T>    pixel type
     * @param somas  list of detected somas (at least 2 for meaningful filtering)
     * @param source the original source image used for detection (2D)
     * @return filtered list containing only somas with EDT thickness above
     *         Otsu's threshold; returns the original list if filtering is not
     *         applicable or produces degenerate results
     * @see #computeEdtAtSomaCenters
     * @see #selectTopSomasByThickness
     */
    public static <T extends RealType<T>> List<SomaResult> filterSomasByThickness(
            final List<SomaResult> somas,
            final RandomAccessibleInterval<T> source) {

        if (somas == null || somas.size() < 2 || source == null) {
            return somas;
        }

        final int n = somas.size();

        // Compute EDT using soma-center-derived threshold
        final double[] edtValues = computeEdtAtSomaCenters(somas, source);

        double minEdt = Double.MAX_VALUE;
        double maxEdt = Double.NEGATIVE_INFINITY;
        for (final double v : edtValues) {
            minEdt = Math.min(minEdt, v);
            maxEdt = Math.max(maxEdt, v);
        }

        SNTUtils.log(String.format("filterSomasByThickness: EDT range=[%.1f, %.1f], %d somas",
                minEdt, maxEdt, n));

        if (maxEdt <= minEdt) return somas;

        // Otsu on EDT values to find thickness threshold
        final double edtOtsu = computeOtsuThreshold(
                ArrayImgs.doubles(edtValues, edtValues.length));

        SNTUtils.log(String.format("  EDT Otsu threshold=%.1f", edtOtsu));

        // Log distribution details
        final double[] sorted = edtValues.clone();
        Arrays.sort(sorted);
        SNTUtils.log(String.format("  EDT percentiles: p10=%.1f, p25=%.1f, median=%.1f, p75=%.1f, p90=%.1f",
                sorted[(int) (n * 0.1)], sorted[(int) (n * 0.25)],
                sorted[n / 2], sorted[(int) (n * 0.75)], sorted[(int) (n * 0.9)]));

        // Filter: keep somas above EDT threshold
        final List<SomaResult> filtered = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (edtValues[i] > edtOtsu) {
                filtered.add(somas.get(i));
            }
        }

        SNTUtils.log(String.format("  %d somas above EDT threshold (kept), %d below (removed)",
                filtered.size(), n - filtered.size()));

        // Safety: if filtering removed everything or kept everything, return original
        if (filtered.isEmpty() || filtered.size() == n) {
            SNTUtils.log("  EDT thickness filtering had no discriminative effect, returning original list");
            return somas;
        }

        return filtered;
    }

    /**
     * Filters a list of detected somas by sampling the source image intensity
     * at each soma center and applying Otsu's threshold to separate real
     * (bright) somas from false detections (dim).
     * <p>
     * Note: for JPEG images, intensity alone may not discriminate well because
     * compression artifacts can create bright spots. Prefer
     * {@link #filterSomasByThickness} which uses geometric thickness (EDT)
     * as the discriminator.
     * </p>
     *
     * @param <T>    pixel type
     * @param somas  list of detected somas (at least 2 for meaningful filtering)
     * @param source the original source image used for detection (2D)
     * @return filtered list containing only somas with center intensity above
     *         Otsu's threshold; returns the original list if filtering is not
     *         applicable (fewer than 2 somas, or Otsu produces degenerate threshold)
     */
    public static <T extends RealType<T>> List<SomaResult> filterSomasByIntensity(
            final List<SomaResult> somas,
            final RandomAccessibleInterval<T> source) {

        if (somas == null || somas.size() < 2 || source == null) {
            return somas;
        }

        final int n = somas.size();
        final RandomAccess<T> ra = source.randomAccess();

        // Sample source intensity at each soma center
        final double[] intensities = new double[n];
        double minVal = Double.MAX_VALUE;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            final long[] center = somas.get(i).center();
            // Clamp to image bounds
            final long x = Math.max(source.min(0), Math.min(source.max(0), center[0]));
            final long y = Math.max(source.min(1), Math.min(source.max(1),
                    center.length > 1 ? center[1] : 0));
            ra.setPosition(new long[]{x, y});
            intensities[i] = ra.get().getRealDouble();
            minVal = Math.min(minVal, intensities[i]);
            maxVal = Math.max(maxVal, intensities[i]);
        }

        // Compute Otsu's threshold on the intensity distribution of soma centers
        if (maxVal <= minVal) return somas;

        final double otsuThreshold = computeOtsuThreshold(
                ArrayImgs.doubles(intensities, intensities.length));

        SNTUtils.log(String.format("filterSomasByIntensity: intensity range=[%.1f, %.1f], "
                        + "Otsu=%.1f, %d somas input",
                minVal, maxVal, otsuThreshold, n));

        // Filter: keep somas whose center intensity exceeds Otsu threshold
        final List<SomaResult> filtered = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (intensities[i] > otsuThreshold) {
                filtered.add(somas.get(i));
            }
        }

        SNTUtils.log(String.format("  %d somas above threshold (kept), %d below (removed)",
                filtered.size(), n - filtered.size()));

        if (filtered.isEmpty() || filtered.size() == n) {
            SNTUtils.log("  Intensity filtering had no discriminative effect, returning original list");
            return somas;
        }

        return filtered;
    }

    /**
     * Estimates the minimum inter-soma distance from a list of detected somas
     * using single-linkage hierarchical clustering with gap analysis.
     * <p>
     * This overload does not filter by intensity and operates directly on the
     * provided soma list. For images with many false detections (e.g., JPEG
     * artifacts), prefer
     * {@link #estimateMinSomaDistance(List, RandomAccessibleInterval)}
     * which pre-filters somas by intensity before clustering.
     * </p>
     *
     * @param somas   list of detected somas (at least 3 required)
     * @return estimated minimum distance between real soma centers (in voxels),
     *         or 0 if fewer than 3 somas or no clear gap is found
     * @see #estimateMinSomaDistance(List, RandomAccessibleInterval)
     */
    public static double estimateMinSomaDistance(final List<SomaResult> somas) {
        return estimateMinSomaDistanceFromClustering(somas);
    }

    /**
     * Estimates the minimum inter-soma distance using a hybrid approach:
     * EDT-thickness filtering followed by spatial gap analysis.
     * <p>
     * Pipeline:
     * <ol>
     *   <li><b>Thickness filter</b> (experimental): recompute EDT from source
     *       using the Minimum auto-threshold, sample EDT at each soma center,
     *       apply Otsu on those values to separate thick from thin detections,
     *       see {@link #filterSomasByThickness}</li>
     *   <li><b>Spatial gap analysis</b>: run single-linkage hierarchical
     *       clustering on the surviving somas, find the largest ratio gap in
     *       merge heights, return the geometric mean of the gap boundaries</li>
     * </ol>
     * </p>
     * <p>
     * <b>Experimental:</b> The quality of the estimate depends heavily on
     * step 1 (thickness filtering), which has known limitations for images
     * with large connected foreground regions, see
     * {@link #filterSomasByThickness} javadoc. The gap analysis in step 2 is
     * well-founded (standard hierarchical clustering), but is only as good as
     * the input somas it receives.
     * </p>
     *
     * @param <T>    pixel type
     * @param somas  list of detected somas (at least 3 required)
     * @param source the original source image, used for EDT computation
     * @return estimated minimum distance between real soma centers (in voxels),
     *         or 0 if estimation fails
     * @see #filterSomasByThickness
     */
    public static <T extends RealType<T>> double estimateMinSomaDistance(
            final List<SomaResult> somas,
            final RandomAccessibleInterval<T> source) {

        if (somas == null || somas.size() < 3) return 0;

        // Step 1: Filter by EDT thickness to remove geometrically thin false detections
        final List<SomaResult> thickSomas = filterSomasByThickness(somas, source);
        SNTUtils.log("estimateMinSomaDistance (hybrid): " + somas.size()
                + " raw → " + thickSomas.size() + " after thickness filter");

        if (thickSomas.size() < 2) {
            SNTUtils.log("  Too few somas after thickness filter, returning 0");
            return 0;
        }

        if (thickSomas.size() == 2) {
            // With exactly 2 somas, just return their distance
            final long[] c1 = thickSomas.get(0).center();
            final long[] c2 = thickSomas.get(1).center();
            double distSq = 0;
            for (int d = 0; d < Math.min(c1.length, c2.length); d++) {
                final double diff = c1[d] - c2[d];
                distSq += diff * diff;
            }
            final double dist = Math.sqrt(distSq);
            SNTUtils.log(String.format("  2 somas remaining, distance=%.1f voxels", dist));
            return dist;
        }

        // Step 2: Spatial gap analysis on filtered set
        return estimateMinSomaDistanceFromClustering(thickSomas);
    }

    /**
     * Core single-linkage hierarchical clustering gap analysis.
     * Finds the largest ratio gap in merge heights to separate intra-cluster
     * distances from inter-cluster distances.
     */
    private static double estimateMinSomaDistanceFromClustering(final List<SomaResult> somas) {
        if (somas == null || somas.size() < 3) return 0;

        final int n = somas.size();

        // Build voxel-coordinate data matrix for Smile
        final double[][] positions = new double[n][];
        for (int i = 0; i < n; i++) {
            final long[] center = somas.get(i).center();
            positions[i] = new double[center.length];
            for (int d = 0; d < center.length; d++) {
                positions[i][d] = center[d];
            }
        }

        // Single-linkage hierarchical clustering via Smile
        SNTUtils.log("estimateMinSomaDistance: computing proximity for " + n + " somas...");
        final float[] proximity = Linkage.proximity(positions);
        SNTUtils.log("  proximity length: " + proximity.length
                + " (expected " + (n * (n + 1) / 2) + ")");
        final SingleLinkage linkage = new SingleLinkage(n, proximity);
        final HierarchicalClustering hc = HierarchicalClustering.fit(linkage);

        // height() returns n-1 non-decreasing merge distances
        final double[] heights = hc.height();
        SNTUtils.log("  heights: " + heights.length + " merges");
        if (heights.length < 2) return 0;

        // Log a sample of heights for diagnostics
        SNTUtils.log(String.format("  first 5 heights: [%.2f, %.2f, %.2f, %.2f, %.2f]",
                heights[0], heights[Math.min(1, heights.length - 1)],
                heights[Math.min(2, heights.length - 1)],
                heights[Math.min(3, heights.length - 1)],
                heights[Math.min(4, heights.length - 1)]));
        SNTUtils.log(String.format("  last 5 heights: [%.2f, %.2f, %.2f, %.2f, %.2f]",
                heights[Math.max(0, heights.length - 5)],
                heights[Math.max(0, heights.length - 4)],
                heights[Math.max(0, heights.length - 3)],
                heights[Math.max(0, heights.length - 2)],
                heights[heights.length - 1]));

        // Find the largest ratio gap in consecutive merge heights.
        // The gap separates intra-cluster merges (small, between false somas)
        // from inter-cluster merges (large, between real cells).
        double bestGapRatio = 0;
        int bestGapIdx = -1;
        for (int i = 0; i < heights.length - 1; i++) {
            if (heights[i] > 0) {
                final double ratio = heights[i + 1] / heights[i];
                if (ratio > bestGapRatio) {
                    bestGapRatio = ratio;
                    bestGapIdx = i;
                }
            }
        }

        SNTUtils.log(String.format("  best gap: ratio=%.2f at index %d (%.2f -> %.2f)",
                bestGapRatio, bestGapIdx,
                bestGapIdx >= 0 ? heights[bestGapIdx] : 0,
                bestGapIdx >= 0 ? heights[bestGapIdx + 1] : 0));

        // Require a meaningful gap: at least 3x jump suggests real cluster separation
        if (bestGapRatio < 3.0 || bestGapIdx < 0) {
            SNTUtils.log("  No clear gap found (ratio < 3.0), returning 0");
            return 0;
        }

        // Return the geometric mean of the gap boundaries as a robust midpoint
        final double belowGap = heights[bestGapIdx];
        final double aboveGap = heights[bestGapIdx + 1];
        final double result = Math.sqrt(belowGap * aboveGap);
        SNTUtils.log(String.format("  estimated minSomaDistance: %.1f voxels", result));
        return result;
    }

    /**
     * Detects soma within a specific labeled region.
     */
    @SuppressWarnings("unused")
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

        // After pruning, the mask may contain disconnected fragments.
        // Keep only the connected component containing the seed point.
        retainSeedComponent(mask, seedX, seedY);

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

    /**
     * For a detected (x, y) soma center, finds the Z-plane with the highest
     * intensity in the original 3D stack at that column. Returns 0 for 2D images.
     */
    private static <T extends RealType<T>> int resolveZForSoma(
            final @UnknownNullability RandomAccessibleInterval<RealType<?>> source,
            final long x, final long y) {

        if (source.numDimensions() <= 2) return 0;

        final long depth = source.dimension(2);
        final RandomAccess<T> ra = (RandomAccess<T>) source.randomAccess();
        double bestVal = Double.NEGATIVE_INFINITY;
        int bestZ = 0;
        for (int z = 0; z < depth; z++) {
            ra.setPosition(new long[]{x, y, z});
            final double val = ra.get().getRealDouble();
            if (val > bestVal) {
                bestVal = val;
                bestZ = z;
            }
        }
        return bestZ;
    }

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
            final long[] pos = stack.removeLast();
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

    /**
     * Retains only the connected component containing the seed point in the
     * given binary mask. All other foreground pixels are cleared. This is used
     * after {@link #pruneNarrowRegions} to discard disconnected fragments that
     * do not belong to the seed's soma.
     *
     * @param mask  binary mask (modified in place)
     * @param seedX seed X coordinate
     * @param seedY seed Y coordinate
     */
    private static void retainSeedComponent(final Img<BitType> mask, final long seedX, final long seedY) {
        final RandomAccess<BitType> ra = mask.randomAccess();
        ra.setPosition(new long[]{seedX, seedY});
        if (!ra.get().get()) return; // seed was pruned; nothing to retain

        final long width = mask.dimension(0);
        final long height = mask.dimension(1);

        // Flood fill from seed on the pruned mask to find its component
        final Img<BitType> component = ArrayImgs.bits(width, height);
        final RandomAccess<BitType> compRA = component.randomAccess();
        final RandomAccess<BitType> maskRA = mask.randomAccess();

        final List<long[]> stack = new ArrayList<>();
        stack.add(new long[]{seedX, seedY});

        while (!stack.isEmpty()) {
            final long[] pos = stack.removeLast();
            final long x = pos[0];
            final long y = pos[1];
            if (x < 0 || x >= width || y < 0 || y >= height) continue;
            compRA.setPosition(pos);
            if (compRA.get().get()) continue; // already visited
            maskRA.setPosition(pos);
            if (!maskRA.get().get()) continue; // not foreground
            compRA.get().set(true);
            stack.add(new long[]{x + 1, y});
            stack.add(new long[]{x - 1, y});
            stack.add(new long[]{x, y + 1});
            stack.add(new long[]{x, y - 1});
        }

        // Replace mask with just the seed's component
        final Cursor<BitType> maskCursor = mask.cursor();
        final Cursor<BitType> compCursor = component.cursor();
        while (maskCursor.hasNext()) {
            maskCursor.fwd();
            compCursor.fwd();
            maskCursor.get().set(compCursor.get().get());
        }
    }

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

    /**
     * Computes the Minimum auto-threshold of the given image. The Minimum
     * method finds the valley between two peaks in the histogram, making it
     * more restrictive than Otsu for images with large bright regions.
     *
     * @param source the input image
     * @return Minimum threshold value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static double computeMinimumThreshold(final RandomAccessibleInterval<? extends RealType<?>> source) {
        final net.imagej.ops.OpService ops = SNTUtils.getContext().getService(net.imagej.ops.OpService.class);
        final net.imglib2.histogram.Histogram1d histogram = ops.image().histogram((Iterable) source);
        // ops.threshold().minimum() returns a List (unlike otsu() which returns a RealType)
        final java.util.List<?> result = (java.util.List<?>) ops.threshold().minimum(histogram);
        return ((RealType<?>) result.getFirst()).getRealDouble();
    }

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

    /**
     * Creates a single-node soma Path from an ImageJ ROI using calibration from a reference Path.
     *
     * @param roi           the ROI to convert (must not be null)
     * @param referencePath a Path from which to extract spacing and units (may be null for pixel units)
     * @return a single-node soma path, or null if ROI is null
     * @see #roiToSomaPath(Roi, double[])
     */
    public static Path roiToSomaPath(final Roi roi, final Path referencePath) {
        if (referencePath == null)
            return roiToSomaPath(roi, (double[]) null);
        final Path result = roiToSomaPath(roi, referencePath.getCalibration());
        if (result != null)
            result.setCTposition(referencePath.getChannel(), referencePath.getFrame());
        return result;
    }

    private static Path roiToSomaPath(final Roi roi, final ij.measure.Calibration calibration) {
        if (calibration == null)
            return roiToSomaPath(roi, (double[]) null);
        final double[] spacing = new double[]{calibration.pixelWidth, calibration.pixelHeight, calibration.pixelDepth};
        final Path path = roiToSomaPath(roi, spacing);
        if (path != null)
            path.setSpacing(calibration.pixelWidth, calibration.pixelHeight, calibration.pixelDepth, calibration.getUnit());
        return path;
    }

    /**
     * Creates a single-node soma Path from an ImageJ ROI.
     * <p>
     * Converts any ROI to a soma path centered at the ROI's centroid.
     * For area ROIs (polygon, oval, freehand, etc.), the radius is estimated
     * from the equivalent circle area. For non-area ROIs (point, line),
     * no radius is assigned.
     * </p>
     *
     * @param roi     the ROI to convert (must not be null)
     * @param spacing voxel spacing [x, y, z] for physical coordinates, or null for pixel units
     * @return a single-node soma path, or null if ROI is null
     */
    public static Path roiToSomaPath(final Roi roi, final double[] spacing) {
        if (roi == null) {
            return null;
        }
        final double sx = (spacing != null && spacing.length > 0) ? spacing[0] : 1.0;
        final double sy = (spacing != null && spacing.length > 1) ? spacing[1] : 1.0;
        final double sz = (spacing != null && spacing.length > 2) ? spacing[2] : 1.0;
        final String units = SNTUtils.getSanitizedUnit("");

        // Get centroid in pixel coordinates
        final double[] centroid = RoiConverter.get2dCentroid(roi);
        final double cx, cy;
        if (centroid != null) {
            cx = centroid[0];
            cy = centroid[1];
        } else {
            // Fallback for ROIs without contour centroid
            cx = roi.getBounds().getCenterX();
            cy = roi.getBounds().getCenterY();
        }

        // Z position (ROI uses 1-based indexing)
        final double cz = (roi.getZPosition() > 0) ? roi.getZPosition() - 1 : 0;

        // Convert to physical coordinates
        final double x = cx * sx;
        final double y = cy * sy;
        final double z = cz * sz;

        final Path path = new Path(sx, sy, sz, units);
        path.addNode(new PointInImage(x, y, z));

        // Assign radius only for area ROIs
        if (roi.isArea()) {
            final double area = roi.getStatistics().area;
            if (area > 0) {
                final double radiusPixels = Math.sqrt(area / Math.PI);
                path.setRadius(radiusPixels * (sx + sy) / 2); // Average spacing for anisotropy
            }
        }
        path.setSWCType(Path.SWC_SOMA);
        path.setName("Soma");
        return path;
    }

}
