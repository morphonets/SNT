#@File(label="Big data volume (file, folder, or URL)", style="both", persist=false, description="File path to either Imaris (.ims), BDV XML, N5/OME-Zarr (local or remote),\nor a single-level TIFF image. For remote URLs, copy its path directly") dataFile
#@int(label="Resolution level the ROIs are defined on (0: full resolution)", value=3, min=0) fromLevel
#@int(label="Resolution level to map the ROIs to", value=0, min=0) toLevel
#@String(label="Conversion", choices={"Replace ROIs in ROI Manager", "Append mapped copies"}) replaceChoice
#@SNTService snt
#@UIService ui

/**
 * file:    Scale_ROIs_Between_Levels.groovy
 * author:  Tiago Ferreira
 * info:    Maps the 2D ROIs listed in the ROI Manager from the pixel grid of one resolution level of a pyramidal
 *          dataset to the pixel grid of another, and stores the result in the ROI Manager. Any dataset supported by
 *          BigDataUtils#describe works: Imaris (.ims), BDV (.xml), N5/OME-Zarr (local or remote) or a regular image.
 *          Mapping goes through world space, i.e., it accounts for the spacing and origin of each level (see
 *          RoiConverter#scale). Typical use: ROIs drawn on a coarse overview of a large volume are mapped to full
 *          resolution, e.g., to crop or segment only those regions
 *
 * Usage:   Draw/load ROIs on an image of the 'from' level (e.g., an overview exported from the file) and add them to
 *          the ROI Manager. Then run this script, choosing the dataset (or pasting its URL) and the two levels.
 *          Single-level datasets (e.g., TIFF) have only level 0. C,Z,T positions assigned
 *          to ROIs are preserved. Text, image and arrow ROIs are skipped
 *
 * @see     Locations_Montage.groovy, to crop locations from a file on disk
 */

snt.requireVersion("5.0.26") // SNT version required to run this script

if (dataFile == null) {
    ui.showDialog("Please choose a dataset or enter its URL.", "No Dataset")
    return
}

// Handles both local paths and remote URLs (File#getAbsolutePath() would mangle the latter)
path = BigDataUtils.toPathString(dataFile)
if (BigDataUtils.isRemoteUrl(path) && !SNTUtils.isReachable(path, 5000)) {
    ui.showDialog("The URL could not be reached:\n${path}", "Unreachable")
    return
}

// Describe the dataset without reading any pixels. Levels are listed finest first
try {
    info = BigDataUtils.describe(path)
} catch (IllegalArgumentException ex) {
    ui.showDialog("Could not read the dataset: ${ex.getMessage()}", "Error")
    return
}

println(info)
levels = info.levels()

if (fromLevel >= levels.size() || toLevel >= levels.size()) {
    ui.showDialog("The file only has ${levels.size()} resolution level(s): valid levels are 0-${levels.size() - 1}.",
            "Invalid Level")
    return
}

from = levels.get(fromLevel)
to = levels.get(toLevel)

rm = RoiManager.getInstance()
if (rm == null || rm.getCount() == 0) {
    ui.showDialog("The ROI Manager is empty. Add ROIs defined on level ${fromLevel} and re-run this script.",
            "No ROIs")
    return
}

// Scale all ROIs first, so that the manager is only modified once the outcome is known
scaled = []
skipped = 0
for (roi in rm.getRoisAsArray()) {
    try {
        mapped = RoiConverter.scale(roi, from, to)
        mapped.setName("${roi.getName()}_L${toLevel}")
        scaled << mapped
    } catch (IllegalArgumentException ex) {
        println("Skipped '${roi.getName()}': ${ex.getMessage()}")
        skipped++
    }
}

// Never discard ROIs that could not be mapped
replace = replaceChoice.startsWith("Replace")
if (replace && skipped > 0) {
    println("Some ROIs were skipped: mapped copies appended instead of replacing")
    replace = false
}
if (replace) rm.reset()
scaled.each { rm.addRoi(it) }
println("Mapped ${scaled.size()} ROI(s) from level ${fromLevel} to level ${toLevel}"
        + (skipped > 0 ? " (${skipped} skipped)" : ""))
rm.runCommand("Show All")


// Imports below
import ij.plugin.frame.RoiManager
import sc.fiji.snt.analysis.RoiConverter
import sc.fiji.snt.SNTUtils
import sc.fiji.snt.util.BigDataUtils
