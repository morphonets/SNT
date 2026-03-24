/**
 * file:  ChannelUnmixing.groovy
 * info:  Auto-generated script that applies SNT Bvv's channel un-mixing
 *        to a whole multi-resolution pyramid volume. Requires n5-imglib2.
 *        Output is a N5 container + BVV/BDV XML file
 *        Run in Fiji's Script Editor (Language: Groovy)
 *
 * Formula: result = #{SIG_NAME} - #{WEIGHT} * #{SUB_NAME}
 * Source:  #{INPUT_PATH}
 * Signal setup: #{SIG_SETUP}, Background setup: #{SUB_SETUP}
 * Levels: #{N_LEVELS}, Timepoints: #{N_TIMEPOINTS}
 */


// -------- Unmixing operation  --------
// Swap this class to change the pixel-wise operation for any
// pixel-wise operation (e.g., ratio, linear combo, etc.)
// Contract: apply(double signal, double background) -> double
class UnmixingOp {
    final double weight
    UnmixingOp(double weight) { this.weight = weight }

    /** Weighted subtraction, clamped to [0, 65535]. */
    double apply(double signal, double background) {
        return Math.max(0, Math.min(65535, signal - weight * background))
    }

    String describe() { "subtract_w${String.format('%.2f', weight)}" }
}

// -------- Parameters  --------
def inputPath   = '#{INPUT_PATH}'
def sigSetup    = #{SIG_SETUP}
def subSetup    = #{SUB_SETUP}
def weight      = #{WEIGHT}
def nLevels     = #{N_LEVELS}
def nTimepoints = #{N_TIMEPOINTS}
def nThreads    = Runtime.getRuntime().availableProcessors()

def op = new UnmixingOp(weight)

// -------- Validate input --------
def inputFile = new File(inputPath)
if (!inputFile.exists()) {
    throw new FileNotFoundException(
        "Input file not found: ${inputPath}\n" +
        "Check that the path is correct and the file has not been moved.")
}
if (!inputFile.canRead()) {
    throw new IOException(
        "Cannot read input file: ${inputPath}\n" +
        "Check file permissions (the file may be locked or on a restricted network drive).")
}

// -------- Output path  --------
def baseName = inputFile.name.replaceFirst(/\.[^.]+$/, '')
def outDir   = new File(inputFile.parentFile, "${baseName}_unmixed_${op.describe()}")
println "Output: ${outDir.absolutePath}"

// -------- Validate output location --------
def parentDir = inputFile.parentFile
if (!parentDir.canWrite()) {
    throw new IOException(
        "Output directory is not writable: ${parentDir.absolutePath}\n" +
        "The drive may be read-only, or you may not have write permissions.\n" +
        "Try copying the input file to a local directory first.")
}
if (outDir.exists()) {
    // Check that an existing output dir is writable (e.g., from a previous run)
    if (!outDir.canWrite()) {
        throw new IOException(
            "Existing output directory is not writable: ${outDir.absolutePath}\n" +
            "Delete it or move it, then re-run the script.")
    }
    println "WARNING: Output directory already exists — data will be overwritten."
}

// -------- Open dataset --------
AbstractSpimData spimData
try {
    if (inputPath.toLowerCase().endsWith('.ims')) {
        spimData = Imaris.openIms(inputPath)
    } else {
        spimData = new XmlIoSpimDataMinimal().load(inputPath)
    }
} catch (Exception e) {
    throw new IOException(
        "Failed to open dataset: ${inputPath}\n" +
        "The file may be corrupted or in an unsupported format.\n" +
        "Details: ${e.message}", e)
}
def imgLoader = spimData.sequenceDescription.imgLoader
def sigLoader = imgLoader.getSetupImgLoader(sigSetup)
def subLoader = imgLoader.getSetupImgLoader(subSetup)

// -------- N5 writer for output --------
def n5
try {
    n5 = new N5FSWriter(outDir.absolutePath)
} catch (Exception e) {
    throw new IOException(
        "Cannot create N5 container at: ${outDir.absolutePath}\n" +
        "Check that the directory is writable and has sufficient disk space.\n" +
        "Details: ${e.message}", e)
}

// Collect per-level dimensions for BDV XML generation
def levelDims = []       // long[][] — dimensions at each level

// -------- Block-wise processing (memory-safe) --------

// Each level may be huge (many GB).  We process block-by-block:
//   1. Read signal block (sequential — HDF5 not thread-safe)
//   2. Read background block (sequential)
//   3. Apply unmixing op (multi-threaded on in-memory block)
//   4. Write result block to N5

def executorService = Executors.newFixedThreadPool(nThreads)
def executor = new DefaultTaskExecutor(executorService)
def totalBlocks = new AtomicInteger(0)

/** Iterate over a grid of blocks covering [0, dims). */
def forEachBlock = { long[] dims, int[] blockSize, Closure action ->
    long[] pos = new long[3]
    for (pos[2] = 0; pos[2] < dims[2]; pos[2] += blockSize[2]) {
        for (pos[1] = 0; pos[1] < dims[1]; pos[1] += blockSize[1]) {
            for (pos[0] = 0; pos[0] < dims[0]; pos[0] += blockSize[0]) {
                long[] bMin = pos.clone()
                long[] bMax = [
                    Math.min(pos[0] + blockSize[0], dims[0]) - 1,
                    Math.min(pos[1] + blockSize[1], dims[1]) - 1,
                    Math.min(pos[2] + blockSize[2], dims[2]) - 1
                ] as long[]
                action(bMin, bMax)
            }
        }
    }
}

