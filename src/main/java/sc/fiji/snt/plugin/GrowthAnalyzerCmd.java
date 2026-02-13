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

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Growth Phase Thresholds:")
    private String HEADER1;

    @Parameter(label = "Threshold for 'Lag'/'Plateau' (%)", min = "10", max = "50", stepSize = "1",
            style = "slider,format:0", description = """
            Defines the minimum growth rate threshold for classifying growth phases.
            Growth rates below this percentage are classified as 'lag' or 'plateau'.
            Must be between 10 and 50%""")
    private double lagPlateauThresholdPercent = GrowthAnalyzer.DEFAULT_BASE_THRESHOLD_FRACTION * 100;

    @Parameter(label = "Threshold for 'Rapid' (%)", min = "110", max = "300", stepSize = "1",
            style = "slider,format:0", description = """
            Growth rates that are this percentage above the overall rate are classified as 'Rapid'.
            Must be between 110 and 300%""")
    private double rapidThresholdPercent = GrowthAnalyzer.DEFAULT_RAPID_THRESHOLD_MULTIPLE * 100;

    @Parameter(label = "Calculation", style = "radioButtonHorizontal",
            choices = {"Global", "Per-neurite"}, description = """
            Global: Thresholds calculated relative to mean growth rate of all neurites
            Per-neurite: Thresholds calculated relative to each neurite's individual growth rate""")
    private String thresholdChoice = "Global"; // Default to false (per-neurite)

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Phase Boundary Detection:")
    private String HEADER2;

    @Parameter(label = "Sensitivity", min = "0.05", max = "1.0", stepSize = "0.05",
            style = "slider,format:0.00", description = """
            Sensitivity for detecting growth phase changes
            Higher values = more sensitive (detect more phases)
            Lower values = less sensitive (detect fewer, major phases)""")
    private double phaseSensitivity = GrowthAnalyzer.DEFAULT_PHASE_SENSITIVITY;

    @Parameter(label = "Window size (no. of frames)", min = "2", max = "40", stepSize = "1",
            style = "slider,format:0", description = """
                    Fixed window size in frames for phase boundary detection.
                    Minimum value is 2 frames.""")
    private int absoluteWindowSize = GrowthAnalyzer.DEFAULT_ABSOLUTE_WINDOW_SIZE;

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Retractions:")
    private String HEADER3;

    @Parameter(label = "Threshold for retraction length (%)", min = "1", max = "50", stepSize = "1",
            style = "slider,format:0", description = """
                    Minimum length decrease to classify as retraction.
                    Must be between 1 and 50%""")
    private double retractionThresholdPercent = GrowthAnalyzer.DEFAULT_RETRACTION_THRESHOLD * 100;
    
    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Filtering Options:")
    private String HEADER4;

    @Parameter(label = "Minimum duration (no. of frames)", min = "2",
            description = "Neurites persisting less than this no. of frames are ignored from analysis")
    private int minTimePoints = GrowthAnalyzer.DEFAULT_MIN_TIME_POINTS;

    // Filtering parameters
    @Parameter(label = "Minimum length (physical units)", min = "0",
            description = "Neurites shorter than this length at any given frame are ignored from analysis")
    private double minPathLength = GrowthAnalyzer.DEFAULT_MIN_PATH_LENGTH;

    @Parameter(required = false, label = "Defaults", callback = "resetInputParameters")
    private Button reset;

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
        thresholdChoice = (GrowthAnalyzer.DEFAULT_USE_GLOBAL_THRESHOLDS) ? "Global" : "Per-neurite";
        phaseSensitivity = GrowthAnalyzer.DEFAULT_PHASE_SENSITIVITY;
        absoluteWindowSize = GrowthAnalyzer.DEFAULT_ABSOLUTE_WINDOW_SIZE;
        retractionThresholdPercent = GrowthAnalyzer.DEFAULT_RETRACTION_THRESHOLD * 100;
        minTimePoints = 2;
        minPathLength = 0;
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
            analyzer.setUseAbsoluteWindowSize(true);
            analyzer.setAbsoluteWindowSize(absoluteWindowSize);
            analyzer.setUseGlobalThresholds(thresholdChoice == null || thresholdChoice.toLowerCase().contains("global"));
            // Configure filtering
            analyzer.setMinTimePoints(Math.max(2, minTimePoints));
            analyzer.setMinPathLength(Math.max(0, minPathLength));
            // Perform the analysis
            analysisResults = analyzer.analyze(paths, frameInterval, timeUnits);
            if (analysisResults.getNeuriteGrowthData().values().stream().allMatch(ngd ->
                            ngd.getTimePointCount() == 1)) {
                error("Only one time point has been parsed. Make sure all the paths to be analyzed have been " +
                        "selected, and that there are enough data points for growth analysis.");
                resetUI();
                return;
            }
            // Generate visualizations and reports
            assembleAndDisplayTable();
            final boolean hasPhases = analysisResults.getNeuriteGrowthData().values()
                    .stream().anyMatch( ngd -> !ngd.getGrowthPhases().isEmpty());
            assembleAndDisplayPlots(hasPhases);
            if (!hasPhases) {
                msg("No growth phases detected. Perhaps thresholds need adjustments!?", "No Growth Phases");
            }
        } catch (final IllegalArgumentException e) {
            error(e.getMessage());
        } catch (final Exception e) {
            error("Analysis failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            resetUI();
        }
    }

    private void assembleAndDisplayTable() {
        final SNTTable table = new SNTTable();
        for (final NeuriteGrowthData data : analysisResults.getNeuriteGrowthData().values()) {
            table.insertRow(data.getNeuriteId());
            table.appendToLastRow("Time unit", timeUnits);
            table.appendToLastRow("Length unit", lengthUnits);
            table.appendToLastRow("Frame Interval", frameInterval);
            if (!data.getTimePoints().isEmpty()) {
                table.appendToLastRow("First frame", data.getTimePoints().getFirst().frame());
                table.appendToLastRow("Last frame", data.getTimePoints().getLast().frame());
                table.appendToLastRow("No. of time points", data.getTimePointCount());
                table.appendToLastRow("Duration", data.getTotalDuration(frameInterval));

            }
            table.appendToLastRow("Initial length", data.getInitialLength());
            table.appendToLastRow("Final length", data.getFinalLength());
            table.appendToLastRow("Total growth", data.getTotalGrowth());
            table.appendToLastRow("Net growth", data.getNetGrowth());
            table.appendToLastRow("Linear rate (Lr)", data.getLinearGrowthRate());
            table.appendToLastRow("Lr R squared", data.getLinearRSquared());
            table.appendToLastRow("Average growth rate", data.getAverageGrowthRate());
            table.appendToLastRow("Average elongation rate", data.getAverageElongationRate());
            table.appendToLastRow("Average retraction rate", data.getAverageRetractionRate());
            table.appendToLastRow("Total elongation length", data.getTotalElongationLength());
            table.appendToLastRow("Total retraction length", data.getTotalRetractionLength());
            table.appendToLastRow("No. of elongation events", data.getElongationEvents().size());
            table.appendToLastRow("No. of retraction events", data.getRetractionEvents().size());
            table.appendToLastRow("No. of growth phases", data.getGrowthPhases().size());
            for (final GrowthAnalyzer.GrowthPhaseType type : GrowthAnalyzer.GrowthPhaseType.values()) {
                table.appendToLastRow(WordUtils.capitalizeFully(type.toString()) + " duration",
                        data.getPhaseDuration(type));
            }
        }
        table.summarize();
        table.show("SNT_Growth_Analysis");
    }

    /**
     * Assembles and displays all visualization plots.
     */
    private void assembleAndDisplayPlots(final boolean includePhases) {
        final List<SNTChart> charts = new ArrayList<>();
        charts.add(assembleLengthVsTimePlot());
        charts.add(assembleDirectionalAnalysisPlot());
        charts.add(assembleAngularVelocityPlot());
        if (includePhases) {
            charts.add(assembleGrowthPhaseRingPlot());
            charts.add(assembleGrowthPhaseDurationRingPlot());
            final String title = String.format("Growth Phase Timelines (N=%d Neurites)", analysisResults.getNeuriteCount());
            charts.add(new SNTChart(title, AnalysisUtils.createTimeline(analysisResults.getNeuritePhases(), timeUnits)));
        }
        // Display charts in tiled layout
        SNTChart.tile(charts);
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
        final List<Path> matchedPaths = new ArrayList<>();
        for (final Path path : paths) {
            final String pathNeuriteId = extractNeuriteIdFromPath(path);
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
