/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.awt.Window;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.GraphTests;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.traverse.DepthFirstIterator;

import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.NodeStatistics;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.SWCPoint;

/**
 * Class for accessing a reconstruction as a graph structure.
 * 
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class DirectedWeightedGraph extends DefaultDirectedWeightedGraph<SWCPoint, SWCWeightedEdge> {

	private static final long serialVersionUID = 1L;
	private Tree tree;

	/**
	 * Creates a DirectedWeightedGraph from a Tree with edge weights corresponding
	 * to inter-node distances.
	 *
	 * @param tree the Tree to be converted
	 * @throws IllegalArgumentException if Tree contains multiple roots
	 */
	public DirectedWeightedGraph(final Tree tree) throws IllegalArgumentException {
		super(SWCWeightedEdge.class);
		this.tree = tree;
		init(tree.getNodesAsSWCPoints(), true);
	}

	protected DirectedWeightedGraph() {
		super(SWCWeightedEdge.class);
		this.tree = null;
	}

	/**
	 * Creates a DirectedWeightedGraph from a collection of reconstruction nodes.
	 *
	 * @param nodes                    the collections of SWC nodes
	 * @param assignDistancesToWeights if true, inter-node Euclidean distances are
	 *                                 used as edge weights
	 */
	protected DirectedWeightedGraph(final Collection<SWCPoint> nodes, final boolean assignDistancesToWeight)
			throws IllegalArgumentException {
		this();
		init(nodes, assignDistancesToWeight);
	}

	private void init(final Collection<SWCPoint> nodes, final boolean assignDistancesToWeights)
			throws IllegalArgumentException {
		final Map<Integer, SWCPoint> map = new HashMap<>();
		for (final SWCPoint node : nodes) {
			map.put(node.id, node);
			addVertex(node);
		}
		for (final SWCPoint node : nodes) {
			if (node.parent == -1)
				continue;
			final SWCPoint previousPoint = map.get(node.parent);
			node.setPreviousPoint(previousPoint);
			final SWCWeightedEdge edge = new SWCWeightedEdge();
			addEdge(previousPoint, node, edge);
			if (assignDistancesToWeights) {
				setEdgeWeight(edge, node.distanceTo(previousPoint));
			}
		}
	}

	/**
	 * Returns a simplified version graph in which slab nodes are removed and graph
	 * is represented only by root, branch nodes and leaves.
	 *
	 * @return the simplified graph
	 */
	public DirectedWeightedGraph getSimplifiedGraph() {
		final LinkedHashSet<SWCPoint> relevantNodes = new LinkedHashSet<>();
		relevantNodes.add(getRoot());
		relevantNodes.addAll(getBPs());
		relevantNodes.addAll(getTips());
		final DirectedWeightedGraph simplifiedGraph = new DirectedWeightedGraph();
		transferCommonProperties(simplifiedGraph);
		relevantNodes.forEach(node -> simplifiedGraph.addVertex(node));
		for (final SWCPoint node : relevantNodes) {
			final SimplifiedVertex ancestor = firstRelevantAncestor(node);
			if (ancestor != null && ancestor.associatedWeight > 0) {
				try {
					final SWCWeightedEdge edge = simplifiedGraph.addEdge(ancestor.vertex, node);
					simplifiedGraph.setEdgeWeight(edge, ancestor.associatedWeight);
				} catch (final IllegalArgumentException ignored) {
					// do nothing. ancestor.vertex not found in simplifiedGraph
				}
			}
		}
		simplifiedGraph.updateVertexProperties();
		return simplifiedGraph;
	}

	private SimplifiedVertex firstRelevantAncestor(SWCPoint node) {
		if (!Graphs.vertexHasPredecessors(this, node)) {
			return null;
		}
		double pathWeight = 0;
		SWCPoint parent;
		while (true) {
			try {
				parent = Graphs.predecessorListOf(this, node).get(0);
				final double edgeWeight = getEdge(parent, node).getWeight();
				pathWeight += edgeWeight;
				if (inDegreeOf(parent) == 0 || outDegreeOf(parent) > 1) {
					return new SimplifiedVertex(parent, pathWeight);
				}
				node = parent;
			} catch (final IndexOutOfBoundsException | NullPointerException ignored) {
				return null;
			}
		}
	}

	private class SimplifiedVertex {
		final SWCPoint vertex;
		final double associatedWeight;

		SimplifiedVertex(final SWCPoint vertex, final double associatedWeight) {
			this.vertex = vertex;
			this.associatedWeight = associatedWeight;
		}
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
	 * Scales each vertex SWCVertex by the specified factors.
	 *
	 * @param xScale                     the scaling factor for x coordinates
	 * @param yScale                     the scaling factor for y coordinates
	 * @param zScale                     the scaling factor for z coordinates
	 * @param updateEdgeWeightsEuclidean if true, update all edge weights with
	 *                                   inter-node Euclidean distances
	 */
	public void scale(final double xScale, final double yScale, final double zScale, final boolean updateEdgeWeightsEuclidean) {
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
		return edgeSet().stream().mapToDouble(e -> e.getWeight()).sum();
	}

	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator() {
		return new DepthFirstIterator<SWCPoint, SWCWeightedEdge>(this);
	}

	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator(final SWCPoint startVertex) {
		return new DepthFirstIterator<SWCPoint, SWCWeightedEdge>(this, startVertex);
	}

	/**
	 * Re-assigns a unique Integer identifier to each vertex based on visit order
	 * during Depth First Search. Also updates the parent and previousPoint fields
	 * of each SWCvertex contained in the Graph.
	 */
	private void updateVertexProperties() {
		final DepthFirstIterator<SWCPoint, SWCWeightedEdge> iter = getDepthFirstIterator(getRoot());
		int currentId = 1;
		while (iter.hasNext()) {
			final SWCPoint v = iter.next();
			v.id = currentId;
			currentId++;
			final List<SWCPoint> parentList = Graphs.predecessorListOf(this, v);
			if (!parentList.isEmpty()) {
				v.parent = parentList.get(0).id;
				v.setPreviousPoint(parentList.get(0));
			} else {
				v.parent = -1;
				v.setPreviousPoint(null);
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

	public NodeStatistics<SWCPoint> getNodeStatistics() {
		return getNodeStatistics("all");
	}

	public NodeStatistics<SWCPoint> getNodeStatistics(final String type) {
		switch(type.toLowerCase()) {
		case "all":
			return new NodeStatistics<SWCPoint>(vertexSet());
		case "tips":
		case "endings":
		case "end points":
		case "end-points":
		case "terminals":
			return new NodeStatistics<SWCPoint>(getTips());
		case "bps":
		case "forks":
		case "junctions":
		case "fork points":
		case "junction points":
		case "branch points":
			return new NodeStatistics<SWCPoint>(getBPs());
		default:
			throw new IllegalArgumentException("type not recognized. Perhaps you meant 'all', 'junctions' or 'tips'?");
		}
	}

	/**
	 * Gets the root of this graph.
	 *
	 * @return the root node.
	 */
	public SWCPoint getRoot() {
		if (vertexSet().isEmpty()) return null;
		if (!GraphTests.isConnected(this)) {
			throw new IllegalStateException("Graph has multiple connected components");
		}
		return vertexSet().stream().filter(v -> inDegreeOf(v) == 0).findFirst().orElse(null);
	}

	/**
	 * Reassembles a tree from this graph's data.
	 *
	 * @return the reassembled tree
	 */
	public Tree getTree() {
		updateVertexProperties();
		return new Tree(this.vertexSet(), "");
	}

	public Set<SWCPoint> vertexSet(final char lr) {
		if (lr == BrainAnnotation.ANY_HEMISPHERE) return vertexSet();
		final Set<SWCPoint> modifiable = new HashSet<SWCPoint>();
		vertexSet().forEach(vertex -> {
			if (lr == vertex.getHemisphere()) modifiable.add(vertex);
		});
		return modifiable;
	}

	/**
	 * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
	 *
	 * @return a reference to the displayed window.
	 */
	public Window show() {
		updateVertexProperties();
		return GraphUtils.show(this);
	}

}
