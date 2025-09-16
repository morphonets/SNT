/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
	}

    @SuppressWarnings("unused")
	public void setCenterPx(final int x, final int y) {
		super.setCenterPx(x, y, 1);
	}

	public void setCenter(final double x, final double y) {
		super.setCenter(x, y, 0);
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

			// Retrieve the radius in pixel coordinates and set the largest radius of this bin span
			int intRadius = (int) Math.round(radius / voxelSize + (double) nSpans / 2);
			final Set<ShollPoint> pointsList = new HashSet<>();

			// Inner loop to gather samples for each sample
			for (int s = 0; s < nSpans; s++) {

				if (intRadius < 1)
					break;

				// Get the circumference pixels for this int radius
				points = getCircumferencePoints(xc, yc, intRadius--);
				pixels = getPixels(points);

                // If enabled, suppress 1‑pixel spikes on the ring mask BEFORE computing length
                if (doSpikeSuppression) {
                    suppressSinglePixelsOnRing(pixels); // modifies mask in place
                }

                // Retrieve lengths (now reflects suppression)
                lenSamples[s] = lengthOnRingFromMaskedPixels(points, pixels, cal);

                if (isRetrieveIntDensitiesSet()) {
                    // Intensity mode: no need to compute connected components
                    double sum = 0;
                    for (final float v : getPixelIntensities(points)) sum += v;
                    binSamples[s] = sum / points.length;
                } else {
                    // Intersections/length mode: compute connected components and record representative positions
                    final Set<ShollPoint> thisBinIntersPoints = groupPositionsOnRing(points, pixels);
                    binSamples[s] = thisBinIntersPoints.size();
                    pointsList.addAll(thisBinIntersPoints);
                }
			}
			statusService.showProgress(i++, size * nSpans);

			// Statistically combine bin data
			double counts = 0;
            double length = 0;
			if (nSpans > 1) {
				if (spanType == MEDIAN) { // 50th percentile
					counts = StatUtils.percentile(binSamples, 50);
                    length = StatUtils.percentile(lenSamples, 50);
				} else if (spanType == MEAN) { // mean
					counts = StatUtils.mean(binSamples);
                    length = StatUtils.mean(lenSamples);
                } else if (spanType == MODE) { // the 1st max freq. element
					counts = StatUtils.mode(binSamples)[0];
                    length = StatUtils.mode(lenSamples)[0];// mode is nonsensical for continuos values of lengths!
                }
			} else { // There was only one sample
				counts = binSamples[0];
                length = lenSamples[0];
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

        if (doSpikeSuppression) {
            // NB: suppressSinglePixelsOnRing will alter mask
            suppressSinglePixelsOnRing(mask);
        }

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
            runs.add(new int[]{ start, end, count });
        }

        // Merge wrap-around if both ends are foreground: last run + first run
        if (!runs.isEmpty() && mask[0] != 0 && mask[n - 1] != 0 && runs.size() > 1) {
            final int[] first = runs.getFirst();
            final int[] last  = runs.getLast();
            // merged run spans last.start ... first.end (cyclic)
            final int mergedCount = last[2] + first[2];
            // replace first with merged, remove last
            runs.set(0, new int[]{ last[0], first[1], mergedCount });
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
