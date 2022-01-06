/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.graph.GraphColorMapper;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.geditor.GraphEditor;
import sc.fiji.snt.viewer.geditor.SNTGraphAdapter;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


@Plugin(type = Command.class, visible = false, label = "Graph Color Mapper",
        initializer = "init")
public class GraphAdapterMapperCmd extends DynamicCommand {

    @Parameter
    private PrefService prefService;

    @Parameter
    private LUTService lutService;

    @Parameter(label = "Color By")
    private String measurementChoice;

    @Parameter(label = "LUT", callback = "lutChoiceChanged")
    private String lutChoice;

    @Parameter(required = false, label = "<HTML>&nbsp;")
    private ColorTable colorTable;

    @Parameter(label="Use Clipping Range")
    private boolean useRange;

    @Parameter(required = false, label="Min Value")
    private double minValue;

    @Parameter(required = false, label="Max Value")
    private double maxValue;

    @Parameter(label="Show Legend")
    private boolean showLegend;

    @Parameter(required = false, label = "Remove Existing Color Coding",
            callback = "removeColorCoding",
    		description="After removing exiting color coding, press 'Cancel' to dismiss this dialog")
    private Button removeColorCoding;

    @Parameter(label = "Editor")
    private GraphEditor editor;

    @Parameter
    private SNTGraphAdapter<Object, DefaultWeightedEdge> adapter;

    @Parameter(required = false)
    private AsSubgraph<Object, DefaultWeightedEdge> subgraph;

    private SNTGraph<Object, DefaultWeightedEdge> cGraph;
    private Map<String, URL> luts;

    @Override
    public void run() {
        if (adapter == null) cancel("Input is null");
        cGraph = adapter.getSourceGraph();
        if (cGraph == null || cGraph.vertexSet().isEmpty()) cancel("Graph is invalid");
        if (measurementChoice.equals(GraphColorMapper.HEAVY_PATH_DECOMPOSITION) &&
                !(cGraph.vertexSet().iterator().next() instanceof SWCPoint)) {
            cancel("This measurement requires a DirectedWeightedGraph");
            return;
        }
        final GraphColorMapper<Object, DefaultWeightedEdge> colorizer = new GraphColorMapper<>(context());
        int mappedState;
        SNTUtils.log("Color Coding Graph (" + measurementChoice + ") using " + lutChoice);
        try {
            if (useRange) {
                colorizer.setMinMax(minValue, maxValue);
            }
            if (subgraph != null) {
                mappedState = colorizer.map(cGraph, subgraph, measurementChoice, colorTable);
            } else {
                mappedState = colorizer.map(cGraph, measurementChoice, colorTable);
            }

        } catch (final Exception exc) {
            cancel(exc.getMessage());
            exc.printStackTrace();
            return;
        }
        switch (mappedState) {
            case GraphColorMapper.VERTICES:
                applyVertexColors();
                break;
            case GraphColorMapper.EDGES:
                applyEdgeColors();
                break;
            case GraphColorMapper.VERTICES_AND_EDGES:
                applyVertexColors();
                applyEdgeColors();
                break;
            default:
                cancel("Color mapping failed");
        }
        if (showLegend && editor != null) {
            editor.setLegend(colorTable, measurementChoice, colorizer.getMinMax()[0], colorizer.getMinMax()[1]);
        }
        SNTUtils.log("Finished...");
    }

    @SuppressWarnings("unused")
    private void init() {
        if (subgraph == null) {
            resolveInput("subgraph");
        }
        if (measurementChoice == null) {
            final MutableModuleItem<String> measurementChoiceInput = getInfo()
                    .getMutableInput("measurementChoice", String.class);
            final List<String> choices = GraphColorMapper.getMetrics();
            Collections.sort(choices);
            measurementChoiceInput.setChoices(choices);
            measurementChoiceInput.setValue(this, prefService.get(getClass(),
                    "measurementChoice", GraphColorMapper.EDGE_WEIGHT));
        }
        if (lutChoice == null) lutChoice = prefService.get(getClass(), "lutChoice",
                "mpl-viridis.lut");
        setLUTs();
        final MutableModuleItem<Double> minValueInput = getInfo()
                .getMutableInput("minValue", Double.class);
        minValueInput.setMinimumValue(-Double.MAX_VALUE);
        minValueInput.setMaximumValue(Double.MAX_VALUE);
        minValueInput.setDefaultValue(0d);

        final MutableModuleItem<Double> maxValueInput = getInfo()
                .getMutableInput("maxValue", Double.class);
        maxValueInput.setMinimumValue(-Double.MAX_VALUE);
        maxValueInput.setMaximumValue(Double.MAX_VALUE);
        maxValueInput.setDefaultValue(0d);
    }

    private void setLUTs() {
        luts = lutService.findLUTs();
        if (luts.isEmpty()) {
            cancel("<HTML>This command requires at least one LUT to be installed.");
        }
        final ArrayList<String> choices = new ArrayList<>();
        for (final Map.Entry<String, URL> entry : luts.entrySet()) {
            choices.add(entry.getKey());
        }

        // define a valid LUT choice
        Collections.sort(choices);
        if (lutChoice == null || !choices.contains(lutChoice)) {
            lutChoice = choices.get(0);
        }

        final MutableModuleItem<String> input = getInfo().getMutableInput(
                "lutChoice", String.class);
        input.setChoices(choices);
        input.setValue(this, lutChoice);
        lutChoiceChanged();
    }

    private void lutChoiceChanged() {
        try {
            colorTable = lutService.loadLUT(luts.get(lutChoice));
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private void removeColorCoding() {
        adapter.getModel().beginUpdate();
        if (subgraph != null) {
            for (Object vertex : subgraph.vertexSet()) {
                adapter.setVertexColor(vertex, null);
            }
            for (DefaultWeightedEdge edge : subgraph.edgeSet()) {
                adapter.setEdgeColor(edge, null);
            }
        } else {
            for (Object vertex : adapter.getVertexToCellMap().keySet()) {
                adapter.setVertexColor(vertex, null);
            }
            for (DefaultWeightedEdge edge : adapter.getEdgeToCellMap().keySet()) {
                adapter.setEdgeColor(edge, null);
            }
        }
        adapter.getModel().endUpdate();
    }

    private void applyVertexColors() {
        adapter.getModel().beginUpdate();
        try {
            for (Object vertex : cGraph.vertexSet()) {
                adapter.setVertexColor(vertex, cGraph.getVertexColor(vertex));
            }
        } finally {
            adapter.getModel().endUpdate();
            //adapter.refresh();
        }
    }

    private void applyEdgeColors() {
        adapter.getModel().beginUpdate();
        try {
            for (DefaultWeightedEdge edge : cGraph.edgeSet()) {
                adapter.setEdgeColor(edge, cGraph.getEdgeColor(edge));
            }
        } finally {
            adapter.getModel().endUpdate();
            //adapter.refresh();
        }
    }

}
