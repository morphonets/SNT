# @SNTService snt
# @UIService ui

"""
file:       Analysis_Demo_(Interactive).py
author:     Tiago Ferreira
version:    20190610
info:       Exemplifies how to programmatically interact with a running instance
            of SNT to analyze traced data. Because of all the GUI updates, this
            approach is _significantly slower_ than analyzing reconstructions
            directly (see Analysis_Demo.py for comparison)
"""

import math

from sc.fiji.snt import (SNTUI, Tree)
from sc.fiji.snt.analysis import (TreeAnalyzer, TreeStatistics)


def run():

    # The SNTService contains convenience methods that will simplify
    # our script. For more advanced features we'll script other classes
    # directly, but we'll use SNTService whenever pertinent.
    # https://morphonets.github.io/SNT/index.html?sc/fiji/snt/SNTService.html
    # Let's start SNT's GUI if it is currently not running
    if not snt.getUI():
        snt.initialize(True)  # display GUI?

    # Let's import some demo data. See Scripted_Tracing_Demo.py for how
    # to retrieve the demo image associated with this data
    demo_tree = snt.demoTree()
    if not demo_tree:
        ui.showDialog("Somehow could not load bundled file.", "Error")
        return

    # Pause tracing functions and load demo data
    snt.getUI().changeState(SNTUI.TRACING_PAUSED)
    snt.loadTree(demo_tree)

    # Almost all SNT analyses are performed on a Tree, i.e., a collection of
    # Paths. To do so we can use TreeAnalyzer or TreeStatistics (the latter
    # features all of TreeAnalyzer's capabilities plus advanced functionality
    # for detailed statistics. We can instantiate both from SNTService using
    # 1) all the Paths currently loaded, or 2) just the current subset of
    # selected Paths in the Path Manager dialog.
    # This is useful when one only wants to analyze the group(s) of Paths
    # selected through Path Manager's filtering toolbar.
    # https://morphonets.github.io/SNT/index.html?sc/fiji/snt/analysis/TreeAnalyzer.html
    # https://morphonets.github.io/SNT/index.html?sc/fiji/snt/analysis/TreeStatistics.html
    analyzer = snt.getAnalyzer(False)  # Include only selected paths?
    stats = snt.getStatistics(False)   # Include only selected paths?

    # Beacause TreeAnalyzer was initiated from SNTService, measurements can be
    # displayed in SNT's UI:
    analyzer.summarize("TreeV Demo", True) # Split summary by compartment?
    analyzer.updateAndDisplayTable()

    # It is also possible to build the above instances from a Tree. This is
    # useful for post-hoc analysis and if, e.g, one needs to manipulate Paths
    # in advance.
    metric = "inter-node distance"  #same as TreeStatistics.INTER_NODE_DISTANCE
    summary_stats = stats.getSummaryStats(metric)
    stats.getHistogram(metric).show()
    print("Smallest inter-node distance: %d" % summary_stats.getMin())

    # E.g., let's' downsample the Tree, imposing 10um between 'shaft' nodes:
    # When downsampling, branch points and tip positions are not altered
    tree = snt.getTree(False) # Retrieve only paths selected in the Manager?
    tree.downSample(10) # 10um
    tree.setLabel("10um downsampled")

    # Let's compare the distribution of the chosen metric after downsampling:
    stats = TreeStatistics(tree)
    summary_stats = stats.getSummaryStats(metric)
    stats.getHistogram(metric).show()
    analyzer.summarize("TreeV Demo (Downsampled)", True)
    analyzer.updateAndDisplayTable()
    print("After downsampling: %d" % summary_stats.getMin())

    # To render both the downsampled tree and the original:
    tree.setColor("cyan")
    tree = snt.demoTree()
    tree.setColor("yellow")
    snt.loadTree(tree)
    snt.getRecViewer().show()
    snt.updateViewers()

run()
