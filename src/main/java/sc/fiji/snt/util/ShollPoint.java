/*
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.util.Iterator;
import java.util.Set;

import ij.measure.Calibration;
import sc.fiji.snt.analysis.sholl.ShollUtils;

/**
 * Convenience flavor of {@link PointInImage} defining 2D/3D points for Sholll Analysis.
 *
 * @author Tiago Ferreira
 */
public class ShollPoint extends PointInImage {

	public int flag = NONE;

	public final static int NONE = -1;
	public final static int VISITED = -2;
	public final static int DELETE = -4;
	public final static int KEEP = -8;

	public ShollPoint(final PointInImage pim) {
		this(pim.x, pim.y, pim.z);
	}

	public ShollPoint(final double x, final double y, final double z) {
		super(x,y, z);
	}

	public ShollPoint(final double x, final double y) {
		super(x,y, 0);
	}

	public ShollPoint(final int x, final int y, final int z, final Calibration cal) {
		super(cal.getX(x), cal.getY(y), cal.getZ(z));
	}

	public ShollPoint(final int x, final int y, final Calibration cal) {
		super(cal.getX(x), cal.getY(y), cal.getZ(0));
	}

	public ShollPoint(final int x, final int y, final int z, final int flag) {
		super(x,y,z);
		this.flag = flag;
	}

	public static void scale(final Set<ShollPoint> set, final Calibration cal) {
		for (final Iterator<ShollPoint> it = set.iterator(); it.hasNext();) {
			final ShollPoint point = it.next();
			point.x = cal.getX(point.x);
			point.y = cal.getY(point.y);
			point.z = cal.getZ(point.z);
		}
	}

	public static ShollPoint fromString(final String string) {
		if (string == null || string.isEmpty())
			return null;
		final String[] ccs = string.trim().split(",");
		if (ccs.length == 3) {
			return new ShollPoint(Double.valueOf(ccs[0]), Double.valueOf(ccs[1]), Double.valueOf(ccs[2]));
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

	@Override
	public String toString() {
		return ShollUtils.d2s(x) + ", " + ShollUtils.d2s(y) + ", " + ShollUtils.d2s(z);
	}

	@Override
	public boolean equals(final Object object) {
		if (this == object)
			return true;
		if (!(object instanceof ShollPoint))
			return false;
		final ShollPoint point = (ShollPoint) object;
		return (this.x == point.x && this.y == point.y && this.z == point.z);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