for (int t = 0; t < nTimepoints; t++) {
    for (int level = 0; level < nLevels; level++) {
        def sigRAI = sigLoader.getImage(t, level)
        def subRAI = subLoader.getImage(t, level)
        def dims = sigRAI.dimensionsAsLongArray()
        def sigZM = Views.zeroMin(sigRAI)
        def subZM = Views.zeroMin(subRAI)

        // Capture per-level dimensions on first timepoint (for BDV XML)
        if (t == 0) {
            levelDims.add(dims.clone())
        }

        def dataset = "setup0/timepoint${t}/s${level}"
        println "Processing ${dataset}  (${dims[0]}x${dims[1]}x${dims[2]})"

        // Block size: match source cells if available, else 64^3
        def blockSize = [64, 64, 64] as int[]
        try {
            def grid = sigLoader.getCellDimensions(t, level)
            if (grid != null) {
                def cd = new int[3]
                grid.cellDimensions(cd)
                blockSize = cd
            }
        } catch (ignored) {}

        // Create the N5 dataset for this level
        n5.createDataset(dataset,
            dims, blockSize,
            org.janelia.saalfeldlab.n5.DataType.UINT16,
            new org.janelia.saalfeldlab.n5.GzipCompression())

        def blockCount = new AtomicInteger(0)
        def nBlocks = (int) (
            Math.ceil(dims[0] / (double) blockSize[0]) *
            Math.ceil(dims[1] / (double) blockSize[1]) *
            Math.ceil(dims[2] / (double) blockSize[2]))

        forEachBlock(dims, blockSize) { long[] bMin, long[] bMax ->
            def bDims = [
                bMax[0] - bMin[0] + 1,
                bMax[1] - bMin[1] + 1,
                bMax[2] - bMin[2] + 1
            ] as long[]

            // Crop source views to this block
            def sigBlock = Views.zeroMin(Views.interval(sigZM, bMin, bMax))
            def subBlock = Views.zeroMin(Views.interval(subZM, bMin, bMax))

            // Step 1 & 2: Materialize both channels (sequential — HDF5 not thread-safe)
            def sigMat = ArrayImgs.unsignedShorts(bDims)
            LoopBuilder.setImages(sigBlock, sigMat)
                .forEachPixel({ a, o -> o.setReal(a.getRealDouble()) } as BiConsumer)
            def subMat = ArrayImgs.unsignedShorts(bDims)
            LoopBuilder.setImages(subBlock, subMat)
                .forEachPixel({ a, o -> o.setReal(a.getRealDouble()) } as BiConsumer)

            // Step 3: Apply unmixing (multi-threaded on in-memory block)
            def result = ArrayImgs.unsignedShorts(bDims)
            LoopBuilder.setImages(sigMat, subMat, result)
                .multiThreaded(executor)
                .forEachPixel({ s, b, o ->
                    o.setReal(op.apply(s.getRealDouble(), b.getRealDouble()))
                } as LoopBuilder.TriConsumer)

            // Step 4: Write to N5 at the correct grid position
            def gridPos = [
                (long)(bMin[0] / blockSize[0]),
                (long)(bMin[1] / blockSize[1]),
                (long)(bMin[2] / blockSize[2])
            ] as long[]
            N5Utils.saveBlock(result, n5, dataset, gridPos)

            def done = blockCount.incrementAndGet()
            if (done % 100 == 0 || done == nBlocks)
                println "  ${dataset}: ${done}/${nBlocks} blocks"
        }

        totalBlocks.addAndGet(blockCount.get())
        println "  Done: ${dataset} (${blockCount.get()} blocks)"
    }
}

// Write setup-level attributes required by BDV N5 ImageLoader
n5.createGroup("setup0")
def downsamplingFactors = new double[nLevels][]
for (int l = 0; l < nLevels; l++) {
    def s = (int) Math.pow(2, l)
    downsamplingFactors[l] = [s as double, s as double, s as double] as double[]
}
n5.setAttribute("setup0", "downsamplingFactors", downsamplingFactors)
n5.setAttribute("setup0", "dataType", org.janelia.saalfeldlab.n5.DataType.UINT16.toString())

executorService.shutdown()
n5.close()
println "\nComplete: ${totalBlocks.get()} blocks written to ${outDir.absolutePath}"

// -------- Write BDV-compatible XML descriptor --------
// This allows the result to be opened directly with:
//   Bvv.open("/path/to/dataset_unmixed.xml")
//   or Fiji > Plugins > BigDataViewer > Open XML/HDF5
def xmlFile = new File(outDir.parentFile, "${outDir.name}.xml")
println "Writing BDV XML: ${xmlFile.absolutePath}"

// Extract voxel size from the source dataset's signal setup
def srcSetup = spimData.sequenceDescription.viewSetupsOrdered.find { it.id == sigSetup }
def voxelSize = [1.0d, 1.0d, 1.0d] as double[]
def unitStr = "pixel"
if (srcSetup?.hasVoxelSize()) {
    def vs = srcSetup.voxelSize
    voxelSize = [vs.dimension(0), vs.dimension(1), vs.dimension(2)] as double[]
    unitStr = vs.unit() ?: "pixel"
}

SpimDataUtils.writeBdvN5Xml(xmlFile, outDir.name,
        levelDims as long[][], voxelSize, unitStr, nTimepoints, "unmixed")

println "BDV XML written: ${xmlFile.absolutePath}"
println "Open in Fiji:  Bvv.open('${xmlFile.absolutePath}')"


// -------- Imports --------
import bdv.img.imaris.Imaris
import bdv.spimdata.XmlIoSpimDataMinimal
import mpicbg.spim.data.generic.AbstractSpimData
import net.imglib2.img.array.ArrayImgs
import net.imglib2.loops.LoopBuilder
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.imglib2.N5Utils

import sc.fiji.snt.io.SpimDataUtils

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import net.imglib2.parallel.DefaultTaskExecutor