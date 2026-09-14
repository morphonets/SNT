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

package sc.fiji.snt.seed;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;

/**
 * Shared helper for turning a detector's raw per-candidate scores (integrated density, local contrast, cluster density,
 * ...) into {@link SeedPoint#confidence} values in {@code [0, 1]}, or {@code NaN} when there is no basis to judge one
 * candidate against another.
 * <p>
 * Plain min-max normalization (dimmest/lowest-scoring candidate -&gt; 0, brightest/highest -&gt; 1) is fragile: a
 * single outlier candidate stretches or compresses the whole scale, often bunching every "ordinary" candidate into a
 * narrow slice of the range. {@link #percentileClipNormalize} instead maps a percentile window of the data onto a
 * matching confidence sub-range and lets anything beyond that window extrapolate past it - curbed (clamped) to
 * {@code [0, 1]} - so a few extreme candidates spread out at the ends without compressing everyone else. A clip of 0
 * recovers plain min-max normalization exactly.
 * </p>
 * <p>
 * Used by every SNT detector that assigns confidence from a raw score: {@code SomaDetectorCmd},
 * {@code AlongPathDetectorCmd}, {@code PeripathDetectorCmd}, and {@code DensityClusterer} (shared by
 * {@code TuftDetector} and {@code RootDetector}) - rather than each keeping its own copy, which is how these previously
 * drifted out of sync on edge-case behavior (e.g. what confidence a tie gets).
 * </p>
 *
 * @author Tiago Ferreira
 */
public final class SeedConfidence {

    private SeedConfidence() {
        // static utility class
    }

    /**
     * Percentile-clipped linear normalization of {@code scores} into confidence {@code [0, 1]}.
     * <p>
     * The {@code clipPercent}th and {@code (100-clipPercent)}th percentiles of the non-NaN entries
     * of {@code scores} map onto confidence {@code clipPercent/100} and {@code 1-clipPercent/100}
     * respectively; values beyond those percentiles extrapolate past that sub-range but are curbed
     * (clamped) to {@code [0, 1]} - so real outliers still spread toward the extremes without
     * compressing every other score into a narrow band, the way plain min-max normalization would.
     * {@code clipPercent = 0} exactly recovers plain min-max normalization onto {@code [0, 1]}
     * (lowest score -&gt; 0, highest -&gt; 1).
     * </p>
     * <p>
     * A {@code NaN} entry in {@code scores} (no score available for that candidate) always maps to
     * confidence {@code NaN} - "nothing to judge it by" is a distinct, honest "unknown" state,
     * never a fabricated real value. If fewer than two distinct non-NaN scores remain (empty
     * input, every score {@code NaN}, or every non-NaN score tied), every entry likewise gets
     * confidence {@code NaN} - there is nothing to normalize against. Callers that read
     * {@link SeedPoint#confidence} as a real number (range filters, LUT color sampling,
     * descending-confidence sorts) must treat {@code NaN} explicitly rather than relying on
     * incidental {@code NaN} comparison/arithmetic semantics; see {@code SeedOverlayRenderer}
     * and {@code SeedOverlay#topKByConfidence} for the established pattern.
     * </p>
     *
     * @param scores      raw per-candidate scores (higher = more confident); {@code NaN} allowed;
     *                    {@code null} or empty returns an empty array
     * @param clipPercent percentile clip width, clamped to {@code [0, 45]} (0 = plain min-max)
     * @return one confidence value per entry of {@code scores}, same order, each in {@code [0, 1]}
     * or {@code NaN}
     */
    public static double[] percentileClipNormalize(final double[] scores, final double clipPercent) {
        if (scores == null || scores.length == 0) return new double[0];
        final double clip = Math.clamp(clipPercent, 0.0, 45.0);

        int validCount = 0;
        for (final double s : scores) if (!Double.isNaN(s)) validCount++;
        final double[] valid = new double[validCount];
        int vi = 0;
        for (final double s : scores) if (!Double.isNaN(s)) valid[vi++] = s;

        double loValue = Double.NaN;
        double hiValue = Double.NaN;
        if (valid.length > 0) {
            final Percentile percentile = new Percentile();
            loValue = percentile.evaluate(valid, clip);
            hiValue = percentile.evaluate(valid, 100.0 - clip);
        }
        final double loConfidence = clip / 100.0;
        final double hiConfidence = 1.0 - loConfidence;
        final double valueRange = hiValue - loValue;

        final double[] confidences = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            final double s = scores[i];
            confidences[i] = (Double.isNaN(s) || Double.isNaN(valueRange) || valueRange <= 0)
                    ? Double.NaN
                    : Math.clamp(loConfidence + (hiConfidence - loConfidence) * (s - loValue) / valueRange,
                            0.0, 1.0);
        }
        return confidences;
    }
}
