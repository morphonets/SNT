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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link BoundingBox}.
 */
public class BoundingBoxTest {

	private static final double DELTA = 1e-9;

	@Test
	public void testDefaultConstructor_nanDimensions() {
		final BoundingBox bb = new BoundingBox();
		assertFalse(bb.hasDimensions());
		assertTrue(Double.isNaN(bb.origin().x));
		assertTrue(Double.isNaN(bb.originOpposite().x));
	}

	@Test
	public void testDefaultSpacing() {
		final BoundingBox bb = new BoundingBox();
		assertEquals(1.0, bb.xSpacing, DELTA);
		assertEquals(1.0, bb.ySpacing, DELTA);
		assertEquals(1.0, bb.zSpacing, DELTA);
	}

	@Test
	public void testConstructorFromPoints() {
		final List<PointInImage> pts = Arrays.asList(
			new PointInImage(1, 2, 3),
			new PointInImage(4, 6, 9),
			new PointInImage(2, 0, 5)
		);
		final BoundingBox bb = new BoundingBox(pts);
		assertEquals(1.0, bb.origin().x, DELTA);
		assertEquals(0.0, bb.origin().y, DELTA);
		assertEquals(3.0, bb.origin().z, DELTA);
		assertEquals(4.0, bb.originOpposite().x, DELTA);
		assertEquals(6.0, bb.originOpposite().y, DELTA);
		assertEquals(9.0, bb.originOpposite().z, DELTA);
	}

