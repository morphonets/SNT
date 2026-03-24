/**
 * file:  ConvertToN5.groovy
 * info:  Auto-generated Groovy script for converting an image to a
 *        multi-resolution N5 dataset suitable for BVV/BDV lazy loading.
 *        to a whole multi-resolution pyramid volume.
 *        Output is a N5 container + BVV/BDV XML file
 *        Requires: n5-imglib2, SCIFIO (or Bio-Formats for proprietary formats)
 *        Run in Fiji's Script Editor (Language: Groovy)
 */

// -------- SciJava parameters  --------
#@Context context
#@IOService ioService

// -------- Script parameters  --------
def inputPath  = '#{INPUT_PATH}'

// -------- Validate input --------
def inputFile = new File(inputPath)
if (!inputFile.exists()) {
    throw new FileNotFoundException("Input file not found: ${inputPath}\n" +
        "Check that the path is correct and the file has not been moved.")
}
if (!inputFile.canRead()) {
    throw new IOException("Cannot read input file: ${inputPath}\n" +
        "Check file permissions (the file may be locked or on a restricted network drive).")
}

// -------- Load source image --------
println "Opening: ${inputPath}"
def opened
try {
    opened = ioService.open(inputPath)
} catch (Exception e) {
    throw new IOException("Failed to open image: ${inputPath}\n" +
        "The file may be corrupted or in an unsupported format.\nDetails: ${e.message}", e)
}
def imgPlus
if (opened instanceof Dataset) {
    imgPlus = ((Dataset) opened).getImgPlus()
} else if (opened instanceof ImgPlus) {
    imgPlus = opened
} else {
    throw new IllegalArgumentException("Unsupported type: " + opened.getClass().getName())
}

println "Image: ${imgPlus.numDimensions()}D, type=${imgPlus.firstElement().getClass().getSimpleName()}"
println ImgUtils.axisReport(imgPlus)

// -------- Resolve axes --------
def (xIdx, yIdx, zIdx) = ImgUtils.findSpatialAxisIndices(imgPlus)
def cIdx = imgPlus.dimensionIndex(Axes.CHANNEL)
if (xIdx < 0 || yIdx < 0) throw new IllegalArgumentException("Image must have X and Y axes")

def sizeX = imgPlus.dimension(xIdx)
def sizeY = imgPlus.dimension(yIdx)
def sizeZ = zIdx >= 0 ? imgPlus.dimension(zIdx) : 1
def nChannels = cIdx >= 0 ? (int) imgPlus.dimension(cIdx) : 1

def cal = ImgUtils.getCalibration(imgPlus)
def voxelSize = [cal.pixelWidth, cal.pixelHeight, cal.pixelDepth] as double[]
def unit = ImgUtils.getSpacingUnits(imgPlus) ?: "pixel"

println "Spatial: ${sizeX}x${sizeY}x${sizeZ}, ${nChannels} channel(s), voxel=${voxelSize} ${unit}"

// -------- Output path  --------
def baseName = inputFile.name.replaceFirst(/\.[^.]+$/, '')
def outDir   = new File(inputFile.parentFile, "${baseName}.n5")
def xmlFile  = new File(inputFile.parentFile, "${baseName}.xml")
println "Output N5:  ${outDir.absolutePath}"
println "Output XML: ${xmlFile.absolutePath}"

// -------- Validate output location --------
if (!inputFile.parentFile.canWrite()) {
    throw new IOException("Output directory is not writable: ${inputFile.parentFile.absolutePath}\n" +
        "The drive may be read-only, or you may not have write permissions.\n" +
        "Try copying the input file to a local directory first.")
}
if (outDir.exists()) {
    if (!outDir.canWrite()) {
        throw new IOException("Existing output directory is not writable: ${outDir.absolutePath}\n" +
            "Delete it or move it, then re-run the script.")
    }
    println "WARNING: Output directory already exists — data will be overwritten."
}

// -------- Compute pyramid levels --------
// Each level halves spatial dimensions until all are ≤ 2048 (macOS GL limit)
def levelDims = []
long lx = sizeX, ly = sizeY, lz = sizeZ
while (true) {
    levelDims.add([lx, ly, lz] as long[])
    if (lx <= 2048 && ly <= 2048 && lz <= 2048) break
    lx = Math.max(1, (long) Math.ceil(lx / 2.0d))
    ly = Math.max(1, (long) Math.ceil(ly / 2.0d))
    lz = Math.max(1, (long) Math.ceil(lz / 2.0d))
}
def nLevels = levelDims.size()
println "Pyramid levels: ${nLevels}"
levelDims.eachWithIndex { d, i -> println "  level ${i}: ${d[0]}x${d[1]}x${d[2]}" }

