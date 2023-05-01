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
package sc.fiji.snt.analysis.sholl.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.math3.analysis.integration.BaseAbstractUnivariateIntegrator;
import org.apache.commons.math3.analysis.integration.RombergIntegrator;
import org.apache.commons.math3.analysis.integration.SimpsonIntegrator;
import org.apache.commons.math3.analysis.integration.UnivariateIntegrator;
import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.analysis.solvers.LaguerreSolver;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.exception.ConvergenceException;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.exception.MathIllegalStateException;
import org.apache.commons.math3.exception.MaxCountExceededException;
import org.apache.commons.math3.exception.NoDataException;
import org.apache.commons.math3.exception.NullArgumentException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis;
import org.apache.commons.math3.stat.descriptive.moment.Skewness;
import org.scijava.prefs.PrefService;

import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;
import sc.fiji.snt.util.ShollPoint;


/**
 * Retrieves descriptive statistics and calculates Sholl Metrics from sampled
 * Sholl profiles, including those relying on polynomial fitting. Fitting to
 * polynomials of arbitrary degree is supported. Relies heavily on the
 * {@code org.apache.commons.math3} package.
 *
 * @author Tiago Ferreira
 */
public class LinearProfileStats extends CommonStats implements ShollStats {

	/* Sampled data */
	private double maxCount = UNASSIGNED_VALUE;
	private double sumCounts = UNASSIGNED_VALUE;
	private double sumSqCounts = UNASSIGNED_VALUE;
	private ArrayList<ShollPoint> maxima;

	/* Polynomial fit */
	private PolynomialFunction pFunction;
	private int maxEval = 1000; // number of function evaluations

	private int primaryBranches = -1;


	/**
	 * Instantiates the Linear Profile Statistics.
	 *
	 * @param profile the profile to be analyzed
	 */
	public LinearProfileStats(final Profile profile) {
		super(profile);
	}

	/**
	 * Retrieves the centroid from all pairs of data (radius, inters. counts).
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the centroid {x,y} coordinates
	 */
	public ShollPoint getCentroid(final boolean fittedData) {
		if (fittedData)
			validateFit();
		final double x = StatUtils.sum(inputRadii) / nPoints;
		final double y = StatUtils.sum(fittedData ? fCounts : inputCounts) / nPoints;
		return new ShollPoint(x, y);
	}

	/** @return {@link #getCentroid(boolean) getCentroid(false)} */
	public ShollPoint getCentroid() {
		return getCentroid(false);
	}

	/**
	 * Calculates the centroid from all (radius, inters. counts) pairs assuming
	 * such points define a non-self-intersecting closed polygon. Implementation
	 * from <a href=
	 * "https://en.wikipedia.org/wiki/Centroid#Centroid_of_a_polygon">wikipedia</a>.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the point by the centroid {x,y} coordinates
	 */
	public ShollPoint getPolygonCentroid(final boolean fittedData) {
		if (fittedData)
			validateFit();
		double area = 0;
		double sumx = 0;
		double sumy = 0;
		final double[] y = (fittedData) ? fCounts : inputCounts;
		for (int i = 1; i < nPoints; i++) {
			final double cfactor = (inputRadii[i - 1] * y[i]) - (inputRadii[i] * y[i - 1]);
			sumx += (inputRadii[i - 1] + inputRadii[i]) * cfactor;
			sumy += (y[i - 1] + y[i]) * cfactor;
			area += cfactor / 2;
		}
		return new ShollPoint(sumx / (6 * area), sumy / (6 * area));
	}

	/**
	 * @return {@link #getPolygonCentroid(boolean) getPolygonCentroid(false)}
	 */
	public ShollPoint getPolygonCentroid() {
		return getPolygonCentroid(false);
	}

	/**
	 * Returns the largest radius associated with at least the number of
	 * specified counts.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @param cutoff
	 *            the cutoff for intersection counts
	 * @return the largest radius associated with the same or more cutoff counts
	 */
	public double getEnclosingRadius(final boolean fittedData, final double cutoff) {
		if (fittedData)
			validateFit();
		final double[] y = (fittedData) ? fCounts : inputCounts;
		final double enclosingRadius = Double.NaN;
		for (int i = nPoints - 1; i > 0; i--) {
			if (y[i] >= cutoff)
				return inputRadii[i];
		}
		return enclosingRadius;
	}

