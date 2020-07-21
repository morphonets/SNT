package sc.fiji.snt.analysis.graph;

import net.imagej.ImageJ;

import org.jgrapht.Graph;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.gui.GuiUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AnnotationGraphUtils {

    private AnnotationGraphUtils() {
    }

    /**
     * Displays a graph in SNT's "Graph Viewer" featuring UI commands for
     * interactive visualization and export options.
     *
     * @param graph the graph to be displayed
     * @return the assembled window
     */
    public static Window show(final Graph<BrainAnnotation, AnnotationWeightedEdge> graph) {
        GuiUtils.setSystemLookAndFeel();
        final JDialog frame = new JDialog((JFrame) null, "SNT Graph Viewer");
        final AnnotationGraphAdapter graphAdapter = new AnnotationGraphAdapter(graph);
        final ImageJ ij = new ImageJ();
        final AnnotationGraphComponent graphComponent = new AnnotationGraphComponent(graphAdapter, ij.context());
        frame.add(graphComponent.getJSplitPane());
        frame.pack();
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        return frame;
    }

    public static void main(final String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTUtils.setDebugMode(true);
//        SNTService sntService = ij.context().getService(SNTService.class);
//        List<String> cellIds = new ArrayList<>();
//        cellIds.add("AA0004");
//        cellIds.add("AA0100");
//        cellIds.add("AA0788");
//        cellIds.add("AA1044");
//        for (String id : cellIds) {
//            Tree tree = new MouseLightLoader(id).getTree("axon");
//            trees.add(tree);
//        }
        List<Tree> trees = new ArrayList<Tree>();
        File cellDir = new File("C:\\Users\\cam\\Desktop\\ML-neurons-Thalamus");
        for (final File file : cellDir.listFiles()) {
            if (file.isFile()) {
                Tree tree = new Tree(file.getAbsolutePath()).subTree("axon");
                trees.add(tree);
            }
        }
        final AnnotationGraph graph = new AnnotationGraph(trees, 200,  6);
//        graph.filterEdgesByWeight(20);
        graph.removeOrphanedNodes();
        AnnotationGraphUtils.show(graph);
    }

}
