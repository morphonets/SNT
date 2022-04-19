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

import javax.swing.JFrame;

import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import net.imagej.ImageJ;
import org.scijava.plot.CategoryChart;
import org.scijava.plot.LineSeries;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.StrahlerAnalyzer;
import sc.fiji.snt.util.SNTColor;

/**
 * Command to perform Horton-Strahler analysis on a collection of {@link Tree}s
 *
 * @author Tiago Ferreira
 */
public class StrahlerCmd extends ContextCommand {

	@Parameter
	private DisplayService displayService;

	@Parameter
	private PlotService plotService;

	@Parameter
	private SNTService sntService;

	@Parameter(required = false)
	private Collection<Tree> trees;

	private Map<String, StrahlerData> dataMap;
	private static SNTTable table;

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
		this.trees = Collections.singleton(tree);
	}

	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			cancel("No valid reconstruction(s) to parse.");
			return;
		}
		final Display<?> display = displayService.getDisplay("SNT Strahler Table");
		final boolean newTableRequired = table == null || display == null || !display.isDisplaying(table);
		if (newTableRequired) {
			table = new SNTTable();
		}
		populateTable(table);
		if (newTableRequired) {
			displayService.createDisplay("SNT Strahler Table", table);
		} else {
			display.update();
		}
		if (trees.size() == 1) {
			getChart().show();
		} else {
			final List<SNTChart> charts = new ArrayList<>();
			charts.add(getChart("length"));
			charts.add(getChart("branches"));
			charts.add(getChart("bifurcation"));
			charts.add(getChart("fragmentation"));
			charts.add(getChart("contraction"));
			final JFrame combinedFrame = SNTChart.combinedFrame(charts);
			combinedFrame.setTitle("Combined Strahler Plots");
			combinedFrame.setVisible(true);
		}
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
	 * Returns the 'complete Strahler chart' (e.g., all metrics in a single panel),
	 * when analyzing a single tree. If multiple trees are being analyzed, returns
	 * the chart for the 'length' metric.
	 *
	 * @return the Strahler chart
	 */
	public SNTChart getChart() {
		if (trees.size() == 1) {
			final Entry<String, StrahlerData> entry = dataMap.entrySet().iterator().next();
			return new SNTChart("Strahler Plot " + entry.getKey(), getSingleTreeChart(entry.getValue()));
		} else {
			return getChart("length");
		}
	}

	/**
	 * Returns a 'Strahler chart' for the specified metric.
	 *
	 * @param metric either "avg contraction", "avg fragmentation", "bifurcation
	 *               ratio", "branch count", or "length" (default)
	 * @return the Strahler chart
	 */
	public SNTChart getChart(final String metric) {
		final String normMetric = normalizedMetric(metric);
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
	 * Gets the Strahler table containing the tabular results of the analysis.
	 *
	 * @return the Strahler table
	 */
	public SNTTable getTable() {
		final SNTTable table = new SNTTable();
		populateTable(table);
		return table;
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
	 * @return the list of parsable trees
	 */
	public List<Tree> getValidTrees() {
		initMap();
		return dataMap.values().stream()
				.filter( analyzer -> analyzer.parseable())
				.map( analyzer -> analyzer.tree)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Returns the Tree(s) that could not be parsed (i.e., topologically valid)
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

	private void populateTable(final SNTTable table) {
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

	private String normalizedMetric(String metric) {
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

	private String toString(final Map<Integer, Double> map, final DecimalFormat df) {
		return map.keySet().stream().map(key -> key + ":" + df.format(map.get(key))).collect(Collectors.joining("; "));
	}

	private class StrahlerData {
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

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		StrahlerCmd cmd = new StrahlerCmd(sntService.demoTrees());
		cmd.setContext(ij.context());
		cmd.run();
		cmd = new StrahlerCmd(sntService.demoTree("op"));
		cmd.setContext(ij.context());
		cmd.run();
	}
}
