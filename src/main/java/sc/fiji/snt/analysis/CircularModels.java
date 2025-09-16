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

/**
 * Shared utilities for circular modeling, including von Mises fits.
 */
public final class CircularModels {

    private CircularModels() {
    } // no instantiation

    /**
     * Returns κ from \bar R using standard fast approximations
     * as per Fisher et. al., Statistical Analysis of Spherical Data and Matlab's CircStat package by Philipp Berens
     */
    public static double kappaFromRBar(final double R) {
        if (!(R > 0.0)) return 0.0;
        if (R < 0.53) return 2 * R + R * R * R + 5.0 * R * R * R * R * R / 6.0;
        if (R < 0.85) return -0.4 + 1.39 * R + 0.43 / (1.0 - R);
        return 1.0 / (R * R * R - 4.0 * R * R + 3.0 * R);
    }

    /**
     * Normalizes deg to [0,360).
     */
    public static double norm360(final double deg) {
        double x = deg % 360.0;
        return (x < 0) ? x + 360.0 : x;
    }

    /**
     * Normalizes deg to [0,180[
     */
    public static double norm180(final double deg) {
        double x = deg % 180.0;
        return (x < 0) ? x + 180.0 : x;
    }

    /**
     * Fit von Mises to a weighted histogram over angle bins.
     * Angles are assumed to be bin centers: (i+0.5)*angleStepDeg.
     *
     * @param weights      non-negative bin heights, length = nBins
     * @param angleStepDeg bin width in degrees (360/nBins)
     * @param domain       DIRECTIONAL (0–360) or AXIAL (0–180)
     */
    public static VonMisesFit fitFromHistogram(final double[] weights,
                                               final double angleStepDeg,
                                               final Domain domain) {
        if (weights == null || weights.length == 0 || !(angleStepDeg > 0)) {
            return new VonMisesFit(Double.NaN, 0.0, 0.0, domain);
        }
        final int n = weights.length;
        final double step = Math.toRadians(angleStepDeg);
        double C = 0.0, S = 0.0, W = 0.0;

        if (domain == Domain.DIRECTIONAL) {
            for (int i = 0; i < n; i++) {
                final double w = weights[i];
                if (!(w > 0)) continue;
                final double th = (i + 0.5) * step;
                C += w * Math.cos(th);
                S += w * Math.sin(th);
                W += w;
            }
            if (!(W > 0)) return new VonMisesFit(Double.NaN, 0.0, 0.0, domain);
            final double rBar = Math.hypot(C, S) / W;
            double mu = Math.toDegrees(Math.atan2(S, C));
            mu = norm360(mu);
            final double kappa = kappaFromRBar(rBar);
            return new VonMisesFit(mu, kappa, rBar, domain);
        } else {
            // AXIAL: use doubled angles, then halve the mean back
            for (int i = 0; i < n; i++) {
                final double w = weights[i];
                if (!(w > 0)) continue;
                final double th2 = 2.0 * ((i + 0.5) * step);
                C += w * Math.cos(th2);
                S += w * Math.sin(th2);
                W += w;
            }
            if (!(W > 0)) return new VonMisesFit(Double.NaN, 0.0, 0.0, domain);
            final double Rbar2 = Math.hypot(C, S) / W;
            double mu = 0.5 * Math.toDegrees(Math.atan2(S, C));
            mu = norm180(mu);
            final double kappa = kappaFromRBar(Rbar2);
            return new VonMisesFit(mu, kappa, Rbar2, domain);
        }
    }

    /**
     * Fit von Mises to angle/weight pairs (angles in degrees).
     *
     * @param anglesDeg angles in degrees; domain interpreted by argument
     * @param weights   non-negative weights; null => all ones
     */
    public static VonMisesFit fitFromPairs(final double[] anglesDeg,
                                           final double[] weights,
                                           final Domain domain) {
        if (anglesDeg == null || anglesDeg.length == 0) {
            return new VonMisesFit(Double.NaN, 0.0, 0.0, domain);
        }
        final boolean axial = (domain == Domain.AXIAL);
        double C = 0.0, S = 0.0, W = 0.0;
        for (int i = 0; i < anglesDeg.length; i++) {
            final double w = (weights == null) ? 1.0 : weights[i];
            if (!(w > 0)) continue;
            double th = Math.toRadians(anglesDeg[i]);
            if (axial) th *= 2.0;
            C += w * Math.cos(th);
            S += w * Math.sin(th);
            W += w;
        }
        if (!(W > 0)) return new VonMisesFit(Double.NaN, 0.0, 0.0, domain);
        final double rBar = Math.hypot(C, S) / W;
        double mu = Math.toDegrees(Math.atan2(S, C));
        if (axial) mu *= 0.5;
        mu = axial ? norm180(mu) : norm360(mu);
        final double kappa = kappaFromRBar(rBar);
        return new VonMisesFit(mu, kappa, rBar, domain);
    }

    /**
     * Domain of the angle data. DIRECTIONAL: 0–360°, AXIAL (orientation): 0–180° with opposite directions equivalent.
     */
    public enum Domain {DIRECTIONAL, AXIAL}

    /**
     * Immutable summary of a von Mises fit on circular (directional) or axial (orientation) data.
     * <p>
     * Conventions used by SNT:
     * <ul>
     *   <li><b>Domain.DIRECTIONAL</b> operates on angles in [0,360[ degrees; opposite directions are distinct.</li>
     *   <li><b>Domain.AXIAL</b> operates on orientations in [0,180[ degrees; opposite directions are equivalent
     *        The reported {@code muDeg} is halved and normalized back to [0,180).</li>
     * </ul>
     * Values follow the usual circular–statistics definitions:
     * <ul>
     *   <li>{@code muDeg}: mean direction/orientation in degrees. Normalized to [0,360[ for
     *       {@link Domain#DIRECTIONAL} and to [0,180) for {@link Domain#AXIAL}. May be {@code NaN} when the
     *       distribution is empty (total weight = 0).</li>
     *   <li>{@code kappa}: von&nbsp;Mises concentration (unitless). Zero indicates a uniform distribution; larger values
     *       indicate stronger concentration. Estimated from the mean resultant length via
     *       {@link #kappaFromRBar(double)}.</li>
     *   <li>{@code rBar}: mean resultant length in [0,1] (also written as R̄, “Rbar”). For
     *       {@link Domain#DIRECTIONAL} this equals the Angular Distribution Coherence (ADC); for
     *       {@link Domain#AXIAL} it equals the Orientation Distribution Coherence (ODC).</li>
     *   <li>{@code domain}: identifies whether the fit was directional or axial; determines the normalization of
     *       {@code muDeg} and the interpretation of {@code rBar}.</li>
     * </ul>
     * <p>
     * Note that a single von&nbsp;Mises model is inherently unimodal;
     *
     * @param muDeg  mean direction/orientation in degrees (normalized by {@code domain}); {@code NaN} if undefined
     * @param kappa  concentration parameter (>= 0), estimated from {@code rBar}
     * @param rBar   mean resultant length in [0,1]; equals ADC (directional) or ODC (axial)
     * @param domain domain of the angles used in the fit (directional vs axial)
     * @see #fitFromHistogram(double[], double, Domain)
     * @see #fitFromPairs(double[], double[], Domain)
     * @see #kappaFromRBar(double)
     */
    public record VonMisesFit(double muDeg, double kappa, double rBar, Domain domain) {
    }
}
