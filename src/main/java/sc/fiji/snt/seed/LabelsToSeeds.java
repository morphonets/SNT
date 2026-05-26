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
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.util.ImgUtils;

import java.util.ArrayList;
import java.util.HashMap;
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
}
