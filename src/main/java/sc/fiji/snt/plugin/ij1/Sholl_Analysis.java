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
package sc.fiji.snt.plugin.ij1;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Label;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Vector;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.HTMLDialog;
import ij.gui.Line;
import ij.gui.Plot;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.io.OpenDialog;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.MeasurementsWriter;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.text.TextPanel;
import ij.text.TextWindow;
import ij.util.Tools;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollOverlay;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.analysis.sholl.gui.ShollTable;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser2D;
import sc.fiji.snt.analysis.sholl.parsers.ImageParser3D;
import sc.fiji.snt.gui.EnhancedGenericDialog;
import sc.fiji.snt.gui.EnhancedWaitForUserDialog;
import sc.fiji.snt.util.ShollPoint;


/**
 * ImageJ 1 plugin that uses the Sholl technique to perform neuronal morphometry
 * directly from bitmap images.
 *
 * @author Tiago Ferreira (based on an earlier implementation by Tom Maddock)
 * @see <a href="https://github.com/tferr/ASA">https://github.com/tferr/ASA</a>
 * @see <a href="http://imagej.net/Sholl">http://imagej.net/Sholl</a>
 * @deprecated This IJ1 plugin has now been replaced by its IJ2 counterpart.
 *             Kept here for backwards compatibility
 */
@Deprecated
public class Sholl_Analysis implements PlugIn, DialogListener {

	/* Plugin Information */
	/** The Plugin's version */
	public static final String VERSION = SNTUtils.VERSION;
	public static final String URL = ShollUtils.URL;

	/* Sholl Type Definitions */
	private static final String[] SHOLL_TYPES = { "Linear", "Linear-norm", "Semi-log", "Log-log" };
	private static final int SHOLL_N = 0;
	private static final int SHOLL_NS = 1;
	private static final int SHOLL_SLOG = 2;
	private static final int SHOLL_LOG = 3;
	private boolean shollN = true;
	private boolean shollNS = false;
	private boolean shollSLOG = true;
	private boolean shollLOG = false;

	/* Data Normalization */
	private static final String[] NORMS2D = { "Area", "Perimeter", "Annulus" };
	private static final String[] NORMS3D = { "Volume", "Surface", "Spherical shell" };
	private static final int AREA_NORM = 0;
	private static final int PERIMETER_NORM = 1;
	private static final int ANNULUS_NORM = 2;
	private int normChoice;

	/* Ramification Indices */
	private double primaryBranches = Double.NaN;
	private boolean inferPrimary;
	private boolean primaryFromPointRoi = false;
	private int multipointCount;

	/* Curve Fitting, Results and Descriptors */
	private boolean fitCurve = true;
	protected static final String[] DEGREES = { "2nd degree", "3rd degree", "4th degree", "5th degree", "6th degree",
			"7th degree", "8th degree", "Best fitting degree" };
	private static final int SMALLEST_DATASET = 6;
	private static EnhancedResultsTable statsTable;
	private final String STATS_TABLE_TITLE = "Sholl Results";
	private double[] centroid = null;
	private int enclosingCutOff = 1;
	private boolean chooseLog = true;

	/* Image path and Output Options */
	private boolean validPath;
	private boolean hideSaved;
	private String imgPath;
	private String imgTitle;

	/* Default parameters and input values */
	private double startRadius = 10.0;
	private double endRadius = 100.0;
	private double stepRadius = 1;
	private double incStep = 0;
	private int polyChoice = DEGREES.length - 1;
	private boolean verbose;
	private boolean mask;
	private boolean overlayShells;
	private boolean save;
	private boolean isCSV;

	/* Common variables */
	private String unit = "pixels";
	private double vxSize = 1;
	private double vxWH = 1;
	private double vxD = 1;
	private boolean is3D;
	private double lowerT;
	private double upperT;

	/* Boundaries and center of analysis */
	private boolean orthoChord = false;
	private boolean trimBounds;
	private final String[] quads = new String[2];
	private static final String QUAD_NORTH = "Above line";
	private static final String QUAD_SOUTH = "Below line";
	private static final String QUAD_EAST = "Left of line";
	private static final String QUAD_WEST = "Right of line";
	private String quadString = "None";
	private int quadChoice;
	private int x;
	private int y;
	private int z;
	private int channel;

	/* Parameters for 3D analysis */
	private boolean skipSingleVoxels = false;

	/* Parameters for 2D analysis */
	private static final String[] BIN_TYPES = { "Mean", "Median", "Mode" };
	/** Flag for integration of repeated measures (2D analysis): average */
	public static final int BIN_AVERAGE = ImageParser2D.MEAN;
	/** Flag for integration of repeated measures (2D analysis): median */
	public static final int BIN_MEDIAN = ImageParser2D.MEDIAN;
	/** Flag for integration of repeated measures (2D analysis): mode */
	public static final int BIN_MODE = ImageParser2D.MODE;
	private int binChoice = BIN_AVERAGE;
	private int nSpans = 1;
	public static final int MAX_N_SPANS = 10;

	/* Advanced options and flags for API usage */
	/* Display prompts? */
	private boolean interactiveMode = true;
	/* Describe fitted curves in plots? */
	private boolean plotLabels = true;
	/* How many discretization steps for Riemann sum & local max? */
	private int fMetricsPrecision = 1000;

	/* Parameters for tabular data */
	private ResultsTable csvRT;
	private int rColumn;
	private int cColumn;
	private boolean limitCSV;
	private boolean tableRequired = true;

	/* Preferences */
	private ShollOptions options;
	private int metrics;

	private double[] radii;
	private double[] counts;
	private ImagePlus img;
	private ImageProcessor ip;
	// private Map<Double, HashSet<UPoint>> intersPoints;
	// private boolean storeIntersPoints = true;
	private Profile profile;
	private ImageParser parser;

