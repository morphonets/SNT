# @Context context
# @SNTService snt

"""
Exemplifies how to convert a binarized skeleton image into a SNT Tree  
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.analysis import SkeletonConverter
from sc.fiji.snt.viewer import Viewer3D

# Documentation Resources: https://imagej.net/SNT:_Scripting
# Latest SNT API: https://morphonets.github.io/SNT/


def get_sample_image():
	from ij import IJ
	IJ.run("ddaC Neuron (84K)", "")
	imp = IJ.getImage()
	IJ.run(imp, "Set Scale...", "distance=0 known=0 unit=pixel")
	imp.show()
	IJ.run(imp, "Skeletonize (2D/3D)", "")
	return imp


def main():
	imp = get_sample_image()
	converter = SkeletonConverter(imp)
	trees = converter.getTrees()
	viewer = Viewer3D(context)
	Tree.assignUniqueColors(trees)
	viewer.add(trees)
	viewer.show()


main()
