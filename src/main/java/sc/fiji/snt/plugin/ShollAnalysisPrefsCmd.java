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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.options.OptionsPlugin;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.widget.Button;

import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.Logger;


/**
 * Implements the "Sholl Options" command
 * 
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Sholl Options", visible = false,
	initializer = "init")
public class ShollAnalysisPrefsCmd extends OptionsPlugin {

	@Parameter
	private PrefService pService;
	@Parameter
	private PlatformService platformService;

	/* DEFAULTS */
	public final static boolean DEF_SKIP_SINGLE_VOXELS = true;
	public final static boolean DEF_INCLUDE_ZERO_COUNTS = true;
	public final static boolean DEF_SKIP_SOMATIC_SEGMENTS = true;
	public final static int DEF_ENCLOSING_RADIUS_CUTOFF = 1;
	public final static int DEF_MIN_DEGREE = 2;
	public final static int DEF_MAX_DEGREE = 20;
	public final static double DEF_RSQUARED = 0.80;
	public final static boolean DEF_KS_TESTING = false;
	public final static boolean DEF_DEBUG_MODE = false;
	public final static boolean DEF_DETAILED_METRICS = false;
	public final static String DEF_ROI_SIZE= "Medium";


	//public final static boolean DEF_AUTO_CLOSE = false;

	/* Fields */
	private final static String PLACEHOLDER_CHOICE = "Choose...";
	private GuiUtils helper;
	private Logger logger;
	private boolean restartRequired;

	/* Prompt */
	protected static final String HEADER_HTML = "<html><body><div style='font-weight:bold;'>";
	protected static final String DESCRIPTION_HTML = "<html><body><div style='width:500px'>";

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = HEADER_HTML + "Sampling of 3D Images:")
	private String HEADER_SAMPLING;

	@Parameter(label = "Ignore isolated voxels",
			description = DESCRIPTION_HTML + "Mitigates over-estimation "
					+ "of intersections. Only applicabale to 3D image stacks")
	private boolean skipSingleVoxels;

	@Parameter(label="No. parallel threads",
			description = DESCRIPTION_HTML + "The max. no. of parallel threads to be used when parsing 3D stacks. "
					+ "This options also sets the no. of threads used by ImageJ and SNT for other multi-threaded "
					+ "tasks. Set it to 0 to use all of the available processors on your computer.")
	private int nThreads;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = HEADER_HTML + "<br>'Best Fit' Polynomial:")
	private String HEADER2;

	@Parameter(label = "Min. degree", min = "2", max = "60",
		callback = "flagRestart", description = "The lowest order to be considered")
	private int minDegree;

	@Parameter(label = "Max. degree", min = "2", max = "60",
		callback = "flagRestart", description = "The highest order to be considered")
	private int maxDegree;

//	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
//		label = HEADER_HTML + "Goodness of Fit Criteria:")
//	private String HEADER1C;

	@Parameter(label = "R-squared cutoff", min = "0.5", stepSize = "0.01", max = "1",
			description = DESCRIPTION_HTML + "The Coefficient of determination "
					+ "(R<sup>2</sup>) cutoff used to discard 'innapropriate fits'. "
					+ "Only fits associated with a R^2 greater than this value will "
					+ "be considered")
	private double rSquared;

	@Parameter(label = "K-S validation", required = false,
			description = DESCRIPTION_HTML + "Whether a fit should be discarded if " 
					+ "two-sample Kolmogorov-Smirnov testing rejects the null hypothesis "
					+ "that fitted and profiled values are samples drawn from the "
					+ " same probability distribution (p&lt;0.05)")
	private boolean ksTesting;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = HEADER_HTML + "<br>Metrics:")
	private String HEADER3;

	@Parameter(label = "Detailed list", callback = "flagRestart", 
			description = "Whether the 'Summary Table' should list "
					+ "detailed metrics or just the default set")
	private boolean detailedMetrics = DEF_DETAILED_METRICS;

	@Parameter(label = "Enclosing radius cuttoff", min = "1",
			description = "The number of intersections defining enclosing radius")
	private int enclosingRadiusCutoff = DEF_ENCLOSING_RADIUS_CUTOFF;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "<br>Outputs:")
	private String HEADER_ROI;

	@Parameter(label = "Point ROIs size", choices = { "Tiny","Small", "Medium", "Large", "Extra Large", "XXL", "XXXL"},
			description = DESCRIPTION_HTML + "The size of the point ROIs highlighting intersection counts")
	private String roiSize = DEF_ROI_SIZE;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "<br>Misc:")
	private String HEADER4;

	@Parameter(label = "Skip somatic segments",
			description = DESCRIPTION_HTML + "If primary paths start far-away from the soma, should "
					+ "segments between the soma and neurite be ignored?<br><br>This option is only "
					+ "considered when the arbor being analyzed is rooted on a single-node Path tagged as 'Soma'.")
	private boolean skipSomaticSegments = DEF_SKIP_SOMATIC_SEGMENTS;

	@Parameter(label = "Include zero counts",
			description = "whether entries associated with 0 intersections should be included in detailed tables, plots, etc.")
	private boolean includeZeroCounts = DEF_INCLUDE_ZERO_COUNTS;

	@Parameter(label = "Debug mode", callback = "flagRestart",
			description = "Whether computations should log "
					+ "detailed information to the Console")
	private boolean debugMode = DEF_DEBUG_MODE;

