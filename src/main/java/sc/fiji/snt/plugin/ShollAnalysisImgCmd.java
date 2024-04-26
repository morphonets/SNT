/*
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import java.awt.Rectangle;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.scijava.Cancelable;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.command.DynamicCommand;
import org.scijava.command.Interactive;
import org.scijava.convert.ConvertService;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.event.EventHandler;
import org.scijava.menu.MenuConstants;
import org.scijava.module.ModuleItem;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.thread.ThreadService;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.frame.Recorder;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.display.ImageDisplayService;
import net.imagej.event.DataDeletedEvent;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.ProfileProperties;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollOverlay;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser2D;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser3D;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.Logger;
import sc.fiji.snt.util.ShollPoint;


/**
 * Implements the Analyze:Sholl:Sholl Analysis (From Image)...
 * 
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = {
		@Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
		@Menu(label = "Neuroanatomy", weight = GuiUtils.DEFAULT_MENU_WEIGHT), //
		@Menu(label = "Sholl"), //
		@Menu(label = "Sholl Analysis (From Image)...") }, //
		initializer = "init", headless = false)
public class ShollAnalysisImgCmd extends DynamicCommand implements Interactive, Cancelable {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	private CommandService cmdService;
	@Parameter
	private ConvertService convertService;
	@Parameter
	private DatasetService datasetService;
	@Parameter
	private DisplayService displayService;
	@Parameter
	private ImageDisplayService imageDisplayService;
	@Parameter
	private LUTService lutService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private StatusService statusService;
	@Parameter
	private ThreadService threadService;


	/* constants */
	private static final List<String> NORM2D_CHOICES = Arrays.asList("Default", "Area", "Perimeter", "Annulus");
	private static final List<String> NORM3D_CHOICES = Arrays.asList("Default", "Volume", "Surface area", "Spherical shell");

	protected static final String HEADER_HTML = "<html><body><div style='font-weight:bold;'>";
	protected static final String HEADER_TOOLTIP = "<HTML><div WIDTH=650>";
	protected static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final int MAX_SPANS = 10;

	private static final String NO_IMAGE = "Image no longer available";
	private static final String NO_CENTER = "Invalid center";
	private static final String NO_RADII = "Invalid radii";
	private static final String NO_THRESHOLD = "Invalid threshold levels";
	private static final String NO_ROI = "No ROI detected";
	private static final String RUNNING = "Analysis currently running";
	private static final int SCOPE_IMP = 0;
	private static final int SCOPE_PROFILE = 1;
	private static final int SCOPE_ABORT = 2;
	private static final int SCOPE_CHANGE_DATASET = 3;
	private static final int SCOPE_OPTIONS = 4;

	/* Parameters */
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Shells:")
	private String HEADER1;

	@Parameter(label = "Starting radius", required = false, callback = "startRadiusStepSizeChanged", min = "0",
			style = NumberWidget.SCROLL_BAR_STYLE)
	private double startRadius;

	@Parameter(label = "Radius step size", required = false, callback = "startRadiusStepSizeChanged", min = "0",
			style = NumberWidget.SCROLL_BAR_STYLE)
	private double stepSize;

	@Parameter(label = "Ending radius", persist = false, required = false, callback = "endRadiusChanged", min = "0",
			style = NumberWidget.SCROLL_BAR_STYLE)
	private double endRadius;

	@Parameter(label = "Hemishells", required = false, callback = "overlayShells", choices = { "None. Use full shells",
			"Above center", "Below center", "Left of center", "Right of center" })
	private String hemiShellChoice = "None. Use full shells";

	@Parameter(label = "Preview", persist = false, callback = "overlayShells")
	private boolean previewShells;

	@Parameter(label = "Set Center from Active ROI", callback = "setCenterFromROI", persist = false)
	private Button centerButton;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Segmentation:")
	private String HEADER2;

	@Parameter(label = "Samples per radius", callback = "nSpansChanged", min = "1", max = "" + MAX_SPANS,
			style = NumberWidget.SCROLL_BAR_STYLE)
	private double nSpans;

	@Parameter(label = "Integration", callback = "nSpansIntChoiceChanged", choices = { "N/A", "Mean", "Median",
			"Mode" })
	private String nSpansIntChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "<br>Metrics:")
	private String HEADER3;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<html><i>Branching Indices:") //Schoenen
	private String HEADER3A;

	@Parameter(label = "Primary branches", callback = "primaryBranchesChoiceChanged", choices = {
			"Infer from starting radius", "Infer from multipoint ROI", "Use no. specified below:" })
	private String primaryBranchesChoice = "Infer from starting radius";

	@Parameter(label = EMPTY_LABEL, callback = "primaryBranchesChanged", min = "0", max = "100",
			stepSize = "1", style = NumberWidget.SCROLL_BAR_STYLE)
	private double primaryBranches; // FIXME: ClassCastException triggered if int??

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<html><i>Polynomial Fit:")
	private String HEADER3B;

	@Parameter(label = "Degree", callback = "polynomialChoiceChanged", required = false, choices = {
			"None. Skip curve fitting", "'Best fitting' degree", "Use degree specified below:" })
	private String polynomialChoice = "'Best fitting' degree";

	@Parameter(label = EMPTY_LABEL, callback = "polynomialDegreeChanged", stepSize="1",
			style = NumberWidget.SCROLL_BAR_STYLE)
	private double polynomialDegree; // FIXME: ClassCastException triggered if int??

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<html><i>Sholl Decay:")
	private String HEADER3C;

	@Parameter(label = "Method", choices = { "Automatically choose", "Semi-Log", "Log-log" })
	private String normalizationMethodDescription;

	@Parameter(label = "Normalizer", callback = "normalizerDescriptionChanged")
	private String normalizerDescription;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "<br>Output:")
	private String HEADER4;

	@Parameter(label = "Plots", callback="saveOptionsChanged", choices = { "Linear plot", "Normalized plot", "Linear & normalized plots",
			"Integrated density plot", "Cumulative: Linear plot", "Cumulative: Integrated density plot", "None. Show no plots" })
	private String plotOutputDescription;

	@Parameter(label = "Tables", callback="saveOptionsChanged", choices = { "Detailed table", "Summary table",
		"Detailed & Summary tables", "None. Show no tables" })
	private String tableOutputDescription;

	@Parameter(label = "Annotations", description="Point ROIs are not created when retrieving \"Norm. integrated density plot\"",
			callback = "annotationsDescriptionChanged",
		choices = { "ROIs (points only)", "ROIs (points and 2D shells)",
			"ROIs (points) and mask", "Mask", "None. Show no annotations" })
	private String annotationsDescription;

	@Parameter(label = "Annotations LUT", callback = "lutChoiceChanged",
			description = HEADER_TOOLTIP + "The mapping LUT used to render ROIs and mask.")
	private String lutChoice;

	@Parameter(required = false, label = EMPTY_LABEL)
	private ColorTable lutTable;

	@Parameter(required = false, callback = "saveChoiceChanged", label = "Save files", //
			description = HEADER_TOOLTIP + "Wheter output files (tables, plots, mask) should be saved once displayed."
					+ "Note that the analyzed image itself is not saved.")
	private boolean save;

	@Parameter(required = false, label = "Destination", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = HEADER_TOOLTIP + "Destination directory. Ignored if \"Save files\" is deselected, "
					+ "or outputs are not savable.")
	private File saveDir;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "<br>Run:")
	private String HEADER5;

	@Parameter(label = "Action", required = false, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, //
			visibility = ItemVisibility.TRANSIENT, //
			callback = "setAnalysisScope", choices = { "Analyze image", "Re-analyze parsed data",
					"Abort current analysis", "Change image...", "Options & Preferences"})
	private String analysisAction;

	@Parameter(label = "<html><b>Analyze Image</html>", callback = "runAnalysis")
	private Button analyzeButton;

	@Parameter(persist = false, required = false, visibility = ItemVisibility.MESSAGE, //
			label = HEADER_HTML + EMPTY_LABEL)
	private String HEADER6;
