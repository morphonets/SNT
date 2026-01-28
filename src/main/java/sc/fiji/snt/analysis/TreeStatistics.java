/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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
    /** Flag specifying "Branch extension angle" */
    public static final String BRANCH_EXTENSION_ANGLE = "Branch extension angle";
    /** Flag specifying "Branch extension angle (Rel.)" */
    public static final String BRANCH_EXTENSION_ANGLE_REL = "Branch extension angle (Rel.)";
    /** Flag specifying Branch extension angle XY */
    public static final String BRANCH_EXTENSION_ANGLE_XY = "Branch extension angle XY";
    /** Flag specifying Branch extension angle XZ */
    public static final String BRANCH_EXTENSION_ANGLE_XZ = "Branch extension angle XZ";
    /** Flag specifying Branch extension angle ZY */
    public static final String BRANCH_EXTENSION_ANGLE_ZY = "Branch extension angle ZY";
    /** Flag specifying Inner branches: Extension angle */
    public static final String INNER_EXTENSION_ANGLE = "Inner branches: Extension angle";
    /** Flag specifying Inner branches: Extension angle (Rel.) */
    public static final String INNER_EXTENSION_ANGLE_REL = "Inner branches: Extension angle (Rel.)";
    /** Flag specifying "Inner branches: Extension angle XY" */
    public static final String INNER_EXTENSION_ANGLE_XY = "Inner branches: Extension angle XY";
    /** Flag specifying "Inner branches: Extension angle XZ" */
    public static final String INNER_EXTENSION_ANGLE_XZ = "Inner branches: Extension angle XZ";
    /** Flag specifying "Inner branches: Extension angle ZY" */
    public static final String INNER_EXTENSION_ANGLE_ZY = "Inner branches: Extension angle ZY";
    /** Flag specifying "Primary branches: Extension angle" */
    public static final String PRIMARY_EXTENSION_ANGLE = "Primary branches: Extension angle";
    /** Flag specifying "Primary branches: Extension angle XY" */
    public static final String PRIMARY_EXTENSION_ANGLE_XY = "Primary branches: Extension angle XY";
    /** Flag specifying "Primary branches: Extension angle XZ" */
    public static final String PRIMARY_EXTENSION_ANGLE_XZ = "Primary branches: Extension angle XZ";
    /** Flag specifying "Primary branches: Extension angle ZY" */
    public static final String PRIMARY_EXTENSION_ANGLE_ZY = "Primary branches: Extension angle ZY";
    /** Flag specifying "Terminal branches: Extension angle" */
    public static final String TERMINAL_EXTENSION_ANGLE = "Terminal branches: Extension angle";
    /** Flag specifying "Terminal branches: Extension angle (Rel.)" */
    public static final String TERMINAL_EXTENSION_ANGLE_REL = "Terminal branches: Extension angle (Rel.)";
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
    /** Flag specifying "Path extension angle" */
    public static final String PATH_EXT_ANGLE = "Path extension angle";
    /** Flag specifying "Path extension angle (Rel.)"*/
    public static final String PATH_EXT_ANGLE_REL = "Path extension angle (Rel.)";
    /** Flag specifying "Path extension angle XY" */
    public static final String PATH_EXT_ANGLE_XY = "Path extension angle XY";
    /** Flag specifying "Path extension angle XZ" */
    public static final String PATH_EXT_ANGLE_XZ = "Path extension angle XZ";
    /** Flag specifying "Path extension angle ZY" */
    public static final String PATH_EXT_ANGLE_ZY = "Path extension angle ZY";
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
    /** Flag specifying "Longest shortest path: Extension angle" statistics. */
    public static final String GRAPH_DIAMETER_ANGLE = "Longest shortest path: Extension angle";
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
    /** Flag specifying "Convex hull: Compactness" statistics */
    public static final String CONVEX_HULL_COMPACTNESS_3D = "Convex hull: " + ConvexHullAnalyzer.COMPACTNESS_3D;
    /** Flag specifying "Convex hull: Eccentricity" statistics */
    public static final String CONVEX_HULL_ECCENTRICITY_2D = "Convex hull: " + ConvexHullAnalyzer.ECCENTRICITY_2D;

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
            GRAPH_DIAMETER_ANGLE, GRAPH_DIAMETER_ANGLE_XY, GRAPH_DIAMETER_ANGLE_XZ, GRAPH_DIAMETER_ANGLE_ZY, //
            BRANCH_CONTRACTION, BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH, BRANCH_MEAN_RADIUS, BRANCH_SURFACE_AREA, //
            BRANCH_VOLUME, COMPLEXITY_INDEX_ACI, COMPLEXITY_INDEX_DCI, //
            CONVEX_HULL_BOUNDARY_SIZE, CONVEX_HULL_BOXIVITY, CONVEX_HULL_CENTROID_ROOT_DISTANCE, CONVEX_HULL_ELONGATION, //
            CONVEX_HULL_ROUNDNESS, CONVEX_HULL_SIZE, CONVEX_HULL_COMPACTNESS_3D, CONVEX_HULL_ECCENTRICITY_2D, //
            DEPTH, BRANCH_EXTENSION_ANGLE, BRANCH_EXTENSION_ANGLE_REL, //
            BRANCH_EXTENSION_ANGLE_XY, BRANCH_EXTENSION_ANGLE_XZ, BRANCH_EXTENSION_ANGLE_ZY, //
            INNER_EXTENSION_ANGLE, INNER_EXTENSION_ANGLE_REL, //
            INNER_EXTENSION_ANGLE_XY, INNER_EXTENSION_ANGLE_XZ, INNER_EXTENSION_ANGLE_ZY, //
            PRIMARY_EXTENSION_ANGLE, //
            PRIMARY_EXTENSION_ANGLE_XY, PRIMARY_EXTENSION_ANGLE_XZ, PRIMARY_EXTENSION_ANGLE_ZY, //
            TERMINAL_EXTENSION_ANGLE, TERMINAL_EXTENSION_ANGLE_REL, //
            TERMINAL_EXTENSION_ANGLE_XY, TERMINAL_EXTENSION_ANGLE_XZ, TERMINAL_EXTENSION_ANGLE_ZY, //
            GRAPH_DIAMETER, HEIGHT, INNER_LENGTH, //
            INTER_NODE_ANGLE, INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, LENGTH, //
            N_BRANCH_NODES, N_BRANCH_POINTS, N_BRANCHES, N_FITTED_PATHS, N_INNER_BRANCHES, N_NODES, N_PATH_NODES, N_PATHS, //
            N_PRIMARY_BRANCHES, N_SPINES, N_TERMINAL_BRANCHES, N_TIPS, NODE_RADIUS, PARTITION_ASYMMETRY, PATH_CHANNEL, //
            PATH_CONTRACTION, PATH_FRACTAL_DIMENSION, PATH_FRAME,  //
            PATH_EXT_ANGLE, PATH_EXT_ANGLE_REL, PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY, //
            PATH_LENGTH, PATH_MEAN_RADIUS, PATH_SPINE_DENSITY, //
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
                        PATH_EXT_ANGLE, PATH_EXT_ANGLE_REL, PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY, //
                        PATH_LENGTH, PATH_MEAN_RADIUS, PATH_SPINE_DENSITY, PATH_N_SPINES, PATH_ORDER, //
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
        // Try specific patterns first
        String result = guessSpecialMetrics(normGuess);
        if (result != null) return result;
        result = guessLengthMetrics(normGuess);
        if (result != null) return result;
        result = guessAngleMetrics(normGuess);
        if (result != null) return result;
        result = guessNodeMetrics(normGuess);
        if (result != null) return result;
        result = guessRadiusMetrics(normGuess);
        if (result != null) return result;
        result = guessSpineMetrics(normGuess);
        if (result != null) return result;
        result = guessCoordinateMetrics(normGuess);
        if (result != null) return result;
        if (normGuess.contains("values") || normGuess.contains("intensit")) {
            return VALUES;
        }
        return "unknown";
    }
    
    private static String guessSpecialMetrics(final String normGuess) {
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
        if (normGuess.contains("path") && normGuess.contains("order")) {
            return PATH_ORDER;
        }
        return null;
    }
    
    private static String guessLengthMetrics(final String normGuess) {
        if (!(normGuess.contains("length") || normGuess.contains("cable"))) {
            return null;
        }
        if (normGuess.contains("term")) return TERMINAL_LENGTH;
        if (normGuess.contains("prim")) return PRIMARY_LENGTH;
        if (normGuess.contains("inner")) return INNER_LENGTH;
        if (normGuess.contains("path")) return PATH_LENGTH;
        return BRANCH_LENGTH;
    }
    
    private static String guessAngleMetrics(final String normGuess) {
        if (!normGuess.contains("angle")) {
            return null;
        }
        // Extension angles with specific path types
        if (normGuess.contains("ext")) {
            return guessExtensionAngleMetrics(normGuess);
        }
        // Default branch extension angles
        return guessPlaneSpecificAngle(normGuess,
                BRANCH_EXTENSION_ANGLE, BRANCH_EXTENSION_ANGLE_XY, BRANCH_EXTENSION_ANGLE_XZ, BRANCH_EXTENSION_ANGLE_ZY);
    }
    
    private static String guessExtensionAngleMetrics(final String normGuess) {
        if (normGuess.contains("path")) {
            if (normGuess.contains("rel")) return PATH_EXT_ANGLE_REL;
            return guessPlaneSpecificAngle(normGuess, PATH_EXT_ANGLE, PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ,
                                          PATH_EXT_ANGLE_ZY);
        }
        if (normGuess.contains("term")) {
            return guessPlaneSpecificAngle(normGuess, TERMINAL_EXTENSION_ANGLE, TERMINAL_EXTENSION_ANGLE_XY,
                    TERMINAL_EXTENSION_ANGLE_XZ, TERMINAL_EXTENSION_ANGLE_ZY);
        }
        if (normGuess.contains("prim")) {
            return guessPlaneSpecificAngle(normGuess, PRIMARY_EXTENSION_ANGLE, PRIMARY_EXTENSION_ANGLE_XY,
                    PRIMARY_EXTENSION_ANGLE_XZ, PRIMARY_EXTENSION_ANGLE_ZY);
        }
        if (normGuess.contains("inner")) {
            return guessPlaneSpecificAngle(normGuess, INNER_EXTENSION_ANGLE, INNER_EXTENSION_ANGLE_XY,
                    INNER_EXTENSION_ANGLE_XZ, INNER_EXTENSION_ANGLE_ZY);
        }
        return null;
    }
    
    private static String guessPlaneSpecificAngle(final String normGuess, 
                                                 final String xzAngle, final String zyAngle, 
                                                 final String xyAngle, final String defaultAngle) {
        if (normGuess.contains("xz")) return xzAngle;
        if (normGuess.contains("zy")) return zyAngle;
        if (normGuess.contains("xy")) return xyAngle;
        return defaultAngle;
    }
    
    private static String guessNodeMetrics(final String normGuess) {
        if (normGuess.contains("bp") || normGuess.contains("branch points") || normGuess.contains("junctions")) {
            return N_BRANCH_POINTS;
        }
        if (normGuess.contains("nodes")) {
            return N_NODES;
        }
        if (normGuess.contains("node") && (normGuess.contains("dis") || normGuess.contains("dx"))) {
            if (normGuess.contains("sq")) return INTER_NODE_DISTANCE_SQUARED;
            if (normGuess.contains("angle")) return INTER_NODE_ANGLE;
            return INTER_NODE_DISTANCE;
        }
        return null;
    }
    
    private static String guessRadiusMetrics(final String normGuess) {
        if (!normGuess.contains("radi")) {
            return null;
        }
        
        if (normGuess.contains("mean") || normGuess.contains("avg") || normGuess.contains("average")) {
            return (normGuess.contains("path")) ? PATH_MEAN_RADIUS : BRANCH_MEAN_RADIUS;
        }
        return NODE_RADIUS;
    }
    
    private static String guessSpineMetrics(final String normGuess) {
        if (!(normGuess.contains("spines") || normGuess.contains("varicosities"))) {
            return null;
        }

        if (normGuess.contains("dens") || normGuess.contains("path")) {
            return PATH_SPINE_DENSITY;
        }
        return N_SPINES;
    }

    private static String guessCoordinateMetrics(final String normGuess) {
        if (normGuess.matches(".*\\bx\\b.*")) return X_COORDINATES;
        if (normGuess.matches(".*\\by\\b.*")) return Y_COORDINATES;
        if (normGuess.matches(".*\\bz\\b.*")) return Z_COORDINATES;
        return null;
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

    /**
     * Assembles a polar histogram for the specified metric (assumed to be an angular
     * measurements (e.g., branch angles, path orientations).
     *
     * @param metric the metric to be plotted (e.g., {@link #BRANCH_EXTENSION_ANGLE_XY},
     *               {@link #PATH_EXT_ANGLE_XY}, etc.)
     * @return the polar histogram chart
     * @throws UnknownMetricException if the metric is not recognized
     * @see #getHistogram(String)
     */
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
        try {
            switch (m) {
                case BRANCH_CONTRACTION, BRANCH_EXTENSION_ANGLE, BRANCH_EXTENSION_ANGLE_REL,
                     BRANCH_EXTENSION_ANGLE_XY, BRANCH_EXTENSION_ANGLE_XZ, BRANCH_EXTENSION_ANGLE_ZY,
                     BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH, BRANCH_MEAN_RADIUS, BRANCH_SURFACE_AREA, BRANCH_VOLUME ->
                        assembleBranchStats(stat, m);
                case COMPLEXITY_INDEX_ACI -> {
                    // implementation: doi: 10.1523/JNEUROSCI.4434-06.2007
                    final double sumPathOrders = tree.list().stream().mapToDouble(p -> p.getOrder() - 1).sum();
                    stat.addValue(sumPathOrders / tree.list().size());
                }
                case COMPLEXITY_INDEX_DCI -> {
                    // Implementations in chronological order:
                    // 1) www.jneurosci.org/content/19/22/9928#F6
                    // 2) https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3373517/
                    // 3) https://journals.physiology.org/doi/full/10.1152/jn.00829.2011
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
                }
                case CONVEX_HULL_BOUNDARY_SIZE, CONVEX_HULL_BOXIVITY, CONVEX_HULL_ELONGATION, CONVEX_HULL_ROUNDNESS,
                     CONVEX_HULL_SIZE, CONVEX_HULL_ECCENTRICITY_2D, CONVEX_HULL_COMPACTNESS_3D ->
                        stat.addValue(getConvexHullMetric(m));
                case CONVEX_HULL_CENTROID_ROOT_DISTANCE -> {
                    final PointInImage root = tree.getRoot();
                    stat.addValue(root == null ? Double.NaN : getConvexAnalyzer().getCentroid().distanceTo(root));
                }
                case ROOT_ANGLE_B_FACTOR, ROOT_ANGLE_C_BIAS, ROOT_ANGLE_M_DIRECTION ->
                        stat.addValue(getRootAngleMetric(m));
                case DEPTH -> stat.addValue(getDepth());
                case GRAPH_DIAMETER, GRAPH_DIAMETER_ANGLE, GRAPH_DIAMETER_ANGLE_XY, GRAPH_DIAMETER_ANGLE_XZ, GRAPH_DIAMETER_ANGLE_ZY ->
                        assembleGraphDiameterStats(stat, m);
                case HEIGHT -> stat.addValue(getHeight());
                case INNER_EXTENSION_ANGLE, INNER_EXTENSION_ANGLE_REL, INNER_EXTENSION_ANGLE_XY,
                     INNER_EXTENSION_ANGLE_XZ, INNER_EXTENSION_ANGLE_ZY, INNER_LENGTH, N_INNER_BRANCHES ->
                        assembleInnerBranchStats(stat, m);
                case INTER_NODE_ANGLE -> {
                    for (final Path p : tree.list()) {
                        if (p.size() < 3) continue;
                        for (int i = 2; i < p.size(); i++) {
                            stat.addValue(p.getAngle(i));
                        }
                    }
                }
                case INTER_NODE_DISTANCE -> {
                    for (final Path p : tree.list()) {
                        if (p.size() < 2) continue;
                        for (int i = 1; i < p.size(); i += 1) {
                            stat.addValue(p.getNode(i).distanceTo(p.getNode(i - 1)));
                        }
                    }
                }
                case INTER_NODE_DISTANCE_SQUARED -> {
                    for (final Path p : tree.list()) {
                        if (p.size() < 2) continue;
                        for (int i = 1; i < p.size(); i += 1) {
                            stat.addValue(p.getNode(i).distanceSquaredTo(p.getNode(i - 1)));
                        }
                    }
                }
                case LENGTH -> stat.addValue(getCableLength());
                case N_BRANCH_NODES -> getBranches().forEach(b -> stat.addValue(b.size()));
                case N_BRANCH_POINTS -> stat.addValue(getBranchPoints().size());
                case N_BRANCHES -> stat.addValue(getNBranches());
                case N_FITTED_PATHS -> stat.addValue(getNFittedPaths());
                case N_NODES -> stat.addValue(getNNodes());
                case N_PATH_NODES -> tree.list().forEach(path -> stat.addValue(path.size()));
                case N_PATHS -> stat.addValue(getNPaths());
                case N_SPINES -> stat.addValue(getNoSpinesOrVaricosities());
                case N_TIPS -> stat.addValue(getTips().size());
                case NODE_RADIUS -> {
                    for (final Path p : tree.list()) {
                        for (int i = 0; i < p.size(); i++) {
                            stat.addValue(p.getNodeRadius(i));
                        }
                    }
                }
                case PARTITION_ASYMMETRY -> getPartitionAsymmetry().forEach(stat::addValue);
                case PATH_CHANNEL -> tree.list().forEach(path -> stat.addValue(path.getChannel()));
                case PATH_CONTRACTION -> tree.list().forEach(path -> stat.addValue(path.getContraction()));
                case PATH_EXT_ANGLE_XY -> {
                    for (final Path p : tree.list())
                        stat.addValue(p.getExtensionAngleXY());
                }
                case PATH_EXT_ANGLE_XZ -> {
                    for (final Path p : tree.list())
                        stat.addValue(p.getExtensionAngleXZ());
                }
                case PATH_EXT_ANGLE_ZY -> {
                    for (final Path p : tree.list())
                        stat.addValue(p.getExtensionAngleZY());
                }
                case PATH_EXT_ANGLE, PATH_EXT_ANGLE_REL -> {
                    final boolean rel = PATH_EXT_ANGLE_REL.equals(m);
                    for (final Path p : tree.list())
                        stat.addValue(p.getExtensionAngle3D(rel));
                }
                case PATH_FRACTAL_DIMENSION -> tree.list().forEach(p -> stat.addValue(p.getFractalDimension()));
                case PATH_FRAME -> tree.list().forEach(p -> stat.addValue(p.getFrame()));
                case PATH_LENGTH -> tree.list().forEach(p -> stat.addValue(p.getLength()));
                case PATH_MEAN_RADIUS -> tree.list().forEach(p -> stat.addValue(p.getMeanRadius()));
                case PATH_N_SPINES -> tree.list().forEach(p -> stat.addValue(p.getSpineOrVaricosityCount()));
                case PATH_ORDER -> tree.list().forEach(p -> stat.addValue(p.getOrder()));
                case PATH_SPINE_DENSITY ->
                        tree.list().forEach(p -> stat.addValue(p.getSpineOrVaricosityCount() / p.getLength()));
                case PATH_SURFACE_AREA -> tree.list().forEach(p -> stat.addValue(p.getApproximatedSurface()));
                case PATH_VOLUME -> tree.list().forEach(p -> stat.addValue(p.getApproximatedVolume()));
                case PRIMARY_EXTENSION_ANGLE, PRIMARY_EXTENSION_ANGLE_XY,
                     PRIMARY_EXTENSION_ANGLE_XZ, PRIMARY_EXTENSION_ANGLE_ZY, PRIMARY_LENGTH, N_PRIMARY_BRANCHES ->
                        assemblePrimaryBranchStats(stat, m);
                case REMOTE_BIF_ANGLES -> getRemoteBifAngles().forEach(stat::addValue);
                case SHOLL_DECAY, SHOLL_KURTOSIS, SHOLL_MAX_FITTED, SHOLL_MAX_FITTED_RADIUS, SHOLL_MAX_VALUE,
                     SHOLL_MEAN_VALUE, SHOLL_N_MAX, SHOLL_N_SECONDARY_MAX, SHOLL_POLY_FIT_DEGREE,
                     SHOLL_RAMIFICATION_INDEX,
                     SHOLL_SKEWNESS, SHOLL_SUM_VALUE -> stat.addValue(getShollMetric(m).doubleValue());
                case STRAHLER_NUMBER -> stat.addValue(getStrahlerNumber());
                case STRAHLER_RATIO -> stat.addValue(getStrahlerBifurcationRatio());
                case SURFACE_AREA -> stat.addValue(tree.getApproximatedSurface());
                case TERMINAL_EXTENSION_ANGLE, TERMINAL_EXTENSION_ANGLE_REL, TERMINAL_EXTENSION_ANGLE_XY,
                     TERMINAL_EXTENSION_ANGLE_XZ, TERMINAL_EXTENSION_ANGLE_ZY, TERMINAL_LENGTH, N_TERMINAL_BRANCHES ->
                        assembleTerminalBranchStats(stat, m);
                case VALUES -> {
                    for (final Path p : tree.list()) {
                        if (!p.hasNodeValues()) continue;
                        for (int i = 0; i < p.size(); i++) stat.addValue(p.getNodeValue(i));
                    }
                    if (stat.getN() == 0) throw new IllegalArgumentException("Tree has no values assigned");
                }
                case VOLUME -> stat.addValue(tree.getApproximatedVolume());
                case WIDTH -> stat.addValue(getWidth());
                case X_COORDINATES -> {
                    for (final Path p : tree.list()) {
                        for (int i = 0; i < p.size(); i++) stat.addValue(p.getNode(i).x);
                    }
                }
                case Y_COORDINATES -> {
                    for (final Path p : tree.list()) {
                        for (int i = 0; i < p.size(); i++) stat.addValue(p.getNode(i).y);
                    }
                }
                case Z_COORDINATES -> {
                    for (final Path p : tree.list()) {
                        for (int i = 0; i < p.size(); i++) stat.addValue(p.getNode(i).z);
                    }
                }
                default -> throw new IllegalArgumentException("Unrecognized parameter " + measurement);
            }
        } catch (final IllegalArgumentException iae) {
            SNTUtils.log("Error: " + iae.getMessage());
            stat.addNaN();
        }
    }

    private void assembleBranchStats(final StatisticsInstance stat, final String branchMetric) {
        switch (branchMetric) {
            case BRANCH_CONTRACTION ->
                    getBranches().forEach(branch -> stat.addValue(branch.getContraction()));
            case BRANCH_EXTENSION_ANGLE ->
                    getBranches().forEach(branch -> stat.addValue(branch.getExtensionAngle3D(false)));
            case BRANCH_EXTENSION_ANGLE_REL -> {
                final StrahlerAnalyzer sa = getStrahlerAnalyzer();
                getBranches().forEach(branch -> {
                    stat.addValue(sa.getRelativeExtensionAngle(branch));
                });
            }
            case BRANCH_EXTENSION_ANGLE_XY ->
                    getBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXY()));
            case BRANCH_EXTENSION_ANGLE_XZ ->
                    getBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXZ()));
            case BRANCH_EXTENSION_ANGLE_ZY ->
                    getBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleZY()));
            case BRANCH_FRACTAL_DIMENSION ->
                    getBranches().forEach(branch -> stat.addValue(branch.getFractalDimension()));
            case BRANCH_LENGTH -> getBranches().forEach(branch -> stat.addValue(branch.getLength()));
            case BRANCH_MEAN_RADIUS -> getBranches().forEach(branch -> stat.addValue(branch.getMeanRadius()));
            case BRANCH_SURFACE_AREA -> getBranches().forEach(branch -> stat.addValue(branch.getApproximatedSurface()));
            case BRANCH_VOLUME -> getBranches().forEach(branch -> stat.addValue(branch.getApproximatedVolume()));
        }
    }

    private void assembleGraphDiameterStats(final StatisticsInstance stat, final String graphDiameterMetric) {
        final Path graphDiameter = tree.getGraph().getLongestPath(true);
        switch (graphDiameterMetric) {
            case GRAPH_DIAMETER -> stat.addValue(graphDiameter.getLength());
            case GRAPH_DIAMETER_ANGLE -> stat.addValue(graphDiameter.getExtensionAngle3D(false));
            case GRAPH_DIAMETER_ANGLE_XY -> stat.addValue(graphDiameter.getExtensionAngleXY());
            case GRAPH_DIAMETER_ANGLE_XZ -> stat.addValue(graphDiameter.getExtensionAngleXZ());
            case GRAPH_DIAMETER_ANGLE_ZY -> stat.addValue(graphDiameter.getExtensionAngleZY());
        }
    }

    private void assembleInnerBranchStats(final StatisticsInstance stat, final String innerBranchMetric) {
        switch (innerBranchMetric) {
            case INNER_EXTENSION_ANGLE_XY ->
                    getInnerBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXY()));
            case INNER_EXTENSION_ANGLE_REL -> {
                final StrahlerAnalyzer sa = getStrahlerAnalyzer();
                getInnerBranches().forEach(branch -> stat.addValue(sa.getRelativeExtensionAngle(branch)));
            }
            case INNER_EXTENSION_ANGLE_XZ ->
                    getInnerBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXZ()));
            case INNER_EXTENSION_ANGLE_ZY ->
                    getInnerBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleZY()));
            case INNER_EXTENSION_ANGLE ->
                    getInnerBranches().forEach(branch -> stat.addValue(branch.getExtensionAngle3D(false)));
            case INNER_LENGTH -> getInnerBranches().forEach(branch -> stat.addValue(branch.getLength()));
            case N_INNER_BRANCHES -> stat.addValue(getInnerBranches().size());
        }
    }

    private void assembleTerminalBranchStats(final StatisticsInstance stat, final String terminalBranchMetric) {
        switch (terminalBranchMetric) {
            case TERMINAL_EXTENSION_ANGLE ->
                    getTerminalBranches().forEach(branch -> stat.addValue(branch.getExtensionAngle3D(false)));
            case TERMINAL_EXTENSION_ANGLE_REL -> {
                final StrahlerAnalyzer sa = getStrahlerAnalyzer();
                getTerminalBranches().forEach(branch -> stat.addValue(sa.getRelativeExtensionAngle(branch)));
            }
            case TERMINAL_EXTENSION_ANGLE_XY ->
                    getTerminalBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXY()));
            case TERMINAL_EXTENSION_ANGLE_XZ ->
                    getTerminalBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXZ()));
            case TERMINAL_EXTENSION_ANGLE_ZY ->
                    getTerminalBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleZY()));
            case TERMINAL_LENGTH -> getTerminalBranches().forEach(branch -> stat.addValue(branch.getLength()));
            case N_TERMINAL_BRANCHES -> stat.addValue(getTerminalBranches().size());
        }
    }

    private void assemblePrimaryBranchStats(final StatisticsInstance stat, final String primaryBranchMetric) {
        switch (primaryBranchMetric) {
            case PRIMARY_EXTENSION_ANGLE ->
                    getPrimaryBranches().forEach(branch -> stat.addValue(branch.getExtensionAngle3D(false)));
            case PRIMARY_EXTENSION_ANGLE_XY ->
                    getPrimaryBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXY()));
            case PRIMARY_EXTENSION_ANGLE_XZ ->
                    getPrimaryBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleXZ()));
            case PRIMARY_EXTENSION_ANGLE_ZY ->
                    getPrimaryBranches().forEach(branch -> stat.addValue(branch.getExtensionAngleZY()));
            case PRIMARY_LENGTH -> getPrimaryBranches().forEach(branch -> stat.addValue(branch.getLength()));
            case N_PRIMARY_BRANCHES -> stat.addValue(getPrimaryBranches().size());
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
        try {
            if (getContext() == null) {
                SNTUtils.getContext().inject(this);
            }
            final String displayName = (tableTitle == null) ? "SNT Measurements" : tableTitle;
            final Display<?> display = displayService.getDisplay(displayName);
            if (display != null) {
                display.update();
            } else {
                displayService.createDisplay(displayName, table);
            }
        } catch (final Exception ignored) {
            System.out.println(SNTTable.toString(table, 0, table.getRowCount() - 1));
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
     * Gets the position of all tips in the analyzed tree that are further distant than the
     * specified distance from the root.
     *
     * @param minDistance the minimum distance from root (inclusive, in the same units as tree coordinates)
     * @return the set of terminal points distant from root by at least {@code minDistance},
     * or an empty set if no tips are found or if the tree has no root
     */
    public Set<PointInImage> getTips(final double minDistance) {
        return getTips(minDistance, Double.MAX_VALUE);
    }

    /**
     * Gets the position of all tips in the analyzed tree that are within the specified
     * distance range from the root. This method is useful for analyzing tree morphology
     * within specific distance bands from the soma.
     *
     * @param minDistance the minimum distance from root (inclusive, in the same units as tree coordinates)
     * @param maxDistance the maximum distance from root (inclusive, in the same units as tree coordinates)
     * @return the set of terminal points within the specified distance range from root,
     * or an empty set if no tips are found within the range or if the tree has no root
     */
    public Set<PointInImage> getTips(final double minDistance, final double maxDistance) {
        if (minDistance < 0 || maxDistance < 0 || minDistance > maxDistance) {
            throw new IllegalArgumentException("Distances must be non-negative and min distance cannot be greater than max");
        }
        final PointInImage root = tree.getRoot();
        if (root == null) {
            return new HashSet<>();
        }
        // Get all tips first
        final Set<PointInImage> allTips = getTips();
        final Set<PointInImage> filteredTips = new HashSet<>();

        // Filter tips by distance range from root
        final double minSq = minDistance * minDistance;
        final double maxSq = maxDistance * maxDistance;
        for (final PointInImage tip : allTips) {
            final double distanceFromRoot = tip.distanceSquaredTo(root);
            if (distanceFromRoot >= minSq && distanceFromRoot <= maxSq) {
                filteredTips.add(tip);
            }
        }

        return filteredTips;
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
            joints.addAll(p.getBranchPoints());
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
            if (p.getParentPath() != null) {
                totalLength += p.getBranchPoint().distanceTo(p.getNode(0));
            }
            totalLength += p.getLength();
        }
        return totalLength;
    }

    /** Clears internal caches and mappings to free memory. */
    public void dispose() {
        tree = null;
        primaryBranches = null;
        innerBranches = null;
        terminalBranches = null;
        tips = null;
        table = null;
        lastDstats = null;
        unfilteredTree = null;
        joints = null;
        tableTitle = null;
        if (sAnalyzer != null) sAnalyzer.dispose();
        sAnalyzer = null;
        shllAnalyzer = null;
        convexAnalyzer = null;
        rootAngleAnalyzer = null;
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
