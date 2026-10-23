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

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import org.jetbrains.annotations.UnknownNullability;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathFitter;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;

import java.util.*;

/**
 * Derives {@link AbstractGWDTTracer} configuration parameters from one or more
 * example paths. This is the "learn from example" mechanism: the user traces or
 * selects a representative neurite, and this class extracts morphometric and
 * signal properties to autofill tracer settings.
 * <p>
 * Usage:
 * <pre>{@code
 *   AutoTraceConfig config = AutoTraceConfig.fromPaths(paths, sourceImage, spacing);
 *   config.applyTo(tracer);
 * }</pre>
 * <p>
 * If input paths lack fitted radii, a non-destructive fit is attempted
 * automatically ({@link PathFitter#setReplaceNodes(boolean)} = false).
 *
 * @author Tiago Ferreira
 */
public class AutoTraceConfig {

    // Derived values (NaN = could not be derived)
    private double[] scoreMapScales;
    private double backgroundThreshold = Double.NaN;
    private double minBranchIntensityLength = Double.NaN;
    private double branchTuneMaxAngle = Double.NaN;
    private double reconnectMinContraction = Double.NaN;
    private double reconnectMaxAngleDeg = Double.NaN;
    private double reconnectMaxBridgeDist = Double.NaN;
    private double resampleStep = Double.NaN;
    private final List<String> recommendations = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private double sphereOverlapThreshold = Double.NaN;

    // Source statistics (for reporting)
    private int pathCount;
    private int nodeCount;
    private double meanRadius = Double.NaN;
    private double minRadius = Double.NaN;
    private double maxRadius = Double.NaN;
    private double meanIntensity = Double.NaN;
    private double meanContraction = Double.NaN;

    // Above this number of voxels, speed-oriented recommendations are made
    private static final long LARGE_IMAGE_VOXELS = 50_000_000L;

    private AutoTraceConfig() {}

    /**
     * Derives auto-trace configuration from one or more example paths.
     *
     * @param paths   representative paths (at least one required)
     * @param source  the grayscale image being traced
     * @param spacing voxel dimensions [x, y, z] in physical units
     * @return a new configuration with derived parameters
     * @throws IllegalArgumentException if paths is null or empty
     */
    public static AutoTraceConfig fromPaths(
            final List<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing) {
        return fromPaths(paths, source, spacing, null, false);
    }

