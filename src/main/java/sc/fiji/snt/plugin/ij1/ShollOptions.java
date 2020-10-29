/*
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
package sc.fiji.snt.plugin.ij1;


import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map.Entry;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import ij.IJ;
import ij.Prefs;
import ij.measure.Measurements;
import ij.plugin.PlugIn;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.Recorder;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.EnhancedGenericDialog;

/**
 * Implements the IJ1 "Sholl Options and Metrics" command.
 *
 * @author Tiago Ferreira
 */
@Deprecated
public class ShollOptions implements PlugIn {

	/** The Menu entry of this plugin as specified in plugins.config **/
	public static final String COMMAND_LABEL = "Metrics & Options...";
	/** Argument for {@link #run(String)} **/
	public static final String SKIP_BITMAP_OPTIONS_LABEL = "skip-bitmap";
	/** Argument for {@link #run(String)} **/
	private static final String RESET_OPTIONS_LABEL = "reset";

	/* Columns in "Sholl Results" table */
	public static final int DIRECTORY = 1;
	public static final int UNIT = 2;
	public static final int THRESHOLD = 4;
	public static final int CENTER = 8;
	public static final int STARTING_RADIUS = 16;
	public static final int ENDING_RADIUS = 32;
	public static final int RADIUS_STEP = 64;
	public static final int SAMPLES_PER_RADIUS = 128;
	public static final int ENCLOSING_RADIUS = 256;
	public static final int INTERSECTING_RADII = 512;
	public static final int SUM_INTERS = 1024;
	public static final int MEAN_INTERS = 2048;
	public static final int MEDIAN_INTERS = 4096;
	public static final int SKEWNESS = 8192;
	public static final int KURTOSIS = 16384;
	public static final int CENTROID = 0x8000;
	public static final int P1090_REGRESSION = 0x10000;
	public static final int NO_TABLE = 0x40000;
	private final static String METRICS_KEY = "sholl.metrics";

	/* Boolean preferences */
	public static final int TRIM_BOUNDS = 1;
	public static final int INFER_PRIMARY = 2;
	public static final int CURVE_FITTING = 4;
	public static final int VERBOSE = 8;
	public static final int SHOLL_N = 16;
	public static final int SHOLL_NS = 32;
	public static final int SHOLL_SLOG = 64;
	public static final int SHOLL_LOG = 128;
	public static final int GUESS_LOG_METHOD = 256;
	public static final int SHOW_MASK = 512;
	public static final int OVERLAY_SHELLS = 1024;
	public static final int SAVE_FILES = 2048;
	public static final int HIDE_SAVED_FILES = 4096;
	public static final int SKIP_SINGLE_VOXELS = 8192;
	private final static String PREFS_KEY = "sholl.prefs";

	/* Non-Boolean preferences */
	protected HashMap<String, String> stringPrefs;
	protected static final String START_RADIUS_KEY = "A";
	protected static final String END_RADIUS_KEY = "B";
	protected static final String STEP_SIZE_KEY = "C";
	protected static final String NSAMPLES_KEY = "D";
	protected static final String INTEGRATION_KEY = "E";
	protected static final String ENCLOSING_RADIUS_KEY = "F";
	protected static final String PRIMARY_BRANCHES_KEY = "G";
	protected static final String POLYNOMIAL_INDEX_KEY = "H";
	protected static final String NORMALIZER_INDEX_KEY = "I";
	protected static final String SAVE_DIR_KEY = "J";
	protected static final String QUAD_CHOICE_KEY = "K";
	// TODO: static final String LIMIT_CSV_KEY = "L";

	private final static String HASHMAP_KEY = "sholl.map";
	private final String HASHMAP_DELIMITER = "|";
	private String hashMapString;

	/* Sholl mask */
	private static final int SAMPLED_MASK = 0;
	protected static final int FITTED_MASK = 1;
	private static final String[] MASK_TYPES = new String[] { "Sampled values", "Fitted values" };
	private final static String MASK_KEY = "sholl.mask";
	private final static int DEFAULT_MASK_BACKGROUND = 228;
	private final static int DEFAULT_MASK_TYPE = SAMPLED_MASK;

