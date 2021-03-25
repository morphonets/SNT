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
import org.jheaps.AddressableHeap;
import org.jheaps.tree.*;
import sc.fiji.snt.util.SparseMatrix;
import sc.fiji.snt.util.SparseMatrixStack;

import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * A skeletal implementation of the bidirectional A-star search algorithm described in
 * Pijls, W.H.L.M. & Post, H., 2009. "Yet another bidirectional algorithm for shortest paths,"
 * Econometric Institute Research Papers EI 2009-10,
 * Erasmus University Rotterdam, Erasmus School of Economics (ESE), Econometric Institute.
 * Like {@link SearchThread}, this class is meant to be extended for use with custom heuristics.
 *
 * @author Cameron Arshadi
 */
public abstract class AbstractBidirectionalSearch extends AbstractSearch implements Callable<Path> {

    private final int start_x;
    private final int start_y;
    private final int start_z;
    private final int goal_x;
    private final int goal_y;
    private final int goal_z;

    protected AddressableHeap<BidirectionalSearchNode, Void> open_from_start;
    protected AddressableHeap<BidirectionalSearchNode, Void> open_from_goal;

    protected long closed_from_start_count;
    protected long closed_from_goal_count;

    protected SparseMatrixStack<BidirectionalSearchNode> nodes_as_image;

    private final boolean verbose = SNTUtils.isDebugMode();

    protected double minimum_cost_per_unit_distance;

    private BidirectionalSearchNode start;
    private BidirectionalSearchNode goal;
    private double bestPathLength;
    private BidirectionalSearchNode touchNode;

    protected int minExpectedSize;

    /* If you specify 0 for timeoutSeconds then there is no timeout. */
    public AbstractBidirectionalSearch(final int start_x, final int start_y, final int start_z,
                                       final int goal_x, final int goal_y, final int goal_z,
                                       final ImagePlus imagePlus, final float stackMin, final float stackMax,
                                       final int timeoutSeconds, final long reportEveryMilliseconds)
    {
        super(imagePlus, stackMin, stackMax, timeoutSeconds, reportEveryMilliseconds);
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        init();
    }

    protected AbstractBidirectionalSearch(final int start_x, final int start_y, final int start_z,
                                          final int goal_x, final int goal_y, final int goal_z,
                                          final SNT snt) {
        super(snt);
        this.start_x = start_x;
        this.start_y = start_y;
        this.start_z = start_z;
        this.goal_x = goal_x;
        this.goal_y = goal_y;
        this.goal_z = goal_z;
        init();
    }


    private void init() {

        open_from_start = new PairingHeap<>(new NodeComparatorFromStart());
        open_from_goal = new PairingHeap<>(new NodeComparatorFromGoal());

        closed_from_start_count = 0L;
        closed_from_goal_count = 0L;

        nodes_as_image = new SparseMatrixStack<>(depth);

        progressListeners = new ArrayList<>();
    }

    protected abstract double minimumCostPerUnitDistance();

    protected BidirectionalSearchNode createNewNode(final int x, final int y, final int z) {
        return new BidirectionalSearchNode(x, y, z);
    }

    public BidirectionalSearchNode getNode(int x, int y, int z) {
        BidirectionalSearchNode neighbor = nodes_as_image.getSlice(z)
                .getValue(x, y);
        if (neighbor == null) {
            neighbor = new BidirectionalSearchNode(x, y, z);
            neighbor.isNew = true;
            nodes_as_image.getSlice(z).setValue(x, y, neighbor);
        }
        return neighbor;
    }

