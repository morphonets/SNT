#@ File (label="Reconstruction file (Leave empty for demo)", style="file", required=false) recFile
#@ Float (label="Area per point (spatially calibrated units)^2", value=100) boxArea
#@ String (label="Color mapping:", choices={"mpl-viridis.lut", "mpl-plasma.lut", "mpl-inferno.lut", "cool.lut", "Cyan Hot.lut"}) lutName
#@ boolean (label="Center grid on image") centerGrid
#@ SNTService snt
#@ RoiManager roiManager
#@ ResultsTable resultsTable

# Documentation Resources: https://imagej.net/SNT:_Scripting
# Latest SNT API: https://morphonets.github.io/SNT/

import math
from ij import IJ
from ij.gui import NewImage
from ij.plugin import LutLoader
from sc.fiji.snt import Tree


boxW = boxH = math.sqrt(boxArea)

# Retrieve a 2D projection of the rasterized skeleton of the reconstruction
# as an 8-bit binary image (skeleton: 255, background: 0)
if recFile is not None:
	imp = Tree(recFile.getAbsolutePath()).getSkeleton2D()
else :
	# Use dendrites of a mouse SSp-m5 neuron
	# AA0004 in the MouseLight database 
	# https://ml-neuronbrowser.janelia.org/
	imp = snt.demoTrees().get(0).getSkeleton2D()

imp.show()

# Get total sum of pixel intensities over the image
totalIntDen = IJ.getValue(imp, "RawIntDen")

# Create grid overlay using point ROIs, with each grid box having area == boxArea
if centerGrid:
	IJ.run(imp, "Grid...", "grid=Points area=" + str(boxArea) + " center")
else:
	IJ.run(imp, "Grid...", "grid=Points area=" + str(boxArea))

# Get the composite grid ROI from the image overlay and
# add it to the RoiManager
overlay = imp.getOverlay()
roiManager.reset()  # clear any existing rois
roiManager.addRoi(overlay.get(0)) # add the single composite grid ROI to RoiManager
roiManager.select(0)  # select this ROI
roiManager.runCommand("Split")  # split into individual point ROIs
roiManager.runCommand("Delete") # delete selected composite ROI, leaving the point ROIs
imp.setOverlay(None)  # clear overlay from source image

# Collect xy coordinates of the grid box corner points
xs = []
ys = []
for roi in roiManager:
	centroid = roi.getContourCentroid()
	xs.append(int(round(centroid[0])))
	ys.append(int(round(centroid[1])))
xMax = max(xs)
yMax = max(ys)

# Now make the rectangular ROIs using the corner points
roiManager.reset()  # clear existing point ROIs
for x, y in zip(xs, ys):
	if x >= xMax or y >= yMax:
		# Skip boundary points
		continue
	IJ.makeRectangle(x, y, boxW, boxH)
	roiManager.runCommand("Add")
	
# Measure integrated density for each ROI
IJ.run("Set Measurements...", "integrated")
resultsTable.reset()  # clear any existing data
roiManager.runCommand("Select All")
roiManager.runCommand("Measure")
colIdx = resultsTable.getColumnIndex("RawIntDen")
# Base the max pixel intensity (255) on the maximum encountered bin probability
maxProb = max(resultsTable.getColumn(colIdx)) / totalIntDen

# Create image with same dimensions as source image
newImp = NewImage.createImage(
	"{} Density Map".format(imp.getTitle()), 
	imp.getWidth(), 							
	imp.getHeight(), 								
	0, 												
	8, 												
	NewImage.FILL_BLACK
	)
# Use viridis color palette to paint the density map
lutPath = IJ.getDirectory("luts") + lutName
lut = LutLoader.openLut(lutPath)
ip = newImp.getProcessor()
ip.setLut(lut)

# Fill in ROIs on the new image using bin probabilities
row = 0
for roi in roiManager:
	localProb = resultsTable.getValue("RawIntDen", row) / totalIntDen
	# Map the bin probability onto the interval [0, 255]
	pixelValue = (255 / maxProb) * localProb
	ip.setColor(pixelValue)
	ip.fill(roi)
	row += 1
	
newImp.setOverlay(overlay)  # show grid on new image
newImp.show()
