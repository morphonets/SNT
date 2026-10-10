// >>> TEMPLATE-NOTE
// This is a template: SNT fills in the #{PLACEHOLDERS} below. It will not run as is.
// Use "Unmix Full Volume..." in the Channel Unmixing card (Bvv/Bdv) to obtain a runnable version
// <<< TEMPLATE-NOTE
/**
 * file:  ChannelUnmixing.groovy
 * info:  Auto-generated script that applies SNT's (Bvv/Bdv) channel un-mixing to a whole volume and saves
 *        the result as a multi-resolution OME-Zarr (Zarr v3, OME-NGFF 0.5) that SNT can stream in BVV/BDV.
 *        Level 0 is unmixed on the fly, one slab at a time (so the volume can be larger than the available
 *        memory), and coarser levels are computed from it by SNT's OME-Zarr writer.
 *        Output is written next to the input (or in the home folder if the input is remote) as
 *        '<name>_unmixed_<op>.ome.zarr'. Existing data is not overwritten: a numeric suffix is added instead.
 *        Run in Fiji's Script Editor (Language: Groovy)
 *
 * Formula: result = signal - w * background, computed on display-range
 *          normalized values, i.e., identical to what is shown in the viewer:
 *          clamp((s - sigMin) - w * (b - subMin) * rangeScale + sigMin, 0, 65535)
 * Source, channels, weight and display ranges are defined in the Parameters section below
 */

// -------- Imports --------
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imagej.axis.DefaultLinearAxis
import net.imglib2.converter.BiConverter
import net.imglib2.converter.Converters
import net.imglib2.img.ImgView
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.view.Views
import sc.fiji.snt.SNTPrefs
import sc.fiji.snt.io.SpimDataUtils
import sc.fiji.snt.util.ImgUtils

import java.util.function.Consumer

// -------- Unmixing operation  --------
// Swap this class to change the pixel-wise operation for any
// pixel-wise operation (e.g., ratio, linear combo, etc.)
// Contract: apply(double signal, double background) -> double
class UnmixingOp {
    final double weight, sigMin, subMin, rangeScale
    UnmixingOp(double weight, double sigMin, double subMin, double rangeScale) {
        this.weight = weight
        this.sigMin = sigMin
        this.subMin = subMin
        this.rangeScale = rangeScale
    }

    /** Weighted subtraction (as in the viewer), clamped to [0, 65535]. */
    double apply(double signal, double background) {
        double v = (signal - sigMin) - weight * ((background - subMin) * rangeScale) + sigMin
        return Math.max(0, Math.min(65535, v))
    }

    String describe(String sigName, String subName) {
        "${sigName}_minus_${subName}_w${String.format('%.2f', weight)}".replaceAll(/[^\w.\-]/, '_')
    }
}

// -------- Parameters  --------
def inputPath   = '#{INPUT_PATH}'
def sigChannel  = #{SIG_CHANNEL}
def subChannel  = #{SUB_CHANNEL}
def nChannels   = #{N_CHANNELS}
def weight      = #{WEIGHT}
def sigMin      = #{SIG_MIN}   // signal display range minimum (B&C)
def subMin      = #{SUB_MIN}   // background display range minimum (B&C)
def rangeScale  = #{RANGE_SCALE}   // signal range width / background range width
def sigName     = '#{SIG_NAME}'
def subName     = '#{SUB_NAME}'
def nTimepoints = #{N_TIMEPOINTS}
def timepoint   = 0            // OME-Zarr export does not support time series: only this timepoint is exported

def op = new UnmixingOp(weight, sigMin, subMin, rangeScale)

// -------- Output location --------
// Written next to the input; remote inputs (URLs) have no local folder, so the home folder is used instead
def isRemote = inputPath ==~ /(?i)^[a-z][a-z0-9+.\-]+:\/\/.*/
def inputFile = isRemote ? new File(inputPath.replaceFirst(/\/+$/, '').split('/')[-1]) : new File(inputPath)
// A container may have been opened through one of its metadata files (e.g., 'zarr.json'): use its folder instead
if (!isRemote && inputFile.isFile() && inputFile.name in ['zarr.json', '.zgroup', '.zarray', '.zattrs', 'attributes.json']) {
    inputFile = inputFile.absoluteFile.parentFile
    inputPath = inputFile.absolutePath
}
if (!isRemote && !inputFile.exists())
    throw new IOException("Input not found: ${inputPath}\n" +
        "Check that the path is correct and that the file has not been moved.")
def parentDir = isRemote ? new File(System.getProperty('user.home')) : inputFile.absoluteFile.parentFile
if (!parentDir.canWrite()) {
    throw new IOException("Output directory is not writable: ${parentDir}\n" +
        "The drive may be read-only, or you may not have write permissions.\n" +
        "Try copying the input to a local directory first.")
}
if (nTimepoints > 1)
    println "WARNING: ${nTimepoints} timepoints in source: only timepoint ${timepoint} will be exported"

// -------- Output path (never overwrites) --------
// 'x.xml', 'x.ims', 'x.ome.zarr' and 'x.n5' all map to 'x_unmixed_...'
def baseName = inputFile.name.replaceFirst(/\.[^.]+$/, '').replaceFirst(/(?i)\.ome$/, '')
def outName = "${baseName}_unmixed_${op.describe(sigName, subName)}"
def outDir = new File(parentDir, "${outName}.ome.zarr")
for (int i = 1; outDir.exists(); i++)
    outDir = new File(parentDir, "${outName}_${i}.ome.zarr")
println "Output: ${outDir.absolutePath}"

// -------- Open channels (full resolution, lazy: nothing is read until the writer needs it) --------
def sig, sub
try {
    sig = SpimDataUtils.openChannel(inputPath, sigChannel, timepoint)
    sub = SpimDataUtils.openChannel(inputPath, subChannel, timepoint)
} catch (Exception e) {
    throw new IOException("Failed to open dataset: ${inputPath}\n" +
        "The file may be corrupted, moved or in an unsupported format.\nDetails: ${e.message}", e)
}
if (sig.nChannels() != nChannels)
    throw new IOException("Channel mismatch: the viewer showed ${nChannels} channels but ${inputPath} has " +
        "${sig.nChannels()}. Channel indices may not refer to the same data: aborting.")
println "Signal:     channel ${sigChannel} ('${sig.name()}')"
println "Background: channel ${subChannel} ('${sub.name()}')"

// -------- Lazy unmixed view (level 0) --------
def unmixed = Converters.convert(Views.zeroMin(sig.img()), Views.zeroMin(sub.img()),
    { s, b, o -> o.setReal(op.apply(s.getRealDouble(), b.getRealDouble())) } as BiConverter,
    new UnsignedShortType())

// -------- Calibration from the signal channel --------
def cal = sig.spacing()
def unit = sig.unit()
def img = new ImgPlus(ImgView.wrap(unmixed), outName,
    new DefaultLinearAxis(Axes.X, unit, cal[0]),
    new DefaultLinearAxis(Axes.Y, unit, cal[1]),
    new DefaultLinearAxis(Axes.Z, unit, cal[2]))
println "Image: ${ImgUtils.axisReport(img)}"
if (!unit) println "WARNING: spatial unit unknown. The OME-Zarr will be written without units (spacing is kept)"

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
