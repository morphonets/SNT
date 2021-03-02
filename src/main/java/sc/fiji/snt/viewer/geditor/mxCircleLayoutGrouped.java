/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxGraph;
import net.imglib2.display.ColorTable;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.annotation.BrainAnnotation;

import java.util.*;
import java.util.stream.Collectors;

public class mxCircleLayoutGrouped extends mxCircleLayout {
    SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge> adapter;
    SNTGraph<BrainAnnotation, DefaultWeightedEdge> sntGraph;
    List<Map<BrainAnnotation, List<BrainAnnotation>>> groups;
    int vertexCount;
    boolean isColorCode = false;
    ColorTable colorTable;
    int midLevel;
    int topLevel;
    boolean isSortMidLevel = false;
    boolean isCenterSource = false;
    BrainAnnotation source;
    private double centerX;
    private double centerY;

    public mxCircleLayoutGrouped(mxGraph graph, int midLevel, int topLevel) throws IllegalArgumentException {
        super(graph);
        if (!(graph instanceof SNTGraphAdapter)) {
            throw new IllegalArgumentException("This action requires an SNTGraph");
        }
        if (!(((SNTGraphAdapter<?, ?>) graph).getSourceGraph().vertexSet().iterator().next() instanceof BrainAnnotation)) {
            throw new IllegalArgumentException("This action requires BrainAnnotation vertex objects.");
        }
        @SuppressWarnings("unchecked")
        SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge> adapter = (SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge>) graph;
        this.adapter = adapter;
        this.sntGraph = adapter.getSourceGraph();
        this.groups = new ArrayList<>();
        this.midLevel = midLevel;
        this.topLevel = topLevel;
    }

    protected Object[] sortVertices() {
        Map<BrainAnnotation, List<BrainAnnotation>> parentMap = new HashMap<>();
        //List<BrainAnnotation> unaccountedVertices = new ArrayList<>();
        // Group lowest level annotations (the graph vertices) by shared ancestor
        for (BrainAnnotation v : sntGraph.vertexSet()) {
            int vLevel = v.getOntologyDepth();
            BrainAnnotation parentV;
            // hard-coded ancestor
            if (vLevel > midLevel) {
                parentV = v.getAncestor(midLevel - vLevel);
            } else {
                parentV = v;
            }
            if (!parentMap.containsKey(parentV)) {
                parentMap.put(parentV, new ArrayList<>());
            }
            parentMap.get(parentV).add(v);
        }
        // Now, group the ancestor keys of the map we created above by shared ancestor.
        // This will create a 2-level hierarchy where one top level BrainAnnotation key points to a map
        // which contains the 2nd level of BrainAnnotation keys, each of which points to a list of the lowest level
        // compartments in that region.
        // For example, Cerebral Cortex -> Somatomotor areas -> MOs, MOp
        Map<BrainAnnotation, Map<BrainAnnotation, List<BrainAnnotation>>> parentMap2 = new HashMap<>();
        for (BrainAnnotation key : parentMap.keySet()) {
            if (key == null) continue;
            int keyLevel = key.getOntologyDepth();
            BrainAnnotation parentKey;
            // hard-coded top-level ancestor
            if (keyLevel > topLevel) {
                parentKey = key.getAncestor(topLevel - keyLevel);
            } else {
                parentKey = key;
            }
            if (!parentMap2.containsKey(parentKey)) {
                parentMap2.put(parentKey, new HashMap<>());
            }
            parentMap2.get(parentKey).put(key, parentMap.get(key));
        }

        List<BrainAnnotation> sortedAnnotationList = new ArrayList<>();
        for (Map.Entry<BrainAnnotation, Map<BrainAnnotation, List<BrainAnnotation>>> entry : parentMap2.entrySet()) {
            System.out.println(entry);
            // Group by top-level compartment
            groups.add(entry.getValue());
            for (List<BrainAnnotation> anList : entry.getValue().values()) {
                List<BrainAnnotation> sortedAnList;
                if (isSortMidLevel) {
                    sortedAnList = anList.stream()
                            .sorted((o1, o2) -> {
                                double val1 = sntGraph.incomingEdgesOf(o1).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                                double val2 = sntGraph.incomingEdgesOf(o2).stream().mapToDouble(e -> sntGraph.getEdgeWeight(e)).sum();
                                return Double.compare(val1, val2);
                            }).collect(Collectors.toList());
                } else {
                    sortedAnList = anList;
                }
                sortedAnnotationList.addAll(sortedAnList);
                this.vertexCount += sortedAnList.size();
            }
        }
        vertexCount += groups.size();

        return sortedAnnotationList.stream().map(v -> adapter.getVertexToCellMap().get(v)).toArray();
    }

