package sc.fiji.snt.analysis.graph;

import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.PointInImage;

import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class AnnotationGraph extends SNTGraph<BrainAnnotation, AnnotationWeightedEdge> {

    public static final String TIPS = "tips";
    public static final String LENGTH = "length";
    public static final String BRANCH_POINTS = "branches";

    protected AnnotationGraph() {
        super(AnnotationWeightedEdge.class);
    }

    public AnnotationGraph(final Collection<Tree> trees) {
        this(trees, "tips", 1, AllenUtils.getHighestOntologyDepth());
    }

    public AnnotationGraph(final Collection<Tree> trees, String metric, double threshold, int maxOntologyDepth) {
        super(AnnotationWeightedEdge.class);
        if (trees.isEmpty()) {
            throw new IllegalArgumentException("Empty Tree collection given");
        }
        if (threshold < 0) {
            threshold = 0;
        }
        if (maxOntologyDepth < 0) {
            maxOntologyDepth = 0;
        }
        switch (metric) {
            case TIPS:
                init_tips(trees, (int)threshold, maxOntologyDepth);
                break;
            case BRANCH_POINTS:
                init_branch_points(trees, (int)threshold, maxOntologyDepth);
                break;
            case LENGTH:
                init_length(trees, threshold, maxOntologyDepth);
                break;
            default:
                throw new IllegalArgumentException("Unknown metric");
        }
    }

    private void init_tips(final Collection<Tree> trees, int minTipCount, int maxOntologyDepth) {
        final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
        for (final Tree tree : trees) {
            BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
            if (rootAnnotation == null) {
                continue;
            }
            rootAnnotation = getLevelAncestor(rootAnnotation, maxOntologyDepth);
            if (!annotationPool.containsKey(rootAnnotation.id())) {
                annotationPool.put(rootAnnotation.id(), rootAnnotation);
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

    private void init_branch_points(final Collection<Tree> trees, int minBranchCount, int maxOntologyDepth) {
        final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
        for (final Tree tree : trees) {
            BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
            if (rootAnnotation == null) {
                continue;
            }
            rootAnnotation = getLevelAncestor(rootAnnotation, maxOntologyDepth);
            if (!annotationPool.containsKey(rootAnnotation.id())) {
                annotationPool.put(rootAnnotation.id(), rootAnnotation);
            }
            rootAnnotation = annotationPool.get(rootAnnotation.id());
            if (!containsVertex(rootAnnotation)) {
                addVertex(rootAnnotation);
            }
            final Set<PointInImage> branches = new TreeAnalyzer(tree).getBranchPoints();
            Map<Integer, Integer> countMap = new HashMap<>();
            for (final PointInImage branch : branches) {
                BrainAnnotation branchAnnotation = branch.getAnnotation();
                if (branchAnnotation == null) {
                    continue;
                }
                branchAnnotation = getLevelAncestor(branchAnnotation, maxOntologyDepth);
                if (!annotationPool.containsKey(branchAnnotation.id())) {
                    annotationPool.put(branchAnnotation.id(), branchAnnotation);
                }
                if (!countMap.containsKey(branchAnnotation.id())) {
                    countMap.put(branchAnnotation.id(), 0);
                }
                countMap.put(branchAnnotation.id(), countMap.get(branchAnnotation.id()) + 1);
            }
            List<Integer> filteredBranches = countMap
                    .entrySet()
                    .stream()
                    .filter(e -> e.getValue() >= minBranchCount)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            for (Integer areaId : filteredBranches) {
                BrainAnnotation fBranch = annotationPool.get(areaId);
                if (!containsVertex(fBranch)) {
                    addVertex(fBranch);
                }
                AnnotationWeightedEdge edge = getEdge(rootAnnotation, fBranch);
                if (edge == null) {
                    edge = new AnnotationWeightedEdge();
                    addEdge(rootAnnotation, fBranch, edge);
                    setEdgeWeight(edge, 0);
                }
                setEdgeWeight(edge, getEdgeWeight(edge) + countMap.get(areaId));
            }
        }
    }

    private void init_length(final Collection<Tree> trees, double minCableLength, int maxOntologyDepth) {
        final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
        for (final Tree tree : trees) {
            BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
            if (rootAnnotation == null) {
                continue;
            }
            rootAnnotation = getLevelAncestor(rootAnnotation, maxOntologyDepth);
            if (!annotationPool.containsKey(rootAnnotation.id())) {
                annotationPool.put(rootAnnotation.id(), rootAnnotation);
            }
            rootAnnotation = annotationPool.get(rootAnnotation.id());
            if (!containsVertex(rootAnnotation)) {
                addVertex(rootAnnotation);
            }
            Map<BrainAnnotation, Double> lengthMap = new TreeStatistics(tree).getAnnotatedLength(maxOntologyDepth);
            for (Map.Entry<BrainAnnotation, Double> entry : lengthMap.entrySet()) {
                if (entry.getKey() == null) {continue;}
                if (entry.getValue() < minCableLength) {continue;}
                BrainAnnotation area = entry.getKey();
                if (!annotationPool.containsKey(area.id())) {
                    annotationPool.put(area.id(), area);
                }
                area = annotationPool.get(area.id());
                if (!containsVertex(area)) {
                    addVertex(area);
                }
                AnnotationWeightedEdge edge = getEdge(rootAnnotation, area);
                if (edge == null) {
                    edge = new AnnotationWeightedEdge();
                    addEdge(rootAnnotation, area, edge);
                    setEdgeWeight(edge, 0);
                }
                setEdgeWeight(edge, getEdgeWeight(edge) + entry.getValue());
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

    /**
     * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
     *
     * @return a reference to the displayed window.
     */
    protected void show() {
        // TODO
    }

}
