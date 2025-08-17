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

import sc.fiji.snt.analysis.growth.GrowthAnalyzer.GrowthPhase;
import sc.fiji.snt.analysis.growth.GrowthAnalyzer.GrowthPhaseType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Container class for growth analysis results from {@link GrowthAnalyzer}, serving as the
 * primary interface for accessing growth analysis results.</p>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * GrowthAnalyzer analyzer = new GrowthAnalyzer();
 * GrowthAnalysisResults results = analyzer.analyze(paths, frameInterval, "hours");
 *
 * // Access individual neurite data
 * for (NeuriteGrowthData data : results.getNeuriteGrowthData().values()) {
 *     System.out.printf("Neurite %s: %.2f μm/h, %d phases\n",
 *         data.getNeuriteId(), data.getLinearGrowthRate(), data.getGrowthPhases().size());
 * }
 *
 * // Get summary statistics
 * GrowthSummaryStatistics summary = results.getSummaryStatistics();
 * System.out.printf("Average growth rate: %.2f ± %.2f μm/h\n",
 *     summary.getMeanGrowthRate(), summary.getStdGrowthRate());
 *
 * // Analyze phase distribution
 * Map<GrowthPhaseType, Integer> phaseDistribution = results.getPhaseDistribution();
 * System.out.printf("Rapid phases: %d (%.1f%%)\n",
 *     phaseDistribution.get(GrowthPhaseType.RAPID),
 *     results.getPhasePercentage(GrowthPhaseType.RAPID));
 * }</pre>
 *
 * @see GrowthAnalyzer
 * @see NeuriteGrowthData
 */
public class GrowthAnalysisResults {

    private final Map<String, NeuriteGrowthData> neuriteGrowthData;
    private final double frameInterval;
    private final String timeUnits;

    // Cached summary statistics
    private GrowthSummaryStatistics summaryStats;
    private Map<GrowthPhaseType, Integer> phaseDistribution;

    /**
     * Creates a new GrowthAnalysisResults container.
     *
     * @param neuriteGrowthData Map of neurite IDs to their growth analysis data
     * @param frameInterval     Time interval between consecutive frames
     * @param timeUnits         Units for time measurements
     */
    public GrowthAnalysisResults(Map<String, NeuriteGrowthData> neuriteGrowthData,
                                 double frameInterval, String timeUnits) {
        this.neuriteGrowthData = new HashMap<>(neuriteGrowthData);
        this.frameInterval = frameInterval;
        this.timeUnits = timeUnits;
    }

    /**
     * Gets the growth analysis data for all analyzed neurites.
     *
     * @return Unmodifiable map of neurite IDs to their growth data
     */
    public Map<String, NeuriteGrowthData> getNeuriteGrowthData() {
        return Collections.unmodifiableMap(neuriteGrowthData);
    }

    /**
     * Gets the growth analysis data for a specific neurite.
     *
     * @param neuriteId Identifier of the neurite
     * @return NeuriteGrowthData for the specified neurite, or null if not found
     */
    public NeuriteGrowthData getNeuriteGrowthData(String neuriteId) {
        return neuriteGrowthData.get(neuriteId);
    }

    /**
     * Gets the frame interval used in the analysis.
     *
     * @return Frame interval in time units
     */
    public double getFrameInterval() {
        return frameInterval;
    }

    /**
     * Gets the time units used in the analysis.
     *
     * @return Time units string (e.g., "hours", "minutes")
     */
    public String getTimeUnits() {
        return timeUnits;
    }

    /**
     * Gets the number of analyzed neurites.
     *
     * @return Number of neurites in the analysis
     */
    public int getNeuriteCount() {
        return neuriteGrowthData.size();
    }

    /**
     * Gets the total number of growth phases across all neurites.
     *
     * @return Total number of detected growth phases
     */
    public int getTotalPhaseCount() {
        return neuriteGrowthData.values().stream()
                .mapToInt(data -> data.getGrowthPhases().size())
                .sum();
    }

    /**
     * Gets summary statistics for all analyzed neurites.
     *
     * @return GrowthSummaryStatistics containing population-level metrics
     */
    public GrowthSummaryStatistics getSummaryStatistics() {
        if (summaryStats == null) {
            summaryStats = calculateSummaryStatistics();
        }
        return summaryStats;
    }

    /**
     * Gets the distribution of growth phase types across all neurites.
     *
     * @return Map of phase types to their occurrence counts
     */
    public Map<GrowthPhaseType, Integer> getPhaseDistribution() {
        if (phaseDistribution == null) {
            phaseDistribution = calculatePhaseDistribution();
        }
        return Collections.unmodifiableMap(phaseDistribution);
    }

    /**
     * Gets the percentage of phases that are of a specific type.
     *
     * @param phaseType The growth phase type to query
     * @return Percentage of phases of the specified type (0-100)
     */
    public double getPhasePercentage(final GrowthPhaseType phaseType) {
        Map<GrowthPhaseType, Integer> distribution = getPhaseDistribution();
        int totalPhases = getTotalPhaseCount();
        if (totalPhases == 0) return 0.0;
        int phaseCount = distribution.getOrDefault(phaseType, 0);
        return (phaseCount * 100.0) / totalPhases;
    }

