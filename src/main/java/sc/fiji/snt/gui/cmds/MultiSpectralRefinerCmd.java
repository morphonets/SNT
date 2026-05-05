/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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

package sc.fiji.snt.gui.cmds;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.MultiSpectralRefiner;
import sc.fiji.snt.gui.GuiUtils;

/**
 * GUI command for {@link MultiSpectralRefiner}. Configures cost function
 * weights, thresholds, and parallelization settings for post-hoc refinement
 * of traced paths in multispectral (Brainbow) images.
 *
 * @author Tiago Ferreira
 * @see MultiSpectralRefiner
 * @see PathFitterCmd
 */
@Plugin(type = Command.class, initializer = "init", visible = false, label = "Multispectral Refinement")
public class MultiSpectralRefinerCmd extends ContextCommand {

	public static final String INTENSITY_WEIGHT_KEY = "intensityWeight";
	public static final String COLOR_WEIGHT_KEY = "colorWeight";
	public static final String RADIUS_WEIGHT_KEY = "radiusWeight";
	public static final String COS_SIMILARITY_KEY = "cosSimilarity";
	public static final String BACKGROUND_THRESHOLD_KEY = "backgroundThreshold";
	public static final String MIN_INTENSITY_KEY = "minIntensity";
	public static final String MAX_INTENSITY_KEY = "maxIntensity";
	public static final String MIN_PERCENT_C_KEY = "minPercentC";
	public static final String MAX_PERCENT_C_KEY = "maxPercentC";
	public static final String MAX_RADIUS_KEY = "maxRadius";
	public static final String AUTO_INTENSITY_KEY = "autoIntensity";

	@Parameter
	private PrefService prefService;

	private static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final String HEADER = "<HTML><body><div style='width:"
			+ GuiUtils.renderedWidth("Relative importance of matching criteria: Controls how much each criterion influences")
			+ ";'>";

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg0 = HEADER
			+ "This command refines traced paths in multi-color labeled images (e.g., Brainbow). It corrects node "
			+ "positions where uneven overlap between fluorescent reporters causes traces to deviate from the "
			+ "neurite's true centerline.";

	// Relative importance of matching criteria
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = HEADER
			+ "<b>Relative importance of matching criteria:</b> Controls how much each criterion influences node "
			+ "placement. The algorithm repositions nodes to minimize a combined penalty "
			+ "based on brightness, color fidelity, and cross-section compactness. "
			+ "Increase a weight to make that criterion dominate.";

	@Parameter(required = false, label = "Brightness importance",
			description = "<HTML>How strongly voxels outside the expected brightness range are penalized.<br>"
					+ "Higher values keep nodes in well-lit regions of the neurite.",
			style = "format:#.00", min = "0", stepSize = "0.05")
	private double intensityWeight;

	@Parameter(required = false, label = "Color-match importance",
			description = "<HTML>How strongly voxels whose hue differs from the reference color are penalized.<br>"
					+ "Higher values keep nodes on the labeled neurite's spectral channel.",
			style = "format:#.00", min = "0", stepSize = "0.05")
	private double colorWeight;

	@Parameter(required = false, label = "Radius importance",
			description = "<HTML>How strongly large radii are penalized, favoring tight cross-sections.<br>"
					+ "Higher values prefer thinner fits; lower values allow wider ones.",
			style = "format:#.00", min = "0", stepSize = "0.25")
	private double radiusWeight;

	// Detection criteria
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg2 = HEADER
			+ "<b>Detection criteria:</b> Define when a voxel is considered part of the neurite "
			+ "versus background.";

	@Parameter(required = false, label = "Color-match stringency",
			description = "<HTML>How closely a voxel's color must match the neurite's reference color (0–1).<br>"
					+ "Higher values are stricter, rejecting weakly colored voxels.",
			style = "format:#.00", min = "0", max = "1", stepSize = "0.05")
	private double cosSimilarityThreshold;

	@Parameter(required = false, label = "Background sensitivity",
			description = "<HTML>Fraction of dim voxels (in a local sphere) that flags a node as background (0–1).<br>"
					+ "Lower values are more aggressive at discarding nodes in dim regions.",
			style = "format:#.00", min = "0", max = "1", stepSize = "0.05")
	private double backgroundThreshold;

	@Parameter(required = false, label = "Max. radius (pixels)",
			description = "<HTML>Largest cross-section radius tested during optimization.<br>"
					+ "(Tip: Should be 2–3× the expected neurite thickness)",
			min = "1", stepSize = "1")
	private int maxRadius;

	private static final String INTENSITY_RANGE_AUTO = "Auto-calibrate from image (min./max. below ignored)";
	private static final String INTENSITY_RANGE_MANUAL = "Specify manually (min./max. below used as-is)";

