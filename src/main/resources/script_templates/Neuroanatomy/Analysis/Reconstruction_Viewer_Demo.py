# @Context context
# @LUTService lut
# @SNTService snt
# @UIService ui

"""
file:       Reconstruction_Viewer_Demo.py
author:     Tiago Ferreira
version:    20190318
info:       Exemplifies how to render reconstructions and neuropil annotations
            in a stand-alone Reconstruction Viewer. Requires internet connection
"""
from sc.fiji.snt import Tree
from sc.fiji.snt.io import MouseLightLoader
from sc.fiji.snt.analysis import TreeColorMapper
from sc.fiji.snt.annotation import AllenCompartment
from sc.fiji.snt.annotation import AllenUtils
from sc.fiji.snt.viewer import Viewer3D


def run():

    if not MouseLightLoader.isDatabaseAvailable():
        ui.showDialog("Could not connect to ML database", "Error")
        return

    # Import one of the largest cells in the MouseLight database
    loader = MouseLightLoader("AA0100")
    if not loader.idExists():
        ui.showDialog("Somehow the specified id was not found", "Error")
        return

    # We'll visualize dendrites and axons differently, loading cell in 2 steps
    print("    Retrieving ML neuron...")
    dend_tree = loader.getTree('dendrite')
    axon_tree = loader.getTree('axon')

    # Color code axons: map path order to color table
    print("... Done. Assigning LUT to axonal arbor...")
    mapper = TreeColorMapper(context)
    mapper.map(axon_tree, "distance to soma", "Ice.lut")

    # Assemble 3D scene in Reconstruction Viewer
    print("... Done. Preparing Reconstruction Viewer...")
    viewer = snt.newRecViewer(True)  # viewer with GUI controls?

    # Add reconstructions and color map legend
    dend_tree.setColor("orange")
    viewer.add([dend_tree, axon_tree])
    viewer.addColorBarLegend(mapper)

    # Add Allen Reference Atlas annotations: brain contour
    # and compartment associated with soma of downloaded cell
    brainMesh = AllenUtils.getCompartment("Whole Brain").getMesh()
    brainMesh.setBoundingBoxColor("cyan")
    viewer.add([brainMesh, loader.getSomaCompartment().getMesh()])

    # Display scene
    viewer.setViewMode("side")
    viewer.show()
    viewer.setAnimationEnabled(True)
    print("... Done. With Viewer active, Press 'H' or 'F1' for help")
    print("    To programmatically access the Viewer at a later time point:")
    print("    viewer = snt.getRecViewer(%s)" % viewer.getID())


run()
