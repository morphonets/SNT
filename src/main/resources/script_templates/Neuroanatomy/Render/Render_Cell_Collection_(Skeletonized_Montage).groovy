#@SNTService snt

/**
 * Exemplifies how to skeletonize neuronal reconstructions.
 * TF 20200701
 */

import ij.ImagePlus
import sc.fiji.snt.Tree

// Retrieve a collection of trees. Here we'll use demo reconstructions
// provided by SNTService. For rendering a collection of SWC files:
// trees = Tree.listFromDir("/path/to/swc/directory", "optional filename pattern")
trees = snt.demoTrees() // retrieve collection of trees


// First we'll handle 2D skeletons
imps = [] // define list holding skeletonized images
for (tree in trees) {
	imps << tree.getSkeleton2D()
}

// Place all skeletons in a common image stack. For simplicity
// we'll use IJ1's "Images to Stack" command
treeStack = new ij.plugin.ImagesToStack().run(imps as ImagePlus[])

// Assemble a montage using IJ1's "Make Montage" command
montage = new ij.plugin.MontageMaker().makeMontage2(
                        treeStack, //image
						2, 2, 1d, // columns, rows, scale
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
lutPath = ij.IJ.getDirectory("luts") + "glasbey_on_dark.lut"
lut = new ij.plugin.LutLoader().openLut(lutPath)
treeStack.getProcessor().setLut(lut)
treeStack.show()

// Assemble a projection
projector = new ij.plugin.ZProjector(treeStack)
projector.setMethod(ij.plugin.ZProjector.MAX_METHOD)
projector.doProjection()
projector.getProjection().show()
