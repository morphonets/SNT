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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.math3.stat.inference.OneWayAnova;
import org.apache.commons.math3.stat.inference.TTest;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.StandardFlowLabelGenerator;
import org.jfree.chart.plot.flow.FlowPlot;
import org.jfree.data.flow.DefaultFlowDataset;
import org.jfree.data.flow.FlowDataset;
import org.jfree.data.flow.FlowKey;
import org.jfree.data.flow.NodeKey;
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.scijava.util.ColorRGB;

import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
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
	 * Computes a two-sample, two-tailed t-test P-value for two of groups being
	 * analyzed. It is assumed that data fulfills basic assumptions on normality,
	 * variance homogeneity, etc.
	 *
	 * @param measurement the measurement ({@link MultiTreeStatistics#N_NODES
	 *                    N_NODES}, {@link MultiTreeStatistics#NODE_RADIUS
	 *                    NODE_RADIUS}, etc.)
	 * @param group1Label the unique label identifying group 1
	 * @param group2Label the unique label identifying group 2
	 * @return the p-value
	 * @throws MathIllegalArgumentException If the computation fails
	 */
	public double tTest(final String measurement, final String group1Label, final String group2Label)
			throws MathIllegalArgumentException {
		return new TTest().tTest(getGroupStats(group1Label).getSummaryStats(measurement),
				getGroupStats(group2Label).getSummaryStats(measurement));
	}

	/**
	 * Computes the one-way ANOVA P-value for all the groups being analyzed. It is
	 * assumed that data fulfills basic assumptions on normality, variance
	 * homogeneity, sample size, etc.
	 *
	 * @param measurement the measurement ({@link MultiTreeStatistics#N_NODES
	 *                    N_NODES}, {@link MultiTreeStatistics#NODE_RADIUS
	 *                    NODE_RADIUS}, etc.)
	 * @return the p-value
	 * @throws MathIllegalArgumentException If the computation fails, E.g., because
	 *                                      less than 2 groups exist, one of the
	 *                                      groups contains less than two trees, or
	 *                                      a convergence error occurs
	 *
	 */
	public double anovaPValue(final String measurement) throws MathIllegalArgumentException {
		final List<SummaryStatistics> allSummaryStats = new ArrayList<>();
		groups.values().forEach(stats -> allSummaryStats.add(stats.getSummaryStats(measurement)));
		return new OneWayAnova().anovaPValue(allSummaryStats, true);
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
	public List<String> getGroups() {
		return new ArrayList<>(groups.keySet());
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
	 * @see TreeStatistics#getMetric(String)
	 * @see #setMinNBins(int)
	 */
	public SNTChart getHistogram(final String measurement) {
		return getHistogram(measurement, false);
	}

	/**
	 * Gets the relative frequencies histogram for a univariate measurement as a
	 * polar (rose) plot assuming a data range between [0-360]. The number of bins
	 * is determined using the Freedman-Diaconis rule.
	 *
	 * @param measurement the measurement (e.g.,
	 *                    {@link MultiTreeStatistics#AVG_REMOTE_ANGLE}
	 * @return the frame holding the histogram
	 * @see #getHistogram(String)
	 * @see #setMinNBins(int)
	 */
	public SNTChart getPolarHistogram(final String measurement) {
		return getHistogram(measurement, true);
	}

	private SNTChart getHistogram(final String measurement, final boolean polar) {
		final String normMeasurement = TreeStatistics.getNormalizedMeasurement(measurement);
		// Retrieve all HistogramDatasetPlus instances
		final double[] limits = new double[] {Double.MAX_VALUE, Double.MIN_VALUE};
		for (final String groupLabel : getGroups()) {
			final double groupMax = getGroupStats(groupLabel).getDescriptiveStats(normMeasurement).getMax();
			final double groupMin = getGroupStats(groupLabel).getDescriptiveStats(normMeasurement).getMin();
			if (groupMin < limits[0]) limits[0] = groupMin;
			if (groupMax > limits[1]) limits[1] = groupMax;
		}
		final LinkedHashMap<String, AnalysisUtils.HistogramDatasetPlus> hdpMap = new LinkedHashMap<>();
		final ArrayList<Integer> bins = new ArrayList<>();
		for (final Entry<String, MultiTreeStatistics> entry : groups.entrySet()) {
			final HDPlus hdp = entry.getValue().new HDPlus(normMeasurement);
			hdp.compute();
			bins.add(hdp.nBins);
			hdpMap.put(entry.getKey(), hdp);
		}
		// Add all series
		final int maxBins = bins.stream().mapToInt(v -> v).max().orElse(1);
		final int finalBinCount = Math.max(minNoOfBins, maxBins);
		final HistogramDataset dataset = new HistogramDataset();
		dataset.setType(HistogramType.RELATIVE_FREQUENCY);
		hdpMap.forEach((label, hdp) -> {
			dataset.addSeries(label, hdp.values(), finalBinCount, limits[0], limits[1]);
		});
		return (polar) ?
				AnalysisUtils.createPolarHistogram(normMeasurement, "", dataset, hdpMap.size(), finalBinCount)
				: AnalysisUtils.createHistogram(normMeasurement, "", hdpMap.size(), dataset,
				new ArrayList<>(hdpMap.values()));
	}

	/**
	 * Assembles a Box and Whisker Plot for the specified measurement (cell morphometry).
	 *
	 * @param measurement the measurement ({@link MultiTreeStatistics#N_NODES
	 *                    N_NODES}, {@link MultiTreeStatistics#NODE_RADIUS
	 *                    NODE_RADIUS}, etc.)
	 * @return the frame holding the box plot
	 * @see MultiTreeStatistics#getMetrics()
	 * @see TreeStatistics#getMetric(String)
	 */
	public SNTChart getBoxPlot(final String measurement) {
		final String normMeasurement = TreeStatistics.getNormalizedMeasurement(measurement);
		final DefaultBoxAndWhiskerCategoryDataset dataset = new DefaultBoxAndWhiskerCategoryDataset();
		groups.forEach((label, mstats) -> {
			final HDPlus hdp = mstats.new HDPlus(normMeasurement);
			dataset.add(hdp.valuesAsList(), normMeasurement, label);
		});
        return AnalysisUtils.boxPlot(normMeasurement, dataset);
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
	 * @param annotations the BrainAnnotations to be queried. Null not allowed.
	 * @return the box plot
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
	 * @param annotations the BrainAnnotations to be queried. Null not allowed.
	 * @param cutoff      a filtering option. If the computed {@code feature} for an
	 *                    annotation is below this value, that annotation is
	 *                    excluded from the plot
	 * @param normalize   If true, values are retrieved as ratios. E.g., If
	 *                    {@code feature} is {@value MultiTreeStatistics#LENGTH},
	 *                    and {@code cutoff} 0.1, BrainAnnotations in
	 *                    {@code annotations} associated with less than 10% of cable
	 *                    length are ignored.
	 * 
	 * @return the box plot
	 */
	public SNTChart getBoxPlot(final String feature, final Collection<BrainAnnotation> annotations, final double cutoff, final boolean normalize) {
		final String normFeature = getBoxOrFlowPlotFeature(feature);
		if (normFeature.equalsIgnoreCase("unknown")) {
			throw new IllegalArgumentException("Unrecognizable measurement: " + feature);
		}
		final HashMap<String, AnnotatedValues> mappedValues = new HashMap<>();
		final DefaultBoxAndWhiskerCategoryDataset dataset = new DefaultBoxAndWhiskerCategoryDataset();
		groups.forEach((groupLabel, groupStats) -> {
			final AnnotatedValues av = new AnnotatedValues(normFeature, cutoff, normalize);
			av.compute(annotations, groupStats.getGroup());
			mappedValues.put(groupLabel, av);
		});
		mappedValues.forEach( (groupLabel, annotatedMeasurements) -> {
			annotatedMeasurements.map.forEach( (brainannotation, values) -> {
				dataset.add(values, groupLabel, BrainAnnotation.simplifiedString(brainannotation));
			});
		});
        final SNTChart chart = AnalysisUtils.boxPlot(normFeature, dataset);
        chart.setTitle("Box-plot [Group Comparison]");
        return chart;
	}

	/**
	 * Assembles a Box and Whisker Plot for the specified feature.
	 *
	 * @param feature the feature ({@value MultiTreeStatistics#LENGTH},
	 *                {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                {@value MultiTreeStatistics#N_TIPS}, etc.). Note that the
	 *                majority of {@link MultiTreeStatistics#getAllMetrics()}
	 *                metrics are currently not supported.
	 * @param depth   the ontological depth of the compartments to be considered
	 * @param cutoff  a filtering option. If the computed {@code feature} for an
	 *                annotation is below this value, that annotation is excluded
	 *                from the plot * @param normalize If true, values are retrieved
	 *                as ratios. E.g., If {@code feature} is
	 *                {@value MultiTreeStatistics#LENGTH}, and {@code cutoff} 0.1,
	 *                BrainAnnotations in {@code annotations} associated with less
	 *                than 10% of cable length are ignored.
	 * @return the box plot
	 */
	public SNTChart getBoxPlot(final String feature, final int depth, final double cutoff, final boolean normalize) {
		final Set<BrainAnnotation> union = new HashSet<>();
		getGroups().forEach(group -> {
			union.addAll(getGroupStats(group).getAnnotations(depth));
		});
		return getBoxPlot(feature, union, cutoff, normalize);
	}

	/**
	 * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
	 * "mean" as integration statistic, and no cutoff value.
	 * 
	 * @see #getFlowPlot(String, Collection, String, double, boolean)
	 */
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final boolean normalize) {
		return getFlowPlot(feature, annotations, "mean", Double.MIN_VALUE, normalize);
	}

	/**
	 * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
	 * "mean" as integration statistic, no cutoff value, and all of the brain
	 * regions of the specified ontology depth.
	 *
	 * @param feature the feature ({@value MultiTreeStatistics#LENGTH},
	 *                {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                {@value MultiTreeStatistics#N_TIPS}, etc.).
	 * @param depth   the ontological depth of the compartments to be considered
	 * @return the flow plot
	 * @see #getFlowPlot(String, Collection, String, double, boolean)
	 */
	public SNTChart getFlowPlot(final String feature, final int depth) {
		return getFlowPlot(feature, depth, Double.MIN_VALUE, true);
	}

	/**
	 * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
	 * "mean" as integration statistic, no cutoff value, and all of the brain
	 * regions of the specified ontology depth. *
	 *
	 * @param feature   the feature ({@value MultiTreeStatistics#LENGTH},
	 *                  {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                  {@value MultiTreeStatistics#N_TIPS}, etc.)
	 * @param depth     the ontological depth of the compartments to be considered
	 * @param cutoff    a filtering option. If the computed {@code feature} for an
	 *                  annotation is below this value, that annotation is excluded
	 *                  from the plot
	 * @param normalize If true, values are retrieved as ratios. E.g., If
	 *                  {@code feature} is {@value MultiTreeStatistics#LENGTH},
	 *                  and {@code cutoff} 0.1, BrainAnnotations in
	 *                  {@code annotations} associated with less than 10% of cable
	 *                  length are ignored.
	 * @return the flow plot
	 */
	public SNTChart getFlowPlot(final String feature, final int depth, final double cutoff, final boolean normalize) {
		final Set<BrainAnnotation> union = new HashSet<>();
		getGroups().forEach(group -> {
			union.addAll(getGroupStats(group).getAnnotations(depth));
		});
		return getFlowPlot(feature, union, "mean", cutoff, normalize);
	}

	/**
	 * Assembles a Flow plot (aka Sankey diagram) for the specified feature.
	 *
	 * @param feature     the feature ({@value MultiTreeStatistics#LENGTH},
	 *                    {@value MultiTreeStatistics#N_BRANCH_POINTS},
	 *                    {@value MultiTreeStatistics#N_TIPS}, etc.). Note that the
	 *                    majority of {@link MultiTreeStatistics#getAllMetrics()}
	 *                    metrics are currently not supported.
	 * @param annotations the BrainAnnotations to be queried. Null not allowed.
	 * @param statistic   the integration statistic (lower case). Either "mean",
	 *                    "sum", "min" or "max". Null not allowed.
	 * @param cutoff      a filtering option. If the computed {@code feature} for an
	 *                    annotation is below this value, that annotation is
	 *                    excluded from the plot
	 * @param normalize   If true, values are retrieved as ratios. E.g., If
	 *                    {@code feature} is {@value MultiTreeStatistics#LENGTH},
	 *                    and {@code cutoff} 0.1, BrainAnnotations in
	 *                    {@code annotations} associated with less than 10% of cable
	 *                    length are ignored.
	 * 
	 * @return the SNTChart holding the flow plot
	 */
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final String statistic, final double cutoff, final boolean normalize) {
		final String normFeature = getBoxOrFlowPlotFeature(feature);
		if (normFeature.equalsIgnoreCase("unknown")) {
			throw new IllegalArgumentException("Unrecognizable measurement \"" + feature);
		}
	
		final TreeMap<String, AnnotatedValues> mappedValues = new TreeMap<>(); // keep groups sorted alphabetically
		groups.forEach((groupLabel, groupStats) -> {
			final AnnotatedValues av = new AnnotatedValues(normFeature, cutoff, normalize);
			av.compute(annotations, groupStats.getGroup());
			mappedValues.put(groupLabel, av);
		});
		final boolean singleCell = isSingleCell();
		final DefaultFlowDataset<FlowNode> dataset = new DefaultFlowDataset<>();
		final FlowPlot plot = new FlowPlot(dataset);
		final Color[] groupColors = SNTColor.getDistinctColorsAWT(groups.size());
		final List<Color> swatchColors = new ArrayList<>();
		final int stage = 0; // constant
		int groupIdx = 0;
		for (Map.Entry<String, AnnotatedValues> entry : mappedValues.entrySet()) {
			final String groupLabel = entry.getKey();
			final AnnotatedValues annotValues = entry.getValue();
			final FlowNode source = new FlowNode(groupLabel);
			if (!singleCell)
				source.setExtraDetails(" [Multiple Cells, N= " + getGroupStats(groupLabel).getGroup().size() +"]");
			plot.setNodeFillColor(new NodeKey<>(stage, source), groupColors[groupIdx++]);
			annotValues.map.forEach((brainAnnot, annotatedMeasurements) -> {
				final FlowNode destination = new FlowNode(brainAnnot, normalize);
				destination.numericLabels = singleCell;
				destination.flow = getSingleValueFromList(annotatedMeasurements, statistic);
				if (normalize)
					destination.flow *= 100; // percent
				dataset.setFlow(stage, source, destination, destination.flow);
				final ColorRGB color = brainAnnot.color();
				swatchColors.add((null == color) ? Color.GRAY : new Color(color.getARGB()));
			});
		}

		plot.setNodeColorSwatch(swatchColors);
		plot.setToolTipGenerator(new FlowToolTipGenerator(normFeature, normalize));
		final SNTChart chart = new SNTChart("Flow Plot [Group Comparison]", new JFreeChart(plot));
		applyFlowPlotLegend(chart, normFeature, statistic, cutoff, normalize, singleCell);
		return chart;
	}

	private void applyFlowPlotLegend(final SNTChart chart, final String metric, final String statistic, final double cutoff,
			final boolean normalize, final boolean singleCell) {
		final StringBuilder title = new StringBuilder();
		if (normalize)
			title.append("Normalized ");
		title.append(metric);
		if (!normalize)
			title.append(" (").append( getGroupStats(getGroups().get(0)).getUnit(metric)).append(")");
		title.append(" "); // padding margin
		final StringBuilder tooltip = new StringBuilder("<HTML><div WIDTH=600><b>NB</b>: ");
		if (!singleCell)
			tooltip.append("For each neuron, ");
		tooltip.append("<i>").append(metric).append("</i> was retrieved at each brain region");
		if (normalize)
			tooltip.append(" and then divided by the neuron's total ").append(metric);
		if (!singleCell)
			tooltip.append(". Flows represent the ").append(statistic)
					.append(" of the measurements for all the cells in the group.");
		if (!Double.isNaN(cutoff) && cutoff > Double.MIN_VALUE) {
			final String v = (normalize) ? String.format("%.2f%%", cutoff * 100) : String.format("%.3f", cutoff);
			if (singleCell)
				tooltip.append(".<br>Brain");
			else
				tooltip.append(".<br>For each neuron, brain");
			tooltip.append(" regions associated with less than ").append(v).append(" of the neuron's <i>")
					.append(metric).append("</i> have been ignored.");
		}
		chart.annotate(title.toString(), tooltip.toString(), "right");
	}

	private boolean isSingleCell() {
		return groups.size() == 1 && getN(getGroups().get(0)) == 1;
	}

	private double getSingleValueFromList(final List<Double> values, final String combineMethod) {
		switch (combineMethod.toLowerCase()) {
		case "sum":
			return values.stream().mapToDouble(Double::doubleValue).sum();
		case "min":
			return values.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
		case "max":
			return values.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
		case "mean":
		case "average":
		case "avg":
			return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
		case "median":
			return org.jfree.data.statistics.Statistics.calculateMedian(values);
		default:
			throw new IllegalArgumentException("Unknown method: " + combineMethod);
		}
	}

	private String getBoxOrFlowPlotFeature(final String guess) {
		if (guess == null || guess.isEmpty()) return LENGTH;
		final String normGuess = guess.toLowerCase();
		if (normGuess.contains("len") || normGuess.contains("cable")) {
			return LENGTH;
		}
		if (normGuess.contains("bp") || normGuess.contains("branch") || normGuess.contains("junction")) {
			return N_BRANCH_POINTS;
		}
		if (normGuess.contains("tip") || normGuess.contains("end")) {
			return N_TIPS;
		}
		return "unknown";
	}

	/**
	 * Sets the minimum number of bins when assembling histograms.
	 *
	 * @param minNoOfBins the minimum number of bins.
	 */
	public void setMinNBins(int minNoOfBins) {
		this.minNoOfBins = minNoOfBins;
	}


	private static class AnnotatedValues {

		final String normFeature;
		final boolean normalize;
		final double cutoff;
		TreeMap<BrainAnnotation, ArrayList<Double>> map;

		AnnotatedValues(final String normFeature, final double cutoff, final boolean normalize) {
			this.normalize = normalize;
			this.cutoff = cutoff;
			this.normFeature = normFeature;
		}
	
		void compute(final Collection<BrainAnnotation> annotations, final Collection<Tree> trees) {
			map = new TreeMap<>(BrainAnnotation.comparator()); // could also be sorted by flow
			for (final BrainAnnotation annotation : annotations) {
				if (annotation == null)
					continue;
				final ArrayList<Double> values = new ArrayList<>();
				for (final Tree tree : trees) {
					if (tree == null)
						continue;
					final TreeStatistics analyzer = new TreeStatistics(tree);
					final boolean includeChildren = annotation.getOntologyDepth() > 0;
					double value;
					switch (normFeature) {
					case LENGTH:
						value = (normalize) ? analyzer.getCableLengthNorm(annotation, includeChildren)
								: analyzer.getCableLength(annotation, includeChildren);
						break;
					case N_BRANCH_POINTS:
						value = (normalize) ? analyzer.getNBranchPointsNorm(annotation, includeChildren)
								: analyzer.getNBranchPoints(annotation, includeChildren);
						break;
					case N_TIPS:
						value = (normalize) ? analyzer.getNTipsNorm(annotation, includeChildren)
								: analyzer.getNTips(annotation, includeChildren);
						break;
					default:
						throw new IllegalArgumentException("Unrecognized feature");
					}
					if (value > cutoff)
						values.add(value);
				}
				if (!values.isEmpty())
					map.put(annotation, values);
			}
		}
	}

	class FlowToolTipGenerator extends StandardFlowLabelGenerator {

		private static final long serialVersionUID = -4171623865505054454L;
		private final String metricString;

		FlowToolTipGenerator(final String metric, final boolean normalized) {
			metricString = (normalized) ? metric + " (normalized)" : metric;
		}

		@Override
		public String generateLabel(final FlowDataset dataset, final FlowKey key) {
			try {
				return generateLabelInternal(dataset, key);
			} catch (final Exception ignored) {
				// fallback to defaults to ensure the plot can be displayed
				return super.generateLabel(dataset, key);
			}
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		public String generateLabelInternal(final FlowDataset dataset, final FlowKey key) {
			final FlowNode source = (FlowNode) key.getSource();
			final FlowNode destination = (FlowNode) key.getDestination();
			Number value = dataset.getFlow(key.getStage(), source, destination);
			final StringBuilder sb = new StringBuilder("<HTML>");
			sb.append("<dl>");
			sb.append("<dt>").append("Source:").append("</dt>");
			sb.append("<dd>").append(source.toString()).append(" ");
			source.appendDescription(sb);
			sb.append("</dd>");
			sb.append("<dt>").append("Destination:").append("</dt>");
			sb.append("<dd>");
			destination.appendDescription(sb);
			sb.append("</dd>");
			sb.append("<dt>Flow:</dt>");
			sb.append("<dd>");
			sb.append(String.format("%.3f [%s]", value.floatValue(), metricString));
			sb.append("</dd>");
			sb.append("</dl>");
			return sb.toString();
		}
	}

	private class FlowNode implements Comparable<FlowNode> {

		final BrainAnnotation annot;
		final String label;
		final boolean percentage;
		StringBuilder extraDetails;
		boolean numericLabels;
		double flow = Double.NaN;

		FlowNode(final BrainAnnotation annot, final boolean percentage) {
			this.annot = annot;
			this.label = BrainAnnotation.simplifiedString(annot);
			this.percentage = percentage;
		}

		FlowNode(final String label) {
			this.annot = null;
			this.label = label;
			this.percentage = false;
		}
	
		void setExtraDetails(final Object obj) {
			extraDetails = new StringBuilder(obj.toString());
		}

		void appendDescription(final StringBuilder sb) {
			if (annot != null) {
				sb.append(annot.acronym());
				sb.append(", ").append(annot.name());
				sb.append("<br>");
				sb.append("Ontology level: ").append(annot.getOntologyDepth());
				sb.append("<br>");
				final BrainAnnotation parent = annot.getParent();
				if (parent != null)
					sb.append("Parent: ").append(parent.name());
				else
					sb.append("N/A");
			}
			if (extraDetails != null) {
				sb.append(extraDetails);
			}
		}

		@Override
		public String toString() {
			// defines node labels in 
			if (!numericLabels)
				return label;
			if (percentage)
				return String.format("%s (%.1f%%)", label, flow);
			return label + " (" + SNTUtils.formatDouble(flow, 2) + ")";
		}

		@Override
		public int compareTo(final FlowNode o) {
			if (annot == null) {
				return label.compareTo(o.label);
			}
			return annot.acronym().compareTo(o.annot.acronym());
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getEnclosingInstance().hashCode();
			// NB: flow cannot influence hasCode()!
			result = prime * result + Objects.hash(annot, label);
			return result;
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof FlowNode)) {
				return false;
			}
			final FlowNode other = (FlowNode) obj;
			if (!getEnclosingInstance().equals(other.getEnclosingInstance())) {
				return false;
			}
			// NB: flow cannot influence equals()!
			return Objects.equals(annot, other.annot) && Objects.equals(label, other.label);
		}

		private GroupedTreeStatistics getEnclosingInstance() {
			return GroupedTreeStatistics.this;
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
		groupedStats.getBoxPlot("node dx sq").show();
		groupedStats.getFlowPlot("cable length", 8, .2, true).show();
	}
}