	/**
	 * This method is called when the plugin is loaded. {@code arg} is specified in
	 * {@code plugins.config}. See {@link ij.plugin.PlugIn#run(java.lang.String)}
	 *
	 * @param arg
	 *            If {@code image}, the plugin runs in "bitmap mode" If {@code csv}
	 *            the plugin is set for analysis of tabular data. If {@code sample},
	 *            the plugin runs on a 2D demo image.
	 */
	@Override
	public void run(final String arg) {

		if (arg.equalsIgnoreCase("sample")) {
			img = ShollUtils.sampleImage();
			if (img == null) {
				sError("Could not retrieve sample image.\nPerhaps you should restart ImageJ?");
				return;
			} else
				img.show();
		} else if (arg.isEmpty() || arg.equalsIgnoreCase("image")) {
			interactiveMode = true;
			img = WindowManager.getCurrentImage();
		}

		final Calibration cal;
		isCSV = arg.equalsIgnoreCase("csv");

		options = getOptions();
		options.setSkipBitmapOptions(isCSV);
		metrics = options.getMetrics();
		if (!IJ.macroRunning())
			setParametersFromPreferences();

		if (isCSV) {

			if (interactiveMode && isTableRequired() && csvRT == null) {
				csvRT = getTable();
				if (csvRT == null)
					return;
			}

			fitCurve = true; // The goal of CSV import is curve fitting

			// Update parameters for CSV data with user input.
			if (!csvPrompt()) {
				return;
			}

			// Retrieve parameters from chosen columns
			if (isTableRequired()) {
				radii = csvRT.getColumnAsDoubles(rColumn);
				counts = csvRT.getColumnAsDoubles(cColumn);
				if (radii == null || counts == null) {
					sError("Chosen columns are empty");
					return;
				}
				if (limitCSV) {
					final TextPanel tp = Sholl_Utils.getTextWindow(getDescription()).getTextPanel();
					final int startRow = tp.getSelectionStart();
					final int endRow = tp.getSelectionEnd();
					final boolean validRange = startRow != -1 && endRow != -1 && startRow != endRow;
					if (validRange) {
						radii = Arrays.copyOfRange(radii, startRow, endRow + 1);
						counts = Arrays.copyOfRange(counts, startRow, endRow + 1);
					} else {
						IJ.log("*** Warning: " + getDescription() + "\n*** Option to restrict "
								+ "analysis ignored: Not a valid selection of rows");
					}
				}
				stepRadius = (radii.length > 1) ? radii[1] - radii[0] : Double.NaN;
			}
			startRadius = radii[0];
			endRadius = radii[radii.length - 1];
			if (normChoice == NORMS3D.length - 1 && (Double.isNaN(stepRadius) || stepRadius <= 0)) {
				final String msg = (is3D) ? NORMS3D[normChoice] : NORMS2D[normChoice];
				IJ.log("*** Warning: " + getDescription() + "\n*** Could not determine" + " radius step size: " + msg
						+ " normalizations will not be relevant");
			}

			cal = null;

		} else {

			// Make sure image is of the right type, reminding the user
			// that the analysis is performed on segmented cells
			ip = getValidProcessor(img);
			if (ip == null)
				return;

			// Set the 2D/3D Sholl flag
			final int depth = img.getNSlices();
			is3D = depth > 1;

			// Get current z,c position
			z = img.getZ();
			channel = img.getC();

			// NB: Removing spaces could disrupt unique filenames: eg "test
			// 01.tif" and "test 02.tif" would both be treated as "test" by
			// img.getShortTitle()
			setDescription(trimExtension(img.getTitle()), false);

			// Get image calibration. Stacks are likely to have anisotropic
			// voxels with large z-steps. It is unlikely that lateral dimensions
			// will differ
			cal = img.getCalibration();
			if (cal != null && cal.scaled()) {
				vxWH = Math.sqrt(cal.pixelWidth * cal.pixelHeight);
				vxD = cal.pixelDepth;
				setUnit(cal.getUnits());
			} else {
				vxWH = vxD = 1;
				setUnit("pixels");
			}
			vxSize = (is3D) ? Math.cbrt(vxWH * vxWH * vxD) : vxWH;

			// Retrieve ROI defining center of analysis
			final Roi roi = getStartupROI();

			// Straight line: get center coordinates, end radius and angle of
			// chord.
			double chordAngle = -1.0; // Initialize line angle
			if (roi != null && roi.getType() == Roi.LINE) {

				final Line chord = (Line) roi;
				x = chord.x1;
				y = chord.y1;
				endRadius = vxSize * chord.getRawLength();
				chordAngle = Math.abs(chord.getAngle(x, y, chord.x2, chord.y2));
				primaryFromPointRoi = false;
				multipointCount = 0;

				// Point: Get center coordinates (x,y)
			} else if (roi != null && roi.getType() == Roi.POINT) {

				final PointRoi point = (PointRoi) roi;
				final Rectangle rect = point.getBounds();
				x = rect.x;
				y = rect.y;

				// If multi-point, use point count to specify # Primary branches
				multipointCount = point.getCount(point.getCounter());
				if (multipointCount > 1) {
					primaryBranches = multipointCount - 1;
					inferPrimary = false;
					primaryFromPointRoi = true;
				}

				// Not a proper ROI type
			} else {
				sError("Straight Line, Point or Multi-point selection required.");
				return;
			}

			// Show the plugin dialog: Update parameters with user input and
			// find out if analysis will be restricted to a hemicircle /
			// hemisphere
			if (!bitmapPrompt(chordAngle, is3D)) {
				return;
			}

			// Impose valid parameters
			final int wdth = ip.getWidth();
			final int hght = ip.getHeight();
			final double dx, dy, dz, maxEndRadius;

			dx = ((orthoChord && trimBounds && quadString.equals(QUAD_WEST)) || x <= wdth / 2) ? (x - wdth) * vxWH
					: x * vxWH;

			dy = ((orthoChord && trimBounds && quadString.equals(QUAD_SOUTH)) || y <= hght / 2) ? (y - hght) * vxWH
					: y * vxWH;

			dz = (z <= depth / 2) ? (z - depth) * vxD : z * vxD;

			maxEndRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (Double.isNaN(startRadius))
				startRadius = 0;
			if (Double.isNaN(incStep))
				incStep = 0;
			endRadius = Double.isNaN(endRadius) ? maxEndRadius : Math.min(endRadius, maxEndRadius);
			stepRadius = Math.max(vxSize, incStep);

			// Calculate how many samples will be taken
			final int size = (int) ((endRadius - startRadius) / stepRadius) + 1;

			// Exit if there are no samples
			if (size <= 1) {
				sError("Invalid parameters: Starting radius must be smaller than\n"
						+ "Ending radius and Radius step size must be within range!");
				return;
			}

			if (!IJ.macroRunning())
				saveParametersToPreferences();
			img.startTiming();
			IJ.resetEscape();

			// Create arrays for radii (in physical units) and intersection
			// counts
			radii = new double[size];
			counts = new double[size];

			for (int i = 0; i < size; i++) {
				radii[i] = startRadius + i * stepRadius;
			}

			// 2D: Analyze the data and return intersection counts with nSpans
			// per radius. 3D: Analysis without nSpans
			if (is3D) {
				counts = analyze3D(x, y, z, radii, img);
			} else {
				counts = analyze2D(x, y, radii, vxSize, nSpans, binChoice, img);
			}
		}

		IJ.showStatus("Preparing Results...");

		// Retrieve pairs of radii, counts for intersecting radii
		final double[][] valuesN = getNonZeroValues(radii, counts);
		final int trimmedCounts = valuesN.length;
		if (trimmedCounts == 0) {
			IJ.beep();
			IJ.showProgress(0, 0);
			IJ.showStatus("Error: All intersection counts were zero!");
			return;
		}

		if (statsTable == null || !statsTable.isShowing())
			initializeStatsTable();
		// Retrieve stats on sampled data
		populateStatsTable(getDescription(), x, y, z, valuesN);

		// Transform and fit data
		final double[][] valuesNS = transformValues(valuesN, true, false, false);
		final double[][] valuesSLOG = transformValues(valuesNS, false, true, false);
		final double[][] valuesLOG = transformValues(valuesSLOG, false, false, true);
		double[] fvaluesN = null;
		double[] fvaluesNS = null;
		// double[] fvaluesLOG = null;

		// Create plots
		final boolean noPlots = options.getPlotOutput() == ShollOptions.NO_PLOTS;
		final boolean onlyLinearPlot = options.getPlotOutput() == ShollOptions.ONLY_LINEAR_PLOT;
		if (shollN) {
			final ShollPlot plotN;
			if (noPlots) {
				plotN = null;
			} else {
				plotN = plotValues("Sholl profile (" + SHOLL_TYPES[SHOLL_N] + ") for " + getDescription(),
						is3D ? "3D distance (" + unit + ")" : "2D distance (" + unit + ")", "N. of Intersections",
						valuesN);
			}
			if (fitCurve)
				fvaluesN = getFittedProfile(valuesN, SHOLL_N, statsTable, plotN);
			if (!noPlots) {
				if (centroid != null) plotN.markPoint(new ShollPoint(centroid[0],
					centroid[1]), Color.RED);
				savePlot(plotN, SHOLL_N);
			}

		}

		// Linear (norm) is not performed when deciding between semi-log/log-log
		if (chooseLog) {

			IJ.showStatus("Calculating determination ratio...");
			// this is inefficient: we'll be splitting double arrays
			final double dratio = getDeterminationRatio(valuesSLOG, valuesLOG);
			statsTable.addValue("Determination ratio", dratio);
			shollNS = false;
			shollSLOG = (dratio >= 1);
			shollLOG = (dratio < 1);

		}

		final String normalizerString = is3D ? NORMS3D[normChoice] : NORMS2D[normChoice];
		final String distanceString = is3D ? "3D distance" : "2D distance";

		if (shollNS) {
			final ShollPlot plotNS;
			if (noPlots || onlyLinearPlot) {
				plotNS = null;
			} else {
				plotNS = plotValues("Sholl profile (" + SHOLL_TYPES[SHOLL_NS] + ") for " + getDescription(),
						distanceString + " (" + unit + ")", "Inters./" + normalizerString, valuesNS);
			}
			if (fitCurve)
				fvaluesNS = getFittedProfile(valuesNS, SHOLL_NS, statsTable, plotNS);
			if (!noPlots && !onlyLinearPlot)
				savePlot(plotNS, SHOLL_NS);

		}
		if (shollSLOG) {
			final ShollPlot plotSLOG;
			if (noPlots || onlyLinearPlot) {
				plotSLOG = null;
			} else {
				plotSLOG = plotValues("Sholl profile (" + SHOLL_TYPES[SHOLL_SLOG] + ") for " + getDescription(),
						distanceString + " (" + unit + ")", "log(Inters./" + normalizerString + ")", valuesSLOG);
			}
			if (fitCurve)
				plotRegression(valuesSLOG, plotSLOG, statsTable, SHOLL_TYPES[SHOLL_SLOG]);
			if (!noPlots && !onlyLinearPlot)
				savePlot(plotSLOG, SHOLL_SLOG);

		}
		if (shollLOG) {
			final ShollPlot plotLOG;
			if (noPlots || onlyLinearPlot) {
				plotLOG = null;
			} else {
				plotLOG = plotValues("Sholl profile (" + SHOLL_TYPES[SHOLL_LOG] + ") for " + getDescription(),
						"log(" + distanceString + ")", "log(Inters./" + normalizerString + ")", valuesLOG);
			}
			if (fitCurve)
				// fvaluesLOG = getFittedProfile(valuesLOG, SHOLL_LOG,
				// statsTable, plotLOG);
				plotRegression(valuesLOG, plotLOG, statsTable, SHOLL_TYPES[SHOLL_LOG]);
			if (!noPlots && !onlyLinearPlot)
				savePlot(plotLOG, SHOLL_LOG);

		}

		final boolean noTable = ((metrics & ShollOptions.NO_TABLE) != 0);
		if (!noTable) {

			// If re-running over the same image, dispose unsaved table from
			// previous runs
			final String profileTable = getDescription() + "_Sholl-Profiles";
			final TextWindow window = (TextWindow) WindowManager.getFrame(profileTable);
			if (window != null)
				window.close(false);

			final EnhancedResultsTable rt = new EnhancedResultsTable();
			rt.showRowNumbers(false);
			rt.setPrecision(options.getScientificNotationAwarePrecision());
			rt.setNaNEmptyCells(true);
			final int lastNonZeroIdx = valuesN.length - 1;
			for (int i = 0; i < radii.length; i++) {
				rt.incrementCounter();
				rt.addValue("Radius", radii[i]);
				rt.addValue("Inters.", counts[i]);
				if (i > lastNonZeroIdx)
					continue;
				if (fvaluesN != null) {
					rt.addValue("Radius (Polyn. fit)", valuesN[i][0]);
					rt.addValue("Inters. (Polyn. fit)", fvaluesN[i]);
				}
				rt.addValue("Radius (norm)" + normalizerString, valuesNS[i][0]);
				rt.addValue("Inters./" + normalizerString, valuesNS[i][1]);
				if (fvaluesNS != null) {
					rt.addValue("Radius (Power fit)", valuesNS[i][0]);
					rt.addValue("Inters./" + normalizerString + " (Power fit)", fvaluesNS[i]);
				}
				rt.addValue("log(Radius)", valuesLOG[i][0]);
				rt.addValue("log(Inters./" + normalizerString + ")", valuesLOG[i][1]);
				// if (fvaluesLOG!=null) {
				// rt.addValue("log(Radius) (Exponential fit)",
				// valuesLOG[i][0]);
				// rt.addValue("log(Inters./"+ normalizerString +") (Exponential
				// fit)", fvaluesLOG[i]);
				// }
			}

			if (validPath && save) {
				try {
					final String path = imgPath + profileTable;
					rt.saveAs(path + Prefs.defaultResultsExtension());
					rt.setUnsavedMeasurements(false);
				} catch (final IOException e) {
					IJ.log(">>>> An error occurred when saving " + getDescription() + "'s profile(s):\n" + e);
				}
			}
			if (!validPath || (validPath && !hideSaved))
				rt.update(profileTable);
		}

		statsTable.update(STATS_TABLE_TITLE);
		String exitmsg = "Done. ";

		if (isCSV) {
			IJ.showStatus(exitmsg);
			return;
		}

		// Create intersections mask if analyzed image remains available
		if (mask && img.getWindow() != null) {

			IJ.showStatus("Preparing intersections mask...");
			final boolean fittedData = options.getMaskType() == ShollOptions.FITTED_MASK && fitCurve;
			ImagePlus maskimg = null;

			if (shollN && fittedData) {

				maskimg = makeMask(getDescription(), fvaluesN, x, y, cal, false);
				maskimg.setProperty("Label", "Polynomial fit");

			} else if (shollN) {

				maskimg = makeMask(getDescription(), counts, x, y, cal, false);
				maskimg.setProperty("Label", "Sampled data");

			} else if (shollNS && fittedData) {

				maskimg = makeMask(getDescription(), fvaluesNS, x, y, cal, true);
				maskimg.setProperty("Label", SHOLL_TYPES[SHOLL_NS] + " (fitted)");

			} else if (shollNS) {

				maskimg = makeMask(getDescription(), valuesNS, x, y, cal, true);
				maskimg.setProperty("Label", SHOLL_TYPES[SHOLL_NS] + " (sampled)");

			} else if (shollSLOG && fittedData) {

				try {
					final double b = statsTable.getValue("Regression intercept (" + SHOLL_TYPES[SHOLL_SLOG] + ")",
							statsTable.getCounter() - 1);
					final double k = statsTable.getValue("Regression coefficient (" + SHOLL_TYPES[SHOLL_SLOG] + ")",
							statsTable.getCounter() - 1);
					final double[] fvaluesSLOG = new double[valuesSLOG.length];
					for (int i = 0; i < valuesSLOG.length; i++)
						fvaluesSLOG[i] = valuesSLOG[i][0] * -k + b;
					maskimg = makeMask(getDescription(), fvaluesSLOG, x, y, cal, true);
					maskimg.setProperty("Label", SHOLL_TYPES[SHOLL_SLOG] + " (fitted)");
				} catch (final IllegalArgumentException ignored) {
					if (verbose)
						IJ.log("[Sholl] ERROR: Regression inaccessible for mask creation");
				}

			} else if (shollSLOG) {

				maskimg = makeMask(getDescription(), valuesSLOG, x, y, cal, true);
				maskimg.setProperty("Label", SHOLL_TYPES[SHOLL_SLOG] + " (sampled)");

			} else if (shollLOG && verbose) {

				IJ.log("[Sholl] INFO: Masks cannot be rendered for " + SHOLL_TYPES[SHOLL_LOG] + " profiles");

			}

			if (maskimg == null) {
				IJ.beep();
				exitmsg = "Error: Mask could not be created! ";
			} else if (!validPath || (validPath && !hideSaved)) {
				maskimg.show();
				maskimg.updateAndDraw();
			}

		}

		if (overlayShells)
			overlayShells();

		IJ.showProgress(0, 0);
		IJ.showTime(img, img.getStartTime(), exitmsg);

	}

	private ShollOptions getOptions() {
		if (options == null)
			options = new ShollOptions(true);
		return options;
	}

	/**
	 * Assigns options to the plugin.
	 *
	 * @param options
	 *            the {@link ShollOptions} instance use to customize the plugin's
	 *            output.
	 */
	public void setOptions(final ShollOptions options) {
		options.instanceAttatchedToPlugin = true;
		this.options = options;
	}

	private void saveParametersToPreferences() {

		final HashMap<Integer, Boolean> boolPrefs = new HashMap<>();
		boolPrefs.put(ShollOptions.TRIM_BOUNDS, trimBounds);
		boolPrefs.put(ShollOptions.INFER_PRIMARY, inferPrimary);
		boolPrefs.put(ShollOptions.CURVE_FITTING, fitCurve);
		boolPrefs.put(ShollOptions.VERBOSE, verbose);
		boolPrefs.put(ShollOptions.SHOLL_N, shollN);
		boolPrefs.put(ShollOptions.SHOLL_NS, shollNS);
		boolPrefs.put(ShollOptions.SHOLL_SLOG, shollSLOG);
		boolPrefs.put(ShollOptions.SHOLL_LOG, shollLOG);
		boolPrefs.put(ShollOptions.GUESS_LOG_METHOD, chooseLog);
		boolPrefs.put(ShollOptions.SHOW_MASK, mask);
		boolPrefs.put(ShollOptions.OVERLAY_SHELLS, overlayShells);
		boolPrefs.put(ShollOptions.SAVE_FILES, save);
		boolPrefs.put(ShollOptions.HIDE_SAVED_FILES, hideSaved);
		boolPrefs.put(ShollOptions.SKIP_SINGLE_VOXELS, skipSingleVoxels);

		int prefs = options.getBooleanPrefs();
		for (final Entry<Integer, Boolean> entry : boolPrefs.entrySet()) {
			final int key = entry.getKey();
			if (entry.getValue())
				prefs |= key;
			else
				prefs &= ~key;
		}
		options.setBooleanPrefs(prefs);
		options.setStringPreference(ShollOptions.START_RADIUS_KEY, String.format("%.2f", startRadius));
		options.setStringPreference(ShollOptions.END_RADIUS_KEY, String.format("%.2f", endRadius));
		options.setStringPreference(ShollOptions.STEP_SIZE_KEY, String.format("%.2f", incStep));
		options.setStringPreference(ShollOptions.NSAMPLES_KEY, nSpans);
		options.setStringPreference(ShollOptions.INTEGRATION_KEY, binChoice);
		options.setStringPreference(ShollOptions.ENCLOSING_RADIUS_KEY, enclosingCutOff);
		options.setStringPreference(ShollOptions.PRIMARY_BRANCHES_KEY, String.format("%.0f", incStep));
		options.setStringPreference(ShollOptions.POLYNOMIAL_INDEX_KEY, polyChoice);
		options.setStringPreference(ShollOptions.NORMALIZER_INDEX_KEY, normChoice);
		if (orthoChord)
			options.setStringPreference(ShollOptions.QUAD_CHOICE_KEY, quadChoice);
		if (validPath)
			options.setStringPreference(ShollOptions.SAVE_DIR_KEY, imgPath);
		options.saveStringPreferences();
	}

	private void setParametersFromPreferences() {
		final int prefs = options.getBooleanPrefs();
		trimBounds = (prefs & ShollOptions.TRIM_BOUNDS) != 0;
		inferPrimary = (prefs & ShollOptions.INFER_PRIMARY) != 0;
		fitCurve = (prefs & ShollOptions.CURVE_FITTING) != 0;
		verbose = (prefs & ShollOptions.VERBOSE) != 0;
		shollN = (prefs & ShollOptions.SHOLL_N) != 0;
		shollNS = (prefs & ShollOptions.SHOLL_NS) != 0;
		shollSLOG = (prefs & ShollOptions.SHOLL_SLOG) != 0;
		shollLOG = (prefs & ShollOptions.SHOLL_LOG) != 0;
		chooseLog = (prefs & ShollOptions.GUESS_LOG_METHOD) != 0;
		mask = (prefs & ShollOptions.SHOW_MASK) != 0;
		overlayShells = (prefs & ShollOptions.OVERLAY_SHELLS) != 0;
		save = (prefs & ShollOptions.SAVE_FILES) != 0;
		hideSaved = (prefs & ShollOptions.HIDE_SAVED_FILES) != 0;
		skipSingleVoxels = (prefs & ShollOptions.SKIP_SINGLE_VOXELS) != 0;

		startRadius = options.getDoubleFromHashMap(ShollOptions.START_RADIUS_KEY, 10.0);
		endRadius = options.getDoubleFromHashMap(ShollOptions.END_RADIUS_KEY, 100.0);
		incStep = options.getDoubleFromHashMap(ShollOptions.STEP_SIZE_KEY, 0);
		quadChoice = options.getIntFromHashMap(ShollOptions.QUAD_CHOICE_KEY, 0);
		nSpans = options.getIntFromHashMap(ShollOptions.NSAMPLES_KEY, 1);
		binChoice = options.getIntFromHashMap(ShollOptions.INTEGRATION_KEY, BIN_AVERAGE);
		enclosingCutOff = options.getIntFromHashMap(ShollOptions.ENCLOSING_RADIUS_KEY, 1);
		primaryBranches = options.getDoubleFromHashMap(ShollOptions.PRIMARY_BRANCHES_KEY, Double.NaN);
		polyChoice = options.getIntFromHashMap(ShollOptions.POLYNOMIAL_INDEX_KEY, DEGREES.length - 1);
		normChoice = options.getIntFromHashMap(ShollOptions.NORMALIZER_INDEX_KEY, 0);
		imgPath = options.getStringFromHashMap(ShollOptions.SAVE_DIR_KEY, null);
	}

