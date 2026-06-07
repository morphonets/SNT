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

package sc.fiji.snt.analysis.curation;

import org.apache.commons.text.WordUtils;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.gui.GuiUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Factory for the small histogram buttons sitting next to threshold spinners in the Curation Assistant.
 * Clicking the button shows the distribution of the corresponding check's metric across the current
 * reconstruction(s), with a marker at the configured threshold and the flagged region lightly shaded.
 *
 * @author Tiago Ferreira
 */
public final class CurationHistograms {

    /**
     * Defines Which side of the threshold(s) constitutes a flagged value.
     */
    public enum Side {
        /**
         * Flagged when {@code value < threshold}: shade left of the marker.
         */
        LEFT_FLAGGED,
        /**
         * Flagged when {@code value > threshold}: shade right of the marker.
         */
        RIGHT_FLAGGED,
        /**
         * Flagged when {@code value < min || value > max}: shade outside the band.
         */
        OUTSIDE_FLAGGED
    }

    private CurationHistograms() {
    } // static utility

    /**
     * Single-sided histogram button (LEFT_FLAGGED or RIGHT_FLAGGED).
     *
     * @param checkName     short name used in the popup title and empty-state message
     * @param measureFn     function that produces the measurement set from a path
     *                      collection; called on a background thread
     * @param pathsSnapshot supplier that returns a snapshot of the current paths;
     *                      called on the EDT before the worker starts, so it can
     *                      safely iterate the live path list
     * @param threshold     supplier of the current spinner value (called on EDT)
     * @param side          which side of the threshold is flagged
     * @param parent        component used to position the empty-state dialog (nullable)
     * @return the small icon button, ready to be added to a row
     */
    public static JButton button(final String checkName,
                                 final Function<Collection<Path>, PlausibilityCheck.Measurements> measureFn,
                                 final Supplier<Collection<Path>> pathsSnapshot,
                                 final DoubleSupplier threshold,
                                 final Side side,
                                 final Component parent) {
        return button(checkName, measureFn, pathsSnapshot, threshold, null, side, parent);
    }

    /**
     * Histogram button supporting both single- and two-threshold checks. Pass {@code secondaryThreshold == null}
     * for single-sided checks; pass both suppliers for {@link Side#OUTSIDE_FLAGGED} (e.g.,
     * {@link PlausibilityCheck.BranchAngle}, which has min and max spinners).
     */
    public static JButton button(final String checkName,
                                 final Function<Collection<Path>, PlausibilityCheck.Measurements> measureFn,
                                 final Supplier<Collection<Path>> pathsSnapshot,
                                 final DoubleSupplier primaryThreshold,
                                 final DoubleSupplier secondaryThreshold,
                                 final Side side,
                                 final Component parent) {
        final JButton btn = GuiUtils.Buttons.histogram();
        final String m = (checkName == null || checkName.isBlank()) ? "measurement" : checkName.toLowerCase();
        btn.setToolTipText("Plot the " + m + " distribution for all the paths listed in the Path Manager");
        btn.addActionListener(e -> {
            // The path snapshot is taken on the EDT (so it can safely iterate the live path list). Threshold reads
            // are deferred to the worker's done() callback: some thresholds depend on side effects of  measureFn
            // (e.g., SignalQuality's auto-threshold resolves against image stats computed inside the worker),
            // so reading them now would yield stale values
            final Collection<Path> snapshot = (pathsSnapshot == null)
                    ? Collections.emptyList()
                    : safeSnapshot(checkName, pathsSnapshot);
            runAsync(checkName, btn, parent, measureFn, snapshot, primaryThreshold, secondaryThreshold, side);
        });
        return btn;
    }