	/**
	 * @param cutoff
	 *            the cutoff for intersection counts
	 * @return {@link #getEnclosingRadius(boolean, double)
	 *         getEnclosingRadius(false, cutoff)}
	 */
	public double getEnclosingRadius(final double cutoff) {
		return getEnclosingRadius(false, cutoff);
	}

	/**
	 * Returns the number of intersecting radii.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the count of all radii associated with at at least one
	 *         intersection
	 */
	public int getIntersectingRadii(final boolean fittedData) {
		if (fittedData)
			validateFit();
		int count = 0;
		for (final double c : (fittedData) ? fCounts : inputCounts) {
			if (c > 0)
				count++;
		}
		return count;
	}

	/**
	 * @return {@link #getIntersectingRadii(boolean)
	 *         getIntersectingRadii(false)}
	 */
	public int getIntersectingRadii() {
		return getIntersectingRadii(false);
	}

	/**
	 * Calculates the kurtosis.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return kurtosis of intersection counts
	 */
	public double getKurtosis(final boolean fittedData) {
		if (fittedData)
			validateFit();
		final Kurtosis k = new Kurtosis();
		return k.evaluate(fittedData ? fCounts : inputCounts);
	}

	/** @return {@link #getKurtosis(boolean) getKurtosis(false)} */
	public double getKurtosis() {
		return getKurtosis(false);
	}

	/**
	 * Returns a list of all the points in the linear Sholl profile associated
	 * with the highest intersections count
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 * @return the list of points of all maxima
	 */
	public ArrayList<ShollPoint> getMaxima(final boolean fittedData) {
		if (!fittedData && maxima != null) {
			return maxima;
		}
		final double values[];
		final double max;
		final ArrayList<ShollPoint> target = new ArrayList<>();
		if (fittedData) {
			validateFit();
			values = fCounts;
			max = StatUtils.max(values);
		} else {
			max = getMaxCount(fittedData);
			values = inputCounts;
		}
		for (int i = 0; i < nPoints; i++) {
			if (values[i] == max) {
				target.add(new ShollPoint(inputRadii[i], values[i]));
			}
		}
		if (maxima == null)
			maxima = target;
		return target;
	}

	/** @return {@link #getMaxima(boolean) getMaxima(false)} */
	public ArrayList<ShollPoint> getMaxima() {
		return getMaxima(false);
	}

	/**
	 * Returns the average coordinates of all maxima.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 * @return the averaged x,y coordinates of maxima
	 */
	public ShollPoint getCenteredMaximum(final boolean fittedData) {
		final ArrayList<ShollPoint> maxima = getMaxima(fittedData);
		debug("Found " + maxima.size() + " maxima");
		double sumX = 0;
		double sumY = 0;
		for (final ShollPoint p : maxima) {
			sumX += p.x;
			sumY += p.y;
		}
		final double avgX = sumX / maxima.size();
		final double avgY = sumY / maxima.size();
		return new ShollPoint(avgX, avgY);
	}

	/**
	 * @return {@link #getCenteredMaximum(boolean) getCenteredMaximum(false)}
	 */
	public ShollPoint getCenteredMaximum() {
		return getCenteredMaximum(false);
	}

	/**
	 * Calculates the arithmetic mean.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the mean of intersection counts
	 */
	public double getMean(final boolean fittedData) {
		if (fittedData) {
			validateFit();
			return StatUtils.mean(fCounts);
		}
		return StatUtils.mean(inputCounts);
	}

	/** @return {@link #getMean(boolean) getMean(false)} */
	public double getMean() {
		return getMean(false);
	}

	/**
	 * Returns the closest index of sampled distances associated with the
	 * specified value
	 *
	 * @param radius
	 *            the query value
	 * @return the position index (zero-based) or -1 if no index could be
	 *         calculated
	 */
	public int getIndexOfRadius(final double radius) {
		return getIndex(inputRadii, radius);
	}

