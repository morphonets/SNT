# @ImageJ ij
# @String(value="<HTML>This script applies the Frangi Vesselness filter to all TIFF images in a directory.<br>Only 2D or 3D grayscale images are supported. Processing log is shown in Console.", visibility="MESSAGE") msg
# @File(label="Directory containing your images", style="directory") input_dir
# @String(label="Consider only filenames containing",description="Clear field for no filtering",value="") name_filter
# @boolean(label="Include subdirectories") recursive
# @float(label="Size of structures to be filtered (spatially calibrated units)",description="The filter kernel will be determined from the average voxel size") scale
# @LogService log
# @StatusService status
# @UIService uiservice

"""
file:       Filter_Multiple_Images.py
author:     Tiago Ferreira, Cameron Arshadi
version:    20190525
info:       Bulk filtering of image files using Frangi Vesselness
"""

from net.imagej.axis import Axes

import os


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
    # First check that scale parameter is > 0, exiting if not
    if scale <= 0:
        log.error('Please select a value > 0 for the scale parameter. Exiting...')
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
        print(msg)

        # Load the input image
        input_image = ij.io().open(f)

        # Verify that the image is 2D/3D and grayscale, skipping it if not
        num_dimensions = input_image.numDimensions()
        if num_dimensions > 3 or int(input_image.getChannels()) > 1:
            log.error('Could not process %s...Only 2D/3D grayscale images are supported' % basename)
            skipped += 1
            continue

        # Convert input image to float, since we are dealing with derivatives
        float_input = ij.op().run("convert.float32", input_image)

        # Obtain spatial calibration of the image
        x_spacing = float_input.averageScale(0)
        y_spacing = float_input.averageScale(1)
        spacing = [x_spacing, y_spacing]

        if num_dimensions == 3 and float_input.axis(2).type() == Axes.Z:
            z_spacing = float_input.averageScale(2)
            spacing.append(z_spacing)

        # Create placeholder image for the output then run the Frangi Vesselness op
        output = ij.op().run("create.img", float_input)
        pixel_scale = scale / (sum(spacing) / len(spacing))  # average voxel size: convert scale to pixel units
        ij.op().run("frangiVesselness", output, float_input, spacing, pixel_scale)

        # Save the result using the same basename as the image, adding "[Frangi].tif"
        # For example, the output for "OP_1.tif" would be named "OP_1[Frangi].tif"
        l = len(f)
        el = len(extension)
        output_filepath = f[0:l - el] + "[Frangi].tif"
        ij.io().save(output, output_filepath)

        processed += 1

    print('Done. %s file(s) processed. %s file(s) skipped...' % (processed, skipped))


run()
