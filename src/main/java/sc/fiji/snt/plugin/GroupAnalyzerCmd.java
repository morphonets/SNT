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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.swing.SwingUtilities;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.FileWidget;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.GroupedTreeStatistics;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.MultiViewer2D;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Command for Comparing Groups of Tree(s). Implements the "Compare Groups..."
 * and "Import & Compare Groups..."
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label="Compare Groups...", initializer = "init")
public class GroupAnalyzerCmd extends CommonDynamicCmd {

	private static final String COMMON_DESC_PRE = "<HTML><div WIDTH=500>Path to directory containing Group ";
	private static final String COMMON_DESC_POST = " reconstructions. Ignored if empty. NB: A single file "
			+ "can also be specified but the \"Browser\" prompt may not allow single files to be selected. "
			+ "In that case, you can manually specify its path in the text field.";

	// I: Input options
	@Parameter(label = "<HTML><b>Groups:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER1;

	@Parameter(label = "Group 1 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 1 + COMMON_DESC_POST)
	private File g1File;
	@Parameter(label = "Group 2 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 2 + COMMON_DESC_POST)
	private File g2File;

	@Parameter(label = "Group 3 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 3 + COMMON_DESC_POST)
	private File g3File;

	@Parameter(label = "Group 4 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 4 + COMMON_DESC_POST)
	private File g4File;

	@Parameter(label = "Group 5 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 5 + COMMON_DESC_POST)
	private File g5File;

	@Parameter(label = "Group 6 directory", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 6 + COMMON_DESC_POST)
	private File g6File;

	@Parameter(label = "<HTML>&nbsp;<br><b>Options:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER3;

	@Parameter(label = "Compartments", choices = {"All", "Dendrites", "Axon"})
	private String scope;

	// II. Metrics
	@Parameter(label = "<HTML>&nbsp;<br><b>Measurements:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(label = "Metric", callback ="metricChanged")
	private String metric;

	@Parameter(required = false, callback = "displayPlotsChanged", label = "Comparison plots",
			description = "Display Histograms and Box-Plots? (Ignored if not metric is specified)")
	private boolean displayPlots;

	@Parameter(required = false, callback = "displayInMultiViewerChanged", label = "Montage of mapped metric",
			description = "<HTML><div WIDTH=500>Assemble multiple-panel figure from group exemplars? "
					+ "This option is only considered if chosen metric can be mapped to a LUT.")
	private boolean displayInMultiViewer;

	@Parameter(required = false, label = "3D Scene", description = "Display groups on a dedicated instance of Reconstruction Viewer?")
	private boolean displayInRecViewer;

	@Parameter(type = ItemIO.OUTPUT, label = "Group Statistics")
	private String report;

	@Parameter(required = false)
	private Viewer3D recViewer;

	private int inputGroupsCounter;
	private boolean noMetrics;


	@SuppressWarnings("unused")
	private void init() {
		final List<String> mChoices = TreeStatistics.getMetrics("common");
		mChoices.add(0, "None. Skip measurements");
		final MutableModuleItem<String> mInput = getInfo().getMutableInput("metric", String.class);
		mInput.setChoices(mChoices);
		metricChanged();
		if (recViewer != null) {
			// then this is Reconstruction Viewer's Load & Compare Command
			displayInRecViewer = true;
			resolveInput("displayInRecViewer");
			getInfo().setLabel("Load & Compare Groups of Cells");
		}
	}

	@SuppressWarnings("unused")
	private void displayPlotsChanged() {
		noMetrics = metric != null && metric.startsWith("None");
		if (noMetrics) {
			GuiUtils.errorPrompt("This option is only available when a metric is specified");
			displayPlots = false;
		}
	}

	@SuppressWarnings("unused")
	private void displayInMultiViewerChanged() {
		noMetrics = metric != null && metric.startsWith("None");
		if (noMetrics || !isMetricMappable(metric)) {
			GuiUtils.errorPrompt("This option is only available when a mappable metric is specified (e.g., '"
					+ MultiTreeStatistics.LENGTH + "').");
			displayInMultiViewer = false;
		}
	}

	private void metricChanged() {
		noMetrics = metric == null || metric.startsWith("None");
		if (noMetrics) {
			displayInMultiViewer = false;
			displayPlots = false;
		} else {
			displayInMultiViewer = displayInMultiViewer && isMetricMappable(metric);
		}
	}

	@Override
	public void run() {

		if (!atLeastOneValidGroup()) {
			error("None of the specified directories is valid.");
			return;
		}

		metricChanged();

		notifyLoadingStart(recViewer);

		final GroupedTreeStatistics stats = new GroupedTreeStatistics();
		inputGroupsCounter = 0;
		addGroup(stats, g1File, "Group 1");
		addGroup(stats, g2File, "Group 2");
		addGroup(stats, g3File, "Group 3");
		addGroup(stats, g4File, "Group 4");
		addGroup(stats, g5File, "Group 5");
		addGroup(stats, g6File, "Group 6");

		if (stats.getGroups().size() == 0) {
			resetUI(false);
			error("No matching reconstruction(s) could be retrieved from the specified path(s). " + warningOnCompartmentTags());
			return;
		}
		if (noMetrics) {
			super.resolveOutput("report");

		} else {

			stats.setMinNBins(3);
			final SNTChart histFrame = (TreeStatistics.REMOTE_BIF_ANGLES.equals(metric))
					? stats.getPolarHistogram(metric)
					: stats.getHistogram(metric);
			final SNTChart boxFrame = stats.getBoxPlot(metric);

			long[] largestN = {Integer.MIN_VALUE};
			final StringBuilder reportBuilder = new StringBuilder("    ").append(metric).append(" Statistics:\r\n");
			final SummaryStatistics uberStats = new SummaryStatistics();
			stats.getGroups().forEach(group -> {
				final DescriptiveStatistics dStats = stats.getGroupStats(group).getDescriptiveStats(metric);
				final long n = dStats.getN();
				if (n > largestN[0]) largestN[0] = n;
				reportBuilder.append(group).append(" Statistics:");
				reportBuilder.append("\r\nFolder:\t").append(getDirPath(group));
				reportBuilder.append("\r\nN:\t").append(n);
				reportBuilder.append("\r\nMean:\t").append(dStats.getMean());
				reportBuilder.append("\r\nStDev:\t").append(dStats.getStandardDeviation());
				reportBuilder.append("\r\nMedian:\t").append(dStats.getPercentile(50));
				reportBuilder.append("\r\nQ1:\t").append(dStats.getPercentile(25));
				reportBuilder.append("\r\nQ3:\t").append(dStats.getPercentile(75));
				reportBuilder.append("\r\nMin:\t").append(dStats.getMin());
				reportBuilder.append("\r\nMax:\t").append(dStats.getMax());
				reportBuilder.append("\r\n\r\n");
				// map to 10-90 percentiles
				uberStats.addValue(dStats.getPercentile(10));
				uberStats.addValue(dStats.getPercentile(90));
			});
			report = reportBuilder.toString();
			if (displayPlots) {
				final List<SNTChart> charts = new ArrayList<>();
				charts.add(histFrame);
				charts.add(boxFrame);
				final SNTChart cChart = SNTChart.combine(charts);
				cChart.setTitle("Comparison Charts");
				cChart.show();
			}
			final boolean mappableMetric = isMetricMappable(metric);
			if (displayInMultiViewer && mappableMetric) {
				stats.getGroups().forEach(group -> displayGroup(stats, group, uberStats.getMin(), uberStats.getMax()));
			}
			final StringBuilder exitMsg = new StringBuilder("<HTML>");
			if (inputGroupsCounter != stats.getGroups().size()) {
				exitMsg.append("<p>Some directories (").append(inputGroupsCounter - stats.getGroups().size());
				exitMsg.append(") did not contain matching data and were skipped.</p>");
				exitMsg.append("<p>").append(warningOnCompartmentTags()).append("</p>");
			}
			if (displayInMultiViewer && largestN[0] > 10) {
				exitMsg.append((mappableMetric) ? "<p>NB: Only the first 10 cells of each group were mapped.</p>"
						: "<p>NB: Metric is not mappable. Choice ignored.</p>");
			}
			if (exitMsg.length() > 10) {
				GuiUtils.errorPrompt(exitMsg.toString());
			}
		}
		if (displayInRecViewer) {
			boolean recViewerIsNotVisible = false;
			if (recViewer == null) {
				recViewer = sntService.newRecViewer(true);
				recViewerIsNotVisible = true;
			}
			recViewer.setSceneUpdatesEnabled(false);
			final ColorRGB[] colors = SNTColor.getDistinctColors(stats.getGroups().size());
			final Iterator<String> groupsIterator = stats.getGroups().iterator();
			int index = 0;
			while (groupsIterator.hasNext()) {
				final String groupLabel = groupsIterator.next();
				recViewer.addTrees(stats.getGroupStats(groupLabel).getGroup(), colors[index].toHTMLColor(), groupLabel);
				index++;
			}
			recViewer.setSceneUpdatesEnabled(true);
			recViewer.updateView();
			if (recViewerIsNotVisible) recViewer.show();
		}
		resetUI(recViewer);

	}

	private String warningOnCompartmentTags() {
		return ("All".equals(scope)) ? ""
				: " Perhaps branches have not been tagged as dendrites/axons? If this is the case, "
						+ "you can re-run the analysis using 'All' as compartment choice.";
	}

	private String getDirPath(final String groupLabel) {
		switch(groupLabel.substring(groupLabel.length() - 1)) {
			case "1":
				return (validFile(g1File)) ? g1File.getAbsolutePath() : "N/A";
			case "2":
				return (validFile(g2File)) ? g2File.getAbsolutePath() : "N/A";
			case "3":
				return (validFile(g3File)) ? g3File.getAbsolutePath() : "N/A";
			case "4":
				return (validFile(g4File)) ? g4File.getAbsolutePath() : "N/A";
			default:
				return "N/A";
		}
	}

	private void displayGroup(final GroupedTreeStatistics stats, String group, final double min, final double max) {
		final List<Tree> trees = new ArrayList<>(stats.getGroupStats(group).getGroup()).subList(0,
				Math.min(11, stats.getGroupStats(group).getGroup().size()));
		final MultiTreeColorMapper cm = new MultiTreeColorMapper(trees);
		cm.setMinMax(min, max);
		cm.map(metric, ColorTables.ICE);
		final MultiViewer2D viewer = cm.getMultiViewer();
		viewer.setGridlinesVisible(false);
		viewer.setLabel(group);
		SwingUtilities.invokeLater(() -> viewer.show());
	}

	final boolean isMetricMappable(final String metric) {
		return Arrays.asList(MultiTreeColorMapper.PROPERTIES).contains(metric);
	}

	private boolean atLeastOneValidGroup() {
		return validFile(g1File) || validFile(g2File) || validFile(g3File)
				|| validFile(g4File) || validFile(g5File) || validFile(g6File);
	}

	private boolean addGroup(final GroupedTreeStatistics stats, final File file, final String label) {
		if (!validFile(file)) return false;
		inputGroupsCounter++;
		final List<Tree> trees = Tree.listFromDir(file.getAbsolutePath(), ("all".equalsIgnoreCase(scope)) ? null : scope);
		if (trees == null || trees.isEmpty()) return false;
		stats.addGroup(trees, label);
		return true;
	}

	private boolean validFile(final File file) {
		return file != null && file.isDirectory() && file.exists() && file.canRead();
	}


	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		GuiUtils.setLookAndFeel();
		ij.ui().showUI();
		ij.command().run(GroupAnalyzerCmd.class, true);
	}
}
