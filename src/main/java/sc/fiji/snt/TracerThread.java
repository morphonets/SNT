/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import ij.ImagePlus;
import sc.fiji.snt.util.SearchImage;

/**
 * SNT's default tracer thread: explores between two points in an image, doing
 * an A* search with a choice of distance measures.
 */
public class TracerThread extends SearchThread {

	private int start_x;
	private int start_y;
	private int start_z;
	private int goal_x;
	private int goal_y;
	private int goal_z;

	private final SearchHeuristic heuristic;
	private final SearchCost costFunction;

	private Path result;

	public TracerThread(final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z,
						final SNT snt, SearchCost costFunction, SearchHeuristic heuristic)
	{
		// TODO: figure out how to handle secondary image
		super(snt);
		this.costFunction = costFunction;
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	@SuppressWarnings("rawtypes")
	public TracerThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final int timeoutSeconds,
		final long reportEveryMilliseconds, final int start_x, final int start_y,
		final int start_z, final int goal_x, final int goal_y, final int goal_z,
						final Class<? extends SearchImage> searchImageType, SearchCost costFunction,
						SearchHeuristic heuristic)
	{
		super(imagePlus, stackMin, stackMax, true, // bidirectional
			true, // definedGoal
			timeoutSeconds, reportEveryMilliseconds, searchImageType);
		this.costFunction = costFunction;
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	private void init(final int start_x, final int start_y, final int start_z, final int goal_x, final int goal_y,
			final int goal_z) {
		this.costFunction.setSearch(this);
		this.heuristic.setSearch(this);
		this.start_x = start_x;
		this.start_y = start_y;
		this.start_z = start_z;
		this.goal_x = goal_x;
		this.goal_y = goal_y;
		this.goal_z = goal_z;
		// need to do this again since it needs to know if hessian is set...
		minimum_cost_per_unit_distance = minimumCostPerUnitDistance();
		final DefaultSearchNode s = createNewNode(start_x, start_y, start_z, 0,
			estimateCostToGoal(start_x, start_y, start_z, true), null,
			OPEN_FROM_START);
		addNode(s, true);
		final DefaultSearchNode g = createNewNode(goal_x, goal_y, goal_z, 0,
			estimateCostToGoal(goal_x, goal_y, goal_z, false), null, OPEN_FROM_GOAL);
		addNode(g, false);
	}

	@Override
	protected boolean atGoal(final int x, final int y, final int z,
		final boolean fromStart)
	{
		if (fromStart) return (x == goal_x) && (y == goal_y) && (z == goal_z);
		else return (x == start_x) && (y == start_y) && (z == start_z);
	}

	@Override
	protected void foundGoal(final Path pathToGoal) {
		result = pathToGoal;
	}

	@Override
	public Path getResult() {
		return result;
	}

	/*
	 * This cost doesn't take into account the distance between the points - it will
	 * be post-multiplied by that value.
	 *
	 * The minimum cost should be > 0 - it is the value that is used in calculating
	 * the heuristic for how far a given point is from the goal.
	 */

	@Override
	protected double costMovingTo(int new_x, int new_y, int new_z) {
		return this.costFunction.costMovingTo(new_x, new_y, new_z);
	}

	@Override
	protected double estimateCostToGoal(final int current_x, final int current_y,
										final int current_z, final boolean fromStart)
	{

		final double xdiff = ((fromStart ? goal_x : start_x) - current_x) *
				x_spacing;
		final double ydiff = ((fromStart ? goal_y : start_y) - current_y) *
				y_spacing;
		final double zdiff = ((fromStart ? goal_z : start_z) - current_z) *
				z_spacing;

		final double distance = Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff *
				zdiff);

		return (minimum_cost_per_unit_distance * distance);
	}

	@Override
	protected double minimumCostPerUnitDistance() {
		return this.costFunction.minimumCostPerUnitDistance();
	}
}
