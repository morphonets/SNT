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

package sc.fiji.snt.util;

import ij.measure.Calibration;
import org.jogamp.vecmath.Vector3d;
import sc.fiji.snt.Path;

import java.util.*;

/**
 * Utility to detect crossover locations between paths: spatially close locations between paths
 * that look like intersections in the image but are not topological joins in the traced graph.
 * <p>
 * Here, a crossover is defined as a spatial location where two distinct paths approach within
 * a distance threshold (in real units) for at least {@code minRunNodes} consecutive node pairs,
 * but do not share an actual tracing node at that location. Optional geometric filtering by
 * crossing angle is supported.
 *
 * <p>Usage:
 * <pre>
 *   CrossoverFinder.Config cfg = new CrossoverFinder.Config()
 *       .proximity(2.0)          // spatial threshold in spatially calibrated units (e.g., microns)
 *       .thetaMinDeg(25)         // optional minimum crossing angle (0 to disable)
 *       .minRunNodes(2)          // consecutive near-node pairs to accept a crossover event candidate
 *       .sameCTOnly(true)        // ignore pairs from different channel/time
 *       .includeSelfCrossovers(false) // whether crossover events within the same path should be detected
 *   List&lt;CrossoverFinder.CrossoverEvent&gt; events = CrossoverFinder.find(paths, cfg);
 * </pre>
 */
public class CrossoverFinder {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    /**
     * Entry point: detect crossover events for a collection of paths using the given config.
     *
     * @param paths the  collection of paths
     * @param cfg   the  config settings
     */
    public static List<CrossoverEvent> find(final Collection<Path> paths, final Config cfg) {
        if (paths == null || paths.isEmpty()) return Collections.emptyList();
        final double d2 = cfg.proximity * cfg.proximity;

        // Build node refs and a uniform hash grid for candidate mining
        final List<NodeRef> nodes = new ArrayList<>();
        for (final Path p : paths) {
            if (p == null || p.size() == 0) continue;
            final int c = p.getChannel();
            final int t = p.getFrame();
            for (int i = 0; i < p.size(); i++) {
                nodes.add(new NodeRef(toVec(p.getNode(i)), p, i, c, t, false));
            }
            // Also insert segment midpoints so "T" configurations (node near the middle of another path's segment)
            // are discovered by the coarse candidate stage.
            for (int i = 0; i < p.size() - 1; i++) {
                final Vector3d a = toVec(p.getNode(i));
                final Vector3d b = toVec(p.getNode(i + 1));
                final Vector3d mid = mid(a, b);
                // Use the segment start index as idx for monotonic run construction
                nodes.add(new NodeRef(mid, p, i, c, t, true));
            }
        }

        if (nodes.isEmpty()) return Collections.emptyList();

        final HashGrid3D<NodeRef> grid = new HashGrid3D<>(cfg.proximity);
        for (NodeRef nr : nodes) grid.insert(nr.pos, nr);

        // Mine near pairs and group by (pathA, pathB)
        final Map<PairKey, List<PairIdx>> byPair = new HashMap<>();
        for (NodeRef a : nodes) {
            for (NodeRef b : grid.queryNeighborhood(a.pos)) {
                if (a == b) continue;
                if (!cfg.includeSelfCrossovers && a.path == b.path) continue;
                if (cfg.sameCTOnly && (a.c != b.c || a.t != b.t)) continue;
                // Avoid adjacent self-segment proximity
                if (a.path == b.path && Math.abs(a.idx - b.idx) <= 2) continue;
                if (dist2(a.pos, b.pos) > d2) continue;
                final PairKey key = PairKey.of(a.path, b.path);
                // Ignore midpoint–midpoint proximities; midpoints exist only to catch "T"-like node > segment cases
                if (a.isMid() && b.isMid()) continue;
                byPair.computeIfAbsent(key, k -> new ArrayList<>()).add(new PairIdx(a, b));
            }
        }
        if (byPair.isEmpty()) return Collections.emptyList();

        // Extract monotonic runs and verify geometrically at the segment level
        final List<CrossoverEvent> events = new ArrayList<>();
        for (Map.Entry<PairKey, List<PairIdx>> e : byPair.entrySet()) {
            final Path A = e.getKey().a;
            final Path B = e.getKey().b;
            final List<PairIdx> pairs = e.getValue();
            pairs.sort(Comparator
                    .comparingInt((PairIdx p) -> p.iA)
                    .thenComparingInt(p -> p.iB));
            dedupeInPlaceByIndices(pairs);
            int start = 0;
            while (start < pairs.size()) {
                int end = start; // inclusive
                while (end + 1 < pairs.size() &&
                        (pairs.get(end + 1).iA - pairs.get(end).iA) <= 1 &&
                        (pairs.get(end + 1).iB - pairs.get(end).iB) <= 1) {
                    end++;
                }
                final int runLen = end - start + 1;
                // Allow single-pair runs when one of the indices touches an endpoint (useful for T-like junctions)
                final List<PairIdx> window = pairs.subList(start, end + 1);
                final boolean endpointExemption = (runLen == 1) && (
                        window.getFirst().iA == 0 || window.getFirst().iB == 0 ||
                                window.getFirst().iA == (A.size() - 1) || window.getFirst().iB == (B.size() - 1));
                if (runLen >= cfg.minRunNodes || endpointExemption) {
                    final Optional<CrossoverEvent> ev = verifyWindow(window, A, B, cfg);
                    ev.ifPresent(events::add);
                }
                start = end + 1;
            }
        }

        // Merge nearby events (simple clustering within proximity radius), then post‑filter
        final List<CrossoverEvent> merged = mergeNearby(events, cfg.proximity);
        return filterByNodeWitness(merged, grid, cfg);
    }

