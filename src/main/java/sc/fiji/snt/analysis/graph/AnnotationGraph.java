/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.viewer.GraphViewer;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A specialized graph implementation for brain annotations that extends {@link SNTGraph}.
 * This class provides functionality to create and analyze graphs based on brain annotations
 * and their relationships, using different metrics for edge weighting.
 * <p>
 * The graph can be constructed using various metrics such as:
 * <ul>
 *     <li>Number of tips ({@link #TIPS})
 *     <li>Total length ({@link #LENGTH})
 *     <li>Number of branch points ({@link #BRANCH_POINTS})
 *     <li>Edge count ({@link #EDGES})
 * </ul>
 * 
 * @see SNTGraph
 * @see BrainAnnotation
 * @see AnnotationWeightedEdge
 */
public class AnnotationGraph extends SNTGraph<BrainAnnotation, AnnotationWeightedEdge> {

	private static final long serialVersionUID = 6826816297520498404L;

	public static final String TIPS = MultiTreeStatistics.N_TIPS;
    public static final String LENGTH = MultiTreeStatistics.LENGTH;
    public static final String BRANCH_POINTS = MultiTreeStatistics.N_BRANCH_POINTS;
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

    /**
     * Protected default constructor for internal use.
     */
    protected AnnotationGraph() {
        super(null, SupplierUtil.createSupplier(AnnotationWeightedEdge.class), new DefaultGraphType.Builder()
                .directed().allowMultipleEdges(false).allowSelfLoops(true).allowCycles(true).weighted(true)
                .modifiable(true)
                .build());
    }

    /**
     * Constructs an annotation graph from a collection of trees using specified parameters.
     *
     * @param trees The collection of trees to analyze
     * @param metric The metric to use for graph construction (one of {@link #getMetrics()})
     * @param threshold The threshold value for filtering connections
     * @param maxOntologyDepth The maximum depth to consider in the ontology hierarchy
     */
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

    /**
     * Constructs an annotation graph from trees and annotations with specified parameters.
     *
     * @param trees The collection of input trees to analyze
     * @param annotations The collection of brain annotations. Used as nodes
     * @param metric The metric to use for graph construction (one of {@link #getMetrics()}). Used as edges
     * @param threshold The threshold value for filtering connections
     */
    public AnnotationGraph(final Collection<Tree> trees, final Collection<BrainAnnotation> annotations, 
            String metric, double threshold) {
        this();
        if (trees.isEmpty()) {
            throw new IllegalArgumentException("Empty Tree collection given");
        }
        if (annotations.isEmpty()) {
            throw new IllegalArgumentException("Empty BrainAnnotation collection given");
        }
        this.treeCollection = trees;
        if (threshold < 0) {
            threshold = 0;
        }
        this.metric = metric;
        this.threshold = threshold;
		maxOntologyDepth = Integer.MIN_VALUE;
		for (final BrainAnnotation annot : annotations) {
			if (annot != null) {
				int depth = annot.getOntologyDepth();
				if (depth > maxOntologyDepth)
					maxOntologyDepth = depth;
			}
		}
        switch (metric) {
            case TIPS:
            	initNodes(trees, annotations, (int)threshold, TIPS);
                break;
            case BRANCH_POINTS:
                initNodes(trees, annotations, (int)threshold, BRANCH_POINTS);
                break;
            case LENGTH:
                initNodes(trees, annotations, (int)threshold, LENGTH);
                break;
            default:
                throw new IllegalArgumentException("Unsupported metric: " + metric);
        }
    }

    /**
     * Constructs an annotation graph from trees and annotations using a specified metric.
     *
     * @param trees The collection of trees to analyze
     * @param annotations The collection of brain annotations to consider
     * @param metric The metric to use for graph construction (one of {@link #getMetrics()})
     */
    public AnnotationGraph(final Collection<Tree> trees, final Collection<BrainAnnotation> annotations, 
            String metric) {
		this(trees, annotations, metric, Double.MIN_VALUE);
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
            final Set<PointInImage> tips = new TreeStatistics(tree).getTips();
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
            final Set<PointInImage> branches = new TreeStatistics(tree).getBranchPoints();
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

	private BrainAnnotation getMatchedAnnotation(final BrainAnnotation query, final Collection<BrainAnnotation> pool) {
		for (final BrainAnnotation annot : pool) {
			if (query.equals(annot) || query.isChildOf(annot))
				return annot;
		}
		return query;
	}

	private double getNodeCount(final TreeStatistics ts, BrainAnnotation annot, final String nodeType) {
		if (TIPS.equals(nodeType)) {
			return ts.getTips(annot).size();
		} else if (BRANCH_POINTS.equals(nodeType)) {
			return ts.getBranchPoints(annot).size();
		} else if (LENGTH.equals(nodeType)) {
			return ts.getCableLength(annot);
		}
		throw new IllegalArgumentException("Unknown nodeType: " + nodeType);
	}

	private void initNodes(final Collection<Tree> trees, final Collection<BrainAnnotation> annotations, double cutoffCount,
			final String nodeType) {
		final Map<Integer, BrainAnnotation> annotationPool = new HashMap<>();
		for (final BrainAnnotation annot : annotations) {
			if (annot != null)
				annotationPool.put(annot.id(), annot);
		}
		for (final Tree tree : trees) {
			final BrainAnnotation rootAnnotation = getMatchedAnnotation(tree.getRoot().getAnnotation(), annotations);
			if (!annotationPool.containsKey(rootAnnotation.id())) {
				annotationPool.put(rootAnnotation.id(), rootAnnotation);
			}
			if (!containsVertex(rootAnnotation))
				addVertex(rootAnnotation);
			final TreeStatistics ta = new TreeStatistics(tree);
			final Map<Integer, Double> countMap = new HashMap<>();
			for (final BrainAnnotation annot : annotations) {
				if (annot != null)
					countMap.put(annot.id(), getNodeCount(ta, annot, nodeType));
			}
			final List<Integer> filteredNodes = countMap.entrySet().stream().filter(e -> e.getValue() >= cutoffCount)
					.map(Map.Entry::getKey).collect(Collectors.toList());
			if (!containsVertex(rootAnnotation))
				addVertex(rootAnnotation);
			for (final Integer areaId : filteredNodes) {
				final BrainAnnotation fNode = annotationPool.get(areaId);
				if (!containsVertex(fNode))
					addVertex(fNode);
				AnnotationWeightedEdge edge = getEdge(rootAnnotation, fNode);
				if (edge == null) {
					edge = new AnnotationWeightedEdge();
					addEdge(rootAnnotation, fNode, edge);
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