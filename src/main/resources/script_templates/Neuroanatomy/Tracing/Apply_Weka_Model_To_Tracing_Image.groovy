#@String(value="Please specify Weka model ('.model' file extension): ",visibility="MESSAGE") msg
#@File file
#@SNTService snt
#@UIService ui

/**
 *  Applies a pre-existing Weka model to image being traced. API Resources:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/SNTService.html
 *  https://javadoc.scijava.org/Fiji/index.html?trainableSegmentation/WekaSegmentation.html
 */

// Exit if SNT is not running
if (!snt.isActive() || !snt.getInstance().accessToValidImageData() || file == null || !file.exists()) {
	ui.showDialog("No valid image data is available or the model file is invalid.", "Error")
	return
}

import trainableSegmentation.WekaSegmentation

// Apply model
segmentator = new WekaSegmentation(snt.getInstance().getImagePlus())
segmentator.loadClassifier(file.getAbsolutePath())
segmentator.applyClassifier(true)
segmentator.getClassifiedImage().show()
