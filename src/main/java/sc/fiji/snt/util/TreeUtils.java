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

package sc.fiji.snt.util;

import ij.measure.Calibration;
import org.jogamp.vecmath.Vector3d;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

import java.util.*;

/**
 * Static utilities for Trees.
 *
 * @author Tiago Ferreira
 */
public class TreeUtils {

    private TreeUtils() {
    }

    /**
     * Assigns distinct colors to paths of a Tree.
     *
     * @see SNTColor#getDistinctColors(int, String)
     */
    public static void assignUniqueColors(final Tree tree) {
        assignUniqueColors(tree, "");
    }

    /**
     * Assigns distinct colors to paths of a Tree.
     * @param excludedHue an optional string defining a hue to be excluded. Either 'red', 'green', 'blue', or 'dim'.
     * @see SNTColor#getDistinctColors(int, String)
     */
    public static void assignUniqueColors(final Tree tree, final String excludedHue) {
        final ColorRGB[] colors = SNTColor.getDistinctColors(tree.size(), excludedHue);
        int idx = 0;
        for (final Path p : tree.list()) p.setColor(colors[idx++]);
    }

    /**
     * Assigns distinct colors to a collection of Trees.
     *
     * @see SNTColor#getDistinctColors(int)
     */
    public static void assignUniqueColors(final Collection<Tree> trees) {
        assignUniqueColors(trees, "");
    }

    /**
     * Assigns distinct colors to a collection of Trees.
     *
     * @param excludedHue an optional string defining a hue to be excluded. Either 'red', 'green', 'blue', or 'dim'.
     * @see SNTColor#getDistinctColors(int, String)
     */
    public static void assignUniqueColors(final Collection<Tree> trees, final String excludedHue) {
        final ColorRGB[] colors = SNTColor.getDistinctColors(trees.size(), excludedHue);
        int i = 0;
        for (final Iterator<Tree> it = trees.iterator(); it.hasNext(); i++) {
            it.next().setColor(colors[i]);
        }
    }

    // Tangent from last N points of path
    private static Vector3d getEndTangent(final Path path) {
        final int n = path.size();
        final int windowSize = Math.min(5, n);
        if (windowSize < 2) return null;

        // Linear regression on last windowSize points
        // Or simpler: vector from point[n-windowSize] to point[n-1]
        final PointInImage p1 = path.getNode(n - windowSize);
        final PointInImage p2 = path.getNode(n - 1);
        final Vector3d v = new Vector3d(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z);
        v.normalize();
        return v;
    }

    // Tangent from first N points of path
    private static Vector3d getStartTangent(final Path path) {
        final int n = path.size();
        final int windowSize = Math.min(5, n);
        if (windowSize < 2) return null;

        final PointInImage p1 = path.getNode(0);
        final PointInImage p2 = path.getNode(windowSize - 1);
        final Vector3d v = new Vector3d(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z);
        v.normalize();
        return v;
    }

