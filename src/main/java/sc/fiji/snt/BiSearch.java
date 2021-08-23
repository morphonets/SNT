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
import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import org.jheaps.AddressableHeap;
import org.jheaps.tree.PairingHeap;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.util.SearchImage;
import sc.fiji.snt.util.SearchImageStack;
import sc.fiji.snt.util.SupplierUtil;

import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * A flexible implementation of the bidirectional heuristic search algorithm described in
 * Pijls, W.H.L.M. & Post, H., 2009. "Yet another bidirectional algorithm for shortest paths,"
 * Econometric Institute Research Papers EI 2009-10,
 * Erasmus University Rotterdam, Erasmus School of Economics (ESE), Econometric Institute.
 * <p>
 * The search distance function ({@link SearchCost}) and heuristic estimate ({@link SearchHeuristic}) are
 * supplied by the caller.
 *
 * @author Cameron Arshadi
 */
public class BiSearch extends AbstractSearch {

    public enum NodeState {OPEN_FROM_START, OPEN_FROM_GOAL, CLOSED_FROM_START, CLOSED_FROM_GOAL, FREE}

    protected final int start_x;
    protected final int start_y;
    protected final int start_z;
    protected final int goal_x;
    protected final int goal_y;
    protected final int goal_z;
    protected final SearchCost costFunction;
    protected final SearchHeuristic heuristic;
    protected AddressableHeap<BiSearchNode, Void> open_from_start;
    protected AddressableHeap<BiSearchNode, Void> open_from_goal;
    protected long closed_from_start_count;
    protected long closed_from_goal_count;
    protected SearchImageStack<BiSearchNode> nodes_as_image;
    protected Path result;
    long started_at;
    long loops;
    long loops_at_last_report;
    long lastReportMilliseconds;
    private double bestPathLength;
    private BiSearchNode touchNode;


    public BiSearch(final ImagePlus imagePlus,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    final int timeoutSeconds, final long reportEveryMilliseconds,
                    final SNT.SearchImageType searchImageType,
                    final SearchCost costFunction, final SearchHeuristic heuristic)
    {
        this(ImageJFunctions.wrapReal(imagePlus), imagePlus.getCalibration(), start_x, start_y, start_z,
                goal_x, goal_y, goal_z, timeoutSeconds, reportEveryMilliseconds, searchImageType,
                costFunction, heuristic);
    }


