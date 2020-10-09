/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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
package sc.fiji.snt.analysis.sholl;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import ij.measure.Calibration;

/**
 * 'Universal Point' Class to access Cartesian coordinates of 2D and 3D points. 
 * Designed to accommodate points from several sources such SWC nodes, pixels,
 * voxels, and ROIs.
 *
 * @author Tiago Ferreira
 */
public class UPoint {

	/** The x-coordinate */
	public double x;
	/** The y-coordinate */
	public double y;
	/** The z-coordinate */
	public double z;
	public int flag = NONE;

	public final static int NONE = -1;
	public final static int VISITED = -2;
	public final static int DELETE = -4;
	public final static int KEEP = -8;

	public UPoint() {
	}

	public UPoint(final double x, final double y, final double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public UPoint(final double x, final double y) {
		this.x = x;
		this.y = y;
		this.z = 0;
	}

	public UPoint(final int x, final int y, final int z, final Calibration cal) {
		this.x = cal.getX(x);
		this.y = cal.getY(y);
		this.z = cal.getZ(z);
	}

	public UPoint(final int x, final int y, final Calibration cal) {
		this.x = cal.getX(x);
		this.y = cal.getY(y);
		this.z = cal.getZ(0);
	}

	public UPoint(final int x, final int y, final int z, final int flag) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.flag = flag;
	}

	public void scale(final double xScale, final double yScale, final double zScale) {
		this.x *= xScale;
		this.y *= yScale;
		this.z *= zScale;
	}

	public void applyOffset(final double xOffset, final double yOffset, final double zOffset) {
		this.x += xOffset;
		this.y += yOffset;
		this.z += zOffset;
	}

	public UPoint minus(final UPoint point) {
		return new UPoint(x - point.x, y - point.y, z - point.z);
	}

	public UPoint plus(final UPoint point) {
		return new UPoint(x + point.x, y + point.y, z + point.z);
	}

	public double scalar(final UPoint point) {
		return x * point.x + y * point.y + z * point.z;
	}

	public UPoint times(final double factor) {
		return new UPoint(x * factor, y * factor, z * factor);
	}

	public double length() {
		return Math.sqrt(scalar(this));
	}

	public double distanceSquared(final UPoint point) {
		final double x1 = x - point.x;
		final double y1 = y - point.y;
		final double z1 = z - point.z;
		return x1 * x1 + y1 * y1 + z1 * z1;
	}

	public double euclideanDxTo(final UPoint point) {
		return Math.sqrt(distanceSquared(point));
	}

	public double chebyshevXYdxTo(final UPoint point) {
		return Math.max(Math.abs(x - point.x), Math.abs(y - point.y));
	}

	public double chebyshevZdxTo(final UPoint point) {
		return Math.abs(z - point.z);
	}

	public double chebyshevDxTo(final UPoint point) {
		return Math.max(chebyshevXYdxTo(point), chebyshevZdxTo(point));
	}

	public static UPoint average(final List<UPoint> points) {
		UPoint result = points.get(0);
		for(int i = 1; i < points.size(); i++)
			result = result.plus(points.get(i));
		return result.times(1.0 / points.size());
	}

	public static void scale(final Set<UPoint> set, final Calibration cal) {
		for (final Iterator<UPoint> it = set.iterator(); it.hasNext();) {
			final UPoint point = it.next();
			point.x = cal.getX(point.x);
			point.y = cal.getY(point.y);
			point.z = cal.getZ(point.z);
		}
	}

	protected static UPoint fromString(final String string) {
		if (string == null || string.isEmpty())
			return null;
		final String[] ccs = string.trim().split(",");
		if (ccs.length == 3) {
			return new UPoint(Double.valueOf(ccs[0]), Double.valueOf(ccs[1]), Double.valueOf(ccs[2]));
		}
		return null;
	}

	public double rawX(final Calibration cal) {
		return cal.getRawX(x);
	}

	public double rawY(final Calibration cal) {
		return cal.getRawY(y);
	}

	public double rawZ(final Calibration cal) {
		return z / cal.pixelDepth + cal.zOrigin;
	}

	public void setFlag(final int flag) {
		this.flag = flag;
	}

	public int intX() {
		return (int) x;
	}

	public int intY() {
		return (int) y;
	}

	public int intZ() {
		return (int) z;
	}

	@Override
	public String toString() {
		return ShollUtils.d2s(x) + ", " + ShollUtils.d2s(y) + ", " + ShollUtils.d2s(z);
	}

	@Override
	public boolean equals(final Object object) {
		if (this == object)
			return true;
		if (!(object instanceof UPoint))
			return false;
		final UPoint point = (UPoint) object;
		return (this.x == point.x && this.y == point.y && this.z == point.z);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
