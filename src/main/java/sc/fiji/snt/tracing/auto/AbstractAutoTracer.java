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

package sc.fiji.snt.tracing.auto;

import ij.gui.Roi;
import net.imagej.ops.OpService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.Logger;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;

/**
 * Abstract base class for grayscale-based automatic neuron tracers.
 * <p>
 * Provides common functionality for soma ROI handling, graph utilities, and
 * tree construction. Subclasses implement specific tracing algorithms that
 * operate directly on grayscale images without binarization.
 * </p>
 *
 * @author Tiago Ferreira
 * @see GWDTTracer
 */
public abstract class AbstractAutoTracer implements AutoTracer {

    protected Roi somaRoi;
    protected int somaRoiZPosition = -1;  // Z-slice (0-indexed), -1 if unset
    protected int rootStrategy = ROI_UNSET;
    protected Logger logger;
    protected boolean verbose = false;

    /**
     * Computes EDT where TRUE = background, so result gives distance to nearest background.
     */
    private static Img<FloatType> computeEDT(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing,
            boolean[] hasForegroundOut) {

        final int nDims = source.numDimensions();
        final long[] dims = new long[nDims];
        source.dimensions(dims);

        // Validate spacing
        final double[] calibration = new double[nDims];
        for (int d = 0; d < nDims; d++) {
            calibration[d] = (spacing != null && d < spacing.length && spacing[d] > 0)
                    ? spacing[d]
                    : 1.0;
        }

        // Create mask: TRUE = background
        final Img<BitType> mask = ArrayImgs.bits(dims);
        final Cursor<? extends RealType<?>> srcCursor = Views.flatIterable(source).localizingCursor();
        final net.imglib2.RandomAccess<BitType> maskRA = mask.randomAccess();

        boolean hasForeground = false;
        while (srcCursor.hasNext()) {
            srcCursor.fwd();
            maskRA.setPosition(srcCursor);
            if (srcCursor.get().getRealDouble() <= threshold) {
                maskRA.get().set(true);
            } else {
                hasForeground = true;
            }
        }

        if (hasForegroundOut != null && hasForegroundOut.length > 0) {
            hasForegroundOut[0] = hasForeground;
        }

        if (!hasForeground) {
            return null;
        }

        final Img<FloatType> edt = ArrayImgs.floats(dims);
        DistanceTransform.binaryTransform(mask, edt, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN, calibration);
        return edt;
    }

