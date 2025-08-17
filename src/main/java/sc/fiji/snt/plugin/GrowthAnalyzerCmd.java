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

package sc.fiji.snt.plugin;

import org.apache.commons.lang.WordUtils;
import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plot.PlotService;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYSeries;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.util.ColorRGB;

import org.scijava.widget.Button;
import sc.fiji.snt.Path;
import sc.fiji.snt.analysis.AnalysisUtils;
import sc.fiji.snt.analysis.growth.GrowthAnalyzer;
import sc.fiji.snt.analysis.growth.GrowthAnalysisResults;
import sc.fiji.snt.analysis.growth.NeuriteGrowthData;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GUI command to the {@link GrowthAnalyzer} class. Implements the "Grow Analysis..." command.
 * @see GrowthAnalyzer
 * @see GrowthAnalysisResults
 */
@Plugin(type = Command.class, label = "Growth Analysis...", initializer = "init")
public class GrowthAnalyzerCmd extends CommonDynamicCmd {

    // GUI Prompt
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private final String TOP_HEADER = "<HTML>This command assembles a growth analysis report of time-matched paths.<br>"
            + "Analysis includes linear growth rates, phase detection, and retraction events.<br>"
            + "Paths must be first tagged using the 'Match Paths Across Time...' command.";

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Growth Phases:")
    private String HEADER1;

    @Parameter(label = "Threshold for 'Lag'/'Plateau' phase (%)", min = "10", max = "50", stepSize = "1",
            style = "slider,format:0", description = """
            Defines the minimum growth rate threshold for classifying growth phases.
            Growth rates below this percentage are classified as 'lag' or 'plateau'.
            Must be between 10 and 50%""")
    private double lagPlateauThresholdPercent = GrowthAnalyzer.DEFAULT_BASE_THRESHOLD_FRACTION * 100;

    @Parameter(label = "Threshold for 'Rapid' phase (%)", min = "110", max = "300", stepSize = "1",
            style = "slider,format:0", description = """
            Growth rates that are this percentage above the overall rate are classified as 'Rapid'.
            Must be between 110 and 300%""")
    private double rapidThresholdPercent = GrowthAnalyzer.DEFAULT_RAPID_THRESHOLD_MULTIPLE * 100;

    @Parameter(label = "Threshold for retraction length (%)", min = "1", max = "50", stepSize = "1",
            style = "slider,format:0", description = """
                    Minimum length decrease to classify as retraction.
                    Must be between 1 and 50%""")
    private double retractionThresholdPercent = GrowthAnalyzer.DEFAULT_RETRACTION_THRESHOLD * 100;

    @Parameter(label = "Phase detection sensitivity", min = "0.05", max = "1.0", stepSize = "0.05",
            style = "slider,format:0.00", description = """
            Sensitivity for detecting growth phase changes
            Higher values = more sensitive (detect more phases)
            Lower values = less sensitive (detect fewer, major phases)""")
    private double phaseSensitivity = GrowthAnalyzer.DEFAULT_PHASE_SENSITIVITY;

    @Parameter(label = "Detection smoothing (%)", min = "5", max = "50", stepSize = "1",
            style = "slider,format:0", description = """
    Controls smoothing of phase change detection. Higher values provide more stable 
    detection with fewer phases, while lower values detect more detailed changes 
    but may include noise.""")
    private double windowSizePercent = GrowthAnalyzer.DEFAULT_WINDOW_SIZE_FRACTION * 100;

    @Parameter(required = false, label = "Defaults", callback = "resetInputParameters")
    private Button reset;

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Filtering Options:")
    private String HEADER2;

    @Parameter(label = "Minimum duration (no. of frames)", min = "2",
            description = "Neurites persisting less than this no. of frames are ignored from analysis")
    private int minTimePoints = GrowthAnalyzer.DEFAULT_MIN_TIME_POINTS;

    // Filtering parameters
    @Parameter(label = "Minimum path length (physical units)", min = "0",
            description = "Neurites shorter than this length at any given frame are ignored from analysis")
    private double minPathLength = GrowthAnalyzer.DEFAULT_MIN_PATH_LENGTH;


