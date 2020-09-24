/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
package sc.fiji.snt;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import java.util.*;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsUndirectedGraph;
import org.junit.Before;
import org.junit.Test;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.ParanoidGraph;
import org.jgrapht.GraphTests;
import org.jgrapht.Graphs;

import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

/**
 * Tests for {@link DirectedWeightedGraph}
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class DirectedWeightedGraphTest {

	private final double precision = 0.0001;
	private Tree tree;
	private TreeAnalyzer analyzer;

	@Before
	public void setUp() throws Exception {
		tree = new SNTService().demoTrees().get(0);
		analyzer = new TreeAnalyzer(tree);
		assumeNotNull(tree);
	}

	public void testModificationAndConversionToTree() throws InterruptedException {
		Set<SWCPoint> points = new HashSet<>();
		SWCPoint v1 = new SWCPoint(1, 2, 1.0, 1.0, 1.0, 0.5, -1);
		SWCPoint v2 = new SWCPoint(2, 2, 1.0, 4.0, 1.0, 2.515, 1);
		SWCPoint v3 = new SWCPoint(3, 2, 4.0, 4.0, 8.0, 3.2, 2);
		SWCPoint v4 = new SWCPoint(4, 2, 9.0, 12.0, 2.0, 3.2, 3);
		points.add(v1);
		points.add(v2);
		points.add(v3);
		points.add(v4);
		
		Tree tree = new Tree(points, "");

		DirectedWeightedGraph graph = tree.getGraph();
		
		SWCPoint oldRoot = graph.getRoot();
		SWCPoint t = graph.getTips().get(0);
		SWCPoint tp = Graphs.predecessorListOf(graph, t).get(0);
		graph.removeEdge(tp, t);
		
		SWCPoint secondPoint = Graphs.successorListOf(graph, oldRoot).get(0);
		graph.addEdge(secondPoint, t);
		
		SWCPoint newRoot = new SWCPoint(0, 2, 0.5, 0.5, 0.5, 1.0, 0);
		graph.addVertex(newRoot);
		graph.addEdge(newRoot, oldRoot);
		
		Tree changedTree = graph.getTree(true);
		TreeAnalyzer analyzer = new TreeAnalyzer(changedTree);
		
		PointInImage newTreeRoot = changedTree.getRoot();
		assertTrue("Graph to Tree: replace root", newTreeRoot.getX() == 0.5 && newTreeRoot.getY() == 0.5 && newTreeRoot.getZ() == 0.5);
		assertEquals("Graph to Tree: # Branches", 3, analyzer.getBranches().size());
		assertEquals("Graph to Tree: Strahler #", 2, analyzer.getStrahlerNumber());
		assertEquals("Graph to Tree: # Branch Points", 1, analyzer.getBranchPoints().size());
		assertEquals("Graph to Tree: # Tips", 2, analyzer.getTips().size());
		assertEquals(analyzer.getCableLength(), (newRoot.distanceTo(v1) + v1.distanceTo(v2) + v2.distanceTo(v3) + v2.distanceTo(v4)), precision);

	}

	@Test
	public void testDirectedWeightedGraph() throws InterruptedException {

		// First test that #equals() and #hashCode() are correctly set up for the vertex
		// Type
		DefaultDirectedWeightedGraph<SWCPoint, SWCWeightedEdge> badGraph = new DefaultDirectedWeightedGraph<>(
				SWCWeightedEdge.class);
		ParanoidGraph<SWCPoint, SWCWeightedEdge> pGraph = new ParanoidGraph<>(badGraph);
		SWCPoint v1 = new SWCPoint(0, 2, 1.0, 1.0, 1.0, 1.0, 0);
		SWCPoint v2 = new SWCPoint(0, 2, 2.0, 2.0, 2.0, 1.0, 0);
		SWCPoint v3 = v1;
		pGraph.addVertex(v1);
		pGraph.addVertex(v2);
		try {
			boolean addSuccess = pGraph.addVertex(v3);
			assertFalse(addSuccess);
		} catch (IllegalArgumentException ex) {
			ex.printStackTrace();
			fail();
		}

		testModificationAndConversionToTree();

		DirectedWeightedGraph graph = tree.getGraph();

		final int numVertices = graph.vertexSet().size();
		final int numRoots = (int) graph.vertexSet().stream().filter(v -> graph.inDegreeOf(v) == 0).count();
		final int numBranchPoints = graph.getBPs().size();
		final int numTips = graph.getTips().size();
		final double summedEdgeWeights = graph.sumEdgeWeights();
		

		// Compare measurements against TreeAnalyzer since TreeAnalyzer is compared
		// against the hard-coded correct values
		assertEquals("Graph: Equal # Vertices", numVertices, tree.getNodes().size());
		assertEquals("Graph: Single Root", 1, numRoots);
		assertEquals("Graph: Equal # Branch Points", numBranchPoints, analyzer.getBranchPoints().size());
		assertEquals("Graph: Equal # End Points", numTips, analyzer.getTips().size());
		assertEquals("Graph: Summed Edge Weights", summedEdgeWeights, analyzer.getCableLength(), precision);

		// Topology tests
		assertTrue("Graph: Is Simple", GraphTests.isSimple(graph));
		assertTrue("Graph: Is connected", GraphTests.isConnected(graph));
		GraphTests.requireDirected(graph);
		GraphTests.requireWeighted(graph);

		// Graph scaling tests
		for (double scaleFactor : new double[] { .25d, 1d, 2d }) {
			graph.scale(scaleFactor, scaleFactor, scaleFactor, true);
			assertTrue("Graph: Is Simple", GraphTests.isSimple(graph));
			assertTrue("Graph: Is connected", GraphTests.isConnected(graph));
			assertEquals("Graph Scaling: Equal # Tips", graph.getTips().size(), numTips);
			assertEquals("Graph Scaling: Equal # Branch points", graph.getBPs().size(), numBranchPoints);
			assertEquals("Graph Scaling: Equal # Vertices", graph.vertexSet().size(), numVertices);
			assertEquals("Graph Scaling: Equal # Roots", graph.vertexSet().stream().filter(v -> graph.inDegreeOf(v) == 0).count(), numRoots);
			assertEquals("Graph Scaling: Summed Edge Weight", graph.sumEdgeWeights(), summedEdgeWeights * scaleFactor,
					precision);
			graph.scale(1 / scaleFactor, 1 / scaleFactor, 1 / scaleFactor, true);
		}

		// Shortest path tests
		// get undirected view of base graph so we can compute arbitrary shortest paths using JGraphT
		AsUndirectedGraph<SWCPoint, SWCWeightedEdge> undirected = new AsUndirectedGraph<>(graph);
		DijkstraShortestPath<SWCPoint, SWCWeightedEdge> dijkstraShortestPath = new DijkstraShortestPath<>(undirected);

		SWCPoint root = graph.getRoot();
		List<SWCPoint> tips = graph.getTips();

		// brute-force test
//		Set<SWCPoint> nodes = graph.vertexSet();
//		for (SWCPoint n1 : nodes) {
//			for (SWCPoint n2 : nodes) {
//				if (n1 == n2) continue;
//				Path sp = graph.getShortestPath(n1, n2);
//				GraphPath dsp = dijkstraShortestPath.getPath(n1, n2);
//				assertEquals(sp.getLength(), dsp.getWeight(), precision);
//			}
//		}
		for (SWCPoint tip : tips) {
			Path sp = graph.getShortestPath(root, tip);
			GraphPath<SWCPoint, SWCWeightedEdge> dsp = dijkstraShortestPath.getPath(root, tip);
			assertEquals(sp.getLength(), dsp.getWeight(), precision);
			assertEquals(sp.size(), dsp.getVertexList().size());
		}
		for (SWCPoint t1 : tips) {
			for (SWCPoint t2 : tips) {
				if (t1 == t2) continue;
				Path sp = graph.getShortestPath(t1, t2);
				GraphPath<SWCPoint, SWCWeightedEdge> dsp = dijkstraShortestPath.getPath(t1, t2);
				assertEquals(sp.getLength(), dsp.getWeight(), precision);
				assertEquals(sp.size(), dsp.getVertexList().size());
			}
		}
		// path between the same points should be null
		assertNull(graph.getShortestPath(root, root));
	}

}
