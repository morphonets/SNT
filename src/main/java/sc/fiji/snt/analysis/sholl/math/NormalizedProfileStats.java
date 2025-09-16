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
        this(profile, DataMode.INTERSECTIONS, normalizationFlag, GUESS_SLOG);
    }

    public NormalizedProfileStats(final Profile profile, final int normalizationFlag, final int methodFlag) {
        this(profile, DataMode.INTERSECTIONS, normalizationFlag, methodFlag);
    }

    public NormalizedProfileStats(final Profile profile, final DataMode dataMode, int normalizationFlag) {
        this(profile, dataMode, normalizationFlag, GUESS_SLOG);
    }

	public NormalizedProfileStats(final Profile profile, final DataMode dataMode, int normalizationFlag, final int methodFlag) {
		super(profile, dataMode, true, true);
		normType = normalizationFlag;
		if (profile.is2D() && is3Dnormalization())
			throw new IllegalArgumentException("3D normalization specified on a 2D profile");

		countsLogNorm = new double[nPoints];
		normalizeCounts();
        radiiLog = new double[nPoints];
        for (int i = 0; i < nPoints; i++) {
            final double r = inputRadii[i];
            radiiLog[i] = (r > 0.0) ? Math.log(r) : Double.NaN;
        }

        regressionSemiLog = new SimpleRegression();
        regressionLogLog = new SimpleRegression();
        for (int i = 0; i < nPoints; i++) {
            final double y = countsLogNorm[i];
            if (!Double.isFinite(y)) continue; // skip invalid ordinates
            regressionSemiLog.addData(inputRadii[i], y);
            final double xLog = radiiLog[i];
            if (Double.isFinite(xLog)) {
                regressionLogLog.addData(xLog, y);
            }
        }
        final double r2Semi = regressionSemiLog.getRSquare();
        final double r2Log  = regressionLogLog.getRSquare();
        final double num    = Double.isFinite(r2Semi) ? r2Semi : 0.0;
        final double denom  = (Double.isFinite(r2Log) && r2Log > 0.0) ? r2Log : Double.MIN_VALUE;
        determinationRatio  = num / denom;
		assignMethod(methodFlag);

	}

    /**
     * Returns the effective shell thickness Δr to use in area/volume-based normalizations.
     * Order: nominal step size > profile property Profile#KEY_EFFECTIVE_STEP_SIZE > robust estimate
     * from median |Δr| of input radii > 0. If all fail, returns 0.
     */
    private double effectiveStepRadius() {
        // 1) Nominal
        final double dr = profile.stepSize();
        if (dr > 0d) return dr;
        // 2) Property
        final String prop = profile.getProperties().getProperty(Profile.KEY_EFFECTIVE_STEP_SIZE);
        if (prop != null && !prop.isEmpty()) {
            try {
                final double fromProp = Double.parseDouble(prop);
                if (fromProp > 0d) return fromProp;
            } catch (final NumberFormatException ignore) { /* fall through */ }
        }
        // 3) Robust estimate from radii (median positive Δr)
        int m = 0;
        for (int i = 1; i < nPoints; i++) {
            final double d = Math.abs(inputRadii[i] - inputRadii[i - 1]);
            if (d > 0d && Double.isFinite(d)) m++;
        }
        if (m > 0) {
            final double[] deltas = new double[m];
            int k = 0;
            for (int i = 1; i < nPoints; i++) {
                final double d = Math.abs(inputRadii[i] - inputRadii[i - 1]);
                if (d > 0d && Double.isFinite(d)) deltas[k++] = d;
            }
            final double median = StatUtils.percentile(deltas, 50d);
            if (median > 0d && Double.isFinite(median)) return median;
        }
        // 4) Give up
        return 0d;
    }

    // Note on Δr when step size = 0:
    // For annulus/spherical-shell normalizations we use an effective shell thickness:
    // 1) the nominal step if > 0; else
    // 2) profile property Profile#KEY_EFFECTIVE_STEP_SIZE; else
    // 3) the median positive Δr between consecutive input radii; else
    // 4) perimeter/surface fallback.
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
            final double x = regressionXdata[i];
            final double y = countsLogNorm[i];
            if (Double.isFinite(x) && Double.isFinite(y)) {
                regressionChosen.addData(x, y);
            }
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
        for (final double r : inputRadii) {
            final double denom = Math.PI * r * r;
            countsLogNorm[i] = (denom > 0 && inputCounts[i] > 0)
                    ? Math.log(inputCounts[i] / denom)
                    : Double.NaN;
            i++;
        }
    }

    private void normByPerimeter() {
        int i = 0;
        for (final double r : inputRadii) {
            final double denom = Math.PI * r * 2;
            countsLogNorm[i] = (denom > 0 && inputCounts[i] > 0)
                    ? Math.log(inputCounts[i] / denom)
                    : Double.NaN;
            i++;
        }
    }

    private void normByVolume() {
        int i = 0;
        for (final double r : inputRadii) {
            final double denom = (4.0 / 3.0) * Math.PI * r * r * r;
            countsLogNorm[i] = (denom > 0 && inputCounts[i] > 0)
                    ? Math.log(inputCounts[i] / denom)
                    : Double.NaN;
            i++;
        }
    }

    private void normBySurface() {
        int i = 0;
        for (final double r : inputRadii) {
            final double denom = 4.0 * Math.PI * r * r;
            countsLogNorm[i] = (denom > 0 && inputCounts[i] > 0)
                    ? Math.log(inputCounts[i] / denom)
                    : Double.NaN;
            i++;
        }
    }

    private void normByAnnulus() {
        final double stepRadius = profile.stepSize();
        final double effDr = (stepRadius > 0.0) ? stepRadius : effectiveStepRadius();
        int i = 0;
        for (final double r : inputRadii) {
            double y = Double.NaN;
            if (effDr > 0) {
                final double r1 = r - stepRadius / 2.0;
                final double r2 = r + stepRadius / 2.0;
                final double denom = Math.PI * (r2 * r2 - r1 * r1);
                if (denom > 0 && inputCounts[i] > 0) y = Math.log(inputCounts[i] / denom);
            } else {
                // dr=0 → annulus undefined. Use perimeter scale as the dr→0 limit proxy
                final double denom = 2.0 * Math.PI * r;
                if (denom > 0 && inputCounts[i] > 0) y = Math.log(inputCounts[i] / denom);
            }
            countsLogNorm[i++] = y;
        }
    }

    private void normBySphericalShell() {
        final double stepRadius = profile.stepSize();
        final double effDr = (stepRadius > 0.0) ? stepRadius : effectiveStepRadius();
        int i = 0;
        for (final double r : inputRadii) {
            double y = Double.NaN;
            if (effDr > 0.0) {
                final double r1 = r - stepRadius / 2.0;
                final double r2 = r + stepRadius / 2.0;
                final double denom = (4.0 / 3.0) * Math.PI * (r2 * r2 * r2 - r1 * r1 * r1);
                if (denom > 0 && inputCounts[i] > 0) y = Math.log(inputCounts[i] / denom);
            } else {
                // dr=0 → shell undefined. Use surface area scale as the dr→0 limit proxy
                final double denom = 4.0 * Math.PI * r * r;
                if (denom > 0 && inputCounts[i] > 0) y = Math.log(inputCounts[i] / denom);
            }
            countsLogNorm[i++] = y;
        }
    }

	/**
	 * Returns the ordinates of the Semi-log/Log-log plot for sampled data.
	 *
	 * @return normalized counts, ie, log(sampled intersections / normalizer)
	 */
	@Override
	public double[] getYValues() {
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
	public double[] getXValues() {
		return regressionXdata;
	}

	@Override
	public double[] getFitYValues() {
		final double[] counts = new double[nPoints];
		int i = 0;
		for (final double x : getXValues()) {
			counts[i++] = regressionChosen.predict(x);
		}
		return counts;
	}

	public static int getNormalizerFlag(final String string) {
        return switch (string.toLowerCase().trim()) {
            case "area" -> AREA;
            case "perimeter" -> PERIMETER;
            case "annulus" -> ANNULUS;
            case "volume" -> VOLUME;
            case "surface", "surface area" -> SURFACE;
            case "spheric shell", "spherical shell" -> S_SHELL;
            default -> -1;
        };
	}

	public static int getMethodFlag(final String string) {
        return switch (string.toLowerCase().replace(" ", "").trim()) {
            case "automaticallychoose", "default", "guess", "determine", "calculate" -> GUESS_SLOG;
            case "semi-log", "semi_log", "semilog" -> SEMI_LOG;
            case "log-log", "log_log", "loglog" -> LOG_LOG;
            default -> -1;
        };
	}
}
