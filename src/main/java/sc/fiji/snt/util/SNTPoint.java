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

package sc.fiji.snt.util;


import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.Collection;

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

	/**
	 * Computes the average position of a collection of SNTPoints.
	 *
	 * @param points the collection of points to average
	 * @return the average point, or null if the collection is null or empty
	 */
	public static PointInImage average(final Collection<? extends SNTPoint> points) {
		if (points == null || points.isEmpty())
			return null;
		double x = 0;
		double y = 0;
		double z = 0;
		int n = 0;
        for (final SNTPoint p : points) {
            if (p != null) {
                x += p.getX();
                y += p.getY();
                z += p.getZ();
                n++;
            }
        }
		return new PointInImage(x / n, y / n, z / n);
	}

	/**
	 * Script friendly method for instantiating a new point.
	 *
	 * @param x the X coordinate
	 * @param y the Y coordinate
	 * @param z the Z coordinate
	 */
	public static PointInImage of(final Number x, final Number y, final Number z) {
		return new PointInImage(x.doubleValue(), y.doubleValue(), z.doubleValue() );
	}

    public static PointInImage of(final Number[] coords) {
        return new PointInImage(coords[0].doubleValue(), coords[1].doubleValue(), coords[2].doubleValue() );
    }
}
