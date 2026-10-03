#@Integer(label="Channel (1-based, multichannel data only):", value=1, min=1) channel
#@Double(label="Slab start (Z, calibrated units):", value=0, min=0) zStart
#@Double(label="Slab end (Z, calibrated units; -1 for last plane):", value=-1, min=-1) zEnd
#@String(label="Depth color map:", choices={"None", "Viridis", "Plasma", "Spectrum", "Fire", "Ice", "Red-green"}) cmap
#@SNTService snt
#@UIService ui

/**
 * file:    Slab_Projection.groovy
 * author:  Tiago Ferreira
 * info:    Computes a max-intensity projection (MIP) of a Z-slab of the image loaded in SNT, optionally color-coding
 *          depth. Planes are read one at a time, so this works on large, lazy (N5/OME-Zarr/IMS/BDV-XML) sources
 *          without loading them into RAM. Only a single channel is processed: wrap the code below in a loop to
 *          process several channels.
 *
 * Usage:   Load a (big) image in SNT and run this script. To restrict the slab to what is currently visible in the
 *          viewer, note the Z range (calibrated units) from the viewer's slab controls.
 *
 * @see     Crop_Around_Marker.groovy, to materialize a small region around a marker
 */

snt.requireVersion("5.0.19") // SNT version required to run this script

if (!snt.isActive()) {
    ui.showDialog("SNT does not appear to be running.", "Error")
    return
}
plugin = snt.getInstance()
data = plugin.getLoadedData()
if (data == null) {
    ui.showDialog("No image data appears to be loaded.", "Error")
    return
}
if (data.numDimensions() < 3) {
    ui.showDialog("A 3D image is required.", "Error")
    return
}
// Single-channel selection for XYZC data
if (data.numDimensions() > 3)
    data = Views.hyperSlice(data, 3, channel - 1)

// Convert calibrated Z positions to 0-based plane coordinates (must be long)
zAxis = 2
zMin = (long) (data.min(zAxis) + Math.round(zStart / plugin.getPixelDepth()))
zMax = (zEnd >= 0) ? (long) (data.min(zAxis) + Math.round(zEnd / plugin.getPixelDepth())) : data.max(zAxis)

depthCoded = !cmap.equalsIgnoreCase("none")

// Every plane in the slab is read at the loaded resolution: for big data, consider loading a
// lower resolution level first
if (depthCoded) {
    imp = ImgUtils.depthCodedProjection(data, zAxis, zMin, zMax, ColorMaps.get(cmap), 0, 0)
} else {
    mip = ImgUtils.maxProjectionWithDepth(data, zAxis, zMin, zMax).max()
    imp = ImgUtils.raiToImp(mip, "MIP")
    imp.resetDisplayRange()
}
imp.setTitle("Slab_MIP_z${zMin - data.min(zAxis)}-${zMax - data.min(zAxis)}")
imp.setCalibration(plugin.getCalibration())
imp.show()


// Imports below
import net.imglib2.view.Views
import sc.fiji.snt.util.ColorMaps
import sc.fiji.snt.util.ImgUtils
