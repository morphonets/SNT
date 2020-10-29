/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import java.awt.Color;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;

import org.scijava.Cancelable;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.DynamicCommand;
import org.scijava.command.Interactive;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.thread.ThreadService;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import sc.fiji.snt.SNT;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollOverlay;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.math.ShollStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GUIHelper;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.Logger;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.ShollPoint;

/**
 * Implements both the "Analyze:Sholl:Sholl Analysis (From Tracings)..." and
 * SNT's "Start Sholl Analysis..." commands
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = { @Menu(label = "Analyze"), @Menu(
	label = "Sholl", weight = 0.01d), @Menu(
		label = "Sholl Analysis (From Tracings)...") }, initializer = "init")
public class ShollAnalysisTreeCmd extends DynamicCommand implements Interactive,
	Cancelable
{

	@Parameter
	private CommandService cmdService;
	@Parameter
	private DisplayService displayService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private StatusService statusService;
	@Parameter
	private LUTService lutService;
	@Parameter
	private ThreadService threadService;

	/* Parameters */
	@Parameter(required = false, label = "File:",
		style = "extensions:eswc/swc/traces", callback = "fileChanged")
	private File file;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "Sampling:")
	private String HEADER1;

	@Parameter(label = "Path filtering", required = false, choices = { "None",
		"Selected paths", "Paths tagged as 'Axon'", "Paths tagged as 'Dendrite'",
		"Paths tagged as 'Custom'", "Paths tagged as 'Undefined'" })
	private String filterChoice;

	@Parameter(label = "Center", required = false, choices = { "Soma",
		"Root node(s)",
		"Root node(s): Primary axon(s)",
		"Root node(s): Primary (basal) dendrites(s)",
		"Root node(s): Primary apical dendrites(s)",
		}, callback = "centerChoiceChanged")
	private String centerChoice;

	@Parameter(label = "Radius step size", required = false, min = "0",
		callback = "stepSizeChanged")
	private double stepSize;

	@Parameter(label = "Preview", persist = false, callback = "overlayShells")
	private boolean previewShells;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "<br>Metrics:")
	private String HEADER2;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = "<html><i>Polynomial Fit:")
	private String HEADER2A;

	@Parameter(label = "Degree", callback = "polynomialChoiceChanged",
		required = false, choices = { "'Best fitting' degree",
			"None. Skip curve fitting", "Use degree specified below:" })
	private String polynomialChoice;

	@Parameter(label = "<html>&nbsp;", callback = "polynomialDegreeChanged",
		style = NumberWidget.SCROLL_BAR_STYLE)
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
		"Linear & normalized plots", "None. Show no plots" })
	private String plotOutputDescription;

	@Parameter(label = "Tables", choices = { "Detailed table", "Summary table",
		"Detailed & Summary tables", "None. Show no tables" })
	private String tableOutputDescription;

	@Parameter(required = false, label = "Annotations", choices = { "None",
		"Color coded nodes", "3D viewer labels image" })
	private String annotationsDescription;

	@Parameter(required = false, label = "Annotations LUT",
		callback = "lutChoiceChanged")
	private String lutChoice;

	@Parameter(required = false, label = "<html>&nbsp;")
	private ColorTable lutTable;

	@Parameter(required = false, callback = "saveChoiceChanged", label = "Save files", //
			description = ShollAnalysisImgCmd.HEADER_TOOLTIP
					+ "Wheter output files (tables, plots, mask) should be saved once displayed.")
	private boolean save;

	@Parameter(required = false, label = "Destination", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = ShollAnalysisImgCmd.HEADER_TOOLTIP
					+ "Destination directory. Ignored if \"Save files\" is deselected, or outputs are not savable.")
	private File saveDir;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = ShollAnalysisImgCmd.HEADER_HTML + "<br>&nbsp;")
	private String HEADER4;

	@Parameter(label = "Run Analysis", callback = "runAnalysis")
	private Button analyzeButton;

	@Parameter(label = " Options, Preferences and Resources... ",
		callback = "runOptions")
	private Button optionsButton;

	/* Parameters for SNT interaction */
	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private SNT snt;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private Tree tree;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private PointInImage center;

	/* Instance variables */
	private GUIHelper helper;
	private Logger logger;
	private AnalysisRunner analysisRunner;
	private Map<String, URL> luts;
	private Future<?> analysisFuture;
	private PreviewOverlay previewOverlay;
	private Profile profile;
	private ShollTable commonSummaryTable;
	private Display<?> detailedTableDisplay;
	private boolean multipleTreesExist;

	/* Interactive runs: References to previous outputs */
	private ShollPlot lPlot;
	private ShollPlot nPlot;

	/* Preferences */
	private int minDegree;
	private int maxDegree;

	@Override
	public void run() {
		// Do nothing. Actually analysis is performed by runAnalysis();
	}

	/*
	 * Triggered every time user interacts with prompt (NB: buttons in the prompt
	 * are excluded from this
	 */
	@Override
	public void preview() {
		// do nothing. It is all implemented by callbacks
	}

	@Override
	public void cancel() {
		if (previewOverlay != null) previewOverlay.removeShellOverlay();
	}

	@SuppressWarnings("unused")
	private void runAnalysis() throws InterruptedException {
		if (analysisFuture != null && !analysisFuture.isDone()) {
			threadService.queue(() -> {
				final boolean killExisting = new GuiUtils((snt != null) ? snt.getUI()
					: null).getConfirmation("An analysis is already running. Abort it?",
						"Ongoing Analysis");
				if (killExisting) {
					analysisFuture.cancel(true);
					analysisRunner.showStatus("Aborted");
				}
			});
			return;
		}
		if (snt != null) {
			logger.info("Retrieving filtered paths... ");
			final Tree filteredTree = getFilteredTree();
			tree.setBoundingBox(snt.getPathAndFillManager().getBoundingBox(false));
			if (filteredTree == null || filteredTree.isEmpty()) {
				cancelAndFreezeUI(
					"Tracings do not seem to contain Paths matching the filtering criteria.",
					"Invalid Filter");
				return;
			}
			logger.info("Considering " + filteredTree.size() + " out of " + tree
				.size());
			analysisRunner = new AnalysisRunner(filteredTree, center);
		}
		else {
			if (tree == null && multipleTreesExist) {
				multipleTreesExistError();
				return;
			}
			if (tree == null) {
				cancelAndFreezeUI("File does not seem to be valid", "Invalid File");
				return;
			}
			analysisRunner = new AnalysisRunner(tree, centerChoice);
			if (analysisRunner.parser.getCenter() == null) {
				cancelAndFreezeUI(
					"No paths match the center criteria. Please choose a different option from the \"Center\" choice list.",
					"Invalid Center");
				return;
			}
		}
		if (!validOutput()) {
			cancelAndFreezeUI("Analysis can only proceed if at least one type\n" +
				"of output (plot, table, annotation) is chosen.", "Invalid Output");
			return;
		}
		logger.info("Analysis started...");
		analysisFuture = threadService.run(analysisRunner);
	}

	private NormalizedProfileStats getNormalizedProfileStats(
		final Profile profile)
	{
		String normString = normalizerDescription.toLowerCase();
		if (normString.startsWith("default")) {
			normString = "Area/Volume";
		}
		if (tree.is3D()) {
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

	/* initializer method running before displaying prompt */
	protected void init() {
		helper = new GUIHelper(context());
		logger = new Logger(context(), "Sholl");
		final boolean calledFromSNT = snt != null;
		final boolean calledFromStandAloneRecViewer = snt == null && tree != null;
		multipleTreesExist = false;
		// Adjust Path filtering choices
		final MutableModuleItem<String> mlitm =
			(MutableModuleItem<String>) getInfo().getInput("filterChoice",
				String.class);
		if (calledFromSNT) {
			previewOverlay = new PreviewOverlay(snt.getImagePlus(), center
				.getUnscaledPoint());
			logger.setDebug(SNTUtils.isDebugMode());
			setLUTs();
			resolveInput("file");
			resolveInput("centerChoice");
			final ArrayList<String> filteredchoices = new ArrayList<>(mlitm
				.getChoices());
			if (!filteredchoices.contains("Selected paths")) filteredchoices.add(1,
				"Selected paths");
			mlitm.setChoices(filteredchoices);
		}
		else {
			resolveInput("previewShells");
			resolveInput("annotationsDescription");
			resolveInput("lutChoice");
			resolveInput("lutTable");
			resolveInput("snt");
			resolveInput("center");
			resolveInput("centerUnscaled");
			if (calledFromStandAloneRecViewer) {
				resolveInput("file");
			}
			else {
				resolveInput("tree");
				final ArrayList<String> filteredchoices = new ArrayList<>();
				for (final String choice : mlitm.getChoices()) {
					if (!choice.equals("Selected paths")) filteredchoices.add(choice);
				}
				mlitm.setChoices(filteredchoices);
			}
		}
		readPreferences();
		getInfo().setLabel("Sholl Analysis " + SNTUtils.VERSION);
		adjustFittingOptions();
	}

	private void setLUTs() {
		// see net.imagej.lut.LUTSelector
		luts = lutService.findLUTs();
		final ArrayList<String> choices = new ArrayList<>();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			choices.add(entry.getKey());
		}
		Collections.sort(choices);
		choices.add(0, "None");
		final MutableModuleItem<String> input = getInfo().getMutableInput(
			"lutChoice", String.class);
		input.setChoices(choices);
		input.setValue(this, lutChoice);
		lutChoiceChanged();
	}

	protected void lutChoiceChanged() {
		try {
			lutTable = lutService.loadLUT(luts.get(lutChoice));
		}
		catch (final Exception ignored) {
			// presumably "No Lut" was chosen
			lutTable = ShollUtils.constantLUT(Color.GREEN);
		}
		overlayShells();
	}

	private void readPreferences() {
		logger.debug("Reading preferences");
		minDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "minDegree",
			ShollAnalysisPrefsCmd.DEF_MIN_DEGREE);
		maxDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "maxDegree",
			ShollAnalysisPrefsCmd.DEF_MAX_DEGREE);
		if (saveDir == null || saveDir.getAbsolutePath().isEmpty())
			saveDir = new File(System.getProperty("user.home"));
	}

	private void adjustFittingOptions() {
		final MutableModuleItem<Integer> polynomialDegreeInput = getInfo()
			.getMutableInput("polynomialDegree", Integer.class);
		polynomialDegreeInput.setMinimumValue(minDegree);
		polynomialDegreeInput.setMaximumValue(maxDegree);
	}

	private void cancelAndFreezeUI(final String msg, final String title) {
		cancel(title);
		helper.errorPrompt(msg, title);
	}

	private void multipleTreesExistError() {
		cancel("Invalid File");
		GuiUtils.showHTMLDialog(
				"<html>"//
				+ "<p>The selected file seems to contain multiple rooted structures (i.e., disconnected primary paths). "//
				+ "Currently there is no automated way to <em>be certain</em> that such structures relate to the same cell, "//
				+ "so the analysis has been aborted to ensure it is not confounded by the presence of multiple cells."//
				+ "</p>"//
				+ "<p>If your file does contain data from a single cell, please use Path Manager&#39;s "//
				+ "<em>Edit &gt;Merge all Primary Paths into Shared Root...</em> command to merge disconnected paths. "//
				+ "More details on this issue can be found "
				+ "<a href='https://forum.image.sc/t/snt-sholl-analysis-bug-following-update/40057/7?u=tferr'>here</a>."//
				+ "</p>"//
				+ "<p>(If you think this limitation is a cumbersome annoyance, please provide feedback on how this "//
				+ "restriction should be lifted using the link above).</p>"//
				+ "</html>", "Multiple Roots Detected");
	}

	private double adjustedStepSize() {
		return Math.max(stepSize, 0);
	}

	private boolean validOutput() {
		boolean noOutput = plotOutputDescription.contains("None");
//		noOutput = noOutput && tableOutputDescription.contains("None");
		if (snt != null) noOutput = noOutput && annotationsDescription.contains(
			"None");
		return !noOutput;
	}

	private void errorIfSaveDirInvalid() {
		if (save && (saveDir == null || !saveDir.exists() || !saveDir.canWrite())) {
			save = false;
			helper.error("No files saved: Output directory is not valid or writable.", "Please Change Output Directory");
		}
	}

	private Tree getFilteredTree() {
		final String choice = filterChoice.toLowerCase();
		if (choice.contains("none")) {
			return tree;
		}
		if (choice.contains("selected")) {
			return new Tree(snt.getPathAndFillManager().getSelectedPaths());
		}
		boolean containsType = false;
		final Set<Integer> existingTypes = tree.getSWCTypes();
		final List<Integer> filteredTypes = new ArrayList<>();
		if (choice.contains("none")) {
			filteredTypes.addAll(Path.getSWCtypes());
			containsType = true;
		}
		else if (choice.contains("axon")) {
			filteredTypes.add(Path.SWC_AXON);
			containsType = existingTypes.contains(Path.SWC_AXON);
		}
		else if (choice.contains("dendrite")) {
			filteredTypes.add(Path.SWC_APICAL_DENDRITE);
			filteredTypes.add(Path.SWC_DENDRITE);
			containsType = existingTypes.contains(Path.SWC_APICAL_DENDRITE) ||
				existingTypes.contains(Path.SWC_DENDRITE);
		}
		else if (choice.contains("custom")) {
			filteredTypes.add(Path.SWC_CUSTOM);
			existingTypes.contains(Path.SWC_CUSTOM);
		}
		else if (choice.contains("undefined")) {
			filteredTypes.add(Path.SWC_UNDEFINED);
			existingTypes.contains(Path.SWC_UNDEFINED);
		}
		if (containsType) {
			try {
				return tree.subTree(filteredTypes.stream().mapToInt(Integer::intValue).toArray());
			} catch (final IllegalArgumentException ex) {
				logger.info("Connectiviy problem detected! The reconstruction is not a valid Tree!?");
				ex.printStackTrace();
				return null;
			}
		}
		return null;
	}

	/* callbacks */

	@SuppressWarnings("unused")
	private void fileChanged() {
		try {
			Collection<Tree> trees = Tree.listFromFile(file.getAbsolutePath());
			if (trees != null && trees.size() == 1) {
				tree = trees.iterator().next();
				multipleTreesExist = false;;
			} else if (trees != null && trees.size() > 1) {
				tree = null;
				multipleTreesExist = true;
			}
		}
		catch (final IllegalArgumentException | NullPointerException ex) {
			tree = null;
			multipleTreesExist = false;
		}
	}

	@SuppressWarnings("unused")
	/* Callback for stepSize */
	private void stepSizeChanged() {
		stepSize = Math.max(0, stepSize);
		overlayShells();
		normalizerDescriptionChanged();
	}

	/* Callback for stepSize && previewShells */
	private void overlayShells() {
		threadService.run(previewOverlay);
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
			cancelAndFreezeUI(normalizerDescription +
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

	private class AnalysisRunner implements Runnable {

		private final Tree tree;
		private final TreeParser parser;
		private LinearProfileStats lStats;
		private NormalizedProfileStats nStats;
		private ArrayList<Object> outputs = new ArrayList<>();

		public AnalysisRunner(final Tree tree, final String centerChoice) {
			this.tree = tree;
			parser = new TreeParser(tree);
			setCenterFromChoice(centerChoice);
			parser.setStepSize(adjustedStepSize());
		}

		public AnalysisRunner(final Tree tree, final PointInImage center) {
			this.tree = tree;
			parser = new TreeParser(tree);
			parser.setCenter(center);
			parser.setStepSize(adjustedStepSize());
		}

		private void setCenterFromChoice(final String centerChoice) {
			final String choice = centerChoice.toLowerCase();
			try {
				if (choice.contains("all")) {
					parser.setCenter(TreeParser.PRIMARY_NODES_ANY);
				}
				else if (choice.contains("soma")) {
					parser.setCenter(TreeParser.PRIMARY_NODES_SOMA);
				}
				else if (choice.contains("axon")) {
					parser.setCenter(TreeParser.PRIMARY_NODES_AXON);
				}
				else if (choice.contains("apical")) {
					parser.setCenter(TreeParser.PRIMARY_NODES_APICAL_DENDRITE);
				}
				else if (choice.contains("dendrite")) {
					parser.setCenter(TreeParser.PRIMARY_NODES_DENDRITE);
				}
			}
			catch (IllegalArgumentException | NullPointerException ignored) {
				// do nothing. We'll check for this later
			}
		}

		@Override
		public void run() {
			// while (!Thread.currentThread().isInterrupted()) {
			runAnalysis();
			// }
		}

		public void runAnalysis() {
			showStatus("Obtaining profile...");
			parser.setStepSize(adjustedStepSize());
			try {
				parser.parse();
			} catch (IllegalArgumentException ex) {
				cancelAndFreezeUI(ex.getMessage(), "Exception Occured");
				ex.printStackTrace();
				return;
			}
			profile = parser.getProfile();

			if (!parser.successful()) {
				cancelAndFreezeUI("No valid profile retrieved.", "Invalid Profile");
				showStatus("Sholl: No profile retrieved.");
				return;
			}

			// Linear profile stats
			lStats = new LinearProfileStats(profile);
			lStats.setLogger(logger);
			int primaryBranches;
			try {
				logger.debug("Retriving primary branches...");
				primaryBranches = new TreeAnalyzer(tree).getPrimaryBranches().size();
			} catch (IllegalArgumentException exc) {
				logger.debug("Failure... Structure is not a graph. Defaulting to primary paths");
				primaryBranches = new TreeAnalyzer(tree).getPrimaryPaths().size();
			}
			lStats.setPrimaryBranches(primaryBranches);

			if (polynomialChoice.contains("Best")) {
				showStatus("Computing 'Best Fit' Polynomial...");
				final int deg = lStats.findBestFit(minDegree, maxDegree, prefService);
				if (deg == -1) {
					helper.error(
						"Please adjust the options for 'best fit' polynomial using Options, Preferences & Resources...\n"
						null);
						"Polynomial Regression Failed");
				}
			}
			else if (polynomialChoice.contains("degree") && polynomialDegree > 1) {
				showStatus("Fitting polynomial...");
				try {
					lStats.fitPolynomial(polynomialDegree);
				}
				catch (final Exception ignored) {
					helper.error("Polynomial regression failed. Unsuitable degree?",
						null);
				}
			}

			/// Normalized profile stats
			nStats = getNormalizedProfileStats(profile);
			logger.debug("Sholl decay: " + nStats.getShollDecay());

			// Set Plots
			outputs = new ArrayList<>();
			showStatus("Preparing outputs...");
			if (plotOutputDescription.toLowerCase().contains("linear")) {
				lPlot = showOrRebuildPlot(lPlot, lStats);
				outputs.add(lPlot);
			}
			if (plotOutputDescription.toLowerCase().contains("normalized")) {
				nPlot = showOrRebuildPlot(nPlot, nStats);
				outputs.add(nPlot);
			}

			// Set tables
			if (tableOutputDescription.contains("Detailed")) {
				final ShollTable dTable = new ShollTable(lStats, nStats);
				dTable.listProfileEntries();
				if (detailedTableDisplay != null) {
					detailedTableDisplay.close();
				}
				detailedTableDisplay = displayService.createDisplay("Sholl-Profiles",
					dTable);
				outputs.add(dTable);
			}
			if (tableOutputDescription.contains("Summary")) {
				final ShollTable sTable = new ShollTable(lStats, nStats);
				if (commonSummaryTable == null) commonSummaryTable = new ShollTable();
				String header = "";
				if (snt == null) {
					header = file.getName();
				}
				else {
					header = "Analysis " + (commonSummaryTable.getRowCount() + 1);
					sTable.setContext(snt.getContext());
				}
				if (!filterChoice.contains("None")) header += "(" + filterChoice + ")";
				sTable.summarize(commonSummaryTable, header);
				updateAndDisplayCommonSummaryTable();
				outputs.add(commonSummaryTable);
			}

			if (snt != null) {
				if (annotationsDescription.contains("labels image")) {
					showStatus("Creating labels image...");
					final ImagePlus labelsImage = parser.getLabelsImage(snt.getImagePlus(), null);
					outputs.add(labelsImage);
					labelsImage.show();
				}
				if (annotationsDescription.contains("coded")) {
					showStatus("Color coding nodes...");
					final TreeColorMapper treeColorizer = new TreeColorMapper(snt
						.getContext());
					treeColorizer.map(tree, lStats, lutTable);
					if (snt != null) snt.updateAllViewers();
				}
				annotationsDescription = "None";
			}

			// Now save everything
			errorIfSaveDirInvalid();
			if (save) {
				int failures = 0;
				for (final Object output : outputs) {
					if (output instanceof ShollPlot) {
						if (!((ShollPlot)output).save(saveDir)) ++failures;
					}
					else if (output instanceof ShollTable) {
						final ShollTable table = (ShollTable)output;
						if (!table.hasContext()) table.setContext(getContext());
						if (!table.saveSilently(saveDir)) ++failures;
					}
					else if (output instanceof ImagePlus) {
						final ImagePlus imp = (ImagePlus)output;
						final File outFile = new File(saveDir, imp.getTitle());
						if (!IJ.saveAsTiff(imp, outFile.getAbsolutePath())) ++failures;
					}
				}
				if (failures > 0)
					helper.error("Some file(s) (" + failures + "/"+ outputs.size() +") could not be saved. \n"
							+ "Please ensure \"Destination\" directory is valid.", "IO Failure");
			}

			showStatus("Sholl Analysis concluded...");
		}

		private ShollPlot showOrRebuildPlot(ShollPlot plot,
			final ShollStats stats)
		{
			if (plot != null && plot.isVisible() && !plot.isFrozen()) {
				plot.rebuild(stats);
				showStatus("Plot updated...");
			}
			else {
				plot = new ShollPlot(stats);
				plot.show();
			}
			return plot;
		}

		private void updateAndDisplayCommonSummaryTable() {
			final String DISPLAY_NAME = "Sholl Metrics";
			final Display<?> display = displayService.getDisplay(DISPLAY_NAME);
			if (display != null && display.isDisplaying(commonSummaryTable)) {
				display.update();
			}
			else {
				displayService.createDisplay(DISPLAY_NAME, commonSummaryTable);
			}
		}

		private void showStatus(final String msg) {
			statusService.showStatus(msg);
		}
	}

	private class PreviewOverlay implements Runnable {

		private final ImagePlus imp;
		private final PointInCanvas centerUnscaled;
		private Overlay overlaySnapshot;
		private double endRadiusUnscaled;

		public PreviewOverlay(final ImagePlus imp,
			final PointInCanvas centerUnscaled)
		{
			this.imp = imp;
			this.centerUnscaled = centerUnscaled;
			overlaySnapshot = imp.getOverlay();
			if (overlaySnapshot == null) overlaySnapshot = new Overlay();
			// Shells will be drawn in pixel coordinates because the
			// image calibration is not aware of a Path's canvasOffset
			final PointInCanvas nw = new PointInCanvas(0, 0, 0);
			final PointInCanvas sw = new PointInCanvas(0, imp.getHeight(), 0);
			final PointInCanvas ne = new PointInCanvas(imp.getWidth(), 0, 0);
			final PointInCanvas se = new PointInCanvas(imp.getWidth(), imp
				.getHeight(), 0);
			endRadiusUnscaled = Math.max(centerUnscaled.distanceSquaredTo(nw),
				centerUnscaled.distanceSquaredTo(sw));
			endRadiusUnscaled = Math.max(endRadiusUnscaled, center.distanceSquaredTo(
				ne));
			endRadiusUnscaled = Math.sqrt(Math.max(endRadiusUnscaled, centerUnscaled
				.distanceSquaredTo(se)));
		}

		@Override
		public void run() {
			if (!previewShells) {
				removeShellOverlay();
				return;
			}
			if (imp == null) {
				helper.error("Image is not available. Cannot preview overlays.",
					"Image Not Available");
				previewShells = false;
			}
			if (centerUnscaled == null) {
				helper.error("Center position unknown. Cannot preview overlays.",
					"Center Not Available");
				previewShells = false;
			}
			try {
				final double unscaledStepSize = adjustedStepSize() / snt
					.getMinimumSeparation();
				final ArrayList<Double> radii = ShollUtils.getRadii(0, Math.max(
					unscaledStepSize, 1), endRadiusUnscaled);
				final Profile profile = new Profile();
				for (final double r : radii) {
					profile.add(new ProfileEntry(r, 0));
				}
				profile.setCenter(new ShollPoint(centerUnscaled.x, centerUnscaled.y,
					centerUnscaled.z));
				final ShollOverlay so = new ShollOverlay(profile);
				so.setShellsLUT(lutTable, ShollOverlay.RADIUS);
				so.addCenter();
				so.assignProperty("temp");
				imp.setOverlay(so.getOverlay());
			}
			catch (final IllegalArgumentException ignored) {
				return; // invalid parameters: do nothing
			}
		}

		public void removeShellOverlay() {
			if (imp == null || overlaySnapshot == null) {
				return;
			}
			ShollOverlay.remove(overlaySnapshot, "temp");
			imp.setOverlay(overlaySnapshot);
		}
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		final CommandService cmdService = ij.command();
		cmdService.run(ShollAnalysisTreeCmd.class, true, input);
	}
}