    /**
     * Finds the thickest point in a foreground structure using the Euclidean
     * Distance Transform (EDT).
     * <p>
     * This is more robust than simply finding the brightest pixel because:
     * <ul>
     *   <li>Hot pixels/artifacts are typically 1 voxel thick → small EDT value</li>
     *   <li>The soma, being the thickest structure, has the largest EDT value</li>
     *   <li>Less sensitive to intensity variations and uneven illumination</li>
     * </ul>
     * </p>
     *
     * @param source    the input image
     * @param threshold pixels above this value are considered foreground
     * @param spacing   voxel spacing [x, y, z] for proper distance calculation
     * @return the position of the thickest point in voxel coordinates, or null if
     * no foreground pixels exist
     */
    public static long[] findThickestPoint(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {

        final Img<FloatType> edt = computeEDT(source, threshold, spacing, null);
        if (edt == null) return null;

        // Find max EDT
        double maxEDT = Double.NEGATIVE_INFINITY;
        final long[] maxPos = new long[source.numDimensions()];
        final Cursor<FloatType> edtCursor = Views.flatIterable(edt).localizingCursor();

        while (edtCursor.hasNext()) {
            edtCursor.fwd();
            final double val = edtCursor.get().getRealDouble();
            if (val > maxEDT) {
                maxEDT = val;
                edtCursor.localize(maxPos);
            }
        }
        return maxPos;
    }

    /**
     * Finds the thickest point and converts to physical coordinates.
     *
     * @param source    the input image
     * @param threshold pixels above this value are considered foreground
     * @param spacing   voxel spacing [x, y, z]
     * @return the position in physical coordinates, or null if no foreground
     */
    public static double[] findThickestPointPhysical(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {

        final long[] voxelPos = findThickestPoint(source, threshold, spacing);
        if (voxelPos == null) return null;

        final double[] physicalPos = new double[voxelPos.length];
        for (int d = 0; d < voxelPos.length; d++) {
            physicalPos[d] = voxelPos[d] * (spacing != null && d < spacing.length ? spacing[d] : 1.0);
        }
        return physicalPos;
    }

    /**
     * Automatically detects the most likely root point (soma center) in a neuronal image.
     * <p>
     * This method uses a combined thickness-intensity approach that is robust to common
     * imaging artifacts and variations in signal distribution. The algorithm:
     * <ol>
     *   <li>Computes a binary mask using the specified threshold</li>
     *   <li>Calculates the Euclidean Distance Transform (EDT) to find distance to background</li>
     *   <li>Scores each foreground pixel as: {@code EDT_value × normalized_intensity}</li>
     *   <li>Returns the position with the maximum score</li>
     * </ol>
     * </p>
     * <p>
     * This combined scoring naturally favors the soma because:
     * <ul>
     *   <li>Soma is typically the <b>thickest</b> structure → high EDT value</li>
     *   <li>Soma usually has <b>reasonable intensity</b> → contributes to score</li>
     *   <li>Hot pixels/artifacts are tiny → low EDT despite high intensity</li>
     *   <li>Thin neurites have low EDT → low score even if bright</li>
     * </ul>
     * </p>
     *
     * @param source    the input image (grayscale)
     * @param threshold pixels above this value are considered foreground. If {@code NaN},
     *                  the mean intensity is used. If negative, Otsu's method is applied.
     * @param spacing   voxel spacing [x, y, z] for proper distance calculation. If null,
     *                  isotropic spacing of 1.0 is assumed.
     * @return the detected root position in <b>voxel coordinates</b>, or null if detection fails
     * (e.g., no foreground pixels, empty image)
     * @see #findRootPhysical(RandomAccessibleInterval, double, double[])
     * @see #findThickestPoint(RandomAccessibleInterval, double, double[])
     */
    public static long[] findRoot(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {

        if (source == null) {
            return null;
        }

        final int nDims = source.numDimensions();
        final long[] dims = new long[nDims];
        source.dimensions(dims);

        // Compute intensity statistics
        final double[] stats = ImgUtils.computeIntensityStats(source);
        final double minIntensity = stats[0];
        final double maxIntensity = stats[1];
        final double meanIntensity = stats[2];

        if (maxIntensity <= minIntensity) {
            return null;  // Uniform image
        }

        // Determine effective threshold
        final double effectiveThreshold;
        if (Double.isNaN(threshold)) {
            effectiveThreshold = meanIntensity;
        } else if (threshold < 0) {
            effectiveThreshold = SomaUtils.computeOtsuThreshold(source);
        } else {
            effectiveThreshold = threshold;
        }

        // Compute EDT
        final boolean[] hasForeground = {false};
        final Img<FloatType> edt = computeEDT(source, effectiveThreshold, spacing, hasForeground);
        if (edt == null || !hasForeground[0]) {
            return null;
        }

        // Find pixel with maximum score = EDT × normalized_intensity
        final Cursor<? extends RealType<?>> scoreCursor = Views.flatIterable(source).localizingCursor();
        final net.imglib2.RandomAccess<FloatType> edtRA = edt.randomAccess();

        double maxScore = Double.NEGATIVE_INFINITY;
        final long[] maxPos = new long[nDims];
        final double intensityRange = maxIntensity - minIntensity;

        while (scoreCursor.hasNext()) {
            scoreCursor.fwd();
            final double intensity = scoreCursor.get().getRealDouble();

            // Skip background pixels
            if (intensity <= effectiveThreshold) {
                continue;
            }

            edtRA.setPosition(scoreCursor);
            final double edtValue = edtRA.get().getRealDouble();

            // Skip pixels at structure edges (EDT ≈ 0)
            if (edtValue <= 0) {
                continue;
            }

            // Combined score: thickness × normalized intensity
            final double normalizedIntensity = (intensity - minIntensity) / intensityRange;
            final double score = edtValue * normalizedIntensity;

            if (score > maxScore) {
                maxScore = score;
                scoreCursor.localize(maxPos);
            }
        }

        return maxScore > Double.NEGATIVE_INFINITY ? maxPos : null;
    }

    /**
     * Estimates background threshold using combined range and percentile heuristics.
     * <p>
     * Averages two estimates:
     * <ul>
     *   <li>5% into intensity range (robust to camera offset)</li>
     *   <li>90th percentile (robust to signal density)</li>
     * </ul>
     *
     * @param source the input image
     * @return estimated background threshold
     */
    protected static double estimateBackgroundThreshold(final RandomAccessibleInterval<? extends RealType<?>> source) {
        // Use the mean of 5% into range, 90th percentile
        final double[] stats = ImgUtils.computeIntensityStats(source);
        final double rangeBasedThreshold = stats[0] + 0.05 * (stats[1] - stats[0]);
        final double percentileThreshold = ImgUtils.computePercentile(source, 90);
        return(rangeBasedThreshold + percentileThreshold) / 2;
    }

    /**
     * Automatically detects the root point and returns physical coordinates.
     *
     * @param source    the input image (grayscale)
     * @param threshold pixels above this value are considered foreground. If {@code NaN},
     *                  the mean intensity is used. If negative, Otsu's method is applied.
     * @param spacing   voxel spacing [x, y, z] for proper distance calculation
     * @return the detected root position in <b>physical coordinates</b>, or null if detection fails
     * @see #findRoot(RandomAccessibleInterval, double, double[])
     */
    public static double[] findRootPhysical(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double threshold,
            final double[] spacing) {

        final long[] voxelPos = findRoot(source, threshold, spacing);
        if (voxelPos == null) {
            return null;
        }

        final double[] physicalPos = new double[voxelPos.length];
        for (int d = 0; d < voxelPos.length; d++) {
            physicalPos[d] = voxelPos[d] * (spacing != null && d < spacing.length ? spacing[d] : 1.0);
        }
        return physicalPos;
    }

    /**
     * Automatically detects the root point using automatic thresholding (Otsu's method).
     * <p>
     * Convenience method equivalent to {@code findRoot(source, -1, spacing)}.
     * </p>
     *
     * @param source  the input image (grayscale)
     * @param spacing voxel spacing [x, y, z]
     * @return the detected root position in voxel coordinates, or null if detection fails
     * @see #findRoot(RandomAccessibleInterval, double, double[])
     */
    public static long[] findRoot(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing) {
        return findRoot(source, -1, spacing);
    }

    /**
     * Automatically detects the root point using automatic thresholding and returns
     * physical coordinates.
     * <p>
     * Convenience method equivalent to {@code findRootPhysical(source, -1, spacing)}.
     * </p>
     *
     * @param source  the input image (grayscale)
     * @param spacing voxel spacing [x, y, z]
     * @return the detected root position in physical coordinates, or null if detection fails
     * @see #findRootPhysical(RandomAccessibleInterval, double, double[])
     */
    public static double[] findRootPhysical(
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing) {
        return findRootPhysical(source, -1, spacing);
    }

    /**
     * Traces the structure and returns a DirectedWeightedGraph.
     *
     * @return the traced graph
     */
    public abstract DirectedWeightedGraph traceToGraph();

    // ==================== Soma ROI Processing (Graph-based) ====================

    /**
     * Gets the voxel spacing used by this tracer.
     *
     * @return array of [x, y, z] spacing values
     */
    protected abstract double[] getSpacing();

    /**
     * Gets the image dimensions.
     *
     * @return array of dimension sizes
     */
    protected abstract long[] getDimensions();

    /**
     * Sets the soma ROI and rooting strategy.
     *
     * @param roi      area ROI delineating the soma (null to disable)
     * @param strategy one of {@link #ROI_UNSET}, {@link #ROI_EDGE} (assumed area ROI),
     *                 {@link #ROI_CENTROID}, or {@link #ROI_CENTROID_WEIGHTED}
     */
    public void setSomaRoi(final Roi roi, final int strategy) {
        if (strategy != ROI_UNSET && strategy != ROI_EDGE &&
                strategy != ROI_CENTROID && strategy != ROI_CENTROID_WEIGHTED &&
                strategy != ROI_CONTAINED) {
            throw new IllegalArgumentException("Invalid root strategy: " + strategy);
        }
        if (strategy != ROI_UNSET && roi == null) {
            throw new IllegalArgumentException("ROI required for non-UNSET strategy");
        }
        if (strategy == ROI_EDGE && !roi.isArea()) {
            throw new IllegalArgumentException("Area ROI required for ROI_EDGE strategy");
        }
        this.somaRoi = roi;
        this.rootStrategy = strategy;
        this.somaRoiZPosition = (roi != null && roi.getZPosition() > 0) ? roi.getZPosition() - 1 : -1; // Convert to 0-indexed
    }

    /**
     * Gets the current soma ROI.
     *
     * @return the soma ROI, or null if not set
     */
    public Roi getSomaRoi() {
        return somaRoi;
    }

    /**
     * Sets the soma ROI using the default strategy (ROI_CENTROID).
     *
     * @param roi area ROI delineating the soma
     */
    public void setSomaRoi(final Roi roi) {
        setSomaRoi(roi, roi != null ? ROI_CENTROID : ROI_UNSET);
    }

    /**
     * Gets the current root strategy.
     *
     * @return the root strategy constant
     */
    public int getRootStrategy() {
        return rootStrategy;
    }

    /**
     * Checks if verbose logging is enabled.
     *
     * @return true if verbose
     */
    public boolean isVerbose() {
        return verbose;
    }

    // ==================== Utility Methods ====================

    /**
     * Sets verbose logging mode.
     *
     * @param verbose true to enable verbose logging
     */
    public void setVerbose(final boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Checks if a point is inside the soma ROI.
     *
     * @param point the point to check (physical coordinates)
     * @return true if inside the ROI
     */
    protected boolean isInsideSomaRoi(final SWCPoint point) {
        if (somaRoi == null) return false;

        final double[] spacing = getSpacing();
        final long[] dims = getDimensions();

        // Convert physical coordinates to pixel coordinates
        final double px = point.x / spacing[0];
        final double py = point.y / spacing[1];

        // Check Z position if ROI is slice-specific
        if (somaRoiZPosition >= 0 && dims.length > 2) {
            final double pz = point.z / spacing[2];
            if (Math.abs(pz - somaRoiZPosition) > 0.5) {
                return false;
            }
        }

        return somaRoi.contains((int) Math.round(px), (int) Math.round(py));
    }

    /**
     * Splits a graph at the soma boundary, creating separate trees for each neurite.
     *
     * @param graph the traced graph
     * @return list of trees, one per neurite exiting the soma
     */
    protected List<Tree> splitAtSomaBoundary(final DirectedWeightedGraph graph) {
        log("Splitting at soma boundary (ROI_EDGE strategy)...");

        // Find all nodes inside the soma
        final Set<SWCPoint> somaNodes = new HashSet<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (isInsideSomaRoi(v)) {
                somaNodes.add(v);
            }
        }
        log("  Found " + somaNodes.size() + " nodes inside soma ROI");

        if (somaNodes.isEmpty()) {
            log("  No nodes inside soma ROI, returning single tree");
            return Collections.singletonList(graph.getTree());
        }

        // Find edges crossing the soma boundary
        final Map<SWCWeightedEdge, SWCPoint> exitPoints = new HashMap<>();
        for (final SWCWeightedEdge edge : graph.edgeSet()) {
            final SWCPoint source = graph.getEdgeSource(edge);
            final SWCPoint target = graph.getEdgeTarget(edge);
            final boolean sourceInside = somaNodes.contains(source);
            final boolean targetInside = somaNodes.contains(target);

            if (sourceInside != targetInside) {
                exitPoints.put(edge, sourceInside ? target : source);
            }
        }
        log("  Found " + exitPoints.size() + " boundary crossings (neurites)");

        if (exitPoints.isEmpty()) {
            log("  No boundary crossings found, returning single tree");
            return Collections.singletonList(graph.getTree());
        }

        // Remove soma nodes
        graph.removeAllVertices(somaNodes);
        log("  Removed soma nodes, " + graph.vertexSet().size() + " nodes remaining");

        // Build trees from connected components
        final List<Tree> trees = new ArrayList<>();
        final Set<SWCPoint> processed = new HashSet<>();

        for (final SWCPoint exitPoint : exitPoints.values()) {
            if (processed.contains(exitPoint) || !graph.containsVertex(exitPoint)) continue;

            // BFS to find connected component
            final Set<SWCPoint> component = new HashSet<>();
            final Queue<SWCPoint> queue = new LinkedList<>();
            queue.add(exitPoint);

            while (!queue.isEmpty()) {
                final SWCPoint current = queue.poll();
                if (component.contains(current)) continue;
                component.add(current);

                // Add all neighbors
                for (final SWCWeightedEdge edge : graph.edgesOf(current)) {
                    final SWCPoint neighbor = graph.getEdgeSource(edge).equals(current)
                            ? graph.getEdgeTarget(edge)
                            : graph.getEdgeSource(edge);
                    if (!component.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            processed.addAll(component);

            // Create subgraph for this component
            if (!component.isEmpty()) {
                final DirectedWeightedGraph subgraph = new DirectedWeightedGraph();
                for (final SWCPoint v : component) {
                    subgraph.addVertex(v);
                }
                for (final SWCWeightedEdge edge : graph.edgeSet()) {
                    final SWCPoint src = graph.getEdgeSource(edge);
                    final SWCPoint tgt = graph.getEdgeTarget(edge);
                    if (component.contains(src) && component.contains(tgt)) {
                        final SWCWeightedEdge newEdge = subgraph.addEdge(src, tgt);
                        if (newEdge != null) {
                            subgraph.setEdgeWeight(newEdge, graph.getEdgeWeight(edge));
                        }
                    }
                }

                // Set the exit point as root
                try {
                    subgraph.setRoot(exitPoint);
                } catch (final Exception ignored) {
                }

                trees.add(subgraph.getTree());
            }
        }

        log("  Created " + trees.size() + " separate trees");
        return trees.isEmpty() ? Collections.singletonList(graph.getTree()) : trees;
    }

    /**
     * Collapses all soma nodes to the geometric ROI centroid.
     *
     * @param graph the graph to modify
     */
    protected void collapseSomaToRoiCentroid(final DirectedWeightedGraph graph) {
        log("Collapsing soma nodes to ROI centroid...");

        final double[] spacing = getSpacing();
        final double[] centroid = somaRoi.getContourCentroid();
        final double cx = centroid[0] * spacing[0];
        final double cy = centroid[1] * spacing[1];
        final double cz = somaRoiZPosition >= 0 ? somaRoiZPosition * spacing[2] : 0;

        collapseSomaNodes(graph, cx, cy, cz);
    }

    /**
     * Collapses all soma nodes to their weighted centroid.
     *
     * @param graph the graph to modify
     */
    protected void collapseSomaToWeightedCentroid(final DirectedWeightedGraph graph) {
        log("Collapsing soma nodes to weighted centroid...");

        // Find soma nodes and compute centroid
        final List<SWCPoint> somaNodes = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (isInsideSomaRoi(v)) {
                somaNodes.add(v);
            }
        }

        if (somaNodes.isEmpty()) {
            log("  No nodes inside soma ROI, skipping collapse");
            return;
        }

        double sumX = 0, sumY = 0, sumZ = 0;
        for (final SWCPoint node : somaNodes) {
            sumX += node.x;
            sumY += node.y;
            sumZ += node.z;
        }

        collapseSomaNodes(graph,
                sumX / somaNodes.size(),
                sumY / somaNodes.size(),
                sumZ / somaNodes.size());
    }

    /**
     * Core method to collapse soma nodes to a centroid.
     */
    protected void collapseSomaNodes(final DirectedWeightedGraph graph,
                                     final double cx, final double cy, final double cz) {
        // Find soma nodes
        final Set<SWCPoint> somaNodes = new HashSet<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (isInsideSomaRoi(v)) {
                somaNodes.add(v);
            }
        }
        log("  Found " + somaNodes.size() + " nodes inside soma ROI");

        if (somaNodes.isEmpty()) return;

        // Find neighbors outside soma
        final Set<SWCPoint> outsideNeighbors = new HashSet<>();
        for (final SWCWeightedEdge edge : graph.edgeSet()) {
            final SWCPoint source = graph.getEdgeSource(edge);
            final SWCPoint target = graph.getEdgeTarget(edge);

            if (somaNodes.contains(source) && !somaNodes.contains(target)) {
                outsideNeighbors.add(target);
            } else if (somaNodes.contains(target) && !somaNodes.contains(source)) {
                outsideNeighbors.add(source);
            }
        }

        // Remove soma nodes
        graph.removeAllVertices(somaNodes);

        // Create centroid node
        final SWCPoint centroidNode = new SWCPoint(
                -1, Path.SWC_SOMA,
                cx, cy, cz,
                computeAverageRadius(somaNodes),
                -1
        );
        graph.addVertex(centroidNode);

        // Connect to outside neighbors
        for (final SWCPoint neighbor : outsideNeighbors) {
            if (graph.containsVertex(neighbor)) {
                final SWCWeightedEdge edge = graph.addEdge(centroidNode, neighbor);
                if (edge != null) {
                    graph.setEdgeWeight(edge, centroidNode.distanceTo(neighbor));
                }
            }
        }

        // Set as root
        try {
            graph.setRoot(centroidNode);
        } catch (final Exception ignored) {
        }

        log("  Collapsed to centroid at (" + cx + ", " + cy + ", " + cz + ")");
        log("  Connected to " + outsideNeighbors.size() + " neurites");
    }

    /**
     * Finds the root node of a graph.
     *
     * @param graph the graph
     * @return the root node, or null if graph is empty
     */
    protected SWCPoint findGraphRoot(final DirectedWeightedGraph graph) {
        try {
            return graph.getRoot();
        } catch (final IllegalStateException e) {
            return graph.vertexSet().stream()
                    .filter(v -> graph.inDegreeOf(v) == 0)
                    .findFirst()
                    .orElse(graph.vertexSet().isEmpty() ? null : graph.vertexSet().iterator().next());
        }
    }

    /**
     * Removes all vertices not connected to the root.
     *
     * @param graph the graph to clean
     */
    protected void removeDisconnectedComponents(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // BFS from root to find connected component
        final Set<SWCPoint> connected = new HashSet<>();
        final Queue<SWCPoint> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            final SWCPoint current = queue.poll();
            if (connected.contains(current)) continue;
            connected.add(current);

            for (final SWCWeightedEdge edge : graph.edgesOf(current)) {
                final SWCPoint neighbor = graph.getEdgeSource(edge).equals(current)
                        ? graph.getEdgeTarget(edge)
                        : graph.getEdgeSource(edge);
                if (!connected.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        // Remove disconnected nodes
        final Set<SWCPoint> toRemove = new HashSet<>(graph.vertexSet());
        toRemove.removeAll(connected);

        if (!toRemove.isEmpty()) {
            graph.removeAllVertices(toRemove);
            log("Removed " + toRemove.size() + " disconnected nodes");
        }
    }

    /**
     * Computes average radius of a set of nodes.
     */
    protected double computeAverageRadius(final Set<SWCPoint> nodes) {
        if (nodes.isEmpty()) return 1.0;
        double sum = 0;
        int count = 0;
        for (final SWCPoint node : nodes) {
            if (node.radius > 0) {
                sum += node.radius;
                count++;
            }
        }
        return count > 0 ? sum / count : 1.0;
    }

    /**
     * Logs a message if verbose mode is enabled.
     */
    protected void log(final String message) {
        if (!verbose) return;
        if (logger == null) {
            logger = new Logger(getClass());
        }
        logger.info(message);
    }

}
