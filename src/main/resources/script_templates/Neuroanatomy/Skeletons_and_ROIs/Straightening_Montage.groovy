#@String(value="<HTML>This script displays traced neurites as a sequence of straightened<br>(linearized) voxels. Sequence is sorted by path length.", visibility="MESSAGE") msg
#@File (required=false, label="Reconstruction file") treeFile
#@int (label="Straightening thickness (in pixels)", min=4) thickness
#@boolean (label="Ignore choices above and run demo") demo
#@SNTService sntService
#@UIService uiService


/** 
 *  Exemplifies how to use IJ1 to make a montage out of 'straightened' Paths.
 *  For further details:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/PathStraightener.html
 *  TF 20220624
 */


import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.PathStraightener
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.ImagesToStack
import ij.plugin.MontageMaker;


def image, tree
try {
	if (demo) {
		// Load demo data from SNTService
		image = sntService.demoImage("OP_1")
		tree = sntService.demoTree("OP_1")
		thickness = 10
	} else {
		// Load data from file paths
		image = IJ.openImage(imgFile.getAbsolutePath())
		tree = Tree.fromFile(treeFile.getAbsolutePath())
        thickness = Math.max(4, thickness)
	}
} finally {
	if (!tree || !image) {
		uiService.showDialog("Invalid file paths or demo data not available.")
		return
	}
}

// Since we'll need to access pixel coordinates, we need to associate
// the Tree object to its source image that will be 'straightened'
tree.assignImage(image)

// Now we'll iterate through all the paths in the tree, converting each
// path into a sequence of polylines. Each polyline will be straighted
// and straightened sequences concatenated into a 2D grayscale image.
// We'll sort  paths by length before the iteration
imps = []
paths = tree.list();
for (path in paths.sort{it.getLength()}) {
	ps = new PathStraightener(path, image)
	ps.setWidth(thickness)
	try {
		imps.add(ps.straighten())
	} catch (IllegalArgumentException ex) {
		// single point path, out-of-bounds, etc.
		println("Straightening failed for " + path)
		ex.printStackTrace()
	}
}

// We can now place all the images in a common image stack: 1st slice
// contains the smallest path;  last slice the longest path
stackedPaths = ImagesToStack.run(imps as ImagePlus[])

// Finally, make and display the stack as a montage
new MontageMaker().makeMontage(
	stackedPaths, // image
	1, imps.size(), // columns, rows
	1, // resizing scale of individual panes
	1, imps.size(), 1, // from, to, step size
	0, false) // border thickness, labels?

//stackedPaths.show() // display temporary stack
//image.show() // display original image
//tree.show2D() // display reconstruction

