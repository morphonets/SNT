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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
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
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.Logger;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * A modified version of {@link ShollAnalysisTreeCmd} for Bulk Sholl Analysis.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Bulk Sholl Analysis (Tracings)", initializer = "init")
public class ShollAnalysisBulkTreeCmd extends CommonDynamicCmd {

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
			label = ShollAnalysisImgCommonCmd.HEADER_HTML + "Input:")
	private String HEADER0;

	@Parameter(required = false, label = "Directory", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = ShollAnalysisImgCommonCmd.HEADER_TOOLTIP + "Input folder containing reconstruction files.")
	private File directory;

	@Parameter(required = false, label = "Filename filter",
			description="Only filenames matching this string (case sensitive) will be considered. "
			+ "Regex patterns accepted. Leave empty to disable fitering.")
	private String filenamePattern;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCommonCmd.HEADER_HTML + "<br>Sampling:")
	private String HEADER1;

	@Parameter(label = "Path filtering", required = false, choices = { "None",
		"Paths tagged as 'Axon'", "Paths tagged as 'Dendrite'",
		"Paths tagged as 'Custom'", "Paths tagged as 'Undefined'" })
	private String filterChoice;

	@Parameter(label = "Center",  required = false, //
		description = "Root nodes correspond to the starting nodes of primary (root) paths of the specified type.\n" //
			+ "If multiple primary paths exits, center becomes the centroid (mid-point) of the identified\n"
			+ "starting node(s).\n \n" //
			+ "If 'Soma node(s): Ignore connectivity' is chosen, the centroid of soma-tagged nodes is \n"
			+ "used, even if such paths are not at the root.", //
		choices = { 
		"Soma node(s): Ignore connectivity",
		"Soma node(s): Primary paths only",
		"Root node(s)",
		"Root node(s): Primary axon(s)",
		"Root node(s): Primary (basal) dendrites(s)",
		"Root node(s): Primary apical dendrites(s)"})
	private String centerChoice;

	@Parameter(label = "Radius step size", required = false, min = "0",
			callback = "stepSizeChanged")
	private double stepSize;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCommonCmd.HEADER_HTML + "<br>Metrics:")
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
			stepSize = "1", style = NumberWidget.SLIDER_STYLE)
	private int polynomialDegree;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = "<html><i>Sholl Decay:")
	private String HEADER2B;

	@Parameter(label = "Method", required = false, choices = { "Automatically choose", "Semi-Log",
		"Log-log" })
	private String normalizationMethodDescription;

	@Parameter(label = "Normalizer", required = false, choices = { "Default", "Area/Volume",
		"Perimeter/Surface area", "Annulus/Spherical shell" },
		callback = "normalizerDescriptionChanged")
	private String normalizerDescription;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCommonCmd.HEADER_HTML + "<br>Output:")
	private String HEADER3;

	@Parameter(label = "Plots",  required = false, choices = { "Linear plot", "Normalized plot",
		"Linear & normalized plots", "None" })
	private String plotOutputDescription;

	@Parameter(label = "Tables", required = false, choices = {"Summary table", "Detailed & Summary tables"})
	private String tableOutputDescription;

	@Parameter(required = false, label = "Destination", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = ShollAnalysisImgCommonCmd.HEADER_TOOLTIP
					+ "Destination directory. NB: Files will be overwritten on re-runs.")
	private File saveDir;

	@Parameter(required = false, label = "Display outputs",//
			description = ShollAnalysisImgCommonCmd.HEADER_TOOLTIP
					+ "Whether plots and tables should be displayed after being saved")
	private boolean showAnalysis;
	
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<HTML>&nbsp;") // empty label
	private String HEADER4;

	@Parameter(label = "Further Options...", callback = "runOptions")
	private Button optionsButton;

	@Parameter(required = false)
	private List<Tree> treeList;
	@Parameter(required = false)
	private Viewer3D recViewer;
	private boolean validDir;


	/* Instance variables */
	private GuiUtils helper;
	private Logger logger;
	private ShollTable commonSummaryTable;
	private static final String SUMMARY_TABLE_NAME = "_Sholl_Metrics.csv";

	/* Preferences */
	private int minDegree;
	private int maxDegree;

	@Override
	public void run() {

		if (treeList == null || treeList.isEmpty()) {
			treeList = Tree.listFromDir(directory.getAbsolutePath(), filenamePattern, getSWCTypes());
			logger.info("Found " + treeList.size() + " reconstructions in " + directory.getAbsolutePath());
		}
		if (treeList == null || treeList.isEmpty()) {
			final String msg = (filenamePattern == null || filenamePattern.isEmpty())
					? "No reconstruction files found in input folder."
					: "No reconstruction files matching '" + filenamePattern + "' were found in input folder.";
			helper.error(msg, "No Files in Input Directory");
			return;
		}
		validDir = saveDir != null && saveDir.exists() && saveDir.canWrite();
		if (!showAnalysis && !validDir) {
			helper.error("Display of outputs disabled and output directory is not valid or writable.", "Please Change Output Directory");
			return;
		}
		
		if (recViewer != null && recViewer.getManagerPanel() != null) {
			recViewer.getManagerPanel().showProgress(-1, -1);
		}

		// defaults when prompt is not displayed
		if (tableOutputDescription == null || tableOutputDescription.isEmpty())
			tableOutputDescription = "Summary table";
		if (plotOutputDescription == null || plotOutputDescription.isEmpty())
			plotOutputDescription = "Linear plot";
		if (normalizerDescription == null || normalizerDescription.isEmpty())
			normalizerDescription = "Default";
		if (normalizationMethodDescription == null || normalizationMethodDescription.isEmpty())
			normalizationMethodDescription = "Automatically choose";
		if (centerChoice == null || centerChoice.isEmpty())
			centerChoice = "Root node(s)";
		
		logger = new Logger(context(), "Sholl");
		logger.info("Running multithreaded analysis...");
		readPreferences();
		commonSummaryTable = new ShollTable();
		treeList.parallelStream().forEach(tree -> new AnalysisRunner(tree).run());
		logger.info("Done.");
		if (commonSummaryTable == null || commonSummaryTable.isEmpty() || commonSummaryTable.getRowCount() < 1) {
			cancel("Options were likely invalid and no files were parsed. See Console for details.");
		} else if (commonSummaryTable.hasUnsavedData() && !saveSummaryTable()) {
			cancel("An Error occurred while saving summary table. Please save it manually.");
		}
		if (recViewer != null && recViewer.getManagerPanel() != null) {
			recViewer.getManagerPanel().showProgress(0, 0);
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
		helper = new GuiUtils();
		if (treeList != null) {
			resolveInput("HEADER0");
			resolveInput("directory");
			resolveInput("filenamePattern");
		} else {
			resolveInput("treeList");
		}
		logger = new Logger(getContext(), "Sholl B.A.");
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
			polynomialDegree = (minDegree + maxDegree) / 2;
		}
	}

	/* Callback for stepSize && normalizerDescription */
	private void normalizerDescriptionChanged() {
		if (stepSize == 0 && (normalizerDescription.contains("Annulus") ||
			normalizerDescription.contains("shell")))
		{
			GuiUtils.errorPrompt(normalizerDescription +
				" normalization requires radius step size to be ≥ 0");
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
            TreeParser parser = new TreeParser(tree);
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
				logger.warn(TREE_LABEL + " Exception occurred: " + ex.getMessage());
				return;
			}
			if (!parser.successful()) {
				logger.warn(TREE_LABEL + " Skipping analysis: Parsing failed. No valid profile retrieved!");
				return;
			}
			final Profile profile = parser.getProfile();

			// Linear profile stats
            LinearProfileStats lStats = new LinearProfileStats(profile);
			lStats.setLogger(logger);
			int primaryBranches;
			try {
				logger.info(TREE_LABEL + " Retrieving primary branches...");
				primaryBranches = new TreeStatistics(tree).getPrimaryBranches().size();
			} catch (IllegalArgumentException exc) {
				logger.info(TREE_LABEL + " Failed. Defaulting to primary paths.");
				primaryBranches = new TreeStatistics(tree).getPrimaryPaths().size();
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
            NormalizedProfileStats nStats = getNormalizedProfileStats(profile);

			// Plots
			if (plotOutputDescription.toLowerCase().contains("linear")) {
				final ShollPlot lPlot = lStats.getPlot(false);
				if (validDir) {
					if (lPlot.save(saveDir))
						logger.info(TREE_LABEL + " Linear plot saved...");
					else
						logger.warn(TREE_LABEL + " Error while saving linear plot");
				}
				if (showAnalysis) lPlot.show();
			}
			if (plotOutputDescription.toLowerCase().contains("normalized")) {
				final ShollPlot nPlot = nStats.getPlot(false);
				if (validDir) {
					if (nPlot.save(saveDir))
						logger.info(TREE_LABEL + " Normalized plot saved...");
					else
						logger.warn(TREE_LABEL + " Error while saving normalized plot");
				}
				if (showAnalysis) nPlot.show();
			}

			// Tables
			if (tableOutputDescription.contains("Detailed")) {
				final ShollTable dTable = new ShollTable(lStats, nStats);
				dTable.listProfileEntries();
				if (!dTable.hasContext()) dTable.setContext(getContext());
				if (validDir) {
					if (dTable.saveSilently(new File(saveDir, TREE_LABEL + "_profile.csv")))
						logger.info(TREE_LABEL + " Detailed table saved...");
					else
						logger.warn(TREE_LABEL + " Error while saving detailed table");
				}
				if (showAnalysis) dTable.show();
			}

			final ShollTable sTable = new ShollTable(lStats, nStats);
			String header = TREE_LABEL;
			if (!filterChoice.contains("None")) header += "(" + filterChoice + ")";
			if (!sTable.hasContext()) sTable.setContext(getContext());
			sTable.summarize(commonSummaryTable, header);
			threadService.queue(ShollAnalysisBulkTreeCmd.this::updateDisplayAndSaveCommonSummaryTable);
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
