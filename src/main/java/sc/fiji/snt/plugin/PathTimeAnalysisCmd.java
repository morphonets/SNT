/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
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
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.PathAnalyzer;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Command for obtaining 'time-profiles' of Paths (time-lapse analysis).
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Time Profile Analysis...", initializer = "init")
public class PathTimeAnalysisCmd extends CommonDynamicCmd {

	private static final String TAG_REGEX_PATTERN = "("+ PathMatcherCmd.TAG_REGEX_PATTERN + ")";

	@Parameter
	private PlotService plotService;

	@Parameter(label = "Metric", choices = { //
			MultiTreeStatistics.LENGTH, //
			TreeStatistics.PATH_LENGTH + " (mean ± SD)", //
			MultiTreeStatistics.N_BRANCH_POINTS, //
			MultiTreeStatistics.HIGHEST_PATH_ORDER, //
			MultiTreeStatistics.MEAN_RADIUS + " (mean ± SD)", //
			MultiTreeStatistics.N_PATHS, //
	})
	private String measurementChoice;

	@Parameter(label = "Grouping Strategy", choices = { "No grouping", "Matched path(s) across time", "Matched path(s) across time (≥2 time-points)"})
	private String scopeChoice;

	@Parameter(label = "Output", choices = { "Plot", "Table", "Plot and Table" })
	private String outputChoice;

