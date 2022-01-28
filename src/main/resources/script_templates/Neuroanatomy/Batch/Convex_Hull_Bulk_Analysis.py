#@String(value="<HTML>This script runs Convex Hull Analysis on an entire directory of reconstruction files.<br>Processing log is shown in Console.", visibility="MESSAGE") msg
#@File(label="Input directory:", style="directory", description="Input folder containing reconstruction files (.traces, .swc, json) to be analyzed") input_dir
#@File(label="Output directory:", style="directory", description="output folder where table and illustrations will be saved. Will be created if it does not exist") output_dir
#@ImageJ ij
#@OpService op
#@DisplayService ds
#@SNTService snt

"""
file:       Convex_Hull_Bulk_Analysis.py
author:     Cameron Arshadi, Tiago Ferreira
version:    20220131
info:       https://forum.image.sc/t/convexhull-bulk-processing/62000
"""

import os
from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import ConvexHull2D, ConvexHull3D, SNTTable
from sc.fiji.snt.viewer import Viewer3D, Annotation3D
from sc.fiji.snt.util import SNTColor

def log(msg, level = "info"):
    # https://forum.image.sc/t/logservice-issue-with-jython-slim-2-7-2-and-scripting-jython-1-0-0/
    from org.scijava.log import LogLevel
    if "warn" in level:
        ij.log().log(LogLevel.WARN, msg)
    elif "error" in level:
        ij.log().log(LogLevel.ERROR, msg)
    else:
        ij.log().log(LogLevel.INFO, msg)

def main():
    # Retrieve reconstructions from input directory
    trees = Tree.listFromDir(input_dir.getAbsolutePath())

    if not trees or trees.isEmpty():
        ij.ui().showDialog("Directory did not contain valid reconstructions.", "Error")
    else:
        # Initialize an empty results table, a Reconstruction Viewer instance.
        # We'll also define a list of unique colors to use in the visualization
        table = SNTTable()
        viewer = snt.newRecViewer(True)
        colors = SNTColor.getDistinctColors(len(trees))
        log("Parsing %s files..." % len(trees))
        for tree, color in zip(trees, colors):
            log("Parsing: %s" % tree.getLabel())
            # Insert an empty row into our table: we'll fill it later
            table.insertRow(tree.getLabel())
            # Check if Tree is 2D or 3D. We'll pick the proper ConvexHull class
            # based on the result. If you use ConvexHull2D on a 3D Tree, it will
            # use a 2D projection of the Tree. Using ConvexHull3D on a 2D Tree
            # has undefined behavior.
            is_3D = tree.is3D()
            hull_class = ConvexHull3D if is_3D else ConvexHull2D
            compute_size = False  # We'll use ImageJ Ops to compute metrics on the result
            # Construct the hull instance (non-computed)
            hull = hull_class(ij.context(), tree.getNodes(), compute_size)
            # Carry out the computation
            hull.compute()
            # Get the geometric representation of the hull
            geo = hull.getMesh() if is_3D else hull.getPolygon()
            # ImageJ Ops has cool methods for geometric analysis of meshes and polygons
            # For more information on these and other functions, see the tutorial at
            # https://nbviewer.org/github/imagej/tutorials/blob/master/notebooks/1-Using-ImageJ/2-ImageJ-Ops.ipynb
            table.appendToLastRow("Size", op.geom().size(geo)) # area in 2D, volume in 3D
            table.appendToLastRow("Boundary Size", op.geom().boundarySize(geo)) # perimeter in 2D, area in 3D
            table.appendToLastRow("Main Elongation", op.geom().mainElongation(geo))
            table.appendToLastRow("Centroid", op.geom().centroid(geo))
            table.appendToLastRow("3D", is_3D)
            # Now we can add the reconstruction to the viewer
            tree.setColor(color)
            viewer.add(tree)
            # as well as its convex hull, by creating an Annotation3D from it
            geo_annot = Annotation3D(geo, color, tree.getLabel() + " Conv. Hull")
            viewer.add(geo_annot)

        # Now we display and save the table..
        ds.createDisplay(table)
        if not os.path.isdir(output_dir.getAbsolutePath()):
            os.mkdir(output_dir.getAbsolutePath())
        table.save(os.path.join(output_dir.getAbsolutePath(), "convex_hull_metrics.csv"))

        # ..As well as the viewer while saving a snapshot of the scene.
        # NB: it is possible to obtain such snapshot in a non-displayed 'headless'
        # viewer. See 'Reconstruction_Viewer_Headless_Demo' script for details
        viewer.show()
        viewer.setSnapshotDir(output_dir.getAbsolutePath())
        viewer.saveSnapshot()
        viewer.setAnimationEnabled(True)


main()
