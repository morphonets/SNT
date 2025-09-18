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

import java.util.NavigableSet;
import java.util.TreeSet;

import org.apache.commons.math3.exception.InsufficientDataException;
import org.apache.commons.math3.stat.Frequency;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest;
import org.scijava.Context;
import org.scijava.command.ContextCommand;

import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.util.Logger;


/**
 * @author Tiago Ferreira
 *
 */
class CommonStats extends ContextCommand implements ShollStats {

	protected final static double UNASSIGNED_VALUE = Double.MIN_VALUE;

    private DataMode dataMode = DataMode.INTERSECTIONS; // Default mode
    protected final double[] inputRadii;
	protected final double[] inputCounts; // holds sampled data. Either intersection counts or lengths
	protected final Profile profile;
	protected int nPoints;
	protected double[] fCounts; // holds fitted data. Either intersection counts or lengths
	protected ShollPlot plot;
	protected Logger logger;

	protected CommonStats(final Profile profile, final DataMode dataMode) {
        this(profile, dataMode, false, false);
	}

	protected CommonStats(final Profile profile, final DataMode dataMode, final boolean duplicateProfile, final boolean trimZeroes) throws IllegalArgumentException {

		if (profile == null)
			throw new IllegalArgumentException("Cannot instantiate analysis with a null profile");
		if (profile.isEmpty())
			throw new IllegalArgumentException("Cannot instantiate analysis with an empty profile");

        setDataMode(dataMode);
		this.profile = (duplicateProfile) ? profile.duplicate() : profile;
		// Remove all zeroes from input sample: this is required when e.g.,
		// performing log transforms, since log(0) is undefined
		if (trimZeroes)
			this.profile.trimZeroCounts();

		nPoints = this.profile.size();
		inputRadii = new double[nPoints];
		inputCounts = new double[nPoints];
		int idx = 0;
        if (dataMode == DataMode.LENGTH) {
            for (final ProfileEntry entry : this.profile.entries()) {
                inputRadii[idx] = entry.radius;
                inputCounts[idx++] = entry.length;
            }
        } else if (dataMode == DataMode.EXTRA) {
            for (final ProfileEntry entry : this.profile.entries()) {
                inputRadii[idx] = entry.radius;
                inputCounts[idx++] = entry.extra;
            }
        } else {
            for (final ProfileEntry entry : this.profile.entries()) {
                inputRadii[idx] = entry.radius;
                inputCounts[idx++] = entry.count;
            }
        }
	}

	/**
	 * Returns the two-sample Kolmogorov-Smirnov (K-S) test between the polynomial
	 * fit and sampled intersections as a measurement of goodness of fit.
	 *
	 * @return the test statistic (p-value) used to evaluate the null hypothesis
	 *         that sampled data and polynomial fit represent samples drawn from the
	 *         same probability distribution
	 * @throws NullPointerException      if curve fitting has not been performed
	 * @throws InsufficientDataException if sampled data contains fewer than two
	 *                                   data points
	 */
	public double getKStestOfFit() {
		validateFit();
		final KolmogorovSmirnovTest test = new KolmogorovSmirnovTest();
		return test.kolmogorovSmirnovTest(inputCounts, fCounts);
	}

	public double getRSquaredOfFit() {
		validateFit();

		// calculate 'residual sum of squares'
		double ssRes = 0.0;
		for (int i = 0; i < nPoints; i++) {
			final double y = inputCounts[i];
			final double f = fCounts[i];
			ssRes += (y - f) * (y - f);
		}
		// calculate 'total sum of squares'
		final double sampleAvg = StatUtils.mean(inputCounts);
		double ssTot = 0.0;
		for (final double y : inputCounts) {
			ssTot += (y - sampleAvg) * (y - sampleAvg);
		}

		return 1.0 - (ssRes / ssTot);
	}

	public ShollPlot getPlot(final boolean cumulativeFrequencies) {
		if (plot == null || plot.isUsingCumulativeFrequencies() != cumulativeFrequencies)
			plot = new ShollPlot(this, cumulativeFrequencies);
		return plot;
	}

	protected double getAdjustedRSquaredOfFit(final int p) {
		try {
			double rSquared = getRSquaredOfFit();
			rSquared = rSquared - (1 - rSquared) * ((double) p / (nPoints - p - 1));
			return rSquared;
		} catch (final ArithmeticException ex) {
			return Double.NaN;
		}
	}

	protected int getIndex(final double[] array, final double value) {
		final NavigableSet<Double> ns = new TreeSet<>();
		for (final double element : array)
			ns.add(element);
		final Double candidate = ns.floor(value);
		if (candidate == null)
			return -1;
		for (int i = 0; i < array.length; i++)
			if (array[i] == candidate)
				return i;
		return -1;
	}

	protected void validateFit() {
		if (!validFit())
			throw new IllegalArgumentException("Fitted data required but fit not yet performed");
	}

	protected void debug(Object msg) {
		if (logger != null)
			logger.debug(msg);
	}

	public void setLogger(final Logger logger) {
			this.logger = logger;
	}

	public void setLogger(final Logger logger, final boolean debug) {
		this.logger = logger;
		logger.setDebug(debug);
	}

	public void setDebug(boolean debug) {
		if (logger != null)
			logger.setDebug(debug);
	}

	@Override
	public void setContext(Context context) {
		super.setContext(context);
		if (logger == null)
			logger = new Logger(context, "Sholl");
	}

	/**
	 * Returns X-values of a Sholl plot.
	 *
	 * @return X-values of a Sholl plot
	 */
	@Override
	public double[] getXValues() {
		return inputRadii;
	}

	@Override
	public double[] getYValues() {
		return inputCounts;
	}

	@Override
	public double[] getYValues(final boolean asCumulativeFrequencies) {
		return (asCumulativeFrequencies) ? getCumFrequencies(getYValues()) : getYValues();
	}

	@Override
	public int getN() {
		return nPoints;
	}

	@Override
	public double[] getFitYValues() {
		return fCounts;
	}

	@Override
	public double[] getFitYValues(final boolean asCumulativeFrequencies) {
		return (asCumulativeFrequencies) ? getCumFrequencies(getFitYValues()) : getFitYValues();
	}

	private double[] getCumFrequencies(final double[] array) {
		final Frequency freq = new Frequency();
		double min = Double.MAX_VALUE;
		double max = Double.MIN_VALUE;
		for (final double y : array) {
			freq.addValue(y);
			if (y < min) min = y;
			if (y > max) max = y;
		}
		final double[] yValues = new double[array.length];
		final double bin = (max - min) / (array.length - 1);
		for (int i = 0; i < array.length; i++) {
			yValues[i] = freq.getCumPct(min + i * bin);
		}
		return yValues;
	}

	/**
	 * Checks if valid fitted data exists.
	 *
	 * @return {@code true} if polynomial fitted data exists
	 */
	@Override
	public boolean validFit() { return (fCounts != null && fCounts.length > 0); }

	@Override
	public Profile getProfile() {
		return profile;
	}

    @Override
    public DataMode getDataMode() {
        return dataMode;
    }

    @Override
    public void setDataMode(final DataMode mode) {
        if (inputCounts != null)
            throw new IllegalArgumentException("DataMode must be called before data is gathered from profile");
        this.dataMode = mode;
    }

    @Override
	public void run() {
		// implemented by extending classes
	}

}
