/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

public class FillerThread extends SearchThread {

	/*
	 * You should synchronize on this object if you want to rely on the pause status
	 * not changing. (The run() method is not synchronized itself, for possibly
	 * dubious performance reasons.)
	 */

	boolean reciprocal;

	double reciprocal_fudge = 0.5;

	public float getDistanceAtPoint(final double xd, final double yd,
		final double zd)
	{

		final int x = (int) Math.round(xd);
		final int y = (int) Math.round(yd);
		final int z = (int) Math.round(zd);

		final SearchNode[] slice = nodes_as_image_from_start[z];
		if (slice == null) return -1.0f;

		final SearchNode n = slice[y * width + x];
		if (n == null) return -1.0f;
		else return n.g;
	}

	// FIXME: may be buggy, synchronization issues

	Fill getFill() {

		final Hashtable<SearchNode, Integer> h = new Hashtable<>();

		final ArrayList<SearchNode> a = new ArrayList<>();

		// The tricky bit here is that we want to create a
		// Fill object with index

		int openAtOrAbove;

		int i = 0;

		for (final SearchNode current : closed_from_start) {
			/* if( current.g <= threshold ) { */
			h.put(current, i);
			a.add(current);
			++i;
			/* } */
		}

		openAtOrAbove = i;

		SNTUtils.log("openAtOrAbove is: " + openAtOrAbove);

		for (final SearchNode current : open_from_start) {
			/* if( current.g <= threshold ) { */
			h.put(current, i);
			a.add(current);
			++i;
			/* } */
		}

		final Fill fill = new Fill();

		fill.setThreshold(threshold);
		if (reciprocal) fill.setMetric("reciprocal-intensity-scaled");
		else fill.setMetric("256-minus-intensity-scaled");

		fill.setSpacing(x_spacing, y_spacing, z_spacing, spacing_units);

		SNTUtils.log("... out of a.size() " + a.size() + " entries");

		for (i = 0; i < a.size(); ++i) {
			final SearchNode f = a.get(i);
			int previousIndex = -1;
			final SearchNode previous = f.getPredecessor();
			if (previous != null) {
				final Integer p = h.get(previous);
				if (p != null) {
					previousIndex = p;
				}
			}
			fill.add(f.x, f.y, f.z, f.g, previousIndex, i >= openAtOrAbove);
		}

		if (sourcePaths != null) {
			fill.setSourcePaths(sourcePaths);
		}

		return fill;
	}

	Set<Path> sourcePaths;

	public static FillerThread fromFill(final ImagePlus imagePlus,
		final float stackMin, final float stackMax, final boolean startPaused,
		final Fill fill)
	{

		boolean reciprocal;
		final String metric = fill.getMetric();

		if (metric.equals("reciprocal-intensity-scaled")) {
			reciprocal = true;
		}
		else if (metric.equals("256-minus-intensity-scaled")) {
			reciprocal = false;
		}
		else {
			SNTUtils.error("Trying to load a fill with an unknown metric ('" + metric +
				"')");
			return null;
		}

		SNTUtils.log("loading a fill with threshold: " + fill.getThreshold());

		final FillerThread result = new FillerThread(imagePlus, stackMin, stackMax,
			startPaused, reciprocal, fill.getThreshold(), 5000);

		final ArrayList<SearchNode> tempNodes = new ArrayList<>();

		for (final Fill.Node n : fill.nodeList) {

			final SearchNode s = new SearchNode(n.x, n.y, n.z, (float) n.distance, 0,
				null, SearchThread.FREE);
			tempNodes.add(s);
		}

		for (int i = 0; i < tempNodes.size(); ++i) {
			final Fill.Node n = fill.nodeList.get(i);
			final SearchNode s = tempNodes.get(i);
			if (n.previous >= 0) {
				s.setPredecessor(tempNodes.get(n.previous));
			}
			if (n.open) {
				s.searchStatus = OPEN_FROM_START;
				result.addNode(s, true);
			}
			else {
				s.searchStatus = CLOSED_FROM_START;
				result.addNode(s, true);
			}
		}
		result.setSourcePaths(fill.sourcePaths);
		return result;
	}

	float threshold;

	public void setThreshold(final double threshold) {
		this.threshold = (float) threshold;
	}

	public float getThreshold() {
		return threshold;
	}

	/* If you specify 0 for timeoutSeconds then there is no timeout. */

