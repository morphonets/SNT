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

import ij.measure.Calibration;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.tracing.BiSearch;
import sc.fiji.snt.tracing.cost.Cost;
import sc.fiji.snt.tracing.cost.Reciprocal;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;
import sc.fiji.snt.util.SWCPoint;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import java.util.*;
import java.util.concurrent.*;

/**
 * Bridges gaps between disconnected components of an auto-traced neuron tree.
 * <p>
 * After auto-tracing (e.g., via {@link GWDTTracer}), neurites with dim or
 * fragmented signal may produce disconnected components. Rather than discarding
 * them, this class attempts to reconnect each orphan component to the main tree
 * by running A* searches between nearby endpoints and validating the resulting
 * paths.
 * </p>
 * <p>
 * Validation criteria (all configurable):
 * <ul>
 *   <li><b>Tortuosity:</b> bridge contraction must exceed a minimum (rejects
 *       meandering paths through noise)</li>
 *   <li><b>Direction compatibility:</b> the bridge must approach existing
 *       branches at a compatible angle</li>
 *   <li><b>Mean intensity:</b> the bridge must pass through sufficiently bright
 *       signal</li>
 *   <li><b>Length:</b> the bridge must not exceed the maximum gap distance</li>
 * </ul>
 * </p>
 * <p>
 * Adapted from NeuTube's chain connection logic ({@code Locseg_Chain_Connection_Test}).
 * </p>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 */
public class ComponentReconnector<T extends RealType<T>> {

    private final RandomAccessibleInterval<T> source;
    private final double[] spacing;
    private final long[] dims;

    // Validation thresholds
    private double maxBridgeDistVoxels = 20.0;
    private double minContraction = 0.6;
    private double maxAngleDeg = 60.0;
    private double minMeanIntensity = Double.NaN; // NaN = auto from image threshold
    private int timeoutPerSearch = 5; // seconds

    // Cost function (defaults to Reciprocal)
    private Cost costFunction;
    private Heuristic heuristic;

    // Logging
    private boolean verbose = false;

    /**
     * Creates a new ComponentReconnector.
     *
     * @param source  the grayscale image used for tracing
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public ComponentReconnector(final RandomAccessibleInterval<T> source, final double[] spacing) {
        this.source = source;
        this.spacing = spacing;
        this.dims = new long[source.numDimensions()];
        source.dimensions(dims);
    }

    /**
     * Sets the maximum Euclidean distance (in voxels) between candidate
     * endpoints. Pairs farther apart are not considered. Default: 20.
     */
    public void setMaxBridgeDistVoxels(final double dist) {
        this.maxBridgeDistVoxels = dist;
    }

    /**
     * Sets the minimum contraction (Euclidean distance / path length) for
     * accepting a bridge. Values close to 1.0 require near-straight bridges;
     * lower values tolerate more tortuous paths. Default: 0.6.
     */
    public void setMinContraction(final double contraction) {
        this.minContraction = Math.clamp(contraction, 0, 1);
    }

    /**
     * Sets the maximum angle (in degrees) between the bridge tangent and the
     * existing branch direction at connection points. Default: 60.
     */
    public void setMaxAngleDeg(final double degrees) {
        this.maxAngleDeg = degrees;
    }

    /**
     * Sets the minimum mean intensity along the bridge path. Set to
     * {@link Double#NaN} to use the auto-traced threshold. Default: NaN.
     */
    public void setMinMeanIntensity(final double intensity) {
        this.minMeanIntensity = intensity;
    }

    /**
     * Sets the A* search timeout per candidate pair, in seconds. Default: 5.
     */
    public void setTimeoutPerSearch(final int seconds) {
        this.timeoutPerSearch = Math.max(1, seconds);
    }

    /**
     * Sets the cost function for A* searches. If not set, defaults to
     * {@link Reciprocal} using the source image intensity range.
     */
    public void setCostFunction(final Cost cost) {
        this.costFunction = cost;
    }

    /**
     * Sets the heuristic for A* searches. If not set, defaults to
     * {@link Euclidean}.
     */
    public void setHeuristic(final Heuristic h) {
        this.heuristic = h;
    }

