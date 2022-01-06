/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;

/**
 * Tests for {@link TreeStatistics}
 */
public class TreeStatisticsTest {

	private final double precision = 0.0001;
	private Tree tree;
	private TreeStatistics tStats;

	@Before
	public void setUp() throws Exception {
		tree = new SNTService().demoTrees().get(1);
		tStats = new TreeStatistics(tree);
		assumeNotNull(tree);
	}

	@Test
	public void testAnnotatedLength() {
		final double cableLength = tStats.getCableLength();
		final int maxOntologyDepth = AllenUtils.getHighestOntologyDepth();
		for (int level = 0; level < maxOntologyDepth; level++) {
			final Map<BrainAnnotation, Double> lengthMap = tStats.getAnnotatedLength(level);
			final double mapLength = lengthMap.values().stream().mapToDouble(d -> d).sum();
			assertEquals("Sum of annotated lengths", cableLength, mapLength, precision);
			final double unaccountedLength = (lengthMap.get(null) == null) ? 0 : lengthMap.get(null);
			assertTrue("Length not entirely in null compartment", mapLength - unaccountedLength > 0);
			// Check for duplicate keys
			Set<String> uniqueKeys = new HashSet<String>();
			lengthMap.keySet().stream().filter(e -> !uniqueKeys.add(e.acronym())).collect(Collectors.toSet());
			assertEquals("No duplicate keys", uniqueKeys.size(), lengthMap.keySet().size());
		}
	}

}
