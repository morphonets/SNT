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

import features.ComputeCurvatures;
import ij.ImagePlus;
import ij.measure.Calibration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Objects;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

public class Tracing3DTest {

	ImagePlus image;

	int startX = 33;
	int startY = 430;
	int startZ = 0;

	int endX = 439;
	int endY = 200;
	int endZ = 45;

	@Before
	public void setUp() {
		image = new ImagePlus(
				Objects.requireNonNull(getClass().getClassLoader().getResource("OP_1.tif")).getPath());
		assumeNotNull(image);
	}

	@After
	public void tearDown() {
		if (image != null) image.close();
	}

	@Test
	public void testTracing() {

		final Calibration calibration = image.getCalibration();

		long pointsExploredNormal;
		{
			final TracerThread tracer = new TracerThread(image, 0, 254, -1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ, endX, endY, endZ,
					true, // reciprocal
					false, // singleSlice
					null, 1, // multiplier
					null, false);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();

			assertTrue("Path length must be greater than 228 micrometres", foundPathLength > 228);
			assertTrue("Path length must be less than 228.5 micrometres", foundPathLength < 228.5);

			pointsExploredNormal = tracer.pointsConsideredInSearch();

		}

		long pointsExploredNBAStar;
		{
			final BidirectionalAStarSearch tracer = new BidirectionalAStarSearch(image, 0, 254,
					-1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ, endX, endY, endZ,
					true, // reciprocal
					false, // singleSlice
					null, 1, // multiplier
					null, false);

			Path result = tracer.call();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();

			assertTrue("Path length must be greater than 228 micrometres", foundPathLength > 228);
			assertTrue("Path length must be less than 228.5 micrometres", foundPathLength < 228.5);

			pointsExploredNBAStar = tracer.pointsConsideredInSearch();

		}

		{
			long pointsExploredHessian;
			long pointsExploredNBAStarHessian;

			final ComputeCurvatures hessian = new ComputeCurvatures(image, 0.98,
				null, calibration != null);
			hessian.run();

			final TracerThread tracer = new TracerThread(image, 0, 254, -1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ, endX, endY, endZ,
					true, // reciprocal
					false, // singleSlice
					hessian, 17.0, // multiplier
					null, true);

			final BidirectionalAStarSearch tracerNBAStar = new BidirectionalAStarSearch(image, 0, 254,
					-1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ, endX, endY, endZ,
					true, // reciprocal
					false, // singleSlice
					hessian, 17.0, // multiplier
					null, true);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final Path resultNBAStar = tracerNBAStar.call();
			assertNotNull("Not path found", resultNBAStar);

			final double foundPathLength = result.getLength();
			final double foundPathLengthNBAStar = resultNBAStar.getLength();


			assertTrue("Path length must be greater than 226 micrometres",
				foundPathLength > 226);
			assertTrue("Path length must be greater than 226 micrometres",
				foundPathLengthNBAStar > 226);

			assertTrue("Path length must be less than 226.2 micrometres",
				foundPathLength < 226.2);
			assertTrue("Path length must be less than 226.2 micrometres",
				foundPathLengthNBAStar < 226.2);

			pointsExploredHessian = tracer.pointsConsideredInSearch();
			pointsExploredNBAStarHessian = tracerNBAStar.pointsConsideredInSearch();

			assertTrue("Hessian-based analysis should explore less than 46000 points",
				pointsExploredHessian < 46000);
			assertTrue("Hessian-based analysis should explore less than 46000 points",
				pointsExploredNBAStarHessian < 46000);

			assertTrue("Hessian-based analysis should reduce the points explored " +
				"by at least a third; in fact went from " + pointsExploredNormal +
				" to " + pointsExploredHessian,
				pointsExploredHessian < pointsExploredNormal * 0.6666);
			assertTrue("Hessian-based analysis should reduce the points explored " +
				"by at least a third; in fact went from " + pointsExploredNBAStar +
				" to " + pointsExploredNBAStarHessian,
				pointsExploredNBAStarHessian < pointsExploredNBAStar * 0.6666);
		}
	}
}
