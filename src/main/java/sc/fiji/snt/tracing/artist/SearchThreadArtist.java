/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import sc.fiji.snt.tracing.DefaultSearchNode;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.tracing.SearchThread;
import sc.fiji.snt.tracing.image.SearchImage;

import java.awt.*;

public class SearchThreadArtist implements SearchArtist {

    private final SearchThread search;
    private Color openColor;
    private Color closedColor;
    private double drawingThreshold = -1;

    public SearchThreadArtist(final SearchThread search, final Color openColor, final Color closedColor) {
        this.search = search;
        this.openColor = openColor;
        this.closedColor = closedColor;
    }

    public void setOpenColor(final Color color) {
        this.openColor = color;
    }

    public void setClosedColor(final Color color) {
        this.closedColor = color;
    }

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

            final byte start_status = (i == 0) ? SearchThread.OPEN_FROM_START : SearchThread.CLOSED_FROM_START;
            final byte goal_status = (i == 0) ? SearchThread.OPEN_FROM_GOAL : SearchThread.CLOSED_FROM_GOAL;
            final Color c = (i == 0) ? openColor : closedColor;
            if (c == null) continue;

            g.setColor(c);

            int pixel_size = (int) canvas.getMagnification();
            if (pixel_size < 1) pixel_size = 1;

            if (plane == MultiDThreePanes.XY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final DefaultSearchNode n = anyNodeUnderThreshold(x, y, currentSliceInPlane, drawingThreshold);
                        if (n == null) continue;
                        final byte status = n.searchStatus;
                        if (status == start_status || status == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(y) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.XZ_PLANE) {
                for (int z = 0; z < search.imgDepth; ++z)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final DefaultSearchNode n = anyNodeUnderThreshold(x, currentSliceInPlane, z, drawingThreshold);
                        if (n == null) continue;
                        final byte status = n.searchStatus;
                        if (status == start_status || status == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(z) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.ZY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int z = 0; z < search.imgDepth; ++z) {
                        final DefaultSearchNode n = anyNodeUnderThreshold(currentSliceInPlane, y, z, drawingThreshold);
                        if (n == null) continue;
                        final byte status = n.searchStatus;
                        if (status == start_status || status == goal_status) g.fillRect(
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

    public DefaultSearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
                                                   final double threshold)
    {
        SearchImage<DefaultSearchNode> startSlice = null;
        try {
            startSlice = search.getNodesAsImageFromStart().getSlice(z);
        } catch (ArrayIndexOutOfBoundsException e) {
            // ignored
        }
        SearchImage<DefaultSearchNode> goalSlice = null;
        if (search.getNodesAsImageFromGoal() != null)
            try {
                goalSlice = search.getNodesAsImageFromGoal().getSlice(z);
            } catch (ArrayIndexOutOfBoundsException e) {
                // ignored
            }
        DefaultSearchNode n = null;
        if (startSlice != null) {
            try{
                n = startSlice.getValue(x, y);
            } catch (ArrayIndexOutOfBoundsException e) {
                // FIXME: This only occurs with MapSearchImage
                //  possibly a synchronization issue going on...
                return null;
            }
            if (n != null && threshold >= 0 && n.g > threshold) n = null;
            if (n == null && goalSlice != null) {
                try {
                    n = goalSlice.getValue(x, y);
                } catch (ArrayIndexOutOfBoundsException e) {
                    return null;
                }

                if (threshold >= 0 && n.g > threshold) n = null;
            }
        }
        return n;
    }

}
