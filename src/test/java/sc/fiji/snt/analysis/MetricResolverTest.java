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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.imagej.display.ColorTables;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Tests for {@link MetricResolver} and its use by statistics and color mapping
 * classes
 */
public class MetricResolverTest {

	private static Tree tree() {
		return new Tree(SNTUtils.randomPaths());
	}

	private static void assertUnknown(final Runnable r) {
		try {
			r.run();
			fail("UnknownMetricException expected");
		} catch (final UnknownMetricException expected) {
			// pass
		}
	}

	/** The metrics of a given domain must be unambiguous once normalized */
	private static void assertUniqueKeys(final Iterable<String> metrics) {
		final Map<String, String> seen = new HashMap<>();
		for (final String m : metrics) {
			final String other = seen.put(MetricResolver.key(m), m);
			assertTrue("Ambiguous metrics: '" + m + "' and '" + other + "'", other == null || other.equals(m));
		}
	}

	// ---- MetricResolver ----

	@Test
	public void stagesAndSuggestions() {
		final MetricResolver r = new MetricResolver("test", List.of("No. of tips", "Path length"),
				w -> (w.any("tip")) ? "No. of tips" : (w.any("bogus")) ? "Not in domain" : null);
		assertEquals("Path length", r.resolve("Path length"));
		assertEquals("Path length", r.resolve("path  LENGTH"));
		assertEquals("No. of tips", r.resolve("no of tips"));
		assertEquals("No. of tips", r.resolve("tips"));
		assertNull(r.resolveStrict("tips"));
		// heuristic results are validated against the domain
		assertNull(r.resolveOrNull("bogus"));
		try {
			r.resolve("path lenth");
			fail();
		} catch (final UnknownMetricException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Path length"));
		}
		assertUnknown(() -> r.resolve("tips", true));
	}

	@Test
	public void wordsMatchStemsNotSubstrings() {
		final MetricResolver.Words w = new MetricResolver.Words("Internode distance (squared)");
		assertTrue(w.any("inter"));
		assertTrue(w.any("sq"));
		assertFalse(w.any("node ")); // no partial words across boundaries
		assertFalse(w.any("val"));
		assertTrue(w.is("internode"));
		assertFalse(w.is("node"));
	}

	// ---- TreeStatistics ----

	@Test
	public void treeStatisticsMetricsResolveToThemselves() {
		final List<String> all = new ArrayList<>(TreeStatistics.getAllMetrics());
		all.addAll(MultiTreeStatistics.getMetrics());
		all.addAll(TreeStatistics.getMetrics("deprecated"));
		assertUniqueKeys(all);
		for (final String m : all) {
			assertEquals(m, TreeStatistics.resolveMetric(m));
			assertEquals(m, TreeStatistics.resolveMetric(m.toLowerCase()));
			assertEquals(m, TreeStatistics.resolveMetric(m.toUpperCase()));
			assertEquals(m, TreeStatistics.resolveMetric(m.replace(" ", "")));
		}
	}

	@Test
	public void treeStatisticsLoosePhrases() {
		assertEquals(TreeStatistics.N_BRANCH_POINTS, TreeStatistics.resolveMetric("# bps"));
		assertEquals(TreeStatistics.N_BRANCH_POINTS, TreeStatistics.resolveMetric("n junctions"));
		assertEquals(TreeStatistics.N_BRANCH_POINTS, TreeStatistics.resolveMetric("branch points"));
		assertEquals(TreeStatistics.N_TIPS, TreeStatistics.resolveMetric("tips"));
		assertEquals(TreeStatistics.LENGTH, TreeStatistics.resolveMetric("cable"));
		assertEquals(TreeStatistics.BRANCH_LENGTH, TreeStatistics.resolveMetric("length"));
		assertEquals(TreeStatistics.TERMINAL_LENGTH, TreeStatistics.resolveMetric("terminal length"));
		assertEquals(TreeStatistics.PATH_LENGTH, TreeStatistics.resolveMetric("path length (um)"));
		assertEquals(TreeStatistics.PATH_MEAN_RADIUS, TreeStatistics.resolveMetric("path avg radius"));
		assertEquals(TreeStatistics.NODE_RADIUS, TreeStatistics.resolveMetric("radii"));
		assertEquals(TreeStatistics.PATH_SPINE_DENSITY, TreeStatistics.resolveMetric("spine density"));
		assertEquals(TreeStatistics.PATH_EXT_ANGLE_XZ, TreeStatistics.resolveMetric("path ext. angle xz"));
		assertEquals(TreeStatistics.BRANCH_EXTENSION_ANGLE_REL, TreeStatistics.resolveMetric("relative ext angle"));
		assertEquals(TreeStatistics.Y_COORDINATES, TreeStatistics.resolveMetric("y"));
		assertEquals(TreeStatistics.VALUES, TreeStatistics.resolveMetric("intensities"));
	}

	@Test
	public void internodeMetricsAreNotMistakenForOtherMetrics() {
		assertEquals(TreeStatistics.INTER_NODE_ANGLE, TreeStatistics.resolveMetric("inter-node angle"));
		assertEquals(TreeStatistics.INTER_NODE_DISTANCE, TreeStatistics.resolveMetric("inter node dist"));
		assertEquals(TreeStatistics.INTER_NODE_DISTANCE_SQUARED, TreeStatistics.resolveMetric("internode dist squared"));
	}