// -------- Write N5 (block by block) --------
def nThreads = Runtime.getRuntime().availableProcessors()
def executorService = Executors.newFixedThreadPool(nThreads)
def executor = new DefaultTaskExecutor(executorService)
def n5
try {
    n5 = new org.janelia.saalfeldlab.n5.N5FSWriter(outDir.absolutePath)
} catch (Exception e) {
    throw new IOException("Cannot create N5 container at: ${outDir.absolutePath}\n" +
        "Check that the directory is writable and has sufficient disk space.\n" +
        "Details: ${e.message}", e)
}

for (int ch = 0; ch < nChannels; ch++) {
    // Extract this channel as a 3D RAI (X, Y, Z)
    def channelRai
    if (cIdx >= 0 && nChannels > 1) {
        channelRai = Views.hyperSlice(imgPlus, cIdx, (long) ch)
    } else {
        channelRai = imgPlus
    }

    // Level 0: write full-resolution data
    def prevLevel = channelRai // source for downsampling
    for (int level = 0; level < nLevels; level++) {
        def ld = levelDims[level]
        def dataset = "setup${ch}/timepoint0/s${level}"
        int[] blockSize = [
            (int) Math.min(ld[0], 64),
            (int) Math.min(ld[1], 64),
            (int) Math.min(ld[2], 64)
        ]

        println "Writing ch${ch} level ${level}: ${ld[0]}x${ld[1]}x${ld[2]}, blocks=${blockSize[0]}x${blockSize[1]}x${blockSize[2]}"

        // For level 0 use source directly; for level > 0 subsample from previous
        def sourceRai
        if (level == 0) {
            sourceRai = Views.zeroMin(prevLevel)
        } else {
            // Subsample by 2 from previous level (nearest-neighbour for speed)
            sourceRai = Views.subsample(Views.zeroMin(prevLevel), 2, 2, 2)
        }
        // Ensure interval matches expected dimensions
        sourceRai = Views.interval(sourceRai, [0L, 0L, 0L] as long[],
                [ld[0] - 1, ld[1] - 1, ld[2] - 1] as long[])

        n5.createDataset(dataset, ld, blockSize,
            org.janelia.saalfeldlab.n5.DataType.UINT16,
            new org.janelia.saalfeldlab.n5.GzipCompression())

        // Block-wise write
        def blocks = ImgUtils.createIntervals(ld, blockSize as long[])
        def nBlocks = blocks.size()
        def blockCount = 0

        for (def interval : blocks) {
            long[] bMin = Intervals.minAsLongArray(interval)
            long[] bMax = Intervals.maxAsLongArray(interval)
            long[] bDims = Intervals.dimensionsAsLongArray(interval)

            def block = Views.zeroMin(Views.interval(sourceRai, bMin, bMax))
            def mat = ArrayImgs.unsignedShorts(bDims)
            LoopBuilder.setImages(block, mat)
                .multiThreaded(executor)
                .forEachPixel({ a, o -> o.setReal(a.getRealDouble()) } as BiConsumer)

            long[] gridPos = [
                (long)(bMin[0] / blockSize[0]),
                (long)(bMin[1] / blockSize[1]),
                (long)(bMin[2] / blockSize[2])
            ]
            org.janelia.saalfeldlab.n5.imglib2.N5Utils.saveBlock(mat, n5, dataset, gridPos)

            def done = ++blockCount
            if (done % 50 == 0 || done == nBlocks)
                println "  ch${ch} s${level}: ${done}/${nBlocks} blocks"
        }
        prevLevel = sourceRai
        println "  Done: ch${ch} s${level}"
    }

    // Write setup-level attributes required by BDV N5 ImageLoader
    n5.createGroup("setup${ch}")
    def downsamplingFactors = new double[nLevels][]
    for (int l = 0; l < nLevels; l++) {
        def s = (int) Math.pow(2, l)
        downsamplingFactors[l] = [s as double, s as double, s as double] as double[]
    }
    n5.setAttribute("setup${ch}", "downsamplingFactors", downsamplingFactors)
    n5.setAttribute("setup${ch}", "dataType", org.janelia.saalfeldlab.n5.DataType.UINT16.toString())
}

executorService.shutdown()
n5.close()
println "\nN5 written: ${outDir.absolutePath}"

// -------- Write BDV XML descriptor --------
println "Writing BDV XML: ${xmlFile.absolutePath}"
// For multi-channel, we write one setup per channel. For simplicity,
// we'll use the first channel's level dims (same for all)
SpimDataUtils.writeBdvN5Xml(xmlFile, outDir.name, levelDims as long[][],
	voxelSize, unit, 1, baseName, nChannels)

println "\nDone! Open in BVV with:"
println "  Bvv.open('${xmlFile.absolutePath}')"

// -------- Imports --------
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import net.imglib2.parallel.DefaultTaskExecutor
import net.imagej.Dataset
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import net.imglib2.img.array.ArrayImgs
import net.imglib2.loops.LoopBuilder
import sc.fiji.snt.io.SpimDataUtils
import sc.fiji.snt.util.ImgUtils
