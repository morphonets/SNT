#@SNTService sntService

import sc.fiji.snt.*
import ij.IJ
import sc.fiji.snt.tracing.auto.BinaryTracer

/**
 * file: Fully_Automated_Tracing_Timelapse_Demo.groovy
 * info: Automates reconstruction of a thresholded timelapse stack, as discussed
 *       in https://forum.image.sc/t/snt-time-lapse-utilites/47974/4
 *       Requires internet access and SNTv4.3.0 or later. Assumes SNT is closed
 *       or contains no paths
 * rev:  20231219
 */

// initialize the tracing interface. Typically one would use a path to a local
// image, but here we will use "demo: binary timelapse" to signal the service
// that we want to download a demo image: the small (4-frame) binarized image
// sequence described in the forum post. NB: An exception will be thrown if
// image cannot be downloaded
sntService.initialize("demo: binary timelapse", true) // image path, display GUI?

// Retrieve references to the SNT plugin, its path manager, and loaded image.
// We'll need these later on
snt = sntService.getInstance()
pafm = sntService.getPathAndFillManager()
imp = snt.getImagePlus()

// Iterate over the single frames in the timelapse
(1..imp.getNFrames()).each { frame ->
	println("Processing frame #" + frame)

	// Assemble a converter for extraction of paths from this frame. API:
	// https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/SkeletonConverter.html
	converter = new BinaryTracer(imp, frame) // image and frame to be parsed
	converter.setPruneByLength(false) // Don't ignore small components...
	converter.setConnectComponents(false) // Don't merge neighboring components..
	converter.setMaxConnectDist(2) //.. within 2um of each other

	// We need to specify the ROI enclosing the root of the structure, i.e.,
	// the soma of the cell. This image already contains a soma-enclosing ROI,
	// so we'll set it so that neurites branch out from its centroid. Since ROI
	// does not reflect an accurate contour of the soma, we'll use the weighted
	// centroid option to better reflect the mostly likely outgrowth center
	converter.setRootRoi(imp.getRoi(), converter.ROI_CENTROID_WEIGHTED)

	// Since the image contains only a single cell and we've imposed all primary
	// neurites to branch out from from a common center, we can retrieve the
	// conversion result as a single reconstructed Tree
	tree = converter.getSingleTree()

	// Now we only need to add the result to PathAndFillManager, the SNT
	// class responsible for keeping track of all traced Paths
	pafm.addTree(tree, "Autotraced_frame" + frame)

}

// .. Ensuring only frame-relevant tracings are displayed during the animation.
// We can also increase the display thickeness of paths for better contrast.
// We can use direct calls from SNT's Script Recorder to toggle the visibility
// filters in SNT's main dialog:
snt.getUI().setVisibilityFilter("selected", false)
snt.getUI().setVisibilityFilter("channel/frame", true)
snt.getUI().setRenderingScale(6)

// Finally, we can tag each path with its frame, run the Time-lapse Utilities>
// Match Paths Across Time... command under default options, which for this
// test video matches neurites across time quite well without adjustments
snt.getUI().getPathManager().applyDefaultTags("Traced Frame")
snt.getUI().getPathManager().runCommand("Match Paths Across Time...")

println("Done. You should now be able to run 'Growth Analysis...' or 'Time Profile...'")
// Path extraction concluded. Let's animate the timelapse..
IJ.run(imp, "Animation Options...", "speed=6 first=1 last=4 loop start")
