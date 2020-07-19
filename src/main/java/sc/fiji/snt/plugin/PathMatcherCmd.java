/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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
@Plugin(type = Command.class, visible = false, label="Match Path(s) Across Time...", initializer = "init")
public class PathMatcherCmd extends CommonDynamicCmd {

	private static final String TAG_FORMAT = "{Group %d}";
	protected static final String TAG_REGEX_PATTERN = "\\{Group \\d+\\}";

	@Parameter(label = "<HTML><b>Range Criteria for Frames:", persist = false, 
			required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER1;

	@Parameter(required = false, label = "Time points:", description="<HTML><div WIDTH=500>"
			+ "Only paths associated with these time-points will be considered for matching. "
			+ "Range(s) (e.g. <tt>2-14</tt>), and list(s) (e.g. <tt>1,3,20,22</tt>) are accepted. "
			+ "Leave empty or type <tt>all</tt> to consider all time-points.")
	private String inputRange;

	@Parameter(label = "<HTML>&nbsp;<br><b>Location Criteria for Starting Node:", 
			persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(label = "X neighborhood:", description="<HTML><div WIDTH=500>"
			+ "Only paths sharing a common origin will be matched. Paths within this 'motion-shift' "
			+ "neighboorhood along the X-axis are assumed to share the same origin. Set it to 0 to "
			+ "disable this option. Assumes spatially calibrated units.")
	private double xNeighborhood;

	@Parameter(label = "Y neighborhood:", description="<HTML><div WIDTH=500>"
			+ "Only paths sharing a common origin will be matched. Paths within this 'motion-shift' "
			+ "neighboorhood along the Y-axis are assumed to share the same origin. Set it to 0 to "
			+ "disable this option. Assumes spatially calibrated units.")
	private double yNeighborhood;

	@Parameter(label = "Z neighborhood:", description="<HTML><div WIDTH=500>"
			+ "Only paths sharing a common origin will be matched. Paths within this 'motion-shift' "
			+ "neighboorhood along the Z-axis are assumed to share the same origin. Set it to 0 to "
			+ "disable this option. Assumes spatially calibrated units.")
	private double zNeighborhood;

	@Parameter(label = "<HTML>&nbsp;<br><b>Options:", 
			persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER3;

	@Parameter(label = "Assign unique colors to groups", description="<HTML><div WIDTH=500>"
			+ "Whether pats from the same group should be assigned a common color tag.")
	private boolean assignUniqueColors;

	@Parameter(label = "Clear Existing Groups", callback = "wipeExistingMatches",
			description="<HTML><div WIDTH=500>Removes maching tags from previous runs. "
					+ "Does nothing if no such tags exist.")
	private Button wipeExistingMatches;

	@Parameter(required = true)
	private Collection<Path> paths;
	private boolean existingMatchesWiped = false;


	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
	}

	private void wipeExistingMatches() {
		for (final Path p : paths) {
			p.setName(p.getName().replaceAll(TAG_REGEX_PATTERN, ""));
		}
		existingMatchesWiped = true;
	}

	@Override
	public void cancel() {
		if (existingMatchesWiped && ui != null) ui.getPathManager().reload();
		super.cancel();
	}

	@Override
	public void run() {

		// Get valid range
		final Set<Integer> timePoints = getTimePoints(inputRange);

		// Operate only on paths matching inputRange
		final Stack<MatchingPath> mPaths = new Stack<>();
		if (timePoints == null) {
			paths.stream().forEach(p -> mPaths.push(new MatchingPath(p)));
		} else {
			paths.stream().forEach(p-> {
				if (timePoints.contains(p.getFrame())) mPaths.push(new MatchingPath(p));
			});
		}

		if (mPaths.isEmpty()) {
			error("No paths to process: Either no paths were specified or range of time-points is not valid.");
			return;
		}

		// Match paths
		ColorRGB[] colors = SNTColor.getDistinctColors(21);
		int groupCounter = 0;
		int colorCounter = 0;
		while (!mPaths.isEmpty()) {
			final MatchingPath current = mPaths.pop();
			final List<MatchingPath> pathsWithCommonRoot = mPaths.stream().filter( p -> p.matches(current)).collect(Collectors.toList());
			if (colorCounter > colors.length) colorCounter = 0;
			for (final MatchingPath hit : pathsWithCommonRoot) {
				hit.assignID(groupCounter+1);
				if (assignUniqueColors) hit.path.setColor(colors[colorCounter]);
			}
			mPaths.removeAll(pathsWithCommonRoot);
			groupCounter++;
			colorCounter++;
		}
		if (groupCounter == paths.size()) {
			msg("Unsuccessful maching: Each path was assigned to its own group.", "Matching Completed");
			wipeExistingMatches();
		} else {
			if (groupCounter == 1) {
				msg("All paths were assigned a common group tag.", "Matching Completed");
			} else {
				final String timePointsParsed = (timePoints == null) ? "all" : String.valueOf(timePoints.size());
				msg(String.format("%d paths assigned to %d group(s) across %s timepoints.", paths.size(), groupCounter,
						timePointsParsed), "Matching Completed");
			}
			if (ui != null) {
				ui.getPathManager().reload();
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
			final PointInImage root = path.getNode(0);
			box = new BoundingBox();
			box.setOrigin(new PointInImage(root.getX() - xNeighborhood, root.getY() - yNeighborhood,
					root.getZ() - zNeighborhood));
			box.setOriginOpposite(new PointInImage(root.getX() + xNeighborhood, root.getY() + yNeighborhood,
					root.getZ() + zNeighborhood));
		}

		boolean matches(final MatchingPath other) {
			/* Matching conditions:
			 * 1. Same path
			 * 2. Different path belonging to a different frame with root within neighborhood
			 */
			if (path.equals(other.path)) return true;
			return path.getFrame() != other.path.getFrame() && box.contains(other.path.getNode(0));
		}

		void assignID(final int id) {
			path.setName(path.getName() + String.format(TAG_FORMAT, id));
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(PathMatcherCmd.class, true, input);
	}
}
