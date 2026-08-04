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

/**
 * A bounded-memory priority queue for Dijkstra/Fast-Marching-style relaxation over non-negative edge weights that are
 * bounded by a known maximum ({@code maxEdgeWeight}). Implements Dial's algorithm ("bucket queue"): a circular array of
 * buckets, one per quantized distance step, reused as the frontier advances.
 * <p>
 * {@link java.util.PriorityQueue} grows without bound under the lazy-deletion update pattern Fast Marching uses
 * (a new entry is pushed every time a voxel's tentative distance improves; stale ones are only discarded when
 * eventually popped). For a large volume with many relaxation events, that queue can grow to a large multiple of the
 * voxel count and exhaust the heap. Once a voxel is finalized at distance {@code d}, no pending relaxation can ever
 * produce a value smaller than {@code d + minEdgeWeight}, so at any time all pending entries lie within a window of
 * width {@code maxEdgeWeight} above the current minimum; that fixed-width window is what the circular bucket array
 * represents, bounding memory by the number of buckets (fixed, derived from {@code maxEdgeWeight}) times the entries
 * currently active in that window, rather than by the total number of relaxation events over the entire run.
 * </p>
 * <p>
 * Classical Dial's algorithm gets away with arbitrary (e.g., First In, First Out) order <em>within</em> a bucket
 * because it assumes bucket width &le; the smallest possible edge weight, so no single relaxation hop can ever land a
 * neighbor in the same bucket as the vertex being processed; same-bucket entries are then mutually independent and
 * order doesn't matter. That assumption does not hold here: edge weight is {@code max(intensity, 1e-6)}, so it can be
 * arbitrarily small (a dim, near-threshold foreground voxel), while bucket width is sized off {@code maxEdgeWeight}.
 * A chain of low-weight hops can land several candidates for the same or different voxels in the same bucket. Popping
 * those in insertion order rather than by exact distance could finalize a voxel using a "good enough" candidate while
 * a strictly better one, reachable via a few more low-weight hops that also land in that bucket, is still pending; once
 * finalized (ALIVE) a voxel is never revisited, so that suboptimal value would be permanent. Using a small min-heap per
 * bucket (ordered by the exact, unquantized distance) restores exact-order processing among same-bucket entries,
 * matching a global heap's result exactly, while keeping memory bounded: each bucket's occupancy is limited by the
 * local frontier width, not by the total number of relaxation events over the whole run.
 * </p>
 * <p>
 * Only the voxel index is returned by {@link #pop()}, not the distance value that led to its  insertion: Fast Marching
 * implementations re-read the authoritative current distance for a voxel from its own distance image after popping,
 * precisely to detect and skip stale duplicate entries (see e.g. {@link DiskBackedStorageBackend}'s ALIVE-state check
 * after {@link #pop()}).
 * </p>
 *
 * @author Tiago Ferreira
 * @see DiskBackedStorageBackend
 */
class BucketPriorityQueue {

    /**
     * Default number of buckets spanning [0, maxEdgeWeight]; balances memory vs. quantization error.
     */
    private static final int DEFAULT_NUM_BUCKETS = 2048;

    private final MinHeap[] buckets;
    private final int numBuckets;
    private final double delta;

    /**
     * Absolute (unwrapped) index of the bucket the cursor currently sits at.
     */
    private long cursorBucket = 0;
    /**
     * Total number of live entries across all buckets (tracked so {@link #isEmpty()} is O(1)).
     */
    private long liveCount = 0;
    private boolean cursorInitialized = false;

    /**
     * @param maxEdgeWeight the maximum possible edge weight in the graph being relaxed
     *                      (e.g., the maximum foreground intensity for grayscale-weighted
     *                      GWDT). Must be &gt; 0.
     */
    BucketPriorityQueue(final double maxEdgeWeight) {
        this(maxEdgeWeight, DEFAULT_NUM_BUCKETS);
    }

    /**
     * @param maxEdgeWeight  the maximum possible edge weight in the graph being relaxed.
     *                       Must be &gt; 0.
     * @param numBucketsHint the number of buckets to span [0, maxEdgeWeight] with. Higher
     *                       values reduce the width of the per-bucket sliding window (and
     *                       thus the typical number of entries a single bucket's min-heap
     *                       needs to hold) at the cost of a proportionally larger (but
     *                       still fixed, image-size-independent) bucket array. Must be
     *                       &ge; 2.
     */
    BucketPriorityQueue(final double maxEdgeWeight, final int numBucketsHint) {
        final double safeMaxEdgeWeight = (maxEdgeWeight > 0 && Double.isFinite(maxEdgeWeight))
                ? maxEdgeWeight : 1.0;
        this.numBuckets = Math.max(2, numBucketsHint);
        // +1 bucket of safety margin against floating-point rounding at the window boundary.
        this.delta = safeMaxEdgeWeight / (numBuckets - 1);
        this.buckets = new MinHeap[numBuckets];
        for (int i = 0; i < numBuckets; i++) buckets[i] = new MinHeap();
    }