	/**
	 * Returns the closest index of the intersections data associated with the
	 * specified value
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 * @param inters
	 *            the query value
	 * @return the position index (zero-based) or -1 if no index could be
	 *         calculated
	 */
	public int getIndexOfInters(final boolean fittedData, final double inters) {
		if (fittedData) {
			validateFit();
			return getIndex(fCounts, inters);
		}
		return getIndex(inputCounts, inters);
	}

	/**
	 * Fits sampled data to a polynomial function and keeps the fit in memory.
	 *
	 * @param degree Degree of the polynomial to be fitted
	 * @throws NullArgumentException if the computed polynomial coefficients were
	 *                               null
	 * @throws NoDataException       if the computed polynomial coefficients were
	 *                               empty
	 * @throws ConvergenceException  if optimization failed
	 */
	public void fitPolynomial(final int degree) {
		fCounts = new double[nPoints];
		if (degree == 0) {
			final double constantTerm = getMean();
			pFunction = new PolynomialFunction(new double[] {constantTerm});
			Arrays.fill(fCounts, constantTerm);
			return;
		}
		final PolynomialCurveFitter fitter = PolynomialCurveFitter.create(degree);
		final ArrayList<WeightedObservedPoint> points = new ArrayList<>();
		for (int i = 0; i < nPoints; i++) {
			points.add(new WeightedObservedPoint(1, inputRadii[i], inputCounts[i]));
		}
		pFunction = new PolynomialFunction(fitter.fit(points));
		for (int i = 0; i < nPoints; i++) {
			fCounts[i] = pFunction.value(inputRadii[i]);
		}
	}

	/**
	 * Computes the 'best fit' polynomial between a specified range of degrees for
	 * this profile and keeps the fit in memory.
	 * <p>
	 * Note that in some edge cases specified constrains may be bypassed: E.g., If
	 * the profile is constant, a constant function is fitted. If the profile
	 * contains only two data points, a linear function is fitted
	 * </p>
	 *
	 * @param fromDegree  the lowest degree to be considered. Will be set to
	 *                    ({@link #getN()}-1) if higher than ({@link #getN()}-1).
	 * @param toDegree    the highest degree to be considered. Will be set to
	 *                    ({@link #getN()}-1) if higher than ({@link #getN()}-1)
	 * @param minRSquared the lowest value for adjusted RSquared. Only fits
	 *                    associated with an equal or higher value will be
	 *                    considered
	 * @param pvalue      the two-sample Kolmogorov-Smirnov (K-S) test statistic
	 *                    (p-value) used to discard 'unsuitable fits'. It is used to
	 *                    evaluate the null hypothesis that profiled data and
	 *                    polynomial fit represent samples drawn from the same
	 *                    probability distribution. Set it to -1 to skip K-S
	 *                    testing.
	 * @return the degree of the 'best fit' polynomial or -1 if no suitable fit
	 *         could be performed.
	 */
	public int findBestFit(final int fromDegree, final int toDegree, final double minRSquared, final double pvalue) {
		double rSqHighest = 0d;
		int bestDegree = -1;
		// edge case: constant values
		if (Arrays.stream(inputCounts).allMatch(c -> c == inputCounts[0])) {
			debug("constant distribution: falling back to constant function");
			fitPolynomial(0);
			return 0;
		}
		// edge case: linear function
		if (getN() == 2) {
			debug("N=2: falling back to linear polynomial fit");
			fitPolynomial(1);
			return 0;
		}
		final int firstDegree = Math.min(fromDegree, nPoints - 1);
		final int lastDegree = Math.min(toDegree, nPoints - 1);
		if (lastDegree != toDegree) {
			debug("Degrees > "+ lastDegree + " ignored: Not enough data points");
		}

		PolynomialFunction bestFit = null;
		for (int deg = firstDegree; deg <= lastDegree; deg++) {
			debug("Fitting to degree "+ deg );
			try {
				fitPolynomial(deg);
			} catch (final NullArgumentException | NoDataException | MathIllegalStateException exc) {
				debug("   ...failure: "+ exc.getMessage());
				continue;
			}
			final double rSq = getRSquaredOfFit(true);
			if (rSq < minRSquared) {
				debug("   fit discarded: R^2=" + String.format("%.4f", rSq) + " (≥" + minRSquared + " allowed)");
				invalidateFit();
				continue;
			}
			if (pvalue > 0 && getKStestOfFit() < pvalue) {
				invalidateFit();
				debug("   fit discarded after two-sample K-S test assessment: p<"+ pvalue );
				continue;
			}
			if (rSq > minRSquared && rSq > rSqHighest) {
				rSqHighest = rSq;
				bestDegree = deg;
				bestFit = pFunction;
			}
		}
		pFunction = bestFit;
		if (pFunction != null && fCounts != null && Arrays.stream(fCounts).allMatch(c -> c == 0d)) {
			// this can happen if a convergenceException occurred on last fit.
			fitPolynomial(bestDegree);
		}
		debug("'Best fit' degree: " + bestDegree);
		return bestDegree;
	}

