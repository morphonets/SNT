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

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.PointInImage;

/**
 * Tests for {@link PathStatistics}.
 */
public class PathStatisticsTest {

	private static final double DELTA = 1e-6;
	private Tree tree;
	private Path primaryPath;

	@Before
	public void setUp() {
		tree = new SNTService().demoTree("fractal");
		assumeNotNull(tree);
		// Get the primary (root) path
		final TreeStatistics tStats = new TreeStatistics(tree);
		final List<Path> primaries = tStats.getPrimaryPaths();
		assumeNotNull(primaries);
		assertFalse(primaries.isEmpty());
		primaryPath = primaries.get(0);
	}

	@Test
	public void testConstructor_singlePath() {
		final PathStatistics stats = new PathStatistics(primaryPath);
		assertNotNull(stats);
		assertEquals(1, stats.getNBranches());
	}

	@Test
	public void testConstructor_pathCollection() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		assertNotNull(stats);
		assertEquals(tree.size(), stats.getNBranches());
	}

	@Test
	public void testGetBranches_count() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		assertEquals(tree.size(), stats.getBranches().size());
	}

	@Test
	public void testGetNBranches() {
		final PathStatistics stats = new PathStatistics(primaryPath);
		assertEquals(1, stats.getNBranches());
	}

	@Test
	public void testGetMetric_pathId_singlePath() throws UnknownMetricException {
		final PathStatistics stats = new PathStatistics(primaryPath);
		final Number id = stats.getMetric("Path ID");
		assertNotNull(id);
		assertEquals(primaryPath.getID(), id.intValue());
	}

	@Test
	public void testGetMetric_pathId_multiPath() throws UnknownMetricException {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final Number id = stats.getMetric("Path ID");
		assertTrue("Multi-path should return NaN for Path ID", Double.isNaN(id.doubleValue()));
	}

	@Test
	public void testGetMetric_byPath_length() throws UnknownMetricException {
		final PathStatistics stats = new PathStatistics(primaryPath);
		final Number length = stats.getMetric(PathStatistics.PATH_LENGTH, primaryPath);
		assertNotNull(length);
		assertEquals(primaryPath.getLength(), length.doubleValue(), DELTA);
	}

	@Test
	public void testGetMetric_byPath_nNodes() throws UnknownMetricException {
		final PathStatistics stats = new PathStatistics(primaryPath);
		final Number nNodes = stats.getMetric(PathStatistics.N_PATH_NODES, primaryPath);
		assertNotNull(nNodes);
		assertEquals(primaryPath.size(), nNodes.intValue());
	}

	@Test
	public void testGetMetric_byPath_nChildren() throws UnknownMetricException {
		final PathStatistics stats = new PathStatistics(primaryPath);
		final Number nChildren = stats.getMetric(PathStatistics.N_CHILDREN, primaryPath);
		assertNotNull(nChildren);
		assertEquals(primaryPath.getChildren().size(), nChildren.intValue());
	}

	@Test
	public void testGetPrimaryBranches_containsPrimary() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final List<Path> primaries = stats.getPrimaryBranches();
		assertNotNull(primaries);
		assertTrue(primaries.contains(primaryPath));
	}

	@Test
	public void testGetTerminalBranches_nonEmpty() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final List<Path> terminals = stats.getTerminalBranches();
		assertNotNull(terminals);
		// In PathStatistics, terminal branches are those with children
		for (final Path p : terminals) {
			assertFalse("Terminal branch should have children", p.getChildren().isEmpty());
		}
	}

	@Test
	public void testGetInnerBranches_samePrimary() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		assertEquals(stats.getPrimaryBranches(), stats.getInnerBranches());
	}

	@Test
	public void testGetPrimaryLength() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final double primaryLen = stats.getPrimaryLength();
		assertTrue("Primary length should be >= 0", primaryLen >= 0);
	}

	@Test
	public void testGetTerminalLength() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final double terminalLen = stats.getTerminalLength();
		assertTrue("Terminal length should be >= 0", terminalLen >= 0);
	}

	@Test
	public void testGetInnerLength_equalsGetPrimaryLength() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		assertEquals(stats.getPrimaryLength(), stats.getInnerLength(), DELTA);
	}

	@Test
	public void testGetCableLength_multiPath() {
		final PathStatistics stats = new PathStatistics(tree.list(), "test");
		final double totalLength = tree.list().stream().mapToDouble(Path::getLength).sum();
		assertEquals("Cable length should equal sum of path lengths", totalLength, stats.getCableLength(), DELTA);
	}

	@Test
	public void testSinglePathCableLength_matchesPathLength() {
		final PathStatistics stats = new PathStatistics(primaryPath);
		assertEquals(primaryPath.getLength(), stats.getCableLength(), DELTA);
	}

	@Test
	public void testManualPathWithKnownLength() {
		final Path p = new Path(1.0, 1.0, 1.0, "um");
		p.addNode(new PointInImage(0, 0, 0));
		p.addNode(new PointInImage(3, 4, 0));
		final PathStatistics stats = new PathStatistics(p);
		assertEquals(5.0, stats.getCableLength(), DELTA);
	}
}
