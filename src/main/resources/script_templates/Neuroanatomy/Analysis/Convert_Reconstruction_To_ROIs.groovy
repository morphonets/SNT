#@SNTService snt


/** 
 *  Exemplifies how to convert a reconstruction into a series of ROIs.
 *  For further details:
 *  https://morphonets.github.io/SNT/index.html?sc/fiji/snt/Tree.html and
 *  https://morphonets.github.io/SNT/index.html?sc/fiji/snt/analysis/RoiConverter.html
 *  TF 20200705
 */

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.RoiConverter
import ij.gui.Overlay
import ij.plugin.frame.RoiManager


// We'll use a demo reconstruction and image provided by SNTService.
// See Tree's API's to construct a tree from a file path, e.g., 
// tree = new Tree('/path/to/swc/file.swc')
// NB: the original image is not required to convert traces, but without
// it, ROIs may be shifted relatively to signal. To verify this, replace
// imp with _any_ other image, e.g., imp = tree.getSkeleton2D()
tree = snt.demoTree()
imp = snt.demoTreeImage() // tree.getSkeleton2D()
tree.assignImage(imp)

// Option 1: Store ROIs in the image overlay  
converter = new RoiConverter(tree, imp)
converter.convertPaths()
converter.convertBranchPoints()
converter.convertTips()
imp.show()

// Option 2: Store ROIs in the ROI Manager
converter = new RoiConverter(tree)
holdingOverlay = new Overlay()
converter.convertPaths(holdingOverlay)
converter.convertBranchPoints(holdingOverlay)
converter.convertTips(holdingOverlay)

rm = RoiManager.getInstance2()
if (rm == null) rm = new RoiManager()
for (roi in holdingOverlay.toArray()) rm.addRoi(roi)
rm.runCommand("sort")
rm.setVisible(true)

// Option 3: If snt is running we can simply run Path Manager's 
// Analyze>Convert to ROIs. E.g.:
if (snt.getUI())
    snt.getUI().getPathManager().runCommand("Convert to ROIs...", "Tips")
