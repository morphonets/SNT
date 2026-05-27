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

package sc.fiji.snt.seed;

import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.labeling.ConnectedComponents;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Helper that turns a labels image (e.g. cellpose, Labkit, StarDist
 * segmentation output) into a list of {@link SeedPoint}s, one per non-zero
 * label.
 * <p>
 * For each label N:
 * <ul>
 *   <li>centroid is the voxel-average position multiplied by the image
 *       calibration to physical units;
 *   <li>radius is the radius of the sphere whose volume equals the label's
 *       total volume ({@code r = cbrt(3V / 4pi)});
 *   <li>confidence is the linear interpolation of the label's volume between
 *       {@code minConfidence} (smallest label in the image) and {@code 1.0}
 *       (largest label). If all labels share the same volume, confidence is
 *       set to {@code 1.0}.
 * </ul>
 *
 * @author Tiago Ferreira
 */
public final class LabelsToSeeds {

    private static final int IDX_SX = 0;
    private static final int IDX_SY = 1;
    private static final int IDX_SZ = 2;
    private static final int IDX_COUNT = 3;

    private LabelsToSeeds() {
    }

    /**
     * Computes one {@link SeedPoint} per non-zero label in {@code imp}.
     *
     * @param imp           a 2D or 3D labels image. Channel and frame come
     *                      from {@code imp}'s active position
     * @param minConfidence floor of the volume -> confidence map, in
     *                      {@code [0, 1]}. The smallest label gets this
     *                      value, the largest gets {@code 1.0}.
     * @param type          {@link SeedPoint#type} for every produced seed
     *                      (e.g. {@code "soma"}). {@code null} -> {@code ""}
     * @param source        {@link SeedPoint#source} for every produced seed
     *                      (e.g. {@code "labels-image:cellpose.tif"})
     *                      {@code null} -> {@code ""}.
     * @return a fresh list of seeds, one per label, in label-ID order.
     * Empty if no non-zero labels are found.
     */
    public static List<SeedPoint> compute(final ImagePlus imp,
                                          final double minConfidence,
                                          final String type,
                                          final String source) {
        if (imp == null) throw new IllegalArgumentException("imp must not be null");
        final double minConf = Math.clamp(minConfidence, 0.0, 1.0);

        // Image calibration in physical units / voxel
        final double sx = imp.getCalibration().pixelWidth;
        final double sy = imp.getCalibration().pixelHeight;
        final double sz = imp.getCalibration().pixelDepth;

        // Active C/T 3D slice (singleton Z is dropped → 2D RAI for 2D images)
        final RandomAccessibleInterval<? extends RealType<?>> rai = ImgUtils.getCtSlice(imp);
        final int numDims = rai.numDimensions();

        // Per-label accumulator: {sumX, sumY, sumZ, count} in VOXEL units
        // sumZ stays at zero for 2D inputs (numDims == 2)
        final Map<Integer, double[]> acc = new HashMap<>();
        final long[] pos = new long[numDims];
        final Cursor<? extends RealType<?>> cursor = rai.localizingCursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            final int v = (int) cursor.get().getRealDouble();
            if (v == 0) continue;
            cursor.localize(pos);
            final double[] a = acc.computeIfAbsent(v, k -> new double[4]);
            a[IDX_SX] += pos[0];
            a[IDX_SY] += pos[1];
            if (numDims >= 3) a[IDX_SZ] += pos[2];
            a[IDX_COUNT] += 1;
        }
        if (acc.isEmpty()) return new ArrayList<>(0);

        // Physical voxel volume — for 2D images Z spacing is irrelevant (area
        // is reported as a "volume" of unit-depth slabs, so the radius math
        // still produces a meaningful disk-equivalent radius)
        final double voxelVolume = sx * sy * ((numDims >= 3) ? sz : 1.0);

        // Establish min/max volume for confidence normalization
        double volMin = Double.POSITIVE_INFINITY;
        double volMax = Double.NEGATIVE_INFINITY;
        for (final double[] a : acc.values()) {
            final double vol = a[IDX_COUNT] * voxelVolume;
            if (vol < volMin) volMin = vol;
            if (vol > volMax) volMax = vol;
        }
        final double volRange = volMax - volMin;

        // Channel / frame come from the active position of `imp` (1-based)
        final int channel = imp.getC();
        final int frame = imp.getT();
        final String typeSafe = (type == null) ? SeedPoint.TAG_UNSET : type;
        final String srcSafe = (source == null) ? SeedPoint.TAG_UNSET : source;

        // Stable ordering: by ascending label ID
        final List<Integer> labels = new ArrayList<>(acc.keySet());
        labels.sort(Integer::compare);

