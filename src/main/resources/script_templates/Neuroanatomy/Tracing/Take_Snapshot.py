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
version:    20260818
info:       Displays a WYSIWYG image of a tracing canvas. Exemplifies
            how to script SNT using SNTService
"""

from sc.fiji.snt import Tree

snt.requireVersion("5.0.14") # SNT version required to run this script

def run():

    # Exit if SNT is not running
    if not snt.isActive():
        ui.showDialog("SNT does not seem to be running. Exiting..", "Error")
        return

    # This script's snapshot mechanism is classic-canvas-based (XY/ZY/XZ/3D panes), which don't
    # exist in Stream mode. BVV has its own native snapshot capture (press Shift+S) instead
    if snt.isStreamMode() and not snt.getInstance().isMaterializedCrop():
        ui.showDialog("Snapshots of XY/ZY/XZ/3D tracing canvases are not available in Stream "
                      "mode. If tracing in BVV, use its own snapshot capture (Shift+S) instead.",
                      "Not Available in Stream Mode")
        return

    # Retrieve current Tree (collection of paths) from the plugin
    include_only_selected_paths = snt.getPlugin().isOnlySelectedPathsVisible()
    tree = snt.getTree(include_only_selected_paths)

    # Refresh displays (just in case something needs to be updated)
    snt.updateViewers()

    # Offset traced paths so that fluorescent signal is not covered by rendered
    # paths. This offset is specified in (x,y,z) pixel coordinates it only affects
    # rendering (e.g., x=-10,y=10,z=1 offsets paths 10 pixels left, 10 pixels down,
    # 1 z-slice forward). The actual Path nodes are not translated. We'll store any
    # existing offset so that we can restore it later on
    existing_offset = tree.getCanvasOffset()

    try:
        # Apply offset, retrieve, and display snapshot image
        tree.applyCanvasOffset(offset, offset, offset)
        snap = snt.getInstance().captureView(view, mip)
        snap.show()
        ls.runLegacyCommand("ij.plugin.ScaleBar", " width=50 ")
    except:
        ui.showDialog("%s canvas does not seem to be available." % view, "Error")
    finally:
        # Restore offsets
        tree.applyCanvasOffset(existing_offset[0], existing_offset[1], existing_offset[2])


run()
