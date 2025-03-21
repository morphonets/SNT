/**
 * file: Sholl_Bitmap_vs_Tracing_Comparison.groovy
 * info: This script compares Sholl profiles obtained from reconstructions and
 *       their image-based (or 2D) counterparts using the Sholl API:
 *       https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/sholl/package-summary.html
 *       https://javadoc.scijava.org/SNT/sc/fiji/snt/analysis/sholl/parsers/package-summary.html
 *       https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/sholl/math/package-summary.html
 */

// define the dialog prompt using SciJava parameters
#@String(choices={"Cortical pyramidal neuron (dendrites, mouse)","Dentate gyrus granule cell (dendrites, rat)","Olfactory projection (axon, drosophila)"},label="Cell") cellChoice
#@String(choices={"0 (continuous)","5","10","15","20"},value="10",style="radioButtonHorizontal",label="Radius step size (µm):") stepChoice
#@String(choices={"2D Reconstruction vs 2D Bitmap (Fast)","3D Reconstruction vs 3D Bitmap (May take a while w/ small step size)", "3D Reconstruction vs 2D Reconstruction (Fast)"},style="radioButtonVertical",label="Type of Comparison") comparisonChoice
#@String(choices={"Simple skeleton (Ground truth segmentation)","Dilated skeleton (More realistic segmentation)"},style="radioButtonVertical",label="Bitmap rasterization") skelChoice
#@String(choices={"Only plots", "Plots and annotated images","Plots, annotated images, and statistics (printed to console)"},style="radioButtonVertical",label="Output") outputChoice
#@SNTService snt

// extract booleans etc. from the dialog prompt
stepSize = stepChoice.split(" ")[0] as double
dilate = skelChoice.contains("Dilated")
illustrations = outputChoice.contains("images")
stats = outputChoice.contains("statistics")
println("Comparison started")

if (comparisonChoice.startsWith("3D Reconstruction vs 2D Reconstruction")) {

    // retrieve 3D and 2D reconstructions
    tree1 = getTree(cellChoice, false)
    tree2 = getTree(cellChoice, true)
    // obtain profiles
    tracingProfile1 = getTracingProfile(tree1)
    tracingProfile2 = getTracingProfile(tree2)
    // obtain the plots
    getPlot(tracingProfile1).show()
    getPlot(tracingProfile2).show()
    // display color mappings and statistics
    if (illustrations) {
        displayTracingIllustration(tree1, tracingProfile1)
        displayTracingIllustration(tree2, tracingProfile2)
    }
    if (stats)
        compare(tracingProfile1, tracingProfile2)

} else { // bitmap vs reconstruction

    // retrieve the reconstruction and its bitmap counterpart
    twoD = comparisonChoice.contains("2D")
    tree = getTree(cellChoice, twoD)
    imp = getBitmapSkeleton(tree, dilate)
    
    // obtain profiles. This can take several minutes
    // depending on stack size and radius step size
    bitmapProfile = getBitmapProfile(imp, tree)
    tracingProfile = getTracingProfile(tree)
    
    // obtain the plots
    getPlot(bitmapProfile).show()
    getPlot(tracingProfile).show()

    // display color mappings and statistics
    if (illustrations) {
        displayBitmapIllustration(bitmapProfile, imp)
        displayTracingIllustration(tree, tracingProfile)
    }
    if (stats)
        compare(tracingProfile, bitmapProfile)
}
println("All done.")


/**
 * Returns a demo reconstruction (Tree) based on the prompt's 'cell choice'.
 *
 * @param description the description of the SNT demo tree
 * @param twoD a flag indicating whether to eliminate the Z dimension
 * @return the Tree object
 */
def getTree(description, twoD) {
    start = new Date()
    print("  Obtaining demo tree...")
    def tree
    switch (description) {
        case { it.contains('granule') }:
            tree = snt.demoTree("DG")
            tree.setLabel("DG")
            tree.getProperties().put("root-voxel", "194,10,40")
            break
        case { it.contains('axon') }:
            tree = snt.demoTree("OP1")
            tree.setLabel("OP")
            tree.getProperties().put("root-voxel", "4,97,2")
            break
        default:
            tree = snt.demoTree("pyramidal")
            tree.setLabel("PC")
            tree.getProperties().put("root-voxel", "382,782,525")
            break
    }
    if (twoD) tree.scale(1d, 1d, 0) // eliminate Z dimension
    println(" Done in " + TimeCategory.minus(new Date(), start))
    return tree
}

/**
 * Returns a raster skeleton of the specified Tree.
 *
 * @param tree the Tree object to be rasterized as a skeleton
 * @param dilate whether the skeleton should be enlarged (dilated) for realism
 * @return the ImagePlus object representing the raster skeleton
 */
def getBitmapSkeleton(tree, dilate) {
    start = new Date()
    print("  Obtaining bitmap image for ${tree.getLabel()}... ")
    def imp = tree.getSkeleton()
    if (dilate) {
        IJ.run(imp, "Dilate", "stack")
        imp.setTitle("Bitmap (DS) " + tree.getLabel())
    } else {
        imp.setTitle("Bitmap (S) " + tree.getLabel())
    }
    println(" Done in " + TimeCategory.minus(new Date(), start))
    return imp
}

