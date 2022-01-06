/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import java.util.List;
import java.util.PriorityQueue;

import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;

/**
 * This class encapsulates the relationship between an arbitrary point
 * (nearPoint.x,nearPoint.y,nearPoint.z) close to a particular point on a
 * particular path. The important method here is
 * {@code distanceToPathNearPoint()} which retrieves the distance to the nearest
 * point on the the line segments on either side of the path point, rather than
 * just the point. Also, it will return null if the point appears to be "off the
 * end" of the Path.
 *
 * @author Mark Longair
 * @author Tiago Ferreira
 */
public class NearPoint implements Comparable<NearPoint> {

	private final Path path;
	public final int indexInPath;
	private final double distanceSquared;
	private Double cachedDistanceToPathNearPoint;
	private final PointInImage pathPoint;
	protected PointInImage near;
	protected IntersectionOnLine closestIntersection;

	private final boolean unScaledPositions;
	private final boolean ignoreZ;

	public NearPoint(final PointInImage nearPoint, final Path path,
		final int indexInPath)
	{
		this(nearPoint, path, indexInPath, false);
	}

	/* Constructor for 2D calculations */
	protected NearPoint(final PointInImage nearPoint, final Path path,
		final int indexInPath, final boolean unScaledPositions)
	{
		this.unScaledPositions = unScaledPositions;
		this.path = path;
		this.indexInPath = indexInPath;
		ignoreZ = Double.isNaN(nearPoint.z);
		pathPoint = getPathPoint(indexInPath);
		near = new PointInImage(nearPoint.x, nearPoint.y, (ignoreZ) ? 0
			: nearPoint.z);
		this.distanceSquared = near.distanceSquaredTo(pathPoint);
		closestIntersection = null;
	}

	public PointInImage getNode() {
		return path.getNodeWithoutChecks(indexInPath);
	}

	public PointInCanvas getNodeUnscaled() {
		return path.getPointInCanvas(indexInPath);
	}

	private PointInImage getPathPoint(final int index) {
		PointInImage node;
		node = (unScaledPositions) ? path.getPointInCanvas(index) : path
			.getNodeWithoutChecks(index);
		if (ignoreZ) node.z = 0;
		return node;
	}

	public Path getPath() {
		return path;
	}

	@Override
	public int compareTo(final NearPoint other) {
		final double d = distanceSquared;
		final double od = other.distanceSquared;
		return Double.compare(d, od);
	}

	@Override
	public String toString() {
		return "  near: (" + near.x + "," + near.y + "," + near.z + ")\n" +
			"  pathPoint: (" + pathPoint.x + "," + pathPoint.y + "," + pathPoint.z +
			")\n" + "  indexInPath: " + indexInPath + "\n" + "  path: " + path +
			"\n" + "  distanceSquared: " + distanceSquared + "\n" +
			"  cachedDistanceToPathNearPoint: " + cachedDistanceToPathNearPoint;
	}

