#@Context context
"""
file:       Graph_Analysis.py
author:     Tiago Ferreira, Cameron Arshadi
version:    20190402
info:       Demonstrates how to handle neurons as graph structures[1] (graph theory)
            in which nodes connected by edges define the morphology of the neuron.
            SNT represents neurons as directed graphs (assuming the root -typically
            the soma- as origin) and allows data be processed using the powerful
            jgrapht[2] library.
            In this demo, the graph diameter[3] (i.e., the length of the longest
            shortest path or the longest graph geodesic) of a cellular compartment
            is computed for a neuron fetched from the MouseLight database, with a 
            performance comparison between SNT and JgraphT algorithms.
            [1] https://en.wikipedia.org/wiki/Graph_theory
            [2] https://jgrapht.org/
            [3] https://mathworld.wolfram.com/GraphDiameter.html
"""

import time

from sc.fiji.snt import Tree
from sc.fiji.snt.io import MouseLightLoader
from sc.fiji.snt.viewer import Viewer3D
from sc.fiji.snt.analysis.graph import DirectedWeightedGraph

from org.jgrapht.alg.shortestpath import DijkstraShortestPath


def graph_diameter(graph, root, tips):
    """Compute graph diameter, which for rooted directed trees
    is the longest shortest path between the root and any terminal node."""
    max_path_length = 0
    longest_shortest_path = None
    for tip in tips:
        # Use SNT's shortest path algorithm
        shortest_path = graph.getShortestPath(root, tip)
        path_length = shortest_path.getLength()
        if path_length > max_path_length:
            max_path_length = path_length
            longest_shortest_path = shortest_path
    return max_path_length, longest_shortest_path


def graph_diameter_dsp(graph, root, tips):
    max_path_length = 0
    longest_shortest_path = None
    for tip in tips:
        # Use JGraphT's Dijkstra implementation
        shortest_path = DijkstraShortestPath(graph).getPath(root, tip)
        path_length = shortest_path.getWeight()
        if path_length > max_path_length:
            max_path_length = path_length
            longest_shortest_path = shortest_path
    return max_path_length, longest_shortest_path


def run():

    # Fetch a neuron from the MouseLight database.
    print("Loading cell...")
    loader = MouseLightLoader("AA0004")

    # Get the SWCPoint representation of each dendritic node, which
    # contains all attributes of a single line in an SWC file.
    print("Extracting dendritic nodes...")
    dendritic_tree = loader.getTree("dendrites")

    # Build a jgrapht Graph object from the Tree nodes and assign
    # euclidean distance between adjacent nodes as edge weights.
    print("Assembling graph from Tree's nodes...")
    graph = dendritic_tree.getGraph()
    
    # When dealing with a relatively low number of vertices (<10k),
    # one can display the graph in SNT's dedicated canvas w/ controls
    # for visualization, navigation and export:
    print("Displaying graph...")
    graph.show()

    # Retrieve the root: the singular node with in-degree 0
    root = graph.getRoot()
    # and the tips: the set of nodes with out-degree 0
    tips = graph.getTips()

    # Compute the longest shortest path using SNT
    t0 = time.time()
    length, path = graph_diameter(graph, root, tips)
    t1 = time.time()
    print("Graph diameter (SNT)=%s. Time: %ss" % (length, t1-t0))

    # Compute the longest shortest path using JGrapht Dijkstra's algorithm,
    # which is slower with larger inputs (i.e., an axon tree)
    t0 = time.time()
    length, _ = graph_diameter_dsp(graph, root, tips)
    t1 = time.time()
    print("Graph diameter (DSP)=%s. Time: %ss" % (length, t1-t0))

    # Visualize the longest path in Viewer3D (interactive instance)
    viewer = Viewer3D(context)

    # Import results as sc.fiji.snt.Tree objects expected by Viewer3D
    dendritic_tree.setColor("cyan")
    viewer.add(dendritic_tree)

    snt_tree_shortest_path = Tree([path])
    snt_tree_shortest_path.setColor("orange")

    # Highlight the shortest path by offsetting it laterally by 10um
    snt_tree_shortest_path.translate(10,10,0)
    viewer.add(snt_tree_shortest_path)
    viewer.show()


run()
