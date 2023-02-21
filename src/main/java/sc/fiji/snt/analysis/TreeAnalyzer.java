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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.scijava.table.DefaultGenericTable;

import net.imagej.ImageJ;

import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;
import org.scijava.app.StatusService;
import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

/**
 * Class for 'entry-level' analysis of a {@link Tree}s. For more sophisticated
 * analysis have a look at {@link TreeStatistics}
 *
 * @author Tiago Ferreira
 */
public class TreeAnalyzer extends ContextCommand {

	@Parameter
	protected StatusService statusService;

	@Parameter
	protected DisplayService displayService;


	protected Tree tree;
	private Tree unfilteredTree;

	protected List<Path> primaryBranches;
	protected List<Path> innerBranches;
	protected List<Path> terminalBranches;
	private Set<PointInImage> joints;
	protected Set<PointInImage> tips;

	protected DefaultGenericTable table;
	private String tableTitle;
	private StrahlerAnalyzer sAnalyzer;
	private ShollAnalyzer shllAnalyzer;

	private int fittedPathsCounter = 0;
	private int unfilteredPathsFittedPathsCounter = 0;

	/**
	 * Instantiates a new Tree analyzer from a collection of paths.
	 *
	 * @param paths Collection of Paths to be analyzed. Note that null Paths are
	 *          discarded. Also, when a Path has been fitted and
	 *          {@link Path#getUseFitted()} is true, its fitted 'flavor' is used.
	 * @param label the label describing the path collection
	 * @see #getParsedTree()
	 */
	public TreeAnalyzer(final Collection<Path> paths, String label) {
		tree = new Tree(paths);
		tree.setLabel(label);
		for (final Path p : tree.list()) {
			// If fitted flavor of path exists use it instead
			if (p.getUseFitted()) {
				fittedPathsCounter++;
			}
		}
		unfilteredPathsFittedPathsCounter = fittedPathsCounter;
	}

	/**
	 * Instantiates a new Tree analyzer.
	 *
	 * @param tree the Tree to be analyzed.
	 */
	public TreeAnalyzer(final Tree tree) {
		this.tree = tree;
		unfilteredPathsFittedPathsCounter = 0;
		fittedPathsCounter = 0;
	}

	/**
	 * Restricts analysis to Paths sharing the specified SWC flag(s).
	 *
	 * @param types the allowed SWC flags (e.g., {@link Path#SWC_AXON}, etc.)
	 */
	public void restrictToSWCType(final int... types) {
		initializeSnapshotTree();
		tree = tree.subTree(types);
	}

	/**
	 * Ignores Paths sharing the specified SWC flag(s).
	 *
	 * @param types the SWC flags to be ignored (e.g., {@link Path#SWC_AXON},
	 *          etc.)
	 */
	public void ignoreSWCType(final int... types) {
		initializeSnapshotTree();
		final ArrayList<Integer> allowedTypes = Path.getSWCtypes();
		for (final int type : types) {
			allowedTypes.remove(Integer.valueOf(type));
		}
		tree = tree.subTree(allowedTypes.stream().mapToInt(i -> i).toArray());
	}

	/**
	 * Restricts analysis to Paths sharing the specified Path {@link Path#getOrder()
	 * order}(s).
	 *
	 * @param orders the allowed Path orders
	 */
	public void restrictToOrder(final int... orders) {
		initializeSnapshotTree();
		final Iterator<Path> it = tree.list().iterator();
		while (it.hasNext()) {
			final Path p = it.next();
			final boolean valid = Arrays.stream(orders).anyMatch(t -> t == p
				.getOrder());
			if (!valid) {
				updateFittedPathsCounter(p);
				it.remove();
			}
		}
	}

	/**
	 * Restricts analysis to paths having the specified number of nodes.
	 *
	 * @param minSize the smallest number of nodes a path must have in order to be
	 *          analyzed. Set it to -1 to disable minSize filtering
	 * @param maxSize the largest number of nodes a path must have in order to be
	 *          analyzed. Set it to -1 to disable maxSize filtering
	 */
	public void restrictToSize(final int minSize, final int maxSize) {
		initializeSnapshotTree();
		final Iterator<Path> it = tree.list().iterator();
		while (it.hasNext()) {
			final Path p = it.next();
			final int size = p.size();
			if ((minSize > 0 && size < minSize) || (maxSize > 0 && size > maxSize)) {
				updateFittedPathsCounter(p);
				it.remove();
			}
		}
	}

	/**
	 * Restricts analysis to paths sharing the specified length range.
	 *
	 * @param lowerBound the smallest length a path must have in order to be
	 *          analyzed. Set it to Double.NaN to disable lowerBound filtering
	 * @param upperBound the largest length a path must have in order to be
	 *          analyzed. Set it to Double.NaN to disable upperBound filtering
	 */
	public void restrictToLength(final double lowerBound,
		final double upperBound)
	{
		initializeSnapshotTree();
		final Iterator<Path> it = tree.list().iterator();
		while (it.hasNext()) {
			final Path p = it.next();
			final double length = p.getLength();
			if (length < lowerBound || length > upperBound) {
				updateFittedPathsCounter(p);
				it.remove();
			}
		}
	}

	/**
	 * Restricts analysis to Paths containing the specified string in their name.
	 *
	 * @param pattern the string to search for
	 */
	public void restrictToNamePattern(final String pattern) {
		initializeSnapshotTree();
		final Iterator<Path> it = tree.list().iterator();
		while (it.hasNext()) {
			final Path p = it.next();
			if (!p.getName().contains(pattern)) {
				updateFittedPathsCounter(p);
				it.remove();
			}
		}
	}

