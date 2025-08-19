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

import sc.fiji.snt.analysis.growth.GrowthAnalyzer.*;

import java.util.*;

/**
 * Data container for growth analysis results of a single neurite over time.
 *
 * <p>This class stores all analysis results for an individual neurite, including time series data,
 * linear growth statistics, detected growth phases, and retraction/elongation events. It provides
 * convenient access methods and derived statistics for measurements of neurite growth behavior.</p>
 *
 * <p><strong>Key Data Categories:</strong></p>
 * <ul>
 *   <li><strong>Time Series:</strong> Raw measurements at each time point</li>
 *   <li><strong>Linear Analysis:</strong> Overall growth rate, R², and regression statistics</li>
 *   <li><strong>Phase Analysis:</strong> Detected growth phases with classifications</li>
 *   <li><strong>Event Analysis:</strong> Retraction and elongation events with statistics</li>
 *   <li><strong>Derived Metrics:</strong> Net growth</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * // Access from analysis results
 * GrowthAnalysisResults results = analyzer.analyze(paths, frameInterval, "hours");
 * NeuriteGrowthData neurite1 = results.getNeuriteGrowthData("neurite#1");
 *
 * // Basic growth metrics
 * System.out.printf("Growth rate: %.2f μm/h (R² = %.3f)\n",
 *     neurite1.getLinearGrowthRate(), neurite1.getLinearRSquared());
 *
 * // Phase analysis
 * List<GrowthPhase> phases = neurite1.getGrowthPhases();
 * System.out.printf("Detected %d growth phases:\n", phases.size());
 * for (GrowthPhase phase : phases) {
 *     System.out.printf("  %s: %.1f h duration, %.2f μm/h rate\n",
 *         phase.type, phase.duration, phase.averageRate);
 * }
 *
 * // Event analysis
 * if (neurite1.hasRetractionEvents()) {
 *     System.out.printf("Retraction events: %d (total: %.1f μm)\n",
 *         neurite1.getRetractionEvents().size(), neurite1.getTotalRetractionLength());
 * }
 * }
 * }</pre>
 *
 * @see GrowthAnalyzer
 * @see GrowthAnalysisResults
 */
public class NeuriteGrowthData {

    // Core identification
    private final String neuriteId;

    // Time series data
    private final List<TimePoint> timePoints;
    private final List<GrowthPhase> growthPhases;
    private final List<RetractionEvent> retractionEvents;
    private final List<ElongationEvent> elongationEvents;

    // Linear growth analysis results
    private double linearGrowthRate = 0;
    private double linearRSquared = 0;
    private double linearIntercept = 0;
    private double totalGrowth = 0;
    private double averageGrowthRate = 0;

    // Event analysis statistics
    private double totalRetractionLength = 0;
    private double averageRetractionRate = 0;
    private double totalElongationLength = 0;
    private double averageElongationRate = 0;

    /**
     * Creates a new NeuriteGrowthData container for the specified neurite.
     *
     * @param neuriteId Unique identifier for this neurite
     */
    public NeuriteGrowthData(String neuriteId) {
        this.neuriteId = neuriteId;
        this.timePoints = new ArrayList<>();
        this.growthPhases = new ArrayList<>();
        this.retractionEvents = new ArrayList<>();
        this.elongationEvents = new ArrayList<>();
    }

    /**
     * Gets the unique identifier for this neurite.
     *
     * @return the neurite identifier
     */
    public String getNeuriteId() {
        return neuriteId;
    }

    /**
     * Gets the time series data points for this neurite.
     *
     * @return Unmodifiable list of time points
     */
    public List<TimePoint> getTimePoints() {
        return Collections.unmodifiableList(timePoints);
    }

    /**
     * Gets the detected growth phases for this neurite.
     *
     * @return Unmodifiable list of growth phases
     */
    public List<GrowthPhase> getGrowthPhases() {
        return Collections.unmodifiableList(growthPhases);
    }