    @Parameter(required = true)
    private Collection<Path> paths;

    @Parameter
    private PlotService plotService;

    // Analysis results
    private GrowthAnalysisResults analysisResults;
    private double frameInterval;
    private String timeUnits;
    private String lengthUnits;

    @SuppressWarnings("unused")
    private void init() {
        super.init(false);
        if (snt.accessToValidImageData()) {
            frameInterval = snt.getImagePlus().getCalibration().frameInterval;
            timeUnits = snt.getImagePlus().getCalibration().getTimeUnit();
        } else {
            frameInterval = 1;
            timeUnits = "No. of frames";
        }
        lengthUnits = snt.getSpacingUnits();
    }

    // callbacks
    @SuppressWarnings("unused")
    private void resetInputParameters() {
        lagPlateauThresholdPercent = GrowthAnalyzer.DEFAULT_BASE_THRESHOLD_FRACTION * 100;
        rapidThresholdPercent = GrowthAnalyzer.DEFAULT_RAPID_THRESHOLD_MULTIPLE * 100;
        retractionThresholdPercent = GrowthAnalyzer.DEFAULT_RETRACTION_THRESHOLD * 100;
        phaseSensitivity = GrowthAnalyzer.DEFAULT_PHASE_SENSITIVITY;
        windowSizePercent = GrowthAnalyzer.DEFAULT_WINDOW_SIZE_FRACTION * 100;
    }