    /**
     * Enables or disables verbose logging.
     */
    public void setVerbose(final boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Attempts to reconnect disconnected components in the graph back to the
     * main tree (the component containing the root).
     *
     * @param graph               the directed weighted graph to modify in place
     * @param root                the root node of the main tree
     * @param backgroundThreshold intensity threshold used during tracing (used
     *                            as fallback for minMeanIntensity if not set)
     * @return the number of components successfully reconnected
     */
    public int reconnect(final DirectedWeightedGraph graph, final SWCPoint root,
                         final double backgroundThreshold) {
        // 1. Find connected components
        final Set<SWCPoint> mainTree = bfs(graph, root);
        final List<Set<SWCPoint>> orphanComponents = findOrphanComponents(graph, mainTree);

        if (orphanComponents.isEmpty()) {
            log("No disconnected components to reconnect");
            return 0;
        }
        log("Found " + orphanComponents.size() + " disconnected component(s)");

        // 2. Build KDTree over main tree vertices for nearest-neighbor queries
        final SWCPoint[] mainVertices = mainTree.toArray(new SWCPoint[0]);
        final double[][] mainCoords = new double[mainVertices.length][3];
        for (int i = 0; i < mainVertices.length; i++) {
            mainCoords[i] = new double[]{mainVertices[i].x, mainVertices[i].y, mainVertices[i].z};
        }
        final KDTree<SWCPoint> kdTree = new KDTree<>(mainCoords, mainVertices);

        // Resolve cost/heuristic defaults
        final Cost cost = (costFunction != null) ? costFunction : createDefaultCost();
        final Heuristic heur = (heuristic != null) ? heuristic : createDefaultHeuristic();
        final double effectiveMinIntensity = Double.isNaN(minMeanIntensity)
                ? backgroundThreshold : minMeanIntensity;
        final double maxPhysicalDist = maxBridgeDistVoxels * spacing[0]; // approximate
        final double cosThreshold = Math.cos(Math.toRadians(maxAngleDeg));

        // 3. For each orphan component, find best bridge
        int reconnected = 0;
        for (final Set<SWCPoint> component : orphanComponents) {
            // Collect candidate endpoints from orphan: leaves and roots (no parent)
            final List<SWCPoint> orphanEndpoints = new ArrayList<>();
            for (final SWCPoint v : component) {
                if (graph.outDegreeOf(v) == 0 || graph.inDegreeOf(v) == 0) {
                    orphanEndpoints.add(v);
                }
            }
            if (orphanEndpoints.isEmpty()) {
                // Fallback: use all vertices
                orphanEndpoints.addAll(component);
            }

            // Find candidate pairs: each orphan endpoint → nearest main tree node
            final List<CandidatePair> candidates = new ArrayList<>();
            for (final SWCPoint orphanNode : orphanEndpoints) {
                final double[] query = {orphanNode.x, orphanNode.y, orphanNode.z};
                final Neighbor<double[], SWCPoint> nearest = kdTree.nearest(query);
                if (nearest == null) continue;
                final double dist = orphanNode.distanceTo(nearest.value());
                if (dist <= maxPhysicalDist) {
                    candidates.add(new CandidatePair(orphanNode, nearest.value(), dist));
                }
            }

            if (candidates.isEmpty()) continue;

            // Sort by distance, try closest first
            candidates.sort(Comparator.comparingDouble(c -> c.distance));

            // Try each candidate until one succeeds
            for (final CandidatePair pair : candidates) {
                final Path bridge = traceBridge(pair.orphanNode, pair.mainNode, cost, heur);
                if (bridge == null) continue;
                if (!validateBridge(bridge, pair, graph, effectiveMinIntensity, cosThreshold)) continue;

                // Accept: merge bridge into graph
                mergeBridge(graph, bridge, pair);
                reconnected++;
                log("  Reconnected component (" + component.size() + " nodes) via "
                        + bridge.size() + "-node bridge (dist=" +
                        String.format("%.1f", pair.distance) + ")");

                // Update KDTree with new nodes from the reconnected component
                // (For simplicity, we don't rebuild the KDTree, subsequent components
                // can still connect to the original main tree. A full rebuild could
                // be done here for maximum accuracy.)
                break; // one bridge per component is enough
            }
        }

        // 4. Remove any still-disconnected components
        final Set<SWCPoint> finalTree = bfs(graph, root);
        final Set<SWCPoint> stillOrphaned = new HashSet<>(graph.vertexSet());
        stillOrphaned.removeAll(finalTree);
        if (!stillOrphaned.isEmpty()) {
            graph.removeAllVertices(stillOrphaned);
            log("Removed " + stillOrphaned.size() + " still-disconnected nodes");
        }

        log("Reconnected " + reconnected + " of " + orphanComponents.size() + " component(s)");
        return reconnected;
    }

    /**
     * Runs a BiSearch (bidirectional A*) between two points.
     *
     * @return the traced Path, or null if the search failed/timed out
     */
    private Path traceBridge(final SWCPoint from, final SWCPoint to,
                             final Cost cost, final Heuristic heur) {
        // Convert physical coordinates to voxel coordinates
        final int x0 = (int) Math.round(from.x / spacing[0]);
        final int y0 = (int) Math.round(from.y / spacing[1]);
        final int z0 = dims.length > 2 ? (int) Math.round(from.z / spacing[2]) : 0;
        final int x1 = (int) Math.round(to.x / spacing[0]);
        final int y1 = (int) Math.round(to.y / spacing[1]);
        final int z1 = dims.length > 2 ? (int) Math.round(to.z / spacing[2]) : 0;

        final Calibration cal = new Calibration();
        cal.pixelWidth = spacing[0];
        cal.pixelHeight = spacing[1];
        if (dims.length > 2) cal.pixelDepth = spacing[2];

        final BiSearch search = new BiSearch(source, cal,
                x0, y0, z0, x1, y1, z1,
                timeoutPerSearch, 0L,
                SNT.SearchImageType.ARRAY,
                cost, heur);

        // Run with timeout
        try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                final Future<?> future = executor.submit(search);
                future.get(timeoutPerSearch + 1, TimeUnit.SECONDS);
                return search.getResult();
            } catch (TimeoutException e) {
                log("  A* search timed out for bridge attempt");
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Validates a traced bridge path against all configured criteria.
     */
    private boolean validateBridge(final Path bridge, final CandidatePair pair,
                                   final DirectedWeightedGraph graph,
                                   final double minIntensity, final double cosThreshold) {
        if (bridge.size() < 2) return false;

        // 1. Contraction (tortuosity)
        final double contraction = bridge.getContraction();
        if (Double.isNaN(contraction) || contraction < minContraction) {
            log("  Bridge rejected: contraction=" + String.format("%.2f", contraction));
            return false;
        }

        // 2. Mean intensity along bridge
        final double meanIntensity = computeMeanIntensity(bridge);
        if (meanIntensity <= minIntensity) {
            log("  Bridge rejected: meanIntensity=" + String.format("%.1f", meanIntensity));
            return false;
        }

        // 3. Direction compatibility at both endpoints
        if (!checkDirectionCompatibility(bridge, pair, graph, cosThreshold)) {
            log("  Bridge rejected: direction incompatible");
            return false;
        }

        return true;
    }

    /**
     * Computes the mean image intensity sampled at each node of the bridge path.
     */
    private double computeMeanIntensity(final Path bridge) {
        final RandomAccess<T> ra = source.randomAccess();
        double sum = 0;
        int count = 0;
        for (int i = 0; i < bridge.size(); i++) {
            final long x = Math.round(bridge.getNode(i).x / spacing[0]);
            final long y = Math.round(bridge.getNode(i).y / spacing[1]);
            final long z = dims.length > 2 ? Math.round(bridge.getNode(i).z / spacing[2]) : 0;
            // Bounds check
            if (x < 0 || x >= dims[0] || y < 0 || y >= dims[1]) continue;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            ra.setPosition(new long[]{x, y, z});
            sum += ra.get().getRealDouble();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * Checks that the bridge approaches existing branches at compatible angles.
     * The tangent at each bridge endpoint is compared to the local direction of
     * the existing tree at the connection point.
     */
    private boolean checkDirectionCompatibility(final Path bridge, final CandidatePair pair,
                                                final DirectedWeightedGraph graph,
                                                final double cosThreshold) {
        // Get bridge direction at the main-tree end
        final double[] bridgeTangent = getBridgeEndTangent(bridge, true);
        if (bridgeTangent == null) return true; // can't compute, be lenient

        // Get existing tree direction at the main-tree connection point
        final double[] treeTangent = getTreeTangent(graph, pair.mainNode);
        if (treeTangent == null) return true; // isolated node, be lenient

        // Dot product of unit tangents
        final double dot = dotNormalized(bridgeTangent, treeTangent);
        // We use abs(dot) because the bridge could approach from either direction long the existing branch: both are valid
        return Math.abs(dot) >= cosThreshold;
    }

    /**
     * Gets the tangent direction at one end of a bridge path, using the first
     * (or last) few nodes.
     *
     * @param atStart true for the start of the bridge, false for the end
     */
    private double[] getBridgeEndTangent(final Path bridge, final boolean atStart) {
        if (bridge.size() < 2) return null;
        final int nSample = Math.min(5, bridge.size());
        final int i0 = atStart ? 0 : bridge.size() - 1;
        final int i1 = atStart ? nSample - 1 : bridge.size() - nSample;
        return new double[]{
                bridge.getNode(i1).x - bridge.getNode(i0).x,
                bridge.getNode(i1).y - bridge.getNode(i0).y,
                bridge.getNode(i1).z - bridge.getNode(i0).z
        };
    }

    /**
     * Gets the local tangent of the tree at a node, by averaging direction
     * vectors to its graph neighbors.
     */
    private double[] getTreeTangent(final DirectedWeightedGraph graph, final SWCPoint node) {
        double tx = 0, ty = 0, tz = 0;
        int count = 0;
        for (final SWCWeightedEdge e : graph.edgesOf(node)) {
            final SWCPoint neighbor = graph.getEdgeSource(e).equals(node)
                    ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
            tx += neighbor.x - node.x;
            ty += neighbor.y - node.y;
            tz += neighbor.z - node.z;
            count++;
        }
        if (count == 0) return null;
        return new double[]{tx / count, ty / count, tz / count};
    }

    /**
     * Computes the dot product of two vectors after normalizing them.
     */
    private static double dotNormalized(final double[] a, final double[] b) {
        final double magA = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        final double magB = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (magA == 0 || magB == 0) return 0;
        return (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (magA * magB);
    }

    /**
     * Merges a validated bridge path into the graph, connecting the orphan
     * component to the main tree.
     */
    private void mergeBridge(final DirectedWeightedGraph graph, final Path bridge,
                             final CandidatePair pair) {
        // Create SWCPoints for bridge nodes (excluding first and last, which are
        // the existing graph nodes)
        SWCPoint prev = pair.mainNode;
        final int startIdx = 1; // skip first node (it's pair.mainNode or very close)
        final int endIdx = bridge.size() - 1; // skip last node (it's pair.orphanNode or very close)

        for (int i = startIdx; i < endIdx; i++) {
            final SWCPoint bridgeNode = new SWCPoint(
                    -1, // id will be reassigned
                    Path.SWC_UNDEFINED, // type
                    bridge.getNode(i).x,
                    bridge.getNode(i).y,
                    bridge.getNode(i).z,
                    1.0, // radius placeholder
                    -1   // parent
            );
            graph.addVertex(bridgeNode);
            final SWCWeightedEdge edge = graph.addEdge(prev, bridgeNode);
            if (edge != null) {
                graph.setEdgeWeight(edge, prev.distanceTo(bridgeNode));
            }
            prev = bridgeNode;
        }

        // Connect last bridge node to the orphan endpoint
        if (!prev.equals(pair.orphanNode) && graph.containsVertex(pair.orphanNode)) {
            final SWCWeightedEdge edge = graph.addEdge(prev, pair.orphanNode);
            if (edge != null) {
                graph.setEdgeWeight(edge, prev.distanceTo(pair.orphanNode));
            }
        }
    }

    /**
     * BFS from a starting node, returning all reachable vertices (following
     * edges in both directions).
     */
    private Set<SWCPoint> bfs(final DirectedWeightedGraph graph, final SWCPoint start) {
        final Set<SWCPoint> visited = new HashSet<>();
        final Queue<SWCPoint> queue = new LinkedList<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            final SWCPoint current = queue.poll();
            if (!visited.add(current)) continue;
            for (final SWCWeightedEdge e : graph.edgesOf(current)) {
                final SWCPoint neighbor = graph.getEdgeSource(e).equals(current)
                        ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    /**
     * Finds all connected components not reachable from the main tree.
     */
    private List<Set<SWCPoint>> findOrphanComponents(final DirectedWeightedGraph graph,
                                                     final Set<SWCPoint> mainTree) {
        final Set<SWCPoint> orphans = new HashSet<>(graph.vertexSet());
        orphans.removeAll(mainTree);

        final List<Set<SWCPoint>> components = new ArrayList<>();
        final Set<SWCPoint> assigned = new HashSet<>();
        for (final SWCPoint orphan : orphans) {
            if (assigned.contains(orphan)) continue;
            final Set<SWCPoint> component = bfs(graph, orphan);
            component.retainAll(orphans); // ensure we don't leak into mainTree
            components.add(component);
            assigned.addAll(component);
        }
        return components;
    }

    private Cost createDefaultCost() {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        final RandomAccess<T> ra = source.randomAccess();
        // Sample a subset of voxels for min/max estimation
        final long step = Math.max(1, dims[0] / 100);
        for (long x = 0; x < dims[0]; x += step) {
            for (long y = 0; y < dims[1]; y += step) {
                if (dims.length > 2) {
                    final long zStep = Math.max(1, dims[2] / 20);
                    for (long z = 0; z < dims[2]; z += zStep) {
                        ra.setPosition(new long[]{x, y, z});
                        final double v = ra.get().getRealDouble();
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                } else {
                    ra.setPosition(new long[]{x, y});
                    final double v = ra.get().getRealDouble();
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
        }
        return new Reciprocal(min, max);
    }

    private Heuristic createDefaultHeuristic() {
        final Calibration cal = new Calibration();
        cal.pixelWidth = spacing[0];
        cal.pixelHeight = spacing[1];
        if (spacing.length > 2) cal.pixelDepth = spacing[2];
        return new Euclidean(cal);
    }

    // -------------------------------------------------------------------------
    //  Tip Extension: bridge from leaf tips across multi-voxel gaps
    // -------------------------------------------------------------------------

    /**
     * Extends leaf tips of the tree across multi-voxel gaps by scanning for
     * bright signal in each leaf's tangent direction and A*-bridging to it.
     * <p>
     * Unlike {@link #reconnect}, which bridges disconnected components already
     * present in the graph, this method discovers unreached foreground signal
     * beyond the Fast Marching frontier (which stops at multi-voxel gaps) and
     * traces through the gap to reach it.
     * </p>
     *
     * @param graph               the directed weighted graph to modify in place
     * @param root                the root node of the main tree
     * @param backgroundThreshold intensity threshold used during tracing
     * @return the number of tips successfully extended
     */
    public int extendTips(final DirectedWeightedGraph graph, final SWCPoint root,
                          final double backgroundThreshold) {
        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }
        if (leaves.isEmpty()) {
            SNTUtils.log("[TipExtension] No leaves to extend");
            return 0;
        }
        SNTUtils.log("[TipExtension] Scanning " + leaves.size() + " leaves"
                + " (maxDist=" + String.format("%.0f", maxBridgeDistVoxels) + " voxels)");

        // Build set of voxel indices already in graph to avoid bridging to self
        final Set<Long> graphVoxels = new HashSet<>();
        for (final SWCPoint v : graph.vertexSet()) {
            final long vx = Math.round(v.x / spacing[0]);
            final long vy = Math.round(v.y / spacing[1]);
            final long vz = dims.length > 2 ? Math.round(v.z / spacing[2]) : 0;
            if (vx >= 0 && vx < dims[0] && vy >= 0 && vy < dims[1]) {
                graphVoxels.add(flatIndex(vx, vy, vz));
            }
        }

        final Cost cost = (costFunction != null) ? costFunction : createDefaultCost();
        final Heuristic heur = (heuristic != null) ? heuristic : createDefaultHeuristic();
        final RandomAccess<T> ra = source.randomAccess();

        // Counters for diagnostics
        int noTangent = 0;
        int noTarget = 0;
        int aStarFailed = 0;
        int rejectedContraction = 0;
        int rejectedIntensity = 0;
        int extended = 0;

        for (final SWCPoint leaf : leaves) {
            // Compute outward tangent at leaf (direction the neurite was heading)
            final double[] tangent = computeLeafTangent(graph, leaf);
            if (tangent == null) {
                noTangent++;
                continue;
            }

            // Only attempt extension if the leaf ended at a gap:
            // check if the voxel just beyond the tip (along tangent) is dark
            if (!isAtGapBoundary(leaf, tangent, ra, backgroundThreshold)) {
                continue; // natural endpoint, no gap to bridge
            }

            // Scan for the brightest target across the gap (not just closest)
            final long[] target = scanForBrightTarget(leaf, tangent, ra,
                    backgroundThreshold, graphVoxels);
            if (target == null) {
                noTarget++;
                continue;
            }

            // Create target point in physical coords
            final SWCPoint targetPt = new SWCPoint(-1, Path.SWC_UNDEFINED,
                    target[0] * spacing[0], target[1] * spacing[1],
                    dims.length > 2 ? target[2] * spacing[2] : 0,
                    1.0, -1);

            final double gapVoxels = leaf.distanceTo(targetPt) / avgSpacing();
            SNTUtils.log("[TipExtension]   Leaf (" + fmtCoord(leaf)
                    + "): gap target at " + String.format("%.0f", gapVoxels) + " voxels");

            // A* bridge from leaf to target
            final Path bridge = traceBridge(leaf, targetPt, cost, heur);
            if (bridge == null || bridge.size() < 2) {
                SNTUtils.log("[TipExtension]     A* failed");
                aStarFailed++;
                continue;
            }

            // Validate: relaxed contraction (gap may cause slight meandering)
            final double contraction = bridge.getContraction();
            if (!Double.isNaN(contraction) && contraction < minContraction * 0.5) {
                SNTUtils.log("[TipExtension]     Rejected: contraction="
                        + String.format("%.2f", contraction));
                rejectedContraction++;
                continue;
            }
            // Skip mean-intensity check: bridge is expected to cross dark gap
            // The A* cost function already penalizes dark regions

            // Add bridge nodes to graph as an extension of the leaf
            SWCPoint prev = leaf;
            for (int i = 1; i < bridge.size(); i++) {
                final SWCPoint node = new SWCPoint(-1, Path.SWC_UNDEFINED,
                        bridge.getNode(i).x, bridge.getNode(i).y, bridge.getNode(i).z,
                        1.0, -1);
                graph.addVertex(node);
                final SWCWeightedEdge edge = graph.addEdge(prev, node);
                if (edge != null) graph.setEdgeWeight(edge, prev.distanceTo(node));
                final long nx = Math.round(node.x / spacing[0]);
                final long ny = Math.round(node.y / spacing[1]);
                final long nz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
                graphVoxels.add(flatIndex(nx, ny, nz));
                prev = node;
            }

            // Continue tracing along the far-side structure beyond the gap
            final int walked = traceAlongStructure(prev, graph, graphVoxels, ra, backgroundThreshold);
            extended++;
            SNTUtils.log("[TipExtension]     Extended: " + bridge.size()
                    + " bridge + " + walked + " walk nodes");
        }

        SNTUtils.log("[TipExtension] Result: " + extended + " extended, "
                + noTangent + " noTangent, " + noTarget + " noTarget, "
                + aStarFailed + " aStarFailed, " + rejectedContraction + " rejContraction, "
                + rejectedIntensity + " rejIntensity");
        return extended;
    }

    /**
     * Computes the outward tangent direction at a leaf node by walking back
     * several nodes toward the tree interior.
     *
     * @return normalized tangent in physical coordinates, or null if undetermined
     */
    private double[] computeLeafTangent(final DirectedWeightedGraph graph, final SWCPoint leaf) {
        SWCPoint ancestor = leaf;
        for (int i = 0; i < 5; i++) {
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(ancestor);
            if (inEdges.isEmpty()) break;
            ancestor = graph.getEdgeSource(inEdges.iterator().next());
        }
        if (ancestor.equals(leaf)) return null;

        final double dx = leaf.x - ancestor.x;
        final double dy = leaf.y - ancestor.y;
        final double dz = leaf.z - ancestor.z;
        final double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (mag < 1e-9) return null;
        return new double[]{dx / mag, dy / mag, dz / mag};
    }

    /**
     * Checks whether a leaf tip is at a gap boundary: the tip's immediate
     * neighborhood (a few voxels along the tangent) contains dark signal,
     * indicating the neurite stopped because of a gap rather than ending
     * naturally.
     */
    private boolean isAtGapBoundary(final SWCPoint leaf, final double[] tangentPhys,
                                    final RandomAccess<T> ra, final double threshold) {
        final int nDims = dims.length;
        final long lx = Math.round(leaf.x / spacing[0]);
        final long ly = Math.round(leaf.y / spacing[1]);
        final long lz = nDims > 2 ? Math.round(leaf.z / spacing[2]) : 0;

        // Convert tangent to voxel space
        double tx = tangentPhys[0] / spacing[0];
        double ty = tangentPhys[1] / spacing[1];
        double tz = nDims > 2 ? tangentPhys[2] / spacing[2] : 0;
        final double tmag = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tmag < 1e-9) return false;
        tx /= tmag; ty /= tmag; tz /= tmag;

        // Check voxels 2-4 steps ahead along the tangent
        int darkCount = 0;
        for (int step = 2; step <= 4; step++) {
            final long px = lx + Math.round(tx * step);
            final long py = ly + Math.round(ty * step);
            final long pz = lz + Math.round(tz * step);
            if (px < 0 || px >= dims[0] || py < 0 || py >= dims[1]) continue;
            if (nDims > 2 && (pz < 0 || pz >= dims[2])) continue;
            if (nDims > 2) ra.setPosition(new long[]{px, py, pz});
            else ra.setPosition(new long[]{px, py});
            if (ra.get().getRealDouble() <= threshold) darkCount++;
        }
        return darkCount >= 2; // at least 2 of 3 samples are dark → gap
    }

    /**
     * Scans the source image in a forward cone from the leaf tip, looking for
     * the brightest voxel not already in the graph. Scans the full range
     * (not just the closest shell) to avoid being distracted by isolated dim
     * pixels near the leaf. Returns the best target voxel, or null.
     */
    private long[] scanForBrightTarget(
            final SWCPoint leaf, final double[] tangentPhys,
            final RandomAccess<T> ra, final double threshold,
            final Set<Long> graphVoxels) {

        final int nDims = dims.length;

        // Leaf position in voxels
        final long lx = Math.round(leaf.x / spacing[0]);
        final long ly = Math.round(leaf.y / spacing[1]);
        final long lz = nDims > 2 ? Math.round(leaf.z / spacing[2]) : 0;

        // Tangent in voxel space (re-normalized)
        double tx = tangentPhys[0] / spacing[0];
        double ty = tangentPhys[1] / spacing[1];
        double tz = nDims > 2 ? tangentPhys[2] / spacing[2] : 0;
        final double tmag = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tmag < 1e-9) return null;
        tx /= tmag;
        ty /= tmag;
        tz /= tmag;

        final int maxR = (int) Math.ceil(maxBridgeDistVoxels);
        final int minR = 5; // minimum gap size worth bridging
        final double cosCone = 0.26; // cos(75°): wide 75° half-angle cone

        // Scan the FULL cone volume and find the brightest target.
        // Score = intensity * alignment, so a bright well-aligned target wins
        // over a dim nearby one.
        double bestScore = 0;
        long[] bestTarget = null;
        final double maxRSq = (double) maxR * maxR;
        final double minRSq = (double) minR * minR;

        for (int dx = -maxR; dx <= maxR; dx++) {
            for (int dy = -maxR; dy <= maxR; dy++) {
                final int dzMax = nDims > 2 ? maxR : 0;
                final int dzMin = nDims > 2 ? -maxR : 0;

                for (int dz = dzMin; dz <= dzMax; dz++) {
                    final double dSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dSq > maxRSq || dSq < minRSq) continue;

                    final double dist = Math.sqrt(dSq);
                    final double dot = dx * tx + dy * ty + dz * tz;
                    final double cosAngle = dot / dist;
                    if (cosAngle < cosCone) continue; // outside cone

                    final long px = lx + dx;
                    final long py = ly + dy;
                    final long pz = lz + dz;

                    if (px < 0 || px >= dims[0] || py < 0 || py >= dims[1]) continue;
                    if (nDims > 2 && (pz < 0 || pz >= dims[2])) continue;

                    if (graphVoxels.contains(flatIndex(px, py, pz))) continue;

                    // Check brightness
                    if (nDims > 2)
                        ra.setPosition(new long[]{px, py, pz});
                    else
                        ra.setPosition(new long[]{px, py});

                    final double intensity = ra.get().getRealDouble();
                    if (intensity > threshold) {
                        // Score: intensity × alignment / distance
                        // Bright, well-aligned, reasonably close targets score highest
                        final double score = intensity * cosAngle / dist;
                        if (score > bestScore) {
                            bestScore = score;
                            bestTarget = new long[]{px, py, pz};
                        }
                    }
                }
            }
        }

        return bestTarget;
    }

    /**
     * After bridging a gap, greedily follows bright signal from the landing
     * point along the far-side structure.  At each step, picks the brightest
     * 26-connected neighbour (8-connected in 2D) that is above threshold and
     * not already in the graph.  Adds each new voxel as a graph node connected
     * to the previous one.  Stops when no bright unvisited neighbour exists or
     * the maximum walk length is reached.
     *
     * @return the number of nodes added
     */
    private int traceAlongStructure(final SWCPoint startNode,
                                    final DirectedWeightedGraph graph,
                                    final Set<Long> graphVoxels,
                                    final RandomAccess<T> ra,
                                    final double threshold) {

        final int nDims = dims.length;
        final int maxWalkNodes = 500; // safety cap
        int added = 0;
        SWCPoint prev = startNode;

        for (int step = 0; step < maxWalkNodes; step++) {
            // Current position in voxel space
            final long cx = Math.round(prev.x / spacing[0]);
            final long cy = Math.round(prev.y / spacing[1]);
            final long cz = nDims > 2 ? Math.round(prev.z / spacing[2]) : 0;

            // Find brightest unvisited neighbor
            double bestIntensity = threshold; // must exceed threshold
            long bestX = -1, bestY = -1, bestZ = -1;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    final int dzMin = nDims > 2 ? -1 : 0;
                    final int dzMax = nDims > 2 ? 1 : 0;
                    for (int dz = dzMin; dz <= dzMax; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        final long nx = cx + dx;
                        final long ny = cy + dy;
                        final long nz = cz + dz;
                        if (nx < 0 || nx >= dims[0] || ny < 0 || ny >= dims[1]) continue;
                        if (nDims > 2 && (nz < 0 || nz >= dims[2])) continue;
                        if (graphVoxels.contains(flatIndex(nx, ny, nz))) continue;

                        if (nDims > 2)
                            ra.setPosition(new long[]{nx, ny, nz});
                        else
                            ra.setPosition(new long[]{nx, ny});

                        final double intensity = ra.get().getRealDouble();
                        if (intensity > bestIntensity) {
                            bestIntensity = intensity;
                            bestX = nx;
                            bestY = ny;
                            bestZ = nz;
                        }
                    }
                }
            }

            if (bestX < 0) break; // done: no bright unvisited neighbor

            // Add new node
            final SWCPoint node = new SWCPoint(-1, Path.SWC_UNDEFINED,
                    bestX * spacing[0], bestY * spacing[1],
                    nDims > 2 ? bestZ * spacing[2] : 0,
                    1.0, -1);
            graph.addVertex(node);
            final SWCWeightedEdge edge = graph.addEdge(prev, node);
            if (edge != null) graph.setEdgeWeight(edge, prev.distanceTo(node));
            graphVoxels.add(flatIndex(bestX, bestY, bestZ));
            prev = node;
            added++;
        }
        return added;
    }

    private long flatIndex(final long x, final long y, final long z) {
        if (dims.length > 2) return x + y * dims[0] + z * dims[0] * dims[1];
        return x + y * dims[0];
    }

    private double avgSpacing() {
        double sum = 0;
        for (final double s : spacing) sum += s;
        return sum / spacing.length;
    }

    private String fmtCoord(final SWCPoint p) {
        return String.format("%.0f, %.0f, %.0f", p.x, p.y, p.z);
    }

    private void log(final String msg) {
        if (verbose) SNTUtils.log("[ComponentReconnector] " + msg);
    }

    private record CandidatePair(SWCPoint orphanNode, SWCPoint mainNode, double distance) {
    }
}
