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

package sc.fiji.snt.analysis.curation;

import sc.fiji.snt.Path;
import sc.fiji.snt.util.CrossoverFinder;
import sc.fiji.snt.util.PointInImage;

import java.util.*;

/**
 * Framework for evaluating the morphological plausibility of path operations
 * and reconstructions. Checks come in two tiers:
 * <ul>
 *   <li>{@link LiveCheck}: lightweight checks that run inline during
 *       interactive tracing (at fork, segment, and edit hook points).</li>
 *   <li>{@link DeepCheck}: heavier checks that scan an entire reconstruction
 *       on demand (e.g., overlap detection, global radius analysis).</li>
 * </ul>
 * Both tiers share the same {@link Warning} type and severity model.
 *
 * @author Tiago Ferreira
 */
public final class PlausibilityCheck {

    private PlausibilityCheck() {} // static utility class

    /** Severity levels for plausibility warnings. */
    public enum Severity {
        /** Informational: the operation is unusual but not necessarily wrong. */
        INFO,
        /** Warning: the operation is suspect and likely an error. */
        WARNING,
        /** Error: the operation is almost certainly wrong. */
        ERROR
    }

    /**
     * A plausibility warning produced by a check.
     *
     * @param checkName     the name of the check that produced this warning
     * @param severity      the severity level
     * @param message       human-readable description of the issue
     * @param location      the spatial location of the issue (may be {@code null})
     * @param affectedPaths the paths involved in this warning (never {@code null}, may be empty)
     * @param value         the measured value that triggered the warning
     * @param threshold     the threshold that was exceeded
     */
    public record Warning(
            String checkName,
            Severity severity,
            String message,
            PointInImage location,
            List<Path> affectedPaths,
            double value,
            double threshold
    ) implements Comparable<Warning> {

        /** Compact constructor: guarantees affectedPaths is never null. */
        public Warning {
            if (affectedPaths == null) affectedPaths = List.of();
        }

        @Override
        public int compareTo(final Warning other) {
            return other.severity.compareTo(this.severity); // higher severity first
        }
    }

    /**
     * A lightweight check that evaluates the plausibility of a single fork
     * operation (parent + child + branch index). Designed to execute in
     * sub-millisecond time so it can run inline at Hook 2 (QUERY_KEEP).
     */
    public static abstract class LiveCheck {

        private boolean enabled = true;

        /** @return a short human-readable name for this check */
        public abstract String getName();

        /**
         * Evaluates the plausibility of a child path forking from a parent.
         *
         * @param parent      the parent path
         * @param child       the child (candidate) path
         * @param branchIndex the node index in the parent where the fork occurs
         * @return list of warnings (empty if the operation is plausible)
         */
        public abstract List<Warning> check(Path parent, Path child, int branchIndex);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(final boolean enabled) { this.enabled = enabled; }
    }

    /**
     * A heavier check that scans an entire collection of paths for issues.
     * Triggered explicitly by the user via a "Run Deep Scan" button.
     */
    public static abstract class DeepCheck {

        private boolean enabled = true;

        /** @return a short human-readable name for this check */
        public abstract String getName();

