/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.thread.ThreadService;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;

import net.imagej.ImageJ;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;
import sc.fiji.snt.gui.GUIHelper;
import sc.fiji.snt.util.Logger;

/**
 * A modified version of {@link ShollAnalysisTreeCmd} for Bulk Sholl Analysis.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Bulk Sholl Analysis (Tracings)", initializer = "init")
public class ShollAnalysisBulkTreeCmd extends ContextCommand
{

	@Parameter
	private CommandService cmdService;
	@Parameter
	private DisplayService displayService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private ThreadService threadService;

	/* Parameters */
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = ShollAnalysisImgCmd.HEADER_HTML + "Input:")
	private String HEADER0;

	@Parameter(label = "Directory", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = ShollAnalysisImgCmd.HEADER_TOOLTIP + "Input folder containing reconstruction files.")
	private File directory;

	@Parameter(label = "Filename filter", required=false, 
			description="Only filenames matching this string (case sensitive) will be considered. "
			+ "Regex patterns accepted. Leave empty to disable fitering.")
	private String filenamePattern;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "<br>Sampling:")
	private String HEADER1;

	@Parameter(label = "Path filtering", required = false, choices = { "None",
		"Paths tagged as 'Axon'", "Paths tagged as 'Dendrite'",
		"Paths tagged as 'Custom'", "Paths tagged as 'Undefined'" })
	private String filterChoice;

	@Parameter(label = "Center", choices = { "Soma",
		"Root node(s)",
		"Root node(s): Primary axon(s)",
		"Root node(s): Primary (basal) dendrites(s)",
		"Root node(s): Primary apical dendrites(s)"})
	private String centerChoice;

	@Parameter(label = "Radius step size", required = false, min = "0",
			callback = "stepSizeChanged")
	private double stepSize;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "<br>Metrics:")
	private String HEADER2;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = "<html><i>Polynomial Fit:")
	private String HEADER2A;

	@Parameter(label = "Degree", callback = "polynomialChoiceChanged",
		required = false, choices = { "'Best fitting' degree (see Options)",
			"None. Skip curve fitting", "Use degree specified below:" })
	private String polynomialChoice;

	@Parameter(label = "<html>&nbsp;", callback = "polynomialDegreeChanged",
			min = "" + ShollAnalysisPrefsCmd.DEF_MIN_DEGREE, max = "" + ShollAnalysisPrefsCmd.DEF_MAX_DEGREE,
			stepSize = "1", style = NumberWidget.SCROLL_BAR_STYLE)
	private int polynomialDegree;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = "<html><i>Sholl Decay:")
	private String HEADER2B;

	@Parameter(label = "Method", choices = { "Automatically choose", "Semi-Log",
		"Log-log" })
	private String normalizationMethodDescription;

	@Parameter(label = "Normalizer", choices = { "Default", "Area/Volume",
		"Perimeter/Surface area", "Annulus/Spherical shell" },
		callback = "normalizerDescriptionChanged")
	private String normalizerDescription;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "<br>Output:")
	private String HEADER3;

	@Parameter(label = "Plots", choices = { "Linear plot", "Normalized plot",
		"Linear & normalized plots", "None" })
	private String plotOutputDescription;

	@Parameter(label = "Tables", choices = {"Summary table", "Detailed & Summary tables"})
	private String tableOutputDescription;

	@Parameter(required = false, label = "Destination", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = ShollAnalysisImgCmd.HEADER_TOOLTIP
					+ "Destination directory. NB: Files will be overwritten on re-runs.")
	private File saveDir;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<HTML>&nbsp;") // empty label
	private String HEADER4;

	@Parameter(label = " Options, Preferences and Resources... ", callback = "runOptions")
	private Button optionsButton;


	/* Instance variables */
	private GUIHelper helper;
	private Logger logger;
	private ShollTable commonSummaryTable;
	private static final String SUMMARY_TABLE_NAME = "_Sholl_Metrics.csv";

	/* Preferences */
	private int minDegree;
	private int maxDegree;

	@Override
	public void run() {

		final List<Tree> treeList = Tree.listFromDir(directory.getAbsolutePath(), filenamePattern, getSWCTypes());
		if (treeList == null || treeList.isEmpty()) {
			final String msg = (filenamePattern == null || filenamePattern.isEmpty())
					? "No reconstruction files found in input folder."
					: "No reconstruction files matching '" + filenamePattern + "' were found in input folder.";
			helper.error(msg, "No Files in Input Directory");
			return;
		}
		if (saveDir == null || !saveDir.exists() || !saveDir.canWrite()) {
			helper.error("Output directory is not valid or writable.", "Please Change Output Directory");
			return;
		}
		logger = new Logger(context(), "Sholl");
		logger.info("Found " + treeList.size() + " reconstructions in " + directory.getAbsolutePath());
		logger.info("Running multithreaded analysis...");
		readPreferences();
		treeList.parallelStream().forEach(tree -> {
			new AnalysisRunner(tree).run();
		});
		logger.info("Done.");
		if (commonSummaryTable == null) {
			cancel("Options were likely invalid and no files were parsed. See Console for details.");
		} else if (commonSummaryTable.hasUnsavedData() && !saveSummaryTable()) {
			cancel("An Error occured while saving summary table. Please save it manually.");
		}

	}


	private String[] getSWCTypes() {
		final String normChoice = filterChoice.toLowerCase();
		if (normChoice.contains("none"))
			return null;
		if (normChoice.contains("axon"))
			return new String[] {"axon"};
		if (normChoice.contains("dendrite"))
			return new String[] {"dendrite"};
		if (normChoice.contains("custom"))
			return new String[] {"custom"};
		if (normChoice.contains("undefined"))
			return new String[] {"undefined"};
		return null;
	}

	private NormalizedProfileStats getNormalizedProfileStats(
		final Profile profile)
	{
		String normString = normalizerDescription.toLowerCase();
		if (normString.startsWith("default")) {
			normString = "Area/Volume";
		}
		if (!profile.is2D()) {
			normString = normString.substring(normString.indexOf("/") + 1);
		}
		else {
			normString = normString.substring(0, normString.indexOf("/"));
		}
		final int normFlag = NormalizedProfileStats.getNormalizerFlag(normString);
		final int methodFlag = NormalizedProfileStats.getMethodFlag(
			normalizationMethodDescription);
		return new NormalizedProfileStats(profile, normFlag, methodFlag);
	}

	private void readPreferences() {
		minDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "minDegree",
			ShollAnalysisPrefsCmd.DEF_MIN_DEGREE);
		maxDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "maxDegree",
			ShollAnalysisPrefsCmd.DEF_MAX_DEGREE);
	}

	private double adjustedStepSize() {
		return Math.max(stepSize, 0);
	}

	/* callbacks */

	@SuppressWarnings("unused")
	private void init() {
		helper = new GUIHelper(context());
	}

	@SuppressWarnings("unused")
	/* Callback for stepSize */
	private void stepSizeChanged() {
		stepSize = Math.max(0, stepSize);
		normalizerDescriptionChanged();
	}

	@SuppressWarnings("unused")
	/* Callback for polynomialChoice */
	private void polynomialChoiceChanged() {
		if (!polynomialChoice.contains("specified")) {
			polynomialDegree = 0;
		}
		else if (polynomialDegree == 0) {
			polynomialDegree = (int)(minDegree + maxDegree) / 2;
		}
	}

	/* Callback for stepSize && normalizerDescription */
	private void normalizerDescriptionChanged() {
		if (stepSize == 0 && (normalizerDescription.contains("Annulus") ||
			normalizerDescription.contains("shell")))
		{
			helper.errorPrompt(normalizerDescription +
				" normalization requires radius step size to be ≥ 0", null);
			normalizerDescription = "Default";
		}
	}

	@SuppressWarnings("unused")
	/* Callback for polynomialDegree */
	private void polynomialDegreeChanged() {
		if (polynomialDegree == 0) polynomialChoice = "'Best fitting' degree";
		else polynomialChoice = "Use degree specified below:";
	}

	@SuppressWarnings("unused")
	/* Callback for optionsButton */
	private void runOptions() {
		threadService.run(() -> {
			final Map<String, Object> input = new HashMap<>();
			input.put("ignoreBitmapOptions", true);
			cmdService.run(ShollAnalysisPrefsCmd.class, true, input);
		});
	}

	private boolean saveSummaryTable() {
		final boolean save = commonSummaryTable.saveSilently(new File(saveDir, SUMMARY_TABLE_NAME));
		if (save)
			logger.info("Summary table saved...");
		else
			logger.warn("Error while saving summary table");
		return save;
	}

	private void updateDisplayAndSaveCommonSummaryTable() {
		final Display<?> display = displayService.getDisplay(SUMMARY_TABLE_NAME);
		if (display != null && display.isDisplaying(commonSummaryTable)) {
			display.update();
		}
		else {
			displayService.createDisplay(SUMMARY_TABLE_NAME, commonSummaryTable);
		}
		if (commonSummaryTable.hasUnsavedData())
			saveSummaryTable(); // keep saving table everytime it is updated
	}

	private class AnalysisRunner implements Runnable {

		private final Tree tree;
		private TreeParser parser;
		private LinearProfileStats lStats;
		private NormalizedProfileStats nStats;
		private final String TREE_LABEL;

		public AnalysisRunner(final Tree tree) {
			this.tree = tree;
			TREE_LABEL = tree.getLabel();
		}

		@Override
		public void run() {

			// Ensure all conditions are met for analysis
			if (tree == null || tree.isEmpty()) {
				String msg = " Skipping: This reconstruction is not valid";
				if (!"None".equals(filterChoice))
					msg += " or does not contain " + filterChoice;
				logger.warn(TREE_LABEL + msg);
				return;
			}
			parser = new TreeParser(tree);
			parser.setStepSize(adjustedStepSize());
			try {
				parser.setCenter(ShollAnalysisTreeCmd.getCenterFromChoice(centerChoice));
			} catch (final IllegalArgumentException ex) {
				logger.warn(TREE_LABEL + " Skipping: Center choice cannot be applied to reconstruction. Try \"Root node(s)\" instead.");
				return;
			}

			// parse
			logger.info(TREE_LABEL + " Parsing started...");
			try {
				parser.parse();
			} catch (final Exception ex) {
				logger.warn(TREE_LABEL + " Exception occured: " + ex.getMessage());
				return;
			}
			if (!parser.successful()) {
				logger.warn(TREE_LABEL + " Skipping analysis: Parsing failed. No valid profile retrieved!");
				return;
			}
			final Profile profile = parser.getProfile();

			// Linear profile stats
			lStats = new LinearProfileStats(profile);
			lStats.setLogger(logger);
			int primaryBranches;
			try {
				logger.info(TREE_LABEL + " Retrieving primary branches...");
				primaryBranches = new TreeAnalyzer(tree).getPrimaryBranches().size();
			} catch (IllegalArgumentException exc) {
				logger.info(TREE_LABEL + " Failed. Defaulting to primary paths.");
				primaryBranches = new TreeAnalyzer(tree).getPrimaryPaths().size();
			}
			lStats.setPrimaryBranches(primaryBranches);

			if (polynomialChoice.contains("Best")) {
				logger.info(TREE_LABEL + " Computing 'Best Fit' Polynomial...");
				final int deg = lStats.findBestFit(minDegree, maxDegree, prefService);
				if (deg == -1) {
					logger.warn(TREE_LABEL + " Fit failed... please adjust options");
				}
			}
			else if (polynomialChoice.contains("degree") && polynomialDegree > 1) {
				try {
					lStats.fitPolynomial(polynomialDegree);
				}
				catch (final Exception ignored) {
					logger.warn(TREE_LABEL + " Polynomial regression failed. Unsuitable degree?");
				}
			}

			/// Normalized profile stats
			nStats = getNormalizedProfileStats(profile);

			// Plots
			if (plotOutputDescription.toLowerCase().contains("linear")) {
				final ShollPlot lPlot = new ShollPlot(lStats);
				if (lPlot.save(saveDir))
					logger.info(TREE_LABEL + " Linear plot saved...");
				else
					logger.warn(TREE_LABEL + " Error while saving linear plot");
			}
			if (plotOutputDescription.toLowerCase().contains("normalized")) {
				final ShollPlot nPlot = new ShollPlot(nStats);
				if (nPlot.save(saveDir))
					logger.info(TREE_LABEL + " Normalized plot saved...");
				else
					logger.warn(TREE_LABEL + " Error while saving normalized plot");
			}

			// Tables
			if (tableOutputDescription.contains("Detailed")) {
				final ShollTable dTable = new ShollTable(lStats, nStats);
				dTable.listProfileEntries();
				if (!dTable.hasContext()) dTable.setContext(getContext());
				if (dTable.saveSilently(new File(saveDir, TREE_LABEL + "_profile.csv")))
					logger.info(TREE_LABEL + " Detailed table saved...");
				else
					logger.warn(TREE_LABEL + " Error while saving detailed table");
			}

			final ShollTable sTable = new ShollTable(lStats, nStats);
			if (commonSummaryTable == null) commonSummaryTable = new ShollTable();
			String header = TREE_LABEL;
			if (!filterChoice.contains("None")) header += "(" + filterChoice + ")";
			if (!sTable.hasContext()) sTable.setContext(getContext());
			sTable.summarize(commonSummaryTable, header);
			updateDisplayAndSaveCommonSummaryTable();
		}

	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		final CommandService cmdService = ij.command();
		cmdService.run(ShollAnalysisBulkTreeCmd.class, true, input);
	}
}
