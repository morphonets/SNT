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

package sc.fiji.snt.tracing.heuristic;

import ij.measure.Calibration;
import sc.fiji.snt.tracing.SearchInterface;

/**
 * Interface for heuristic estimates to the goal used by classes implementing {@link SearchInterface}.
 * This useful for heuristic-informed searches like A*.
 *
 * @author Cameron Arshadi
 */
public interface SearchHeuristic {

    void setCalibration(Calibration calibration);

    double estimateCostToGoal(int current_x, int current_y, int current_z, int goal_x, int goal_y, int goal_z);

}
