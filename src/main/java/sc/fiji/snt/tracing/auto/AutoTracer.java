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

package sc.fiji.snt.tracing.auto;

import sc.fiji.snt.Tree;

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
