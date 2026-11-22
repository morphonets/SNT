#@File(label="Big data volume (file, folder, or URL)", style="both", persist=false, description="Imaris (.ims), BDV XML, N5/OME-Zarr (local or remote), or TIFF image. For remote URLs, copy their paths directly") dataFile
#@Integer(label="Channel (1-based):", value=1, min=1) channel
#@Double(label="Slab start (Z, calibrated units):", value=0, min=0) zStart
#@Double(label="Slab end (Z, calibrated units; -1 for last plane):", value=-1, min=-1) zEnd
#@Integer(label="Resolution level (pyramidal images only):", description="The resolution level to be projected:\n0: Full resolution\n1: 2nd highest resolution, etc.", value=0, min=0) level
#@String(label="Depth color map:", choices={"None", "Viridis", "Plasma", "Spectrum", "Fire", "Ice", "Red-green"}) cmap
#@SNTService snt
#@UIService ui

/**
 * file:    Slab_Projection_(From_File).groovy
 * author:  Tiago Ferreira
 * info:    Computes a max-intensity projection (MIP) of a Z-slab of a dataset specified by path or URL, optionally
 *          color-coding depth. Counterpart of Slab_Projection_(Stream_Mode).groovy: SNT does not need to be
 *          running, and no viewer is required, since the volume is not loaded in memory. Only the requested channel
 *          and resolution level are opened (lazily), and planes are read one at a time, so this works on very large
 *          Imaris, BDV XML, N5/OME-Zarr (local or remote) and TIFF datasets
 *
 * Usage:   Choose the dataset, channel and resolution level (see BigDataUtils#describe for the levels of a dataset),
 *          and the Z range of the slab (calibrated units, relative to the first plane). The display range of the
 *          output is the range of the projection
 *
 * @see     Slab_Projection_(Stream_Mode).groovy, to project what is currently visible in SNT's BVV
 */

snt.requireVersion("5.0.26") // SNT version required to run this script

if (dataFile == null) {
    ui.showDialog("Please choose a dataset or enter its URL.", "No Dataset")
    return
}
path = BigDataUtils.toPathString(dataFile)
if (BigDataUtils.isRemoteUrl(path) && !SNTUtils.isReachable(path, 5000)) {
    ui.showDialog("The URL could not be reached:\n${path}", "Unreachable")
    return
}

// Validate the choices without reading any pixels
try {
    info = BigDataUtils.describe(path)
} catch (IllegalArgumentException ex) {
    ui.showDialog("Could not read the dataset: ${ex.getMessage()}", "Error")
    return
}
println(info)
if (channel > info.nChannels()) {
    ui.showDialog("The dataset only has ${info.nChannels()} channel(s).", "Invalid Channel")
    return
}
level = Math.min(level, info.nLevels() - 1)

// Lazy, single-channel image of the requested level
try {
    ch = BigDataUtils.openChannel(path, channel - 1, 0, level)
} catch (IOException | IllegalArgumentException ex) {
    ui.showDialog("Could not open the dataset: ${ex.getMessage()}", "Error")
    return
}
data = ch.img()
if (data.numDimensions() != 3) {
    ui.showDialog("A 3D image is required.", "Error")
    return
}

// Convert calibrated Z positions to 0-based plane coordinates (must be long). The spacing is that of this level
zAxis = 2
zStep = ch.spacing()[zAxis]
zMin = (long) (data.min(zAxis) + Math.round(zStart / zStep))
zMax = (zEnd >= 0) ? (long) (data.min(zAxis) + Math.round(zEnd / zStep)) : data.max(zAxis)
if (zMin > data.max(zAxis) || zMin > zMax) {
    ui.showDialog("The slab falls outside the image data.", "Invalid Slab")
    return
}

// Every plane in the slab is read at this resolution: for big data, consider choosing a coarser level first
if (!cmap.equalsIgnoreCase("none")) {
    imp = ImgUtils.depthCodedProjection(data, zAxis, zMin, zMax, ColorMaps.get(cmap), 0, 0)
} else {
    mip = ImgUtils.maxProjectionWithDepth(data, zAxis, zMin, zMax).max()
    imp = ImgUtils.raiToImp(mip, "MIP")
    imp.resetDisplayRange()
}
imp.setTitle("Slab_MIP_${ch.name()}${level > 0 ? '_L' + level : ''}_z${zMin - data.min(zAxis)}-${zMax - data.min(zAxis)}")
cal = imp.getCalibration()
cal.pixelWidth = ch.spacing()[0]
cal.pixelHeight = ch.spacing()[1]
cal.pixelDepth = zStep
if (ch.unit()) cal.setUnit(ch.unit())
imp.show()


// Imports below
import sc.fiji.snt.SNTUtils
import sc.fiji.snt.util.BigDataUtils
import sc.fiji.snt.util.ColorMaps
import sc.fiji.snt.util.ImgUtils
