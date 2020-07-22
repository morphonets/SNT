package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.scijava.util.ColorRGB;

import java.util.HashMap;
import java.util.Map;

public abstract class ColorableGraph<Object, DefaultWeightedEdge> extends DefaultDirectedWeightedGraph<Object, DefaultWeightedEdge> {
    protected final Map<Object, ColorRGB> vertexColorRGBMap;
    protected final Map<DefaultWeightedEdge, ColorRGB> edgeColorRGBMap;

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

}
