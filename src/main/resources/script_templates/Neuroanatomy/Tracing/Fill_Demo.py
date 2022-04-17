#@ DatasetService ds
#@ DisplayService display
#@ DatasetIOService dsio
#@ ConvertService cs
#@ OpService op
#@ SNTService snt

"""
file:    Fill_Demo.py
version: 20211012
author:  Cameron Arshadi
info:    Demonstrates how to fill programatically. More details at
         https://forum.image.sc/t/batch-filling-in-snt/58733/7
"""

from ij import IJ, ImagePlus
from ij.plugin import LutLoader
from net.imagej import Dataset
from net.imglib2.img.display.imagej import ImageJFunctions
from net.imglib2.type.logic import BitType
from net.imglib2.type.numeric.real import FloatType
from net.imglib2.type.numeric.integer import UnsignedByteType
from sc.fiji.snt import Tree, FillConverter
from sc.fiji.snt.tracing import FillerThread
from sc.fiji.snt.tracing.cost import Reciprocal
from sc.fiji.snt.util import ImgUtils

# Documentation Resources: https://imagej.net/plugins/snt/scripting
# Latest SNT API: https://javadoc.scijava.org/SNT/


def copyAxes(dataset, out_dataset):
	# Copy scale and axis metadata to the output.
	# There's probably a better way to do this...
	for d in range(dataset.numDimensions()):
		out_dataset.setAxis(dataset.axis(d), d)


def showGrayMask(dataset, converter):
	# Create an image of the same type and dimension as the input dataset
	output = op.create().img(dataset)
	# Map pixel values at fill node positions between the input and output
	# The input and output need not have the same dimensions, but must be the same type.
	# The only other requirement is that both RandomAccessibles are defined at the positions
	# of the fill nodes
	converter.convert(dataset, output)
	# Convert output Img to another Dataset
	output = ds.create(output)
	# Copy the scale and axis metadata to the output
	copyAxes(dataset, output)
	display.createDisplay("Gray Fill Mask", output)


def showBinaryMask(dataset, converter):
	# The memory efficient BitType is great for a binary mask
	output = op.create().img(dataset, BitType())
	# convertBinary only expects the output RandomAccessible, since it is
	# just setting 1 at fill node positions
	converter.convertBinary(output)
	# Convert output Img to another Dataset
	output = ds.create(output)
	# Copy the scale and axis metadata to the output
	copyAxes(dataset, output)
	display.createDisplay("Binary Fill Mask", output)
	

def showDistanceMap(dataset, converter):
	# The node distance is stored internally as a Double,
	# but we can convert it to Float to display it
	output = op.create().img(dataset, FloatType())
	converter.convertDistance(output)
	# Convert output Img to another Dataset
	output = ds.create(output)
	# Copy the scale and axis metadata to the output
	copyAxes(dataset, output)
	# Convert to ImagePlus so we can add a calibration bar overlay
	distanceImp = cs.convert(output, ImagePlus)
	distanceImp.getProcessor().setColorModel(LutLoader.getLut("fire"))
	# The maximum pixel value is small, likely less than 1.
	# Reset the display range min-max so we can see this narrow band of intensities
	distanceImp.resetDisplayRange()
	# Add a calibration bar to visualize the distance measure
	IJ.run(
		distanceImp, 
		"Calibration Bar...",
		"location=[Upper Right] fill=White label=Black number=20 decimal=4 font=8 zoom=1.2 overlay"
	)
	distanceImp.setTitle("Annotated Distance Map")
	distanceImp.show()


def showLabelMask(dataset, converter):
	# Choose the integer type based on the cardinality of
	# the input list of FillerThreads. If there are less than
	# 256, choose UnsignedByteType. If there are more than 255 but less
	# than 65536, choose UnsignedShortType, etc. 
	# Assigned labels start at 1. 0 is reserved for background.
	output = op.create().img(dataset, UnsignedByteType())
	converter.convertLabels(output)
	# Convert output Img to another Dataset
	output = ds.create(output)
	# Copy the scale and axis metadata to the output
	copyAxes(dataset, output)
	# I'm sure there is an ImageJ2 way of doing this...
	labelImp = cs.convert(output, ImagePlus)
	labelImp.getProcessor().setColorModel(LutLoader.getLut("glasbey_on_dark"))
	labelImp.setTitle("Label Mask")
	labelImp.show()


def main():
	# Drosophila olfactory projection fiber
	# Taken from the Diadem dataset
	# https://diadem.janelia.org/olfactory_projection_fibers_readme.html
	tree = snt.demoTree("OP_1")
	dataset = cs.convert(snt.demoImage("OP_1"), Dataset)
	
	# Assign the scale metadata of the image to the Tree object
	# Otherwise, it won't know the mapping from world coordinates to voxel coordinates
	tree.assignImage(dataset)
	
	# Compute the minimum and maximum pixel intensities in the image.
	# These values are used by the search cost function to rescale 
	# image values to a standardized interval (0.0 - 255.0) prior to 
	# taking the reciprocal, which is used as the cost
	# of moving to that neighboring voxel. This cost is post-multiplied by physical distance.
	# Therefore, it is important to make sure the image is spatially calibrated before processing.
	# You could also try out different costs in the sc.fiji.snt.tracing.cost package.
	# One thing to note is that different costs will require *very* different thresholds, as the
	# distance magnitudes depend on the underlying function of intensity. For example,
	# the costs for OneMinusErf can go as low as 1.0 x 10^-75, so selecting a good threshold
	# is usually easier done interactivly via the GUI.
	min_max = op.stats().minMax(dataset)
	cost = Reciprocal(min_max.getA().getRealDouble(), min_max.getB().getRealDouble())
	
	# Build the filler instance with a manually selected threshold
	threshold = 0.02
	fillers = []
	for path in tree.list():
		filler = FillerThread(dataset, threshold, cost)
		# Every point in the Path will be a seed point for the Dijkstra search. 
		# Alternatively, you could construct a single FillerThread with all the Paths,
		# or for subsets of Paths, etc
		# setSourcePaths() expects a list, so wrap the Path in one
		filler.setSourcePaths([path])
		# Make sure this is set to True, otherwise the process will run until it has explored 
		# every voxel in the input image. This can be slow and eat up memory, especially if the image is large.
		filler.setStopAtThreshold(True)
		# Set this to false to save memory,
		# the extra nodes are only relevant if you want to 
		# resume progress using the same FillerThread instance,
		# which in most cases is unneccessary since the process is relatively quick. 
		filler.setStoreExtraNodes(False)
		# Now run it. This could also be done in a worker thread
		filler.run()
		fillers.append(filler)
	
	# FillConverter accepts a list of (completed) FillerThread instances
	converter = FillConverter(fillers)
	
	showBinaryMask(dataset, converter)
	showGrayMask(dataset, converter)
	showLabelMask(dataset, converter)
	# For some reason, the distance map
	# must be shown last for the scale bar to show correctly??
	showDistanceMap(dataset, converter)


main()
