#@File (style="directory", required=false, label="Reconstructions directory (Leave empty for demo):") inputdir
#@String (label="Strategy:", choices={"Skeletonization + Dilation + Gaussian smoothing", "Skeletonization + Gaussian smoothing"}) filters
#@Float (label="Sigma (Gaussian blur):", value=30) sigma
#@SNTService snt
#@UIService ui

"""
 Exemplifies how to obtain a density map from a group of neuronal reconstructions.
 TF 20221009
 Documentation Resources: https:#imagej.net/plugins/snt/scripting
 Latest SNT API: https:#javadoc.scijava.org/SNT/
"""

from ij import IJ, ImagePlus
from ij.plugin import ImagesToStack
from sc.fiji.snt import Tree, SNTUtils
from sc.fiji.snt.viewer import Viewer2D

def getskeletons(trees):
    skels = []
    for tree in trees:
        root = tree.getRoot()
        # Align reconstructions: bring roots to a common origin
        tree.translate(-root.getX(), -root.getY(), -root.getZ())
        skels.append(tree.getSkeleton2D())
    return skels


def main():
    global inputdir, filters, sigma

    # Retrive all reconstruction files from the directory
    files = SNTUtils.getReconstructionFiles(inputdir, "")  # folder, filename pattern
    if files and len(files) < 2:
        ui.showDialog("Directory must contain at least two reconstructions.")
        return

    demo = not files # Whether demo data ought to be used
    imps = []  # list holding skeletonized images
    v2d = Viewer2D() # Rec. Plotter instance for displaying input reconstructions

    if demo:
        # Directory is invalid. Retrieve demo data instead
        trees = snt.demoTrees()
        sigma = 30
        # Rotate cells to "straighten up" the apical shaft
        for tree in trees:
            tree.rotate(Tree.Z_AXIS, 15)
        imps = getskeletons(trees)
    else:
        # Directory is valid. NB: Each file may contain 1 or more cells
        for f in files:
            print("Parsing " + str(f))
            trees = Tree.listFromFile(f.getAbsolutePath())
            imps += getskeletons(trees)

    # Place all skeletons in a common image stack
    tmpstack = ImagesToStack().run(imps)

    # Assemble projection
    IJ.run(tmpstack, "Z Project...", "projection=[Max Intensity]")
    result = IJ.getImage()
    result.setTitle("Gaussian Density Map")

    # Apply filter(s) (in place)
    if "Dilation" in filters:
        IJ.run(result, "Dilate", "")
    IJ.run(result, "Gaussian Blur...", "sigma=" + str(sigma) +" stack")

    # Adjust output
    IJ.run(result, "Flip Vertically", "")
    IJ.run(result, "Enhance Contrast", "saturated=0.35")
    IJ.run(result, "16 colors", "")
    IJ.run(result, "3D Surface Plot",
        ("plotType=4 smooth=0 perspective=0.3 colorType=0 drawAxes=1 drawText=1"
        " drawLines=0 scale=1.5 scaleZ=0.5 rotationX=45 windowWidth=%d"
        " windowHeight=%d" % (result.getWidth(), result.getHeight()) ))

    # Dispose temporay image
    tmpstack.close()

    # Display input reconstructions
    v2d.add(trees)
    v2d.setGridlinesVisible(False)
    v2d.setTitle("Original Cells")
    v2d.show(result.getWidth(), result.getHeight())

main()
