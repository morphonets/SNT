# @String(value="<HTML>This script creates an illustration of a tracing canvas.<br>N.B.: Paths can also be exported as vector graphics<br>using <i>Reconstruction Plotter...</i> or <i>Create Figure...</i>",visibility="MESSAGE") msg
# @String(label="Tracing Canvas", choices={"XY", "ZY", "XZ", "3D"}, style="radioButtonHorizontal") view
# @double(label="Paths offset",description="In pixels. Positive values move paths SE. Negative NW", value=20) offset
# @boolean(label="Max Intensity Projection",description="If current image is a stack, compute Max Intensity Projection") mip
# @LegacyService ls
# @SNTService snt
# @UIService ui

"""
file:       Take_Snapshot.py
author:     Tiago Ferreira
version:    20180614
info:       Displays a WYSIWYG image of a tracing canvas. Exemplifies
            how to script SNT using SNTService
"""

from sc.fiji.snt import Tree

def run():

    # Exit if SNT is not running
    if not snt.isActive():
        ui.showDialog("SNT does not seem to be running. Exiting..", "Error")
        return

    # Retrieve current Tree (collection of paths) from the plugin
    include_only_selected_paths = snt.getPlugin().isOnlySelectedPathsVisible()
    tree = snt.getTree(include_only_selected_paths)

    # Refresh displays (just in case something needs to be updated)
    snt.updateViewers()

    # Offset traced paths so that fluorescent signal is not covered by
    # rendered paths. This offset is specified in pixels and it only
    # affects rendering. The actual Path nodes are not translated.
    # Offset is specified in (x,y,z) coordinates. Eg, (x=-10,y=10,z=1)
    # offsets paths 10 pixels left, 10 pixels down, 1 z-slice forward
    tree.applyCanvasOffset(offset,offset,offset)

    try:
        # Retrieve 'snapshot'
        snap = snt.captureView(view, mip)
 
        # Restore offsets, display 'snapshot' and add a scale bar to it
        if offset != 0:
            tree.applyCanvasOffset(0,0,0)
        snap.show()
        ls.runLegacyCommand("ij.plugin.ScaleBar", " width=50 ")
    except:
        ui.showDialog("%s canvas does not seem to be available." % view, "Error")
        return


run()
