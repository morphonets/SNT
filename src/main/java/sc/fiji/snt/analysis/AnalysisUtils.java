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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.StatisticalSummary;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SubCategoryAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.PolarAxisLocation;
import org.jfree.chart.plot.PolarPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.DefaultPolarItemRenderer;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.jfree.data.xy.DefaultXYDataset;
import org.scijava.util.ColorRGB;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;

/**
 * Static utilities for sc.fiji.analysis.
 *
 * @author Tiago Ferreira
 */
class AnalysisUtils {

	private AnalysisUtils() {
	}

	/**
	 * Assembles a label from a measurements metric and a spatial unit
	 * 
	 * @param standardMetric SNT's supported metric, e.g.,
	 *                       {@link TreeStatistics#BRANCH_VOLUME}
	 * @param tree           {@link Tree} from which spatial unit is extracted
	 * @return the metric label, e.g., "Branch volume (µm³)"
	 */
	public static String getMetricLabel(final String standardMetric, final Tree tree) {
		final String unit = (String) tree.getProperties().getOrDefault(TreeProperties.KEY_SPATIAL_UNIT, "? units");
		switch (standardMetric) {
		case TreeStatistics.CONVEX_HULL_SIZE:
			if (tree.is3D()) {
				return String.format("%s (%s\u00B3)", standardMetric, unit);
			} else {
				return String.format("%s (%s\u00B2)", standardMetric, unit);
			}
		case TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE:
			if (tree.is3D()) {
				return String.format("%s (%s\u00B2)", standardMetric, unit);
			} else {
				return String.format("%s (%s)", standardMetric, unit);
			}
		default:
			return getMetricLabel(standardMetric, unit);
		}
	}

	/**
	 * Assembles a label from a measurements metric and a spatial unit
	 * 
	 * @param standardMetric SNT's supported metric, e.g.,
	 *                       {@link TreeStatistics#BRANCH_VOLUME}
	 * @param unit,          the associated spatial uni (e.g., "µm")
	 * @return the metric label, e.g., "Branch volume (µm³)"
	 */
	public static String getMetricLabel(final String standardMetric, final String unit) {
		if (unit == null || unit.isEmpty())
			return standardMetric;
		switch (standardMetric) {
		case TreeStatistics.BRANCH_LENGTH:
		case TreeStatistics.CONVEX_HULL_CENTROID_ROOT_DISTANCE:
		case TreeStatistics.DEPTH:
		case TreeStatistics.HEIGHT:
		case TreeStatistics.INNER_LENGTH:
		case TreeStatistics.INTER_NODE_DISTANCE:
		case TreeStatistics.LENGTH:
		case TreeStatistics.NODE_RADIUS:
		case TreeStatistics.PATH_LENGTH:
		case TreeStatistics.PRIMARY_LENGTH:
		case TreeStatistics.TERMINAL_LENGTH:
		case TreeStatistics.WIDTH:
		case MultiTreeStatistics.AVG_BRANCH_LENGTH:
		case MultiTreeStatistics.INNER_LENGTH:
		case MultiTreeStatistics.PRIMARY_LENGTH:
		case MultiTreeStatistics.TERMINAL_LENGTH:
		case ShollAnalyzer.CENTROID_RADIUS:
		case ShollAnalyzer.ENCLOSING_RADIUS:
		case ShollAnalyzer.MAX_FITTED_RADIUS:
			return String.format("%s (%s)", standardMetric, unit);
		case TreeStatistics.INTER_NODE_DISTANCE_SQUARED:
		case TreeStatistics.SURFACE_AREA:
			return String.format("%s (%s\u00B2)", standardMetric, unit);
		case TreeStatistics.BRANCH_VOLUME:
		case TreeStatistics.PATH_VOLUME:
		case TreeStatistics.VOLUME:
			return String.format("%s (%s\u00B3)", standardMetric, unit);
		default:
			return standardMetric;
		}
	}

