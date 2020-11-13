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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.text.WordUtils;

import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.annotation.BrainAnnotation;

/**
 * Computes summary and descriptive statistics from univariate properties of
 * {@link Tree} groups. For analysis of individual Trees use {@link TreeStatistics}.
 *
 * @author Tiago Ferreira
 */
public class MultiTreeStatistics extends TreeStatistics {

	/*
	 * NB: These should all be Capitalized expressions in lower case without hyphens
	 * unless for "Horton-Strahler"
	 */
	/** Flag for {@value #LENGTH} analysis. */
	public static final String LENGTH = "Cable length";

	/** Flag for {@value #TERMINAL_LENGTH} analysis. */
	public static final String TERMINAL_LENGTH = "Length of terminal branches (sum)";

	/** Flag for {@value #INNER_LENGTH} analysis. */
	public static final String INNER_LENGTH = "Length of inner branches (sum)";

	/** Flag for {@value #PRIMARY_LENGTH} analysis. */
	public static final String PRIMARY_LENGTH = "Length of primary branches (sum)";

	/** Flag for {@value #AVG_BRANCH_LENGTH} analysis. */
	public static final String AVG_BRANCH_LENGTH = "Average branch length";

	/** Flag specifying {@link Tree#assignValue(double) Tree value} statistics */
	public static final String ASSIGNED_VALUE = "Assigned value";

	/** Flag specifying {@link StrahlerAnalyzer#getRootNumber() Horton-Strahler number} statistics */
	public static final String STRAHLER_NUMBER = "Horton-Strahler number";

	/** Flag specifying {@link StrahlerAnalyzer#getAvgBifurcationRatio() Horton-Strahler bifurcation ratio} statistics */
	public static final String STRAHLER_RATIO = "Horton-Strahler bifurcation ratio";

	/** Flag specifying {@value #HIGHEST_PATH_ORDER} statistics */
	public static final String HIGHEST_PATH_ORDER = "Highest path order";

	/** Flag specifying {@value #N_BRANCHES} statistics */
	public static final String N_BRANCHES = "No. of branches";

	/** Flag specifying {@value #N_PRIMARY_BRANCHES} statistics */
	public static final String N_PRIMARY_BRANCHES = "No. of primary branches";

	/** Flag specifying {@value #N_INNER_BRANCHES} statistics */
	public static final String N_INNER_BRANCHES = "No. of inner branches";

	/** Flag specifying {@value #N_TERMINAL_BRANCHES} statistics */
	public static final String N_TERMINAL_BRANCHES = "No. of terminal branches";

	/** Flag for {@value #N_BRANCH_POINTS} statistics */
	public static final String N_BRANCH_POINTS = "No. of branch points";

	/** Flag for {@value #AVG_CONTRACTION} statistics */
	public static final String AVG_CONTRACTION = "Average contraction";

	/** Flag for {@value #AVG_FRAGMENTATION} statistics */
	public static final String AVG_FRAGMENTATION = "Average fragmentation";

	/** Flag specifying {@value #AVG_REMOTE_ANGLE} statistics */
	public static final String AVG_REMOTE_ANGLE = "Average remote bif. angle";
	
	/** Flag specifying {@value #AVG_PARTITION_ASYMMETRY} statistics */
	public static final String AVG_PARTITION_ASYMMETRY = "Average partition asymmetry";
	
	/** Flag specifying {@value #AVG_FRACTAL_DIMENSION} statistics */
	public static final String AVG_FRACTAL_DIMENSION = "Average fractal dimension";

	/** Flag for {@value #N_PATHS} statistics */
	public static final String N_PATHS = "No. of paths";

	/** Flag for {@value #MEAN_RADIUS} statistics */
	public static final String MEAN_RADIUS = "Mean radius";

	/** Flag for {@value #N_NODES} statistics */
	public static final String N_NODES = "No. of nodes";