/**
 * Obtains the bitmap profile for the specified Tree, computed by parsing
 * the skeletonized representation of the tree.
 *
 * @param imp the skeletonized image
 * @param tree the original tree providing the center of analysis
 * @return the Sholl profile of the skeletonized image
 */
def getBitmapProfile(imp, tree) {
    start = new Date()
    print("  Obtaining bitmap profile (${stepSize}µm step size)...")
    // extract center (coordinates of tree's root) from tree's properties
    center = (tree.getProperties().get("root-voxel").split(",")).collect { it as int }
    if (!tree.is3D()) center[2] = 1
    def bitmapParser
    // initialize the appropriate parser for the image
    if (imp.getNSlices() > 1) {
        bitmapParser = new ImageParser3D(imp)
        bitmapParser.setSkipSingleVoxels(true)
    } else {
        bitmapParser = new ImageParser2D(imp)
    }
    // set center of analysis in pixels
    bitmapParser.setCenterPx(center[0], center[1], center[2])
    // set start radius, step size, and end radius (physical units)
    bitmapParser.setRadii(0, stepSize, bitmapParser.maxPossibleRadius())
    // parse the image. This takes a while with large 3D stacks!
    bitmapParser.parse()
    println(" Done in " + TimeCategory.minus(new Date(), start))
    return bitmapParser.getProfile()
}

/**
 * Obtains the profile for the specified reconstruction (Tree).
 *
 * @param tree the Tree object to get the reconstruction profile from
 * @return the Sholl profile
 */
def getTracingProfile(tree) {
    start = new Date()
    print("  Obtaining reconstruction profile (${stepSize}µm step size)...")
    def treeParser = new TreeParser(tree)
    treeParser.setCenter(tree.getRoot())
    treeParser.setStepSize(stepSize)
    treeParser.setSkipSomaticSegments(false)
    treeParser.parse()
    println(" Done in " + TimeCategory.minus(new Date(), start))
    return treeParser.getProfile()
}

/**
 * Generates a ShollPlot for the specified profile.
 * The plot display some basic curve fitting.
 *
 * @param profile the ShollProfile object to generate the plot for
 * @return the ShollPlot object representing the plot
 */
def getPlot(profile) {
    start = new Date()
    print("  Obtaining Sholl plot...")
    def lStats = new LinearProfileStats(profile)
    lStats.setDebug(true)
    lStats.findBestFit(2, 30, .7, -1)
    plot = new ShollPlot(lStats)
    println(" Done in " + TimeCategory.minus(new Date(), start))
    return plot
}

/**
 * Displays profile image with annotated ROIs.
 *
 * @param profile the sampled profile
 * @param imp the image parsed
 */
def displayBitmapIllustration(profile, imp) {
    start = new Date()
    print("  Preparing ROIs...")
    so = new ShollOverlay(profile, imp)
    so.setPointsSize("large")
    so.setShellsOpacity(50)
    so.setPointsOpacity(75)
    so.addCenter()
    so.setPointsLUT("Ice.lut", "sholl-count")
    so.setShellsLUT("Ice.lut", "sholl-count")
    so.updateDisplay()
    println(" Done in " + TimeCategory.minus(new Date(), start))
    imp.show()
}

/**
 * Displays a reconstruction color mapped to its Sholl profile
 *
 * @param tree the Tree object to be rendered
 * @param profile the Tree's Sholl profile
 */
def displayTracingIllustration(tree, profile) {
    start = new Date()
    print("  Rendering reconstruction...")
    mapper = new TreeColorMapper()
    colorTable = mapper.getColorTable("Ice.lut")
    mapper.map(tree, profile, colorTable)
    viewer = (tree.is3D()) ? new Viewer3D() : new Viewer2D()
    viewer.add(tree)
    viewer.addColorBarLegend(mapper)
    println(" Done in " + TimeCategory.minus(new Date(), start))
    viewer.show()
}

/**
 * Compares two Sholl profiles using Kolmogorov-Smirnov test statistics
 * and least squares regression.
 *
 * @param profile1  the first profile
 * @param profile2  the second profile
 */
def compare(profile1, profile2) {
    comparator = new Comparator(profile1, profile2)
    println("  Comparing profiles: " + profile1.identifier() + " vs " + profile2.identifier())
    println("    KS-test [1]: " + comparator.getKSTest())
    println("    Regression[2] Pearson's r: " + comparator.regression.getR())
    println("    Regression[2] r-square (coef. of determination): " + comparator.regression.getRSquare())
    println("  [1] p-value associated with the null hypothesis that the two profiles represent samples from the same distribution")
    println("  [2] Least squares regression model between the two profiles")
}

// imports below
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import ij.IJ
import ij.ImagePlus
import sc.fiji.snt.*
import sc.fiji.snt.analysis.*
import sc.fiji.snt.analysis.sholl.*
import sc.fiji.snt.analysis.sholl.gui.*
import sc.fiji.snt.analysis.sholl.parsers.*
import sc.fiji.snt.analysis.sholl.math.*
import sc.fiji.snt.viewer.*
