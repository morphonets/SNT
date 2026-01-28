/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxICell;
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

    public void scaleEdgeWidths(double minWidth, double maxWidth, String scale) {
        Object[] cells = getEdgeToCellMap().values().toArray();
        if (cells.length == 0) {
            return;
        }
        double minWeight = Double.MAX_VALUE;
        double maxWeight = -Double.MAX_VALUE;
        SNTGraph<V, E> sntGraph = getSourceGraph();
        // First get the range of observed weights, negative weights are allowed.
        for (Object cell : cells) {
            mxICell mxc = (mxICell) cell;
            if (!mxc.isEdge()) continue;
            double weight = sntGraph.getEdgeWeight(getCellToEdgeMap().get(mxc));
            if (weight < minWeight) {minWeight = weight;}
            if (weight > maxWeight) {maxWeight = weight;}
        }
        if (minWeight == maxWeight) {
            minWeight = 0;
            maxWeight = Math.abs(maxWeight);
        }
        getModel().beginUpdate();
        if (scale.equals("linear")) {
            // Map the input interval onto a new interval [newMin, newMax]
            for (Object cell : cells) {
                mxICell mxc = (mxICell) cell;
                if (!mxc.isEdge()) continue;
                double weight = sntGraph.getEdgeWeight(getCellToEdgeMap().get(mxc));
                double scaledWeight = minWidth + ((maxWidth - minWidth) / (maxWeight - minWeight)) * (weight - minWeight);
                setCellStyles(mxConstants.STYLE_STROKEWIDTH, String.valueOf(scaledWeight), new Object[]{mxc});
            }
        }
        else if (scale.equals("log")) {
            // If the min edge weight is not 1, shift all values so that the minimum is 1.
            // This is necessary for correct log function behavior at the minimum value (i.e., log(1) == 0)
            double rightShift = 0;
            double leftShift = 0;
            if (minWeight < 1) {
                rightShift = 1 - minWeight;
            }
            else if (minWeight > 1) {
                leftShift = 1 - minWeight;
            }
            for (Object cell : cells) {
                mxICell mxc = (mxICell) cell;
                if (!mxc.isEdge()) continue;
                double weight = sntGraph.getEdgeWeight(
                        getCellToEdgeMap().get(mxc)
                ) + rightShift + leftShift;
                double k = maxWidth / Math.log(maxWeight + rightShift + leftShift);
                double scaledWeight = k * Math.log(weight) + minWidth;
                setCellStyles(mxConstants.STYLE_STROKEWIDTH, String.valueOf(scaledWeight), new Object[]{mxc});
            }
        }
        getModel().endUpdate();
    }

}
