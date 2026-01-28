/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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
import java.util.Arrays;
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

import net.imagej.ImageJ;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYSeries;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathStatistics;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Command for obtaining plots pertaining to spine/varicosity counts
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Spine/Varicosity Density Profile...", initializer = "init")
public class PathSpineAnalysisCmd extends CommonDynamicCmd {

	private static final String NONE_OPTION = " - None -";
	@Parameter
	private PlotService plotService;

	@Parameter(label = "X-Axis Metric", choices = { "Path ID", //
			PathStatistics.PATH_CHANNEL, PathStatistics.PATH_FRAME, //
			PathStatistics.N_BRANCH_POINTS, PathStatistics.PATH_CONTRACTION, //
			PathStatistics.PATH_EXT_ANGLE, PathStatistics.PATH_EXT_ANGLE_REL, //
			PathStatistics.PATH_EXT_ANGLE_XY, PathStatistics.PATH_EXT_ANGLE_XZ, PathStatistics.PATH_EXT_ANGLE_ZY,//
			PathStatistics.PATH_LENGTH, PathStatistics.PATH_MEAN_RADIUS, //
			PathStatistics.PATH_ORDER, PathStatistics.PATH_SURFACE_AREA, //
			PathStatistics.N_PATH_NODES, PathStatistics.PATH_VOLUME })
	private String xAxisMetric;

	@Parameter(label = "Y-Axis Metric 1", choices = { //
			PathStatistics.PATH_SPINE_DENSITY, PathStatistics.N_SPINES //
	})
	private String yAxisMetric1;

	@Parameter(label = "Y-axis Metric 2", required = false, choices = { NONE_OPTION, //
			PathStatistics.PATH_SPINE_DENSITY, PathStatistics.N_SPINES //
	})
	private String yAxisMetric2;

	@Parameter(label = "Y-axis Metric 3", required = false)
	private String yAxisMetric3;

	@Parameter(label = "Y-axis Metric 4", required = false)
	private String yAxisMetric4;

	@Parameter(label = "Output", choices = { "Plot", "Table", "Plot and Table" })
	private String outputChoice;

	@Parameter(required = true)
	private Collection<Path> paths;

