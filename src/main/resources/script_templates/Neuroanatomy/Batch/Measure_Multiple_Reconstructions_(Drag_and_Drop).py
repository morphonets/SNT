# @SNTService snt

"""
file:      Measure_Multiple_Reconstructions_(Drag_and_Drop).py
author:    Tiago Ferreira
version:   20261106
info:      Bulk measurements of reconstruction files using SNT's Measurements prompt
"""

# Opens an empty Measurements prompt. Drag and drop reconstruction files
# (or folders containing them) onto the dialog to have them measured
snt.newMeasureUI().setVisible(True)