    /**
     * Defensive snapshot wrapper: turns a thrown exception into an empty list.
     */
    private static Collection<Path> safeSnapshot(final String checkName, final Supplier<Collection<Path>> supplier) {
        try {
            final Collection<Path> snap = supplier.get();
            return (snap == null) ? Collections.emptyList() : snap;
        } catch (final Exception ex) {
            SNTUtils.log("CurationHistograms: '" + checkName + "' path snapshot threw: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Runs {@code measureFn} on a background thread and shows the resulting histogram once it returns.
     * Disables the button and sets a wait cursor on the parent window while the computation runs.
     */
    private static void runAsync(final String checkName,
                                 final JButton button,
                                 final Component parent,
                                 final Function<Collection<Path>, PlausibilityCheck.Measurements> measureFn,
                                 final Collection<Path> pathsSnapshot,
                                 final DoubleSupplier primaryThreshold,
                                 final DoubleSupplier secondaryThreshold,
                                 final Side side) {
        if (measureFn == null) {
            final double p0 = (primaryThreshold == null) ? Double.NaN : primaryThreshold.getAsDouble();
            final double s0 = (secondaryThreshold == null) ? Double.NaN : secondaryThreshold.getAsDouble();
            showHistogram(checkName, PlausibilityCheck.Measurements.EMPTY, p0, s0, side, parent);
            return;
        }
        final Window window = SwingUtilities.getWindowAncestor(parent != null ? parent : button);
        final Cursor previousCursor = (window != null) ? window.getCursor() : null;
        if (window != null) window.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        button.setEnabled(false);

        new SwingWorker<PlausibilityCheck.Measurements, Void>() {
            @Override
            protected PlausibilityCheck.Measurements doInBackground() {
                try {
                    return measureFn.apply(pathsSnapshot);
                } catch (final Exception ex) {
                    SNTUtils.log("CurationHistograms: '" + checkName + "' measure() threw: " + ex.getMessage());
                    return PlausibilityCheck.Measurements.EMPTY;
                }
            }

            @Override
            protected void done() {
                // Restore UI state before showing the chart so the wait cursor doesn't linger over the new window.
                button.setEnabled(true);
                if (window != null) {
                    window.setCursor(previousCursor);
                }
                PlausibilityCheck.Measurements m = PlausibilityCheck.Measurements.EMPTY;
                try {
                    m = get();
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (final ExecutionException ex) {
                    SNTUtils.log("CurationHistograms: '" + checkName + "' worker failed: " + ex.getMessage());
                }
                // Threshold reads happen here on the EDT, after the worker has had a chance to update any
                // state the threshold supplier may depend on (e.g., SignalQuality's resolved auto-threshold
                // following a full-volume image-stats scan in prepareImage)
                final double p = (primaryThreshold == null) ? Double.NaN : primaryThreshold.getAsDouble();
                final double s = (secondaryThreshold == null) ? Double.NaN : secondaryThreshold.getAsDouble();
                showHistogram(checkName, m, p, s, side, parent);
            }
        }.execute();
    }

    private static void showHistogram(final String checkName, final PlausibilityCheck.Measurements m,
                                      final double primary, final double secondary, final Side side,
                                      final Component parent) {
        if (m == null || m.isEmpty()) {
            final String msg = getNoHistErrorMsg(checkName, m);
            new GuiUtils(parent).centeredMsg(msg, checkName);
            return;
        }

        final boolean polar = checkName != null && (checkName.toLowerCase().contains("angle")
                || checkName.toLowerCase().contains("°") || checkName.toLowerCase().contains("direction"));
        final SNTChart chart = (polar) ?
                SNTChart.getPolarHistogram(m.values(), m.metric(), m.unit())
                : SNTChart.getHistogram(m.values(), m.metric(), m.unit());
        chart.setQuartilesVisible(true);
        chart.setTitle(WordUtils.capitalizeFully(checkName) + " Distribution");

        // Compute shading bounds. When the metric advertises its natural domain (e.g., angle checks bounded to
        // [0, 180]) we prefer those bounds. This matters most for polar plots where values wrap around the dial.
        // When domain bounds are NaN we fall back to dataMin/Max +/- span.
        double dataMin = Double.POSITIVE_INFINITY;
        double dataMax = Double.NEGATIVE_INFINITY;
        for (final double v : m.values()) {
            if (v < dataMin) dataMin = v;
            if (v > dataMax) dataMax = v;
        }
        final double span = (dataMax > dataMin) ? (dataMax - dataMin) : Math.max(Math.abs(dataMax), 1.0);
        final double shadeLo = Double.isNaN(m.domainMin()) ? (dataMin - span) : m.domainMin();
        final double shadeHi = Double.isNaN(m.domainMax()) ? (dataMax + span) : m.domainMax();
        final String highlight = "#E53E4D";
        switch (side) {
            case LEFT_FLAGGED -> {
                if (!Double.isNaN(primary)) {
                    chart.shadeXRegion(shadeLo, primary, highlight);
                    chart.annotateXline(primary, String.format("threshold = %.2f", primary), highlight);
                }
            }
            case RIGHT_FLAGGED -> {
                if (!Double.isNaN(primary)) {
                    chart.shadeXRegion(primary, shadeHi, highlight);
                    chart.annotateXline(primary, String.format("threshold = %.2f", primary), highlight);
                }
            }
            case OUTSIDE_FLAGGED -> {
                if (!Double.isNaN(primary) && !Double.isNaN(secondary)) {
                    final double lo = Math.min(primary, secondary);
                    final double hi = Math.max(primary, secondary);
                    chart.shadeXRegion(shadeLo, lo, highlight);
                    chart.shadeXRegion(hi, shadeHi, highlight);
                    chart.annotateXline(lo, String.format("min = %.2f", lo), highlight);
                    chart.annotateXline(hi, String.format("max = %.2f", hi), highlight);
                } else if (!Double.isNaN(primary)) {
                    // Single threshold supplied: fall back to a single marker
                    chart.annotateXline(primary, String.format("threshold = %.2f", primary), highlight);
                }
            }
        }
        chart.annotate(m.formatCounts());
        chart.show();
    }

    /**
     * Builds the empty-state message shown when a histogram cannot be drawn. The result opens with the check name
     * and a one-line headline ("0 of N X assessed." or "No data to plot yet.") so the user knows which check is
     * reporting and which empty state they're in.
     */
    private static String getNoHistErrorMsg(final String checkName, final PlausibilityCheck.Measurements m) {
        final boolean haveCandidates = (m != null && m.notAssessable() > 0);
        final String headline;
        if (haveCandidates) {
            final String plural = (m.subject() == null || m.subject().isBlank()) ? "values" : m.subjectPlural();
            headline = String.format("%s: 0 of %d %s could be evaluated.", checkName, m.notAssessable(), plural);
        } else {
            headline = checkName + ": No data to plot yet.";
        }
        final String hint = resolveEmptyHint(m, haveCandidates);
        return headline + "\n\n" + hint;
    }

    /**
     * Resolves the explanatory hint for an empty-state message: check-specific if available, otherwise
     * inferred from the measurement's subject.
     */
    private static String resolveEmptyHint(final PlausibilityCheck.Measurements m, final boolean haveCandidates) {
        if (m != null && m.emptyHint() != null && !m.emptyHint().isBlank()) {
            return m.emptyHint();
        }
        if (haveCandidates) {
            // Skipped candidates
            return switch ((m == null || m.subject() == null) ? "" : m.subject()) {
                case "fork" -> "This check evaluates branch points. Trace at least one path that forks off another.";
                case "terminal branch" -> "This check evaluates terminal branches (paths with no children). " +
                        "Trace at least one branch.";
                case "node pair", "monotonic run" -> "This check needs paths with fitted radii. " +
                        "Run \"Refine › Fit Paths...\" or set radii manually in the Path Manager.";
                case "cross-over" -> "This check needs at least two paths close enough to overlap.";
                default -> "Enable debug mode and check the Console for per-item skip reasons.";
            };
        }
        // No candidates at all: nothing in the Path Manager.
        return "Trace some paths or import a reconstruction (File › Load Tracings › menu). " +
                "SNT will then measure every path in the Path Manager.";
    }
}
