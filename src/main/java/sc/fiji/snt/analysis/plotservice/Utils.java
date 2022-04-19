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

import org.scijava.plot.NumberAxis;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.block.BlockBorder;
import org.jfree.chart.plot.Plot;

import java.awt.*;
import java.util.Objects;

/**
 * @author Matthias Arzt
 * @author Tiago Ferreira
 */
class Utils {

	static JFreeChart setupJFreeChart(String title, Plot plot) {
		JFreeChart chart = new JFreeChart(plot);
		chart.setTitle(title);
		chart.setBackgroundPaint(Color.WHITE);
		chart.getLegend().setFrame(BlockBorder.NONE);
		return chart;
	}

	static ValueAxis getJFreeChartAxis(NumberAxis v) {
		if(v.isLogarithmic())
			return getJFreeChartLogarithmicAxis(v);
		else
			return getJFreeCharLinearAxis(v);
	}

	static ValueAxis getJFreeChartLogarithmicAxis(NumberAxis v) {
		LogAxis axis = new LogAxis(v.getLabel());
		switch (v.getRangeStrategy()) {
			case MANUAL:
				axis.setRange(v.getMin(), v.getMax());
				break;
			default:
				axis.setAutoRange(true);
		}
		return axis;
	}

	static ValueAxis getJFreeCharLinearAxis(NumberAxis v) {
		org.jfree.chart.axis.NumberAxis axis = new org.jfree.chart.axis.NumberAxis(v.getLabel());
		switch(v.getRangeStrategy()) {
			case MANUAL:
				axis.setRange(v.getMin(), v.getMax());
				break;
			case AUTO:
				axis.setAutoRange(true);
				axis.setAutoRangeIncludesZero(false);
				break;
			case AUTO_INCLUDE_ZERO:
				axis.setAutoRange(true);
				axis.setAutoRangeIncludesZero(true);
				break;
			default:
				axis.setAutoRange(true);
		}
		return axis;
	}

	static class SortedLabelFactory {
		private int n;
		SortedLabelFactory() { n = 0; }
		SortedLabel newLabel(Object label) { return new SortedLabel(n++, label); }
	}

	static class SortedLabel implements Comparable<SortedLabel> {
		SortedLabel(final int id, final Object label) {this.label = Objects.requireNonNull(label); this.id = id; }
		@Override public String toString() { return label.toString(); }
		@Override public int compareTo(SortedLabel o) { return Integer.compare(id, o.id); }
		public Object getLabel() { return label; }
		private final Object label;
		private final int id;
	}

}
