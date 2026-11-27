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

import java.awt.Color;
import java.util.Arrays;

import org.junit.Test;
import org.scijava.util.ColorRGB;

/**
 * Tests for {@link SNTColor}.
 */
public class SNTColorTest {

	private static final double DELTA = 1.0;

	// ---- SNTColor instance tests ----

	@Test
	public void testConstructor_withSWCType() {
		final SNTColor c = new SNTColor(Color.RED, 2);
		assertEquals(Color.RED, c.color());
		assertEquals(2, c.type());
		assertTrue(c.isTypeDefined());
	}

	@Test
	public void testConstructor_withoutSWCType() {
		final SNTColor c = new SNTColor(Color.BLUE);
		assertEquals(Color.BLUE, c.color());
		assertEquals(SNTColor.SWC_TYPE_IGNORED, c.type());
		assertFalse(c.isTypeDefined());
	}

	@Test
	public void testSetAWTColor() {
		final SNTColor c = new SNTColor(Color.RED);
		c.setAWTColor(Color.GREEN);
		assertEquals(Color.GREEN, c.color());
	}

	@Test
	public void testSetSWCType() {
		final SNTColor c = new SNTColor(Color.RED);
		c.setSWCType(3);
		assertEquals(3, c.type());
		assertTrue(c.isTypeDefined());
	}

	@Test
	public void testEquals_sameColorAndType() {
		final SNTColor c1 = new SNTColor(Color.RED, 2);
		final SNTColor c2 = new SNTColor(Color.RED, 2);
		assertEquals(c1, c2);
	}

	@Test
	public void testEquals_differentType() {
		final SNTColor c1 = new SNTColor(Color.RED, 2);
		final SNTColor c2 = new SNTColor(Color.RED, 3);
		assertNotEquals(c1, c2);
	}

	@Test
	public void testEquals_differentColor() {
		final SNTColor c1 = new SNTColor(Color.RED, 2);
		final SNTColor c2 = new SNTColor(Color.BLUE, 2);
		assertNotEquals(c1, c2);
	}

	@Test
	public void testHashCode_equalObjects() {
		final SNTColor c1 = new SNTColor(Color.RED, 2);
		final SNTColor c2 = new SNTColor(Color.RED, 2);
		assertEquals(c1.hashCode(), c2.hashCode());
	}

	// ---- colorToString tests ----

	@Test
	public void testColorToString_null() {
		assertEquals("null color", SNTColor.colorToString(null));
	}

	@Test
	public void testColorToString_awtColor() {
		final String result = SNTColor.colorToString(Color.RED);
		assertNotNull(result);
		assertTrue(result.startsWith("#"));
		assertEquals(9, result.length()); // #rrggbbaa
	}

	@Test
	public void testColorToString_awtColorBlack() {
		assertEquals("#000000ff", SNTColor.colorToString(Color.BLACK));
	}

	@Test
	public void testColorToString_awtColorWhite() {
		assertEquals("#ffffffff", SNTColor.colorToString(Color.WHITE));
	}

	@Test
	public void testColorToString_colorRGB() {
		final ColorRGB c = new ColorRGB(255, 0, 0);
		final String result = SNTColor.colorToString(c);
		assertNotNull(result);
		assertTrue(result.startsWith("#"));
	}

	// ---- fromHex tests ----

	@Test
	public void testFromHex_sixDigit() {
		final Color c = SNTColor.fromHex("#ff0000");
		assertEquals(255, c.getRed());
		assertEquals(0, c.getGreen());
		assertEquals(0, c.getBlue());
	}

	@Test
	public void testFromHex_eightDigit() {
		final Color c = SNTColor.fromHex("#ff000080");
		assertEquals(255, c.getRed());
		assertEquals(0, c.getGreen());
		assertEquals(0, c.getBlue());
		assertEquals(128, c.getAlpha(), DELTA);
	}

	@Test
	public void testFromHex_noHash() {
		final Color c = SNTColor.fromHex("00ff00");
		assertEquals(0, c.getRed());
		assertEquals(255, c.getGreen());
		assertEquals(0, c.getBlue());
	}

	@Test
	public void testFromHex_colorName() {
		// CSS / named color strings should also work
		final Color c = SNTColor.fromHex("blue");
		assertNotNull(c);
		assertEquals(0, c.getRed());
		// "blue" should have a non-zero blue channel
		assertTrue("Blue channel should be positive for 'blue'", c.getBlue() > 0);
	}

	@Test
	public void testFromString_sameAsFromHex() {
		final Color c1 = SNTColor.fromString("#aabbcc");
		final Color c2 = SNTColor.fromHex("#aabbcc");
		assertEquals(c1, c2);
	}

	// ---- average tests ----

	@Test
	public void testAverage_empty() {
		final Color avg = SNTColor.average(Arrays.asList());
		assertEquals(Color.BLACK, avg);
	}

	@Test
	public void testAverage_null() {
		final Color avg = SNTColor.average(null);
		assertEquals(Color.BLACK, avg);
	}

	@Test
	public void testAverage_singleColor() {
		final Color avg = SNTColor.average(Arrays.asList(new Color(100, 150, 200)));
		assertEquals(100, avg.getRed());
		assertEquals(150, avg.getGreen());
		assertEquals(200, avg.getBlue());
	}

