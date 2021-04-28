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
 * TODO
 */
public class TubenessCost implements SearchCost {

    static final double MINIMUM_COST_PER_UNIT_DISTANCE = 1 / 60.0;

    HessianAnalyzer hessian;
    double multiplier;
    AbstractSearch search;

    public TubenessCost(HessianAnalyzer hessian, double multiplier) {
        this.hessian = hessian;
        this.multiplier = multiplier;
    }

    @Override
    public double costMovingTo(int new_x, int new_y, int new_z) {
        double measure;
        if (!hessian.is3D) {
            measure = hessian.tubenessAccess.setPositionAndGet(new_x, new_y).getRealDouble();
        } else {
            measure = hessian.tubenessAccess.setPositionAndGet(new_x, new_y, new_z).getRealDouble();
        }
        if (measure == 0) {
            measure = 0.2;
        }
        measure *= multiplier;
        if (measure > 256) measure = 256;
        return 1 / measure;
    }

    @Override
    public double minimumCostPerUnitDistance() {
        return MINIMUM_COST_PER_UNIT_DISTANCE;
    }

    @Override
    public void setSearch(AbstractSearch search) {
        this.search = search;
    }

}
