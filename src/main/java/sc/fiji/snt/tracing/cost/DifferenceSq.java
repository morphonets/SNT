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

/**
 *
 * @author Cameron Arshadi
 */
public class DifferenceSq extends Difference {

    public static final double MIN_COST_PER_UNIT_DISTANCE = 1;

    public DifferenceSq(final double min, final double max) {
        super(min, max);
    }

    @Override
    public double costMovingTo(double valueAtNewPoint) {
        return Math.pow(super.costMovingTo(valueAtNewPoint), 2);
    }

    @Override
    public double minStepCost() {
        return MIN_COST_PER_UNIT_DISTANCE;
    }

}
