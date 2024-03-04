/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.BiconnectivityInspector;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.ImagesToStack;
import ij.process.ImageProcessor;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

/**
 * Class for generation of {@link Tree}s from a skeletonized {@link ImagePlus}.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see sc.fiji.skeletonize3D.Skeletonize3D_
 * @see sc.fiji.analyzeSkeleton.AnalyzeSkeleton_
 */
public class SkeletonConverter {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	/* scripting convenience: Keep references to AnalyzeSkeleton_ common fields */
	/** Pruning mode: flag for lowest intensity branch pruning */
	public static final int LOWEST_INTENSITY_BRANCH = AnalyzeSkeleton_.LOWEST_INTENSITY_BRANCH;
	/** Pruning mode: flag for lowest pixel intensity pruning */
	public static final int LOWEST_INTENSITY_VOXEL = AnalyzeSkeleton_.LOWEST_INTENSITY_VOXEL;
	/** Pruning mode: flag shortest branch pruning */
	public static final int SHORTEST_BRANCH = AnalyzeSkeleton_.SHORTEST_BRANCH;
	/**
	 * Rooting strategy: Tree(s) are rooted at arbitrary on end-point(s) of
	 * skeletonized structures, and are not influenced by root-delineating ROI.
	 */
	public static final int ROI_UNSET = 8;
	/**
	 * Rooting strategy: Tree(s) are rooted on end-point(s) of skeletonized
	 * structures adjacent to the perimeter (contour) of root-delineating ROI.
	 * Skeletonized voxels inside ROI are excluded from analysis.
	 */
	public static final int ROI_EDGE = 16;
	/**
	 * Rooting strategy: Tree(s) are rooted on end-point(s) of skeletonized
	 * structures contained by ROI. Skeletonized voxels inside ROI are included in
	 * analysis.
	 */
	public static final int ROI_CONTAINED = 32;
	/**
	 * Rooting strategy: Root(s) of extracted tree(s) are rooted on the centroid of
	 * root-delineating ROI. Skeletonized voxels inside ROI are excluded from
	 * analysis.
	 */
	public static final int ROI_CENTROID = 64;
	/**
	 * Rooting strategy: Root(s) of extracted tree(s) are rooted on the centroid of
	 * all the root(s) contained by root-delineating ROI. Skeletonized voxels inside
	 * ROI are excluded from analysis.
	 */
	public static final int ROI_CENTROID_WEIGHTED = 128;


