# @String(value="<HTML><b>Scale: Apply Scaling Factor(s)",visibility="MESSAGE") msg1
# @double(label="X", min=0) xscale
# @double(label="Y", min=0) yscale
# @double(label="Z", min=0) zscale
# @double(label="Radii", min=0) rscale
# @boolean(label="Skip scaling") skip_scale
# @String(value="<HTML><b>Translate: Apply Offset(s)  ",visibility="MESSAGE") msg2
# @double(label="X") xtrans
# @double(label="Y") ytrans
# @double(label="Z") ztrans
# @boolean(label="Skip translation") skip_trans
# @String(value="<HTML><b>Rotate: Apply Rotation Angle ",visibility="MESSAGE") msg3
# @double(label="Angle") angle
# @String(label="Rotation axis", choices={"X", "Y", "Z"}) axis_choice
# @boolean(label="Skip rotation") skip_rot
# @String(value="<HTML><b>Swap Axes: Exchange Coordinates",visibility="MESSAGE") msg4
# @String(label="First axis", choices={"X", "Y", "Z"}) swap_choice_src
# @String(label="Second axis", choices={"X", "Y", "Z"}) swap_choice_tgt
# @boolean(label="Skip swap") skip_swap
# @String(value="<HTML><b>Options",visibility="MESSAGE") msg5
# @String(label="Scope", choices={"All paths", "Selected paths only"}) scope
# @SNTService snt
# @UIService ui

"""
file:       Transform_Paths.py
author:     Tiago Ferreira
version:    20210512
info:       Applies transformations to paths in the active SNT instance
"""

from sc.fiji.snt import Tree

def getAxis(choice):
    if "X" in choice:
        return Tree.X_AXIS
    if "Y" in choice:
        return Tree.Y_AXIS    
    if "Z" in choice:
        return Tree.Z_AXIS
    return -1
  

def run():

    global skip_scale, skip_trans, skip_rot, skip_swap
    global xscale, yscale, zscale, rscale, xtrans, ytrans, ztrans
    global axis_choice, angle, swap_choice_src, swap_choice_tgt

    # Exit if SNT is not running
    if not snt.isActive():
        ui.showDialog("SNT does not seem to be running.", "Error")
        return

    # Retrieve paths from the plugin
    tree = snt.getTree("Selected" in scope)
    if tree.isEmpty():
        ui.showDialog("At least one path is required but none is available.", "Error")
        return

    # Apply transformations
    if not skip_scale:
        if xscale * yscale == 0:
            ui.showDialog("Scaling factor(s) would nullify path(s).", "Error")
        else:
            tree.scale(xscale, yscale, zscale, rscale)
    if not skip_trans:
        tree.translate(xtrans, ytrans, ztrans)
    if not skip_rot:
         tree.rotate(getAxis(axis_choice), angle)
    if not skip_swap:
         tree.swapAxes(getAxis(swap_choice_src), getAxis(swap_choice_tgt)) 
    
    # Refresh displays
    snt.updateViewers()


run()
