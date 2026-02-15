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

import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.util.Intervals;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ImgUtils;

/**
 * Factory for creating optimal GWDT tracer implementations based on image size.
 * Automatically selects the best storage backend:
 * <ul>
 *   <li><b>Array storage</b>: Images &lt; 1GB - Fast, high memory</li>
 *   <li><b>Sparse storage</b>: Images 1GB-5GB - Balanced, lower memory</li>
 *   <li><b>Disk-backed storage</b>: Images &gt; 5GB - Slow, minimal memory</li>
 * </ul>
 * <p>
 * The thresholds are based on estimated working memory (not source image size):
 * working memory ≈ source size × 10 (for GWDT + Fast Marching data structures).
 * </p>
 * Usage:
 * <pre>{@code
 * // Automatic selection
 * AbstractGWDTTracer<?> tracer = GWDTTracerFactory.createOptimal(image);
 * tracer.setSeedPhysical(somaCenter);
 * List<Tree> trees = tracer.traceTrees();
 *
 * // Manual selection
 * AbstractGWDTTracer<?> tracer = new SparseGWDTTracer<>(image);
 * }</pre>
 *
 * @author Tiago Ferreira
 */
public class GWDTTracerFactory {

    // Default fallback thresholds in MB (used if RAM detection fails)
    private static final long DEFAULT_SPARSE_THRESHOLD_MB = 1000;
    private static final long DEFAULT_DISK_THRESHOLD_MB = 4000;

    // Memory allocation strategy: percentage of available heap
    // Conservative: use only 30% of max heap for working memory
    private static final double MAX_HEAP_FRACTION = 0.30;

    // Memory estimation factor: working memory = source size × this factor.
    // Factor includes: GWDT (8 bytes) + distances (8) + parents (4) + state (1) = 21 bytes
    // Plus overhead for source image access, so we use 25 as conservative estimate
    private static final int MEMORY_FACTOR = 25;

    /**
     * Get dynamic memory thresholds based on available JVM heap.
     * <p>
     * Strategy:
     * <ul>
     *   <li>Array: Use if working memory < 20% of max heap AND sufficient free memory</li>
     *   <li>Sparse: Use if working memory < 20% of max heap OR moderate free memory</li>
     *   <li>Disk: Use if insufficient memory or very large images</li>
     * </ul>
     * </p>
     *
     * @return array of [sparseThresholdMB, diskThresholdMB]
     */
    private static long[] getDynamicThresholds() {
        final Runtime runtime = Runtime.getRuntime();

        try {
            // Get JVM memory info
            final long maxMemoryBytes = runtime.maxMemory();     // -Xmx setting
            final long totalMemoryBytes = runtime.totalMemory(); // Current heap size
            final long freeMemoryBytes = runtime.freeMemory();   // Free in current heap

            // Available memory = what we can still allocate
            final long availableBytes = maxMemoryBytes - (totalMemoryBytes - freeMemoryBytes);
            final long availableMB = availableBytes / (1024 * 1024);

            // Conservative: use only 20% of max heap for working memory
            final long maxMemoryMB = maxMemoryBytes / (1024 * 1024);
            final long maxWorkingMemoryMB = (long) (maxMemoryMB * MAX_HEAP_FRACTION);
            SNTUtils.log(String.format("JVM Memory: max=%dMB, available=%dMB, threshold=%.0f%% (~%dMB)",
                    maxMemoryBytes / (1024 * 1024),
                    availableMB,
                    MAX_HEAP_FRACTION * 100,
                    maxWorkingMemoryMB));

            // Determine thresholds based on available memory
            long sparseThreshold;
            long diskThreshold;

            if (maxWorkingMemoryMB < 100) {
                // Very limited memory (< 500MB max heap) - aggressive disk usage
                sparseThreshold = 50;
                diskThreshold = 100;
                SNTUtils.log("Limited JVM memory detected - using aggressive disk-backing");
            } else if (maxWorkingMemoryMB < 500) {
                // Limited memory (500MB-2.5GB max heap) - prefer sparse
                sparseThreshold = maxWorkingMemoryMB / 4;  // 25% of threshold
                diskThreshold = maxWorkingMemoryMB / 2;     // 50% of threshold
                SNTUtils.log("Moderate JVM memory - preferring sparse storage");
            } else {
                // Good memory (> 2.5GB max heap) - can use array more liberally
                sparseThreshold = Math.min(maxWorkingMemoryMB / 3, 1000);
                diskThreshold = Math.min(maxWorkingMemoryMB / 2, 4000);
                SNTUtils.log("Sufficient JVM memory - array storage available");
            }

            return new long[]{sparseThreshold, diskThreshold};

        } catch (Exception e) {
            SNTUtils.log("Warning: Could not detect JVM memory, using default thresholds");
            SNTUtils.log("  Error: " + e.getMessage());
            return new long[]{DEFAULT_SPARSE_THRESHOLD_MB, DEFAULT_DISK_THRESHOLD_MB};
        }
    }