    // AnalyzeSkeleton parameters
    private final ImagePlus imp;
    private ImagePlus origIP = null;
    private int pruneMode = SHORTEST_BRANCH;
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
    // Area ROI delineating soma
	private Roi somaRoi;
	private int strategy;
	private SkeletonResult skeletonResult;

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
			ij.IJ.run(imp, "Convert to Mask", " black");
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
	 * Generates a single {@link Tree} from {@link #getSingleGraph()}. If a
	 * ROI-based centroid has been set, Root is converted to a single node, root
	 * path with radius set to that of a circle with the same area of root-defining
	 * soma.
	 * 
	 * @return the single tree
	 * @see #setRootRoi(Roi, int)
	 */
	public Tree getSingleTree() {
		final DirectedWeightedGraph graph = getSingleGraph();
		final Tree tree = graph.getTree();
		assignToImage(tree);

		// It seems convenient to isolate the graphs' root (soma) as a
		// stand-alone, single node path for easier tagging, fitting, etc.
		if (!tree.list().isEmpty()
				&& (getRootRoiStrategy() == ROI_CENTROID || getRootRoiStrategy() == ROI_CENTROID_WEIGHTED)) {

			// Retrieve primary paths. this is expected to be a singleton list
			final List<Path> primaryPaths = tree.list().stream().filter(p -> p.isPrimary())
					.collect(Collectors.toList());
			// Create soma Path and add it to tree
			final Path newRootPath = tree.list().get(0).createPath();
			newRootPath.setOrder(1);
			newRootPath.setName("Centroid");
			newRootPath.setSWCType(Path.SWC_SOMA);
			newRootPath.addNode(graph.getRoot());
			newRootPath.setRadius(RoiConverter.getFittedRadius(imp, somaRoi));
			tree.add(newRootPath);
			// Set primary paths to branch out from it
			primaryPaths.forEach(primaryPath -> {
				primaryPath.setStartJoin(newRootPath, newRootPath.getNode(0));
			});
		}
		return tree;
	}

    /**
     * Generates a list of {@link DirectedWeightedGraph}s from the skeleton image.
     * Each graph corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     * @return 
     *
     * @return the list of skeletonized graphs
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
		
		if (getRootRoiStrategy() == ROI_CENTROID || getRootRoiStrategy() == ROI_CENTROID_WEIGHTED) {
			rootAllGraphsOnSomaCentroid(graphList, getRootRoiStrategy() == ROI_CENTROID_WEIGHTED);
			skeletonResult = null; // dispose temp resource
			return graphList;
		}
		for (final DirectedWeightedGraph graph : graphList) {
			SWCPoint root = null;
			if (getRootRoiStrategy() == ROI_EDGE)
				root = getFeaturedVertexInSoma(graph, true, true, true);
			else if (getRootRoiStrategy() == ROI_CONTAINED)
				root = getFeaturedVertexInSoma(graph, true, true, false);
			if (root != null) {
				graph.setRoot(root); // Assign the detected root
			} else if (!convertToDirected(graph)) {
				// If that did not work, enforce a consistent edge direction
				SNTUtils.log("Graph w/ multiple components and/or inconsistent edge directions!? Skipping...");
			}
			graph.updateVertexProperties();
		}
		skeletonResult = null; // dispose temp resource
		return graphList;
	}

	/**
	 * Generates a single {@link DirectedWeightedGraph}s by combining
	 * {@link #getGraphs()}'s list into a single, combined graph. Typically, this
	 * method assumes that the skeletonization handles a known single component
	 * (e.g., an image of a single cell). If multiple graphs() do exist, this method
	 * requires that {@link #setRootRoi(Roi, int)} has been called using
	 * {@link #ROI_CENTROID} or {@link #ROI_CENTROID_WEIGHTED}.
	 * 
	 * @return the single graph
	 */
	public DirectedWeightedGraph getSingleGraph() throws IllegalArgumentException {
		final List<DirectedWeightedGraph> graphs = getGraphs();
		if (graphs.size() == 1)
			return graphs.iterator().next();
		if (getRootRoiStrategy() != ROI_CENTROID && getRootRoiStrategy() != ROI_CENTROID_WEIGHTED) {
			throw new IllegalArgumentException(
					"Combining multiple graphs requires ROI_CENTROID or ROI_CENTROID_WEIGHTED strategy");
		}
		final SWCPoint commonRoot = graphs.iterator().next().getRoot();
		final DirectedWeightedGraph holder = new DirectedWeightedGraph();
		holder.addVertex(commonRoot);
		graphs.forEach(g -> Graphs.addGraph(holder, g));
		holder.setRoot(commonRoot);
		holder.updateVertexProperties();
		return holder;
	}
	
	private void rootAllGraphsOnSomaCentroid(final List<DirectedWeightedGraph> graphs, final boolean weightedCentroid) {
		if (somaRoi == null)
			return;
		final List<SWCPoint> allSomaticEndPoints = new ArrayList<>();
		final Roi roi = getEnlargedSomaAsNeeded();
		for (final DirectedWeightedGraph graph : graphs) {
			allSomaticEndPoints.addAll(getGraphNodesAssociatedWithRoi(getSkeletonResult().getListOfEndPoints(), roi,
					somaRoi.getZPosition(), graph, false));
		}
		if (allSomaticEndPoints.isEmpty())
			return;
		final SWCPoint newRoot = getCentroid(allSomaticEndPoints);
		if (newRoot == null)
			throw new IllegalArgumentException(
					"Centroid could not be computed. No end-points in ROI or invalid root strategy!?");

		if (!weightedCentroid || allSomaticEndPoints.size() == 1) {
			// XYZ coordinates of root become the those of the soma ROI centroid
			// (conversion to physical coordinates needed)
			final double[] xyCentroid = somaRoi.getContourCentroid();
			newRoot.x = xyCentroid[0] * pixelWidth;
			newRoot.y = xyCentroid[1] * pixelHeight;
		}
		final int uniqueId = -2; // will be updated when graph rebuilt
		newRoot.id = uniqueId;
		graphs.forEach(g -> {
			final SWCPoint refRoot = getNearestPoint(g.vertexSet(), newRoot);
			g.addVertex(newRoot);
			g.addEdge(refRoot, newRoot);
			g.setRoot(newRoot);
			g.updateVertexProperties();
		});
	}

	private SWCPoint getCentroid(final List<SWCPoint> points) {
		if (points == null)
			return null;
		final PointInImage cntrd = SNTPoint.average(points);
		return new SWCPoint(-1, -1, cntrd.x, cntrd.y, cntrd.z, 0, -1);
	}
	
	private SWCPoint getNearestPoint(final Collection<SWCPoint> points, final SWCPoint target) {
		double distanceSquaredToNearestParentPoint = Double.MAX_VALUE;
		SWCPoint nearest = null;
		for (final SWCPoint s : points) {
			if (s == null) continue;
			final double distanceSquared = target.distanceSquaredTo(s.x, s.y, s.z);
			if (distanceSquared < distanceSquaredToNearestParentPoint) {
				nearest = s;
				distanceSquaredToNearestParentPoint = distanceSquared;
			}
		}
		return nearest;
	}

	private Roi getEnlargedSomaAsNeeded() {
		// If no skeletonization occurred inside the soma ROI, we need to enlarge its
		// perimeter by a bit when checking for nodes that would be "inside" the
		// original ROI. We do this to ensure the closest graph node is indeed
		// associated with the XY coordinates of the soma
		return (getRootRoiStrategy() == ROI_CONTAINED) ? somaRoi : RoiConverter.enlarge(somaRoi, 4);
	}

	private SWCPoint getFeaturedVertexInSoma(final DirectedWeightedGraph graph, final boolean tip,
			final boolean junction, final boolean slab) {
		if (somaRoi == null)
			return null;
		final Roi soma = getEnlargedSomaAsNeeded();
		int somaSlice = somaRoi.getZPosition();
		final List<SWCPoint> result = new ArrayList<>();
		if (tip) {
			final SWCPoint nearestTip = getFirstGraphNodeAssociatedWithRoi(getSkeletonResult().getListOfEndPoints(),
					soma, somaSlice, graph);
			if (nearestTip != null)
				result.add(nearestTip);
		}
		if (junction) {
			final SWCPoint nearestJunction = getFirstGraphNodeAssociatedWithRoi(
					getSkeletonResult().getListOfJunctionVoxels(), soma, somaSlice, graph);
			if (nearestJunction != null)
				result.add(nearestJunction);
		}
		if (slab) {
			final SWCPoint nearetSlab = getFirstGraphNodeAssociatedWithRoi(getSkeletonResult().getListOfSlabVoxels(),
					soma, somaSlice, graph);
			if (nearetSlab != null)
				result.add(nearetSlab);
		}
		return result.stream().filter(Objects::nonNull).findFirst().orElse(null);
	}

	private List<SWCPoint> getGraphNodesAssociatedWithRoi(final List<Point> skelPointList, final Roi roi,
			final int roiSlice, final DirectedWeightedGraph graph, final boolean firstNodeOnly) {
		final List<SWCPoint> result = new ArrayList<>();
		for (final Point p : skelPointList) {
			if (roiSlice > 0 && p.z != roiSlice)
				continue;
			if (roi.containsPoint(p.x, p.y)) {
				final SWCPoint match = getMatchingLocationInGraph(
						new PointInImage(p.x * pixelWidth, p.y * pixelHeight, p.z * pixelDepth), graph);
				if (match != null) {
					result.add(match);
					if (firstNodeOnly)
						return result;
				}

			}
		}
		return result;
	}

	private SWCPoint getFirstGraphNodeAssociatedWithRoi(final List<Point> skelPointList, final Roi roi,
			final int roiSlice, final DirectedWeightedGraph graph) {
		final List<SWCPoint> result = getGraphNodesAssociatedWithRoi(skelPointList, roi, roiSlice, graph, true);
		return (result.isEmpty()) ? null : result.get(0);
	}

	private SWCPoint getMatchingLocationInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
		if (point != null) {
			for (final SWCPoint p : graph.vertexSet()) {
				if (p.isSameLocation(point)) {
					return p;
				}
			}
		}
		return null;
	}

