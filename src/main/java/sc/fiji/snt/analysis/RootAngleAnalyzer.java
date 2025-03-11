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

import net.imglib2.display.ColorTable;
import org.apache.commons.math3.analysis.integration.SimpsonIntegrator;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.util.MathUtils;

import org.scijava.plot.*;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class to perform Root angle analysis on a {@link Tree} according to Bird & Cuntz 2019,
 * <a href="https://pubmed.ncbi.nlm.nih.gov/31167149/">PMID 31167149</a>.
 *
 * @author Tiago Ferreira
 */
public class RootAngleAnalyzer {

    @Parameter
    private PlotService plotService;

    private final DirectedWeightedGraph graph;
    private final boolean is3D;
    private final String analysisLabel;
    private ArrayList<Double> rootAnglesInDegrees;
    private DescriptiveStatistics stats;
    private Tree taggedTree;
    private VonMisesDistribution vmDistribution;


    /**
     * Constructs a RootAngleAnalyzer for the given tree.
     *
     * @param tree the tree to analyze.
     * @throws IllegalArgumentException if tree contains multiple roots or loops
     */
    public RootAngleAnalyzer(final Tree tree) throws IllegalArgumentException {
        graph = tree.getGraph();
        is3D = tree.is3D();
        analysisLabel = tree.getLabel();
    }

