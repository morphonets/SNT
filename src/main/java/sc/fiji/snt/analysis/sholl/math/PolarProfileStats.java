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

import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.analysis.AnalysisUtils;
import sc.fiji.snt.analysis.CircularModels;
import sc.fiji.snt.analysis.CircularModels.Domain;
import sc.fiji.snt.analysis.CircularModels.VonMisesFit;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.ShollPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * PolarProfileStats computes angle-resolved (‘Polar Sholl’) metrics from a Sholl Profile,
 * providing analysis and visualization methods for directional patterns in Sholl profiles.
 * <p>
 * This class analyzes the directional distribution of Sholl intersections/cable length
 * by dividing the 360° space around a center point into angular sectors. The angular
 * resolution is specified using step size in degrees (e.g., 10° for 36 bins).
 * </p>
 *
 * @author Tiago Ferreira
 */
public class PolarProfileStats implements ShollStats {

    private final Profile profile;
    private DataMode dataMode = DataMode.INTERSECTIONS; // Default mode
    private double angleStepSize; // in degrees
    private int nAngleBins; // calculated from angleStepSize since range is always [0-360[ degrees
    private double[][] dataMatrix; // [radialBin][angleBin]
    private boolean parsed;
    private Report reportCache; // cached summary of peaks, coherences, etc.

    /**
     * Instantiates a new PolarProfileStats from an existing Sholl Profile, with the default angular resolution (10°).
     *
     * @param profile the Sholl Profile containing radii and intersection counts.
     */
    @SuppressWarnings("unused")
    public PolarProfileStats(final Profile profile) {
        this(profile, DataMode.INTERSECTIONS, ShollAnalysisPrefsCmd.DEF_ANGLE_STEP_SIZE);
    }

    /**
     * Instantiates a new PolarProfileStats from a Profile with custom angular resolution.
     *
     * @param profile       the Sholl Profile
     * @param angleStepSize the angular step size in degrees (e.g., 10.0 for 10° bins)
     */
    public PolarProfileStats(final Profile profile, final DataMode dataMode, final double angleStepSize) {
        this.profile = profile;
        setAngleStepSize(angleStepSize);
        setDataMode(dataMode);
    }

    /**
     * Instantiates a new PolarProfileStats from a Profile with custom angular resolution.
     *
     * @param linerStats    the LinearProfileStats instance from which profile is extracted. If a polynomial fit has
     *                      been applied the fitted profile is used. The data mode used is that of LinearProfileStats
     *                      and cannot be changed.
     * @param angleStepSize the angular step size in degrees (e.g., 10.0 for 10° bins)
     */
    public PolarProfileStats(final LinearProfileStats linerStats, final double angleStepSize) {
        this.profile = linerStats.getProfileCopy();
        profile.getProperties().setProperty("fit", linerStats.getPolynomialAsString(true));
        this.dataMode = linerStats.getDataMode(); // do not use setter, as it may trigger exception
        setAngleStepSize(angleStepSize);
    }

    /**
     * Gets the angular step size in degrees.
     *
     * @return the angular step size in degrees
     */
    @SuppressWarnings("unused")
    public double getAngleStepSize() {
        return angleStepSize;
    }

    /**
     * Sets the angular step size in degrees.
     *
     * @param angleStepSize the angular step size in degrees (e.g., a step size of 10° splits the data into 36 bins)
     * @throws IllegalArgumentException if angleStepSize is not a positive divisor of 360
     */
    public void setAngleStepSize(final double angleStepSize) {
        if (angleStepSize <= 0 || angleStepSize > 360) {
            throw new IllegalArgumentException("Angle step size must be between 0 and 360 degrees");
        }
        final double nBins = 360.0 / angleStepSize;
        if (Math.abs(nBins - Math.round(nBins)) > 1e-10) {
            throw new IllegalArgumentException("Angle step size must result in an integer number of bins (360° / step size must be a whole number)");
        }
        final int newNAngleBins = (int) Math.round(360.0 / angleStepSize);
        parsed = parsed && this.nAngleBins == newNAngleBins;
        this.angleStepSize = angleStepSize;
        this.nAngleBins = newNAngleBins;
        reportCache = null; // angle binning changed -> invalidate cached report
    }

    @Override
    public DataMode getDataMode() {
        return dataMode;
    }

    @Override
    public void setDataMode(final DataMode mode) {
        parsed = parsed && this.dataMode == mode;
        if (!(profile.getProperties().getProperty("fit", "").isEmpty())) {
            throw new IllegalArgumentException("DataMode cannot be changed when handling fitted profiles");
        }
        this.dataMode = mode;
        reportCache = null; // data source changed -> invalidate cached report
    }

