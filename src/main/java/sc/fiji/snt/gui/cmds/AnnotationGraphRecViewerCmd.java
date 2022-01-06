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

package sc.fiji.snt.gui.cmds;

import com.mxgraph.model.mxCell;

import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.viewer.geditor.AnnotationGraphAdapter;

import java.util.*;
import java.util.stream.Collectors;


@Plugin(type = Command.class, visible = false, label = "Show Graph in Rec. Viewer")
public class AnnotationGraphRecViewerCmd extends DynamicCommand {

    public static final String COLOR_SOMA_MESH = "Soma compartment (graph color)";
    public static final String COLOR_UNIQUE = "Unique colors";

    @Parameter( label = "Color Trees By",
            choices = {COLOR_SOMA_MESH, COLOR_UNIQUE}) // NB: we cannot use AnnotationGraph.getMetrics()  here
    private String colorMetric;

    @Parameter(label= "Metric-based Mesh Opacity")
    private boolean useRelativeOpacity;

    @Parameter(label="adapter")
    private AnnotationGraphAdapter adapter;

    @Override
    public void run() {
        if (adapter == null) cancel("Invalid adapter");
        Set<BrainAnnotation> annotations;
        Object[] selectionCells = adapter.getSelectionCells();
        if (selectionCells == null || selectionCells.length == 0) {
            annotations = adapter.getVertexToCellMap().keySet();
        } else {
            annotations = Arrays.stream(selectionCells)
                    .map(c -> (mxCell) c)
                    .filter(mxCell::isVertex)
                    .map(c -> adapter.getCellToVertexMap().get(c))
                    .collect(Collectors.toSet());
        }

        AnnotationGraph annGraph = (AnnotationGraph) adapter.getSourceGraph();
        int maxDepth = annGraph.getMaxOntologyDepth();
        List<Tree> trees = annGraph.getTrees();
        Map<BrainAnnotation, List<Tree>> treeMap = new HashMap<>();
        if (colorMetric.equals(COLOR_SOMA_MESH) && trees != null) {
            for (Tree tree : trees) {
                BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
                if (rootAnnotation == null) continue;
                BrainAnnotation adjustedAnn = rootAnnotation;
                if (rootAnnotation.getOntologyDepth() > maxDepth) {
                    int diff = maxDepth - rootAnnotation.getOntologyDepth();
                    adjustedAnn = rootAnnotation.getAncestor(diff);
                }
                if (!treeMap.containsKey(adjustedAnn)) {
                    treeMap.put(adjustedAnn, new ArrayList<>());
                }
                treeMap.get(adjustedAnn).add(tree);
            }
        }
        List<OBJMesh> meshes = new ArrayList<>();
        for (BrainAnnotation ann : annotations) {
            if (!ann.isMeshAvailable()) {
                System.out.println("Skipping " + ann.acronym() + ", mesh not available...");
                continue;
            }
            OBJMesh mesh = ann.getMesh();
            double transparency = 90; // default value
            if (useRelativeOpacity && annGraph.getVertexValueMap().containsKey(ann)) {
                transparency = mapValueToTransparency(annGraph.getVertexValue(ann));
            }
            mesh.setColor(adapter.getSourceGraph().getVertexColor(ann), transparency);
            meshes.add(mesh);
            if (colorMetric.equals(COLOR_SOMA_MESH)) {
                List<Tree> annTrees = treeMap.get(ann);
                if (annTrees == null || annTrees.isEmpty()) continue;
                for (Tree tree : annTrees) {
                    tree.setColor(adapter.getSourceGraph().getVertexColor(ann));
                }
            }
        }
        Viewer3D viewer = new Viewer3D(getContext());
        viewer.add(meshes);
        if (colorMetric.equals(COLOR_UNIQUE)) {
            viewer.addTrees(trees, "unique");
        } else if (colorMetric.equals(COLOR_SOMA_MESH)) {
            viewer.addTrees(trees, "");
        } else {
            cancel("Unknown Tree coloring choice");
        }
        viewer.show();
        viewer.updateView();
    }

    private double mapValueToTransparency(double value) {
        // Opacity and Transparency expressed as percentages
        // vertex values are normalized to 0-1 by default during color mapping.
        double minOpacity = 2.0;
        double maxOpacity = 40.0;
        double minValue = 0.0;
        double maxValue = 1.0;
        double opacity = minOpacity + ( (maxOpacity - minOpacity) / (maxValue - minValue) ) * (value - minValue);
        return 100.0 - opacity;
    }

    /* IDE debug method **/
    public static void main(final String[] args) {
        GuiUtils.setLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ij.command().run(AnnotationGraphRecViewerCmd.class, true);
    }

}
