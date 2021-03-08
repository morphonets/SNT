/*
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

import java.awt.Rectangle;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.convert.ConvertService;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.event.EventHandler;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.table.DefaultGenericTable;
import org.scijava.thread.ThreadService;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.display.ImageDisplayService;
import net.imagej.event.DataDeletedEvent;
import net.imagej.legacy.LegacyService;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollOverlay;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser2D;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser3D;
import sc.fiji.snt.gui.GUIHelper;
import sc.fiji.snt.util.Logger;
import sc.fiji.snt.util.ShollPoint;


/**
 * Implements the Analyze:Sholl:Sholl Analysis (From Image)...
 * 
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = { @Menu(label = "Plugins"), @Menu(label = "Neuroanatomy"),
		@Menu(label = "Sholl", weight = 0.01d), @Menu(label = "Sholl Analysis (From Image) [Recordable]...") }, initializer = "init")
public class ShollAnalysisImgRecordable extends ContextCommand {

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
	private LegacyService legacyService;
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

	protected static final String HEADER_TOOLTIP = "<HTML><div WIDTH=650>";
	protected static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final int MAX_SPANS = 10;

	private static final String NO_IMAGE = "Image no longer available";
	private static final String NO_CENTER = "Invalid center";
	private static final String NO_RADII = "Invalid radii";
	private static final String NO_THRESHOLD = "Invalid threshold levels";
	private static final String NO_ROI = "No ROI detected";
	private static final String RUNNING = "Analysis currently running";

	/* Parameters */
	@Parameter(label = "Starting radius", required = false, min = "0")
	private double startRadius;

	@Parameter(label = "Radius step size", required = false, min = "0")
	private double stepSize;

	@Parameter(label = "Ending radius", persist = false, required = false, min = "0")
	private double endRadius;

	@Parameter(label = "Hemishells", required = false, choices = { "None. Use full shells",
			"Above center", "Below center", "Left of center", "Right of center" })
	private String hemiShellChoice = "None. Use full shells";

	@Parameter(label = "Samples per radius", callback = "nSpansChanged", min = "1", max = "" + MAX_SPANS)
	private double nSpans;

	@Parameter(label = "Integration", callback = "nSpansIntChoiceChanged", choices = { "N/A", "Mean", "Median",
			"Mode" })
	private String nSpansIntChoice;


	@Parameter(label = "Primary branches", callback = "primaryBranchesChoiceChanged", choices = {
			"Infer from starting radius", "Infer from multipoint ROI",//
			 "1",  "2",  "3",  "4",  "5",  "6",  "7",  "8",  "9", "10",//
			"11", "12", "13", "14", "15", "16", "17", "18", "19", "20",//
			"21", "22", "23", "24", "25", "26", "27", "28", "29", "30",//
			"31", "32", "33", "34", "35", "36", "37", "38", "39", "40",//
			"41", "42", "43", "44", "45", "46", "47", "48", "49", "50"})
	private String primaryBranchesChoice = "Infer from starting radius";
	private double primaryBranches;

	@Parameter(label = "Degree", callback = "polynomialChoiceChanged", required = false, choices = {
			"None. Skip curve fitting", "'Best fitting' degree",//
			"2",  "3",  "4",  "5",  "6",  "7",  "8",  "9", "10",//
			"11", "12", "13", "14", "15", "16", "17", "18", "19", "20",//
			"21", "22", "23", "24", "25", "26", "27", "28", "29", "30",//
			"31", "32", "33", "34", "35", "36", "37", "38", "39", "40",//
			"41", "42", "43", "44", "45", "46", "47", "48", "49", "50"})
	private String polynomialChoice = "'Best fitting' degree";
	private double polynomialDegree;

	@Parameter(label = "Method", choices = { "Automatically choose", "Semi-Log", "Log-log" })
	private String normalizationMethodDescription;

	@Parameter(label = "Normalizer", choices = { "Default", "Area/Volume", "Perimeter/Surface Area", "Annulus/Spherical shell" })
	private String normalizerDescription;

	@Parameter(label = "Plots", callback="saveOptionsChanged", choices = { "Linear plot", "Normalized plot", "Linear & normalized plots",
			"None. Show no plots" })
	private String plotOutputDescription;

	@Parameter(label = "Tables", callback="saveOptionsChanged", choices = { "Detailed table", "Summary table",
		"Detailed & Summary tables", "None. Show no tables" })
	private String tableOutputDescription;

	@Parameter(label = "Annotations", callback = "annotationsDescriptionChanged",
		choices = { "ROIs (Sholl points only)", "ROIs (points and 2D shells)",
			"ROIs and mask", "None. Show no annotations" })
	private String annotationsDescription;

	@Parameter(label = "Annotations LUT", callback = "lutChoiceChanged",
			description = HEADER_TOOLTIP + "The mapping LUT used to render ROIs and mask.")
	private String lutChoice;

	@Parameter(required = false, callback = "saveChoiceChanged", label = "Save files", //
			description = HEADER_TOOLTIP + "Wheter output files (tables, plots, mask) should be saved once displayed."
					+ "Note that the analyzed image itself is not saved.")
	private boolean save;

	@Parameter(required = false, label = "Destination", type = ItemIO.INPUT, style = FileWidget.DIRECTORY_STYLE, //
			description = HEADER_TOOLTIP + "Destination directory. Ignored if \"Save files\" is deselected, "
					+ "or outputs are not savable.")
	private File saveDir;

	/* Instance variables */
	private Dataset dataset;
	private GUIHelper helper;
	private Logger logger;
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
	private DefaultGenericTable commonSummaryTable;
	private Display<?> detailedTableDisplay;

	/* Preferences */
