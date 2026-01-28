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

package sc.fiji.snt;

import java.util.ArrayList;

import org.scijava.app.StatusService;

/**
 * An implementation of the MultiTaskProgress interface that updates the
 * {@link StatusService} progress bar
 */
class FittingProgress implements MultiTaskProgress {

	int totalTasks;
	double progress;
	private final StatusService statusService;
	private final ArrayList<Double> tasksProportionsDone;
	private final SNTUI sntui;
	private final String msgStatus;

	public FittingProgress(final SNTUI sntui, final StatusService statusService,
		final int totalTasks)
	{
		this.statusService = statusService;
		this.sntui = sntui;
		msgStatus = "Fitting " + totalTasks + " paths... ";
		tasksProportionsDone = new ArrayList<>();
		this.totalTasks = totalTasks;
		for (int i = 0; i < totalTasks; ++i)
			tasksProportionsDone.add(0.0);
	}

	@Override
	synchronized public void updateProgress(final double proportion,
		final int taskIndex)
	{
		tasksProportionsDone.set(taskIndex, proportion);
		updateStatus();
	}

	protected void updateStatus() {
		final double totalDone = tasksProportionsDone.stream().mapToDouble(f -> f).sum();
		statusService.showStatus((int) totalDone, totalTasks, msgStatus);
		sntui.showStatus(msgStatus + SNTUtils.formatDouble(100 * totalDone / totalTasks, 1) + "%", false);
	}

	protected double getProgress() {
		return progress;
	}

	@Override
	public void done() {
		statusService.clearStatus();
		statusService.showProgress(0, 0);
		sntui.showStatus(null, false);
	}
}
