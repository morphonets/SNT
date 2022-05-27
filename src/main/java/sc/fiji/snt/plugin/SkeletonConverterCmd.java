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
@Plugin(type = Command.class, visible = false, label = "Automated Tracing: Tree(s) from Skeleton Image...", initializer = "init")
public class SkeletonConverterCmd extends CommonDynamicCmd {

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = "<HTML>This command attempts to automatically reconstruct a pre-processed<br>" //
			+ "image in which background pixels have been masked to zero.<br>"
			+ "Result can be curated using editing commands in Path Manager and<br>" + "canvas contextual menu.";

	@Parameter(label = "<HTML>&nbsp;<br><b> I. Input Image(s):", required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER1;

	@Parameter(label = "Segmented Image", required = false, description = "<HTML>Image from which paths will be extracted.<br>"//
			+ "Assumed to be binary", style = ChoiceWidget.LIST_BOX_STYLE)
	private String maskImgChoice;

	@Parameter(label = "Path to segmented image", required = false, description = "<HTML>Path to 8-bit image from which paths will be extracted.<br>"//
			+ "Assumed to be binary", style = FileWidget.OPEN_STYLE)
	private File maskImgFileChoice;

	@Parameter(label = "Skeletonize", required = false, description = "<HTML>whether segmented image should be skeletonized.<br>"
			+ "With 2D images isolated pixels are automatically filtered out from the skeleton.<br>"
			+ "Unnecessary if segmented image is already a topological sekeleton")
	private boolean skeletonizeMaskImage;

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

	@Parameter(label = "Connect adjacent components", description = "<HTML>If the skeletonized image is fragmented into multiple components:<br>"
			+ "Should individual components be connected?")
	private boolean connectComponents;

	@Parameter(label = "Max. connection distance", min = "0.0", description = "<HTML>The maximum allowable distance between the "
			+ "closest pair of points for two components to be merged.<br>"
			+ "This value is only used if \"Connect adjacent components\" is enabled")
	private double maxConnectDist;

	@Parameter(label = "Discard small components", description = "<HTML>Whether to ignore disconnected components below sub-threshold length")
	private boolean pruneByLength;

	@Parameter(label = "Length threshold", description = "<HTML>Disconnected structures below this length will be discarded.<br>"
			+ "This value is only used if \"Discard small components\" is enabled.")
	private double lengthThreshold;

	@Parameter(label = "<HTML>&nbsp;<br><b> IV. Options:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER4;

	@Parameter(label = "Replace existing paths", description = "<HTML>Whether any existing paths should be cleared "
			+ "before conversion")
	private boolean clearExisting;

	@Parameter(label = "Activate 'Edit Mode'", description = "<HTML>Whether SNT's 'Edit Mode' should be activated after command finishes")
	private boolean editMode;

	@Parameter(required = false, persist = false)
	private boolean useFileChoosers;

	private HashMap<String, ImagePlus> impMaskMap;
	private HashMap<String, ImagePlus> impOrigMap;

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		if (useFileChoosers) { // disable choice widgets. Use file choosers

			resolveInput("maskImgChoice");
			resolveInput("originalImgChoice");

		} else { // disable file choosers. Use choice widgets

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
			impMaskMap = new HashMap<>();
			impOrigMap = new HashMap<>();
			if (impCollection != null && !impCollection.isEmpty()) {
				final ImagePlus existingImp = snt.getImagePlus();
				for (final ImagePlus imp : impCollection) {
					if (imp.equals(snt.getImagePlus()))
						continue;
					if (imp.getProcessor().isBinary()) {
						impMaskMap.put(imp.getTitle(), imp);
						maskChoices.add(imp.getTitle());
					} else {
						impOrigMap.put(imp.getTitle(), imp);
						originalChoices.add(imp.getTitle());
					}
				}
				Collections.sort(maskChoices);
				Collections.sort(originalChoices);
			}

			if (snt.accessToValidImageData()) {
				if (snt.getImagePlus().getProcessor().isBinary()) {
					if (snt.getImagePlus().getStackSize() > 1) {
						// FIXME: AnalyzeSkeleton_ does not work with wrapped Imgs in 3D??
						maskChoices.add(0, "Copy of image being traced");
					} else {
						maskChoices.add(0, "Image being traced");
						maskChoices.add(1, "Copy of image being traced");
					}

				} else {
					originalChoices.add(0, "Image being traced");
				}
			}
			if (maskChoices.isEmpty())
				maskChoices.add("No other image open");
			if (originalChoices.isEmpty())
				originalChoices.add("No other image open");
			maskImgChoiceItem.setChoices(maskChoices);
			originalImgChoiceItem.setChoices(originalChoices);
		}

	}

	@Override
	public void run() {

		ImagePlus chosenMaskImp = null;
		ImagePlus chosenOrigImp = null;
		boolean isValidOrigImg = true;
		boolean ensureChosenImpIsVisible = false;

		try {

			if (useFileChoosers) {
				if (!SNTUtils.fileAvailable(maskImgFileChoice)) {
					error("File path of segmented image is invalid.");
					return;
				}
				chosenMaskImp = IJ.openImage(maskImgFileChoice.getAbsolutePath());
				ensureChosenImpIsVisible = true;
				if (SNTUtils.fileAvailable(originalImgFileChoice)) {
					chosenOrigImp = IJ.openImage(originalImgFileChoice.getAbsolutePath());
				} else {
					isValidOrigImg = false;
				}
			} else {

				if ("Copy of image being traced".equals(maskImgChoice)) {
					/*
					 * Make deep copy of imp returned by getLoadedDataAsImp() since it holds
					 * references to the same pixel arrays as used by the source data
					 */
					chosenMaskImp = snt.getLoadedDataAsImp().duplicate();
					if (snt.getImagePlus() != null)
						chosenMaskImp.setRoi(snt.getImagePlus().getRoi());
					ensureChosenImpIsVisible = chosenMaskImp.getBitDepth() > 8 || skeletonizeMaskImage
							|| snt.getImagePlus().getNChannels() > 1 || snt.getImagePlus().getNFrames() > 1;
				} else if ("Image being traced".equals(maskImgChoice)) {
					chosenMaskImp = snt.getLoadedDataAsImp();
					if (snt.getImagePlus() != null)
						chosenMaskImp.setRoi(snt.getImagePlus().getRoi());
				} else {
					chosenMaskImp = impMaskMap.get(maskImgChoice);
				}

				if ("Image being traced".equals(originalImgChoice)) {
					chosenOrigImp = snt.getLoadedDataAsImp();
				} else {
					chosenOrigImp = impOrigMap.get(originalImgChoice);
				}
			}

			// Extra user-friendliness: Abort if images remain ill-defined at this point
			if (chosenMaskImp == null) {
				noImgError();
				return;
			} else if (chosenMaskImp.getBitDepth() != 8) {
				String msg = "The segmented/skeletonized image must be 8-bit.";
				if (ensureChosenImpIsVisible) {
					msg += " Please simplify " + chosenMaskImp.getTitle();
					msg += " and re-run using <i>Utilities > Extract Paths from Segmented Image...";
					chosenMaskImp.show();
				}
				error(msg);
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
			final boolean isValidRoi = roi != null && roi.isArea();
			final boolean isValidConnectDist = maxConnectDist > 0d;
			if (!isValidOrigImg || !isBinary || !isCompatible || (!isValidRoi && inferRootFromRoi)
					|| (!isValidConnectDist && connectComponents)) {
				final StringBuilder sb = new StringBuilder("<HTML><p>The following issue(s) were detected:</p><ul>");
				if (!isValidOrigImg) {
					sb.append("<li>Original image is not valid/li>");
				}
				if (!isBinary) {
					sb.append("<li>Image is not binary: ");
					if (skeletonizeMaskImage) {
						sb.append("Skeletonization will consider foreground to be any non-zero value</li>");
					} else {
						sb.append("Image does not seem segmented, and thus, not a skeleton</li>");
					}
				}
				if (!isCompatible) {
					sb.append("<li>Images do not share the same spatial calibration</li>");
				}
				if (!isValidRoi && inferRootFromRoi) {
					sb.append("<li>Image does not contain a valid area ROI</li>");
				}
				if (!isValidConnectDist && connectComponents) {
					sb.append("<li>Max. connection distance must be > 0</li>");
				}
				sb.append("</ul>");
				sb.append(
						"<p>It is recommended that you address the issue(s) above and<br> re-run using <i>Utilities > Extract Paths from Segmented Image...</p>");
				if (!new GuiUtils().getConfirmation(sb.toString(), "Proceed Despite Warnings?",
						"Proceed. I'm Feeling Lucky", "Abort")) {
					if (ensureChosenImpIsVisible)
						chosenMaskImp.show();
					resetUI(false, SNTUI.SNT_PAUSED); // waive img to IJ for easier drawing of ROIS, etc.
					super.cancel();
					return;
				}
				// User is sure to continue: skeletonize grayscale image
				connectComponents = connectComponents && isValidConnectDist;
				inferRootFromRoi = inferRootFromRoi && isValidRoi;
				if (skeletonizeMaskImage && !isBinary) {
					SkeletonConverter.skeletonize(chosenMaskImp, chosenMaskImp.getNSlices() == 1);
				}
			}

			// Now we can finally run the conversion!
			status("Creating Trees from Skeleton...", false);
			final SkeletonConverter converter = new SkeletonConverter(chosenMaskImp, skeletonizeMaskImage && isBinary);
			converter.setPruneByLength(pruneByLength);
			converter.setLengthThreshold(lengthThreshold);
			converter.setConnectComponents(connectComponents);
			converter.setMaxConnectDist(maxConnectDist);
			if (chosenOrigImp == null) {
				converter.setPruneMode(SkeletonConverter.SHORTEST_BRANCH);
			} else {
				converter.setOrigIP(chosenOrigImp);
				converter.setPruneMode(SkeletonConverter.LOWEST_INTENSITY_VOXEL);
			}

			final List<Tree> trees = (isValidRoi) ? converter.getTrees(roi, roiPlane) : converter.getTrees();
			final PathAndFillManager pafm = sntService.getPathAndFillManager();
			if (clearExisting) {
				pafm.clear();
			}
			for (final Tree tree : trees) {
				pafm.addTree(tree);
			}

			// Extra user-friendliness: If no display canvas exist, or no image is being
			// traced, adopt the chosen image as tracing canvas
			if (ensureChosenImpIsVisible)
				chosenMaskImp.show();
			if (snt.getImagePlus() == null)
				snt.initialize(chosenMaskImp);

			resetUI(false, (editMode) ? SNTUI.EDITING : SNTUI.READY);
			status("Successfully created " + trees.size() + " Tree(s)...", true);

		} catch (final Exception | Error ex) {
			ex.printStackTrace();
			error("An exception occured. See Console for details.");
		}
	}

	private void noImgError() {
		error("To run this command you must first open a segmented/skeletonized 8-bit image from which paths can be extracted. "
				+ "Alternatively, you can use 'Scripts> Skeletons and ROIs> Reconstruction From Segmented Image' for batch processing.");
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.get(SNTService.class).initialize(true);
		final Map<String, Object> input = new HashMap<>();
		input.put("useFileChoosers", true);
		ij.command().run(SkeletonConverterCmd.class, true, input);
	}
}
