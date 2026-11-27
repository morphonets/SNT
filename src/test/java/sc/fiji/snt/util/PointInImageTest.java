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

package sc.fiji.snt.util;

import static org.junit.Assert.*;

import org.junit.Test;
import sc.fiji.snt.Tree;

/**
 * Tests for {@link PointInImage}.
 */
public class PointInImageTest {

	private static final double DELTA = 1e-9;

	@Test
	public void testDefaultConstructor() {
		final PointInImage p = new PointInImage();
		assertEquals(0.0, p.x, DELTA);
		assertEquals(0.0, p.y, DELTA);
		assertEquals(0.0, p.z, DELTA);
	}

	@Test
	public void testCoordinateConstructor() {
		final PointInImage p = new PointInImage(1.0, 2.0, 3.0);
		assertEquals(1.0, p.x, DELTA);
		assertEquals(2.0, p.y, DELTA);
		assertEquals(3.0, p.z, DELTA);
	}

	@Test
	public void testDistanceSquaredTo_coordinates() {
		final PointInImage p = new PointInImage(0, 0, 0);
		assertEquals(14.0, p.distanceSquaredTo(1.0, 2.0, 3.0), DELTA);
	}

	@Test
	public void testDistanceSquaredTo_point() {
		final PointInImage p = new PointInImage(1, 1, 1);
		final PointInImage q = new PointInImage(4, 5, 1);
		assertEquals(25.0, p.distanceSquaredTo(q), DELTA);
	}

	@Test
	public void testDistanceTo() {
		final PointInImage p = new PointInImage(0, 0, 0);
		final PointInImage q = new PointInImage(3, 4, 0);
		assertEquals(5.0, p.distanceTo(q), DELTA);
	}

	@Test
	public void testDistanceTo_samePoint() {
		final PointInImage p = new PointInImage(5, 6, 7);
		assertEquals(0.0, p.distanceTo(p), DELTA);
	}

	@Test
	public void testEuclideanDxTo() {
		final PointInImage p = new PointInImage(0, 0, 0);
		final PointInImage q = new PointInImage(3, 4, 0);
		assertEquals(p.distanceTo(q), p.euclideanDxTo(q), DELTA);
	}

	@Test
	public void testChebyshevXYdxTo() {
		final PointInImage p = new PointInImage(0, 0, 0);
		final PointInImage q = new PointInImage(3, 7, 100);
		assertEquals(7.0, p.chebyshevXYdxTo(q), DELTA);
	}

	@Test
	public void testChebyshevZdxTo() {
		final PointInImage p = new PointInImage(0, 0, 0);
		final PointInImage q = new PointInImage(3, 7, 5);
		assertEquals(5.0, p.chebyshevZdxTo(q), DELTA);
	}

	@Test
	public void testChebyshevDxTo() {
		final PointInImage p = new PointInImage(0, 0, 0);
		final PointInImage q = new PointInImage(3, 7, 10);
		// max(max(|3|,|7|), |10|) = 10
		assertEquals(10.0, p.chebyshevDxTo(q), DELTA);
	}

	@Test
	public void testIsReal_validPoint() {
		final PointInImage p = new PointInImage(1, 2, 3);
		assertTrue(p.isReal());
	}

	@Test
	public void testIsReal_nanX() {
		final PointInImage p = new PointInImage(Double.NaN, 2, 3);
		assertFalse(p.isReal());
	}

	@Test
	public void testIsReal_nanY() {
		final PointInImage p = new PointInImage(1, Double.NaN, 3);
		assertFalse(p.isReal());
	}

	@Test
	public void testIsReal_nanZ() {
		final PointInImage p = new PointInImage(1, 2, Double.NaN);
		assertFalse(p.isReal());
	}

	@Test
	public void testIsReal_infiniteX() {
		final PointInImage p = new PointInImage(Double.POSITIVE_INFINITY, 2, 3);
		assertFalse(p.isReal());
	}

	@Test
	public void testIsReal_zero() {
		final PointInImage p = new PointInImage(0, 0, 0);
		assertTrue(p.isReal());
	}

	@Test
	public void testIsSameLocation_equal() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage q = new PointInImage(1, 2, 3);
		assertTrue(p.isSameLocation(q));
	}

	@Test
	public void testIsSameLocation_different() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage q = new PointInImage(1, 2, 4);
		assertFalse(p.isSameLocation(q));
	}

	@Test
	public void testScale() {
		final PointInImage p = new PointInImage(2, 3, 4);
		p.scale(2, 3, 4);
		assertEquals(4.0, p.x, DELTA);
		assertEquals(9.0, p.y, DELTA);
		assertEquals(16.0, p.z, DELTA);
	}

	@Test
	public void testScaleByOne_unchanged() {
		final PointInImage p = new PointInImage(5, 6, 7);
		p.scale(1, 1, 1);
		assertEquals(5.0, p.x, DELTA);
		assertEquals(6.0, p.y, DELTA);
		assertEquals(7.0, p.z, DELTA);
	}

	@Test
	public void testGetX() {
		final PointInImage p = new PointInImage(10, 20, 30);
		assertEquals(10.0, p.getX(), DELTA);
	}

	@Test
	public void testGetY() {
		final PointInImage p = new PointInImage(10, 20, 30);
		assertEquals(20.0, p.getY(), DELTA);
	}

	@Test
	public void testGetZ() {
		final PointInImage p = new PointInImage(10, 20, 30);
		assertEquals(30.0, p.getZ(), DELTA);
	}

	@Test
	public void testGetCoordinateOnAxis() {
		final PointInImage p = new PointInImage(10, 20, 30);
		assertEquals(10.0, p.getCoordinateOnAxis(Tree.X_AXIS), DELTA);
		assertEquals(20.0, p.getCoordinateOnAxis(Tree.Y_AXIS), DELTA);
		assertEquals(30.0, p.getCoordinateOnAxis(Tree.Z_AXIS), DELTA);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGetCoordinateOnAxis_invalidAxis() {
		final PointInImage p = new PointInImage(1, 2, 3);
		p.getCoordinateOnAxis(99);
	}

	@Test
	public void testEquals_sameObject() {
		final PointInImage p = new PointInImage(1, 2, 3);
		assertEquals(p, p);
	}

	@Test
	public void testEquals_equalPoints() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage q = new PointInImage(1, 2, 3);
		assertEquals(p, q);
	}

	@Test
	public void testEquals_differentPoints() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage q = new PointInImage(1, 2, 4);
		assertNotEquals(p, q);
	}

	@Test
	public void testEquals_null() {
		final PointInImage p = new PointInImage(1, 2, 3);
		assertNotEquals(p, null);
	}

	@Test
	public void testHashCode_consistency() {
		final PointInImage p = new PointInImage(1, 2, 3);
		assertEquals(p.hashCode(), p.hashCode());
	}

	@Test
	public void testHashCode_equalObjects() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage q = new PointInImage(1, 2, 3);
		assertEquals(p.hashCode(), q.hashCode());
	}

	@Test
	public void testClone() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage clone = p.clone();
		assertNotSame(p, clone);
		assertEquals(p.x, clone.x, DELTA);
		assertEquals(p.y, clone.y, DELTA);
		assertEquals(p.z, clone.z, DELTA);
	}

	@Test
	public void testClone_independence() {
		final PointInImage p = new PointInImage(1, 2, 3);
		final PointInImage clone = p.clone();
		clone.x = 99;
		assertEquals(1.0, p.x, DELTA);
	}
}
