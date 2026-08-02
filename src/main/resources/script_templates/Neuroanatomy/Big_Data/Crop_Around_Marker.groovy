#@Double(label="Crop width/height (XY, calibrated units):", value=100, min=1) boxXY
#@Double(label="Crop depth (Z, calibrated units):", value=100, min=0) boxZ
#@boolean(label="Use secondary (filtered) layer, if loaded") useSecondary
#@SNTService snt
#@UIService ui

/**
 * file:    Crop_Around_Marker.groovy
 * author:  Tiago Ferreira
 * info:    Carves out a small region around the last marked location in SNT's Stream-mode BigDataViewer/BigVolumeViewer
 *          and materializes it as a regular, in-RAM ImagePlus. Streamed big-data sources (N5/OME-Zarr/IMS/BDV-XML) are
 *          lazy by design, so most classic ImageJ/SNT operations that require full pixel access can't run on them
 *          directly. This script bridges that gap for the case where only a small, local region is actually needed.
 *
 * Usage:   Press 'M' in the BVV/BDV canvas to mark the location of interest (see the viewer's Markers pane), then run
 *          this script: it crops around the most recently placed arker, however long ago it was placed.
 *
 * @see     Locations_Montage.groovy, for batch-cropping many locations from a file on disk
 */

snt.requireVersion("5.0.14") // SNT version required to run this script

if (!snt.isActive() || snt.getUI() == null) {
    ui.showDialog("SNT does not appear to be running.", "Error")
    return
}
plugin = snt.getInstance()
viewer = snt.getUI().getActiveBigViewer()
if (viewer == null) {
    ui.showDialog("This script requires SNT's Stream mode (BVV/BDV) to be active.", "No Big Viewer Active")
    return
}
if (useSecondary && !plugin.isSecondaryDataAvailable()) {
    ui.showDialog("No secondary (filtered) layer is currently loaded. Using the main image instead.",
            "Secondary Layer Unavailable")
}
data = (useSecondary && plugin.isSecondaryDataAvailable()) ? plugin.getSecondaryData() : plugin.getLoadedData()
if (data == null) {
    ui.showDialog("No image data appears to be loaded.", "Error")
    return
}

// Retrieve the last marker placed in the viewer (world/calibrated coordinates), then convert to pixels
markerPos = viewer.getMarkerManager().getLastPosition()
if (markerPos == null) {
    ui.showDialog("No marker has been placed yet. Press 'M' in the viewer to mark a location, "
            + "then re-run this script.", "No Marker Found")
    return
}
cx = Math.round(markerPos.getX() / plugin.getPixelWidth())
cy = Math.round(markerPos.getY() / plugin.getPixelHeight())
cz = Math.round(markerPos.getZ() / plugin.getPixelDepth())

// Compute crop bounds (pixels), clamped to the data's extent
ndims = data.numDimensions()
hw = Math.round((boxXY / 2) / plugin.getPixelWidth())
hd = Math.round((boxZ / 2) / plugin.getPixelDepth())
min = new long[ndims]
max = new long[ndims]
min[0] = Math.max(0, cx - hw)
max[0] = Math.min(data.dimension(0) - 1, cx + hw)
min[1] = Math.max(0, cy - hw)
max[1] = Math.min(data.dimension(1) - 1, cy + hw)
if (ndims > 2) {
    min[2] = Math.max(0, cz - hd)
    max[2] = Math.min(data.dimension(2) - 1, cz + hd)
}
if (min[0] > max[0] || min[1] > max[1] || (ndims > 2 && min[2] > max[2])) {
    ui.showDialog("The last marker's position falls outside the current image data. Place a new "
            + "marker (M key) over a valid location and try again.", "Invalid Position")
    return
}

// Crop and materialize into a regular, in-RAM ImagePlus (inspired by n5-viewer's CropController:
// https://github.com/saalfeldlab/n5-viewer/blob/master/src/main/java/org/janelia/saalfeldlab/n5/bdv/CropController.java)
cropped = Views.zeroMin(Views.interval(data, min, max))
title = "Crop_x${cx}_y${cy}_z${cz}"
imp = ImgUtils.raiToImp(cropped, title)
imp.setCalibration(plugin.getCalibration())
imp.resetDisplayRange()
imp.show()

dimsStr = (0..<ndims).collect { max[it] - min[it] + 1 }.join("x")
posStr = (0..<ndims).collect { [cx, cy, cz][it] }.join(", ")
println("Extracted ${dimsStr} crop centered at pixel (${posStr})")
println("This crop is now regular, in-RAM data: feed it to Weka classifier training, Sholl "
        + "analysis, or any command that requires full random access -- none of which run "
        + "directly on streamed big data.")


// Imports below
import net.imglib2.view.Views
import sc.fiji.snt.util.ImgUtils
