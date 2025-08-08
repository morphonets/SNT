//@UIService ui

/**
 * file: Sholl_Extensive_Stats_Demo.groovy
 * author: Tiago Ferreira
 * version: 20201101
 * info: A demo that illustrates how to use to the SNT's API to fine-tune
 *       Sholl-based statistics from multiple sources
 */

import ij.ImagePlus
import ij.io.Opener
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.sholl.*
import sc.fiji.snt.analysis.sholl.gui.*
import sc.fiji.snt.analysis.sholl.math.*
import sc.fiji.snt.analysis.sholl.parsers.*


// The starting point of a programmatic Sholl analysis is a parser.
// There are parsers for 2D images, 3D images, reconstruction
// files and pre-retrieved Sholl (tabular) data.

// To parse a grayscale/binay image, we use ImageParser2D and
// ImageParser3D, depending on whether the image is 2D or 3D
// (see Sholl_Extract_Profile_From_Image_Demo script for details)
imp = Opener.openUsingBioFormats("path/to/image/file.tif")
if (imp != null) {
    if (imp.getNDimensions() == 2)
        parser = new ImageParser2D(imp)
    else
        parser = new ImageParser3D(imp)
    // we would then set the required options:
    parser.setHemiShells(true)
    parser.setThreshold(100, 250)
    parser.setCenter(10,10,10)
    // (...)
}

// Traced data:
tree = Tree.fromFile("path/to/reconstruction/file.swc")
if (tree != null) {
    parser = new TreeParser(tree)
    parser.setCenter(TreeParser.ROOT_NODES_DENDRITE)
    parser.setStepSize(10) // in physical units, e.g., microns
    // (...)
}

// In this case, we'll just use a CSV table containing demo
// data from the ddaC1 sample image:
table = ShollUtils.csvSample()
parser = new TabularParser(table, "radii_um", "counts")

// Once the parser is defined, we parse the input data
parser.parse()
if (!parser.successful())
    ui.showDialog("Could not parse\n"+ csvFile)

// We will now analyze the data
println "*** Analyzing CSV Demo ***"
profile = parser.getProfile()
lStats = new LinearProfileStats(profile)
plot = new ShollPlot(lStats)
plot.show()

// Determine polynomial of 'best fit'. We'll wait .3s between
// fits in order to animate the iterative fitting plot
rSq_highest = 0
pValue = 1
bestDegree = 1
println "*** Determining Best Fit [Degrees 1-30] ***"
for (degree in 1..30) {
    try {
        lStats.fitPolynomial(degree)
        sleep(300)
        plot.rebuild()
    } catch (Exception e) {
        println "  Could not fit degree ${degree}: ${e.getClass().getName()}"
        continue
    }
    pValue = lStats.getKStestOfFit()
    if (pValue < 0.05) {
        println "  Skipping degree ${degree}: Fitted data significantly different"
        continue
    }
    rSq = lStats.getRSquaredOfFit(true)
    if (rSq > rSq_highest) {
        rSq_highest = rSq
        bestDegree = degree
    }
}
println "  'Best polynomial': " + bestDegree
println "  Rsquared (adj.): " + rSq_highest
println "  p-value (K-S test): " + pValue


// Note that we could have done all this by simply calling findBestFit. It would
// have been way, way faster (but we wouldn't have the animation :)). We use it as:
bestDegree2 = lStats.findBestFit(1, // lowest degree
                            30,     // highest degree
                            0.70,   // lowest value for adjusted RSquared
                            0.05)   // the two-sample K-S p-value used to discard 'unsuitable fits'
println "  The automated 'Best polynomial': " + bestDegree2

// Now we can access all of the Sholl-based metrics for either sampled or fitted data
// Note that 'it' is an implicit variable that is provided in closures, i.e., the current
// value of the [false, true] list:
[false, true].each {
    println "*** Linear Sholl Stats ${it?"Fitted":"Sampled"} Data ***"
    println "  Min: " + lStats.getMin(it)
    println "  Max: " + lStats.getMax(it)
    println "  Mean: " + lStats.getMean(it)
    println "  Median: " + lStats.getMedian(it)
    println "  Sum: " + lStats.getSum(it)
    println "  Variance: " + lStats.getVariance(it)
    println "  Sum squared: " + lStats.getSumSq(it)
    println "  Intersect. radii: " + lStats.getIntersectingRadii(it)
    println "  I branches: " + lStats.getPrimaryBranches(it)
    println "  Ramification index: " + lStats.getRamificationIndex(it)
    println "  Centroid: " + lStats.getCentroid(it)
    println "  Centroid (polygon): " + lStats.getPolygonCentroid(it)
    println "  Enclosing radius: " + lStats.getEnclosingRadius(it, 1)
    println "  Maxima: " + lStats.getMaxima(it)
    println "  Centered maximum: " + lStats.getCenteredMaximum(it)
    println "  Kurtosis: " + lStats.getKurtosis(it)
    println "  Skewness: " + lStats.getSkewness(it)
}

// Determine Sholl decay using area as a normalizer. The choice between
// log-log or semi-log method is automatically made by the program
nStats = new NormalizedProfileStats(profile, ShollStats.AREA)
plot = new ShollPlot(nStats)
plot.show()

println "Chosen method: " + nStats.getMethodDescription()
println "Sholl decay: " + nStats.getShollDecay()
println "Determination ratio: " + nStats.getDeterminationRatio()

// We'll now restrict the linear regression to a subset of percentiles
[[10,90], [20,80], [30,70]].each {
    sleep(500)
    nStats.restrictRegToPercentile(it[0], it[1])
    println "R^2 P[${it[0]},${it[1]}]: " + nStats.getRSquaredOfFit()
    plot.rebuild()
    nStats.resetRegression()
}

// We can now display the input data:
ui.show("ddaCsample.csv", table)
