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

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.util.CrossoverFinder;
import sc.fiji.snt.util.PointInImage;

import java.io.*;
import java.util.*;

/**
 * Infers {@link PlausibilityCheck} thresholds from a collection of reference
 * {@link Tree}s. For each configurable parameter the calibrator collects the
 * corresponding metric across every parent-child fork (live checks) or every
 * path (deep checks) and derives thresholds from percentile statistics.
 * <p>
 * Typical usage:
 * <pre>{@code
 * PlausibilityCalibrator cal = new PlausibilityCalibrator(trees);
 * CalibrationResult result = cal.calibrate();
 * result.applyTo(monitor);            // updates all thresholds
 * System.out.println(result.toTable()); // human-readable summary
 * }</pre>
 *
 * @author Tiago Ferreira
 * @see PlausibilityCheck
 * @see PlausibilityMonitor
 */
public class PlausibilityCalibrator {

    /** Summary for a single calibrated parameter. */
    public record CheckSummary(
            String checkName,
            String paramLabel,
            double computedValue,
            int sampleSize,
            double mean,
            double stdDev,
            double percentileUsed
    ) {}

    /** Aggregated calibration results from a set of reference trees. */
    public static class CalibrationResult {

        private final List<CheckSummary> summaries = new ArrayList<>();
        private boolean hasRadii;
        private int treeCount;

        /** @return the per-check summaries */
        public List<CheckSummary> getSummaries() {
            return Collections.unmodifiableList(summaries);
        }

        /** @return {@code true} if any reference tree contained fitted radii */
        public boolean hasRadii() { return hasRadii; }

        /** @return the number of reference trees used */
        public int getTreeCount() { return treeCount; }

        /**
         * Applies all calibrated thresholds to the given monitor.
         *
         * @param monitor the monitor whose checks will be updated
         */
        public void applyTo(final PlausibilityMonitor monitor) {
            for (final CheckSummary s : summaries) {
                if (s.sampleSize == 0) continue;
                switch (s.checkName) {
                    case "Branch angle" -> {
                        final PlausibilityCheck.BranchAngle chk = monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
                        if (chk != null) {
                            if ("Min. fork angle".equals(s.paramLabel))
                                chk.setMinAngleDeg(s.computedValue);
                            else
                                chk.setMaxAngleDeg(s.computedValue);
                        }
                    }
                    case "Direction continuity" -> {
                        final PlausibilityCheck.DirectionContinuity chk = monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
                        if (chk != null) chk.setMinAlignmentDeg(s.computedValue);
                    }
                    case "Radius continuity" -> {
                        final PlausibilityCheck.RadiusContinuity chk = monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
                        if (chk != null) chk.setMaxRatio(s.computedValue);
                    }
                    case "Terminal branch length" -> {
                        final PlausibilityCheck.TerminalBranchLength chk = monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
                        if (chk != null) chk.setMinLengthUm(s.computedValue);
                    }
                    case "Soma distance" -> {
                        final PlausibilityCheck.SomaDistance chk = monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
                        if (chk != null) chk.setMaxDistUm(s.computedValue);
                    }
                    case "Tortuosity consistency" -> {
                        final PlausibilityCheck.TortuosityConsistency chk = monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
                        if (chk != null) chk.setMaxContractionDiff(s.computedValue);
                    }
                    case "Path overlap" -> {
                        final PlausibilityCheck.PathOverlap chk = monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
                        if (chk != null) chk.setProximityUm(s.computedValue);
                    }
                    case "Radius jumps" -> {
                        final PlausibilityCheck.RadiusJumps chk = monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
                        if (chk != null) chk.setMaxJumpRatio(s.computedValue);
                    }
                    case "Radius monotonicity" -> {
                        final PlausibilityCheck.RadiusMonotonicity chk = monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
                        if (chk != null) chk.setMinIncreasingRun((int) Math.round(s.computedValue));
                    }
                    default -> SNTUtils.log("PlausibilityCalibrator: unknown check '" + s.checkName + "'");
                }
            }
            // Enable/disable ConstantRadii based on whether reference data has radii
            final PlausibilityCheck.ConstantRadii crChk = monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
            if (crChk != null) crChk.setEnabled(hasRadii);
        }

