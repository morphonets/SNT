#@ImagePlus imp
#@LogService log

from sholl import Sholl_Analysis
from sholl import Options
from os.path import expanduser


def spacedDistances(start, end, step):
    """Retrieves a list of Sholl sampling distances"""
    leng = (end - start) / step + 1
    return [start + i * step for i in range(leng)]


# x,y,z coordinates of center of analysis
xc, yc, zc = 100, 100, 10

# Threshold values for segmentation
lower_t, upper_t = 88, 255

# Definitions for sampling distances
start_radius, end_radius, step_size, = 10, 100, 10

# Destination directory for saving plots and tables
export_path = expanduser("~")

sa = Sholl_Analysis()

if sa.validateImage(imp):

    # Specify plugin settings
    sa.setDescription(imp.getTitle(), True)
    sa.setExportPath(export_path, True)
    sa.setInteractiveMode(False)

    # Customize output options
    so = Options()
    so.setMetric(Options.MEDIAN_INTERS, False)  # "Sholl Results" table
    so.setPlotOutput(Options.NO_PLOTS)  # Which plots should be generated?
    so.setPromptChoice(Options.HIDE_SAVED_FILES, True)  # Main prompt option
    so.setPromptChoice(Options.OVERLAY_SHELLS, True)  # Main prompt option
    sa.setOptions(so)

    # Specify analysis settings
    sa.setCenter(xc, yc, zc)
    sa.setThreshold(lower_t, upper_t)

    # Retrieve intersection counts
    distances = spacedDistances(start_radius, end_radius, step_size)
    counts = sa.analyze3D(xc, yc, zc, distances, imp)

    if all(c == 0 for c in counts):
        log.warn("All intersection counts were zero")

    else:

        # Do something with sampled data if analysis was successful
        for idx, inters in enumerate(counts):
            log.info("r=%s: %s inters." % (distances[idx],inters))

        # Retrieve metrics
        sa.analyzeProfile(distances, counts, True)

    log.info("Analysis finished. Files saved to %s" % export_path)
    log.info("Sholl Results Table has not been saved")

else:

    log.error(imp.getTitle() + " is not a valid image")
