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

package sc.fiji.snt.analysis.growth;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import sc.fiji.snt.Path;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core analyzer for time-lapse growth analysis of neuronal paths.
 *
 * <p>This class provides ways to quantify  neuronal growth patterns over time, including linear growth analysis
 * (overall growth rates obtained from linear regression), phase-based growth detection (identification of distinct
 * growth phases), and analysis of retraction/elongation events (phase boundary detection).
 * It is designed to work with paths that have been matched across time frames using "{neurite #}" tags.</p>
 * <p>
 * Growth phases are classified from instantaneous growth rates relative to the overall growth rate of each neurite:
 * <ul>
 *   <li><strong>LAG:</strong> Slow positive growth (0% to base threshold)</li>
 *   <li><strong>STEADY:</strong> Medium growth (base threshold to rapid threshold)</li>
 *   <li><strong>RAPID:</strong> Fast growth (above rapid threshold)</li>
 *   <li><strong>PLATEAU:</strong> Minimal growth (within ±base threshold of zero)</li>
 *   <li><strong>RETRACTION:</strong> Negative growth (below -base threshold)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * // Create analyzer with default settings
 * GrowthAnalyzer analyzer = new GrowthAnalyzer();
 *
 * // Configure
 * analyzer.setRapidThresholdMultiple(1.8);  // threshold for rapid growth
 * analyzer.setBaseThresholdFraction(0.25);  // sensitivity for phase detection
 * analyzer.setPhaseSensitivity(0.4);        // sensitivity for phase boundary detection
 *
 * // Analyze time-matched paths
 * Collection<Path> timeLapsePaths = getMatchedPaths();
 * GrowthAnalysisResults results = analyzer.analyze(timeLapsePaths, frameInterval, "hours");
 *
 * // Access results
 * Map<String, NeuriteGrowthData> growthData = results.getNeuriteGrowthData();
 * for (NeuriteGrowthData data : growthData.values()) {
 *     System.out.printf("Neurite %s: %.2f μm/h growth rate, %d phases\n",
 *         data.getNeuriteId(), data.getLinearGrowthRate(), data.getGrowthPhases().size());
 * }
 * }</pre>
 *
 * @see GrowthAnalysisResults
 * @see NeuriteGrowthData
 * @see GrowthPhase
 */
public class GrowthAnalyzer {

    /**
     * Default base threshold as fraction of overall growth rate for PLATEAU/LAG classification
     */
    public static final double DEFAULT_BASE_THRESHOLD_FRACTION = 0.3; // 30%
    /**
     * Default rapid growth threshold as multiple of overall growth rate for RAPID classification
     */
    public static final double DEFAULT_RAPID_THRESHOLD_MULTIPLE = 1.5; // 150%
    /**
     * Default phase detection sensitivity (0.05-1.0 range, higher = more sensitive)
     */
    public static final double DEFAULT_PHASE_SENSITIVITY = 0.5;
    /**
     * Default retraction threshold percentage
     */
    public static final double DEFAULT_RETRACTION_THRESHOLD = 0.05; // 5%
    /**
     * Default minimum path length for analysis
     */
    public static final double DEFAULT_MIN_PATH_LENGTH = 5.0;
    /**
     * Default minimum time points required for analysis
     */
    public static final int DEFAULT_MIN_TIME_POINTS = 3;
    
    /**
     * Default window size fraction for change point detection
     */
    public static final double DEFAULT_WINDOW_SIZE_FRACTION = 0.125; // 1/8 of data length
    
    /**
     * Default absolute window size in frames (used when useAbsoluteWindowSize is true)
     */
    public static final int DEFAULT_ABSOLUTE_WINDOW_SIZE = 3;
    
    /**
     * Default setting for using absolute window size instead of fraction
     */
    public static final boolean DEFAULT_USE_ABSOLUTE_WINDOW_SIZE = true;
    
    /**
     * Default setting for using global mean growth rate for thresholds
     */
    public static final boolean DEFAULT_USE_GLOBAL_THRESHOLDS = true;

    // Path matching patterns
    public static final String TAG_REGEX_PATTERN = "\\{([Nn]eurite\\s*#\\d+)}";

    // Configurable parameters
    private double baseThresholdFraction;
    private double rapidThresholdMultiple;
    private double phaseSensitivity;
    private double retractionThreshold;
    private double minPathLength;
    private double windowSizeFraction;
    private int absoluteWindowSize;
    private boolean useAbsoluteWindowSize;
    private int minTimePoints;
    private boolean useGlobalThresholds;

