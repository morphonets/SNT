/**
 * file: Calibrate_Curation_From_NeuroMorpho.groovy
 * info: Generates a .curation preset file from NeuroMorpho.org reconstructions.
 *       Edit {@code cellIds} with the NeuroMorpho cell names for your cell type 
 *       of interest (e.g., from a NeuroMorpho.org search).
 *       The script will download each cell, compute plausibility thresholds from
 *       percentile statistics, and save the result as a .curation file that can
 *       be loaded in SNT's Curation Assistant panel.
 * rev:  20260419
 */
 
#@ String (label="Preset name:", value="my-cell-type") presetName
#@ String (label="NeuroMorpho.org cell ids:", value="cnic_002,cnic_003,cnic_004") cellIds
#@ String (label="Comment:", value="Calibrated from Wearne_Hof cells") comment
#@ Double (label="Upper percentile:", value=95.0, min=50, max=100, stepSize=1) upperPctl
#@ Double (label="Lower percentile:", value=5.0, min=0, max=50, stepSize=1) lowerPctl
#@ File (label="Workspace directory:", style="directory", required=false) workspaceDir


cellIds = cellIds.split(/\s*,\s*/)

// Download cells
if (!new NeuroMorphoLoader().isDatabaseAvailable()) {
    println("ERROR: NeuroMorpho.org is not reachable. Aborting.")
    return
}

trees = []
cellIds.eachWithIndex { id, idx ->
    print("Loading ${idx + 1}/${cellIds.size()}: ${id}... ")
    try {
        tree = NeuroMorphoLoader.get(id)
        if (tree != null) {
            trees.add(tree)
            println("OK (${tree.list().size()} paths)")
        } else {
            println("SKIPPED (null)")
        }
    } catch (Exception e) {
        println("FAILED: ${e.message}")
    }
}

if (trees.isEmpty()) {
    println("ERROR: No cells could be loaded. Aborting.")
    return
}

println("\nCalibrating from ${trees.size()} cell(s)...")

// Calibrate
calibrator = new PlausibilityCalibrator(trees)
calibrator.setUpperPercentile(upperPctl)
calibrator.setLowerPercentile(lowerPctl)
result = calibrator.calibrate()

println(result.toTable())

// Apply to a temporary monitor so we can save as .curation file
monitor = new PlausibilityMonitor()
result.applyTo(monitor)

if (workspaceDir == null)
    workspaceDir = new File(System.getProperty("user.home"), "SNT_workspace")
dir = PlausibilityCalibrator.getCurationsDirectory(workspaceDir)
filename = presetName.replaceAll("[^a-zA-Z0-9._-]", "_") + "." + PlausibilityCalibrator.CURATION_EXTENSION
outFile = new File(dir, filename)

header = "${comment} (${trees.size()} cells, P${lowerPctl.intValue()}/P${upperPctl.intValue()})"
PlausibilityCalibrator.save(monitor, outFile, header)

println("\nPreset saved to: ${outFile.absolutePath}")
println("Load it in SNT via: Curation Assistant > Options > ${presetName}")

// Imports below
import sc.fiji.snt.analysis.curation.PlausibilityCalibrator
import sc.fiji.snt.analysis.curation.PlausibilityMonitor
import sc.fiji.snt.io.NeuroMorphoLoader