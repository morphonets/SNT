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

import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedPoint;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * Interface for automatic neuron tracers that reconstruct complete neuronal
 * morphologies from images.
 * <p>
 * Implementations include both grayscale-based tracers (e.g., {@link GWDTTracer})
 * and skeleton-based tracers (e.g., {@link BinaryTracer}).
 * </p>
 *
 * @author Tiago Ferreira
 */
public interface AutoTracer {

    // ==================== ROI Strategy Constants ====================

    /**
     * Rooting strategy: Tree is rooted at an algorithm-specific point (e.g., seed
     * point for grayscale tracers, arbitrary endpoint for binary tracers).
     * Any soma-marking ROI is ignored.
     */
    int ROI_UNSET = 0;

    /**
     * Rooting strategy: Separate trees are created for each neurite exiting the
     * soma ROI. Nodes inside the ROI are excluded from the output trees.
     */
    int ROI_EDGE = 1;

    /**
     * Rooting strategy: A single tree is created with all branches connected to
     * the geometric centroid of the soma ROI.
     */
    int ROI_CENTROID = 2;

    /**
     * Rooting strategy: A single tree is created with all branches connected to
     * a weighted centroid computed from nodes inside the soma ROI.
     */
    int ROI_CENTROID_WEIGHTED = 3;

    /**
     * Rooting strategy: Trees are rooted on nodes contained within the soma ROI.
     * Unlike {@link #ROI_EDGE}, nodes inside the ROI are included in the analysis.
     */
    int ROI_CONTAINED = 4;

    /**
     * Role a {@link SeedPoint} plays in a tracing run. Tracers honor the
     * subset they implement (see {@link #honoredSeedRoles()}) and silently
     * ignore the rest. The role list is intentionally small; new roles can
     * be added without breaking existing implementors because each role
     * maps to its own {@code default}-method setter.
     */
    enum SeedRole {
        /** Starting point: tree grows outward from here. */
        ROOT,
        /** Target / endpoint: trace toward this point. */
        TIP,
        /** Intermediate constraint: path should pass through / near this. */
        WAYPOINT
    }

    /**
     * Hands the tracer a collection of seeds to use as roots in this run.
     * Default: no-op. Concrete tracers that consume root seeds override.
     *
     * @param seeds candidate roots; may be {@code null} or empty
     */
    default void setRoots(final Collection<SeedPoint> seeds) {
        // Do nothing. Tracers consuming root seeds override.
    }

    /**
     * Hands the tracer a collection of seeds to use as tips / targets in
     * this run. Default: no-op. Concrete tracers that consume tip seeds
     * override.
     *
     * @param seeds candidate tips; may be {@code null} or empty
     */
    default void setTips(final Collection<SeedPoint> seeds) {
        // Do nothing. Tracers consuming tip seeds override.
    }

    /**
     * Hands the tracer a collection of seeds to use as waypoints (path
     * constraints) in this run. Default: no-op. Concrete tracers that
     * consume waypoint seeds override.
     *
     * @param seeds candidate waypoints; may be {@code null} or empty
     */
    default void setWaypoints(final Collection<SeedPoint> seeds) {
        // Do nothing. Tracers consuming waypoint seeds override.
    }

    /**
     * @return the {@link SeedRole}s this tracer actually honors. Callers
     *         (e.g. an "Autotrace from Seeds" wrapper command) can use this
     *         to gate UI choices and to error early when the user picks a
     *         role the tracer ignores. Default: empty set.
     */
    default EnumSet<SeedRole> honoredSeedRoles() {
        return EnumSet.noneOf(SeedRole.class);
    }

    /**
     * Traces the neuronal structure and returns a list of Trees.
     *
     * @return list of traced trees, or empty list if tracing fails
     */
    List<Tree> traceTrees();

    /**
     * Traces the neuronal structure and returns a single merged Tree.
     * <p>
     * Default implementation merges all trees from {@link #traceTrees()}.
     * </p>
     *
     * @return the traced tree, or null if tracing fails
     */
    default Tree trace() {
        final List<Tree> trees = traceTrees();
        if (trees == null || trees.isEmpty()) {
            return null;
        }
        if (trees.size() == 1) {
            return trees.getFirst();
        }
        final Tree merged = new Tree();
        for (final Tree tree : trees) {
            merged.merge(tree);
        }
        return merged;
    }
}
