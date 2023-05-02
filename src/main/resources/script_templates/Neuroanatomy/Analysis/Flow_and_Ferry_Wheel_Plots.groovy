import sc.fiji.snt.annotation.*
import sc.fiji.snt.analysis.*
import sc.fiji.snt.analysis.graph.*
import sc.fiji.snt.io.*
import sc.fiji.snt.viewer.*
import sc.fiji.snt.viewer.geditor.*

/**
 * file:	Flow_and_Ferry_Wheel_Plots.groovy
 * author:	Tiago Ferreira
 * version:	20230425
 * info:	Exemplifies how to use Flow Plots (Sankey diagrams, implemented in
 * 			SNT v4.1.16) and ferris-wheel plots to summarize projection patterns
 * 			across brain regions for groups of (MouseLight) cells.
 *			Requires internet connection.
 */

// refine neuronal groups from cell IDs:
MY_IDs = ['AA0011', 'AA0012', 'AA0115', 'AA0179', 'AA0180', 'AA0181']
TH_IDS = ['AA0039', 'AA0101', 'AA0103', 'AA0105', 'AA0188', 'AA0278']

// retrieve axons from the MouseLight database and store them in a map:
map = ["MY Proj.":getAxons(MY_IDs), "TH Proj.": getAxons(TH_IDS)]

// define the morphometric surrogate of innervation at target areas
// (Cable length, No. of tips, etc.):
metric = "Cable length"

// initialize a TreeStatistic instance aware of cell populations:
stats = new GroupedTreeStatistics()
map.each { label, neurons ->
	stats.addGroup(neurons, label) // add each group to the instance
}

// Retrieve a flow plot. There are several ways to do so. Here, we'll retrieve
// a flow plot for all mid-ontology brain areas (ontology level 6), using
// normalized measurements (i.e., lengths normalized to the cell's total cable
// length). To simplify the plot, we'll only list regions associated with a
// 'cut-off' of at least 10%: Brain regions associated with less than 10% of a
// a cell's cable length will not be included:
stats.getFlowPlot(metric, 6, 0.1, true).show() // metric, ontology level, min cutoff, normalize measurement?

// We can also retrieve a plot for ad-hoc lists of target areas. Here, we will
// use absolute (not normalized) measurements:
selectedAreas = getCompartments(["MOs", "ANT", "LAT", "MED", "VENT", "CP", "STR", "MY"])
stats.getFlowPlot(metric, selectedAreas, false).show()

// To complement the visualization, we can also retrieve a box-lot of the data:
stats.getBoxPlot(metric, 6, 0.1, true).show()

// ...and a Ferris-wheel diagram of the same data. This is slightly more
// involved and requires calling dedicated classes:
map.each { groupName, neurons ->
   annotationGraph = new AnnotationGraph(neurons, selectedAreas, metric) // list of cells, list of brain areas, metric
   //annotationGraph = new AnnotationGraph(neurons, metric, 0.1, 6) // list of cells, metric, min cutoff, ontology level
   prettifyAndDisplay(annotationGraph, "Ferris Wheel: " + groupName)
}

// NB: It is worth noticing that we can run similar analyses for single-cells,
// or a single-group of cells. The approach is similar once the appropriate
// TreeStatistics instance as been initialized:
singleAxon = map["MY Proj."].get(0)
stats = new TreeStatistics(singleAxon)
stats.getFlowPlot(metric, 6, 0.1, true).show() // single cell plot

groupOfAxons = map["MY Proj."]
stats = new MultiTreeStatistics(groupOfAxons)
stats.getFlowPlot(metric, 6, 0.1, true).show() // single group plot

// Arrange plots on screen:
SNTChart.tileAll()

// Supporting methods below

/* Retrieves the axonal arbors from the specified MouseLight neuronal ids */
def getAxons(ids) {
	axons = []
	for(id in ids)
		axons.add(new MouseLightLoader(id).getTree('axon'))
	axons
}

/* Retrieves CCFv3 annotations from a list of acronyms/names */
def getCompartments(names) {
	compartments = []
	for(name in names)
		compartments.add(AllenUtils.getCompartment(name))
	compartments
}

/* Customizes and displays an annotation graph */
def prettifyAndDisplay(graph, title) {

	// Color code edges: This is equivalent to running Graph Viewer's
	// Analyze>Color Coding... command once the graph is displayed
	mapper = new GraphColorMapper()
	mapper.map(graph, GraphColorMapper.EDGE_WEIGHT, "Ice")

	// Initialize Graph viewer and access its 'under-the-hood' editor
	viewer = new GraphViewer(graph)
	editor = viewer.getEditor()

	// Run Graph Viewer's Analyze>Scale Edge Widths... command
	graphAdapter = editor.getGraphComponent().getGraph()
	graphAdapter.scaleEdgeWidths(1, 10, "linear") // linear scaling of edge thickness from 1 to 10px

	// Run Graph Viewer's Diagram>Layout>Circle (Grouped)... command
	groupedLayout = new mxCircleLayoutGrouped(graphAdapter, 7, 4)  // ontology levels for sorting outside nodes: mid & top levels
	groupedLayout.setRadius(200) // radius of circle
	groupedLayout.setCenterSource(true) // place location of somas in the center?
	groupedLayout.setSortMidLevel(true) // enable mid-level sorting?
	editor.applyLayout(groupedLayout)

	// display
	mapper.setLegend(editor)
	viewer.show(title)
}
