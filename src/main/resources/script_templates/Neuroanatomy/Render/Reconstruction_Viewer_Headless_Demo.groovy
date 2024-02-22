#@ LUTService lut
#@ SNTService snt

import sc.fiji.snt.analysis.MultiTreeColorMapper
import sc.fiji.snt.viewer.Viewer3D


/**
 * Exemplifies how to use Reconstruction Viewer in (experimental) headless mode
 */

// Retrieve some reconstructions
trees = snt.demoTrees()

// Do some programatic visualization
mapper = new MultiTreeColorMapper(trees)
colorTable = lut.loadLUT(lut.findLUTs().get("Ice.lut"))
mapper.map("no. of branches", colorTable)

// Initialize a headless, non-displayable Reconstruction Viewer
viewer = new Viewer3D(false, "offscreen") // non-interactive viewer rendering offscreen

// Add objects to the scene. NB: Currently, meshes not fully supported
viewer.add(trees);
viewer.addColorBarLegend(mapper)

// Customize scene
viewer.setViewMode("xy")
viewer.setEnableDarkMode(false)
viewer.addLabel("!! HEADLESS SCENE !!")

// Display a snapshot of the scene as an ImagePlus object
viewer.snapshot().show()

