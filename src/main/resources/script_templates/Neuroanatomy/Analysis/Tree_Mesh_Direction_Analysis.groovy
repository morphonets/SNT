/**
 * file: Tree_Mesh_Direction_Analysis.groovy
 * info: This demo script answers the question "At what angle do dendrites exit cortical layers?":
 *       It uses SNT built-in functions to compute the overall direction of dendritic arbors, and retrieves
 *       the angle between such direction and the local curvature of the cortical layer at the cell body. It
 *       also compares the overall direction of dendritic arbors to the orientation axes of the cortical layer.
 *       Relevant API: https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/viewer/OBJMesh.html
 */

// ===== SciJava @ Parameters =====
#@SNTService snt // 
#@UIService ui


// ===== Main script =====
def trees = snt.demoTrees() // the cells to be analyzed: MouseLight dendrites registered to the Allen CCF
def mop = AllenUtils.getCompartment("MOp5") // The CCF compartment to be analyzed
def mesh = mop.getMesh() // the 3D mesh defining the contours of the compartment

// The default mesh label is detailed. For simplicity, we'll truncate it to compartments' acronym
mesh.setLabel(mop.acronym())

// For simplicity we'll skip cells outside the compartment
trees.removeAll { tree -> mop != tree.getRoot().getAnnotation() }

// Exit if mesh/cells could not be downloaded
if (!trees || !mesh) {
    ui.showDialog("Data could not be fetched. Make sure you are online")
    return
}

// Load mesh, compute its orientation axes, and all hemisphere-specific data
def meshMeasurements = computePrincipalAxes(mesh)

// Create common viewer to display results holding the studied mesh with its orientation axes highlighlited
def viewer = new Viewer3D(true)
renderMeshAndAxes(viewer, mesh, meshMeasurements)

// Create common table to collect results
def table = new SNTTable()

for (tree in trees) {

    // Add tree to viewer
    viewer.add(tree)

    // Measure tree orientation angles, and their relationship to the mesh surface (local mesh angle)
    def result = analyzeTree(tree, mesh, meshMeasurements)
    
    // Do not proceed if analysis failed somehow
    if (!result.success) continue
    
    // Populate table with this tree's measurements
   	addResultToTable(table, result)

    // Highlight tree's direction vector (scaled to tree height)
    def scaledDirectionEnd = createScaledDirectionVector(result.root, result.directionVector, tree)
    def vectorAnnotation = viewer.annotateLine([result.root, scaledDirectionEnd], "${tree.getLabel()} Direction")
    vectorAnnotation.setColor("magenta")
    vectorAnnotation.setSize(15f)

    // Highlight tree's root
    def rootAnnotation = viewer.annotatePoint(result.root, "${tree.getLabel()} Root")
    rootAnnotation.setColor("red")
    rootAnnotation.setSize(40f)

    // Highlight centroid of distal tips used to compute the tree's overall direction
    def centroidAnnotation = viewer.annotatePoint(result.tipsCentroid, "${tree.getLabel()} Centroid")
    centroidAnnotation.setColor("red")
    centroidAnnotation.setSize(40f)

    // Highlight the cell's 'local mesh direction vector', i.e., the vector
    // summarizing the local curvature of the mesh at the location of the cell's root
    if (result.localMeshDirection) {
        def treeHalfWidth = tree.getBoundingBox().width() / 2
        def localDirectionStart = SNTPoint.of(
                result.root.x - result.localMeshDirection[0] * treeHalfWidth,
                result.root.y - result.localMeshDirection[1] * treeHalfWidth,
                result.root.z - result.localMeshDirection[2] * treeHalfWidth
        )
        def localDirectionEnd = SNTPoint.of(
                result.root.x + result.localMeshDirection[0] * treeHalfWidth,
                result.root.y + result.localMeshDirection[1] * treeHalfWidth,
                result.root.z + result.localMeshDirection[2] * treeHalfWidth
        )
        def localDirectionAnnotation = viewer.annotateLine(
                [localDirectionStart, localDirectionEnd], "${tree.getLabel()} Local Mesh Direction")
        localDirectionAnnotation.setColor("green")
        localDirectionAnnotation.setSize(15f)
    }

}

// Add mesh measurements to table. Display it w/ statistical summary
addMeshMeasurementsToTable(table, mesh, meshMeasurements)
table.fillEmptyCells("")
table.summarize()
table.show("Tree-Mesh Direction Analysis Results")