    void setGrowthPhases(List<GrowthPhase> phases) {
        this.growthPhases.clear();
        this.growthPhases.addAll(phases);
    }

    /**
     * Gets the detected retraction events for this neurite.
     *
     * @return Unmodifiable list of retraction events
     */
    public List<RetractionEvent> getRetractionEvents() {
        return Collections.unmodifiableList(retractionEvents);
    }

    void setRetractionEvents(List<RetractionEvent> events) {
        this.retractionEvents.clear();
        this.retractionEvents.addAll(events);
    }

    /**
     * Gets the detected elongation events for this neurite.
     *
     * @return Unmodifiable list of elongation events
     */
    public List<ElongationEvent> getElongationEvents() {
        return Collections.unmodifiableList(elongationEvents);
    }

    void setElongationEvents(List<ElongationEvent> events) {
        this.elongationEvents.clear();
        this.elongationEvents.addAll(events);
    }

    /**
     * Gets the linear growth rate from regression analysis.
     *
     * @return Growth rate in length units per time unit
     */
    public double getLinearGrowthRate() {
        return linearGrowthRate;
    }

    void setLinearGrowthRate(double rate) {
        this.linearGrowthRate = rate;
    }

    /**
     * Gets the R-squared value from linear regression.
     *
     * @return R-squared value (0-1, higher indicates better linear fit)
     */
    public double getLinearRSquared() {
        return linearRSquared;
    }

    void setLinearRSquared(double rSquared) {
        this.linearRSquared = rSquared;
    }

    /**
     * Gets the y-intercept from linear regression.
     *
     * @return Y-intercept in length units
     */
    public double getLinearIntercept() {
        return linearIntercept;
    }

    void setLinearIntercept(double intercept) {
        this.linearIntercept = intercept;
    }

    /**
     * Gets the total growth (final length - initial length).
     *
     * @return Total growth in length units
     */
    public double getTotalGrowth() {
        return totalGrowth;
    }

    void setTotalGrowth(double growth) {
        this.totalGrowth = growth;
    }

    /**
     * Gets the average growth rate (total growth / total time).
     *
     * @return Average growth rate in length units per time unit
     */
    public double getAverageGrowthRate() {
        return averageGrowthRate;
    }

    void setAverageGrowthRate(double rate) {
        this.averageGrowthRate = rate;
    }

    /**
     * Gets the total length lost to retraction events.
     *
     * @return Total retraction length in length units
     */
    public double getTotalRetractionLength() {
        return totalRetractionLength;
    }

    // PHASE ANALYSIS METHODS

    void setTotalRetractionLength(double length) {
        this.totalRetractionLength = length;
    }

    /**
     * Gets the average rate of retraction events.
     *
     * @return Average retraction rate in length units per time unit
     */
    public double getAverageRetractionRate() {
        return averageRetractionRate;
    }

    void setAverageRetractionRate(double rate) {
        this.averageRetractionRate = rate;
    }

    /**
     * Gets the total length gained from elongation events.
     *
     * @return Total elongation length in length units
     */
    public double getTotalElongationLength() {
        return totalElongationLength;
    }

    // PATTERN DETECTION METHODS

    void setTotalElongationLength(double length) {
        this.totalElongationLength = length;
    }

    /**
     * Gets the average rate of elongation events.
     *
     * @return Average elongation rate in length units per time unit
     */
    public double getAverageElongationRate() {
        return averageElongationRate;
    }

    // PACKAGE-PRIVATE SETTERS (for GrowthAnalyzer)

    void setAverageElongationRate(double rate) {
        this.averageElongationRate = rate;
    }

    /**
     * Gets the net growth accounting for retractions.
     *
     * @return Net growth (total growth - total retraction length)
     */
    public double getNetGrowth() {
        return totalGrowth - totalRetractionLength;
    }

