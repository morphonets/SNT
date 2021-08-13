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

import ij.measure.Calibration;
import ij.plugin.ZProjector;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ij.ImagePlus;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.util.ArraySearchImage;

import java.util.Objects;

public class Tracing2DTest {

	ImagePlus image;

	int startX = 33;
	int startY = 430;

	int endX = 439;
	int endY = 200;

	@Before
	public void setUp() {
		ImagePlus imp = new ImagePlus(
				Objects.requireNonNull(getClass().getClassLoader().getResource("OP_1.tif")).getPath());
		assumeNotNull(imp);
		image = ZProjector.run (imp, "max");
	}

	@After
	public void tearDown() {
		if (image != null) image.close();
	}

	@Test
	public void testTracing() {

		final Calibration cal = image.getCalibration();
		final RandomAccessibleInterval<UnsignedByteType> img = ImageJFunctions.wrap(image);
		ImageStatistics stats = image.getStatistics();

		long pointsExploredNormal;
		{
			final TracerThread tracer = new TracerThread(
					img, cal,
					startX, startY, 0,
					endX, endY, 0,
					-1, 100,
					ArraySearchImage.class,
					new ReciprocalCost(stats.min, stats.max),
					new EuclideanHeuristic());

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();

			assertTrue("Path length must be greater than 191 micrometres",
				foundPathLength > 191);

			assertTrue("Path length must be less than 192 micrometres",
				foundPathLength < 192);

			pointsExploredNormal = tracer.pointsConsideredInSearch();
		}

		long pointsExploredNBAStar;
		{
			final BidirectionalSearch tracer = new BidirectionalSearch(
					img, cal,
					startX, startY, 0,
					endX, endY, 0,
					-1, 100, // reciprocal
					ArraySearchImage.class,
					new ReciprocalCost(stats.min, stats.max),
					new EuclideanHeuristic()
			);

			tracer.run();
			final Path result = tracer.getResult();
			assertNotNull("Not path found", result);

			final double foundPathLength = result.getLength();

			assertTrue("Path length must be greater than 191 micrometres",
					foundPathLength > 191);

			assertTrue("Path length must be less than 192 micrometres",
					foundPathLength < 192);

			pointsExploredNBAStar = tracer.pointsConsideredInSearch();
		}



		{
			long pointsExploredHessian;
			long pointsExploredNBAStarHessian;

			double[] spacing = new double[2];
			spacing[0] = cal.pixelWidth;
			spacing[1] = cal.pixelHeight;
			final Tubeness<UnsignedByteType, FloatType> op = new Tubeness<>(new double[]{0.835}, spacing);
			final RandomAccessibleInterval<FloatType> tubenessImg = ArrayImgs.floats(img.dimensionsAsLongArray());
			op.compute(img, tubenessImg);
			final ComputeMinMax<FloatType> minMax = new ComputeMinMax<>(Views.iterable(tubenessImg),
					new FloatType(), new FloatType());
			minMax.process();
			double maximum = minMax.getMax().get();
			maximum *= 0.6;

			final TracerThread tracer = new TracerThread(tubenessImg, cal,
					startX, startY, 0,
					endX, endY, 0,
					-1, 100,
					ArraySearchImage.class,
					new ReciprocalCost(0, maximum),
					new EuclideanHeuristic());

			final BidirectionalSearch tracerNBAStar = new BidirectionalSearch(
					tubenessImg, cal,
					startX, startY, 0,
					endX, endY, 0,
					-1, 100,
					ArraySearchImage.class,
					new ReciprocalCost(0, maximum),
					new EuclideanHeuristic()
			);

			tracer.run();
			final Path result = tracer.getResult();
			tracerNBAStar.run();
			final Path resultNBAStar = tracerNBAStar.getResult();
			assertNotNull("Not path found", result);
			assertNotNull("Not path found", resultNBAStar);

			final double foundPathLength = result.getLength();
			final double foundPathLengthNBAStar = resultNBAStar.getLength();
//			System.out.println(foundPathLengthNBAStar);
//			System.out.println(foundPathLength);

			assertTrue(foundPathLength > 192.2);
			assertTrue(foundPathLengthNBAStar > 192.2);

			assertTrue(foundPathLength < 192.3);
			assertTrue(foundPathLengthNBAStar < 192.3);

			pointsExploredHessian = tracer.pointsConsideredInSearch();
			pointsExploredNBAStarHessian = tracerNBAStar.pointsConsideredInSearch();

			assertTrue("Hessian-based analysis should reduce the points explored " +
				"by at least a two fifths; in fact went from " + pointsExploredNormal +
				" to " + pointsExploredHessian,
				pointsExploredHessian < pointsExploredNormal * 0.80);

			assertTrue("Hessian-based analysis should reduce the points explored " +
							"by at least a two fifths; in fact went from " + pointsExploredNBAStar +
							" to " + pointsExploredNBAStarHessian,
					pointsExploredNBAStarHessian < pointsExploredNBAStar * 0.80);
		}
	}
}
