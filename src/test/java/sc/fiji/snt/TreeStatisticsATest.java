/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNotNull;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.analysis.TreeStatistics;

/**
 * Tests for {@link TreeStatistics} and geometric transformations of {@link Tree}s
 *
 * @author Tiago Ferreira
 */
public class TreeStatisticsATest {

	private final double precision = 0.0001;
	private Tree tree;
	private TreeStatistics analyzer;

	@Before
	public void setUp() throws Exception {
		tree = new SNTService().demoTree("fractal");
		analyzer = new TreeStatistics(tree);
		assumeNotNull(tree);
	}

	@Test
	public void testAnalyzer() {
        assertEquals("# Paths = 16", 16, analyzer.getNPaths());
        assertEquals("# Branch points (tree) = 15", 15, analyzer.getBranchPoints().size());
		assertEquals("# Branch points (graph) = 15", 15, tree.getGraph().getBPs().size());
		assertEquals("# Tips (tree) = 16", 16, analyzer.getTips().size());
		assertEquals("# Tips (graph) = 16", 16, tree.getGraph().getTips().size());
		assertEquals("# I paths = 1", 1, analyzer.getPrimaryPaths().size());
        assertEquals("# Highest path order = 5", 5, analyzer.getHighestPathOrder());
		final double cableLength =  analyzer.getCableLength();
		final double primaryLength =  analyzer.getPrimaryLength();
		final double terminalLength =  analyzer.getTerminalLength();
		final double avgBranchLength =  analyzer.getAvgBranchLength();
		final int nBPs = analyzer.getBranchPoints().size();
		final int nTips = analyzer.getTips().size();
		final int nBranches = analyzer.getNBranches();

		assertEquals("Sum length of all paths", 569.3452, cableLength, precision);
		assertEquals("Sum length of I branches", 51.0000, primaryLength, precision);
		assertEquals("Sum length of terminal paths", 153.2965, terminalLength, precision);
		assertEquals("Avg branch length", 18.3659, avgBranchLength, precision);
        assertEquals("Strahler number: 5", 5, analyzer.getStrahlerNumber());
        assertEquals("Strahler bif. ratio: 2", 2, analyzer.getStrahlerBifurcationRatio(), 0.0);
        assertEquals("N Branches: 31", 31, analyzer.getNBranches());
        assertEquals("Width = 116.0", 116d, analyzer.getWidth(), 0.0);
        assertEquals("Height = 145.0", 145d, analyzer.getHeight(), 0.0);
        assertEquals("Depth = 0.0", 0d, analyzer.getDepth(), 0.0);
		final double avgContraction = analyzer.getAvgContraction();
		assertEquals("Avg contraction", 0.9628, avgContraction, precision);
		final double avgRemoteBifAngle = analyzer.getAvgRemoteBifAngle();
		assertEquals("Avg remote bif angle", 41.3833, avgRemoteBifAngle, precision);
		final double avgPartitionAsymmetry = analyzer.getAvgPartitionAsymmetry();
		assertEquals("Avg partition asymmetry", 0.0, avgPartitionAsymmetry, precision);
		final double avgFractalDimension = analyzer.getAvgFractalDimension();
		assertEquals("Avg fractal dimension", 1.0146, avgFractalDimension, precision);

		// Scaling tests
		for (double scaleFactor : new double[] { .25d, 1d, 2d}) {
			tree.scale(scaleFactor, scaleFactor, scaleFactor);
			final TreeStatistics scaledAnalyzer = new TreeStatistics(tree);
            assertEquals("Scaling: Equal # Tips", nTips, scaledAnalyzer.getTips().size());
            assertEquals("Scaling: Equal # Branch points", nBPs, scaledAnalyzer.getBranchPoints().size());
            assertEquals("Scaling: Equal # Branches", nBranches, scaledAnalyzer.getNBranches());
			assertEquals("Scaling: Cable length", cableLength * scaleFactor, scaledAnalyzer.getCableLength(), precision);
			tree.scale(1 / scaleFactor, 1 / scaleFactor, 1 / scaleFactor);
		}

		// Rotation tests
		final double angle = 33.3;
		for (final int axis : new int[] {Tree.X_AXIS, Tree.Y_AXIS, Tree.Z_AXIS}) {
			tree.rotate(axis, angle);
			final TreeStatistics rotatedAnalyzer = new TreeStatistics(tree);
            assertEquals("Rotation: Equal # Tips", analyzer.getTips().size(), rotatedAnalyzer.getTips().size());
            assertEquals("Rotation: Equal # Branch points", analyzer.getBranchPoints().size(), rotatedAnalyzer.getBranchPoints().size());
			assertEquals("Rotation: Cable length", cableLength, rotatedAnalyzer.getCableLength(), precision);
			tree.rotate(axis, -angle);
		}
	}

}
