/**
 * file: Tree_Span_Angle_Analysis.groovy
 * info: This demo script answers the question "What is the overall extending direction of a neuronal arbor?",
 *       and demonstrates how to use SNT to study spatial spread and angular distribution of terminal branches,
 *       and directional bias in neuronal arbors. Using the dendrates of Dentate Gyrus cell, the script computes:
 *       1. The angular distribution of tip-to-soma angles
 *          (i.e., between each tip and the tree's root)
 *       2. The angular distribution of branch-point-to-soma angles
 *          (i.e., between each branch point and the tree's root)
 *       3. The principal orientation axes using PCA
 *       4. Displays all orientations in a 3D scene
 *       Relevant API: https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/PCAnalyzer.html
 */

// ===== SciJava @ Parameters =====
#@SNTService snt


// ===== Main Script =====

// Input: dentate gyrus granule cell from Beining's archive
def tree = snt.demoTree("dg")

// Setup: Retrieve root, branch points, and tips
def root = tree.getRoot() // xell soma
def tStats = new TreeStatistics(tree)
def tips = tStats.getTips()
def bps = tStats.getBranchPoints()

// Retrieve angles between root and tips
def tipAngles = []
for (tip in tips) {
    tipAngles.add(computeAngleBetweenPoints(root, tip))
}

// Retrieve angles between root and branch points
def bpAngles = []
for (bp in bps) {
    bpAngles.add(computeAngleBetweenPoints(root, bp))
}

// Display distribution histograms
def map = ["[Root-tip] angles (°)": tipAngles, "[Root-branch point] angles (°)": bpAngles]
def table = new SNTTable(map) // assemble a table from map
def histogram = SNTChart.getHistogram(table, true) // true = polar histogram
histogram.show("Distributions")


// Now we'll compute extension angles. While doing so, we'll keep
// assembling a 3D scene to visualize the measurements
def viewer = new Viewer3D(true) // true = interactive viewer

// Add reconstruction to viewer
viewer.add(tree)


// 1. Highlight direction from root (soma) to centroid of all tips
def tipsCentroid = SNTPoint.average(tips) // obtain centroid
logExtensionAngles(table, "Vector [Root-Tips Centroid]", root, tipsCentroid) // log measurements

// Add direction to 3D scene as an annotation vector
def tipsCentroidVector = viewer.annotateLine([root, tipsCentroid], "Vector [Root-Tips Centroid]")
tipsCentroidVector.setColor("orange")
tipsCentroidVector.setSize(30f)

// 2. Highlight direction from root (soma) to centroid of all branch points
def bpsCentroid = SNTPoint.average(bps) 
logExtensionAngles(table, "Vector [Root-BPs Centroid]", root, bpsCentroid)
def bpsVector = viewer.annotateLine([root, bpsCentroid], "Vector [Root-BPs Centroid]")
bpsVector.setColor("magenta")
bpsVector.setSize(30f)

// 3. Compute principal orientation axes from all the nodes in the tree, except
// the root, since that is our common reference point. PCA eigenvectors have
// no inherent direction - they can point either way along the axis, so we'll
// ensure that it is pointing away from root
def axes = PCAnalyzer.getPrincipalAxes(tree, true)  // true = exclude root, auto-oriented
axes = PCAnalyzer.orientTowardTips(axes, tree) // see getOrientedPrincipalAxes(axes, root, tipsCentroid)

// Retrieve the % of total variance explained by each principal axis
def variances = PCAnalyzer.getVariancePercentages(axes)

// Define labels and thicknesses so that we can add principal axes
// to the 3D scene vectors scaled to each axis' variance
def axisNames = ["Primary", "Secondary", "Tertiary"]
def minThickness = 10f
def maxThickness = 30f

// Get tree bounding box for scaling for direction vectors
def treeBounds = tree.getBoundingBox()
def treeSize = treeBounds.height()
def axisScale = treeSize * .8f  // Scale axes to 90% of tree's height

