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
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.ShollPoint;

import java.awt.*;
import java.util.ArrayList;

/**
 * Statistics for polar (radius×angle) Sholl distributions, providing analysis
 * and visualization methods for directional patterns in Sholl profiles.
 * <p>
 * This class analyzes the directional distribution of Sholl intersections/cable length
 * by dividing the space around a center point into angular sectors. The angular resolution
 * is specified using step size in degrees (e.g., 10° for 36 bins).
 * </p>
 *
 * @author Tiago Ferreira
 */
public class PolarStats implements ShollStats {

    private final Profile profile;
    private DataMode dataMode = DataMode.INTERSECTIONS; // Default mode
    private double angleStepSize; // in degrees
    private int nAngleBins; // calculated from angleStepSize since range is always [0-360[ degrees
    private double[][] intersectionMatrix; // [radialBin][angleBin]
    private double[][] lengthMatrix; // [radialBin][angleBin]
    private boolean parsed;

    /**
     * Instantiates a new PolarStats from an existing Sholl Profile, with a default 10° angular resolution.
     *
     * @param profile the Sholl Profile containing radii and intersection counts
     */
    public PolarStats(final Profile profile) {
        this(profile, DataMode.INTERSECTIONS, ShollAnalysisPrefsCmd.DEF_ANGLE_STEP_SIZE);
    }

    /**
     * Instantiates a new PolarStats from a Profile with custom angular resolution.
     *
     * @param profile       the Sholl Profile
     * @param angleStepSize the angular step size in degrees (e.g., 10.0 for 10° bins)
     */
    public PolarStats(final Profile profile, final DataMode dataMode, final double angleStepSize) {
        this.profile = profile;
        setAngleStepSize(angleStepSize);
        setDataMode(dataMode);
    }

    /**
     * Instantiates a new PolarStats from a Profile with custom angular resolution.
     *
     * @param linerStats    the LinearProfileStats instance from which profile is extracted. If a polynomial fit has
     *                      been applied the fitted profile is used. The data mode used is that of LinearProfileStats
     *                      and cannot be changed.
     * @param angleStepSize the angular step size in degrees (e.g., 10.0 for 10° bins)
     */
    public PolarStats(final LinearProfileStats linerStats, final double angleStepSize) {
        this.profile = (linerStats.validFit()) ? linerStats.getFittedProfile() : linerStats.getProfile();
        profile.getProperties().setProperty("fit", linerStats.getPolynomialAsString(true));
        this.dataMode = linerStats.getDataMode(); // do not use setter
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
     * @param angleStepSize the angular step size in degrees (e.g., 10.0 for 10° bins)
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
    }

    @Override
    public DataMode getDataMode() {
        return dataMode;
    }

    @Override
    public void setDataMode(final DataMode mode) {
        parsed = parsed && this.dataMode == mode;
        this.dataMode = mode;
        if ("true".equals(profile.getProperties().getProperty("fitted"))) {
            throw new IllegalArgumentException("DataMode cannot be changed when handling fitted profiles");
        }
    }

