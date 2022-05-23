# @String(value="<HTML>This script runs Strahler Analysis on an entire directory of reconstruction files.<br>Processing log is shown in Console.", visibility="MESSAGE") msg
# @File(label="Input directory:", style="directory", description="Input folder containing reconstruction files (.traces, .swc, json) to be analyzed") input_dir
# @String(label="Filename filter", description="<HTML>Only filenames matching this string (case sensitive) will be considered.<br>Regex patterns accepted. Leave empty to disable fitering.",value="") name_filter
# @File(label="Output directory:", style="directory", description="output folder where main_tables and plots will be saved. Will be created if it does not exist") output_dir
# @String(label="Output (Tables and plots):", choices={"Save and display","Save without displaying anything"}) output_choice
# @String(label="Extra in-depth analysis:", choices={"None","Branch length", "Branch contraction"}) extra_analysis_choice
# @ImageJ ij


"""
file:       Strahler_Bulk_Analysis.py
author:     Tiago Ferreira
version:    20220523
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

        # retrieve main plot and main summary table
        main_chart = sa.getChart()
        main_chart.saveAsPNG(os.path.join(str(output_dir), "StrahlerPlot_Main.png"))
        main_table = sa.getSummaryTable()
        main_table_title = "CombinedStrahlerTableMain.csv"
        main_table.save(os.path.join(str(output_dir), main_table_title))
        if ('and display' in output_choice):
            main_table.show(main_table_title)
            main_chart.show()

        if not ('None' in extra_analysis_choice):
            # retrieve detailed plots and main detailed table
            detail_table = sa.getDetailedTable()
            detail_table_title = "CombinedStrahlerTableDetailed.csv"
            detail_table.save(os.path.join(str(output_dir), detail_table_title))
            if ('and display' in output_choice):
                detail_table.show(detail_table_title)

            box_chart = sa.getBoxPlot(extra_analysis_choice)
            chart_title = "StrahlerBoxPlot %s.png" % extra_analysis_choice
            box_chart.saveAsPNG(os.path.join(str(output_dir), chart_title))
            hist_chart = sa.getHistogram(extra_analysis_choice)
            chart_title = "StrahlerHistogram %s.png" % extra_analysis_choice
            hist_chart.saveAsPNG(os.path.join(str(output_dir), chart_title))

            if ('and display' in output_choice):
                hist_chart.show()
                box_chart.show()

        failures = sa.getInvalidTrees()
        if (failures):
            log("The following files seem to contain invalid structures", "warn")
            log(', '.join(tree.getLabel() for tree in failures), "warn")
        else:
            log("All trees successfully parsed")

    log("Files saved to %s" % str(output_dir))
    ij.ui().showDialog("Analysis complete. See console for details.", "Completed")

main()
