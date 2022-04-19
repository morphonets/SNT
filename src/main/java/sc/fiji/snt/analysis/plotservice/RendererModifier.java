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

package sc.fiji.snt.analysis.plotservice;

import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.SeriesStyle;
import org.jfree.chart.renderer.AbstractRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.scijava.ui.awt.AWTColors;
import org.scijava.util.ColorRGB;

/**
 * @author Matthias Arzt
 */
class RendererModifier {

	final AbstractRenderer renderer;

	private RendererModifier(AbstractRenderer renderer) {
		this.renderer = renderer;
	}

	static public RendererModifier wrap(AbstractRenderer renderer) {
		return new RendererModifier(renderer);
	}

	public void setSeriesStyle(int index, SeriesStyle style) {
		if(style == null)
			return;
		setSeriesColor(index, style.getColor());
		setSeriesLineStyle(index, style.getLineStyle());
		setSeriesMarkerStyle(index, style.getMarkerStyle());
	}

	public void setSeriesColor(int index, ColorRGB color) {
		if (color == null)
			return;
		renderer.setSeriesPaint(index, AWTColors.getColor(color));
	}

	public void setSeriesLineStyle(int index, LineStyle style) {
		AwtLineStyles line = AwtLineStyles.getInstance(style);
		setSeriesLinesVisible(index, line.isVisible());
		renderer.setSeriesStroke(index, line.getStroke());
	}

	public void setSeriesMarkerStyle(int index, MarkerStyle style) {
		AwtMarkerStyles marker = AwtMarkerStyles.getInstance(style);
		setSeriesShapesVisible(index, marker.isVisible());
		setSeriesShapesFilled(index, marker.isFilled());
		renderer.setSeriesShape(index, marker.getShape());
	}

	private void setSeriesLinesVisible(int index, boolean visible) {
		if(renderer instanceof LineAndShapeRenderer)
			((LineAndShapeRenderer) renderer).setSeriesLinesVisible(index, visible);
		if(renderer instanceof XYLineAndShapeRenderer)
			((XYLineAndShapeRenderer) renderer).setSeriesLinesVisible(index, visible);
	}

	private void setSeriesShapesVisible(int index, boolean visible) {
		if(renderer instanceof LineAndShapeRenderer)
			((LineAndShapeRenderer) renderer).setSeriesShapesVisible(index, visible);
		if(renderer instanceof XYLineAndShapeRenderer)
			((XYLineAndShapeRenderer) renderer).setSeriesShapesVisible(index, visible);
	}

	private void setSeriesShapesFilled(int index, boolean filled) {
		if(renderer instanceof LineAndShapeRenderer)
			((LineAndShapeRenderer) renderer).setSeriesShapesFilled(index, filled);
		if(renderer instanceof XYLineAndShapeRenderer)
			((XYLineAndShapeRenderer) renderer).setSeriesShapesFilled(index, filled);
	}

}