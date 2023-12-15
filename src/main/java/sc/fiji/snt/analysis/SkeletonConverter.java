/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.analysis;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.ImagesToStack;

import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.BiconnectivityInspector;
import sc.fiji.analyzeSkeleton.*;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for generation of {@link Tree}s from a skeletonized {@link ImagePlus}.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see sc.fiji.skeletonize3D.Skeletonize3D_
 * @see AnalyzeSkeleton_
 */
public class SkeletonConverter {

	/* scripting convenience: Keep references to AnalyzeSkeleton_ common fields */
    public static final int LOWEST_INTENSITY_BRANCH = AnalyzeSkeleton_.LOWEST_INTENSITY_BRANCH;
    public static final int LOWEST_INTENSITY_VOXEL = AnalyzeSkeleton_.LOWEST_INTENSITY_VOXEL;
    public static final int SHORTEST_BRANCH = AnalyzeSkeleton_.SHORTEST_BRANCH;

    // AnalyzeSkeleton parameters
    private final ImagePlus imp;
    private ImagePlus origIP = null;
    private int pruneMode = AnalyzeSkeleton_.SHORTEST_BRANCH;
    private boolean pruneEnds = false;
    private boolean shortestPath = false;
    private boolean silent = true;
    private boolean verbose = false;
    private boolean pruneByLength = false;
    private double lengthThreshold;
    // Connection parameters
    private boolean connectComponents = false;
    private double maxConnectDist;
    // Scale parameters for generated reconstructions
    double pixelWidth;
    double pixelHeight;
    double pixelDepth;

	/**
	 * @param imagePlus The image to be parsed. Will be converted to a topological
	 *                  skeleton (assuming non-zero foreground)
	 */
	public SkeletonConverter(final ImagePlus imagePlus) {
		this(imagePlus, true);
	}

    /**
     * @param imagePlus   The image to be parsed. It is expected to be binary
     *                    (non-zero foreground).
     * @param skeletonize If true, image will be skeletonized using
     *                    {@link Skeletonize3D_} _in place_ prior to the analysis.
     *                    Conversion will be nonsensical if {@code false} and
     *                    {@code imagePlus} is not a topological skeleton
     * @throws IllegalArgumentException if {@code skeletonize} is true and
     *                                  {@code imagePlus} is not binary.
     */
    public SkeletonConverter(final ImagePlus imagePlus, final boolean skeletonize) throws IllegalArgumentException {
    	this.imp = imagePlus;
        final Calibration cal = imp.getCalibration();
        this.pixelWidth = cal.pixelWidth;
        this.pixelHeight = cal.pixelHeight;
        this.pixelDepth = cal.pixelDepth;
        if (skeletonize) {
            if (!imagePlus.getProcessor().isBinary())
                throw new IllegalArgumentException("Only binary images allowed");
            skeletonize(imagePlus, imagePlus.getNSlices() == 1);
        }
    }

	/**
	 * @param imagePlus The (timelapse) image to be parsed. It is expected to be
	 *                  binary (non-zero foreground).
	 * @param frame     The frame of the timelapse image to be parsed
	 * @throws IllegalArgumentException If image is not binary {@code imagePlus} is
	 *                                  not binary.
	 */
	public SkeletonConverter(final ImagePlus imagePlus, final int frame) {
		this(ImpUtils.getFrame(imagePlus, frame), true);
	}