	/* Sholl plots */
	public static final int ALL_PLOTS = 0;
	public static final int ONLY_LINEAR_PLOT = 1;
	public static final int NO_PLOTS = 2;
	private static final String[] PLOT_OUTPUTS = new String[] { "All chosen methods", "Only linear profile",
			"No plots" };
	private final static String PLOT_OUTPUT_KEY = "sholl.plots";
	private final static int DEFAULT_PLOT_OUTPUT = ALL_PLOTS;

	private final static int UNSET_PREFS = -1;
	private int currentMetrics = UNSET_PREFS;
	private int currentBooleanPrefs = UNSET_PREFS;
	private int maskBackground = UNSET_PREFS;
	private int maskType = UNSET_PREFS;
	private int plotOutputType = UNSET_PREFS;
	private String commentString = null;

	private boolean skipBitmapOptions;
	protected boolean instanceAttatchedToPlugin;

	public ShollOptions(final boolean attachInstanceToPlugin) {
		this.instanceAttatchedToPlugin = attachInstanceToPlugin;
		currentMetrics = getMetrics();
		currentBooleanPrefs = getBooleanPrefs();
		commentString = getCommentString();
		plotOutputType = getPlotOutput();
		loadStringPreferences();
	}

	public ShollOptions() {
		this(false);
	}

	/**
	 * This method is called when the plugin is loaded. {@code arg} can be
	 * specified in {@code plugins.config}.
	 *
	 * @param arg
	 *            If {@link #RESET_OPTIONS_LABEL} options and preferences are
	 *            reset to defaults. If {@link #SKIP_BITMAP_OPTIONS_LABEL}
	 *            options specific to bitmap analysis are not displayed in GUI
	 *            when displaying prompt.
	 */
	@Override
	public void run(final String arg) {
		if (arg.equals(RESET_OPTIONS_LABEL)) {
			resetOptions();
			return;
		}
		setSkipBitmapOptions(arg.equals(SKIP_BITMAP_OPTIONS_LABEL));
		promptForOptions();
	}

	protected static int getDefaultMetrics() {
		return UNIT + THRESHOLD + CENTER + STARTING_RADIUS + ENDING_RADIUS + RADIUS_STEP + SAMPLES_PER_RADIUS
				+ ENCLOSING_RADIUS + INTERSECTING_RADII + SUM_INTERS + MEAN_INTERS + MEDIAN_INTERS + SKEWNESS + KURTOSIS
				+ CENTROID + P1090_REGRESSION;
	}

	protected int getMetrics() {
		if (currentMetrics == UNSET_PREFS) {
			// Somehow Prefs.getInt() fails. We'll cast from double instead
			currentMetrics = (int) Prefs.get(METRICS_KEY, getDefaultMetrics());
		}
		return currentMetrics;
	}

	private static int getSMetrics() {
		return new ShollOptions().getMetrics();
	}

	protected int getDefaultBooleanPrefs() {
		return INFER_PRIMARY + CURVE_FITTING + SHOLL_N + GUESS_LOG_METHOD;
	}

	protected int getBooleanPrefs() {
		if (currentBooleanPrefs == UNSET_PREFS) {
			// Somehow Prefs.getInt() fails. We'll cast from double instead
			currentBooleanPrefs = (int) Prefs.get(PREFS_KEY, getDefaultBooleanPrefs());
		}
		return currentBooleanPrefs;
	}

	/**
	 * Returns the background color of the Sholl mask image
	 *
	 * @return the gray value (8-bit scale) of the first entry of the LUT of the
	 *         Sholl mask's LUT image
	 * @see Sholl_Utils#matlabJetColorMap(int)
	 */
	protected int getMaskBackground() {
		if (maskBackground == UNSET_PREFS) {
			// Somehow Prefs.getInt() fails. We'll cast from double instead
			maskBackground = (int) Prefs.get(MASK_KEY, DEFAULT_MASK_BACKGROUND);
		}
		return maskBackground;
	}

	/**
	 * Sets the background color of the Sholl mask
	 *
	 * @param grayLevel
	 *            the gray value (8-bit scale) to be used as the first entry of
	 *            the LUT of the Sholl mask image
	 * @see Sholl_Utils#matlabJetColorMap(int)
	 */
	protected void setMaskBackground(final int grayLevel) {
		Prefs.set(MASK_KEY, grayLevel);
		maskBackground = grayLevel;
	}