	private void initializeSnapshotTree() {
		if (unfilteredTree == null) {
			unfilteredTree = new Tree(tree.list());
			unfilteredTree.setLabel(tree.getLabel());
		}
		sAnalyzer = null; // reset Strahler analyzer
	}

	/**
	 * Removes any filtering restrictions that may have been set. Once called,
	 * subsequent analysis will use all paths initially parsed by the constructor.
	 * Does nothing if no paths are currently being excluded from the analysis.
	 */
	public void resetRestrictions() {
		if (unfilteredTree == null) return; // no filtering has occurred
		tree.replaceAll(unfilteredTree.list());
		joints = null;
		primaryBranches = null;
		innerBranches = null;
		terminalBranches = null;
		tips = null;
		sAnalyzer = null;
		shllAnalyzer = null;
		fittedPathsCounter = unfilteredPathsFittedPathsCounter;
	}

	private void updateFittedPathsCounter(final Path filteredPath) {
		if (fittedPathsCounter > 0 && filteredPath.isFittedVersionOfAnotherPath())
			fittedPathsCounter--;
	}

	/**
	 * Returns the set of parsed Paths.
	 *
	 * @return the set of paths currently being considered for analysis.
	 * @see #resetRestrictions()
	 */
	public Tree getParsedTree() {
		return tree;
	}

	/**
	 * Outputs a summary of the current analysis to the Analyzer table using the
	 * default Tree label.
	 *
	 * @param groupByType if true measurements are grouped by SWC-type flag
	 * @see #run()
	 * @see #setTable(DefaultGenericTable)
	 */
	public void summarize(final boolean groupByType) {
		summarize(tree.getLabel(), groupByType);
	}

	/**
	 * Outputs a summary of the current analysis to the Analyzer table.
	 *
	 * @param rowHeader the String to be used as label for the summary
	 * @param groupByType if true measurements are grouped by SWC-type flag
	 * @see #run()
	 * @see #setTable(DefaultGenericTable)
	 */
	public void summarize(final String rowHeader, final boolean groupByType) {
		measure(rowHeader, TreeStatistics.getMetrics("common"), true);
	}

	protected int getNextRow(final String rowHeader) {
		table.appendRow((rowHeader==null)?"":rowHeader);
		return table.getRowCount() - 1;
	}

	/**
	 * Gets a list of supported metrics. Note that this list will only include
	 * commonly used metrics. For a complete list of supported metrics see
	 * {@link #getAllMetrics()}
	 * 
	 * @return the list of available metrics
	 * @see TreeStatistics#getMetrics(String)
	 */
	@SuppressWarnings("deprecation")
	public static List<String> getMetrics() {
		final ArrayList<String> metrics = new ArrayList<>();
		metrics.add(MultiTreeStatistics.ASSIGNED_VALUE);
		metrics.add(MultiTreeStatistics.AVG_BRANCH_LENGTH);
		metrics.add(MultiTreeStatistics.AVG_CONTRACTION);
		metrics.add(MultiTreeStatistics.AVG_FRACTAL_DIMENSION);
		metrics.add(MultiTreeStatistics.AVG_FRAGMENTATION);
		metrics.add(MultiTreeStatistics.AVG_PARTITION_ASYMMETRY);
		metrics.add(MultiTreeStatistics.AVG_REMOTE_ANGLE);
		metrics.add(MultiTreeStatistics.HIGHEST_PATH_ORDER);
		metrics.add(TreeStatistics.AVG_SPINE_DENSITY);
		metrics.add(TreeStatistics.DEPTH);
		metrics.add(TreeStatistics.HEIGHT);
		metrics.add(TreeStatistics.INNER_LENGTH);
		metrics.add(TreeStatistics.LENGTH);
		metrics.add(TreeStatistics.N_BRANCH_POINTS);
		metrics.add(TreeStatistics.N_BRANCHES);
		metrics.add(TreeStatistics.N_FITTED_PATHS);
		metrics.add(TreeStatistics.N_INNER_BRANCHES);
		metrics.add(TreeStatistics.N_NODES);
		metrics.add(TreeStatistics.N_PATHS);
		metrics.add(TreeStatistics.N_PRIMARY_BRANCHES);
		metrics.add(TreeStatistics.N_SPINES);
		metrics.add(TreeStatistics.N_TERMINAL_BRANCHES);
		metrics.add(TreeStatistics.N_TIPS);
		metrics.add(TreeStatistics.PATH_MEAN_RADIUS);
		metrics.add(TreeStatistics.PRIMARY_LENGTH);
		metrics.add(TreeStatistics.STRAHLER_NUMBER);
		metrics.add(TreeStatistics.STRAHLER_RATIO);
		metrics.add(TreeStatistics.TERMINAL_LENGTH);
		metrics.add(TreeStatistics.WIDTH);
		metrics.add("Sholl: "+ ShollAnalyzer.SUM);
		metrics.add("Sholl: "+ ShollAnalyzer.MAX);
		metrics.add("Sholl: "+ ShollAnalyzer.N_MAX);
		metrics.add("Sholl: "+ ShollAnalyzer.N_SECONDARY_MAX);
		metrics.add("Sholl: "+ ShollAnalyzer.POLY_FIT_DEGREE);
		return metrics;
	}

