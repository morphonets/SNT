/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

package sc.fiji.snt.viewer;

import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;

import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;

class ColorTableMapper extends ColorMapper {

	private final ColorTable colorTable;

	public ColorTableMapper(final ColorTable colorTable, final double min,
		final double max)
	{
		super();
		this.colorTable = (colorTable == null) ? ColorTables.ICE : colorTable;
		this.min = min;
		this.max = max;
	}

	@Override
	public Color getColor(final double mappedValue) {
		final int idx;
		if (mappedValue <= min) idx = 0;
		else if (mappedValue > max) idx = colorTable.getLength() - 1;
		else idx = (int) Math.round((colorTable.getLength() - 1) * (mappedValue -
			min) / (max - min));
		return new Color(colorTable.get(ColorTable.RED, idx), colorTable.get(
			ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
	}

	@Override
	public double getMin() {
		return min;
	}

	@Override
	public double getMax() {
		return max;
	}

	@Override
	public void setMin(final double min) {
		this.min = min;
	}

	@Override
	public void setMax(final double max) {
		this.max = max;
	}

}