	/**
	 * Returns the type of data to be used in Sholl mask.
	 *
	 * @return the type of mask. Either Options.SAMPLED_MASK or
	 *         Options.FITTED_MASK.
	 */
	protected int getMaskType() {
		if (maskType == UNSET_PREFS) {
			// Somehow Prefs.getInt() fails. We'll cast from double instead
			maskType = (int) Prefs.get(MASK_KEY + ".type", DEFAULT_MASK_TYPE);
		}
		return maskType;
	}

	/**
	 * Sets the flag for output plots.
	 *
	 * @param output
	 *            the output flag. Either Options.ALL_PLOTS,
	 *            Options.ONLY_LINEAR_PLOT or Options.NO_PLOTS
	 */
	public void setPlotOutput(final int output) {
		Prefs.set(PLOT_OUTPUT_KEY + ".out", output);
		plotOutputType = output;
	}

	/**
	 * Returns the flag for outputPlots.
	 *
	 * @return the output flag. Either Options.ALL_PLOTS,
	 *         Options.ONLY_LINEAR_PLOT or Options.NO_PLOTS
	 */
	protected int getPlotOutput() {
		if (plotOutputType == UNSET_PREFS) {
			plotOutputType = (int) Prefs.get(PLOT_OUTPUT_KEY + ".out", DEFAULT_PLOT_OUTPUT);
		}
		return plotOutputType;
	}

	protected void setBooleanPrefs(final int newValue) {
		Prefs.set(PREFS_KEY, newValue);
		currentBooleanPrefs = newValue;
	}

	/**
	 * Sets the type of data to be used in Sholl mask.
	 *
	 * @param type
	 *            the type of mask. Either Options.SAMPLED_MASK or
	 *            Options.FITTED_MASK.
	 */
	protected void setMaskType(final int type) {
		Prefs.set(MASK_KEY + ".type", type);
		maskType = type;
	}

	protected String getCommentString() {
		if (commentString == null)
			commentString = Prefs.getString(METRICS_KEY + ".comment", null);
		return commentString;
	}

	protected void setCommentString(String comment) {
		if (comment.trim().isEmpty())
			comment = null;
		Prefs.set(METRICS_KEY + ".comment", comment);
		commentString = comment;
	}

	private void resetOptions() {

		// Reset plugin parameters
		Prefs.set(PREFS_KEY, null);
		currentBooleanPrefs = UNSET_PREFS;
		Prefs.set(HASHMAP_KEY, null);
		hashMapString = "";

		// Reset Sholl metrics and output options
		Prefs.set(METRICS_KEY, null);
		Prefs.set(METRICS_KEY + ".comment", null);
		Prefs.set(MASK_KEY, null);
		Prefs.set(MASK_KEY + ".type", null);
		setPlotOutput(DEFAULT_PLOT_OUTPUT);
		currentMetrics = UNSET_PREFS;
		commentString = null;
		maskBackground = UNSET_PREFS;
		maskType = UNSET_PREFS;

		// Reset Analyzer prefs
		Analyzer.setPrecision(3);
		Analyzer.setMeasurement(Measurements.SCIENTIFIC_NOTATION, false);
		// Reset other global IJ prefs
		Prefs.setThreads(Runtime.getRuntime().availableProcessors());
		Prefs.set("options.ext", null);

	}

