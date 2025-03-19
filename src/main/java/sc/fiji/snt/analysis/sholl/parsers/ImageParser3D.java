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
import java.util.concurrent.atomic.AtomicInteger;

import org.scijava.Context;
import org.scijava.thread.ThreadService;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ChannelSplitter;
import ij.util.ThreadUtil;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.util.ShollPoint;

/**
 * Parser for 3D images
 * 
 * @author Tiago Ferreira
 */
public class ImageParser3D extends ImageParser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private double vxW, vxH, vxD;
	private boolean skipSingleVoxels;
	private ImageStack stack;
	private final int nCPUs;
	private final ThreadService threadService;
	private int nSamples;

	public ImageParser3D(final ImagePlus imp) {
		this(imp, SNTUtils.getContext());
	}

	public ImageParser3D(final ImagePlus imp, final Context context) {
		super(imp, context);
		skipSingleVoxels = true;
		setPosition(imp.getC(), imp.getT());
		threadService = context.getService(ThreadService.class);
		nCPUs = SNTPrefs.getThreads();
	}

	@Override
	public void parse() {
		super.parse();
		nSamples = radii.size();
		stack = (imp.isComposite()) ? ChannelSplitter.getChannel(imp, channel) : imp.getStack();
		vxW = cal.pixelWidth;
		vxH = cal.pixelHeight;
		vxD = cal.pixelDepth;
		// ensure all voxels to be parsed are within image bounds
		minX = Math.max(minX, 0);
		minY = Math.max(minY, 0);
		minZ = Math.max(minZ, 0); // voxel query uses 0-based indices
		maxX = Math.min(maxX, stack.getWidth());
		maxY = Math.min(maxY, stack.getHeight());
		maxZ = Math.min(maxZ, stack.getSize());
		// Split processing across the number of available CPUs
		final Thread[] threads = new Thread[nCPUs];
		final AtomicInteger progressCounter = new AtomicInteger(0);
		for (int ithread = 0; ithread < threads.length; ithread++) {
			final int chunkSize = (nSamples + nCPUs - 1) / nCPUs; // divide by threads rounded up
			final int start = ithread * chunkSize;
			final int end = Math.min(start + chunkSize, nSamples);
			threads[ithread] = threadService.newThread(new ChunkParser(start, end, progressCounter));
		}
		ThreadUtil.startAndJoin(threads);
	}

	public void setPosition(final int channel, final int frame) {
		super.setPosition(channel, frame);
	}

	private class ChunkParser implements Runnable {

		private final int start;
		private final int end;
		private final AtomicInteger progressCounter;

		public ChunkParser(final int start, final int end, final AtomicInteger progressCounter) {
			this.start = start;
			this.end = end;
			this.progressCounter = progressCounter;
		}

		@Override
		public void run() {

			for (int s = start; s < end; s++) {
				final int counter = progressCounter.getAndIncrement();
				statusService.showStatus(counter, nSamples, "Sampling shell " + counter + "/" + nSamples + " (" + nCPUs + " threads)");
				// Initialize List to hold surface points
				final ArrayList<ShollPoint> pixelPoints = new ArrayList<>();
				// Restrain analysis to the smallest volume for this sphere
				final double r = radii.get(s);
				final double upperR = r + voxelSize;
				final double lowerR = r - voxelSize;
				final int xr = (int) Math.round(r / vxW);
				final int yr = (int) Math.round(r / vxH);
				final int zr = (int) Math.round(r / vxD);
				int xMin = Math.max(xc - xr, minX);
				int yMin = Math.max(yc - yr, minY);
				int zMin = Math.max(zc - zr, minZ);
				int xMax = Math.min(xc + xr, maxX);
				int yMax = Math.min(yc + yr, maxY);
				int zMax = Math.min(zc + zr, maxZ);
				// Iterate over the volume of the sphere
				for (int z = zMin; z <= zMax; z++) {
					for (int y = yMin; y <= yMax; y++) {
						for (int x = xMin; x <= xMax; x++) {
							if (!running)
								return;
							final ShollPoint p = new ShollPoint(x, y, z, cal);
							final double dxSq = p.distanceSquaredTo(center);
							if (dxSq > lowerR * lowerR && dxSq < upperR * upperR) {
								final double vxValue = stack.getVoxel(x, y, z); // all 0-based indices
								if ( !withinThreshold(vxValue) || (isSkipSingleVoxels() && !hasNeighbors(x, y, z)) )
									continue;
								final ShollPoint point = new ShollPoint(x, y, z, ShollPoint.NONE);
								if (isRetrieveIntDensitiesSet()) point.v = vxValue;
								pixelPoints.add(point);
							}
						}
					}
				}
				// We now have the points intercepting the surface of this shell: Check
				// if they are clustered and add them in world coordinates to profile
				if (isRetrieveIntDensitiesSet()) {
					final double sum = pixelPoints.stream().mapToDouble(o -> o.v).sum();
					profile.add(new ProfileEntry(r, sum / pixelPoints.size()));
				} else {
					cullTotUnique3DGroups(pixelPoints);
					ShollPoint.scale(pixelPoints, cal);
					profile.add(new ProfileEntry(r, new HashSet<>(pixelPoints)));
				}
			}
		}
	}

	void cullTotUnique3DGroups(final List<ShollPoint> points) {
		for (ListIterator<ShollPoint> it1 = points.listIterator(); it1.hasNext(); ) {
			ShollPoint pi = it1.next();
			for (ListIterator<ShollPoint> it2 = points.listIterator(it1.nextIndex()); it2.hasNext(); ) {
				ShollPoint pj = it2.next();
				// Compute the chessboard (Chebyshev) distance for this point. A
				// chessboard distance of 1 in xy (lateral) underlies
				// 8-connectivity within the plane. A distance of 1 in z (axial)
				// underlies 26-connectivity in 3D
				if (pi.chebyshevXYdxTo(pj) * pi.chebyshevZdxTo(pj) < 2) { // int distances: ==1 <=> <2
					pj.setFlag(ShollPoint.DELETE);
				}
			}
		}
		points.removeIf(shollPoint -> shollPoint.flag == ShollPoint.DELETE);
	}

	private boolean hasNeighbors(final int x, final int y, final int z) {
		final int[][] neighbors = {
				{x - 1, y, z},
				{x + 1, y, z},
				{x, y - 1, z},
				{x, y + 1, z},
				{x, y, z + 1},
				{x, y, z - 1}
		};
		for (final int[] neighbor : neighbors) {
			int nx = neighbor[0];
			int ny = neighbor[1];
			int nz = neighbor[2];
			if (withinBounds(nx, ny, nz) && withinThreshold(stack.getVoxel(nx, ny, nz))) {
				return true;
			}
		}
		return false;
	}

	public void setSkipSingleVoxels(final boolean skip) {
		skipSingleVoxels = skip;
	}

	public boolean isSkipSingleVoxels() {
		return skipSingleVoxels;
	}
}
