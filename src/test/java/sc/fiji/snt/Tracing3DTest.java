/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import ij.process.ImageStatistics;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.tracing.*;
import sc.fiji.snt.tracing.cost.*;
import sc.fiji.snt.tracing.heuristic.Dijkstra;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;

import java.util.Objects;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import static sc.fiji.snt.SNT.SearchImageType.ARRAY;

public class Tracing3DTest {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static OpService opService;
	private static Img<UnsignedByteType> img;
	private static Calibration cal;
	private static double[] spacing;
	private static ImageStatistics stats;
	private final int startX = 33;
	private final int startY = 430;
	private final int startZ = 1;
	private final int endX = 439;
	private final int endY = 200;
	private final int endZ = 45;

	@SuppressWarnings("resource")
	@BeforeClass
	public static void setUp() {
		ImagePlus imp = new ImagePlus(
				Objects.requireNonNull(Tracing3DTest.class.getClassLoader().getResource("OP_1.tif")).getPath());
		assumeNotNull(imp);
		stats = imp.getStatistics(ImageStatistics.MIN_MAX | ImageStatistics.MEAN | ImageStatistics.STD_DEV);
		cal = imp.getCalibration();
		spacing = new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		img = ImageJFunctions.wrap(imp);
		opService = new Context(OpService.class).getService(OpService.class);
	}

	private <T extends RealType<T>> AbstractSearch createSearch(RandomAccessibleInterval<T> img,
																Cost cost,
																SNT.SearchType searchType,
																SNT.SearchImageType imageClass,
																SNT.HeuristicType heuristicType)
	{
		Heuristic heuristic;
		switch (heuristicType) {
			case EUCLIDEAN:
				heuristic = new Euclidean(cal);
				break;
			case DIJKSTRA:
				heuristic = new Dijkstra();
				break;
			default:
				throw new IllegalArgumentException("Unknown heuristic type " + heuristicType);
		}
		switch (searchType) {
			case ASTAR:
				return new TracerThread(
						img, cal,
						startX, startY, startZ,
						endX, endY, endZ,
						-1, 100,
						imageClass,
						cost,
						heuristic);
			case NBASTAR:
				return new BiSearch(
						img, cal,
						startX, startY, startZ,
						endX, endY, endZ,
						-1, 100,
						imageClass,
						cost,
						heuristic);
			default:
				throw new IllegalArgumentException("Unknown search type " + searchType);

		}
	}

	private void searchTest(final AbstractSearch search, final double minLength, final double maxLength) {
		search.run();
		Path result = search.getResult();
		assertNotNull(result);
		double length = result.getLength();
//		System.out.println(length);
		assertTrue(length >= minLength);
		assertTrue(length <= maxLength);
	}

	private void costTest(final Cost cost, final double minLength, final double maxLength) {
		searchTest(
				createSearch(img, cost, SNT.SearchType.ASTAR, ARRAY, SNT.HeuristicType.EUCLIDEAN),
				minLength, maxLength);
	}

	@Test
	public void testReciprocalCost() {
		costTest(new Reciprocal(stats.min, stats.max), 227.3, 227.4);
	}

	@Test
	public void testDifferenceCost() {
		costTest(new Difference(stats.min, stats.max), 240.5, 240.6);
	}

	@Test
	public void testDifferenceSqCost() {
		costTest(new DifferenceSq(stats.min, stats.max), 247, 248);
	}

	@Test
	public void testOneMinusErfCost() {
		OneMinusErf cost = new OneMinusErf(stats.max, stats.mean, stats.stdDev);
		costTest(cost, 222.9, 223.0);
		cost.setZFudge(0.2);
		costTest(cost, 341.0, 341.1);
	}

	@Test
	public void testSearchImageEquality() {
		for (SNT.SearchImageType searchImageType : SNT.SearchImageType.values()) {
			AbstractSearch search = createSearch(
					img,
					new Reciprocal(stats.min, stats.max), SNT.SearchType.ASTAR,
					searchImageType,
					SNT.HeuristicType.EUCLIDEAN);
			searchTest(search, 227.326, 227.327);
		}
	}

