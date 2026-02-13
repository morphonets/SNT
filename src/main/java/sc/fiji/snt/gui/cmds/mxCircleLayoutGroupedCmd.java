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

package sc.fiji.snt.gui.cmds;

import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;
import com.mxgraph.view.mxGraph;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.viewer.geditor.SNTGraphAdapter;
import sc.fiji.snt.viewer.geditor.mxCircleLayoutGrouped;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/**
 *
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, label = "Grouped Circle Layout",
        initializer = "init")
public class mxCircleLayoutGroupedCmd extends DynamicCommand {

    @Parameter
    PrefService prefService;

    @Parameter
    LUTService lutService;

    @Parameter(label="Radius", description="The radius of the circle on which the vertices are distributed. Set to 0 " +
            "to determine automatically.")
    double radius;

    @Parameter(label="Top-level")
    int topLevel;

    @Parameter(label="Mid-level")
    int midLevel;

    @Parameter(label="Sort mid-level compartments by weight")
    boolean sortMidLevel;

    @Parameter(label="Color by group")
    boolean colorByGroup;

    @Parameter(label="Move source to center", required=false)
    boolean center;

    @Parameter(label="LUT", callback="lutChoiceChanged")
    String lutChoice;

    @Parameter(required=false, label="<HTML>&nbsp;")
    ColorTable colorTable;

    @Parameter(label="adapter")
    mxGraph adapter;

    Map<String, URL> luts;

    @Override
    public void run() {
        if (adapter == null) cancel("Invalid adapter");
        if (midLevel < topLevel) cancel("midLevel must be greater than topLevel");
        mxCircleLayoutGrouped groupedLayout = new mxCircleLayoutGrouped(adapter, midLevel, topLevel);
        groupedLayout.setRadius(radius);
        groupedLayout.setColorTable(colorTable);
        groupedLayout.setColorCode(colorByGroup);
        groupedLayout.setSortMidLevel(sortMidLevel);
        groupedLayout.setCenterSource(center);
        try {
            applyLayout(groupedLayout, adapter);
            applyParallelEdgeLayout(adapter);
        } catch (Exception exc) {
            cancel(exc.getMessage());
        }
    }

    @SuppressWarnings({ "unused", "unchecked" })
    private void init() {
        if (lutChoice == null) lutChoice = prefService.get(getClass(), "lutChoice",
                "mpl-viridis.lut");
        setLUTs();
        final MutableModuleItem<Integer> topLevelInput = getInfo()
                .getMutableInput("topLevel", Integer.class);
        topLevelInput.setMinimumValue(0);
        topLevelInput.setMaximumValue(AllenUtils.getHighestOntologyDepth());
        topLevelInput.setDefaultValue(0);

        final MutableModuleItem<Integer> midLevelInput = getInfo()
                .getMutableInput("midLevel", Integer.class);
        midLevelInput.setMinimumValue(0);
        midLevelInput.setMaximumValue(AllenUtils.getHighestOntologyDepth());
        midLevelInput.setDefaultValue(0);

        if (!(adapter instanceof SNTGraphAdapter)) {
            cancel("This action requires an SNTGraph");
            return;
        }
        if (!(((SNTGraphAdapter<?, ?>) adapter).getSourceGraph().vertexSet().iterator().next() instanceof BrainAnnotation)) {
            cancel("This action requires BrainAnnotation vertex objects.");
            return;
        }
        SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge> sntAdapter = (SNTGraphAdapter<BrainAnnotation, DefaultWeightedEdge>) adapter;
        SNTGraph<BrainAnnotation, DefaultWeightedEdge> graph = sntAdapter.getSourceGraph();
        int sourceCount = 0;
        for (BrainAnnotation an : graph.vertexSet()) {
            if (graph.outDegreeOf(an) > 0) {
                sourceCount++;
            }
        }
        if (sourceCount != 1) {
            center = false;
            resolveInput("center");
        } else {
            center = true;
        }

    }

    private void setLUTs() {
        luts = lutService.findLUTs();
        if (luts == null || luts.isEmpty()) {
            cancel("<HTML>This command requires at least one LUT to be installed.");
        }
        final ArrayList<String> choices = new ArrayList<>(luts.keySet());
        // define a valid LUT choice
        Collections.sort(choices);
        if (lutChoice == null || !choices.contains(lutChoice)) {
            lutChoice = choices.getFirst();
        }

        final MutableModuleItem<String> input = getInfo().getMutableInput("lutChoice", String.class);
        input.setChoices(choices);
        input.setValue(this, lutChoice);
        lutChoiceChanged();
    }

    private void lutChoiceChanged() {
        try {
            if (lutChoice == null) lutChoice = "Ice.lut";
            colorTable = lutService.loadLUT(luts.get(lutChoice));
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private void applyLayout(final mxIGraphLayout layout, final mxGraph graph) {
        Object cell = graph.getSelectionCell();

        if (cell == null || graph.getModel().getChildCount(cell) == 0) {
            cell = graph.getDefaultParent();
        }

        graph.getModel().beginUpdate();
        try {
            layout.execute(cell);
        } finally {
            graph.getModel().endUpdate();
        }
    }

    private void applyParallelEdgeLayout(mxGraph graph) {
        Object cell = graph.getSelectionCell();

        if (cell == null || graph.getModel().getChildCount(cell) == 0) {
            cell = graph.getDefaultParent();
        }

        graph.getModel().beginUpdate();
        try {
            mxParallelEdgeLayout layout = new mxParallelEdgeLayout(graph);
            layout.execute(cell);
        } finally {
            graph.getModel().endUpdate();
        }
    }

}
