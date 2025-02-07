/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import net.imagej.ImageJ;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.DefaultGenericTable;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.ShollAnalyzer;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

import java.io.File;
import java.util.*;

/**
 * Command for measuring Tree(s).
 * @deprecated replaced by {@link sc.fiji.snt.gui.MeasureUI }
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label="Measure...", initializer = "init")
@Deprecated
public class AnalyzerCmd extends CommonDynamicCmd {

	private static final String TABLE_TITLE = "SNT Measurements";

	// I: Input options
	@Parameter(label = "<HTML><b>Input Files:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER1;

	@Parameter(required = false, style = FileWidget.DIRECTORY_STYLE, label = "Directory:",
			description = "<HTML><div WIDTH=500>Path to directory containing files to be measured. "
					+ "NB: A single file can also be specified but the \"Browser\" prompt "
					+ "may not allow single files to be selected. In that case, you can "
					+ "manually specify its path in the text field.")
	private File file;

	@Parameter(label = "File name contains", required = false, description = "<HTML><div WIDTH=500>"
			+ "Only filenames containing this pattern will be imported from the directory")
	private String pattern;

	// II. Metrics
	@Parameter(label = "<HTML>&nbsp;<br><b>Metrics:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(label = MultiTreeStatistics.LENGTH)
	private boolean cableLength;

	@Parameter(label = MultiTreeStatistics.TERMINAL_LENGTH)
	private boolean terminalLength;

	@Parameter(label = MultiTreeStatistics.PRIMARY_LENGTH)
	private boolean primaryLength;

	@Parameter(label = MultiTreeStatistics.INNER_LENGTH)
	private boolean innerLength;

	@Parameter(label = MultiTreeStatistics.AVG_BRANCH_LENGTH)
	private boolean avgBranchLength;

	@Parameter(label = MultiTreeStatistics.AVG_CONTRACTION)
	private boolean avgContraction;

	@Parameter(label = MultiTreeStatistics.AVG_FRAGMENTATION)
	private boolean avgFragmentation;

	@Parameter(label = MultiTreeStatistics.AVG_REMOTE_ANGLE)
	private boolean avgRemoteAngle;
	
	@Parameter(label = MultiTreeStatistics.AVG_PARTITION_ASYMMETRY)
	private boolean avgPartitionAsymmetry;

	@Parameter(label = MultiTreeStatistics.AVG_FRACTAL_DIMENSION)
	private boolean avgFractalDimension;

	@Parameter(label = MultiTreeStatistics.PATH_SPINE_DENSITY)
	private boolean spineDensity;

	@Parameter(label = MultiTreeStatistics.N_BRANCH_POINTS)
	private boolean nBPs;

	@Parameter(label = MultiTreeStatistics.N_TIPS)
	private boolean nTips;

	@Parameter(label = MultiTreeStatistics.N_BRANCHES)
	private boolean nBranches;

	@Parameter(label = MultiTreeStatistics.N_PRIMARY_BRANCHES)
	private boolean nPrimaryBranches;

	@Parameter(label = MultiTreeStatistics.N_INNER_BRANCHES)
	private boolean nInnerBranches;

	@Parameter(label = MultiTreeStatistics.N_SPINES)
	private boolean nSpinesOrVaricosities;

	@Parameter(label = MultiTreeStatistics.N_TERMINAL_BRANCHES)
	private boolean nTerminalBranches;

	@Parameter(label = MultiTreeStatistics.STRAHLER_NUMBER)
	private boolean sNumber;

	@Parameter(label = MultiTreeStatistics.STRAHLER_RATIO)
	private boolean sRatio;

	@Parameter(label = MultiTreeStatistics.WIDTH)
	private boolean width;

	@Parameter(label = MultiTreeStatistics.HEIGHT)
	private boolean height;

	@Parameter(label = MultiTreeStatistics.DEPTH)
	private boolean depth;

	@Parameter(label = MultiTreeStatistics.MEAN_RADIUS)
	private boolean meanRadius;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.MEAN)
	private boolean shollMean;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.SUM)
	private boolean shollSum;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.MAX)
	private boolean shollMax;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.N_MAX)
	private boolean shollNMax;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.N_SECONDARY_MAX)
	private boolean shollNSecMax;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.MAX_FITTED)
	private boolean shollMaxFitted;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.MAX_FITTED_RADIUS)
	private boolean shollMaxFittedRadius;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.POLY_FIT_DEGREE)
	private boolean shollDegree;

	@Parameter(label = "Sholl: "+ ShollAnalyzer.DECAY)
	private boolean shollDecay;

	// III. Options
	@Parameter(label = "<HTML>&nbsp;<br><b>Options:", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER3;

	@Parameter(label = "Action", choices = {"Choose", "Select All", "Select None", "Reverse selection"}, callback="actionChoiceSelected")
	private String actionChoice;

	@Parameter(label = "Distinguish compartments", description="<HTML><div WIDTH=500>Whether measurements "
			+ "should be grouped by cellular compartment (e.g., \"axon\", \"dendrites\", etc.)")
	private boolean splitByType;

	@Parameter(label = "<HTML>&nbsp;<br", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String fittingSpacer;

	@Parameter(label = "Note on Fitted Paths", callback="fittingHelpMsgPressed")
	private Button fittingHelpMsg;

	@Parameter(required = false)
	private Tree tree;

	@Parameter(required = false)
	private Collection<Tree> trees;

	@Parameter(required = false)
	private DefaultGenericTable table;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE, persist = false)
	private boolean calledFromPathManagerUI;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (tree != null || trees != null) {
			resolveInput("SPACER1");
			getInfo().getMutableInput("SPACER1", String.class).setLabel("");
			resolveInput("file");
			getInfo().getMutableInput("file", File.class).setLabel("");
			resolveInput("pattern");
			getInfo().getMutableInput("pattern", String.class).setLabel("");
		}
		if (tree == null && trees == null) {
			// caller specified nothing: user will be prompted for file/dir
			resolveInput("tree");
			resolveInput("trees");
		} else if (tree != null) {
			// caller specified a single tree
			resolveInput("trees");
		} else {
			// caller specified a collection of trees
			resolveInput("tree");
		}
		if (table == null) {
			table = (sntService.isActive()) ? sntService.getTable() : new SNTTable();
			if (table == null) table = new SNTTable();
		}
		resolveInput("table");
		if (!calledFromPathManagerUI) {
			resolveInput("fittingSpacer");
			resolveInput("fittingHelpMsg");
		}
		resolveInput("calledFromPathManagerUI");
	}

	@SuppressWarnings("unused")
	private void fittingHelpMsgPressed() {
		new GuiUtils().showHTMLDialog("<HTML><div WIDTH=550>"
					+ "<p>Some branch-based metrics may not be available when mixing fitted and "
					+ "non-fitted paths because paths are fitted independently from one another and "
					+ "may not be aware of the original connectivity. "
					+ "When this happens, metrics will be reported as <em>NaN</em> and related errors "
					+ "reported to the Console (when running in <em>Debug</em> mode).</p>"
					+ "<p>If this becomes an issue in your analyses, consider fitting paths in situ "
					+ "using the <em>Replace existing nodes</em> option instead. Also, remember that "
					+ "you can also use the Path Manager&#39;s Edit&gt;Rebuild... command to re-compute "
					+ "relationships between paths.</p>",
					"Warning on Fitted Paths", true);
	}

	@SuppressWarnings("unused")
	private void actionChoiceSelected() {
		if (actionChoice.contains("All")) {
			setAllCheckboxesEnabled(true);
		} else if (actionChoice.contains("None")) {
			setAllCheckboxesEnabled(false);
		} else if (actionChoice.contains("Reverse")) {
			reverseCheckboxes();
		}
	}

	private void setAllCheckboxesEnabled(final boolean enable) {
		avgBranchLength = enable;
		avgContraction = enable;
		avgFragmentation = enable;
		avgRemoteAngle = enable;
		avgPartitionAsymmetry = enable;
		avgFractalDimension = enable;
		spineDensity = enable;
		cableLength = enable;
		depth = enable;
		height = enable;
		meanRadius = enable;
		nBPs = enable;
		nBranches = enable;
		nPrimaryBranches = enable;
		nInnerBranches = enable;
		nSpinesOrVaricosities = enable;
		nTerminalBranches = enable;
		nTips = enable;
		primaryLength = enable;
		innerLength = enable;
		sNumber = enable;
		sRatio = enable;
		terminalLength = enable;
		width = enable;
		shollMean = enable;
		shollSum = enable;
		shollMax = enable;
		shollNMax = enable;
		shollNSecMax = enable;
		shollMaxFitted = enable;
		shollMaxFittedRadius = enable;
		shollDegree = enable;
		shollDecay = enable;
		actionChoice = "Choose";
	}

	private void reverseCheckboxes() {
		avgBranchLength = !avgBranchLength;
		avgContraction = !avgContraction;
		avgFragmentation = !avgFragmentation;
		avgRemoteAngle = !avgRemoteAngle;
		avgPartitionAsymmetry = !avgPartitionAsymmetry;
		avgFractalDimension = !avgFractalDimension;
		spineDensity = !spineDensity;
		cableLength = !cableLength;
		depth = !depth;
		height = !height;
		meanRadius = !meanRadius;
		nBPs = !nBPs;
		nBranches = !nBranches;
		nPrimaryBranches = !nPrimaryBranches;
		nInnerBranches = !nInnerBranches;
		nSpinesOrVaricosities = !nSpinesOrVaricosities;
		nTerminalBranches = !nTerminalBranches;
		nTips = !nTips;
		primaryLength = !primaryLength;
		innerLength = !innerLength;
		sNumber = !sNumber;
		sRatio = !sRatio;
		terminalLength = !terminalLength;
		width = !width;
		shollMean = !shollMean;
		shollSum = !shollSum;
		shollMax = !shollMax;
		shollNMax = !shollNMax;
		shollNSecMax = !shollNSecMax;
		shollMaxFitted = !shollMaxFitted;
		shollMaxFittedRadius = !shollMaxFittedRadius;
		shollDegree = !shollDegree;
		shollDecay = !shollDecay;
		actionChoice = "Choose";
	}

	@Override
	public void run() {

		final List<String> metrics = new ArrayList<>();
		if (avgBranchLength) metrics.add(MultiTreeStatistics.AVG_BRANCH_LENGTH);
		if (avgContraction) metrics.add(MultiTreeStatistics.AVG_CONTRACTION);
		if (avgFragmentation) metrics.add(MultiTreeStatistics.AVG_FRAGMENTATION);
		if(avgRemoteAngle) metrics.add(MultiTreeStatistics.AVG_REMOTE_ANGLE);
		if(avgPartitionAsymmetry) metrics.add(MultiTreeStatistics.AVG_PARTITION_ASYMMETRY);
		if(avgFractalDimension) metrics.add(MultiTreeStatistics.AVG_FRACTAL_DIMENSION);
		if(spineDensity) metrics.add(MultiTreeStatistics.PATH_SPINE_DENSITY);
		if(cableLength) metrics.add(MultiTreeStatistics.LENGTH);
		if(terminalLength) metrics.add(MultiTreeStatistics.TERMINAL_LENGTH);
		if(primaryLength) metrics.add(MultiTreeStatistics.PRIMARY_LENGTH);
		if(innerLength) metrics.add(MultiTreeStatistics.INNER_LENGTH);
		if(nBPs) metrics.add(MultiTreeStatistics.N_BRANCH_POINTS);
		if(nTips) metrics.add(MultiTreeStatistics.N_TIPS);
		if(nBranches) metrics.add(MultiTreeStatistics.N_BRANCHES);
		if(nPrimaryBranches) metrics.add(MultiTreeStatistics.N_PRIMARY_BRANCHES);
		if(nInnerBranches) metrics.add(MultiTreeStatistics.N_INNER_BRANCHES);
		if(nTerminalBranches) metrics.add(MultiTreeStatistics.N_TERMINAL_BRANCHES);
		if(nSpinesOrVaricosities) metrics.add(MultiTreeStatistics.N_SPINES);
		if(sNumber) metrics.add(MultiTreeStatistics.STRAHLER_NUMBER);
		if(sRatio) metrics.add(MultiTreeStatistics.STRAHLER_RATIO);
		if(width) metrics.add(MultiTreeStatistics.WIDTH);
		if(height) metrics.add(MultiTreeStatistics.HEIGHT);
		if(depth) metrics.add(MultiTreeStatistics.DEPTH);
		if(meanRadius) metrics.add(MultiTreeStatistics.MEAN_RADIUS);
		if(shollMean) metrics.add("Sholl: " + ShollAnalyzer.MEAN);
		if(shollSum) metrics.add("Sholl: " + ShollAnalyzer.SUM);
		if(shollMax) metrics.add("Sholl: " + ShollAnalyzer.MAX);
		if(shollNMax) metrics.add("Sholl: " + ShollAnalyzer.N_MAX);
		if(shollNSecMax) metrics.add("Sholl: " + ShollAnalyzer.N_SECONDARY_MAX);
		if(shollMaxFitted) metrics.add("Sholl: " + ShollAnalyzer.MAX_FITTED);
		if(shollMaxFittedRadius) metrics.add("Sholl: " + ShollAnalyzer.MAX_FITTED_RADIUS);
		if(shollDegree) metrics.add("Sholl: " + ShollAnalyzer.POLY_FIT_DEGREE);
		if(shollDecay) metrics.add("Sholl: " + ShollAnalyzer.DECAY);
		if (metrics.isEmpty()) {
			error("No metrics chosen.");
			return;
		}

		if (tree != null) {
			trees = Collections.singletonList(tree);
		}

		if (trees != null) {
			measure(trees, metrics);
			return;
		}

		if (file == null || !file.exists() || !file.canRead()) {
			error("Specified path is not valid.");
			return;
		}

		if (file.isDirectory()) {
			trees = Tree.listFromDir(file.getAbsolutePath(), pattern);
		} else if (validFile(file)) {
			trees = new ArrayList<>();
			final Tree tree = Tree.fromFile(file.getAbsolutePath());
			if (tree != null) trees.add(tree);
		}
		if (trees.isEmpty()) {
			error("No reconstruction file(s) could be retrieved from the specified path.");
			return;
		}
		measure(trees, metrics);
	}

	private boolean validFile(final File file) {
		return (pattern == null || pattern.isEmpty()) || file.getName().contains(pattern);
	}

	private void measure(final Collection<Tree> trees, final List<String> metrics) {
		try {
			final int n = trees.size();
			final Iterator<Tree> it = trees.iterator();
			int index = 0;
			while (it.hasNext()) {
				final Tree tree = it.next();
				statusService.showStatus(index++, n, tree.getLabel());
				final TreeStatistics analyzer = new TreeStatistics(tree);
				analyzer.setContext(getContext());
				analyzer.setTable(table, TABLE_TITLE);
				analyzer.measure(metrics, splitByType); // will display table
			}
		} catch (final IllegalArgumentException | ArithmeticException | IllegalStateException ex) {
			error("An error occurred while computing metric(s). See Console for details.");
			ex.printStackTrace();
		} finally {
			resetUI();
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
		input.put("tree", tree);
		ij.command().run(AnalyzerCmd.class, true, (Map<String, Object>)null);
	}
}
