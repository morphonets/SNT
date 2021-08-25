#@SNTService sntService
#@DatasetIOService datasetIOService

import sc.fiji.snt.Tree
import sc.fiji.snt.SciViewSNT


/**
 * Exemplifies how bridge SNT with sciview. Have a look at 
 * https://docs.scenery.graphics/sciview/ if sciview is not available in your system
 * 
 * TF, KH 20190827
 */

// All the heavy lifting is performed by SciViewSNT, that can be instantiated
// from a SciJava Context, an existing SciView instance, or from SNTService
// directly using sntService.getOrCreateSciViewSNT()
sciViewSNT = sntService.getOrCreateSciViewSNT()

// We can now add reconstructions as we do with Reconstruction Viewer:
tree = sntService.demoTrees().get(0) // retrieve a sample tree from SNTService
tree.setColor("red")
sciViewSNT.addTree(tree)

// Now let's add a volume:
ds = datasetIOService.open("http://wsr.imagej.net/images/t1-head.gif")
sciViewSNT.getSciView().addVolume(ds)

// Let's add another tree, and center the view on it
tree.translate(2, 2, 2)
tree.setColor("cyan")
sciViewSNT.addTree(tree)
sciViewSNT.getSciView().centerOnNode(sciViewSNT.getTreeAsSceneryNode(tree))
