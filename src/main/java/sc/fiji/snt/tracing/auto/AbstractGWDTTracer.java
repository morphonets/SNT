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

import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathFitter;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.numeric.real.DoubleType;

import java.util.*;

/**
 * Abstract base class for GWDT-based tracers: APP2-style neuron tracer using Gray-Weighted
 * Distance Transform and Fast Marching. The tree is built directly on the grayscale image
 * using intensity-weighted paths.
 * <p>
 * Subclasses specify storage backend via {@link #createStorageBackend()}.
 * </p>
 * This implementation follows the APP2 algorithm:
 * <ol>
 *   <li>GWDT - Gray-weighted distance transform on grayscale (no binarization)</li>
 *   <li>Fast Marching - Build initial tree from seed using geodesic distance on GWDT</li>
 *   <li>Hierarchical Pruning - Long-segment-first with intensity-weighted coverage</li>
 * </ol>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see <a href="https://pubmed.ncbi.nlm.nih.gov/23603332/">PMID: 23603332</a>
 */
public abstract class AbstractGWDTTracer<T extends RealType<T>> extends AbstractAutoTracer {

    /** Fast Marching state: voxel has not yet been reached. */
    public static final byte FAR = 0;
    /** Fast Marching state: voxel is in the narrow-band trial set. */
    public static final byte TRIAL = 1;
    /** Fast Marching state: voxel distance has been finalized. */
    public static final byte ALIVE = 2;

    // Input
    protected final RandomAccessibleInterval<T> source;
    protected final double[] spacing;  // voxel dimensions [x, y, z]
    protected final long[] dims;
    protected final long[] minBounds; // Cached interval bounds for efficient access
    protected final long[] maxBounds;

    // Thresholding
    protected double backgroundThreshold = -1; // auto-computed if negative

    // Pruning parameters
    protected double minBranchIntensityLength = 5.0;  // APP2's length_thresh: sum of normalized intensities (default: 5.0)
    protected double srRatio = 1.0 / 9.0;  // APP2's SR_ratio default: 1/9 (0.111)
    protected double sphereOverlapThreshold = 0.1;  // APP2: node covered if >10% of sphere overlaps
    protected double leafPruneOverlap = 0.9;  // APP2: prune leaf if 90% overlap with parent
    protected boolean leafPruneEnabled = true;  // APP2's is_leaf_prune
    protected boolean jointLeafPruneEnabled = true;  // Controls joint leaf pruning (see jointLeafPruning() docs)
    protected boolean smoothEnabled = true;  // APP2's is_smooth (default: true)
    protected int smoothWindowSize = 5;  // APP2's smooth window size
    protected boolean resampleEnabled = true;  // APP2's b_resample (default: true)
    protected double resampleStep = 2.0;  // Resample step in voxels (APP2 uses ~10 for large images)
    protected int cnnType = 2;  // APP2's cnn_type: 1=6-conn, 2=18-conn (default), 3=26-conn
    private int maxGapVoxels = 3;  // 0=disabled, 1=APP2's is_break_accept, >1=multi-voxel gap bridging
    protected boolean zigzagRemovalEnabled = true;  // Remove consecutive direction reversals
    protected boolean overshootRemovalEnabled = true;  // Remove direction reversals at branch points
    protected double branchTuneMaxAngle = 90.0;  // Max angle (degrees) for branch tuning turn test; NaN disables
    protected boolean parallelBranchPruneEnabled = true;  // Remove shorter of two sibling branches that run parallel
    private boolean pathFittingEnabled = false;  // Post-hoc PathFitter refinement of node positions and radii
    private double tipExtensionDistance = 0;  // 0=disabled; >0: A* tip extension range (voxels)

    // Multi-soma recovery pass configuration
    private double caliperFraction = 0.5;  // Fraction of nearest-neighbor distance used as FM spread limit; <0 = disabled
    private int tracedRegionBuffer = 5;  // Buffer (in voxels) around traced regions excluded between multi-soma passes
    private double minSomaDistance = 0;  // Min distance (voxels) between soma centers; used for NMS and consolidation
    private int nSomas = -1;  // Expected number of somas; -1 = auto-estimate, 0 = disabled
    private boolean autoFilterSomas = true;  // Whether traceMultiSoma auto-filters input somas

    // Score map configuration
    protected boolean scoreMapEnabled = false;  // Compute/use per-node tubeness/vesselness scores
    protected RandomAccessibleInterval<? extends RealType<?>> scoreMap = null;  // External probability/score map (any source)
    protected SNT.FilterType scoreMapFilterType = SNT.FilterType.TUBENESS;  // Filter preset when computing internally
    protected double[] scoreMapScales = null;  // Scales for filter; null = auto from radius distribution
    protected double scoreMapPruneThreshold = 0.0;  // Min score for dark-segment pruning; 0 = use score > 0 as criterion

    // Computed data
    protected double maxIntensity;
    protected double minIntensity; // Image min for GI normalization
    protected long[] seedVoxel; // Seed point (soma location)

    // Storage backend (created by subclass)
    protected StorageBackend storage;
    
    // Reusable arrays for hot path optimization (reduces allocations)
    private final int[] deltaCache;
    private final long[] neighborPosCache;
    private final long[] bridgePosCache;

    // Gap bridging counter — reset before each FM run, incremented inside addNeighborsToHeap
    private int gapBridgeCount;

    /**
     * Creates a new GWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public AbstractGWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        if (source == null) {
            throw new IllegalArgumentException("Source image cannot be null");
        }
        this.source = source;
        this.dims = Intervals.dimensionsAsLongArray(source);
        this.minBounds = Intervals.minAsLongArray(source);
        this.maxBounds = Intervals.maxAsLongArray(source);

        // Match spacing to source dimensions
        final int nDims = source.numDimensions();
        this.spacing = new double[nDims];
        for (int d = 0; d < nDims; d++) {
            if (spacing == null || d >= spacing.length) {
                this.spacing[d] = 1.0;
            } else if (spacing[d] <= 0) {
                throw new IllegalArgumentException(
                        "Invalid spacing[" + d + "]=" + spacing[d] + ". Spacing must be positive.");
            } else {
                this.spacing[d] = spacing[d];
            }
        }

        if (spacing != null && spacing.length != nDims) {
            log("Spacing length (" + spacing.length + ") != source dimensions (" + nDims +
                    "). Using: " + Arrays.toString(this.spacing));
        }

        // Initialize reusable arrays for hot path optimization
        this.deltaCache = new int[nDims];
        this.neighborPosCache = new long[nDims];
        this.bridgePosCache = new long[nDims];

        computeIntensityRange();
    }

    /**
     * Create the storage backend for this tracer.
     * Subclasses override to specify array, sparse, or disk-backed storage.
     */
    protected abstract StorageBackend createStorageBackend();

    @Override
    protected double[] getSpacing() {
        return spacing;
    }

    @Override
    protected long[] getDimensions() {
        return dims;
    }

    protected static double[] createIsotropicSpacing(final int nDims) {
        final double[] spacing = new double[nDims];
        Arrays.fill(spacing, 1.0);
        return spacing;
    }

    protected static double[] getSpacing(final ImagePlus imp, final int nDims) {
        if (nDims == 2) {
            return new double[]{
                    imp.getCalibration().pixelWidth,
                    imp.getCalibration().pixelHeight
            };
        }
        return new double[]{
                imp.getCalibration().pixelWidth,
                imp.getCalibration().pixelHeight,
                imp.getCalibration().pixelDepth
        };
    }

    /**
     * Sets the background threshold. Pixels at or below this value are considered background.
     * If negative (default), threshold is auto-computed as the image mean.
     */
    public void setBackgroundThreshold(final double threshold) {
        this.backgroundThreshold = threshold;
    }

    /**
     * Sets the SR (Signal-to-Redundant) ratio threshold used in hierarchical pruning.
     * Default: 3/9 = 0.333 Segments with signal/redundant >= this ratio are kept.
     */
    public void setSrRatio(final double ratio) {
        this.srRatio = Math.max(0, ratio);
    }

    /**
     * Sets the minimum branch intensity-length threshold. This is the sum of
     * normalized intensities ({@code intensity / maxIntensity}) along a branch
     * segment. Branches whose cumulative score falls below this value are
     * pruned. A value of 5.0 (default) means a branch needs the equivalent of
     * 5 voxels at maximum intensity, or more voxels at lower intensity.
     * <p>
     * Note: despite the name of the APP2 parameter ({@code length_thresh}),
     * this is NOT a Euclidean distance — it is an intensity-weighted length
     * that favors bright branches.
     * </p>
     *
     * @param threshold minimum intensity-length score (default: 5.0)
     */
    public void setMinBranchIntensityLength(final double threshold) {
        this.minBranchIntensityLength = Math.max(0, threshold);
    }

    /**
     * @deprecated Use {@link #setMinBranchIntensityLength(double)} instead.
     */
    @Deprecated
    public void setMinSegmentLengthVoxels(final double lengthInVoxels) {
        setMinBranchIntensityLength(lengthInVoxels);
    }

    /**
     * @deprecated Use {@link #setMinBranchIntensityLength(double)} instead.
     *             Note: the physical-to-voxel conversion is not meaningful for
     *             this parameter since it is an intensity sum, not a distance.
     */
    @Deprecated
    public void setMinSegmentLength(final double lengthInPhysicalUnits) {
        final double avgSpacing = computeAverageSpacing();
        setMinBranchIntensityLength(lengthInPhysicalUnits / avgSpacing);
    }

    /**
     * Sets the sphere overlap threshold for detecting redundant nodes during hierarchical pruning.
     * When a node's sphere has more than this fraction covered by claimed nodes,
     * it's considered redundant. Default: 0.4. Lower = more aggressive pruning.
     */
    public void setSphereOverlapThreshold(final double threshold) {
        this.sphereOverlapThreshold = Math.max(0, Math.min(1, threshold));
    }

    /**
     * Sets the leaf pruning overlap threshold. Leaves with more than this
     * fraction overlapping their parent are pruned. Values &le; 0 disable
     * leaf pruning entirely. Default: 0.9
     *
     * @param threshold overlap fraction in (0, 1], or &le; 0 to disable
     */
    public void setLeafPruneOverlap(final double threshold) {
        if (threshold <= 0) {
            this.leafPruneEnabled = false;
        } else {
            this.leafPruneEnabled = true;
            this.leafPruneOverlap = Math.min(1, threshold);
        }
    }

    /**
     * Enables or disables joint leaf pruning independently of leaf pruning.
     * Joint leaf pruning removes leaves whose sphere is 90% covered by other
     * nodes' spheres. See {@link #jointLeafPruning} for known cascading issue.
     * Default: true
     */
    public void setJointLeafPruneEnabled(final boolean enabled) {
        this.jointLeafPruneEnabled = enabled;
    }


    /**
     * Sets the smoothing window size (must be odd and &ge; 3). Values below 3
     * disable smoothing entirely. Default: 5
     *
     * @param windowSize the moving-average window, or any value &lt; 3 to disable
     */
    public void setSmoothWindowSize(final int windowSize) {
        if (windowSize < 3) {
            this.smoothEnabled = false;
        } else {
            this.smoothEnabled = true;
            this.smoothWindowSize = (windowSize % 2 == 0) ? windowSize + 1 : windowSize;
        }
    }

    /**
     * Sets the resampling step size in voxels. Points closer than this distance
     * will be merged. Values &le; 0 disable resampling entirely. Default: 2.0
     *
     * @param step step size in voxels, or &le; 0 to disable
     */
    public void setResampleStep(final double step) {
        if (step <= 0) {
            this.resampleEnabled = false;
        } else {
            this.resampleEnabled = true;
            this.resampleStep = Math.max(0.5, step);
        }
    }

    /**
     * Sets the connectivity type for Fast Marching.
     * <p>
     * <b>3D images:</b>
     * <ul>
     *   <li>1 = 6-connectivity (face neighbors)</li>
     *   <li>2 = 18-connectivity (face + edge neighbors) - default</li>
     *   <li>3 = 26-connectivity (face + edge + corner neighbors)</li>
     * </ul>
     * <b>2D images:</b>
     * <ul>
     *   <li>1 = 4-connectivity (edge neighbors)</li>
     *   <li>2, 3 = 8-connectivity (edge + corner neighbors)</li>
     * </ul>
     *
     * @param type connectivity type (1, 2, or 3)
     */
    public void setConnectivityType(final int type) {
        this.cnnType = Math.max(1, Math.min(3, type));
    }

    /**
     * Sets whether to allow bridging single-voxel intensity gaps during
     * fast marching.
     * <p>
     * When enabled, the wavefront can cross a single dark voxel to connect
     * broken neurites. The crossing is only allowed if the current voxel is
     * above threshold (i.e., bright → dark → bright, but not dark → dark).
     * </p>
     * <p>
     * This is useful for images with fragmented signals or small imaging
     * artifacts, but may falsely connect separate structures. For bridging
     * larger, multi-voxel gaps between disconnected tree components, see
     * {@link #setMaxGapVoxels(int)} or {@link #setTipExtensionDistance(double)}.
     * </p>
     *
     * @param allow true to allow crossing single dark voxels (default: false)
     * @see #setMaxGapVoxels(int)
     * @see #setTipExtensionDistance(double)
     */
    public void setAllowVoxelGap(final boolean allow) {
        this.maxGapVoxels = allow ? Math.max(1, maxGapVoxels) : 0;
    }

    /**
     * Returns whether gap bridging is enabled (single- or multi-voxel).
     *
     * @return true if dark voxels can be crossed during tracing
     * @see #setAllowVoxelGap(boolean)
     * @see #getMaxGapVoxels()
     */
    public boolean isAllowVoxelGap() {
        return maxGapVoxels > 0;
    }

    /**
     * Sets the maximum number of consecutive dark voxels that Fast Marching
     * can bridge during tracing. When the FM wavefront encounters a dark
     * neighbor, it probes ahead along the same direction for up to this many
     * steps looking for bright signal. If found, the far-side bright voxel is
     * added to the FM heap with accumulated gap cost, and FM continues
     * expanding from there normally (including all branches).
     * <p>
     * Set to 0 to disable gap bridging entirely, 1 for the legacy single-voxel
     * bridge, or higher values (e.g. 50) for beaded or fragmented signal.
     * The gap cost assumes synthetic intensity of {@code threshold + 1} for
     * each dark voxel crossed, keeping the cost model consistent with normal
     * FM traversal while penalizing gap traversal.
     * </p>
     *
     * @param maxVoxels maximum gap width in voxels (default: 3)
     * @see #setAllowVoxelGap(boolean)
     */
    public void setMaxGapVoxels(final int maxVoxels) {
        this.maxGapVoxels = Math.max(0, maxVoxels);
    }

    /**
     * Returns the maximum number of consecutive dark voxels FM can bridge.
     *
     * @return maximum gap width in voxels; 0 if disabled
     * @see #setMaxGapVoxels(int)
     */
    public int getMaxGapVoxels() {
        return maxGapVoxels;
    }

    /**
     * Enables or disables zigzag removal. When enabled, consecutive nodes that
     * form back-and-forth direction reversals (both with angles &ge; 90&deg;) are
     * collapsed. Default: true
     *
     * @param enabled true to enable zigzag removal
     */
    public void setZigzagRemovalEnabled(final boolean enabled) {
        this.zigzagRemovalEnabled = enabled;
    }

    /**
     * Enables or disables overshoot removal. When enabled, continuation nodes
     * that reverse direction adjacent to a branch point are collapsed. This
     * removes short stubs where the tracing overshoots past a bifurcation and
     * doubles back. Default: true
     *
     * @param enabled true to enable overshoot removal
     */
    public void setOvershootRemovalEnabled(final boolean enabled) {
        this.overshootRemovalEnabled = enabled;
    }

    /**
     * Sets the maximum angle (in degrees) for the branch tuning turn test.
     * During branch tuning, a node adjacent to a branch point is re-parented
     * to a closer neighbor only if the original connection forms an angle
     * &ge; this threshold (i.e., a direction reversal). Lower values make
     * tuning more aggressive (more rewiring); higher values make it more
     * conservative.
     * <p>
     * Set to {@link Double#NaN} or a negative value to disable branch tuning
     * entirely. Default: 90.0 (equivalent to NeuTube's dot-product &le; 0 test).
     * </p>
     *
     * @param maxAngleDeg maximum angle in degrees, or NaN/negative to disable
     */
    public void setBranchTuneMaxAngle(final double maxAngleDeg) {
        this.branchTuneMaxAngle = maxAngleDeg;
    }

    /**
     * Enables or disables parallel branch pruning. When enabled, sibling
     * branches that run alongside each other (their nodes stay within a
     * radius-dependent proximity threshold) are detected, and the shorter
     * sibling is removed. This eliminates redundant paths that trace the
     * same neurite on opposite sides of its centerline — a common artifact
     * in FM-based tracers operating on thick or bright structures.
     * <p>
     * Default: {@code true}.
     *
     * @param enabled whether to prune parallel sibling branches
     */
    public void setParallelBranchPruneEnabled(final boolean enabled) {
        this.parallelBranchPruneEnabled = enabled;
    }

    /**
     * Returns whether parallel branch pruning is enabled.
     *
     * @return true if parallel branch pruning is active
     */
    public boolean isParallelBranchPruneEnabled() {
        return parallelBranchPruneEnabled;
    }

    /**
     * Enables or disables post-hoc path fitting using {@link PathFitter}.
     * When enabled, after all graph-level processing is complete and Trees
     * have been assembled, each path is refined using cross-sectional
     * intensity fitting ({@link PathFitter#RADII_AND_MIDPOINTS}). This snaps
     * node positions to the signal centerline and computes accurate radii
     * from the local intensity profile, replacing the initial estimates from
     * the autotracer.
     * <p>
     * Default: {@code false} (disabled).
     *
     * @param enabled whether to apply PathFitter refinement to output paths
     * @see PathFitter
     */
    public void setPathFittingEnabled(final boolean enabled) {
        this.pathFittingEnabled = enabled;
    }

    /**
     * Returns whether post-hoc path fitting is enabled.
     *
     * @return true if PathFitter refinement is active
     */
    public boolean isPathFittingEnabled() {
        return pathFittingEnabled;
    }

