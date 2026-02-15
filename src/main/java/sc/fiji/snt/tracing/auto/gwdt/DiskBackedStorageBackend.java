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

import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.DiskCachedCellImgFactory;
import net.imglib2.cache.img.DiskCachedCellImgOptions;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.util.SWCPoint;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Disk-backed storage backend for very large images.
 * <p>
 * Uses ImgLib2's {@link net.imglib2.cache.img.DiskCachedCellImg} to store
 * GWDT and Fast Marching data on disk with LRU caching in memory.
 * Can process images of arbitrary size with bounded memory usage.
 * </p>
 * <p>
 * Trade-offs:
 * <ul>
 *   <li>Memory: Constant (~500MB-1GB) regardless of image size</li>
 *   <li>Speed: 2-5× slower than ArrayStorageBackend due to disk I/O</li>
 *   <li>Disk: Requires ~25 bytes per voxel temporary disk space</li>
 *   <li>Best for: Images > 2GB or when RAM is limited</li>
 * </ul>
 * </p>
 * <p>
 * Temporary files are stored in the system temp directory and automatically
 * deleted when {@link #dispose()} is called.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class DiskBackedStorageBackend implements StorageBackend {

    private final long[] dims;
    private int cnnType = 2;

    // Disk-backed images
    private Img<DoubleType> gwdtImage;
    private Img<DoubleType> distances;
    private Img<LongType> parents;
    private Img<ByteType> state;

    private double maxGWDT;
    private long seedIndex = -1;

    // Cache configuration
    private static final int DEFAULT_CELL_SIZE = 64;  // 64^3 cells
    private static final int DEFAULT_CACHE_SIZE = 1000;  // ~1000 cells in RAM
    private final int cellSize;
    private final int cacheSize;

    // Temp directory for disk cache
    private File tempDir;
    
    // ALIVE index tracking (ENABLED by default - critical for disk-backed performance)
    private boolean trackAlive = true;
    private Set<Long> aliveIndices = null;

    public DiskBackedStorageBackend(long[] dims) {
        this(dims, DEFAULT_CELL_SIZE, DEFAULT_CACHE_SIZE);
    }

    /**
     * Creates a disk-backed storage backend with custom cache settings.
     *
     * @param dims      image dimensions
     * @param cellSize  size of each cache cell (e.g., 64 for 64³ cells)
     * @param cacheSize number of cells to keep in RAM (e.g., 1000)
     */
    public DiskBackedStorageBackend(long[] dims, int cellSize, int cacheSize) {
        this.dims = dims.clone();
        this.cellSize = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
        this.cacheSize = cacheSize > 0 ? cacheSize : DEFAULT_CACHE_SIZE;
        try {
            this.tempDir = Files.createTempDirectory("gwdt-cache-").toFile();
            tempDir.deleteOnExit();
            SNTUtils.log("Disk-backed storage using temp dir: " + tempDir.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory for disk cache", e);
        }
    }

    @Override
    public void computeGWDT(RandomAccessibleInterval<?> source, double threshold,
                            double[] spacing, double minIntensity, double maxIntensity) {
        final int nDims = source.numDimensions();

        // Create disk-backed images for GWDT computation
        final DiskCachedCellImgOptions options = createCacheOptions();

        gwdtImage = new DiskCachedCellImgFactory<>(new DoubleType(), options).create(dims);
        final Img<ByteType> gwdtState = new DiskCachedCellImgFactory<>(new ByteType(), options).create(dims);

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

        SNTUtils.log("Initializing disk-backed GWDT...");

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

        SNTUtils.log("Finding initial seeds...");

        // Add neighbors of ALIVE pixels to heap
        final Cursor<ByteType> initCursor = gwdtState.localizingCursor();
        final long[] pos = new long[nDims];
        while (initCursor.hasNext()) {
            initCursor.fwd();
            if (initCursor.get().get() == AbstractGWDTTracer.ALIVE) {
                initCursor.localize(pos);
                addGWDTNeighborsToHeap(pos, gwdtStateRA, heap, srcRA, gwdtRA, threshold, spacing);
            }
        }

        SNTUtils.log("Running Fast Marching for GWDT...");

        // Fast Marching main loop
        final long[] currentPos = new long[nDims];
        long processedVoxels = 0;
        final long totalVoxels = computeTotalVoxels();
        long lastLogTime = System.currentTimeMillis();

        while (!heap.isEmpty()) {
            final long[] entry = heap.poll();
            final long currentIdx = entry[0];
            indexToPos(currentIdx, currentPos);

            gwdtStateRA.setPosition(currentPos);
            if (gwdtStateRA.get().get() == AbstractGWDTTracer.ALIVE) continue;

            gwdtStateRA.get().set(AbstractGWDTTracer.ALIVE);
            addGWDTNeighborsToHeap(currentPos, gwdtStateRA, heap, srcRA, gwdtRA, threshold, spacing);

            processedVoxels++;

            // Progress logging every 5 seconds
            final long currentTime = System.currentTimeMillis();
            if (currentTime - lastLogTime > 5000) {
                final double progress = 100.0 * processedVoxels / totalVoxels;
                SNTUtils.log(String.format("  GWDT progress: %.1f%% (%d/%d voxels)",
                        progress, processedVoxels, totalVoxels));
                lastLogTime = currentTime;
            }
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

        SNTUtils.log("GWDT computation complete. Max GWDT: " + maxGWDT);
    }

    /**
     * Add neighbors to GWDT Fast Marching heap.
     */
    private void addGWDTNeighborsToHeap(final long[] pos,
                                        final RandomAccess<ByteType> stateRA,
                                        final PriorityQueue<long[]> heap,
                                        final RandomAccess<? extends RealType<?>> srcRA,
                                        final RandomAccess<DoubleType> gwdtRA,
                                        final double threshold,
                                        final double[] spacing) {
        gwdtRA.setPosition(pos);
        final double currentDist = gwdtRA.get().get();

        final int nDims = pos.length;
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
        this.seedIndex = seedIndex;

        SNTUtils.log("Initializing disk-backed Fast Marching structures...");

        final DiskCachedCellImgOptions options = createCacheOptions();

        this.distances = new DiskCachedCellImgFactory<>(new DoubleType(), options).create(dims);
        this.parents = new DiskCachedCellImgFactory<>(new LongType(), options).create(dims);
        this.state = new DiskCachedCellImgFactory<>(new ByteType(), options).create(dims);

        // Initialize all to defaults
        for (final DoubleType t : distances) t.set(Double.MAX_VALUE);
        for (final LongType t : parents) t.set(-1);
        // state defaults to 0 (FAR)
        
        // Initialize ALIVE tracking if enabled (critical for disk-backed performance)
        if (trackAlive) {
            int expectedSize = (int) Math.min(100000, computeTotalVoxels() / 1000);
            this.aliveIndices = new HashSet<>(expectedSize);
            SNTUtils.log("ALIVE index tracking enabled");
        }

        SNTUtils.log("Fast Marching structures initialized");
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
        final RandomAccess<LongType> ra = parents.randomAccess();
        ra.setPosition(pos);
        ra.get().set(parentIndex);
    }

    @Override
    public long getParent(long index) {
        final long[] pos = indexToPos(index);
        final RandomAccess<LongType> ra = parents.randomAccess();
        ra.setPosition(pos);
        return ra.get().get();
    }

    @Override
    public void setState(long index, byte stateValue) {
        final long[] pos = indexToPos(index);
        final RandomAccess<ByteType> ra = state.randomAccess();
        ra.setPosition(pos);
        ra.get().set(stateValue);
        
        // Track ALIVE indices if enabled
        if (stateValue == AbstractGWDTTracer.ALIVE && trackAlive && aliveIndices != null) {
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
        if (seedIndex < 0) {
            throw new IllegalStateException("Seed index not set. Call initializeFastMarching first.");
        }

        SNTUtils.log("Building graph from disk-backed data...");

        final DirectedWeightedGraph graph = new DirectedWeightedGraph();

        // Map from linear index to SWCPoint
        final Map<Long, SWCPoint> indexToNode = new HashMap<>();
        int nodeId = 1;

        // Create root node first
        final long[] pos = new long[dims.length];
        indexToPos(seedIndex, pos);
        final double rootX = pos[0] * spacing[0];
        final double rootY = pos[1] * spacing[1];
        final double rootZ = (dims.length > 2) ? pos[2] * spacing[2] : 0;
        final SWCPoint rootNode = new SWCPoint(nodeId++, Path.SWC_SOMA, rootX, rootY, rootZ, 1.0, -1);
        graph.addVertex(rootNode);
        indexToNode.put(seedIndex, rootNode);

        long nodesFound = 0;
        
        // Use tracked ALIVE indices if available, otherwise full cursor scan
        if (trackAlive && aliveIndices != null && !aliveIndices.isEmpty()) {
            SNTUtils.log("Building graph from " + aliveIndices.size() + " tracked ALIVE nodes (optimized)");
            
            // Only iterate tracked indices - MUCH faster than full scan
            for (long idx : aliveIndices) {
                if (idx == seedIndex) continue; // Already created root
                
                indexToPos(idx, pos);
                final double x = pos[0] * spacing[0];
                final double y = pos[1] * spacing[1];
                final double z = (dims.length > 2) ? pos[2] * spacing[2] : 0;

                final SWCPoint node = new SWCPoint(nodeId++, 2, x, y, z, 1.0, -1);
                graph.addVertex(node);
                indexToNode.put(idx, node);
                
                nodesFound++;
                if (nodesFound % 10000 == 0) {
                    SNTUtils.log("  Processed " + nodesFound + " nodes...");
                }
            }
        } else {
            SNTUtils.log("Building graph with full-volume scan (tracking " + 
                    (trackAlive ? "not initialized" : "disabled") + ")");
            
            // Fall back to full cursor scan (slow for disk-backed!)
            final Cursor<ByteType> stateCursor = state.localizingCursor();

            while (stateCursor.hasNext()) {
                stateCursor.fwd();
                if (stateCursor.get().get() != AbstractGWDTTracer.ALIVE) continue;

                stateCursor.localize(pos);
                final long idx = posToIndex(pos);

                if (idx == seedIndex) continue; // Already created root

                final double x = pos[0] * spacing[0];
                final double y = pos[1] * spacing[1];
                final double z = (dims.length > 2) ? pos[2] * spacing[2] : 0;

                final SWCPoint node = new SWCPoint(nodeId++, 2, x, y, z, 1.0, -1);
                graph.addVertex(node);
                indexToNode.put(idx, node);

                nodesFound++;
                if (nodesFound % 10000 == 0) {
                    SNTUtils.log("  Found " + nodesFound + " nodes...");
                }
            }
        }

        SNTUtils.log("Found " + indexToNode.size() + " nodes total");
        SNTUtils.log("Creating edges from parent pointers...");

        // Create edges based on parent pointers
        final RandomAccess<LongType> parentRA = parents.randomAccess();
        int edgesCreated = 0;
        for (final Map.Entry<Long, SWCPoint> entry : indexToNode.entrySet()) {
            final long idx = entry.getKey();
            final SWCPoint node = entry.getValue();

            indexToPos(idx, pos);
            parentRA.setPosition(pos);
            final long parentIdx = parentRA.get().get();

            // Skip if root
            if (parentIdx == idx || parentIdx < 0) continue;

            final SWCPoint parentNode = indexToNode.get(parentIdx);
            if (parentNode != null) {
                graph.addEdge(parentNode, node, new SWCWeightedEdge());
                edgesCreated++;
            }
        }

        graph.setRoot(rootNode);
        SNTUtils.log("Graph created: " + indexToNode.size() + " nodes, " + edgesCreated + " edges");

        return graph;
    }

    @Override
    public long estimateMemoryUsage() {
        // Disk-backed uses fixed cache size
        final long cellVolume = (long) cellSize * cellSize * (dims.length > 2 ? cellSize : 1);
        final long boundedCacheSize = cacheSize;
        
        // GWDT (8) + distances (8) + parents (8) + state (1) = 25 bytes per voxel in cache
        return cellVolume * boundedCacheSize * 25;
    }

    @Override
    public String getBackendType() {
        return "Disk-backed (out-of-core)";
    }

    public Set<Long> getAliveIndices() {
        return aliveIndices;
    }

    @Override
    public void dispose() {
        SNTUtils.log("Cleaning up disk-backed storage...");

        // Clear references to allow cache cleanup
        gwdtImage = null;
        distances = null;
        parents = null;
        state = null;
        
        if (aliveIndices != null) {
            aliveIndices.clear();
            aliveIndices = null;
        }

        // Delete temp directory
        if (tempDir != null && tempDir.exists()) {
            deleteDirectory(tempDir);
            SNTUtils.log("Temp directory deleted: " + tempDir.getAbsolutePath());
        }
    }

    /**
     * Recursively delete directory and contents.
     */
    private void deleteDirectory(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Create disk cache options with custom temp directory.
     */
    private DiskCachedCellImgOptions createCacheOptions() {
        return DiskCachedCellImgOptions.options()
                .cellDimensions(cellSize, cellSize, dims.length > 2 ? cellSize : 1)
                .cacheType(DiskCachedCellImgOptions.CacheType.BOUNDED)
                .maxCacheSize(cacheSize)
                .tempDirectory(tempDir.toPath());
    }

    // ==================== Utility Methods ====================

    /**
     * Sets the connectivity type for neighbor iteration.
     */
    public void setConnectivityType(final int type) {
        this.cnnType = Math.max(1, Math.min(3, type));
    }
    
    @Override
    public void setTrackAliveIndices(boolean track) {
        this.trackAlive = track;
        if (!track) {
            SNTUtils.log("Warning: Disabling ALIVE tracking in disk-backed storage will cause very slow buildGraph()");
        }
    }

    /**
     * Iterate over neighbors with connectivity filtering.
     */
    private void iterateNeighborsForFM(final long[] pos, final long[] neighborPos,
                                       final int nDims, final double[] spacing,
                                       final java.util.function.DoubleConsumer consumer) {
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

            // Connectivity filter
            if (offset > cnnType) return;

            consumer.accept(Math.sqrt(euclideanDistSq));
        });
    }

    /**
     * Iterates over all neighbor offsets.
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

    /**
     * Convert linear index to position.
     */
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
     * Convert linear index to position (in-place).
     */
    private void indexToPos(long idx, long[] pos) {
        long remaining = idx;
        for (int d = 0; d < dims.length; d++) {
            pos[d] = remaining % dims[d];
            remaining /= dims[d];
        }
    }

    /**
     * Compute total voxels in image.
     */
    private long computeTotalVoxels() {
        long total = 1;
        for (long d : dims) total *= d;
        return total;
    }
}
