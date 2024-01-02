/*-
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

package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;

import sc.fiji.snt.PathFitter;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;

/**
 * GUI command for {@link PathFitter}
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", visible = false, label = "Refinement of Paths")
public class PathFitterCmd extends ContextCommand {

	@Parameter
	private PrefService prefService;

	public static final String FITCHOICE_KEY = "choice";
	public static final String FALLBACKCHOICE_KEY = "fallback";
	public static final String MAXRADIUS_KEY = "maxrad";
	public static final String MINANGLE_KEY = "minang";
	public static final String FITINPLACE_KEY = "inplace";
	public static final String SECLAYER_KEY = "secondary";

	private static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final String CHOICE_RADII =
		"1) Assign radii of fitted cross-sections to nodes";
	private static final String CHOICE_MIDPOINT =
		"2) Snap node coordinates to cross-section centroids";
	private static final String CHOICE_BOTH =
		"1) & 2): Assign fitted radii and snap node coordinates";
	private static final String CHOICE_FALLBACK_MODE = "Best guess (mode of possible fits)";
	private static final String CHOICE_FALLBACK_MINSEP ="Smallest voxel separation";
	private static final String CHOICE_FALLBACK_NAN = "NaN (Not a number)";

	private static String HEADER = "<HTML><body><div style='width:"
			+ GuiUtils.renderedWidth("Type of refinement: SNT can use the fluorescent signal around traced paths to opt")
			+ ";'>";

	private String unit;
	private double smallestSep;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = HEADER +
		"<b>Type of refinement:</b> SNT can use the fluorescent signal around traced paths " //
		+ "to optimize their curvatures and estimate their thickness. The optimization " //
		+ "algorithm uses pixel intensities to fit circular cross-sections around each node. " //
		+ "Once computed, fitted cross-sections can be use to: 1) Infer the radius of nodes, " //
		+ "and/or 2) refine node positioning, by snapping their coordinates to the cross-section " //
		+ "centroid. Which optimization shuld be performed when refining paths?";
	@Parameter(required = true, label = EMPTY_LABEL, choices = { CHOICE_RADII,
		CHOICE_MIDPOINT, CHOICE_BOTH })
	private String fitChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg2 = HEADER +
		"<b>Max. radius:</b> This setting defines (in physical units) the largest " //
		+ "radius allowed in the fit. It constrains the optimization to minimize " //
		+ "fitting artifacts caused from neighboring structures. (Tip: The " //
		+ "<i>Secondary Layer Creation Wizard</i> can estimate neurite thickness)";
	@Parameter(required = false, initializer = "init", label = EMPTY_LABEL, callback= "updateMaxRadiusLegend",
			min = "0", style="format:#.000", description="<HTML>An exaggerated " //
		+ "radius may originate jagged paths.<br>When in doubt, start with a smaller radius " //
		+ "and repeat fitting in smaller increments")
	private double maxRadius;

	@Parameter(required = false, persist=false, visibility = ItemVisibility.MESSAGE, initializer="updateMaxRadiusLegend")
	private String maxRadiusLegend;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg3 = HEADER + //
			"<b>Radius fallback:</b> If fitting fails at certain node location(s), which radius " //
			+ "should be adopted as a fallback? (Tip: Fallback values can be optimized using "
			+ "the <i>Correct Radii...</i> command)";
	@Parameter(required = true, label = EMPTY_LABEL, choices = { CHOICE_FALLBACK_MODE, CHOICE_FALLBACK_MINSEP,
			CHOICE_FALLBACK_NAN })
	private String fallbackChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg4 = HEADER +
		"<b>Min. angle:</b> This is an advanced, micro-optimization setting defining (in degrees) " //
		+ "the smallest angle between the fitted node and parent tangent vectors. It minimizes " //
		+ "abrupt jaggering between neighboring nodes.";
	@Parameter(required = false, label = EMPTY_LABEL, style="format:#.00",
			description="<HTML>A small value " //
		+ "may cause jagged paths. Large values allow for more<br>"
		+ "tolerant fits. When in doubt, use a value between 60 and 90&deg;.")
	private double minAngle;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg5 = HEADER +
		"<b>Target image:</b> Which image should be used for fitting?";
	@Parameter(required = false, label = EMPTY_LABEL, choices= {"Main image", "Secondary (if available)"})
	private String impChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg6 = HEADER +
		"<b>Replace nodes:</b> Defines whether fitted coordinates/radii should "
		+ "replace (override) those of input path(s):";

	@Parameter(required = false, label = EMPTY_LABEL, choices = {
			"Keep original path(s)", "Replace existing nodes (undoable operation)" })
	private String fitInPlaceChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			description="Press 'Reset' or set it to 0 to use the available processors on your computer")
	private final String msg7 = HEADER +
		"<b>Multithreading:</b> Number of parallel threads to compute fits:";

	@Parameter(required = false, label = EMPTY_LABEL, persist = false, min = "0", stepSize = "1")
	private int nThreads;

	@Parameter(required = false, label = "Reset Defaults", callback = "reset")
	private Button reset;

	@SuppressWarnings("unused")
	private void init() {
		if (SNTUtils.getPluginInstance().accessToValidImageData()) {
			unit = SNTUtils.getPluginInstance().getSpacingUnits();
			smallestSep = SNTUtils.getPluginInstance().getMinimumSeparation();
		} else {
			unit = SNTUtils.getSanitizedUnit(null);
			smallestSep = 1;
		}
		if (maxRadius <= 0d || Double.isNaN(minAngle))
			maxRadius = PathFitter.DEFAULT_MAX_RADIUS * smallestSep;
		if (minAngle < 0d || Double.isNaN(minAngle))
			minAngle = Math.toDegrees(PathFitter.DEFAULT_MIN_ANGLE);
		nThreads = getAdjustedThreadNumber(SNTPrefs.getThreads());
		updateMaxRadiusLegend();
	}

	private void updateMaxRadiusLegend() {
		maxRadiusLegend = String.format("For current image: %.3f%s ≈ %dpixel(s)", maxRadius, unit,
				Math.round(maxRadius / smallestSep));
	}

	@SuppressWarnings("unused")
	private void reset() {
		fitChoice = PathFitterCmd.CHOICE_RADII;
		maxRadius = PathFitter.DEFAULT_MAX_RADIUS * smallestSep;
		minAngle = Math.toDegrees(PathFitter.DEFAULT_MIN_ANGLE);
		fallbackChoice = PathFitterCmd.CHOICE_FALLBACK_MODE;
		impChoice = "Main image";
		fitInPlaceChoice = null;
		nThreads = getAdjustedThreadNumber(0);
		updateMaxRadiusLegend();
		prefService.clear(PathFitterCmd.class); // useful if user dismisses dialog after pressing "Reset"
	}

	private int getAdjustedThreadNumber(final int threads) {
		int processors = Runtime.getRuntime().availableProcessors();
		if (threads < 1 || threads > processors)
			return processors;
		return threads;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		switch (fitChoice) {
			case CHOICE_MIDPOINT:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY, PathFitter.MIDPOINTS);
				break;
			case CHOICE_RADII:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY, PathFitter.RADII);
				break;
			default:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY, PathFitter.RADII_AND_MIDPOINTS);
				break;
		}
		prefService.put(PathFitterCmd.class, MAXRADIUS_KEY, Math.round(maxRadius / smallestSep));
		prefService.put(PathFitterCmd.class, MINANGLE_KEY, Math.toRadians(minAngle));
		switch (fallbackChoice) {
		case CHOICE_FALLBACK_NAN:
			prefService.put(PathFitterCmd.class, FALLBACKCHOICE_KEY, PathFitter.FALLBACK_NAN);
			break;
		case CHOICE_FALLBACK_MINSEP:
			prefService.put(PathFitterCmd.class, FALLBACKCHOICE_KEY, PathFitter.FALLBACK_MIN_SEP);
			break;
		default:
			prefService.put(PathFitterCmd.class, FALLBACKCHOICE_KEY, PathFitter.FALLBACK_MODE);
			break;
		}
		prefService.put(PathFitterCmd.class, SECLAYER_KEY, impChoice.toLowerCase().contains("secondary"));
		prefService.put(PathFitterCmd.class, FITINPLACE_KEY, fitInPlaceChoice.toLowerCase().contains("replace"));
		nThreads = getAdjustedThreadNumber(nThreads);
		SNTPrefs.setThreads(nThreads);
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(PathFitterCmd.class, true);
	}

}
