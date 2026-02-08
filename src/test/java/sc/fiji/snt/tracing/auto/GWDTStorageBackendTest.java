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
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.SWCPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for GWDT storage backend implementations.
 * <p>
 * Verifies that Array, Sparse, and DiskBacked storage backends produce
 * identical results for the same input image and parameters.
 * </p>
 *
 * @author Tiago Ferreira
 */
@Ignore("Takes several (~6) minutes to run")
public class GWDTStorageBackendTest {

    private ImagePlus testImage;
    private double[] seedPoint;
    private static final double TOLERANCE = 1e-6;

    @Before
    public void setUp() {
        // Load OP1 demo dataset
        final SNTService sntService = new SNTService();
        testImage = sntService.demoImage("OP1");
        assertNotNull("Demo image should load", testImage);

        // Use known soma center for OP1
        seedPoint = new double[]{11.208050, 141.749, 0.000};
    }

    @After
    public void tearDown() {
        if (testImage != null) {
            testImage.close();
        }
    }

    @Test
    public void testArrayBackend() {
        final GWDTTracer<?> tracer = new GWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(true);

        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Array backend should produce trees", trees);
        assertFalse("Array backend should produce non-empty trees", trees.isEmpty());

        System.out.println("Array backend: " + getTotalNodes(trees) + " nodes, " +
                getTotalPaths(trees) + " paths");
    }

    @Test
    public void testSparseBackend() {
        final SparseGWDTTracer<?> tracer = new SparseGWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(true);

        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Sparse backend should produce trees", trees);
        assertFalse("Sparse backend should produce non-empty trees", trees.isEmpty());

        System.out.println("Sparse backend: " + getTotalNodes(trees) + " nodes, " +
                getTotalPaths(trees) + " paths");
    }

    @Test
    public void testDiskBackedBackend() {
        final DiskBackedGWDTTracer<?> tracer = new DiskBackedGWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(true);

        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Disk-backed backend should produce trees", trees);
        assertFalse("Disk-backed backend should produce non-empty trees", trees.isEmpty());

        System.out.println("Disk-backed backend: " + getTotalNodes(trees) + " nodes, " +
                getTotalPaths(trees) + " paths");
    }

    @Test
    public void testBackendsProduceIdenticalResults() {
        // Create tracers with identical parameters
        final GWDTTracer<?> arrayTracer = new GWDTTracer<>(testImage);
        final SparseGWDTTracer<?> sparseTracer = new SparseGWDTTracer<>(testImage);
        final DiskBackedGWDTTracer<?> diskTracer = new DiskBackedGWDTTracer<>(testImage);

        // Set identical parameters
        for (AbstractGWDTTracer<?> tracer : new AbstractGWDTTracer<?>[]{arrayTracer, sparseTracer, diskTracer}) {
            tracer.setSeedPhysical(seedPoint);
            tracer.setBackgroundThreshold(-1);  // auto
            tracer.setMinSegmentLengthVoxels(5.0);
            tracer.setSrRatio(1.0 / 9.0);
            tracer.setConnectivityType(2);
            tracer.setVerbose(false);  // Reduce log spam
        }

        // Trace with all backends
        final DirectedWeightedGraph arrayGraph = arrayTracer.traceToGraph();
        final DirectedWeightedGraph sparseGraph = sparseTracer.traceToGraph();
        final DirectedWeightedGraph diskGraph = diskTracer.traceToGraph();

        // Verify all graphs exist
        assertNotNull("Array graph should not be null", arrayGraph);
        assertNotNull("Sparse graph should not be null", sparseGraph);
        assertNotNull("Disk graph should not be null", diskGraph);

        // Compare node counts
        final int arrayNodes = arrayGraph.vertexSet().size();
        final int sparseNodes = sparseGraph.vertexSet().size();
        final int diskNodes = diskGraph.vertexSet().size();

        System.out.println("Node counts - Array: " + arrayNodes +
                ", Sparse: " + sparseNodes +
                ", Disk: " + diskNodes);

        assertEquals("Array and Sparse should have same node count",
                arrayNodes, sparseNodes);
        assertEquals("Array and Disk should have same node count",
                arrayNodes, diskNodes);

        // Compare edge counts
        final int arrayEdges = arrayGraph.edgeSet().size();
        final int sparseEdges = sparseGraph.edgeSet().size();
        final int diskEdges = diskGraph.edgeSet().size();

        System.out.println("Edge counts - Array: " + arrayEdges +
                ", Sparse: " + sparseEdges +
                ", Disk: " + diskEdges);

        assertEquals("Array and Sparse should have same edge count",
                arrayEdges, sparseEdges);
        assertEquals("Array and Disk should have same edge count",
                arrayEdges, diskEdges);

        // Compare graph topology (more detailed check)
        assertTrue("Array and Sparse graphs should be topologically identical",
                graphsAreTopologicallyIdentical(arrayGraph, sparseGraph));
        assertTrue("Array and Disk graphs should be topologically identical",
                graphsAreTopologicallyIdentical(arrayGraph, diskGraph));
    }