    /**
     * Gets the initial length of the neurite.
     *
     * @return Initial length, i.e., at the first time point
     */
    public double getInitialLength() {
        return timePoints.isEmpty() ? 0 : timePoints.getFirst().length();
    }

    /**
     * Gets the final length of the neurite.
     *
     * @return Final length, i.e., at the last time point
     */
    public double getFinalLength() {
        return timePoints.isEmpty() ? 0 : timePoints.getLast().length();
    }

    /**
     * Gets the total duration of the time series.
     *
     * @param frameInterval Time interval between frames
     * @return Total duration in time units
     */
    public double getTotalDuration(final double frameInterval) {
        if (timePoints.size() < 2) return 0;
        return (timePoints.getLast().frame() - timePoints.getFirst().frame()) * frameInterval;
    }

    /**
     * Gets the number of unique time points in the series.
     * This counts distinct frame numbers, so duplicate time points for the same frame are counted only once.
     *
     * @return Number of unique time points
     */
    public int getTimePointCount() {
        if (timePoints.isEmpty()) return 0;
        
        // Count unique frame numbers to handle cases where duplicate time points exist
        return (int) timePoints.stream()
                .mapToInt(TimePoint::frame)
                .distinct()
                .count();
    }
    
    /**
     * Gets the total number of time point entries in the series (including duplicates).
     * This returns the raw size of the time points list.
     *
     * @return Total number of time point entries
     */
    public int getTimePointEntryCount() {
        return timePoints.size();
    }

    /**
     * Gets the total duration of phases of a specific type.
     *
     * @param phaseType The growth phase type
     * @return Total duration of phases of the specified type
     */
    public double getPhaseDuration(final GrowthPhaseType phaseType) {
        return growthPhases.stream()
                .filter(phase -> phase.type() == phaseType)
                .mapToDouble(GrowthPhase::duration).sum();
    }

    /**
     * Gets the percentage of time spent in a specific phase type.
     *
     * @param phaseType     The growth phase type
     * @param frameInterval Time interval between frames
     * @return Percentage of time in the specified phase type (0-100)
     */
    public double getPhasePercentage(final GrowthPhaseType phaseType, final double frameInterval) {
        double totalDuration = getTotalDuration(frameInterval);
        if (totalDuration == 0) return 0;
        return (getPhaseDuration(phaseType) / totalDuration) * 100;
    }

    /**
     * Gets the longest phase of a specific type.
     *
     * @param phaseType The growth phase type
     * @return Optional containing the longest phase of the specified type
     */
    public Optional<GrowthPhase> getLongestPhase(GrowthPhaseType phaseType) {
        return growthPhases.stream()
                .filter(phase -> phase.type() == phaseType)
                .max(Comparator.comparingDouble(GrowthPhase::duration));
    }

    /**
     * Checks if this neurite has any phases of the specified type.
     *
     * @param phaseType The growth phase type to check
     * @return True if at least one phase of the specified type exists
     */
    public boolean hasPhaseType(final GrowthPhaseType phaseType) {
        return growthPhases.stream().anyMatch(phase -> phase.type() == phaseType);
    }

    /**
     * Checks if this neurite has retraction events.
     *
     * @return True if any retraction events were detected
     */
    public boolean hasRetractionEvents() {
        return !retractionEvents.isEmpty();
    }

    /**
     * Checks if this neurite has elongation events.
     *
     * @return True if any elongation events were detected
     */
    public boolean hasElongationEvents() {
        return !elongationEvents.isEmpty();
    }

    void addTimePoint(TimePoint timePoint) {
        timePoints.add(timePoint);
    }

    // OBJECT METHODS
    @Override
    public String toString() {
        return String.format("NeuriteGrowthData[id=%s, rate=%.2f, phases=%d, retractions=%d]",
                neuriteId, linearGrowthRate, growthPhases.size(), retractionEvents.size());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        NeuriteGrowthData that = (NeuriteGrowthData) obj;
        return Objects.equals(neuriteId, that.neuriteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(neuriteId);
    }
}