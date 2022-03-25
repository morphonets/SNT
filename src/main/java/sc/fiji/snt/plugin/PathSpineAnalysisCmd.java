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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.DefaultColumn;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import net.imagej.ImageJ;
import net.imagej.plot.LineStyle;
import net.imagej.plot.MarkerStyle;
import net.imagej.plot.PlotService;
import net.imagej.plot.XYPlot;
import net.imagej.plot.XYSeries;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathAnalyzer;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Command for obtaining plots pertaining to spine/varicosity counts
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Spine/Varicosity Density Profile...", initializer = "init")
public class PathSpineAnalysisCmd extends CommonDynamicCmd {

	@Parameter
	private PlotService plotService;

	@Parameter(label = "X-Axis Metric", choices = { //
			TreeStatistics.PATH_SPINE_DENSITY, //
			TreeStatistics.N_SPINES //
	})
	private String xAxisMetric;

	@Parameter(label = "Y-axis Metric 1", choices = { //
			TreeStatistics.PATH_LENGTH, TreeStatistics.N_BRANCH_POINTS, //
			TreeStatistics.PATH_ORDER, TreeStatistics.PATH_MEAN_RADIUS, //
	})
	private String yAxisMetric1;

	@Parameter(label = "Y-axis Metric 2", choices = { " - None -", //
			TreeStatistics.PATH_LENGTH, TreeStatistics.N_BRANCH_POINTS, //
			TreeStatistics.PATH_ORDER, TreeStatistics.PATH_MEAN_RADIUS, //
	})
	private String yAxisMetric2;

	@Parameter(label = "Y-axis Metric 3", choices = { " - None -", //
			TreeStatistics.PATH_LENGTH, TreeStatistics.N_BRANCH_POINTS, //
			TreeStatistics.PATH_ORDER, TreeStatistics.PATH_MEAN_RADIUS, //
	})
	private String yAxisMetric3;

	@Parameter(label = "Y-axis Metric 4", choices = { " - None -", //
			TreeStatistics.PATH_LENGTH, TreeStatistics.N_BRANCH_POINTS, //
			TreeStatistics.PATH_ORDER, TreeStatistics.PATH_MEAN_RADIUS, //
	})
	private String yAxisMetric4;

	@Parameter(label = "Output", choices = { "Plot", "Table", "Plot and Table" })
	private String outputChoice;

	@Parameter(required = true)
	private Collection<Path> paths;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
	}

	@Override
	public void run() {

		final List<Double> xValues = new ArrayList<>(paths.size());
		final List<Double> y1Values = new ArrayList<>(paths.size());
		final List<Double> y2Values = (yAxisMetric2.contains("None")) ? null : new ArrayList<>(paths.size());
		final List<Double> y3Values = (yAxisMetric3.contains("None")) ? null : new ArrayList<>(paths.size());
		final List<Double> y4Values = (yAxisMetric4.contains("None")) ? null : new ArrayList<>(paths.size());
		paths.forEach(p-> {
			final PathAnalyzer pa = new PathAnalyzer(Collections.singletonList(p), p.getName());
			xValues.add(pa.getMetric(xAxisMetric).doubleValue());
			y1Values.add(pa.getMetric(yAxisMetric1).doubleValue());
			if (y2Values != null)
				y2Values.add(pa.getMetric(yAxisMetric2).doubleValue());
			if (y3Values != null)
				y3Values.add(pa.getMetric(yAxisMetric3).doubleValue());
			if (y4Values != null)
				y4Values.add(pa.getMetric(yAxisMetric4).doubleValue());
		});

		if (outputChoice.toLowerCase().contains("plot")) {
			final XYPlot plot = plotService.newXYPlot();
			plot.xAxis().setLabel(xAxisMetric);
			plot.xAxis().setLabel("");
			addSeries(plot, yAxisMetric1, xValues, y1Values, Colors.BLACK);
			final ColorRGB[] uniqueColors = SNTColor.getDistinctColors(3);
			int colorCounter = 0;
			addSeries(plot, yAxisMetric2, xValues, y2Values, uniqueColors[colorCounter++]);
			addSeries(plot, yAxisMetric3, xValues, y3Values, uniqueColors[colorCounter++]);
			addSeries(plot, yAxisMetric4, xValues, y4Values, uniqueColors[colorCounter++]);
			new SNTChart("SNT: Density Profile", plot).show();
		}

		if (outputChoice.toLowerCase().contains("table")) {
			final SNTTable table = new SNTTable();
			addColumn(table, xAxisMetric, xValues);
			addColumn(table, yAxisMetric1, y1Values);
			addColumn(table, yAxisMetric2, y2Values);
			addColumn(table, yAxisMetric3, y3Values);
			addColumn(table, yAxisMetric4, y4Values);
			uiService.show("SNT_DensityProfile.csv", table);
		}

	}

	private void addSeries(final XYPlot plot, final String label, final List<Double> x, final List<Double> y,
			final ColorRGB color) {
		if (y != null) {
			final XYSeries series = plot.addXYSeries();
			series.setStyle(plot.newSeriesStyle(color, LineStyle.SOLID, MarkerStyle.FILLEDCIRCLE));
			series.setValues(x, y);
			series.setLabel(label);
		}
	}

	private void addColumn(final SNTTable table,  final String header, final List<Double> data) {
		if (data != null) {
			final DefaultColumn<Double> col = new DefaultColumn<Double>(Double.class, header);
			col.addAll(data);
			table.add(col);
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(PathSpineAnalysisCmd.class, true, input);
	}
}
