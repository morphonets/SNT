#@ File (style="directory", required=false, label="Reconstructions directory (Leave empty for demo):") recDir
#@ boolean (label="Choose panel dimensions automatically", value=true) autoSize
#@ int (label="Number of columns", value=0) columns
#@ int (label="Number of rows", value=0) rows
#@ Float (label="Scale factor for reconstructions", value=1.0) scale
#@ String (label="Color mapping:", choices={"Ice.lut", "mpl-viridis.lut", "glasbey_on_dark.lut"}, value="glasbey_on_dark.lut") lutName
#@ SNTService snt

/**
 * Exemplifies how to skeletonize neuronal reconstructions.
 * TF 20200701
 */

import java.lang.Math
import ij.ImagePlus
import sc.fiji.snt.Tree

if (recDir) {
    // Retrive all reconstruction files from the directory
    trees = Tree.listFromDir(recDir.getAbsolutePath())
} else {
    // Directory is invalid. Let's retrieve demo data instead
    trees = snt.demoTrees()
    autoSize = true
    scale = 1
}

// First we'll handle 2D skeletons
imps = [] // define list holding skeletonized images
for (tree in trees) {
	imps << tree.getSkeleton2D()
}

// Place all skeletons in a common image stack. For simplicity
// we'll use IJ1's "Images to Stack" command
treeStack = new ij.plugin.ImagesToStack().run(imps as ImagePlus[])

// Assemble a montage using IJ1's "Make Montage" command

if (autoSize) {
    N = trees.size()
    columns = (int)Math.floor(Math.sqrt(N))
    rows = (int)Math.ceil(N/columns)
}

montage = new ij.plugin.MontageMaker().makeMontage2(
                        treeStack, //image
						Math.max(1, columns), Math.max(1, rows), Math.max(0.1d, scale as double),
						1, treeStack.getNSlices(), 1,// from, to, step
						0, false) // border thickness, labels?
montage.setTitle("2D Skeletons")
montage.show()

// Now, we'll handle 3D skeletons
imps = [] // define list holding skeletonized images
trees.eachWithIndex { tree, index ->
    imp = tree.getSkeleton()
    // assign a unique intensity value to each image
    (1..imp.getStack().getSize()).each{
        imp.getStack().getProcessor(it).subtract(index *25);
    }
    imps << imp
}

// Place all skeletons in a common stack using IJ1's 
// "Image>Stack>Tools>Concatenate" command
treeStack = new ij.plugin.Concatenator().concatenate(imps as ImagePlus[], false)
treeStack.setTitle("3D Skeletons")

// Assign unique colors to each skeleton and display the result
lutPath = ij.IJ.getDirectory("luts") + lutName
lut = new ij.plugin.LutLoader().openLut(lutPath)
treeStack.getProcessor().setLut(lut)
treeStack.show()

// Assemble a projection
projector = new ij.plugin.ZProjector(treeStack)
projector.setMethod(ij.plugin.ZProjector.MAX_METHOD)
projector.doProjection()
projector.getProjection().show()
