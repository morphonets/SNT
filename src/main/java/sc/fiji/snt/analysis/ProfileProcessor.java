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

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imglib2.*;
import net.imglib2.algorithm.region.BresenhamLine;
import net.imglib2.algorithm.region.CircleCursor;
import net.imglib2.algorithm.region.hypersphere.HyperSphere;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Util;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.*;
import smile.math.MathEx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.StringUtils;

import static sc.fiji.snt.util.ImgUtils.outOfBounds;

/**
 * Profile intensities within local neighborhoods around {@link Path}
 * {@link sc.fiji.snt.util.PointInImage}s
 *
 * @param <T> pixel type
 * @author Cameron Arshadi
 */
public class ProfileProcessor<T extends RealType<T>> implements Callable<double[]> {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private final RandomAccessibleInterval<T> rai;
	private final Path path;
	private final double avgSep;
	private final long[] intervalMin;
	private final long[] intervalMax;
	private Metric metric = Metric.SUM;
	private Shape shape = Shape.HYPERSPHERE;
	private int radius = 0;
	private double[] values;

	public enum Metric {
		SUM, MIN, MAX, MEAN, MEDIAN, VARIANCE, SD, CV;

		@Override
		public String toString() {
			return (equals(SD) || equals(CV)) ? super.toString() : StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

	public enum Shape {
		NONE, LINE, CIRCLE, DISK, HYPERSPHERE;

		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

	public ProfileProcessor(final RandomAccessibleInterval<T> rai, final Path path) {
		this.path = (path.getUseFitted()) ? path.getFitted() : path;
		this.rai = rai;
		this.intervalMin = Intervals.minAsLongArray(rai);
		this.intervalMax = Intervals.maxAsLongArray(rai);
		final Calibration cal = path.getCalibration();
		if (rai.numDimensions() == 2) {
			avgSep = (cal.pixelWidth + cal.pixelHeight) / 2;
		} else {
			avgSep = (cal.pixelWidth + cal.pixelHeight + cal.pixelDepth) / 3;
		}
	}

	/**
	 * Gets the array of available {@link Metric}s
	 * 
	 * @return the Metric array
	 */
	public static Metric[] getMetrics() {
		return Metric.values();
	}

	/**
	 * Gets the array of available {@link Shape}s
	 * 
	 * @return the Shape array
	 */
	public static Shape[] getShapes() {
		return Shape.values();
	}

	/**
	 * Sets the metric to be computed for each local neighborhood. This setting is
	 * ignored if using {@link Shape#NONE}.
	 *
	 * @param metric
	 */
	public void setMetric(final Metric metric) {
		this.metric = metric;
	}

	/**
	 * Sets the shape to be iterated.
	 *
	 * @param shape
	 */
	public void setShape(final Shape shape) {
		this.shape = shape;
	}

	/**
	 * Specify a fixed radius for each {@link Shape} region around each
	 * {@link PointInImage}. Set to {@literal <= 0} to use the actual {@link PointInImage}
	 * radii.
	 *
	 * @param radius
	 */
	public void setRadius(final int radius) {
		this.radius = radius;
	}

	/**
	 * The profile values, or null if they have not been processed yet.
	 *
	 * @return the values
	 */
	public double[] getValues() {
		return values;
	}

	/**
	 * Gets the raw values for each node at the specified step interval.
	 *
	 * @param nodeStep the step interval between nodes
	 * @return a map of node indices to their raw values
	 */
	public SortedMap<Integer, List<Double>> getRawValues(final int nodeStep) {
		final SortedMap<Integer, List<Double>> rawValues = new TreeMap<>();
		int step = Math.max(nodeStep, 1);
		if (step > path.size())
			step = path.size();
		final int start = (shape == Shape.LINE) ? 1 : 0;
		final int end = (shape == Shape.LINE) ? path.size() - 1 : path.size();
		for (int i = start; i < end; ++i) {
			if (i % step != 0)
				continue;
			long r = (radius <= 0) ? Math.round(path.getNodeRadius(i) / avgSep) : radius;
			if (r < 1)
				r = 1;
			final Cursor<T> cursor = getSuitableCursor(rai, shape, r, path, i);
			if (cursor != null)
				rawValues.put(i, getAllValues(cursor));
		}
		return rawValues;
	}

	/**
	 * Processes the profile data by calling the computation.
	 */
	public void process() {
		call();
	}

	/**
	 * Process and return the profile values.
	 *
	 * @return the values.
	 * @throws ArrayIndexOutOfBoundsException if using CENTERLINE shape and any Path
	 *                                        nodes are outside the bounds of the
	 *                                        image
	 */
	@Override
	public double[] call() {
		values = new double[path.size()];

		if (path.size() == 1)
			return values;

		if (shape == Shape.NONE)
			return profilePathNodes(rai, path, values);

		final int start = (shape == Shape.LINE) ? 1 : 0;
		final int end = (shape == Shape.LINE) ? path.size() - 1 : path.size();
		for (int i = start; i < end; ++i) {
			long r = (radius <= 0) ? Math.round(path.getNodeRadius(i) / avgSep) : radius;
			if (r < 1)
				r = 1;

			final Cursor<T> cursor = getSuitableCursor(rai, shape, r, path, i);
			if (cursor == null)
				continue;

			final double value = switch (metric) {
                case SUM -> sum(cursor);
                case MIN -> min(cursor);
                case MAX -> max(cursor);
                case MEAN -> mean(cursor);
                case MEDIAN -> median(cursor);
                case VARIANCE -> variance(cursor);
                case SD -> Math.sqrt(variance(cursor));
                case CV -> cv(cursor);
                default -> throw new IllegalArgumentException("Unknown profiler method: " + metric);
            };
            values[i] = value;
		}
		return values;
	}

	/**
	 * Get the intensities for the point coordinates of a Path
	 *
	 * @param rai    the image
	 * @param path   the Path to profile
	 * @param values the array to store the values
	 * @param <T>
	 * @return the value array
	 * @throws ArrayIndexOutOfBoundsException if the Path contains any points
	 *                                        outside the image bounds
	 */
	public static <T extends RealType<T>> double[] profilePathNodes(final RandomAccessible<T> rai, final Path path,
			final double[] values) {
		final PathCursor<T> cursor = new PathCursor<>(rai, path);
		int i = 0;
		while (cursor.hasNext())
			values[i++] = cursor.next().getRealDouble();
		return values;
	}

	private static double[] getPlaneNormal(final Path path, final int i) {
		final double[] tangent = new double[3];
		path.getTangent(i, 1, tangent);
		if (Arrays.stream(tangent).allMatch(e -> e == 0))
			return null;

		LinAlgHelpers.normalize(tangent);
		return tangent;
	}

	private static <T> Cursor<T> getSuitableCursor(final RandomAccessible<T> rai, final Shape shape, final long radius,
			final Path path, final int i) {
		if (rai.numDimensions() == 2)
			return getSuitableCursor2d(rai, shape, radius, path, i);

		return getSuitableCursor3d(rai, shape, radius, path, i);
	}

	private static <T> Cursor<T> getSuitableCursor2d(final RandomAccessible<T> rai, final Shape shape,
			final long radius, final Path path, final int i) {
		final Localizable centerPoint = new Point(path.getXUnscaled(i), path.getYUnscaled(i));
		switch (shape) {
		case CIRCLE:
			return new CircleCursor<>(rai, centerPoint, radius);
		case HYPERSPHERE:
		case DISK:
			return new HyperSphere<>(rai, centerPoint, radius).cursor();
		case LINE:
			return getLineCursor(rai, radius, path, i);
		case NONE:
		default:
			throw new IllegalArgumentException("Unsupported shape: " + shape);

		}
	}

	private static <T> Cursor<T> getSuitableCursor3d(final RandomAccessible<T> rai, final Shape shape,
			final long radius, final Path path, final int i) {
		final Localizable centerPoint = new Point(path.getXUnscaled(i), path.getYUnscaled(i), path.getZUnscaled(i));
		switch (shape) {
		case CIRCLE: {
			final double[] circleNormal = getPlaneNormal(path, i);
			if (circleNormal == null)
				return null;
			return new CircleCursor3D<>(rai, centerPoint, radius, circleNormal);
		}

		case HYPERSPHERE:
			return new HyperSphere<>(rai, centerPoint, radius).cursor();

		case DISK: {
			final double[] circleNormal = getPlaneNormal(path, i);
			if (circleNormal == null)
				return null;
			return new DiskCursor3D<>(rai, centerPoint, radius, circleNormal);
		}
		case LINE:
			return getLineCursor(rai, radius, path, i);
		case NONE:
		default:
			throw new IllegalArgumentException("Unsupported shape: " + shape);

		}
	}

	private static <T> Cursor<T> getLineCursor(final RandomAccessible<T> rai, final long radius, final Path path,
			final int i) {
		if (i < 0 || i > path.size() - 1)
			throw new IllegalArgumentException("Position i out of bounds");
		final double x1 = path.getXUnscaled(i - 1);
		final double x2 = path.getXUnscaled(i + 1);
		final double y1 = path.getYUnscaled(i - 1);
		final double y2 = path.getYUnscaled(i + 1);
		final double phi = Math.atan((y2 - y1) / (x2 - x1));
		final double x0 = (x1 + x2) / 2;
		final double y0 = (y1 + y2) / 2;
		final double dx = radius * Math.sin(phi);
		final double dy = radius * Math.cos(phi);
		if (rai.numDimensions() == 3)
			return new BresenhamLine<T>(rai, new Point((int) (x0 - dx), (int) (y0 + dy), path.getZUnscaled(i)),
					new Point((int) (x0 + dx), (int) (y0 - dy), path.getZUnscaled(i)));
		return new BresenhamLine<T>(rai, new Point((int) (x0 - dx), (int) (y0 + dy)),
				new Point((int) (x0 + dx), (int) (y0 - dy)));
	}

	private List<Double> getAllValues(final Cursor<T> cursor) {
		final List<Double> values = new ArrayList<>();
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (!outOfBounds(pos, intervalMin, intervalMax))
				values.add(cursor.get().getRealDouble());
		}
		return values;
	}

	private double sum(final Cursor<T> cursor) {
		double sum = 0;
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			sum += cursor.get().getRealDouble();
		}
		return sum;
	}