	/**
	 * Returns the distance to the path, If a corresponding point on the path was
	 * found . Returns -1 if no such point can be found
	 *
	 * @return the distance to the path, If a corresponding point on the path was
	 *         found . Returns -1 if no such point can be found
	 */
	public double distanceToPathNearPoint() {
		/*
		 * Currently these objects are immutable, so if there's a cached value then just
		 * return that:
		 */
		if (cachedDistanceToPathNearPoint != null)
			return cachedDistanceToPathNearPoint;
		final int pathSize = path.size();
		if (pathSize < 1) {
			cachedDistanceToPathNearPoint = (double) -1;
			return -1;
		}
		if (indexInPath == 0 || indexInPath == (pathSize - 1)) {
			PointInImage start;
			PointInImage end;
			if (indexInPath == 0) {
				start = pathPoint;
				end = getPathPoint(0);
			}
			else {
				start = getPathPoint(pathSize - 2);
				end = pathPoint;
			}
			final IntersectionOnLine intersection;
			if (path.size() == 1) {
				final PointInImage point = getPathPoint(0);
				intersection = distanceFromSinglePointPath(point.x, point.y, point.z,
					near.x, near.y, near.z);
			}
			else {
				intersection = distanceToLineSegment(near.x, near.y, near.z, start.x,
					start.y, start.z, end.x, end.y, end.z);
			}
			if (intersection == null) {
				closestIntersection = null;
				cachedDistanceToPathNearPoint = (double) -1;
				return -1;
			}
			else {
				closestIntersection = intersection;
				cachedDistanceToPathNearPoint = intersection.distance;
				return intersection.distance;
			}
		}
		else {
			// There's a point on either size:
			final PointInImage previous = getPathPoint(indexInPath - 1);
			final PointInImage next = getPathPoint(indexInPath + 1);
			final IntersectionOnLine intersectionA = distanceToLineSegment(near.x,
				near.y, near.z, previous.x, previous.y, previous.z, pathPoint.x,
				pathPoint.y, pathPoint.z);
			final IntersectionOnLine intersectionB = distanceToLineSegment(near.x,
				near.y, near.z, pathPoint.x, pathPoint.y, pathPoint.z, next.x, next.y,
				next.z);
			double smallestDistance = -1;
			if (intersectionA == null && intersectionB != null) {
				smallestDistance = intersectionB.distance;
				closestIntersection = intersectionB;
			}
			else if (intersectionA != null && intersectionB == null) {
				smallestDistance = intersectionA.distance;
				closestIntersection = intersectionA;
			}
			else if (intersectionA != null && intersectionB != null) {
				if (intersectionA.distance < intersectionB.distance) {
					smallestDistance = intersectionA.distance;
					closestIntersection = intersectionA;
				}
				else {
					smallestDistance = intersectionB.distance;
					closestIntersection = intersectionB;
				}
			}
			if (smallestDistance >= 0) {
				cachedDistanceToPathNearPoint = smallestDistance;
				return smallestDistance;
			}
			/*
			 * Otherwise the only other possibility is that it's between the planes:
			 */
			final boolean afterPlaneAtEndOfPrevious = 0 < normalSideOfPlane(
				pathPoint.x, pathPoint.y, pathPoint.z, pathPoint.x - previous.x,
				pathPoint.y - previous.y, pathPoint.z - previous.z, near.x, near.y,
				near.z);
			final boolean beforePlaneAtStartOfNext = 0 < normalSideOfPlane(
				pathPoint.x, pathPoint.y, pathPoint.z, pathPoint.x - next.x,
				pathPoint.y - next.y, pathPoint.z - next.z, near.x, near.y, near.z);
			if (afterPlaneAtEndOfPrevious && beforePlaneAtStartOfNext) {
				// Then just return the distance to the point:
				closestIntersection = new IntersectionOnLine();
				closestIntersection.distance = distanceToPathPoint();
				closestIntersection.x = pathPoint.x;
				closestIntersection.y = pathPoint.y;
				closestIntersection.z = pathPoint.z;
				closestIntersection.fromPerpendicular = false;
				cachedDistanceToPathNearPoint = closestIntersection.distance;
				return closestIntersection.distance;
			}
			else {
				closestIntersection = null;
				cachedDistanceToPathNearPoint = (double) -1;
				return -1;
			}
		}
	}

	/*
	 * This returns null if the perpendicular dropped to the line doesn't lie within
	 * the segment. Otherwise it returns the shortest distance to this line segment
	 * and the point of intersection in an IntersectionOnLine object
	 */
	public static IntersectionOnLine distanceToLineSegment(final double x,
		final double y, final double z, final double startX, final double startY,
		final double startZ, final double endX, final double endY,
		final double endZ)
	{

		final boolean insideStartPlane = 0 >= normalSideOfPlane(startX, startY,
			startZ, startX - endX, startY - endY, startZ - endZ, x, y, z);
		final boolean insideEndPlane = 0 >= normalSideOfPlane(endX, endY, endZ,
			endX - startX, endY - startY, endZ - startZ, x, y, z);
		if (insideStartPlane && insideEndPlane) return distanceFromPointToLine(
			startX, startY, startZ, endX - startX, endY - startY, endZ - startZ, x, y,
			z);
		else return null;
	}

