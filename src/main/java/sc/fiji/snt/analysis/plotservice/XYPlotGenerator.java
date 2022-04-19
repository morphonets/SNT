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

import org.scijava.plot.SeriesStyle;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYPlotItem;
import org.scijava.plot.XYSeries;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeriesCollection;

import java.util.Collection;
import java.util.Iterator;

import static sc.fiji.snt.analysis.plotservice.Utils.*;

/**
 * @author Matthias Arzt
 * @author Tiago Ferreira
 */
class XYPlotGenerator {

	private final XYPlot xyPlot;

	private final SortedLabelFactory sortedLabelFactory = new SortedLabelFactory();

	private final org.jfree.chart.plot.XYPlot jfcPlot = new org.jfree.chart.plot.XYPlot();

	private final XYSeriesCollection jfcDataSet = new XYSeriesCollection();

	private final XYLineAndShapeRenderer jfcRenderer = new XYLineAndShapeRenderer();

	private XYPlotGenerator(XYPlot xyPlot) {
		this.xyPlot = xyPlot;
	}

	static JFreeChart run(XYPlot xyPlot) { return new XYPlotGenerator(xyPlot).getJFreeChart();
	}

	private JFreeChart getJFreeChart() {
		jfcPlot.setDataset(jfcDataSet);
		jfcPlot.setDomainAxis(getJFreeChartAxis(xyPlot.xAxis()));
		jfcPlot.setRangeAxis(getJFreeChartAxis(xyPlot.yAxis()));
		jfcPlot.setRenderer(jfcRenderer);
		addAllSeries();
		return Utils.setupJFreeChart(xyPlot.getTitle(), jfcPlot);
	}

	private void addAllSeries() {
		for(XYPlotItem series : xyPlot.getItems())
			if(series instanceof XYSeries)
				addSeries((XYSeries) series);
	}

	private void addSeries(XYSeries series) {
		SortedLabel uniqueLabel = sortedLabelFactory.newLabel(series.getLabel());
		addSeriesData(uniqueLabel, series.getXValues(), series.getYValues());
		setSeriesStyle(uniqueLabel, series.getStyle(), series.getLegendVisible());
	}

	private void addSeriesData(SortedLabel uniqueLabel, Collection<Double> xs, Collection<Double> ys) {
		org.jfree.data.xy.XYSeries series = new org.jfree.data.xy.XYSeries(uniqueLabel, false, true);
		Iterator<Double> xi = xs.iterator();
		Iterator<Double> yi = ys.iterator();
		while (xi.hasNext() && yi.hasNext())
			series.add(xi.next(), yi.next());
		jfcDataSet.addSeries(series);
	}

	private void setSeriesStyle(SortedLabel label, SeriesStyle style, boolean legendVisible) {
		if (style == null)
			return;
		int index = jfcDataSet.getSeriesIndex(label);
		RendererModifier.wrap(jfcRenderer).setSeriesStyle(index, style);
		jfcRenderer.setSeriesVisibleInLegend(index, legendVisible);
	}

}