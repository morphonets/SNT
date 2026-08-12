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

package sc.fiji.snt.tracing;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SearchProgressCallback;

import java.util.ArrayList;

/**
 * A tracer thread for manual tracing.
 * 
 * @author Tiago Ferreira
 */
public class ManualTracerThread extends Thread implements SearchInterface {

	private final double start_x;
	private final double start_y;
	private final double start_z;
	private final double goal_x;
	private final double goal_y;
	private final double goal_z;
	private final SNT plugin;
	private final ArrayList<SearchProgressCallback> progListeners = new ArrayList<>();
	private Path result;

	public ManualTracerThread(final SNT plugin,
		final double start_x, final double start_y, final double start_z,
		final double goal_x, final double goal_y, final double goal_z)
	{
		this(plugin, start_x, start_y, start_z, goal_x, goal_y, goal_z,
				plugin.getWidth(), plugin.getHeight(), plugin.getDepth());
	}

	/**
	 * Variant that checks the goal against explicit bounds instead of {@code plugin}'s own current
	 * {@code getWidth()}/{@code getHeight()}/{@code getDepth()}. Needed by headless, crop-independent
	 * callers (see {@code SNT#manualTraceHeadless}, used by BDV/BVV's manual-trace toggle) whose pixel
	 * indices are expressed in a grid that is not necessarily {@code plugin}'s own current canvas grid
	 * (e.g. the full streamed source, while a smaller crop is materialized on the classic canvas) -
	 * checking against {@code plugin}'s own, possibly smaller, current dimensions would then wrongly
	 * reject a perfectly valid goal.
	 *
	 * @param boundsWidth the width of the grid start_x/goal_x are expressed in, or 0 if unknown
	 * @param boundsHeight the height of the grid start_y/goal_y are expressed in, or 0 if unknown
	 * @param boundsDepth the depth of the grid start_z/goal_z are expressed in, or 0 if unknown
	 */
	public ManualTracerThread(final SNT plugin,
		final double start_x, final double start_y, final double start_z,
		final double goal_x, final double goal_y, final double goal_z,
		final long boundsWidth, final long boundsHeight, final long boundsDepth)
	{
		// NB: bounds are 0 when unknown (e.g., SNT started against a BigDataViewer/SpimData source without
		// pixel-level metadata). That is "unknown bounds", not a 0-size volume, so the check is skipped in
		// that case rather than rejecting every goal
		final boolean boundsKnown = boundsWidth > 0 && boundsHeight > 0 && boundsDepth > 0;
		if (boundsKnown && (goal_x > boundsWidth || goal_y > boundsHeight || goal_z > boundsDepth))
			throw new IllegalArgumentException("Out-of bounds goal");
		this.start_x = start_x * plugin.getPixelWidth();
		this.start_y = start_y * plugin.getPixelHeight();
		this.start_z = start_z * plugin.getPixelDepth();
		this.goal_x = goal_x * plugin.getPixelWidth();
		this.goal_y = goal_y * plugin.getPixelHeight();
		this.goal_z = goal_z * plugin.getPixelDepth();
		this.plugin = plugin;
	}

	@Override
	public void run() {
		result = new Path(plugin.getPixelWidth(), plugin.getPixelHeight(), plugin.getPixelDepth(),
			plugin.getSpacingUnits());
		result.setCTposition(plugin.getChannel(), plugin.getFrame());
		result.addPointDouble(start_x, start_y, start_z);
		result.addPointDouble(goal_x, goal_y, goal_z);
		for (final SearchProgressCallback progress : progListeners)
			progress.finished(this, true);
	}

	@Override
	public Path getResult() {
		return result;
	}

	public void addProgressListener(final SearchProgressCallback callback) {
		progListeners.add(callback);
	}



}