        /**
         * Scans a collection of paths for plausibility issues.
         *
         * @param paths the paths to scan
         * @return list of warnings (empty if no issues found)
         */
        public abstract List<Warning> scan(Collection<Path> paths);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(final boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Warns when the child neurite's radius at the fork point exceeds
     * the parent's radius at the branch point.
     */
    public static class RadiusContinuity extends LiveCheck {

        private double maxRatio = 1.5;

        @Override
        public String getName() { return "Radius continuity"; }

        public void setMaxRatio(final double maxRatio) { this.maxRatio = maxRatio; }
        public double getMaxRatio() { return maxRatio; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (parent == null || child == null || child.size() == 0) return Collections.emptyList();
            if (branchIndex < 0 || branchIndex >= parent.size()) return Collections.emptyList();

            final double parentRadius = parent.getNode(branchIndex).radius;
            if (parentRadius <= 0) return Collections.emptyList();

            // Sample child radius from first few nodes (median of up to 5)
            final int sampleSize = Math.min(5, child.size());
            final List<Double> childRadii = new ArrayList<>(sampleSize);
            for (int i = 0; i < sampleSize; i++) {
                final double r = child.getNode(i).radius;
                if (r > 0) childRadii.add(r);
            }
            if (childRadii.isEmpty()) return Collections.emptyList();
            Collections.sort(childRadii);
            final double childRadius = childRadii.get(childRadii.size() / 2);

            final double ratio = childRadius / parentRadius;
            if (ratio > maxRatio) {
                final Severity sev = ratio > maxRatio * 2 ? Severity.ERROR : Severity.WARNING;
                return List.of(new Warning(getName(), sev,
                        String.format("Radius changes %.1f× at fork (child %.2f, parent %.2f)",
                                ratio, childRadius, parentRadius),
                        parent.getNode(branchIndex), List.of(parent, child), ratio, maxRatio));
            }
            return Collections.emptyList();
        }
    }

    /**
     * Warns when the child path's initial direction deviates sharply from
     * the parent's tangent at the branch point (potential U-turn).
     */
    public static class DirectionContinuity extends LiveCheck {

        private double minAlignmentDeg = 30.0;

        @Override
        public String getName() { return "Direction continuity"; }

        public void setMinAlignmentDeg(final double deg) { this.minAlignmentDeg = deg; }
        public double getMinAlignmentDeg() { return minAlignmentDeg; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (parent == null || child == null) return Collections.emptyList();
            if (child.size() < 2 || parent.size() < 3) return Collections.emptyList();
            if (branchIndex < 0 || branchIndex >= parent.size()) return Collections.emptyList();

            final double[] parentTangent = new double[3];
            parent.getTangent(branchIndex, Math.min(2, branchIndex), parentTangent);
            final double parentMag = mag(parentTangent);
            if (parentMag == 0) return Collections.emptyList();

            final double[] childDir = child.getInitialDirection(5);
            if (childDir == null) return Collections.emptyList();
            final double childMag = mag(childDir);

            final double dot = (parentTangent[0] * childDir[0] +
                    parentTangent[1] * childDir[1] +
                    parentTangent[2] * childDir[2]) / (parentMag * childMag);
            // dot close to +1 means child continues along parent (normal); dot close to -1 means
            // child doubles back (U-turn). Convert to a deviation angle: 0° = same direction,
            // 180° = perfect reversal
            final double angleDeg = Math.toDegrees(Math.acos(Math.clamp(dot, -1.0, 1.0)));
            // Flag when the deviation from the parent direction exceeds the maximum allowed: i.e.
            // the child bends back too sharply
            final double deviationFromParent = 180.0 - angleDeg;

            if (deviationFromParent < minAlignmentDeg) {
                final Severity sev = deviationFromParent < minAlignmentDeg / 2 ? Severity.ERROR : Severity.WARNING;
                return List.of(new Warning(getName(), sev,
                        String.format("Possible U-turn at fork: only %.1f° from reversal (min %.1f°)",
                                deviationFromParent, minAlignmentDeg),
                        parent.getNode(branchIndex), List.of(parent, child), deviationFromParent, minAlignmentDeg));
            }
            return Collections.emptyList();
        }
    }

    /**
     * Warns when the bifurcation angle at a fork is outside a plausible range.
     */
    public static class BranchAngle extends LiveCheck {

        private double minAngleDeg = 10.0;
        private double maxAngleDeg = 170.0;

        @Override
        public String getName() { return "Branch angle"; }

        public void setMinAngleDeg(final double deg) { this.minAngleDeg = deg; }
        public double getMinAngleDeg() { return minAngleDeg; }
        public void setMaxAngleDeg(final double deg) { this.maxAngleDeg = deg; }
        public double getMaxAngleDeg() { return maxAngleDeg; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (parent == null || child == null) return Collections.emptyList();
            if (child.size() < 2) return Collections.emptyList();
            if (branchIndex < 0 || branchIndex >= parent.size()) return Collections.emptyList();

            final PointInImage branchNode = parent.getNode(branchIndex);
            final double[] parentCont;
            if (branchIndex < parent.size() - 1) {
                final PointInImage next = parent.getNode(branchIndex + 1);
                parentCont = new double[]{next.x - branchNode.x, next.y - branchNode.y, next.z - branchNode.z};
            } else if (branchIndex > 0) {
                final PointInImage prev = parent.getNode(branchIndex - 1);
                parentCont = new double[]{branchNode.x - prev.x, branchNode.y - prev.y, branchNode.z - prev.z};
            } else {
                return Collections.emptyList();
            }

            final int childEnd = Math.min(5, child.size() - 1);
            final double[] childDir = {
                    child.getNode(childEnd).x - child.getNode(0).x,
                    child.getNode(childEnd).y - child.getNode(0).y,
                    child.getNode(childEnd).z - child.getNode(0).z
            };

            final double m1 = mag(parentCont), m2 = mag(childDir);
            if (m1 == 0 || m2 == 0) return Collections.emptyList();

            final double dot = (parentCont[0] * childDir[0] + parentCont[1] * childDir[1] +
                    parentCont[2] * childDir[2]) / (m1 * m2);
            final double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

            final List<Path> affected = List.of(parent, child);
            final List<Warning> warnings = new ArrayList<>();
            if (angleDeg < minAngleDeg) {
                warnings.add(new Warning(getName(), Severity.WARNING,
                        String.format("Fork angle too narrow: %.1f° (min %.1f°)",
                                angleDeg, minAngleDeg),
                        branchNode, affected, angleDeg, minAngleDeg));
            } else if (angleDeg > maxAngleDeg) {
                warnings.add(new Warning(getName(), Severity.WARNING,
                        String.format("Fork angle too wide: %.1f° (max %.1f°)",
                                angleDeg, maxAngleDeg),
                        branchNode, affected, angleDeg, maxAngleDeg));
            }
            return warnings;
        }
    }

    /** Warns when child and parent have very different tortuosity (contraction). */
    public static class TortuosityConsistency extends LiveCheck {

        private double maxContractionDiff = 0.3;

        @Override
        public String getName() { return "Tortuosity consistency"; }

        public void setMaxContractionDiff(final double diff) { this.maxContractionDiff = diff; }
        public double getMaxContractionDiff() { return maxContractionDiff; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (parent == null || child == null) return Collections.emptyList();
            if (child.size() < 5 || parent.size() < 5) return Collections.emptyList();
            final double cc = child.getContraction(), pc = parent.getContraction();
            if (Double.isNaN(cc) || Double.isNaN(pc)) return Collections.emptyList();
            final double diff = Math.abs(cc - pc);
            if (diff > maxContractionDiff) {
                return List.of(new Warning(getName(), Severity.INFO,
                        String.format("Tortuosity mismatch at fork: child %.2f vs parent %.2f (diff %.2f)",
                                cc, pc, diff),
                        parent.getNode(branchIndex), List.of(parent, child), diff, maxContractionDiff));
            }
            return Collections.emptyList();
        }
    }

    /** Warns when a primary path starts far from any soma node. */
    public static class SomaDistance extends LiveCheck {

        private double maxDistUm = 500.0;

        @Override
        public String getName() { return "Soma distance"; }

        public void setMaxDistUm(final double dist) { this.maxDistUm = dist; }
        public double getMaxDistUm() { return maxDistUm; }

        /**
         * For this check, {@code parent} is ignored: it evaluates whether the
         * child's first node is within range of any soma path. The soma paths
         * must be supplied by the monitor via context.
         */
        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            // This check is handled specially by PlausibilityMonitor which
            // supplies soma locations. The default implementation is a no-op;
            // the actual distance computation happens in the monitor.
            return Collections.emptyList();
        }

        /**
         * Evaluates distance from a point to the nearest soma location.
         *
         * @param start     the start point of the new path
         * @param somaNodes the soma node locations (may be empty)
         * @return list of warnings (empty if within range or no soma exists)
         */
        public List<Warning> checkDistance(final PointInImage start, final List<PointInImage> somaNodes) {
            if (start == null || somaNodes == null || somaNodes.isEmpty()) return Collections.emptyList();
            double minDist = Double.MAX_VALUE;
            for (final PointInImage soma : somaNodes) {
                final double d = start.distanceTo(soma);
                if (d < minDist) minDist = d;
            }
            if (minDist > maxDistUm) {
                final Severity sev = minDist > maxDistUm * 3 ? Severity.ERROR : Severity.WARNING;
                return List.of(new Warning(getName(), sev,
                        String.format("%.0f \u00b5m from nearest soma (max %.0f \u00b5m)",
                                minDist, maxDistUm),
                        start, List.of(), minDist, maxDistUm));
            }
            return Collections.emptyList();
        }
    }

