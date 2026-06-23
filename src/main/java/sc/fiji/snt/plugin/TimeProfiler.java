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

import net.imagej.axis.Axes;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.TreeUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for generating kymograph-style intensity profiles across time.
 *
 * @author Tiago Ferreira
 * @see PathTimeAnalysisCmd
 * @see PathProfiler
 */
@Plugin(type = Command.class, label = "Timelapse Path Profiler...", initializer = "init")
public class TimeProfiler extends PathProfiler {

    @Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Output:", persist = false)
    private String HEADER_N;

    @Parameter(label = EMPTY_LABEL, style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE, choices = {"Heatmap", "Table"})
    private String outputStr;

    @Parameter(label = HEADER_HTML + "NB:", required = false, visibility = ItemVisibility.MESSAGE, persist = false)
    private String multiPathMsg = "<HTML>Signal from multiple paths will be averaged in a single data matrix.<br>" +
            "Only paths with at least 2 nodes will be considered.";

    private Collection<Path> filteredPaths;
    private double frameRate;

    @SuppressWarnings("unused")
    @Override
    protected void init() {
        super.init();
        if (dataset == null || dataset.getFrames() < 2) {
            error("An image with at least 2 time points is required for intensity profiling across time.");
            return;
        }
        filteredPaths = tree.list().stream().filter(p-> p.size() > 2).collect(Collectors.toList());
        if (filteredPaths.isEmpty()) {
            super.error("None of the paths can be profiled. Only paths with at least 2 nodes can be profiled.");
            return;
        }
        try {
            if (filteredPaths.size() == 1) resolveInput(multiPathMsg);
            final MutableModuleItem<String> mi = getInfo().getMutableInput("metricStr", String.class);
            mi.setChoices(List.of("Sum", "Min", "Max", "Mean", "Median", "Variance", "Standard deviation", "CV", "N/A"));
        } catch (final Exception ignored) {
            // non-critical
        }
        frameRate = (usePhysicalUnits) ? dataset.averageScale(dataset.dimensionIndex(Axes.TIME)) : 1;
    }