	private void promptForOptions() {

		currentMetrics = getMetrics();
		final int bitmapOptions = 2;
		int nOptions = 16;
		if (skipBitmapOptions)
			nOptions -= bitmapOptions;
		final String[] labels = new String[nOptions];
		final int[] items = new int[nOptions];
		final boolean[] states = new boolean[nOptions];
		int idx = 0;

		items[idx] = DIRECTORY;
		labels[idx] = "Image / Input directory";
		states[idx++] = (currentMetrics & DIRECTORY) != 0;

		items[idx] = UNIT;
		labels[idx] = "Spatial unit";
		states[idx++] = (currentMetrics & UNIT) != 0;

		if (!skipBitmapOptions) {
			items[idx] = THRESHOLD;
			labels[idx] = "Threshold levels";
			states[idx++] = (currentMetrics & THRESHOLD) != 0;
		}

		items[idx] = CENTER;
		labels[idx] = "Center of analysis";
		states[idx++] = (currentMetrics & CENTER) != 0;

		items[idx] = STARTING_RADIUS;
		labels[idx] = "Starting radius";
		states[idx++] = (currentMetrics & STARTING_RADIUS) != 0;

		items[idx] = RADIUS_STEP;
		labels[idx] = "Radius step size";
		states[idx++] = (currentMetrics & RADIUS_STEP) != 0;

		if (!skipBitmapOptions) {
			items[idx] = SAMPLES_PER_RADIUS;
			labels[idx] = "Samples per radius";
			states[idx++] = (currentMetrics & SAMPLES_PER_RADIUS) != 0;
		}

		items[idx] = ENCLOSING_RADIUS;
		labels[idx] = "Enclosing radius";
		states[idx++] = (currentMetrics & ENCLOSING_RADIUS) != 0;

		items[idx] = INTERSECTING_RADII;
		labels[idx] = "Intersecting radii";
		states[idx++] = (currentMetrics & INTERSECTING_RADII) != 0;

		items[idx] = SUM_INTERS;
		labels[idx] = "Sum of intersections";
		states[idx++] = (currentMetrics & SUM_INTERS) != 0;

		items[idx] = MEAN_INTERS;
		labels[idx] = "Mean n. of intersections";
		states[idx++] = (currentMetrics & MEAN_INTERS) != 0;

		items[idx] = MEDIAN_INTERS;
		labels[idx] = "Median n. of intersections";
		states[idx++] = (currentMetrics & MEDIAN_INTERS) != 0;

		items[idx] = SKEWNESS;
		labels[idx] = "Skewness";
		states[idx++] = (currentMetrics & SKEWNESS) != 0;

		items[idx] = KURTOSIS;
		labels[idx] = "Kurtosis";
		states[idx++] = (currentMetrics & KURTOSIS) != 0;

		items[idx] = CENTROID;
		labels[idx] = "Centroid";
		states[idx++] = (currentMetrics & CENTROID) != 0;

		items[idx] = P1090_REGRESSION;
		labels[idx] = "P10-P90 regression";
		states[idx++] = (currentMetrics & P1090_REGRESSION) != 0;

		// Output options
		final int outputOptions = 1;
		final String[] outputLabels = new String[outputOptions];
		final int[] outputItems = new int[outputOptions];
		final boolean[] outputStates = new boolean[outputOptions];
		idx = 0;

		outputItems[idx] = NO_TABLE;
		outputLabels[idx] = "Do_not_generate_detailed_table";
		outputStates[idx++] = (currentMetrics & NO_TABLE) != 0;

		final EnhancedGenericDialog gd = new EnhancedGenericDialog("Sholl Metrics and Options");
		final Font font = new Font("SansSerif", Font.BOLD, 12);

		// Metrics (columns of Sholl Results table)
		gd.setInsets(0, 0, 0);
		gd.addMessage("Sholl Results Table:", font);
		gd.setInsets(0, 0, 0);
		gd.addCheckboxGroup(nOptions / 2, 2, labels, states);
		gd.addStringField("Append comment:", getCommentString(), 20);

		// Output files
		gd.setInsets(15, 0, 0);
		gd.addMessage("Output Files:", font);
		gd.addCheckboxGroup(outputOptions, 1, outputLabels, outputStates);
		gd.addChoice("Plots to be generated:", PLOT_OUTPUTS, PLOT_OUTPUTS[getPlotOutput()]);

		// Intersections mask
		if (!skipBitmapOptions) {
			gd.setInsets(15, 0, 0);
			gd.addMessage("Intersections mask:", font);
			gd.addSlider("  Background (grayscale):", 0, 255, getMaskBackground());
			gd.addChoice("Preferred data:", MASK_TYPES, MASK_TYPES[getMaskType()]);
		}

		// Include IJ preferences for convenience
		gd.setInsets(15, 0, 2);
		gd.addMessage("Global preferences (shared by all ImageJ commands):", font);
		gd.addNumericField("Parallel threads (3D images):", Prefs.getThreads(), 0, 4, "");
		gd.addStringField("File extension for tables:", Prefs.defaultResultsExtension(), 4);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Decimal places (0-9):", Analyzer.getPrecision(), 0, 4, "");
		gd.setInsets(0, 70, 0);
		gd.addCheckbox("Scientific notation", (Analyzer.getMeasurements() & Measurements.SCIENTIFIC_NOTATION) != 0);
		gd.assignPopupToHelpButton(createOptionsMenu());
		gd.enableYesNoCancel("OK", "Revert to Defaults");
		gd.showDialog();

		if (gd.wasCanceled()) {
			return;
		} else if (gd.wasOKed()) {

			// Sholl Table prefs
			for (int i = 0; i < nOptions; i++) {
				if (gd.getNextBoolean())
					currentMetrics |= items[i];
				else
					currentMetrics &= ~items[i];
			}
			setCommentString(gd.getNextString());

			// Output prefs
			for (int i = 0; i < outputOptions; i++) {
				if (gd.getNextBoolean())
					currentMetrics |= outputItems[i];
				else
					currentMetrics &= ~outputItems[i];
			}
			Prefs.set(METRICS_KEY, currentMetrics);

			// Output prefs II: plot options
			setPlotOutput(gd.getNextChoiceIndex());

			if (!skipBitmapOptions) {
				setMaskBackground(Math.min(Math.max((int) gd.getNextNumber(), 0), 255));
				setMaskType(gd.getNextChoiceIndex());
			}

			// IJ prefs
			Prefs.setThreads((int) gd.getNextNumber());
			String extension = gd.getNextString();
			if (!extension.startsWith("."))
				extension = "." + extension;
			Prefs.set("options.ext", extension);
			Analyzer.setPrecision(Math.min(Math.max((int) gd.getNextNumber(), 0), 9));
			Analyzer.setMeasurement(Measurements.SCIENTIFIC_NOTATION, gd.getNextBoolean());

		} else {
			if (Recorder.record)
				Recorder.recordOption(RESET_OPTIONS_LABEL);
			resetOptions();
			if (instanceAttatchedToPlugin) {
				IJ.showMessage("Settings Successfully Reset",
						"   You should now close the main prompt and\nre-run the plugin for new settings to take effect.");
			}
		}
	}

