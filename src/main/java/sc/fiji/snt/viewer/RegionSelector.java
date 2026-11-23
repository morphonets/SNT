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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import java.util.function.Consumer;

import sc.fiji.snt.util.BoundingBox;

/**
 * Interactive selection of a 3D region in a big data viewer. Implementations are
 * viewer specific, but all report the selection as a {@link BoundingBox} in the
 * viewer's world (calibrated) units, i.e., the same frame used by
 * {@link AbstractBigViewer#getSceneCenter()} and by path coordinates.
 *
 * @author Tiago Ferreira
 */
public interface RegionSelector {

	/**
	 * Prompts the user to select a region. Must be called on the EDT. The call
	 * does not block: the result is delivered through the callback, also on the
	 * EDT.
	 *
	 * @param initial the region to start from. If null, a default region is
	 *                derived from the current view
	 * @param onDone  receives the selected region, or null if the selection was
	 *                canceled or could not be performed
	 */
	void select(BoundingBox initial, Consumer<BoundingBox> onDone);

	/**
	 * Gets a selector for the specified viewer
	 *
	 * @param viewer the viewer
	 * @return a selector, or null if the viewer type does not support interactive
	 *         region selection (currently only {@link Bdv} does)
	 */
	static RegionSelector of(final AbstractBigViewer viewer) {
		return (viewer instanceof Bdv bdv && bdv.isOpen()) ? new BdvRegionSelector(bdv) : null;
	}

}
