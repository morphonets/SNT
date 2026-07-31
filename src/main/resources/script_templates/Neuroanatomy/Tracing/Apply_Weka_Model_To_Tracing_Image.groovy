#@String(value="Please specify Weka model ('.model' file extension): ",visibility="MESSAGE") msg
#@File file
#@SNTService snt
#@UIService ui

snt.requireVersion("5.0.14") // SNT version required to run this script

/**
 *  Applies a pre-existing Weka model to the image being traced. API Resources:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/SNTService.html
 *  https://javadoc.scijava.org/Fiji/index.html?trainableSegmentation/WekaSegmentation.html
 */

// Exit if SNT is not running
if (!snt.isActive() || !snt.getInstance().accessToValidImageData() || file == null || !file.exists()) {
	ui.showDialog("No valid image data is available or the model file is invalid.", "Error")
	return
}

// WekaSegmentation.applyClassifier() classifies the *entire* image in one blocking pass:
// On a large streamed dataset this can mean a very long wait and a large amount of RAM
if (snt.isStreamMode() && !new GuiUtils().getConfirmation(
		"Applying a Weka classifier requires computing the classification for the entire "
		+ "dataset in one pass. Depending on dataset size and available RAM, this can take a "
		+ "long time or exhaust memory. Continue?",
		"Run on Streamed Image?")) {
	return
}

import trainableSegmentation.WekaSegmentation
import sc.fiji.snt.gui.GuiUtils

// Apply model
segmentator = new WekaSegmentation(snt.getInstance().getLoadedDataAsImp())
segmentator.loadClassifier(file.getAbsolutePath())
segmentator.applyClassifier(true)
segmentator.getClassifiedImage().show()
