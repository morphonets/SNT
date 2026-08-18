#@ String(label="Channels to materialize (e.g. 'all', '1,2', '1-4'; must start at 1 w/o gaps)", required=false) channelsStr
#@ Double(label="Padding XY (calibrated units)", value=5, min=0) padXY
#@ Double(label="Padding Z (calibrated units)", value=5, min=0) padZ
#@ boolean(label="Restrict region to selected paths (unchecked = all traced paths)", value=true) fromSelection
#@ SNTService sntService
#@ UIService uiService

/**
 * file:    Materialize_Multichannel_Region.groovy
 * author:  Tiago Ferreira
 * info:    Stream-mode's "Materialize Region" only crops the single channel currently active in BDV/BVV because SNT's
 *          streamed pixel data (ctSlice3d) is, by construction, single C/T slice. This script reaches directly into
 *          the active BDV/BVV viewer's own source list cropping the SAME world region from every requested channel,
 *          and merging the results into one real, in-RAM, multichannel image, which is then installed as a normal
 *          materialized crop via SNT's API.
 *          Once installed, snt.getImagePlus() returns this real hyperstack, so snt.getDataset() returns a genuinely
 *          multichannel Dataset allowing SNT commands (e.g., Node/Path Profilers) to access all channels, not just 
 *          whichever one was active in the viewer. 
 *
 * Usage:   Select the paths that define the region of interest in Path Manager (or leave the selection empty to use
 *          _all_ paths), then run this script. Leave "Channels" blank, or enter 'all', to materialize every
 *          source currently open in the active viewer. To materialize only the first few channels (e.g. to save
 *          memory), enter a contiguous run startingt at 1,e .g., '1,2' or '1-2', '1,2-4', etc.
 *
 * Caveats: This assumes the sources in the viewer belong to the same dataset (same bit-depth, and dimensions). 
 *          'Channels' must be a contiguous run starting at 1 (e.g. '1,2,3'), not an arbitrary subset (e.g. '1,3',
 *          or '2,3'). Node Profiler/Plot Profile index a path's channel by its channel number as recorded on the
 *          path, which is its original 1-based source index. A merged crop's own channels are always numbered 1..N 
 *          in request order, so any subset other than a plain '1..N' prefix would put a different source's pixel
 *          data at the wrong hyperstack position.
 *
 * @see     MaterializeRegionDialog, for the single-channel, dialog-driven equivalent
 * @see     Crop_Around_Marker.groovy, for a simpler single-channel scripted crop
 */

// Abort if running an old version, or if snt is not running
sntService.requireVersion("5.0.11")

if (!sntService.isActive() || sntService.getUI() == null) {
    uiService.showDialog("SNT does not appear to be running.", "Error")
    return
}
snt = sntService.getInstance()
viewer = snt.getUI().getActiveBigViewer()

// Check if we can progress
if (!snt.isStreamMode() || !snt.accessToValidImageData() || viewer == null) {
    uiService.showDialog("This script requires SNT's Stream mode (BVV/BDV) to be active.", "No Big Viewer Active")
    return
}

// Every channel of a multichannel dataset is added as its own consecutive BDV/BVV source (see Bdv.java/Bvv.java's
// "Multichannel: show each channel as a separate source" loaders) - grabbing them is just indexing into the
// viewer's own source list, the same list snt itself reads from in AbstractTracer#syncChannelFromActiveSource()
vp = viewer.getViewerPanel()
sources = vp.state().getSources()
if (sources == null || sources.isEmpty()) {
    uiService.showDialog("The active viewer has no sources loaded.", "Error")
    return
}
timepoint = vp.state().getCurrentTimepoint()

if (channelsStr == null || channelsStr.trim().isEmpty() || channelsStr.toLowerCase().contains("all")) {
    channelIndices = (1..sources.size()).toList()
} else {
    try {
        channelIndices = channelsStr.split(",").collectMany { token ->
            token = token.trim()
            if (token.contains("-")) {
                def parts = token.split("-").collect { it.trim() as int }
                if (parts.size() != 2 || parts[0] > parts[1]) {
                    throw new NumberFormatException("Invalid range format: ${token}")
                }
                return (parts[0]..parts[1]).toList()
            } else {
                return [token as int]
            }
        }.unique().sort() // Dedupe + sort ascending
    } catch (Exception e) {
        ui.showDialog("'Channels' must be numbers or valid ranges (e.g., '1,3', '1-4', or '1,3-5,8').", "Invalid Input")
        return
    }
}

