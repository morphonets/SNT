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

package sc.fiji.snt.viewer;

import bdv.viewer.OverlayRenderer;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Thin adapter over BDV's {@code ViewerPanel} and BVV's {@code VolumeViewerPanel},
 * exposing only the canvas/overlay operations needed by SNT's overlay renderers.
 *
 * <p>Callers pass a {@link RealPoint} to {@link #getGlobalMouseCoordinates}; both
 * panel types accept {@code RealPoint} so no widening is required.
 *
 * @author Tiago Ferreira
 * @see Bvv#adapt(bvv.core.VolumeViewerPanel)
 * @see Bdv#adapt(bdv.viewer.ViewerPanel)
 */
interface BigViewerPanel {

    /** Width of the display canvas in pixels. */
    int getDisplayWidth();

    /** Height of the display canvas in pixels. */
    int getDisplayHeight();

    /** Registers an overlay renderer with the display canvas. */
    void addOverlay(OverlayRenderer overlay);

    /** Unregisters an overlay renderer from the display canvas. */
    void removeOverlay(OverlayRenderer overlay);

    /** Copies the current world-to-viewer transform into {@code t}. */
    void getViewerTransform(AffineTransform3D t);

    /**
     * Writes the current global mouse position into {@code pos}.
     * Callers must pass a {@link RealPoint} (both panel types require it).
     */
    void getGlobalMouseCoordinates(RealPoint pos);

    /** Requests a repaint of the display. */
    void requestRepaint();
}
