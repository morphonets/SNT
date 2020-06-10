#@SciView sciView
#@SNTService sntService
#@DatasetIOService datasetIOService

import sc.fiji.snt.Tree
import sc.fiji.snt.SciViewSNT


/**
 * Exemplifies how bridge SNT with SciView. Have a look at 
 * https://imagej.net/SNT#Installation if SciView is not available in your system
 * 
 * TF, KH 20190827
 */

// All the heavy lifting is performed by SciViewSNT, that can be instantiated
// from a SciJava Context, an existing SciView instance, or from SNTService
// directly using sntService.getOrCreateSciViewSNT()
sciViewSNT = new SciViewSNT(sciView)

// We can now add reconstructions as we do with Reconstruction Viewer:
tree = sntService.demoTree() // retrieve a toy tree from SNTService
tree.setColor("red")
sciViewSNT.addTree(tree)

// Now let's add a volume:
ds = datasetIOService.open("http://wsr.imagej.net/images/boats.gif");
sciView.addVolume(ds);

// Let's add another tree, and center the view on it
tree.translate(2, 2, 2)
tree.setColor("cyan")
sciViewSNT.addTree(tree)
sciView.centerOnNode(sciViewSNT.getTreeAsSceneryNode(tree))

