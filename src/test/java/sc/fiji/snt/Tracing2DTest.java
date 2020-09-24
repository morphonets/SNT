/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import features.ComputeCurvatures;
import ij.ImagePlus;
import ij.measure.Calibration;
import util.BatchOpener;

public class Tracing2DTest {

	ImagePlus image;

	double startX = 73.539;
	double startY = 48.449;
	double endX = 1.730;
	double endY = 13.554;

	@Before
	public void setUp() {
		image = BatchOpener.openFirstChannel(
			"tests/sample-data/c061AG-small-section-z-max.tif");
		assumeNotNull(image);
	}

	@After
	public void tearDown() {
		if (image != null) image.close();
	}

	@Test
	public void testTracing() {

		double pixelWidth = 1;
		double pixelHeight = 1;
		double minimumSeparation = 1;
		final Calibration calibration = image.getCalibration();
		if (calibration != null) {
			minimumSeparation = Math.min(Math.abs(calibration.pixelWidth), Math.min(
				Math.abs(calibration.pixelHeight), Math.abs(calibration.pixelDepth)));
			pixelWidth = calibration.pixelWidth;
			pixelHeight = calibration.pixelHeight;
		}

		int pointsExploredNormal = 0;
		{
			final TracerThread tracer = new TracerThread(image, 0, 255, -1, // timeoutSeconds
				100, // reportEveryMilliseconds
				(int) (startX / pixelWidth), (int) (startY / pixelHeight), 0,
				(int) (endX / pixelWidth), (int) (endY / pixelHeight), 0, true, // reciprocal
				true, // singleSlice
				null, 1, // multiplier
				null, false);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();
			assertTrue("Path length must be greater than 100 micrometres",
				foundPathLength > 100);

			assertTrue("Path length must be less than 105 micrometres",
				foundPathLength < 105);

			pointsExploredNormal = tracer.pointsConsideredInSearch();
		}

		int pointsExploredHessian = 0;
		{
			final ComputeCurvatures hessian = new ComputeCurvatures(image,
				minimumSeparation, null, calibration != null);
			hessian.run();

			final TracerThread tracer = new TracerThread(image, 0, 255, -1, // timeoutSeconds
				100, // reportEveryMilliseconds
				(int) (startX / pixelWidth), (int) (startY / pixelHeight), 0,
				(int) (endX / pixelWidth), (int) (endY / pixelHeight), 0, true, // reciprocal
				true, // singleSlice
				hessian, 50, // multiplier
				null, true);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();

			assertTrue("Path length must be greater than 92 micrometres",
				foundPathLength > 92);

			assertTrue("Path length must be less than 96 micrometres",
				foundPathLength < 96);

			pointsExploredHessian = tracer.pointsConsideredInSearch();

			assertTrue("Hessian-based analysis should reduce the points explored " +
				"by at least a two fifths; in fact went from " + pointsExploredNormal +
				" to " + pointsExploredHessian,
				pointsExploredHessian < pointsExploredNormal * 0.8);
		}
	}
}
