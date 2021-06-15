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
 * Uses the reciprocal of voxel intensity, rescaled to the interval (double precision)
 * 255.0 * (intensity - stackMin) / (stackMax - stackMin)
 * to compute the cost of moving to a neighbor node.
 */
public class ReciprocalCost implements SearchCost {

    // For 0 intensity, use half the smallest possible rescaled value (assuming we don't allow DoubleType)
    static final double RECIPROCAL_FUDGE = (255 * 0.5 * (double)Float.MIN_VALUE) / (double)Float.MAX_VALUE;
    static final double MIN_COST_PER_UNIT_DISTANCE = 1 / 255.0;

    private final double stackMin;
    private final double stackMax;

    public ReciprocalCost(final double stackMin, final double stackMax) {
        this.stackMin = stackMin;
        this.stackMax = stackMax;
    }

    @Override
    public double costMovingTo(double valueAtNewPoint) {
        valueAtNewPoint = 255.0 * (valueAtNewPoint - stackMin) / (stackMax - stackMin);
        if (valueAtNewPoint <= 0) {
            valueAtNewPoint = RECIPROCAL_FUDGE;
        } else if (valueAtNewPoint > 255) {
            valueAtNewPoint = 255;
        }
        return 1.0 / valueAtNewPoint;
    }

    @Override
    public double minStepCost() {
        return MIN_COST_PER_UNIT_DISTANCE;
    }

}
