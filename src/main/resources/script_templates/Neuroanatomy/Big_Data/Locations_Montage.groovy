#@File(required = false, label = "CSV file of X,Y,Z locations") csvFile
#@boolean(label = "File lists locations in pixel coordinates") pixelPositions
#@File(required = false, label = "Image file") imgFile
#@int(label = "Window XY size (in pixels)", min = 4) windowXY
#@int(label = "Window Z thickness (in pixels, 1=2D)", min = 1) windowZ
#@SNTService sntService
#@UIService uiService


/**
 *  Headless companion to Skeletons_and_ROIs/Tip_Montage.groovy: parses a
 *  CSV file of image locations and builds a montage of image neighborhoods
 *  centered at each listed location. The only requirement is that the CSV
 *  must have 'X', 'Y', 'Z' column headings (case-insensitive). Any image
 *  format readable by SCIFIO (including OME-TIFF, ZARR, N5) is supported.
 *
 *  TF 20260609
 */

import sc.fiji.snt.analysis.NodeCollector
import sc.fiji.snt.analysis.SNTTable
import sc.fiji.snt.util.ImgUtils
import sc.fiji.snt.util.SNTPoint


// Abort if running an old version
sntService.requireVersion("5.0.11")

/**
 * Parses a CSV file into a PointInImage list. Header lookup (X/Y/Z) is
 * case and whitespace-insensitive by means of SNTTable#findColumnIndex
 */
def populateFromFile(File file) {
    def table = new SNTTable(file.absolutePath)
    def (xIdx, yIdx, zIdx) = ["X", "Y", "Z"].collect { table.findColumnIndex(it) }
    if ([xIdx, yIdx, zIdx].any { it < 0 })
        throw new IOException("Could not find X, Y, Z columns in ${file.name}")
    (0..<table.rowCount).collect { row ->
        SNTPoint.of(table.get(xIdx, row), table.get(yIdx, row), table.get(zIdx, row))
    }
}


def imgPlus, points

// Load data
if (csvFile && imgFile) {
    // Use SCIFIO to load ImgPlus (both regular and pyramid file formats supported)
    imgPlus = ImgUtils.open(imgFile.getAbsolutePath())
    points = populateFromFile(csvFile)
}
if (!imgPlus || !points) {
    uiService.showDialog("Invalid file paths, or empty/invalid CSV.")
    return
}

// Assemble the NodeCollector
def collector = new NodeCollector(imgPlus, points)
collector.setPointsInPixelSpace(pixelPositions)
collector.setWindow(windowXY, windowZ)

// If the window depth is a slab of multiple planes, we will project it:
def doMIP = (windowZ > 1) ? NodeCollector.Projection.MIP_Z: NodeCollector.Projection.NONE
collector.setProjection(doMIP)

// Retrieve the montage. Each crop is materialized into a contiguous array,
// then assembled by IJ1's MontageMaker. Crops are typically small, so this
// should be RAM-friendly even for thousands of locations
def montage = collector.getMontage(
    -1, // no. of columns (-1=auto)
    -1, // no. of rows (-1=auto)
    1, // scaling factor for each tile in the montage
    2, // width of the border around each tile
    false) // label each tile?
montage.show()

// For per-crop access without building a montage (e.g. saving each crop to
// disk, or further imglib2 processing), skip getMontage() and use:
//   collector.setMaterialize(false) // lazy views into the source ImgPlus
//   collector.getCrops().each { crop -> ... }
println("done")
