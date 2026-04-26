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

package sc.fiji.snt.tracing;

import ij.process.FloatProcessor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Static utilities for computing cross-sectional planes perpendicular to a path
 * tangent. Extracted from {@link sc.fiji.snt.PathFitter} for reuse by other
 * path-analysis tools (e.g., varicosity detection, torus mask generation).
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public final class CrossSectionUtils {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private CrossSectionUtils() {
        // static utility class
    }

    /**
     * Computes an orthonormal basis for the plane perpendicular to the given
     * normal (tangent) vector.
     *
     * @param nx X component of the normal vector
     * @param ny Y component of the normal vector
     * @param nz Z component of the normal vector
     * @return {@code double[2][3]}: {@code [0]} is the first basis vector,
     * {@code [1]} is the second basis vector
     */
    public static double[][] computeTangentPlaneBasis(final double nx, final double ny, final double nz) {
        final double epsilon = 1e-6;

        // First basis vector: cross product with (0,0,1) or (0,1,0)
        double ax, ay, az;
        if (Math.abs(nx) < epsilon && Math.abs(ny) < epsilon) {
            // Normal is parallel to Z, use (0,1,0) instead
            ax = nz;
            ay = 0;
            az = -nx;
        } else {
            // Cross normal with (0,0,1)
            ax = -ny;
            ay = nx;
            az = 0;
        }

        // Second basis vector: cross product of a with normal
        double bx = ay * nz - az * ny;
        double by = az * nx - ax * nz;
        double bz = ax * ny - ay * nx;

        // Normalize both vectors
        final double a_size = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= a_size;
        ay /= a_size;
        az /= a_size;

        final double b_size = Math.sqrt(bx * bx + by * by + bz * bz);
        bx /= b_size;
        by /= b_size;
        bz /= b_size;

        return new double[][]{
                {ax, ay, az},
                {bx, by, bz}
        };
    }

    /**
     * Computes the effective physical scale along an arbitrary direction vector
     * given anisotropic voxel spacing.
     *
     * @param vx       X component of direction vector
     * @param vy       Y component of direction vector
     * @param vz       Z component of direction vector
     * @param xSpacing voxel width
     * @param ySpacing voxel height
     * @param zSpacing voxel depth
     * @return effective scale (physical distance per unit in the given direction)
     */
    public static double computeScaleAlongVector(final double vx, final double vy, final double vz,
                                                 final double xSpacing, final double ySpacing, final double zSpacing) {
        return Math.sqrt(
                (vx * xSpacing) * (vx * xSpacing) +
                        (vy * ySpacing) * (vy * ySpacing) +
                        (vz * zSpacing) * (vz * zSpacing)
        );
    }

    /**
     * Samples a square cross-section of an image on the plane perpendicular to a
     * path tangent at a given node position. The section is centered on the node
     * and oriented according to the precomputed basis vectors.
     *
     * @param side       grid size in pixels (width = height = side)
     * @param scaleA     physical spacing along first basis vector
     * @param scaleB     physical spacing along second basis vector
     * @param ox         node X position (world/calibrated coordinates)
     * @param oy         node Y position (world/calibrated coordinates)
     * @param oz         node Z position (world/calibrated coordinates)
     * @param aBasis     first basis vector {@code [ax, ay, az]}
     * @param bBasis     second basis vector {@code [bx, by, bz]}
     * @param xSpacing   voxel width (for converting world→pixel)
     * @param ySpacing   voxel height
     * @param zSpacing   voxel depth
     * @param realAccess interpolating accessor into the source image
     * @return the sampled cross-section as a {@link FloatProcessor}
     */
    public static FloatProcessor sampleCrossSection(
            final int side,
            final double scaleA,
            final double scaleB,
            final double ox, final double oy, final double oz,
            final double[] aBasis,
            final double[] bBasis,
            final double xSpacing, final double ySpacing, final double zSpacing,
            final RealRandomAccess<FloatType> realAccess) {

        final float[] pixels = new float[side * side];
        final boolean is3d = zSpacing > 0;
        final int nDim = is3d ? 3 : 2;
        final double[] position = new double[nDim];

        // Scale each basis by its respective physical spacing
        final double ax_s = aBasis[0] * scaleA;
        final double ay_s = aBasis[1] * scaleA;
        final double az_s = aBasis[2] * scaleA;

        final double bx_s = bBasis[0] * scaleB;
        final double by_s = bBasis[1] * scaleB;
        final double bz_s = bBasis[2] * scaleB;

        final double midside = (side - 1) / 2.0;

        for (int grid_i = 0; grid_i < side; ++grid_i) {
            final double gi = midside - grid_i;
            for (int grid_j = 0; grid_j < side; ++grid_j) {
                final double gj = midside - grid_j;

                // Convert to pixel coordinates (divide by voxel spacing)
                position[0] = (ox + gi * ax_s + gj * bx_s) / xSpacing;
                position[1] = (oy + gi * ay_s + gj * by_s) / ySpacing;
                if (nDim > 2)
                    position[2] = (oz + gi * az_s + gj * bz_s) / zSpacing;

                pixels[grid_j * side + grid_i] = realAccess.setPositionAndGet(position).getRealFloat();
            }
        }

        return new FloatProcessor(side, side, pixels);
    }

    /**
     * Applies an annular mask to a cross-section {@link FloatProcessor}. Pixels
     * outside the annulus (closer than {@code innerRadius} or farther than
     * {@code outerRadius} from center) are set to {@link Float#NaN}.
     *
     * @param fp          the cross-section image to mask (modified in place)
     * @param innerRadius inner radius in grid pixels (0 for solid disk)
     * @param outerRadius outer radius in grid pixels
     */
    public static void applyAnnularMask(final FloatProcessor fp,
                                        final double innerRadius, final double outerRadius) {
        final int side = fp.getWidth();
        final double center = (side - 1) / 2.0;
        final double innerR2 = innerRadius * innerRadius;
        final double outerR2 = outerRadius * outerRadius;
        final float[] pixels = (float[]) fp.getPixels();

        for (int y = 0; y < side; y++) {
            final double dy = y - center;
            for (int x = 0; x < side; x++) {
                final double dx = x - center;
                final double dist2 = dx * dx + dy * dy;
                if (dist2 < innerR2 || dist2 > outerR2) {
                    pixels[y * side + x] = Float.NaN;
                }
            }
        }
    }

    /**
     * Back-projects a 2D cross-section grid coordinate to 3D world coordinates.
     *
     * @param gridX    grid X coordinate (pixel in the cross-section)
     * @param gridY    grid Y coordinate (pixel in the cross-section)
     * @param side     cross-section grid size
     * @param scaleIso isotropic physical scale of the grid
     * @param ox       node X position (world coordinates)
     * @param oy       node Y position (world coordinates)
     * @param oz       node Z position (world coordinates)
     * @param aBasis   first basis vector {@code [ax, ay, az]}
     * @param bBasis   second basis vector {@code [bx, by, bz]}
     * @return {@code double[3]} world coordinates {@code {x, y, z}}
     */
    public static double[] backProject(
            final int gridX, final int gridY, final int side,
            final double scaleIso,
            final double ox, final double oy, final double oz,
            final double[] aBasis, final double[] bBasis) {

        final double midside = (side - 1) / 2.0;
        final double gi = midside - gridX;
        final double gj = midside - gridY;

        return new double[]{
                ox + (gi * aBasis[0] + gj * bBasis[0]) * scaleIso,
                oy + (gi * aBasis[1] + gj * bBasis[1]) * scaleIso,
                oz + (gi * aBasis[2] + gj * bBasis[2]) * scaleIso
        };
    }

    /**
     * Computes the isotropic scale for a cross-section plane given the tangent
     * plane basis vectors and voxel spacing.
     *
     * @param aBasis   first basis vector
     * @param bBasis   second basis vector
     * @param xSpacing voxel width
     * @param ySpacing voxel height
     * @param zSpacing voxel depth
     * @return the isotropic scale (geometric mean of scales along both basis vectors)
     */
    public static double computeIsotropicScale(final double[] aBasis, final double[] bBasis,
                                               final double xSpacing, final double ySpacing, final double zSpacing) {
        final double scaleA = computeScaleAlongVector(aBasis[0], aBasis[1], aBasis[2], xSpacing, ySpacing, zSpacing);
        final double scaleB = computeScaleAlongVector(bBasis[0], bBasis[1], bBasis[2], xSpacing, ySpacing, zSpacing);
        return Math.sqrt(scaleA * scaleB);
    }

    /**
     * Computes the grid size needed to cover a given physical radius at the
     * specified scale, capped to a maximum.
     *
     * @param physicalRadius radius in calibrated units
     * @param scale          physical units per grid pixel
     * @param maxSide        maximum allowed grid size
     * @return the grid size (always odd, so center pixel is well-defined)
     */
    public static int computeGridSize(final double physicalRadius, final double scale, final int maxSide) {
        int side = (int) Math.ceil(2 * physicalRadius / scale) + 1;
        if (side % 2 == 0) side++; // ensure odd
        return Math.clamp(side, 5, maxSide);
    }

    /**
     * Samples a 1D intensity profile along a direction vector, centered on a
     * given world position. Intended for 2D images where the cross-section
     * perpendicular to a path tangent is a line, not a plane.
     *
     * @param nSamples   number of samples (should be odd so center is well-defined)
     * @param scale      physical spacing per sample along the direction
     * @param ox         center X position (world/calibrated coordinates)
     * @param oy         center Y position (world/calibrated coordinates)
     * @param direction  direction vector {@code [dx, dy]} (unit length)
     * @param xSpacing   voxel width (for converting world→pixel)
     * @param ySpacing   voxel height
     * @param realAccess interpolating accessor into the source image (2D)
     * @return the sampled profile as a float array
     */
    public static float[] sampleProfile(
            final int nSamples,
            final double scale,
            final double ox, final double oy,
            final double[] direction,
            final double xSpacing, final double ySpacing,
            final RealRandomAccess<FloatType> realAccess) {

        final float[] profile = new float[nSamples];
        final double mid = (nSamples - 1) / 2.0;
        final double dx_s = direction[0] * scale;
        final double dy_s = direction[1] * scale;
        final double[] position = new double[2];

        for (int i = 0; i < nSamples; i++) {
            final double g = mid - i;
            position[0] = (ox + g * dx_s) / xSpacing;
            position[1] = (oy + g * dy_s) / ySpacing;
            profile[i] = realAccess.setPositionAndGet(position).getRealFloat();
        }
        return profile;
    }

    /**
     * Applies an annular mask to a 1D profile. Samples closer than
     * {@code innerRadius} or farther than {@code outerRadius} from the center
     * are set to {@link Float#NaN}.
     *
     * @param profile     the profile array (modified in place)
     * @param innerRadius inner radius in sample units (0 for no inner exclusion)
     * @param outerRadius outer radius in sample units
     */
    public static void applyAnnularMask1D(final float[] profile,
                                          final double innerRadius,
                                          final double outerRadius) {
        final double mid = (profile.length - 1) / 2.0;
        for (int i = 0; i < profile.length; i++) {
            final double d = Math.abs(i - mid);
            if (d < innerRadius || d > outerRadius) {
                profile[i] = Float.NaN;
            }
        }
    }

    /**
     * Finds local maxima in a 1D profile using prominence-based filtering.
     * A sample is a local maximum if it is strictly greater than both its
     * neighbors (ignoring NaN) and its prominence exceeds the threshold.
     * Prominence is defined as the peak value minus the highest saddle point
     * connecting the peak to any higher peak (or the profile boundary).
     *
     * @param profile    the 1D intensity profile (may contain NaN for masked regions)
     * @param prominence minimum prominence threshold
     * @return array of indices into {@code profile} where maxima were found
     */
    public static int[] findMaxima1D(final float[] profile, final double prominence) {
        final java.util.List<Integer> maxima = new java.util.ArrayList<>();
        final int n = profile.length;

        for (int i = 1; i < n - 1; i++) {
            if (Float.isNaN(profile[i])) continue;

            // Find valid left and right neighbors (skip NaN)
            int li = i - 1;
            while (li >= 0 && Float.isNaN(profile[li])) li--;
            int ri = i + 1;
            while (ri < n && Float.isNaN(profile[ri])) ri++;

            if (li < 0 || ri >= n) continue;
            if (profile[i] <= profile[li] || profile[i] <= profile[ri]) continue;

            // Compute prominence: walk each direction to find the minimum
            // before reaching a higher peak or the edge
            float leftMin = profile[i];
            for (int j = i - 1; j >= 0; j--) {
                if (Float.isNaN(profile[j])) continue;
                if (profile[j] > profile[i]) break;
                leftMin = Math.min(leftMin, profile[j]);
            }
            float rightMin = profile[i];
            for (int j = i + 1; j < n; j++) {
                if (Float.isNaN(profile[j])) continue;
                if (profile[j] > profile[i]) break;
                rightMin = Math.min(rightMin, profile[j]);
            }
            // Prominence = peak - highest saddle (i.e., the shallower side)
            final float prom = profile[i] - Math.max(leftMin, rightMin);
            if (prom >= prominence) {
                maxima.add(i);
            }
        }

        return maxima.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Paints an annular cross-section into a 3D output image. For each pixel in
     * the annulus (between {@code innerRadius} and {@code outerRadius} in grid
     * space), the corresponding voxel in the output image is set to
     * {@code fillValue}. Existing non-zero voxels are preserved (logical OR).
     *
     * @param <T>         pixel type (must support setReal)
     * @param output      the 3D output image (modified in place); dimensions
     *                    correspond to pixel coordinates
     * @param side        cross-section grid size in pixels
     * @param scaleIso    isotropic physical scale of the grid
     * @param ox          node X position (world/calibrated coordinates)
     * @param oy          node Y position (world/calibrated coordinates)
     * @param oz          node Z position (world/calibrated coordinates)
     * @param aBasis      first tangent plane basis vector
     * @param bBasis      second tangent plane basis vector
     * @param xSpacing    voxel width
     * @param ySpacing    voxel height
     * @param zSpacing    voxel depth
     * @param innerRadius inner radius in grid pixels (0 for solid disk)
     * @param outerRadius outer radius in grid pixels
     * @param fillValue   the value to write into annulus voxels
     */
    public static <T extends RealType<T>> void paintAnnulus(
            final RandomAccessibleInterval<T> output,
            final int side,
            final double scaleIso,
            final double ox, final double oy, final double oz,
            final double[] aBasis, final double[] bBasis,
            final double xSpacing, final double ySpacing, final double zSpacing,
            final double innerRadius, final double outerRadius,
            final double fillValue) {

        final long maxX = output.max(0);
        final long maxY = output.max(1);
        final boolean is2D = output.dimension(2) <= 1;
        final long maxZ = is2D ? 0 : output.max(2);

        final RandomAccess<T> ra = output.randomAccess();

        // Convert grid-pixel radii back to world units
        final double innerRWorld = innerRadius * scaleIso;
        final double outerRWorld = outerRadius * scaleIso;
        final double innerRW2 = innerRWorld * innerRWorld;
        final double outerRW2 = outerRWorld * outerRWorld;

        if (is2D) {
            // 2D: paint along the perpendicular line (1D cross-section).
            // Walk along aBasis from -outerR to +outerR, skipping the
            // inner hollow region. Step at half-pixel to avoid gaps.
            final double stepWorld = Math.min(xSpacing, ySpacing) * 0.5;
            for (double t = -outerRWorld; t <= outerRWorld; t += stepWorld) {
                if (t > -innerRWorld && t < innerRWorld) continue;
                final double wx = ox + t * aBasis[0];
                final double wy = oy + t * aBasis[1];
                final long px = Math.round(wx / xSpacing);
                final long py = Math.round(wy / ySpacing);
                if (px < 0 || px > maxX || py < 0 || py > maxY) continue;
                ra.setPosition(px, 0);
                ra.setPosition(py, 1);
                ra.get().setReal(fillValue);
            }
        } else {
            // 3D: forward mapping from cross-section grid
            final double center = (side - 1) / 2.0;
            final double innerR2 = innerRadius * innerRadius;
            final double outerR2 = outerRadius * outerRadius;

            for (int gy = 0; gy < side; gy++) {
            final double dy = gy - center;
            for (int gx = 0; gx < side; gx++) {
                final double dx = gx - center;
                final double dist2 = dx * dx + dy * dy;

                // Skip pixels outside the annulus
                if (dist2 < innerR2 || dist2 > outerR2) continue;

                // Back-project to world coordinates, then to pixel coordinates
                final double[] world = backProject(gx, gy, side, scaleIso, ox, oy, oz, aBasis, bBasis);
                final long px = Math.round(world[0] / xSpacing);
                final long py = Math.round(world[1] / ySpacing);
                final long pz = (zSpacing > 0) ? Math.round(world[2] / zSpacing) : 0;

                // Bounds check
                if (px < 0 || px > maxX || py < 0 || py > maxY || pz < 0 || pz > maxZ)
                    continue;

                ra.setPosition(px, 0);
                    ra.setPosition(py, 1);
                    ra.setPosition(pz, 2);
                    ra.get().setReal(fillValue);
                }
            }
        }
    }
}
