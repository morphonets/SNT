/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.tracing;

import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.jheaps.AddressableHeap;
import org.jheaps.tree.PairingHeap;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchProgressCallback;
import sc.fiji.snt.tracing.cost.Cost;
import sc.fiji.snt.tracing.cost.Reciprocal;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;
import sc.fiji.snt.tracing.image.SupplierUtil;

import java.util.*;

/**
 * A flexible implementation of the bidirectional heuristic search algorithm described in
 * Pijls, W.H.L.M. & Post, H., 2009. "Yet another bidirectional algorithm for shortest paths,"
 * Econometric Institute Research Papers EI 2009-10,
 * Erasmus University Rotterdam, Erasmus School of Economics (ESE), Econometric Institute.
 * <p>
 * The search distance function ({@link Cost}) and heuristic estimate ({@link Heuristic}) are
 * supplied by the caller.
 *
 * @author Cameron Arshadi
 */
public class BiSearch extends AbstractSearch {

    protected final int start_x;
    protected final int start_y;
    protected final int start_z;
    protected final int goal_x;
    protected final int goal_y;
    protected final int goal_z;
    protected final Cost costFunction;
    protected final Heuristic heuristic;
    protected AddressableHeap<BiSearchNode, Void> open_from_start;
    protected AddressableHeap<BiSearchNode, Void> open_from_goal;
    protected long closed_from_start_count;
    protected long closed_from_goal_count;
    protected final SearchImageStack<BiSearchNode> nodes_as_image;
    protected Path result;
    long started_at;
    long loops;
    long loops_at_last_report;
    long lastReportMilliseconds;
    private double bestPathLength;
    private BiSearchNode touchNode;


