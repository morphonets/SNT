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

import java.util.*;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import sc.fiji.snt.util.ImgUtils;

/**
 * Characterizes an image before it is traced: intensity statistics, noise,
 * signal-to-noise ratio, foreground sparsity and saturation, with advice on
 * issues that are likely to compromise tracing. It is a diagnostic only: it does
 * not suggest tracing parameters, which are better derived from example paths
 * ({@link AutoTraceConfig#fromPaths}) or by the tracer itself.
 * <p>
 * The image is only sampled on a coarse lattice, so probing is cheap even for
 * large or lazily loaded images
 * </p>
 *
 * @author Tiago Ferreira
 */
public final class ImageProbe {

	// Statistics are computed from at most this many lattice samples
	private static final int MAX_SAMPLES = 200_000;
	private static final double MAD_TO_SIGMA = 1.4826;
	// Percentile defining the signal level. High, as the foreground of neuronal images is sparse
	private static final double SIGNAL_PERCENTILE = 99.9;

	private ImageProbe() {
	}

	/**
	 * The outcome of a probe. All values are rough estimates from a sample of voxels, and are less reliable
	 * for images with sparse foreground
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
	 * @param recommendations    advice on how to trace the image
	 * @param warnings           issues that are likely to compromise tracing
	 */
	public record Result(long[] dimensions, double[] spacing, String pixelType, boolean binary, double background,
			double noise, double signal, double snr, double saturatedFraction, double foregroundFraction,
			double anisotropy, List<String> recommendations, List<String> warnings) {

		/** @return a multi-line, human-readable summary */
		public String summary() {
			final StringBuilder sb = new StringBuilder();
			sb.append(String.format("%s image, %s voxels%n", pixelType, Arrays.toString(dimensions)));
			if (binary) return sb.append("  Binary image\n").toString();
			sb.append(String.format("  Background: %.4g (noise %.4g), signal: %.4g%n", background, noise, signal));
			sb.append(String.format("  Est. SNR: %.1f, est. foreground: %.2f%%, est. saturated: %.2f%%%n", snr,
					100 * foregroundFraction, 100 * saturatedFraction));
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
		final double[] sorted = sample(source);
		Arrays.sort(sorted);
		if (sorted[0] == sorted[sorted.length - 1]) {
			warns.add("The image is uniform: there is nothing to trace");
			return new Result(dims, sp, typeName, false, sorted[0], 0, sorted[0], 0, 0, 0, anisotropy, recs, warns);
		}
		if (isBinary(sorted)) {
			recs.add("The image is binary: trace it directly with the binary (skeletonization) tracer. "
					+ "Intensity-based settings do not apply");
			return new Result(dims, sp, typeName, true, sorted[0], 0, sorted[sorted.length - 1], Double.POSITIVE_INFINITY,
					0, fractionAbove(sorted, sorted[0]), anisotropy, recs, warns);
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
		final boolean integer = source.firstElement() instanceof net.imglib2.type.numeric.IntegerType;
		if (flat) noise = integer ? 1d : 1e-3 * contrast;
		// Integer data cannot resolve noise below one gray level: this also bounds the SNR by the dynamic range
		else if (integer && noise < 1d) noise = 1d;
		if (flat) recs.add("The background is flat (no measurable noise): the image seems preprocessed. The estimated "
				+ "SNR is relative to the quantization step and tells little about noise");
		final double snr = contrast / noise;
		final double fgLevel = background + Math.max(3 * noise, 0.05 * contrast);
		final double foreground = fractionAbove(sorted, fgLevel);
		final double saturated = saturatedFraction(source.firstElement(), sorted);
		advise(recs, warns, dims, anisotropy, snr, contrast, foreground, saturated, typeName);
		return new Result(dims, sp, typeName, false, background, noise, signal, snr, saturated, foreground, anisotropy,
				recs, warns);
	}

	private static void advise(final List<String> recs, final List<String> warns, final long[] dims,
			final double anisotropy, final double snr, final double contrast, final double foreground,
			final double saturated, final String typeName) {
		if (snr < 3) warns.add(String.format("The estimated signal-to-noise ratio is very low (%.1f): tracing may "
				+ "follow noise. Denoising the image may help", snr));
		else if (snr < 6) recs.add(String.format("The estimated signal-to-noise ratio is low (%.1f): keep the score map filter "
				+ "enabled, and consider raising the background threshold", snr));
		if (saturated > 0.01) warns.add(String.format("An estimated %.1f%% of the voxels are saturated: thin branches next "
				+ "to bright structures may be lost, and radii overestimated", 100 * saturated));
		if (foreground < 0.001) warns.add("Almost no foreground was found in the sampled voxels: the structures "
				+ "may be too sparse or too faint to be represented, so the estimates here are unreliable");
		else if (foreground > 0.3) warns.add(String.format("An estimated %.0f%% of the voxels are foreground: the "
				+ "background estimate may be unreliable", 100 * foreground));
		if ("UnsignedByteType".equals(typeName) && contrast < 40)
			recs.add("The dynamic range in use is small (8-bit): contrast stretching before tracing may help");
		if (anisotropy >= 2) recs.add(String.format("Voxels are anisotropic (%.1fx): the score map accounts for "
				+ "physical units, but thin branches along the coarse axis may be fragmented", anisotropy));
		long voxels = 1;
		for (final long d : dims) voxels *= d;
		if (voxels > 200_000_000L) recs.add("The image is very large: tracing it may be slow and memory-intensive");
	}

	// Samples a jittered regular lattice
	private static double[] sample(final RandomAccessibleInterval<? extends RealType<?>> source) {
		final int n = source.numDimensions();
		final long total = Intervals.numElements(source);
		final long step = Math.max(1, Math.round(Math.pow((double) total / MAX_SAMPLES, 1d / n)));
		final long[] min = source.minAsLongArray();
		final long[] max = source.maxAsLongArray();
		final List<Double> values = new ArrayList<>();
		final RandomAccess<? extends RealType<?>> ra = source.randomAccess();
		final long[] pos = min.clone();
		final long[] p = new long[n];
		// Jitter avoids aliasing with regular structures
		final Random rnd = new Random(42);
		while (true) {
			for (int d = 0; d < n; d++) p[d] = Math.min(max[d], pos[d] + (step > 1 ? rnd.nextInt((int) step) : 0));
			ra.setPosition(p);
			final double v = ra.get().getRealDouble();
			if (!Double.isNaN(v) && !Double.isInfinite(v)) values.add(v);
			int d = 0;
			while (d < n) {
				pos[d] += step;
				if (pos[d] <= max[d]) break;
				pos[d] = min[d];
				d++;
			}
			if (d == n) break;
		}
		if (values.isEmpty()) throw new IllegalArgumentException("Image has no finite values");
		return values.stream().mapToDouble(Double::doubleValue).toArray();
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

	// Backgrounds that are mostly constant have a MAD (median absolute deviation) of 0: use the lower half of
	// the distribution and, failing that, the standard deviation of the samples below the 90th percentile
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
		return probe(List.of(rai).getFirst(), new double[] { cal.pixelWidth, cal.pixelHeight, cal.pixelDepth });
	}

}