    @Override
    public void run() {
        if (getContext() == null) {
            // mimic parent PathProfiler
            throw new IllegalArgumentException("No context has been set. Note that this method should only be called from CommandService.");
        }
        evalParameters();
        status("Retrieving profile(s). This may take a while...", false);
        final List<Frame> shownPlots = new ArrayList<>();
        try {
            getChannels().forEach(ch -> {
                try {
                    if ("table".equalsIgnoreCase(outputStr)) {
                       showTimeProfileAsTable(ch);
                    } else {
                        shownPlots.add(showTimeProfileAsHeatmap(ch));
                    }
                } catch (final InterruptedException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (final Exception ex) {
            error("Could not generate profile: " + ex.getMessage());
            SNTUtils.error("TimeProfiler", ex);
        } finally {
            resetUI();
            GuiUtils.tile(shownPlots, false);
        }
    }

    final Frame showTimeProfileAsHeatmap(final int ch) throws InterruptedException, InvocationTargetException {
        if (filteredPaths.size() == 1)
            return showTimeProfileAsHeatmap(filteredPaths.iterator().next(), ch);
        return showTimeProfileAsHeatmap(filteredPaths, ch);
    }

    final void showTimeProfileAsTable(final int ch) throws InterruptedException, InvocationTargetException {
        if (tree.list().size() == 1)
            showTimeProfileAsTable(filteredPaths.iterator().next(), ch);
        else
            showTimeProfileAsTable(filteredPaths, ch);
    }

    private JFrame showTimeProfileAsHeatmap(final Path path, final int channel) throws InterruptedException, InvocationTargetException {
        final List<Map<String, List<Double>>> profile = getTimeProfile(path, channel);
        final int nFrames = profile.size();
        // X_VALUES are cumulative distances: identical across all frames
        final List<Double> xVals = profile.getFirst().get(PathProfiler.X_VALUES);
        final int nSamples = xVals.size();
        final double[] xCoords = xVals.stream().mapToDouble(Double::doubleValue).toArray();
        // Y coords are 1-based frame numbers, matching table and 3D outputs
        final double[] yCoords = new double[nFrames];
        final double[][] matrix = new double[nFrames][nSamples];
        for (int f = 0; f < nFrames; f++) {
            yCoords[f] = (usePhysicalUnits) ? f * frameRate : f + 1; // 1-based frame numbers
            final List<Double> intensities = profile.get(f).get(PathProfiler.Y_VALUES);
            for (int s = 0; s < nSamples; s++)
                matrix[f][s] = intensities.get(s);
        }
        final String fullTitle = sanitizedOutputName(path, channel, false) + ", " + getYAxisLabel(channel);
        final JFrame frame = SNTChart.showHeatmap(fullTitle,
                xCoords, yCoords, matrix, ColorMaps.VIRIDIS, getXAxisLabel(), getTimeAxisLabel());
        SwingUtilities.invokeLater(() -> frame.setTitle(sanitizedOutputName(path, channel, true) + "_Heatmap"));
        return frame;
    }



    private JFrame showTimeProfileAsHeatmap(final Collection<Path> paths, final int channel)
            throws InterruptedException, InvocationTargetException {
        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("paths cannot be null or empty");
        final int nFrames = (int) dataset.getFrames();
        // use max node count so the longest path loses no resolution
        final int nSamples = paths.stream().mapToInt(Path::size).max().orElse(10);
        // normalize each path to its own length: all paths contribute to all columns, no NaN
        final double[] xCoords = new double[nSamples];
        for (int s = 0; s < nSamples; s++) xCoords[s] = (double) s / (nSamples - 1);
        final double[] yCoords = new double[nFrames];
        final double[][] matrix = new double[nFrames][nSamples];
        final Map<Path, double[]> snapshot = TreeUtils.snapshotNodeValues(new Tree(new ArrayList<>(paths)));
        try {
            for (int f = 0; f < nFrames; f++) {
                yCoords[f] = (usePhysicalUnits) ? f * frameRate : f + 1;
                final double[] sum = new double[nSamples];
                for (final Path p : paths)
                    for (int s = 0; s < nSamples; s++)
                        sum[s] += getResampledValues(p, channel, f, nSamples, p.getLength())[s];
                for (int s = 0; s < nSamples; s++) matrix[f][s] = sum[s] / paths.size();
            }
        } finally {
            TreeUtils.restoreNodeValues(snapshot);
        }
        final String title = "Timelapse Profile: Ch" + (channel + 1) + " (N=" + paths.size() + " paths), " + getYAxisLabel(channel);
        final JFrame frame = SNTChart.showHeatmap(title, xCoords, yCoords, matrix, ColorMaps.VIRIDIS, "Normalized distance", getTimeAxisLabel());
        SwingUtilities.invokeLater(() -> frame.setTitle(sanitizedOutputName(paths, channel, true) +"_Heatmap"));
        return frame;
    }

    private void showTimeProfileAsTable(final Path path, final int channel) {
        final List<Map<String, List<Double>>> profile = getTimeProfile(path, channel);
        final SNTTable table = new SNTTable();
        int index = 0;
        final String timeColHeader = getTimeAxisLabel();
        for (final Map<String, List<Double>> frameEntry : profile) {
            table.appendRow();
            table.appendToLastRow(timeColHeader, (usePhysicalUnits) ? index * frameRate : (index+1));
            index++;
            final List<Double> xValues = frameEntry.get(X_VALUES);
            final List<Double> yValues = frameEntry.get(Y_VALUES);
            for (int i = 0; i < xValues.size(); i++) {
                table.appendToLastRow(String.format("%.4f", xValues.get(i)), yValues.get(i));
            }
        }
        table.show(sanitizedOutputName(path, channel, true) + ".csv");
    }

    private void showTimeProfileAsTable(final Collection<Path> paths, final int channel) {
        if (paths == null || paths.isEmpty())
            throw new IllegalArgumentException("paths cannot be null or empty");
        final int nFrames = (int)dataset.getFrames();
        final int nSamples = paths.stream().mapToInt(Path::size).max().orElse(10);
        final double[] xCoords = new double[nSamples];
        for (int s = 0; s < nSamples; s++) xCoords[s] = (double) s / (nSamples - 1);
        final SNTTable table = new SNTTable();
        final Map<Path, double[]> snapshot = TreeUtils.snapshotNodeValues(new Tree(new ArrayList<>(paths)));
        try {
            final String timeColHeader = getTimeAxisLabel();
            for (int f = 0; f < nFrames; f++) {
                table.appendRow();
                table.appendToLastRow(timeColHeader, (usePhysicalUnits) ? f * frameRate : (f+1));
                final double[] sum = new double[nSamples];
                for (final Path p : paths)
                    for (int s = 0; s < nSamples; s++)
                        sum[s] += getResampledValues(p, channel, f, nSamples, p.getLength())[s];
                for (int s = 0; s < nSamples; s++)
                    table.appendToLastRow(String.format("%.4f", xCoords[s]), sum[s] / paths.size());
            }
        } finally {
            TreeUtils.restoreNodeValues(snapshot);
        }
        table.show(sanitizedOutputName(paths, channel, true) + ".csv");
    }

    private String getTimeAxisLabel() {
        if (usePhysicalUnits)
            return "Time (" + dataset.axis(dataset.dimensionIndex(Axes.TIME)).unit() + ")";
        return "Frame No.";
    }

    private String sanitizedOutputName(final Path path, final int channel, final boolean replaceWhiteSpace) {
        String result = "Timelapse Profile: Ch" + (channel + 1) + " " + PathManagerUI.removeTags(path).trim();
        result = result.replaceAll("\\s*\\[[^]]+:\\d+]", ""); // Remove all square brackets containing colons
        // Replace: one or more spaces OR a comma with optional surrounding spaces
        return (replaceWhiteSpace) ? result.replaceAll("\\s+|\\s*,\\s*", "_") : result;
    }

    private String sanitizedOutputName(final Collection<Path> paths, final int channel, final boolean replaceWhiteSpace) {
        final String result = "Timelapse Profile: Ch" + (channel + 1) + " N=" + paths.size() + " paths";
        // Replace: one or more spaces OR a comma with optional surrounding spaces
        return (replaceWhiteSpace) ? result.replaceAll("\\s+|\\s*,\\s*|\\s*:\\s*", "_") : result;
    }

}