    /* If you specify 0 for timeoutSeconds then there is no timeout. */
    public BiSearch(final RandomAccessibleInterval<? extends RealType<?>> image,
                    final Calibration calibration,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    final int timeoutSeconds, final long reportEveryMilliseconds,
                    final SNT.SearchImageType searchImageType,
                    final Cost costFunction, final Heuristic heuristic)
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
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(searchImageType, BiSearchNode.class, imgWidth, imgHeight));
        init();
    }

    public BiSearch(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image,
                    final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    Cost costFunction, Heuristic heuristic)
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
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(snt.getSearchImageType(), BiSearchNode.class, imgWidth, imgHeight));
        init();
    }

    public BiSearch(final SNT snt, final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z,
                    Cost costFunction, Heuristic heuristic)
    {
        super(snt, snt.getLoadedData());
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        this.costFunction = costFunction;
        this.heuristic = heuristic;
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(snt.getSearchImageType(), BiSearchNode.class, imgWidth, imgHeight));
        init();
    }

    public BiSearch(final SNT snt, final int start_x, final int start_y, final int start_z,
                    final int goal_x, final int goal_y, final int goal_z)
    {
        super(snt, snt.getLoadedData());
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        this.costFunction = new Reciprocal(snt.getStats().min, snt.getStats().max);
        final Calibration cal = new Calibration();
        cal.pixelWidth = snt.getPixelWidth();
        cal.pixelHeight = snt.getPixelHeight();
        cal.pixelDepth = snt.getPixelDepth();
        this.heuristic  = new Euclidean(cal);
        nodes_as_image = new SearchImageStack<>(
                SupplierUtil.createSupplier(snt.getSearchImageType(), BiSearchNode.class, imgWidth, imgHeight));
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
                SNTUtils.log("New " + getClass().getSimpleName() + " running!");
                printStatus();
            }

            started_at = lastReportMilliseconds = System.currentTimeMillis();

            final BiSearchNode start = new BiSearchNode(start_x, start_y, start_z);
            final BiSearchNode goal = new BiSearchNode(goal_x, goal_y, goal_z);

            start.setGFromStart(0d);
            goal.setGFromGoal(0d);

            double bestFScoreFromStart = heuristic.estimateCostToGoal(start.getX(), start.getY(), start.getZ(),
                    goal.getX(), goal.getY(), goal.getZ()) * costFunction.minStepCost();

            double bestFScoreFromGoal = heuristic.estimateCostToGoal(goal.getX(), goal.getY(), goal.getZ(),
                    start.getX(), start.getY(), start.getZ()) * costFunction.minStepCost();

            start.setFFromStart(bestFScoreFromStart);
            goal.setFFromGoal(bestFScoreFromGoal);

            bestPathLength = Double.POSITIVE_INFINITY;

            touchNode = null;

            start.setHeapHandleFromStart(open_from_start.insert(start));
            goal.setHeapHandleFromGoal(open_from_goal.insert(goal));

            nodes_as_image.newSlice(start_z);
            nodes_as_image.getSlice(start_z).setValue(start_x, start_y, start);

            if (nodes_as_image.getSlice(goal_z) == null) {
                nodes_as_image.newSlice(goal_z);
            }
            nodes_as_image.getSlice(goal_z).setValue(goal_x, goal_y, goal);

            // The search terminates when one side is exhausted
            while (!open_from_goal.isEmpty() && !open_from_start.isEmpty()) {

                if (Thread.currentThread().isInterrupted()) {
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
                    p.setHeapHandleFromStart(null);
                    p.setStateFromStart(BiSearchNode.State.CLOSED);
                    closed_from_start_count++;

                    bestFScoreFromStart = p.getFFromStart();

                    if (p.getGFromStart() + heuristic.estimateCostToGoal(p.getX(), p.getY(), p.getZ(),
                            goal.getX(), goal.getY(), goal.getZ()) * costFunction.minStepCost()
                            >= bestPathLength
                            ||
                            p.getGFromStart() + bestFScoreFromGoal - heuristic.estimateCostToGoal(p.getX(), p.getY(),
                                    p.getZ(), start.getX(), start.getY(), start.getZ()) * costFunction.minStepCost()
                                    >= bestPathLength)
                    {
                        // REJECTED
                        continue;

                    } else {
                        // STABILIZED
                        expandNeighbors(p, true);
                    }

                } else {

                    final BiSearchNode p = open_from_goal.deleteMin().getKey();
                    p.setHeapHandleFromGoal(null);
                    p.setStateFromGoal(BiSearchNode.State.CLOSED);
                    closed_from_goal_count++;

                    bestFScoreFromGoal = p.getFFromGoal();

                    if (p.getGFromGoal() + heuristic.estimateCostToGoal(p.getX(), p.getY(), p.getZ(), start.getX(),
                            start.getY(), start.getZ()) * costFunction.minStepCost()
                            >= bestPathLength
                            ||
                            p.getGFromGoal() + bestFScoreFromStart -
                                    heuristic.estimateCostToGoal(p.getX(), p.getY(), p.getZ(), goal.getX(), goal.getY(),
                                            goal.getZ()) * costFunction.minStepCost()
                                    >= bestPathLength)
                    {
                        // REJECTED
                        continue;

                    } else {
                        // STABILIZED
                        expandNeighbors(p, false);
                    }
                }
            }

            // Failure
            if (touchNode == null) {
                SNTUtils.error("Searches did not meet.");
                reportFinished(false);
                return;
            }

            // Success
            if (verbose) {
                SNTUtils.log("Searches met!");
                SNTUtils.log("Cost for path = " + bestPathLength);
                SNTUtils.log("Total loops = " + loops);
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
            final int new_z = p.getZ() + zdiff;
            // We check whether the neighbor is outside the bounds of the min-max of the interval,
            //  which may or may not have the origin at (0, 0, 0)
            if (new_z < zMin || new_z > zMax)
                continue;
            SearchImage<BiSearchNode> slice = nodes_as_image.getSlice(new_z);
            if (slice == null) {
                slice = nodes_as_image.newSlice(new_z);
            }
            imgAccess.setPosition(new_z, 2);

            for (int xdiff = -1; xdiff <= 1; xdiff++) {
                final int new_x = p.getX() + xdiff;
                if (new_x < xMin || new_x > xMax)
                    continue;
                imgAccess.setPosition(new_x, 0);

                for (int ydiff = -1; ydiff <= 1; ydiff++) {
                    if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0))
                        continue;
                    final int new_y = p.getY() + ydiff;
                    if (new_y < yMin || new_y > yMax)
                        continue;
                    imgAccess.setPosition(new_y, 1);

                    double cost_moving_to_new_point = costFunction.costMovingTo(imgAccess.get().getRealDouble());
                    if (cost_moving_to_new_point < costFunction.minStepCost()) {
                        cost_moving_to_new_point = costFunction.minStepCost();
                    }

                    final double current_g = p.getG(fromStart);
                    final double tentative_g = current_g +
                            Math.sqrt(
                                    Math.pow(xdiff * xSep, 2) +
                                    Math.pow(ydiff * ySep, 2) +
                                    Math.pow(zdiff * zSep, 2))
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
            newNode.setState(BiSearchNode.State.OPEN, fromStart);
            newNode.heapInsert(open_queue, fromStart);
            nodes_as_image.getSlice(newNode.getZ()).setValue(newNode.getX(), newNode.getY(), newNode);

        } else if (alreadyThere.getF(fromStart) > tentative_f) {

            alreadyThere.setFrom(tentative_g, tentative_f, predecessor, fromStart);
            AddressableHeap.Handle<BiSearchNode, Void> handle = alreadyThere.getHeapHandle(fromStart);
            if (handle == null) {
                alreadyThere.setState(BiSearchNode.State.OPEN, fromStart);
                alreadyThere.heapInsert(open_queue, fromStart);
            } else {
                alreadyThere.heapDecreaseKey(fromStart);
            }
            final double pathLength = alreadyThere.getGFromStart() + alreadyThere.getGFromGoal();
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

        final Deque<BiSearchNode> forwardPath = new ArrayDeque<>();
        BiSearchNode p = touchNode.getPredecessorFromStart();
        while (p != null) {
            forwardPath.addFirst(p);
            p = p.getPredecessorFromStart();
        }

        final List<BiSearchNode> backwardsPath = new ArrayList<>();
        p = touchNode.getPredecessorFromGoal();
        while (p != null) {
            backwardsPath.add(p);
            p = p.getPredecessorFromGoal();
        }

        final Path path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

        for (final BiSearchNode n : forwardPath) {
            path.addPointDouble(n.getX() * x_spacing, n.getY() * y_spacing, n.getZ() * z_spacing);
        }

        path.addPointDouble(touchNode.getX() * x_spacing, touchNode.getY() * y_spacing,
                touchNode.getZ() * z_spacing);

        for (final BiSearchNode n : backwardsPath) {
            path.addPointDouble(n.getX() * x_spacing, n.getY() * y_spacing, n.getZ() * z_spacing);
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
            SNTUtils.log("Timed out...");
            return true;
        }

        final long since_last_report = currentMilliseconds - lastReportMilliseconds;
        if ((reportEveryMilliseconds > 0) && (since_last_report > reportEveryMilliseconds)) {
            final long loops_since_last_report = loops - loops_at_last_report;
            if (verbose) {
                SNTUtils.log("" + (since_last_report / (double) loops_since_last_report) + "ms/loop");
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
        SNTUtils.log("... Start nodes: open=" + open_from_start.size() +
                " closed=" + closed_from_start_count);
        SNTUtils.log("...  Goal nodes: open=" + open_from_goal.size() +
                " closed=" + closed_from_goal_count);
    }

    @Override
    protected void reportPointsInSearch() {
        for (final SearchProgressCallback progress : progressListeners)
            progress.pointsInSearch(this, open_from_start.size() + open_from_goal.size(),
                    closed_from_start_count + closed_from_goal_count);
    }

    public SearchImageStack<BiSearchNode> getNodesAsImage() {
        return nodes_as_image;
    }

    static class NodeComparatorFromStart implements Comparator<BiSearchNode> {

        public int compare(BiSearchNode n1, BiSearchNode n2) {
            int result = Double.compare(n1.getFFromStart(), n2.getFFromStart());
            if (result != 0) {
                return result;

            } else {
                int x_compare = 0;
                if (n1.getX() > n2.getX()) x_compare = 1;
                if (n1.getX() < n2.getX()) x_compare = -1;

                if (x_compare != 0) return x_compare;

                int y_compare = 0;
                if (n1.getY() > n2.getY()) y_compare = 1;
                if (n1.getY() < n2.getY()) y_compare = -1;

                if (y_compare != 0) return y_compare;

                int z_compare = 0;
                if (n1.getZ() > n2.getZ()) z_compare = 1;
                if (n1.getZ() < n2.getZ()) z_compare = -1;

                return z_compare;
            }
        }
    }


    static class NodeComparatorFromGoal implements Comparator<BiSearchNode> {

        public int compare(BiSearchNode n1, BiSearchNode n2) {
            int result = Double.compare(n1.getFFromGoal(), n2.getFFromGoal());
            if (result != 0) {
                return result;

            } else {
                int x_compare = 0;
                if (n1.getX() > n2.getX()) x_compare = 1;
                if (n1.getX() < n2.getX()) x_compare = -1;

                if (x_compare != 0) return x_compare;

                int y_compare = 0;
                if (n1.getY() > n2.getY()) y_compare = 1;
                if (n1.getY() < n2.getY()) y_compare = -1;

                if (y_compare != 0) return y_compare;

                int z_compare = 0;
                if (n1.getZ() > n2.getZ()) z_compare = 1;
                if (n1.getZ() < n2.getZ()) z_compare = -1;

                return z_compare;
            }
        }
    }

}
