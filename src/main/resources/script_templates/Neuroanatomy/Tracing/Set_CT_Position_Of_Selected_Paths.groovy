#@Integer(label="Channel", persist=false, min=1) channel
#@Integer(label="Frame", persist=false, min=1) frame
#@SNTService snt
#@UIService ui


/** 
 *  Exemplifies how to modify selected paths in the GUI using a script.
 *  API Resources:
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/SNTService.html
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/SNTUI.html
 *  https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/PathManagerUI.html
 */

if (!snt.isActive() || !snt.getUI()) {
	ui.showDialog("This script assumes SNT to be running and its GUI available.", "Error")
	return
}

// Get selected paths (those highlighted in Path Manager)
paths = snt.getSelectedPaths()
if (paths) {
	// Apply chosen channel & frame
	paths.each{ it.setCTposition(channel, frame) }
	// Apply tags (Tag>Image Properties> menu)
	snt.getUI().getPathManager().applyDefaultTags("Traced Channel", "Traced Frame")
} else {
	snt.getUI().error("There are no selected paths.")
}
