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

package sc.fiji.snt.tracing.heuristic;

/**
 * @author Cameron Arshadi
 */
public class Dijkstra implements Heuristic {

    /**
     * Since Dijkstra's algorithm is equivalent to an A* search where the heuristic function h(x) = 0, return 0.
     *
     * @return 0
     */
    @Override
    public double estimateCostToGoal(int current_x, int current_y, int current_z, int goal_x, int goal_y, int goal_z)
    {
        return 0;
    }

}