    // Note: Entries without angular locations (entry.points empty) are skipped by default to avoid
    // fabricating uniform rings (esp. for fitted profiles). Set profile.props["polar.allowUniformFallback"]=true
    // to enable uniform distribution: In the absence of points data is spread evenly across the 360° range
    private void computeMatrix() {
        if (profile.center() == null || profile.isEmpty()) {
            throw new IllegalStateException("Center of profile unknown or profile is empty");
        }

        // Do NOT fabricate angles when no intersection points are available for a certain profile entry. This prevents
        // spurious uniform outer rings (common with fitted profiles whose trailing entries have positive counts but no
        // angular locations). Only distribute if explicitly allowed via property flag
        final boolean allowUniformFallback = Boolean.parseBoolean(
                profile.getProperties().getProperty("polar.allowUniformFallback", "false"));

        // we can only proceed if intersection points are known
        final boolean hasIntersectionPoints =
                profile.entries().stream().anyMatch(entry -> entry.points != null && !entry.points.isEmpty());
        if (!hasIntersectionPoints && !allowUniformFallback) {
            throw new IllegalArgumentException("Polar analysis requires location of Sholl intersections to be known. " +
                    "Ensure the parser was used to generate the Profile with intersection points.");
        }

        // Initialize intersection and length matrices
        dataMatrix = new double[profile.size()][nAngleBins];
        final double stepRadius = profile.stepSize();
        final int nRadialBins = profile.size();
        final double startRadius = profile.startRadius();

        for (final ProfileEntry entry : profile.entries()) {

            final double radius = entry.radius;

            // Bin by ring interval [startRadius + r*step, startRadius + (r+1)*step)
            final double idx = (radius - startRadius) / stepRadius;
            final int radialBin = (int) Math.floor(idx + 1e-12); // numeric guard
            if (radialBin < 0 || radialBin >= nRadialBins) continue;

            if (entry.points != null && !entry.points.isEmpty()) {
                // Bin points by angle, then distribute counts and length proportionally
                final int[] binCounts = new int[nAngleBins];
                for (final ShollPoint point : entry.points) {
                    final Path path = new Path();
                    path.addNode(profile.center());
                    path.addNode(point);
                    double theta = path.getExtensionAngle3D(false); // degrees in [0,360[
                    int angleBin = (int) Math.floor(theta / angleStepSize);
                    if (angleBin < 0) angleBin = 0;
                    if (angleBin >= nAngleBins) angleBin = nAngleBins - 1;
                    binCounts[angleBin]++;
                }
                final int components = entry.points.size();
                final double countScale = (components > 0) ? (entry.count / (double) components) : 0d;
                final double lengthPerComponent = (components > 0) ? (entry.length / (double) components) : 0d;
                for (int angleBin = 0; angleBin < nAngleBins; angleBin++) {
                    final int c = binCounts[angleBin];
                    if (c == 0) continue;
                    switch (dataMode) {
                        case INTERSECTIONS -> dataMatrix[radialBin][angleBin] += countScale * c;
                        case LENGTH -> dataMatrix[radialBin][angleBin] += lengthPerComponent * c;
                        default -> throw new IllegalArgumentException("Unrecognized dataMode");
                    }
                }

            } else if (allowUniformFallback) {
                // opt-in behavior: uniform fallback across angle bins
                switch (dataMode) {
                    case INTERSECTIONS -> {
                        final double countPerBin = entry.count / nAngleBins;
                        if (countPerBin == 0d) continue;
                        for (int angleBin = 0; angleBin < nAngleBins; angleBin++)
                            dataMatrix[radialBin][angleBin] += countPerBin;
                    }
                    case LENGTH -> {
                        final double lengthPerBin = entry.length / nAngleBins;
                        if (lengthPerBin == 0d) continue;
                        for (int angleBin = 0; angleBin < nAngleBins; angleBin++)
                            dataMatrix[radialBin][angleBin] += lengthPerBin;
                    }
                    default -> throw new IllegalArgumentException("Unrecognized dataMode");
                }
            }
        }
        parsed = true;
        reportCache = null; // matrices rebuilt -> recompute report lazily
    }

    /**
     * Returns the length matrix with shape {@code [nRadialBins][nAngleBins]}.
     * When data mode is <em>intersections</em>: Each entry stores the number of intersected components
     * assigned to the ring-sector bin at the given radial and angular index.
     * When data mode is <em>length</em>: Each entry stores the total cable length (in calibrated physical units)
     * assigned to the ring-sector bin at the given radial and angular index.
     *
     * <p><b>Indexing:</b> row = radial bin (inner to outer), column = angular bin
     * (0..nAngleBins-1). Angular bins partition [0°, 360°[ using an equal step
     * size given by {@link #getAngleStepSize()} and are referenced by their
     * bin center angle when aggregating directions.</p>
     *
     * @return a non-null 2D array where {@code C[r][a]} is the count in bin (r,a),
     * or {@code L[r][a]} is the length in bin (r,a),
     */
    public double[][] getMatrix() {
        if (!parsed) computeMatrix();
        return dataMatrix;
    }

    /**
     * Returns the angular distribution obtained by summing the intersections matrix or the
     * length matrix across all radial bins.
     *
     * <p><b>Units:</b> For intersections: counts per angular bin (dimensionless). For length:
     * calibrated length per angular bin (e.g., micrometers).</p>
     * <p><b>Length:</b> {@code nAngleBins}. Entry {@code k} corresponds to the
     * angular bin centered at {@code (k + 0.5) * getAngleStepSize()}.</p>
     *
     * @return a non-null array {@code w} of size {@code nAngleBins}, where
     * {@code w[k]} is the total number of components in angular bin {@code k}.
     */
    public double[] getAngularDistribution() {
        final double[][] matrix = getMatrix();
        final double[] angularDist = new double[nAngleBins];
        for (final double[] row : matrix) {
            for (int a = 0; a < nAngleBins; a++) angularDist[a] += row[a];
        }
        return angularDist;
    }

    /**
     * Returns the angular distribution obtained by summing only those radial bins whose
     * ring centers fall within the half-open interval {@code [rMin, rMax[}.
     *
     * <p>Units match {@link #getAngularDistribution()} (counts or calibrated length). The
     * selection uses the center of each ring at {@code startRadius + (r+0.5)*step}.
     * This keeps binning stable and avoids partial-ring weighting.</p>
     */
    public double[] getAngularDistribution(final double rMin, final double rMax) {
        final double[][] matrix = getMatrix();
        final double[] angularDist = new double[nAngleBins];
        final double step = profile.stepSize();
        final double start = profile.startRadius();
        for (int r = 0; r < matrix.length; r++) {
            final double center = start + (r + 0.5) * step;
            if (center < rMin || center >= rMax) continue;
            for (int a = 0; a < nAngleBins; a++) angularDist[a] += matrix[r][a];
        }
        return angularDist;
    }

