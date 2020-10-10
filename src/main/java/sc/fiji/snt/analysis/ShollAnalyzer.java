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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ij.plugin.filter.MaximumFinder;
import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.sholl.Logger;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;
import sc.fiji.snt.util.ShollPoint;

/**
 * Class to retrieve Sholl metrics from a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class ShollAnalyzer {

	static final String MEAN = "Mean";
	static final String MEDIAN = "Median";
	static final String MAX = "Max";
	static final String MAX_FITTED = "Max (fitted)";
	static final String MAX_FITTED_RADIUS = "Max (fitted) radius";
	static final String POLY_FIT_DEGREE = "Degree of Polynomial fit";
	static final String N_MAX = "No. maxima";
	static final String CENTROID = "Centroid";
	static final String CENTROID_RADIUS = "Centroid radius";
	static final String SUM = "Sum";
	static final String VARIANCE = "Variance";
	static final String SKEWENESS = "Skeweness";
	static final String KURTOSIS = "Kurtosis";
	static final String N_SECONDARY_MAX = "No. secondary maxima";
	static final String MAX_RADIUS_PREFIX = "Max radius";
	static final String SECONDARY_MAX_PREFIX = "Secondary max";
	static final String SECONDARY_MAX_RADIUS_PREFIX = "Secondary max radius";
	static final String ENCLOSING_RADIUS = "Enclosing radius";
	static final String DECAY = "Decay";
	static final String INTERCEPT = "Intercept";

	static final String[] ALL_FLAGS = { //
			CENTROID, //
			CENTROID_RADIUS, //
			DECAY, //
			ENCLOSING_RADIUS, //
			INTERCEPT, //
			KURTOSIS, //
			MAX, //
			MAX_FITTED, //
			MAX_FITTED_RADIUS, //
			MEAN, //
			MEDIAN, //
			N_MAX, //
			N_SECONDARY_MAX, //
			POLY_FIT_DEGREE, //
			SKEWENESS, //
			SUM, //
			VARIANCE, //
	};

	private final Tree tree;
	private final LinkedHashMap<String, Number> metrics;
	private final ArrayList<Double> maximaRadii;
	private final ArrayList<double[]> secondaryMaxima;;

	private NormalizedProfileStats nStats;
	private LinearProfileStats lStats;
	private final TreeParser parser;
	private final int[] polynomialDegreeRange;
	private boolean includeFitting;

	/**
	 * Instantiates a new Sholl analyzer using default settings, i.e., focused on
	 * Tree's root; using sampling step size of average inter-node distance; curve
	 * fitting enabled; and all other parameters left to defaults/auto-discovery.
	 *
	 * @param tree the Tree to be analyzed
	 */
	public ShollAnalyzer(final Tree tree) {
		this.tree = tree;
		parser = new TreeParser(tree);
		parser.setCenter(tree.getRoot());
		final double stepSizeSq = new TreeStatistics(tree).getSummaryStats(TreeStatistics.INTER_NODE_DISTANCE_SQUARED)
				.getMean();
		parser.setStepSize(Math.sqrt(stepSizeSq));
		metrics = new LinkedHashMap<>();
		maximaRadii = new ArrayList<>();
		secondaryMaxima = new ArrayList<>();
		includeFitting = true;// default in Sholl plugin
		polynomialDegreeRange = new int[] { 2, 20 }; // defaults in Sholl plugin
	}

	/**
	 * Sets the polynomial fit range for linear Sholl statistics.
	 *
	 * @param fromDegree the lowest degree to be considered. Set it to -1 to skip
	 *                   polynomial fit
	 * @param toDegree   the highest degree to be considered. Set it to -1 to skip
	 *                   polynomial fit
	 */
	public void setPolynomialFitRange(final int fromDegree, final int toDegree) {
		polynomialDegreeRange[0] = fromDegree;
		polynomialDegreeRange[1] = toDegree;
	}

	/**
	 * Sets whether curve fitting computations should be performed.
	 *
	 * @param enable if {@code true} polynomial and linear regression on normalized
	 *               Sholl data will be performed. Enabling this option may
	 *               adversely affect performance.
	 */
	public void setEnableCurveFitting(final boolean enable) {
		includeFitting = enable;
	}

	public static List<String> getMetrics() {
		return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
	}

	public Map<String, Number> getSingleValueMetrics() {

		if (!metrics.isEmpty()) {
			return metrics;
		}

		getLinearStats();
		if (lStats != null) {
			if (includeFitting && polynomialDegreeRange[0] + polynomialDegreeRange[1] > 0) {
				try {
					final int bestFit = lStats.findBestFit(polynomialDegreeRange[0], polynomialDegreeRange[1], 0.60,
							-1);
					if (lStats.validFit()) {
						final ShollPoint fMax = lStats.getCenteredMaximum(true);
						metrics.put(MAX_FITTED, fMax.y);
						metrics.put(MAX_FITTED_RADIUS, fMax.x);
						metrics.put(POLY_FIT_DEGREE, bestFit);
					}
				} catch (final NullPointerException t) {
					SNTUtils.error("Metric skipped under exception", t);
				}
			}
			metrics.put(MEAN, lStats.getMean());
			metrics.put(MEAN, lStats.getMean());
			metrics.put(SUM, lStats.getSum());
			metrics.put(MAX, lStats.getMax());
			metrics.put(N_MAX, getMaximaRadii().size());
			metrics.put(N_SECONDARY_MAX, getSecondaryMaxima().size());
			final ShollPoint centroid = lStats.getCentroid();
			metrics.put(CENTROID, centroid.y);
			metrics.put(CENTROID_RADIUS, centroid.x);
			metrics.put(MEDIAN, lStats.getMedian());
			metrics.put(KURTOSIS, lStats.getKurtosis());
			metrics.put(SKEWENESS, lStats.getSkewness());
			metrics.put(VARIANCE, lStats.getVariance());
			metrics.put(ENCLOSING_RADIUS, lStats.getEnclosingRadius(1));
		}
		if (includeFitting) {
			getNormStats();
			if (nStats != null && nStats.validFit()) {
				metrics.put(DECAY, nStats.getShollDecay());
				metrics.put(INTERCEPT, nStats.getIntercept());
			}
		}
		return metrics;
	}

	public ArrayList<Double> getMaximaRadii() {
		if (!maximaRadii.isEmpty()) return maximaRadii;
		getLinearStats();
		if (lStats == null) return maximaRadii;
		final ArrayList<ShollPoint> maximaPoints = lStats.getMaxima();
		for (final ShollPoint mp : maximaPoints) {
			maximaRadii.add(mp.x);
		}
		return maximaRadii;
	}

	public ArrayList<double[]> getSecondaryMaxima() {

		if (!secondaryMaxima.isEmpty()) return secondaryMaxima;
		getLinearStats();
		if (lStats == null) return secondaryMaxima;

		double[] dataToUse = lStats.getYvalues();
		double variance = lStats.getVariance();
		double absoluteMax = lStats.getMax();
		try {
			if (lStats.validFit()) {
				dataToUse = lStats.getFitYvalues();
				variance = lStats.getVariance(true);
				absoluteMax = lStats.getMax(true);
			}
		} catch (final Exception ignored) {
			SNTUtils.log("Polynomial fit discarded...");
		}
		final int[] allPeakIndices = MaximumFinder.findMaxima(dataToUse, Math.sqrt(variance), false);
		final ArrayList<Integer> filteredIndices = new ArrayList<>();
		for (int i = 0; i < allPeakIndices.length; i++) {
			if (dataToUse[allPeakIndices[i]] < absoluteMax) {
				filteredIndices.add(allPeakIndices[i]);
			}
		}
		final double[] sMaximaRadii = getValues(lStats.getXvalues(), filteredIndices);
		final double[] sMaxima = getValues(dataToUse, filteredIndices);
		for (int i = 0; i < filteredIndices.size(); i++) {
			secondaryMaxima.add(new double[] {sMaximaRadii[i], sMaxima[i]});
		}
		return secondaryMaxima;
	}

	double[] getValues(final double[] array, final ArrayList<Integer> indices) {
		final int size = indices.size();
		final double[] values = new double[size];
		for (int i = 0; i < size; i++)
			values[i] = array[indices.get(i)];
		return values;
	}

	/**
	 * Gets the {@link LinearProfileStats} associated with this analyzer. By default
	 * it is set to determine the polynomial of 'best-fit' (2-20 degree range.)
	 *
	 * @return the LinearProfileStats instance
	 */
	public LinearProfileStats getLinearStats() {
		if (lStats == null) {
			if (!parser.successful())
				parser.parse();
			if (parser.successful())
				lStats = new LinearProfileStats(parser.getProfile());
		}
		return lStats;
	}

	/**
	 * Gets the {@link NormalizedProfileStats} associated with this analyzer. By
	 * default it is set to determine the regression method of 'best-fit' (log-log
	 * or semi-log) using shell volume as normalizer (if Tree has a depth component)
	 * or shell area if Tree is 2D.
	 *
	 * @return the LinearProfileStats instance
	 */
	public NormalizedProfileStats getNormStats() {
		if (nStats == null) {
			if (!parser.successful())
				parser.parse();
			if (parser.successful()) {
				nStats = new NormalizedProfileStats(parser.getProfile(),
						(tree.is3D()) ? NormalizedProfileStats.VOLUME : NormalizedProfileStats.AREA);
			}
		}
		return nStats;
	}

	public static void main(final String[] args) throws InterruptedException {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		//final Tree tree = new Tree("/home/tferr/code/morphonets/SNT/clustering/zi/cells/AA0768.json", "axon");
		final ShollAnalyzer analyzer = new ShollAnalyzer(tree);
		analyzer.setPolynomialFitRange(2, 70);
		Logger logger = new Logger(ij.context());
		logger.setDebug(true);
		analyzer.getLinearStats().setLogger(logger);
		analyzer.getSingleValueMetrics().forEach((metric, value) -> {
			System.out.println(metric + ":\t" + value);
		});
		System.out.println("Max occurs at:\t" + String.valueOf(analyzer.getMaximaRadii()));
		analyzer.getSecondaryMaxima().forEach(sMax -> {
			System.out.println("Sec max occur at:\t" + Arrays.toString(sMax));
		});
		analyzer.getLinearStats().getPlot().show();
	}
}
