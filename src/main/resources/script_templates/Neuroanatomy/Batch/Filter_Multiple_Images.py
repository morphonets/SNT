# @ImageJ ij
# @String(value="<HTML>This script applies the Tubeness filter to all TIFF images in a directory.<br>Only 2D or 3D grayscale images are supported. Processing log is shown in Console.", visibility="MESSAGE") msg
# @File(label="Directory containing your images", style="directory") input_dir
# @String(label="Consider only filenames containing",description="Clear field for no filtering",value="") name_filter
# @boolean(label="Include subdirectories") recursive
# @String(label="Size of structures to be filtered (multiple of avg. voxel spacing)",description="The filter kernel will be determined from the average voxel spacing") scales
# @LogService log
# @StatusService status
# @UIService uiservice
# @DatasetIOService dio
# @IOService io
# @Context context

"""
file:	   Filter_Multiple_Images.py
author:	 Tiago Ferreira, Cameron Arshadi
version:	20190525
info:	   Bulk filtering of image files using Frangi Vesselness
"""

import os
from java.lang import Runtime
from net.imglib2.type.numeric.real import FloatType
from net.imglib2.img.display.imagej import ImageJFunctions
from net.imagej.axis import Axes
from sc.fiji.snt.filter import Tubeness
from io.scif.img import ImgSaver


def get_image_files(directory, filtering_string, extension):
	"""Returns a list containing the paths of files in the specified
	   directory. The list will only include files with the supplied
	   extension whose filename contains the specified string."""
	files = []
	for (dirpath, dirnames, filenames) in os.walk(directory):
		for f in filenames:
			if os.path.basename(f).startswith('.'):
				continue
			if filtering_string in f and f.lower().endswith(extension):
				files.append(os.path.join(dirpath, f))
		if not recursive:
			break  # do not process subdirectories

	return files


def run():
	# First check that scale parameters are > 0, exiting if not
	global scales
	scales = [float(s) for s in scales.split(',')]
	if not scales or any(s <= 0 for s in scales):
		log.error('Please select values > 0 for the scale parameter. Exiting...')
		return

	# Get all files with specified name filter and extension in the input directory
	d = str(input_dir)
	extension = ".tif"
	files = get_image_files(d, name_filter, extension)
	if not files or len(files) == 0:
		uiservice.showDialog("No files matched the specified criteria", "Error")
		return

	processed = 0
	skipped = 0
	for f in files:

		basename = os.path.basename(f)
		msg = 'Processing file %s: %s...' % (processed + skipped + 1, basename)
		status.showStatus(msg)

		# Load the input image
		input_image = dio.open(f)

		# Verify that the image is 2D/3D and grayscale, skipping it if not
		num_dimensions = input_image.numDimensions()
		if num_dimensions > 3 or input_image.getChannels() > 1:
			log.error('Could not process %s...Only 2D/3D grayscale images are supported' % basename)
			skipped += 1
			continue

		# Obtain spatial calibration of the image
		x_spacing = input_image.averageScale(0)
		y_spacing = input_image.averageScale(1)
		spacing = [x_spacing, y_spacing]

		if num_dimensions == 3 and input_image.axis(2).type() == Axes.Z:
			z_spacing = input_image.averageScale(2)
			spacing.append(z_spacing)

		avgsep = sum(spacing) / len(spacing)
		scales = [s * avgsep for s in scales]
		# Create placeholder image for the output then run the Frangi Vesselness op
		output = ij.op().create().img(input_image, FloatType())
		# Pass scales in physical units (e.g., um)
		op = Tubeness(scales, spacing, Runtime.getRuntime().availableProcessors())
		op.compute(input_image, output)

		# Save the result using the same basename as the image, adding "[Frangi].tif"
		# For example, the output for "OP_1.tif" would be named "OP_1[Frangi].tif"
		l = len(f)
		el = len(extension)
		output_filepath = f[0:l - el] + "[Tubeness].tif"
		saver = ImgSaver(context)
		saver.saveImg(output_filepath, output)

		processed += 1

	print('Done. %s file(s) processed. %s file(s) skipped...' % (processed, skipped))


run()
