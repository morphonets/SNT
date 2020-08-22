package sc.fiji.snt.viewer.geditor;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import sc.fiji.snt.analysis.graph.AnnotationWeightedEdge;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;

public class mxCircleLayoutSorted<V, E extends DefaultWeightedEdge> extends mxCircleLayout {
    SNTGraphAdapter<V, E> adapter;
    SNTGraph<V, E> sntGraph;
    String criteria;

    public mxCircleLayoutSorted(mxGraph graph, String criteria) throws IllegalArgumentException {
        super(graph);
        if (!(graph instanceof SNTGraphAdapter)) {
            throw new IllegalArgumentException("This action requires an SNTGraph");
        }
        @SuppressWarnings("unchecked")
        SNTGraphAdapter<V, E> adapter = (SNTGraphAdapter<V,E>) graph;
        this.adapter = adapter;
        this.sntGraph = adapter.getSourceGraph();
        this.criteria = criteria;
    }

    protected Object[] sortVertices(String criteria) {
        Object[] sortedVertexArray = null;
        if (criteria.equals("incomingWeight")) {
            Object[] vertexArray = graph.getChildVertices(parent);
             sortedVertexArray = Arrays.stream(vertexArray)
                    .map(v -> (V) graph.getModel().getValue(v))
                    .sorted((o1, o2) -> {
                        double val1 = sntGraph.incomingEdgesOf(o1).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                        double val2 = sntGraph.incomingEdgesOf(o2).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                        // Reverse order is necessary for graph to orient properly (?)
                        return Double.compare(val2, val1);
                    }).map(v -> adapter.getVertexToCellMap().get(v)).toArray();

        } else if (criteria.equals("compartment")) {
            if (!(sntGraph.vertexSet().iterator().next() instanceof BrainAnnotation)) {
                throw new IllegalArgumentException("AnnotationGraph required.");
            }
            SNTGraph<BrainAnnotation, AnnotationWeightedEdge> aGraph = (SNTGraph<BrainAnnotation, AnnotationWeightedEdge>) sntGraph;
            Map<BrainAnnotation, List<BrainAnnotation>> parentMap = new HashMap<>();
            Set<BrainAnnotation> unaccountedVertices = new HashSet<>(); // not currently used
            // Group lowest level annotations (the graph vertices) by shared ancestor
            for (BrainAnnotation v : aGraph.vertexSet()) {
                int vLevel = v.getOntologyDepth();
                BrainAnnotation parentV;
                // hard-coded ancestor
                if (vLevel > 6) {
                    parentV = v.getAncestor(6 - vLevel);
                } else {
                    parentV = v;
                }
                if (!parentMap.containsKey(parentV)) {
                    parentMap.put(parentV, new ArrayList<>());
                }
                parentMap.get(parentV).add(v);
            }
            // Now, group the ancestor keys of the map we created above by shared ancestor.
            // This will create a 2-level hierarchy where one top level BrainAnnotation key points to a map
            // which contains the 2nd level of BrainAnnotation keys, each of which points to a list of the lowest level
            // compartments in that region.
            // For example, Cerebral Cortex -> Somatomotor areas -> MOs, MOp
            Map<BrainAnnotation, Map<BrainAnnotation, List<BrainAnnotation>>> parentMap2 = new HashMap<>();
            for (BrainAnnotation key : parentMap.keySet()) {
                if (key == null ) continue;
                int keyLevel = key.getOntologyDepth();
                BrainAnnotation parentKey;
                // hard-coded top-level ancestor
                if (keyLevel > 3) {
                    parentKey = key.getAncestor(3 - keyLevel);
                } else {
                    parentKey = key;
                }
                if (!parentMap2.containsKey(parentKey)) {
                    parentMap2.put(parentKey, new HashMap<>());
                }
                parentMap2.get(parentKey).put(key, parentMap.get(key));
            }
            System.out.println(parentMap2);
            List<BrainAnnotation> sortedAnnotationList = new ArrayList<>();
            for (Map<BrainAnnotation, List<BrainAnnotation>> map : parentMap2.values()) {
                for (List<BrainAnnotation> anList : map.values()) {
                    sortedAnnotationList.addAll(anList);
                }
            }
            sortedVertexArray = sortedAnnotationList.stream().map(v -> adapter.getVertexToCellMap().get(v)).toArray();
        }
        return sortedVertexArray;
    }

    @Override
    public void execute(Object parent) {
        mxIGraphModel model = graph.getModel();

        // Moves the vertices to build a circle. Makes sure the
        // radius is large enough for the vertices to not
        // overlap
        model.beginUpdate();
        try {
            // Gets all vertices inside the parent and finds
            // the maximum dimension of the largest vertex
            double max = 0;
            Double top = null;
            Double left = null;

            Object[] sortedVertexArray = sortVertices(criteria);
            List<Object> vertices = new ArrayList<>();

            for (Object cell : sortedVertexArray) {
                if (!isVertexIgnored(cell)) {
                    vertices.add(cell);
                    mxRectangle bounds = getVertexBounds(cell);
                    if (top == null) {
                        top = bounds.getY();
                    } else {
                        top = Math.min(top, bounds.getY());
                    }
                    if (left == null) {
                        left = bounds.getX();
                    } else {
                        left = Math.min(left, bounds.getX());
                    }
                    max = Math.max(max, Math.max(bounds.getWidth(), bounds
                            .getHeight()));
                } else if (!isEdgeIgnored(cell)) {
                    if (isResetEdges()) {
                        graph.resetEdge(cell);
                    }
                    if (isDisableEdgeStyle()) {
                        setEdgeStyleEnabled(cell, false);
                    }
                }
            }
            int vertexCount = vertices.size();
            double r = Math.max(vertexCount * max / Math.PI, radius);
            // Moves the circle to the specified origin
            if (moveCircle) {
                left = x0;
                top = y0;
            }
            //noinspection ConstantConditions
            circle(vertices.toArray(), r, left, top);
        } finally {
            model.endUpdate();
        }
    }

    @Override
    public void circle(Object[] vertices, double r, double left, double top) {
        super.circle(vertices, r, left, top);
    }
}