	/**
	 * Tries to retrieve a valid startup ROI. Prompts the user for a new ROI if
	 * nothing appropriate was found (if the plugin has not been called from a
	 * macro or script).
	 *
	 * @return the startup ROI
	 *
	 * @see <a href= "http://imagej.net/Sholl_Analysis#Startup_ROI">Startup_ROI
	 *      </a>
	 */
	private Roi getStartupROI() {
		Roi roi = img.getRoi();
		final boolean validRoi = roi != null && (roi.getType() == Roi.LINE || roi.getType() == Roi.POINT);
		if (!IJ.macroRunning() && !validRoi) {
			img.killRoi();
			Toolbar.getInstance().setTool("line");
			final EnhancedWaitForUserDialog wd = new EnhancedWaitForUserDialog(
					"Please define the largest Sholl radius by creating\n"
							+ "a straight line starting at the center of analysis.\n"
							+ "(Hold down \"Shift\" to draw an orthogonal radius)\n \n"
							+ "Alternatively, define the focus of the arbor using\n"
							+ "the Point/Multi-point Selection Tool.");
			wd.addHyperlink(URL + "#Startup_ROI");
			wd.show();
			if (wd.escPressed())
				return null;
			roi = img.getRoi();
		}
		return roi;
	}

	/**
	 * Performs curve fitting, adds the fitted curve to the Sholl plot and
	 * appends descriptors related to the fit to the summary table. Returns
	 * fitted values or null if values.length is less than SMALLEST_DATASET
	 */
	private double[] getFittedProfile(final double[][] values, final int method, final ResultsTable rt,
			final ShollPlot plot) {

		final int size = values.length;
		final double[] x = new double[size];
		final double[] y = new double[size];
		for (int i = 0; i < size; i++) {
			x[i] = values[i][0];
			y[i] = values[i][1];
		}

		// Define a global analysis title
		final String longtitle = "Sholl Profile (" + SHOLL_TYPES[method] + ") for " + getDescription();

		// Abort curve fitting when dealing with small datasets that are prone
		// to inflated coefficients of determination
		if (fitCurve && size <= SMALLEST_DATASET) {
			IJ.log(longtitle + ":\nCurve fitting not performed: Not enough data points\n" + "At least "
					+ (SMALLEST_DATASET + 1) + " pairs of values are required for curve fitting.");
			return null;
		}

		// Perform fitting
		final CurveFitter cf = new CurveFitter(x, y);
		// cf.setRestarts(4); // default: 2;
		// cf.setMaxIterations(50000); //default: 25000

		if (method == SHOLL_N) {
			if (DEGREES[polyChoice].startsWith("2")) {
				cf.doFit(CurveFitter.POLY2, false);
			} else if (DEGREES[polyChoice].startsWith("3")) {
				cf.doFit(CurveFitter.POLY3, false);
			} else if (DEGREES[polyChoice].startsWith("4")) {
				cf.doFit(CurveFitter.POLY4, false);
			} else if (DEGREES[polyChoice].startsWith("5")) {
				cf.doFit(CurveFitter.POLY5, false);
			} else if (DEGREES[polyChoice].startsWith("6")) {
				cf.doFit(CurveFitter.POLY6, false);
			} else if (DEGREES[polyChoice].startsWith("7")) {
				cf.doFit(CurveFitter.POLY7, false);
			} else if (DEGREES[polyChoice].startsWith("8")) {
				cf.doFit(CurveFitter.POLY8, false);
			} else {
				IJ.showStatus("Choosing polynomial of best fit...");
				if (verbose)
					IJ.log("\n*** Choosing polynomial of best fit for " + getDescription() + "...");
				cf.doFit(getBestPolyFit(x, y), false);
			}

		} else if (method == SHOLL_NS) {
			cf.doFit(CurveFitter.POWER, false);
		} else if (method == SHOLL_LOG) {
			cf.doFit(CurveFitter.EXP_WITH_OFFSET, false);
		}

		// IJ.showStatus("Curve fitter status: " + cf.getStatusString());
		final double[] parameters = cf.getParams();
		final int degree = cf.getNumParams() - 1;

		// Get fitted data
		final double[] fy = new double[size];
		for (int i = 0; i < size; i++)
			fy[i] = cf.f(parameters, x[i]);

		// Initialize plotLabel
		final StringBuffer plotLabel = new StringBuffer();

		// Register quality of fit
		plotLabel.append("R\u00B2= " + IJ.d2s(cf.getRSquared(), 3));

		// Plot fitted curve
		if (plot != null) {
			plot.setColor(Color.BLUE);
			plot.addPoints(x, fy, ShollPlot.THICK_LINE);
		}

		if (verbose) {
			IJ.log("\n*** " + longtitle + ", fitting details:" + cf.getResultString());
		}

		if (method == SHOLL_N) {

			double cv = 0d; // Polyn. regression: ordinate of maximum
			double cr = 0d; // Polyn. regression: abscissa of maximum
			double mv = 0d; // Polyn. regression: Average value
			double rif = Double.NaN; // Polyn. regression: Ramification index

			// Get coordinates of cv, the local maximum of polynomial. We'll
			// iterate around the index of highest fitted value to retrieve
			// values with a precision of 1/fMetricsPrecision of radius step
			// size
			final int maxIdx = CurveFitter.getMax(fy);
			final double crLeft = (x[Math.max(maxIdx - 1, 0)] + x[maxIdx]) / 2;
			final double crRight = (x[Math.min(maxIdx + 1, size - 1)] + x[maxIdx]) / 2;
			final double crStep = (crRight - crLeft) / fMetricsPrecision;
			double crTmp, cvTmp;
			for (int i = 0; i < fMetricsPrecision; i++) {
				crTmp = crLeft + (i * crStep);
				cvTmp = cf.f(parameters, crTmp);
				if (cvTmp > cv) {
					cv = cvTmp;
					cr = crTmp;
				}
			}

			// Calculate mv, the mean value of the fitted polynomial between
			// NonZeroStartRadius and NonZeroEndRadius. We will define it as
			// a Riemann sum calculated with a 1/fMetricsPrecision of radius
			// step size. For a walk-through, see eg,
			// http://archives.math.utk.edu/visual.calculus/5/average.1/
			final double[] xRange = Tools.getMinMax(x);
			final int subintervals = size * fMetricsPrecision;
			final double deltaX = (xRange[1] - xRange[0]) / subintervals;
			for (int i = 0; i < subintervals; i++) {
				final double xi = xRange[0] + (i * deltaX);
				final double f_xi = cf.f(parameters, xi);
				mv += f_xi * deltaX;
			}
			mv = (1 / (xRange[1] - xRange[0])) * mv;

			// Highlight mean value on the plot
			// if (plot != null) {
			// // plot.drawHorizontalLine(mv, Color.LIGHT_GRAY);
			// plot.setLineWidth(1);
			// plot.setColor(Color.LIGHT_GRAY);
			// plot.drawLine(xRange[0], mv, xRange[1], mv);
			// }

			// Calculate the "fitted" ramification index
			if (inferPrimary)
				rif = cv / y[0];
			else if (!(primaryBranches == 0 || Double.isNaN(primaryBranches)))
				rif = cv / primaryBranches;

			// Register parameters
			plotLabel.append("\nNm= " + IJ.d2s(cv, 2));
			plotLabel.append("\nrc= " + IJ.d2s(cr, 2));
			plotLabel.append("\nNav= " + IJ.d2s(mv, 2));
			plotLabel.append("\n" + Sholl_Utils.ordinal(degree)).append(" degree");

			rt.addValue("Critical value", cv);
			rt.addValue("Critical radius", cr);
			rt.addValue("Mean value", mv);
			rt.addValue("Ramification index (fit)", rif);
			final double[] moments = getMoments(fy);
			if ((metrics & ShollOptions.SKEWNESS) != 0)
				rt.addValue("Skewness (fit)", moments[2]);
			if ((metrics & ShollOptions.KURTOSIS) != 0)
				rt.addValue("Kurtosis (fit)", moments[3]);
			rt.addValue("Polyn. degree", degree);
			rt.addValue("Polyn. R^2", cf.getRSquared());

		}

		if (plot != null && plotLabels) {
			plot.drawLabel(plotLabel.toString(), Color.BLACK);
			// remove legend to mimic previous versions
			plot.setLegend("Sampled Data\nPoyn. fit", 0);
		}

		return fy;

	}

	/**
	 * Remove zeros and Not-a-Number (NaN) values from data. Zero intersections
	 * are problematic for logs and polynomial fits. Long stretches of zeros
	 * (e.g., caused by discontinuous arbors) often cause sharp "bumps" on the
	 * fitted curve. Setting zeros to NaN is not option as it would impact the
	 * CurveFitter.
	 */
	private double[][] getNonZeroValues(final double[] xpoints, final double[] ypoints) {

		final int size = Math.min(xpoints.length, ypoints.length);
		int i, j, nsize = 0;

		for (i = 0; i < size; i++) {
			if (xpoints[i] > 0.0 && ypoints[i] > 0.0)
				nsize++;
		}

		final double[][] values = new double[nsize][2];
		for (i = 0, j = 0; i < size; i++) {
			if (xpoints[i] > 0.0 && ypoints[i] > 0.0) {
				values[j][0] = xpoints[i];
				values[j++][1] = ypoints[i];
			}
		}

		return values;

	}

	/**
	 * Obtains the "Determination Ratio", used to choose between semi-log and
	 * log-log methods: (R^2 semi-log regression)/(R^2 log-log regression)
	 */
	private double getDeterminationRatio(final double semilog[][], final double[][] loglog) {

		final int size = semilog.length;
		final double[] slx = new double[size];
		final double[] sly = new double[size];
		final double[] llx = new double[size];
		final double[] lly = new double[size];

		for (int i = 0; i < size; i++) {
			slx[i] = semilog[i][0];
			sly[i] = semilog[i][1];
			llx[i] = loglog[i][0];
			lly[i] = loglog[i][1];
		}
		final CurveFitter cf1 = new CurveFitter(slx, sly);
		final CurveFitter cf2 = new CurveFitter(llx, lly);
		cf1.doFit(CurveFitter.STRAIGHT_LINE, false);
		cf2.doFit(CurveFitter.STRAIGHT_LINE, false);
		final double rsqrd1 = cf1.getRSquared();
		final double rsqrd2 = cf2.getRSquared();

		if (verbose) {
			// final String norm = is3D ? NORMS3D[normChoice] :
			// NORMS2D[normChoice];
			IJ.log("\n*** Choosing normalization method for " + getDescription() + "...");
			IJ.log("Semi-log: R^2= " + IJ.d2s(rsqrd1, 5) + "... " + cf1.getStatusString());
			IJ.log("Log-log: R^2= " + IJ.d2s(rsqrd2, 5) + "... " + cf2.getStatusString());
		}

		return rsqrd1 / Math.max(Double.MIN_VALUE, rsqrd2);

	}

	/**
	 * Guesses the polynomial of best fit by comparison of coefficient of
	 * determination
	 */
	private int getBestPolyFit(final double x[], final double[] y) {

		final int[] polyList = { CurveFitter.POLY2, CurveFitter.POLY3, CurveFitter.POLY4, CurveFitter.POLY5,
				CurveFitter.POLY6, CurveFitter.POLY7, CurveFitter.POLY8 };

		int bestFit = 0;
		double bestRSquared = 0.0;
		final double[] listRSquared = new double[polyList.length];

		for (int i = 0; i < polyList.length; i++) {

			final CurveFitter cf = new CurveFitter(x, y);
			// cf.setRestarts(4); // default: 2;
			// cf.setMaxIterations(50000); //default: 25000
			cf.doFit(polyList[i], false);
			listRSquared[i] = cf.getRSquared();
			if (listRSquared[i] > bestRSquared) {
				bestRSquared = listRSquared[i];
				bestFit = i;
			}
			if (verbose)
				IJ.log(CurveFitter.fitList[polyList[i]] + ": R^2= " + IJ.d2s(listRSquared[i], 5) + "... "
						+ cf.getStatusString());

		}

		return polyList[bestFit];
	}

