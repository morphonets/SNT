#@String(value="This script uses the selected paths in Path Manager to train a random forest<br>classifier (a machine learning algorithm for semantic segmentation) aimed at<br>classifing neurite-associated pixels. Classification is performed by <i>Trainable<br>Weka Segmentation</i>.<p><b>Inputs:</b></p><ul><li><b>Pre-traced paths</b>:&nbsp;&nbsp; Used to train neurite classification. If no paths are<br>currently selected, all paths in Path Manager are used<li><b>Background ROIs</b>:&nbsp;&nbsp; Used to train background classification. These must<br>be stored in the ROI Manager. ROIs can be of any type but should not<br>include any neurite signal. If a ROI is not associated with a particular<br>Z-plane (cf. ROI Manager options), it is assumed that it is associated with<br>all the Z-planes in the image</ul><p><b>Options:</b></p><ul><li><b>Output image</b>:&nbsp;&nbsp; If <i>Probabilities</i> (the default), pixels in output image encode<br>likelihood of association to traced neurites; if <i>Labels</i>, output is binary</li><li><b>Output action</b>:&nbsp;&nbsp; If <i>Load as secondary layer</i> (the default), output image<br>becomes SNT's secondary layer to speed up tracing operations. This may<br>only make sense if choice for <i>Output image</i> is <i>Probabilities</i>. If <i>Run auto-<br>tracing</i>, SNT will attempt automated tracing on <i>Output image</i>. This may<br>only make sense if choice for <i>Output image</i> is <i>Labels</i></li><li><b>Save to</b>:&nbsp;&nbsp; If a directory is specified, both output image and classification<br>model are saved. The saved model can be applied to other images and<br>can be refined using the <i>Trainable Weka Segmentation</i> UI</li><li><b>Balance classes</b>:&nbsp;&nbsp; Whether neurite- and background- classifying classes<br>should be given the same importance (default is no balancing)</li>",visibility="MESSAGE") msg
#@String(label="Output image",choices={"Probabilities","Labels"}) outputChoice
#@String(label="Output action",choices={"None","Load as secondary layer","Run auto-tracing"}) actionChoice
#@File(label="Save to",style="directory",required=false) outputDir
#@boolean(label="Balance classes") classBalance
#@IOService io
#@SNTService snt
#@UIService ui

/**
 *  Exemplifies how to train a Weka model using traced paths. API Resources:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/SNTService.html
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/RoiConverter.html
 *  https://javadoc.scijava.org/Fiji/index.html?trainableSegmentation/WekaSegmentation.html
 */
 
// Exit if SNT is not running
if (!snt.isActive() || !snt.getUI()) {
	ui.showDialog("SNT does not appear to be running.", "Error")
	return
}

// Retrieve image being traced and selected path(s). Retrieve all paths in Path
// Manager if no selection exists. NB: Timelapses are not supported at the moment
imp = snt.getInstance().getLoadedDataAsImp()
paths = snt.getUI().getPathManager().getSelectedPaths(true)
if (imp == null || paths.isEmpty()) {
	ui.showDialog("A traceable image and pre-traced path(s) are required.", "Error")
	return
}

// Initialize WekaSegmentation
segmentator = new WekaSegmentation(imp)
segmentator.setDoClassBalance(classBalance)

// I. train classifier for background
roiCounter = 0
for (roi in RoiManager.getRoiManager().getRoisAsArray()) {
	if (roi.getZPosition() > 0) {
		// ROI is associated with a single Z-plane
		segmentator.addExample(0, roi, roi.getZPosition())
	} else for (z in 1..imp.getNSlices()) {
		// ROI is associated with _ALL_ Z-planes
		segmentator.addExample(0, roi, z)
	}
	roiCounter++
}
println("Training examples added to class 1: " + roiCounter)
if (roiCounter==0) {
	snt.getUI().error("Could not extract training data from background ROIs "
	+ "since ROI Manager is empty.<br><br>SNT will now pause: Please create "
	+ "ROIs marking background signal, add them to the ROI Manager, and rerun.")
	snt.getUI().runCommand("Pause SNT")
	return
}

// II. train classifier for foreground
converter = new RoiConverter(paths, imp)
converter.convertPaths()
roiCounter = 0
for (z in 1..imp.getNSlices()) {
	planeRois = converter.getZplaneROIs(z)
	for (roi in planeRois) {
		segmentator.addExample(1, roi, z)
		roiCounter++
	}
}
println("Training examples added to class 2: " + roiCounter)
if (roiCounter==0) {
	snt.getUI().error("Could not extract training examples from Paths.")
	return
}

// III. Train and apply classifier. Retrive result
println("Training classifier. This may take a while...")
segmentator.trainClassifier()
println("Applying classifier. This may take a while...")
result = getResult(segmentator, outputChoice)
if (result == null) {
	println("segmentator result is null!")
	snt.getUI().error("Classification failed.")
	return
}

// IV. Show and save result
result.show()
if (outputDir != null) {
	basename = snt.getInstance().getImagePlus().getTitle()
	save(segmentator, result, outputDir.getAbsolutePath(), basename)
}

// V. Run output action
if (actionChoice.contains("secondary layer")) {
	snt.getInstance().loadSecondaryImage(result)
	snt.getUI().showMessage("P-map image successfully loaded as secondary layer "
		+ " You can now toggle secondary layer tracing by pressing 'L'.",
		result.getTitle() + " Loaded")
} else if (actionChoice.contains("auto-tracing")) {
	// Run Extract Paths from Segmented Image... command
	snt.getUI().runAutotracingWizard(result)
}
println("done")


def getResult(segmentator, choice) {
	switch(choice.toLowerCase()) {
		case "probabilities":
			segmentator.applyClassifier(true)
			result = segmentator.getClassifiedImage()
			if (result != null) {
				// classes are interleaved in stack, remove background class:
				IJ.run(result, "Slice Remover", "first=1 last=" + result.getNSlices() + " increment=2")
				IJ.run(result, "mpl-viridis", "")
				result.setTitle("p-map_trained-paths.tif")
			}
			return result
		case "labels":
			segmentator.applyClassifier(false)
			result = segmentator.getClassifiedImage()
			if (result != null) {
				IJ.run(result, "glasbey", "")
				result.setTitle("labels_trained-paths.tif")
			}
			return result
		default:
			return null
	}
}

def save(segmentator, result, dir, baseFileName) {
	println("Saving to " + dir)
	baseFileName = baseFileName.take(baseFileName.lastIndexOf('.'))
	segmentator.saveClassifier(dir + File.separator + baseFileName + ".model")
	segmentator.saveData(dir + File.separator + baseFileName + ".arff")
	io.save(result, dir + File.separator + baseFileName + "_classified.tif")
}


// imports below
import sc.fiji.snt.analysis.RoiConverter
import trainableSegmentation.WekaSegmentation
import ij.IJ
import ij.plugin.frame.RoiManager
