package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.scijava.util.ColorRGB;

public abstract class ColorableGraph<Object, DefaultWeightedEdge> extends DefaultDirectedWeightedGraph<Object, DefaultWeightedEdge> {

    public ColorableGraph(Class<? extends DefaultWeightedEdge> edgeClass) {
        super(edgeClass);
    }

    public abstract void setVertexColor(Object vertex, ColorRGB color);

    public abstract void setEdgeColor(DefaultWeightedEdge edge, ColorRGB color);

    public abstract ColorRGB getVertexColor(Object vertex);

    public abstract ColorRGB getEdgeColor(DefaultWeightedEdge edge);

}
