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
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.GroupedTreeStatistics;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.SNTChart;
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

	@Parameter(label = "Group 1", style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 1 + COMMON_DESC_POST)
	private File g1File;
	@Parameter(label = "Group 2", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 2 + COMMON_DESC_POST)
	private File g2File;

	@Parameter(label = "Group 3", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 3 + COMMON_DESC_POST)
	private File g3File;

	@Parameter(label = "Group 4", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 4 + COMMON_DESC_POST)
	private File g4File;

	@Parameter(label = "Group 5", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 5 + COMMON_DESC_POST)
	private File g5File;

	@Parameter(label = "Group 6", required = false, style = FileWidget.DIRECTORY_STYLE, description = COMMON_DESC_PRE + 6 + COMMON_DESC_POST)
	private File g6File;

	@Parameter(label = "<HTML>&nbsp;<br><b>Options:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER3;

	@Parameter(label = "Compartments", choices = {"All", "Dendrites", "Axon"})
	private String scope;

	// II. Metrics
	@Parameter(label = "<HTML>&nbsp;<br><b>Metrics:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(label = "Metric", callback ="metricChanged", choices = {//
			MultiTreeStatistics.LENGTH,
			MultiTreeStatistics.TERMINAL_LENGTH,
			MultiTreeStatistics.PRIMARY_LENGTH,
			MultiTreeStatistics.INNER_LENGTH,
			MultiTreeStatistics.AVG_BRANCH_LENGTH,
			MultiTreeStatistics.AVG_CONTRACTION,
			MultiTreeStatistics.AVG_FRAGMENTATION,
			MultiTreeStatistics.AVG_REMOTE_ANGLE,
			MultiTreeStatistics.AVG_PARTITION_ASYMMETRY,
			MultiTreeStatistics.N_BRANCH_POINTS,
			MultiTreeStatistics.N_TIPS,
			MultiTreeStatistics.N_BRANCHES,
			MultiTreeStatistics.N_PRIMARY_BRANCHES,
			MultiTreeStatistics.N_INNER_BRANCHES,
			MultiTreeStatistics.N_TERMINAL_BRANCHES,
			MultiTreeStatistics.STRAHLER_NUMBER,
			MultiTreeStatistics.STRAHLER_RATIO,
			MultiTreeStatistics.WIDTH,
			MultiTreeStatistics.HEIGHT,
			MultiTreeStatistics.DEPTH,
			MultiTreeStatistics.MEAN_RADIUS
	})
	private String metric;

	@Parameter(label = "<HTML>&nbsp;<br><b>Output(s):", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER4;

	@Parameter(required = false, label = "Comparison plots", description = "Display Histograms & Box-Plots?")
	private boolean displayPlots;

	@Parameter(required = false, label = "Mapped metric montage", description = "<HTML><div WIDTH=500>Assemble multiple-panel figure "
			+ "from group exemplars? This option is only considered if chosen metric can be mapped to a LUT.")
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
		if (recViewer != null) {
			// then this is Reconstruction Viewer's Load & Compare Command
			displayInRecViewer = true;
			resolveInput("displayInRecViewer");
			getInfo().setLabel("Load & Compare Groups of Cells");
			List<String> mChoices = MultiTreeStatistics.getMetrics();
			mChoices.add(0, "None. Skip measurements");
			final MutableModuleItem<String> mInput = getInfo().getMutableInput("metric", String.class);
			mInput.setChoices(mChoices);
		}
	}

	@SuppressWarnings("unused")
	private void metricChanged() {
		noMetrics = metric.toLowerCase().contains("none");
		if (noMetrics) {
			displayInMultiViewer = false;
			displayPlots = false;
		} else {
			displayInMultiViewer = displayInMultiViewer && isMetricMappable(metric);
		}
	}

	@Override
	public void run() {

		final GroupedTreeStatistics stats = new GroupedTreeStatistics();
		inputGroupsCounter = 0;
		addGroup(stats, g1File, "Group 1");
		addGroup(stats, g2File, "Group 2");
		addGroup(stats, g3File, "Group 3");
		addGroup(stats, g4File, "Group 4");
		addGroup(stats, g5File, "Group 5");
		addGroup(stats, g6File, "Group 6");

		if (stats.getGroups().size() == 0) {
			cancel("No matching reconstruction(s) could be retrieved from the specified path(s).");
			return;
		}
		if (noMetrics) {
			super.resolveOutput("report");

		} else {
			final SNTChart histFrame = stats.getHistogram(metric);
			final SNTChart boxFrame = stats.getBoxPlot(metric);

			final StringBuilder reportBuilder = new StringBuilder("    ").append(metric).append(" Statistics:\r\n");
			final SummaryStatistics uberStats = new SummaryStatistics();
			stats.getGroups().forEach(group -> {
				final DescriptiveStatistics dStats = stats.getGroupStats(group).getDescriptiveStats(metric);
				reportBuilder.append(group).append(" Statistics:");
				reportBuilder.append("\r\nFolder:\t").append(getDirPath(group));
				reportBuilder.append("\r\nN:\t").append(dStats.getN());
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
				histFrame.show();
				boxFrame.setLocationRelativeTo(histFrame);
				boxFrame.setVisible(true);
			}
			final boolean mappableMetric = isMetricMappable(metric);
			if (displayInMultiViewer && mappableMetric) {
				stats.getGroups().forEach(group -> displayGroup(stats, group, uberStats.getMin(), uberStats.getMax()));
			}
			final StringBuilder exitMsg = new StringBuilder("<HTML>");
			if (inputGroupsCounter != stats.getGroups().size()) {
				exitMsg.append("<p>Some directories (").append(inputGroupsCounter - stats.getGroups().size());
				exitMsg.append(") did not contain matching data and were skipped.</p>");
			}
			if (displayInMultiViewer) {
				exitMsg.append((mappableMetric) ? "<p>NB: Only the first 10 cells of each group were mapped.</p>"
						: "<p>NB: Metric is not mappable. Choice ignored.</p>");
			}
			if (exitMsg.length() > 10) {
				uiService.showDialog(exitMsg.toString(), "Warning");
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
				for (final Tree tree : stats.getGroupStats(groupLabel).getGroup()) {
					tree.setLabel((tree.getLabel() == null) ? "" : tree.getLabel() + " (" + groupLabel + ")");
					tree.setColor(colors[index]);
					recViewer.addTree(tree);
				}
				index++;
			}
			recViewer.setSceneUpdatesEnabled(true);
			recViewer.updateView();
			if (recViewerIsNotVisible) recViewer.show();
		}

		resetUI();

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
		return Arrays.stream(MultiTreeColorMapper.PROPERTIES).anyMatch(metric::equals);
	}

	private boolean addGroup(final GroupedTreeStatistics stats, final File file, final String label) {
		if (!validFile(file)) return false;
		inputGroupsCounter++;
		final List<Tree> trees = Tree.listFromDir(file.getAbsolutePath(), "", getSWCTypes(scope));
		if (trees == null || trees.isEmpty()) return false;
		stats.addGroup(trees, label);
		return true;
	}

	private String[] getSWCTypes(final String scope) {
		if (scope == null) return null;
		switch(scope.toLowerCase()) {
		case "axon":
			return new String[] {Path.SWC_AXON_LABEL};
		case "dendrites":
			return new String[] {Path.SWC_APICAL_DENDRITE_LABEL, Path.SWC_DENDRITE_LABEL};
		default:
			return null;
		}
	}

	private boolean validFile(final File file) {
		return file != null && file.isDirectory() && file.exists() && file.canRead();
	}


	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(GroupAnalyzerCmd.class, true);
	}
}