    /**
     * Sets the maximum distance (in voxels) for A*-based tip extension.
     * When set to a positive value, leaf tips that end at gap boundaries
     * (where the signal drops below threshold) are extended by scanning
     * for bright signal in a forward cone and running bidirectional A*
     * to bridge the gap. This complements FM gap bridging
     * ({@link #setMaxGapVoxels(int)}), which handles small gaps during
     * wavefront expansion: tip extension targets larger contiguous gaps
     * that exceed {@code maxGapVoxels}.
     * <p>
     * Set to 0 (the default) to disable. Typical values are 20–50 voxels.
     * Note: this is an experimental feature — in noisy images, large
     * values may produce spurious bridges.
     * </p>
     *
     * @param distance maximum gap distance in voxels (default: 0 = disabled)
     * @see #setMaxGapVoxels(int)
     */
    public void setTipExtensionDistance(final double distance) {
        this.tipExtensionDistance = Math.max(0, distance);
    }

    /**
     * Returns the maximum distance (in voxels) for A*-based tip extension.
     *
     * @return maximum tip extension distance; 0 if disabled
     * @see #setTipExtensionDistance(double)
     */
    public double getTipExtensionDistance() {
        return tipExtensionDistance;
    }

    /**
     * Sets the fraction of the nearest-neighbor soma distance used as the
     * caliper radius for Fast Marching spread in multi-soma tracing. For each
     * soma, the caliper radius is {@code fraction × nearest_neighbor_distance},
     * limiting how far the wavefront can propagate. This prevents one cell's
     * tracing from invading a neighbor's territory.
     * <p>
     * Smaller fractions are more conservative (less cross-cell contamination)
     * but may truncate long processes that extend past the midpoint between
     * cells. Larger fractions allow the tracer to follow distal processes
     * further, relying on the exclusion mask
     * ({@link #setTracedRegionBuffer(int)}) to prevent overlap.
     * </p>
     * <p>
     * Set to a negative value (e.g., {@code -1}) to disable the caliper
     * entirely, allowing unrestricted Fast Marching spread. In this mode,
     * territory boundaries are enforced solely by the exclusion mask.
     * Default: {@code 0.5}.
     * </p>
     *
     * @param fraction fraction of nearest-neighbor distance (default: 0.5),
     *                 or negative to disable the caliper
     * @see #setTracedRegionBuffer(int)
     */
    public void setCaliperFraction(final double fraction) {
        this.caliperFraction = fraction;
    }

    /**
     * Returns the caliper fraction used for limiting Fast Marching spread in
     * multi-soma tracing, or a negative value if the caliper is disabled.
     *
     * @return caliper fraction, or negative if disabled
     * @see #setCaliperFraction(double)
     */
    public double getCaliperFraction() {
        return caliperFraction;
    }

    /**
     * Sets the buffer (in voxels) applied around traced regions between passes
     * in {@link #traceMultiSoma(List)}. After tracing from one soma, the traced
     * voxels are expanded by this buffer before being excluded from subsequent
     * Fast Marching runs. Larger values create wider exclusion zones, preventing
     * re-tracing of neurites already claimed by a previous soma.
     * <p>
     * Set to 0 to disable buffering (only exact traced voxels are excluded).
     * Default: 5.
     * </p>
     *
     * @param buffer buffer size in voxels (non-negative)
     */
    public void setTracedRegionBuffer(final int buffer) {
        this.tracedRegionBuffer = Math.max(0, buffer);
    }

    /**
     * Returns the buffer size (in voxels) applied around traced regions between
     * multi-soma passes.
     *
     * @return buffer size in voxels
     * @see #setTracedRegionBuffer(int)
     */
    public int getTracedRegionBuffer() {
        return tracedRegionBuffer;
    }

    /**
     * Sets the minimum distance (in voxels) between soma centers for multi-soma
     * tracing. This value controls two filtering stages:
     * <ol>
     *   <li>Non-maximum suppression in {@link SomaUtils#detectAllSomas} — callers
     *       should pass this value as the {@code minSomaDistance} parameter.</li>
     *   <li>Post-hoc consolidation in {@link #traceMultiSoma} — any somas closer
     *       than this distance are merged before tracing.</li>
     * </ol>
     * <p>
     * Set this to the minimum known distance between real soma centers in the
     * image. If &le; 0 (default), both filtering stages are disabled.
     * </p>
     *
     * @param distance minimum inter-soma distance in voxels
     */
    public void setMinSomaDistance(final double distance) {
        this.minSomaDistance = distance;
    }

    /**
     * Returns the minimum inter-soma distance (in voxels).
     * @return minimum distance, or 0 if not set
     * @see #setMinSomaDistance(double)
     */
    public double getMinSomaDistance() {
        return minSomaDistance;
    }

    /**
     * Sets the expected number of somas in the image for multi-soma tracing.
     * When set to a positive value, {@link #traceMultiSoma(List)} selects the
     * top-N somas ranked by EDT thickness via
     * {@link SomaUtils#selectTopSomasByThickness}.
     * <p>
     * <b>Experimental:</b> The EDT-based ranking relies on a global
     * binarization threshold (currently the Minimum auto-threshold) to compute
     * the distance transform. For images with large connected foreground
     * regions, the EDT may measure distance-to-nearest-dark-pixel rather than
     * actual soma body half-width, causing false detections deep inside bright
     * regions to rank higher than real somas. In practice,
     * {@link #setMinSomaDistance(double)} is more reliable and should be
     * preferred when the approximate inter-soma spacing is known.
     * </p>
     * <p>
     * This parameter is orthogonal to {@link #setMinSomaDistance(double)}:
     * <ul>
     *   <li>{@code nSomas > 0} only: top-N selection by EDT thickness</li>
     *   <li>{@code minSomaDistance > 0} only: NMS during detection,
     *       consolidation during tracing</li>
     *   <li>Both set: minSomaDistance drives NMS/consolidation upstream,
     *       nSomas acts as a downstream cap</li>
     *   <li>Neither set: auto-estimation via thickness filtering + gap
     *       analysis (best-effort, also experimental)</li>
     * </ul>
     * </p>
     * <p>
     * Set to -1 to trigger auto-estimation (default). Set to 0 to disable
     * the count-based filter entirely.
     * </p>
     *
     * @param nSomas expected number of somas (&gt; 0), -1 for auto, or 0 to disable
     * @see #setMinSomaDistance(double)
     * @see SomaUtils#selectTopSomasByThickness
     */
    public void setNSomas(final int nSomas) {
        this.nSomas = nSomas;
    }

    /**
     * Returns the expected number of somas.
     * @return expected count, -1 if auto-estimating, or 0 if disabled
     * @see #setNSomas(int)
     */
    public int getNSomas() {
        return nSomas;
    }

    /**
     * Enables or disables automatic soma filtering in
     * {@link #traceMultiSoma(List)}. When enabled (the default), the pipeline
     * applies EDT-based thickness filtering, count-based selection, gap
     * analysis, and/or NMS consolidation depending on the values of
     * {@link #setNSomas(int)} and {@link #setMinSomaDistance(double)}.
     * <p>
     * <b>Set to {@code false} when providing pre-curated soma lists</b> — for
     * example, somas detected from user-provided ROI seeds via
     * {@link SomaUtils#detectSomasAt}. In that case the input list is
     * authoritative and should not be reduced by heuristic filters. When
     * disabled, the only processing applied is NMS consolidation if
     * {@code minSomaDistance > 0} (which the caller explicitly requested).
     * </p>
     * <p>
     * Example usage with user-provided seeds:
     * <pre>{@code
     * List<SomaUtils.SomaResult> somas = SomaUtils.detectSomasAt(imp, rois);
     * tracer.setAutoFilter(false);   // trust user seeds
     * tracer.setNSomas(0);           // no count-based cap
     * List<Tree> trees = tracer.traceMultiSoma(somas);
     * }</pre>
     * </p>
     *
     * @param enabled whether to enable automatic soma filtering (default:
     *                {@code true})
     * @see SomaUtils#detectSomasAt(ij.ImagePlus, java.util.Collection)
     * @see #setNSomas(int)
     * @see #setMinSomaDistance(double)
     */
    public void setAutoFilter(final boolean enabled) {
        this.autoFilterSomas = enabled;
    }

    /**
     * Returns whether automatic soma filtering is enabled.
     *
     * @return {@code true} if auto-filtering is active (the default)
     * @see #setAutoFilter(boolean)
     */
    public boolean isAutoFilter() {
        return autoFilterSomas;
    }

    /**
     * Enables or disables per-node score computation using a vesselness or
     * probability map. When enabled, node scores are stored in
     * {@link PointInImage#v} and can influence pruning decisions. The score map
     * is also available to {@link ComponentReconnector} for bridge validation.
     * <p>
     * If an external score map has been set via {@link #setScoreMap}, it is used
     * directly. Otherwise, a score map is computed internally using the filter
     * specified by {@link #setScoreMapFilterType}. Default: {@code false}.
     * </p>
     *
     * @param enabled whether to enable score-based node evaluation
     * @see #setScoreMap(RandomAccessibleInterval)
     * @see #setScoreMapFilterType(SNT.FilterType)
     */
    public void setScoreMapEnabled(final boolean enabled) {
        this.scoreMapEnabled = enabled;
    }

    /**
     * Sets an external score/probability map to use for per-node scoring.
     * <p>
     * This can be any {@link RandomAccessibleInterval} whose values represent
     * structure confidence at each voxel, e.g., a deep-learning prediction,
     * a pre-computed vesselness image, or any other probability map. The RAI
     * must have the same spatial dimensions as the source image.
     * </p>
     * <p>
     * Setting this automatically enables score-based evaluation (equivalent to
     * calling {@code setScoreMapEnabled(true)}). Set to {@code null} to revert
     * to internal filter computation.
     * </p>
     *
     * @param map the score/probability map, or null to use internal computation
     */
    public void setScoreMap(final RandomAccessibleInterval<? extends RealType<?>> map) {
        this.scoreMap = map;
        if (map != null) this.scoreMapEnabled = true;
    }

    /**
     * Returns the current score map, whether externally provided or internally
     * computed. May be {@code null} if scoring has not yet been performed or is
     * disabled.
     *
     * @return the score map RAI, or null
     */
    public RandomAccessibleInterval<? extends RealType<?>> getScoreMap() {
        return scoreMap;
    }

    /**
     * Sets the filter type used to compute the score map internally when no
     * external map is provided. Only {@link SNT.FilterType#TUBENESS} and
     * {@link SNT.FilterType#FRANGI} are supported. Default: TUBENESS.
     *
     * @param type the filter type
     * @throws IllegalArgumentException if type is not TUBENESS or FRANGI
     */
    public void setScoreMapFilterType(final SNT.FilterType type) {
        if (type != SNT.FilterType.TUBENESS && type != SNT.FilterType.FRANGI) {
            throw new IllegalArgumentException("Only TUBENESS and FRANGI are supported for score maps");
        }
        this.scoreMapFilterType = type;
    }

    /**
     * Sets the scales (in physical units) for the internal vesselness filter.
     * Set to {@code null} to auto-derive scales from the radius distribution
     * computed by {@link #recalculateRadiiFromImage}. Default: null (auto).
     *
     * @param scales array of scales in physical units, or null for auto
     */
    public void setScoreMapScales(final double[] scales) {
        this.scoreMapScales = scales;
    }

    /**
     * Sets the minimum score threshold for score-aware pruning. Nodes with
     * scores below this value are considered unreliable. Set to 0 to use
     * any positive score as the acceptance criterion. Default: 0.
     *
     * @param threshold minimum acceptable score
     */
    public void setScoreMapPruneThreshold(final double threshold) {
        this.scoreMapPruneThreshold = threshold;
    }

    /**
     * Sets the seed point (soma location) in voxel coordinates.
     * For 2D images, only x and y are used (z is ignored if provided).
     */
    public void setSeed(final long[] voxelCoords) {
        if (voxelCoords == null || voxelCoords.length == 0) {
            throw new IllegalArgumentException("Seed coordinates cannot be null or empty.");
        }
        // Use the minimum of provided coords and image dimensions
        final int nDims = Math.min(voxelCoords.length, dims.length);
        this.seedVoxel = new long[dims.length];
        System.arraycopy(voxelCoords, 0, this.seedVoxel, 0, nDims);
        validateSeedInBounds(this.seedVoxel);
    }

    /**
     * Sets the seed point in physical coordinates.
     * For 2D images, only x and y are used (z is ignored if provided).
     */
    public void setSeedPhysical(final double[] physicalCoords) {
        if (physicalCoords == null || physicalCoords.length == 0) {
            throw new IllegalArgumentException("Seed coordinates cannot be null or empty.");
        }
        // Use the minimum of provided coords and image dimensions
        final int nDims = Math.min(physicalCoords.length, dims.length);
        this.seedVoxel = new long[dims.length];
        for (int d = 0; d < nDims; d++) {
            if (!Double.isFinite(physicalCoords[d])) {
                throw new IllegalArgumentException("Seed coordinate at dimension " + d + " is not finite.");
            }
            this.seedVoxel[d] = Math.round(physicalCoords[d] / spacing[d]);
        }
        validateSeedInBounds(this.seedVoxel);
        log("Seed voxel: " + Arrays.toString(seedVoxel));
    }

    @Override
    public DirectedWeightedGraph traceToGraph() {
        if (seedVoxel == null) {
            throw new IllegalStateException("Seed point not set. Call setSeed() first.");
        }
        validateSeedInBounds(seedVoxel);

        // Create storage backend
        storage = createStorageBackend();
        log("Using " + storage.getBackendType() + " storage backend");
        log("Estimated memory: " + (storage.estimateMemoryUsage() / (1024*1024)) + " MB");

        try {
            // Step 1: Threshold
            final double threshold = getEffectiveThreshold();
            log("Threshold: " + threshold);
            log("Intensity range: [" + minIntensity + ", " + maxIntensity + "]");

            // Step 2: Compute GWDT
            status("Computing distance transform...");
            log("Computing GWDT...");
            storage.computeGWDT(source, threshold, spacing, minIntensity, maxIntensity);
            log("GWDT max value: " + storage.getMaxGWDT());

            // Step 3: Fast Marching
            status("Fast marching...");
            log("Building initial tree via Fast Marching...");
            final long seedIndex = posToIndex(seedVoxel);
            runFastMarching(threshold, seedIndex);

            // Step 4: Build graph
            status("Building graph...");
            log("Converting to graph...");
            final DirectedWeightedGraph graph = storage.buildGraph(dims, spacing, threshold);
            log("Graph: " + graph.vertexSet().size() + " vertices, " + graph.edgeSet().size() + " edges");

            // Post-process: pruning, smoothing, etc.
            postProcessGraph(graph, threshold);

            return graph;

        } finally {
            // Always clean up storage
            if (storage != null) {
                storage.dispose();
            }
        }
    }

    /**
     * Initializes storage and runs Fast Marching from the given seed.
     * This is the standard entry point used by {@link #traceToGraph()}.
     */
    protected void runFastMarching(final double threshold, final long seedIndex) {
        storage.initializeFastMarching(dims, seedIndex);
        executeFastMarching(threshold, seedIndex, 0); // 0 = no spread limit
    }

