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
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.tracing.auto.gwdt.ArrayStorageBackend;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;

/**
 * In-memory GWDT tracer using array storage.
 * <p>
 * Fast but memory-intensive. Best for images < 500MB.
 * Uses APP2-style algorithm: Gray-Weighted Distance Transform,
 * Fast Marching, and hierarchical pruning.
 * </p>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see AbstractGWDTTracer
 * @see <a href="https://pubmed.ncbi.nlm.nih.gov/23603332/">PMID: 23603332</a>
 */
public class GWDTTracer<T extends RealType<T>> extends AbstractGWDTTracer<T> {

    /**
     * Creates a new GWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public GWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        super(source, spacing);
    }

    /**
     * Creates a new GWDTTracer from an ImgPlus.
     *
     * @param source the grayscale image to trace
     */
    public GWDTTracer(final ImgPlus<T> source) {
        this(source, ImgUtils.getSpacing(source));
    }

    /**
     * Creates a new GWDTTracer with isotropic spacing (1.0 for each dimension).
     */
    public GWDTTracer(final RandomAccessibleInterval<T> source) {
        this(source, createIsotropicSpacing(source.numDimensions()));
    }

    /**
     * Creates a new GWDTTracer from an ImagePlus.
     *
     * @param source the grayscale image to trace
     */
    public GWDTTracer(final ImagePlus source) {
        this(ImgUtils.getCtSlice(source), getSpacing(source, source.getNSlices() > 1 ? 3 : 2));
    }

    @Override
    protected StorageBackend createStorageBackend() {
        return new ArrayStorageBackend(dims);
    }

    @Override
    protected double[] getSpacing() {
        return super.spacing;
    }

    @Override
    protected long[] getDimensions() {
        return super.dims;
    }


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
        // If multiple trees, merge them (should only happen with ROI_UNSET)
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