	/**
	 * Computes the specified metric.
	 *
	 * @param metric the metric to be computed (case insensitive). While it is
	 *               expected to be an element of {@link #getMetrics()}, it can be
	 *               specified in a "loose" manner: If {@code metric} is not
	 *               initially recognized, an heuristic will match it to the closest
	 *               entry in the list of possible metrics. E.g., "# bps", "n
	 *               junctions", will be both mapped to
	 *               {@link MultiTreeStatistics#N_BRANCH_POINTS}. Details on the
	 *               matching are printed to the Console when in debug mode.
	 * @return the computed value
	 * @throws IllegalArgumentException if metric is not recognized
	 * @see #getMetrics()
	 */
	public Number getMetric(final String metric) throws IllegalArgumentException {
		return getMetricInternal(TreeStatistics.getNormalizedMeasurement(metric));
	}

	protected Number getMetricInternal(final String metric) {
		try {
			return getMetricWithoutChecks(metric);
		} catch (final Exception ignored) {
			SNTUtils.log("Error: " + ignored.getMessage());
			return Double.NaN;
		}
	}

	@SuppressWarnings("deprecation")
	protected Number getMetricWithoutChecks(final String metric) throws UnknownMetricException {
		switch (metric) {
		case MultiTreeStatistics.ASSIGNED_VALUE:
			return tree.getAssignedValue();
		case MultiTreeStatistics.AVG_BRANCH_LENGTH:
			return getAvgBranchLength();
		case MultiTreeStatistics.AVG_CONTRACTION:
			return getAvgContraction();
		case MultiTreeStatistics.AVG_FRAGMENTATION:
			return getAvgFragmentation();
		case MultiTreeStatistics.AVG_REMOTE_ANGLE:
			return getAvgRemoteBifAngle();
		case MultiTreeStatistics.AVG_PARTITION_ASYMMETRY:
			return getAvgPartitionAsymmetry();
		case MultiTreeStatistics.AVG_FRACTAL_DIMENSION:
			return getAvgFractalDimension();
		case MultiTreeStatistics.PRIMARY_LENGTH:
			return getPrimaryLength();
		case MultiTreeStatistics.TERMINAL_LENGTH:
			return getTerminalLength();
		case MultiTreeStatistics.INNER_LENGTH:
			return getInnerLength();
		case TreeStatistics.AVG_SPINE_DENSITY:
		case TreeStatistics.PATH_SPINE_DENSITY:
			return getSpineOrVaricosityDensity();
		case TreeStatistics.DEPTH:
			return getDepth();
		case TreeStatistics.HEIGHT:
			return getHeight();
		case MultiTreeStatistics.HIGHEST_PATH_ORDER:
			return getHighestPathOrder();
		case TreeStatistics.LENGTH:
			return getCableLength();
		case TreeStatistics.PATH_MEAN_RADIUS:
		case MultiTreeStatistics.MEAN_RADIUS:
			final TreeStatistics treeStats = new TreeStatistics(tree);
			return treeStats.getSummaryStats(TreeStatistics.PATH_MEAN_RADIUS).getMean();
		case TreeStatistics.N_BRANCH_POINTS:
			return getBranchPoints().size();
		case TreeStatistics.N_BRANCHES:
			return getNBranches();
		case TreeStatistics.N_FITTED_PATHS:
			return getNFittedPaths();
		case TreeStatistics.N_NODES:
			return getNNodes();
		case TreeStatistics.N_PATHS:
			return getNPaths();
		case TreeStatistics.N_PRIMARY_BRANCHES:
			return getPrimaryBranches().size();
		case TreeStatistics.N_INNER_BRANCHES:
			return getInnerBranches().size();
		case TreeStatistics.N_TERMINAL_BRANCHES:
			return getTerminalBranches().size();
		case TreeStatistics.N_TIPS:
			return getTips().size();
		case TreeStatistics.N_SPINES:
			return getNoSpinesOrVaricosities();
		case TreeStatistics.PRIMARY_LENGTH:
			return getPrimaryLength();
		case TreeStatistics.INNER_LENGTH:
			return getInnerLength();
		case TreeStatistics.STRAHLER_NUMBER:
			return getStrahlerNumber();
		case TreeStatistics.STRAHLER_RATIO:
			return getStrahlerBifurcationRatio();
		case TreeStatistics.TERMINAL_LENGTH:
			return getTerminalLength();
		case TreeStatistics.WIDTH:
			return getWidth();
		default:
			if (metric.startsWith("Sholl: ")) {
				return getShollMetric(metric);
			}
			throw new UnknownMetricException("Unrecognizable measurement \"" + metric + "\". "
					+ "Maybe you meant one of the following?: \"" + String.join(", ", getMetrics() + "\""));
		}
	}

	protected Number getShollMetric(final String metric) {
		final String fMetric = metric.substring(metric.indexOf("Sholl: ") + 6).trim();
		return getShollAnalyzer().getSingleValueMetrics().getOrDefault(fMetric, Double.NaN);
	}

	/**
	 * Measures this Tree, outputting the result to this Analyzer table using
	 * default row labels. If a Context has been specified, the table is updated.
	 * Otherwise, table contents are printed to Console.
	 *
	 * @param metrics     the list of metrics to be computed. When null or an empty
	 *                    collection is specified, {@link #getMetrics()} is used.
	 * @param groupByType if false, metrics are computed to all branches in the
	 *                    Tree. If true, measurements will be split by SWC type
	 *                    annotations (axon, dendrite, etc.)
	 */
	public void measure(final Collection<String> metrics, final boolean groupByType) {
		measure(tree.getLabel(), metrics, groupByType);
	}

