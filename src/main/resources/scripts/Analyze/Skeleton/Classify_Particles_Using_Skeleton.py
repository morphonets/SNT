# @String(visibility="MESSAGE",value="<html><div WIDTH=600>This script tags particles according to skeleton features: It detects maxima on a masked image and clusters detected maxima using features of the mask-derived skeleton. A maxima is considered to be associated to a skeleton feature (junction, tip, etc.) if the distance between its centroid and the feature is less than or equal to a cuttoff (\"snap to\") distance.") MSG
# @ImagePlus(label="Particles image") impPart
# @ImagePlus(label="Skeletonizable mask", description="Must be a binary image (background = 0). Used to confine maxima detection and generate skeleton") impSkel
# @String(label="AutoThreshold for particle detection", choices={"Default", "Huang", "Intermodes", "IsoData", "IJ_IsoData", "Li", "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen"}) thres_method
# @Double(label="Max. \"snap to\" distance", description="In calibrated units", min=1, max=100, style="scroll bar", value=3) cutoff_dist
# @String(label="Output", choices={"ROIs only", "ROIs and Measurements"}) output
# @ImageJ ij


"""
    Classify_Particles_Using_Skeleton.py
    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    Tags particles according to skeleton features: Detects maxima on a masked
    image and clusters detected maxima using features of the skeletonized mask.
    A maxima is considered to be associated to a skeleton feature (junction,
    tip, etc.) if the distance between its centroid and the feature is less than
    or equal to a cuttoff ("snap to") distance.

    :version: 20210929
    :url: https://github.com/morphonets/SNT
    :license: GPL3, see LICENSE for more details
"""

from ij import IJ
from ij.gui import Overlay, PointRoi
from ij.plugin.filter import MaximumFinder

from sc.fiji.analyzeSkeleton import AnalyzeSkeleton_
from sc.fiji.skeletonize3D import Skeletonize3D_
from sc.fiji.snt.util import ImpUtils
from sc.fiji.snt.analysis import SNTTable

from java.awt import Color
import math, sys


def cleanse_overlay(overlay):
    """ Removes all point ROIs from the specified overlay """
    if not overlay:
        return Overlay()
    for i in reversed(range(overlay.size())):
        roi = overlay.get(i)
        if isinstance(roi, PointRoi):
            overlay.remove(i)
    return overlay


def distance(x1, y1, x2, y2):
    """ Calculates the distance between 2D points """
    return math.sqrt((x2 - x1)**2 + (y2 - y1)**2)


def error(msg):
    """ Displays an error message """
    ij.ui().showDialog(msg, "Error")


def get_centroids(imp, tolerance):
    """ Returns maxima using IJ1 """
    mf = MaximumFinder()
    maxima = mf.getMaxima(imp.getProcessor(), tolerance, True)
    return maxima.xpoints, maxima.ypoints, maxima.npoints


def get_threshold(imp, method):
#    from ij.process import AutoThresholder
#    from ij.process import ImageStatistics
#    thresholder = AutoThresholder()
#    stats = imp.getProcessor().getStatistics()
#    value = thresholder.getThreshold(method, stats.histogram)
    arg = "%s dark" % method
    IJ.setAutoThreshold(imp, arg)
    value = imp.getProcessor().getMinThreshold()
    IJ.resetThreshold(imp)
    return value


def pixel_size(imp):
    """ Returns the smallest pixel length of the specified image """
    cal = imp.getCalibration()
    return min(cal.pixelWidth, cal.pixelHeight)


def skeleton_properties(imp):
    """ Retrieves lists of endpoints, junction points, junction
        voxels and total length from a skeletonized image
    """
    skel_analyzer = AnalyzeSkeleton_()
    skel_analyzer.setup("", imp)
    skel_result = skel_analyzer.run()

    avg_lengths = skel_result.getAverageBranchLength()
    n_branches = skel_result.getBranches()
    lengths = [n*avg for n, avg in zip(n_branches, avg_lengths)]
    total_length = sum(lengths)

    return (skel_result.getListOfEndPoints(), skel_result.getJunctions(),
            skel_result.getListOfJunctionVoxels(), total_length)


def skeletonize(imp):
    """ Skeletonizes the specified image in situ """
    thin = Skeletonize3D_()
    thin.setup("", imp)
    thin.run(None)
    ImpUtils.removeIsolatedPixels(imp)


