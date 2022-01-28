#@UIService uiService
from ij import IJ, ImagePlus
from ij.gui import Overlay, Roi
from ij.plugin import ImagesToStack, Straightener

"""
This script straightens the pixels associated with Sholl sampling shells, so
that the signal sampled during Sholl Analysis can be measured in a more
straightforward way. For details, see https://forum.image.sc/t/51087/

The script assumes that the frontmost image is a 2D image analyzed by the Sholl
Analysis plugin (typically a "Sholl Mask image") containing "ROI shells" as 
annotations in the image overlay. Further details:
https://forum.image.sc/t/measuring-distribution-of-object-diameters-in-different-stripes-using-sholl-plugin/51087
"""

def error():
  uiService.showDialog("No 'shell' ROIs found in frontmost image.\
  Make sure image has '2D Sholl Shells' in its overlay.")


shell_width = 10
imp = IJ.getImage()
overlay = imp.getOverlay()
raster_shells = []

if imp and overlay:
  for i in range(overlay.size()):
    roi = overlay.get(i)
    if roi.getName() and "Shell" in roi.getName():
      imp.setRoi(roi)
      IJ.run(imp, "Area to Line", "")
      raster = ImagePlus(roi.getName(), Straightener().straighten(imp, roi, shell_width))
      raster_shells.append(raster)
  if raster_shells:
    holding_imp = ImagesToStack.run(raster_shells)
    IJ.run(holding_imp, "Make Montage...", "columns=1 rows={} scale=1".format(holding_imp.getNSlices()))
    measurable_imp = IJ.getImage()
    measurable_imp.setTitle("Rasterized Shells")
    IJ.setAutoThreshold(measurable_imp, "Default dark")
    IJ.run(measurable_imp, "Analyze Particles...", "  show=[Overlay Masks] display clear overlay")
  else:
    error()
else:
  error()
