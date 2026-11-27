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
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

/**
 * Tests for {@link StrahlerAnalyzer}.
 */
public class StrahlerAnalyzerTest {

	private static final double DELTA = 1e-6;
	private Tree tree;
	private StrahlerAnalyzer analyzer;

	@Before
	public void setUp() {
		tree = new SNTService().demoTree("fractal");
		assumeNotNull(tree);
		analyzer = new StrahlerAnalyzer(tree);
	}

	@Test
	public void testGetRootNumber() {
		final int rootNumber = analyzer.getRootNumber();
		assertTrue("Root number should be >= 1", rootNumber >= 1);
		// For the fractal demo tree we know the Strahler number is 5
		assertEquals(5, rootNumber);
	}

	@Test
	public void testGetHighestBranchOrder() {
		final int highest = analyzer.getHighestBranchOrder();
		assertTrue("Highest branch order should be >= 1", highest >= 1);
		assertTrue("Highest branch order should be <= root number", highest <= analyzer.getRootNumber());
	}

	@Test
	public void testGetBranchCounts_keysMatchOrders() {
		final Map<Integer, Double> counts = analyzer.getBranchCounts();
		assertFalse("Branch counts map should not be empty", counts.isEmpty());
		for (final int order : counts.keySet()) {
			assertTrue("Order should be >= 1", order >= 1);
			assertTrue("Order should be <= root number", order <= analyzer.getRootNumber());
		}
	}

	@Test
	public void testGetBranchCounts_valuesPositive() {
		final Map<Integer, Double> counts = analyzer.getBranchCounts();
		for (final double count : counts.values()) {
			assertTrue("Branch count should be >= 0", count >= 0);
		}
	}

	@Test
	public void testGetLengths_keysMatchOrders() {
		final Map<Integer, Double> lengths = analyzer.getLengths();
		assertFalse("Lengths map should not be empty", lengths.isEmpty());
		for (final int order : lengths.keySet()) {
			assertTrue("Order should be >= 1", order >= 1);
		}
	}

	@Test
	public void testGetLengths_sumEqualsOrLessThanCableLength() {
		final Map<Integer, Double> lengths = analyzer.getLengths();
		final double sumLength = lengths.values().stream().mapToDouble(d -> d).sum();
		final double cableLength = new TreeStatistics(tree).getCableLength();
		// The sum of per-order cable lengths should be <= total cable length
		// (some edges between different orders are not counted in per-order sums)
		assertTrue("Sum of per-order lengths should be <= cable length", sumLength <= cableLength + DELTA);
	}

	@Test
	public void testGetBranchPointCounts_keysMatchOrders() {
		final Map<Integer, Double> bpCounts = analyzer.getBranchPointCounts();
		assertFalse("Branch point counts map should not be empty", bpCounts.isEmpty());
		for (final int order : bpCounts.keySet()) {
			assertTrue("Order should be >= 1", order >= 1);
		}
	}

	@Test
	public void testGetBranchPointCounts_valuesNonNegative() {
		final Map<Integer, Double> bpCounts = analyzer.getBranchPointCounts();
		for (final double count : bpCounts.values()) {
			assertTrue("Branch point count should be >= 0", count >= 0);
		}
	}

	@Test
	public void testGetBifurcationRatios_containsNaN() {
		final Map<Integer, Double> ratios = analyzer.getBifurcationRatios();
		// The highest order should have NaN ratio (no higher order to compare with)
		final int highest = analyzer.getHighestBranchOrder();
		assertTrue("Highest order bifurcation ratio should be NaN",
				Double.isNaN(ratios.get(highest)));
	}

	@Test
	public void testGetBifurcationRatios_positiveForLowerOrders() {
		final Map<Integer, Double> ratios = analyzer.getBifurcationRatios();
		final int highest = analyzer.getHighestBranchOrder();
		for (final Map.Entry<Integer, Double> entry : ratios.entrySet()) {
			if (entry.getKey() < highest) {
				assertFalse("Non-highest order ratio should not be NaN", Double.isNaN(entry.getValue()));
				assertTrue("Bifurcation ratio should be > 0", entry.getValue() > 0);
			}
		}
	}

	@Test
	public void testGetAvgBifurcationRatio_fractalTree() {
		// For a perfect binary fractal tree the bifurcation ratio is 2
		final double avgRatio = analyzer.getAvgBifurcationRatio();
		assertFalse("Average bifurcation ratio should not be NaN", Double.isNaN(avgRatio));
		assertEquals("Expected bifurcation ratio of 2 for fractal tree", 2.0, avgRatio, DELTA);
	}

	@Test
	public void testGetBranches_allOrders() {
		final Map<Integer, List<Path>> branches = analyzer.getBranches();
		assertFalse("Branches map should not be empty", branches.isEmpty());
		for (final Map.Entry<Integer, List<Path>> entry : branches.entrySet()) {
			assertNotNull("Branch list should not be null", entry.getValue());
		}
	}

	@Test
	public void testGetBranches_byOrder_validRange() {
		final int highest = analyzer.getHighestBranchOrder();
		for (int order = 1; order <= highest; order++) {
			final List<Path> branches = analyzer.getBranches(order);
			assertNotNull("Branches should not be null for order " + order, branches);
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGetBranches_invalidOrder_zero() {
		analyzer.getBranches(0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGetBranches_invalidOrder_tooHigh() {
		analyzer.getBranches(analyzer.getHighestBranchOrder() + 1);
	}

	@Test
	public void testGetAvgFragmentations_allOrders() {
		final Map<Integer, Double> frags = analyzer.getAvgFragmentations();
		assertFalse("Fragmentations map should not be empty", frags.isEmpty());
	}

	@Test
	public void testGetAvgContractions_allOrders() {
		final Map<Integer, Double> contractions = analyzer.getAvgContractions();
		assertFalse("Contractions map should not be empty", contractions.isEmpty());
	}

	@Test
	public void testGetGraph_notNull() {
		assertNotNull("Graph should not be null", analyzer.getGraph());
	}

	@Test
	public void testGetRootAssociatedBranches_notNull() {
		final List<Path> rootBranches = analyzer.getRootAssociatedBranches();
		assertNotNull("Root-associated branches list should not be null", rootBranches);
	}

	@Test
	public void testOrderConsistencyWithTreeStatistics() {
		// The Strahler root number should match TreeStatistics.getStrahlerNumber()
		final int strahlerFromStats = new TreeStatistics(tree).getStrahlerNumber();
		assertEquals("Strahler numbers should match", strahlerFromStats, analyzer.getRootNumber());
	}
}
