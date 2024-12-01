/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.gui.cmds;

import ij.ImagePlus;
import ij.WindowManager;
import net.imagej.ImageJ;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.ImpUtils;

import java.util.*;

/**
 * Implements the 'Choose Tracing Image (From Open Image)...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(initializer = "init", type = Command.class)
public class ChooseDatasetCmd extends CommonDynamicCmd {

	@Parameter(persist = false, required = false,
		style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String choice;

	@Parameter(label = "Validate spatial calibration", required = false,
		description = "Checks whether voxel dimensions of chosen image differ from those of loaded image (if any)")
	private boolean validateCalibration;

	@Parameter(label = "Keep loaded image open", required = false,
		description = "If the image currently loaded should remain open or instead disposed")
	private boolean restoreImg;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean secondaryLayer;

	private HashMap<String, ImagePlus> impMap;

	@Override
	public void run() {
		if (impMap == null || choice == null || impMap.isEmpty()) {
			return;
		}
		ImagePlus chosenImp = impMap.get(choice);
		if (!secondaryLayer && !compatibleCalibration(chosenImp)) {
			cancel();
			return;
		}
		chosenImp = convertInPlaceToCompositeAsNeeded(ui, chosenImp);
		if (chosenImp.getType() == ImagePlus.COLOR_RGB) {
			cancel();
			return;
		}
		if (secondaryLayer) {
			if (!ImpUtils.sameXYZDimensions(snt.getImagePlus(), chosenImp)) {
				error("Dimensions of chosen image differ from those of image being traced.");
			} else {
				snt.flushSecondaryData();
				snt.loadSecondaryImage(chosenImp);
			}
		} else {
			snt.getPrefs().setTemp(SNTPrefs.RESTORE_LOADED_IMGS, restoreImg);
			snt.initialize(chosenImp);
		}
		resetUI();
	}

	protected boolean isCalibrationCompatible(final ImagePlus chosenImp) {
		if (!validateCalibration || !snt.accessToValidImageData())
			return true;
		return ImpUtils.sameCalibration(snt.getImagePlus(), chosenImp);
	}

	private boolean compatibleCalibration(final ImagePlus chosenImp) {
		if (!(isCalibrationCompatible(chosenImp))) {
			final Result result = uiService.showDialog(
					"Images do not share the same spatial calibration.\n"
					+ "Load " + chosenImp.getTitle() + " nevertheless?",
					MessageType.QUESTION_MESSAGE);
			return (Result.YES_OPTION == result || Result.OK_OPTION == result);
		}
		return true;
	}

	protected void init() {
		super.init(true);
		if (!snt.accessToValidImageData() && secondaryLayer) {
			resolveInputs();
			error("A secondary tracing layer can only be used when a main tracing image exists.");
			return;
		}
		final MutableModuleItem<String> mItem = getInfo().getMutableInput("choice", String.class);
		if (secondaryLayer) {
			getInfo().setLabel("Load Secondary Layer");
			mItem.setLabel("New secondary layer:");
			validateCalibration = false;
			resolveInput("validateCalibration");
		} else {
			getInfo().setLabel("Change Tracing Image");
			mItem.setLabel("New tracing image:");
			resolveInput("secondaryLayer");
		}
		final Collection<ImagePlus> impCollection = getImpInstances();
		if (impCollection == null || impCollection.isEmpty()) {
			noImgsOpenError();
			return;
		}
		impMap = new HashMap<>(impCollection.size());
		final ImagePlus existingImp = snt.getImagePlus();
		for (final ImagePlus imp : impCollection) {
			if (imp.equals(existingImp)) continue;
			impMap.put(imp.getTitle(), imp);
		}
		if (impMap.isEmpty()) {
			noImgsOpenError();
			return;
		}
		if (!snt.accessToValidImageData()) {
			restoreImg = false;
			resolveInput("restoreImg");
		}
		final List<String> choices = new ArrayList<>(impMap.keySet());
		Collections.sort(choices);
		mItem.setChoices(choices);
		if (choices.size() > 10) mItem.setWidgetStyle(ChoiceWidget.LIST_BOX_STYLE);
	}

	private void noImgsOpenError() {
		resolveInputs();
		error("No other open images seem to be available.");
	}

	private void resolveInputs() {
		choice = null;
		resolveInput("choice");
		resolveInput("validateCalibration");
		resolveInput("restoreImg");
	}

	public static Collection<ImagePlus> getImpInstances() {
		// In theory, we should be able to use legacyService to retrieve
		// all the images but somehow this can never retrieve the full
		// list of current available instances:
//		return legacyService.getImageMap().getImagePlusInstances();
		final String[] titles = WindowManager.getImageTitles();
		final Collection<ImagePlus> imps = new ArrayList<>();
		for (final String title : titles) {
			// ignore side panes
			//if (title.startsWith("ZY [") || title.startsWith("XZ [")) continue;
			imps.add(WindowManager.getImage(title));
		}
		return imps;
	}

	public static ImagePlus getCurrentImage() {
//		return legacyService.getImageMap().lookupImagePlus(imageDisplayService.getActiveImageDisplay());;
		return WindowManager.getCurrentImage();
	}

	protected static ImagePlus convertInPlaceToCompositeAsNeeded(final SNTUI ui, final ImagePlus imp) {
		if (imp != null && imp.getType() == ImagePlus.COLOR_RGB && new GuiUtils((ui==null)?imp.getWindow():ui).getConfirmation(
				"RGB images are (intentionally) not supported by SNT. You can however convert " + imp.getTitle()
						+ " to a multichannel image. Would you like to do it now? (Import will abort if you choose \"No\")",
				"Convert to Multichannel?")) {
			return ImpUtils.convertRGBtoComposite(imp);
		}
		return imp;
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ChooseDatasetCmd.class, true);
	}

}
