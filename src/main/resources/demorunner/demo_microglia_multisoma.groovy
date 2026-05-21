/**
 * DemoRunner script: Multi-soma autotracing of microglia cells.
 * Image is already loaded in SNT by DemoRunner. This script detects somas,
 * configures a GWDT tracer with demo-appropriate defaults, and traces all
 * cells independently.
 * DemoRunner sets UI to RUNNING_CMD before launching this script.
 */

import sc.fiji.snt.*
import sc.fiji.snt.tracing.auto.*
import sc.fiji.snt.util.*

def sntInstance = SNTUtils.getInstance()

try {
    def img = sntInstance.getLoadedDataAsImg(false) // true = secondary layer

    // Step 1: Detect all somas
    sntInstance.setCanvasLabelAllPanes("Detecting somas..");
    def somas = SomaUtils.detectAllSomas(img, -1, -1, 14, 400)
    println("Detected ${somas.size()} soma(s)")

    // Step 2: Configure tracer with demo defaults
    sntInstance.setCanvasLabelAllPanes("Preparing tracer..");
    def tracer = GWDTTracerFactory.create(img)
    tracer.setBackgroundThreshold(-1)
    tracer.setCaliperFraction(-1) // no territory limit
    tracer.setTracedRegionBuffer(5)
    tracer.setScoreMapEnabled(true)
    tracer.setScoreMapFilterType(SNT.FilterType.TUBENESS)
    tracer.setSmoothWindowSize(3)

    // Step 3: Trace all cells
    sntInstance.setCanvasLabelAllPanes("Tracing..");
    def trees = tracer.traceMultiSoma(somas)
    trees = TreeUtils.filterBySize(trees, 2, -1)
    println("Traced ${trees.size()} cell(s)")

    // Step 4: Display results
    def pfm = sntInstance.getPathAndFillManager()
    pfm.clear()

	TreeUtils.assignUniqueColors(trees, "dim")
    pfm.addTrees(trees, "AutoTracer")
    sntInstance.updateAllViewers()

    // Compute image stats (needed by CurationManager) and set up curation
    if (sntInstance.getUI() != null) {
        sntInstance.getUI().runCommand("computeStats")
        sntInstance.getUI().getCurationManager().calibrateFromTrees(trees)
    }
    println("Microglia demo: ${trees.size()} cell(s) reconstructed")

	// Apply grayscale LUT for better visibility of paths
	def imp = SNTUtils.getInstance().getImagePlus()
	ImpUtils.setLut(imp, "Grays")
	imp.updateAndDraw()

} finally {
    // Restore UI state (DemoRunner set RUNNING_CMD before launching)
    sntInstance?.setCanvasLabelAllPanes(null)
    sntInstance?.getPrefs()?.setTemp("demo-running", false)
    sntInstance?.getUI()?.changeState(SNTUI.READY)
}
