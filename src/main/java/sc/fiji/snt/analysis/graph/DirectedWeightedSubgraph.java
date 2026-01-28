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

import java.awt.Window;
import java.util.*;
import java.util.stream.Collectors;

import org.jgrapht.GraphTests;
import org.jgrapht.Graphs;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.traverse.DepthFirstIterator;

import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.NodeStatistics;
import sc.fiji.snt.util.SWCPoint;

/**
 * Class for accessing the sub-graph that has a subset of vertices and a subset
 * of edges of a {@link DirectedWeightedGraph}.
 * 
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class DirectedWeightedSubgraph extends AsSubgraph<SWCPoint, SWCWeightedEdge> {

	private static final long serialVersionUID = 1L;
	private final DirectedWeightedGraph graph;
	private String label;

	/**
	 * Creates a subgraph from a sub-set of nodes.
	 *
	 * @param graph      the graph from which {@code nodeSubset} were extracted.
	 * @param nodeSubset the sub-set of nodes that define the sub-graph.
	 */
	protected DirectedWeightedSubgraph(final DirectedWeightedGraph graph, final Set<SWCPoint> nodeSubset) {
		super(graph, nodeSubset);
		this.graph = graph;
	}

	/**
	 * Sets the root of the tree. This modifies the edge directions such that
	 * all other nodes in the graph have the new root as ancestor.
	 *
	 * @param newRoot the new root of the tree, which must be an existing vertex of the graph
	 * @throws IllegalArgumentException if the graph does not contain newRoot
	 */
	public void setRoot(final SWCPoint newRoot) {
		graph.setRoot(newRoot);
	}

	/**
	 * Gets the sum of all edge weights.
	 *
	 * @param adjusted whether edges of parent graph should be taken into account
	 * @return the sum of all edge weights
	 */
	public double sumEdgeWeights(final boolean adjusted) {
		double totalWeight = 0d;
		for (final SWCWeightedEdge edge : edgeSet()) {
			totalWeight += edge.getWeight();
		}
		if (adjusted) {
			// Now account for missing edges that cross compartment boundaries
			final List<SWCPoint> rootList = vertexSet().stream().filter(v -> inDegreeOf(v) == 0).collect(Collectors.toList());
			for (final SWCPoint root : rootList) {
				final List<SWCPoint> parent = Graphs.predecessorListOf(graph, root);
				if (!parent.isEmpty()) {
					totalWeight += graph.getEdge(parent.get(0), root).getWeight();
				}
			}
		}
		return totalWeight;
	}

	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator() {
		return new DepthFirstIterator<>(this);
	}

	public DepthFirstIterator<SWCPoint, SWCWeightedEdge> getDepthFirstIterator(final SWCPoint startVertex) {
		return new DepthFirstIterator<>(this, startVertex);
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
	 * Gets the end points (tips) of the subgraph.
	 *
	 * @return the list of end points
	 */
	public List<SWCPoint> getTips() {
		return vertexSet().stream().filter(v -> outDegreeOf(v) == 0).collect(Collectors.toList());
	}

	/**
	 * Gets the root of this graph.
	 *
	 * @return the root node.
	 */
	public SWCPoint getRoot() {
		if (vertexSet().isEmpty())
			return null;
		if (!GraphTests.isConnected(this)) {
			throw new IllegalStateException("Graph has multiple connected components");
		}
		return vertexSet().stream().filter(v -> inDegreeOf(v) == 0).findFirst().orElse(null);
	}

	public NodeStatistics<SWCPoint> getNodeStatistics(final String type) {
		switch(type.toLowerCase()) {
		case "all":
			return new NodeStatistics<>(vertexSet());
		case "tips":
		case "endings":
		case "end points":
		case "terminals":
			return new NodeStatistics<>(getTips());
		case "bps":
		case "forks":
		case "junctions":
		case "fork points":
		case "junction points":
		case "branch points":
			return new NodeStatistics<>(getBPs());
		default:
			throw new IllegalArgumentException("type not recognized. Perhaps you meant 'all', 'junctions' or 'tips'?");
		}
	}

	/**
	 * Returns the tree associated with the parent's graph
	 *
	 * @return the associated tree
	 */
	public Tree getTree() {
		return graph.getTree();
	}

	/**
	 * Displays this graph in a new instance of SNT's "Dendrogram Viewer".
	 *
	 * @return a reference to the displayed window.
	 */
	public Window show() {
		// TODO decide what to do here
		//return GraphUtils.show(this);
		throw new UnsupportedOperationException();
	}

	/**
	 * Sets an identifying label for this subgraph.
	 *
	 * @param label the identifying string
	 */
	public void setLabel(final String label) {
		this.label = label;
	}

	/**
	 * Returns the label identifying this subgraph.
	 *
	 * @return the label (or null) if none has been set.
	 */
	public String getLabel() {
		return label;
	}

}
