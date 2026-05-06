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

import ij.ImagePlus;
import ij.ImageStack;
import net.imagej.ImgPlus;
import net.imglib2.type.numeric.NumericType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.filter.SpectralSimilarity;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Post-hoc refinement of traced paths in multispectral (e.g., Brainbow) images.
 * <p>
 * For each path, the algorithm computes a reference color vector (the average intensity
 * across all channels at each node), then iteratively adjusts node positions and radii
 * to minimize a cost function combining:
 * <ol>
 *   <li><b>Intensity penalty</b> fraction of voxels in the node's sphere whose total
 *       intensity falls outside the expected range</li>
 *   <li><b>Color penalty</b> fraction of voxels whose spectral angle (cosine similarity)
 *       to the reference color vector is below threshold</li>
 *   <li><b>Radius penalty</b> {@code 1/r²}, favoring compact cross-sections</li>
 * </ol>
 * This is an SNT-native re-implementation of the nCorrect algorithm described in:
 * <blockquote>
 *   Azzouz, S et al., "Optimized Neuron Tracing Using Post Hoc Reanalysis."
 *   <a href="https://www.biorxiv.org/content/10.1101/2022.10.10.511642">doi:10.1101/2022.10.10.511642</a>
 * </blockquote>
 * The minimum path-length quality gate is informed by:
 * <blockquote>
 *   Leiwe et al., "Automated neuronal reconstruction with super-multicolour Tetbow
 *   labelling and threshold-based clustering of colour hues", <i>Nat Commun</i> 15,
 *   5279 (2024). <a href="https://doi.org/10.1038/s41467-024-49455-y">doi:10.1038/s41467-024-49455-y</a>
 * </blockquote>
 * <p>
 * Like {@link sc.fiji.snt.PathFitter}, each instance operates on a single {@link Path}.
 * {@link #call()} is thread-safe for parallel execution; {@link #apply()} must be called
 * sequentially to commit results back to the original path.
 * </p>
 *
 * @author Tiago Ferreira
 * @see sc.fiji.snt.PathFitter
 */
public class MultiSpectralRefiner implements Callable<Path> {

	static { net.imagej.patcher.LegacyInjector.preinit(); }

	// Cost function weights (nCorrect defaults)
	private double intensityWeight = 1.00;
    private double colorWeight = 0.85;
    private double radiusWeight = 3.75;

    // Thresholds (defaults auto-calibrated for 16-bit in constructor)
    private double cosSimilarityThreshold = 0.90;
    private double backgroundThreshold = 0.47;
    private double minIntensityThreshold;
    private double maxIntensityThreshold;
    private double minPercentC = 0.05;
    private double maxPercentC = 0.30;
    private int maxRadius = 12;
    private int maxIterations = 50;
    private double convergenceThreshold = 0.001; // stop when improvement < 0.1%
    private int referenceWindowRadius = -1; // -1 = global (all nodes), >0 = sliding window of ±N nodes
    private boolean autoTune = false; // whether to auto-tune parameters from path/image statistics

    // Image data
    private final ImageStack[] channelStacks;
    private final int nChannels;
    private final int imgWidth;  // image width (columns, ImageJ x)
    private final int imgHeight; // image height (rows, ImageJ y)
    private final int imgDepth;  // image depth (slices, ImageJ z)
    private final double xSpacing;
    private final double ySpacing;
    private final double zSpacing;

    // Path data
    private final Path path;

    // Results
    private Path refined;
    private boolean succeeded;
    private double initialPenalty;
    private double finalPenalty;
    private double[] initialNodeCosts; // per-node cost before refinement
    private double[] finalNodeCosts;   // per-node cost after refinement

    // Working data (populated during call())
    private ArrayList<int[]> workingNodes; // [x, y, z, radius] in pixel coords
    private double[] colorRef; // global reference color vector (unit-normalized), used when window disabled
    private double[][] localColorRefs; // per-node reference color vectors (unit-normalized), null if global
    private double[] localRawRefSums; // per-node unnormalized channel sums, null if global
    private boolean[] activeChannels; // channels included in cost computation
    private double rawRefSum; // unnormalized channel sum of reference color (for intensity checks)

    // Voxel read cache: avoids re-reading the same voxels across iterations.
    // Key = packed position (z * W * H + y * W + x), value = channel intensities.
    private HashMap<Long, double[]> voxelCache;

    // Precomputed anisotropy ratios (squared) for ellipsoidal sampling.
    // A sphere of radius r in physical space maps to an ellipsoid in pixel space:
    //   (dx*xSpacing)² + (dy*ySpacing)² + (dz*zSpacing)² < (r*xSpacing)²
    // Dividing by xSpacing²:  dx² + dy²*(ySpacing/xSpacing)² + dz²*(zSpacing/xSpacing)² < r²
    private double yAniso2; // (ySpacing / xSpacing)²
    private double zAniso2; // (zSpacing / xSpacing)²

    /**
     * Creates a refiner for a single path using an ImagePlus.
     *
     * @param imp  the multichannel image (Brainbow, etc.)
     * @param path the path to refine
     * @throws IllegalArgumentException if the image has fewer than 2 channels
     */
    public MultiSpectralRefiner(final ImagePlus imp, final Path path) {
        if (imp.getNChannels() < 2)
            throw new IllegalArgumentException("Multispectral refinement requires at least 2 channels");
        if (path == null || path.size() < 2)
            throw new IllegalArgumentException("Path must have at least 2 nodes");
        this.path = path;
        this.nChannels = imp.getNChannels();
        this.imgWidth = imp.getWidth();
        this.imgHeight = imp.getHeight();
        this.imgDepth = imp.getNSlices();
        this.xSpacing = imp.getCalibration().pixelWidth;
        this.ySpacing = imp.getCalibration().pixelHeight;
        this.zSpacing = imp.getCalibration().pixelDepth;
        this.channelStacks = new ImageStack[nChannels];
        for (int i = 1; i <= nChannels; i++) {
            channelStacks[i - 1] = ImpUtils.getChannelStack(imp, i);
        }
        // Auto-calibrate intensity thresholds based on bit depth.
        // nCorrect defaults (10000/85000) were tuned for 16-bit 3-channel images
        // where max channel sum = 65535*3 = 196605. Those defaults correspond to
        // ~5% and ~43% of the maximum possible channel sum. We preserve these
        // proportions for other bit depths and channel counts
        final double maxChannelSum = (Math.pow(2, imp.getBitDepth()) - 1) * nChannels;
        this.minIntensityThreshold = 0.05 * maxChannelSum;
        this.maxIntensityThreshold = 0.43 * maxChannelSum;

        // Precompute anisotropy ratios for ellipsoidal sphere sampling
        // When spacings are equal these reduce to 1.0 (isotropic, no overhead).
        final double yRatio = ySpacing / xSpacing;
        final double zRatio = zSpacing / xSpacing;
        this.yAniso2 = yRatio * yRatio;
        this.zAniso2 = zRatio * zRatio;
    }

    /**
     * Creates a refiner for a single path using an ImgPlus.
     *
     * @param img  the multichannel image (Brainbow, etc.)
     * @param path the path to refine
     * @throws IllegalArgumentException if the image has fewer than 2 channels
     * @see ImgUtils#toImagePlus(ImgPlus)
     */
    public <T extends NumericType<T>> MultiSpectralRefiner(final ImgPlus<T> img, final Path path) {
        this(ImgUtils.toImagePlus(img), path);
    }

    /**
     * Sets the weight for the intensity background penalty term.
     *
     * @param weight the weight (default 1.00)
     */
    public void setIntensityWeight(final double weight) {
        this.intensityWeight = weight;
    }

    /**
     * Sets the weight for the color (cosine similarity) penalty term.
     *
     * @param weight the weight (default 0.85)
     */
    public void setColorWeight(final double weight) {
        this.colorWeight = weight;
    }

    /**
     * Sets the weight for the radius expansion penalty term.
     *
     * @param weight the weight (default 3.75)
     */
    public void setRadiusWeight(final double weight) {
        this.radiusWeight = weight;
    }

    /**
     * Sets the cosine similarity threshold below which a voxel is considered
     * spectrally dissimilar to the reference color.
     *
     * @param threshold the threshold (default 0.90)
     */
    public void setCosSimilarityThreshold(final double threshold) {
        this.cosSimilarityThreshold = threshold;
    }

    /**
     * Sets the background fraction threshold. If more than this fraction of
     * voxels in a node's sphere fail the intensity test, the node is considered
     * to be in the background.
     *
     * @param threshold the threshold (default 0.47)
     */
    public void setBackgroundThreshold(final double threshold) {
        this.backgroundThreshold = threshold;
    }

    /**
     * Sets the intensity range for adaptive percent-C computation.
     *
     * @param minIntensity lower bound (total channel sum); default 10000
     * @param maxIntensity upper bound (total channel sum); default 85000
     */
    public void setIntensityRange(final double minIntensity, final double maxIntensity) {
        this.minIntensityThreshold = minIntensity;
        this.maxIntensityThreshold = maxIntensity;
    }

    /**
     * Sets the percent-C range for adaptive intensity tolerance.
     *
     * @param minPercent tolerance at high intensities (default 0.05)
     * @param maxPercent tolerance at low intensities (default 0.30)
     */
    public void setPercentCRange(final double minPercent, final double maxPercent) {
        this.minPercentC = minPercent;
        this.maxPercentC = maxPercent;
    }

    /**
     * Sets the maximum radius (in pixels) to test during radius optimization.
     *
     * @param maxRadius the max radius (default 12)
     */
    public void setMaxRadius(final int maxRadius) {
        this.maxRadius = maxRadius;
    }

    /**
     * Sets the maximum number of iterations for the refinement loop.
     * The loop also stops early if convergence is reached.
     *
     * @param maxIterations the iteration cap (default 50)
     */
    public void setMaxIterations(final int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * Sets the convergence threshold for the refinement loop. The loop stops
     * when the relative improvement per iteration falls below this fraction
     * (e.g., 0.001 = stop when improvement is less than 0.1% of total penalty).
     *
     * @param threshold the relative improvement threshold (default 0.001)
     */
    public void setConvergenceThreshold(final double threshold) {
        this.convergenceThreshold = threshold;
    }

    /**
     * Sets the sliding-window radius for per-node reference color computation.
     * When enabled, each node's reference color is the average of the ±N
     * neighboring nodes along the path, rather than a single global average.
     * This handles color drift along long axons in Brainbow images.
     *
     * @param windowRadius number of nodes on each side to include. Use -1
     *                     (default) for a single global reference, or a
     *                     positive value (e.g., 10–30) for sliding window.
     */
    public void setReferenceWindowRadius(final int windowRadius) {
        this.referenceWindowRadius = windowRadius;
    }

    /**
     * Enables automatic parameter tuning from path and image statistics.
     * When enabled, {@link #call()} will adjust {@code maxRadius},
     * {@code cosSimilarityThreshold}, and intensity thresholds based on the
     * actual data before running the optimization loop.
     *
     * @param autoTune true to enable (default false)
     */
    public void setAutoTune(final boolean autoTune) {
        this.autoTune = autoTune;
    }

    /**
     * Returns the total penalty of the path before refinement.
     * Only valid after {@link #call()} has been invoked.
     */
    public double getInitialPenalty() {
        return initialPenalty;
    }

    /**
     * Returns the total penalty of the path after refinement.
     * Only valid after {@link #call()} has been invoked.
     */
    public double getFinalPenalty() {
        return finalPenalty;
    }

    /**
     * Whether the refinement succeeded (penalty decreased or path was already optimal).
     */
    public boolean succeeded() {
        return succeeded;
    }

    /**
     * Returns the per-node cost values before refinement.
     * Only valid after {@link #call()} has been invoked.
     *
     * @return array of per-node costs (same length as the path), or null if call() hasn't run
     */
    public double[] getInitialNodeCosts() {
        return initialNodeCosts;
    }

    /**
     * Returns the per-node cost values after refinement.
     * Only valid after {@link #call()} has been invoked.
     *
     * @return array of per-node costs (same length as the path), or null if call() hasn't run
     */
    public double[] getFinalNodeCosts() {
        return finalNodeCosts;
    }

    /**
     * Applies all settings from another refiner to this one.
     * Useful for batch operations where a reference refiner holds the
     * user-configured parameters.
     *
     * @param ref the reference refiner to copy settings from
     */
    public void applySettings(final MultiSpectralRefiner ref) {
        this.intensityWeight = ref.intensityWeight;
        this.colorWeight = ref.colorWeight;
        this.radiusWeight = ref.radiusWeight;
        this.cosSimilarityThreshold = ref.cosSimilarityThreshold;
        this.backgroundThreshold = ref.backgroundThreshold;
        this.minIntensityThreshold = ref.minIntensityThreshold;
        this.maxIntensityThreshold = ref.maxIntensityThreshold;
        this.minPercentC = ref.minPercentC;
        this.maxPercentC = ref.maxPercentC;
        this.maxRadius = ref.maxRadius;
        this.maxIterations = ref.maxIterations;
        this.convergenceThreshold = ref.convergenceThreshold;
        this.referenceWindowRadius = ref.referenceWindowRadius;
        this.autoTune = ref.autoTune;
    }

    /**
     * Reads stored preferences from {@link sc.fiji.snt.gui.cmds.MultiSpectralRefinerCmd}
     * and applies them to this refiner. If auto-intensity is enabled in the preferences,
     * the auto-calibrated values from the constructor are preserved.
     */
    public void readPreferences() {
        final org.scijava.prefs.PrefService prefService = SNTUtils.getContext()
                .getService(org.scijava.prefs.PrefService.class);
        final Parameters defaults = Parameters.defaults();
        final Class<?> cmdClass = sc.fiji.snt.gui.cmds.MultiSpectralRefinerCmd.class;

        intensityWeight = prefService.getDouble(cmdClass, "intensityWeight", defaults.intensityWeight());
        colorWeight = prefService.getDouble(cmdClass, "colorWeight", defaults.colorWeight());
        radiusWeight = prefService.getDouble(cmdClass, "radiusWeight", defaults.radiusWeight());
        cosSimilarityThreshold = prefService.getDouble(cmdClass, "cosSimilarity", defaults.cosSimilarityThreshold());
        backgroundThreshold = prefService.getDouble(cmdClass, "backgroundThreshold", defaults.backgroundThreshold());
        maxRadius = prefService.getInt(cmdClass, "maxRadius", defaults.maxRadius());
        minPercentC = prefService.getDouble(cmdClass, "minPercentC", defaults.minPercentC());
        maxPercentC = prefService.getDouble(cmdClass, "maxPercentC", defaults.maxPercentC());

        // Only override auto-calibrated intensity if user explicitly set manual values
        final boolean autoIntensity = prefService.getBoolean(cmdClass, "autoIntensity", true);
        if (!autoIntensity) {
            minIntensityThreshold = prefService.getDouble(cmdClass, "minIntensity", minIntensityThreshold);
            maxIntensityThreshold = prefService.getDouble(cmdClass, "maxIntensity", maxIntensityThreshold);
        }
        // else: keep the auto-calibrated values from the constructor
        referenceWindowRadius = prefService.getInt(cmdClass, "referenceWindowRadius", -1);
        autoTune = prefService.getBoolean(cmdClass, "autoTune", false);
    }

    /**
     * Runs the multispectral refinement on this path. Thread-safe: does not
     * modify the original path. Call {@link #apply()} afterward (sequentially)
     * to commit results.
     *
     * @return the refined path, or null if refinement failed
     */
    @Override
    public Path call() {
        SNTUtils.log("MSRefiner: Refining '" + path.getName()
                + "' (" + path.size() + " nodes, " + nChannels + "ch)");
        try {
            // 0. Path-level quality gate: paths shorter than two cross-section
            //    diameters have too few voxels for a reliable reference color.
            //    See Leiwe et al., Nat Commun 15, 5279 (2024) for evidence that
            //    short fragments produce unreliable color vectors.
            final double minLength = maxRadius * xSpacing * 2;
            if (path.getLength() < minLength) {
                SNTUtils.log("MSRefiner: Skipping '" + path.getName()
                        + "': path length (" + String.format("%.2f", path.getLength())
                        + ") < minimum (" + String.format("%.2f", minLength) + ")");
                succeeded = false;
                return null;
            }

            // 1. Convert Path nodes to pixel-coordinate working array
            initWorkingNodes();

            // 2. Initialize voxel cache for read deduplication
            voxelCache = new HashMap<>();

            // 3. Compute reference color vector(s)
            if (referenceWindowRadius > 0) {
                // Sliding-window: per-node local reference from ±windowRadius neighbors
                computeLocalReferenceColors();
                colorRef = null; // not used in windowed mode
            } else {
                // Global: single reference across all nodes
                colorRef = computeReferenceColor(0, workingNodes.size());
                localColorRefs = null;
                localRawRefSums = null;
            }

            // 4. Auto-tune parameters from path/image statistics (if enabled)
            if (autoTune) autoTuneParameters();

            // 5. Detect degenerate channels (zero-variance or near-zero)
            detectDegenerateChannels();

            // 6. Normalize reference color vector(s) to unit length (Eq. 1 from paper)
            if (localColorRefs != null) {
                for (final double[] ref : localColorRefs) normalizeVector(ref);
            } else {
                normalizeVector(colorRef);
            }

            // 7. Assign optimal radius to each node
            initialPenalty = 0;
            initialNodeCosts = new double[workingNodes.size()];
            for (int i = 0; i < workingNodes.size(); i++) {
                final double[] bestRad = findBestRadius(workingNodes.get(i), i);
                workingNodes.get(i)[3] = (int) bestRad[0];
                initialNodeCosts[i] = bestRad[1];
                initialPenalty += bestRad[1];
            }

            // 8. Iterative node position refinement
            finalPenalty = iterativeRefinement();

            // 9. Compute final per-node costs
            finalNodeCosts = new double[workingNodes.size()];
            for (int i = 0; i < workingNodes.size(); i++) {
                finalNodeCosts[i] = computeCost(workingNodes.get(i), i);
            }

            // 10. Release cache (no longer needed)
            voxelCache = null;

            // 11. Build the refined Path from working nodes
            refined = buildRefinedPath();
            succeeded = true;
            SNTUtils.log("MSRefiner: '" + path.getName() + "' refined. Penalty: "
                    + String.format("%.3f -> %.3f (delta=%.3f)", initialPenalty, finalPenalty,
                    initialPenalty - finalPenalty));
            return refined;

        } catch (final Exception e) {
            SNTUtils.log("MSRefiner: FAILED '" + path.getName() + "': " + e.getMessage());
            e.printStackTrace();
            succeeded = false;
            return null;
        }
    }

    /**
     * Applies the refinement result to the original path by replacing all its
     * nodes with the refined geometry. This preserves hierarchical relationships
     * (parent, children, connections) via {@link Path#replaceNodes(Path)}.
     * Must be called sequentially (not thread-safe).
     *
     * @throws IllegalStateException if {@link #call()} has not been run or failed
     */
    /** Suffix appended to path names after multi-spectral refinement. */
    private static final String REFINED_SUFFIX = " [Refined*]";

    public void apply() {
        if (refined == null || !succeeded)
            throw new IllegalStateException("No refinement result to apply");
        synchronized (path) {
            path.replaceNodes(refined);
            if (!path.getName().contains(REFINED_SUFFIX)) {
                path.setName(path.getName() + REFINED_SUFFIX);
            }
        }
    }

    /**
     * Converts Path nodes (real-world coordinates) to pixel-coordinate int arrays
     * for the optimization loop. Each entry is [x, y, z, radius] in ImageJ pixel
     * convention (x=column, y=row, z=slice).
     * <p>
     * Missing or invalid radii (NaN, zero, negative) are interpolated from flanking
     * valid nodes via {@link Path#sanitizeRadii(boolean)}. The interpolation is
     * read-only (the original path is not modified). If the path has no valid radii
     * at all (e.g., unfitted paths), a fallback of 1 pixel is used and
     * {@code findBestRadius()} will optimize from scratch.
     * </p>
     */
    private void initWorkingNodes() {
        // Compute interpolated radii without modifying the original path
        final Map<Integer, Double> interpolated = path.sanitizeRadii(false);
        workingNodes = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            final PointInImage node = path.getNode(i);
            final int[] px = SpectralSimilarity.nodeToPixelCoords(node, xSpacing, ySpacing, zSpacing);
            // Use interpolated radius if available, otherwise the node's own radius
            double radius = node.radius;
            if (interpolated != null && interpolated.containsKey(i))
                radius = interpolated.get(i);
            final double rPixels = radius / xSpacing;
            // Fall back to 1 pixel if radius is still invalid (e.g., all-NaN path)
            final int pr = (Double.isNaN(rPixels) || rPixels < 1) ? 1 : (int) Math.round(rPixels);
            workingNodes.add(new int[]{px[0], px[1], px[2], pr});
        }
    }

    /**
     * Computes the average color vector (intensity per channel) across a range of
     * working nodes. Also stores the unnormalized channel sum in {@link #rawRefSum}
     * for use in intensity checks.
     *
     * @param startIdx start index (inclusive)
     * @param endIdx   end index (exclusive)
     * @return the average color vector [ch0, ch1, ..., chN-1] (unnormalized)
     */
    private double[] computeReferenceColor(final int startIdx, final int endIdx) {
        final int count = endIdx - startIdx;
        final int[][] positions = new int[count][3];
        for (int i = 0; i < count; i++) {
            final int[] pt = workingNodes.get(startIdx + i);
            positions[i] = new int[]{pt[0], pt[1], pt[2]};
        }
        final double[] ref = SpectralSimilarity.averageColorAtPositions(channelStacks, positions);
        rawRefSum = channelSum(ref);
        return ref;
    }

    /**
     * Computes per-node reference color vectors using a sliding window of
     * ±{@link #referenceWindowRadius} neighboring nodes. Each node's reference
     * is the average color of nodes in its local neighborhood, allowing the
     * reference to adapt to gradual color drift along long axons.
     * <p>
     * Endpoint nodes use asymmetric windows (only the available side).
     * Also computes per-node raw channel sums for the intensity factor.
     */
    private void computeLocalReferenceColors() {
        final int n = workingNodes.size();
        final int w = referenceWindowRadius;
        localColorRefs = new double[n][];
        localRawRefSums = new double[n];

        for (int i = 0; i < n; i++) {
            final int lo = Math.max(0, i - w);
            final int hi = Math.min(n, i + w + 1); // exclusive
            final int count = hi - lo;
            final int[][] positions = new int[count][3];
            for (int j = 0; j < count; j++) {
                final int[] pt = workingNodes.get(lo + j);
                positions[j] = new int[]{pt[0], pt[1], pt[2]};
            }
            localColorRefs[i] = SpectralSimilarity.averageColorAtPositions(channelStacks, positions);
            localRawRefSums[i] = channelSum(localColorRefs[i]);
        }
        // Set global rawRefSum to the median local sum (used by degenerate channel detection)
        final double[] sortedSums = localRawRefSums.clone();
        java.util.Arrays.sort(sortedSums);
        rawRefSum = sortedSums[n / 2];
        SNTUtils.log("MSRefiner: Using sliding-window reference (±" + w + " nodes). "
                + "Local refSum range: " + String.format("%.1f - %.1f", sortedSums[0], sortedSums[n - 1]));
    }

    /**
     * Automatically adjusts parameters based on path and image statistics.
     * Called during {@link #call()} when {@link #autoTune} is enabled, after
     * working nodes and reference color have been computed.
     * <p>
     * Adjusts three groups of parameters:
     * <ol>
     *   <li><b>maxRadius</b>: set to {@code ceil(meanPathRadius * 2.5)}, clamped to [3, 30].
     *       Paths with no valid radii keep the existing default.</li>
     *   <li><b>cosSimilarityThreshold</b>: estimated from the distribution of cosine
     *       similarities along path nodes. Set to {@code mean - 2σ}, clamped to [0.70, 0.98].
     *       Paths with uniform color get a tight threshold; variable paths get a looser one.</li>
     *   <li><b>Intensity thresholds</b>: derived from the 5th and 95th percentiles of
     *       actual node intensities rather than from theoretical bit-depth limits.</li>
     * </ol>
     */
    private void autoTuneParameters() {
        final int n = workingNodes.size();
        if (n < 3) return; // too few nodes to estimate statistics

        // --- 1. maxRadius from path radii ---
        double sumR = 0;
        int validR = 0;
        for (final int[] pt : workingNodes) {
            if (pt[3] > 0) {
                sumR += pt[3];
                validR++;
            }
        }
        if (validR > 0) {
            final int suggested = (int) Math.ceil((sumR / validR) * 2.5);
            final int newMax = Math.max(3, Math.min(30, suggested));
            if (newMax != maxRadius) {
                SNTUtils.log("MSRefiner: Auto-tune maxRadius: " + maxRadius + " -> " + newMax
                        + " (mean path radius=" + String.format("%.1f", sumR / validR) + "px)");
                maxRadius = newMax;
            }
        }

        // --- 2. cosSimilarityThreshold from observed color variance ---
        // Compute cosine similarity between each node's color and the reference,
        // then set threshold = mean - 2*stdDev (allows 2σ of natural variation)
        final double[] cosSims = new double[n];
        // Use the global reference for this estimation (stable baseline)
        final double[] ref = (colorRef != null) ? colorRef
                : computeNormalizedGlobalRef();
        for (int i = 0; i < n; i++) {
            final int[] pt = workingNodes.get(i);
            final double[] voxColor = new double[nChannels];
            for (int c = 0; c < nChannels; c++)
                voxColor[c] = channelStacks[c].getVoxel(pt[0], pt[1], pt[2]);
            double dot = 0, mag = 0;
            for (int c = 0; c < nChannels; c++) {
                dot += voxColor[c] * ref[c];
                mag += voxColor[c] * voxColor[c];
            }
            mag = Math.sqrt(mag);
            cosSims[i] = (mag > 0) ? dot / mag : 0;
        }
        double simSum = 0, simSumSq = 0;
        for (final double s : cosSims) { simSum += s; simSumSq += s * s; }
        final double simMean = simSum / n;
        final double simStd = Math.sqrt(Math.max(0, simSumSq / n - simMean * simMean));
        final double suggested = simMean - 2.0 * simStd;
        final double newThreshold = Math.max(0.70, Math.min(0.98, suggested));
        if (Math.abs(newThreshold - cosSimilarityThreshold) > 0.01) {
            SNTUtils.log("MSRefiner: Auto-tune cosSimilarityThreshold: "
                    + String.format("%.3f -> %.3f", cosSimilarityThreshold, newThreshold)
                    + " (mean cosine=" + String.format("%.3f", simMean)
                    + ", std=" + String.format("%.3f", simStd) + ")");
            cosSimilarityThreshold = newThreshold;
        }

        // --- 3. Intensity thresholds from actual node intensity distribution ---
        final double[] intensities = new double[n];
        for (int i = 0; i < n; i++) {
            final int[] pt = workingNodes.get(i);
            double sum = 0;
            for (int c = 0; c < nChannels; c++)
                sum += channelStacks[c].getVoxel(pt[0], pt[1], pt[2]);
            intensities[i] = sum;
        }
        java.util.Arrays.sort(intensities);
        // 5th and 95th percentiles
        final double p5 = intensities[Math.max(0, (int) (n * 0.05))];
        final double p95 = intensities[Math.min(n - 1, (int) (n * 0.95))];
        // Set thresholds with some headroom
        final double newMin = p5 * 0.5;
        final double newMax = p95 * 1.5;
        if (Math.abs(newMin - minIntensityThreshold) > 1 || Math.abs(newMax - maxIntensityThreshold) > 1) {
            SNTUtils.log("MSRefiner: Auto-tune intensity range: ["
                    + String.format("%.0f, %.0f] -> [%.0f, %.0f]",
                    minIntensityThreshold, maxIntensityThreshold, newMin, newMax)
                    + " (P5=" + String.format("%.0f", p5)
                    + ", P95=" + String.format("%.0f", p95) + ")");
            minIntensityThreshold = newMin;
            maxIntensityThreshold = newMax;
        }
    }

    /**
     * Computes a normalized global reference color (used by auto-tune when
     * only local references are available).
     */
    private double[] computeNormalizedGlobalRef() {
        final int[][] positions = new int[workingNodes.size()][3];
        for (int i = 0; i < workingNodes.size(); i++) {
            final int[] pt = workingNodes.get(i);
            positions[i] = new int[]{pt[0], pt[1], pt[2]};
        }
        final double[] ref = SpectralSimilarity.averageColorAtPositions(channelStacks, positions);
        normalizeVector(ref);
        return ref;
    }

    /**
     * Detects degenerate channels (zero or near-zero variance across path nodes)
     * and marks them as inactive. Inactive channels are excluded from the cosine
     * similarity computation but still contribute to intensity sums.
     * <p>
     * A channel is considered degenerate if its standard deviation across all
     * path nodes is less than 1% of its mean value (or the mean is effectively zero).
     */
    private void detectDegenerateChannels() {
        activeChannels = new boolean[nChannels];
        int activeCount = 0;
        for (int c = 0; c < nChannels; c++) {
            double sum = 0;
            double sumSq = 0;
            for (final int[] pt : workingNodes) {
                final double v = getVoxel(c, pt[0], pt[1], pt[2]);
                sum += v;
                sumSq += v * v;
            }
            final int n = workingNodes.size();
            final double mean = sum / n;
            final double variance = (sumSq / n) - (mean * mean);
            final double stdDev = Math.sqrt(Math.max(0, variance));
            // Active if mean is non-trivial and stdDev is at least 1% of mean
            activeChannels[c] = mean > 1e-6 && (stdDev / mean) >= 0.01;
            if (!activeChannels[c]) {
                SNTUtils.log("MSRefiner: Channel " + (c + 1)
                        + " is degenerate (mean=" + String.format("%.1f", mean)
                        + ", stdDev=" + String.format("%.1f", stdDev) + "); excluded from color matching");
            } else {
                activeCount++;
            }
        }
        // Safety: if all channels are degenerate, re-enable all
        if (activeCount == 0) {
            SNTUtils.log("MSRefiner: All channels degenerate; re-enabling all for color matching");
            for (int c = 0; c < nChannels; c++) activeChannels[c] = true;
        }
    }

    /** @see SpectralSimilarity#normalizeVector(double[]) */
    private static void normalizeVector(final double[] vec) {
        SpectralSimilarity.normalizeVector(vec);
    }

    /**
     * Finds the radius (1..maxRadius) that minimizes the cost function at the
     * given node position.
     *
     * @param pt        the node [x, y, z, currentRadius]
     * @param nodeIndex index of this node in the working list
     * @return double[2]: [bestRadius, bestPenalty]
     */
    private double[] findBestRadius(final int[] pt, final int nodeIndex) {
        int bestR = pt[3];
        double minPenalty = Double.POSITIVE_INFINITY;
        final int savedR = pt[3];
        for (int r = 1; r < maxRadius; r++) {
            pt[3] = r;
            final double penalty = computeCost(pt, nodeIndex);
            if (penalty <= minPenalty) {
                minPenalty = penalty;
                bestR = r;
            }
        }
        pt[3] = savedR; // restore
        return new double[]{bestR, minPenalty};
    }

    /**
     * Computes the 3-term cost for a node at the given position with its current radius.
     * <p>
     * This is the core of nCorrect: for each voxel in the node's sphere, we check:
     * <ul>
     *   <li><b>Intensity condition</b>: total intensity within [percentC * refSum, 3 * refSum]</li>
     *   <li><b>Color condition</b>: cosine similarity between unit-normalized voxel color
     *       and the (already normalized) reference vector &gt; threshold (Eq. 1 from the paper)</li>
     * </ul>
     * The penalty is:
     * {@code a * fractionBadIntensity + b * fractionBadColor + c / r²}
     * <p>
     * Degenerate channels (detected during {@link #detectDegenerateChannels()}) are excluded
     * from the color similarity computation but still contribute to intensity sums.
     *
     * @param pos       the node [x, y, z, radius]
     * @param nodeIndex index of this node in the working list (used to select
     *                  the per-node reference color when sliding-window mode is active)
     * @return the cost value
     */
    private double computeCost(final int[] pos, final int nodeIndex) {
        final int r = pos[3];
        if (r <= 0) return Double.MAX_VALUE;

        // Select reference color for this node: local (sliding window) or global
        final double[] nodeRef = (localColorRefs != null && nodeIndex >= 0
                && nodeIndex < localColorRefs.length) ? localColorRefs[nodeIndex] : colorRef;
        final double nodeRefSum = (localRawRefSums != null && nodeIndex >= 0
                && nodeIndex < localRawRefSums.length) ? localRawRefSums[nodeIndex] : rawRefSum;

        // Collect voxels within the physical sphere (ellipsoid in pixel space).
        // A sphere of physical radius r*xSpacing satisfies:
        //   dx² + dy²*yAniso2 + dz²*zAniso2 < r²
        // The iteration bounds are tightened per axis to avoid scanning voxels
        // outside the ellipsoid (important when zSpacing >> xSpacing).
        final double r2 = (double) r * r;
        final int rY = (int) Math.ceil(r / Math.sqrt(yAniso2));
        final int rZ = (int) Math.ceil(r / Math.sqrt(zAniso2));

        // Adaptive intensity tolerance (percentC): driven by the actual intensity
        // at the node center rather than the path-average reference. This makes the
        // tolerance continuously adaptive: dim regions get wider tolerance, bright
        // regions get stricter tolerance regardless of the overall path brightness.
        final double[] centerColor = getVoxelAllChannels(pos[0], pos[1], pos[2]);
        double centerSum = 0;
        for (int c = 0; c < nChannels; c++) centerSum += centerColor[c];
        final double percentC = computePercentC(centerSum);

        // Score each voxel inline (no intermediate list allocation)
        int totalVoxels = 0;
        double badIntensityCount = 0;
        double badColorCount = 0;

        for (int z = pos[2] - rZ; z <= pos[2] + rZ; z++) {
            for (int x = pos[0] - r; x <= pos[0] + r; x++) {
                for (int y = pos[1] - rY; y <= pos[1] + rY; y++) {
                    final int dx = x - pos[0];
                    final int dy = y - pos[1];
                    final int dz = z - pos[2];
                    final double dist2 = dx * dx + dy * dy * yAniso2 + dz * dz * zAniso2;
                    if (!isValid(x, y, z) || dist2 >= r2) continue;
                    totalVoxels++;

                    // Read all channels at once (cached if voxelCache is active)
                    final double[] voxelColor = getVoxelAllChannels(x, y, z);
                    double voxelSum = 0;
                    for (int c = 0; c < nChannels; c++) voxelSum += voxelColor[c];

                    // Intensity condition: is voxel intensity in expected range?
                    final boolean intensityOk = voxelSum > percentC * nodeRefSum
                            && voxelSum < 3.0 * nodeRefSum;

                    // Color condition: cosine similarity on unit-normalized vectors (Eq. 1)
                    // Only active channels participate in the spectral angle computation
                    double dotProd = 0;
                    double magVoxel = 0;
                    for (int c = 0; c < nChannels; c++) {
                        if (!activeChannels[c]) continue;
                        dotProd += voxelColor[c] * nodeRef[c];
                        magVoxel += voxelColor[c] * voxelColor[c];
                    }
                    // nodeRef is already unit-normalized, so magRef = 1.0
                    final double denom = Math.sqrt(magVoxel);
                    final boolean colorOk = denom > 0 && (dotProd / denom) > cosSimilarityThreshold;

                    if (!intensityOk) badIntensityCount++;
                    if (!colorOk && !intensityOk) badColorCount++;
                }
            }
        }
        if (totalVoxels == 0) return Double.MAX_VALUE;

        double fracBadIntensity = badIntensityCount / totalVoxels;
        final double fracBadColor = badColorCount / totalVoxels;

        // Strong penalty if mostly background and radius > 1
        if (fracBadIntensity >= backgroundThreshold && r > 1) {
            fracBadIntensity = 100.0;
        }

        return intensityWeight * fracBadIntensity
                + colorWeight * fracBadColor
                + radiusWeight / ((double) r * r);
    }

    /**
     * Adaptive percent-C: linear interpolation between maxPercentC (at low intensity)
     * and minPercentC (at high intensity). This controls how tolerant the intensity
     * test is: dimmer reference colors get wider tolerance.
     */
    private double computePercentC(final double refSum) {
        if (refSum <= minIntensityThreshold) return maxPercentC;
        if (refSum >= maxIntensityThreshold) return minPercentC;
        final double m = (maxPercentC - minPercentC) / (minIntensityThreshold - maxIntensityThreshold);
        final double b = maxPercentC - m * minIntensityThreshold;
        return m * refSum + b;
    }

    /**
     * Iteratively refines node positions by swapping each interior node with
     * the lowest-cost shared neighbor of its predecessor and successor.
     * Stops when:
     * <ul>
     *   <li>no node moved (zero improvement), or</li>
     *   <li>relative improvement falls below {@link #convergenceThreshold}, or</li>
     *   <li>{@link #maxIterations} is reached</li>
     * </ul>
     *
     * @return the total penalty after convergence
     */
    private double iterativeRefinement() {
        double totalPenalty = initialPenalty;
        if (workingNodes.size() <= 2) return totalPenalty;

        for (int iter = 0; iter < maxIterations; iter++) {
            double iterPenalty = totalPenalty;

            for (int i = 1; i < workingNodes.size() - 1; i++) {
                final List<int[]> neighbors = getSharedNeighbors(
                        workingNodes.get(i - 1), workingNodes.get(i + 1), workingNodes.get(i));

                int bestIdx = -1;
                double[] bestResult = {0, Double.POSITIVE_INFINITY};
                for (int j = 0; j < neighbors.size(); j++) {
                    final double[] result = findBestRadius(neighbors.get(j), i);
                    if (result[1] < bestResult[1]) {
                        bestResult = result;
                        bestIdx = j;
                    }
                }

                if (bestIdx != -1) {
                    final double oldPenalty = computeCost(workingNodes.get(i), i);
                    iterPenalty -= (oldPenalty - bestResult[1]);
                    final int[] newPt = neighbors.get(bestIdx);
                    newPt[3] = (int) bestResult[0];
                    workingNodes.set(i, newPt);
                }
            }

            if (iterPenalty >= totalPenalty) {
                break; // no improvement
            }
            // Check convergence: relative improvement < threshold
            final double relativeImprovement = (totalPenalty - iterPenalty) / Math.abs(totalPenalty);
            totalPenalty = iterPenalty;
            if (relativeImprovement < convergenceThreshold) {
                SNTUtils.log("MSRefiner: Converged at iteration " + (iter + 1)
                        + " (relative improvement: " + String.format("%.5f", relativeImprovement) + ")");
                break;
            }
        }
        return totalPenalty;
    }

    /**
     * Finds positions that are in the 26-neighborhood of both p1 and p2,
     * ensuring the path remains topologically connected. The current position
     * (priority) is placed first in the list.
     */
    private List<int[]> getSharedNeighbors(final int[] p1, final int[] p2, final int[] priority) {
        final List<int[]> neighbors = new ArrayList<>();
        final HashMap<String, int[]> p1Neighbors = new HashMap<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    final int x = p1[0] + dx;
                    final int y = p1[1] + dy;
                    final int z = p1[2] + dz;
                    if (isValid(x, y, z))
                        p1Neighbors.put(x + " " + y + " " + z, new int[]{x, y, z, 0});
                }
            }
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    final int x = p2[0] + dx;
                    final int y = p2[1] + dy;
                    final int z = p2[2] + dz;
                    if (x == priority[0] && y == priority[1] && z == priority[2])
                        neighbors.addFirst(new int[]{x, y, z, 0});
                    else if (isValid(x, y, z) && p1Neighbors.containsKey(x + " " + y + " " + z))
                        neighbors.add(new int[]{x, y, z, 0});
                }
            }
        }
        return neighbors;
    }

    /**
     * Builds a new Path from the refined working nodes, converting pixel
     * coordinates back to calibrated real-world coordinates.
     */
    private Path buildRefinedPath() {
        final Path result = path.createPath();
        for (int i = 0; i < workingNodes.size(); i++) {
            final int[] node = workingNodes.get(i);
            // Convert back to calibrated coordinates (ImageJ convention)
            final double realX = node[0] * xSpacing;
            final double realY = node[1] * ySpacing;
            final double realZ = node[2] * zSpacing;
            result.addNode(new PointInImage(realX, realY, realZ));
            result.setRadius(node[3] * xSpacing, i);
        }
        return result;
    }

    /**
     * Gets all channel values at a voxel position. If the voxel cache is active,
     * values are read from the cache (or read once and cached). This avoids
     * redundant ImageStack reads across iterations since the same voxels are
     * re-evaluated many times as neighboring nodes shift positions.
     *
     * @param x column (ImageJ x)
     * @param y row (ImageJ y)
     * @param z slice (ImageJ z)
     * @return channel intensities (length = nChannels)
     */
    private double[] getVoxelAllChannels(final int x, final int y, final int z) {
        if (voxelCache != null) {
            final long key = (long) z * imgWidth * imgHeight + (long) y * imgWidth + x;
            double[] cached = voxelCache.get(key);
            if (cached != null) return cached;
            cached = new double[nChannels];
            for (int c = 0; c < nChannels; c++)
                cached[c] = channelStacks[c].getVoxel(x, y, z);
            voxelCache.put(key, cached);
            return cached;
        }
        final double[] vals = new double[nChannels];
        for (int c = 0; c < nChannels; c++)
            vals[c] = channelStacks[c].getVoxel(x, y, z);
        return vals;
    }

    /**
     * Gets the voxel value at the given pixel position in the specified channel.
     * Coordinates follow ImageJ convention: x=column, y=row, z=slice.
     */
    private double getVoxel(final int channel, final int x, final int y, final int z) {
        if (voxelCache != null) {
            return getVoxelAllChannels(x, y, z)[channel];
        }
        return channelStacks[channel].getVoxel(x, y, z);
    }

    private boolean isValid(final int x, final int y, final int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < imgWidth && y < imgHeight && z < imgDepth;
    }

    /** @see SpectralSimilarity#channelSum(double[]) */
    private static double channelSum(final double[] color) {
        return SpectralSimilarity.channelSum(color);
    }

    /**
     * Refines all paths in a tree, processing each path in parallel.
     *
     * @param imp  the multichannel image
     * @param tree the tree to refine
     * @return the number of paths successfully refined
     */
    public static int refineTree(final ImagePlus imp, final Tree tree) {
        final List<MultiSpectralRefiner> refiners = new ArrayList<>();
        for (final Path p : tree.list()) {
            if (p.size() >= 2) {
                refiners.add(new MultiSpectralRefiner(imp, p));
            }
        }
        // Parallel refinement (thread-safe)
        refiners.parallelStream().forEach(MultiSpectralRefiner::call);
        // Sequential apply
        int count = 0;
        for (final MultiSpectralRefiner refiner : refiners) {
            if (refiner.succeeded()) {
                refiner.apply();
                count++;
            }
        }
        return count;
    }

    /**
     * Refines all paths in a tree, processing each path in parallel.
     *
     * @param img  the multichannel image (ImgPlus)
     * @param tree the tree to refine
     * @return the number of paths successfully refined
     * @see ImgUtils#toImagePlus(ImgPlus)
     */
    public static <T extends NumericType<T>> int refineTree(final ImgPlus<T> img, final Tree tree) {
        return refineTree(ImgUtils.toImagePlus(img), tree);
    }

    /**
     * Refines all paths in a tree with custom parameters.
     *
     * @param img    the multichannel image (ImgPlus)
     * @param tree   the tree to refine
     * @param params the parameter set to apply to all refiners
     * @return the number of paths successfully refined
     * @see ImgUtils#toImagePlus(ImgPlus)
     */
    public static <T extends NumericType<T>> int refineTree(final ImgPlus<T> img, final Tree tree,
                                                            final Parameters params) {
        return refineTree(ImgUtils.toImagePlus(img), tree, params);
    }

    /**
     * Refines all paths in a tree with custom parameters.
     *
     * @param imp    the multichannel image
     * @param tree   the tree to refine
     * @param params the parameter set to apply to all refiners
     * @return the number of paths successfully refined
     */
    public static int refineTree(final ImagePlus imp, final Tree tree, final Parameters params) {
        final List<MultiSpectralRefiner> refiners = new ArrayList<>();
        for (final Path p : tree.list()) {
            if (p.size() >= 2) {
                final MultiSpectralRefiner r = new MultiSpectralRefiner(imp, p);
                params.applyTo(r);
                refiners.add(r);
            }
        }
        refiners.parallelStream().forEach(MultiSpectralRefiner::call);
        int count = 0;
        for (final MultiSpectralRefiner refiner : refiners) {
            if (refiner.succeeded()) {
                refiner.apply();
                count++;
            }
        }
        return count;
    }

    /**
     * Computes the cost at each node of a path without modifying it.
     * Useful for diagnostics and for the "Color Drift" plausibility check.
     *
     * @param img  the multichannel image (ImgPlus)
     * @param path the path to evaluate
     * @return per-node cost values (same length as path.size())
     * @see ImgUtils#toImagePlus(ImgPlus)
     */
    public static <T extends NumericType<T>> double[] computeNodeCosts(final ImgPlus<T> img, final Path path) {
        return computeNodeCosts(ImgUtils.toImagePlus(img), path);
    }

    /**
     * Computes the cost at each node of a path without modifying it.
     * Useful for diagnostics and for the "Color Drift" plausibility check.
     *
     * @param imp  the multichannel image
     * @param path the path to evaluate
     * @return per-node cost values (same length as path.size())
     */
    public static double[] computeNodeCosts(final ImagePlus imp, final Path path) {
        final MultiSpectralRefiner refiner = new MultiSpectralRefiner(imp, path);
        refiner.initWorkingNodes();
        refiner.voxelCache = new HashMap<>();
        refiner.colorRef = refiner.computeReferenceColor(0, refiner.workingNodes.size());
        refiner.detectDegenerateChannels();
        normalizeVector(refiner.colorRef);
        final double[] costs = new double[refiner.workingNodes.size()];
        for (int i = 0; i < refiner.workingNodes.size(); i++) {
            final double[] bestRad = refiner.findBestRadius(refiner.workingNodes.get(i), i);
            refiner.workingNodes.get(i)[3] = (int) bestRad[0];
            costs[i] = bestRad[1];
        }
        refiner.voxelCache = null;
        return costs;
    }

    /**
     * Immutable parameter set for multispectral refinement.
     * Provides a convenient way to configure multiple refiners consistently.
     * <p>
     * Intensity thresholds ({@code minIntensityThreshold}, {@code maxIntensityThreshold})
     * can be set to {@link Double#NaN} to use auto-calibrated values based on the
     * image's bit depth (the default behavior).
     * </p>
     */
    public record Parameters(
            double intensityWeight,
            double colorWeight,
            double radiusWeight,
            double cosSimilarityThreshold,
            double backgroundThreshold,
            double minIntensityThreshold,
            double maxIntensityThreshold,
            double minPercentC,
            double maxPercentC,
            int maxRadius,
            int maxIterations,
            double convergenceThreshold,
            int referenceWindowRadius,
            boolean autoTune
    ) {
        /**
         * Default parameters: weights and thresholds from the nCorrect publication,
         * with intensity thresholds set to NaN (auto-calibrated from bit depth).
         * Reference window is -1 (global, single reference per path).
         * Auto-tune is off by default.
         */
        public static Parameters defaults() {
            return new Parameters(1.00, 0.85, 3.75, 0.90, 0.47, Double.NaN, Double.NaN, 0.05, 0.30, 12, 50, 0.001, -1, false);
        }

        void applyTo(final MultiSpectralRefiner r) {
            r.setIntensityWeight(intensityWeight);
            r.setColorWeight(colorWeight);
            r.setRadiusWeight(radiusWeight);
            r.setCosSimilarityThreshold(cosSimilarityThreshold);
            r.setBackgroundThreshold(backgroundThreshold);
            if (!Double.isNaN(minIntensityThreshold) && !Double.isNaN(maxIntensityThreshold))
                r.setIntensityRange(minIntensityThreshold, maxIntensityThreshold);
            r.setPercentCRange(minPercentC, maxPercentC);
            r.setMaxRadius(maxRadius);
            r.setMaxIterations(maxIterations);
            r.setConvergenceThreshold(convergenceThreshold);
            r.setReferenceWindowRadius(referenceWindowRadius);
            r.setAutoTune(autoTune);
        }
    }
}