// Render and measure each principal axis
axes.eachWithIndex { axis, index ->

    // Scale axis length proportionally to variance percentage
    def variancePercentage = variances[index]
    def scaledLength = axisScale * (variancePercentage / 100.0)
    
    // Create axis' start and end points centered tree's centroid
    def treeCentroid = treeBounds.getCentroid()
    def axisStart = SNTPoint.of(
        treeCentroid.x - axis.x * scaledLength,
        treeCentroid.y - axis.y * scaledLength,
        treeCentroid.z - axis.z * scaledLength
    )
    def axisEnd = SNTPoint.of(
        treeCentroid.x + axis.x * scaledLength,
        treeCentroid.getY() + axis.y * scaledLength,
        treeCentroid.getZ() + axis.z * scaledLength
    )

	// Log axis angle to table - compute as vector from root in axis direction
    def label = axisNames[index] + " axis (" + variancePercentage.round(2) + "%)"
    
    // Create a point in the axis direction from root (axes are already oriented)
    def axisFromRoot = SNTPoint.of(
        root.x + axis.x * axisScale,
        root.y + axis.y * axisScale,
        root.z + axis.z * axisScale
    )
    logExtensionAngles(table, label, root, axisFromRoot)

    // Scale thickness proportional to variance percentage
    def thickness = minThickness + (maxThickness - minThickness) * (variancePercentage / 100.0)
    def axisAnnotation = viewer.annotateLine([axisStart, axisEnd], label)
    axisAnnotation.setColor("cyan")
    axisAnnotation.setSize(thickness)
}

// Highlight other landmarks: Centroid of tips
def tipsCentroidAnnotation = viewer.annotatePoint(tipsCentroid, "Centroid of Tips")
tipsCentroidAnnotation.setColor("green")
tipsCentroidAnnotation.setSize(10f)

// Highlight other landmarks: Centroid of tips
def bpsCentroidAnnotation = viewer.annotatePoint(bpsCentroid, "Centroid of BPs")
bpsCentroidAnnotation.setColor("green")
bpsCentroidAnnotation.setSize(10f)

// Display scene and table with statistical summary. Note how cector [Root-Tips Centroid] 
// and principal axis run almost parallel sharing a similar angle ~200°.
viewer.show()
table.summarize();
table.show("Measurements")


// ===== Helper functions below
/**
 * Computes anges between two points using SNT built-in functions.
 * Angle is reported using navigation/compass convention (90°→N)
 */
def computeAngleBetweenPoints(point1, point2) {
    def path = new Path()
    path.addNode(point1)
    path.addNode(point2)
    return path.getExtensionAngleFromVertical()
}

/**
 * Retrieves the 3D orientation of the vector going from 'fromPoint' to
 * 'toPoint' by computing both the horizontal direction (azimuth) and
 * the vertical inclination (elevation). Angles are reported as seen from
 * the coordinate origin using navigation/compass convention.
 * Results are logged to the table.
 */
def logExtensionAngles(table, header, fromPoint, toPoint) {
    def path = new Path()
    path.addNode(fromPoint)
    path.addNode(toPoint)
    def angles = path.getExtensionAngles3D()
    table.insertRow(header)
    table.appendToLastRow("Azimuth (°)", angles[0])
    table.appendToLastRow("Elevation (°)", angles[1])
}

/** Alternative strategy to reorient principal axes */
def getOrientedPrincipalAxes(axes, root, tipsCentroid) {
	// PCAnalyzer features a built-in method to orient principal axes toward
	// the centroid of tips: PCAnalyzer.orientTowardTips(axes, tree). However,
	// in cases we have already computed the centroid of tips, one can orient
	// the axes with less overhead computation using orientTowardDirection():
	def tipsDirection = [
            tipsCentroid.x() - root.x(),
            tipsCentroid.y() - root.y(),
            tipsCentroid.z() - root.z(),
	]
	return PCAnalyzer.orientTowardDirection(axes, tipsDirection)
}

// ===== Imports below
import sc.fiji.snt.Path
import sc.fiji.snt.analysis.PCAnalyzer
import sc.fiji.snt.analysis.SNTChart
import sc.fiji.snt.analysis.SNTTable
import sc.fiji.snt.analysis.TreeStatistics
import sc.fiji.snt.util.SNTPoint
import sc.fiji.snt.viewer.Viewer3D
