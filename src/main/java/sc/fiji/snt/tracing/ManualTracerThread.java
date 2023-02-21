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
		if (goal_x > plugin.getWidth()|| goal_y > plugin.getHeight() || goal_z > plugin.getDepth())
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
