/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.analysis.PersistenceAnalyzer;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.util.SWCPoint;

/**
 * Tests for {@link PersistenceAnalyzer}
 */
public class PersistenceAnalyzerTest {

	private final double precision = 0.0001;
	private Tree tree;
	private PersistenceAnalyzer pAnalyzer;
	private final List<String> allDescriptors = PersistenceAnalyzer.getDescriptors();
	private TreeAnalyzer tAnalyzer;

	@Before
	public void setUp() throws Exception {
		tree = new SNTService().demoTrees().get(0);
		pAnalyzer = new PersistenceAnalyzer(tree);
		tAnalyzer = new TreeAnalyzer(tree);
		assumeNotNull(tree);
	}

	@Test
	public void testDiagram() {
		// Tests basic validity of the Persistence Diagram for each descriptor function.
		// TODO devise proper correctness tests for each descriptor.
		final int numTips = tAnalyzer.getTips().size();
		for (final String descriptor : allDescriptors) {
			final ArrayList<ArrayList<Double>> diagram = pAnalyzer.getDiagram(descriptor);
			assertEquals("Number of points in diagram", numTips, diagram.size());
			for (final ArrayList<Double> point : diagram) {
				assertTrue("Two non-negative values per point in diagram",
						point.size() == 2 && point.get(0) >= 0 && point.get(1) >= 0);
			}
		}
	}

	@Test
	public void testBarcode() {
		// Use geodesic descriptor since the sum of all intervals equals total cable length.
		ArrayList<Double> barcode = pAnalyzer.getBarcode("geodesic");
		final double cableLength = tAnalyzer.getCableLength();
		double sumIntervals = 0d;
		for (final double interval : barcode) {
			sumIntervals += interval;
		}
		assertEquals("Barcode: summed intervals", cableLength, sumIntervals, precision);
		/*
		 * For the other descriptors it should be sufficient to demonstrate non-negative
		 * interval lengths
		 */
		for (final String descriptor : allDescriptors) {
			barcode = pAnalyzer.getBarcode(descriptor);
			for (final double interval : barcode) {
				assertTrue("Non-negative intervals", interval >= 0);
			}
		}
	}

	@Test
	public void testDiagramNodes() {
		final int numTips = tAnalyzer.getTips().size();
		for (final String descriptor : allDescriptors) {
			final ArrayList<ArrayList<SWCPoint>> diagramNodes = pAnalyzer.getDiagramNodes(descriptor);
			assertEquals("Number of points in diagram", numTips, diagramNodes.size());
			for (final ArrayList<SWCPoint> point : diagramNodes) {
				assertTrue("Two SWCPoint objects per point in diagram",
						point.size() == 2 && point.get(0) instanceof SWCPoint && point.get(1) instanceof SWCPoint);
			}
		}
	}

	@Test
	public void testLandscape() {
		for (final String descriptor : allDescriptors) {
			final double[] landscape = pAnalyzer.getLandscape(descriptor, 5, 100);
			assertEquals("Landscape size", 500, landscape.length);
			double minVal = 0;
			for (int i = 0; i < landscape.length; i++) {
				if (landscape[i] < minVal) {
					minVal = landscape[i];
				}
			}
			assertTrue("Landscape: no negative values", minVal >= 0);
		}
	}

}