        /**
         * Returns a human-readable summary table of the calibration results.
         *
         * @return the formatted table string
         */
        public String toTable() {
            final StringBuilder sb = new StringBuilder();
            sb.append(String.format("Calibration from %d tree(s) | Radii present: %s%n", treeCount, hasRadii));
            sb.append(String.format("%-28s %-22s %10s %8s %10s %10s %6s%n",
                    "Check", "Parameter", "Threshold", "N", "Mean", "Std Dev", "Pctl"));
            sb.append("-".repeat(96)).append('\n');
            for (final CheckSummary s : summaries) {
                sb.append(String.format("%-28s %-22s %10.2f %8d %10.2f %10.2f %5.0f%%%n",
                        s.checkName, s.paramLabel, s.computedValue, s.sampleSize,
                        s.mean, s.stdDev, s.percentileUsed));
            }
            return sb.toString();
        }
    }

    private final Collection<Tree> trees;
    private double upperPercentile = 95.0;
    private double lowerPercentile = 5.0;

    /**
     * Creates a calibrator from a collection of reference trees.
     *
     * @param trees the reference trees (must not be null or empty)
     * @throws IllegalArgumentException if {@code trees} is null or empty
     */
    public PlausibilityCalibrator(final Collection<Tree> trees) {
        if (trees == null || trees.isEmpty())
            throw new IllegalArgumentException("At least one reference tree is required");
        this.trees = trees;
    }

    /**
     * Sets the percentile used for "flag if above" thresholds (e.g., max fork
     * angle, max radius ratio). Default is 95.
     *
     * @param percentile the upper percentile (0-100)
     */
    public void setUpperPercentile(final double percentile) {
        this.upperPercentile = percentile;
    }

    /**
     * Sets the percentile used for "flag if below" thresholds (e.g., min fork
     * angle, min terminal branch length). Default is 5.
     *
     * @param percentile the lower percentile (0-100)
     */
    public void setLowerPercentile(final double percentile) {
        this.lowerPercentile = percentile;
    }

    /**
     * Runs the calibration against all reference trees and returns a
     * {@link CalibrationResult} containing the inferred thresholds.
     *
     * @return the calibration result (never null)
     */
    public CalibrationResult calibrate() {
        final CalibrationResult result = new CalibrationResult();
        result.treeCount = trees.size();
        result.hasRadii = trees.stream().anyMatch(t ->
                t.list().stream().anyMatch(Path::hasRadii));

        calibrateBranchAngle(result);
        calibrateDirectionContinuity(result);
        calibrateRadiusContinuity(result);
        calibrateTerminalBranchLength(result);
        calibrateSomaDistance(result);
        calibrateTortuosityConsistency(result);
        calibratePathOverlap(result);
        calibrateRadiusJumps(result);
        calibrateRadiusMonotonicity(result);

        return result;
    }

