package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.GraphViewer;

import java.util.*;

@Plugin(type = Command.class, visible = false, label = "Create Annotation Graph")
public class AnnotationGraphGeneratorCmd extends NewGraphOptionsCmd {

    @Parameter
    protected StatusService statusService;

    @Parameter(label = "Trees")
    private Collection<Tree> trees;

    @Parameter(label = "Compartment", choices = {"All", "Axon", "Dendrites"})
    private String compartment;

    @Override
    public void run() {
        if (trees == null || trees.isEmpty()) {
            cancel("Invalid Tree Collection");
            return;
        }
        final List<Tree> annotatedTrees = new ArrayList<>();
        for (Tree tree : trees) {
            if (!tree.isAnnotated()) {
                SNTUtils.log(tree.getLabel() + " does not have neuropil labels. Skipping...");
                continue;
            }
            if (!compartment.equals("All")) {
                tree = tree.subTree(compartment);
                if (tree.isEmpty()) {
                    SNTUtils.log(tree.getLabel()
                            + " does not contain processes tagged as \"" + compartment + "\". Skipping...");
                    continue;
                }
            }
            annotatedTrees.add(tree);
        }
        if (annotatedTrees.isEmpty()) {
            cancel("<HTML><div WIDTH=600>None of the selected Trees meet the necessary criteria.<br>" +
                    "Please ensure the selected Trees" +
                    " contain processes tagged as \"" + compartment + "\" and are annotated with neuropil labels.");
            return;
        }
        statusService.showStatus("Generating graph...");
        final AnnotationGraph annotationGraph = new AnnotationGraph(annotatedTrees, metric, threshold, depth);
        final GraphViewer graphViewer = new GraphViewer(annotationGraph);
        graphViewer.setContext(getContext());
        graphViewer.show();
        SNTUtils.log("Finished. Graph created from " + annotatedTrees.size() + " Trees.");
        statusService.clearStatus();
    }

    /* IDE debug method **/
    public static void main(final String[] args) {
        GuiUtils.setSystemLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        final Collection<Tree> trees = new SNTService().demoTrees();
        final Map<String, Object> inputs = new HashMap<>();
        inputs.put("trees", trees);
        ij.command().run(AnnotationGraphGeneratorCmd.class, true, inputs);
    }

}
