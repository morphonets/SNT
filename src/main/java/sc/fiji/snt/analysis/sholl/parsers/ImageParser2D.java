/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.math3.stat.StatUtils;
import org.scijava.Context;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.TypeConverter;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.UPoint;

/**
 * @author Tiago Ferreira
 */
public class ImageParser2D extends ImageParser {

	private ImageProcessor ip;
	private final boolean doSpikeSupression;
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
		this(imp, (Context) IJ.runPlugIn("org.scijava.Context", ""));
	}

	public ImageParser2D(final ImagePlus imp, final Context context) {
		super(imp, context);
		setPosition(imp.getC(), imp.getZ(), imp.getT());
		doSpikeSupression = true;
	}

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
	public void parse() {
		super.parse();
		ip = getProcessor();

		double[] binsamples;
		int[] pixels;
		int[][] points;

		final int size = radii.size();

		// Create array for bin samples. Passed value of binSize must be at
		// least 1
		binsamples = new double[nSpans];

		statusService.showStatus(
				"Sampling " + size + " radii, " + nSpans + " measurement(s) per radius. Press 'Esc' to abort...");

		// Outer loop to control the analysis bins
		int i = 0;
		for (final Double radius : radii) {

			// Retrieve the radius in pixel coordinates and set the largest
			// radius of this bin span
			int intRadius = (int) Math.round(radius / voxelSize + nSpans / 2);
			final Set<UPoint> pointsList = new HashSet<>();

			// Inner loop to gather samples for each sample
			for (int s = 0; s < nSpans; s++) {

				if (intRadius < 1)
					break;

				// Get the circumference pixels for this int radius
				points = getCircumferencePoints(xc, yc, intRadius--);
				pixels = getPixels(points);

				// Count the number of intersections
				final Set<UPoint> thisBinIntersPoints = targetGroupsPositions(pixels, points);
				binsamples[s] = thisBinIntersPoints.size();
				pointsList.addAll(thisBinIntersPoints);
			}
			statusService.showProgress(i++, size * nSpans);

			// Statistically combine bin data
			double counts = 0;
			if (nSpans > 1) {
				if (spanType == MEDIAN) { // 50th percentile
					counts = StatUtils.percentile(binsamples, 50);
				} else if (spanType == MEAN) { // mean
					counts = StatUtils.mean(binsamples);
				} else if (spanType == MODE) { // the 1st max freq. element
					counts = StatUtils.mode(binsamples)[0];
				}
			} else { // There was only one sample
				counts = binsamples[0];
			}
			profile.add(new ProfileEntry(radius, counts, pointsList));

		}

		clearStatus();
	}

	protected Set<UPoint> targetGroupsPositions(final int[] pixels, final int[][] rawpoints) {

		int i, j;
		int[][] points;

		// Count how many target pixels (i.e., foreground, non-zero) we have
		for (i = 0, j = 0; i < pixels.length; i++)
			if (pixels[i] != 0.0)
				j++;

		// Create an array to hold target pixels
		points = new int[j][2];

		// Copy all target pixels into the array
		for (i = 0, j = 0; i < pixels.length; i++)
			if (pixels[i] != 0.0)
				points[j++] = rawpoints[i];

		return groupPositions(points);

	}

	private void removeSinglePixels(final int[][] points, final int pointsLength, final int[] grouping,
			final HashSet<Integer> positions) {

		for (int i = 0; i < pointsLength; i++) {

			// Check for other members of this group
			boolean multigroup = false;
			for (int j = 0; j < pointsLength; j++) {
				if (i == j)
					continue;
				if (grouping[i] == grouping[j]) {
					multigroup = true;
					break;
				}
			}

			// If not a single-pixel group, try again
			if (multigroup)
				continue;

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

			// Now perform the stair checks
			if ((px[0] != 0 && px[1] != 0 && px[3] != 0 && px[4] == 0 && px[6] == 0 && px[7] == 0)
					|| (px[1] != 0 && px[2] != 0 && px[4] != 0 && px[3] == 0 && px[5] == 0 && px[6] == 0)
					|| (px[4] != 0 && px[6] != 0 && px[7] != 0 && px[0] == 0 && px[1] == 0 && px[3] == 0)
					|| (px[3] != 0 && px[5] != 0 && px[6] != 0 && px[1] == 0 && px[2] == 0 && px[4] == 0)) {

				positions.remove(i);
			}

		}

	}

	protected Set<UPoint> groupPositions(final int[][] points) {

		int target, source, len;

		// Create an array to hold the point grouping data
		final int[] grouping = new int[len = points.length];

		// Initialize each point to be in a unique group
		final HashSet<Integer> positions = new HashSet<>();
		for (int i = 0; i < len; i++) {
			grouping[i] = i + 1;
			positions.add(i);
		}

		for (int i = 0; i < len; i++) {
			for (int j = 0; j < len; j++) {

				// Don't compare the same point with itself
				if (i == j)
					continue;

				// Compute the chessboard (Chebyshev) distance. A distance of 1
				// underlies 8-connectivity
				final UPoint p1 = new UPoint(points[i][0], points[i][1]);
				final UPoint p2 = new UPoint(points[j][0], points[j][1]);

				// Should these two points be in the same group?
				if ((p1.chebyshevXYdxTo(p2) <= 1) && (grouping[i] != grouping[j])) {

					// Record which numbers we're changing
					source = grouping[i];
					target = grouping[j];

					// Change all targets to sources
					for (int k = 0; k < len; k++)
						if (grouping[k] == target)
							grouping[k] = source;

					// Remove redundant position
					positions.remove(j);
				}

			}
		}

		if (doSpikeSupression) {
			removeSinglePixels(points, len, grouping, positions);
		}

		final Set<UPoint> sPoints = new HashSet<>();
		for (final Integer pos : positions)
			sPoints.add(new UPoint(points[pos][0], points[pos][1], cal));

		return sPoints;
	}

	protected int[] getPixels(final int[][] points) {

		// Initialize the array to hold the pixel values. int arrays are
		// initialized to a default value of 0
		final int[] pixels = new int[points.length];

		// Put the pixel value for each circumference point in the pixel array
		for (int i = 0; i < pixels.length; i++) {

			// We already filtered out of bounds coordinates in
			// getCircumferencePoints
			if (withinBoundsAndThreshold(points[i][0], points[i][1]))
				pixels[i] = 1;
		}

		return pixels;

	}

	protected boolean withinBoundsAndThreshold(final int x, final int y) {
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
}