    private static Optional<CrossoverEvent> verifyWindow(final List<PairIdx> w, final Path A, final Path B, final Config cfg) {
        if (w.isEmpty()) return Optional.empty();
        final int iA0 = w.getFirst().iA, iA1 = w.getLast().iA;
        final int iB0 = w.getFirst().iB, iB1 = w.getLast().iB;

        final List<Double> dists = new ArrayList<>();
        final List<Double> angles = new ArrayList<>();
        final List<Vector3d> midpoints = new ArrayList<>();

        // Guard: paths must have at least one segment
        if (A.size() < 2 || B.size() < 2) return Optional.empty();

        // Clamp window to valid segment index range [0 .. size-2]
        final int aStart = Math.max(0, iA0);
        final int aEnd   = Math.min(iA1, A.size() - 2);
        final int bStart = Math.max(0, iB0);
        final int bEnd   = Math.min(iB1, B.size() - 2);
        if (aStart > aEnd || bStart > bEnd) return Optional.empty();

        for (int ia = aStart; ia <= aEnd; ia++) {
            final Segment3D sA = seg(A, ia);
            final Vector3d tA = tangent(sA);
            for (int ib = bStart; ib <= bEnd; ib++) {
                final Segment3D sB = seg(B, ib);
                final Vector3d tB = tangent(sB);
                final ClosestResult cr = segSegClosest(sA, sB);
                dists.add(cr.dist);
                angles.add(angleDeg(tA, tB));
                midpoints.add(mid(cr.p, cr.q));
            }
        }
        if (dists.isEmpty()) return Optional.empty();

        // Topological veto: if one path is a direct child of the other, treat it as a true junction
        if (!cfg.includeDirectChildren) {
            final List<Path> aChildren = A.getChildren();
            final List<Path> bChildren = B.getChildren();
            if ((aChildren != null && aChildren.contains(B)) || (bChildren != null && bChildren.contains(A))) {
                return Optional.empty();
            }
        }

        final double medDist = median(dists);
        final double medAng = median(angles);
        if (cfg.thetaMinDeg > 0 && medAng < cfg.thetaMinDeg) return Optional.empty();
        final Vector3d c = mean(midpoints);

        final Set<Path> participants = new LinkedHashSet<>(Arrays.asList(A, B));
        final Map<Path, IntSummaryStatistics> idxWin = new LinkedHashMap<>();
        idxWin.put(A, new IntSummaryStatistics());
        idxWin.put(B, new IntSummaryStatistics());
        for (PairIdx p : w) {
            idxWin.get(A).accept(p.iA);
            idxWin.get(B).accept(p.iB);
        }
        return Optional.of(new CrossoverEvent(c.x, c.y, c.z, medDist, medAng, participants, idxWin));
    }