	/**
	 * Measures this Tree, outputting the result to this Analyzer table. If a
	 * Context has been specified, the table is updated. Otherwise, table contents
	 * are printed to Console.
	 *
	 * @param rowHeader   the row header label
	 * @param metrics     the list of metrics to be computed. When null or an empty
	 *                    collection is specified, {@link #getMetrics()} is used.
	 * @param groupByType if false, metrics are computed to all branches in the
	 *                    Tree. If true, measurements will be split by SWC type
	 *                    annotations (axon, dendrite, etc.)
	 */
	public void measure(final String rowHeader, final Collection<String> metrics, final boolean groupByType) {
		if (table == null) table = new SNTTable();
		final Collection<String> measuringMetrics = (metrics == null || metrics.isEmpty()) ? getMetrics() : metrics;
		if (groupByType) {
			for (final int type : tree.getSWCTypes(false)) {
				restrictToSWCType(type);
				final int row = getNextRow(rowHeader);
				table.set(getCol("SWC Type(s)"), row, Path.getSWCtypeName(type, true));
				measuringMetrics.forEach(metric -> table.set(getCol(metric), row, getMetricInternal(metric)));
				resetRestrictions();
			}
		} else {
			final int row = getNextRow(rowHeader);
			table.set(getCol("SWC Type(s)"), row, getSWCTypesAsString());
			measuringMetrics.forEach(metric -> table.set(getCol(metric), row, getMetricInternal(metric)));
		}
		if (getContext() != null) updateAndDisplayTable();
	}

	protected String getSWCTypesAsString() {
		final StringBuilder sb = new StringBuilder();
		final Set<Integer> types = tree.getSWCTypes(true);
		for (int type: types) {
			sb.append(Path.getSWCtypeName(type, true)).append(" ");
		}
		return sb.toString().trim();
	}

	/**
	 * Sets the Analyzer table.
	 *
	 * @param table the table to be used by the analyzer
	 * @see #summarize(boolean)
	 */
	public void setTable(final DefaultGenericTable table) {
		this.table = table;
	}

	/**
	 * Sets the table.
	 *
	 * @param table the table to be used by the analyzer
	 * @param title the title of the table display window
	 */
	public void setTable(final DefaultGenericTable table, final String title) {
		this.table = table;
		this.tableTitle = title;
	}

	/**
	 * Gets the table currently being used by the Analyzer
	 *
	 * @return the table
	 */
	public DefaultGenericTable getTable() {
		return table;
	}

	/**
	 * Generates detailed summaries in which measurements are grouped by SWC-type
	 * flags
	 *
	 * @see #summarize(String, boolean)
	 */
	@Override
	public void run() {
		if (tree.list() == null || tree.list().isEmpty()) {
			cancel("No Paths to Measure");
			return;
		}
		statusService.showStatus("Measuring Paths...");
		summarize(true);
		statusService.clearStatus();
	}

	/**
	 * Updates and displays the Analyzer table.
	 */
	public void updateAndDisplayTable() {
		if (getContext() == null) {
			System.out.println(SNTTable.toString(table, 0, table.getRowCount() - 1));
			return;
		}
		final String displayName = (tableTitle == null) ? "SNT Measurements"
			: tableTitle;
		final Display<?> display = displayService.getDisplay(displayName);
		if (display != null) {
			display.update();
		}
		else {
			displayService.createDisplay(displayName, table);
		}
	}

