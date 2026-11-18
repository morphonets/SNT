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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.util.PointInImage;

/**
 * Bridges SNT paths and {@link TipDetectionConfig}: converts example paths to {@link TipDetectionConfig.Example}
 * (radii, node voxels and tip voxels in the grid of an image).
 *
 * @author Tiago Ferreira
 */
public final class TipExamples {

    private TipExamples() {
    }

    /**
     * Converts example paths to examples. Paths without radii are fitted (non-destructively) to obtain them. The tips
     * are the ends of the paths, except for those that are branching points of other paths. The start of a path is
     * never a tip: it is either a branching point or the origin of the structure
     *
     * @param paths       the example paths (traced towards the tips of the structures)
     * @param source      the image the paths refer to: needed to fit radii
     * @param spacing     voxel size (x, y, z) in calibrated units
     * @param voxelOffset the offset [x, y, z], in voxels, between the coordinate frame of the paths and the grid of the
     *                    image: {@code voxel = node / spacing + voxelOffset} (null: none)
     * @return the examples, in the grid of the image
     * @throws IllegalArgumentException if paths is null or empty
     */
    public static TipDetectionConfig.Example fromPaths(final List<Path> paths,
                                                       final RandomAccessibleInterval<? extends RealType<?>> source,
                                                       final double[] spacing, final double[] voxelOffset) {
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("At least one path is required");
        final double[] offset = AutoTraceConfig.normalizedOffset(voxelOffset);

        // Radii (fitted paths are copies: only their radii are used)
        final List<Double> radiiList = AutoTraceConfig.collectRadii(
                AutoTraceConfig.ensureRadii(paths, source, spacing, offset));
        final double[] radii = radiiList.stream().mapToDouble(Double::doubleValue).toArray();

        final List<long[]> nodes = new ArrayList<>();
        final List<long[]> tips = new ArrayList<>();
        final double tolerance = 1.5 * Math.max(spacing[0], Math.max(spacing[1], spacing[2]));
        for (final Path p : paths) {
            for (int i = 0; i < p.size(); i++) nodes.add(toVoxel(p.getNode(i), spacing, offset));
            if (p.size() < 2) continue;
            final PointInImage end = p.getNode(p.size() - 1);
            if (!isBranchPoint(p, end, tolerance)) tips.add(toVoxel(end, spacing, offset));
        }
        return new TipDetectionConfig.Example(radii, nodes.toArray(new long[0][]), tips.toArray(new long[0][]));
    }

    /**
     * Gets the scales of the score map that suit the radii of the examples
     *
     * @param radii the radii of the examples (see {@link TipDetectionConfig.Example})
     * @return the scales (calibrated units), empty if there are no radii
     */
    public static double[] scales(final double[] radii) {
        if (radii == null || radii.length == 0) return new double[0];
        final List<Double> sorted = new ArrayList<>(radii.length);
        for (final double r : radii) sorted.add(r);
        Collections.sort(sorted);
        return AutoTraceConfig.deriveScales(sorted);
    }

    /**
     * Gets the bounding box of the nodes of the paths in the grid of an image
     *
     * @param paths       the paths
     * @param spacing     voxel size (x, y, z) in calibrated units
     * @param voxelOffset see {@link #fromPaths}
     * @return {min, max}: the lowest and highest voxel {x, y, z} spanned by the nodes. Null if there are no nodes
     */
    public static long[][] voxelBounds(final List<Path> paths, final double[] spacing, final double[] voxelOffset) {
        final double[] offset = AutoTraceConfig.normalizedOffset(voxelOffset);
        final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] max = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        boolean any = false;
        for (final Path p : paths) {
            for (int i = 0; i < p.size(); i++) {
                final long[] v = toVoxel(p.getNode(i), spacing, offset);
                for (int d = 0; d < 3; d++) {
                    min[d] = Math.min(min[d], v[d]);
                    max[d] = Math.max(max[d], v[d]);
                }
                any = true;
            }
        }
        return any ? new long[][]{min, max} : null;
    }

    private static boolean isBranchPoint(final Path p, final PointInImage node, final double tolerance) {
        final Set<PointInImage> branchPoints = p.getBranchPoints();
        for (final PointInImage bp : branchPoints) {
            if (bp.distanceTo(node) <= tolerance) return true;
        }
        return false;
    }

    private static long[] toVoxel(final PointInImage node, final double[] spacing, final double[] offset) {
        return new long[]{Math.round(node.x / spacing[0] + offset[0]), Math.round(node.y / spacing[1] + offset[1]),
                Math.round(node.z / spacing[2] + offset[2])};
    }
}