    /**
     * Derives auto-trace configuration from one or more example paths, whose coordinates are not necessarily
     * anchored at the origin of the image: the voxel in {@code source} that corresponds to a node is
     * {@code node / spacing + voxelOffset} (see {@code SNT#getActiveCanvasPixelOffset()}).
     *
     * @param paths       representative paths (at least one required)
     * @param source      the grayscale image being traced
     * @param spacing     voxel dimensions [x, y, z] in physical units
     * @param voxelOffset the offset [x, y, z], in voxels, between the coordinate frame of the paths and the grid of
     *                    the image (null or empty: none)
     * @param streamed    whether the image is a (lazily loaded) streamed source, in which case recommendations
     *                    account for the cost of reading it
     * @return a new configuration with derived parameters
     * @throws IllegalArgumentException if paths is null or empty
     */
    public static AutoTraceConfig fromPaths(
            final List<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing,
            final double[] voxelOffset,
            final boolean streamed) {
        final double[] offset = normalizedOffset(voxelOffset);
        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("At least one path is required");

        final AutoTraceConfig config = new AutoTraceConfig();
        config.pathCount = paths.size();

        // Step 1: Ensure radii are available (fit if needed)
        final List<Path> workingPaths = ensureRadii(paths, source, spacing, offset);

        // Step 2: Collect radii
        final List<Double> radii = collectRadii(workingPaths);
        config.nodeCount = countNodes(workingPaths);

        if (!radii.isEmpty()) {
            Collections.sort(radii);
            final int n = radii.size();
            config.minRadius = radii.getFirst();
            config.maxRadius = radii.getLast();
            config.meanRadius = radii.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

            // Score map scales from percentiles (matching deriveScalesFromRadii logic)
            config.scoreMapScales = deriveScales(radii);

            // Length threshold: scale-aware minimum. A branch shorter than the
            // thinnest observed radius is likely noise. Use p10 as floor.
            final double p10 = radii.get(Math.max(0, n / 10));
            config.minBranchIntensityLength = Math.max(3.0, p10 / avgSpacing(spacing));
        }

        // Step 3: Collect contraction
        final List<Double> contractions = new ArrayList<>();
        for (final Path p : workingPaths) {
            final double c = p.getContraction();
            if (!Double.isNaN(c) && c > 0) contractions.add(c);
        }
        if (!contractions.isEmpty()) {
            config.meanContraction = contractions.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            // Set reconnect threshold at 80% of observed minimum contraction
            // real neurites are at least this straight, so bridges should be too
            final double minContraction = contractions.stream().mapToDouble(Double::doubleValue).min().orElse(0.5);
            config.reconnectMinContraction = Math.max(0.3, minContraction * 0.8);
        }

        // Step 4: Sample intensity along paths
        config.meanIntensity = sampleMeanIntensity(workingPaths, source, spacing, offset);
        if (!Double.isNaN(config.meanIntensity)) {
            // Background threshold: use a fraction of the mean path intensity.
            // Paths run through bright signal, so background should be well below.
            config.backgroundThreshold = config.meanIntensity * 0.25;
        }

        // Step 5: Branch angles (from paths with parent relationships)
        final List<Double> branchAngles = collectBranchAngles(workingPaths);
        if (!branchAngles.isEmpty()) {
            // Use the 90th percentile of observed fork angles as the tuning threshold
            Collections.sort(branchAngles);
            final int idx90 = (int) (branchAngles.size() * 0.9);
            config.branchTuneMaxAngle = branchAngles.get(Math.min(idx90, branchAngles.size() - 1));
        }

        // Step 6: Reconnect bridge distance from max radius
        if (!Double.isNaN(config.maxRadius)) {
            // Max gap we'd expect: a few diameters of the thickest structure
            config.reconnectMaxBridgeDist = Math.max(10, (config.maxRadius / avgSpacing(spacing)) * 4);
        }

        // Step 7: Reconnect angle from branch angle or default
        if (!Double.isNaN(config.branchTuneMaxAngle)) {
            config.reconnectMaxAngleDeg = Math.min(90, config.branchTuneMaxAngle * 1.2);
        }

        // Step 8: Resampling interval from the node density of the example paths
        config.resampleStep = deriveResampleStep(workingPaths, spacing);

        // Step 9: Tolerated sphere overlap between distinct paths
        config.sphereOverlapThreshold = deriveSphereOverlap(workingPaths);

        config.deriveNotes(source, spacing, streamed);

        return config;
    }

    // Image-based recommendations and warnings about values that could not be derived. These are advice only
    private void deriveNotes(final RandomAccessibleInterval<?> source, final double[] spacing,
                             final boolean streamed) {
        final long[] dims = Intervals.dimensionsAsLongArray(source);
        long voxels = 1;
        for (final long d : dims) voxels *= d;
        final String backend = GWDTTracerFactory.recommendBackend(dims);
        final boolean large = voxels >= LARGE_IMAGE_VOXELS || !"array".equals(backend);
        if (large && streamed) {
            recommendations.add(String.format("The image is streamed and very large (%,d voxels): trace a "
                    + "materialized crop around the structures of interest, as tracing the full volume would be slow.",
                    voxels));
        } else if (large) {
            recommendations.add(String.format("The image is large (%,d voxels; %s storage would be used): "
                    + "'Low' connectivity is the fastest option.", voxels, backend));
            recommendations.add("On large images the score map filter is the most expensive step: "
                    + "'None' is faster if the signal is clean.");
        }
        if (!Double.isNaN(minRadius) && minRadius / avgSpacing(spacing) <= 1.0) {
            recommendations.add("The thinnest example structures are about 1 voxel wide: "
                    + "'High' connectivity better preserves thin neurites, at the cost of speed.");
        }
        if (Double.isNaN(meanRadius))
            warnings.add("Radii could not be determined: score map scales, min. branch score, tip extension distance "
                    + "and overlap tolerance are unavailable.");
        if (Double.isNaN(meanIntensity))
            warnings.add("Intensities could not be sampled: background threshold is unavailable.");
        if (Double.isNaN(branchTuneMaxAngle))
            warnings.add("No branch points among the example paths: max. branching angle is unavailable.");
        if (!Double.isNaN(meanRadius) && Double.isNaN(sphereOverlapThreshold))
            warnings.add("Example paths do not touch each other: overlap tolerance is unavailable.");
        if (pathCount < 3 || nodeCount < 30)
            warnings.add("Few example paths/nodes (" + pathCount + " paths, " + nodeCount
                    + " nodes): the learned values may be poorly supported. Select more representative paths.");
    }

