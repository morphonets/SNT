package sc.fiji.snt.viewer;

import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.view.mxInteractiveCanvas;
import com.mxgraph.view.mxCellState;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;

import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;

import javax.swing.*;
import javax.swing.border.BevelBorder;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class GraphViewer {
    @Parameter
    private Context context;
    private final SNTGraph graph;
    private SNTGraphAdapter adapter;
    private SNTGraphComponent component;

    public GraphViewer(final SNTGraph inputGraph) {
        this.graph = inputGraph;
    }

    public void setContext(final Context context) {
        if (context == null) throw new NullContextException("Context cannot be null!");
        context.inject(this);
    }

    private Context getContext() {
        if (context == null)
            setContext(new Context(CommandService.class, LUTService.class, PrefService.class));
        return context;
    }

    /**
     * Displays a graph in SNT's "Graph Viewer" featuring UI commands for
     * interactive visualization and export options.
     *
     * @return the assembled window
     */
    public Window show() {
        if (this.graph instanceof DirectedWeightedGraph) {
            adapter = new TreeGraphAdapter((DirectedWeightedGraph)this.graph);
            component = new TreeGraphComponent((TreeGraphAdapter) adapter, getContext());
        } else if (this.graph instanceof AnnotationGraph) {
            adapter = new AnnotationGraphAdapter((AnnotationGraph) this.graph);
            component = new AnnotationGraphComponent((AnnotationGraphAdapter) adapter, getContext());
//            {
//                /**
//                 *
//                 */
//                private static final long serialVersionUID = 4683716829748931448L;
//
//                public mxInteractiveCanvas createCanvas()
//                {
//                    return new SwingCanvas(this);
//                }
//            };

        } else {
            throw new UnsupportedOperationException("Currently only DirectedWeightedGraph and AnnotationGraph are supported.");
        }
        GuiUtils.setSystemLookAndFeel();
        final JSplitPane pane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, component.getControlPanel(), component);
        JFrame frame = new JFrame("Graph Viewer");
        frame.add(pane);
        frame.pack();
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        return frame;
    }

    public class SwingCanvas extends mxInteractiveCanvas
    {
        protected CellRendererPane rendererPane = new CellRendererPane();

        protected JLabel vertexRenderer = new JLabel();

        protected mxGraphComponent graphComponent;

        public SwingCanvas(mxGraphComponent graphComponent)
        {
            this.graphComponent = graphComponent;

            vertexRenderer.setBorder(
                    BorderFactory.createBevelBorder(BevelBorder.RAISED));
            vertexRenderer.setHorizontalAlignment(JLabel.CENTER);
            vertexRenderer
                    .setBackground(graphComponent.getBackground().darker());
            vertexRenderer.setOpaque(true);
        }

        public void drawVertex(mxCellState state, String label)
        {
            vertexRenderer.setText(label);
            // TODO: Configure other properties...

            rendererPane.paintComponent(g, vertexRenderer, graphComponent,
                    (int) (state.getX() + translate.getX()),
                    (int) (state.getY() + translate.getY()),
                    (int) state.getWidth(), (int) state.getHeight(), true);
        }

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
        List<Tree> trees = new ArrayList<Tree>();
        for (String id : cellIds) {
            Tree tree = new MouseLightLoader(id).getTree("axon");
            trees.add(tree);
        }
        //List<Tree> trees = ij.context().getService(SNTService.class).demoTrees();
        final AnnotationGraph graph = new AnnotationGraph(trees, 40, 7);
        //graph.filterEdgesByWeight(20);
        // graph.removeOrphanedNodes();
        GraphViewer graphViewer = new GraphViewer(graph);
        graphViewer.setContext(ij.context());
        graphViewer.show();
    }
}