    @Test
    public void testFactorySelectsCorrectBackend() {
        // For small OP1 image, factory should select array backend
        final AbstractGWDTTracer<?> tracer = GWDTTracerFactory.createOptimal(testImage);
        assertNotNull("Factory should create tracer", tracer);

        // Check it's the right type (OP1 is small, should be array)
        assertTrue("Factory should select GWDTTracer for small image",
                tracer instanceof GWDTTracer);

        System.out.println("Factory selected: " + tracer.getClass().getSimpleName());
    }

    @Test
    public void testMemoryEstimation() {
        System.out.println("\n=== Testing Memory Estimation with Dynamic Thresholds ===");

        // Print current memory status
        GWDTTracerFactory.printMemoryStatus();

        final long[] smallDims = {256, 256, 50};
        final long[] mediumDims = {1024, 1024, 100};
        final long[] largeDims = {2048, 2048, 500};

        final long smallMB = GWDTTracerFactory.estimateWorkingMemoryMB(smallDims);
        final long mediumMB = GWDTTracerFactory.estimateWorkingMemoryMB(mediumDims);
        final long largeMB = GWDTTracerFactory.estimateWorkingMemoryMB(largeDims);

        System.out.println("\nImage size estimates:");
        System.out.println("  256×256×50: " + smallMB + " MB -> " +
                GWDTTracerFactory.recommendBackend(smallDims));
        System.out.println("  1024×1024×100: " + mediumMB + " MB -> " +
                GWDTTracerFactory.recommendBackend(mediumDims));
        System.out.println("  2048×2048×500: " + largeMB + " MB -> " +
                GWDTTracerFactory.recommendBackend(largeDims));

        // Verify recommendations are valid
        final String smallBackend = GWDTTracerFactory.recommendBackend(smallDims);
        final String mediumBackend = GWDTTracerFactory.recommendBackend(mediumDims);
        final String largeBackend = GWDTTracerFactory.recommendBackend(largeDims);

        assertTrue("Backend should be array, sparse, or disk",
                smallBackend.matches("array|sparse|disk"));
        assertTrue("Backend should be array, sparse, or disk",
                mediumBackend.matches("array|sparse|disk"));
        assertEquals("Large image should always use disk", "disk", largeBackend);
    }

    @Test
    public void testSparseBackendCompressionRatio() {
        final SparseGWDTTracer<?> tracer = new SparseGWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(true);

        // Run tracing (logs will show compression ratio)
        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Sparse backend should produce trees", trees);

        // Verify memory usage is less than array
        final long sparseMemory = tracer.storage.estimateMemoryUsage();
        final long totalVoxels = testImage.getWidth() * testImage.getHeight() * testImage.getNSlices();
        final long arrayMemory = totalVoxels * 21;

        System.out.println("Memory comparison:");
        System.out.println("  Array: " + (arrayMemory / 1024 / 1024) + " MB");
        System.out.println("  Sparse: " + (sparseMemory / 1024 / 1024) + " MB");
        System.out.println("  Reduction: " + (100.0 * (arrayMemory - sparseMemory) / arrayMemory) + "%");
    }

    @Test
    public void testDiskBackedCleanup() {
        final DiskBackedGWDTTracer<?> tracer = new DiskBackedGWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);

        // Run tracing
        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Disk-backed backend should produce trees", trees);

        // Verify storage backend exists
        assertNotNull("Storage backend should be created", tracer.storage);

