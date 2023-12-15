/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.Button;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;

/**
 * Command for matching Paths across time-points (time-lapse analysis).
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label="Match Path(s) Across Time...", initializer = "init")
public class PathMatcherCmd extends CommonDynamicCmd {

	private static final String TAG_FORMAT = "{neurite#%d}";
	protected static final String TAG_REGEX_PATTERN = "\\{neurite#\\d+\\}";

	@Parameter(label = "<HTML><b>Range Criteria for Time Points:", persist = false, 
			required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER1;

	@Parameter(required = false, label = "Frame range", description="<HTML><div WIDTH=500>"
			+ "Only paths associated with these frames will be considered for matching. "
			+ "Range(s) (e.g. <tt>2-14</tt>), and list(s) (e.g. <tt>1,3,20,22</tt>) are accepted. "
			+ "Leave empty or type <tt>all</tt> to consider all time-points.")
	private String inputRange;

	@Parameter(label = "<HTML>&nbsp;<br><b>Matching Criteria:", 
			persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(label = "Origin", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to start at a common XYZ location.")
	private boolean startNodeLocationMatching;

	@Parameter(label = "Orientation", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to extent under the same overall direction "
			+ "(outgrowth angle).")
	private boolean directionMatching;
	
	@Parameter(label = "Channel", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to share the same channel to be matched.")
	private boolean channelMatching;

	@Parameter(label = "Path order", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to share the same order to be matched.")
	private boolean pathOrderMatching;

	@Parameter(label = "Type tag", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to share the same type tag ('Axon', 'Dendrite', etc.) to be matched.")
	private boolean typeTagMatching;

	@Parameter(label = "Color tag", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to share the same color tag to be matched.")
	private boolean colorTagMatching;

	@Parameter(label = "Custom tag", description="<HTML><div WIDTH=500>"
			+ "Whether paths neeed to share a custom tag to be matched.")
	private boolean customTagMatching;

	@Parameter(label = "<HTML>&nbsp;<br><b>Matching Criteria Settings:", 
			persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER3;

	@Parameter(label = "Orgin: X neighborhood", required = false, stepSize = "0.05", 
			style = "format:#.00000", description = "<HTML><div WIDTH=500>"
			+ "Starting node location: Paths within this 'motion-shift' neighborhood "
			+ "along the X-axis are assumed to share the same origin. Ignored if 'Starting "
			+ "node location' is deselected. Assumes spatially calibrated units.")
	private double xNeighborhood;

	@Parameter(label = "Origin: Y neighborhood", required = false, stepSize = "0.05", 
			style = "format:#.00000", description="<HTML><div WIDTH=500>"
			+ "Starting node location: Paths within this 'motion-shift' neighborhood "
			+ "along the Y-axis are assumed to share the same origin. Ignored if 'Starting "
			+ "node location' is deselected. Assumes spatially calibrated units.")
	private double yNeighborhood;

	@Parameter(label = "Origin: Z neighborhood", required = false, stepSize = "0.05", 
			style = "format:#.00000", description="<HTML><div WIDTH=500>"
			+ "Starting node location: Paths within this 'motion-shift' neighborhood "
			+ "along the Z-axis are assumed to share the same origin. Ignored if 'Starting "
			+ "node location' is deselected. Assumes spatially calibrated units.")
	private double zNeighborhood;

	@Parameter(label = "Orientation: Angle range (°)", required = false, stepSize = "0.5", 
			style = "format:#.0", min = "0", max = "360", description="<HTML><div WIDTH=500>"
			+ "Paths sharing an outgrowth angle +/- this range (in degrees) are assumed to "
			+ "share the same overall direction of growth. Ignored if 'Orientation' is"
			+ "deselected.")
	private double directionMatchingRange;

	@Parameter(label = "Custom tag", required = false, description="<HTML><div WIDTH=500>"
			+ "The string (case sensitive) to be consider when assessing custom tag "
			+ "matching. Ignored if 'Custom tag' is deselected. Regex pattern allowed.")
	private String customTagPattern;

	@Parameter(label = "<HTML>&nbsp;<br><b>Options:", 
			persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER4;

	@Parameter(label = "Assign unique colors to groups", description="<HTML><div WIDTH=500>"
			+ "Whether pats from the same group should be assigned a common color tag.")
	private boolean assignUniqueColors;
	
	@Parameter(label = "Clear Existing Match(es)", callback = "wipeMatches",
			description="<HTML><div WIDTH=500>Removes maching tags from previous runs. "
					+ "Does nothing if no such tags exist.")
	private Button wipeExistingMatches;

	
	@Parameter(required = true, persist = false)
	private Collection<Path> paths;
	@Parameter(required = false, persist = false)
	private boolean applyDefaults;

	private boolean existingMatchesWiped;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (applyDefaults) {
			inputRange = "";
			startNodeLocationMatching = true;
			channelMatching = true;
			xNeighborhood = yNeighborhood = zNeighborhood = snt.getMinimumSeparation() * 3;
			directionMatching = true;
			directionMatchingRange = 30;
			assignUniqueColors = true;
			List.of("assignUniqueColors", "channelMatching", "colorTagMatching", "customTagMatching",
					"customTagPattern", "directionMatching", "directionMatchingRange", "inputRange",
					"pathOrderMatching", "startNodeLocationMatching", "typeTagMatching", "xNeighborhood",
					"yNeighborhood", "zNeighborhood", "wipeExistingMatches", "SPACER1", "SPACER2", "SPACER3", "SPACER4")
					.forEach(input -> resolveInput(input));
		} else {
			resolveInput("applyDefaults");
		}
	}

	private void wipeMatches() {
		for (final Path p : paths) {
			p.setName(p.getName().replaceAll(TAG_REGEX_PATTERN, ""));
		}
		if (ui != null)
			ui.getPathManager().update();
		existingMatchesWiped = true;
	}

	@Override
	public void cancel() {
		if (existingMatchesWiped && ui != null) ui.getPathManager().update();
		super.cancel();
	}

	private boolean validMatchingChoice() {
		return pathOrderMatching || startNodeLocationMatching || typeTagMatching || colorTagMatching
				|| (customTagMatching && !customTagPattern.trim().isEmpty());
	}

	@Override
	public void run() {

		if (!validMatchingChoice()) {
			error("No paths to process: No matching criteria selected");
			return;
		}

		// Get valid range
		final Set<Integer> timePoints = getTimePoints(inputRange);

		// Operate only on paths matching inputRange
		final Stack<MatchingPath> mPaths = new Stack<>();
		if (timePoints == null) {
			paths.forEach(p -> mPaths.push(new MatchingPath(p)));
		} else {
			paths.forEach(p-> {
				if (timePoints.contains(p.getFrame())) mPaths.push(new MatchingPath(p));
			});
		}

		if (mPaths.isEmpty()) {
			error("No paths to process: Either no paths were specified or range of time-points is not valid.");
			return;
		}

		// Match paths
		final ColorRGB[] colors = SNTColor.getDistinctColors(20);
		int groupCounter = 0;
		int colorCounter = 0;
		while (!mPaths.isEmpty()) {
			final MatchingPath current = mPaths.pop();
			final List<MatchingPath> matches = mPaths.stream().filter( p -> p.matches(current)).collect(Collectors.toList());
			matches.add(current); // add query path to group
			if (colorCounter > colors.length-1) colorCounter = 0;
			for (final MatchingPath hit : matches) {
				hit.assignID(groupCounter+1);
				if (assignUniqueColors) hit.path.setColor(colors[colorCounter]);
			}
			mPaths.removeAll(matches); // remove query and matches from pool
			groupCounter++;
			colorCounter++;
		}
		if (groupCounter == paths.size()) {
			msg("Unsuccessful maching: Each path perceived as an independent neurite!?.", "Matching Completed");
			wipeMatches();
		} else {
			if (groupCounter == 1) {
				msg("All paths were assigned a common neurite.", "Matching Completed");
			} else {
				final String timePointsParsed = (timePoints == null) ? "all" : String.valueOf(timePoints.size());
				msg(String.format("%d paths assigned to %d neurite(s) across %s timepoints.", paths.size(), groupCounter,
						timePointsParsed), "Matching Completed");
			}
			if (ui != null) {
				ui.getPathManager().update();
				resetUI();
			}
		}

	}

	/* null: consider all time-points; empty set: assume invalid input */
	private Set<Integer> getTimePoints(final String userInput) {
		if (userInput == null || userInput.trim().isEmpty() || userInput.equalsIgnoreCase("all"))
			return null;
		try {
			final Set<Integer> timePoints = new HashSet<>();
			final String[] convertedArray = userInput.split(",");
			for (final String number : convertedArray) {
				final int idx1 = userInput.indexOf("-");
				if (idx1 > -1) { // input displayed in range
					final String rngStart = userInput.substring(0, idx1);
					final String rngEnd = userInput.substring(idx1 + 1);
					final int first = Integer.parseInt(rngStart.trim());
					final int last = Integer.parseInt(rngEnd.trim());
					IntStream.rangeClosed(first, last).forEach(timePoint -> {
						timePoints.add(timePoint);
					});
				} else {
					timePoints.add(Integer.parseInt(number.trim()));
				}
			}
			return timePoints;
		} catch (final NumberFormatException ignored) {
			return new HashSet<>();
		}
	}

	@SuppressWarnings("unused")
	private boolean pathsExistAcrossMultipleTimePoints() {
		final int refFrame = paths.iterator().next().getFrame();
		return paths.stream().anyMatch(p -> p.getFrame() != refFrame);
	}

	private class MatchingPath {

		Path path;
		BoundingBox box;

		MatchingPath(final Path path) {
			this.path = path;
		}

		BoundingBox bBox() {
			if (box == null) {
				final PointInImage root = path.getNode(0);
				box = new BoundingBox();
				box.setOrigin(new PointInImage(root.getX() - xNeighborhood, root.getY() - yNeighborhood,
						root.getZ() - zNeighborhood));
				box.setOriginOpposite(new PointInImage(root.getX() + xNeighborhood, root.getY() + yNeighborhood,
						root.getZ() + zNeighborhood));
			}
			return box;
		}

		/*
		 * With A being Paths' middle node, B Path's starting node, and C Path's end node, compute
		 * angle between the vectors BA, and BC.
		 * 
		 */
		double calculateDegAngle(final Path p) {
			final PointInImage a = p.getNode(p.size() / 2);
			final PointInImage b = p.getNode(0);
			final PointInImage c = p.getNode(p.size() - 1);
			// define vectors
			final double[] v1 = new double[] { a.getX() - b.getX(), a.getY() - a.getY(), a.getZ() - b.getZ() }; // BA
			final double[] v2 = new double[] { c.getX() - b.getX(), c.getY() - b.getY(), c.getZ() - b.getZ() }; // BC
			// normalize vectors
			final double v1Magnitude = Math.sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]);
			for (int i = 0; i < v1.length; i++)
				v1[i] /= v1Magnitude;
			final double v2Magnitude = Math.sqrt(v2[0] * v2[0] + v2[1] * v2[1] + v2[2] * v2[2]);
			for (int i = 0; i < v2.length; i++)
				v2[i] /= v2Magnitude;
			// Dot product
			double dot = 0.0;
			for (int i = 0; i < v1.length; i++) {
				dot += v1[i] * v2[i];
			}
			double angle = Math.toDegrees(Math.acos(dot));
			angle = (angle + Math.ceil(-angle / 360) * 360); // Keep angle between 0 and 360
			return angle;
		}

		boolean matches(final MatchingPath other) {
			if (path.getFrame() == other.path.getFrame())
				return false; // paths cannot belong to the same neurite if they co-exist on the same frame
			if (startNodeLocationMatching && !bBox().contains(other.path.getNode(0)))
				return false; // paths cannot belong to the same neurite without sharing common origin
			if (directionMatching && Math
					.abs(calculateDegAngle(path) - calculateDegAngle(other.path)) > directionMatchingRange)
				return false; // paths cannot belong to the same neurite without sharing common direction
			if (channelMatching && path.getChannel() != other.path.getChannel())
				return false; // paths cannot belong to the same neurite without sharing same channel
			if (pathOrderMatching && path.getOrder() != other.path.getOrder())
				return false; // paths cannot belong to the same neurite without sharing same order
			if (typeTagMatching && path.getSWCType() != other.path.getSWCType())
				return false; // paths cannot belong to the same neurite without sharing same type
			if (colorTagMatching && path.getColor() != other.path.getColor())
				return false; // paths cannot belong to the same neurite without sharing color tag
			if (customTagMatching
					&& (!path.getName().contains(customTagPattern) || !other.path.getName().contains(customTagPattern)))
				return false; // paths cannot belong to the same neurite without sharing custom tag
			return true;
		}

		void assignID(final int id) {
			path.setName(path.getName() + String.format(TAG_FORMAT, id));
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(PathMatcherCmd.class, true, input);
	}
}