	@Parameter(required = false)
	private boolean anyMetric;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		getInfo().setLabel("Multimetric Plot...");
		if (anyMetric) {
			final List<String> metrics = Arrays.asList(NONE_OPTION, "Path ID", //
					PathStatistics.PATH_CHANNEL, PathStatistics.PATH_FRAME, //
					PathStatistics.PATH_CONTRACTION, PathStatistics.PATH_LENGTH, //
					PathStatistics.PATH_EXT_ANGLE, PathStatistics.PATH_EXT_ANGLE_REL, //
					PathStatistics.PATH_EXT_ANGLE_XY, PathStatistics.PATH_EXT_ANGLE_XZ, PathStatistics.PATH_EXT_ANGLE_ZY, //
					PathStatistics.PATH_ORDER, PathStatistics.PATH_N_SPINES, PathStatistics.PATH_SPINE_DENSITY, //
					PathStatistics.N_BRANCH_POINTS, PathStatistics.PATH_MEAN_RADIUS, //
					PathStatistics.PATH_SURFACE_AREA, PathStatistics.N_PATH_NODES, PathStatistics.PATH_VOLUME);
			Collections.sort(metrics);
			Arrays.asList("xAxisMetric", "yAxisMetric1", "yAxisMetric2", "yAxisMetric3", "yAxisMetric4").forEach(m -> {
				getInfo().getMutableInput(m, String.class).setChoices(metrics);
			});
		} else {
			resolveInput("yAxisMetric3");
			resolveInput("yAxisMetric4");
			yAxisMetric3 = NONE_OPTION;
			yAxisMetric4 = NONE_OPTION;
		}
	}

	@Override
	public void run() {

		final List<Double> xValues = (NONE_OPTION.equals(xAxisMetric)) ? null : new ArrayList<>(paths.size());
		final List<Double> y1Values = (NONE_OPTION.equals(yAxisMetric1)) ? null : new ArrayList<>(paths.size());
		final List<Double> y2Values = (NONE_OPTION.equals(yAxisMetric2)) ? null : new ArrayList<>(paths.size());
		final List<Double> y3Values = (NONE_OPTION.equals(yAxisMetric3)) ? null : new ArrayList<>(paths.size());
		final List<Double> y4Values = (NONE_OPTION.equals(yAxisMetric4)) ? null : new ArrayList<>(paths.size());
		if (xValues == null || (y1Values == null && y2Values == null && y3Values == null && y4Values == null)) {
			error("At least one metric per axis must be chosen.");
			return;
		}
		paths.forEach(p -> {
			final PathStatistics pa = new PathStatistics(p);
			xValues.add(pa.getMetric(xAxisMetric, p).doubleValue());
			if (y1Values != null)
				y1Values.add(pa.getMetric(yAxisMetric1, p).doubleValue());
			if (y2Values != null)
				y2Values.add(pa.getMetric(yAxisMetric2, p).doubleValue());
			if (y3Values != null)
				y3Values.add(pa.getMetric(yAxisMetric3, p).doubleValue());
			if (y4Values != null)
				y4Values.add(pa.getMetric(yAxisMetric4, p).doubleValue());
		});

		if (outputChoice.toLowerCase().contains("plot")) {
			final XYPlot plot = plotService.newXYPlot();
			plot.xAxis().setLabel(xAxisMetric);
			plot.yAxis().setLabel("");
			final ColorRGB[] uniqueColors = SNTColor.getDistinctColors(4);
			int seriesCounter = 0;
			if (y1Values != null)
				addSeries(plot, yAxisMetric1, xValues, y1Values, uniqueColors[seriesCounter++]);
			if (y2Values != null)
				addSeries(plot, yAxisMetric2, xValues, y2Values, uniqueColors[seriesCounter++]);
			if (y3Values != null)
				addSeries(plot, yAxisMetric3, xValues, y3Values, uniqueColors[seriesCounter++]);
			if (y4Values != null)
				addSeries(plot, yAxisMetric4, xValues, y4Values, uniqueColors[seriesCounter++]);
			if (plot.getItems().size() == 1) {
				plot.yAxis().setLabel(plot.getItems().get(0).getLabel());
				plot.getItems().get(0).setLegendVisible(false);
			}
			new SNTChart((anyMetric) ? "SNT: MultiMetric Plot" : "SNT: Density Profile", plot).show();
		}

		if (outputChoice.toLowerCase().contains("table")) {
			final SNTTable table = new SNTTable();
			addColumn(table, xAxisMetric, xValues);
			addColumn(table, yAxisMetric1, y1Values);
			addColumn(table, yAxisMetric2, y2Values);
			addColumn(table, yAxisMetric3, y3Values);
			addColumn(table, yAxisMetric4, y4Values);
			uiService.show((anyMetric) ? "SNT_MultiMetrics.csv" : "SNT_DensityProfile.csv", table);
		}

	}

	private void addSeries(final XYPlot plot, final String label, final List<Double> x, final List<Double> y,
			final ColorRGB color) {
		if (y != null) {
			final XYSeries series = plot.addXYSeries();
			series.setStyle(plotService.newSeriesStyle(color, LineStyle.NONE, MarkerStyle.FILLEDCIRCLE));
			series.setValues(x, y);
			series.setLabel(label);
		}
	}

	private void addColumn(final SNTTable table, final String header, final List<Double> data) {
		if (data != null) {
			final DefaultColumn<Double> col = new DefaultColumn<>(Double.class, header);
			col.addAll(data);
			table.add(col);
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		// GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		input.put("anyMetric", false);
		ij.command().run(PathSpineAnalysisCmd.class, true, input);
	}
}
