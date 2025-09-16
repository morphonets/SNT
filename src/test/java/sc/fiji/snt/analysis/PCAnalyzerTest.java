/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import sc.fiji.snt.analysis.PCAnalyzer.PrincipalAxis;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

/**
 * Tests for {@link PCAnalyzer}
 */
public class PCAnalyzerTest {

	@Test
	public void testPrincipalAxisBasicProperties() {
		// Create a simple line along X-axis
		final List<SNTPoint> points = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			points.add(new PointInImage(i, 0, 0));
		}

		final PrincipalAxis[] axes = PCAnalyzer.getPrincipalAxes(points);
		assertNotNull("PCA should return axes for valid points", axes);
		assertEquals("Should return 3 axes", 3, axes.length);

		// Primary axis should be along X direction for a line along X-axis
		final PrincipalAxis primary = axes[0];
		assertTrue("Primary axis X component should be dominant", 
			Math.abs(primary.x) > Math.abs(primary.y) &&
			Math.abs(primary.x) > Math.abs(primary.z));

		// Variance should be positive
		assertTrue("Variance should be positive", primary.getVariance() > 0);
	}

	@Test
	public void testVariancePercentages() {
		// Create points in a plane (XY plane)
		final List<SNTPoint> points = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				points.add(new PointInImage(i, j, 0));
			}
		}

		final PrincipalAxis[] axes = PCAnalyzer.getPrincipalAxes(points);
		final double[] percentages = PCAnalyzer.getVariancePercentages(axes);
		
		assertNotNull("Variance percentages should not be null", percentages);
		assertEquals("Should return 3 percentages", 3, percentages.length);

		// Percentages should sum to approximately 100%
		final double sum = percentages[0] + percentages[1] + percentages[2];
		assertEquals("Percentages should sum to 100%", 100.0, sum, 0.001);

		// All percentages should be non-negative
		for (final double percentage : percentages) {
			assertTrue("Percentage should be non-negative", percentage >= 0);
		}
	}

	@Test
	public void testAngleComputation() {
		// Create a simple line along X-axis
		final List<SNTPoint> points = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			points.add(new PointInImage(i, 0, 0));
		}

		final PrincipalAxis[] axes = PCAnalyzer.getPrincipalAxes(points);
		final PrincipalAxis primary = axes[0];

		// Angle with X-axis should be close to 0
		final double angleWithX = primary.getAngleWith(1, 0, 0);
		assertTrue("Angle with X-axis should be small", angleWithX < 5.0);

		// Angle with Y-axis should be close to 90
		final double angleWithY = primary.getAngleWith(0, 1, 0);
		assertTrue("Angle with Y-axis should be close to 90°", Math.abs(angleWithY - 90.0) < 5.0);
	}

	@Test
	public void testEdgeCases() {
		// Test with null input
		assertNull("Should return null for null input", 
			PCAnalyzer.getPrincipalAxes((List<SNTPoint>) null));

		// Test with empty collection
		final List<SNTPoint> emptyList = new ArrayList<>();
		assertNull("Should return null for empty collection", PCAnalyzer.getPrincipalAxes(emptyList));

		// Test with insufficient points
		final List<SNTPoint> twoPoints = new ArrayList<>();
		twoPoints.add(new PointInImage(0, 0, 0));
		twoPoints.add(new PointInImage(1, 1, 1));
		assertNull("Should return null for insufficient points", PCAnalyzer.getPrincipalAxes(twoPoints));

		// Test variance percentages with null input
		assertNull("Should return null for null axes", PCAnalyzer.getVariancePercentages(null));
	}

	@Test
	public void testAxisOrdering() {
		// Create points with clear variance ordering
		final List<SNTPoint> points = new ArrayList<>();
		
		// Major variation along X (0-10)
		// Minor variation along Y (0-2)  
		// No variation along Z (0)
		for (int i = 0; i < 10; i++) {
			for (int j = 0; j < 3; j++) {
				points.add(new PointInImage(i, j * 0.2, 0));
			}
		}

		final PrincipalAxis[] axes = PCAnalyzer.getPrincipalAxes(points);
		final double[] percentages = PCAnalyzer.getVariancePercentages(axes);

		// Primary axis should have the highest variance percentage
		assertTrue("Primary axis should have highest variance", percentages[0] > percentages[1] && percentages[0] > percentages[2]);
		
		// Secondary axis should have higher variance than tertiary
		assertTrue("Secondary axis should have higher variance than tertiary", percentages[1] > percentages[2]);
	}
}