    /**
     * Creates the optimal GWDT tracer for the given image.
     * <p>
     * Automatically selects between Array, Sparse, or DiskBacked storage
     * based on estimated working memory requirements.
     * </p>
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel spacing [x, y, z]
     * @return optimal tracer implementation
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static AbstractGWDTTracer<?> createOptimal(
            final RandomAccessibleInterval<?> source,
            final double[] spacing) {

        final long[] dims = Intervals.dimensionsAsLongArray(source);
        final long estimatedMB = estimateWorkingMemoryMB(dims);

        // Get dynamic thresholds based on JVM memory
        final long[] thresholds = getDynamicThresholds();
        final long sparseThreshold = thresholds[0];
        final long diskThreshold = thresholds[1];

        final AbstractGWDTTracer<?> tracer;

        if (estimatedMB > diskThreshold) {
            SNTUtils.log(String.format("Large image (%d MB working memory > %d MB threshold), using disk-backed storage",
                    estimatedMB, diskThreshold));
            tracer = new DiskBackedGWDTTracer(source, spacing);
        } else if (estimatedMB > sparseThreshold) {
            SNTUtils.log(String.format("Medium image (%d MB working memory > %d MB threshold), using sparse storage",
                    estimatedMB, sparseThreshold));
            tracer = new SparseGWDTTracer(source, spacing);
        } else {
            SNTUtils.log(String.format("Small image (%d MB working memory), using array storage", estimatedMB));
            tracer = new GWDTTracer(source, spacing);
        }

        return tracer;
    }

    /**
     * Creates the optimal GWDT tracer from an ImgPlus.
     *
     * @param source the grayscale image to trace
     * @return optimal tracer implementation
     */
    public static AbstractGWDTTracer<?> createOptimal(final ImgPlus<?> source) {
        return createOptimal(source, ImgUtils.getSpacing(source));
    }

    /**
     * Creates the optimal GWDT tracer from an ImagePlus.
     *
     * @param source the grayscale image to trace
     * @return optimal tracer implementation
     */
    public static AbstractGWDTTracer<?> createOptimal(final ImagePlus source) {
        final RandomAccessibleInterval<?> rai = ImgUtils.getCtSlice(source);
        final double[] spacing = getSpacing(source, source.getNSlices() > 1 ? 3 : 2);
        return createOptimal(rai, spacing);
    }

    /**
     * Estimate working memory requirements in MB.
     * <p>
     * Formula: (total voxels × bytes per voxel) / (1024²)
     * where bytes per voxel = GWDT (8) + distances (8) + parents (4) + state (1) = 21
     * We use 25 to include overhead.
     * </p>
     *
     * @param dims image dimensions
     * @return estimated working memory in megabytes
     */
    public static long estimateWorkingMemoryMB(final long[] dims) {
        long totalVoxels = 1;
        for (long d : dims) {
            totalVoxels *= d;
        }
        return (totalVoxels * MEMORY_FACTOR) / (1024 * 1024);
    }

    /**
     * Get recommended storage backend type for an image.
     *
     * @param dims image dimensions
     * @return "array", "sparse", or "disk"
     */
    public static String recommendBackend(final long[] dims) {
        final long estimatedMB = estimateWorkingMemoryMB(dims);
        final long[] thresholds = getDynamicThresholds();

        if (estimatedMB > thresholds[1]) return "disk";
        if (estimatedMB > thresholds[0]) return "sparse";
        return "array";
    }

    /**
     * Get current memory thresholds being used.
     * <p>
     * Useful for diagnostics and testing.
     * </p>
     *
     * @return array of [sparseThresholdMB, diskThresholdMB]
     */
    public static long[] getCurrentThresholds() {
        return getDynamicThresholds();
    }

