package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.scijava.util.ColorRGB;

import java.util.HashMap;
import java.util.Map;

public class ColorableGraph<Object, DefaultWeightedEdge> extends DefaultDirectedWeightedGraph<Object, DefaultWeightedEdge> {
    private final Map<Object, ColorRGB> vertexColorRGBMap;
    private final Map<DefaultWeightedEdge, ColorRGB> edgeColorRGBMap;

    public ColorableGraph(Class<? extends DefaultWeightedEdge> edgeClass) {
        super(edgeClass);
        vertexColorRGBMap = new HashMap<>();
        edgeColorRGBMap = new HashMap<>();
    }

    public void setVertexColor(Object vertex, ColorRGB color) {
        if (containsVertex(vertex)) {
            vertexColorRGBMap.put(vertex, color);
        }
    }

    public void setEdgeColor(DefaultWeightedEdge edge, ColorRGB color) {
        if (containsEdge(edge)) {
            edgeColorRGBMap.put(edge, color);
        }
    }

    public ColorRGB getVertexColor(Object vertex) {
        if (containsVertex(vertex) && vertexColorRGBMap.containsKey(vertex)) {
            return vertexColorRGBMap.get(vertex);
        }
        return null;
    }

    public ColorRGB getEdgeColor(DefaultWeightedEdge edge) {
        if (containsEdge(edge) && edgeColorRGBMap.containsKey(edge)) {
            return edgeColorRGBMap.get(edge);
        }
        return null;
    }

    protected Map<Object, ColorRGB> getVertexColorRGBMap() {
        return vertexColorRGBMap;
    }

    protected Map<DefaultWeightedEdge, ColorRGB> getEdgeColorRGBMap() {
        return edgeColorRGBMap;
    }

}
