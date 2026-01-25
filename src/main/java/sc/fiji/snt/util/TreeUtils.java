/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import org.jogamp.vecmath.Vector3d;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

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


}
