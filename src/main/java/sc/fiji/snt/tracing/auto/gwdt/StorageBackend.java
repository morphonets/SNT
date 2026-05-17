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

import net.imglib2.RandomAccessibleInterval;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;

import java.util.Set;

/**
 * Storage backend for GWDT tracing data structures.
 * <p>
 * Implementations can use in-memory arrays ({@link ArrayStorageBackend}),
 * disk-backed storage, or sparse representations for memory efficiency.
 * </p>
 * The backend handles storage for:
 * <ul>
 *   <li>GWDT (Gray-Weighted Distance Transform) values</li>
 *   <li>Fast Marching distances</li>
 *   <li>Fast Marching parent pointers</li>
 *   <li>Fast Marching state (FAR/TRIAL/ALIVE)</li>
 * </ul>
 *
 * @author Tiago Ferreira
 */
public interface StorageBackend {

    /**
     * Compute Gray-Weighted Distance Transform.
     * <p>
     * For each foreground pixel, GWDT = sum of intensities along shortest path to background.
     * Uses Fast Marching with background pixels as seeds.
     * </p>
     *
     * @param source       input image (untyped RandomAccessibleInterval)
     * @param threshold    background threshold
     * @param spacing      voxel spacing [x, y, z]
     * @param minIntensity minimum intensity in source (for normalization)
     * @param maxIntensity maximum intensity in source (for normalization)
     */
    void computeGWDT(RandomAccessibleInterval<?> source, double threshold,
                     double[] spacing, double minIntensity, double maxIntensity);

    /**
     * Get GWDT value at linear index.
     *
     * @param index linear voxel index
     * @return GWDT value, or Double.MAX_VALUE if not computed
     */
    double getGWDT(long index);

    /**
     * Get maximum GWDT value across the entire image.
     *
     * @return maximum GWDT value
     */
    double getMaxGWDT();

    /**
     * Initialize Fast Marching data structures.
     * <p>
     * Allocates storage for distances, parents, and state arrays.
     * Initializes all values to defaults (distances=MAX_VALUE, parents=-1, state=FAR).
     * </p>
     *
     * @param dims      image dimensions
     * @param seedIndex linear index of seed voxel (root)
     */
    void initializeFastMarching(long[] dims, long seedIndex);

    /**
     * Set Fast Marching distance at linear index.
     *
     * @param index    linear voxel index
     * @param distance geodesic distance from seed
     */
    void setDistance(long index, double distance);

    /**
     * Get Fast Marching distance at linear index.
     *
     * @param index linear voxel index
     * @return distance from seed, or Double.MAX_VALUE if unreached
     */
    double getDistance(long index);

    /**
     * Set parent pointer at linear index.
     *
     * @param index       linear voxel index
     * @param parentIndex linear index of parent voxel in shortest path tree
     */
    void setParent(long index, long parentIndex);

    /**
     * Get parent pointer at linear index.
     *
     * @param index linear voxel index
     * @return parent index, or -1 if root or unset
     */
    long getParent(long index);

    /**
     * Set Fast Marching state at linear index.
     *
     * @param index      linear voxel index
     * @param stateValue one of FAR (0), TRIAL (1), or ALIVE (2)
     */
    void setState(long index, byte stateValue);

    /**
     * Get Fast Marching state at linear index.
     *
     * @param index linear voxel index
     * @return state value: FAR (0), TRIAL (1), or ALIVE (2)
     */
    byte getState(long index);

    /**
     * Build graph from computed Fast Marching data.
     * <p>
     * Walks the parent pointers to reconstruct the shortest path tree,
     * converting voxel coordinates to physical coordinates and creating
     * SWCPoint nodes connected by edges.
     * </p>
     *
     * @param dims      image dimensions
     * @param spacing   voxel spacing for coordinate conversion
     * @param threshold background threshold (for validation)
     * @return directed weighted graph representing the traced tree
     */
    DirectedWeightedGraph buildGraph(long[] dims, double[] spacing, double threshold);

    /**
     * Get estimated memory usage in bytes.
     *
     * @return estimated memory footprint of this backend
     */
    long estimateMemoryUsage();

    /**
     * Get storage backend type name for logging.
     *
     * @return human-readable backend type (e.g., "Array (in-memory)", "Sparse", "Disk-backed")
     */
    String getBackendType();

    /**
     * Enable or disable tracking of ALIVE indices during Fast Marching.
     * <p>
     * When enabled, the backend maintains a set of linear indices that have
     * been marked as ALIVE. This allows {@link #buildGraph} to iterate only
     * touched voxels instead of scanning the entire volume, providing dramatic
     * speedup for large sparse images.
     * </p>
     * <p>
     * Memory cost: ~24 bytes per ALIVE voxel
     * </p>
     * Recommended settings:
     * <ul>
     *   <li>ArrayStorageBackend: ON (default) - moderate speedup, low cost</li>
     *   <li>SparseStorageBackend: OFF (default) - already iterates keys only</li>
     *   <li>DiskBackedStorageBackend: ON (default) - essential for performance</li>
     * </ul>
     *
     * @param track true to track ALIVE indices, false to use full-volume scan
     */
    default void setTrackAliveIndices(boolean track) {
        // Default: no-op for backwards compatibility
    }

    /**
     * Returns the set of linear indices that have been marked as ALIVE during
     * Fast Marching. Only available when
     * {@link #setTrackAliveIndices(boolean) ALIVE tracking} is enabled;
     * returns {@code null} otherwise.
     *
     * @return the tracked ALIVE indices, or null if tracking is disabled
     */
    default Set<Long> getAliveIndices() {
        return null;
    }

    /**
     * Re-initializes Fast Marching data structures (distances, parents, state)
     * while preserving the pre-computed GWDT. Voxels whose linear indices appear
     * in {@code excludedIndices} are pre-marked as ALIVE with distance 0,
     * effectively making them impassable barriers for the next FM run.
     * <p>
     * Excluded voxels are stamped directly into the state array <em>after</em>
     * ALIVE tracking is initialized, so they are <b>not</b> added to the
     * tracked ALIVE set. This ensures {@link #buildGraph} only sees voxels
     * reached by the current FM pass, not leftover exclusions.
     * </p>
     * <p>
     * This enables a NeuTube-style recovery pass: after tracing from one seed,
     * the traced region (optionally dilated) is excluded and a new FM run can
     * proceed from a different seed without recomputing the GWDT.
     * </p>
     *
     * @param dims            image dimensions
     * @param seedIndex       linear index of the new seed voxel
     * @param excludedIndices set of linear indices to pre-mark as ALIVE
     *                        (impassable); may be {@code null} or empty
     */
    void reinitializeFastMarching(long[] dims, long seedIndex, Set<Long> excludedIndices);

    /**
     * Clean up resources (close files, free memory, delete temp files).
     * <p>
     * Called automatically after tracing completes. Implementations should
     * release any resources and allow garbage collection.
     * </p>
     */
    void dispose();
}