	/* Converts nodes to single point paths: useful to allow Tree-based classes to parse isolated nodes */
	static Tree convertPointsToSinglePointPaths(final Collection<? extends PointInImage> points) {
		final Tree tree = new Tree();
		for (final PointInImage point : points) {
			final Path p = new Path(1, 1, 1, "NA");
			p.addNode(point);
			point.onPath = p;
			tree.add(p);
		}
		return tree;
	}

	static Tree createTreeFromPointAssociatedPaths(final Collection<? extends PointInImage> points) {
		final Tree tree = new Tree();
		for (final PointInImage point : points) {
			if (point.onPath != null)
				tree.add(point.onPath);
		}
		return tree;
	}

	static <T extends StatisticalSummary> String getSummaryDescription(final T stats, final HistogramDatasetPlus datasetPlus) {
		final StringBuilder sb = new StringBuilder();
		final double mean = stats.getMean();
		final int nDecimals = (mean < 0.51) ? 3 : 2;
		sb.append("Q1: ").append(SNTUtils.formatDouble(datasetPlus.q1, nDecimals));
		if (stats instanceof DescriptiveStatistics)
			sb.append("  Median: ").append(SNTUtils.formatDouble(//
					((DescriptiveStatistics) stats).getPercentile(50), nDecimals));
		sb.append("  Q3: ").append(SNTUtils.formatDouble(datasetPlus.q3, nDecimals));
		sb.append("  IQR: ").append(SNTUtils.formatDouble(datasetPlus.q3 - datasetPlus.q1, nDecimals));
		sb.append("  Bins: ").append(datasetPlus.nBins);
		sb.append("\nN: ").append(datasetPlus.n);
		sb.append("  Min: ").append(SNTUtils.formatDouble(datasetPlus.min, nDecimals));
		sb.append("  Max: ").append(SNTUtils.formatDouble(datasetPlus.max, nDecimals));
		sb.append("  Mean\u00B1").append("SD: ").append(SNTUtils.formatDouble(
			mean, nDecimals)).append("\u00B1").append(SNTUtils.formatDouble(
				stats.getStandardDeviation(), nDecimals));
		return sb.toString();
	}

	static JFreeChart createPolarHistogram(final String xAxisTitle, final String unit, final DescriptiveStatistics stats,
			final HistogramDatasetPlus datasetPlus) {

		final PolarPlot polarPlot = new PolarPlot();
		polarPlot.setDataset(histoDatasetToSingleXYDataset(datasetPlus.getDataset(xAxisTitle), 1, datasetPlus.nBins));

		// Customize series
		final DefaultPolarItemRenderer render = new DefaultPolarItemRenderer();
		polarPlot.setRenderer(render);
		render.setShapesVisible(false);
		render.setConnectFirstAndLastPoint(true);
		for (int bin = 0; bin < datasetPlus.nBins; bin++) {
			render.setSeriesFilled(bin, true);
			render.setSeriesOutlinePaint(bin, Color.DARK_GRAY);
			//render.setSeriesPaint(bin, Color.LIGHT_GRAY);
			render.setSeriesPaint(bin, new Color(102, 170, 215));
		}
		final JFreeChart chart = assemblePolarPlotChart(getMetricLabel(xAxisTitle, unit), polarPlot, false);
		chart.removeLegend();
		final String desc = getSummaryDescription(stats, datasetPlus);
		final TextTitle label = new TextTitle(desc);
		label.setFont(polarPlot.getAngleLabelFont().deriveFont(Font.PLAIN));
		label.setPosition(RectangleEdge.BOTTOM);
		chart.addSubtitle(label);

		return chart;
	}

