// >>> TEMPLATE-NOTE
// This is a template: SNT fills in the #{PLACEHOLDERS} below. It will not run as is.
// Use "Export Recipe..." in the More menu of the Tip Detection Wizard to obtain a runnable version
// <<< TEMPLATE-NOTE
/**
 * file:  LazyTipDetection.groovy
 * info:  Auto-generated script that detects the tips of tubular structures (e.g., axons) in volumes of any size,
 *        using the parameters learned in SNT's Tip Detection Wizard. Optionally subtracts a background channel
 *        (e.g., autofluorescence) first. Everything is lazy (see TipDetector): the unmixed image and its Tubeness
 *        score map are computed per cell, on demand, and tips are collected in a single parallel pass. Optionally,
 *        blocks without signal are skipped, as assessed from a coarser resolution level.
 *        Tips closer than the analysis radius to the faces of the volume (or region) are skipped.
 *        Each dataset in 'inputPaths' is processed in turn, with the same parameters, and its tips are written to
 *        a CSV file next to it (or in the home folder if the dataset is remote). Coordinates are in world
 *        (calibrated) units. Run in Fiji's Script Editor (Language: Groovy)
 *
 * Batching: add the paths of other datasets to 'inputPaths'. The parameters are only valid for datasets that
 *        were acquired and processed like the one used in the wizard (same voxel size, same intensity range).
 *        The scores of the wizard are listed below for reference: a warning is printed if the tips found in a
 *        dataset score very differently
 */

// -------- Imports --------
import net.imglib2.view.Views
import sc.fiji.snt.tracing.auto.TipDetectionConfig
import sc.fiji.snt.tracing.auto.TipDetector
import sc.fiji.snt.util.BigDataUtils

import java.util.function.Consumer
import java.util.function.DoubleConsumer

// -------- Parameters --------
// .ims, .xml, .tif, N5 or OME-Zarr (local or remote). On Windows use forward slashes ('C:/data/x.ims') or
// escape backslashes ('C:\\data\\x.ims'): a single backslash is an escape character in Groovy strings
def inputPaths = [
    '#{INPUT_PATH}',
]
def sigChannel = #{SIG_CHANNEL}   // channel with the structures to detect (0-based)
def bgChannel = #{BG_CHANNEL}     // channel with background (e.g., autofluorescence). -1: none
def bgWeight = #{BG_WEIGHT}       // result = signal - weight * background (clamped to 0)
def level = #{LEVEL}              // resolution level (0: full resolution). See BigDataUtils.describe()
def timepoint = 0                 // 0-based
def regionWorld = null            // [xMin, yMin, zMin, xMax, yMax, zMax] in world (calibrated) units. null: whole volume
def skipEmpty = #{SKIP_EMPTY}     // skip blocks without signal, as assessed at a coarser level
def deviationFactor = #{DEVIATION_FACTOR}     // skip blocks darker than background + k x absolute deviation (coarse level). Lower k to skip fewer blocks

