package sc.fiji.snt.analysis.graph;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxICell;
import com.mxgraph.util.mxConstants;

import org.jgrapht.ext.JGraphXAdapter;

import org.scijava.util.ColorRGB;

import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;

public class AnnotationGraphAdapter extends JGraphXAdapter<BrainAnnotation, AnnotationWeightedEdge> {

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
        if (!graph.getVertexColorRGBMap().isEmpty()) {
            for (Map.Entry<BrainAnnotation, ColorRGB> entry : graph.getVertexColorRGBMap().entrySet()) {
                setVertexColor(entry.getKey(), entry.getValue());
            }
        }
        if (!graph.getEdgeColorRGBMap().isEmpty()) {
            for (Map.Entry<AnnotationWeightedEdge, ColorRGB> entry : graph.getEdgeColorRGBMap().entrySet()) {
                setEdgeColor(entry.getKey(), entry.getValue());
            }
        }
        vertexStyle.put(mxConstants.STYLE_FILLCOLOR, vColor);
        setLabelsVisible(true);
        setEnableVertexLabels(true);
        setEnableEdgeLabels(true);
        setKeepEdgesInBackground(true); // Edges will not appear above vertices
        setResetEdgesOnConnect(true);
        setResetViewOnRootChange(true);
        setEdgeLabelsMovable(true);

    }

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

    public AnnotationGraph getSourceGraph() {
        AnnotationGraph aGraph = new AnnotationGraph();
        Map<BrainAnnotation, mxICell> vMap = getVertexToCellMap();
        Map<AnnotationWeightedEdge, mxICell> eMap = getEdgeToCellMap();
        for (Map.Entry<BrainAnnotation, mxICell> entry : vMap.entrySet()) {
            aGraph.addVertex(entry.getKey());
            aGraph.setVertexColor(entry.getKey(), new ColorRGB( (String) getCellStyle(entry.getValue()).get(mxConstants.STYLE_STROKECOLOR)));
        }
        for (Map.Entry<AnnotationWeightedEdge, mxICell> entry : eMap.entrySet()) {
            AnnotationWeightedEdge edge = entry.getKey();
            aGraph.addEdge(edge.getSource(), edge.getTarget(), edge);
            aGraph.setEdgeColor(edge, new ColorRGB( (String) getCellStyle(entry.getValue()).get(mxConstants.STYLE_STROKECOLOR)));
        }
        return aGraph;
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

    protected boolean isEdgeLabelsEnabled() {
        return !(boolean)stylesheet.getDefaultEdgeStyle().get(mxConstants.STYLE_NOLABEL);
    }

    protected boolean isVertexLabelsEnabled() {
        return !(boolean)stylesheet.getDefaultVertexStyle().get(mxConstants.STYLE_NOLABEL);
    }

    protected void setEnableEdgeLabels(final boolean enable) {
        getModel().beginUpdate();
        stylesheet.getDefaultEdgeStyle().put(mxConstants.STYLE_NOLABEL, !enable);
        getModel().endUpdate();
        refresh();
    }

    protected void setEnableVertexLabels(final boolean enable) {
        getModel().beginUpdate();
        stylesheet.getDefaultVertexStyle().put(mxConstants.STYLE_NOLABEL, !enable);
        getModel().endUpdate();
        refresh();
    }

}
