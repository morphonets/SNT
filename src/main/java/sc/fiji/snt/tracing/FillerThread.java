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

package sc.fiji.snt.tracing;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.*;
import sc.fiji.snt.tracing.cost.*;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;

import java.util.*;

/**
 * Seeded-volume segmentation via single-source shortest paths. Path nodes are used as seed points in an open-ended
 * variant of Dijkstra's algorithm. The threshold sets the maximum allowable distance for a node to be included in the
 * {@link Fill}. This distance is represented in the g-score of a node, which is the length of the shortest path from a
 * seed point to that node. The magnitudes of these distances are heavily dependent on the supplied cost function
 * {@link Cost}, so the threshold should be set with a particular cost function in mind. It often helps to adjust
 * the threshold interactively.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @author Mark Longair
 */
public class FillerThread extends SearchThread {

    double threshold;
    Set<Path> sourcePaths;
    private Set<DefaultSearchNode> aboveThresholdNodeSet;


    public FillerThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
                        final double initialThreshold, final Cost costFunction)
    {
        this(image, calibration, initialThreshold, 0, 0, costFunction);
    }

    public FillerThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
                        final double initialThreshold, final long reportEveryMilliseconds,
                        final Cost costFunction)
    {
        this(image, calibration, initialThreshold, 0, reportEveryMilliseconds, costFunction);
    }

    public FillerThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
                        final double initialThreshold, int timeoutSeconds, final long reportEveryMilliseconds,
                        final Cost costFunction)
    {
        this(image, calibration, initialThreshold, timeoutSeconds, reportEveryMilliseconds,
                costFunction, SNT.SearchImageType.MAP);

        setThreshold(initialThreshold);
    }

    /* If you specify 0 for timeoutSeconds then there is no timeout. */

    public FillerThread(final RandomAccessibleInterval<? extends RealType<?>> image, final Calibration calibration,
                        final double initialThreshold, int timeoutSeconds, final long reportEveryMilliseconds,
                        final Cost costFunction, final SNT.SearchImageType searchImageType)
    {
        super(image, calibration, false, false, timeoutSeconds, reportEveryMilliseconds,
                searchImageType, costFunction);

        setThreshold(initialThreshold);
    }

    // FIXME: may be buggy, synchronization issues

    public static FillerThread fromFill(final RandomAccessibleInterval<? extends RealType<?>> image,
                                        final Calibration calibration, final ImageStatistics stats, final Fill fill)
    {
        SNTUtils.log("loading a fill with threshold: " + fill.getThreshold() +
                ", metric: " + fill.getMetric().toString());
        final Cost cost;
        switch (fill.getMetric()) {
            case RECIPROCAL:
                cost = new Reciprocal(stats.min, stats.max);
                break;
            case DIFFERENCE:
                cost = new Difference(stats.min, stats.max);
                break;
            case DIFFERENCE_SQUARED:
                cost = new DifferenceSq(stats.min, stats.max);
                break;
            case PROBABILITY:
                cost = new OneMinusErf(stats.max, stats.mean, stats.stdDev);
                break;
            default:
                throw new IllegalArgumentException("Unknown cost: " + fill.getMetric());
        }
        final FillerThread result = new FillerThread(image, calibration,
                fill.getThreshold(), 1000, cost);

        final ArrayList<DefaultSearchNode> tempNodes = new ArrayList<>();

        for (final Fill.Node n : fill.getNodeList()) {

            final DefaultSearchNode s = new DefaultSearchNode(n.x, n.y, n.z, (float) n.distance, 0,
                    null, SearchThread.FREE);
            tempNodes.add(s);
        }

        for (int i = 0; i < tempNodes.size(); ++i) {
            final Fill.Node n = fill.getNodeList().get(i);
            final DefaultSearchNode s = tempNodes.get(i);
            if (n.previous >= 0) {
                s.setPredecessor(tempNodes.get(n.previous));
            }
            if (n.open) {
                s.searchStatus = OPEN_FROM_START;
            } else {
                s.searchStatus = CLOSED_FROM_START;
            }
            result.addNode(s, true);
        }
        result.setSourcePaths(fill.getSourcePaths());
        return result;
    }

    public static FillerThread fromFill(final ImagePlus imagePlus, final ImageStatistics stats, final Fill fill) {
        return fromFill(ImageJFunctions.wrapReal(imagePlus), imagePlus.getCalibration(), stats, fill);
    }

    public double getDistanceAtPoint(final double xd, final double yd,
                                     final double zd)
    {

        final int x = (int) Math.round(xd);
        final int y = (int) Math.round(yd);
        final int z = (int) Math.round(zd);

        final SearchImage<DefaultSearchNode> slice = nodes_as_image_from_start.getSlice(z);
        if (slice == null) {
            return -1.0;
        }
        final DefaultSearchNode n = slice.getValue(x, y);
        if (n == null) {
            return -1.0;
        } else return n.g;
    }

    public Fill getFill() {

        final Hashtable<DefaultSearchNode, Integer> h = new Hashtable<>();

        final ArrayList<DefaultSearchNode> a = new ArrayList<>();

        // The tricky bit here is that we want to create a
        // Fill object with index

        int i = 0;
        for (final SearchImage<DefaultSearchNode> slice : nodes_as_image_from_start) {
            if (slice == null) continue;
            for (final DefaultSearchNode current : slice) {
                if (current != null) {
                    h.put(current, i);
                    a.add(current);
                    ++i;
                }
            }
        }

        final Fill fill = new Fill();


        fill.setThreshold(threshold);
        // FIXME
        if (costFunction.getClass().equals(Reciprocal.class))
            fill.setMetric(SNT.CostType.RECIPROCAL);
        else if (costFunction.getClass().equals(Difference.class))
            fill.setMetric(SNT.CostType.DIFFERENCE);
        else if (costFunction.getClass().equals(DifferenceSq.class))
            fill.setMetric(SNT.CostType.DIFFERENCE_SQUARED);
        else if (costFunction.getClass().equals(OneMinusErf.class))
            fill.setMetric(SNT.CostType.PROBABILITY);
        else
            throw new IllegalArgumentException("Unknown cost " + costFunction.getClass());

        fill.setSpacing(xSep, ySep, zSep, spacing_units);

        SNTUtils.log("... out of a.size() " + a.size() + " entries");

        for (i = 0; i < a.size(); ++i) {
            final DefaultSearchNode f = a.get(i);
            int previousIndex = -1;
            final DefaultSearchNode previous = f.getPredecessor();
            if (previous != null) {
                final Integer p = h.get(previous);
                if (p != null) {
                    previousIndex = p;
                }
            }
            boolean open;
            if (f.searchStatus == SearchThread.OPEN_FROM_START ||
                    f.searchStatus == SearchThread.OPEN_FROM_GOAL)
            {
                open = true;
            }
            else if (f.searchStatus == SearchThread.CLOSED_FROM_START ||
                    f.searchStatus == SearchThread.CLOSED_FROM_GOAL)
            {
                open = false;
            }
            else
            {
                throw new IllegalStateException("Somehow a FREE node is in the Fill.");
            }
            fill.add(f.x, f.y, f.z, f.g, previousIndex, open);
        }

        if (sourcePaths != null) {
            fill.setSourcePaths(sourcePaths);
        }

        return fill;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(final double threshold) {
        this.threshold = threshold;
    }

    public void setSourcePaths(final Collection<Path> newSourcePaths) {
        sourcePaths = new HashSet<>();
        sourcePaths.addAll(newSourcePaths);
        for (final Path p : newSourcePaths) {
            if (p == null) return;
            for (int k = 0; k < p.size(); ++k) {
                final DefaultSearchNode f = new DefaultSearchNode(p.getXUnscaled(k), p.getYUnscaled(
                        k), p.getZUnscaled(k), 0, 0, null, OPEN_FROM_START);
                addNode(f, true);
            }
        }
    }

    @Override
    public void run() {

        try {

            if (verbose) {
                SNTUtils.log("New FillerThread running!");
                printStatus();
            }

            started_at = lastReportMilliseconds = System.currentTimeMillis();

            aboveThresholdNodeSet = new HashSet<>();

            while (!open_from_start.isEmpty()) {

                if (Thread.currentThread().isInterrupted()) {
                    setExitReason(CANCELLED);
                    reportFinished(false);
                    return;
                }

                ++loops;

                // We only check every ten-thousandth loop for
                // whether we should report the progress, etc.
                if (0 == (loops % 10000) && checkStatus()) {
                    SNTUtils.log("FillerThread timed out...");
                    setExitReason(TIMED_OUT);
                    reportFinished(false);
                    return;
                }

                final DefaultSearchNode p = open_from_start.deleteMin().getKey();
                if (p == null) continue;

                p.searchStatus = CLOSED_FROM_START;
                closed_from_start_count++;

                expandNeighbors(p);

            }

            // For nodes that are above-threshold, add them back into the open set
            //  so that we may resume progress using this same instance.
            for (DefaultSearchNode newNode : aboveThresholdNodeSet) {
                // Is this newNode really new?
                SearchImage<DefaultSearchNode> slice = nodes_as_image_from_start.getSlice(newNode.z);
                if (slice == null) {
                    slice = nodes_as_image_from_start.newSlice(newNode.z);
                }
                // Only add them if they are better than the current nodes (if they exist)
                testNeighbor(newNode, slice);
            }

            SNTUtils.log("Fill complete for thread " + Thread.currentThread());
            setExitReason(SUCCESS);
            reportFinished(true);

        } catch (final OutOfMemoryError e) {
            SNTUtils.error("Out of memory, try splitting the work across multiple FillerThread instances.", e);
        }
    }

    private void expandNeighbors(final DefaultSearchNode p) {
        for (int zdiff = -1; zdiff <= 1; ++zdiff) {
            final int new_z = p.z + zdiff;
            // We check whether the neighbor is outside the bounds of the min-max of the interval,
            //  which may or may not have the origin at (0, 0, 0)
            if (new_z < zMin || new_z > zMax) continue;
            SearchImage<DefaultSearchNode> slice = nodes_as_image_from_start.getSlice(new_z);
            if (slice == null) {
                slice = nodes_as_image_from_start.newSlice(new_z);
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
                    final double g_for_new_point = p.g + Math.sqrt(
                                    Math.pow(xdiff * xSep, 2) +
                                    Math.pow(ydiff * ySep, 2) +
                                    Math.pow(zdiff * zSep, 2))
                            * cost_moving_to_new_point;

                    final DefaultSearchNode newNode = createNewNode(
                            new_x,
                            new_y,
                            new_z,
                            g_for_new_point,
                            0,
                            p,
                            FREE);

                    // TODO add an option to just let it run indefinitely,
                    //  but be wary of memory use
                    if (g_for_new_point > threshold) {
                        // Only fill up to the threshold
                        aboveThresholdNodeSet.add(newNode);
                        continue;
                    }

                    testNeighbor(newNode, slice);
                }
            }
        }
    }

    private void testNeighbor(final DefaultSearchNode newNode, final SearchImage<DefaultSearchNode> slice) {
        // Is this newNode really new?
        final DefaultSearchNode alreadyThereInThisSearch = slice.getValue(newNode.x, newNode.y);

        if (alreadyThereInThisSearch == null) {
            newNode.searchStatus = OPEN_FROM_START;
            newNode.heapHandle = open_from_start.insert(newNode);
            slice.setValue(newNode.x, newNode.y, newNode);
        } else {

            // The other alternative is that this node is already in on of the lists working from the
            // start but has a better way of getting to that point.

            if (alreadyThereInThisSearch.g > newNode.g) {

                if (alreadyThereInThisSearch.searchStatus == OPEN_FROM_START) {
                    alreadyThereInThisSearch.setFrom(newNode);
                    alreadyThereInThisSearch.searchStatus = OPEN_FROM_START;
                    alreadyThereInThisSearch.heapHandle.decreaseKey(alreadyThereInThisSearch);
                } else if (alreadyThereInThisSearch.searchStatus == CLOSED_FROM_START) {
                    alreadyThereInThisSearch.setFrom(newNode);
                    alreadyThereInThisSearch.searchStatus = OPEN_FROM_START;
                    alreadyThereInThisSearch.heapHandle = open_from_start.insert(alreadyThereInThisSearch);
                }
            }
        }
    }

    public SearchImageStack<DefaultSearchNode> getNodesAsImage() {
        return nodes_as_image_from_start;
    }

    @Override
    protected void reportPointsInSearch() {

        super.reportPointsInSearch();

        // Find the minimum distance in the open list.
        final DefaultSearchNode p = open_from_start.findMin().getKey();
        if (p == null) return;

        final double minimumDistanceInOpen = p.g;

        for (final SearchProgressCallback progress : progressListeners) {
            if (progress instanceof FillerProgressCallback) {
                final FillerProgressCallback fillerProgress =
                        (FillerProgressCallback) progress;
                fillerProgress.maximumDistanceCompletelyExplored(this,
                        (float) minimumDistanceInOpen);
            }
        }

    }

    @Override
    public Path getResult() {
        throw new IllegalStateException("BUG: attempted to retrieve a Path from Filler");
    }

    @Override
    public void reportFinished(boolean success) {
        super.reportFinished(success);
    }
}