    /**
     * Calculates the angle between the vectors formed by the given node, its preceding node, and the root.
     *
     * @param node the current node. Must have a previous node assigned
     * @param root the root node.
     * @return the angle in radians between the vectors formed by (node, previous) and (node, root).
     * The return value is always in the range \[0, π\].
     * @throws NullPointerException if node has no previous node assigned.
     */
    private double getRootAngle(final SWCPoint node, final SWCPoint root) throws NullPointerException {
        assert node.previous() != null;
        final Vector3D vector1 = new Vector3D(
                node.getX() - node.previous().getX(),
                node.getY() - node.previous().getY(),
                node.getZ() - node.previous().getZ()
        );
        final Vector3D vector2 = new Vector3D(
                node.getX() - root.getX(),
                node.getY() - root.getY(),
                node.getZ() - root.getZ()
        );
        final double dotProduct = vector1.dotProduct(vector2);
        final double magnitude1 = vector1.getNorm();
        final double magnitude2 = vector2.getNorm();
        return (magnitude1 == 0 || magnitude2 == 0)
                ? 0
                : Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct / (magnitude1 * magnitude2))));
    }

    /**
     * Calculates the angle between the vectors formed by the given node, its previous point, and the root,
     * and converts the result from radians to degrees.
     *
     * @param node the current node.
     * @param root the root node.
     * @return the angle in degrees between the vectors formed by (node, previous) and (node, root).
     * The return value is always in the range \[0, 180\].
     */
    private double getRootAngleInDegrees(final SWCPoint node, final SWCPoint root) {
        final double angleInRadians = getRootAngle(node, root);
        return Math.toDegrees(angleInRadians); // % 360 not needed since angle is normalized
    }

    private void compute() throws IllegalArgumentException {
        final List<SWCPoint> nodes = graph.getNodesFromLeavesToRoot();
        final SWCPoint root = graph.getRoot();
        if (nodes.isEmpty() || root == null) {
            throw new IllegalArgumentException("Tree has no nodes or unknown root");
        }
        rootAnglesInDegrees = new ArrayList<>();
        for (final SWCPoint node : nodes) {
            if (node.previous() != null) {
                final double angle = getRootAngleInDegrees(node, root);
                rootAnglesInDegrees.add(angle);
                node.v = angle;
            }
        }
    }

    /**
     * @return the list of root angles sorted from leaves to root (in degrees).
     */
    public List<Double> getAngles() {
        if (rootAnglesInDegrees == null) compute();
        return rootAnglesInDegrees;
    }

    /**
     * @return the graph of the tree being parsed. Vertices are tagged with the root angles (@see {@link SWCPoint#v}).
     */
    public DirectedWeightedGraph getTaggedGraph() {
        if (rootAnglesInDegrees == null) compute();
        return graph;
    }

    /**
     * Returns analyzed tree with the root angles assigned to its nodes.
     *
     * @param colorTable the color table specifying the color mapping. Null allowed.
     * @return a copy of the tree with the root angles assigned to its nodes.
     */
    @SuppressWarnings("unused")
    public Tree getTaggedTree(final ColorTable colorTable) {
        if (taggedTree == null) taggedTree = getTaggedGraph().getTree();
        if (colorTable != null) {
            final TreeColorMapper mapper = new TreeColorMapper();
            mapper.map(taggedTree, TreeColorMapper.VALUES, colorTable);
        }
        return taggedTree;
    }

    /**
     * Returns analyzed tree with the root angles assigned to its nodes.
     *
     * @param lutName the LUT name specifying the color mapping. Null allowed.
     * @return a copy of the tree with the root angles assigned to its nodes.
     */
    @SuppressWarnings("unused")
    public Tree getTaggedTree(final String lutName) {
        if (taggedTree == null) taggedTree = getTaggedGraph().getTree();
        if (lutName != null) {
            final TreeColorMapper mapper = new TreeColorMapper();
            mapper.map(taggedTree, TreeColorMapper.VALUES, lutName);
        }
        return taggedTree;
    }

    /**
     * @return the descriptive statistics of the root angles (in degrees).
     */
    public DescriptiveStatistics getDescriptiveStatistics() {
        if (stats == null) {
            stats = new DescriptiveStatistics();
            getAngles().forEach(angle -> stats.addValue(angle));
        }
        return stats;
    }

    /**
     * @return Returns the smallest of root angles (in degrees).
     */
    public double min() {
        return getDescriptiveStatistics().getMin();
    }

    /**
     * @return Returns the largest of root angles (in degrees).
     */
    public double max() {
        return getDescriptiveStatistics().getMax();
    }

    /**
     * @return Returns the arithmetic mean of root angles (in degrees).
     */
    public double mean() {
        return getDescriptiveStatistics().getMean();
    }

    /**
     * Returns the mean direction of the fitted Von Mises distribution.
     *
     * @return the mean direction (in degrees).
     */
    public double meanDirection() {
        initVonMisesDistributionAsNeeded();
        return Math.toDegrees(vmDistribution.getMeanDirection());
    }

    /**
     * Returns the strength of the centripetal bias, also known as κ.
     * κ is defined as the concentration of the Von Mises fit of the root angle distribution.
     * κ= 0 indicate no bias (root angles are distributed uniformly).
     * K->∞ indicate that all neurites point directly toward the root of the tree
     *
     * @return Returns the centripetal bias, or κ (dimensionless, range: [0, ∞[).
     */
    public double centripetalBias() {
        initVonMisesDistributionAsNeeded();
        return vmDistribution.getConcentration();
    }

    /**
     * Returns the balancing factor, computed from {@link #centripetalBias()}.
     *
     * @return the balancing factor (dimensionless, range: [0, 1]).
     */
    public double balancingFactor() {
        final double k = centripetalBias();
        // Default fit parameters from Bird & Cuntz 2019
        final double[] params = (is3D) ? new double[]{0.7331, 3.714, 0.3331} : new double[]{1.201, 4.39, 0.2857};
        double bf = 1 - Math.pow(1 + Math.pow(k / params[0], 1 / params[2]), -1 / params[1]);
        if (bf < 0) { // Remove extreme values
            bf = 0;
            SNTUtils.log("Warning: Balancing factor out of usual range");
        } else if (bf > 1) {
            bf = 1;
            SNTUtils.log("Warning: Balancing factor out of usual range");
        }
        return bf;
    }

    private void initVonMisesDistributionAsNeeded() {
        if (vmDistribution == null) vmDistribution = new VonMisesDistribution(asRadians(getAngles()));
    }

    private List<Double> asRadians(final List<Double> anglesInDegrees) {
        final List<Double> anglesInRadians = new ArrayList<>(anglesInDegrees.size());
        anglesInDegrees.forEach(degAngle -> anglesInRadians.add(Math.toRadians(degAngle)));
        return anglesInRadians;
    }

    /**
     * Assembles the histogram distribution of root angles.
     *
     * @param polar whether the histogram should be polar.
     */
    public SNTChart getHistogram(final boolean polar) {
        SNTChart chart;
        final DescriptiveStatistics stats = getDescriptiveStatistics();
        final String desc = getDescriptiveStatisticsDescription(stats);
        if (polar) {
            chart = AnalysisUtils.createPolarHistogram("Root angles", "°", stats, desc);
        } else {
            chart = AnalysisUtils.createHistogram("Root angles", "°", stats, desc);
        }
        chart.setTitle(String.format("Root Angle Distribution (%s)", analysisLabel));
        return chart;
    }

    /**
     * Generates a density plot of the root angles using the fitted Von Mises distribution.
     * The plot displays the density of root angles in degrees.
     *
     * @return an SNTChart object representing the density plot of the root angles.
     */
    public SNTChart getDensityPlot() {
        if (plotService == null) {
            SNTUtils.getContext().inject(this);
        }
        final XYPlot plot = plotService.newXYPlot();
        plot.xAxis().setLabel("Root angle (°)");
        plot.yAxis().setLabel("Density");
        final XYSeries series = addDensityPlotSeries(plot);
        series.setStyle(plotService.newSeriesStyle(Colors.BLACK, LineStyle.SOLID, MarkerStyle.NONE));
        series.setLabel(String.format("Von Mises fit: μ=%.2f°; κ=%.3f", meanDirection(), centripetalBias()));
        series.setLegendVisible(false);
        final SNTChart chart = new SNTChart(String.format("Root Angle Density (%s)", analysisLabel), plot);
        chart.annotate(
                String.format("Von Mises fit: μ=%.2f°; κ=%.3f", meanDirection(), centripetalBias()),
                String.format("Balancing factor: %.3f\nCramér-Von Mises statistic: %.3f", balancingFactor(), getCramerVonMisesStatistic()),
                "center");
        chart.setGridlinesVisible(false);
        chart.setLineWidth(2);
        return chart;
    }

    private XYSeries addDensityPlotSeries(final XYPlot plot) {
        initVonMisesDistributionAsNeeded();
        final int n = 360; // 0.5deg resolution
        final List<Double> xValues = new ArrayList<>(n);
        final List<Double> yValues = new ArrayList<>(n);
        for (int i = 0; i <= n; i++) {
            final double angle = i * Math.PI / n;
            xValues.add(Math.toDegrees(angle));
            yValues.add(vmDistribution.density(angle));
        }
        final XYSeries series = plot.addXYSeries();
        series.setValues(xValues, yValues);
        return series;
    }

    /**
     * Computes the Cramér-von Mises statistic between computed root angles and fitted Von Mises distribution.
     * A value of 0 indicates a perfect fit between the empirical distribution and the theoretical distribution,
     * and larger values indicate a greater discrepancy between the two distributions.
     *
     * @return the Cramér-von Mises statistic. Range: [0, ∞[ (dimensionless).
     */
    public double getCramerVonMisesStatistic() {
        initVonMisesDistributionAsNeeded();
        final List<Double> inputAngles = asRadians(getAngles());
        final int n = inputAngles.size();
        final double[] ecdf = new double[n];
        final double[] cdf = new double[n];

        // Sort input angles
        Collections.sort(inputAngles);
        // Calculate the ECDF by assigning each sorted value a cumulative
        // probability based on its position in the sorted list
        for (int i = 0; i < n; i++) {
            ecdf[i] = (i + 1) / (double) n;
        }
        // Calculate the cumulative distribution function of the fitted von Mises distribution at the input angles
        for (int i = 0; i < n; i++) {
            cdf[i] = vmDistribution.cdf(0, inputAngles.get(i));
        }
        // Compute the Cramér-von Mises statistic
        double statistic = 0.0;
        for (int i = 0; i < n; i++) {
            statistic += Math.pow(cdf[i] - ecdf[i], 2);
        }
        statistic += 1.0 / (12 * n);
        return statistic;
    }

    public static SNTChart getHistogram(final List<RootAngleAnalyzer> analyzers, final boolean polar) {
        SNTChart chart;
        final DescriptiveStatistics uberStats = new DescriptiveStatistics();
        for (final RootAngleAnalyzer analyzer : analyzers) {
            for (final double value : analyzer.getDescriptiveStatistics().getValues())
                uberStats.addValue(value);
        }
        final String desc = getDescriptiveStatisticsDescription(uberStats);
        if (polar) {
            chart = AnalysisUtils.createPolarHistogram("Root angles", "°", uberStats, desc);
        } else {
            chart = AnalysisUtils.createHistogram("Root angles", "°", uberStats, desc);
        }
        chart.setTitle(String.format("Root Angle Distribution (%s)", getGroupLabel(analyzers)));
        return chart;
    }

    public static SNTChart getDensityPlot(final List<RootAngleAnalyzer> analyzers) {
        final PlotService plotService = SNTUtils.getContext().getService(PlotService.class);
        final XYPlot plot = plotService.newXYPlot();
        plot.xAxis().setLabel("Root angle (°)");
        plot.yAxis().setLabel("Density");
        final ColorRGB[] colors = SNTColor.getDistinctColors(analyzers.size());
        int idx = 0;
        for (final RootAngleAnalyzer analyzer : analyzers) {
            final XYSeries series = analyzer.addDensityPlotSeries(plot);
            series.setStyle(plotService.newSeriesStyle(colors[idx++], LineStyle.SOLID, MarkerStyle.NONE));
            series.setLabel(String.format("%s: μ=%.2f°; κ=%.3f", analyzer.analysisLabel, analyzer.meanDirection(), analyzer.centripetalBias()));
            series.setLegendVisible(true); // or it won't make it to final SNTChart
        }
        final SNTChart chart = new SNTChart(String.format("Root Angle Density (%s)", getGroupLabel(analyzers)), plot);
        chart.setGridlinesVisible(false);
        chart.setLineWidth(2);
        chart.setLegendVisible( analyzers.size() < 11);
        return chart;
    }

    private static String getGroupLabel(final List<RootAngleAnalyzer> analyzers) {
        final String labels = analyzers.stream().map(analyzer -> analyzer.analysisLabel).collect(Collectors.joining(", "));
        return labels.length() > 50 ? labels.substring(0, 50) + "..." : labels;
    }

    private static String getDescriptiveStatisticsDescription(final DescriptiveStatistics stats) {
        return String.format("""
                        Q1: %.2f°; Median: %.2f°; Q3: %.2f°; IQR: %.2f°
                        N: %d; Min: %.2f°; Max: %.2f°; Mean±SD: %.2f±%.2f°""",
                stats.getPercentile(25), stats.getPercentile(50), stats.getPercentile(75), stats.getPercentile(75) - stats.getPercentile(25), //
                stats.getN(), stats.getMin(), stats.getMax(), stats.getMean(), stats.getStandardDeviation());
    }

    private static class VonMisesDistribution {
        final double meanDirection;
        final double concentration;
        final Random random;

        /**
         * Constructs a VonMisesDistribution with the specified mean direction and concentration.
         *
         * @param anglesInRadians the list of angles (in radians) to be fitted by the distribution.
         */
        VonMisesDistribution(final Collection<Double> anglesInRadians) {
            double sumSin = 0.0;
            double sumCos = 0.0;
            for (final double angle : anglesInRadians) {
                sumSin += Math.sin(angle);
                sumCos += Math.cos(angle);
            }
            final double R = Math.sqrt(sumSin * sumSin + sumCos * sumCos) / anglesInRadians.size();
            meanDirection = Math.atan2(sumSin, sumCos);
            concentration = (R * (2 - R * R)) / (1 - R * R);
            random = new Random();
        }

        /**
         * Returns the mean direction of the distribution.
         *
         * @return the mean direction in radians
         */
        double getMeanDirection() {
            return meanDirection;
        }

        /**
         * Returns the concentration parameter κ of the distribution, which measures how tightly values are clustered
         * around the mean direction. A higher concentration indicates that the values are more tightly clustered.
         *
         * @return the concentration parameter (dimensionless).
         */
        double getConcentration() {
            return concentration;
        }

        /**
         * Calculates the probability density function (PDF) of the von Mises distribution at a given angle.
         * The PDF describes the likelihood of a random variable taking on a particular value.
         *
         * @param angle the angle at which to compute the PDF (in radians).
         * @return the computed value of the PDF at the given angle.
         */
        double density(final double angle) {
            return Math.exp(concentration * Math.cos(angle - meanDirection)) / (2 * Math.PI * I0(concentration));
        }

        /**
         * Computes the modified Bessel function of the first kind, I0.
         * This function is used in the normalization constant of the von Mises distribution's PDF.
         *
         * @param x the value for which to compute the Bessel function.
         * @return the computed value of I0(x).
         */
        double I0(final double x) {
            double sum = 1.0;
            double term = 1.0;
            // expansion is an infinite series. [1,100[ should approximate (I_0(x)) for most practical purposes!?
            for (int k = 1; k < 100; k++) {
                term *= (x * x) / (4 * k * k);
                sum += term;
            }
            return sum;
        }

        /**
         * Calculates the cumulative distribution function (CDF) of the Von Mises distribution at a given angle.
         *
         * @param min the lower bound of the interval in radians.
         * @param max the upper bound of the interval in radians.
         * @return the CDF value at the specified range.
         */
        double cdf(final double min, final double max) {
            if (min == 0 && max == 0) return 0;
            final SimpsonIntegrator integrator = new SimpsonIntegrator();
            return integrator.integrate(10000, this::density, min, max);
        }

        /**
         * Generates a list of angles sampled from the Von Mises distribution.
         *
         * @param numSamples the number of samples to generate.
         * @param min        the minimum value for the angles in radians.
         * @param max        the maximum value for the angles in radians.
         * @return a list of angles in radians, clamped between min and max.
         */
        @SuppressWarnings("unused")
        List<Double> sampleAngles(final int numSamples, final double min, final double max) {
            final List<Double> angles = new ArrayList<>(numSamples);
            for (int i = 0; i < numSamples; i++)
                angles.add(sample(min, max));
            return angles;
        }

        /**
         * Generates a single sample from the Von Mises distribution for a sub-interval of [0, π].
         *
         * @param min the minimum value for the angle in radians.
         * @param max the maximum value for the angle in radians.
         * @return a random angle in radians, clamped between min and max
         * As per Bird & Cuntz (2019) [min, max] are a sub-interval of [0, π]
         */
        double sample(final double min, final double max) {
            final double a = 1.0 + Math.sqrt(1.0 + 4.0 * concentration * concentration);
            final double b = (a - Math.sqrt(2.0 * a)) / (2.0 * concentration);
            final double r = (1.0 + b * b) / (2.0 * b);
            while (true) {
                final double u1 = random.nextDouble();
                final double z = Math.cos(Math.PI * u1);
                final double f = (1.0 + r * z) / (r + z);
                final double c = concentration * (r - f);
                final double u2 = random.nextDouble();
                if (u2 < c * (2.0 - c) || u2 <= c * Math.exp(1.0 - c)) {
                    final double u3 = random.nextDouble();
                    double theta = meanDirection + Math.signum(u3 - 0.5) * Math.acos(f);
                    theta = MathUtils.reduce(theta, Math.PI, 0); // Normalize to [0, π]
                    if (theta >= min && theta <= max) {
                        return theta;
                    }
                }
            }
        }
    }

    public static void main(final String[] args) {
        final Tree tree =  new sc.fiji.snt.SNTService().demoTree("DG");
        assert (tree != null);
        final RootAngleAnalyzer analyzer = new RootAngleAnalyzer(tree);
        SNTChart.combine(List.of(analyzer.getHistogram(true), analyzer.getDensityPlot())).show();
//        final sc.fiji.snt.viewer.Viewer3D viewer = new sc.fiji.snt.viewer.Viewer3D();
//        viewer.add(analyzer.getTaggedTree(net.imagej.display.ColorTables.ICE));
//        viewer.addColorBarLegend(net.imagej.display.ColorTables.ICE, analyzer.min(), analyzer.max());
//        viewer.show();
        System.out.println("Mean direction: " + analyzer.meanDirection());
        System.out.println("Centripetal bias (k): " + analyzer.centripetalBias());
        System.out.println("Balancing actor (bf): " + analyzer.balancingFactor());
        System.out.println("Mean: " + analyzer.mean());
        System.out.println("Min: " + analyzer.min());
        System.out.println("Max: " + analyzer.max());
    }
}