                tree.add(path);
                segmentToPath.put(segment, path);

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
        // Actually, let's trace from segmentRoot to leaf to get correct order
        final List<SWCPoint> orderedNodes = new ArrayList<>(segment.nodes);
        Collections.reverse(orderedNodes);  // Now segmentRoot first, leaf last

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
     * Computes ownership for all nodes using APP2's algorithm.
     * Each node is assigned to the leaf that has the longest intensity path through it.
     */
    private void computeNodeOwnership(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf) {

        final net.imglib2.RandomAccess<T> srcRA = source.randomAccess();

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0) {
                leaves.add(v);
            }
        }

        // Initialize all distances to 0
        for (final SWCPoint node : graph.vertexSet()) {
            nodeDistToLeaf.put(node, 0.0);
            nodeOwner.put(node, null);
        }

        // For each leaf, propagate ownership toward root
        // Nodes are assigned to the leaf with the LONGEST intensity path through them
        for (final SWCPoint leaf : leaves) {
            // Get intensity at leaf
            final long[] pos = new long[dims.length];
            nodeToVoxelPos(leaf, pos);
            double leafIntensity = 0;
            if (isInBounds(pos)) {
                srcRA.setPosition(pos);
                leafIntensity = srcRA.get().getRealDouble() / maxIntensity;
            }

            // Initialize leaf's ownership
            nodeDistToLeaf.put(leaf, leafIntensity);
            nodeOwner.put(leaf, leaf);

            // Propagate toward root
            SWCPoint current = leaf;
            double distFromLeaf = leafIntensity;

            final Set<SWCPoint> visited = new HashSet<>();
            while (!visited.contains(current)) {
                visited.add(current);

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());
                if (parent == null) break;

                // Get intensity at parent
                nodeToVoxelPos(parent, pos);
                double parentIntensity = 0;
                if (isInBounds(pos)) {
                    srcRA.setPosition(pos);
                    parentIntensity = srcRA.get().getRealDouble() / maxIntensity;
                }

                distFromLeaf += parentIntensity;

                // APP2: use >= so furthest leaf wins ties
                if (distFromLeaf >= nodeDistToLeaf.get(parent)) {
                    nodeDistToLeaf.put(parent, distFromLeaf);
                    nodeOwner.put(parent, leaf);
                } else {
                    // Another leaf already has a longer path through this node
                    // Stop propagating
                    break;
                }

                current = parent;
            }
        }
    }

    /**
     * Builds hierarchy segments by grouping consecutive nodes with the same owner.
     */
    private void buildHierarchySegments(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf,
            final Map<SWCPoint, HierarchySegment> leafToSegment) {

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0) {
                leaves.add(v);
            }
        }

        // For each leaf, trace its segment (consecutive nodes it owns)
        for (final SWCPoint leaf : leaves) {
            final List<SWCPoint> segmentNodes = new ArrayList<>();
            SWCPoint current = leaf;
            SWCPoint segmentRoot = leaf;
            double segmentLength = 0;

            final Set<SWCPoint> visited = new HashSet<>();
            while (current != null && !visited.contains(current)) {
                visited.add(current);

                // Check if this node is owned by this leaf
                if (nodeOwner.get(current) != leaf) {
                    // Ownership boundary reached - this node belongs to parent segment
                    break;
                }

                segmentNodes.add(current);
                segmentRoot = current;
                segmentLength = nodeDistToLeaf.get(current);

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }

            if (!segmentNodes.isEmpty()) {
                // Reverse so nodes go from leaf toward root
                // (we collected them leaf-to-root, but that's actually correct for path building)
                final HierarchySegment segment = new HierarchySegment(leaf, segmentRoot, segmentNodes, segmentLength);
                leafToSegment.put(leaf, segment);

                // Set parent segment if we hit an ownership boundary
                // Parent segment will be set after all segments are created
            }
        }

        // Now link parent segments
        for (final HierarchySegment segment : leafToSegment.values()) {
            final SWCPoint segRoot = segment.segmentRoot;

            // Get parent of segment root
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(segRoot);
            if (inEdges.isEmpty()) continue;
            final SWCPoint parentNode = graph.getEdgeSource(inEdges.iterator().next());
            if (parentNode == null) continue;

            // Find which segment owns the parent node
            final SWCPoint parentLeaf = nodeOwner.get(parentNode);
            if (parentLeaf != null && leafToSegment.containsKey(parentLeaf)) {
                segment.parent = leafToSegment.get(parentLeaf);
            }
        }
    }

    /**
     * Represents a hierarchical segment as defined by APP2.
     * Each segment connects a leaf to its "ownership boundary" - the point where
     * another leaf's influence takes over.
     */
    private static class HierarchySegment {
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

    private static double[] createIsotropicSpacing(final int nDims) {
        final double[] spacing = new double[nDims];
        Arrays.fill(spacing, 1.0);
        return spacing;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GWDTTracer<?> create(final ImgPlus<?> source) {
        return new GWDTTracer(source);
    }

    @SuppressWarnings({"rawtypes"})
    public static GWDTTracer<?> create(final ImagePlus source) {
        return new GWDTTracer(source);
    }

    public static void main(String[] args) {
        ImagePlus imp = new sc.fiji.snt.SNTService().demoImage("OP1");
        //imp = sc.fiji.snt.util.ImpUtils.getMIP(imp);
        final GWDTTracer<?> tracer = new GWDTTracer<>(imp);
        tracer.setSeedPhysical(new double[]{11.208050, 141.749, 0.000});
        tracer.setVerbose(true);
        final List<Tree> trees = tracer.traceTrees();
        System.out.println("Trees: " + (trees != null ? trees.size() : 0));
        if (trees != null && !trees.isEmpty()) {
            final sc.fiji.snt.viewer.Viewer3D viewer = new sc.fiji.snt.viewer.Viewer3D();
            trees.forEach(tree -> tree.setColor("red"));
            viewer.addTrees(trees, "red");
            final Tree ref = new sc.fiji.snt.SNTService().demoTree("OP1");
            ref.translate(1, 1, 0);
            ref.setColor("blue");
            viewer.add(ref);
            viewer.show();
        }
    }
}
