#@SNTService snt

/** 
 *  Exemplifies how to compute Delaunay triangulations for sub-compartments of
 *  a neuronal reconstruction.
 *  TF 20240115
 */

// Retrieve demo reconstruction. Real-world data could be retrieved using, eg:
// tree = new Tree('/path/to/reconstruction/file')
tree = snt.demoTree("pyramidal neuron")

// Retrieve 'matched' image. See below method for details
imp = getImage(tree)

// Retrieve overlay with centroid coordinates of branch points and tips (each
// coordinate group stored in a multipoint ROI). See below method for details
rois = getBPsAndTips(tree)

// Retrieve list of 'contrast' colors
colors = SNTColor.getDistinctColorsAWT(rois.size())

// For each group of coordinates: retrieve the multipont ROI, compute the
// corresponding Delaunay (also a ROI), color it, and add it to the overlay
(0..<rois.size()).each {
	imp.setRoi(rois.get(it))
	IJ.run(imp, "Delaunay Voronoi", "mode=Delaunay interactive make")
	delaunayRoi = imp.getRoi()
	delaunayRoi.setStrokeColor(colors[it])
	overlay.add(delaunayRoi)
	imp.setRoi(null)
}

// Display the result
imp.setOverlay(overlay)
imp.setTitle(tree.getLabel())
imp.show()


/**
 * Returns a rasterized image of a tree, with a 1-to-1 correspondence between
 * its coordinates and pixel coordinates on the images
 *
 * @param tree the imput tree
 * @return the 2D, skeletonized image of the tree as an ImagePlus
 */
def getImage(tree) {
	// we'll need to set the tree's "upper-left" coordinate to 0 to obtain 1-1
	// correspondence with pixel coordinates. Currently, Tree#getSkeleton2D()
	// pads the resulting image with a 3-pixel border, so we'll account for it
	//https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/Tree.html
	box = tree.getBoundingBox()
	tree.translate(-box.origin().getX(), -box.origin().getY(), -box.origin().getZ())
	tree.translate(3, 3, 0)
	tree.getSkeleton2D()
}

/**
 * Returns an overlay containing tips and branch-point coordinates as multipoint
 * ROIs for each sub-compartment of a Tree. A sub-compartment is defined, by
 * SWC-type flags. Nodes tagged as 'soma' are excluded.
 *
 * @param tree the imput tree
 * @return the overlay listing all converted ROIs
 */
def getBPsAndTips(tree) {
	overlay = new Overlay()
	compartments = tree.getSWCTypes(false) // include soma?
	compartments.each {
		//https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/RoiConverter.html
		converter = new RoiConverter(tree.subTree(it))
		converter.convertBranchPoints(overlay)
		converter.convertTips(overlay)
	}
	overlay
}

// Imports below:
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.RoiConverter
import sc.fiji.snt.util.SNTColor
import ij.IJ
import ij.gui.Overlay
