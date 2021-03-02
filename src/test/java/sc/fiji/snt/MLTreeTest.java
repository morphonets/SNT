/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.io.MouseLightLoader;

/**
 * Tests for {@link TreeAnalyzer} and geometric transformations of {@link Tree}s
 *
 * @author Tiago Ferreira
 */
public class MLTreeTest {

	private Tree dendroTree;
	private Tree fullTree;

	@Before
	public void setUp() throws Exception {
		assumeTrue(MouseLightLoader.isDatabaseAvailable());
		final MouseLightLoader loader = new MouseLightLoader("AA0100");
		dendroTree = loader.getTree("dendrite");
		fullTree = loader.getTree();
	}

	@Test
	public void testAnalyzer() {
		assertNumberOfNodes(fullTree, 48709);
		assertNumberOfNodes(dendroTree, 28143);
	}

	void assertNumberOfNodes(final Tree tree, final int count) {
		assertTrue("# nodes", tree.getNodes().size() == count);
		assertTrue("# nodes", tree.getNodesAsSWCPoints().size() == count);
		assertTrue("# vertices", tree.getGraph().vertexSet().size() == count);

	}
}
