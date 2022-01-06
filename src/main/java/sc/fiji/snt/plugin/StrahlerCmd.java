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

import java.util.Comparator;
import java.util.Map;
import java.util.stream.IntStream;

import org.scijava.app.StatusService;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.ui.UIService;
import org.scijava.util.Colors;

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

/**
 * Command to perform Horton-Strahler analysis on a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class StrahlerCmd extends ContextCommand {

	@Parameter
	private PlotService plotService;

	@Parameter
	private UIService uiService;

	@Parameter
	private StatusService statusService;

	@Parameter(required = false)
	private Tree tree;

	private StrahlerAnalyzer sAnalyzer;
	private Map<Integer, Double> tLengthMap;
	private Map<Integer, Double> bPointsMap;
	private Map<Integer, Double> nBranchesMap;
	private Map<Integer, Double> bRatioMap;
	private int maxOrder;


	/**
	 * Instantiates a new StrahlerCmd. A {@code tree} is is expected to have been
	 * specified as input {@code @parameter}
	 */
	public StrahlerCmd() {
		// tree is expected as @parameter
	}

	/**
	 * Instantiates a new StrahlerCmd.
	 *
	 * @param tree the Tree to be analyzed
	 */
	public StrahlerCmd(final Tree tree) {
		this.tree = tree;
	}

	private void compute() throws IllegalArgumentException {
		if (sAnalyzer != null) return;
		statusService.showStatus("Classifying branches...");
		sAnalyzer = new StrahlerAnalyzer(tree);
		maxOrder = sAnalyzer.getRootNumber();
		tLengthMap = sAnalyzer.getLengths();
		nBranchesMap = sAnalyzer.getBranchCounts();
		bPointsMap = sAnalyzer.getBranchPointCounts();
		bRatioMap = sAnalyzer.getBifurcationRatios();
	}

	@Override
	public void run() {
		if (tree == null || tree.isEmpty()) {
			cancel("No Paths to Measure");
			return;
		}
		try {
			uiService.show("SNT: Strahler Table", getTable());
			getChart().show();
			//uiService.show("SNT: Strahler Plot", getCategoryChart());
		} catch (final IllegalArgumentException ex) {
			cancel("Analysis could not be performed: " + ex.getLocalizedMessage() + "\n"
			+ "Please ensure you select a single set of connected paths (one root exclusively)");
		} finally {
			statusService.clearStatus();
		}
	}

	/**
	 * Assesses if tree contains multiple roots or loops
	 *
	 * @return true, if successful
	 */
	public boolean validStructure() {
		try {
			compute();
		} catch (final IllegalArgumentException ex) {
			return false;
		}
		return true;
	}

	/**
	 * Returns the Strahler chart.
	 *
	 * @return the Strahler chart
	 * @see #getChart()
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public CategoryChart<Integer> getCategoryChart() throws IllegalArgumentException {

		compute();
		final CategoryChart<Integer> chart = plotService.newCategoryChart(Integer.class);
		chart.categoryAxis().setLabel("Horton-Strahler order");
		chart.categoryAxis().setOrder(Comparator.reverseOrder());

		final LineSeries<Integer> series1 = chart.addLineSeries();
		series1.setLabel("No. of branches");
		series1.setValues(nBranchesMap);
		series1.setStyle(chart.newSeriesStyle(Colors.BLUE, LineStyle.SOLID, MarkerStyle.CIRCLE));

		final LineSeries<Integer> series2 = chart.addLineSeries();
		series2.setLabel("Length");
		series2.setValues(tLengthMap);
		series2.setStyle(chart.newSeriesStyle(Colors.RED, LineStyle.SOLID, MarkerStyle.CIRCLE));
		
		final LineSeries<Integer> series3 = chart.addLineSeries();
		series3.setLabel("Bifurcation ratio");
		series3.setValues(bRatioMap);
		series3.setStyle(chart.newSeriesStyle(Colors.BLACK, LineStyle.SOLID, MarkerStyle.CIRCLE));
		return chart;
	}

	/**
	 * A variant of {@link #getCategoryChart()} that returns the Strahler chart as a
	 * {@link SNTChart} object.
	 *
	 * @return the Strahler chart as a {@link SNTChart} object
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public SNTChart getChart() throws IllegalArgumentException {
		final String title = (tree.getLabel()== null) ? "Strahler Plot" : tree.getLabel() + "Strahler Plot";
		return new SNTChart(title, getCategoryChart());
	}

	
	/**
	 * Gets the Strahler table.
	 *
	 * @return the Strahler table
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public SNTTable getTable() throws IllegalArgumentException {
		compute();
		final SNTTable table = new SNTTable();
		IntStream.rangeClosed(1, maxOrder).forEach(order -> {
			table.appendRow();
			final int row = Math.max(0, table.getRowCount() - 1);
			table.set("Horton-Strahler #", row, order);
			table.set("Rev. Horton-Strahler #", row, maxOrder - order + 1);
			table.set("Length (Sum)", row, tLengthMap.get(order));
			table.set("# Branches", row, nBranchesMap.get(order));
			table.set("Bifurcation ratio", row, bRatioMap.get(order));
			table.set("# Branch Points", row, bPointsMap.get(order));
		});
		return table;
	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTree("fractal");
		final StrahlerCmd cmd = new StrahlerCmd(tree);
		cmd.setContext(ij.context());
		cmd.run();
	}
}
