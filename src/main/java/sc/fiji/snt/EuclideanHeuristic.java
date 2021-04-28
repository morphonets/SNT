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

/**
 * A* search heuristic using euclidean distance from current node to goal node
 */
public class EuclideanHeuristic implements SearchHeuristic {

    private double x_spacing;
    private double y_spacing;
    private double z_spacing;

    public EuclideanHeuristic() { }

    @Override
    public void setSearch(AbstractSearch search) {
        this.x_spacing = search.x_spacing;
        this.y_spacing = search.y_spacing;
        this.z_spacing = search.z_spacing;
    }

    @Override
    public double estimateCostToGoal(final int source_x, final int source_y, final int source_z,
                                        final int target_x, final int target_y, final int target_z)
    {
        final double xdiff = (target_x - source_x) * x_spacing;
        final double ydiff = (target_y - source_y) * y_spacing;
        final double zdiff = (target_z - source_z) * z_spacing;

        return Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff *
                zdiff);
    }

}
