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
import sc.fiji.snt.util.*;

import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * A flexible implementation of the bidirectional heuristic search algorithm described in
 * Pijls, W.H.L.M. & Post, H., 2009. "Yet another bidirectional algorithm for shortest paths,"
 * Econometric Institute Research Papers EI 2009-10,
 * Erasmus University Rotterdam, Erasmus School of Economics (ESE), Econometric Institute.
 *
 * The search distance function ({@link SearchCost}) and heuristic estimate ({@link SearchHeuristic}) are
 * supplied by the caller.
 *
 * @author Cameron Arshadi
 */
public class BidirectionalSearch extends AbstractSearch {

    protected static final byte STABILIZED = 0;
    protected static final byte REJECTED = 1;
    protected static final byte UNEXPLORED = 2;

    protected final int start_x;
    protected final int start_y;
    protected final int start_z;
    protected final int goal_x;
    protected final int goal_y;
    protected final int goal_z;

    private final boolean verbose = SNTUtils.isDebugMode();

    protected final SearchCost costFunction;
    protected final SearchHeuristic heuristic;

    protected AddressableHeap<BidirectionalSearchNode, Void> open_from_start;
    protected AddressableHeap<BidirectionalSearchNode, Void> open_from_goal;
    protected long closed_from_start_count;
    protected long closed_from_goal_count;
    protected SearchImageStack<BidirectionalSearchNode> nodes_as_image;

    private double bestPathLength;
    private BidirectionalSearchNode touchNode;

    protected Path result;

    long started_at;
    long loops;
    long loops_at_last_report;
    long lastReportMilliseconds;
    private int[] imgPosition;

    @SuppressWarnings("rawtypes")
    public BidirectionalSearch(final ImagePlus imagePlus,
                               final int start_x, final int start_y, final int start_z,
                               final int goal_x, final int goal_y, final int goal_z,
                               final int timeoutSeconds, final long reportEveryMilliseconds,
                               Class<? extends SearchImage> searchImageType,
                               SearchCost costFunction, SearchHeuristic heuristic)
    {
        this(ImageJFunctions.wrapReal(imagePlus), imagePlus.getCalibration(), start_x, start_y, start_z,
                goal_x, goal_y, goal_z, timeoutSeconds, reportEveryMilliseconds, searchImageType,
                costFunction, heuristic);
    }


