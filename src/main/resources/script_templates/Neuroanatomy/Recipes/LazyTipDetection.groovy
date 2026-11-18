/**
 * file:  LazyTipDetection.groovy
 * info:  Detects the tips of tubular structures (e.g., axons) in a grayscale volume of any size, without
 *        binarizing or tracing it. Optionally subtracts a background channel (e.g., autofluorescence) first.
 *        Everything is lazy: the unmixed image and its Tubeness score map are computed per cell, on demand
 *        (disk-cached), and tips are collected in a single parallel pass (see TipFinder). Cell borders need no
 *        special handling. Tips closer than the analysis radius to the faces of the volume (or region) are skipped.
 *        Tips are written to a CSV file (calibrated units, world coordinates). Run in Fiji's Script Editor (Groovy)
 *
 * Tips: Try a small region first, at a coarse level, to tune scales, radius and threshold:
 *       - scales: about the radius of the structures (physical units)
 *       - tipRadius: about 2-4x the diameter of the thickest structures. Smaller values detect the tips of shorter
 *         branches, but are less selective
 *       - minExtent/minPurity: raise them to reject speckles and clutter, lower them if real tips are missed
 *       - veto: rejects candidates that sit on large bright structures, e.g., residual autofluorescence ('bg':
 *         background channel) or haze/tissue ('smooth': a heavily blurred copy of the signal). Lower the percentile
 *         to be more aggressive. Real tips on top of the veto structure are lost too
 *       - threshold: scores scale with image intensity. Pick a value below the score of the thinnest structures
 *         (see the 'Scores' summary), or use thresholdPercentile, which adapts to the data
 */

// -------- Imports --------
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss
import net.imglib2.converter.BiConverter
import net.imglib2.converter.Converters
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.view.Views
import sc.fiji.snt.filter.Lazy
import sc.fiji.snt.filter.Tubeness
import sc.fiji.snt.tracing.auto.TipFinder
import sc.fiji.snt.util.BigDataUtils

import java.util.function.Consumer
import java.util.function.DoubleConsumer

// -------- Parameters --------
// .ims, .xml, .tif, N5 or OME-Zarr (local or remote). On Windows use forward slashes ('C:/data/x.ims') or
// escape backslashes ('C:\\data\\x.ims'): a single backslash is an escape character in Groovy strings
def inputPath = '/path/to/dataset.ims'
def sigChannel = 1                // channel with the structures to detect (0-based)
def bgChannel = -1                 // channel with background (e.g., autofluorescence). -1: none
def bgWeight = 1.0                // result = signal - weight * background (clamped to 0)
def level = 1                     // resolution level (0: full resolution). See BigDataUtils.describe()
def timepoint = 0                 // 0-based
def regionWorld = null            // [xMin, yMin, zMin, xMax, yMax, zMax] in world (calibrated) units. null: whole volume
def scales = [1.8, 3.6, 5.4, 7]      // Tubeness scales (calibrated units)
def tipRadius = 80.0               // analysis radius (calibrated units)
def threshold = Double.NaN        // Tubeness score for foreground (scales with image intensity). NaN: use percentile
def thresholdPercentile = 99.5      // used if threshold is NaN: percentile of the positive scores (0-100)
def minMass = 10                  // min voxels above threshold (in the radius, and connected to the tip)
def minExtent = 0.6               // min reach of the connected structure, as a fraction of tipRadius. Rejects speckles
def minPurity = 0.3               // min fraction of voxels in the radius that are connected to the tip. Rejects clutter
def vetoMode = 'smooth'             // 'none', 'bg' (background channel) or 'smooth' (works for single channel images)
def vetoScale = 40.0              // 'smooth' only: sigma (calibrated units) of the Gaussian. Thin axons are diluted by it
def vetoRadius = 10.0             // radius (calibrated units) in which the veto image is averaged around a candidate
def vetoThreshold = Double.NaN    // candidates with a larger mean are rejected. NaN: use vetoPercentile
def vetoPercentile = 95           // used if vetoThreshold is NaN: percentile of the veto image (0-100)
def cellSize = 64                 // cell size of the lazy score map
def debug = true                  // print score distribution and why candidates are rejected