    private void computeMatrices() {
        if (profile.center() == null || profile.isEmpty()) {
            throw new IllegalStateException("Center of profile unknown or profile is empty");
        }
        // we can only proceed if intersection points are known
        final boolean hasIntersectionPoints =
                profile.entries().stream().anyMatch(entry -> entry.points != null && !entry.points.isEmpty());
        if (!hasIntersectionPoints) {
            throw new IllegalArgumentException("Polar analysis requires location of Sholl intersections to be known. " +
                    "Ensure the parser was used to generate the Profile with intersection points.");
        }

        // Initialize intersection and length matrices
        intersectionMatrix = new double[profile.size()][nAngleBins];
        lengthMatrix = new double[profile.size()][nAngleBins];
        final double stepRadius = profile.stepSize();
        final double nRadialBins = profile.size();
        for (final ProfileEntry entry : profile.entries()) {
            final double radius = entry.radius;

            final int radialBin = (int) Math.floor(radius / stepRadius);
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
                    intersectionMatrix[radialBin][angleBin] += countScale * c;
                    lengthMatrix[radialBin][angleBin] += lengthPerComponent * c;
                }
            } else {
                // Fall back to uniform distribution for this entry
                final double countPerBin = entry.count / nAngleBins;
                final double lengthPerBin = entry.length / nAngleBins;
                for (int angleBin = 0; angleBin < nAngleBins; angleBin++) {
                    intersectionMatrix[radialBin][angleBin] += countPerBin;
                    lengthMatrix[radialBin][angleBin] += lengthPerBin;
                }
            }
        }
        parsed = true;
    }

    public double[][] getMatrix() {
        if (!parsed) computeMatrices();
        return (getDataMode() == DataMode.INTERSECTIONS) ? intersectionMatrix : lengthMatrix;
    }

    public double[] getAngularDistribution() {
        final double[][] matrix = getMatrix();
        final double[] angularDist = new double[nAngleBins];
        for (int a = 0; a < nAngleBins; a++) {
            for (int r = 0; r < profile.size(); r++) {
                angularDist[a] += matrix[r][a];
            }
        }
        return angularDist;
    }

    public double getAngularDistributionCoherence() {
        final double[] angularDist = getAngularDistribution();
        return calculateAngularDistributionCoherence(angularDist);
    }

    private double calculateAngularDistributionCoherence(final double[] angularDist) {
        final double angleStepDeg = angleStepSize;
        double sx = 0, sy = 0, sum = 0;
        for (int a = 0; a < nAngleBins; a++) {
            final double thetaDeg = (a + 0.5) * angleStepDeg;
            final double thetaRad = Math.toRadians(thetaDeg);
            final double weight = angularDist[a];
            sx += weight * Math.cos(thetaRad);
            sy += weight * Math.sin(thetaRad);
            sum += weight;
        }
        return (sum > 0) ? Math.sqrt(sx * sx + sy * sy) / sum : 0.0; // adimensional
    }

    public double getPreferredDirection() {
        final double[] angularDist = getAngularDistribution();
        return calculatePreferredDirection(angularDist);
    }

    private double calculatePreferredDirection(final double[] angularDist) {
        final double angleStepDeg = angleStepSize;
        double sx = 0, sy = 0;
        for (int a = 0; a < nAngleBins; a++) {
            final double thetaDeg = (a + 0.5) * angleStepDeg;
            final double thetaRad = Math.toRadians(thetaDeg);
            final double weight = angularDist[a];
            sx += weight * Math.cos(thetaRad);
            sy += weight * Math.sin(thetaRad);
        }
        double dirDeg = Math.toDegrees(Math.atan2(sy, sx));
        dirDeg = (dirDeg % 360 + 360) % 360; // normalize to [0, 360[
        return dirDeg;
    }

    public SNTChart getPlot() {
        return getPlot(ColorMaps.ICE);
    }

    public SNTChart getPlot(final String colormap) {
        final ColorTable colorTable = ColorMaps.get(colormap);
        return getPlot((colorTable == null) ? ColorMaps.ICE : colorTable);
    }

    public SNTChart getPlot(final ColorTable colorTable) {
        if (colorTable == null)
            throw new IllegalArgumentException("colorTable cannot be null");
        return createPolarHeatmapFromMatrix(colorTable, getMatrix());
    }

    private SNTChart createPolarHeatmapFromMatrix(final ColorTable colorTable, final double[][] matrix) {

        final java.util.List<double[][]> seriesData = new ArrayList<>();
        final double angleStep = angleStepSize;
        final double stepRadius = profile.stepSize();

        // Create filled polar segments for each bin
        for (int r = 0; r < profile.size(); r++) {
            for (int a = 0; a < nAngleBins; a++) {
                final double value = matrix[r][a];
                if (value > 0) {
                    final double innerRadius = r * stepRadius;
                    final double outerRadius = (r + 1) * stepRadius;
                    final double startAngle = a * angleStep;
                    final double endAngle = (a + 1) * angleStep;

                    // Create filled sector by defining a closed polygon
                    // Start from inner radius, go to outer radius, then back to close the shape
                    final double[][] series = new double[][]{
                            new double[]{startAngle, endAngle, endAngle, startAngle, startAngle},
                            new double[]{innerRadius, innerRadius, outerRadius, outerRadius, innerRadius}
                    };
                    seriesData.add(series);
                }
            }
        }
        // Set colors based on values
        final java.util.List<Color> seriesColors = new ArrayList<>();
        final double minValue = getMinValue(matrix);
        final double maxValue = getMaxValue(matrix);
        for (int r = 0; r < profile.size(); r++) {
            for (int a = 0; a < nAngleBins; a++) {
                final double value = matrix[r][a];
                if (value > 0) {
                    seriesColors.add(getHeatmapColor(value, minValue, maxValue, colorTable));
                }
            }
        }
        final SNTChart plot = AnalysisUtils.polarPlot(String.format("Sholl Profile (Polar) for %s",
                profile.getProperties().getProperty(Profile.KEY_ID)), seriesData, seriesColors);
        plot.addColorBarLegend(colorBarLegendLabel(), colorTable, minValue, maxValue, (getDataMode() == DataMode.INTERSECTIONS) ? 0 : 1);
        plot.annotate(ShollPlot.defaultXtitle(getProfile()));
        return plot;
    }

    private String colorBarLegendLabel() {
        final String s2 = profile.getProperties().getProperty("fit", "");
        String s1;
        if (getDataMode() == DataMode.INTERSECTIONS)
            s1 = (s2.isEmpty()) ? "No. Intersections" : "No. Inters.";
        else
            s1 = (profile.scaled()) ? "Length ("+ profile.spatialCalibration().getUnit() + ")" : "Length";
        return String.format("%s [%s]", s1, s2);
    }

    private double getMinValue(final double[][] matrix) {
        double min = 0;
        for (int r = 0; r < profile.size(); r++) {
            for (int a = 0; a < nAngleBins; a++) {
                if (matrix[r][a] < min) {
                    min = matrix[r][a];
                }
            }
        }
        return min;
    }

    private double getMaxValue(final double[][] matrix) {
        double max = 0;
        for (int r = 0; r < profile.size(); r++) {
            for (int a = 0; a < nAngleBins; a++) {
                if (matrix[r][a] > max) {
                    max = matrix[r][a];
                }
            }
        }
        return max;
    }

    private Color getHeatmapColor(final double value, final double minValue, final double maxValue, final ColorTable colorTable) {
        if (maxValue == 0) return Color.LIGHT_GRAY;

        // Normalize value to [0, 1] range
        final double normalizedValue = (value - minValue) / (maxValue - minValue);

        // Map to colorTable index (same approach as ColorMaps.discreteColors)
        final int colorIndex = (int) Math.round(normalizedValue * (colorTable.getLength() - 1));

        // Use the same approach as ColorMaps.discreteColorsAWT
        // Extract RGB values and create ColorRGB first, then convert to AWT Color
        final org.scijava.util.ColorRGB colorRGB = new org.scijava.util.ColorRGB(
                colorTable.get(ColorTable.RED, colorIndex),
                colorTable.get(ColorTable.GREEN, colorIndex),
                colorTable.get(ColorTable.BLUE, colorIndex)
        );

        return new Color(colorRGB.getRed(), colorRGB.getGreen(), colorRGB.getBlue());
    }

    // ShollStats implementation
    @Override
    public double[] getXValues() {
        // Return angular bin centers in degrees
        final double[] angles = new double[nAngleBins];
        for (int i = 0; i < nAngleBins; i++) {
            angles[i] = (i + 0.5) * angleStepSize;
        }
        return angles;
    }

    @Override
    public double[] getYValues() {
        return getAngularDistribution();
    }

    @Override
    public double[] getYValues(final boolean asCumulativeFrequencies) {
        return (asCumulativeFrequencies) ? null : getYValues(); // Not applicable for polar data
    }

    @Override
    public double[] getFitYValues() {
        // No fitting for polar data
        return null;
    }

    @Override
    public double[] getFitYValues(final boolean asCumulativeFrequencies) {
        // No fitting for polar data
        return null;
    }

    @Override
    public boolean validFit() {
        // No fitting for polar data
        return false;
    }

    @Override
    public int getN() {
        return nAngleBins;
    }

    @Override
    public Profile getProfile() {
        return profile;
    }
}