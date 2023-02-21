/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import ij.measure.Calibration;
import sc.fiji.snt.Path;

/**
 * Defines a Point in a tracing canvas in pixel coordinates.
 *
 * @author Tiago Ferreira
 */
public class PointInCanvas extends PointInImage implements SNTPoint {

	public PointInCanvas(final double x, final double y, final double z) {
		super(x, y, z);
	}

	protected PointInCanvas(final double x, final double y, final double z,
		final Path onPath)
	{
		super(x, y, z, onPath);
	}

	/**
	 * Converts the pixels coordinates of this point into a physical location if
	 * this point is associated with a Path.
	 *
	 * @return this point in spatially calibrated units
	 * @throws IllegalArgumentException if this point is not associated with a
	 *           Path
	 */
	public PointInImage getScaledPoint() throws IllegalArgumentException {
		if (onPath == null) throw new IllegalArgumentException(
			"Point not associated with a Path");
		final double x, y, z;
		final Calibration cal = onPath.getCalibration();
		x = this.x * cal.pixelWidth;
		y = this.y * cal.pixelHeight;
		z = this.z * cal.pixelDepth;
		return new PointInImage(x, y, z, onPath);
	}
}
