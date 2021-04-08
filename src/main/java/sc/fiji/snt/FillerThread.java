/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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
import java.util.*;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import sc.fiji.snt.util.SparseMatrix;

public class FillerThread extends SearchThread {

	boolean reciprocal;
	double reciprocal_fudge = 0.5;
	double threshold;

	public double getDistanceAtPoint(final double xd, final double yd,
		final double zd)
	{

		final int x = (int) Math.round(xd);
		final int y = (int) Math.round(yd);
		final int z = (int) Math.round(zd);

		SparseMatrix<DefaultSearchNode> slice = nodes_as_image_from_start.getSlice(z);
		if (slice == null) {
			return -1.0;
		}
		final DefaultSearchNode n = slice.getValue(x, y);
		if (n == null) {
			return -1.0;
		}
		else return n.g;
	}

	// FIXME: may be buggy, synchronization issues

	Fill getFill() {

		final Hashtable<DefaultSearchNode, Integer> h = new Hashtable<>();

		final ArrayList<DefaultSearchNode> a = new ArrayList<>();

		// The tricky bit here is that we want to create a
		// Fill object with index

		int i = 0;
		for (final SparseMatrix<DefaultSearchNode> slice : nodes_as_image_from_start) {
			for (final Int2ObjectOpenHashMap<DefaultSearchNode> row : slice) {
				for (final DefaultSearchNode current : row.values()) {
					if (current.g <= threshold) {
						h.put(current, i);
						a.add(current);
						++i;
					}
				}
			}
		}

		final Fill fill = new Fill();

		fill.setThreshold(threshold);
		if (reciprocal) fill.setMetric("reciprocal-intensity-scaled");
		else fill.setMetric("256-minus-intensity-scaled");

		fill.setSpacing(x_spacing, y_spacing, z_spacing, spacing_units);

		SNTUtils.log("... out of a.size() " + a.size() + " entries");

		for (i = 0; i < a.size(); ++i) {
			final DefaultSearchNode f = a.get(i);
			int previousIndex = -1;
			final DefaultSearchNode previous = f.getPredecessor();
			if (previous != null) {
				final Integer p = h.get(previous);
				if (p != null) {
					previousIndex = p;
				}
			}
			boolean open;
			if (f.searchStatus == SearchThread.OPEN_FROM_START || f.searchStatus == SearchThread.OPEN_FROM_GOAL) {
				open = true;
			} else if (f.searchStatus == SearchThread.CLOSED_FROM_START || f.searchStatus == SearchThread.CLOSED_FROM_GOAL) {
				open = false;
			} else {
				throw new IllegalStateException("Somehow a FREE node is in the Fill.");
			}
			fill.add(f.x, f.y, f.z, f.g, previousIndex, open);
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

		final FillerThread result = new FillerThread(imagePlus, stackMin, stackMax, reciprocal, fill.getThreshold(),
				5000);

		final ArrayList<DefaultSearchNode> tempNodes = new ArrayList<>();

		for (final Fill.Node n : fill.nodeList) {

			final DefaultSearchNode s = new DefaultSearchNode(n.x, n.y, n.z, (float) n.distance, 0,
				null, SearchThread.FREE);
			tempNodes.add(s);
		}

		for (int i = 0; i < tempNodes.size(); ++i) {
			final Fill.Node n = fill.nodeList.get(i);
			final DefaultSearchNode s = tempNodes.get(i);
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

	public void setThreshold(final double threshold) {
		this.threshold = threshold;
	}

	public double getThreshold() {
		return threshold;
	}

	/* If you specify 0 for timeoutSeconds then there is no timeout. */

	public FillerThread(final ImagePlus imagePlus, final float stackMin,
		final float stackMax, final boolean reciprocal,
		final double initialThreshold, final long reportEveryMilliseconds)
	{

		super(imagePlus, stackMin, stackMax, false, false, 0,
				reportEveryMilliseconds);

		this.reciprocal = reciprocal;
		setThreshold(initialThreshold);

		//setPriority(MIN_PRIORITY);
	}

	public void setSourcePaths(final Collection<Path> newSourcePaths) {
		sourcePaths = new HashSet<>();
		sourcePaths.addAll(newSourcePaths);
		for (final Path p : newSourcePaths) {
			if (p == null) return;
			for (int k = 0; k < p.size(); ++k) {
				final DefaultSearchNode f = new DefaultSearchNode(p.getXUnscaled(k), p.getYUnscaled(
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
			final boolean nodes_this_slice = nodes_as_image_from_start.getSlice(z) != null;
			if (nodes_this_slice) for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final DefaultSearchNode s = nodes_as_image_from_start.getSlice(z).getValue(x, y);
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
		final DefaultSearchNode p = open_from_start.findMin().getKey();
		if (p == null) return;

		final double minimumDistanceInOpen = p.g;

		for (final SearchProgressCallback progress : progressListeners) {
			if (progress instanceof FillerProgressCallback) {
				final FillerProgressCallback fillerProgress =
					(FillerProgressCallback) progress;
				fillerProgress.maximumDistanceCompletelyExplored(this,
						(float)minimumDistanceInOpen);
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
		throw new IllegalStateException("BUG: attempted to retrieve a Path from Filler");
	}

	@Override
	public void reportFinished(boolean success) {
		super.reportFinished(success);
	}
}