    /* If you specify 0 for timeoutSeconds then there is no timeout. */
    public BiSearch(final RandomAccessibleInterval<? extends RealType<?>> image,
                    final Calibration calibration,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    final int timeoutSeconds, final long reportEveryMilliseconds,
                    final SNT.SearchImageType searchImageType,
                    final SearchCost costFunction, final SearchHeuristic heuristic)
    {
        super(image, calibration, timeoutSeconds, reportEveryMilliseconds);
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        this.costFunction = costFunction;
        this.heuristic = heuristic;
        this.heuristic.setCalibration(calibration);
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(searchImageType, BiSearchNode.class, imgWidth, imgHeight));
        init();
    }

    public BiSearch(final SNT snt, final ImagePlus imagePlus,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    SearchCost costFunction, SearchHeuristic heuristic)
    {
        this(snt, ImageJFunctions.wrapReal(imagePlus), start_x, start_y, start_z, goal_x, goal_y, goal_z,
                costFunction, heuristic);
    }

    public BiSearch(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    SearchCost costFunction, SearchHeuristic heuristic)
    {
        super(snt, image);
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        this.costFunction = costFunction;
        this.heuristic = heuristic;
        Calibration cal = new Calibration();
        cal.pixelWidth = snt.x_spacing;
        cal.pixelHeight = snt.y_spacing;
        cal.pixelDepth = snt.z_spacing;
        this.heuristic.setCalibration(cal);
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(snt.searchImageType, BiSearchNode.class, imgWidth, imgHeight));
        init();
    }


    private void init() {

        open_from_start = new PairingHeap<>(new NodeComparatorFromStart());
        open_from_goal = new PairingHeap<>(new NodeComparatorFromGoal());

        closed_from_start_count = 0L;
        closed_from_goal_count = 0L;

        progressListeners = new ArrayList<>();
    }

    @Override
    public void run() {

        try {
            if (verbose) {
                System.out.println("New SearchThread running!");
                printStatus();
            }

            started_at = lastReportMilliseconds = System.currentTimeMillis();

            BiSearchNode start = new BiSearchNode(start_x, start_y, start_z);
            BiSearchNode goal = new BiSearchNode(goal_x, goal_y, goal_z);

            start.gFromStart = 0d;
            goal.gFromGoal = 0d;

            double bestFScoreFromStart =
                    heuristic.estimateCostToGoal(start.x, start.y, start.z, goal.x, goal.y, goal.z) *
                            costFunction.minStepCost();
            double bestFScoreFromGoal =
                    heuristic.estimateCostToGoal(goal.x, goal.y, goal.z, start.x, start.y, start.z) *
                            costFunction.minStepCost();

            start.fFromStart = bestFScoreFromStart;
            goal.fFromGoal = bestFScoreFromGoal;

            bestPathLength = Double.POSITIVE_INFINITY;

            touchNode = null;

            start.heapHandleFromStart = open_from_start.insert(start);
            goal.heapHandleFromGoal = open_from_goal.insert(goal);

            nodes_as_image.newSlice(start_z);
            nodes_as_image.getSlice(start_z).setValue(start_x, start_y, start);

            if (nodes_as_image.getSlice(goal_z) == null) {
                nodes_as_image.newSlice(goal_z);
            }
            nodes_as_image.getSlice(goal_z).setValue(goal_x, goal_y, goal);

            // The search terminates when one side is exhausted
            while (!open_from_goal.isEmpty() && !open_from_start.isEmpty()) {

                if (Thread.currentThread().isInterrupted()) {
                    if (verbose) System.out.println("Search thread interrupted, returning null result.");
                    reportFinished(false);
                    return;
                }

                ++loops;

                if (0 == (loops % 10000) && checkStatus()) {
                    // search timed out
                    reportFinished(false);
                    return;
                }

                final boolean fromStart = open_from_start.size() < open_from_goal.size();

                if (fromStart) {

                    final BiSearchNode p = open_from_start.deleteMin().getKey();
                    p.heapHandleFromStart = null;
                    p.stateFromStart = NodeState.CLOSED_FROM_START;
                    closed_from_start_count++;

                    bestFScoreFromStart = p.fFromStart;

                    if (p.gFromStart + heuristic.estimateCostToGoal(p.x, p.y, p.z, goal.x, goal.y, goal.z) *
                            costFunction.minStepCost() >= bestPathLength
                            ||
                            p.gFromStart + bestFScoreFromGoal -
                                    heuristic.estimateCostToGoal(p.x, p.y, p.z, start.x, start.y, start.z) *
                                            costFunction.minStepCost() >= bestPathLength)
                    {
                        // REJECTED
                        continue;

                    } else {
                        // STABILIZED
                        expandNeighbors(p, true);
                    }

                } else {

                    final BiSearchNode p = open_from_goal.deleteMin().getKey();
                    p.heapHandleFromGoal = null;
                    p.stateFromGoal = NodeState.CLOSED_FROM_GOAL;
                    closed_from_goal_count++;

                    bestFScoreFromGoal = p.fFromGoal;

                    if (p.gFromGoal + heuristic.estimateCostToGoal(p.x, p.y, p.z, start.x, start.y, start.z) *
                            costFunction.minStepCost() >= bestPathLength
                            ||
                            p.gFromGoal + bestFScoreFromStart -
                                    heuristic.estimateCostToGoal(p.x, p.y, p.z, goal.x, goal.y, goal.z) *
                                            costFunction.minStepCost() >= bestPathLength)
                    {
                        // REJECTED
                        continue;

                    } else {
                        // STABILIZED
                        expandNeighbors(p, false);
                    }
                }
            }

            if (touchNode == null) {
                SNTUtils.error("Searches did not meet.");
                reportFinished(false);
                return;
            }

            // Success
            if (verbose) {
                System.out.println("Searches met!");
                System.out.println("Cost for path = " + bestPathLength);
                System.out.println("Total loops = " + loops);
            }

            result = reconstructPath(xSep, ySep, zSep, spacing_units);
            reportFinished(true);

        } catch (Exception ex) {
            SNTUtils.error("Exception during search", ex);
            reportFinished(false);
        }

    }

    protected void expandNeighbors(final BiSearchNode p, final boolean fromStart) {
        for (int zdiff = -1; zdiff <= 1; ++zdiff) {
            final int new_z = p.z + zdiff;
            // We check whether the neighbor is outside the bounds of the min-max of the interval,
            //  which may or may not have the origin at (0, 0, 0)
            if (new_z < zMin || new_z > zMax) continue;
            SearchImage<BiSearchNode> slice = nodes_as_image.getSlice(new_z);
            if (slice == null) {
                slice = nodes_as_image.newSlice(new_z);
            }
            imgAccess.setPosition(new_z, 2);

            for (int xdiff = -1; xdiff <= 1; xdiff++) {
                final int new_x = p.x + xdiff;
                if (new_x < xMin || new_x > xMax) continue;
                imgAccess.setPosition(new_x, 0);

                for (int ydiff = -1; ydiff <= 1; ydiff++) {
                    if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0)) continue;
                    final int new_y = p.y + ydiff;
                    if (new_y < yMin || new_y > yMax) continue;
                    imgAccess.setPosition(new_y, 1);

                    double cost_moving_to_new_point = costFunction.costMovingTo(imgAccess.get().getRealDouble());
                    if (cost_moving_to_new_point < costFunction.minStepCost()) {
                        cost_moving_to_new_point = costFunction.minStepCost();
                    }

                    final double current_g = fromStart ? p.gFromStart : p.gFromGoal;
                    final double tentative_g = current_g + Math.sqrt(
                            Math.pow(xdiff * xSep, 2) + Math.pow(ydiff * ySep, 2) + Math.pow(zdiff * zSep, 2))
                            * cost_moving_to_new_point;

                    final double tentative_h = heuristic.estimateCostToGoal(
                            new_x,
                            new_y,
                            new_z,
                            fromStart ? goal_x : start_x,
                            fromStart ? goal_y : start_y,
                            fromStart ? goal_z : start_z) * costFunction.minStepCost();

                    final double tentative_f = tentative_g + tentative_h;

                    testNeighbor(new_x, new_y, new_z, tentative_g, tentative_f, p, slice, fromStart);
                }
            }
        }
    }

    private void testNeighbor(final int new_x, final int new_y, final int new_z,
                              final double tentative_g, final double tentative_f,
                              final BiSearchNode predecessor,
                              final SearchImage<BiSearchNode> currentSlice,
                              final boolean fromStart)
    {
        final AddressableHeap<BiSearchNode, Void> open_queue = fromStart ? open_from_start : open_from_goal;
        final BiSearchNode alreadyThere = currentSlice.getValue(new_x, new_y);
        if (alreadyThere == null) {
            final BiSearchNode newNode = new BiSearchNode(new_x, new_y, new_z);
            newNode.setFrom(tentative_g, tentative_f, predecessor, fromStart);
            newNode.heapInsert(open_queue, fromStart);
            nodes_as_image.getSlice(newNode.z).setValue(newNode.x, newNode.y, newNode);

        } else if ((fromStart ? alreadyThere.fFromStart : alreadyThere.fFromGoal) > tentative_f) {

            alreadyThere.setFrom(tentative_g, tentative_f, predecessor, fromStart);
            alreadyThere.heapInsertOrDecrease(open_queue, fromStart);
            final double pathLength = alreadyThere.gFromStart + alreadyThere.gFromGoal;
            if (pathLength < bestPathLength)
            {
                bestPathLength = pathLength;
                touchNode = alreadyThere;
            }
        }
    }

    protected Path reconstructPath(final double x_spacing, final double y_spacing, final double z_spacing,
                                   final String spacing_units)
    {

        Deque<BiSearchNode> forwardPath = new ArrayDeque<>();
        BiSearchNode p = touchNode.predecessorFromStart;
        while (p != null) {
            forwardPath.addFirst(p);
            p = p.predecessorFromStart;
        }

        List<BiSearchNode> backwardsPath = new ArrayList<>();
        p = touchNode.predecessorFromGoal;
        while (p != null) {
            backwardsPath.add(p);
            p = p.predecessorFromGoal;
        }

        Path path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

        for (BiSearchNode n : forwardPath) {
            path.addPointDouble(n.x * x_spacing, n.y * y_spacing, n.z * z_spacing);
        }

        path.addPointDouble(touchNode.x * x_spacing, touchNode.y * y_spacing,
                touchNode.z * z_spacing);

        for (BiSearchNode n : backwardsPath) {
            path.addPointDouble(n.x * x_spacing, n.y * y_spacing, n.z * z_spacing);
        }

        return path;
    }

    @Override
    public void addProgressListener(final SearchProgressCallback callback) {
        progressListeners.add(callback);
    }

    public void reportFinished(final boolean success) {
        for (final SearchProgressCallback progress : progressListeners)
            progress.finished(this, success);
    }

    @Override
    public long pointsConsideredInSearch() {
        return open_from_start.size() + open_from_goal.size() + closed_from_start_count + closed_from_goal_count;
    }

    protected boolean checkStatus() {
        final long currentMilliseconds = System.currentTimeMillis();
        final long millisecondsSinceStart = currentMilliseconds - started_at;

        if ((timeoutSeconds > 0) && (millisecondsSinceStart > (1000L * timeoutSeconds))) {
            if (verbose) System.out.println("Timed out...");
            return true;
        }

        final long since_last_report = currentMilliseconds - lastReportMilliseconds;
        if ((reportEveryMilliseconds > 0) && (since_last_report > reportEveryMilliseconds)) {
            final long loops_since_last_report = loops - loops_at_last_report;
            if (verbose) {
                System.out.println("" + (since_last_report / (double) loops_since_last_report) + "ms/loop");
                printStatus();
            }

            reportPointsInSearch();

            loops_at_last_report = loops;
            lastReportMilliseconds = currentMilliseconds;
        }
        return false;
    }

    @Override
    public Path getResult() {
        return result;
    }

    @Override
    public void printStatus() {
        System.out.println("... Start nodes: open=" + open_from_start.size() +
                " closed=" + closed_from_start_count);
        System.out.println("...  Goal nodes: open=" + open_from_goal.size() +
                " closed=" + closed_from_goal_count);
    }

    @Override
    protected void reportPointsInSearch() {
        for (final SearchProgressCallback progress : progressListeners)
            progress.pointsInSearch(this, open_from_start.size() + open_from_goal.size(),
                    closed_from_start_count + closed_from_goal_count);
    }

    @Override
    protected BiSearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
                                                 final double threshold)
    {
        final SearchImage<BiSearchNode> slice = nodes_as_image.getSlice(z);
        if (slice == null) {
            return null;
        }
        try {
            return slice.getValue(x, y);
        } catch (ArrayIndexOutOfBoundsException e) {
            // FIXME: This only occurs with MapSearchImage
            //  possibly a synchronization issue going on...
            return null;
        }
    }

    static class NodeComparatorFromStart implements Comparator<BiSearchNode> {

        public int compare(BiSearchNode n1, BiSearchNode n2) {
            int result = Double.compare(n1.fFromStart, n2.fFromStart);
            if (result != 0) {
                return result;

            } else {
                int x_compare = 0;
                if (n1.x > n2.x) x_compare = 1;
                if (n1.x < n2.x) x_compare = -1;

                if (x_compare != 0) return x_compare;

                int y_compare = 0;
                if (n1.y > n2.y) y_compare = 1;
                if (n1.y < n2.y) y_compare = -1;

                if (y_compare != 0) return y_compare;

                int z_compare = 0;
                if (n1.z > n2.z) z_compare = 1;
                if (n1.z < n2.z) z_compare = -1;

                return z_compare;
            }
        }
    }


    static class NodeComparatorFromGoal implements Comparator<BiSearchNode> {

        public int compare(BiSearchNode n1, BiSearchNode n2) {
            int result = Double.compare(n1.fFromGoal, n2.fFromGoal);
            if (result != 0) {
                return result;

            } else {
                int x_compare = 0;
                if (n1.x > n2.x) x_compare = 1;
                if (n1.x < n2.x) x_compare = -1;

                if (x_compare != 0) return x_compare;

                int y_compare = 0;
                if (n1.y > n2.y) y_compare = 1;
                if (n1.y < n2.y) y_compare = -1;

                if (y_compare != 0) return y_compare;

                int z_compare = 0;
                if (n1.z > n2.z) z_compare = 1;
                if (n1.z < n2.z) z_compare = -1;

                return z_compare;
            }
        }
    }

}
