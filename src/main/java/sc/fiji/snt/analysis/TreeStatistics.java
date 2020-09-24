/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.text.WordUtils;
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
import sc.fiji.snt.util.SWCPoint;

/**
 * Computes summary and descriptive statistics from univariate properties of
 * Paths and Nodes in a {@link Tree}. For analysis of groups of Trees use
 * {@link MultiTreeStatistics}.
 *
 * @author Tiago Ferreira
 */
public class TreeStatistics extends TreeAnalyzer {

	/** Flag for {@value #PATH_LENGTH} analysis. */
	public static final String PATH_LENGTH = "Path length";

	/** Flag for {@value #BRANCH_LENGTH} analysis. */
	public static final String BRANCH_LENGTH = "Branch length";

	/** Flag for {@value #TERMINAL_LENGTH} analysis. */
	public static final String TERMINAL_LENGTH = "Length of terminal branches";

	/** Flag for {@value #PRIMARY_LENGTH} analysis. */
	public static final String PRIMARY_LENGTH = "Length of primary branches";

	/** Flag for {@value #INNER_LENGTH} analysis. */
	public static final String INNER_LENGTH = "Length of inner branches";

	/** Flag for {@value #PATH_ORDER} statistics. */
	public static final String PATH_ORDER = "Path order";

	/** Flag for {@value #PATH_CHANNEL} statistics. */
	public static final String PATH_CHANNEL = "Path channel";

	/** Flag for {@value #PATH_FRAME} statistics. */
	public static final String PATH_FRAME = "Path frame";

	/** Flag for {@value #INTER_NODE_DISTANCE} statistics. */
	public static final String INTER_NODE_DISTANCE = "Inter-node distance";

	/** Flag for {@value #INTER_NODE_DISTANCE_SQUARED} statistics. */
	public static final String INTER_NODE_DISTANCE_SQUARED =
		"Inter-node distance (squared)";

	/** Flag for {@value #N_BRANCH_POINTS} statistics. */
	public static final String N_BRANCH_POINTS = "No. of branch points";

	/** Flag for {@value #N_BRANCH_POINTS} statistics. */
	public static final String N_NODES = "No. of nodes";

	/** Flag for {@value #NODE_RADIUS} statistics. */
	public static final String NODE_RADIUS = "Node radius";

	/** Flag for {@value #MEAN_RADIUS} statistics. */
	public static final String MEAN_RADIUS = "Path mean radius";

	/** Flag for {@value #X_COORDINATES} statistics. */
	public static final String X_COORDINATES = "X coordinates";

	/** Flag for {@value #Y_COORDINATES} statistics. */
	public static final String Y_COORDINATES = "Y coordinates";

	/** Flag for {@value #Z_COORDINATES} statistics. */
	public static final String Z_COORDINATES = "Z coordinates";

	/** Flag for {@value #CONTRACTION} statistics. */
	public static final String CONTRACTION = "Contraction";

	/** Flag for {@value #REMOTE_BIF_ANGLES} statistics. */
	public static final String REMOTE_BIF_ANGLES = "Remote bif. angles";
	
	/** Flag for {@value #PARTITION_ASYMMETRY} statistics. */
	public static final String PARTITION_ASYMMETRY = "Partition asymmetry";
	
	/** Flag for {@value #FRACTAL_DIMENSION} statistics. */
	public static final String FRACTAL_DIMENSION = "Fractal dimension";

	/**
	 * Flag for analysis of {@value #VALUES}, an optional numeric property that
	 * can be assigned to Path nodes (e.g., voxel intensities, assigned via
	 * {@link PathProfiler}. Note that an {@link IllegalArgumentException} is
	 * triggered if no values have been assigned to the tree being analyzed.
	 * 
	 * @see Path#hasNodeValues()
	 * @see PathProfiler#assignValues()
	 */
	public static final String VALUES = "Node intensity values";

	private static final String[] ALL_FLAGS = { //
			BRANCH_LENGTH, //
			CONTRACTION, //
			REMOTE_BIF_ANGLES, //
			PARTITION_ASYMMETRY, //
			FRACTAL_DIMENSION, //
			INTER_NODE_DISTANCE, //
			INTER_NODE_DISTANCE_SQUARED, //
			MEAN_RADIUS, //
			N_BRANCH_POINTS, //
			N_NODES, //
			NODE_RADIUS, //
			PATH_CHANNEL,//
			PATH_FRAME,//
			PATH_LENGTH, //
			PATH_ORDER, //
			PRIMARY_LENGTH, //
			INNER_LENGTH, //
			TERMINAL_LENGTH, //
			VALUES, //
			X_COORDINATES, //
			Y_COORDINATES, //
			Z_COORDINATES, //
	};

	protected LastDstats lastDstats;

