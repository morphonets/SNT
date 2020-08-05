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

package sc.fiji.snt.gui.cmds;

import com.mxgraph.model.mxCell;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Plugin(type = Command.class, visible = false, label = "Show Graph in Rec. Viewer")
public class GraphRecViewerCmd extends ContextCommand {

    public static final String COLOR_SOMA_MESH = "Soma compartment mesh";
    public static final String COLOR_UNIQUE = "Color cells uniquely";

    @Parameter( label = "Color by",
            choices = {COLOR_SOMA_MESH, COLOR_UNIQUE}) // NB: we cannot use AnnotationGraph.getMetrics()  here
    private String colorMetric;

    @Parameter(label= "Use Relative Opacity")
    private boolean useRelativeOpacity;

    @Parameter(label="adapter")
    private AnnotationGraphAdapter adapter;

    @Override
    public void run() {
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
        List<Tree> trees = ((AnnotationGraph) adapter.getSourceGraph()).getTrees();
        if (trees == null) trees = new ArrayList<>();
        List<OBJMesh> meshes = new ArrayList<>();
        for (BrainAnnotation ann : annotations) {
            if (!ann.isMeshAvailable()) {
                System.out.println("Skipping " + ann.acronym() + ", mesh not available...");
                continue;
            }
            OBJMesh mesh = ann.getMesh();
            int aDepth = ann.getOntologyDepth();
            for (Tree tree : trees) {
                BrainAnnotation rootAnnotation = tree.getRoot().getAnnotation();
                if (rootAnnotation == null) continue;
                BrainAnnotation adjustedAnn = rootAnnotation;
                if (rootAnnotation.getOntologyDepth() > aDepth) {
                    int diff = aDepth - rootAnnotation.getOntologyDepth();
                    adjustedAnn = rootAnnotation.getAncestor(diff);
                }
                if (adjustedAnn.id() == ann.id()) {
                    tree.setColor(adapter.getSourceGraph().getVertexColor(ann));
                }
            }
            mesh.setColor(adapter.getSourceGraph().getVertexColor(ann), 85);
            meshes.add(mesh);
        }
        Viewer3D viewer = new Viewer3D(getContext());
        viewer.add(meshes);
        viewer.add(trees);
        viewer.show();
        viewer.updateView();
    }

    /* IDE debug method **/
    public static void main(final String[] args) {
        GuiUtils.setSystemLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ij.command().run(GraphRecViewerCmd.class, true);
    }

}
