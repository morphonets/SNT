/**
 * file:    Peripath_Detection_Demo.groovy
 * author:  Tiago Ferreira
 * version: 2026.04.09
 * info:    Demonstrates the PeripathDetector workflow: automated detection of
 *          intensity maxima (spines, varicosities, puncta) around traced paths.
 *
 *          The script loads a demo image of a cultured hippocampal neuron with
 *          synaptic markers, auto-traces a dendrite, fits radii, runs detection,
 *          and then shows how the annular search region (torus mask) can be used
 *          to extract the peri-neuronal signal for further analysis.
 *          NB: sc.fiji.snt.analysis.PathProfiler/NodeProfiler to profile voxel
 *              intensities along paths/across path nodes.
 *
 * @see https://imagej.net/plugins/snt/spines-varicosities
 * @see https://imagej.net/plugins/snt/scripting
 * @see https://javadoc.scijava.org/SNT/
 * @see sc.fiji.snt.analysis.PeripathDetector
 */

#@ SNTService snt

// Ensure SNT is up-to-date and is not busy with another operation
snt.requireVersion("5.0.7")
if (snt.isActive() && snt.getUI() && !snt.getUI().isReady()) {
    print("Please complete current operation before running this script!")
    return
}

// 1) Load a demo image. We'll use  CIL:810: it is a cultured hippocampal
// neuron with 3 channels: Ch1: N-caherin; Ch2: V-glut; Ch3: NMDAR. We'll
// detect  V-glut puncta (Ch2) around a traced dendrite traced from Ch1
imp = snt.demoImage("cil810")
detectionChannel = 2  // V-glut channel

// 2) Trace detection neurite. This demo image has no pre-existing
// reconstructions, so we'll auto-trace a path between two manually chosen
// points on a primary dendrite
plugin = snt.initialize(imp, snt.isActive()) // image to be traced, boolean flag for GUI display
startPoint = SNTPoint.of(58.986, 39.211, 0.0)
endPoint = SNTPoint.of(0.339, 17.967, 0.0)
path = plugin.autoTrace(startPoint, endPoint, null)
println("Traced path: ${path.getName()} (${path.size()} nodes)")

// 3) Fit radii. PeripathDetector uses per-node radii to define the search
// annulus. Without fitted radii, a fallback (2x voxel size) is used for all
// nodes, which may not reflect the actual neurite thickness. path.fitRadii()
// fits circular cross-sections to the image signal at each node, computing
// locally adaptive radii, equivalent to the GUI's "Fit Path" action
path.fitRadii(imp)
println("Mean fitted radius: ${sprintf('%.2f', path.getMeanRadius())} ${path.getCalibration().unit}")

// 4) Configure the detector. The detector searches for intensity maxima in
// an annular band around each path node. The annulus is defined by inner/
// outer radius settings:
//
// innerRadiusMultiplier: where the search starts, as a fraction of the node's
//   fitted radius. 1.0 = at the neurite edge; < 1.0 = includes the membrane;
//   > 1.0 = starts outside the neurite
//
// outerRadiusMultiplier: where the search ends, as a multiple of the node's
//   radius. 2.0 = searches up to 2x the neurite thickness
//
// prominence: noise tolerance for MaximumFinder (grayscale units). A maximum
//   must protrude above its surrounding saddle by at least this  value.
//   Higher = fewer, more confident detections. A good starting point may be
//   ~5% of the channel's dynamic range. It can be previewed using IJ's built-in
//   command
// 
// mergingDistance: nearby detections within this distance (physical units) are
//   merged, keeping the brightest. Set to -1 for auto (= outer radius)
cfg = new PeripathDetector.Config()
    .innerRadiusMultiplier(.70)  // start within the neurite
    .outerRadiusMultiplier(2.5)  // search up to 2.5x neurite thickness
    .prominence(50.0)            // moderate noise tolerance
    .mergingDistance(-1)         // auto
    .assignToNearestPath(true)

// 5) Run detection
detectionImg = ImgUtils.getCtSlice3d(imp, detectionChannel, 1)
paths = [path]
detections = PeripathDetector.detect(paths, detectionImg, cfg)
println("Detected ${detections.size()} maxima in channel ${detectionChannel}")

// 6) Display detections as ROIs. RoiConverter.toPointRoi() creates one
// multi-point ROI per path, with per-point Z positions and the path's
// name/color for easier identification
rm = RoiManager.getInstance2()
if (rm == null) {
    rm = new RoiManager()
}
detections.groupBy {
    it.path
}.each {
    p, dets -> name = "${p.getName()} (${dets.size()} maxima)"
    rm.addRoi(RoiConverter.toPointRoi(dets, imp, name, p.getColor()))
}
rm.runCommand("Sort")
rm.runCommand("Show All")

// 7) Generate and display the torus mask, a binary image representing the
// annular search region.
// NB: This mask is an approximation for visualization and segmentation. The
// detection itself operates on per-node cross-section profiles, _not_ this
// mask. For 2D images, each node contributes a perpendicular line segment;
// small gaps between nodes are expected from digitization.
maskImp = ImpUtils.create("Torus Mask", imp.getWidth(), imp.getHeight(), imp.getNSlices(), 8) // title, dimensions, and bit-depth
maskImp.setCalibration(imp.getCalibration())
PeripathDetector.createTorusMask(paths, maskImp, cfg, 1) // set masked pixels to 1

// 8) Use the mask to extract peri-neuronal signal. We'll multiply the
// detection channel by the normalized mask (0/1) to isolate only the
// signal within the torus region. This is useful for measuring intensities,
// computing integrated density, or feeding into downstream analysis
// (e.g., colocalization, thresholding)
chImp = ImpUtils.getChannel(imp, detectionChannel)
extracted = ImageCalculator.run(chImp, maskImp, "multiply create 32-bit")
extracted.setTitle("Ch${detectionChannel} Signal in Torus")
extracted.show()

println("Done. ${detections.size()} maxima detected. ROIs, torus mask, and extracted signal displayed.")


// Imports below:
import ij.IJ
import ij.plugin.ImageCalculator
import ij.plugin.frame.RoiManager
import sc.fiji.snt.analysis.PeripathDetector
import sc.fiji.snt.analysis.RoiConverter
import sc.fiji.snt.util.ImgUtils
import sc.fiji.snt.util.ImpUtils
import sc.fiji.snt.util.SNTPoint
