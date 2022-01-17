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

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.swing.JFrame;

import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.ui.UIService;
import org.scijava.util.ColorRGB;

import net.imagej.ImageJ;
import net.imagej.plot.CategoryChart;
import net.imagej.plot.LineSeries;
import net.imagej.plot.LineStyle;
import net.imagej.plot.MarkerStyle;
import net.imagej.plot.PlotService;
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
public class StrahlerBulkCmd extends ContextCommand {

	@Parameter
	private PlotService plotService;

	@Parameter
	private UIService uiService;

	private final Collection<Tree> trees;
	private Map<String, StrahlerData> dataMap;

	/**
	 * Instantiates a new StrahlerBulkCmd.
	 *
	 * @param trees the Trees to be analyzed
	 */
	public StrahlerBulkCmd(final Collection<Tree> trees) {
		this.trees = trees;
	}

	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			cancel("No valid reconstructions to parse.");
			return;
		}
		initMap();
		uiService.show("SNT: Strahler Table", getTable());
		final List<SNTChart> charts = new ArrayList<>();
		charts.add(getChart("length"));
		charts.add(getChart("branches"));
		charts.add(getChart("bifurcation"));
		charts.add(getChart("fragmentation"));
		charts.add(getChart("contraction"));
		final JFrame combinedFrame = SNTChart.combinedFrame(charts);
		combinedFrame.setTitle("Combined Strahler Plots");
		combinedFrame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				SNTChart.getOpenCharts();
			}
			@Override
			public void windowClosed(final WindowEvent e) {
				SNTChart.getOpenCharts();
			}
		});
		combinedFrame.setVisible(true);
		
	}

	private void initMap() {
		if (dataMap == null) {
			dataMap = new TreeMap<>();
			trees.forEach(tree -> dataMap.put(tree.getLabel(), new StrahlerData(tree)));
		}
	}

	/**
	 * Returns the Strahler chart as a {@link SNTChart} object.
	 *
	 * @param metric either "bifurcation ratios", "branch counts", or "length"
	 * @return the Strahler chart
	 */
	public SNTChart getChart(final String metric) {
		final String normMetric = normalizedMetric(metric);
		initMap();
		final CategoryChart<Integer> chart = plotService.newCategoryChart(Integer.class);
		chart.categoryAxis().setLabel("Horton-Strahler order");
		chart.categoryAxis().setOrder(Comparator.reverseOrder());
		chart.numberAxis().setLabel(normMetric);
		final ColorRGB[] colors = SNTColor.getDistinctColors(trees.size());
		final int[] idx = { 0 };
		dataMap.forEach((label, data) -> {
			final LineSeries<Integer> series = chart.addLineSeries();
			series.setLabel(label);
			series.setStyle(chart.newSeriesStyle(colors[idx[0]++], LineStyle.SOLID, MarkerStyle.CIRCLE));
			if (data.parseable()) {
				if (normMetric.contains("ratio"))
					series.setValues(data.analyzer.getBifurcationRatios());
				else if (normMetric.contains("branch"))
					series.setValues(data.analyzer.getBranchCounts());
				else if (normMetric.toLowerCase().contains("length"))
					series.setValues(data.analyzer.getLengths());
				else if (normMetric.contains("frag"))
					series.setValues(data.analyzer.getAvgFragmentations());
				else if (normMetric.contains("contract"))
					series.setValues(data.analyzer.getAvgContractions());
				else
					throw new IllegalArgumentException("Unrecognized metric: " + normMetric);
			}
		});
		return new SNTChart("Strahler Plot (" + normMetric + ")", chart);
	}

	/**
	 * Gets the table with bulk measurements
	 *
	 * @return the table
	 */
	public SNTTable getTable() throws IllegalArgumentException {
		initMap();
		final SNTTable table = new SNTTable();
		final DecimalFormat iDF = new DecimalFormat("#");
		final DecimalFormat dDF = new DecimalFormat("#.###");
		dataMap.forEach((label, data) -> {
			final int row = table.insertRow(label);
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
		return table;
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

	private class StrahlerData {
		final StrahlerAnalyzer analyzer;

		StrahlerData(final Tree tree) {
			this.analyzer = new StrahlerAnalyzer(tree);
		}

		boolean parseable() {
			try {
				return analyzer.getRootNumber() > 0;
			} catch (final IllegalArgumentException ignored) {
				return false;
			}
		}
	}

	private String toString(final Map<Integer, Double> map, final DecimalFormat df) {
		return map.keySet().stream().map(key -> key + ":" + df.format(map.get(key))).collect(Collectors.joining("; "));
	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final StrahlerBulkCmd cmd = new StrahlerBulkCmd(sntService.demoTrees());
		cmd.setContext(ij.context());
		cmd.run();
	}
}