// Enforce a plain '1..N' prefix - see the Caveat above for why any other subset would silently mismatch
// each remaining channel's number against the pixel data actually merged into that slot
for (int i = 0; i < channelIndices.size(); i++) {
    if (channelIndices[i] != i + 1) {
        uiService.showDialog("'Channels' must be a contiguous run starting at 1 (e.g. '1,2,3'), not an arbitrary " +
                "subset (e.g. '1,3' or '2,3') - see this script's own header comment for why. Leave blank to " +
                "include every channel.", "Unsupported Channel Selection")
        return
    }
}
nChannels = channelIndices.size()
// The prefix check above only validates SHAPE (1..N, no gaps) - it says nothing about whether N actually
// exists in this viewer. Without this, e.g. "1-10" against a 3-source viewer would sail through as a
// perfectly clean prefix and only fail later, as an uncaught IndexOutOfBoundsException from sources.get(),
// instead of this dialog
if (nChannels > sources.size()) {
    uiService.showDialog("Requested ${nChannels} channel(s), but the active viewer only has ${sources.size()} " +
            "source(s) (1-${sources.size()}).", "Invalid Channel")
    return
}

// Region of interest: the (padded) bounding box of the relevant paths, in world/
// calibrated coordinates (same convention MaterializeRegionDialog uses)
pathManager = snt.getUI().getPathManager()
paths = fromSelection ? pathManager.getSelectedPaths(true) : snt.getPathAndFillManager().getPaths()
if (paths == null || paths.isEmpty()) {
    uiService.showDialog("No paths are available to define the region of interest.", "No Paths Found")
    return
}
box = new Tree(paths).getBoundingBox()
box.expand(padXY, padXY, padZ)

// Resolve the region to voxel bounds once (clamped to the active channel's own extent),
// then reuse identically for every requested channel
cal = snt.getCalibration()
voxelBounds = snt.resolveVoxelBounds(box, cal)
voxelMin = voxelBounds.min()
voxelMax = voxelBounds.max()
if (voxelBounds.clamped()) {
    println("Materialize_Multichannel_Region: requested region exceeded the loaded " +
    	"volume on at least one side; using the clamped extent instead.")
}
cropW = voxelMax[0] - voxelMin[0] + 1
cropH = voxelMax[1] - voxelMin[1] + 1
cropD = voxelMax[2] - voxelMin[2] + 1

// Let's check there is enough RAM to materialize the whole crop
memNeeded = snt.estimateMaterializationBytes(cropW, cropH, cropD) * nChannels
memAvailable = snt.getMaterializationMemoryBudget()
if (memNeeded > memAvailable) {
    uiService.showDialog(String.format("Requested %d-channel crop (%.2f GB) exceeds the materialization budget (%.2f GB " +
            "available); select fewer channels/paths, reduce padding, or raise the budget in SNT's preferences.",
            nChannels, memNeeded / 1e9, memAvailable / 1e9), "Crop Too Large")
    return
}

// Crop each requested channel from the viewer's own source list, and convert
// each to a real, in-RAM, single-channel ImagePlus
channelImps = []
for (ch in channelIndices) {
    sac = sources.get(ch - 1)
    spimSource = sac.getSpimSource()
    rai = spimSource.getSource(timepoint, 0) // level 0: full resolution
    cropped = Views.interval(rai, voxelMin, voxelMax)
    imp = ImgUtils.raiToImp(cropped, "C${ch}").duplicate() // duplicate(): raiToImp() alone is a lazy, read-only wrap
    imp.setCalibration(cal)
    channelImps << imp
}

// Merge the per-channel crops into one CompositeImage
merged = (nChannels > 1) ? ImpUtils.mergeChannels(channelImps) : channelImps[0]
merged.setTitle("Materialized Region (${nChannels} channel${nChannels > 1 ? "s" : ""})")
merged.setCalibration(cal)
merged.resetDisplayRange()

// Install result as this session's materialized canvas
crop = new SNT.MaterializedCrop(merged, voxelMin, snt.getChannel(), snt.getFrame())
snt.installMaterializedCrop(crop)

println("Materialized a ${cropW}x${cropH}x${cropD}, ${nChannels}-channel region (channels ${channelIndices.join(", ")}) " +
        "from ${paths.size()} path(s). Node Profiler / Plot Profile / Fit / A* run from Path Manager can now use any " +
        "of these channels.")


// Imports below
import ij.ImagePlus
import net.imglib2.view.Views
import sc.fiji.snt.SNT
import sc.fiji.snt.Tree
import sc.fiji.snt.util.ImgUtils
import sc.fiji.snt.util.ImpUtils
