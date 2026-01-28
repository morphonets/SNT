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

package sc.fiji.snt.analysis.graph;

import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.BiconnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.jgrapht.traverse.DepthFirstIterator;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.jgrapht.util.SupplierUtil;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.NodeStatistics;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.SWCPoint;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for accessing a reconstruction as a graph structure.
 * 
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class DirectedWeightedGraph extends SNTGraph<SWCPoint, SWCWeightedEdge> {

	private static final long serialVersionUID = 1L;
	private Tree tree;

	/**
	 * Creates a DirectedWeightedGraph from a Tree with edge weights corresponding
	 * to inter-node distances.
	 *
	 * @param tree the Tree to be converted
	 * @throws IllegalArgumentException if Tree contains multiple roots
	 */
	public DirectedWeightedGraph(final Tree tree) {
		this();
		this.tree = tree;
		init(tree.getNodesAsSWCPoints(), true);
	}

	/**
	 * Creates a DirectedWeightedGraph from a Tree, with the option to assign edge weights corresponding
	 * to inter-node distances.
	 *
	 * @param tree the Tree to be converted
	 * @param assignDistancesToWeight whether to assign inter-node distances between adjacent points as edge weights
	 * @throws IllegalArgumentException if Tree contains multiple roots
	 */
	public DirectedWeightedGraph(final Tree tree, final boolean assignDistancesToWeight) {
		this();
		this.tree = tree;
		init(tree.getNodesAsSWCPoints(), assignDistancesToWeight);
	}

	/**
	 * Creates an empty DirectedWeightedGraph. Edges added to the graph are unweighted until
	 * a weight is assigned manually.
	 *
	 * @see #assignEdgeWeightsEuclidean()
	 * @see #setEdgeWeight(Object, Object, double)
	 * @see #setEdgeWeight(Object, double)
	 */
	public DirectedWeightedGraph() {
		super(null, SupplierUtil.createSupplier(SWCWeightedEdge.class), new DefaultGraphType.Builder()
				.directed().allowMultipleEdges(false).allowSelfLoops(false).allowCycles(false).weighted(true)
				.modifiable(true)
				.build());
	}

	/**
	 * Creates a DirectedWeightedGraph from a collection of reconstruction nodes.
	 *
	 * @param nodes                    the collections of SWC nodes
	 * @param assignDistancesToWeight if true, inter-node Euclidean distances are
	 *                                 used as edge weights
	 */
	public DirectedWeightedGraph(final Collection<SWCPoint> nodes, final boolean assignDistancesToWeight) {
		this();
		init(nodes, assignDistancesToWeight);
	}

	private void init(final Collection<SWCPoint> nodes, final boolean assignDistancesToWeights) {
		final Map<Integer, SWCPoint> map = new HashMap<>();
		for (final SWCPoint node : nodes) {
			map.put(node.id, node);
			addVertex(node);
		}
		for (final SWCPoint node : nodes) {
			if (node.parent == -1)
				continue;
			final SWCPoint previousPoint = map.get(node.parent);
			node.setPrevious(previousPoint);
			final SWCWeightedEdge edge = new SWCWeightedEdge();
			addEdge(previousPoint, node, edge);
			if (assignDistancesToWeights) {
				setEdgeWeight(edge, node.distanceTo(previousPoint));
			}
		}
	}

	/**
	 * Returns a simplified graph in which slab nodes are removed and the graph
	 * is represented only by root, branch nodes and leaves. An edge weight in this
	 * graph corresponds to the sum of edge weights along the path between the corresponding
	 * vertices in the base graph.
	 *
	 * @return the simplified graph
	 * @throws IllegalStateException if the base graph does not have exactly one root
	 */
	public DirectedWeightedGraph getSimplifiedGraph() {
		final DirectedWeightedGraph simplifiedGraph = new DirectedWeightedGraph();
		transferCommonProperties(simplifiedGraph);
		final LinkedHashSet<SWCPoint> relevantNodes = new LinkedHashSet<>();
		relevantNodes.add(getRoot());
		relevantNodes.addAll(getBPs());
		relevantNodes.addAll(getTips());
		relevantNodes.forEach(simplifiedGraph::addVertex);
		for (final SWCPoint node : relevantNodes) {
			if (inDegreeOf(node) == 0) {
				// Skip root
				continue;
			}
			double pathWeight = 0;
			SWCPoint current = node;
			while (inDegreeOf(current) > 0) {
				final SWCWeightedEdge inEdge = incomingEdgesOf(current).iterator().next();
				pathWeight += inEdge.getWeight();
				current = inEdge.getSource();
				if (outDegreeOf(current) > 1) {
					break;
				}
			}
			simplifiedGraph.setEdgeWeight(simplifiedGraph.addEdge(current, node), pathWeight);
		}
		return simplifiedGraph;
	}

	public SWCPoint addVertex(final double x, final double y, final double z) {
		SWCPoint v = new SWCPoint(0, 0, x, y, z, 0, 0);
		addVertex(v);
		return v;
	}

	/**
	 * For all edges, sets the Euclidean distance between the source and target
	 * vertex as the weight.
	 */
	public void assignEdgeWeightsEuclidean() {
		for (final SWCWeightedEdge e : this.edgeSet()) {
			final double distance = e.getSource().distanceTo(e.getTarget());
			this.setEdgeWeight(e, distance);
		}
	}

	/**
	 * Scales the point coordinates of each vertex by the specified factors.
	 *
	 * @param xScale                     the scaling factor for x coordinates
	 * @param yScale                     the scaling factor for y coordinates
	 * @param zScale                     the scaling factor for z coordinates
	 * @param updateEdgeWeightsEuclidean if true, update all edge weights with
	 *                                   inter-node Euclidean distances
	 */
	public void scale(final double xScale, final double yScale, final double zScale,
					  final boolean updateEdgeWeightsEuclidean)
	{
		for (final SWCPoint v : vertexSet()) {
			v.scale(xScale, yScale, zScale);
		}
		if (updateEdgeWeightsEuclidean) {
			assignEdgeWeightsEuclidean();
		}
	}

	/**
	 * Gets the sum of all edge weights.
	 *
	 * @return the sum of all edge weights
	 */
	public double sumEdgeWeights() {
		return edgeSet().stream().mapToDouble(SWCWeightedEdge::getWeight).sum();
	}

	/**
	 * Gets a {@link DepthFirstIterator}, using the graph root as start vertex.
	 *
	 * @return the DepthFirstIterator
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator() {
		return new DepthFirstIterator<>(this, getRoot());
	}

	/**
	 * Gets a {@link DepthFirstIterator}, using the specified start vertex.
	 *
	 * @param startVertex the start vertex
	 * @return the DepthFirstIterator
	 */
	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator(final SWCPoint startVertex) {
		return new DepthFirstIterator<>(this, startVertex);
	}

	/**
	 * Gets a {@link BreadthFirstIterator}, using the graph root as start vertex.
	 *
	 * @return the BreadthFirstIterator
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public BreadthFirstIterator<SWCPoint, SWCWeightedEdge> getBreadthFirstIterator() {
		return new BreadthFirstIterator<>(this, getRoot());
	}

	/**
	 * Gets a {@link BreadthFirstIterator}, using the specified start vertex.
	 *
	 * @param startVertex the start vertex
	 * @return the BreadthFirstIterator
	 */
	public BreadthFirstIterator<SWCPoint, SWCWeightedEdge> getBreadthFirstIterator(final SWCPoint startVertex) {
		return new BreadthFirstIterator<>(this, startVertex);
	}

	/**
	 * Gets a {@link TopologicalOrderIterator} for this graph.
	 *
	 * @return the TopologicalOrderIterator
	 */
	public TopologicalOrderIterator<SWCPoint, SWCWeightedEdge> getTopologicalOrderIterator() {
		return new TopologicalOrderIterator<>(this);
	}

	/**
	 * Return the sequence of nodes representing the
	 * <a href="https://mathworld.wolfram.com/GraphDiameter.html">longest shortest-path</a>
	 * in the graph, as a {@link Path}.
	 *
	 * @param useDirected whether to treat the graph as directed.
	 *                    If true, the longest shortest-path will always include the root and a terminal node.
	 * @return the longest shortest-path
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public Path getLongestPath(final boolean useDirected) {
		if (useDirected) {
			return vertexSequenceToPath(getLongestPathDirectedVertices());
		} else {
			return vertexSequenceToPath(getLongestPathUndirectedVertices());
		}
	}

	/**
	 * Return the sequence of nodes representing the
	 * <a href="https://mathworld.wolfram.com/GraphDiameter.html">longest shortest-path</a>
	 * in the graph, as a {@link Deque}. This eliminates the computational overhead of converting the vertex sequence
	 * to a {@link Path} object, if a {@link Path} is not desired.
	 *
	 * @param useDirected whether to treat the graph as directed.
	 *                    If true, the longest shortest-path will always include the root and a terminal node.
	 * @return the longest shortest-path
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public Deque<SWCPoint> getLongestPathVertices(final boolean useDirected) {
		if (useDirected) {
			return getLongestPathDirectedVertices();
		} else {
			return getLongestPathUndirectedVertices();
		}
	}

	private Deque<SWCPoint> getLongestPathDirectedVertices() {
		if (vertexSet().isEmpty()) {
			return null;
		}
		final SWCPoint root = getRoot();
		final SWCPoint deepestNode = deepestNodeFromStart(root, this);
		if (root == deepestNode) {
			return null;
		}
		final Deque<SWCPoint> longestShortestPathVertices = new ArrayDeque<>();
		SWCPoint node = deepestNode;
		longestShortestPathVertices.addFirst(node);
		while (true) {
			Set<SWCWeightedEdge> inEdges = incomingEdgesOf(node);
			if (inEdges.isEmpty()) {
				break;
			}
			node = inEdges.iterator().next().getSource();
			longestShortestPathVertices.addFirst(node);
		}
		return longestShortestPathVertices;
	}

	private Deque<SWCPoint> getLongestPathUndirectedVertices() {
		if (vertexSet().isEmpty()) {
			return null;
		}
		final AsUndirectedGraph<SWCPoint, SWCWeightedEdge> undirectedGraph = new AsUndirectedGraph<>(this);
		// Choose an arbitrary node as start
		final SWCPoint start = vertexSet().iterator().next();
		final SWCPoint x = deepestNodeFromStart(start, undirectedGraph);
		final SWCPoint y = deepestNodeFromStart(x, undirectedGraph);
		return getShortestPathVertices(x, y);
	}

	private static SWCPoint deepestNodeFromStart(final SWCPoint start, final Graph<SWCPoint, SWCWeightedEdge> graph) {
		final BreadthFirstIterator<SWCPoint, SWCWeightedEdge> breadthFirstIterator =
				new BreadthFirstIterator<>(graph, start);
		start.v = 0;
		double maxWeight = 0;
		SWCPoint deepestNode = start;
		while (breadthFirstIterator.hasNext()) {
			final SWCPoint node = breadthFirstIterator.next();
			final SWCPoint previousNode = breadthFirstIterator.getParent(node);
			if (previousNode == null) {
				continue;
			}
			final SWCWeightedEdge edge = breadthFirstIterator.getSpanningTreeEdge(node);
			final double weight = edge.getWeight();
			node.v = previousNode.v + weight;
			if (node.v > maxWeight) {
				maxWeight = node.v;
				deepestNode = node;
			}
		}
		return deepestNode;
	}

	/**
	 * Gets the shortest path between source and target vertex as a {@link Path} object.
	 * Since underlying edge direction is ignored, a shortest path will always exist between
	 * any two vertices that share a connected component.
	 *
	 * @param v1 the source vertex
	 * @param v2 the target vertex
	 * @return the shortest Path between source v1 and target v2, or null if no path exists
	 * @throws IllegalArgumentException if the graph does not contain both v1 and v2
	 */
	public Path getShortestPath(final SWCPoint v1, final SWCPoint v2) {
		return vertexSequenceToPath(getShortestPathVertices(v1, v2));
	}

	/**
	 * Gets the shortest path between source and target vertex as a {@link Deque} of SWCPoint objects.
	 * This eliminates the computational overhead of creating a {@link Path} object from the vertex sequence,
	 * if a {@link Path} is not desired.
	 * Since underlying edge direction is ignored, a shortest path will always exist between
	 * any two vertices that share a connected component.
	 *
	 * @param v1 the source vertex
	 * @param v2 the target vertex
	 * @return the shortest path between source v1 and target v2, or null if no path exists
	 * @throws IllegalArgumentException if the graph does not contain both v1 and v2
	 */
	public Deque<SWCPoint> getShortestPathVertices(final SWCPoint v1, final SWCPoint v2) {
		return shortestPathInternal(v1, v2);
	}

	private Path vertexSequenceToPath(final Deque<SWCPoint> vertexSequence) {
		if (vertexSequence == null || vertexSequence.isEmpty()) {
			return null;
		}
		Path path;
		final Path onPath = vertexSequence.getFirst().getPath();
		if (onPath == null) {
			path = new Path(1d, 1d, 1d, "? units");
		} else {
			path = onPath.createPath();
		}
		path.setOrder(-1);
		path.setName("Path between " + vertexSequence.getFirst() + " and " + vertexSequence.getLast());
		for (SWCPoint vertex : vertexSequence) {
			path.addNode(vertex);
		}
		return path;
	}

	/**
	 * Uses the lowest common ancestor to find the shortest path between any two vertices.
	 */
	private Deque<SWCPoint> shortestPathInternal(final SWCPoint v1, final SWCPoint v2) {
		if (!containsVertex(v1)) {
			throw new IllegalArgumentException("Graph does not contain vertex " + v1);
		}
		if (!containsVertex(v2)) {
			throw new IllegalArgumentException("Graph does not contain vertex " + v2);
		}
		if (v1 == v2) {
			SNTUtils.log("Source " + v1 + " and target " + v2 + " are the same object.");
			return null;
		}

		final Deque<SWCPoint> firstPath = new ArrayDeque<>();
		final Deque<SWCPoint> secondPath = new ArrayDeque<>();

		// Trace the path back to the root for v1
		SWCPoint currentVertex = v1;
		firstPath.add(currentVertex);
		while (true) {
			final Set<SWCWeightedEdge> inEdges = incomingEdgesOf(currentVertex);
			if (inEdges.isEmpty()) {
				break;
			}
			currentVertex = inEdges.iterator().next().getSource();
			firstPath.add(currentVertex);
		}
		// Trace the path back to the root for v2, in reverse order
		currentVertex = v2;
		secondPath.addFirst(currentVertex);
		while (true) {
			final Set<SWCWeightedEdge> inEdges = incomingEdgesOf(currentVertex);
			if (inEdges.isEmpty()) {
				break;
			}
			currentVertex = inEdges.iterator().next().getSource();
			secondPath.addFirst(currentVertex);
		}

		if (firstPath.getLast() != secondPath.getFirst()) {

			/*
			 * Source and target either do not share a connected component
			 * or the graph is in some illegal configuration with regard to
			 * topology (e.g., a node with multiple incoming edges) and/or edge direction.
			 */
			SNTUtils.error("Source " + v1 + " and target " + v2 + " do not share the same root ancestor." +
					" Check that the graph forms a connected, rooted tree.");
			return null;
		}

		// Check if either source or target is the graph root
		int i = firstPath.size() ;
		if (i == 1) {
			return secondPath;
		}
		int j = secondPath.size();
		if (j == 1) {
			return firstPath;
		}

		int k = Math.min(i, j);  // We only need to walk down the shorter of the two paths
		SWCPoint lca = null;
		while (k > 0) {
			// Find the first difference
			final SWCPoint node1 = firstPath.removeLast();
			final SWCPoint node2 = secondPath.removeFirst();
			// Compare references
			if (node1 == node2) {
				lca = node1;
			} else {
				// Last assigned lca is the true lca
				firstPath.add(node1);
				firstPath.add(lca);
				secondPath.addFirst(node2);
				firstPath.addAll(secondPath);
				return firstPath;
			}
			i--;
			j--;
			k--;
		}

		// At this point, we know the lca is either the source or target vertex
		if (i == 0) {
			secondPath.addFirst(lca);
			return secondPath;
		}
		if (j == 0) {
			firstPath.add(lca);
			return firstPath;
		}

		SNTUtils.error("Somehow, a path could not be found between source " + v1 + " and target " +
				v2 + ". Check that the graph forms a connected, rooted tree.");
		return null;
	}

	/**
	 * Re-assigns a unique Integer identifier to each vertex based on visit order
	 * during Depth First Search. Also updates the parent and previousPoint fields
	 * of each SWCPoint vertex contained in the Graph.
	 *
	 * @throws IllegalStateException if the graph does not contain exactly one root
	 */
	public void updateVertexProperties() {
		final DepthFirstIterator<SWCPoint, SWCWeightedEdge> iter = getDepthFirstIterator(getRoot());
		int currentId = 1;
		while (iter.hasNext()) {
			final SWCPoint v = iter.next();
			v.id = currentId;
			currentId++;
			final List<SWCPoint> parentList = Graphs.predecessorListOf(this, v);
			if (!parentList.isEmpty()) {
				v.parent = parentList.get(0).id;
				v.setPrevious(parentList.get(0));
			} else {
				v.parent = -1;
				v.setPrevious(null);
			}
		}
	}

	private void transferCommonProperties(final DirectedWeightedGraph otherGraph) {
		otherGraph.tree = this.tree;
	}

	/**
	 * Gets the branch points (junctions) of the graph.
	 *
	 * @return the list of branch points
	 */
	public List<SWCPoint> getBPs() {
		return vertexSet().stream().filter(v -> outDegreeOf(v) > 1).collect(Collectors.toList());
	}

	/**
	 * Gets the end points (tips) of the graph.
	 *
	 * @return the list of end points
	 */
	public List<SWCPoint> getTips() {
		return vertexSet().stream().filter(v -> outDegreeOf(v) == 0).collect(Collectors.toList());
	}

	/**
	 * Gets a NodeStatistics instance for the vertex set
	 *
	 * @return the NodeStatistics instance
	 */
	public NodeStatistics<SWCPoint> getNodeStatistics() {
		return getNodeStatistics("all");
	}

	/**
	 * Gets a NodeStatistics instance for the nodes in the vertex set of the specified type
	 *
	 * @param type the vertex type (e.g., "tips"/"end-points", "junctions"/"branch points", "all")
	 * @return the NodeStatistics instance
	 */
	public NodeStatistics<SWCPoint> getNodeStatistics(final String type) {
        final NodeStatistics<SWCPoint> nodeStats =  switch (type.toLowerCase()) {
            case "all" -> new NodeStatistics<>(vertexSet());
            case "tips", "endings", "end points", "end-points", "terminals" -> new NodeStatistics<>(getTips());
            case "bps", "forks", "junctions", "fork points", "junction points", "branch points", "branch-points" ->
                    new NodeStatistics<>(getBPs());
            default ->
                    throw new IllegalArgumentException("type not recognized. Perhaps you meant 'all', 'junctions' or 'tips'?");
        };
        if (getTree() != null)
            nodeStats.assignBranches(getTree());
        return nodeStats;
	}

	/**
	 * Gets the root of this graph.
	 *
	 * @return the root node.
	 * @throws IllegalStateException if the graph does not contain exactly one root
	 */
	public SWCPoint getRoot() {
		final List<SWCPoint> roots = vertexSet().stream().filter(v -> inDegreeOf(v) == 0).toList();
		if (roots.isEmpty()) {
			throw new IllegalStateException("Graph has no nodes with in-degree 0");
		}
		if (roots.size() > 1) {
			throw new IllegalStateException("Graph has multiple connected components and/or inconsistent edge directions");
		}
		return roots.getFirst();
	}

	/**
	 * Returns a new tree associated with this graph, using the current state of the
	 * graph to build the tree.
	 *
	 * @return the tree
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public Tree getTree() {
		return getTree(true);
	}

	/**
	 * Returns a tree associated with this graph.
	 *
	 * @param createNewTree If true, reassembles a tree from this graph's data.
	 *                      If false, returns the cached tree if it exists or null if it does not exist
	 * @return the tree
	 */
	public Tree getTree(final boolean createNewTree) {
		if (createNewTree) {
			updateVertexProperties();
			final String label = tree != null ? tree.getLabel() : "";
			return new Tree(this, label);
		} else {
			return tree;
		}
	}

	/**
	 * Attempt to build a new Tree with the same {@link Path} hierarchy as the source Tree.
	 * @return the tree
	 */
	public Tree getTreeWithSamePathStructure() {
		updateVertexProperties();
		return new Tree(this, "", true);
	}

	/**
	 * Return the connected components of this graph as a list of new {@link DirectedWeightedGraph}s.
	 * @return the list of DirectedWeightedGraph objects
	 */
	public List<DirectedWeightedGraph> getComponents() {
		final List<DirectedWeightedGraph> componentList = new ArrayList<>();
		final BiconnectivityInspector<SWCPoint, SWCWeightedEdge> inspector = new BiconnectivityInspector<>(this);
		for (final org.jgrapht.Graph<SWCPoint, SWCWeightedEdge> component : inspector.getConnectedComponents()) {
			final DirectedWeightedGraph dwgComponent = new DirectedWeightedGraph();
			Graphs.addGraph(dwgComponent, component);
			componentList.add(dwgComponent);
		}
		return componentList;
	}

	/**
	 * Return the connected components of this graph as a list of {@link Tree}s
	 * @return the list of Tree objects
	 */
	public List<Tree> getTrees() {
		return getComponents().stream().map(c -> c.getTree(true)).collect(Collectors.toList());
	}

	/**
	 * Returns the subgraph defined by the supplied subset of vertices, including their edges.
	 *
	 * @param nodeSubset a subset of this graph's vertex set
	 * @return the subgraph
	 */
	public DirectedWeightedSubgraph getSubgraph(final Set<SWCPoint> nodeSubset) {
		return new DirectedWeightedSubgraph(this, nodeSubset);
	}

	/**
	 * Returns the subset of vertices contained in the given hemisphere
	 *
	 * @param lr the hemisphere (i.e., {@link BrainAnnotation#LEFT_HEMISPHERE}, {@link BrainAnnotation#RIGHT_HEMISPHERE}
	 *           ,{@link BrainAnnotation#ANY_HEMISPHERE}
	 * @return the vertex subset
	 */
	public Set<SWCPoint> vertexSet(final char lr) {
		if (lr == BrainAnnotation.ANY_HEMISPHERE) return vertexSet();
		final Set<SWCPoint> modifiable = new HashSet<>();
		vertexSet().forEach(vertex -> {
			if (lr == vertex.getHemisphere()) modifiable.add(vertex);
		});
		return modifiable;
	}

	/**
	 * Sets the root of the tree. This modifies the edge directions such that
	 * all other nodes in the graph have the new root as ancestor.
	 *
	 * @param newRoot the new root of the tree, which must be an existing vertex of the graph
	 * @throws IllegalArgumentException if the graph does not contain newRoot
	 */
	public void setRoot(final SWCPoint newRoot) {
		if (!containsVertex(newRoot)) {
			throw new IllegalArgumentException("Node not contained in graph");
		}
		final Deque<SWCPoint> stack = new ArrayDeque<>();
		stack.push(newRoot);
		final Set<SWCPoint> visited = new HashSet<>();
		while (!stack.isEmpty()) {
			final SWCPoint swcPoint = stack.pop();
			visited.add(swcPoint);
			SWCPoint newTarget = null;
			for (final SWCWeightedEdge edge : edgesOf(swcPoint)) {
				if (edge.getSource() == swcPoint) {
					newTarget = edge.getTarget();
				} else if (edge.getTarget() == swcPoint) {
					newTarget = edge.getSource();
				}
				if (visited.contains(newTarget)) continue;
				removeEdge(edge);
				addEdge(swcPoint, newTarget);
				stack.push(newTarget);
			}
		}
	}

	public List<SWCPoint> getNodesFromRootToLeaves() {
		final List<SWCPoint> result = getNodesFromLeavesToRoot();
		Collections.reverse(result); // Reverse the result to get nodes from root to leaves
		return result;
	}

	public List<SWCPoint> getNodesFromLeavesToRoot() {
		final List<SWCPoint> result = new ArrayList<>();
		final Set<SWCPoint> visited = new HashSet<>();
		final Queue<SWCPoint> leafNodes = new LinkedList<>(getTips()); // collect all leaf nodes
		// Process each leaf and its ancestors in sequence
		while (!leafNodes.isEmpty()) {
            SWCPoint currentNode = leafNodes.poll();
			// Follow path from this leaf to root
			while (currentNode != null && !visited.contains(currentNode)) {
				visited.add(currentNode);
				result.add(currentNode);
				currentNode = currentNode.previous();
			}
		}
		return result;
	}

	/**
	 * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
	 *
	 * @return a reference to the displayed window.
	 * @throws IllegalStateException if the graph does not have exactly one root
	 */
	public Window show() {
		updateVertexProperties();
		return GraphUtils.show(this);
	}

}
