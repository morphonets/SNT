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

package sc.fiji.snt.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.scijava.plot.CategoryChart;
import org.scijava.plot.LineSeries;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYSeries;

import org.scijava.plugin.Parameter;
import org.scijava.ui.UIService;
import org.scijava.util.ColorRGB;

import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

/**
 * Command to perform {@link Path#getOrder() Path Ordering} analysis on a
 * {@link Tree}. Albeit related to reverse Horton-Strahler classification, Path
 * ordering is formally distinct, as it classifies <i>Paths</i> rather than
 * <i>branches</i>.
 * 
 * @see StrahlerCmd
 * 
 * @author Tiago Ferreira
 */
public class PathOrderAnalysisCmd extends TreeAnalyzer {

	@Parameter
	private PlotService plotService;

	@Parameter
	private UIService uiService;

	private int maxPathOrder;
	private int nPathsPreviousOrder;
	private final Map<Integer, Double> nPathsMap = new TreeMap<>();
	private final Map<Integer, Double> bPointsMap = new TreeMap<>();
	private final Map<Integer, Double> bRatioMap = new TreeMap<>();
	private final Map<Integer, Double> tLengthMap = new TreeMap<>();

	public PathOrderAnalysisCmd(final Tree tree) {
		super(tree);
	}

	@Override
	public void run() {
		if (tree == null || tree.isEmpty()) {
			cancel("No Paths to Measure");
			return;
		}
		statusService.showStatus("Measuring Paths...");
		compute();
		updateAndDisplayTable();
		displayPlot();
		statusService.clearStatus();
	}

	public void compute() {
		maxPathOrder = 1;
		for (final Path p : tree.list()) {
			if (p.getOrder() > maxPathOrder) maxPathOrder = p.getOrder();
		}
		IntStream.rangeClosed(1, maxPathOrder).forEach(order -> {

			final ArrayList<Path> groupedPaths = tree.list().stream() // convert set
																																// of paths to
																																// stream
				.filter(path -> path.getOrder() == order) // include only those of this
																									// order
				.collect(Collectors.toCollection(ArrayList::new)); // collect the output
																														// in a new list

			// now measure the group
			final TreeAnalyzer analyzer = new TreeAnalyzer(new Tree(groupedPaths));
			if (!analyzer.getParsedTree().isEmpty()) {
				tLengthMap.put(order, analyzer.getCableLength());
				final int nPaths = analyzer.getNPaths();
				nPathsMap.put(order, (double) nPaths);
				bPointsMap.put(order, (double) analyzer.getBranchPoints().size());
				bRatioMap.put(order, (order > 1) ? (double) nPaths / nPathsPreviousOrder
					: Double.NaN);
				nPathsPreviousOrder = nPaths;
			}
		});
	}

	@Override
	public void updateAndDisplayTable() {
		if (table == null) setTable(new SNTTable(),
			"Path Order Analysis");
		IntStream.rangeClosed(1, maxPathOrder).forEach(order -> {
			table.appendRow();
			final int row = Math.max(0, table.getRowCount() - 1);
			table.set(getCol("Path order"), row, order);
			table.set(getCol("Length (Sum)"), row, tLengthMap.get(order));
			table.set(getCol("# Paths"), row, (nPathsMap.get(order).intValue()));
			table.set(getCol("# Branch Points"), row, bPointsMap.get(order)
				.intValue());
			table.set(getCol("Bifurcation ratio"), row, bRatioMap.get(order));
		});
		super.updateAndDisplayTable();
	}

	public void displayPlot() {
		getChart().show();
	}

