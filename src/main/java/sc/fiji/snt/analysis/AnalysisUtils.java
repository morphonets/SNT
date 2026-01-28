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

package sc.fiji.snt.analysis;

import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.StatisticalSummary;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.axis.*;
import org.jfree.chart.entity.EntityCollection;
import org.jfree.chart.labels.BoxAndWhiskerToolTipGenerator;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.*;
import org.jfree.chart.renderer.DefaultPolarItemRenderer;
import org.jfree.chart.renderer.Outlier;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.BoxAndWhiskerRenderer;
import org.jfree.chart.renderer.category.CategoryItemRendererState;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.*;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.chart.util.SortOrder;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.statistics.BoxAndWhiskerCategoryDataset;
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.jfree.data.xy.DefaultXYDataset;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.analysis.growth.GrowthAnalyzer.GrowthPhase;
import sc.fiji.snt.analysis.growth.GrowthAnalyzer.GrowthPhaseType;

import java.awt.*;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;

/**
 * Static utilities for sc.fiji.analysis.
 *
 * @author Tiago Ferreira
 */
public class AnalysisUtils {

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
				return String.format("%s (%s³)", standardMetric, unit);
			} else {
				return String.format("%s (%s²)", standardMetric, unit);
			}
		case TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE:
			if (tree.is3D()) {
				return String.format("%s (%s²)", standardMetric, unit);
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
	 * @param unit          the associated spatial uni (e.g., "µm")
	 * @return the metric label, e.g., "Branch volume (µm³)"
	 */
	public static String getMetricLabel(final String standardMetric, final String unit) {
		if (unit == null || unit.isEmpty())
			return standardMetric;
        return switch (standardMetric) {
            case TreeStatistics.BRANCH_LENGTH, TreeStatistics.CONVEX_HULL_CENTROID_ROOT_DISTANCE, TreeStatistics.DEPTH,
                 TreeStatistics.HEIGHT, TreeStatistics.INNER_LENGTH, TreeStatistics.INTER_NODE_DISTANCE,
                 TreeStatistics.LENGTH, TreeStatistics.NODE_RADIUS, TreeStatistics.BRANCH_MEAN_RADIUS,
                 TreeStatistics.PATH_MEAN_RADIUS, TreeStatistics.PATH_LENGTH, TreeStatistics.PRIMARY_LENGTH,
                 TreeStatistics.TERMINAL_LENGTH, TreeStatistics.WIDTH, MultiTreeStatistics.AVG_BRANCH_LENGTH,
                 MultiTreeStatistics.INNER_LENGTH, MultiTreeStatistics.PRIMARY_LENGTH,
                 MultiTreeStatistics.TERMINAL_LENGTH, ShollAnalyzer.CENTROID_RADIUS, ShollAnalyzer.ENCLOSING_RADIUS,
                 ShollAnalyzer.MAX_FITTED_RADIUS, TreeStatistics.SHOLL_MAX_FITTED_RADIUS ->
                    String.format("%s (%s)", standardMetric, unit);
            case TreeStatistics.INTER_NODE_DISTANCE_SQUARED, TreeStatistics.SURFACE_AREA ->
                    String.format("%s (%s²)", standardMetric, unit);
            case TreeStatistics.BRANCH_VOLUME, TreeStatistics.PATH_VOLUME, TreeStatistics.VOLUME ->
                    String.format("%s (%s³)", standardMetric, unit);
            default -> standardMetric + " (" + unit + ")";
        };
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
		if (datasetPlus.n ==0) datasetPlus.compute();
		sb.append("Q1: ").append(SNTUtils.formatDouble(datasetPlus.q1, nDecimals));
		if (stats instanceof DescriptiveStatistics)
			sb.append("  Med.: ").append(SNTUtils.formatDouble(//
					((DescriptiveStatistics) stats).getPercentile(50), nDecimals));
		sb.append("  Q3: ").append(SNTUtils.formatDouble(datasetPlus.q3, nDecimals));
		sb.append("  IQR: ").append(SNTUtils.formatDouble(datasetPlus.q3 - datasetPlus.q1, nDecimals));
		sb.append("  Bins: ").append(datasetPlus.nBins);
		sb.append("\nN: ").append(datasetPlus.n);
		sb.append("  Min: ").append(SNTUtils.formatDouble(datasetPlus.min, nDecimals));
		sb.append("  Max: ").append(SNTUtils.formatDouble(datasetPlus.max, nDecimals));
		sb.append("  Mean±").append("SD: ").append(SNTUtils.formatDouble(
			mean, nDecimals)).append("±").append(SNTUtils.formatDouble(
				stats.getStandardDeviation(), nDecimals));
		return sb.toString();
	}

	static SNTChart createPolarHistogram(final String title, final String unit, final DescriptiveStatistics stats, final HistogramDatasetPlus datasetPlus) {
		return createPolarHistogram(title, unit, stats, getSummaryDescription(stats, datasetPlus), datasetPlus);
	}

	static SNTChart createPolarHistogram(final String title, final String unit, final DescriptiveStatistics stats, final String description) {
		final AnalysisUtils.HistogramDatasetPlus datasetPlus = new AnalysisUtils.HistogramDatasetPlus(stats, title);
		return createPolarHistogram(title, unit, stats, description, datasetPlus);
	}

	static SNTChart createPolarHistogram(final String title, final String unit, final DescriptiveStatistics stats, final String description, final HistogramDatasetPlus datasetPlus) {
		final JFreeChart chart = createPolarHistogram(title, unit, stats, datasetPlus, description);
		return new SNTChart("Polar Hist. " + StringUtils.capitalize(title), chart);
	}

	private static JFreeChart createPolarHistogram(final String xAxisTitle, final String unit, final DescriptiveStatistics stats,
			final HistogramDatasetPlus datasetPlus, final String description) {

        final PolarPlot polarPlot = initPolarPlot();
		polarPlot.setDataset(histoDatasetToSingleXYDataset(datasetPlus.getDataset(), 1, datasetPlus.nBins));

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
		final JFreeChart chart = assemblePolarPlotChart(polarPlot, false);
		chart.removeLegend();
		final TextTitle label = new TextTitle(getMetricLabel(xAxisTitle, unit) + "\n" + description);
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

	private static JFreeChart assemblePolarPlotChart(final PolarPlot polarPlot, final boolean createLegend) {
		final JFreeChart chart = new JFreeChart(null, polarPlot.getAngleLabelFont(), polarPlot, createLegend);
		chart.setBorderVisible(false);
		return chart;
	}

	static List<XYItemRenderer> getNormalCurveRenderers(final XYPlot histogram) {
		return getCurveRenderers(histogram, "Norm. dist.");
	}

	static List<XYItemRenderer> getGMMCurveRenderers(final XYPlot histogram) {
		return getCurveRenderers(histogram, "GMM");
	}

	private static List<XYItemRenderer> getCurveRenderers(final XYPlot histogram, final String pattern) {
		final List<XYItemRenderer> list = new ArrayList<>();
		for (int i = 0; i < histogram.getDatasetCount(); i++) {
			final XYDataset dataset = histogram.getDataset(i);
			for (int j = 0; j < dataset.getSeriesCount(); j++) {
				if (dataset.getSeriesKey(j) != null && dataset.getSeriesKey(j).toString().contains(pattern)) {
					list.add(histogram.getRendererForDataset(dataset));
				}
			}
		}
		return list;
	}

	static XYItemRenderer getQuartileMarkersRenderer(final XYPlot histogram) {
		for (int i = 0; i < histogram.getDatasetCount(); i++) {
			final XYDataset dataset = histogram.getDataset(i);
			for (int j = 0; j < dataset.getSeriesCount(); j++) {
				if (dataset.getSeriesKey(j) != null && dataset.getSeriesKey(j).toString().startsWith("Percentile")) {
					return histogram.getRendererForDataset(dataset);
				}
			}
		}
		return null;
	}

	static void addQuartileMarkers(final XYPlot histogram, final HistogramDatasetPlus hdp,
									final boolean visibility) {
		addQuartileMarkers(histogram, Collections.singletonList(hdp), visibility);
	}

	static void addQuartileMarkers(final XYPlot histogram, final List<HistogramDatasetPlus> hdps,
								   final boolean visibility) {

		assert hdps.size() == histogram.getDatasetCount();

		// define strokes, tooltips, and colors
		final Stroke stroke1 = new BasicStroke(
				1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
				1.0f, new float[]{10.0f, 6.0f}, 0.0f);
		final Stroke stroke2 = new BasicStroke(
				2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
				1.0f, new float[]{10.0f, 6.0f}, 0.0f);

		final XYToolTipGenerator xyToolTipGenerator = (dataset, series, item) -> {
			return String.format("%s %.3f", dataset.getSeriesKey(series), dataset.getX(series, item).doubleValue());
		};
		final Color[] colors = (hdps.size() == 1) ?
				new Color[]{Color.DARK_GRAY} : SNTColor.getDistinctColorsAWT(hdps.size());

		// define marker positions
		double minY = histogram.getRangeAxis(0).getRange().getLowerBound();
		double maxY = histogram.getRangeAxis(0).getRange().getUpperBound();
		final int[] percentiles = {25, 50, 75};

		// define common render and dataset.
		// NB: TF: This would be simpler w/ ValueMarkers but AFAICT there is
		// no immediate way to toggle visibility of ValueMarkers w/ current API
		final DefaultXYItemRenderer renderer = new DefaultXYItemRenderer();
		renderer.setDefaultShapesVisible(false);
		renderer.setDrawSeriesLineAsPath(true);
		renderer.setDefaultSeriesVisible(visibility);
		renderer.setDefaultSeriesVisibleInLegend(false);
		renderer.setDefaultToolTipGenerator(xyToolTipGenerator);
		final XYSeriesCollection markersDataset = new XYSeriesCollection();
		final int markersIdx = histogram.getDatasetCount();
		histogram.setDataset(markersIdx, markersDataset);
		histogram.setRenderer(markersIdx, renderer);

		// populate series
		int counter = 0;
		for (int i = 0; i < hdps.size(); i++) {
			for (int j = 0; j < percentiles.length; j++) {
				final int p = percentiles[j];
				final XYSeries series = new XYSeries(String.format("Percentile %d [%02d]", p, i + 1));
				series.add(hdps.get(i).dStats.getPercentile(p), minY);
				series.add(hdps.get(i).dStats.getPercentile(p), maxY);
				markersDataset.addSeries(series);
				renderer.setSeriesPaint(counter, colors[i]);
				renderer.setSeriesOutlinePaint(counter, colors[i]);
				renderer.setSeriesStroke(counter, (j == 1) ? stroke2 : stroke1);
				counter++;
			}
		}
	}

	private static void addSecondaryCurveToHist(final XYPlot histogram, final XYSeriesCollection curveDataset, final boolean visibility) {

		assert curveDataset.getSeriesCount() == histogram.getDatasetCount();

		// create the series;
		final int curveIdx = histogram.getDatasetCount();
		histogram.setDataset(curveIdx, curveDataset);

		// Customize series
		final XYSplineRenderer renderer = new XYSplineRenderer();
		renderer.setDefaultShapesVisible(false);
		renderer.setDrawSeriesLineAsPath(true);
		renderer.setDefaultStroke(new BasicStroke(2f));
		renderer.setAutoPopulateSeriesStroke(false); // otherwise stroke is not applied
		renderer.setDefaultSeriesVisible(visibility);
		renderer.setDefaultSeriesVisibleInLegend(false);
		final Color[] colors = (curveDataset.getSeriesCount() == 1) ?
				new Color[]{Color.DARK_GRAY} : SNTColor.getDistinctColorsAWT(curveDataset.getSeriesCount());
		for (int j = 0; j < curveDataset.getSeriesCount(); j++) {
			renderer.setSeriesPaint(j, colors[j]);
			renderer.setSeriesOutlinePaint(j, colors[j]);
		}
		final XYToolTipGenerator xyToolTipGenerator = (dataset, series, item) -> String.format("%s (%.3f, %.3f)", dataset.getSeriesKey(series),
                dataset.getX(series, item).doubleValue(), dataset.getY(series, item).doubleValue());
		renderer.setDefaultToolTipGenerator(xyToolTipGenerator);
		histogram.setRenderer(curveIdx, renderer);
	}

	static XYSeriesCollection getNormalDataset(final HistogramDatasetPlus hdp, final double fromX, final double toX) {
		return getNormalDataset(Collections.singletonList(hdp), fromX, toX);
	}

	static XYSeriesCollection getNormalDataset(final List<HistogramDatasetPlus> hdps, final double fromX, final double toX) {
		final XYSeriesCollection dataset = new XYSeriesCollection();
		final AtomicInteger counter = new AtomicInteger(1);
		hdps.forEach( hdp -> {
			if (hdp.dStats.getStandardDeviation() > 0)
				dataset.addSeries(getNormalCurve(hdp, fromX, toX, String.valueOf(counter.getAndIncrement())));
		});
		return dataset;
	}

	private static XYSeries getNormalCurve(final HistogramDatasetPlus hdp, final double fromX, final double toX, final String label) {
		final smile.stat.distribution.GaussianDistribution gaussian = new smile.stat.distribution.GaussianDistribution(hdp.mean(), hdp.std());
		final int nSteps = 500;
		final double step = (toX - fromX) / nSteps;
		final XYSeries series = new XYSeries("Norm. dist. " + label);
		final double histArea = hdp.histogramArea();
		// Calculate the total area under the Gaussian curve withing the [fromX-toX] range
		double totalGaussianArea = 0;
		for (int i = 0; i < nSteps; i++) {
			final double x = fromX + (i * step);
			final double y = gaussian.p(x);
			totalGaussianArea += y * step;
		}
		// Generate the scaled Gaussian curve
		final double scaleFactor = histArea / totalGaussianArea;
		for (int i = 0; i < nSteps; i++) {
			final double x = fromX + (i * step);
			final double y = gaussian.p(x) * scaleFactor;
			series.add(x, y);
		}
		return series;
	}

	private static XYSeriesCollection getGMMDataset(final HistogramDatasetPlus hdp, final double fromX, final double toX) {
		return getGMMDataset(List.of(hdp), fromX, toX);
	}

	private static XYSeriesCollection getGMMDataset(final List<HistogramDatasetPlus> hdps, final double fromX, final double toX) {
		final XYSeriesCollection dataset = new XYSeriesCollection();
		final AtomicInteger counter = new AtomicInteger(1);
		hdps.forEach( hdp -> {
			if (hdp.n > 20) // at least 20 points to fit a GMM
				dataset.addSeries(getGMMCurve(hdp, fromX, toX, String.valueOf(counter.getAndIncrement())));
		});
		return dataset;
	}

	private static XYSeries getGMMCurve(final HistogramDatasetPlus hdp, final double fromMin, final double toMax, final String label) {
		final smile.stat.distribution.GaussianMixture mixture = smile.stat.distribution.GaussianMixture.fit(hdp.values());
		final int nSteps = 500;
		final double step = (toMax - fromMin) / nSteps;
		final XYSeries series = new XYSeries("GMM " + label);
		final double histArea = hdp.histogramArea();
		// Calculate the total area under the curve withing the [fromX-toX] range
		double totalArea = 0;
		for (int i = 0; i < nSteps; i++) {
			final double x = fromMin + (i * step);
			final double y = mixture.p(x);
			totalArea += y * step;
		}
		// Generate the scaled curve
		final double scaleFactor = histArea / totalArea;
		for (int i = 0; i < nSteps; i++) {
			final double x = fromMin + (i * step);
			final double y = mixture.p(x) * scaleFactor;
			series.add(x, y);
		}
		return series;
	}

	static SNTChart createHistogram(final String title, final String unit, final DescriptiveStatistics stats, final String description) {
		final AnalysisUtils.HistogramDatasetPlus datasetPlus = new AnalysisUtils.HistogramDatasetPlus(stats, title);
		final JFreeChart chart = createHistogram(title, unit, stats, datasetPlus, description);
		return new SNTChart("Hist. " + StringUtils.capitalize(title), chart);
	}

	static SNTChart createHistogram(final String title, final String unit, final DescriptiveStatistics stats) {
		final AnalysisUtils.HistogramDatasetPlus datasetPlus = new AnalysisUtils.HistogramDatasetPlus(stats, title);
		return createHistogram(title, unit, stats, datasetPlus);
	}

	static SNTChart createHistogram(final String title, final String unit, final DescriptiveStatistics stats, final HistogramDatasetPlus datasetPlus) {
		final JFreeChart chart = createHistogram(title, unit, stats, datasetPlus, getSummaryDescription(stats, datasetPlus));
		return new SNTChart("Hist. " + StringUtils.capitalize(title), chart);
	}

	private static JFreeChart createHistogram(final String xAxisTitle, final String unit, final DescriptiveStatistics stats,
			final HistogramDatasetPlus datasetPlus, final String summaryDescription) {

		final JFreeChart chart = ChartFactory.createHistogram(null, getMetricLabel(xAxisTitle, unit),
			"Rel. Frequency", datasetPlus.getDataset());

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
		final TextTitle label = new TextTitle(summaryDescription);
		label.setFont(plot.getRangeAxis().getLabelFont().deriveFont(Font.PLAIN));
		label.setPosition(RectangleEdge.BOTTOM);
		chart.addSubtitle(label);
		final double minX = plot.getDomainAxis().getRange().getLowerBound();
		final double maxX = plot.getDomainAxis().getRange().getUpperBound();
		addSecondaryCurveToHist(plot, getNormalDataset(datasetPlus, minX, maxX), false);
		addSecondaryCurveToHist(plot, getGMMDataset(datasetPlus, minX, maxX), false);
		addQuartileMarkers(plot, datasetPlus, false);
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
		if (plot instanceof CategoryPlot cPlot) {
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
			final HistogramDataset dataset, final List<HistogramDatasetPlus> hdps) {
		final JFreeChart chart = ChartFactory.createHistogram(null, getMetricLabel(normMeasurement, unit), "Rel. Frequency", dataset);

		// Customize plot
		final XYPlot plot = chart.getXYPlot();
		applyHistogramStyle(chart, plot);
		final XYBarRenderer bar_renderer = (XYBarRenderer) plot.getRenderer();
		bar_renderer.setBarPainter(new StandardXYBarPainter());
		bar_renderer.setDrawBarOutline(true);
		bar_renderer.setSeriesOutlinePaint(0, Color.DARK_GRAY);
		final Color[] colors = (nSeries==1) ? new Color[]{ Color.LIGHT_GRAY} : SNTColor.getDistinctColorsAWT(nSeries);
		for (int i = 0; i < nSeries; i++) {
			final Color awtColor = SNTColor.alphaColor(colors[i], 50);
			bar_renderer.setSeriesPaint(i, awtColor);
		}
		bar_renderer.setShadowVisible(false);
		if (nSeries==1) chart.removeLegend();
		if (hdps != null) {
			final double minX = plot.getDomainAxis(0).getRange().getLowerBound();
			final double maxX = plot.getDomainAxis(0).getRange().getUpperBound();
			addSecondaryCurveToHist(plot, getNormalDataset(hdps, minX, maxX), false);
			addSecondaryCurveToHist(plot, getGMMDataset(hdps, minX, maxX), false);
			addQuartileMarkers(plot, hdps, false);
		}
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
		//render.setDefaultFillPaint(Color.red);
		final LegendItemCollection chartLegend = new LegendItemCollection();
		final Shape shape = new Rectangle(polarPlot.getAngleLabelFont().getSize() * 2,
				polarPlot.getAngleLabelFont().getSize());
		final ColorRGB[] colors = (nSeries==1) ? new ColorRGB[]{Colors.GRAY} : SNTColor.getDistinctColors(nSeries);
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
		final SNTChart chart = new SNTChart("Grouped Polar Hist.", assemblePolarPlotChart(polarPlot, nSeries > 1));
		chart.setTitle(getMetricLabel(normMeasurement, unit));
		return chart;
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

	/**
	 * Computes the "optimal" number of bins for a histogram dataset using Freedman-Diaconis rule.
	 *
	 * @param stats the descriptive statistics used to calculate the number of bins
	 * @return the computed number of bins for the histogram
	 */
	public static int computeNBins(final DescriptiveStatistics stats) {
		final AnalysisUtils.HistogramDatasetPlus datasetPlus = new AnalysisUtils.HistogramDatasetPlus(stats, "");
		datasetPlus.compute();
		return datasetPlus.nBins;
	}

	/**
	 * Generates a ring plot (aka donut plot).
	 *
	 * @param title  the title of the chart. Null allowed
	 * @param data   a map of data values where the key is a String label and the value is a Number
	 *               representing the data associated with the label
	 * @param colors a map of colors corresponding to the sections of the plot; if null, distinct
	 *               colors will be automatically generated
	 * @return an instance of SNTChart containing the generated ring plot
	 */
	public static SNTChart ringPlot(final String title, final HashMap<String, Double> data,
									final HashMap<String, Color> colors)  {
		final DefaultPieDataset<String> dataset = new DefaultPieDataset<>();
		data.forEach((k,v) -> dataset.setValue(WordUtils.capitalizeFully(k), v));
		dataset.sortByValues(SortOrder.DESCENDING);
		final RingPlot ringPlot = getRingPlot(dataset);
		if (colors == null) {
			final Color[] c = SNTColor.getDistinctColorsAWT(data.size());
			int idx = 0;
			for (final String key : data.keySet())
				ringPlot.setSectionPaint(key, c[idx++]);
		} else {
			colors.forEach((k,v) -> ringPlot.setSectionPaint(WordUtils.capitalizeFully(k), v));
		}
		return new SNTChart(title, new JFreeChart(null, null, ringPlot, false));
	}

    private static PolarPlot initPolarPlot() {
        final PolarPlot polarPlot = new PolarPlot();
        polarPlot.setAngleTickUnit(new NumberTickUnit(PolarPlot.DEFAULT_ANGLE_TICK_UNIT_SIZE) {
            @Override
            public String valueToString(double value) {
                return super.valueToString(value) + "°";
            }
        });
        polarPlot.setAxisLocation(PolarAxisLocation.EAST_BELOW);
        polarPlot.setCounterClockwise(false);
        polarPlot.setRadiusMinorGridlinesVisible(false);
        polarPlot.setBackgroundAlpha(0f);
        polarPlot.setAngleGridlinePaint(Color.DARK_GRAY);
        polarPlot.setBackgroundPaint(Color.WHITE);
        polarPlot.setRadiusGridlinePaint(Color.LIGHT_GRAY);
        polarPlot.setAngleGridlinesVisible(true);
        polarPlot.setOutlineVisible(false);
        final NumberAxis rangeAxis = new NumberAxis();
        rangeAxis.setLabelFont(polarPlot.getAngleLabelFont());
        rangeAxis.setAxisLineVisible(false);
        rangeAxis.setTickMarksVisible(false);
        rangeAxis.setAutoTickUnitSelection(true);
        rangeAxis.setTickLabelsVisible(true);
        polarPlot.setAxis(rangeAxis);
        return polarPlot;
    }

    private static CustomBoxAndWhiskerRenderer assignRenderer(final CategoryPlot plot, final boolean monochrome, final int nSeries) {
        plot.setBackgroundPaint(null);
        plot.setRangePannable(true);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.setOutlineVisible(false);
        if (plot.getDataset().getColumnCount() * plot.getDataset().getRowCount() > 4) {
            plot.getDomainAxis().setCategoryLabelPositions(CategoryLabelPositions.UP_45);
        }
        final CustomBoxAndWhiskerRenderer renderer = new CustomBoxAndWhiskerRenderer();
        plot.setRenderer(renderer);
        renderer.setPointSize((double) plot.getRangeAxis().getTickLabelFont().getSize2D() / 2);
        renderer.setDrawOutliers(true);
        renderer.setItemMargin(0);
        renderer.setDefaultPaint(Color.BLACK);
        if (monochrome) {
            for (int i = 0; i < nSeries; i++) {
                renderer.setSeriesPaint(i, Color.GRAY);
                renderer.setSeriesOutlinePaint(i, Color.BLACK);
                renderer.setSeriesItemLabelPaint(i, Color.BLACK);
            }
        } else {
            final ColorRGB[] colors = SNTColor.getDistinctColors(nSeries);
            for (int i = 0; i < nSeries; i++) {
                final Color color = new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue());
                renderer.setSeriesPaint(i, color);
                renderer.setSeriesOutlinePaint(i, color);
                renderer.setSeriesItemLabelPaint(i, color);
            }
        }
        final String tooltipFormat = "<html><body>Max: {5}<br>Q3: {7}<br>Median: {3}<br>Q1: {6}<br>Min: {4}<br>Mean: {2}</body></html>";
        renderer.setDefaultToolTipGenerator(new BoxAndWhiskerToolTipGenerator(tooltipFormat, NumberFormat.getNumberInstance()));
        renderer.setUseOutlinePaintForWhiskers(true);
        renderer.setMaximumBarWidth(0.10);
        renderer.setMedianVisible(true);
        renderer.setMeanVisible(true);
        renderer.setFillBox(false);
        return renderer;
    }

    public static SNTChart boxPlot(final String valueAxisLabel, final DefaultBoxAndWhiskerCategoryDataset dataset) {
        final JFreeChart chart = ChartFactory.createBoxAndWhiskerChart(null, null, valueAxisLabel, dataset, false);
        assignRenderer((CategoryPlot) chart.getPlot(), true, dataset.getRowCount());
        final int height = 400;
        final double width = dataset.getRowCount() * 50;
        final SNTChart sntChart = new SNTChart("Box-plot", chart, new Dimension((int) width, height));
        sntChart.setOutlineVisible(false);
        return sntChart;
    }

    /**
     * Generates a polar plot
     *
     * @param title        the title of the chart. Null allowed
     * @param seriesData   the data (must be an array with length 2, containing two arrays of equal length,
     *                     the first containing the x-values and the second containing the y-values)
     * @param seriesColors the series colors corresponding to the sections of the plot
     * @return an instance of SNTChart containing the generated polar plot
     */
    public static SNTChart polarPlot(final String title, final List<double[][]> seriesData, final List<Color> seriesColors) {
        final PolarPlot polarPlot = initPolarPlot();
        final DefaultXYDataset xyDataset = new DefaultXYDataset();
        for (int i = 0; i < seriesData.size(); i++) {
            xyDataset.addSeries(String.format("%03d", i), seriesData.get(i));
        }
        polarPlot.setDataset(xyDataset);
        final DefaultPolarItemRenderer renderer = new DefaultPolarItemRenderer();
        polarPlot.setRenderer(renderer);
        renderer.setShapesVisible(false);
        renderer.setConnectFirstAndLastPoint(true);
        for (int i = 0; i < seriesColors.size(); i++) {
            final Color color = seriesColors.get(i);
            renderer.setSeriesFilled(i, true);
            renderer.setSeriesOutlinePaint(i, color);
            renderer.setSeriesPaint(i, color);
            renderer.setSeriesFillPaint(i, color);
        }
        return new SNTChart(title, assemblePolarPlotChart(polarPlot, false));
    }

	private static RingPlot getRingPlot(final DefaultPieDataset<String> dataset) {
		final RingPlot ringPlot = new RingPlot(dataset);
		ringPlot.setSectionDepth(0.33); //  width of the donut
		ringPlot.setIgnoreZeroValues(true);
		ringPlot.setIgnoreNullValues(true);
		// adjust sections
		ringPlot.setSectionOutlinesVisible(false);
		ringPlot.setOutlineVisible(false);
		ringPlot.setSeparatorsVisible(false);
		ringPlot.setBackgroundPaint(null);
		ringPlot.setShadowPaint(null);
		// adjust labels
		ringPlot.setLabelOutlinePaint(null);
		ringPlot.setLabelBackgroundPaint(null);
		ringPlot.setLabelShadowPaint(null);
		ringPlot.setLabelGenerator(new StandardPieSectionLabelGenerator("{0} ({2})")); // label (%)
		final boolean likelyDenseLabels = atLeastOneSmallFraction(dataset) && countValidSections(dataset) > 2;
		ringPlot.setSimpleLabels(!likelyDenseLabels);
		ringPlot.setLabelLinksVisible(likelyDenseLabels);
		ringPlot.setLabelLinkStyle(PieLabelLinkStyle.STANDARD);
		return ringPlot;
	}

	private static long countValidSections(final DefaultPieDataset<String> dataset) {
		return dataset.getKeys().stream().filter(key -> {
					final Number value = dataset.getValue(key);
					return value != null && value.doubleValue() > 0 && !Double.isNaN(value.doubleValue());
				}).count();
	}

	private static boolean atLeastOneSmallFraction(final DefaultPieDataset<String> dataset) {
		// Calculate the sum of all dataset values
		final double total = dataset.getKeys().stream().mapToDouble(key -> dataset.getValue(key).doubleValue()).sum();
		for (final String key : dataset.getKeys()) { // Check if any section is less than 5% of the total
			final double value = dataset.getValue(key).doubleValue();
			if (value > 0 && value / total < 0.05)
				return true;
		}
		return false;
	}

    static class HistogramDatasetPlus {
		int nBins;
		long n;
		double q1, q3, min, max;
		HistogramDataset dataset;
		double histArea;
		final String label;
		final DescriptiveStatistics dStats;

		HistogramDatasetPlus(final String label) {
			dStats = new DescriptiveStatistics();
			this.label = label;
		}

		HistogramDatasetPlus(final DescriptiveStatistics stats, final String label) {
			dStats = stats;
			this.label = label;
		}

		List<Double> valuesAsList() {
			return DoubleStream.of(values()).boxed().toList();
		}

		double[] values() {
			return dStats.getValues();
		}

		double mean() {
			return dStats.getMean();
		}

		double std() {
			return dStats.getStandardDeviation();
		}

		void compute() {
			if (nBins > 0) {
				return; // already computed
			}
			n = dStats.getN();
			q1 = dStats.getPercentile(25);
			q3 = dStats.getPercentile(75);
			min = dStats.getMin();
			max = dStats.getMax();
			if (n == 0 || max == min || Double.isNaN(max) || Double.isNaN(min)) {
				nBins = 1;
			}
			else {
				final double binWidth = 2 * (q3 - q1) / Math.cbrt(n); // Freedman-Diaconis rule
				if (binWidth == 0) {
					nBins = (int) Math.round(Math.sqrt(n));
				} else {
					nBins = (int) Math.ceil((max - min) / binWidth);
				}
				nBins = Math.max(1, nBins);
			}
		}

		HistogramDataset getDataset() {
			if (dataset != null) return dataset;
			compute();
			dataset = new HistogramDataset();
			dataset.setType(HistogramType.RELATIVE_FREQUENCY);
			dataset.addSeries(label, values(), nBins);
			return dataset;
		}

		double histogramArea() {
			if (histArea > 0) return histArea;
			histArea = 0;
			dataset = getDataset();
			for (int i = 0; i < dataset.getSeriesCount(); i++) {
				for (int j = 0; j < dataset.getItemCount(i); j++) {
					final double w = dataset.getEndX(i, j).doubleValue() - dataset.getStartX(i, j).doubleValue();
					final double h = dataset.getY(i, j).doubleValue();
					histArea += w * h;
				}
			}
			return histArea;
		}
	}

    /**
     * Creates a horizontal timeline chart with one row per neurite showing color-coded growth phases
     * in sequence, so that each neurite gets one row with colored segments representing different phases.
     *
     * @param neuritePhases Map of neurite IDs to their growth phases
     * @param timeUnits     Units for time axis
     * @return JFreeChart with horizontal timeline visualization
     */
    public static JFreeChart createTimeline(final Map<String, java.util.List<GrowthPhase>> neuritePhases,
                                            final String timeUnits) {
        final DefaultXYDataset dataset = new DefaultXYDataset();
        
        // Sort neurites by relative proportion of each phase type in priority order (RAPID, STEADY, PLATEAU, LAG, RETRACTION)
        final List<String> neuriteIds = new ArrayList<>(neuritePhases.keySet());
        neuriteIds.sort((neurite1, neurite2) -> {
            double score1 = calculatePhasePriorityScore(neuritePhases.get(neurite1));
            double score2 = calculatePhasePriorityScore(neuritePhases.get(neurite2));
            return Double.compare(score2, score1); // Descending order (highest priority first)
        });
        
        final List<String> seriesLabels = new ArrayList<>();
        int seriesIndex = 0;
        int plottedNeuriteIndex = 0;
        for (final String neuriteId : neuriteIds) {
            final List<GrowthPhase> phases = neuritePhases.get(neuriteId);
            if (phases.isEmpty()) {
                continue; // exclude neurites without phases
            }
            seriesLabels.add(WordUtils.capitalizeFully(neuriteId).replace("Neurite", ""));
            for (final GrowthPhase phase : phases) {
                final double[] xData = {phase.startTime(), phase.endTime()};
                final double[] yData = {plottedNeuriteIndex, plottedNeuriteIndex}; // Same Y for horizontal line
                // Create series data
                final double[][] seriesData = new double[2][];
                seriesData[0] = xData; // X values (time)
                seriesData[1] = yData; // Y values (neurite index)
                final String seriesKey = neuriteId + "_" + phase.type() + "_" + seriesIndex;
                dataset.addSeries(seriesKey, seriesData);
                seriesIndex++;
            }
            plottedNeuriteIndex++;
        }

        // Create XY line chart
        final JFreeChart chart = ChartFactory.createXYLineChart(null, "Time (" + timeUnits + ")",
                null, dataset, PlotOrientation.VERTICAL, true, true, false);

        // Customize renderer to draw "horizontal "bars"
        final XYPlot plot = (XYPlot) chart.getPlot();
        final XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        plot.setRenderer(renderer);

        // Set strokes and colors for each phase segment
        seriesIndex = 0;
        for (final String neuriteId : neuriteIds) {
            final List<GrowthPhase> phases = neuritePhases.get(neuriteId);
            if (phases.isEmpty()) {
                continue; // exclude neurites without phases
            }
            for (final GrowthPhase phase : phases) {
                final Color phaseColor = getPhaseColor(phase.type());
                renderer.setSeriesPaint(seriesIndex, phaseColor);
                renderer.setSeriesStroke(seriesIndex, new BasicStroke(15.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                renderer.setSeriesVisibleInLegend(seriesIndex, false); // Hide individual series from legend
                seriesIndex++;
            }
        }

        // Create custom Y-axis with neurite names
        final SymbolAxis yAxis = new SymbolAxis("Neurite", seriesLabels.toArray(new String[0]));
        plot.setRangeAxis(yAxis);
        yAxis.setGridBandsVisible(false);
        yAxis.setTickMarksVisible(false);
        yAxis.setMinorTickMarksVisible(false);
        yAxis.setAxisLineVisible(false);
        yAxis.setInverted(true);

        // Apply styles
        addCustomPhaseLegend(chart, plot);
        applyHistogramStyle(chart, plot);
        plot.setOutlineVisible(false);
        return chart;
    }

    /**
     * Creates a ring plot of growth phases.
     *
     * @param title            The plot title
     * @param phaseFrequencies Map of growth phases  to their frequencies
     * @return JFreeChart with horizontal timeline visualization
     */
    public static SNTChart ringPlot(final String title, final HashMap<GrowthPhaseType, Double> phaseFrequencies) {
        final HashMap<String, Double> donutData = new HashMap<>(phaseFrequencies.size());
        final HashMap<String, Color> donutColors = new HashMap<>(phaseFrequencies.size());
        phaseFrequencies.forEach((phaseType, counts) -> {
            final String key = WordUtils.capitalizeFully(phaseType.toString());
            donutData.put(key, counts);
            donutColors.put(key, getPhaseColor(phaseType));
        });
        return ringPlot(title, donutData, donutColors);
    }

    /**
     * Helper method to get appropriate color for each growth phase type.
     */
    private static Color getPhaseColor(final GrowthPhaseType type) {
        return switch (type) {
            case LAG -> new Color(255, 248, 191, 255); // Yellow
            case RAPID -> new Color(193, 229, 97, 255); // Green
            case STEADY -> new Color(183, 227, 242, 255); // Blue
            case PLATEAU -> new Color(211, 211, 211, 255);  // Gray
            case RETRACTION -> new Color(235, 184, 188, 255); // Red
        };
    }

    /** Add a custom legend showing phase types and their colors. */
    private static void addCustomPhaseLegend(final JFreeChart chart, final XYPlot plot) {
        final LegendItemCollection legendItems = new LegendItemCollection();
        for (final GrowthPhaseType type : GrowthPhaseType.values()) {
            final Color color = getPhaseColor(type);
            final LegendItem item = new LegendItem(
                    WordUtils.capitalizeFully(type.toString()),
                    null, null, null,
                    new java.awt.geom.Rectangle2D.Double(0, 0, 12, 12),
                    color
            );
            legendItems.add(item);
        }
        replaceLegend(chart, plot, legendItems);
    }

    /** Add a custom legend showing phase types and their colors. */
    public static void replaceLegend(final JFreeChart chart, final XYPlot plot, final LegendItemCollection legendItems) {
        plot.setFixedLegendItems(legendItems);
        if (chart.getLegend() != null) {
            chart.getLegend().setVisible(true);
            chart.getLegend().setPosition(RectangleEdge.BOTTOM);
        }
    }
    
    /**
     * Calculates a priority score for sorting neurites based on weighted phase proportions.
     * Uses weighted scoring: RAPID (×10000) > STEADY (×1000) > PLATEAU (×100) > LAG (×10).
     * RETRACTION phases receive a very heavy penalty (×-100000) to push problematic neurites to bottom.
     * 
     * @param phases List of growth phases for a neurite
     * @return Priority score for sorting (higher = more priority, negative for high retraction)
     */
    private static double calculatePhasePriorityScore(final List<GrowthPhase> phases) {
        if (phases.isEmpty()) return 0.0;
        
        double rapidTotal = 0.0;
        double steadyTotal = 0.0;
        double plateauTotal = 0.0;
        double lagTotal = 0.0;
        double retractionTotal = 0.0;
        double totalDuration = 0.0;
        
        // Sum total amounts by phase type and calculate total duration
        for (final GrowthPhase phase : phases) {
            double duration = phase.duration();
            totalDuration += duration;
            
            switch (phase.type()) {
                case RAPID:
                    rapidTotal += duration;
                    break;
                case STEADY:
                    steadyTotal += duration;
                    break;
                case PLATEAU:
                    plateauTotal += duration;
                    break;
                case LAG:
                    lagTotal += duration;
                    break;
                case RETRACTION:
                    retractionTotal += duration;
                    break;
            }
        }
        
        // Avoid division by zero
        if (totalDuration == 0.0) return 0.0;
        
        // Calculate relative proportions (0.0 to 1.0)
        double rapidProportion = rapidTotal / totalDuration;
        double steadyProportion = steadyTotal / totalDuration;
        double plateauProportion = plateauTotal / totalDuration;
        double lagProportion = lagTotal / totalDuration;
        double retractionProportion = retractionTotal / totalDuration;
        
        // Use weighted scoring with heavy penalty for retraction
        // Priority order: RAPID > STEADY > PLATEAU > LAG, with RETRACTION heavily penalized
        double score = (rapidProportion * 10000.0) + 
                      (steadyProportion * 1000.0) + 
                      (plateauProportion * 100.0) + 
                      (lagProportion * 10.0) - 
                      (retractionProportion * 100000.0); // Very heavy penalty for any retraction
        
        // Add duration bonus for tie-breaking within same phase composition ranges
        // This ensures longer neurites appear before shorter ones when phase proportions are similar
        double durationBonus = Math.min(100.0, totalDuration * 0.5);
        
        return score + durationBonus;
    }


    /**
     * This modifies the default BoxAndWhiskerRenderer to achieve the following: 1)
     * Highlight mean w/ a more discrete marker; 2) Do not use far out markers
     * (their definition is not transparent to the user); 3) Make rendering of
     * outliers optional. If outliers are chosen to be rendered, then render all
     * values (the original implementation renders summary values only!?).
     * <p>
     * NB: It has not been thoroughly tested. Horizontal plots are not affected
     * because we're not overriding drawHorizontalItem()
     * </p>
     */
    private static class CustomBoxAndWhiskerRenderer extends BoxAndWhiskerRenderer {

        private static final long serialVersionUID = 1L;
        private double pointSize = 5d;
        private boolean drawOutliers;

        private void setPointSize(final Double pointSize) {
            this.pointSize = pointSize;
        }

        private void setDrawOutliers(final boolean drawOutliers) {
            this.drawOutliers = drawOutliers;
        }

        @Override
        public void drawVerticalItem(final Graphics2D g2, final CategoryItemRendererState state,
                                     final Rectangle2D dataArea, final CategoryPlot plot, final CategoryAxis domainAxis,
                                     final ValueAxis rangeAxis, final CategoryDataset dataset, final int row, final int column) {

            final BoxAndWhiskerCategoryDataset bawDataset = (BoxAndWhiskerCategoryDataset) dataset;

            final double categoryEnd = domainAxis.getCategoryEnd(column, getColumnCount(), dataArea,
                    plot.getDomainAxisEdge());
            final double categoryStart = domainAxis.getCategoryStart(column, getColumnCount(), dataArea,
                    plot.getDomainAxisEdge());
            final double categoryWidth = categoryEnd - categoryStart;

            double xx = categoryStart;
            final int seriesCount = getRowCount();
            final int categoryCount = getColumnCount();

            if (seriesCount > 1) {
                final double seriesGap = dataArea.getWidth() * getItemMargin() / (categoryCount * (seriesCount - 1));
                final double usedWidth = (state.getBarWidth() * seriesCount) + (seriesGap * (seriesCount - 1));
                // offset the start of the boxes if the total width used is smaller
                // than the category width
                final double offset = (categoryWidth - usedWidth) / 2;
                xx = xx + offset + (row * (state.getBarWidth() + seriesGap));
            } else {
                // offset the start of the box if the box width is smaller than the
                // category width
                final double offset = (categoryWidth - state.getBarWidth()) / 2;
                xx = xx + offset;
            }

            double yyAverage;

            final Paint itemPaint = getItemPaint(row, column);
            g2.setPaint(itemPaint);
            final Stroke s = getItemStroke(row, column);
            g2.setStroke(s);

            final RectangleEdge location = plot.getRangeAxisEdge();

            final Number yQ1 = bawDataset.getQ1Value(row, column);
            final Number yQ3 = bawDataset.getQ3Value(row, column);
            final Number yMax = bawDataset.getMaxRegularValue(row, column);
            final Number yMin = bawDataset.getMinRegularValue(row, column);
            Shape box = null;
            if (yQ1 != null && yQ3 != null && yMax != null && yMin != null) {

                final double yyQ1 = rangeAxis.valueToJava2D(yQ1.doubleValue(), dataArea, location);
                final double yyQ3 = rangeAxis.valueToJava2D(yQ3.doubleValue(), dataArea, location);
                final double yyMax = rangeAxis.valueToJava2D(yMax.doubleValue(), dataArea, location);
                final double yyMin = rangeAxis.valueToJava2D(yMin.doubleValue(), dataArea, location);
                final double xxmid = xx + state.getBarWidth() / 2.0;
                final double halfW = (state.getBarWidth() / 2.0) * getWhiskerWidth();

                // draw the body...
                box = new Rectangle2D.Double(xx, Math.min(yyQ1, yyQ3), state.getBarWidth(), Math.abs(yyQ1 - yyQ3));
                if (getFillBox()) {
                    g2.fill(box);
                }

                final Paint outlinePaint = getItemOutlinePaint(row, column);
                if (getUseOutlinePaintForWhiskers()) {
                    g2.setPaint(outlinePaint);
                }
                // draw the upper shadow...
                g2.draw(new Line2D.Double(xxmid, yyMax, xxmid, yyQ3));
                g2.draw(new Line2D.Double(xxmid - halfW, yyMax, xxmid + halfW, yyMax));

                // draw the lower shadow...
                g2.draw(new Line2D.Double(xxmid, yyMin, xxmid, yyQ1));
                g2.draw(new Line2D.Double(xxmid - halfW, yyMin, xxmid + halfW, yyMin));

                g2.setStroke(getItemOutlineStroke(row, column));
                g2.setPaint(outlinePaint);
                g2.draw(box);
            }

            g2.setPaint(getArtifactPaint());

            // draw mean - SPECIAL AIMS REQUIREMENT...
            if (isMeanVisible()) {
                final Number yMean = bawDataset.getMeanValue(row, column);
                if (yMean != null) {
                    yyAverage = rangeAxis.valueToJava2D(yMean.doubleValue(), dataArea, location);
                    final double xxAverage = xx + state.getBarWidth() / 2.0;
                    final Shape s1 = new Line2D.Double(xxAverage - pointSize, yyAverage, xxAverage + pointSize,
                            yyAverage);
                    final Shape s2 = new Line2D.Double(xxAverage, yyAverage - pointSize, xxAverage,
                            yyAverage + pointSize);
                    g2.draw(s1);
                    g2.draw(s2);
                }
            }

            // draw median...
            if (isMedianVisible()) {
                final Number yMedian = bawDataset.getMedianValue(row, column);
                if (yMedian != null) {
                    final double yyMedian = rangeAxis.valueToJava2D(yMedian.doubleValue(), dataArea, location);
                    g2.draw(new Line2D.Double(xx, yyMedian, xx + state.getBarWidth(), yyMedian));
                }
            }

            // draw yOutliers...
            if (drawOutliers) {

                g2.setPaint(itemPaint);

                // draw outliers
                final HashMap<Outlier, Integer> outliers = new HashMap<>();
                final java.util.List<?> yOutliers = bawDataset.getOutliers(row, column);
                final double xCenter = xx + state.getBarWidth() / 2.0;
                if (yOutliers != null) {
                    for (Object yOutlier : yOutliers) {
                        final Number outlierValue = ((Number) yOutlier);
                        final double yyOutlier = rangeAxis.valueToJava2D(outlierValue.doubleValue(), dataArea,
                                location);
                        final Outlier outlier = new Outlier(xCenter, yyOutlier, pointSize);
                        outliers.put(outlier, outliers.getOrDefault(outlier, 1));
                    }

                    outliers.forEach((outlier, count) -> {

                        if (count == 1) {
                            drawOutlier(outlier, g2);
                        } else {
                            final int leftPoints = count / 2;
                            final int rightPoints = count - leftPoints;
                            for (int i = 1; i <= leftPoints; i++) {
                                final double offset = Math.min(i * pointSize, state.getBarWidth() / 2);
                                outlier.setPoint(new Point2D.Double(xCenter - offset, outlier.getY()));
                                drawOutlier(outlier, g2);
                            }
                            for (int i = 1; i <= rightPoints; i++) {
                                final double offset = Math.min(i * pointSize, state.getBarWidth() / 2);
                                outlier.setPoint(new Point2D.Double(xCenter + offset, outlier.getY()));
                                drawOutlier(outlier, g2);
                            }
                        }

                    });
                }
            }
            // collect entity and tool tip information...
            if (state.getInfo() != null && box != null) {
                final EntityCollection entities = state.getEntityCollection();
                if (entities != null) {
                    addItemEntity(entities, dataset, row, column, box);
                }
            }

        }

        private void drawOutlier(final Outlier outlier, final Graphics2D g2) {
            final Point2D point = outlier.getPoint();
            final double size = outlier.getRadius();
            final Ellipse2D dot = new Ellipse2D.Double(point.getX() + size / 2, point.getY(), size, size);
            g2.fill(dot);
        }

    }
}
