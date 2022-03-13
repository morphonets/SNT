#@ File (label="Reconstruction File (Leave empty for demo):", style="file", required=false) recFile
#@ File (label="Original image (Optional):", style="file", required=false) impFile
#@ boolean (label="Convert paths") convertPaths
#@ boolean (label="Convert branch points") convertBranchPoints
#@ boolean (label="Convert tips") convertTips
#@ SNTService snt


/** 
 *  Exemplifies how to convert a reconstruction into a series of ROIs.
 *  For further details:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/Tree.html and
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/RoiConverter.html
 *  TF 20200705
 */

import ij.IJ
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.RoiConverter
import ij.gui.Overlay
import ij.plugin.frame.RoiManager

if (recFile) {
    tree = new Tree(recFile.getAbsolutePath())
    if (impFile) {
        imp = IJ.openImage(impFile.getAbsolutePath())
    } else {
        // Use skeleton image so that
        // drawn ROIs are visible
        imp = tree.getSkeleton2D()
    }
    tree.assignImage(imp)
} else {
    // We'll use a demo reconstruction and image provided by SNTService.
    // See Tree's API's to construct a tree from a file path, e.g., 
    // tree = new Tree('/path/to/swc/file.swc')
    // NB: the original image is not required to convert traces, but without
    // it, ROIs may be shifted relatively to signal. To verify this, replace
    // imp with _any_ other image, e.g., imp = tree.getSkeleton2D()
    tree = snt.demoTree()
    imp = snt.demoTreeImage() // tree.getSkeleton2D()
    tree.assignImage(imp)
    convertPaths = convertBranchPoints = convertTips = true
}

// Option 1: Store ROIs in the image overlay  
converter = new RoiConverter(tree, imp)
if (convertPaths) converter.convertPaths()
if (convertBranchPoints) converter.convertBranchPoints()
if (convertTips) converter.convertTips()
imp.show()

// Option 2: Store ROIs in the ROI Manager
converter = new RoiConverter(tree)
holdingOverlay = new Overlay()
if (convertPaths) converter.convertPaths(holdingOverlay)
if (convertBranchPoints) converter.convertBranchPoints(holdingOverlay)
if (convertTips) converter.convertTips(holdingOverlay)

rm = RoiManager.getInstance2()
if (rm == null) rm = new RoiManager()
for (roi in holdingOverlay.toArray()) rm.addRoi(roi)
rm.runCommand("sort")
rm.setVisible(true)

// Option 3: If snt is running we can simply run Path Manager's 
// Analyze>Convert to ROIs. E.g.:
// if (snt.getUI())
//     snt.getUI().getPathManager().runCommand("Convert to ROIs...", "Tips")
