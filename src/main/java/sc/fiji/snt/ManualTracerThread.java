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

import java.awt.Graphics;
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
	private final ArrayList<SearchProgressCallback> progListeners =
		new ArrayList<>();
	private volatile int threadStatus = SearchThread.PAUSED;
	private Path result;

	public ManualTracerThread(final SNT plugin,
		final double start_x, final double start_y, final double start_z,
		final double goal_x, final double goal_y, final double goal_z)
	{
		if (goal_x > plugin.width || goal_y > plugin.width || goal_z > plugin.depth)
			throw new IllegalArgumentException("Out-of bounds goal");
		this.start_x = start_x * plugin.x_spacing;
		this.start_y = start_y * plugin.y_spacing;
		this.start_z = start_z * plugin.z_spacing;
		this.goal_x = goal_x * plugin.x_spacing;
		this.goal_y = goal_y * plugin.y_spacing;
		this.goal_z = goal_z * plugin.z_spacing;
		this.plugin = plugin;
	}

	@Override
	public void run() {
		result = new Path(plugin.x_spacing, plugin.y_spacing, plugin.z_spacing,
			plugin.spacing_units);
		result.setCTposition(plugin.channel, plugin.frame);
		result.addPointDouble(start_x, start_y, start_z);
		result.addPointDouble(goal_x, goal_y, goal_z);
		threadStatus = SearchThread.SUCCESS;
		for (final SearchProgressCallback progress : progListeners)
			progress.finished(this, true);
	}

	@Override
	public Path getResult() {
		return result;
	}

	@Override
	public void drawProgressOnSlice(final int plane,
		final int currentSliceInPlane, final TracerCanvas canvas, final Graphics g)
	{
		// do nothing.
	}

	public int getThreadStatus() {
		return threadStatus;
	}

	public void requestStop() {
		synchronized (this) {
			if (threadStatus == SearchThread.PAUSED) this.interrupt();
			threadStatus = SearchThread.STOPPING;
			reportThreadStatus();
		}
	}

	public void addProgressListener(final SearchProgressCallback callback) {
		progListeners.add(callback);
	}

	public void reportThreadStatus() {
		for (final SearchProgressCallback progress : progListeners)
			progress.threadStatus(this, threadStatus);
	}


}
