/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingDouble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.jfree.chart.JFreeChart;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.PointInImage;

/**
 * Computes summary and descriptive statistics from univariate properties of
 * {@link Tree} groups. For analysis of individual Trees use
 * {@link TreeStatistics}.
 *
 * @author Tiago Ferreira
 */
public class MultiTreeStatistics extends TreeStatistics {

	/*
	 * NB: These should all be Capitalized expressions in lower case without hyphens
	 * unless for "Horton-Strahler"
	 */

	/** Flag for {@value #TERMINAL_LENGTH} analysis. */
	public static final String TERMINAL_LENGTH = "Length of terminal branches (sum)";

	/** Flag for {@value #INNER_LENGTH} analysis. */
	public static final String INNER_LENGTH = "Length of inner branches (sum)";

	/** Flag for {@value #PRIMARY_LENGTH} analysis. */
	public static final String PRIMARY_LENGTH = "Length of primary branches (sum)";

	/** Flag for {@value #AVG_BRANCH_LENGTH} analysis. */
	public static final String AVG_BRANCH_LENGTH = "Average branch length";

	/** Flag specifying {@link Tree#assignValue(double) Tree value} statistics */
	public static final String ASSIGNED_VALUE = "Assigned value";

	/** Flag specifying {@value #HIGHEST_PATH_ORDER} statistics */
	public static final String HIGHEST_PATH_ORDER = "Highest path order";

	/** Flag for {@value #AVG_CONTRACTION} statistics */
	public static final String AVG_CONTRACTION = "Average contraction";

	/** Flag for {@value #AVG_FRAGMENTATION} statistics */
	public static final String AVG_FRAGMENTATION = "Average fragmentation";

	/** Flag specifying {@value #AVG_REMOTE_ANGLE} statistics */
	public static final String AVG_REMOTE_ANGLE = "Average remote bif. angle";

	/** Flag specifying {@value #AVG_PARTITION_ASYMMETRY} statistics */
	public static final String AVG_PARTITION_ASYMMETRY = "Average partition asymmetry";

	/** Flag specifying {@value #AVG_FRACTAL_DIMENSION} statistics */
	public static final String AVG_FRACTAL_DIMENSION = "Average fractal dimension";

	/**
	 * Flag for {@value #MEAN_RADIUS} statistics
	 * 
	 * @deprecated use #PATH_MEAN_RADIUS instead
	 */
	@Deprecated
	public static final String MEAN_RADIUS = "Mean radius";

	private final List<Tree> groupOfTrees;
	private Collection<DirectedWeightedGraph> groupOfGraphs;

	/**
	 * Instantiates a new instance from a collection of Trees.
	 *
	 * @param group the collection of Trees to be analyzed
	 */
	public MultiTreeStatistics(final Collection<Tree> group) {
		super(new Tree());
		this.groupOfTrees = new ArrayList<>(group);
	}

	/**
	 * Instantiates a new instance from a collection of Trees.
	 *
	 * @param group    the collection of Trees to be analyzed
	 * @param swcTypes SWC type(s) a string with at least 2 characters describing
	 *                 the SWC type allowed in the subtree (e.g., 'axn', or
	 *                 'dendrite')
	 * @throws NoSuchElementException {@code swcTypes} are not applicable to
	 *                                {@code group}
	 */
	public MultiTreeStatistics(final Collection<Tree> group, final String... swcTypes) throws NoSuchElementException {
		super(new Tree());
		this.groupOfTrees = new ArrayList<>();
		group.forEach(inputTree -> {
			final Tree filteredTree = inputTree.subTree(swcTypes);
			if (filteredTree != null && filteredTree.size() > 0)
				groupOfTrees.add(filteredTree);
		});
		if (groupOfTrees.isEmpty())
			throw new NoSuchElementException("No match for the specified type(s) in group");
	}

	/**
	 * Gets the collection of Trees being analyzed.
	 *
	 * @return the Tree group
	 */
	public Collection<Tree> getGroup() {
		return groupOfTrees;
	}

	/**
	 * Sets an identifying label for the group of Trees being analyzed.
	 *
	 * @param groupLabel the identifying string for the group.
	 */
	public void setLabel(final String groupLabel) {
		tree.setLabel(groupLabel);
	}

	@Override
	public SummaryStatistics getSummaryStats(final String metric) {
		final SummaryStatistics sStats = new SummaryStatistics();
		assembleStats(new StatisticsInstance(sStats), getNormalizedMeasurement(metric));
		return sStats;
	}

