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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.tracing.auto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import sc.fiji.snt.util.ImgUtils;

/**
 * Characterizes an image before it is traced: intensity statistics, noise,
 * signal-to-noise ratio, foreground sparsity and the approximate width of the
 * bright structures, from which coarse auto-tracing defaults are suggested.
 * Unlike {@link AutoTraceConfig#fromPaths}, no example paths are needed, so the
 * result is a starting point to be refined once paths are available.
 * <p>
 * The image is only sampled (a coarse lattice for the statistics, and a small
 * window around the brightest samples for the structure scale), so probing is
 * cheap even for large or lazily loaded images
 * </p>
 *
 * @author Tiago Ferreira
 */
public final class ImageProbe {

	// Statistics are computed from at most this many lattice samples
	private static final int MAX_SAMPLES = 200_000;
	// Upper limit of the window (in voxels) used to estimate the structure scale
	private static final long[] MAX_WINDOW = { 192, 192, 48 };
	// Scales (sigma, in XY voxels) tested to find the width of the structures
	private static final double[] SIGMAS = { 0.7, 1, 1.4, 2, 2.8, 4, 5.6, 8 };
	private static final double DOG_RATIO = 1.6;
	private static final double MAD_TO_SIGMA = 1.4826;
	// Percentile defining the signal level
	private static final double SIGNAL_PERCENTILE = 99.5;

	private ImageProbe() {
	}

	/**
	 * The outcome of a probe. NaN is used for values that could not be determined
	 *
	 * @param dimensions         image dimensions in voxels
	 * @param spacing            voxel size [x, y, z] in physical units
	 * @param pixelType          simple name of the pixel type
	 * @param binary             whether the image only has two distinct values
	 * @param background         robust estimate (median) of the background level
	 * @param noise              robust estimate (scaled MAD) of the background
	 *                           noise. 0 for noise-free backgrounds
	 * @param signal             signal level (high percentile of the sampled
	 *                           intensities)
	 * @param snr                (signal - background) / noise. Infinite if there
	 *                           is no noise
	 * @param saturatedFraction  fraction of samples at the maximum value of the
	 *                           pixel type (integer types only)
	 * @param foregroundFraction fraction of samples clearly above the background
	 * @param anisotropy         ratio between largest and smallest voxel size
	 * @param radius             approximate radius of the structures, in physical
	 *                           units
	 * @param scoreMapScales     suggested scales (physical units) for the score
	 *                           map filter. Empty if the radius is unknown
	 * @param backgroundThreshold suggested background threshold (intensity)
	 * @param recommendations    advice on how to trace the image
	 * @param warnings           issues that are likely to compromise tracing
	 */
	public record Result(long[] dimensions, double[] spacing, String pixelType, boolean binary, double background,
			double noise, double signal, double snr, double saturatedFraction, double foregroundFraction,
			double anisotropy, double radius, double[] scoreMapScales, double backgroundThreshold,
			List<String> recommendations, List<String> warnings) {

		/** @return a multi-line, human-readable summary */
		public String summary() {
			final StringBuilder sb = new StringBuilder();
			sb.append(String.format("%s image, %s voxels%n", pixelType, Arrays.toString(dimensions)));
			if (binary) return sb.append("  Binary image\n").toString();
			sb.append(String.format("  Background: %.4g (noise %.4g), signal: %.4g%n", background, noise, signal));
			sb.append(String.format("  SNR: %.1f, foreground: %.2f%%, saturated: %.2f%%%n", snr,
					100 * foregroundFraction, 100 * saturatedFraction));
			if (!Double.isNaN(radius)) sb.append(String.format("  Approx. radius: %.3g%n", radius));
			if (!Double.isNaN(backgroundThreshold))
				sb.append(String.format("  Suggested background threshold: %.4g%n", backgroundThreshold));
			return sb.toString();
		}

		/** @return true if there are no warnings */
		public boolean isHealthy() {
			return warnings.isEmpty();
		}
	}