    /**
     * Print current memory status and thresholds to console.
     * Useful for debugging memory-related issues.
     */
    public static void printMemoryStatus() {
        final Runtime runtime = Runtime.getRuntime();
        final long maxMB = runtime.maxMemory() / (1024 * 1024);
        final long totalMB = runtime.totalMemory() / (1024 * 1024);
        final long freeMB = runtime.freeMemory() / (1024 * 1024);
        final long usedMB = totalMB - freeMB;
        final long availableMB = maxMB - usedMB;

        final long[] thresholds = getDynamicThresholds();

        System.out.println("=== GWDT Factory Memory Status ===");
        System.out.println("JVM Max Memory (-Xmx):     " + maxMB + " MB");
        System.out.println("JVM Current Heap:          " + totalMB + " MB");
        System.out.println("JVM Used:                  " + usedMB + " MB");
        System.out.println("JVM Available:             " + availableMB + " MB");
        System.out.println();
        System.out.println("Dynamic Thresholds:");
        System.out.println("  Array → Sparse:          " + thresholds[0] + " MB");
        System.out.println("  Sparse → Disk:           " + thresholds[1] + " MB");
        System.out.println();
        System.out.println("Recommended backends:");
        System.out.println("  < " + thresholds[0] + " MB:         Array (fast, high memory)");
        System.out.println("  " + thresholds[0] + "-" + thresholds[1] + " MB:  Sparse (balanced)");
        System.out.println("  > " + thresholds[1] + " MB:         Disk (slow, low memory)");
    }

    /**
     * Creates optimal tracer with manual threshold override.
     * <p>
     * Allows forcing specific backends for testing or when automatic
     * selection is not appropriate for your workflow.
     * </p>
     *
     * @param source             the grayscale image to trace
     * @param spacing            voxel spacing [x, y, z]
     * @param sparseThresholdMB  use sparse above this (MB), or -1 for dynamic
     * @param diskThresholdMB    use disk above this (MB), or -1 for dynamic
     * @return optimal tracer implementation
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static AbstractGWDTTracer<?> createOptimal(
            final RandomAccessibleInterval<?> source,
            final double[] spacing,
            final long sparseThresholdMB,
            final long diskThresholdMB) {

        final long[] dims = Intervals.dimensionsAsLongArray(source);
        final long estimatedMB = estimateWorkingMemoryMB(dims);

        // Use provided thresholds or fall back to dynamic
        final long sparseThresh = (sparseThresholdMB > 0) ? sparseThresholdMB : getDynamicThresholds()[0];
        final long diskThresh = (diskThresholdMB > 0) ? diskThresholdMB : getDynamicThresholds()[1];

        SNTUtils.log(String.format("Using thresholds: sparse=%dMB, disk=%dMB (estimated=%dMB)",
                sparseThresh, diskThresh, estimatedMB));

        final AbstractGWDTTracer<?> tracer;

        if (estimatedMB > diskThresh) {
            SNTUtils.log("→ Selecting disk-backed storage");
            tracer = new DiskBackedGWDTTracer(source, spacing);
        } else if (estimatedMB > sparseThresh) {
            SNTUtils.log("→ Selecting sparse storage");
            tracer = new SparseGWDTTracer(source, spacing);
        } else {
            SNTUtils.log("→ Selecting array storage");
            tracer = new GWDTTracer(source, spacing);
        }

        return tracer;
    }

    /**
     * Force use of array storage regardless of image size.
     * <p>
     * Use with caution - may cause OutOfMemoryError for large images.
     * </p>
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel spacing
     * @return array-based tracer
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GWDTTracer<?> forceArray(
            final RandomAccessibleInterval<?> source,
            final double[] spacing) {
        SNTUtils.log("Forcing array storage (manual override)");
        return new GWDTTracer(source, spacing);
    }

    /**
     * Force use of sparse storage regardless of image size.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel spacing
     * @return sparse tracer
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SparseGWDTTracer<?> forceSparse(
            final RandomAccessibleInterval<?> source,
            final double[] spacing) {
        SNTUtils.log("Forcing sparse storage (manual override)");
        return new SparseGWDTTracer(source, spacing);
    }

    /**
     * Force use of disk-backed storage regardless of image size.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel spacing
     * @return disk-backed tracer
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DiskBackedGWDTTracer<?> forceDisk(
            final RandomAccessibleInterval<?> source,
            final double[] spacing) {
        SNTUtils.log("Forcing disk-backed storage (manual override)");
        return new DiskBackedGWDTTracer(source, spacing);
    }

    private static double[] getSpacing(final ImagePlus imp, final int nDims) {
        final double[] spacing;
        if (nDims == 2) {
            spacing = new double[]{
                    imp.getCalibration().pixelWidth,
                    imp.getCalibration().pixelHeight
            };
        } else {
            spacing = new double[]{
                    imp.getCalibration().pixelWidth,
                    imp.getCalibration().pixelHeight,
                    imp.getCalibration().pixelDepth
            };
        }
        return spacing;
    }
}