	/**
	 * Instantiates a new instance from a collection of Paths
	 *
	 * @param tree the collection of paths to be analyzed
	 */
	public TreeStatistics(final Tree tree) {
		super(tree);
	}

	/**
	 * Gets the list of all {@link TreeAnalyzer} supported metrics.
	 *
	 * @return the list of all TreeAnalyzer's metrics
	 * 
	 * @see TreeAnalyzer#getAllMetrics()
	 */
	public static List<String> getAnalyzerMetrics() {
		return TreeAnalyzer.getAllMetrics();
	}

	/**
	 * Gets the list of <i>all</i> supported metrics.
	 *
	 * @return the list of available metrics
	 */
	public static List<String> getAllMetrics() {
		return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
	}

	/**
	 * Gets the list of most commonly used metrics.
	 *
	 * @return the list of commonly used metrics
	 */
	public static List<String> getMetrics() {
		return getAllMetrics().stream().filter(metric -> {
			return !metric.toLowerCase().contains("path");
		}).collect(Collectors.toList());
	}

	/**
	 * Computes the {@link SummaryStatistics} for the specified measurement.
	 *
	 * @param metric the measurement ({@link #N_NODES}, {@link #NODE_RADIUS},
	 *          etc.)
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
	 * @param metric the measurement ({@link #N_NODES}, {@link #NODE_RADIUS},
	 *          etc.)
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
		return getAnnotatedLength(level, BrainAnnotation.ANY_HEMISPHERE);
	}

	/**
	 * Retrieves the amount of cable length present on each brain compartment
	 * innervated by the analyzed neuron.
	 *
	 * @param level      the ontological depth of the compartments to be considered
	 * @param hemisphere typically 'left' or 'right'. The hemisphere flag (
	 *                   {@link BrainAnnotation#LEFT_HEMISPHERE} or
	 *                   {@link BrainAnnotation#RIGHT_HEMISPHERE}) is extracted from
	 *                   the first character of the string (case insensitive).
	 *                   Ignored if not a recognized option
	 * @return the map containing the brain compartments as keys, and cable lengths
	 *         as values.
	 * @see AllenCompartment#getOntologyDepth()
	 */
	public Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final String hemisphere) {
		return getAnnotatedLength(level, BrainAnnotation.getHemisphereFlag(hemisphere));
	}

	private Map<BrainAnnotation, Double> getAnnotatedLength(final int level, final char lr) {
		final DirectedWeightedGraph graph = tree.getGraph();
		final NodeStatistics<SWCPoint> nodeStats = new NodeStatistics<SWCPoint>(graph.vertexSet(lr));
		final Map<BrainAnnotation, Set<SWCPoint>> annotatedNodesMap = nodeStats.getAnnotatedNodes(level);
		final HashMap<BrainAnnotation, Double> lengthMap = new HashMap<>();
		for (final Map.Entry<BrainAnnotation, Set<SWCPoint>> entry : annotatedNodesMap.entrySet()) {
		    final BrainAnnotation annotation = entry.getKey();
		    final Set<SWCPoint> nodeSubset = entry.getValue();
		    final DirectedWeightedSubgraph subgraph = graph.getSubgraph(nodeSubset);
		    final double subgraphWeight = subgraph.sumEdgeWeights(true);
		    lengthMap.put(annotation, subgraphWeight);
		}
		return lengthMap;
	}

	public Map<BrainAnnotation, double[]> getAnnotatedLengthsByHemisphere(final int level) {
		final char ipsiFlag = tree.getGraph().getRoot().getHemisphere();
		if (ipsiFlag == BrainAnnotation.ANY_HEMISPHERE)
			throw new IllegalArgumentException("Tree's root has its hemisphere flag unset");
		final char contraFlag = (ipsiFlag == BrainAnnotation.LEFT_HEMISPHERE) ? BrainAnnotation.RIGHT_HEMISPHERE
				: BrainAnnotation.LEFT_HEMISPHERE;
		final Map<BrainAnnotation, Double> ipsiMap = getAnnotatedLength(level, ipsiFlag);
		final Map<BrainAnnotation, Double> contraMap = getAnnotatedLength(level, contraFlag);
		final Map<BrainAnnotation, double[]> finalMap = new HashMap<>();
		ipsiMap.forEach( (k, ipsiLength) -> {
			double[] values = new double[2];
			final Double contraLength = contraMap.get(k);
			values[0] = ipsiLength;
			values[1] = (contraLength == null) ? 0d : contraLength;
			finalMap.put(k, values);
		});
		contraMap.keySet().removeIf( k -> ipsiMap.get(k) != null);
		contraMap.forEach( (k, contraLength) -> {
			finalMap.put(k, new double[] {0d, contraLength});
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
	 * @param hemisphere 'left', 'right' or 'ratio' (case insensitive). Ignored if
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

	private SNTChart getAnnotatedLengthsByHemisphereHistogram(int depth) {
		final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		Map<BrainAnnotation, double[]> seriesMap = getAnnotatedLengthsByHemisphere(depth);
		seriesMap.entrySet().stream().sorted((e1, e2) -> -Double.compare(e1.getValue()[0], e2.getValue()[0]))
		.forEach(entry -> {
			if (entry.getKey() != null) {
					dataset.addValue(entry.getValue()[0], "Ipsilateral", entry.getKey().acronym());
					dataset.addValue(entry.getValue()[1], "Contralateral", entry.getKey().acronym());
			}
		});
		int nAreas = seriesMap.size();
		if (seriesMap.get(null) != null) {
			dataset.addValue(seriesMap.get(null)[0], "Ipsilateral", "Other" );
			dataset.addValue(seriesMap.get(null)[1], "Contralateral", "Other" );
			nAreas--;
		}
		final String axisTitle = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth \u2264" + depth;
		final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
				"Brain areas (N=" + nAreas + ", "+ axisTitle +")", // domain axis title
				"Cable length", // range axis title
				dataset, 2);
		final String tLabel = (tree.getLabel() == null) ? "" : tree.getLabel();
		final SNTChart frame = new SNTChart(tLabel + " Annotated Length", chart, new Dimension(400, 600));
		return frame;
	}

	private SNTChart getAnnotatedLengthHistogram(final Map<BrainAnnotation, Double> map, final int depth, final String secondaryLabel) {
		final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		final String seriesLabel = (depth == Integer.MAX_VALUE) ? "no filtering" : "depth \u2264" + depth;
		map.entrySet().stream().sorted((e1, e2) -> -e1.getValue().compareTo(e2.getValue())).forEach(entry -> {
			if (entry.getKey() != null)
					dataset.addValue(entry.getValue(), seriesLabel, entry.getKey().acronym());
		});
		int nAreas = map.size();
		if (map.get(null) != null) {
			dataset.addValue(map.get(null), seriesLabel,"Other" );
			nAreas--;
		}
		final JFreeChart chart = AnalysisUtils.createCategoryPlot( //
				"Brain areas (N=" + nAreas + ", "+ seriesLabel +")", // domain axis title
				"Cable length", // range axis title
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
		final HistogramDatasetPlus datasetPlus = new HDPlus(normMeasurement, true);
		return getHistogram(normMeasurement, datasetPlus);
	}

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
		for (final Tree tree: trees) {
			if (tree.getLabel() != null) sb.append(tree.getLabel()).append(" ");
		}
		return (sb.length() == 0) ? "Grouped Cells" : sb.toString().trim();
	}

	protected SNTChart getHistogram(final String normMeasurement, final HistogramDatasetPlus datasetPlus) {
		final JFreeChart chart = AnalysisUtils.createHistogram(normMeasurement, lastDstats.dStats, datasetPlus);
		final SNTChart frame = new SNTChart("Hist. " + tree.getLabel(), chart);
		return frame;
	}

	protected static String tryReallyHardToGuessMetric(final String guess) {
		final String normGuess = guess.toLowerCase();
		if (normGuess.indexOf("contrac") != -1) {
			return CONTRACTION;
		}
		if (normGuess.indexOf("remote") != -1 && normGuess.indexOf("angle") != -1) {
			return REMOTE_BIF_ANGLES;
		}
		if (normGuess.indexOf("partition") != -1 && normGuess.indexOf("asymmetry") != -1) {
			return PARTITION_ASYMMETRY;
		}
		if (normGuess.indexOf("fractal") != -1) {
			return FRACTAL_DIMENSION;
		}
		if (normGuess.indexOf("length") != -1 || normGuess.indexOf("cable") != -1) {
			if (normGuess.indexOf("term") != -1) {
				return TERMINAL_LENGTH;
			}
			else if (normGuess.indexOf("prim") != -1) {
				return PRIMARY_LENGTH;
			}
			else if (normGuess.indexOf("inner") != -1) {
				return INNER_LENGTH;
			}
			else if (normGuess.indexOf("path") != -1) {
				return PATH_LENGTH;
			}
			else {
				return BRANCH_LENGTH;
			}
		}
		if (normGuess.indexOf("path") != -1 && normGuess.indexOf("order") != -1) {
			return PATH_ORDER;
		}
		if (normGuess.indexOf("bp") != -1 || normGuess.indexOf("branch points") != -1 || normGuess.indexOf("junctions") != -1) {
			return N_BRANCH_POINTS;
		}
		if (normGuess.indexOf("nodes") != -1) {
			return N_NODES;
		}
		if (normGuess.indexOf("node") != -1 && (normGuess.indexOf("dis") != -1 || normGuess.indexOf("dx") != -1)) {
			if (normGuess.indexOf("sq") != -1) {
				return INTER_NODE_DISTANCE_SQUARED;
			}
			else {
				return INTER_NODE_DISTANCE;
			}
		}
		if (normGuess.indexOf("radi") != -1 ) {
			if (normGuess.indexOf("mean") != -1 || normGuess.indexOf("avg") != -1 || normGuess.indexOf("average") != -1) {
				return MEAN_RADIUS;
			}
			else {
				return NODE_RADIUS;
			}
		}
		if (normGuess.indexOf("values") != -1 || normGuess.indexOf("intensit") > -1) {
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
		if (Arrays.stream(ALL_FLAGS).anyMatch(measurement::equalsIgnoreCase)) {
			// This is just so that we can use capitalized strings in the GUI
			// and lower case strings in scripts
			return WordUtils.capitalize(measurement, new char[]{});
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

	protected void assembleStats(final StatisticsInstance stat,
		final String measurement)
	{
		switch (getNormalizedMeasurement(measurement)) {
		case BRANCH_LENGTH:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getLength());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case CONTRACTION:
			try {
				for (final Path p : getBranches())
					stat.addValue(p.getContraction());
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
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
		case PARTITION_ASYMMETRY:
			try {
				for (final double asymmetry : getPartitionAsymmetry())
					stat.addValue(asymmetry);
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
			}
			break;
		case FRACTAL_DIMENSION:
			try {
				for (final double fDim : getFractalDimension())
					stat.addValue(fDim);
			} catch (final IllegalArgumentException ignored) {
				SNTUtils.log("Error: " + ignored.getMessage());
				stat.addValue(Double.NaN);
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
		case MEAN_RADIUS:
			for (final Path p : tree.list()) {
				stat.addValue(p.getMeanRadius());
			}
			break;
		case N_BRANCH_POINTS:
			for (final Path p : tree.list()) {
				stat.addValue(p.getJunctionNodes().size());
			}
			break;
		case N_NODES:
			for (final Path p : tree.list())
				stat.addValue(p.size());
			break;
		case NODE_RADIUS:
			for (final Path p : tree.list()) {
				for (int i = 0; i < p.size(); i++) {
					stat.addValue(p.getNodeRadius(i));
				}
			}
			break;
		case PATH_LENGTH:
			for (final Path p : tree.list())
				stat.addValue(p.getLength());
			break;
		case PATH_ORDER:
			for (final Path p : tree.list()) {
				stat.addValue(p.getOrder());
			}
			break;
		case PATH_FRAME:
			for (final Path p : tree.list()) {
				stat.addValue(p.getFrame());
			}
			break;
		case PATH_CHANNEL:
			for (final Path p : tree.list()) {
				stat.addValue(p.getChannel());
			}
			break;
		case PRIMARY_LENGTH:
			for (final Path p : getPrimaryBranches())
				stat.addValue(p.getLength());
			break;
		case TERMINAL_LENGTH:
			for (final Path p : getTerminalBranches())
				stat.addValue(p.getLength());
			break;
		case INNER_LENGTH:
			for (final Path p : getInnerBranches())
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
		return (lastDstats != null && tree.size() == lastDstats.size &&
			normMeasurement.equals(lastDstats.measurement));
	}

	class LastDstats {

		private final String measurement;
		final DescriptiveStatistics dStats;
		private final int size;

		LastDstats(final String measurement,
			final DescriptiveStatistics dStats)
		{
			this.measurement = measurement;
			this.dStats = dStats;
			size = tree.size();
		}
	}

	class StatisticsInstance {

		private SummaryStatistics sStatistics;
		private DescriptiveStatistics dStatistics;

		StatisticsInstance(final SummaryStatistics sStatistics) {
			this.sStatistics = sStatistics;
		}

		StatisticsInstance(final DescriptiveStatistics dStatistics) {
			this.dStatistics = dStatistics;
		}

		void addValue(final double value) {
			if (sStatistics != null) sStatistics.addValue(value);
			else dStatistics.addValue(value);
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

	/* IDE debug method */
	public static void main(final String[] args) {
		final MouseLightLoader loader = new MouseLightLoader("AA0015");
		final Tree axon = loader.getTree("axon");
		final TreeStatistics tStats = new TreeStatistics(axon);
		final int depth = 6;//Integer.MAX_VALUE;

		// retrieve some metrics:
		tStats.getHistogram("fractal dimension").show();
		NodeStatistics<?> nStats =new NodeStatistics<>(tStats.getTips());
		SNTChart hist = nStats.getAnnotatedHistogram(depth);
		hist.annotate("No. of tips: " + tStats.getTips().size());
		hist.show();

		// retrieve annotated lengths
		//AllenUtils.assignHemisphereTags(axon.getGraph());
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
	}
}