    /**
     * Creates a new GrowthAnalyzer with default parameters.
     */
    public GrowthAnalyzer() {
        baseThresholdFraction = DEFAULT_BASE_THRESHOLD_FRACTION;
        rapidThresholdMultiple = DEFAULT_RAPID_THRESHOLD_MULTIPLE;
        phaseSensitivity = DEFAULT_PHASE_SENSITIVITY;
        retractionThreshold = DEFAULT_RETRACTION_THRESHOLD;
        minPathLength = DEFAULT_MIN_PATH_LENGTH;
        minTimePoints = DEFAULT_MIN_TIME_POINTS;
        windowSizeFraction = DEFAULT_WINDOW_SIZE_FRACTION;
        absoluteWindowSize = DEFAULT_ABSOLUTE_WINDOW_SIZE;
        useAbsoluteWindowSize = DEFAULT_USE_ABSOLUTE_WINDOW_SIZE;
        useGlobalThresholds = DEFAULT_USE_GLOBAL_THRESHOLDS;
    }

    /**
     * Creates a new GrowthAnalyzer with custom threshold parameters.
     *
     * @param plateauThresholdFraction  Fraction of overall growth rate for plateau threshold
     * @param rapidThresholdMultiple Multiple of overall growth rate for rapid threshold
     * @param phaseSensitivity       Sensitivity for change point detection (0.1-2.0, lower = more sensitive)
     */
    public GrowthAnalyzer(double plateauThresholdFraction, double rapidThresholdMultiple, double phaseSensitivity) {
        this();
        setPlateauThreshold(plateauThresholdFraction);
        setRapidThreshold(rapidThresholdMultiple);
        setPhaseSensitivity(phaseSensitivity);
    }