// Display viewer w/ light background, and zoom-in into the cells location
viewer.setEnableDarkMode(false)
viewer.show()
viewer.zoomTo(trees)
println("Done. Results displayed in table and Reconstruction Viewer")


// ===== functions below =====
/** Analyzes a single tree returning a map of detailed results */
def computePrincipalAxes(mesh) {
    def principalAxesByHemisphere = [:]
    def hemisphereBoxes = [:]
    def variancePercentagesByHemisphere = [:]

    ["left", "right"].each { hemisphere ->
        def axes = mesh.getPrincipalAxes(hemisphere)
        principalAxesByHemisphere[hemisphere] = axes
        hemisphereBoxes[hemisphere] = mesh.getBoundingBox(hemisphere)
        variancePercentagesByHemisphere[hemisphere] = PCAnalyzer.getVariancePercentages(axes)
    }
    return [axes : principalAxesByHemisphere, boxes : hemisphereBoxes, variances: variancePercentagesByHemisphere]
}


/** Analyzes a single tree returning a map (dictionary) of detailed results */
def analyzeTree(tree, mesh, meshMeasurements) {
    try {
        // define the orientation of the cell as the vector between its root and the
        // centroid of its most distal tips (here, at least 300um away from root)
        def root = tree.getRoot()
        def tips = new TreeStatistics(tree).getTips(300)
        def tipsCentroid = SNTPoint.average(tips)
        def hemisphere = AllenUtils.getHemisphere(tree) // either 'left' or 'right'

        // Compute the overall direction of the tree
        def angles = computeDirectionAngles(root, tipsCentroid)
        def directionVector = [
                x: tipsCentroid.x - root.x,
                y: tipsCentroid.y - root.y,
                z: tipsCentroid.z - root.z,
                magnitude: angles.magnitude  // store magnitude to avoid recomputation
        ]

        // Use pre-computed mesh and principal axes (Batch Mesh Processing optimization)
        def principalAxes = meshMeasurements.axes[hemisphere]

        // Find best alignment
        def bestAlignment = null
        if (principalAxes) {
            def alignment = findBestAlignedAxis(directionVector, principalAxes)
            def axisNames = ["Primary", "Secondary", "Tertiary"]
            bestAlignment = [
                    name : axisNames[alignment.index],
                    angle: alignment.angle,
                    index: alignment.index
            ]
        }

        // Local mesh direction analysis
        def localMeshDirection = null
        def curvatureAngle = Double.NaN
        if (mesh) {
            localMeshDirection = mesh.getLocalDirection(root, hemisphere, 100)
            if (localMeshDirection != null) {
                curvatureAngle = mesh.getAngleWithLocalDirection(
                	root, // the point at which to compute the local mesh direction
                	[directionVector.x, directionVector.y, directionVector.z] as double[], // normalized vector
                	hemisphere, // the ipsilateral hemi-half of the mesh to be considered
                	100 // the number of mesh vertices definining the local neighborhood
            	)
            }
        }

        return [
                success           : true,
                tree              : tree,
                root              : root,
                tipsCentroid      : tipsCentroid,
                hemisphere        : hemisphere,
                angles            : angles,
                directionVector   : directionVector,
                mesh              : mesh,
                bestAlignment     : bestAlignment,
                localMeshDirection: localMeshDirection,
                curvatureAngle    : curvatureAngle,
        ]

    } catch (Exception e) {
        return [
                success: false,
                tree   : tree,
                error  : e.getMessage()
        ]
    }
}

/** Computes direction angles between 2 points (root and centroid) */
def computeDirectionAngles(root, centroid) {
    def tempPath = new Path()
    tempPath.addNode(root)
    tempPath.addNode(centroid)
    def angles = tempPath.getExtensionAngles3D()
    if (angles == null) {
        return [azimuth: Double.NaN, elevation: Double.NaN, magnitude: 0.0]
    }
    def magnitude = root.distanceTo(centroid)
    return [azimuth: angles[0], elevation: angles[1], magnitude: magnitude]
}

/** Finds the principal axis most aligned with the tree direction */
def findBestAlignedAxis(directionVector, principalAxes) {
    def minAngle = Double.MAX_VALUE
    def bestAxisIndex = -1
    for (int i = 0; i < principalAxes.length; i++) {
        def angle = principalAxes[i].getAngleWith(directionVector.x, directionVector.y, directionVector.z)
        if (angle < minAngle) {
            minAngle = angle
            bestAxisIndex = i
        }
    }
    return [index: bestAxisIndex, angle: minAngle]
}