    /**
     * Gets neurites that show a specific growth pattern or characteristic.
     *
     * @param criterion Filtering criterion for neurite selection
     * @return List of neurite IDs matching the criterion
     */
    public List<String> getNeuritesByPattern(final NeuriteSelectionCriterion criterion) {
        return neuriteGrowthData.entrySet().stream()
                .filter(entry -> criterion.matches(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Creates a map suitable for generating phase distribution visualizations.
     *
     * @return Map with phase type names as keys and counts as values
     */
    public Map<String, Double> getPhaseDistributionForVisualization() {
        final Map<GrowthPhaseType, Integer> distribution = getPhaseDistribution();
        final Map<String, Double> result = new HashMap<>();
        for (Map.Entry<GrowthPhaseType, Integer> entry : distribution.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue().doubleValue());
        }
        return result;
    }

    /**
     * Creates a map of neurite phases suitable for timeline visualization.
     *
     * @return Map of neurite IDs to their growth phases
     */
    public Map<String, List<GrowthPhase>> getNeuritePhases() {
        final Map<String, List<GrowthPhase>> result = new TreeMap<>(); // sorted by keys
        for (Map.Entry<String, NeuriteGrowthData> entry : neuriteGrowthData.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getGrowthPhases());
        }
        return result;
    }

    /**
     * Calculates summary statistics across all neurites.
     */
    private GrowthSummaryStatistics calculateSummaryStatistics() {
        if (neuriteGrowthData.isEmpty()) {
            return new GrowthSummaryStatistics();
        }
        List<Double> growthRates = new ArrayList<>();
        List<Double> totalGrowths = new ArrayList<>();
        List<Double> rSquaredValues = new ArrayList<>();
        int totalRetractions = 0;
        int totalElongations = 0;
        for (NeuriteGrowthData data : neuriteGrowthData.values()) {
            growthRates.add(data.getLinearGrowthRate());
            totalGrowths.add(data.getTotalGrowth());
            rSquaredValues.add(data.getLinearRSquared());
            totalRetractions += data.getRetractionEvents().size();
            totalElongations += data.getElongationEvents().size();
        }
        return new GrowthSummaryStatistics(
                calculateMean(growthRates),
                calculateStandardDeviation(growthRates),
                calculateMean(totalGrowths),
                calculateStandardDeviation(totalGrowths),
                calculateMean(rSquaredValues),
                totalRetractions,
                totalElongations,
                neuriteGrowthData.size()
        );
    }

    /**
     * Calculates the distribution of growth phase types.
     */
    private Map<GrowthPhaseType, Integer> calculatePhaseDistribution() {
        final Map<GrowthPhaseType, Integer> distribution = new HashMap<>();
        // Initialize all phase types to zero
        for (GrowthPhaseType type : GrowthPhaseType.values()) {
            distribution.put(type, 0);
        }
        // Count phases from all neurites
        for (NeuriteGrowthData data : neuriteGrowthData.values()) {
            for (GrowthPhase phase : data.getGrowthPhases()) {
                distribution.put(phase.type(), distribution.get(phase.type()) + 1);
            }
        }

        return distribution;
    }

    // Statistical helper methods
    private double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double calculateStandardDeviation(List<Double> values) {
        if (values.size() < 2) return 0.0;

        double mean = calculateMean(values);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);

        return Math.sqrt(variance);
    }

    // ========================================================================
    // INNER CLASSES AND INTERFACES
    // ========================================================================

    /**
     * Interface for defining neurite selection criteria.
     */
    @FunctionalInterface
    public interface NeuriteSelectionCriterion {
        // Predefined criteria
        static NeuriteSelectionCriterion hasRetractions() {
            return data -> !data.getRetractionEvents().isEmpty();
        }

        static NeuriteSelectionCriterion hasRapidGrowth() {
            return data -> data.getGrowthPhases().stream()
                    .anyMatch(phase -> phase.type() == GrowthPhaseType.RAPID);
        }

        static NeuriteSelectionCriterion highGrowthRate(double threshold) {
            return data -> data.getLinearGrowthRate() > threshold;
        }

        boolean matches(NeuriteGrowthData data);
    }

    /**
     * Container for summary statistics across all analyzed neurites.
     */
    public record GrowthSummaryStatistics(double meanGrowthRate, double stdGrowthRate, double meanTotalGrowth,
                                          double stdTotalGrowth, double meanRSquared, int totalRetractions,
                                          int totalElongations, int neuriteCount) {
        public GrowthSummaryStatistics() {
            this(0, 0, 0, 0, 0, 0, 0, 0);
        }

        public double getRetractionFrequency() {
            return neuriteCount > 0 ? (double) totalRetractions / neuriteCount : 0.0;
        }

        public double getElongationFrequency() {
            return neuriteCount > 0 ? (double) totalElongations / neuriteCount : 0.0;
        }
    }
}