	// Signal intensity range
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg3 = HEADER
			+ "<b>Signal intensity range:</b> Expected brightness range of the neurite signal "
			+ "(summed across all channels).";

	@Parameter(required = false, label = EMPTY_LABEL,
			choices = { INTENSITY_RANGE_AUTO, INTENSITY_RANGE_MANUAL },
			callback = "intensityRangeChanged")
	private String intensityRangeChoice;

	@Parameter(required = false, label = "Min. signal intensity",
			description = "<HTML>Below this combined-channel brightness, a wider color tolerance is applied.<br>"
					+ "Decrease to accommodate dimmer neurites. Ignored when auto-calibrating.",
			style = "format:#.0", min = "0")
	private double minIntensityThreshold;

	@Parameter(required = false, label = "Max. signal intensity",
			description = "<HTML>Above this combined-channel brightness, a stricter color tolerance is applied.<br>"
					+ "Increase if your neurites saturate. Ignored when auto-calibrating.",
			style = "format:#.0", min = "0")
	private double maxIntensityThreshold;

	// Brightness tolerance
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg4 = HEADER
			+ "<b>Brightness tolerance:</b> How forgiving the brightness assessment is when deciding "
			+ "if a voxel belongs to the neurite. Dim neurites get a wider tolerance; bright "
			+ "neurites get a stricter one. Use defaults in case of doubt.";

	@Parameter(required = false, label = "Tolerance (bright signal)",
			description = "<HTML>Strictest tolerance, applied to the brightest neurites (0–1).<br>"
					+ "Decrease to reject more marginal voxels around bright structures.",
			style = "format:#.00", min = "0", max = "1", stepSize = "0.01")
	private double minPercentC;

	@Parameter(required = false, label = "Tolerance (dim signal)",
			description = "<HTML>Most permissive tolerance, applied to the dimmest neurites (0–1).<br>"
					+ "Increase to retain more voxels around faint structures.",
			style = "format:#.00", min = "0", max = "1", stepSize = "0.01")
	private double maxPercentC;

