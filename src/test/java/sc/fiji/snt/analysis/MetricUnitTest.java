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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import net.imagej.display.ColorTables;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.BoundingBox;

/**
 * Tests for metric units and labels: {@link TreeStatistics#getUnit(String, String, boolean)},
 * {@link AnalysisUtils#getMetricLabel(String, String)} and
 * {@link ColorMapper#getMeasurementLabel()}
 */
public class MetricUnitTest {

	private static final String UM = "µm";

	@Test
	public void spatialMetrics() {
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.PATH_LENGTH, UM, true));
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.NODE_RADIUS, UM, true));
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.INTER_NODE_DISTANCE, UM, true));
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.X_COORDINATES, UM, true));
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.WIDTH, UM, true));
		assertEquals(UM + "²", TreeStatistics.getUnit(TreeStatistics.INTER_NODE_DISTANCE_SQUARED, UM, true));
		assertEquals(UM + "²", TreeStatistics.getUnit(TreeStatistics.SURFACE_AREA, UM, true));
		assertEquals(UM + "³", TreeStatistics.getUnit(TreeStatistics.BRANCH_VOLUME, UM, true));
		assertEquals(UM + "⁻¹", TreeStatistics.getUnit(TreeStatistics.PATH_SPINE_DENSITY, UM, true));
	}

	@Test
	public void convexHull() {
		assertEquals(UM + "³", TreeStatistics.getUnit(TreeStatistics.CONVEX_HULL_SIZE, UM, true));
		assertEquals(UM + "²", TreeStatistics.getUnit(TreeStatistics.CONVEX_HULL_SIZE, UM, false));
		assertEquals(UM + "²", TreeStatistics.getUnit(TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE, UM, true));
		assertEquals(UM, TreeStatistics.getUnit(TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE, UM, false));
		assertEquals("", TreeStatistics.getUnit(TreeStatistics.CONVEX_HULL_ELONGATION, UM, true));
	}

	@Test
	public void anglesAreIndependentOfSpatialUnit() {
		assertEquals("°", TreeStatistics.getUnit(TreeStatistics.INTER_NODE_ANGLE, "", true));
		assertEquals("°", TreeStatistics.getUnit(TreeStatistics.PATH_EXT_ANGLE_REL, UM, true));
		assertEquals("°", TreeStatistics.getUnit(TreeStatistics.REMOTE_BIF_ANGLES, null, true));
		assertEquals("°", TreeStatistics.getUnit(TreeStatistics.ROOT_ANGLE_M_DIRECTION, UM, true));
		// indices derived from root angles are not angles
		assertEquals("", TreeStatistics.getUnit(TreeStatistics.ROOT_ANGLE_B_FACTOR, UM, true));
		assertEquals("", TreeStatistics.getUnit(TreeStatistics.ROOT_ANGLE_C_BIAS, UM, true));
	}

	@Test
	public void unknownSpatialUnitYieldsNoUnit() {
		for (final String metric : new String[] { TreeStatistics.PATH_LENGTH, TreeStatistics.BRANCH_VOLUME,
				TreeStatistics.SURFACE_AREA, TreeStatistics.CONVEX_HULL_SIZE, TreeStatistics.Z_COORDINATES }) {
			assertEquals(metric, "", TreeStatistics.getUnit(metric, "", true));
			assertEquals(metric, "", TreeStatistics.getUnit(metric, null, true));
		}
	}

	@Test
	public void dimensionlessMetrics() {
		assertEquals("", TreeStatistics.getUnit(TreeStatistics.N_BRANCH_POINTS, UM, true));
		assertEquals("", TreeStatistics.getUnit(TreeStatistics.PATH_ORDER, UM, true));
	}

	@Test
	public void metricLabel() {
		assertEquals("Path length (" + UM + ")", AnalysisUtils.getMetricLabel("Path length", UM));
		assertEquals("No. of tips", AnalysisUtils.getMetricLabel("No. of tips", ""));
		assertEquals("No. of tips", AnalysisUtils.getMetricLabel("No. of tips", (String) null));
	}

	@Test
	public void treeColorMapperLabel() {
		final Tree tree = new Tree(SNTUtils.randomPaths());
		tree.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, UM);
		final TreeColorMapper mapper = new TreeColorMapper();
		assertNull(mapper.getMeasurement());
		assertNull(mapper.getMeasurementLabel());
		// lower case input must be reported as the standard metric
		mapper.map(tree, "path length", ColorTables.ICE);
		assertEquals(TreeColorMapper.PATH_LENGTH, mapper.getMeasurement());
		assertEquals("Path length (" + UM + ")", mapper.getMeasurementLabel());
		mapper.map(tree, TreeColorMapper.PATH_ORDER, ColorTables.ICE);
		assertEquals(TreeColorMapper.PATH_ORDER, mapper.getMeasurementLabel());
	}

	@Test
	public void uncalibratedTreeHasNoPlaceholderInLabel() {
		final Tree tree = new Tree(SNTUtils.randomPaths());
		tree.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, BoundingBox.UNSET_SPACING_UNIT);
		final TreeColorMapper mapper = new TreeColorMapper();
		mapper.map(tree, TreeColorMapper.PATH_LENGTH, ColorTables.ICE);
		assertEquals(TreeColorMapper.PATH_LENGTH, mapper.getMeasurementLabel());
	}

	@Test
	public void multiTreeColorMapperLabel() {
		final Tree t1 = new Tree(SNTUtils.randomPaths());
		final Tree t2 = new Tree(SNTUtils.randomPaths());
		t1.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, UM);
		t2.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, UM);
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(java.util.List.of(t1, t2));
		mapper.map(MultiTreeColorMapper.CABLE_LENGTH, ColorTables.ICE);
		assertEquals("Cable length (" + UM + ")", mapper.getMeasurementLabel());
		mapper.map(MultiTreeColorMapper.N_TIPS, ColorTables.ICE);
		assertEquals(MultiTreeColorMapper.N_TIPS, mapper.getMeasurementLabel());
	}
}