    public List<BidirectionalSearchNode> getNeighbors(final BidirectionalSearchNode p) {
        final List<BidirectionalSearchNode> neighborList = new ArrayList<>();
        for (int zdiff = -1; zdiff <= 1; zdiff++) {
            final int new_z = p.z + zdiff;
            if (new_z < 0 || new_z >= depth) continue;
            if (nodes_as_image.getSlice(new_z) == null) {
                nodes_as_image.newSlice(new_z);
            }
            for (int xdiff = -1; xdiff <= 1; xdiff++) {
                for (int ydiff = -1; ydiff <= 1; ydiff++) {
                    if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0)) continue;
                    final int new_x = p.x + xdiff;
                    final int new_y = p.y + ydiff;
                    if (new_x < 0 || new_x >= width) continue;
                    if (new_y < 0 || new_y >= height) continue;
                    BidirectionalSearchNode neighbor = getNode(new_x, new_y, new_z);
                    if (neighbor.searchStatus == CLOSED_FROM_START || neighbor.searchStatus == CLOSED_FROM_GOAL) {
                        continue;
                    }
                    neighborList.add(neighbor);
                }
            }
        }
        return neighborList;
    }

    protected double getGScoreForStep(final BidirectionalSearchNode from, final BidirectionalSearchNode to) {

        final double xdiffsq = Math.pow((to.x - from.x) * x_spacing, 2);
        final double ydiffsq = Math.pow((to.y - from.y) * y_spacing, 2);
        final double zdiffsq = Math.pow((to.z - from.z) * z_spacing, 2);

        double cost_moving_to_new_point = costMovingTo(to.x, to.y,
                to.z);

        if (cost_moving_to_new_point < minimum_cost_per_unit_distance) {
            cost_moving_to_new_point = minimum_cost_per_unit_distance;
        }

        final double stepCost = Math.sqrt(xdiffsq + ydiffsq + zdiffsq) * cost_moving_to_new_point;

        if (from.searchStatus == CLOSED_FROM_START) {
            return from.gFromStart + stepCost;
        } else if (from.searchStatus == CLOSED_FROM_GOAL) {
            return from.gFromGoal + stepCost;
        } else {
            throw new IllegalStateException("Somehow a FREE node made it into the neighbor search");
        }
    }

    protected void evaluateNeighbors(final BidirectionalSearchNode p, final boolean fromStart) {
        if (fromStart) {
            for (BidirectionalSearchNode neighbor : getNeighbors(p)) {
                double g_for_new_point = getGScoreForStep(p, neighbor);
                if (g_for_new_point < neighbor.gFromStart) {
                    neighbor.gFromStart = g_for_new_point;
                    neighbor.fFromStart = neighbor.gFromStart + estimateCostToGoal(neighbor, goal);
                    neighbor.predecessorFromStart = p;
                    neighbor.searchStatus = OPEN_FROM_START;
                    if (neighbor.isNew) {
                        neighbor.heapHandle = open_from_start.insert(neighbor);
                        neighbor.isNew = false;
                    } else {
                        neighbor.heapHandle.decreaseKey(neighbor);
                    }
                }
                // A finite value indicates a meeting point between the opposing searches
                double pathLength = neighbor.gFromStart + neighbor.gFromGoal;
                if (pathLength < bestPathLength) {
                    bestPathLength = pathLength;
                    touchNode = neighbor;
                }
            }
        } else {
            for (BidirectionalSearchNode neighbor : getNeighbors(p)) {
                double g_for_new_point = getGScoreForStep(p, neighbor);
                if (g_for_new_point < neighbor.gFromGoal) {
                    neighbor.gFromGoal = g_for_new_point;
                    neighbor.fFromGoal = neighbor.gFromGoal + estimateCostToGoal(neighbor, start);
                    neighbor.predecessorFromGoal = p;
                    neighbor.searchStatus = OPEN_FROM_GOAL;
                    if (neighbor.isNew) {
                        neighbor.heapHandle = open_from_goal.insert(neighbor);
                        neighbor.isNew = false;
                    } else {
                        neighbor.heapHandle.decreaseKey(neighbor);
                    }
                }
                // A finite value indicates a meeting point between the opposing searches
                double pathLength = neighbor.gFromGoal + neighbor.gFromStart;
                if (pathLength < bestPathLength) {
                    bestPathLength = pathLength;
                    touchNode = neighbor;
                }
            }
        }
    }

    @Override
     public Path call() throws Exception {

        if (verbose) {
            System.out.println("New SearchThread running!");
            printStatus();
        }

        long lastReportMilliseconds;
        final long started_at = lastReportMilliseconds = System.currentTimeMillis();

        int loops_at_last_report = 0;
        int loops = 0;

        start = createNewNode(start_x, start_y, start_z);
        goal = createNewNode(goal_x, goal_y, goal_z);

        start.searchStatus = OPEN_FROM_START;
        goal.searchStatus = OPEN_FROM_GOAL;

        start.heapHandle = open_from_start.insert(start);
        goal.heapHandle = open_from_goal.insert(goal);

        nodes_as_image.newSlice(start_z);
        nodes_as_image.getSlice(start_z).setValue(start_x, start_y, start);

        SparseMatrix<BidirectionalSearchNode> goalSlice = nodes_as_image.getSlice(goal_z);
        if (goalSlice == null) {
            nodes_as_image.newSlice(goal_z);
        }
        nodes_as_image.getSlice(goal_z).setValue(goal_x, goal_y, goal);

        double bestFScoreFromStart = estimateCostToGoal(start, goal);
        double bestFScoreFromGoal = estimateCostToGoal(goal, start);

        start.fFromStart = bestFScoreFromStart;
        goal.fFromGoal = bestFScoreFromGoal;

        start.gFromStart = 0d;
        goal.gFromGoal = 0d;

        bestPathLength = Double.POSITIVE_INFINITY;

        touchNode = null;

        while (!open_from_goal.isEmpty() && !open_from_start.isEmpty()) {

            if (Thread.currentThread().isInterrupted()) {
                if (verbose) System.out.println("Search thread interrupted, returning null result.");
                return null;
            }

            if (0 == (loops % 10000)) {

                final long currentMilliseconds = System.currentTimeMillis();
                final long millisecondsSinceStart = currentMilliseconds - started_at;

                if ((timeoutSeconds > 0) && (millisecondsSinceStart > (1000L * timeoutSeconds))) {
                    if (verbose) System.out.println("Timed out...");
                    return null;
                }

                final long since_last_report = currentMilliseconds - lastReportMilliseconds;
                if ((reportEveryMilliseconds > 0) && (since_last_report > reportEveryMilliseconds)) {
                    final int loops_since_last_report = loops - loops_at_last_report;
                    if (verbose) {
                        System.out.println("" + (since_last_report / (double) loops_since_last_report) + "ms/loop");
                        printStatus();
                    }

                    reportPointsInSearch();

                    loops_at_last_report = loops;
                    lastReportMilliseconds = currentMilliseconds;
                }
            }

            if (open_from_start.size() < open_from_goal.size()) {

                BidirectionalSearchNode p;

                if (open_from_start.size() == 0) continue;

                p = open_from_start.deleteMin().getKey();
                if (p == null) continue;

                p.searchStatus = CLOSED_FROM_START;
                closed_from_start_count++;

                if (p.gFromStart + estimateCostToGoal(p, goal) >= bestPathLength ||
                        p.gFromStart + bestFScoreFromGoal - estimateCostToGoal(p, start) >= bestPathLength) {

                    // current is rejected
                    continue;

                } else {

                    // current is stabilized
                    evaluateNeighbors(p, true);
                }

                if (!open_from_start.isEmpty()) {
                    bestFScoreFromStart = open_from_start.findMin().getKey().fFromStart;
                }

            } else {

                BidirectionalSearchNode p;

                if (open_from_goal.size() == 0) continue;

                p = open_from_goal.deleteMin().getKey();
                if (p == null) continue;

                p.searchStatus = CLOSED_FROM_GOAL;
                closed_from_goal_count++;

                if (p.gFromGoal + estimateCostToGoal(p, start) >= bestPathLength ||
                        p.gFromGoal + bestFScoreFromStart - estimateCostToGoal(p, goal) >= bestPathLength) {

                    // current is rejected
                    continue;

                } else {

                    //current is stabilized
                    evaluateNeighbors(p, false);
                }
                if (!open_from_goal.isEmpty()) {
                    bestFScoreFromGoal = open_from_goal.findMin().getKey().fFromGoal;
                }
            }
            ++loops;
        }

        if (touchNode == null) {
            // Not sure how this would happen...
            if (verbose) System.out.println("Touch node is null, returning null result");
            return null;
        }

        // Success
        if (verbose) System.out.println("Searches met!");
        return reconstructPath(touchNode, x_spacing, y_spacing, z_spacing, spacing_units);

    }

    protected Path reconstructPath(final BidirectionalSearchNode touchNode, final double x_spacing,
                                 final double y_spacing, final double z_spacing,
                                 final String spacing_units) {

        Deque<BidirectionalSearchNode> forwardPath = new ArrayDeque<>();
        BidirectionalSearchNode p = touchNode.predecessorFromStart;
        while (p != null) {
            forwardPath.addFirst(p);
            p = p.predecessorFromStart;
        }

        List<BidirectionalSearchNode> backwardsPath = new ArrayList<>();
        p = touchNode.predecessorFromGoal;
        while (p != null) {
            backwardsPath.add(p);
            p = p.predecessorFromGoal;
        }

        Path path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

        for (BidirectionalSearchNode n : forwardPath) {
            path.addPointDouble(n.x * x_spacing, n.y * y_spacing, n.z * z_spacing);
        }

        path.addPointDouble(touchNode.x * x_spacing, touchNode.y * y_spacing, touchNode.z * z_spacing);

        for (BidirectionalSearchNode n : backwardsPath) {
            path.addPointDouble(n.x * x_spacing, n.y * y_spacing, n.z * z_spacing);
        }

        return path;
    }

    public void addProgressListener(final SearchProgressCallback callback) {
        progressListeners.add(callback);
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
    protected BidirectionalSearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
                                                      final double threshold)
    {
        final SparseMatrix<BidirectionalSearchNode> slice = nodes_as_image.getSlice(z);
        if (slice == null) {
            return null;
        }
        BidirectionalSearchNode n = slice.getValue(x,y);
        if (n == null) {
            return  null;
        }
        if (threshold >= 0) {
            boolean fromStart = (n.searchStatus == OPEN_FROM_START || n.searchStatus == CLOSED_FROM_START);
            if (threshold >= 0 && (fromStart ? n.gFromStart : n.gFromGoal) > threshold) {
                return null;
            }
        }
        return  n;
    }

    public void setMinExpectedSizeOfResult(final int size) {
        this.minExpectedSize = size;
    }


    protected double estimateCostToGoal(BidirectionalSearchNode from, BidirectionalSearchNode to) {
        return 0;
    }

    static class NodeComparatorFromStart implements Comparator<BidirectionalSearchNode> {

        public int compare(BidirectionalSearchNode n1, BidirectionalSearchNode n2) {
            return Double.compare(n1.fFromStart, n2.fFromStart);
        }
    }

    static class NodeComparatorFromGoal implements Comparator<BidirectionalSearchNode> {

        public int compare(BidirectionalSearchNode n1, BidirectionalSearchNode n2) {
            return Double.compare(n1.fFromGoal, n2.fFromGoal);
        }
    }
}
