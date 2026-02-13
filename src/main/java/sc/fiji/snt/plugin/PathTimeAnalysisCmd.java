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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYSeries;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.PathStatistics;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.growth.GrowthAnalyzer;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Command for obtaining 'time-profiles' of Paths (time-lapse analysis).
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Time Profile Analysis...", initializer = "init")
public class PathTimeAnalysisCmd extends CommonDynamicCmd {

	private static final String TAG_REGEX_PATTERN = "("+ GrowthAnalyzer.TAG_REGEX_PATTERN + ")";
	private static final String TAG_REGEX_PATTERN_LEGACY = "("+ PathMatcherCmd.TAG_REGEX_PATTERN_LEGACY + ")";

	@Parameter
	private PlotService plotService;

	@Parameter(label = "Metric", choices = { //
			MultiTreeStatistics.LENGTH, //
			TreeStatistics.PATH_LENGTH + " (mean ± SD)", //
			TreeStatistics.PATH_EXT_ANGLE_XY + " (mean ± SD)", //
			TreeStatistics.PATH_EXT_ANGLE_XZ + " (mean ± SD)", //
			TreeStatistics.PATH_EXT_ANGLE_ZY + " (mean ± SD)", //
			MultiTreeStatistics.N_BRANCH_POINTS, //
			MultiTreeStatistics.HIGHEST_PATH_ORDER, //
			MultiTreeStatistics.PATH_MEAN_RADIUS + " (mean ± SD)", //
			MultiTreeStatistics.N_PATHS, //
	})
	private String measurementChoice;

