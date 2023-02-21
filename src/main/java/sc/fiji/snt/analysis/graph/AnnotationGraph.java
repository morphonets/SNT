/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
package sc.fiji.snt.analysis.graph;

import net.imagej.ImageJ;
import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.util.SupplierUtil;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.viewer.GraphViewer;

import java.util.List;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class AnnotationGraph extends SNTGraph<BrainAnnotation, AnnotationWeightedEdge> {

	private static final long serialVersionUID = 6826816297520498404L;

	public static final String TIPS = "tips";
    public static final String LENGTH = "length";
    public static final String BRANCH_POINTS = "branch points";
    public static final String EDGES = "edges";
    private static final String[] ALL_FLAGS = {
            TIPS,
            LENGTH,
            BRANCH_POINTS,
            EDGES
    };

    private Collection<Tree> treeCollection;
	private String metric;
	private double threshold;
	private int maxOntologyDepth;

    protected AnnotationGraph() {
        super(null, SupplierUtil.createSupplier(AnnotationWeightedEdge.class), new DefaultGraphType.Builder()
                .directed().allowMultipleEdges(false).allowSelfLoops(true).allowCycles(true).weighted(true)
                .modifiable(true)
                .build());
    }

    public AnnotationGraph(final Collection<Tree> trees, String metric, double threshold, int maxOntologyDepth) {
        this();
        if (trees.isEmpty()) {
            throw new IllegalArgumentException("Empty Tree collection given");
        }
        this.treeCollection = trees;
        if (threshold < 0) {
            threshold = 0;
        }
        if (maxOntologyDepth < 0) {
            maxOntologyDepth = 0;
        }
        this.metric = metric;
        this.threshold = threshold;
        this.maxOntologyDepth = maxOntologyDepth;
       
        switch (metric) {
            case TIPS:
                initTips(trees, (int)threshold, maxOntologyDepth);
                break;
            case BRANCH_POINTS:
                initBranchPoints(trees, (int)threshold, maxOntologyDepth);
                break;
            case LENGTH:
                initLength(trees, threshold, maxOntologyDepth);
                break;
            case EDGES:
                initEdges(trees, threshold, maxOntologyDepth);
                break;
            default:
                throw new IllegalArgumentException("Unknown metric");
        }
    }

    public static String[] getMetrics() {
        return ALL_FLAGS;
    }

    private void initTips(final Collection<Tree> trees, int minTipCount, int maxOntologyDepth) {
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

    private void initBranchPoints(final Collection<Tree> trees, int minBranchCount, int maxOntologyDepth) {
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

    private void initLength(final Collection<Tree> trees, double minCableLength, int maxOntologyDepth) {
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

    private void initEdges(Collection<Tree> trees, double threshold, int maxOntologyDepth) {
        final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
        final Map<AnnotationWeightedEdge, Integer> edgeCountMap = new HashMap<>();
        for (final Tree tree : trees) {
            DirectedWeightedGraph tGraph = tree.getGraph(true);
            BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
            if (rootAnnotation == null) {
                continue;
            }
            for (SWCWeightedEdge e : tGraph.edgeSet()) {
                BrainAnnotation sourceAnnotation = e.getSource().getAnnotation();
                if (sourceAnnotation != null) {
                    if (!annotationPool.containsKey(sourceAnnotation.id())) {
                        annotationPool.put(sourceAnnotation.id(), sourceAnnotation);
                    }
                    sourceAnnotation = annotationPool.get(sourceAnnotation.id());
                    if (!containsVertex(sourceAnnotation)) {
                        addVertex(sourceAnnotation);
                    }
                }
                BrainAnnotation targetAnnotation = e.getTarget().getAnnotation();
                if (targetAnnotation != null) {
                    if (!annotationPool.containsKey(targetAnnotation.id())) {
                        annotationPool.put(targetAnnotation.id(), targetAnnotation);
                    }
                    targetAnnotation = annotationPool.get(targetAnnotation.id());
                    if (!containsVertex(targetAnnotation)) {
                        addVertex(targetAnnotation);
                    }
                }
                if (sourceAnnotation != null && targetAnnotation != null) {
                    if (!containsEdge(sourceAnnotation, targetAnnotation)) {
                        AnnotationWeightedEdge edge = addEdge(sourceAnnotation, targetAnnotation);
                        setEdgeWeight(edge, 0);
                    }
                    AnnotationWeightedEdge edge = getEdge(sourceAnnotation, targetAnnotation);
                    setEdgeWeight(edge, edge.getWeight() + 1);
                    edgeCountMap.merge(edge, 1, Integer::sum);
                }
            }
        }
        // Prune self-loops, edges with sub-threshold counts, and isolated vertices
        Set<AnnotationWeightedEdge> removedEdges = edgeSet().stream()
                .filter(e -> (e.getSource().id() == e.getTarget().id()) || (edgeCountMap.getOrDefault(e,0) < threshold))
                .collect(Collectors.toSet());
        removeAllEdges(removedEdges);
        Set<BrainAnnotation> removedVertices = vertexSet().stream().filter(v -> degreeOf(v) == 0).collect(Collectors.toSet());
        removeAllVertices(removedVertices);
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

    public Set<BrainAnnotation> removeOrphans() {
        Set<BrainAnnotation> toRemove = new HashSet<>();
        for (BrainAnnotation node : vertexSet()) {
            if (this.degreeOf(node) == 0) {
                toRemove.add(node);
            }
        }
        removeAllVertices(toRemove);
        return toRemove;
    }

    /**
     * Gets the sum of all edge weights.
     *
     * @return the sum of all edge weights
     */
    public double sumEdgeWeights() {
        return edgeSet().stream().mapToDouble(this::getEdgeWeight).sum();
    }

    public List<Tree> getTrees() {
        if (treeCollection != null) {
            return new ArrayList<>(treeCollection);
        }
        return null;
    }

    /**
     * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
     *
     */
    protected void show() {
        new GraphViewer(this).show();
    }

	public int getMaxOntologyDepth() {
		return maxOntologyDepth;
	}

	public double getThreshold() {
		return threshold;
	}

	public String getMetric() {
		return metric;
	}

	@SuppressWarnings("unused")
	public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        Tree t = new MouseLightLoader("AA0001").getTree();
        final AnnotationGraph g = new AnnotationGraph(Collections.singleton(t), AnnotationGraph.LENGTH, 0,
                AllenUtils.getHighestOntologyDepth());
        BrainAnnotation cortex = AllenUtils.getCompartment("cerebral cortex");
        UnaryOperator<BrainAnnotation> op = a -> a.isChildOf(cortex) ? cortex : a;
        UnaryOperator<BrainAnnotation> op2 = a -> a.getOntologyDepth() > 7 ? a.getAncestor(7 - a.getOntologyDepth()) : a;
        UnaryOperator<BrainAnnotation> op3 = (a) -> a.isChildOf(cortex) ? null : a;
        UnaryOperator<BrainAnnotation> op4 = (a) -> a.isMeshAvailable() ? a : null;
        UnaryOperator<BrainAnnotation> op5 = (a) -> g.edgesOf(a).stream()
                .mapToDouble(AnnotationWeightedEdge::getWeight)
                .sum() < 1000 ? null : a;
        g.applyEdges((e) -> e.getWeight() < 2000 ? null : e);
        g.show();
    }

}
