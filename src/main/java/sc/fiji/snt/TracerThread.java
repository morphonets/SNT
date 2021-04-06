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

import features.ComputeCurvatures;
import ij.ImagePlus;

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

	private final boolean reciprocal;
	private final ComputeCurvatures hessian;
	private final double multiplier;
	private final float[][] cachedTubeness;
	private final boolean useHessian;

	private final boolean singleSlice;

	private Path result;


	public TracerThread(final SNT snt, final int start_x, final int start_y,
	                    final int start_z, final int goal_x, final int goal_y, final int goal_z) {
		super(snt);
		reciprocal = true;
		singleSlice = snt.is2D();
		useHessian = snt.isHessianEnabled((snt.isTracingOnSecondaryImageActive())?"secondary":"primary");
		if (useHessian) {
			final boolean secondary = snt.isTracingOnSecondaryImageActive();
			hessian = (secondary) ? snt.secondaryHessian.hessian : snt.primaryHessian.hessian;
			if (hessian == null) {
				throw new IllegalArgumentException("Hessian enabled but settings are invalid.");
			}
			multiplier = (secondary) ? snt.secondaryHessian.getMultiplier() : snt.primaryHessian.getMultiplier();
			cachedTubeness = (secondary) ? snt.secondaryHessian.cachedTubeness : snt.primaryHessian.cachedTubeness;
		} else {
			hessian = null;
			multiplier = HessianCaller.DEFAULT_MULTIPLIER;
			cachedTubeness = null;
		}
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	public TracerThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final int timeoutSeconds,
		final long reportEveryMilliseconds, final int start_x, final int start_y,
		final int start_z, final int goal_x, final int goal_y, final int goal_z,
		final boolean reciprocal, final boolean singleSlice,
		final ComputeCurvatures hessian, final double multiplier,
		final float[][] cachedTubeness, final boolean useHessian)
	{
		super(imagePlus, stackMin, stackMax, true, // bidirectional
			true, // definedGoal
			timeoutSeconds, reportEveryMilliseconds);

		this.reciprocal = reciprocal;
		this.singleSlice = singleSlice;
		this.hessian = hessian;
		this.cachedTubeness = cachedTubeness;
		this.multiplier = multiplier;
		this.useHessian = useHessian;
		init(start_x, start_y, start_z, goal_x, goal_y, goal_z);
	}

	private void init(final int start_x, final int start_y, final int start_z, final int goal_x, final int goal_y,
			final int goal_z) {
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

	/* If you specify 0 for timeoutSeconds then there is no timeout. */
	public TracerThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final int start_x, final int start_y,
		final int start_z, final int goal_x, final int goal_y, final int goal_z,
		final boolean reciprocal, final boolean singleSlice,
		final ComputeCurvatures hessian, final double multiplier)
	{
		this(imagePlus, stackMin, stackMax, 0, 1000, start_x, start_y, start_z,
			goal_x, goal_y, goal_z, reciprocal, singleSlice, hessian,
			multiplier, null, true);
	}

	@Override
	protected boolean atGoal(final int x, final int y, final int z,
		final boolean fromStart)
	{
		if (fromStart) return (x == goal_x) && (y == goal_y) && (z == goal_z);
		else return (x == start_x) && (y == start_y) && (z == start_z);
	}

	@Override
	protected double minimumCostPerUnitDistance() {

		double minimum_cost;

		if (hessian == null) {

			minimum_cost = reciprocal ? (1 / 255.0) : 1;

		}
		else {

			// result = 1E-4; /* for the ratio of e0/e1 */
			// result = 0.002; // 1; /* for e1 - e0 */

			minimum_cost = 1 / 60.0;
		}

		return minimum_cost;
	}

	@Override
	protected void foundGoal(final Path pathToGoal) {
		result = pathToGoal;
	}

	@Override
	public Path getResult() {
		if (result != null && minExpectedSize > 0 && result.size() < minExpectedSize) {
			SNTUtils.log("Result size: " + result.size() + ", Min expected size: " + minExpectedSize);
			return null;
		}
		return result;
	}

	/*
	 * If we're taking the reciprocal of the value at the new point as our cost,
	 * then values of zero cause a problem. This is the value that we use instead of
	 * zero there.
	 */

	static final double RECIPROCAL_FUDGE = 0.5;

	/*
	 * This cost doesn't take into account the distance between the points - it will
	 * be post-multiplied by that value.
	 *
	 * The minimum cost should be > 0 - it is the value that is used in calculating
	 * the heuristic for how far a given point is from the goal.
	 */

	@Override
	protected double costMovingTo(final int new_x, final int new_y,
		final int new_z)
	{

		double cost;
		if (useHessian) {

			if (cachedTubeness == null) {

				if (singleSlice) {

					final double[] hessianEigenValues = new double[2];

					final boolean real = hessian.hessianEigenvaluesAtPoint2D(new_x, new_y,
							true, hessianEigenValues, false, true, (float)x_spacing, (float)y_spacing);

					// Just use the absolute value
					// of the largest eigenvalue
					// (if it's < 0)

					if (real && (hessianEigenValues[1] < 0)) {

						double measure = Math.abs(hessianEigenValues[1]);
						if (measure == 0) // This should never happen in
							// practice...
							measure = 0.2;

						measure *= multiplier;
						if (measure > 256) measure = 256;
						cost = 1 / measure;
					}
					else {
						cost = 1 / 0.2;
					}
					return cost;

				}
				else {

					final double[] hessianEigenValues = new double[3];

					final boolean real = hessian.hessianEigenvaluesAtPoint3D(new_x, new_y,
							new_z, true, hessianEigenValues, false, true,
							(float)x_spacing, (float)y_spacing, (float)z_spacing);

					/*
					 * FIXME: there's lots of literature on how to pick this rule (see Sato et al,
					 * "Three-dimensional multi-scale line filter for segmentation and visualization
					 * of curvilinear structures in medical images". The rule I'm using here
					 * probably isn't optimal.
					 */

					final double e1 = hessianEigenValues[1];
					final double e2 = hessianEigenValues[2];

					if (real && (e1 < 0) && (e2 < 0)) {

						double measure = Math.sqrt(e1 * e2);

						if (measure == 0) // This should never happen in
							// practice...
							measure = 0.2;

						measure *= multiplier;
						if (measure > 256) measure = 256;

						cost = 1 / measure;

					}
					else {

						cost = 1 / 0.2;

					}

				}

			}
			else {

				// Then this saves a lot of time:
				float measure = cachedTubeness[new_z][new_y * width + new_x];
				if (measure == 0) measure = 0.2f;
				cost = 1 / measure;

			}

		}
		else {

			final double value_at_new_point = getValueAtNewPoint(new_x, new_y, new_z);
			if (reciprocal) {
				cost = 1 / RECIPROCAL_FUDGE;
				if (value_at_new_point != 0) cost = 1.0 / value_at_new_point;
			}
			else {
				cost = 256 - value_at_new_point;
			}

		}

		return cost;
	}

	private double getValueAtNewPoint(final int new_x, final int new_y, final int new_z) {
		double value_at_new_point = -1;
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				value_at_new_point = slices_data_b[new_z][new_y * width + new_x] & 0xFF;
				break;
			case ImagePlus.GRAY16: {
				value_at_new_point = slices_data_s[new_z][new_y * width + new_x];
				break;
			}
			case ImagePlus.GRAY32: {
				value_at_new_point = slices_data_f[new_z][new_y * width + new_x];
				break;
			}
		}
		return 255.0 * (value_at_new_point - stackMin) / (stackMax - stackMin);
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

}
