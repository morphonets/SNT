/**
 * file:  ConvertToOmeZarr.groovy
 * info:  Converts an image to a multi-resolution OME-Zarr (Zarr v3, OME-NGFF 0.5) that SNT can stream in BVV/BDV:
 *        zstd-compressed, sharded, and with chunks matching the cells BVV loads. The image is processed one slab
 *        at a time, so it can be larger than the memory available to Fiji/SNT.
 *        Output is written next to the input image as '<name>.ome.zarr'. Existing data is not overwritten:
 *        a numeric suffix is added to the name instead.
 *        Requires: SCIFIO (or Bio-Formats for proprietary formats)
 *        Run in Fiji's Script Editor (Language: Groovy)
 */

// -------- SciJava parameters  --------
#@ IOService ioService
#@ File (label="Input image", style="open") inputFile

import java.util.function.Consumer
import net.imagej.Dataset
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import sc.fiji.snt.SNTPrefs
import sc.fiji.snt.util.ImgUtils


// -------- Validate input and output location --------
if (!inputFile.exists() || !inputFile.canRead()) {
    throw new IOException("Cannot read input file: ${inputFile}\n" +
        "Check that the path is correct and that you have permission to read it.")
}
def parentDir = inputFile.absoluteFile.parentFile
if (!parentDir.canWrite()) {
    throw new IOException("Output directory is not writable: ${parentDir}\n" +
        "The drive may be read-only, or you may not have write permissions.\n" +
        "Try copying the input file to a local directory first.")
}

// -------- Load source image --------
println "Opening: ${inputFile}"
def opened
try {
    opened = ioService.open(inputFile.absolutePath)
} catch (Exception e) {
    throw new IOException("Failed to open image: ${inputFile}\n" +
        "The file may be corrupted or in an unsupported format.\nDetails: ${e.message}", e)
}
def imgPlus = (opened instanceof Dataset) ? ((Dataset) opened).getImgPlus() : opened
if (!(imgPlus instanceof ImgPlus))
    throw new IllegalArgumentException("Unsupported type: " + opened.getClass().getName())
println "Image: ${ImgUtils.axisReport(imgPlus)}"

restoreSpatialUnit(imgPlus, inputFile) // TODO: may not ne needed

// X, Y, Z[, C] layout: singleton axes are dropped and the others reordered, with no pixel copy
def img = ImgUtils.normalizeToXYZ(imgPlus)

// -------- Output path (never overwrites) --------
// 'x.tif', 'x.ome.tif' and 'x.nii.gz' all map to 'x.ome.zarr'
def baseName = inputFile.name.replaceFirst(/\.[^.]+$/, '').replaceFirst(/(?i)\.(ome|nii)$/, '')
def outDir = new File(parentDir, "${baseName}.ome.zarr")
for (int i = 1; outDir.exists(); i++)
    outDir = new File(parentDir, "${baseName}_${i}.ome.zarr")
println "Output: ${outDir.absolutePath}"

// -------- Convert --------
def start = System.currentTimeMillis()
def lastPrint = 0L
def progress = { String msg -> // printed at most every 2 seconds
    def now = System.currentTimeMillis()
    if (now - lastPrint > 2000) {
        println msg
        lastPrint = now
    }
} as Consumer
try {
    ImgUtils.saveAsOmeZarr(img, outDir, SNTPrefs.getThreads(), progress)
} catch (Throwable t) {
    outDir.deleteDir() // do not leave a partial container behind
    throw t
}

println "\nDone in ${(System.currentTimeMillis() - start) / 1000 as long}s! Open in BVV with:"
println "  Bvv.open('${outDir.absolutePath}')"


// -------- Helpers --------
/**
 * At least for some CZI fiels, SCIFIO may drop the spatial unit even though the spacing is correct.
 * This sets the missing unit of the X, Y, Z axes from Bio-Formats metadata, in case they remain unset. 
 * Spacing is left untouched.
 */
def restoreSpatialUnit(ImgPlus imgPlus, File file) {
    def spatialAxes = (0..<imgPlus.numDimensions()).findAll {
        imgPlus.axis(it).type() in [Axes.X, Axes.Y, Axes.Z]
    }
    if (!spatialAxes.any { !imgPlus.axis(it).unit() }) return
    def reader = null
    def symbol = null
    try {
        reader = new loci.formats.ImageReader()
        def store = loci.formats.MetadataTools.createOMEXMLMetadata()
        reader.setMetadataStore(store)
        reader.setId(file.absolutePath)
        symbol = store.getPixelsPhysicalSizeX(0)?.unit()?.getSymbol()
    } catch (Throwable t) {
        println "Could not read the unit from Bio-Formats: ${t}"
    } finally {
        reader?.close()
    }
    if (symbol) {
        spatialAxes.findAll { !imgPlus.axis(it).unit() }.each { imgPlus.axis(it).setUnit(symbol) }
        println "Spatial unit missing in source: set to '${symbol}' from Bio-Formats metadata"
    } else {
        println "WARNING: spatial unit unknown. The OME-Zarr will be written without units (spacing is kept)"
    }
}
