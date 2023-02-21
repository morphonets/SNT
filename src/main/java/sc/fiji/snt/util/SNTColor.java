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

import java.awt.Color;
import java.util.Arrays;
import java.util.Collection;

import org.scijava.util.ColorRGB;

/**
 * A simple class for handling Colors including the ability to map an AWT Color
 * to a SWC type integer tag.
 * 
 * @author Tiago Ferreira
 */
public class SNTColor {

	protected static final int SWC_TYPE_IGNORED = -1;
	private Color color;
	private int swcType;

	/**
	 * Instantiates a new SNT color.
	 *
	 * @param color the AWT color
	 * @param swcType the SWC type integer flag to be associated with color
	 */
	public SNTColor(final Color color, final int swcType) {
		this.color = color;
		this.swcType = swcType;
	}

	/**
	 * Instantiates a new SNT color without SWC type association
	 *
	 * @param color the AWT color
	 */
	public SNTColor(final Color color) {
		this(color, SWC_TYPE_IGNORED);
	}

	/**
	 * Retrieves the AWT color
	 *
	 * @return the AWT color
	 */
	public Color color() {
		return color;
	}

	/**
	 * Retrieves the SWC type
	 *
	 * @return the SWC type integer flag
	 */
	public int type() {
		return swcType;
	}

	/**
	 * Checks if an SWC type has been defined.
	 *
	 * @return true, if an SWC integer flag has been specified
	 */
	public boolean isTypeDefined() {
		return swcType != SWC_TYPE_IGNORED;
	}

	/**
	 * Re-assigns a AWT color.
	 *
	 * @param color the new color
	 */
	public void setAWTColor(final Color color) {
		this.color = color;
	}

	/**
	 * Re-assigns a SWC type integer flag
	 *
	 * @param swcType the new SWC type
	 */
	public void setSWCType(final int swcType) {
		this.swcType = swcType;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((color == null) ? 0 : color.hashCode());
		result = prime * result + swcType;
		return result;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof SNTColor)) {
			return false;
		}
		final SNTColor other = (SNTColor) obj;
		if (color == null) {
			if (other.color != null) {
				return false;
			}
		}
		else if (!color.equals(other.color)) {
			return false;
		}
		return swcType == other.swcType;
	}

	/**
	 * Returns the color encoded as hex string with the format #rrggbbaa.
	 *
	 * @param color the input AWT color
	 * @return the converted string
	 */
	public static String colorToString(final Color color) {
		if (color == null) throw new IllegalArgumentException(
			"Cannot convert null object");
		return String.format("#%02x%02x%02x%02x", color.getRed(), color.getGreen(),
			color.getBlue(), color.getAlpha());
	}

	/**
	 * Returns an AWT Color from a (#)RRGGBB(AA) hex string.
	 *
	 * @param hex the input string
	 * @return the converted AWT color
	 */
	public static Color stringToColor(final String hex) {
		if (hex.length() < 6) throw new IllegalArgumentException(
			"Unsupported format. Only (#)RRGGBB(AA) allowed");
		final String input = hex.charAt(0) == '#' ? hex.substring(1) : hex;
		final int r = Integer.valueOf(input.substring(0, 2), 16);
		final int g = Integer.valueOf(input.substring(2, 4), 16);
		final int b = Integer.valueOf(input.substring(4, 6), 16);
		final int a = (hex.length() < 8) ? 255 : Integer.valueOf(hex.substring(6,
			8), 16);
		return new Color(r / 255f, g / 255f, b / 255f, a / 255f);
	}

	/**
	 * Averages a collection of colors
	 *
	 * @param colors the colors to be averaged
	 * @return the averaged color. Note that an average will never be accurate
	 *         because the RGB space is not linear
	 */
	public static Color average(final Collection<Color> colors) {
		if (colors == null || colors.isEmpty()) return null;
		int tR = 0;
		int tG = 0;
		int tB = 0;
		int tA = 0;
		for (final Color c : colors) {
			if (c == null) continue;
			tR += c.getRed();
			tG += c.getGreen();
			tB += c.getBlue();
			tA += c.getAlpha();
		}
		final int n = colors.size();
		return new Color(tR / n, tG / n, tB / n, tA / n);
	}

	/**
	 * Adds an alpha component to a AWT color.
	 *
	 * @param c the input color
	 * @param percent alpha value in percentage
	 * @return the color with an alpha component
	 */
	public static Color alphaColor(final Color c, final double percent) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(
			percent / 100 * 255));
	}

	/**
	 * Returns a suitable 'contrast' color.
	 *
	 * @param c the input color
	 * @return Either white or black, as per hue of input color.
	 */
	public static Color contrastColor(final Color c) {
		final int intensity = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
		return intensity < 128 ? Color.WHITE : Color.BLACK;
	}

	/**
	 * Returns distinct colors based on Kenneth Kelly's 22 colors of maximum
	 * contrast (black and white excluded). More details on this
	 * <a href="https://stackoverflow.com/a/4382138">SO discussion</a>
	 *
	 * @param nColors the number of colors to be retrieved.
	 * @return the maximum contrast colors
	 */
	public static ColorRGB[] getDistinctColors(final int nColors) {
		if (nColors < KELLY_COLORS.length) {
			return Arrays.copyOfRange(KELLY_COLORS, 0, nColors);
		}
		final ColorRGB[] colors = Arrays.copyOf(KELLY_COLORS, nColors);
		for (int last = KELLY_COLORS.length; last != 0 && last < nColors; last <<=
			1)
		{
			System.arraycopy(colors, 0, colors, last, Math.min(last << 1, nColors) -
				last);
		}
		return colors;
	}

	private static ColorRGB[] KELLY_COLORS = {
		// See https://stackoverflow.com/a/4382138
		ColorRGB.fromHTMLColor("#FFB300"), // Vivid Yellow
		ColorRGB.fromHTMLColor("#803E75"), // Strong Purple
		ColorRGB.fromHTMLColor("#FF6800"), // Vivid Orange
		ColorRGB.fromHTMLColor("#A6BDD7"), // Very Light Blue
		ColorRGB.fromHTMLColor("#C10020"), // Vivid Red
		ColorRGB.fromHTMLColor("#CEA262"), // Grayish Yellow
		ColorRGB.fromHTMLColor("#817066"), // Medium Gray
		ColorRGB.fromHTMLColor("#007D34"), // Vivid Green
		ColorRGB.fromHTMLColor("#F6768E"), // Strong Purplish Pink
		ColorRGB.fromHTMLColor("#00538A"), // Strong Blue
		ColorRGB.fromHTMLColor("#FF7A5C"), // Strong Yellowish Pink
		ColorRGB.fromHTMLColor("#53377A"), // Strong Violet
		ColorRGB.fromHTMLColor("#FF8E00"), // Vivid Orange Yellow
		ColorRGB.fromHTMLColor("#B32851"), // Strong Purplish Red
		ColorRGB.fromHTMLColor("#F4C800"), // Vivid Greenish Yellow
		ColorRGB.fromHTMLColor("#7F180D"), // Strong Reddish Brown
		ColorRGB.fromHTMLColor("#93AA00"), // Vivid Yellowish Green
		ColorRGB.fromHTMLColor("#593315"), // Deep Yellowish Brown
		ColorRGB.fromHTMLColor("#F13A13"), // Vivid Reddish Orange
		ColorRGB.fromHTMLColor("#232C16") // Dark Olive Green
	};

}