	/**
	 * Probes an image
	 *
	 * @param source  the grayscale image (2D or 3D)
	 * @param spacing voxel size [x, y, z] in physical units. Null for unit voxels
	 * @return the probe result
	 * @throws IllegalArgumentException if the image is not 2D or 3D, or is empty
	 */
	public static Result probe(final RandomAccessibleInterval<? extends RealType<?>> source,
			final double[] spacing) {
		final int n = source.numDimensions();
		if (n < 2 || n > 3) throw new IllegalArgumentException("Only 2D or 3D images can be probed");
		if (Intervals.numElements(source) == 0) throw new IllegalArgumentException("Image is empty");
		final double[] sp = normalizedSpacing(spacing);
		final List<String> recs = new ArrayList<>();
		final List<String> warns = new ArrayList<>();
		final long[] dims = Intervals.dimensionsAsLongArray(source);
		final String typeName = source.firstElement().getClass().getSimpleName();
		final double anisotropy = Math.max(sp[0], Math.max(sp[1], sp[2])) / Math.min(sp[0], Math.min(sp[1], sp[2]));

		// Pass 1: lattice sampling
		final Samples samples = sample(source);
		final double[] sorted = samples.values;
		Arrays.sort(sorted);
		if (sorted[0] == sorted[sorted.length - 1]) {
			warns.add("The image is uniform: there is nothing to trace");
			return new Result(dims, sp, typeName, false, sorted[0], 0, sorted[0], 0, 0, 0, anisotropy, Double.NaN,
					new double[0], Double.NaN, recs, warns);
		}
		if (isBinary(sorted)) {
			recs.add("The image is binary: trace it directly with the binary (skeletonization) tracer. "
					+ "Intensity-based settings do not apply");
			return new Result(dims, sp, typeName, true, sorted[0], 0, sorted[sorted.length - 1], Double.POSITIVE_INFINITY,
					0, fractionAbove(sorted, sorted[0]), anisotropy, Double.NaN, new double[0], Double.NaN, recs,
					warns);
		}

		final double background = percentile(sorted, 50);
		final double mad = mad(sorted, background);
		double noise = MAD_TO_SIGMA * mad;
		if (noise <= 0) noise = lowerSpread(sorted, background);
		final double signal = percentile(sorted, SIGNAL_PERCENTILE);
		final double contrast = signal - background;
		// A flat background (e.g., preprocessed or thresholded data) has no measurable noise: use the
		// quantization step (integer types) so that the SNR remains finite and comparable
		final boolean flat = noise <= 0;
		if (flat) noise = (source.firstElement() instanceof net.imglib2.type.numeric.IntegerType) ? 1d
				: 1e-3 * contrast;
		if (flat) recs.add("The background is flat (no measurable noise): the image seems preprocessed. The SNR "
				+ "is relative to the quantization step and tells little about noise");
		final double snr = contrast / noise;
		final double fgLevel = background + Math.max(3 * noise, 0.05 * contrast);
		final double foreground = fractionAbove(sorted, fgLevel);
		final double saturated = saturatedFraction(source.firstElement(), sorted);
		final double threshold = background + Math.max(3 * noise, 0.1 * contrast);

		// Pass 2: structure scale around the brightest samples
		final double radius = estimateRadius(source, samples, sp, signal, background);
		final double[] scales = Double.isNaN(radius) ? new double[0]
				: new double[] { 0.7 * radius, radius, 1.4 * radius };

		advise(recs, warns, dims, sp, anisotropy, snr, contrast, foreground, saturated, radius, typeName);
		return new Result(dims, sp, typeName, false, background, noise, signal, snr, saturated, foreground, anisotropy,
				radius, scales, threshold, recs, warns);
	}

