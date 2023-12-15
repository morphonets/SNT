#@SNTService sntService

import sc.fiji.snt.*
import sc.fiji.snt.analysis.*
import ij.IJ

/**
 * file: Fully_Automated_Tracing_Timelapse_Demo.groovy
 * info: Automates reconstruction of a thresholded timelapse stack, as discussed
 *       in https://forum.image.sc/t/snt-time-lapse-utilites/47974/4
 *       Requires internet access and SNTv4.3.0 or later. Assumes SNT is closed
 *       or contains no paths
 * rev:  20231215
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

	// Assemble a converter[1] for extraction of paths from this frame
	// [1] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/SkeletonConverter.html
	converter = new SkeletonConverter(imp, frame) // image and frame to be parsed
	converter.setPruneByLength(false) // Do not prune away small fragments
	converter.setConnectComponents(true) // Merge any broken components..
	converter.setMaxConnectDist(5) //.. within 5um of each other
	
	// Retrieve the list of structure(s) that have been extracted as trees (i.e.,
	// collection of Paths). For this particular image we expect to extract only
	// a single tree. We need to specify the ROI enclosing the root of the
	// structure, i.e., the soma of the cell. This demo image already contains 
	// a soma-enclosing ROI
	trees = converter.getTrees(imp.getRoi(), false)

	// Now we only need to add the result to PathAndFillManager the SNT component
	// responsible for keeping track of all tracings:
	trees.each{ tree ->
		pafm.addTree(tree, "Autotraced_frame" + frame)
	}

}

// Path extraction concluded. Let's animate the timelapse..
IJ.doCommand("Start Animation [\\]")

// .. Ensuring only frame-relevant tracings are displayed during the animation.
// We can use direct calls from SNT's Script Recorder to toggle the visibility
// filters in SNT's main dialog:
snt.getUI().setVisibilityFilter("selected", false)
snt.getUI().setVisibilityFilter("channel/frame", true)

// We can also tag each path with its frame, and run the Time-lapse Utilities>
// Match Paths Across Time... under default options, which for this test video
// are likely to work without adjustments
snt.getUI().getPathManager().applyDefaultTags("Traced Frame")
snt.getUI().getPathManager().runCommand("Match Paths Across Time...")

println("Done. You should now be able to obtain time profiles for common metrics.")
