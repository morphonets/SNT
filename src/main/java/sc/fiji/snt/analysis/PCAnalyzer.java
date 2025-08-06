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

package sc.fiji.snt.analysis;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.Covariance;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Utility class for performing Principal Component Analysis (PCA) on various
 * SNT data structures including Trees, Paths, and collections of SNTPoints.
 * <p>
 * This class provides methods to compute the principal axes of 3D point data,
 * which represent the directions of maximum variance in the data. This is useful
 * for analyzing the overall orientation and shape characteristics of neuronal
 * structures, meshes, and other 3D geometries.
 * </p>
 *
 * @author SNT Team
 */
public class PCAnalyzer {

    /**
     * Computes the principal axes for a Tree structure.
     *
     * @param tree the Tree to analyze
     * @return array of three PrincipalAxis objects ordered by decreasing variance
     * (primary, secondary, tertiary), or null if computation fails
     */
    public static PrincipalAxis[] getPrincipalAxes(final Tree tree) {
        return getPrincipalAxes(tree, false);
    }

    /**
     * Computes the principal axes for a Tree structure with option to exclude root.
     *
     * @param tree        the Tree to analyze
     * @param excludeRoot if true, excludes the root node from the analysis
     * @return array of three PrincipalAxis objects ordered by decreasing variance
     * (primary, secondary, tertiary), or null if computation fails
     */
    public static PrincipalAxis[] getPrincipalAxes(final Tree tree, final boolean excludeRoot) {
        if (tree == null) return null;
        final List<SNTPoint> points = new ArrayList<>();
        final SNTPoint root = excludeRoot ? tree.getRoot() : null;

        for (final Path path : tree.list()) {
            for (final SNTPoint point : path.getNodes()) {
                // Skip root if excludeRoot is true
                if (excludeRoot && point.equals(root)) {
                    continue;
                }
                points.add(point);
            }
        }
        return getPrincipalAxes(points);
    }

    /**
     * Computes the principal axes for a Path structure.
     *
     * @param path the Path to analyze
     * @return array of three PrincipalAxis objects ordered by decreasing variance
     * (primary, secondary, tertiary), or null if computation fails
     */
    public static PrincipalAxis[] getPrincipalAxes(final Path path) {
        if (path == null) return null;
        return getPrincipalAxes(path.getNodes());
    }

    /**
     * Computes the principal axes for a collection of SNTPoints.
     *
     * @param points the collection of points to analyze
     * @return array of three PrincipalAxis objects ordered by decreasing variance
     * (primary, secondary, tertiary), or null if computation fails
     */
    public static PrincipalAxis[] getPrincipalAxes(final Collection<? extends SNTPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        // Convert points to matrix for PCA
        final List<? extends SNTPoint> pointList = new ArrayList<>(points);
        final int n = pointList.size();

        // Need at least 3 points for meaningful PCA in 3D
        if (n < 3) {
            return null;
        }

        final double[][] data = new double[n][3];

        for (int i = 0; i < n; i++) {
            final SNTPoint point = pointList.get(i);
            data[i][0] = point.getX();
            data[i][1] = point.getY();
            data[i][2] = point.getZ();
        }

        return computePrincipalAxes(data);
    }

    /**
     * Core PCA computation method that works with raw coordinate data.
     *
     * @param data n×3 matrix where each row represents a 3D point (x, y, z)
     * @return array of three PrincipalAxis objects ordered by decreasing variance
     * (primary, secondary, tertiary), or null if computation fails
     */
    private static PrincipalAxis[] computePrincipalAxes(final double[][] data) {
        try {
            // Create matrix and compute covariance
            final RealMatrix matrix = new Array2DRowRealMatrix(data);
            final Covariance covariance = new Covariance(matrix);
            final RealMatrix covMatrix = covariance.getCovarianceMatrix();

            // Compute eigenvalues and eigenvectors
            final EigenDecomposition eigenDecomp = new EigenDecomposition(covMatrix);
            final RealMatrix eigenVectors = eigenDecomp.getV();
            final double[] eigenValues = eigenDecomp.getRealEigenvalues();

            // EigenDecomposition returns eigenvalues in descending order by default
            final PrincipalAxis[] principalAxes = new PrincipalAxis[3];
            for (int i = 0; i < 3; i++) {
                principalAxes[i] = new PrincipalAxis(
                        eigenVectors.getEntry(0, i),
                        eigenVectors.getEntry(1, i),
                        eigenVectors.getEntry(2, i),
                        eigenValues[i]
                );
            }

            return principalAxes;

        } catch (final Exception e) {
            // Handle any numerical issues with PCA computation
            return null;
        }
    }

