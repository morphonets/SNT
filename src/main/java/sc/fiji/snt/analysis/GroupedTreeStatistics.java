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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.entity.EntityCollection;
import org.jfree.chart.labels.BoxAndWhiskerToolTipGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.Outlier;
import org.jfree.chart.renderer.category.BoxAndWhiskerRenderer;
import org.jfree.chart.renderer.category.CategoryItemRendererState;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.statistics.BoxAndWhiskerCategoryDataset;
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.scijava.util.ColorRGB;

import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics.HDPlus;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.SNTColor;

/**
 * Computes statistics from {@link Tree} groups.
 *
 * @see TreeStatistics
 * @see MultiTreeStatistics
 * 
 * @author Tiago Ferreira
 */
public class GroupedTreeStatistics {

	private static final String LENGTH = MultiTreeStatistics.LENGTH;
	private static final String N_TIPS = MultiTreeStatistics.N_TIPS;
	private static final String N_BRANCH_POINTS = MultiTreeStatistics.N_BRANCH_POINTS;

	private final LinkedHashMap<String, MultiTreeStatistics> groups;
	private int minNoOfBins = 1;

	/**
	 * Instantiates a new grouped tree statistics.
	 */
	public GroupedTreeStatistics() {
		groups = new LinkedHashMap<>();
	}

	/**
	 * Adds a comparison group to the analysis queue.
	 *
	 * @param group      the group
	 * @param groupLabel a unique label identifying the group
	 */
	public void addGroup(final Collection<Tree> group, final String groupLabel) {
		final MultiTreeStatistics mStats = new MultiTreeStatistics(group);
		mStats.setLabel(groupLabel);
		groups.put(groupLabel, mStats);
	}

	/**
	 * Adds a comparison group to the analysis queue.
	 *
	 * @param group    the collection of Trees to be analyzed
	 * @param groupLabel a unique label identifying the group
	 * @param swcTypes   SWC type(s) a string with at least 2 characters describing
	 *                   the SWC type allowed in the subtree (e.g., 'axn', or
	 *                   'dendrite')
	 * @throws NoSuchElementException {@code swcTypes} are not applicable to {@code group}
	 */
	public void addGroup(final Collection<Tree> group, final String groupLabel, final String... swcTypes)
			throws NoSuchElementException {
		final MultiTreeStatistics mStats = new MultiTreeStatistics(group, swcTypes);
		mStats.setLabel(groupLabel);
		groups.put(groupLabel, mStats);
	}

	/**
	 * Gets the group statistics.
	 *
	 * @param groupLabel the unique label identifying the group
	 * @return the group statistics or null if no group is mapped to
	 *         {@code groupLabel}
	 */
	public MultiTreeStatistics getGroupStats(final String groupLabel) {
		return groups.get(groupLabel);
	}

	/**
	 * Gets the number of Trees in a specified group.
	 *
	 * @param groupLabel the unique label identifying the group
	 * @return the number of Trees or -1 if no group is mapped to
	 *         {@code groupLabel}
	 */
	public int getN(final String groupLabel) {
		final MultiTreeStatistics sts = groups.get(groupLabel);
		return (sts == null) ? -1 : sts.getGroup().size();
	}

	/**
	 * Gets the group identifiers currently queued for analysis.
	 *
	 * @return the group identifiers
	 */
	public Collection<String> getGroups() {
		return groups.keySet();
	}

	/**
	 * Gets the relative frequencies histogram for a univariate measurement. The
	 * number of bins is determined using the Freedman-Diaconis rule.
	 *
	 * @param measurement the measurement ({@link MultiTreeStatistics#N_NODES
	 *                    N_NODES}, {@link MultiTreeStatistics#NODE_RADIUS
	 *                    NODE_RADIUS}, etc.)
	 * @return the frame holding the histogram
	 * @see MultiTreeStatistics#getMetrics()
	 * @see TreeStatistics#getMetrics()
	 * @see #setMinNBins(int)
	 */
	public SNTChart getHistogram(final String measurement) {
		final String normMeasurement = MultiTreeStatistics.getNormalizedMeasurement(measurement, true);
		// Retrieve all HistogramDatasetPlus instances
		final double[] limits = new double[] {Double.MAX_VALUE, Double.MIN_VALUE};
		for (final String groupLabel : getGroups()) {
			final double groupMax = getGroupStats(groupLabel).getDescriptiveStats(normMeasurement).getMax();
			final double groupMin = getGroupStats(groupLabel).getDescriptiveStats(normMeasurement).getMin();
			if (groupMax < limits[0]) limits[0] = groupMin;
			if (groupMax > limits[1]) limits[1] = groupMax;
		}
		final LinkedHashMap<String, HDPlus> hdpMap = new LinkedHashMap<>();
		final ArrayList<Integer> bins = new ArrayList<>();
		for (final Entry<String, MultiTreeStatistics> entry : groups.entrySet()) {
			final HDPlus hdp = entry.getValue().new HDPlus(normMeasurement);
			hdp.compute(true);
			bins.add(hdp.nBins);
			hdpMap.put(entry.getKey(), hdp);
		}
		// Add all series
		final int maxBins = bins.stream().mapToInt(v -> v).max().orElse(1);
		final int finalBinCount = Math.max(minNoOfBins, maxBins);
		final HistogramDataset dataset = new HistogramDataset();
		dataset.setType(HistogramType.RELATIVE_FREQUENCY);
		hdpMap.forEach((label, hdp) -> {
			dataset.addSeries(label, hdp.valuesAsArray(), finalBinCount, limits[0], limits[1]);
		});
		return AnalysisUtils.createHistogram(normMeasurement, hdpMap.size(), dataset);
	}