	/**
	 * Runs {@link #findBestFit(int, int, double, double)} using the preferences
	 * specified by the user using the {@link ShollAnalysisPrefsCmd} command.
	 *
	 * @param fromDegree  the lowest degree to be considered. See
	 *                    {@link #findBestFit(int, int, double, double)}
	 * @param toDegree    the highest degree to be considered. See
	 *                    {@link #findBestFit(int, int, double, double)}
	 * @param prefService the {@link PrefService} used to read preferences
	 * @return the degree of the 'best fit' polynomial. See
	 *         {@link #findBestFit(int, int, double, double)}
	 */
	public int findBestFit(final int fromDegree, final int toDegree, final PrefService prefService) {
		final double rSq = prefService.getDouble(ShollAnalysisPrefsCmd.class, "rSquared", ShollAnalysisPrefsCmd.DEF_RSQUARED);
		final boolean ksTesting = prefService.getBoolean(ShollAnalysisPrefsCmd.class, "ksTesting", ShollAnalysisPrefsCmd.DEF_KS_TESTING);
		logger.debug("Determining 'Best fit' polynomial [degrees " + fromDegree + "-" + toDegree + "]...");
		return findBestFit(fromDegree, toDegree, rSq, (ksTesting) ? 0.05 : -1);
	}

	private void invalidateFit() {
		pFunction = null;
		fCounts = null;
	}

	/**
	 * Returns the abscissae of the sampled linear plot for sampled data.
	 *
	 * @return the sampled distances.
	 */
	@Override
	public double[] getXvalues() {
		return inputRadii;
	}

	/**
	 * Returns the ordinates of the sampled linear plot of sampled data.
	 *
	 *
	 * @return the sampled intersection counts.
	 */
	@Override
	public double[] getYvalues() {
		return inputCounts;
	}

	/**
	 * Returns the ordinates of the sampled linear plot of fitted data.
	 *
	 * @return the y-values of the polynomial fit retrieved at sampling
	 *         distances
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int) } has not been called
	 */
	@Override
	public double[] getFitYvalues() {
		validateFit();
		return fCounts;
	}