    /**
     * Computes the variance percentages for an array of principal axes.
     * This is a convenience method that returns the percentage of total variance
     * explained by each principal axis.
     *
     * @param axes the array of three PrincipalAxis objects (primary, secondary, tertiary)
     * @return array of three percentages (primary, secondary, tertiary) that sum to 100%,
     * or null if axes is null
     */
    public static double[] getVariancePercentages(final PrincipalAxis[] axes) {
        return PrincipalAxis.getVariancePercentages(axes);
    }

    /**
     * Convenience method to orient existing principal axes toward a tree's tips centroid.
     * For several topologies, this orients the primary axis is so it aligns with the
     * general growth direction of the arbor.
     *
     * @param axes the principal axes to orient
     * @param tree the tree to get tips centroid from (orientation reference)
     * @return oriented principal axes
     */
    public static PrincipalAxis[] orientTowardTips(final PrincipalAxis[] axes, final Tree tree) {
        if (axes == null || tree == null) return axes;

        // Compute tips centroid direction from root
        final SNTPoint root = tree.getRoot();
        final java.util.Set<PointInImage> tips = new TreeStatistics(tree).getTips();
        if (tips.isEmpty()) return axes;

        final SNTPoint tipsCentroid = SNTPoint.average(tips);
        final double[] tipsDirection = {
                tipsCentroid.getX() - root.getX(),
                tipsCentroid.getY() - root.getY(),
                tipsCentroid.getZ() - root.getZ()
        };

        return orientTowardDirection(axes, tipsDirection);
    }

    /**
     * Orients principal axes so the primary axis points toward a reference direction, i.e.,
     * the primary axis is oriented to minimize the angle with the reference direction.
     * If the primary axis points away from the reference (dot product &lt; 0), it's flipped.\
     *
     * @param axes               the principal axes to orient
     * @param referenceDirection the reference direction vector [x, y, z]
     * @return oriented principal axes
     */
    public static PrincipalAxis[] orientTowardDirection(final PrincipalAxis[] axes, final double[] referenceDirection) {
        if (axes == null || referenceDirection == null) return axes;

        // Normalize reference direction
        final double refMag = Math.sqrt(referenceDirection[0] * referenceDirection[0] +
                referenceDirection[1] * referenceDirection[1] +
                referenceDirection[2] * referenceDirection[2]);
        if (refMag == 0) return axes;

        final double[] normRef = {
                referenceDirection[0] / refMag,
                referenceDirection[1] / refMag,
                referenceDirection[2] / refMag
        };

        // Create oriented axes array
        final PrincipalAxis[] orientedAxes = new PrincipalAxis[axes.length];

        for (int i = 0; i < axes.length; i++) {
            final PrincipalAxis axis = axes[i];

            // Check orientation using dot product
            final double dotProduct = axis.x * normRef[0] +
                    axis.y * normRef[1] +
                    axis.z * normRef[2];

            // Flip axis if pointing away from reference (only for primary axis by default)
            if (i == 0 && dotProduct < 0) {
                orientedAxes[i] = new PrincipalAxis(-axis.x, -axis.y, -axis.z, axis.eigenvalue);
            } else {
                orientedAxes[i] = new PrincipalAxis(axis.x, axis.y, axis.z, axis.eigenvalue);
            }
        }

        return orientedAxes;
    }

    /**
     * Represents a principal axis containing the direction vector and associated
     * variance (eigenvalue) from Principal Component Analysis.
     * <p>
     * Each principal axis represents a direction of variance in the analyzed data,
     * with the primary axis having the highest variance, secondary axis the second
     * highest, and tertiary axis the lowest.
     * </p>
     */
    public static class PrincipalAxis {

