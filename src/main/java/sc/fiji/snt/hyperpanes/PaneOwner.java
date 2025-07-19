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

package sc.fiji.snt.hyperpanes;

public interface PaneOwner {

	/**
	 * Called when the mouse is moved to a new position.
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param plane the plane identifier
	 * @param shift_down whether the shift key is pressed
	 */
	public void mouseMovedTo(double x, double y, int plane, boolean shift_down);

	/**
	 * Called when a zoom event occurs.
	 *
	 * @param zoomInEvent true for zoom in, false for zoom out
	 * @param x the x coordinate of the zoom center
	 * @param y the y coordinate of the zoom center
	 * @param sourcePlane the plane where the zoom occurred
	 */
	public void zoomEventOccurred(boolean zoomInEvent, int x, int y,
	                              int sourcePlane);

	/**
	 * Called when a pan event occurs.
	 *
	 * @param x the x coordinate of the pan
	 * @param y the y coordinate of the pan
	 * @param sourcePlane the plane where the pan occurred
	 */
	public void panEventOccurred(int x, int y, int sourcePlane);

	/**
	 * Shows a status message with progress information.
	 *
	 * @param progress the current progress value
	 * @param maximum the maximum progress value
	 * @param message the status message to display
	 */
	public void showStatus(int progress, int maximum, String message);

}
