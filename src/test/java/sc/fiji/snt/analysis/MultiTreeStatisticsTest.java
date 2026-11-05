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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Regression tests for MultiTreeStatistics, whose wrapping Tree is empty. Metric
 * units and raw values must not depend on it, and metric names are
 * case-insensitive
 */
public class MultiTreeStatisticsTest {

	private static MultiTreeStatistics stats() {
		return new MultiTreeStatistics(
				Arrays.asList(new Tree(SNTUtils.randomPaths()), new Tree(SNTUtils.randomPaths())));
	}

	@Test
	public void unitsDoNotRequireTheWrappingTree() {
		final MultiTreeStatistics s = stats();
		assertEquals(s.getUnit(TreeStatistics.X_COORDINATES), s.getUnit("x coordinates"));
		// Convex hull metrics are the only ones that depend on dimensionality
		s.getUnit(TreeStatistics.CONVEX_HULL_SIZE);
	}

	@Test
	public void rawValuesAreCaseInsensitive() {
		final MultiTreeStatistics s = stats();
		final int rows = s.getRawValues("x coordinates").getRowCount();
		assertTrue(rows > 0);
		assertEquals(rows, s.getRawValues(TreeStatistics.X_COORDINATES).getRowCount());
	}
}
