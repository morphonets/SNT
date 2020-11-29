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

package sc.fiji.snt;

import java.awt.Color;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

/**
 * Implements a common thread that explores the image using a variety of
 * strategies, e.g., to trace tubular structures or surfaces.
 * 
 * @author Mark Longair
 * @author Tiago Ferreira
 */
public abstract class SearchThread extends Thread implements SearchInterface {

	public static final byte OPEN_FROM_START = 1;
	public static final byte CLOSED_FROM_START = 2;
	public static final byte OPEN_FROM_GOAL = 3;
	public static final byte CLOSED_FROM_GOAL = 4;
	public static final byte FREE = 5; // Indicates that this node isn't in a list yet...

	/** Thread state: the run method is going and the thread is unpaused */
	public static final int RUNNING = 0;
	/** Thread state: run() hasn't been started yet or the thread is paused */
	public static final int PAUSED = 1;
	/** Thread state: the thread cannot be used again */
	public static final int STOPPING = 2;

	public static final int SUCCESS = 0;
	public static final int CANCELLED = 1;
	public static final int TIMED_OUT = 2;
	public static final int POINTS_EXHAUSTED = 3;
	public static final int OUT_OF_MEMORY = 4;
	public static final String[] EXIT_REASONS_STRINGS = { "SUCCESS", "CANCELLED",
		"TIMED_OUT", "POINTS_EXHAUSTED", "OUT_OF_MEMORY" };

	/* This can only be changed in a block synchronized on this object */
	private volatile int threadStatus = PAUSED;

	protected ImagePlus imagePlus;
	protected byte[][] slices_data_b;
	protected short[][] slices_data_s;
	protected float[][] slices_data_f;
	int imageType = -1;
	float stackMin;
	float stackMax;
	protected float x_spacing;
	protected float y_spacing;
	protected float z_spacing;
	protected String spacing_units;
	protected int width;
	protected int height;
	protected int depth;

	private Color openColor;
	private Color closedColor;
	private float drawingThreshold;

	/* The search may only be bidirectional if definedGoal is true */
	private boolean bidirectional;

	/*
	 * If there is no definedGoal then the search is just Dijkstra's algorithm (h =
	 * 0 in the A* search algorithm.
	 */
	private boolean definedGoal;

	private boolean startPaused;
	private int timeoutSeconds;
	private long reportEveryMilliseconds;
	private long lastReportMilliseconds;
	protected ArrayList<SearchProgressCallback> progressListeners;
	protected double minimum_cost_per_unit_distance;
	protected PriorityQueue<SearchNode> closed_from_start;
	protected PriorityQueue<SearchNode> open_from_start;

	// The next two are null if the search is not bidirectional
	private PriorityQueue<SearchNode> closed_from_goal;
	private PriorityQueue<SearchNode> open_from_goal;

	protected SearchNode[][] nodes_as_image_from_start;
	protected SearchNode[][] nodes_as_image_from_goal;

	protected int exitReason;
	private boolean verbose = SNTUtils.isDebugMode();
	private CountDownLatch latch;


	/*
	 * This calculates the cost of moving to a new point in the image. This does not
	 * take into account the distance to this new point, only the value at it. This
	 * will be post-multiplied by the distance from the last point. So, if you want
	 * to take into account the curvature of the image at that point then you should
	 * do so in this method.
	 */

	// The default implementation does a simple reciprocal of the
	// image value scaled to 0 to 255 if it is not already an 8
	// bit value:

	protected double costMovingTo(final int new_x, final int new_y,
		final int new_z)
	{

		double value_at_new_point = -1;
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				value_at_new_point = slices_data_b[new_z][new_y * width + new_x] & 0xFF;
				break;
			case ImagePlus.GRAY16:
				value_at_new_point = slices_data_s[new_z][new_y * width + new_x];
				value_at_new_point = 255.0 * (value_at_new_point - stackMin) /
					(stackMax - stackMin);
				break;
			case ImagePlus.GRAY32:
				value_at_new_point = slices_data_f[new_z][new_y * width + new_x];
				value_at_new_point = 255.0 * (value_at_new_point - stackMin) /
					(stackMax - stackMin);
				break;
		}

