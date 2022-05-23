# @String(value="<HTML>This script runs Strahler Analysis on an entire directory of reconstruction files.<br>Processing log is shown in Console.", visibility="MESSAGE") msg
# @File(label="Input directory:", style="directory", description="Input folder containing reconstruction files (.traces, .swc, json) to be analyzed") input_dir
# @String(label="Filename filter", description="<HTML>Only filenames matching this string (case sensitive) will be considered.<br>Regex patterns accepted. Leave empty to disable fitering.",value="") name_filter
# @File(label="Output directory:", style="directory", description="output folder where tables and plots will be saved. Will be created if it does not exist") output_dir
# @String(label="Output (tables and plots):", choices={"Save and display","Save without displaying anything"}) output_choice
# @ImageJ ij


"""
file:       Strahler_Bulk_Analysis.py
author:     Tiago Ferreira
version:    20220110
info:       Performs bulk Strahler Analysis 
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import SNTChart
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

    if not trees:
        ij.ui().showDialog("Directory did not contain valid reconstructions.", "Error")

    else:
        log("Parsing %s files..." % len(trees))
        sa = StrahlerCmd(trees)
        sa.setContext(ij.context())

        charts = []
        for m in ["length", "branches", " bif. ratio", "contraction", "fragmentation"]:
            chart = sa.getChart(m)
            charts.append(chart)
            chart_title = "StrahlerPlot_%s.png" % m
            chart.saveAsPNG(os.path.join(str(output_dir), chart_title))

        table = sa.getTable()
        table_title = "CombinedStrahlerTable.csv"
        table.save(os.path.join(str(output_dir), table_title))

        if ('Save and display' in output_choice):
            ij.display().createDisplay(table_title, table)
            SNTChart.combine(charts).show()

        failures = sa.getInvalidTrees()
        if (failures):
            log("The following files seem to contain invalid structures", "warn")
            log(', '.join(tree.getLabel() for tree in failures), "warn")
        else:
            log("All trees successfully parsed")

    log("Done.")
    ij.ui().showDialog("Analysis complete. See console for details.", "Completed")

main()
