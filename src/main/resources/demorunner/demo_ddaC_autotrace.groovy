/**
 * DemoRunner script: Autotraces the ddaC binary image.
 * Image is already loaded in SNT by DemoRunner. This script configures a
 * BinaryTracer with demo-appropriate defaults, traces, and displays results.
 * DemoRunner sets UI to RUNNING_CMD before launching this script.
 */

import ij.ImagePlus
import sc.fiji.snt.*
import sc.fiji.snt.tracing.auto.BinaryTracer
import sc.fiji.snt.util.TreeUtils

def sntInstance = SNTUtils.getInstance()

try {
    def imp = sntInstance.getImagePlus()

    // Duplicate so the original display image is not skeletonized in place
    def dup = imp.duplicate()
    def roi = imp.getRoi()

    // Configure binary tracer
    sntInstance.setCanvasLabelAllPanes("Skeletonizing..");
    def tracer = new BinaryTracer(dup, true) // true = skeletonize
    tracer.setPruneMode(BinaryTracer.PERIPHERAL_SEGMENTS)
    tracer.setRootRoi(roi, BinaryTracer.ROI_EDGE)
    tracer.setPruneByLength(true)
    tracer.setLengthThreshold(3) // µm
    tracer.setConnectComponents(true)
    tracer.setMaxConnectDist(6) // µm

    // Trace
    sntInstance.setCanvasLabelAllPanes("Autotracer running...");
    def trees = tracer.getTrees()
    if (trees == null || trees.isEmpty()) {
        SNTUtils.log("ddaC demo: tracing produced no results")
        return
    }

    // Post-process: remove single-node paths
    trees.each { tree ->
        tree.list().removeIf { p -> p.size() == 1 && p.getChildren().isEmpty() }
    }
    trees.removeIf { it.isEmpty() }

    // Display results
    def pfm = sntInstance.getPathAndFillManager()
    pfm.clear()
    trees.each { TreeUtils.assignUniqueColors(it, "dim") }
    pfm.addTrees(trees, "BinaryTracer")
    sntInstance.updateAllViewers()

    // Compute image stats (needed by CurationManager) and set up curation
    if (sntInstance.getUI() != null) {
        sntInstance.getUI().runCommand("computeStats")
        sntInstance.getUI().getCurationManager().calibrateFromTrees(trees)
    }
    println("ddaC demo: traced ${trees.size()} tree(s)")

} finally {
    // Restore UI state (DemoRunner set RUNNING_CMD before launching)
    sntInstance?.setCanvasLabelAllPanes(null)
    sntInstance?.getPrefs()?.setTemp("demo-running", false)
    sntInstance?.getUI()?.changeState(SNTUI.READY)
}
