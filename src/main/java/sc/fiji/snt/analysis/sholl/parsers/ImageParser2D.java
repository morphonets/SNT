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
package sc.fiji.snt.analysis.sholl.parsers;

import java.util.*;

import org.apache.commons.math3.stat.StatUtils;
import org.scijava.Context;

import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.TypeConverter;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.util.ShollPoint;

/**
 * Sholl Parser for 2D images
 *
 * @author Tiago Ferreira
 */
public class ImageParser2D extends ImageParser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private ImageProcessor ip;
	private final boolean doSpikeSuppression;
	private boolean aggressiveSpikeSuppression;
	private int nSpans;
	private int spanType;
	private int slice;

	/** Flag for integration of repeated measures: average */
	public static final int MEAN = 0;
	/** Flag for integration of repeated measures: median */
	public static final int MEDIAN = 1;
	/** Flag for integration of repeated measures: mode */
	public static final int MODE = 2;
	private static final int NONE = -1;
	public final int MAX_N_SPANS = 10;

	public ImageParser2D(final ImagePlus imp) {
		this(imp, SNTUtils.getContext());
	}

	public ImageParser2D(final ImagePlus imp, final Context context) {
		super(imp, context);
		setPosition(imp.getC(), imp.getZ(), imp.getT());
		doSpikeSuppression = true;
		aggressiveSpikeSuppression = false;
	}

	@SuppressWarnings("unused")
	public void setCenterPx(final int x, final int y) {
		super.setCenterPx(x, y, 1);
	}

	public void setCenter(final double x, final double y) {
		super.setCenter(x, y, 0);
	}

	/**
	 * Sets experimental spike suppression mode.
	 * <p>
	 * When sampling binarized images, jagged (ladder-like) foreground edges can
	 * produce single-pixel "spikes" that go tangent to the sampling shell,
	 * registering as spurious intersections. This is not an algorithmic problem
	 * with Sholl analysis itself: it is an inherent consequence of rasterized
	 * circles intersecting rasterized structures.
	 * </p>
	 * <p>
	 * By default, (off), SNT uses legacy stair-aware suppression that only removes
	 * isolated ring hits matching specific diagonal staircase patterns in the
	 * source image. This is the same behavior as v4.x and is safe for all image
	 * types including skeletonized (1-pixel wide) structures.
	 * </p>
	 * <p>
	 * When enabled, SNT applies a stricter 1D morphological opening on the ring:
	 * <em>any</em> foreground pixel whose immediate ring neighbors are both
	 * background is suppressed. This effectively removes single-pixel noise but
	 * will also suppress genuine intersections where a thin neurite crosses the
	 * sampling shell at a steep angle, producing exactly one foreground pixel on
	 * the ring. <b>Do not use with skeletonized images.</b>
	 * </p>
	 *
	 * @param enabled if true, applies aggressive 1D ring suppression (suitable
	 *          for thick processes only). If false (default), uses legacy
	 *          stair-aware suppression compatible with all image types.
	 */
	public void setAggressiveSpikeSuppression(final boolean enabled) {
		aggressiveSpikeSuppression = enabled;
	}

	@Override
	public void setRadii(final double startRadius, final double step, final double endRadius) {
		setRadii(startRadius, step, endRadius, 1, NONE);
	}

	public void setRadii(final double startRadius, final double step, final double endRadius, final int span,
						 final int integrationFlag) {
		super.setRadii(startRadius, step, endRadius);
		setRadiiSpan(span, integrationFlag);
	}

	public void setRadiiSpan(final int nSamples, final int integrationFlag) {
		nSpans = Math.max(1, Math.min(MAX_N_SPANS, nSamples));
		properties.setProperty(KEY_NSAMPLES, String.valueOf(nSpans));
		switch (integrationFlag) {
			case NONE:
				if (nSamples > 1)
					throw new IllegalArgumentException("Integration flag required when nSamples > 1");
				break;
			case MEDIAN:
				properties.setProperty(KEY_NSAMPLES_INTG, INTG_MEDIAN);
				break;
			case MODE:
				properties.setProperty(KEY_NSAMPLES_INTG, INTG_MODE);
				break;
			case MEAN:
				properties.setProperty(KEY_NSAMPLES_INTG, INTG_MEAN);
				break;
			default:
				throw new IllegalArgumentException("Unrecognized integration flag");
		}
		spanType = integrationFlag;
	}

	@Override
	public Profile getProfile() {
		if (profile == null || profile.isEmpty()) parse();
		return super.getProfile();
	}

	@Override
	public void parse() {
		super.parse();
		ip = getProcessor();

		double[] binSamples;
		double[] lenSamples;
		int[] pixels;
		int[][] points;

		final int size = radii.size();

		// Create array for bin samples. Passed value of binSize must be at least 1
		binSamples = new double[nSpans];
		lenSamples = new double[nSpans];

		statusService.showStatus(
				"Sampling " + size + " radii, " + nSpans + " measurement(s) per radius. Press 'Esc' to abort...");

		// Outer loop to control the analysis bins
		int i = 0;
		for (final Double radius : radii) {

			// Reset samples from previous radius
			Arrays.fill(binSamples, 0);
			Arrays.fill(lenSamples, 0);

			// Retrieve the radius in pixel coordinates and set the largest radius of this bin span
			int intRadius = (int) Math.round(radius / voxelSize + (double) nSpans / 2);
			final Set<ShollPoint> pointsList = new HashSet<>();

			// Inner loop to gather samples for each sample
			int nCollected = 0;
			for (int s = 0; s < nSpans; s++) {

				if (intRadius < 1)
					break;

				// Get the circumference pixels for this int radius
				points = getCircumferencePoints(xc, yc, intRadius--);
				pixels = getPixels(points);

				// Spike suppression on lengthMask only: intersection counting uses its own
				// legacy path (groupPositionsLegacy + removeSinglePixelsLegacy) to preserve
				// backward compatibility with v4.x
				final int[] lengthMask = Arrays.copyOf(pixels, pixels.length);
				if (doSpikeSuppression) {
					if (aggressiveSpikeSuppression) {
						suppressSinglePixelsOnRing(lengthMask); // modifies mask in place
					} else {
						suppressLegacySinglePixelsOnRing(points, lengthMask); // modifies mask in place
					}
				}
				lenSamples[s] = lengthOnRingFromMaskedPixels(points, lengthMask, cal);

				if (isRetrieveIntDensitiesSet()) {
					// Intensity mode: no need to compute connected components
					double sum = 0;
					for (final float v : getPixelIntensities(points)) sum += v;
					binSamples[s] = sum / points.length;
				} else {
					// Intersections mode: use aggressive ring-runs or legacy 8-connected grouping
					final Set<ShollPoint> thisBinIntersPoints;
					if (aggressiveSpikeSuppression) {
						thisBinIntersPoints = groupPositionsOnRing(points, lengthMask);
					} else {
						thisBinIntersPoints = targetGroupsPositionsLegacy(pixels, points);
					}
					binSamples[s] = thisBinIntersPoints.size();
					pointsList.addAll(thisBinIntersPoints);
				}
				nCollected++;
			}
			statusService.showProgress(i++, size * nSpans);

			// Statistically combine bin data, using only the samples actually collected.
			// If intRadius drops below 1 mid-span, remaining slots stay at 0.0 and must
			// be excluded to avoid deflating the result.
			double counts = 0;
			double length = 0;
			if (nCollected == 0) {
				// No valid samples for this radius: record zero
			} else if (nCollected == 1 || nSpans == 1) {
				counts = binSamples[0];
				length = lenSamples[0];
			} else {
				final double[] usableBins = Arrays.copyOf(binSamples, nCollected);
				final double[] usableLens = Arrays.copyOf(lenSamples, nCollected);
				if (spanType == MEDIAN) { // 50th percentile
					counts = StatUtils.percentile(usableBins, 50);
					length = StatUtils.percentile(usableLens, 50);
				} else if (spanType == MEAN) { // mean
					counts = StatUtils.mean(usableBins);
					length = StatUtils.mean(usableLens);
				} else if (spanType == MODE) { // the 1st max freq. element
					counts = StatUtils.mode(usableBins)[0];
					length = StatUtils.mode(usableLens)[0];// mode is nonsensical for continuous values of lengths!
				}
			}
			profile.add(new ProfileEntry(radius, counts, length, pointsList)); // pointsList already in cal. units

		}

		clearStatus();
	}

	private float[] getPixelIntensities(final int[][] points) {

		// Initialize the array to hold the pixel values. float
		// arrays are initialized to a default value of 0f
		final float[] pixels = new float[points.length];

		// Put the pixel value for each circumference point in the pixel array
		for (int i = 0; i < pixels.length; i++) {

			// We already filtered out-of-bounds coordinates in getCircumferencePoints
			final int x = points[i][0];
			final int y = points[i][1];
			if (withinXYbounds(x, y)) {
				final float value = (ip instanceof FloatProcessor) ? Float.intBitsToFloat(ip.getPixel(x, y)) : ip.getPixel(x, y);
				if (withinThreshold(value)) pixels[i] = value;
			}
		}
		return pixels;
	}

	private int[] getPixels(final int[][] points) {

		// Initialize the array to hold the pixel values. int arrays are
		// initialized to a default value of 0
		final int[] pixels = new int[points.length];

		// Put the pixel value for each circumference point in the pixel array
		for (int i = 0; i < pixels.length; i++) {

			// We already filtered out-of-bounds coordinates in getCircumferencePoints
			if (withinBoundsAndThreshold(points[i][0], points[i][1]))
				pixels[i] = 1;
		}

		return pixels;

	}

	private boolean withinBoundsAndThreshold(final int x, final int y) {
		return withinXYbounds(x, y) && withinThreshold(ip.getPixel(x, y));
	}

	public void setPosition(final int channel, final int slice, final int frame) {
		if (slice < 1 || slice > imp.getNSlices())
			throw new IllegalArgumentException("Specified slice position is out of range");
		this.slice = slice; // 1-based
		minZ = maxZ = slice - 1; // 0-based
		properties.setProperty(KEY_SLICE_POS, String.valueOf(slice));
		super.setPosition(channel, frame);
	}

	private ImageProcessor getProcessor() {
		imp.setPositionWithoutUpdate(channel, slice, frame);
		final ImageProcessor ip = imp.getChannelProcessor();
		if (ip instanceof FloatProcessor || ip instanceof ColorProcessor)
			return new TypeConverter(ip, false).convertToShort();
		return ip;
	}

	/**
	 * Returns one representative point (component) per contiguous foreground run on the ring.
	 * Representative positions are emitted as IMAGE pixel coordinates (the middle sample of each run)
	 * and carry the image Calibration
	 */
	private Set<ShollPoint> groupPositionsOnRing(final int[][] ring, final int[] mask) {
		final int n = (ring == null) ? 0 : ring.length;
		final LinkedHashSet<ShollPoint> out = new LinkedHashSet<>();
		if (n == 0 || mask == null || mask.length != n) return out;

		// Collect linear runs of foreground pixels (indices on the ring)
		final ArrayList<int[]> runs = new ArrayList<>(); // {start, end, count}
		int i = 0;
		while (i < n) {
			// skip background
			while (i < n && mask[i] == 0) i++;
			if (i >= n) break;
			// accumulate a run of ones
			final int start = i;
			int count = 0;
			while (i < n && mask[i] != 0) {
				count++;
				i++;
			}
			final int end = i - 1;
			runs.add(new int[] { start, end, count });
		}

		// Merge wrap-around if both ends are foreground: last run + first run
		if (!runs.isEmpty() && mask[0] != 0 && mask[n - 1] != 0 && runs.size() > 1) {
			final int[] first = runs.getFirst();
			final int[] last = runs.getLast();
			// merged run spans last.start ... first.end (cyclic)
			final int mergedCount = last[2] + first[2];
			// replace first with merged, remove last
			runs.set(0, new int[] { last[0], first[1], mergedCount });
			runs.removeLast();
		}
		// Emit one IMAGE-SPACE pixel per run (middle sample), with calibration
		for (final int[] r : runs) {
			final int start = r[0], count = r[2];
			// pick the middle index of the run, modulo n for wrap-around runs
			final int mid = (start + (count >>> 1)) % n;
			final int ix = ring[mid][0];
			final int iy = ring[mid][1];
			out.add(new ShollPoint(ix, iy, cal));
		}
		return out;
	}

	/**
	 * Suppress 1-pixel "spikes" on a rasterized ring mask in-place (wrap-around aware).
	 * A spike is a foreground pixel whose immediate ring neighbors are both background.
	 * This is a 1D morphological opening on the circular sequence.
	 *
	 * @param mask int[N] ring-aligned 0/1 mask (modified in place)
	 */
	private static void suppressSinglePixelsOnRing(final int[] mask) {
		final int n = (mask == null) ? 0 : mask.length;
		if (n == 0) return;
		// Mark drops first so neighbor tests are not affected during the scan
		final boolean[] drop = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (mask[i] == 0) continue;
			final int l = (i - 1 + n) % n;
			final int r = (i + 1) % n;
			if (mask[l] == 0 && mask[r] == 0) drop[i] = true;
		}
		for (int i = 0; i < n; i++) if (drop[i]) mask[i] = 0;
	}

	private Set<ShollPoint> targetGroupsPositionsLegacy(final int[] pixels, final int[][] rawpoints) {
		int i, j;

		// Count how many target pixels (i.e., foreground, non-zero) we have
		for (i = 0, j = 0; i < pixels.length; i++) {
			if (pixels[i] != 0) j++;
		}

		// Create an array to hold target pixels
		final int[][] points = new int[j][2];

		// Copy all target pixels into the array, preserving ring order
		for (i = 0, j = 0; i < pixels.length; i++) {
			if (pixels[i] != 0) points[j++] = rawpoints[i];
		}

		return groupPositionsLegacy(points);
	}

	private void removeSinglePixelsLegacy(final int[][] points, final int pointsLength, final int[] grouping,
										  final HashSet<Integer> positions) {

		for (int i = 0; i < pointsLength; i++) {

			// Check for other members of this group
			boolean multigroup = false;
			for (int j = 0; j < pointsLength; j++) {
				if (i == j) continue;
				if (grouping[i] == grouping[j]) {
					multigroup = true;
					break;
				}
			}

			// If not a single-pixel group, try again
			if (multigroup) continue;

			// Store the coordinates of this point
			final int dx = points[i][0];
			final int dy = points[i][1];

			// Calculate the 8 neighbors surrounding this point
			final int[][] testpoints = new int[8][2];
			testpoints[0][0] = dx - 1;
			testpoints[0][1] = dy + 1;
			testpoints[1][0] = dx;
			testpoints[1][1] = dy + 1;
			testpoints[2][0] = dx + 1;
			testpoints[2][1] = dy + 1;
			testpoints[3][0] = dx - 1;
			testpoints[3][1] = dy;
			testpoints[4][0] = dx + 1;
			testpoints[4][1] = dy;
			testpoints[5][0] = dx - 1;
			testpoints[5][1] = dy - 1;
			testpoints[6][0] = dx;
			testpoints[6][1] = dy - 1;
			testpoints[7][0] = dx + 1;
			testpoints[7][1] = dy - 1;

			// Pull out the pixel values for these points
			final int[] px = getPixels(testpoints);

			// Stair checks (legacy behavior)
			if ((px[0] != 0 && px[1] != 0 && px[3] != 0 && px[4] == 0 && px[6] == 0 && px[7] == 0)
					|| (px[1] != 0 && px[2] != 0 && px[4] != 0 && px[3] == 0 && px[5] == 0 && px[6] == 0)
					|| (px[4] != 0 && px[6] != 0 && px[7] != 0 && px[0] == 0 && px[1] == 0 && px[3] == 0)
					|| (px[3] != 0 && px[5] != 0 && px[6] != 0 && px[1] == 0 && px[2] == 0 && px[4] == 0)) {
				positions.remove(i);
			}
		}
	}

	private Set<ShollPoint> groupPositionsLegacy(final int[][] points) {
		final int len = points.length;
		final Set<ShollPoint> sPoints = new HashSet<>();

		if (len == 0) return sPoints;

		// Create an array to hold the point grouping data
		final int[] grouping = new int[len];

		// Initialize each point to be in a unique group
		final HashSet<Integer> positions = new HashSet<>();
		for (int i = 0; i < len; i++) {
			grouping[i] = i + 1;
			positions.add(i);
		}

		for (int i = 0; i < len; i++) {
			for (int j = 0; j < len; j++) {

				// Don't compare the same point with itself
				if (i == j) continue;

				// Chessboard (Chebyshev) distance, 8-connectivity in plane
				final ShollPoint p1 = new ShollPoint(points[i][0], points[i][1]);
				final ShollPoint p2 = new ShollPoint(points[j][0], points[j][1]);

				// Should these two points be in the same group?
				if ((p1.chebyshevXYdxTo(p2) <= 1) && (grouping[i] != grouping[j])) {
					final int source = grouping[i];
					final int target = grouping[j];

					// Change all targets to sources
					for (int k = 0; k < len; k++) {
						if (grouping[k] == target) grouping[k] = source;
					}

					// Remove redundant position
					positions.remove(j);
				}
			}
		}

		// Compare first and last positions on the circumference and merge if connected
		final int firstIdx = 0;
		final int lastIdx = len - 1;
		final ShollPoint p1 = new ShollPoint(points[firstIdx][0], points[firstIdx][1]);
		final ShollPoint p2 = new ShollPoint(points[lastIdx][0], points[lastIdx][1]);
		if (p1.chebyshevXYdxTo(p2) <= 1) positions.remove(firstIdx);

		// Do legacy spike suppression
		if (doSpikeSuppression) {
			removeSinglePixelsLegacy(points, len, grouping, positions);
		}

		// Return Sholl points in calibrated units
		positions.forEach(pos -> sPoints.add(new ShollPoint(points[pos][0], points[pos][1], cal)));
		return sPoints;
	}

	/**
	 * Legacy spike suppression: only removes isolated ring hits that match
	 * stair-like diagonal artifacts in the source image.
	 */
	private void suppressLegacySinglePixelsOnRing(final int[][] ring, final int[] mask) {
		final int n = (ring == null) ? 0 : ring.length;
		if (n == 0 || mask == null || mask.length != n) return;

		final boolean[] drop = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (mask[i] == 0) continue;
			final int left = (i - 1 + n) % n;
			final int right = (i + 1) % n;
			if (mask[left] != 0 || mask[right] != 0) continue; // only isolated ring samples

			final int dx = ring[i][0];
			final int dy = ring[i][1];

			final int[][] testpoints = new int[8][2];
			testpoints[0][0] = dx - 1;
			testpoints[0][1] = dy + 1;
			testpoints[1][0] = dx;
			testpoints[1][1] = dy + 1;
			testpoints[2][0] = dx + 1;
			testpoints[2][1] = dy + 1;
			testpoints[3][0] = dx - 1;
			testpoints[3][1] = dy;
			testpoints[4][0] = dx + 1;
			testpoints[4][1] = dy;
			testpoints[5][0] = dx - 1;
			testpoints[5][1] = dy - 1;
			testpoints[6][0] = dx;
			testpoints[6][1] = dy - 1;
			testpoints[7][0] = dx + 1;
			testpoints[7][1] = dy - 1;

			final int[] px = getPixels(testpoints);
			if ((px[0] != 0 && px[1] != 0 && px[3] != 0 && px[4] == 0 && px[6] == 0 && px[7] == 0)
					|| (px[1] != 0 && px[2] != 0 && px[4] != 0 && px[3] == 0 && px[5] == 0 && px[6] == 0)
					|| (px[4] != 0 && px[6] != 0 && px[7] != 0 && px[0] == 0 && px[1] == 0 && px[3] == 0)
					|| (px[3] != 0 && px[5] != 0 && px[6] != 0 && px[1] == 0 && px[2] == 0 && px[4] == 0)) {
				drop[i] = true;
			}
		}
		for (int i = 0; i < n; i++) if (drop[i]) mask[i] = 0;
	}

	/** Linearized length on a ring from a 0/1 mask; no extra allocations. */
	private static double lengthOnRingFromMaskedPixels(final int[][] ring, final int[] mask,
													   final ij.measure.Calibration cal) {
		final double pw = (cal != null && cal.pixelWidth  > 0) ? cal.pixelWidth  : 1.0;
		final double ph = (cal != null && cal.pixelHeight > 0) ? cal.pixelHeight : 1.0;
		final int n = ring.length;
		if (n == 0) return 0.0;
		double length = 0.0;
		for (int i = 0; i < n; i++) {
			final int j = (i + 1) % n; // wrap-around
			if (mask[i] != 0 && mask[j] != 0) {
				final int[] a = ring[i], b = ring[j];
				length += Math.hypot((b[0] - a[0]) * pw, (b[1] - a[1]) * ph);
			}
		}
		return length;
	}
}
