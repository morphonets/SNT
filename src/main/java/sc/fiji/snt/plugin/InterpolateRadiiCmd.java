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

package sc.fiji.snt.plugin;

import java.awt.Color;
import java.io.IOException;
import java.util.*;
import java.util.function.DoublePredicate;
import java.util.stream.IntStream;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Command for finding all the nodes in a collection of paths with invalid
 * radius, and assign them interpolated values from their neighbors and
 * {@link Viewer3D}
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Correct Invalid Radii", initializer = "init")
public class InterpolateRadiiCmd extends CommonDynamicCmd {

	private static String HEADER;
	private static final String desc = "NB: NaN or negative values are always queued for interpolation";

	static {
		HEADER = "<HTML><body><div style='width:"
				+ GuiUtils.renderedWidth("Some nodes in a path may not have valid radius (e.g., if refining ")
				+ ";'>";
	}

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String MSG_1 = HEADER //
			+ "<b>Scope</b>: Some nodes may not have valid radius (e.g., if refining failed at " //
			+ "their location because their cross-section fit deviated too much from a circular " //
			+ "pattern). This command collects such nodes from selected paths, and assigns " //
			+ "them new radii using linear interpolation based on flanking nodes with valid " //
			+ "radii. It can apply the interpolation immediately, or simply preview it."; //
	@Parameter(required = true, label = "Interpolation", //
			choices = { "Preview only", "Apply", "Preview and Apply" })
	private String applyChoice;

	@Parameter(required = false, persist = false, description = desc, visibility = ItemVisibility.MESSAGE)
	private String MSG_2 = HEADER //
			+ "<b>Definition of invalid radii</b>: Here you can set the condition " +
			"defining invalid radii. Threshold values assume spatially- calibrated units:";
	@Parameter(required = false, label = "Criterion", description = desc, choices = {" ", "<", "≤", "=", "≠", ">", "≥"}, callback="updateLabel")
	private String thresholdCriterion;

	@Parameter(required = false, label = "Threshold", description = desc, callback="updateLabel")
	private double thresholdValue;

	@Parameter(required = false, persist = false, description = desc, visibility = ItemVisibility.MESSAGE)
	private String thresholdLabel;
	
	@Parameter(required = false, persist = false, label = "    Debug mode", callback = "debugChoiceChanged", //
			description = "Interpolation details are displayed in Console when Debug Mode is on")
	private boolean debugChoice;

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String MSG_3 = HEADER //
			+ "NB: Only paths with previously assigned radii and at least 2 nodes can be parsed. For " //
			+ "smaller paths or ad hoc node editing use <i>Edit Path</i> commands instead.";

	@Parameter(required = true)
	private Collection<Path> paths;

	private Map<Integer, Double> replacements;
	private ShollPlot plot;


	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		updateLabel();
		debugChoice = SNTUtils.isDebugMode();
	}

	@SuppressWarnings("unused")
	private void debugChoiceChanged() {
		ui.setEnableDebugMode(debugChoice);
	}

	private void updateLabel() {
		if (thresholdCriterion == null || thresholdCriterion.isBlank()) {
			thresholdLabel = "";
		} else if ("<".equals(thresholdCriterion)) {
			// somehow formatter does not handle %s when replacement is "<"!?
			thresholdLabel = String.format("Radii less than %.3f queued for interpolation", thresholdValue);
		} else {
			thresholdLabel = String.format("Radii %s %.3f queued for interpolation", thresholdCriterion,
					thresholdValue);
		}
	}

	@Override
	public void run() {

		if (!sntService.isActive())
			error("SNT not running?");
		if (paths == null || paths.isEmpty())
			error("Invalid input: Not a valid collection of Paths.");

		statusService.showStatus("Interpolating radii...");
		final boolean preview = applyChoice.toLowerCase().contains("prev");
		final boolean apply = applyChoice.toLowerCase().contains("apply");
		int replaced = 0;
		final List<ShollPlot> allPlots = new ArrayList<>();
		for (final Path path : paths) {

			if (!path.hasRadii()) {
				SNTUtils.log("Skipping " + path.getName() + ": No radii exit");
				continue;
			}
			if (path.size() < 2) {
				SNTUtils.log("Skipping " + path.getName() + ": Not enough nodes");
				continue;
			}

			if (preview) {
				plot = new ShollPlot(path.getName(), "Node position (0-based indices)", "Radius");
				plotOriginals(path, plot);
			}
			try {
				DoublePredicate predicate = thresholdPredicate();
				if (predicate == null)
					predicate = nonZeroPredicate();
				else
					predicate = predicate.or(nonZeroPredicate());
				replacements = path.sanitizeRadii(predicate, apply);
				if (replacements == null || replacements.isEmpty()) {
					SNTUtils.log("Skipping " + path.getName() + ": No replacements needed or no valid replacement found");
				} else {
					replaced += replacements.size();
				}
			} catch (final Exception ex) {
				SNTUtils.log("Interpolation failed for " + path.getName() + ": " + ex.getMessage());
			}
			if (preview) {
				plotReplacements(replacements, plot, (apply) ? "Replaced" : "Interpolated");
				plot.enableLegend(true);
				allPlots.add(plot);
			}
		}

		ShollPlot.show(allPlots, "Radii Interpolations");
		sntService.updateViewers();
		SNTUtils.log("Finished...");
		if (replaced == 0) {
			error("No radii have been interpolated. "
					+ "Either: i) none of the selected path(s) have radii assigned, ii) None of their nodes has "
					+ "an invalid radius, or iii) interpolation is not possible.");
		} else {
			msg(replaced + " node(s) interpolated across " + (paths.size()) + " path(s).", "Interpolation Completed");
		}
		resetUI();

	}

	private DoublePredicate thresholdPredicate() {
		switch(thresholdCriterion) {
		case "<":
			return (x) -> { return x < thresholdValue; };
		case "≤":
			return (x) -> { return x <= thresholdValue; };
		case ">":
			return (x) -> { return x > thresholdValue; };
		case "≥":
			return (x) -> { return x >= thresholdValue; };
		case "=":
			return (x) -> { return x == thresholdValue; };
		case "≠":
			return (x) -> { return x != thresholdValue; };
		default:
			return null;
		}
	}

	private DoublePredicate nonZeroPredicate() {
		return (x) -> { return x < 0 || Double.isNaN(x); };
	}

	private void plotOriginals(final Path p, final ShollPlot plot) {
		final double[] radii = new double[p.size()];
		for (int i = 0; i < p.size(); i++) {
			radii[i] = p.getNodeRadius(i);
		}
		plot.setColor(Color.BLUE);
		plot.setLineWidth(2f);
		plot.addPoints(IntStream.range(0, p.size()).mapToDouble(d -> d).toArray(), radii, "Original");
	}

	private void plotReplacements(final Map<Integer, Double> map, final ShollPlot plot, final String legend) {
		final List<double[]> points = new ArrayList<>();
		if (map != null) {
			map.forEach((i, d) -> points.add(new double[] { i, d }));
		}
		plot.setColor(Color.RED);
		plot.addPoints(points, legend);
	}

	/* IDE debug method **/
	public static void main(final String[] args) throws IOException {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Collection<Path> paths = sntService.demoTree("op").list();
		input.put("paths", paths);
		ij.command().run(InterpolateRadiiCmd.class, true, input);
	}

}
