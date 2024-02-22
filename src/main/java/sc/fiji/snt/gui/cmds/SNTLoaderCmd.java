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

import io.scif.services.DatasetIOService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.imagej.display.ImageDisplayService;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.DynamicCommand;
import org.scijava.convert.ConvertService;
import org.scijava.display.DisplayService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.FileWidget;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.CompositeConverter;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.SNTUtils;

/**
 * Command for Launching SNT
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = true,
	menuPath = "Plugins>Neuroanatomy>SNT...", label ="SNT Startup Prompt",
	initializer = "initialize")
public class SNTLoaderCmd extends DynamicCommand {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	private SNTService sntService;
	@Parameter
	private CommandService cmdService;
	@Parameter
	private DatasetIOService datasetIOService;
	@Parameter
	private DisplayService displayService;
	@Parameter
	private ImageDisplayService imageDisplayService;
	@Parameter
	private ConvertService convertService;
	@Parameter
	private UIService uiService;

	private static final String IMAGE_NONE = "None. Use a display canvas";
	private static final String IMAGE_FILE = "Image file from path specified below";
	private static final String UI_SIMPLE = "Memory saving: Only XY view";
	private static final String UI_DEFAULT = "Default: XY, ZY and XZ views";
	private static final String DEF_DESCRIPTION =
		"Optional. Ignored when a display canvas is used";

	@Parameter(required = true, label = "Image", //
			description = "The image to be traced (optional). If binary, it will be eligible for automated reconstruction.", //
			callback = "imageChoiceChanged")
	private String imageChoice;

	@Parameter(required = false, label = "Image file",
			description = "<HTML>Image file, when <i>Image</i> choice is <i>"+ IMAGE_FILE +"</i> (optional)",
		style = FileWidget.OPEN_STYLE, callback = "imageFileChanged")
	private File imageFile;

	@Parameter(required = false, label = "<HTML>&nbsp;",
		visibility = ItemVisibility.MESSAGE)
	private String SPACER1;

	@Parameter(required = false, label = "Reconstruction file", //
			description="The reconstruction file to be loaded (.traces, .(e)swc or .json) (optional)",
		style = FileWidget.OPEN_STYLE, callback = "tracesFileChanged")
	private File tracesFile;

	@Parameter(required = false, label = "<HTML>&nbsp;",
		visibility = ItemVisibility.MESSAGE)
	private String SPACER2;

	@Parameter(required = false, label = "User interface", choices = { UI_DEFAULT,
		UI_SIMPLE }, description = DEF_DESCRIPTION + " or image is 2D")
	private String uiChoice;

	@Parameter(required = false, label = "Tracing channel",
		description = DEF_DESCRIPTION, min = "1", //
		max = ""+ ij.CompositeImage.MAX_CHANNELS +"", callback = "channelChanged")
	private int channel;

	private Collection<ImagePlus> openImps;
	private ImagePlus sourceImp;
	private File currentImageFile;

	@Override
	public void initialize() {
		if (sntService.isActive() && sntService.getUI() != null) {
			exit("SNT seems to be already running.");
			return;
		}
		// TODO: load defaults from prefService?
		openImps = ChooseDatasetCmd.getImpInstances();
		sourceImp = ChooseDatasetCmd.getCurrentImage();
		final MutableModuleItem<String> imageChoiceInput = getInfo().getMutableInput("imageChoice", String.class);
		final List<String> choices = new ArrayList<>();
		choices.add(IMAGE_NONE);
		choices.add(IMAGE_FILE);
		openImps.forEach( imp -> choices.add(imp.getTitle()));
		imageChoiceInput.setChoices(choices);
		if (sourceImp != null) {
			// Backwards compatibility: We used to have frontmost image selected in the choice list
			imageChoiceInput.setValue(this, sourceImp.getTitle());
			imageChoice = sourceImp.getTitle();
			adjustChannelInput();
		}
	}

	private void adjustChannelInput() {
		if (sourceImp != null && sourceImp.getTitle().equals(imageChoice)) {
			channel = Math.min(channel, sourceImp.getNChannels());
		}
	}

	private void loadSourceImp() {
		if (sourceImp == null) {
			uiService.showDialog("There are no images open.");
			return;
		}
		if (sourceImp.getOriginalFileInfo() != null) {
			final String dir = sourceImp.getOriginalFileInfo().directory;
			final String file = sourceImp.getOriginalFileInfo().fileName;
			imageFile = (dir == null || file == null) ? null : new File(dir, file);
		}
		adjustFileFields(false);
	}

	private ImagePlus getImpFromTitle(final String title) {
		try {
			for (final ImagePlus imp : openImps) {
				if (imp.getTitle().equals(title)) return imp;
			}
		} catch (final NullPointerException npe) {
			npe.printStackTrace();
		}
		return null;
	}

	@SuppressWarnings("unused")
	private void imageChoiceChanged() {
		switch (imageChoice) {
			case IMAGE_NONE:
				clearImageFileChoice();
				channel = 1;
				uiChoice = UI_SIMPLE;
				return;
			case IMAGE_FILE:
				if (null == imageFile) imageFile = currentImageFile;
				adjustFileFields(false);
				return;
			default: // imageChoice is an image tile of an open image
				sourceImp = getImpFromTitle(imageChoice);
				loadSourceImp();
				if (sourceImp != null) {
					currentImageFile = imageFile;
					imageFile = null;
					if (sourceImp.getNSlices() == 1) uiChoice = UI_SIMPLE;
					adjustChannelInput();
				}
				return;
		}
	}

	@SuppressWarnings("unused")
	private void channelChanged() {
		adjustChannelInput();
	}

	private void clearImageFileChoice() {
		currentImageFile = imageFile;
		final MutableModuleItem<File> imageFileInput = getInfo().getMutableInput(
			"imageFile", File.class);
		imageFileInput.setValue(this, null);
	}

	@SuppressWarnings("unused")
	private void imageFileChanged() {
		adjustFileFields(true);
	}

	private void adjustFileFields(final boolean adjustImageChoice) {
		if (adjustImageChoice) {
			if (imageFile == null || !imageFile.exists()) {
				imageChoice = IMAGE_NONE;
				return;
			} else {
				imageChoice = IMAGE_FILE;
			}
		}
		if (imageFile != null) {
			final File candidate = SNTUtils.findClosestPair(imageFile, new String[] { "traces", "swc" , "json", "ndf"});
			if (candidate != null && candidate.exists()) {
				tracesFile = candidate;
			}
		}
		adjustChannelInput();
	}

	@SuppressWarnings("unused")
	private void tracesFileChanged() {
		if (IMAGE_FILE.equals(imageChoice) || tracesFile == null || !tracesFile
			.exists()) return;
		final File candidate = SNTUtils.findClosestPair(tracesFile, "tif");
		if (candidate != null && candidate.exists()) {
			imageFile = candidate;
			adjustChannelInput();
		}
	}

	@Override
	public void run() {

		SNTUtils.setIsLoading(true);
		final boolean noImg = IMAGE_NONE.equals(imageChoice) || (IMAGE_FILE.equals(
			imageChoice) && imageFile == null);

		if (noImg) {
			final PathAndFillManager pathAndFillManager = new PathAndFillManager();
			if (tracesFile != null && tracesFile.exists()) {
				pathAndFillManager.setHeadless(true);
				if (!pathAndFillManager.load(tracesFile.getAbsolutePath()))
				{
					exit(String.format("%s is not a valid file", tracesFile
						.getAbsolutePath()));
				}
			}

			initPlugin(new SNT(getContext(), pathAndFillManager));
			return;
		}

		else if (IMAGE_FILE.equals(imageChoice)) {

			sourceImp = openImage();
			if (sourceImp == null) {
				exit("");
				return; // error messages have been displayed by openImage()
			} else if (!sourceImp.isVisible())
				sourceImp.show();

		}
		else if (sourceImp == null) { // frontmost image does not exist
			exit("An image is required but none was found.");
			return;
		}

		// Is spatial calibration set?
		if (!validateImageDimensions()) {
			exit("");
			return;
		}

		// If user loaded an existing image, it is possible it can be RGB
		if (ImagePlus.COLOR_RGB == sourceImp.getType()) {
			final boolean convert = new GuiUtils().getConfirmation(
				"RGB images are (intentionally) not supported. You can however convert " +
					sourceImp.getTitle() +
					" to a multichannel image. Would you like to do it now? (SNT will quit if you choose \"No\")",
				"Convert to Multichannel?");
			if (!convert) {
				exit("");
				return;
			}
			sourceImp.hide();
			sourceImp = CompositeConverter.makeComposite(sourceImp);
			sourceImp.show();
		}

		final SNT sntInstance = new SNT(getContext(), sourceImp);
		sntInstance.loadTracings(tracesFile);
		initPlugin(sntInstance);
	}

	private ImagePlus openImage() {
		// final Dataset ds = datasetIOService.open(imageFile.getAbsolutePath());
		// sourceImp = convertService.convert(ds, ImagePlus.class);
		// displayService.createDisplay(sourceImp.getTitle(), sourceImp);

		// FIXME: In some cases the code above seems to open images as virtual
		// stacks which causes all sort of problems later on. We'll fallback
		// to IJ1 until this issue is fixed
		final int nImagesBefore = ij.WindowManager.getImageCount();
		ImagePlus imp = IJ.openImage(imageFile.getAbsolutePath());
		if (imp != null)
			return imp;

		// HACK: if the image was opened by bio-formats, it was likely displayed
		// but HandleExtraFileTypes will return null. If a new image meanwhile
		// exists we will assume this has been the case.
		final int nImagesAfter = ij.WindowManager.getImageCount();
		if (nImagesAfter == nImagesBefore + 1) {
			imp = IJ.getImage(); // cannot be null
			final String title = imp.getTitle().toLowerCase();
			final String filename = imageFile.getName().toLowerCase();
			if (title.contains(filename)) return imp;
		} else if (nImagesAfter > nImagesBefore + 1) {
			exit("Import of " + imageFile.getName() + " resulted in several images open.\n"
					+ "Since it is not clear which one should be chosen, please make\n"
					+ "the relevant image frontmost and rerun SNT.");
			return null;
		}

		exit("Could not open image:\n" + imageFile.getAbsolutePath() + ".\n"
				+ "If the file corresponds to a proprietary image format, please open it\n"
				+ "using Plugins>Bio-Formats>Bio-Formats Importer..., then re-run SNT\n"
				+ "with " + imageFile.getName() + " as the frontmost image.");
		return null;
	}

	private void initPlugin(final SNT snt)
	{
		try {
			GuiUtils.setLookAndFeel(); // needs to be called here to set L&F of image's contextual menu
			final boolean singlePane = IMAGE_NONE.equals(imageChoice) || uiChoice.equals(UI_SIMPLE);
			final int frame = (sourceImp == null) ? 1 : sourceImp.getFrame();
			snt.initialize(singlePane, channel, frame);
			snt.startUI();
		}
		catch (final OutOfMemoryError error) {
			final StringBuilder sb = new StringBuilder(
				"Out of Memory: There is not enough RAM to load SNT under current options.\n");
			sb.append("Please allocate more memory to IJ or ");
			if (uiChoice.equals(UI_SIMPLE)) {
				sb.append("choose a smaller ").append((sourceImp == null) ? "file."
					: "image.");
			}
			else {
				sb.append("select the \"").append(UI_SIMPLE).append("\" interface.");
			}
			exit(sb.toString());
			GuiUtils.restoreLookAndFeel();
		} finally {
			exit("");
		}
	}

	// this exists only to address issue https://github.com/fiji/SNT/issues/25 and avoid the propagation
	// of swc files in pixel coordinates
	private boolean validateImageDimensions() {
		final int[] dims = sourceImp.getDimensions();
		if (dims[4] > 1 && dims[3] == 1 && new GuiUtils().getConfirmation(
			"It appears that image has " + dims[4] + " timepoints but only 1 Z-slice. " +
				"Swap Z,T Dimensions?", "Swap Z,T Dimensions?"))
		{
			sourceImp.setDimensions(dims[2], dims[4], dims[3]);
		}
		final Calibration cal = sourceImp.getCalibration();
		if (!cal.scaled() || (sourceImp.getZ() > 1 && (cal.pixelDepth < cal.pixelHeight ||
			cal.pixelDepth < cal.pixelWidth)))
		{
			return new GuiUtils().getConfirmation("Spatial calibration of " +
				sourceImp.getTitle() +
				" appears to be unset or inaccurate. Continue nevertheless?",
				"Inaccurate Spatial Calibration?");
		}
		return true;
	}

	private void exit(final String msg) {
		SNTUtils.setIsLoading(false);
		if (msg != null && !msg.isEmpty())
			cancel(msg);
	}

	/*
	 * IDE debug method
	 */
	public static void main(final String[] args) {
		SNTUtils.startApp();
	}

}
