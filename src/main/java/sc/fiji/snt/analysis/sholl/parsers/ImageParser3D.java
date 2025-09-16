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
import sc.fiji.snt.analysis.sholl.Profile;
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

    // Precomputed 3D half-neighborhood (13 offsets) and their calibrated lengths
    private int[][] halfNbr;
    private double[] halfLen;

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
    public Profile getProfile() {
        if (profile == null || profile.isEmpty()) parse();
        return super.getProfile();
    }

    @Override
    public void parse() {
        super.parse();
        nSamples = radii.size();
		stack = (imp.isComposite()) ? ChannelSplitter.getChannel(imp, channel) : imp.getStack();
		vxW = cal.pixelWidth;
		vxH = cal.pixelHeight;
		vxD = cal.pixelDepth;

        // ensure all voxels to be parsed are within image bounds (inclusive max)
        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        minZ = Math.max(minZ, 0); // voxel query uses 0-based indices
        maxX = Math.min(maxX, stack.getWidth()  - 1);
        maxY = Math.min(maxY, stack.getHeight() - 1);
        maxZ = Math.min(maxZ, stack.getSize()   - 1);

        // Precompute calibrated half-neighborhood (13 undirected edges)
        final ArrayList<int[]> offs = new ArrayList<>(13);
        final ArrayList<Double> lens = new ArrayList<>(13);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    // lexicographic half-space: include exactly one direction per edge
                    if (!(dz > 0 || (dz == 0 && (dy > 0 || (dy == 0 && dx > 0))))) continue;
                    offs.add(new int[]{dx, dy, dz});
                    final double ddx = dx * vxW, ddy = dy * vxH, ddz = dz * vxD;
                    lens.add(Math.sqrt(ddx*ddx + ddy*ddy + ddz*ddz));
                }
            }
        }
        halfNbr = offs.toArray(new int[offs.size()][]);
        halfLen = new double[lens.size()];
        for (int i = 0; i < halfLen.length; i++) halfLen[i] = lens.get(i);

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

                // Choose containers based on mode (intensity vs intersections/length).
                // For intensity profiles: pixelPoints is not needed; voxelSet is still required to compute length.
                final boolean intensityMode = isRetrieveIntDensitiesSet();
                final ArrayList<ShollPoint> pixelPoints = intensityMode ? null : new ArrayList<>();
                final HashSet<Long> voxelSet = new HashSet<>();

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
                double intensitySum = 0d;
                for (int z = zMin; z <= zMax; z++) {
                    for (int y = yMin; y <= yMax; y++) {
                        for (int x = xMin; x <= xMax; x++) {
                            if (!running)
                                return;
                            // Inline squared distance in world units to avoid per-voxel object creation
                            final double wx = (x - xc) * vxW;
                            final double wy = (y - yc) * vxH;
                            final double wz = (z - zc) * vxD;
                            final double r2 = wx*wx + wy*wy + wz*wz;
                            if (r2 > lowerR * lowerR && r2 < upperR * upperR) {
                                final double vxValue = stack.getVoxel(x, y, z); // all 0-based indices
                                if (!withinThreshold(vxValue) || (isSkipSingleVoxels() && !hasNeighbors(x, y, z)))
                                    continue;
                                voxelSet.add(key(x, y, z));
                                if (intensityMode) {
                                    intensitySum += vxValue;
                                } else {
                                    pixelPoints.add(new ShollPoint(x, y, z));
                                }
                            }
                        }
                    }
                }

                // compute length
                final double lengthBeforeCulling = calculateLengthInShell3D(voxelSet);

                // We now have the shell voxels; assign profile entries
                if (intensityMode) {
                    final int n = voxelSet.size();
                    final double mean = (n > 0) ? (intensitySum / n) : Double.NaN;
                    profile.add(new ProfileEntry(r, mean, lengthBeforeCulling));
                } else {
                    cullTotUnique3DGroups(pixelPoints);
                    ShollPoint.scale(pixelPoints, cal);
                    profile.add(new ProfileEntry(r, pixelPoints.size(), lengthBeforeCulling, new HashSet<>(pixelPoints)));
                }
            }
        }
    }

    // ----------------- Component culling (O(N) BFS over 26-neighbors) -----------------

    void cullTotUnique3DGroups(final List<ShollPoint> points) {
        if (points.isEmpty()) return;

        // Membership set for O(1) neighbor checks
        final HashSet<Long> S = new HashSet<>(points.size() * 2);
        for (final ShollPoint p : points) S.add(key((int) p.x, (int) p.y, (int) p.z));

        // Visited set and BFS queue
        final HashSet<Long> visited = new HashSet<>(S.size() * 2);
        final ArrayDeque<long[]> q = new ArrayDeque<>();

        // Results
        final ArrayList<ShollPoint> reps = new ArrayList<>();

        // 26-neighborhood (excluding self)
        final int[][] NBR = new int[26][3];
        {
            int t = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if (!(dx == 0 && dy == 0 && dz == 0)) NBR[t++] = new int[]{dx, dy, dz};
        }

        for (final ShollPoint seed : points) {
            final long sKey = key((int) seed.x, (int) seed.y, (int) seed.z);
            if (!S.contains(sKey) || visited.contains(sKey)) continue;

            // Gather one connected component by BFS
            final ArrayList<Long> comp = new ArrayList<>(64);
            double sumWX = 0, sumWY = 0, sumWZ = 0; // world-coordinate sums
            int cnt = 0;

            visited.add(sKey);
            q.add(new long[]{(long) seed.x, (long) seed.y, (long) seed.z});

            while (!q.isEmpty()) {
                final long[] v = q.removeFirst();
                final int x = (int) v[0], y = (int) v[1], z = (int) v[2];
                final long k = key(x, y, z);
                comp.add(k);
                // accumulate centroid in world units (handles anisotropic voxels)
                sumWX += x * vxW;
                sumWY += y * vxH;
                sumWZ += z * vxD;
                cnt++;

                for (int i = 0; i < 26; i++) {
                    final int nx = x + NBR[i][0], ny = y + NBR[i][1], nz = z + NBR[i][2];
                    final long nk = key(nx, ny, nz);
                    if (!S.contains(nk) || !visited.add(nk)) continue;
                    q.add(new long[]{nx, ny, nz});
                }
            }

            // Choose representative voxel closest to the centroid (world distance)
            int rx = (int) seed.x, ry = (int) seed.y, rz = (int) seed.z; // default fallback
            if (cnt > 0) {
                final double cx = sumWX / cnt;
                final double cy = sumWY / cnt;
                final double cz = sumWZ / cnt;
                double best = Double.POSITIVE_INFINITY;
                for (final long k : comp) {
                    final int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
                    final double dx = x * vxW - cx;
                    final double dy = y * vxH - cy;
                    final double dz = z * vxD - cz;
                    final double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < best) {
                        best = d2;
                        rx = x;
                        ry = y;
                        rz = z;
                    }
                }
            }

            reps.add(new ShollPoint(rx, ry, rz));
        }

        points.clear();
        points.addAll(reps);
    }

    // ----------------- Neighborhood test for isolated pixels -----------------

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
            final int nx = neighbor[0];
            final int ny = neighbor[1];
            final int nz = neighbor[2];
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

    // ----------------- Voxel key packing -----------------

    /** Packs x,y,z voxel indices into a single long key for set membership tests. */
    private static long key(final int x, final int y, final int z) {
        // Assumes dimensions < 2^21 along each axis. Masks keep values positive.
        final long X = ((long) x) & 0x1FFFFFL;
        final long Y = ((long) y) & 0x1FFFFFL;
        final long Z = ((long) z) & 0x1FFFFFL;
        return (X << 42) | (Y << 21) | Z;
    }
    private static int unpackX(final long k) { return (int) ((k >>> 42) & 0x1FFFFFL); }
    private static int unpackY(final long k) { return (int) ((k >>> 21) & 0x1FFFFFL); }
    private static int unpackZ(final long k) { return (int) ( k         & 0x1FFFFFL); }

    // ----------------- Shell length computations (calibrated, in physical units) -----------------

    /**
     * Calibrated shell length from a set of voxel keys (streaming intensity mode).
     * Sums edge lengths in a precomputed half 26-neighborhood to avoid double-counting.
     */
    private double calculateLengthInShell3D(final Set<Long> voxelSet) {
        if (voxelSet == null || voxelSet.isEmpty()) return 0.0;
        double length = 0.0;
        for (final long k : voxelSet) {
            final int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
            for (int i = 0; i < halfNbr.length; i++) {
                final int[] d = halfNbr[i];
                final int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (voxelSet.contains(key(nx, ny, nz))) {
                    length += halfLen[i];
                }
            }
        }
        return length;
    }
}