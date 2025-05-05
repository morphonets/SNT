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
package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.util.SupplierUtil;

/**
 * A specialized implementation of {@link SNTGraph} that represents a directed pseudograph
 * with weighted edges. A pseudograph is a graph type that allows:
 * <ul>
 *     <li>Multiple edges between the same pair of vertices (parallel edges)
 *     <li>Self-loops (edges from a vertex to itself)
 *     <li>Weighted edges
 *     <li>Directed edges
 *     <li>Cycles in the graph
 * </ul>
 *
 * <p>Like its parent class {@link SNTGraph}, this implementation supports vertex/edge coloring
 * and vertex value mapping.
 *
 * @param <V> the vertex type
 * @param <E> the edge type, must extend {@link DefaultWeightedEdge}
 *
 * @see SNTGraph
 * @see DefaultWeightedEdge
 */

public class SNTPseudograph<V, E extends DefaultWeightedEdge> extends SNTGraph<V, E> {

	private static final long serialVersionUID = 4375953050236896508L;

    /**
     * Constructs a new SNTPseudograph with edges of the specified class.
     * The graph is initialized with the following properties:
     * <ul>
     *     <li>Directed edges
     *     <li>Multiple edges allowed between vertices
     *     <li>Self-loops allowed
     *     <li>Cycles allowed
     *     <li>Weighted edges
     *     <li>Modifiable structure
     * </ul>
     *
     * @param edgeClass the class of edges to be used in this graph
     * @throws IllegalArgumentException if the edge class cannot be instantiated
     */
    public SNTPseudograph(Class<? extends E> edgeClass) {
        super(null, SupplierUtil.createSupplier(edgeClass), new DefaultGraphType.Builder()
                .directed().allowMultipleEdges(true).allowSelfLoops(true).allowCycles(true).weighted(true)
                .modifiable(true)
                .build());
    }

}
