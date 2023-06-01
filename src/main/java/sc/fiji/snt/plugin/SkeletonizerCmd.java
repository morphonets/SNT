/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
import java.util.HashMap;
import java.util.Map;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.ui.UIService;
import org.scijava.widget.ChoiceWidget;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.LutLoader;
import ij.process.ImageProcessor;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Convenience command for converting Paths into skeleton images
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Convert Paths to Topographic Skeletons")
public class SkeletonizerCmd implements Command {

	@Parameter
	private UIService uiService;

	@Parameter
	private SNTService sntService;

	@Parameter(required = false, label = "Output", style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, choices = {
			"Binary (all paths have the same intensity)", "Labels (each path has an unique intensity)" })
	private String imgChoice;

	@Parameter(required = false, label = "Roi filtering", style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, choices = {
			"None (Convert complete paths)", "Convert only path segments contained by ROI" })
	private String roiChoice;

	@Parameter(required = false, label = "Run \"Analyze Skeleton\" after conversion")
	private boolean callAnalyzeSkeleton;

	@Parameter(required = true)
	private Tree tree;

	private SNT plugin;

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
		plugin = sntService.getPlugin();
		if (plugin == null) {
			error("No active instance of SNT was found.");
			return;
		}

		final ImagePlus imp = plugin.getImagePlus();
		final boolean displayCanvas = !plugin.accessToValidImageData();
		final boolean twoDdisplayCanvas =  imp != null && imp.getNSlices() == 1 && tree.is3D();
		final boolean useNewImage = displayCanvas || twoDdisplayCanvas;

		final Roi roi = (imp == null) ? null : imp.getRoi();
		boolean restrictByRoi = !roiChoice.startsWith("None");
		final boolean validAreaRoi = (roi == null || !roi.isArea());
		if (restrictByRoi && validAreaRoi) {
			if (!getConfirmation(
				"ROI filtering requested but no area ROI was found.\n" +
					"Proceed without ROI filtering?", "Proceed Without ROI Filtering?"))
				return;
			restrictByRoi = false;
		}

		plugin.showStatus(0, 0, "Converting paths to skeletons...");
		final boolean asLabelsImage = imgChoice.startsWith("Labels");
		try {
			final ImagePlus imagePlus = (useNewImage) ? tree.getSkeleton((asLabelsImage) ? -1 : 255) : plugin.makePathVolume(tree.list(), asLabelsImage);
			if (asLabelsImage) {
				final IndexColorModel model = LutLoader.getLut("glasbey_on_dark");
				if (model != null)
					imp.getProcessor().setColorModel(model);
				imagePlus.setDisplayRange(0, tree.size());
			} else {
				SNTUtils.convertTo8bit(imagePlus);
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
					+ "current options. Please allocate more memory to IJ, downsample the reconstruction, "
					+ " or consider skeletonization through API scripting";
			error(msg);
		}
	}

	private boolean getConfirmation(final String msg, final String title) {
		final Result res = uiService.getDefaultUI().dialogPrompt(msg, title,
			DialogPrompt.MessageType.QUESTION_MESSAGE,
			DialogPrompt.OptionType.YES_NO_OPTION).prompt();
		return Result.YES_OPTION.equals(res);
	}

	private void error(final String msg) {
		// With HTML errors, uiService will not use the java.awt legacy messages
		// that do not scale in hiDPI
		uiService.getDefaultUI().dialogPrompt("<HTML>" + msg, "Error",
			DialogPrompt.MessageType.ERROR_MESSAGE,
			DialogPrompt.OptionType.DEFAULT_OPTION).prompt();
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