	public String getUnit(final String metric) {
		final String m = metric.toLowerCase();
		if (TreeStatistics.WIDTH.equals(metric) || TreeStatistics.HEIGHT.equals(metric)
				|| TreeStatistics.DEPTH.equals(metric) || m.contains("length") || m.contains("radius")
				|| m.contains("distance") || TreeStatistics.CONVEX_HULL_ELONGATION.equals(metric)) {
			return (String) tree.getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT, "? units");
		} else if (m.contains("volume")) {
			return tree.getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT, "? units") + "^3";
		} else if (m.contains("surface area")) {
			return tree.getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT, "? units") + "^2";
		} else if (TreeStatistics.CONVEX_HULL_SIZE.equals(metric)) {
			return tree.getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT, "? units") + ((tree.is3D()) ? "^3" : "^2");
		} else if (TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE.equals(metric)) {
			return tree.getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT, "? units") + ((tree.is3D()) ? "^2" : "");
		}
		return "";
	}

	protected int getCol(final String header) {
		final String unit = getUnit(header);
		final String normHeader = (unit.length() > 1) ? header + " (" + unit + ")" : header;
		int idx = table.getColumnIndex(normHeader);
		if (idx == -1) {
			table.appendColumn(normHeader);
			idx = table.getColumnCount() - 1;
		}
		return idx;
	}

	protected int getSinglePointPaths() {
		return (int) tree.list().stream().filter(p -> p.size() == 1).count();
	}

	/**
	 * Gets the no. of paths parsed by the Analyzer.
	 *
	 * @return the number of paths
	 */
	public int getNPaths() {
		return tree.list().size();
	}

	protected int getNFittedPaths() {
		return fittedPathsCounter;
	}

	public double getWidth() {
		return tree.getBoundingBox(true).width();
	}

	public double getHeight() {
		return tree.getBoundingBox(true).height();
	}

	public double getDepth() {
		return tree.getBoundingBox(true).depth();
	}

	/**
	 * Retrieves all the Paths in the analyzed Tree tagged as primary.
	 *
	 * @return the list of primary paths.
	 * @see #getPrimaryBranches()
	 */
	public List<Path> getPrimaryPaths() {
		final List<Path> primaryPaths = new ArrayList<>();
		for (final Path p : tree.list()) {
			if (p.isPrimary()) primaryPaths.add(p);
		}
		return primaryPaths;
	}

	/**
	 * Retrieves the branches of highest
	 * {@link StrahlerAnalyzer#getHighestBranchOrder() Strahler order} in the Tree.
	 * This typically correspond to the most 'internal' branches of the Tree in
	 * direct sequence from the root.
	 * 
	 * @return the list containing the "inner" branches. Note that these branches
	 *         (Path segments) will not carry any connectivity information.
	 * @see #getPrimaryPaths()
	 * @see StrahlerAnalyzer#getBranches()
	 * @see StrahlerAnalyzer#getHighestBranchOrder()
	 */
	public List<Path> getInnerBranches() {
		getStrahlerAnalyzer();
		innerBranches = sAnalyzer.getBranches(sAnalyzer.getHighestBranchOrder());
		return innerBranches;
	}

	/**
	 * Retrieves the primary branches of the analyzed Tree. Primary branches (or
	 * root-associated) have origin in the Tree's root, extending to the closest
	 * branch/end-point. Note that a primary branch can also be terminal.
	 * 
	 * @return the primary branches. Note that these branches (Path segments) will
	 *         not carry any connectivity information.
	 * @see #getPrimaryPaths()
	 * @see StrahlerAnalyzer#getRootAssociatedBranches()
	 */
	public List<Path> getPrimaryBranches() {
		getStrahlerAnalyzer();
		primaryBranches = sAnalyzer.getRootAssociatedBranches();
		return primaryBranches;
	}

	/**
	 * Retrieves the terminal branches of the analyzed Tree. A terminal branch
	 * corresponds to the section of a terminal Path between its last branch-point
	 * and its terminal point (tip). A terminal branch can also be primary.
	 *
	 * @return the terminal branches. Note that as per
	 *         {@link Path#getSection(int, int)}, these branches will not carry any
	 *         connectivity information.
	 * @see #getPrimaryBranches
	 * @see #restrictToOrder(int...)
	 */
	public List<Path> getTerminalBranches() {
		getStrahlerAnalyzer();
		terminalBranches = sAnalyzer.getBranches(1);
		return terminalBranches;
	}

	/**
	 * Gets the position of all the tips in the analyzed tree.
	 *
	 * @return the set of terminal points
	 */
	public Set<PointInImage> getTips() {

		// retrieve all start/end points
		tips = new HashSet<>();
		for (final Path p : tree.list()) {
			tips.add(p.getNode(p.size() - 1));
		}
		// now remove any joint-associated point
		if (joints == null) getBranchPoints();
		tips.removeAll(joints);
		return tips;

	}

	/**
	 * Gets the position of all the tips in the analyzed tree associated with the
	 * specified annotation.
	 *
	 * @param annot the BrainAnnotation to be queried. Null not allowed.
	 * @return the branch points positions, or an empty set if no tips were
	 *         retrieved.
	 */
	public Set<PointInImage> getTips(final BrainAnnotation annot) {
		if (tips == null) getTips();
		final HashSet<PointInImage> fTips = new HashSet<>();
		for (final PointInImage tip : tips) {
			final BrainAnnotation annotation = tip.getAnnotation();
			if (annotation != null && isSameOrParentAnnotation(annot, annotation))
				fTips.add(tip);
		}
		return fTips;
	}

	/**
	 * Gets the number of end points in the analyzed tree associated with the
	 * specified annotation.
	 *
	 * @param annot the BrainAnnotation to be queried.
	 * @return the number of end points
	 */
	public int getNtips(final BrainAnnotation annot) {
		return getTips(annot).size();
	}

	/**
	 * Gets the percentage of end points in the analyzed tree associated with the
	 * specified annotation
	 *
	 * @param annot the BrainAnnotation to be queried.
	 * @return the ratio between the no. of branch points associated with
	 *         {@code annot} and the total number of end points in the tree.
	 */
	public double getNtipsNorm(final BrainAnnotation annot) {
		return (double) (getNtips(annot)) / (double)(tips.size());
	}

	/**
	 * Gets the position of all the branch points in the analyzed tree.
	 *
	 * @return the branch points positions
	 */
	public Set<PointInImage> getBranchPoints() {
		joints = new HashSet<>();
		for (final Path p : tree.list()) {
			joints.addAll(p.getJunctionNodes());
		}
		return joints;
	}

	/**
	 * Gets the position of all the branch points in the analyzed tree associated
	 * with the specified annotation.
	 *
	 * @param annot the BrainAnnotation to be queried.
	 * @return the branch points positions, or an empty set if no branch points
	 *         were retrieved.
	 */
	public Set<PointInImage> getBranchPoints(final BrainAnnotation annot) {
		if (joints == null) getBranchPoints();
		final HashSet<PointInImage>fJoints = new HashSet<>();
		for (final PointInImage joint: joints) {
			final BrainAnnotation annotation = joint.getAnnotation();
			if (annotation != null && isSameOrParentAnnotation(annot, annotation))
				fJoints.add(joint);
		}
		return fJoints;
	}

	/**
	 * Gets the number of branch points in the analyzed tree associated with the
	 * specified annotation.
	 *
	 * @param annot the BrainAnnotation to be queried.
	 * @return the number of branch points
	 */
	public int getNbranchPoints(final BrainAnnotation annot) {
		return getBranchPoints(annot).size();
	}

	/**
	 * Gets the percentage of branch points in the analyzed tree associated with the
	 * specified annotation
	 *
	 * @param annot the BrainAnnotation to be queried.
	 * @return the ratio between the no. of branch points associated with
	 *         {@code annot} and the total number of branch points in the tree.
	 */
	public double getNbranchPointsNorm(final BrainAnnotation annot) {
		return (double) (getNbranchPoints(annot)) / (double)(joints.size());
	}

	/**
	 * Gets the cable length.
	 *
	 * @return the cable length of the tree
	 */
	public double getCableLength() {
		return sumLength(tree.list());
	}

	/**
	 * Gets the cable length associated with the specified compartment (neuropil
	 * label).
	 *
	 * @param compartment the query compartment (null not allowed). All of its
	 *                    children will be considered
	 * @return the filtered cable length
	 */
	public double getCableLength(final BrainAnnotation compartment) {
		return getCableLength(compartment, true);
	}

	/**
	 * Gets the cable length associated with the specified compartment (neuropil
	 * label) as a ratio of total length.
	 *
	 * @param compartment the query compartment (null not allowed). All of its
	 *                    children will be considered
	 * @return the filtered cable length normalized to total cable length
	 */
	public double getCableLengthNorm(final BrainAnnotation compartment) {
		return getCableLength(compartment, true) / getCableLength();
	}

	/**
	 * Gets the cable length associated with the specified compartment (neuropil
	 * label).
	 *
	 * @param compartment the query compartment (null not allowed)
	 * @param includeChildren whether children of {@code compartment} should be included
	 * @return the filtered cable length
	 */
	public double getCableLength(final BrainAnnotation compartment, final boolean includeChildren) {
		double sumLength = 0d;
		for (final Path path : tree.list()) {
			for (int i = 1; i < path.size(); i++) {
				final BrainAnnotation prevNodeAnnotation = path.getNodeAnnotation(i - 1);
				final BrainAnnotation currentNodeAnnotation = path.getNodeAnnotation(i);
				if (includeChildren) {
					if (isSameOrParentAnnotation(compartment, currentNodeAnnotation)
							&& isSameOrParentAnnotation(compartment, prevNodeAnnotation)) {
						sumLength += path.getNode(i).distanceTo(path.getNode(i - 1));
					}
				} else {
					if (compartment.equals(currentNodeAnnotation) &&
							compartment.equals(prevNodeAnnotation)) {
						sumLength += path.getNode(i).distanceTo(path.getNode(i - 1));
					}
				}
			}
		}
		return sumLength;
	}

	protected boolean isSameOrParentAnnotation(final BrainAnnotation annot, final BrainAnnotation annotToBeTested) {
		return annot.equals(annotToBeTested) || annot.isParentOf(annotToBeTested);
	}

	public Set<BrainAnnotation> getAnnotations() {
		final HashSet<BrainAnnotation> set = new HashSet<>();
		for (final Path path : tree.list()) {
			for (int i = 0; i < path.size(); i++) {
				final BrainAnnotation annotation = path.getNodeAnnotation(i);
				if (annotation != null) set.add(annotation);
			}
		}
		return set;
	}

	public Set<BrainAnnotation> getAnnotations(final int level) {
		final Set<BrainAnnotation> filteredAnnotations = new HashSet<>();
		getAnnotations().forEach(annot -> {
			final int depth = annot.getOntologyDepth();
			if (depth > level) {
				filteredAnnotations.add(annot.getAncestor(level - depth));
			} else {
				filteredAnnotations.add(annot);
			}
		});
		return filteredAnnotations;
	}

	/**
	 * Gets the cable length of primary branches.
	 *
	 * @return the length sum of all primary branches
	 * @see #getPrimaryBranches()
	 */
	public double getPrimaryLength() {
		if (primaryBranches == null) getPrimaryBranches();
		return sumLength(primaryBranches);
	}

	/**
	 * Gets the cable length of inner branches
	 *
	 * @return the length sum of all inner branches
	 * @see #getInnerBranches()
	 */
	public double getInnerLength() {
		if (innerBranches == null) getInnerBranches();
		return sumLength(innerBranches);
	}

	/**
	 * Gets the cable length of terminal branches
	 *
	 * @return the length sum of all terminal branches
	 * @see #getTerminalBranches()
	 */
	public double getTerminalLength() {		
		if (terminalBranches == null) getTerminalBranches();
		return sumLength(terminalBranches);
	}

	/**
	 * Gets the highest {@link sc.fiji.snt.Path#getOrder() path order} of the analyzed tree
	 *
	 * @return the highest Path order, or -1 if Paths in the Tree have no defined
	 *         order
	 * @see #getStrahlerNumber()
	 */
	public int getHighestPathOrder() {
		int root = -1;
		for (final Path p : tree.list()) {
			final int order = p.getOrder();
			if (order > root) root = order;
		}
		return root;
	}

	/**
	 * Checks whether this tree is topologically valid, i.e., contains only one root
	 * and no loops.
	 *
	 * @return true, if Tree is valid, false otherwise
	 */
	public boolean isValid() {
		if (sAnalyzer == null)
			sAnalyzer = new StrahlerAnalyzer(tree);
		try {
			sAnalyzer.getGraph();
			return true;
		} catch (final IllegalArgumentException ignored) {
			return false;
		}
	}

	/**
	 * Gets the highest {@link StrahlerAnalyzer#getRootNumber() Strahler number} of
	 * the analyzed tree.
	 *
	 * @return the highest Strahler (root) number order
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public int getStrahlerNumber() throws IllegalArgumentException {
		getStrahlerAnalyzer();
		return sAnalyzer.getRootNumber();
	}

	/**
	 * Gets the {@link StrahlerAnalyzer} instance associated with this analyzer
	 *
	 * @return the StrahlerAnalyzer instance associated with this analyzer
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public StrahlerAnalyzer getStrahlerAnalyzer() throws IllegalArgumentException {
		if (sAnalyzer == null) sAnalyzer = new StrahlerAnalyzer(tree);
		return sAnalyzer;
	}

	/**
	 * Gets the {@link ShollAnalyzer} instance associated with this analyzer. Note
	 * that changes to {@link ShollAnalyzer} must be performed before calling
	 * {@link #getMetric(String)}, {@link #measure(Collection, boolean)}, etc.
	 *
	 * @return the ShollAnalyzer instance associated with this analyzer
	 */
	public ShollAnalyzer getShollAnalyzer() {
		if (shllAnalyzer == null) shllAnalyzer = new ShollAnalyzer(tree, this);
		return shllAnalyzer;
	}

	/**
	 * Gets the average {@link StrahlerAnalyzer#getAvgBifurcationRatio() Strahler
	 * bifurcation ratio} of the analyzed tree.
	 *
	 * @return the average bifurcation ratio
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public double getStrahlerBifurcationRatio() throws IllegalArgumentException {
		getStrahlerAnalyzer();
		return sAnalyzer.getAvgBifurcationRatio();
	}

	/**
	 * Gets the number of branches in the analyzed tree.
	 *
	 * @return the number of branches
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public int getNBranches() throws IllegalArgumentException {
		return getBranches().size();
	}

	/**
	 * Gets the number of nodes in the analyzed tree.
	 *
	 * @return the number of nodes
	 */
	public long getNNodes() {
		return tree.getNodesCount();
	}

	/**
	 * Gets all the branches in the analyzed tree. A branch is defined as the Path
	 * composed of all the nodes between two branching points or between one
	 * branching point and a termination point.
	 *
	 * @return the list of branches as Path objects.
	 * @see StrahlerAnalyzer#getBranches()
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public List<Path> getBranches() throws IllegalArgumentException {
		getStrahlerAnalyzer();
		return sAnalyzer.getBranches().values().stream().flatMap(List::stream).collect(Collectors.toList());
	}

	/**
	 * Gets average {@link Path#getContraction() contraction} for all the branches
	 * of the analyzed tree.
	 * 
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 * @return the average branch contraction
	 */
	public double getAvgContraction() throws IllegalArgumentException {
		double contraction = 0;
		final List<Path> branches = getBranches();
		for (final Path p : branches) {
			final double pContraction = p.getContraction();
			if (!Double.isNaN(pContraction)) contraction += pContraction;
		}
		return contraction / branches.size();
	}

	public double getAvgFragmentation() {
		double fragmentation = 0;
		final List<Path> branches = getBranches();
		for (final Path p : branches) {
			fragmentation += p.size();
		}
		return fragmentation / branches.size();
	}

	/**
	 * Gets average length for all the branches of the analyzed tree.
	 * 
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 * @return the average branch length
	 */
	public double getAvgBranchLength() throws IllegalArgumentException {
		final List<Path> branches = getBranches();
		return sumLength(getBranches()) / branches.size();
	}

	/**
	 * Gets the angle between each bifurcation point and its children in the simplified graph, 
	 * which comprise either branch points or terminal nodes.
	 * Note that branch points with more than 2 children are ignored.
	 * 
	 * @return the list of remote bifurcation angles
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public List<Double> getRemoteBifAngles() throws IllegalArgumentException {
		final DirectedWeightedGraph sGraph = tree.getGraph(true);
		final List<SWCPoint> branchPoints = sGraph.getBPs();
		final List<Double> angles = new ArrayList<Double>();
		for (final SWCPoint bp : branchPoints) {
			final List<SWCPoint> children = Graphs.successorListOf(sGraph, bp);
			// Only consider bifurcations
			if (children.size() > 2) {
				continue;
			}
			final SWCPoint c0 = children.get(0);
			final SWCPoint c1 = children.get(1);
			// Get vector for each parent-child link
			final double[] v0 = new double[] { c0.getX() - bp.getX(), c0.getY() - bp.getY(), c0.getZ() - bp.getZ() };
			final double[] v1 = new double[] { c1.getX() - bp.getX(), c1.getY() - bp.getY(), c1.getZ() - bp.getZ() };
			// Dot product
			double dot = 0.0;
			for (int i = 0 ; i < v0.length ; i++) {
				dot += v0[i]*v1[i];
			}
			final double cosineAngle = (double) dot / ( Math.sqrt(v0[0]*v0[0] + v0[1]*v0[1] + v0[2]*v0[2]) * Math.sqrt(v1[0]*v1[0] + v1[1]*v1[1] + v1[2]*v1[2]) );
			final double angleRadians = Math.acos(cosineAngle);
			final double angleDegrees = angleRadians * ( (double) 180.0 / Math.PI );
			angles.add(angleDegrees);
		}
		return angles;
	}

	/**
	 * Gets the average remote bifurcation angle of the analyzed tree.
	 * Note that branch points with more than 2 children are ignored during the computation.
	 * 
	 * @return the average remote bifurcation angle
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public double getAvgRemoteBifAngle() throws IllegalArgumentException {
		final List<Double> angles = getRemoteBifAngles();
		double sumAngles = 0.0;
		for (final double a : angles) {
			sumAngles += a;
		}
		return (double) sumAngles / angles.size();
	}

	/**
	 * Gets the partition asymmetry at each bifurcation point in the analyzed tree.
	 * Note that branch points with more than 2 children are ignored.
	 * 
	 * @return a list containing the partition asymmetry at each bifurcation point
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public List<Double> getPartitionAsymmetry() throws IllegalArgumentException {
		final DirectedWeightedGraph sGraph = tree.getGraph(true);
		final List<SWCPoint> branchPoints = sGraph.getBPs();
		final List<Double> resultList = new ArrayList<Double>();
		for (final SWCPoint bp : branchPoints) {
			final List<SWCPoint> children = Graphs.successorListOf(sGraph, bp);
			// Only consider bifurcations
			if (children.size() > 2) {
				continue;
			}
			final List<Integer> tipCounts = new ArrayList<Integer>();
			for (final SWCPoint child : children) {
				int count = 0;
				final DepthFirstIterator<SWCPoint, SWCWeightedEdge> dfi = sGraph.getDepthFirstIterator(child);
				while (dfi.hasNext()) {
					final SWCPoint node = dfi.next();
					if (Graphs.successorListOf(sGraph, node).size() == 0) {
						count++;
					}
				}
				tipCounts.add(count);
			}
			double asymmetry;
			// Make sure we avoid getting NaN
			if (tipCounts.get(0) == tipCounts.get(1)) {
				asymmetry = 0.0;
			}
			else {
				asymmetry = (double) Math.abs(tipCounts.get(0) - tipCounts.get(1)) / (tipCounts.get(0) + tipCounts.get(1) - 2);
			}
			resultList.add(asymmetry);
		}
		return resultList;
	}

	/**
	 * Gets the average partition asymmetry of the analyzed tree.
	 * Note that branch points with more than 2 children are ignored during the computation.
	 * 
	 * @return the average partition asymmetry
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public double getAvgPartitionAsymmetry() throws IllegalArgumentException {
		final List<Double> asymmetries = getPartitionAsymmetry();
		double sumAsymmetries = 0.0;
		for (final double a : asymmetries) {
			sumAsymmetries += a;
		}
		return (double) sumAsymmetries / asymmetries.size();
	}

	/**
	 * Gets the fractal dimension of each branch in the analyzed tree.
	 * Note that branches with less than 5 points are ignored.
	 * 
	 * @return a list containing the fractal dimension of each branch
	 * @see StrahlerAnalyzer#getBranches()
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public List<Double> getFractalDimension() throws IllegalArgumentException {
		final List<Path> branches = getBranches();
		final List<Double> fractalDims = new ArrayList<Double>();
		for (final Path b : branches) {
			// Must have at least 4 points after the start-node in a branch
			if (b.size() < 5) {
				continue;
			}
			final List<Double> pathDists = new ArrayList<Double>();
			final List<Double> eucDists  = new ArrayList<Double>();
			// Start at the second node in the branch
			for (int i = 1; i < b.size(); i++) {
				double pDist = b.getNode(i).distanceTo(b.getNode(i-1));
				if (!pathDists.isEmpty()) {
					double cumDist = pathDists.get(i-2) + pDist;
					pathDists.add(cumDist);
				}
				else {
					pathDists.add(pDist);
				}
				double eDist = b.getNode(i).distanceTo(b.getNode(0));
				eucDists.add(eDist);
			}
			double numerator = 0.0;
			for (int i = 0; i < eucDists.size(); i++) {
				numerator += Math.log(1 + eucDists.get(i)) * Math.log(1 + pathDists.get(i));
			}
			double denominator = 0.0;
			for (int i = 0; i < eucDists.size(); i++) {
				denominator += Math.log(1 + eucDists.get(i)) * Math.log(1 + eucDists.get(i));
			}
			double fDim = (double) numerator / denominator;
			fractalDims.add(fDim);	
		}
		return fractalDims;
	}

	/**
	 * Gets the average fractal dimension of the analyzed tree.
	 * Note that branches with less than 5 points are ignored during the computation.
	 * 
	 * @return the average fractal dimension
	 * @throws IllegalArgumentException if the tree contains multiple roots or loops
	 */
	public double getAvgFractalDimension() throws IllegalArgumentException {
		final List<Double> fractalDims = getFractalDimension();
		double sumDims = 0.0;
		for (final double fDim : fractalDims) {
			sumDims += fDim;
		}
		return (double) sumDims / fractalDims.size();
		
	}

	/**
	 * Gets the number of spines/varicosities that have been (manually) assigned to
	 * tree being analyzed.
	 * 
	 * @return the number of spines/varicosities
	 */
	public int getNoSpinesOrVaricosities() {
		return tree.list().stream().mapToInt(p -> p.getSpineOrVaricosityCount()).sum();
	}

	/**
	 * Gets the overall density of spines/varicosities associated with this tree
	 * 
	 * @return the spine/varicosity density (same as
	 *         {@code getNoSpinesOrVaricosities()/getCableLength()})
	 */
	public double getSpineOrVaricosityDensity() {
		return getNoSpinesOrVaricosities() / getCableLength();
	}

	private double sumLength(final Collection<Path> paths) {
		double totalLength = 0d;
		for (final Path p : paths) {
			if (p.getStartJoins() != null) {
				totalLength += p.getStartJoinsPoint().distanceTo(p.getNode(0));
			}
			totalLength += p.getLength();
		}
		return totalLength;
	}

	/* IDE debug method */
	public static void main(final String[] args) throws InterruptedException {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final TreeAnalyzer analyzer = new TreeAnalyzer(tree);
		TreeAnalyzer.getMetrics().forEach( m -> {
			System.out.println(m + ": " + analyzer.getMetric(m));
		});
	}
}