//	@Parameter(label = "Auto-close dialog", callback = "flagRestart")
//	private boolean autoClose = DEF_AUTO_CLOSE;

	@Parameter(label = "Reset Options & Preferences...", callback = "reset")
	private Button resetPrefs;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
		label = HEADER_HTML + "<br>Help &amp; Resources:")
	private String HEADER5;

	@Parameter(label = "Resource", required = false, persist = false,
		callback = "help", choices = { PLACEHOLDER_CHOICE, "About...",
			"Documentation", "Image.sc Forum: Topics on Sholl",
			"Image.sc Forum: Topics on SNT", "Source code" })
	private String helpChoice = " ";

	@Parameter(required = false, persist = false, visibility = ItemVisibility.INVISIBLE)
	private boolean ignoreBitmapOptions;

	@SuppressWarnings("unused")
	private void init() {
		helper = new GuiUtils();
		logger = new Logger(context(), "Sholl");
		logger.debug("Prefs successfully initialized");
		if (ignoreBitmapOptions) {
			resolveInput("HEADER_SAMPLING");
			resolveInput("skipSingleVoxels");
			resolveInput("nThreads");
			resolveInput("HEADER_ROI");
			resolveInput("roiSize");
		} else {
			resolveInput("skipSomaticSegments");
		}
		debugMode = SNTUtils.isDebugMode();
		nThreads = SNTPrefs.getThreads();
	}

	@SuppressWarnings("unused")
	private void help() {
		if (PLACEHOLDER_CHOICE.equals(helpChoice)) return; // do nothing
		final String choice = helpChoice.toLowerCase();
		helpChoice = PLACEHOLDER_CHOICE;
		if (choice.contains("about")) {
			GuiUtils.showAboutDialog();
			return;
		}
		String url = "";
		if (choice.contains("forum") && choice.contains("sholl"))
			url = "https://forum.image.sc/search?q=sholl";
		else if (choice.contains("snt"))
			url = "https://forum.image.sc/search?q=snt";
		else if (choice.contains("code"))
			url = "https://github.com/morphonets/SNT";
		else url = ShollUtils.URL;
		try {
			platformService.open(new URI(url).toURL());
		}
		catch (final IOException | URISyntaxException e) {
			logger.debug(e);
			helper.error("<HTML><div WIDTH=400>Web page could not be open. " +
				"Please visit " + url + " using your web browser.", null);
		}
	}

	@SuppressWarnings("unused")
	private void flagRestart() {
		restartRequired = true;
		if (minDegree > maxDegree) maxDegree = minDegree;
		if (maxDegree < minDegree) minDegree = maxDegree;
	}

	@Override
	public void run() {
		super.run();
		SNTPrefs.setThreads(Math.max(0, nThreads));
		if (restartRequired)
			helper.centeredMsg("You may need to restart the Sholl Analysis plugin for changes to take effect.",
					"New Preferences Set");
	}

	@Override
	public void reset() {

		final Result result = helper.yesNoPrompt(
			"Reset all preferences to defaults?", "Confirm Reset");
		if (result == Result.NO_OPTION || result == Result.CANCEL_OPTION) return;

		// Reset preferences
		super.reset();
		pService.clear(ShollAnalysisImgCmd.class);
		pService.clear(ShollAnalysisTreeCmd.class);
		pService.clear(ShollAnalysisBulkTreeCmd.class);
		pService.clear(ChooseDataset.class);

		// Reset inputs in prompt
		skipSingleVoxels = DEF_SKIP_SINGLE_VOXELS;
		skipSomaticSegments = DEF_SKIP_SOMATIC_SEGMENTS;
		includeZeroCounts = DEF_INCLUDE_ZERO_COUNTS;
		enclosingRadiusCutoff = DEF_ENCLOSING_RADIUS_CUTOFF;
		minDegree = DEF_MIN_DEGREE;
		maxDegree = DEF_MAX_DEGREE;
		rSquared = DEF_RSQUARED;
		ksTesting = DEF_KS_TESTING;
		debugMode = DEF_DEBUG_MODE;
		detailedMetrics = DEF_DETAILED_METRICS;
		roiSize = DEF_ROI_SIZE;
		SNTPrefs.setThreads(0);
//		autoClose = DEF_AUTO_CLOSE;

		helper.centeredMsg("Preferences were successfully reset.", SNTUtils.getReadableVersion());
		restartRequired = true;

	}

}
