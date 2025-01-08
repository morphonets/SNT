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

	public void map(final String measurement, final ColorTable colorTable) {
		if (colorTable == null) throw new IllegalArgumentException(
			"colorTable cannot be null");
		if (measurement == null) throw new IllegalArgumentException(
			"measurement cannot be null");
		this.colorTable = colorTable;
		// Implementation left to extending classes
	}

	public Color getNaNColor() {
		return nanColor;
	}

	public void setNaNColor(final Color nanColor) {
		this.nanColor = nanColor;
	}

	public Color getColor(final double mappedValue) {
		if (Double.isNaN(mappedValue))
			return getNaNColor();
		final int idx = getColorTableIdx(mappedValue);
		return new Color(colorTable.get(ColorTable.RED, idx), colorTable.get(
			ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
	}

	public ColorRGB getColorRGB(final double mappedValue) {
		final int idx = getColorTableIdx(mappedValue);
		return new ColorRGB(colorTable.get(ColorTable.RED, idx), colorTable.get(
			ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
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

	public static void unMap(final Tree tree) {
		unMap(tree.list());
	}

	public static void unMap(final Collection<Path> paths) {
		paths.forEach(p -> {
			p.setColor((java.awt.Color)null);
			p.setNodeColors(null);
		});
	}
}
