#@File(required = false, label = "CSV file of X,Y,Z locations") csvFile
#@boolean(label = "File lists locations in pixel coordinates") pixelPositions
#@File(required = false, label = "Image file") imgFile
#@int(label = "Window XY size (in pixels)", min = 4) windowXY
#@int(label = "Window Z thickness (in pixels, 1=2D)", min = 1) windowZ
#@int(label = "Channel (1-based index)", min = 1) channel
#@String(label = "Output", choices = {"Montage", "Folder of stacks"}, style = "radioButtonVertical") outputType
#@SNTService sntService
#@UIService uiService


/**
 *  Headless companion to Skeletons_and_ROIs/Tip_Montage.groovy: parses a
 *  CSV file of image locations and builds a montage of image neighborhoods
 *  centered at each listed location. The only requirement is that the CSV
 *  must have 'X', 'Y', 'Z' column headings (case-insensitive). Any image
 *  format readable by SCIFIO (including OME-TIFF, ZARR, N5) is supported.
 *  An optional 'Label' column (marker label) is used to name each location.
 *
 *  Output is either a single montage, or a folder of TIFF stacks (one per
 *  location, named after the marker label and coordinates) created next to
 *  the image file.
 *
 *  TF 20260609
 */

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import sc.fiji.snt.analysis.NodeCollector
import sc.fiji.snt.analysis.SNTTable
import sc.fiji.snt.util.BigDataUtils
import sc.fiji.snt.util.ImgUtils
import sc.fiji.snt.util.ImpUtils
import sc.fiji.snt.util.SNTPoint


// Abort if running an old version
sntService.requireVersion("5.0.11")

/**
 * Parses a CSV file into a list of points. Header lookup (X/Y/Z) is case and
 * whitespace-insensitive by means of SNTTable#findColumnIndex. If a 'Label'
 * column exists, its values are stored in the labels list (else index-based)
 */
def populateFromFile(File file, List labels) {
    def table = new SNTTable(file.absolutePath)
    def (xIdx, yIdx, zIdx) = ["X", "Y", "Z"].collect { table.findColumnIndex(it) }
    if ([xIdx, yIdx, zIdx].any { it < 0 })
        throw new IOException("Could not find X, Y, Z columns in ${file.name}")
    def lIdx = table.findColumnIndex("Label")
    (0..<table.rowCount).collect { row ->
        def lbl = (lIdx >= 0) ? table.get(lIdx, row)?.toString()?.trim() : null
        labels << (lbl ? lbl : "loc-${row + 1}")
        SNTPoint.of(table.get(xIdx, row), table.get(yIdx, row), table.get(zIdx, row))
    }
}

/** Returns a file-system safe version of a string */
def sanitize(String name) {
    name.replaceAll(/[^\w.\-]+/, "_")
}

/** Formats a coordinate for use in file names */
def fmt(double v) {
    String.format("%.1f", v)
}


/**
 * Opens the image. Multi-resolution formats (Imaris, BDV XML, N5, OME-Zarr)
 * are opened lazily, one channel at a time, through BigDataUtils#openChannel
 * which is much faster than SCIFIO for large files. Anything else falls back
 * to ImgUtils#open. Sets the binding variables 'channelIdx' (channel to be cropped) and
 * 'parallelOk' (whether crops can be fetched concurrently)
 */
def openImage(String path) {
    def lower = path.toLowerCase()
    def isBig = [".ims", ".xml", ".n5", ".zarr", ".ome.zarr"].any { lower.endsWith(it) } ||
            BigDataUtils.isRemoteUrl(path)
    if (isBig) {
        try {
            def img = BigDataUtils.openChannel(path, channel - 1, 0).toImgPlus()
            channelIdx = 0 // image is already single-channel
            // Imaris/BDV reads are serialized by BigDataUtils, no gain from threads
            parallelOk = !(lower.endsWith(".ims") || lower.endsWith(".xml"))
            return img
        } catch (Exception ex) {
            println("BigDataUtils could not open file (${ex.message}). Trying SCIFIO...")
        }
    }
    channelIdx = channel - 1
    parallelOk = false
    ImgUtils.open(path) // SCIFIO: regular and pyramid formats
}

