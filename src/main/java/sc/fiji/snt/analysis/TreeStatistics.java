/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;
import org.scijava.app.StatusService;
import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.table.DefaultGenericTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.analysis.AnalysisUtils.HistogramDatasetPlus;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes summary and descriptive statistics from properties of Paths and Nodes in a {@link Tree}, including
 * convenience methods to plot distributions of such data. For analysis of groups of Trees have a look at
 * {@link MultiTreeStatistics} and {@link GroupedTreeStatistics}.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class TreeStatistics extends ContextCommand {

    // branch angles
    /** Flag specifying Branch extension angle XY */
    public static final String BRANCH_EXTENSION_ANGLE_XY = "Branch extension angle XY";
    /** Flag specifying Branch extension angle XZ */
    public static final String BRANCH_EXTENSION_ANGLE_XZ = "Branch extension angle XZ";
    /** Flag specifying Branch extension angle ZY */
    public static final String BRANCH_EXTENSION_ANGLE_ZY = "Branch extension angle ZY";
    /** Flag specifying "Inner branches: Extension angle XY" */
    public static final String INNER_EXTENSION_ANGLE_XY = "Inner branches: Extension angle XY";
    /** Flag specifying "Inner branches: Extension angle XZ" */
    public static final String INNER_EXTENSION_ANGLE_XZ = "Inner branches: Extension angle XZ";
    /** Flag specifying "Inner branches: Extension angle ZY" */
    public static final String INNER_EXTENSION_ANGLE_ZY = "Inner branches: Extension angle ZY";
    /** Flag specifying "Primary branches: Extension angle XY" */
    public static final String PRIMARY_EXTENSION_ANGLE_XY = "Primary branches: Extension angle XY";
    /** Flag specifying "Primary branches: Extension angle XZ" */
    public static final String PRIMARY_EXTENSION_ANGLE_XZ = "Primary branches: Extension angle XZ";
    /** Flag specifying "Primary branches: Extension angle ZY" */
    public static final String PRIMARY_EXTENSION_ANGLE_ZY = "Primary branches: Extension angle ZY";
    /** Flag specifying "Terminal branches: Extension angle XY" */
    public static final String TERMINAL_EXTENSION_ANGLE_XY = "Terminal branches: Extension angle XY";
    /** Flag specifying "Terminal branches: Extension angle XZ" */
    public static final String TERMINAL_EXTENSION_ANGLE_XZ = "Terminal branches: Extension angle XZ";
    /** Flag specifying "Terminal branches: Extension angle ZY" */
    public static final String TERMINAL_EXTENSION_ANGLE_ZY = "Terminal branches: Extension angle ZY";
    /** Flag specifying "Remote bifurcation angles" */
    public static final String REMOTE_BIF_ANGLES = "Remote bif. angles";
    // paths
    /** Flag specifying "Path length" */
    public static final String PATH_LENGTH = "Path length";
    /** Flag specifying "Path extension angle XY" */
    public static final String PATH_EXT_ANGLE_XY = "Path extension angle XY";
    /** Flag specifying "Path extension angle XZ" */
    public static final String PATH_EXT_ANGLE_XZ = "Path extension angle XZ";
    /** Flag specifying "Path extension angle ZY" */
    public static final String PATH_EXT_ANGLE_ZY = "Path extension angle ZY";
    /** Flag specifying "Path extension angle XY (Rel.)"*/
    public static final String PATH_EXT_ANGLE_REL_XY = "Path extension angle XY (Rel.)";
    /** Flag specifying "Path extension angle XZ (Rel.)"*/
    public static final String PATH_EXT_ANGLE_REL_XZ = "Path extension angle XZ (Rel.)";
    /** Flag specifying "Path extension angle ZY (Rel.)"*/
    public static final String PATH_EXT_ANGLE_REL_ZY = "Path extension angle ZY (Rel.)";
    /** Flag specifying "Path order" */
    public static final String PATH_ORDER = "Path order";
    /** Flag specifying "Path channel" */
    public static final String PATH_CHANNEL = "Path channel";
    /** Flag specifying "Path frame" */
    public static final String PATH_FRAME = "Path frame";
    /** Flag specifying "Path mean radius" */
    public static final String PATH_MEAN_RADIUS = "Path mean radius";
    /** Flag specifying "Path spine/varicosity density" */
    public static final String PATH_SPINE_DENSITY = "Path spine/varicosity density";
    /** Flag specifying "Path contraction" */
    public static final String PATH_CONTRACTION = "Path contraction";
    /** Flag specifying "Path fractal dimension" */
    public static final String PATH_FRACTAL_DIMENSION = "Path fractal dimension";
    // branches
    /** Flag specifying "Branch length" */
    public static final String BRANCH_LENGTH = "Branch length";
    /** Flag specifying "Branch mean radius" */
    public static final String BRANCH_MEAN_RADIUS = "Branch mean radius";
    /** Flag specifying "Terminal branches: Length" */
    public static final String TERMINAL_LENGTH = "Terminal branches: Length";
    /** Flag specifying "Primary branches: Length" */
    public static final String PRIMARY_LENGTH = "Primary branches: Length";
    /** Flag specifying "Inner branches: Length" */
    public static final String INNER_LENGTH = "Inner branches: Length";
    /** Flag specifying "Branch contraction" */
    public static final String BRANCH_CONTRACTION = "Branch contraction";
    /** Flag specifying "Branch fractal dimension" */
    public static final String BRANCH_FRACTAL_DIMENSION = "Branch fractal dimension";
    // nodes
    /** Flag specifying "Node radius" */
    public static final String NODE_RADIUS = "Node radius";
    /** Flag specifying "Internode angle" */
    public static final String INTER_NODE_ANGLE = "Internode angle";
    /** Flag specifying "Internode distance" */
    public static final String INTER_NODE_DISTANCE = "Internode distance";
    /** Flag specifying "Internode distance (squared)" */
    public static final String INTER_NODE_DISTANCE_SQUARED = "Internode distance (squared)";
    // counts
    /** Flag specifying "No. of branch points" */
    public static final String N_BRANCH_POINTS = "No. of branch points";
    /** Flag specifying "No. of nodes" */
    public static final String N_NODES = "No. of nodes";
    /** Flag specifying "No. of path nodes (path fragmentation)" */
    public static final String N_PATH_NODES = "No. of path nodes (path fragmentation)";
    /** Flag specifying "No. of branch nodes (branch fragmentation)" */
    public static final String N_BRANCH_NODES = "No. of branch nodes (branch fragmentation)";
    /** Flag specifying "No. of paths" */
    public static final String N_PATHS = "No. of paths";
    /** Flag specifying "No. of spines/varicosities" */
    public static final String N_SPINES = "No. of spines/varicosities";
    /** Flag specifying "No. of branches" */
    public static final String N_BRANCHES = "No. of branches";
    /** Flag specifying "No. of primary branches" */
    public static final String N_PRIMARY_BRANCHES = "No. of primary branches";
    /** Flag specifying "No. of inner branches" */
    public static final String N_INNER_BRANCHES = "No. of inner branches";
    /** Flag specifying "No. of terminal branches" */
    public static final String N_TERMINAL_BRANCHES = "No. of terminal branches";
    /** Flag specifying "No. of tips" */
    public static final String N_TIPS = "No. of tips";
    /** Flag specifying "No. of fitted paths" */
    public static final String N_FITTED_PATHS = "No. of fitted paths";
    /** Flag specifying "No. of spines/varicosities per path" */
    public static final String PATH_N_SPINES = "No. of spines/varicosities per path";
    //misc
    /** Flag specifying "Cable length" */
    public static final String LENGTH = "Cable length";
    /** Flag specifying "Complexity index: ACI" */
    public static final String COMPLEXITY_INDEX_ACI = "Complexity index: ACI";
    /** Flag specifying "Complexity index: DCI" */
    public static final String COMPLEXITY_INDEX_DCI = "Complexity index: DCI";
    /** Flag specifying "X coordinates" */
    public static final String X_COORDINATES = "X coordinates";
    /** Flag specifying "Y coordinates" */
    public static final String Y_COORDINATES = "Y coordinates";
    /** Flag specifying "Z coordinates" */
    public static final String Z_COORDINATES = "Z coordinates";
    /** Flag specifying "Width" */
    public static final String WIDTH = "Width";
    /** Flag specifying "Height" */
    public static final String HEIGHT = "Height";
    /** Flag specifying "Depth" */
    public static final String DEPTH = "Depth";
    /** Flag specifying "Partition asymmetry" */
    public static final String PARTITION_ASYMMETRY = "Partition asymmetry";
    // graph geodesics
    /** Flag specifying "Longest shortest path: Length" statistics. */
    public static final String GRAPH_DIAMETER = "Longest shortest path: Length";
    /** Flag specifying "Longest shortest path: Extension angle XY" statistics. */
    public static final String GRAPH_DIAMETER_ANGLE_XY = "Longest shortest path: Extension angle XY";
    /** Flag specifying "Longest shortest path: Extension angle XZ" statistics. */
    public static final String GRAPH_DIAMETER_ANGLE_XZ = "Longest shortest path: Extension angle XZ";
    /** Flag specifying "Longest shortest path: Extension angle ZY" statistics. */
    public static final String GRAPH_DIAMETER_ANGLE_ZY = "Longest shortest path: Extension angle ZY";
    // volume and surface
    /** Flag specifying Volume statistics. */
    public static final String VOLUME = "Volume";
    /** Flag specifying Branch volume statistics. */
    public static final String BRANCH_VOLUME = "Branch volume";
    /** Flag specifying Path volume statistics. */
    public static final String PATH_VOLUME = "Path volume";
    /** Flag specifying Surface area statistics. */
    public static final String SURFACE_AREA = "Surface area";
    /** Flag specifying Branch surface area statistics. */
    public static final String BRANCH_SURFACE_AREA = "Branch surface area";
    /** Flag specifying Path surface area statistics. */
    public static final String PATH_SURFACE_AREA = "Path surface area";
    // Strahler
    /** Flag specifying "Horton-Strahler root number" statistics. */
    public static final String STRAHLER_NUMBER = "Horton-Strahler root number";
    /** Flag specifying {@link StrahlerAnalyzer#getAvgBifurcationRatio() Horton-Strahler bifurcation ratio} statistics */
    public static final String STRAHLER_RATIO = "Horton-Strahler bifurcation ratio";
    // Sholl
    /** Flag specifying {@link sc.fiji.snt.analysis.sholl.math.LinearProfileStats#getMean() Sholl mean} statistics */
    public static final String SHOLL_MEAN_VALUE = "Sholl: " + ShollAnalyzer.MEAN;
    /** Flag specifying {@link sc.fiji.snt.analysis.sholl.math.LinearProfileStats#getSum() Sholl sum} statistics */
    public static final String SHOLL_SUM_VALUE = "Sholl: " + ShollAnalyzer.SUM;
    /** Flag specifying {@link sc.fiji.snt.analysis.sholl.math.LinearProfileStats#getMax() Sholl max} statistics */
    public static final String SHOLL_MAX_VALUE = "Sholl: " + ShollAnalyzer.MAX;
    /** Flag specifying "Sholl: No. maxima" statistics */
    public static final String SHOLL_N_MAX = "Sholl: " + ShollAnalyzer.N_MAX;
    /** Flag specifying "Sholl: No. secondary maxima" statistics */
    public static final String SHOLL_N_SECONDARY_MAX = "Sholl: " + ShollAnalyzer.N_SECONDARY_MAX;
    /** Flag specifying "Sholl: Decay" statistics */
    public static final String SHOLL_DECAY = "Sholl: " + ShollAnalyzer.DECAY;
    /** Flag specifying "Sholl: Max (fitted)" statistics */
    public static final String SHOLL_MAX_FITTED = "Sholl: " + ShollAnalyzer.MAX_FITTED;
    /** Flag specifying "Sholl: Max (fitted) radius" statistics */
    public static final String SHOLL_MAX_FITTED_RADIUS = "Sholl: " + ShollAnalyzer.MAX_FITTED_RADIUS;
    /** Flag specifying "Sholl: Degree of Polynomial fit" statistics */
    public static final String SHOLL_POLY_FIT_DEGREE = "Sholl: " + ShollAnalyzer.POLY_FIT_DEGREE;
    /** Flag specifying "Sholl: Kurtosis" statistics */
    public static final String SHOLL_KURTOSIS = "Sholl: " + ShollAnalyzer.KURTOSIS;
    /** Flag specifying "Sholl: Skewness" statistics */
    public static final String SHOLL_SKEWNESS = "Sholl: " + ShollAnalyzer.SKEWENESS;
    /** Flag specifying "Sholl: Ramification index" statistics */
    public static final String SHOLL_RAMIFICATION_INDEX = "Sholl: " + ShollAnalyzer.RAMIFICATION_INDEX;
    //convex hull
    /** Flag specifying "Convex hull: Boundary size" statistics */
    public static final String CONVEX_HULL_BOUNDARY_SIZE = "Convex hull: " + ConvexHullAnalyzer.BOUNDARY_SIZE;
    /** Flag specifying "Convex hull: Size" statistics */
    public static final String CONVEX_HULL_SIZE = "Convex hull: " + ConvexHullAnalyzer.SIZE;
    /** Flag specifying "Convex hull: Boxivity" statistics */
    public static final String CONVEX_HULL_BOXIVITY = "Convex hull: " + ConvexHullAnalyzer.BOXIVITY;
    /** Flag specifying "Convex hull: Elongation" statistics */
    public static final String CONVEX_HULL_ELONGATION = "Convex hull: " + ConvexHullAnalyzer.ELONGATION;
    /** Flag specifying "Convex hull: Roundness" statistics */
    public static final String CONVEX_HULL_ROUNDNESS = "Convex hull: " + ConvexHullAnalyzer.ROUNDNESS;
    /** Flag specifying "Convex hull: Centroid-root distance" statistics */
    public static final String CONVEX_HULL_CENTROID_ROOT_DISTANCE = "Convex hull: Centroid-root distance";

    /** Flag specifying "Root angles: Balancing factor */
    public static final String ROOT_ANGLE_B_FACTOR = "Root angles: " + RootAngleAnalyzer.BALANCING_FACTOR;
    /** Flag specifying "Root angles: Centripetal bias */
    public static final String ROOT_ANGLE_C_BIAS = "Root angles: " + RootAngleAnalyzer.CENTRIPETAL_BIAS;
    /** Flag specifying "Root angles: Mean direction */
    public static final String ROOT_ANGLE_M_DIRECTION = "Root angles: " + RootAngleAnalyzer.MEAN_DIRECTION;

    /**
     * Flag for analysis of Node intensity values, an optional numeric property that can
     * be assigned to Path nodes (e.g., voxel intensities), assigned via
     * {@link PathProfiler}. Note that an {@link IllegalArgumentException} is
     * triggered if no values have been assigned to the tree being analyzed.
     *
     * @see Path#hasNodeValues()
     * @see PathProfiler#assignValues()
     */
    public static final String VALUES = "Node intensity values";

    /** @deprecated Use {@link #BRANCH_CONTRACTION} or {@link #PATH_CONTRACTION} instead */
    @Deprecated
    public static final String CONTRACTION = "Contraction";
    /** @deprecated Use {@link #BRANCH_MEAN_RADIUS} or {@link #PATH_MEAN_RADIUS} instead */
    @Deprecated
    public static final String MEAN_RADIUS = PATH_MEAN_RADIUS;
    /** @deprecated Use {@link #PATH_SPINE_DENSITY} instead */
    @Deprecated
    public static final String AVG_SPINE_DENSITY = "Average spine/varicosity density";
    /** @deprecated Use {@link #BRANCH_FRACTAL_DIMENSION} or {@link #PATH_FRACTAL_DIMENSION} instead */
    @Deprecated
    public static final String FRACTAL_DIMENSION = "Fractal dimension";

    private static final String[] ALL_FLAGS = { //
            GRAPH_DIAMETER_ANGLE_XY, GRAPH_DIAMETER_ANGLE_XZ, GRAPH_DIAMETER_ANGLE_ZY, //
            BRANCH_CONTRACTION, BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH, BRANCH_MEAN_RADIUS, BRANCH_SURFACE_AREA, //
            BRANCH_VOLUME, COMPLEXITY_INDEX_ACI, COMPLEXITY_INDEX_DCI, //
            CONVEX_HULL_BOUNDARY_SIZE, CONVEX_HULL_BOXIVITY, CONVEX_HULL_CENTROID_ROOT_DISTANCE, CONVEX_HULL_ELONGATION, //
            CONVEX_HULL_ROUNDNESS, CONVEX_HULL_SIZE, //
            DEPTH, BRANCH_EXTENSION_ANGLE_XY, BRANCH_EXTENSION_ANGLE_XZ, BRANCH_EXTENSION_ANGLE_ZY, //
            INNER_EXTENSION_ANGLE_XY, INNER_EXTENSION_ANGLE_XZ, INNER_EXTENSION_ANGLE_ZY, //
            PRIMARY_EXTENSION_ANGLE_XY, PRIMARY_EXTENSION_ANGLE_XZ, PRIMARY_EXTENSION_ANGLE_ZY, //
            TERMINAL_EXTENSION_ANGLE_XY, TERMINAL_EXTENSION_ANGLE_XZ, TERMINAL_EXTENSION_ANGLE_ZY, //
            GRAPH_DIAMETER, HEIGHT, INNER_LENGTH, //
            INTER_NODE_ANGLE, INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, LENGTH, //
            N_BRANCH_NODES, N_BRANCH_POINTS, N_BRANCHES, N_FITTED_PATHS, N_INNER_BRANCHES, N_NODES, N_PATH_NODES, N_PATHS, //
            N_PRIMARY_BRANCHES, N_SPINES, N_TERMINAL_BRANCHES, N_TIPS, NODE_RADIUS, PARTITION_ASYMMETRY, PATH_CHANNEL, //
            PATH_CONTRACTION, PATH_FRACTAL_DIMENSION, PATH_FRAME, PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY, //
            PATH_EXT_ANGLE_REL_XY, PATH_EXT_ANGLE_REL_XZ, PATH_EXT_ANGLE_REL_ZY, PATH_LENGTH, PATH_MEAN_RADIUS, PATH_SPINE_DENSITY, //
            PATH_N_SPINES, PATH_ORDER, PATH_SURFACE_AREA, PATH_VOLUME, PRIMARY_LENGTH, REMOTE_BIF_ANGLES, //
            ROOT_ANGLE_B_FACTOR, ROOT_ANGLE_C_BIAS, ROOT_ANGLE_M_DIRECTION, //
            SHOLL_DECAY, SHOLL_KURTOSIS, SHOLL_MAX_FITTED, SHOLL_MAX_FITTED_RADIUS, SHOLL_MAX_VALUE, SHOLL_MEAN_VALUE, SHOLL_N_MAX, //
            SHOLL_N_SECONDARY_MAX, SHOLL_POLY_FIT_DEGREE, SHOLL_RAMIFICATION_INDEX, SHOLL_SKEWNESS, SHOLL_SUM_VALUE, //
            STRAHLER_NUMBER, STRAHLER_RATIO, SURFACE_AREA, TERMINAL_LENGTH, VALUES, VOLUME, WIDTH, X_COORDINATES, //
            Y_COORDINATES, Z_COORDINATES//
    };

    private static boolean exactMetricMatch;
    @Parameter
    protected StatusService statusService;
    @Parameter
    protected DisplayService displayService;
    protected Tree tree;
    protected List<Path> primaryBranches;
    protected List<Path> innerBranches;
    protected List<Path> terminalBranches;
    protected Set<PointInImage> tips;
    protected DefaultGenericTable table;
    protected LastDstats lastDstats;
    private Tree unfilteredTree;
    private Set<PointInImage> joints;
    private String tableTitle;
    private StrahlerAnalyzer sAnalyzer;
    private ShollAnalyzer shllAnalyzer;
    private int fittedPathsCounter = 0;
    private final int unfilteredPathsFittedPathsCounter;
    private ConvexHullAnalyzer convexAnalyzer;
    private RootAngleAnalyzer rootAngleAnalyzer;


    /**
     * Instantiates a new instance from a collection of Paths
     *
     * @param tree the collection of paths to be analyzed
     */
    public TreeStatistics(final Tree tree) {
        this.tree = tree;
        unfilteredPathsFittedPathsCounter = 0;
        fittedPathsCounter = 0;
    }

    /**
     * Instantiates Tree statistics from a collection of paths.
     *
     * @param paths Collection of Paths to be analyzed. Note that null Paths are
     *              discarded. Also, when a Path has been fitted and
     *              {@link Path#getUseFitted()} is true, its fitted 'flavor' is used.
     * @param label the label describing the path collection
     * @see #getParsedTree()
     */
    public TreeStatistics(final Collection<Path> paths, String label) {
        tree = new Tree(paths);
        tree.setLabel(label);
        for (final Path p : tree.list()) {
            // If fitted flavor of path exists use it instead
            if (p.getUseFitted()) {
                fittedPathsCounter++;
            }
        }
        unfilteredPathsFittedPathsCounter = fittedPathsCounter;
    }

    /**
     * Gets the list of supported metrics.
     *
     * @return the list of available metrics
     */
    public static List<String> getAllMetrics() {
        return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
    }

    /**
     * Gets a subset of supported metrics.
     *
     * @param type the type. Either 'legacy' (metrics supported up to SNTv4.0.5),
     *             "safe" (metrics that can be computed from invalid graphs),
     *             'common' (commonly used metrics), 'quick' (used by the 'quick
     *             measure' GUI commands), or 'all' (shortcut to {@link #getAllMetrics()})
     * @return the list metrics
     */
    public static List<String> getMetrics(final String type) {
        // We could use Arrays.asList() here but that would make list immutable
        String[] metrics;
        switch (type) {
            case "all":
                return getAllMetrics();
            case "legacy":
                // Historical metrics up to SNTv4.0.10
                metrics = new String[]{BRANCH_LENGTH, CONTRACTION, REMOTE_BIF_ANGLES, PARTITION_ASYMMETRY, FRACTAL_DIMENSION, //
                        INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, MEAN_RADIUS, AVG_SPINE_DENSITY, N_BRANCH_POINTS, //
                        N_NODES, N_SPINES, NODE_RADIUS, PATH_CHANNEL, PATH_FRAME, PATH_LENGTH, PATH_ORDER, PRIMARY_LENGTH, //
                        INNER_LENGTH, TERMINAL_LENGTH, VALUES, X_COORDINATES, Y_COORDINATES, Z_COORDINATES};
                break;
            case "deprecated":
                metrics = new String[]{AVG_SPINE_DENSITY, CONTRACTION, FRACTAL_DIMENSION, MEAN_RADIUS, AVG_SPINE_DENSITY};
                break;
            case "safe":
                metrics = new String[]{INTER_NODE_ANGLE, //
                        INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, N_BRANCH_POINTS, N_FITTED_PATHS, N_NODES, //
                        N_PATH_NODES, N_PATHS, N_SPINES, N_TIPS, NODE_RADIUS, PATH_CHANNEL, PATH_CONTRACTION, PATH_FRAME, //
                        PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY, PATH_EXT_ANGLE_REL_XY, PATH_EXT_ANGLE_REL_XZ, //
                        PATH_EXT_ANGLE_REL_ZY, PATH_LENGTH, PATH_MEAN_RADIUS, PATH_SPINE_DENSITY, PATH_N_SPINES, PATH_ORDER, //
                        PATH_SURFACE_AREA, PATH_VOLUME, VALUES, X_COORDINATES, Y_COORDINATES, Z_COORDINATES};
                break;
            case "common":
                metrics = new String[]{BRANCH_CONTRACTION, BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH, BRANCH_MEAN_RADIUS, //
                        BRANCH_SURFACE_AREA, BRANCH_VOLUME, COMPLEXITY_INDEX_ACI, COMPLEXITY_INDEX_DCI, CONVEX_HULL_SIZE, //
                        DEPTH, INNER_LENGTH, INTER_NODE_ANGLE, INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, LENGTH, //
                        N_BRANCH_POINTS, N_BRANCHES, N_INNER_BRANCHES, N_NODES, N_PRIMARY_BRANCHES, N_SPINES, //
                        N_TERMINAL_BRANCHES, N_TIPS, NODE_RADIUS, PARTITION_ASYMMETRY, PRIMARY_LENGTH, REMOTE_BIF_ANGLES, //
                        SHOLL_DECAY, SHOLL_MAX_VALUE, SHOLL_MAX_FITTED, SHOLL_MAX_FITTED_RADIUS, SHOLL_MEAN_VALUE, //
                        SURFACE_AREA, STRAHLER_NUMBER, TERMINAL_LENGTH, VALUES, VOLUME, X_COORDINATES, Y_COORDINATES, Z_COORDINATES};
                break;
            case "quick":
                metrics = new String[] { // Excludes metrics expected to be too specific or uncommon for most users
                        LENGTH, BRANCH_LENGTH, N_BRANCH_POINTS, N_TIPS, N_BRANCHES, //
                        N_PRIMARY_BRANCHES, N_TERMINAL_BRANCHES, PATH_MEAN_RADIUS, //
                        PATH_SPINE_DENSITY, STRAHLER_NUMBER, PATH_ORDER};
                break;
            default:
                throw new IllegalArgumentException("Unrecognized type");
        }
        return Arrays.stream(metrics).collect(Collectors.toList());
    }

    /**
     * Gets the list of most commonly used metrics.
     *
     * @return the list of commonly used metrics.
     * @see #getMetrics(String)
     */
    @Deprecated
    public static List<String> getMetrics() {
        return getMetrics("common");
    }

    protected static Map<BrainAnnotation, Double> getAnnotatedLength(final DirectedWeightedGraph graph, final int level, final char lr, final boolean norm) {
        final NodeStatistics<SWCPoint> nodeStats = new NodeStatistics<>(graph.vertexSet(lr));
        final Map<BrainAnnotation, Set<SWCPoint>> annotatedNodesMap = nodeStats.getAnnotatedNodes(level);
        final HashMap<BrainAnnotation, Double> lengthMap = new HashMap<>();
        for (final Map.Entry<BrainAnnotation, Set<SWCPoint>> entry : annotatedNodesMap.entrySet()) {
            final BrainAnnotation annotation = entry.getKey();
            final Set<SWCPoint> nodeSubset = entry.getValue();
            final DirectedWeightedSubgraph subgraph = graph.getSubgraph(nodeSubset);
            final double subgraphWeight = subgraph.sumEdgeWeights(true);
            lengthMap.put(annotation, subgraphWeight);
        }
        if (norm) {
            final double sumLength = graph.sumEdgeWeights();
            lengthMap.replaceAll((k, v) -> v / sumLength);
        }
        return lengthMap;
    }

    protected static Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final DirectedWeightedGraph graph, final int level, final boolean norm) {
        final char ipsiFlag = graph.getRoot().getHemisphere();
        if (ipsiFlag == BrainAnnotation.ANY_HEMISPHERE)
            throw new IllegalArgumentException("Tree's root has its hemisphere flag unset");
        final char contraFlag = (ipsiFlag == BrainAnnotation.LEFT_HEMISPHERE) ? BrainAnnotation.RIGHT_HEMISPHERE : BrainAnnotation.LEFT_HEMISPHERE;
        final Map<BrainAnnotation, Double> ipsiMap = getAnnotatedLength(graph, level, ipsiFlag, norm);
        final Map<BrainAnnotation, Double> contraMap = getAnnotatedLength(graph, level, contraFlag, norm);
        final Map<BrainAnnotation, double[]> finalMap = new HashMap<>();
        ipsiMap.forEach((k, ipsiLength) -> {
            double[] values = new double[2];
            final Double contraLength = contraMap.get(k);
            values[0] = ipsiLength;
            values[1] = (contraLength == null) ? 0d : contraLength;
            finalMap.put(k, values);
        });
        contraMap.keySet().removeIf(k -> ipsiMap.get(k) != null);
        contraMap.forEach((k, contraLength) -> finalMap.put(k, new double[]{0d, contraLength}));
        return finalMap;
    }

    /**
     * Creates a TreeStatistics instance from a group of Trees and a specific metric for convenient retrieval
     * of histograms
     * @param trees the collection of trees
     * @param metric the measurement
     * @return the TreeStatistics instance
     */
    public static TreeStatistics fromCollection(final Collection<Tree> trees, final String metric) {
        final Iterator<Tree> iterator = trees.iterator();
        final TreeStatistics holder = new TreeStatistics(iterator.next());
        if (trees.size() == 1) return holder;
        holder.tree.setLabel(getLabelFromTreeCollection(trees));
        final String normMeasurement = getNormalizedMeasurement(metric);
        final DescriptiveStatistics holderStats = holder.getDescriptiveStats(normMeasurement);
        while (iterator.hasNext()) {
            final Tree tree = iterator.next();
            final TreeStatistics treeStats = new TreeStatistics(tree);
            final DescriptiveStatistics dStats = treeStats.getDescriptiveStats(normMeasurement);
            for (final double v : dStats.getValues()) {
                holderStats.addValue(v);
            }
        }
        return holder;
    }

    private static String getLabelFromTreeCollection(final Collection<Tree> trees) {
        final StringBuilder sb = new StringBuilder();
        for (final Tree tree : trees) {
            if (tree.getLabel() != null) sb.append(tree.getLabel()).append(" ");
        }
        return (sb.isEmpty()) ? "Grouped Cells" : sb.toString().trim();
    }

    protected static String tryReallyHardToGuessMetric(final String guess) {
        final String normGuess = guess.toLowerCase();
        if (normGuess.contains("contrac")) {
            return (normGuess.contains("path")) ? PATH_CONTRACTION : BRANCH_CONTRACTION;
        }
        if (normGuess.contains("remote") && normGuess.contains("angle")) {
            return REMOTE_BIF_ANGLES;
        }
        if (normGuess.contains("partition") && normGuess.contains("asymmetry")) {
            return PARTITION_ASYMMETRY;
        }
        if (normGuess.contains("fractal")) {
            return (normGuess.contains("path")) ? PATH_FRACTAL_DIMENSION : BRANCH_FRACTAL_DIMENSION;
        }
        if (normGuess.contains("length") || normGuess.contains("cable")) {
            if (normGuess.contains("term")) {
                return TERMINAL_LENGTH;
            } else if (normGuess.contains("prim")) {
                return PRIMARY_LENGTH;
            } else if (normGuess.contains("inner")) {
                return INNER_LENGTH;
            } else if (normGuess.contains("path")) {
                return PATH_LENGTH;
            } else {
                return BRANCH_LENGTH;
            }
        }
        if (normGuess.contains("angle")) {
            if (normGuess.contains("path") && normGuess.contains("ext")) {
                if (normGuess.contains("xz"))
                    return (normGuess.contains("rel")) ? PATH_EXT_ANGLE_REL_XZ : PATH_EXT_ANGLE_XZ;
                else if (normGuess.contains("zy"))
                    return (normGuess.contains("rel")) ? PATH_EXT_ANGLE_REL_ZY : PATH_EXT_ANGLE_ZY;
                else return (normGuess.contains("rel")) ? PATH_EXT_ANGLE_REL_XY : PATH_EXT_ANGLE_XY;
            } else if (normGuess.contains("term")) {
                if (normGuess.contains("xz")) return TERMINAL_EXTENSION_ANGLE_XZ;
                else if (normGuess.contains("zy")) return TERMINAL_EXTENSION_ANGLE_ZY;
                else return TERMINAL_EXTENSION_ANGLE_XY;
            } else if (normGuess.contains("prim")) {
                if (normGuess.contains("xz")) return PRIMARY_EXTENSION_ANGLE_XZ;
                else if (normGuess.contains("zy")) return PRIMARY_EXTENSION_ANGLE_ZY;
                else return PRIMARY_EXTENSION_ANGLE_XY;
            } else if (normGuess.contains("inner")) {
                if (normGuess.contains("xz")) return INNER_EXTENSION_ANGLE_XZ;
                else if (normGuess.contains("zy")) return INNER_EXTENSION_ANGLE_ZY;
                else return INNER_EXTENSION_ANGLE_XY;
            } else if (normGuess.contains("xz")) return BRANCH_EXTENSION_ANGLE_XZ;
            else if (normGuess.contains("zy")) return BRANCH_EXTENSION_ANGLE_ZY;
            else return BRANCH_EXTENSION_ANGLE_XY;
        }
        if (normGuess.contains("path") && normGuess.contains("order")) {
            return PATH_ORDER;
        }
        if (normGuess.contains("bp") || normGuess.contains("branch points") || normGuess.contains("junctions")) {
            return N_BRANCH_POINTS;
        }
        if (normGuess.contains("nodes")) {
            return N_NODES;
        }
        if (normGuess.contains("node") && (normGuess.contains("dis") || normGuess.contains("dx"))) {
            if (normGuess.contains("sq")) {
                return INTER_NODE_DISTANCE_SQUARED;
            } else if (normGuess.contains("angle")) {
                return INTER_NODE_ANGLE;
            } else {
                return INTER_NODE_DISTANCE;
            }
        }
        if (normGuess.contains("radi")) {
            if (normGuess.contains("mean") || normGuess.contains("avg") || normGuess.contains("average")) {
                return (normGuess.contains("path")) ? PATH_MEAN_RADIUS : BRANCH_MEAN_RADIUS;
            } else {
                return NODE_RADIUS;
            }
        }
        if (normGuess.contains("spines") || normGuess.contains("varicosities")) {
            if (normGuess.contains("dens") || normGuess.contains("path")) {
                return PATH_SPINE_DENSITY;
            } else {
                return N_SPINES;
            }
        }
        if (normGuess.contains("values") || normGuess.contains("intensit")) {
            return VALUES;
        }
        if (normGuess.matches(".*\\bx\\b.*")) {
            return X_COORDINATES;
        }
        if (normGuess.matches(".*\\by\\b.*")) {
            return Y_COORDINATES;
        }
        if (normGuess.matches(".*\\bz\\b.*")) {
            return Z_COORDINATES;
        }
        return "unknown";
    }

    protected static String getNormalizedMeasurement(final String measurement) {
        if (isExactMetricMatch()) return measurement;
        for (final String flag : ALL_FLAGS) {
            if (flag.equalsIgnoreCase(measurement)) return flag;
        }
        for (final String flag : MultiTreeStatistics.getMetrics()) {
            if (flag.equalsIgnoreCase(measurement)) return flag;
        }
        for (final String flag : getMetrics("deprecated")) {
            if (flag.equalsIgnoreCase(measurement)) return flag;
        }
        final String normMeasurement = tryReallyHardToGuessMetric(measurement);
        if (!measurement.equals(normMeasurement)) {
            SNTUtils.log("\"" + normMeasurement + "\" assumed");
            if ("unknown".equals(normMeasurement)) {
                throw new UnknownMetricException("Unrecognizable measurement! " + "Maybe you meant one of the following?: " + Arrays.toString(ALL_FLAGS));
            }
        }
        return normMeasurement;
    }

    /**
     * Checks if 'fuzzy matching' of metrics is active.
     * @return true if exact string matching is active, false if approximate matching is active
     */
    public static boolean isExactMetricMatch() {
        return exactMetricMatch;
    }

    /**
     * Sets whether approximate string matching ('fuzzy matching') should be used when retrieving metrics.
     * @param exactMetricMatch exact string matching is used if true, approximate if false
     */
    public static void setExactMetricMatch(final boolean exactMetricMatch) {
        TreeStatistics.exactMetricMatch = exactMetricMatch;
    }

    /**
     * Computes the specified metric.
     *
     * @param metric the single-value metric to be computed (case-insensitive). While it is expected to be an element
     *               of {@link #getAllMetrics()}, if {@link #isExactMetricMatch()} is set, {@code metric} can be
     *               specified in a loose manner: If {@code metric} is not initially recognized, a heuristic will match
     *               it to the closest entry in the list of possible metrics. E.g., "# bps", "n junctions", will be both
     *               mapped to  {@link #N_BRANCH_POINTS}. Details on the matching are printed to the Console when in
     *               debug mode.
     * @return the computed value (average if metric is associated with multiple values)
     * @throws UnknownMetricException if metric is not recognized
     * @see #getMetrics()
     * @see #setExactMetricMatch(boolean)
     */
    public Number getMetric(final String metric) throws UnknownMetricException {
        return switch (metric) {
            case MultiTreeStatistics.ASSIGNED_VALUE -> tree.getAssignedValue();
            case MultiTreeStatistics.AVG_BRANCH_LENGTH -> getAvgBranchLength();
            case MultiTreeStatistics.AVG_CONTRACTION -> getAvgContraction();
            case MultiTreeStatistics.AVG_FRAGMENTATION -> getAvgFragmentation();
            case MultiTreeStatistics.AVG_REMOTE_ANGLE -> getAvgRemoteBifAngle();
            case MultiTreeStatistics.AVG_PARTITION_ASYMMETRY -> getAvgPartitionAsymmetry();
            case MultiTreeStatistics.AVG_FRACTAL_DIMENSION -> getAvgFractalDimension();
            case MultiTreeStatistics.PRIMARY_LENGTH -> getPrimaryLength();
            case MultiTreeStatistics.TERMINAL_LENGTH -> getTerminalLength();
            case MultiTreeStatistics.INNER_LENGTH -> getInnerLength();
            case MultiTreeStatistics.HIGHEST_PATH_ORDER -> getHighestPathOrder();
            case AVG_SPINE_DENSITY -> getSpineOrVaricosityDensity();
            case DEPTH -> getDepth();
            case HEIGHT -> getHeight();
            case LENGTH -> getCableLength();
            case N_BRANCH_POINTS -> getBranchPoints().size();
            case N_BRANCHES -> getNBranches();
            case N_FITTED_PATHS -> getNFittedPaths();
            case N_NODES -> getNNodes();
            case N_PATHS -> getNPaths();
            case N_PRIMARY_BRANCHES -> getPrimaryBranches().size();
            case N_INNER_BRANCHES -> getInnerBranches().size();
            case N_TERMINAL_BRANCHES -> getTerminalBranches().size();
            case N_TIPS -> getTips().size();
            case N_SPINES -> getNoSpinesOrVaricosities();
            case STRAHLER_NUMBER -> getStrahlerNumber();
            case STRAHLER_RATIO -> getStrahlerBifurcationRatio();
            case WIDTH -> getWidth();
            default -> {
                if (metric.startsWith("Sholl: "))
                    yield getShollMetric(metric);
                yield new TreeStatistics(tree).getSummaryStats(metric).getMean();
            }
        };
    }

    /**
     * Restricts analysis to Paths sharing the specified SWC flag(s).
     *
     * @param types the allowed SWC flags (e.g., {@link Path#SWC_AXON}, etc.)
     */
    public void restrictToSWCType(final int... types) {
        initializeSnapshotTree();
        tree = tree.subTree(types);
    }

    private void initializeSnapshotTree() {
        if (unfilteredTree == null) {
            unfilteredTree = new Tree(tree.list());
            unfilteredTree.setLabel(tree.getLabel());
        }
        sAnalyzer = null; // reset Strahler analyzer
    }

    /**
     * Removes any filtering restrictions that may have been set. Once called,
     * subsequent analysis will use all paths initially parsed by the constructor.
     * Does nothing if no paths are currently being excluded from the analysis.
     */
    public void resetRestrictions() {
        if (unfilteredTree == null) return; // no filtering has occurred
        tree.replaceAll(unfilteredTree.list());
        joints = null;
        primaryBranches = null;
        innerBranches = null;
        terminalBranches = null;
        tips = null;
        sAnalyzer = null;
        shllAnalyzer = null;
        fittedPathsCounter = unfilteredPathsFittedPathsCounter;
        convexAnalyzer = null;
    }

    private void updateFittedPathsCounter(final Path filteredPath) {
        if (fittedPathsCounter > 0 && filteredPath.isFittedVersionOfAnotherPath()) fittedPathsCounter--;
    }

    /**
     * Returns the set of parsed Paths.
     *
     * @return the set of paths currently being considered for analysis.
     * @see #resetRestrictions()
     */
    public Tree getParsedTree() {
        return tree;
    }

    /**
     * Outputs a summary of the current analysis to the measurements table using the
     * default Tree label.
     *
     * @param groupByType if true measurements are grouped by SWC-type flag
     * @see #setTable(DefaultGenericTable)
     */
    public void summarize(final boolean groupByType) {
        summarize(tree.getLabel(), groupByType);
    }

    /**
     * Outputs a summary of the current analysis to the measurements table.
     *
     * @param rowHeader   the String to be used as label for the summary
     * @param groupByType if true measurements are grouped by SWC-type flag
     * @see #run()
     * @see #setTable(DefaultGenericTable)
     */
    public void summarize(final String rowHeader, final boolean groupByType) {
        measure(rowHeader, TreeStatistics.getMetrics("quick"), groupByType);
    }

    protected int getNextRow(final String rowHeader) {
        table.appendRow((rowHeader == null) ? "" : rowHeader);
        return table.getRowCount() - 1;
    }

    protected String getSWCTypesAsString() {
        final StringBuilder sb = new StringBuilder();
        final Set<Integer> types = tree.getSWCTypes(true);
        for (int type : types) {
            sb.append(Path.getSWCtypeName(type, true)).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Measures this Tree, outputting the result to the measurements table using
     * default row labels. If a Context has been specified, the table is updated.
     * Otherwise, table contents are printed to Console.
     *
     * @param metrics     the list of metrics to be computed. When null or an empty
     *                    collection is specified, {@link #getMetrics()} is used.
     * @param groupByType if false, metrics are computed to all branches in the
     *                    Tree. If true, measurements will be split by SWC type
     *                    annotations (axon, dendrite, etc.)
     * @see #setExactMetricMatch(boolean)
     */
    public void measure(Collection<String> metrics, boolean groupByType) {
        measure(tree.getLabel(), metrics, groupByType);
    }

    /**
     * Measures this Tree, outputting the result to the measurements table. If a
     * Context has been specified, the table is updated. Otherwise, table contents
     * are printed to Console.
     *
     * @param rowHeader   the row header label
     * @param metrics     the list of metrics to be computed. When null or an empty
     *                    collection is specified, {@link #getMetrics()} is used.
     * @param groupByType if false, metrics are computed to all branches in the
     *                    Tree. If true, measurements will be split by SWC type
     *                    annotations (axon, dendrite, etc.)
     * @see #setExactMetricMatch(boolean)
     */
    public void measure(final String rowHeader, final Collection<String> metrics, final boolean groupByType) {
        if (table == null) table = new SNTTable();
        final Collection<String> measuringMetrics = (metrics == null || metrics.isEmpty()) ? getMetrics() : metrics;
        for (final String metric : measuringMetrics) {
            final String unit = getUnit(metric);
            final String metricHeader = (unit.length() > 1) ? metric + " (" + unit + ")" : metric;
            if (groupByType) {
                for (final int type : tree.getSWCTypes(false)) {
                    restrictToSWCType(type);
                    logStatsToTable(metric, metricHeader, rowHeader);
                    resetRestrictions();
                }
            } else {
                logStatsToTable(metric, metricHeader, rowHeader);
            }
        }
        updateAndDisplayTable();
    }

    private void logStatsToTable(final String metric, final String metricHeader, final String rowHeader) {
        SummaryStatistics summaryStatistics;
        try {
            summaryStatistics = getSummaryStats(metric);
        } catch (final IllegalArgumentException | IndexOutOfBoundsException | NullPointerException e) {
            // e.g. Node values cannot be assigned or a convex hull of a single-path tree
            summaryStatistics = new SummaryStatistics();
            summaryStatistics.addValue(Double.NaN);
            SNTUtils.log(e.getMessage());
        }
        if (summaryStatistics.getN() == 1) {
            ((SNTTable) table).set(metricHeader + " [Single value]", rowHeader, summaryStatistics.getSum());
        } else {
            ((SNTTable) table).set(metricHeader + " [MIN]", rowHeader, summaryStatistics.getMin());
            ((SNTTable) table).set(metricHeader + " [MAX]", rowHeader, summaryStatistics.getMax());
            ((SNTTable) table).set(metricHeader + " [MEAN]", rowHeader, summaryStatistics.getMean());
            ((SNTTable) table).set(metricHeader + " [STD_DEV]", rowHeader, summaryStatistics.getStandardDeviation());
            ((SNTTable) table).set(metricHeader + " [N]", rowHeader, summaryStatistics.getN());
        }
        ((SNTTable) table).set("SWC Type(s)", rowHeader, getSWCTypesAsString());
    }

    /**
     * Computes the {@link SummaryStatistics} for the specified measurement.
     *
     * @param metric the measurement ({@link #N_NODES}, {@link #NODE_RADIUS}, etc.)
     * @return the SummaryStatistics object.
     */
    public SummaryStatistics getSummaryStats(final String metric) {
        final SummaryStatistics sStats = new SummaryStatistics();
        assembleStats(new StatisticsInstance(sStats), getNormalizedMeasurement(metric));
        return sStats;
    }

    /**
     * Computes the {@link DescriptiveStatistics} for the specified measurement.
     *
     * @param metric the measurement ({@link #N_NODES}, {@link #NODE_RADIUS}, etc.)
     * @return the DescriptiveStatistics object.
     */
    public DescriptiveStatistics getDescriptiveStats(final String metric) {
        final String normMeasurement = getNormalizedMeasurement(metric);
        if (!lastDstatsCanBeRecycled(normMeasurement)) {
            final DescriptiveStatistics dStats = new DescriptiveStatistics();
            assembleStats(new StatisticsInstance(dStats), normMeasurement);
            lastDstats = new LastDstats(normMeasurement, dStats);
        }
        return lastDstats.dStats;
    }

    /**
     * Sets the measurements table.
     *
     * @param table the table to be used by this TreeStatistics instance
     * @param title the title of the table display window
     */
    public void setTable(final DefaultGenericTable table, final String title) {
        this.table = table;
        this.tableTitle = title;
    }

    /**
     * Gets the table currently being used by this TreeStatistics instance
     *
     * @return the table
     */
    public DefaultGenericTable getTable() {
        return table;
    }

    /**
     * Sets the table for this TreeStatistics instance.
     *
     * @param table the table to be used by this TreeStatistics instance
     * @see #summarize(boolean)
     */
    public void setTable(final DefaultGenericTable table) {
        this.table = table;
    }

    /**
     * Retrieves the amount of cable length present on each brain compartment
     * innervated by the analyzed neuron.
     *
     * @param level the ontological depth of the compartments to be considered
     * @return the map containing the brain compartments as keys, and cable lengths
     * as values.
     * @see AllenCompartment#getOntologyDepth()
     */
    public Map<BrainAnnotation, Double> getAnnotatedLength(final int level) {
        return getAnnotatedLength(tree.getGraph(), level, BrainAnnotation.ANY_HEMISPHERE, false);
    }

    /**
     * Retrieves the amount of cable length present on each brain compartment
     * innervated by the analyzed neuron.
     *
     * @param level      the ontological depth of the compartments to be considered
     * @param hemisphere typically 'left' or 'right'. The hemisphere flag (
     *                   {@link BrainAnnotation#LEFT_HEMISPHERE} or
     *                   {@link BrainAnnotation#RIGHT_HEMISPHERE}) is extracted from
     *                   the first character of the string (case-insensitive).
     *                   Ignored if not a recognized option
     * @return the map containing the brain compartments as keys, and cable lengths
     * as values.
     * @see AllenCompartment#getOntologyDepth()
     */
    public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere) {
        return getAnnotatedLength(tree.getGraph(), level, BrainAnnotation.getHemisphereFlag(hemisphere), false);
    }

    /**
     * Retrieves the amount of cable length present on each brain compartment
     * innervated by the analyzed neuron.
     *
     * @param level      the ontological depth of the compartments to be considered
     * @param hemisphere typically 'left' or 'right'. The hemisphere flag (
     *                   {@link BrainAnnotation#LEFT_HEMISPHERE} or
     *                   {@link BrainAnnotation#RIGHT_HEMISPHERE}) is extracted from
     *                   the first character of the string (case-insensitive).
     *                   Ignored if not a recognized option
     * @param norm       whether length should be normalized to the cells' cable
     *                   length
     * @return the map containing the brain compartments as keys, and cable lengths
     * as values.
     * @see AllenCompartment#getOntologyDepth()
     */
    public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere, final boolean norm) {
        return getAnnotatedLength(tree.getGraph(), level, BrainAnnotation.getHemisphereFlag(hemisphere), norm);
    }

    /**
     * Retrieves the amount of cable length present on each brain compartment innervated by the analyzed neuron
     * in the two brain hemispheres. Lengths are absolute and not normalized to the cells' cable length.
     *
     * @param level      the ontological depth of the compartments to be considered
     * @return the map containing the brain compartments as keys, and cable lengths per hemisphere as values.
     * @see AllenCompartment#getOntologyDepth()
     */
    public Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final int level) {
        return getAnnotatedLengthsByHemisphere(tree.getGraph(), level, false);
    }

    /**
     * Retrieves the of cable length frequencies across brain areas.
     *
     * @return the histogram of cable length frequencies.
     */
    public SNTChart getAnnotatedLengthHistogram() {
        return getAnnotatedLengthHistogram(Integer.MAX_VALUE);
    }

    /**
     * Retrieves the histogram of cable length frequencies across brain areas of the
     * specified ontology level.
     *
     * @param depth the ontological depth of the compartments to be considered
     * @return the annotated length histogram
     * @see AllenCompartment#getOntologyDepth()
     */
    public SNTChart getAnnotatedLengthHistogram(final int depth) {
        final Map<BrainAnnotation, Double> map = getAnnotatedLength(depth);
        return getAnnotatedLengthHistogram(map, depth, "");
    }

    /**
     * Retrieves the histogram of cable length frequencies across brain areas of the
     * specified ontology level across the specified hemisphere.
     *
     * @param depth      the ontological depth of the compartments to be considered
     * @param hemisphere 'left', 'right' or 'ratio' (case-insensitive). Ignored if
     *                   not a recognized option
     * @return the annotated length histogram
     * @see AllenCompartment#getOntologyDepth()
     */
    public SNTChart getAnnotatedLengthHistogram(int depth, String hemisphere) {
        if ("ratio".equalsIgnoreCase(hemisphere.trim())) return getAnnotatedLengthsByHemisphereHistogram(depth);
        final Map<BrainAnnotation, Double> map = getAnnotatedLength(depth, hemisphere);

        String label;
        final char hemiFlag = BrainAnnotation.getHemisphereFlag(hemisphere);
        label = switch (hemiFlag) {
            case BrainAnnotation.LEFT_HEMISPHERE -> "Left hemi.";
            case BrainAnnotation.RIGHT_HEMISPHERE -> "Right hemi.";
            default -> "";
        };
        return getAnnotatedLengthHistogram(map, depth, label);
    }

    protected SNTChart getAnnotatedLengthsByHemisphereHistogram(int depth) {
        final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        Map<BrainAnnotation, double[]> seriesMap = getAnnotatedLengthsByHemisphere(depth);
        Map<BrainAnnotation, double[]> undefinedLengthMap = new HashMap<>();
        seriesMap.entrySet().stream().sorted((e1, e2) -> -Double.compare(e1.getValue()[0], e2.getValue()[0])).forEach(entry -> {
            if (entry.getKey() == null || entry.getKey().getOntologyDepth() == 0) {
                // a null brain annotation or the root brain itself
                undefinedLengthMap.put(entry.getKey(), entry.getValue());
            } else {
                dataset.addValue(entry.getValue()[0], "Ipsilateral", entry.getKey().acronym());
                dataset.addValue(entry.getValue()[1], "Contralateral", entry.getKey().acronym());
            }
        });

        if (!undefinedLengthMap.isEmpty()) {
            undefinedLengthMap.forEach((k, v) -> {
                dataset.addValue(v[0], "Ipsilateral", BrainAnnotation.simplifiedString(k));
                dataset.addValue(v[1], "Contralateral", BrainAnnotation.simplifiedString(k));
            });
        }
        final String axisTitle = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth ≤" + depth;
        final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
                "Brain areas (N=" + (seriesMap.size() - undefinedLengthMap.size()) + ", " + axisTitle + ")", // domain axis title
                String.format("Cable length (%s)", getSpatialUnit()), // range axis title
                "", // axis unit (already applied)
                dataset, 2);
        final String tLabel = (tree.getLabel() == null) ? "" : tree.getLabel();
        return new SNTChart(tLabel + " Annotated Length", chart, new Dimension(400, 600));
    }

    protected SNTChart getAnnotatedLengthHistogram(final Map<BrainAnnotation, Double> map, final int depth, final String secondaryLabel) {
        final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        Map<BrainAnnotation, Double> undefinedLengthMap = new HashMap<>();
        final String seriesLabel = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth ≤" + depth;
        map.entrySet().stream().sorted((e1, e2) -> -e1.getValue().compareTo(e2.getValue())).forEach(entry -> {
            if (entry.getKey() == null || entry.getKey().getOntologyDepth() == 0) {
                // a null brain annotation or the root brain itself
                undefinedLengthMap.put(entry.getKey(), entry.getValue());
            } else dataset.addValue(entry.getValue(), seriesLabel, entry.getKey().acronym());
        });
        if (!undefinedLengthMap.isEmpty()) {
            undefinedLengthMap.forEach((k, v) -> dataset.addValue(v, seriesLabel, BrainAnnotation.simplifiedString(k)));
        }
        final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
                "Brain areas (N=" + (map.size() - undefinedLengthMap.size()) + ", " + seriesLabel + ")", // domain axis title
                String.format("Cable length (%s)", getSpatialUnit()), // range axis title
                "", // unit: already specified
                dataset);
        final String tLabel = (tree.getLabel() == null) ? "" : tree.getLabel();
        final SNTChart frame = new SNTChart(tLabel + " Annotated Length", chart, new Dimension(400, 600));
        if (secondaryLabel != null) frame.annotate(secondaryLabel);
        return frame;
    }

    /**
     * Retrieves the histogram of relative frequencies histogram for a univariate
     * measurement. The number of bins is determined using the Freedman-Diaconis
     * rule.
     *
     * @param metric the measurement ({@link #N_NODES}, {@link #NODE_RADIUS}, etc.)
     * @return the frame holding the histogram
     */
    public SNTChart getHistogram(final String metric) {
        final String normMeasurement = getNormalizedMeasurement(metric);
        final HistogramDatasetPlus datasetPlus = new HDPlus(normMeasurement);
        return getHistogram(normMeasurement, datasetPlus);
    }

    public SNTChart getPolarHistogram(final String metric) {
        final String normMeasurement = getNormalizedMeasurement(metric);
        final HistogramDatasetPlus datasetPlus = new HDPlus(normMeasurement);
        final SNTChart chart = AnalysisUtils.createPolarHistogram(normMeasurement, getUnit(normMeasurement), lastDstats.dStats, datasetPlus);
        chart.setTitle("Polar Hist. " + tree.getLabel());
        return chart;
    }

    /**
     * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
     * "mean" as integration statistic, and no cutoff value.
     *
     * @see #getFlowPlot(String, Collection, String, double, boolean)
     */
    public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations, final boolean normalize) {
        return getFlowPlot(feature, annotations, "sum", Double.MIN_VALUE, normalize);
    }

    /**
     * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
     * "mean" as integration statistic, no cutoff value, and all of the brain
     * regions of the specified ontology depth.
     *
     * @param feature the feature ({@value MultiTreeStatistics#LENGTH},
     *                {@value MultiTreeStatistics#N_BRANCH_POINTS},
     *                {@value MultiTreeStatistics#N_TIPS}, etc.).
     * @param depth   the ontological depth of the compartments to be considered
     * @return the flow plot
     * @see #getFlowPlot(String, Collection, String, double, boolean)
     */
    public SNTChart getFlowPlot(final String feature, final int depth) {
        return getFlowPlot(feature, depth, Double.MIN_VALUE, true);
    }

    /**
     * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
     * "mean" as integration statistic, no cutoff value, and all of the brain
     * regions of the specified ontology depth. *
     *
     * @param feature the feature ({@value MultiTreeStatistics#LENGTH},
     *                {@value MultiTreeStatistics#N_BRANCH_POINTS},
     *                {@value MultiTreeStatistics#N_TIPS}, etc.)
     * @param depth   the ontological depth of the compartments to be considered
     * @param cutoff  a filtering option. If the computed {@code feature} for an
     *                annotation is below this value, that annotation is excluded
     *                from the plot * @param normalize If true, values are retrieved
     *                as ratios. E.g., If {@code feature} is
     *                {@value MultiTreeStatistics#LENGTH}, and {@code cutoff} 0.1,
     *                BrainAnnotations in {@code annotations} associated with less
     *                than 10% of cable length are ignored.
     * @return the flow plot
     */
    public SNTChart getFlowPlot(final String feature, final int depth, final double cutoff, final boolean normalize) {
        return getFlowPlot(feature, getAnnotations(depth), "sum", cutoff, normalize);
    }

    /**
     * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
     * "mean" as integration statistic, and no cutoff value.
     *
     * @see #getFlowPlot(String, Collection, String, double, boolean)
     */
    public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations) {
        return getFlowPlot(feature, annotations, "sum", Double.MIN_VALUE, false);
    }

    /**
     * Assembles a Flow plot (aka Sankey diagram) for the specified feature.
     *
     * @param feature     the feature ({@value MultiTreeStatistics#LENGTH},
     *                    {@value MultiTreeStatistics#N_BRANCH_POINTS},
     *                    {@value MultiTreeStatistics#N_TIPS}, etc.). Note that the
     *                    majority of {@link MultiTreeStatistics#getAllMetrics()}
     *                    metrics are currently not supported.
     * @param annotations the BrainAnnotations to be queried. Null not allowed.
     * @param statistic   the integration statistic (lower case). Either "mean",
     *                    "sum", "min" or "max". Null not allowed.
     * @param cutoff      a filtering option. If the computed {@code feature} for an
     *                    annotation is below this value, that annotation is
     *                    excluded from the plot
     * @param normalize   If true, values are retrieved as ratios. E.g., If
     *                    {@code feature} is {@value MultiTreeStatistics#LENGTH},
     *                    and {@code cutoff} 0.1, BrainAnnotations in
     *                    {@code annotations} associated with less than 10% of cable
     *                    length are ignored.
     * @return the SNTChart holding the flow plot
     */
    public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations, final String statistic, final double cutoff, final boolean normalize) {
        final GroupedTreeStatistics gts = new GroupedTreeStatistics();
        gts.addGroup(Collections.singleton(getParsedTree()), (null == tree.getLabel()) ? "" : tree.getLabel());
        final SNTChart chart = gts.getFlowPlot(feature, annotations, statistic, cutoff, normalize);
        chart.setTitle("Flow Plot [Single Cell]");
        return chart;
    }

    protected SNTChart getHistogram(final String normMeasurement, final HistogramDatasetPlus datasetPlus) {
        final SNTChart chart = AnalysisUtils.createHistogram(normMeasurement, getUnit(normMeasurement), lastDstats.dStats, datasetPlus);
        chart.setTitle("Hist. " + tree.getLabel());
        return chart;
    }

    protected void assembleStats(final StatisticsInstance stat, final String measurement) {
        final String m = getNormalizedMeasurement(measurement);
        switch (m) {
            case BRANCH_CONTRACTION:
                try {
                    for (final Path p : getBranches())
                        stat.addValue(p.getContraction());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case BRANCH_EXTENSION_ANGLE_XY:
                try {
                    getBranches().forEach(b -> stat.addValue(b.getExtensionAngleXY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case BRANCH_EXTENSION_ANGLE_XZ:
                try {
                    getBranches().forEach(b -> stat.addValue(b.getExtensionAngleXZ(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case BRANCH_EXTENSION_ANGLE_ZY:
                try {
                    getBranches().forEach(b -> stat.addValue(b.getExtensionAngleZY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case BRANCH_FRACTAL_DIMENSION:
                try {
                    getFractalDimension().forEach(stat::addValue);
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case BRANCH_LENGTH:
                try {
                    for (final Path p : getBranches())
                        stat.addValue(p.getLength());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case BRANCH_MEAN_RADIUS:
                try {
                    for (final Path p : getBranches())
                        stat.addValue(p.getMeanRadius());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case BRANCH_SURFACE_AREA:
                try {
                    for (final Path p : getBranches())
                        stat.addValue(p.getApproximatedSurface());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case BRANCH_VOLUME:
                try {
                    for (final Path p : getBranches())
                        stat.addValue(p.getApproximatedVolume());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case COMPLEXITY_INDEX_ACI:
                // implementation: doi: 10.1523/JNEUROSCI.4434-06.2007
                double sumPathOrders = 0;
                for (final Path p : tree.list())
                    sumPathOrders += p.getOrder() - 1;
                stat.addValue(sumPathOrders / tree.list().size());
                break;
            case COMPLEXITY_INDEX_DCI:
                try {
                    // Implementation by chronological order:
                    // www.jneurosci.org/content/19/22/9928#F6
                    // https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3373517/
                    // https://journals.physiology.org/doi/full/10.1152/jn.00829.2011
                    final DirectedWeightedGraph graph = tree.getGraph();
                    final List<SWCPoint> graphTips = graph.getTips();
                    final SWCPoint root = graph.getRoot();
                    double sumBranchTipOrders = 0;
                    for (final SWCPoint tip : graphTips) {
                        for (final SWCPoint vx : graph.getShortestPathVertices(root, tip)) {
                            if (graph.outDegreeOf(vx) > 1) sumBranchTipOrders++;
                        }
                    }
                    stat.addValue((sumBranchTipOrders + graphTips.size()) * getCableLength() / getPrimaryBranches().size());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case CONVEX_HULL_BOUNDARY_SIZE:
            case CONVEX_HULL_BOXIVITY:
            case CONVEX_HULL_ELONGATION:
            case CONVEX_HULL_ROUNDNESS:
            case CONVEX_HULL_SIZE:
                stat.addValue(getConvexHullMetric(m));
                break;
            case ROOT_ANGLE_B_FACTOR:
            case ROOT_ANGLE_C_BIAS:
            case ROOT_ANGLE_M_DIRECTION:
                stat.addValue(getRootAngleMetric(m));
                break;
            case CONVEX_HULL_CENTROID_ROOT_DISTANCE:
                final PointInImage root = tree.getRoot();
                if (root == null) stat.addNaN();
                else stat.addValue(getConvexAnalyzer().getCentroid().distanceTo(root));
                break;
            case DEPTH:
                stat.addValue(getDepth());
                break;
            case GRAPH_DIAMETER:
                try {
                    stat.addValue(tree.getGraph().getLongestPath(true).getLength());
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case GRAPH_DIAMETER_ANGLE_XY:
                try {
                    stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleXY(false));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case GRAPH_DIAMETER_ANGLE_XZ:
                try {
                    stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleXZ(false));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case GRAPH_DIAMETER_ANGLE_ZY:
                try {
                    stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleZY(false));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case HEIGHT:
                stat.addValue(getHeight());
                break;
            case INNER_EXTENSION_ANGLE_XY:
                try {
                    getInnerBranches().forEach(b -> stat.addValue(b.getExtensionAngleXY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case INNER_EXTENSION_ANGLE_XZ:
                try {
                    getInnerBranches().forEach(b -> stat.addValue(b.getExtensionAngleXZ(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case INNER_EXTENSION_ANGLE_ZY:
                try {
                    getInnerBranches().forEach(b -> stat.addValue(b.getExtensionAngleZY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case INNER_LENGTH:
                try {
                    getInnerBranches().forEach(b -> stat.addValue(b.getLength()));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case INTER_NODE_ANGLE:
                for (final Path p : tree.list()) {
                    if (p.size() < 3) continue;
                    for (int i = 2; i < p.size(); i++) {
                        stat.addValue(p.getAngle(i));
                    }
                }
                break;
            case INTER_NODE_DISTANCE:
                for (final Path p : tree.list()) {
                    if (p.size() < 2) continue;
                    for (int i = 1; i < p.size(); i += 1) {
                        stat.addValue(p.getNode(i).distanceTo(p.getNode(i - 1)));
                    }
                }
                break;
            case INTER_NODE_DISTANCE_SQUARED:
                for (final Path p : tree.list()) {
                    if (p.size() < 2) continue;
                    for (int i = 1; i < p.size(); i += 1) {
                        stat.addValue(p.getNode(i).distanceSquaredTo(p.getNode(i - 1)));
                    }
                }
                break;
            case LENGTH:
                stat.addValue(getCableLength());
                break;
            case N_BRANCH_NODES:
                try {
                    getBranches().forEach(b -> stat.addValue(b.size()));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case N_BRANCH_POINTS:
                stat.addValue(getBranchPoints().size());
                break;
            case N_BRANCHES:
                stat.addValue(getNBranches());
                break;
            case N_FITTED_PATHS:
                stat.addValue(getNFittedPaths());
                break;
            case N_INNER_BRANCHES:
                stat.addValue(getInnerBranches().size());
                break;
            case N_NODES:
                stat.addValue(getNNodes());
                break;
            case N_PATH_NODES:
                tree.list().forEach(path -> stat.addValue(path.size()));
                break;
            case N_PATHS:
                stat.addValue(getNPaths());
                break;
            case N_PRIMARY_BRANCHES:
                stat.addValue(getPrimaryBranches().size());
                break;
            case N_SPINES:
                stat.addValue(getNoSpinesOrVaricosities());
                break;
            case N_TERMINAL_BRANCHES:
                stat.addValue(getTerminalBranches().size());
                break;
            case N_TIPS:
                stat.addValue(getTips().size());
                break;
            case NODE_RADIUS:
                for (final Path p : tree.list()) {
                    for (int i = 0; i < p.size(); i++) {
                        stat.addValue(p.getNodeRadius(i));
                    }
                }
                break;
            case PARTITION_ASYMMETRY:
                try {
                    for (final double asymmetry : getPartitionAsymmetry())
                        stat.addValue(asymmetry);
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case PATH_CHANNEL:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getChannel());
                }
                break;
            case PATH_CONTRACTION:
                try {
                    for (final Path p : tree.list())
                        stat.addValue(p.getContraction());
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case PATH_EXT_ANGLE_XY:
            case PATH_EXT_ANGLE_REL_XY:
                for (final Path p : tree.list())
                    stat.addValue(p.getExtensionAngleXY(PATH_EXT_ANGLE_REL_XY.equals(m)));
                break;
            case PATH_EXT_ANGLE_XZ:
            case PATH_EXT_ANGLE_REL_XZ:
                for (final Path p : tree.list())
                    stat.addValue(p.getExtensionAngleXZ(PATH_EXT_ANGLE_REL_XZ.equals(m)));
                break;
            case PATH_EXT_ANGLE_ZY:
            case PATH_EXT_ANGLE_REL_ZY:
                for (final Path p : tree.list())
                    stat.addValue(p.getExtensionAngleZY(PATH_EXT_ANGLE_REL_ZY.equals(m)));
                break;
            case PATH_FRACTAL_DIMENSION:
                tree.list().forEach(p -> stat.addValue(p.getFractalDimension()));
                break;
            case PATH_FRAME:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getFrame());
                }
                break;
            case PATH_LENGTH:
                for (final Path p : tree.list())
                    stat.addValue(p.getLength());
                break;
            case PATH_MEAN_RADIUS:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getMeanRadius());
                }
                break;
            case PATH_N_SPINES:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getSpineOrVaricosityCount());
                }
                break;
            case PATH_ORDER:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getOrder());
                }
                break;
            case PATH_SPINE_DENSITY:
                for (final Path p : tree.list()) {
                    stat.addValue(p.getSpineOrVaricosityCount() / p.getLength());
                }
                break;
            case PATH_SURFACE_AREA:
                for (final Path p : tree.list())
                    stat.addValue(p.getApproximatedSurface());
                break;
            case PATH_VOLUME:
                for (final Path p : tree.list())
                    stat.addValue(p.getApproximatedVolume());
                break;
            case PRIMARY_EXTENSION_ANGLE_XY:
                try {
                    getPrimaryBranches().forEach(b -> stat.addValue(b.getExtensionAngleXY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case PRIMARY_EXTENSION_ANGLE_XZ:
                try {
                    getPrimaryBranches().forEach(b -> stat.addValue(b.getExtensionAngleXZ(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case PRIMARY_EXTENSION_ANGLE_ZY:
                try {
                    getPrimaryBranches().forEach(b -> stat.addValue(b.getExtensionAngleZY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case PRIMARY_LENGTH:
                for (final Path p : getPrimaryBranches())
                    stat.addValue(p.getLength());
                break;
            case REMOTE_BIF_ANGLES:
                try {
                    for (final double angle : getRemoteBifAngles())
                        stat.addValue(angle);
                } catch (final IllegalArgumentException iae) {
                    SNTUtils.log("Error: " + iae.getMessage());
                    stat.addNaN();
                }
                break;
            case SHOLL_DECAY:
            case SHOLL_KURTOSIS:
            case SHOLL_MAX_FITTED:
            case SHOLL_MAX_FITTED_RADIUS:
            case SHOLL_MAX_VALUE:
            case SHOLL_MEAN_VALUE:
            case SHOLL_N_MAX:
            case SHOLL_N_SECONDARY_MAX:
            case SHOLL_POLY_FIT_DEGREE:
            case SHOLL_RAMIFICATION_INDEX:
            case SHOLL_SKEWNESS:
            case SHOLL_SUM_VALUE:
                stat.addValue(getShollMetric(m).doubleValue());
                break;
            case STRAHLER_NUMBER:
                stat.addValue(getStrahlerNumber());
                break;
            case STRAHLER_RATIO:
                stat.addValue(getStrahlerBifurcationRatio());
                break;
            case SURFACE_AREA:
                stat.addValue(tree.getApproximatedSurface());
                break;
            case TERMINAL_EXTENSION_ANGLE_XY:
                try {
                    getTerminalBranches().forEach(b -> stat.addValue(b.getExtensionAngleXY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case TERMINAL_EXTENSION_ANGLE_XZ:
                try {
                    getTerminalBranches().forEach(b -> stat.addValue(b.getExtensionAngleXZ(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case TERMINAL_EXTENSION_ANGLE_ZY:
                try {
                    getTerminalBranches().forEach(b -> stat.addValue(b.getExtensionAngleZY(false)));
                } catch (final IllegalArgumentException ignored) {
                    stat.addNaN();
                }
                break;
            case TERMINAL_LENGTH:
                for (final Path p : getTerminalBranches())
                    stat.addValue(p.getLength());
                break;
            case VALUES:
                for (final Path p : tree.list()) {
                    if (!p.hasNodeValues()) continue;
                    for (int i = 0; i < p.size(); i++) {
                        stat.addValue(p.getNodeValue(i));
                    }
                }
                if (stat.getN() == 0) throw new IllegalArgumentException("Tree has no values assigned");
                break;
            case VOLUME:
                stat.addValue(tree.getApproximatedVolume());
                break;
            case WIDTH:
                stat.addValue(getWidth());
                break;
            case X_COORDINATES:
                for (final Path p : tree.list()) {
                    for (int i = 0; i < p.size(); i++) {
                        stat.addValue(p.getNode(i).x);
                    }
                }
                break;
            case Y_COORDINATES:
                for (final Path p : tree.list()) {
                    for (int i = 0; i < p.size(); i++) {
                        stat.addValue(p.getNode(i).y);
                    }
                }
                break;
            case Z_COORDINATES:
                for (final Path p : tree.list()) {
                    for (int i = 0; i < p.size(); i++) {
                        stat.addValue(p.getNode(i).z);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Unrecognized parameter " + measurement);
        }
    }

    protected boolean lastDstatsCanBeRecycled(final String normMeasurement) {
        return (lastDstats != null && tree.size() == lastDstats.size && normMeasurement.equals(lastDstats.measurement));
    }

    /**
     * Generates detailed summaries in which measurements are grouped by SWC-type
     * flags
     *
     * @see #summarize(String, boolean)
     */
    @Override
    public void run() {
        if (tree.list() == null || tree.list().isEmpty()) {
            cancel("No Paths to Measure");
            return;
        }
        if (statusService != null) statusService.showStatus("Measuring Paths...");
        summarize(true);
        if (statusService != null) statusService.clearStatus();
    }

    protected Number getShollMetric(final String metric) {
        final String fMetric = metric.substring(metric.indexOf("Sholl: ") + 6).trim();
        return getShollAnalyzer().getSingleValueMetrics().getOrDefault(fMetric, Double.NaN);
    }

    /**
     * Convenience method to initialize a {@link NodeStatistics} from all the nodes of the Tree being analyzed.
     * @return the NodeStatistics instance
     */
    public NodeStatistics<SWCPoint> getNodeStatistics() {
        return getNodeStatistics("all");
    }

    /**
     * Convenience method to initialize a {@link NodeStatistics} from a subset of the nodes of the Tree being analyzed.
     *
     * @param type the vertex type (e.g., "tips"/"end-points", "junctions"/"branch points", "all")
     * @return the NodeStatistics instance
     * @see DirectedWeightedGraph#getNodeStatistics(String)
     */
    public NodeStatistics<SWCPoint> getNodeStatistics(final String type) {
        return tree.getGraph().getNodeStatistics(type);
    }

    /**
     * Convenience method to obtain a single-value metric from {@link ConvexHullAnalyzer}
     * @param metric the metric to be retrieved, one of {@link ConvexHullAnalyzer#supportedMetrics()}
     * @return the convex hull metric
     * @see #getConvexAnalyzer()
     * @see ConvexHullAnalyzer#get(String)
     */
    public double getConvexHullMetric(final String metric) {
        final String fMetric = metric.substring(metric.indexOf("Convex Hull: ") + 14).trim();
        return getConvexAnalyzer().get(fMetric);
    }

    /**
     * Gets the {@link ConvexHullAnalyzer} instance associated with this TreeStatistics instance.
     *
     * @return the ConvexHullAnalyzer instance
     */
    public ConvexHullAnalyzer getConvexAnalyzer() {
        if (convexAnalyzer == null) {
            convexAnalyzer = new ConvexHullAnalyzer(tree);
            convexAnalyzer.setContext((getContext() == null) ? SNTUtils.getContext() : getContext());
        }
        return convexAnalyzer;
    }

    /**
     * Convenience method to obtain a single-value metric from {@link RootAngleAnalyzer}.
     * @param metric the metric to be retrieved, one of {@link RootAngleAnalyzer#supportedMetrics()}
     * @return the root angle metric
     * @see #getRootAngleAnalyzer()
     */
    public double getRootAngleMetric(final String metric) {
        final String fMetric = metric.substring(metric.indexOf("Root Angle: ") + 14).trim();
        try {
            return getRootAngleAnalyzer().getAnalysis().get(fMetric);
        } catch (final IllegalArgumentException ignored){
            return Double.NaN;
        }
    }

    /**
     * Gets the {@link RootAngleAnalyzer} instance associated with this TreeStatistics instance.
     *
     * @return the RootAngleAnalyzer instance
     */
    public RootAngleAnalyzer getRootAngleAnalyzer() throws IllegalArgumentException {
        if (rootAngleAnalyzer == null) {
            rootAngleAnalyzer = new RootAngleAnalyzer(tree);
        }
        return rootAngleAnalyzer;
    }

    /**
     * Retrieves the brain compartments (neuropil labels) associated with the Tree being measured
     * innervated by the analyzed neuron.
     *
     * @return the set of brain compartments ({@link BrainAnnotation}s)
     * @see AllenCompartment
     */
    public Set<BrainAnnotation> getAnnotations() {
        final HashSet<BrainAnnotation> set = new HashSet<>();
        for (final Path path : tree.list()) {
            for (int i = 0; i < path.size(); i++) {
                final BrainAnnotation annotation = path.getNodeAnnotation(i);
                if (annotation != null) set.add(annotation);
            }
        }
        return set;
    }

    /**
     * Retrieves the brain compartments (neuropil labels) associated with the Tree being measured
     * innervated by the analyzed neuron.
     *
     * @param depth the max. ontological depth of the compartments to be retrieved
     * @return the set of brain compartments ({@link BrainAnnotation}s)
     * @see AllenCompartment
     */
    public Set<BrainAnnotation> getAnnotations(final int depth) {
        final Set<BrainAnnotation> filteredAnnotations = new HashSet<>();
        getAnnotations().forEach(annot -> {
            final int annotDepth = annot.getOntologyDepth();
            if (annotDepth > depth) {
                filteredAnnotations.add(annot.getAncestor(depth - annotDepth));
            } else {
                filteredAnnotations.add(annot);
            }
        });
        return filteredAnnotations;
    }

    /**
     * Gets the cable length of primary branches.
     *
     * @return the length sum of all primary branches
     * @see #getPrimaryBranches()
     */
    public double getPrimaryLength() {
        if (primaryBranches == null) getPrimaryBranches();
        return sumLength(primaryBranches);
    }

    /**
     * Gets the cable length of inner branches
     *
     * @return the length sum of all inner branches
     * @see #getInnerBranches()
     */
    public double getInnerLength() {
        if (innerBranches == null) getInnerBranches();
        return sumLength(innerBranches);
    }

    /**
     * Gets the cable length of terminal branches
     *
     * @return the length sum of all terminal branches
     * @see #getTerminalBranches()
     */
    public double getTerminalLength() {
        if (terminalBranches == null) getTerminalBranches();
        return sumLength(terminalBranches);
    }

    /**
     * Gets the highest {@link sc.fiji.snt.Path#getOrder() path order} of the analyzed tree
     *
     * @return the highest Path order, or -1 if Paths in the Tree have no defined
     * order
     * @see #getStrahlerNumber()
     */
    public int getHighestPathOrder() {
        int root = -1;
        for (final Path p : tree.list()) {
            final int order = p.getOrder();
            if (order > root) root = order;
        }
        return root;
    }

    /**
     * Checks whether this tree is topologically valid, i.e., contains only one root and no loops.
     *
     * @return true, if Tree is valid, false otherwise
     */
    public boolean isValid() {
        if (sAnalyzer == null) sAnalyzer = new StrahlerAnalyzer(tree);
        try {
            sAnalyzer.getGraph();
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Gets the highest {@link StrahlerAnalyzer#getRootNumber() Strahler number} of the analyzed tree.
     *
     * @return the highest Strahler (root) number order
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public int getStrahlerNumber() throws IllegalArgumentException {
        getStrahlerAnalyzer();
        return sAnalyzer.getRootNumber();
    }

    /**
     * Gets the {@link StrahlerAnalyzer} instance associated with this TreeStatistics instance
     *
     * @return the StrahlerAnalyzer instance associated with this TreeStatistics instance
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public StrahlerAnalyzer getStrahlerAnalyzer() throws IllegalArgumentException {
        if (sAnalyzer == null) sAnalyzer = new StrahlerAnalyzer(tree);
        return sAnalyzer;
    }

    /**
     * Gets the {@link ShollAnalyzer} instance associated with this TreeStatistics instance. Note
     * that changes to {@link ShollAnalyzer} must be performed before retrieving
     * Sholl related metrics, i.e., before calling {@link #measure(Collection, boolean)}, etc.
     *
     * @return the ShollAnalyzer instance associated with this TreeStatistics instance
     */
    public ShollAnalyzer getShollAnalyzer() {
        if (shllAnalyzer == null) shllAnalyzer = new ShollAnalyzer(tree, this);
        return shllAnalyzer;
    }

    /**
     * Gets the average {@link StrahlerAnalyzer#getAvgBifurcationRatio() Strahler bifurcation ratio} of analyzed tree.
     *
     * @return the average bifurcation ratio
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public double getStrahlerBifurcationRatio() throws IllegalArgumentException {
        getStrahlerAnalyzer();
        return sAnalyzer.getAvgBifurcationRatio();
    }

    /**
     * Gets the number of branches in the analyzed tree.
     *
     * @return the number of branches
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public int getNBranches() throws IllegalArgumentException {
        return getBranches().size();
    }

    /**
     * Gets the number of nodes in the analyzed tree.
     *
     * @return the number of nodes
     */
    public long getNNodes() {
        return tree.getNodesCount();
    }

    /**
     * Gets all the branches in the analyzed tree. A branch is defined as the Path composed of all the nodes between
     * two branching points or between one branching point and a termination point.
     *
     * @return the list of branches as Path objects.
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     * @see StrahlerAnalyzer#getBranches()
     */
    public List<Path> getBranches() throws IllegalArgumentException {
        getStrahlerAnalyzer();
        return sAnalyzer.getBranches().values().stream().flatMap(List::stream).collect(Collectors.toList());
    }

    /**
     * Gets average {@link Path#getContraction() contraction} for all the branches of the analyzed tree.
     *
     * @return the average branch contraction
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public double getAvgContraction() throws IllegalArgumentException {
        double contraction = 0;
        final List<Path> branches = getBranches();
        for (final Path p : branches) {
            final double pContraction = p.getContraction();
            if (!Double.isNaN(pContraction)) contraction += pContraction;
        }
        return contraction / branches.size();
    }

    /**
     * Gets the average no. of nodes (fragmentation) for all the branches of the analyzed tree.
     *
     * @return the average no. of branch nodes (average branch fragmentation)
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public double getAvgFragmentation() {
        double fragmentation = 0;
        final List<Path> branches = getBranches();
        for (final Path p : branches) {
            fragmentation += p.size();
        }
        return fragmentation / branches.size();
    }

    /**
     * Gets average length for all the branches of the analyzed tree.
     *
     * @return the average branch length
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public double getAvgBranchLength() throws IllegalArgumentException {
        final List<Path> branches = getBranches();
        return sumLength(getBranches()) / branches.size();
    }

    /**
     * Gets the angle between each bifurcation point and its children in the simplified graph, which comprise either
     * branch points or terminal nodes. Note that branch points with more than 2 children are ignored.
     *
     * @return the list of remote bifurcation angles
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     */
    public List<Double> getRemoteBifAngles() throws IllegalArgumentException {
        final DirectedWeightedGraph sGraph = tree.getGraph(true);
        final List<SWCPoint> branchPoints = sGraph.getBPs();
        final List<Double> angles = new ArrayList<>();
        for (final SWCPoint bp : branchPoints) {
            final List<SWCPoint> children = Graphs.successorListOf(sGraph, bp);
            // Only consider bifurcations
            if (children.size() > 2) {
                continue;
            }
            final SWCPoint c0 = children.get(0);
            final SWCPoint c1 = children.get(1);
            // Get vector for each parent-child link
            final double[] v0 = new double[]{c0.getX() - bp.getX(), c0.getY() - bp.getY(), c0.getZ() - bp.getZ()};
            final double[] v1 = new double[]{c1.getX() - bp.getX(), c1.getY() - bp.getY(), c1.getZ() - bp.getZ()};
            // Dot product
            double dot = 0.0;
            for (int i = 0; i < v0.length; i++) {
                dot += v0[i] * v1[i];
            }
            final double cosineAngle = dot / (Math.sqrt(v0[0] * v0[0] + v0[1] * v0[1] + v0[2] * v0[2]) * Math.sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]));
            final double angleRadians = Math.acos(cosineAngle);
            final double angleDegrees = angleRadians * (180.0 / Math.PI);
            angles.add(angleDegrees);
        }
        return angles;
    }

    /**
     * Gets the average remote bifurcation angle of the analyzed tree. Note that branch points with more than 2
     * children are ignored during the computation.
     *
     * @return the average remote bifurcation angle
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     */
    public double getAvgRemoteBifAngle() throws IllegalArgumentException {
        final List<Double> angles = getRemoteBifAngles();
        double sumAngles = 0.0;
        for (final double a : angles) {
            sumAngles += a;
        }
        return sumAngles / angles.size();
    }

    /**
     * Gets the partition asymmetry at each bifurcation point in the analyzed tree.
     * Note that branch points with more than 2 children are ignored.
     *
     * @return a list containing the partition asymmetry at each bifurcation point
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     */
    public List<Double> getPartitionAsymmetry() throws IllegalArgumentException {
        final DirectedWeightedGraph sGraph = tree.getGraph(true);
        final List<SWCPoint> branchPoints = sGraph.getBPs();
        final List<Double> resultList = new ArrayList<>();
        for (final SWCPoint bp : branchPoints) {
            final List<SWCPoint> children = Graphs.successorListOf(sGraph, bp);
            // Only consider bifurcations
            if (children.size() > 2) {
                continue;
            }
            final List<Integer> tipCounts = new ArrayList<>();
            for (final SWCPoint child : children) {
                int count = 0;
                final DepthFirstIterator<SWCPoint, SWCWeightedEdge> dfi = sGraph.getDepthFirstIterator(child);
                while (dfi.hasNext()) {
                    final SWCPoint node = dfi.next();
                    if (Graphs.successorListOf(sGraph, node).isEmpty()) {
                        count++;
                    }
                }
                tipCounts.add(count);
            }
            double asymmetry;
            // Make sure we avoid getting NaN
            if (tipCounts.get(0).equals(tipCounts.get(1))) {
                asymmetry = 0.0;
            } else {
                asymmetry = (double) Math.abs(tipCounts.get(0) - tipCounts.get(1)) / (tipCounts.get(0) + tipCounts.get(1) - 2);
            }
            resultList.add(asymmetry);
        }
        return resultList;
    }

    /**
     * Gets the average partition asymmetry of the analyzed tree.
     * Note that branch points with more than 2 children are ignored during the computation.
     *
     * @return the average partition asymmetry
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     */
    public double getAvgPartitionAsymmetry() throws IllegalArgumentException {
        final List<Double> asymmetries = getPartitionAsymmetry();
        double sumAsymmetries = 0.0;
        for (final double a : asymmetries) {
            sumAsymmetries += a;
        }
        return sumAsymmetries / asymmetries.size();
    }

    /**
     * Gets the fractal dimension of each branch in the analyzed tree.
     * Note that branches with less than 5 points are ignored.
     *
     * @return a list containing the fractal dimension of each branch
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     * @see StrahlerAnalyzer#getBranches()
     * @see Path#getFractalDimension()
     */
    public List<Double> getFractalDimension() throws IllegalArgumentException {
        final List<Double> fractalDims = new ArrayList<>();
        for (final Path b : getBranches()) {
            final double fDim = b.getFractalDimension();
            if (!Double.isNaN(fDim)) fractalDims.add(fDim);
        }
        return fractalDims;
    }

    /**
     * Gets the average fractal dimension of the analyzed tree.
     * Note that branches with less than 5 points are ignored during the computation.
     *
     * @return the average fractal dimension
     * @throws IllegalArgumentException if the tree contains multiple roots or loops
     */
    public double getAvgFractalDimension() throws IllegalArgumentException {
        final List<Double> fractalDims = getFractalDimension();
        double sumDims = 0.0;
        for (final double fDim : fractalDims) {
            sumDims += fDim;
        }
        return sumDims / fractalDims.size();

    }

    /**
     * Gets the number of spines/varicosities that have been (manually) assigned to
     * tree being analyzed.
     *
     * @return the number of spines/varicosities
     */
    public int getNoSpinesOrVaricosities() {
        return tree.list().stream().mapToInt(Path::getSpineOrVaricosityCount).sum();
    }

    /**
     * Gets the overall density of spines/varicosities associated with this tree
     *
     * @return the spine/varicosity density (same as
     * {@code getNoSpinesOrVaricosities()/getCableLength()})
     */
    public double getSpineOrVaricosityDensity() {
        return getNoSpinesOrVaricosities() / getCableLength();
    }

    /**
     * Updates and displays the measurements table. If {@link org.scijava.Context} has not been set
     * (running headless!?), table is printed to Console.
     */
    public void updateAndDisplayTable() {
        if (getContext() == null) {
            System.out.println(SNTTable.toString(table, 0, table.getRowCount() - 1));
            return;
        }
        final String displayName = (tableTitle == null) ? "SNT Measurements" : tableTitle;
        final Display<?> display = displayService.getDisplay(displayName);
        if (display != null) {
            display.update();
        } else {
            displayService.createDisplay(displayName, table);
        }
    }

    /**
     * Returns the physical unit associated with the specified metric.
     * E.g.: returns µm³ if metric is "volume" and coordinates of tree being measured are defined in µm.
     * @param metric the metric to be queried (case-insensitive)
     * @return physical unit
     */
    public String getUnit(final String metric) {
        final String m = metric.toLowerCase();
        if (TreeStatistics.WIDTH.equals(metric) || TreeStatistics.HEIGHT.equals(metric) || TreeStatistics.DEPTH.equals(metric)
                || m.contains("length") || m.contains("radius") || m.contains("distance") || TreeStatistics.CONVEX_HULL_ELONGATION.equals(metric)) {
            return getSpatialUnit();
        } else if (m.contains("volume")) {
            return getSpatialUnit() + "³";
        } else if (m.contains("surface area")) {
            return getSpatialUnit() + "²";
        } else if (TreeStatistics.CONVEX_HULL_SIZE.equals(metric)) {
            return getSpatialUnit() + ((tree.is3D()) ? "³" : "²");
        } else if (TreeStatistics.CONVEX_HULL_BOUNDARY_SIZE.equals(metric)) {
            return getSpatialUnit() + ((tree.is3D()) ? "²" : "");
        } else if (RootAngleAnalyzer.MEAN_DIRECTION.equals(metric) || REMOTE_BIF_ANGLES.equals(metric) || m.contains("extension angle"))
            return "°";
        return "";
    }

    protected String getSpatialUnit() {
        return (String) tree.getProperties().getOrDefault(TreeProperties.KEY_SPATIAL_UNIT, "? units");
    }

    protected int getCol(final String header) {
        final String normHeader = AnalysisUtils.getMetricLabel(header, tree);
        int idx = table.getColumnIndex(normHeader);
        if (idx == -1) {
            table.appendColumn(normHeader);
            idx = table.getColumnCount() - 1;
        }
        return idx;
    }

    /**
     * Gets the no. of parsed paths.
     *
     * @return the number of paths
     */
    public int getNPaths() {
        return tree.list().size();
    }

    protected int getNFittedPaths() {
        return fittedPathsCounter;
    }

    /**
     * Returns the width of the {@link sc.fiji.snt.util.BoundingBox} box of the tree being measured
     *
     * @return the bounding box width
     */
    public double getWidth() {
        return tree.getBoundingBox(true).width();
    }

    /**
     * Returns the height of the {@link sc.fiji.snt.util.BoundingBox} box of the tree being measured
     *
     * @return the bounding box height
     */
    public double getHeight() {
        return tree.getBoundingBox(true).height();
    }

    /**
     * Returns the depth of the {@link sc.fiji.snt.util.BoundingBox} box of the tree being measured
     *
     * @return the bounding box depth
     */
    public double getDepth() {
        return tree.getBoundingBox(true).depth();
    }

    /**
     * Retrieves all the Paths in the analyzed Tree tagged as primary.
     *
     * @return the list of primary paths.
     * @see #getPrimaryBranches()
     */
    public List<Path> getPrimaryPaths() {
        final List<Path> primaryPaths = new ArrayList<>();
        for (final Path p : tree.list()) {
            if (p.isPrimary()) primaryPaths.add(p);
        }
        return primaryPaths;
    }

    /**
     * Retrieves the branches of highest
     * {@link StrahlerAnalyzer#getHighestBranchOrder() Strahler order} in the Tree.
     * This typically correspond to the most 'internal' branches of the Tree in
     * direct sequence from the root.
     *
     * @return the list containing the "inner" branches. Note that these branches
     * (Path segments) will not carry any connectivity information.
     * @see #getPrimaryPaths()
     * @see StrahlerAnalyzer#getBranches()
     * @see StrahlerAnalyzer#getHighestBranchOrder()
     */
    public List<Path> getInnerBranches() {
        getStrahlerAnalyzer();
        innerBranches = sAnalyzer.getBranches(sAnalyzer.getHighestBranchOrder());
        return innerBranches;
    }

    /**
     * Retrieves the primary branches of the analyzed Tree. Primary branches (or
     * root-associated) have origin in the Tree's root, extending to the closest
     * branch/end-point. Note that a primary branch can also be terminal.
     *
     * @return the primary branches. Note that these branches (Path segments) will
     * not carry any connectivity information.
     * @see #getPrimaryPaths()
     * @see StrahlerAnalyzer#getRootAssociatedBranches()
     */
    public List<Path> getPrimaryBranches() {
        getStrahlerAnalyzer();
        primaryBranches = sAnalyzer.getRootAssociatedBranches();
        return primaryBranches;
    }

    /**
     * Retrieves the terminal branches of the analyzed Tree. A terminal branch
     * corresponds to the section of a terminal Path between its last branch-point
     * and its terminal point (tip). A terminal branch can also be primary.
     *
     * @return the terminal branches. Note that as per
     * {@link Path#getSection(int, int)}, these branches will not carry any
     * connectivity information.
     * @see #getPrimaryBranches
     */
    public List<Path> getTerminalBranches() {
        getStrahlerAnalyzer();
        terminalBranches = sAnalyzer.getBranches(1);
        return terminalBranches;
    }

    /**
     * Gets the position of all the tips in the analyzed tree.
     *
     * @return the set of terminal points
     */
    public Set<PointInImage> getTips() {

        // retrieve all start/end points
        tips = new HashSet<>();
        for (final Path p : tree.list()) {
            tips.add(p.getNode(p.size() - 1));
        }
        // now remove any joint-associated point
        if (joints == null) getBranchPoints();
        tips.removeAll(joints);
        return tips;

    }

    /**
     * Gets the position of all the tips in the analyzed tree associated with the specified annotation.
     *
     * @param annot the BrainAnnotation to be queried (all of its children will be considered). Null not allowed.
     * @return the branch points positions, or an empty set if no tips were retrieved.
     */
    public Set<PointInImage> getTips(final BrainAnnotation annot) {
        return getTips(annot, true);
    }

    /**
     * Gets the position of all the tips in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot           the BrainAnnotation to be queried. Null not allowed.
     * @param includeChildren whether children of {@code annot} should be included.
     * @return the branch points positions, or an empty set if no tips were retrieved.
     */
    public Set<PointInImage> getTips(final BrainAnnotation annot, final boolean includeChildren) {
        if (tips == null) getTips();
        final HashSet<PointInImage> fTips = new HashSet<>();
        for (final PointInImage tip : tips) {
            final BrainAnnotation annotation = tip.getAnnotation();
            if (annotation != null && contains(annot, annotation, includeChildren)) fTips.add(tip);
        }
        return fTips;
    }

    /**
     * Gets the number of end points in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot the BrainAnnotation to be queried. All of its children will be considered
     * @return the number of end points
     */
    public int getNTips(final BrainAnnotation annot) {
        return getNTips(annot, true);
    }

    /**
     * Gets the number of end points in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot           the BrainAnnotation to be queried.
     * @param includeChildren whether children of {@code annot} should be included.
     * @return the number of end points
     */
    public int getNTips(final BrainAnnotation annot, final boolean includeChildren) {
        return getTips(annot, includeChildren).size();
    }

    /**
     * Gets the number of end points in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot the BrainAnnotation to be queried. All of its children will be considered
     * @return the number of end points
     */
    public double getNTipsNorm(final BrainAnnotation annot) {
        return getNTipsNorm(annot, true);
    }

    /**
     * Gets the percentage of end points in the analyzed tree associated with the
     * specified annotation
     *
     * @param annot           the BrainAnnotation to be queried.
     * @param includeChildren whether children of {@code annot} should be included
     * @return the ratio between the no. of branch points associated with
     * {@code annot} and the total number of end points in the tree.
     */
    public double getNTipsNorm(final BrainAnnotation annot, final boolean includeChildren) {
        return (double) (getNTips(annot, includeChildren)) / (double) (tips.size());
    }

    /**
     * Gets the position of all the branch points in the analyzed tree.
     *
     * @return the branch points positions
     */
    public Set<PointInImage> getBranchPoints() {
        joints = new HashSet<>();
        for (final Path p : tree.list()) {
            joints.addAll(p.getJunctionNodes());
        }
        return joints;
    }

    /**
     * Gets the position of all the branch points in the analyzed tree associated
     * with the specified annotation.
     *
     * @param annot the BrainAnnotation to be queried. All of its children are
     *              considered.
     * @return the branch points positions, or an empty set if no branch points were retrieved
     */
    public Set<PointInImage> getBranchPoints(final BrainAnnotation annot) {
        return getBranchPoints(annot, true);
    }

    /**
     * Gets the position of all the branch points in the analyzed tree associated
     * with the specified annotation.
     *
     * @param annot           the BrainAnnotation to be queried.
     * @param includeChildren whether children of {@code annot} should be included
     * @return the branch points positions, or an empty set if no branch points were retrieved
     */
    public Set<PointInImage> getBranchPoints(final BrainAnnotation annot, final boolean includeChildren) {
        if (joints == null) getBranchPoints();
        final HashSet<PointInImage> fJoints = new HashSet<>();
        for (final PointInImage joint : joints) {
            final BrainAnnotation annotation = joint.getAnnotation();
            if (annotation != null && contains(annot, annotation, includeChildren)) fJoints.add(joint);
        }
        return fJoints;
    }

    /**
     * Gets the number of branch points in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot the BrainAnnotation to be queried. All of its children are
     *              considered.
     * @return the number of branch points
     */
    public int getNBranchPoints(final BrainAnnotation annot) {
        return getNBranchPoints(annot, true);
    }

    /**
     * Gets the number of branch points in the analyzed tree associated with the
     * specified annotation.
     *
     * @param annot           the BrainAnnotation to be queried.
     * @param includeChildren whether children of {@code annot} should be included
     * @return the number of branch points
     */
    public int getNBranchPoints(final BrainAnnotation annot, final boolean includeChildren) {
        return getBranchPoints(annot, includeChildren).size();
    }

    /**
     * Gets the percentage of branch points in the analyzed tree associated with the
     * specified annotation
     *
     * @param annot the BrainAnnotation to be queried. All of its children are
     *              considered.
     * @return the ratio between the no. of branch points associated with
     * {@code annot} and the total number of branch points in the tree.
     */
    public double getNBranchPointsNorm(final BrainAnnotation annot) {
        return getNBranchPointsNorm(annot, true);
    }

    /**
     * Gets the percentage of branch points in the analyzed tree associated with the
     * specified annotation
     *
     * @param annot           the BrainAnnotation to be queried.
     * @param includeChildren whether children of {@code annot} should be included
     * @return the ratio between the no. of branch points associated with
     * {@code annot} and the total number of branch points in the tree.
     */
    public double getNBranchPointsNorm(final BrainAnnotation annot, final boolean includeChildren) {
        return (double) (getNBranchPoints(annot, includeChildren)) / (double) (joints.size());
    }

    /**
     * Gets the cable length.
     *
     * @return the cable length of the tree
     */
    public double getCableLength() {
        return sumLength(tree.list());
    }

    /**
     * Gets the cable length associated with the specified compartment (neuropil
     * label).
     *
     * @param compartment the query compartment (null not allowed). All of its
     *                    children will be considered
     * @return the filtered cable length
     */
    public double getCableLength(final BrainAnnotation compartment) {
        return getCableLength(compartment, true);
    }

    /**
     * Gets the cable length associated with the specified compartment (neuropil
     * label) as a ratio of total length.
     *
     * @param compartment the query compartment (null not allowed). All of its
     *                    children will be considered
     * @return the filtered cable length normalized to total cable length
     */
    public double getCableLengthNorm(final BrainAnnotation compartment) {
        return getCableLength(compartment, true) / getCableLength();
    }

    /**
     * Gets the cable length associated with the specified compartment (neuropil
     * label) as a ratio of total length.
     *
     * @param compartment     the query compartment (null not allowed)
     * @param includeChildren whether children of {@code compartment} should be
     *                        included
     * @return the filtered cable length normalized to total cable length
     */
    public double getCableLengthNorm(final BrainAnnotation compartment, final boolean includeChildren) {
        return getCableLength(compartment, includeChildren) / getCableLength();
    }

    /**
     * Gets the cable length associated with the specified compartment (neuropil
     * label).
     *
     * @param compartment     the query compartment (null not allowed)
     * @param includeChildren whether children of {@code compartment} should be included
     * @return the filtered cable length
     */
    public double getCableLength(final BrainAnnotation compartment, final boolean includeChildren) {
        final DirectedWeightedGraph graph = tree.getGraph();
        final NodeStatistics<SWCPoint> nodeStats = new NodeStatistics<>(graph.vertexSet());
        final DirectedWeightedSubgraph subgraph = graph.getSubgraph(new HashSet<>(nodeStats.get(compartment, includeChildren)));
        return subgraph.sumEdgeWeights(true);
    }

    protected boolean contains(final BrainAnnotation annot, final BrainAnnotation annotToBeTested, final boolean includeChildren) {
        if (includeChildren) return annot.equals(annotToBeTested) || annot.isParentOf(annotToBeTested);
        return annot.equals(annotToBeTested);
    }

    private double sumLength(final Collection<Path> paths) {
        double totalLength = 0d;
        for (final Path p : paths) {
            if (p.getStartJoins() != null) {
                totalLength += p.getStartJoinsPoint().distanceTo(p.getNode(0));
            }
            totalLength += p.getLength();
        }
        return totalLength;
    }

    static class StatisticsInstance {

        private SummaryStatistics sStatistics;
        private DescriptiveStatistics dStatistics;

        StatisticsInstance(final SummaryStatistics sStatistics) {
            this.sStatistics = sStatistics;
        }

        StatisticsInstance(final DescriptiveStatistics dStatistics) {
            this.dStatistics = dStatistics;
        }

        void addNaN() {
            if (sStatistics != null) sStatistics.addValue(Double.NaN);
            else dStatistics.addValue(Double.NaN);
        }
    
        void addValue(final double value) {
            if (Double.isNaN(value)) return;
            if (sStatistics != null) sStatistics.addValue(value);
            else dStatistics.addValue(value);
        }

        long getN() {
            return (sStatistics != null) ? sStatistics.getN() : dStatistics.getN();
        }

    }

    class LastDstats {

        final DescriptiveStatistics dStats;
        private final String measurement;
        private final int size;

        LastDstats(final String measurement, final DescriptiveStatistics dStats) {
            this.measurement = measurement;
            this.dStats = dStats;
            size = tree.size();
        }
    }

    class HDPlus extends HistogramDatasetPlus {

        HDPlus(final String measurement) {
            super(measurement);
            getDescriptiveStats(measurement);
            for (final double v : lastDstats.dStats.getValues()) {
                dStats.addValue(v);
            }
        }

        SNTTable getTable() {
            final SNTTable table = new SNTTable();
            table.addColumn(label, dStats.getValues());
            return table;
        }
    }

    /* IDE debug method */
    public static void main(final String[] args) {
        final MouseLightLoader loader = new MouseLightLoader("AA0015");
        final Tree axon = loader.getTree("axon");
        final TreeStatistics tStats = new TreeStatistics(axon);
        final int depth = 6;// Integer.MAX_VALUE;

        // retrieve some metrics:
        tStats.getHistogram("fractal dimension").show();
        NodeStatistics<?> nStats = new NodeStatistics<>(tStats.getTips());
        SNTChart hist = nStats.getAnnotatedHistogram(depth);
        hist.annotate("No. of tips: " + tStats.getTips().size());
        hist.show();

        // retrieve annotated lengths
        // AllenUtils.assignHemisphereTags(axon.getGraph());
        hist = tStats.getAnnotatedLengthHistogram(depth);
        AllenCompartment somaCompartment = loader.getSomaCompartment();
        if (somaCompartment.getOntologyDepth() > depth)
            somaCompartment = somaCompartment.getAncestor(depth - somaCompartment.getOntologyDepth());
        hist.annotateCategory(somaCompartment.acronym(), "soma");
        hist.show();
        hist = tStats.getAnnotatedLengthHistogram(depth, "left");
        hist.annotateCategory(somaCompartment.acronym(), "soma");
        hist.show();
        hist = tStats.getAnnotatedLengthHistogram(depth, "right");
        hist.annotateCategory(somaCompartment.acronym(), "soma");
        hist.show();
        hist = tStats.getAnnotatedLengthHistogram(depth, "ratio");
        hist.annotateCategory(somaCompartment.acronym(), "soma");
        hist.setFontSize(25);
        hist.show();
        hist = tStats.getFlowPlot("Cable length", tStats.getAnnotatedLength(depth).keySet());
        hist.show();
    }
}
