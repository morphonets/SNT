package sc.fiji.snt.viewer;

import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxConstants;

import org.scijava.util.ColorRGB;

import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.analysis.graph.AnnotationWeightedEdge;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.viewer.SNTGraphAdapter;

import java.util.*;

public class AnnotationGraphAdapter extends SNTGraphAdapter<BrainAnnotation, AnnotationWeightedEdge> {

    private static final String DARK_GRAY = "#222222";
    private static final String LIGHT_GRAY = "#eeeeee";
    // default cell colors
    private String defaultVertexStrokeColor = DARK_GRAY;
    private String defaultVertexGradientColor = LIGHT_GRAY;
    private String defaultEdgeStrokeColor = DARK_GRAY;

    protected AnnotationGraphAdapter(final AnnotationGraph graph) {
        this(graph, LIGHT_GRAY);
    }

    protected AnnotationGraphAdapter(final AnnotationGraph graph, final String verticesColor) {
        super(graph);
        final String vColor = (verticesColor == null) ? LIGHT_GRAY : new ColorRGB(verticesColor).toHTMLColor();
        final Map<String, Object> edgeStyle = getStylesheet().getDefaultEdgeStyle();
        //edgeStyle.put(mxConstants.STYLE_ROUNDED, true);
        edgeStyle.put(mxConstants.STYLE_ENDARROW, mxConstants.ARROW_BLOCK);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        edgeStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        edgeStyle.put(mxConstants.STYLE_STROKECOLOR, defaultEdgeStrokeColor);

        final Map<String, Object> vertexStyle = getStylesheet().getDefaultVertexStyle();
        vertexStyle.put(mxConstants.STYLE_AUTOSIZE, true);
        vertexStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        vertexStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        vertexStyle.put(mxConstants.STYLE_STROKECOLOR, defaultVertexStrokeColor);
        vertexStyle.put(mxConstants.STYLE_GRADIENTCOLOR, defaultVertexGradientColor);
        // Render all source nodes in a different color
//        List<mxICell> sources = getVertexToCellMap()
//                .entrySet()
//                .stream()
//                .filter(e -> graph.outDegreeOf(e.getKey()) > 0)
//                .map(Map.Entry::getValue)
//                .collect(Collectors.toList());
//        Object[] sArray = sources.toArray();
//        setCellStyles(mxConstants.STYLE_STROKECOLOR, "black", sArray);
//        setCellStyles(mxConstants.STYLE_FONTSTYLE, String.valueOf(mxConstants.FONT_BOLD), sources.toArray());
//        setCellStyles(mxConstants.STYLE_GRADIENTCOLOR, "black", sArray);
        setCellColorsFromGraph();
        vertexStyle.put(mxConstants.STYLE_FILLCOLOR, vColor);

    }

    @Override
    public void setVertexColor(BrainAnnotation vertex, ColorRGB color) {
        Object cell = getVertexToCellMap().get(vertex);
        if (cell == null) {
            return;
        }
        String strokeColor;
        String gradientColor;
        if (color == null) {
            strokeColor = defaultVertexStrokeColor;
            gradientColor = defaultVertexGradientColor;
        } else {
            strokeColor = color.toHTMLColor();
            gradientColor = color.toHTMLColor();
        }
        Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
        setCellStyles(mxConstants.STYLE_GRADIENTCOLOR, gradientColor, modified);
    }

    @Override
    public void setEdgeColor(AnnotationWeightedEdge edge, ColorRGB color) {
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
    }

    @Override
    public String convertValueToString(final Object cell) {
        final Object obj = ((mxCell)cell).getValue();
        if (obj instanceof BrainAnnotation) {
            return ""+ ((BrainAnnotation)obj).acronym();
        }
        if (obj instanceof AnnotationWeightedEdge) {
            return obj.toString();
        }
        return cell.toString();
    }

    @Override
    public String getToolTipForCell(Object cell) {
        mxCell mxc = (mxCell) cell;
        if (!mxc.isVertex()) {
            return null;
        }
        BrainAnnotation obj = (BrainAnnotation) mxc.getValue();
        return obj.name();

    }

}
