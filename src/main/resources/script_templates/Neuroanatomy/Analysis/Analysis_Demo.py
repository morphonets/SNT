# @UIService ui
# @PlotService plotService

"""
file:       Analysis_Demo.py
author:     Tiago Ferreira, Cameron Arshadi
version:    20190610
info:       A Jython demo of how SNT can analyze neuronal reconstructions fetched
            from online databases such as MouseLight, NeuroMorpho or FlyCircuit.
            This demo requires internet connection and assumes you've already ran
            Analysis_Demo_(Interactive).py 
"""

from collections import defaultdict

from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import TreeStatistics
from sc.fiji.snt.io import (MouseLightLoader, NeuroMorphoLoader)



def run():

    # To load a neuron from a local file, one could use:
    tree = Tree.fromFile("path/to/local/file")
    if not tree:
       print("path/to/local/file is not a valid file")
    else:
        # Do something with it, e.g.
        axon = tree.subTree("axon")

    # (See Analysis_Demo_(Interactive).py for further details on Tree scripting)
    # For now, we'll import one of the largest cells in the MouseLight database
    loader = MouseLightLoader("AA0100")
    if not loader.isDatabaseAvailable():
        ui.showDialog("Could not connect to ML database", "Error")
        return
    if not loader.idExists():
        ui.showDialog("Somehow the specified id was not found", "Error")
        return

    # All of the raw data in the MouseLight database is stored as JSONObjects.
    # (https://stleary.github.io/JSON-java/index.html). If needed, these could
    # be access as follows: all_data = loader.getJSON()

    # To extract a compartment:
    d_tree = loader.getTree('dendrites')
    a_tree = loader.getTree('axon')
    for tree in [d_tree, a_tree]:

        print("Parsing %s" % tree.getLabel())
        d_stats = TreeStatistics(tree)

        # NB: SummaryStatistics should be more performant than DescriptiveStatistics
        # https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/TreeStatistics.html
        metric = "inter-node distance"  # same as TreeStatistics.INTER_NODE_DISTANCE 
        summary_stats = d_stats.getSummaryStats(metric)
        print("The average inter-node distance is %d" % summary_stats.getMean())

        # To display a detailed distribution analysis:
        d_stats.getHistogram(metric).show()

        # We can get the volume of a compartment by approximating the volume of
        # each path, and summing to total. For info on the assumptions made in
        # this volume calculation, have a look at Tree's documentation:
        # https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/Tree.html
        compartment_volume = tree.getApproximatedVolume()
        print("Approximate volume of tracing is %d cubic microns" % compartment_volume)

        # Let's find the minimum bounding box containing all existing nodes:
        # https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/util/BoundingBox.html
        bb = tree.getBoundingBox()
        bb_dim = bb.getDimensions()
        print("Dimensions of bounding box containing all nodes (in micrometers): {}x{}x{}".format(*bb_dim))

    # To load a neuron from NeuroMorpho.org we use NeuroMorphoLoader:
    # https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/io/NeuroMorphoLoader.html
    # We choose a reconstruction with traced node radii:
    # http://neuromorpho.org/neuron_info.jsp?neuron_name=Adol-20100419cell1
    nm_loader = NeuroMorphoLoader()
    if nm_loader.isDatabaseAvailable():
        nm_tree = nm_loader.getTree("Adol-20100419cell1")

        # Like before, we can plot data using the convenience of TreeStatistics
        if nm_tree is not None:
            nm_stats = TreeStatistics(nm_tree)
            nm_metric = "mean radius"  # same as TreeStatistics.MEAN_RADIUS
            nm_stats.getHistogram(nm_metric).show()

            # To build custom plots, we can use SciJava's PlotService. E.g., we
            # can see how radius changes with centrifugal path order. For
            # simplicity, we'll only consider the mean node radius of each path
            order_dict = defaultdict(list)
            for p in nm_tree.list():
                r = p.getMeanRadius()
                order_dict[p.getOrder()].append(r)
            l = []
            for item in order_dict.items():
                # average the mean path radius over each path order
                mean_radius = sum(item[1]) / len(item[1])
                l.append((item[0], mean_radius))

            l = sorted(l, key=lambda x: x[0])
            xs, ys = zip(*l)

            plot = plotService.newXYPlot()
            plot.setTitle("Mean path radius vs Path Order")
            series = plot.addXYSeries()
            series.setLabel("Adol-20100419cell1")
            series.setValues(xs, ys)
            ui.show(plot)


run()