    /** Warns when a path has radii assigned but they are all identical (likely unfitted defaults). */
    public static class ConstantRadii extends LiveCheck {

        @Override
        public String getName() { return "Constant radii"; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (child == null || child.size() < 3 || !child.hasRadii()) return Collections.emptyList();
            final double first = child.getNode(0).radius;
            if (first <= 0) return Collections.emptyList();
            boolean allSame = true;
            for (int i = 1; i < child.size(); i++) {
                if (Math.abs(child.getNode(i).radius - first) > 1e-6) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                return List.of(new Warning(getName(), Severity.INFO,
                        String.format("Uniform radii (%.2f): likely unfitted", first),
                        child.getNode(0), List.of(child), first, 0));
            }
            return Collections.emptyList();
        }
    }

    /** Warns when a terminal branch is suspiciously short. */
    public static class TerminalBranchLength extends LiveCheck {

        private double minLengthUm = 2.0;

        @Override
        public String getName() { return "Terminal branch length"; }

        public void setMinLengthUm(final double len) { this.minLengthUm = len; }
        public double getMinLengthUm() { return minLengthUm; }

        @Override
        public List<Warning> check(final Path parent, final Path child, final int branchIndex) {
            if (child == null || child.size() < 2) return Collections.emptyList();
            // Only flag terminal paths (no children)
            if (child.getChildren() != null && !child.getChildren().isEmpty()) return Collections.emptyList();
            final double length = child.getLength();
            if (length < minLengthUm) {
                return List.of(new Warning(getName(), Severity.INFO,
                        String.format("Terminal branch too short: %.2f \u00b5m (min %.2f \u00b5m)",
                                length, minLengthUm),
                        child.getNode(0), List.of(child), length, minLengthUm));
            }
            return Collections.emptyList();
        }
    }