def renderMeshAndAxes(viewer, mesh, meshMeasurements) {
    // Add the mesh itself
    viewer.add(mesh)

    ["left", "right"].forEach { hemisphere ->
        // Use hemisphere-specific centroid as origin for principal axes
        def meshCentroid = mesh.getCentroid(hemisphere)
        def axisNames = ["Primary", "Secondary", "Tertiary"]
        // Use pre-computed measurements
        def primaryAxisScale = meshMeasurements.boxes[hemisphere].getDiagonal() / 2
        def variancePercentages = meshMeasurements.variances[hemisphere]
        def minThickness = 10f   // Minimum thickness for visibility
        def maxThickness = 30f  // Maximum thickness
        meshMeasurements.axes[hemisphere].eachWithIndex { axis, index ->
            def variancePercentage = variancePercentages[index]

            // Scale each axis proportionally to its variance percentage
            def axisScale = primaryAxisScale * (variancePercentage / 100.0)

            // Create axis endpoints that extend proportionally to their variance
            def axisStart = SNTPoint.of(
                    meshCentroid.x - axis.x * axisScale,
                    meshCentroid.y - axis.y * axisScale,
                    meshCentroid.z - axis.z * axisScale
            )
            def axisEnd = SNTPoint.of(
                    meshCentroid.x + axis.x * axisScale,
                    meshCentroid.y + axis.y * axisScale,
                    meshCentroid.z + axis.z * axisScale
            )

            // Scale thickness proportional to variance percentage
            def thickness = minThickness + (maxThickness - minThickness) * (variancePercentage / 100.0)
            def axisAnnotation = viewer.annotateLine(
                    [axisStart, axisEnd], "${mesh.label()} ${axisNames[index]} Axis (${hemisphere} hem.)")
            axisAnnotation.setColor("blue")
            axisAnnotation.setSize(thickness as float)
        }
    }
}


/** Appends result data to table (see #analyzeTree(tree, mesh, meshMeasurements)) */
def addResultToTable(table, result) {
    table.appendRow()
    table.appendToLastRow("Tree", result.tree.getLabel())
    if (result.success) {
        table.appendToLastRow("Tree azimuth (°)", result.angles.azimuth)
        table.appendToLastRow("Tree elevation (°)", result.angles.elevation)
        table.appendToLastRow("Local angle w/ ${result.mesh.label()} (°)", result.curvatureAngle)
        table.appendToLastRow("Tree hemisphere", result.hemisphere)
        table.appendToLastRow("${result.mesh.label()} axis most aligned w/ tree", result.bestAlignment.name)
    } else {
        table.appendToLastRow("Analysis Status", "Failed: ${result.error}")
    }
}

/** Appends mesh measurements to table */
def addMeshMeasurementsToTable(table, mesh, meshMeasurements) {

    ["left", "right"].each { hemisphere ->
    	table.appendRow()
    	table.appendToLastRow("Mesh", mesh.label())
        table.appendToLastRow("Hemisphere", hemisphere)
        def axes = meshMeasurements.axes[hemisphere] // principal axes
        def variances = meshMeasurements.variances[hemisphere] // use pre-computed variance percentages
        def axisAngles = axes.collect { axis -> axis.getAngleWith(0,0,1) } // direction of principal axes from 'vertical'
        table.appendToLastRow("I axis angle (°)", axisAngles[0])
        table.appendToLastRow("II axis angle (°)", axisAngles[1])
        table.appendToLastRow("III axis angle (°)", axisAngles[2])
        table.appendToLastRow("I axis var. (%)", variances[0])
        table.appendToLastRow("II var. (%)", variances[1])
        table.appendToLastRow("III var. (%)", variances[2])
    }

}

/** Creates a scaled direction vector endpoint based on tree size */
def createScaledDirectionVector(root, directionVector, Tree tree) {
    def magnitude = directionVector.magnitude // use pre-computed magnitude from directionVector
    if (magnitude == 0) return root
    def scale = tree.getBoundingBox().height() / magnitude  // scale to tree height
    def scaledEnd = SNTPoint.of(
            root.x + directionVector.x * scale,
            root.y + directionVector.y * scale,
            root.z + directionVector.z * scale)
    return scaledEnd
}


// ===== Imports below =====
import sc.fiji.snt.Path
import sc.fiji.snt.SNTService
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.PCAnalyzer
import sc.fiji.snt.analysis.SNTTable
import sc.fiji.snt.analysis.TreeStatistics
import sc.fiji.snt.annotation.AllenUtils
import sc.fiji.snt.util.SNTPoint
import sc.fiji.snt.viewer.Viewer3D