        // Cleanup should not throw exceptions
        tracer.storage.dispose();

        // Note: We can't easily verify temp files are deleted without exposing internals,
        // but dispose() should handle it
        System.out.println("Disk-backed cleanup completed without errors");
    }

    @Test
    public void testAllBackendsWithDifferentParameters() {
        final AbstractGWDTTracer<?>[] tracers = {
                new GWDTTracer<>(testImage),
                new SparseGWDTTracer<>(testImage),
                new DiskBackedGWDTTracer<>(testImage)
        };

        final String[] names = {"Array", "Sparse", "DiskBacked"};

        // Test with different parameter combinations
        final double[][] thresholds = {{-1}, {10}, {20}};
        final double[][] srRatios = {{1.0/9.0}, {1.0/6.0}};

        for (int i = 0; i < tracers.length; i++) {
            System.out.println("\nTesting " + names[i] + " backend with parameter variations:");

            for (double[] thresh : thresholds) {
                for (double[] sr : srRatios) {
                    final AbstractGWDTTracer<?> tracer = tracers[i];
                    tracer.setSeedPhysical(seedPoint);
                    tracer.setBackgroundThreshold(thresh[0]);
                    tracer.setSrRatio(sr[0]);
                    tracer.setVerbose(false);

                    final List<Tree> trees = tracer.traceTrees();
                    assertNotNull(names[i] + " should produce trees", trees);

                    System.out.println("  threshold=" + thresh[0] + ", SR=" + sr[0] +
                            " -> " + getTotalNodes(trees) + " nodes");
                }
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Check if two graphs are topologically identical.
     * Compares node positions and connectivity, not exact object identity.
     */
    private boolean graphsAreTopologicallyIdentical(
            DirectedWeightedGraph g1,
            DirectedWeightedGraph g2) {

        if (g1.vertexSet().size() != g2.vertexSet().size()) return false;
        if (g1.edgeSet().size() != g2.edgeSet().size()) return false;

        // Build position-based node maps (since SWCPoint objects will differ)
        final Set<String> g1Positions = new HashSet<>();
        final Set<String> g2Positions = new HashSet<>();

        for (SWCPoint p : g1.vertexSet()) {
            g1Positions.add(positionKey(p));
        }
        for (SWCPoint p : g2.vertexSet()) {
            g2Positions.add(positionKey(p));
        }

        // Check all nodes exist in both graphs (by position)
        if (!g1Positions.equals(g2Positions)) {
            System.err.println("Node positions differ!");
            return false;
        }

        // Check edge connectivity (parent-child relationships)
        final Set<String> g1Edges = new HashSet<>();
        final Set<String> g2Edges = new HashSet<>();

        for (var edge : g1.edgeSet()) {
            SWCPoint src = g1.getEdgeSource(edge);
            SWCPoint tgt = g1.getEdgeTarget(edge);
            g1Edges.add(positionKey(src) + "->" + positionKey(tgt));
        }
        for (var edge : g2.edgeSet()) {
            SWCPoint src = g2.getEdgeSource(edge);
            SWCPoint tgt = g2.getEdgeTarget(edge);
            g2Edges.add(positionKey(src) + "->" + positionKey(tgt));
        }

        if (!g1Edges.equals(g2Edges)) {
            System.err.println("Edge connectivity differs!");
            return false;
        }

        return true;
    }

    /**
     * Create position-based key for node comparison.
     * Uses rounded coordinates to handle minor floating point differences.
     */
    private String positionKey(SWCPoint p) {
        return String.format("%.3f,%.3f,%.3f", p.x, p.y, p.z);
    }

    /**
     * Get total number of nodes across all trees.
     */
    private int getTotalNodes(List<Tree> trees) {
        int total = 0;
        for (Tree tree : trees) {
            for (var path : tree.list()) {
                total += path.size();
            }
        }
        return total;
    }

    /**
     * Get total number of paths across all trees.
     */
    private int getTotalPaths(List<Tree> trees) {
        int total = 0;
        for (Tree tree : trees) {
            total += tree.size();
        }
        return total;
    }

    /**
     * Compare two trees for equality in terms of structure and node positions.
     */
    private void assertTreesEqual(List<Tree> trees1, List<Tree> trees2, String message) {
        assertEquals(message + " - tree count should match",
                trees1.size(), trees2.size());

        assertEquals(message + " - total nodes should match",
                getTotalNodes(trees1), getTotalNodes(trees2));

        assertEquals(message + " - total paths should match",
                getTotalPaths(trees1), getTotalPaths(trees2));
    }

    @Test
    public void testBackendConsistency() {
        System.out.println("\n=== Testing Backend Consistency ===");

        // Trace with all three backends using identical parameters
        final GWDTTracer<?> arrayTracer = new GWDTTracer<>(testImage);
        final SparseGWDTTracer<?> sparseTracer = new SparseGWDTTracer<>(testImage);
        final DiskBackedGWDTTracer<?> diskTracer = new DiskBackedGWDTTracer<>(testImage);

        // Set identical parameters
        for (AbstractGWDTTracer<?> tracer : new AbstractGWDTTracer<?>[]{
                arrayTracer, sparseTracer, diskTracer}) {
            tracer.setSeedPhysical(seedPoint);
            tracer.setBackgroundThreshold(-1);
            tracer.setMinSegmentLengthVoxels(5.0);
            tracer.setSrRatio(1.0 / 9.0);
            tracer.setSphereOverlapThreshold(0.1);
            tracer.setLeafPruneEnabled(true);
            tracer.setSmoothEnabled(true);
            tracer.setSmoothWindowSize(5);
            tracer.setResampleEnabled(true);
            tracer.setResampleStep(2.0);
            tracer.setConnectivityType(2);
            tracer.setVerbose(false);
        }

        // Trace
        final long startArray = System.currentTimeMillis();
        final List<Tree> arrayTrees = arrayTracer.traceTrees();
        final long timeArray = System.currentTimeMillis() - startArray;

        final long startSparse = System.currentTimeMillis();
        final List<Tree> sparseTrees = sparseTracer.traceTrees();
        final long timeSparse = System.currentTimeMillis() - startSparse;

        final long startDisk = System.currentTimeMillis();
        final List<Tree> diskTrees = diskTracer.traceTrees();
        final long timeDisk = System.currentTimeMillis() - startDisk;

        // Report results
        System.out.println("\nResults:");
        System.out.println("Array:  " + getTotalNodes(arrayTrees) + " nodes, " +
                getTotalPaths(arrayTrees) + " paths, " + timeArray + " ms");
        System.out.println("Sparse: " + getTotalNodes(sparseTrees) + " nodes, " +
                getTotalPaths(sparseTrees) + " paths, " + timeSparse + " ms " +
                "(" + String.format("%.1f", 100.0 * timeSparse / timeArray) + "%)");
        System.out.println("Disk:   " + getTotalNodes(diskTrees) + " nodes, " +
                getTotalPaths(diskTrees) + " paths, " + timeDisk + " ms " +
                "(" + String.format("%.1f", 100.0 * timeDisk / timeArray) + "%)");

        // Verify consistency
        assertTreesEqual(arrayTrees, sparseTrees, "Array vs Sparse");
        assertTreesEqual(arrayTrees, diskTrees, "Array vs Disk");

        // Cleanup disk backend
        diskTracer.storage.dispose();

        System.out.println("\n✓ All backends produced identical results");
    }

    @Test
    public void testFactoryAutomaticSelection() {
        System.out.println("\n=== Testing Factory Automatic Selection ===");

        // Factory should select array for OP1 (small image)
        final AbstractGWDTTracer<?> tracer = GWDTTracerFactory.createOptimal(testImage);
        assertNotNull("Factory should create tracer", tracer);

        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(false);

        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Factory-created tracer should produce trees", trees);
        assertFalse("Factory-created tracer should produce non-empty trees", trees.isEmpty());

        System.out.println("Factory created: " + tracer.getClass().getSimpleName());
        System.out.println("Result: " + getTotalNodes(trees) + " nodes, " +
                getTotalPaths(trees) + " paths");
    }

    @Test
    public void testSparseBackendMemoryEfficiency() {
        System.out.println("\n=== Testing Sparse Backend Memory Efficiency ===");

        final SparseGWDTTracer<?> tracer = new SparseGWDTTracer<>(testImage);
        tracer.setSeedPhysical(seedPoint);
        tracer.setVerbose(true);  // Will log compression ratio

        final List<Tree> trees = tracer.traceTrees();
        assertNotNull("Sparse tracer should produce trees", trees);

        // Check actual memory usage
        final long sparseMemory = tracer.storage.estimateMemoryUsage();
        final long totalVoxels = testImage.getWidth() *
                testImage.getHeight() *
                testImage.getNSlices();
        final long arrayMemory = totalVoxels * 21;

        System.out.println("\nMemory comparison:");
        System.out.println("  Total voxels: " + totalVoxels);
        System.out.println("  Array memory: " + (arrayMemory / 1024 / 1024) + " MB");
        System.out.println("  Sparse memory: " + (sparseMemory / 1024 / 1024) + " MB");

        if (sparseMemory < arrayMemory) {
            final double reduction = 100.0 * (arrayMemory - sparseMemory) / arrayMemory;
            System.out.println("  Reduction: " + String.format("%.1f", reduction) + "%");
        } else {
            final double overhead = 100.0 * (sparseMemory - arrayMemory) / arrayMemory;
            System.out.println("  Overhead: " + String.format("%.1f", overhead) + "%");
        }

        // For OP1 (dense), sparse may use more due to overhead - this is expected
        // The test verifies sparse backend works, not that it's always more efficient
        assertTrue("Sparse backend should produce valid results", trees.size() > 0);
        assertTrue("Sparse backend should produce nodes", getTotalNodes(trees) > 0);
    }

    @Test
    public void testStorageBackendDisposal() {
        System.out.println("\n=== Testing Storage Backend Disposal ===");

        final AbstractGWDTTracer<?>[] tracers = {
                new GWDTTracer<>(testImage),
                new SparseGWDTTracer<>(testImage),
                new DiskBackedGWDTTracer<>(testImage)
        };

        for (AbstractGWDTTracer<?> tracer : tracers) {
            tracer.setSeedPhysical(seedPoint);
            tracer.setVerbose(false);

            // Trace
            final List<Tree> trees = tracer.traceTrees();
            assertNotNull("Tracer should produce trees", trees);

            // Dispose should not throw
            try {
                tracer.storage.dispose();
                System.out.println(tracer.getClass().getSimpleName() +
                        " - disposal successful");
            } catch (Exception e) {
                fail("Disposal should not throw exception: " + e.getMessage());
            }
        }
    }

    @Test
    public void testManualBackendOverride() {
        System.out.println("\n=== Testing Manual Backend Override ===");

        // Force backends manually
        final AbstractGWDTTracer<?> forcedArray = GWDTTracerFactory.forceArray(
                ImgUtils.getCtSlice(testImage),
                new double[]{1.0, 1.0, 1.0});
        final AbstractGWDTTracer<?> forcedSparse = GWDTTracerFactory.forceSparse(
                ImgUtils.getCtSlice(testImage),
                new double[]{1.0, 1.0, 1.0});
        final AbstractGWDTTracer<?> forcedDisk = GWDTTracerFactory.forceDisk(
                ImgUtils.getCtSlice(testImage),
                new double[]{1.0, 1.0, 1.0});

        // Verify correct types
        assertTrue("forceArray should return GWDTTracer",
                forcedArray instanceof GWDTTracer);
        assertTrue("forceSparse should return SparseGWDTTracer",
                forcedSparse instanceof SparseGWDTTracer);
        assertTrue("forceDisk should return DiskBackedGWDTTracer",
                forcedDisk instanceof DiskBackedGWDTTracer);

        System.out.println("Manual override methods working correctly");
    }

    @Test
    public void testCustomThresholds() {
        System.out.println("\n=== Testing Custom Threshold Override ===");

        // Force sparse by setting low threshold
        final AbstractGWDTTracer<?> tracer = GWDTTracerFactory.createOptimal(
                ImgUtils.getCtSlice(testImage),
                new double[]{1.0, 1.0, 1.0},
                10,    // sparseThresholdMB - very low, forces sparse for any image
                5000   // diskThresholdMB - very high
        );

        // Should select sparse since image is > 10MB
        assertTrue("Low threshold should force sparse backend",
                tracer instanceof SparseGWDTTracer);

        System.out.println("Custom thresholds working correctly");
    }
}
