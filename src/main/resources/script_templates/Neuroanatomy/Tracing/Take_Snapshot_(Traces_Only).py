# @String(value="<HTML>This script creates an illustration of a tracing canvas.<br>N.B.: Paths can also be exported as vector graphics<br>using the <i>Reconstruction Plotter...</i> command.",visibility="MESSAGE") msg
# @String(label="Tracing Canvas", choices={"XY", "ZY", "XZ"}, style="radioButtonHorizontal") view
# @ColorRGB(label="Background color", required='false') bckgrnd
# @LegacyService ls
# @SNTService snt
# @UIService ui

"""
file:       Take_Snapshot_(Traces_Only).py
author:     Tiago Ferreira
version:    20201009
info:       Displays a WYSIWYG image of a tracing canvas without displaying
            image data (see Take_Snapshot.py for details)
"""

from sc.fiji.snt import Tree
from org.scijava.util import ColorRGB

def run():

    # Exit if SNT is not running
    if not snt.isActive():
        ui.showDialog("SNT does not seem to be running. Exiting..", "Error")
        return

    # Refresh displays (just in case something needs to be updated)
    snt.updateViewers()

    # Retrieve all paths from the plugin
    tree = snt.getTree()

    background = ColorRGB('white') if bckgrnd is None else bckgrnd
    try:
        snap = snt.captureView(view, background)
        snap.show()
        ls.runLegacyCommand("ij.plugin.ScaleBar", " width=50 ")
    except:
        ui.showDialog("%s canvas does not seem to be available." % view, "Error")
        return


run()
