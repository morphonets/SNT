# @String(value="This script measures all SWC/TRACES files in a directory",visibility="MESSAGE") msg
# @File(label="Input directory", style="directory") input_dir
# @String(label="Consider only filenames containing",description="Clear field for no filtering",value="") name_filter
# @boolean(label="Include subdirectories") recursive
# @String(label="Metrics",choices={"Standard suite","Complete suite"}) chosen_metrics
# @Context context
# @DisplayService displayservice
# @LogService log
# @StatusService status
# @UIService uiservice


"""
file:      Measure_Multiple_Files.py
author:    Tiago Ferreira
version:   20200527
info:      Bulk measurements of reconstruction files using SNT
"""

import os, sys
from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import (TreeAnalyzer, SNTTable)


def get_trees(directory, filtering_string):
    """Returns a list containing the Tree objects which represent
       reconstruction files in the specified directory. The list
       will only contain Trees from SWC, TRACES and JSON files
       whose filename contains the specified string."""

    if not recursive:
        return Tree.listFromDir(directory, filtering_string)
    trees = []
    for (dirpath, dirnames, filenames) in os.walk(directory):
        trees += Tree.listFromDir(dirpath, filtering_string)
    return trees


def run():

    d = str(input_dir)
    trees = get_trees(d, name_filter)
    if not trees or len(trees) == 0:
        uiservice.showDialog('No files matched the specified criteria',
                             'Error')
        return

    # Define a common table to host results
    table = SNTTable()  # a org.scijava.table.DefaultGenericTable

    # Define the metrics to be considered
    if 'Complete' in chosen_metrics:
        metrics = TreeAnalyzer.getAllMetrics()
    else:
        metrics = TreeAnalyzer.getMetrics()

    for (counter, tree) in enumerate(trees):

        msg = 'Analyzing Tree: %s: %s...' % (counter + 1,
                tree.getLabel())
        status.showStatus(msg)
        print(msg)

        # Prepare analysis. We'll make TreeAnalyzer aware of current context
        # so that we don't need to worry about displaying/updating the table
        analyzer = TreeAnalyzer(tree)
        analyzer.setContext(context)
        analyzer.setTable(table, 'SWC Measurements: %s' % input_dir)

        # Analyze the data grouping measurements by compartment (e.g., axon,
        # dendrite). See the analysis API for more sophisticated operations:
        # https://morphonets.github.io/SNT/
        analyzer.measure(metrics, True)  # Split results by compartment?

    msg = 'Done. %s file(s) analyzed...' % (counter + 1)
    print(msg)
  
    # To save the table
    #table.save('path/to/swc/file.csv')
    uiservice.showDialog(msg)

run()