    @Override
    public void run() {
        try {
            final GrowthAnalyzer analyzer = new GrowthAnalyzer();
            // Configure thresholds
            analyzer.setPlateauThreshold(lagPlateauThresholdPercent / 100d);
            analyzer.setRapidThreshold(rapidThresholdPercent / 100d);
            analyzer.setRetractionThreshold(retractionThresholdPercent / 100d);
            analyzer.setPhaseSensitivity(phaseSensitivity);
            analyzer.setWindowSizeFraction(windowSizePercent / 100d);
            // Configure filtering
            analyzer.setMinTimePoints(Math.max(2, minTimePoints));
            analyzer.setMinPathLength(minPathLength);
            // Perform the analysis
            analysisResults = analyzer.analyze(paths, frameInterval, timeUnits);
            // Generate visualizations and reports
            generateSummaryStatistics();
            assembleAndDisplayPlots();
        } catch (final IllegalArgumentException e) {
            error(e.getMessage());
        } catch (final Exception e) {
            error("Analysis failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            resetUI();
        }
    }

    /**
     * Generates and displays summary statistics table.
     */
    private void generateSummaryStatistics() {
        final SNTTable summaryTable = new SNTTable();
        for (final NeuriteGrowthData data : analysisResults.getNeuriteGrowthData().values()) {
            summaryTable.insertRow(data.getNeuriteId());
            summaryTable.appendToLastRow("Total_Growth_" + lengthUnits, data.getTotalGrowth());
            summaryTable.appendToLastRow("Net_Growth_" + lengthUnits, data.getNetGrowth());
            summaryTable.appendToLastRow("Linear_Rate_" + lengthUnits + "_per_" + timeUnits, data.getLinearGrowthRate());
            summaryTable.appendToLastRow("R_Squared", data.getLinearRSquared());
            summaryTable.appendToLastRow("Average_Rate_" + lengthUnits + "_per_" + timeUnits, data.getAverageGrowthRate());
            summaryTable.appendToLastRow("Average_Elongation_Rate_" + lengthUnits + "_per_" + timeUnits, data.getAverageElongationRate());
            summaryTable.appendToLastRow("Average_Retraction_Rate_" + lengthUnits + "_per_" + timeUnits, data.getAverageRetractionRate());
            summaryTable.appendToLastRow("Total_Elongation_Length_" + lengthUnits, data.getTotalElongationLength());
            summaryTable.appendToLastRow("Total_Retraction_Length_" + lengthUnits, data.getTotalRetractionLength());
            summaryTable.appendToLastRow("No_Growth_Phases", data.getGrowthPhases().size());
            summaryTable.appendToLastRow("No_Elongation_Events", data.getElongationEvents().size());
            summaryTable.appendToLastRow("No_Retraction_Events", data.getRetractionEvents().size());
        }
        summaryTable.show("SNT_Growth_Analysis_Summary.csv");
    }

    /**
     * Assembles and displays all visualization plots.
     */
    private void assembleAndDisplayPlots() {
        // Create the main plots
        final String title = String.format("Growth Phase Timelines (N=%d Neurites)", analysisResults.getNeuriteCount());
        final SNTChart chart1 = new SNTChart(title, AnalysisUtils.createTimeline(analysisResults.getNeuritePhases(), timeUnits));
        final SNTChart chart2 = assembleLengthVsTimePlot();
        final SNTChart chart3 = assembleGrowthPhaseRingPlot();
        final SNTChart chart4 = assembleGrowthPhaseDurationRingPlot();
        final SNTChart chart5 = assembleDirectionalAnalysisPlot();
        final SNTChart chart6 = assembleAngularVelocityPlot();
        
        // Display charts in tiled layout (2x3 grid)
        SNTChart.tile(List.of(chart1, chart2, chart3, chart4, chart5, chart6));
    }

    /**
     * Creates the growth phase distribution donut plot.
     */
    private SNTChart assembleGrowthPhaseRingPlot() {
        // Get phase distribution as GrowthPhaseType -> Integer map and convert to GrowthPhaseType -> Double map
        final Map<GrowthAnalyzer.GrowthPhaseType, Integer> phaseDistribution = analysisResults.getPhaseDistribution();
        final HashMap<GrowthAnalyzer.GrowthPhaseType, Double> phaseFrequencies = new HashMap<>();
        for (Map.Entry<GrowthAnalyzer.GrowthPhaseType, Integer> entry : phaseDistribution.entrySet()) {
            if (entry.getValue() > 0) { // Only include phases with non-zero counts
                phaseFrequencies.put(entry.getKey(), entry.getValue().doubleValue());
            }
        }
        final String title = String.format("Growth Phases (N=%d Phases)", analysisResults.getTotalPhaseCount());
        return AnalysisUtils.ringPlot(title, phaseFrequencies);
    }

    /**
     * Creates the growth phase duration distribution donut plot.
     * Shows cumulative time spent in each phase type rather than occurrence counts.
     * 
     * @return SNTChart showing phase distribution by cumulative duration
     */
    private SNTChart assembleGrowthPhaseDurationRingPlot() {
        // Calculate cumulative durations for each phase type
        final HashMap<GrowthAnalyzer.GrowthPhaseType, Double> phaseDurations = new HashMap<>();
        // Initialize all phase types with zero duration
        for (GrowthAnalyzer.GrowthPhaseType phaseType : GrowthAnalyzer.GrowthPhaseType.values()) {
            phaseDurations.put(phaseType, 0.0);
        }
        // Sum durations across all neurites for each phase type
        for (NeuriteGrowthData neuriteData : analysisResults.getNeuriteGrowthData().values()) {
            for (GrowthAnalyzer.GrowthPhase phase : neuriteData.getGrowthPhases()) {
                phaseDurations.compute(phase.type(),
                        (k, currentDuration) -> currentDuration + phase.duration());
            }
        }
        // Remove phase types with zero duration for cleaner visualization
        phaseDurations.entrySet().removeIf(entry -> entry.getValue() == 0.0);
        // Calculate total duration for title
        double totalDuration = phaseDurations.values().stream().mapToDouble(Double::doubleValue).sum();
        final String title = String.format("Growth Phase Durations (Total Duration: %.1f %s)",
            totalDuration, timeUnits);
        return AnalysisUtils.ringPlot(title, phaseDurations);
    }

    /**
     * Creates the length vs time plot showing individual growth trajectories.
     */
    private SNTChart assembleLengthVsTimePlot() {
        XYPlot plot = plotService.newXYPlot();
        plot.xAxis().setLabel("Time (" + timeUnits + ")");
        plot.yAxis().setLabel("Length (" + lengthUnits + ")");

        final Map<String, ColorRGB> simpleLegendItems = new TreeMap<>();
        final ColorRGB[] colors = SNTColor.getDistinctColors(analysisResults.getNeuriteCount());
        int colorIndex = 0;
        boolean hasRetractions = false;
        boolean hasRapidElongations = false;
        for (final NeuriteGrowthData data : analysisResults.getNeuriteGrowthData().values()) {

            // Add main growth trajectory
            final XYSeries mainSeries = plot.addXYSeries();
            mainSeries.setLabel(data.getNeuriteId());
            final ColorRGB color = colors[colorIndex % colors.length];
            simpleLegendItems.put(data.getNeuriteId(), color);
            mainSeries.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID, MarkerStyle.CIRCLE));
            List<Double> xValues = new ArrayList<>();
            List<Double> yValues = new ArrayList<>();
            for (GrowthAnalyzer.TimePoint tp : data.getTimePoints()) {
                xValues.add(tp.frame() * frameInterval);
                yValues.add(tp.length());
            }
            mainSeries.setValues(xValues, yValues);

            // Add linear regression line if available
            if (data.getLinearGrowthRate() != 0) {
                XYSeries regressionSeries = plot.addXYSeries();
                regressionSeries.setLabel(String.format("R²=%.3f", data.getLinearRSquared()));
                regressionSeries.setStyle(plotService.newSeriesStyle(color, LineStyle.DASH, MarkerStyle.NONE));
                List<Double> regX = new ArrayList<>();
                List<Double> regY = new ArrayList<>();
                double minTime = xValues.getFirst();
                double maxTime = xValues.getLast();
                regX.add(minTime);
                regX.add(maxTime);
                regY.add(data.getLinearIntercept() + data.getLinearGrowthRate() * minTime);
                regY.add(data.getLinearIntercept() + data.getLinearGrowthRate() * maxTime);
                regressionSeries.setValues(regX, regY);
                // Update simplified legend items
                simpleLegendItems.put(String.format("%s R²=%.3f", data.getNeuriteId(), data.getLinearRSquared()), color);
                simpleLegendItems.remove(data.getNeuriteId());
            }
            colorIndex++;

            // Add retraction annotations
            for (GrowthAnalyzer.RetractionEvent retraction : data.getRetractionEvents()) {
                XYSeries retractionSeries = plot.addXYSeries();
                retractionSeries.setStyle(plotService.newSeriesStyle(ColorRGB.fromHTMLColor("#ff0000"),
                        LineStyle.NONE, MarkerStyle.FILLEDCIRCLE));
                List<Double> rX = List.of(retraction.startTime(), retraction.endTime());
                List<Double> rY = List.of(retraction.startLength(), retraction.endLength());
                retractionSeries.setValues(rX, rY);
                hasRetractions = true;
            }
            // Add rapid annotations
            List<GrowthAnalyzer.ElongationEvent> rapidElongations = filterElongationEventsForRapidPhases(data.getElongationEvents(), data.getGrowthPhases());
            for (GrowthAnalyzer.ElongationEvent elongation : rapidElongations) {
                XYSeries retractionSeries = plot.addXYSeries();
                retractionSeries.setStyle(plotService.newSeriesStyle(ColorRGB.fromHTMLColor("#00ff00"),
                        LineStyle.NONE, MarkerStyle.FILLEDCIRCLE));
                List<Double> rX = List.of(elongation.startTime(), elongation.endTime());
                List<Double> rY = List.of(elongation.startLength(), elongation.endLength());
                retractionSeries.setValues(rX, rY);
                hasRapidElongations = true;
            }
        }
        final SNTChart chart = new SNTChart("Length Over Time", plot);
        final LegendItemCollection legendItems = new LegendItemCollection();
        simpleLegendItems.forEach( (key, color) -> legendItems.add(new LegendItem(
                WordUtils.capitalizeFully(key),
                null, null, null,
                new java.awt.geom.Rectangle2D.Double(0, 0, 10, 4),
                new Color(color.getRed(), color.getGreen(), color.getBlue()
                )
        )));
        if (hasRetractions) {
            legendItems.add(new LegendItem("Retraction phase",
                    null, null, null,
                    new java.awt.geom.Ellipse2D.Double(0, 0, 7, 7), Color.RED));
        }
        if (hasRapidElongations) {
            legendItems.add(new LegendItem("Rapid phase",
                    null, null, null,
                    new java.awt.geom.Ellipse2D.Double(0, 0, 7, 7), Color.GREEN));
        }
        AnalysisUtils.replaceLegend(chart.getChart(), chart.getChart().getXYPlot(), legendItems);
        return chart;
    }

    /**
     * Filters elongation events to only include those occurring during RAPID phases
     */
    private static List<GrowthAnalyzer.ElongationEvent> filterElongationEventsForRapidPhases(
            final List<GrowthAnalyzer.ElongationEvent> elongationEvents, final List<GrowthAnalyzer.GrowthPhase> growthPhases) {
        // Get all RAPID phases
        final List<GrowthAnalyzer.GrowthPhase> rapidPhases = growthPhases.stream()
                .filter(phase -> phase.type() == GrowthAnalyzer.GrowthPhaseType.RAPID)
                .collect(Collectors.toList());
        // Filter elongation events that overlap with RAPID phases
        return elongationEvents.stream()
                .filter(elongation -> overlapsWithAnyRapidPhase(elongation, rapidPhases))
                .collect(Collectors.toList());
    }

    /**
     * Checks if an elongation event overlaps with any RAPID phase
     */
    private static boolean overlapsWithAnyRapidPhase(
            GrowthAnalyzer.ElongationEvent elongation,
            List<GrowthAnalyzer.GrowthPhase> rapidPhases) {

        return rapidPhases.stream().anyMatch(rapidPhase ->
                // Check temporal overlap
                elongation.startTime() < rapidPhase.endTime() &&
                        elongation.endTime() > rapidPhase.startTime()
        );
    }

    /**
     * Creates a directional analysis plot showing changes in neurite extension angles over time.
     * This plot helps visualize how neurite growth direction changes during development.
     * 
     * @return SNTChart showing extension angle trajectories over time
     */
    private SNTChart assembleDirectionalAnalysisPlot() {
        XYPlot plot = plotService.newXYPlot();
        plot.xAxis().setLabel("Time (" + timeUnits + ")");
        plot.yAxis().setLabel("Extension angle (°)");
        final ColorRGB[] colors = SNTColor.getDistinctColors(analysisResults.getNeuriteCount());
        int colorIndex = 0;
        boolean hasDirectionalData = false;
        for (NeuriteGrowthData data : analysisResults.getNeuriteGrowthData().values()) {
            // Get the matched paths for this neurite
            String neuriteId = data.getNeuriteId();
            List<Path> matchedPaths = getMatchedPathsForNeurite(neuriteId);
            if (matchedPaths.size() < 2) continue; // Need at least 2 time points for directional analysis
            
            // Calculate extension angles over time
            List<Double> timePoints = new ArrayList<>();
            List<Double> extensionAngles = new ArrayList<>();
            for (Path path : matchedPaths) {
                double angle = path.getExtensionAngle3D(false); // Absolute 3D extension angle
                if (!Double.isNaN(angle)) {
                    double timePoint = path.getFrame() * frameInterval;
                    timePoints.add(timePoint);
                    extensionAngles.add(angle);
                    hasDirectionalData = true;
                }
            }
            
            if (timePoints.size() >= 2) {
                // Add main extension angle trajectory
                XYSeries angleSeries = plot.addXYSeries();
                angleSeries.setLabel(WordUtils.capitalizeFully(neuriteId));
                final ColorRGB color = colors[colorIndex % colors.length];
                angleSeries.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID, MarkerStyle.CIRCLE));
                angleSeries.setValues(timePoints, extensionAngles);
            }
            colorIndex++;
        }
        
        if (!hasDirectionalData) {
            // Create empty plot with message
            XYSeries emptySeries = plot.addXYSeries();
            emptySeries.setLabel("No directional data available");
            emptySeries.setValues(List.of(0.0), List.of(0.0));
        }
        return new SNTChart("Extension Direction Over Time", plot);
    }
    
    /**
     * Creates a complementary plot showing angular velocity (rate of directional change).
     * 
     * @return SNTChart showing angular velocity over time
     */
    private SNTChart assembleAngularVelocityPlot() {
        XYPlot plot = plotService.newXYPlot();
        plot.xAxis().setLabel("Time (" + timeUnits + ")");
        plot.yAxis().setLabel("Angular velocity (°/" + timeUnits + ")");
        
        final ColorRGB[] colors = SNTColor.getDistinctColors(analysisResults.getNeuriteCount());
        int colorIndex = 0;
        
        boolean hasVelocityData = false;

        for (NeuriteGrowthData data : analysisResults.getNeuriteGrowthData().values()) {
            String neuriteId = data.getNeuriteId();
            List<Path> matchedPaths = getMatchedPathsForNeurite(neuriteId);
            
            if (matchedPaths.size() < 3) continue; // Need at least 3 time points for velocity calculation
            
            List<Double> timePoints = new ArrayList<>();
            List<Double> angularVelocities = new ArrayList<>();
            
            double previousAngle = Double.NaN;
            double previousTime = Double.NaN;
            
            for (Path path : matchedPaths) {
                double angle = path.getExtensionAngle3D(false);
                double timePoint = path.getFrame() * frameInterval;
                
                if (!Double.isNaN(angle) && !Double.isNaN(previousAngle)) {
                    double angularChange = calculateAngularChange(previousAngle, angle);
                    double timeChange = timePoint - previousTime;
                    if (timeChange > 0) {
                        double angularVelocity = angularChange / timeChange;
                        timePoints.add(timePoint);
                        angularVelocities.add(angularVelocity);
                        hasVelocityData = true;
                    }
                }
                
                previousAngle = angle;
                previousTime = timePoint;
            }
            
            if (!timePoints.isEmpty()) {
                XYSeries velocitySeries = plot.addXYSeries();
                velocitySeries.setLabel(WordUtils.capitalizeFully(neuriteId));
                final ColorRGB color = colors[colorIndex % colors.length];
                velocitySeries.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID, MarkerStyle.SQUARE));
                velocitySeries.setValues(timePoints, angularVelocities);
            }
            
            colorIndex++;
        }
        
        if (!hasVelocityData) {
            XYSeries emptySeries = plot.addXYSeries();
            emptySeries.setLabel("No angular velocity data available");
            emptySeries.setValues(List.of(0.0), List.of(0.0));
        }
        
        return new SNTChart("Angular Velocity Over Time", plot);
    }
    
    /**
     * Helper method to get matched paths for a specific neurite ID.
     */
    private List<Path> getMatchedPathsForNeurite(String neuriteId) {
        List<Path> matchedPaths = new ArrayList<>();
        
        for (Path path : paths) {
            String pathNeuriteId = extractNeuriteIdFromPath(path);
            if (neuriteId.equals(pathNeuriteId)) {
                matchedPaths.add(path);
            }
        }
        
        // Sort by frame number for temporal analysis
        matchedPaths.sort(Comparator.comparingInt(Path::getFrame));
        
        return matchedPaths;
    }
    
    /**
     * Extracts neurite ID from path name (e.g., "{neurite#1}" -> "neurite#1").
     */
    private String extractNeuriteIdFromPath(Path path) {
        String pathName = path.getName();
        if (pathName == null) return "";
        // Use regex to extract neurite ID from curly braces
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(GrowthAnalyzer.TAG_REGEX_PATTERN);
        java.util.regex.Matcher matcher = pattern.matcher(pathName);
        if (matcher.find()) {
            return matcher.group(1); // Returns content without braces
        }
        return pathName; // Fallback to full path name
    }
    
    /**
     * Calculates the angular change between two angles, handling 360° wraparound.
     * Returns the shortest angular distance.
     */
    private double calculateAngularChange(double previousAngle, double currentAngle) {
        double change = currentAngle - previousAngle;
        // Handle 360° wraparound - choose the shortest path
        if (change > 180) {
            change -= 360;
        } else if (change < -180) {
            change += 360;
        }
        return Math.abs(change); // Return absolute change for velocity calculation
    }
}