	private static DefaultXYDataset histoDatasetToSingleXYDataset(final HistogramDataset histDataset, final int nSeries,
			final int nBins) {
		final DefaultXYDataset xyDataset = new DefaultXYDataset();
		for (int series = 0; series < nSeries; series++) {
			// for each bar in the histogram, we'll draw the triangle defined by the origin
			// (0,0), (bin-start, freq), (bin-end, freq)
			for (int bin = 0; bin < nBins; bin++) {
				final double xStart = (double) histDataset.getStartX(series, bin);
				final double yStart = (double) histDataset.getStartY(series, bin);
				final double xEnd = (double) histDataset.getEndX(series, bin);
				final double yEnd = (double) histDataset.getEndY(series, bin);
				final double[][] seriesData = new double[][] { new double[] { 0, xStart, xEnd },
						new double[] { 0, yStart, yEnd } };
				xyDataset.addSeries(histDataset.getSeriesKey(series) + "[bin " + (bin + 1) + "]", seriesData);
			}
		}
		return xyDataset;
	}

	private static JFreeChart assemblePolarPlotChart(final String xAxisTitle, final PolarPlot polarPlot, final boolean createLegend) {
		// Customize axes
		final NumberAxis rangeAxis = new NumberAxis();
		polarPlot.setAxis(rangeAxis);
		rangeAxis.setAxisLineVisible(true);
		rangeAxis.setTickMarksVisible(true);
		rangeAxis.setAutoTickUnitSelection(true);
		rangeAxis.setTickLabelsVisible(true);

		// Customize plot
		polarPlot.addCornerTextItem(xAxisTitle);
		polarPlot.setCounterClockwise(false);
		polarPlot.setRadiusMinorGridlinesVisible(false);
		polarPlot.setAxisLocation(PolarAxisLocation.NORTH_LEFT);
		polarPlot.setBackgroundAlpha(0f);
		polarPlot.setAngleGridlinePaint(Color.DARK_GRAY);
		polarPlot.setBackgroundPaint(Color.WHITE);
		polarPlot.setRadiusGridlinePaint(Color.LIGHT_GRAY);
		polarPlot.setAngleGridlinesVisible(true);
		polarPlot.setOutlineVisible(false);

		// Customize chart
		final JFreeChart chart = new JFreeChart(null,rangeAxis.getLabelFont(), polarPlot, createLegend);
		chart.setBorderVisible(false);
		return chart;
	}

	static JFreeChart createHistogram(final String xAxisTitle, final String unit, final DescriptiveStatistics stats,
			final HistogramDatasetPlus datasetPlus) {

		final JFreeChart chart = ChartFactory.createHistogram(null, getMetricLabel(xAxisTitle, unit),
			"Rel. Frequency", datasetPlus.getDataset(xAxisTitle));

		// Customize plot
		final XYPlot plot = chart.getXYPlot();
		final XYBarRenderer bar_renderer = (XYBarRenderer) plot.getRenderer();
		bar_renderer.setBarPainter(new StandardXYBarPainter());
		bar_renderer.setDrawBarOutline(true);
		bar_renderer.setSeriesOutlinePaint(0, Color.DARK_GRAY);
		bar_renderer.setSeriesPaint(0, SNTColor.alphaColor(Color.LIGHT_GRAY, 50));
		bar_renderer.setShadowVisible(false);
		applyHistogramStyle(chart, plot);

		// Append descriptive label
		chart.removeLegend();
		final String desc = getSummaryDescription(stats, datasetPlus);
		final TextTitle label = new TextTitle(desc);
		label.setFont(plot.getRangeAxis().getLabelFont().deriveFont(Font.PLAIN));
		label.setPosition(RectangleEdge.BOTTOM);
		chart.addSubtitle(label);
		return chart;
	}

	private static void applyHistogramStyle(final JFreeChart chart, final Plot plot) {
		final Color bColor = null; //Color.WHITE; make graph transparent so that it can be exported without background
		plot.setBackgroundPaint(bColor);
		if (plot instanceof XYPlot) {
			((XYPlot) plot).setDomainGridlinesVisible(false);
			((XYPlot) plot).setRangeGridlinesVisible(false);
			((XYPlot) plot).setAxisOffset(new RectangleInsets(0,0, 0, 0));
		}
		if (plot instanceof CategoryPlot) {
			final CategoryPlot cPlot = (CategoryPlot) plot;
			cPlot.setDomainGridlinesVisible(false);
			cPlot.setRangeGridlinesVisible(true);
			cPlot.getDomainAxis().setCategoryMargin(0);
			cPlot.getDomainAxis().setLowerMargin(0.01);
			cPlot.getDomainAxis().setUpperMargin(0.01);
			cPlot.setAxisOffset(new RectangleInsets(0,0, 0, 0));
		}
		chart.setBackgroundPaint(bColor);
		if (chart.getLegend() != null)
			chart.getLegend().setBackgroundPaint(bColor);
		chart.setAntiAlias(true);
		chart.setTextAntiAlias(true);
	}

