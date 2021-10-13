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

package sc.fiji.snt.tracing;

import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.tracing.cost.Cost;
import sc.fiji.snt.tracing.cost.Reciprocal;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;

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

	private final Heuristic heuristic;

	private Path result;

	public TracerThread(final SNT snt, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z)
	{
		this(snt, snt.getLoadedData(), start_x, start_y, start_z, goal_x, goal_y, goal_z,
				new Reciprocal(snt.getStats().min, snt.getStats().max),
				new Euclidean(snt.getImagePlus().getCalibration()));
	}

	public TracerThread(final SNT snt, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z,
						Cost costFunction, Heuristic heuristic)
	{
		this(snt, snt.getLoadedData(), start_x, start_y, start_z, goal_x, goal_y, goal_z,
				costFunction, heuristic);
	}

	public TracerThread(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image,
                        final int start_x, final int start_y, final int start_z,
                        final int goal_x, final int goal_y, final int goal_z,
                        Cost costFunction, Heuristic heuristic)
	{
		super(snt, image, costFunction);
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	public TracerThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
                        final int start_x, final int start_y, final int start_z,
                        final int goal_x, final int goal_y, final int goal_z,
                        final int timeoutSeconds, final long reportEveryMilliseconds,
                        final SNT.SearchImageType searchImageType,
                        Cost costFunction, Heuristic heuristic)
	{
		super(image, calibration, true, true, timeoutSeconds, reportEveryMilliseconds,
				searchImageType, costFunction);
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	public TracerThread(final Dataset dataset, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z, final int timeoutSeconds,
						final long reportEveryMilliseconds, final SNT.SearchImageType searchImageType,
						Cost costFunction, Heuristic heuristic)
	{
		super(dataset, true, true, timeoutSeconds, reportEveryMilliseconds, searchImageType,
			  costFunction);
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	public TracerThread(final Dataset dataset, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z, final int timeoutSeconds,
						final long reportEveryMilliseconds, Cost costFunction, Heuristic heuristic)
	{
		super(dataset, true, true, timeoutSeconds, reportEveryMilliseconds, SNT.SearchImageType.MAP,
			  costFunction);
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	public TracerThread(final Dataset dataset, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z, Cost costFunction, Heuristic heuristic)
	{
		super(dataset, true, true, 0, 1000, SNT.SearchImageType.MAP,
			  costFunction);
		this.heuristic = heuristic;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	public TracerThread(final Dataset dataset, final int start_x, final int start_y, final int start_z,
						final int goal_x, final int goal_y, final int goal_z, Cost costFunction)
	{
		super(dataset, true, true, 0, 1000, SNT.SearchImageType.MAP,
			  costFunction);
		this.heuristic = new Euclidean(dataset);
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	private void init(final int start_x, final int start_y, final int start_z, final int goal_x, final int goal_y,
					  final int goal_z)
	{
		this.start_x = start_x;
		this.start_y = start_y;
		this.start_z = start_z;
		this.goal_x = goal_x;
		this.goal_y = goal_y;
		this.goal_z = goal_z;
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
		if (fromStart)
			return (x == goal_x) && (y == goal_y) && (z == goal_z);
		else
			return (x == start_x) && (y == start_y) && (z == start_z);
	}

	@Override
	protected void foundGoal(final Path pathToGoal) {
		result = pathToGoal;
	}

	@Override
	public Path getResult() {
		return result;
	}

	@Override
	protected double estimateCostToGoal(final int current_x, final int current_y, final int current_z,
										final boolean fromStart)
	{
		return costFunction.minStepCost() * heuristic.estimateCostToGoal(
				current_x,
				current_y,
				current_z,
				fromStart ? goal_x : start_x,
				fromStart ? goal_y : start_y,
				fromStart ? goal_z : start_z);
	}

}