//
//	@Parameter(label = " Options, Preferences and Resources... ", callback = "runOptions")
//	private Button optionsButton;

	/* Instance variables */
	private Dataset dataset;
	private GuiUtils helper;
	private Logger logger;
	private PreviewOverlay previewOverlay;
	private Map<String, URL> luts;
	private ImagePlus imp;
	private ImageParser parser;
	private Overlay overlaySnapshot;
	private Calibration cal;
	private ShollPoint center;
	private double voxelSize;
	private boolean twoD;
	private double maxPossibleRadius;
	private int posC;
	private int posT;
	private double upperT;
	private double lowerT;
	private Thread analysisThread;
	private AnalysisRunner analysisRunner;
	private Profile profile;
	private int scope;
	private ShollTable commonSummaryTable;
	private Display<?> detailedTableDisplay;

	/* Preferences */
//	private boolean autoClose;
	private int minDegree;
	private int maxDegree;


	@EventHandler
	public void onEvent(final DataDeletedEvent evt) {
		if (evt.getObject().equals(dataset)) {
			imp = null;
			setAnalysisScope();
			logger.debug(evt);
		}
	}

	@Override
	public void run() {
		// the code here gets called once the prompt is displayed.
		// There is no "OK" button in the prompt, so we don't need anything here
		// All the code is run from callbacks
	}

	/*
	 * Triggered every time user interacts with prompt (NB: buttons in the
	 * prompt are excluded from this
	 */
	@Override
	public void preview() {
		if (imp == null) {
			// We could call cancelAndFreezeUI(NO_IMAGE); but that can become too annoying easily
			// we'll reset some fields instead and let the pop-up error appear when analysis cannot
			// progress
			previewShells = false;
			setAnalysisScope(); // update analysisButotn

		} else if (dataset != imageDisplayService.getActiveDataset()) {
			// uiService.getDisplayViewer(impDisplay).getWindow().requestFocus();
			imp.getWindow().requestFocus(); // Only works in legacy mode
		}
	}

	private boolean validRequirements(final boolean includeOngoingAnalysis) {
		String cancelReason = "";
		if (imp == null) {
			cancelReason = NO_IMAGE;
		} else if (includeOngoingAnalysis && ongoingAnalysis()) {
			cancelReason = RUNNING;
		} else {
			cancelReason = validateRequirements(true);
		}
		final boolean successfullCheck = cancelReason.isEmpty();
		if (!successfullCheck)
			cancelAndFreezeUI(cancelReason);
		return successfullCheck;
	}

	private boolean ongoingAnalysis() {
		return analysisThread != null && analysisThread.isAlive();
	}

	protected void runAnalysis() throws InterruptedException {
		if (Recorder.record) {
			Recorder.recordString(
					  "// N.B.: Currently,recorded instances of the Sholl Analysis prompt may not allow for \n" //
					+ "// fully automated macros(see e.g., https://github.com/imagej/imagej-legacy/pull/239.)\n" //
					+ "// Please have a look at the example scripts in Templates>Neuroanatomy>\n"//
					+ "// for more robust ways to automate Sholl. E.g., the script\n"//
					+ "// 'Sholl_Extract_Profile_From_Image_Demo.py' exemplifies how to parse\n"//
					+ "// an image programmatically. Alternatively, the Legacy IJ1 command remains\n"//
					+ "// available with historical support for recorded calls.\n");
		}
		switch (scope) {
		case SCOPE_IMP:
			if (imp == null) {
				if (twoD) attemptToLoadDemoImage(NO_IMAGE);
				if (imp == null) {
					analysisAction = "Change image...";
					setAnalysisScope();
					return;
				}
			}
			if (!validRequirements(true))
				return;
			updateHyperStackPosition(); // Did channel/frame changed?
			previewShells = false;
			imp.setOverlay(overlaySnapshot);
			parser.reset();
			parser.setRetrieveIntDensities(plotOutputDescription.toLowerCase().contains("integrated"));
			startAnalysisThread(false);
			break;
		case SCOPE_PROFILE:
			if (!validProfileExists())
				return;
			startAnalysisThread(true);
			break;
		case SCOPE_ABORT:
			if (analysisRunner == null || !ongoingAnalysis())
				return;
			statusService.showStatus(0, 0, "Analysis aborted...");
			analysisRunner.terminate();
			logger.debug("Analysis aborted...");
			break;
		case SCOPE_CHANGE_DATASET:
			//temporary use IJ1 code the following is not working reliably
			// threadService.newThread(() -> getNewDataset()).start();
			getNewDatasetUsingLegacyIJ1();
			break;
		case SCOPE_OPTIONS:
			runOptions();
			break;
		default:
			throw new IllegalArgumentException("Unrecognized option: " + scope);
		}
	}

	private void getNewDatasetUsingLegacyIJ1() {
		final List<String> choices = new ArrayList<>(Arrays.asList(ij.WindowManager.getImageTitles()));
		if (choices.size() < 2) {
			if (twoD)
				attemptToLoadDemoImage("No other images are currently open");
			else
				helper.error("No other images are currently open");
			return;
		}
		if (imp != null)
			choices.remove(imp.getTitle());
		if (!choices.contains("Drosophila_ddaC_Neuron.tif"))
			choices.add("Demo Image");
		final String choice = helper.getChoice("Choose new image", "Choose New Image", choices.toArray(new String[0]),
				choices.get(0));
		if (choice == null) // user pressed cancel
			return;
		final ImagePlus newImp;
		if ("Demo Image".equals(choice)) {
			attemptToLoadDemoImage(null);
			newImp = imp;
		} else {
			newImp = ij.WindowManager.getImage(choice);
		}
		if (newImp == null) {
			helper.error(
					"Somehow, could not retrieve the chosen image. Please make it is frontmost, and restart the plugin.");
			return;
		}
		if (twoD != (newImp.getNSlices() == 1)) {
			helper.error("Z-dimension of new dataset differs which will require a rebuild of the main dialog. "
					+ "Please restart the command to analyze " + newImp.getTitle(), "Not a Suitable Choice");
			return;
		}
		loadDataset(newImp);
		imp.getWindow().toFront();
		helper.centeredMsg("Target image is now " + newImp.getTitle(), SNTUtils.getReadableVersion());
		logger.debug("Changed scope of analysis to: " + newImp.getTitle());
	}

	@SuppressWarnings("unused")
	private void getNewDataset() {
		final List<Dataset> list = datasetService.getDatasets();
		if (list == null || list.size() < 2) {
			if (twoD) {
				attemptToLoadDemoImage("Initial image is no longer available");
			} else {
				helper.error("No other images are open.", "No Other Images");
			}
			return;
		}
		try {
			final Map<String, Object> input= new HashMap<>();
			input.put("datasetToIgnore", dataset);
			final Future<CommandModule> cmdModule = cmdService.run(ChooseDataset.class, true, input);
			cmdModule.get();
			// FIXME: this throws a ClassCastException. not sure why
			//ImageDisplay imgDisplay = (ImageDisplay) cmdModule.get().getOutput("chosen");
		} catch (final NullPointerException | InterruptedException | ExecutionException | ClassCastException exc) {
			if (logger.isDebug()) exc.printStackTrace();
		}

		final String result = prefService.get(ChooseDataset.class, "choice", "");
		if (result.isEmpty()) {
			return; // ChooseImgDisplay canceled / not initialized
		}
		Dataset newDataset = null;
		for (final Dataset dataset : datasetService.getDatasets()) {
			if (result.equals(dataset.getName())) {
				newDataset = dataset;
				break;
			}
		}
		if (newDataset == null) {
			helper.error("Could not retrieve new dataset", null);
			logger.debug("Failed to change dataset");
			return;
		}
		ImagePlus newImp = null;
		try {
			newImp = convertService.convert(newDataset, ImagePlus.class);
		} catch (final UnsupportedOperationException exc) {
			// grrrr... this keeps happening with perfectly valid images //TODO: report this
			// fallback to IJ1 retrieval
			if (logger.isDebug()) exc.printStackTrace();
			newImp = ij.WindowManager.getImage(result);
		}
		if (newImp == null) {
			helper.error("Somehow, could not retrieve the chosen image. Please make it frontmost, and restart the plugin.");
			return;
		}
		if (twoD != (newImp.getNSlices() == 1)) {
			helper.error("Z-dimension of new dataset differs which will require a rebuild of the main dialog. " +
				"Please restart the command to analyze " + newImp.getTitle(),
				"Not a Suitable Choice");
			return;
		}
		loadDataset(newImp);
		imp.getWindow().toFront();
		helper.centeredMsg("Target image is now " + newImp.getTitle(), SNTUtils.getReadableVersion());
		logger.debug("Changed scope of analysis to: " + newImp.getTitle());
	}

	private void startAnalysisThread(final boolean skipImageParsing) {
		analysisRunner = new AnalysisRunner(parser);
		analysisRunner.setSkipParsing(skipImageParsing || plotOutputDescription.toLowerCase().contains("integrated") != parser.isRetrieveIntDensitiesSet());
		statusService.showStatus("Analysis started");
		logger.debug("Analysis started...");
		if (twoD) {
			logger.debug("Parsing 2D image: Single thread operation");
		} else {
			logger.debug(String.format("Parsing 3D image using %d threads ", SNTPrefs.getThreads()));
		}
		analysisThread = threadService.newThread(analysisRunner);
		threadService.queue(analysisThread);
		savePreferences();
//		if (autoClose && !isCanceled()) {
//			try {  //FIXME: this kludge will only work if prompt has focus
//				final Robot r = new Robot();
//				r.keyPress(KeyEvent.VK_ESCAPE);
//			} catch (final AWTException exc) {
//				logger.debug(exc);
//			}
//		}
	}

	private boolean validProfileExists() {
		return getProfile() != null && !getProfile().isEmpty();
	}

	private NormalizedProfileStats getNormalizedProfileStats(final Profile profile) {
		String normalizerString = normalizerDescription.toLowerCase();
		if (normalizerString.startsWith("default")) {
			normalizerString = (twoD) ? NORM2D_CHOICES.get(1) : NORM3D_CHOICES.get(1);
		}
		final int normFlag = NormalizedProfileStats.getNormalizerFlag(normalizerString);
		final int methodFlag = NormalizedProfileStats.getMethodFlag(normalizationMethodDescription);
		return new NormalizedProfileStats(profile, normFlag, methodFlag);
	}

	protected void setProfile(final Profile profile) {
		this.profile = profile;
	}

	protected Profile getProfile() {
		return profile;
	}

	/* initializer method running before displaying prompt */
	protected void init() {
		helper = new GuiUtils();
		logger = new Logger(context(), "Sholl");
		readPreferences();
		imp = ImpUtils.getCurrentImage();
		if (imp == null)
			attemptToLoadDemoImage("No image is currently open");
		if (imp == null) {
			helper.error("A dataset is required but none was found", null);
			cancel(null);
			return;
		}
		getInfo().setLabel("Sholl Analysis " + SNTUtils.VERSION);
		previewOverlay = new PreviewOverlay();
		setLUTs();
		loadDataset(imp);
		adjustSamplingOptions();
		adjustFittingOptions();
		setNormalizerChoices();
	}

	private void readPreferences() {
		logger.debug("Reading preferences");
//		autoClose = prefService.getBoolean(Prefs.class, "autoClose", Prefs.DEF_AUTO_CLOSE);
		minDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "minDegree", ShollAnalysisPrefsCmd.DEF_MIN_DEGREE);
		maxDegree = prefService.getInt(ShollAnalysisPrefsCmd.class, "maxDegree", ShollAnalysisPrefsCmd.DEF_MAX_DEGREE);
		startRadius = prefService.getDouble(getClass(), "startRadius", startRadius);
		endRadius = prefService.getDouble(getClass(), "endRadius", endRadius);
		stepSize = prefService.getDouble(getClass(), "stepSize", stepSize);
		nSpans = prefService.getDouble(getClass(), "nSpans", nSpans);
		nSpansIntChoice = prefService.get(getClass(), "nSpansIntChoice", nSpansIntChoice);
		primaryBranchesChoice = prefService.get(getClass(), "primaryBranchesChoice", primaryBranchesChoice);
		primaryBranches = prefService.getDouble(getClass(), "primaryBranches", primaryBranches);
		polynomialChoice = prefService.get(getClass(), "polynomialChoice", polynomialChoice);
		polynomialDegree = prefService.getDouble(getClass(), "polynomialDegree", polynomialDegree);
		normalizationMethodDescription = prefService.get(getClass(), "normalizationMethodDescription", "Automatically choose");
		annotationsDescription = prefService.get(getClass(), "annotationsDescription", "ROIs (points and 2D shells)");
		plotOutputDescription = prefService.get(getClass(), "plotOutputDescription", "Linear plot");
		lutChoice = prefService.get(getClass(), "lutChoice", "mpl-viridis.lut");
	}

	private void savePreferences() {
		logger.debug("Saving preferences");
//		autoClose = prefService.getBoolean(Prefs.class, "autoClose", Prefs.DEF_AUTO_CLOSE);
		prefService.put(ShollAnalysisPrefsCmd.class,  "minDegree", minDegree);
		prefService.put(ShollAnalysisPrefsCmd.class, "maxDegree", maxDegree);
		prefService.put(getClass(), "startRadius", startRadius);
		prefService.put(getClass(), "endRadius", endRadius);
		prefService.put(getClass(), "stepSize", stepSize);
		prefService.put(getClass(), "nSpans", nSpans);
		prefService.put(getClass(), "nSpansIntChoice", nSpansIntChoice);
		prefService.put(getClass(), "primaryBranchesChoice", primaryBranchesChoice);
		prefService.put(getClass(), "primaryBranches", primaryBranches);
		prefService.put(getClass(), "polynomialChoice", polynomialChoice);
		prefService.put(getClass(), "polynomialDegree", polynomialDegree);
		prefService.put(getClass(), "normalizationMethodDescription", normalizationMethodDescription);
		prefService.put(getClass(), "annotationsDescription", annotationsDescription);
		prefService.put(getClass(), "plotOutputDescription", plotOutputDescription);
		prefService.put(getClass(), "lutChoice", lutChoice);
	}


	private void loadDataset(final ImagePlus imp) {
		this.imp = imp;
		dataset = convertService.convert(imp, Dataset.class);
		twoD = dataset.getDepth() == 1;
		posC = imp.getC();
		posT = imp.getFrame();
		cal = imp.getCalibration();
		overlaySnapshot = imp.getOverlay();
		initializeParser();
		voxelSize = parser.getIsotropicVoxelSize();
		adjustRadiiInputs(true);
		center = getCenterFromROI(true);
	}

	private void initializeParser() {
		parser = (twoD) ? new ImageParser2D(imp, context()) : new ImageParser3D(imp, context());
	}

	private void adjustRadiiInputs(final boolean startUpAdjust) {
		maxPossibleRadius = parser.maxPossibleRadius();
		final List<String> names = Arrays.asList("startRadius", "stepSize", "endRadius");
		final List<String> labels = Arrays.asList("Start radius", "Step size", "End radius");
		final String unit = cal.getUnit();
		for (int i = 0; i < names.size(); i++) {
			final MutableModuleItem<Double> mItem = getInfo().getMutableInput(names.get(i), Double.class);
			mItem.setMaximumValue(maxPossibleRadius);
			if (startUpAdjust) {
				mItem.setStepSize(voxelSize);
				mItem.setLabel(labels.get(i) + " (" + unit + ")");
			}
		}
	}

	private void adjustSamplingOptions() {
		try {
			if (!twoD) {
				final ModuleItem<String> ignoreIsolatedVoxelsInput = getInfo().getInput("HEADER2", String.class);
				removeInput(ignoreIsolatedVoxelsInput);
				final MutableModuleItem<Double> nSpansInput = getInfo().getMutableInput("nSpans", Double.class);
				removeInput(nSpansInput);
				final MutableModuleItem<String> nSpansIntChoiceInput = getInfo().getMutableInput("nSpansIntChoice",
						String.class);
				removeInput(nSpansIntChoiceInput);
			}
		} catch (NullPointerException npe) {
			logger.debug(npe);
		}
	}

	private void adjustFittingOptions() {
			final MutableModuleItem<Double> polynomialDegreeInput = getInfo()
					.getMutableInput("polynomialDegree", Double.class);
			polynomialDegreeInput.setMinimumValue((double) minDegree);
			polynomialDegreeInput.setMaximumValue((double) maxDegree);
	}

	protected void setAnalysisScope() {
		final MutableModuleItem<Button> aButton = getInfo().getMutableInput("analyzeButton", Button.class);
		String label;
		if (analysisAction.contains("Analyze image")) {
			scope = SCOPE_IMP;
			if (imp != null) {
				final String title = imp.getTitle().substring(0, Math.min(imp.getTitle().length(), 40));
				label = "Analyze " + title;
			} else {
				label = NO_IMAGE;
			}
		} else if (analysisAction.contains("Abort")) {
			scope = SCOPE_ABORT;
			if (ongoingAnalysis()) {
				label = "Press to abort";
			} else {
				label = "No analysis is currently running";
			}
		} else if (analysisAction.contains("parsed")) {
			scope = SCOPE_PROFILE;
			if (validProfileExists()) {
				label = "Press to re-run analysis";
			} else {
				label = "No profile has yet been obtained";
			}
		} else if (analysisAction.contains("Change")) {
			scope = SCOPE_CHANGE_DATASET;
			label = "Choose new image...";
		} else if (analysisAction.contains("Options")) {
			scope = SCOPE_OPTIONS;
			label = "Options Prompt...";
		} else
			label = analysisAction;
		aButton.setLabel(String.format("<html><b>%s</html>", label));
	}

	private void setNormalizerChoices() {
		final List<String> choices = (twoD) ? NORM2D_CHOICES : NORM3D_CHOICES;
		final MutableModuleItem<String> mItem = getInfo().getMutableInput("normalizerDescription", String.class);
		mItem.setChoices((twoD) ? NORM2D_CHOICES : NORM3D_CHOICES);
		mItem.setValue(this, choices.get(0));
	}

	private boolean validRadiiOptions() {
		return (!Double.isNaN(startRadius) && !Double.isNaN(stepSize) && !Double
			.isNaN(endRadius) && endRadius > startRadius);
	}

	private ShollPoint getCenterFromROI(final boolean setEndRadius) {
		final Roi roi = imp.getRoi();
		if (roi == null)
			return null;
		if (roi.getType() == Roi.LINE) {
			final Line line = (Line) roi;
			if (setEndRadius)
				endRadius = line.getLength();
			return new ShollPoint(line.x1, line.y1, imp.getZ(), cal);
		}
		if (roi.getType() == Roi.POINT) {
			final Rectangle rect = roi.getBounds();
			return new ShollPoint(rect.x, rect.y, imp.getZ(), cal);
		}
		if (setEndRadius)
				endRadius = roi.getFeretsDiameter() / 2;
		final double[] ctd = roi.getContourCentroid();
		return new ShollPoint((int) Math.round(ctd[0]), (int) Math.round(ctd[1]), imp
			.getZ(), cal);
	}

	protected boolean updateHyperStackPosition() {
		if (imp == null)
			return false;
		final boolean posChanged = imp.getC() != posC || imp.getFrame() != posT;
		if (!posChanged)
			return false;
		final String oldPosition = "Channel " + posC + ", Frame " + posT;
		final String newPosition = "Channel " + imp.getC() + ", Frame " + imp.getFrame();
		final String msg = "Scope of analysis is currently %s. Update scope to active position (%s)?";
		final Result result = helper.yesNoPrompt(String.format(msg, oldPosition, newPosition),
				"Dataset Position Changed");
		if (result == Result.YES_OPTION) {
			posC = imp.getC();
			posT = imp.getFrame();
			initializeParser();// call parser.setPosition();
			readThresholdFromImp();
			return true;
		}
		return false;
	}

	protected void setCenterFromROI() {
		if (imp == null) {
			proposeNewImageIfImageClosed();
			return;
		}
		final ShollPoint newCenter = getCenterFromROI(false);
		if (newCenter == null) {
			cancelAndFreezeUI(NO_ROI);
			return;
		}
		if (center != null && center.equals(newCenter)) {
			helper.error("ROI already defines the same center currently in use (" + centerDescription()
					+ "). No changes were made.", "Center Already Defined");
			return;
		}
		if (center != null && newCenter.z != center.z) {
			final Result result = helper.yesNoPrompt(
					String.format("Current center was set at Z-position %s. Move center to active Z-position %s?",
							ShollUtils.d2s(center.z), ShollUtils.d2s(newCenter.z)),
					"Z-Position Changed");
			if (result == Result.NO_OPTION)
				newCenter.z = center.z;
		}
		center = newCenter;
		if (!previewShells) {
			helper.centeredMsg("New center set to " + centerDescription(), "Center Updated");
		}
		overlayShells();
	}

	private String centerDescription() {
		final StringBuilder sb = new StringBuilder();
		sb.append("X=").append(ShollUtils.d2s(center.x));
		sb.append(" Y=").append(ShollUtils.d2s(center.y));
		if (!twoD)
			sb.append(" Z=").append(ShollUtils.d2s(center.z));
		if (imp.getNChannels() > 1)
			sb.append(" C=").append(posC);
		if (imp.getNFrames() > 1)
			sb.append(" T=").append(posT);
		return sb.toString();
	}

	private void cancelAndFreezeUI(final String cancelReason) {
		String uiMsg;
		switch (cancelReason) {
		case NO_IMAGE:
			previewShells = false;
			annotationsDescription = "None. Show no annotations";
			analysisAction = "Change image...";
			setAnalysisScope();
			uiMsg = NO_IMAGE;
			break;
		case NO_CENTER:
			previewShells = false;
			uiMsg = "Please set an ROI, then press \"" + "Set New Center from Active ROI\". "
					+ "Center coordinates will be defined by the ROI's centroid.";
			break;
		case NO_RADII:
			previewShells = false;
			uiMsg = "Ending radius and Radius step size must be within range.";
			break;
		case NO_THRESHOLD:
			uiMsg = "Image is not segmented. Please adjust threshold levels";
			if (imp.getType() == ImagePlus.COLOR_RGB)
				uiMsg += ".<br><br>Since applying a threshold to an RGB image is an ambigous operation, "
						+ "you will need to first convert the image to a multichannel composite using IJ's "
						+ " 'Channels Tool'. This will allow single channels to be parsed";
			break;
		case RUNNING:
			uiMsg = "An analysis is currently running. Please wait...";
			break;
		default:
			if (cancelReason.contains(",")) {
				uiMsg = "Image cannot be analyzed. Muliple invalid requirements:<br>- "
						+ cancelReason.replace(", ", "<br>- ");
			} else {
				uiMsg = cancelReason;
			}
			break;
		}
		cancel(cancelReason);
		// previewShells = false;
		helper.error(uiMsg + ".", null);
	}

	private boolean readThresholdFromImp() {
		boolean successfulRead = true;
		if (imp.getProcessor().isBinary()) {
			lowerT = 1;
			upperT = 255;
		} else if (imp.isThreshold()) {
			lowerT = imp.getProcessor().getMinThreshold();
			upperT = imp.getProcessor().getMaxThreshold();
		} else if (plotOutputDescription.toLowerCase().contains("integrated")) {
			lowerT = Double.MIN_VALUE;
			upperT = Double.MAX_VALUE;
		} else {
			successfulRead = false;
		}
		return successfulRead;
	}

	private void attemptToLoadDemoImage(final String promptMsgIgnoredIfNull) {
		if (promptMsgIgnoredIfNull == null || helper.getConfirmation(promptMsgIgnoredIfNull + ". Run analysis on demo image?", "Open Demo Image?")) {
			imp = ShollUtils.sampleImage();
			if (imp == null)
				helper.error("Demo image could not be loaded.", null);
			else {
				loadDataset(imp);
				imp.show();
			}
		}
	}

	private void setLUTs() {
		// see net.imagej.lut.LUTSelector
		luts = lutService.findLUTs();
		final ArrayList<String> choices = new ArrayList<>();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			choices.add(entry.getKey());
		}
		Collections.sort(choices);
		choices.add(0, "No LUT. Use active ROI color");
		final MutableModuleItem<String> input = getInfo().getMutableInput("lutChoice", String.class);
		input.setChoices(choices);
		input.setValue(this, lutChoice);
		lutChoiceChanged();
	}

	private double adjustedStepSize() {
		return Math.max(stepSize, voxelSize);
	}

	/* callbacks */
	protected void startRadiusStepSizeChanged() {
		if (startRadius > endRadius || stepSize > endRadius)
			endRadius = Math.min(endRadius + adjustedStepSize(), maxPossibleRadius);
		previewShells = previewShells && validRadiiOptions();
		overlayShells();
	}

	protected void endRadiusChanged() {
		if (endRadius < startRadius + stepSize)
			startRadius = Math.max(endRadius - adjustedStepSize(), 0);
		previewShells = previewShells && validRadiiOptions();
		overlayShells();
	}

	protected void nSpansChanged() {
		nSpansIntChoice = (nSpans == 1) ? "N/A" : "Mean";
		if (previewShells)
			overlayShells();
	}

	protected void nSpansIntChoiceChanged() {
		int nSpansBefore = (int)nSpans;
		if (nSpansIntChoice.contains("N/A"))
			nSpans = 1;
		else if (nSpans == 1)
			nSpans++;
		if (previewShells && nSpansBefore != nSpans)
			overlayShells();
	}

	protected void polynomialChoiceChanged() {
		if (!polynomialChoice.contains("specified")) {
			polynomialDegree = 0;
		} else if (polynomialDegree == 0) {
			polynomialDegree = Math.round((float) (minDegree + maxDegree) / 2);
		}
	}

	protected void normalizerDescriptionChanged() {
		if (stepSize < voxelSize
				&& (normalizerDescription.contains("Annulus") || normalizerDescription.contains("shell"))) {
			helper.error(normalizerDescription + " normalization requires radius step size to be ≥ "
					+ ShollUtils.d2s(voxelSize) + cal.getUnit(), null);
			normalizerDescription = (twoD) ? NORM2D_CHOICES.get(0) : NORM3D_CHOICES.get(0);
		}
	}

	protected void annotationsDescriptionChanged() {
		if (annotationsDescription.contains("None")) {
			lutChoice = "No LUT. Use active ROI color";
			lutChoiceChanged();
		}
		saveOptionsChanged();
	}

	protected void polynomialDegreeChanged() {
		if (polynomialDegree == 0)
			polynomialChoice = "'Best fitting' degree";
		else
			polynomialChoice = "Use degree specified below:";
	}

	protected void primaryBranchesChoiceChanged() {
		if (primaryBranchesChoice.contains("starting radius")) {
			primaryBranches = 0;
		} else if (primaryBranchesChoice.contains("multipoint") && imp != null) {
			final Roi roi = imp.getRoi();
			if (roi == null || roi.getType() != Roi.POINT) {
				helper.error("Please activate a multipoint ROI marking primary branches.", "No Multipoint ROI Exists");
				primaryBranchesChoice = "Infer from starting radius";
				primaryBranches = 0;
				return;
			}
			final PointRoi point = (PointRoi) roi;
			primaryBranches = point.getCount(point.getCounter());
		} else if (primaryBranches == 0)
			primaryBranches = 1;
	}

	protected void primaryBranchesChanged() {
		if (primaryBranches == 0)
			primaryBranchesChoice = "Infer from starting radius";
		else
			primaryBranchesChoice = "Use no. specified below:";
	}

	protected void lutChoiceChanged() {
		try {
			lutTable = lutService.loadLUT(luts.get(lutChoice));
		} catch (final Exception ignored) {
			// presumably "No Lut" was chosen by user
			lutTable = ShollUtils.constantLUT(Roi.getColor());
		}
		if (previewShells) overlayShells();
	}

	@SuppressWarnings("unused")
	private void saveChoiceChanged() {
		if (save && saveDir == null)
			saveDir = SNTPrefs.lastknownDir();
	}

	private void saveOptionsChanged() {
		if (plotOutputDescription.startsWith("None") && tableOutputDescription.startsWith("None")
				&& !annotationsDescription.contains("mask"))
			save = false;
	}

	private String errorIfSaveDirInvalid() {
		if (save && (saveDir == null || !saveDir.exists() || !saveDir.canWrite())) {
			save = false;
			return "<dt>Invalid Output Directory</dt>"
					+ "<dd>No files saved: Output directory is not valid or writable.</dd>";
		}
		return "";
	}

	private String errorIfInvalidOverlay() {
		if (!annotationsDescription.contains("None") && imp == null) {
			annotationsDescription = "None. Show no annotations";
			return "<dt>" + NO_IMAGE + "</dt>"
					+ "<dd>ROIs could not be added to the image overlay</dd>";
		}
		return "";
	}

	private void proposeNewImageIfImageClosed() {
		final List<Dataset> list = datasetService.getDatasets();
		final boolean imagesExist = list != null && !list.isEmpty();
		if (imagesExist && helper.getConfirmation("Initial image is no longer available.", NO_IMAGE)) {
			analysisAction = "Change image...";
			setAnalysisScope();
			try {
				runAnalysis();
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
			return;
		} else if (twoD) {
			attemptToLoadDemoImage("Initial image is no longer available");
		} else {
			cancelAndFreezeUI(NO_IMAGE);
		}
	}

	protected void overlayShells() {
		if (imp == null) {
			previewShells = false;
			proposeNewImageIfImageClosed();
			return;
		}
		previewShells = previewShells && validRadii();
		threadService.newThread(previewOverlay).start();
	}

	private boolean validRadii() {
		final String reasonToInvalidateRaddi = validateRequirements(false);
		final boolean validRadii = reasonToInvalidateRaddi.isEmpty();
		if (!validRadii)
			cancelAndFreezeUI(reasonToInvalidateRaddi);
		return validRadii;
	}

	private String validateRequirements(final boolean includeThresholdCheck) {
		final List<String> cancelReasons = new ArrayList<>();
		if (center == null)
			cancelReasons.add(NO_CENTER);
		if (!validRadiiOptions())
			cancelReasons.add(NO_RADII);
		if (!includeThresholdCheck)
			return String.join(", ", cancelReasons);
		if (!readThresholdFromImp())
			cancelReasons.add(NO_THRESHOLD);
		return String.join(", ", cancelReasons);
	}

	private void runOptions() {
		threadService.newThread(() -> {
			final Map<String, Object> input = new HashMap<>();
			input.put("ignoreBitmapOptions", false);
			cmdService.run(ShollAnalysisPrefsCmd.class, true, input);
		}).start();
	}

	/** Private classes **/
	class AnalysisRunner implements Runnable {

		private final ImageParser parser;
		private boolean skipParsing;
		private ArrayList<Object> outputs = new ArrayList<>();

		public AnalysisRunner(final ImageParser parser) {
			this.parser = parser;
			parser.setCenter(center.x, center.y, center.z);
			parser.setRadii(startRadius, adjustedStepSize(), endRadius);
			parser.setHemiShells(hemiShellChoice);
			parser.setThreshold(lowerT, upperT);
			if (parser instanceof ImageParser3D) {
				((ImageParser3D) parser).setSkipSingleVoxels(prefService.getBoolean(
					ShollAnalysisPrefsCmd.class, "skipSingleVoxels", ShollAnalysisPrefsCmd.DEF_SKIP_SINGLE_VOXELS));
			}
		}

		public void setSkipParsing(final boolean skipParsing) {
			this.skipParsing = skipParsing;
		}

		public void terminate() {
			parser.terminate();
			statusService.showStatus(0, 0, "");
		}

		@Override
		public void run() {
			if (!validOutput()) return;

			if (!skipParsing) {
				parser.parse();
				if (!parser.successful()) {
					helper.error("No valid profile retrieved.", null);
					return;
				}
			}
			if (!parser.successful() && helper.getConfirmation("Previous run did not yield a valid profile. Re-parse image?", "Parse Image Again?")) {
				if (!updateHyperStackPosition()) {
					initializeParser();
					readThresholdFromImp();
				}
				if (!validRequirements(false)) return;
				parser.parse();
				if (!parser.successful()) {
					helper.error("No valid profile retrieved. Maybe settings are not appropriate?", "Re-run Failed");
					return;
				}
			}
			final Profile profile = parser.getProfile();

			// Linear profile stats
			final LinearProfileStats lStats = new LinearProfileStats(profile);
			lStats.setLogger(logger);
			if (primaryBranches > 0 ) {
				lStats.setPrimaryBranches((int)primaryBranches);
			}

			if (polynomialChoice.contains("Best")) {
				final int deg = lStats.findBestFit(minDegree, maxDegree, prefService);
				if (deg == -1) {
					helper.error("Polynomial regression failed. You may need to adjust Options for 'Best Fit' Polynomial", null);
				}
			} else if (polynomialChoice.contains("degree") && polynomialDegree > 1) {
				try {
					lStats.fitPolynomial((int)polynomialDegree);
				} catch (final Exception ignored){
					helper.error("Polynomial regression failed. Unsuitable degree?", null);
				}
			}

			if (!prefService.getBoolean(ShollAnalysisPrefsCmd.class, "includeZeroCounts",
					ShollAnalysisPrefsCmd.DEF_INCLUDE_ZERO_COUNTS)) {
				profile.trimZeroCounts();
			}

			/// Normalized profile stats
			final NormalizedProfileStats nStats = getNormalizedProfileStats(profile);
			logger.debug("Sholl decay: " + nStats.getShollDecay());

			// Set ROIs
			if (!annotationsDescription.contains("None") && imp != null) {
				if (annotationsDescription.contains("ROIs")) {
					final ShollOverlay sOverlay = new ShollOverlay(profile, imp, true);
					sOverlay.addCenter();
					sOverlay.setPointsSize(prefService.get(ShollAnalysisPrefsCmd.class, "roiSize", ShollAnalysisPrefsCmd.DEF_ROI_SIZE));
					if (annotationsDescription.contains("shells")) {
						sOverlay.setShellsThickness((int)nSpans);
						sOverlay.setShellsLUT(lutTable, ShollOverlay.COUNT);
					}
					sOverlay.setPointsLUT(lutTable, ShollOverlay.COUNT);
					sOverlay.updateDisplay();
					overlaySnapshot = imp.getOverlay();
				}
				if (annotationsDescription.toLowerCase().contains("mask")) showMask();
			}

			// Set Plots
			outputs = new ArrayList<>();
			if (plotOutputDescription.toLowerCase().contains("integrated") || plotOutputDescription.toLowerCase().contains("linear")) {
				final ShollPlot lPlot = lStats.getPlot(plotOutputDescription.toLowerCase().contains("cum"));
				outputs.add(lPlot);
				lPlot.show();
			}
			if (plotOutputDescription.toLowerCase().contains("normalized")) {
				final ShollPlot nPlot = nStats.getPlot(false);
				outputs.add(nPlot);
				nPlot.show();
			}

			// Set tables
			if (tableOutputDescription.contains("Detailed")) {
				final ShollTable dTable = new ShollTable(lStats, nStats);
				dTable.listProfileEntries();
				dTable.setTitle(imp.getTitle()+"_Sholl-Profiles");
				if (detailedTableDisplay != null) {
					detailedTableDisplay.close();
				}
				outputs.add(dTable);
				detailedTableDisplay = displayService.createDisplay(dTable.getTitle(), dTable);
			}

			if (tableOutputDescription.contains("Summary")) {

				final ShollTable sTable = new ShollTable(lStats, nStats);
				if (commonSummaryTable == null)
					commonSummaryTable = new ShollTable();
				sTable.summarize(commonSummaryTable, imp.getTitle());
				sTable.setTitle("Sholl Results");
				outputs.add(sTable);
				final Display<?> display = displayService.getDisplay("Sholl Results");
				if (display != null && display.isDisplaying(commonSummaryTable)) {
					display.update();
				} else {
					displayService.createDisplay(sTable.getTitle(), commonSummaryTable);
				}
			}

			setProfile(profile);

			// Now save everything
			String consolidatedErrorMsg = errorIfSaveDirInvalid() + errorIfInvalidOverlay();
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
					consolidatedErrorMsg += 
							"<dt>IO Failures</dt>"
									+ "<dd>Some file(s) (" + failures + "/"+ outputs.size() +") could not be saved.</dd>";
			}
			if (!consolidatedErrorMsg.isEmpty()) {
				helper.error("<html><dl>" + consolidatedErrorMsg + "</dl>", "Errors");
			}
		}

		private void showMask() {
			final ImagePlus mask = parser.getMask();
			if (!lutChoice.contains("No LUT.") && lutTable != null)
				ImpUtils.applyColorTable(mask, lutTable);
			outputs.add(mask);
			mask.show();
		}

		private boolean validOutput() {
			final boolean noOutput = plotOutputDescription.contains("None") && tableOutputDescription.contains("None")
					&& annotationsDescription.contains("None");
			if (noOutput) {
				cancel("Invalid output");
				helper.error("Analysis can only proceed if at least one type " +
					"of output (plot, table, annotation) is chosen.", "Invalid Output");
			}
			return !noOutput;
		}

	}

	private class PreviewOverlay implements Runnable {
		@Override
		public void run() {
			if (!previewShells) {
				if (overlaySnapshot == null || overlaySnapshot.equals(imp.getOverlay()))
					return;
				ShollOverlay.remove(overlaySnapshot, "temp");
				imp.setOverlay(overlaySnapshot);
				return;
			}
			try {
				overlaySnapshot = imp.getOverlay();
				final ArrayList<Double> radii = ShollUtils.getRadii(startRadius, adjustedStepSize(), endRadius);
				final Profile profile = new Profile();
				profile.assignImage(imp);
				for (final double r : radii)
					profile.add(new ProfileEntry(r, 0));
				profile.setCenter(center);
				profile.getProperties().setProperty(ProfileProperties.KEY_HEMISHELLS,
						ShollUtils.extractHemiShellFlag(hemiShellChoice));
				final ShollOverlay so = new ShollOverlay(profile);
				so.setShellsThickness((int)nSpans);
				if (lutTable == null) {
					so.setShellsColor(Roi.getColor());
				} else {
					so.setShellsLUT(lutTable, ShollOverlay.RADIUS);
				}
				so.addCenter();
				so.assignProperty("temp");
				imp.setOverlay(so.getOverlay());
			} catch (final IllegalArgumentException ignored) {
				return; // invalid parameters: do nothing
			}
		}
	}
}