	@Parameter(required = true)
	private Collection<Path> paths;


	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
	}

	private String getMasurementChoiceMetric() {
		final int idx = measurementChoice.indexOf(" (mean");
		if (idx > -1) return measurementChoice.substring(0, idx);
		return measurementChoice;
	}

	@Override
	public void run() {
		if (scopeChoice.toLowerCase().startsWith("match")) {
			runMatchedAnalysis(scopeChoice.toLowerCase().contains("2"));
		} else {
			runNonMatchedAnalysis();
		}
	}

	private Map<Integer, List<Path>> getPathListMap() {
		final TreeMap<Integer, List<Path>> map = new TreeMap<>();
		for (final Path p : paths) {
			List<Path> list = map.get(p.getFrame());
			if (list == null) {
				list = new ArrayList<>();
				map.put(p.getFrame(), list);
			}
			list.add(p);
		}
		return map;
	}

	private void runNonMatchedAnalysis() {
		final Map<Integer, List<Path>> map = getPathListMap();
		final ArrayList<Double> xValues = new ArrayList<>(map.size());
		final ArrayList<Double> yValues = new ArrayList<>(map.size());
		final boolean includeSDevSeries = measurementChoice.contains(" (mean");
		ArrayList<Double> lowerStdDevValues = (includeSDevSeries) ?  new ArrayList<>(map.size()) : null;
		ArrayList<Double> upperStdDevValues = (includeSDevSeries) ?  new ArrayList<>(map.size()) : null;
		final String metric = getMasurementChoiceMetric();
		map.forEach((frame, list) -> {
			final PathAnalyzer pa = new PathAnalyzer(list, String.valueOf(frame));
			xValues.add((double) frame);
			if (includeSDevSeries) {
				SummaryStatistics stats = pa.getSummaryStats(metric);
				final double mean = stats.getMean();
				final double sd = stats.getStandardDeviation();
				yValues.add(mean);
				lowerStdDevValues.add(mean - sd);
				upperStdDevValues.add(mean + sd);
			} else {
				yValues.add(pa.getMetric(metric).doubleValue());
			}
		});

		if (outputChoice.toLowerCase().contains("plot")) {
			final XYPlot plot = getPlot();
			final XYSeries series = plot.addXYSeries();
			series.setStyle(plot.newSeriesStyle(Colors.BLACK, LineStyle.SOLID, MarkerStyle.FILLEDCIRCLE));
			series.setValues(xValues, yValues);
			if (includeSDevSeries) {
				series.setLabel("Mean ± SD");
				series.setLegendVisible(true);
				final XYSeries upper = plot.addXYSeries();
				upper.setLabel("Mean+SD");
				upper.setLegendVisible(false);
				upper.setStyle(plot.newSeriesStyle(Colors.DARKGRAY, LineStyle.DASH, MarkerStyle.NONE));
				upper.setValues(xValues, upperStdDevValues);
				final XYSeries lower = plot.addXYSeries();
				lower.setLabel("Mean-SD");
				lower.setLegendVisible(false);
				lower.setStyle(plot.newSeriesStyle(Colors.DARKGRAY, LineStyle.DASH, MarkerStyle.NONE));
				lower.setValues(xValues, lowerStdDevValues);
			} else {
				series.setLabel("Un-matched paths");
				series.setLegendVisible(false);
			}
			uiService.show("SNT: Time Profile", plot);
		}

		if (outputChoice.toLowerCase().contains("table")) {
			final SNTTable table = new SNTTable();
			final DefaultColumn<Double> xcol = new DefaultColumn<Double>(Double.class, "Frame");
			xcol.addAll(xValues);
			table.add(xcol);
			if (includeSDevSeries) {
				final String label = getMasurementChoiceMetric();
				final DefaultColumn<Double> mean = new DefaultColumn<Double>(Double.class, label + " Mean");
				mean.addAll(upperStdDevValues);
				table.add(mean);
				final DefaultColumn<Double> upperErr = new DefaultColumn<Double>(Double.class, label + " Mean+SD");
				upperErr.addAll(upperStdDevValues);
				table.add(upperErr);
				final DefaultColumn<Double> lowerErr = new DefaultColumn<Double>(Double.class, label + " Mean-SD");
				lowerErr.addAll(lowerStdDevValues);
				table.add(lowerErr);
			} else {
				final DefaultColumn<Double> ycol = new DefaultColumn<Double>(Double.class, measurementChoice);
				ycol.addAll(yValues);
				table.add(ycol);
			}
			uiService.show("SNT_TimeProfile.csv", table);
		}
	}

	private XYPlot getPlot() {
		final XYPlot plot = plotService.newXYPlot();
		plot.xAxis().setLabel("Time (frame no.)");
		plot.yAxis().setLabel(getMasurementChoiceMetric());
		return plot;
	}

	private void runMatchedAnalysis(final boolean ignoreSinglePoints) {
		final Pattern pattern = Pattern.compile(TAG_REGEX_PATTERN);
		final TreeMap<String, TreeMap<Integer, Path>> map = new TreeMap<>();
		for (final Path p : paths) {
			final Matcher matcher = pattern.matcher(p.getName());
			if (matcher.find()) {
				final String groupID = matcher.group(1);
				TreeMap<Integer, Path> groupMap = map.get(groupID);
				if (groupMap == null) {
					groupMap = new TreeMap<>();
				}
				groupMap.put(p.getFrame(), p);
				map.put(groupID, groupMap);
			}
		}
		if (map.isEmpty()) {
			error("No matched paths found. Please run \"Match Paths Across Time...\" or "
				+ "assign groups manually using \"Group #\" tags.");
			return;
		}
		if (ignoreSinglePoints) {
			map.entrySet().removeIf(entry->entry.getValue().size() < 2);
		}
		final List<XYSeries> allSeries = (outputChoice.toLowerCase().contains("table")) ? new ArrayList<>() : null;
		if (outputChoice.toLowerCase().contains("plot")) {
			final XYPlot plot = getPlot();
			final ColorRGB[] uniqueColors = SNTColor.getDistinctColors(map.size());
			final int[] colorCounter = { 0 };
			final String metric = getMasurementChoiceMetric();
			map.forEach((groupID, groupMap) -> {
				final XYSeries series = plot.addXYSeries();
				series.setLabel(groupID.substring(1, groupID.length()-1)); // group ID without curly braces
				series.setLegendVisible(true);
				series.setStyle(
						plot.newSeriesStyle(uniqueColors[colorCounter[0]++], LineStyle.SOLID, MarkerStyle.CIRCLE));
				final ArrayList<Double> xValues = new ArrayList<>(groupMap.size());
				final ArrayList<Double> yValues = new ArrayList<>(groupMap.size());
				groupMap.forEach((frame, path) -> {
					final PathAnalyzer pa = new PathAnalyzer(Collections.singletonList(path), String.valueOf(frame));
					yValues.add(pa.getMetric(metric).doubleValue());
					xValues.add((double) frame);
				});
				series.setValues(xValues, yValues);
				if (allSeries != null) allSeries.add(series);
			});
			uiService.show("SNT: Time Profile", plot);
			if (allSeries != null) {
				final SNTTable table = new SNTTable();
				for (final XYSeries series : allSeries) {
					int initialRow = Math.max(0, table.getRowCount() - 1);
					for (final double x : series.getXValues()) {
						final int row = table.insertRow(series.getLabel());
						table.set("Frame", row, x);
					}
					for (final double y : series.getYValues()) {
						table.set(metric, initialRow++, y);
					}
				}
				uiService.show("SNT_TimeProfile.csv", table);
			}
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(PathTimeAnalysisCmd.class, true, input);
	}
}
