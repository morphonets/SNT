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
package sc.fiji.snt.analysis.sholl.parsers;

import java.util.ArrayList;
import java.util.HashSet;
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
	private int progressCounter;
	private boolean skipSingleVoxels;
	private ImageStack stack;
	private final int nCPUs;
	private final ThreadService threadService;
	private int nSamples;

	@Deprecated
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

		// Split processing across the number of available CPUs
		final Thread[] threads = new Thread[nCPUs];
		setThreadedCounter(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			final int chunkSize = (nSamples + nCPUs - 1) / nCPUs; // divide by
																	// threads
																	// rounded
																	// up.
			final int start = ithread * chunkSize;
			final int end = Math.min(start + chunkSize, nSamples);
			threads[ithread] = threadService.newThread(new ChunkParser(start, end));
		}
		ThreadUtil.startAndJoin(threads);

	}

	public void setPosition(final int channel, final int frame) {
		super.setPosition(channel, frame);
	}

	private class ChunkParser implements Runnable {

		private final int start;
		private final int end;

		public ChunkParser(final int start, final int end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public void run() {
			final AtomicInteger ai = new AtomicInteger(0);
			for (int k = ai.getAndIncrement(); k < nCPUs; k = ai.getAndIncrement()) {
				for (int s = start; s < end; s++) {

					final int counter = getThreadedCounter();
					statusService.showStatus(counter, nSamples, "Sampling shell " +
						counter + "/" + nSamples + " (" + nCPUs + " threads)");
					setThreadedCounter(counter + 1);

					// Initialize ArrayLists to hold surface points
					final ArrayList<ShollPoint> pixelPoints = new ArrayList<>();

					// Restrain analysis to the smallest volume for this
					// sphere
					final double r = radii.get(s);
					final double upperR = r + voxelSize;
					final double lowerR = r - voxelSize;
					final int xr = (int) Math.round(r / vxW);
					final int yr = (int) Math.round(r / vxH);
					final int zr = (int) Math.round(r / vxD);
					final int xmin = Math.max(xc - xr, minX);
					final int ymin = Math.max(yc - yr, minY);
					final int zmin = Math.max(zc - zr, minZ);
					final int xmax = Math.min(xc + xr, maxX);
					final int ymax = Math.min(yc + yr, maxY);
					final int zmax = Math.min(zc + zr, maxZ);

					for (int z = zmin; z <= zmax; z++) {
						for (int y = ymin; y <= ymax; y++) {
							for (int x = xmin; x <= xmax; x++) {
								if (!running)
									return;
								final ShollPoint p = new ShollPoint(x, y, z, cal);
								final double dxSq = p.distanceSquaredTo(center);
								if (dxSq > lowerR * lowerR && dxSq < upperR * upperR) {
									final double vxValue = stack.getVoxel(x, y, z);
									if ( !withinThreshold(vxValue) || (skipSingleVoxels && !hasNeighbors(x, y, z)) )
										continue;
									final ShollPoint point = new ShollPoint(x, y, z, ShollPoint.NONE);
									if (isRetrieveIntDensitiesSet()) point.v = vxValue;
									pixelPoints.add(point);
								}
							}
						}
					}

					// We now have the points intercepting the
					// surface of this shell: Check if they are
					// clustered and add them in world coordinates
					// to profile
					if (isRetrieveIntDensitiesSet()) {
						final double sum = pixelPoints.stream().filter(o -> o.v > 10).mapToDouble(o -> o.v).sum();
						profile.add(new ProfileEntry(r, sum/pixelPoints.size()));
					} else {
						final HashSet<ShollPoint> points = getUnique3Dgroups(pixelPoints);
						ShollPoint.scale(points, cal);
						profile.add(new ProfileEntry(r, points));
					}

				}
			}
		}

	}

	protected HashSet<ShollPoint> getUnique3Dgroups(final ArrayList<ShollPoint> points) {

		for (int i = 0; i < points.size(); i++) {
			for (int j = i + 1; j < points.size(); j++) {

				final ShollPoint pi = points.get(i);
				final ShollPoint pj = points.get(j);
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

		return new HashSet<>(points);

	}

	private boolean hasNeighbors(final int x, final int y, final int z) {

		final int[][] neighbors = new int[6][3];
		neighbors[0] = new int[] { x - 1, y, z };
		neighbors[1] = new int[] { x + 1, y, z };
		neighbors[2] = new int[] { x, y - 1, z };
		neighbors[3] = new int[] { x, y + 1, z };
		neighbors[4] = new int[] { x, y, z + 1 };
		neighbors[5] = new int[] { x, y, z - 1 };

		for (int i = 0; i < neighbors.length; i++) {
			try {
				if (!withinBounds(neighbors[i][0], neighbors[i][1], neighbors[i][2]))
					return false;
				if (withinThreshold(stack.getVoxel(neighbors[i][0], neighbors[i][1], neighbors[i][2])))
					return true;
			} catch (final IndexOutOfBoundsException ignored) { // Edge voxel?
																// Neighborhood
																// unknown.
				return false;
			}
		}
		return false;

	}

	private int getThreadedCounter() {
		return progressCounter;
	}

	private void setThreadedCounter(final int updatedCounter) {
		progressCounter = updatedCounter;
	}

	public void setSkipSingleVoxels(final boolean skip) {
		skipSingleVoxels = skip;
	}

	public boolean isSkipSingleVoxels() {
		return skipSingleVoxels;
	}
}