	/**
	 * Creates the main dialog (csvPrompt imports tabular data). Returns false
	 * if dialog was canceled or dialogItemChanged() if dialog was OKed.
	 */
	private boolean bitmapPrompt(final double chordAngle, final boolean is3D) {

		if (!interactiveMode)
			return true;

		final EnhancedGenericDialog gd = new EnhancedGenericDialog("Sholl Analysis v" + VERSION);

		final Font headerFont = new Font("SansSerif", Font.BOLD, 12);
		final int xIndent = 42;

		// Part I: Definition of Shells
		gd.setInsets(0, 0, 0);
		gd.addHyperlinkMessage("I. Definition of Shells:", headerFont, Color.BLACK, URL + "#Definition_of_Shells");
		gd.addNumericField("Starting radius", startRadius, 2, 9, unit);
		gd.addNumericField("Ending radius", endRadius, 2, 9, unit);
		gd.addNumericField("Radius_step size", incStep, 2, 9, unit);

		// If an orthogonal chord exists, prompt for hemicircle/hemisphere
		// analysis
		orthoChord = (chordAngle > -1 && chordAngle % 90 == 0);
		if (orthoChord) {
			if (chordAngle == 90.0) {
				quads[0] = QUAD_EAST;
				quads[1] = QUAD_WEST;
			} else {
				quads[0] = QUAD_NORTH;
				quads[1] = QUAD_SOUTH;
			}
			gd.setInsets(0, xIndent, 0);
			gd.addCheckbox("Restrict analysis to hemi" + (is3D ? "sphere:" : "circle:"), trimBounds);
			gd.setInsets(0, 0, 0);
			gd.addChoice("_", quads, quads[quadChoice]);
		}

		// Part II: Multiple samples (2D) and noise filtering (3D)
		if (is3D) {
			gd.setInsets(2, 0, 2);
			gd.addHyperlinkMessage("II. Noise Reduction:", headerFont, Color.BLACK,
					URL + "#Multiple_Samples_and_Noise_Reduction");
			gd.setInsets(0, xIndent, 0);
			gd.addCheckbox("Ignore isolated (6-connected) voxels", skipSingleVoxels);
		} else {
			gd.setInsets(10, 0, 2);
			gd.addHyperlinkMessage("II. Multiple Samples per Radius:", headerFont, Color.BLACK,
					URL + "#Multiple_Samples_and_Noise_Reduction");
			gd.addSlider("#_Samples", 1, MAX_N_SPANS, nSpans);
			gd.setInsets(0, 0, 0);
			gd.addChoice("Integration", BIN_TYPES, BIN_TYPES[binChoice]);
		}

		// Part III: Indices and Curve Fitting
		gd.setInsets(10, 0, 2);
		gd.addHyperlinkMessage("III. Descriptors and Curve Fitting:", headerFont, Color.BLACK,
				URL + "#Descriptors_and_Curve_Fitting");
		gd.addNumericField(" Enclosing radius cutoff", enclosingCutOff, 0, 4, "intersection(s)");

		// We'll use the "units" label of the GenericDialog's numeric field to
		// provide some feedback on the usage of multi-point counters
		// (http://imagej.net/Sholl_Analysis#Startup_ROI). This is obviously
		// extremely hacky, but we are already at the limit of customization
		// allowed by GenericDialogs
		final String mpTip = (primaryFromPointRoi)
				? "Multi-point [2-" + multipointCount + "] count: " + String.valueOf((int) primaryBranches)
				: "(Multi-point counter absent)";
		gd.addNumericField("#_Primary branches", primaryBranches, 0, 4, mpTip);
		if (!IJ.macroRunning()) {
			try { // Access "units" label
				final Panel p = (Panel) gd.getComponent(gd.getComponentCount() - 1);
				final Label l = (Label) p.getComponent(1);
				l.setForeground(EnhancedGenericDialog.getDisabledComponentColor());
			} catch (final Exception ignored) {
			}
		}

		gd.setInsets(0, 2 * xIndent, 0);
		gd.addCheckbox("Infer from starting radius", inferPrimary);
		gd.setInsets(6, xIndent, 0);
		gd.addCheckbox("Fit profile and compute descriptors", fitCurve);
		gd.setInsets(3, 2 * xIndent, 0);
		gd.addCheckbox("Show fitting details", verbose);

		// Part IV: Sholl Methods
		gd.setInsets(10, 0, 2);
		gd.addHyperlinkMessage("IV. Sholl Methods:", headerFont, Color.BLACK, URL + "#Choice_of_Methods");
		gd.setInsets(0, xIndent / 2, 2);
		gd.addMessage("Profiles Without Normalization:");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Linear", shollN);
		gd.setInsets(0, 0, 0);
		gd.addChoice("Polynomial", DEGREES, DEGREES[polyChoice]);

		gd.setInsets(8, xIndent / 2, 2);
		gd.addMessage("Normalized Profiles:");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckboxGroup(2, 2, new String[] { "Most informative", SHOLL_TYPES[SHOLL_NS], SHOLL_TYPES[SHOLL_SLOG],
				SHOLL_TYPES[SHOLL_LOG] }, new boolean[] { chooseLog, shollNS, shollSLOG, shollLOG });
		gd.setInsets(0, 0, 0);
		if (is3D) {
			gd.addChoice("Normalizer", NORMS3D, NORMS3D[normChoice]);
		} else {
			gd.addChoice("Normalizer", NORMS2D, NORMS2D[normChoice]);
		}

		// Part V: Mask and outputs
		gd.setInsets(10, 0, 2);
		gd.addHyperlinkMessage("V. Output Options:", headerFont, Color.BLACK, URL + "#Output_Options");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Create intersections mask", mask);
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox((is3D) ? "Overlay intersection points"
			: "Overlay sampling shells and intersection points", overlayShells);

		// Offer to save results
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Save results to:", save);
		gd.setInsets(-5, 0, 0);
		gd.addDirectoryField("Directory", imgPath, 15);
		gd.setInsets(0, 2 * xIndent, 0);
		gd.addCheckbox("Do not display saved files", hideSaved);

		// Add listener and scroll bars. Update prompt and status bar before
		// displaying it
		gd.addDialogListener(this);
		dialogItemChanged(gd, null);

		gd.addCitationMessage();
		gd.assignPopupToHelpButton(createOptionsMenu(gd));
		gd.showScrollableDialog();
		if (gd.wasCanceled()) {
			return false;
		} else if (gd.wasOKed()) {
			EnhancedGenericDialog.improveRecording();
			return dialogItemChanged(gd, null);
		} else { // User pressed any other button
			return false;
		}
	}

	/** Applies "Cf. Segmentation" LUT */
	private void applySegmentationLUT() {

		this.ip.resetMinAndMax();
		final double min = this.ip.getMin();
		final double max = this.ip.getMax();
		final double t1 = (min + (lowerT * 255.0 / (max - min)));
		final double t2 = (min + (upperT * 255.0 / (max - min)));

		final byte[] r = new byte[256];
		final byte[] g = new byte[256];
		final byte[] b = new byte[256];
		for (int i = 0; i < 256; i++) {
			if (i >= t1 && i <= t2) {
				r[i] = (byte) 0;
				g[i] = (byte) 100;
				b[i] = (byte) 255;
			} else {
				r[i] = (byte) 255;
				g[i] = (byte) 236;
				b[i] = (byte) 158;
			}
		}
		this.ip.setColorModel(new IndexColorModel(8, 256, r, g, b));
		this.img.updateAndDraw();

	}

	/**
	 * Some users have been measuring the interstitial spaces between neuronal
	 * processes rather than the processes themselves. This creates a warning
	 * message while highlighting the arbor to remember the user that
	 * highlighted pixels are the ones to be measured
	 */
	private void offlineHelp(final GenericDialog parentDialog) {

		// remember image LUT
		final LUT lut = this.ip.getLut();
		final int mode = this.ip.getLutUpdateMode();

		// apply new LUT in new thread to provide a more responsive user
		// interface
		final Thread newThread = new Thread(new Runnable() {
			@Override
			public void run() {
				applySegmentationLUT();
			}
		});
		newThread.start();

		// present dialog
		final StringBuilder sb = new StringBuilder();
		sb.append("<html>");
		sb.append("<div WIDTH=400>");
		sb.append("Pixels highlighted in ");
		sb.append("<span style='background-color:#c0c0c0;color:#0000ff;font-weight:bold;'>&nbsp;Blue&nbsp;</span> ");
		sb.append("will be interpreted as <i>arbor</i>. Pixels in ");
		sb.append("<span style='background-color:#b0b0b0;color:#ffff00;font-weight:bold;'>&nbsp;Yellow&nbsp;</span> ");
		sb.append("will be interpreted as <i>background</i>. ");
		sb.append("Make sure you are sampling neuronal processes and not the interstitial spaces between them!");
		sb.append("<br><br>");
		sb.append("<b>Segmentation values:</b><br>");
		sb.append("&emsp;Lower threshold (lowest intensity in arbor):&ensp<tt>").append(IJ.d2s(lowerT, 1))
				.append("</tt><br>");
		sb.append("&emsp;Upper threshold (brightest intensity in arbor):&ensp<tt>").append(IJ.d2s(upperT, 1))
				.append("</tt><br>");
		sb.append("&emsp;Intensity at analysis center (x=").append(String.valueOf(x)).append(", y=")
				.append(String.valueOf(y)).append(", z=").append(String.valueOf(z)).append(", ch=")
				.append(String.valueOf(channel)).append("):&ensp<tt>").append(IJ.d2s(this.ip.get(x, y), 1))
				.append("</tt>");
		sb.append("<br><br>");
		sb.append("<b>Image details:</b><br>");
		sb.append("&emsp;Image type:&ensp;").append(String.valueOf(this.ip.getBitDepth())).append("-bit")
				.append((is3D) ? " (3D)" : " (2D)").append("<br>");
		sb.append("&emsp;Binary image?&ensp;").append(String.valueOf(this.ip.isBinary())).append("</tt><br>");
		sb.append("&emsp;Multi-channel image?&ensp;").append(String.valueOf(this.img.isComposite()))
				.append("</tt><br>");
		sb.append("&emsp;Spatial units:&ensp;<tt>").append(unit).append("</tt><br>");
		sb.append("&emsp;Inverted LUT (<i>Image>Lookup Tables>Invert LUT</i>)?&ensp<tt>")
				.append(String.valueOf(this.ip.isInvertedLut())).append("</tt><br>");
		sb.append("&emsp;Image saved locally?&ensp;").append(String.valueOf(validPath)).append("</tt>");
		sb.append("<br><br>");
		sb.append("<b>Analysis options:</b><br>");
		sb.append("&emsp;Orthogonal restriction allowed?&ensp<tt>").append(String.valueOf(orthoChord))
				.append("</tt><br>");
		sb.append("&emsp;Repetead measures allowed?&ensp<tt>").append(String.valueOf(!is3D)).append("</tt><br>");
		sb.append("&emsp;Noise supression allowed?&ensp<tt>").append(String.valueOf(is3D)).append("</tt><br>");
		sb.append("&emsp;Saving options available?&ensp<tt>").append(String.valueOf(validPath)).append("</tt><br>");
		sb.append("&emsp;Multi-point ROIs marking primary branches:&ensp<tt>")
				.append(String.valueOf(Math.max(0, multipointCount - 1))).append("</tt>");
		sb.append("<br><br>");
		sb.append("<b>Other settings:</b><br>");
		sb.append("&emsp;Black background (<i>Process>Binary>Options...</i>)?&ensp<tt>")
				.append(String.valueOf(Prefs.blackBackground)).append("</tt>");
		sb.append("</div>");
		sb.append("</html>");
		new HTMLDialog(parentDialog, "Segmentation Details", sb.toString());

		// HTMLDialog dismissed: revert to initial state
		this.ip.setLut(lut);
		this.ip.setThreshold(lowerT, upperT, mode);
		this.img.updateAndDraw();
	}