	private static void advise(final List<String> recs, final List<String> warns, final long[] dims,
			final double[] sp, final double anisotropy, final double snr, final double contrast,
			final double foreground, final double saturated, final double radius, final String typeName) {
		if (snr < 3) warns.add(String.format("Very low signal-to-noise ratio (%.1f): tracing is likely to "
				+ "follow noise. Denoise the image or trace a cleaner region", snr));
		else if (snr < 6) recs.add(String.format("Low signal-to-noise ratio (%.1f): keep the score map filter "
				+ "enabled, and consider raising the background threshold", snr));
		if (saturated > 0.01) warns.add(String.format("%.1f%% of the samples are saturated: thin branches next "
				+ "to bright structures may be lost, and radii overestimated", 100 * saturated));
		if (foreground < 0.001) warns.add("Almost no foreground was found in the sampled voxels: the structures "
				+ "may be too sparse or too faint to be represented. Probing a region that contains them "
				+ "gives more reliable values");
		else if (foreground > 0.3) warns.add(String.format("%.0f%% of the samples are foreground: the "
				+ "background estimate may be unreliable. Probe a region with more background", 100 * foreground));
		if ("UnsignedByteType".equals(typeName) && contrast < 40)
			recs.add("The dynamic range in use is small (8-bit): contrast stretching before tracing may help");
		if (anisotropy >= 2) recs.add(String.format("Voxels are anisotropic (%.1fx): the score map accounts for "
				+ "physical units, but thin branches along the coarse axis may be fragmented", anisotropy));
		if (Double.isNaN(radius)) {
			recs.add("The width of the structures could not be estimated: use example paths instead");
		} else {
			final double avg = (sp[0] + sp[1]) / 2;
			if (radius / avg < 1.0) recs.add("The structures are about 1 voxel wide or thinner: 'High' "
					+ "connectivity better preserves them, at the cost of speed");
			else if (radius / avg > 0.9 * SIGMAS[SIGMAS.length - 1] * Math.sqrt(2)) warns.add("The structures "
					+ "are at the upper limit of the scales probed: they may not be tubular (e.g., somata)");
		}
		long voxels = 1;
		for (final long d : dims) voxels *= d;
		if (voxels > 200_000_000L) recs.add("The image is very large: trace a region of interest instead of the "
				+ "whole image");
	}

	private record Samples(double[] values, long[][] bright, double brightLevel) {
	}

	// Samples a regular lattice, keeping the position of the brightest samples
	private static Samples sample(final RandomAccessibleInterval<? extends RealType<?>> source) {
		final int n = source.numDimensions();
		final long total = Intervals.numElements(source);
		final long step = Math.max(1, Math.round(Math.pow((double) total / MAX_SAMPLES, 1d / n)));
		final long[] min = source.minAsLongArray();
		final long[] max = source.maxAsLongArray();
		final List<double[]> positions = new ArrayList<>();
		final List<Double> values = new ArrayList<>();
		final RandomAccess<? extends RealType<?>> ra = source.randomAccess();
		final long[] pos = min.clone();
		// Jitter avoids aliasing with regular structures
		final Random rnd = new Random(42);
		while (true) {
			final long[] p = pos.clone();
			for (int d = 0; d < n; d++) p[d] = Math.min(max[d], p[d] + (step > 1 ? rnd.nextInt((int) step) : 0));
			ra.setPosition(p);
			final double v = ra.get().getRealDouble();
			if (!Double.isNaN(v) && !Double.isInfinite(v)) {
				values.add(v);
				final double[] vp = new double[n + 1];
				for (int d = 0; d < n; d++) vp[d] = p[d];
				vp[n] = v;
				positions.add(vp);
			}
			int d = 0;
			while (d < n) {
				pos[d] += step;
				if (pos[d] <= max[d]) break;
				pos[d] = min[d];
				d++;
			}
			if (d == n) break;
		}
		final double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
		if (arr.length == 0) throw new IllegalArgumentException("Image has no finite values");
		final double[] copy = arr.clone();
		Arrays.sort(copy);
		final double level = percentile(copy, 99);
		final List<long[]> bright = new ArrayList<>();
		for (final double[] vp : positions) {
			if (vp[n] >= level) {
				final long[] p = new long[n];
				for (int d = 0; d < n; d++) p[d] = (long) vp[d];
				bright.add(p);
			}
		}
		return new Samples(arr, bright.toArray(new long[0][]), level);
	}