	static SNTChart createHistogram(final String normMeasurement, final String unit, final int nSeries,
			final HistogramDataset dataset) {
		final JFreeChart chart = ChartFactory.createHistogram(null, getMetricLabel(normMeasurement, unit), "Rel. Frequency", dataset);

		// Customize plot
		final XYPlot plot = chart.getXYPlot();
		applyHistogramStyle(chart, plot);
		final XYBarRenderer bar_renderer = (XYBarRenderer) plot.getRenderer();
		bar_renderer.setBarPainter(new StandardXYBarPainter());
		bar_renderer.setDrawBarOutline(true);
		bar_renderer.setSeriesOutlinePaint(0, Color.DARK_GRAY);
		final ColorRGB[] colors = SNTColor.getDistinctColors(nSeries);
		for (int i = 0; i < nSeries; i++) {
			final Color awtColor = new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue(), 128);
			bar_renderer.setSeriesPaint(i, awtColor);
		}
		bar_renderer.setShadowVisible(false);
		return new SNTChart("Grouped Hist.", chart);
	}

	static SNTChart createPolarHistogram(final String normMeasurement, final String unit, final HistogramDataset dataset, final int nSeries,
			final int nBins) {
		final PolarPlot polarPlot = new PolarPlot();
		polarPlot.setDataset(histoDatasetToSingleXYDataset(dataset, nSeries, nBins));
		final DefaultPolarItemRenderer render = new DefaultPolarItemRenderer();
		polarPlot.setRenderer(render);
		render.setShapesVisible(false);
		render.setConnectFirstAndLastPoint(true);
		render.setDefaultFillPaint(Color.red);
		final LegendItemCollection chartLegend = new LegendItemCollection();
		final Shape shape = new Rectangle(polarPlot.getAngleLabelFont().getSize() * 2,
				polarPlot.getAngleLabelFont().getSize());
		final ColorRGB[] colors = SNTColor.getDistinctColors(nSeries);
		for (int s = 0; s < nSeries; s++) {
			final Color awtColor = new Color(colors[s].getRed(), colors[s].getGreen(), colors[s].getBlue());
			chartLegend.add(new LegendItem(dataset.getSeriesKey(s).toString(), null, null, null, shape, awtColor));
			for (int bin = 0; bin < nBins; bin++) {
				final int index = s * nBins + bin;
				render.setSeriesFilled(index, true);
				render.setSeriesOutlinePaint(index, Color.DARK_GRAY);
				render.setSeriesPaint(index, awtColor);
				render.setSeriesFillPaint(index, awtColor);
				render.setSeriesItemLabelPaint(index, awtColor);
			}
		}
		polarPlot.setFixedLegendItems(chartLegend);
		return new SNTChart("Grouped Polar Hist.", assemblePolarPlotChart(getMetricLabel(normMeasurement, unit), polarPlot, nSeries > 1));
	}

	static JFreeChart createCategoryPlot(final String domainTitle,
			final String rangeTitle, final String unit, final DefaultCategoryDataset dataset) {
		final JFreeChart chart = ChartFactory.createBarChart(null, domainTitle, getMetricLabel(rangeTitle, unit), dataset,
				PlotOrientation.HORIZONTAL, // orientation
				false, // include legend
				true, // tooltips?
				false // URLs?
		);
		// Customize plot
		final CategoryPlot plot = chart.getCategoryPlot();
		applyHistogramStyle(chart, plot);
		final BarRenderer barRender = ((BarRenderer) plot.getRenderer());
		barRender.setBarPainter(new StandardBarPainter());
		barRender.setDrawBarOutline(true);
		barRender.setSeriesPaint(0, SNTColor.alphaColor(Color.LIGHT_GRAY, 50));
		barRender.setSeriesOutlinePaint(0, Color.DARK_GRAY);
		barRender.setShadowVisible(false);
		return chart;
	}
	
	static JFreeChart createCategoryPlot(final String domainTitle,
			final String rangeTitle, final String unit, final DefaultCategoryDataset dataset, final int nSeries) {
		final JFreeChart chart = ChartFactory.createBarChart(null, domainTitle, getMetricLabel(rangeTitle, unit), dataset,
				PlotOrientation.HORIZONTAL, // orientation
				true, // include legend
				true, // tooltips?
				false // URLs?
		);
		// Customize plot
		final CategoryPlot plot = chart.getCategoryPlot();
		final ColorRGB[] colors = SNTColor.getDistinctColors(nSeries);
		SubCategoryAxis domainAxis = new SubCategoryAxis(" ");
		final BarRenderer barRender = ((BarRenderer) plot.getRenderer());
		barRender.setBarPainter(new StandardBarPainter());
		barRender.setDrawBarOutline(true);
		barRender.setShadowVisible(false);
		for (int i = 0; i < nSeries; i++) {
			final Color color = new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue());
			barRender.setSeriesPaint(i, SNTColor.alphaColor(color, 50));
			barRender.setSeriesOutlinePaint(i, color);
			barRender.setItemMargin(0);
			domainAxis.addSubCategory("");
		}
		plot.setDomainAxis(domainAxis);
		applyHistogramStyle(chart, plot);
		domainAxis.setCategoryMargin(0.25);
		
		return chart;
	}

	static class HistogramDatasetPlus {
		final List<Double> values;
		int nBins;
		long n;
		double q1, q3, min, max;
		private DescriptiveStatistics dStats;
		private boolean computedAsPercentage;

		HistogramDatasetPlus() {
			values = new ArrayList<Double>();
		}

		HistogramDatasetPlus(final DescriptiveStatistics stats, final boolean retrieveValues) {
			this();
			dStats = stats;
			if (retrieveValues) {
				for (final double v : stats.getValues()) {
					values.add(v);
				}
			}
		}

		double[] valuesAsArray() {
			final double[] doubles = new double[values.size()];
			for (int i = 0; i < doubles.length; i++) {
				doubles[i] = values.get(i);
			}
			return doubles;
		}

		void compute() {
			compute(false);
		}

		void compute(final boolean asPercentage) {
			if (dStats == null || computedAsPercentage != asPercentage) {
				dStats = new DescriptiveStatistics();
				if (asPercentage) {
					computedAsPercentage = true;
					values.forEach(v -> dStats.addValue(v/values.size()));
				} else
					values.forEach(v -> dStats.addValue(v));
			}
			n = dStats.getN();
			q1 = dStats.getPercentile(25);
			q3 = dStats.getPercentile(75);
			min = dStats.getMin();
			max = dStats.getMax();
			if (n == 0 || max == min || Double.isNaN(max) || Double.isNaN(min)) {
				nBins = 1;
			}
			{
				final double binWidth = 2 * (q3 - q1) / Math.cbrt(n); // Freedman-Diaconis rule
				if (binWidth == 0) {
					nBins = (int) Math.round(Math.sqrt(n));
				} else {
					nBins = (int) Math.ceil((max - min) / binWidth);
				}
				nBins = Math.max(1, nBins);
			}
		}

		HistogramDataset getDataset(final String label) {
			compute();
			final HistogramDataset dataset = new HistogramDataset();
			dataset.setType(HistogramType.RELATIVE_FREQUENCY);
			dataset.addSeries(label, valuesAsArray(), nBins);
			return dataset;
		}

	}
}
