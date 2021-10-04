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

package sc.fiji.snt.tracing.cost;

/**
 * Uses the reciprocal of voxel intensity, rescaled to the interval (double precision)
 * 255 * (intensity - min) / (max - min)
 * to compute the cost of moving to a neighbor node.
 */
public class Reciprocal implements Cost {

    // In theory, this value should be smaller than any intensity we can reasonably expect from a 32-bit image,
    //  since a value of 0 should have maximal cost. In practice, setting this any smaller than ~1E-10 leads to
    //  erratic search behavior at light / dark boundaries. 1E-6 appears to behave well over different bit depths and
    //  intensity ranges.
    public static final double RECIPROCAL_FUDGE = 1E-6;
    public static final double CONST_8 = 255d;
    public static final double MIN_COST_PER_UNIT_DISTANCE = 1 / CONST_8;

    private final double min;
    private final double max;

    public Reciprocal(final double min, final double max) {
        this.min = min;
        this.max = max;
    }

    @Override
    public double costMovingTo(double valueAtNewPoint) {
        valueAtNewPoint = CONST_8 * (valueAtNewPoint - min) / (max - min);
        if (valueAtNewPoint <= 0) {
            valueAtNewPoint = RECIPROCAL_FUDGE;
        } else if (valueAtNewPoint > CONST_8) {
            valueAtNewPoint = CONST_8;
        }
        return 1.0 / valueAtNewPoint;
    }

    @Override
    public double minStepCost() {
        return MIN_COST_PER_UNIT_DISTANCE;
    }

}
