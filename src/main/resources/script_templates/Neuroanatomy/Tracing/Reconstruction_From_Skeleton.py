#@Context context
#@SNTService snt


"""
Exemplifies how to convert a binarized skeleton image into a SNT's Tree  
"""

from sc.fiji.snt import (Path, PathAndFillManager, SNT, SNTUI, Tree)
from sc.fiji.snt.analysis.graph import DirectedWeightedGraph, SNTPseudograph, SWCWeightedEdge
from sc.fiji.snt.util import (BoundingBox, PointInImage, SNTColor, SWCPoint)
from sc.fiji.snt.viewer import (Annotation3D, OBJMesh, MultiViewer2D, Viewer2D, Viewer3D, GraphViewer)
from sc.fiji.skeletonize3D import Skeletonize3D_
from sc.fiji.analyzeSkeleton import AnalyzeSkeleton_

# Documentation Resources: https://imagej.net/SNT:_Scripting
# Latest SNT API: https://morphonets.github.io/SNT/


def convertToDirected(graph):
	singletons = [v for v in graph.vertexSet() if graph.degreeOf(v) == 0]
	graph.removeAllVertices(singletons)
	if graph.vertexSet().size() == 0:
		return
	r = [v for v in graph.vertexSet() if graph.degreeOf(v) == 1][0]
	stack = []
	stack.append(r)
	visited = set()
	while len(stack) > 0:
		n = stack.pop()
		visited.add(n)
		edges = graph.edgesOf(n)
		for e in edges:
			if e.getSource() == n:
				t = e.getTarget()
			elif e.getTarget() == n:
				t = e.getSource()
			if t in visited: continue
			graph.removeEdge(e)
			graph.addEdge(n, t)
			stack.append(t)

def get_sample_image():
	from ij import IJ
	IJ.run("ddaC Neuron (84K)", "")
	imp = IJ.getImage()
	IJ.run(imp, "Set Scale...", "distance=0 known=0 unit=pixel")
	imp.show()
	IJ.run(imp, "Skeletonize (2D/3D)", "")
	return imp

def get_skeleton_graphs(imp):
	skel = AnalyzeSkeleton_()
	skel.setup("", imp)
	skelResult = skel.run(AnalyzeSkeleton_.SHORTEST_BRANCH, False, True, None, True, False)
	return skelResult.getGraph()

def main():

	imp = get_sample_image()
	skel_graphs = get_skeleton_graphs(imp)
	trees = []
	for g in skel_graphs:
		sntGraph = DirectedWeightedGraph(Tree())
		edgeMap = {}
		for v in g.getVertices():
			p = v.getPoints()[0]
			junc = SWCPoint(0, 0, p.x, p.y, p.z, 0, -1)
			sntGraph.addVertex(junc)
			branches = v.getBranches()
			edgeList = []
			for e in branches:
				if e not in edgeMap:
					sl = []
					slabs = e.getSlabs()
					if len(slabs) == 0: continue
					swcp = SWCPoint(0, 0, slabs[0].x, slabs[0].y, slabs[0].z, 0, -1)
					sntGraph.addVertex(swcp)
					sl.append(swcp)
					for i in range(1, len(slabs)):
						swcp = SWCPoint(0, 0, slabs[i].x, slabs[i].y, slabs[i].z, 0, -1)
						sntGraph.addVertex(swcp)
						sl.append(swcp)
						sntGraph.addEdge(sl[i-1], sl[i])
					edgeMap[e] = sl
				edgeList.append(edgeMap[e])
				
			for e in edgeList:
				d0 = e[0].distanceTo(junc)
				d1 = e[-1].distanceTo(junc)
				if (d1 < d0):
					e.reverse()
				sntGraph.addEdge(junc, e[0])
		
		convertToDirected(sntGraph)
		if sntGraph.vertexSet().size() == 0:
			continue

		trees.append(sntGraph.getTree())

	viewer = Viewer3D(context)
	Tree.assignUniqueColors(trees)
	viewer.add(trees)
	viewer.show()


main()
