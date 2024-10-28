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

import java.awt.Dimension;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.AnalysisUtils.HistogramDatasetPlus;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;

/**
 * Computes summary and descriptive statistics from univariate properties of
 * Paths and Nodes in a {@link Tree}. For analysis of groups of Trees have a
 * look at {@link MultiTreeStatistics} and {@link GroupedTreeStatistics}.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class TreeStatistics extends TreeAnalyzer {

	// branch angles
	/** Metric: Branch extension angle XY */
	public static final String BRANCH_EXTENSION_ANGLE_XY = "Branch extension angle XY";
	/** Metric: Branch extension angle XZ */
	public static final String BRANCH_EXTENSION_ANGLE_XZ = "Branch extension angle XZ";
	/** Metric: Branch extension angle ZY */
	public static final String BRANCH_EXTENSION_ANGLE_ZY = "Branch extension angle ZY";
	/** Flag for {@value #INNER_EXTENSION_ANGLE_XY} analysis. */
	public static final String INNER_EXTENSION_ANGLE_XY = "Inner branches: Extension angle XY";
	/** Flag for {@value #INNER_EXTENSION_ANGLE_XZ} analysis. */
	public static final String INNER_EXTENSION_ANGLE_XZ = "Inner branches: Extension angle XZ";
	/** Flag for {@value #INNER_EXTENSION_ANGLE_ZY} analysis. */
	public static final String INNER_EXTENSION_ANGLE_ZY = "Inner branches: Extension angle ZY";
	/** Flag for {@value #PRIMARY_EXTENSION_ANGLE_XY} analysis. */
	public static final String PRIMARY_EXTENSION_ANGLE_XY = "Primary branches: Extension angle XY";
	/** Flag for {@value #PRIMARY_EXTENSION_ANGLE_XZ} analysis. */
	public static final String PRIMARY_EXTENSION_ANGLE_XZ = "Primary branches: Extension angle XZ";
	/** Flag for {@value #PRIMARY_EXTENSION_ANGLE_ZY} analysis. */
	public static final String PRIMARY_EXTENSION_ANGLE_ZY = "Primary branches: Extension angle ZY";
	/** Flag for {@value #TERMINAL_EXTENSION_ANGLE_XY} analysis. */
	public static final String TERMINAL_EXTENSION_ANGLE_XY = "Terminal branches: Extension angle XY";
	/** Flag for {@value #TERMINAL_EXTENSION_ANGLE_XZ} analysis. */
	public static final String TERMINAL_EXTENSION_ANGLE_XZ = "Terminal branches: Extension angle XZ";
	/** Flag for {@value #TERMINAL_EXTENSION_ANGLE_ZY} analysis. */
	public static final String TERMINAL_EXTENSION_ANGLE_ZY = "Terminal branches: Extension angle ZY";
	/** Flag for {@value #REMOTE_BIF_ANGLES} statistics. */
	public static final String REMOTE_BIF_ANGLES = "Remote bif. angles";
	// paths
	/** Flag for {@value #PATH_LENGTH} analysis. */
	public static final String PATH_LENGTH = "Path length";
	/** Flag for {@value #PATH_EXT_ANGLE_XY} analysis. */
	public static final String PATH_EXT_ANGLE_XY = "Path extension angle XY";
	/** Flag for {@value #PATH_EXT_ANGLE_XZ} analysis. */
	public static final String PATH_EXT_ANGLE_XZ = "Path extension angle XZ";
	/** Flag for {@value #PATH_EXT_ANGLE_ZY} analysis. */
	public static final String PATH_EXT_ANGLE_ZY = "Path extension angle ZY";
	/** Flag for {@value #PATH_EXT_ANGLE_REL_XY} analysis. */
	public static final String PATH_EXT_ANGLE_REL_XY = "Path extension angle XY (Rel.)";
	/** Flag for {@value #PATH_EXT_ANGLE_REL_XZ} analysis. */
	public static final String PATH_EXT_ANGLE_REL_XZ = "Path extension angle XZ (Rel.)";
	/** Flag for {@value #PATH_EXT_ANGLE_REL_ZY} analysis. */
	public static final String PATH_EXT_ANGLE_REL_ZY = "Path extension angle ZY (Rel.)";
	/** Flag for {@value #PATH_ORDER} statistics. */
	public static final String PATH_ORDER = "Path order";
	/** Flag for {@value #PATH_CHANNEL} statistics. */
	public static final String PATH_CHANNEL = "Path channel";
	/** Flag for {@value #PATH_FRAME} statistics. */
	public static final String PATH_FRAME = "Path frame";
	/** Flag for {@value #PATH_MEAN_RADIUS} statistics. */
	public static final String PATH_MEAN_RADIUS = "Path mean radius";
	/** Flag for {@value #PATH_SPINE_DENSITY} statistics */
	public static final String PATH_SPINE_DENSITY = "Path spine/varicosity density";
	/** Flag for {@value #PATH_CONTRACTION} statistics. */
	public static final String PATH_CONTRACTION = "Path contraction";
	/** Flag for {@value #PATH_FRACTAL_DIMENSION} statistics. */
	public static final String PATH_FRACTAL_DIMENSION = "Path fractal dimension";
	// branches
	/** Flag for {@value #BRANCH_LENGTH} analysis. */
	public static final String BRANCH_LENGTH = "Branch length";
	/** Flag for {@value #BRANCH_MEAN_RADIUS} analysis. */
	public static final String BRANCH_MEAN_RADIUS = "Branch mean radius";
	/** Flag for {@value #TERMINAL_LENGTH} analysis. */
	public static final String TERMINAL_LENGTH = "Terminal branches: Length";
	/** Flag for {@value #PRIMARY_LENGTH} analysis. */
	public static final String PRIMARY_LENGTH = "Primary branches: Length";
	/** Flag for {@value #INNER_LENGTH} analysis. */
	public static final String INNER_LENGTH = "Inner branches: Length";
	/** Flag for {@value #BRANCH_CONTRACTION} statistics. */
	public static final String BRANCH_CONTRACTION = "Branch contraction";
	/** Flag for {@value #BRANCH_FRACTAL_DIMENSION} statistics. */
	public static final String BRANCH_FRACTAL_DIMENSION = "Branch fractal dimension";
	// nodes
	/** Flag for {@value #NODE_RADIUS} statistics. */
	public static final String NODE_RADIUS = "Node radius";
	/** Flag for {@value #INTER_NODE_ANGLE} statistics. */
	public static final String INTER_NODE_ANGLE = "Internode angle";
	/** Flag for {@value #INTER_NODE_DISTANCE} statistics. */
	public static final String INTER_NODE_DISTANCE = "Internode distance";
	/** Flag for {@value #INTER_NODE_DISTANCE_SQUARED} statistics. */
	public static final String INTER_NODE_DISTANCE_SQUARED = "Internode distance (squared)";
	// counts
	/** Flag for {@value #N_BRANCH_POINTS} statistics. */
	public static final String N_BRANCH_POINTS = "No. of branch points";
	/** Flag for {@value #N_NODES} statistics. */
	public static final String N_NODES = "No. of nodes";
	/** Flag for {@value #N_PATH_NODES} statistics. */
	public static final String N_PATH_NODES = "No. of path nodes (path fragmentation)";
	/** Flag for {@value #N_BRANCH_NODES} statistics. */
	public static final String N_BRANCH_NODES = "No. of branch nodes (branch fragmentation)";
	/** Flag for {@value #N_PATHS} statistics */
	public static final String N_PATHS = "No. of paths";
	/** Flag for {@value #N_SPINES} statistics. */
	public static final String N_SPINES = "No. of spines/varicosities";
	/** Flag specifying {@value #N_BRANCHES} statistics */
	public static final String N_BRANCHES = "No. of branches";
	/** Flag specifying {@value #N_PRIMARY_BRANCHES} statistics */
	public static final String N_PRIMARY_BRANCHES = "No. of primary branches";
	/** Flag specifying {@value #N_INNER_BRANCHES} statistics */
	public static final String N_INNER_BRANCHES = "No. of inner branches";
	/** Flag specifying {@value #N_TERMINAL_BRANCHES} statistics */
	public static final String N_TERMINAL_BRANCHES = "No. of terminal branches";
	/** Flag specifying {@value #N_TIPS} statistics */
	public static final String N_TIPS = "No. of tips";
	/** Flag for {@value #N_FITTED_PATHS} statistics */
	public static final String N_FITTED_PATHS = "No. of fitted paths";
	/** Flag for {@value #PATH_N_SPINES} statistics */
	public static final String PATH_N_SPINES = "No. of spines/varicosities per path";
	//misc
	/** Flag for {@value #LENGTH} analysis. */
	public static final String LENGTH = "Cable length";
	/** Flag for {@value #COMPLEXITY_INDEX_ACI} statistics. */
	public static final String COMPLEXITY_INDEX_ACI = "Complexity index: ACI";
	/** Flag for {@value #COMPLEXITY_INDEX_DCI} statistics. */
	public static final String COMPLEXITY_INDEX_DCI = "Complexity index: DCI";
	/** Flag for {@value #X_COORDINATES} statistics. */
	public static final String X_COORDINATES = "X coordinates";
	/** Flag for {@value #Y_COORDINATES} statistics. */
	public static final String Y_COORDINATES = "Y coordinates";
	/** Flag for {@value #Z_COORDINATES} statistics. */
	public static final String Z_COORDINATES = "Z coordinates";
	/** Flag for {@value #WIDTH} statistics */
	public static final String WIDTH = "Width";
	/** Flag for {@value #HEIGHT} statistics */
	public static final String HEIGHT = "Height";
	/** Flag for {@value #DEPTH} statistics */
	public static final String DEPTH = "Depth";
	/** Flag for {@value #PARTITION_ASYMMETRY} statistics. */
	public static final String PARTITION_ASYMMETRY = "Partition asymmetry";
	// graph geodesics
	/** Flag for {@value #GRAPH_DIAMETER} statistics. */
	public static final String GRAPH_DIAMETER = "Longest shortest path: Length";
	/** Flag for {@value #GRAPH_DIAMETER_ANGLE_XY} statistics. */
	public static final String GRAPH_DIAMETER_ANGLE_XY = "Longest shortest path: Extension angle XY";
	/** Flag for {@value #GRAPH_DIAMETER_ANGLE_XZ} statistics. */
	public static final String GRAPH_DIAMETER_ANGLE_XZ = "Longest shortest path: Extension angle XZ";
	/** Flag for {@value #GRAPH_DIAMETER_ANGLE_ZY} statistics. */
	public static final String GRAPH_DIAMETER_ANGLE_ZY = "Longest shortest path: Extension angle ZY";
	// volume and surface
	/** Flag for {@value #VOLUME} statistics. */
	public static final String VOLUME = "Volume";
	/** Flag for {@value #BRANCH_VOLUME} statistics. */
	public static final String BRANCH_VOLUME = "Branch volume";
	/** Flag for {@value #PATH_VOLUME} statistics. */
	public static final String PATH_VOLUME = "Path volume";
	/** Flag for {@value #SURFACE_AREA} statistics. */
	public static final String SURFACE_AREA = "Surface area";
	/** Flag for {@value #BRANCH_SURFACE_AREA} statistics. */
	public static final String BRANCH_SURFACE_AREA = "Branch surface area";
	/** Flag for {@value #PATH_SURFACE_AREA} statistics. */
	public static final String PATH_SURFACE_AREA = "Path surface area";
	// Strahler
	/** Flag specifying {@link StrahlerAnalyzer#getRootNumber() Horton-Strahler number} statistics */
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
	/** Flag specifying {@value #SHOLL_N_MAX} statistics */
	public static final String SHOLL_N_MAX = "Sholl: " + ShollAnalyzer.N_MAX;
	/** Flag specifying {@value #SHOLL_N_SECONDARY_MAX} statistics */
	public static final String SHOLL_N_SECONDARY_MAX = "Sholl: " + ShollAnalyzer.N_SECONDARY_MAX;
	/** Flag specifying {@value #SHOLL_DECAY} statistics */
	public static final String SHOLL_DECAY = "Sholl: " + ShollAnalyzer.DECAY;
	/** Flag specifying {@value #SHOLL_MAX_FITTED} statistics */
	public static final String SHOLL_MAX_FITTED = "Sholl: " + ShollAnalyzer.MAX_FITTED;
	/** Flag specifying {@value #SHOLL_MAX_FITTED_RADIUS} statistics */
	public static final String SHOLL_MAX_FITTED_RADIUS = "Sholl: " + ShollAnalyzer.MAX_FITTED_RADIUS;
	/** Flag specifying {@value #SHOLL_POLY_FIT_DEGREE} statistics */
	public static final String SHOLL_POLY_FIT_DEGREE = "Sholl: " + ShollAnalyzer.POLY_FIT_DEGREE;
	/** Flag specifying {@value #SHOLL_KURTOSIS} statistics */
	public static final String SHOLL_KURTOSIS = "Sholl: " + ShollAnalyzer.KURTOSIS;
	/** Flag specifying {@value #SHOLL_SKEWENESS} statistics */
	public static final String SHOLL_SKEWENESS = "Sholl: " + ShollAnalyzer.SKEWENESS;
	/** Flag specifying {@value #SHOLL_RAMIFICATION_INDEX} statistics */
	public static final String SHOLL_RAMIFICATION_INDEX = "Sholl: " + ShollAnalyzer.RAMIFICATION_INDEX;
	//convex hull
	/** Flag specifying {@value #CONVEX_HULL_BOUNDARY_SIZE} statistics */
	public static final String CONVEX_HULL_BOUNDARY_SIZE = "Convex hull: " + ConvexHullAnalyzer.BOUNDARY_SIZE;
	/** Flag specifying {@value #CONVEX_HULL_SIZE} statistics */
	public static final String CONVEX_HULL_SIZE = "Convex hull: " + ConvexHullAnalyzer.SIZE;
	/** Flag specifying {@value #CONVEX_HULL_BOXIVITY} statistics */
	public static final String CONVEX_HULL_BOXIVITY= "Convex hull: " + ConvexHullAnalyzer.BOXIVITY;
	/** Flag specifying {@value #CONVEX_HULL_ELONGATION} statistics */
	public static final String CONVEX_HULL_ELONGATION= "Convex hull: " + ConvexHullAnalyzer.ELONGATION;
	/** Flag specifying {@value #CONVEX_HULL_ROUNDNESS} statistics */
	public static final String CONVEX_HULL_ROUNDNESS= "Convex hull: " + ConvexHullAnalyzer.ROUNDNESS;
	/** Flag specifying {@value #CONVEX_HULL_CENTROID_ROOT_DISTANCE} statistics */
	public static final String CONVEX_HULL_CENTROID_ROOT_DISTANCE = "Convex hull: Centroid-root distance";

	/**
	 * Flag for analysis of {@value #VALUES}, an optional numeric property that can
	 * be assigned to Path nodes (e.g., voxel intensities), assigned via
	 * {@link PathProfiler}. Note that an {@link IllegalArgumentException} is
	 * triggered if no values have been assigned to the tree being analyzed.
	 * 
	 * @see Path#hasNodeValues()
	 * @see PathProfiler#assignValues()
	 */
	public static final String VALUES = "Node intensity values";

	/** @deprecated  Use {@link #BRANCH_CONTRACTION} or {@link #PATH_CONTRACTION} instead */
	@Deprecated
	public static final String CONTRACTION = "Contraction";

	/** @deprecated  Use {@link #BRANCH_MEAN_RADIUS} or {@link #PATH_MEAN_RADIUS} instead */
	@Deprecated
	public static final String MEAN_RADIUS = PATH_MEAN_RADIUS;

	/** @deprecated  Use {@link #PATH_SPINE_DENSITY} instead */
	@Deprecated
	public static final String AVG_SPINE_DENSITY = "Average spine/varicosity density";

	@Deprecated
	/** @deprecated  Use {@link #BRANCH_FRACTAL_DIMENSION} or {@link #PATH_FRACTAL_DIMENSION} instead */
	public static final String FRACTAL_DIMENSION = "Fractal dimension";

	private static final String[] ALL_FLAGS = {
			GRAPH_DIAMETER_ANGLE_XY, GRAPH_DIAMETER_ANGLE_XZ, GRAPH_DIAMETER_ANGLE_ZY, //
			BRANCH_CONTRACTION, BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH,
			BRANCH_MEAN_RADIUS, BRANCH_SURFACE_AREA, BRANCH_VOLUME, COMPLEXITY_INDEX_ACI, COMPLEXITY_INDEX_DCI,
			CONVEX_HULL_BOUNDARY_SIZE, CONVEX_HULL_BOXIVITY, CONVEX_HULL_CENTROID_ROOT_DISTANCE, CONVEX_HULL_ELONGATION,
			CONVEX_HULL_ROUNDNESS, CONVEX_HULL_SIZE, DEPTH, //
			BRANCH_EXTENSION_ANGLE_XY, BRANCH_EXTENSION_ANGLE_XZ, BRANCH_EXTENSION_ANGLE_ZY, //
			INNER_EXTENSION_ANGLE_XY, INNER_EXTENSION_ANGLE_XZ, INNER_EXTENSION_ANGLE_ZY, PRIMARY_EXTENSION_ANGLE_XY,
			PRIMARY_EXTENSION_ANGLE_XZ, PRIMARY_EXTENSION_ANGLE_ZY, TERMINAL_EXTENSION_ANGLE_XY,
			TERMINAL_EXTENSION_ANGLE_XZ, TERMINAL_EXTENSION_ANGLE_ZY, //
			GRAPH_DIAMETER, HEIGHT, INNER_LENGTH, INTER_NODE_ANGLE, INTER_NODE_DISTANCE,
			INTER_NODE_DISTANCE_SQUARED, LENGTH, N_BRANCH_NODES, N_BRANCH_POINTS, N_BRANCHES, N_FITTED_PATHS,
			N_INNER_BRANCHES, N_NODES, N_PATH_NODES, N_PATHS, N_PRIMARY_BRANCHES, N_SPINES, N_TERMINAL_BRANCHES, N_TIPS,
			NODE_RADIUS, PARTITION_ASYMMETRY, PATH_CHANNEL, PATH_CONTRACTION, PATH_FRACTAL_DIMENSION, PATH_FRAME,
			PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY, PATH_EXT_ANGLE_REL_XY, PATH_EXT_ANGLE_REL_XZ,
			PATH_EXT_ANGLE_REL_ZY, PATH_LENGTH, PATH_MEAN_RADIUS,
			PATH_SPINE_DENSITY, PATH_N_SPINES, PATH_ORDER, PATH_SURFACE_AREA, PATH_VOLUME, PRIMARY_LENGTH,
			REMOTE_BIF_ANGLES, SHOLL_DECAY, SHOLL_KURTOSIS, SHOLL_MAX_FITTED, SHOLL_MAX_FITTED_RADIUS, SHOLL_MAX_VALUE,
			SHOLL_MEAN_VALUE, SHOLL_N_MAX, SHOLL_N_SECONDARY_MAX, SHOLL_POLY_FIT_DEGREE, SHOLL_RAMIFICATION_INDEX,
			SHOLL_SKEWENESS, SHOLL_SUM_VALUE, STRAHLER_NUMBER, STRAHLER_RATIO, SURFACE_AREA, TERMINAL_LENGTH, VALUES,
			VOLUME, WIDTH, X_COORDINATES, Y_COORDINATES, Z_COORDINATES };

	protected LastDstats lastDstats;
	private ConvexHullAnalyzer convexAnalyzer;
	private static boolean exactMetricMatch;

	/**
	 * Instantiates a new instance from a collection of Paths
	 *
	 * @param tree the collection of paths to be analyzed
	 */
	public TreeStatistics(final Tree tree) {
		super(tree);
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
			measure(getAllMetrics(), true);
			return getAllMetrics();
		case "legacy":
			// Historical metrics up to SNTv4.0.10
			metrics = new String[] { BRANCH_LENGTH, CONTRACTION, REMOTE_BIF_ANGLES, PARTITION_ASYMMETRY,
					FRACTAL_DIMENSION, INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, MEAN_RADIUS, AVG_SPINE_DENSITY,
					N_BRANCH_POINTS, N_NODES, N_SPINES, NODE_RADIUS, PATH_CHANNEL, PATH_FRAME, PATH_LENGTH, PATH_ORDER,
					PRIMARY_LENGTH, INNER_LENGTH, TERMINAL_LENGTH, VALUES, X_COORDINATES, Y_COORDINATES,
					Z_COORDINATES };
			break;
		case "deprecated":
			metrics = new String[] { AVG_SPINE_DENSITY, CONTRACTION, FRACTAL_DIMENSION, MEAN_RADIUS,
					AVG_SPINE_DENSITY };
			break;
		case "safe":
			metrics = new String[] { INTER_NODE_ANGLE, //
					INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, N_BRANCH_POINTS, N_FITTED_PATHS,
					N_NODES, N_PATH_NODES, N_PATHS, N_SPINES, N_TIPS, NODE_RADIUS, PATH_CHANNEL, PATH_CONTRACTION,
					PATH_FRAME, PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY,
					PATH_EXT_ANGLE_REL_XY, PATH_EXT_ANGLE_REL_XZ, PATH_EXT_ANGLE_REL_ZY,
					PATH_LENGTH, PATH_MEAN_RADIUS, PATH_SPINE_DENSITY, PATH_N_SPINES, PATH_ORDER,
					PATH_SURFACE_AREA, PATH_VOLUME, VALUES, X_COORDINATES, Y_COORDINATES, Z_COORDINATES };
			break;
		case "common":
			metrics = new String[] { BRANCH_CONTRACTION, BRANCH_FRACTAL_DIMENSION, BRANCH_LENGTH, BRANCH_MEAN_RADIUS,
					BRANCH_SURFACE_AREA, BRANCH_VOLUME, COMPLEXITY_INDEX_ACI, COMPLEXITY_INDEX_DCI, CONVEX_HULL_SIZE,
					DEPTH, INNER_LENGTH, INTER_NODE_ANGLE, INTER_NODE_DISTANCE, INTER_NODE_DISTANCE_SQUARED, LENGTH,
					N_BRANCH_POINTS, N_BRANCHES, N_INNER_BRANCHES, N_NODES, N_PRIMARY_BRANCHES, N_SPINES,
					N_TERMINAL_BRANCHES, N_TIPS, NODE_RADIUS, PARTITION_ASYMMETRY, PRIMARY_LENGTH, REMOTE_BIF_ANGLES,
					SHOLL_DECAY, SHOLL_MAX_VALUE, SHOLL_MAX_FITTED, SHOLL_MAX_FITTED_RADIUS, SHOLL_MEAN_VALUE,
					SURFACE_AREA, STRAHLER_NUMBER, TERMINAL_LENGTH, VALUES, VOLUME, X_COORDINATES, Y_COORDINATES,
					Z_COORDINATES };
			break;
		case "quick":
			/* NB: This list can only include metrics supported by #getMetricWithoutChecks() */
			metrics = new String[] { //
					LENGTH, MultiTreeStatistics.AVG_BRANCH_LENGTH, N_BRANCH_POINTS, N_TIPS, N_BRANCHES, //
					N_PRIMARY_BRANCHES, N_TERMINAL_BRANCHES, //
					PATH_MEAN_RADIUS, //
					AVG_SPINE_DENSITY, //
					STRAHLER_NUMBER, MultiTreeStatistics.HIGHEST_PATH_ORDER,
					/* Disabled metrics (likely too specific or uncommon for most users) */
					// MultiTreeStatistics.ASSIGNED_VALUE, MultiTreeStatistics.AVG_CONTRACTION,
					// MultiTreeStatistics.AVG_FRACTAL_DIMENSION,
					// MultiTreeStatistics.AVG_FRAGMENTATION,
					// MultiTreeStatistics.AVG_REMOTE_ANGLE,
					// MultiTreeStatistics.AVG_PARTITION_ASYMMETRY,
					// PRIMARY_LENGTH, INNER_LENGTH, TERMINAL_LENGTH,
					// N_INNER_BRANCHES,
					// N_FITTED_PATHS, N_PATHS, N_NODES,
					// WIDTH, HEIGHT, DEPTH,
					// SHOLL_DECAY, SHOLL_MAX_VALUE,
			};
			break;
		default:
			throw new IllegalArgumentException("Unrecognized type");
		}
		return Arrays.stream(metrics).collect(Collectors.toList());
	}

	/**
	 * Gets the list of most commonly used metrics.
	 *
	 * @return the list of commonly used metrics
	 * @see #getMetric(String)
	 */
	@Deprecated
	public static List<String> getMetrics() {
		return getMetrics("common");
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
	 * Retrieves the amount of cable length present on each brain compartment
	 * innervated by the analyzed neuron.
	 *
	 * @param level the ontological depth of the compartments to be considered
	 * @return the map containing the brain compartments as keys, and cable lengths
	 *         as values.
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
	 *         as values.
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
	 *         as values.
	 * @see AllenCompartment#getOntologyDepth()
	 */
	public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere,
			final boolean norm) {
		return getAnnotatedLength(tree.getGraph(), level, BrainAnnotation.getHemisphereFlag(hemisphere), norm);
	}

	protected static Map<BrainAnnotation, Double> getAnnotatedLength(final DirectedWeightedGraph graph, final int level,
			final char lr, final boolean norm) {
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
			lengthMap.values().forEach( l -> l /= sumLength);
		}
		return lengthMap;
	}

	public Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final int level) {
		return getAnnotatedLengthsByHemisphere(tree.getGraph(), level, false);
	}

	protected static Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final DirectedWeightedGraph graph,
			final int level, final boolean norm) {
		final char ipsiFlag = graph.getRoot().getHemisphere();
		if (ipsiFlag == BrainAnnotation.ANY_HEMISPHERE)
			throw new IllegalArgumentException("Tree's root has its hemisphere flag unset");
		final char contraFlag = (ipsiFlag == BrainAnnotation.LEFT_HEMISPHERE) ? BrainAnnotation.RIGHT_HEMISPHERE
				: BrainAnnotation.LEFT_HEMISPHERE;
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
		contraMap.forEach((k, contraLength) -> {
			finalMap.put(k, new double[] { 0d, contraLength });
		});
		return finalMap;
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
		if ("ratio".equalsIgnoreCase(hemisphere.trim()))
			return getAnnotatedLengthsByHemisphereHistogram(depth);
		final Map<BrainAnnotation, Double> map = getAnnotatedLength(depth, hemisphere);

		String label;
		final char hemiFlag = BrainAnnotation.getHemisphereFlag(hemisphere);
		switch (hemiFlag) {
		case BrainAnnotation.LEFT_HEMISPHERE:
			label = "Left hemi.";
			break;
		case BrainAnnotation.RIGHT_HEMISPHERE:
			label = "Right hemi.";
			break;
		default:
			label = "";
		}
		return getAnnotatedLengthHistogram(map, depth, label);
	}

	protected SNTChart getAnnotatedLengthsByHemisphereHistogram(int depth) {
		final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		Map<BrainAnnotation, double[]> seriesMap = getAnnotatedLengthsByHemisphere(depth);
		Map<BrainAnnotation, double[]> undefinedLengthMap = new HashMap<>();
		seriesMap.entrySet().stream().sorted((e1, e2) -> -Double.compare(e1.getValue()[0], e2.getValue()[0]))
				.forEach(entry -> {
					if (entry.getKey() == null || entry.getKey().getOntologyDepth() == 0) {
						// a null brain annotation or the root brain itself
						undefinedLengthMap.put(entry.getKey(), entry.getValue());
					} else {
						dataset.addValue(entry.getValue()[0], "Ipsilateral", entry.getKey().acronym());
						dataset.addValue(entry.getValue()[1], "Contralateral", entry.getKey().acronym());
					}
				});

		if (!undefinedLengthMap.isEmpty()) {
			undefinedLengthMap.forEach( (k, v) -> {
				dataset.addValue(v[0], "Ipsilateral", BrainAnnotation.simplifiedString(k));
				dataset.addValue(v[1], "Contralateral", BrainAnnotation.simplifiedString(k));
			});
		}
		final String axisTitle = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth \u2264" + depth;
		final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
				"Brain areas (N=" + (seriesMap.size() - undefinedLengthMap.size()) + ", " + axisTitle + ")", // domain axis title
				String.format("Cable length (%s)", getUnit()), // range axis title
				"", // axis unit (already applied)
				dataset, 2);
		final String tLabel = (tree.getLabel() == null) ? "" : tree.getLabel();
		return new SNTChart(tLabel + " Annotated Length", chart, new Dimension(400, 600));
	}

	protected SNTChart getAnnotatedLengthHistogram(final Map<BrainAnnotation, Double> map, final int depth,
			final String secondaryLabel) {
		final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		Map<BrainAnnotation, Double> undefinedLengthMap = new HashMap<>();
		final String seriesLabel = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth \u2264" + depth;
		map.entrySet().stream().sorted((e1, e2) -> -e1.getValue().compareTo(e2.getValue())).forEach(entry -> {
			if (entry.getKey() == null || entry.getKey().getOntologyDepth() == 0) {
				// a null brain annotation or the root brain itself
				undefinedLengthMap.put(entry.getKey(), entry.getValue());
			} else
				dataset.addValue(entry.getValue(), seriesLabel, entry.getKey().acronym());
		});
		if (!undefinedLengthMap.isEmpty()) {
			undefinedLengthMap.forEach( (k, v) -> {
				dataset.addValue(v, seriesLabel, BrainAnnotation.simplifiedString(k));
			});
		}
		final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
				"Brain areas (N=" + (map.size()-undefinedLengthMap.size()) + ", " + seriesLabel + ")", // domain axis title
				String.format("Cable length (%s)", getUnit()), // range axis title
				"", // unit: already specified
				dataset);
		final String tLabel = (tree.getLabel() == null) ? "" : tree.getLabel();
		final SNTChart frame = new SNTChart(tLabel + " Annotated Length", chart, new Dimension(400, 600));
		if (secondaryLabel != null)
			frame.annotate(secondaryLabel);
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
		final HistogramDatasetPlus datasetPlus = new HDPlus(normMeasurement, true);
		return getHistogram(normMeasurement, datasetPlus);
	}

	public SNTChart getPolarHistogram(final String metric) {
		final String normMeasurement = getNormalizedMeasurement(metric);
		final HistogramDatasetPlus datasetPlus = new HDPlus(normMeasurement, true);
		final JFreeChart chart = AnalysisUtils.createPolarHistogram(normMeasurement, getUnit(), lastDstats.dStats, datasetPlus);
		return new SNTChart("Polar Hist. " + tree.getLabel(), chart);
	}

	/**
	 * Assembles a Flow plot (aka Sankey diagram) for the specified feature using
	 * "mean" as integration statistic, and no cutoff value.
	 * 
	 * @see #getFlowPlot(String, Collection, String, double, boolean)
	 */
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final boolean normalize) {
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
	 * 
	 * @return the SNTChart holding the flow plot
	 */
	public SNTChart getFlowPlot(final String feature, final Collection<BrainAnnotation> annotations,
			final String statistic, final double cutoff, final boolean normalize) {
		final GroupedTreeStatistics gts = new GroupedTreeStatistics();
		gts.addGroup(Collections.singleton(getParsedTree()), (null == tree.getLabel()) ? "" : tree.getLabel());
		final SNTChart chart = gts.getFlowPlot(feature, annotations, statistic, cutoff, normalize);
		chart.setTitle("Flow Plot [Single Cell]");
		return chart;
	}

	public static TreeStatistics fromCollection(final Collection<Tree> trees, final String metric) {
		final Iterator<Tree> iterator = trees.iterator();
		final TreeStatistics holder = new TreeStatistics(iterator.next());
		if (trees.size() == 1)
			return holder;
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
			if (tree.getLabel() != null)
				sb.append(tree.getLabel()).append(" ");
		}
		return (sb.length() == 0) ? "Grouped Cells" : sb.toString().trim();
	}

	protected SNTChart getHistogram(final String normMeasurement, final HistogramDatasetPlus datasetPlus) {
		final JFreeChart chart = AnalysisUtils.createHistogram(normMeasurement, getUnit(), lastDstats.dStats, datasetPlus);
		return new SNTChart("Hist. " + tree.getLabel(), chart);
	}

	protected static String tryReallyHardToGuessMetric(final String guess) {
		final String normGuess = guess.toLowerCase();
		if (normGuess.contains("contrac")) {
			return CONTRACTION;
		}
		if (normGuess.contains("remote") && normGuess.contains("angle")) {
			return REMOTE_BIF_ANGLES;
		}
		if (normGuess.contains("partition") && normGuess.contains("asymmetry")) {
			return PARTITION_ASYMMETRY;
		}
		if (normGuess.contains("fractal")) {
			return FRACTAL_DIMENSION;
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
					return (normGuess.contains("rel")) ? PATH_EXT_ANGLE_REL_ZY :PATH_EXT_ANGLE_ZY;
				else
					return (normGuess.contains("rel")) ? PATH_EXT_ANGLE_REL_XY :PATH_EXT_ANGLE_XY;
			} else if (normGuess.contains("term")) {
				if (normGuess.contains("xz"))
					return TERMINAL_EXTENSION_ANGLE_XZ;
				else if (normGuess.contains("zy"))
					return TERMINAL_EXTENSION_ANGLE_ZY;
				else
					return TERMINAL_EXTENSION_ANGLE_XY;
			} else if (normGuess.contains("prim")) {
				if (normGuess.contains("xz"))
					return PRIMARY_EXTENSION_ANGLE_XZ;
				else if (normGuess.contains("zy"))
					return PRIMARY_EXTENSION_ANGLE_ZY;
				else
					return PRIMARY_EXTENSION_ANGLE_XY;
			} else if (normGuess.contains("inner")) {
				if (normGuess.contains("xz"))
					return INNER_EXTENSION_ANGLE_XZ;
				else if (normGuess.contains("zy"))
					return INNER_EXTENSION_ANGLE_ZY;
				else
					return INNER_EXTENSION_ANGLE_XY;
			} else if (normGuess.contains("xz"))
				return BRANCH_EXTENSION_ANGLE_XZ;
			else if (normGuess.contains("zy"))
				return BRANCH_EXTENSION_ANGLE_ZY;
			else
				return BRANCH_EXTENSION_ANGLE_XY;
		}
		if (normGuess.contains("path") && normGuess.contains("order")) {
			return PATH_ORDER;
		}
		if (normGuess.contains("bp") || normGuess.contains("branch points")
				|| normGuess.contains("junctions")) {
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
			if (normGuess.contains("mean") || normGuess.contains("avg")
					|| normGuess.contains("average")) {
				return MEAN_RADIUS;
			} else {
				return NODE_RADIUS;
			}
		}
		if (normGuess.contains("spines") || normGuess.contains("varicosities")) {
			if (normGuess.contains("mean") || normGuess.contains("avg") || normGuess.contains("average")
					|| normGuess.contains("dens")) {
				return AVG_SPINE_DENSITY;
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
		if (isExactMetricMatch())
			return measurement;
		for (final String flag: ALL_FLAGS) {
			if (flag.equalsIgnoreCase(measurement))
				return flag;
		}
		for (final String flag : MultiTreeStatistics.getMetrics()) {
			if (flag.equalsIgnoreCase(measurement))
				return flag;
		}
		for (final String flag : getMetrics("deprecated")) {
			if (flag.equalsIgnoreCase(measurement))
				return flag;
		}
		final String normMeasurement = tryReallyHardToGuessMetric(measurement);
		if (!measurement.equals(normMeasurement)) {
			SNTUtils.log("\"" + normMeasurement + "\" assumed");
			if ("unknown".equals(normMeasurement)) {
				throw new UnknownMetricException("Unrecognizable measurement! "
						+ "Maybe you meant one of the following?: " + Arrays.toString(ALL_FLAGS));
			}
		}
		return normMeasurement;
	}

	protected void assembleStats(final StatisticsInstance stat, final String measurement) {
		final String m = getNormalizedMeasurement(measurement);
		switch (m) {
		case BRANCH_CONTRACTION:
		case CONTRACTION:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getContraction());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_LENGTH:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getLength());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_MEAN_RADIUS:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getMeanRadius());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_SURFACE_AREA:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getApproximatedSurface());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_VOLUME:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getApproximatedVolume());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
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
						if (graph.outDegreeOf(vx) > 1)
							sumBranchTipOrders++;
					}
				}
				stat.addValue((sumBranchTipOrders + graphTips.size()) * getCableLength() / getPrimaryBranches().size());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case CONVEX_HULL_BOUNDARY_SIZE:
		case CONVEX_HULL_BOXIVITY:
		case CONVEX_HULL_ELONGATION:
		case CONVEX_HULL_ROUNDNESS:
		case CONVEX_HULL_SIZE:
			stat.addValue(getConvexHullMetric(m));
			break;
		case CONVEX_HULL_CENTROID_ROOT_DISTANCE:
			final PointInImage root = tree.getRoot();
			if (root == null)
				stat.addValue(Double.NaN);
			else
				stat.addValue(getConvexAnalyzer().getCentroid().distanceTo(root));
			break;
		case DEPTH:
			stat.addValue(getDepth());
			break;
		case BRANCH_FRACTAL_DIMENSION:
		case FRACTAL_DIMENSION:
			try {
				getFractalDimension().forEach(stat::addValue);
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case PATH_FRACTAL_DIMENSION:
			tree.list().forEach(p -> stat.addValue(p.getFractalDimension()));
			break;
		case GRAPH_DIAMETER:
			try {
				stat.addValue(tree.getGraph().getLongestPath(true).getLength());
			} catch (final IllegalArgumentException ignored) {
				stat.addValue(Double.NaN);
			}
			break;
		case GRAPH_DIAMETER_ANGLE_XY:
			try {
				stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleXY(false));
			} catch (final IllegalArgumentException ignored) {
				stat.addValue(Double.NaN);
			}
			break;
		case GRAPH_DIAMETER_ANGLE_XZ:
			try {
				stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleXZ(false));
			} catch (final IllegalArgumentException ignored) {
				stat.addValue(Double.NaN);
			}
			break;
		case GRAPH_DIAMETER_ANGLE_ZY:
			try {
				stat.addValue(tree.getGraph().getLongestPath(true).getExtensionAngleZY(false));
			} catch (final IllegalArgumentException ignored) {
				stat.addValue(Double.NaN);
			}
			break;
		case HEIGHT:
			stat.addValue(getHeight());
			break;
		case INNER_LENGTH:
			try {
				getInnerBranches().forEach( b -> stat.addValue(b.getLength()));
			} catch (final IllegalArgumentException ignored ) {
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_EXTENSION_ANGLE_XY:
			try {
				getBranches().forEach( b -> stat.addValue(b.getExtensionAngleXY(false)));
			} catch (final IllegalArgumentException ignored ) {
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_EXTENSION_ANGLE_XZ:
			try {
				getBranches().forEach( b -> stat.addValue(b.getExtensionAngleXZ(false)));
			} catch (final IllegalArgumentException ignored ) {
				stat.addValue(Double.NaN);
			}
			break;
		case BRANCH_EXTENSION_ANGLE_ZY:
			try {
				getBranches().forEach( b -> stat.addValue(b.getExtensionAngleZY(false)));
			} catch (final IllegalArgumentException ignored ) {
				stat.addValue(Double.NaN);
			}
			break;
		case INNER_EXTENSION_ANGLE_XY:
				try {
					getInnerBranches().forEach( b -> stat.addValue(b.getExtensionAngleXY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case INNER_EXTENSION_ANGLE_XZ:
				try {
					getInnerBranches().forEach( b -> stat.addValue(b.getExtensionAngleXZ(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case INNER_EXTENSION_ANGLE_ZY:
				try {
					getInnerBranches().forEach( b -> stat.addValue(b.getExtensionAngleZY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case PRIMARY_EXTENSION_ANGLE_XY:
				try {
					getPrimaryBranches().forEach( b -> stat.addValue(b.getExtensionAngleXY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case PRIMARY_EXTENSION_ANGLE_XZ:
				try {
					getPrimaryBranches().forEach( b -> stat.addValue(b.getExtensionAngleXZ(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case PRIMARY_EXTENSION_ANGLE_ZY:
				try {
					getPrimaryBranches().forEach( b -> stat.addValue(b.getExtensionAngleZY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case TERMINAL_EXTENSION_ANGLE_XY:
				try {
					getTerminalBranches().forEach( b -> stat.addValue(b.getExtensionAngleXY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case TERMINAL_EXTENSION_ANGLE_XZ:
				try {
					getTerminalBranches().forEach( b -> stat.addValue(b.getExtensionAngleXZ(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case TERMINAL_EXTENSION_ANGLE_ZY:
				try {
					getTerminalBranches().forEach( b -> stat.addValue(b.getExtensionAngleZY(false)));
				} catch (final IllegalArgumentException ignored ) {
					stat.addValue(Double.NaN);
				}
				break;
		case INTER_NODE_ANGLE:
			for (final Path p : tree.list()) {
				if (p.size() < 3)
					continue;
				for (int i = 2; i < p.size(); i++) {
					stat.addValue(p.getAngle(i));
				}
			}
			break;
		case INTER_NODE_DISTANCE:
			for (final Path p : tree.list()) {
				if (p.size() < 2)
					continue;
				for (int i = 1; i < p.size(); i += 1) {
					stat.addValue(p.getNode(i).distanceTo(p.getNode(i - 1)));
				}
			}
			break;
		case INTER_NODE_DISTANCE_SQUARED:
			for (final Path p : tree.list()) {
				if (p.size() < 2)
					continue;
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
				stat.addValue(Double.NaN);
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
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
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
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case PATH_FRAME:
			for (final Path p : tree.list()) {
				stat.addValue(p.getFrame());
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
		case PATH_LENGTH:
			for (final Path p : tree.list())
				stat.addValue(p.getLength());
			break;
		case PATH_MEAN_RADIUS:
			for (final Path p : tree.list()) {
				stat.addValue(p.getMeanRadius());
			}
			break;
		case PATH_SPINE_DENSITY:
		case AVG_SPINE_DENSITY:
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
		case PRIMARY_LENGTH:
			for (final Path p : getPrimaryBranches())
				stat.addValue(p.getLength());
			break;
		case REMOTE_BIF_ANGLES:
			try {
				for (final double angle : getRemoteBifAngles())
					stat.addValue(angle);
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
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
		case SHOLL_SKEWENESS:
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
		case TERMINAL_LENGTH:
			for (final Path p : getTerminalBranches())
				stat.addValue(p.getLength());
			break;
		case VALUES:
			for (final Path p : tree.list()) {
				if (!p.hasNodeValues())
					continue;
				for (int i = 0; i < p.size(); i++) {
					stat.addValue(p.getNodeValue(i));
				}
			}
			if (stat.getN() == 0)
				throw new IllegalArgumentException("Tree has no values assigned");
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

	class LastDstats {

		private final String measurement;
		final DescriptiveStatistics dStats;
		private final int size;

		LastDstats(final String measurement, final DescriptiveStatistics dStats) {
			this.measurement = measurement;
			this.dStats = dStats;
			size = tree.size();
		}
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

		void addValue(final double value) {
			if (sStatistics != null)
				sStatistics.addValue(value);
			else
				dStatistics.addValue(value);
		}

		long getN() {
			return (sStatistics != null) ? sStatistics.getN() : dStatistics.getN();
		}

	}

	class HDPlus extends HistogramDatasetPlus {
		final String measurement;

		HDPlus(final String measurement) {
			this(measurement, true);
		}

		HDPlus(final String measurement, final boolean retrieveValues) {
			super();
			this.measurement = measurement;
			if (retrieveValues) {
				getDescriptiveStats(measurement);
				for (final double v : lastDstats.dStats.getValues()) {
					values.add(v);
				}
			}
		}
	}

	public NodeStatistics<SWCPoint> getNodeStatistics() {
		return getNodeStatistics("all");
	}

	public NodeStatistics<SWCPoint> getNodeStatistics(final String type) {
		return tree.getGraph().getNodeStatistics(type);
	}

	public double getConvexHullMetric(final String metric) {
		final String fMetric = metric.substring(metric.indexOf("Convex Hull: ") + 13).trim();
		return getConvexAnalyzer().get(fMetric);
	}

	/**
	 * Gets the {@link ConvexHullAnalyzer} instance associated with this analyzer.
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

	@Override
	public void resetRestrictions() {
		convexAnalyzer = null;
		super.resetRestrictions();
	
	}

	public static boolean isExactMetricMatch() {
		return exactMetricMatch;
	}

	public static void setExactMetricMatch(final boolean exactMetricMatch) {
		TreeStatistics.exactMetricMatch = exactMetricMatch;
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
