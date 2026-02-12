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

package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.jgrapht.Graphs;
import org.jogamp.vecmath.Vector3d;

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
	private final Map<Path, SWCPoint> branchStartMap = new HashMap<>();
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

		// Maps built during classification to avoid O(n²) re-filtering
		final Map<Integer, Set<SWCPoint>> nodesByOrder = new HashMap<>();
		final Map<Integer, Set<SWCPoint>> branchPointsByOrder = new HashMap<>();
		final Map<Integer, Set<SWCPoint>> tipsByOrder = new HashMap<>();

		SNTUtils.log("Assigning order labels...");
		// NB: We must iterate over vertices in reverse order (tips-to-root)
		// so children are classified before parents
		maxOrder = 1;
		final ListIterator<SWCPoint> listIterator = allNodes.listIterator(allNodes.size());
		while (listIterator.hasPrevious()) {
			final SWCPoint node = listIterator.previous();
			classifyNode(node);
			if (node.v < 1)
				unVisitedNodes.add(node);
		}

		// All vertices should have been visited at this point. Do a 2nd pass
		// if not (perhaps ordering in graph.vertexSet() got scrambled?)
		assert unVisitedNodes.isEmpty();
		while (!unVisitedNodes.isEmpty()) {
			final ListIterator<SWCPoint> unvisitedIterator = unVisitedNodes.listIterator(unVisitedNodes.size());
			while (unvisitedIterator.hasPrevious()) {
				final SWCPoint node = unvisitedIterator.previous();
				classifyNode(node);
				if (node.v >= 1) // Fix: remove nodes that HAVE been visited
					unvisitedIterator.remove();
			}
		}
		SNTUtils.log("Max order: " + maxOrder);

		// Single pass to build all order-based maps and identify branch points/tips
		SNTUtils.log("Building order maps...");
		for (final SWCPoint node : graph.vertexSet()) {
			final int order = (int) node.v;
			nodesByOrder.computeIfAbsent(order, k -> new HashSet<>()).add(node);

			final int outDegree = graph.outDegreeOf(node);
			if (outDegree > 1) {
				branchPointsByOrder.computeIfAbsent(order, k -> new HashSet<>()).add(node);
			} else if (outDegree == 0) {
				tipsByOrder.computeIfAbsent(order, k -> new HashSet<>()).add(node);
			}
		}

		// Single pass over edges to compute cable length per order
		SNTUtils.log("Computing cable lengths...");
		final Map<Integer, Double> cableLengthByOrder = new HashMap<>();
		for (final SWCWeightedEdge edge : graph.edgeSet()) {
			final SWCPoint source = graph.getEdgeSource(edge);
			final SWCPoint target = graph.getEdgeTarget(edge);
			// Edge belongs to the order of both endpoints (they should match for intra-order edges)
			if ((int) source.v == (int) target.v) {
				final int order = (int) source.v;
				cableLengthByOrder.merge(order, graph.getEdgeWeight(edge), Double::sum);
			}
		}

		SNTUtils.log("Assembling maps...");
		for (int order = 1; order <= maxOrder; order++) {
			// Cable length
			tLengthMap.put(order, cableLengthByOrder.getOrDefault(order, 0.0));

			// Branch points count
			final Set<SWCPoint> bps = branchPointsByOrder.getOrDefault(order, Collections.emptySet());
			bPointsMap.put(order, (double) bps.size());

			// Build branches from relevant nodes (branch points + tips)
			final LinkedHashSet<SWCPoint> relevantNodes = new LinkedHashSet<>();
			relevantNodes.addAll(bps);
			relevantNodes.addAll(tipsByOrder.getOrDefault(order, Collections.emptySet()));

			final ArrayList<Path> branches = new ArrayList<>();
			for (final SWCPoint relevantNode : relevantNodes) {
				final Path p = getPathToFirstRelevantAncestor(relevantNode);
				if (p.size() > 1) branches.add(p);
			}

			nBranchesMap.put(order, (double) branches.size());
			branchesMap.put(order, branches);
		}
	}

	private void classifyNode(final SWCPoint node) {
		final int degree = graph.outDegreeOf(node);
		int order = 0;
		if (degree == 0) {
			order = 1;
		} else if (degree == 1) {
			order = (int) Graphs.successorListOf(graph, node).getFirst().v;
		} else {
			// degree > 1: find highest order and its frequency in single pass
			final List<SWCPoint> children = Graphs.successorListOf(graph, node);
			int highestOrder = 0;
			int highestOrderFreq = 0;
			for (final SWCPoint child : children) {
				final int childOrder = (int) child.v;
				if (childOrder > highestOrder) {
					highestOrder = childOrder;
					highestOrderFreq = 1;
				} else if (childOrder == highestOrder) {
					highestOrderFreq++;
				}
			}
			order = (highestOrderFreq >= 2) ? highestOrder + 1 : highestOrder;
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

		List<SWCPoint> ancestors = new ArrayList<>();
		ancestors.add(currentVertex);

		while (Graphs.vertexHasPredecessors(graph, currentVertex)) {
			currentVertex = Graphs.predecessorListOf(graph,  currentVertex).getFirst();
			ancestors.add(currentVertex);
			if (graph.outDegreeOf(currentVertex) > 1) {
				break;
			}
		}

		// Iterate in reverse to add nodes root-to-tip
		for (int i = ancestors.size() - 1; i >= 0; i--) {
			path.addNode(ancestors.get(i));
		}

		// Store the mapping between branch and its start vertex
		branchStartMap.put(path, startVertex);

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
			final double nNodes = branches.stream().mapToInt(Path::size).sum();
			fragMap.put(order, (branches.isEmpty()) ? Double.NaN : nNodes/branches.size());
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
				mappedNodes.computeIfAbsent((int) node.v, k -> new ArrayList<>()).add(node);
			}
		}
		return mappedNodes;
	}

	/**
	 * Computes the relative extension angle for a StrahlerAnalyzer branch
	 * by finding the parent direction from the graph structure.
	 * 
	 * @param branch the branch path to analyze
	 * @return the relative extension angle in degrees (0-180°), or Double.NaN if computation fails
	 */
	public double getRelativeExtensionAngle(final Path branch) {
		if (graph == null) compute();
		return computeRelativeExtensionAngle(branch, graph);
	}

	/**
	 * Gets a map of relative extension angles for all branches in each Strahler order.
	 *
	 * @return map with Strahler order as key and list of relative extension angles as values
	 */
	public Map<Integer, List<Double>> getExtensionAngles(final boolean relative) {
		final Map<Integer, List<Double>> anglesMap = new TreeMap<>();
		if (relative) {
			getBranches().forEach((order, branches) -> {
				final List<Double> angles = new ArrayList<>();
				for (final Path branch : branches) {
					final double angle = getRelativeExtensionAngle(branch);
					if (!Double.isNaN(angle)) {
						angles.add(angle);
					}
				}
				anglesMap.put(order, angles);
			});
		} else {
			getBranches().forEach((order, branches) -> {
				final List<Double> angles = new ArrayList<>();
				for (final Path branch : branches) {
					final double angle = branch.getExtensionAngle3D(false);
					if (!Double.isNaN(angle)) {
						angles.add(angle);
					}
				}
				anglesMap.put(order, angles);
			});
		}
		return anglesMap;
	}

	/**
	 * Gets the average relative extension angle for branches of a specific Strahler order.
	 *
	 * @param order the Strahler order
	 * @return the average relative extension angle in degrees, or Double.NaN if no valid angles
	 * @throws IllegalArgumentException if order is invalid
	 */
	public double getAvgExtensionAngle(final boolean relative, final int order) throws IllegalArgumentException {
		if (order < 1 || order > getHighestBranchOrder()) {
			throw new IllegalArgumentException("Invalid branch order: 1 >= order <= " + getHighestBranchOrder());
		}
		final List<Double> angles = getExtensionAngles(relative).get(order);
		if (angles == null || angles.isEmpty()) {
			return Double.NaN;
		}
		return angles.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
	}

	/**
	 * Gets a map of average relative extension angles for all Strahler orders.
	 *
	 * @return map with Strahler order as key and average relative extension angle as value
	 */
	public Map<Integer, Double> getAvgExtensionAngles(final boolean relative) {
		final Map<Integer, Double> avgAnglesMap = new TreeMap<>();
		final Map<Integer, List<Double>> anglesMap = getExtensionAngles(relative);
		anglesMap.forEach((order, angles) -> {
			final double avgAngle = angles.isEmpty() ? Double.NaN :
					angles.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
			avgAnglesMap.put(order, avgAngle);
		});
		return avgAnglesMap;
	}

	/**
	 * Computes the relative extension angle for a StrahlerAnalyzer branch
	 * by finding the parent direction from the graph structure.
	 */
	private double computeRelativeExtensionAngle(final Path branch, final DirectedWeightedGraph graph) {
		if (branch.size() < 2) {
			return Double.NaN;
		}

		// Get the branch's direction vector
		final Vector3d branchDirection = branch.getExtensionDirection3D();
		if (branchDirection == null) {
			return Double.NaN;
		}

		// Get the branch start vertex from our mapping
		final SWCPoint branchStart = branchStartMap.get(branch);

		if (branchStart == null) {
			return Double.NaN;
		}

		// Get the parent direction by finding the predecessor path
		final Vector3d parentDirection = getParentDirection(branchStart, graph);
		if (parentDirection == null) {
			return Double.NaN;
		}

		// Use the existing method to compute angle between directions
		return branch.getExtensionAngle3D(parentDirection);
	}

	/**
	 * Gets the direction vector of the parent segment leading to the branch point.
	 */
	private Vector3d getParentDirection(final SWCPoint branchPoint, final DirectedWeightedGraph graph) {
		// Find predecessors of the branch point
		final List<SWCPoint> predecessors = Graphs.predecessorListOf(graph, branchPoint);
		if (predecessors.isEmpty()) {
			return null; // This is a root, no parent
		}

		// Trace back to get enough points to compute direction
		final List<SWCPoint> parentSegment = new ArrayList<>();
		SWCPoint current = predecessors.getFirst(); // Take the first (should be only one) predecessor

		// Collect points going backwards until we have enough or hit a branch point
		while (current != null && parentSegment.size() < 10) { // Use up to 10 points for direction
			parentSegment.addFirst(current); // Add to beginning of list

			final List<SWCPoint> currentPredecessors = Graphs.predecessorListOf(graph, current);
			if (currentPredecessors.isEmpty()) {
				break; // Reached root
			}

			current = currentPredecessors.getFirst();

			// Stop if we hit another branch point (more than one successor)
			if (graph.outDegreeOf(current) > 1) {
				parentSegment.addFirst(current);
				break;
			}
		}

		if (parentSegment.size() < 2) {
			return null; // Not enough points to compute direction
		}

		// Compute direction using linear regression (same approach as Path.getExtensionDirection3D)
		return computeDirectionFromPoints(parentSegment);
	}

	/**
	 * Computes direction vector from a list of SWCPoints by creating a temporary Path
	 * and using its existing getExtensionDirection3D() method. This recycles the 
	 * linear regression logic already implemented in the Path class.
	 */
	private Vector3d computeDirectionFromPoints(final List<SWCPoint> points) {
		if (points.size() < 2) {
			return null;
		}
		// Create a temporary Path from the SWCPoints to reuse existing linear regression
		final Path tempPath = new Path();
		for (final SWCPoint point : points) {
			tempPath.addNode(new sc.fiji.snt.util.PointInImage(point.x, point.y, point.z));
		}
		// Use the existing, well-tested linear regression implementation
		return tempPath.getExtensionDirection3D();
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

		// Demonstrate new relative extension angle functionality
		System.out.println("\n--- Relative Extension Angles ---");
		analyzer.getAvgExtensionAngles(true).forEach((order, avgAngle) -> {
			System.out.printf("# Avg relative extension angle order %d: %.1f°%n", order, avgAngle);
		});
		
		// Show individual branch angles for first order
		final Map<Integer, List<Double>> relativeAngles = analyzer.getExtensionAngles(true);
		if (!relativeAngles.isEmpty()) {
			final int firstOrder = relativeAngles.keySet().iterator().next();
			final List<Double> angles = relativeAngles.get(firstOrder);
			System.out.printf("# Individual relative angles for order %d: %s%n", 
				firstOrder, angles.stream().map(a -> String.format("%.1f°", a)).collect(java.util.stream.Collectors.joining(", ")));
		}
		final TreeColorMapper mapper = new TreeColorMapper(ij.context());
		mapper.map(tree, TreeColorMapper.STRAHLER_ORDERS, ColorTables.ICE);
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

	/**
	 * Gets statistics about the internal mappings for debugging purposes.
	 *
	 * @return map containing mapping statistics
	 */
	private Map<String, Integer> getMappingStatistics() {
		final Map<String, Integer> stats = new HashMap<>();
		stats.put("branchStartMappings", branchStartMap.size());
		stats.put("totalVertices", graph != null ? graph.vertexSet().size() : 0);
		return stats;
	}

	/** Clears internal caches and mappings to free memory. */
	public void dispose() {
		branchStartMap.clear();
		mappedNodes.clear();
		branchesMap.clear();
		nBranchesMap.clear();
		bPointsMap.clear();
		bRatioMap.clear();
		tLengthMap.clear();
	}
}