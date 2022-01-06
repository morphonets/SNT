/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt.tracing;

import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.jheaps.AddressableHeap;
import org.jheaps.tree.PairingHeap;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchProgressCallback;
import sc.fiji.snt.tracing.cost.Cost;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;
import sc.fiji.snt.tracing.image.SupplierUtil;

import java.util.ArrayList;

/**
 * Implements a common thread that explores the image using a variety of
 * strategies, e.g., to trace tubular structures or surfaces.
 * 
 * @author Mark Longair
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public abstract class SearchThread extends AbstractSearch {

	public static final byte OPEN_FROM_START = 1;
	public static final byte CLOSED_FROM_START = 2;
	public static final byte OPEN_FROM_GOAL = 3;
	public static final byte CLOSED_FROM_GOAL = 4;
	public static final byte FREE = 5; // Indicates that this node isn't in a list yet...

	public static final int SUCCESS = 0;
	public static final int CANCELLED = 1;
	public static final int TIMED_OUT = 2;
	public static final int POINTS_EXHAUSTED = 3;
	public static final int OUT_OF_MEMORY = 4;
	public static final String[] EXIT_REASONS_STRINGS = { "SUCCESS", "CANCELLED",
		"TIMED_OUT", "POINTS_EXHAUSTED", "OUT_OF_MEMORY" };

	/* The search may only be bidirectional if definedGoal is true */
	private final boolean bidirectional;

	protected final Cost costFunction;

	/*
	 * If there is no definedGoal then the search is just Dijkstra's algorithm (h =
	 * 0 in the A* search algorithm.
	 */
	private final boolean definedGoal;

	protected AddressableHeap<DefaultSearchNode, Void> open_from_start;
	private AddressableHeap<DefaultSearchNode, Void> open_from_goal;

	protected long closed_from_start_count;
	protected long closed_from_goal_count;

	protected SearchImageStack<DefaultSearchNode> nodes_as_image_from_start;
	protected SearchImageStack<DefaultSearchNode> nodes_as_image_from_goal;

	protected int exitReason;
	protected final boolean verbose = SNTUtils.isDebugMode();

	protected long started_at;
	protected long loops;
	protected long loops_at_last_report;
	protected long lastReportMilliseconds;

	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	protected SearchThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
						   final boolean bidirectional, final boolean definedGoal, final int timeoutSeconds,
						   final long reportEveryMilliseconds, final SNT.SearchImageType searchImageType,
						   final Cost costFunction)
	{
		super(image, calibration, timeoutSeconds, reportEveryMilliseconds);
		this.bidirectional = bidirectional;
		this.definedGoal = definedGoal;
		this.nodes_as_image_from_start = new SearchImageStack<>(SupplierUtil.createSupplier(searchImageType,
																							DefaultSearchNode.class,
																							imgWidth,
																							imgHeight));
		if (bidirectional) {
			this.nodes_as_image_from_goal = new SearchImageStack<>(SupplierUtil.createSupplier(searchImageType,
																							   DefaultSearchNode.class,
																							   imgWidth,
																							   imgHeight));
		}
		this.costFunction = costFunction;
		init();
	}

	protected SearchThread(final Dataset dataset, final boolean bidirectional, final boolean definedGoal,
						   final int timeoutSeconds, final long reportEveryMilliseconds,
						   final SNT.SearchImageType searchImageType, final Cost costFunction)
	{
		super(dataset, timeoutSeconds, reportEveryMilliseconds);
		this.bidirectional = bidirectional;
		this.definedGoal = definedGoal;
		this.nodes_as_image_from_start = new SearchImageStack<>(SupplierUtil.createSupplier(searchImageType,
																							DefaultSearchNode.class,
																							imgWidth,
																							imgHeight));
		if (bidirectional) {
			this.nodes_as_image_from_goal = new SearchImageStack<>(SupplierUtil.createSupplier(searchImageType,
																							   DefaultSearchNode.class,
																							   imgWidth,
																							   imgHeight));
		}
		this.costFunction = costFunction;
		init();
	}

	protected SearchThread(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image,
						   final Cost costFunction)
	{
		super(snt, image);
		this.costFunction = costFunction;
		this.bidirectional = true;
		this.definedGoal = true;
		this.nodes_as_image_from_start = new SearchImageStack<>(SupplierUtil.createSupplier(snt.getSearchImageType(), DefaultSearchNode.class, imgWidth, imgHeight));
		this.nodes_as_image_from_goal = new SearchImageStack<>(SupplierUtil.createSupplier(snt.getSearchImageType(), DefaultSearchNode.class, imgWidth, imgHeight));
		init();
	}

	private void init() {
		this.open_from_start = new PairingHeap<>();
		if (this.bidirectional) {
			this.open_from_goal = new PairingHeap<>();
		}
		this.progressListeners = new ArrayList<>();
	}

	@Override
	protected void reportPointsInSearch() {
		for (final SearchProgressCallback progress : progressListeners)
			progress.pointsInSearch(this, open_from_start.size() + (bidirectional
				? open_from_goal.size() : 0), closed_from_start_count + (bidirectional
					? closed_from_goal_count : 0));
	}

	@Override
	public long pointsConsideredInSearch() {
		return open_from_start.size() + (bidirectional ? open_from_goal.size()
			: 0) + closed_from_start_count + (bidirectional ? closed_from_goal_count
				: 0);
	}

	/*
	 * This is a factory method for creating specialized search nodes, subclasses of
	 * SearchNode:
	 */

	public DefaultSearchNode createNewNode(final int x, final int y, final int z,
										   final double g, final double h, final DefaultSearchNode predecessor,
										   final byte searchStatus)
	{
		return new DefaultSearchNode(x,y,z, g, h, predecessor, searchStatus);
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

	protected boolean atGoal(final int x, final int y, final int z, final boolean fromStart) {
		return false;
	}

	@Override
	public void addProgressListener(final SearchProgressCallback callback) {
		progressListeners.add(callback);
	}

	public void reportFinished(final boolean success) {
		for (final SearchProgressCallback progress : progressListeners)
			progress.finished(this, success);
	}

	@Override
	public void printStatus() {
		SNTUtils.log("... Start nodes: open=" + open_from_start.size() +
			" closed=" + closed_from_start_count);
		if (bidirectional) {
			SNTUtils.log("...  Goal nodes: open=" + open_from_goal.size() +
				" closed=" + closed_from_goal_count);
		}
		else SNTUtils.log(" ... unidirectional search");
	}

	protected boolean checkStatus() {
		final long currentMilliseconds = System.currentTimeMillis();
		final long millisecondsSinceStart = currentMilliseconds - started_at;

		if ((timeoutSeconds > 0) && (millisecondsSinceStart > (1000L * timeoutSeconds))) {
			return true;
		}

		final long since_last_report = currentMilliseconds - lastReportMilliseconds;
		if ((reportEveryMilliseconds > 0) && (since_last_report > reportEveryMilliseconds)) {
			final long loops_since_last_report = loops - loops_at_last_report;
			if (verbose) {
				System.out.println("" + (since_last_report / (double) loops_since_last_report) + "ms/loop");
				printStatus();
			}

			reportPointsInSearch();

			loops_at_last_report = loops;
			lastReportMilliseconds = currentMilliseconds;
		}
		return false;
	}

	@Override
	public void run() {

		try {

			if (verbose) {
				SNTUtils.log("New " + getClass().getSimpleName() + " running!");
				printStatus();
			}

			started_at = lastReportMilliseconds = System.currentTimeMillis();

			/*
			 * We maintain the list of nodes in the search in a couple of different data
			 * structures here, which is bad for memory usage but good for the speed of the
			 * search.
			 *
			 * As well as keeping the nodes in priority lists, we keep them in a set of
			 * arrays that are indexed in the same way as voxels in the image.
			 */

			final int[] imgPosition = new int[3];

			while ((open_from_start.size() > 0) || (bidirectional && (open_from_goal
				.size() > 0)))
			{

				if (Thread.currentThread().isInterrupted()) {
					setExitReason(CANCELLED);
					reportFinished(false);
					return;
				}

				++loops;

				// We only check every thousandth loop for
				// whether we should report the progress, etc.

				if (0 == (loops % 10000) && checkStatus()) {
					SNTUtils.log("Timed out...");
					setExitReason(TIMED_OUT);
					reportFinished(false);
					return;
				}

				boolean fromStart = true;
				if (bidirectional) fromStart = open_from_goal.size() > open_from_start
					.size();

				final AddressableHeap<DefaultSearchNode, Void> open_queue = fromStart ? open_from_start
					: open_from_goal;

				final SearchImageStack<DefaultSearchNode> nodes_as_image_this_search = fromStart
					? nodes_as_image_from_start : nodes_as_image_from_goal;
				final SearchImageStack<DefaultSearchNode> nodes_as_image_other_search = fromStart
					? nodes_as_image_from_goal : nodes_as_image_from_start;

				DefaultSearchNode p;

				if (open_queue.size() == 0) continue;

				p = open_queue.deleteMin().getKey();
				if (p == null) continue;

				// Has the route from the start found the goal?
				if (definedGoal && atGoal(p.x, p.y, p.z, fromStart)) {
					SNTUtils.log("Found the goal!");
					if (fromStart) foundGoal(p.asPath(xSep, ySep, zSep,
						spacing_units));
					else foundGoal(p.asPathReversed(xSep, ySep, zSep,
						spacing_units));
					setExitReason(SUCCESS);
					reportFinished(true);
					return;
				}

				if (fromStart) {
					p.searchStatus = CLOSED_FROM_START;
					closed_from_start_count++;
				} else {
					p.searchStatus = CLOSED_FROM_GOAL;
					closed_from_goal_count++;
				}
				//nodes_as_image_this_search.getSlice(p.z).setValueWithoutChecks(p.x, p.y, p);

				// Now look at the neighbours of p. We're going to consider
				// the 26 neighbours in 3D.

				for (int zdiff = -1; zdiff <= 1; zdiff++) {

					final int new_z = p.z + zdiff;
					if (new_z < zMin || new_z > zMax) continue;

					if (nodes_as_image_this_search.getSlice(new_z) == null) {
						nodes_as_image_this_search.newSlice(new_z);
					}

					for (int xdiff = -1; xdiff <= 1; xdiff++)
						for (int ydiff = -1; ydiff <= 1; ydiff++) {

							if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0)) continue;

							final int new_x = p.x + xdiff;
							if (new_x < xMin || new_x > xMax) continue;

							final int new_y = p.y + ydiff;
							if (new_y < yMin || new_y > yMax) continue;

							final double xdiffsq = (xdiff * xSep) * (xdiff * xSep);
							final double ydiffsq = (ydiff * ySep) * (ydiff * ySep);
							final double zdiffsq = (zdiff * zSep) * (zdiff * zSep);

							final double h_for_new_point = estimateCostToGoal(new_x, new_y,
								new_z, fromStart);

							imgPosition[0] = new_x;
							imgPosition[1] = new_y;
							imgPosition[2] = new_z;
							double value_at_new_point = imgAccess.setPositionAndGet(imgPosition).getRealDouble();

							double cost_moving_to_new_point = costFunction.costMovingTo(value_at_new_point);
							if (cost_moving_to_new_point < costFunction.minStepCost()) {
								cost_moving_to_new_point = costFunction.minStepCost();
							}

							final double g_for_new_point = (p.g + Math.sqrt(xdiffsq +
								ydiffsq + zdiffsq) * cost_moving_to_new_point);

							final double f_for_new_point = h_for_new_point + g_for_new_point;

							DefaultSearchNode newNode = createNewNode(new_x, new_y, new_z, g_for_new_point,
									h_for_new_point, p, FREE);

							// Is this newNode really new?
							DefaultSearchNode alreadyThereInThisSearch =
								nodes_as_image_this_search.getSlice(new_z).getValue(newNode.x, newNode.y);

							if (alreadyThereInThisSearch == null) {
								newNode.searchStatus = fromStart ? OPEN_FROM_START
										: OPEN_FROM_GOAL;
								newNode.heapHandle = open_queue.insert(newNode);
								nodes_as_image_this_search.getSlice(new_z).setValue(newNode.x, newNode.y, newNode);
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
										alreadyThereInThisSearch.setFrom(newNode);
										alreadyThereInThisSearch.searchStatus = fromStart
											? OPEN_FROM_START : OPEN_FROM_GOAL;
										alreadyThereInThisSearch.heapHandle.decreaseKey(alreadyThereInThisSearch);
									}
									else if (alreadyThereInThisSearch.searchStatus == (fromStart
										? CLOSED_FROM_START : CLOSED_FROM_GOAL))
									{
										alreadyThereInThisSearch.setFrom(newNode);
										alreadyThereInThisSearch.searchStatus = fromStart
											? OPEN_FROM_START : OPEN_FROM_GOAL;
										alreadyThereInThisSearch.heapHandle = open_queue.insert(alreadyThereInThisSearch);
									}
								}
							}

							if (bidirectional && nodes_as_image_other_search.getSlice(new_z) != null) {

								final DefaultSearchNode alreadyThereInOtherSearch =
									nodes_as_image_other_search.getSlice(new_z).getValue(newNode.x, newNode.y);
								if (alreadyThereInOtherSearch != null) {

									Path result;

									// If either of the next two if conditions
									// are true
									// then we've finished.

									if (alreadyThereInOtherSearch.searchStatus == CLOSED_FROM_START ||
											alreadyThereInOtherSearch.searchStatus == CLOSED_FROM_GOAL)
									{

										if (fromStart) {
											result = p.asPath(xSep, ySep, zSep,
												spacing_units);
											final Path fromGoalReversed = alreadyThereInOtherSearch
												.asPathReversed(xSep, ySep, zSep,
													spacing_units);
											result.add(fromGoalReversed);
										}
										else {
											result = alreadyThereInOtherSearch.asPath(xSep,
													ySep, zSep, spacing_units);
											result.add(p.asPathReversed(xSep, ySep,
													zSep, spacing_units));
										}
										if (verbose) {
											SNTUtils.log("Searches met!");
											SNTUtils.log("Cost for path = "
													+ (newNode.g + alreadyThereInOtherSearch.g));
											SNTUtils.log("Total loops = " + loops);
										}

										foundGoal(result);
										setExitReason(SUCCESS);
										reportFinished(true);
										return;
									}
								}
							}
						}
				}

			}

			/*
			 * If we get to here then we haven't found a route to the point. (With the
			 * current impmlementation this shouldn't happen, so print a warning - probably
			 * the programmer hasn't populated the open list to start with.) However, in
			 * this case let's return the best path so far anyway...
			 */

			if (definedGoal) {
				SNTUtils.log("FAILED to find a route.  Shouldn't happen...");
				setExitReason(POINTS_EXHAUSTED);
				reportFinished(false);
			}

		}
		catch (final OutOfMemoryError oome) {
			SNTUtils.error("Out Of Memory Error", oome);
			setExitReason(OUT_OF_MEMORY);
			reportFinished(false);
		}
		catch (final Throwable t) {
			SNTUtils.error("Exception in search thread " + Thread.currentThread(), t);
		}
	}

	/*
	 * This is the heuristic value for the A* search. There's no defined goal in
	 * this default superclass implementation, so always return 0 so we end up with
	 * Dijkstra's algorithm.
	 */
	double estimateCostToGoal(final int current_x, final int current_y,
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
	public int getExitReason() {
		return exitReason;
	}

	// Add a node, ignoring requests to add duplicate nodes:

	public void addNode(final DefaultSearchNode n, final boolean fromStart) {

		final SearchImageStack<DefaultSearchNode> nodes_as_image = fromStart ? nodes_as_image_from_start
			: nodes_as_image_from_goal;

		SearchImage<DefaultSearchNode> slice = nodes_as_image.getSlice(n.z);
		if (slice == null) {
			slice = nodes_as_image.newSlice(n.z);
		}

		if (slice.getValue(n.x, n.y) != null) {
			// Then there's already a node there:
			return;
		}

		if (n.searchStatus == OPEN_FROM_START) {

			n.heapHandle = open_from_start.insert(n);
			slice.setValue(n.x, n.y, n);
		}
		else if (n.searchStatus == OPEN_FROM_GOAL) {
			assert bidirectional && definedGoal;

			n.heapHandle = open_from_goal.insert(n);
			slice.setValue(n.x, n.y, n);

		}
		else if (n.searchStatus == CLOSED_FROM_START) {

			slice.setValue(n.x, n.y, n);
		}
		else if (n.searchStatus == CLOSED_FROM_GOAL) {
			assert bidirectional && definedGoal;

			slice.setValue(n.x, n.y, n);

		} else {
			throw new IllegalArgumentException("BUG: Unknown status for SearchNode: " + n.searchStatus);
		}

	}

	public SearchImageStack<DefaultSearchNode> getNodesAsImageFromStart() {
		return nodes_as_image_from_start;
	}

	public SearchImageStack<DefaultSearchNode> getNodesAsImageFromGoal() {
		return nodes_as_image_from_goal;
	}

}
