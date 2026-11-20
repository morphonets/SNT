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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

/**
 * Parameters of {@link TipFinder} learned from training paths: the radii of the training paths give the scales of the
 * score map and the analysis radius, and the scores (and, optionally, the background image) sampled along them give the
 * thresholds. The path ends of the training paths are taken as known tips, which allows the learned parameters to be
 * checked (see {@link #evaluate}).
 * <p>
 * This class has no dependency on SNT paths: examples are described by a {@link TrainingPaths}.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class TipDetectionConfig {

    /**
     * Training paths, in the voxel grid of the image that is analyzed
     *
     * @param radii the radii (calibrated units) sampled along the training paths
     * @param nodes the voxel position {x, y, z} of every node of the training paths
     * @param tips  the voxel position {x, y, z} of the tips (the path ends) of the training paths
     */
    public record TrainingPaths(double[] radii, long[][] nodes, long[][] tips) {
    }

    /** Computes the score map of the analyzed region for a set of scales (calibrated units) */
    @FunctionalInterface
    public interface ScoreMapFactory {
        RandomAccessibleInterval<? extends RealType<?>> create(double[] scales);
    }

    /**
     * Response of a single scale: how the score map separates the training paths from their surroundings
     *
     * @param scale          the scale (calibrated units)
     * @param nodeMedian     median score at the nodes
     * @param nodeP10        10th percentile of the scores at the nodes
     * @param backgroundP50  median score away from the training paths
     * @param backgroundP99  99th percentile of the scores away from the training paths
     */
    public record ScaleResponse(double scale, double nodeMedian, double nodeP10, double backgroundP50,
                                double backgroundP99) {
        /** Median score at the nodes relative to the 99th percentile of the background */
        public double contrast() {
            return (backgroundP99 > 0) ? nodeMedian / backgroundP99 : Double.NaN;
        }
    }

    /**
     * Outcome of running the learned parameters on the analyzed region
     *
     * @param tips      the detected tips
     * @param pathEnds  number of example tips that could be evaluated (those at least one radius away from the
     *                  border of the region)
     * @param matched   number of those that have a detected tip nearby
     * @param skipped   number of example tips that are too close to the border of the region
     * @param tolerance the distance (calibrated units) within which a detection matches an example tip
     * @param status    the outcome for each example tip (same order as {@link TrainingPaths#tips()})
     * @param near      number of detections within the tolerance of an evaluated example tip
     */
    public record Evaluation(List<TipFinder.Tip> tips, int pathEnds, int matched, int skipped, double tolerance,
                             TipStatus[] status, int near) {
        /** Detections that are not close to an example tip (not necessarily false: the training paths are partial) */
        public int extra() {
            return tips.size() - near;
        }
    }

    /** Outcome of an example tip: found, missed, or not evaluated (too close to the border of the region) */
    public enum TipStatus {
        MATCHED, MISSED, SKIPPED
    }

    // Heuristic defaults: the analysis radius is this many times the 95th
    // percentile of the training radii. A tube of length R and radius r has
    // linearity 1 - 3r^2/R^2, so R = 2.7r just reaches the default min.
    // linearity (0.6), and 6r leaves about 2x margin for curvature and the
    // apparent widening caused by smoothing
    private static final double ANALYSIS_RADIUS_FACTOR = 6;
    // Floor of the analysis radius, in voxels, so that the neighborhood holds
    // enough voxels to pass the default min. mass
    private static final double MIN_ANALYSIS_RADIUS_VOXELS = 5;

    private double[] scales = {};
    private double analysisRadius = Double.NaN;
    private double minScore = Double.NaN;
    private double maxBackground = Double.NaN;
    private double backgroundRadius = Double.NaN;
    private double minLength = 0.6;
    private double minConnectedFraction = 0.3;
    private double minRadius = Double.NaN;
    private double medianRadius = Double.NaN;
    private double maxRadius = Double.NaN;
    private double nodeScoreP10 = Double.NaN;
    private double nodeScoreMedian = Double.NaN;
    private double backgroundP50 = Double.NaN;
    private double backgroundP99 = Double.NaN;
    private double[] spacing;
    private final List<ScaleResponse> responses = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private TipDetectionConfig() {
    }

    /**
     * Creates a configuration from known parameters (e.g., exported by the Tip Detection Wizard)
     *
     * @param neuriteRadii  the neurite radii (Tubeness scales, calibrated units)
     * @param analysisRadius     the analysis radius (calibrated units)
     * @param minScore      the Tubeness score above which voxels are considered foreground
     * @param minLength     min. length: reach of the connected structure, as a fraction of the radius
     * @param minConnectedFraction     min. connected fraction: fraction of the voxels in the radius connected to the tip
     * @param maxBackground the max. background (mean in backgroundRadius around a candidate). NaN for none
     * @param backgroundRadius the radius (calibrated units) in which the background is averaged around a candidate
     * @return the configuration (no background subtraction: see {@link #setBackground(int, double)})
     */
    public static TipDetectionConfig of(final double[] neuriteRadii, final double analysisRadius, final double minScore,
                                        final double minLength, final double minConnectedFraction, final double maxBackground,
                                        final double backgroundRadius) {
        final TipDetectionConfig c = new TipDetectionConfig();
        c.scales = neuriteRadii.clone();
        c.analysisRadius = analysisRadius;
        c.minScore = minScore;
        c.minLength = minLength;
        c.minConnectedFraction = minConnectedFraction;
        c.maxBackground = maxBackground;
        c.backgroundRadius = backgroundRadius;
        return c;
    }

    /**
     * Learns parameters from examples
     *
     * @param example      the training paths
     * @param spacing      voxel size (x, y, z) in calibrated units
     * @param scales       the scales to use (e.g., see {@code AutoTraceConfig#deriveScales}). If null or empty,
     *                     the median radius is used
     * @param scoreMaps    computes the score map of the region for a set of scales. The maps must be zero-min,
     *                     in the grid of the nodes of the training paths
     * @param backgroundImg         the background image (e.g., a background channel), on the same grid as the score maps. May be null
     * @return the learned configuration. Values that could not be derived are NaN
     */
    public static TipDetectionConfig learn(final TrainingPaths example, final double[] spacing, final double[] scales,
                                           final ScoreMapFactory scoreMaps,
                                           final RandomAccessibleInterval<? extends RealType<?>> backgroundImg) {
        if (example == null || example.nodes() == null || example.nodes().length == 0)
            throw new IllegalArgumentException("At least one example node is required");
        final TipDetectionConfig c = new TipDetectionConfig();
        c.spacing = spacing.clone();
        final double minSpacing = Math.min(spacing[0], Math.min(spacing[1], spacing[2]));

        // Radii: scales and analysis radius
        final double[] radii = (example.radii() == null) ? new double[0] : example.radii().clone();
        Arrays.sort(radii);
        if (radii.length > 0) {
            c.minRadius = radii[0];
            c.maxRadius = radii[radii.length - 1];
            c.medianRadius = radii[radii.length / 2];
        } else {
            c.warnings.add("The training paths have no radii, so the Neurite radii and Analysis radius are guesses. "
                    + "Check them.");
        }
        final double robustRadius = (radii.length > 0) ? percentile(radii, 95) : 2 * minSpacing;
        c.scales = (scales != null && scales.length > 0) ? scales.clone()
                : new double[]{(radii.length > 0) ? c.medianRadius : minSpacing};
        c.analysisRadius = Math.max(ANALYSIS_RADIUS_FACTOR * robustRadius, MIN_ANALYSIS_RADIUS_VOXELS * minSpacing);
        c.backgroundRadius = Math.max(2 * robustRadius, minSpacing);
        for (final double s : c.scales) {
            if (s < 0.7 * minSpacing)
                c.warnings.add(String.format("Neurite radius %.3g is smaller than the voxel size (%.3g): the Tubeness "
                        + "score will be unreliable. Increase the radius.", s, minSpacing));
        }
        if (example.nodes().length < 30)
            c.warnings.add("Only " + example.nodes().length + " nodes in the training paths: the learned values may "
                    + "be unreliable. Trace more paths.");

        // Scores along the training paths versus the rest of the region
        final RandomAccessibleInterval<? extends RealType<?>> ridge = scoreMaps.create(c.scales);
        for (int d = 0; d < 3; d++) {
            if (ridge.min(d) != 0) throw new IllegalArgumentException("Score maps must be zero-min");
        }
        final BitSet nearExamples = markNeighborhood(example.nodes(), dims(ridge), spacing,
                Math.max(2 * robustRadius, 2 * minSpacing));
        if (nearExamples == null)
            c.warnings.add("The region is too large to exclude the training paths from the background sample: "
                    + "background percentiles may be inflated.");
        for (final double s : c.scales) {
            c.responses.add(response(s, scoreMaps.create(new double[]{s}), example.nodes(), nearExamples));
        }
        final double[] nodeScores = sample(ridge, example.nodes());
        final double[] background = backgroundSample(ridge, nearExamples);
        if (nodeScores.length > 0) {
            c.nodeScoreP10 = percentile(nodeScores, 10);
            c.nodeScoreMedian = percentile(nodeScores, 50);
            // Below the score of the thinnest structures, so that they stay continuous
            c.minScore = c.nodeScoreP10;
        }
        if (background.length > 0) {
            c.backgroundP50 = percentile(background, 50);
            c.backgroundP99 = percentile(background, 99);
        }
        if (c.minScore > 0 && !Double.isNaN(c.backgroundP99) && c.minScore <= c.backgroundP99)
            c.warnings.add("The training paths score no higher than the strongest background in Tubeness: expect "
                    + "false detections or missed tips. Try other Neurite radii or subtracting a background "
                    + "channel. Raising Min. Tubeness score removes false detections but may miss weaker tips.");
        if (!(c.minScore > 0)) {
            c.warnings.add("Tubeness scores along the training paths are not positive, so Min. Tubeness score could "
                    + "not be learned. Check that the paths follow bright structures and that the Neurite radii "
                    + "fit them.");
            c.minScore = Double.NaN;
        }

        // Max. background: above what is seen along the training paths
        if (backgroundImg != null) {
            final double[] backgroundValues = sample(backgroundImg, example.nodes());
            if (backgroundValues.length > 0) c.maxBackground = percentile(backgroundValues, 99);
        }
        return c;
    }

    /**
     * Runs {@link TipFinder} with the learned parameters on the region, and compares its detections to the example
     * tips
     *
     * @param ridge     the score map (as computed for {@link #getNeuriteRadii()})
     * @param examples  the training paths this configuration was learned from
     * @param backgroundImg      the background image, or null
     * @return the evaluation, or null if the parameters are incomplete
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Evaluation evaluate(final RandomAccessibleInterval<? extends RealType<?>> ridge, final TrainingPaths examples,
                               final RandomAccessibleInterval<? extends RealType<?>> backgroundImg) {
        if (Double.isNaN(minScore) || Double.isNaN(analysisRadius)) return null;
        if (spacing == null) throw new IllegalStateException("Voxel size unknown: call setSpacing() first");
        final TipFinder finder = configure(new TipFinder(ridge, spacing), backgroundImg);
        final List<TipFinder.Tip> tips = finder.find();
        final double tolerance = analysisRadius;
        int pathEnds = 0, matched = 0, skipped = 0;
        final boolean[] isNear = new boolean[tips.size()];
        final long[] margin = new long[3];
        for (int d = 0; d < 3; d++) margin[d] = (ridge.dimension(d) == 1) ? 0 : (long) Math.ceil(analysisRadius / spacing[d]) + 1;
        final TipStatus[] status = new TipStatus[(examples.tips() == null) ? 0 : examples.tips().length];
        if (examples.tips() != null) {
            for (int i = 0; i < status.length; i++) {
                final long[] t = examples.tips()[i];
                boolean interior = true;
                for (int d = 0; d < 3; d++) {
                    if (t[d] < ridge.min(d) + margin[d] || t[d] > ridge.max(d) - margin[d]) interior = false;
                }
                if (!interior) {
                    skipped++;
                    status[i] = TipStatus.SKIPPED;
                    continue;
                }
                pathEnds++;
                status[i] = TipStatus.MISSED;
                for (int k = 0; k < tips.size(); k++) {
                    final double[] pos = tips.get(k).position();
                    double d2 = 0;
                    for (int d = 0; d < 3; d++) {
                        final double dd = pos[d] - t[d] * spacing[d];
                        d2 += dd * dd;
                    }
                    if (d2 <= tolerance * tolerance) {
                        isNear[k] = true;
                        if (status[i] != TipStatus.MATCHED) {
                            matched++;
                            status[i] = TipStatus.MATCHED;
                        }
                    }
                }
            }
        }
        int near = 0;
        for (final boolean b : isNear) if (b) near++;
        return new Evaluation(tips, pathEnds, matched, skipped, tolerance, status, near);
    }

    /**
     * Applies the learned parameters to a finder. Values that could not be derived are not changed
     *
     * @param finder the finder to configure
     * @param backgroundImg   the background image, or null
     * @param <T>    the score map type
     * @return the finder
     */
    public <T extends RealType<T>> TipFinder<T> configure(final TipFinder<T> finder,
                                                          final RandomAccessibleInterval<? extends RealType<?>> backgroundImg) {
        if (!Double.isNaN(analysisRadius)) finder.setAnalysisRadius(analysisRadius);
        if (!Double.isNaN(minScore)) finder.setMinScore(minScore);
        finder.setMinLength(minLength).setMinConnectedFraction(minConnectedFraction);
        if (backgroundImg != null && !Double.isNaN(maxBackground)) configureBackground(finder, backgroundImg);
        return finder;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configureBackground(final TipFinder finder, final RandomAccessibleInterval backgroundImg) {
        finder.setBackground(backgroundImg, backgroundRadius).setMaxBackground(maxBackground);
    }

    // -- Persistence --

    /**
     * Gets the parameters needed to run {@link TipDetector} (the statistics learned from the training paths are not
     * included)
     *
     * @return the parameters, to be restored with {@link #fromProperties(java.util.Properties)}
     */
    public java.util.Properties toProperties() {
        final java.util.Properties p = new java.util.Properties();
        final StringBuilder sb = new StringBuilder();
        for (final double s : scales) sb.append((sb.length() == 0) ? "" : ",").append(s);
        p.setProperty("scales", sb.toString());
        p.setProperty("analysisRadius", Double.toString(analysisRadius));
        p.setProperty("minScore", Double.toString(minScore));
        p.setProperty("minLength", Double.toString(minLength));
        p.setProperty("minConnectedFraction", Double.toString(minConnectedFraction));
        p.setProperty("maxBackground", Double.toString(maxBackground));
        p.setProperty("backgroundRadius", Double.toString(backgroundRadius));
        p.setProperty("backgroundChannel", Integer.toString(backgroundChannel));
        p.setProperty("backgroundWeight", Double.toString(backgroundWeight));
        p.setProperty("medianRadius", Double.toString(medianRadius));
        return p;
    }

    /**
     * Restores parameters saved with {@link #toProperties()}
     *
     * @param p the properties
     * @return the configuration
     * @throws IllegalArgumentException if the neurite radii, analysis radius or min. score are missing or invalid
     */
    public static TipDetectionConfig fromProperties(final java.util.Properties p) {
        try {
            final String[] tokens = p.getProperty("scales", "").trim().split("\\s*,\\s*");
            final double[] radii = Arrays.stream(tokens).filter(t -> !t.isEmpty()).mapToDouble(Double::parseDouble)
                    .toArray();
            final TipDetectionConfig c = of(radii, Double.parseDouble(p.getProperty("analysisRadius")),
                    Double.parseDouble(p.getProperty("minScore")), Double.parseDouble(p.getProperty("minLength", "0.6")),
                    Double.parseDouble(p.getProperty("minConnectedFraction", "0.3")),
                    Double.parseDouble(p.getProperty("maxBackground", "NaN")),
                    Double.parseDouble(p.getProperty("backgroundRadius", "NaN")));
            c.setBackground(Integer.parseInt(p.getProperty("backgroundChannel", "0")),
                    Double.parseDouble(p.getProperty("backgroundWeight", "1")));
            c.medianRadius = Double.parseDouble(p.getProperty("medianRadius", "NaN"));
            if (radii.length == 0 || !(c.analysisRadius > 0) || Double.isNaN(c.minScore))
                throw new IllegalArgumentException("Incomplete parameters");
            return c;
        } catch (final NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("Invalid tip detection parameters: " + ex.getMessage(), ex);
        }
    }

    // -- Getters and setters --

    private int backgroundChannel = 0;
    private double backgroundWeight = 1;

    /** @return the (1-based) channel subtracted from the signal before detection, or 0 for none */
    public int getBackgroundChannel() { return backgroundChannel; }
    public double getBackgroundWeight() { return backgroundWeight; }

    /**
     * Sets the background that is subtracted (weighted, clamped to zero) from the signal before detection
     *
     * @param channel the channel (1-based), or 0 for none
     * @param weight  the weight
     */
    public void setBackground(final int channel, final double weight) {
        this.backgroundChannel = Math.max(0, channel);
        this.backgroundWeight = weight;
    }

    public double[] getNeuriteRadii() { return scales.clone(); }
    public double getAnalysisRadius() { return analysisRadius; }
    public double getMinScore() { return minScore; }
    public double getMaxBackground() { return maxBackground; }
    public double getBackgroundRadius() { return backgroundRadius; }
    public double getMinLength() { return minLength; }
    public double getMinConnectedFraction() { return minConnectedFraction; }
    public double getMinRadius() { return minRadius; }
    public double getMedianRadius() { return medianRadius; }
    public double getMaxRadius() { return maxRadius; }
    public double getNodeScoreP10() { return nodeScoreP10; }
    public double getNodeScoreMedian() { return nodeScoreMedian; }
    public double getBackgroundP50() { return backgroundP50; }
    public double getBackgroundP99() { return backgroundP99; }
    public List<ScaleResponse> getScaleResponses() { return Collections.unmodifiableList(responses); }
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

    /** Sets the voxel size {x, y, z}, needed by {@link #evaluate} (set by {@link #learn}) */
    public void setSpacing(final double[] spacing) { this.spacing = (spacing == null) ? null : spacing.clone(); }
    public void setAnalysisRadius(final double analysisRadius) { this.analysisRadius = analysisRadius; }
    public void setMinScore(final double minScore) { this.minScore = minScore; }
    public void setMaxBackground(final double maxBackground) { this.maxBackground = maxBackground; }
    public void setMinLength(final double minLength) { this.minLength = minLength; }
    public void setMinConnectedFraction(final double minConnectedFraction) { this.minConnectedFraction = minConnectedFraction; }

    // -- Sampling helpers --

    private static long[] dims(final RandomAccessibleInterval<?> rai) {
        return rai.dimensionsAsLongArray();
    }

    private static ScaleResponse response(final double scale, final RandomAccessibleInterval<? extends RealType<?>> map,
                                          final long[][] nodes, final BitSet nearExamples) {
        final double[] atNodes = sample(map, nodes);
        final double[] background = backgroundSample(map, nearExamples);
        return new ScaleResponse(scale, (atNodes.length > 0) ? percentile(atNodes, 50) : Double.NaN,
                (atNodes.length > 0) ? percentile(atNodes, 10) : Double.NaN,
                (background.length > 0) ? percentile(background, 50) : Double.NaN,
                (background.length > 0) ? percentile(background, 99) : Double.NaN);
    }

    // Values at the positions that are inside the image, sorted
    private static double[] sample(final RandomAccessibleInterval<? extends RealType<?>> rai, final long[][] positions) {
        final RandomAccess<? extends RealType<?>> ra = rai.randomAccess();
        final double[] values = new double[positions.length];
        int n = 0;
        for (final long[] p : positions) {
            boolean inside = true;
            for (int d = 0; d < 3; d++) inside &= p[d] >= rai.min(d) && p[d] <= rai.max(d);
            if (!inside) continue;
            ra.setPosition(p);
            values[n++] = ra.get().getRealDouble();
        }
        final double[] result = Arrays.copyOf(values, n);
        Arrays.sort(result);
        return result;
    }

    // Marks the voxels (by their linear index in a zero-min grid of the given dimensions) that are within
    // distance (calibrated units) of any node. Null if the grid is too large to be indexed
    private static BitSet markNeighborhood(final long[][] nodes, final long[] dims, final double[] spacing,
                                           final double distance) {
        final long total = dims[0] * dims[1] * dims[2];
        if (total > Integer.MAX_VALUE) return null;
        final BitSet marked = new BitSet((int) total);
        final int[] r = new int[3];
        for (int d = 0; d < 3; d++) r[d] = (int) Math.ceil(distance / spacing[d]);
        for (final long[] n : nodes) {
            for (int dz = -r[2]; dz <= r[2]; dz++) {
                final long z = n[2] + dz;
                if (z < 0 || z >= dims[2]) continue;
                for (int dy = -r[1]; dy <= r[1]; dy++) {
                    final long y = n[1] + dy;
                    if (y < 0 || y >= dims[1]) continue;
                    for (int dx = -r[0]; dx <= r[0]; dx++) {
                        final long x = n[0] + dx;
                        if (x < 0 || x >= dims[0]) continue;
                        final double px = dx * spacing[0], py = dy * spacing[1], pz = dz * spacing[2];
                        if (px * px + py * py + pz * pz <= distance * distance)
                            marked.set((int) (x + dims[0] * (y + dims[1] * z)));
                    }
                }
            }
        }
        return marked;
    }

    // Strided sample of the voxels that are not marked, sorted
    private static double[] backgroundSample(final RandomAccessibleInterval<? extends RealType<?>> rai,
                                             final BitSet exclude) {
        final long[] dims = dims(rai);
        final long total = dims[0] * dims[1] * dims[2];
        final int stride = (int) Math.max(1, Math.round(Math.cbrt(total / 200_000d)));
        final RandomAccess<? extends RealType<?>> ra = rai.randomAccess();
        final List<Double> values = new ArrayList<>();
        for (long z = 0; z < dims[2]; z += stride) {
            for (long y = 0; y < dims[1]; y += stride) {
                for (long x = 0; x < dims[0]; x += stride) {
                    if (exclude != null && exclude.get((int) (x + dims[0] * (y + dims[1] * z)))) continue;
                    ra.setPosition(new long[]{rai.min(0) + x, rai.min(1) + y, rai.min(2) + z});
                    values.add(ra.get().getRealDouble());
                }
            }
        }
        final double[] result = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(result);
        return result;
    }

    // Percentile of sorted values
    static double percentile(final double[] sorted, final double p) {
        return sorted[(int) Math.round(p / 100d * (sorted.length - 1))];
    }
}
