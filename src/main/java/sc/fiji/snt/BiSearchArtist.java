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

import sc.fiji.snt.hyperpanes.MultiDThreePanes;

import java.awt.*;


public class BiSearchArtist implements SearchArtist {

    private final BiSearch search;
    private final Color openColor;
    private final Color closedColor;

    public BiSearchArtist(final BiSearch search, final Color openColor, final Color closedColor) {
        this.search = search;
        this.openColor = openColor;
        this.closedColor = closedColor;
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

            final BiSearch.NodeState start_status = (i == 0) ? BiSearch.NodeState.OPEN_FROM_START :
                    BiSearch.NodeState.CLOSED_FROM_START;
            final BiSearch.NodeState goal_status = (i == 0) ? BiSearch.NodeState.OPEN_FROM_GOAL :
                    BiSearch.NodeState.CLOSED_FROM_GOAL;
            final Color c = (i == 0) ? openColor : closedColor;
            if (c == null) continue;

            g.setColor(c);

            int pixel_size = (int) canvas.getMagnification();
            if (pixel_size < 1) pixel_size = 1;

            if (plane == MultiDThreePanes.XY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final BiSearchNode n = search.anyNodeUnderThreshold(x, y, currentSliceInPlane,
                                search.drawingThreshold);
                        if (n == null) continue;
                        if (n.getStateFromStart() == start_status || n.getStateFromGoal() == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(y) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.XZ_PLANE) {
                for (int z = 0; z < search.imgDepth; ++z)
                    for (int x = 0; x < search.imgWidth; ++x) {
                        final BiSearchNode n = search.anyNodeUnderThreshold(x, currentSliceInPlane, z,
                                search.drawingThreshold);
                        if (n == null) continue;
                        if (n.getStateFromStart() == start_status || n.getStateFromGoal() == goal_status) g.fillRect(
                                canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(z) -
                                        pixel_size / 2, pixel_size, pixel_size);
                    }
            }
            else if (plane == MultiDThreePanes.ZY_PLANE) {
                for (int y = 0; y < search.imgHeight; ++y)
                    for (int z = 0; z < search.imgDepth; ++z) {
                        final BiSearchNode n = search.anyNodeUnderThreshold(currentSliceInPlane, y, z,
                                search.drawingThreshold);
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


}