	/**
	 * Returns the analysis chart.
	 *
	 * @return the analysis chart
	 * @see #getChart()
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public CategoryChart getCategoryChart() throws IllegalArgumentException {	
		final CategoryChart chart = plotService.newCategoryChart();
		final List<Integer> categories = IntStream.rangeClosed(1, maxPathOrder)
				.boxed().collect(Collectors.toList());
		chart.categoryAxis().setManualCategories(categories);

		final LineSeries series1 = chart.addLineSeries();
		series1.setLabel("N. Paths");
		series1.setValues(nPathsMap);
		series1.setStyle(plotService.newSeriesStyle(new ColorRGB("#1b9e77"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));

		final LineSeries series2 = chart.addLineSeries();
		series2.setLabel("N. Branch points");
		series2.setValues(bPointsMap);
		series2.setStyle(plotService.newSeriesStyle(new ColorRGB("#d95f02"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));

		final LineSeries series3 = chart.addLineSeries();
		series3.setLabel("Length");
		series3.setValues(tLengthMap);
		series3.setStyle(plotService.newSeriesStyle(new ColorRGB("#7570b3"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));

		chart.categoryAxis().setLabel("Path order");
		return chart;
	}

	/**
	 * A variant of {@link #getCategoryChart()} that returns the Analysis chart as a
	 * {@link SNTChart} object.
	 *
	 * @return the Strahler chart as a {@link SNTChart} object
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public SNTChart getChart() throws IllegalArgumentException {
		final String title = (tree.getLabel()== null) ? "Strahler Plot" : tree.getLabel() + "Strahler Plot";
		return new SNTChart(title, getCategoryChart());
	}

	@SuppressWarnings("unused")
	private void displayXYPlot() {

		final XYPlot plot = plotService.newXYPlot();
		final XYSeries series1 = plot.addXYSeries();
		series1.setStyle(plotService.newSeriesStyle(new ColorRGB("#1b9e77"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));
		series1.setLabel("N. Paths");
		series1.setValues(converIntSetToDoubleList(nPathsMap.keySet()), nPathsMap.values().stream().collect(Collectors.toList()));

		final XYSeries series2 = plot.addXYSeries();
		series2.setStyle(plotService.newSeriesStyle(new ColorRGB("#d95f02"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));
		series2.setLabel("N. Branch points");
		series1.setValues(converIntSetToDoubleList(bPointsMap.keySet()), bPointsMap.values().stream().collect(Collectors.toList()));


		final XYSeries series3 = plot.addXYSeries();
		series3.setStyle(plotService.newSeriesStyle(new ColorRGB("#7570b3"), LineStyle.SOLID,
				MarkerStyle.CIRCLE));
		series3.setLabel("Length");
		series1.setValues(converIntSetToDoubleList(tLengthMap.keySet()), tLengthMap.values().stream().collect(Collectors.toList()));

		uiService.show("SNT: Path Order Plot", plot);
	}

	private List<Double> converIntSetToDoubleList(final Set<Integer> set) {
		ArrayList<Double> list = new ArrayList<>(set.size());
		set.forEach( i -> list.add((double)i));
		return list;
	}

	/**
	 * @return the map containing the number of paths on each order (order as key
	 *         and counts as value). Single-point paths are ignored. An empty map
	 *         will be returned if {{@link #compute()} has not been called.
	 */
	public Map<Integer, Double> getCountMap() {
		return nPathsMap;
	}

	/**
	 * @return the map containing the number of branch points on each order (order
	 *         as key and branch point count as value). Single-point paths are
	 *         ignored. An empty map will be returned if {{@link #compute()} has not
	 *         been called.
	 */
	public Map<Integer, Double> getBranchPointMap() {
		return bPointsMap;
	}

	/**
	 * @return the highest path order of the parsed tree.
	 */
	public int getHighestPathOrder() {
		return this.maxPathOrder;
	}

	/**
	 * @return the map containing the total path lengh on each order (order as key
	 *         and sum length as value). Single-point paths are ignored. An empty
	 *         map will be returned if {{@link #compute()} has not been called.
	 */
	public Map<Integer, Double> getLengthMap() {
		return tLengthMap;
	}

	/**
	 * @return the map containing th bifurcation ratios between orders (order as key
	 *         and ratios as value). Single-point paths are ignored. An empty map
	 *         will be returned if {{@link #compute()} has not been called.
	 */
	public Map<Integer, Double> getRatioMap() {
		return bRatioMap;
	}

}