    /**
     * Performs growth analysis on a collection of time-matched paths. I.e., extracts matched path groups,
     * filters them based on minimum requirements, and performs linear growth analysis, phase detection,
     * and event analysis for each group.
     *
     * @param paths         Collection of paths with time-matching tags as assigned by
     *                      {@link sc.fiji.snt.plugin.PathMatcherCmd}(e.g., "{neurite#1}", "{neurite#2}")
     * @param frameInterval Time interval between consecutive frames
     * @param timeUnits     Units for time measurements (e.g., "hours", "minutes")
     * @return GrowthAnalysisResults containing all analysis data
     * @throws IllegalArgumentException if paths is null or empty, or if frameInterval ≤ 0
     */
    public GrowthAnalysisResults analyze(Collection<Path> paths, double frameInterval, String timeUnits) {
        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("Paths collection cannot be null or empty");
        if (frameInterval <= 0)
            throw new IllegalArgumentException("Frame interval must be positive");

        // Extract matched groups from path tags
        final Map<String, List<Path>> matchedGroups = extractMatchedGroups(paths);
        if (matchedGroups.isEmpty()) {
            throw new IllegalArgumentException("No matched paths found. Paths must be tagged with {neurite #1}, " +
                    "{neurite #2}, ... tags, as done by the 'Match Paths Across Time...' command.");
        }
        // Filter groups based on minimum requirements
        filterGroups(matchedGroups);
        if (matchedGroups.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "No groups meet min. requirements (length: %.1f, time points: %d)", minPathLength, minTimePoints));
        }

        // Calculate global mean growth rate if needed
        double globalMeanRate = 0.0;
        if (useGlobalThresholds) {
            globalMeanRate = calculateGlobalMeanGrowthRate(matchedGroups, frameInterval);
        }

        // Perform analysis for each group
        final Map<String, NeuriteGrowthData> growthDataMap = new HashMap<>();
        for (Map.Entry<String, List<Path>> entry : matchedGroups.entrySet()) {
            final String groupId = entry.getKey();
            final List<Path> groupPaths = entry.getValue();
            final NeuriteGrowthData growthData = analyzeGroup(groupId, groupPaths, frameInterval, globalMeanRate);
            growthDataMap.put(groupId, growthData);
        }

        return new GrowthAnalysisResults(growthDataMap, frameInterval, timeUnits);
    }

    /**
     * Calculates the global mean growth rate across all neurite groups.
     * This is used as the reference rate when useGlobalThresholds is enabled.
     *
     * @param matchedGroups Map of neurite groups with their paths
     * @param frameInterval Time interval between frames
     * @return Global mean growth rate across all neurites
     */
    private double calculateGlobalMeanGrowthRate(Map<String, List<Path>> matchedGroups, double frameInterval) {
        double totalGrowthRate = 0.0;
        int neuriteCount = 0;

        for (Map.Entry<String, List<Path>> entry : matchedGroups.entrySet()) {
            List<Path> groupPaths = entry.getValue();
            
            // Calculate linear growth rate for this neurite
            if (groupPaths.size() >= 2) {
                // Sort paths by frame
                groupPaths.sort((p1, p2) -> Integer.compare(p1.getFrame(), p2.getFrame()));
                
                // Calculate overall growth rate using simple linear approach
                Path firstPath = groupPaths.get(0);
                Path lastPath = groupPaths.get(groupPaths.size() - 1);
                
                double totalLengthChange = lastPath.getLength() - firstPath.getLength();
                double totalTime = (lastPath.getFrame() - firstPath.getFrame()) * frameInterval;
                
                if (totalTime > 0) {
                    double neuriteGrowthRate = totalLengthChange / totalTime;
                    totalGrowthRate += neuriteGrowthRate;
                    neuriteCount++;
                }
            }
        }

        return neuriteCount > 0 ? totalGrowthRate / neuriteCount : 0.0;
    }

    /**
     * Analyzes a single group of time-matched paths representing one neurite over time.
     *
     * @param groupId       Identifier for this neurite group
     * @param groupPaths    List of paths for this neurite, sorted by frame
     * @param frameInterval Time interval between frames
     * @param globalMeanRate Global mean growth rate across all neurites (used when useGlobalThresholds is true)
     * @return NeuriteGrowthData containing all analysis results for this neurite
     */
    private NeuriteGrowthData analyzeGroup(final String groupId, final List<Path> groupPaths, 
                                          final double frameInterval, final double globalMeanRate) {
        // Create time series data
        final NeuriteGrowthData data = new NeuriteGrowthData(groupId);
        for (final Path path : groupPaths) {
            data.addTimePoint(new TimePoint(path.getFrame(), path.getLength(), path));
        }
        // Analyze
        performLinearGrowthAnalysis(data, frameInterval);
        performPhaseBasedAnalysis(data, frameInterval, globalMeanRate);
        performRetractionAnalysis(data, frameInterval);
        return data;
    }

    /**
     * Performs linear growth analysis using regression to determine overall growth characteristics.
     *
     * @param data          NeuriteGrowthData to populate with linear analysis results
     * @param frameInterval Time interval between frames
     */
    private void performLinearGrowthAnalysis(final NeuriteGrowthData data, final double frameInterval) {
        final List<TimePoint> timePoints = data.getTimePoints();
        if (timePoints.size() < 2) return;

        // Calculate overall linear growth rate using regression
        final SimpleRegression regression = new SimpleRegression();
        for (TimePoint tp : timePoints) {
            regression.addData(tp.frame * frameInterval, tp.length);
        }

        data.setLinearGrowthRate(regression.getSlope());
        data.setLinearRSquared(regression.getRSquare());
        data.setLinearIntercept(regression.getIntercept());

        // Calculate additional statistics
        double initialLength = timePoints.getFirst().length;
        double finalLength = timePoints.getLast().length;
        double totalTime = (timePoints.getLast().frame - timePoints.getFirst().frame) * frameInterval;
        double totalGrowth = finalLength - initialLength;
        double averageRate = totalGrowth / totalTime;

        data.setTotalGrowth(totalGrowth);
        data.setAverageGrowthRate(averageRate);
    }

    /**
     * Performs phase-based analysis to identify distinct growth phases.
     *
     * @param data          NeuriteGrowthData to populate with phase analysis results
     * @param frameInterval Time interval between frames
     * @param globalMeanRate Global mean growth rate across all neurites (used when useGlobalThresholds is true)
     */
    private void performPhaseBasedAnalysis(final NeuriteGrowthData data, final double frameInterval, final double globalMeanRate) {
        final List<TimePoint> timePoints = data.getTimePoints();
        if (timePoints.size() < 4) return; // Need at least 4 points for phase detection

        // Calculate instantaneous growth rates and frame-independent length changes
        final List<Double> instantaneousRates = new ArrayList<>();
        final List<Double> lengthChanges = new ArrayList<>();
        for (int i = 1; i < timePoints.size(); i++) {
            TimePoint prev = timePoints.get(i - 1);
            TimePoint curr = timePoints.get(i);
            double deltaLength = curr.length - prev.length;
            double deltaTime = (curr.frame - prev.frame) * frameInterval;
            double rate = deltaTime > 0 ? deltaLength / deltaTime : 0;
            instantaneousRates.add(rate);
            lengthChanges.add(deltaLength); // frameInterval-independent for change detection
        }

        // Detect phase changes using frame-independent length changes
        final List<Integer> changePoints = detectChangePoints(lengthChanges);

        // Create growth phases
        final List<GrowthPhase> phases = new ArrayList<>();
        for (int i = 0; i < changePoints.size() - 1; i++) {
            int startIdx = changePoints.get(i);
            int endIdx = changePoints.get(i + 1);

            // Calculate phase statistics
            double startTime = timePoints.get(startIdx).frame * frameInterval;
            double endTime = timePoints.get(Math.min(endIdx, timePoints.size() - 1)).frame * frameInterval;
            double duration = endTime - startTime;

            // Calculate average rate for this phase
            double avgRate = 0;
            int rateCount = 0;
            for (int j = startIdx; j < Math.min(endIdx, instantaneousRates.size()); j++) {
                avgRate += instantaneousRates.get(j);
                rateCount++;
            }
            avgRate = rateCount > 0 ? avgRate / rateCount : 0;

            // Classify phase type using either global or per-neurite reference rate
            double referenceRate = useGlobalThresholds ? globalMeanRate : data.getAverageGrowthRate();
            GrowthPhaseType phaseType = classifyPhaseType(avgRate, referenceRate);

            double lengthChange = 0;
            if (endIdx < timePoints.size()) {
                lengthChange = timePoints.get(endIdx).length - timePoints.get(startIdx).length;
            }

            GrowthPhase phase = new GrowthPhase(startIdx, endIdx, startTime, endTime,
                    duration, avgRate, lengthChange, phaseType);
            phases.add(phase);
        }

        data.setGrowthPhases(phases);
    }

    /**
     * Performs retraction and elongation event analysis.
     *
     * @param data          NeuriteGrowthData to populate with event analysis results
     * @param frameInterval Time interval between frames
     */
    private void performRetractionAnalysis(NeuriteGrowthData data, double frameInterval) {
        List<TimePoint> timePoints = data.getTimePoints();
        List<RetractionEvent> retractions = new ArrayList<>();
        List<ElongationEvent> elongations = new ArrayList<>();

        for (int i = 1; i < timePoints.size(); i++) {
            TimePoint prev = timePoints.get(i - 1);
            TimePoint curr = timePoints.get(i);

            double lengthChange = curr.length - prev.length;
            double fractionChange = Math.abs(lengthChange) / prev.length;

            if (lengthChange < 0 && fractionChange >= retractionThreshold) {
                // Retraction event
                double startTime = prev.frame * frameInterval;
                double endTime = curr.frame * frameInterval;
                double duration = endTime - startTime;
                double rate = lengthChange / duration;

                RetractionEvent retraction = new RetractionEvent(prev.frame, curr.frame,
                        startTime, endTime, duration, prev.length, curr.length, lengthChange, rate);
                retractions.add(retraction);

            } else if (lengthChange > 0 && fractionChange >= retractionThreshold) {
                // Significant elongation event
                double startTime = prev.frame * frameInterval;
                double endTime = curr.frame * frameInterval;
                double duration = endTime - startTime;
                double rate = lengthChange / duration;

                ElongationEvent elongation = new ElongationEvent(prev.frame, curr.frame,
                        startTime, endTime, duration, prev.length, curr.length, lengthChange, rate);
                elongations.add(elongation);
            }
        }

        data.setRetractionEvents(retractions);
        data.setElongationEvents(elongations);

        // Calculate retraction statistics
        if (!retractions.isEmpty()) {
            double totalRetractionLength = retractions.stream()
                    .mapToDouble(r -> Math.abs(r.lengthChange)).sum();
            double avgRetractionRate = retractions.stream()
                    .mapToDouble(r -> Math.abs(r.rate)).average().orElse(0);

            data.setTotalRetractionLength(totalRetractionLength);
            data.setAverageRetractionRate(avgRetractionRate);
        }

        // Calculate elongation statistics
        if (!elongations.isEmpty()) {
            double totalElongationLength = elongations.stream()
                    .mapToDouble(e -> e.lengthChange).sum();
            double avgElongationRate = elongations.stream()
                    .mapToDouble(e -> e.rate).average().orElse(0);

            data.setTotalElongationLength(totalElongationLength);
            data.setAverageElongationRate(avgElongationRate);
        }
    }

    /**
     * Classifies a growth phase based on its average rate relative to the overall growth rate.
     *
     * @param phaseRate   Average growth rate for this phase
     * @param overallRate Overall growth rate for the entire neurite
     * @return GrowthPhaseType classification
     */
    private GrowthPhaseType classifyPhaseType(double phaseRate, double overallRate) {
        double baseThreshold = overallRate * baseThresholdFraction;
        double rapidThreshold = overallRate * rapidThresholdMultiple;

        if (Math.abs(phaseRate) < baseThreshold) {
            return GrowthPhaseType.PLATEAU;
        } else if (phaseRate < -baseThreshold) {
            return GrowthPhaseType.RETRACTION;
        } else if (phaseRate > rapidThreshold) {
            return GrowthPhaseType.RAPID;
        } else if (phaseRate > baseThreshold) {
            return GrowthPhaseType.STEADY;
        } else {
            return GrowthPhaseType.LAG;
        }
    }

    /**
     * Detects change points in growth rate data using multiple statistical methods. Combines mean shift detection,
     * variance change detection, and trend analysis for (hopefully) robust phase boundary identification.
     *
     * @param rates List of instantaneous growth rates
     * @return List of indices where phase changes occur
     */
    private List<Integer> detectChangePoints(List<Double> rates) {
        List<Integer> changePoints = new ArrayList<>();
        changePoints.add(0); // Always start with first point

        if (rates.size() < 4) {
            changePoints.add(rates.size());
            return changePoints;
        }

        // Use multiple detection methods and combine results
        List<Integer> meanShiftPoints = detectMeanShiftChangePoints(rates);
        List<Integer> varianceChangePoints = detectVarianceChangePoints(rates);
        List<Integer> trendChangePoints = detectTrendChangePoints(rates);

        // Combine and filter change points
        Set<Integer> allChangePoints = new TreeSet<>();
        allChangePoints.addAll(meanShiftPoints);
        allChangePoints.addAll(varianceChangePoints);
        allChangePoints.addAll(trendChangePoints);

        // Filter out points that are too close together
        List<Integer> filteredPoints = filterCloseChangePoints(new ArrayList<>(allChangePoints), rates.size());
        changePoints.addAll(filteredPoints);
        changePoints.add(rates.size()); // Always end with last point
        return changePoints;
    }

    /**
     * Gets the current base threshold fraction for phase classification.
     *
     * @return Current base threshold fraction
     */
    public double getBaseThresholdFraction() {
        return baseThresholdFraction;
    }

    /**
     * Sets the base threshold fraction for phase classification.
     *
     * <p>This parameter determines the boundary between PLATEAU and other phases.
     * Growth rates within ±(baseThreshold × overallRate) are classified as PLATEAU.</p>
     *
     * @param plateauThresholdFraction Fraction of overall growth rate (typically 0.2-0.4)
     * @throws IllegalArgumentException if fraction is not between 0.1 and 0.5
     */
    public void setPlateauThreshold(double plateauThresholdFraction) {
        if (plateauThresholdFraction < 0.1 || plateauThresholdFraction > 0.5) {
            throw new IllegalArgumentException("Base threshold fraction must be between 0.1 and 0.5");
        }
        this.baseThresholdFraction = plateauThresholdFraction;
    }

    /**
     * Gets the current rapid threshold multiple.
     *
     * @return Current rapid threshold multiple
     */
    public double getRapidThresholdMultiple() {
        return rapidThresholdMultiple;
    }

    /**
     * Sets the rapid threshold multiple for phase classification.
     *
     * <p>Growth rates above (rapidThreshold × overallRate) are classified as RAPID.</p>
     *
     * @param rapidThresholdMultiple Multiple of overall growth rate (typically 1.2-2.0)
     * @throws IllegalArgumentException if multiple is not between 1.1 and 3.0
     */
    public void setRapidThreshold(final double rapidThresholdMultiple) {
        if (rapidThresholdMultiple < 1.1 || rapidThresholdMultiple > 3.0) {
            throw new IllegalArgumentException("Rapid threshold multiple must be between 1.1 and 3.0");
        }
        this.rapidThresholdMultiple = rapidThresholdMultiple;
    }

    /**
     * Gets the current phase detection sensitivity.
     *
     * @return Current phase sensitivity
     */
    public double getPhaseSensitivity() {
        return phaseSensitivity;
    }

    /**
     * Sets the phase detection sensitivity.
     *
     * <p>Higher values make change point detection more sensitive (more phase transitions).
     * Lower values make detection less sensitive (fewer, longer phases).</p>
     *
     * @param phaseSensitivity Sensitivity parameter (0.05-1.0, higher = more sensitive)
     * @throws IllegalArgumentException if sensitivity is not between 0.05 and 1.0
     */
    public void setPhaseSensitivity(double phaseSensitivity) {
        if (phaseSensitivity < 0.05 || phaseSensitivity > 1.0) {
            throw new IllegalArgumentException("Phase sensitivity must be between 0.05 and 1.0");
        }
        this.phaseSensitivity = phaseSensitivity;
    }

    /**
     * Gets the current retraction threshold.
     *
     * @return Current retraction threshold percentage
     */
    public double getRetractionThreshold() {
        return retractionThreshold;
    }

    /**
     * Sets the retraction threshold as a fraction of length
     *
     * @param retractionThreshold Minimum decrease to classify as retraction (0.01-0.5)
     * @throws IllegalArgumentException if threshold is not between 0.01 and 0.5
     */
    public void setRetractionThreshold(double retractionThreshold) {
        if (retractionThreshold < 0.01 || retractionThreshold > 0.5) {
            throw new IllegalArgumentException("Retraction threshold must be between 0.01 and 0.5");
        }
        this.retractionThreshold = retractionThreshold;
    }

    /**
     * Sets the minimum path length for analysis inclusion.
     *
     * @param minPathLength Minimum path length in physical units
     * @throws IllegalArgumentException if length is negative
     */
    public void setMinPathLength(double minPathLength) {
        if (minPathLength < 0) {
            throw new IllegalArgumentException("Minimum path length cannot be negative");
        }
        this.minPathLength = minPathLength;
    }

    /**
     * Sets the minimum number of time points required for analysis.
     *
     * @param minTimePoints Minimum number of time points (at least 2)
     * @throws IllegalArgumentException if less than 2
     */
    public void setMinTimePoints(int minTimePoints) {
        if (minTimePoints < 2) {
            throw new IllegalArgumentException("Minimum time points must be at least 2");
        }
        this.minTimePoints = minTimePoints;
    }

    /**
     * Gets the current window size fraction for change point detection.
     *
     * @return Current window size fraction (typically 0.05-0.25)
     */
    public double getWindowSizeFraction() {
        return windowSizeFraction;
    }

    /**
     * Sets the window size fraction for change point detection.
     * 
     * <p>This parameter controls the size of the moving window used in change point detection
     * algorithms. The actual window size is calculated as: windowSize = max(minSize, dataLength × fraction)</p>
     *
     * @param windowSizeFraction Fraction of data length for window size (typically 0.05-0.25)
     * @throws IllegalArgumentException if fraction is not between 0.05 and 0.5
     */
    public void setWindowSizeFraction(double windowSizeFraction) {
        if (windowSizeFraction < 0.05 || windowSizeFraction > 0.5) {
            throw new IllegalArgumentException("Window size fraction must be between 0.05 and 0.5");
        }
        this.windowSizeFraction = windowSizeFraction;
    }

    /**
     * Gets whether global thresholds are used for phase classification.
     *
     * @return true if using global mean growth rate for thresholds, false if using per-neurite rates
     */
    public boolean isUseGlobalThresholds() {
        return useGlobalThresholds;
    }

    /**
     * Sets whether to use global thresholds for phase classification.
     * 
     * <p>This parameter determines how growth phase thresholds are calculated:</p>
     * <ul>
     *   <li><strong>Global thresholds (true)</strong>: Thresholds calculated relative to mean growth rate across all neurites</li>
     *   <li><strong>Per-neurite thresholds (false)</strong>: Thresholds calculated relative to each individual neurite's growth rate</li>
     * </ul>
     *
     * @param useGlobalThresholds true to use global mean rate, false to use per-neurite rates
     */
    public void setUseGlobalThresholds(boolean useGlobalThresholds) {
        this.useGlobalThresholds = useGlobalThresholds;
    }

    /**
     * Gets the current absolute window size in frames.
     *
     * @return Current absolute window size (used when useAbsoluteWindowSize is true)
     */
    public int getAbsoluteWindowSize() {
        return absoluteWindowSize;
    }

    /**
     * Sets the absolute window size for change point detection in frames.
     * 
     * <p>This parameter is only used when useAbsoluteWindowSize is true.</p>
     *
     * @param absoluteWindowSize Window size in frames (minimum 2)
     * @throws IllegalArgumentException if window size is less than 2
     */
    public void setAbsoluteWindowSize(int absoluteWindowSize) {
        if (absoluteWindowSize < 2) {
            throw new IllegalArgumentException("Absolute window size must be at least 2 frames");
        }
        this.absoluteWindowSize = absoluteWindowSize;
    }

    /**
     * Gets whether absolute window size is used instead of fractional window size.
     *
     * @return true if using absolute window size, false if using fractional window size
     */
    public boolean isUseAbsoluteWindowSize() {
        return useAbsoluteWindowSize;
    }

    /**
     * Sets whether to use absolute window size instead of fractional window size.
     * 
     * <p>Window size modes:</p>
     * <ul>
     *   <li><strong>Absolute (true)</strong>: Fixed window size in frames, consistent across all datasets</li>
     *   <li><strong>Fractional (false)</strong>: Window size as percentage of data length, adaptive to dataset size</li>
     * </ul>
     *
     * @param useAbsoluteWindowSize true to use absolute window size, false to use fractional
     */
    public void setUseAbsoluteWindowSize(boolean useAbsoluteWindowSize) {
        this.useAbsoluteWindowSize = useAbsoluteWindowSize;
    }

    /**
     * Extracts matched groups from path tags using regex patterns.
     */
    private Map<String, List<Path>> extractMatchedGroups(Collection<Path> paths) {
        final Map<String, List<Path>> groups = new HashMap<>();
        Pattern pattern = Pattern.compile(TAG_REGEX_PATTERN);
        for (final Path path : paths) {
            final Matcher matcher = pattern.matcher(path.getName());
            if (matcher.find()) {
                final String groupId = matcher.group(1); // "neurite#1" in "{neurite#1}"
                groups.computeIfAbsent(groupId, k -> new ArrayList<>()).add(path);
            }
        }

        // Sort paths within each group by frame
        groups.values().forEach(pathList -> pathList.sort(Comparator.comparingInt(Path::getFrame)));
        return groups;
    }

    /**
     * Filters groups based on minimum requirements.
     */
    private void filterGroups(Map<String, List<Path>> matchedGroups) {
        matchedGroups.entrySet().removeIf(entry -> {
            List<Path> groupPaths = entry.getValue();

            // Check minimum time points
            if (groupPaths.size() < minTimePoints) {
                return true;
            }

            // Check minimum path length (at least one path must meet criteria)
            boolean hasValidLength = groupPaths.stream()
                    .anyMatch(p -> p.getLength() >= minPathLength);

            return !hasValidLength;
        });
    }

    /**
     * Calculates the appropriate window size based on current settings.
     * 
     * @param dataSize Size of the data array
     * @param minSize Minimum window size for this detection method
     * @return Calculated window size
     */
    private int calculateWindowSize(int dataSize, int minSize) {
        return calculateWindowSize(dataSize, minSize, 1.0);
    }
    
    /**
     * Calculates the appropriate window size based on current settings with scaling factor.
     * 
     * @param dataSize Size of the data array
     * @param minSize Minimum window size for this detection method
     * @param scaleFactor Scaling factor for fractional window size (e.g., 0.75 for smaller windows)
     * @return Calculated window size
     */
    private int calculateWindowSize(int dataSize, int minSize, double scaleFactor) {
        if (useAbsoluteWindowSize) {
            // Use absolute window size, but respect minimum requirements
            return Math.max(minSize, absoluteWindowSize);
        } else {
            // Use fractional window size (existing behavior)
            return Math.max(minSize, (int) (dataSize * windowSizeFraction * scaleFactor));
        }
    }

    private List<Integer> detectMeanShiftChangePoints(List<Double> rates) {
        List<Integer> changePoints = new ArrayList<>();
        int windowSize = calculateWindowSize(rates.size(), 3);

        for (int i = windowSize; i < rates.size() - windowSize; i++) {
            double leftMean = calculateMean(rates.subList(i - windowSize, i));
            double rightMean = calculateMean(rates.subList(i, i + windowSize));
            double combinedStd = calculateStandardDeviation(rates.subList(i - windowSize, i + windowSize));

            double meanDifference = Math.abs(rightMean - leftMean);
            // Transform 0.05-1.0 sensitivity to threshold multiplier (higher sensitivity = lower threshold)
            double threshold = combinedStd * (1.05 - phaseSensitivity);

            if (meanDifference > threshold && combinedStd > 0) {
                changePoints.add(i);
            }
        }

        return changePoints;
    }

    private List<Integer> detectVarianceChangePoints(List<Double> rates) {
        List<Integer> changePoints = new ArrayList<>();
        int windowSize = calculateWindowSize(rates.size(), 2, 0.75); // Slightly smaller for variance detection

        for (int i = windowSize; i < rates.size() - windowSize; i++) {
            double leftVariance = calculateVariance(rates.subList(i - windowSize, i));
            double rightVariance = calculateVariance(rates.subList(i, i + windowSize));
            double combinedVariance = calculateVariance(rates.subList(i - windowSize, i + windowSize));

            double avgIndividualVariance = (leftVariance + rightVariance) / 2.0;
            double changeScore = combinedVariance / (avgIndividualVariance + 1e-6);

            // Transform 0.05-1.0 sensitivity to threshold (higher sensitivity = lower threshold)
            double threshold = 1.55 - phaseSensitivity;
            if (changeScore > threshold) {
                changePoints.add(i);
            }
        }

        return changePoints;
    }

    private List<Integer> detectTrendChangePoints(List<Double> rates) {
        List<Integer> changePoints = new ArrayList<>();
        int windowSize = calculateWindowSize(rates.size(), 3);

        for (int i = windowSize; i < rates.size() - windowSize; i++) {
            double leftSlope = calculateSlope(rates.subList(i - windowSize, i));
            double rightSlope = calculateSlope(rates.subList(i, i + windowSize));

            double slopeDifference = Math.abs(rightSlope - leftSlope);
            double dataRange = calculateRange(rates);
            // Transform 0.05-1.0 sensitivity to threshold multiplier (higher sensitivity = lower threshold)
            double threshold = (dataRange / rates.size()) * (1.05 - phaseSensitivity);

            if (slopeDifference > threshold) {
                changePoints.add(i);
            }
        }

        return changePoints;
    }

    private List<Integer> filterCloseChangePoints(List<Integer> changePoints, int dataSize) {
        if (changePoints.isEmpty()) return changePoints;

        List<Integer> filtered = new ArrayList<>();
        int minDistance = Math.max(2, dataSize / 20);

        int lastPoint = -minDistance;
        for (int point : changePoints) {
            if (point - lastPoint >= minDistance) {
                filtered.add(point);
                lastPoint = point;
            }
        }

        return filtered;
    }

    // Statistical helper methods
    private double calculateMean(final List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double calculateVariance(final List<Double> values) {
        if (values.size() < 2) return 0;
        final double mean = calculateMean(values);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }

    private double calculateStandardDeviation(final List<Double> values) {
        return Math.sqrt(calculateVariance(values));
    }

    private double calculateSlope(final List<Double> values) {
        if (values.size() < 2) return 0.0;
        int n = values.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double y = values.get(i);
            sumX += i;
            sumY += y;
            sumXY += (double) i * y;
            sumX2 += (double) i * (double) i;
        }
        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-10) return 0.0;
        return (n * sumXY - sumX * sumY) / denominator;
    }

    private double calculateRange(final List<Double> values) {
        if (values.isEmpty()) return 0.0;
        final double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        final double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return max - min;
    }

    // DATA CLASSES

    /**
     * Types of growth phases
     */
    public enum GrowthPhaseType {
        /**
         * Retraction phase - negative growth
         */
        RETRACTION,
        /**
         * Lag phase - slow positive growth
         */
        LAG,
        /**
         * Plateau phase - minimal growth
         */
        PLATEAU,
        /**
         * Steady phase - medium growth
         */
        STEADY,
        /**
         * Rapid phase - fast growth
         */
        RAPID
    }

    /**
     * Represents a single time point measurement
     */
    public record TimePoint(int frame, double length, Path path) {
    }

    /**
     * Represents a growth phase with specific characteristics
     */
    public record GrowthPhase(int startIndex, int endIndex, double startTime, double endTime, double duration,
                              double averageRate, double lengthChange, GrowthPhaseType type) {
    }

    /**
     * Represents a retraction event
     */
    public record RetractionEvent(int startFrame, int endFrame, double startTime, double endTime, double duration,
                                  double startLength, double endLength, double lengthChange, double rate) {
    }

    /**
     * Represents an elongation event
     */
    public record ElongationEvent(int startFrame, int endFrame, double startTime, double endTime, double duration,
                                  double startLength, double endLength, double lengthChange, double rate) {
    }
}