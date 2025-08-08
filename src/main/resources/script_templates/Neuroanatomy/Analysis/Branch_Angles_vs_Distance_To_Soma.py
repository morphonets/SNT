from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import TreeStatistics, SNTChart
from sc.fiji.snt.io import NeuroMorphoLoader
from sc.fiji.snt.util import ColorMaps

"""
file:       Branch_Angles_vs_Distance_To_Soma.py
author:     Tiago Ferreira
version:    20250421
info:       A Jython demo on how to display two-dimensional data in SNT.
            In this case, bifurcation angles as a function of their distance to
            the cell body for axons of basket cells. Morphologies are
            retrieved from NeuroMorpho.org (internet connection required). This
            type of data matrix could also be displayed as a heatmap. See
            https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/SNTChart.html
"""

# Documentation Resources: https://imagej.net/plugins/snt/scripting
# Latest SNT API: https://javadoc.scijava.org/SNT/

    
def branch_angles_x_path_distances(tree, holding_dic):

    # retrieve tree as a graph and its root (soma)
    graph = tree.getGraph(True) #simplified graph?
    root = graph.getRoot()
    
    # retrieve branch points (BPs) and their Euclidean distance to root
    bp_to_root_dxs = []
    for bp in graph.getBPs():
        bp_to_root_dxs.append(bp.distanceTo(root))
    holding_dic["distances"].extend(bp_to_root_dxs)

    # retrieve branch point angles (remote bifurcation angles)
    bf_angles = TreeStatistics(tree).getRemoteBifAngles()
    holding_dic["angles"].extend(bf_angles)
    

def run():

    if not NeuroMorphoLoader().isDatabaseAvailable():
        print("NeuroMorpho does not seem to be reachable. Are you online!?")
        return

    # define cell names from Neuromorpho.org's Carlen archive (PMID 33593856)
    nm_neuron_names = ["458_4_C1","458_3_C2","500_1_C3","535_1_C2","535_2_C4",
                       "613_3_C4","614_5_C2","614_5_C6","614_6_C3","190131_C2"]

    # define a common dictionary to hold data
    dic = { "angles": [], "distances": [] }

    # iterate through all neuron names
    for neuron_name in nm_neuron_names:
        # load morphology from NeuroMorpho.org
        full_tree = NeuroMorphoLoader.get(neuron_name)
        axon = full_tree.subTree("axon")
        # populate data matrix
        branch_angles_x_path_distances(axon, dic)

    # display the 2D histogram for computed data
    axes_labels = ["Bif. angles (degrees)", "Distance to root (um)", "Freq."]
    SNTChart.showHistogram3D(dic["angles"], dic["distances"], ColorMaps.get("viridis"), axes_labels)

run()
