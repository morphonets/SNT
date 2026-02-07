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

import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;

/**
 * Abstract base class for GWDT-based tracers: APP2-style neuron tracer using Gray-Weighted
 * Distance Transform and Fast Marching. The tree is built directly on the grayscale image
 * using intensity-weighted paths.
 * <p>
 * Subclasses specify storage backend via {@link #createStorageBackend()}.
 * </p>
 * <p>
 * This implementation follows the APP2 algorithm:
 * <ol>
 *   <li>GWDT - Gray-weighted distance transform on grayscale (no binarization)</li>
 *   <li>Fast Marching - Build initial tree from seed using geodesic distance on GWDT</li>
 *   <li>Hierarchical Pruning - Long-segment-first with intensity-weighted coverage</li>
 * </ol>
 * </p>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see <a href="https://pubmed.ncbi.nlm.nih.gov/23603332/">PMID: 23603332</a>
 */
public abstract class AbstractGWDTTracer<T extends RealType<T>> extends AbstractAutoTracer {

    // Fast Marching states
    public static final byte FAR = 0;
    public static final byte TRIAL = 1;
    public static final byte ALIVE = 2;

    // Input
    protected final RandomAccessibleInterval<T> source;
    protected final double[] spacing;  // voxel dimensions [x, y, z]
    protected final long[] dims;
    protected final long[] minBounds; // Cached interval bounds for efficient access
    protected final long[] maxBounds;

    // Thresholding
    protected double backgroundThreshold = -1; // auto-computed if negative

    // Pruning parameters
    protected double lengthThreshVoxels = 5.0;  // APP2's length_thresh: sum of normalized intensities (default: 5.0)
    protected double srRatio = 1.0 / 9.0;  // APP2's SR_ratio default: 1/9 (0.111)
    protected double sphereOverlapThreshold = 0.1;  // APP2: node covered if >10% of sphere overlaps
    protected double leafPruneOverlap = 0.9;  // APP2: prune leaf if 90% overlap with parent
    protected boolean leafPruneEnabled = true;  // APP2's is_leaf_prune
    protected boolean smoothEnabled = true;  // APP2's is_smooth (default: true)
    protected int smoothWindowSize = 5;  // APP2's smooth window size
    protected boolean resampleEnabled = true;  // APP2's b_resample (default: true)
    protected double resampleStep = 2.0;  // Resample step in voxels (APP2 uses ~10 for large images)
    protected int cnnType = 2;  // APP2's cnn_type: 1=6-conn, 2=18-conn (default), 3=26-conn
    private boolean allowGap = false;  // APP2's is_break_accept: bridge single dark voxels

    // Computed data
    protected double maxIntensity;
    protected double minIntensity; // Image min for GI normalization
    protected long[] seedVoxel; // Seed point (soma location)

    // Storage backend (created by subclass)
    protected StorageBackend storage;
    
    // Reusable arrays for hot path optimization (reduces allocations)
    private int[] deltaCache;
    private long[] neighborPosCache;

    /**
     * Creates a new GWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public AbstractGWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        if (source == null) {
            throw new IllegalArgumentException("Source image cannot be null");
        }
        this.source = source;
        this.dims = Intervals.dimensionsAsLongArray(source);
        this.minBounds = Intervals.minAsLongArray(source);
        this.maxBounds = Intervals.maxAsLongArray(source);

        // Match spacing to source dimensions
        final int nDims = source.numDimensions();
        this.spacing = new double[nDims];
        for (int d = 0; d < nDims; d++) {
            if (spacing == null || d >= spacing.length) {
                this.spacing[d] = 1.0;
            } else if (spacing[d] <= 0) {
                throw new IllegalArgumentException(
                        "Invalid spacing[" + d + "]=" + spacing[d] + ". Spacing must be positive.");
            } else {
                this.spacing[d] = spacing[d];
            }
        }

        if (spacing != null && spacing.length != nDims) {
            log("Spacing length (" + spacing.length + ") != source dimensions (" + nDims +
                    "). Using: " + Arrays.toString(this.spacing));
        }

        // Initialize reusable arrays for hot path optimization
        this.deltaCache = new int[nDims];
        this.neighborPosCache = new long[nDims];

        computeIntensityRange();
    }

    /**
     * Create the storage backend for this tracer.
     * Subclasses override to specify array, sparse, or disk-backed storage.
     */
    protected abstract StorageBackend createStorageBackend();

    /**
     * Sets the background threshold. Pixels at or below this value are considered background.
     * If negative (default), threshold is auto-computed as the image mean.
     */
    public void setBackgroundThreshold(final double threshold) {
        this.backgroundThreshold = threshold;
    }

    /**
     * Sets the SR (Signal-to-Redundant) ratio threshold used in hierarchical pruning.
     * Default: 3/9 = 0.333 Segments with signal/redundant >= this ratio are kept.
     */
    public void setSrRatio(final double ratio) {
        this.srRatio = Math.max(0, ratio);
    }

    /**
     * Sets the minimum segment length in VOXEL units. Default: 2.0
     * Segments shorter than this are always pruned.
     */
    public void setMinSegmentLengthVoxels(final double lengthInVoxels) {
        this.lengthThreshVoxels = Math.max(0, lengthInVoxels);
    }

    /**
     * Sets the minimum segment length in physical units.
     * Internally converted to voxels using average spacing.
     */
    public void setMinSegmentLength(final double lengthInPhysicalUnits) {
        final double avgSpacing = computeAverageSpacing();
        this.lengthThreshVoxels = lengthInPhysicalUnits / avgSpacing;
    }

    /**
     * Sets the sphere overlap threshold for detecting redundant nodes during hierarchical pruning.
     * When a node's sphere has more than this fraction covered by claimed nodes,
     * it's considered redundant. Default: 0.4. Lower = more aggressive pruning.
     */
    public void setSphereOverlapThreshold(final double threshold) {
        this.sphereOverlapThreshold = Math.max(0, Math.min(1, threshold));
    }

    /**
     * Sets the leaf pruning overlap threshold.
     * Leaves with more than this fraction overlapping parent are pruned. Default: 0.9
     */
    public void setLeafPruneOverlap(final double threshold) {
        this.leafPruneOverlap = Math.max(0, Math.min(1, threshold));
    }

    /**
     * Enables or disables leaf pruning (both leaf and joint leaf). Default: true
     */
    public void setLeafPruneEnabled(final boolean enabled) {
        this.leafPruneEnabled = enabled;
    }

    /**
     * Enables or disables curve smoothing. Default: true
     */
    public void setSmoothEnabled(final boolean enabled) {
        this.smoothEnabled = enabled;
    }

    /**
     * Sets the smoothing window size. Default: 5
     */
    public void setSmoothWindowSize(final int windowSize) {
        this.smoothWindowSize = Math.max(3, windowSize);
    }

    /**
     * Enables/disables resampling. Default: true
     */
    public void setResampleEnabled(final boolean enabled) {
        this.resampleEnabled = enabled;
    }

    /**
     * Sets the resampling step size in voxels. Default: 2.0
     * Points closer than this distance will be merged.
     */
    public void setResampleStep(final double step) {
        this.resampleStep = Math.max(0.5, step);
    }

    /**
     * Sets the connectivity type for Fast Marching.
     * <p>
     * <b>3D images:</b>
     * <ul>
     *   <li>1 = 6-connectivity (face neighbors)</li>
     *   <li>2 = 18-connectivity (face + edge neighbors) - default</li>
     *   <li>3 = 26-connectivity (face + edge + corner neighbors)</li>
     * </ul>
     * <b>2D images:</b>
     * <ul>
     *   <li>1 = 4-connectivity (edge neighbors)</li>
     *   <li>2, 3 = 8-connectivity (edge + corner neighbors)</li>
     * </ul>
     *
     * @param type connectivity type (1, 2, or 3)
     */
    public void setConnectivityType(final int type) {
        this.cnnType = Math.max(1, Math.min(3, type));
    }