	@Test
	public void unknownMetricsAreReported() {
		assertUnknown(() -> TreeStatistics.resolveMetric("gibberish"));
		assertUnknown(() -> TreeStatistics.resolveMetric(""));
		assertUnknown(() -> TreeStatistics.resolveMetric(null));
		try {
			TreeStatistics.resolveMetric("lenth");
			fail();
		} catch (final UnknownMetricException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Did you mean"));
		}
	}

	@Test
	public void exactMatchIsPerInstance() {
		final TreeStatistics strict = new TreeStatistics(tree());
		final TreeStatistics loose = new TreeStatistics(tree());
		strict.setExactMetricMatch(true);
		assertTrue(strict.isExactMetricMatch());
		assertFalse(loose.isExactMetricMatch());
		// case/punctuation differences are always tolerated
		assertEquals(TreeStatistics.N_BRANCH_POINTS, strict.getNormalizedMeasurement("no of branch points"));
		// loose phrases are not, but only for the strict instance
		assertUnknown(() -> strict.getNormalizedMeasurement("# bps"));
		assertEquals(TreeStatistics.N_BRANCH_POINTS, loose.getNormalizedMeasurement("# bps"));
		// the setting must also be honored by getMetric()
		assertUnknown(() -> strict.getMetric("# bps"));
	}

	// ---- Color mappers ----

	@Test
	public void treeColorMapperMetricsResolveToThemselves() {
		assertUniqueKeys(TreeColorMapper.getMetrics());
		for (final String m : TreeColorMapper.getMetrics()) {
			assertEquals(m, TreeColorMapper.getNormalizedMeasurement(m));
			// The canonical name must be returned, not a re-capitalized input
			assertEquals(m, TreeColorMapper.getNormalizedMeasurement(m.toLowerCase()));
		}
		assertEquals(TreeColorMapper.PATH_EXT_ANGLE_REL,
				TreeColorMapper.getNormalizedMeasurement("path extension angle (rel.)"));
		assertEquals(TreeColorMapper.PATH_EXT_ANGLE_XY,
				TreeColorMapper.getNormalizedMeasurement("path extension angle xy"));
	}

	@Test
	public void treeColorMapperLoosePhrases() {
		assertEquals(TreeColorMapper.PATH_DISTANCE, TreeColorMapper.getNormalizedMeasurement("distance to soma"));
		assertEquals(TreeColorMapper.PATH_LENGTH, TreeColorMapper.getNormalizedMeasurement("length"));
		assertEquals(TreeColorMapper.STRAHLER_ORDERS, TreeColorMapper.getNormalizedMeasurement("strahler"));
		assertEquals(TreeColorMapper.PATH_EXT_ANGLE, TreeColorMapper.getNormalizedMeasurement("path angle"));
		assertEquals(TreeColorMapper.Z_COORDINATES, TreeColorMapper.getNormalizedMeasurement("z"));
		assertUnknown(() -> TreeColorMapper.getNormalizedMeasurement("gibberish"));
	}

	@Test
	public void multiTreeColorMapperResolution() {
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(List.of(tree(), tree()));
		assertUniqueKeys(MultiTreeColorMapper.getMetrics());
		for (final String m : MultiTreeColorMapper.getMetrics()) {
			assertEquals(m, MultiTreeColorMapper.resolveMetric(m.toLowerCase()));
		}
		mapper.map("tips", ColorTables.ICE);
		assertEquals(MultiTreeColorMapper.N_TIPS, mapper.getMeasurement());
		mapper.map("cable length", ColorTables.ICE);
		assertEquals(MultiTreeColorMapper.CABLE_LENGTH, mapper.getMeasurement());
		// "root" must no longer be routed to the Strahler number
		assertUnknown(() -> mapper.map("root angle", ColorTables.ICE));
		assertUnknown(() -> mapper.map("gibberish", ColorTables.ICE));
	}

	// ---- NodeStatistics ----

	@Test
	public void nodeStatisticsResolution() {
		for (final String m : NodeStatistics.getMetrics()) {
			assertEquals(m, NodeStatistics.getNormalizedMeasurement(m));
			assertEquals(m, NodeStatistics.getNormalizedMeasurement(m.toLowerCase()));
		}
		assertEquals(NodeStatistics.VALUES, NodeStatistics.getNormalizedMeasurement("intensity"));
		assertEquals(NodeStatistics.BRANCH_ORDER, NodeStatistics.getNormalizedMeasurement("horton-strahler"));
		assertEquals(NodeStatistics.NEAREST_NEIGHBOR_DISTANCE,
				NodeStatistics.getNormalizedMeasurement("nearest neighbour"));
		// previously mistaken for VALUES ("int") and BRANCH_ORDER ("ord")
		assertUnknown(() -> NodeStatistics.getNormalizedMeasurement("internode distance"));
		assertUnknown(() -> NodeStatistics.getNormalizedMeasurement("coordinates"));
	}
}
