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
package sc.fiji.snt.analysis.sholl.math;

import java.util.Arrays;

import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import sc.fiji.snt.analysis.sholl.Profile;


/**
 * Calculates Sholl Metrics from normalized profiles, including Sholl decay and
 * methods for determination of 'optimal' normalization. Relies heavily on the
 * {@code org.apache.commons.math3} package.
 *
 * @author Tiago Ferreira
 */
public class NormalizedProfileStats extends CommonStats implements ShollStats {

	/* Input */
	private final int normType;

	/* Regression fit */
	private final SimpleRegression regressionLogLog;
	private final SimpleRegression regressionSemiLog;
	private SimpleRegression regressionChosen;

	private final double[] radiiLog;
	private final double[] countsLogNorm;
	private final double determinationRatio;
	private int chosenMethod;
	private String normTypeString;
	private String chosenMethodDescription;

	private double[] regressionXdata;

	public NormalizedProfileStats(final Profile profile, final int normalizationFlag) {
		this(profile, normalizationFlag, GUESS_SLOG);
	}

	public NormalizedProfileStats(final Profile profile, final int normalizationFlag, final int methodFlag) {

		super(profile, true, true);
		normType = normalizationFlag;
		if (profile.is2D() && is3Dnormalization())
			throw new IllegalArgumentException("3D normalization specified on a 2D profile");

		countsLogNorm = new double[nPoints];
		normalizeCounts();
		radiiLog = Arrays.stream(inputRadii).map(r -> Math.log(r)).toArray();

		regressionSemiLog = new SimpleRegression();
		regressionLogLog = new SimpleRegression();
		for (int i = 0; i < nPoints; i++) {
			regressionSemiLog.addData(inputRadii[i], countsLogNorm[i]);
			regressionLogLog.addData(radiiLog[i], countsLogNorm[i]);
		}
		determinationRatio = regressionSemiLog.getRSquare() / Math.max(Double.MIN_VALUE, regressionLogLog.getRSquare());
		assignMethod(methodFlag);

	}

	private void normalizeCounts() {

		switch (normType) {
		case AREA:
			normByArea();
			normTypeString = "Area";
			break;
		case VOLUME:
			normByVolume();
			normTypeString = "Volume";
			break;
		case PERIMETER:
			normByPerimeter();
			normTypeString = "Perimeter";
			break;
		case SURFACE:
			normBySurface();
			normTypeString = "Surface";
			break;
		case ANNULUS:
			normByAnnulus();
			normTypeString = "Annulus";
			break;
		case S_SHELL:
			normBySphericalShell();
			normTypeString = "Spherical shell";
			break;
		default:
			throw new IllegalArgumentException("Unrecognized flag");
		}
	}

	private void assignMethod(final int flag) {
		switch (flag) {
		case SEMI_LOG:
			chosenMethod = SEMI_LOG;
			chosenMethodDescription = "Semi-log";
			regressionChosen = regressionSemiLog;
			regressionXdata = inputRadii;
			break;
		case LOG_LOG:
			chosenMethod = LOG_LOG;
			chosenMethodDescription = "Log-log";
			regressionChosen = regressionLogLog;
			regressionXdata = radiiLog;
			break;
		case GUESS_SLOG:
			assignMethod((determinationRatio >= 1) ? SEMI_LOG : LOG_LOG);
			break;
		default:
			throw new IllegalArgumentException("Unrecognized flag");
		}
	}

	public int getNormalizer() {
		return normType;
	}

	public String getNormalizerDescription() {
		return normTypeString;
	}

	public int getMethod() {
		return chosenMethod;
	}

	public String getMethodDescription() {
		return chosenMethodDescription;
	}

	public void resetRegression() {
		regressionChosen.clear();
		for (int i = 0; i < nPoints; i++) {
			regressionChosen.addData(regressionXdata[i], countsLogNorm[i]);
		}
	}

	public SimpleRegression getRegression() {
		return regressionChosen;
	}

	public void restrictRegToPercentile(final double p1, final double p2) {
		final double x1 = StatUtils.percentile(regressionXdata, p1);
		final double x2 = StatUtils.percentile(regressionXdata, p2);
		restrictRegToRange(x1, x2);
	}

	public void restrictRegToRange(final double x1, final double x2) {
		final int p1Idx = getIndex(regressionXdata, x1);
		final int p2Idx = getIndex(regressionXdata, x2);
		for (int i = 0; i < p1Idx; i++)
			regressionChosen.removeData(regressionXdata[i], countsLogNorm[i]);
		for (int i = p2Idx + 1; i < nPoints; i++)
			regressionChosen.removeData(regressionXdata[i], countsLogNorm[i]);
	}