    /**
     * Sets whether to allow bridging small intensity gaps.
     * <p>
     * When enabled, fast marching can cross a single dark voxel to connect
     * broken neurites. The crossing is only allowed if the current voxel is
     * above threshold (i.e., we can step from bright → dark → bright, but not
     * dark → dark).
     * </p>
     * <p>
     * This is useful for images with fragmented signals or small imaging
     * artifacts, but may falsely connect separate structures.
     * </p>
     *
     * @param allow true to allow crossing single dark voxels (default: false)
     */
    public void setAllowGap(final boolean allow) {
        this.allowGap = allow;
    }

    /**
     * Returns whether gap bridging is enabled.
     *
     * @return true if single dark voxels can be crossed during tracing
     */
    public boolean isAllowGap() {
        return allowGap;
    }

    /**
     * Sets the seed point (soma location) in voxel coordinates.
     * For 2D images, only x and y are used (z is ignored if provided).
     */
    public void setSeed(final long[] voxelCoords) {
        // Use the minimum of provided coords and image dimensions
        final int nDims = Math.min(voxelCoords.length, dims.length);
        this.seedVoxel = new long[dims.length];
        System.arraycopy(voxelCoords, 0, this.seedVoxel, 0, nDims);
    }

    /**
     * Sets the seed point in physical coordinates.
     * For 2D images, only x and y are used (z is ignored if provided).
     */
    public void setSeedPhysical(final double[] physicalCoords) {
        // Use the minimum of provided coords and image dimensions
        final int nDims = Math.min(physicalCoords.length, dims.length);
        this.seedVoxel = new long[dims.length];
        for (int d = 0; d < nDims; d++) {
            this.seedVoxel[d] = Math.round(physicalCoords[d] / spacing[d]);
        }
        log("Seed voxel: " + Arrays.toString(seedVoxel));
    }

    @Override
    public DirectedWeightedGraph traceToGraph() {
        if (seedVoxel == null) {
            throw new IllegalStateException("Seed point not set. Call setSeed() first.");
        }

        // Create storage backend
        storage = createStorageBackend();
        log("Using " + storage.getBackendType() + " storage backend");
        log("Estimated memory: " + (storage.estimateMemoryUsage() / (1024*1024)) + " MB");

        try {
            // Step 1: Threshold
            final double threshold = getEffectiveThreshold();
            log("Threshold: " + threshold);
            log("Intensity range: [" + minIntensity + ", " + maxIntensity + "]");

            // Step 2: Compute GWDT
            log("Computing GWDT...");
            storage.computeGWDT(source, threshold, spacing, minIntensity, maxIntensity);
            log("GWDT max value: " + storage.getMaxGWDT());

            // Step 3: Fast Marching
            log("Building initial tree via Fast Marching...");
            final long seedIndex = posToIndex(seedVoxel);
            runFastMarching(threshold, seedIndex);

            // Step 4: Build graph
            log("Converting to graph...");
            final DirectedWeightedGraph graph = storage.buildGraph(dims, spacing, threshold);
            log("Graph: " + graph.vertexSet().size() + " vertices, " + graph.edgeSet().size() + " edges");

            // Step 5-8: Pruning, smoothing, etc (same as before, using graph)
            recalculateRadiiFromImage(graph);
            darkNodeAndSegmentPruning(graph);
            hierarchicalPrune(graph);
            removeDisconnectedComponents(graph);

            if (smoothEnabled) {
                log("Smoothing final curve...");
                smoothCurve(graph, smoothWindowSize);
            }

            if (resampleEnabled) {
                log("Resampling curve (step=" + resampleStep + ")...");
                resampleCurve(graph, resampleStep);
                log("After resampling: " + graph.vertexSet().size() + " vertices");
            }

            return graph;

        } finally {
            // Always clean up storage
            if (storage != null) {
                storage.dispose();
            }
        }
    }

