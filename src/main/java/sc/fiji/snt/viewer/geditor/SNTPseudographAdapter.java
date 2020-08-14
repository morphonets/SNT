package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxConstants;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.graph.SNTPseudograph;
import sc.fiji.snt.annotation.BrainAnnotation;
import java.util.Map;

public class SNTPseudographAdapter<V, E extends DefaultWeightedEdge> extends SNTGraphAdapter<V, E> {

    private static final String DARK_GRAY = "#222222";
    private static final String LIGHT_GRAY = "#eeeeee";
    // default cell colors
    private final String defaultVertexStrokeColor = DARK_GRAY;
    private final String defaultVertexFillColor = LIGHT_GRAY;
    private final String defaultEdgeStrokeColor = DARK_GRAY;

    public SNTPseudographAdapter(SNTPseudograph<V, E> graph) {
        super(graph);
        final Map<String, Object> edgeStyle = getStylesheet().getDefaultEdgeStyle();
        edgeStyle.put(mxConstants.STYLE_ENDARROW, mxConstants.ARROW_BLOCK);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        edgeStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_BOTTOM);
        edgeStyle.put(mxConstants.STYLE_ENDSIZE, mxConstants.DEFAULT_FONTSIZE);
        //edgeStyle.put(mxConstants.STYLE_ROUNDED, true);

        final Map<String, Object> vertexStyle = getStylesheet().getDefaultVertexStyle();
        vertexStyle.put(mxConstants.STYLE_AUTOSIZE, true);
        vertexStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        vertexStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        vertexStyle.put(mxConstants.STYLE_ROUNDED, true);
        vertexStyle.put(mxConstants.STYLE_SHAPE, mxConstants.NONE);
        vertexStyle.put(mxConstants.STYLE_FONTCOLOR, "black");

        setCellColorsFromGraph();
        setAllowLoops(true);
    }

    @Override
    public void setVertexColor(V vertex, ColorRGB color) {
        Object cell = getVertexToCellMap().get(vertex);
        if (cell == null) {
            return;
        }
        String strokeColor;
        String fillColor;
        if (color == null) {
            strokeColor = defaultVertexStrokeColor;
            fillColor = defaultVertexFillColor;
        } else {
            strokeColor = color.toHTMLColor();
            fillColor = color.toHTMLColor();
        }
        Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
        setCellStyles(mxConstants.STYLE_FILLCOLOR, fillColor, modified);
        cGraph.setVertexColor(vertex, color);
    }

    @Override
    public void setEdgeColor(E edge, ColorRGB color) {
        Object cell = getEdgeToCellMap().get(edge);
        if (cell == null) {
            return;
        }
        String strokeColor;
        if (color == null) {
            strokeColor = defaultEdgeStrokeColor;
        } else {
            strokeColor = color.toHTMLColor();
        }
        Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
        cGraph.setEdgeColor(edge, color);
    }

    @Override
    public String convertValueToString(final Object cell) {
        final Object obj = ((mxCell)cell).getValue();
        if (obj == null) return ""; // required for Analyze>Complementary
        if (obj instanceof BrainAnnotation) {
            return ""+ ((BrainAnnotation)obj).acronym();
        }
        return obj.toString();
    }

}