    private void calibrateBranchAngle(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            final TreeStatistics ts = new TreeStatistics(tree);
            for (final double angle : ts.getRemoteBifAngles()) {
                stats.addValue(angle);
            }
        }
        addSummary(result, "Branch angle", "Min. fork angle",
                stats, lowerPercentile);
        addSummary(result, "Branch angle", "Max. fork angle",
                stats, upperPercentile);
    }

    private void calibrateDirectionContinuity(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                final Path parent = path.getParentPath();
                if (parent == null) continue;
                final int bpIdx = path.getBranchPointIndex();
                if (bpIdx < 0 || bpIdx >= parent.size()) continue;
                if (parent.size() < 3) continue;

                final double[] parentTangent = new double[3];
                parent.getTangent(bpIdx, Math.min(2, bpIdx), parentTangent);
                final double parentMag = PlausibilityCheck.mag(parentTangent);
                if (parentMag == 0) continue;

                final double[] childDir = path.getInitialDirection(5);
                if (childDir == null) continue;
                final double childMag = PlausibilityCheck.mag(childDir);

                final double dot = (parentTangent[0] * childDir[0] +
                        parentTangent[1] * childDir[1] +
                        parentTangent[2] * childDir[2]) / (parentMag * childMag);
                final double angleDeg = Math.toDegrees(Math.acos(
                        Math.min(1.0, Math.abs(dot))));
                stats.addValue(angleDeg);
            }
        }
        addSummary(result, "Direction continuity", "Min. direction change",
                stats, lowerPercentile);
    }

    private void calibrateRadiusContinuity(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                final Path parent = path.getParentPath();
                if (parent == null) continue;
                final int bpIdx = path.getBranchPointIndex();
                if (bpIdx < 0 || bpIdx >= parent.size()) continue;

                final double parentRadius = parent.getNode(bpIdx).radius;
                if (parentRadius <= 0) continue;

                final int sampleSize = Math.min(5, path.size());
                final List<Double> childRadii = new ArrayList<>(sampleSize);
                for (int i = 0; i < sampleSize; i++) {
                    final double r = path.getNode(i).radius;
                    if (r > 0) childRadii.add(r);
                }
                if (childRadii.isEmpty()) continue;
                Collections.sort(childRadii);
                final double childRadius = childRadii.get(childRadii.size() / 2);

                stats.addValue(childRadius / parentRadius);
            }
        }
        addSummary(result, "Radius continuity", "Max. radius ratio",
                stats, upperPercentile);
    }

    private void calibrateTerminalBranchLength(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                final List<Path> children = path.getChildren();
                if (children != null && !children.isEmpty()) continue;
                if (path.size() < 2) continue;
                stats.addValue(path.getLength());
            }
        }
        addSummary(result, "Terminal branch length", "Min. length",
                stats, lowerPercentile);
    }

    private void calibrateSomaDistance(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            final List<PointInImage> somaNodes = tree.getSomaNodes();
            if (somaNodes == null || somaNodes.isEmpty()) continue;
            for (final Path path : tree.list()) {
                if (path.size() == 0) continue;
                final PointInImage start = path.getNode(0);
                double minDist = Double.MAX_VALUE;
                for (final PointInImage soma : somaNodes) {
                    final double d = start.distanceTo(soma);
                    if (d < minDist) minDist = d;
                }
                stats.addValue(minDist);
            }
        }
        addSummary(result, "Soma distance", "Max. distance",
                stats, upperPercentile);
    }

    private void calibrateTortuosityConsistency(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                final Path parent = path.getParentPath();
                if (parent == null) continue;
                if (path.size() < 5 || parent.size() < 5) continue;
                final double cc = path.getContraction();
                final double pc = parent.getContraction();
                if (Double.isNaN(cc) || Double.isNaN(pc)) continue;
                stats.addValue(Math.abs(cc - pc));
            }
        }
        addSummary(result, "Tortuosity consistency", "Max. contraction diff",
                stats, upperPercentile);
    }

    private void calibratePathOverlap(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            // Compute median inter-node distance for this tree
            final TreeStatistics ts = new TreeStatistics(tree);
            final DescriptiveStatistics indStats = ts.getDescriptiveStats(TreeStatistics.INTER_NODE_DISTANCE);
            if (indStats.getN() == 0) continue;
            final double proximity = indStats.getPercentile(50) * 2.0;

            final List<Path> paths = tree.list();
            if (paths.size() < 2) continue;
            final CrossoverFinder.Config cfg = new CrossoverFinder.Config()
                    .proximity(proximity)
                    .includeSelfCrossovers(false)
                    .includeDirectChildren(false)
                    .minRunNodes(2);
            final List<CrossoverFinder.CrossoverEvent> events = CrossoverFinder.find(paths, cfg);
            for (final CrossoverFinder.CrossoverEvent ev : events) {
                stats.addValue(ev.medianMinDist);
            }
        }
        addSummary(result, "Path overlap", "Cross-over proximity",
                stats, lowerPercentile);
    }

    private void calibrateRadiusJumps(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                if (path.size() < 2 || !path.hasRadii()) continue;
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 <= 0 || r2 <= 0) continue;
                    final double ratio = Math.max(r1, r2) / Math.min(r1, r2);
                    if (ratio > 1.0) { // only include actual jumps
                        stats.addValue(ratio);
                    }
                }
            }
        }
        addSummary(result, "Radius jumps", "Max. jump ratio",
                stats, upperPercentile);
    }

    private void calibrateRadiusMonotonicity(final CalibrationResult result) {
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                if (path.size() < 3 || !path.hasRadii()) continue;
                int runLength = 0;
                for (int i = 0; i < path.size() - 1; i++) {
                    final double r1 = path.getNode(i).radius;
                    final double r2 = path.getNode(i + 1).radius;
                    if (r1 > 0 && r2 > 0 && r2 > r1) {
                        runLength++;
                    } else {
                        if (runLength > 0) stats.addValue(runLength);
                        runLength = 0;
                    }
                }
                if (runLength > 0) stats.addValue(runLength);
            }
        }
        addSummary(result, "Radius monotonicity", "Max. increasing run",
                stats, upperPercentile);
    }

    private void addSummary(final CalibrationResult result, final String checkName,
                            final String paramLabel, final DescriptiveStatistics stats,
                            final double percentile) {
        if (stats.getN() == 0) {
            result.summaries.add(new CheckSummary(checkName, paramLabel,
                    Double.NaN, 0, Double.NaN, Double.NaN, percentile));
            return;
        }
        result.summaries.add(new CheckSummary(
                checkName,
                paramLabel,
                stats.getPercentile(percentile),
                (int) stats.getN(),
                stats.getMean(),
                stats.getStandardDeviation(),
                percentile
        ));
    }


    /** The file extension for curation preset files (without leading dot). */
    public static final String CURATION_EXTENSION = "curation";

    /** Resource path prefix for built-in presets bundled in the JAR. */
    private static final String BUILT_IN_RESOURCE_DIR = "curations/";

    /**
     * Names of built-in curation presets bundled with SNT (without the
     * {@code .curation} extension). This list must be kept in sync with
     * the files under {@code src/main/resources/curations/}.
     */
    public static final String[] BUILT_IN_PRESETS = {
            "CA1 Dendrites (Mouse, DeFelipe)",
            "CA3 Dendrites (Rat, Amaral)",
            "Eurydendroid Cells (Zebrafish, Baier)",
            "Kenyon Cells (Drosophila, Bock)",
            "Martinotti Axons (Mouse, Yuste)",
            "Martinotti Dendrites (Mouse, Yuste)",
            "Purkinje Dendrites (Mouse, Dusart)",
            "RGC Dendrites (Mouse, Fried)",
    };

    /**
     * Returns the curations subdirectory inside the given workspace directory.
     * Creates the directory if it does not exist.
     *
     * @param workspaceDir the SNT workspace directory (e.g., from
     *                     {@code SNTPrefs.getWorkspaceDir()})
     * @return the curations directory ({@code <workspaceDir>/curations/})
     */
    public static File getCurationsDirectory(final File workspaceDir) {
        final File dir = new File(workspaceDir, "curations");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Lists all {@code .curation} files in the curations subdirectory of the
     * given workspace directory.
     *
     * @param workspaceDir the SNT workspace directory
     * @return an array of curation files (may be empty, never null)
     */
    public static File[] listCurationFiles(final File workspaceDir) {
        final File dir = getCurationsDirectory(workspaceDir);
        final File[] files = dir.listFiles((f, name) -> name.endsWith("." + CURATION_EXTENSION));
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    /**
     * Saves the current state of a {@link PlausibilityMonitor} (thresholds and
     * enabled states) to a {@code .curation} properties file.
     *
     * @param monitor the monitor whose state will be saved
     * @param file    the target file (should have {@code .curation} extension)
     * @param comment an optional header comment (e.g., cell type description)
     * @throws IOException if writing fails
     */
    public static void save(final PlausibilityMonitor monitor, final File file,
                            final String comment) throws IOException {
        // LinkedHashMap preserves insertion order for readable output
        final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

        // Live checks
        final PlausibilityCheck.BranchAngle ba = monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        if (ba != null) {
            entries.put("branchAngle.min", String.valueOf(ba.getMinAngleDeg()));
            entries.put("branchAngle.max", String.valueOf(ba.getMaxAngleDeg()));
            entries.put("branchAngle.enabled", String.valueOf(ba.isEnabled()));
        }
        final PlausibilityCheck.DirectionContinuity dc = monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        if (dc != null) {
            entries.put("directionContinuity.minAlignment", String.valueOf(dc.getMinAlignmentDeg()));
            entries.put("directionContinuity.enabled", String.valueOf(dc.isEnabled()));
        }
        final PlausibilityCheck.RadiusContinuity rc = monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        if (rc != null) {
            entries.put("radiusContinuity.maxRatio", String.valueOf(rc.getMaxRatio()));
            entries.put("radiusContinuity.enabled", String.valueOf(rc.isEnabled()));
        }
        final PlausibilityCheck.TerminalBranchLength tbl = monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        if (tbl != null) {
            entries.put("terminalBranchLength.minLength", String.valueOf(tbl.getMinLengthUm()));
            entries.put("terminalBranchLength.enabled", String.valueOf(tbl.isEnabled()));
        }
        final PlausibilityCheck.SomaDistance sd = monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
        if (sd != null) {
            entries.put("somaDistance.maxDist", String.valueOf(sd.getMaxDistUm()));
            entries.put("somaDistance.enabled", String.valueOf(sd.isEnabled()));
        }
        final PlausibilityCheck.TortuosityConsistency tc = monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        if (tc != null) {
            entries.put("tortuosityConsistency.maxDiff", String.valueOf(tc.getMaxContractionDiff()));
            entries.put("tortuosityConsistency.enabled", String.valueOf(tc.isEnabled()));
        }
        final PlausibilityCheck.ConstantRadii cr = monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        if (cr != null) {
            entries.put("constantRadii.enabled", String.valueOf(cr.isEnabled()));
        }

        // Deep checks
        final PlausibilityCheck.PathOverlap po = monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
        if (po != null) {
            entries.put("pathOverlap.proximity", String.valueOf(po.getProximityUm()));
            entries.put("pathOverlap.enabled", String.valueOf(po.isEnabled()));
        }
        final PlausibilityCheck.RadiusJumps rj = monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        if (rj != null) {
            entries.put("radiusJumps.maxRatio", String.valueOf(rj.getMaxJumpRatio()));
            entries.put("radiusJumps.enabled", String.valueOf(rj.isEnabled()));
        }
        final PlausibilityCheck.RadiusMonotonicity rm = monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        if (rm != null) {
            entries.put("radiusMonotonicity.minRun", String.valueOf(rm.getMinIncreasingRun()));
            entries.put("radiusMonotonicity.enabled", String.valueOf(rm.isEnabled()));
        }

        // Monitor-level state
        entries.put("monitor.enabled", String.valueOf(monitor.isEnabled()));

        try (final PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            if (comment != null && !comment.isEmpty()) {
                writer.println("# " + comment);
            }
            for (final Map.Entry<String, String> e : entries.entrySet()) {
                writer.println(e.getKey() + "=" + e.getValue());
            }
        }
    }

    /**
     * Loads thresholds and enabled states from a {@code .curation} properties
     * file and applies them to the given monitor.
     *
     * @param file    the curation file to load
     * @param monitor the monitor to update
     * @throws IOException if reading fails
     */
    public static void load(final File file, final PlausibilityMonitor monitor)
            throws IOException {
        final Properties props = new Properties();
        try (final FileReader reader = new FileReader(file)) {
            props.load(reader);
        }
        applyProperties(props, monitor);
    }

    /**
     * Loads a built-in curation preset bundled in the SNT JAR and applies it to
     * the given monitor. Preset names correspond to entries in
     * {@link #BUILT_IN_PRESETS}.
     *
     * @param presetName the preset name (without extension), e.g. {@code "dummy1"}
     * @param monitor    the monitor to update
     * @throws IOException              if reading fails
     * @throws IllegalArgumentException if the preset resource is not found
     */
    public static void loadBuiltIn(final String presetName, final PlausibilityMonitor monitor)
            throws IOException {
        final String resourcePath = BUILT_IN_RESOURCE_DIR + presetName + "." + CURATION_EXTENSION;
        try (final InputStream is = PlausibilityCalibrator.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Built-in preset not found: " + resourcePath);
            }
            final Properties props = new Properties();
            props.load(is);
            applyProperties(props, monitor);
        }
    }

    private static void applyProperties(final Properties props, final PlausibilityMonitor monitor) {
        // Live checks
        final PlausibilityCheck.BranchAngle ba = monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        if (ba != null) {
            setDouble(props, "branchAngle.min", ba::setMinAngleDeg);
            setDouble(props, "branchAngle.max", ba::setMaxAngleDeg);
            setEnabled(props, "branchAngle.enabled", ba::setEnabled);
        }
        final PlausibilityCheck.DirectionContinuity dc = monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        if (dc != null) {
            setDouble(props, "directionContinuity.minAlignment", dc::setMinAlignmentDeg);
            setEnabled(props, "directionContinuity.enabled", dc::setEnabled);
        }
        final PlausibilityCheck.RadiusContinuity rc = monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        if (rc != null) {
            setDouble(props, "radiusContinuity.maxRatio", rc::setMaxRatio);
            setEnabled(props, "radiusContinuity.enabled", rc::setEnabled);
        }
        final PlausibilityCheck.TerminalBranchLength tbl = monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        if (tbl != null) {
            setDouble(props, "terminalBranchLength.minLength", tbl::setMinLengthUm);
            setEnabled(props, "terminalBranchLength.enabled", tbl::setEnabled);
        }
        final PlausibilityCheck.SomaDistance sd = monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
        if (sd != null) {
            setDouble(props, "somaDistance.maxDist", sd::setMaxDistUm);
            setEnabled(props, "somaDistance.enabled", sd::setEnabled);
        }
        final PlausibilityCheck.TortuosityConsistency tc = monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        if (tc != null) {
            setDouble(props, "tortuosityConsistency.maxDiff", tc::setMaxContractionDiff);
            setEnabled(props, "tortuosityConsistency.enabled", tc::setEnabled);
        }
        final PlausibilityCheck.ConstantRadii cr = monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        if (cr != null) {
            setEnabled(props, "constantRadii.enabled", cr::setEnabled);
        }

        // Deep checks
        final PlausibilityCheck.PathOverlap po = monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
        if (po != null) {
            setDouble(props, "pathOverlap.proximity", po::setProximityUm);
            setEnabled(props, "pathOverlap.enabled", po::setEnabled);
        }
        final PlausibilityCheck.RadiusJumps rj = monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        if (rj != null) {
            setDouble(props, "radiusJumps.maxRatio", rj::setMaxJumpRatio);
            setEnabled(props, "radiusJumps.enabled", rj::setEnabled);
        }
        final PlausibilityCheck.RadiusMonotonicity rm = monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        if (rm != null) {
            setInt(props, "radiusMonotonicity.minRun", rm::setMinIncreasingRun);
            setEnabled(props, "radiusMonotonicity.enabled", rm::setEnabled);
        }

        // Monitor-level state (only present in session files, not calibration presets)
        setEnabled(props, "monitor.enabled", monitor::setEnabled);
    }

    private static void setDouble(final Properties props, final String key,
                                  final java.util.function.DoubleConsumer setter) {
        final String val = props.getProperty(key);
        if (val != null) {
            try {
                setter.accept(Double.parseDouble(val));
            } catch (final NumberFormatException e) {
                SNTUtils.log("PlausibilityCalibrator: invalid value for " + key + ": " + val);
            }
        }
    }

    private static void setInt(final Properties props, final String key,
                               final java.util.function.IntConsumer setter) {
        final String val = props.getProperty(key);
        if (val != null) {
            try {
                setter.accept(Integer.parseInt(val));
            } catch (final NumberFormatException e) {
                SNTUtils.log("PlausibilityCalibrator: invalid value for " + key + ": " + val);
            }
        }
    }

    private static void setEnabled(final Properties props, final String key,
                                   final java.util.function.Consumer<Boolean> setter) {
        final String val = props.getProperty(key);
        if (val != null) setter.accept(Boolean.parseBoolean(val));
    }
}