	/**
	 * Sets the Roi enclosing the nodes to be set as root(s) in the final graphs.
	 * Must be called before retrieval of any converted data.
	 * 
	 * @param roi      The area enclosing the components defining the root(s) of the
	 *                 skeletonized structures. Typically this will correspond to an
	 *                 area ROI delineating the soma. Note that by default ImageJ
	 *                 ROIs do not carry depth information, so if you would like to
	 *                 restrain the delineation to a single plane, be sure to call
	 *                 {@link Roi#setPosition(int, int, int)} beforehand.
	 * @param strategy the strategy for root placement: Either
	 *                 {@link #ROI_CENTROID}, {@link #ROI_CENTROID_WEIGHTED},
	 *                 {@link #ROI_CONTAINED}, {@link #ROI_EDGE}, or
	 *                 {@link #ROI_UNSET}
	 */
	public void setRootRoi(final Roi roi, final int strategy) {
		if (roi != null && !roi.isArea())
			throw new IllegalArgumentException("Only area ROIs supported");
		if (strategy != ROI_CENTROID && strategy != ROI_CENTROID_WEIGHTED && strategy != ROI_CONTAINED
				&& strategy != ROI_EDGE && strategy != ROI_UNSET)
			throw new IllegalArgumentException("Not a valid flag for root placement strategy");
		if (strategy != ROI_UNSET && roi == null)
			throw new IllegalArgumentException("Only ROI_UNSET can be used with a null ROI");
		somaRoi = roi;
		this.strategy = strategy;
		if (roi != null && (strategy == ROI_EDGE || strategy == ROI_CENTROID || strategy == ROI_CENTROID_WEIGHTED)) {
			final Roi existingROi = imp.getRoi();
			imp.setRoi(roi);
			final int roiSlice = roi.getZPosition();
			// if ROI is not associated with a particular slice, clear entire stack
			final int minZ = (roiSlice == 0) ? 1 : roiSlice;
			final int maxZ = (roiSlice == 0) ? 1 : imp.getNSlices();
			for (int z = minZ; z <= maxZ; z++) {
				final ImageProcessor ip = imp.getImageStack().getProcessor(z);
				ip.setColor(0);
				ip.fill(roi);
			}
			imp.setRoi(existingROi);
		}
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
    public int getRootRoiStrategy() {
        return (somaRoi ==null) ? ROI_UNSET : strategy;
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
	 * Sets the loop pruning strategy. See <a href=
	 * "https://imagej.net/plugins/analyze-skeleton/?amp=1#loop-detection-and-pruning">AnalyzeSkeleton
	 * documentation</a>
	 *
	 * @param pruneMode the loop prune strategy, e.g., {@link #SHORTEST_BRANCH},
	 *                  {@link #LOWEST_INTENSITY_BRANCH} or
	 *                  {@link #LOWEST_INTENSITY_VOXEL}
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
    	if (skeletonResult == null) {
    		final AnalyzeSkeleton_ skeleton = new AnalyzeSkeleton_();
    		skeleton.setup("", imp);
    		skeletonResult = skeleton.run(pruneMode, pruneEnds, shortestPath, origIP, silent, verbose);
    	}
        return skeletonResult;
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
    private boolean convertToDirected(final DirectedWeightedGraph sntGraph) {
        final SWCPoint root = sntGraph.vertexSet().stream()
                .filter(v -> sntGraph.degreeOf(v) == 1).findFirst().orElse(null);
        if (root == null) {
            return false;
        }
        sntGraph.setRoot(root);
        return true;
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
