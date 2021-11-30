#@ File (style="directory", required=false, label="Reconstructions directory (Leave empty for demo):") recDir
#@ String (label="Mapping metric:", choices={"Cable length", "Cell/id", "Highest path order", "Horton-Strahler number", "No. of branch points", "No. of branches", "No. of tips"}) mapMetric
#@ String (label="Color mapping:", choices={"Ice.lut", "mpl-viridis.lut"}) lutName
#@ LUTService lut
#@ SNTService snt

/**
 * Exemplifies how to generate a publication-quality multi-panel figure in which
 * multiple reconstructions are sorted and color-coded by a specified morphometric
 * trait (cable length in this example).
 * The script prompts for a directory containing the reconstruction files to be
 * analyzed. If no directory is specified, the script will parse a collection of
 * dendritic arbors from the MouseLight database instead.
 * TF 20190919
 */

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.MultiTreeColorMapper
import sc.fiji.snt.viewer.MultiViewer2D


if (recDir) {
	// Retrive all reconstruction files from the directory
	trees = Tree.listFromDir(recDir.getAbsolutePath())
} else {
	// Directory is invalid. Let's retrieve demo data instead
	trees = snt.demoTrees()
	// Rotate the reconstructions to "straighten up" the
	// apical shaft of these pyramidal cell dendrites
	for (tree in trees) tree.rotate(Tree.Z_AXIS, 190)
}

// Align reconstructions, bringing their somas to a common origin
for (tree in trees) {
	root = tree.getRoot() // We could also use tree.getSomaLocation()
	tree.translate(-root.getX(), -root.getY(), -root.getZ())
}

// Color code each cell and assign a hue ramp to the group
colorTable = lut.loadLUT(lut.findLUTs().get(lutName))
mapper = new MultiTreeColorMapper(trees)
mapper.map("length", colorTable)

// Assemble a multi-panel Viewer2D from the color mapper
viewer = mapper.getMultiViewer()

// Customize viewer
viewer.setLayoutColumns( (trees.size()<11) ? trees.size() : (int)(trees.size()/2) )
viewer.setGridlinesVisible(false)
viewer.setOutlineVisible(false)
viewer.setAxesVisible(false)

// Display result (panels can  be exported by right-clicking on
// individual panels or by using viewer.save("/path/to/file")
viewer.show()
println("All done")