	/**
	 * Gets the polynomial function.
	 *
	 * @return the polynomial
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public PolynomialFunction getPolynomial() {
		validateFit();
		return pFunction;
	}

	/**
	 * Returns the degree of the polynomial.
	 *
	 * @return the polynomial degree
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public int getPolynomialDegree() {
		validateFit();
		return pFunction.degree();
	}

	/**
	 * Returns a string describing the polynomial fit
	 *
	 * @return the description, e.g., 8th degree
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public String getPolynomialAsString() {
		final int deg = getPolynomialDegree();
		String degOrd = "";
		final String[] sufixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
		switch (deg % 100) {
		case 11:
		case 12:
		case 13:
			degOrd = deg + "th";
			break;
		default:
			degOrd = deg + sufixes[deg % 10];
			break;
		}
		return degOrd + " deg.";
	}

	/**
	 * Calculates local maxima (critical points at which the derivative of the
	 * polynomial is zero) within the specified interval.
	 *
	 * @param lowerBound
	 *            the lower bound of the interval
	 * @param upperBound
	 *            the upper bound of the interval
	 * @param initialGuess
	 *            initial guess for a solution (solver's starting point)
	 * @return the set of Points defined by the {x,y} coordinates of maxima
	 *         (sorted by descendant order)
	 * @throws TooManyEvaluationsException
	 *             if the maximum number of evaluations is exceeded when solving
	 *             for one of the roots
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public Set<ShollPoint> getPolynomialMaxima(final double lowerBound, final double upperBound,
			final double initialGuess) {
		validateFit();
		final PolynomialFunction derivative = pFunction.polynomialDerivative();

		debug("Solving derivative for " + pFunction.toString());
		debug("LaguerreSolver: Evaluation limit: " + getMaxEvaluations() +
			", derivative is " + derivative.toString());

		final LaguerreSolver solver = new LaguerreSolver();
		final Complex[] roots = solver.solveAllComplex(derivative.getCoefficients(), initialGuess, getMaxEvaluations());
		if (roots == null)
			return null;
		final Set<ShollPoint> maxima = new TreeSet<>(new Comparator<ShollPoint>() {
			@Override
			public int compare(final ShollPoint p1, final ShollPoint p2) {
				return Double.compare(p2.y, p1.y); // descendant order of
													// ordinates
			}
		});
		final double tolerance = profile.stepSize();
		for (final Complex root : roots) {
			final double x = root.getReal();
			if (x < lowerBound || x > upperBound)
				continue;
			final double y = pFunction.value(x);
			if (y > pFunction.value(x - tolerance) && y > pFunction.value(x + tolerance)) {
				maxima.add(new ShollPoint(x, y));
			}
		}
		return maxima;
	}

	/**
	 * Gets the function evaluation limit for solvers
	 *
	 * @return the set maximum of evaluations (1000 by default)
	 */
	public int getMaxEvaluations() {
		return maxEval;
	}

	/**
	 * Sets the the function evaluation limit for solvers.
	 *
	 * @param maxEval
	 *            the new maximum of evaluations
	 */
	public void setMaxEvaluations(final int maxEval) {
		this.maxEval = maxEval;
	}

	/**
	 * Returns RSquared of the polynomial fit. Implementation from <a href=
	 * "https://en.wikipedia.org/wiki/Coefficient_of_determination">wikipedia</a>.
	 * \( R^2 = 1 - (SSres/SStot) \) with \( SSres = SUM(i) (yi - fi)^2 \) \(
	 * SStot = SUM(i) (yi - yavg)^2 \)
	 *
	 * @param adjusted
	 *            if {@code true} returns adjusted RSquared, i.e., adjusted for
	 *            the number of terms of the polynomial model
	 * @return RSquared, a measure for the goodness of fit
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public double getRSquaredOfFit(final boolean adjusted) {
		if (adjusted) {
			final int p = pFunction.degree() - 1;
			return getAdjustedRSquaredOfFit(p);
		}
		return getRSquaredOfFit();
	}

	/**
	 * Gets the mean value of polynomial fit.
	 *
	 * @param integrator
	 *            the integration method to retrieve the integral of the
	 *            polynomial fit. Either "Simpson" (the default), or "Romberg"
	 *            (case insensitive)
	 * @param lowerBound
	 *            the lower bound (smallest radius) for the interval
	 * @param upperBound
	 *            the upper bound (largest radius) for the interval
	 * @return the mean value of polynomial fit
	 * @throws MathIllegalArgumentException
	 *             if bounds do not satisfy the integrator requirements
	 * @throws TooManyEvaluationsException
	 *             if the maximum number of function evaluations is exceeded by
	 *             the integrator
	 * @throws MaxCountExceededException
	 *             if the maximum iteration count is exceeded by the integrator
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public double getMeanValueOfPolynomialFit(final String integrator, final double lowerBound,
			final double upperBound) {
		validateFit();
		if (pFunction.degree() == 0) return pFunction.getCoefficients()[0];
		if (pFunction.degree() == 1) return pFunction.value((lowerBound+upperBound)/2);

		final UnivariateIntegrator uniIntegrator;
		if (integrator != null && integrator.toLowerCase().contains("romberg"))
			uniIntegrator = new RombergIntegrator();
		else
			uniIntegrator = new SimpsonIntegrator();
		final double integral = uniIntegrator.integrate(BaseAbstractUnivariateIntegrator.DEFAULT_MAX_ITERATIONS_COUNT,
				pFunction, lowerBound, upperBound);
		return 1 / (upperBound - lowerBound) * integral;
	}

	/**
	 * Calculates the mean value of polynomial fit using the default integration
	 * method (Simpson's).
	 *
	 * @param lowerBound
	 *            the lower bound (smallest radius) for the interval
	 * @param upperBound
	 *            the upper bound (largest radius) for the interval
	 * @return the mean value of polynomial fit, or {@code Double.NaN} if
	 *         calculation failed.
	 * @throws NullPointerException
	 *             if {@link #fitPolynomial(int)} has not been called
	 */
	public double getMeanValueOfPolynomialFit(final double lowerBound, final double upperBound) {
		try {
			return getMeanValueOfPolynomialFit(null, lowerBound, upperBound);
		} catch (MathIllegalArgumentException | MaxCountExceededException exc) {
			debug(exc);
			return Double.NaN;
		}
	}

