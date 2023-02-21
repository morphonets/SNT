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

package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jgrapht.Graphs;
import org.jgrapht.graph.AsSubgraph;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Class to perform Horton-Strahler analysis on a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class StrahlerAnalyzer {

	private final Tree tree;
	private int maxOrder = -1;
	private final Map<Integer, List<SWCPoint>> mappedNodes = new TreeMap<>();
	private final Map<Integer, List<Path>> branchesMap = new TreeMap<>();
	private final Map<Integer, Double> nBranchesMap = new TreeMap<>();
	private final Map<Integer, Double> bPointsMap = new TreeMap<>();
	private final Map<Integer, Double> bRatioMap = new TreeMap<>();
	private final Map<Integer, Double> tLengthMap = new TreeMap<>();
	private DirectedWeightedGraph graph;

	public StrahlerAnalyzer(final Tree tree) {
		this.tree = tree;
	}

	private void compute() throws IllegalArgumentException {
		SNTUtils.log("Retrieving graph...");
		compute(tree.getGraph()); // IllegalArgumentException if i.e, tree has multiple roots
	}

	private void compute(final DirectedWeightedGraph graph) throws IllegalArgumentException {

		this.graph = graph;

		final List<SWCPoint> allNodes = new ArrayList<>();
		final List<SWCPoint> unVisitedNodes = new ArrayList<>();

		// we'll store order classification in the "value" field of each node,
		// so we need to reset those to 0 in case they have been set elsewhere
		SNTUtils.log("Resetting node values...");
		for (final SWCPoint node : graph.vertexSet()) {
			node.v = 0;
			allNodes.add(node);
		}

		SNTUtils.log("Assigning order labels...");
		// NB: We must iterate over vertices in reverse order
		maxOrder = 1;
		final ListIterator<SWCPoint> listIterator = allNodes.listIterator(allNodes.size());
		while (listIterator.hasPrevious()) {
			final SWCPoint node = listIterator.previous();
			classifyNode(node);
			if (node.v < 1)
				unVisitedNodes.add(node);
		}

		// All vertices should have been visited at this point. Do a 2nd pass
		// if not (perhaps ordering in graph.vertexSet() got scrambled?),
		assert unVisitedNodes.isEmpty();
		while (unVisitedNodes.size() > 0) {
			final ListIterator<SWCPoint> unvisitedIterator = unVisitedNodes.listIterator(unVisitedNodes.size());
			while (unvisitedIterator.hasPrevious()) {
				final SWCPoint node = unvisitedIterator.previous();
				classifyNode(node);
				if (node.v < 1)
					unvisitedIterator.remove();
			}
		}
		SNTUtils.log("Max order: " + maxOrder);

		SNTUtils.log("Assembling maps...");
		IntStream.rangeClosed(1, maxOrder).forEach(order -> {

			final Set<SWCPoint> nodes = graph.vertexSet().stream() //
					.filter(node -> node.v == order) // include only those of this order
					.collect(Collectors.toCollection(HashSet::new)); // collect output in new list

			// now measure the group
			final AsSubgraph<SWCPoint, SWCWeightedEdge> subGraph = new AsSubgraph<SWCPoint, SWCWeightedEdge>(
					graph, nodes);

			// Total length
			double cableLength = 0;
			for (final SWCWeightedEdge edge : subGraph.edgeSet()) {
				cableLength += subGraph.getEdgeWeight(edge);
			}
			tLengthMap.put(order, cableLength);

			// # N Branch Points
			double nBPs = 0;
			for (final SWCPoint node : subGraph.vertexSet()) {
				if (graph.outDegreeOf(node) > 1) {
					nBPs++;
				}
			}
			bPointsMap.put(order, nBPs);

			// # N. branches
			ArrayList<Path>branches = new ArrayList<>();
			final LinkedHashSet<SWCPoint> relevantNodes = new LinkedHashSet<>();
			relevantNodes.addAll(subGraph.vertexSet().stream()
					.filter(v -> graph.outDegreeOf(v) > 1).collect(Collectors.toCollection(HashSet::new)));
			relevantNodes.addAll(subGraph.vertexSet().stream()
					.filter(v -> graph.outDegreeOf(v) == 0).collect(Collectors.toCollection(HashSet::new)));
			
			for (SWCPoint subGraphNode : relevantNodes) {
				Path p = getPathToFirstRelevantAncestor(subGraphNode);
				if (p.size() > 1) branches.add(p);
			}
			
			nBranchesMap.put(order, (double)branches.size());
			branchesMap.put(order, branches);
		});
	}

	private void classifyNode(final SWCPoint node) {
		final int degree = graph.outDegreeOf(node);
		int order = 0;
		if (degree == 0) {
			order = 1;
		} else if (degree == 1) {
			order = (int) Graphs.successorListOf(graph, node).get(0).v;
		} else if (degree > 1) {
			final List<SWCPoint> children = Graphs.successorListOf(graph, node);
			final int highestOrder = (int) Collections.max(children, Comparator.comparing(n -> (int) n.v)).v;
			final long highestOrderFreq = children.stream().filter(c -> (int) c.v == highestOrder).count();
			if (highestOrderFreq == 1L)
				order = highestOrder;
			else if (highestOrderFreq > 1L) {
				order = highestOrder + 1;
			}
		}
		if (order > maxOrder) maxOrder = order;
		node.v = order;
	}

	/**
	 * @return the graph of the tree being parsed.
	 */
	public DirectedWeightedGraph getGraph() {
		if (graph == null) compute();
		return graph;
	}
	
	private Path getPathToFirstRelevantAncestor(SWCPoint startVertex) {
		Path path = startVertex.getPath().createPath();
		path.setOrder((int) startVertex.v);
		SWCPoint currentVertex = startVertex;

		List<SWCPoint> reversed = new ArrayList<SWCPoint>();
		reversed.add(0, currentVertex);
		
		while (Graphs.vertexHasPredecessors(graph, currentVertex)) {
			currentVertex = Graphs.predecessorListOf(graph,  currentVertex).get(0);
			reversed.add(0, currentVertex);
			if (graph.outDegreeOf(currentVertex) > 1) {
				break;
			}
		}

		for (SWCPoint point : reversed) {
			path.addNode(point);
		}
		return path;
	}

	/**
	 * @return the highest Horton-Strahler number in the parsed tree.
	 */
	public int getRootNumber() {
		if (maxOrder < 1) compute();
		return maxOrder;
	}

	/**
	 * @return the highest Horton-Strahler number associated with a branch in the
	 *         parsed tree. Either {@code getRootNumber()} or
	 *         {@code getRootNumber()-1}.
	 */
	public int getHighestBranchOrder() {
		if (branchesMap == null || branchesMap.isEmpty()) compute();
		return (branchesMap.get(getRootNumber()).isEmpty()) ? maxOrder-1 : maxOrder;
	}

	/**
	 * @return the map containing the cable lengh associated to each order (
	 *         Horton-Strahler numbers as key and cable length as value).
	 */
	public Map<Integer, Double> getLengths() {
		if (tLengthMap == null || tLengthMap.isEmpty()) compute();
		return tLengthMap;
	}

	public Map<Integer, Double> getAvgFragmentations() {
		final Map<Integer, Double> fragMap = new TreeMap<>();
		getBranches().forEach( (order, branches) -> {
			final double nNodes = branches.stream().mapToInt(branch -> branch.size()).sum();
			fragMap.put(order, (branches.size()==0) ? Double.NaN : nNodes/branches.size());
		});
		return fragMap;
	}

	public Map<Integer, Double> getAvgContractions() {
		final Map<Integer, Double> contractMap = new TreeMap<>();
		getBranches().forEach((order, branches) -> {
			double contraction = 0;
			for (final Path branch : branches) {
				final double pContraction = branch.getContraction();
				if (!Double.isNaN(pContraction))
					contraction += pContraction;
			}
			contractMap.put(order, contraction / branches.size());
		});
		return contractMap;
	}

	/**
	 * @return the map containing the number of branches on each order
	 *         (Horton-Strahler numbers as key and branch count as value).
	 */
	public Map<Integer, Double> getBranchCounts() {
		if (nBranchesMap == null || nBranchesMap.isEmpty()) compute();
		return nBranchesMap;
	}

	/**
	 * @return the map containing the number of branch points on each order
	 *         (Horton-Strahler numbers as key and branch points count as value).
	 */
	public Map<Integer, Double> getBranchPointCounts() {
		if (bPointsMap == null || bPointsMap.isEmpty()) compute();
		return bPointsMap;
	}

	/**
	 * @return the map containing the list of branches on each order
	 *         (Horton-Strahler numbers as key and branch points count as value).
	 */
	public Map<Integer, List<Path>> getBranches() {
		if (branchesMap == null || branchesMap.isEmpty()) compute();
		return branchesMap;
	}

	public List<Path> getBranches(final int order) throws IllegalArgumentException {
		if (order < 1 || order > getHighestBranchOrder())
			throw new IllegalArgumentException("Invalid branch order: 1 >= order <= " + getHighestBranchOrder());
		return branchesMap.get(order);
	}

	public List<Path> getRootAssociatedBranches() {
		final List<Path> rootBranches = new ArrayList<>();
		final int highestBranchOrder = getHighestBranchOrder();
		final SWCPoint root = graph.getRoot();
		IntStream.rangeClosed(1, highestBranchOrder).forEach(order -> {
			getBranches().get(order).forEach(branch -> {
				if (branch.getNode(0).isSameLocation(root)) {
					//System.out.println("Adding root branch order " + branch.getOrder());
					rootBranches.add(branch);
				}
			});
		});
		return rootBranches;
	}

	/**
	 * @return the map containing the bifurcation ratios obtained as the ratio of
	 *         no. of branches between consecutive orders (Horton-Strahler numbers
	 *         as key and ratios as value).
	 */
	public Map<Integer, Double> getBifurcationRatios() {
		if (bRatioMap == null || bRatioMap.isEmpty()) {
			compute();
			int hbo = getHighestBranchOrder();
			IntStream.rangeClosed(2, hbo).forEach(order -> {
				bRatioMap.put(order - 1, nBranchesMap.get(order - 1) / nBranchesMap.get(order));
			});
			bRatioMap.put(hbo, Double.NaN);
		}
		return bRatioMap;
	}

	/**
	 * @return the average {@link #getBifurcationRatios() bifurcation ratio} of the
	 *         parsed tree. In a complete binary tree, the bifurcation ratio is 2.
	 */
	public double getAvgBifurcationRatio() {
		return getBifurcationRatios().values().stream().filter(r -> !Double.isNaN(r)).mapToDouble(r -> r).average()
				.orElse(Double.NaN);
	}

	/**
	 * @return the map containing the nodes associated with each order
	 *         (Horton-Strahler numbers as key and ratios as value).
	 */
	protected Map<Integer, List<SWCPoint>> getNodes() {
		if (mappedNodes == null || mappedNodes.isEmpty()) {
			compute();
			for (final SWCPoint node : graph.vertexSet()) {
				List<SWCPoint> list = mappedNodes.get((int) node.v);
				if (list == null) {
					list = new ArrayList<>();
					list.add(node);
					mappedNodes.put((int) node.v, list);
				} else {
					mappedNodes.get((int) node.v).add(node);
				}
			}
		}
		return mappedNodes;
	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		SNTUtils.setDebugMode(true);
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTree("fractal");
		final StrahlerAnalyzer analyzer = new StrahlerAnalyzer(tree);
		analyzer.getBranchCounts().forEach((order, counts) -> {
			System.out.println("# branches order " + order + ": " + counts);
		});
		analyzer.getBranchPointCounts().forEach((order, counts) -> {
			System.out.println("# BPs order " + order + ": " + counts);
		});
		analyzer.getBifurcationRatios().forEach((order, ratio) -> {
			System.out.println("# B. ratio order " + order + ": " + ratio);
		});
		System.out.println("# Avg B. ratio: " + analyzer.getAvgBifurcationRatio());
		final TreeColorMapper mapper = new TreeColorMapper(ij.context());
		mapper.map(tree, TreeColorMapper.STRAHLER_NUMBER, ColorTables.ICE);
		final Viewer3D viewer = new Viewer3D(ij.context());
		viewer.addColorBarLegend(mapper);
		viewer.add(tree);
		viewer.show();
	}

	public static void classify(final DirectedWeightedGraph graph, final boolean reverseOrder) {
		final StrahlerAnalyzer sa = new StrahlerAnalyzer(null);
		sa.compute(graph);
		if (reverseOrder) {
			graph.vertexSet().forEach( vertex -> {
				vertex.v = sa.getRootNumber() - vertex.v + 1;
			});
		}
	}
}