    @Override
    public void execute(Object parent) {
        mxIGraphModel model = graph.getModel();

        // Moves the vertices to build a circle. Makes sure the
        // radius is large enough for the vertices to not
        // overlap
        model.beginUpdate();
        try {
            // Gets all vertices inside the parent and finds
            // the maximum dimension of the largest vertex
            double max = 0;
            Double top = null;
            Double left = null;

            Object[] sortedVertexArray = sortVertices();

            for (Object cell : sortedVertexArray) {
                mxRectangle bounds = getVertexBounds(cell);
                if (top == null) {
                    top = bounds.getY();
                } else {
                    top = Math.min(top, bounds.getY());
                }
                if (left == null) {
                    left = bounds.getX();
                } else {
                    left = Math.min(left, bounds.getX());
                }
                max = Math.max(max, Math.max(bounds.getWidth(), bounds
                        .getHeight()));
            }
            if (isCenterSource) {
                List<BrainAnnotation> removeFrom = accountForCenterSource();
                if (removeFrom != null)
                    removeFrom.remove(source);
            }
            double r;
            if (radius <= 0) {
                r = vertexCount * max / Math.PI;
            } else {
                r = radius;
            }
            // Moves the circle to the specified origin
            if (moveCircle) {
                left = x0;
                top = y0;
            }
            this.centerX = left + r;
            this.centerY = top + r;
            circle(r, left, top);
            if (isColorCode) {
                colorCode();
            }
            if (isCenterSource) {
                centerSource();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            model.endUpdate();
        }
    }

    public void circle(double r, double left, double top)
    {
        double phi = 2 * Math.PI / vertexCount;
        int i = 0;
        for (Map<BrainAnnotation, List<BrainAnnotation>> group : groups) {
            for (List<BrainAnnotation> anList : group.values()) {
                for (BrainAnnotation an : anList) {
                    Object cell = adapter.getVertexToCellMap().get(an);
                    if (isVertexMovable(cell)) {
                        setVertexLocation(cell,
                                left + r + r * Math.sin(i * phi), top + r + r
                                        * Math.cos(i * phi));
                    }
                    ++i;
                }
            }
            // bump angle on group change
            ++i;
        }
    }

    private void centerSource() {
        Object sourceCell = adapter.getVertexToCellMap().get(source);
        setVertexLocation(sourceCell, getCenterX(), getCenterY());
    }

    private List<BrainAnnotation> accountForCenterSource() {
        for (Map<BrainAnnotation, List<BrainAnnotation>> group : groups) {
            for (List<BrainAnnotation> anList : group.values()) {
                for (BrainAnnotation an : anList) {
                    if (sntGraph.outDegreeOf(an) > 0) {
                        source = an;
                        vertexCount -= 1;
                        return anList;
                    }
                }
            }
        }
        return null;
    }

    private void colorCode() {
        int min = 0;
        int max = groups.size()-1;
        int minColor = 0;
        int maxColor = colorTable.getLength() - 1;
        for (int i = 0; i < groups.size(); i++) {
            int idx = minColor + ( (maxColor - minColor) / (max - min) ) * (i - min);
            ColorRGB c = new ColorRGB(colorTable.get(ColorTable.RED, idx), colorTable.get(
                    ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
            Map<BrainAnnotation, List<BrainAnnotation>> group = groups.get(i);
            for (List<BrainAnnotation> anList : group.values()) {
                for (BrainAnnotation an : anList) {
                    adapter.setVertexColor(an, c);
                    Set<DefaultWeightedEdge> inEdges = sntGraph.incomingEdgesOf(an);
                    for (DefaultWeightedEdge edge : inEdges) {
                        adapter.setEdgeColor(edge, c);
                    }
                }
            }
        }
    }

    public void setColorCode(boolean colorCode) {
        this.isColorCode = colorCode;
    }

    public void setColorTable(ColorTable colorTable) {
        this.colorTable = colorTable;
    }

    public void setSortMidLevel(boolean sortMidLevel) {
        this.isSortMidLevel = sortMidLevel;
    }

    public void setCenterSource(boolean centerSource) {
        this.isCenterSource = centerSource;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

}