	// Window around the median position of the brightest samples
	private static FinalInterval window(final RandomAccessibleInterval<?> source, final long[][] bright) {
		final int n = source.numDimensions();
		final long[] min = new long[n];
		final long[] max = new long[n];
		for (int d = 0; d < n; d++) {
			final long[] coords = new long[bright.length];
			for (int i = 0; i < bright.length; i++) coords[i] = bright[i][d];
			Arrays.sort(coords);
			final long center = (coords.length == 0) ? (source.min(d) + source.max(d)) / 2
					: coords[coords.length / 2];
			final long size = Math.min(source.dimension(d), MAX_WINDOW[d]);
			long lo = center - size / 2;
			lo = Math.max(source.min(d), Math.min(lo, source.max(d) - size + 1));
			min[d] = lo;
			max[d] = lo + size - 1;
		}
		return new FinalInterval(min, max);
	}

	// Scale at which a multi-scale DoG responds most at the brightest voxels
	private static double estimateRadius(final RandomAccessibleInterval<? extends RealType<?>> source,
			final Samples samples, final double[] sp, final double signal, final double background) {
		final int n = source.numDimensions();
		final FinalInterval box = window(source, samples.bright);
		final long[] dims = Intervals.dimensionsAsLongArray(box);
		final int len = (int) Intervals.numElements(box);
		final float[] data = new float[len];
		final Cursor<? extends RealType<?>> c = Views.flatIterable(Views.interval(source, box)).cursor();
		for (int i = 0; c.hasNext(); i++) data[i] = (float) c.next().getRealDouble();
		final var img = ArrayImgs.floats(data, dims);

		// Voxels of the window with the highest intensities
		final float[] copy = data.clone();
		Arrays.sort(copy);
		final float level = copy[(int) Math.min(len - 1, Math.round(0.99 * (len - 1)))];
		final int[] idx = new int[len];
		int nIdx = 0;
		for (int i = 0; i < len; i++) if (data[i] >= level && data[i] > background) idx[nIdx++] = i;
		if (nIdx < 10) return Double.NaN;

		final double sxy = (sp[0] + sp[1]) / 2;
		final double[] response = new double[SIGMAS.length];
		for (int s = 0; s < SIGMAS.length; s++) {
			final double[] sigma = new double[n];
			for (int d = 0; d < n; d++) {
				final double phys = SIGMAS[s] * sxy;
				// Thin stacks are filtered in plane only
				sigma[d] = (d == 2 && dims[2] < 5) ? 0.2 : phys / sp[d];
			}
			final float[] g1 = new float[len];
			final float[] g2 = new float[len];
			final double[] sigma2 = Arrays.stream(sigma).map(v -> v * DOG_RATIO).toArray();
			Gauss3.gauss(sigma, Views.extendBorder(img), ArrayImgs.floats(g1, dims));
			Gauss3.gauss(sigma2, Views.extendBorder(img), ArrayImgs.floats(g2, dims));
			double sum = 0;
			for (int k = 0; k < nIdx; k++) sum += g1[idx[k]] - g2[idx[k]];
			response[s] = sum / nIdx;
		}
		int best = 0;
		for (int s = 1; s < response.length; s++) if (response[s] > response[best]) best = s;
		if (response[best] <= 0) return Double.NaN;
		double logSigma = Math.log(SIGMAS[best]);
		if (best > 0 && best < response.length - 1) {
			final double a = response[best - 1];
			final double b = response[best];
			final double cc = response[best + 1];
			final double denom = a - 2 * b + cc;
			if (denom < 0) {
				final double offset = Math.max(-0.5, Math.min(0.5, 0.5 * (a - cc) / denom));
				logSigma += offset * (Math.log(SIGMAS[best + 1]) - Math.log(SIGMAS[best]));
			}
		}
		// A bright tube of radius r responds most at sigma of about r / sqrt(2)
		return Math.exp(logSigma) * Math.sqrt(2) * sxy;
	}