	@Override
	public DescriptiveStatistics getDescriptiveStats(final String metric) {
		final DescriptiveStatistics dStats = new DescriptiveStatistics();
		final String normMeasurement = getNormalizedMeasurement(metric);
		if (!lastDstatsCanBeRecycled(normMeasurement)) {
			assembleStats(new StatisticsInstance(dStats), normMeasurement);
			lastDstats = new LastDstats(normMeasurement, dStats);
		}
		return lastDstats.dStats;
	}

	@Override
	protected void assembleStats(final StatisticsInstance stat, final String measurement)
			throws UnknownMetricException {
		final String normMeasurement = getNormalizedMeasurement(measurement);
		for (final Tree t : groupOfTrees) {
			new TreeStatistics(t).assembleStats(stat, normMeasurement);
		}
	}

	private void assignGroupToSuperTree() {
		if (super.tree.isEmpty()) {
			for (final Tree tree : groupOfTrees)
				super.tree.list().addAll(tree.list());
		}
	}

	private void populateGroupOfGraphs() {
		if (groupOfGraphs == null) {
			groupOfGraphs = new ArrayList<>();
			groupOfTrees.forEach(t -> groupOfGraphs.add(t.getGraph()));
		}
	}

	@Override
	protected String getUnit() {
		final String u1 = (String) groupOfTrees.get(0).getProperties().getOrDefault(TreeProperties.KEY_SPATIAL_UNIT, "");
		for (int i = 1; i < groupOfTrees.size(); i++) {
			final String u2 = (String) groupOfTrees.get(i).getProperties().getOrDefault(TreeProperties.KEY_SPATIAL_UNIT, "");
			if (!u2.equals(u1))
				return "";
		}
		return u1;
	}

	@Override
	public void restrictToSWCType(final int... types) {
		throw new IllegalArgumentException("Operation not supported. Only filtering in constructor is supported");
	}

	@Override
	public void resetRestrictions() {
		throw new IllegalArgumentException("Operation not supported. Only filtering in constructor is supported");
	}

	@Override
	public StrahlerAnalyzer getStrahlerAnalyzer() throws IllegalArgumentException {
		throw new IllegalArgumentException("Operation currently not supported in MultiTreeStatistics");
	}

	@Override
	public ShollAnalyzer getShollAnalyzer() {
		throw new IllegalArgumentException("Operation currently not supported in MultiTreeStatistics");
	}

	@Override
	public Set<BrainAnnotation> getAnnotations() {
		assignGroupToSuperTree();
		return super.getAnnotations();
	}

	@Override
	public Set<BrainAnnotation> getAnnotations(final int level) {
		assignGroupToSuperTree();
		return super.getAnnotations(level);
	}

	@Override
	public double getCableLength(final BrainAnnotation compartment) {
		assignGroupToSuperTree();
		return getCableLength(compartment, true);
	}


