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

package sc.fiji.snt;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.util.PointInImage;

/**
 * Test for basic I/O Operations
 *
 * @author Tiago Ferreira
 */
public class IOTest {

	private List<Tree> trees;
	private double precision = 1e-10;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void setUp() throws Exception {
		trees = new SNTService().demoTreesSWC();
		assumeNotNull(trees);
	}

	@Test
	public void testTreeIO() {
		trees.forEach(tree -> {
			final List<PointInImage> nodes = tree.getNodes();
			assertEquals(nodes.size(), tree.getNodesAsSWCPoints().size());
			final double cableLength = new TreeAnalyzer(tree).getCableLength();
			final Set<PointInImage> bps = new TreeAnalyzer(tree).getBranchPoints();
			try {

				// SWC I/O
				final String swcPath = folder.newFile(tree.getLabel() + ".swc").getAbsolutePath();
				assertTrue("Saving to "+ swcPath, tree.saveAsSWC(swcPath));
				final Tree swcTree = new Tree(swcPath);
				assertFalse("Reading file " + swcPath, swcTree.isEmpty());

				// Did tree change when saving to SWC?
				assertEquals(bps, new TreeAnalyzer(swcTree).getBranchPoints());
				assertEquals(cableLength, new TreeAnalyzer(swcTree).getCableLength(), precision);

				// TRACES I/O
				final String tracesPath = folder.newFile(tree.getLabel() + ".traces").getAbsolutePath();
				assertTrue("Saving to "+ tracesPath, tree.save(tracesPath));
				final Tree tracesTree = new Tree(tracesPath);
				assertFalse("Reading file " + tracesPath, tracesTree.isEmpty());

				// Did tree change when saving to TRACES?
				assertEquals(bps, new TreeAnalyzer(tracesTree).getBranchPoints());
				assertEquals(cableLength, new TreeAnalyzer(tracesTree).getCableLength(), precision);
			} catch (final IOException e) {
				e.printStackTrace();
			}
		});
	}

}
