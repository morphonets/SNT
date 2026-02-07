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

package sc.fiji.snt.tracing.auto.gwdt;

import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;


/**
 * In-memory array storage backend using ImgLib2 ArrayImgs.
 * Fast but memory-intensive. Best for images < 500MB.
 */
public class ArrayStorageBackend implements StorageBackend {

    private final long[] dims;
    private Img<DoubleType> gwdtImage;
    private Img<DoubleType> distances;
    private Img<IntType> parents;
    private Img<ByteType> state;
    private double maxGWDT;
    private int cnnType = 2;  // APP2's cnn_type: 1=6-conn, 2=18-conn (default), 3=26-conn
    
    // ALIVE index tracking for efficient graph building
    private Set<Long> aliveIndices = null;
    private boolean trackAlive = true;  // ON by default for array storage

    public ArrayStorageBackend(long[] dims) {
        this.dims = dims.clone();
    }

    public void computeGWDT(RandomAccessibleInterval source, double threshold,
                            double[] spacing, double minIntensity, double maxIntensity) {
        final int nDims = source.numDimensions();
        gwdtImage = ArrayImgs.doubles(dims);
        final Img<ByteType> gwdtState = ArrayImgs.bytes(dims);

        // Priority queue: (index, distance)
        final PriorityQueue<long[]> heap = new PriorityQueue<>(
                Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1]))
        );

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<? extends RealType<?>> typedSource =
                (RandomAccessibleInterval<? extends RealType<?>>) source;
        final RandomAccess<? extends RealType<?>> srcRA = typedSource.randomAccess();
        final RandomAccess<DoubleType> gwdtRA = gwdtImage.randomAccess();
        final RandomAccess<ByteType> gwdtStateRA = gwdtState.randomAccess();

        // Initialize: background pixels are seeds with distance 0
        final Cursor<? extends RealType<?>> srcCursor = Views.flatIterable(typedSource).localizingCursor();
        final Cursor<ByteType> stateCursor = Views.flatIterable(gwdtState).cursor();
        final Cursor<DoubleType> gwdtCursor = Views.flatIterable(gwdtImage).cursor();

        while (srcCursor.hasNext()) {
            srcCursor.fwd();
            stateCursor.fwd();
            gwdtCursor.fwd();

            final double val = srcCursor.get().getRealDouble();

            if (val <= threshold) {
                // Background pixel - seed
                stateCursor.get().set(AbstractGWDTTracer.ALIVE);
                gwdtCursor.get().set(0.0);
            } else {
                // Foreground pixel
                stateCursor.get().set(AbstractGWDTTracer.FAR);
                gwdtCursor.get().set(Double.MAX_VALUE);
            }
        }

        // Add neighbors of ALIVE pixels to heap
        final Cursor<ByteType> initCursor = gwdtState.localizingCursor();
        final long[] pos = new long[nDims];
        while (initCursor.hasNext()) {
            initCursor.fwd();
            if (initCursor.get().get() == AbstractGWDTTracer.ALIVE) {
                initCursor.localize(pos);
                addGWDTNeighborsImg(pos, gwdtStateRA, heap, srcRA, gwdtRA, threshold, spacing);
            }
        }

        // Fast Marching main loop
        final long[] currentPos = new long[nDims];
        while (!heap.isEmpty()) {
            final long[] entry = heap.poll();
            final long currentIdx = entry[0];
            indexToPos(currentIdx, currentPos);

            gwdtStateRA.setPosition(currentPos);
            if (gwdtStateRA.get().get() == AbstractGWDTTracer.ALIVE) continue;

            gwdtStateRA.get().set(AbstractGWDTTracer.ALIVE);
            addGWDTNeighborsImg(currentPos, gwdtStateRA, heap, srcRA, gwdtRA, threshold, spacing);
        }

        // Find max GWDT
        maxGWDT = 0;
        final Cursor<DoubleType> maxCursor = gwdtImage.cursor();
        while (maxCursor.hasNext()) {
            maxCursor.fwd();
            final double v = maxCursor.get().getRealDouble();
            if (v != Double.MAX_VALUE && v > maxGWDT) {
                maxGWDT = v;
            }
        }
        if (maxGWDT == 0) maxGWDT = 1;
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
     * Add neighbors to the GWDT Fast Marching heap using imglib2 RandomAccess.
     */
    private void addGWDTNeighborsImg(final long[] pos,
                                     final RandomAccess<ByteType> stateRA,
                                     final PriorityQueue<long[]> heap,
                                     final RandomAccess<? extends RealType<?>> srcRA,
                                     final RandomAccess<DoubleType> gwdtRA,
                                     final double threshold,
                                     final double[] spacing) {
        gwdtRA.setPosition(pos);
        final double currentDist = gwdtRA.get().get();

        // Use cnnType-connectivity (matching Fast Marching tree)
        final int nDims = srcRA.numDimensions();
        final long[] neighborPos = new long[nDims];

        iterateNeighborsForFM(pos, neighborPos, nDims, spacing, (euclideanDist) -> {
            final long neighborIdx = posToIndex(neighborPos);

            stateRA.setPosition(neighborPos);
            if (stateRA.get().get() == AbstractGWDTTracer.ALIVE) return;

            // Get intensity at neighbor
            srcRA.setPosition(neighborPos);
            final double intensity = srcRA.get().getRealDouble();

            // Skip background in GWDT propagation
            if (intensity <= threshold) return;

            // Edge weight = intensity (gray-weighted)
            final double newDist = currentDist + Math.max(intensity, 1e-6);

            gwdtRA.setPosition(neighborPos);
            final double oldDist = gwdtRA.get().get();
            if (newDist < oldDist) {
                gwdtRA.get().set(newDist);
                stateRA.setPosition(neighborPos);
                stateRA.get().set(AbstractGWDTTracer.TRIAL);
                heap.offer(new long[]{neighborIdx, Double.doubleToLongBits(newDist)});
            }
        });
    }


    /**
     * Iterate over neighbors with connectivity filtering.
     * Calls the consumer with valid neighbors and their Euclidean distance.
     */
    public void iterateNeighborsForFM(final long[] pos, final long[] neighborPos,
                                       final int nDims, final double[] spacing, final java.util.function.DoubleConsumer consumer) {
        final int[] delta = new int[nDims];
        iterateNeighbors(delta, 0, nDims, () -> {
            boolean isCenter = true;
            double euclideanDistSq = 0;
            int offset = 0;

            for (int d = 0; d < nDims; d++) {
                neighborPos[d] = pos[d] + delta[d];
                if (delta[d] != 0) {
                    isCenter = false;
                    euclideanDistSq += (delta[d] * spacing[d]) * (delta[d] * spacing[d]);
                    offset += Math.abs(delta[d]);
                }
                if (neighborPos[d] < 0 || neighborPos[d] >= dims[d]) return;
            }
            if (isCenter) return;

            // connectivity filter 2D and 3D (the offset is the Manhattan distance)
            if (offset > cnnType) return;

            consumer.accept(Math.sqrt(euclideanDistSq));
        });
    }

    /**
     * Iterates over all neighbor offsets (26-connectivity in 3D).
     * Helper method for neighbor iteration.
     */
    private void iterateNeighbors(final int[] delta, final int dim, final int nDims,
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

    @Override
    public double getGWDT(long index) {
        final long[] pos = indexToPos(index);
        final RandomAccess<DoubleType> ra = gwdtImage.randomAccess();
        ra.setPosition(pos);
        return ra.get().getRealDouble();
    }

    @Override
    public double getMaxGWDT() {
        return maxGWDT;
    }

    @Override
    public void initializeFastMarching(long[] dims, long seedIndex) {
        this.distances = ArrayImgs.doubles(dims);
        this.parents = ArrayImgs.ints(dims);
        this.state = ArrayImgs.bytes(dims);

        // Initialize all to defaults
        for (final DoubleType t : distances) t.set(Double.MAX_VALUE);
        for (final IntType t : parents) t.set(-1);
        // state defaults to 0 (FAR)
        
        // Initialize ALIVE tracking if enabled
        if (trackAlive) {
            // Estimate: typical tree is 0.1-1% of voxels
            int expectedSize = (int) Math.min(100000, computeTotalVoxels(dims) / 100);
            aliveIndices = new HashSet<>(expectedSize);
        }
    }

    @Override
    public void setDistance(long index, double distance) {
        final long[] pos = indexToPos(index);
        final RandomAccess<DoubleType> ra = distances.randomAccess();
        ra.setPosition(pos);
        ra.get().set(distance);
    }

    @Override
    public double getDistance(long index) {
        final long[] pos = indexToPos(index);
        final RandomAccess<DoubleType> ra = distances.randomAccess();
        ra.setPosition(pos);
        return ra.get().getRealDouble();
    }

    @Override
    public void setParent(long index, long parentIndex) {
        final long[] pos = indexToPos(index);
        final RandomAccess<IntType> ra = parents.randomAccess();
        ra.setPosition(pos);
        ra.get().set((int) parentIndex);
    }

    @Override
    public long getParent(long index) {
        final long[] pos = indexToPos(index);
        final RandomAccess<IntType> ra = parents.randomAccess();
        ra.setPosition(pos);
        return ra.get().getInteger();
    }

    @Override
    public void setState(long index, byte stateValue) {
        final long[] pos = indexToPos(index);
        final RandomAccess<ByteType> ra = state.randomAccess();
        ra.setPosition(pos);
        ra.get().set(stateValue);
        
        // Track ALIVE indices if enabled
        if (trackAlive && stateValue == AbstractGWDTTracer.ALIVE) {
            aliveIndices.add(index);
        }
    }

    @Override
    public byte getState(long index) {
        final long[] pos = indexToPos(index);
        final RandomAccess<ByteType> ra = state.randomAccess();
        ra.setPosition(pos);
        return ra.get().getByte();
    }

    @Override
    public DirectedWeightedGraph buildGraph(long[] dims, double[] spacing, double threshold) {
        final DirectedWeightedGraph graph = new DirectedWeightedGraph();

        final RandomAccess<ByteType> stateRA = state.randomAccess();
        final RandomAccess<IntType> parentRA = parents.randomAccess();

        // Map from linear index to SWCPoint
        final Map<Long, SWCPoint> indexToNode = new HashMap<>();

        final long[] pos = new long[dims.length];
        
        // Use tracked ALIVE indices if available, otherwise full scan
        if (trackAlive && aliveIndices != null && !aliveIndices.isEmpty()) {
            SNTUtils.log("Building graph from " + aliveIndices.size() + " tracked ALIVE nodes");
            
            // First pass: create nodes for tracked ALIVE voxels only
            for (long idx : aliveIndices) {
                indexToPos(idx, pos);
                
                // Convert voxel position to physical coordinates
                final double x = pos[0] * spacing[0];
                final double y = pos[1] * spacing[1];
                final double z = (dims.length > 2) ? pos[2] * spacing[2] : 0;

                // Create SWCPoint with temporary radius (will be recalculated later)
                final SWCPoint node = new SWCPoint(
                        -1,  // id (will be set by graph)
                        2,   // type (dendrite by default)
                        x, y, z,
                        1.0, // temporary radius
                        -1   // parent id (will be set below)
                );

                indexToNode.put(idx, node);
                graph.addVertex(node);
            }
        } else {
            SNTUtils.log("Building graph with full-volume scan (tracking " + 
                    (trackAlive ? "not initialized" : "disabled") + ")");
            
            // First pass: create nodes for all ALIVE voxels (full scan)
            final long totalVoxels = computeTotalVoxels(dims);
            for (long idx = 0; idx < totalVoxels; idx++) {
                indexToPos(idx, pos);
                stateRA.setPosition(pos);

                if (stateRA.get().get() == AbstractGWDTTracer.ALIVE) {
                    // Convert voxel position to physical coordinates
                    final double x = pos[0] * spacing[0];
                    final double y = pos[1] * spacing[1];
                    final double z = (dims.length > 2) ? pos[2] * spacing[2] : 0;

                    // Create SWCPoint with temporary radius (will be recalculated later)
                    final SWCPoint node = new SWCPoint(
                            -1,  // id (will be set by graph)
                            2,   // type (dendrite by default)
                            x, y, z,
                            1.0, // temporary radius
                            -1   // parent id (will be set below)
                    );

                    indexToNode.put(idx, node);
                    graph.addVertex(node);
                }
            }
        }

        // Second pass: create edges based on parent pointers
        for (final Map.Entry<Long, SWCPoint> entry : indexToNode.entrySet()) {
            final long idx = entry.getKey();
            final SWCPoint node = entry.getValue();

            indexToPos(idx, pos);
            parentRA.setPosition(pos);
            final long parentIdx = parentRA.get().getInteger();

            // Skip if this is the root (parent points to itself)
            if (parentIdx == idx || parentIdx < 0) {
                continue;
            }

            // Find parent node
            final SWCPoint parentNode = indexToNode.get(parentIdx);
            if (parentNode != null) {
                // Add edge from parent to child
                graph.addEdge(parentNode, node, new SWCWeightedEdge());
            }
        }

        return graph;
    }

    private long computeTotalVoxels(long[] dims) {
        long total = 1;
        for (long d : dims) total *= d;
        return total;
    }

    private void indexToPos(long idx, long[] pos) {
        long remaining = idx;
        for (int d = 0; d < dims.length; d++) {
            pos[d] = remaining % dims[d];
            remaining /= dims[d];
        }
    }

    private double euclideanDistance(SWCPoint p1, SWCPoint p2) {
        return p1.distanceTo(p2);
    }

    @Override
    public long estimateMemoryUsage() {
        long totalVoxels = 1;
        for (long d : dims) totalVoxels *= d;
        // GWDT (8) + distances (8) + parents (4) + state (1) = 21 bytes per voxel
        return totalVoxels * 21;
    }

    @Override
    public String getBackendType() {
        return "Array (in-memory)";
    }
    
    @Override
    public void setTrackAliveIndices(boolean track) {
        this.trackAlive = track;
    }
    
    public Set<Long> getAliveIndices() {
        return aliveIndices;
    }

    @Override
    public void dispose() {
        // Java GC will handle cleanup
        gwdtImage = null;
        distances = null;
        parents = null;
        state = null;
        if (aliveIndices != null) {
            aliveIndices.clear();
            aliveIndices = null;
        }
    }

    private long[] indexToPos(long idx) {
        final long[] pos = new long[dims.length];
        long remaining = idx;
        for (int d = 0; d < dims.length; d++) {
            pos[d] = remaining % dims[d];
            remaining /= dims[d];
        }
        return pos;
    }

    /**
     * Convert position to linear index.
     */
    private long posToIndex(long[] pos) {
        long idx = 0;
        long stride = 1;
        for (int d = 0; d < pos.length; d++) {
            idx += pos[d] * stride;
            stride *= dims[d];
        }
        return idx;
    }
}