	/**
	 * Retrieves values from the dialog, disabling dialog components that are
	 * not applicable. Returns false if no analysis method was chosen
	 *
	 * @param gd
	 *            reference to the GenericDialog
	 * @param e
	 *            the event generated by the user in the dialog
	 * @return true, if options are valid
	 */
	@Override
	public boolean dialogItemChanged(final GenericDialog gd, final AWTEvent e) {

		// components of GenericDialog
		final Vector<?> numericfields = gd.getNumericFields();
		final Vector<?> stringfields = gd.getStringFields();

		final Vector<?> choices = gd.getChoices();
		final Vector<?> checkboxes = gd.getCheckboxes();

		// options common to bitmapPrompt() and csvPrompt()
		final TextField ieprimaryBranches, ieimgPath;
		final Choice iepolyChoice, ienormChoice;
		final Checkbox ieinferPrimary, iechooseLog, ieshollNS, ieshollSLOG, ieshollLOG, iemask, iesave, iehideSaved;

		// options specific to bitmapPrompt();
		Choice iequadChoice = null, iebinChoice = null;

		final Object source = (e == null) ? null : e.getSource();
		int checkboxCounter = 0;
		int choiceCounter = 0;
		int numFieldCounter = 0;
		int strFieldCounter = 0;

		if (isCSV) { // csvPrompt()

			if (isTableRequired()) {
				setDescription(gd.getNextString(), false);
				strFieldCounter++;

				// Get columns choices and ensure rColumn and cColumn are not
				// the same
				rColumn = gd.getNextChoiceIndex();
				cColumn = gd.getNextChoiceIndex();
				final Choice ierColumn = (Choice) choices.elementAt(choiceCounter++);
				final Choice iecColumn = (Choice) choices.elementAt(choiceCounter++);
				if (rColumn == cColumn) {
					final int newChoice = (rColumn < ierColumn.getItemCount() - 1) ? rColumn + 1 : 0;
					if (source.equals(ierColumn))
						iecColumn.select(newChoice);
					else if (source.equals(iecColumn))
						ierColumn.select(newChoice);
				}

				limitCSV = gd.getNextBoolean();
				checkboxCounter++;
				is3D = gd.getNextBoolean();
				checkboxCounter++;
			}

			enclosingCutOff = (int) Math.max(1, gd.getNextNumber());
			primaryBranches = gd.getNextNumber();
			if (primaryBranches <= 0)
				primaryBranches = Double.NaN;
			numFieldCounter = 1;
			ieprimaryBranches = (TextField) numericfields.elementAt(numFieldCounter++);
			inferPrimary = gd.getNextBoolean();
			ieinferPrimary = (Checkbox) checkboxes.elementAt(checkboxCounter++);

			shollN = gd.getNextBoolean();
			checkboxCounter++;
			polyChoice = gd.getNextChoiceIndex();
			iepolyChoice = (Choice) choices.elementAt(choiceCounter++);

			chooseLog = gd.getNextBoolean();
			iechooseLog = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollNS = gd.getNextBoolean();
			ieshollNS = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollSLOG = gd.getNextBoolean();
			ieshollSLOG = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollLOG = gd.getNextBoolean();
			ieshollLOG = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			normChoice = gd.getNextChoiceIndex();
			ienormChoice = (Choice) choices.elementAt(choiceCounter++);

			verbose = gd.getNextBoolean();
			checkboxCounter++;

		} else { // bitmapPrompt()

			// Part I: Definition of Shells
			startRadius = Math.max(0, gd.getNextNumber());
			// final TextField iestartRadius =
			// (TextField)numericfields.elementAt(fieldCounter++);
			numFieldCounter++;
			endRadius = Math.max(0, gd.getNextNumber());
			final TextField ieendRadius = (TextField) numericfields.elementAt(numFieldCounter++);
			// fieldCounter++;
			incStep = Math.max(0, gd.getNextNumber());
			// final TextField ieincStep =
			// (TextField)numericfields.elementAt(fieldCounter++);
			numFieldCounter++;
			if (endRadius <= startRadius || endRadius <= incStep) {
				ieendRadius.setForeground(Color.RED);
				IJ.showStatus("Error: Ending radius out of range!");
				return false;
			}
			ieendRadius.setForeground(Color.BLACK);

			// Orthogonal chord options
			if (orthoChord) {
				trimBounds = gd.getNextBoolean();
				quadChoice = gd.getNextChoiceIndex();
				quadString = (trimBounds) ? quads[quadChoice] : "None";
				checkboxCounter++;
				// final Checkbox ietrimBounds =
				// (Checkbox)checkboxes.elementAt(checkboxCounter++);
				iequadChoice = (Choice) choices.elementAt(choiceCounter++);
				iequadChoice.setEnabled(trimBounds);
			}

			// Part II: Multiple samples (2D) and noise filtering (3D)
			if (is3D) {
				skipSingleVoxels = gd.getNextBoolean();
				checkboxCounter++;
			} else {
				nSpans = Math.min(Math.max((int) gd.getNextNumber(), 1), MAX_N_SPANS);
				numFieldCounter++;
				binChoice = gd.getNextChoiceIndex();
				iebinChoice = (Choice) choices.elementAt(choiceCounter++);
				iebinChoice.setEnabled(nSpans > 1);
			}

			// Part III: Indices and Curve Fitting
			enclosingCutOff = (int) Math.max(1, gd.getNextNumber()); // will
																		// become
																		// zero
																		// if
																		// NaN
			numFieldCounter++;
			primaryBranches = gd.getNextNumber();
			if (primaryBranches <= 0)
				primaryBranches = Double.NaN;
			ieprimaryBranches = (TextField) numericfields.elementAt(numFieldCounter++);
			inferPrimary = gd.getNextBoolean();
			ieinferPrimary = (Checkbox) checkboxes.elementAt(checkboxCounter++);

			fitCurve = gd.getNextBoolean();
			checkboxCounter++;
			verbose = gd.getNextBoolean();
			final Checkbox ieverbose = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			ieverbose.setEnabled(fitCurve);

			// Part IV: Sholl Methods
			shollN = gd.getNextBoolean();
			checkboxCounter++;

			polyChoice = gd.getNextChoiceIndex();
			iepolyChoice = (Choice) choices.elementAt(choiceCounter++);

			chooseLog = gd.getNextBoolean();
			iechooseLog = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollNS = gd.getNextBoolean();
			ieshollNS = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollSLOG = gd.getNextBoolean();
			ieshollSLOG = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			shollLOG = gd.getNextBoolean();
			ieshollLOG = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			normChoice = gd.getNextChoiceIndex();
			ienormChoice = (Choice) choices.elementAt(choiceCounter++);

			// Part V: Mask and outputs
			mask = gd.getNextBoolean();
			iemask = (Checkbox) checkboxes.elementAt(checkboxCounter++);
			iemask.setEnabled(shollN || shollNS || shollSLOG || chooseLog);
			overlayShells = gd.getNextBoolean();
			checkboxCounter++;
		}

		// Retrieve fields common to both prompts
		save = gd.getNextBoolean();
		iesave = (Checkbox) checkboxes.elementAt(checkboxCounter++);
		setExportPath(gd.getNextString());
		ieimgPath = (TextField) stringfields.elementAt(strFieldCounter++);
		hideSaved = gd.getNextBoolean();
		iehideSaved = (Checkbox) checkboxes.elementAt(checkboxCounter++);

		// Disable fields common to both prompts
		iesave.setEnabled(validPath);
		// ieimgPath.setEnabled(save);
		iehideSaved.setEnabled(save);
		iehideSaved.setState(hideSaved && save);
		Color pathFieldForeground = Color.BLACK;
		if (!save)
			pathFieldForeground = EnhancedGenericDialog.getDisabledComponentColor();
		if (!validPath)
			pathFieldForeground = Color.RED;
		ieimgPath.setForeground(pathFieldForeground);
		ieprimaryBranches.setEnabled(!inferPrimary);
		iepolyChoice.setEnabled(fitCurve && shollN); // fitCurve is true if
														// isCSV
		ieshollNS.setEnabled(!chooseLog);
		ieshollSLOG.setEnabled(!chooseLog);
		ieshollLOG.setEnabled(!chooseLog);
		ienormChoice.setEnabled(shollNS || shollSLOG || shollLOG || chooseLog);

		// Disable the OK button if no method is chosen
		final boolean proceed = (shollN || shollNS || shollSLOG || shollLOG || chooseLog);

		// Provide some interactive feedback (of sorts)
		String tipMsg = "";
		if (!proceed)
			tipMsg = "Error: At least one method needs to be chosen!";
		if (source != null) {
			if (source.equals(iequadChoice))
				tipMsg += "The \"Restriction\" option requires an orthogonal line.";
			else if (source.equals(iebinChoice))
				tipMsg += "The \"Integration\" option is disabled with 3D images.";
			else if (source.equals(iepolyChoice))
				tipMsg += "The BAR update site allows fitting to polynomials of higher order.";
			else if (source.equals(ienormChoice))
				tipMsg += "\"Annulus/Spherical shell\" requires non-continuous sampling.";
			else if (source.equals(iechooseLog) || source.equals(ieshollNS) || source.equals(ieshollSLOG)
					|| source.equals(ieshollLOG))
				tipMsg += "Determination ratio chooses most informative method.";
			else if (source.equals(ieprimaryBranches) || source.equals(ieinferPrimary))
				tipMsg += "# Primary branches are used to calculate Schoenen indices.";
			else if (source.equals(iehideSaved))
				tipMsg += "Saving path: " + imgPath;
			else if (!isCSV) {
				if (Double.isNaN(startRadius))
					tipMsg += "Starting radius: 0   ";
				if (Double.isNaN(endRadius))
					tipMsg += "Ending radius: max.   ";
				if (Double.isNaN(incStep) || incStep == 0)
					tipMsg += "Step size: continuous";
			}
		}
		IJ.showStatus(tipMsg);

		return proceed;

	}

	/**
	 * Creates the dialog for tabular data (bitmapPrompt is the main prompt).
	 */
	private boolean csvPrompt() {

		if (!interactiveMode)
			return true;
		if (isTableRequired() && !validTable(csvRT))
			return false;

		final EnhancedGenericDialog gd = new EnhancedGenericDialog("Sholl Analysis v" + VERSION);
		final Font headerFont = new Font("SansSerif", Font.BOLD, 12);
		final int xIndent = 40;
		gd.setInsets(0, 0, 0);

		// Part I: Import options unless analyzeTabularInput() methods have
		// been called
		if (isTableRequired()) {
			gd.addMessage("I. Results Table Import Options:", headerFont);
			gd.addStringField("Name of dataset", getDescription(), 20);
			final String[] headings = csvRT.getHeadings();
			gd.addChoice("Distance column", headings, headings[0]);
			gd.addChoice("Intersections column", headings, headings[1]);
			gd.setInsets(0, xIndent, 0);
			gd.addCheckbox("Restrict analysis to selected rows only (if any)", limitCSV);
			gd.setInsets(0, xIndent, 0);
			gd.addCheckbox("3D data? (uncheck if 2D profile)", is3D);
			gd.setInsets(15, 0, 2);
		}

		// Part II: Indices and Curve Fitting
		gd.addHyperlinkMessage("II. Descriptors and Ramification Indices:", headerFont, Color.BLACK,
				URL + "#Descriptors_and_Curve_Fitting");
		gd.addNumericField("Enclosing radius cutoff", enclosingCutOff, 0, 6, "intersection(s)");
		gd.addNumericField(" #_Primary branches", primaryBranches, 0);
		gd.setInsets(0, 2 * xIndent, 0);
		gd.addCheckbox("Infer from starting radius", inferPrimary);

		// Part III: Sholl Methods
		gd.setInsets(15, 0, 2);
		gd.addHyperlinkMessage("III. Sholl Methods:", headerFont, Color.BLACK, URL + "#Choice_of_Methods");
		gd.setInsets(0, xIndent / 2, 2);
		gd.addMessage("Profiles Without Normalization:");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Linear", shollN);
		gd.setInsets(0, 0, 0);
		gd.addChoice("Polynomial", DEGREES, DEGREES[polyChoice]);

		gd.setInsets(8, xIndent / 2, 2);
		gd.addMessage("Normalized Profiles:");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckboxGroup(2, 2, new String[] { "Most informative", SHOLL_TYPES[SHOLL_NS], SHOLL_TYPES[SHOLL_SLOG],
				SHOLL_TYPES[SHOLL_LOG] }, new boolean[] { chooseLog, shollNS, shollSLOG, shollLOG });

		final String[] norms = new String[NORMS3D.length];
		for (int i = 0; i < norms.length; i++) {
			norms[i] = NORMS2D[i] + "/" + NORMS3D[i];
		}
		gd.setInsets(2, 0, 0);
		gd.addChoice("Normalizer", norms, norms[normChoice]);

		gd.setInsets(15, 0, 2);
		gd.addHyperlinkMessage("IV. Output Options:", headerFont, Color.BLACK, URL + "#Output_Options");
		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Show fitting details", verbose);

		gd.setInsets(0, xIndent, 0);
		gd.addCheckbox("Save results to:", save);
		gd.setInsets(-5, 0, 0);
		gd.addDirectoryField("Directory", imgPath, 15);
		gd.setInsets(0, 2 * xIndent, 0);
		gd.addCheckbox("Do not display saved files", hideSaved);

		gd.addCitationMessage();
		gd.assignPopupToHelpButton(createOptionsMenu(gd));
		gd.addDialogListener(this);
		dialogItemChanged(gd, null);
		gd.showScrollableDialog();

		if (gd.wasCanceled())
			return false;
		else if (gd.wasOKed()) {
			EnhancedGenericDialog.improveRecording();
			return dialogItemChanged(gd, null);
		} else {
			return false;
		}
	}

	/** Checks if table is valid while warning user about it */
	private boolean validTable(final ResultsTable table) {
		if (table == null) {
			return false;
		} else if (table.getHeadings().length < 2 || table.getCounter() < 2) {
			lError("Profile does not contain enough data points.",
					"N.B. At least " + (SMALLEST_DATASET + 1) + " pairs of values are required for curve fitting.");
			return false;
		} else
			return true;
	}

	/**
	 * Measures intersections for each sphere surface (does the actual 3D
	 * analysis). Accepts an array of radii and takes the measurement(s) at each
	 * radius. Requires threshold values to be set beforehand using
	 * {@link #setThreshold(int, int)}.
	 *
	 * @param xc
	 *            the X position of the analysis center
	 * @param yc
	 *            the Y position of the analysis center
	 * @param zc
	 *            the Z position (slice) of the analysis center
	 * @param radii
	 *            the sampling distances
	 * @param img
	 *            The image being analyzed
	 * @return intersection counts (the linear profile of sampled data)
	 * @see #setInteractiveMode(boolean)
	 */
	@Deprecated
	public synchronized double[] analyze3D(final int xc, final int yc, final int zc, final double[] radii,
			final ImagePlus img) {
		IJ.showStatus(
				"Analyzing image (" + radii.length + "shells/" + Prefs.THREADS + "threads). Press \"Esc\" to abort...");
		assembleProfile(xc, yc, zc, radii, 0, 0, skipSingleVoxels, img);
		return getProfile().countsAsArray();
	}

	private synchronized void assembleProfile(final int xc, final int yc, final int zc, final double[] radii,
			final int nSpans, final int binChoice, final boolean skipSingleVoxels, final ImagePlus img) {
		parser = (is3D) ? new ImageParser3D(img) : new ImageParser2D(img);
		parser.setCenterPx(xc, yc, zc);
		parser.setRadii(radii);
		if (is3D)
			((ImageParser3D) parser).setSkipSingleVoxels(skipSingleVoxels);
		else
			((ImageParser2D) parser).setRadiiSpan(nSpans, binChoice);
		parser.setThreshold(lowerT, upperT);
		parser.setHemiShells(quadString);
		final ParserRunner runner = new ParserRunner(parser);
		runner.run();
	}

