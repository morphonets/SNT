/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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


import java.util.Collection;
import java.util.Iterator;

import sc.fiji.snt.annotation.BrainAnnotation;

/**
 * Classes extend this interface implement a point in a 3D space, always using
 * real world coordinates.
 *
 * @author Tiago Ferreira
 */
public interface SNTPoint {

	/** @return the X-coordinate of the point */
	public double getX();

	/** @return the Y-coordinate of the point */
	public double getY();

	/** @return the Z-coordinate of the point */
	public double getZ();
	
	/** @return the coordinate on the specified axis */
	public double getCoordinateOnAxis(int axis);

	/**
	 * Assigns a neuropil annotation (e.g., atlas compartment) to this point.
	 *
	 * @param annotation the annotation to be assigned to this point
	 */
	public void setAnnotation(BrainAnnotation annotation);

	/** @return the neuropil annotation assigned to this point */
	public BrainAnnotation getAnnotation();

	public void setHemisphere(char lr);

	public char getHemisphere();

	public static PointInImage average(final Collection<? extends SNTPoint> points) {
		if (points == null || points.isEmpty())
			return null;
		double x = 0;
		double y = 0;
		double z = 0;
		int n = 0;
		final Iterator<? extends SNTPoint> it = points.iterator();
		while (it.hasNext()) {
			final SNTPoint p = it.next();
			if (p != null) {
				x += p.getX();
				y += p.getY();
				z += p.getZ();
				n++;
			}
		}
		return new PointInImage(x / n, y / n, z / n);
	}

}