    /**
     * Convenience: preferred direction computed from a radial band {@code [rMin,rMax)}.
     */
    public double getPreferredDirection(final double rMin, final double rMax) {
        return preferredAngle(getAngularDistribution(rMin, rMax), false);
    }

    /**
     * Convenience: preferred orientation computed from a radial band {@code [rMin,rMax)}.
     */
    public double getPreferredOrientation(final double rMin, final double rMax) {
        return preferredAngle(getAngularDistribution(rMin, rMax), true);
    }

    /**
     * Finds up to {@code maxPeaks} local maxima in the direction (0–360°) distribution within a Sholl band.
     * Peaks are detected after a small circular moving-average smoothing (window=3). Candidates must exceed
     * {@code minProminence} above the mean of their two neighbors and are greedily selected with a minimum
     * separation of {@code 2× angleStepSize}.
     *
     * @param rMin          the smallest Sholl radius to be considered
     * @param rMax          the largest Sholl radius to be considered
     * @param maxPeaks      the number of peaks to be retrieved
     * @param minProminence Minimum required local prominence measured on the smoothed angular distribution s[k]
     *                      (3-point circular moving average). Prominence is defined as s[k] - 0.5*(s[k-1] + s[k+1]) and
     *                      has the same units as the underlying distribution: Intersections mode: counts per angular
     *                      bin (dimensionless count); Length mode: calibrated length per angular bin (e.g., µm)
     * @see #findDirectionPeaks(double, double, int, double)
     */
    public List<PolarPeak> findDirectionPeaks(final double rMin, final double rMax,
                                              final int maxPeaks,
                                              final double minProminence) {
        final double[] dist = getAngularDistribution(rMin, rMax);
        double sum = 0;
        for (double v : dist) sum += v;
        if (sum <= 0) return Collections.emptyList();
        final double minProm = (minProminence == -1) ? autoProminenceThreshold(dist) : minProminence;
        final double minSep = 2.0 * angleStepSize;
        return findPeaksCircular(dist, maxPeaks, minSep, minProm).direction();
    }

    /**
     * Finds up to {@code maxPeaks} local maxima in the orientation (0–180°) distribution within a Sholl band.
     * Peaks are detected after a small circular moving-average smoothing (window=3). Candidates must exceed
     * {@code minProminence} above the mean of their two neighbors and are greedily selected with a minimum
     * separation of {@code 2× angleStepSize}.
     *
     * @param rMin          the smallest Sholl radius to be considered
     * @param rMax          the largest Sholl radius to be considered
     * @param maxPeaks      the number of peaks to be retrieved
     * @param minProminence Minimum required local prominence measured on the smoothed angular distribution s[k]
     *                      (3-point circular moving average). Prominence is defined as s[k] - 0.5*(s[k-1] + s[k+1]) and
     *                      has the same units as the underlying distribution: Intersections mode: counts per angular
     *                      bin (dimensionless count); Length mode: calibrated length per angular bin (e.g., µm)
     * @see #findDirectionPeaks(double, double, int, double)
     */
    public List<PolarPeak> findOrientationPeaks(final double rMin, final double rMax,
                                                final int maxPeaks,
                                                final double minProminence) {
        final double[] dist = getAngularDistribution(rMin, rMax);
        double sum = 0;
        for (double v : dist) sum += v;
        if (sum <= 0) return Collections.emptyList();
        final double minProm = (minProminence == -1) ? autoProminenceThreshold(dist) : minProminence;
        final double minSep = 2.0 * angleStepSize;
        return findPeaksCircular(dist, maxPeaks, minSep, minProm).orientation();
    }

    /**
     * Automatically finds directional peaks (0–360°) over a Sholl radial band by deriving a data-driven
     * prominence threshold (median + 1.5×MAD (Median Absolute Deviation), of local prominences after light smoothing)
     * and applying a minimum separation of two angular bins. The number of returned peaks is the predicted number of
     * meaningful directions in the band.
     *
     * @param rMin the smallest Sholl radius to be considered
     * @param rMax the largest Sholl radius to be considered
     */
    public List<PolarPeak> findDirectionPeaks(final double rMin, final double rMax) {
        return findDirectionPeaks(rMin, rMax, Integer.MAX_VALUE, -1);
    }

    /**
     * Automatically finds orientation (0–180°) peaks over a radial band using the same settings used by
     * {@link #findDirectionPeaks(double, double)}.
     *
     * @param rMin the smallest Sholl radius to be considered
     * @param rMax the largest Sholl radius to be considered
     */
    public List<PolarPeak> findOrientationPeaks(final double rMin, final double rMax) {
        return findOrientationPeaks(rMin, rMax, Integer.MAX_VALUE, -1);
    }

    /**
     * Returns a cached summary report of the analysis. Recomputed lazily whenever the
     * underlying matrices change (on changing angle step, data mode, etc.).
     */
    public Report getReport() {
        if (!parsed) computeMatrix();
        if (reportCache == null) reportCache = computeReport();
        return reportCache;
    }