    /* If you specify 0 for timeoutSeconds then there is no timeout. */
    @SuppressWarnings("rawtypes")
    public BidirectionalSearch(final RandomAccessibleInterval<? extends RealType<?>> image,
                               final Calibration calibration,
                               final int start_x, final int start_y, final int start_z,
                               final int goal_x, final int goal_y, final int goal_z,
                               final int timeoutSeconds, final long reportEveryMilliseconds,
                               Class<? extends SearchImage> searchImageType,
                               SearchCost costFunction, SearchHeuristic heuristic)
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
                SupplierUtil.createSupplier(searchImageType, BidirectionalSearchNode.class, imgWidth, imgHeight));
        init();
    }

    public BidirectionalSearch(final SNT snt, final ImagePlus imagePlus,
                               final int start_x, final int start_y, final int start_z,
                               final int goal_x, final int goal_y, final int goal_z,
                               SearchCost costFunction, SearchHeuristic heuristic)
    {
        this(snt, ImageJFunctions.wrapReal(imagePlus), start_x, start_y, start_z, goal_x, goal_y, goal_z,
                costFunction, heuristic);
    }

    public BidirectionalSearch(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image,
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
                SupplierUtil.createSupplier(snt.searchImageType, BidirectionalSearchNode.class, imgWidth, imgHeight));
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

            BidirectionalSearchNode start = new BidirectionalSearchNode(start_x, start_y, start_z);
            BidirectionalSearchNode goal = new BidirectionalSearchNode(goal_x, goal_y, goal_z);

            start.gFromStart = 0d;
            goal.gFromGoal = 0d;

            double bestFScoreFromStart =
                    heuristic.estimateCostToGoal(start.x, start.y, start.z, goal.x, goal.y, goal.z) *
                            costFunction.minimumCostPerUnitDistance();
            double bestFScoreFromGoal =
                    heuristic.estimateCostToGoal(goal.x, goal.y, goal.z, start.x, start.y, start.z) *
                            costFunction.minimumCostPerUnitDistance();

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

            this.imgPosition = new int[3];

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

                if (open_from_start.size() < open_from_goal.size()) {

                    BidirectionalSearchNode p;

                    p = open_from_start.deleteMin().getKey();
                    p.heapHandleFromStart = null;
                    closed_from_start_count++;

                    bestFScoreFromStart = p.fFromStart;

                    if (p.gFromStart + heuristic.estimateCostToGoal(p.x, p.y, p.z, goal.x, goal.y, goal.z) *
                            costFunction.minimumCostPerUnitDistance()
                            >= bestPathLength
                            ||
                            p.gFromStart + bestFScoreFromGoal -
                                    heuristic.estimateCostToGoal(p.x, p.y, p.z, start.x, start.y, start.z) *
                                            costFunction.minimumCostPerUnitDistance()
                                    >= bestPathLength) {

                        p.state = REJECTED;

                    } else {

                        p.state = STABILIZED;
                        expandNeighbors(p, true);
                    }

                } else {

                    BidirectionalSearchNode p;

                    p = open_from_goal.deleteMin().getKey();
                    p.heapHandleFromGoal = null;
                    closed_from_goal_count++;

                    bestFScoreFromGoal = p.fFromGoal;

                    if (p.gFromGoal + heuristic.estimateCostToGoal(p.x, p.y, p.z, start.x, start.y, start.z) *
                            costFunction.minimumCostPerUnitDistance()
                            >= bestPathLength
                            ||
                            p.gFromGoal + bestFScoreFromStart -
                                    heuristic.estimateCostToGoal(p.x, p.y, p.z, goal.x, goal.y, goal.z) *
                                            costFunction.minimumCostPerUnitDistance()
                                    >= bestPathLength) {

                        p.state = REJECTED;

                    } else {

                        p.state = STABILIZED;
                        expandNeighbors(p, false);
                    }

                }

            }

            if (touchNode == null) {
                if (verbose) System.out.println("Touch node is null, returning null result");
                reportFinished(false);
                return;
            }

            // Success
            if (verbose) {
                System.out.println("Searches met!");
                System.out.println("Cost for path = " + bestPathLength);
                System.out.println("Total loops = " + loops);
                System.out.println("Remaining open from start = " + open_from_start.size());
                System.out.println("Remaining open from goal = " + open_from_goal.size());
                System.out.println("Closed from start = " + closed_from_start_count);
                System.out.println("Closed from goal = " + closed_from_goal_count);
            }

            result = reconstructPath(xSep, ySep, zSep, spacing_units);
            reportFinished(true);

        } catch (Exception ex) {
            SNTUtils.error("Exception during search", ex);
            reportFinished(false);
        }

    }

    protected void expandNeighbors(final BidirectionalSearchNode p, final boolean fromStart) {

        for (int zdiff = -1; zdiff <= 1; zdiff++) {
            final int new_z = p.z + zdiff;
            if (new_z < intervalMin[2] || new_z > intervalMax[2]) continue;
            SearchImage<BidirectionalSearchNode> currentSlice = nodes_as_image.getSlice(new_z);
            if (currentSlice == null) {
                currentSlice = nodes_as_image.newSlice(new_z);
            }
            for (int xdiff = -1; xdiff <= 1; xdiff++) {
                for (int ydiff = -1; ydiff <= 1; ydiff++) {
                    if ((xdiff == 0) && (ydiff == 0) && (zdiff == 0)) continue;
                    final int new_x = p.x + xdiff;
                    final int new_y = p.y + ydiff;
                    if (new_x < intervalMin[0] || new_x > intervalMax[0]) continue;
                    if (new_y < intervalMin[1] || new_y > intervalMax[1]) continue;

                    BidirectionalSearchNode alreadyThereInEitherSearch = currentSlice.getValue(new_x, new_y);

                    final double xdiffsq = (xdiff * xSep) * (xdiff * xSep);
                    final double ydiffsq = (ydiff * ySep) * (ydiff * ySep);
                    final double zdiffsq = (zdiff * zSep) * (zdiff * zSep);

                    imgPosition[0] = new_x;
                    imgPosition[1] = new_y;
                    imgPosition[2] = new_z;
                    double value_at_new_point = imgAccess.setPositionAndGet(imgPosition).getRealDouble();
                    double cost_moving_to_new_point = costFunction.costMovingTo(value_at_new_point);

                    if (cost_moving_to_new_point < costFunction.minimumCostPerUnitDistance()) {
                        cost_moving_to_new_point = costFunction.minimumCostPerUnitDistance();
                    }

                    if (fromStart) {

                        final double g_for_new_point = p.gFromStart +
                                Math.sqrt(xdiffsq + ydiffsq + zdiffsq) * cost_moving_to_new_point;

                        final double h_for_new_point =
                                heuristic.estimateCostToGoal(new_x, new_y, new_z, goal_x, goal_y, goal_z) *
                                        costFunction.minimumCostPerUnitDistance();

                        final double f_for_new_point = g_for_new_point + h_for_new_point;

                        if (alreadyThereInEitherSearch == null) {

                            alreadyThereInEitherSearch = new BidirectionalSearchNode(new_x, new_y, new_z,
                                    f_for_new_point, Double.POSITIVE_INFINITY, // fFromStart, fFromGoal
                                    g_for_new_point, Double.POSITIVE_INFINITY, // gFromStart, gFromGoal
                                    p, null); // predecessorFromStart, predecessorFromGoal

                            alreadyThereInEitherSearch.heapHandleFromStart = open_from_start.insert(
                                    alreadyThereInEitherSearch);

                            nodes_as_image.getSlice(new_z).setValue(new_x, new_y, alreadyThereInEitherSearch);

                        } else if (alreadyThereInEitherSearch.fFromStart > f_for_new_point) {
                            alreadyThereInEitherSearch.gFromStart = g_for_new_point;
                            alreadyThereInEitherSearch.fFromStart = g_for_new_point + h_for_new_point;
                            alreadyThereInEitherSearch.predecessorFromStart = p;
                            if (alreadyThereInEitherSearch.heapHandleFromStart == null) {
                                alreadyThereInEitherSearch.heapHandleFromStart =
                                        open_from_start.insert(alreadyThereInEitherSearch);
                            } else {
                                alreadyThereInEitherSearch.heapHandleFromStart.decreaseKey(alreadyThereInEitherSearch);
                            }

                            final double pathLength = alreadyThereInEitherSearch.gFromStart +
                                    alreadyThereInEitherSearch.gFromGoal;

                            if (pathLength < bestPathLength) {
                                bestPathLength = pathLength;
                                touchNode = alreadyThereInEitherSearch;
                            }

                        }

                    } else {

                        final double g_for_new_point = p.gFromGoal +
                                Math.sqrt(xdiffsq + ydiffsq + zdiffsq) * cost_moving_to_new_point;

                        final double h_for_new_point =
                                heuristic.estimateCostToGoal(new_x, new_y, new_z, start_x, start_y, start_z) *
                                        costFunction.minimumCostPerUnitDistance();

                        final double f_for_new_point = g_for_new_point + h_for_new_point;

                        if (alreadyThereInEitherSearch == null) {

                            alreadyThereInEitherSearch = new BidirectionalSearchNode(new_x, new_y, new_z,
                                    Double.POSITIVE_INFINITY, f_for_new_point,
                                    Double.POSITIVE_INFINITY, g_for_new_point,
                                    null, p);

                            alreadyThereInEitherSearch.heapHandleFromGoal = open_from_goal.insert(
                                    alreadyThereInEitherSearch);

                            nodes_as_image.getSlice(new_z).setValue(new_x, new_y, alreadyThereInEitherSearch);

                        } else if (alreadyThereInEitherSearch.fFromGoal > f_for_new_point) {
                            alreadyThereInEitherSearch.gFromGoal = g_for_new_point;
                            alreadyThereInEitherSearch.fFromGoal = g_for_new_point + h_for_new_point;
                            alreadyThereInEitherSearch.predecessorFromGoal = p;
                            if (alreadyThereInEitherSearch.heapHandleFromGoal == null) {
                                alreadyThereInEitherSearch.heapHandleFromGoal =
                                        open_from_goal.insert(alreadyThereInEitherSearch);
                            } else {
                                alreadyThereInEitherSearch.heapHandleFromGoal.decreaseKey(alreadyThereInEitherSearch);
                            }

                            final double pathLength = alreadyThereInEitherSearch.gFromStart +
                                    alreadyThereInEitherSearch.gFromGoal;

                            if (pathLength < bestPathLength) {
                                bestPathLength = pathLength;
                                touchNode = alreadyThereInEitherSearch;
                            }

                        }

                    }

                }
            }
        }
    }

    protected Path reconstructPath(final double x_spacing,
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

        path.addPointDouble(touchNode.x * x_spacing, touchNode.y * y_spacing,
                touchNode.z * z_spacing);

        for (BidirectionalSearchNode n : backwardsPath) {
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
    protected BidirectionalSearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
                                                            final double threshold) {
        final SearchImage<BidirectionalSearchNode> slice = nodes_as_image.getSlice(z);
        if (slice == null) {
            return null;
        }
        return slice.getValue(x, y);
    }

    @Override
    public void drawProgressOnSlice(final int plane,
                                    final int currentSliceInPlane, final TracerCanvas canvas, final Graphics g) {

        for (int i = 0; i < 2; ++i) {

            final byte stabilized_status = (i == 0) ? STABILIZED : REJECTED;
            final Color c = (i == 0) ? openColor : closedColor;
            if (c == null) continue;

            g.setColor(c);

            int pixel_size = (int) canvas.getMagnification();
            if (pixel_size < 1) pixel_size = 1;

            if (plane == MultiDThreePanes.XY_PLANE) {
                for (int y = 0; y < imgHeight; ++y)
                    for (int x = 0; x < imgWidth; ++x) {
                        final BidirectionalSearchNode n = anyNodeUnderThreshold(x, y, currentSliceInPlane,
                                drawingThreshold);
                        if (n == null) continue;
                        if (n.state == stabilized_status) {
                            g.fillRect(
                                    canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(y) -
                                            pixel_size / 2, pixel_size, pixel_size);
                        }
                    }
            } else if (plane == MultiDThreePanes.XZ_PLANE) {
                for (int z = 0; z < imgDepth; ++z)
                    for (int x = 0; x < imgWidth; ++x) {
                        final BidirectionalSearchNode n = anyNodeUnderThreshold(x, currentSliceInPlane, z,
                                drawingThreshold);
                        if (n == null) continue;
                        if ((n.state == stabilized_status)) {
                            g.fillRect(
                                    canvas.myScreenX(x) - pixel_size / 2, canvas.myScreenY(z) -
                                            pixel_size / 2, pixel_size, pixel_size);
                        }
                    }
            } else if (plane == MultiDThreePanes.ZY_PLANE) {
                for (int y = 0; y < imgHeight; ++y)
                    for (int z = 0; z < imgDepth; ++z) {
                        final BidirectionalSearchNode n = anyNodeUnderThreshold(currentSliceInPlane, y, z,
                                drawingThreshold);
                        if (n == null) continue;
                        if ((n.state == stabilized_status)) {
                            g.fillRect(
                                    canvas.myScreenX(z) - pixel_size / 2, canvas.myScreenY(y) -
                                            pixel_size / 2, pixel_size, pixel_size);
                        }
                    }
            }
        }
    }

    static class NodeComparatorFromStart implements Comparator<BidirectionalSearchNode> {

        public int compare(BidirectionalSearchNode n1, BidirectionalSearchNode n2) {
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


    static class NodeComparatorFromGoal implements Comparator<BidirectionalSearchNode> {

        public int compare(BidirectionalSearchNode n1, BidirectionalSearchNode n2) {
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
