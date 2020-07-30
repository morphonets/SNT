package sc.fiji.snt.analysis.graph;

import java.util.*;

public class AnnotationGraphUtils {

    public static AnnotationGraph union(Collection<AnnotationGraph> graphs) {
        // get union of the edge sets
        List<Set<HashableEdge>> edgeSets = getHashableEdgeSetList(graphs);
        Set<HashableEdge> result = edgeSets.get(0);
        for (Set<HashableEdge> edgeSet : edgeSets) {
            result.addAll(edgeSet);
        }
        return buildGraph(result);
    }

    public static AnnotationGraph intersection(Collection<AnnotationGraph> graphs) {
        // get the intersection of the edge sets
        List<Set<HashableEdge>> edgeSets = getHashableEdgeSetList(graphs);
        Set<HashableEdge> result = edgeSets.get(0);
        for (Set<HashableEdge> edgeSet : edgeSets) {
            result.retainAll(edgeSet);
        }
        return buildGraph(result);
    }

    public static AnnotationGraph difference(AnnotationGraph graph1, AnnotationGraph graph2) {
        Set<HashableEdge> result = getHashableEdgeSet(graph1.edgeSet());
        result.removeAll(getHashableEdgeSet(graph2.edgeSet()));
        return buildGraph(result);
    }

    public static AnnotationGraph symDifference(AnnotationGraph graph1, AnnotationGraph graph2) {
        Set<HashableEdge> edgeSet1 = getHashableEdgeSet(graph1.edgeSet());
        Set<HashableEdge> edgeSet2 = getHashableEdgeSet(graph2.edgeSet());
        Set<HashableEdge> symmetricDiff = new HashSet<>(edgeSet1);
        symmetricDiff.addAll(edgeSet2);
        Set<HashableEdge> tmp = new HashSet<>(edgeSet1);
        tmp.retainAll(edgeSet2);
        symmetricDiff.removeAll(tmp);
        return buildGraph(symmetricDiff);
    }

    private static AnnotationGraph buildGraph(Set<HashableEdge> edgeSet) {
        AnnotationGraph newGraph = new AnnotationGraph();
        for (HashableEdge he : edgeSet) {
            newGraph.addVertex(he.getEdge().getSource());
            newGraph.addVertex(he.getEdge().getTarget());
            newGraph.addEdge(he.getEdge().getSource(), he.getEdge().getTarget());
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

    static class HashableEdge {
        AnnotationWeightedEdge edge;

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

}
