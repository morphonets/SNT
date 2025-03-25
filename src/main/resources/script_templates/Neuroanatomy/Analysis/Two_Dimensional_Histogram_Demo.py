#@SNTService snt

"""
 Exemplifies how to plot a two-dimensional bivariate histogram in SNT.
 Documentation Resources: https:#imagej.net/plugins/snt/scripting
 Latest SNT API: https://javadoc.scijava.org/SNT/
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.util import ColorMaps
from sc.fiji.snt.analysis import SNTChart, TreeStatistics


# The API for generating two-dimensional histograms is minimalistic[1].
# Histograms can be created from a matrix, two lists of values, or two
# DescriptiveStatistics instances (as produced by TreeStatistics[2],
# NodeStatistics[3], etc.). We'll use the latter in this example

# 1st let's retrieve some demo trees from SNTService. We'll translate them so
# that the coordinates of their soma (root) is (0,0,0)
trees = snt.demoTrees()
for tree in trees:
    root = tree.getRoot()
    tree.translate(-root.getX(), -root.getY(), -root.getZ())

# Now we'll define 2 morphometric properties and assemble DescriptiveStatistics
# instances for them. We'll use X,Y coordinates to use the histogram as a
# 'morphology rendering' plot
metric1 = "X coordinates"
metric2 = "Y coordinates"
stats1 = TreeStatistics.fromCollection(trees, metric1).getDescriptiveStats(metric1)
stats2 = TreeStatistics.fromCollection(trees, metric2).getDescriptiveStats(metric2)

# Define a color map (LUT). If 'None', histogram rendered in a single color
colormap = ColorMaps.get("viridis")

# Optional: Define labels for axes
labels = [metric1, metric2, "Freq."]

# Display the histogram
SNTChart.showHistogram3D(stats1, stats2, colormap, labels)

# API references below:
# [1] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/SNTChart.html
# [2] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/TreeStatistics.html
# [3] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/NodeStatistics.html