	@Test
	public void testCompute() {
		final BoundingBox bb = new BoundingBox();
		final List<PointInImage> pts = Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 5, 2)
		);
		bb.compute(pts.iterator());
		assertEquals(0.0, bb.origin().x, DELTA);
		assertEquals(10.0, bb.originOpposite().x, DELTA);
		assertEquals(5.0, bb.originOpposite().y, DELTA);
	}

	@Test
	public void testAppend_empty() {
		final BoundingBox bb = new BoundingBox();
		final List<PointInImage> pts = Arrays.asList(
			new PointInImage(2, 3, 4),
			new PointInImage(8, 7, 6)
		);
		bb.append(pts.iterator());
		assertEquals(2.0, bb.origin().x, DELTA);
		assertEquals(8.0, bb.originOpposite().x, DELTA);
	}

	@Test
	public void testAppend_extends() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(5, 5, 5)
		));
		final List<PointInImage> more = Arrays.asList(
			new PointInImage(-1, 10, 3)
		);
		bb.append(more.iterator());
		assertEquals(-1.0, bb.origin().x, DELTA);
		assertEquals(10.0, bb.originOpposite().y, DELTA);
	}

	@Test
	public void testHasDimensions_true() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(1, 1, 1)
		));
		assertTrue(bb.hasDimensions());
	}

	@Test
	public void testHasDimensions_singlePoint() {
		final BoundingBox bb = new BoundingBox(Collections.singletonList(
			new PointInImage(5, 5, 5)
		));
		assertFalse(bb.hasDimensions());
	}

	@Test
	public void testWidth() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(2, 3, 4),
			new PointInImage(7, 8, 9)
		));
		assertEquals(5.0, bb.width(), DELTA);
	}

	@Test
	public void testHeight() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(2, 3, 4),
			new PointInImage(7, 8, 9)
		));
		assertEquals(5.0, bb.height(), DELTA);
	}

	@Test
	public void testDepth() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(2, 3, 4),
			new PointInImage(7, 8, 9)
		));
		assertEquals(5.0, bb.depth(), DELTA);
	}

	@Test
	public void testGetDimensions_scaled() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 20, 30)
		));
		final double[] dims = bb.getDimensions(true);
		assertEquals(10.0, dims[0], DELTA);
		assertEquals(20.0, dims[1], DELTA);
		assertEquals(30.0, dims[2], DELTA);
	}

	@Test
	public void testGetDiagonal() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(3, 4, 0)
		));
		assertEquals(5.0, bb.getDiagonal(), DELTA);
	}

	@Test
	public void testGetCentroid() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final SNTPoint centroid = bb.getCentroid();
		assertEquals(5.0, centroid.getX(), DELTA);
		assertEquals(5.0, centroid.getY(), DELTA);
		assertEquals(5.0, centroid.getZ(), DELTA);
	}

	@Test
	public void testCombine() {
		final BoundingBox bb1 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(5, 5, 5)
		));
		final BoundingBox bb2 = new BoundingBox(Arrays.asList(
			new PointInImage(3, 3, 3),
			new PointInImage(10, 10, 10)
		));
		bb1.combine(bb2);
		assertEquals(0.0, bb1.origin().x, DELTA);
		assertEquals(10.0, bb1.originOpposite().x, DELTA);
	}

	@Test
	public void testIntersection() {
		final BoundingBox bb1 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final BoundingBox bb2 = new BoundingBox(Arrays.asList(
			new PointInImage(5, 5, 5),
			new PointInImage(15, 15, 15)
		));
		final BoundingBox inter = bb1.intersection(bb2);
		assertEquals(5.0, inter.origin().x, DELTA);
		assertEquals(10.0, inter.originOpposite().x, DELTA);
	}

	@Test
	public void testContainsBoundingBox_true() {
		final BoundingBox outer = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final BoundingBox inner = new BoundingBox(Arrays.asList(
			new PointInImage(2, 2, 2),
			new PointInImage(8, 8, 8)
		));
		assertTrue(outer.contains(inner));
	}

	@Test
	public void testContainsBoundingBox_false() {
		final BoundingBox outer = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(5, 5, 5)
		));
		final BoundingBox outside = new BoundingBox(Arrays.asList(
			new PointInImage(3, 3, 3),
			new PointInImage(10, 10, 10)
		));
		assertFalse(outer.contains(outside));
	}

	@Test
	public void testContainsPoint_inside() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertTrue(bb.contains(new PointInImage(5, 5, 5)));
	}

	@Test
	public void testContainsPoint_outside() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertFalse(bb.contains(new PointInImage(11, 5, 5)));
	}

	@Test
	public void testContainsPoint_onBoundary() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertTrue(bb.contains(new PointInImage(0, 0, 0)));
		assertTrue(bb.contains(new PointInImage(10, 10, 10)));
	}

	@Test
	public void testContains2D_inside() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertTrue(bb.contains2D(new PointInImage(5, 5, 999)));
	}

	@Test
	public void testContains2D_outsideZ_stillTrue() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		// contains2D ignores Z, so far-out Z should still return true
		assertTrue(bb.contains2D(new PointInImage(5, 5, 9999)));
	}

	@Test
	public void testSetSpacing() {
		final BoundingBox bb = new BoundingBox();
		bb.setSpacing(0.5, 0.5, 1.0, "um");
		assertEquals(0.5, bb.xSpacing, DELTA);
		assertEquals(0.5, bb.ySpacing, DELTA);
		assertEquals(1.0, bb.zSpacing, DELTA);
	}

	@Test
	public void testIsScaled_afterSetSpacing() {
		final BoundingBox bb = new BoundingBox();
		assertFalse(bb.isScaled());
		bb.setSpacing(0.5, 0.5, 1.0, "um");
		assertTrue(bb.isScaled());
	}

	@Test
	public void testIsScaled_defaultNotScaled() {
		final BoundingBox bb = new BoundingBox();
		assertFalse(bb.isScaled());
	}

	@Test
	public void testSetDimensions() {
		final BoundingBox bb = new BoundingBox();
		bb.setOrigin(new PointInImage(0, 0, 0));
		bb.setSpacing(2, 2, 2, "um");
		bb.setDimensions(5, 5, 5);
		assertEquals(10.0, bb.originOpposite().x, DELTA);
		assertEquals(10.0, bb.originOpposite().y, DELTA);
		assertEquals(10.0, bb.originOpposite().z, DELTA);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSetDimensions_noOrigin() {
		final BoundingBox bb = new BoundingBox();
		bb.setDimensions(5, 5, 5);
	}

	@Test
	public void testSanitizedUnit_null() {
		assertEquals("? units", BoundingBox.sanitizedUnit(null));
	}

	@Test
	public void testSanitizedUnit_pixel() {
		assertEquals("? units", BoundingBox.sanitizedUnit("pixels"));
	}

	@Test
	public void testSanitizedUnit_empty() {
		assertEquals("? units", BoundingBox.sanitizedUnit("  "));
	}

	@Test
	public void testSanitizedUnit_um() {
		final String result = BoundingBox.sanitizedUnit("um");
		assertNotNull(result);
		assertFalse(result.isEmpty());
	}

	@Test
	public void testSanitizedUnit_micron() {
		final String result = BoundingBox.sanitizedUnit("micron");
		assertEquals(BoundingBox.sanitizedUnit("um"), result);
	}

	@Test
	public void testSanitizedUnit_custom() {
		assertEquals("mm", BoundingBox.sanitizedUnit("mm"));
	}

	@Test
	public void testScale() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(2, 4, 6),
			new PointInImage(4, 8, 12)
		));
		final BoundingBox scaled = bb.scale(new double[]{2, 2, 2});
		assertEquals(4.0, scaled.origin().x, DELTA);
		assertEquals(8.0, scaled.origin().y, DELTA);
		assertEquals(8.0, scaled.originOpposite().x, DELTA);
	}

	@Test
	public void testShift() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(5, 5, 5)
		));
		final BoundingBox shifted = bb.shift(new double[]{10, 20, 30});
		assertEquals(10.0, shifted.origin().x, DELTA);
		assertEquals(20.0, shifted.origin().y, DELTA);
		assertEquals(30.0, shifted.origin().z, DELTA);
		assertEquals(15.0, shifted.originOpposite().x, DELTA);
	}

	@Test
	public void testEquals_sameBounds() {
		final BoundingBox bb1 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final BoundingBox bb2 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertEquals(bb1, bb2);
	}

	@Test
	public void testEquals_differentBounds() {
		final BoundingBox bb1 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final BoundingBox bb2 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(11, 10, 10)
		));
		assertNotEquals(bb1, bb2);
	}

	@Test
	public void testHashCode_equalObjects() {
		final BoundingBox bb1 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		final BoundingBox bb2 = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 10, 10)
		));
		assertEquals(bb1.hashCode(), bb2.hashCode());
	}

	@Test
	public void testClone() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(1, 2, 3),
			new PointInImage(4, 5, 6)
		));
		bb.setSpacing(0.5, 0.5, 1.0, "um");
		final BoundingBox clone = bb.clone();
		assertNotSame(bb, clone);
		assertEquals(bb, clone);
	}

	@Test
	public void testUnscaledOrigin() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(2, 4, 6),
			new PointInImage(10, 10, 10)
		));
		bb.setSpacing(2, 2, 2, "um");
		final PointInImage unscaled = bb.unscaledOrigin();
		assertEquals(1.0, unscaled.x, DELTA);
		assertEquals(2.0, unscaled.y, DELTA);
		assertEquals(3.0, unscaled.z, DELTA);
	}

	@Test
	public void testUnscaledOriginOpposite() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(10, 20, 30)
		));
		bb.setSpacing(2, 4, 6, "um");
		final PointInImage unscaled = bb.unscaledOriginOpposite();
		assertEquals(5.0, unscaled.x, DELTA);
		assertEquals(5.0, unscaled.y, DELTA);
		assertEquals(5.0, unscaled.z, DELTA);
	}

	@Test
	public void testToString_notNull() {
		final BoundingBox bb = new BoundingBox(Arrays.asList(
			new PointInImage(0, 0, 0),
			new PointInImage(1, 1, 1)
		));
		assertNotNull(bb.toString());
	}
}
