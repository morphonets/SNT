# @SNTService snt
# @UIService ui

"""
file:    Scripted_Tracing_Demo.py
author:  Tiago Ferreira, Cameron Arshadi
version: 20231214
info:    Exemplifies how to programmatically perform A* tracing between two
         points without GUI interaction, which allows for automated tracing
         of relatively simple structures (e.g., neurospheres neurites,
         microtubule bundles, etc). In this demo, points are retrieved from the
         SWC file of SNT's "demo tree", effectively recreating the initial SWC
         data.
"""

import time
from sc.fiji.snt import (Path, SNT, Tree)

def run():

    # Exit if SNT is already busy doing something
    if snt.isActive() and snt.getUI() and not snt.getUI().isReady():
        ui.showDialog("Please complete current operation before running this script", "Error")
        return

    # Prepare plugin for auto-tracing. Typically we would initialize SNT with
    # an image or image path, but in this case we can specify a demo dataset:
    plugin = snt.initialize("demo: fractal", False) # image, whether UI should be shown
    plugin.enableAstar(True)
    plugin.getPathAndFillManager().clear()
    #plugin.startHessian("primary", 1.0, 25.0, True)

    ref_tree = snt.demoTree()
    new_tree = Tree()
    for path in ref_tree.list():

        end_point = path.getNode(path.size() - 1)
        fork_point = path.getStartJoinsPoint()

        if fork_point is None:
            # We're creating a primary Path
            start_point = path.getNode(0)
            primary_path = plugin.autoTrace(start_point, end_point, None)
            new_tree.add(primary_path)
        else:
            # We're creating a branched Path: assign fork point to new Tree
            fork_path = new_tree.get(ref_tree.indexOf(path.getStartJoins()))
            fork_point.setPath(fork_path)
            child = plugin.autoTrace([fork_point, end_point], fork_point)
            new_tree.add(child)

        # The demo tree is tiny: Add a delay to better monitor progress
        time.sleep(0.2)


    #snt.dispose()


run()