	private static double[] normalizedSpacing(final double[] spacing) {
		final double[] sp = { 1, 1, 1 };
		if (spacing != null) {
			for (int d = 0; d < Math.min(3, spacing.length); d++) if (spacing[d] > 0) sp[d] = spacing[d];
		}
		return sp;
	}

	private static boolean isBinary(final double[] sorted) {
		int distinct = 1;
		for (int i = 1; i < sorted.length && distinct <= 2; i++) if (sorted[i] != sorted[i - 1]) distinct++;
		return distinct <= 2;
	}

	// Of an array that is already sorted
	private static double percentile(final double[] sorted, final double p) {
		return sorted[(int) Math.round(p / 100d * (sorted.length - 1))];
	}

	private static double mad(final double[] sorted, final double median) {
		final double[] dev = new double[sorted.length];
		for (int i = 0; i < dev.length; i++) dev[i] = Math.abs(sorted[i] - median);
		Arrays.sort(dev);
		return dev[dev.length / 2];
	}

	// Backgrounds that are mostly constant have a MAD of 0: use the lower half of the distribution and,
	// failing that, the standard deviation of the samples below the 90th percentile
	private static double lowerSpread(final double[] sorted, final double median) {
		final double spread = (median - percentile(sorted, 5)) / 1.645;
		if (spread > 0) return spread;
		final int n = (int) Math.max(2, Math.round(0.9 * sorted.length));
		double sum = 0;
		double sumSq = 0;
		for (int i = 0; i < n; i++) {
			sum += sorted[i];
			sumSq += sorted[i] * sorted[i];
		}
		final double mean = sum / n;
		return Math.sqrt(Math.max(0, sumSq / n - mean * mean));
	}

	private static double fractionAbove(final double[] sorted, final double level) {
		int lo = 0;
		int hi = sorted.length;
		while (lo < hi) {
			final int mid = (lo + hi) >>> 1;
			if (sorted[mid] > level) hi = mid;
			else lo = mid + 1;
		}
		return (double) (sorted.length - lo) / sorted.length;
	}

	private static double saturatedFraction(final RealType<?> type, final double[] sorted) {
		final double typeMax = (type instanceof UnsignedByteType) ? 255d
				: (type instanceof UnsignedShortType) ? 65535d : Double.NaN;
		if (Double.isNaN(typeMax)) return 0;
		return (sorted[sorted.length - 1] >= typeMax) ? 1d - (double) (sorted.length - fractionCount(sorted, typeMax))
				/ sorted.length : 0;
	}

	// Number of values below the given level
	private static int fractionCount(final double[] sorted, final double level) {
		int idx = Arrays.binarySearch(sorted, level);
		if (idx < 0) return -idx - 1;
		while (idx > 0 && sorted[idx - 1] == level) idx--;
		return idx;
	}

	/**
	 * Convenience for callers working with an {@link ij.ImagePlus}
	 *
	 * @param imp the image. Only the current channel and frame are probed
	 * @return the probe result, in the calibration of the image
	 */
	public static Result probe(final ij.ImagePlus imp) {
		final var rai = ImgUtils.getCtSlice3d(imp, imp.getChannel() - 1, imp.getFrame() - 1);
		final var cal = imp.getCalibration();
		return probe(Collections.unmodifiableList(List.of(rai)).getFirst(),
				new double[] { cal.pixelWidth, cal.pixelHeight, cal.pixelDepth });
	}

}