        /**
         * The X component of the axis direction
         */
        public final double x;
        /**
         * The Y component of the axis direction
         */
        public final double y;
        /**
         * The Z component of the axis direction
         */
        public final double z;
        /**
         * The eigenvalue associated with this axis
         */
        public final double eigenvalue;

        private PrincipalAxis(double x, double y, double z, double eigenvalue) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.eigenvalue = eigenvalue;
        }

        /**
         * Computes the variance percentages for an array of principal axes.
         * This is a convenience method that returns the percentage of total variance
         * explained by each principal axis, which is useful for understanding the
         * overall shape variation along each axis.
         *
         * @param axes the array of three PrincipalAxis objects (primary, secondary, tertiary)
         * @return array of three percentages (primary, secondary, tertiary) that sum to 100%,
         * or null if axes is null
         */
        public static double[] getVariancePercentages(final PrincipalAxis[] axes) {
            if (axes == null) return null;

            // Calculate total variance
            double totalVariance = 0.0;
            for (final PrincipalAxis axis : axes) {
                totalVariance += axis.getVariance();
            }

            // Calculate percentages
            final double[] percentages = new double[3];
            for (int i = 0; i < axes.length; i++) {
                percentages[i] = axes[i].getVariancePercentage(totalVariance);
            }
            return percentages;
        }

        // -- getters for scripting consistency

        /**
         * @return the X component of the axis direction
         */
        public double getX() {
            return x;
        }

        /**
         * @return the Y component of the axis direction
         */
        public double getY() {
            return y;
        }

        /**
         * @return the Z component of the axis direction
         */
        public double getZ() {
            return z;
        }

        /**
         * @return the eigenvalue (variance) associated with this axis
         */
        public double getEigenvalue() {
            return eigenvalue;
        }

        /**
         * @return the absolute variance along this axis
         */
        public double getVariance() {
            return Math.abs(eigenvalue);
        }

        /**
         * Computes the percentage of total variance explained by this principal axis.
         *
         * @param totalVariance the sum of all eigenvalues (total variance)
         * @return the percentage of total variance explained by this axis (0-100%)
         */
        public double getVariancePercentage(final double totalVariance) {
            if (totalVariance <= 0) return 0.0;
            return (getVariance() / totalVariance) * 100.0;
        }

        /**
         * Computes the acute angle between this principal axis and a direction vector.
         *
         * @param directionComponents the 3-element array of the (X,Y,Z) components of the direction vector
         * @return the acute angle in degrees (0-90°), or NaN if the direction vector has zero magnitude
         */
        @SuppressWarnings("unused")
        public double getAngleWith(final double[] directionComponents) {
            return getAngleWith(directionComponents[0], directionComponents[1], directionComponents[2]);
        }

        /**
         * Computes the acute angle between this principal axis and a direction vector.
         *
         * @param dirX the X component of the direction vector
         * @param dirY the Y component of the direction vector
         * @param dirZ the Z component of the direction vector
         * @return the acute angle in degrees (0-90°), or NaN if the direction vector has zero magnitude
         */
        public double getAngleWith(final double dirX, final double dirY, final double dirZ) {
            // Normalize direction vector
            final double dirMag = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (dirMag == 0) return Double.NaN;

            final double normDirX = dirX / dirMag;
            final double normDirY = dirY / dirMag;
            final double normDirZ = dirZ / dirMag;

            // Compute dot product (principal axis is already normalized)
            double dotProduct = normDirX * x + normDirY * y + normDirZ * z;

            // Clamp to [-1, 1] to handle numerical errors
            dotProduct = Math.max(-1.0, Math.min(1.0, dotProduct));

            // Return acute angle (0-90 degrees)
            final double angle = Math.acos(Math.abs(dotProduct));
            return Math.toDegrees(angle);
        }

        @Override
        public String toString() {
            return String.format("PrincipalAxis[direction=(%.3f, %.3f, %.3f), variance=%.3f]",
                    x, y, z, getVariance());
        }
    }
}