	/**
	 * Convenience method to skeletonize an image using
	 * {@link Skeletonize3D_}.
	 *
	 * @param imp                 The image to be skeletonized. All non-zero
	 *                            values are considered to be foreground.
	 * @param lowerThreshold      intensities below this value will be set to zero,
	 *                            and will not contribute to the skeleton. Ignored
	 *                            if < 0
	 * @param upperThreshold       intensities above this value will be set to zero,
	 *                            and will not contribute to the skeleton. Ignored
	 *                            if < 0
	 * @param erodeIsolatedPixels If true, any isolated pixels (single point
	 *                            skeletons) that may be formed after
	 *                            skeletonization are eliminated by erosion.
	 */
	public static void skeletonize(final ImagePlus imp, final double lowerThreshold, final double upperThreshold,
			final boolean erodeIsolatedPixels) {
		if (lowerThreshold != ij.process.ImageProcessor.NO_THRESHOLD && lowerThreshold > 0 && upperThreshold > 0) {
			// TODO: Adopt IJ ops!?
			ij.IJ.setRawThreshold(imp, lowerThreshold, upperThreshold, "no update");
			SNTUtils.log("Lower threshold = " + lowerThreshold + ", Upper threshold = " + upperThreshold);
			final int nImagesBefore = ij.WindowManager.getImageCount();
			IJ.run(imp, "Convert to Mask", " black");
			// HACK: By some strange reason (sometimes!?) a new mask is created even though we are skipping the
			// flag to create a new stack in the options string. So for now, we'll just try to intercept it
			final int nImagesAfter = ij.WindowManager.getImageCount();
			if (nImagesAfter == nImagesBefore + 1) {
				final ImagePlus maskImg = ij.WindowManager.getCurrentImage();
				if (maskImg.getTitle().startsWith("MASK_") ) {
					imp.setImage(maskImg);
					maskImg.close();
				}
			}
		} else {
			ImpUtils.convertTo8bit(imp); // does nothing if imp already 8-bit
		}
		SNTUtils.log("Skeletonizing...");
		final Skeletonize3D_ thin = new Skeletonize3D_();
		thin.setup("", imp);
		thin.run(null);
		if (erodeIsolatedPixels)
			ImpUtils.removeIsolatedPixels(imp);
		imp.updateImage();
	}

	/**
	 * Convenience method to skeletonize a thresholded image using
	 * {@link Skeletonize3D_}.
	 *
	 * @param imp                 The thresholded image to be skeletonized. If
	 *                            the image is not thresholded all non-zero
	 *                            values are considered to be foreground.
	 * @param erodeIsolatedPixels If true, any isolated pixels (single point
	 *                            skeletons) that may be formed after
	 *                            skeletonization are eliminated by erosion.
	 */
	public static void skeletonize(final ImagePlus imp, final boolean erodeIsolatedPixels) {
		skeletonize(imp, imp.getProcessor().getMinThreshold(), imp.getProcessor().getMaxThreshold(), erodeIsolatedPixels);
	}

