package sc.fiji.snt.viewer;

import net.imagej.ImageJ;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.plugin.Parameter;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.viewer.geditor.*;

import javax.swing.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class GraphViewer {
    @Parameter
    private Context context;
    private final SNTGraph<?, ? extends DefaultWeightedEdge> graph;
    private SNTGraphAdapter<?, ? extends DefaultWeightedEdge> adapter;
    private SNTGraphComponent component;
	private GraphEditor editor;

    public GraphViewer(final SNTGraph<?, ? extends DefaultWeightedEdge> inputGraph) {
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
        } else if (graph instanceof SNTPseudograph) {
            adapter = new SNTPseudographAdapter<>((SNTPseudograph<?, ? extends DefaultWeightedEdge>) this.graph);
            component = new SNTPseudographComponent((SNTPseudographAdapter<?, ? extends DefaultWeightedEdge>)adapter, getContext());
        } else {
            throw new UnsupportedOperationException("Unsupported Graph Type.");
        }
        GuiUtils.setSystemLookAndFeel();
        editor = new GraphEditor("Graph Viewer", component);
        editor.setContext(getContext());
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
        cellIds.add("AA0100");
        cellIds.add("AA0788");
        cellIds.add("AA1044");
        List<Tree> trees = new ArrayList<>();
        for (String id : cellIds) {
            Tree tree = new MouseLightLoader(id).getTree("axon");
            trees.add(tree);
        }
        //List<Tree> trees = ij.context().getService(SNTService.class).demoTrees();
        final AnnotationGraph graph = new AnnotationGraph(trees, "tips", 5, 7);
        //DirectedWeightedGraph graph = trees.get(0).getGraph(true);
        //GraphColorMapper<SWCPoint, SWCWeightedEdge> mapper = new GraphColorMapper<>(ij.context());
        //mapper.map(graph, GraphColorMapper.EDGE_WEIGHT, "Ice");
        //graph.filterEdgesByWeight(20);
        // graph.removeOrphanedNodes();
        GraphViewer graphViewer = new GraphViewer(trees.get(0).getGraph(true));
        graphViewer.setContext(ij.context());
        graphViewer.getEditor().setLegend("cool.lut", "Metric", 0d, 1000);
        graphViewer.show();
    }
}