	/**
	 * Does the actual 2D analysis. Accepts an array of radii and takes the
	 * measurement(s) at each radius. Requires threshold values to be set
	 * beforehand using {@link #setThreshold(int, int)}.
	 *
	 * @param xc
	 *            the X position of the analysis center
	 * @param yc
	 *            the Y position of the analysis center
	 * @param radii
	 *            the sampling distances
	 * @param pixelSize
	 *            pixel dimensions (spatial calibration)
	 * @param binsize
	 *            the number of samples to be retrieved at each radius
	 * @param bintype
	 *            flag for integration of multiple samples:
	 *            {@link #BIN_AVERAGE}, {@link #BIN_MEDIAN} or {@link #BIN_MODE}
	 * @param imp
	 *            ImageProcessor of analyzed image
	 * @return intersection counts (the linear profile of sampled data)
	 * @see #setInteractiveMode(boolean)
	 */
	@Deprecated
	public double[] analyze2D(final int xc, final int yc, final double[] radii, final double pixelSize,
			final int binsize, final int bintype, final ImagePlus imp) {
		IJ.showStatus("Analyzing image (" + radii.length + "shells). Press \"Esc\" to abort...");
		assembleProfile(xc, yc, 1, radii, binsize, bintype, false, img);
		return getProfile().countsAsArray();
	}

	private Profile getProfile() {
		return profile;
	}

	private void setProfile(final Profile profile) {
		this.profile = profile;
	}

	/** Private classes **/
	class ParserRunner implements Runnable {

		private final ImageParser parser;

		public ParserRunner(final ImageParser parser) {
			this.parser = parser;
		}

		@Override
		public void run() {
			parser.parse();
			if (IJ.escapePressed()) {
				IJ.showStatus("Canceling Parsing...");
				parser.terminate();
			}
			if (!parser.successful()) {
				IJ.showStatus("No valid profile retrieved.");
				return;
			}
			setProfile(parser.getProfile());
		}
	}

	/**
	 * Returns the location of pixels clockwise along a (1-pixel wide)
	 * circumference using Bresenham's Circle Algorithm.
	 *
	 * @param cx
	 *            the X position of the analysis center
	 * @param cy
	 *            the Y position of the analysis center
	 * @param radius
	 *            the circumference radius
	 * @return the circumference points
	 */
	@Deprecated
	public int[][] getCircumferencePoints(final int cx, final int cy, final int radius) {
		if (parser != null) {
			return parser.getCircumferencePoints(cx, cy, radius);
		}
		return new ImageParser2D(img).getCircumferencePoints(cx, cy, radius);
	}

	/** Adds Sholl ROIs to input image */
	private void overlayShells() {
		final ShollOverlay so = new ShollOverlay(getProfile(), img, true);
		so.addCenter();
		if (!is3D)
			so.setShellsColor(Roi.getColor());
		so.setPointsColor(Roi.getColor());
		img.setOverlay(so.getOverlay());
	}

	/**
	 * Sets the threshold limits. With grayscale images, the method must be
	 * called upfront since analysis is performed on segmented arbors. Note that
	 * with binary images, background is always considered to be 0,
	 * independently of {@link ij.Prefs#blackBackground}
	 *
	 * @param lower
	 *            the lower threshold value
	 * @param upper
	 *            the upper threshol value
	 */
	public void setThreshold(final int lower, final int upper) {
		this.lowerT = lower;
		this.upperT = upper;
	}

	private ImagePlus makeMask(final String ttl, final double[][] values, final int xc, final int yc,
			final Calibration cal, final boolean floatProcessor) {
		final double[] yvalues = new double[values.length];
		for (int i = 0; i < values.length; i++)
			yvalues[i] = values[i][1];
		return makeMask(ttl, yvalues, xc, yc, cal, floatProcessor);
	}

	/**
	 * Creates a 2D Sholl heatmap by applying measured values to the foreground
	 * pixels of a copy of the analyzed image
	 */
	private ImagePlus makeMask(final String ttl, final double[] values, final int xc, final int yc,
			final Calibration cal, final boolean floatProcessor) {

		if (values == null || parser == null) {
			throw new IllegalArgumentException("Mask cannot be generate before parsing image");
		}

		final ImageProcessor mp = parser.getMaskProcessor(floatProcessor, values);

		// Apply LUT
		final double[] range = Tools.getMinMax(values);
		final boolean logMask = floatProcessor && range[1] < 0;
		final int fcolor = (logMask) ? options.getMaskBackground() : -1;
		final int bcolor = (fcolor == -1) ? options.getMaskBackground() : -1;
		mp.setColorModel(Sholl_Utils.matlabJetColorMap(bcolor, fcolor));
		mp.setMinAndMax(logMask ? range[0] : 0, range[1]);

		final String title = ttl + "_ShollMask.tif";
		final ImagePlus img2 = new ImagePlus(title, mp);

		// Apply calibration, set mask label and mark center of analysis
		img2.setCalibration(cal);
		img2.setRoi(new PointRoi(xc, yc));

		if (validPath && save) {
			IJ.save(img2, imgPath + title);
		}
		return img2;

	}

	/**
	 * Checks if image to be analyzed is valid.
	 *
	 * @param imp
	 *            the image being analyzed
	 * @return true, if valid (8/16 bit, 2D/3D, single or multi-channel)
	 */
	public boolean validateImage(final ImagePlus imp) {
		return (imp != null && imp.getBitDepth() < 24 && imp.getNDimensions() <= 4
				&& !(imp.getNSlices() > 1 && imp.getNFrames() > 1));
	}

	/**
	 * Checks if image is valid (segmented grayscale), sets validPath and
	 * returns its ImageProcessor
	 */
	private ImageProcessor getValidProcessor(final ImagePlus img) {

		ImageProcessor ip = null;
		String exitmsg = "";
		String tipMsg = "Run \"Image>Type>8/16-bit\" to change image type";

		if (img == null) {
			tipMsg = "Press \"Analyze Sample Image\" for a demo";
			exitmsg = "There are no images open.";
		} else {

			final int type = img.getBitDepth();
			if (type == 24)
				exitmsg = "RGB color images are not supported.";
			else if (type == 32)
				exitmsg = "32-bit grayscale images are not supported.";
			else if (img.getNDimensions() > 4 || (img.getNSlices() > 1 && img.getNFrames() > 1))
				exitmsg = "Images with a temporal axis are not supported.";
			else { // 8/16-bit grayscale image

				ip = (img.isComposite()) ? img.getChannelProcessor() : img.getProcessor();
				final double lower = ip.getMinThreshold();
				if (lower != ImageProcessor.NO_THRESHOLD) {
					lowerT = lower;
					upperT = ip.getMaxThreshold();
				} else if (ip.isBinary()) { // binary images: background is zero
					lowerT = upperT = 255;
				} else {
					tipMsg = "Run \"Image>Adjust>Threshold...\" before running the plugin";
					exitmsg = (img.isComposite()) ? "Multi-channel image is not thresholded."
							: "Image is not thresholded.";
				}
			}
		}

		if (!"".equals(exitmsg)) {
			IJ.showStatus("Tip: " + tipMsg + "...");
			lError(exitmsg,
					"This plugin requires a segmented arbor (2D/3D). Either:\n"
							+ "	 - A binary image (Arbor: non-zero value)\n"
							+ "	 - A thresholded grayscale image (8/16-bit)\n"
							+ "	 NB: To threshold a multi-channel image, display it as\n"
							+ "	        grayscale using \"Image>Color>Channels Tool...\"");
			return null;
		}

		// Retrieve image path and check if it is valid
		imgPath = IJ.getDirectory("image");
		if (imgPath == null) {
			validPath = false;
		} else {
			final File dir = new File(imgPath);
			validPath = dir.exists() && dir.isDirectory();
		}

		return ip;
	}

