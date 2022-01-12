#@String(value="For a demo leave the field below empty.", visibility="MESSAGE") msg
#@File(label="Path to reconstruction file:", required=false) recFile
#@OpService ops
#@SNTService snt

/**
 * file: Sholl_Convex_Hull_As_Center.groovy
 * author: Tiago Ferreira
 * version: 20220111
 * info: A demo which illustrates how to perform Sholl using the centroid
 *       of the arbor's convex hull as center of analysis
 */

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.*
import sc.fiji.snt.analysis.sholl.*
import sc.fiji.snt.analysis.sholl.math.*
import sc.fiji.snt.analysis.sholl.parsers.*
import sc.fiji.snt.viewer.*
import net.imagej.display.*


// First we retrieve a reconstruction (a 'Tree') from the path of the input file
// declared above as a 'script parameter'. If the file is not valid, we'll use a
// demo file distributed with SNT. Note that you could use the 'Batch' button of,
// the Script Editor (next to 'Run'), which would allow you to apply this scipt
// to multiple files!
if (recFile)
    tree = Tree.fromFile(recFile.getAbsolutePath())
else
    tree = snt.demoTree("pyramidal")

// The next step is to initialize the Sholl parser that will parse the Tree
parser = new TreeParser(tree)

// Rather than using an existing node in the arbor as focal point, we'll center
// the analysis on the centroid of its convex hull. This is more relevant for
// radial arbors, but here we'll proceed with the loaded pyramidal neuron. 
// We'll compute tne convex hull, i.e., a mesh if a 3D reconstruction, or a
// polygon if 2D, and set the Sholl center to the centroid of the convex hull
hull2D = new ConvexHull2D(tree.getNodes(), true)
hull2D.compute()
centroid2D = ops.geom().centroid(hull2D.getPolygon())
if (tree.is3D()) {
    hull3D = new ConvexHull3D(tree.getNodes(), true)
    hull3D.compute()
    centroid3D = ops.geom().centroid(hull3D.getMesh())
    parser.setCenter(centroid3D.positionAsDoubleArray())
} else {
    parser.setCenter(centroid2D.positionAsDoubleArray())
}

// We can now set all relevant sampling options an parse reconstruction. See the
// Sholl_Extensive_Stats_Demo script for details and further options
parser.setStepSize(0)
parser.parse()

// We then obtain the Sholl profile and instantiate 'linear profile' statistics 
// Again, see Sholl_Extensive_Stats_Demo script for details
profile = parser.getProfile()
stats = new LinearProfileStats(profile)

// Now we are ready to visualize the analysis. We'll display everything in
// Viewer2D (Reconstruction Plotter). When dealing with 3D reconstructions, it
// makes more sense to use Viewer3D (Reconstruction Viewer), but this will
// exemplifies how to create a publication-quality figure
viewer = new Viewer2D(snt.getContext())
viewer.addPolygon(hull2D.getPolygon(), "Convex hull")

// Color code the tree according to the Sholl profile (i.e., using intersection
// counts as mapping variable)
mapper = new TreeColorMapper(snt.context())
mapper.map(tree, stats, ColorTables.ICE)
viewer.add(tree)
viewer.addColorBarLegend(mapper)

// Highlight center
viewer.getChart().setFontSize(25)
viewer.getChart().annotatePoint(centroid2D.positionAsDoubleArray(), "Center", "black")
viewer.show()

// We could proceed with all sort of analysis. Let's just display the profile
// and exit. Again, see Sholl_Extensive_Stats_Demo for more details
bestDegree = stats.findBestFit(1, 30, 0.75, -1)
if (bestDegree > -1)
    stats.fitPolynomial(bestDegree)
stats.plot().show()
print("All done!")