    /**
     * Post‑hoc filter: keep an event only if at least one participant path has a
     * real node within a small radius of the event center.
     */
    private static List<CrossoverEvent> filterByNodeWitness(final List<CrossoverEvent> in,
                                                            final HashGrid3D<NodeRef> grid,
                                                            final Config cfg) {
        if (in.isEmpty()) return in;
        final double r = (cfg.nodeWitnessRadius > 0) ? cfg.nodeWitnessRadius : cfg.proximity;
        final double r2 = r * r;
        final List<CrossoverEvent> out = new ArrayList<>(in.size());
        for (CrossoverEvent ev : in) {
            final Vector3d c = new Vector3d(ev.x, ev.y, ev.z);
            boolean witnessed = false;
            for (NodeRef nr : grid.queryNeighborhood(c)) {
                if (nr.isMid()) continue; // only real nodes count as witnesses
                if (!ev.participants.contains(nr.path)) continue; // only participants may witness
                if (dist2(nr.pos, c) <= r2) { witnessed = true; break; }
            }
            if (witnessed) out.add(ev);
        }
        return out;
    }

    private static List<CrossoverEvent> mergeNearby(final List<CrossoverEvent> in, final double r) {
        final double r2 = r * r;
        final List<CrossoverEvent> out = new ArrayList<>();
        for (CrossoverEvent ev : in) {
            boolean merged = false;
            for (int i = 0; i < out.size(); i++) {
                final CrossoverEvent o = out.get(i);
                final double dx = ev.x - o.x, dy = ev.y - o.y, dz = ev.z - o.z;
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    // merge: average center, union participants, combine stats (keep min dist median)
                    final double nx = (ev.x + o.x) * 0.5;
                    final double ny = (ev.y + o.y) * 0.5;
                    final double nz = (ev.z + o.z) * 0.5;
                    final Set<Path> parts = new LinkedHashSet<>(o.participants);
                    parts.addAll(ev.participants);
                    final Map<Path, IntSummaryStatistics> idx = new LinkedHashMap<>(o.indexWindow);
                    ev.indexWindow.forEach((k, v) -> idx.merge(k, v, (a, b) -> {
                        final IntSummaryStatistics s = new IntSummaryStatistics();
                        s.combine(a);
                        s.combine(b);
                        return s;
                    }));
                    out.set(i, new CrossoverEvent(nx, ny, nz,
                            Math.min(ev.medianMinDist, o.medianMinDist),
                            (ev.medianAngleDeg + o.medianAngleDeg) * 0.5, parts, idx));
                    merged = true;
                    break;
                }
            }
            if (!merged) out.add(ev);
        }
        return out;
    }

    // helpers ---------------------------------------------------------------

    private static Segment3D seg(final Path p, final int i) {
        final Vector3d a = toVec(p.getNode(i));
        final Vector3d b = toVec(p.getNode(i + 1));
        return new Segment3D(a, b);
    }

    private static Vector3d toVec(final PointInImage n) {
        return new Vector3d(n.x, n.y, n.z);
    }

    /** Robust angle between vectors in <b>radians</b>. */
    private static double angleBetween(final Vector3d u, final Vector3d v) {
        final double ul = u.length();
        final double vl = v.length();
        if (ul == 0.0 || vl == 0.0) return 0.0;
        double c = (u.x * v.x + u.y * v.y + u.z * v.z) / (ul * vl);
        c = Math.max(-1.0, Math.min(1.0, c));
        return Math.acos(c);
    }

    private static double dist2(final Vector3d a, final Vector3d b) {
        final double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx*dx + dy*dy + dz*dz;
    }

    private static Vector3d tangent(final Segment3D s) {
        final Vector3d t = new Vector3d(s.b);
        t.sub(s.a);
        final double n = t.length();
        if (n <= 1e-12) return new Vector3d(0,0,0);
        t.scale(1.0 / n);
        return t;
    }

    private static double angleDeg(final Vector3d u, final Vector3d v) {
        double dot = Math.abs(u.dot(v));
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private static Vector3d mid(final Vector3d p, final Vector3d q) {
        return new Vector3d(0.5*(p.x+q.x), 0.5*(p.y+q.y), 0.5*(p.z+q.z));
    }

    private static double median(final List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        final double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        java.util.Arrays.sort(a);
        final int m = a.length / 2;
        return (a.length % 2 == 0) ? 0.5 * (a[m - 1] + a[m]) : a[m];
    }

    private static Vector3d mean(final List<Vector3d> pts) {
        double sx=0, sy=0, sz=0; int n=0; for (Vector3d p: pts) { sx+=p.x; sy+=p.y; sz+=p.z; n++; }
        return (n==0) ? new Vector3d(0,0,0) : new Vector3d(sx/n, sy/n, sz/n);
    }

    /** Remove consecutive duplicates with the same (iA,iB) after sorting. */
    private static void dedupeInPlaceByIndices(final List<PairIdx> pairs) {
        if (pairs.isEmpty()) return;
        int w = 1; // write index
        PairIdx prev = pairs.getFirst();
        for (int r = 1; r < pairs.size(); r++) {
            final PairIdx cur = pairs.get(r);
            if (cur.iA != prev.iA || cur.iB != prev.iB) {
                pairs.set(w++, cur);
                prev = cur;
            }
        }
        if (w < pairs.size()) pairs.subList(w, pairs.size()).clear();
    }

    private static ClosestResult segSegClosest(final Segment3D s1, final Segment3D s2) {
        // Robust, clamped closest approach between two 3D segments
        final Vector3d p = s1.a;
        final Vector3d q = s2.a;
        final Vector3d d1 = new Vector3d(s1.b); d1.sub(s1.a);
        final Vector3d d2 = new Vector3d(s2.b); d2.sub(s2.a);
        final Vector3d r  = new Vector3d(p);    r.sub(q);
        final double a = d1.dot(d1);
        final double e = d2.dot(d2);
        final double f = d2.dot(r);

        double s, t;
        if (a <= 1e-18 && e <= 1e-18) { // both degenerate
            return new ClosestResult(new Vector3d(p), new Vector3d(q), Math.sqrt(dist2(p,q)));
        }
        if (a <= 1e-18) { // s1 degenerate
            s = 0;
            t = clamp(f / e, 0, 1);
        } else {
            final double c = d1.dot(r);
            if (e <= 1e-18) { // s2 degenerate
                t = 0;
                s = clamp(-c / a, 0, 1);
            } else {
                final double b = d1.dot(d2);
                final double denom = a*e - b*b;
                if (denom != 0) s = clamp((b*f - c*e) / denom, 0, 1); else s = 0;
                t = clamp((b*s + f) / e, 0, 1);
                // reproject s with clamped t
                if (t == 0 || t == 1) s = clamp(-(c + b*t) / a, 0, 1);
            }
        }
        final Vector3d cp1 = new Vector3d(d1); cp1.scale(s); cp1.add(p);
        final Vector3d cp2 = new Vector3d(d2); cp2.scale(t); cp2.add(q);
        return new ClosestResult(cp1, cp2, Math.sqrt(dist2(cp1, cp2)));
    }

    private static double clamp(final double x, final double lo, final double hi) {
        return (x < lo ? lo : Math.min(x, hi));
    }

    /**
     * Immutable configuration with builder-like setters.
     */
    public static final class Config {

        /**
         * Neighborhood radius used for coarse candidate mining and event merging,
         * expressed in real‑world units (e.g., micrometers). This distance is used to
         * (1) query the uniform hash grid for near neighbors and (2) merge nearby
         * crossover events. Larger values increase sensitivity but may yield more
         * candidates/spurious pairs.
         * <p>Default: {@code 2.0}.</p>
         */
        double proximity = 2.0;

        /**
         * Minimum crossing angle in <b>degrees</b> required for an event to be accepted.
         * The angle is orientation‑invariant (0–90°) computed from local segment tangents.
         * Set to {@code 0} to disable angle filtering.
         * <p>Default: {@code 0.0} (disabled).</p>
         */
        double thetaMinDeg = 0.0;

        /**
         * Minimum number of consecutive near node‑pairs (monotonic in both paths)
         * required to form a candidate window. Higher values increase robustness by
         * requiring a short “track” of proximity. Note: single‑pair windows may still
         * be considered when an endpoint exemption applies.
         * <p>Default: {@code 2}.</p>
         */
        int minRunNodes = 2;

        /**
         * If {@code true}, only compare paths that share the same channel and time (C/T)
         * indices. Set to {@code false} to allow cross‑channel or cross‑time comparisons.
         * <p>Default: {@code true}.</p>
         */
        boolean sameCTOnly = true;

        /**
         * If {@code true}, report crossovers within the same path (self‑proximity).
         * If {@code false}, self pairs are ignored. Typically left {@code false}.
         * <p>Default: {@code false}.</p>
         */
        boolean includeSelfCrossovers = false;

        /**
         * If {@code true}, report crossovers with a path's direct child
         * If {@code false}, child pairs are ignored.
         * <p>Default: {@code false}.</p>
         */
        boolean includeDirectChildren = false;

        /**
         * Radius (in real‑world units) used by the post‑hoc witness filter. If {@code <= 0},
         * the filter uses {@link #proximity}. An event is kept only if at least one
         * <em>participant</em> path has a real node (not a midpoint) within this radius
         * of the event center.
         * <p>Default: {@code -1.0} (fallback to {@link #proximity}).</p>
         */
        public double nodeWitnessRadius = -1.0;

        /**
         * Sets {@link #proximity}.
         *
         * @param v neighborhood radius in real‑world units; values {@code < 0} are clamped to {@code 0}
         * @return this config (for chaining)
         */
        public Config proximity(final double v) {
            this.proximity = Math.max(0, v);
            return this;
        }

        /**
         * Sets {@link #thetaMinDeg}.
         *
         * @param v minimum crossing angle in degrees; {@code < 0} is clamped to {@code 0} (disables filtering)
         * @return this config (for chaining)
         */
        public Config thetaMinDeg(final double v) {
            this.thetaMinDeg = Math.max(0, v);
            return this;
        }

        /**
         * Sets {@link #minRunNodes}.
         *
         * @param n minimum run length (consecutive near node‑pairs); values {@code < 1} are clamped to {@code 1}
         * @return this config (for chaining)
         */
        public Config minRunNodes(final int n) {
            this.minRunNodes = Math.max(1, n);
            return this;
        }

        /**
         * Sets {@link #sameCTOnly}.
         *
         * @param b whether to restrict comparisons to paths with the same C/T indices
         * @return this config (for chaining)
         */
        public Config sameCTOnly(final boolean b) {
            this.sameCTOnly = b;
            return this;
        }

        /**
         * Sets {@link #includeSelfCrossovers}.
         *
         * @param b whether to include crossovers within the same path
         * @return this config (for chaining)
         */
        public Config includeSelfCrossovers(final boolean b) {
            this.includeSelfCrossovers = b;
            return this;
        }

        /**
         * Sets {@link #includeDirectChildren}.
         *
         * @param b whether to include crossovers with the path's direct child
         * @return this config (for chaining)
         */
        public Config includeDirectChildren(final boolean b) {
            this.includeDirectChildren = b;
            return this;
        }

        /**
         * Sets {@link #nodeWitnessRadius}.
         *
         * @param v witness radius in real‑world units; if {@code <= 0}, the post‑hoc filter falls back to {@link #proximity}
         * @return this config (for chaining)
         */
        public Config nodeWitnessRadius(final double v) {
            this.nodeWitnessRadius = v;
            return this;
        }

        @Override
        public String toString() {
            return "Config{proximity=" + proximity
                    + ", minRunNodes=" + minRunNodes
                    + ", includeSelfCrossovers=" + includeSelfCrossovers
                    + ", thetaMinDeg=" + thetaMinDeg
                    + ", sameCTOnly=" + sameCTOnly
                    + ", nodeWitnessRadius=" + nodeWitnessRadius + "}";
        }
    }

    /** One detected crossover event. */
    public static final class CrossoverEvent {

        /** X-coordinate of crossover center in real world units */
        public final double x;
        /** Y-coordinate of crossover center in real world units */
        public final double y;
        /** Z-coordinate of crossover center in real world units */
        public final double z;
        /** Unique paths taking part in the crossover */
        public final Set<Path> participants;
        /** Median crossing angle between local tangents */
        public final double medianAngleDeg;
        /** Median segment-to-segment closest distance (real world units) */
        public final double medianMinDist;
        /**  Min/max of node indices per path near the event */
        public final Map<Path, IntSummaryStatistics> indexWindow; //

         private CrossoverEvent(final double x, final double y, final double z,
                              final double medianMinDist, final double medianAngleDeg,
                              final Set<Path> participants,
                              final Map<Path, IntSummaryStatistics> indexWindow) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.medianMinDist = medianMinDist;
            this.medianAngleDeg = medianAngleDeg;
            this.participants = Collections.unmodifiableSet(new LinkedHashSet<>(participants));
            this.indexWindow = Collections.unmodifiableMap(new LinkedHashMap<>(indexWindow));
        }

        /**
         * The XYZCT locations (ImagePlus nomenclature with ZCT 1-based indices) of the event center.
         * If {@link #participants} are associated with multiple channels/frames, their mean position is retrieved.
         */
        public double[] xyzct() {
            double sumRawX = 0, sumRawY = 0, sumRawZ = 0, sumC = 0, sumT = 0;
            for (final Path participant : participants) {
                final Calibration cal = participant.getCalibration();
                sumRawX += cal.getRawX(x) + participant.getCanvasOffset().x;
                sumRawY += cal.getRawY(y) + participant.getCanvasOffset().y;
                sumRawZ += cal.getRawZ(z) + participant.getCanvasOffset().z;
                sumC += participant.getChannel();
                sumT += participant.getFrame();
            }
            final int n = participants.size();
            return new double[]{sumRawX / n, sumRawY / n, sumRawZ / n, sumC / n, sumT / n};
        }

        @Override
        public String toString() {
            return String.format("Crossover[x=%.3f,y=%.3f,z=%.3f; r~%.3fum; angle~%.1f°; paths=%d]",
                    x, y, z, medianMinDist, medianAngleDeg, participants.size());
        }

    }

    // --- small value types --------------------------------------------------------------------

    private record Segment3D(Vector3d a, Vector3d b) {}
    private record ClosestResult(Vector3d p, Vector3d q, double dist) {}
    private record NodeRef(Vector3d pos, Path path, int idx, int c, int t, boolean isMid) {}

    private static final class PairIdx {
        final int iA, iB;

        PairIdx(NodeRef a, NodeRef b) {
            this.iA = a.idx;
            this.iB = b.idx;
        }
    }

    private record PairKey(Path a, Path b) {

        static PairKey of(Path p, Path q) {
            return (System.identityHashCode(p) <= System.identityHashCode(q)) ? new PairKey(p, q) : new PairKey(q, p);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PairKey k)) return false;
            return a == k.a && b == k.b;
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }
    }

    /**
     * Simple 3D hash grid for near-neighbor queries.
     */
    private static final class HashGrid3D<T> {
        private final double h; // cell size
        private final Map<Cell, List<T>> map = new HashMap<>();

        HashGrid3D(final double cellSizeUm) {
            this.h = Math.max(1e-9, cellSizeUm);
        }

        void insert(final Vector3d p, final T payload) {
            map.computeIfAbsent(cell(p), k -> new ArrayList<>()).add(payload);
        }

        Iterable<T> queryNeighborhood(final Vector3d p) {
            final Cell c = cell(p);
            final List<T> out = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        final Cell n = new Cell(c.ix + dx, c.iy + dy, c.iz + dz);
                        final List<T> bucket = map.get(n);
                        if (bucket != null) out.addAll(bucket);
                    }
            return out;
        }

        private Cell cell(final Vector3d p) {
            return new Cell((int) Math.floor(p.x / h), (int) Math.floor(p.y / h), (int) Math.floor(p.z / h));
        }

        private record Cell(int ix, int iy, int iz) {

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Cell c)) return false;
                return ix == c.ix && iy == c.iy && iz == c.iz;
            }

        }
    }
}