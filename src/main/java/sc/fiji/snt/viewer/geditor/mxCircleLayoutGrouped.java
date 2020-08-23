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
import java.util.List;

public class mxCircleLayoutGrouped extends mxCircleLayout {
    SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge> adapter;
    SNTGraph<BrainAnnotation, DefaultWeightedEdge> sntGraph;
    List<Map<BrainAnnotation, List<BrainAnnotation>>> groups;
    int vertexCount = 0;
    boolean isColorCode = false;
    ColorTable colorTable;
    int midLevel;
    int topLevel;

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
                sortedAnnotationList.addAll(anList);
                this.vertexCount += anList.size();

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
            double r = Math.max(vertexCount * max / Math.PI, radius);
            // Moves the circle to the specified origin
            if (moveCircle) {
                left = x0;
                top = y0;
            }
            //noinspection ConstantConditions
            circle(r, left, top);

            if (isColorCode) {
                colorCode();
            }
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



}