    /**
     * Inserts a voxel index at the given (non-negative, non-decreasing-in-aggregate)
     * distance. Distances passed to this method must never be smaller than the distance
     * of the most recently {@link #pop() popped} entry by more than {@code maxEdgeWeight}
     * (guaranteed by construction for Dijkstra/Fast-Marching relaxation with bounded edge
     * weights).
     */
    void push(final long voxelIndex, final double distance) {
        long distBucket = (long) Math.floor(distance / delta);
        if (!cursorInitialized) {
            cursorBucket = distBucket;
            cursorInitialized = true;
        } else if (distBucket < cursorBucket) {
            // Should not happen given the bounded-edge-weight invariant; clamp defensively
            // rather than risk corrupting an already-drained bucket. Note: this only affects
            // which bucket schedules the entry, not the (unclamped) authoritative distance
            // already recorded by the caller.
            distBucket = cursorBucket;
        }
        final int slot = (int) (distBucket % numBuckets);
        buckets[slot].push(voxelIndex, distance);
        liveCount++;
    }

    /**
     * Removes and returns the voxel index with the smallest exact distance among all
     * entries in the earliest non-empty bucket.
     *
     * @throws java.util.NoSuchElementException if the queue is empty
     */
    long pop() {
        if (liveCount == 0) throw new java.util.NoSuchElementException("BucketPriorityQueue is empty");
        int scanned = 0;
        while (true) {
            final MinHeap bucket = buckets[(int) (cursorBucket % numBuckets)];
            if (!bucket.isEmpty()) {
                liveCount--;
                return bucket.popMin();
            }
            cursorBucket++;
            if (++scanned > numBuckets) {
                // Defensive: liveCount said non-empty but every bucket is empty. Should be
                // unreachable given the bounded-window invariant; fail loudly rather than loop.
                throw new IllegalStateException(
                        "BucketPriorityQueue: liveCount=" + (liveCount + 1) + " but all buckets are empty");
            }
        }
    }

    boolean isEmpty() {
        return liveCount == 0;
    }

    long size() {
        return liveCount;
    }

    /**
     * A minimal, primitive-typed (no boxing) binary min-heap over (voxel index, distance)
     * pairs, ordered by distance. Backed by two parallel growable {@code long[]}/{@code
     * double[]} arrays. Intended to hold the modest number of entries active in a single
     * {@link BucketPriorityQueue} bucket at any time, not the whole frontier.
     */
    private static final class MinHeap {

        private long[] index = new long[8];
        private double[] dist = new double[8];
        private int size = 0;

        void push(final long voxelIndex, final double distance) {
            if (size == index.length) grow();
            index[size] = voxelIndex;
            dist[size] = distance;
            siftUp(size);
            size++;
        }

        long popMin() {
            if (size == 0) throw new java.util.NoSuchElementException("MinHeap is empty");
            final long result = index[0];
            size--;
            index[0] = index[size];
            dist[0] = dist[size];
            if (size > 0) siftDown(0);
            return result;
        }

        boolean isEmpty() {
            return size == 0;
        }

        private void grow() {
            final int newCap = index.length * 2;
            final long[] newIndex = new long[newCap];
            final double[] newDist = new double[newCap];
            System.arraycopy(index, 0, newIndex, 0, size);
            System.arraycopy(dist, 0, newDist, 0, size);
            index = newIndex;
            dist = newDist;
        }

        private void siftUp(int i) {
            while (i > 0) {
                final int parent = (i - 1) >>> 1;
                if (dist[i] >= dist[parent]) break;
                swap(i, parent);
                i = parent;
            }
        }

        private void siftDown(int i) {
            while (true) {
                final int left = 2 * i + 1;
                final int right = left + 1;
                int smallest = i;
                if (left < size && dist[left] < dist[smallest]) smallest = left;
                if (right < size && dist[right] < dist[smallest]) smallest = right;
                if (smallest == i) break;
                swap(i, smallest);
                i = smallest;
            }
        }

        private void swap(final int a, final int b) {
            final long ti = index[a];
            index[a] = index[b];
            index[b] = ti;
            final double td = dist[a];
            dist[a] = dist[b];
            dist[b] = td;
        }
    }
}
