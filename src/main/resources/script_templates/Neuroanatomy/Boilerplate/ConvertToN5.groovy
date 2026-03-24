// Auto-generated Groovy script for converting an image to a
// multi-resolution N5 dataset suitable for BVV/BDV lazy loading.
//
// Input:  #{INPUT_PATH}
// Output: N5 container + BDV XML descriptor (next to the input file)
//
// Run in Fiji's Script Editor (Language: Groovy)
// Requires: n5-imglib2, SCIFIO (or Bio-Formats for proprietary formats)

// ─────────── SciJava parameters  ───────────
#@Context context
#@IOService ioService

// ─────────── Script parameters  ───────────
def inputPath  = '#{INPUT_PATH}'

// ─────────── Load source image ───────────
println "Opening: ${inputPath}"
def opened = ioService.open(inputPath)
def imgPlus
if (opened instanceof Dataset) {
    imgPlus = ((Dataset) opened).getImgPlus()
} else if (opened instanceof ImgPlus) {
    imgPlus = opened
} else {
    throw new IllegalArgumentException("Unsupported type: " + opened.getClass().getName())
}

println "Image: ${imgPlus.numDimensions()}D, type=${imgPlus.firstElement().getClass().getSimpleName()}"
for (int d = 0; d < imgPlus.numDimensions(); d++) {
    def ax = imgPlus.axis(d)
    println "  dim[${d}]: ${ax.type()} size=${imgPlus.dimension(d)} scale=${imgPlus.averageScale(d)} unit=${ax.unit()}"
}

// ─────────── Resolve axes ───────────
def (xIdx, yIdx, zIdx) = ImgUtils.findSpatialAxisIndices(imgPlus)
def cIdx = imgPlus.dimensionIndex(Axes.CHANNEL)
if (xIdx < 0 || yIdx < 0) throw new IllegalArgumentException("Image must have X and Y axes")

def sizeX = imgPlus.dimension(xIdx)
def sizeY = imgPlus.dimension(yIdx)
def sizeZ = zIdx >= 0 ? imgPlus.dimension(zIdx) : 1
def nChannels = cIdx >= 0 ? (int) imgPlus.dimension(cIdx) : 1

def cal = ImgUtils.getCalibration(imgPlus)
def voxelSize = [cal.pixelWidth, cal.pixelHeight, cal.pixelDepth] as double[]
def unit = cal.getUnit() ?: "pixel"

println "Spatial: ${sizeX}x${sizeY}x${sizeZ}, ${nChannels} channel(s), voxel=${voxelSize} ${unit}"

// ─────────── Output path  ───────────
def baseName = new File(inputPath).name.replaceFirst(/\.[^.]+$/, '')
def outDir   = new File(new File(inputPath).parentFile, "${baseName}.n5")
def xmlFile  = new File(new File(inputPath).parentFile, "${baseName}.xml")
println "Output N5:  ${outDir.absolutePath}"
println "Output XML: ${xmlFile.absolutePath}"

// ─────────── Compute pyramid levels ───────────
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

// ─────────── Write N5 (block by block) ───────────
def nThreads = Runtime.getRuntime().availableProcessors()
def executorService = Executors.newFixedThreadPool(nThreads)
def executor = new DefaultTaskExecutor(executorService)
def n5 = new org.janelia.saalfeldlab.n5.N5FSWriter(outDir.absolutePath)

for (int ch = 0; ch < nChannels; ch++) {
    // Extract this channel as a 3D RAI (X, Y, Z)
    def channelRai
    if (cIdx >= 0 && nChannels > 1) {
        channelRai = Views.hyperSlice(imgPlus, cIdx, (long) ch)
    } else {
        channelRai = imgPlus
    }
    // Ensure we have a 3D volume: drop non-spatial singleton dims if needed
    // The RAI after hyperSlice should have nDims-1 dimensions

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
        def nBlocks = (int)(
            Math.ceil(ld[0] / (double) blockSize[0]) *
            Math.ceil(ld[1] / (double) blockSize[1]) *
            Math.ceil(ld[2] / (double) blockSize[2]))
        def blockCount = 0

        long[] pos = new long[3]
        for (pos[2] = 0; pos[2] < ld[2]; pos[2] += blockSize[2]) {
            for (pos[1] = 0; pos[1] < ld[1]; pos[1] += blockSize[1]) {
                for (pos[0] = 0; pos[0] < ld[0]; pos[0] += blockSize[0]) {
                    long[] bMin = pos.clone()
                    long[] bMax = [
                        Math.min(pos[0] + blockSize[0], ld[0]) - 1,
                        Math.min(pos[1] + blockSize[1], ld[1]) - 1,
                        Math.min(pos[2] + blockSize[2], ld[2]) - 1
                    ]
                    long[] bDims = [bMax[0]-bMin[0]+1, bMax[1]-bMin[1]+1, bMax[2]-bMin[2]+1]

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
            }
        }
        prevLevel = sourceRai
        println "  Done: ch${ch} s${level}"
    }

    // Write setup-level attributes required by BDV N5 ImageLoader
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

// ─────────── Write BDV XML descriptor ───────────
println "Writing BDV XML: ${xmlFile.absolutePath}"
// For multi-channel, we write one setup per channel. For simplicity,
// we'll use the first channel's level dims (same for all)
SpimDataUtils.writeBdvN5Xml(xmlFile, outDir.name, levelDims as long[][],
	voxelSize, unit, 1, baseName, nChannels)

println "\nDone! Open in BVV with:"
println "  Bvv.open('${xmlFile.absolutePath}')"

// ─────────── Imports ───────────
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import net.imglib2.parallel.DefaultTaskExecutor
import net.imagej.Dataset
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imglib2.view.Views
import net.imglib2.img.array.ArrayImgs
import net.imglib2.loops.LoopBuilder
import sc.fiji.snt.io.SpimDataUtils
import sc.fiji.snt.util.ImgUtils
