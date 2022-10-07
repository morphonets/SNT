#@String(value="This script converts all .ndf files in a directory into ROIs (as ZIP files). Conversion log is shown in Console.", visibility="MESSAGE") msg
#@File(label="Directory of .ndf files:", style="directory") input_dir
#@Boolean(label="Open Console", value=false) open_console
#@String(value="NB: ROI Manager will be reset during conversion: Any stored ROIs will be cleared.", visibility="MESSAGE") msg2
#@LogService log
#@StatusService status
#@UIService ui

"""
file:       Convert_NeuronJ_Traces_to_ROIs.py
author:     Tiago Ferreira
version:    20221007
info:       Converts .ndf (NeuronJ) files in a directory into ROIs
"""

import os
from sc.fiji.snt.io import NDFImporter
from ij.plugin.frame import RoiManager

def convert(filepath):
    rm = RoiManager.getInstance2()
    if not rm:
        rm = RoiManager()
    rm.reset()
    file_saved = False
    try:
        importer = NDFImporter(filepath)
        rm.setOverlay(importer.getROIs())
        rm.deselect()
    finally:
        if rm.getCount() > 0:
            file_saved = rm.save(filepath + ".RoiSet.zip")
        rm.reset()
    return file_saved
 
def run():
    if not input_dir:
        return
    if not os.path.isdir(str(input_dir)):
        ui.showDialog("Chosen path is not valid.", "Error")
        return
    status.showStatus("Converting .ndf files...")
    if open_console:
        ui.getDefaultUI().getConsolePane().show()
    conversion_counter = 0
    d = str(input_dir)
    print('Processing %s...' % d)
    for f in os.listdir(d):
        if os.path.basename(f).startswith('.'):
            continue
        if not f.lower().endswith('.ndf'):
            print('Skipping %s...' % f)
            continue
        file_path = os.path.join(d, f)
        print('Converting %s to .RoiSet.zip' % f)
        if convert(file_path):
            conversion_counter += 1
        else:
            log.error('Could not convert [%s]. Conversion failed!' % f)
    msg = str(conversion_counter) + ' file(s) successfully converted.'
    print(msg)
    ui.showDialog(msg)

run()

