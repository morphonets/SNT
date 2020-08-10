package sc.fiji.snt.analysis.graph;

import org.jgrapht.GraphType;
import org.jgrapht.graph.AbstractBaseGraph;

import org.scijava.util.ColorRGB;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public abstract class SNTGraph<V, DefaultWeightedEdge> extends AbstractBaseGraph<V, DefaultWeightedEdge> {

	private static final long serialVersionUID = 8458292348918037500L;

	private final Map<V, ColorRGB> vertexColorRGBMap;
    private final Map<DefaultWeightedEdge, ColorRGB> edgeColorRGBMap;
    private final Map<V, Double> vertexValueMap;

    protected SNTGraph(Supplier<V> vertexSupplier, Supplier<DefaultWeightedEdge> edgeSupplier, GraphType type) {
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

    public void setEdgeColor(DefaultWeightedEdge edge, ColorRGB color) {
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

    public ColorRGB getEdgeColor(DefaultWeightedEdge edge) {
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

    public Map<DefaultWeightedEdge, ColorRGB> getEdgeColorRGBMap() {
        return edgeColorRGBMap;
    }

    public Map<V, Double> getVertexValueMap() {
        return vertexValueMap;
    }
}
