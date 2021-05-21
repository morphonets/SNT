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
 * A search distance function based on the filter described in
 * Frangi A.F., Niessen W.J., Vincken K.L., Viergever M.A. (1998) Multiscale vessel enhancement filtering.
 * In: Wells W.M., Colchester A., Delp S. (eds) Medical Image Computing and Computer-Assisted Intervention. MICCAI 1998.
 * Lecture Notes in Computer Science, vol 1496. Springer, Berlin, Heidelberg.
 * https://doi.org/10.1007/BFb0056195
 * TODO
 *
 * @author Cameron Arshadi
 */
public class FrangiCost implements SearchCost {

    static final double STEP_COST_LOWER_BOUND = 1e-60;
    final double minStepCost;

    HessianProcessor hessian;
    SearchInterface search;

    public FrangiCost(HessianProcessor hessian) {
        if (hessian == null) {
            throw new IllegalArgumentException("HessianAnalyzer cannot be null");
        }
        this.hessian = hessian;
        this.minStepCost = computeMinStepCost();
        SNTUtils.log("min step cost = " + minStepCost);
    }

    /* This cost function is based on an A* implementation written by Christopher Bruns (cmbruns) for
       the Janelia Workstation (https://github.com/JaneliaSciComp/workstation), original source at
       https://github.com/JaneliaSciComp/workstation/blob/master/modules/LargeVolumeViewer/src/main/java/org/janelia/workstation/gui/large_volume_viewer/tracing/AStar.java
       There, they set the cost of moving to a neighbor to be the probability of that pixel intensity occurring by
       chance, given the intensity statistics of the sub-volume. Here, we use the vesselness measure instead of
       pixel intensity. */

    @Override
    public double costMovingTo(int new_x, int new_y, int new_z) {

        double vesselness;
        if (hessian.is3D) {
            vesselness = hessian.frangiAccess.setPositionAndGet(new_x, new_y, new_z).getRealDouble();
        } else {
            vesselness = hessian.frangiAccess.setPositionAndGet(new_x, new_y).getRealDouble();
        }

        double zScore = (vesselness - hessian.frangiStats.getAvg()) / hessian.frangiStats.getStdDev();
        return oneMinusErf(0.80 * zScore);
    }

    private static double oneMinusErf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));

        // use Horner's method
        double result = t * Math.exp( -z*z - 1.26551223 +
                t * ( 1.00002368 +
                        t * ( 0.37409196 +
                                t * ( 0.09678418 +
                                        t * (-0.18628806 +
                                                t * ( 0.27886807 +
                                                        t * (-1.13520398 +
                                                                t * ( 1.48851587 +
                                                                        t * (-0.82215223 +
                                                                                t * ( 0.17087277))))))))));
        if (z < 0)
            result = 2.0 - result;
        return  result;
    }

    private double computeMinStepCost() {
        double zScore = (hessian.frangiStats.getMax() - hessian.frangiStats.getAvg()) / hessian.frangiStats.getStdDev();
        return oneMinusErf(0.80 * zScore) + STEP_COST_LOWER_BOUND;
    }

    private double costMovingTo2(int new_x, int new_y, int new_z) {
        // this is just a placeholder metric that works well enough for the time being

        double measure;
        if (!hessian.is3D) {
            measure = hessian.frangiAccess.setPositionAndGet(new_x, new_y).getRealDouble();

        } else {
            measure = hessian.frangiAccess.setPositionAndGet(new_x, new_y, new_z).getRealDouble();

        }
        if (measure == 0) {
            measure = 1e-60;

        }
        return hessian.frangiStats.getMax() / measure;
    }

    @Override
    public double minimumCostPerUnitDistance() {
        return minStepCost;
    }

    @Override
    public void setSearch(AbstractSearch search) {
        this.search = search;
    }

}
