/**
 * file: AutoTracing_Demo_(Microglia_Cells).groovy
 * info: Demonstrates multi-soma autotracing on a 2D image of microglia cells.
 *       The script detects all cell bodies automatically, configures a GWDT
 *       tracer, and reconstructs each cell independently using exclusion masks
 *       to prevent territory overlap. Does not require SNT's GUI, unless it is
 *       already available. Requires SNT 5.0.10 or later, and internet access.
 * rev:  20260520
 */
#@ SNTService snt

// Documentation Resources: https://imagej.net/plugins/snt/scripting
// Latest SNT API: https://javadoc.scijava.org/SNT/

import sc.fiji.snt.*
import sc.fiji.snt.util.*
import sc.fiji.snt.viewer.*
import sc.fiji.snt.tracing.auto.*

// Ensure we are running a compatible version & retrieve demo image
snt.requireVersion("5.0.10")
img = snt.demoImgPlus("microglia") // ImgPlus object

// Step 1: Detect all somas in the image. These will serve as seeds for the
// multi-cell reconstruction. Parameters:
//   img  the image as an ImgPlus
//   -1   intensity threshold (-1 = auto via Otsu)
//   -1   Z-slice (-1 = auto: per-soma auto detection using MIP-based intensity lookup)
//   14   min. soma radius in pixels (smaller detections are discarded)
//   400  min. inter-soma distance in pixels (NMS removes nearby duplicates)
somas = SomaUtils.detectAllSomas(img, -1, -1, 14, 400)
println("Detected ${somas.size()} soma(s)")

// Step 2: Assemble and configure the tracer. GWDTTracerFactory automatically
// selects the optimal storage backend based on image size, scaling from fast
// in-memory processing for typical images to disk-backed out-of-core storage
// for datasets that exceed available RAM
tracer = GWDTTracerFactory.create(img)

// Background threshold: voxels at or below this value are considered background.
// -1 = auto-estimate from image statistics
tracer.setBackgroundThreshold(-1)

// Caliper fraction: controls how far each cell's tracing can extend toward
// its nearest neighbor, as a fraction of the inter-soma distance. At 0.5
// (default), each cell traces up to the midpoint between somas, so
// territories just touch. Values above 0.5 allow territories to overlap,
// letting processes extend past the midpoint — the exclusion mask still
// prevents actual re-tracing. Here, we'll set it to -1 to disable this
// function entirely (the default)
tracer.setCaliperFraction(-1)

// Exclusion buffer (in voxels) applied around traced regions between passes:
// after tracing from one soma, traced voxels are dilated by this buffer before
// being masked out in subsequent Fast Marching runs. Larger values create wider
// exclusion zones, preventing re-tracing of neurites already claimed by another
// cell. 0 = no dilation (only exact traced voxels are excluded). Default: 5
tracer.setTracedRegionBuffer(5)

// Minimum branch intensity-length: sum of normalized intensities (intensity /
// maxIntensity) along a branch. Branches scoring below this are pruned. A value
// of 4 means a branch needs the equivalent of 4 voxels at max intensity. Default: 5
tracer.setMinBranchIntensityLength(4)

// Remove zigzag artifacts: collapses consecutive nodes with sharp angle
// reversals. Default: true
tracer.setZigzagRemovalEnabled(true)

// Multi-voxel gap bridging during Fast Marching: maximum number of consecutive
// dark voxels the FM wavefront can jump across to reach bright signal on the
// far side. FM then continues expanding normally from the bridged voxel,
// tracing the full structure including branches. Gap cost assumes synthetic
// intensity of threshold+1 per dark step, keeping the cost model consistent.
// Set to 0 to disable, 1 for single dark voxel hoping, etc.. Default: 3
tracer.setMaxGapVoxels(3)

// A*-based tip extension: extends leaf tips across gaps larger than
// maxGapVoxels by scanning for bright signal ahead and A*-traing to it.
// Complements FM gap bridging for rare wide contiguous gaps. This is an
// experimental feature. Set to 0 to disable (the default).
//tracer.setTipExtensionDistance(30)

// Enable score-map-based pruning using the built-in Tubeness filter. This
// computes a Hessian-based "tube-likeness" score at each traced node, allowing
// adaptive pruning that is more lenient for thin neurites and stricter for thick
// ones. Filter scales are auto-derived from the radius distribution of each
// cell's reconstruction (computed internally during tracing).
// Alternatives: FRANGI, or an external probability map via setScoreMap().
// Default: disabled
tracer.setScoreMapEnabled(true)
tracer.setScoreMapFilterType(SNT.FilterType.TUBENESS)

// Post-hoc path fitting: refines node positions to signal centerlines using
// cross-sectional intensity fitting (PathFitter), and recomputes radii from
// the fitted profiles. Improves positional accuracy at the cost of extra
// computation. Default: disabled
tracer.setPathFittingEnabled(true)

// Enable curve smoothing w/ moving-average window of 3 nodes
tracer.setSmoothWindowSize(3)

// Note: many other parameters can be tuned (connectivity type, overshoot
// removal, branch tuning angle, etc.). Here we will leave those to defaults.
// See AbstractGWDTTracer API for the full list of options.

// Step 3: Run multi-soma tracing. Returns one Tree per detected soma.
// Each pass uses the GWDT + Fast Marching pipeline independently, with
// exclusion masks preventing territory overlap between cells
trees = tracer.traceMultiSoma(somas)
println("Traced ${trees.size()} cell(s)")
trees.each { println("  ${it.getLabel()}: ${it.size()} paths, ${it.list().sum { it.getNodes().size() }} nodes") }

// Step 4: Filter out failed traces (e.g., cells that only produced a single
// soma path with no neurites). TreeUtils.filterBySize keeps only trees with
// at least the specified min/max number of paths (-1 = no upper bound)
trees = TreeUtils.filterBySize(trees, 2, -1) // keep only trees w/ at least 2 paths
println("${trees.size()} cell(s) after filtering single-path traces")

// Step 5: Display result in main program if available, otherwise in a
// dedicated Reconstruction Viewer
if (snt.isActive() && snt.getUI() != null && snt.getUI().isReady()) {
    snt.initialize(img, true)
    TreeUtils.assignUniqueColors(trees, "dim") //uniqe colors withou dim hues
    snt.loadTrees(trees)
    // W/ UI available, we can initiate assisted proof-reading in Curation Assistant
    snt.getUI().getCurationManager().calibrateFromTrees(trees)
} else {
    Viewer3D.show(trees)
}
println("Done.")