// Learned by the wizard (calibrated units)
def neuriteRadii = [#{NEURITE_RADII}]          // neurite radii: scales (sigma) of the Tubeness filter
def analysisRadius = #{ANALYSIS_RADIUS}     // analysis radius: of the sphere analyzed around each candidate. Also the min. distance between tips
def minScore = #{MIN_SCORE}      // min. Tubeness score for foreground (scales with image intensity)
def minLength = #{MIN_LENGTH}     // min. length: reach of the connected structure, as a fraction of analysisRadius
def minConnectedFraction = #{MIN_CONNECTED_FRACTION}     // min. connected fraction: of the structure voxels in the radius that are connected to the tip
def maxBackground = #{MAX_BACKGROUND} // max. background: candidates over a larger mean background are rejected. NaN: off
def backgroundRadius = #{BACKGROUND_RADIUS}   // radius in which the background is averaged around a candidate

// Reference from the wizard: voxel size of level 0 and scores along the training paths (and elsewhere)
def refSpacing = [#{REF_SPACING}]
def refScores = [nodeP10: #{REF_NODE_P10}, nodeMedian: #{REF_NODE_MEDIAN},
                 backgroundP50: #{REF_BG_P50}, backgroundP99: #{REF_BG_P99}]

// -------- Detection --------
def config = TipDetectionConfig.of(neuriteRadii as double[], analysisRadius, minScore, minLength,
        minConnectedFraction, maxBackground, backgroundRadius)
config.setBackground((bgChannel >= 0) ? bgChannel + 1 : 0, bgWeight) // 1-based. 0: none
def maxCoarseVoxels = 32_000_000L

inputPaths.each { inputPath ->
    println "=== ${inputPath} ==="
    def info = BigDataUtils.describe(inputPath)
    def spacing0 = info.levels()[0].spacing()
    if ((0..2).any { Math.abs(spacing0[it] - refSpacing[it]) > 0.05 * refSpacing[it] })
        println "WARNING: voxel size ${spacing0.toList()} differs from the one of the wizard (${refSpacing}): " +
                "scales and radius may not be appropriate"

    // Open channels (lazy: nothing is read yet)
    def sig = BigDataUtils.openChannel(inputPath, sigChannel, timepoint, level).crop(regionWorld as double[])
    def bg = (bgChannel >= 0) ?
            BigDataUtils.openChannel(inputPath, bgChannel, timepoint, level).crop(regionWorld as double[]) : null
    def spacing = sig.spacing() as double[]
    def offset = sig.origin() // world position of the first voxel (differs from the volume's if a region was set)
    println "Signal: channel ${sigChannel} ('${sig.name()}'), level ${level}, voxel size ${spacing.toList()} " +
            "${sig.unit()}, ${sig.img().dimensionsAsLongArray().toList()} voxels"
    if (bg != null) println "Background: channel ${bgChannel} ('${bg.name()}'), weight ${bgWeight}"

    // Coarse copy of the volume, to skip empty blocks
    def coarse = null
    if (skipEmpty) {
        def cl = TipDetector.coarseLevel(info.levels(), level, maxCoarseVoxels)
        if (cl == null) {
            println "No level coarser than ${level}: all blocks will be processed"
        } else {
            def cs = BigDataUtils.openChannel(inputPath, sigChannel, timepoint, cl.level())
            def cb = (bgChannel >= 0) ? BigDataUtils.openChannel(inputPath, bgChannel, timepoint, cl.level()) : null
            coarse = new TipDetector.Coarse(Views.zeroMin(cs.img()), (cb != null) ? Views.zeroMin(cb.img()) : null,
                    cs.spacing() as double[], cs.origin() as double[], offset as double[], deviationFactor)
            println "Empty blocks assessed at level ${cl.level()} (${cl.dims().toList()} voxels)"
        }
    }

    def lastTenth = [-1]
    def detector = new TipDetector(config, Views.zeroMin(sig.img()), (bg != null) ? Views.zeroMin(bg.img()) : null,
            spacing)
            .setCoarse(coarse)
            .setLogger({ String s -> println s } as Consumer)
            .setProgressListener({ double f ->
                int tenth = (int) (f * 10)
                synchronized (lastTenth) {
                    if (tenth > lastTenth[0]) {
                        lastTenth[0] = tenth
                        println "${tenth * 10}%"
                    }
                }
            } as DoubleConsumer)
    def t0 = System.currentTimeMillis()
    def tips = detector.detect()
    println "${tips.size()} tips in ${(System.currentTimeMillis() - t0) / 1000} s"
    if (tips.isEmpty()) return

    // Do the scores of the tips resemble the ones of the wizard?
    def sorted = tips.collect { it.score() }.sort()
    def median = sorted[(int) (sorted.size() / 2)]
    println "Tip scores: median ${median}, min ${sorted[0]}, max ${sorted[-1]}. Wizard: nodes (p10 ${refScores.nodeP10}, " +
            "median ${refScores.nodeMedian}), elsewhere (p50 ${refScores.backgroundP50}, p99 ${refScores.backgroundP99})"
    if (!Double.isNaN(refScores.nodeMedian) && (median < 0.2 * refScores.nodeMedian || median > 5 * refScores.nodeMedian))
        println "WARNING: the scores differ markedly from the ones of the wizard. Is the intensity range the same?"

    // -------- Output (world coordinates) --------
    def inputFile = new File(inputPath)
    def outDir = (BigDataUtils.isRemoteUrl(inputPath) || !inputFile.absoluteFile.parentFile?.canWrite()) ?
            new File(System.getProperty('user.home')) : inputFile.absoluteFile.parentFile
    def baseName = inputFile.name.replaceFirst(/\.[^.]+$/, '').replaceFirst(/(?i)\.ome$/, '')
    def outFile = new File(outDir, "${baseName}_tips_L${level}.csv")
    for (int i = 1; outFile.exists(); i++)
        outFile = new File(outDir, "${baseName}_tips_L${level}_${i}.csv")
    outFile.withWriter { w ->
        w.writeLine('X,Y,Z,Score,DirX,DirY,DirZ')
        tips.each { t ->
            def p = t.position()
            def d = t.direction()
            w.writeLine("${p[0] + offset[0]},${p[1] + offset[1]},${p[2] + offset[2]},${t.score()},${d[0]},${d[1]},${d[2]}")
        }
    }
    println "Written to ${outFile.absolutePath}"
}
