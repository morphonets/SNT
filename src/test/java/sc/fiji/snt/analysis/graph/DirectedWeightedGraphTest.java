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

import static org.junit.Assert.*;

import java.util.*;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsUndirectedGraph;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.ParanoidGraph;
import org.jgrapht.GraphTests;
import org.jgrapht.Graphs;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

/**
 * Tests for {@link DirectedWeightedGraph}
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class DirectedWeightedGraphTest {

	private final double precision = 1e-6;
	private Tree tree;
	private TreeStatistics analyzer;
	private DirectedWeightedGraph graph;

	@Before
	public void setUp() throws Exception {
//		tree = new MouseLightLoader("AA0103").getTree();
		tree = new SNTService().demoTrees().get(0);
		analyzer = new TreeStatistics(tree);
		graph = tree.getGraph();
	}

	@Test
	public void testVertexEquality() {
		final DefaultDirectedWeightedGraph<SWCPoint, SWCWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(
				SWCWeightedEdge.class);
		final ParanoidGraph<SWCPoint, SWCWeightedEdge> pGraph = new ParanoidGraph<>(graph);
		final SWCPoint v1 = new SWCPoint(0, 2, 1.0, 1.0, 1.0, 1.0, 0);
		final SWCPoint v2 = new SWCPoint(0, 2, 1.0, 1.0, 1.0, 1.0, 0);
		final SWCPoint v3 = v1;
		pGraph.addVertex(v1);
		boolean added = pGraph.addVertex(v2);
		assertTrue(added);
		added = pGraph.addVertex(v3);
		assertFalse(added);
	}

	@Test
	public void testGraphProperties() {
		final int numRoots = (int) graph.vertexSet().stream().filter(v -> graph.inDegreeOf(v) == 0).count();
		// Compare measurements against TreeStatistics since TreeStatistics is compared
		// against the hard-coded correct values
		assertEquals(tree.getNodes().size(), graph.vertexSet().size());
		assertEquals(1, numRoots);
		assertEquals(analyzer.getBranchPoints().size(), graph.getBPs().size());
		assertEquals(analyzer.getTips().size(), graph.getTips().size());
		assertEquals(analyzer.getCableLength(), graph.sumEdgeWeights(), precision);
	}

	@Test
	public void testTopology() {
		// Topology tests
		assertTrue(GraphTests.isSimple(graph));
		assertTrue(GraphTests.isConnected(graph));
		GraphTests.requireDirected(graph);
		GraphTests.requireWeighted(graph);
	}

	@Test
	public void testScaling() {
		// Graph scaling tests
		final double summedEdgeWeights = graph.sumEdgeWeights();
		for (final double scaleFactor : new double[] { 0.01, 0.25, 1.0, 2.0, 5.0 }) {
			graph.scale(scaleFactor, scaleFactor, scaleFactor, true);
			assertTrue(GraphTests.isSimple(graph));
			assertTrue(GraphTests.isConnected(graph));
			assertEquals(analyzer.getTips().size(), graph.getTips().size());
			assertEquals(analyzer.getBranchPoints().size(), graph.getBPs().size());
			assertEquals(tree.getNodes().size(), graph.vertexSet().size());
			assertEquals(1, graph.vertexSet().stream().filter(v -> graph.inDegreeOf(v) == 0).count());
			assertEquals(summedEdgeWeights * scaleFactor, graph.sumEdgeWeights(), precision);
			graph.scale(1 / scaleFactor, 1 / scaleFactor, 1 / scaleFactor, true);
		}
	}

	@Test
	public void testLongestPathDirected() {
		// Compare against JGraphT Dijkstra implementation
		final DijkstraShortestPath<SWCPoint, SWCWeightedEdge> dijkstraShortestPath = new DijkstraShortestPath<>(graph);

		final SWCPoint root = graph.getRoot();
		final List<SWCPoint> tips = graph.getTips();

		GraphPath<SWCPoint, SWCWeightedEdge> longestPathDSP = null;
		double longestPathDSPWeight = 0;
		for (final SWCPoint tip : tips) {
			final GraphPath<SWCPoint, SWCWeightedEdge> dsp = dijkstraShortestPath.getPath(root, tip);
			final double pathWeight = dsp.getWeight();
			if (pathWeight > longestPathDSPWeight) {
				longestPathDSP = dsp;
				longestPathDSPWeight = pathWeight;
			}
		}
		assertNotNull(longestPathDSP);
		final List<SWCPoint> longestPathDSPVertices = longestPathDSP.getVertexList();

		final Path longestPathSNT = graph.getLongestPath(true);
		assertNotNull(longestPathSNT);
		final Deque<SWCPoint> longestPathSNTVertices = graph.getLongestPathVertices(true);
		assertNotNull(longestPathSNTVertices);

		assertEquals(longestPathDSP.getWeight(), longestPathSNT.getLength(), precision);
		assertEquals(longestPathDSPVertices.size(), longestPathSNT.size());
		assertEquals(longestPathDSPVertices.size(), longestPathSNTVertices.size());

		// PointInImage and SWCPoint are not directly comparable, so use node location as proxy
		for (int i = 0; i < longestPathSNT.size(); i++) {
			assertTrue(longestPathDSPVertices.get(i).isSameLocation(longestPathSNT.getNode(i)));
		}

		final Iterator<SWCPoint> longestPathSNTVerticesIterator = longestPathSNTVertices.iterator();
		for (final SWCPoint swcPoint : longestPathDSPVertices) {
			assertEquals(swcPoint, longestPathSNTVerticesIterator.next());
		}
	}

	@Test
	public void testLongestPathUndirected() {
		// Compare against JGraphT Dijkstra implementation
		// get undirected view of base graph so we can compute arbitrary shortest paths using JGraphT
		final AsUndirectedGraph<SWCPoint, SWCWeightedEdge> undirected = new AsUndirectedGraph<>(graph);
		final DijkstraShortestPath<SWCPoint, SWCWeightedEdge> dijkstraShortestPath = new DijkstraShortestPath<>(undirected);

		final SWCPoint root = graph.getRoot();
		final List<SWCPoint> tips = graph.getTips();
		tips.add(root);

		GraphPath<SWCPoint, SWCWeightedEdge> longestPathDSP = null;
		double longestPathDSPWeight = 0;
		for (final SWCPoint tip1 : tips) {
			for (final SWCPoint tip2 : tips) {
				if (tip1 == tip2) continue;
				final GraphPath<SWCPoint, SWCWeightedEdge> dsp = dijkstraShortestPath.getPath(tip1, tip2);
				final double pathWeight = dsp.getWeight();
				if (pathWeight > longestPathDSPWeight) {
					longestPathDSP = dsp;
					longestPathDSPWeight = pathWeight;
				}
			}
		}
		assertNotNull(longestPathDSP);
		final List<SWCPoint> longestPathDSPVertices = longestPathDSP.getVertexList();

		final Path longestPathSNT = graph.getLongestPath(false);
		assertNotNull(longestPathSNT);
		final Deque<SWCPoint> longestPathSNTVertices = graph.getLongestPathVertices(false);
		assertNotNull(longestPathSNTVertices);

		assertEquals(longestPathDSP.getWeight(), longestPathSNT.getLength(), precision);
		assertEquals(longestPathDSPVertices.size(), longestPathSNT.size());
		assertEquals(longestPathDSPVertices.size(), longestPathSNTVertices.size());

		// In the undirected case, it is possible for the dijkstra and SNT longest paths to be opposites of each other.
		// This is not a bug, just need to check which case occurred.
		if (longestPathDSPVertices.get(0).isSameLocation(longestPathSNT.getNode(0))) {
			// PointInImage and SWCPoint are not directly comparable, so use node location as proxy
			for (int i = 0; i < longestPathSNT.size(); i++) {
				assertTrue(longestPathDSPVertices.get(i).isSameLocation(longestPathSNT.getNode(i)));
			}
			final Iterator<SWCPoint> longestPathSNTVerticesIterator = longestPathSNTVertices.iterator();
			for (final SWCPoint swcPoint : longestPathDSPVertices) {
				assertEquals(swcPoint, longestPathSNTVerticesIterator.next());
			}
		} else if (longestPathDSPVertices.get(0).isSameLocation(
				longestPathSNT.getNode(longestPathSNT.size() - 1))) {
			// PointInImage and SWCPoint are not directly comparable, so use node location as proxy
			for (int i = 0; i < longestPathSNT.size(); i++) {
				assertTrue(longestPathDSPVertices.get(i).isSameLocation(
						longestPathSNT.getNode(longestPathSNT.size() - (i + 1))));
			}
			final Iterator<SWCPoint> longestPathSNTVerticesIterator = longestPathSNTVertices.descendingIterator();
			for (final SWCPoint swcPoint : longestPathDSPVertices) {
				assertEquals(swcPoint, longestPathSNTVerticesIterator.next());
			}
		} else {
			// If we get here, something is wrong.
			fail();
		}
	}

	@Ignore
	@Test
	public void testShortestPath() {
		// Compare against JGraphT Dijkstra implementation
		// get undirected view of base graph so we can compute arbitrary shortest paths using JGraphT
		final AsUndirectedGraph<SWCPoint, SWCWeightedEdge> undirected = new AsUndirectedGraph<>(graph);
		final DijkstraShortestPath<SWCPoint, SWCWeightedEdge> dijkstraShortestPath = new DijkstraShortestPath<>(undirected);

		// brute-force test
		final Set<SWCPoint> nodes = graph.vertexSet();
		//final long time0 = System.nanoTime();
		for (final SWCPoint n1 : nodes) {
			for (final SWCPoint n2 : nodes) {
				if (n1 == n2) continue;
				final Path sp = graph.getShortestPath(n1, n2);
				final Deque<SWCPoint> spDeque = graph.getShortestPathVertices(n1, n2);
				final Iterator<SWCPoint> spDequeIterator = spDeque.iterator();

				final GraphPath<SWCPoint, SWCWeightedEdge> dsp = dijkstraShortestPath.getPath(n1, n2);
				final List<SWCPoint> dspVertexList = dsp.getVertexList();

				assertEquals(dspVertexList.size(), sp.size());
				assertEquals(dspVertexList.size(), spDeque.size());

				for (int i = 0; i < dspVertexList.size(); i++) {
					assertEquals(dspVertexList.get(i), spDequeIterator.next());
					assertTrue(dspVertexList.get(i).isSameLocation(sp.getNode(i)));
				}

				assertEquals(sp.getLength(), dsp.getWeight(), precision);
			}
		}
		//final long time1 = System.nanoTime();
		//System.out.println((time1 - time0) / 1e9);

		// path between the same points should be null
		final SWCPoint sameNode = nodes.iterator().next();
		assertNull(graph.getShortestPath(sameNode, sameNode));
	}

	@Test
	public void testSimplifiedGraph() {
		final int numBranchPoints = analyzer.getBranchPoints().size();
		final int numTips = analyzer.getTips().size();
		final PointInImage root = tree.getRoot();
		final DirectedWeightedGraph simplifiedGraph = tree.getGraph(true);
		final Tree simplifiedTree = simplifiedGraph.getTree(true);
		final TreeStatistics simplifiedAnalyzer = new TreeStatistics(simplifiedTree);
		// The demo tree root is also a branch point, so exclude it from the count
		assertEquals(numBranchPoints + numTips, simplifiedGraph.vertexSet().size());
		assertEquals(numBranchPoints, simplifiedGraph.getBPs().size());
		assertEquals(numTips, simplifiedGraph.getTips().size());
		assertTrue(root.isSameLocation(simplifiedGraph.getRoot()));
		assertEquals(analyzer.getNBranches(), simplifiedAnalyzer.getNBranches());
		assertEquals(analyzer.getStrahlerNumber(), simplifiedAnalyzer.getStrahlerNumber());
		assertEquals(analyzer.getTerminalBranches().size(), simplifiedAnalyzer.getTerminalBranches().size());
		assertEquals(analyzer.getPrimaryPaths().size(), simplifiedAnalyzer.getPrimaryPaths().size());
		assertEquals(analyzer.getAvgPartitionAsymmetry(), simplifiedAnalyzer.getAvgPartitionAsymmetry(), precision);
		assertEquals(analyzer.getStrahlerBifurcationRatio(), simplifiedAnalyzer.getStrahlerBifurcationRatio(), precision);
	}

	@Test
	public void testGraphToTree() {
		final Tree newTree = graph.getTree(true);
		final TreeStatistics newAnalyzer = new TreeStatistics(newTree);
		assertEquals(tree.getNodes().size(), newTree.getNodes().size());
		assertEquals(analyzer.getCableLength(), newAnalyzer.getCableLength(), precision);
		assertEquals(analyzer.getNBranches(), newAnalyzer.getNBranches());
		assertEquals(analyzer.getBranchPoints().size(), newAnalyzer.getBranchPoints().size());
		assertEquals(analyzer.getTips().size(), newAnalyzer.getTips().size());
		assertEquals(analyzer.getAvgBranchLength(), newAnalyzer.getAvgBranchLength(), precision);
		assertEquals(analyzer.getAvgRemoteBifAngle(), newAnalyzer.getAvgRemoteBifAngle(), precision);
		assertEquals(analyzer.getStrahlerNumber(), newAnalyzer.getStrahlerNumber());
		assertEquals(analyzer.getAvgContraction(), newAnalyzer.getAvgContraction(), precision);
		assertEquals(analyzer.getAvgFractalDimension(), newAnalyzer.getAvgFractalDimension(), precision);
		assertEquals(analyzer.getAvgPartitionAsymmetry(), newAnalyzer.getAvgPartitionAsymmetry(), precision);
		assertEquals(analyzer.getHeight(), newAnalyzer.getHeight(), precision);
		assertEquals(analyzer.getDepth(), newAnalyzer.getDepth(), precision);
		assertEquals(analyzer.getWidth(), newAnalyzer.getWidth(), precision);
		assertEquals(analyzer.getInnerBranches().size(), newAnalyzer.getInnerBranches().size());
		assertEquals(analyzer.getInnerLength(), newAnalyzer.getInnerLength(), precision);
		assertEquals(analyzer.getPrimaryPaths().size(), newAnalyzer.getPrimaryPaths().size());
		assertEquals(analyzer.getPrimaryLength(), newAnalyzer.getPrimaryLength(), precision);
		assertEquals(analyzer.getTerminalBranches().size(), newAnalyzer.getTerminalBranches().size());
		assertEquals(analyzer.getTerminalLength(), newAnalyzer.getTerminalLength(), precision);
	}

	@Test
	public void testModificationAndConversionToTree() {
		final Set<SWCPoint> points = new HashSet<>();
		final SWCPoint v1 = new SWCPoint(1, 2, 1.0, 1.0, 1.0, 0.5, -1);
		final SWCPoint v2 = new SWCPoint(2, 2, 1.0, 4.0, 1.0, 2.515, 1);
		final SWCPoint v3 = new SWCPoint(3, 2, 4.0, 4.0, 8.0, 3.2, 2);
		final SWCPoint v4 = new SWCPoint(4, 2, 9.0, 12.0, 2.0, 3.2, 3);
		points.add(v1);
		points.add(v2);
		points.add(v3);
		points.add(v4);
		final Tree tree = new Tree(points, "");
		final DirectedWeightedGraph graph = tree.getGraph();
		final SWCPoint oldRoot = graph.getRoot();
		final SWCPoint tip = graph.getTips().get(0);
		final SWCPoint tipParent = Graphs.predecessorListOf(graph, tip).get(0);
		graph.removeEdge(tipParent, tip);
		final SWCPoint secondPoint = Graphs.successorListOf(graph, oldRoot).get(0);
		graph.addEdge(secondPoint, tip);
		final SWCPoint newRoot = new SWCPoint(0, 2, 0.5, 0.5, 0.5, 1.0, 0);
		graph.addVertex(newRoot);
		graph.addEdge(newRoot, oldRoot);
		final Tree changedTree = graph.getTree(true);
		final TreeStatistics changedAnalyzer = new TreeStatistics(changedTree);
		final PointInImage newTreeRoot = changedTree.getRoot();
		assertTrue(newTreeRoot.getX() == 0.5 &&
				newTreeRoot.getY() == 0.5 && newTreeRoot.getZ() == 0.5);
		assertEquals(5, changedTree.getNodes().size());
		assertEquals(3, changedAnalyzer.getBranches().size());
		assertEquals( 2, changedAnalyzer.getStrahlerNumber());
		assertEquals(1, changedAnalyzer.getBranchPoints().size());
		assertEquals( 2, changedAnalyzer.getTips().size());
		assertEquals(newRoot.distanceTo(v1) + v1.distanceTo(v2) + v2.distanceTo(v3) +
				v2.distanceTo(v4), changedAnalyzer.getCableLength(), precision);
	}

}