	/**
	 * Assembles a Box and Whisker Plot for the specified measurement (cell morphometry).
	 *
	 * @param measurement the measurement ({@link MultiTreeStatistics#N_NODES
	 *                    N_NODES}, {@link MultiTreeStatistics#NODE_RADIUS
	 *                    NODE_RADIUS}, etc.)
	 * @return the frame holding the box plot
	 * @see MultiTreeStatistics#getMetrics()
	 * @see TreeStatistics#getMetrics()
	 */
	public SNTChart getBoxPlot(final String measurement) {

		final String normMeasurement = MultiTreeStatistics.getNormalizedMeasurement(measurement, true);
		final DefaultBoxAndWhiskerCategoryDataset dataset = new DefaultBoxAndWhiskerCategoryDataset();
		groups.forEach((label, mstats) -> {
			final HDPlus hdp = mstats.new HDPlus(normMeasurement);
			dataset.add(hdp.values, normMeasurement, label);
		});
		final JFreeChart chart = ChartFactory.createBoxAndWhiskerChart(null, null, normMeasurement, dataset, false);
		assignRenderer((CategoryPlot) chart.getPlot(), true);
		final int height = 400;
		final double width = (groups.size() < 4) ? height / 1.5 : height * 1.5;
		return new SNTChart("Box-plot", chart,  new Dimension((int) width, height));
	}