    /**
     * Computes the angle between two direction vectors.
     *
     * @param v1 first direction vector (should be normalized)
     * @param v2 second direction vector (should be normalized)
     * @return angle in degrees (0-180)
     */
    private static double computeAngleBetweenVectors(final Vector3d v1, final Vector3d v2) {
        // Vectors from getExtensionDirection3D() are already normalized
        final double dot = v1.dot(v2);
        // Clamp to [-1, 1] to handle floating-point precision issues
        final double clampedDot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(clampedDot));
    }

    /**
     * Merges a child path into its parent path.
     * <p>
     * The child's nodes are appended to the parent (via {@link Path#add(Path)}),
     * and the child's children (grandchildren) are reparented to the extended parent.
     * The child path is then removed from this tree.
     * </p>
     *
     * @param tree   the Tree holding parent and child
     * @param parent the parent path that will absorb the child
     * @param child  the child path to be merged into parent
     */
    private static void mergeChildIntoParent(final Tree tree, final Path parent, final Path child) {
        // Store grandchildren before modifying relationships
        final List<Path> grandchildren = new ArrayList<>(child.getChildren());

        // Determine if child needs to be reversed
        // The child's first node should be near the parent's last node (the branch point)
        final PointInImage parentEnd = parent.lastNode();
        final PointInImage childStart = child.firstNode();
        final PointInImage childEnd = child.lastNode();

        if (parentEnd != null && childStart != null && childEnd != null) {
            final double distToStart = parentEnd.distanceSquaredTo(childStart);
            final double distToEnd = parentEnd.distanceSquaredTo(childEnd);

            if (distToEnd < distToStart) {
                // Child is oriented backwards relative to parent, reverse it
                child.reverse();
            }
        }

        // Detach child from parent (this also removes from parent's children list)
        child.detachFromParent();

        // Append child's nodes to parent
        // Path.add() handles skipping duplicate nodes at the junction
        parent.add(child);

        // Reparent grandchildren to the extended parent
        // Their branch points should now be on the extended parent path
        for (final Path grandchild : grandchildren) {
            final PointInImage branchPoint = grandchild.getBranchPoint();
            grandchild.detachFromParent();
            grandchild.setBranchFrom(parent, branchPoint);
        }

        // Remove child from this tree's path list
        tree.list().remove(child);
    }

    /**
     * Merges paths that continue along the same trajectory at branch points,
     * reducing fragmentation in auto-traced reconstructions.
     * <p>
     * <b>Problem:</b> Auto-tracers like {@link sc.fiji.snt.tracing.auto.GWDTTracer} often
     * create separate paths at every branch point, even when the neurite continues
     * straight through. This may fragment continuous paths into smaller segments.
     * </p>
     * <p>
     * <b>Solution:</b> At each junction where a parent path ends and children begin,
     * this method checks if any child's trajectory aligns with the parent's. If the
     * angle between their direction vectors is below the threshold, the most aligned
     * child is merged into the parent, creating a longer continuous path.
     * </p>
     * <p>
     * <b>Algorithm:</b>
     * <ol>
     *   <li>For each path with children, compute the parent's end tangent vector
     *       (direction at its terminal segment)</li>
     *   <li>For each child, compute its start tangent vector</li>
     *   <li>Find the child with the smallest angle to the parent direction</li>
     *   <li>If this angle ≤ threshold: append child's nodes to parent, reparent
     *       grandchildren to the extended parent, remove child from tree</li>
     *   <li>Repeat until no more merges are possible</li>
     * </ol>
     * </p>
     * <p>
     * <b>Criteria for merging:</b> Two paths are merged when:
     * <ul>
     *   <li>The child starts at the parent's endpoint (branch point)</li>
     *   <li>The angle between their direction vectors ≤ {@code angleThreshold}</li>
     *   <li>The child is the <i>most aligned</i> among all siblings</li>
     * </ul>
     * </p>
     * <p>
     * <b>Example:</b> A traced axon might be split into 5 paths at 4 branch points:
     * <pre>
     * Before: P1 → P2 → P3 → P4 → P5  (with side branches B1, B2, B3, B4)
     * After:  P1 (merged trunk) with children B1, B2, B3, B4
     * </pre>
     * </p>
     *
     * @param angleThreshold maximum angle (in degrees) between parent end tangent
     *                       and child start tangent for paths to be considered
     *                       continuous. Suggested values:
     *                       <ul>
     *                         <li>15-20°: strict, only nearly straight continuations</li>
     *                         <li>30°: moderate (default), allows gentle curves</li>
     *                         <li>45°: permissive, may merge actual branches</li>
     *                       </ul>
     *                       Use 0 or negative to disable merging.
     * @return the number of paths that were merged (and removed from the tree)
     */
    public int mergeContinuousPaths(final Tree tree, final double angleThreshold) {
        if (tree.list().isEmpty() || angleThreshold <= 0) return 0;

        int totalMerged = 0;
        boolean changed = true;

        while (changed) {
            changed = false;

            for (int i = 0; i < tree.list().size(); i++) {
                final Path parent = tree.list().get(i);
                final List<Path> children = parent.getChildren();
                if (children.isEmpty()) continue;
                if (parent.size() < 2) continue;

                // Get parent's END tangent (last few points, not whole path)
                final Vector3d parentEndTangent = getEndTangent(parent);
                if (parentEndTangent == null) continue;

                // Get parent endpoint position and radius
                final Path.PathNode parentEnd = parent.getNode(parent.size() - 1);

                Path mostAligned = null;
                double minAngle = Double.MAX_VALUE;

                for (final Path child : children) {
                    if (child.size() < 2) continue;

                    // Check 1: Child must start at parent's endpoint
                    final Path.PathNode childStart = child.getNode(0);
                    final double dist = parentEnd.distanceTo(childStart);
                    final double radiusSum = parentEnd.getRadius() + childStart.getRadius();
                    if (dist > radiusSum * 1.5) continue; // Not connected at endpoint

                    // Check 2: Radius compatibility
                    final double radiusRatio = parentEnd.getRadius() / childStart.getRadius();
                    if (radiusRatio < 0.5 || radiusRatio > 2.0) continue;

                    // Check 3: Direction alignment using START tangent of child
                    final Vector3d childStartTangent = getStartTangent(child);
                    if (childStartTangent == null) continue;

                    final double angle = computeAngleBetweenVectors(parentEndTangent, childStartTangent);
                    if (angle < minAngle) {
                        minAngle = angle;
                        mostAligned = child;
                    }
                }

                if (mostAligned != null && minAngle <= angleThreshold) {
                    mergeChildIntoParent(tree, parent, mostAligned);
                    totalMerged++;
                    changed = true;
                    break;
                }
            }
        }
        return totalMerged;
    }

    /**
     * Result of finding the closest endpoints between two paths.
     *
     * @param distance  the Euclidean distance between the closest endpoints
     * @param p1AtStart true if path1's start node is the closest endpoint, false if end
     * @param p2AtStart true if path2's start node is the closest endpoint, false if end
     */
    public record EndpointMatch(double distance, boolean p1AtStart, boolean p2AtStart) {

        /**
         * Checks if the paths are in "natural" order for merging (p1.end → p2.start).
         * @return true if no reversal is needed for either path
         */
        public boolean isNaturalOrder() {
            return !p1AtStart && p2AtStart;
        }
    }

    /**
     * Finds the closest endpoint pairing between two paths.
     * Checks all four combinations: start-start, start-end, end-start, end-end.
     *
     * @param p1 first path
     * @param p2 second path
     * @return EndpointMatch describing the closest pairing, or null if either path is empty
     */
    public static EndpointMatch findClosestEndpoints(final Path p1, final Path p2) {
        if (p1 == null || p2 == null || p1.size() == 0 || p2.size() == 0) {
            return null;
        }

        final PointInImage p1Start = p1.firstNode();
        final PointInImage p1End = p1.lastNode();
        final PointInImage p2Start = p2.firstNode();
        final PointInImage p2End = p2.lastNode();

        double minDistSq = Double.MAX_VALUE;
        boolean p1AtStart = false;
        boolean p2AtStart = false;

        double d = p1Start.distanceSquaredTo(p2Start);
        if (d < minDistSq) { minDistSq = d; p1AtStart = true; p2AtStart = true; }

        d = p1Start.distanceSquaredTo(p2End);
        if (d < minDistSq) { minDistSq = d; p1AtStart = true; p2AtStart = false; }

        d = p1End.distanceSquaredTo(p2Start);
        if (d < minDistSq) { minDistSq = d; p1AtStart = false; p2AtStart = true; }

        d = p1End.distanceSquaredTo(p2End);
        if (d < minDistSq) { minDistSq = d; p1AtStart = false; p2AtStart = false; }

        return new EndpointMatch(Math.sqrt(minDistSq), p1AtStart, p2AtStart);
    }

    /**
     * Orders a collection of paths to form a spatially continuous chain based on
     * endpoint proximity. The algorithm greedily connects paths by finding the
     * closest endpoint pairs.
     * <p>
     * This method does NOT modify the paths (no reversal). Use
     * {@link #orientPathsForMerging(List)} on the result to fix orientations.
     * </p>
     *
     * @param paths the paths to order (at least 2)
     * @return ordered list forming a chain, or empty list if paths cannot form a continuous chain
     */
    public static List<Path> orderByEndpointProximity(final Collection<Path> paths) {
        if (paths == null || paths.size() < 2) {
            return new ArrayList<>(paths != null ? paths : List.of());
        }

        final List<Path> remaining = new ArrayList<>(paths);
        final LinkedList<Path> chain = new LinkedList<>();

        // Start with the first path
        chain.add(remaining.removeFirst());

        // Greedily extend the chain at either end
        while (!remaining.isEmpty()) {
            final Path chainStart = chain.getFirst();
            final Path chainEnd = chain.getLast();

            Path bestPath = null;
            double bestDist = Double.MAX_VALUE;
            boolean addToFront = false;

            for (final Path candidate : remaining) {
                // Check distance to chain start
                final EndpointMatch matchStart = findClosestEndpoints(chainStart, candidate);
                if (matchStart != null && matchStart.distance() < bestDist) {
                    bestDist = matchStart.distance();
                    bestPath = candidate;
                    addToFront = true;
                }

                // Check distance to chain end
                final EndpointMatch matchEnd = findClosestEndpoints(chainEnd, candidate);
                if (matchEnd != null && matchEnd.distance() < bestDist) {
                    bestDist = matchEnd.distance();
                    bestPath = candidate;
                    addToFront = false;
                }
            }

            if (bestPath == null) {
                return List.of(); // No suitable connection found
            }

            remaining.remove(bestPath);
            if (addToFront) {
                chain.addFirst(bestPath);
            } else {
                chain.addLast(bestPath);
            }
        }

        return new ArrayList<>(chain);
    }

    /**
     * Orients paths in a chain so they can be merged end-to-start.
     * After this operation, each path's end node will be near the next path's start node.
     * <p>
     * Paths are reversed in-place as needed.
     * </p>
     *
     * @param orderedPaths list of paths previously ordered by {@link #orderByEndpointProximity}
     */
    public static void orientPathsForMerging(final List<Path> orderedPaths) {
        if (orderedPaths == null || orderedPaths.size() < 2) {
            return;
        }

        for (int i = 0; i < orderedPaths.size() - 1; i++) {
            final Path current = orderedPaths.get(i);
            final Path next = orderedPaths.get(i + 1);

            final EndpointMatch match = findClosestEndpoints(current, next);
            if (match == null) continue;

            // We want current.end → next.start
            if (match.p1AtStart()) {
                current.reverse();
            }
            if (!match.p2AtStart()) {
                next.reverse();
            }
        }
    }

    /**
     * Checks if a collection of paths can form a spatially continuous chain
     * within a given tolerance.
     *
     * @param paths     the paths to check
     * @param tolerance maximum allowed distance between consecutive endpoints
     * @return true if paths can be ordered into a continuous chain within tolerance
     */
    public static boolean canFormContinuousChain(final Collection<Path> paths, final double tolerance) {
        if (paths == null || paths.size() < 2) {
            return true; // Single path or empty is trivially continuous
        }

        final List<Path> ordered = orderByEndpointProximity(paths);
        if (ordered.isEmpty()) {
            return false;
        }

        for (int i = 0; i < ordered.size() - 1; i++) {
            final EndpointMatch match = findClosestEndpoints(ordered.get(i), ordered.get(i + 1));
            if (match == null || match.distance() > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Merges a list of paths into a single path by appending nodes sequentially.
     * <p>
     * The paths should be pre-ordered and oriented using {@link #orderByEndpointProximity}
     * and {@link #orientPathsForMerging}.
     * </p>
     * <p>
     * This method handles:
     * <ul>
     *   <li>Appending nodes from each path to the result</li>
     *   <li>Optionally reparenting children of merged paths to the result</li>
     *   <li>Preserving the first path's parent connection (if any)</li>
     * </ul>
     * </p>
     * <p>
     * <b>Note:</b> This method does NOT delete the original paths from any manager.
     * The caller is responsible for cleanup.
     * </p>
     *
     * @param orderedPaths     paths to merge, in order (should be oriented for merging)
     * @param reparentChildren if true, children of merged paths are reconnected to the result
     * @return the merged path, or null if input is empty
     */
    public static Path mergePaths(final List<Path> orderedPaths, final boolean reparentChildren) {
        if (orderedPaths == null || orderedPaths.isEmpty()) {
            return null;
        }

        if (orderedPaths.size() == 1) {
            return orderedPaths.getFirst();
        }

        final Path first = orderedPaths.getFirst();
        final Path result = first.createPath();
        result.setName(first.getName());

        // Preserve first path's parent connection
        final Path firstParent = first.getParentPath();
        final PointInImage firstBranchPoint = first.getBranchPoint();

        // Collect children for reparenting
        final List<ChildBranchInfo> childrenToReparent = new ArrayList<>();

        for (final Path p : orderedPaths) {
            if (reparentChildren) {
                for (final Path child : new ArrayList<>(p.getChildren())) {
                    childrenToReparent.add(new ChildBranchInfo(child, child.getBranchPoint()));
                    child.detachFromParent();
                }
            }

            if (p.getParentPath() != null) {
                p.detachFromParent();
            }

            result.add(p);
        }

        // Reparent children to merged path
        if (reparentChildren) {
            for (final ChildBranchInfo info : childrenToReparent) {
                final PointInImage closest = result.nearestNodeTo(info.branchPoint, Double.MAX_VALUE);
                if (closest != null) {
                    info.child.setBranchFrom(result, closest);
                }
            }
        }

        // Restore first path's parent connection
        if (firstParent != null && firstBranchPoint != null) {
            result.setBranchFrom(firstParent, firstBranchPoint);
        }

        return result;
    }

    /** Helper record for storing child path with its original branch point. */
    private record ChildBranchInfo(Path child, PointInImage branchPoint) {}


    /**
     * Result of analyzing which endpoint cluster (starts vs ends) is tighter.
     *
     * @param useStartNodes true if start nodes form the tighter cluster (should be the root)
     * @param clusterCentroid the centroid of the tighter cluster
     * @param variance the variance (spread) of the tighter cluster
     */
    public record EndpointClusterAnalysis(boolean useStartNodes, PointInImage clusterCentroid, double variance) {}

    /**
     * Analyzes a collection of paths to determine which endpoint cluster (starts or ends)
     * is more tightly grouped, suggesting the root location.
     * <p>
     * This is useful for auto-orienting paths toward a common root when the user
     * hasn't specified an explicit root location.
     *
     * @param paths the paths to analyze (must have at least 2)
     * @return analysis result, or null if paths is null or has fewer than 2 paths
     */
    public static EndpointClusterAnalysis analyzeEndpointClusters(final Collection<Path> paths) {
        if (paths == null || paths.size() < 2) return null;

        final List<PointInImage> startNodes = new ArrayList<>();
        final List<PointInImage> endNodes = new ArrayList<>();

        for (final Path p : paths) {
            if (p.size() == 0) continue;
            startNodes.add(p.firstNode());
            endNodes.add(p.lastNode());
        }

        if (startNodes.size() < 2) return null;

        final PointInImage startCentroid = SNTPoint.average(startNodes);
        final PointInImage endCentroid = SNTPoint.average(endNodes);

        final double startVariance = computeVariance(startNodes, startCentroid);
        final double endVariance = computeVariance(endNodes, endCentroid);

        // Tighter cluster = lower variance = likely the root
        if (startVariance <= endVariance) {
            return new EndpointClusterAnalysis(true, startCentroid, startVariance);
        } else {
            return new EndpointClusterAnalysis(false, endCentroid, endVariance);
        }
    }

    /**
     * Computes the variance (average squared distance) of points from a centroid.
     */
    private static double computeVariance(final List<PointInImage> points, final PointInImage centroid) {
        if (points.isEmpty()) return Double.MAX_VALUE;
        double sumSq = 0;
        for (final PointInImage p : points) {
            sumSq += p.distanceSquaredTo(centroid);
        }
        return sumSq / points.size();
    }

    /**
     * Determines which paths need to be reversed so that their start nodes point
     * toward a given root location.
     * <p>
     * A path should be reversed if its end node is closer to the root than its start node.
     *
     * @param paths the paths to analyze
     * @param rootLocation the target root location
     * @return list of paths that should be reversed (subset of input)
     */
    public static List<Path> findPathsNeedingReversal(final Collection<Path> paths, final PointInImage rootLocation) {
        if (paths == null || rootLocation == null) return Collections.emptyList();

        final List<Path> needsReversal = new ArrayList<>();
        for (final Path p : paths) {
            if (p.size() == 0) continue;
            final double distFromStart = p.firstNode().distanceSquaredTo(rootLocation);
            final double distFromEnd = p.lastNode().distanceSquaredTo(rootLocation);
            if (distFromEnd < distFromStart) {
                needsReversal.add(p);
            }
        }
        return needsReversal;
    }

    /**
     * Orients paths so their start nodes point toward a given root location.
     * Paths are reversed in-place if their end node is closer to the root.
     * <p>
     * <b>Note:</b> This method does NOT update child branch points. Callers
     * must handle child branch point updates separately if paths have children.
     *
     * @param paths the paths to orient
     * @param rootLocation the target root location
     * @return the number of paths that were reversed
     */
    public static int orientPathsTowardRoot(final Collection<Path> paths, final PointInImage rootLocation) {
        final List<Path> toReverse = findPathsNeedingReversal(paths, rootLocation);
        for (final Path p : toReverse) {
            p.reverse();
        }
        return toReverse.size();
    }

    /**
     * Analyzes paths and suggests auto-orientation based on:
     * <ol>
     *   <li>If a reference point (e.g., ROI centroid) is provided, orient toward it</li>
     *   <li>For 3+ paths: find the tighter endpoint cluster as the root</li>
     *   <li>For 2 paths: find the closest endpoint pair as the root</li>
     * </ol>
     *
     * @param paths the paths to analyze
     * @param referencePoint optional reference point (e.g., ROI centroid); can be null
     * @return the suggested root location, or null if it cannot be determined
     */
    public static PointInImage suggestRootLocation(final Collection<Path> paths, final PointInImage referencePoint) {
        if (paths == null || paths.isEmpty()) return null;

        // If reference point provided, use it
        if (referencePoint != null) {
            return referencePoint;
        }

        final List<Path> pathList = new ArrayList<>(paths);

        // For 3+ paths, use cluster analysis
        if (pathList.size() >= 3) {
            final EndpointClusterAnalysis analysis = analyzeEndpointClusters(paths);
            if (analysis != null) {
                return analysis.clusterCentroid();
            }
        }

        // For 2 paths, find closest endpoints
        if (pathList.size() == 2) {
            final Path p1 = pathList.get(0);
            final Path p2 = pathList.get(1);
            final EndpointMatch match = findClosestEndpoints(p1, p2);
            if (match != null) {
                // The closest endpoints should be the root
                final PointInImage ep1 = match.p1AtStart() ? p1.firstNode() : p1.lastNode();
                final PointInImage ep2 = match.p2AtStart() ? p2.firstNode() : p2.lastNode();
                return SNTPoint.average(List.of(ep1, ep2));
            }
        }

        // Fallback: average of start nodes
        final List<PointInImage> startNodes = new ArrayList<>();
        for (final Path p : paths) {
            if (p.size() > 0) startNodes.add(p.firstNode());
        }
        return startNodes.isEmpty() ? null : SNTPoint.average(startNodes);
    }

    /**
     * Gets the connected tree (component) containing the given path.
     * This traverses up to the root and then collects all descendants,
     * ensuring we only get paths that are actually connected via parent-child relationships.
     * <p>
     * This is useful when paths may share a tree ID but are not actually connected
     * (e.g., orphaned paths or paths awaiting relationship rebuild).
     *
     * @param path a path in the tree
     * @return a Tree containing only the connected component
     */
    public static Tree getConnectedTree(final Path path) {
        if (path == null) {
            return new Tree();
        }
        // Find the root of this connected component
        Path root = path;
        while (root.getParentPath() != null) {
            root = root.getParentPath();
        }
        // Collect all paths in this connected component
        final Tree tree = new Tree();
        collectConnectedPaths(root, tree);
        return tree;
    }

    /**
     * Recursively collects a path and all its descendants into the tree.
     *
     * @param path the path to add (along with its descendants)
     * @param tree the tree to collect paths into
     */
    private static void collectConnectedPaths(final Path path, final Tree tree) {
        tree.add(path);
        for (final Path child : path.getChildren()) {
            collectConnectedPaths(child, tree);
        }
    }

    /**
     * Checks if 'target' is in the subtree rooted at 'root'.
     * This traverses down through all descendants of root.
     *
     * @param target the path to search for
     * @param root   the root of the subtree to search in
     * @return true if target is root or any descendant of root
     */
    public static boolean isInSubtree(final Path target, final Path root) {
        if (root == null || target == null) {
            return false;
        }
        if (root == target) {
            return true;
        }
        for (final Path child : root.getChildren()) {
            if (isInSubtree(target, child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if 'potentialDescendant' is a descendant of 'potentialAncestor'.
     * This is equivalent to checking if potentialDescendant is in the subtree
     * rooted at potentialAncestor (excluding potentialAncestor itself).
     *
     * @param potentialDescendant the path to check if it's a descendant
     * @param potentialAncestor   the path to check if it's an ancestor
     * @return true if potentialDescendant is a descendant of potentialAncestor
     */
    public static boolean isDescendantOf(final Path potentialDescendant, final Path potentialAncestor) {
        if (potentialAncestor == null || potentialDescendant == null) {
            return false;
        }
        if (potentialAncestor == potentialDescendant) {
            return false; // A path is not a descendant of itself
        }
        return isInSubtree(potentialDescendant, potentialAncestor);
    }

    /**
     * Checks if 'potentialAncestor' is an ancestor of 'potentialDescendant'.
     * This traverses up the parent chain from potentialDescendant.
     *
     * @param potentialAncestor   the path to check if it's an ancestor
     * @param potentialDescendant the path to check if it has the ancestor
     * @return true if potentialAncestor is an ancestor of potentialDescendant
     */
    public static boolean isAncestorOf(final Path potentialAncestor, final Path potentialDescendant) {
        if (potentialAncestor == null || potentialDescendant == null) {
            return false;
        }
        Path current = potentialDescendant.getParentPath();
        while (current != null) {
            if (current == potentialAncestor) {
                return true;
            }
            current = current.getParentPath();
        }
        return false;
    }

    /**
     * Returns the maximum path order in the connected tree containing the given path.
     * Higher values indicate deeper/more developed trees.
     *
     * @param path a path in the tree
     * @return the maximum order value found in the connected tree, or 0 if retrieval fails
     */
    public static int getMaxOrder(final Path path) {
        final Tree tree = getConnectedTree(path);
        return tree.list().stream()
                .mapToInt(Path::getOrder)
                .max()
                .orElse(0);
    }

    /**
     * Counts all descendants (children, grandchildren, etc.) of a path.
     */
    public static int countDescendants(final Path path) {
        if (path == null) return 0;
        int count = 0;
        for (final Path child : path.getChildren()) {
            count++; // Count this child
            count += countDescendants(child); // Count grandchildren recursively
        }
        return count;
    }

    /**
     * Collects direct children of a path into the provided collection.
     */
    public static void collectChildren(final Path path, final Collection<Path> children) {
        if (path == null) return;
        children.addAll(path.getChildren());
    }

    /**
     * Collects all descendants (children, grandchildren, etc.) of a path into the provided collection.
     */
    public static void collectDescendants(final Path path, final Collection<Path> descendants) {
        if (path == null) return;
        for (final Path child : path.getChildren()) {
            descendants.add(child);
            collectDescendants(child, descendants);
        }
    }

    /**
     * Gets the root path of the tree containing the given path.
     */
    public static Path getRoot(final Path path) {
        if (path == null) return null;
        Path root = path;
        while (root.getParentPath() != null) {
            root = root.getParentPath();
        }
        return root;
    }

    /**
     * Applies the parent's canvas offset to the child path and all its descendants.
     * This ensures paths are in the same coordinate space after connection.
     * Node coordinates are transformed to maintain the same visual position.
     */
    public static void syncCanvasOffset(final Path child, final Path parent) {
        final PointInCanvas parentOffset = parent.getCanvasOffset();
        final PointInCanvas childOffset = child.getCanvasOffset();
        // Skip if offsets are already the same
        if (childOffset.isSameLocation(parentOffset)) {
            return;
        }
        // Calculate the offset difference (in pixel/canvas units)
        final Calibration cal = child.getCalibration();
        final double dx = (childOffset.x - parentOffset.x) * cal.pixelWidth;
        final double dy = (childOffset.y - parentOffset.y) * cal.pixelHeight;
        final double dz = (childOffset.z - parentOffset.z) * cal.pixelDepth;
        // Transform node coordinates and apply new offset recursively
        applyCanvasOffsetRecursively(child, parentOffset, dx, dy, dz);
    }

    /**
     * Recursively applies a canvas offset to a path and all its children,
     * transforming node coordinates to maintain visual position.
     */
    private static void applyCanvasOffsetRecursively(final Path path, final PointInCanvas newOffset,
                                                     final double dx, final double dy, final double dz) {
        // Transform all node coordinates
        for (int i = 0; i < path.size(); i++) {
            final PointInImage node = path.getNode(i);
            node.x += dx;
            node.y += dy;
            node.z += dz;
        }
        // Set new offset
        path.setCanvasOffset(newOffset);
        // Recurse to children
        for (final Path child : path.getChildren()) {
            applyCanvasOffsetRecursively(child, newOffset, dx, dy, dz);
        }
    }

    /**
     * Splits a tree with multiple primary paths into separate trees,
     * one rooted at each primary path.
     */
    public static List<Tree> splitByPrimaryPaths(final Tree tree) {
        // Find all primary paths
        final List<Path> primaryPaths = new ArrayList<>();
        for (final Path p : tree.list()) {
            if (p.isPrimary()) {
                primaryPaths.add(p);
            }
        }

        if (primaryPaths.size() <= 1) {
            // No split needed or possible
            return Collections.singletonList(tree);
        }

        // For each primary path, collect it and all its descendants
        int counter = 1;
        final List<Tree> componentTrees = new ArrayList<>();
        for (final Path primaryPath : primaryPaths) {
            final Set<Path> componentPaths = new HashSet<>();
            collectDescendants(primaryPath, tree, componentPaths);
            if (!componentPaths.isEmpty()) {
                final Tree cTree = new Tree(componentPaths);
                cTree.setLabel(String.format("%s [Component %d]", tree.getLabel(), counter++));
                componentTrees.add(cTree);
            }
        }

        return componentTrees;
    }

    /**
     * Recursively collects a path and all paths that branch from it.
     */
    private static void collectDescendants(final Path path, final Tree tree, final Set<Path> collected) {
        if (path == null || collected.contains(path)) return;
        collected.add(path);
        // Find all paths that have this path as their parent
        for (final Path p : tree.list()) {
            if (p.getParentPath() == path) {
                collectDescendants(p, tree, collected);
            }
        }
    }

}
