package sc.fiji.snt.analysis.graph;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxICell;
import com.mxgraph.util.mxConstants;

import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;

import org.scijava.util.ColorRGB;

import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class AnnotationGraphAdapter extends JGraphXAdapter<BrainAnnotation, AnnotationWeightedEdge> {

    private static final String DARK_GRAY = "#222222";
    private static final String LIGHT_GRAY = "#eeeeee";

    protected AnnotationGraphAdapter(final Graph<BrainAnnotation, AnnotationWeightedEdge> graph) {
        this(graph, LIGHT_GRAY);
    }

    protected AnnotationGraphAdapter(final Graph<BrainAnnotation, AnnotationWeightedEdge> graph, final String verticesColor) {
        super(graph);
        final String vColor = (verticesColor == null) ? LIGHT_GRAY : new ColorRGB(verticesColor).toHTMLColor();
        final Map<String, Object> edgeStyle = getStylesheet().getDefaultEdgeStyle();
        //edgeStyle.put(mxConstants.STYLE_ROUNDED, true);
        edgeStyle.put(mxConstants.STYLE_ENDARROW, mxConstants.ARROW_BLOCK);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        edgeStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
        edgeStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        edgeStyle.put(mxConstants.STYLE_STROKECOLOR, DARK_GRAY);

        final Map<String, Object> vertexStyle = getStylesheet().getDefaultVertexStyle();
        vertexStyle.put(mxConstants.STYLE_SHAPE, mxConstants.SHAPE_HEXAGON);
        vertexStyle.put(mxConstants.STYLE_AUTOSIZE, true);
        vertexStyle.put(mxConstants.STYLE_LABEL_POSITION, mxConstants.ALIGN_CENTER);
        vertexStyle.put(mxConstants.STYLE_VERTICAL_LABEL_POSITION, mxConstants.ALIGN_MIDDLE);
//        vertexStyle.put(mxConstants.STYLE_FONTCOLOR, DARK_GRAY);
//        vertexStyle.put(mxConstants.STYLE_STROKECOLOR, DARK_GRAY);
        // Render all source nodes in a different color
        List<mxICell> sources = getVertexToCellMap()
                .entrySet()
                .stream()
                .filter(e -> graph.outDegreeOf(e.getKey()) > 0)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        Object[] sArray = sources.toArray();
        setCellStyles(mxConstants.STYLE_STROKECOLOR, "black", sArray);
        //setCellStyles(mxConstants.STYLE_FONTCOLOR, "black", sArray);
        setCellStyles(mxConstants.STYLE_FONTSTYLE, String.valueOf(mxConstants.FONT_BOLD), sources.toArray());
        setCellStyles(mxConstants.STYLE_GRADIENTCOLOR, "grey", sArray);

        vertexStyle.put(mxConstants.STYLE_FILLCOLOR, vColor);
        setLabelsVisible(true);
        setEnableVertexLabels(true);
        setEnableEdgeLabels(true);
        setKeepEdgesInBackground(true); // Edges will not appear above vertices
        setResetEdgesOnConnect(true);
        setResetViewOnRootChange(true);
        setEdgeLabelsMovable(true);

    }

    protected void setVertexColors(Map<BrainAnnotation, ColorRGB> vertexColorMap) {
        for (BrainAnnotation v : vertexColorMap.keySet()) {
            Object cell = getVertexToCellMap().get(v);
            Object[] modified = { cell };
            String newColor = vertexColorMap.get(v).toHTMLColor();
            if (newColor == null) {
                newColor = DARK_GRAY;
            }
            setCellStyles(mxConstants.STYLE_STROKECOLOR, newColor, modified);
        }
    }

    protected void setEdgeColors(Map<AnnotationWeightedEdge, ColorRGB> edgeColorMap) {
        for (AnnotationWeightedEdge e : edgeColorMap.keySet()) {
            Object cell = getEdgeToCellMap().get(e);
            Object[] modified = { cell };
            String newColor = edgeColorMap.get(e).toHTMLColor();
            if (newColor == null) {
                newColor = DARK_GRAY;
            }
            setCellStyles(mxConstants.STYLE_STROKECOLOR, newColor, modified);
        }
    }

    protected AnnotationGraph getAnnotationGraph() {
        AnnotationGraph aGraph = new AnnotationGraph();
        Set<BrainAnnotation> vMap = getVertexToCellMap().keySet();
        Set<AnnotationWeightedEdge> eMap = getEdgeToCellMap().keySet();
        for (BrainAnnotation v : vMap) {
            aGraph.addVertex(v);
        }
        for (AnnotationWeightedEdge e : eMap) {
            aGraph.addEdge(e.getSource(), e.getTarget(), e);
        }
        System.out.println(aGraph.edgeSet().size());
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
