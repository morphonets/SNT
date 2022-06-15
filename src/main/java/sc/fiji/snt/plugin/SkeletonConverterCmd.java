/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;

import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SkeletonConverter;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.ChooseDatasetCmd;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

import java.io.File;
import java.util.*;

/**
 * Command providing a GUI for {@link SkeletonConverter}, SNT's autotracing
 * class.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Automated Tracing: Tree(s) from Segmented Image...", initializer = "init")
public class SkeletonConverterCmd extends CommonDynamicCmd {

	private static final String IMG_NONE= "None";
	private static final String IMG_UNAVAILABLE_CHOICE = "No other image open";
	private static final String IMG_TRACED_CHOICE = "Image being traced";
	private static final String IMG_TRACED_DUP_CHOICE = "Image being traced (duplicate)";
	private static final String IMG_TRACED_SEC_LAYER_CHOICE = "Secondary image layer";

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = "<HTML>This command attempts to automatically reconstruct a pre-processed<br>" //
			+ "image in which background pixels have been zeroed. Result can be<br>"
			+ "curated using edit commands in Path Manager and image context menu.";

	@Parameter(label = "<HTML>&nbsp;<br><b> I. Input Image(s):", required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER1;

	@Parameter(label = "Segmented Image", required = false, description = "<HTML>Image from which paths will be extracted. Will be skeletonized by the algorithm.<br>"
			+ "If thresholded, only highlighted pixels are considered, otherwise all non-zero<br<intensities will be taken into account", style = ChoiceWidget.LIST_BOX_STYLE)
	private String maskImgChoice;

	@Parameter(label = "Path to segmented image", required = false, description = "<HTML>Path to filtered image from which paths will be extracted.<br>"//
			+ "Will be skeletonized by the algorithm.<br>If thresholded, only highlighted pixels are considered, otherwise all non-zero<br>"
			+ "intensities will be taken into account", style = FileWidget.OPEN_STYLE)
	private File maskImgFileChoice;

//	@Parameter(label = "Skeletonize", required = false, description = "<HTML>Whether segmented image should be skeletonized.<br>"
//			+ "With 2D images isolated pixels are automatically filtered out from the skeleton.<br>"
//			+ "Unnecessary if segmented image is already a topological sekeleton")
//	private boolean skeletonizeMaskImage;

	@Parameter(label = "Original Image", required = false, description = "<HTML>Optional. Original (un-processed) image used to resolve<br>"//
			+ "loops in segmented image using brightness criteria. If<br>"
			+ "unavailable, length-based criteria are used instead", style = ChoiceWidget.LIST_BOX_STYLE)
	private String originalImgChoice;

	@Parameter(label = "Path to original image", required = false, description = "<HTML>Optional. Path to original (un-processed) image used to resolve<br>"//
			+ "loops in segmented image using brightness criteria. If<br>"
			+ "unavailable, length-based criteria are used instead", style = FileWidget.OPEN_STYLE)
	private File originalImgFileChoice;

	@Parameter(label = "<HTML>&nbsp;<br><b> II. Root (Reconstruction Origin):", required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER2;

	@Parameter(label = "Set root from ROI", description = "<HTML>Assumes that an active area ROI exists highlighting the root of the structure.<br>"
			+ "If no ROI exists, an arbitrary root node will be used")
	private boolean inferRootFromRoi;

	@Parameter(label = "Restrict to active plane (3D only)", description = "<HTML>Assumes that the root highlighted by the ROI occurs at the<br>"
			+ "ROI's Z-plane. Ensures other possible roots above or below<br>the ROI are not considered. Ignored if image is 2D")
	private boolean roiPlane;

	@Parameter(label = "<HTML>&nbsp;<br><b> III. Gaps &amp; Disconnected Components:", required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER3;

	@Parameter(label = "Discard small components", description = "<HTML>Whether to ignore disconnected components below sub-threshold length")
	private boolean pruneByLength;

	@Parameter(label = "Length threshold", description = "<HTML>Disconnected structures below this cable length will be discarded.<br>"
			+ "Increase this value if the algorith produces too many isolated branches.<br>Decrease it to enrich for larger, contiguos structures.<br><br>"
			+ "This value is only used if \"Discard small components\" is enabled.")
	private double lengthThreshold;

	@Parameter(label = "Connect adjacent components", description = "<HTML>If the segmented image is fragmented into multiple components:<br>"
			+ "Should the algorithm attempt to connect nearby components?")
	private boolean connectComponents;

	@Parameter(label = "Max. connection distance", min = "0.0", description = "<HTML>The maximum allowable distance between disconnected "
			+ "components to be merged.<br>"
			+ "Increase this value if the algorith produces too many gaps.<br>Decrease it to minimize spurious connections.<br><br>"
			+ "This value is only used if \"Connect adjacent components\" is enabled. Merges<br>"
			+ "occur only between end-points and only when the operation does not introduce loops")
	private double maxConnectDist;

	@Parameter(label = "<HTML>&nbsp;<br><b> IV. Options:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER4;

	@Parameter(label = "Replace existing paths", description = "<HTML>Whether any existing paths should be discarded "
			+ "before conversion")
	private boolean clearExisting;

	@Parameter(label = "Activate 'Edit Mode'", description = "<HTML>Whether SNT's 'Edit Mode' should be activated after command finishes")
	private boolean editMode;

	@Parameter(label = "Debug mode", persist = false, callback = "debuModeCallback", description = "<HTML>Enable SNT's debug mode for verbose Console logs?")
	private boolean debugMode;

	@Parameter(required = false, persist = false)
	private boolean useFileChoosers;
	@Parameter(required = false, persist = false)
	private boolean simplifyPrompt;

	private HashMap<String, ImagePlus> impMap;
	private ImagePlus chosenMaskImp;
	private boolean abortRun;
	private boolean ensureMaskImgVisibleOnAbort;

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);

		if (simplifyPrompt) { // adopt sensible defaults

			resolveInput("HEADER1");
			resolveInput("maskImgFileChoice");
			resolveInput("originalImgFileChoice");
			resolveInput("maskImgChoice");
			resolveInput("originalImgChoice");
			resolveInput("useFileChoosers");
			resolveInput("roiPlane");
			resolveInput("pruneByLength");
			resolveInput("lengthThreshold");
			useFileChoosers = false;
			maskImgChoice = IMG_TRACED_DUP_CHOICE;
			connectComponents = true;
			maxConnectDist = 5d; // hopefully 5 microns
			pruneByLength = false;
			editMode = true;

		} else if (useFileChoosers) { // disable choice widgets. Use file choosers

			resolveInput("simplifyPrompt");
			resolveInput("maskImgChoice");
			resolveInput("originalImgChoice");

		} else { // disable file choosers. Use choice widgets

			resolveInput("simplifyPrompt");
			resolveInput("maskImgFileChoice");
			resolveInput("originalImgFileChoice");

			// Populate choices with list of open images
			final Collection<ImagePlus> impCollection = ChooseDatasetCmd.getImpInstances();
			if (impCollection.isEmpty()) {
				noImgError();
				return;
			}
			final MutableModuleItem<String> maskImgChoiceItem = getInfo().getMutableInput("maskImgChoice",
					String.class);
			final MutableModuleItem<String> originalImgChoiceItem = getInfo().getMutableInput("originalImgChoice",
					String.class);

			final List<String> maskChoices = new ArrayList<>();
			final List<String> originalChoices = new ArrayList<>();
			impMap = new HashMap<>();
			if (impCollection != null && !impCollection.isEmpty()) {
				final ImagePlus existingImp = snt.getImagePlus();
				for (final ImagePlus imp : impCollection) {
					if (imp.equals(snt.getImagePlus()) || !isHyperstack(imp))
						continue;
					impMap.put(imp.getTitle(), imp);
					maskChoices.add(imp.getTitle());
					originalChoices.add(imp.getTitle());
				}
				Collections.sort(maskChoices);
				Collections.sort(originalChoices);
			}

			if (snt.accessToValidImageData()) {
				maskChoices.add(0, IMG_TRACED_DUP_CHOICE);
				maskChoices.add(0, IMG_TRACED_SEC_LAYER_CHOICE);
				originalChoices.add(0, "Image being traced");
				if (!isBinary(snt.getImagePlus())) {
					// the active image is grayscale: assume it is the original
					originalImgChoice = IMG_TRACED_CHOICE;
				}
			}
			if (maskChoices.isEmpty())
				maskChoices.add(IMG_UNAVAILABLE_CHOICE);
			originalChoices.add(0, IMG_NONE);
			maskImgChoiceItem.setChoices(maskChoices);
			originalImgChoiceItem.setChoices(originalChoices);
		}

		debugMode = SNTUtils.isDebugMode();
	}

	@SuppressWarnings("unused")
	private void debuModeCallback() {
		SNTUtils.setDebugMode(debugMode);
	}

	private boolean isHyperstack(final ImagePlus imp) {
		return imp.getNChannels() > 1 || imp.getNFrames() > 1;
	}

	private boolean isBinary(final ImagePlus imp) {
		return imp.getProcessor().isBinary();
	}

	@Override
	public void cancel() {
		this.cancel("");
	}

	@Override
	public void cancel(final String reason) {
		super.cancel(reason);
		snt.setCanvasLabelAllPanes(null);
		abortRun = true;
	}

	@Override
	public void run() {
		if (abortRun || isCanceled()) {
			return;
		}

		ImagePlus chosenOrigImp = null;
		boolean isValidOrigImg = true;

		try {

			if (useFileChoosers) {

				if (!SNTUtils.fileAvailable(maskImgFileChoice)) {
					error("File path of segmented image is invalid.");
					return;
				}
				SNTUtils.log("Loading " + maskImgFileChoice.getAbsolutePath());
				chosenMaskImp = IJ.openImage(maskImgFileChoice.getAbsolutePath());
				ensureMaskImgVisibleOnAbort = true;
				if (SNTUtils.fileAvailable(originalImgFileChoice)) {
					SNTUtils.log("Loading " + originalImgFileChoice.getAbsolutePath());
					chosenOrigImp = IJ.openImage(originalImgFileChoice.getAbsolutePath());
				} else {
					isValidOrigImg = originalImgFileChoice == null || originalImgFileChoice.toString().isEmpty();
				}

			} else {

				if (IMG_TRACED_DUP_CHOICE.equals(maskImgChoice)) {
					/*
					 * Make deep copy of imp returned by getLoadedDataAsImp() since it holds
					 * references to the same pixel arrays as used by the source data
					 */
					SNTUtils.log("Duplicating loaded data");
					chosenMaskImp = snt.getLoadedDataAsImp().duplicate();
					if (snt.getImagePlus() != null)
						chosenMaskImp.setRoi(snt.getImagePlus().getRoi());
					ensureMaskImgVisibleOnAbort = true;

				} else if (IMG_TRACED_SEC_LAYER_CHOICE.equals(maskImgChoice)) {
					chosenMaskImp = snt.getSecondaryDataAsImp();
					if (chosenMaskImp != null && snt.getImagePlus() != null)
						chosenMaskImp.setRoi(snt.getImagePlus().getRoi());
				} else {
					chosenMaskImp = impMap.get(maskImgChoice);
				}
				if (IMG_TRACED_CHOICE.equals(originalImgChoice)) {
					chosenOrigImp = snt.getLoadedDataAsImp();
				} else if (impMap != null) { // e.g. when 
					chosenOrigImp = impMap.get(originalImgChoice);
				}
			}

			// Abort if images remain ill-defined at this point
			if (chosenMaskImp == null) {
				if (IMG_TRACED_SEC_LAYER_CHOICE.equals(maskImgChoice)) {
					final String msg1 = "No secondary layer image exists. Please load one or create it using the "
							+ "<i>Built-in Filters</i> wizard in the <i>Auto-tracing</i> widget.";
					final String msg2 = " retry automated tracing using <i>Utilities > Extract Paths From Segmented Image...";
					if (snt.getStats().max == 0) {
						final String msg3 = "<br><br>NB: Statistics for the main image have not been computed yet. You will "
								+ "need to trace a small path over a relevant feature to compute them. This will allow "
								+ "SNT to better understand the dynamic range of the image.";
						error(msg1 + msg3 + "<br><br>You can always" + msg2);
					} else if (new GuiUtils().getConfirmation(
							msg1 + "<br>Start wizard now?<br><br> Once created, you can" + msg2, "Start Wizard?",
							"Start Wizard", "No. Not Now")) {
						snt.getUI().runSecondaryLayerWizard();
						return;
					}
				} else {
					noImgError();
				}
				return;
			}
			if (isHyperstack(chosenMaskImp)) {
				error("The segmented/skeletonized image is not a single channel 2D/3D image " + "Please simplify "
						+ chosenMaskImp.getTitle()
						+ " and rerun using <i>Utilities > Extract Paths from Segmented Image...");
				return;
			}

			// Extra user-friendliness: Retrieve ROI. If not found, look for it on second
			// image
			Roi roi = chosenMaskImp.getRoi();
			if (roi == null && chosenOrigImp != null)
				roi = chosenOrigImp.getRoi();

			// Extra user-friendliness: Aggregate unexpected settings in a single list
			final boolean isBinary = chosenMaskImp.getProcessor().isBinary();
			final boolean isCompatible = chosenOrigImp == null
					|| chosenMaskImp.getCalibration().equals(chosenOrigImp.getCalibration());
			final boolean isSameDim = chosenOrigImp == null || (chosenMaskImp.getWidth() == chosenOrigImp.getWidth()
					&& chosenMaskImp.getHeight() == chosenOrigImp.getHeight()
					&& chosenMaskImp.getNSlices() == chosenOrigImp.getNSlices());
			final boolean isValidRoi = roi != null && roi.isArea();
			final boolean isValidConnectDist = maxConnectDist > 0d;
			if (!isValidOrigImg || !isBinary || !isSameDim || !isCompatible || (!isValidRoi && inferRootFromRoi)
					|| (!isValidConnectDist && connectComponents)) {
				final int width = GuiUtils
						.renderedWidth("      Warning: Images do not share the same spatial calibration<");
				final StringBuilder sb = new StringBuilder("<HTML><div WIDTH=").append(Math.max(550, width))
						.append("><p>The following issue(s) were detected:</p><ul>");
				if (!isValidOrigImg) {
					sb.append("<li>Warning: Original image is not valid and will be ignored</li>");
				}
				if (!isSameDim) {
					sb.append("<li>Warning: Images do not share the same dimensions. Algorithm will likely fail</li>");
					ensureMaskImgVisibleOnAbort = true;
				}
				if (!isBinary) {
					sb.append(
							"<li>Info: Image is not thresholded: Non-zero intensities will be used as foreground</li>");
					ensureMaskImgVisibleOnAbort = true;
				}
				if (!isCompatible) {
					sb.append("<li>Warning: Images do not share the same spatial calibration</li>.");
					ensureMaskImgVisibleOnAbort = true;
				}
				if (!isValidRoi && inferRootFromRoi) {
					sb.append(
							"<li>Warning: Image does not contain an active area ROI. Root detection will be disabled</li>");
				}
				if (!isValidConnectDist && connectComponents) {
					sb.append(
							"<li>Warning: Max. connection distance must be > 0. Connection of components will be disabled</li>");
				}
				sb.append("</ul>");
				sb.append("<p>Would you like to proceed? If you abort ");
				if (ensureMaskImgVisibleOnAbort) {
					sb.append(" segmented image will be displayed so that you can edit it accordingly. You can then");
				} else {
					sb.append(" you can");
				}
				if (ensureMaskImgVisibleOnAbort) {
					sb.append(" rerun using <i>Utilities > Extract Paths From Segmented Image...</i>");
				}
				sb.append("</p>");
				if (!new GuiUtils().getConfirmation(sb.toString(), "Proceed Despite Warnings?",
						"Proceed. I'm Feeling Lucky", "Abort")) {
					if (ensureMaskImgVisibleOnAbort && !useFileChoosers)
						chosenMaskImp.show();
					resetUI(false, SNTUI.SNT_PAUSED); // waive img to IJ for easier drawing of ROIS, etc.
					cancel();
					return;
				}
				// User is sure to continue: skeletonize grayscale image
				connectComponents = connectComponents && isValidConnectDist;
				inferRootFromRoi = inferRootFromRoi && isValidRoi;
			}

			SNTUtils.log("Segmented image: " + chosenMaskImp.getTitle());
			SNTUtils.log("Segmented image thresholded/binarized: "
					+ (isBinary(chosenMaskImp) || chosenMaskImp.isThreshold()));
			SNTUtils.log("Original image: " + ((chosenOrigImp == null) ? null : chosenOrigImp.getTitle()));

			// We'll skeletonize all images again, just to ensure we are indeed dealing with
			// skeletons
			snt.setCanvasLabelAllPanes("Skeletonizing..");
			SkeletonConverter.skeletonize(chosenMaskImp, chosenMaskImp.getNSlices() == 1);

			// Now we can finally run the conversion!
			snt.setCanvasLabelAllPanes("Autotracer running...");
			status("Creating Trees from Skeleton...", false);
			final SkeletonConverter converter = new SkeletonConverter(chosenMaskImp, false);
			SNTUtils.log("Converting....");
			converter.setPruneByLength(pruneByLength);
			SNTUtils.log("Prune by length: " + pruneByLength);
			converter.setLengthThreshold(lengthThreshold);
			SNTUtils.log("Length threshold: " + lengthThreshold);
			converter.setConnectComponents(connectComponents);
			SNTUtils.log("Connect components: " + connectComponents);
			converter.setMaxConnectDist(maxConnectDist);
			SNTUtils.log("MaxC onnecting Dist.: " + maxConnectDist);
			if (chosenOrigImp == null) {
				converter.setPruneMode(SkeletonConverter.SHORTEST_BRANCH);
				SNTUtils.log("Pruning mode: Shortest branch");
			} else {
				converter.setOrigIP(chosenOrigImp);
				converter.setPruneMode(SkeletonConverter.LOWEST_INTENSITY_VOXEL);
				SNTUtils.log("Pruning mode: Dimmest voxel");
			}

			final List<Tree> trees = (isValidRoi) ? converter.getTrees(roi, roiPlane) : converter.getTrees();
			SNTUtils.log("... Done. " + trees.size() + " tree(s) retrieved.");
			if (trees.isEmpty()) {
				error("No paths could be extracted. Chosen parameters were not suitable!?");
				return;
			}
			Tree.assignUniqueColors(trees);
			final PathAndFillManager pafm = sntService.getPathAndFillManager();
			if (clearExisting) {
				pafm.clear();
			}
			trees.forEach(tree -> pafm.addTree(tree, "Autotraced"));

			// Extra user-friendliness: If no display canvas exist, no image is being
			// traced, or we are importing from a file path, adopt the chosen image as
			// tracing canvas
			if (snt.getImagePlus() == null || useFileChoosers) {
				// Suppress the 'auto-tracing' prompt for this image. This
				// will be reset once SNT initializes with the new data
				snt.getPrefs().setTemp("autotracing-prompt-armed", false);
				snt.initialize(chosenMaskImp);
			}

			if (editMode && ui != null && pafm.size() > 0) {
				if (!trees.get(0).isEmpty())
					ui.getPathManager().setSelectedPaths(Collections.singleton(trees.get(0).get(0)), this);
				ui.setVisibilityFilter("all", false);
				resetUI(false, SNTUI.EDITING);
			} else {
				resetUI(false,  SNTUI.READY);
			}
			status("Successfully created " + trees.size() + " Tree(s)...", true);

		} catch (final Exception | Error ex) {
			ex.printStackTrace();
			error("An exception occured. See Console for details.");
		}
	}

	@Override
	protected void error(final String msg) {
		super.error(msg);
		abortRun = true; // should not be needed but isCanceled() is not working as expected!?
		resolveAllInputs();
		if (ensureMaskImgVisibleOnAbort && chosenMaskImp != null) {
			chosenMaskImp.show();
		}
	}

	private void noImgError() {
		error("To run this command you must first open a pre-processed image from which paths can be extracted (i.e., "
				+ "in which background pixels have been removed). E.g.:"
				+ "<ul><li>A segmented (thresholded) image (8-bit)</li>"
				+ "<li>A filtered image, as created by <i>Built-in Filters</i> in the <i>Auto-tracing</i> widget</li>"
				+ "</ul>" + "<p>" + "<p>Related Scripts:</p>" + "<ul>" + "<li>Batch &rarr Filter Multiple Images</li>"
				+ "<li>Skeletons and ROIs &rarr Reconstruction From Segmented Image</li>" + "</ul>" + "<p>To Rerun:</p>"
				+ "<ul>" + "<li>Utilities &rarr Extract Paths from Seg. Image... (opened images)</li>"
				+ "<li>File &rarr AutoTrace Segmented Image... (unopened files)</li>");
	}

	private void resolveAllInputs() { // ensures prompt is not displayed on error
		resolveInput("msg1");
		resolveInput("HEADER1");
		resolveInput("maskImgChoice");
		resolveInput("maskImgFileChoice");
		resolveInput("originalImgChoice");
		resolveInput("originalImgFileChoice");
		resolveInput("HEADER2");
		resolveInput("inferRootFromRoi");
		resolveInput("roiPlane");
		resolveInput("HEADER3");
		resolveInput("connectComponents");
		resolveInput("maxConnectDist");
		resolveInput("pruneByLength");
		resolveInput("lengthThreshold");
		resolveInput("HEADER4");
		resolveInput("clearExisting");
		resolveInput("editMode");
		resolveInput("useFileChoosers");
		resolveInput("simplifyPrompt");
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.get(SNTService.class).initialize(true);
		final Map<String, Object> input = new HashMap<>();
		input.put("useFileChoosers", false);
		ij.command().run(SkeletonConverterCmd.class, true, input);
	}
}
