package sc.fiji.snt.plugin;

import ij.ImagePlus;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SkeletonConverter;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

import java.util.List;
import java.util.stream.IntStream;


@Plugin(type = Command.class, visible = false, label="Trees from Skeleton Image...", initializer = "init")
public class SkeletonConverterCmd extends CommonDynamicCmd {

    @Parameter(label="Skeletonize image")
    private boolean skeletonizeImage;

    @Parameter(label="Show skeleton")
    private boolean showSkeleton;

    @Parameter(label="Prune singletons")
    private boolean pruneSingletons;

    @Parameter(label="Replace existing paths")
    private boolean clearExisting;

    protected void init() {
        super.init(true);
        if (!snt.accessToValidImageData()) {
            cancel("Valid image data is required for computation.");
        }
    }

    @Override
    public void run() {
        status("Creating Trees from Skeleton...", false);
        final ImagePlus inputImp = snt.getLoadedDataAsImp().duplicate();
        SkeletonConverter converter = new SkeletonConverter(inputImp, skeletonizeImage);
        List<Tree> trees = converter.getTrees();
        final PathAndFillManager pafm = sntService.getPathAndFillManager();
        if (clearExisting) {
            final int[] indices = IntStream.rangeClosed(0, pafm.size() - 1).toArray();
            pafm.deletePaths(indices);
        }
        for (Tree tree : trees) {
            if (pruneSingletons && tree.getNodes().size() == 1) {
                continue;
            }
            pafm.addTree(tree);
        }
        if (skeletonizeImage && showSkeleton) {
            inputImp.show();
        }
        resetUI();
        status("Successfully created " + trees.size() + " Tree(s)...", true);
    }
}
