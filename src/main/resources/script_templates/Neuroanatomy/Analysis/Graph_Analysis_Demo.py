"""
file:       Graph_Analysis_Demo.py
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


def graph_diameter_dsp(graph, root, tips):
    dsp = DijkstraShortestPath(graph)
    max_path_length = 0
    longest_shortest_path = None
    for tip in tips:
        # Use JGraphT's Dijkstra implementation
        shortest_path = dsp.getPath(root, tip)
        path_length = shortest_path.getWeight()
        if path_length > max_path_length:
            max_path_length = path_length
            longest_shortest_path = shortest_path
    return max_path_length, longest_shortest_path


def run():

    # Fetch a neuron from the MouseLight database.
    print("Loading cell...")
    loader = MouseLightLoader("AA0012")

    # Get the SWCPoint representation of each axonal node, which
    # contains all attributes of a single line in an SWC file.
    print("Extracting axon nodes...")
    axon_tree = loader.getTree("axon")

    # Build a DirectedWeightedGraph object from the Tree nodes, assigning
    # euclidean distance between adjacent nodes as edge weights.
    # See https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/graph/DirectedWeightedGraph.html
    print("Assembling graph from Tree's nodes...")
    graph = axon_tree.getGraph()
    
    # When dealing with a relatively low number of vertices (<10k),
    # one can display the full graph in SNT's dedicated canvas w/ controls
    # for visualization, navigation and export. 
    # If the graph contains a very large number of vertices, 
    # it is possible to obtain a simplified graph representation consisting 
    # of the root, branch points and terminals while retaining the global 
    # topology of their connections:
    print("Displaying simplified graph...")
    graph.getSimplifiedGraph().show()

    # Retrieve the root: the singular node with in-degree 0
    root = graph.getRoot()
    # and the tips: the set of nodes with out-degree 0
    tips = graph.getTips()

    # Compute the longest shortest path using SNT
    t0 = time.time()
    path = graph.getLongestPath(True)
    # Whether to treat the graph as directed (True) or undirected (False).
    # If True, the longest shortest path will always include the tree root
    # and some terminal node.
    # If False, it may occur between any pair of terminal nodes (including the root).
    # It is also possible to compute arbitrary shortest paths using
    # graph.getShortestPath(vertex1, vertex2)
    t1 = time.time()
    print("Graph diameter (SNT)=%s. Time: %ss" % (path.getLength(), t1-t0))

    # Compute the longest shortest path using JGrapht Dijkstra's algorithm,
    # which is slower with larger inputs (i.e., an axon tree)
    t0 = time.time()
    length, _ = graph_diameter_dsp(graph, root, tips)
    t1 = time.time()
    print("Graph diameter (DSP)=%s. Time: %ss" % (length, t1-t0))

    # Visualize the longest path in Viewer3D (interactive instance)
    viewer = Viewer3D(True) # true = interactive

    # Import results as sc.fiji.snt.Tree objects expected by Viewer3D
    axon_tree.setColor("cyan")
    viewer.add(axon_tree)

    snt_tree_shortest_path = Tree([path])
    snt_tree_shortest_path.setColor("orange")
    snt_tree_shortest_path.setLabel("AA0012 (axon): longest shortest path")
    viewer.add(snt_tree_shortest_path)

    # We can obtain the mesh of the Allen CCF compartment containing the axon root
    # as well as the compartment innervated by the terminal node of the longest path
    soma_mesh = axon_tree.getRoot().getAnnotation().getMesh()
    terminal_mesh = path.getNode(path.size()-1).getAnnotation().getMesh()
    viewer.loadRefBrain("mouse")
    viewer.add([soma_mesh, terminal_mesh])

    # Highlight the shortest path by increasing its radius
    viewer.setTreeThickness([axon_tree.getLabel()], 1, None)
    viewer.setTreeThickness([snt_tree_shortest_path.getLabel()], 3, None)
    viewer.show()


run()
