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

package sc.fiji.snt.analysis;

import static org.junit.Assert.*;

import java.awt.Color;
import java.awt.geom.Rectangle2D;

import org.junit.Test;

import ij.gui.EllipseRoi;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.TextRoi;
import sc.fiji.snt.util.BigDataUtils.LevelInfo;

/**
 * Tests for {@link RoiConverter#scale(Roi, LevelInfo, LevelInfo)}
 */
public class RoiConverterScaleTest {

	private static final double EPS = 1e-6;

	// Full resolution, and a 2x downsampled level with the half-voxel origin shift of N5/OME-Zarr pyramids
	private static final LevelInfo L0 = new LevelInfo(0, new long[] { 1000, 1000, 100 }, new double[] { 1, 1, 2 },
			new double[] { 0, 0, 0 });
	private static final LevelInfo L1 = new LevelInfo(1, new long[] { 500, 500, 100 }, new double[] { 2, 2, 2 },
			new double[] { 0.5, 0.5, 0 });

	private static void assertBounds(final Roi roi, final double x, final double y, final double w, final double h) {
		final Rectangle2D.Double b = roi.getFloatBounds();
		assertEquals("x", x, b.x, EPS);
		assertEquals("y", y, b.y, EPS);
		assertEquals("width", w, b.width, EPS);
		assertEquals("height", h, b.height, EPS);
	}

	@Test
	public void testNull() {
		assertNull(RoiConverter.scale(null, L0, L1));
	}

	@Test
	public void testRectangle() {
		final Roi scaled = RoiConverter.scale(new Roi(100, 50, 40, 20), L0, L1);
		assertEquals(Roi.RECTANGLE, scaled.getType());
		// x1 = (100 * 1 + 0 - 0.5) / 2
		assertBounds(scaled, 49.75, 24.75, 20, 10);
	}

	@Test
	public void testRoundTrip() {
		final Roi roi = new Roi(100, 50, 40, 20);
		assertBounds(RoiConverter.scale(RoiConverter.scale(roi, L0, L1), L1, L0), 100, 50, 40, 20);
	}

	@Test
	public void testSameLevelReturnsCopy() {
		final Roi roi = new Roi(10, 20, 30, 40);
		final Roi copy = RoiConverter.scale(roi, L0, L0);
		assertNotSame(roi, copy);
		assertBounds(copy, 10, 20, 30, 40);
	}

	@Test
	public void testOriginalIsNotModified() {
		final Roi roi = new Roi(100, 50, 40, 20);
		RoiConverter.scale(roi, L0, L1);
		assertBounds(roi, 100, 50, 40, 20);
	}

	@Test
	public void testAnisotropicSpacing() {
		final LevelInfo aniso = new LevelInfo(1, new long[] { 500, 250, 100 }, new double[] { 2, 4, 2 },
				new double[] { 0, 0, 0 });
		assertBounds(RoiConverter.scale(new Roi(100, 100, 40, 40), L0, aniso), 50, 25, 20, 10);
	}

	@Test
	public void testOval() {
		final Roi scaled = RoiConverter.scale(new OvalRoi(10, 10, 30, 30), L0, L1);
		assertEquals(Roi.OVAL, scaled.getType());
		assertBounds(scaled, 4.75, 4.75, 15, 15);
	}

	@Test
	public void testPolygonKeepsType() {
		final float[] xs = { 0, 10, 10 };
		final float[] ys = { 0, 0, 10 };
		final Roi scaled = RoiConverter.scale(new PolygonRoi(xs, ys, 3, Roi.POLYGON), L0, L1);
		assertEquals(Roi.POLYGON, scaled.getType());
		assertBounds(scaled, -0.25, -0.25, 5, 5);
	}

