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
import net.imagej.Dataset;
import net.imagej.axis.Axes;

/**
 * A* search heuristic using euclidean distance from current node to goal node
 */
public class Euclidean implements Heuristic {

    private final Calibration calibration;

    public Euclidean(final Calibration calibration) {
        this.calibration = calibration;
    }

    public Euclidean(final Dataset dataset) {
        final Calibration cal = new Calibration();
        cal.pixelWidth = dataset.averageScale(0);
        cal.pixelHeight = dataset.averageScale(1);
        cal.pixelDepth = dataset.dimensionIndex(Axes.Z) > -1 ? dataset.averageScale(dataset.dimensionIndex(Axes.Z)) : 1.0;
        this.calibration = cal;
    }

    @Override
    public double estimateCostToGoal(final int source_x, final int source_y, final int source_z,
                                        final int target_x, final int target_y, final int target_z)
    {
        final double xdiff = (target_x - source_x) * calibration.pixelWidth;
        final double ydiff = (target_y - source_y) * calibration.pixelHeight;
        final double zdiff = (target_z - source_z) * calibration.pixelDepth;

        return Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
    }

}
