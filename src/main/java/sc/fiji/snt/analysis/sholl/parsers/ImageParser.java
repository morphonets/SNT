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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Properties;

import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;

import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.ZProjector;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.ShollPoint;

/**
 * A {@link Parser} for extracting Sholl profiles from image data.
 * <p>
 * ImageParser analyzes 2D or 3D images to compute intersection counts at various
 * radial distances from a center point. It supports thresholded analysis, 
 * multi-channel images, and various sampling strategies including hemishell analysis.
 * </p>
 * <p>
 * The parser can work with binary images, grayscale images with threshold ranges,
 * or intensity-based analysis. It provides methods for setting analysis parameters
 * such as center point, radii, thresholds, and sampling regions.
 * </p>
 * Key features include:
 * <ul>
 * <li>Support for 2D and 3D image analysis</li>
 * <li>Flexible center point definition (manual coordinates or ROI-based)</li>
 * <li>Customizable radial sampling with automatic or manual radius specification</li>
 * <li>Threshold-based analysis for binary or grayscale images</li>
 * <li>Hemishell analysis for directional sampling</li>
 * <li>Integration with ImageJ's calibration system</li>
 * </ul>
 *
 * @author Tiago Ferreira
 * @see Parser
 * @see Profile
 */
public class ImageParser extends ContextCommand implements Parser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	protected StatusService statusService;

	protected Profile profile;
	protected Properties properties;
	protected ShollPoint center;
	protected ArrayList<Double> radii;
	protected final ImagePlus imp;
	protected final Calibration cal;
	protected final double voxelSize;
	protected double lowerT = ImageProcessor.NO_THRESHOLD;
	protected double upperT = ImageProcessor.NO_THRESHOLD;
	protected int channel;
	protected int frame;
	protected int minX, maxX;
	protected int minY, maxY;
	protected int minZ, maxZ;
	protected int xc;
	protected int yc;
	protected int zc;
	protected long start;
	private boolean retrieveIntDensities;

	protected volatile boolean running = true;

	@Deprecated
	protected ImageParser(final ImagePlus imp) {
		this(imp, SNTUtils.getContext());
	}

	protected ImageParser(final ImagePlus imp, final Context context) {
		setContext(context);
		this.imp = imp;
		if (imp.getProcessor().isBinary())
			setThreshold(1, 255);
		cal = imp.getCalibration(); // never null
		if (imp.getNSlices() > 2) {
			voxelSize = (cal.pixelWidth + cal.pixelHeight + cal.pixelDepth) / 3;
		} else {
			voxelSize = (cal.pixelWidth + cal.pixelHeight) / 2;
		}
		initProfile();
	}

	private void initProfile() {
		profile = new Profile();
		profile.assignImage(imp);
		properties = profile.getProperties();
	}

	/**
	 * Gets the isotropic voxel size used for analysis.
	 * <p>
	 * For 2D images, this is the average of pixel width and height.
	 * For 3D images, this is the average of pixel width, height, and depth.
	 * This value is used as the minimum step size for radial sampling.
	 * </p>
	 *
	 * @return the isotropic voxel size in calibrated units
	 */
	public double getIsotropicVoxelSize() {
		return voxelSize;
	}

	/**
	 * Computes the center of analysis from the centroid of the active ROI.
	 *
	 * @throws IllegalArgumentException if image has no current selection or active
	 *                                  ROI
	 */
	public void setCenterFromROI() throws IllegalArgumentException {
		final Roi roi = imp.getRoi();
		if (roi == null)
			throw new IllegalArgumentException("Cannot retrieve center: Image does not have an active ROI!");
		setCenterFromROI(roi);
	}

	/**
	 * Computes the center of analysis from the centroid of the specified ROI.
	 *
	 * @param roi the selection from which the centroid will be computed
	 * @throws IllegalArgumentException if roi is null
	 */
	public void setCenterFromROI(final Roi roi) throws IllegalArgumentException {
		if (roi == null) {
			throw new IllegalArgumentException("Cannot retrieve center: ROI is null!");
		}
		if (roi.getType() == Roi.LINE) {
			final Line line = (Line) roi;
			setCenterPx(line.x1, line.y1, imp.getZ());
		} else if (roi.getType() == Roi.POINT) {
			final Rectangle rect = roi.getBounds();
			setCenterPx(rect.x, rect.y, imp.getZ());
		} else {
			final double[] ctd = roi.getContourCentroid();
			setCenterPx((int) Math.round(ctd[0]), (int) Math.round(ctd[1]), imp.getZ());
		}
	}

	/**
	 * Sets the center of analysis using image (pixel) coordinates.
	 *
	 * @param x the x coordinate (in pixels)
	 * @param y the y coordinate (in pixels)
	 * @param z the z coordinate (in pixels), i.e., slice number (1-based index)
	 * @throws IndexOutOfBoundsException if specified coordinates extends beyond
	 *                                   image dimensions
	 */
	public void setCenterPx(final int x, final int y, final int z) throws IndexOutOfBoundsException {
		if (x > imp.getWidth() - 1 || y > imp.getHeight() || z > imp.getNSlices())
			throw new IndexOutOfBoundsException("specified coordinates cannot be applied to image");
		center = new ShollPoint(x, y, z, cal);
		profile.setCenter(center);
		xc = x;
		yc = y;
		zc = z;
	}

	/**
	 * Sets the center of analysis using real world (calibrated) coordinates.
	 *
	 * @param x the x coordinate (in calibrated units)
	 * @param y the y coordinate (in calibrated units)
	 * @param z the z coordinate (in calibrated units)
	 */
	public void setCenter(final double x, final double y, final double z) {
		center = new ShollPoint(x, y, z);
		profile.setCenter(center);
		xc = (int) center.rawX(cal);
		yc = (int) center.rawY(cal);
		zc = (int) center.rawZ(cal);
	}

	/**
	 * Sets the threshold range for pixel inclusion in the analysis.
	 * <p>
	 * Only pixels with intensity values within the specified range will be
	 * considered as intersections during Sholl analysis. This is particularly
	 * useful for analyzing specific structures in grayscale images.
	 * </p>
	 *
	 * @param lower the lower threshold value (inclusive)
	 * @param upper the upper threshold value (inclusive)
	 */
	public void setThreshold(final double lower, final double upper) {
		lowerT = lower;
		upperT = upper;
	}

	public void setRadii(final double[] radiiArray) {
		if (radiiArray == null) {
			throw new IllegalArgumentException("radii array cannot be null");
		}
		if (radii == null)
			radii = new ArrayList<>();
		radii.clear();
		for (final double r : radiiArray) {
			radii.add(r);
		}
	}

	public void setRadii(final double startRadius, final double step, final double endRadius) {
		final double fStartRadius = (Double.isNaN(startRadius)) ? voxelSize : Math.max(voxelSize, startRadius);
		final double maxRadius = maxPossibleRadius();
		final double fEndRadius = (Double.isNaN(endRadius)) ? maxRadius : Math.min(endRadius, maxRadius);
		final double fStep = (Double.isNaN(step)) ? voxelSize : Math.max(step, voxelSize);
		radii = ShollUtils.getRadii(fStartRadius, fStep, fEndRadius);
	}

	public double maxPossibleRadius() {
		final double maxX = imp.getWidth() - 1 * cal.pixelWidth;
		final double maxY = imp.getHeight() - 1 * cal.pixelHeight;
		final double maxZ = imp.getNSlices() - 1 * cal.pixelDepth;
		final ShollPoint[] points = new ShollPoint[8];
		points[0] = new ShollPoint(0, 0, 0);
		points[1] = new ShollPoint(maxX, maxY, maxZ);
		if (center == null)
			return points[0].euclideanDxTo(points[1]);
		points[2] = new ShollPoint(maxX, 0, 0);
		points[3] = new ShollPoint(0, maxY, 0);
		points[4] = new ShollPoint(maxX, maxY, 0);
		points[5] = new ShollPoint(0, 0, maxZ);
		points[6] = new ShollPoint(maxX, 0, maxZ);
		points[7] = new ShollPoint(0, maxY, maxZ);
		double max = 0;
		for (final ShollPoint p : points)
			max = Math.max(max, center.distanceSquaredTo(p));
		return Math.sqrt(max);
	}

	public void setRetrieveIntDensities(final boolean retrieveIntDensities) {
		this.retrieveIntDensities = retrieveIntDensities;
	}

	public boolean isRetrieveIntDensitiesSet() {
		return retrieveIntDensities;
	}

	protected void checkUnsetFields(final boolean includeThreshold) {
		if (center == null || radii == null)
			throw new IllegalArgumentException("Cannot proceed with undefined parameters");
		if (includeThreshold && (upperT == ImageProcessor.NO_THRESHOLD || lowerT == ImageProcessor.NO_THRESHOLD)) {
			if (imp.isThreshold()) { // check if image is thresholded
				lowerT = imp.getProcessor().getMinThreshold();
				upperT = imp.getProcessor().getMaxThreshold();
			}
			if (upperT == ImageProcessor.NO_THRESHOLD || lowerT == ImageProcessor.NO_THRESHOLD)
				throw new IllegalArgumentException("Cannot proceed with undefined threshold levels");
		}
	}

	protected void checkUnsetFields() {
		checkUnsetFields(!retrieveIntDensities);
	}

	public void setHemiShells(final String flag) {
		checkUnsetFields(false);
		final int maxRadius = (int) Math.round(radii.get(radii.size() - 1) / voxelSize);
		minX = Math.max(xc - maxRadius, 0);
		maxX = Math.min(xc + maxRadius, imp.getWidth() - 1);
		minY = Math.max(yc - maxRadius, 0);
		maxY = Math.min(yc + maxRadius, imp.getHeight() - 1);
		minZ = Math.max(zc - maxRadius, 0);
		maxZ = Math.min(zc + maxRadius, imp.getNSlices() - 1);

		final String fFlag = ShollUtils.extractHemiShellFlag(flag);
		switch (fFlag) {
		case HEMI_NORTH:
			maxY = Math.min(yc + maxRadius, yc);
			break;
		case HEMI_SOUTH:
			minY = Math.max(yc - maxRadius, yc);
			break;
		case HEMI_WEST:
			minX = xc;
			break;
		case HEMI_EAST:
			maxX = xc;
			break;
		case HEMI_NONE:
			break;
		default:
			throw new IllegalArgumentException("Unrecognized flag: " + flag);
		}
		properties.setProperty(KEY_HEMISHELLS, fFlag);

	}

	public int[][] getCircumferencePoints(final int cx, final int cy, final int radius) {

		// Initialize algorithm variables
		int i = 0, x = 0, y = radius;
		final int r = radius + 1;
		int err = 0, errR, errD;

		// Array to store first 1/8 of points relative to center
		final int[][] data = new int[r][2];

		do {
			// Add this point as part of the circumference
			data[i][0] = x;
			data[i++][1] = y;

			// Calculate the errors for going right and down
			errR = err + 2 * x + 1;
			errD = err - 2 * y + 1;

			// Choose which direction to go
			if (Math.abs(errD) < Math.abs(errR)) {
				y--;
				err = errD; // Go down
			} else {
				x++;
				err = errR; // Go right
			}
		} while (x <= y);

		// Create an array to hold the absolute coordinates
		final int[][] points = new int[r * 8][2];

		// Loop through the relative circumference points
		for (i = 0; i < r; i++) {

			// Pull out the point for quick access;
			x = data[i][0];
			y = data[i][1];

			// Convert the relative point to an absolute point
			points[i][0] = x + cx;
			points[i][1] = y + cy;

			// Use geometry to calculate remaining 7/8 of the circumference
			// points
			points[r * 4 - i - 1][0] = x + cx;
			points[r * 4 - i - 1][1] = -y + cy;
			points[r * 8 - i - 1][0] = -x + cx;
			points[r * 8 - i - 1][1] = y + cy;
			points[r * 4 + i][0] = -x + cx;
			points[r * 4 + i][1] = -y + cy;
			points[r * 2 - i - 1][0] = y + cx;
			points[r * 2 - i - 1][1] = x + cy;
			points[r * 2 + i][0] = y + cx;
			points[r * 2 + i][1] = -x + cy;
			points[r * 6 + i][0] = -y + cx;
			points[r * 6 + i][1] = x + cy;
			points[r * 6 - i - 1][0] = -y + cx;
			points[r * 6 - i - 1][1] = -x + cy;

		}

		// Count how many points are out of bounds, while eliminating
		// duplicates. Duplicates are always at multiples of r (8 points)
		int pxX, pxY, count = 0, j = 0;
		for (i = 0; i < points.length; i++) {

			// Pull the coordinates out of the array
			pxX = points[i][0];
			pxY = points[i][1];

			if ((i + 1) % r != 0 && withinXYbounds(pxX, pxY))
				count++;
		}

		// Create the final array containing only unique points within bounds
		final int[][] refined = new int[count][2];

		for (i = 0; i < points.length; i++) {

			pxX = points[i][0];
			pxY = points[i][1];

			if ((i + 1) % r != 0 && withinXYbounds(pxX, pxY)) {
				refined[j][0] = pxX;
				refined[j++][1] = pxY;
			}

		}

		// Return the array
		return refined;

	}

	public ImagePlus getMask() {
		final ImagePlus img = new ImagePlus(imp.getTitle() + "_ShollMask",
				getMaskProcessor(false, profile.countsAsArray()));
		img.setCalibration(cal);
		try {
			ColorMaps.applyPlasma(img, 100, false);
		} catch (final Exception ignored) {
			// do nothing ...
		}
		return img;
	}

	/**
	 * Creates a 2D 'Sholl heatmap' by applying measured values to the foreground
	 * pixels of a copy of the analyzed image.
	 *
	 * @param floatProcessor if true, a FloatProcessor is returned, if false, a
	 *                       ShortProcessor is returned
	 * @param maskValues     the mask values
	 * @return the processor containing the heatmap
	 */
	public ImageProcessor getMaskProcessor(final boolean floatProcessor, final double[] maskValues) {

		checkUnsetFields();

		// Work on a stack projection when dealing with a volume
		final ImageProcessor ip = imp.getNSlices() > 1 ? projImp() : imp.getProcessor();

		// NB: 16-bit image: Negative values will be set to 0
		final ImageProcessor mp = floatProcessor ? new FloatProcessor(ip.getWidth(), ip.getHeight())
				: new ShortProcessor(ip.getWidth(), ip.getHeight());

		final int drawSteps = maskValues.length;
		final int sRadius = (int) Math.round(profile.startRadius() / voxelSize);
		final int drawWidth = (int) Math.round((profile.endRadius() - profile.startRadius()) / drawSteps);

		for (int i = 0; i < drawSteps; i++) {
			int drawRadius = sRadius + i * drawWidth;
			for (int j = 0; j < drawWidth; j++) {

				// this will already exclude pixels out of bounds
				final int[][] points = getCircumferencePoints(xc, yc, drawRadius++);
				for (final int[] point : points) {
					final double value = ip.getPixel(point[0], point[1]);
					if (withinThreshold(value)) {
						mp.putPixelValue(point[0], point[1], maskValues[i]);
					}
				}

			}
		}

		mp.resetMinAndMax();
		return mp;

	}

	private ImageProcessor projImp() {
		ImageProcessor ip;
		final ZProjector zp = new ZProjector(imp);
		zp.setMethod(ZProjector.MAX_METHOD);
		zp.setStartSlice(minZ);
		zp.setStopSlice(maxZ);
		if (imp.isComposite()) {
			zp.doHyperStackProjection(false);
			final ImagePlus projImp = zp.getProjection();
			projImp.setC(channel);
			ip = projImp.getChannelProcessor();
		} else {
			zp.doProjection();
			ip = zp.getProjection().getProcessor();
		}
		return ip;
	}

	protected void setPosition(final int channel, final int frame) {
		if (channel < 1 || channel > imp.getNChannels() || frame < 1 || frame > imp.getNFrames())
			throw new IllegalArgumentException("Specified (channel, slice, frame) position is out of range");
		this.channel = channel; // 1-based
		this.frame = frame; // 1-based
		properties.setProperty(KEY_CHANNEL_POS, String.valueOf(channel));
		properties.setProperty(KEY_FRAME_POS, String.valueOf(frame));
	}

	protected boolean withinThreshold(final double value) {
		return (value >= lowerT && value <= upperT);
	}

	protected boolean withinXYbounds(final int x, final int y) {
		return (x >= minX && x <= maxX && y >= minY && y <= maxY);
	}

	protected boolean withinZbounds(final int z) {
		return (z >= minZ && z <= maxZ);
	}

	protected boolean withinBounds(final int x, final int y, final int z) {
		return withinXYbounds(x, y) && withinZbounds(z);
	}

	protected void clearStatus() {
		statusService.showStatus(0, 0, "Finished. " + SNTUtils.getElapsedTime(start));
	}

	@Override
	public void parse() {
		checkUnsetFields();
		if (UNSET.equals(properties.getProperty(KEY_HEMISHELLS, UNSET)))
			setHemiShells(HEMI_NONE);
		start = System.currentTimeMillis();
		// remainder implemented by parsers extending this class
	}

	@Override
	public Profile getProfile() {
		if (profile != null)
			profile.setIsIntDensityProfile(isRetrieveIntDensitiesSet());
		return profile;
	}

	@Override
	public boolean successful() {
		return !profile.isEmpty();
	}

	@Override
	public void terminate() {
		running = false;
	}

	public void reset() {
		initProfile();
	}

	@Override
	public void run() {
		// implemented by extending classes
	}

}