    /**
     * Adds a warning to be reported along with this configuration.
     *
     * @param warning the warning text
     */
    public void addWarning(final String warning) { warnings.add(warning); }

    /**
     * Fraction of the nodes of the paths that fall within the bounds of an image, using the same
     * node-to-voxel convention as the rest of this class (calibrated coordinates divided by the voxel size).
     *
     * @param paths   the paths to test
     * @param source  the image
     * @param spacing voxel dimensions [x, y, z] in physical units
     * @return a value in [0, 1]; NaN if the paths have no nodes
     */
    public static double fractionInBounds(final List<Path> paths,
                                          final RandomAccessibleInterval<?> source, final double[] spacing) {
        return fractionInBounds(paths, source, spacing, null);
    }

    /**
     * As {@link #fractionInBounds(List, RandomAccessibleInterval, double[])}, for paths whose coordinate frame is
     * offset from the image grid: {@code voxel = node / spacing + voxelOffset}.
     *
     * @param paths       the paths to test
     * @param source      the image
     * @param spacing     voxel dimensions [x, y, z] in physical units
     * @param voxelOffset the offset [x, y, z], in voxels (null or empty: none)
     * @return a value in [0, 1]; NaN if the paths have no nodes
     */
    public static double fractionInBounds(final List<Path> paths, final RandomAccessibleInterval<?> source,
                                          final double[] spacing, final double[] voxelOffset) {
        final double[] off = normalizedOffset(voxelOffset);
        final int nDims = source.numDimensions();
        int inside = 0, total = 0;
        for (final Path p : paths) {
            for (int i = 0; i < p.size(); i++) {
                final PointInImage node = p.getNode(i);
                final long x = Math.round(node.x / spacing[0] + off[0]);
                final long y = Math.round(node.y / spacing[1] + off[1]);
                final long z = (nDims > 2) ? Math.round(node.z / spacing[2] + off[2]) : source.min(0);
                total++;
                if (x >= source.min(0) && x <= source.max(0) && y >= source.min(1) && y <= source.max(1)
                        && (nDims < 3 || (z >= source.min(2) && z <= source.max(2)))) inside++;
            }
        }
        return (total == 0) ? Double.NaN : inside / (double) total;
    }

    /**
     * Returns image-based suggestions (e.g., connectivity) that cannot be learned from the paths.
     * They are advice only and are never applied automatically.
     *
     * @return the recommendations (possibly empty)
     */
    public List<String> getRecommendations() { return Collections.unmodifiableList(recommendations); }

    /**
     * Returns warnings about values that could not be derived or are weakly supported.
     *
     * @return the warnings (possibly empty)
     */
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

    /**
     * Applies this configuration to a tracer, setting all derived parameters.
     * Parameters that could not be derived (NaN) are left at the tracer's
     * current defaults.
     *
     * @param <T>    pixel type
     * @param tracer the tracer to configure
     */
    public <T extends RealType<T>> void applyTo(final AbstractGWDTTracer<T> tracer) {
        if (!Double.isNaN(backgroundThreshold))
            tracer.setBackgroundThreshold(backgroundThreshold);
        if (!Double.isNaN(minBranchIntensityLength))
            tracer.setMinBranchIntensityLength(minBranchIntensityLength);
        if (!Double.isNaN(branchTuneMaxAngle))
            tracer.setBranchTuneMaxAngle(branchTuneMaxAngle);
        if (!Double.isNaN(resampleStep))
            tracer.setResampleStep(resampleStep);
        if (!Double.isNaN(sphereOverlapThreshold))
            tracer.setSphereOverlapThreshold(sphereOverlapThreshold);
        if (scoreMapScales != null && scoreMapScales.length > 0) {
            tracer.setScoreMapEnabled(true);
            tracer.setScoreMapScales(scoreMapScales);
        }
    }

