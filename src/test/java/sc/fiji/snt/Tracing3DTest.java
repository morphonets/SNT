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

import ij.ImagePlus;
import ij.measure.Calibration;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import sc.fiji.snt.util.ArraySearchImage;

import java.util.Objects;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

public class Tracing3DTest {

	ImagePlus image;

	int startX = 33;
	int startY = 430;
	int startZ = 1;

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

	@Ignore
	@Test
	public void testTracing() {
		// TODO
		final Calibration calibration = image.getCalibration();

		long pointsExploredNormal;
		{
			final TracerThread tracer = new TracerThread(image, 0, 254, -1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ, endX, endY, endZ,
					ArraySearchImage.class, new ReciprocalCost(), new EuclideanHeuristic());

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();
//			System.out.println(foundPathLength);

			assertTrue(foundPathLength > 227.4);
			assertTrue(foundPathLength < 227.6);

			pointsExploredNormal = tracer.pointsConsideredInSearch();

		}

		long pointsExploredNBAStar;
		{
			final BidirectionalHeuristicSearch tracer = new BidirectionalHeuristicSearch(
					startX, startY, startZ,
					endX, endY, endZ,
					image, 0, 254,
					-1, 100,
					ArraySearchImage.class, new ReciprocalCost(), new EuclideanHeuristic());

			Path result = tracer.call();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();
//			System.out.println(foundPathLength);
			assertTrue(foundPathLength > 227.4);
			assertTrue(foundPathLength < 227.6);

			pointsExploredNBAStar = tracer.pointsConsideredInSearch();

		}

		{
			long pointsExploredHessian;
			long pointsExploredNBAStarHessian;

			final HessianProcessor hessian = new HessianProcessor(image, null);
			hessian.processTubeness(0.84, false);
			//hessian.processFrangi(new double[]{0.54, 0.77, 0.843}, true);

			final TracerThread tracer = new TracerThread(image, 0, 254, -1, // timeoutSeconds
					100, // reportEveryMilliseconds
					startX, startY, startZ,
					endX, endY, endZ,
					ArraySearchImage.class,
					new TubenessCost(hessian, 5),
					new EuclideanHeuristic());

			final BidirectionalHeuristicSearch tracerNBAStar = new BidirectionalHeuristicSearch(
					startX, startY, startZ,
					endX, endY, endZ,
					image, 0, 254,
					-1, 100, // reciprocal
					ArraySearchImage.class,
					new TubenessCost(hessian, 5),
					new EuclideanHeuristic());

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final Path resultNBAStar = tracerNBAStar.call();
			assertNotNull("Not path found", resultNBAStar);

			final double foundPathLength = result.getLength();
			final double foundPathLengthNBAStar = resultNBAStar.getLength();
//			System.out.println(foundPathLength);
//			System.out.println(foundPathLengthNBAStar);

			assertTrue(foundPathLength > 226.4);
			assertTrue(foundPathLengthNBAStar > 226.4);

			assertTrue(foundPathLength < 226.5);
			assertTrue(foundPathLengthNBAStar < 226.5);

			pointsExploredHessian = tracer.pointsConsideredInSearch();
			pointsExploredNBAStarHessian = tracerNBAStar.pointsConsideredInSearch();
//			System.out.println(pointsExploredHessian);
//			System.out.println(pointsExploredNBAStarHessian);
			assertTrue(pointsExploredHessian < 49000);
			assertTrue(pointsExploredNBAStarHessian < 49000);

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
