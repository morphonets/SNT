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

package sc.fiji.snt.tracing.artist;

import sc.fiji.snt.TracerCanvas;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.tracing.BiSearch;
import sc.fiji.snt.tracing.BiSearchNode;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.tracing.image.SearchImage;

import java.awt.*;

/**
 * An artist implementation that visualizes bidirectional search operations.
 * <p>
 * This class is responsible for rendering the visual representation of bidirectional
 * search processes, typically showing:
 * </p>
 * <ul>
 *   <li>Forward search progress from the start point</li>
 *   <li>Backward search progress from the target point</li>
 *   <li>Search frontiers and explored regions</li>
 *   <li>Meeting points when the bidirectional paths connect</li>
 * </ul>
 */
public class BiSearchArtist implements SearchArtist {

    private final BiSearch search;
    private Color openColor;
    private Color closedColor;
    private double drawingThreshold = -1;

    /**
     * Constructs a new BiSearchArtist with the specified search and colors.
     *
     * @param search the BiSearch instance to visualize
     * @param openColor the color for open nodes
     * @param closedColor the color for closed nodes
     */
    public BiSearchArtist(final BiSearch search, final Color openColor, final Color closedColor) {
        this.search = search;
        this.openColor = openColor;
        this.closedColor = closedColor;
    }

    /**
     * Sets the color for open nodes.
     *
     * @param color the color to set
     */
    public void setOpenColor(final Color color) {
        this.openColor = color;
    }

    /**
     * Sets the color for closed nodes.
     *
     * @param color the color to set
     */
    public void setClosedColor(final Color color) {
        this.closedColor = color;
    }

    /**
     * Sets the drawing threshold for visualization.
     *
     * @param threshold the threshold value
     */
    public void setDrawingThreshold(final double threshold) {
        this.drawingThreshold =  threshold;
    }

    /*
     * This draws over the Graphics object the current progress of the search at
     * this slice. If openColor or closedColor are null then that means
     * "don't bother to draw that list".
     */
    @Override
    public void drawProgressOnSlice(final int plane,
                                    final int currentSliceInPlane, final TracerCanvas canvas, final Graphics g)
    {

        for (int i = 0; i < 2; ++i) {

            /*
             * The first time through we draw the nodes in the open list, the second time
             * through we draw the nodes in the closed list.
             */

            final BiSearchNode.State start_status = (i == 0) ? BiSearchNode.State.OPEN :
                    BiSearchNode.State.CLOSED;
            final BiSearchNode.State goal_status = (i == 0) ? BiSearchNode.State.OPEN :
                    BiSearchNode.State.CLOSED;
            final Color c = (i == 0) ? openColor : closedColor;
            if (c == null) continue;

            g.setColor(c);

            int pixel_size = (int) canvas.getMagnification();
            if (pixel_size < 1) pixel_size = 1;

            if (plane == MultiDThreePanes.XY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final BiSearchNode n = anyNodeUnderThreshold(x, y, currentSliceInPlane, drawingThreshold);
                        if (n == null) continue;
                        if (n.getStateFromStart() == start_status || n.getStateFromGoal() == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(y) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.XZ_PLANE) {
                for (int z = 0; z < search.imgDepth; ++z)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final BiSearchNode n = anyNodeUnderThreshold(x, currentSliceInPlane, z, drawingThreshold);
                        if (n == null) continue;
                        if (n.getStateFromStart() == start_status || n.getStateFromGoal() == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(z) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.ZY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int z = 0; z < search.imgDepth; ++z) {
                        final BiSearchNode n = anyNodeUnderThreshold(currentSliceInPlane, y, z, drawingThreshold);
                        if (n == null) continue;
                        if (n.getStateFromStart() == start_status || n.getStateFromGoal() == goal_status) g.fillRect(
                                canvas.myScreenX(z) - pixel_size / 2, canvas.myScreenY(y) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
        }
    }

    @Override
    public SearchInterface getSearch() {
        return search;
    }

    public BiSearchNode anyNodeUnderThreshold(final int x, final int y, final int z, final double threshold) {
        final SearchImage<BiSearchNode> slice;
        try {
             slice = search.getNodesAsImage().getSlice(z);

        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
        if (slice == null) {
            return null;
        }
        try {
            BiSearchNode n = slice.getValue(x, y);
            if ( n == null || threshold >= 0 && n.getGFromGoal() > threshold && n.getGFromStart() > threshold )
            {
                return null;
            }
            return n;

        } catch (ArrayIndexOutOfBoundsException e) {
            // FIXME: This only occurs with MapSearchImage
            //  possibly a synchronization issue going on...
        }
        return null;
    }


}