	/** Creates optionsMenu */
	private JPopupMenu createOptionsMenu(final EnhancedGenericDialog gd) {
		final JPopupMenu popup = new JPopupMenu();
		JMenuItem mi;
		final boolean analyzingImage = !isCSV;
		final boolean analyzingTable = isCSV && isTableRequired();
		final boolean analyzingTraces = isCSV && !isTableRequired();

		if (analyzingImage) {
			mi = new JMenuItem("Cf. Segmentation");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(final ActionEvent e) {
					offlineHelp(gd);
				}
			});
			popup.add(mi);
			popup.addSeparator();
		}
		mi = new JMenuItem("Options...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				final Thread newThread = new Thread(new Runnable() {
					@Override
					public void run() {
						if (Recorder.record)
							Recorder.setCommand(ShollOptions.COMMAND_LABEL);
						// IJ.runPlugIn(Options.class.getName(), analyzingImage
						// ? "" : Options.SKIP_BITMAP_OPTIONS_LABEL);
						options.run(analyzingImage ? "" : ShollOptions.SKIP_BITMAP_OPTIONS_LABEL);
						if (Recorder.record)
							Recorder.saveCommand();
					}
				});
				newThread.start();
			}
		});
		popup.add(mi);
		popup.addSeparator();
		mi = new JMenuItem("Analyze image...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				gd.disposeWithouRecording();
				runInBitmapMode();
			}
		});
		mi.setEnabled(analyzingTable);
		popup.add(mi);
		mi = new JMenuItem(analyzingTable ? "Replace input data" : "Analyze sampled profile...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				gd.disposeWithouRecording();
				runInTabularMode(true);
			}
		});
		mi.setEnabled(!analyzingTraces);
		popup.add(mi);
		mi = new JMenuItem("Analyze traced cells...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				gd.disposeWithouRecording();
				IJ.runPlugIn(ShollAnalysisPlugin.class.getName(), "");
			}
		});
		mi.setEnabled(!analyzingTraces);
		popup.add(mi);
		popup.addSeparator();
		mi = new JMenuItem();
		mi = EnhancedGenericDialog.menuItemTrigerringURL("Online documentation", URL);
		popup.add(mi);
		mi = EnhancedGenericDialog.menuItemTriggeringResources();
		popup.add(mi);
		return popup;
	}

	/** Retrieves the median of an array */
	private double getMedian(final double[] array) {
		final int size = array.length;
		Arrays.sort(array);
		final double median;
		if (size % 2 == 0)
			median = (array[size / 2] + array[size / 2 - 1]) / 2;
		else
			median = array[size / 2];
		return median;
	}

	private static void initializeStatsTable() {
		statsTable = new EnhancedResultsTable();
		statsTable.setNaNEmptyCells(true);
	}

	/** Populates the Sholl Summary Table with profile statistics */
	private void populateStatsTable(final String rowLabel, final int xc, final int yc, final int zc,
			final double[][] values) {

		double sumY = 0, maxIntersect = 0, maxR = 0, enclosingR = Double.NaN;

		// Retrieve simple statistics
		final int size = values.length;
		final double[] x = new double[size];
		final double[] y = new double[size];
		for (int i = 0; i < size; i++) {
			x[i] = values[i][0];
			y[i] = values[i][1];
			if (y[i] > maxIntersect) {
				maxIntersect = y[i];
				maxR = x[i];
			}
			if (y[i] >= enclosingCutOff)
				enclosingR = x[i];
			sumY += y[i];
		}

		// Calculate ramification index, the maximum of intersection divided by
		// the n. of primary branches, assumed to be the n. intersections at
		// starting radius
		final double ri;
		if (inferPrimary)
			ri = maxIntersect / y[0];
		else if (!(primaryBranches == 0 || Double.isNaN(primaryBranches)))
			ri = maxIntersect / primaryBranches;
		else
			ri = Double.NaN;

		statsTable.incrementCounter();
		statsTable.setPrecision(options.getScientificNotationAwarePrecision());
		statsTable.addValue("Image", rowLabel);
		if ((metrics & ShollOptions.DIRECTORY) != 0)
			statsTable.addValue("Directory", (validPath) ? imgPath : "Unknown");
		final String comment = options.getCommentString();
		if (comment != null)
			statsTable.addValue("Comment", comment);
		if (!isCSV && img != null && img.isComposite())
			statsTable.addValue("Channel", channel);
		if ((metrics & ShollOptions.UNIT) != 0)
			statsTable.addValue("Unit", unit);
		if (!isCSV && (metrics & ShollOptions.THRESHOLD) != 0) {
			statsTable.addValue("Lower threshold", lowerT);
			statsTable.addValue("Upper threshold", upperT);
		}
		if ((metrics & ShollOptions.CENTER) != 0 && !isCenterUnknown()) {
			statsTable.addValue("X center (px)", xc);
			statsTable.addValue("Y center (px)", yc);
			statsTable.addValue("Z center (slice)", zc);
		}
		if ((metrics & ShollOptions.STARTING_RADIUS) != 0)
			statsTable.addValue("Starting radius", startRadius);
		if ((metrics & ShollOptions.ENDING_RADIUS) != 0)
			statsTable.addValue("Ending radius", endRadius);
		if ((metrics & ShollOptions.RADIUS_STEP) != 0)
			statsTable.addValue("Radius step", stepRadius);
		if ((metrics & ShollOptions.SAMPLES_PER_RADIUS) != 0)
			statsTable.addValue("Samples/radius", (isCSV || is3D) ? 1 : nSpans);
		if ((metrics & ShollOptions.ENCLOSING_RADIUS) != 0)
			statsTable.addValue("Enclosing radius cutoff", enclosingCutOff);
		statsTable.addValue("I branches (user)", (inferPrimary) ? Double.NaN : primaryBranches);
		statsTable.addValue("I branches (inferred)", (inferPrimary) ? y[0] : Double.NaN);
		if ((metrics & ShollOptions.INTERSECTING_RADII) != 0)
			statsTable.addValue("Intersecting radii", size);
		if ((metrics & ShollOptions.SUM_INTERS) != 0)
			statsTable.addValue("Sum inters.", sumY);

		// Calculate skewness and kurtosis of sampled data (linear Sholl);
		final double[] moments = getMoments(y);
		if ((metrics & ShollOptions.MEAN_INTERS) != 0)
			statsTable.addValue("Mean inters.", moments[0]);
		if ((metrics & ShollOptions.MEDIAN_INTERS) != 0)
			statsTable.addValue("Median inters.", getMedian(y));
		if ((metrics & ShollOptions.SKEWNESS) != 0)
			statsTable.addValue("Skewness (sampled)", moments[2]);
		if ((metrics & ShollOptions.KURTOSIS) != 0)
			statsTable.addValue("Kurtosis (sampled)", moments[3]);
		statsTable.addValue("Max inters.", maxIntersect);
		statsTable.addValue("Max inters. radius", maxR);
		statsTable.addValue("Ramification index (sampled)", ri);

		// Calculate the 'center of mass' for the sampled curve (linear Sholl);
		if ((metrics & ShollOptions.CENTROID) != 0) {
			centroid = Sholl_Utils.baryCenter(x, y);
			statsTable.addValue("Centroid radius", centroid[0]);
			statsTable.addValue("Centroid value", centroid[1]);
		}
		if ((metrics & ShollOptions.ENCLOSING_RADIUS) != 0)
			statsTable.addValue("Enclosing radius", enclosingR);
		// rt.addValue("Enclosed field", field);

	}

	/** Transforms data */
	private double[][] transformValues(final double[][] values, final boolean normY, final boolean logY,
			final boolean logX) {

		double x, y;
		final double[][] transValues = new double[values.length][2];

		for (int i = 0; i < values.length; i++) {

			x = values[i][0];
			y = values[i][1];

			if (normY) {
				if (normChoice == AREA_NORM) {
					if (is3D)
						transValues[i][1] = y / (Math.PI * x * x * x * 4 / 3); // Volume
																				// of
																				// sphere
					else
						transValues[i][1] = y / (Math.PI * x * x); // Area of
																	// circle
				} else if (normChoice == PERIMETER_NORM) {
					if (is3D)
						transValues[i][1] = y / (Math.PI * x * x * 4); // Surface
																		// area
																		// of
																		// sphere
					else
						transValues[i][1] = y / (Math.PI * x * 2); // Length of
																	// circumference
				} else if (normChoice == ANNULUS_NORM) {
					final double r1 = x - stepRadius / 2;
					final double r2 = x + stepRadius / 2;
					if (is3D)
						transValues[i][1] = y / (Math.PI * 4 / 3 * (r2 * r2 * r2 - r1 * r1 * r1)); // Volume
																									// of
																									// spherical
																									// shell
					else
						transValues[i][1] = y / (Math.PI * (r2 * r2 - r1 * r1)); // Area
																					// of
																					// annulus
				}
			} else
				transValues[i][1] = y;

			if (logY)
				transValues[i][1] = Math.log(y);

			if (logX)
				transValues[i][0] = Math.log(x);
			else
				transValues[i][0] = x;
		}

		return transValues;

	}

	/** Returns a plot with some axes customizations */
	private ShollPlot plotValues(final String title, final String xLabel, final String yLabel, final double[][] xy) {
		final LinearProfileStats stats = new LinearProfileStats(new Profile(xy));
		final ShollPlot plot = new ShollPlot(title, xLabel, yLabel, stats, true);
		return plot;
	}

	/** Calls plotRegression for both regressions as specified in Options */
	private void plotRegression(final double[][] values, final ShollPlot plot, final ResultsTable rt,
			final String method) {

		final int size = values.length;
		final double[] x = new double[size];
		final double[] y = new double[size];
		for (int i = 0; i < size; i++) {
			x[i] = values[i][0];
			y[i] = values[i][1];
		}
		plotRegression(x, y, false, plot, rt, method);
		if ((metrics & ShollOptions.P1090_REGRESSION) != 0)
			plotRegression(x, y, true, plot, rt, method);
		// remove legend to mimic previous versions
		if (plot != null) plot.setLegend("Sampled Data\n", 0);

	}

	/**
	 * Performs linear regression using the full range data or percentiles 10-90
	 */
	private void plotRegression(final double[] x, final double[] y, final boolean trim, final ShollPlot plot,
			final ResultsTable rt, final String method) {

		final int size = x.length;
		int start = 0;
		int end = size - 1;

		String labelSufix = " (" + method + ")";

		final CurveFitter cf;
		if (trim) {

			labelSufix += " [P10-P90]"; // "[P\u2081\u2080 - P\u2089\u2080]";
			start = (int) (size * 0.10);
			end = end - start;

			// Do not proceed if there are not enough points
			if (end <= SMALLEST_DATASET)
				return;

			final double[] xtrimmed = Arrays.copyOfRange(x, start, end);
			final double[] ytrimmed = Arrays.copyOfRange(y, start, end);

			cf = new CurveFitter(xtrimmed, ytrimmed);

		} else {
			cf = new CurveFitter(x, y);
		}

		cf.doFit(CurveFitter.STRAIGHT_LINE, false);
		final double[] cfparam = cf.getParams();
		// IJ.log("Curve fitter status: " + cf.getStatusString());

		if (verbose) {
			IJ.log("\n*** " + getDescription() + ", regression details" + labelSufix + cf.getResultString());
		}

		final double k = -cfparam[1]; // slope
		final double kIntercept = cfparam[0]; // y-intercept
		final double kRSquared = cf.getRSquared(); // R^2

		rt.addValue("Regression coefficient" + labelSufix, k);
		rt.addValue("Regression intercept" + labelSufix, kIntercept);
		rt.addValue("Regression R^2" + labelSufix, kRSquared);

		if (plot != null) {

			final double x1 = x[start];
			final double x2 = x[end];
			final double y1 = cf.f(cfparam, x1);
			final double y2 = cf.f(cfparam, x2);

			final Color color = (trim) ? Color.RED : Color.BLUE;
			plot.setLineWidth(2);
			plot.setColor(color);
			plot.drawLine(x1, y1, x2, y2);
			plot.setLineWidth(1);

			plot.markPoint(new ShollPoint(0, kIntercept), color);
			if (plotLabels) {
				final StringBuffer label = new StringBuffer();
				label.append("R\u00B2= " + IJ.d2s(kRSquared, 3));
				label.append("\nk= " + IJ.d2s(k, -2));
				label.append("\nIntercept= " + IJ.d2s(kIntercept, 2));
				plot.drawLabel(label.toString(), color);
			}

		}

	}

	/** Saves plot according to imgPath */
	private void savePlot(final Plot plot, final int shollChoice) {

		if (!validPath || (validPath && !hideSaved))
			plot.show();
		if (validPath && save) {
			Recorder.disablePathRecording();
			final String path = imgPath + getDescription() + "_ShollPlot" + SHOLL_TYPES[shollChoice] + ".tif";
			IJ.saveAs(plot.getImagePlus(), "tiff", path);
		}

	}

	/** Creates improved error messages with 'help' and 'action' buttons */
	private void error(final String boldMsg, final String plainMsg, final boolean extended) {

		if (IJ.macroRunning()) {
			final String msg = boldMsg + "\n" + plainMsg;
			IJ.error("Sholl Analysis v" + VERSION + " Error", msg);
		} else {
			final EnhancedGenericDialog gd = new EnhancedGenericDialog("Sholl Analysis v" + VERSION + " Error");
			if (boldMsg != null && !"".equals(boldMsg)) {
				gd.addMessage(boldMsg, new Font("SansSerif", Font.BOLD, 12));
			}
			if (plainMsg != null && !"".equals(plainMsg)) {
				gd.addMessage(plainMsg);
			}
			if (!isCSV) {
				gd.addHyperlinkMessage("Alternatively, use the other commands in the \"Analysis>\n"
						+ "Sholl>\" menu to re-analyze data from previous runs or\n"
						+ "tracings from reconstructed cells", null, Color.DARK_GRAY, URL + "#Importing");
			}
			gd.hideCancelButton();
			if (extended) {
				gd.addHelp(isCSV ? URL + "#Importing" : URL);
				gd.setHelpLabel("Online Help");
				gd.enableYesNoCancel("OK", (isCSV) ? "Import Other Data" : "Analyze Sample Image");
			}
			gd.showDialog();
			if (extended && !gd.wasOKed() && !gd.wasCanceled()) {
				retrieveSampleData();
			}
		}

	}

	/** Re-runs the plugin with sample data */
	private void retrieveSampleData() {
		if (isCSV) {
			if (Recorder.record)
				Recorder.setCommand(null);
			runInTabularMode(true);
		} else {
			run("sample");
		}
	}

	/** Simple error message */
	private void sError(final String msg) {
		if (interactiveMode)
			error("", msg, false);
		else
			IJ.log("[ERROR] " + msg);
	}

	/** Extended error message */
	private void lError(final String mainMsg, final String extraMsg) {
		error(mainMsg, extraMsg, true);
	}

	/** Returns a filename that does not include extension */
	private String trimExtension(String filename) {
		final int index = filename.lastIndexOf(".");
		if (index > -1)
			filename = filename.substring(0, index);
		return filename;
	}

	/**
	 * Returns the mean, variance, skewness and kurtosis of an array of
	 * univariate data. Code from ij.process.ByteStatistics
	 */
	private double[] getMoments(final double values[]) {
		final int npoints = values.length;
		double v, v2, sum1 = 0.0, sum2 = 0.0, sum3 = 0.0, sum4 = 0.0;
		for (int i = 0; i < npoints; i++) {
			v = values[i];
			v2 = v * v;
			sum1 += v;
			sum2 += v2;
			sum3 += v * v2;
			sum4 += v2 * v2;
		}
		final double mean = sum1 / npoints;
		final double mean2 = mean * mean;
		final double variance = sum2 / npoints - mean2;
		final double std = Math.sqrt(variance);
		final double skewness = ((sum3 - 3.0 * mean * sum2) / npoints + 2.0 * mean * mean2) / (variance * std);
		final double kurtosis = (((sum4 - 4.0 * mean * sum3 + 6.0 * mean2 * sum2) / npoints - 3.0 * mean2 * mean2)
				/ (variance * variance) - 3.0);
		return new double[] { mean, variance, skewness, kurtosis };
	}

	/**
	 * Controls the inclusion of fitting details in Sholl plots.
	 *
	 * @param plotLabels
	 *            If {@code true}, plotting labels will be added to plots when
	 *            performing curve fitting
	 */
	public void setPlotLabels(final boolean plotLabels) {
		this.plotLabels = plotLabels;
	}

	/**
	 * Sets the precision used to calculate metrics from fitted data, such as
	 * Nav and Nm.
	 *
	 * @param precision
	 *            The precision value as a fraction of radius step size. Eg,
	 *            {@code 100} sets accuracy to radiusStepSize/100
	 */
	public void setPrecision(final int precision) {
		fMetricsPrecision = precision;
	}

	/**
	 * Alternative to {@link #setPlotLabels(boolean) setPlotLabels()} to be
	 * called by IJ macros using the
	 * <a href="http://imagej.nih.gov/ij/developer/macro/functions.html#call">
	 * call()</a> built-in macro function
	 *
	 * <p>
	 * An error message is displayed in the IJ Log window if
	 * {@code booleanString} can not be parsed. Usage example:
	 * {@code call("sholl.Sholl_Analysis.setPlotLabels", "false");}
	 * </p>
	 *
	 * @param booleanString
	 *            If {@code "true"}, plotting labels will be added.
	 */
	public void setPlotLabels(final String booleanString) {
		if (validateBooleanString(booleanString))
			this.plotLabels = Boolean.valueOf(booleanString);
	}

	/**
	 * Alternative to {@link #setPrecision(int) setPrecision()} to be called by
	 * IJ macros using the
	 * <a href="http://imagej.nih.gov/ij/developer/macro/functions.html#call">
	 * call()</a> built-in macro function
	 *
	 * <p>
	 * An error message is displayed in the IJ Log window if {@code intString}
	 * is invalid. Usage example:
	 * {@code call("sholl.Sholl_Analysis.setPrecision", "1000");}
	 * </p>
	 *
	 * @param intString
	 *            The string integer to set the precision in terms of radius
	 *            step size. Eg, {@code "100"} sets accuracy to
	 *            radiusStepSize/100
	 */
	public void setPrecision(final String intString) {
		if (validateIntString(intString))
			this.fMetricsPrecision = Integer.parseInt(intString);
	}

	private static boolean validateBooleanString(final String string) {
		final boolean valid = string != null && (string.equalsIgnoreCase("true") || string.equalsIgnoreCase("false"));
		if (!valid)
			IJ.log(">>> Sholl Utils: Not a valid option: '" + string + "'");
		return valid;
	}

	private static boolean validateIntString(final String string) {
		boolean valid = true;
		try {
			Integer.parseInt(string);
		} catch (final NumberFormatException e) {
			valid = false;
			IJ.log(">>> Sholl Utils: Not a valid option: '" + string + "'");
		}
		return valid;
	}

	/**
	 * Prompts the user for tabular data, retrieved from several sources
	 * including 1) Importing a new text/csv file; 2) Trying to import data from
	 * the system clipboard; or 3) any other {@link ResultsTable} currently
	 * opened by ImageJ.
	 *
	 * @return A populated Results table or {@code null} if chosen source did
	 *         not contain valid data.
	 */
	private ResultsTable getTable() {

		ResultsTable rt = null;
		final ArrayList<ResultsTable> tables = new ArrayList<>();
		final ArrayList<String> tableTitles = new ArrayList<>();

		final Frame[] windows = WindowManager.getNonImageWindows();
		TextWindow rtWindow;
		for (final Frame w : windows) {
			if (w instanceof TextWindow) {
				rtWindow = (TextWindow) w;
				rt = ((TextWindow) w).getTextPanel().getResultsTable();
				if (rt != null) {
					tables.add(rt);
					tableTitles.add(rtWindow.getTitle());
				}
			}
		}

		final boolean noTablesOpened = tableTitles.isEmpty();

		// Append options for external sources
		tableTitles.add("External file...");
		tableTitles.add("Clipboard");

		// Build prompt
		final GenericDialog gd = new GenericDialog("Choose Sampled Profile");
		final int cols = (tableTitles.size() < 18) ? 1 : 2;
		final int rows = (tableTitles.size() % cols > 0) ? tableTitles.size() / cols + 1 : tableTitles.size() / cols;
		gd.addRadioButtonGroup("Use tabular data of sampled profiles from:",
				tableTitles.toArray(new String[tableTitles.size()]), rows, cols, tableTitles.get(0));
		// gd.hideCancelButton();
		gd.showDialog();

		if (gd.wasCanceled()) {
			return null;

		} else if (gd.wasOKed()) {

			setExportPath(null);
			setDescription("Imported data", false);
			final String choice = gd.getNextRadioButton();
			if ("External file...".equals(choice)) {

				try {
					rt = ResultsTable.open("");
					if (rt != null && validTable(rt)) {
						setExportPath(OpenDialog.getLastDirectory());
						setDescription(OpenDialog.getLastName(), true);
						if (!IJ.macroRunning()) // no need to display table
							rt.show(getDescription());
					}
				} catch (final IOException e) {
					lError("", e.getMessage());
				}

			} else if ("Clipboard".equals(choice)) {

				final String clipboard = Sholl_Utils.getClipboardText();
				final String error = "Clipboard does not seem to contain valid data";
				if (clipboard.isEmpty()) {
					rt = null;
					lError("", error);
					if (Recorder.record)
						Recorder.setCommand(null);
				} else {
					try {
						final File temp = File.createTempFile("IJclipboard", ".txt");
						temp.deleteOnExit();
						final PrintStream out = new PrintStream(temp.getAbsolutePath());
						out.println(clipboard);
						out.close();
						rt = ResultsTable.open(temp.getAbsolutePath());
						if (validTable(rt)) {
							setDescription("Clipboard Data", true);
							if (!IJ.macroRunning()) // no need to display table
								rt.show(getDescription());
						} else {
							lError("", error);
							return null;
						}
					} catch (final IllegalArgumentException | IOException | SecurityException ignored) {
						rt = null;
						lError("", "Could not extract tabular data from clipboard.");
					}
				}
			} else if (!noTablesOpened) {

				rt = tables.get(tableTitles.indexOf(choice));
				if (rt == null)
					lError("", getDescription() + " is no longer available.");
				else if (validTable(rt))
					setDescription(choice, false);
			}

		}

		// We haven't extracted other info from the table. Let's "reset" it here
		incStep = Double.NaN;
		setCenterUnknown();
		setUnit("N.A.");

		return rt;

	}

	/**
	 * Instructs the plugin to parse the specified table expected to contain a
	 * sampled profile. Does nothing if the specified table does not contain the
	 * specified column indices.
	 *
	 * @param rt
	 *            the input {@link ResultsTable}
	 * @param rCol
	 *            the index of the radii column
	 * @param cCol
	 *            the index of the intersections count column
	 * @param threeD
	 *            3D analysis?
	 * @see #setStepRadius(double)
	 * @see #validTable(ResultsTable)
	 * @see #setInteractiveMode(boolean)
	 */
	public void analyzeTabularInput(final ResultsTable rt, final int rCol, final int cCol, final boolean threeD) {
		if (rt != null && rt.columnExists(rCol) && rt.columnExists(cCol)) {
			setIsTableRequired(false);
			csvRT = rt;
			rColumn = rCol;
			cColumn = cCol;
			is3D = threeD;
			limitCSV = false;
			validPath = false;
			imgPath = null;
			runInTabularMode(false);
			csvRT = null;
		}
		setIsTableRequired(true);
	}

	/**
	 * Instructs the plugin to parse the specified file expected to contain a
	 * sampled profile.
	 *
	 * @param csvFile
	 *            the input file expected to contain tabular data in any of the
	 *            formats recognized by {@link ij.measure.ResultsTable}
	 * @param rCol
	 *            the index of the radii column
	 * @param cCol
	 *            the index of the intersections count column
	 * @param threeD
	 *            3D analysis?
	 * @throws IOException
	 *             if file could be opened.
	 * @see #setInteractiveMode(boolean)
	 * @see #setStepRadius(double)
	 */
	public void analyzeTabularInput(final File csvFile, final int rCol, final int cCol, final boolean threeD)
			throws IOException {
		csvRT = ResultsTable.open(csvFile.getAbsolutePath());
		if (csvRT != null && csvRT.getCounter() > 2 && csvRT.columnExists(rCol) && csvRT.columnExists(cCol)) {
			setIsTableRequired(false);
			setExportPath(csvFile.getParent());
			setDescription(trimExtension(csvFile.getName()), false);
			rColumn = rCol;
			cColumn = cCol;
			is3D = threeD;
			runInTabularMode(false);
			csvRT = null;
		} else {
			sError("Profile could not be parsed or it does not contain enough data points.\n" + "N.B.: At least "
					+ (SMALLEST_DATASET + 1) + " pairs of values are required for curve fitting.");
		}
		setIsTableRequired(true);
	}

	/**
	 * Analyzes a sampled profile.
	 *
	 * @param distances
	 *            the array containing radii
	 * @param inters
	 *            the array containing intersection counts
	 * @param threeD
	 *            3D profile?
	 * @see #setInteractiveMode(boolean)
	 */
	public void analyzeProfile(final double[] distances, final double[] inters, final boolean threeD) {
		if (distances != null && inters != null) {
			setIsTableRequired(false);
			radii = distances;
			counts = inters;
			is3D = threeD;
			runInTabularMode(true);
		} else {
			sError("Profile could not be parsed.");
		}
		setIsTableRequired(true);
	}

	private void runInTabularMode(final boolean discardExistingInputTable) {
		isCSV = true;
		if (discardExistingInputTable) {
			csvRT = null;
			limitCSV = false;
		}
		run("csv");
	}

	private void runInBitmapMode() {
		isCSV = false;
		run("");
	}

	/**
	 * @return {@code true} Can analysis of tabular data proceed without an
	 *         input table?
	 */
	private boolean isTableRequired() {
		return this.tableRequired;
	}

	/**
	 * @param required
	 *            if {@code false}, it is assumed that input data has been
	 *            specified through API calls, and tabular data will proceed
	 *            without prompts for input options
	 */
	private void setIsTableRequired(final boolean required) {
		this.tableRequired = required;
		if (!required) {
			this.limitCSV = false;
		}
	}

	/**
	 * @param nBranches
	 *            the number of primary branches to be used in the calculation
	 *            of ramification indices. Must be greater than 0.
	 */
	public void setPrimaryBranches(final double nBranches) {
		if (Double.isNaN(nBranches) && nBranches <= 0)
			return;
		inferPrimary = false;
		primaryBranches = nBranches;
	}

	/**
	 * @param exportDir
	 *            The path to the directory where results should be saved.
	 *            {@code null} not allowed.
	 * @see #setExportPath(String, boolean)
	 */
	public void setExportPath(final String exportDir) {
		setExportPath(exportDir, true);
	}

	/**
	 * @param exportDir
	 *            The path to the directory where results should be saved.
	 *            {@code null} not allowed. Files will only be saved if the
	 *            specified path is valid.
	 * @param displaySavedFiles
	 *            If saved plots and tables should be displayed.
	 */
	public void setExportPath(String exportDir, final boolean displaySavedFiles) {
		if (exportDir == null) {
			validPath = false;
			return;
		}
		if (!exportDir.isEmpty() && !exportDir.endsWith(File.separator))
			exportDir += File.separator;
		try {
			final File dir = new File(exportDir);
			validPath = dir.exists() && dir.isDirectory() && dir.canWrite();
		} catch (final SecurityException ignored) {
			validPath = false;
		}
		if (!validPath)
			save = false;
		imgPath = exportDir;
		hideSaved = !displaySavedFiles;
	}

	/**
	 * @param verbose
	 *            Whether details on curve fitting should be displayed
	 */
	public void setVerbose(final boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Instructs the plugin to run in headless mode or with user interaction.
	 *
	 * @param interactive
	 *            Whether dialog prompts should be displayed to collect input
	 *            from the user
	 */
	public void setInteractiveMode(final boolean interactive) {
		this.interactiveMode = interactive;
	}

	/**
	 * Associates the analysis with an identifier.
	 *
	 * @param label
	 *            the label describing the analysis. It is used in the titles of
	 *            frames and images when displaying results
	 * @param makeUnique
	 *            if {@code true} and ImageJ is already displaying a window
	 *            using the same label as title, a suffix ("-1", "-2", etc.) is
	 *            appended to label to ensure it is unique
	 */
	public void setDescription(String label, final boolean makeUnique) {
		if (makeUnique)
			label = WindowManager.makeUniqueName(label);
		imgTitle = label;
	}

	/**
	 * @return the label describing the analysis
	 */
	public String getDescription() {
		return imgTitle;
	}

	/**
	 * Sets the analysis center.
	 *
	 * @param xc
	 *            the x coordinate (in pixels)
	 * @param yc
	 *            the y coordinate (in pixels)
	 * @param zc
	 *            the z coordinate (slice number)
	 */
	public void setCenter(final int xc, final int yc, final int zc) {
		this.x = xc;
		this.y = yc;
		this.z = zc;
	}

	private void setCenterUnknown() {
		setCenter(-1, -1, -1);
	}

	boolean isCenterUnknown() {
		return (this.x == -1 && this.y == -1 && this.z == -1);
	}

	/**
	 * Sets the unit for sampling distances.
	 *
	 * @param unit
	 *            the physical unit, e.g., "mm"
	 */
	public void setUnit(final String unit) {
		this.unit = unit;
	}

	/**
	 * Sets the radius step size (shell spacing).
	 *
	 * @param stepRadius
	 *            the step size in physical units
	 */
	public void setStepRadius(final double stepRadius) {
		this.stepRadius = stepRadius;
	}

	/**
	 * Returns a reference to the plugin's "Sholl Results" table displaying
	 * summary statistics.
	 *
	 * @return the "Sholl Results" table
	 */
	public EnhancedResultsTable getShollTable() {
		if (statsTable == null || !statsTable.isShowing())
			initializeStatsTable();
		return statsTable;
	}
}


