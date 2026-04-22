/**
 * file:    Spacing_Analysis.groovy
 * author:  Tiago Ferreira
 * version: 2026.04.21
 * info:    Demonstrates how to compute geodesic (along-the-path) distances
 *          between consecutive detections from PeripathDetector. The script
 *          detects intensity maxima (varicosities, synaptic puncta, etc.)
 *          around traced paths, computes the along-path spacing between
 *          consecutive detections on each path, and displays the results as
 *          a table and a frequency histogram. It also performs a nearest
 *          neighbor (NN) analysis with Clark-Evans statistics to assess
 *          whether detections are clustered, random, or regularly spaced.
 *
 * @see Peripath_Detection_Demo.groovy template script
 * @see https://imagej.net/plugins/snt/spines-varicosities
 * @see https://imagej.net/plugins/snt/scripting
 * @see https://javadoc.scijava.org/SNT/
 * @see sc.fiji.snt.analysis.PeripathDetector
 */

#@ File (style="File", required=false, label="Traced image file (leave empty for demo):") imageFile
#@ File (style="File", required=false, label="Reconstruction file (leave empty for demo):") tracesFile
#@ Integer (value=2, required=false, label="Detection channel:") detectionChannel
#@ SNTService snt

// Ensure SNT is up-to-date and is not busy with another operation
snt.requireVersion("5.0.7")
if (snt.isActive() && snt.getUI() && !snt.getUI().isReady()) {
    print("Please complete current operation before running this script!")
    return
}

// 1) Load image and paths: user-supplied or demo
def getImageAndPaths(fileImg, fileTree) {
    if (fileImg && fileImg.getAbsolutePath()) {
        // user specified local files
        imp = ImpUtils.open(fileImg)
        paths = []
        for (tree in Tree.listFromFile(fileTree.getAbsolutePath()))
            paths.addAll(tree.list())
    } else {
        // demo mode: CIL:810 hippocampal neuron (Ch1: N-cadherin; Ch2: V-glut; Ch3: NMDAR)
        // see Peripath_Detection_Demo.groovy template script
        imp = snt.demoImage("cil810")
        detectionChannel = 2  // V-glut channel
        plugin = snt.initialize(imp, snt.isActive())
        startPoint = SNTPoint.of(58.986, 39.211, 0.0)
        endPoint = SNTPoint.of(0.339, 17.967, 0.0)
        path = plugin.autoTrace(startPoint, endPoint, null)
        println("Traced path: ${path.getName()} (${path.size()} nodes)")
        paths = [path]
    }
    return Tuple.tuple(imp, paths)
}

(imp, paths) = getImageAndPaths(imageFile, tracesFile)

// 2) Fit radii. PeripathDetector uses per-node radii to define the search
// annulus. Without fitted radii, a fallback (2x voxel size) is used
paths.each { p ->
    if (!p.hasRadii()) {
        p.fitRadii(imp)
        println("Fitted radii for ${p.getName()} (mean: ${sprintf('%.2f', p.getMeanRadius())} ${p.getCalibration().unit})")
    }
}

// 3) Configure and run detection
cfg = new PeripathDetector.Config()
    .innerRadiusMultiplier(.70)  // start within the neurite
    .outerRadiusMultiplier(2.5)  // search up to 2.5x neurite thickness
    .prominence(50.0)            // moderate noise tolerance
    .mergingDistance(-1)         // auto
    .assignToNearestPath(true)

detectionImg = ImgUtils.getCtSlice3d(imp, detectionChannel, 1)
detections = PeripathDetector.detect(paths, detectionImg, cfg)
println("Detected ${detections.size()} maxima in channel ${detectionChannel}")

if (detections.size() < 2) {
    println("Not enough detections to compute inter-detection distances.")
    return
}

// 4) Compute geodesic (along-path) distances between consecutive detections.
// Group detections by their associated path and sort by node index so that
// consecutive detections are ordered along the neurite
def geodesicDistance(path, fromIdx, toIdx) {
    // Sum node-to-node Euclidean distances along the path between two node indices
    def start = Math.min(fromIdx, toIdx)
    def end = Math.max(fromIdx, toIdx)
    double dist = 0.0
    for (int i = start; i < end; i++) {
        dist += path.getNode(i).distanceTo(path.getNode(i + 1))
    }
    return dist
}