	/**
	 * Calculates the median.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the median of intersection counts
	 */
	public double getMedian(final boolean fittedData) {
		if (fittedData) {
			validateFit();
			return StatUtils.percentile(fCounts, 50);
		}
		return StatUtils.percentile(inputCounts, 50);
	}

	/** @return {@link #getMedian(boolean) getMedian(false)} */
	public double getMedian() {
		return getMedian(false);
	}

	/**
	 * Sets the number of primary branches associated with this profile, used to
	 * calculate ramification indices. When the number is set to -1 (the default),
	 * the number of primary branches is inferred from the number of intersections
	 * at starting radius.
	 * 
	 * @param nBranches the new primary branches
	 * @see #getPrimaryBranches(boolean)
	 * @see #getRamificationIndex(boolean)
	 */
	public void setPrimaryBranches(final int nBranches) {
		if (nBranches < 1) {
			primaryBranches = -1;
		} else {
			primaryBranches = nBranches;
		}
	}

	/**
	 * Checks whether the number of primary branches is being inferred from the
	 * number of intersections at starting radius or if it has been set explicitly.
	 * 
	 * @return true, if number of primary branches is being inferred
	 * @see #setPrimaryBranches(int)
	 */
	public boolean isPrimaryBranchesInferred() {
		return primaryBranches == -1;
	}

	/**
	 * Returns intersection counts at the smallest radius (inferred no. of primary
	 * branches), or the number of the actual number of primary branches specified
	 * by {@link #setPrimaryBranches(int)}.
	 *
	 * @param fittedData If {@code true}, and {@link #setPrimaryBranches(int)} has
	 *                   not been called, calculation is performed on polynomial
	 *                   fitted values, otherwise from sampled data. It is ignored
	 *                   if the number of primary branches has been successfully
	 *                   specified by calling {@link #setPrimaryBranches(int)}
	 * @return the number of primary branches
	 * @see #getRamificationIndex(boolean)
	 */
	public double getPrimaryBranches(final boolean fittedData) {
		if (fittedData) {
			validateFit();
		}
		if (primaryBranches != -1) return primaryBranches;
		return (fittedData) ? fCounts[0] : inputCounts[0];
	}

	/**
	 * @return {@link #getPrimaryBranches(boolean) getPrimaryBranches(false)}
	 * @see #getRamificationIndex()
	 */
	public double getPrimaryBranches() {
		return getPrimaryBranches(false);
	}

	/**
	 * Calculates the ramification index (the highest intersections count divided by
	 * the n. of primary branches). If the number of primary branches has not been
	 * specified, it is assumed to be the n. intersections at starting radius.
	 *
	 * @param fittedData If {@code true}, calculation is performed on polynomial
	 *                   fitted values, otherwise from sampled data
	 *
	 * @return the ramification index
	 * @see #setPrimaryBranches(int)
	 */
	public double getRamificationIndex(final boolean fittedData) {
		if (fittedData)
			validateFit();
		return getMaxCount(fittedData) / getPrimaryBranches(fittedData);
	}