//	private boolean autoClose;
	private int minDegree;
	private int maxDegree;
	private ColorTable lutTable;


	@EventHandler
	public void onEvent(final DataDeletedEvent evt) {
		if (evt.getObject().equals(dataset)) {
			imp = null;
			cancel(NO_IMAGE);
			logger.debug(evt);
		}
	}

	@Override
	public void run() {
		if (!validRequirements())
			return;
		updateHyperStackPosition(); // Did channel/frame changed?
		imp.setOverlay(overlaySnapshot);
		parser.reset();
		analysisRunner = new AnalysisRunner(parser);
		analysisRunner.setSkipParsing(false);
		statusService.showStatus("Analysis started");
		logger.debug("Analysis started...");
		analysisThread = threadService.newThread(analysisRunner);
		analysisThread.start();
		savePreferences();
}


	private boolean validRequirements() {
		String cancelReason = "";
		if (imp == null) {
			cancelReason = NO_IMAGE;
		} else if (ongoingAnalysis()) {
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

	private NormalizedProfileStats getNormalizedProfileStats(final Profile profile) {
		String normalizerString = normalizerDescription.toLowerCase();
		if (normalizerString.startsWith("default")) {
			normalizerString = (twoD) ? NORM2D_CHOICES.get(1) : NORM3D_CHOICES.get(1);
		}
		final int normFlag = NormalizedProfileStats.getNormalizerFlag(normalizerString);
		final int methodFlag = NormalizedProfileStats.getMethodFlag(normalizationMethodDescription);
		return new NormalizedProfileStats(profile, normFlag, methodFlag);
	}

	/* initializer method running before displaying prompt */
	
	@SuppressWarnings("unused")
	private void init() {
		helper = new GUIHelper(context());
		logger = new Logger(context(), "Sholl");
		readPreferences();
		imp = legacyService.getImageMap().lookupImagePlus(imageDisplayService.getActiveImageDisplay());
		if (imp == null)
			displayDemoImage();
		if (imp == null) {
			helper.error("A dataset is required but none was found", null);
			cancel(null);
			return;
		}
		legacyService.syncActiveImage();
		setLUTs();
		loadDataset(imp);
		if (saveDir == null) saveDir = new File("");
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
		center = getCenterFromROI(true);
	}

	private void initializeParser() {
		parser = (twoD) ? new ImageParser2D(imp, context()) : new ImageParser3D(imp, context());
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
		final String msg = "Scope of analysis is currently %s.\nUpdate scope to active position (%s)?";
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
			cancelAndFreezeUI(NO_IMAGE);
			return;
		}
		final ShollPoint newCenter = getCenterFromROI(false);
		if (newCenter == null) {
			cancelAndFreezeUI(NO_ROI);
			return;
		}
		if (center != null && center.equals(newCenter)) {
			helper.error("ROI already defines the same center currently in use\n(" + centerDescription()
					+ "). No changes were made.", "Center Already Defined");
			return;
		}
		if (center != null && newCenter.z != center.z) {
			final Result result = helper.yesNoPrompt(
					String.format("Current center was set at Z-position %s.\n" + "Move center to active Z-position %s?",
							ShollUtils.d2s(center.z), ShollUtils.d2s(newCenter.z)),
					"Z-Position Changed");
			if (result == Result.NO_OPTION)
				newCenter.z = center.z;
		}
		center = newCenter;
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
		case NO_CENTER:
			uiMsg = "Please set an ROI, then press \"" + "Set New Center from Active ROI\".\n"
					+ "Center coordinates will be defined by the ROI's centroid.";
			break;
		case NO_RADII:
			uiMsg = "Ending radius and Radius step size must be within range.";
			break;
		case NO_THRESHOLD:
			uiMsg = "Image is not segmented. Please adjust threshold levels.";
			break;
		case RUNNING:
			uiMsg = "An analysis is currently running. Please wait...";
			break;
		default:
			if (cancelReason.contains(",")) {
				uiMsg = "Image cannot be analyzed. Muliple invalid requirements:\n- "
						+ cancelReason.replace(", ", "\n- ");
			} else {
				uiMsg = cancelReason;
			}
			break;
		}
		cancel(cancelReason);
	}

	private boolean readThresholdFromImp() {
		boolean successfulRead = true;
		final double minT = imp.getProcessor().getMinThreshold();
		final double maxT = imp.getProcessor().getMaxThreshold();
		if (imp.getProcessor().isBinary()) {
			lowerT = 1;
			upperT = 255;
		} else if (imp.isThreshold()) {
			lowerT = minT;
			upperT = maxT;
		} else {
			successfulRead = false;
		}
		return successfulRead;
	}

	private void displayDemoImage() {
		final Result result = helper.yesNoPrompt("No images are currently open. Run plugin on demo image?", null);
		if (result != Result.YES_OPTION)
			return;
		imp = ShollUtils.sampleImage();
		if (imp == null) {
			helper.error("Demo image could not be loaded.", null);
			return;
		}
		displayService.createDisplay(imp.getTitle(), imp);
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
		lutChoiceChanged();
	}

	private double adjustedStepSize() {
		return Math.max(stepSize, voxelSize);
	}

	/* callbacks */
	protected void startRadiusStepSizeChanged() {
		if (startRadius > endRadius || stepSize > endRadius)
			endRadius = Math.min(endRadius + adjustedStepSize(), maxPossibleRadius);
	}

	protected void endRadiusChanged() {
		if (endRadius < startRadius + stepSize)
			startRadius = Math.max(endRadius - adjustedStepSize(), 0);
	}

	protected void nSpansChanged() {
		nSpansIntChoice = (nSpans == 1) ? "N/A" : "Mean";
	}

	protected void nSpansIntChoiceChanged() {
		int nSpansBefore = (int)nSpans;
		if (nSpansIntChoice.contains("N/A"))
			nSpans = 1;
		else if (nSpans == 1)
			nSpans++;
	}

	protected void polynomialChoiceChanged() {
		if (!polynomialChoice.contains("specified")) {
			polynomialDegree = 0;
		} else if (polynomialDegree == 0) {
			polynomialDegree = (minDegree + maxDegree ) / 2;
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
	}

	@SuppressWarnings("unused")
	private void saveChoiceChanged() {
		if (save && saveDir == null)
			saveDir = new File(IJ.getDirectory("current"));
	}

	private void saveOptionsChanged() {
		if (plotOutputDescription.startsWith("None") && tableOutputDescription.startsWith("None")
				&& !annotationsDescription.contains("mask"))
			save = false;
	}

	private void errorIfSaveDirInvalid() {
		if (save && (saveDir == null || !saveDir.exists() || !saveDir.canWrite())) {
			save = false;
			helper.error("No files saved: Output directory is not valid or writable.", "Please Change Output Directory");
		}
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

	@SuppressWarnings("unused")
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
			if (!parser.successful()) {
				final Result result = helper.yesNoPrompt("Previous run did not yield a valid profile. Re-parse image?", null);
				if (result != Result.YES_OPTION)
					return;
				if (!updateHyperStackPosition()) {
					initializeParser();
					readThresholdFromImp();
				}
				if (!validRequirements()) return;
				parser.parse();
				if (!parser.successful()) {
					helper.error("No valid profile retrieved.", "Re-run Failed");
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

			/// Normalized profile stats
			final NormalizedProfileStats nStats = getNormalizedProfileStats(profile);
			logger.debug("Sholl decay: " + nStats.getShollDecay());

			// Set ROIs
			if (!annotationsDescription.contains("None")) {
				final ShollOverlay sOverlay = new ShollOverlay(profile, imp, true);
				sOverlay.addCenter();
				if (annotationsDescription.contains("shells"))
					sOverlay.setShellsLUT(lutTable, ShollOverlay.COUNT);
				sOverlay.setPointsLUT(lutTable, ShollOverlay.COUNT);
				sOverlay.updateDisplay();
				overlaySnapshot = imp.getOverlay();
				if (annotationsDescription.contains("mask")) showMask();
			}

			// Set Plots
			outputs = new ArrayList<>();
			if (plotOutputDescription.toLowerCase().contains("linear")) {
				final ShollPlot lPlot = lStats.getPlot();
				outputs.add(lPlot);
				lPlot.show();
			}
			if (plotOutputDescription.toLowerCase().contains("normalized")) {
				final ShollPlot nPlot = nStats.getPlot();
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
					commonSummaryTable = new DefaultGenericTable();
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
		}

		private void showMask() {
			final ImagePlus mask = parser.getMask();
			if (!lutChoice.contains("No LUT.")) mask.getProcessor().setLut(SNTUtils
				.getLut(lutTable));
			outputs.add(mask);
			displayService.createDisplay(mask);
		}

		private boolean validOutput() {
			boolean noOutput = plotOutputDescription.contains("None");
//			noOutput = noOutput && tableOutputDescription.contains("None");
			noOutput = noOutput && annotationsDescription.contains("None");
			if (noOutput) {
				cancel("Invalid output");
				helper.error("Analysis can only proceed if at least one type\n" +
					"of output (plot, table, annotation) is chosen.", "Invalid Output");
			}
			return !noOutput;
		}

	}

}
