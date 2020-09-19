package sc.fiji.snt.plugin;

import ij.ImagePlus;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SkeletonConverter;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.ChooseDatasetCmd;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;


@Plugin(type = Command.class, visible = false, label="Tree(s) from Skeleton Image...", initializer = "init")
public class SkeletonConverterCmd extends ChooseDatasetCmd {

	@Parameter(label = "Skeletonize image", description="Wether the segmented image should be skeletonized.<br>"
			+ "Unnecessary if segmented image is already a topological sekeleton")
	private boolean skeletonizeImage;

	@Parameter(label = "Prune singletons", description = "Wether single-node structures (isolated voxels) should be ignored")
	private boolean pruneSingletons;

	@Parameter(label = "Replace existing paths", description = "Wether any existing paths should be cleared before conversion")
	private boolean clearExisting;

	@Override
	protected void init() {
		super.init();
		final MutableModuleItem<String> mItem = getInfo().getMutableInput("choice", String.class);
		final List<String> choices = mItem.getChoices(); // UnmodifiableCollection
		final List<String> impList = (choices == null) ? new ArrayList<>() : new ArrayList<>(choices);
		final boolean accessToValidImageData = snt.accessToValidImageData();
		if (accessToValidImageData) {
			unresolveInput("choice");
			impList.add("Copy of data being traced");
			if (snt.getImagePlus().getProcessor().isBinary())
				impList.add("Data being traced");
			mItem.setChoices(impList);
		}
		if (!impList.isEmpty()) {
			mItem.setLabel("Segmented Image");
			mItem.setDescription("<HTML>The skeletonized image from which paths will be extracted.<br>"
					+ "Assumed to be binary.");
		}
	}

	@Override
	protected void resolveInputs() {
		super.resolveInputs();
		resolveInput("skeletonizeImage");
		resolveInput("pruneSingletons");
		resolveInput("clearExisting");
	}

	@Override
	public void run() {

		if (choice == null || impMap == null || impMap.isEmpty()) {
			error("To run this command you need to first open, in Fiji, a "
					+ "segmented/skeletonized image from which paths can be "
					+ "extracted.");
			return;
		}

		boolean ensureChosenImpIsVisible = false;
		ImagePlus chosenImp;
		if ("Copy of data being traced".equals(choice)) {
			chosenImp = snt.getLoadedDataAsImp();
			ensureChosenImpIsVisible = chosenImp.getBitDepth() > 8 || skeletonizeImage
					|| snt.getImagePlus().getNChannels() > 1 || snt.getImagePlus().getNFrames() > 1;
		} else if ("Data being traced".equals(choice)) {
			chosenImp = snt.getImagePlus();
		} else {
			chosenImp = impMap.get(choice);
		}

		if (chosenImp.getBitDepth() != 8) {
			String msg = "The segmented/skeletonized image must be 8-bit.";
			if (ensureChosenImpIsVisible) msg += " Please simplify " + chosenImp.getTitle() + ", and re-run";
			error(msg);
			return;
		}

		final boolean isBinary = chosenImp.getProcessor().isBinary();
		final boolean isCompatible = isCalibrationCompatible(chosenImp);
		if (!isBinary || !isCompatible) {
			final StringBuilder sb = new StringBuilder("<HTML><div WIDTH=600><p>The following issues were detected:</p><ul>");
			if (!isBinary) {
				sb.append("<li>Image is not binary: ");
				if (skeletonizeImage) {
					sb.append("Skeletonization will consider foreground to be any non-zero value.</li>");
				} else {
					sb.append("Image does not seem segmented, and thus, not a skeleton.</li>");
				}
			}
			if (!isCompatible) {
				sb.append("<li>Images do not share the same spatial calibration.</li>");
			}
			sb.append("</ul>");
			if (!new GuiUtils().getConfirmation(sb.toString(), "Proceed Despite Warnings?")) {
				cancel();
				return;
			}
			// User is sure to continue: skeletonize grayscale image
			if (skeletonizeImage && !isBinary) {
				SkeletonConverter.skeletonize(chosenImp);
			}
		}
		status("Creating Trees from Skeleton...", false);
		final SkeletonConverter converter = new SkeletonConverter(chosenImp, skeletonizeImage && isBinary);
		final List<Tree> trees = converter.getTrees();
		final PathAndFillManager pafm = sntService.getPathAndFillManager();
		if (clearExisting) {
			final int[] indices = IntStream.rangeClosed(0, pafm.size() - 1).toArray();
			pafm.deletePaths(indices);
		}
		for (final Tree tree : trees) {
			if (pruneSingletons && tree.getNodes().size() == 1) {
				continue;
			}
			pafm.addTree(tree);
		}

		// Extra user-friendliness: If no display canvas exist, or no image is being traced, 
		// adopt the chosen image as tracing canvas
		if (ensureChosenImpIsVisible) chosenImp.show();
		if (snt.getImagePlus() == null) snt.initialize(chosenImp);

		resetUI();
		status("Successfully created " + trees.size() + " Tree(s)...", true);

	}
}
