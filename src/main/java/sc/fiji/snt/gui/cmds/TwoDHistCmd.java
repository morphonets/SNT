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

package sc.fiji.snt.gui.cmds;

import net.imagej.Dataset;
import net.imglib2.display.ColorTable;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.util.ColorMaps;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Command for plotting 2D histograms of morphometric distributions
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Two-Dimensional Histograms", initializer = "init")
public class TwoDHistCmd extends CommonDynamicCmd {

    @Parameter
    private PrefService prefService;

    @Parameter(required = true, label = "Measurement 1")
    private String measurementChoice1;

    @Parameter(required = true, label = "Measurement 2")
    private String measurementChoice2;

    @Parameter(required = false, label = "Color scheme", choices = { "Monochrome", //
            "Heatmap: Fire", "Heatmap: Ice", "Heatmap: Plasma", "Heatmap: Red-Green", "Heatmap: Spectrum", "Heatmap: Viridis",//
            "Gradient: Cyan", "Gradient: Green", "Gradient: Magenta", "Gradient: Red", "Gradient: Yellow" })
    private String colorMapChoice;

    // Allowed inputs are a single Tree, or a Collection of Trees
    @Parameter(required = false)
    private Tree tree;

    @Parameter(required = false)
    private Collection<Tree> trees;

    @Parameter(required = false)
    private Dataset dataset;

    @Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
    private boolean onlyConnectivitySafeMetrics = true;

    private boolean imgDataAvailable;

    protected void init() {
        super.init(false);
        final List<String> choices = (onlyConnectivitySafeMetrics)
                ? TreeStatistics.getMetrics("safe") : TreeStatistics.getMetrics("common");
        Collections.sort(choices);
        final MutableModuleItem<String> measurementChoiceInput1 = getInfo().getMutableInput("measurementChoice1", String.class);
        measurementChoiceInput1.setChoices(choices);
        final MutableModuleItem<String> measurementChoiceInput2 = getInfo().getMutableInput("measurementChoice2", String.class);
        measurementChoiceInput2.setChoices(choices);
        if (dataset == null) choices.remove(TreeStatistics.VALUES);
        resolveInput("onlyConnectivitySafeMetrics");
        if (tree != null) {
            trees = Collections.singletonList(tree);
            resolveInput("trees");
        } else {
            resolveInput("tree");
        }
    }

    @Override
    public void run() {
        if (dataset != null && (TreeStatistics.VALUES.equals(measurementChoice1)
                || TreeStatistics.VALUES.equals(measurementChoice2))) {
            SNTUtils.log("Assigning values...");
            trees.forEach(tree -> new PathProfiler(tree,dataset).assignValues());
        }
        final DescriptiveStatistics stats1 = TreeStatistics.fromCollection(trees, measurementChoice1)
                .getDescriptiveStats(measurementChoice1);
        final DescriptiveStatistics stats2 = TreeStatistics.fromCollection(trees, measurementChoice2)
                .getDescriptiveStats(measurementChoice2);
        try {
            SNTChart.showHistogram3D(stats1, stats2, getColorTable(), measurementChoice1, measurementChoice2, "Freq.");
            if (ui.getRecorder(false) != null)
                ui.getRecorder(false).recordComment(String.format("Prompt options: \"%s\", \"%s\", \"%s\"",
                        measurementChoice1, measurementChoice2, colorMapChoice));
        } catch (final IllegalArgumentException | NullPointerException | InterruptedException |
                       InvocationTargetException ex) {
            error("It was not possible to retrieve valid histogram data. See Console for details");
            ex.printStackTrace();
        } finally {
            resetUI();
        }
    }

    private ColorTable getColorTable() {
        try {
            return ColorMaps.get(colorMapChoice.split(": ")[1]);
        } catch (final Exception ignored) {
            return null;
        }
    }

}