	/**
	 * Calculates the branching index (BI) as defined in
	 * <a href="https://pubmed.ncbi.nlm.nih.gov/24503022/">PMID 24503022</a>(doi:
	 * 10.1016/j.jneumeth.2014.01.016). Note that this index is very sensitive to
	 * radius step size.
	 *
	 * @param fittedData If {@code true}, calculation is performed on polynomial
	 *                   fitted values, otherwise from sampled data
	 *
	 * @return the branching index
	 * @see #getRamificationIndex(boolean)
	 */
	public double getBranchingIndex(final boolean fittedData) {
		if (fittedData)
			validateFit();
		final double[] counts = (fittedData) ? fCounts : inputCounts;
		double bi = 0;
		for (int i = 1; i < counts.length; i++) {
			final double v = (counts[i] - counts[i - 1]) * i;
			if (v > 0)
				bi += v;
		}
		return bi;
	}

	private double getMaxCount(final boolean fittedData) {
		if (fittedData)
			return StatUtils.max(fCounts);
		if (maxCount == UNASSIGNED_VALUE)
			maxCount = StatUtils.max(inputCounts);
		return maxCount;
	}

	/**
	 * @return {@link #getRamificationIndex(boolean)
	 *         getRamificationIndex(false)}
	 */
	public double getRamificationIndex() {
		return getRamificationIndex(false);
	}

	/**
	 * Returns the largest value of intersection count.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the largest value of intersection counts
	 */
	public double getMax(final boolean fittedData) {
		if (fittedData)
			validateFit();
		return getMaxCount(fittedData);
	}

	/** @return {@link #getMax(boolean) getMax(false)} */
	public double getMax() {
		return getMaxCount(false);
	}

	/**
	 * Returns the lowest value of intersection count.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the lowest value of intersection counts
	 */
	public double getMin(final boolean fittedData) {
		if (fittedData) {
			validateFit();
			return StatUtils.min(fCounts);
		}
		return StatUtils.min(inputCounts);
	}

	/** @return {@link #getMin(boolean) getMin(false)} */
	public double getMin() {
		return getMin(false);
	}

	/**
	 * Calculates the skewness.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the skewness of intersection counts
	 */
	public double getSkewness(final boolean fittedData) {
		if (fittedData)
			validateFit();
		final Skewness s = new Skewness();
		return s.evaluate(fittedData ? fCounts : inputCounts);
	}

	/** @return {@link #getSkewness(boolean) getSkewness(false)} */
	public double getSkewness() {
		return getSkewness(false);
	}

	/**
	 * Calculates the sum.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the sum of intersection counts
	 */
	public double getSum(final boolean fittedData) {
		if (fittedData) {
			validateFit();
			return StatUtils.sum(fCounts);
		}
		if (sumCounts == UNASSIGNED_VALUE)
			sumCounts = StatUtils.sum(inputCounts);
		return sumCounts;
	}

	/** @return {@link #getSum(boolean) getSum(false)} */
	public double getSum() {
		return getSum(false);
	}

	/**
	 * Calculates the sum of the squared values.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 * @return the sum of the squared values of intersection counts
	 */
	public double getSumSq(final boolean fittedData) {
		if (fittedData) {
			validateFit();
			return StatUtils.sumSq(fCounts);
		}
		if (sumSqCounts == UNASSIGNED_VALUE)
			sumSqCounts = StatUtils.sumSq(inputCounts);
		return sumSqCounts;
	}

	/** @return {@link #getSumSq(boolean) getSumSq(false)} */
	public double getSumSq() {
		return getSumSq(false);
	}

	/**
	 * Calculates the variance.
	 *
	 * @param fittedData
	 *            If {@code true}, calculation is performed on polynomial fitted
	 *            values, otherwise from sampled data
	 *
	 * @return the variance of intersection counts
	 */
	public double getVariance(final boolean fittedData) {
		if (fittedData)
			validateFit();
		return StatUtils.variance(fittedData ? fCounts : inputCounts);
	}

	public ShollPlot plot() {
		return new ShollPlot(this);
	}

	/** @return {@link #getVariance(boolean) getVariance(false)} */
	public double getVariance() {
		return getVariance(false);
	}

	@Override
	public boolean validFit() {
		return (pFunction != null && super.validFit());
	}

}
