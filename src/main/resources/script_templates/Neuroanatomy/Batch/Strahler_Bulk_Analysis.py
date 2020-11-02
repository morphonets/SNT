# @String(value="<HTML>This script runs Strahler Analysis on an entire directory of reconstruction files.<br>Processing log is shown in Console.", visibility="MESSAGE") msg
# @File(label="Input directory:", style="directory", description="Input folder containing reconstruction files (.traces, .swc, json) to be analyzed") input_dir
# @String(label="Filename filter", description="<HTML>Only filenames matching this string (case sensitive) will be considered.<br>Regex patterns accepted. Leave empty to disable fitering.",value="") name_filter
# @File(label="Output directory:", style="directory", description="output folder where tables and plots will be saved. Will be created if it does not exist") output_dir
# @String(label="Output (tables and plots):", choices={"Save and display plots","Save without displaying anything"}) output_choice
# @ImageJ ij


"""
file:       Strahler_Bulk_Analysis.py
author:     Tiago Ferreira
version:    20201101
info:       Performs bulk Strahler Analysis 
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.plugin import StrahlerCmd
import os

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
    trees = Tree.listFromDir(input_dir.getAbsolutePath(), name_filter)
    if not trees or trees.isEmpty():
        ij.ui().showDialog("Directory did not contain valid reconstructions.", "Error")
    else:
        display_plots = 'without' not in output_choice
        log("Parsing %s files..." % len(trees))
        for tree in trees:
            log("Parsing: %s" % tree.getLabel())
            sa = StrahlerCmd(tree)
            sa.setContext(ij.context())
            if not sa.validStructure():
                 log("Skipping... Not a valid structure", "warn")
            else:
                plot = sa.getChart()
                plot_title = "%s_StrahlerPlot.png" % tree.getLabel()
                plot.saveAsPNG(os.path.join(str(output_dir), plot_title))
                table = sa.getTable()
                table_title = "%s_StrahlerTable.csv" % tree.getLabel()
                table.save(os.path.join(str(output_dir), table_title))
                if (display_plots):
                    plot.show()
        log("Done.")
        ij.ui().showDialog("Analysis complete. See console for details.", "Completed")

main()