    /**
     * Writes {@link Report} details to a table.
     *
     * @param table    destination table (non-null)
     */
    public void detailReport(final SNTTable table) {
        //if (table == null) return;
        final Report rep = getReport();
        table.addColumn("Angular Bin-Center (°)", getBinCenters());
        table.addColumn("Angular Value", getAngularDistribution());
        final List<List<Double>> dirPeaksCols = peaksToAngleValueColumns(rep.directionPeaks());
        table.addColumn("Dir. Peak (°)", dirPeaksCols.getFirst());
        table.addColumn("Dir. Peak Value", dirPeaksCols.getLast());
        final List<List<Double>> oriPeaksCols = peaksToAngleValueColumns(rep.orientationPeaks);
        table.addColumn("Ori. Peak (°)", oriPeaksCols.getFirst());
        table.addColumn("Ori. Peak Value", oriPeaksCols.getLast());
    }

    /**
     * Appends summary data of {@link Report} to the last row of the specified table
     * Columns written:
     * <ul>
     *   <li>Angular Distribution Coherence [0,1]</li>
     *   <li>Orientation Distribution Coherence [0,1]</li>
     *   <li>Preferred Direction in degrees [0,360[</li>
     *   <li>Preferred Orientation in degrees [0,180[</li>
     *   <li>Number of directional peaks</li>
     *   <li>Angle, Value of first two directional peaks</li>
     *   <li>Number of orientation peaks</li>
     *   <li>Angle, Value of first two orientation peaks</li>
     * </ul>
     *
     * @param table the SNTTable to append to (must be non-null)
     */
    public void appendSummaryReport(final SNTTable table) {
        if (table == null) return;
        final Report rep = getReport();
        table.appendToLastRow("ADC", rep.adc());
        table.appendToLastRow("ODC", rep.odc());
        table.appendToLastRow("PD (°)", rep.pd());
        table.appendToLastRow("PO (°)", rep.po());
        // Peaks: directional
        final List<PolarPeak> dpk = rep.directionPeaks();
        table.appendToLastRow("No. Dir.Peaks", dpk.size());
        for (int i = 0; i < Math.min(dpk.size(), 2); i++) {
            final PolarPeak p = dpk.get(i);
            table.appendToLastRow(String.format("Dir.Peak%d Angle (°)", i+1), p.angleDeg());
            table.appendToLastRow(String.format("Dir.Peak%d Value", i+1), p.value());
        }
        // Peaks: orientation
        final List<PolarPeak> opk = rep.orientationPeaks();
        table.appendToLastRow("No. Ori.Peaks", opk.size());
        for (int i = 0; i < Math.min(opk.size(), 2); i++) {
            final PolarPeak p = opk.get(i);
            table.appendToLastRow(String.format("Ori.Peak%2d Angle (°)", i+1), p.angleDeg());
            table.appendToLastRow(String.format("Ori.Peak%2d Value", i+1), p.value());
        }
    }

    /*
     * Converts a list of {@link PolarPeak} into two parallel columns for tabular export.
     */
    private static List<List<Double>> peaksToAngleValueColumns(final List<PolarPeak> peaks) {
        final List<Double> angles = new ArrayList<>();
        final List<Double> values = new ArrayList<>();
        for (final PolarPeak p : peaks) {
            angles.add(p.angleDeg());
            values.add(p.value());
        }
        return List.of(angles, values);
    }

    /**
     * Returns the list of angular bin centers (degrees), sorted ascending in [0, 360).
     * The i-th center is (i + 0.5) * angleStepSize. Useful for exporting a column
     * that aligns with values returned by {@link #getAngularDistribution()}.
     *
     * @return an array of length nAngleBins with bin-center angles in degrees.
     */
    private double[] getBinCenters() {
        final double[] bins = new double[nAngleBins];
        final double step = angleStepSize; // typically 360.0 / nAngleBins
        for (int i = 0; i < nAngleBins; i++) {
            double ang = (i + 0.5) * step; // center of the sector
            // Normalize defensively to [0, 360[ to avoid 360.0000000 from FP round-off
            ang = ang % 360.0;
            if (ang < 0) ang += 360.0;
            bins[i] = ang;
        }
        return bins; // already ascending
    }

    /**
     * Recomputes and returns a summary report for the current polar distribution over the
     * full radial range. The report includes: number of directional peaks, the list of peaks
     * (angles and heights), and the ADC/ODC coherences.
     */
    private Report computeReport() {
        final double[] dist = getAngularDistribution();
        // First and second circular moments from the same distribution
        final double[] m1 = getSxSySum(dist, false);
        final double[] m2 = getSxSySum(dist, true);
        final double sum = m1[2];
        final double adc = (sum > 0) ? Math.hypot(m1[0], m1[1]) / sum : 0.0;
        final double odc = (sum > 0) ? Math.hypot(m2[0], m2[1]) / sum : 0.0;
        final DualPeaks dualPeaks = findPeaksCircular();
        // Use unmodifiable defensive copies to protect cache integrity
        final List<PolarPeak> dirPeaks = List.copyOf(dualPeaks.direction());
        final List<PolarPeak> oriPeaks = List.copyOf(dualPeaks.orientation());

        final double pd = getPreferredDirection(dirPeaks);
        final double po = getPreferredOrientation(oriPeaks);

        // Allow unimodality when either (i) exactly one robust peak, or (ii) strong coherence with a dominant peak,
        // or (iii) very strong coherence even if the peak picker is overly conservative (zero peaks).
        final double DOM_RATIO_MIN = 1.5;    // top peak must be ≥1.5× the second peak
        final double ADC_STRONG    = 0.80;   // strong directional coherence
        final double ODC_STRONG    = 0.80;   // strong axial coherence
        final double ADC_VERY_STRONG = 0.85; // coherence high enough to accept unimodality even if no peaks returned

        final double dirDom = dominanceRatio(dirPeaks);
        final double oriDom = dominanceRatio(oriPeaks);

        final boolean dirUnimodal = (dirPeaks.size() == 1)
                || (adc >= ADC_STRONG && dirDom >= DOM_RATIO_MIN)
                || (dirPeaks.isEmpty() && adc >= ADC_VERY_STRONG);
        final boolean oriUnimodal = (oriPeaks.size() == 1)
                || (odc >= ODC_STRONG && oriDom >= DOM_RATIO_MIN)
                || (oriPeaks.isEmpty() && odc >= ADC_VERY_STRONG);

        VonMisesFit vmDir = null;
        VonMisesFit vmAx  = null;
        if (dirUnimodal) {
            vmDir = CircularModels.fitFromHistogram(dist, angleStepSize, Domain.DIRECTIONAL);
        }
        if (oriUnimodal) {
            vmAx = CircularModels.fitFromHistogram(dist, angleStepSize, Domain.AXIAL);
        }
        return new Report(dirPeaks, oriPeaks, adc, odc, pd, po, vmDir, vmAx);
    }

