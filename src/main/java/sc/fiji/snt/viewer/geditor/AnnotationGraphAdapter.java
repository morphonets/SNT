/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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
        this(graph, null);
    }

    protected AnnotationGraphAdapter(final AnnotationGraph graph, final String verticesColor) {

        super(graph);
        this.annotationGraph = graph;

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
        vertexStyle.put(mxConstants.STYLE_SHAPE, mxConstants.SHAPE_RECTANGLE);
        vertexStyle.put(mxConstants.STYLE_FONTCOLOR, "black");

        if (verticesColor != null)
        	vertexStyle.put(mxConstants.STYLE_FILLCOLOR, new ColorRGB(verticesColor).toHTMLColor());

        setCellColorsFromGraph();
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