	public double distanceToPathPoint() {
		return Math.sqrt(distanceSquared);
	}

	public double distanceToPathPointSquared() {
		return distanceSquared;
	}

	/*
	 * This tests whether a given point (x, y, z) is on the side of a plane in the
	 * direction of its normal vector (nx,ny,nz). (cx,cy,cz) is any point in the
	 * plane. If (x,y,z) is in the plane, it returns 0; if (x,y,z) is on the side of
	 * the plane pointed to by the normal vector then it returns 1; otherwise (i.e.
	 * it is on the other side) this returns -1
	 */
	public static int normalSideOfPlane(final double cx, final double cy,
		final double cz, final double nx, final double ny, final double nz,
		final double x, final double y, final double z)
	{
		final double vx = x - cx;
		final double vy = y - cy;
		final double vz = z - cz;

		final double dotProduct = nx * vx + ny * vy + nz * vz;

		if (dotProduct > 0) return 1;
		else if (dotProduct < 0) return -1;
		else return 0;
	}

	/*
	 * To find where the perpendicular dropped from the the point to the line meets
	 * it, with:
	 *
	 * A = (ax, ay, az) being a point in the line V = (vx, vy, vz) being a vector
	 * along the line P = (x, y, z) being our point
	 *
	 * [(A + b V) - P] . V = 0
	 *
	 * ... which we can reduce to:
	 *
	 * b = [ (x - ax) * vx + (y - ay) * vy + (z - az) * vz ] / (vx * vx + vy * vy +
	 * vz * vz)
	 */

	static class IntersectionOnLine {

		double x, y, z, distance;
		boolean fromPerpendicular = true;
	}

	public static IntersectionOnLine distanceFromPointToLine(final double ax,
		final double ay, final double az, final double vx, final double vy,
		final double vz, final double x, final double y, final double z)
	{
		final double b = ((x - ax) * vx + (y - ay) * vy + (z - az) * vz) / (vx *
			vx + vy * vy + vz * vz);

		final IntersectionOnLine i = new IntersectionOnLine();

		i.x = ax + b * vx;
		i.y = ay + b * vy;
		i.z = az + b * vz;

		final double xdiff = i.x - x;
		final double ydiff = i.y - y;
		final double zdiff = i.z - z;

		i.distance = Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
		return i;
	}

	protected static IntersectionOnLine distanceFromSinglePointPath(
		final double pathX, final double pathY, final double pathZ,
		final double nearPointX, final double nearPointY, final double nearPointZ)
	{
		final IntersectionOnLine i = new IntersectionOnLine();
		i.x = pathX;
		i.y = pathY;
		i.z = pathZ;
		final double xdiff = i.x - nearPointX;
		final double ydiff = i.y - nearPointY;
		final double zdiff = i.z - nearPointZ;
		i.distance = Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
		return i;
	}
}

class NearPointInCanvas implements Comparable<NearPointInCanvas> {

	private final double distanceSquared;
	private final Path path;

	protected NearPointInCanvas(final PointInCanvas nearPic, final PointInCanvas pic) {
		this.path = nearPic.onPath;
		this.distanceSquared = nearPic.distanceSquaredTo(pic);
	}

	@Override
	public int compareTo(NearPointInCanvas other) {
		final double d = getDistanceSquared();
		final double od = other.getDistanceSquared();
		return Double.compare(d, od);
	}

	public double getDistanceSquared() {
		return distanceSquared;
	}

	public Path getPath() {
		return path;
	}

	public static NearPointInCanvas nearestPointInCanvas(final List<PointInCanvas> points, final PointInCanvas pic) {

		// Order all points in all paths by their Euclidean distance to pic:
		final PriorityQueue<NearPointInCanvas> pq = new PriorityQueue<>();
		for (final PointInCanvas point : points) {
			pq.add(new NearPointInCanvas(point, pic));
		}

		while (true) {
			final NearPointInCanvas np = pq.poll();
			if (np == null)
				return null;
			if (np.getDistanceSquared() >= 0) {
				return np;
			}
		}
	}

}
