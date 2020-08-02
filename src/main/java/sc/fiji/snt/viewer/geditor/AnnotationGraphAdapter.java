package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxConstants;

import org.scijava.util.ColorRGB;

import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.analysis.graph.AnnotationWeightedEdge;
import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;

public class AnnotationGraphAdapter extends SNTGraphAdapter<BrainAnnotation, AnnotationWeightedEdge> {

    private static final String DARK_GRAY = "#222222";
    private static final String LIGHT_GRAY = "#eeeeee";
    // default cell colors
    private final String defaultVertexStrokeColor = DARK_GRAY;
    private final String defaultVertexFillColor = LIGHT_GRAY;
    private final String defaultEdgeStrokeColor = DARK_GRAY;
    private final AnnotationGraph annotationGraph;

    public AnnotationGraphAdapter(final AnnotationGraph graph) {
        this(graph, LIGHT_GRAY);
    }

    protected AnnotationGraphAdapter(final AnnotationGraph graph, final String verticesColor) {
        super(graph);
        this.annotationGraph = graph;
        final String vColor = (verticesColor == null) ? LIGHT_GRAY : new ColorRGB(verticesColor).toHTMLColor();
        final Map<String, Object> edgeStyle = getStylesheet().getDefaultEdgeStyle();
        //edgeStyle.put(mxConstants.STYLE_ROUNDED, true);
        edgeStyle.put(mxConstants.STYLE_ENDARROW, mxConstants.ARROW_BLOCK);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        edgeStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        //edgeStyle.put(mxConstants.STYLE_STROKECOLOR, defaultEdgeStrokeColor);

        final Map<String, Object> vertexStyle = getStylesheet().getDefaultVertexStyle();
        vertexStyle.put(mxConstants.STYLE_AUTOSIZE, false);
        vertexStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        vertexStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        //vertexStyle.put(mxConstants.STYLE_STROKECOLOR, defaultVertexStrokeColor);
        //vertexStyle.put(mxConstants.STYLE_GRADIENTCOLOR, defaultVertexGradientColor);
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
        //vertexStyle.put(mxConstants.STYLE_FILLCOLOR, vColor);
        setAllowLoops(true);

    }

    public AnnotationGraph getAnnotationGraph() {
        return annotationGraph;
    }

    @Override
    public void setVertexColor(BrainAnnotation vertex, ColorRGB color) {
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

    @Override
    public String getToolTipForCell(Object cell) {
        mxCell mxc = (mxCell) cell;
        if (mxc.isVertex() && mxc.getValue() instanceof BrainAnnotation) {
            BrainAnnotation vertex = (BrainAnnotation) mxc.getValue();
            String tip = "<html>";
            tip += "<b>Region:</b> " + vertex.name();
            tip += "<br><b>Incoming edges:</b> ";
            if (annotationGraph.inDegreeOf(vertex) > 0) {
                double sum = annotationGraph.incomingEdgesOf(vertex).stream()
                        .mapToDouble(AnnotationWeightedEdge::getWeight)
                        .sum();
                int count = 1;
                for (AnnotationWeightedEdge edge : annotationGraph.incomingEdgesOf(vertex)) {
                    BrainAnnotation source = edge.getSource();
                    tip += "<br>" + count + ". " + source.name();
                    tip += ", weight=" + String.format("%,.2f", edge.getWeight());
                    tip += ", ratio=" + String.format("%.2f", edge.getWeight()/sum);
                    ++count;
                }
                tip += "<br>sum=" + String.format("%,.2f", sum);
            } else {
                tip += "<br>None";
            }
            tip += "<br><b>Outgoing edges:</b> ";
            if (annotationGraph.outDegreeOf(vertex) > 0) {
                double sum = annotationGraph.outgoingEdgesOf(vertex).stream()
                        .mapToDouble(AnnotationWeightedEdge::getWeight)
                        .sum();
                int count = 0;
                for (AnnotationWeightedEdge edge : annotationGraph.outgoingEdgesOf(vertex)) {
                    BrainAnnotation target = edge.getTarget();
                    tip += "<br>" + count + ". " + target.name();
                    tip += ", weight=" + String.format("%,.2f", edge.getWeight());
                    tip += ", ratio=" + String.format("%.2f", edge.getWeight()/sum);
                    ++count;
                }
                tip += "<br>sum=" + String.format("%,.2f", sum);
            } else {
                tip += "<br>None";
            }
            tip += "</html>";
            return tip;
        	// NB: Once cell is displayed/edited we no longer can cast cell's
        	// value to a BrainAnnotation object!?
        	// return ((BrainAnnotation) mxc.getValue()).name();
        }
        return getOriginalValueOfmxCell(mxc.getId());
    }

}