    /**
     * Detects paths that run very close to each other without being
     * topologically connected. Wraps {@link CrossoverFinder}.
     */
    public static class PathOverlap extends DeepCheck {

        private double proximityUm = 2.0;

        @Override
        public String getName() { return "Path overlap"; }

        public void setProximityUm(final double um) { this.proximityUm = um; }
        public double getProximityUm() { return proximityUm; }

        @Override
        public List<Warning> scan(final Collection<Path> paths) {
            if (paths == null || paths.size() < 2) return Collections.emptyList();
            final CrossoverFinder.Config cfg = new CrossoverFinder.Config()
                    .proximity(proximityUm)
                    .includeSelfCrossovers(false)
                    .includeDirectChildren(false)
                    .minRunNodes(2);
            final List<CrossoverFinder.CrossoverEvent> events = CrossoverFinder.find(paths, cfg);
            final List<Warning> warnings = new ArrayList<>();
            for (final CrossoverFinder.CrossoverEvent ev : events) {
                warnings.add(new Warning(getName(), Severity.WARNING,
                        String.format("Cross-over detected: %.1f \u00b5m apart, %.0f° angle",
                                ev.medianMinDist, ev.medianAngleDeg),
                        new PointInImage(ev.x, ev.y, ev.z),
                        new ArrayList<>(ev.participants),
                        ev.medianMinDist, proximityUm));
            }
            return warnings;
        }
    }