    /**
     * Run Fast Marching using storage backend.
     */
    protected void runFastMarching(final double threshold, final long seedIndex) {
        storage.initializeFastMarching(dims, seedIndex);

        // Priority queue: (index, distance)
        final PriorityQueue<long[]> heap = new PriorityQueue<>(
                Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1]))
        );

        final RandomAccess<T> srcRA = source.randomAccess();

        // Mark seed as ALIVE
        storage.setState(seedIndex, ALIVE);
        storage.setDistance(seedIndex, 0);
        storage.setParent(seedIndex, seedIndex);

        // Add seed neighbors
        final long[] seedPos = new long[dims.length];
        indexToPos(seedIndex, seedPos);
        addNeighborsToHeap(seedPos, srcRA, heap, threshold);

        // Fast Marching main loop
        final long[] currentPos = new long[dims.length];
        int nodeCount = 1;

        while (!heap.isEmpty()) {
            final long[] entry = heap.poll();
            final long currentIdx = entry[0];
            indexToPos(currentIdx, currentPos);

            if (storage.getState(currentIdx) == ALIVE) continue;

            // Check background
            srcRA.setPosition(currentPos);
            if (srcRA.get().getRealDouble() <= threshold) {
                continue;
            }

            storage.setState(currentIdx, ALIVE);
            nodeCount++;

            addNeighborsToHeap(currentPos, srcRA, heap, threshold);
        }

        log("Initial tree: " + nodeCount + " nodes");
    }

    /**
     * Add neighbors to Fast Marching heap.
     * Uses cached arrays to reduce allocations in hot path.
     */
    protected void addNeighborsToHeap(final long[] pos, final RandomAccess<T> srcRA,
                                     final PriorityQueue<long[]> heap, final double threshold) {
        final int nDims = dims.length;
        final long currentIdx = posToIndex(pos);
        final double currentDist = storage.getDistance(currentIdx);
        
        // Reuse cached arrays to avoid allocations
        Arrays.fill(deltaCache, 0);

        iterateNeighbors(deltaCache, 0, nDims, () -> {
            // Check connectivity
            int offset = 0;
            for (int d = 0; d < nDims; d++) {
                offset += Math.abs(deltaCache[d]);
            }
            if (offset == 0 || offset > cnnType) return;

            // Compute neighbor position
            boolean inBounds = true;
            for (int d = 0; d < nDims; d++) {
                neighborPosCache[d] = pos[d] + deltaCache[d];
                if (neighborPosCache[d] < 0 || neighborPosCache[d] >= dims[d]) {
                    inBounds = false;
                    break;
                }
            }
            if (!inBounds) return;

            final long neighborIdx = posToIndex(neighborPosCache);
            if (storage.getState(neighborIdx) == ALIVE) return;

            // Check threshold
            srcRA.setPosition(neighborPosCache);
            final double intensity = srcRA.get().getRealDouble();
            if (intensity <= threshold) return;

            // Compute distance via GWDT
            final double gwdt = storage.getGWDT(neighborIdx);
            final double maxGWDT = storage.getMaxGWDT();

            // Edge cost
            double euclideanDist = 0;
            for (int d = 0; d < nDims; d++) {
                final double dd = deltaCache[d] * spacing[d];
                euclideanDist += dd * dd;
            }
            euclideanDist = Math.sqrt(euclideanDist);

            final double gwdtCost = (maxGWDT > 0) ? (maxGWDT - gwdt) / maxGWDT : 0;
            final double edgeCost = euclideanDist + gwdtCost;
            final double newDist = currentDist + edgeCost;

            // Update if better
            if (newDist < storage.getDistance(neighborIdx)) {
                storage.setDistance(neighborIdx, newDist);
                storage.setParent(neighborIdx, currentIdx);

                if (storage.getState(neighborIdx) == FAR) {
                    storage.setState(neighborIdx, TRIAL);
                }

                heap.offer(new long[]{neighborIdx, Double.doubleToRawLongBits(newDist)});
            }
        });
    }


    /**
     * Smooths the final curve using a moving average filter.
     *
     * @param graph      the graph to smooth
     * @param windowSize the smoothing window (default 5: 2 neighbors on each side)
     */
    protected void smoothCurve(final DirectedWeightedGraph graph, final int windowSize) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Find all branch points (nodes with multiple children)
        final Set<SWCPoint> branchPoints = new HashSet<>();
        branchPoints.add(root);
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) > 1) {
                branchPoints.add(v);
            }
        }

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        // For each leaf, trace to branch point and smooth that segment
        for (final SWCPoint leaf : leaves) {
            // Collect segment from leaf to branch point
            final List<SWCPoint> segment = new ArrayList<>();
            SWCPoint current = leaf;
            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                segment.add(current);

                if (branchPoints.contains(current) && !current.equals(leaf)) {
                    break;  // Stop at branch point (include it)
                }

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }

            if (segment.size() < 3) continue;  // Need at least 3 points to smooth

            // Apply moving average to this segment (exclude endpoints which are junctions)
            smoothSegment(segment, windowSize, branchPoints);
        }
    }

    // ==================== APP2-STYLE SEGMENT-BASED TREE CONSTRUCTION ====================

    /**
     * Applies moving average smoothing to a segment.
     * Modifies coordinates and radius in place.
     * Does NOT smooth branch points or endpoints to preserve topology.
     */
    private void smoothSegment(final List<SWCPoint> segment, final int windowSize,
                               final Set<SWCPoint> branchPoints) {
        if (windowSize < 2) return;

        final int halfWindow = windowSize / 2;
        final int n = segment.size();

        // Store original values (APP2 does this to avoid cascading effects)
        final double[] origX = new double[n];
        final double[] origY = new double[n];
        final double[] origZ = new double[n];
        final double[] origR = new double[n];

        for (int i = 0; i < n; i++) {
            origX[i] = segment.get(i).x;
            origY[i] = segment.get(i).y;
            origZ[i] = segment.get(i).z;
            origR[i] = segment.get(i).radius;
        }

        // Apply smoothing with TRIANGULAR WEIGHTS (matching APP2's smooth_curve_and_radius)
        // Don't move start & end points
        for (int i = 1; i < n - 1; i++) {
            final SWCPoint node = segment.get(i);

            // Don't smooth branch points
            if (branchPoints.contains(node)) continue;

            // APP2 uses triangular weights:
            // Center gets weight (1 + halfwin)
            // Neighbors at distance j get weight (1 + halfwin - j)
            double sumX = 0, sumY = 0, sumZ = 0, sumR = 0;
            double sumWeight = 0;

            // Center point with highest weight
            double centerWeight = 1.0 + halfWindow;
            sumX += centerWeight * origX[i];
            sumY += centerWeight * origY[i];
            sumZ += centerWeight * origZ[i];
            sumR += centerWeight * origR[i];
            sumWeight += centerWeight;

            // Neighbors with decreasing weights
            for (int j = 1; j <= halfWindow; j++) {
                int k1 = i + j;
                if (k1 > n - 1) k1 = n - 1;
                int k2 = i - j;
                if (k2 < 0) k2 = 0;

                double weight = 1.0 + halfWindow - j;

                sumX += weight * origX[k1];
                sumY += weight * origY[k1];
                sumZ += weight * origZ[k1];
                sumR += weight * origR[k1];

                sumX += weight * origX[k2];
                sumY += weight * origY[k2];
                sumZ += weight * origZ[k2];
                sumR += weight * origR[k2];

                sumWeight += 2 * weight;
            }

            if (sumWeight > 0) {
                node.x = sumX / sumWeight;
                node.y = sumY / sumWeight;
                node.z = sumZ / sumWeight;
                node.radius = Math.max(0.5, sumR / sumWeight);
            }
        }
    }

    /**
     * Recalculates radii for all nodes using image-based method (like APP2).
     * This is critical for accurate coverage detection during pruning.
     */
    protected void recalculateRadiiFromImage(final DirectedWeightedGraph graph) {
        final RandomAccess<T> srcRA = source.randomAccess();

        // Use a threshold slightly above background for radius calculation
        // APP2 uses max(40, bkg_thresh)
        final double radiusThreshold = Math.max(40, backgroundThreshold);

        int count = 0;
        double sumOldRadius = 0, sumNewRadius = 0;

        for (final SWCPoint node : graph.vertexSet()) {
            // Convert physical coordinates to voxel position
            final long[] pos = new long[dims.length];
            pos[0] = Math.round(node.x / spacing[0]);
            pos[1] = Math.round(node.y / spacing[1]);
            if (dims.length > 2) pos[2] = Math.round(node.z / spacing[2]);

            // Clamp to bounds
            for (int d = 0; d < dims.length; d++) {
                pos[d] = Math.max(0, Math.min(dims[d] - 1, pos[d]));
            }

            sumOldRadius += node.radius;

            // Calculate new radius using image-based method
            final double newRadius = calculateRadiusFromImage(pos, srcRA, radiusThreshold);

            // Convert from voxel units to physical units
            node.radius = newRadius * spacing[0];  // Assume isotropic XY

            sumNewRadius += node.radius;
            count++;
        }

        if (count > 0) {
            log("  Radius recalculation: avg " + String.format("%.2f", sumOldRadius / count) +
                    " -> " + String.format("%.2f", sumNewRadius / count) + " (" + count + " nodes)");
        }
    }


    /**
     * Calculates radius by expanding outward until hitting background (like APP2's markerRadiusXY).
     * This is much more accurate than GWDT-based estimation.
     */
    private double calculateRadiusFromImage(final long[] pos, final RandomAccess<T> srcRA, final double threshold) {
        final int maxRadius = (int) Math.min(Math.min(dims[0], dims[1]) / 2, 50);  // Cap at 50 voxels

        // For 2D-like images (thin Z), use 2D radius calculation
        final boolean is2D = dims.length < 3 || dims[2] <= 3;

        for (int r = 1; r <= maxRadius; r++) {
            int totalCount = 0;
            int backgroundCount = 0;

            // Check shell at radius r (not the entire sphere, just the shell)
            final int zRange = is2D ? 0 : r;
            for (int dz = -zRange; dz <= zRange; dz++) {
                final long z = dims.length > 2 ? pos[2] + dz : 0;
                if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;

                for (int dy = -r; dy <= r; dy++) {
                    final long y = pos[1] + dy;
                    if (y < 0 || y >= dims[1]) return r;  // Hit boundary

                    for (int dx = -r; dx <= r; dx++) {
                        final long x = pos[0] + dx;
                        if (x < 0 || x >= dims[0]) return r;  // Hit boundary

                        // Check if on shell (distance between r-1 and r)
                        final double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist > r - 1 && dist <= r) {
                            totalCount++;

                            srcRA.setPosition(new long[]{x, y, dims.length > 2 ? z : 0});
                            final double intensity = srcRA.get().getRealDouble();

                            if (intensity <= threshold) {
                                backgroundCount++;
                                // APP2 uses 0.1% threshold (0.001)
                                if (totalCount > 0 && (double) backgroundCount / totalCount > 0.001) {
                                    return r;
                                }
                            }
                        }
                    }
                }
            }
        }

        return maxRadius;
    }

    /**
     * APP2-style dark node and segment pruning.
     * <p>
     * Phase 1: Dark Node Pruning (iterative)
     * - For each leaf, if intensity <= background threshold, remove the leaf
     * - Repeat until no more dark leaves found
     * <p>
     * Phase 2: Dark Segment Pruning
     * - For each terminal branch (path from leaf to branch point):
     * - Delete if average intensity <= background threshold, OR
     * - Delete if >= 20% of nodes are dark (intensity <= threshold)
     */
    protected void darkNodeAndSegmentPruning(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<T> srcRA = source.randomAccess();
        final double threshold = backgroundThreshold;

        // Phase 1: Dark Node Pruning (iterative leaf trimming)
        int totalDarkNodesPruned = 0;
        int iteration = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            iteration++;
            final List<SWCPoint> darkLeaves = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // Check intensity at this node
                final long[] pos = new long[dims.length];
                nodeToVoxelPos(v, pos);

                if (isInBounds(pos)) {
                    srcRA.setPosition(pos);
                    final double intensity = srcRA.get().getRealDouble();

                    if (intensity <= threshold) {
                        darkLeaves.add(v);
                    }
                }
            }

            if (!darkLeaves.isEmpty()) {
                graph.removeAllVertices(darkLeaves);
                totalDarkNodesPruned += darkLeaves.size();
                changed = true;
            }
        }

        if (totalDarkNodesPruned > 0) {
            log("  Dark node pruning: " + iteration + " iterations, removed " + totalDarkNodesPruned + " nodes");
        }

        // Phase 2: Dark Segment Pruning
        // Find terminal branches and check if they're mostly dark
        int darkSegmentsPruned = 0;
        changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> segmentToRemove = new ArrayList<>();

            for (final SWCPoint leaf : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(leaf) != 0 || leaf.equals(root)) continue;

                // Trace back to branch point (node with multiple children) or root
                final List<SWCPoint> segment = new ArrayList<>();
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    segment.add(current);

                    // Stop if we hit a branch point (has other children) or root
                    if (current.equals(root)) break;
                    if (graph.outDegreeOf(current) > 1) break;  // This node has other children

                    // Check parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;

                    final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());

                    // Stop at branch point: parent has multiple outgoing edges
                    if (graph.outDegreeOf(parent) > 1) {
                        // Don't include the branch point itself in the segment
                        break;
                    }

                    current = parent;
                }

                // Skip if segment is empty or just the root
                if (segment.isEmpty()) continue;
                if (segment.size() == 1 && segment.getFirst().equals(root)) continue;

                // Calculate segment statistics
                double sumIntensity = 0;
                int totalNodes = 0;
                int darkNodes = 0;

                for (final SWCPoint node : segment) {
                    final long[] pos = new long[dims.length];
                    nodeToVoxelPos(node, pos);

                    if (isInBounds(pos)) {
                        srcRA.setPosition(pos);
                        final double intensity = srcRA.get().getRealDouble();
                        sumIntensity += intensity;
                        totalNodes++;
                        if (intensity <= threshold) {
                            darkNodes++;
                        }
                    }
                }

                // APP2 criteria: delete if average <= threshold OR >= 20% dark nodes
                if (totalNodes > 0) {
                    double avgIntensity = sumIntensity / totalNodes;
                    double darkRatio = (double) darkNodes / totalNodes;

                    if (avgIntensity <= threshold || darkRatio >= 0.2) {
                        segmentToRemove.addAll(segment);
                        changed = true;
                    }
                }
            }

            if (!segmentToRemove.isEmpty()) {
                // Don't remove the root!
                segmentToRemove.remove(root);
                graph.removeAllVertices(segmentToRemove);
                darkSegmentsPruned++;
            }
        }

        if (darkSegmentsPruned > 0) {
            log("  Dark segment pruning: removed " + darkSegmentsPruned + " dark segments");
        }
    }

    /**
     * Performs hierarchical pruning using intensity-weighted coverage.
     * <p>
     * Algorithm:
     * 1. Decompose tree into segments (paths between branch points)
     * 2. Calculate segment length as SUM OF NORMALIZED INTENSITIES (not Euclidean!)
     * 3. Sort segments by length (longest first)
     * 4. For each segment, compute intensity-weighted coverage ratio using PIXEL MASK
     * 5. If coverage > threshold, prune segment and children
     * </p>
     */
    protected void hierarchicalPrune(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // APP2 uses intensity-based length: sum of (intensity / maxIntensity)
        // length_thresh = 5 means total normalized intensity sum >= 5
        // This is NOT Euclidean distance!
        final double intensityLengthThresh = lengthThreshVoxels;  // Now interpreted as intensity sum

        log("Intensity-based length threshold: " + intensityLengthThresh +
                " (sum of normalized intensities)");

        // Get image access for intensity lookup
        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        // COVERAGE MASK: Use imglib2 BitType image to track covered regions
        final Img<BitType> coverageMask = ArrayImgs.bits(dims);  // false = not covered
        final RandomAccess<BitType> coverageRA = coverageMask.randomAccess();

        // Compute intensity-based distance from root to each node
        // APP2: distance = sum of normalized intensities along path
        final Map<SWCPoint, Double> distFromRoot = new HashMap<>();
        computeIntensityDistancesFromRoot(graph, root, distFromRoot, ra);

        // Find all leaves and sort by distance from root (farthest first)
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }
        leaves.sort((a, b) -> Double.compare(
                distFromRoot.getOrDefault(b, 0.0),
                distFromRoot.getOrDefault(a, 0.0)
        ));

        log("Processing " + leaves.size() + " leaves (longest paths first)");

        // Track claimed nodes - ROOT IS ALWAYS CLAIMED FIRST
        final Set<SWCPoint> claimedNodes = new HashSet<>();
        claimedNodes.add(root);

        // Track which leaves have been accepted (for parent-first processing)
        final Set<SWCPoint> acceptedLeaves = new HashSet<>();

        // Mark root's sphere as covered
        markSphereAsCovered(root, coverageMask);

        final Set<SWCPoint> nodesToRemove = new HashSet<>();
        final Set<SWCPoint> rejectedLeaves = new HashSet<>();  // Leaves rejected (too short or covered)
        int keptCount = 0;
        int prunedShort = 0;
        int prunedCovered = 0;

        // APP2 PARENT-FIRST: Process in multiple passes
        // Only evaluate a segment if its parent segment has been accepted
        // Continue until no more progress can be made
        boolean madeProgress = true;
        while (madeProgress) {
            madeProgress = false;

            for (final SWCPoint leaf : leaves) {
                // Skip already processed leaves
                if (acceptedLeaves.contains(leaf) || rejectedLeaves.contains(leaf)) continue;
                if (!graph.containsVertex(leaf)) continue;

                // Trace from leaf toward root until we hit a claimed node
                final List<SWCPoint> pathNodes = new ArrayList<>();
                SWCPoint current = leaf;
                SWCPoint connectionPoint = null;  // The claimed node we connect to
                double pathLength = 0;  // Now intensity-based, not Euclidean
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    // Stop when we reach a claimed node (this connects us to the tree)
                    if (claimedNodes.contains(current)) {
                        connectionPoint = current;
                        break;
                    }

                    visited.add(current);
                    pathNodes.add(current);

                    // APP2: path length = sum of normalized intensities
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(current, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        // Normalize to 0-1 range (APP2 divides by 255)
                        double normalizedIntensity = intensity / maxIntensity;
                        pathLength += normalizedIntensity;
                    }

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // APP2 PARENT-FIRST CHECK:
                // If we didn't reach a claimed node, the parent segment isn't accepted yet - SKIP (defer)
                if (connectionPoint == null) {
                    // Parent not ready yet, try again in next pass
                    continue;
                }

                // If we didn't reach a claimed node properly, mark for removal
                if (pathNodes.isEmpty()) {
                    rejectedLeaves.add(leaf);
                    continue;
                }

                // Filter by intensity-based length
                if (pathLength < intensityLengthThresh) {
                    nodesToRemove.addAll(pathNodes);
                    rejectedLeaves.add(leaf);
                    prunedShort++;
                    madeProgress = true;
                    continue;
                }

                // APP2 PIXEL-BASED COVERAGE: check signal vs redundant using coverage mask
                double sumSignal = 0;
                double sumRedundant = 0;

                for (final SWCPoint node : pathNodes) {
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(node, voxelPos);

                    if (!isInBounds(voxelPos)) {
                        continue;
                    }

                    ra.setPosition(voxelPos);
                    final double intensity = ra.get().getRealDouble();

                    // Check if this voxel CENTER is already covered
                    coverageRA.setPosition(voxelPos);
                    if (coverageRA.get().get()) {
                        // Center already covered → immediately redundant (APP2 line 362)
                        sumRedundant += intensity;
                    } else {
                        // Center NOT covered - BUT STILL CHECK SPHERE OVERLAP (APP2 lines 366-400)
                        // This is CRITICAL: edge branches have uncovered centers but overlapping spheres
                        double sphereOverlap = computeSphereCoverageFromMask(node, coverageMask);
                        if (sphereOverlap > sphereOverlapThreshold) {
                            // >10% of sphere is covered by other branches → redundant
                            sumRedundant += intensity;
                        } else {
                            // <10% covered → this is real signal
                            sumSignal += intensity;
                        }
                    }
                }

                // APP2 keep criterion:
                // Keep if: no parent (root) OR no redundancy OR (signal/redundant >= srRatio AND signal >= 256)
                // The sum_sig >= 256 (T_max for 8-bit) ensures minimum total signal
                final double minSignalThreshold = 256.0;  // T_max for 8-bit images
                boolean keep = (sumRedundant == 0) ||
                        (sumSignal / sumRedundant >= srRatio && sumSignal >= minSignalThreshold);

                if (keep) {
                    // Claim all nodes in path - this connects leaf to the main tree
                    claimedNodes.addAll(pathNodes);
                    acceptedLeaves.add(leaf);
                    keptCount++;
                    madeProgress = true;

                    // APP2: Mark all node spheres as covered in the mask
                    for (final SWCPoint node : pathNodes) {
                        markSphereAsCovered(node, coverageMask);
                    }
                } else {
                    nodesToRemove.addAll(pathNodes);
                    rejectedLeaves.add(leaf);
                    prunedCovered++;
                    madeProgress = true;
                }
            }
        }

        // Any remaining unprocessed leaves are disconnected - remove them
        for (final SWCPoint leaf : leaves) {
            if (!acceptedLeaves.contains(leaf) && !rejectedLeaves.contains(leaf)) {
                // Trace and remove
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();
                while (current != null && !visited.contains(current) && !claimedNodes.contains(current)) {
                    visited.add(current);
                    nodesToRemove.add(current);
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }
            }
        }

        // Remove only unclaimed nodes
        nodesToRemove.removeAll(claimedNodes);
        graph.removeAllVertices(nodesToRemove);


        log("Kept " + keptCount + " branches, pruned " + prunedShort + " (short) + " + prunedCovered + " (covered)");
        log("After hierarchical pruning: " + graph.vertexSet().size() + " vertices");


        // APP2 additional pruning steps
        if (leafPruneEnabled) {
            leafNodePruning(graph);
            jointLeafPruning(graph);
        }

        // Additional: prune short terminal branches iteratively
        pruneShortTerminalBranches(graph, intensityLengthThresh);  // Same threshold

        // Prune dark terminal branches (low intensity)
        pruneDarkTerminalBranches(graph, backgroundThreshold);
    }

    /**
     * Unmarks a node's sphere from the coverage count mask.
     * APP2: Only decrement if count > 1 (prevents underflow and maintains proper counting)
     */
    private void unmarkSphereInCountMaskConditional(final SWCPoint node, final RandomAccess<UnsignedShortType> countRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        countRA.setPosition(pos);
                        int current = countRA.get().getInteger();
                        // APP2: Only decrement if > 1
                        if (current > 1) countRA.get().set(current - 1);
                    }
                }
            }
        }
    }

    /**
     * Computes INTENSITY-WEIGHTED joint coverage fraction (like APP2).
     * Returns [coveredSig, totalSig] where coverage is weighted by image intensity.
     */
    protected double[] computeIntensityWeightedJointCoverage(final SWCPoint node,
                                                           final RandomAccess<UnsignedShortType> countRA,
                                                           final RandomAccess<? extends RealType<?>> srcRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        double coveredSig = 0;
        double totalSig = 0;

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;

                        // Get intensity at this voxel
                        srcRA.setPosition(pos);
                        double intensity = srcRA.get().getRealDouble();
                        totalSig += intensity;

                        // Check if covered by multiple branches (count > 1)
                        countRA.setPosition(pos);
                        if (countRA.get().getInteger() > 1) {
                            coveredSig += intensity;
                        }
                    }
                }
            }
        }

        return new double[]{coveredSig, totalSig};
    }

    /**
     * Computes intensity range for GI normalization.
     * APP2 uses actual image min/max, not threshold-based.
     */
    protected void computeIntensityRange() {
        double min = Double.MAX_VALUE;
        double max = 0;
        final Cursor<T> cursor = Views.flatIterable(source).cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            final double v = cursor.get().getRealDouble();
            if (v > max) max = v;
            if (v < min) min = v;
        }
        this.minIntensity = min;
        this.maxIntensity = max;
    }

    /**
     * Resamples the curve to have evenly spaced points.
     * Keeps branch points and endpoints, removes intermediate points that are too close.
     */
    protected void resampleCurve(final DirectedWeightedGraph graph, final double stepSize) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Convert step size from voxels to physical units
        final double avgSpacing = computeAverageSpacing();
        final double physicalStep = stepSize * avgSpacing;

        // Find branch points (must be preserved)
        final Set<SWCPoint> branchPoints = new HashSet<>();
        branchPoints.add(root);
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) > 1) {
                branchPoints.add(v);
            }
        }

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        // Process each path from leaf to branch point
        final Set<SWCPoint> toRemove = new HashSet<>();

        for (final SWCPoint leaf : leaves) {
            // Trace from leaf to branch point
            final List<SWCPoint> segment = new ArrayList<>();
            SWCPoint current = leaf;
            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                segment.add(current);

                // Stop at branch point (include it)
                if (branchPoints.contains(current) && !current.equals(leaf)) {
                    break;
                }

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }

            if (segment.size() < 3) continue;  // Need at least 3 points to resample

            // Resample this segment: keep first, last, and points at ~stepSize intervals
            double accumulatedDist = 0;

            for (int i = 1; i < segment.size() - 1; i++) {
                final SWCPoint p = segment.get(i);
                accumulatedDist += p.distanceTo(segment.get(i - 1));

                if (accumulatedDist >= physicalStep) {
                    // Keep this point
                    accumulatedDist = 0;
                } else {
                    // Mark for removal (unless it's a branch point)
                    if (!branchPoints.contains(p)) {
                        toRemove.add(p);
                    }
                }
            }
            // Last point (branch point) is always kept
        }

        // Now we need to reconnect the graph after removing points
        // For each point being removed, connect its parent to its child
        for (final SWCPoint removePoint : toRemove) {
            if (!graph.containsVertex(removePoint)) continue;

            // Get parent (source of incoming edge)
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(removePoint);
            if (inEdges.isEmpty()) continue;
            final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());

            // Get children (targets of outgoing edges) - capture BEFORE removal
            final List<SWCPoint> children = new ArrayList<>();
            for (final SWCWeightedEdge childEdge : graph.outgoingEdgesOf(removePoint)) {
                final SWCPoint child = graph.getEdgeTarget(childEdge);
                if (child != null) {
                    children.add(child);
                }
            }

            // Remove the point (this also removes connected edges)
            graph.removeVertex(removePoint);

            // Reconnect: parent -> each child
            for (final SWCPoint child : children) {
                if (graph.containsVertex(child) && graph.containsVertex(parent)) {
                    final SWCWeightedEdge newEdge = graph.addEdge(parent, child);
                    if (newEdge != null) {
                        graph.setEdgeWeight(newEdge, parent.distanceTo(child));
                    }
                }
            }
        }
    }

    protected double getEffectiveThreshold() {
        return (backgroundThreshold >= 0) ? backgroundThreshold : estimateBackgroundThreshold(source);
    }

    protected long posToIndex(final long[] pos) {
        return posToIndex(pos, dims);
    }

    public static long posToIndex(final long[] pos, final long[] dims) {
        long idx = 0;
        long stride = 1;
        for (int d = 0; d < pos.length; d++) {
            idx += pos[d] * stride;
            stride *= dims[d];
        }
        return idx;
    }

    /**
     * Convert linear index to position, writing to provided array (avoids allocation).
     */
    protected void indexToPos(final long idx, final long[] pos) {
        long remaining = idx;
        for (int d = 0; d < dims.length; d++) {
            pos[d] = remaining % dims[d];
            remaining /= dims[d];
        }
    }

    /**
     * Converts an SWCPoint's physical coordinates to voxel position.
     * Handles both 2D and 3D images.
     *
     * @param node The SWCPoint with physical coordinates
     * @param pos  Output array (must be dims.length size)
     */
    protected void nodeToVoxelPos(final SWCPoint node, final long[] pos) {
        pos[0] = Math.round(node.x / spacing[0]);
        pos[1] = Math.round(node.y / spacing[1]);
        if (dims.length > 2) {
            pos[2] = Math.round(node.z / spacing[2]);
        }
    }

    /**
     * Checks if a voxel position is within image bounds.
     * Uses ImgUtils.outOfBounds with cached interval bounds.
     */
    protected boolean isInBounds(final long[] pos) {
        return !ImgUtils.outOfBounds(pos, minBounds, maxBounds);
    }

    /**
     * Computes average voxel spacing, handling both 2D and 3D images.
     */
    protected double computeAverageSpacing() {
        double sum = 0;
        for (int d = 0; d < dims.length; d++) {
            sum += spacing[d];
        }
        return sum / dims.length;
    }

    /**
     * Iterates over all neighbor offsets (26-connectivity in 3D).
     */
    public static void iterateNeighbors(final int[] delta, final int dim, final int nDims,
                                        final Runnable action) {
        if (dim == nDims) {
            action.run();
            return;
        }
        for (int d = -1; d <= 1; d++) {
            delta[dim] = d;
            iterateNeighbors(delta, dim + 1, nDims, action);
        }
    }


    /**
     * Marks all voxels within the node's sphere as covered in the mask.
     * Uses RandomAccess for clean imglib2 integration.
     */
    private void markSphereAsCovered(final SWCPoint node, final Img<BitType> coverageMask) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;

        // Use minimum radius of 5 voxels to ensure meaningful coverage detection
        // In thick tubes, this helps the main centerline to "cover" edge voxels
        final int r = (int) Math.max(5, Math.round(node.radius / spacing[0]));

        final RandomAccess<BitType> maskRA = coverageMask.randomAccess();
        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    // Check if within sphere
                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        maskRA.setPosition(pos);
                        maskRA.get().set(true);
                    }
                }
            }
        }
    }

    /**
     * Computes fraction of node's sphere that is covered by the mask.
     * This matches APP2's sphere overlap check in hierarchy coverage pruning.
     */
    private double computeSphereCoverageFromMask(final SWCPoint node, final Img<BitType> coverageMask) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        // Use same minimum radius as marking
        final int r = (int) Math.max(5, Math.round(node.radius / spacing[0]));

        final RandomAccess<BitType> maskRA = coverageMask.randomAccess();
        final double rr = r * r;
        final long[] pos = new long[dims.length];

        int totalVoxels = 0;
        int coveredVoxels = 0;

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        totalVoxels++;
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        maskRA.setPosition(pos);
                        if (maskRA.get().get()) {
                            coveredVoxels++;
                        }
                    }
                }
            }
        }

        return totalVoxels > 0 ? (double) coveredVoxels / totalVoxels : 0.0;
    }

    /**
     * Compute intensity-based distance from root to all nodes.
     * APP2 uses sum of normalized intensities, not Euclidean distance.
     */
    private void computeIntensityDistancesFromRoot(final DirectedWeightedGraph graph,
                                                   final SWCPoint root,
                                                   final Map<SWCPoint, Double> distances,
                                                   final RandomAccess<? extends RealType<?>> ra) {
        distances.clear();
        distances.put(root, 0.0);

        // BFS from root
        final Queue<SWCPoint> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            final SWCPoint current = queue.poll();
            final double currentDist = distances.get(current);

            for (final SWCWeightedEdge edge : graph.outgoingEdgesOf(current)) {
                final SWCPoint child = graph.getEdgeTarget(edge);
                if (!distances.containsKey(child)) {
                    // Get intensity at child position
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(child, voxelPos);

                    double intensity = 0;
                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        intensity = ra.get().getRealDouble();
                    }

                    // APP2: normalize intensity to 0-1 range
                    double normalizedIntensity = intensity / maxIntensity;
                    distances.put(child, currentDist + normalizedIntensity);
                    queue.add(child);
                }
            }
        }
    }

    /**
     * Prune terminal branches where average intensity is below threshold
     * or where >20% of nodes are dark. This removes branches tracing through noise.
     */
    private void pruneDarkTerminalBranches(final DirectedWeightedGraph graph,
                                           final double bkgThreshold) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // APP2 uses background threshold directly (NOT max(40, bkg_thresh) which is only for radius)
        log("Prune dark terminal branches, threshold=" + bkgThreshold);

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> branchToRemove = new ArrayList<>();

            // Find all current leaves
            final List<SWCPoint> leaves = new ArrayList<>();
            for (final SWCPoint v : graph.vertexSet()) {
                if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                    leaves.add(v);
                }
            }

            for (final SWCPoint leaf : leaves) {
                // Trace from leaf to branch point
                final List<SWCPoint> branchNodes = new ArrayList<>();
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    branchNodes.add(current);

                    // Stop at root
                    if (current.equals(root)) break;

                    // Stop at branch point (has multiple children)
                    if (graph.outDegreeOf(current) > 1) break;

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // Don't remove main branch to root
                if (current != null && current.equals(root) && branchNodes.contains(root)) {
                    continue;
                }

                // Need at least 2 nodes to evaluate (leaf + at least one more)
                if (branchNodes.size() < 2) continue;

                // Check intensity along branch (exclude junction point)
                double sumIntensity = 0;
                int darkCount = 0;
                int nodeCount = branchNodes.size() - 1;  // Exclude junction

                for (int i = 0; i < nodeCount; i++) {
                    final SWCPoint p = branchNodes.get(i);
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(p, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        sumIntensity += intensity;
                        if (intensity <= bkgThreshold) {
                            darkCount++;
                        }
                    } else {
                        darkCount++;  // Out of bounds = dark
                    }
                }

                double avgIntensity = sumIntensity / nodeCount;
                double darkFraction = (double) darkCount / nodeCount;

                // APP2 criterion: remove if avg intensity <= threshold OR >20% dark nodes
                if (avgIntensity <= bkgThreshold || darkFraction > 0.2) {
                    // Remove all except the junction point
                    for (int i = 0; i < branchNodes.size() - 1; i++) {
                        branchToRemove.add(branchNodes.get(i));
                    }
                }
            }

            if (!branchToRemove.isEmpty()) {
                graph.removeAllVertices(branchToRemove);
                totalPruned += branchToRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Dark branch pruning removed " + totalPruned + " nodes");
            log("  After dark branch pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * Iteratively removes terminal branches (leaf to branch point) shorter than threshold.
     * This cleans up short spikes that survived hierarchical pruning.
     */
    private void pruneShortTerminalBranches(final DirectedWeightedGraph graph,
                                            final double minBranchLength) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> branchToRemove = new ArrayList<>();

            // Find all current leaves
            final List<SWCPoint> leaves = new ArrayList<>();
            for (final SWCPoint v : graph.vertexSet()) {
                if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                    leaves.add(v);
                }
            }

            for (final SWCPoint leaf : leaves) {
                // Trace from leaf to branch point (node with >1 children or root)
                final List<SWCPoint> branchNodes = new ArrayList<>();
                double branchLength = 0;  // Now intensity-based
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    branchNodes.add(current);

                    // APP2: branch length = sum of normalized intensities
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(current, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        branchLength += intensity / maxIntensity;
                    }

                    // Stop at root
                    if (current.equals(root)) break;

                    // Stop at branch point (has multiple children)
                    if (graph.outDegreeOf(current) > 1) break;

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // Don't remove if we reached root (main branch)
                if (current != null && current.equals(root) && branchNodes.contains(root)) {
                    continue;
                }

                // Remove branch if too short (but keep the junction point)
                if (branchLength < minBranchLength && branchNodes.size() > 1) {
                    // Remove all except the last node (junction point)
                    for (int i = 0; i < branchNodes.size() - 1; i++) {
                        branchToRemove.add(branchNodes.get(i));
                    }
                }
            }

            if (!branchToRemove.isEmpty()) {
                graph.removeAllVertices(branchToRemove);
                totalPruned += branchToRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Short branch pruning removed " + totalPruned + " nodes");
            log("  After short branch pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * APP2 Leaf Node Pruning: Iteratively remove leaves whose sphere
     * is 90% covered by parent's sphere.
     * CRITICAL: Only remove actual leaf nodes (no children), never branch points.
     */
    private void leafNodePruning(final DirectedWeightedGraph graph) {
        log("Performing leaf node pruning...");

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<T> srcRA = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> toRemove = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be TRUE leaf (no children) and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // Get parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(v);
                if (inEdges.isEmpty()) continue;
                final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());
                if (parent == null) continue;

                // CRITICAL: Don't remove if parent would become isolated or is root
                if (graph.outDegreeOf(parent) <= 1 && graph.inDegreeOf(parent) == 0) {
                    continue;  // Would orphan parent
                }

                // APP2-style intensity-weighted sphere overlap check
                // Count intensity sum in leaf sphere and in overlap region
                final long x1 = Math.round(v.x / spacing[0]);
                final long y1 = Math.round(v.y / spacing[1]);
                final long z1 = dims.length > 2 ? Math.round(v.z / spacing[2]) : 0;
                final int r1 = (int) Math.max(1, Math.round(v.radius / spacing[0]));

                final long x2 = Math.round(parent.x / spacing[0]);
                final long y2 = Math.round(parent.y / spacing[1]);
                final long z2 = dims.length > 2 ? Math.round(parent.z / spacing[2]) : 0;
                final double r2 = Math.max(1, parent.radius / spacing[0]);
                final double r2_sq = r2 * r2;

                double sumLeafInt = 0;
                double sumOverlapInt = 0;
                final long[] pos = new long[dims.length];

                for (int dz = -r1; dz <= r1; dz++) {
                    final long zz = z1 + dz;
                    if (dims.length > 2 && (zz < 0 || zz >= dims[2])) continue;

                    for (int dy = -r1; dy <= r1; dy++) {
                        final long yy = y1 + dy;
                        if (yy < 0 || yy >= dims[1]) continue;

                        for (int dx = -r1; dx <= r1; dx++) {
                            final long xx = x1 + dx;
                            if (xx < 0 || xx >= dims[0]) continue;

                            // Check if inside leaf sphere
                            if (dx * dx + dy * dy + dz * dz > r1 * r1) continue;

                            pos[0] = xx;
                            pos[1] = yy;
                            if (dims.length > 2) pos[2] = zz;
                            srcRA.setPosition(pos);
                            final double intensity = srcRA.get().getRealDouble();

                            sumLeafInt += intensity;

                            // Check if also inside parent sphere
                            final double distToParent_sq = (x2 - xx) * (x2 - xx) + (y2 - yy) * (y2 - yy) + (z2 - zz) * (z2 - zz);
                            if (distToParent_sq <= r2_sq) {
                                sumOverlapInt += intensity;
                            }
                        }
                    }
                }

                // APP2: prune if 90% of intensity is in overlap
                if (sumLeafInt > 0 && sumOverlapInt / sumLeafInt >= leafPruneOverlap) {
                    toRemove.add(v);
                }
            }

            if (!toRemove.isEmpty()) {
                graph.removeAllVertices(toRemove);
                totalPruned += toRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Leaf pruning removed " + totalPruned + " nodes");
        }
    }

    /**
     * APP2 Joint Leaf Pruning: Remove leaves if 90% of sphere is covered
     * by multiple other nodes (not just parent).
     */
    private void jointLeafPruning(final DirectedWeightedGraph graph) {
        log("Performing APP2-style joint leaf pruning...");

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Get image access for intensity lookup
        final RandomAccess<? extends RealType<?>> srcRA = source.randomAccess();

        // Create a coverage COUNT mask (like APP2's mask[ind]++)
        // Each voxel counts how many times it's covered by different branches
        final Img<UnsignedShortType> coverageCount = ArrayImgs.unsignedShorts(dims);
        final RandomAccess<UnsignedShortType> countRA = coverageCount.randomAccess();

        // Mark all node spheres in the coverage count mask
        for (final SWCPoint node : graph.vertexSet()) {
            markSphereInCountMask(node, countRA);
        }

        int totalPruned = 0;
        boolean changed = true;
        int iteration = 0;

        while (changed) {
            changed = false;
            iteration++;
            final List<SWCPoint> toRemove = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // APP2: Check if 90% of INTENSITY-WEIGHTED sphere coverage has count > 1
                double[] result = computeIntensityWeightedJointCoverage(v, countRA, srcRA);
                double coveredSig = result[0];
                double totalSig = result[1];

                if (totalSig > 0 && coveredSig / totalSig >= 0.9) {
                    toRemove.add(v);
                }
            }

            if (!toRemove.isEmpty()) {
                // Unmark removed nodes from coverage count (only if count > 1, like APP2)
                for (SWCPoint v : toRemove) {
                    unmarkSphereInCountMaskConditional(v, countRA);
                }
                graph.removeAllVertices(toRemove);
                totalPruned += toRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Joint leaf pruning: " + iteration + " iterations, removed " + totalPruned + " nodes");
            log("  After joint pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * Marks a node's sphere in the coverage count mask (increment count).
     */
    private void markSphereInCountMask(final SWCPoint node, final RandomAccess<UnsignedShortType> countRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        countRA.setPosition(pos);
                        int current = countRA.get().getInteger();
                        countRA.get().set(current + 1);
                    }
                }
            }
        }
    }

    // ==================== Tree Building Methods ====================

    /**
     * Traces the neuron and returns a Tree.
     *
     * @return the traced neuron morphology, or null if tracing fails
     */
    public Tree trace() {
        final List<Tree> trees = traceTrees();
        if (trees == null || trees.isEmpty()) {
            return null;
        }
        // If multiple trees, merge them (should only happen with ROI_EDGE)
        if (trees.size() == 1) {
            return trees.getFirst();
        }
        final Tree merged = new Tree();
        trees.forEach(t -> merged.list().addAll(t.list()));
        return merged;
    }

    /**
     * Traces the neuron and returns a list of Trees.
     * <p>
     * With {@link #ROI_EDGE} strategy, returns separate trees for each neurite
     * exiting the soma ROI. With other strategies, returns a single tree.
     * </p>
     *
     * @return list of traced trees, or empty list if tracing fails
     */
    public List<Tree> traceTrees() {
        final DirectedWeightedGraph graph = traceToGraph();
        if (graph == null || graph.vertexSet().size() < 2) {
            log("Tracing failed: No meaningful vertices exist");
            return Collections.emptyList();
        }
        final List<Tree> trees = tracedTrees(graph);
        if (verbose) {
            int totalPaths = 0;
            for (final Tree t : trees) totalPaths += t.size();
            log(String.format("Traced %d tree(s) with %d path(s)", trees.size(), totalPaths));
        }
        return trees;
    }

    /**
     * Converts the traced graph to Tree(s) based on soma ROI strategy.
     */
    private List<Tree> tracedTrees(final DirectedWeightedGraph graph) {

        // Use APP2-style segment ordering for proper path organization
        // This ensures main trunk is a single path (root → furthest tip)
        final Tree segmentOrderedTree = buildTreeWithSegmentOrdering(graph);

        // Apply soma ROI strategy
        if (somaRoi == null || rootStrategy == ROI_UNSET) {
            return Collections.singletonList(segmentOrderedTree);
        }

        return switch (rootStrategy) {
            case ROI_EDGE -> splitAtSomaBoundary(graph);
            case ROI_CENTROID -> {
                collapseSomaToRoiCentroid(graph);
                yield Collections.singletonList(buildTreeWithSegmentOrdering(graph));
            }
            case ROI_CENTROID_WEIGHTED -> {
                collapseSomaToWeightedCentroid(graph);
                yield Collections.singletonList(buildTreeWithSegmentOrdering(graph));
            }
            default -> Collections.singletonList(segmentOrderedTree);
        };
    }

    /**
     * Builds a Tree from the graph using APP2's segment-based organization.
     * <p>
     * This ensures paths are organized as a human annotator would trace:
     * - Main trunk: root → furthest distal tip (single path)
     * - Branches: each branch point → its furthest tip
     * - Sorted by length (longest first)
     * </p>
     */
    private Tree buildTreeWithSegmentOrdering(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return graph.getTree();

        // Step 1: Compute ownership - which leaf "owns" each node
        // A node is owned by the leaf that has the longest intensity path through it
        final Map<SWCPoint, SWCPoint> nodeOwner = new HashMap<>();  // node -> owning leaf
        final Map<SWCPoint, Double> nodeDistToLeaf = new HashMap<>();  // node -> intensity dist to its leaf

        computeNodeOwnership(graph, nodeOwner, nodeDistToLeaf);

        // Step 2: Build segments - group nodes by owner
        final Map<SWCPoint, HierarchySegment> leafToSegment = new HashMap<>();
        buildHierarchySegments(graph, nodeOwner, nodeDistToLeaf, leafToSegment);

        // Step 3: Sort segments by length (longest first) and build paths
        final List<HierarchySegment> segments = new ArrayList<>(leafToSegment.values());
        segments.sort((a, b) -> Double.compare(b.length, a.length));

        // Step 4: Build Tree with properly ordered paths
        return buildTreeFromSegments(graph, segments);
    }

    /**
     * Builds a Tree from segments, processing longest segments first.
     * This ensures the main trunk is a single continuous path.
     */
    private Tree buildTreeFromSegments(
            final DirectedWeightedGraph graph,
            final List<HierarchySegment> segments) {

        final Tree tree = new Tree();
        if (segments.isEmpty()) return tree;

        final Map<HierarchySegment, Path> segmentToPath = new HashMap<>();

        // Track which segments have been processed
        final Set<HierarchySegment> processed = new HashSet<>();

        // Process segments in order (longest first)
        // But we need to ensure parent segment is processed before child
        boolean madeProgress = true;
        while (madeProgress && processed.size() < segments.size()) {
            madeProgress = false;

            for (final HierarchySegment segment : segments) {
                if (processed.contains(segment)) continue;

                // Can only process if parent is already processed (or no parent = root segment)
                if (segment.parent != null && !processed.contains(segment.parent)) {
                    continue;
                }

                // Create path for this segment
                final Path path = createPathFromSegment(segment);
                if (path == null || path.size() == 0) {
                    processed.add(segment);
                    continue;
                }

                // Set parent path relationship
                if (segment.parent != null) {
                    final Path parentPath = segmentToPath.get(segment.parent);
                    if (parentPath != null) {
                        // Find connection point - the node just before this segment starts
                        final SWCPoint connectionNode = segment.segmentRoot;
                        final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(connectionNode);
                        if (!inEdges.isEmpty()) {
                            final SWCPoint parentNode = graph.getEdgeSource(inEdges.iterator().next());
                            if (parentNode != null) {
                                // Create PointInImage for the branch point
                                final PointInImage branchPoint = new PointInImage(parentNode.x, parentNode.y, parentNode.z);
                                path.setBranchFrom(parentPath, branchPoint);
                            }
                        }
                    }
                }

                segmentToPath.put(segment, path);
                tree.add(path);
                processed.add(segment);
                madeProgress = true;
            }
        }

        return tree;
    }

    /**
     * Creates a Path from a HierarchySegment.
     * Nodes are added from segmentRoot toward leaf (root-to-tip direction).
     */
    private Path createPathFromSegment(final HierarchySegment segment) {
        if (segment.nodes.isEmpty()) return null;

        final Path path = new Path(
                spacing[0],  // x spacing
                spacing[1],  // y spacing
                dims.length > 2 ? spacing[2] : 1.0,  // z spacing
                ""  // units
        );

        // Nodes are stored leaf-to-root, but we need root-to-leaf for the path
        // Reverse to get segmentRoot first, leaf last
        final List<SWCPoint> orderedNodes = new ArrayList<>(segment.nodes);
        Collections.reverse(orderedNodes);

        for (final SWCPoint node : orderedNodes) {
            path.addPointDouble(node.x, node.y, node.z);
            // Set radius if available
            if (path.size() > 0) {
                path.setRadius(node.radius, path.size() - 1);
            }
        }

        return path;
    }

    /**
     * Computes node ownership: which leaf owns each node based on longest intensity path.
     * <p>
     * APP2 assigns each node to the leaf that has the longest total intensity
     * along the path from leaf to that node.
     * </p>
     */
    private void computeNodeOwnership(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf) {

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Find all leaves (nodes with no children, excluding root)
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        // For each leaf, trace back to root and compute intensity distances
        for (final SWCPoint leaf : leaves) {
            SWCPoint current = leaf;
            double distFromLeaf = 0;

            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);

                // Update ownership if this path is longer
                final double existingDist = nodeDistToLeaf.getOrDefault(current, -1.0);
                if (distFromLeaf > existingDist) {
                    nodeOwner.put(current, leaf);
                    nodeDistToLeaf.put(current, distFromLeaf);
                }

                // Get normalized intensity at current node
                final long[] pos = new long[dims.length];
                pos[0] = Math.round(current.x / spacing[0]);
                pos[1] = Math.round(current.y / spacing[1]);
                if (dims.length > 2) pos[2] = Math.round(current.z / spacing[2]);

                double intensity = 0;
                if (isInBounds(pos)) {
                    ra.setPosition(pos);
                    intensity = ra.get().getRealDouble();
                }

                // Normalize intensity
                final double normalizedIntensity = (maxIntensity > 0) ? intensity / maxIntensity : 0;
                distFromLeaf += normalizedIntensity;

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }
        }
    }

    /**
     * Builds hierarchy segments by grouping nodes by their owning leaf.
     * Each segment contains all nodes owned by a particular leaf.
     */
    private void buildHierarchySegments(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf,
            final Map<SWCPoint, HierarchySegment> leafToSegment) {

        final SWCPoint root = findGraphRoot(graph);

        // Group nodes by owning leaf
        final Map<SWCPoint, List<SWCPoint>> leafNodes = new HashMap<>();
        for (final Map.Entry<SWCPoint, SWCPoint> entry : nodeOwner.entrySet()) {
            final SWCPoint node = entry.getKey();
            final SWCPoint leaf = entry.getValue();
            leafNodes.computeIfAbsent(leaf, k -> new ArrayList<>()).add(node);
        }

        // Create segments
        for (final Map.Entry<SWCPoint, List<SWCPoint>> entry : leafNodes.entrySet()) {
            final SWCPoint leaf = entry.getKey();
            final List<SWCPoint> nodes = entry.getValue();

            // Sort nodes by distance from leaf (closest first)
            nodes.sort((a, b) -> Double.compare(
                    nodeDistToLeaf.getOrDefault(a, 0.0),
                    nodeDistToLeaf.getOrDefault(b, 0.0)
            ));

            // Find segment root (the node in this segment closest to root, i.e., furthest from leaf)
            SWCPoint segmentRoot = null;
            if (!nodes.isEmpty()) {
                segmentRoot = nodes.get(nodes.size() - 1);  // Last = furthest from leaf
            }

            // Compute segment length (intensity-weighted)
            final double segmentLength = nodeDistToLeaf.getOrDefault(leaf, 0.0);

            final HierarchySegment segment = new HierarchySegment(leaf, segmentRoot, nodes, segmentLength);
            leafToSegment.put(leaf, segment);
        }

        // Establish parent-child relationships between segments
        for (final HierarchySegment segment : leafToSegment.values()) {
            if (segment.segmentRoot == null || segment.segmentRoot.equals(root)) {
                continue;  // Root segment has no parent
            }

            // Find parent: walk back from segmentRoot to find first node owned by different leaf
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(segment.segmentRoot);
            if (inEdges.isEmpty()) continue;

            SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());
            while (parent != null && nodeOwner.get(parent) == segment.leaf) {
                final Set<SWCWeightedEdge> parentInEdges = graph.incomingEdgesOf(parent);
                if (parentInEdges.isEmpty()) break;
                parent = graph.getEdgeSource(parentInEdges.iterator().next());
            }

            if (parent != null) {
                final SWCPoint parentLeaf = nodeOwner.get(parent);
                if (parentLeaf != null && leafToSegment.containsKey(parentLeaf)) {
                    segment.parent = leafToSegment.get(parentLeaf);
                }
            }
        }
    }

    /**
     * Represents a hierarchical segment as defined by APP2.
     * Each segment connects a leaf to its "ownership boundary" - the point where
     * another leaf's influence takes over.
     */
    protected static class HierarchySegment {
        final SWCPoint leaf;           // The leaf node that "owns" this segment
        final SWCPoint segmentRoot;    // Where this segment connects to parent segment
        final List<SWCPoint> nodes;    // All nodes in this segment (leaf to segmentRoot)
        final double length;           // Intensity-based length
        HierarchySegment parent;       // Parent segment (null for root segment)

        HierarchySegment(SWCPoint leaf, SWCPoint segmentRoot, List<SWCPoint> nodes, double length) {
            this.leaf = leaf;
            this.segmentRoot = segmentRoot;
            this.nodes = nodes;
            this.length = length;
            this.parent = null;
        }
    }
}
