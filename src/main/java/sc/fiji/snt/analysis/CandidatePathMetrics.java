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

package sc.fiji.snt.analysis;

import ij.measure.Calibration;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Quality metrics for a set of candidate paths linking the same two endpoints (e.g., paths computed with different
 * cost functions). Metrics are descriptive: they do not assume a ground truth and are meant to be compared across
 * candidates (column-wise), not combined into a single score. Background statistics are pooled across all candidates
 * so that every path is judged against the same local background.
 * <p>
 * Paths are expected to be in the voxel space of {@code img} (i.e., {@link Path#getXUnscaledDouble(int)} and friends
 * index into {@code img}).
 *
 * @author Tiago Ferreira
 */
public final class CandidatePathMetrics {

    /**
     * The metrics computed by {@link #compute(Path[], RandomAccessibleInterval)}
     */
    public enum Metric {
        /**
         * Mean absolute angle between consecutive internodes
         */
        TURNING("Turning", "Mean absolute turning angle between consecutive internodes (degrees)",
                true, Kind.ANGLE),
        /**
         * Euclidean distance between endpoints over path length
         */
        CONTRACTION("Contraction", "Euclidean distance between endpoints / path length (1 = straight line)",
                false, Kind.RATIO),
        /**
         * Mean RMS distance to the other candidates
         */
        DEVIATION("Deviation", "Mean symmetric RMS distance to the other candidate paths. Measures agreement with "
                + "the other candidates, not correctness", true, Kind.DISTANCE),
        /**
         * Path intensity relative to local background
         */
        SNR("SNR", "(Mean path intensity - background) / background sigma. Background: lower quartile of an annulus "
                + "around the path, pooled across candidates", false, Kind.RATIO),
        /**
         * Fraction of nodes at background-like intensity
         */
        DROPOUT("Dropout", "Fraction of nodes below the midpoint between background and median path intensity",
                true, Kind.PERCENT),
        /**
         * Robust coefficient of variation of background-subtracted node intensity
         */
        CV("CV", "Robust coefficient of variation (1.4826*MAD/median) of background-subtracted node intensity",
                true, Kind.RATIO),
        /**
         * Mean distance between path and local cross-sectional intensity centroid
         */
        OFF_CENTER("Off-center", "Mean distance between path and the intensity-weighted centroid of the "
                + "structure it lies on in its cross-section (nodes without signal count as the sampling radius)",
                true, Kind.DISTANCE);

        /**
         * How a metric is expressed
         */
        public enum Kind {
            ANGLE, RATIO, DISTANCE, PERCENT
        }

        private final String header;
        private final String description;
        private final boolean lowerIsBetter;
        private final Kind kind;

        Metric(final String header, final String description, final boolean lowerIsBetter, final Kind kind) {
            this.header = header;
            this.description = description;
            this.lowerIsBetter = lowerIsBetter;
            this.kind = kind;
        }

        public String header() {
            return header;
        }

        public String description() {
            return description;
        }

        public boolean lowerIsBetter() {
            return lowerIsBetter;
        }

        public Kind kind() {
            return kind;
        }

        /**
         * @param units the spacing units, used by distance metrics
         * @return the header of a table column listing this metric, e.g., "Contraction (um)"
         */
        public String columnName(final String units) {
            return (kind == Kind.DISTANCE) ? header + " (" + units + ")" : header;
        }

        /**
         * @param hasReference true if the table has a reference row against which values are compared
         * @return the (HTML) tooltip of a table header listing this metric
         */
        public String tooltip(final boolean hasReference) {
            return "<html><div width=300>" + description + "<br><i>" + (lowerIsBetter ? "Lower" : "Higher")
                    + " is better. Best values are highlighted"
                    + (hasReference ? ", and values worse than the original segment are shown in red." : ".");
        }

        /**
         * Formats a value of this metric, using "n/a" for NaN
         */
        public String format(final double v) {
            if (Double.isNaN(v)) return "n/a";
            return switch (kind) {
                case ANGLE -> String.format("%.1f", v);
                case PERCENT -> String.format("%.0f%%", v * 100);
                case RATIO, DISTANCE -> String.format("%.2f", v);
            };
        }
    }

    // Paths with fewer nodes do not yield meaningful statistics
    private static final int MIN_NODES = 5;
    /**
     * Default radius (in units of the smallest voxel side) of the cross-sectional disk sampled around each node
     */
    public static final int DEFAULT_RADIUS = 4;
    // Paths with more nodes are subsampled (every k-th node) for the cross-section and deviation metrics
    private static final int MAX_SAMPLED_NODES = 400;
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 30; // sample offsets are stored as bytes
    // Samples beyond this fraction of the sampling radius are considered background
    private static final double BACKGROUND_START = 0.6;
    // MAD to standard deviation, assuming normality
    private static final double MAD_TO_SIGMA = 1.4826;

    private CandidatePathMetrics() {
    }

    /**
     * Computes all metrics for a set of candidate paths linking the same endpoints.
     *
     * @param paths the candidate paths (null entries are allowed and yield NaN rows)
     * @param img   the image the paths were computed on. Must be accessible at the paths' unscaled node coordinates
     * @return the metric values, indexed as {@code [pathIndex][Metric.ordinal()]}. NaN means 'not computable'
     */
    public static double[][] compute(final Path[] paths, final RandomAccessibleInterval<?> img) {
        return compute(paths, img, DEFAULT_RADIUS, () -> false);
    }

    /**
     * Computes all metrics for a set of candidate paths linking the same endpoints.
     *
     * @param paths  the candidate paths (null entries are allowed and yield NaN rows)
     * @param img    the image the paths were computed on. Must be accessible at the paths' unscaled node coordinates
     * @param radius the radius (in units of the smallest voxel side) of the cross-sectional disk sampled around each
     *               node. Should be larger than the expected radius of the neurite. Clamped to a sensible range
     * @return the metric values, indexed as {@code [pathIndex][Metric.ordinal()]}. NaN means 'not computable'
     */
    public static double[][] compute(final Path[] paths, final RandomAccessibleInterval<?> img, final int radius) {
        return compute(paths, img, radius, () -> false);
    }

    /**
     * Computes all metrics for a set of candidate paths linking the same endpoints. Very long paths are subsampled
     * (every k-th node) for the most expensive metrics.
     *
     * @param paths     the candidate paths (null entries are allowed and yield NaN rows)
     * @param img       the image the paths were computed on. Must be accessible at the paths' unscaled node
     *                  coordinates
     * @param radius    the radius (in units of the smallest voxel side) of the cross-sectional disk sampled around
     *                  each node. Should be larger than the expected radius of the neurite. Clamped to a sensible
     *                  range
     * @param cancelled polled regularly: if it returns true the computation is aborted
     * @return the metric values, indexed as {@code [pathIndex][Metric.ordinal()]}. NaN means 'not computable'
     * @throws CancellationException if {@code cancelled} returned true
     */
    public static double[][] compute(final Path[] paths, final RandomAccessibleInterval<?> img, final int radius,
                                     final BooleanSupplier cancelled) throws CancellationException {
        final int rad = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        final int n = paths.length;
        final double[][] out = new double[n][Metric.values().length];
        for (final double[] row : out) Arrays.fill(row, Double.NaN);

        final boolean[] valid = new boolean[n];
        final double[][][] xyz = new double[n][][]; // physical coordinates [path][node][x,y,z]
        for (int i = 0; i < n; i++) {
            final Path p = paths[i];
            valid[i] = p != null && p.size() >= MIN_NODES;
            if (!valid[i]) continue;
            xyz[i] = physicalNodes(p);
            out[i][Metric.TURNING.ordinal()] = meanTurningAngle(xyz[i]);
            out[i][Metric.CONTRACTION.ordinal()] = contraction(xyz[i]);
        }

        // Deviation from the other candidates
        for (int i = 0; i < n; i++) {
            if (!valid[i]) continue;
            double sum = 0;
            int count = 0;
            for (int j = 0; j < n; j++) {
                if (j == i || !valid[j]) continue;
                checkCancelled(cancelled);
                sum += Math.sqrt(0.5 * (meanSqDist(xyz[i], xyz[j]) + meanSqDist(xyz[j], xyz[i])));
                count++;
            }
            if (count > 0) out[i][Metric.DEVIATION.ordinal()] = sum / count;
        }

        // Intensity-based metrics
        try {
            intensityMetrics(paths, valid, xyz, img, rad, cancelled, out);
        } catch (final CancellationException ex) {
            throw ex;
        } catch (final RuntimeException ex) {
            // E.g., lazy data became unavailable: leave intensity metrics as NaN
            sc.fiji.snt.SNTUtils.log("Cost-function metrics: intensity sampling failed: " + ex.getMessage());
        }
        return out;
    }

    /**
     * Checks if a value is the best in its column. Values that are displayed identically to the best one
     * ({@link Metric#format(double)}) are also reported as best.
     * At least two non-NaN values are needed in the column for any of them to be called best.
     *
     * @param values the output of {@link #compute(Path[], RandomAccessibleInterval)}
     * @param metric the metric (column)
     * @param row    the row (path) index
     * @return true if the value at {@code row} is the best (or tied for best) in its column
     */
    public static boolean isBest(final double[][] values, final Metric metric, final int row) {
        final int col = metric.ordinal();
        final double v = values[row][col];
        if (Double.isNaN(v)) return false;
        double best = Double.NaN;
        for (final double[] r : values) {
            final double x = r[col];
            if (Double.isNaN(x)) continue;
            if (Double.isNaN(best) || (metric.lowerIsBetter() ? x < best : x > best)) best = x;
        }
        // Need at least two values to call anything 'best'
        int valid = 0;
        for (final double[] r : values) if (!Double.isNaN(r[col])) valid++;
        if (valid < 2) return false;
        // Ties are defined by what is displayed: values that read the same as the best one are also best
        return metric.format(v).equals(metric.format(best));
    }

    // ---- Geometry ----

    private static double[][] physicalNodes(final Path p) {
        final double[][] xyz = new double[p.size()][3];
        for (int i = 0; i < xyz.length; i++) {
            final var node = p.getNode(i);
            xyz[i][0] = node.x;
            xyz[i][1] = node.y;
            xyz[i][2] = node.z;
        }
        return xyz;
    }

    private static double meanTurningAngle(final double[][] xyz) {
        double sum = 0;
        int count = 0;
        for (int i = 1; i < xyz.length - 1; i++) {
            final double[] a = sub(xyz[i], xyz[i - 1]);
            final double[] b = sub(xyz[i + 1], xyz[i]);
            final double na = norm(a);
            final double nb = norm(b);
            if (na == 0 || nb == 0) continue;
            final double cos = Math.clamp(dot(a, b) / (na * nb), -1d, 1d);
            sum += Math.toDegrees(Math.acos(cos));
            count++;
        }
        return (count == 0) ? Double.NaN : sum / count;
    }

    private static double contraction(final double[][] xyz) {
        double length = 0;
        for (int i = 1; i < xyz.length; i++) length += norm(sub(xyz[i], xyz[i - 1]));
        if (length == 0) return Double.NaN;
        return norm(sub(xyz[xyz.length - 1], xyz[0])) / length;
    }

    // Mean squared distance from the nodes of a to the polyline b
    private static double meanSqDist(final double[][] a, final double[][] b) {
        double sum = 0;
        final int stride = stride(a.length);
        int count = 0;
        for (int i = 0; i < a.length; i += stride) {
            final double[] p = a[i];
            count++;
            double min = Double.MAX_VALUE;
            for (int k = 1; k < b.length; k++) {
                min = Math.min(min, sqDistToSegment(p, b[k - 1], b[k]));
            }
            sum += min;
        }
        return sum / count;
    }

    private static double sqDistToSegment(final double[] p, final double[] s0, final double[] s1) {
        final double[] d = sub(s1, s0);
        final double[] w = sub(p, s0);
        final double len2 = dot(d, d);
        final double t = (len2 == 0) ? 0 : Math.clamp(dot(w, d) / len2, 0d, 1d);
        final double[] c = {s0[0] + t * d[0], s0[1] + t * d[1], s0[2] + t * d[2]};
        final double[] r = sub(p, c);
        return dot(r, r);
    }

    // ---- Intensity ----

    // Samples of the cross-section disk (perpendicular to the path) around a single node
    private record Section(double center, float[] values, byte[] a, byte[] b) {
    }

    private static void intensityMetrics(final Path[] paths, final boolean[] valid, final double[][][] xyz,
                                         final RandomAccessibleInterval<?> img, final int radius,
                                         final BooleanSupplier cancelled, final double[][] out) {
        final RandomAccess<?> ra = img.randomAccess();
        final List<List<Section>> sections = new ArrayList<>();
        double minSpacing = Double.MAX_VALUE;
        for (int i = 0; i < paths.length; i++) {
            if (!valid[i]) {
                sections.add(null);
                continue;
            }
            final Calibration cal = paths[i].getCalibration();
            final double[] sp = {cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
            minSpacing = Math.min(minSpacing, Math.min(sp[0], Math.min(sp[1], sp[2])));
            final List<Section> list = new ArrayList<>(xyz[i].length);
            final int stride = stride(xyz[i].length);
            for (int k = 0; k < xyz[i].length; k += stride) {
                checkCancelled(cancelled);
                final Section s = sampleSection(xyz[i], k, sp, img, ra, radius);
                if (s != null) list.add(s);
            }
            sections.add(list);
        }

        // Pool background samples across candidates
        final double r2min = (BACKGROUND_START * radius) * (BACKGROUND_START * radius);
        int total = 0;
        for (final List<Section> list : sections) {
            if (list == null) continue;
            for (final Section s : list) total += s.values.length;
        }
        final double[] pool = new double[total];
        int m = 0;
        for (final List<Section> list : sections) {
            if (list == null) continue;
            for (final Section s : list) {
                for (int q = 0; q < s.values.length; q++) {
                    if (s.a[q] * s.a[q] + s.b[q] * s.b[q] >= r2min) pool[m++] = s.values[q];
                }
            }
        }
        if (m < 10) return;
        final double[] bgSamples = Arrays.copyOf(pool, m);
        // Lower quartile and lower-half spread: robust to bright neighbors occupying part of the ring (assumes a
        // normal background, for which median - P25 = 0.6745 sigma)
        final double ringMedian = median(bgSamples); // sorts in place
        final double bg = bgSamples[(int) (0.25 * (bgSamples.length - 1))];
        final double sigma = Math.max((ringMedian - bg) / 0.6745, 1e-6);

        for (int i = 0; i < paths.length; i++) {
            final List<Section> list = sections.get(i);
            if (list == null || list.size() < MIN_NODES) continue;
            final double[] c = new double[list.size()];
            for (int k = 0; k < c.length; k++) c[k] = list.get(k).center;
            final double mean = Arrays.stream(c).average().orElse(Double.NaN);
            final double med = median(c.clone());
            out[i][Metric.SNR.ordinal()] = (mean - bg) / sigma;

            final double threshold = bg + 0.5 * (med - bg);
            int low = 0;
            for (final double v : c) if (v < threshold) low++;
            out[i][Metric.DROPOUT.ordinal()] = (double) low / c.length;

            if (med - bg > 0) {
                final double[] sub = new double[c.length];
                for (int k = 0; k < c.length; k++) sub[k] = c[k] - bg;
                final double subMed = median(sub.clone());
                out[i][Metric.CV.ordinal()] = (subMed > 0) ? MAD_TO_SIGMA * mad(sub, subMed) / subMed : Double.NaN;
            }

            // Centroid of the cross-section, using only the structure the path is on
            final double floor = bg + 2 * sigma;
            double dist = 0;
            int count = 0;
            for (final Section s : list) {
                final double off = centroidOffset(s, floor, radius);
                // No signal in the cross-section: path left the neurite. Count as the largest measurable offset
                dist += minSpacing * (Double.isNaN(off) ? radius : off);
                count++;
            }
            if (count > 0) out[i][Metric.OFF_CENTER.ordinal()] = dist / count;
        }
    }

    // Samples a disk of the given radius (in units of the smallest voxel side) perpendicular to the path at node k
    private static Section sampleSection(final double[][] xyz, final int k, final double[] sp,
                                         final RandomAccessibleInterval<?> img, final RandomAccess<?> ra,
                                         final int radius) {
        final double[] t = sub(xyz[Math.min(k + 1, xyz.length - 1)], xyz[Math.max(k - 1, 0)]);
        final double nt = norm(t);
        if (nt == 0) return null;
        for (int d = 0; d < 3; d++) t[d] /= nt;
        // Orthonormal basis (u, v) of the plane perpendicular to t
        final double[] h = (Math.abs(t[0]) < 0.9) ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        final double[] u = cross(t, h);
        final double nu = norm(u);
        for (int d = 0; d < 3; d++) u[d] /= nu;
        final double[] v = cross(t, u);

        final double step = Math.min(sp[0], Math.min(sp[1], sp[2]));
        final int nd = Math.min(3, img.numDimensions());
        final int cap = (2 * radius + 1) * (2 * radius + 1);
        final float[] vals = new float[cap];
        final byte[] as = new byte[cap];
        final byte[] bs = new byte[cap];
        int n = 0;
        double center = Double.NaN;
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (a * a + b * b > radius * radius) continue;
                boolean inside = true;
                for (int d = 0; d < nd && inside; d++) {
                    final double pos = xyz[k][d] + step * (a * u[d] + b * v[d]);
                    final long idx = Math.round(pos / sp[d]);
                    if (idx < img.min(d) || idx > img.max(d)) inside = false;
                    else ra.setPosition(idx, d);
                }
                if (!inside) continue;
                final double val = ((RealType<?>) ra.get()).getRealDouble();
                if (a == 0 && b == 0) center = val;
                vals[n] = (float) val;
                as[n] = (byte) a;
                bs[n] = (byte) b;
                n++;
            }
        }
        if (Double.isNaN(center) || n < 10) return null;
        return new Section(center, Arrays.copyOf(vals, n), Arrays.copyOf(as, n), Arrays.copyOf(bs, n));
    }

    // Distance (in sampling steps) between the cross-section center and the intensity-weighted centroid of the
    // connected group of samples above 'floor' that is nearest to the center, so that neighboring neurites in the
    // same disk do not bias the centroid. NaN if no sample is above 'floor'
    private static double centroidOffset(final Section s, final double floor, final int radius) {
        final int size = 2 * radius + 1;
        final float[] grid = new float[size * size];
        Arrays.fill(grid, Float.NaN);
        int seed = -1;
        int seedDist = Integer.MAX_VALUE;
        for (int q = 0; q < s.values.length; q++) {
            if (s.values[q] <= floor) continue;
            final int idx = (s.a[q] + radius) * size + (s.b[q] + radius);
            grid[idx] = s.values[q];
            final int d2 = s.a[q] * s.a[q] + s.b[q] * s.b[q];
            if (d2 < seedDist) {
                seedDist = d2;
                seed = idx;
            }
        }
        if (seed < 0) return Double.NaN;
        final boolean[] visited = new boolean[grid.length];
        final Deque<Integer> stack = new ArrayDeque<>();
        stack.push(seed);
        visited[seed] = true;
        double sw = 0, sa = 0, sb = 0;
        while (!stack.isEmpty()) {
            final int idx = stack.pop();
            final int a = idx / size - radius;
            final int b = idx % size - radius;
            final double w = grid[idx] - floor;
            sw += w;
            sa += w * a;
            sb += w * b;
            for (int da = -1; da <= 1; da++) {
                for (int db = -1; db <= 1; db++) {
                    final int na = a + da + radius;
                    final int nb = b + db + radius;
                    if (na < 0 || nb < 0 || na >= size || nb >= size) continue;
                    final int nIdx = na * size + nb;
                    if (visited[nIdx] || Float.isNaN(grid[nIdx])) continue;
                    visited[nIdx] = true;
                    stack.push(nIdx);
                }
            }
        }
        return Math.hypot(sa / sw, sb / sw);
    }

    // ---- Small vector/statistics helpers ----

    private static int stride(final int nNodes) {
        return Math.max(1, (int) Math.ceil(nNodes / (double) MAX_SAMPLED_NODES));
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Metrics computation cancelled");
    }

    private static double[] sub(final double[] a, final double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(final double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] cross(final double[] a, final double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    // Sorts the array in place
    private static double median(final double[] values) {
        Arrays.sort(values);
        final int n = values.length;
        return (n % 2 == 1) ? values[n / 2] : 0.5 * (values[n / 2 - 1] + values[n / 2]);
    }

    private static double mad(final double[] values, final double median) {
        final double[] dev = new double[values.length];
        for (int i = 0; i < dev.length; i++) dev[i] = Math.abs(values[i] - median);
        return median(dev);
    }
}
