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

package sc.fiji.snt.plugin;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import net.imagej.ImageJ;
import org.scijava.plot.CategoryChart;
import org.scijava.plot.LineSeries;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.GroupedTreeStatistics;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.StrahlerAnalyzer;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.util.SNTColor;

/**
 * Command to perform Horton-Strahler analysis on a collection of {@link Tree}s
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label="Strahler Analysis...")
public class StrahlerCmd extends ContextCommand {

	@Parameter
	private DisplayService displayService;

	@Parameter
	private PlotService plotService;

	@Parameter
	private SNTService sntService;

	@Parameter(required = false)
	private Collection<Tree> trees;

	@Parameter(label = "Include in depth analysis of each Strahler order")
	private boolean detailedAnalysis;

	private Map<String, StrahlerData> dataMap;
	private SNTTable summaryTable;
	private SNTTable detailedTable;

	/**
	 * Instantiates a new StrahlerCmd. Trees to be analyzed expected as input
	 * {@code @parameter}
	 */
	public StrahlerCmd() {
		// tree or trees expected as @parameter
	}

	/**
	 * Instantiates a new StrahlerCmd.
	 *
	 * @param trees the collection of Trees to be analyzed
	 */
	public StrahlerCmd(final Collection<Tree> trees) {
		this.trees = trees;
	}

	/**
	 * Instantiates a new StrahlerCmd for a single Tree
	 *
	 * @param tree the single Tree to be analyzed
	 */
	public StrahlerCmd(final Tree tree) {
		this(Collections.singleton(tree));
	}

	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			cancel("No valid reconstruction(s) to parse.");
			return;
		}
		if (!validInput()) {
			if (sntService.isActive() && mixedPaths()) {
				cancel("None of the reconstruction(s) could be parsed. This is likely caused by\n"
						+ "mixing fitted and un-fitted paths which may mask original connectivity.\n"
						+ "You may need to apply (or discard) fits more coherently.");
			} else {
				cancel("None of the reconstruction(s) could be parsed. Invalid topologies?");
			}
			return;
		}
		Display<?> tableDisplay = displayService.getDisplay("SNT Strahler Table");
		boolean newTableRequired = summaryTable == null || tableDisplay == null || !tableDisplay.isDisplaying(summaryTable);
		if (newTableRequired) {
			summaryTable = new SNTTable();
		}
		populateSummaryTable(summaryTable);
		if (newTableRequired) {
			displayService.createDisplay("SNT Strahler Table", summaryTable);
		} else {
			tableDisplay.update();
		}
		if (detailedAnalysis) {
			final List<SNTChart> charts = new ArrayList<>();
			for (final String m : new String[]{"Branch length", "Branch contraction"}) {
				charts.add(getHistogram(m));
				charts.add(getBoxPlot(m));
			}
			tableDisplay = displayService.getDisplay("SNT Detailed Strahler Table");
			newTableRequired = detailedTable == null || tableDisplay == null || !tableDisplay.isDisplaying(detailedTable);
			if (newTableRequired) {
				detailedTable = new SNTTable();
			}
			populateDetailedTable(detailedTable);
			if (newTableRequired) {
				displayService.createDisplay("SNT Detailed Strahler Table", detailedTable);
			} else {
				tableDisplay.update();
			}
			if (trees.size() == 1) {
				final SNTChart cChart = SNTChart.combine(charts);
				cChart.setTitle("Strahler Charts");
				cChart.show();
			} else {
				charts.forEach(c -> c.show());
			}
		}
		getChart().show();

	}

	private void initMap() {
		if (dataMap == null) {
			dataMap = new TreeMap<>();
			int i = 1;
			for (final Tree tree : trees) {
				dataMap.put((tree.getLabel() == null) ? "Tree " + i++ : tree.getLabel(), new StrahlerData(tree));
			}
		}
	}

	private boolean mixedPaths() {
		final boolean ref = trees.iterator().next().get(0).isFittedVersionOfAnotherPath();
		for (final Tree tree : trees) {
			for (final Path path : tree.list()) {
				if (path.isFittedVersionOfAnotherPath() != ref)
					return true;
			}
		}
		return false;
	}

	private CategoryChart getSingleTreeChart(final StrahlerData sd) throws IllegalArgumentException {
		if (!sd.parseable()) {
			throw new IllegalArgumentException("");
		}
		final CategoryChart chart = plotService.newCategoryChart();
		chart.categoryAxis().setLabel("Horton-Strahler order");
		chart.categoryAxis().setOrder((Comparator<?>) Comparator.reverseOrder());
		LineSeries series = chart.addLineSeries();
		series.setLabel("Length (sum)");
		series.setValues(sd.analyzer.getLengths());
		series.setStyle(plotService.newSeriesStyle(Colors.BLUE, LineStyle.SOLID, MarkerStyle.CIRCLE));
		series = chart.addLineSeries();
		series.setLabel("No. of branches");
		series.setValues(sd.analyzer.getBranchCounts());
		series.setStyle(plotService.newSeriesStyle(Colors.RED, LineStyle.SOLID, MarkerStyle.CIRCLE));
		series = chart.addLineSeries();
		series.setLabel("Bif. ratio");
		series.setValues(sd.analyzer.getBifurcationRatios());
		series.setStyle(plotService.newSeriesStyle(Colors.DARKORANGE, LineStyle.SOLID, MarkerStyle.CIRCLE));
		series = chart.addLineSeries();
		series.setLabel("Avg. contraction");
		series.setValues(sd.analyzer.getAvgContractions());
		series.setStyle(plotService.newSeriesStyle(Colors.DARKMAGENTA, LineStyle.SOLID, MarkerStyle.CIRCLE));
		series = chart.addLineSeries();
		series.setLabel("Avg. fragmentation");
		series.setValues(sd.analyzer.getAvgFragmentations());
		series.setStyle(plotService.newSeriesStyle(Colors.DARKGREEN, LineStyle.SOLID, MarkerStyle.CIRCLE));
		return chart;
	}

	/**
	 * Returns the 'complete Strahler plot' (e.g., all metrics in a single plot). If
	 * multiple trees are being analyzed, returns a multi-panel montage, in
	 * which each panel corresponding to each individual plot.
	 *
	 * @return the Strahler plot
	 */
	public SNTChart getChart() {
		if (trees.size() == 1) {
			initMap();
			final Entry<String, StrahlerData> entry = dataMap.entrySet().iterator().next();
			return new SNTChart("Strahler Plot " + entry.getKey(), getSingleTreeChart(entry.getValue()));
		}
		final List<SNTChart> charts = new ArrayList<>();
		charts.add(getChart("length"));
		charts.add(getChart("branches"));
		charts.add(getChart("bifurcation"));
		charts.add(getChart("fragmentation"));
		charts.add(getChart("contraction"));
		final SNTChart combinedFrame = SNTChart.combine(charts, false);
		combinedFrame.setTitle("Combined Strahler Plots");
		return combinedFrame;
	}

	/**
	 * Returns a 'Strahler plot' for the specified metric.
	 *
	 * @param metric either "avg contraction", "avg fragmentation", "bifurcation
	 *               ratio", "branch count", or "length" (default)
	 * @return the Strahler plot
	 */
	public SNTChart getChart(final String metric) {
		final String normMetric = normalizedStrahlerMetric(metric);
		initMap();
		final CategoryChart chart = plotService.newCategoryChart();
		chart.categoryAxis().setLabel("Horton-Strahler order");
		chart.categoryAxis().setOrder((Comparator<?>) Comparator.reverseOrder());
		chart.numberAxis().setLabel(normMetric);
		final ColorRGB[] colors = SNTColor.getDistinctColors(trees.size());
		final int[] idx = { 0 };
		dataMap.forEach((label, data) -> {
			final LineSeries series = chart.addLineSeries();
			series.setLabel(label);
			series.setStyle(plotService.newSeriesStyle(colors[idx[0]++], LineStyle.SOLID, MarkerStyle.CIRCLE));
			if (data.parseable()) {
				if (normMetric.toLowerCase().contains("length"))
					series.setValues(data.analyzer.getLengths());
				else if (normMetric.contains("branch"))
					series.setValues(data.analyzer.getBranchCounts());
				else if (normMetric.contains("ratio"))
					series.setValues(data.analyzer.getBifurcationRatios());
				else if (normMetric.contains("contract"))
					series.setValues(data.analyzer.getAvgContractions());
				else if (normMetric.contains("frag"))
					series.setValues(data.analyzer.getAvgFragmentations());
				else
					throw new IllegalArgumentException("Unrecognized metric: " + normMetric);
			}
		});
		return new SNTChart("Strahler Plot (" + normMetric + ")", chart);
	}

	/**
	 * Returns a histogram for the specified metric, in which data is grouped by
	 * order.
	 *
	 * @param metric either "branch contraction", "branch fragmentation", or "branch
	 *               length" (default)
	 * @return the Strahler histogram if only a single Tree is being analyzed, or a
	 *         montage of histograms if multiple trees are being parsed.
	 */
	public SNTChart getHistogram(final String metric) {
		return getTreeStatisticsChart(metric, false);
	}

	/**
	 * Returns a boxplot for the specified metric, in which data is grouped by
	 * order.
	 *
	 * @param metric either "branch contraction", "branch fragmentation", or "branch
	 *               length" (default)
	 * @return the Strahler boxplot if only a single Tree is being analyzed, or a
	 *         montage of plots if multiple trees are being parses.
	 */
	public SNTChart getBoxPlot(final String metric) {
		return getTreeStatisticsChart(metric, true);
	}

	private SNTChart getTreeStatisticsChart(final String metric, final boolean boxplotElseHistogram) {
		final String normMetric = normalizedTreeStatisticsMetric(metric);
		initMap();
		final List<SNTChart> charts = new ArrayList<>();
		final boolean singleChart = getValidTrees().size() == 1;
		dataMap.forEach((label, data) -> {
			if (!data.parseable()) return;
			// We'll create a histogram per tree being analyzed: For each histogram,
			// we'll treat Strahler orders as series, each detailing the distribution
			// of the metric for the order.A quick/hacky way to do this is to artificially
			// treat each branch as a tree, so that we can use GroupedTreeStatistics
			final GroupedTreeStatistics groupedStats = new GroupedTreeStatistics();
			data.analyzer.getBranches().forEach((order, branches) -> {
				final List<Tree> branchesAsTree = new ArrayList<>(data.analyzer.getBranches().size());
				branches.forEach(b -> branchesAsTree.add(new Tree(Collections.singleton(b))));
				groupedStats.addGroup(branchesAsTree, "Order " + order);
			});
			groupedStats.setMinNBins(6);
			final SNTChart chart = (boxplotElseHistogram) ? groupedStats.getBoxPlot(normMetric)
					: groupedStats.getHistogram(normMetric);
			if (singleChart) {
				chart.setTitle("Strahler " + normMetric + ((boxplotElseHistogram) ? " BoxPlot " : " Histogram ") + label);
			} else {
				chart.setChartTitle(label);
			}
			charts.add(chart);
		});
		if (singleChart) {
			return charts.get(0);
		}
		final SNTChart result = SNTChart.combine(charts);
		result.setTitle("Strahler Combined " + normMetric + ((boxplotElseHistogram)? " BoxPlots" : " Histograms"));
		return result;
	}

	/**
	 * Gets the detailed table listing individual branch properties aggregated by
	 * Strahler order.
	 *
	 * @return the detailed table
	 */
	public SNTTable getDetailedTable() {
		final SNTTable table = new SNTTable();
		populateDetailedTable(table);
		return table;
	}

	private void populateDetailedTable(final SNTTable table) {
		initMap();
		dataMap.forEach((label, data) -> {
			data.analyzer.getBranches().forEach((order, branches) -> {
				table.addColumn(label + " Branch length Order " + order,
						branches.stream().map(Path::getLength).collect(Collectors.toList()));
			});
			data.analyzer.getBranches().forEach((order, branches) -> {
				table.addColumn(label + " Contraction Order " + order,
						branches.stream().map(Path::getContraction).collect(Collectors.toList()));
			});
		});
		table.fillEmptyCells(Double.NaN);
	}

	/**
	 * Gets the summary table containing the tabular results of the analysis.
	 *
	 * @return the summary table
	 */
	public SNTTable getSummaryTable() {
		final SNTTable table = new SNTTable();
		populateSummaryTable(table);
		return table;
	}

	/**
	 * @deprecated Use {@link #getSummaryTable()} instead
	 */
	@Deprecated
	public SNTTable getTable() {
		return getSummaryTable();
	}

	/**
	 * Assesses if all trees being analyzed can be parsed (A tree won't be parsed,
	 * if topologically invalid, e.g., by containing a loop, or disconnected Paths).
	 *
	 * @return true, if successful
	 */
	public boolean validInput() {
		initMap();
		return dataMap.values().stream().allMatch(analyzer -> analyzer.parseable());
	}

	/**
	 * Returns the Tree(s) successfully parsed (i.e., topologically valid)
	 *
	 * @return the list of parsed trees
	 */
	public List<Tree> getValidTrees() {
		initMap();
		return dataMap.values().stream()
				.filter( analyzer -> analyzer.parseable())
				.map( analyzer -> analyzer.tree)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Returns the Tree(s) that could not be parsed (i.e., topologically invalid)
	 *
	 * @return the list of non-parsable trees
	 */
	public List<Tree> getInvalidTrees() {
		initMap();
		return dataMap.values().stream()
				.filter( analyzer -> !analyzer.parseable())
				.map( analyzer -> analyzer.tree)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	private void populateSummaryTable(final SNTTable table) {
		initMap();
		final DecimalFormat iDF = new DecimalFormat("#");
		final DecimalFormat dDF = new DecimalFormat("#.###");
		dataMap.forEach((label, data) -> {
			int row = table.getRowIndex(label);
			if (row < 0)
				row = table.insertRow(label);
			if (data.parseable()) {
				table.set("Root no.", row, iDF.format(data.analyzer.getRootNumber()));
				table.set("Avg. bif. ratio", row, dDF.format(data.analyzer.getAvgBifurcationRatio()));
				table.set("Order:Measurement Pairs: Length (sum)", row, toString(data.analyzer.getLengths(), dDF));
				table.set("Order:Measurement Pairs: No. of branches", row,
						toString(data.analyzer.getBranchCounts(), iDF));
				table.set("Order:Measurement Pairs: Bifurcation ratio", row,
						toString(data.analyzer.getBifurcationRatios(), iDF));
				table.set("Order:Measurement Pairs: Avg. contraction", row,
						toString(data.analyzer.getAvgContractions(), iDF));
				table.set("Order:Measurement Pairs: Avg. fragmentation", row,
						toString(data.analyzer.getAvgFragmentations(), iDF));
			} else {
				table.set("Root no.", row, "Not parseable");
			}
		});
	}

	private String normalizedStrahlerMetric(String metric) {
		metric = metric.toLowerCase();
		if (metric.contains("bif"))
			return "Bifurcation ratio";
		else if (metric.contains("branch"))
			return "No. of branches";
		else if (metric.contains("length"))
			return "Length (sum)";
		else if (metric.contains("frag"))
			return "Avg. fragmentation";
		else if (metric.contains("contract"))
			return "Avg. contraction";
		else
			throw new IllegalArgumentException("Unrecognized metric");
	}

	private String normalizedTreeStatisticsMetric(String metric) {
		for (final String m : TreeStatistics.getAllMetrics()) {
			if (m.equalsIgnoreCase(metric)) return m;
		}
		throw new IllegalArgumentException("Unrecognized metric");
	}

	private String toString(final Map<Integer, Double> map, final DecimalFormat df) {
		return map.keySet().stream().map(key -> key + ":" + df.format(map.get(key))).collect(Collectors.joining("; "));
	}

	private static class StrahlerData {
		final StrahlerAnalyzer analyzer;
		final Tree tree;

		StrahlerData(final Tree tree) {
			this.analyzer = new StrahlerAnalyzer(tree);
			this.tree = tree;
		}

		boolean parseable() {
			try {
				return analyzer.getRootNumber() > 0;
			} catch (final IllegalArgumentException ignored) {
				return false;
			}
		}
	}

	/**
	 * Whether run calls should output detailed tables and plots
	 *
	 * @param detailedAnalysis if true, subsequent {@linkplain #run} calls will
	 *                         include extra outputs
	 */
	public void setDetailedAnalysis(boolean detailedAnalysis) {
		this.detailedAnalysis = detailedAnalysis;
	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		StrahlerCmd cmd = new StrahlerCmd(sntService.demoTrees().get(2));
		cmd.setContext(ij.context());
		cmd.setDetailedAnalysis(true);
		cmd.run();
		cmd = new StrahlerCmd(sntService.demoTree("op"));
		cmd.setContext(ij.context());
		cmd.setDetailedAnalysis(true);
		cmd.run();
	}
}