	private double min(final Cursor<T> cursor) {
		double min = Double.MAX_VALUE;
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			min = Math.min(min, cursor.get().getRealDouble());
		}
		return min;
	}

	private double max(final Cursor<T> cursor) {
		double max = -Double.MAX_VALUE;
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			max = Math.max(max, cursor.get().getRealDouble());
		}
		return max;
	}

	private double mean(final Cursor<T> cursor) {
		double sum = 0;
		long count = 0;
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			sum += cursor.get().getRealDouble();
			count++;
		}
		return sum / (double) count;
	}

	private double median(final Cursor<T> cursor) {
		final List<Double> vals = new ArrayList<>();
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			vals.add(cursor.get().getRealDouble());
		}
		// This is nearly twice as fast as SMILE's median implementation
		return Util.median(vals.stream().mapToDouble(Double::doubleValue).toArray());
	}

	private double variance(final Cursor<T> cursor) {
		final List<Double> vals = new ArrayList<>();
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			vals.add(cursor.get().getRealDouble());
		}
		return (vals.size() == 1) ? 0 : MathEx.var(vals.stream().mapToDouble(Double::doubleValue).toArray());
	}

	private double cv(final Cursor<T> cursor) {
		double sum = 0;
		long count = 0;
		final List<Double> vals = new ArrayList<>();
		while (cursor.hasNext()) {
			cursor.fwd();
			final long[] pos = new long[cursor.numDimensions()];
			cursor.localize(pos);
			if (outOfBounds(pos, intervalMin, intervalMax))
				continue;
			final double v = cursor.get().getRealDouble();
			sum += v;
			count++;
			vals.add(v);
		}
		if (vals.size() == 1 || count == 0) return 0;
		final double mean = sum / (double) count;
		final double variance = MathEx.var(vals.stream().mapToDouble(Double::doubleValue).toArray());
		return Math.sqrt(variance) / mean;
	}

	@SuppressWarnings("unused")
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService snt = new SNTService();
		final Tree t = snt.demoTree("OP_1");
		final ImagePlus imp = snt.demoImage("OP_1");
		t.assignImage(imp);
		final Img<UnsignedByteType> img = ImageJFunctions.wrapReal(imp);
		final ProfileProcessor<UnsignedByteType> profiler = new ProfileProcessor<>(img, t.get(0));
		for (final Shape s : ProfileProcessor.getShapes()) {
			profiler.setShape(s);
			profiler.setRadius(5);
			for (final Metric m : ProfileProcessor.getMetrics()) {
				profiler.setMetric(m);
				final long t0 = System.currentTimeMillis();
				final double[] values = profiler.call();
				System.out.printf("%s %s %d ms%n", s, m, System.currentTimeMillis() - t0);
				//System.out.println(Arrays.toString(values));
			}
		}
		System.out.println("done");
	}
}