    /**
     * Runs the Fast Marching loop from the given seed without re-initializing
     * storage. The caller is responsible for initializing or re-initializing
     * the FM data structures (distances, parents, state) before calling this
     * method. Used by {@link #traceMultiSoma(List)} to run FM with a
     * pre-configured exclusion mask.
     *
     * @param threshold background intensity threshold
     * @param seedIndex linear index of the seed voxel
     * @param maxSpreadRadius maximum Euclidean distance (in calibrated units)
     *        from the seed beyond which FM expansion is halted. If &lt;= 0, no
     *        limit is applied. Used in multi-soma mode to prevent FM from
     *        spreading into neighboring cells' territory.
     */
    private void executeFastMarching(final double threshold, final long seedIndex,
                                     final double maxSpreadRadius) {
        // Priority queue: (index, distance)
        final PriorityQueue<long[]> heap = new PriorityQueue<>(
                Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1]))
        );

        final RandomAccess<T> srcRA = source.randomAccess();

        // Mark seed as ALIVE
        storage.setState(seedIndex, ALIVE);
        storage.setDistance(seedIndex, 0);
        storage.setParent(seedIndex, seedIndex);

        // Add seed neighbors
        final long[] seedPos = new long[dims.length];
        indexToPos(seedIndex, seedPos);
        addNeighborsToHeap(seedPos, srcRA, heap, threshold);

        // Fast Marching main loop
        final long[] currentPos = new long[dims.length];
        final boolean hasSpreadLimit = maxSpreadRadius > 0;
        int nodeCount = 1;
        int skippedByRadius = 0;
        gapBridgeCount = 0;

        while (!heap.isEmpty()) {
            final long[] entry = heap.poll();
            final long currentIdx = entry[0];
            indexToPos(currentIdx, currentPos);

            if (storage.getState(currentIdx) == ALIVE) continue;

            // Caliper check: skip voxels beyond the maximum spread radius
            if (hasSpreadLimit) {
                double eucDistSq = 0;
                for (int d = 0; d < dims.length; d++) {
                    final double diff = (currentPos[d] - seedPos[d]) * spacing[d];
                    eucDistSq += diff * diff;
                }
                if (eucDistSq > maxSpreadRadius * maxSpreadRadius) {
                    skippedByRadius++;
                    continue;
                }
            }

            // Check background
            srcRA.setPosition(currentPos);
            if (srcRA.get().getRealDouble() <= threshold) {
                continue;
            }

            storage.setState(currentIdx, ALIVE);
            nodeCount++;

            addNeighborsToHeap(currentPos, srcRA, heap, threshold);
        }

        SNTUtils.log("Fast Marching: " + nodeCount + " ALIVE nodes"
                + (hasSpreadLimit ? " (skipped " + skippedByRadius
                + " beyond radius " + String.format("%.1f", maxSpreadRadius) + ")" : "")
                + (gapBridgeCount > 0 ? " (bridged " + gapBridgeCount + " gaps)" : ""));
    }

    /**
     * Add neighbors to Fast Marching heap.
     * Uses cached arrays to reduce allocations in hot path.
     */
    protected void addNeighborsToHeap(final long[] pos, final RandomAccess<T> srcRA,
                                     final PriorityQueue<long[]> heap, final double threshold) {
        final int nDims = dims.length;
        final long currentIdx = posToIndex(pos);
        final double currentDist = storage.getDistance(currentIdx);
        srcRA.setPosition(pos);
        final double currentIntensity = srcRA.get().getRealDouble();
        
        // Reuse cached arrays to avoid allocations
        Arrays.fill(deltaCache, 0);

        iterateNeighbors(deltaCache, 0, nDims, () -> {
            // Check connectivity
            int offset = 0;
            for (int d = 0; d < nDims; d++) {
                offset += Math.abs(deltaCache[d]);
            }
            if (offset == 0 || offset > cnnType) return;

            // Compute neighbor position
            boolean inBounds = true;
            for (int d = 0; d < nDims; d++) {
                neighborPosCache[d] = pos[d] + deltaCache[d];
                if (neighborPosCache[d] < 0 || neighborPosCache[d] >= dims[d]) {
                    inBounds = false;
                    break;
                }
            }
            if (!inBounds) return;

            final long neighborIdx = posToIndex(neighborPosCache);
            if (storage.getState(neighborIdx) == ALIVE) return;

            // Check threshold
            srcRA.setPosition(neighborPosCache);
            final double intensity = srcRA.get().getRealDouble();
            // Base geometric distance (one step in the current direction)
            double euclideanDist = 0;
            for (int d = 0; d < nDims; d++) {
                final double dd = deltaCache[d] * spacing[d];
                euclideanDist += dd * dd;
            }
            euclideanDist = Math.sqrt(euclideanDist);

            if (intensity <= threshold) {
                if (maxGapVoxels <= 0 || currentIntensity <= threshold) return;

                // Gap bridging: probe ahead along the same direction for up to
                // maxGapVoxels steps, looking for the first bright voxel.
                // Cost per dark step assumes synthetic intensity of threshold+1,
                // keeping the cost model consistent with normal FM traversal.
                final double maxGWDT = storage.getMaxGWDT();
                final double syntheticGapCostPerStep = euclideanDist
                        + computeGWDTTraversalCost(
                            computeSyntheticGapGWDT(threshold, maxGWDT), maxGWDT);
                // Start with cost for the first dark voxel (the neighbor itself)
                double accumulatedGapCost = syntheticGapCostPerStep;

                for (int step = 2; step <= maxGapVoxels + 1; step++) {
                    boolean probeInBounds = true;
                    for (int d = 0; d < nDims; d++) {
                        bridgePosCache[d] = pos[d] + (long) step * deltaCache[d];
                        if (bridgePosCache[d] < 0 || bridgePosCache[d] >= dims[d]) {
                            probeInBounds = false;
                            break;
                        }
                    }
                    if (!probeInBounds) break;

                    accumulatedGapCost += syntheticGapCostPerStep;

                    final long probeIdx = posToIndex(bridgePosCache);
                    if (storage.getState(probeIdx) == ALIVE) break;

                    srcRA.setPosition(bridgePosCache);
                    final double probeIntensity = srcRA.get().getRealDouble();
                    if (probeIntensity > threshold) {
                        // Found bright voxel across the gap — bridge to it
                        final double gwdt = storage.getGWDT(probeIdx);
                        final double gwdtCost = computeGWDTTraversalCost(gwdt, maxGWDT);
                        if (!Double.isFinite(gwdtCost)) break;

                        final double newDist = currentDist + accumulatedGapCost + gwdtCost;
                        if (newDist < storage.getDistance(probeIdx)) {
                            storage.setDistance(probeIdx, newDist);
                            storage.setParent(probeIdx, currentIdx);
                            if (storage.getState(probeIdx) == FAR) {
                                storage.setState(probeIdx, TRIAL);
                            }
                            heap.offer(new long[]{probeIdx,
                                    Double.doubleToRawLongBits(newDist)});
                            gapBridgeCount++;
                        }
                        break; // bridge to first bright voxel only
                    }
                }
                return;
            }

            // Compute distance via GWDT
            final double gwdt = storage.getGWDT(neighborIdx);
            final double maxGWDT = storage.getMaxGWDT();

            // Edge cost
            final double gwdtCost = computeGWDTTraversalCost(gwdt, maxGWDT);
            if (!Double.isFinite(gwdtCost)) return;
            final double edgeCost = euclideanDist + gwdtCost;
            final double newDist = currentDist + edgeCost;

            // Update if better
            if (newDist < storage.getDistance(neighborIdx)) {
                storage.setDistance(neighborIdx, newDist);
                storage.setParent(neighborIdx, currentIdx);

                if (storage.getState(neighborIdx) == FAR) {
                    storage.setState(neighborIdx, TRIAL);
                }

                heap.offer(new long[]{neighborIdx, Double.doubleToRawLongBits(newDist)});
            }
        });
    }


    /**
     * Smooths the final curve using a moving average filter.
     *
     * @param graph      the graph to smooth
     * @param windowSize the smoothing window (default 5: 2 neighbors on each side)
     */
    protected void smoothCurve(final DirectedWeightedGraph graph, final int windowSize) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Find all branch points (nodes with multiple children)
        final Set<SWCPoint> branchPoints = new HashSet<>();
        branchPoints.add(root);
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) > 1) {
                branchPoints.add(v);
            }
        }

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        // For each leaf, trace to branch point and smooth that segment
        for (final SWCPoint leaf : leaves) {
            // Collect segment from leaf to branch point
            final List<SWCPoint> segment = new ArrayList<>();
            SWCPoint current = leaf;
            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                segment.add(current);

                if (branchPoints.contains(current) && !current.equals(leaf)) {
                    break;  // Stop at branch point (include it)
                }

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }

            if (segment.size() < 3) continue;  // Need at least 3 points to smooth

            // Apply moving average to this segment (exclude endpoints which are junctions)
            smoothSegment(segment, windowSize, branchPoints);
        }
    }

    // ==================== APP2-STYLE SEGMENT-BASED TREE CONSTRUCTION ====================

    /**
     * Applies moving average smoothing to a segment.
     * Modifies coordinates and radius in place.
     * Does NOT smooth branch points or endpoints to preserve topology.
     */
    private void smoothSegment(final List<SWCPoint> segment, final int windowSize,
                               final Set<SWCPoint> branchPoints) {
        if (windowSize < 2) return;

        final int halfWindow = windowSize / 2;
        final int n = segment.size();

        // Store original values (APP2 does this to avoid cascading effects)
        final double[] origX = new double[n];
        final double[] origY = new double[n];
        final double[] origZ = new double[n];
        final double[] origR = new double[n];

        for (int i = 0; i < n; i++) {
            origX[i] = segment.get(i).x;
            origY[i] = segment.get(i).y;
            origZ[i] = segment.get(i).z;
            origR[i] = segment.get(i).radius;
        }

        // Apply smoothing with TRIANGULAR WEIGHTS (matching APP2's smooth_curve_and_radius)
        // Don't move start & end points
        for (int i = 1; i < n - 1; i++) {
            final SWCPoint node = segment.get(i);

            // Don't smooth branch points
            if (branchPoints.contains(node)) continue;

            // APP2 uses triangular weights:
            // Center gets weight (1 + halfwin)
            // Neighbors at distance j get weight (1 + halfwin - j)
            double sumX = 0, sumY = 0, sumZ = 0, sumR = 0;
            double sumWeight = 0;

            // Center point with highest weight
            double centerWeight = 1.0 + halfWindow;
            sumX += centerWeight * origX[i];
            sumY += centerWeight * origY[i];
            sumZ += centerWeight * origZ[i];
            sumR += centerWeight * origR[i];
            sumWeight += centerWeight;

            // Neighbors with decreasing weights
            for (int j = 1; j <= halfWindow; j++) {
                int k1 = i + j;
                if (k1 > n - 1) k1 = n - 1;
                int k2 = i - j;
                if (k2 < 0) k2 = 0;

                double weight = 1.0 + halfWindow - j;

                sumX += weight * origX[k1];
                sumY += weight * origY[k1];
                sumZ += weight * origZ[k1];
                sumR += weight * origR[k1];

                sumX += weight * origX[k2];
                sumY += weight * origY[k2];
                sumZ += weight * origZ[k2];
                sumR += weight * origR[k2];

                sumWeight += 2 * weight;
            }

            if (sumWeight > 0) {
                node.x = sumX / sumWeight;
                node.y = sumY / sumWeight;
                node.z = sumZ / sumWeight;
                node.radius = Math.max(0.5, sumR / sumWeight);
            }
        }
    }

    /**
     * Returns a radius-adaptive score threshold. Thinner structures produce
     * weaker vesselness responses, so the acceptance bar is lowered for small
     * radii. Adapted from NeuTube's {@code Local_Neuroseg_Good_Score}:
     * <pre>
     *   effective = base × (1 + 1 / (2 + exp(4 - r)))
     * </pre>
     * where {@code r} is the node radius in voxels. For large radii the factor
     * approaches {@code base × 1.33}; for very small radii it approaches
     * {@code base × 1.02}, i.e., the threshold drops substantially.
     *
     * @param baseThreshold the configured score threshold
     * @param radiusPhysical the node radius in physical units
     * @return the adjusted threshold (&le; baseThreshold for thin structures)
     */
    private double adaptiveScoreThreshold(final double baseThreshold, final double radiusPhysical) {
        if (baseThreshold <= 0 || Double.isNaN(radiusPhysical) || radiusPhysical <= 0) {
            return baseThreshold;
        }
        // Convert to voxel units (approximate, using average XY spacing)
        final double radiusVoxels = radiusPhysical / ((spacing[0] + spacing[1]) / 2.0);
        // NeuTube formula: factor = 1 + 1/(2 + exp(4 - r))
        // For r=0.5 → factor≈1.03 (very lenient)
        // For r=2   → factor≈1.07
        // For r=5   → factor≈1.19
        // For r=10  → factor≈1.33 (full stringency)
        // We invert it: divide base by the factor so thin = lower threshold
        final double factor = 1.0 + 1.0 / (2.0 + Math.exp(4.0 - radiusVoxels));
        return baseThreshold / factor;
    }

    /**
     * Computes the cosine of the angle at node {@code b} in the path
     * a&rarr;b&rarr;c. Vectors (a-b) and (b-c) are used; the result is the
     * dot product of the unit vectors, i.e.&nbsp;cos(angle). A value of 1
     * means straight continuation, 0 means 90&deg;, and &minus;1 means full
     * reversal.
     *
     * @return cosine of the angle, or {@link Double#NaN} if either vector has
     *         zero length
     */
    private static double cosAngle(final SWCPoint a, final SWCPoint b, final SWCPoint c) {
        final double v1x = a.x - b.x, v1y = a.y - b.y, v1z = a.z - b.z;
        final double v2x = b.x - c.x, v2y = b.y - c.y, v2z = b.z - c.z;
        final double len1sq = v1x * v1x + v1y * v1y + v1z * v1z;
        final double len2sq = v2x * v2x + v2y * v2y + v2z * v2z;
        if (len1sq == 0 || len2sq == 0) return Double.NaN;
        return (v1x * v2x + v1y * v2y + v1z * v2z) / Math.sqrt(len1sq * len2sq);
    }

    /**
     * Tests whether three consecutive nodes form a direction reversal (angle
     * &ge; 90&deg;). Equivalent to {@code cosAngle(a,b,c) <= 0}.
     *
     * @param a the first node (e.g., parent)
     * @param b the middle node
     * @param c the third node (e.g., child)
     * @return true if the path a&rarr;b&rarr;c reverses direction
     */
    private static boolean formsTurn(final SWCPoint a, final SWCPoint b, final SWCPoint c) {
        return cosAngle(a, b, c) <= 0;
    }

    /**
     * Tests whether three consecutive nodes form an angle exceeding the given
     * threshold. An angle of 90&deg; corresponds to a dot product of 0; larger
     * angles have negative cosines.
     *
     * @param a            the first node
     * @param b            the middle node
     * @param c            the third node
     * @param cosThreshold cosine of the maximum allowed angle (e.g., 0 for
     *                     90&deg;, &minus;0.5 for 120&deg;). The path is a
     *                     "turn" when cosAngle &le; this value.
     * @return true if the angle at b exceeds the threshold
     */
    private static boolean formsTurn(final SWCPoint a, final SWCPoint b, final SWCPoint c,
                                     final double cosThreshold) {
        return cosAngle(a, b, c) <= cosThreshold;
    }

    /**
     * Returns the parent of a node in the graph, or null if none exists.
     */
    private static SWCPoint getParent(final DirectedWeightedGraph graph, final SWCPoint node) {
        final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(node);
        return inEdges.isEmpty() ? null : graph.getEdgeSource(inEdges.iterator().next());
    }

    /**
     * Returns the single child of a continuation node, or null if the node has
     * zero or multiple children.
     */
    private static SWCPoint getSingleChild(final DirectedWeightedGraph graph, final SWCPoint node) {
        return (graph.outDegreeOf(node) == 1)
                ? graph.getEdgeTarget(graph.outgoingEdgesOf(node).iterator().next())
                : null;
    }

    /**
     * Removes a continuation node from the graph, reconnecting its parent to
     * each of its children. Edge weights are updated to reflect the new
     * Euclidean distances.
     *
     * @param graph the graph to modify
     * @param node  the node to remove (must have exactly one incoming edge)
     */
    private static void mergeToParent(final DirectedWeightedGraph graph, final SWCPoint node) {
        final SWCPoint parent = getParent(graph, node);
        if (parent == null) return;
        final List<SWCPoint> children = new ArrayList<>();
        for (final SWCWeightedEdge e : graph.outgoingEdgesOf(node)) {
            children.add(graph.getEdgeTarget(e));
        }
        graph.removeVertex(node);
        for (final SWCPoint child : children) {
            if (graph.containsVertex(child) && graph.containsVertex(parent)) {
                final SWCWeightedEdge newEdge = graph.addEdge(parent, child);
                if (newEdge != null) {
                    graph.setEdgeWeight(newEdge, parent.distanceTo(child));
                }
            }
        }
    }

    /**
     * Removes zigzag artifacts from the graph. A zigzag is two consecutive
     * continuation nodes that both reverse direction (angle &ge; 90&deg;). The
     * inner node is merged to its parent, collapsing the back-and-forth
     * pattern. The process repeats until no more zigzags are found.
     * <p>
     * Adapted from NeuTube's {@code Swc_Tree_Remove_Zigzag}.
     * </p>
     *
     * @param graph the graph to clean
     * @see #setZigzagRemovalEnabled(boolean)
     */
    protected void removeZigzags(final DirectedWeightedGraph graph) {
        boolean changed = true;
        int removed = 0;
        while (changed) {
            changed = false;
            // Snapshot vertex set to avoid concurrent modification
            for (final SWCPoint node : new ArrayList<>(graph.vertexSet())) {
                if (!graph.containsVertex(node)) continue;
                // Node must be a continuation (single parent, single child)
                if (graph.outDegreeOf(node) != 1 || graph.inDegreeOf(node) != 1) continue;
                final SWCPoint parent = getParent(graph, node);
                final SWCPoint child = getSingleChild(graph, node);
                if (parent == null || child == null) continue;
                if (!formsTurn(parent, node, child)) continue;
                // Child must also be a continuation and a turn
                if (!graph.containsVertex(child)) continue;
                if (graph.outDegreeOf(child) != 1 || graph.inDegreeOf(child) != 1) continue;
                final SWCPoint grandchild = getSingleChild(graph, child);
                if (grandchild == null) continue;
                if (!formsTurn(node, child, grandchild)) continue;
                // Both are turns: collapse the inner node (child)
                mergeToParent(graph, child);
                removed++;
                changed = true;
            }
        }
        if (removed > 0) {
            log("  Zigzag removal: collapsed " + removed + " nodes");
        }
    }

    /**
     * Removes overshoot artifacts from the graph. An overshoot is a
     * continuation node that reverses direction and sits adjacent to a branch
     * point&mdash;either its parent is a branch point (and its child is not) or
     * its child is a branch point (and its parent is not). This pattern
     * indicates the tracing overshot past a bifurcation and doubled back.
     * <p>
     * Adapted from NeuTube's {@code Swc_Tree_Remove_Overshoot}.
     * </p>
     *
     * @param graph the graph to clean
     * @see #setOvershootRemovalEnabled(boolean)
     */
    protected void removeOvershoots(final DirectedWeightedGraph graph) {
        int removed = 0;
        for (final SWCPoint node : new ArrayList<>(graph.vertexSet())) {
            if (!graph.containsVertex(node)) continue;
            // Must be a continuation node
            if (graph.outDegreeOf(node) != 1 || graph.inDegreeOf(node) != 1) continue;
            final SWCPoint parent = getParent(graph, node);
            final SWCPoint child = getSingleChild(graph, node);
            if (parent == null || child == null) continue;
            if (!formsTurn(parent, node, child)) continue;
            // Overshoot: turn is adjacent to exactly one branch point
            final boolean parentIsBP = graph.outDegreeOf(parent) > 1;
            final boolean childIsBP = graph.outDegreeOf(child) > 1;
            if (parentIsBP == childIsBP) continue; // both or neither, not an overshoot
            mergeToParent(graph, node);
            removed++;
        }
        if (removed > 0) {
            log("  Overshoot removal: collapsed " + removed + " nodes");
        }
    }

    /**
     * Tunes branch-point topology by re-parenting nodes adjacent to
     * bifurcations. For each continuation node whose parent is a branch point,
     * the method considers alternative attachment targets (grandparent,
     * siblings) and picks the closest one that does not create a turn exceeding
     * the configured angle threshold.
     * <p>
     * Adapted from NeuTube's {@code Swc_Tree_Node_Tune_Branch} (Part&nbsp;1:
     * nodes whose parent is a branch point).
     * </p>
     *
     * @param graph        the graph to modify
     * @param cosThreshold cosine of the maximum allowed angle. Connections with
     *                     {@code cosAngle <= cosThreshold} are considered turns
     *                     and the node becomes eligible for rewiring.
     * @see #setBranchTuneMaxAngle(double)
     */
    protected void tuneBranches(final DirectedWeightedGraph graph, final double cosThreshold) {
        int rewired = 0;
        for (final SWCPoint node : new ArrayList<>(graph.vertexSet())) {
            if (!graph.containsVertex(node)) continue;
            // Must be a continuation node (single parent, single child)
            if (graph.outDegreeOf(node) != 1 || graph.inDegreeOf(node) != 1) continue;
            final SWCPoint parent = getParent(graph, node);
            if (parent == null) continue;
            // Parent must be a branch point
            if (graph.outDegreeOf(parent) <= 1) continue;
            final SWCPoint child = getSingleChild(graph, node);
            if (child == null) continue;

            // Current connection must be a turn to be eligible for rewiring
            if (!formsTurn(child, node, parent, cosThreshold)) continue;

            // --- Find best alternative attachment point ---
            SWCPoint bestTarget = null;
            double bestDist = Double.MAX_VALUE;

            // Candidate: grandparent (parent's parent)
            final SWCPoint grandparent = getParent(graph, parent);
            if (grandparent != null && !formsTurn(child, node, grandparent, cosThreshold)) {
                bestTarget = grandparent;
                bestDist = node.distanceTo(grandparent);
            }

            // Candidates: siblings (other children of parent)
            for (final SWCWeightedEdge e : new ArrayList<>(graph.outgoingEdgesOf(parent))) {
                final SWCPoint sibling = graph.getEdgeTarget(e);
                if (sibling.equals(node)) continue;
                if (formsTurn(child, node, sibling, cosThreshold)) continue;
                final double d = node.distanceTo(sibling);
                if (d < bestDist) {
                    bestTarget = sibling;
                    bestDist = d;
                }
            }

            if (bestTarget != null) {
                graph.removeEdge(parent, node);
                final SWCWeightedEdge newEdge = graph.addEdge(bestTarget, node);
                if (newEdge != null) {
                    graph.setEdgeWeight(newEdge, bestDist);
                }
                rewired++;
            }
        }
        if (rewired > 0) {
            log("  Branch tuning: rewired " + rewired + " nodes");
        }
    }

    /**
     * Recalculates radii for all nodes using image-based method (like APP2).
     * This is critical for accurate coverage detection during pruning.
     */
    protected void recalculateRadiiFromImage(final DirectedWeightedGraph graph, final double effectiveThreshold) {
        final RandomAccess<T> srcRA = source.randomAccess();

        // Use a threshold slightly above background for radius calculation
        // APP2 uses max(40, bkg_thresh)
        final double radiusThreshold = Math.max(40, effectiveThreshold);

        int count = 0;
        double sumOldRadius = 0, sumNewRadius = 0;

        for (final SWCPoint node : graph.vertexSet()) {
            // Convert physical coordinates to voxel position
            final long[] pos = new long[dims.length];
            pos[0] = Math.round(node.x / spacing[0]);
            pos[1] = Math.round(node.y / spacing[1]);
            if (dims.length > 2) pos[2] = Math.round(node.z / spacing[2]);

            // Clamp to bounds
            for (int d = 0; d < dims.length; d++) {
                pos[d] = Math.max(0, Math.min(dims[d] - 1, pos[d]));
            }

            sumOldRadius += node.radius;

            // Calculate new radius using image-based method
            final double newRadius = calculateRadiusFromImage(pos, srcRA, radiusThreshold);

            // Convert from voxel units to physical units
            node.radius = newRadius * spacing[0];  // Assume isotropic XY

            sumNewRadius += node.radius;
            count++;
        }

        if (count > 0) {
            log("  Radius recalculation: avg " + String.format("%.2f", sumOldRadius / count) +
                    " -> " + String.format("%.2f", sumNewRadius / count) + " (" + count + " nodes)");
        }
    }


    /**
     * Computes or applies a score map and stamps each graph node's
     * {@link PointInImage#v} with its score. If an external score map was set
     * via {@link #setScoreMap}, it is sampled directly. Otherwise, a
     * Tubeness or Frangi filter is computed from the source image.
     * <p>
     * When {@code scoreMapScales} is null, scales are auto-derived from the
     * radius distribution already present in the graph (from
     * {@link #recalculateRadiiFromImage}), using 3 percentiles (25th, 50th,
     * 75th) of the non-zero radii.
     * </p>
     *
     * @param graph the graph whose nodes will be scored
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void computeAndApplyScoreMap(final DirectedWeightedGraph graph) {
        // Step 1: Compute score map if not externally provided
        if (scoreMap == null) {
            final double[] scales = (scoreMapScales != null) ? scoreMapScales : deriveScalesFromRadii(graph);
            if (scales == null || scales.length == 0) {
                log("  Score map: no valid scales derived; skipping");
                return;
            }
            log("  Computing " + scoreMapFilterType + " score map with scales: " + Arrays.toString(scales));

            final long[] outDims = Intervals.dimensionsAsLongArray(source);
            final ArrayImg<DoubleType, ?> output = ArrayImgs.doubles(outDims);

            if (scoreMapFilterType == SNT.FilterType.FRANGI) {
                final Frangi frangi = new Frangi(scales, spacing, maxIntensity);
                frangi.compute(source, output);
            } else {
                // Default to TUBENESS
                final Tubeness tubeness = new Tubeness(scales, spacing);
                tubeness.compute(source, output);
            }
            scoreMap = output;
        }

        // Step 2: Sample score map at each node position
        final RandomAccess<? extends RealType<?>> scoreRA = scoreMap.randomAccess();
        final long[] pos = new long[dims.length];
        int count = 0;
        double sumScore = 0;
        double maxScore = Double.NEGATIVE_INFINITY;

        for (final SWCPoint node : graph.vertexSet()) {
            nodeToVoxelPos(node, pos);
            if (isInBounds(pos)) {
                scoreRA.setPosition(pos);
                final double score = scoreRA.get().getRealDouble();
                node.v = score;
                sumScore += score;
                maxScore = Math.max(maxScore, score);
                count++;
            } else {
                node.v = 0;
            }
        }

        if (count > 0) {
            log("  Score map applied: avg=" + String.format("%.4f", sumScore / count) +
                    ", max=" + String.format("%.4f", maxScore) + " (" + count + " nodes)");
        }
    }

    /**
     * Derives filter scales from the radius distribution in the current graph.
     * Returns the 25th, 50th, and 75th percentiles of non-zero radii (in
     * physical units), which correspond to the range of structure thicknesses
     * present in the reconstruction.
     *
     * @param graph the graph with radii already computed
     * @return array of scales, or null if insufficient data
     */
    private double[] deriveScalesFromRadii(final DirectedWeightedGraph graph) {
        final List<Double> radii = new ArrayList<>();
        for (final SWCPoint node : graph.vertexSet()) {
            if (node.radius > 0) {
                radii.add(node.radius);
            }
        }
        if (radii.size() < 4) return null;  // too few nodes for meaningful percentiles

        Collections.sort(radii);
        final int n = radii.size();
        final double p25 = radii.get(n / 4);
        final double p50 = radii.get(n / 2);
        final double p75 = radii.get(3 * n / 4);

        // Deduplicate: only keep distinct values (within 10% tolerance)
        final List<Double> scales = new ArrayList<>();
        scales.add(p25);
        if (p50 > p25 * 1.1) scales.add(p50);
        if (p75 > (scales.getLast()) * 1.1) scales.add(p75);

        return scales.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Calculates radius by expanding outward until hitting background (like APP2's markerRadiusXY).
     * This is much more accurate than GWDT-based estimation.
     */
    private double calculateRadiusFromImage(final long[] pos, final RandomAccess<T> srcRA, final double threshold) {
        final int maxRadius = (int) Math.min(Math.min(dims[0], dims[1]) / 2, 50);  // Cap at 50 voxels

        // For 2D-like images (thin Z), use 2D radius calculation
        final boolean is2D = dims.length < 3 || dims[2] <= 3;
        final long[] samplePos = new long[dims.length];

        for (int r = 1; r <= maxRadius; r++) {
            int totalCount = 0;
            int backgroundCount = 0;

            // Check shell at radius r (not the entire sphere, just the shell)
            final int zRange = is2D ? 0 : r;
            for (int dz = -zRange; dz <= zRange; dz++) {
                final long z = dims.length > 2 ? pos[2] + dz : 0;
                if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;

                for (int dy = -r; dy <= r; dy++) {
                    final long y = pos[1] + dy;
                    if (y < 0 || y >= dims[1]) return r;  // Hit boundary

                    for (int dx = -r; dx <= r; dx++) {
                        final long x = pos[0] + dx;
                        if (x < 0 || x >= dims[0]) return r;  // Hit boundary

                        // Check if on shell (distance between r-1 and r)
                        final double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist > r - 1 && dist <= r) {
                            totalCount++;

                            samplePos[0] = x;
                            samplePos[1] = y;
                            if (dims.length > 2) samplePos[2] = z;
                            srcRA.setPosition(samplePos);
                            final double intensity = srcRA.get().getRealDouble();

                            if (intensity <= threshold) {
                                backgroundCount++;
                                // APP2 uses 0.1% threshold (0.001)
                                if (totalCount > 0 && (double) backgroundCount / totalCount > 0.001) {
                                    return r;
                                }
                            }
                        }
                    }
                }
            }
        }

        return maxRadius;
    }

    /**
     * APP2-style dark node and segment pruning.
     * <p>
     * Phase 1: Dark Node Pruning (iterative)
     * - For each leaf, if intensity &lt;= background threshold, remove the leaf
     * - Repeat until no more dark leaves found
     * <p>
     * Phase 2: Dark Segment Pruning
     * - For each terminal branch (path from leaf to branch point):
     * - Delete if average intensity &lt;= background threshold, OR
     * - Delete if &gt;= 20% of nodes are dark (intensity &lt;= threshold)
     */
    protected void darkNodeAndSegmentPruning(final DirectedWeightedGraph graph, final double effectiveThreshold) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<T> srcRA = source.randomAccess();
        final double threshold = effectiveThreshold;

        // Phase 1: Dark Node Pruning (iterative leaf trimming)
        int totalDarkNodesPruned = 0;
        int iteration = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            iteration++;
            final List<SWCPoint> darkLeaves = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // Check intensity at this node
                final long[] pos = new long[dims.length];
                nodeToVoxelPos(v, pos);

                if (isInBounds(pos)) {
                    srcRA.setPosition(pos);
                    final double intensity = srcRA.get().getRealDouble();

                    final boolean isDark = intensity <= threshold;
                    final boolean isLowScore = scoreMapEnabled && scoreMap != null
                            && v.v <= adaptiveScoreThreshold(scoreMapPruneThreshold, v.radius);
                    if (isDark || isLowScore) {
                        darkLeaves.add(v);
                    }
                }
            }

            if (!darkLeaves.isEmpty()) {
                graph.removeAllVertices(darkLeaves);
                totalDarkNodesPruned += darkLeaves.size();
                changed = true;
            }
        }

        if (totalDarkNodesPruned > 0) {
            log("  Dark node pruning: " + iteration + " iterations, removed " + totalDarkNodesPruned + " nodes");
        }

        // Phase 2: Dark Segment Pruning
        // Find terminal branches and check if they're mostly dark
        int darkSegmentsPruned = 0;
        changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> segmentToRemove = new ArrayList<>();

            for (final SWCPoint leaf : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(leaf) != 0 || leaf.equals(root)) continue;

                // Trace back to branch point (node with multiple children) or root
                final List<SWCPoint> segment = new ArrayList<>();
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    segment.add(current);

                    // Stop if we hit a branch point (has other children) or root
                    if (current.equals(root)) break;
                    if (graph.outDegreeOf(current) > 1) break;  // This node has other children

                    // Check parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;

                    final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());

                    // Stop at branch point: parent has multiple outgoing edges
                    if (graph.outDegreeOf(parent) > 1) {
                        // Don't include the branch point itself in the segment
                        break;
                    }

                    current = parent;
                }

                // Skip if segment is empty or just the root
                if (segment.isEmpty()) continue;
                if (segment.size() == 1 && segment.getFirst().equals(root)) continue;

                // Calculate segment statistics
                double sumIntensity = 0;
                int totalNodes = 0;
                int darkNodes = 0;
                double minScore = Double.MAX_VALUE;
                double minRadius = Double.MAX_VALUE;

                for (final SWCPoint node : segment) {
                    final long[] pos = new long[dims.length];
                    nodeToVoxelPos(node, pos);

                    if (isInBounds(pos)) {
                        srcRA.setPosition(pos);
                        final double intensity = srcRA.get().getRealDouble();
                        sumIntensity += intensity;
                        totalNodes++;
                        if (intensity <= threshold) {
                            darkNodes++;
                        }
                        if (scoreMapEnabled && scoreMap != null) {
                            minScore = Math.min(minScore, node.v);
                            minRadius = Math.min(minRadius, node.radius);
                        }
                    }
                }

                // APP2 criteria: delete if average <= threshold OR >= 20% dark nodes
                // Score criteria: also delete if min score <= adaptive threshold
                // (radius-adaptive: thinner segments get a lower bar)
                if (totalNodes > 0) {
                    double avgIntensity = sumIntensity / totalNodes;
                    double darkRatio = (double) darkNodes / totalNodes;

                    boolean shouldPrune = avgIntensity <= threshold || darkRatio >= 0.2;
                    if (!shouldPrune && scoreMapEnabled && scoreMap != null) {
                        final double effectiveScoreThresh = adaptiveScoreThreshold(
                                scoreMapPruneThreshold, minRadius);
                        shouldPrune = minScore <= effectiveScoreThresh;
                    }
                    if (shouldPrune) {
                        segmentToRemove.addAll(segment);
                        changed = true;
                    }
                }
            }

            if (!segmentToRemove.isEmpty()) {
                // Don't remove the root!
                segmentToRemove.remove(root);
                graph.removeAllVertices(segmentToRemove);
                darkSegmentsPruned++;
            }
        }

        if (darkSegmentsPruned > 0) {
            log("  Dark segment pruning: removed " + darkSegmentsPruned + " dark segments");
        }
    }

    /**
     * Performs hierarchical pruning using intensity-weighted coverage.
     * <p>
     * Algorithm:
     * 1. Decompose tree into segments (paths between branch points)
     * 2. Calculate segment length as SUM OF NORMALIZED INTENSITIES (not Euclidean!)
     * 3. Sort segments by length (longest first)
     * 4. For each segment, compute intensity-weighted coverage ratio using PIXEL MASK
     * 5. If coverage > threshold, prune segment and children
     * </p>
     */
    protected void hierarchicalPrune(final DirectedWeightedGraph graph, final double effectiveThreshold) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // APP2 uses intensity-based length: sum of (intensity / maxIntensity)
        // length_thresh = 5 means total normalized intensity sum >= 5
        // This is NOT Euclidean distance!
        final double intensityLengthThresh = minBranchIntensityLength;

        log("Intensity-based length threshold: " + intensityLengthThresh +
                " (sum of normalized intensities)");

        // Get image access for intensity lookup
        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        // COVERAGE MASK: Use imglib2 BitType image to track covered regions
        final Img<BitType> coverageMask = ArrayImgs.bits(dims);  // false = not covered
        final RandomAccess<BitType> coverageRA = coverageMask.randomAccess();

        // Compute intensity-based distance from root to each node
        // APP2: distance = sum of normalized intensities along path
        final Map<SWCPoint, Double> distFromRoot = new HashMap<>();
        computeIntensityDistancesFromRoot(graph, root, distFromRoot, ra);

        // Find all leaves and sort by distance from root (farthest first)
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }
        leaves.sort((a, b) -> Double.compare(
                distFromRoot.getOrDefault(b, 0.0),
                distFromRoot.getOrDefault(a, 0.0)
        ));

        log("Processing " + leaves.size() + " leaves (longest paths first)");

        // Track claimed nodes - ROOT IS ALWAYS CLAIMED FIRST
        final Set<SWCPoint> claimedNodes = new HashSet<>();
        claimedNodes.add(root);

        // Track which leaves have been accepted (for parent-first processing)
        final Set<SWCPoint> acceptedLeaves = new HashSet<>();

        // Mark root's sphere as covered
        markSphereAsCovered(root, coverageMask);

        final Set<SWCPoint> nodesToRemove = new HashSet<>();
        final Set<SWCPoint> rejectedLeaves = new HashSet<>();  // Leaves rejected (too short or covered)
        int keptCount = 0;
        int prunedShort = 0;
        int prunedCovered = 0;
        int coveredByRatio = 0;       // failed srRatio test
        int coveredByMinSignal = 0;   // passed ratio but failed minSignalThreshold
        int coveredByBoth = 0;        // failed both ratio and minSignal
        int coveredAllRedundant = 0;  // sumSignal == 0 (entirely covered)

        // APP2 PARENT-FIRST: Process in multiple passes
        // Only evaluate a segment if its parent segment has been accepted
        // Continue until no more progress can be made
        boolean madeProgress = true;
        while (madeProgress) {
            madeProgress = false;

            for (final SWCPoint leaf : leaves) {
                // Skip already processed leaves
                if (acceptedLeaves.contains(leaf) || rejectedLeaves.contains(leaf)) continue;
                if (!graph.containsVertex(leaf)) continue;

                // Trace from leaf toward root until we hit a claimed node
                final List<SWCPoint> pathNodes = new ArrayList<>();
                SWCPoint current = leaf;
                SWCPoint connectionPoint = null;  // The claimed node we connect to
                double pathLength = 0;  // Now intensity-based, not Euclidean
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    // Stop when we reach a claimed node (this connects us to the tree)
                    if (claimedNodes.contains(current)) {
                        connectionPoint = current;
                        break;
                    }

                    visited.add(current);
                    pathNodes.add(current);

                    // APP2: path length = sum of normalized intensities
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(current, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        pathLength += normalizeIntensity(intensity);
                    }

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // APP2 PARENT-FIRST CHECK:
                // If we didn't reach a claimed node, the parent segment isn't accepted yet - SKIP (defer)
                if (connectionPoint == null) {
                    // Parent not ready yet, try again in next pass
                    continue;
                }

                // If we didn't reach a claimed node properly, mark for removal
                if (pathNodes.isEmpty()) {
                    rejectedLeaves.add(leaf);
                    continue;
                }

                // Filter by intensity-based length
                if (pathLength < intensityLengthThresh) {
                    nodesToRemove.addAll(pathNodes);
                    rejectedLeaves.add(leaf);
                    prunedShort++;
                    madeProgress = true;
                    continue;
                }

                // APP2 PIXEL-BASED COVERAGE: check signal vs redundant using coverage mask
                double sumSignal = 0;
                double sumRedundant = 0;

                for (final SWCPoint node : pathNodes) {
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(node, voxelPos);

                    if (!isInBounds(voxelPos)) {
                        continue;
                    }

                    ra.setPosition(voxelPos);
                    final double intensity = ra.get().getRealDouble();

                    // Check if this voxel CENTER is already covered
                    coverageRA.setPosition(voxelPos);
                    if (coverageRA.get().get()) {
                        // Center already covered → immediately redundant (APP2 line 362)
                        sumRedundant += intensity;
                    } else {
                        // Center NOT covered - BUT STILL CHECK SPHERE OVERLAP (APP2 lines 366-400)
                        // This is CRITICAL: edge branches have uncovered centers but overlapping spheres
                        double sphereOverlap = computeSphereCoverageFromMask(node, coverageMask);
                        if (sphereOverlap > sphereOverlapThreshold) {
                            // >10% of sphere is covered by other branches → redundant
                            sumRedundant += intensity;
                        } else {
                            // <10% covered → this is real signal
                            sumSignal += intensity;
                        }
                    }
                }

                // APP2 keep criterion:
                // Keep if: no redundancy OR (signal/redundant >= srRatio AND signal >= minSignalThreshold)
                // APP2 hardcoded 256 (T_max for 8-bit); we scale to actual image max
                final double minSignalThreshold = maxIntensity;
                final boolean ratioOk = (sumRedundant == 0) || (sumSignal / sumRedundant >= srRatio);
                final boolean signalOk = sumSignal >= minSignalThreshold;
                final boolean keep = (sumRedundant == 0) || (ratioOk && signalOk);

                if (keep) {
                    // Claim all nodes in path - this connects leaf to the main tree
                    claimedNodes.addAll(pathNodes);
                    acceptedLeaves.add(leaf);
                    keptCount++;
                    madeProgress = true;

                    // APP2: Mark all node spheres as covered in the mask
                    for (final SWCPoint node : pathNodes) {
                        markSphereAsCovered(node, coverageMask);
                    }
                } else {
                    nodesToRemove.addAll(pathNodes);
                    rejectedLeaves.add(leaf);
                    prunedCovered++;
                    madeProgress = true;
                    // Diagnose why this branch was rejected
                    if (sumSignal == 0) {
                        coveredAllRedundant++;
                    } else if (!ratioOk && !signalOk) {
                        coveredByBoth++;
                    } else if (!ratioOk) {
                        coveredByRatio++;
                    } else {
                        coveredByMinSignal++;
                    }
                }
            }
        }

        // Any remaining unprocessed leaves are disconnected - remove them
        int unprocessedCount = 0;
        for (final SWCPoint leaf : leaves) {
            if (!acceptedLeaves.contains(leaf) && !rejectedLeaves.contains(leaf)) {
                unprocessedCount++;
                // Trace and remove
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();
                while (current != null && !visited.contains(current) && !claimedNodes.contains(current)) {
                    visited.add(current);
                    nodesToRemove.add(current);
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }
            }
        }
        SNTUtils.log("  hierarchicalPrune: " + leaves.size() + " leaves, kept=" + keptCount +
                ", prunedShort=" + prunedShort + ", prunedCovered=" + prunedCovered +
                " (allRedundant=" + coveredAllRedundant + ", failedRatio=" + coveredByRatio +
                ", failedMinSignal=" + coveredByMinSignal + ", failedBoth=" + coveredByBoth + ")" +
                ", unprocessed=" + unprocessedCount + ", toRemove=" + nodesToRemove.size());

        // Remove only unclaimed nodes
        nodesToRemove.removeAll(claimedNodes);
        graph.removeAllVertices(nodesToRemove);


        log("Kept " + keptCount + " branches, pruned " + prunedShort + " (short) + " + prunedCovered + " (covered)");
        log("After hierarchical pruning: " + graph.vertexSet().size() + " vertices");


        SNTUtils.log("  After main hierarchical: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        // APP2 additional pruning steps
        if (leafPruneEnabled) {
            leafNodePruning(graph);
            SNTUtils.log("  After leafNodePruning: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
            if (jointLeafPruneEnabled) {
                jointLeafPruning(graph);
                SNTUtils.log("  After jointLeafPruning: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
            }
        }

        // Additional: prune short terminal branches iteratively
        pruneShortTerminalBranches(graph, intensityLengthThresh);  // Same threshold
        SNTUtils.log("  After pruneShortTerminal: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");

        // Prune dark terminal branches (low intensity)
        pruneDarkTerminalBranches(graph, effectiveThreshold);
        SNTUtils.log("  After pruneDarkTerminal: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
    }

    /**
     * Unmarks a node's sphere from the coverage count mask.
     * APP2: Only decrement if count > 1 (prevents underflow and maintains proper counting)
     */
    private void unmarkSphereInCountMaskConditional(final SWCPoint node, final RandomAccess<UnsignedShortType> countRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        countRA.setPosition(pos);
                        int current = countRA.get().getInteger();
                        // APP2: Only decrement if > 1
                        if (current > 1) countRA.get().set(current - 1);
                    }
                }
            }
        }
    }

    /**
     * Prunes parallel sibling branches — pairs of terminal branches that fork
     * from the same branch point and then run alongside each other (their nodes
     * stay within a radius-dependent proximity threshold). This is a common FM
     * artifact: when the wavefront crosses a wide neurite, voxels on opposite
     * sides of the centerline form separate parent chains that never re-merge
     * (trees have no cycles), producing two paths that trace the same structure.
     * <p>
     * For every branch point with 2+ children, each pair of sibling terminal
     * segments is compared. A segment is "parallel" to its sibling when most
     * of its nodes fall within {@code max(meanRadius * 3, 3 voxels)} of the
     * nearest node on the other segment. The shorter parallel sibling is pruned.
     */
    protected void pruneParallelBranches(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        int pruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;

            // Collect branch points (nodes with >1 outgoing edge)
            final List<SWCPoint> branchPoints = new ArrayList<>();
            for (final SWCPoint v : graph.vertexSet()) {
                if (graph.outDegreeOf(v) > 1) {
                    branchPoints.add(v);
                }
            }

            outer:
            for (final SWCPoint bp : branchPoints) {
                if (!graph.containsVertex(bp)) continue;

                // Collect stems rooted at this branch point. A "stem" is the
                // chain from the child of bp downstream to the next branch point
                // or leaf (whichever comes first), excluding bp itself.
                final List<SWCWeightedEdge> outEdges = new ArrayList<>(graph.outgoingEdgesOf(bp));
                final List<List<SWCPoint>> stems = new ArrayList<>();

                for (final SWCWeightedEdge edge : outEdges) {
                    final SWCPoint child = graph.getEdgeTarget(edge);
                    final List<SWCPoint> stem = traceStem(graph, child);
                    if (stem != null && !stem.isEmpty()) {
                        stems.add(stem);
                    }
                }

                if (stems.size() < 2) continue;

                // Compare every pair of sibling stems
                for (int i = 0; i < stems.size(); i++) {
                    for (int j = i + 1; j < stems.size(); j++) {
                        final List<SWCPoint> stemA = stems.get(i);
                        final List<SWCPoint> stemB = stems.get(j);

                        // Determine which is shorter
                        final List<SWCPoint> shorter, longer;
                        if (stemA.size() <= stemB.size()) {
                            shorter = stemA;
                            longer = stemB;
                        } else {
                            shorter = stemB;
                            longer = stemA;
                        }

                        if (isParallelSegment(shorter, longer)) {
                            // The last node of the shorter stem may be a
                            // sub-branch-point with children. Re-parent those
                            // children onto the nearest node of the longer stem
                            // before removing the shorter stem's nodes.
                            final SWCPoint shorterTip = shorter.getLast();
                            if (graph.containsVertex(shorterTip) && graph.outDegreeOf(shorterTip) > 0) {
                                reparentChildren(graph, shorterTip, longer);
                            }
                            graph.removeAllVertices(shorter);
                            pruned++;
                            changed = true;
                            break outer; // restart — graph modified
                        }
                    }
                }
            }
        }

        if (pruned > 0) {
            log("Parallel branch pruning: removed " + pruned + " redundant branch(es)");
        }
    }

    /**
     * Traces a stem from the given start node downstream, collecting nodes
     * until a branch point (outDegree &gt; 1) or a leaf (outDegree == 0) is
     * reached. The terminating node (branch point or leaf) IS included in the
     * returned list so that children of a sub-branch-point can be re-parented
     * when the stem is pruned.
     *
     * @return list of nodes in the stem (start → branch-point/leaf), or null
     *         if start has been removed from the graph
     */
    private List<SWCPoint> traceStem(final DirectedWeightedGraph graph,
                                     final SWCPoint start) {
        final List<SWCPoint> segment = new ArrayList<>();
        SWCPoint current = start;
        final Set<SWCPoint> visited = new HashSet<>();

        while (current != null && !visited.contains(current)) {
            visited.add(current);
            segment.add(current);

            final int outDeg = graph.outDegreeOf(current);
            if (outDeg == 0 || outDeg > 1) {
                // Reached leaf or sub-branch-point — stop
                break;
            }
            // Single child — continue
            current = graph.getEdgeTarget(graph.outgoingEdgesOf(current).iterator().next());
        }

        return segment.isEmpty() ? null : segment;
    }

    /**
     * Re-parents all children of {@code oldParent} onto the nearest node
     * in the {@code targetStem}. For each child, a new edge is created from
     * the closest node in targetStem to the child, preserving the subtree.
     */
    private void reparentChildren(final DirectedWeightedGraph graph,
                                  final SWCPoint oldParent,
                                  final List<SWCPoint> targetStem) {
        final List<SWCWeightedEdge> childEdges = new ArrayList<>(graph.outgoingEdgesOf(oldParent));
        for (final SWCWeightedEdge edge : childEdges) {
            final SWCPoint child = graph.getEdgeTarget(edge);
            // Find nearest node on the longer stem
            SWCPoint nearest = targetStem.getFirst();
            double bestDistSq = Double.MAX_VALUE;
            for (final SWCPoint candidate : targetStem) {
                final double dx = child.x - candidate.x;
                final double dy = child.y - candidate.y;
                final double dz = child.z - candidate.z;
                final double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    nearest = candidate;
                }
            }
            // Remove old edge and create new one
            graph.removeEdge(edge);
            graph.addEdge(nearest, child);
        }
    }

    /**
     * Tests whether the shorter segment runs parallel to the longer one.
     * A segment is considered parallel if &ge; 70% of its nodes fall within
     * a proximity threshold of the nearest node on the other segment.
     * The threshold adapts to local radius: {@code max(meanRadius * 3, 3 * avgSpacing)}.
     */
    private boolean isParallelSegment(final List<SWCPoint> shorter, final List<SWCPoint> longer) {
        if (shorter.size() < 3) return false; // too short to judge

        // Compute mean radius across both segments for adaptive threshold
        double sumRadius = 0;
        int radiusCount = 0;
        for (final SWCPoint n : shorter) {
            if (n.radius > 0) { sumRadius += n.radius; radiusCount++; }
        }
        for (final SWCPoint n : longer) {
            if (n.radius > 0) { sumRadius += n.radius; radiusCount++; }
        }
        final double avgSpacing = computeAverageSpacing();
        final double meanRadius = radiusCount > 0 ? sumRadius / radiusCount : avgSpacing;

        // Proximity threshold: 3× mean radius, floored at 3 voxels in physical units
        final double proximityThreshold = Math.max(meanRadius * 3, 3 * avgSpacing);
        final double proxThreshSq = proximityThreshold * proximityThreshold;

        // Count how many nodes of the shorter segment are "close" to the longer
        int closeCount = 0;
        for (final SWCPoint sNode : shorter) {
            double minDistSq = Double.MAX_VALUE;
            for (final SWCPoint lNode : longer) {
                final double dx = sNode.x - lNode.x;
                final double dy = sNode.y - lNode.y;
                final double dz = sNode.z - lNode.z;
                final double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                    if (minDistSq < proxThreshSq) break; // early exit
                }
            }
            if (minDistSq < proxThreshSq) {
                closeCount++;
            }
        }

        final double closeRatio = (double) closeCount / shorter.size();
        return closeRatio >= 0.7;
    }

    /**
     * Computes INTENSITY-WEIGHTED joint coverage fraction (like APP2).
     * Returns [coveredSig, totalSig] where coverage is weighted by image intensity.
     */
    protected double[] computeIntensityWeightedJointCoverage(final SWCPoint node,
                                                           final RandomAccess<UnsignedShortType> countRA,
                                                           final RandomAccess<? extends RealType<?>> srcRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        double coveredSig = 0;
        double totalSig = 0;

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;

                        // Get intensity at this voxel
                        srcRA.setPosition(pos);
                        double intensity = srcRA.get().getRealDouble();
                        totalSig += intensity;

                        // Check if covered by multiple branches (count > 1)
                        countRA.setPosition(pos);
                        if (countRA.get().getInteger() > 1) {
                            coveredSig += intensity;
                        }
                    }
                }
            }
        }

        return new double[]{coveredSig, totalSig};
    }

    /**
     * Computes intensity range for GI normalization.
     * APP2 uses actual image min/max, not threshold-based.
     */
    protected void computeIntensityRange() {
        double min = Double.MAX_VALUE;
        double max = Double.NEGATIVE_INFINITY;
        final Cursor<T> cursor = Views.flatIterable(source).cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            final double v = cursor.get().getRealDouble();
            if (v > max) max = v;
            if (v < min) min = v;
        }
        if (min == Double.MAX_VALUE || max == Double.NEGATIVE_INFINITY) {
            min = 0;
            max = 0;
        }
        this.minIntensity = min;
        this.maxIntensity = max;
    }

    /**
     * Resamples the curve to have evenly spaced points.
     * Keeps branch points and endpoints, removes intermediate points that are too close.
     */
    protected void resampleCurve(final DirectedWeightedGraph graph, final double stepSize) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Convert step size from voxels to physical units
        final double avgSpacing = computeAverageSpacing();
        final double physicalStep = stepSize * avgSpacing;

        // Find branch points (must be preserved)
        final Set<SWCPoint> branchPoints = new HashSet<>();
        branchPoints.add(root);
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) > 1) {
                branchPoints.add(v);
            }
        }

        // Find all leaves
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        // Process each path from leaf to branch point
        final Set<SWCPoint> toRemove = new HashSet<>();

        for (final SWCPoint leaf : leaves) {
            // Trace from leaf to branch point
            final List<SWCPoint> segment = new ArrayList<>();
            SWCPoint current = leaf;
            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                segment.add(current);

                // Stop at branch point (include it)
                if (branchPoints.contains(current) && !current.equals(leaf)) {
                    break;
                }

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }

            if (segment.size() < 3) continue;  // Need at least 3 points to resample

            // Resample this segment: keep first, last, and points at ~stepSize intervals
            double accumulatedDist = 0;

            for (int i = 1; i < segment.size() - 1; i++) {
                final SWCPoint p = segment.get(i);
                accumulatedDist += p.distanceTo(segment.get(i - 1));

                if (accumulatedDist >= physicalStep) {
                    // Keep this point
                    accumulatedDist = 0;
                } else {
                    // Mark for removal (unless it's a branch point)
                    if (!branchPoints.contains(p)) {
                        toRemove.add(p);
                    }
                }
            }
            // Last point (branch point) is always kept
        }

        // Now we need to reconnect the graph after removing points
        // For each point being removed, connect its parent to its child
        for (final SWCPoint removePoint : toRemove) {
            if (!graph.containsVertex(removePoint)) continue;

            // Get parent (source of incoming edge)
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(removePoint);
            if (inEdges.isEmpty()) continue;
            final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());

            // Get children (targets of outgoing edges) - capture BEFORE removal
            final List<SWCPoint> children = new ArrayList<>();
            for (final SWCWeightedEdge childEdge : graph.outgoingEdgesOf(removePoint)) {
                final SWCPoint child = graph.getEdgeTarget(childEdge);
                if (child != null) {
                    children.add(child);
                }
            }

            // Remove the point (this also removes connected edges)
            graph.removeVertex(removePoint);

            // Reconnect: parent -> each child
            for (final SWCPoint child : children) {
                if (graph.containsVertex(child) && graph.containsVertex(parent)) {
                    final SWCWeightedEdge newEdge = graph.addEdge(parent, child);
                    if (newEdge != null) {
                        graph.setEdgeWeight(newEdge, parent.distanceTo(child));
                    }
                }
            }
        }
    }

    protected double getEffectiveThreshold() {
        return (backgroundThreshold >= 0) ? backgroundThreshold : estimateBackgroundThreshold(source);
    }

    private void validateSeedInBounds(final long[] seed) {
        for (int d = 0; d < dims.length; d++) {
            if (seed[d] < 0 || seed[d] >= dims[d]) {
                throw new IllegalArgumentException(
                        "Seed coordinate out of bounds at dimension " + d + ": " + seed[d] +
                                " (valid range: 0.." + (dims[d] - 1) + ")");
            }
        }
    }

    private double normalizeIntensity(final double intensity) {
        return (maxIntensity > 0 && Double.isFinite(maxIntensity)) ? intensity / maxIntensity : 0.0;
    }

    private double computeGWDTTraversalCost(final double gwdt, final double maxGWDT) {
        if (!Double.isFinite(gwdt) || gwdt == Double.MAX_VALUE) {
            return Double.POSITIVE_INFINITY;
        }
        if (!(maxGWDT > 0) || !Double.isFinite(maxGWDT)) {
            return Double.POSITIVE_INFINITY;
        }
        final double clampedGWDT = Math.max(0.0, Math.min(gwdt, maxGWDT));
        return (maxGWDT - clampedGWDT) / maxGWDT;
    }

    /**
     * Computes a synthetic GWDT value for dark voxels inside a gap.
     * Pretends the voxel has intensity {@code threshold + 1} so that
     * gap traversal is expensive but not infinite, and the cost model
     * stays consistent with normal FM traversal.
     */
    private double computeSyntheticGapGWDT(final double threshold, final double maxGWDT) {
        // GWDT for a foreground voxel is roughly proportional to its normalized
        // intensity.  Simulate a barely-above-threshold voxel.
        final double syntheticIntensity = threshold + 1.0;
        final double normIntensity = (maxIntensity > 0 && Double.isFinite(maxIntensity))
                ? syntheticIntensity / maxIntensity : 0.0;
        // Scale to GWDT range (approximate — real GWDT is path-based,
        // but for a single step this is a reasonable proxy)
        return normIntensity * maxGWDT;
    }

    protected long posToIndex(final long[] pos) {
        return posToIndex(pos, dims);
    }

    /** Converts an N-dimensional position to a flat array index using row-major order. */
    public static long posToIndex(final long[] pos, final long[] dims) {
        long idx = 0;
        long stride = 1;
        for (int d = 0; d < pos.length; d++) {
            idx += pos[d] * stride;
            stride *= dims[d];
        }
        return idx;
    }

    /**
     * Convert linear index to position, writing to provided array (avoids allocation).
     */
    protected void indexToPos(final long idx, final long[] pos) {
        long remaining = idx;
        for (int d = 0; d < dims.length; d++) {
            pos[d] = remaining % dims[d];
            remaining /= dims[d];
        }
    }

    /**
     * Converts an SWCPoint's physical coordinates to voxel position.
     * Handles both 2D and 3D images.
     *
     * @param node The SWCPoint with physical coordinates
     * @param pos  Output array (must be dims.length size)
     */
    protected void nodeToVoxelPos(final SWCPoint node, final long[] pos) {
        pos[0] = Math.round(node.x / spacing[0]);
        pos[1] = Math.round(node.y / spacing[1]);
        if (dims.length > 2) {
            pos[2] = Math.round(node.z / spacing[2]);
        }
    }

    /**
     * Checks if a voxel position is within image bounds.
     * Uses ImgUtils.outOfBounds with cached interval bounds.
     */
    protected boolean isInBounds(final long[] pos) {
        return !ImgUtils.outOfBounds(pos, minBounds, maxBounds);
    }

    /**
     * Computes average voxel spacing, handling both 2D and 3D images.
     */
    protected double computeAverageSpacing() {
        double sum = 0;
        for (int d = 0; d < dims.length; d++) {
            sum += spacing[d];
        }
        return sum / dims.length;
    }

    /**
     * Iterates over all neighbor offsets (26-connectivity in 3D).
     */
    public static void iterateNeighbors(final int[] delta, final int dim, final int nDims,
                                        final Runnable action) {
        if (dim == nDims) {
            action.run();
            return;
        }
        for (int d = -1; d <= 1; d++) {
            delta[dim] = d;
            iterateNeighbors(delta, dim + 1, nDims, action);
        }
    }


    /**
     * Marks all voxels within the node's sphere as covered in the mask.
     * Uses RandomAccess for clean imglib2 integration.
     */
    private void markSphereAsCovered(final SWCPoint node, final Img<BitType> coverageMask) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;

        // Use minimum radius of 5 voxels to ensure meaningful coverage detection
        // In thick tubes, this helps the main centerline to "cover" edge voxels
        final int r = (int) Math.max(5, Math.round(node.radius / spacing[0]));

        final RandomAccess<BitType> maskRA = coverageMask.randomAccess();
        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    // Check if within sphere
                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        maskRA.setPosition(pos);
                        maskRA.get().set(true);
                    }
                }
            }
        }
    }

    /**
     * Computes fraction of node's sphere that is covered by the mask.
     * This matches APP2's sphere overlap check in hierarchy coverage pruning.
     */
    private double computeSphereCoverageFromMask(final SWCPoint node, final Img<BitType> coverageMask) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        // Use same minimum radius as marking
        final int r = (int) Math.max(5, Math.round(node.radius / spacing[0]));

        final RandomAccess<BitType> maskRA = coverageMask.randomAccess();
        final double rr = r * r;
        final long[] pos = new long[dims.length];

        int totalVoxels = 0;
        int coveredVoxels = 0;

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        totalVoxels++;
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        maskRA.setPosition(pos);
                        if (maskRA.get().get()) {
                            coveredVoxels++;
                        }
                    }
                }
            }
        }

        return totalVoxels > 0 ? (double) coveredVoxels / totalVoxels : 0.0;
    }

    /**
     * Compute intensity-based distance from root to all nodes.
     * APP2 uses sum of normalized intensities, not Euclidean distance.
     */
    private void computeIntensityDistancesFromRoot(final DirectedWeightedGraph graph,
                                                   final SWCPoint root,
                                                   final Map<SWCPoint, Double> distances,
                                                   final RandomAccess<? extends RealType<?>> ra) {
        distances.clear();
        distances.put(root, 0.0);

        // BFS from root
        final Queue<SWCPoint> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            final SWCPoint current = queue.poll();
            final double currentDist = distances.get(current);

            for (final SWCWeightedEdge edge : graph.outgoingEdgesOf(current)) {
                final SWCPoint child = graph.getEdgeTarget(edge);
                if (!distances.containsKey(child)) {
                    // Get intensity at child position
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(child, voxelPos);

                    double intensity = 0;
                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        intensity = ra.get().getRealDouble();
                    }

                    // APP2: normalize intensity to 0-1
                    distances.put(child, currentDist + normalizeIntensity(intensity));
                    queue.add(child);
                }
            }
        }
    }

    /**
     * Prune terminal branches where average intensity is below threshold
     * or where >20% of nodes are dark. This removes branches tracing through noise.
     */
    private void pruneDarkTerminalBranches(final DirectedWeightedGraph graph,
                                           final double bkgThreshold) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // APP2 uses background threshold directly (NOT max(40, bkg_thresh) which is only for radius)
        log("Prune dark terminal branches, threshold=" + bkgThreshold);

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> branchToRemove = new ArrayList<>();

            // Find all current leaves
            final List<SWCPoint> leaves = new ArrayList<>();
            for (final SWCPoint v : graph.vertexSet()) {
                if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                    leaves.add(v);
                }
            }

            for (final SWCPoint leaf : leaves) {
                // Trace from leaf to branch point
                final List<SWCPoint> branchNodes = new ArrayList<>();
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    branchNodes.add(current);

                    // Stop at root
                    if (current.equals(root)) break;

                    // Stop at branch point (has multiple children)
                    if (graph.outDegreeOf(current) > 1) break;

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // Don't remove main branch to root
                if (current != null && current.equals(root) && branchNodes.contains(root)) {
                    continue;
                }

                // Need at least 2 nodes to evaluate (leaf + at least one more)
                if (branchNodes.size() < 2) continue;

                // Check intensity along branch (exclude junction point)
                double sumIntensity = 0;
                int darkCount = 0;
                int nodeCount = branchNodes.size() - 1;  // Exclude junction

                for (int i = 0; i < nodeCount; i++) {
                    final SWCPoint p = branchNodes.get(i);
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(p, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        sumIntensity += intensity;
                        if (intensity <= bkgThreshold) {
                            darkCount++;
                        }
                    } else {
                        darkCount++;  // Out of bounds = dark
                    }
                }

                double avgIntensity = sumIntensity / nodeCount;
                double darkFraction = (double) darkCount / nodeCount;

                // APP2 criterion: remove if avg intensity <= threshold OR >20% dark nodes
                if (avgIntensity <= bkgThreshold || darkFraction > 0.2) {
                    // Remove all except the junction point
                    for (int i = 0; i < branchNodes.size() - 1; i++) {
                        branchToRemove.add(branchNodes.get(i));
                    }
                }
            }

            if (!branchToRemove.isEmpty()) {
                graph.removeAllVertices(branchToRemove);
                totalPruned += branchToRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Dark branch pruning removed " + totalPruned + " nodes");
            log("  After dark branch pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * Iteratively removes terminal branches (leaf to branch point) shorter than threshold.
     * This cleans up short spikes that survived hierarchical pruning.
     */
    private void pruneShortTerminalBranches(final DirectedWeightedGraph graph,
                                            final double minBranchLength) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> branchToRemove = new ArrayList<>();

            // Find all current leaves
            final List<SWCPoint> leaves = new ArrayList<>();
            for (final SWCPoint v : graph.vertexSet()) {
                if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                    leaves.add(v);
                }
            }

            for (final SWCPoint leaf : leaves) {
                // Trace from leaf to branch point (node with >1 children or root)
                final List<SWCPoint> branchNodes = new ArrayList<>();
                double branchLength = 0;  // Now intensity-based
                SWCPoint current = leaf;
                final Set<SWCPoint> visited = new HashSet<>();

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    branchNodes.add(current);

                    // APP2: branch length = sum of normalized intensities
                    final long[] voxelPos = new long[dims.length];
                    nodeToVoxelPos(current, voxelPos);

                    if (isInBounds(voxelPos)) {
                        ra.setPosition(voxelPos);
                        double intensity = ra.get().getRealDouble();
                        branchLength += normalizeIntensity(intensity);
                    }

                    // Stop at root
                    if (current.equals(root)) break;

                    // Stop at branch point (has multiple children)
                    if (graph.outDegreeOf(current) > 1) break;

                    // Move to parent
                    final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                    if (inEdges.isEmpty()) break;
                    current = graph.getEdgeSource(inEdges.iterator().next());
                }

                // Don't remove if we reached root (main branch)
                if (current != null && current.equals(root) && branchNodes.contains(root)) {
                    continue;
                }

                // Remove branch if too short (but keep the junction point)
                if (branchLength < minBranchLength && branchNodes.size() > 1) {
                    // Remove all except the last node (junction point)
                    for (int i = 0; i < branchNodes.size() - 1; i++) {
                        branchToRemove.add(branchNodes.get(i));
                    }
                }
            }

            if (!branchToRemove.isEmpty()) {
                graph.removeAllVertices(branchToRemove);
                totalPruned += branchToRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Short branch pruning removed " + totalPruned + " nodes");
            log("  After short branch pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * APP2 Leaf Node Pruning: Iteratively remove leaves whose sphere
     * is 90% covered by parent's sphere.
     * CRITICAL: Only remove actual leaf nodes (no children), never branch points.
     */
    private void leafNodePruning(final DirectedWeightedGraph graph) {
        log("Performing leaf node pruning...");

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        final RandomAccess<T> srcRA = source.randomAccess();

        int totalPruned = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            final List<SWCPoint> toRemove = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be TRUE leaf (no children) and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // Get parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(v);
                if (inEdges.isEmpty()) continue;
                final SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());
                if (parent == null) continue;

                // CRITICAL: Don't remove if parent would become isolated or is root
                if (graph.outDegreeOf(parent) <= 1 && graph.inDegreeOf(parent) == 0) {
                    continue;  // Would orphan parent
                }

                // APP2-style intensity-weighted sphere overlap check
                // Count intensity sum in leaf sphere and in overlap region
                final long x1 = Math.round(v.x / spacing[0]);
                final long y1 = Math.round(v.y / spacing[1]);
                final long z1 = dims.length > 2 ? Math.round(v.z / spacing[2]) : 0;
                final int r1 = (int) Math.max(1, Math.round(v.radius / spacing[0]));

                final long x2 = Math.round(parent.x / spacing[0]);
                final long y2 = Math.round(parent.y / spacing[1]);
                final long z2 = dims.length > 2 ? Math.round(parent.z / spacing[2]) : 0;
                final double r2 = Math.max(1, parent.radius / spacing[0]);
                final double r2_sq = r2 * r2;

                double sumLeafInt = 0;
                double sumOverlapInt = 0;
                final long[] pos = new long[dims.length];

                for (int dz = -r1; dz <= r1; dz++) {
                    final long zz = z1 + dz;
                    if (dims.length > 2 && (zz < 0 || zz >= dims[2])) continue;

                    for (int dy = -r1; dy <= r1; dy++) {
                        final long yy = y1 + dy;
                        if (yy < 0 || yy >= dims[1]) continue;

                        for (int dx = -r1; dx <= r1; dx++) {
                            final long xx = x1 + dx;
                            if (xx < 0 || xx >= dims[0]) continue;

                            // Check if inside leaf sphere
                            if (dx * dx + dy * dy + dz * dz > r1 * r1) continue;

                            pos[0] = xx;
                            pos[1] = yy;
                            if (dims.length > 2) pos[2] = zz;
                            srcRA.setPosition(pos);
                            final double intensity = srcRA.get().getRealDouble();

                            sumLeafInt += intensity;

                            // Check if also inside parent sphere
                            final double distToParent_sq = (x2 - xx) * (x2 - xx) + (y2 - yy) * (y2 - yy) + (z2 - zz) * (z2 - zz);
                            if (distToParent_sq <= r2_sq) {
                                sumOverlapInt += intensity;
                            }
                        }
                    }
                }

                // APP2: prune if 90% of intensity is in overlap
                if (sumLeafInt > 0 && sumOverlapInt / sumLeafInt >= leafPruneOverlap) {
                    toRemove.add(v);
                }
            }

            if (!toRemove.isEmpty()) {
                graph.removeAllVertices(toRemove);
                totalPruned += toRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Leaf pruning removed " + totalPruned + " nodes");
        }
    }

    /**
     * APP2 Joint Leaf Pruning: Remove leaves if 90% of sphere is covered
     * by multiple other nodes (not just parent).
     * <p>
     * KNOWN ISSUE: This algorithm is prone to cascading collapse. When a leaf is
     * removed, its parent becomes a new leaf. Because adjacent nodes on the same
     * branch have overlapping spheres (minimum radius = 3 voxels), the parent's
     * sphere is typically 90%+ covered by its own parent/siblings. This causes a
     * chain reaction that can reduce a graph from thousands of vertices down to
     * just the root. The cascade is especially severe on 2D images and images
     * with low resolution where the minimum sphere radius is large relative to
     * branch spacing. Controlled via {@link #jointLeafPruneEnabled}; disabled
     * by default in multi-soma mode to avoid destroying valid reconstructions.
     * TODO: Fix the algorithm to distinguish between same-branch overlap
     * (structural adjacency, should not trigger pruning) and cross-branch
     * overlap (genuine redundancy, should trigger pruning).
     * </p>
     */
    private void jointLeafPruning(final DirectedWeightedGraph graph) {
        log("Performing APP2-style joint leaf pruning...");

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Get image access for intensity lookup
        final RandomAccess<? extends RealType<?>> srcRA = source.randomAccess();

        // Create a coverage COUNT mask (like APP2's mask[ind]++)
        // Each voxel counts how many times it's covered by different branches
        final Img<UnsignedShortType> coverageCount = ArrayImgs.unsignedShorts(dims);
        final RandomAccess<UnsignedShortType> countRA = coverageCount.randomAccess();

        // Mark all node spheres in the coverage count mask
        for (final SWCPoint node : graph.vertexSet()) {
            markSphereInCountMask(node, countRA);
        }

        int totalPruned = 0;
        boolean changed = true;
        int iteration = 0;

        while (changed) {
            changed = false;
            iteration++;

            final List<SWCPoint> toRemove = new ArrayList<>();

            for (final SWCPoint v : graph.vertexSet()) {
                // Must be leaf and not root
                if (graph.outDegreeOf(v) != 0 || v.equals(root)) continue;

                // APP2: Check if 90% of INTENSITY-WEIGHTED sphere coverage has count > 1
                double[] result = computeIntensityWeightedJointCoverage(v, countRA, srcRA);
                double coveredSig = result[0];
                double totalSig = result[1];

                if (totalSig > 0 && coveredSig / totalSig >= 0.9) {
                    toRemove.add(v);
                }
            }

            if (!toRemove.isEmpty()) {
                // Unmark removed nodes from coverage count (only if count > 1, like APP2)
                for (SWCPoint v : toRemove) {
                    unmarkSphereInCountMaskConditional(v, countRA);
                }
                graph.removeAllVertices(toRemove);
                totalPruned += toRemove.size();
                changed = true;
            }
        }

        if (totalPruned > 0) {
            log("  Joint leaf pruning: " + iteration + " iterations, removed " + totalPruned + " nodes");
            log("  After joint pruning: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * Marks a node's sphere in the coverage count mask (increment count).
     */
    private void markSphereInCountMask(final SWCPoint node, final RandomAccess<UnsignedShortType> countRA) {
        final long cx = Math.round(node.x / spacing[0]);
        final long cy = Math.round(node.y / spacing[1]);
        final long cz = dims.length > 2 ? Math.round(node.z / spacing[2]) : 0;
        final int r = (int) Math.max(3, Math.round(node.radius / spacing[0]));

        final double rr = r * r;
        final long[] pos = new long[dims.length];

        for (int dz = -r; dz <= r; dz++) {
            final long z = cz + dz;
            if (dims.length > 2 && (z < 0 || z >= dims[2])) continue;
            for (int dy = -r; dy <= r; dy++) {
                final long y = cy + dy;
                if (y < 0 || y >= dims[1]) continue;
                for (int dx = -r; dx <= r; dx++) {
                    final long x = cx + dx;
                    if (x < 0 || x >= dims[0]) continue;

                    if (dx * dx + dy * dy + dz * dz <= rr) {
                        pos[0] = x;
                        pos[1] = y;
                        if (dims.length > 2) pos[2] = z;
                        countRA.setPosition(pos);
                        int current = countRA.get().getInteger();
                        countRA.get().set(current + 1);
                    }
                }
            }
        }
    }

    // ==================== Tree Building Methods ====================

    /**
     * Traces the neuron and returns a Tree.
     *
     * @return the traced neuron morphology, or null if tracing fails
     */
    public Tree trace() {
        final List<Tree> trees = traceTrees();
        if (trees == null || trees.isEmpty()) {
            return null;
        }
        // If multiple trees, merge them (should only happen with ROI_EDGE)
        if (trees.size() == 1) {
            return trees.getFirst();
        }
        final Tree merged = new Tree();
        trees.forEach(t -> merged.list().addAll(t.list()));
        return merged;
    }

    /**
     * Traces the neuron and returns a list of Trees.
     * <p>
     * With {@link #ROI_EDGE} strategy, returns separate trees for each neurite
     * exiting the soma ROI. With other strategies, returns a single tree.
     * </p>
     *
     * @return list of traced trees, or empty list if tracing fails
     */
    public List<Tree> traceTrees() {
        final DirectedWeightedGraph graph = traceToGraph();
        if (graph == null || graph.vertexSet().size() < 2) {
            log("Tracing failed: No meaningful vertices exist");
            return Collections.emptyList();
        }
        status("Building trees...");
        final List<Tree> trees = tracedTrees(graph);
        if (pathFittingEnabled) {
            trees.forEach(this::fitPaths);
        }
        if (verbose) {
            int totalPaths = 0;
            for (final Tree t : trees) totalPaths += t.size();
            log(String.format("Traced %d tree(s) with %d path(s)", trees.size(), totalPaths));
        }
        return trees;
    }

    /**
     * Traces multiple somas in a single image using a NeuTube-style recovery
     * pass strategy. The expensive GWDT is computed once; Fast Marching is then
     * run once per soma, with previously-traced regions (dilated by
     * {@link #getTracedRegionBuffer()}) excluded from each subsequent pass.
     * <p>
     * Somas should be sorted largest-first (as returned by
     * {@link SomaUtils#detectAllSomas}). Each soma's contour is used to
     * configure the ROI strategy, and the seed is placed at the soma center.
     * Post-processing (pruning, smoothing, etc.) is applied independently to
     * each trace.
     * </p>
     * <p>
     * <b>Auto-filtering:</b> By default, the input list is processed through
     * a soma reduction pipeline (EDT thickness filtering, count-based
     * selection, gap analysis, and/or NMS consolidation) before tracing. This
     * is appropriate when somas come from automatic detection (e.g.,
     * {@link SomaUtils#detectAllSomas}), which may produce many false
     * positives. When providing pre-curated somas — e.g., from user-supplied
     * ROIs via {@link SomaUtils#detectSomasAt} — call
     * {@link #setAutoFilter(boolean) setAutoFilter(false)} to bypass the
     * reduction pipeline and trace all input somas as-is. NMS consolidation
     * via {@link #setMinSomaDistance(double)} is still honored when
     * auto-filtering is disabled, since the caller explicitly requested it.
     * </p>
     *
     * @param somas the detected somas, sorted by radius (largest first)
     * @return list of trees, one per successfully traced soma
     * @throws IllegalStateException if no seed can be derived from any soma
     * @see #setAutoFilter(boolean)
     * @see #setTracedRegionBuffer(int)
     * @see SomaUtils#detectAllSomas
     * @see SomaUtils#detectSomasAt
     */
    public List<Tree> traceMultiSoma(final List<SomaUtils.SomaResult> somas) {
        if (somas == null || somas.isEmpty()) {
            throw new IllegalArgumentException("Soma list cannot be null or empty");
        }

        // --- Soma reduction pipeline ---
        // Four modes depending on which parameters are set:
        //   nSomas > 0 + minSomaDistance > 0 : NMS/consolidation + top-N cap
        //   nSomas > 0 only                  : top-N by EDT thickness (experimental — see setNSomas javadoc)
        //   minSomaDistance > 0 only          : NMS/consolidation (most reliable mode)
        //   neither (auto)                    : thickness filter + gap analysis (experimental)
        //
        // The "minSomaDistance only" mode is the most robust: it uses standard NMS
        // and consolidation, which are well-established in object detection. The
        // EDT-based modes (nSomas, auto) depend on a global binarization threshold
        // for the distance transform, which can be unreliable for images with large
        // connected foreground regions (e.g., JPEGs with compression artifacts).
        List<SomaUtils.SomaResult> workingSomas = new ArrayList<>(somas);
        double effectiveMinSomaDist = minSomaDistance;
        final int effectiveNSomas = nSomas;

        SNTUtils.log("traceMultiSoma: " + somas.size() + " input somas, "
                + "nSomas=" + effectiveNSomas + ", "
                + "minSomaDistance=" + String.format("%.1f", effectiveMinSomaDist)
                + ", autoFilter=" + autoFilterSomas);

        if (autoFilterSomas) {
            if (effectiveNSomas > 0 && somas.size() > effectiveNSomas) {
                // Experimental: keep top-N by EDT thickness. See selectTopSomasByThickness
                // javadoc for known limitations with global binarization thresholds.
                workingSomas = SomaUtils.selectTopSomasByThickness(somas, source, effectiveNSomas);
            }

            if (effectiveMinSomaDist <= 0 && effectiveNSomas <= 0 && somas.size() > 2) {
                // Experimental auto-estimation: thickness filter → gap analysis.
                final List<SomaUtils.SomaResult> thickSomas =
                        SomaUtils.filterSomasByThickness(somas, source);
                if (thickSomas.size() < somas.size()) {
                    SNTUtils.log("Thickness filter: " + somas.size() + " → " + thickSomas.size());
                    workingSomas = thickSomas;
                }

                if (workingSomas.size() > 2) {
                    effectiveMinSomaDist = SomaUtils.estimateMinSomaDistance(workingSomas);
                    if (effectiveMinSomaDist > 0) {
                        SNTUtils.log("Auto-estimated minSomaDistance: " + String.format("%.1f", effectiveMinSomaDist)
                                + " voxels (from " + workingSomas.size() + " thickness-filtered somas)");
                    }
                }
            } else if (effectiveMinSomaDist > 0) {
                SNTUtils.log("Using user-specified minSomaDistance: " + String.format("%.1f", effectiveMinSomaDist));
            }
        } else {
            SNTUtils.log("Auto-filtering disabled: using all " + somas.size() + " input somas as-is");
            if (effectiveMinSomaDist > 0) {
                SNTUtils.log("NMS consolidation still active (minSomaDistance=" +
                        String.format("%.1f", effectiveMinSomaDist) + ")");
            }
        }

        // Consolidate nearby somas (no-op if effectiveMinSomaDist <= 0)
        final List<SomaUtils.SomaResult> consolidated = consolidateSomas(workingSomas, effectiveMinSomaDist);
        if (consolidated.size() != workingSomas.size()) {
            SNTUtils.log("Soma consolidation: " + workingSomas.size() + " → " + consolidated.size());
        }

        if (consolidated.size() == 1) {
            // Single soma after consolidation: delegate to standard pipeline
            configureSomaFromResult(consolidated.getFirst());
            return traceTrees();
        }

        SNTUtils.log("Multi-soma tracing: " + consolidated.size() + " somas, buffer=" + tracedRegionBuffer);

        storage = createStorageBackend();
        storage.setTrackAliveIndices(true); // Required for recovery passes
        SNTUtils.log("Using " + storage.getBackendType() + " storage backend");

        try {
            // Step 1: Compute threshold and GWDT once (image-dependent, seed-independent)
            final double threshold = getEffectiveThreshold();
            SNTUtils.log(String.format("Threshold: %.4f (intensity range: [%.1f, %.1f])",
                    threshold, minIntensity, maxIntensity));
            status("Computing distance transform...");
            storage.computeGWDT(source, threshold, spacing, minIntensity, maxIntensity);
            SNTUtils.log("GWDT max value: " + storage.getMaxGWDT());

            final List<Tree> allTrees = new ArrayList<>();
            Set<Long> claimedVoxels = new HashSet<>();

            // Disable joint leaf pruning in multi-soma mode to avoid cascading collapse
            // (see jointLeafPruning() javadoc for details on the known issue)
            final boolean savedJointLeafPrune = jointLeafPruneEnabled;
            jointLeafPruneEnabled = false;

            // Compute per-soma caliper radius: half the distance to each soma's
            // nearest neighbor (in calibrated units). This limits FM spread to
            // prevent cross-cell contamination via the shared GWDT.
            final double[] caliperRadii = computeCaliperRadii(consolidated);
            for (int s = 0; s < consolidated.size(); s++) {
                SNTUtils.log(String.format("  Soma %d: caliper radius = %.1f",
                        s + 1, caliperRadii[s]));
            }

            for (int i = 0; i < consolidated.size(); i++) {
                final SomaUtils.SomaResult soma = consolidated.get(i);
                SNTUtils.log(String.format("--- Pass %d/%d: center=%s, radius=%.1f ---",
                        i + 1, consolidated.size(), Arrays.toString(soma.center()), soma.radius()));

                // Configure seed from soma center
                configureSomaFromResult(soma);
                if (seedVoxel == null) {
                    SNTUtils.log("Skipping soma " + (i + 1) + ": could not derive seed");
                    continue;
                }
                final long seedIndex = posToIndex(seedVoxel);

                // Re-initialize FM with exclusion mask (first pass has empty mask)
                status("Fast marching (soma " + (i + 1) + "/" + consolidated.size() + ")...");
                if (i == 0) {
                    storage.initializeFastMarching(dims, seedIndex);
                } else {
                    storage.reinitializeFastMarching(dims, seedIndex, claimedVoxels);
                }

                // Run Fast Marching from this soma's seed with caliper-limited spread
                executeFastMarching(threshold, seedIndex, caliperRadii[i]);

                // Collect newly-traced ALIVE indices before building graph
                // (buildGraph may clear tracking state in some backends)
                final Set<Long> currentAlive = storage.getAliveIndices();
                if (currentAlive != null && !currentAlive.isEmpty()) {
                    final Set<Long> newAlive = new HashSet<>(currentAlive);
                    // Remove previously claimed voxels to get only this pass's voxels
                    newAlive.removeAll(claimedVoxels);
                    // Dilate and add to claimed set for next pass
                    if (tracedRegionBuffer > 0) {
                        claimedVoxels.addAll(dilateIndices(newAlive, tracedRegionBuffer));
                    } else {
                        claimedVoxels.addAll(newAlive);
                    }
                    SNTUtils.log("Pass " + (i + 1) + ": " + newAlive.size() + " new ALIVE voxels, "
                            + claimedVoxels.size() + " total claimed");
                } else {
                    SNTUtils.log("Pass " + (i + 1) + ": 0 ALIVE voxels (FM didn't spread)");
                }

                // Build and post-process graph for this soma
                status("Building graph (soma " + (i + 1) + ")...");
                final DirectedWeightedGraph graph = storage.buildGraph(dims, spacing, threshold);
                if (graph == null || graph.vertexSet().size() < 2) {
                    SNTUtils.log("Soma " + (i + 1) + ": graph has " +
                            (graph == null ? 0 : graph.vertexSet().size()) + " vertices, skipping");
                    continue;
                }
                SNTUtils.log("Soma " + (i + 1) + ": graph before pruning: " +
                        graph.vertexSet().size() + " vertices, " + graph.edgeSet().size() + " edges");

                // Apply full post-processing pipeline
                postProcessGraph(graph, threshold);
                SNTUtils.log("Soma " + (i + 1) + ": graph after pruning: " +
                        graph.vertexSet().size() + " vertices, " + graph.edgeSet().size() + " edges");

                // Build trees from the graph
                final List<Tree> trees = tracedTrees(graph);
                SNTUtils.log("Soma " + (i + 1) + ": tracedTrees returned " + trees.size() + " tree(s)");
                for (int t = 0; t < trees.size(); t++) {
                    final Tree tree = trees.get(t);
                    SNTUtils.log("  Tree " + (t + 1) + ": " + tree.size() + " paths, empty=" + tree.isEmpty());
                    if (!tree.isEmpty()) {
                        if (pathFittingEnabled) fitPaths(tree);
                        tree.setLabel("Cell " + (i + 1));
                        allTrees.add(tree);
                    }
                }
            }

            // Restore joint leaf pruning setting
            jointLeafPruneEnabled = savedJointLeafPrune;

            if (allTrees.isEmpty()) {
                SNTUtils.log("Multi-soma tracing produced no valid trees");
            } else {
                int totalPaths = 0;
                for (final Tree t : allTrees) totalPaths += t.size();
                SNTUtils.log(String.format("Multi-soma result: %d tree(s) with %d path(s)",
                        allTrees.size(), totalPaths));
            }
            return allTrees;

        } finally {
            if (storage != null) {
                storage.dispose();
            }
        }
    }

    /**
     * Computes per-soma caliper radii for limiting FM spread in multi-soma mode.
     * For each soma, the caliper radius is {@code caliperFraction × nearest
     * neighbor distance} (in calibrated units). This limits each soma's FM
     * expansion to prevent cross-cell contamination.
     * <p>
     * Returns {@link Double#MAX_VALUE} for all somas if: only one soma is
     * present, or the caliper is disabled ({@code caliperFraction < 0}).
     * </p>
     *
     * @param somas list of detected somas
     * @return array of caliper radii (calibrated units), one per soma
     * @see #setCaliperFraction(double)
     */
    private double[] computeCaliperRadii(final List<SomaUtils.SomaResult> somas) {
        final int n = somas.size();
        final double[] radii = new double[n];

        if (n <= 1 || caliperFraction < 0) {
            Arrays.fill(radii, Double.MAX_VALUE);
            return radii;
        }

        // Precompute calibrated positions (voxel coords * spacing).
        // SomaResult.center() may be {x,y} only; backfill Z from zSlice()
        final double[][] positions = new double[n][];
        for (int i = 0; i < n; i++) {
            final SomaUtils.SomaResult soma = somas.get(i);
            final long[] center = soma.center();
            positions[i] = new double[dims.length];
            for (int d = 0; d < Math.min(center.length, dims.length); d++) {
                positions[i][d] = center[d] * spacing[d];
            }
            if (dims.length > 2 && center.length <= 2 && soma.zSlice() >= 0) {
                positions[i][2] = soma.zSlice() * spacing[2];
            }
        }

        // For each soma, find nearest-neighbor distance
        for (int i = 0; i < n; i++) {
            double minDistSq = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double distSq = 0;
                for (int d = 0; d < positions[i].length; d++) {
                    final double diff = positions[i][d] - positions[j][d];
                    distSq += diff * diff;
                }
                minDistSq = Math.min(minDistSq, distSq);
            }
            radii[i] = Math.sqrt(minDistSq) * caliperFraction;
        }

        return radii;
    }

    /**
     * Consolidates a list of detected somas by merging nearby detections.
     * Uses greedy clustering: iterates through somas (assumed sorted by radius,
     * largest first), and for each unmerged soma, absorbs any remaining somas
     * whose center is within {@code minDist} voxels. The largest-radius soma in
     * each cluster is kept as the representative.
     * <p>
     * Acts as a second line of defense after NMS in
     * {@link SomaUtils#detectAllSomas}. Returns the input list unchanged if
     * {@code minDist} &le; 0.
     * </p>
     *
     * @param somas   list of detected somas (should be sorted by radius, largest first)
     * @param minDist minimum distance in voxels; somas closer than this are merged
     * @return consolidated list with nearby somas merged; same list if no merging needed
     */
    private List<SomaUtils.SomaResult> consolidateSomas(final List<SomaUtils.SomaResult> somas,
                                                         final double minDist) {
        if (minDist <= 0 || somas.size() <= 1) return new ArrayList<>(somas);

        final int n = somas.size();
        final boolean[] merged = new boolean[n];
        final List<SomaUtils.SomaResult> consolidated = new ArrayList<>();

        // Convert minDist from voxels to calibrated units for comparison
        // Use the mean spacing as a representative scale factor
        double meanSpacing = 0;
        for (final double s : spacing) meanSpacing += s;
        meanSpacing /= spacing.length;
        final double mergeDistCal = minDist * meanSpacing;
        final double mergeDistSq = mergeDistCal * mergeDistCal;

        // Precompute calibrated positions
        final double[][] positions = new double[n][];
        for (int i = 0; i < n; i++) {
            final long[] center = somas.get(i).center();
            positions[i] = new double[dims.length];
            for (int d = 0; d < Math.min(center.length, dims.length); d++) {
                positions[i][d] = center[d] * spacing[d];
            }
        }

        for (int i = 0; i < n; i++) {
            if (merged[i]) continue;

            // This soma becomes a cluster representative (largest radius due to sort order)
            consolidated.add(somas.get(i));

            // Absorb nearby somas
            for (int j = i + 1; j < n; j++) {
                if (merged[j]) continue;

                double distSq = 0;
                for (int d = 0; d < positions[i].length; d++) {
                    final double diff = positions[i][d] - positions[j][d];
                    distSq += diff * diff;
                }

                if (distSq <= mergeDistSq) {
                    merged[j] = true;
                }
            }
        }

        return consolidated;
    }

    /**
     * Configures the tracer's seed and soma ROI from a {@link SomaUtils.SomaResult}.
     * Sets the seed voxel at the soma center and, if a contour is available,
     * passes it to {@link #setSomaRoi(ij.gui.Roi, int)}.
     *
     * @param soma the detected soma result
     */
    private void configureSomaFromResult(final SomaUtils.SomaResult soma) {
        // Set seed at soma center (voxel coordinates)
        final long[] center = soma.center();
        seedVoxel = new long[dims.length];
        System.arraycopy(center, 0, seedVoxel, 0, Math.min(center.length, dims.length));
        // Handle Z for 3D images
        if (dims.length > 2 && center.length <= 2 && soma.zSlice() >= 0) {
            seedVoxel[2] = soma.zSlice();
        }
        validateSeedInBounds(seedVoxel);

        // Configure soma ROI if contour available
        if (soma.hasContour()) {
            final ij.gui.Roi contourRoi = soma.createContourRoi(); // never null if hasContour true
            assert contourRoi != null;
            if (soma.zSlice() >= 0) contourRoi.setPosition(soma.zSlice() + 1);
            setSomaRoi(contourRoi, rootStrategy != ROI_UNSET ? rootStrategy : ROI_CENTROID);
            setSomaRoiZPosition(soma.zSlice());
        }
    }

    /**
     * Dilates a set of voxel indices by the given radius, returning all indices
     * within the ball neighborhood of each input index. Uses the image
     * dimensions to stay within bounds.
     *
     * @param indices the seed indices to dilate
     * @param radius  dilation radius in voxels
     * @return dilated set including all original indices plus their neighborhoods
     */
    private Set<Long> dilateIndices(final Set<Long> indices, final int radius) {
        final Set<Long> dilated = new HashSet<>(indices.size() * 2);
        dilated.addAll(indices);

        final int nDims = dims.length;
        final long[] pos = new long[nDims];
        final long[] neighborPos = new long[nDims];
        final int radiusSq = radius * radius;

        for (final long idx : indices) {
            // Convert index to position
            long remaining = idx;
            for (int d = 0; d < nDims; d++) {
                pos[d] = remaining % dims[d];
                remaining /= dims[d];
            }

            // Iterate over the bounding box of the dilation ball
            dilateRecursive(dilated, pos, neighborPos, 0, nDims, radius, radiusSq);
        }
        return dilated;
    }

    /**
     * Recursive helper for {@link #dilateIndices} that iterates over all
     * positions within a ball of given radius around a center position.
     */
    private void dilateRecursive(final Set<Long> dilated, final long[] center,
                                 final long[] pos, final int dim, final int nDims,
                                 final int radius, final int radiusSq) {
        if (dim == nDims) {
            // Check if within sphere
            long distSq = 0;
            for (int d = 0; d < nDims; d++) {
                final long delta = pos[d] - center[d];
                distSq += delta * delta;
            }
            if (distSq <= radiusSq) {
                dilated.add(posToIndex(pos));
            }
            return;
        }
        final long lo = Math.max(0, center[dim] - radius);
        final long hi = Math.min(dims[dim] - 1, center[dim] + radius);
        for (long v = lo; v <= hi; v++) {
            pos[dim] = v;
            dilateRecursive(dilated, center, pos, dim + 1, nDims, radius, radiusSq);
        }
    }

    /**
     * Applies the full post-processing pipeline to a traced graph. Extracted
     * from {@link #traceToGraph()} so it can be reused by
     * {@link #traceMultiSoma(List)}.
     *
     * @param graph     the graph to post-process (modified in place)
     * @param threshold the background threshold used during tracing
     */
    private void postProcessGraph(final DirectedWeightedGraph graph, final double threshold) {
        recalculateRadiiFromImage(graph, threshold);
        SNTUtils.log("  After recalcRadii: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        if (scoreMapEnabled) {
            status("Computing score map...");
            computeAndApplyScoreMap(graph);
            SNTUtils.log("  After scoreMap: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        }
        status("Pruning branches...");
        darkNodeAndSegmentPruning(graph, threshold);
        SNTUtils.log("  After darkPruning: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        hierarchicalPrune(graph, threshold);
        SNTUtils.log("  After hierarchicalPrune: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        if (parallelBranchPruneEnabled) {
            pruneParallelBranches(graph);
            SNTUtils.log("  After parallelPrune: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");
        }
        // Tip extension: use A* to extend leaf tips across gaps larger than
        // maxGapVoxels. Disabled by default (tipExtensionDistance <= 0).
        if (tipExtensionDistance > 0) {
            final SWCPoint root = findGraphRoot(graph);
            if (root != null) {
                final ComponentReconnector<T> reconnector = new ComponentReconnector<>(source, spacing);
                reconnector.setMaxBridgeDistVoxels(tipExtensionDistance);
                reconnector.setVerbose(verbose);

                status("Extending tips across gaps...");
                final int extended = reconnector.extendTips(graph, root, threshold);
                log("Extended " + extended + " tip(s) across gaps");
                SNTUtils.log("  After tipExtension: " + graph.vertexSet().size()
                        + "v, " + graph.edgeSet().size() + "e");
            }
        }
        removeDisconnectedComponents(graph);
        SNTUtils.log("  After removeDisconnected: " + graph.vertexSet().size() + "v, " + graph.edgeSet().size() + "e");

        status("Refining traces...");
        if (smoothEnabled) smoothCurve(graph, smoothWindowSize);
        if (zigzagRemovalEnabled) removeZigzags(graph);
        if (overshootRemovalEnabled) removeOvershoots(graph);
        if (!Double.isNaN(branchTuneMaxAngle) && branchTuneMaxAngle >= 0) {
            tuneBranches(graph, Math.cos(Math.toRadians(branchTuneMaxAngle)));
        }
        if (resampleEnabled) {
            resampleCurve(graph, resampleStep);
            log("After resampling: " + graph.vertexSet().size() + " vertices");
        }
    }

    /**
     * Refines all paths in a tree using {@link PathFitter} with
     * {@link PathFitter#RADII_AND_MIDPOINTS} scope. Node positions are snapped
     * to the signal centerline and radii are recomputed from cross-sectional
     * intensity profiles. Fitting is performed in parallel; results are applied
     * sequentially to preserve path hierarchy.
     *
     * @param tree the tree whose paths will be refined in-place
     */
    private void fitPaths(final Tree tree) {
        status("Fitting paths to signal...");
        final double minScale = Math.min(spacing[0], Math.min(spacing[1],
                spacing.length > 2 ? spacing[2] : spacing[0]));
        final double floor = 3.0 * minScale;
        final double defaultRadius = 5.0 * minScale; // matches PathFitter's default sideSearch=10
        final List<PathFitter> fitters = tree.list().stream()
                .filter(p -> !p.isFittedVersionOfAnotherPath() && p.size() >= 2)
                .map(p -> {
                    final PathFitter fitter = new PathFitter(source, p,
                            spacing[0], spacing[1],
                            spacing.length > 2 ? spacing[2] : 1.0, "");
                    fitter.setScope(PathFitter.RADII_AND_MIDPOINTS);
                    fitter.setReplaceNodes(true);
                    final double meanR = p.getMeanRadius();
                    // Tighten search window for thin neurites without exceeding default
                    final double searchRadius = (meanR > 0)
                            ? Math.min(Math.max(meanR * 2.0, floor), defaultRadius)
                            : defaultRadius;
                    fitter.setCrossSectionRadius(searchRadius);
                    return fitter;
                })
                .toList();
        // Parallel fitting (thread-safe: each fitter works on its own path)
        fitters.parallelStream().forEach(PathFitter::call);
        // Sequential application (modifies path hierarchy)
        int fitted = 0;
        for (final PathFitter fitter : fitters) {
            if (fitter.getSucceeded()) {
                fitter.applyFit();
                fitted++;
            }
        }
        log("Path fitting: " + fitted + "/" + fitters.size() + " paths refined");
        // Light post-fit smooth: PathFitter can introduce jitter from
        // cross-sectional fits. Apply the user's smoothing window as a
        // final polish to coordinates and radii.
        if (smoothEnabled && smoothWindowSize >= 3) {
            final int ws = smoothWindowSize % 2 == 0 ? smoothWindowSize + 1 : smoothWindowSize;
            for (final Path p : tree.list()) {
                if (p.size() > ws) {
                    final Path smoothed = p.getSmoothedPath(ws);
                    p.replaceNodes(smoothed);
                }
            }
        }
    }

    /**
     * Converts the traced graph to Tree(s) based on soma ROI strategy.
     */
    private List<Tree> tracedTrees(final DirectedWeightedGraph graph) {

        // Use APP2-style segment ordering for proper path organization
        // This ensures main trunk is a single path (root → furthest tip)
        final Tree segmentOrderedTree = buildTreeWithSegmentOrdering(graph);

        // Apply soma ROI strategy
        if (somaRoi == null || rootStrategy == ROI_UNSET) {
            return Collections.singletonList(segmentOrderedTree);
        }

        return switch (rootStrategy) {
            case ROI_EDGE -> splitAtSomaBoundary(graph);
            case ROI_CENTROID -> {
                collapseSomaToRoiCentroid(graph);
                yield Collections.singletonList(buildTreeWithSegmentOrdering(graph));
            }
            case ROI_CENTROID_WEIGHTED -> {
                collapseSomaToWeightedCentroid(graph);
                yield Collections.singletonList(buildTreeWithSegmentOrdering(graph));
            }
            default -> Collections.singletonList(segmentOrderedTree);
        };
    }

    /**
     * Builds a Tree from the graph using APP2's segment-based organization.
     * <p>
     * This ensures paths are organized as a human annotator would trace:
     * - Main trunk: root → furthest distal tip (single path)
     * - Branches: each branch point → its furthest tip
     * - Sorted by length (longest first)
     * </p>
     */
    private Tree buildTreeWithSegmentOrdering(final DirectedWeightedGraph graph) {
        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return graph.getTree();

        // Step 1: Compute ownership - which leaf "owns" each node
        // A node is owned by the leaf that has the longest intensity path through it
        final Map<SWCPoint, SWCPoint> nodeOwner = new HashMap<>();  // node -> owning leaf
        final Map<SWCPoint, Double> nodeDistToLeaf = new HashMap<>();  // node -> intensity dist to its leaf

        computeNodeOwnership(graph, nodeOwner, nodeDistToLeaf);

        // Step 2: Build segments - group nodes by owner
        final Map<SWCPoint, HierarchySegment> leafToSegment = new HashMap<>();
        buildHierarchySegments(graph, nodeOwner, nodeDistToLeaf, leafToSegment);

        // Step 3: Sort segments by length (longest first) and build paths
        final List<HierarchySegment> segments = new ArrayList<>(leafToSegment.values());
        segments.sort((a, b) -> Double.compare(b.length, a.length));

        // Step 4: Build Tree with properly ordered paths
        return buildTreeFromSegments(graph, segments);
    }

    /**
     * Builds a Tree from segments, processing longest segments first.
     * This ensures the main trunk is a single continuous path.
     */
    private Tree buildTreeFromSegments(
            final DirectedWeightedGraph graph,
            final List<HierarchySegment> segments) {

        final Tree tree = new Tree();
        if (segments.isEmpty()) return tree;

        final Map<HierarchySegment, Path> segmentToPath = new HashMap<>();

        // Track which segments have been processed
        final Set<HierarchySegment> processed = new HashSet<>();

        // Process segments in order (longest first)
        // But we need to ensure parent segment is processed before child
        boolean madeProgress = true;
        while (madeProgress && processed.size() < segments.size()) {
            madeProgress = false;

            for (final HierarchySegment segment : segments) {
                if (processed.contains(segment)) continue;

                // Can only process if parent is already processed (or no parent = root segment)
                if (segment.parent != null && !processed.contains(segment.parent)) {
                    continue;
                }

                // Create path for this segment
                final Path path = createPathFromSegment(segment);
                if (path == null || path.size() == 0) {
                    processed.add(segment);
                    continue;
                }

                // Set parent path relationship
                if (segment.parent != null) {
                    final Path parentPath = segmentToPath.get(segment.parent);
                    if (parentPath != null) {
                        // Find connection point - the node just before this segment starts
                        final SWCPoint connectionNode = segment.segmentRoot;
                        final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(connectionNode);
                        if (!inEdges.isEmpty()) {
                            final SWCPoint parentNode = graph.getEdgeSource(inEdges.iterator().next());
                            if (parentNode != null) {
                                // Create PointInImage for the branch point
                                final PointInImage branchPoint = new PointInImage(parentNode.x, parentNode.y, parentNode.z);
                                path.setBranchFrom(parentPath, branchPoint);
                            }
                        }
                    }
                }

                segmentToPath.put(segment, path);
                tree.add(path);
                processed.add(segment);
                madeProgress = true;
            }
        }

        return tree;
    }

    /**
     * Creates a Path from a HierarchySegment.
     * Nodes are added from segmentRoot toward leaf (root-to-tip direction).
     */
    private Path createPathFromSegment(final HierarchySegment segment) {
        if (segment.nodes.isEmpty()) return null;

        final Path path = new Path(
                spacing[0],  // x spacing
                spacing[1],  // y spacing
                dims.length > 2 ? spacing[2] : 1.0,  // z spacing
                ""  // units
        );

        // Nodes are stored leaf-to-root, but we need root-to-leaf for the path
        // Reverse to get segmentRoot first, leaf last
        final List<SWCPoint> orderedNodes = new ArrayList<>(segment.nodes);
        Collections.reverse(orderedNodes);

        for (final SWCPoint node : orderedNodes) {
            path.addPointDouble(node.x, node.y, node.z);
            // Set radius if available
            if (path.size() > 0) {
                path.setRadius(node.radius, path.size() - 1);
            }
        }

        return path;
    }

    /**
     * Computes node ownership: which leaf owns each node based on longest intensity path.
     * <p>
     * APP2 assigns each node to the leaf that has the longest total intensity
     * along the path from leaf to that node.
     * </p>
     */
    private void computeNodeOwnership(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf) {

        final SWCPoint root = findGraphRoot(graph);
        if (root == null) return;

        // Find all leaves (nodes with no children, excluding root)
        final List<SWCPoint> leaves = new ArrayList<>();
        for (final SWCPoint v : graph.vertexSet()) {
            if (graph.outDegreeOf(v) == 0 && !v.equals(root)) {
                leaves.add(v);
            }
        }

        final RandomAccess<? extends RealType<?>> ra = source.randomAccess();

        // For each leaf, trace back to root and compute intensity distances
        for (final SWCPoint leaf : leaves) {
            SWCPoint current = leaf;
            double distFromLeaf = 0;

            final Set<SWCPoint> visited = new HashSet<>();

            while (current != null && !visited.contains(current)) {
                visited.add(current);

                // Update ownership if this path is longer
                final double existingDist = nodeDistToLeaf.getOrDefault(current, -1.0);
                if (distFromLeaf > existingDist) {
                    nodeOwner.put(current, leaf);
                    nodeDistToLeaf.put(current, distFromLeaf);
                }

                // Get normalized intensity at current node
                final long[] pos = new long[dims.length];
                pos[0] = Math.round(current.x / spacing[0]);
                pos[1] = Math.round(current.y / spacing[1]);
                if (dims.length > 2) pos[2] = Math.round(current.z / spacing[2]);

                double intensity = 0;
                if (isInBounds(pos)) {
                    ra.setPosition(pos);
                    intensity = ra.get().getRealDouble();
                }

                // Normalize intensity
                final double normalizedIntensity = normalizeIntensity(intensity);
                distFromLeaf += normalizedIntensity;

                // Move to parent
                final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(current);
                if (inEdges.isEmpty()) break;
                current = graph.getEdgeSource(inEdges.iterator().next());
            }
        }
    }

    /**
     * Builds hierarchy segments by grouping nodes by their owning leaf.
     * Each segment contains all nodes owned by a particular leaf.
     */
    private void buildHierarchySegments(
            final DirectedWeightedGraph graph,
            final Map<SWCPoint, SWCPoint> nodeOwner,
            final Map<SWCPoint, Double> nodeDistToLeaf,
            final Map<SWCPoint, HierarchySegment> leafToSegment) {

        final SWCPoint root = findGraphRoot(graph);

        // Group nodes by owning leaf
        final Map<SWCPoint, List<SWCPoint>> leafNodes = new HashMap<>();
        for (final Map.Entry<SWCPoint, SWCPoint> entry : nodeOwner.entrySet()) {
            final SWCPoint node = entry.getKey();
            final SWCPoint leaf = entry.getValue();
            leafNodes.computeIfAbsent(leaf, k -> new ArrayList<>()).add(node);
        }

        // Create segments
        for (final Map.Entry<SWCPoint, List<SWCPoint>> entry : leafNodes.entrySet()) {
            final SWCPoint leaf = entry.getKey();
            final List<SWCPoint> nodes = entry.getValue();

            // Sort nodes by distance from leaf (closest first)
            nodes.sort(Comparator.comparingDouble(a -> nodeDistToLeaf.getOrDefault(a, 0.0)));

            // Find segment root (the node in this segment closest to root, i.e., furthest from leaf)
            SWCPoint segmentRoot = null;
            if (!nodes.isEmpty()) {
                segmentRoot = nodes.getLast();  // Last = furthest from leaf
            }

            // Compute segment length (intensity-weighted)
            final double segmentLength = nodeDistToLeaf.getOrDefault(leaf, 0.0);

            final HierarchySegment segment = new HierarchySegment(leaf, segmentRoot, nodes, segmentLength);
            leafToSegment.put(leaf, segment);
        }

        // Establish parent-child relationships between segments
        for (final HierarchySegment segment : leafToSegment.values()) {
            if (segment.segmentRoot == null || segment.segmentRoot.equals(root)) {
                continue;  // Root segment has no parent
            }

            // Find parent: walk back from segmentRoot to find first node owned by different leaf
            final Set<SWCWeightedEdge> inEdges = graph.incomingEdgesOf(segment.segmentRoot);
            if (inEdges.isEmpty()) continue;

            SWCPoint parent = graph.getEdgeSource(inEdges.iterator().next());
            while (parent != null && nodeOwner.get(parent) == segment.leaf) {
                final Set<SWCWeightedEdge> parentInEdges = graph.incomingEdgesOf(parent);
                if (parentInEdges.isEmpty()) break;
                parent = graph.getEdgeSource(parentInEdges.iterator().next());
            }

            if (parent != null) {
                final SWCPoint parentLeaf = nodeOwner.get(parent);
                if (parentLeaf != null && leafToSegment.containsKey(parentLeaf)) {
                    segment.parent = leafToSegment.get(parentLeaf);
                }
            }
        }
    }

    /**
     * Represents a hierarchical segment as defined by APP2.
     * Each segment connects a leaf to its "ownership boundary" - the point where
     * another leaf's influence takes over.
     */
    protected static class HierarchySegment {
        final SWCPoint leaf;           // The leaf node that "owns" this segment
        final SWCPoint segmentRoot;    // Where this segment connects to parent segment
        final List<SWCPoint> nodes;    // All nodes in this segment (leaf to segmentRoot)
        final double length;           // Intensity-based length
        HierarchySegment parent;       // Parent segment (null for root segment)

        HierarchySegment(SWCPoint leaf, SWCPoint segmentRoot, List<SWCPoint> nodes, double length) {
            this.leaf = leaf;
            this.segmentRoot = segmentRoot;
            this.nodes = nodes;
            this.length = length;
            this.parent = null;
        }
    }
}
