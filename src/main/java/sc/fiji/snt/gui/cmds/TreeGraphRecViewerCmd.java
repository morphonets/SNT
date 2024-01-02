/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.viewer.geditor.TreeGraphAdapter;

import java.util.Collections;

@Plugin(type = Command.class, visible = false, label = "Show Tree Graph in Rec. Viewer")
public class TreeGraphRecViewerCmd extends DynamicCommand {

    @Parameter(label="adapter")
    private TreeGraphAdapter adapter;

    @Override
    public void run() {
        if (adapter == null) cancel("Invalid adapter");
        DirectedWeightedGraph graph = (DirectedWeightedGraph) adapter.getSourceGraph();
        Tree tree = graph.getTree(false);
        if (tree == null) cancel("Invalid Tree");
        Viewer3D viewer = new Viewer3D(getContext());
        viewer.addTrees(Collections.singleton(tree), "unique");
        viewer.show();
    }

}