	@Override
	public double getRSquaredOfFit() {
		return regressionChosen.getRSquare();
	}

	/**
	 * Returns <a href="http://mathworld.wolfram.com/CorrelationCoefficient.html">
	 * Pearson's product moment correlation coefficient</a>, usually denoted r.
	 *
	 * @return Pearson's r
	 */
	public double getR() {
		return regressionChosen.getR();
	}

	/**
	 * Returns the intercept of the estimated regression line,
	 *
	 * @return the intercept of the regression line
	 */
	public double getIntercept() {
		return regressionChosen.getIntercept();
	}

	public double getSlope() {
		return regressionChosen.getSlope();
	}

	public double getShollDecay() {
		return -getSlope();
	}

	public double getDeterminationRatio() {
		return determinationRatio;
	}

	public boolean is2Dnormalization() {
		final int methods2D = AREA + PERIMETER + ANNULUS;
		return ((methods2D & normType) != 0);
	}

	public boolean is3Dnormalization() {
		final int methods3D = VOLUME + SURFACE + S_SHELL;
		return ((methods3D & normType) != 0);
	}

	private void normByArea() {
		int i = 0;
		for (final double r : inputRadii) { // Area of circle
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * r * r));
			i++;
		}
	}

	private void normByPerimeter() {
		int i = 0;
		for (final double r : inputRadii) { // Length of circumference
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * r * 2));
			i++;
		}
	}

	private void normByVolume() {
		int i = 0;
		for (final double r : inputRadii) { // Volume of sphere
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * r * r * r * 4 / 3));
			i++;
		}
	}

	private void normBySurface() {
		int i = 0;
		for (final double r : inputRadii) { // Surface area of sphere
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * r * r * 4));
			i++;
		}
	}

	private void normByAnnulus() {
		final double stepRadius = profile.stepSize();
		int i = 0;
		for (final double r : inputRadii) { // Area of annulus
			final double r1 = r - stepRadius / 2;
			final double r2 = r + stepRadius / 2;
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * (r2 * r2 - r1 * r1)));
			i++;
		}
	}

	private void normBySphericalShell() {
		final double stepRadius = profile.stepSize();
		int i = 0;
		for (final double r : inputRadii) { // Volume of spherical shell
			final double r1 = r - stepRadius / 2;
			final double r2 = r + stepRadius / 2;
			countsLogNorm[i] = Math.log(inputCounts[i] / (Math.PI * 4 / 3 * (r2 * r2 * r2 - r1 * r1 * r1)));
			i++;
		}
	}

	/**
	 * Returns the ordinates of the Semi-log/Log-log plot for sampled data.
	 *
	 * @return normalized counts, ie, log(sampled intersections / normalizer)
	 */
	@Override
	public double[] getYvalues() {
		return countsLogNorm;
	}

	@Override
	public boolean validFit() {
		return (regressionChosen != null);
	}

	/**
	 * Returns the abscissae of the Semi-log /Log-log plot for sampled data.
	 * Note that distances associated with zero intersections are removed from
	 * the input profile since log(0) is undefined.
	 *
	 * @return sampled distances in the case of Semi-log analysis or their log
	 *         transform in the case of Log-log analysis
	 */
	@Override
	public double[] getXvalues() {
		return regressionXdata;
	}

	@Override
	public double[] getFitYvalues() {
		final double[] counts = new double[nPoints];
		int i = 0;
		for (final double x : getXvalues()) {
			counts[i++] = regressionChosen.predict(x);
		}
		return counts;
	}

	public static int getNormalizerFlag(final String string) {
		switch (string.toLowerCase().trim()) {
		case "area":
			return AREA;
		case "perimeter":
			return PERIMETER;
		case "annulus":
			return ANNULUS;
		case "volume":
			return VOLUME;
		case "surface":
		case "surface area":
			return SURFACE;
		case "spheric shell":
		case "spherical shell":
			return S_SHELL;
		default:
			return -1;
		}
	}

	public static int getMethodFlag(final String string) {
		switch (string.toLowerCase().replace(" ", "").trim()) {
		case "automaticallychoose":
		case "default":
		case "guess":
		case "determine":
		case "calculate":
			return GUESS_SLOG;
		case "semi-log":
		case "semi_log":
		case "semilog":
			return SEMI_LOG;
		case "log-log":
		case "log_log":
		case "loglog":
			return LOG_LOG;
		default:
			return -1;
		}
	}
}