	@Test
	public void testAstarAdmissibility() {
		// Since Dijkstra is guaranteed to produce the optimal path (assuming our implementation is correct),
		//  compare the heuristic searches against that to ensure the A* implementations are correct
		AbstractSearch search = createSearch(
				img,
				new Reciprocal(stats.min, stats.max), SNT.SearchType.ASTAR,
				ARRAY,
				SNT.HeuristicType.DIJKSTRA);
		search.run();
		final double optimalLength = search.getResult().getLength();
		for (SNT.HeuristicType heuristicType : SNT.HeuristicType.values()) {
			for (SNT.SearchType searchType : SNT.SearchType.values()) {
				// SearchThread is not guaranteed to yield the optimal path
				// since it terminates as soon as the two opposing searches meet.
				// So ignore it for now.
				if (searchType == SNT.SearchType.ASTAR)
					continue;
				search = createSearch(
						img,
						new Reciprocal(stats.min, stats.max), searchType,
						ARRAY,
						heuristicType);
				search.run();
				assertEquals(optimalLength, search.getResult().getLength(), 1e-12);
			}
		}
	}

	private void filterTest(final RandomAccessibleInterval<FloatType> filteredImg, final Reciprocal cost,
							final double reductionFactor)
	{
		AbstractSearch filterSearch = createSearch(
				filteredImg,
				cost, SNT.SearchType.ASTAR,
				ARRAY,
				SNT.HeuristicType.EUCLIDEAN);
		filterSearch.run();
		assertNotNull(filterSearch.getResult());

		AbstractSearch search = createSearch(
				img,
				new Reciprocal(stats.min, stats.max), SNT.SearchType.ASTAR,
				ARRAY,
				SNT.HeuristicType.EUCLIDEAN);
		search.run();
		assertNotNull(search.getResult());

		assertTrue(filterSearch.pointsConsideredInSearch() <=
				search.pointsConsideredInSearch() * reductionFactor);
	}

	private RandomAccessibleInterval<FloatType> createLazyImg(
			UnaryComputerOp<RandomAccessibleInterval<UnsignedByteType>, RandomAccessibleInterval<FloatType>> op)
	{
		return Lazy.process(
				img,
				img,
				new int[]{60, 60, 60},
				new FloatType(),
				op);
	}

	//@Test
	public void testFrangi() {
		final double[] scales = new double[]{0.75};
		final RandomAccessibleInterval<FloatType> frangi = createLazyImg(new Frangi<>(scales, spacing, stats.max));
		ComputeMinMax<FloatType> cmm = new ComputeMinMax<>(frangi, new FloatType(), new FloatType());
		cmm.process();
		filterTest(frangi, new Reciprocal(cmm.getMin().getRealDouble(), cmm.getMax().getRealDouble() / 4.0),
				0.95);
	}

	//@Test
	public void testTubeness() {
		final double[] scales = new double[]{0.75};
		final RandomAccessibleInterval<FloatType> tubeness = createLazyImg(new Tubeness<>(scales, spacing));
		ComputeMinMax<FloatType> cmm = new ComputeMinMax<>(tubeness, new FloatType(), new FloatType());
		cmm.process();
		filterTest(tubeness, new Reciprocal(cmm.getMin().getRealDouble(), cmm.getMax().getRealDouble() / 4.0),
				0.8);
	}

	@Test
	public void testGauss() {
		double s = cal.pixelWidth * 2;
		double[] sigmas = new double[]{s / cal.pixelWidth, s / cal.pixelHeight, s / cal.pixelDepth};
		final RandomAccessibleInterval<FloatType> gaussian = Lazy.process(
				img,
				img,
				new int[]{60,60,60},
				new FloatType(),
				opService,
				net.imagej.ops.filter.gauss.DefaultGaussRAI.class,
				(Object) sigmas);
		ComputeMinMax<FloatType> cmm = new ComputeMinMax<>(gaussian, new FloatType(), new FloatType());
		cmm.process();
		Cost cost = new Reciprocal(cmm.getMin().getRealDouble(), cmm.getMax().getRealDouble());
		AbstractSearch search = createSearch(gaussian, cost, SNT.SearchType.ASTAR, ARRAY, SNT.HeuristicType.EUCLIDEAN);
		searchTest(search, 230.1, 230.2);
	}

}