/** Reports progress in the Fiji status bar and console (throttled) */
@groovy.transform.Synchronized
def reportProgress(int done, int total, long startMs, String what) {
    def elapsed = (System.currentTimeMillis() - startMs) / 1000d
    if (done == total || done == 1 || elapsed - lastLog >= 5) {
        lastLog = elapsed
        def eta = (done > 0) ? elapsed * (total - done) / done : 0d
        println(String.format("%s %d/%d (%.0f%%), %.0fs elapsed, ~%.0fs left",
                what, done, total, 100d * done / total, elapsed, eta))
    }
}

def imgPlus, points
def labels = []
lastLog = 0d

// Load data
if (csvFile && imgFile) {
    println("Opening ${imgFile.name}...")
    imgPlus = openImage(imgFile.getAbsolutePath())
    points = populateFromFile(csvFile, labels)
    println("Loaded ${points?.size() ?: 0} locations")
}
if (!imgPlus || !points) {
    uiService.showDialog("Invalid file paths, or empty/invalid CSV.")
    return
}

// Assemble the NodeCollector
def collector = new NodeCollector(imgPlus, points)
collector.setPointsInPixelSpace(pixelPositions)
collector.setWindow(windowXY, windowZ)
collector.setChannel(channelIdx)

// For montages, a slab of multiple planes is projected. Stacks are kept as is
def asStacks = (outputType == "Folder of stacks")
def doMIP = (windowZ > 1 && !asStacks) ? NodeCollector.Projection.MIP_Z : NodeCollector.Projection.NONE
collector.setProjection(doMIP)

if (asStacks) {
    // Save one stack per location in a folder next to the image. Crops are
    // fetched one at a time to keep peak memory bounded to a single crop
    def baseName = imgFile.name.replaceFirst(/\.[^.]+$/, "")
    def outDir = new File(imgFile.absoluteFile.parentFile, baseName + "_C${channel}_locations")
    if (!outDir.exists() && !outDir.mkdirs()) {
        uiService.showDialog("Could not create ${outDir.absolutePath}")
        return
    }
    collector.setMaterialize(true)
    // Resolve unique file names up front (cheap), then fetch/save crops
    def used = [] as Set
    def names = points.indices.collect { i ->
        def pt = points[i]
        def name = sanitize("${labels[i]}_X${fmt(pt.getX())}_Y${fmt(pt.getY())}_Z${fmt(pt.getZ())}")
        if (!used.add(name)) name = "${name}_${i + 1}"
        name
    }
    def n = points.size()
    def t0 = System.currentTimeMillis()
    def done = new AtomicInteger()
    def task = { int i ->
        def imp = ImgUtils.toImagePlus(collector.getCrop(points[i]), names[i])
        ImpUtils.save(imp, new File(outDir, names[i] + ".tif").absolutePath)
        imp.close()
        reportProgress(done.incrementAndGet(), n, t0, "Saving stacks")
    }
    def nThreads = parallelOk ? Math.min(Runtime.runtime.availableProcessors(), 8) : 1
    if (nThreads > 1) {
        println("Using ${nThreads} threads")
        def pool = Executors.newFixedThreadPool(nThreads)
        def futures = points.indices.collect { i -> pool.submit({ task(i) } as Runnable) }
        pool.shutdown()
        futures.each { it.get() } // propagates exceptions
        pool.awaitTermination(1, TimeUnit.MINUTES)
    } else {
        points.indices.each { i -> task(i) }
    }
    println("Saved ${n} stacks to ${outDir.absolutePath}")
} else {
    // Retrieve the montage. Each crop is materialized into a contiguous
    // array, then assembled by IJ1's MontageMaker. Crops are typically
    // small, so this should be RAM-friendly even for thousands of locations
    println("Assembling montage of ${points.size()} locations. This may take a while...")
    def montage = collector.getMontage(
        -1, // no. of columns (-1=auto)
        -1, // no. of rows (-1=auto)
        1, // scaling factor for each tile in the montage
        2, // width of the border around each tile
        false) // label each tile?
    montage.show()
}

println("done")
