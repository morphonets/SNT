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

import ij.ImagePlus;

/**
 * Uses the reciprocal of voxel intensity to compute the cost of moving to a neighbor node.
 */
public class ReciprocalCost implements SearchCost {

    static final double RECIPROCAL_FUDGE = 0.5;
    static final double MINIMUM_COST_PER_UNIT_DISTANCE = 1 / 255.0;
    AbstractSearch search;

    public ReciprocalCost() { }

    private double getValueAtNewPoint(final int new_x, final int new_y, final int new_z) {
        double value_at_new_point = -1;
        switch (search.imageType) {
            case ImagePlus.GRAY8:
            case ImagePlus.COLOR_256:
                value_at_new_point = search.slices_data_b[new_z][new_y * search.width + new_x] & 0xFF;
                break;
            case ImagePlus.GRAY16: {
                value_at_new_point = search.slices_data_s[new_z][new_y * search.width + new_x];
                value_at_new_point = 255.0 * (value_at_new_point - search.stackMin) / (search.stackMax - search.stackMin);
                break;
            }
            case ImagePlus.GRAY32: {
                value_at_new_point = search.slices_data_f[new_z][new_y * search.width + new_x];
                value_at_new_point = 255.0 * (value_at_new_point - search.stackMin) / (search.stackMax - search.stackMin);
                break;
            }
        }
        return value_at_new_point;
    }

    @Override
    public double costMovingTo(int new_x, int new_y, int new_z) {
        double value = getValueAtNewPoint(new_x, new_y, new_z);
        if (value != 0) {
            return 1.0 / value;
        } else {
            return 1 / RECIPROCAL_FUDGE;
        }
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
