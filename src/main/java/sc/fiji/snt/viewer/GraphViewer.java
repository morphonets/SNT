package sc.fiji.snt.viewer;

import net.imagej.ImageJ;

import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.plugin.Parameter;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.geditor.AnnotationGraphAdapter;
import sc.fiji.snt.viewer.geditor.AnnotationGraphComponent;
import sc.fiji.snt.viewer.geditor.GraphEditor;
import sc.fiji.snt.viewer.geditor.SNTGraphAdapter;
import sc.fiji.snt.viewer.geditor.SNTGraphComponent;
import sc.fiji.snt.viewer.geditor.TreeGraphAdapter;
import sc.fiji.snt.viewer.geditor.TreeGraphComponent;

import javax.swing.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class GraphViewer {
    @Parameter
    private Context context;
    private final SNTGraph<?, ?> graph;
    private SNTGraphAdapter<?, ?> adapter;
    private SNTGraphComponent component;
	private GraphEditor editor;

    public GraphViewer(final SNTGraph<?, ?> inputGraph) {
        this.graph = inputGraph;
    }

    public void setContext(final Context context) {
        if (context == null) throw new NullContextException("Context cannot be null!");
        context.inject(this);
    }

    private Context getContext() {
        if (context == null)
            setContext(new Context());
        return context;
    }

    public GraphEditor getEditor() {
    	if (editor == null) initEditor();
    	return editor;
    }

    private void initEditor() {
        if (graph instanceof DirectedWeightedGraph) {
            adapter = new TreeGraphAdapter((DirectedWeightedGraph)this.graph);
            component = new TreeGraphComponent((TreeGraphAdapter) adapter, getContext());
        } else if (this.graph instanceof AnnotationGraph) {
            adapter = new AnnotationGraphAdapter((AnnotationGraph) this.graph);
            component = new AnnotationGraphComponent((AnnotationGraphAdapter) adapter, getContext());
        } else {
            throw new UnsupportedOperationException("Currently only DirectedWeightedGraph and AnnotationGraph are supported.");
        }
        GuiUtils.setSystemLookAndFeel();
        editor = new GraphEditor("Graph Viewer", component);
    }

    /**
     * Displays a graph in SNT's "Graph Viewer" featuring UI commands for
     * interactive visualization and export options.
     *
     * @return the assembled window
     */
    public Window show() {
    	if (editor == null) initEditor();
        JFrame frame = editor.createFrame(getContext());
        //frame.pack(); //FIXME: Don't pack() otherwise stall occurs on openjdk
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        return frame;
    }

    public static void main(final String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTUtils.setDebugMode(true);
        List<String> cellIds = new ArrayList<>();
        cellIds.add("AA0004");
        cellIds.add("AA0100");
        cellIds.add("AA0788");
        cellIds.add("AA1044");
        cellIds.add("AA0023");
        cellIds.add("AA0310");
        cellIds.add("AA0824");
        cellIds.add("AA0017");
        cellIds.add("AA0345");
        List<Tree> trees = new ArrayList<Tree>();
        for (String id : cellIds) {
            Tree tree = new MouseLightLoader(id).getTree("axon");
            trees.add(tree);
        }
        //List<Tree> trees = ij.context().getService(SNTService.class).demoTrees();
        //final AnnotationGraph graph = new AnnotationGraph(trees, "branches", 5, 8);
        DirectedWeightedGraph graph = trees.get(0).getGraph(true);
        GraphColorMapper<SWCPoint, SWCWeightedEdge> mapper = new GraphColorMapper<>(ij.context());
        mapper.map(graph, GraphColorMapper.EDGE_WEIGHT, "Ice");
        //graph.filterEdgesByWeight(20);
        // graph.removeOrphanedNodes();
        GraphViewer graphViewer = new GraphViewer(graph);
        graphViewer.setContext(ij.context());
        graphViewer.getEditor().setLegend("cool.lut", "Metric", 0d, 1000);
        graphViewer.show();
    }
}