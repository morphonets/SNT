/**
 * file: Root_Angle_Analysis.groovy
 * info: Runs Root Angle Analysis[1] on DG cells from NeuroMorpho's[2] Beining
 *       archive[3]. To explore the full potential of this type of analysis have 
 *       a look at the RootAngleAnalyzer API[4], and the original publication by
 *       Bird & Cuntz[5]. Internet connection required.
 *       
 *  [1] https://imagej.net/plugins/snt/analysis#root-angle-analysis
 *  [2] Ascoli et al. 2007, https://pubmed.ncbi.nlm.nih.gov/17728438/
 *  [3] Beining et al. 2017, https://pubmed.ncbi.nlm.nih.gov/27514866/
 *      http://cng.gmu.edu:8080/neuroMorpho/MetaDataResult.jsp?count=240&summary={%22neuron%22:{%22archive%22:[%22Beining%22]},%22ageWeightOperators%22:{},%22ageWeightOperations%22:{}}
 *  [4] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/RootAngleAnalyzer.html
 *  [5] Bird & Cuntz 2019, https://pubmed.ncbi.nlm.nih.gov/31167149/
 */

// Define ids for group 1 and 2 (for sake of simplification, here we'll
// list only a small subset of the cells used in the original study)
group1Ids = ["21dpi_contra_infra_01", "21dpi_contra_infra_02", "21dpi_contra_infra_03", "21dpi_contra_infra_04"]
group2Ids = ["Mature_contra_infra_01", "Mature_contra_infra_02", "Mature_contra_infra_03", "Mature_contra_infra_04"]

// download reconstructions from NeuroMorpho.org. A NPE will be
group1Cells = downloadCells(group1Ids)
group2Cells = downloadCells(group2Ids)

// exit if nothing was retrieved
if (!group1Cells || !group2Cells) {
	println("No cells downloaded! Either server is down or you are offline!?")
	return
}

// assemble Root Angle Analyzers for each group. This will call detailStats()
// (see function below), printing root analysis related metrics to console
println("===== Group 1 analysis ====")
group1Analyzers = getAnalyzers(group1Cells)
println("===== Group 2 analysis ====")
group2Analyzers = getAnalyzers(group2Cells)

// display root angle distributions and von Mises fits
[group1Analyzers, group2Analyzers].eachWithIndex { analyzers, index ->
	chart1 = RootAngleAnalyzer.getHistogram(analyzers, true)
	chart2 = RootAngleAnalyzer.getDensityPlot(analyzers)
	chartCombo = SNTChart.combine([chart1, chart2])
	chartCombo.setTitle("Group " + (index+1))
	chartCombo.show()
	taggedTrees = []
	analyzers.each { taggedTrees.add(it.getTaggedTree("Ice.lut")) }
	multiviewer = FigCreatorCmd.render(taggedTrees, "2d vector, montage, zero-origin")
	multiviewer.setTitle("Group " + (index+1))
}
return null // avoid ignoring unsupported RootAngleAnalyzer output


/** Downloads reconstructions from NeuroMorpho.org from a list of cell ids */
def downloadCells(ids) {
	loader = new NeuroMorphoLoader()
	if (!loader.isDatabaseAvailable()) {
		println(" >>>>>> Could not reach NeuroMorpho.org")
		return
	}
	cells = []
	for (id in ids) cells.add(loader.getTree(id))
	return cells
}


/** Assembles a list of Root Angle Analyzers from a list of reconstructions */
def getAnalyzers(cells) {
	analyzers = []
	for (cell in cells) {
		analyzer = new RootAngleAnalyzer(cell)
		detailStats(cell, analyzer)
		analyzers.add(analyzer)
	}
	return analyzers
}


/** Prints Root Angle Analyzer statistics to console */
def detailStats(cell, analyzer) {
	println("${cell.getLabel()}:")
	println(" bf: ${analyzer.balancingFactor()}")
	println(" cb: ${analyzer.centripetalBias()}")
	println(" md: ${analyzer.meanDirection()}")
	println(" cvms: ${analyzer.getCramerVonMisesStatistic()}")
}

// imports below
import sc.fiji.snt.io.*
import sc.fiji.snt.analysis.*
import sc.fiji.snt.gui.cmds.FigCreatorCmd
