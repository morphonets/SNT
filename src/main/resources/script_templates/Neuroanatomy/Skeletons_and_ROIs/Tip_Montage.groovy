#@String(value = "<HTML>This script assembles a montage of small image neighborhoods centered on each<br>tip of a reconstruction. Works on both regular (e.g., TIFF) and pyramidal images<br>(e.g., OME-Zarr, N5).", visibility = "MESSAGE") msg
#@File(required = false, label = "Reconstruction file (leave empty for demo)") treeFile
#@File(required = false, label = "Image file (leave empty for demo)") imgFile
# @int(label = "Window XY size (in pixels)", min = 4) windowXY
# @int(label = "Window Z thickness (in pixels, 1=2D)", min = 1) windowZ
#@SNTService sntService
#@UIService uiService


/**
 *  Builds a montage of all the end-points (tips) of a reconstruction (Tree).
 *
 *  Uses sc.fiji.snt.analysis.NodeCollector, which:
 *   - operates on an ImgPlus, working directly with lazy, pyramid sources (OME-Zarr, N5) w/o loading the whole volume
 *   - pads out-of-bounds crops with zeros, so the resulting montage forms a uniform grid
 *   - defaults the channel/frame to the tip's owning Path (overridable)
 *
 *  TF 20260609
 */

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.NodeCollector
import sc.fiji.snt.util.ImgUtils


// Abort if running an old version
sntService.requireVersion("5.0.11")

def tree, imgPlus

// Load data
if (treeFile && imgFile) {
    // Use SCIFIO to load ImgPlus (both regular and pyramid file formats supported)
    imgPlus = ImgUtils.open(imgFile.getAbsolutePath())
    tree = Tree.fromFile(treeFile.getAbsolutePath())
} else {
    // No data specified: Run on demo dataset
    imgPlus = sntService.demoImgPlus("OP_1")
    tree = sntService.demoTree("OP_1")
    windowXY = 40
    windowZ = 1
}
if (!tree || !imgPlus) {
    uiService.showDialog("Invalid file paths or demo data not available.")
    return
}

// Tree must know its source image so that tip coordinates can be unscaled against the right calibration
tree.assignImage(imgPlus)

// If the window depth is a slab of multiple planes, we will project it (maximum intensity projection)
def doMIP = (windowZ > 1) ? NodeCollector.Projection.MIP_Z : NodeCollector.Projection.NONE

// Now we can assemble the NodeCollector...
def collector = NodeCollector.fromTips(imgPlus, tree)
collector.setWindow(windowXY, windowZ)
collector.setProjection(doMIP)

//... and retrieve the montage:
def montage = collector.getMontage(
        -1, // no. of columns (-1=auto)
        -1, // no. of columns (-1=auto)
        1, // scaling factor for each tile in the montage
        2, // width of the border around each tile
        false) // label each tile?
montage.show()

// Other things you can do with the same builder:
//
//   // Branch-point montage
//   NodeCollector.fromBranchPoints(imgPlus, tree).setWindow(20, 1).getMontage().show()
//
//   // Every 10th node along all paths
//   NodeCollector.fromNodes(imgPlus, tree, 10).setWindow(20, 1).getMontage().show()
//
//   // Roots of a population of Trees
//   NodeCollector.fromRoots(imgPlus, listOfTrees).setWindow(20, 1).getMontage().show()
//
//   // Arbitrary points (bookmarks, seeds, ...)
//   NodeCollector collector = new NodeCollector(imgPlus, myPointsCollection)
//   collector.setWindow(20, 5).setProjection(NodeCollector.Projection.MIP_Z)
//   collector.getStack().show()  // just the stack, no montage
//
//   // Lazy crops as RAIs (without actual materialization of the volume), e.g. for further imglib2 work:
//   def crops = NodeCollector.fromTips(imgPlus, tree).setWindow(20, 1).getCrops()
