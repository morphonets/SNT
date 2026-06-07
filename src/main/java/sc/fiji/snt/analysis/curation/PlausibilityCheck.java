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

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.ProfileProcessor;
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
     * Classifies how an error propagates through the reconstruction. Used to pick the right denominator/numerator pair
     * when computing a warning's impact:
     *   - {@code SUBTREE}: errors at this point cascade to every downstream path. Impact = (length of the subtree
     *   rooted at the affected path) / (total tree length). Use for topology checks where the affected path's parent
     *   assignment, direction, or origin is suspect.
     *   - {@code LOCAL}: errors stay within the affected path itself.
     *   Impact = (affected path length) / (total tree length). Use for caliber, terminal-length, and image-signal checks.
     *   - {@code NONE}: no meaningful impact metric; the warning carries {@code NaN} as its impact value.
     */
    public enum ImpactKind { SUBTREE, LOCAL, NONE }

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
     * @param impact        normalized fraction in {@code [0, 1]} indicating what portion of the reconstruction is
     *                      affected if this warning is a true error. {@link Double#NaN}  when not yet computed.
     *                      See {@link ImpactKind}.
     */
    public record Warning(
            String checkName,
            Severity severity,
            String message,
            PointInImage location,
            List<Path> affectedPaths,
            double value,
            double threshold,
            double impact
    ) implements Comparable<Warning> {

        /** Compact constructor: guarantees affectedPaths is never null. */
        public Warning {
            if (affectedPaths == null) affectedPaths = List.of();
        }

        /**
         * Convenience constructor for callers that don't yet know the impact (filled in later by {@code PlausibilityMonitor}).
         */
        public Warning(final String checkName, final Severity severity, final String message,
                       final PointInImage location, final List<Path> affectedPaths,
                       final double value, final double threshold) {
            this(checkName, severity, message, location, affectedPaths, value, threshold, Double.NaN);
        }

        /** Returns a copy of this warning with {@link #impact} replaced. */
        public Warning withImpact(final double newImpact) {
            return new Warning(checkName, severity, message, location, affectedPaths,
                    value, threshold, newImpact);
        }

        @Override
        public int compareTo(final Warning other) {
            return other.severity.compareTo(this.severity); // higher severity first
        }
    }

    /**
     * Full distribution of a check's raw measurements across a reconstruction, collected without applying the
     * warning threshold. Powers the per-check histogram popups in the Curation Assistant: showing the user where their
     * data sits relative to the configured threshold is more informative than
     * showing only the flagged subset.
     *
     * @param values        the raw metric values (one per fork, node-pair, run,  or path depending on the check).
     *                      Never {@code null}.
     * @param notAssessable the number of candidate units (forks, paths, etc.) that could not be evaluated (too few
     *                      nodes, missing radii, missing image, etc.).
     * @param metric        short human-readable name of the metric used as the  histogram's x-axis label.
     * @param unit          unit string ({@code "µm"}, {@code "ratio"}, or {@code ""} when dimensionless).
     * @param subject       singular noun describing what each value counts ({@code "fork"}, {@code "path"},
     *                      {@code "cross-over"}, {@code "node pair"},  {@code "monotonic run"}, etc.).
     * @param domainMin     optional lower bound of the metric's natural domain. {@link Double#NaN} when unknown.
     * @param domainMax     optional upper bound of the metric's natural domain. {@link Double#NaN} when unknown.
     * @param emptyHint     optional check-specific message shown to the user when the result is empty or unassessable
     */
    public record Measurements(double[] values, int notAssessable, String metric, String unit, String subject,
                               double domainMin, double domainMax, String emptyHint) {

        /**
         * Sentinel returned when no measurements can be produced.
         */
        public static final Measurements EMPTY = new Measurements(new double[0], 0, "", "", "",
                Double.NaN, Double.NaN, "");

        /**
         * Compact constructor: guarantees non-null arrays and strings.
         */
        public Measurements {
            if (values == null) values = new double[0];
            if (metric == null) metric = "";
            if (unit == null) unit = "";
            if (subject == null) subject = "";
            if (emptyHint == null) emptyHint = "";
            if (notAssessable < 0) notAssessable = 0;
        }

        /**
         * Returns a copy of this record with {@link #emptyHint} replaced. Avoids forcing every {@code measure()}
         * call site to pass a hint through the constructor when only special cases need one.
         */
        public Measurements withHint(final String hint) {
            return new Measurements(values, notAssessable, metric, unit, subject,
                    domainMin, domainMax, hint == null ? "" : hint);
        }

        /**
         * @return {@code true} if no values were collected.
         */
        public boolean isEmpty() {
            return values.length == 0;
        }

        /**
         * @return the count of evaluated units (size of {@link #values}).
         */
        public int assessed() {
            return values.length;
        }

        /**
         * @return the plural form of {@link #subject}.
         */
        String subjectPlural() {
            if (subject == null || subject.isEmpty()) return "";
            if (subject.endsWith("s") || subject.endsWith("x") || subject.endsWith("ch") || subject.endsWith("sh")) {
                return subject + "es";
            }
            return subject + "s";
        }

        /**
         * Convenience formatter for UI footers and warning text.
         */
        String formatCounts() {
            final int n = assessed();
            final String s1 = (n == 1) ? subject : subjectPlural();
            if (subject == null || subject.isEmpty()) {
                return String.format("%d/%d assessed", n, (notAssessable + n));
            }
            return String.format("%d/%d %s assessed", n, (notAssessable + n), s1);
        }
    }

    /**
     * A lightweight check that evaluates the plausibility of a single fork operation (parent + child + branch index).
     * Designed to execute in sub-millisecond time so it can run inline at Hook 2 (QUERY_KEEP).
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

        /**
         * Computes the full distribution of this check's metric across {@code paths}, without applying the warning
         * threshold. Default implementation returns {@link Measurements#EMPTY}; subclasses override to enable
         * per-check histogram popups in the Curation Assistant.
         *
         * @param paths the paths to evaluate; {@code null} or empty yields  {@link Measurements#EMPTY}.
         * @return a {@link Measurements} record; never {@code null}.
         */
        public Measurements measure(final Collection<Path> paths) {
            return Measurements.EMPTY;
        }

        /**
         * Declares how this check's errors propagate. Default {@code SUBTREE}
         * for live checks because most live checks (branch angle, direction,
         * tortuosity, soma distance) catch topology problems whose
         * consequences cascade downstream. Caliber-only checks (e.g.,
         * {@code RadiusContinuity}, {@code ConstantRadii}) override to
         * {@link ImpactKind#LOCAL}.
         */
        public ImpactKind impactKind() { return ImpactKind.SUBTREE; }

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

        /**
         * Computes the full distribution of this check's metric across {@code paths}, without applying the warning
         * threshold. Default implementation returns {@link Measurements#EMPTY}; subclasses override to enable
         * per-check histogram popups in the Curation Assistant.
         *
         * @param paths the paths to evaluate; {@code null} or empty yields {@link Measurements#EMPTY}.
         * @return a {@link Measurements} record; never {@code null}.
         */
        public Measurements measure(final Collection<Path> paths) {
            return Measurements.EMPTY;
        }

        /**
         * Declares how this check's errors propagate. Default {@code LOCAL} for deep checks because most deep checks
         * (radius jumps, monotonicity, signal quality) measure intrinsic per-path properties that don't propagate to
         * descendants. Topology-class checks (e.g., {@code PathOverlap}) override to {@link ImpactKind#SUBTREE}.
         */
        public ImpactKind impactKind() { return ImpactKind.LOCAL; }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Internal helper: packages a {@code List<Double>} as a {@link Measurements} record. Saves every per-check
     * {@code measure()} from copying boilerplate. Domain bounds default to {@link Double#NaN} (unknown).
     */
    private static Measurements toMeasurements(final List<Double> vals, final int notAssessable,
                                               final String metric, final String unit, final String subject) {
        return toMeasurements(vals, notAssessable, metric, unit, subject, Double.NaN, Double.NaN);
    }

    /**
     * Variant of {@link #toMeasurements(List, int, String, String, String)} that also carries the metric's natural
     * domain bounds (used by polar histograms to clamp shading).
     */
    private static Measurements toMeasurements(final List<Double> vals, final int notAssessable,
                                               final String metric, final String unit, final String subject,
                                               final double domainMin, final double domainMax) {
        final double[] arr = new double[vals.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vals.get(i);
        return new Measurements(arr, notAssessable, metric, unit, subject, domainMin, domainMax, "");
    }

    /**
     * Warns when the child neurite's radius at the fork point exceeds
     * the parent's radius at the branch point.
     */
    public static class RadiusContinuity extends LiveCheck {

        private double maxRatio = 1.5;

        @Override
        public String getName() { return "Radius continuity"; }

        @Override
        public ImpactKind impactKind() { return ImpactKind.LOCAL; }

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

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path child : paths) {
                if (child == null) continue;
                final Path parent = child.getParentPath();
                if (parent == null) continue; // primary path: outside this check's domain
                if (child.size() == 0) { skipped++; continue; }
                final int branchIndex = child.getBranchPointIndex();
                if (branchIndex < 0 || branchIndex >= parent.size()) { skipped++; continue; }
                final double parentRadius = parent.getNode(branchIndex).radius;
                if (parentRadius <= 0) { skipped++; continue; }
                final int sampleSize = Math.min(5, child.size());
                final List<Double> childRadii = new ArrayList<>(sampleSize);
                for (int i = 0; i < sampleSize; i++) {
                    final double r = child.getNode(i).radius;
                    if (r > 0) childRadii.add(r);
                }
                if (childRadii.isEmpty()) { skipped++; continue; }
                Collections.sort(childRadii);
                final double childRadius = childRadii.get(childRadii.size() / 2);
                vals.add(childRadius / parentRadius);
            }
            return toMeasurements(vals, skipped, "Radius ratio (child / parent)", "ratio", "fork");
        }
    }

    /**
     * Warns when the child path's initial direction deviates sharply from the parent's tangent at the branch point
     * (potential U-turn).
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

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path child : paths) {
                if (child == null) continue;
                final Path parent = child.getParentPath();
                if (parent == null) continue;
                if (child.size() < 2 || parent.size() < 3) { skipped++; continue; }
                final int branchIndex = child.getBranchPointIndex();
                if (branchIndex < 0 || branchIndex >= parent.size()) { skipped++; continue; }
                final double[] parentTangent = new double[3];
                parent.getTangent(branchIndex, Math.min(2, branchIndex), parentTangent);
                final double parentMag = mag(parentTangent);
                if (parentMag == 0) { skipped++; continue; }
                final double[] childDir = child.getInitialDirection(5);
                if (childDir == null) { skipped++; continue; }
                final double childMag = mag(childDir);
                if (childMag == 0) { skipped++; continue; }
                final double dot = (parentTangent[0] * childDir[0] +
                        parentTangent[1] * childDir[1] +
                        parentTangent[2] * childDir[2]) / (parentMag * childMag);
                final double angleDeg = Math.toDegrees(Math.acos(Math.clamp(dot, -1.0, 1.0)));
                vals.add(180.0 - angleDeg); // deviation from reversal (matches check())
            }
            return toMeasurements(vals, skipped, "Deviation from reversal", "°", "fork", 0.0, 180.0);
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
            final double angleDeg = Math.toDegrees(Math.acos(Math.clamp(dot, -1.0, 1.0)));

            final List<Path> affected = List.of(parent, child);
            final List<Warning> warnings = new ArrayList<>();
            if (angleDeg < minAngleDeg) {
                warnings.add(new Warning(getName(), Severity.WARNING,
                        String.format("Fork angle too narrow: %.1f° (min %.1f°)", angleDeg, minAngleDeg),
                        branchNode, affected, angleDeg, minAngleDeg));
            } else if (angleDeg > maxAngleDeg) {
                warnings.add(new Warning(getName(), Severity.WARNING,
                        String.format("Fork angle too wide: %.1f° (max %.1f°)", angleDeg, maxAngleDeg),
                        branchNode, affected, angleDeg, maxAngleDeg));
            }
            return warnings;
        }

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path child : paths) {
                if (child == null) continue;
                final Path parent = child.getParentPath();
                if (parent == null) continue;
                if (child.size() < 2) { skipped++; continue; }
                final int branchIndex = child.getBranchPointIndex();
                if (branchIndex < 0 || branchIndex >= parent.size()) { skipped++; continue; }
                final PointInImage branchNode = parent.getNode(branchIndex);
                final double[] parentCont;
                if (branchIndex < parent.size() - 1) {
                    final PointInImage next = parent.getNode(branchIndex + 1);
                    parentCont = new double[]{next.x - branchNode.x, next.y - branchNode.y, next.z - branchNode.z};
                } else if (branchIndex > 0) {
                    final PointInImage prev = parent.getNode(branchIndex - 1);
                    parentCont = new double[]{branchNode.x - prev.x, branchNode.y - prev.y, branchNode.z - prev.z};
                } else { skipped++; continue; }
                final int childEnd = Math.min(5, child.size() - 1);
                final double[] childDir = {
                        child.getNode(childEnd).x - child.getNode(0).x,
                        child.getNode(childEnd).y - child.getNode(0).y,
                        child.getNode(childEnd).z - child.getNode(0).z
                };
                final double m1 = mag(parentCont), m2 = mag(childDir);
                if (m1 == 0 || m2 == 0) { skipped++; continue; }
                final double dot = (parentCont[0] * childDir[0] + parentCont[1] * childDir[1] +
                        parentCont[2] * childDir[2]) / (m1 * m2);
                final double angleDeg = Math.toDegrees(Math.acos(Math.clamp(dot, -1.0, 1.0)));
                vals.add(angleDeg);
            }
            return toMeasurements(vals, skipped, "Fork angle", "°", "fork", 0.0, 180.0);
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

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path child : paths) {
                if (child == null) continue;
                final Path parent = child.getParentPath();
                if (parent == null) continue;
                if (child.size() < 5 || parent.size() < 5) { skipped++; continue; }
                final double cc = child.getContraction(), pc = parent.getContraction();
                if (Double.isNaN(cc) || Double.isNaN(pc)) { skipped++; continue; }
                vals.add(Math.abs(cc - pc));
            }
            return toMeasurements(vals, skipped, "Tortuosity mismatch (|child - parent|)", "", "fork", 0.0, 1.0);
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
                        String.format("%.0f µm from nearest soma/root (max %.0f µm)", minDist, maxDistUm),
                        start, List.of(), minDist, maxDistUm));
            }
            return Collections.emptyList();
        }

        /**
         * Measures the start-to-nearest-reference distance for every path's first node. The reference point set is
         * the tree's tagged soma node(s) when present; otherwise we fall back to the tree's root (first node of the
         * primary path).
         */
        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<PointInImage> refs;
            try {
                refs = referencePoints(paths);
            } catch (final Exception e) {
                return Measurements.EMPTY;
            }
            if (refs.isEmpty()) return Measurements.EMPTY; // truly empty tree
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                if (path == null || path.size() == 0) { skipped++; continue; }
                final PointInImage start = path.getNode(0);
                double minDist = Double.MAX_VALUE;
                for (final PointInImage ref : refs) {
                    final double d = start.distanceTo(ref);
                    if (d < minDist) minDist = d;
                }
                if (minDist == Double.MAX_VALUE) { skipped++; continue; }
                vals.add(minDist);
            }
            return toMeasurements(vals, skipped, "Distance to nearest soma/root", "µm", "path");
        }

        /**
         * Returns the soma node(s) for the tree built from {@code paths}, or a single-element list with the tree's
         * root when no soma is tagged. Empty list only when the tree itself has no root (empty Path Manager edge case).
         */
        private static List<PointInImage> referencePoints(final Collection<Path> paths) {
            final Tree tree = new Tree(paths);
            final List<PointInImage> soma = tree.getSomaNodes();
            if (soma != null && !soma.isEmpty()) return soma;
            final PointInImage root = tree.getRoot();
            return (root == null) ? Collections.emptyList() : List.of(root);
        }
    }

    /** Warns when a path has radii assigned, but they are all identical (likely unfitted defaults). */
    public static class ConstantRadii extends LiveCheck {

        @Override
        public String getName() { return "Constant radii"; }

        /**
         * No meaningful impact metric for this check. ConstantRadii is a
         * data-completeness flag, not a morphological one: the path's
         * geometry is fine, the user just hasn't run Fit Volumes (or set
         * radii manually) yet. Reporting an impact fraction would suggest
         * structural consequences that don't apply. Em dash is the honest
         * display.
         */
        @Override
        public ImpactKind impactKind() { return ImpactKind.NONE; }

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

        @Override
        public ImpactKind impactKind() { return ImpactKind.LOCAL; }

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
                        String.format("Terminal branch too short: %.2fµm (min %.2fµm)", length, minLengthUm),
                        child.getNode(0), List.of(child), length, minLengthUm));
            }
            return Collections.emptyList();
        }

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                if (path == null || path.size() < 2) { skipped++; continue; }
                // Domain restricted to terminal paths (no children), mirroring check(). Non-terminal paths are outside
                // this check's domain entirely, so they're filtered (not counted toward skipped); Keeping the
                // denominator in formatCounts() as "terminal branches we tried to assess" rather than "all paths"
                if (path.getChildren() != null && !path.getChildren().isEmpty()) continue;
                vals.add(path.getLength());
            }
            return toMeasurements(vals, skipped, "Terminal branch length", "µm", "terminal branch");
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

        @Override
        public ImpactKind impactKind() { return ImpactKind.SUBTREE; }

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
                        String.format("Cross-over detected: %.1f µm apart, %.0f° angle",
                                ev.medianMinDist, ev.medianAngleDeg),
                        new PointInImage(ev.x, ev.y, ev.z),
                        new ArrayList<>(ev.participants),
                        ev.medianMinDist, proximityUm));
            }
            return warnings;
        }

        /**
         * Sweeps {@link CrossoverFinder} at 5x the current proximity threshold
         * so the histogram shows context around the cutoff, not just flagged
         * events. The threshold marker indicates where the user's current
         * cutoff lies; events at distance &gt; proximityUm appear unflagged.
         */
        @Override
        public Measurements measure(final Collection<Path> paths) {
            final String pathOverlapHint = "This check needs at least two paths near each other to look for cross-overs.";
            if (paths == null || paths.size() < 2) {
                return Measurements.EMPTY.withHint(pathOverlapHint);
            }
            final CrossoverFinder.Config cfg = new CrossoverFinder.Config()
                    .proximity(proximityUm * 5.0)
                    .includeSelfCrossovers(false)
                    .includeDirectChildren(false)
                    .minRunNodes(2);
            final List<CrossoverFinder.CrossoverEvent> events;
            try {
                events = CrossoverFinder.find(paths, cfg);
            } catch (final Exception e) {
                return Measurements.EMPTY.withHint(pathOverlapHint);
            }
            final List<Double> vals = new ArrayList<>(events.size());
            for (final CrossoverFinder.CrossoverEvent ev : events) vals.add(ev.medianMinDist);
            return toMeasurements(vals, 0, "Cross-over distance", "µm", "cross-over");
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

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                if (path == null || path.size() < 2 || !path.hasRadii()) { skipped++; continue; }
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 <= 0 || r2 <= 0) { skipped++; continue; }
                    vals.add(Math.max(r1, r2) / Math.min(r1, r2));
                }
            }
            final Measurements m = toMeasurements(vals, skipped, "Adjacent radius ratio", "ratio", "node pair");
            return vals.isEmpty()
                    ? m.withHint("This check needs paths with fitted radii. Run " +
                                 "Run \"Refine › Fit Paths...\" or set radii manually in the Path Manager.")
                    : m;
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

        /**
         * Collects every monotonic-increase run of length &gt;= 2 (regardless
         * of the current {@code minIncreasingRun} threshold) so the histogram
         * shows the full distribution of run lengths in the reconstruction.
         */
        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                if (path == null || path.size() < 2 || !path.hasRadii()) { skipped++; continue; }
                int runLength = 0;
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 > 0 && r2 > 0 && r2 > r1) {
                        runLength++;
                    } else {
                        if (runLength >= 2) vals.add((double) runLength);
                        runLength = 0;
                    }
                }
                if (runLength >= 2) vals.add((double) runLength);
            }
            final Measurements m = toMeasurements(vals, skipped, "Monotonic-increase run length",
                    "nodes", "monotonic run");
            return vals.isEmpty()
                    ? m.withHint("This check needs paths with fitted radii. Run " +
                            "\"Fit Volumes...\" or set radii manually in the Path Manager.")
                    : m;
        }
    }


    /**
     * Assesses local image signal quality around traced paths and flags
     * those in regions with poor contrast. For each path, voxel intensities
     * are sampled in a local neighborhood; signal and background are
     * separated by percentile thresholds, and the contrast ratio
     * (signal&nbsp;median / background&nbsp;median) is computed.
     * <p>
     * This check requires image data to be provided via
     * {@link #setImage(RandomAccessibleInterval)} before scanning.
     * If no image is set, the check returns an empty list.
     * <p>
     * Reference: Zhang et al., <i>Nature Methods</i> (2024),
     * doi:10.1038/s41592-024-02401-8.
     */
    public static class SignalQuality extends DeepCheck {

        /** Sentinel value indicating the threshold should be inferred from image stats. */
        public static final double AUTO_THRESHOLD = -1.0;

        private double minContrast = AUTO_THRESHOLD;
        private RandomAccessibleInterval<? extends RealType<?>> image;
        private double imageMin = Double.NaN;
        private double imageMax = Double.NaN;

        @Override
        public String getName() { return "Image signal quality"; }

        /**
         * Sets the minimum signal-to-background contrast ratio below which
         * a path is flagged. Set to {@link #AUTO_THRESHOLD} to infer from
         * image statistics.
         *
         * @param contrast ratio &ge; 1.0, or {@link #AUTO_THRESHOLD}
         */
        public void setMinContrast(final double contrast) { this.minContrast = contrast; }

        /** @return the current minimum contrast threshold */
        public double getMinContrast() { return minContrast; }

        /**
         * Provides the image data for signal quality assessment.
         *
         * @param image the image (3D RAI); {@code null} to clear
         */
        public void setImage(final RandomAccessibleInterval<? extends RealType<?>> image) {
            this.image = image;
        }

        /**
         * Sets image-level statistics used to auto-compute the contrast
         * threshold when {@link #getMinContrast()} == {@link #AUTO_THRESHOLD}.
         *
         * @param min image minimum intensity
         * @param max image maximum intensity
         */
        public void setImageStats(final double min, final double max) {
            this.imageMin = min;
            this.imageMax = max;
        }

        /**
         * Sets the image and computes its full-volume min/max stats by scanning every voxel. Use this instead of
         * {@link #setImage} + {@link #setImageStats} when the caller wants the resolved auto-threshold to reflect the
         * actual image range, independent of any lazily-populated stats elsewhere.
         * <p>
         * Should always be called from a worker thread.
         *
         * @param rai the image data; {@code null} clears the image and stats.
         */
        public void prepareImage(final RandomAccessibleInterval<? extends RealType<?>> rai) {
            setImage(rai);
            if (rai == null) {
                this.imageMin = Double.NaN;
                this.imageMax = Double.NaN;
                return;
            }
            final double[] mm = scanMinMaxRaw(rai);
            this.imageMin = mm[0];
            this.imageMax = mm[1];
            SNTUtils.log(String.format("SignalQuality.prepareImage: full-volume scan => min=%.3f, max=%.3f",
                    imageMin, imageMax));
        }

        /** Wildcard-erasing adapter so the generic helper can iterate the RAI. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static double[] scanMinMaxRaw(final RandomAccessibleInterval<? extends RealType<?>> rai) {
            return scanMinMax((RandomAccessibleInterval) rai);
        }

        /**
         * Linear min/max scan; returns {@code {NaN, NaN}} for an empty iterable so callers can distinguish "no data"
         * from "data with min=max=0". {@link #resolveThreshold} already treats {@code NaN} stats as "uncomputed" and
         * falls back to its hardcoded default.
         */
        private static <T extends RealType<T>> double[] scanMinMax(final RandomAccessibleInterval<T> rai) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            final Cursor<T> c = rai.cursor();
            while (c.hasNext()) {
                final double v = c.next().getRealDouble();
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (min == Double.POSITIVE_INFINITY) return new double[]{Double.NaN, Double.NaN};
            return new double[]{min, max};
        }

        /** @return whether image data has been provided */
        public boolean hasImage() { return image != null; }

        /**
         * Resolves the effective contrast threshold. If set to {@link #AUTO_THRESHOLD} and image statistics are
         * available, derives it as half the best possible contrast ratio for the image:
         * {@code (max + 1) / (min + 1) / 2}. This accounts for the full dynamic range of the image.
         */
        private double resolveThreshold() {
            if (minContrast != AUTO_THRESHOLD) return minContrast;
            if (!Double.isNaN(imageMax) && !Double.isNaN(imageMin) && imageMax > imageMin) {
                final double auto = (imageMax + 1.0) / (imageMin + 1.0) / 2.0;
                SNTUtils.log(String.format("SignalQuality: auto-threshold from image stats: min=%.1f max=%.1f => threshold=%.2f",
                        imageMin, imageMax, auto));
                return auto;
            }
            SNTUtils.log("SignalQuality: no image stats for auto-threshold, using fallback=1.5");
            return 1.5; // fallback
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public List<Warning> scan(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty() || image == null)
                return Collections.emptyList();

            final double threshold = resolveThreshold();
            SNTUtils.log(String.format("SignalQuality: scanning %d paths, threshold=%.2f",
                    paths.size(), threshold));

            final List<Warning> warnings = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                // LINE shape requires at least 3 nodes (skips first and last)
                if (path == null || path.size() < 3) { skipped++; continue; }

                // Sample perpendicular cross-sections at each node using LINE shape.
                // The line extends well beyond the neurite radius so that it captures
                // both on-neurite (signal) and flanking off-neurite (background) pixels.
                final SortedMap<Integer, List<Double>> rawValues;
                try {
                    final ProfileProcessor pp = new ProfileProcessor(image, path);
                    pp.setShape(ProfileProcessor.Shape.LINE);
                    // Use 3× path radius (min 5 px) to ensure lines reach background
                    final int lineRadius = path.hasRadii()
                            ? Math.max((int) Math.round(path.getMeanRadius() * 3), 5)
                            : 5;
                    pp.setRadius(lineRadius);
                    rawValues = pp.getRawValues(1);
                } catch (final Exception e) {
                    SNTUtils.log(String.format("SignalQuality: \"%s\" threw: %s",
                            path.getName(), e.getMessage()));
                    skipped++; continue;
                }
                if (rawValues == null || rawValues.isEmpty()) { skipped++; continue; }

                // Pool all cross-section pixel values across sampled nodes
                final DescriptiveStatistics ds = new DescriptiveStatistics();
                for (final List<Double> nodeVals : rawValues.values()) {
                    for (final double v : nodeVals) {
                        if (!Double.isNaN(v)) ds.addValue(v);
                    }
                }
                if (ds.getN() < 10) { skipped++; continue; }

                final double p25 = ds.getPercentile(25);
                final double p75 = ds.getPercentile(75);

                // Background: lower quartile (flanking off-neurite pixels)
                final DescriptiveStatistics bgStats = new DescriptiveStatistics();
                // Signal: upper quartile (on-neurite pixels)
                final DescriptiveStatistics sigStats = new DescriptiveStatistics();
                for (final List<Double> nodeVals : rawValues.values()) {
                    for (final double v : nodeVals) {
                        if (Double.isNaN(v)) continue;
                        if (v <= p25) bgStats.addValue(v);
                        else if (v >= p75) sigStats.addValue(v);
                    }
                }

                if (bgStats.getN() < 2 || sigStats.getN() < 2) { skipped++; continue; }
                final double bgMedian = bgStats.getPercentile(50);
                final double signalMedian = sigStats.getPercentile(50);

                // Add 1 to both to avoid division by zero in fluorescence
                // images where background is often exactly 0, while
                // preserving the ratio for non-zero values
                final double contrast = (signalMedian + 1) / (bgMedian + 1);

                SNTUtils.log(String.format("SignalQuality: \"%s\" contrast=%.2f (sig=%.0f bg=%.0f)",
                        path.getName(), contrast, signalMedian, bgMedian));

                if (contrast < threshold) {
                    final int mid = path.size() / 2;
                    warnings.add(new Warning(getName(), Severity.WARNING,
                            String.format("Low signal quality in \"%s\": contrast %.2f " +
                                            "(min %.2f), signal median=%.0f, background median=%.0f",
                                    path.getName(), contrast, threshold,
                                    signalMedian, bgMedian),
                            path.getNode(mid), List.of(path), contrast, threshold));
                }
            }
            SNTUtils.log(String.format("SignalQuality: done. %d warnings, %d paths skipped",
                    warnings.size(), skipped));
            return warnings;
        }

        /**
         * Computes the signal/background contrast ratio for a single path, using the same per-path procedure as
         * {@link #scan(Collection)}.
         *
         * @return the contrast ratio, or {@link Double#NaN} when the path cannot be evaluated (too few nodes,
         *         no image, or insufficient samples). Callers should treat NaN as "not assessable".
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private double contrastFor(final Path path) {
            if (path == null || path.size() < 3 || image == null) return Double.NaN;
            final SortedMap<Integer, List<Double>> rawValues;
            try {
                final ProfileProcessor pp = new ProfileProcessor(image, path);
                pp.setShape(ProfileProcessor.Shape.LINE);
                final int lineRadius = path.hasRadii()
                        ? Math.max((int) Math.round(path.getMeanRadius() * 3), 5)
                        : 5;
                pp.setRadius(lineRadius);
                rawValues = pp.getRawValues(1);
            } catch (final Exception e) {
                return Double.NaN;
            }
            if (rawValues == null || rawValues.isEmpty()) return Double.NaN;
            final DescriptiveStatistics ds = new DescriptiveStatistics();
            for (final List<Double> nodeVals : rawValues.values()) {
                for (final double v : nodeVals) {
                    if (!Double.isNaN(v)) ds.addValue(v);
                }
            }
            if (ds.getN() < 10) return Double.NaN;
            final double p25 = ds.getPercentile(25);
            final double p75 = ds.getPercentile(75);
            final DescriptiveStatistics bgStats = new DescriptiveStatistics();
            final DescriptiveStatistics sigStats = new DescriptiveStatistics();
            for (final List<Double> nodeVals : rawValues.values()) {
                for (final double v : nodeVals) {
                    if (Double.isNaN(v)) continue;
                    if (v <= p25) bgStats.addValue(v);
                    else if (v >= p75) sigStats.addValue(v);
                }
            }
            if (bgStats.getN() < 2 || sigStats.getN() < 2) return Double.NaN;
            return (sigStats.getPercentile(50) + 1) / (bgStats.getPercentile(50) + 1);
        }

        /**
         * @return the resolved threshold (auto-derived from image stats when the spinner is set to
         * {@link #AUTO_THRESHOLD}). Exposed so  the curation UI can draw the marker at the effective cutoff
         * even in auto mode.
         */
        public double getResolvedThreshold() { return resolveThreshold(); }

        @Override
        public Measurements measure(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) return Measurements.EMPTY;
            // No image: surface the prerequisite explicitly rather than returning an empty Measurements.
            // The UI then displays a check-specific hint instead of the generic empty-state text
            if (image == null) {
                return toMeasurements(new ArrayList<>(), paths.size(),
                        "Signal/background contrast", "ratio", "path")
                        .withHint("Load an image first. This check measures signal contrast along each path; " +
                                "it cannot run without valid image data.");
            }
            final List<Double> vals = new ArrayList<>();
            int skipped = 0;
            for (final Path path : paths) {
                final double c = contrastFor(path);
                if (Double.isNaN(c)) skipped++;
                else vals.add(c);
            }
            return toMeasurements(vals, skipped, "Signal/background contrast", "ratio", "path");
        }
    }

    static double mag(final double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
