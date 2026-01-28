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

import org.jgrapht.GraphType;
import org.jgrapht.Graphs;
import org.jgrapht.graph.AbstractBaseGraph;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.util.ColorRGB;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/**
 * An abstract weighted graph implementation that extends {@link AbstractBaseGraph} with additional
 * support for vertex/edge coloring and vertex value mapping. This specialized graph implementation
 * is designed for visualization and analysis purposes in Graph Viewer.
 *
 * <p>The graph maintains three main mappings:
 * <ul>
 *     <li>Vertex colors - Associates vertices with RGB colors
 *     <li>Edge colors - Associates edges with RGB colors
 *     <li>Vertex values - Associates vertices with numeric values
 * </ul>
 *
 * <p>This implementation also provides methods for filtering and transforming vertices and edges.
 *
 * @param <V> the vertex type
 * @param <E> the edge type, must extend {@link DefaultWeightedEdge}
 *
 * @see AbstractBaseGraph
 * @see DefaultWeightedEdge
 */
public abstract class SNTGraph<V, E extends DefaultWeightedEdge>
        extends AbstractBaseGraph<V, E>
{

	private static final long serialVersionUID = 8458292348918037500L;

	private final Map<V, ColorRGB> vertexColorRGBMap;
    private final Map<E, ColorRGB> edgeColorRGBMap;
    private final Map<V, Double> vertexValueMap;

    protected SNTGraph(Supplier<V> vertexSupplier, Supplier<E> edgeSupplier, GraphType type) {
        super(vertexSupplier, edgeSupplier, type);
        vertexColorRGBMap = new HashMap<>();
        edgeColorRGBMap = new HashMap<>();
        vertexValueMap = new HashMap<>();
    }

    public void setVertexColor(V vertex, ColorRGB color) {
        if (containsVertex(vertex)) {
            vertexColorRGBMap.put(vertex, color);
        }
    }

    public void setEdgeColor(E edge, ColorRGB color) {
        if (containsEdge(edge)) {
            edgeColorRGBMap.put(edge, color);
        }
    }

    public ColorRGB getVertexColor(V vertex) {
        if (containsVertex(vertex) && vertexColorRGBMap.containsKey(vertex)) {
            return vertexColorRGBMap.get(vertex);
        }
        return null;
    }

    public ColorRGB getEdgeColor(E edge) {
        if (containsEdge(edge) && edgeColorRGBMap.containsKey(edge)) {
            return edgeColorRGBMap.get(edge);
        }
        return null;
    }

    public void setVertexValue(V vertex, double value) {
        vertexValueMap.put(vertex, value);
    }

    public double getVertexValue(V vertex) {
        return vertexValueMap.get(vertex);
    }

    public Map<V, ColorRGB> getVertexColorRGBMap() {
        return vertexColorRGBMap;
    }

    public Map<E, ColorRGB> getEdgeColorRGBMap() {
        return edgeColorRGBMap;
    }

    public Map<V, Double> getVertexValueMap() {
        return vertexValueMap;
    }

    /**
     * Remove vertices from this graph that do not match the given predicate. Edges incident to removed vertices
     * are also removed from the graph.
     *
     * @param predicate a non-interfering, stateless predicate to apply to each vertex to determine if it
     * should be retained in the vertex set.
     */
    public void filterVertices(final Predicate<V> predicate) {
        removeAllVertices(vertexSet().parallelStream().filter(v -> !predicate.test(v)).collect(Collectors.toSet()));
    }

    /**
     * Remove edges from this graph that do not match the given predicate. Vertices incident to removed edges
     * are _not_ removed from the graph.
     *
     * @param predicate a non-interfering, stateless predicate to apply to each edge to determine if it
     * should be retained in the edge set.
     */
    public void filterEdges(final Predicate<E> predicate) {
        removeAllEdges(edgeSet().parallelStream().filter(e -> !predicate.test(e)).collect(Collectors.toSet()));
    }

    /**
     * Apply the given operator over the vertex set. If a vertex is changed as a result of the function
     * (on the basis of equality, e.g., vIn.equals(vOut)), the input vertex is replaced with the
     * output vertex and all neighboring edges of the input vertex are re-assigned to the output vertex.
     * If the output vertex is null, the input vertex and its neighboring edges are removed from the graph.
     *
     * @param operator a non-interfering, stateless operator to apply to each vertex
     */
    public void applyVertices(final UnaryOperator<V> operator) {
        final Map<V, V> toReplace = new HashMap<>();
        for (final V input : vertexSet()) {
            final V output = operator.apply(input);
            if (output == null || !output.equals(input)) {
                toReplace.put(input, output);
            }
        }
        for (final V input : toReplace.keySet()) {
            final V output = toReplace.get(input);
            if (output == null) {
                removeVertex(input);
                continue;
            }
            addVertex(output);
            for (final V neighbor : Graphs.neighborSetOf(this, input)) {
                removeEdge(input, neighbor);
                addEdge(output, neighbor);
            }
            removeVertex(input);
        }
    }

    /**
     * Apply the given operator over the edge set. If an edge is changed as a result of the function
     * (on the basis of equality, e.g., edgeIn.equals(edgeOut)), the input edge between source and target
     * is replaced with the output edge.
     * If the output edge is null, the input edge is removed from the graph.
     *
     * @param operator a non-interfering, stateless operator to apply to each edge
     */
    public void applyEdges(final UnaryOperator<E> operator) {
        final Map<E, E> toReplace = new HashMap<>();
        for (final E input : edgeSet()) {
            final E output = operator.apply(input);
            if (output == null || !output.equals(input)) {
                toReplace.put(input, output);
            }
        }
        for (final E input : toReplace.keySet()) {
            final E output = toReplace.get(input);
            if (output == null) {
                removeEdge(input);
                continue;
            }
            final V source = getEdgeSource(input);
            final V target = getEdgeTarget(input);
            removeEdge(input);
            addEdge(source, target, output);
        }
    }

}