	/** Creates optionsMenu */
	private JPopupMenu createOptionsMenu() {
		final JPopupMenu popup = new JPopupMenu();
		JMenuItem mi;
		mi = new JMenuItem("Plot Options...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				IJ.doCommand("Plots...");
			}
		});
		popup.add(mi);
		mi = new JMenuItem("Input/Output Options...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				IJ.doCommand("Input/Output...");
			}
		});
		popup.add(mi);
		popup.addSeparator();
		mi = EnhancedGenericDialog.menuItemTrigerringURL("Help on Sholl Metrics", ShollUtils.URL + "#Metrics");
		popup.add(mi);
		return popup;
	}

	/**
	 * Retrieves precision according to {@code Analyze>Set Measurements...}
	 *
	 * @return the number of decimal digits to be used in tables
	 */
	protected int getScientificNotationAwarePrecision() {
		final boolean sNotation = (Analyzer.getMeasurements() & Measurements.SCIENTIFIC_NOTATION) != 0;
		int precision = Analyzer.getPrecision();
		if (sNotation)
			precision = -precision;
		return precision;
	}

	/**
	 * Instructs {@link Sholl_Analysis} to exclude plots from output (only
	 * tables will be displayed).
	 *
	 * @param noPlots
	 *            If {@code true}, plugin will only output tables. If
	 *            {@code false}, both tables and plots will be produced (the
	 *            default)
	 */
	@Deprecated
	public void setNoPlots(final boolean noPlots) {
		currentMetrics = getSMetrics();
		if (noPlots)
			currentMetrics |= NO_PLOTS;
		else
			currentMetrics &= ~NO_PLOTS;
		Prefs.set(METRICS_KEY, currentMetrics);
	}

	public void setMetric(final int key, final boolean value) {
		if (value)
			currentMetrics |= key;
		else
			currentMetrics &= ~key;
	}

	public void setPromptChoice(final int key, final boolean value) {
		if (value)
			currentBooleanPrefs |= key;
		else
			currentBooleanPrefs &= ~key;
	}

	/**
	 * Instructs {@link Sholl_Analysis} to exclude detailed table from output
	 * (Summary table is still displayed).
	 *
	 * @param noTable
	 *            If {@code true}, plugin will not output the "detailed table"
	 *            containing all the retrieved profiles. Note that the Summary
	 *            "Sholl Results" table is always displayed.
	 *
	 * @see #setNoPlots(boolean)
	 */
	@Deprecated
	public void setNoTable(final boolean noTable) {
		currentMetrics = getSMetrics();
		if (noTable)
			currentMetrics |= NO_TABLE;
		else
			currentMetrics &= ~NO_TABLE;
		Prefs.set(METRICS_KEY, currentMetrics);
	}

	public void setSkipBitmapOptions(final boolean skipBitmapOptions) {
		this.skipBitmapOptions = skipBitmapOptions;
	}

	@Deprecated
	private void loadStringPreferences() {
		stringPrefs = new HashMap<>();
		hashMapString = Prefs.get(HASHMAP_KEY, "");
		stringPrefs.put(START_RADIUS_KEY, getValueFromHashMapString(START_RADIUS_KEY, Double.toString(10)));
		stringPrefs.put(END_RADIUS_KEY, getValueFromHashMapString(END_RADIUS_KEY, Double.toString(100)));
		stringPrefs.put(STEP_SIZE_KEY, getValueFromHashMapString(STEP_SIZE_KEY, Double.toString(0)));
		stringPrefs.put(NSAMPLES_KEY, getValueFromHashMapString(NSAMPLES_KEY, Integer.toString(1)));
		stringPrefs.put(INTEGRATION_KEY,
				getValueFromHashMapString(INTEGRATION_KEY, Integer.toString(Sholl_Analysis.BIN_AVERAGE)));
		stringPrefs.put(ENCLOSING_RADIUS_KEY, getValueFromHashMapString(ENCLOSING_RADIUS_KEY, Integer.toString(1)));
		stringPrefs.put(PRIMARY_BRANCHES_KEY,
				getValueFromHashMapString(PRIMARY_BRANCHES_KEY, Double.toString(Double.NaN)));
		stringPrefs.put(POLYNOMIAL_INDEX_KEY,
				getValueFromHashMapString(POLYNOMIAL_INDEX_KEY, Integer.toString(Sholl_Analysis.DEGREES.length - 1)));
		stringPrefs.put(NORMALIZER_INDEX_KEY, getValueFromHashMapString(NORMALIZER_INDEX_KEY, Integer.toString(0)));
		stringPrefs.put(SAVE_DIR_KEY, getValueFromHashMapString(SAVE_DIR_KEY, ""));
		stringPrefs.put(QUAD_CHOICE_KEY, getValueFromHashMapString(QUAD_CHOICE_KEY, Integer.toString(0)));

	}

	protected void setStringPreference(final String key, final double value) {
		stringPrefs.put(key, Double.toString(value));
	}

	protected void setStringPreference(final String key, final int value) {
		stringPrefs.put(key, Integer.toString(value));
	}

	protected void setStringPreference(final String key, final String value) {
		stringPrefs.put(key, value);
	}

	protected void saveStringPreferences() {
		if (stringPrefs.isEmpty()) {
			// remove entry from Prefs file;
			Prefs.set(HASHMAP_KEY, null);
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for (final Entry<String, String> entry : stringPrefs.entrySet()) {
			sb.append(entry.getKey()).append(entry.getValue()).append(HASHMAP_DELIMITER);
		}
		Prefs.set(HASHMAP_KEY, sb.toString());
	}

	private String getValueFromHashMapString(final String key, final String defaultValue) {
		final int start_index = hashMapString.indexOf(key) + key.length();
		final int end_index = hashMapString.indexOf(HASHMAP_DELIMITER, start_index);
		try {
			return hashMapString.substring(start_index, end_index);
		} catch (final IndexOutOfBoundsException ignored) {
			return defaultValue;
		}
	}

	protected int getIntFromHashMap(final String key, final int defaultValue) {
		final String value = stringPrefs.get(key);
		try {
			return Integer.valueOf(value);
		} catch (final NumberFormatException ignored) {
			return defaultValue;
		}
	}

	protected double getDoubleFromHashMap(final String key, final double defaultValue) {
		final String value = getValueFromHashMapString(key, Double.toString(defaultValue));
		try {
			return Double.valueOf(value);
		} catch (final NumberFormatException ignored) {
			return defaultValue;
		}
	}

	protected String getStringFromHashMap(final String key, final String defaultValue) {
		return getValueFromHashMapString(key, defaultValue);
	}

}
