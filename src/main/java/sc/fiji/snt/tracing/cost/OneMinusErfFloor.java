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

package sc.fiji.snt.tracing.cost;

import sc.fiji.snt.SNTUtils;

/**
 * {@link OneMinusErf} with a small constant added to every step cost. The
 * floor keeps path length in the objective when the erfc term vanishes on
 * bright voxels, which discourages lateral jitter inside neurites and
 * zig-zagging across dim gaps.
 */
public class OneMinusErfFloor extends OneMinusErf {

    /** Default additive floor per unit step */
    public static final double DEFAULT_FLOOR = 1e-3;

    private final double floor;

    public OneMinusErfFloor(final double max, final double average, final double standardDeviation) {
        this(max, average, standardDeviation, DEFAULT_FLOOR);
    }

    public OneMinusErfFloor(final double max, final double average, final double standardDeviation,
                            final double floor) {
        super(max, average, standardDeviation);
        this.floor = floor;
        SNTUtils.log("min step cost (with floor " + floor + ") = " + minStepCost());
    }

    @Override
    public double costMovingTo(final double valueAtNewPoint) {
        return super.costMovingTo(valueAtNewPoint) + floor;
    }

    @Override
    public double minStepCost() {
        return super.minStepCost() + floor;
    }

    public double getFloor() {
        return floor;
    }

}
