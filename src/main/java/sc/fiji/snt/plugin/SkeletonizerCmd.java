/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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

import java.awt.image.IndexColorModel;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.LutLoader;
import ij.process.ImageProcessor;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.ImpUtils;

/**
 * Convenience command for converting Paths into skeleton images
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", label = "Convert Paths to Topographic Skeletons")
public class SkeletonizerCmd extends CommonDynamicCmd {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final String NO_RESTRICTION = "None (Convert complete paths)";
	private static final String MATERIALIZED_REGION_RESTRICTION = "Convert only paths within the materialized region";
	private static final String ROI_RESTRICTION = "Convert only path segments contained by ROI";

	@Parameter(required = false, label = "Output", style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, choices = {
			"Binary (all paths have the same intensity)", "Labels (each path has an unique intensity)" })
	private String imgChoice;

	@Parameter(required = false, label = "Roi filtering", style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
			choices = { NO_RESTRICTION, ROI_RESTRICTION })
	private String roiChoice;

	@Parameter(required = false, label = "Run \"Analyze Skeleton\" after conversion")
	private boolean callAnalyzeSkeleton;

	@Parameter(required = true)
	private Tree tree;


	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		if (snt != null && snt.isStreamMode()) {
			if (snt.isMaterializedCrop()) {
				// BDV/BVV have no ROI concept, but a materialized crop (see SNT#isMaterializedCrop()) defines an
				// analogous region to restrict to
				final MutableModuleItem<String> mi = getInfo().getMutableInput("roiChoice", String.class);
				mi.setChoices(List.of(NO_RESTRICTION, MATERIALIZED_REGION_RESTRICTION));
				roiChoice = MATERIALIZED_REGION_RESTRICTION;
			} else {
				resolveInput("roiChoice"); // No crop materialized: nothing to restrict to
				roiChoice = NO_RESTRICTION;
			}
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		if (tree == null || tree.isEmpty()) {
			error("No Paths to convert.");
			return;
		}
		if (snt == null) {
			error("No active instance of SNT was found.");
			return;
		}

		final ImagePlus imp = snt.getImagePlus();
		final boolean displayCanvas = !snt.accessToValidImageData();
		final boolean twoDDisplayCanvas =  imp != null && imp.getNSlices() == 1 && tree.is3D();
		final boolean useNewImage = displayCanvas || twoDDisplayCanvas;

		// Stream mode's analog of classic mode's ROI restriction below: skeletonization is already spatially confined
		// to a materialized crop (SNT#makePathVolume() rasterizes into the crop's own, small array; nodes outside it
		// are silently dropped), but every Path in the tree would still be walked to discover that, wasting Bresenham3D
		// computation. Pre-filter to just the whole paths that intersect the crop's world bounds instead
		final boolean restrictToMaterializedRegion = !useNewImage && snt.isStreamMode() && snt.isMaterializedCrop()
				&& MATERIALIZED_REGION_RESTRICTION.equals(roiChoice);

		final Roi roi = (imp == null) ? null : imp.getRoi();
		boolean restrictByRoi = !restrictToMaterializedRegion && !roiChoice.startsWith("None");
		final boolean validAreaRoi = (roi == null || !roi.isArea());
		if (restrictByRoi && validAreaRoi) {
			if (!getConfirmationEdtSafe(
				"ROI filtering requested but no area ROI was found.\n" +
					"Proceed without ROI filtering?", "Proceed Without ROI Filtering?"))
				return;
			restrictByRoi = false;
		}

		Collection<Path> pathsToConvert = tree.list();
		if (restrictToMaterializedRegion) {
			final BoundingBox cropBounds = snt.getMaterializedCropWorldBounds();
			if (cropBounds != null) {
				pathsToConvert = tree.list().stream()
						.filter(p -> p.getNodes().stream().anyMatch(cropBounds::contains))
						.collect(Collectors.toList());
				if (pathsToConvert.isEmpty()) {
					error("No paths intersect the materialized region.");
					return;
				}
			}
		}

		setStatus("Converting paths to skeletons...");
		final boolean asLabelsImage = imgChoice.startsWith("Labels");
		try {
			final ImagePlus imagePlus = (useNewImage)
					? tree.getSkeleton((asLabelsImage) ? -1 : 255)
					: snt.makePathVolume(pathsToConvert, asLabelsImage);
			if (asLabelsImage) {
				final IndexColorModel model = LutLoader.getLut("glasbey_on_dark");
				if (model != null) imagePlus.getProcessor().setColorModel(model);
				imagePlus.setDisplayRange(0, pathsToConvert.size());
			} else {
				ImpUtils.convertTo8bit(imagePlus);
			}
			if (restrictByRoi && roi.isArea()) {
				final ImageStack stack = imagePlus.getStack();
				for (int i = 1; i <= stack.getSize(); i++) {
					final ImageProcessor ip = stack.getProcessor(i);
					ip.setValue(0);
					ip.fillOutside(roi);
				}
				imagePlus.setRoi(roi);
			}
			if (callAnalyzeSkeleton) {
				final Skeletonize3D_ skeletonizer = new Skeletonize3D_();
				skeletonizer.setup("", imagePlus);
				skeletonizer.run(imagePlus.getProcessor());
				final AnalyzeSkeleton_ analyzer = new AnalyzeSkeleton_();
				analyzer.setup("", imagePlus);
				analyzer.run(imagePlus.getProcessor());
			}
			imagePlus.show();
		}
		catch (final OutOfMemoryError error) {
			final String msg = "Out of Memory: There is not enough RAM to perform skeletonization under "
					+ "current options. Please allocate more memory to Fiji, downsample the paths, "
					+ " or consider skeletonization through API scripting";
			error(msg);
		} finally {
			resetUI();
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", new Tree());
		ij.command().run(SkeletonizerCmd.class, true, input);
	}

}