/**
 * Slight improvements to {@link ij.measure.ResultsTable}. This class is only
 * used by IJ1 plugins and is thus deprecated.
 * @deprecated Use {@linkplain ShollTable} instead
 */
@Deprecated
class EnhancedResultsTable extends ResultsTable {

	private boolean unsavedMeasurements;
	private boolean listenerAdded;
	private boolean isShowing;
	private String title;

	/**
	 * Calls the default {@code incrementCounter()} while monitoring unsaved
	 * measurements
	 */
	@Override
	public synchronized void incrementCounter() {
		super.incrementCounter();
		setUnsavedMeasurements(true);
	}

	/**
	 * Deletes the specified row and setting the unsaved measurements flag.
	 */
	@Override
	public synchronized void deleteRow(final int row) {
		super.deleteRow(row);
		setUnsavedMeasurements(true);
	}

	/**
	 * Calls the default {@code show()} method while attaching a WindowListener
	 * used to prompt users to save unsaved measurements when closing
	 * (non-programmatically) the ResultsTable window
	 *
	 * @param windowTitle
	 *            the title of the window displaying the ResultsTable
	 */
	@Override
	public void show(final String windowTitle) {

		super.show(windowTitle);

		if (listenerAdded)
			return;
		final TextWindow window = getWindow(windowTitle);
		if (window == null)
			return;
		window.addWindowListener(new WindowAdapter() {
			@Override
			public void windowDeactivated(final WindowEvent e) {
				final TextWindow tw = (TextWindow) e.getWindow();
				if (tw != null)
					title = tw.getTitle();
			}

			@Override
			public void windowClosed(final WindowEvent e) {
				isShowing = false;
			}

			@Override
			public void windowClosing(final WindowEvent e) {
				try {
					final TextWindow tw = (TextWindow) e.getWindow();
					final EnhancedResultsTable ert = (EnhancedResultsTable) tw.getTextPanel().getResultsTable();
					if (!(ert.getUnsavedMeasurements() && ert.getCounter() > 0))
						return;
					final ImageJ ij = IJ.getInstance();
					final boolean macro = (IJ.macroRunning()) || Interpreter.isBatchMode();
					if (!macro && ij != null && !ij.quitting()) {
						promptForSave(windowTitle);
					}
				} catch (final Exception exc) {
					IJ.log(">>>> An error occurred when closing table:\n" + exc);
				}
			}

		});
		listenerAdded = true;
		isShowing = true;
	}

	/**
	 * Updates the ResultsTable and displays it in its own window if this
	 * ResultsTable is already being displayed, otherwise displays the contents
	 * of this ResultsTable in new window with the specified title.
	 *
	 * @param fallBackTitle
	 *            The window title of the new TextWindow to be opened if current
	 *            table is not being displayed
	 *
	 */
	public void update(final String fallBackTitle) {
		if (title != null && isShowing()) {
			listenerAdded = true;
			show(title);
		} else {
			listenerAdded = false;
			show(fallBackTitle);
		}
	}

	@Override
	public boolean save(final String path) {
		final boolean result = super.save(path);
		setUnsavedMeasurements(!result);
		return result;
	}

	private synchronized boolean promptForSave(final String tableTitle) {
		final GenericDialog gd = new GenericDialog("Unsaved Data");
		gd.addMessage("Save measurements in " + tableTitle + "?", new Font("SansSerif", Font.BOLD, 12));
		gd.addMessage("Data will be discarded if you dismiss this prompt!", new Font("SansSerif", Font.PLAIN, 12),
				EnhancedGenericDialog.getDisabledComponentColor());
		gd.setCancelLabel("No. Discard measurements");
		gd.setOKLabel("Yes. Save to...");
		gd.showDialog();
		if (gd.wasOKed() && (new MeasurementsWriter()).save("")) {
			setUnsavedMeasurements(false);
		}
		return false;
	}

	private TextWindow getWindow(final String title) {
		return (TextWindow) WindowManager.getWindow(title);
	}

	public void setUnsavedMeasurements(final boolean unsavedMeasurements) {
		this.unsavedMeasurements = unsavedMeasurements;
	}

	private boolean getUnsavedMeasurements() {
		return unsavedMeasurements;
	}

	public boolean isShowing() {
		return isShowing;
	}

}
