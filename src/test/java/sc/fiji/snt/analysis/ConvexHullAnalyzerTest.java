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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNotNull;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.PointInImage;

/**
 * Tests for {@link ConvexHullAnalyzer} (3D hulls)
 */
public class ConvexHullAnalyzerTest {

	// Computed independently (numpy/scipy) from the nodes of the OP_1 demo tree
	private static final double[] OP1_CENTROID = { 82.20407, 85.68432, 29.23882 };
	private static final double OP1_ELONGATION = 0.655761;

	private Tree tree;

	@Before
	public void setUp() {
		tree = new SNTService().demoTree("OP_1");
		assumeNotNull(tree);
	}

	@Test
	public void centroidAndElongationMatchIndependentReference() {
		final ConvexHullAnalyzer analyzer = new ConvexHullAnalyzer(tree);
		final PointInImage c = analyzer.getCentroid();
		assertEquals(OP1_CENTROID[0], c.x, 1e-3);
		assertEquals(OP1_CENTROID[1], c.y, 1e-3);
		assertEquals(OP1_CENTROID[2], c.z, 1e-3);
		assertEquals(OP1_ELONGATION, analyzer.getElongation(), 1e-4);
	}

	/*
	 * Shape descriptors must not depend on where the tree is. Guards against
	 * reference-point errors (e.g., a wrong centroid inflating the inertia tensor
	 * of hulls that are far from the origin), which a mesh centered at the origin
	 * cannot reveal
	 */
	@Test
	public void descriptorsAreTranslationInvariant() {
		final double[] offset = { 1000, -2000, 300 };
		final Tree moved = tree.clone();
		moved.translate(offset[0], offset[1], offset[2]);
		final ConvexHullAnalyzer a = new ConvexHullAnalyzer(tree);
		final ConvexHullAnalyzer b = new ConvexHullAnalyzer(moved);
		assertEquals(a.getElongation(), b.getElongation(), 1e-6);
		assertEquals(a.getRoundness(), b.getRoundness(), 1e-6);
		assertEquals(a.getCompactness(), b.getCompactness(), 1e-6);
		assertEquals(a.getSize(), b.getSize(), 1e-6 * a.getSize());
		final PointInImage ca = a.getCentroid();
		final PointInImage cb = b.getCentroid();
		assertEquals(ca.x + offset[0], cb.x, 1e-3);
		assertEquals(ca.y + offset[1], cb.y, 1e-3);
		assertEquals(ca.z + offset[2], cb.z, 1e-3);
	}

	@Test
	public void elongationIsDimensionless() {
		assertEquals("", new TreeStatistics(tree).getUnit(
				TreeStatistics.CONVEX_HULL_ELONGATION));
	}

}
