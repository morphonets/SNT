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

package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.List;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.Straightener;
import ij.process.ImageProcessor;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;

/**
 * Command to "straighten" an image using Path coordinates.
 *
 * @author Tiago Ferreira
 */
public class PathStraightener {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private final Path path;
	private final ImagePlus imp;
	private int width = 20;

	/**
	 * Instantiates a new PathStraightener
	 *
	 * @param path the Path to be 'straightened'
	 * @param imp  the image from which pixel intensities will be retrieved. Note
	 *             that no effort is made to ensure that the image is suitable
	 */
	public PathStraightener(final Path path, final ImagePlus imp) throws IllegalArgumentException {
		if (imp == null) {
			throw new IllegalArgumentException("Image cannot be null");
		}
		if (path == null) {
			throw new IllegalArgumentException("Path cannot be null");
		}
		if (path.size() < 2) {
			throw new IllegalArgumentException("Multi-node path required");
		}
		this.path = path;
		this.imp = imp;
	}

	/**
	 * Instantiates a new PathStraightener
	 *
	 * @param path the Path to be 'straightened'
	 * @param snt  the SNT instance providing the image to from which pixel
	 *             intensities will be retrieved.
	 */
	public PathStraightener(final Path path, final SNT snt) throws IllegalArgumentException {
		if (snt == null || !snt.accessToValidImageData()) {
			throw new IllegalArgumentException("Invalid SNT instance.");
		}
		if (path == null) {
			throw new IllegalArgumentException("Path cannot be null.");
		}
		if (path.size() < 2) {
			throw new IllegalArgumentException("Path must have at least 2 nodes");
		}
		this.path = path;
		this.imp = snt.getImagePlus();
		// there is only so much we can do to know if the image currently loaded in SNT
		// can be parsed. We'll try some minor cross-checks nevertheless:
		if (path.getChannel() > imp.getNChannels() || path.getFrame() > imp.getNFrames()) {
			throw new IllegalArgumentException("Path position is not compatible with dimensions of active image.");
		}

	}

	/**
	 * @return the straightened path as an ImagePlus object
	 */
	public ImagePlus straighten() {
		final int currentC = imp.getC();
		final int currentT = imp.getT();
		final int currentZ = imp.getZ();
		imp.setPositionWithoutUpdate(path.getChannel(), currentZ, path.getFrame());
		ImagePlus result;
		if (imp.isComposite()) {
			int currentMode = imp.getDisplayMode();
			imp.setDisplayMode(CompositeImage.GRAYSCALE); //HACK: straightener creates unsupported "RGB stacks" otherwise!?
			final List<ImageProcessor> ips = new ArrayList<>(imp.getNChannels());
			for (int ch = 1; ch <= imp.getNChannels(); ch++) {
				final ImageProcessor ip = straighten(ch);
				if (ip != null) ips.add(ip);
			}
			final ImageStack stack = new ImageStack(ips.get(0).getWidth(), ips.get(0).getHeight());
			ips.forEach(ip -> stack.addSlice(ip));
			result = new ImagePlus("", stack);
			result.setDimensions(imp.getNChannels(), 1, 1);
			result = new CompositeImage(result, currentMode);
			((CompositeImage) result).copyLuts(imp);
			imp.setDisplayMode(currentMode);
		} else {
			result = new ImagePlus("", straighten(path.getChannel()));
		}
		imp.setPosition(currentC, currentZ, currentT);
		result.setTitle("Straightened " + path.getName());
		result.setCalibration(imp.getCalibration());
		return result;
	}

	/**
	 * @param channel the channel to bes straightened
	 * 
	 * @return the straightened path for the specified channel as an ImageProcessor
	 *         object
	 */
	public ImageProcessor straighten(final int channel) throws IllegalArgumentException {
		final RoiConverter converter = new RoiConverter(path, imp);
		converter.setView(RoiConverter.XY_PLANE);
		final List<PolygonRoi> polyLines = converter.getROIs(path);
		if (polyLines.size() == 0) {
			throw new IllegalArgumentException("Could not extract valid ROIs from path");
		}
		final List<ImageProcessor> ips = new ArrayList<>();
		final Roi existingRoi = imp.getRoi();
		final boolean redirecting = IJ.redirectingErrorMessages();
		try {
			IJ.redirectErrorMessages(true);
			polyLines.forEach(roi -> {
				if (roi == null) return; //i.e., continue;
				imp.setPositionWithoutUpdate(channel, roi.getZPosition(), roi.getTPosition());
				imp.setRoi(roi);
				// HACK: With one of the polyline of Path 33 of OP_1, roi != null but imp.getRoi()
				// returns null. Probably an out-of-bounds, small polyline, so we'll check for
				// that condition here. Also, not clear spline fitting is all that important, but
				// IJ's Straighten command does it, so we do it also here. Also, it does not seem
				// possible to proceed using regular API call, so we'll call
				// ij.plugin.Selection#run("spline") to avoid having to re-implement the code
				if (imp.getRoi() != null) IJ.run(imp, "Fit Spline", "");
				final ImageProcessor ip = new Straightener().straighten(imp, roi, getWidth());
				if (ip != null && ip.getPixels() != null)
					ips.add(ip);
			});
		} finally {
			IJ.redirectErrorMessages(redirecting);
			imp.setRoi(existingRoi);
		}
		return (ips.isEmpty()) ? null : combineHorizontally(ips);
	}

	private ImageProcessor combineHorizontally(final List<ImageProcessor> ips) {
		if (ips.isEmpty())
			return null;
		final int maxWidth = ips.stream().mapToInt(ip -> ip.getWidth()).sum();
		final ImageProcessor holder = ips.get(0).createProcessor(maxWidth, getWidth());
		holder.setBackgroundValue(0);
		holder.fill();
		int xloc = 0;
		for (final ImageProcessor ip : ips) {
			holder.insert(ip, xloc, 0);
			xloc += ip.getWidth();
		}
		return holder;
	}

	private int getWidth() {
		return width;
	}

	public void setWidth(final int width) {
		this.width = width;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final SNTService sntService = new SNTService();
		final ImagePlus imp = sntService.demoImage("fractal");
		final Path p = sntService.demoTree("fractal").get(0);
		final PathStraightener ps = new PathStraightener(p, imp);
		ps.straighten().show();
	}

}
