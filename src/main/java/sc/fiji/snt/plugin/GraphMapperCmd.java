/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;

import org.jgrapht.graph.DefaultWeightedEdge;

import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.graph.ColorableGraph;
import sc.fiji.snt.analysis.graph.GraphColorMapper;


@Plugin(type = Command.class, visible = false, label = "Graph Color Mapper",
        initializer = "init")
public class GraphMapperCmd extends DynamicCommand {

    @Parameter
    private PrefService prefService;

    @Parameter
    private LUTService lutService;

    @Parameter(required = true, label = "Color by")
    private String measurementChoice;

    @Parameter(label = "LUT", callback = "lutChoiceChanged")
    private String lutChoice;

    @Parameter(required = false, label = "<HTML>&nbsp;")
    private ColorTable colorTable;

    @Parameter(required = false, label = "Remove Existing Color Coding",
            callback = "removeColorCoding")
    private Button removeColorCoding;

    @Parameter(required = true)
    private ColorableGraph<Object, DefaultWeightedEdge> graph;

    private Map<String, URL> luts;

    @Override
    public void run() {
        if (graph == null || graph.vertexSet().isEmpty()) cancel("Invalid input tree");
        SNTUtils.log("Color Coding Graph(" + measurementChoice + ") using " + lutChoice);
        final GraphColorMapper colorizer = new GraphColorMapper(context());
        try {
            colorizer.map(graph, measurementChoice, colorTable);
        }
        catch (final Exception exc) {
            cancel(exc.getMessage());
            return;
        }
        for (DefaultWeightedEdge e : graph.edgeSet()) {
            System.out.println(graph.getEdgeColor(e).toHTMLColor());
        }
        SNTUtils.log("Finished...");
    }

    @SuppressWarnings("unused")
    private void init() {
        final MutableModuleItem<String> measurementChoiceInput = getInfo()
                .getMutableInput("measurementChoice", String.class);
        final List<String> choices = GraphColorMapper.getMetrics();
        Collections.sort(choices);
        measurementChoiceInput.setChoices(choices);
        measurementChoiceInput.setValue(this, prefService.get(getClass(),
                "measurementChoice", GraphColorMapper.EDGE_WEIGHT));
        if (lutChoice == null) lutChoice = prefService.get(getClass(), "lutChoice",
                "mpl-viridis.lut");
        setLUTs();
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
        }
        catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private void removeColorCoding() {
        for (Object vertex : graph.vertexSet()) {
            graph.setVertexColor(vertex, null);
        }
        for (DefaultWeightedEdge edge : graph.edgeSet()) {
            graph.setEdgeColor(edge, null);
        }
    }

}
