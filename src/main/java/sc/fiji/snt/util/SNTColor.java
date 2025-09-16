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

import org.scijava.util.ColorRGB;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

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
	 * Re-assigns an AWT color.
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
		if (!(obj instanceof SNTColor other)) {
			return false;
		}
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

	@Override
	public String toString() {
		return colorToString(color);
	}

	/**
	 * Returns the color encoded as hex string with the format #rrggbbaa.
	 *
	 * @param color the input AWT color
	 * @return the converted string
	 */
	public static String colorToString(final Color color) {
		if (color == null) return "null color";
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
		final ColorRGB color = ColorRGB.fromHTMLColor(hex.toLowerCase()); // backwards compatibility for v4.2.0 that used capitalized strings
		if (color != null)
			return new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
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
	 *         because the RGB space is not linear. Color.BLACK is returned if all
	 *         colors in input collection are null;
	 */
	public static Color average(final Collection<Color> colors) {
		if (colors == null || colors.isEmpty()) {
			return Color.BLACK;
		}
		return new Color(
				(int) colors.stream().filter(Objects::nonNull).mapToInt(Color::getRed).average().orElse(0),
				(int) colors.stream().filter(Objects::nonNull).mapToInt(Color::getGreen).average().orElse(0),
				(int) colors.stream().filter(Objects::nonNull).mapToInt(Color::getBlue).average().orElse(0),
				(int) colors.stream().filter(Objects::nonNull).mapToInt(Color::getAlpha).average().orElse(0)
		);
	}

	private static Color average(final Color... colors) {
		return average(Arrays.asList(colors));
	}

	private static Color previousNonNull(Color[] array, int index) {
		for (int i = index - 1; i >= 0; i--) {
			if (array[i] != null)
				return array[i];
		}
		return null;
	}

	private static Color nextNonNull(Color[] array, int index) {
		for (int i = index + 1; i < array.length; i++) {
			if (array[i] != null)
				return array[i];
		}
		return null;
	}

	/**
	 * Replaces null colors in an array with the average of flanking non-null colors.
	 *
	 * @param colors the color array
	 */
	public static void interpolateNullEntries(final Color[] colors) {
		if (colors != null) {
			for (int i = 0; i < colors.length; i++) {
				if (colors[i] == null) {
					// black if both flanking colors are null
					// left flanking color if right flanking color is null
					// right flanking color if left flanking color is null
					// average of left and right flanking colors if both non-null
					colors[i] = average(previousNonNull(colors, i), nextNonNull(colors, i));
				}
			}
		}
	}

	/**
	 * Adds an alpha component to an AWT color.
	 *
	 * @param c the input color
	 * @param percent alpha value in percentage
	 * @return the color with an alpha component
	 */
	public static Color alphaColor(final Color c, final double percent) {
		return (c == null) ? null : new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(
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

	/**
	 * Returns distinct colors based on Kenneth Kelly's 22 colors of maximum
	 * contrast (black and white excluded). More details on this
	 * <a href="https://stackoverflow.com/a/4382138">SO discussion</a>
	 *
	 * @param nColors    the number of colors to be retrieved
	 * @param excludedHue an optional string defining a hue to be excluded. Either 'red', 'green', 'blue', or 'dim'
	 * @return the maximum contrast colors
	 */
	public static ColorRGB[] getDistinctColors(final int nColors, final String excludedHue) {
		ColorRGB[] kColors = switch (excludedHue.toLowerCase()) {
            case "red" -> KELLY_COLORS_NO_RED;
            case "green" -> KELLY_COLORS_NO_GREEN;
            case "blue" -> KELLY_COLORS_NO_BLUE;
            case "dim" -> KELLY_COLORS_NO_DIM;
            default -> KELLY_COLORS;
        };
        final ColorRGB[] colors = Arrays.copyOf(kColors, nColors);
		for (int last = kColors.length; last != 0 && last < nColors; last <<= 1) {
			System.arraycopy(colors, 0, colors, last, Math.min(last << 1, nColors) -
					last);
		}
		return colors;
	}

	public static Color[] getDistinctColorsAWT(final int nColors) {
		final Color[] colors = new Color[nColors];
		final ColorRGB[] colorsRGB = getDistinctColors(nColors);
		for (int i = 0; i < nColors; i++) {
			colors[i] = new Color(colorsRGB[i].getARGB());
		}
		return colors;
	}

	private static final ColorRGB[] KELLY_COLORS = {
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

	private static final ColorRGB[] KELLY_COLORS_NO_DIM = {
			// See https://stackoverflow.com/a/4382138
			ColorRGB.fromHTMLColor("#FFB300"), // Vivid Yellow
			ColorRGB.fromHTMLColor("#803E75"), // Strong Purple
			ColorRGB.fromHTMLColor("#FF6800"), // Vivid Orange
			ColorRGB.fromHTMLColor("#C10020"), // Vivid Red
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

	private static final ColorRGB[] KELLY_COLORS_NO_BLUE = {
			ColorRGB.fromHTMLColor("#FFB300"), // Vivid Yellow
			ColorRGB.fromHTMLColor("#803E75"), // Strong Purple
			ColorRGB.fromHTMLColor("#FF6800"), // Vivid Orange
			ColorRGB.fromHTMLColor("#C10020"), // Vivid Red
			ColorRGB.fromHTMLColor("#CEA262"), // Grayish Yellow
			ColorRGB.fromHTMLColor("#817066"), // Medium Gray
			ColorRGB.fromHTMLColor("#007D34"), // Vivid Green
			ColorRGB.fromHTMLColor("#F6768E"), // Strong Purplish Pink
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
	private static final ColorRGB[] KELLY_COLORS_NO_GREEN = {
			ColorRGB.fromHTMLColor("#FFB300"), // Vivid Yellow
			ColorRGB.fromHTMLColor("#803E75"), // Strong Purple
			ColorRGB.fromHTMLColor("#FF6800"), // Vivid Orange
			ColorRGB.fromHTMLColor("#A6BDD7"), // Very Light Blue
			ColorRGB.fromHTMLColor("#C10020"), // Vivid Red
			ColorRGB.fromHTMLColor("#CEA262"), // Grayish Yellow
			ColorRGB.fromHTMLColor("#817066"), // Medium Gray
			ColorRGB.fromHTMLColor("#F6768E"), // Strong Purplish Pink
			ColorRGB.fromHTMLColor("#00538A"), // Strong Blue
			ColorRGB.fromHTMLColor("#FF7A5C"), // Strong Yellowish Pink
			ColorRGB.fromHTMLColor("#53377A"), // Strong Violet
			ColorRGB.fromHTMLColor("#FF8E00"), // Vivid Orange Yellow
			ColorRGB.fromHTMLColor("#B32851"), // Strong Purplish Red
			ColorRGB.fromHTMLColor("#F4C800"), // Vivid Greenish Yellow
			ColorRGB.fromHTMLColor("#7F180D"), // Strong Reddish Brown
			ColorRGB.fromHTMLColor("#593315"), // Deep Yellowish Brown
			ColorRGB.fromHTMLColor("#F13A13"), // Vivid Reddish Orange
	};
	private static final ColorRGB[] KELLY_COLORS_NO_RED = {
			ColorRGB.fromHTMLColor("#FFB300"), // Vivid Yellow
			ColorRGB.fromHTMLColor("#803E75"), // Strong Purple
			ColorRGB.fromHTMLColor("#FF6800"), // Vivid Orange
			ColorRGB.fromHTMLColor("#A6BDD7"), // Very Light Blue
			ColorRGB.fromHTMLColor("#CEA262"), // Grayish Yellow
			ColorRGB.fromHTMLColor("#817066"), // Medium Gray
			ColorRGB.fromHTMLColor("#007D34"), // Vivid Green
			ColorRGB.fromHTMLColor("#F6768E"), // Strong Purplish Pink
			ColorRGB.fromHTMLColor("#00538A"), // Strong Blue
			ColorRGB.fromHTMLColor("#FF7A5C"), // Strong Yellowish Pink
			ColorRGB.fromHTMLColor("#53377A"), // Strong Violet
			ColorRGB.fromHTMLColor("#FF8E00"), // Vivid Orange Yellow
			ColorRGB.fromHTMLColor("#F4C800"), // Vivid Greenish Yellow
			ColorRGB.fromHTMLColor("#7F180D"), // Strong Reddish Brown
			ColorRGB.fromHTMLColor("#93AA00"), // Vivid Yellowish Green
			ColorRGB.fromHTMLColor("#593315"), // Deep Yellowish Brown
			ColorRGB.fromHTMLColor("#F13A13"), // Vivid Reddish Orange
			ColorRGB.fromHTMLColor("#232C16") // Dark Olive Green
	};
}
