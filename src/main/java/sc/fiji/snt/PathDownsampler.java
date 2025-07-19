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

package sc.fiji.snt;

import java.util.ArrayList;

class SimplePoint {

	/** The x, y, z coordinates of this point */
	public double x = 0, y = 0, z = 0;
	/** The original index of this point before downsampling */
	public int originalIndex;

	public SimplePoint(final double x, final double y, final double z,
		final int originalIndex)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.originalIndex = originalIndex;
	}
}

/**
 * This is an implementation of the Ramer-Douglas-Peucker algorithm for
 * simplifying a curve represented by line-segments, as described <a href=
 * "https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm">here</a>
 */
public class PathDownsampler {

	protected static ArrayList<SimplePoint> downsample(
		final ArrayList<SimplePoint> points, final double permittedDeviation)
	{
		final int n = points.size();
		final SimplePoint startPoint = points.get(0);
		final SimplePoint endPoint = points.get(n - 1);
		double vx = endPoint.x - startPoint.x;
		double vy = endPoint.y - startPoint.y;
		double vz = endPoint.z - startPoint.z;
		final double vSize = Math.sqrt(vx * vx + vy * vy + vz * vz);
		// Scale v to be a unit vector along the line:
		vx /= vSize;
		vy /= vSize;
		vz /= vSize;
		// Now find the point between the end points that is the greatest
		// distance from the line:
		double maxDistanceSquared = 0;
		int maxIndex = -1;
		for (int i = 1; i < n - 1; ++i) {
			final SimplePoint midPoint = points.get(i);
			final double dx = midPoint.x - startPoint.x;
			final double dy = midPoint.y - startPoint.y;
			final double dz = midPoint.z - startPoint.z;
			final double projectedLength = dx * vx + dy * vy + dz * vz;
			final double dLengthSquared = dx * dx + dy * dy + dz * dz;
			final double distanceSquared = dLengthSquared - projectedLength *
				projectedLength;
			if (distanceSquared > maxDistanceSquared) {
				maxDistanceSquared = distanceSquared;
				maxIndex = i;
			}
		}
		if (maxDistanceSquared > (permittedDeviation * permittedDeviation)) {
			// Then divide at that point and recurse:
			ArrayList<SimplePoint> firstPart = new ArrayList<>();
			for (int i = 0; i <= maxIndex; ++i)
				firstPart.add(points.get(i));
			ArrayList<SimplePoint> secondPart = new ArrayList<>();
			for (int i = maxIndex; i < n; ++i)
				secondPart.add(points.get(i));
			firstPart = downsample(firstPart, permittedDeviation);
			secondPart = downsample(secondPart, permittedDeviation);
			firstPart.remove(firstPart.size() - 1);
			firstPart.addAll(secondPart);
			return firstPart;
		}
		else {
			// Otherwise just return the first and last points:
			final ArrayList<SimplePoint> result = new ArrayList<>();
			result.add(startPoint);
			result.add(endPoint);
			return result;
		}
	}
}