    /**
     * Detects abrupt radius jumps between adjacent nodes within a path.
     */
    public static class RadiusJumps extends DeepCheck {

        private double maxJumpRatio = 3.0;

        @Override
        public String getName() { return "Radius jumps"; }

        public void setMaxJumpRatio(final double ratio) { this.maxJumpRatio = ratio; }
        public double getMaxJumpRatio() { return maxJumpRatio; }

        @Override
        public List<Warning> scan(final Collection<Path> paths) {
            if (paths == null) return Collections.emptyList();
            final List<Warning> warnings = new ArrayList<>();
            for (final Path path : paths) {
                if (path == null || path.size() < 2 || !path.hasRadii()) continue;
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 <= 0 || r2 <= 0) continue;
                    final double ratio = Math.max(r1, r2) / Math.min(r1, r2);
                    if (ratio > maxJumpRatio) {
                        warnings.add(new Warning(getName(), Severity.WARNING,
                                String.format("Abrupt radius change: %.1f× (%.2f \u2192 %.2f) in \"%s\"",
                                        ratio, r1, r2, path.getName()),
                                path.getNode(i + 1), List.of(path), ratio, maxJumpRatio));
                    }
                }
            }
            return warnings;
        }
    }

    /**
     * Detects sustained radius increases along a path (neurites should
     * generally taper distally).
     */
    public static class RadiusMonotonicity extends DeepCheck {

        private int minIncreasingRun = 10;

        @Override
        public String getName() { return "Radius monotonicity"; }

        public void setMinIncreasingRun(final int n) { this.minIncreasingRun = n; }
        public int getMinIncreasingRun() { return minIncreasingRun; }

        @Override
        public List<Warning> scan(final Collection<Path> paths) {
            if (paths == null) return Collections.emptyList();
            final List<Warning> warnings = new ArrayList<>();
            for (final Path path : paths) {
                if (path == null || path.size() < minIncreasingRun || !path.hasRadii()) continue;
                int runStart = -1;
                int runLength = 0;
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 > 0 && r2 > 0 && r2 > r1) {
                        if (runLength == 0) runStart = i;
                        runLength++;
                    } else {
                        if (runLength >= minIncreasingRun) {
                            final double startR = path.getNode(runStart).radius;
                            final double endR = path.getNode(runStart + runLength).radius;
                            warnings.add(new Warning(getName(), Severity.INFO,
                                    String.format("Radius inversion: increases over %d nodes (%.2f \u2192 %.2f) in \"%s\"",
                                            runLength, startR, endR, path.getName()),
                                    path.getNode(runStart), List.of(path), runLength, minIncreasingRun));
                        }
                        runLength = 0;
                    }
                }
                // Check final run
                if (runLength >= minIncreasingRun) {
                    final double startR = path.getNode(runStart).radius;
                    final double endR = path.getNode(runStart + runLength).radius;
                    warnings.add(new Warning(getName(), Severity.INFO,
                            String.format("Radius inversion: increases over %d nodes (%.2f \u2192 %.2f) in \"%s\"",
                                    runLength, startR, endR, path.getName()),
                            path.getNode(runStart), List.of(path), runLength, minIncreasingRun));
                }
            }
            return warnings;
        }
    }

    static double mag(final double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
