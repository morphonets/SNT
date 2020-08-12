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
        Tree tree = graph.getTree();
        if (tree == null) cancel("Invalid Tree");
        Viewer3D viewer = new Viewer3D(getContext());
        viewer.addTrees(Collections.singleton(tree), true);
        viewer.show();
    }

}