	@Parameter(label = "Grouping Strategy", choices = { "No grouping", "Individual neurite(s) across time", "Individual neurite(s) across time (≥2 time-points)"})
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
		if (scopeChoice.toLowerCase().startsWith("individual")) {
			runMatchedAnalysis(scopeChoice.toLowerCase().contains("2"));
		} else {
			runNonMatchedAnalysis();
		}
		resetUI();
	}

	private Map<Integer, List<Path>> getPathListMap() {
		final TreeMap<Integer, List<Path>> map = new TreeMap<>();
		for (final Path p : paths) {
			List<Path> list = map.computeIfAbsent(p.getFrame(), k -> new ArrayList<>());
			list.add(p);
		}
		return map;
	}

	private void runNonMatchedAnalysis() {
		final Map<Integer, List<Path>> map = getPathListMap();
		if (map.size() < 2) {
			error("Selected Paths seem to be all asociated with the same time-point. "
				+ "Make sure to select paths associated with at least two time-points (frames).");
			return;
		}
		final ArrayList<Double> xValues = new ArrayList<>(map.size());
		final ArrayList<Double> yValues = new ArrayList<>(map.size());
		final boolean includeSDevSeries = measurementChoice.contains(" (mean");
		ArrayList<Double> lowerStdDevValues = (includeSDevSeries) ?  new ArrayList<>(map.size()) : null;
		ArrayList<Double> upperStdDevValues = (includeSDevSeries) ?  new ArrayList<>(map.size()) : null;
		final String metric = getMasurementChoiceMetric();
		map.forEach((frame, list) -> {
			final PathStatistics pa = new PathStatistics(list, String.valueOf(frame));
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
			series.setStyle(plotService.newSeriesStyle(Colors.BLACK, LineStyle.SOLID, MarkerStyle.FILLEDCIRCLE));
			series.setValues(xValues, yValues);
			if (includeSDevSeries) {
				series.setLabel("Mean ± SD");
				series.setLegendVisible(true);
				final XYSeries upper = plot.addXYSeries();
				upper.setLabel("Mean+SD");
				upper.setLegendVisible(false);
				upper.setStyle(plotService.newSeriesStyle(Colors.DARKGRAY, LineStyle.DASH, MarkerStyle.NONE));
				upper.setValues(xValues, upperStdDevValues);
				final XYSeries lower = plot.addXYSeries();
				lower.setLabel("Mean-SD");
				lower.setLegendVisible(false);
				lower.setStyle(plotService.newSeriesStyle(Colors.DARKGRAY, LineStyle.DASH, MarkerStyle.NONE));
				lower.setValues(xValues, lowerStdDevValues);
			} else {
				series.setLabel("Un-matched paths");
				series.setLegendVisible(false);
			}
			new SNTChart("SNT: Time Profile", plot).show();
		}

		if (outputChoice.toLowerCase().contains("table")) {
			final SNTTable table = new SNTTable();
			final DefaultColumn<Double> xcol = new DefaultColumn<>(Double.class, "Frame");
			xcol.addAll(xValues);
			table.add(xcol);
			if (includeSDevSeries) {
				final String label = getMasurementChoiceMetric();
				final DefaultColumn<Double> mean = new DefaultColumn<>(Double.class, label + " Mean");
				mean.addAll(upperStdDevValues);
				table.add(mean);
				final DefaultColumn<Double> upperErr = new DefaultColumn<>(Double.class, label + " Mean+SD");
				upperErr.addAll(upperStdDevValues);
				table.add(upperErr);
				final DefaultColumn<Double> lowerErr = new DefaultColumn<>(Double.class, label + " Mean-SD");
				lowerErr.addAll(lowerStdDevValues);
				table.add(lowerErr);
			} else {
				final DefaultColumn<Double> ycol = new DefaultColumn<>(Double.class, measurementChoice);
				ycol.addAll(yValues);
				table.add(ycol);
			}
			table.setTitle("SNT_TimeProfile");
			table.show();
		}
	}

	private XYPlot getPlot() {
		final XYPlot plot = plotService.newXYPlot();
		plot.xAxis().setLabel("Time (frame no.)");
		plot.yAxis().setLabel(getMasurementChoiceMetric());
		return plot;
	}

	private void runMatchedAnalysis(final boolean ignoreSinglePoints) {
		TreeMap<String, TreeMap<Integer, Path>> map = getMatches(Pattern.compile(TAG_REGEX_PATTERN));
		if (map.isEmpty()) {
			// maybe this is some old data from v<4.3?
			map = getMatches(Pattern.compile(TAG_REGEX_PATTERN_LEGACY));
		}
		if (map.isEmpty()) {
			error("No matched paths found. Please run \"Match Paths Across Time...\" or "
					+ "assign groups manually using 'neurite #' tags.");
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
						plotService.newSeriesStyle(uniqueColors[colorCounter[0]++], LineStyle.SOLID, MarkerStyle.CIRCLE));
				final ArrayList<Double> xValues = new ArrayList<>(groupMap.size());
				final ArrayList<Double> yValues = new ArrayList<>(groupMap.size());
				groupMap.forEach((frame, path) -> {
					final PathStatistics pa = new PathStatistics(Collections.singletonList(path), String.valueOf(frame));
					yValues.add(pa.getMetric(metric).doubleValue());
					xValues.add((double) frame);
				});
				series.setValues(xValues, yValues);
				if (allSeries != null) allSeries.add(series);
			});
			new SNTChart("SNT: Time Profile", plot).show();
			if (allSeries != null) {
				final SNTTable table = new SNTTable();
				for (final XYSeries series : allSeries) {
					for (int i = 0; i < series.getXValues().size(); i++) {
						final int row = table.insertRow(series.getLabel());
						table.set("Frame", row, series.getXValues().get(i));
						table.set(metric, row, series.getYValues().get(i));
					}
				}
				table.setTitle("SNT_TimeProfile");
				table.show();
			}
		}
	}

	private TreeMap<String, TreeMap<Integer, Path>> getMatches(final Pattern pattern) {
		final TreeMap<String, TreeMap<Integer, Path>> map = new TreeMap<>(new NumberAwareComparator());
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
		return map;
	}

	//https://stackoverflow.com/a/58249974
	private static class NumberAwareComparator implements Comparator<String>
	{
		@Override
		public int compare(final String s1, final String s2) {
			final int len1 = s1.length();
			final int len2 = s2.length();
			int i1 = 0;
			int i2 = 0;
			while (true) {
				// handle the case when one string is longer than another
				if (i1 == len1)
					return i2 == len2 ? 0 : -1;
				if (i2 == len2)
					return 1;

				final char ch1 = s1.charAt(i1);
				final char ch2 = s2.charAt(i2);
				if (Character.isDigit(ch1) && Character.isDigit(ch2)) {
					// skip leading zeros
					while (i1 < len1 && s1.charAt(i1) == '0')
						i1++;
					while (i2 < len2 && s2.charAt(i2) == '0')
						i2++;

					// find the ends of the numbers
					int end1 = i1;
					int end2 = i2;
					while (end1 < len1 && Character.isDigit(s1.charAt(end1)))
						end1++;
					while (end2 < len2 && Character.isDigit(s2.charAt(end2)))
						end2++;

					final int diglen1 = end1 - i1;
					final int diglen2 = end2 - i2;

					// if the lengths are different, then the longer number is bigger
					if (diglen1 != diglen2)
						return diglen1 - diglen2;

					// compare numbers digit by digit
					while (i1 < end1) {
						if (s1.charAt(i1) != s2.charAt(i2))
							return s1.charAt(i1) - s2.charAt(i2);
						i1++;
						i2++;
					}
				} else {
					// plain characters comparison
					if (ch1 != ch2)
						return ch1 - ch2;
					i1++;
					i2++;
				}
			}
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		GuiUtils.setLookAndFeel();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(PathTimeAnalysisCmd.class, true, input);
	}
}