	@Test
	public void testPolylineKeepsType() {
		final float[] xs = { 0, 10, 20 };
		final float[] ys = { 0, 10, 0 };
		final Roi scaled = RoiConverter.scale(new PolygonRoi(xs, ys, 3, Roi.POLYLINE), L0, L1);
		assertEquals(Roi.POLYLINE, scaled.getType());
		assertEquals(3, scaled.getFloatPolygon().npoints);
	}

	@Test
	public void testLine() {
		final Roi scaled = RoiConverter.scale(new Line(0, 0, 10, 20), L0, L1);
		assertEquals(Roi.LINE, scaled.getType());
		assertBounds(scaled, -0.25, -0.25, 5, 10);
	}

	@Test
	public void testPoints() {
		final PointRoi roi = new PointRoi(new float[] { 10, 20 }, new float[] { 30, 40 });
		roi.setSize(3);
		final Roi scaled = RoiConverter.scale(roi, L0, L1);
		assertTrue(scaled instanceof PointRoi);
		assertEquals(3, ((PointRoi) scaled).getSize());
		final var fp = scaled.getFloatPolygon();
		assertEquals(2, fp.npoints);
		assertEquals(4.75, fp.xpoints[0], EPS);
		assertEquals(14.75, fp.ypoints[0], EPS);
		assertEquals(9.75, fp.xpoints[1], EPS);
		assertEquals(19.75, fp.ypoints[1], EPS);
	}

	@Test
	public void testEllipse() {
		final Roi roi = new EllipseRoi(0, 0, 20, 10, 0.5);
		final Rectangle2D.Double b0 = roi.getFloatBounds();
		final Rectangle2D.Double b1 = RoiConverter.scale(roi, L0, L1).getFloatBounds();
		assertEquals(b0.width / 2, b1.width, 1e-3);
		assertEquals(b0.height / 2, b1.height, 1e-3);
	}

	@Test
	public void testComposite() {
		final ShapeRoi roi = new ShapeRoi(new Roi(100, 50, 40, 20)).or(new ShapeRoi(new OvalRoi(130, 60, 30, 30)));
		final Roi scaled = RoiConverter.scale(roi, L0, L1);
		assertEquals(Roi.COMPOSITE, scaled.getType());
		final Rectangle2D.Double b = scaled.getFloatBounds();
		assertTrue(b.x <= 49.75 && b.x > 48.75);
		assertTrue(b.width >= 30 && b.width < 32);
		final Roi back = RoiConverter.scale(scaled, L1, L0);
		assertBounds(back, 100, 50, 60, 40);
	}

	@Test
	public void testCornerDiameter() {
		final Roi scaled = RoiConverter.scale(new Roi(0, 0, 20, 20, 10), L0, L1);
		assertEquals(5, scaled.getCornerDiameter());
	}

	@Test
	public void testAttributesAndPositionAreCopied() {
		final Roi roi = new Roi(100, 50, 40, 20);
		roi.setName("roi");
		roi.setStrokeColor(Color.RED);
		roi.setPosition(2, 3, 4);
		final Roi scaled = RoiConverter.scale(roi, L0, L1);
		assertEquals("roi", scaled.getName());
		assertEquals(Color.RED, scaled.getStrokeColor());
		assertTrue(scaled.hasHyperStackPosition());
		assertEquals(2, scaled.getCPosition());
		assertEquals(3, scaled.getZPosition());
		assertEquals(4, scaled.getTPosition());
	}

	@Test
	public void testUnassignedPositionStaysUnassigned() {
		final Roi scaled = RoiConverter.scale(new Roi(0, 0, 10, 10), L0, L1);
		assertFalse(scaled.hasHyperStackPosition());
		assertEquals(0, scaled.getPosition());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testTextRoiNotSupported() {
		RoiConverter.scale(new TextRoi(0, 0, "text"), L0, L1);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidSpacing() {
		final LevelInfo bad = new LevelInfo(1, new long[] { 1, 1, 1 }, new double[] { 0, 1, 1 }, new double[3]);
		RoiConverter.scale(new Roi(0, 0, 10, 10), L0, bad);
	}
}
