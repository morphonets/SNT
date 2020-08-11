package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxConstants;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.graph.SNTGraph;

import java.util.HashMap;
import java.util.Map;

public class SNTGraphAdapter<V, E extends DefaultWeightedEdge> extends JGraphXAdapter<V, E> {
    private HashMap<String, String> mxCellIDsToOriginalValuesMap;
    protected final SNTGraph<V, E> cGraph;

    public SNTGraphAdapter(SNTGraph<V, E> graph) {
        super(graph);
        this.cGraph = graph;
        setAutoSizeCells(true);
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
        cGraph.setVertexColor(vertex, color);
    }

    public void setEdgeColor(E edge, ColorRGB color) {
        java.lang.Object cell = getEdgeToCellMap().get(edge);
        if (cell == null || color == null) {
            return;
        }
        String strokeColor = color.toHTMLColor();
        java.lang.Object[] modified = { cell };
        setCellStyles(mxConstants.STYLE_STROKECOLOR, strokeColor, modified);
        cGraph.setEdgeColor(edge, color);
    }

    public void setCellColorsFromGraph() {
        if (!cGraph.getVertexColorRGBMap().isEmpty()) {
            for (Map.Entry<V, ColorRGB> entry : cGraph.getVertexColorRGBMap().entrySet()) {
                setVertexColor(entry.getKey(), entry.getValue());
            }
        }
        if (!cGraph.getEdgeColorRGBMap().isEmpty()) {
            for (Map.Entry<E, ColorRGB> entry : cGraph.getEdgeColorRGBMap().entrySet()) {
                setEdgeColor(entry.getKey(), entry.getValue());
            }
        }
    }

    public SNTGraph<V, E> getSourceGraph() {
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