    // computes the dominance ratio between the top two peaks (sorted in descending height)
    // returns positive infinity when there is a single peak and NaN when there are no peaks
    private static double dominanceRatio(final List<PolarPeak> peaks) {
        if (peaks == null || peaks.isEmpty()) return Double.NaN;
        if (peaks.size() == 1) return Double.POSITIVE_INFINITY;
        final double a = peaks.get(0).value();
        final double b = peaks.get(1).value();
        if (!(b > 0)) return Double.POSITIVE_INFINITY;
        return a / b;
    }

    private double getPreferredDirection(final List<PolarPeak> directionPeaks) {
        if (!directionPeaks.isEmpty()) return directionPeaks.getFirst().angleDeg;
        return preferredAngle(getAngularDistribution(), false); // fallback to mean
    }

    private double getPreferredOrientation(final List<PolarPeak> orientationPeaks) {
        if (!orientationPeaks.isEmpty()) return orientationPeaks.getFirst().angleDeg;
        return preferredAngle(getAngularDistribution(), true); // fallback to mean
    }

    private DualPeaks findPeaksCircular() {
        final double[] dist = getAngularDistribution(profile.startRadius(), profile.endRadius());
        return findPeaksCircular(dist, Integer.MAX_VALUE, 2.0 * angleStepSize, autoProminenceThreshold(dist));
    }

    private DualPeaks findPeaksCircular(final double[] y,
                                        final int maxPeaks,
                                        final double minSeparationDeg,
                                        final double minProminence) {
        final int n = (y == null) ? 0 : y.length;
        if (n == 0) return new DualPeaks(Collections.emptyList(), Collections.emptyList());

        final double stepDeg = angleStepSize;

        // Smooth once
        final double[] s = smoothCircular3(y);

        // Directional candidates on s
        final List<PolarPeak> dirCand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final double prev = s[(i - 1 + n) % n];
            final double cur = s[i];
            final double next = s[(i + 1) % n];
            if (cur > prev && cur >= next) {
                final double prom = cur - 0.5 * (prev + next);
                if (prom >= minProminence) {
                    double ang = (i + 0.5) * stepDeg;
                    ang = (ang % 360 + 360) % 360;
                    dirCand.add(new PolarPeak(ang, cur, i));
                }
            }
        }
        final List<PolarPeak> dir = nonMaximumSuppression(dirCand, maxPeaks, minSeparationDeg, false);

