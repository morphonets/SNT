package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxConstants;
import org.jgrapht.ext.JGraphXAdapter;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.HashMap;
import java.util.Map;

public class SNTGraphAdapter<V, DefaultWeightedEdge> extends JGraphXAdapter<V, DefaultWeightedEdge> {
    private final SNTGraph<V, DefaultWeightedEdge> cGraph;
    private HashMap<String, String> mxCellIDsToOriginalValuesMap;

    protected SNTGraphAdapter(SNTGraph<V, DefaultWeightedEdge> graph) {
        super(graph);
        this.cGraph = graph;
//        setAutoOrigin(true);
        setAutoSizeCells(false);
        setLabelsVisible(true);
//        setEnableVertexLabels(true);
//        setEnableEdgeLabels(true);
        setKeepEdgesInBackground(true); // Edges will not appear above vertices
        setVertexLabelsMovable(false);
        setEdgeLabelsMovable(true);
        setDisconnectOnMove(false);
        setResetEdgesOnMove(true);
        setResetEdgesOnResize(true);

		// Change the scale and translation after graph has changed
        setResetViewOnRootChange(true);

        // restrict editability of graph by default
        setCellsDisconnectable(false);
        setDropEnabled(false);
        setSplitEnabled(true);

    }

    @Override
    public void cellLabelChanged( final Object cell, final Object value, final boolean autoSize )
	{
		if (mxCellIDsToOriginalValuesMap == null)
			mxCellIDsToOriginalValuesMap = new HashMap<>();
		final mxCell mxc = (mxCell) cell;
		if (!mxCellIDsToOriginalValuesMap.containsKey(mxc.getId()))
			mxCellIDsToOriginalValuesMap.put(mxc.getId(), mxc.getValue().toString());
		super.cellLabelChanged(cell, value, autoSize);
	}

    public void setVertexColor(V vertex, ColorRGB color) {
        java.lang.Object cell = getVertexToCellMap().get(vertex);
        if (cell == null || color == null) {
            return;
        }
        String strokeColor = color.toHTMLColor();
        String fillColor = color.toHTMLColor();
        java.lang.Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
        setCellStyles(mxConstants.STYLE_FILLCOLOR, fillColor, modified);
    }

    public void setEdgeColor(DefaultWeightedEdge edge, ColorRGB color) {
        java.lang.Object cell = getEdgeToCellMap().get(edge);
        if (cell == null || color == null) {
            return;
        }
        String strokeColor = color.toHTMLColor();
        java.lang.Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
    }

    protected void setCellColorsFromGraph() {
        if (!cGraph.getVertexColorRGBMap().isEmpty()) {
            for (Map.Entry<V, ColorRGB> entry : cGraph.getVertexColorRGBMap().entrySet()) {
                setVertexColor(entry.getKey(), entry.getValue());
            }
        }
        if (!cGraph.getEdgeColorRGBMap().isEmpty()) {
            for (Map.Entry<DefaultWeightedEdge, ColorRGB> entry : cGraph.getEdgeColorRGBMap().entrySet()) {
                setEdgeColor(entry.getKey(), entry.getValue());
            }
        }
    }

    public SNTGraph<V, DefaultWeightedEdge> getSourceGraph() {
        return cGraph;
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

    protected String getOriginalValueOfmxCell(final String mxCellId) {
    	return (mxCellIDsToOriginalValuesMap == null) ? null : mxCellIDsToOriginalValuesMap.get(mxCellId);
    }

}
