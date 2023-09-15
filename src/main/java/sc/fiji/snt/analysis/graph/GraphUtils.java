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

import java.awt.Window;
import java.util.Collection;

import org.jgrapht.Graph;
import org.scijava.util.Colors;

import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.GraphViewer;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Utilities for Graph handling.
 * @author Tiago Ferreira
 */
public class GraphUtils {

	private GraphUtils() {
	}

	@Deprecated
	public static DirectedWeightedGraph createGraph(final Collection<SWCPoint> nodes,
	                                                                              final boolean assignDistancesToWeights) {
		return new DirectedWeightedGraph(nodes, assignDistancesToWeights);
	}

	@Deprecated
	public static DirectedWeightedGraph createGraph(final Tree tree) throws IllegalArgumentException {
		return tree.getGraph();
	}

	/**
	 * Creates a {@link Tree} from a graph.
	 *
	 * @param graph the graph to be converted.
	 * @return the Tree, assembled from the graph vertices
	 */
	@Deprecated
	public static Tree createTree(final Graph<SWCPoint, ?> graph) {
		if (graph instanceof DirectedWeightedGraph) {
			return ((DirectedWeightedGraph)graph).getTree();
		}
		return new Tree(graph.vertexSet(), "");
	}

	@Deprecated
	public static DirectedWeightedGraph getSimplifiedGraph(
			final DirectedWeightedGraph graph) {
		return graph.getSimplifiedGraph();
	}

	/**
	 * Displays a graph in SNT's "Dendrogram Viewer" featuring UI commands for
	 * interactive visualization and export options.
	 *
	 * @param graph the graph to be displayed
	 * @return the assembled window
	 */
	public static Window show(final DirectedWeightedGraph graph) {
		final GraphViewer graphViewer = new GraphViewer(graph);
		if (SNTUtils.getPluginInstance() != null)
			graphViewer.setContext(SNTUtils.getPluginInstance().getContext());
		return graphViewer.show();
	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		SNTUtils.setDebugMode(true);
		SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTree("fractal");
		tree.downSample(Double.MAX_VALUE);
		tree.setColor(Colors.RED);
		final DirectedWeightedGraph graph = tree.getGraph();
		final Viewer3D recViewer = new Viewer3D(ij.context());
		final Tree convertedTree = GraphUtils.createTree(graph);
		convertedTree.setColor(Colors.CYAN);
		recViewer.add(tree);
		recViewer.add(convertedTree);
		recViewer.show();
		GraphUtils.show(graph);
		GraphUtils.show(graph.getSimplifiedGraph());
	}
}