	public FillerThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final boolean startPaused, final boolean reciprocal,
		final double initialThreshold, final long reportEveryMilliseconds)
	{

		super(imagePlus, stackMin, stackMax, false, // bidirectional
			false, // definedGoal
			startPaused, 0, reportEveryMilliseconds);

		this.reciprocal = reciprocal;
		setThreshold(initialThreshold);

		setPriority(MIN_PRIORITY);
	}

	public void setSourcePaths(final Collection<Path> newSourcePaths) {
		sourcePaths = new HashSet<>();
		sourcePaths.addAll(newSourcePaths);
		for (final Path p : newSourcePaths) {
			if (p == null) return;
			for (int k = 0; k < p.size(); ++k) {
				final SearchNode f = new SearchNode(p.getXUnscaled(k), p.getYUnscaled(
					k), p.getZUnscaled(k), 0, 0, null, OPEN_FROM_START);
				addNode(f, true);
			}
		}
	}

	public ImagePlus fillAsImagePlus(final boolean realData) {

		final byte[][] new_slice_data_b = new byte[depth][];
		final short[][] new_slice_data_s = new short[depth][];
		final float[][] new_slice_data_f = new float[depth][];

		for (int z = 0; z < depth; ++z) {
			switch (imageType) {
				case ImagePlus.GRAY8:
				case ImagePlus.COLOR_256:
					new_slice_data_b[z] = new byte[width * height];
					break;
				case ImagePlus.GRAY16:
					new_slice_data_s[z] = new short[width * height];
					break;
				case ImagePlus.GRAY32:
					new_slice_data_f[z] = new float[width * height];
					break;
				default:
					throw new IllegalArgumentException("Unsupported image type");
			}
		}

		final ImageStack stack = new ImageStack(width, height);

		for (int z = 0; z < depth; ++z) {
			final SearchNode[] nodes_this_slice = nodes_as_image_from_start[z];
			if (nodes_this_slice != null) for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final SearchNode s = nodes_as_image_from_start[z][y * width + x];
					if ((s != null) && (s.g <= threshold)) {
						switch (imageType) {
							case ImagePlus.GRAY8:
							case ImagePlus.COLOR_256:
								new_slice_data_b[z][y * width + x] = realData
									? slices_data_b[z][y * width + x] : (byte) 255;
								break;
							case ImagePlus.GRAY16:
								new_slice_data_s[z][y * width + x] = realData
									? slices_data_s[z][y * width + x] : 255;
								break;
							case ImagePlus.GRAY32:
								new_slice_data_f[z][y * width + x] = realData
									? slices_data_f[z][y * width + x] : 255;
								break;
							default:
								break;
						}
					}
				}
			}

			switch (imageType) {
				case ImagePlus.GRAY8:
				case ImagePlus.COLOR_256:
					final ByteProcessor bp = new ByteProcessor(width, height);
					bp.setPixels(new_slice_data_b[z]);
					stack.addSlice(null, bp);
					break;
				case ImagePlus.GRAY16:
					final ShortProcessor sp = new ShortProcessor(width, height);
					sp.setPixels(new_slice_data_s[z]);
					stack.addSlice(null, sp);
					break;
				case ImagePlus.GRAY32:
					final FloatProcessor fp = new FloatProcessor(width, height);
					fp.setPixels(new_slice_data_f[z]);
					stack.addSlice(null, fp);
					break;
				default:
					break;
			}

		}

		final ImagePlus imp = new ImagePlus("filled neuron", stack);

		imp.setCalibration(imagePlus.getCalibration());

		return imp;
	}

	@Override
	protected void reportPointsInSearch() {

		super.reportPointsInSearch();

		// Find the minimum distance in the open list.
		final SearchNode p = open_from_start.peek();
		if (p == null) return;

		final float minimumDistanceInOpen = p.g;

		for (final SearchProgressCallback progress : progressListeners) {
			if (progress instanceof FillerProgressCallback) {
				final FillerProgressCallback fillerProgress =
					(FillerProgressCallback) progress;
				fillerProgress.maximumDistanceCompletelyExplored(this,
					minimumDistanceInOpen);
			}
		}

	}

	@Override
	public void drawProgressOnSlice(final int plane,
		final int currentSliceInPlane, final TracerCanvas canvas, final Graphics g)
	{

		super.drawProgressOnSlice(plane, currentSliceInPlane, canvas, g);

	}

	@Override
	public Path getResult() {
		throw new RuntimeException(
			"BUG: getResult should never be called on a FillerThread");
	}
}
