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
import sc.fiji.snt.gui.GuiUtils;

/**
 * GUI command for {@link PathFitter}
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Refinement of Paths")
public class PathFitterCmd extends ContextCommand {

	@Parameter
	private PrefService prefService;

	public static final String FITCHOICE_KEY = "choice";
	public static final String MAXRADIUS_KEY = "maxrad";
	public static final String FITINPLACE_KEY = "inplace";

	private static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final String CHOICE_RADII =
		"1) Assign radii of fitted cross-sections to nodes";
	private static final String CHOICE_MIDPOINT =
		"2) Snap node coordinates to cross-section centroids";
	private static final String CHOICE_BOTH =
		"1) & 2): Assign fitted radii and snap node coordinates";
	private static String HEADER;

	static {
		GuiUtils.setSystemLookAndFeel();
		final javax.swing.JLabel label = new javax.swing.JLabel();
		final int width = label.getFontMetrics(label.getFont()).stringWidth(
			"Type of Fit" + CHOICE_BOTH);
		HEADER = "<HTML><body><div style='width:" + width + ";'>";
	}

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = HEADER +
		"<b>Type of refinement:</b> SNT can use the fluorescent signal around traced paths " //
		+
		"to optimize curvatures and estimate the thickness of traced structures. The optimization " //
		+
		"algorithm uses pixel intensities to fit circular cross-sections around each node. " //
		+
		"Once computed, fitted cross-sections can be use to: 1) Infer the radius of nodes, " //
		+
		"and/or 2) refine node positioning, by snapping their coordinates to the cross-section " //
		+ "centroid.<br><br>" //
		+
		"Please specify the type of optimization to be performed when refining paths:";

	@Parameter(required = true, label = EMPTY_LABEL, choices = { CHOICE_RADII,
		CHOICE_MIDPOINT, CHOICE_BOTH })
	private String fitChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String spacer = EMPTY_LABEL;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg2 = HEADER +
		"<b>Max. radius:</b> This setting defines (in pixels) the largest radius " //
		+ "allowed in the fit. It constrains the optimization to minimize fitting " //
		+ "artifacts caused from neighboring structures (Tip: You can estimate the " //
		+ "thickness of neurites by running <i>Estimate Radii</i> from the gear menu " //
		+ "of the Auto-tracing widget):";
	@Parameter(required = false, label = EMPTY_LABEL)
	private int maxRadius = PathFitter.DEFAULT_MAX_RADIUS;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String spacer2 = EMPTY_LABEL;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg3 = HEADER +
		"<b>Replace nodes:</b> Defines wether fitted paths should immediately "
		+ "replace (override) input path(s):";

	@Parameter(required = false, label = EMPTY_LABEL, choices = {
			"Keep original path(s)", "Replace existing nodes (undoable operation)" })
	private String fitInPlaceChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String spacer3 = EMPTY_LABEL;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			description="Press 'Reset' or set it to 0 to use the available processors on your computer")
	private final String msg4 = HEADER +
		"<b>Multithreading:</b> Number of parallel threads:";

	@Parameter(required = false, label = EMPTY_LABEL, persist = false, min = "0", stepSize = "1",
			description="Press 'Reset' or set it to 0 to use the available processors on your computer")
	private int nThreads = SNTPrefs.getThreads();

	@Parameter(required = false, label = "Reset Defaults", callback = "reset")
	private Button reset;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		switch (fitChoice) {
			case CHOICE_MIDPOINT:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY,
					PathFitter.MIDPOINTS);
				break;
			case CHOICE_RADII:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY, PathFitter.RADII);
				break;
			default:
				prefService.put(PathFitterCmd.class, FITCHOICE_KEY,
					PathFitter.RADII_AND_MIDPOINTS);
				break;
		}
		prefService.put(PathFitterCmd.class, MAXRADIUS_KEY, maxRadius);
		prefService.put(PathFitterCmd.class, FITINPLACE_KEY, fitInPlaceChoice.toLowerCase().contains("replace"));
		nThreads = getAdjustedThreadNumber(nThreads);
		SNTPrefs.setThreads(nThreads);
	}

	private int getAdjustedThreadNumber(final int threads) {
		int processors = Runtime.getRuntime().availableProcessors();
		if (threads < 1 || threads > processors)
			return processors;
		return threads;
	}

	@SuppressWarnings("unused")
	private void reset() {
		fitChoice = PathFitterCmd.CHOICE_RADII;
		maxRadius = PathFitter.DEFAULT_MAX_RADIUS;
		fitInPlaceChoice = null;
		nThreads = getAdjustedThreadNumber(0);
		prefService.clear(PathFitterCmd.class); // useful if user dismisses dialog after pressing "Reset"
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(PathFitterCmd.class, true);
	}

}
