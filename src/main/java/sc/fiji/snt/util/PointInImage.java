/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

package sc.fiji.snt.util;

import java.util.Objects;

import ij.measure.Calibration;
import sholl.UPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathTransformer;
import sc.fiji.snt.Tree;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

/**
 * Defines a Point in an image, a node of a traced {@link Path}. Coordinates are
 * always expressed in real-world coordinates.
 *
 * @author Tiago Ferreira
 */
public class PointInImage implements SNTPoint {

	/** The cartesian coordinate of this node */
	public double x, y, z;

	/**
	 * A property associated with this point (e.g., voxel intensity) (optional
	 * field)
	 */
	public double v;

	private BrainAnnotation annotation;

	/** The Path associated with this node, if any (optional field) */
	public Path onPath = null;

	private char lr = BrainAnnotation.ANY_HEMISPHERE;


	public PointInImage(final double x, final double y, final double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	protected PointInImage(final double x, final double y, final double z,
		final Path onPath)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.onPath = onPath;
	}

	public double distanceSquaredTo(final double ox, final double oy,
		final double oz)
	{
		final double xdiff = x - ox;
		final double ydiff = y - oy;
		final double zdiff = z - oz;
		return xdiff * xdiff + ydiff * ydiff + zdiff * zdiff;
	}

	public double distanceSquaredTo(final PointInImage o) {
		return distanceSquaredTo(o.x, o.y, o.z);
	}

	public double distanceTo(final PointInImage o) {
		return Math.sqrt(distanceSquaredTo(o));
	}

	@Override
	public String toString() {
		return "( " + x + ", " + y + ", " + z + " ) [onPath " + onPath + "]";
	}

	public PointInImage transform(final PathTransformer transformer) {
		final double[] result = new double[3];
		transformer.transformPoint(x, y, z, result);
		return new PointInImage(result[0], result[1], result[2]);
	}

	public boolean isReal() {
		return !(Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || Double
			.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z));
	}

	public boolean isSameLocation(final PointInImage pim) {
		return (this.x == pim.x) && (this.y == pim.y) && (this.z == pim.z);
	}

	/**
	 * Scales this point coordinates.
	 *
	 * @param xScale the scaling factor for x coordinates
	 * @param yScale the scaling factor for y coordinates
	 * @param zScale the scaling factor for z coordinates
	 */
	public void scale(final double xScale, final double yScale,
		final double zScale)
	{
		x *= xScale;
		y *= yScale;
		z *= zScale;
	}

	/**
	 * Converts the coordinates of this point into pixel units if this point is
	 * associated with a Path.
	 *
	 * @return this point in pixel coordinates
	 * @throws IllegalArgumentException if this point is not associated with a
	 *           Path
	 */
	public PointInCanvas getUnscaledPoint() throws IllegalArgumentException {
		if (onPath == null) throw new IllegalArgumentException(
			"Point not associated with a Path");
		final Calibration cal = onPath.getCalibration();
		final PointInCanvas offset = onPath.getCanvasOffset();
		final double x = this.x / cal.pixelWidth + offset.x;
		final double y = this.y / cal.pixelHeight + offset.y;
		final double z = this.z / cal.pixelDepth + offset.z;
		return new PointInCanvas(x, y, z, onPath);
	}

	/**
	 * Converts the coordinates of this point into pixel units if this point is
	 * associated with a Path.
	 *
	 * @param view {@link MultiDThreePanes#XY_PLANE},
	 *             {@link MultiDThreePanes#ZY_PLANE}, etc.
	 * @return this point in pixel coordinates
	 * @throws IllegalArgumentException if this point is not associated with a Path,
	 *                                  or view was not recognized
	 */
	public PointInCanvas getUnscaledPoint(final int view) {
		final PointInCanvas point = getUnscaledPoint();
		final double x = point.getX();
		final double y = point.getY();
		final double z = point.getZ();
		switch (view) {
			case MultiDThreePanes.XY_PLANE:
				break;
			case MultiDThreePanes.XZ_PLANE:
				point.y = z;
				point.z = y;
				break;
			case MultiDThreePanes.ZY_PLANE:
				point.x = z;
				point.z = x;
				break;
			default:
				throw new IllegalArgumentException("Unknown plane: " + view);
		}
		return point;
	}

	public UPoint toUPoint() {
		return new UPoint(x, y, z);
	}

	/**
	 * Returns the Path associated with this node (if any)
	 * 
	 * @return the path associated with this node or null if
	 *         {@link #setPath(Path)} has not been called.
	 */
	public Path getPath() {
		return onPath;
	}

	/**
	 * Associates a Path with this node
	 * 
	 * @param onPath the Path to be associated with this node
	 */
	public void setPath(final Path onPath) {
		this.onPath = onPath;
	}

	@Override
	public PointInImage clone() {
		final PointInImage dup = new PointInImage(getX(), getY(), getZ());
		dup.onPath = onPath;
		dup.v = v;
		dup.annotation = annotation;
		dup.lr = lr;
		return dup;
	}

	@Override
	public boolean equals(final Object o) {
		if (o == this) return true;
		if (o == null) return false;
		if (getClass() != o.getClass()) return false;
		final PointInImage other = (PointInImage) o;
		final boolean sameLoc = isSameLocation(other);
		return (onPath == null || other.onPath == null) ? sameLoc : (sameLoc && onPath.equals(other.onPath));
	}

	@Override
	public int hashCode() {
		return Objects.hash(x, y, z);
	}

	@Override
	public double getX() {
		return x;
	}

	@Override
	public double getY() {
		return y;
	}

	@Override
	public double getZ() {
		return z;
	}
	
	/**
	 * Gets the coordinate along the specified axis.
	 *
	 * @param axis the axis. Either {@link Tree#X_AXIS}, {@link Tree#Y_AXIS}, or
	 *             {@link Tree#Z_AXIS}
	 */
	@Override
	public double getCoordinateOnAxis(final int axis) {
		switch (axis) {
		case Tree.X_AXIS:
			return getX();
		case Tree.Y_AXIS:
			return getY();
		case Tree.Z_AXIS:
			return getZ();
		default:
			throw new IllegalArgumentException("Unrecognized axis " + axis);
		}
	}

	@Override
	public void setAnnotation(final BrainAnnotation annotation) {
		this.annotation = annotation;
	}

	@Override
	public BrainAnnotation getAnnotation() {
		return annotation;
	}

	@Override
	public void setHemisphere(char lr) {
		this.lr = lr;
	}

	@Override
	public char getHemisphere() {
		return lr;
	}
}