	/** Flag specifying {@value #N_TIPS} statistics */
	public static final String N_TIPS = "No. of tips";

	/** Flag for {@value #WIDTH} statistics */
	public static final String WIDTH = "Width";

	/** Flag for {@value #HEIGHT} statistics */
	public static final String HEIGHT = "Height";

	/** Flag for {@value #DEPTH} statistics */
	public static final String DEPTH = "Depth";

	/** Flag for {@value #N_FITTED_PATHS} statistics */
	public static final String N_FITTED_PATHS = "No. of fitted paths";

	protected static String[] ALL_FLAGS = { //
			ASSIGNED_VALUE, //
			AVG_BRANCH_LENGTH, //
			AVG_CONTRACTION, //
			AVG_FRAGMENTATION, //
			AVG_REMOTE_ANGLE, //
			AVG_PARTITION_ASYMMETRY, //
			AVG_FRACTAL_DIMENSION, //
			DEPTH, //
			HEIGHT, //
			HIGHEST_PATH_ORDER, //
			LENGTH, //
			MEAN_RADIUS, //
			N_BRANCH_POINTS, //
			N_BRANCHES, //
			N_FITTED_PATHS, //
			N_NODES, //
			N_PATHS, //
			N_PRIMARY_BRANCHES, //
			N_INNER_BRANCHES, //
			N_TERMINAL_BRANCHES, //
			N_TIPS, //
			PRIMARY_LENGTH, //
			INNER_LENGTH, //
			TERMINAL_LENGTH, //
			STRAHLER_NUMBER, //
			STRAHLER_RATIO, //
			WIDTH, //
	};

	private Collection<Tree> groupOfTrees;

	/**
	 * Instantiates a new instance from a collection of Trees.
	 *
	 * @param group the collection of Trees to be analyzed
	 */
	public MultiTreeStatistics(final Collection<Tree> group) {
		super(new Tree());
		this.groupOfTrees = group;
	}