	@Test
	public void testAverage_twoColors() {
		final Color avg = SNTColor.average(Arrays.asList(
			new Color(0, 0, 0),
			new Color(100, 200, 255)
		));
		assertEquals(50, avg.getRed());
		assertEquals(100, avg.getGreen());
		assertEquals(127, avg.getBlue(), DELTA);
	}

	@Test
	public void testAverage_withNullEntries() {
		// null entries in the collection should be skipped
		final Color avg = SNTColor.average(Arrays.asList(null, new Color(100, 0, 0), null));
		assertEquals(100, avg.getRed());
	}

	// ---- interpolateNullEntries tests ----

	@Test
	public void testInterpolateNullEntries_noNulls() {
		final Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
		SNTColor.interpolateNullEntries(colors);
		assertEquals(Color.RED, colors[0]);
		assertEquals(Color.GREEN, colors[1]);
		assertEquals(Color.BLUE, colors[2]);
	}

	@Test
	public void testInterpolateNullEntries_nullInMiddle() {
		final Color[] colors = {Color.BLACK, null, Color.WHITE};
		SNTColor.interpolateNullEntries(colors);
		assertNotNull(colors[1]);
		// The middle should be an average of black (0,0,0) and white (255,255,255)
		assertEquals(127, colors[1].getRed(), DELTA);
	}

	@Test
	public void testInterpolateNullEntries_nullArray() {
		// should not throw
		SNTColor.interpolateNullEntries(null);
	}

	@Test
	public void testInterpolateNullEntries_allNull() {
		final Color[] colors = {null, null, null};
		SNTColor.interpolateNullEntries(colors);
		// All flanking colors are null, so result is black
		for (final Color c : colors) {
			assertEquals(Color.BLACK, c);
		}
	}

	// ---- alphaColor tests ----

	@Test
	public void testAlphaColor_fullOpacity() {
		final Color result = SNTColor.alphaColor(Color.RED, 100.0);
		assertEquals(255, result.getAlpha());
		assertEquals(Color.RED.getRed(), result.getRed());
	}

	@Test
	public void testAlphaColor_halfOpacity() {
		final Color result = SNTColor.alphaColor(Color.RED, 50.0);
		assertEquals(128, result.getAlpha(), DELTA);
	}

	@Test
	public void testAlphaColor_transparent() {
		final Color result = SNTColor.alphaColor(Color.RED, 0.0);
		assertEquals(0, result.getAlpha());
	}

	@Test
	public void testAlphaColor_nullInput() {
		assertNull(SNTColor.alphaColor(null, 100.0));
	}

	// ---- contrastColor tests ----

	@Test
	public void testContrastColor_black() {
		assertEquals(Color.WHITE, SNTColor.contrastColor(Color.BLACK));
	}

	@Test
	public void testContrastColor_white() {
		assertEquals(Color.BLACK, SNTColor.contrastColor(Color.WHITE));
	}

	@Test
	public void testContrastColor_dark() {
		// Dark color should return white
		final Color dark = new Color(50, 50, 50);
		assertEquals(Color.WHITE, SNTColor.contrastColor(dark));
	}

	@Test
	public void testContrastColor_light() {
		// Light color should return black
		final Color light = new Color(200, 200, 200);
		assertEquals(Color.BLACK, SNTColor.contrastColor(light));
	}

	// ---- getDistinctColors tests ----

	@Test
	public void testGetDistinctColors_count() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(5);
		assertEquals(5, colors.length);
	}

	@Test
	public void testGetDistinctColors_moreThanKelly() {
		// Kelly has 20 colors; requesting more should repeat them
		final ColorRGB[] colors = SNTColor.getDistinctColors(25);
		assertEquals(25, colors.length);
		assertNotNull(colors[24]);
	}

	@Test
	public void testGetDistinctColors_single() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(1);
		assertEquals(1, colors.length);
		assertNotNull(colors[0]);
	}

	@Test
	public void testGetDistinctColorsHex_format() {
		final String[] hexColors = SNTColor.getDistinctColorsHex(3);
		assertEquals(3, hexColors.length);
		for (final String hex : hexColors) {
			assertNotNull(hex);
			assertTrue("Expected hex string: " + hex, hex.startsWith("#"));
		}
	}

	@Test
	public void testGetDistinctColorsAWT_count() {
		final Color[] colors = SNTColor.getDistinctColorsAWT(4);
		assertEquals(4, colors.length);
		for (final Color c : colors) {
			assertNotNull(c);
		}
	}

	@Test
	public void testGetDistinctColors_excludedHue_red() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(5, "red");
		assertEquals(5, colors.length);
	}

	@Test
	public void testGetDistinctColors_excludedHue_green() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(5, "green");
		assertEquals(5, colors.length);
	}

	@Test
	public void testGetDistinctColors_excludedHue_blue() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(5, "blue");
		assertEquals(5, colors.length);
	}

	@Test
	public void testGetDistinctColors_excludedHue_dim() {
		final ColorRGB[] colors = SNTColor.getDistinctColors(5, "dim");
		assertEquals(5, colors.length);
	}
}