        // Orientation candidates: fold s when bins are even, otherwise use s directly with 2θ mapping
        List<PolarPeak> ori;
        if (nAngleBins % 2 == 0) {
            final int half = nAngleBins / 2;
            final double[] fold = new double[half];
            for (int a = 0; a < half; a++) fold[a] = s[a] + s[a + half];
            final List<PolarPeak> oriCand = new ArrayList<>();
            for (int i = 0; i < half; i++) {
                final double prev = fold[(i - 1 + half) % half];
                final double cur = fold[i];
                final double next = fold[(i + 1) % half];
                if (cur > prev && cur >= next) {
                    final double prom = cur - 0.5 * (prev + next);
                    if (prom >= minProminence) {
                        double ang = (i + 0.5) * stepDeg;     // stepDeg == 360/N == 180/(N/2)
                        ang = (ang % 180 + 180) % 180;
                        oriCand.add(new PolarPeak(ang, cur, i));
                    }
                }
            }
            ori = nonMaximumSuppression(oriCand, maxPeaks, minSeparationDeg, true);
        } else {
            final List<PolarPeak> oriCand = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final double prev = s[(i - 1 + n) % n];
                final double cur = s[i];
                final double next = s[(i + 1) % n];
                if (cur > prev && cur >= next) {
                    final double prom = cur - 0.5 * (prev + next);
                    if (prom >= minProminence) {
                        double ang = (i + 0.5) * stepDeg;
                        ang = (ang % 180 + 180) % 180;        // map to orientation domain
                        oriCand.add(new PolarPeak(ang, cur, i));
                    }
                }
            }
            ori = nonMaximumSuppression(oriCand, maxPeaks, minSeparationDeg, true);
        }
        return new DualPeaks(dir, ori);
    }

    // Greedy non-maximum suppression on circular angles
    private List<PolarPeak> nonMaximumSuppression(final List<PolarPeak> candidates,
                                                  final int maxPeaks,
                                                  final double minSeparationDeg,
                                                  final boolean orientationMode) {
        if (candidates.isEmpty()) return Collections.emptyList();
        candidates.sort((a, b) -> Double.compare(b.value, a.value));
        final List<PolarPeak> out = new ArrayList<>();
        final double wrap = orientationMode ? 180.0 : 360.0;
        final double sep = Math.max(0.0, minSeparationDeg);
        final int kMax = Math.max(1, maxPeaks);
        for (final PolarPeak p : candidates) {
            boolean ok = true;
            for (PolarPeak q : out) {
                double d = Math.abs(p.angleDeg - q.angleDeg);
                if (d > wrap / 2) d = wrap - d;
                if (d < sep) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(p);
                if (out.size() >= kMax) break;
            }
        }
        return out;
    }

    /**
     * Convenience getter for {@link Report#adc() }
     *
     * @return direction distribution coherence ([0-1], unitless)
     */
    public double getAngularDistributionCoherence() {
        return getReport().adc();
    }

    /**
     * Convenience getter for {@link Report#odc() }
     *
     * @return orientation distribution coherence ([0-1], unitless)
     */
    @SuppressWarnings("unused")
    public double getOrientationDistributionCoherence() {
        return getReport().odc();
    }

    /**
     * Convenience getter for {@link Report#pd() }
     *
     * @return preferred direction in degrees, normalized to [0, 360[ degrees
     */
    public double getPreferredDirection() {
        return getReport().pd();
    }

    /**
     * Convenience getter for {@link Report#po() }
     *
     * @return preferred orientation in degrees, normalized to [0, 180[ degrees
     */
    public double getPreferredOrientation() {
        return getReport().po();
    }

    /**
     * Returns either the preferred direction (directional mean; ADC) or the preferred orientation (axis; ODC) of the
     * supplied angular distribution, depending on {@code orientationMode}.
     *
     * @param angularDist     weights per angular bin (length of array = nAngleBins)
     * @param orientationMode when {@code true}, computes preferred orientation using the second circular moment (2θ)
     *                        and halves the result; when {@code false}, computes preferred direction using the first
     *                        moment (θ).
     * @return preferred angle in degrees  or {@code Double.NaN} if the distribution has zero total weight
     */
    private double preferredAngle(final double[] angularDist, final boolean orientationMode) {
        if (angularDist == null || angularDist.length != nAngleBins) return Double.NaN;
        final double[] sxSySum = getSxSySum(angularDist, orientationMode);
        final double sx = sxSySum[0];
        final double sy = sxSySum[1];
        final double sum = sxSySum[2];
        if (sum <= 0) return Double.NaN;
        double angDeg = Math.toDegrees(Math.atan2(sy, sx));
        if (orientationMode) {
            angDeg *= 0.5;                 // undo the 2θ doubling
            angDeg = (angDeg % 180 + 180) % 180; // [0, 180[
        } else {
            angDeg = (angDeg % 360 + 360) % 360; // [0, 360[
        }
        return angDeg;
    }

    private double[] getSxSySum(final double[] angularDist, final boolean orientationMode) {
        double sx = 0, sy = 0, sum = 0;
        final double thetaRadMultiplier = (orientationMode) ? 2d : 1d;
        for (int a = 0; a < nAngleBins; a++) {
            final double thetaDeg = (a + 0.5) * angleStepSize; // angleStepSize in degrees
            final double thetaRad = Math.toRadians(thetaDeg) * thetaRadMultiplier;
            final double weight = angularDist[a];
            sx += weight * Math.cos(thetaRad);
            sy += weight * Math.sin(thetaRad);
            sum += weight;
        }
        return new double[]{sx, sy, sum};
    }

    /**
     * Circular moving average smoothing with window size 3 (neighbors + self).
     */
    private double[] smoothCircular3(final double[] y) {
        final int n = (y == null) ? 0 : y.length;
        if (n == 0) return new double[0];
        final double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            final double y0 = y[(i - 1 + n) % n];
            final double y1 = y[i];
            final double y2 = y[(i + 1) % n];
            s[i] = (y0 + y1 + y2) / 3.0;
        }
        return s;
    }

    /**
     * Data-driven prominence threshold based on median and MAD of local prominences.
     */
    private double autoProminenceThreshold(final double[] y) {
        final double[] s = smoothCircular3(y);
        final int n = s.length;
        if (n == 0) return 0.0;
        final double[] prom = new double[n];
        double maxProm = 0.0;
        for (int i = 0; i < n; i++) {
            final double prev = s[(i - 1 + n) % n];
            final double next = s[(i + 1) % n];
            final double val = s[i];
            final double p = val - 0.5 * (prev + next);
            prom[i] = p;
            if (p > maxProm) maxProm = p;
        }
        int m = 0;
        for (double v : prom) if (v > 0) m++;
        if (m == 0) return 0.0;
        final double[] pos = new double[m];
        int j = 0;
        for (double v : prom) if (v > 0) pos[j++] = v;
        final double med = median(pos);
        final double mad = medianAbsoluteDeviation(pos, med);

        // Relaxed robust threshold
        double thr = Math.max(0.0, med + 1.5 * mad);

        // Safety caps: never exceed half of the maximum positive prominence;
        // if very few positives, relax further to avoid missing obvious peaks.
        if (maxProm > 0) thr = Math.min(thr, 0.5 * maxProm);
        if (m < 4) thr = 0.1 * maxProm;

        return thr;
    }

    private double median(final double[] a) {
        if (a == null || a.length == 0) return Double.NaN;
        final double[] c = Arrays.copyOf(a, a.length);
        Arrays.sort(c);
        final int n = c.length;
        return (n % 2 == 1) ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    private double medianAbsoluteDeviation(final double[] a, final double med) {
        if (a == null || a.length == 0 || Double.isNaN(med)) return Double.NaN;
        final double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - med);
        return median(d);
    }

    /**
     * @return The polar plot using default settings
     */
    public SNTChart getPlot() {
        return getPlot(ColorMaps.ICE);
    }

    /**
     * Returns the polar plot using the specified colormap.
     *
     * @param colormap the color name (e.g., "fire", "viridis", etc.)
     * @return The polar plot using default settings
     * @see ColorMaps#get(String)
     */
    public SNTChart getPlot(final String colormap) {
        return getPlot(ColorMaps.get(colormap));
    }

    /**
     * Returns the polar plot using the specified colormap.
     *
     * @param colorTable the colorTable. Ignored if null
     * @return The polar plot
     * @see net.imagej.display.ColorTables
     */
    public SNTChart getPlot(final ColorTable colorTable) {
        return createPolarHeatmapFromMatrix((colorTable == null) ? ColorMaps.ICE : colorTable, getMatrix());
    }

    private SNTChart createPolarHeatmapFromMatrix(final ColorTable colorTable, final double[][] matrix) {

        final List<double[][]> seriesData = new ArrayList<>();
        final List<java.awt.Color> seriesColors = new ArrayList<>();

        final double angleStep = angleStepSize;
        final double stepRadius = profile.stepSize();
        final double startRadius = profile.startRadius();
        final int effRows = effectiveRadialRows(matrix); // Only draw up to the last radial row that has any signal
        final double eps = 1e-12; // treat tiny numerical residue as zero for all modes
        // Compute color range only over effective rows
        final double[] minMax = getMinMax(matrix, effRows);

        // Create filled polar segments for each bin
        for (int r = 0; r < effRows; r++) {
            for (int a = 0; a < nAngleBins; a++) {
                final double value = matrix[r][a];
                // Skip empty/near-empty bins to avoid spurious wedges
                if (!(value > eps)) continue;

                final double innerRadius = startRadius + r * stepRadius;
                final double outerRadius = startRadius + (r + 1) * stepRadius;
                final double startAngle = a * angleStep;
                final double endAngle = (a + 1) * angleStep;

                // Create filled sector by defining a closed polygon
                // Start from inner radius, go to outer radius, then back to close the shape
                final double[][] series = new double[][]{
                        new double[]{startAngle, endAngle, endAngle, startAngle, startAngle},
                        new double[]{innerRadius, innerRadius, outerRadius, outerRadius, innerRadius}
                };
                seriesData.add(series);
                seriesColors.add(getHeatmapColor(value, minMax[0], minMax[1], colorTable));

            }
        }
        final SNTChart plot = AnalysisUtils.polarPlot(String.format("Sholl Profile (Polar) for %s",
                profile.getProperties().getProperty(Profile.KEY_ID)), seriesData, seriesColors);
        plot.addColorBarLegend(colorBarLegendLabel(), colorTable, minMax[0], minMax[1],
                (getDataMode() == DataMode.INTERSECTIONS) ? 0 : 1);
        plot.annotate(getReport().toString());
        plot.annotate(ShollPlot.defaultXtitle(getProfile()));
        return plot;
    }

    private String dataModeLabel(final boolean abbreviated) {
        if (getDataMode() == DataMode.INTERSECTIONS)
            return (abbreviated) ? "No. Intersections" : "No. Inters.";
        return (profile.scaled()) ? "Length (" + profile.spatialCalibration().getUnit() + ")" : "Length";
    }

    private String colorBarLegendLabel() {
        final String s2 = profile.getProperties().getProperty("fit", "");
        final String s1 = dataModeLabel(!s2.isEmpty());
        return (s2.isEmpty()) ? s1 : String.format("%s, %s", s1, s2);
    }

    /**
     * Returns number of radial rows up to and including the last row with any signal (> eps).
     */
    private int effectiveRadialRows(final double[][] matrix) {
        final int rows = Math.min(matrix.length, profile.size());
        final double eps = 1e-12;
        for (int r = rows - 1; r >= 0; r--) {
            for (int a = 0; a < nAngleBins; a++) {
                final double v = matrix[r][a];
                if (v > eps) return r + 1;
            }
        }
        return 0; // all rows empty
    }

    /**
     * Min/max over the first {@code rows} radial rows.
     */
    private double[] getMinMax(final double[][] matrix, final int rows) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < rows; r++) {
            for (int a = 0; a < nAngleBins; a++) {
                final double v = matrix[r][a];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        // The typical usage for min is colorbar Workaround any edge case: addColorBarLegend() requires min > max
        if (rows == 0)
            return new double[]{0.0, 1.0}; // safe legend range for empty plots
        if (min == max) {
            min -= 0.001d;
            max += 0.001d;
        }
        return new double[]{min, max};
    }

    private java.awt.Color getHeatmapColor(final double value, final double minValue, final double maxValue, final ColorTable colorTable) {
        final double denom = Math.max(1e-12, (maxValue - minValue));
        double norm = (value - minValue) / denom;
        if (Double.isNaN(norm)) norm = 0;
        norm = Math.max(0, Math.min(1, norm));
        final int colorIndex = (int) Math.round(norm * (colorTable.getLength() - 1));
        final int r = colorTable.get(ColorTable.RED, colorIndex);
        final int g = colorTable.get(ColorTable.GREEN, colorIndex);
        final int b = colorTable.get(ColorTable.BLUE, colorIndex);
        return new java.awt.Color(r, g, b);
    }

    // ShollStats implementation
    @Override
    public double[] getXValues() {
        final double[] angles = new double[nAngleBins];
        for (int i = 0; i < nAngleBins; i++) {
            angles[i] = (i + 0.5) * angleStepSize;
        }
        return angles; // angular bin centers in degrees
    }

    @Override
    public double[] getYValues() {
        return getAngularDistribution();
    }

    @Override
    public double[] getYValues(final boolean asCumulativeFrequencies) {
        return (asCumulativeFrequencies) ? null : getYValues(); // cumulative freq. not applicable for polar data
    }

    @Override
    public double[] getFitYValues() {
        return null; // No extra fitting for polar data
    }

    @Override
    public double[] getFitYValues(final boolean asCumulativeFrequencies) {
        return null; // No extra fitting for polar data
    }

    @Override
    public boolean validFit() {
        return false; // No extra fitting for polar data
    }

    @Override
    public int getN() {
        return nAngleBins;
    }

    @Override
    public Profile getProfile() {
        return profile;
    }

    // Holder for both sets of peaks computed from a single smoothing pass
    private record DualPeaks(List<PolarPeak> direction, List<PolarPeak> orientation) {
    }

    /**
     * Simple container for angular peaks.
     *
     * @param angleDeg bin-center angle (Direction: [0,360[, Orientation: [0,180[)
     * @param value    the peak height in distribution units (counts or length)
     * @param bin      index of the peak bin in the distribution
     */
    public record PolarPeak(double angleDeg, double value, int bin) {
        @Override
        public String toString() {
            return String.format("%.1f° (%.3f)", angleDeg, value);
        }
    }

    /**
     * Immutable summary of the global polar distribution.
     *
     * @param directionPeaks   unmodifiable list of directional peaks ([0,360[ degrees), sorted by descending height
     * @param orientationPeaks unmodifiable list of orientation peaks ([0,180[ degrees), sorted by descending height
     * @param adc              Angular Distribution Coherence (ADC) in [0,1]. Higher values indicate greater
     *                         concentration of weight around a single direction; 0 indicates a uniform distribution.
     * @param odc              Orientation Distribution Coherence in [0,1]. This is a variant of ADC that is insensitive
     *                         to arrow direction. ranging from [0, 1]. High values indicate mass concentrated along a
     *                         line; low values indicate near-uniform angular. distribution. NB: ODC can capture
     *                         morphologies that are symmetric across 180° (two opposite lobes) for which ADC can be
     *                         near 0.
     * @param pd               Preferred direction angle in [0,360[ degrees
     * @param po               Preferred orientation angle in [0,180[ degrees
     * @param vmDirectional    Optional von Mises fit for the directional distribution (non-null only if unimodal)
     * @param vmAxial          Optional von Mises fit for the axial/orientation distribution (non-null only if unimodal)
     */
    public record Report(List<PolarPeak> directionPeaks,
                         List<PolarPeak> orientationPeaks,
                         double adc, double odc,
                         double pd, double po,
                         VonMisesFit vmDirectional,
                         VonMisesFit vmAxial) {

        /**
         * Creates an adaptive summary tailored to common regimes:
         * <ul>
         *   <li><b>No preferred directions</b> when both ADC and ODC are very low or no peaks are found.</li>
         *   <li><b>Single preferred direction</b> when ADC is strong and exactly one robust peak is detected.</li>
         *   <li><b>Preferred orientation (axis)</b> when ODC is strong but ADC is weak.</li>
         *   <li><b>Multiple preferred directions</b> listing up to three top angles otherwise.</li>
         * </ul>
         */
        @Override
        public String toString() {

            // Heuristics
            final double ADC_LOW = 0.15;  // near-uniform directionality
            final double ADC_STRONG = 0.45;  // clear unimodal directionality
            final double ODC_LOW = 0.15;
            final double ODC_STRONG = 0.45;  // clear axis/bilobed pattern

            // If unimodal fits are available, render compact von Mises summaries
            final String vmDirStr = (vmDirectional != null)
                    ? String.format("; VM: μ=%.1f°, κ=%.2f", vmDirectional.muDeg(), vmDirectional.kappa())
                    : "";
            final String vmAxStr = (vmAxial != null)
                    ? String.format("; VM: μ=%.1f°, κ=%.2f", vmAxial.muDeg(), vmAxial.kappa())
                    : "";

            // A) Strong unimodal direction: trust first moment regardless of peak detection
            if (adc >= ADC_STRONG) {
                return String.format("Single preferred direction: %.1f° (ADC=%.2f%s)", pd, adc, vmDirStr);
            }

            // B) Strong axis but weak direction (e.g., two-lobed / antipodal morphology)
            if (odc >= ODC_STRONG && adc < ADC_STRONG) {
                return String.format("Preferred orientation (axis): %.1f° (ODC=%.2f%s)", po, odc, vmAxStr);
            }

            // C) No clear structure
            if (adc < ADC_LOW && odc < ODC_LOW) {
                return String.format("No preferred directions detected (ADC=%.2f, ODC=%.2f)", adc, odc);
            }

            final int nPeaks = directionPeaks.size();
            // D) Use peak picking to distinguish the remaining cases
            // D1) Single peak
            if (nPeaks == 1)
                return String.format("Single preferred direction: %.1f° (ADC=%.2f%s)", directionPeaks.getFirst().angleDeg, adc, vmDirStr);

            // D2) Multiple peaks
            if (nPeaks >= 2) {
                final int k = Math.min(3, nPeaks);
                final StringBuilder list = new StringBuilder();
                for (int i = 0; i < k; i++) {
                    if (i > 0) list.append(", ");
                    list.append(String.format("%.1f°", directionPeaks.get(i).angleDeg));
                }
                if (nPeaks > k) list.append(", …");
                return String.format("Multiple preferred directions (%d): %s (ADC=%.2f, ODC=%.2f)", nPeaks, list, adc, odc);
            }

            // E) Weak
            return (adc >= odc)
                    ? String.format("Weak preferred direction: %.1f° (ADC=%.2f)", pd, adc)
                    : String.format("Weak preferred orientation: %.1f° (ODC=%.2f)", po, odc);
        }
    }
}