// -------- Open channels (lazy: nothing is read yet) --------
def sig = BigDataUtils.openChannel(inputPath, sigChannel, timepoint, level).crop(regionWorld as double[])
def bg = (bgChannel >= 0) ? BigDataUtils.openChannel(inputPath, bgChannel, timepoint, level).crop(regionWorld as double[]) : null
def spacing = sig.spacing() as double[]
println "Signal: channel ${sigChannel} ('${sig.name()}'), level ${level}, voxel size ${spacing.toList()} ${sig.unit()}"
def offset = sig.origin() // world position of the first voxel (differs from the volume's if a region was set)
if (regionWorld != null) println "Region: ${sig.img().dimensionsAsLongArray().toList()} voxels, origin ${offset.toList()}"

// -------- Lazy background subtraction --------
def src = Views.zeroMin(sig.img())
def bgRaw = (bg != null) ? Views.zeroMin(bg.img()) : null
if (bg != null) {
    println "Background: channel ${bgChannel} ('${bg.name()}'), weight ${bgWeight}"
    src = Converters.convert(src, Views.zeroMin(bg.img()),
        { s, b, o -> o.setReal(Math.max(0d, Math.min(65535d, s.getRealDouble() - bgWeight * b.getRealDouble()))) } as BiConverter,
        new UnsignedShortType())
}

// -------- Lazy score map, computed per cell (including a halo) on demand --------
def ridge = Lazy.process(src, src, [cellSize, cellSize, cellSize] as int[], new FloatType(),
    new Tubeness(scales as double[], spacing))

// -------- Veto image (optional) --------
def vetoImg = null
if (vetoMode == 'bg') {
    if (bgRaw == null) throw new IllegalArgumentException("vetoMode 'bg' requires bgChannel >= 0")
    vetoImg = bgRaw
} else if (vetoMode == 'smooth') {
    def sg = (0..2).collect { vetoScale / spacing[it] } as double[]
    def ext = Views.extendBorder(src)
    vetoImg = Lazy.process(src, [cellSize, cellSize, cellSize] as int[], new FloatType(),
        { out -> FastGauss.convolve(sg, ext, out) } as Consumer)
} else if (vetoMode != 'none') {
    throw new IllegalArgumentException("Unknown vetoMode: ${vetoMode}")
}

// -------- Tips --------
def finder = new TipFinder(ridge, spacing).setRadius(tipRadius)
    .setMinMass(minMass).setMinExtent(minExtent).setMinPurity(minPurity)
if (vetoImg != null) finder.setVeto(vetoImg, vetoRadius)
def lastTenth = [-1]
finder.setProgressListener({ double f ->
    int tenth = (int) (f * 10)
    synchronized (lastTenth) {
        if (tenth > lastTenth[0]) {
            lastTenth[0] = tenth
            println "${tenth * 10}%"
        }
    }
} as DoubleConsumer)
if (debug) {
    println "Score map: ${ridge.dimensionsAsLongArray().toList()} voxels (scales: ${scales})"
    finder.setDebugLogger({ String s -> println s } as Consumer)
    // Computes (and caches) the score map: this is the slow part for large regions
    println "Scores: ${finder.describeScores(8)}"
}
if (vetoImg != null) {
    if (debug) println "Veto image (${vetoMode}): ${finder.describeVeto(8)}"
    if (Double.isNaN(vetoThreshold)) vetoThreshold = finder.vetoPercentile(vetoPercentile, 8)
    finder.setVetoThreshold(vetoThreshold)
    println "Veto threshold: ${vetoThreshold}"
}
if (Double.isNaN(threshold)) {
    threshold = finder.percentileThreshold(thresholdPercentile, 8)
    println "Threshold: ${threshold} (percentile ${thresholdPercentile} of the positive scores)"
}
finder.setThreshold(threshold)
def t0 = System.currentTimeMillis()
def tips = finder.find()
println "${tips.size()} tips in ${(System.currentTimeMillis() - t0) / 1000} s"
if (tips.isEmpty()) return;

// -------- Output (world coordinates) --------
def isRemote = BigDataUtils.isRemoteUrl(inputPath)
def inputFile = new File(inputPath)
def outDir = (isRemote || !inputFile.absoluteFile.parentFile?.canWrite()) ? new File(System.getProperty('user.home')) : inputFile.absoluteFile.parentFile
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
