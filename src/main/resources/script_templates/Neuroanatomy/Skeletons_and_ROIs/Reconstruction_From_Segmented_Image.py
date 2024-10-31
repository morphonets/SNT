#@ String (label="Image", choices={"Current image", "Image from path specified below", "None. Use demo image"}, style="listBox") choice
#@ File (label="Image file", required=false) impFile
#@ boolean (label="Skeletonize image") skeletonizeImp
#@ String(visibility="MESSAGE", value="<html><b>Disconnected Components:",persist=false) divider1
#@ boolean (label="Connect components", description="Whether to connect individual components of the result") connectComponents
#@ Float (label="Max connection distance", description="The maximum allowable distance between the closest pair of points for two components to be merged") maxConnectDist
#@ boolean (label="Prune by length") pruneByLength
#@ Float (label="Length threshold", description="The minimum tree length necessary to avoid pruning") lengthThreshold
#@ String(visibility="MESSAGE", value="<html><b>Options:", persist=false) divider2
#@ boolean (label="Set root from ROI") rootFromROI
#@ boolean (label="Show result") showInViewer
#@ boolean (label="Save result") saveResult
#@ File (label="Output directory", style="directory", required=false) outDir
#@ Context context
#@ SNTService snt

"""
Exemplifies how to convert a binarized skeleton image into a SNT Tree  
"""

import os
from ij import IJ
from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import SkeletonConverter
from sc.fiji.snt.viewer import Viewer3D

# Documentation Resources: https://imagej.net/plugins/snt/scripting
# Latest SNT API: https://javadoc.scijava.org/SNT/


def main():
	global skeletonizeImp
	if choice == "Current image":
		imp = IJ.getImage()
	elif choice == "Image from path specified below":
		imp = IJ.openImage(impFile.getAbsolutePath())
	elif choice == "None. Use demo image":
		imp = snt.demoImage("ddaC")
		skeletonizeImp = True
	# Use the image to create the reconstructions, first skeletonizing 
	# if it is not already a skeleton.
	converter = SkeletonConverter(imp, skeletonizeImp)
	converter.setPruneByLength(pruneByLength)
	converter.setLengthThreshold(lengthThreshold)
	converter.setConnectComponents(connectComponents)
	converter.setMaxConnectDist(maxConnectDist)
	if rootFromROI and imp.getRoi() and imp.getRoi().isArea():
		# root will be set by juntion/end point contained in ROI
		trees = converter.getTrees(imp.getRoi(), False) # roi containing root location, do not restrict ROI to 2D
	else:
		# root will be the starting node of the skeleton
		trees = converter.getTrees()
	if showInViewer:
		# Display generated reconstructions
		viewer = Viewer3D(context)
		Tree.assignUniqueColors(trees)
		viewer.add(trees)
		viewer.show()
	if saveResult:
		# Save each generated tree as an SWC file
		impTitle = imp.getShortTitle() + "-skeleton"
		recDir = os.path.join(outDir.getAbsolutePath(), impTitle)
		if not os.path.isdir(recDir):
			os.mkdir(recDir)
		for idx, t in enumerate(trees):
			swcPath = os.path.join(recDir, impTitle + "-" + str(idx) + ".swc")
			success = "File saved: {}".format(swcPath) if t.saveAsSWC(swcPath) else "I/O Error. File not saved: {}".format(swcPath)
			print(success)


main()