	// Threading
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			description = "Press 'Reset' or set it to 0 to use the available processors on your computer")
	private final String msg5 = HEADER
			+ "<b>Multithreading:</b> Number of parallel threads:";

	@Parameter(required = false, label = EMPTY_LABEL, persist = false, min = "0", stepSize = "1")
	private int nThreads;

	@Parameter(required = false, label = "Reset Defaults", callback = "reset")
	private Button reset;

	@SuppressWarnings("unused")
	private void init() {
		final MultiSpectralRefiner.Parameters defaults = MultiSpectralRefiner.Parameters.defaults();
		if (Double.isNaN(intensityWeight) || intensityWeight < 0)
			intensityWeight = defaults.intensityWeight();
		if (Double.isNaN(colorWeight) || colorWeight < 0)
			colorWeight = defaults.colorWeight();
		if (Double.isNaN(radiusWeight) || radiusWeight < 0)
			radiusWeight = defaults.radiusWeight();
		if (Double.isNaN(cosSimilarityThreshold))
			cosSimilarityThreshold = defaults.cosSimilarityThreshold();
		if (Double.isNaN(backgroundThreshold))
			backgroundThreshold = defaults.backgroundThreshold();
		if (maxRadius <= 0)
			maxRadius = defaults.maxRadius();
		if (Double.isNaN(minPercentC))
			minPercentC = defaults.minPercentC();
		if (Double.isNaN(maxPercentC))
			maxPercentC = defaults.maxPercentC();

		// Initialize intensity range from stored prefs or auto-calibrate
		final boolean autoIntensity = prefService.getBoolean(MultiSpectralRefinerCmd.class, AUTO_INTENSITY_KEY, true);
		intensityRangeChoice = autoIntensity ? INTENSITY_RANGE_AUTO : INTENSITY_RANGE_MANUAL;
		if (autoIntensity) {
			autoCalibrate();
		} else {
			if (Double.isNaN(minIntensityThreshold) || minIntensityThreshold <= 0)
				minIntensityThreshold = 10000;
			if (Double.isNaN(maxIntensityThreshold) || maxIntensityThreshold <= 0)
				maxIntensityThreshold = 85000;
		}
		nThreads = getAdjustedThreadNumber(SNTPrefs.getThreads());
	}

	private boolean isAutoIntensity() {
		return INTENSITY_RANGE_AUTO.equals(intensityRangeChoice);
	}

	private void autoCalibrate() {
		if (SNTUtils.getInstance() != null && SNTUtils.getInstance().accessToValidImageData()) {
			final ij.ImagePlus imp = SNTUtils.getInstance().getImagePlus();
			final double maxChannelSum = (Math.pow(2, imp.getBitDepth()) - 1) * imp.getNChannels();
			minIntensityThreshold = 0.05 * maxChannelSum;
			maxIntensityThreshold = 0.43 * maxChannelSum;
		} else {
			// Fallback to 16-bit 3-channel defaults
			minIntensityThreshold = 10000;
			maxIntensityThreshold = 85000;
		}
	}

	@SuppressWarnings("unused")
	private void intensityRangeChanged() {
		if (isAutoIntensity()) {
			autoCalibrate();
		}
	}

	@SuppressWarnings("unused")
	private void reset() {
		final MultiSpectralRefiner.Parameters defaults = MultiSpectralRefiner.Parameters.defaults();
		intensityWeight = defaults.intensityWeight();
		colorWeight = defaults.colorWeight();
		radiusWeight = defaults.radiusWeight();
		cosSimilarityThreshold = defaults.cosSimilarityThreshold();
		backgroundThreshold = defaults.backgroundThreshold();
		maxRadius = defaults.maxRadius();
		minPercentC = defaults.minPercentC();
		maxPercentC = defaults.maxPercentC();
		intensityRangeChoice = INTENSITY_RANGE_AUTO;
		autoCalibrate();
		nThreads = getAdjustedThreadNumber(0);
		prefService.clear(MultiSpectralRefinerCmd.class);
	}

	private int getAdjustedThreadNumber(final int threads) {
		final int processors = Runtime.getRuntime().availableProcessors();
		if (threads < 1 || threads > processors)
			return processors;
		return threads;
	}

	@Override
	public void run() {
		prefService.put(MultiSpectralRefinerCmd.class, INTENSITY_WEIGHT_KEY, intensityWeight);
		prefService.put(MultiSpectralRefinerCmd.class, COLOR_WEIGHT_KEY, colorWeight);
		prefService.put(MultiSpectralRefinerCmd.class, RADIUS_WEIGHT_KEY, radiusWeight);
		prefService.put(MultiSpectralRefinerCmd.class, COS_SIMILARITY_KEY, cosSimilarityThreshold);
		prefService.put(MultiSpectralRefinerCmd.class, BACKGROUND_THRESHOLD_KEY, backgroundThreshold);
		prefService.put(MultiSpectralRefinerCmd.class, MAX_RADIUS_KEY, maxRadius);
		prefService.put(MultiSpectralRefinerCmd.class, MIN_PERCENT_C_KEY, minPercentC);
		prefService.put(MultiSpectralRefinerCmd.class, MAX_PERCENT_C_KEY, maxPercentC);
		final boolean auto = isAutoIntensity();
		prefService.put(MultiSpectralRefinerCmd.class, AUTO_INTENSITY_KEY, auto);
		if (!auto) {
			prefService.put(MultiSpectralRefinerCmd.class, MIN_INTENSITY_KEY, minIntensityThreshold);
			prefService.put(MultiSpectralRefinerCmd.class, MAX_INTENSITY_KEY, maxIntensityThreshold);
		}
		nThreads = getAdjustedThreadNumber(nThreads);
		SNTPrefs.setThreads(nThreads);
	}

	/**
	 * Reads stored preferences and returns a {@link MultiSpectralRefiner.Parameters}
	 * record, or defaults if no preferences have been stored.
	 *
	 * @return the parameter set from stored preferences
	 */
	public static MultiSpectralRefiner.Parameters readParameters() {
		final PrefService prefService = SNTUtils.getContext().getService(PrefService.class);
		final MultiSpectralRefiner.Parameters defaults = MultiSpectralRefiner.Parameters.defaults();
		return new MultiSpectralRefiner.Parameters(
				prefService.getDouble(MultiSpectralRefinerCmd.class, INTENSITY_WEIGHT_KEY, defaults.intensityWeight()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, COLOR_WEIGHT_KEY, defaults.colorWeight()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, RADIUS_WEIGHT_KEY, defaults.radiusWeight()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, COS_SIMILARITY_KEY, defaults.cosSimilarityThreshold()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, BACKGROUND_THRESHOLD_KEY, defaults.backgroundThreshold()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, MIN_INTENSITY_KEY, defaults.minIntensityThreshold()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, MAX_INTENSITY_KEY, defaults.maxIntensityThreshold()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, MIN_PERCENT_C_KEY, defaults.minPercentC()),
				prefService.getDouble(MultiSpectralRefinerCmd.class, MAX_PERCENT_C_KEY, defaults.maxPercentC()),
				prefService.getInt(MultiSpectralRefinerCmd.class, MAX_RADIUS_KEY, defaults.maxRadius()),
				defaults.maxIterations(),
				defaults.convergenceThreshold()
		);
	}

}
