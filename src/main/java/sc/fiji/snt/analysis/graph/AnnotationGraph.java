package sc.fiji.snt.analysis.graph;

import org.scijava.util.ColorRGB;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.PointInImage;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class AnnotationGraph extends ColorableGraph<BrainAnnotation, AnnotationWeightedEdge> {

    private final Map<BrainAnnotation, ColorRGB> vertexColorRGBMap = new HashMap<>();
    private final Map<AnnotationWeightedEdge, ColorRGB> edgeColorRGBMap = new HashMap<>();

    protected AnnotationGraph() {
        super(AnnotationWeightedEdge.class);
    }

    public AnnotationGraph(final Collection<Tree> trees) {
        this(trees, 1, AllenUtils.getHighestOntologyDepth());
    }

    public AnnotationGraph(final Collection<Tree> trees, int minTipCount, int maxOntologyDepth) {
        super(AnnotationWeightedEdge.class);
        if (maxOntologyDepth < 0) {
            maxOntologyDepth = 0;
        }
        init(trees, minTipCount, maxOntologyDepth);
    }

    private void init(final Collection<Tree> trees, int minTipCount, int maxOntologyDepth) {
        final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
        for (final Tree tree : trees) {
            BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
            if (rootAnnotation == null) {
                continue;
            }
            rootAnnotation = getLevelAncestor(rootAnnotation, maxOntologyDepth);
            if (!annotationPool.containsKey(rootAnnotation.id())) {
                annotationPool.put(rootAnnotation.id(), rootAnnotation);
                addVertex(rootAnnotation);
            }
            rootAnnotation = annotationPool.get(rootAnnotation.id());
            if (!containsVertex(rootAnnotation)) {
                addVertex(rootAnnotation);
            }
            final Set<PointInImage> tips = new TreeAnalyzer(tree).getTips();
            Map<Integer, Integer> countMap = new HashMap<>();
            for (final PointInImage tip : tips) {
                BrainAnnotation tipAnnotation = tip.getAnnotation();
                if (tipAnnotation == null) {
                    continue;
                }
                tipAnnotation = getLevelAncestor(tipAnnotation, maxOntologyDepth);
                if (!annotationPool.containsKey(tipAnnotation.id())) {
                    annotationPool.put(tipAnnotation.id(), tipAnnotation);
                }
                if (!countMap.containsKey(tipAnnotation.id())) {
                    countMap.put(tipAnnotation.id(), 0);
                }
                countMap.put(tipAnnotation.id(), countMap.get(tipAnnotation.id()) + 1);
            }
            List<Integer> filteredTips = countMap
                    .entrySet()
                    .stream()
                    .filter(e -> e.getValue() >= minTipCount)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            for (Integer areaId : filteredTips) {
                BrainAnnotation fTip = annotationPool.get(areaId);
                if (!containsVertex(fTip)) {
                    addVertex(fTip);
                }
                AnnotationWeightedEdge edge = getEdge(rootAnnotation, fTip);
                if (edge == null) {
                    edge = new AnnotationWeightedEdge();
                    addEdge(rootAnnotation, fTip, edge);
                    setEdgeWeight(edge, 0);
                }
                setEdgeWeight(edge, getEdgeWeight(edge) + countMap.get(areaId));
            }
        }
    }

    private BrainAnnotation getLevelAncestor(BrainAnnotation annotation, int maxOntologyDepth) {
        int depth = annotation.getOntologyDepth();
        BrainAnnotation ancestor = annotation;
        if (depth > maxOntologyDepth) {
            int diff = maxOntologyDepth - depth;
            ancestor = annotation.getAncestor(diff);
        }
        return ancestor;
    }

    public void filterEdgesByWeight(int minWeight) {
        if (minWeight < 1) {
            return;
        }
        Set<AnnotationWeightedEdge> toRemove = new HashSet<>();
        for (AnnotationWeightedEdge edge : edgeSet()) {
            if (edge.getWeight() < minWeight) {
                toRemove.add(edge);
            }
        }
        removeAllEdges(toRemove);
    }

    public void removeOrphanedNodes() {
        Set<BrainAnnotation> toRemove = new HashSet<>();
        for (BrainAnnotation node : vertexSet()) {
            if (this.degreeOf(node) == 0) {
                toRemove.add(node);
            }
        }
        removeAllVertices(toRemove);
    }

    /**
     * Gets the sum of all edge weights.
     *
     * @return the sum of all edge weights
     */
    public double sumEdgeWeights() {
        return edgeSet().stream().mapToDouble(this::getEdgeWeight).sum();
    }

    @Override
    public void setVertexColor(BrainAnnotation vertex, ColorRGB color) {
        if (containsVertex(vertex)) {
            vertexColorRGBMap.put(vertex, color);
        }
    }

    @Override
    public void setEdgeColor(AnnotationWeightedEdge edge, ColorRGB color) {
        if (containsEdge(edge)) {
            edgeColorRGBMap.put(edge, color);
        }
    }

    public ColorRGB getVertexColor(BrainAnnotation vertex) {
        if (containsVertex(vertex) && vertexColorRGBMap.containsKey(vertex)) {
            return vertexColorRGBMap.get(vertex);
        }
        return null;
    }

    public ColorRGB getEdgeColor(AnnotationWeightedEdge edge) {
        if (containsEdge(edge) && edgeColorRGBMap.containsKey(edge)) {
            return edgeColorRGBMap.get(edge);
        }
        return null;
    }

    protected Map<BrainAnnotation, ColorRGB> getVertexColorRGBMap() {
        return vertexColorRGBMap;
    }

    protected Map<AnnotationWeightedEdge, ColorRGB> getEdgeColorRGBMap() {
        return edgeColorRGBMap;
    }

    /**
     * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
     *
     * @return a reference to the displayed window.
     */
    public Window show() {
        return AnnotationGraphUtils.show(this);
    }

}
