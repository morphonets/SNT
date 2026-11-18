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

package sc.fiji.snt.tracing.auto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import ij.process.AutoThresholder;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * Detects the tips of tubular structures directly from a ridge (e.g., Tubeness
 * or Frangi) score map, without binarizing, skeletonizing or tracing the image.
 * <p>
 * The score map is only read through random access, so it can be a lazy,
 * disk-cached image of any size: voxels are visited on a coarse grid in
 * parallel blocks, and the neighborhood of each voxel is read across block
 * (and cell) borders transparently. No halo or edge bookkeeping is needed,
 * other than skipping voxels closer than the analysis radius to the faces of
 * the volume.
 * </p>
 * Local geometry: at each candidate voxel above {@link #setThreshold(double)},
 * the voxels above threshold within {@link #setRadius(double)} are summarized by
 * their centroid and covariance. At a tip, the structure lies on one side
 * only, i.e., the centroid is displaced from the candidate (asymmetry), the
 * structure is elongated (linearity) and the displacement follows its main
 * axis (alignment). Along a segment the centroid sits on the candidate, and at
 * branch points the structure is not elongated. Candidates are then reduced to
 * the best one per radius and pushed outwards to the end of the structure.
 * <p>
 * Coordinates are voxel indices times spacing (no origin offset applied).
 * </p>
 *
 * @param <T> the pixel type of the score map
 * @author Tiago Ferreira
 */
public class TipFinder<T extends RealType<T>> {

    /**
     * A detected tip.
     *
     * @param position  position in calibrated (physical) units
     * @param direction unit vector pointing from the tip into the structure
     * @param score     value in [0,1]: asymmetry times linearity of the neighborhood
     */
    public record Tip(double[] position, double[] direction, double score) {
    }

    private record Candidate(int x, int y, int z, double score, double dx, double dy, double dz) {
    }

    /** Counts of voxels reaching/failing each test, for debugging */
    private static final class Diagnostics {
        final LongAdder visited = new LongAdder(), aboveThreshold = new LongAdder(), failMass = new LongAdder(),
                failVeto = new LongAdder(), failConnected = new LongAdder(), failExtent = new LongAdder(), failPurity = new LongAdder(),
                failAsymmetry = new LongAdder(), failLinearity = new LongAdder(), failAlignment = new LongAdder(),
                accepted = new LongAdder();
        // Sums over the voxels that reached each test
        final DoubleAdder sumAsymmetry = new DoubleAdder(), sumLinearity = new DoubleAdder(),
                sumAlignment = new DoubleAdder(), sumMass = new DoubleAdder(), sumConnected = new DoubleAdder(),
                sumExtent = new DoubleAdder(), sumPurity = new DoubleAdder(), sumVeto = new DoubleAdder();
        final LongAdder nVeto = new LongAdder(), nConnected = new LongAdder(), nExtent = new LongAdder(), nPurity = new LongAdder(),
                nAsymmetry = new LongAdder(), nLinearity = new LongAdder(), nAlignment = new LongAdder();
    }

    /** Per-thread work space of the flood fill (neighborhood box, visit stamps and queue) */
    private static final class Scratch {
        final int[] rad;
        final int nx, ny;
        final int[] stamp;
        final int[] queue;
        RandomAccess<? extends RealType<?>> vetoAccess;
        int current;

        Scratch(final int[] rad) {
            this.rad = rad;
            nx = 2 * rad[0] + 1;
            ny = 2 * rad[1] + 1;
            final int size = nx * ny * (2 * rad[2] + 1);
            stamp = new int[size];
            queue = new int[size];
        }

        int index(final int dx, final int dy, final int dz) {
            return ((dz + rad[2]) * ny + (dy + rad[1])) * nx + (dx + rad[0]);
        }
    }

    private final RandomAccessibleInterval<T> ridge;
    private final double[] spacing;
    private double threshold = Double.NaN;
    private double radius;
    private double minAsymmetry = 0.5;
    private double minLinearity = 0.6;
    private double minAlignment = 0.7;
    private int minMass = 10;
    private RandomAccessibleInterval<? extends RealType<?>> vetoImg;
    private RandomAccessible<? extends RealType<?>> vetoExt;
    private double vetoThreshold = Double.NaN;
    private double vetoRadius = 0;
    private List<int[]> vetoOffsets;
    private double minExtent = 0.6;
    private double minPurity = 0.3;
    private int stride = 0; // 0: derived from radius
    private int blockSize = 64;
    private int numThreads = Runtime.getRuntime().availableProcessors();
    private DoubleConsumer progressListener;
    private Consumer<String> debugLog;
    private volatile boolean cancelled;

    /**
     * Creates a new TipFinder.
     *
     * @param ridge   the 3D ridge score map. Larger values must mean more tubular
     * @param spacing the voxel size (x, y, z) in calibrated units
     */
    public TipFinder(final RandomAccessibleInterval<T> ridge, final double[] spacing) {
        if (ridge == null || ridge.numDimensions() != 3)
            throw new IllegalArgumentException("A 3D score map is required");
        if (spacing == null || spacing.length < 3)
            throw new IllegalArgumentException("Spacing must have 3 elements");
        for (int d = 0; d < 3; d++) {
            if (!(spacing[d] > 0)) throw new IllegalArgumentException("Spacing must be positive");
        }
        this.ridge = ridge;
        this.spacing = new double[]{spacing[0], spacing[1], spacing[2]};
        this.radius = 6 * Math.min(spacing[0], Math.min(spacing[1], spacing[2]));
    }

    /**
     * Sets the score above which a voxel is considered part of a structure.
     * If never set (or NaN), {@link #find()} uses
     * {@link #estimateThreshold(AutoThresholder.Method, int)}
     *
     * @param threshold the threshold
     * @return this instance
     */
    public TipFinder<T> setThreshold(final double threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * Sets the radius of the neighborhood analyzed at each voxel. It should be
     * about twice the radius of the thickest structure: Smaller values detect
     * the tips of shorter branches, but are less selective.
     *
     * @param radius the radius in calibrated units
     * @return this instance
     */
    public TipFinder<T> setRadius(final double radius) {
        if (!(radius > 0)) throw new IllegalArgumentException("Radius must be positive");
        this.radius = radius;
        return this;
    }

    /**
     * Sets the minimum displacement of the neighborhood centroid, relative to
     * half the radius, in [0,1] (default 0.5).
     *
     * @param minAsymmetry the minimum asymmetry
     * @return this instance
     */
    public TipFinder<T> setMinAsymmetry(final double minAsymmetry) {
        this.minAsymmetry = minAsymmetry;
        return this;
    }

    /**
     * Sets the minimum elongation of the neighborhood, as the relative gap
     * between its two largest covariance eigenvalues, in [0,1] (default 0.6).
     *
     * @param minLinearity the minimum linearity
     * @return this instance
     */
    public TipFinder<T> setMinLinearity(final double minLinearity) {
        this.minLinearity = minLinearity;
        return this;
    }

    /**
     * Sets the minimum alignment between the centroid displacement and the
     * main axis of the neighborhood, in [0,1] (default 0.7).
     *
     * @param minAlignment the minimum alignment
     * @return this instance
     */
    public TipFinder<T> setMinAlignment(final double minAlignment) {
        this.minAlignment = minAlignment;
        return this;
    }

    /**
     * Sets the minimum number of voxels above threshold in the neighborhood,
     * and of those connected to the candidate (default 10).
     *
     * @param minMass the minimum number of voxels
     * @return this instance
     */
    public TipFinder<T> setMinMass(final int minMass) {
        this.minMass = Math.max(1, minMass);
        return this;
    }

    /**
     * Sets an image used to reject candidates: a candidate is discarded if the mean of this image in a small
     * sphere around it exceeds the veto threshold (see {@link #setVetoThreshold(double)}). Typically a
     * background channel (e.g., autofluorescence). For single channel images, use a heavily smoothed version of
     * the image: thin structures are diluted, whereas large bright structures (e.g., haze or tissue) are not
     *
     * @param veto   the veto image. Must have the same interval as the score map. Null disables the veto
     * @param radius the radius (calibrated units) of the sphere in which the veto image is averaged
     * @param <V>    the veto image type
     * @return this instance
     */
    public <V extends RealType<V>> TipFinder<T> setVeto(final RandomAccessibleInterval<V> veto, final double radius) {
        if (veto == null) {
            vetoImg = null;
            vetoExt = null;
            return this;
        }
        if (!net.imglib2.util.Intervals.equals(veto, ridge))
            throw new IllegalArgumentException("The veto image must have the same interval as the score map");
        vetoImg = veto;
        vetoExt = Views.extendZero(veto);
        vetoRadius = Math.max(0, radius);
        return this;
    }

    /**
     * Sets the veto threshold: candidates are discarded when the mean of the veto image around them is larger. If
     * NaN (default), the 95th percentile of the veto image is used
     *
     * @param threshold the threshold, in the intensity units of the veto image
     * @return this instance
     * @see #setVeto(RandomAccessibleInterval, double)
     * @see #vetoPercentile(double, int)
     */
    public TipFinder<T> setVetoThreshold(final double threshold) {
        this.vetoThreshold = threshold;
        return this;
    }

    /**
     * Gets a percentile of the veto image from a strided sample (all values, including zeros)
     *
     * @param percentile   the percentile, in the range (0, 100)
     * @param sampleStride the sampling step (voxels) along each axis
     * @return the value at that percentile
     * @throws IllegalStateException if no veto image has been set
     */
    public double vetoPercentile(final double percentile, final int sampleStride) {
        if (vetoImg == null) throw new IllegalStateException("No veto image set");
        if (!(percentile > 0 && percentile < 100))
            throw new IllegalArgumentException("Percentile must be in the range (0, 100): " + percentile);
        final float[] v = sample(vetoImg, sampleStride, false).values();
        return v[(int) Math.round(percentile / 100d * (v.length - 1))];
    }

    /**
     * Summarizes the veto image from a strided sample: mean, median and some percentiles
     *
     * @param sampleStride the sampling step (voxels) along each axis
     * @return the summary
     */
    public String describeVeto(final int sampleStride) {
        if (vetoImg == null) return "no veto image";
        final float[] v = sample(vetoImg, sampleStride, false).values();
        double sum = 0;
        for (final float f : v) sum += f;
        final int n = v.length;
        return String.format("sampled %d voxels: mean %.4g, median %.4g, p90 %.4g, p95 %.4g, p99 %.4g, max %.4g", n,
                sum / n, v[n / 2], v[(int) (0.90 * (n - 1))], v[(int) (0.95 * (n - 1))],
                v[(int) (0.99 * (n - 1))], v[n - 1]);
    }

    /** Sorted values of a strided sample, and the number of positions sampled */
    private record Sample(float[] values, long total) {
    }

    /**
     * Samples an image on a grid (stride voxels apart). The grid is processed in parallel by blocks, so that a
     * lazy image is computed by all threads, not by the calling one
     */
    private Sample sample(final RandomAccessibleInterval<? extends RealType<?>> rai, final int stride,
                          final boolean positiveOnly) {
        final int s = Math.max(1, stride);
        final int kb = Math.max(1, blockSize / s);
        final long[] nk = new long[3];
        long total = 1;
        for (int d = 0; d < 3; d++) {
            nk[d] = (rai.dimension(d) - 1) / s + 1;
            total *= nk[d];
        }
        final List<long[]> blocks = new ArrayList<>();
        for (long kz = 0; kz < nk[2]; kz += kb)
            for (long ky = 0; ky < nk[1]; ky += kb)
                for (long kx = 0; kx < nk[0]; kx += kb)
                    blocks.add(new long[]{kx, ky, kz});
        final ForkJoinPool pool = new ForkJoinPool(Math.max(1, numThreads));
        try {
            final List<float[]> parts = pool.submit(() -> blocks.parallelStream().map(b -> {
                final RandomAccess<? extends RealType<?>> ra = rai.randomAccess();
                float[] values = new float[256];
                int n = 0;
                for (long kz = b[2]; kz < Math.min(nk[2], b[2] + kb); kz++) {
                    for (long ky = b[1]; ky < Math.min(nk[1], b[1] + kb); ky++) {
                        for (long kx = b[0]; kx < Math.min(nk[0], b[0] + kb); kx++) {
                            ra.setPosition(rai.min(0) + kx * s, 0);
                            ra.setPosition(rai.min(1) + ky * s, 1);
                            ra.setPosition(rai.min(2) + kz * s, 2);
                            final double v = ra.get().getRealDouble();
                            if (positiveOnly && !(v > 0)) continue;
                            if (n == values.length) values = java.util.Arrays.copyOf(values, n * 2);
                            values[n++] = (float) v;
                        }
                    }
                }
                return java.util.Arrays.copyOf(values, n);
            }).toList()).get();
            int size = 0;
            for (final float[] part : parts) size += part.length;
            final float[] all = new float[size];
            int pos = 0;
            for (final float[] part : parts) {
                System.arraycopy(part, 0, all, pos, part.length);
                pos += part.length;
            }
            java.util.Arrays.parallelSort(all);
            return new Sample(all, total);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sampling", e);
        } catch (final java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Sampling failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Sets the minimum extent of the structure that is connected to a candidate, as a fraction of the radius
     * (default 0.6). Connected means 26-connected voxels above threshold within the neighborhood. Isolated
     * speckles, which are small, do not reach it. Use 0 to disable
     *
     * @param minExtent the fraction of the radius that the connected structure must span, in [0, 1]
     * @return this instance
     */
    public TipFinder<T> setMinExtent(final double minExtent) {
        this.minExtent = Math.max(0, Math.min(1, minExtent));
        return this;
    }

    /**
     * Sets the minimum purity (default 0.3): the fraction of the voxels above threshold in the neighborhood that
     * are connected to the candidate. Low values mean that the candidate sits in clutter (e.g., speckles, haze)
     * Use 0 to disable
     *
     * @param minPurity the fraction, in [0, 1]
     * @return this instance
     */
    public TipFinder<T> setMinPurity(final double minPurity) {
        this.minPurity = Math.max(0, Math.min(1, minPurity));
        return this;
    }

    /**
     * Sets the edge (voxels) of the cells in which candidate tips are sought: the voxel with the highest score
     * above threshold in each cell is evaluated. The default is a third of the radius, which is dense enough not to
     * miss a tip, whatever the width of the structure
     *
     * @param stride the step, or &le;0 for the default
     * @return this instance
     */
    public TipFinder<T> setStride(final int stride) {
        this.stride = Math.max(0, stride);
        return this;
    }

    /**
     * Sets the edge (voxels) of the cubic blocks that are processed in
     * parallel. Does not affect results (default 64)
     *
     * @param blockSize the block size
     * @return this instance
     */
    public TipFinder<T> setBlockSize(final int blockSize) {
        this.blockSize = Math.max(8, blockSize);
        return this;
    }

    /**
     * Sets the number of threads (default: all available processors)
     *
     * @param numThreads the number of threads
     * @return this instance
     */
    public TipFinder<T> setNumThreads(final int numThreads) {
        this.numThreads = Math.max(1, numThreads);
        return this;
    }

    /**
     * Sets a listener notified with the fraction of blocks processed
     *
     * @param listener the listener
     * @return this instance
     */
    public TipFinder<T> setProgressListener(final DoubleConsumer listener) {
        this.progressListener = listener;
        return this;
    }

    /**
     * Enables diagnostics: parameters, the number of voxels reaching or failing each test (and the mean value of
     * the measures of those that reached it) are reported to the logger when {@link #find()} ends.
     *
     * @param logger receives the messages, e.g., {@code System.out::println}. Null disables diagnostics
     * @return this instance
     */
    public TipFinder<T> setDebugLogger(final Consumer<String> logger) {
        this.debugLog = logger;
        return this;
    }

    private void debug(final String msg) {
        if (debugLog != null) debugLog.accept("[TipFinder] " + msg);
    }

    /**
     * Summarizes the distribution of the (positive) scores of a strided sample: count, mean, maximum and some
     * percentiles. Useful to choose a threshold: it should sit below the scores of the structures of interest.
     *
     * @param sampleStride the sampling step (voxels) along each axis
     * @return the summary
     */
    public String describeScores(final int sampleStride) {
        final Sample sample = sample(ridge, sampleStride, true);
        final float[] values = sample.values();
        final int n = values.length;
        if (n == 0) return "sampled " + sample.total() + " voxels (stride " + sampleStride + "): no positive scores";
        double sum = 0;
        for (final float v : values) sum += v;
        return String.format("sampled %d voxels (stride %d): %d positive (%.2f%%), mean %.4g, median %.4g, "
                        + "p90 %.4g, p99 %.4g, p99.9 %.4g, max %.4g", sample.total(), Math.max(1, sampleStride), n,
                100d * n / sample.total(), sum / n, values[n / 2], values[(int) (0.90 * (n - 1))],
                values[(int) (0.99 * (n - 1))], values[(int) (0.999 * (n - 1))], values[n - 1]);
    }

    /** Requests {@link #find()} to return as soon as possible (it then returns null) */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Gets a percentile of the positive scores of a strided sample. Unlike {@link #estimateThreshold}, the result
     * does not depend on the shape of the score distribution, and the percentile transfers across datasets
     * (whose scores depend on the image intensity)
     *
     * @param percentile   the percentile, in the range (0, 100), e.g., 90
     * @param sampleStride the sampling step (voxels) along each axis
     * @return the score at that percentile
     * @throws IllegalStateException if the sample has no positive scores
     */
    public double percentileThreshold(final double percentile, final int sampleStride) {
        if (!(percentile > 0 && percentile < 100))
            throw new IllegalArgumentException("Percentile must be in the range (0, 100): " + percentile);
        final float[] values = sample(ridge, sampleStride, true).values();
        if (values.length == 0) throw new IllegalStateException("No positive scores in sample: nothing to threshold");
        return values[(int) Math.round(percentile / 100d * (values.length - 1))];
    }

    /**
     * Estimates a threshold from a strided sample of the positive scores.
     * Scores are binned between 0 and mean + 6 standard deviations, so heavy
     * tails do not flatten the histogram
     *
     * @param method       the automatic threshold method (e.g., Triangle)
     * @param sampleStride the sampling step (voxels) along each axis
     * @return the threshold
     * @throws IllegalStateException if the sample has no positive scores
     */
    public double estimateThreshold(final AutoThresholder.Method method, final int sampleStride) {
        final long s = Math.max(1, sampleStride);
        final RandomAccessibleInterval<T> sampled = Views.subsample(ridge, s, s, s);
        long n = 0;
        double mean = 0;
        double m2 = 0;
        for (final Cursor<T> c = Views.iterable(sampled).cursor(); c.hasNext(); ) {
            final double v = c.next().getRealDouble();
            if (!(v > 0)) continue;
            n++;
            final double delta = v - mean;
            mean += delta / n;
            m2 += delta * (v - mean);
        }
        if (n < 2) throw new IllegalStateException("No positive scores found");
        final double upper = mean + 6 * Math.sqrt(m2 / (n - 1));
        final int[] hist = new int[256];
        for (final Cursor<T> c = Views.iterable(sampled).cursor(); c.hasNext(); ) {
            final double v = c.next().getRealDouble();
            if (!(v > 0)) continue;
            hist[Math.min(255, (int) (v / upper * 256))]++;
        }
        final int t = new AutoThresholder().getThreshold(method, hist);
        return (t + 1) * upper / 256d;
    }

    /**
     * Finds the tips.
     *
     * @return the tips, best first, or null if cancelled
     * @throws IllegalStateException if the threshold had to be estimated and that failed
     */
    public List<Tip> find() {
        cancelled = false;
        if (Double.isNaN(threshold)) threshold = percentileThreshold(90, 8);
        final double thr = threshold;
        final Diagnostics diag = (debugLog == null) ? null : new Diagnostics();
        if (vetoImg != null && Double.isNaN(vetoThreshold)) vetoThreshold = vetoPercentile(95, 8);
        final double minSpacing = Math.min(spacing[0], Math.min(spacing[1], spacing[2]));
        final int step = (stride > 0) ? stride : Math.max(1, (int) Math.round(radius / minSpacing / 3));

        // Voxels of the (ellipsoidal) neighborhood
        final int[] rad = new int[3];
        // A singleton axis (2D data as a one-slice volume) has no extent: neither neighbors nor border margin
        for (int d = 0; d < 3; d++) rad[d] = (ridge.dimension(d) == 1) ? 0 : (int) Math.floor(radius / spacing[d]);
        final List<int[]> offsets = new ArrayList<>();
        for (int dz = -rad[2]; dz <= rad[2]; dz++) {
            for (int dy = -rad[1]; dy <= rad[1]; dy++) {
                for (int dx = -rad[0]; dx <= rad[0]; dx++) {
                    final double px = dx * spacing[0];
                    final double py = dy * spacing[1];
                    final double pz = dz * spacing[2];
                    if (px * px + py * py + pz * pz <= radius * radius) offsets.add(new int[]{dx, dy, dz});
                }
            }
        }

        // Skip voxels whose neighborhood would leave the volume
        final long[] vMin = new long[3];
        final long[] vMax = new long[3];
        for (int d = 0; d < 3; d++) {
            final long margin = (ridge.dimension(d) == 1) ? 0 : (long) Math.ceil(radius / spacing[d]) + 1;
            vMin[d] = ridge.min(d) + margin;
            vMax[d] = ridge.max(d) - margin;
            if (vMax[d] < vMin[d]) {
                debug("Nothing to scan: axis " + d + " has " + ridge.dimension(d) + " voxels, but " + (2 * margin)
                        + " are skipped at its faces (radius " + radius + " / spacing " + spacing[d]
                        + "). Use a smaller radius or a larger region");
                return new ArrayList<>();
            }
        }

        vetoOffsets = null;
        if (vetoImg != null) {
            final int[] vr = new int[3];
            for (int d = 0; d < 3; d++) vr[d] = (int) Math.floor(vetoRadius / spacing[d]);
            vetoOffsets = new ArrayList<>();
            for (int dz = -vr[2]; dz <= vr[2]; dz++)
                for (int dy = -vr[1]; dy <= vr[1]; dy++)
                    for (int dx = -vr[0]; dx <= vr[0]; dx++) {
                        final double px = dx * spacing[0], py = dy * spacing[1], pz = dz * spacing[2];
                        if (px * px + py * py + pz * pz <= vetoRadius * vetoRadius)
                            vetoOffsets.add(new int[]{dx, dy, dz});
                    }
            debug("veto: radius " + vetoRadius + " (" + vetoOffsets.size() + " voxels), threshold " + vetoThreshold);
        }

        final List<long[]> blocks = new ArrayList<>();
        for (long z = vMin[2]; z <= vMax[2]; z += blockSize)
            for (long y = vMin[1]; y <= vMax[1]; y += blockSize)
                for (long x = vMin[0]; x <= vMax[0]; x += blockSize)
                    blocks.add(new long[]{x, y, z});

        debug("threshold " + thr + ", radius " + radius + ", cell size " + step + ", minMass " + minMass
                + ", minExtent " + minExtent + ", minPurity " + minPurity
                + ", min asymmetry/linearity/alignment " + minAsymmetry + "/" + minLinearity + "/" + minAlignment
                + ", neighborhood " + offsets.size() + " voxels, volume "
                + java.util.Arrays.toString(ridge.dimensionsAsLongArray()) + ", scanned "
                + (vMax[0] - vMin[0] + 1) + "x" + (vMax[1] - vMin[1] + 1) + "x" + (vMax[2] - vMin[2] + 1)
                + " in " + blocks.size() + " block(s)");
        if (offsets.size() < 2 * minMass)
            debug("WARNING: the neighborhood (" + offsets.size() + " voxels) is too small for minMass " + minMass
                    + ": no voxel can pass. Radius is " + String.format("%.1f", radius / minSpacing)
                    + " voxel(s). Increase the radius (>= 4 voxels recommended)");
        final RandomAccessible<T> extended = Views.extendZero(ridge);
        final AtomicInteger done = new AtomicInteger();
        final List<Candidate> candidates = new ArrayList<>();
        final ForkJoinPool pool = new ForkJoinPool(numThreads);
        try {
            final List<List<Candidate>> results = pool.submit(() -> blocks.parallelStream().map(b -> {
                if (cancelled) return new ArrayList<Candidate>();
                final List<Candidate> found = scanBlock(extended, b, vMin, vMax, step, offsets, thr, diag, rad);
                if (progressListener != null) progressListener.accept(done.incrementAndGet() / (double) blocks.size());
                return found;
            }).toList()).get();
            results.forEach(candidates::addAll);
        } catch (final InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tip search failed: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
        if (cancelled) return null;
        if (diag != null) reportDiagnostics(diag, candidates.size());

        // Keep the best candidate per radius, then push it to the end of the structure
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        final Map<Long, List<Candidate>> grid = new HashMap<>();
        final List<Candidate> kept = new ArrayList<>();
        for (final Candidate c : candidates) {
            if (!hasKeptNeighbor(grid, c)) {
                kept.add(c);
                grid.computeIfAbsent(cellKey(c), k -> new ArrayList<>()).add(c);
            }
        }
        final RandomAccess<T> ra = extended.randomAccess();
        final List<Tip> tips = new ArrayList<>(kept.size());
        for (final Candidate c : kept) tips.add(toTip(ra, c, thr));
        debug(candidates.size() + " candidates reduced to " + tips.size() + " tip(s)");
        return tips;
    }

    private void reportDiagnostics(final Diagnostics d, final int nCandidates) {
        final long above = d.aboveThreshold.sum();
        debug(String.format("visited %d cells, %d with voxels above threshold (%.2f%%)", d.visited.sum(), above,
                100d * above / Math.max(1, d.visited.sum())));
        if (vetoImg != null)
            debug(String.format("veto: %d failed (> %.4g); mean %.4g", d.failVeto.sum(), vetoThreshold,
                    d.sumVeto.sum() / Math.max(1, d.nVeto.sum())));
        debug(String.format("mass: %d failed (< %d voxels in neighborhood); mean %.1f", d.failMass.sum(), minMass,
                d.sumMass.sum() / Math.max(1, above)));
        debug(String.format("connected mass: %d failed (< %d voxels connected to the voxel); mean %.1f",
                d.failConnected.sum(), minMass, d.sumConnected.sum() / Math.max(1, d.nConnected.sum())));
        debug(String.format("extent: %d failed (< %.2f of radius); mean %.3f", d.failExtent.sum(), minExtent,
                d.sumExtent.sum() / Math.max(1, d.nExtent.sum())));
        debug(String.format("purity: %d failed (< %.2f); mean %.3f", d.failPurity.sum(), minPurity,
                d.sumPurity.sum() / Math.max(1, d.nPurity.sum())));
        debug(String.format("asymmetry: %d failed (< %.2f); mean %.3f", d.failAsymmetry.sum(), minAsymmetry,
                d.sumAsymmetry.sum() / Math.max(1, d.nAsymmetry.sum())));
        debug(String.format("linearity: %d failed (< %.2f); mean %.3f", d.failLinearity.sum(), minLinearity,
                d.sumLinearity.sum() / Math.max(1, d.nLinearity.sum())));
        debug(String.format("alignment: %d failed (< %.2f); mean %.3f", d.failAlignment.sum(), minAlignment,
                d.sumAlignment.sum() / Math.max(1, d.nAlignment.sum())));
        debug(nCandidates + " candidate(s) accepted");
    }

    private List<Candidate> scanBlock(final RandomAccessible<T> extended, final long[] origin, final long[] vMin,
                                      final long[] vMax, final int step, final List<int[]> offsets,
                                      final double thr, final Diagnostics diag, final int[] rad) {
        final Scratch scratch = new Scratch(rad);
        if (vetoExt != null) scratch.vetoAccess = vetoExt.randomAccess();
        final List<Candidate> found = new ArrayList<>();
        final RandomAccess<T> ra = extended.randomAccess();
        final long[] lo = new long[3];
        final long[] hi = new long[3];
        final long[] k0 = new long[3];
        for (int d = 0; d < 3; d++) {
            lo[d] = origin[d];
            hi[d] = Math.min(vMax[d], origin[d] + blockSize - 1);
            k0[d] = Math.floorDiv(lo[d] - vMin[d] + step - 1, step); // first grid index in block
        }
        final double[] eig = new double[3];
        final double[] axis = new double[3];
        // Every voxel of every cell is read, and the voxel with the highest score is the seed of the cell: a thin
        // structure cannot slip between the seeds, as it could if only the voxels of a lattice were visited
        for (long z = vMin[2] + k0[2] * step; z <= hi[2]; z += step) {
            for (long y = vMin[1] + k0[1] * step; y <= hi[1]; y += step) {
                for (long x = vMin[0] + k0[0] * step; x <= hi[0]; x += step) {
                    final long zEnd = Math.min(vMax[2], z + step - 1);
                    final long yEnd = Math.min(vMax[1], y + step - 1);
                    final long xEnd = Math.min(vMax[0], x + step - 1);
                    double best = thr;
                    long bx = -1, by = -1, bz = -1;
                    for (long zz = z; zz <= zEnd; zz++) {
                        for (long yy = y; yy <= yEnd; yy++) {
                            ra.setPosition(x, 0);
                            ra.setPosition(yy, 1);
                            ra.setPosition(zz, 2);
                            for (long xx = x; xx <= xEnd; xx++) {
                                final double v = ra.get().getRealDouble();
                                if (v > best) {
                                    best = v;
                                    bx = xx;
                                    by = yy;
                                    bz = zz;
                                }
                                ra.fwd(0);
                            }
                        }
                    }
                    if (diag != null) diag.visited.increment();
                    if (bx < 0) continue;
                    if (diag != null) diag.aboveThreshold.increment();
                    ra.setPosition(bx, 0);
                    ra.setPosition(by, 1);
                    ra.setPosition(bz, 2);
                    final Candidate c = evaluate(ra, bx, by, bz, offsets, thr, eig, axis, diag, scratch);
                    if (c != null) found.add(c);
                }
            }
        }
        return found;
    }

    private Candidate evaluate(final RandomAccess<T> ra, final long x, final long y, final long z,
                               final List<int[]> offsets, final double thr, final double[] eig, final double[] axis,
                               final Diagnostics diag, final Scratch sc) {
        if (sc.vetoAccess != null) {
            double sum = 0;
            for (final int[] o : vetoOffsets) {
                sc.vetoAccess.setPosition(x + o[0], 0);
                sc.vetoAccess.setPosition(y + o[1], 1);
                sc.vetoAccess.setPosition(z + o[2], 2);
                sum += sc.vetoAccess.get().getRealDouble();
            }
            final double mean = sum / vetoOffsets.size();
            if (diag != null) {
                diag.sumVeto.add(mean);
                diag.nVeto.increment();
            }
            if (mean > vetoThreshold) {
                if (diag != null) diag.failVeto.increment();
                return null;
            }
        }
        // Voxels above threshold in the neighborhood
        int total = 0;
        for (final int[] o : offsets) {
            ra.setPosition(x + o[0], 0);
            ra.setPosition(y + o[1], 1);
            ra.setPosition(z + o[2], 2);
            if (ra.get().getRealDouble() > thr) total++;
        }
        if (diag != null) diag.sumMass.add(total);
        if (total < minMass) {
            if (diag != null) diag.failMass.increment();
            return null;
        }

        // Flood fill (26-connected) of the voxels above threshold that are connected to the center
        final int[] rad = sc.rad;
        final double r2 = radius * radius;
        if (++sc.current == Integer.MAX_VALUE) {
            java.util.Arrays.fill(sc.stamp, 0);
            sc.current = 1;
        }
        final int stampValue = sc.current;
        int head = 0, tail = 0;
        sc.queue[tail++] = sc.index(0, 0, 0);
        sc.stamp[sc.queue[0]] = stampValue;
        int n = 0;
        double sx = 0, sy = 0, sz = 0, sxx = 0, syy = 0, szz = 0, sxy = 0, sxz = 0, syz = 0, maxD2 = 0;
        while (head < tail) {
            final int idx = sc.queue[head++];
            final int ox = idx % sc.nx - rad[0];
            final int oy = (idx / sc.nx) % sc.ny - rad[1];
            final int oz = idx / (sc.nx * sc.ny) - rad[2];
            final double px = ox * spacing[0];
            final double py = oy * spacing[1];
            final double pz = oz * spacing[2];
            n++;
            sx += px;
            sy += py;
            sz += pz;
            sxx += px * px;
            syy += py * py;
            szz += pz * pz;
            sxy += px * py;
            sxz += px * pz;
            syz += py * pz;
            maxD2 = Math.max(maxD2, px * px + py * py + pz * pz);
            for (int dz = -1; dz <= 1; dz++) {
                final int nz = oz + dz;
                if (nz < -rad[2] || nz > rad[2]) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    final int ny = oy + dy;
                    if (ny < -rad[1] || ny > rad[1]) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = ox + dx;
                        if (nx < -rad[0] || nx > rad[0]) continue;
                        final int nIdx = sc.index(nx, ny, nz);
                        if (sc.stamp[nIdx] == stampValue) continue;
                        sc.stamp[nIdx] = stampValue; // also marks voxels below threshold, so they are tested once
                        final double qx = nx * spacing[0];
                        final double qy = ny * spacing[1];
                        final double qz = nz * spacing[2];
                        if (qx * qx + qy * qy + qz * qz > r2) continue;
                        ra.setPosition(x + nx, 0);
                        ra.setPosition(y + ny, 1);
                        ra.setPosition(z + nz, 2);
                        if (ra.get().getRealDouble() > thr) sc.queue[tail++] = nIdx;
                    }
                }
            }
        }
        if (diag != null) {
            diag.sumConnected.add(n);
            diag.nConnected.increment();
        }
        if (n < minMass) {
            if (diag != null) diag.failConnected.increment();
            return null;
        }
        final double extent = Math.sqrt(maxD2) / radius;
        if (diag != null) {
            diag.sumExtent.add(extent);
            diag.nExtent.increment();
        }
        if (extent < minExtent) {
            if (diag != null) diag.failExtent.increment();
            return null;
        }
        final double purity = (double) n / total;
        if (diag != null) {
            diag.sumPurity.add(purity);
            diag.nPurity.increment();
        }
        if (purity < minPurity) {
            if (diag != null) diag.failPurity.increment();
            return null;
        }

        final double cx = sx / n, cy = sy / n, cz = sz / n;
        final double cLen = Math.sqrt(cx * cx + cy * cy + cz * cz);
        final double asymmetry = Math.min(1, cLen / (0.5 * radius));
        if (diag != null) {
            diag.sumAsymmetry.add(asymmetry);
            diag.nAsymmetry.increment();
        }
        if (asymmetry < minAsymmetry) {
            if (diag != null) diag.failAsymmetry.increment();
            return null;
        }
        eigen3(new double[]{sxx / n - cx * cx, syy / n - cy * cy, szz / n - cz * cz, sxy / n - cx * cy,
                sxz / n - cx * cz, syz / n - cy * cz}, eig, axis);
        if (!(eig[0] > 0)) return null;
        final double linearity = (eig[0] - eig[1]) / eig[0];
        if (diag != null) {
            diag.sumLinearity.add(linearity);
            diag.nLinearity.increment();
        }
        if (linearity < minLinearity) {
            if (diag != null) diag.failLinearity.increment();
            return null;
        }
        final double dot = (cx * axis[0] + cy * axis[1] + cz * axis[2]);
        if (diag != null) {
            diag.sumAlignment.add(Math.abs(dot) / cLen);
            diag.nAlignment.increment();
        }
        if (Math.abs(dot) / cLen < minAlignment) {
            if (diag != null) diag.failAlignment.increment();
            return null;
        }
        final double sign = Math.signum(dot);
        return new Candidate((int) x, (int) y, (int) z, asymmetry * linearity, sign * axis[0], sign * axis[1],
                sign * axis[2]);
    }

    private long cellKey(final Candidate c) {
        return key(Math.floorDiv((long) (c.x * spacing[0] / radius * 1e6), 1_000_000L),
                Math.floorDiv((long) (c.y * spacing[1] / radius * 1e6), 1_000_000L),
                Math.floorDiv((long) (c.z * spacing[2] / radius * 1e6), 1_000_000L));
    }

    private static long key(final long i, final long j, final long k) {
        return (i * 73856093L) ^ (j * 19349663L) ^ (k * 83492791L);
    }

    private boolean hasKeptNeighbor(final Map<Long, List<Candidate>> grid, final Candidate c) {
        final long ci = Math.floorDiv((long) (c.x * spacing[0] / radius * 1e6), 1_000_000L);
        final long cj = Math.floorDiv((long) (c.y * spacing[1] / radius * 1e6), 1_000_000L);
        final long ck = Math.floorDiv((long) (c.z * spacing[2] / radius * 1e6), 1_000_000L);
        for (long i = ci - 1; i <= ci + 1; i++) {
            for (long j = cj - 1; j <= cj + 1; j++) {
                for (long k = ck - 1; k <= ck + 1; k++) {
                    final List<Candidate> list = grid.get(key(i, j, k));
                    if (list == null) continue;
                    for (final Candidate other : list) {
                        final double dx = (c.x - other.x) * spacing[0];
                        final double dy = (c.y - other.y) * spacing[1];
                        final double dz = (c.z - other.z) * spacing[2];
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) return true;
                    }
                }
            }
        }
        return false;
    }

    private Tip toTip(final RandomAccess<T> ra, final Candidate c, final double thr) {
        final double[] p = {c.x * spacing[0], c.y * spacing[1], c.z * spacing[2]};
        final double[] dir = {c.dx, c.dy, c.dz};
        // Walk away from the structure for as long as the score stays above threshold
        final double minSpacing = Math.min(spacing[0], Math.min(spacing[1], spacing[2]));
        final double stepLen = 0.5 * minSpacing;
        final int maxSteps = (int) Math.ceil(radius / stepLen);
        final double[] last = p.clone();
        for (int i = 1; i <= maxSteps; i++) {
            final double qx = p[0] - dir[0] * stepLen * i;
            final double qy = p[1] - dir[1] * stepLen * i;
            final double qz = p[2] - dir[2] * stepLen * i;
            ra.setPosition(Math.round(qx / spacing[0]), 0);
            ra.setPosition(Math.round(qy / spacing[1]), 1);
            ra.setPosition(Math.round(qz / spacing[2]), 2);
            if (!(ra.get().getRealDouble() > thr)) break;
            last[0] = qx;
            last[1] = qy;
            last[2] = qz;
        }
        return new Tip(last, dir, c.score);
    }

    /**
     * Eigen-decomposition of a symmetric 3x3 matrix (Jacobi rotations)
     *
     * @param c    {xx, yy, zz, xy, xz, yz}
     * @param vals receives the eigenvalues, largest first
     * @param axis receives the unit eigenvector of the largest eigenvalue
     */
    static void eigen3(final double[] c, final double[] vals, final double[] axis) {
        final double[][] a = {{c[0], c[3], c[4]}, {c[3], c[1], c[5]}, {c[4], c[5], c[2]}};
        final double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            int p = 0, q = 1;
            double max = Math.abs(a[0][1]);
            if (Math.abs(a[0][2]) > max) {
                p = 0;
                q = 2;
                max = Math.abs(a[0][2]);
            }
            if (Math.abs(a[1][2]) > max) {
                p = 1;
                q = 2;
                max = Math.abs(a[1][2]);
            }
            if (max < 1e-14 * (Math.abs(a[0][0]) + Math.abs(a[1][1]) + Math.abs(a[2][2]) + 1e-300)) break;
            final double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
            final double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
            final double cs = 1 / Math.sqrt(t * t + 1);
            final double sn = t * cs;
            final int r = 3 - p - q;
            final double app = a[p][p], aqq = a[q][q], apq = a[p][q];
            a[p][p] = app - t * apq;
            a[q][q] = aqq + t * apq;
            a[p][q] = a[q][p] = 0;
            final double arp = a[r][p], arq = a[r][q];
            a[r][p] = a[p][r] = cs * arp - sn * arq;
            a[r][q] = a[q][r] = cs * arq + sn * arp;
            for (int k = 0; k < 3; k++) {
                final double vkp = v[k][p], vkq = v[k][q];
                v[k][p] = cs * vkp - sn * vkq;
                v[k][q] = cs * vkq + sn * vkp;
            }
        }
        final double[] e = {a[0][0], a[1][1], a[2][2]};
        int iMax = 0, iMin = 0;
        for (int i = 1; i < 3; i++) {
            if (e[i] > e[iMax]) iMax = i;
            if (e[i] < e[iMin]) iMin = i;
        }
        final int iMid = 3 - iMax - iMin;
        vals[0] = e[iMax];
        vals[1] = (iMax == iMin) ? e[iMax] : e[iMid];
        vals[2] = e[iMin];
        for (int k = 0; k < 3; k++) axis[k] = v[k][iMax];
    }
}
