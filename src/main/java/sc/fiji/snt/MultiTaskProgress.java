/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

interface MultiTaskProgress {

	/**
	 * Updates the progress of a specific task.
	 *
	 * @param proportionDone the proportion of the task completed (0.0 to 1.0)
	 * @param taskIndex the index of the task being updated
	 */
	public void updateProgress(double proportionDone, int taskIndex);

	/**
	 * Called when all tasks are completed.
	 */
	public void done();

}
