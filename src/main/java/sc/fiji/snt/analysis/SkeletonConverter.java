/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import ij.ImagePlus;
import sc.fiji.analyzeSkeleton.*;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.*;

/**
 * Class for generation of {@link Tree}s from a skeletonized {@link ImagePlus}.
 *
 * @see sc.fiji.skeletonize3D.Skeletonize3D_
 * @see AnalyzeSkeleton_
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class SkeletonConverter {

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

    public SkeletonConverter(final ImagePlus imagePlus) {
        this.imp = imagePlus;
    }

    /**
     * Generates a list of {@link Tree}s from the skeleton image.
     * Each Tree corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     * @return the skeleton tree list
     */
    public List<Tree> getTrees() {
        final Graph[] skeletonGraphs = getSkeletonGraphs();
        final List<Tree> treeList = new ArrayList<>();
        for (final Graph skelGraph : skeletonGraphs) {
            treeList.add(treeFromSkeletonGraph(skelGraph));
        }
        return treeList;
    }

    /**
     * Sets the original {@link ImagePlus} to be used during voxel-based loop pruning.
     * See <a href="https://imagej.net/AnalyzeSkeleton.html#Loop_detection_and_pruning">AnalyzeSkeleton documentation</a>
     *
     * @param origIP the original ImagePlus
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean) 
     */
    public void setOrigIP(ImagePlus origIP) {
        this.origIP = origIP;
    }

    /**
     * Sets the loop pruning strategy.
     * See <a href="https://imagej.net/AnalyzeSkeleton.html#Loop_detection_and_pruning">AnalyzeSkeleton documentation</a>
     * @param pruneMode the loop prune strategy, e.g., {@link AnalyzeSkeleton_#SHORTEST_BRANCH},
     * {@link AnalyzeSkeleton_#LOWEST_INTENSITY_BRANCH} or {@link AnalyzeSkeleton_#LOWEST_INTENSITY_VOXEL}
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean) 
     */
    public void setPruneMode(int pruneMode) {
        this.pruneMode = pruneMode;
    }

    /**
     * Sets whether or not to prune branches which end in end-points from the result.
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean) 
     */
    public void setPruneEnds(boolean pruneEnds) {
        this.pruneEnds = pruneEnds;
    }

    /**
     * Sets whether or not to calculate the longest shortest-path in the skeleton result.
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean) 
     */
    public void setShortestPath(boolean shortestPath) {
        this.shortestPath = shortestPath;
    }

    /**
     * Setting this to false will display both the tagged skeleton image and the shortest path image (if the
     * shortest path calculation is enabled).
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
     * Sets whether or not to prune branches below a threshold length from the result.
     */
    public void setPruneByLength(boolean pruneByLength) { this.pruneByLength = pruneByLength; }

    /**
     * The minimum branch length necessary to avoid pruning. This value is only used
     * if {@link SkeletonConverter#pruneByLength} is true.
     * @see SkeletonConverter#setPruneByLength(boolean)
     * @param lengthThreshold the length threshold
     */
    public void setLengthThreshold(double lengthThreshold) {
        if (lengthThreshold < 0) {
            lengthThreshold = 0;
        }
        this.lengthThreshold = lengthThreshold;
    }

    /**
     * Runs AnalyzeSkeleton on the image and gets the Graph Array returned by {@link SkeletonResult#getGraph()}
     */
    private Graph[] getSkeletonGraphs() {
        final AnalyzeSkeleton_ skeleton = new AnalyzeSkeleton_();
        skeleton.setup("", imp);
        SkeletonResult skeletonResult;
        if (pruneByLength) {
            skeletonResult = skeleton.run(pruneMode, lengthThreshold, shortestPath, origIP, silent, verbose);
        } else {
            skeletonResult = skeleton.run(pruneMode, pruneEnds, shortestPath, origIP, silent, verbose);
        }
        return skeletonResult.getGraph();
    }

    /**
     * Convert the AnalyzeSkeleton {@link Graph} object to an SNT {@link Tree}, using a {@link DirectedWeightedGraph}
     * as an intermediary data structure.
     */
    private Tree treeFromSkeletonGraph(final Graph skeletonGraph) {
        final DirectedWeightedGraph sntGraph = new DirectedWeightedGraph();
        final Map<Point, SWCPoint> pointMap = new HashMap<>();
        for (final Vertex vertex : skeletonGraph.getVertices()) {
            final Point v = vertex.getPoints().get(0);
            /* Use dummy values for all fields except the point coordinates.
            These will be assigned real values automatically during conversion to Tree. */
            final SWCPoint swcPoint = new SWCPoint(0,0,v.x, v.y, v.z, 0, -1);
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
            SWCPoint swcSlab = new SWCPoint(0, 0, slabs.get(0).x, slabs.get(0).y, slabs.get(0).z, 0, -1);
            pointMap.put(slabs.get(0), swcSlab);
            sntGraph.addVertex(swcSlab);
            for (int i = 1; i < slabs.size(); i++) {
                swcSlab = new SWCPoint(0, 0, slabs.get(i).x, slabs.get(i).y, slabs.get(i).z, 0, -1);
                pointMap.put(slabs.get(i), swcSlab);
                sntGraph.addVertex(swcSlab);
                sntGraph.addEdge(pointMap.get(slabs.get(i - 1)), swcSlab);
            }
            sntGraph.addEdge(p1, pointMap.get(slabs.get(0)));
            sntGraph.addEdge(pointMap.get(slabs.get(slabs.size() - 1)), p2);
        }
        convertToDirected(sntGraph);
        final Tree tree = sntGraph.getTree();
        /* Assign image calibration to tree. Avoids unexpected offsets when initializing SNT */
        tree.assignImage(imp);
        return tree;
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
        final Stack<SWCPoint> stack = new Stack<>();
        stack.push(root);
        final Set<SWCPoint> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            final SWCPoint swcPoint = stack.pop();
            visited.add(swcPoint);
            SWCPoint newTarget = null;
            for (final SWCWeightedEdge edge : sntGraph.edgesOf(swcPoint)) {
                if (edge.getSource() == swcPoint) {
                    newTarget = edge.getTarget();
                } else if (edge.getTarget() == swcPoint) {
                    newTarget = edge.getSource();
                }
                if (visited.contains(newTarget)) continue;
                sntGraph.removeEdge(edge);
                sntGraph.addEdge(swcPoint, newTarget);
                stack.push(newTarget);
            }
        }
    }

    /* IDE debug method */
    public static void main(String[] args) {
        //IJ.open("C:\\Users\\cam\\Desktop\\Drosophila_ddaC_Neuron.tif\\");
        //ImagePlus imp = IJ.getImage();
        ImagePlus imp = new SNTService().demoTrees().get(0).getSkeleton();
        SkeletonConverter converter = new SkeletonConverter(imp);
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