		if (value_at_new_point == 0) return 2.0;
		else return 1.0 / value_at_new_point;

	}

	/*
	 * Use this for doing special progress updates, beyond what
	 * SearchProgressCallback provides.
	 */
	protected void reportPointsInSearch() {
		for (final SearchProgressCallback progress : progressListeners)
			progress.pointsInSearch(this, open_from_start.size() + (bidirectional
				? open_from_goal.size() : 0), closed_from_start.size() + (bidirectional
					? closed_from_goal.size() : 0));
	}

	public int pointsConsideredInSearch() {
		return open_from_start.size() + (bidirectional ? open_from_goal.size()
			: 0) + closed_from_start.size() + (bidirectional ? closed_from_goal.size()
				: 0);
	}

	/*
	 * This is a factory method for creating specialized search nodes, subclasses of
	 * SearchNode:
	 */

	protected SearchNode createNewNode(final int x, final int y, final int z,
		final float g, final float h, final SearchNode predecessor,
		final byte searchStatus)
	{
		return new SearchNode(x, y, z, g, h, predecessor, searchStatus);
	}

	/*
	 * This is called if the goal has been found in the search. If your search has
	 * no defined goal, then this will never be called, so don't bother to override
	 * it.
	 */

	protected void foundGoal(final Path pathToGoal) {
		/*
		 * A dummy implementation that does nothing with this exciting news.
		 */
	}

	protected boolean atGoal(final int x, final int y, final int z,
		final boolean fromStart)
	{
		return false;
	}

	void setDrawingColors(final Color openColor, final Color closedColor) {
		this.openColor = openColor;
		this.closedColor = closedColor;
	}

	void setDrawingThreshold(final float threshold) {
		this.drawingThreshold = threshold;
	}

	/*
	 * If you need to force the distance between two points to always be greater
	 * than some value (e.g. to make your A star heuristic valid or something, then
	 * you should override this method and return that value.
	 */
	protected double minimumCostPerUnitDistance() {
		return 0.0;
	}

	public void addProgressListener(final SearchProgressCallback callback) {
		progressListeners.add(callback);
	}

	public int getThreadStatus() {
		return threadStatus;
	}

	// Safely stops the thread (for discarding the object.)

	@Override
	public void requestStop() {
		SNTUtils.log("requestStop called, about to enter synchronized");
		synchronized (this) {
			SNTUtils.log("... entered synchronized");
			if (threadStatus == PAUSED) {
				SNTUtils.log("was paused so interrupting");
				this.interrupt();
				SNTUtils.log("done interrupting");
			}
			threadStatus = STOPPING;
			reportThreadStatus();
			SNTUtils.log("... leaving synchronized");
		}
		countDown();
		SNTUtils.log("requestStop finished (threadStatus now " + threadStatus + ")");
	}

	private void countDown() {
		if (latch != null && latch.getCount() > 0) {
			latch.countDown();
		}
	}

	/**
	 * This method can be overridden if one needs to find out when a point was
	 * first discovered.
	 *
	 * @param n the search node
	 */
	protected void addingNode(final SearchNode n) {}

	public void reportThreadStatus() {
		for (final SearchProgressCallback progress : progressListeners)
			progress.threadStatus(this, threadStatus);
	}

	public void reportFinished(final boolean success) {
		countDown(); // needs to be called before notifying progressListeners
		for (final SearchProgressCallback progress : progressListeners)
			progress.finished(this, success);

	}

	// Toggles the paused or unpaused status of the thread.

	public void pauseOrUnpause() {
		// Toggle the paused status:
		SNTUtils.log("pauseOrUnpause called, about to enter synchronized");
		synchronized (this) {
			SNTUtils.log("... entered synchronized");
			switch (threadStatus) {
				case PAUSED:
					SNTUtils.log("paused, going to switch to running - interrupting first");
					this.interrupt();
					SNTUtils.log("finished interrupting");
					threadStatus = RUNNING;
					break;
				case RUNNING:
					SNTUtils.log("running, going to switch to paused");
					threadStatus = PAUSED;
					break;
				default:
					// Do nothing, we're actually stopping anyway.
			}
			reportThreadStatus();
			SNTUtils.log("... leaving synchronized");
		}
		SNTUtils.log("pauseOrUnpause finished");
	}


	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	public SearchThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final boolean bidirectional,
		final boolean definedGoal, final boolean startPaused,
		final int timeoutSeconds, final long reportEveryMilliseconds)
	{

		this.imagePlus = imagePlus;

		this.stackMin = stackMin;
		this.stackMax = stackMax;

		this.bidirectional = bidirectional;
		this.definedGoal = definedGoal;
		this.startPaused = startPaused;

		this.imageType = imagePlus.getType();

		width = imagePlus.getWidth();
		height = imagePlus.getHeight();
		depth = imagePlus.getNSlices();

		{
			final ImageStack s = imagePlus.getStack();
			switch (imageType) {
				case ImagePlus.GRAY8:
				case ImagePlus.COLOR_256:
					slices_data_b = new byte[depth][];
					for (int z = 0; z < depth; ++z)
						slices_data_b[z] = (byte[]) s.getPixels(z + 1);
					break;
				case ImagePlus.GRAY16:
					slices_data_s = new short[depth][];
					for (int z = 0; z < depth; ++z)
						slices_data_s[z] = (short[]) s.getPixels(z + 1);
					break;
				case ImagePlus.GRAY32:
					slices_data_f = new float[depth][];
					for (int z = 0; z < depth; ++z)
						slices_data_f[z] = (float[]) s.getPixels(z + 1);
					break;
			}
		}

		final Calibration calibration = imagePlus.getCalibration();

		x_spacing = (float) calibration.pixelWidth;
		y_spacing = (float) calibration.pixelHeight;
		z_spacing = (float) calibration.pixelDepth;
		spacing_units = SNTUtils.getSanitizedUnit(calibration.getUnit());

		if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {

			SNTUtils.error(
				"SearchThread: One dimension of the calibration information was zero: (" +
					x_spacing + "," + y_spacing + "," + z_spacing + ")");
			countDown();
			return;

		}

		this.timeoutSeconds = timeoutSeconds;
		this.reportEveryMilliseconds = reportEveryMilliseconds;
		init();
	}

	protected SearchThread(final SNT snt)
		{
			imagePlus = snt.getImagePlus();
			imageType = imagePlus.getType();
			width = snt.width;
			height = snt.height;
			depth = snt.depth;
			stackMin = snt.stackMin;
			stackMax = snt.stackMax;
			if (snt.doSearchOnSecondaryData && snt.isSecondaryImageLoaded()) {
				imageType = ImagePlus.GRAY32;
				slices_data_f = snt.secondaryData;
				stackMin = snt.stackMinSecondary;
				stackMax = snt.stackMaxSecondary;
			} else if (snt.slices_data_b != null) {
				imageType = ImagePlus.GRAY8;
				slices_data_b = snt.slices_data_b;
			} else if (snt.slices_data_s != null) {
				imageType = ImagePlus.GRAY16;
				slices_data_s = snt.slices_data_s;
			} else if (snt.slices_data_f != null) {
				imageType = ImagePlus.GRAY32;
				slices_data_f = snt.slices_data_f;
			}
			x_spacing = (float) snt.x_spacing;
			y_spacing = (float) snt.y_spacing;
			z_spacing = (float) snt.z_spacing;
			spacing_units = snt.spacing_units;

			bidirectional = true;
			definedGoal = true;
			startPaused = false;
			timeoutSeconds = 0;
			reportEveryMilliseconds = 1000;
			init();
		}

	private void init() {
		closed_from_start = new PriorityQueue<>();
		open_from_start = new PriorityQueue<>();
		if (bidirectional) {
			closed_from_goal = new PriorityQueue<>();
			open_from_goal = new PriorityQueue<>();
		}
		nodes_as_image_from_start = new SearchNode[depth][];
		if (bidirectional) nodes_as_image_from_goal = new SearchNode[depth][];
		minimum_cost_per_unit_distance = minimumCostPerUnitDistance();
		progressListeners = new ArrayList<>();
	}

	public void printStatus() {
		SNTUtils.log("... Start nodes: open=" + open_from_start.size() +
			" closed=" + closed_from_start.size());
		if (bidirectional) {
			SNTUtils.log("...  Goal nodes: open=" + open_from_goal.size() +
				" closed=" + closed_from_goal.size());
		}
		else SNTUtils.log(" ... unidirectional search");
	}

	@Override
	public void run() {

		try {

			if (verbose) {
				SNTUtils.log("New SearchThread running!");
				printStatus();
				SNTUtils.log("... was asked to start it in the " + (startPaused ? "paused"
						: "unpaused") + " state.");
			}

			synchronized (this) {
				threadStatus = startPaused ? PAUSED : RUNNING;
				reportThreadStatus();
			}

			final long started_at = lastReportMilliseconds = System
				.currentTimeMillis();

			int loops_at_last_report = 0;
			int loops = 0;

			/*
			 * We maintain the list of nodes in the search in a couple of different data
			 * structures here, which is bad for memory usage but good for the speed of the
			 * search.
			 *
			 * As well as keeping the nodes in priority lists, we keep them in a set of
			 * arrays that are indexed in the same way as voxels in the image.
			 */

			while ((open_from_start.size() > 0) || (bidirectional && (open_from_goal
				.size() > 0)))
			{

				if (threadStatus == STOPPING) {
					reportThreadStatus();
					setExitReason(CANCELLED);
					reportFinished(false);
					return;
				}
				else if (threadStatus == PAUSED) {
					try {
						reportThreadStatus();
						Thread.sleep(4000);
					}
					catch (final InterruptedException ignored) {}
				}

				// We only check every thousandth loop for
				// whether we should report the progress, etc.

				if (0 == (loops % 1000)) {

					final long currentMilliseconds = System.currentTimeMillis();

					final long millisecondsSinceStart = currentMilliseconds - started_at;

					if ((timeoutSeconds > 0) && (millisecondsSinceStart > (1000 *
						timeoutSeconds)))
					{
						SNTUtils.log("Timed out...");
						setExitReason(TIMED_OUT);
						reportFinished(false);
						return;
					}

					final long since_last_report = currentMilliseconds -
						lastReportMilliseconds;

					if ((reportEveryMilliseconds > 0) &&
						(since_last_report > reportEveryMilliseconds))
					{

						final int loops_since_last_report = loops - loops_at_last_report;
						SNTUtils.log("" + (since_last_report /
							(double) loops_since_last_report) + "ms/loop");

						if (verbose) printStatus();

						reportPointsInSearch();

						loops_at_last_report = loops;
					}
				}

				boolean fromStart = true;
				if (bidirectional) fromStart = open_from_goal.size() > open_from_start
					.size();

				final PriorityQueue<SearchNode> open_queue = fromStart ? open_from_start
					: open_from_goal;
				final PriorityQueue<SearchNode> closed_queue = fromStart
					? closed_from_start : closed_from_goal;

				final SearchNode[][] nodes_as_image_this_search = fromStart
					? nodes_as_image_from_start : nodes_as_image_from_goal;
				final SearchNode[][] nodes_as_image_other_search = fromStart
					? nodes_as_image_from_goal : nodes_as_image_from_start;

				SearchNode p = null;

				if (open_queue.size() == 0) continue;

				// p = get_highest_priority( open_from_start,
				// open_from_start_hash );
				p = open_queue.poll();
				if (p == null) continue;

				// Has the route from the start found the goal?
				if (definedGoal && atGoal(p.x, p.y, p.z, fromStart)) {
					SNTUtils.log("Found the goal!");
					if (fromStart) foundGoal(p.asPath(x_spacing, y_spacing, z_spacing,
						spacing_units));
					else foundGoal(p.asPathReversed(x_spacing, y_spacing, z_spacing,
						spacing_units));
					setExitReason(SUCCESS);
					reportFinished(true);
					return;
				}

				p.searchStatus = fromStart ? CLOSED_FROM_START : CLOSED_FROM_GOAL;
				closed_queue.add(p);
				nodes_as_image_this_search[p.z][p.y * width + p.x] = p;

				// Now look at the neighbours of p. We're going to consider
				// the 26 neighbours in 3D.

				for (int zdiff = -1; zdiff <= 1; zdiff++) {

					final int new_z = p.z + zdiff;
					if (new_z < 0 || new_z >= depth) continue;

					if (nodes_as_image_this_search[new_z] == null) {
						nodes_as_image_this_search[new_z] = new SearchNode[width * height];
					}

					for (int xdiff = -1; xdiff <= 1; xdiff++)
						for (int ydiff = -1; ydiff <= 1; ydiff++) {

							if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0)) continue;

							final int new_x = p.x + xdiff;
							final int new_y = p.y + ydiff;

							if (new_x < 0 || new_x >= width) continue;

							if (new_y < 0 || new_y >= height) continue;

							final double xdiffsq = (xdiff * x_spacing) * (xdiff * x_spacing);
							final double ydiffsq = (ydiff * y_spacing) * (ydiff * y_spacing);
							final double zdiffsq = (zdiff * z_spacing) * (zdiff * z_spacing);

							final float h_for_new_point = estimateCostToGoal(new_x, new_y,
								new_z, fromStart);

							double cost_moving_to_new_point = costMovingTo(new_x, new_y,
								new_z);
							if (cost_moving_to_new_point < minimum_cost_per_unit_distance) {
								cost_moving_to_new_point = minimum_cost_per_unit_distance;
							}

							final float g_for_new_point = (float) (p.g + Math.sqrt(xdiffsq +
								ydiffsq + zdiffsq) * cost_moving_to_new_point);

							final float f_for_new_point = h_for_new_point + g_for_new_point;

							final SearchNode newNode = createNewNode(new_x, new_y, new_z,
								g_for_new_point, h_for_new_point, p, FREE);

							// Is this newNode really new?
							final SearchNode alreadyThereInThisSearch =
								nodes_as_image_this_search[new_z][new_y * width + new_x];

							if (alreadyThereInThisSearch == null) {

								newNode.searchStatus = fromStart ? OPEN_FROM_START
									: OPEN_FROM_GOAL;
								open_queue.add(newNode);
								addingNode(newNode);
								nodes_as_image_this_search[new_z][new_y * width + new_x] =
									newNode;

							}
							else {

								// The other alternative is that this node is
								// already in one
								// of the lists working from the start but has a
								// better way
								// of getting to that point.

								if (alreadyThereInThisSearch.f > f_for_new_point) {

									if (alreadyThereInThisSearch.searchStatus == (fromStart
										? OPEN_FROM_START : OPEN_FROM_GOAL))
									{

										open_queue.remove(alreadyThereInThisSearch);
										alreadyThereInThisSearch.setFrom(newNode);
										alreadyThereInThisSearch.searchStatus = fromStart
											? OPEN_FROM_START : OPEN_FROM_GOAL;
										open_queue.add(alreadyThereInThisSearch);

									}
									else if (alreadyThereInThisSearch.searchStatus == (fromStart
										? CLOSED_FROM_START : CLOSED_FROM_GOAL))
									{

										closed_queue.remove(alreadyThereInThisSearch);
										alreadyThereInThisSearch.setFrom(newNode);
										alreadyThereInThisSearch.searchStatus = fromStart
											? OPEN_FROM_START : OPEN_FROM_GOAL;
										open_queue.add(alreadyThereInThisSearch);
									}
								}
							}

							if (bidirectional && nodes_as_image_other_search[new_z] != null) {

								final SearchNode alreadyThereInOtherSearch =
									nodes_as_image_other_search[new_z][new_y * width + new_x];
								if (alreadyThereInOtherSearch != null) {

									Path result = null;

									// If either of the next two if conditions
									// are true
									// then we've finished.

									if (alreadyThereInOtherSearch != null &&
										(alreadyThereInOtherSearch.searchStatus == CLOSED_FROM_START ||
											alreadyThereInOtherSearch.searchStatus == CLOSED_FROM_GOAL))
									{

										if (fromStart) {
											result = p.asPath(x_spacing, y_spacing, z_spacing,
												spacing_units);
											final Path fromGoalReversed = alreadyThereInOtherSearch
												.asPathReversed(x_spacing, y_spacing, z_spacing,
													spacing_units);
											result.add(fromGoalReversed);
										}
										else {
											result = alreadyThereInOtherSearch.asPath(x_spacing,
												y_spacing, z_spacing, spacing_units);
											result.add(p.asPathReversed(x_spacing, y_spacing,
												z_spacing, spacing_units));
										}
										SNTUtils.log("Searches met!");
										foundGoal(result);
										setExitReason(SUCCESS);
										reportFinished(true);
										return;
									}
								}
							}
						}
				}
				++loops;
			}

			/*
			 * If we get to here then we haven't found a route to the point. (With the
			 * current impmlementation this shouldn't happen, so print a warning - probably
			 * the programmer hasn't populated the open list to start with.) However, in
			 * this case let's return the best path so far anyway...
			 */

			SNTUtils.log("FAILED to find a route.  Shouldn't happen...");
			setExitReason(POINTS_EXHAUSTED);
			reportFinished(false);
		}
		catch (final OutOfMemoryError oome) {
			SNTUtils.error("Out Of Memory Error", oome);
			//GuiUtils.errorPrompt("Out of memory while searching for a path");
			setExitReason(OUT_OF_MEMORY);
			reportFinished(false);
		}
		catch (final Throwable t) {
			SNTUtils.error("Exception in search thread", t);
		}
		finally {
			countDown();
		}
	}

	/*
	 * This is the heuristic value for the A* search. There's no defined goal in
	 * this default superclass implementation, so always return 0 so we end up with
	 * Dijkstra's algorithm.
	 */

	float estimateCostToGoal(final int current_x, final int current_y,
		final int current_z, final boolean fromStart)
	{
		return 0;
	}

	/* This method is used to set the reason for the thread finishing */
	void setExitReason(final int exitReason) {
		this.exitReason = exitReason;
	}

	/*
	 * Use this to find out why the thread exited if you're not adding listeners to
	 * do that.
	 */
	int getExitReason() {
		return exitReason;
	}

	SearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
		final double threshold)
	{
		final SearchNode[] startSlice = nodes_as_image_from_start[z];
		SearchNode[] goalSlice = null;
		if (nodes_as_image_from_goal != null) goalSlice =
			nodes_as_image_from_goal[z];
		final int index = y * width + x;
		SearchNode n = null;
		if (startSlice != null) {
			n = startSlice[index];
			if (n != null && threshold >= 0 && n.g > threshold) n = null;
			if (n == null && goalSlice != null) {
				n = goalSlice[index];
				if (threshold >= 0 && n.g > threshold) n = null;
			}
		}
		return n;
	}

	/*
	 * This draws over the Graphics object the current progress of the search at
	 * this slice. If openColor or closedColor are null then that means
	 * "don't bother to draw that list".
	 */

	@Override
	public void drawProgressOnSlice(final int plane,
		final int currentSliceInPlane, final TracerCanvas canvas, final Graphics g)
	{

		for (int i = 0; i < 2; ++i) {

			/*
			 * The first time through we draw the nodes in the open list, the second time
			 * through we draw the nodes in the closed list.
			 */

			final byte start_status = (i == 0) ? OPEN_FROM_START : CLOSED_FROM_START;
			final byte goal_status = (i == 0) ? OPEN_FROM_GOAL : CLOSED_FROM_GOAL;
			final Color c = (i == 0) ? openColor : closedColor;
			if (c == null) continue;

			g.setColor(c);

			int pixel_size = (int) canvas.getMagnification();
			if (pixel_size < 1) pixel_size = 1;

			if (plane == MultiDThreePanes.XY_PLANE) {
				for (int y = 0; y < height; ++y)
					for (int x = 0; x < width; ++x) {
						final SearchNode n = anyNodeUnderThreshold(x, y, currentSliceInPlane,
							drawingThreshold);
						if (n == null) continue;
						final byte status = n.searchStatus;
						if (status == start_status || status == goal_status) g.fillRect(
							canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(y) -
								pixel_size / 2, pixel_size, pixel_size);
					}
			}
			else if (plane == MultiDThreePanes.XZ_PLANE) {
				for (int z = 0; z < depth; ++z)
					for (int x = 0; x < width; ++x) {
						final SearchNode n = anyNodeUnderThreshold(x, currentSliceInPlane, z,
							drawingThreshold);
						if (n == null) continue;
						final byte status = n.searchStatus;
						if (status == start_status || status == goal_status) g.fillRect(
							canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(z) -
								pixel_size / 2, pixel_size, pixel_size);
					}
			}
			else if (plane == MultiDThreePanes.ZY_PLANE) {
				for (int y = 0; y < height; ++y)
					for (int z = 0; z < depth; ++z) {
						final SearchNode n = anyNodeUnderThreshold(currentSliceInPlane, y, z,
							drawingThreshold);
						if (n == null) continue;
						final byte status = n.searchStatus;
						if (status == start_status || status == goal_status) g.fillRect(
							canvas.myScreenX(z) - pixel_size / 2, canvas.myScreenY(y) -
								pixel_size / 2, pixel_size, pixel_size);
					}
			}
		}
	}

	// Add a node, ignoring requests to add duplicate nodes:

	public void addNode(final SearchNode n, final boolean fromStart) {

		final SearchNode[][] nodes_as_image = fromStart ? nodes_as_image_from_start
			: nodes_as_image_from_goal;

		if (nodes_as_image[n.z] == null) {
			nodes_as_image[n.z] = new SearchNode[width * height];
		}

		if (nodes_as_image[n.z][n.y * width + n.x] != null) {
			// Then there's already a node there:
			return;
		}

		if (n.searchStatus == OPEN_FROM_START) {

			open_from_start.add(n);
			nodes_as_image[n.z][n.y * width + n.x] = n;

		}
		else if (n.searchStatus == OPEN_FROM_GOAL) {
			assert bidirectional && definedGoal;

			open_from_goal.add(n);
			nodes_as_image[n.z][n.y * width + n.x] = n;

		}
		else if (n.searchStatus == CLOSED_FROM_START) {

			closed_from_start.add(n);
			nodes_as_image[n.z][n.y * width + n.x] = n;

		}
		else if (n.searchStatus == CLOSED_FROM_GOAL) {
			assert bidirectional && definedGoal;

			closed_from_goal.add(n);
			nodes_as_image[n.z][n.y * width + n.x] = n;

		}

	}

	@Override
	public void setCountDownLatch(final CountDownLatch latch) {
		this.latch = latch;
	}

}