	@Override
	public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere) {
		return getAnnotatedLength(level, hemisphere, false);
	}

	@Override
	public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere, final boolean norm) {
		final char lrflag = BrainAnnotation.getHemisphereFlag(hemisphere);
		populateGroupOfGraphs();
		final List<Map<BrainAnnotation, Double>> mapList = new ArrayList<>();
		groupOfGraphs.forEach(g -> mapList.add(getAnnotatedLength(g, level, lrflag, norm)));
		mapList.forEach(e -> e.keySet().remove(null)); // remove all null keys (untagged nodes)
		return mapList.stream().flatMap(m -> m.entrySet().stream())
				.collect(groupingBy(Map.Entry::getKey, summingDouble(Map.Entry::getValue)));
	}

	@Override
	public Map<BrainAnnotation, Double> getAnnotatedLength(final int level) {
		populateGroupOfGraphs();
		return getAnnotatedLength(level, "both");
	}

	@Override
	public Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final int level) {
		populateGroupOfGraphs();
		final List<Map<BrainAnnotation, double[]>> mapList = new ArrayList<>();
		groupOfGraphs.forEach(g -> {
			mapList.add(getAnnotatedLengthsByHemisphere(g, level, false));
		});
		return mapList.stream().flatMap(m -> m.entrySet().stream())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
						(v1, v2) -> new double[] { v1[0] + v1[0], v1[1] + v1[1] }));
	}

	@Override
	public SNTChart getHistogram(final String metric) {
		final String normMeasurement = getNormalizedMeasurement(metric);
		final HistogramDatasetPlusMulti datasetPlus = new HistogramDatasetPlusMulti(normMeasurement);
		try {
			return getHistogram(normMeasurement, datasetPlus);
		} catch (final IllegalArgumentException ex) {
			throw new IllegalArgumentException("TreeStatistics metric is likely not supported by MultiTreeStatistics",
					ex);
		}
	}

	@Override
	public SNTChart getPolarHistogram(final String metric) {
		final String normMeasurement = getNormalizedMeasurement(metric);
		final HistogramDatasetPlusMulti datasetPlus = new HistogramDatasetPlusMulti(normMeasurement);
		try {
			final JFreeChart chart = AnalysisUtils.createPolarHistogram(normMeasurement, "", lastDstats.dStats,
					datasetPlus);
			return new SNTChart("Polar Hist. " + tree.getLabel(), chart);
		} catch (final IllegalArgumentException ex) {
			throw new IllegalArgumentException("TreeStatistics metric is likely not supported by MultiTreeStatistics",
					ex);
		}
	}

	@Override
	public Set<PointInImage> getTips() {
		assignGroupToSuperTree();
		return super.getTips();
	}

	@Override
	public Set<PointInImage> getBranchPoints() {
		assignGroupToSuperTree();
		return super.getBranchPoints();
	}

	@Override
	public List<Path> getBranches() throws IllegalArgumentException {
		final List<Path> allBranches = new ArrayList<>();
		groupOfTrees.forEach(t -> {
			final List<Path> list = new StrahlerAnalyzer(t).getBranches().values().stream().flatMap(List::stream)
					.collect(Collectors.toList());
			allBranches.addAll(list);
		});
		return allBranches;
	}

	@Override
	public List<Path> getPrimaryBranches() {
		if (primaryBranches == null) {
			primaryBranches = new ArrayList<>();
			groupOfTrees.forEach(t -> {
				primaryBranches.addAll(new StrahlerAnalyzer(t).getRootAssociatedBranches());
			});
		}
		return primaryBranches;
	}

	@Override
	public List<Path> getInnerBranches() {
		if (innerBranches == null) {
			innerBranches = new ArrayList<>();
			groupOfTrees.forEach(t -> {
				final StrahlerAnalyzer sa = new StrahlerAnalyzer(t);
				innerBranches.addAll(sa.getBranches(sa.getHighestBranchOrder()));
			});
		}
		return innerBranches;
	}

	@Override
	public List<Path> getTerminalBranches() {
		if (terminalBranches == null) {
			terminalBranches = new ArrayList<>();
			groupOfTrees.forEach(t -> {
				final StrahlerAnalyzer sa = new StrahlerAnalyzer(t);
				terminalBranches.addAll(sa.getBranches(1));
			});
		}
		return terminalBranches;
	}

	@Override
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final boolean normalize) {
		return getFlowPlot(feature, annotations, "mean", Double.MIN_VALUE, normalize);
	}

	@Override
	public SNTChart getFlowPlot(final String feature, final int depth) {
		return getFlowPlot(feature, depth, Double.MIN_VALUE, true);
	}

	@Override
	public SNTChart getFlowPlot(final String feature, final int depth, final double cutoff, final boolean normalize) {
		return getFlowPlot(feature, getAnnotations(depth), "mean", cutoff, normalize);
	}

	@Override
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations) {
		return getFlowPlot(feature, annotations, "mean", Double.MIN_VALUE, false);
	}

	@Override
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final String statistic, final double cutoff, final boolean normalize) {
		final GroupedTreeStatistics gts = new GroupedTreeStatistics();
		gts.addGroup(groupOfTrees, (null == tree.getLabel()) ? "" : tree.getLabel());
		final SNTChart chart = gts.getFlowPlot(feature, annotations, statistic, cutoff, normalize);
		chart.setTitle("Flow Plot [Group of Cells]");
		return chart;
	}

	class HistogramDatasetPlusMulti extends HDPlus {
		HistogramDatasetPlusMulti(final String measurement) {
			super(measurement, false);
			getDescriptiveStats(measurement);
			for (final double v : lastDstats.dStats.getValues()) {
				values.add(v);
			}
		}
	}

	public static List<String> getMetrics() {
		final String[] ALL_FLAGS = { ASSIGNED_VALUE, AVG_BRANCH_LENGTH, AVG_CONTRACTION, AVG_FRACTAL_DIMENSION,
				AVG_FRAGMENTATION, AVG_PARTITION_ASYMMETRY, AVG_REMOTE_ANGLE, HIGHEST_PATH_ORDER, INNER_LENGTH,
				MEAN_RADIUS, PRIMARY_LENGTH, TERMINAL_LENGTH };
		return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
	}


	/* IDE debug method */
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		SNTUtils.setDebugMode(true);
		final MultiTreeStatistics treeStats = new MultiTreeStatistics(sntService.demoTrees());
		treeStats.setLabel("Demo Dendrites");
		treeStats.getHistogram("x coordinates").show();
		treeStats.getPolarHistogram("x coordinates").show();
		treeStats.getFlowPlot("Cable length", treeStats.getAnnotatedLength(9).keySet()).show();
	}
}