	/**
	 * Instantiates a new instance from a collection of Trees.
	 *
	 * @param group    the collection of Trees to be analyzed
	 * @param swcTypes SWC type(s) a string with at least 2 characters describing
	 *                 the SWC type allowed in the subtree (e.g., 'axn', or
	 *                 'dendrite')
	 * @throws NoSuchElementException {@code swcTypes} are not applicable to {@code group}
	 */
	public MultiTreeStatistics(final Collection<Tree> group, final String... swcTypes) throws NoSuchElementException {
		super(new Tree());
		this.groupOfTrees = new ArrayList<>();
		group.forEach( inputTree -> {
			final Tree filteredTree = inputTree.subTree(swcTypes);
			if (filteredTree != null && filteredTree.size() > 0) groupOfTrees.add(filteredTree);
		});
		if (groupOfTrees.isEmpty()) throw new NoSuchElementException("No match for the specified type(s) in group");
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
			return !(ASSIGNED_VALUE.equals(metric) || metric.toLowerCase().contains("path"));
		}).collect(Collectors.toList());
	}

	/**
	 * Gets the collection of Trees being analyzed.
	 *
	 * @return the Tree group
	 */
	public Collection<Tree> getGroup() {
		return groupOfTrees;
	}

	/**
	 * Sets an identifying label for the group of Trees being analyzed.
	 *
	 * @param groupLabel the identifying string for the group.
	 */
	public void setLabel(final String groupLabel) {
		tree.setLabel(groupLabel);
	}

	protected static String getNormalizedMeasurement(final String measurement, final boolean defaultToTreeStatistics)
			throws UnknownMetricException {
		if (Arrays.stream(ALL_FLAGS).anyMatch(measurement::equalsIgnoreCase)) {
			// This is just so that we can use capitalized strings in the GUI
			// and lower case strings in scripts
			return WordUtils.capitalize(measurement, new char[] { '-' }); // Horton-Strahler
		}
		if (measurement.startsWith("Sholl: ")) return measurement;
		String normMeasurement = tryReallyHardToGuessMetric(measurement);
		final boolean unknown = "unknown".equals(normMeasurement);
		if (!unknown && !measurement.equals(normMeasurement)) {
			SNTUtils.log("\"" + normMeasurement + "\" assumed");
		}
		if (unknown) {
			if (defaultToTreeStatistics) {
				SNTUtils.log("Unrecognized MultiTreeStatistics parameter... Defaulting to TreeStatistics analysis");
				normMeasurement = TreeStatistics.getNormalizedMeasurement(measurement);
			} else {
				throw new UnknownMetricException("Unrecognizable measurement! "
						+ "Maybe you meant one of the following?: \"" + String.join(", ", getMetrics()) + "\"");
			}
		}
		return normMeasurement;
	}

	protected static String tryReallyHardToGuessMetric(final String guess) {
		if (Arrays.stream(ALL_FLAGS).anyMatch(guess::equalsIgnoreCase)) {
			return WordUtils.capitalize(guess, '-');
		}
		SNTUtils.log("\""+ guess + "\" was not immediately recognized as parameter");
		String normGuess = guess.toLowerCase();
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
			else if (normGuess.indexOf("branch") != -1 && containsAvgReference(normGuess)) {
				return AVG_BRANCH_LENGTH;
			}
			else {
				return LENGTH;
			}
		}
		if (normGuess.indexOf("strahler") != -1 || normGuess.indexOf("horton") != -1 || normGuess.indexOf("h-s") != -1) {
			if (normGuess.indexOf("ratio") != -1) {
				return STRAHLER_RATIO;
			}
			else {
				return STRAHLER_NUMBER;
			}
		}
		if (normGuess.indexOf("path") != -1 && normGuess.indexOf("order") != -1) {
			return HIGHEST_PATH_ORDER;
		}
		if (normGuess.indexOf("assign") != -1 || normGuess.indexOf("value") != -1) {
			return ASSIGNED_VALUE;
		}
		if (normGuess.indexOf("branches") != -1) {
			if (normGuess.indexOf("prim") != -1) {
				return N_PRIMARY_BRANCHES;
			}
			else if (normGuess.indexOf("inner") != -1) {
				return N_INNER_BRANCHES;
			}
			else if (normGuess.indexOf("term") != -1) {
				return N_TERMINAL_BRANCHES;
			}
			else {
				return N_BRANCHES;
			}
		}
		if (normGuess.indexOf("bp") != -1 || normGuess.indexOf("branch") != -1 || normGuess.indexOf("junctions") != -1) {
			return N_BRANCH_POINTS;
		}
		if (normGuess.indexOf("radi") != -1 && containsAvgReference(normGuess)) {
			return MEAN_RADIUS;
		}
		if (normGuess.indexOf("nodes") != -1 ) {
			return N_NODES;
		}
		if (normGuess.indexOf("tips") != -1 || normGuess.indexOf("termin") != -1 || normGuess.indexOf("end") != -1 ) {
			// n tips/termini/terminals/end points/endings
			return N_TIPS;
		}
		if (normGuess.indexOf("paths") != -1) {
			if (normGuess.indexOf("fit") != -1) {
				return N_FITTED_PATHS;
			}
			else {
				return N_PATHS;
			}
		}
		if (containsAvgReference(normGuess)) {
			if (normGuess.indexOf("contraction") != -1) {
				return AVG_CONTRACTION;
			}
			if (normGuess.indexOf("fragmentation") != -1) {
				return AVG_FRAGMENTATION;
			}
			if (normGuess.indexOf("remote") != -1 || normGuess.indexOf("bif") != -1) {
				return AVG_REMOTE_ANGLE;
			}
			if (normGuess.indexOf("partition") != -1 || normGuess.indexOf("asymmetry") != -1) {
				return AVG_PARTITION_ASYMMETRY;
			}
			if (normGuess.indexOf("fractal") != -1) {
				return AVG_FRACTAL_DIMENSION;
			}
		}
		return "unknown";
	}

	private static boolean containsAvgReference(final String string) {
		return (string.indexOf("mean") != -1 || string.indexOf("avg") != -1 || string.indexOf("average") != -1);
	}

	@Override
	public SummaryStatistics getSummaryStats(final String metric) {
		final SummaryStatistics sStats = new SummaryStatistics();
		assembleStats(new StatisticsInstance(sStats), getNormalizedMeasurement(metric, true));
		return sStats;
	}

	@Override
	public DescriptiveStatistics getDescriptiveStats(final String metric) {
		final DescriptiveStatistics dStats = new DescriptiveStatistics();
		final String normMeasurement = getNormalizedMeasurement(metric, true);
		if (!lastDstatsCanBeRecycled(normMeasurement)) {
			assembleStats(new StatisticsInstance(dStats), normMeasurement);
			lastDstats = new LastDstats(normMeasurement, dStats);
		}
		return lastDstats.dStats;
	}

	@Override
	protected void assembleStats(final StatisticsInstance stat,
		final String measurement) throws UnknownMetricException
	{
		try {
			String normMeasurement = getNormalizedMeasurement(measurement, false);
			for (final Tree t : groupOfTrees) {
				final TreeAnalyzer ta = new TreeAnalyzer(t);
				stat.addValue(ta.getMetricInternal(normMeasurement).doubleValue());
			}
		} catch (final UnknownMetricException ignored) {
			SNTUtils.log("Unrecognized MultiTreeStatistics parameter... Defaulting to TreeStatistics analysis");
			final String normMeasurement = TreeStatistics.getNormalizedMeasurement(measurement); // Will throw yet another UnknownMetricException
			assignGroupToSuperTree();
			super.assembleStats(stat, normMeasurement);
		}
	}

	private void assignGroupToSuperTree() {
		if (super.tree.isEmpty()) {
			for (final Tree tree : groupOfTrees)
				super.tree.list().addAll(tree.list());
		}
	}

	@Override
	public void restrictToSWCType(final int... types) {
		throw new IllegalArgumentException("Operation not supported. Only filtering in constructor is supported");
	}

	@Override
	public void resetRestrictions() {
		throw new IllegalArgumentException("Operation not supported. Only filtering in constructor is supported");
	}

	@Override
	public Set<BrainAnnotation> getAnnotations() {
		assignGroupToSuperTree();
		return super.getAnnotations();
	}

	@Override
	public Set<BrainAnnotation> getAnnotations(final int level) {
		assignGroupToSuperTree();
		return super.getAnnotations(level);
	}

//	@Override
//	public double getCableLength(final BrainAnnotation compartment) {
//		assignGroupToSuperTree();
//		return super.getCableLength(compartment);
//	}
//
//	@Override
//	public double getCableLength(final BrainAnnotation compartment, final boolean includeChildren) {
//		assignGroupToSuperTree();
//		return super.getCableLength(compartment, includeChildren);
//	}

	public SNTChart getHistogram(final String metric) {
		final String normMeasurement = getNormalizedMeasurement(metric, true);
		final HistogramDatasetPlusMulti datasetPlus = new HistogramDatasetPlusMulti(normMeasurement);
		return getHistogram(normMeasurement, datasetPlus);
	}

	class HistogramDatasetPlusMulti extends HDPlus {
		HistogramDatasetPlusMulti(String measurement) {
			super(measurement, false);
			getDescriptiveStats(measurement);
			for (final double v : lastDstats.dStats.getValues()) {
				values.add(v);
			}
		}
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		SNTUtils.setDebugMode(true);
		final MultiTreeStatistics treeStats = new MultiTreeStatistics(sntService.demoTrees());
		treeStats.setLabel("Demo Dendrites");
		treeStats.getHistogram("junctions").show();
	}
}