// Build the results table with inter-detection and NN distances
table = new SNTTable()
unit = paths[0].getCalibration().unit
distColHeader = "Inter-detection Distance (${unit})".toString()
nnColHeader = "NN Distance (${unit})".toString()

// We'll collect NN distances and path-level stats for Clark-Evans analysis
allNNDistances = []
ceTable = new SNTTable()  // summary table for Clark-Evans stats per path

detections.groupBy { it.path }.each { path, dets ->
    // Sort detections along the path by their node index
    dets.sort { it.nodeIndex }
    def nDets = dets.size()

    // Compute consecutive inter-detection distances
    def consecutiveDists = []
    for (int i = 1; i < nDets; i++) {
        consecutiveDists << geodesicDistance(path, dets[i - 1].nodeIndex, dets[i].nodeIndex)
    }

    // Compute NN distance for each detection (min of distances to neighbors)
    def nnDists = []
    for (int i = 0; i < nDets; i++) {
        double nn = Double.MAX_VALUE
        if (i > 0) nn = Math.min(nn, consecutiveDists[i - 1])
        if (i < nDets - 1) nn = Math.min(nn, consecutiveDists[i])
        nnDists << nn
    }
    allNNDistances.addAll(nnDists)

    // Populate the inter-detection distances table
    for (int i = 1; i < nDets; i++) {
        def prev = dets[i - 1]
        def curr = dets[i]
        table.insertRow(null)
        table.appendToLastRow("Path", path.getName())
        table.appendToLastRow("Detection A", "node ${prev.nodeIndex} (I=${sprintf('%.1f', prev.intensity)})".toString())
        table.appendToLastRow("Detection B", "node ${curr.nodeIndex} (I=${sprintf('%.1f', curr.intensity)})".toString())
        table.appendToLastRow(distColHeader, consecutiveDists[i - 1])
        table.appendToLastRow(nnColHeader, nnDists[i])
    }

    // Clark-Evans ratio for this path:
    // R = mean(observed NN) / expected NN, where expected = L / (2n)
    // R < 1: clustered, R ≈ 1: random (Poisson), R > 1: regular/dispersed
    if (nDets >= 2) {
        def pathLength = path.getLength()
        def meanNN = nnDists.sum() / nDets
        def expectedNN = pathLength / (2.0 * nDets)
        def R = meanNN / expectedNN
        def pattern = R < 0.8 ? "clustered" : (R > 1.2 ? "dispersed" : "random")
        ceTable.insertRow(null)
        ceTable.appendToLastRow("Path", path.getName())
        ceTable.appendToLastRow("# Detections", nDets)
        ceTable.appendToLastRow("Path Length (${unit})".toString(), pathLength)
        ceTable.appendToLastRow("Mean NN Dist. (${unit})".toString(), meanNN)
        ceTable.appendToLastRow("Expected NN Dist. (${unit})".toString(), expectedNN)
        ceTable.appendToLastRow("Clark-Evans R", R)
        ceTable.appendToLastRow("Pattern", pattern)
        println("${path.getName()}: R=${sprintf('%.3f', R)} (${pattern}), " +
                "mean NN=${sprintf('%.2f', meanNN)} ${unit}, n=${nDets}")
    }
}

table.show("Inter-detection Geodesic Distances")
ceTable.show("Clark-Evans NN Analysis")

// 5) Display detections as ROIs
rm = RoiManager.getInstance2()
if (rm == null) {
    rm = new RoiManager()
}
detections.groupBy { it.path }.each { p, dets ->
    name = "${p.getName()} (${dets.size()} maxima)"
    rm.addRoi(RoiConverter.toPointRoi(dets, imp, name, p.getColor()))
}
rm.runCommand("Sort")
rm.runCommand("Show All")

// 6) Plot histograms: inter-detection distances and NN distances
histogram = SNTChart.getHistogram(table, [distColHeader, nnColHeader], false)
histogram.show()


println("Done. ${detections.size()} detections across ${paths.size()} path(s). " +
        "${table.getRowCount()} inter-detection distances computed.")


// Imports below:
import ij.plugin.frame.RoiManager
import sc.fiji.snt.analysis.PeripathDetector
import sc.fiji.snt.analysis.RoiConverter
import sc.fiji.snt.analysis.SNTChart
import sc.fiji.snt.analysis.SNTTable
import sc.fiji.snt.Tree
import sc.fiji.snt.util.ImgUtils
import sc.fiji.snt.util.ImpUtils
import sc.fiji.snt.util.SNTPoint