        final List<SeedPoint> seeds = new ArrayList<>(labels.size());
        for (final int label : labels) {
            final double[] a = acc.get(label);
            final double count = a[IDX_COUNT];
            final double cxVox = a[IDX_SX] / count;
            final double cyVox = a[IDX_SY] / count;
            final double czVox = (numDims >= 3) ? a[IDX_SZ] / count : 0.0;

            final double cxPhys = cxVox * sx;
            final double cyPhys = cyVox * sy;
            final double czPhys = czVox * sz;

            final double volume = count * voxelVolume;
            final double radius = Math.cbrt(3.0 * volume / (4.0 * Math.PI));
            final double conf = (volRange > 0)
                    ? minConf + (1.0 - minConf) * (volume - volMin) / volRange
                    : 1.0;
            seeds.add(new SeedPoint(cxPhys, cyPhys, czPhys, conf, radius,
                    channel, frame, typeSafe, srcSafe));
        }
        return seeds;
    }

    /**
     * Detects whether {@code imp} is a binary mask, i.e. all non-zero voxels
     * share a single value.
     *
     * @param imp the candidate image. {@code null} triggers an {@link IllegalArgumentException}.
     * @return {@code true} if exactly one distinct non-zero value is present.
     * An all-zero image returns {@code false}
     */
    public static <T extends RealType<T>> boolean isBinaryMask(final ImagePlus imp) {
        if (imp == null) throw new IllegalArgumentException("imp must not be null");
        if (ImpUtils.isBinary(imp)) return true;
        final RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice(imp);
        final Cursor<T> cursor = rai.cursor();
        double firstNonZero = 0;
        boolean seen = false;
        while (cursor.hasNext()) {
            cursor.fwd();
            final double v = cursor.get().getRealDouble();
            if (v == 0) continue;
            if (!seen) {
                firstNonZero = v;
                seen = true;
            } else if (v != firstNonZero) {
                return false;
            }
        }
        return seen;
    }

    /**
     * Labels the connected components of a binary mask, returning a new
     * labels image suitable for {@link #compute(ImagePlus, double, String, String)}.
     * Operates on the active C/T 3D slice of {@code mask}; each non-zero
     * voxel is treated as foreground, every distinct connected region gets a
     * unique integer label. The result inherits the input's calibration.
     * <p>
     * Connectivity is binary:
     * <ul>
     *   <li>{@code fullyConnected = true}: 8-connected in 2D, 26-connected in 3D.</li>
     *   <li>{@code fullyConnected = false}: 4-connected in 2D, 6-connected in 3D.</li>
     * </ul>
     *
     * @param mask           a binary mask (any RealType; non-zero = foreground).
     *                       Multi-label images can be passed safely: every
     *                       non-zero voxel is collapsed to foreground first,
     *                       so calling this on a labels image is equivalent
     *                       to re-running CCA on its non-zero union.
     * @param fullyConnected if {@code true}, use the more permissive
     *                       structuring element (8-/26-connected); else use
     *                       face-connected (4-/6-connected).
     * @return a labeled {@link ImagePlus} with one distinct integer per
     * component. Background remains 0. Same dimensions and calibration
     * as {@code mask}.
     */
    public static <T extends RealType<T>> ImagePlus connectedComponents(final ImagePlus mask, final boolean fullyConnected) {
        if (mask == null) throw new IllegalArgumentException("mask must not be null");
        final RandomAccessibleInterval<T> source = ImgUtils.getCtSlice(mask);

        // Binarize via a view: any non-zero voxel becomes 1. No data copied
        final RandomAccessibleInterval<IntType> binary = Converters.convert(
                source,
                (in, out) -> out.set(in.getRealFloat() != 0 ? 1 : 0),
                new IntType());

        // Output label image: same shape, IntType so we can address > 65k components
        final long[] dims = new long[binary.numDimensions()];
        binary.dimensions(dims);
        final Img<IntType> labelImg = ArrayImgs.ints(dims);
        final ImgLabeling<Integer, IntType> labeling = new ImgLabeling<>(labelImg);

        final ConnectedComponents.StructuringElement se = fullyConnected
                ? ConnectedComponents.StructuringElement.EIGHT_CONNECTED
                : ConnectedComponents.StructuringElement.FOUR_CONNECTED;
        ConnectedComponents.labelAllConnectedComponents(binary, labeling, sequentialLabelGenerator(), se);

        // Wrap as a native ImagePlus and detach from the lazy view via duplicate()
        final String title = mask.getTitle() + " [CCA]";
        final ImagePlus wrapped = ImageJFunctions.wrap(labelImg, title);
        final ImagePlus result = wrapped.duplicate();
        result.setTitle(title);
        result.setCalibration(mask.getCalibration().copy());
        return result;
    }

    /**
     * Yields 1, 2, 3, ... for the {@link ConnectedComponents} label generator.
     * One instance per CCA invocation: the iterator is not thread-safe and
     * is consumed exactly once.
     */
    private static Iterator<Integer> sequentialLabelGenerator() {
        return new Iterator<>() {
            private int next = 1;

            @Override
            public boolean hasNext() {
                return next > 0; // false on overflow; ConnectedComponents stops when input is exhausted
            }

            @Override
            public Integer next() {
                return next++;
            }
        };
    }
}
