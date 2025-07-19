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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.net.URL;
import java.util.Collection;
import java.util.Map;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;

import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Tree;

/**
 * Parent class for ColorMappers.
 *
 * @author Tiago Ferreira
 */
public class ColorMapper {

    @Parameter
	protected LUTService lutService;
    
    protected Map<String, URL> luts;
	protected ColorTable colorTable;
	protected boolean integerScale;
	protected double min = Double.MAX_VALUE;
	protected double max = Double.MIN_VALUE;
	private Color nanColor;

	/**
	 * Sets up the color mapping for the specified measurement using the given color table.
	 * <p>
	 * This method configures the ColorMapper to use the specified measurement and
	 * color table for mapping values to colors. The actual mapping implementation
	 * is left to extending classes.
	 * </p>
	 *
	 * @param measurement the measurement to be mapped
	 * @param colorTable the color table to use for mapping
	 * @throws IllegalArgumentException if colorTable or measurement is null
	 */
	public void map(final String measurement, final ColorTable colorTable) {
		if (colorTable == null) throw new IllegalArgumentException(
			"colorTable cannot be null");
		if (measurement == null) throw new IllegalArgumentException(
			"measurement cannot be null");
		this.colorTable = colorTable;
		// Implementation left to extending classes
	}

	/**
	 * Gets the color used for NaN (Not a Number) values.
	 * <p>
	 * Returns the color that will be used when mapping NaN values, which
	 * cannot be mapped to the regular color scale.
	 * </p>
	 *
	 * @return the color for NaN values
	 */
	public Color getNaNColor() {
		return nanColor;
	}

	/**
	 * Sets the color to use for NaN (Not a Number) values.
	 * <p>
	 * Specifies the color that should be used when mapping NaN values,
	 * which cannot be mapped to the regular color scale.
	 * </p>
	 *
	 * @param nanColor the color to use for NaN values
	 */
	public void setNaNColor(final Color nanColor) {
		this.nanColor = nanColor;
	}

	/**
	 * Gets the color corresponding to the specified mapped value.
	 * <p>
	 * Maps the given value to a color using the current color table and
	 * mapping bounds. Returns the NaN color if the value is NaN.
	 * </p>
	 *
	 * @param mappedValue the value to map to a color
	 * @return the corresponding color
	 */
	public Color getColor(final double mappedValue) {
		if (Double.isNaN(mappedValue))
			return getNaNColor();
		final int idx = getColorTableIdx(mappedValue);
		return new Color(colorTable.get(ColorTable.RED, idx), colorTable.get(
			ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
	}

	/**
	 * Gets the color corresponding to the specified mapped value as ColorRGB.
	 *
	 * @param mappedValue the value to map to a color
	 * @return the corresponding ColorRGB
	 * @see #getColor(double)
	 */
	public ColorRGB getColorRGB(final double mappedValue) {
		final Color color = getColor(mappedValue);
		return new ColorRGB(color.getRed(), color.getGreen(), color.getBlue());
	}

	private int getColorTableIdx(final double mappedValue) {
		final int idx;
		if (mappedValue <= min) idx = 0;
		else if (mappedValue > max) idx = colorTable.getLength() - 1;
		else idx = (int) Math.round((colorTable.getLength() - 1) * (mappedValue -
			min) / (max - min));
		return idx;
	}

	/**
	 * Sets the LUT mapping bounds.
	 *
	 * @param min the mapping lower bound (i.e., the highest measurement value for
	 *          the LUT scale). It is automatically calculated (the default) when
	 *          set to Double.NaN
	 * @param max the mapping upper bound (i.e., the highest measurement value for
	 *          the LUT scale).It is automatically calculated (the default) when
	 *          set to Double.NaN.
	 */
	public void setMinMax(final double min, final double max) {
		if (!Double.isNaN(min) && !Double.isNaN(max) && min > max)
			throw new IllegalArgumentException("min > max");
		this.min = (Double.isNaN(min)) ? Double.MAX_VALUE : min;
		this.max = (Double.isNaN(max)) ? Double.MIN_VALUE : max;
	}

	/**
	 * Checks if the color mapping uses an integer scale.
	 * <p>
	 * Returns true if the mapping is configured to use discrete integer
	 * values rather than continuous floating-point values.
	 * </p>
	 *
	 * @return true if using integer scale, false for continuous scale
	 */
	public boolean isIntegerScale() {
		return integerScale;
	}

	/**
	 * Returns the mapping bounds
	 *
	 * @return a two-element array with current {minimum, maximum} mapping bounds
	 */
	public double[] getMinMax() {
		return new double[] { min, max };
	}

	/**
	 * Gets the current color table used for mapping.
	 * <p>
	 * Returns the ColorTable instance that defines the color mapping
	 * from values to colors.
	 * </p>
	 *
	 * @return the current color table
	 */
	public ColorTable getColorTable() {
		return colorTable;
	}

	protected void initLuts() {
		if (luts == null) {
			if (lutService == null)
				SNTUtils.getContext().inject(this);
			luts = lutService.findLUTs();
		}
	}

	/**
	 * Removes color mapping from all paths in the specified tree.
	 * <p>
	 * Resets the color of all paths in the tree to their default state,
	 * effectively removing any color mapping that was previously applied.
	 * </p>
	 *
	 * @param tree the tree whose paths should have color mapping removed
	 */
	public static void unMap(final Tree tree) {
		unMap(tree.list());
	}

	/**
	 * Removes color mapping from the specified collection of paths.
	 * <p>
	 * Resets the color of all paths in the collection to their default state,
	 * effectively removing any color mapping that was previously applied.
	 * This includes both path-level and node-level color assignments.
	 * </p>
	 *
	 * @param paths the collection of paths to have color mapping removed
	 */
	public static void unMap(final Collection<Path> paths) {
		paths.forEach(p -> {
			p.setColor((java.awt.Color)null);
			p.setNodeColors(null);
		});
	}
}
