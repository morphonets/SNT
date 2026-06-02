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

package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.util.SupplierUtil;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.SWCPoint;

import java.util.Collection;

/**
 * Memory-efficient variant of {@link DirectedWeightedGraph} backed by a compressed sparse-row (CSR) adjacency
 * representation ({@link CsrDirectedSpecifics}). Public surface is identical to {@link DirectedWeightedGraph}
 * <p>
 * Intended for the post-Fast-Marching forest produced by the disk-backed GWDT tracer, where the dense object-based
 * specifics jgrapht uses by default can run to multi-gigabyte heaps for 10⁷+-vertex graphs. Per-vertex
 * overhead with this class is on the order of 30 bytes vs ≈400 bytes for the efault {@code FastLookupDirectedSpecifics}.
 * <p>
 * Trade-off: {@code getEdge(v1, v2)} degrades from O(1) to O(out-degree of v1) because the per-pair edge index is gone.
 *
 * @author Tiago Ferreira
 * @see CsrDirectedSpecifics
 * @see CsrGraphSpecificsStrategy
 */
public class SparseDirectedWeightedGraph extends DirectedWeightedGraph {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an empty sparse directed-weighted graph. Edges are unweighted until {@link #setEdgeWeight} or
     * {@link #assignEdgeWeightsEuclidean()} is called, matching {@link DirectedWeightedGraph}'s default behavior.
     */
    public SparseDirectedWeightedGraph() {
        // We bypass DirectedWeightedGraph's default ctor and go directly to SNTGraph's 4-arg ctor so we can install
        // the CSR specifics strategy. The graph type is identical to DirectedWeightedGraph's: directed, weighted,
        // no multi-edges, no self-loops, no cycles
        super(null,
                SupplierUtil.createSupplier(SWCWeightedEdge.class),
                new DefaultGraphType.Builder()
                        .directed()
                        .allowMultipleEdges(false)
                        .allowSelfLoops(false)
                        .allowCycles(false)
                        .weighted(true)
                        .modifiable(true)
                        .build(),
                new CsrGraphSpecificsStrategy());
    }

    /**
     * Builds a CSR-backed graph from a {@link Tree}. Edge weights are set to inter-node Euclidean distances.
     * Mirrors {@link DirectedWeightedGraph#DirectedWeightedGraph(Tree)} but uses ~20x less per-vertex memory.
     * Useful when batching many {@link sc.fiji.snt.analysis.StrahlerAnalyzer} /
     * {@link sc.fiji.snt.analysis.TreeStatistics} instances against large neuron collections.
     *
     * @param tree the source tree
     * @throws IllegalArgumentException if {@code tree} contains multiple roots
     */
    public SparseDirectedWeightedGraph(final Tree tree) {
        this(tree, true);
    }

    /**
     * Builds a CSR-backed graph from a {@link Tree}, with an option to skip Euclidean edge-weight assignment.
     *
     * @param tree                    the source tree
     * @param assignDistancesToWeight if {@code true}, edge weights are set to inter-node Euclidean distances
     * @throws IllegalArgumentException if {@code tree} contains multiple roots
     */
    public SparseDirectedWeightedGraph(final Tree tree, final boolean assignDistancesToWeight) {
        this();
        this.tree = tree;
        init(tree.getNodesAsSWCPoints(), assignDistancesToWeight);
    }

    /**
     * Builds a CSR-backed graph from a collection of SWC nodes. Mirrors
     * {@link DirectedWeightedGraph#DirectedWeightedGraph(Collection, boolean)}.
     *
     * @param nodes                   the SWC nodes
     * @param assignDistancesToWeight if {@code true}, edge weights are set to inter-node Euclidean distances
     */
    public SparseDirectedWeightedGraph(final Collection<SWCPoint> nodes,
                                       final boolean assignDistancesToWeight) {
        this();
        init(nodes, assignDistancesToWeight);
    }
}
