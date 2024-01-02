/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;

/**
 * Utilities for AnnotationGraph handling and manipulation.
 */
public class AnnotationGraphUtils {

    /**
     * Return the graph representing the
     * <a href="https://reference.wolfram.com/language/ref/GraphUnion.html">union</a>
     * of the edge sets of a collection of graphs.
     *
     * @param graphs the graph collection
     * @return the union graph
     */
    public static AnnotationGraph union(Collection<AnnotationGraph> graphs) {
        List<Set<HashableVertex>> vertexSets = getHashableVertexSetList(graphs);
        List<Set<HashableEdge>> edgeSets = getHashableEdgeSetList(graphs);
        Set<HashableVertex> vertexResult = vertexSets.get(0);
        for (Set<HashableVertex> vertexSet : vertexSets) {
            vertexResult.addAll(vertexSet);
        }
        Set<HashableEdge> edgeResult = edgeSets.get(0);
        for (Set<HashableEdge> edgeSet : edgeSets) {
            edgeResult.addAll(edgeSet);
        }
        return buildGraph(vertexResult, edgeResult);
    }

    /**
     * Return the graph representing the
     * <a href="https://reference.wolfram.com/language/ref/GraphIntersection.html">intersection</a>
     * of the edge sets of a collection of graphs.
     *
     * @param graphs the graph collection
     * @return the intersection graph
     */
    public static AnnotationGraph intersection(Collection<AnnotationGraph> graphs) {
        List<Set<HashableVertex>> vertexSets = getHashableVertexSetList(graphs);
        List<Set<HashableEdge>> edgeSets = getHashableEdgeSetList(graphs);
        Set<HashableVertex> vertexResult = vertexSets.get(0);
        for (Set<HashableVertex> vertexSet : vertexSets) {
            vertexResult.addAll(vertexSet);
        }
        Set<HashableEdge> edgeResult = edgeSets.get(0);
        for (Set<HashableEdge> edgeSet : edgeSets) {
            edgeResult.retainAll(edgeSet);
        }
        return buildGraph(vertexResult, edgeResult);
    }

    /**
     * Return the graph representing the
     * <a href="https://reference.wolfram.com/language/ref/GraphDifference.html">difference</a>
     * of edge sets of graph1 - graph2
     *
     * @param graph1
     * @param graph2
     * @return the difference graph
     */
    public static AnnotationGraph difference(AnnotationGraph graph1, AnnotationGraph graph2) {
        Set<HashableVertex> vertexSet1 = getHashableVertexSet(graph1.vertexSet());
        Set<HashableVertex> vertexSet2 = getHashableVertexSet(graph2.vertexSet());
        Set<HashableEdge> edgeSet1 = getHashableEdgeSet(graph1.edgeSet());
        Set<HashableEdge> edgeSet2 = getHashableEdgeSet(graph2.edgeSet());
        vertexSet1.addAll(vertexSet2);
        edgeSet1.removeAll(edgeSet2);
        return buildGraph(vertexSet1, edgeSet1);
    }

    /**
     * Return the graph representing the
     * <a href="https://mathworld.wolfram.com/SymmetricDifference.html">symmetric difference</a>
     * of the edge sets of graph1 and graph2
     *
     * @param graph1
     * @param graph2
     * @return the symmetric difference graph
     */
    public static AnnotationGraph symDifference(AnnotationGraph graph1, AnnotationGraph graph2) {
        Set<HashableVertex> vertexSet1 = getHashableVertexSet(graph1.vertexSet());
        Set<HashableVertex> vertexSet2 = getHashableVertexSet(graph2.vertexSet());
        Set<HashableEdge> edgeSet1 = getHashableEdgeSet(graph1.edgeSet());
        Set<HashableEdge> edgeSet2 = getHashableEdgeSet(graph2.edgeSet());
        vertexSet1.addAll(vertexSet2);
        Set<HashableEdge> symmetricDiff = new HashSet<>(edgeSet1);
        symmetricDiff.addAll(edgeSet2);
        Set<HashableEdge> tmp = new HashSet<>(edgeSet1);
        tmp.retainAll(edgeSet2);
        symmetricDiff.removeAll(tmp);
        return buildGraph(vertexSet1, symmetricDiff);
    }

    private static AnnotationGraph buildGraph(Set<HashableVertex> vertexSet, Set<HashableEdge> edgeSet) {
        Map<Integer, BrainAnnotation> vertexMap = new HashMap<>();
        AnnotationGraph newGraph = new AnnotationGraph();
        for (HashableVertex hv : vertexSet) {
            vertexMap.put(hv.getVertex().id(), hv.getVertex());
            newGraph.addVertex(hv.getVertex());
        }
        // Ensure that we form edges between the vertices contained in the current graph..
        for (HashableEdge he : edgeSet) {
            newGraph.addEdge(vertexMap.get(he.getEdge().getSource().id())
                    , vertexMap.get(he.getEdge().getTarget().id()));
        }
        return newGraph;
    }

    private static List<Set<HashableEdge>> getHashableEdgeSetList(Collection<AnnotationGraph> graphs) {
        List<Set<HashableEdge>> edgeSets = new ArrayList<>();
        for (AnnotationGraph graph : graphs) {
            edgeSets.add(getHashableEdgeSet(graph.edgeSet()));
        }
        return edgeSets;
    }

    private static Set<HashableEdge> getHashableEdgeSet(Set<AnnotationWeightedEdge> edges) {
        Set<HashableEdge> edgeSet = new HashSet<>();
        for (AnnotationWeightedEdge edge : edges) {
            edgeSet.add(new HashableEdge(edge));
        }
        return edgeSet;
    }

    private static Set<HashableVertex> getHashableVertexSet(Set<BrainAnnotation> vertices) {
        Set<HashableVertex> vertexSet = new HashSet<>();
        for (BrainAnnotation vertex : vertices) {
            vertexSet.add(new HashableVertex(vertex));
        }
        return vertexSet;
    }

    private static List<Set<HashableVertex>> getHashableVertexSetList(Collection<AnnotationGraph> graphs) {
        List<Set<HashableVertex>> vertexSets = new ArrayList<>();
        for (AnnotationGraph graph : graphs) {
            vertexSets.add(getHashableVertexSet(graph.vertexSet()));
        }
        return vertexSets;
    }

    static class HashableEdge {
        final AnnotationWeightedEdge edge;

        private HashableEdge(AnnotationWeightedEdge edge) {
            this.edge = edge;
        }

        public AnnotationWeightedEdge getEdge() {
            return this.edge;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HashableEdge)) {
                return false;
            }
            HashableEdge other = (HashableEdge) o;
            return this.toString().equals(other.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return edge.getSource().id() + "->" + edge.getTarget().id();
        }
    }

    static class HashableVertex {
        final BrainAnnotation vertex;

        private HashableVertex(BrainAnnotation vertex) {
            this.vertex = vertex;
        }

        public BrainAnnotation getVertex() {
            return this.vertex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HashableVertex)) {
                return false;
            }
            HashableVertex other = (HashableVertex) o;
            return getVertex().id() == other.getVertex().id();
        }

        @Override
        public int hashCode() {
            return getVertex().id();
        }

        @Override
        public String toString() {
            return getVertex().name();
        }

    }

}
