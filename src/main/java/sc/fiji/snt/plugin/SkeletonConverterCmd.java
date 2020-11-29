/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

package sc.fiji.snt.plugin;

import ij.ImagePlus;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;

import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SkeletonConverter;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.ChooseDatasetCmd;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Command providing a GUI for {@link SkeletonConverter}
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label="Tree(s) from Skeleton Image...", initializer = "init")
public class SkeletonConverterCmd extends ChooseDatasetCmd {

	@Parameter(label="Skeletonize image", description="<HTML>Wether the segmented image should be skeletonized.<br>"
			+ "Unnecessary if segmented image is already a topological sekeleton")
	private boolean skeletonizeImage;

	@Parameter(label="<HTML>&nbsp;", required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(label="Prune by length", description="<HTML>Whether to remove sub-threshold length trees from the result")
	private boolean pruneByLength;

	@Parameter(label="Length threshold", description="<HTML>The minimum tree length necessary to avoid pruning.<br>" +
			"This value is only used if \"Prune by length\" is enabled.")
	private double lengthThreshold;

	@Parameter(label="Connect components", description="<HTML>If the skeletonized image is fragmented into multiple components:<br>"
			+ "Should individual components be connected?")
	private boolean connectComponents;

	@Parameter(label="Max. connection distance", min = "0.0", description="<HTML>The maximum allowable distance between the " +
			"closest pair of points for two components to be merged.<br>"
			+ "This value is only used if \"Connect components\" is enabled")
	private double maxConnectDist;

	@Parameter(label="Replace existing paths", description="<HTML>Whether any existing paths should be cleared " +
			"before conversion")
	private boolean clearExisting;

	@Override
	protected void init() {
		super.init(true);

		final MutableModuleItem<String> mItem = getInfo().getMutableInput("choice", String.class);
		mItem.setWidgetStyle(ChoiceWidget.LIST_BOX_STYLE);
		mItem.setLabel("Segmented Image");
		mItem.setDescription("<HTML>The skeletonized image from which paths will be extracted.<br>"//
				+ "Assumed to be binary.");
		final List<String> choices = new ArrayList<>();

		// Populate choices with list of open images
		final Collection<ImagePlus> impCollection = getImpInstances();
		if (impCollection != null && !impCollection.isEmpty()) {
			impMap = new HashMap<>(impCollection.size());
			final ImagePlus existingImp = snt.getImagePlus();
			for (final ImagePlus imp : impCollection) {
				if (!imp.equals(existingImp)) {
					impMap.put(imp.getTitle(), imp);
					choices.add(imp.getTitle());
				}
			}
			if (!impMap.isEmpty()) Collections.sort(choices);
		}

		final boolean accessToValidImageData = snt.accessToValidImageData();
		if (accessToValidImageData) {
			//unresolveInput("choice");
			if (snt.getImagePlus().getProcessor().isBinary()) {
				choices.add(0, "Data being traced");
				choices.add(1, "Copy of data being traced");
			} else {
				choices.add(0, "Copy of data being traced");
			}
		}
		if (choices.isEmpty()) {
			cancel("No Images are currently available.\n"
					+ "Perhaps you'd like to run 'Script> Skeletons and ROIs> Reconstruction From Skeleton' instead?");
		}
		mItem.setChoices(choices);
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

		if (choice == null) { // this should never happen
			error("To run this command you need to first select a segmented/"
					+ "skeletonized image from which paths can be extracted.");
			return;
		}

		if (connectComponents && maxConnectDist <= 0d) {
			error("Max. connection distance must be > 0.");
			return;
		}

		boolean ensureChosenImpIsVisible = false;
		ImagePlus chosenImp;
		if ("Copy of data being traced".equals(choice)) {
			/* Make deep copy of imp returned by getLoadedDataAsImp() since it holds references to
			 the same pixel arrays as used by the source data */
			chosenImp = snt.getLoadedDataAsImp().duplicate();
			ensureChosenImpIsVisible = chosenImp.getBitDepth() > 8 || skeletonizeImage
					|| snt.getImagePlus().getNChannels() > 1 || snt.getImagePlus().getNFrames() > 1;
		} else if ("Data being traced".equals(choice)) {
			chosenImp = snt.getLoadedDataAsImp();
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
			final StringBuilder sb = new StringBuilder("<HTML><div WIDTH=600><p>The following issue(s) were detected:</p><ul>");
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
				if (ensureChosenImpIsVisible) chosenImp.show();
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
		converter.setPruneByLength(pruneByLength);
		converter.setLengthThreshold(lengthThreshold);
		converter.setConnectComponents(connectComponents);
		converter.setMaxConnectDist(maxConnectDist);
		final List<Tree> trees = converter.getTrees();
		final PathAndFillManager pafm = sntService.getPathAndFillManager();
		if (clearExisting) {
			final int[] indices = IntStream.rangeClosed(0, pafm.size() - 1).toArray();
			pafm.deletePaths(indices);
		}
		for (final Tree tree : trees) {
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