    /**
     * Returns a human-readable summary of the derived configuration, suitable
     * for display in a dialog or log.
     *
     * @return multi-line summary string
     */
    public String getSummary() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Derived from ").append(pathCount).append(" path(s), ")
          .append(nodeCount).append(" nodes:\n");
        if (!Double.isNaN(meanRadius))
            sb.append("  Radius: ").append(fmt(minRadius)).append(" – ")
              .append(fmt(maxRadius)).append(" (mean ").append(fmt(meanRadius)).append(")\n");
        if (scoreMapScales != null)
            sb.append("  Score map scales: ").append(Arrays.toString(scoreMapScales)).append("\n");
        if (!Double.isNaN(meanIntensity))
            sb.append("  Mean intensity: ").append(fmt(meanIntensity))
              .append(" → background threshold: ").append(fmt(backgroundThreshold)).append("\n");
        if (!Double.isNaN(meanContraction))
            sb.append("  Mean contraction: ").append(fmt(meanContraction))
              .append(" → reconnect min: ").append(fmt(reconnectMinContraction)).append("\n");
        if (!Double.isNaN(branchTuneMaxAngle))
            sb.append("  Branch tune max angle: ").append(fmt(branchTuneMaxAngle)).append("°\n");
        if (!Double.isNaN(minBranchIntensityLength))
            sb.append("  Min branch intensity-length: ").append(fmt(minBranchIntensityLength)).append("\n");
        if (!Double.isNaN(reconnectMaxBridgeDist))
            sb.append("  Max bridge distance: ").append(fmt(reconnectMaxBridgeDist)).append(" voxels\n");
        if (!Double.isNaN(resampleStep))
            sb.append("  Resample interval: ").append(fmt(resampleStep)).append(" voxels\n");
        if (!Double.isNaN(sphereOverlapThreshold))
            sb.append("  Overlap tolerance: ").append(fmt(sphereOverlapThreshold)).append("\n");
        return sb.toString();
    }

    // Getters
    public double[] getScoreMapScales() { return scoreMapScales; }
    public double getBackgroundThreshold() { return backgroundThreshold; }
    public double getMinBranchIntensityLength() { return minBranchIntensityLength; }
    public double getBranchTuneMaxAngle() { return branchTuneMaxAngle; }
    public double getReconnectMinContraction() { return reconnectMinContraction; }
    public double getReconnectMaxAngleDeg() { return reconnectMaxAngleDeg; }
    public double getReconnectMaxBridgeDist() { return reconnectMaxBridgeDist; }
    public double getResampleStep() { return resampleStep; }
    public double getSphereOverlapThreshold() { return sphereOverlapThreshold; }
    public double getMeanRadius() { return meanRadius; }
    public double getMeanIntensity() { return meanIntensity; }
    public double getMeanContraction() { return meanContraction; }
    public int getPathCount() { return pathCount; }
    public int getNodeCount() { return nodeCount; }

    /**
     * Ensures all paths have radii. For paths without radii, attempts a
     * non-destructive fit. Returns a list of paths that have radii (fitted
     * versions where applicable, originals otherwise).
     */
    private static List<Path> ensureRadii(
            final List<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing, final double[] offset) {
        final List<Path> result = new ArrayList<>(paths.size());
        final boolean offsetNeeded = offset[0] != 0 || offset[1] != 0 || offset[2] != 0;
        for (final Path p : paths) {
            if (p.hasRadii()) {
                result.add(p);
            } else {
                // Attempt non-destructive fit. The fitter reads the voxels from the canvas offset of the path: it is
                // set temporarily if the grid of the image is offset from the coordinates of the path
                final PointInCanvas previousOffset = p.getCanvasOffset();
                if (offsetNeeded) p.setCanvasOffset(new PointInCanvas(offset[0], offset[1], offset[2]));
                try {
                    final PathFitter fitter = new PathFitter(source, p,
                            spacing[0], spacing[1],
                            spacing.length > 2 ? spacing[2] : 1.0, "");
                    fitter.setReplaceNodes(false);
                    final Path fitted = fitter.call();
                    if (fitted != null && fitted.hasRadii()) {
                        result.add(fitted);
                    } else {
                        result.add(p); // use as-is
                    }
                } catch (final Exception e) {
                    SNTUtils.log("AutoTraceConfig: fit failed for path " + p.getID() + ": " + e.getMessage());
                    result.add(p);
                } finally {
                    if (offsetNeeded && previousOffset != null) p.setCanvasOffset(previousOffset);
                }
            }
        }
        return result;
    }

    private static List<Double> collectRadii(final List<Path> paths) {
        final List<Double> radii = new ArrayList<>();
        for (final Path p : paths) {
            if (!p.hasRadii()) continue;
            for (int i = 0; i < p.size(); i++) {
                final double r = p.getNode(i).getRadius();
                if (r > 0) radii.add(r);
            }
        }
        return radii;
    }

    private static int countNodes(final List<Path> paths) {
        int count = 0;
        for (final Path p : paths) count += p.size();
        return count;
    }

    /**
     * Derives filter scales from sorted radii using percentiles, with
     * deduplication (values within 10% are merged).
     */
    private static double[] deriveScales(final List<Double> sortedRadii) {
        if (sortedRadii.size() < 4) {
            // Too few: use mean as single scale
            final double mean = sortedRadii.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            return new double[]{ mean };
        }
        final int n = sortedRadii.size();
        final double p25 = sortedRadii.get(n / 4);
        final double p50 = sortedRadii.get(n / 2);
        final double p75 = sortedRadii.get(3 * n / 4);

        final List<Double> scales = new ArrayList<>();
        scales.add(p25);
        if (p50 > p25 * 1.1) scales.add(p50);
        if (p75 > scales.getLast() * 1.1) scales.add(p75);

        return scales.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Samples source image intensity at each node position and returns the mean.
     */
    @SuppressWarnings("unchecked")
    private static <T extends RealType<T>> double sampleMeanIntensity(
            final List<Path> paths,
            final @UnknownNullability RandomAccessibleInterval<? extends RealType<?>> source,
            final double[] spacing, final double[] offset) {
        final RandomAccess<T> ra = (RandomAccess<T>) source.randomAccess();
        final int nDims = source.numDimensions();
        final long[] pos = new long[nDims];
        double sum = 0;
        int count = 0;

        for (final Path p : paths) {
            for (int i = 0; i < p.size(); i++) {
                final PointInImage node = p.getNode(i);
                pos[0] = Math.round(node.x / spacing[0] + offset[0]);
                pos[1] = Math.round(node.y / spacing[1] + offset[1]);
                if (nDims > 2) pos[2] = Math.round(node.z / spacing[2] + offset[2]);

                // Bounds check
                boolean inBounds = true;
                for (int d = 0; d < nDims; d++) {
                    if (pos[d] < source.min(d) || pos[d] > source.max(d)) {
                        inBounds = false;
                        break;
                    }
                }
                if (inBounds) {
                    ra.setPosition(pos);
                    sum += ra.get().getRealDouble();
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    /**
     * Collects branch/fork angles from paths that have parent relationships.
     * Returns angles in degrees.
     */
    private static List<Double> collectBranchAngles(final List<Path> paths) {
        final List<Double> angles = new ArrayList<>();
        for (final Path p : paths) {
            if (p.getParentPath() == null || p.size() < 2) continue;
            // This path branches off a parent: compute extension angle
            final PointInImage start = p.getNode(0);
            // Use first few nodes for tangent direction
            final int tangentLen = Math.min(5, p.size());
            final PointInImage tangentEnd = p.getNode(tangentLen - 1);

            // Get parent path and its direction at the junction
            final Path parent = p.getParentPath();
            final PointInImage joinPt = p.getBranchPoint();
            final int joinIdx = parent.indexNearestTo(joinPt.x, joinPt.y, joinPt.z, Double.MAX_VALUE);
            if (joinIdx < 0 || joinIdx >= parent.size() - 1) continue;

            // Parent direction at junction
            final int parentTangentStart = Math.max(0, joinIdx - 4);
            final PointInImage parentPt = parent.getNode(parentTangentStart);
            final PointInImage junctionPt = parent.getNode(joinIdx);

            // Vectors
            final double px = junctionPt.x - parentPt.x;
            final double py = junctionPt.y - parentPt.y;
            final double pz = junctionPt.z - parentPt.z;
            final double cx = tangentEnd.x - start.x;
            final double cy = tangentEnd.y - start.y;
            final double cz = tangentEnd.z - start.z;

            final double dot = px * cx + py * cy + pz * cz;
            final double magP = Math.sqrt(px * px + py * py + pz * pz);
            final double magC = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (magP > 0 && magC > 0) {
                final double cosAngle = Math.clamp(dot / (magP * magC), -1, 1);
                angles.add(Math.toDegrees(Math.acos(cosAngle)));
            }
        }
        return angles;
    }

    /**
     * Median distance between consecutive nodes, in voxels, rounded to an integer
     * in [1, 10]. NaN if no distances are available.
     */
    private static double deriveResampleStep(final List<Path> paths, final double[] spacing) {
        final double avg = avgSpacing(spacing);
        final List<Double> steps = new ArrayList<>();
        for (final Path p : paths) {
            for (int i = 1; i < p.size(); i++) {
                final double d = p.getNode(i).distanceTo(p.getNode(i - 1));
                if (d > 0) steps.add(d / avg);
            }
        }
        if (steps.isEmpty()) return Double.NaN;
        Collections.sort(steps);
        final double median = steps.get(steps.size() / 2);
        return Math.clamp(Math.round(median), 1, 10);
    }

    // Upper bound on the number of nodes compared (pairwise) when estimating overlaps
    private static final int MAX_OVERLAP_NODES = 4000;

    /**
     * Estimates the sphere overlap tolerated between distinct example paths: for
     * each node, the largest fraction of its sphere covered by a single node of
     * another path (lens volume). The 90th percentile of the non-zero fractions
     * is returned, limited to [0.05, 0.5]. NaN if the paths have no radii or do
     * not touch each other.
     */
    private static double deriveSphereOverlap(final List<Path> paths) {
        final List<double[]> nodes = new ArrayList<>(); // x, y, z, r, pathIdx
        for (int pi = 0; pi < paths.size(); pi++) {
            final Path p = paths.get(pi);
            if (!p.hasRadii()) continue;
            for (int i = 0; i < p.size(); i++) {
                final Path.PathNode n = p.getNode(i);
                if (n.getRadius() > 0) nodes.add(new double[]{n.x, n.y, n.z, n.getRadius(), pi});
            }
        }
        if (nodes.size() < 2) return Double.NaN;
        final int stride = Math.max(1, (int) Math.ceil(nodes.size() / (double) MAX_OVERLAP_NODES));
        final List<Double> fractions = new ArrayList<>();
        for (int a = 0; a < nodes.size(); a += stride) {
            final double[] na = nodes.get(a);
            double best = 0;
            for (final double[] nb : nodes) {
                if (nb[4] == na[4]) continue;
                final double dx = na[0] - nb[0], dy = na[1] - nb[1], dz = na[2] - nb[2];
                final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                best = Math.max(best, lensFraction(na[3], nb[3], d));
            }
            if (best > 0) fractions.add(best);
        }
        if (fractions.isEmpty()) return Double.NaN;
        Collections.sort(fractions);
        final double p90 = fractions.get(Math.min(fractions.size() - 1, (int) (fractions.size() * 0.9)));
        return Math.clamp(p90, 0.05, 0.5);
    }

    // Fraction of the volume of sphere A (radius ra) that is inside sphere B (radius rb)
    private static double lensFraction(final double ra, final double rb, final double d) {
        if (d >= ra + rb) return 0;
        if (d <= Math.abs(ra - rb)) return (ra <= rb) ? 1 : (rb * rb * rb) / (ra * ra * ra);
        final double lens = Math.PI * Math.pow(ra + rb - d, 2)
                * (d * d + 2 * d * rb - 3 * rb * rb + 2 * d * ra + 6 * ra * rb - 3 * ra * ra) / (12 * d);
        return lens / (4.0 / 3.0 * Math.PI * ra * ra * ra);
    }

    // [x, y, z] offset, in voxels: zeros if none was specified
    private static double[] normalizedOffset(final double[] voxelOffset) {
        final double[] off = new double[3];
        if (voxelOffset != null) System.arraycopy(voxelOffset, 0, off, 0, Math.min(3, voxelOffset.length));
        return off;
    }

    private static double avgSpacing(final double[] spacing) {
        double sum = 0;
        for (final double s : spacing) sum += s;
        return sum / spacing.length;
    }

    private static String fmt(final double v) {
        return SNTUtils.formatDouble(v, 2);
    }
}
