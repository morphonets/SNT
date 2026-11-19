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

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Coarse map of where a volume may contain structures, used to skip the blocks of a large volume that are empty
 * (see {@link TipFinder#setBlockFilter(java.util.function.Predicate)}). It is built from a low resolution version of
 * the volume: a voxel is active if it is brighter than the background by a number of robust standard deviations
 * (median and MAD of the values). Queries are made in the voxel grid of the full resolution volume, and are
 * conservative: the neighbors of active voxels count as active, as do blocks that are not covered by the coarse
 * image
 *
 * @author Tiago Ferreira
 */
public final class OccupancyMask {

    private final BitSet active;
    private final long[] dims;
    private final double[] coarseSpacing;
    private final double[] coarseOrigin;
    private final double[] fineSpacing;
    private final double[] fineOrigin;
    private final double threshold;
    private final double activeFraction;

    private OccupancyMask(final BitSet active, final long[] dims, final double[] coarseSpacing,
                          final double[] coarseOrigin, final double[] fineSpacing, final double[] fineOrigin,
                          final double threshold, final double activeFraction) {
        this.active = active;
        this.dims = dims;
        this.coarseSpacing = coarseSpacing;
        this.coarseOrigin = coarseOrigin;
        this.fineSpacing = fineSpacing;
        this.fineOrigin = fineOrigin;
        this.threshold = threshold;
        this.activeFraction = activeFraction;
    }

    /**
     * Builds a mask
     *
     * @param coarse        the low resolution volume (3D). It is read into memory
     * @param coarseSpacing its voxel size (calibrated units)
     * @param coarseOrigin  the position of its first voxel in the same (world) coordinates as {@code fineOrigin}
     * @param fineSpacing   the voxel size of the full resolution volume
     * @param fineOrigin    the position of the first voxel of the full resolution volume (world coordinates)
     * @param madFactor     the number of robust standard deviations above the median that make a voxel active
     * @return the mask
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static OccupancyMask build(final RandomAccessibleInterval<? extends RealType<?>> coarse,
                                      final double[] coarseSpacing, final double[] coarseOrigin,
                                      final double[] fineSpacing, final double[] fineOrigin,
                                      final double madFactor) {
        if (coarse.numDimensions() != 3) throw new IllegalArgumentException("A 3D image is required");
        final long[] dims = coarse.dimensionsAsLongArray();
        final long total = dims[0] * dims[1] * dims[2];
        if (total > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Image is too large: " + total);
        final float[] values = new float[(int) total];
        final RandomAccessibleInterval<RealType<?>> zeroMin = (RandomAccessibleInterval) Views.zeroMin(coarse);
        LoopBuilder.setImages(zeroMin, ArrayImgs.floats(values, dims))
                .multiThreaded().forEachPixel((in, out) -> out.setReal(in.getRealDouble()));

        // Robust background statistics from a strided sample
        final int stride = (int) Math.max(1, total / 2_000_000);
        final float[] sample = new float[(int) ((total + stride - 1) / stride)];
        for (int i = 0, k = 0; i < total; i += stride) sample[k++] = values[i];
        Arrays.sort(sample);
        final double median = sample[sample.length / 2];
        final float[] deviations = new float[sample.length];
        for (int i = 0; i < sample.length; i++) deviations[i] = (float) Math.abs(sample[i] - median);
        Arrays.sort(deviations);
        final double sigma = 1.4826 * deviations[deviations.length / 2];
        final double threshold = median + madFactor * Math.max(sigma, 1e-9);

        final BitSet bits = new BitSet((int) total);
        for (int i = 0; i < total; i++) {
            if (values[i] > threshold) bits.set(i);
        }
        return new OccupancyMask(bits, dims, coarseSpacing.clone(), coarseOrigin.clone(), fineSpacing.clone(),
                fineOrigin.clone(), threshold, bits.cardinality() / (double) total);
    }

    /**
     * Tests a box of the full resolution volume
     *
     * @param min the first voxel of the box
     * @param max the last voxel of the box (inclusive)
     * @return false only if the box and its surroundings are all below the threshold
     */
    public boolean isActive(final long[] min, final long[] max) {
        final long[] c0 = new long[3];
        final long[] c1 = new long[3];
        for (int d = 0; d < 3; d++) {
            final double w0 = fineOrigin[d] + (min[d] - 0.5) * fineSpacing[d];
            final double w1 = fineOrigin[d] + (max[d] + 0.5) * fineSpacing[d];
            // Voxel v of the coarse image is centered at origin + v * spacing. One voxel of margin
            c0[d] = Math.max(0, (long) Math.floor((w0 - coarseOrigin[d]) / coarseSpacing[d] + 0.5) - 1);
            c1[d] = Math.min(dims[d] - 1, (long) Math.floor((w1 - coarseOrigin[d]) / coarseSpacing[d] + 0.5) + 1);
            if (c1[d] < c0[d]) return true; // Not covered: keep
        }
        for (long z = c0[2]; z <= c1[2]; z++)
            for (long y = c0[1]; y <= c1[1]; y++) {
                final long row = dims[0] * (y + dims[1] * z);
                final int from = (int) (row + c0[0]);
                final int next = active.nextSetBit(from);
                if (next >= 0 && next <= row + c1[0]) return true;
            }
        return false;
    }

    /** @return the value above which a voxel of the coarse image is active */
    public double getThreshold() {
        return threshold;
    }

    /** @return the fraction of voxels of the coarse image that are active */
    public double getActiveFraction() {
        return activeFraction;
    }
}