	/**
	 * Convenience method to skeletonize a thresholded time-lapse using
	 * {@link Skeletonize3D_}.
	 *
	 * @param imp                 The timelapse to be skeletonized. If the image is
	 *                            not thresholded all non-zero values are considered
	 *                            to be foreground.
	 * @param erodeIsolatedPixels If true, any isolated pixels (single point
	 *                            skeletons) that may be formed after
	 *                            skeletonization are eliminated by erosion.
	 */
	public static void skeletonizeTimeLapse(final ImagePlus imp, final boolean erodeIsolatedPixels) {
		final ImagePlus[] imps = new ImagePlus[imp.getNFrames()];
		for (int f = 1; f < imp.getNFrames(); f++) {
			final ImagePlus extracted = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), f, f);
			skeletonize(extracted, imp.getProcessor().getMinThreshold(), imp.getProcessor().getMaxThreshold(),
					erodeIsolatedPixels);
			imps[f - 1] = extracted;
		}
		final ImagePlus result = ImagesToStack.run(imps);
		result.setDimensions(imp.getNChannels(), imp.getNSlices(), imp.getNFrames());
		imp.setImage(result);
	}

	/**
	 * Generates a list of {@link Tree}s from the skeleton image. Each Tree
	 * corresponds to one connected component of the graph returned by
	 * {@link SkeletonResult#getGraph()}.
	 *
	 * @return the skeleton tree list
	 */
    public List<Tree> getTrees() {
        final List<Tree> treeList = new ArrayList<>();
        for (final DirectedWeightedGraph graph : getGraphs()) {
            final Tree tree = graph.getTree();
            /* Assign image calibration and known CT positions to tree */
            assignToImage(tree);
            treeList.add(tree);
        }
        return treeList;
    }

    /**
     * Generates a list of {@link Tree}s from the skeleton image.
     * Each Tree corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     *
     * @param roi                the ROI enclosing the end-/junction-point(s) to be
     *                           set as root(s) of the final graphs.
     * @param restrictToROIplane if true and the image is 3D, ROI enclosure is
     *                           restricted to the ROI plane
     *                           ({@link Roi#getZPosition()}), or the active Z-slice
     *                           if ROI is not associated with a particular slice
     * @return the skeleton tree list
     */
    public List<Tree> getTrees(final Roi roi, final boolean restrictToROIplane) {
        final List<Tree> treeList = new ArrayList<>();
        for (final DirectedWeightedGraph graph : getGraphs(roi, restrictToROIplane)) {
            final Tree tree = graph.getTree();
            /* Assign image calibration and known CT positions to tree */
            assignToImage(tree);
            treeList.add(tree);
        }
        return treeList;
    }

    /**
     * Generates a list of {@link DirectedWeightedGraph}s from the skeleton image.
     * Each graph corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     * @return 
     *
     * @return the skeleton graph list
     */
    public List<DirectedWeightedGraph> getGraphs() {
        List<DirectedWeightedGraph> graphList = new ArrayList<>();
        for (final Graph skelGraph : getSkeletonGraphs()) {
            final DirectedWeightedGraph graph = sntGraphFromSkeletonGraph(skelGraph);
            if (pruneByLength && graph.sumEdgeWeights() < lengthThreshold) {
                continue;
            }
            graphList.add(graph);
        }
        if (connectComponents && graphList.size() > 1) {
            graphList = connectComponents(graphList);
        }
        for (final DirectedWeightedGraph graph : graphList) {
            convertToDirected(graph);
            graph.updateVertexProperties();
        }
        return graphList;
    }

    /**
     * Generates a list of {@link DirectedWeightedGraph}s from the skeleton image.
     * Each graph corresponds to one connected component of the graph returned by
     * {@link SkeletonResult#getGraph()}.
     *
     * @param roi                the ROI enclosing the end-/junction-point(s) to be
     *                           set as root(s) of the final graphs.
     * @param restrictToROIplane if true and the image is 3D, ROI enclosure is
     *                           restricted to the ROI plane
     *                           ({@link Roi#getZPosition()}), or the active Z-slice
     *                           if ROI is not associated with a particular slice
     * @return the skeleton graph list
     */
    public List<DirectedWeightedGraph> getGraphs(final Roi roi, final boolean restrictToROIplane) {

        int roiSlice = -1;
        if (restrictToROIplane && imp.getNSlices() > 1) {
            roiSlice = roi.getZPosition();
            if (roiSlice == 0) {
                // ROI is not associated with any slice, let's assume user meant active z-pos
                roiSlice = imp.getZ();
            }
            // make ROI slice a 0-based index as per SkeletonResult point coordinates
            roiSlice--;
        }
        final SkeletonResult sr = getSkeletonResult();
        final List<PointInImage> putativeRoots = new ArrayList<>();
        for (final Point p : sr.getListOfEndPoints()) {
            // If the ROI is enclosing an end-point, use it as root
            if (roiSlice > -1 && p.z != roiSlice)
                continue;
            if (roi.containsPoint(p.x, p.y))
                putativeRoots.add(new PointInImage(p.x * pixelWidth, p.y * pixelHeight, p.z * pixelDepth));
        }
        if (putativeRoots.isEmpty()) {
            // then maybe the ROI is enclosing a junction-point
            for (final Point p : sr.getListOfJunctionVoxels()) {
                if (roiSlice > -1 && p.z != roiSlice)
                    continue;
                if (roi.containsPoint(p.x, p.y))
                    putativeRoots.add(new PointInImage(p.x * pixelWidth, p.y * pixelHeight, p.z * pixelDepth));
            }
        }
        final List<DirectedWeightedGraph> graphList = getGraphs();
        if (putativeRoots.isEmpty()) {
            return graphList;
        }
		if (putativeRoots.size() > graphList.size()) {
			SNTUtils.log("# of end-points and/or junction points enclosed by the ROI >  # of structures in image");
		}
        for (final DirectedWeightedGraph graph : graphList) {
            // We now have a list of putative roots and a series of graphs.
            // Let's try to assign them
            for (final PointInImage putativeRoot : putativeRoots) {
                final SWCPoint root = getMatchingLocationInGraph(putativeRoot, graph);
                if (root != null)
                    graph.setRoot(root);
            }
        }
        return graphList;
    }

	/**
	 * Roots a graph into the centroid of a ROI. Does nothing if none of graph
	 * nodes' (X,Y) coordinates are contained by the ROI
	 * 
	 * @param graph The graph to be rooted
	 * @param roi   the ROI defining the root centroid
	 */
	public void setCentroidAsRoot(final DirectedWeightedGraph graph, final Roi roi) {
		final SWCPoint nearest = getNearestTipJunctionOrSlabInRoi(graph, roi);
		if (nearest == null)
			return;
		final int uniqueId = graph.vertexSet().stream().mapToInt(v -> v.id).max().orElse(-2) + 1;
		final double[] cc = roi.getContourCentroid();
		final PointInImage centroid = new PointInImage(cc[0], cc[1], roi.getZPosition());
		final SWCPoint newRoot = new SWCPoint(uniqueId, nearest.type, centroid.x * pixelWidth, centroid.y * pixelHeight,
				centroid.z * pixelDepth, nearest.radius, nearest.id);
		newRoot.setPath(nearest.getPath());
		graph.addVertex(newRoot);
		graph.addEdge(nearest, newRoot);
		graph.setRoot(newRoot);
	}

	private boolean nodeInRoi(final SWCPoint node, final Roi roi) {
		return (node != null && roi.containsPoint(imp.getCalibration().getRawX(node.x), imp.getCalibration().getRawY(node.y)));
	}

	private SWCPoint getNearestTipJunctionOrSlabInRoi(final DirectedWeightedGraph graph, final Roi roi) {
		final Set<SWCPoint> vertices = graph.vertexSet();
		final List<PointInImage> contourPoints = getContourPoints(roi);
		final SWCPoint nearestTip = getNearestPointInGraph(vertices.stream().filter(v -> graph.outDegreeOf(v) == 0).collect(Collectors.toList()), contourPoints);
		if (nodeInRoi(nearestTip, roi))
			return nearestTip;
		final SWCPoint nearestJunction = getNearestPointInGraph(vertices.stream().filter(v -> graph.outDegreeOf(v) > 1).collect(Collectors.toList()), contourPoints);
		if (nodeInRoi(nearestJunction, roi))
			return nearestTip;
		final SWCPoint nearestSlab = getNearestPointInGraph(vertices.stream().filter(v -> graph.outDegreeOf(v) == 1).collect(Collectors.toList()), contourPoints);
		if (nodeInRoi(nearestSlab, roi))
			return nearestTip;
		return null;
	}

	private List<PointInImage> getContourPoints(final Roi roi) {
		List<PointInImage> points = new ArrayList<>();
		final Calibration cal = imp.getCalibration();
		for (java.awt.Point p : roi.getContainedPoints()) {
			points.add(new PointInImage(cal.getX(p.x), cal.getY(p.y), cal.getZ(roi.getZPosition())));
		}
		return points;
	}

	private SWCPoint getNearestPointInGraph(final Collection<SWCPoint> vertices, List<PointInImage> points) {
		double distanceSquaredToNearestParentPoint = Double.MAX_VALUE;
		SWCPoint nearest = null;
		for (final SWCPoint p : vertices) {
			for (final PointInImage pim : points) {
				final double distanceSquared = pim.distanceSquaredTo(p.x, p.y, p.z);
				if (distanceSquared < distanceSquaredToNearestParentPoint) {
					nearest = p;
					distanceSquaredToNearestParentPoint = distanceSquared;
				}
			}
		}
		return nearest;
	}

    private SWCPoint getMatchingLocationInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
        if (point != null) {
            for (final SWCPoint p : graph.vertexSet()) {
                if (p.isSameLocation(new PointInImage(point.x, point.y, point.z))) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Sets the original {@link ImagePlus} to be used during voxel-based loop pruning.
     * See <a href="https://imagej.net/plugins/analyze-skeleton/?amp=1#loop-detection-and-pruning">AnalyzeSkeleton documentation</a>
     *
     * @param origIP the original ImagePlus
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setOrigIP(ImagePlus origIP) {
        this.origIP = origIP;
    }

    /**
     * Gets the loop pruning strategy.
     *
     * @see #setPruneMode(int)
     */
    public int getPruneMode() {
        return pruneMode;
    }

    /**
     * Sets the loop pruning strategy.
     * See <a href="https://imagej.net/plugins/analyze-skeleton/?amp=1#loop-detection-and-pruning">AnalyzeSkeleton documentation</a>
     *
     * @param pruneMode the loop prune strategy, e.g., {@link AnalyzeSkeleton_#SHORTEST_BRANCH},
     *                  {@link AnalyzeSkeleton_#LOWEST_INTENSITY_BRANCH} or {@link AnalyzeSkeleton_#LOWEST_INTENSITY_VOXEL}
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setPruneMode(int pruneMode) {
        this.pruneMode = pruneMode;
    }

    /**
     * Sets whether to prune branches which end in end-points from the result.
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setPruneEnds(boolean pruneEnds) {
        this.pruneEnds = pruneEnds;
    }

    /**
     * Sets whether to calculate the longest shortest-path in the skeleton result.
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setShortestPath(boolean shortestPath) {
        this.shortestPath = shortestPath;
    }

    /**
     * Setting this to false will display both the tagged skeleton image and the shortest path image (if the
     * shortest path calculation is enabled).
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    /**
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Sets whether to prune components below a threshold length from the result.
     */
    public void setPruneByLength(boolean pruneByLength) {
        this.pruneByLength = pruneByLength;
    }

    /**
     * The minimum component length necessary to avoid pruning. This value is only used
     * if {@link SkeletonConverter#pruneByLength} is true.
     *
     * @param lengthThreshold the length threshold
     * @see SkeletonConverter#setPruneByLength(boolean)
     */
    public void setLengthThreshold(double lengthThreshold) {
        if (lengthThreshold < 0) {
            lengthThreshold = 0;
        }
        this.lengthThreshold = lengthThreshold;
    }

    /**
     * Whether to merge broken components in the skeleton result
     *
     * @param connectComponents
     * @see SkeletonConverter#setMaxConnectDist(double)
     */
    public void setConnectComponents(boolean connectComponents) {
        this.connectComponents = connectComponents;
    }

    /**
     * The maximum allowable distance between nearest neighbors to be considered for a merge
     *
     * @param maxConnectDist
     * @see SkeletonConverter#setConnectComponents(boolean)
     */
    public void setMaxConnectDist(double maxConnectDist) {
        if (maxConnectDist <= 0) {
            maxConnectDist = Double.MIN_VALUE;
        }
        this.maxConnectDist = maxConnectDist;
    }

	/**
	 * Assigns image calibration, etc. to tree. Avoids unexpected offsets when
	 * initializing SNT
	 */
	private void assignToImage(final Tree tree) {
		final String fProp = imp.getProp("extracted-frame");
		final String cProp = imp.getProp("extracted-channel");
		final int f = (fProp == null) ? 1 : Integer.valueOf(fProp);
		final int c = (cProp == null) ? 1 : Integer.valueOf(cProp);
		tree.list().forEach(path -> path.setCTposition(c, f));
		tree.assignImage(imp);
	}
   
    /**
     * Runs AnalyzeSkeleton on the image and gets the Graph Array returned by {@link SkeletonResult#getGraph()}
     */
    private Graph[] getSkeletonGraphs() {
        return getSkeletonResult().getGraph();
    }

    /**
     * Runs AnalyzeSkeleton on the image and gets its {@link SkeletonResult}
     */
    private SkeletonResult getSkeletonResult() {
        final AnalyzeSkeleton_ skeleton = new AnalyzeSkeleton_();
        skeleton.setup("", imp);
        return skeleton.run(pruneMode, pruneEnds, shortestPath, origIP, silent, verbose);
    }
   
    /**
     * Convert the AnalyzeSkeleton {@link Graph} object to an SNT {@link Tree}, using a {@link DirectedWeightedGraph}
     * as an intermediary data structure.
     */
    private DirectedWeightedGraph sntGraphFromSkeletonGraph(final Graph skeletonGraph) {
        final DirectedWeightedGraph sntGraph = new DirectedWeightedGraph();
        final Map<Point, SWCPoint> pointMap = new HashMap<>();
        for (final Vertex vertex : skeletonGraph.getVertices()) {
            final Point v = vertex.getPoints().get(0);
            /* Use dummy values for all fields except the point coordinates.
            These will be assigned real values automatically during conversion to Tree. */
            final SWCPoint swcPoint = new SWCPoint(
                    0,
                    0,
                    v.x * pixelWidth,
                    v.y * pixelHeight,
                    v.z * pixelDepth,
                    0,
                    -1
            );
            pointMap.put(v, swcPoint);
            sntGraph.addVertex(swcPoint);
        }
        for (final Edge edge : skeletonGraph.getEdges()) {
            final SWCPoint p1 = pointMap.get(edge.getV1().getPoints().get(0));
            final SWCPoint p2 = pointMap.get(edge.getV2().getPoints().get(0));
            final List<Point> slabs = edge.getSlabs();
            if (slabs.isEmpty()) {
                sntGraph.addEdge(p1, p2);
                continue;
            }
            SWCPoint swcSlab = new SWCPoint(
                    0,
                    0,
                    slabs.get(0).x * pixelWidth,
                    slabs.get(0).y * pixelHeight,
                    slabs.get(0).z * pixelDepth,
                    0,
                    -1
            );
            pointMap.put(slabs.get(0), swcSlab);
            sntGraph.addVertex(swcSlab);
            for (int i = 1; i < slabs.size(); i++) {
                swcSlab = new SWCPoint(
                        0,
                        0,
                        slabs.get(i).x * pixelWidth,
                        slabs.get(i).y * pixelHeight,
                        slabs.get(i).z * pixelDepth,
                        0,
                        -1
                );
                pointMap.put(slabs.get(i), swcSlab);
                sntGraph.addVertex(swcSlab);
                final SWCPoint previous = pointMap.get(slabs.get(i - 1));
                final SWCWeightedEdge e = sntGraph.addEdge(previous, swcSlab);
                sntGraph.setEdgeWeight(e, swcSlab.distanceTo(previous));
            }
            SWCPoint firstSlabPoint = pointMap.get(slabs.get(0));
            SWCWeightedEdge e1 = sntGraph.addEdge(p1, firstSlabPoint);
            sntGraph.setEdgeWeight(e1, firstSlabPoint.distanceTo(p1));

            SWCPoint lastSlabPoint = pointMap.get(slabs.get(slabs.size() - 1));
            SWCWeightedEdge e2 = sntGraph.addEdge(lastSlabPoint, p2);
            sntGraph.setEdgeWeight(e2, lastSlabPoint.distanceTo(p2));
        }

        return sntGraph;
    }

    /**
     * Enforce consistent edge direction in the resulting {@link DirectedWeightedGraph}, which is required
     * before conversion to a Tree. The graph is traversed depth first starting at some terminal
     * node (i.e, a node of degree 1). The incident edges of each visited node are changed to orient towards
     * the adjacent un-visited node.
     */
    private void convertToDirected(final DirectedWeightedGraph sntGraph) {
        final SWCPoint root = sntGraph.vertexSet().stream()
                .filter(v -> sntGraph.degreeOf(v) == 1).findFirst().orElse(null);
        if (root == null) {
            return;
        }
        sntGraph.setRoot(root);
    }

    private List<DirectedWeightedGraph> connectComponents(final List<DirectedWeightedGraph> graphList) {
        final List<SWCPoint> vertices = new ArrayList<>();
        int component = 0;
        for (final DirectedWeightedGraph graph : graphList) {
            for (final SWCPoint vertex : graph.vertexSet()) {
                if (graph.degreeOf(vertex) <= 1) {
                    // Only consider endpoints and isolated vertices
                    vertex.v = component;
                    vertices.add(vertex);
                }
            }
            component++;
        }
        SWCPoint[] vertexArray = new SWCPoint[vertices.size()];
        vertexArray = vertices.toArray(vertexArray);

        final double[][] coordinates = new double[vertices.size()][];
        for (int i = 0; i < vertexArray.length; i++) {
            final SWCPoint vertex = vertexArray[i];
            coordinates[i] = new double[]{vertex.getX(), vertex.getY(), vertex.getZ()};
        }

        final KDTree<SWCPoint> kdtree = new KDTree<>(coordinates, vertexArray);
        final List<VertexPair> pairList = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i++) {
            final SWCPoint referenceVertex = vertexArray[i];
            final List<Neighbor<double[], SWCPoint>> neighbors = new ArrayList<>();
            // Query the ball around the reference vertex
            kdtree.search(coordinates[i], maxConnectDist, neighbors);
            for (final Neighbor<double[], SWCPoint> neighbor : neighbors) {
                final SWCPoint neighborVertex = neighbor.value;
                if (neighborVertex.v == referenceVertex.v) {
                    // Skip neighbors that occur within the same component
                    continue;
                }
                final double distance = neighbor.distance;
                //System.out.println(distance);
                pairList.add(new VertexPair(referenceVertex, neighborVertex, distance));
                break; // Stop after finding closest neighbor in a different component
            }
        }
        // Sort pairs ascending by distance
        Collections.sort(pairList);
        // Keep track of components that have been merged so we do not create loops
        final Set<Integer> mergedComponents = new HashSet<>();
        final DirectedWeightedGraph mergedGraph = new DirectedWeightedGraph();
        graphList.forEach(graph -> Graphs.addGraph(mergedGraph, graph));
        for (final VertexPair pair : pairList) {
            if (mergedComponents.contains((int) pair.v1.v) && mergedComponents.contains((int) pair.v2.v)) {
                continue;
            }
            mergedGraph.addEdge(pair.v1, pair.v2);
            mergedComponents.add((int) pair.v1.v);
            mergedComponents.add((int) pair.v2.v);
        }
        final List<DirectedWeightedGraph> finalComponentList = new ArrayList<>();
        final BiconnectivityInspector<SWCPoint, SWCWeightedEdge> inspector = new BiconnectivityInspector<>(mergedGraph);
        for (final org.jgrapht.Graph<SWCPoint, SWCWeightedEdge> graph : inspector.getConnectedComponents()) {
            final DirectedWeightedGraph graphComponent = new DirectedWeightedGraph();
            Graphs.addGraph(graphComponent, graph);
            finalComponentList.add(graphComponent);
        }
        return finalComponentList;
    }

    private static class VertexPair implements Comparable<VertexPair> {

        SWCPoint v1;
        SWCPoint v2;
        double distance;

        VertexPair(SWCPoint v1, SWCPoint v2, double distance) {
            this.v1 = v1;
            this.v2 = v2;
            this.distance = distance;
        }

        @Override
        public int compareTo(VertexPair o) {
            return Double.compare(this.distance, o.distance);
        }
    }

    /* IDE debug method */
    public static void main(String[] args) {
    	ImagePlus imp = new sc.fiji.snt.SNTService().demoImage("ddaC");
        SkeletonConverter converter = new SkeletonConverter(imp, true);
        converter.setPruneEnds(false);
        converter.setPruneMode(AnalyzeSkeleton_.SHORTEST_BRANCH);
        converter.setShortestPath(false);
        converter.setSilent(true);
        converter.setVerbose(true);
        //converter.setPruneByLength(true);
        //converter.setLengthThreshold(200);
        List<Tree> skelTrees = converter.getTrees();
        System.out.println("Num result trees: " + skelTrees.size());
        Viewer3D viewer = new Viewer3D();
        Tree.assignUniqueColors(skelTrees);
        viewer.add(skelTrees);
        viewer.show();
    }

}
