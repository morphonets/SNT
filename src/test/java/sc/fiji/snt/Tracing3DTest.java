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

public class Tracing3DTest {

	ImagePlus image;

	double startX = 56.524;
	double startY = 43.258;
	double startZ = 18;
	double endX = 0;
	double endY = 17.015;
	double endZ = 22.8;

	@Before
	public void setUp() {
		image = BatchOpener.openFirstChannel(
			"tests/sample-data/c061AG-small-section.tif");
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
		double pixelDepth = 1;
		final Calibration calibration = image.getCalibration();
		if (calibration != null) {
			pixelWidth = calibration.pixelWidth;
			pixelHeight = calibration.pixelHeight;
			pixelDepth = calibration.pixelDepth;
		}

		final boolean doNormal = false;

		int pointsExploredNormal = 0;
		// This is very slow without the preprocessing, so don't do that bit by
		// default:
		if (doNormal) {
			final TracerThread tracer = new TracerThread(image, 0, 255, -1, // timeoutSeconds
				100, // reportEveryMilliseconds
				(int) (startX / pixelWidth), (int) (startY / pixelHeight), 0,
				(int) (endX / pixelWidth), (int) (endY / pixelHeight), 0, true, // reciprocal
				false, // singleSlice
				null, 1, // multiplier
				null, false);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();
			assertTrue("Path length must be greater than 95 micrometres",
				foundPathLength > 95);

			assertTrue("Path length must be less than 100 micrometres",
				foundPathLength < 100);

			pointsExploredNormal = tracer.pointsConsideredInSearch();
		}

		int pointsExploredHessian = 0;
		{
			final ComputeCurvatures hessian = new ComputeCurvatures(image, 0.721,
				null, calibration != null);
			hessian.run();

			final TracerThread tracer = new TracerThread(image, 0, 255, -1, // timeoutSeconds
				100, // reportEveryMilliseconds
				(int) (startX / pixelWidth), (int) (startY / pixelHeight),
				(int) (startZ / pixelDepth), (int) (endX / pixelWidth), (int) (endY /
					pixelHeight), (int) (endZ / pixelDepth), true, // reciprocal
				false, // singleSlice
				hessian, 19.69, // multiplier
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

			assertTrue("Hessian-based analysis should explore less than 24000 points",
				pointsExploredHessian < 24000);

			if (doNormal) {
				assertTrue("Hessian-based analysis should reduce the points explored " +
					"by at least a third; in fact went from " + pointsExploredNormal +
					" to " + pointsExploredHessian,
					pointsExploredHessian < pointsExploredNormal * 0.6666);
			}
		}
	}
}
