package sc.fiji.snt.viewer.geditor;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import sc.fiji.snt.analysis.graph.SNTGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class mxCircleLayoutSorted extends mxCircleLayout {
    SNTGraphAdapter<Object, DefaultWeightedEdge> adapter;
    SNTGraph<Object, DefaultWeightedEdge> sntGraph;

    public mxCircleLayoutSorted(mxGraph graph) throws IllegalArgumentException {
        super(graph);
        if (!(graph instanceof SNTGraphAdapter)) {
            throw new IllegalArgumentException("This action requires an SNTGraph");
        }
        @SuppressWarnings("unchecked")
        SNTGraphAdapter<Object, DefaultWeightedEdge> adapter = (SNTGraphAdapter<Object, DefaultWeightedEdge>) graph;
        this.adapter = adapter;
        this.sntGraph = adapter.getSourceGraph();
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
            Object[] vertexArray = graph.getChildVertices(parent);
            Object[] sortedVertexArray = Arrays.stream(vertexArray)
                    .map(v -> graph.getModel().getValue(v))
                    .sorted((o1, o2) -> {
                        double val1 = sntGraph.incomingEdgesOf(o1).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                        double val2 = sntGraph.incomingEdgesOf(o2).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                        // Reverse order is necessary for graph to orient properly (?)
                        return Double.compare(val2, val1);
                    }).map(v -> adapter.getVertexToCellMap().get(v)).toArray();

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