	private CustomBoxAndWhiskerRenderer assignRenderer(final CategoryPlot plot, final boolean monochrome) {
		plot.setBackgroundPaint(null);
		plot.setRangePannable(true);
		plot.setDomainGridlinesVisible(false);
		plot.setRangeGridlinesVisible(false);
		plot.setOutlineVisible(false);
		final CustomBoxAndWhiskerRenderer renderer = new CustomBoxAndWhiskerRenderer();
		plot.setRenderer(renderer);
		renderer.setPointSize((double) plot.getRangeAxis().getTickLabelFont().getSize2D() / 2);
		renderer.setDrawOutliers(true);
		renderer.setItemMargin(0);
		renderer.setDefaultPaint(Color.BLACK);
		if (monochrome) {
			for (int i = 0; i < groups.size(); i++) {
				renderer.setSeriesPaint(i, Color.GRAY);
				renderer.setSeriesOutlinePaint(i, Color.BLACK);
				renderer.setSeriesItemLabelPaint(i, Color.BLACK);
			}
		} else {
			final ColorRGB[] colors = SNTColor.getDistinctColors(groups.size());
			for (int i = 0; i < groups.size(); i++) {
				final Color color = new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue());
				renderer.setSeriesPaint(i, color);
				renderer.setSeriesOutlinePaint(i, color);
				renderer.setSeriesItemLabelPaint(i, color);
			}
		}
		String tooltipformat = "<html><body>Max: {5}<br>Q3: {7}<br>Median: {3}<br>Q1: {6}<br>Min: {4}<br>Mean: {2}</body></html>";
		renderer.setDefaultToolTipGenerator(new BoxAndWhiskerToolTipGenerator(tooltipformat, NumberFormat.getNumberInstance()));
		renderer.setUseOutlinePaintForWhiskers(true);
		renderer.setMaximumBarWidth(0.10);
		renderer.setMedianVisible(true);
		renderer.setMeanVisible(true);
		renderer.setFillBox(false);
		return renderer;
	}

	/**
	 * Assembles a Box and Whisker Plot for the specified feature (absolute
	 * measurements).
	 *
	 * @param feature     the feature ({@value MultiTreeStatistics#LENGTH},
	 *                    {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                    {@value MultiTreeStatistics#N_TIPS}, etc.). Note that the
	 *                    majority of {@link MultiTreeStatistics#getAllMetrics()}
	 *                    metrics are currently not supported.
	 * @param annotations the BrainAnnotation to be queried. Null not allowed.
	 * @return the frame holding the box plot
	 */
	public SNTChart getBoxPlot(final String feature, final Collection<BrainAnnotation> annotations) {
		return getBoxPlot(feature, annotations, Double.MIN_VALUE, false);
	}

	/**
	 * Assembles a Box and Whisker Plot for the specified feature.
	 *
	 * @param feature     the feature ({@value MultiTreeStatistics#LENGTH},
	 *                    {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                    {@value MultiTreeStatistics#N_TIPS}, etc.). Note that the
	 *                    majority of {@link MultiTreeStatistics#getAllMetrics()}
	 *                    metrics are currently not supported.
	 * @param annotations the BrainAnnotation to be queried. Null not allowed.
	 * @param cutoff      a filtering option. If the computed {@code feature} for an
	 *                    annotation is below this value, that annotation is
	 *                    excluded from the plot
	 * @param normalize   If true, values are retrieved as ratios. E.g., If
	 *                    {@code feature} is {@value MultiTreeStatistics#LENGTH},
	 *                    and {@code cutoff} 0.1, BrainAnnotations in
	 *                    {@code annotations} associated with less than 10% of cable
	 *                    length are ignored.
	 * 
	 * @return the frame holding the box plot
	 */
	public SNTChart getBoxPlot(final String feature, final Collection<BrainAnnotation> annotations, final double cutoff, final boolean normalize) {
		final String normFeature = getBoxPlotFeature(feature);
		if (normFeature.equalsIgnoreCase("unknown")) {
			throw new IllegalArgumentException("Unrecognizable measurement \"" + feature);
		}

		class AnnotatedValues {

			final HashMap<String, ArrayList<Double>> map  = new HashMap<>();

			AnnotatedValues(final Collection<BrainAnnotation> annotations, final Collection<Tree> trees) {
				for (final BrainAnnotation annotation : annotations) {
					if (annotation == null) continue;
					final ArrayList<Double> values = new ArrayList<>();
					for (final Tree tree: trees) {
						if (tree == null) continue;
						final TreeAnalyzer analyzer = new TreeAnalyzer(tree);
						double value;
						switch (normFeature) {
						case LENGTH:
							value = (normalize) ? analyzer.getCableLengthNorm(annotation) : analyzer.getCableLength(annotation);
							break;
						case N_BRANCH_POINTS:
							value = (normalize) ? analyzer.getNbranchPointsNorm(annotation) : analyzer.getNbranchPoints(annotation);
							break;
						case N_TIPS:
							value = (normalize) ? analyzer.getNtipsNorm(annotation) : analyzer.getNtips(annotation);
							break;
						default:
							throw new IllegalArgumentException("Unrecognized feature");
						}
						if (value > cutoff) values.add(value);
					}
					if (!values.isEmpty()) map.put(annotation.acronym(), values);
				}
			}
		}

		final HashMap<String, AnnotatedValues> mappedValues = new HashMap<>();
		final DefaultBoxAndWhiskerCategoryDataset dataset = new DefaultBoxAndWhiskerCategoryDataset();
		groups.forEach((groupLabel, groupStats) -> {
			mappedValues.put(groupLabel, new AnnotatedValues(annotations, groupStats.getGroup()));
		});
		mappedValues.forEach( (groupLabel, annotatedLenghts) -> {
			annotatedLenghts.map.forEach( (annotationLabel, values) -> {
				dataset.add(values, groupLabel, annotationLabel);
			});
		});

		final JFreeChart chart = ChartFactory.createBoxAndWhiskerChart(null, null, normFeature, dataset, true);
		assignRenderer((CategoryPlot) chart.getPlot(), false);
		final int height = 400;
		final double width = (groups.size() < 4) ? height / 1.5 : height * 1.5;
		return new SNTChart("Box-plot", chart,  new Dimension((int) width, height));
	}

	private String getBoxPlotFeature(final String guess) {
		if (guess == null || guess.isEmpty()) return LENGTH;
		final String normGuess = guess.toLowerCase();
		if (normGuess.indexOf("len") != -1 || normGuess.indexOf("cable") != -1) {
			return LENGTH;
		}
		if (normGuess.indexOf("bp") != -1 || normGuess.indexOf("branch") != -1 || normGuess.indexOf("junction") != -1) {
			return N_BRANCH_POINTS;
		}
		if (normGuess.indexOf("tip") != -1 || normGuess.indexOf("end") != -1) {
			return N_TIPS;
		}
		return "unknown";
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
	private class CustomBoxAndWhiskerRenderer extends BoxAndWhiskerRenderer {

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
					for (int i = 0; i < yOutliers.size(); i++) {
						final Number outlierValue = ((Number) yOutliers.get(i));
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

	/* IDE debug method */
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final GroupedTreeStatistics groupedStats = new GroupedTreeStatistics();
		groupedStats.addGroup(sntService.demoTrees().subList(0, 4), "Group 1");
		groupedStats.addGroup(sntService.demoTrees().subList(2, 4), "Group 2");
		groupedStats.getHistogram(TreeStatistics.INTER_NODE_DISTANCE).show();
		groupedStats.getBoxPlot("node dx sq").setVisible(true);

	}

	/**
	 * Sets the minimum number of bins when assembling histograms.
	 *
	 * @param minNoOfBins the minimum number of bins.
	 */
	public void setMinNBins(int minNoOfBins) {
		this.minNoOfBins = minNoOfBins;
	}
}