def run():

    mask_ip = impSkel.getProcessor()
    part_ip = impPart.getProcessor()

    if not mask_ip.isBinary():
        error(impSkel.getTitle() + " is not a binary mask.")
        return

    # Mask grayscale image and skeletonize mask
    try:
        mask_pixels = mask_ip.getPixels()
        part_pixels = part_ip.getPixels()
        for i in xrange(len(part_pixels)):
            if mask_pixels[i] == 0:
                part_pixels[i] = 0
        part_ip.setPixels(part_pixels)
    except IndexError:
        error("Chosen images are not the same size.")
    skeletonize(impSkel)

    # Get skeleton features
    end_points, junctions, junction_voxels, total_len = skeleton_properties(impSkel)
    if not end_points and not junction_voxels:
        error(impSkel.getTitle() + " does not seem a valid skeleton.")
        return

    # Retrieve centroids from IJ1
    threshold_lower = get_threshold(impPart, thres_method)
    cx, cy, n_particles = get_centroids(impPart, threshold_lower)
    if None in (cx, cy):
        error("Verify parameters: No particles detected.")
        return

    # Loop through each centroids and categorize its position
    # according to its distance to skeleton features
    n_bp = n_tip = n_none = n_both = 0

    overlay = cleanse_overlay(impPart.getOverlay())
    for i in range(n_particles):

        j_dist = ep_dist = sys.maxint

        # Retrieve the distance between this particle and the closest junction voxel
        for jvoxel in junction_voxels:
            dist = distance(cx[i], cy[i], jvoxel.x, jvoxel.y)
            if (dist <= cutoff_dist and dist < j_dist):
                j_dist = dist

        # Retrieve the distance between this particle and the closest end-point
        for end_point in end_points:
            dist = distance(cx[i], cy[i], end_point.x, end_point.y)
            if (dist <= cutoff_dist and dist < ep_dist):
                ep_dist = dist

        roi_id = str(i).zfill(len(str(n_particles)))
        roi_name = "Unknown:" + roi_id
        roi_color = Color.ORANGE
        roi_type = 2  # dot

        # Is particle associated with neither junctions nor end-points?
        if j_dist > cutoff_dist and ep_dist > cutoff_dist:
            roi_name = "Unc:" + roi_id
            #n_none += 1
        # Is particle associated with both?
        elif abs(j_dist - ep_dist) <= pixel_size(impPart) / 2:
            roi_name = "J+T:" + roi_id
            roi_color = Color.CYAN
            #roi_type = 1  # crosshair
            n_both += 1
        # Is particle associated with an end-point?
        elif ep_dist < j_dist:
            roi_name = "Tip:" + roi_id
            roi_color = Color.GREEN
            #roi_type = 0  # hybrid
            n_tip += 1
        # Is particle associated with a junction?
        elif ep_dist > j_dist:
            roi_name = "Junction:" + roi_id
            roi_color = Color.MAGENTA
            #roi_type = 3  # circle
            n_bp += 1

        roi = PointRoi(cx[i], cy[i])
        roi.setName(roi_name)
        roi.setStrokeColor(roi_color)
        roi.setPointType(roi_type)
        roi.setSize(2)  # medium
        overlay.add(roi)

    # Display result
    impSkel.setOverlay(overlay)
    impPart.setOverlay(overlay)

    # Output some measurements
    if "table" in output:

        t = SNTTable()
        t.appendToLastRow("Part. image", "%s (%s)" % (impPart.getTitle(), impPart.getCalibration().getUnits()))
        t.appendToLastRow("Skel. image", "%s (%s)" % (impSkel.getTitle(), impSkel.getCalibration().getUnits()))
        t.appendToLastRow("Junction particles", n_bp)
        t.appendToLastRow("Tip particles", n_tip)
        t.appendToLastRow("J+T particles", n_both)
        t.appendToLastRow("Unc. particles", n_none)
        t.appendToLastRow("Junctions w/ particles", n_bp + n_both)
        t.appendToLastRow("Tips w/ particles", n_tip + n_both)
        t.appendToLastRow("Total skel. lenght", total_len)
        t.appendToLastRow("Total end points", len(end_points))
        t.appendToLastRow("Total junctions", sum(junctions))
        t.appendToLastRow("Unc. particles / Total skel. lenght)", n_none/total_len)
        t.appendToLastRow("Snap-to dist.", str(cutoff_dist) + impPart.getCalibration().getUnits())
        t.appendToLastRow("Threshold", "%d (%s)" % (threshold_lower, thres_method))
        ij.ui().show("Results", t)


run()

