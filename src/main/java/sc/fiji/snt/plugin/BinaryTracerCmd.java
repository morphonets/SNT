/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
import ij.gui.Roi;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.*;
import sc.fiji.snt.tracing.auto.BinaryTracer;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.ChooseDatasetCmd;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.TreeUtils;

import java.io.File;
import java.util.*;

/**
 * Command providing a GUI for {@link BinaryTracer}, SNT's autotracing
 * class.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Automated Tracing: Tree(s) from Segmented Image...", initializer = "init")
public class BinaryTracerCmd extends CommonDynamicCmd {

    public static final String ROI_UNSET = "None. Ignore any ROIs";
    public static final String ROI_CONTAINED = "ROI marks a single root";
    public static final String ROI_EDGE = "Path(s) branch out from ROI's edge";
    public static final String ROI_CENTROID = "Path(s) branch out from ROI's simple centroid";
    public static final String ROI_CENTROID_WEIGHTED = "Path(s) branch out from ROI's weighted centroid";
    private static final String IMG_NONE = "None";
    private static final String IMG_UNAVAILABLE_CHOICE = "No other image open";
    private static final String IMG_TRACED_CHOICE = "Image being traced";
    private static final String IMG_TRACED_DUP_CHOICE = "Image being traced (duplicate)";
    private static final String IMG_TRACED_SEC_LAYER_CHOICE = "Secondary image layer";
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private final String msg1 = "<HTML>This command attempts to automatically reconstruct a pre-processed<br>" //
            + "image in which background pixels have been zeroed. Result can be<br>"
            + "curated using edit commands in Path Manager and image context menu.";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER1 = "<HTML>&nbsp;<br><b> I. Input Image(s)";

    @Parameter(label = "Segmented Image", required = false, description = "<HTML>Image from which paths will be extracted. Will be skeletonized by the algorithm.<br>"
            + "If thresholded, only highlighted pixels are considered, otherwise all non-zero<br>intensities will be taken into account", style = ChoiceWidget.LIST_BOX_STYLE)
    private String maskImgChoice;

    @Parameter(label = "Path to segmented image", required = false, description = "<HTML>Path to filtered image from which paths will be extracted.<br>"//
            + "Will be skeletonized by the algorithm.<br>If thresholded, only highlighted pixels are considered, otherwise all non-zero<br>"
            + "intensities will be taken into account", style = FileWidget.OPEN_STYLE)
    private File maskImgFileChoice;

    @Parameter(label = "Intensity image", required = false,
            description = "<HTML>Optional. Original (un-processed) image used to resolve loops<br>"//
                    + "in the segmented image using brightness criteria.", style = ChoiceWidget.LIST_BOX_STYLE)
    private String originalImgChoice;

    @Parameter(label = "Path to intensity image", required = false, description = "<HTML>Optional. Path to original (un-processed) image used to resolve<br>"//
            + "loops in the segmented image using brightness criteria.<br>"
            + "If available: loops will be nicked at either the dimmest voxel or the dimmest branch in the loop<br>"
            + "If unavailable: Loops will be nicked using topological criteria", style = FileWidget.OPEN_STYLE)
    private File originalImgFileChoice;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER2 = "<HTML>&nbsp;<br><b> II. Soma/Root Detection from ROI";

    @Parameter(label = "ROI strategy", choices = {ROI_UNSET, ROI_EDGE, ROI_CENTROID, ROI_CENTROID_WEIGHTED, ROI_CONTAINED}, //
            description = "<HTML>Assumes that an active area ROI marks the root(s) of the structure.<br><dl>" //
                    + "<dt><i>" + ROI_UNSET + "</i></dt>" //
                    + "<dd>An <b>arbitrary root node</b> is used</dd>" //
                    + "<dt><i>" + ROI_EDGE + "</i></dt>" //
                    + "<dd>Paths branch out around the ROI's contour. Most accurate strategy for <b>complex topologies</b></dd>" //
                    + "<dt><i>" + ROI_CENTROID + "</i></dt>" //
                    + "<dd>Paths branch out from the centroid of ROI's contour. Suitable for simpler cells w/ <b>accurate soma contours</b></dd>" //
                    + "<dt><i>" + ROI_CENTROID_WEIGHTED + "</i></dt>" //
                    + "<dd>Paths branch out from the centroid of root(s) contained by ROI. Suitable for simpler cells w/ <b>imprecise soma contours</b></dd>" //
                    + "<dt><i>" + ROI_CONTAINED + "</i></dt>" //
                    + "<dd>ROI marks the location of a single root. Suitable for polarized cells with <b>only one neurite extending from ROI</b></dd>" //
                    + "</dl>")
    private String rootChoice;

    @Parameter(label = "Active plane only", description = "<HTML>Assumes that the root(s) highlighted by the ROI occur at the<br>"
            + "ROI's Z-plane. Ensures other possible end-/junction- points above or below<br>the ROI are not considered. Ignored if image is 2D")
    private boolean roiPlane;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER3 = "<HTML>&nbsp;<br><b> III. Loop Resolution";

    @Parameter(label = "Nicking strategy:", choices = {
            "Dimmest branch (intensity image required)",
            "Dimmest voxel (intensity image required)",
            "Shortest branch",
            "Shortest segment (preserves longest paths)",
            "Furthest from root (preserves proximal structure)",
            "Peripheral segments (preserves backbone)"
    },
            description = "<HTML>Strategy for breaking loops/cycles in the skeleton:<dl>"
                    + "<dt><i>Dimmest branch</i></dt>"
                    + "<dd>Removes the branch with lowest average intensity. <b>Requires intensity image</b></dd>"
                    + "<dt><i>Dimmest voxel</i></dt>"
                    + "<dd>Cuts at the single dimmest pixel in the loop. <b>Requires intensity image</b></dd>"
                    + "<dt><i>Shortest branch</i></dt>"
                    + "<dd>Removes the shortest branch (path between junctions)."
                    + "<br>Fast, but may break main structures</dd>"
                    + "<dt><i>Shortest segment</i></dt>"
                    + "<dd>Removes shortest edge(s). <b>Preserves longest continuous paths</b>."
                    + "<br>Suitable for simple loops</dd>"
                    + "<dt><i>Furthest from root</i></dt>"
                    + "<dd>Removes edges most distant from root. <b>Preserves proximal structure</b>."
                    + "<br><b>Requires a root-defining ROI</b>. Suitable when proximal loops are more meaningful</dd>"
                    + "<dt><i>Peripheral segments</i></dt>"
                    + "<dd>Removes edges with lowest connectivity. <b>Preserves main backbone/trunk</b>."
                    + "<br>Suitable for complex structures</dd>"
                    + "</dl>")
    private String loopSolvingChoice;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4 = "<HTML>&nbsp;<br><b> IV. Gaps &amp; Disconnected Components";

    @Parameter(label = "Prune small components", description = "<HTML>Whether to ignore disconnected components below sub-threshold length")
    private boolean pruneByLength;

    @Parameter(label = "Length threshold", description = "<HTML>Disconnected structures below this cable length will be discarded.<br>"
            + "Increase this value if the algorithm produces too many isolated branches.<br>Decrease it to enrich for larger, contiguous structures.<br><br>"
            + "This value is only used if \"Prune small components\" is enabled.")
    private double lengthThreshold;

    @Parameter(label = "Bridge gaps", description = "<HTML>If the segmented image is fragmented into multiple components:<br>"
            + "Should the algorithm attempt to connect nearby components?")
    private boolean connectComponents;

    @Parameter(label = "Max. connection distance", min = "0.0", description = "<HTML>The maximum allowable distance between disconnected "
            + "components to be merged.<br>"
            + "Increase this value if the algorithm produces too many gaps.<br>Decrease it to minimize spurious connections.<br><br>"
            + "This value is only used if \"Bridge gaps\" is enabled. Merges<br>"
            + "occur only between end-points and only when the operation does not introduce loops")
    private double maxConnectDist;

    @Parameter(label = "Prune single-node paths", description = "<HTML>If checked, any single-point paths without any children are never created")
    private boolean cullSingleNodePaths;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER5 = "<HTML>&nbsp;<br><b> V. Options";

    @Parameter(label = "Replace existing paths", description = "<HTML>Whether any existing paths should be discarded "
            + "before conversion")
    private boolean clearExisting;

    @Parameter(label = "Apply distinct colors", description = "<HTML>Whether paths should be assigned unique colors")
    private boolean assignDistinctColors;

    @Parameter(label = "Activate 'Edit Mode'", description = "<HTML>Whether SNT's 'Edit Mode' should be activated after command finishes")
    private boolean editMode;

    @Parameter(label = "Debug mode", persist = false, callback = "debugModeCallback", description = "<HTML>Enable SNT's debug mode for verbose Console logs?")
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
            resolveInput("cullSingleNodePaths");
            resolveInput("clearExisting");
            resolveInput("assignDistinctColors");
            useFileChoosers = false;
            maskImgChoice = IMG_TRACED_DUP_CHOICE;
            final MutableModuleItem<String> rootChoiceItem = getInfo().getMutableInput("rootChoice", String.class);
            rootChoiceItem.setValue(this, ROI_EDGE); // mostly likely to succeed (assuming a ROI exists)
            connectComponents = true;
            maxConnectDist = 5d; // hopefully 5 microns
            pruneByLength = true;
            lengthThreshold = 1d;
            cullSingleNodePaths = true;
            clearExisting = false;
            assignDistinctColors = true;
            editMode = false;

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
            if (!impCollection.isEmpty()) {
                final ImagePlus existingImp = snt.getImagePlus();
                for (final ImagePlus imp : impCollection) {
                    if (imp.equals(existingImp) || isHyperstack(imp)) continue;
                    impMap.put(imp.getTitle(), imp);
                    maskChoices.add(imp.getTitle());
                    originalChoices.add(imp.getTitle());
                }
                Collections.sort(maskChoices);
                Collections.sort(originalChoices);
            }

            if (snt.accessToValidImageData()) {
                maskChoices.addFirst(IMG_TRACED_DUP_CHOICE);
                maskChoices.addFirst(IMG_TRACED_SEC_LAYER_CHOICE);
                originalChoices.addFirst(IMG_TRACED_CHOICE);
                if (isSegmented(snt.getImagePlus())) {
                    // the active image is binary: assume it is the segmented (non-skeletonized)
                    maskImgChoice = IMG_TRACED_DUP_CHOICE;
                } else {
                    // the active image is grayscale: assume it is the original
                    originalImgChoice = IMG_TRACED_CHOICE;
                }
            }
            if (maskChoices.isEmpty())
                maskChoices.add(IMG_UNAVAILABLE_CHOICE);
            originalChoices.addFirst(IMG_NONE);
            maskImgChoiceItem.setChoices(maskChoices);
            originalImgChoiceItem.setChoices(originalChoices);
        }

        debugMode = SNTUtils.isDebugMode();
    }

    @SuppressWarnings("unused")
    private void debugModeCallback() {
        SNTUtils.setDebugMode(debugMode);
    }

    private boolean isHyperstack(final ImagePlus imp) {
        return imp.getNChannels() > 1 || imp.getNFrames() > 1;
    }

    private int getRootStrategy() {
        return switch (rootChoice) {
            case ROI_CENTROID -> BinaryTracer.ROI_CENTROID;
            case ROI_CENTROID_WEIGHTED -> BinaryTracer.ROI_CENTROID_WEIGHTED;
            case ROI_EDGE -> BinaryTracer.ROI_EDGE;
            case ROI_CONTAINED -> BinaryTracer.ROI_CONTAINED;
            default -> BinaryTracer.ROI_UNSET;
        };
    }

    private void assignRoiZPosition(final Roi roi) {
        if (roiPlane && roi.getZPosition() == 0)
            roi.setPosition(chosenMaskImp);
        else if (!roiPlane)
            roi.setPosition(0);
    }

    private boolean isSegmented(final ImagePlus imp) {
        return imp.getProcessor().isBinary() || imp.isThreshold();
    }

    @Override
    public void cancel() {
        cancel("");
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
                chosenMaskImp = ImpUtils.open(maskImgFileChoice);
                ensureMaskImgVisibleOnAbort = true;
                if (SNTUtils.fileAvailable(originalImgFileChoice)) {
                    SNTUtils.log("Loading " + originalImgFileChoice.getAbsolutePath());
                    chosenOrigImp = ImpUtils.open(originalImgFileChoice);
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
                    if (snt.getImagePlus() != null) {
                        chosenMaskImp.setRoi(snt.getImagePlus().getRoi());
                        if (snt.getImagePlus().isThreshold()) {
                            chosenMaskImp.getProcessor().setThreshold(snt.getImagePlus().getProcessor().getMinThreshold(),
                                    snt.getImagePlus().getProcessor().getMaxThreshold());
                        }
                    }
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

            if (loopSolvingChoice != null && loopSolvingChoice.contains("dimmest") && chosenOrigImp == null) {
                chosenMaskImp = null;
                ensureMaskImgVisibleOnAbort = false;
                error("The chosen loop resolution strategy requires an intensity image, but none was provided.");
                return;
            }

            // Extra user-friendliness: Retrieve ROI. If not found,
            // look for it in the image overlay or on second  image
            final Roi roi = getRoi(chosenMaskImp, chosenOrigImp, snt.getImagePlus());
            // Extra user-friendliness: Aggregate unexpected settings in a single list
            final boolean isSame = (useFileChoosers) ? (maskImgFileChoice == originalImgFileChoice) : (maskImgChoice.equals(originalImgChoice));
            final boolean isSegmented = isSegmented(chosenMaskImp);
            final boolean isCompatible = chosenOrigImp == null
                    || chosenMaskImp.getCalibration().equals(chosenOrigImp.getCalibration());
            final boolean isSameDim = chosenOrigImp == null || (chosenMaskImp.getWidth() == chosenOrigImp.getWidth()
                    && chosenMaskImp.getHeight() == chosenOrigImp.getHeight()
                    && chosenMaskImp.getNSlices() == chosenOrigImp.getNSlices());
            final boolean isValidConnectDist = maxConnectDist > 0d;
            final boolean isValidRoi = roi != null && roi.isArea();
            boolean inferRootFromRoi = !ROI_UNSET.equals(rootChoice);
            if (isSame || !isValidOrigImg || !isSegmented || !isSameDim || !isCompatible || (!isValidRoi && inferRootFromRoi)
                    || (!isValidConnectDist && connectComponents)) {
                final int width = GuiUtils
                        .renderedWidth("      Warning: Images do not share the same spatial calibration<");
                final StringBuilder sb = new StringBuilder("<HTML><div WIDTH=").append(Math.max(550, width))
                        .append("><p>The following issue(s) were detected:</p><ul>");
                if (isSame) {
                    sb.append("<li>Warning: Choices for segmented and original image point to the same image</li>");
                }
                if (!isValidOrigImg) {
                    sb.append("<li>Warning: Original image is not valid and will be ignored</li>");
                }
                if (!isSameDim) {
                    sb.append("<li>Warning: Images do not share the same dimensions. Algorithm will likely fail</li>");
                    ensureMaskImgVisibleOnAbort = true;
                }
                if (!isSegmented) {
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
                sb.append("<p>Would you like to proceed? If you abort, ");
                if (ensureMaskImgVisibleOnAbort) {
                    sb.append(" segmented image will be displayed so that you can edit it accordingly. You can then rerun");
                } else {
                    sb.append(" you can rerun later on");
                }
                sb.append(" using <i>Utilities > Extract Paths From Segmented Image...</i>");
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
            SNTUtils.log("Segmented image thresholded/binarized: " + isSegmented);
            SNTUtils.log("Original image: " + ((chosenOrigImp == null) ? null : chosenOrigImp.getTitle()));
            SNTUtils.log("Root-defining strategy: " + rootChoice);
            SNTUtils.log("ROI: " + roi);

            // Skeletonize all images again, just to ensure we are indeed dealing with skeletons
            snt.setCanvasLabelAllPanes("Skeletonizing..");
            BinaryTracer.skeletonize(chosenMaskImp, chosenMaskImp.getNSlices() == 1);

            // fallback loop-solving strategy if intensity image is not available
            final int fallbackLoopSolvingStrategy = BinaryTracer.PERIPHERAL_SEGMENTS;

            // Now we can finally run the conversion!
            snt.setCanvasLabelAllPanes("Autotracer running...");
            status("Creating Trees from Skeleton...", false);
            final BinaryTracer converter = new BinaryTracer(chosenMaskImp, false);
            SNTUtils.log("Converting....");
            setPruneMode(converter);
            converter.setPruneByLength(pruneByLength);
            SNTUtils.log("Prune by length: " + pruneByLength);
            converter.setLengthThreshold(lengthThreshold);
            SNTUtils.log("Length threshold: " + lengthThreshold);
            converter.setConnectComponents(connectComponents);
            SNTUtils.log("Connect components: " + connectComponents);
            converter.setMaxConnectDist(maxConnectDist);
            SNTUtils.log("Max connecting dist.: " + maxConnectDist);

            final int chosenMode = converter.getPruneMode();
            if (chosenOrigImp == null) {
                // User chose intensity-based but no intensity image available → fallback
                if (chosenMode == BinaryTracer.LOWEST_INTENSITY_BRANCH ||
                        chosenMode == BinaryTracer.LOWEST_INTENSITY_VOXEL) {
                    converter.setPruneMode(fallbackLoopSolvingStrategy);
                    SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter) +
                            " (fallback; intensity image not available)");
                } else {
                    SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter));
                }
            } else {
                converter.setOrigIP(chosenOrigImp);
                SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter));
            }

            if (inferRootFromRoi && isValidRoi) {
                assignRoiZPosition(roi);
                converter.setRootRoi(roi, getRootStrategy());
            }
            List<Tree> trees;
            try {
                trees = converter.getTrees();
            } catch (final ClassCastException ignored) {
                if (chosenOrigImp != null &&
                        (converter.getPruneMode() == BinaryTracer.LOWEST_INTENSITY_BRANCH ||
                                converter.getPruneMode() == BinaryTracer.LOWEST_INTENSITY_VOXEL))
                    SNTUtils.log("Intensity-based pruning failed (unsupported image type!?): Defaulting to fallback strategy");
                converter.setPruneMode(fallbackLoopSolvingStrategy);

                try {
                    trees = converter.getTrees();
                } catch (final IllegalStateException ex) {
                    error(ex.getMessage() + ".<br>The ROI strategy may be creating unsolvable loops in the " +
                            "structure. It may be beneficial to adopt a less restrictive option.");
                    SNTUtils.error("", ex);
                    return;
                }
            }
            if (trees == null) {
                error("No paths could be extracted. No structures found in image!?");
                return;
            }
            if (trees.isEmpty()) {
                error("No paths could be extracted. Chosen parameters were not suitable!?");
                return;
            }
            SNTUtils.log("... Done. " + trees.size() + " component(s) retrieved.");
            trees.forEach(tree -> {
                for (final Iterator<Path> it = tree.list().iterator(); it.hasNext(); ) {
                    final Path path = it.next();
                    path.setCTposition(snt.getChannel(), snt.getFrame());
                    if (cullSingleNodePaths && path.size() == 1 && path.getChildren().isEmpty())
                        it.remove();
                }
            });
            final PathAndFillManager pafm = sntService.getPathAndFillManager();
            if (clearExisting) {
                pafm.clear();
            }
            if (assignDistinctColors) {
                trees.forEach(tree -> {
                    final ColorRGB[] colors = SNTColor.getDistinctColors(tree.size());
                    int idx = 0;
                    for (final Path p : tree.list())
                        p.setColor(colors[idx++]);
                });
            } else {
                TreeUtils.assignUniqueColors(trees);
            }
            trees.forEach(tree -> pafm.addTree(tree, "Autotraced"));
            if (trees.size() > 1)
                ui.getPathManager().applyDefaultTags("Arbor ID");

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
                if (!trees.getFirst().isEmpty())
                    ui.getPathManager().setSelectedPaths(Collections.singleton(trees.getFirst().get(0)), this);
                ui.setVisibilityFilter("all", false);
                resetUI(false, SNTUI.EDITING);
            } else {
                resetUI(false, SNTUI.READY);
            }
            status("Successfully created " + trees.size() + " Tree(s)...", true);
            if (chosenOrigImp != null && converter.getPruneMode() == BinaryTracer.PERIPHERAL_SEGMENTS) {
                info("Intensity-based resolution of loops could not be used. 'Peripheral segments' pruning was used " +
                        "instead.<br><i>" + originalImgChoice + "</i> (" + chosenOrigImp.getBitDepth() + "-bit " +
                        "image) may not allow for this option.");
            }
        } catch (final Throwable ex) {
            ex.printStackTrace();
            error("An exception occurred. See Console for details.");
        } finally {
            snt.setCanvasLabelAllPanes(null);
        }
    }

    private String pruneModeToString(final BinaryTracer binaryTracer) {
        return switch (binaryTracer.getPruneMode()) {
            case BinaryTracer.LOWEST_INTENSITY_BRANCH -> "LOWEST_INTENSITY_BRANCH";
            case BinaryTracer.LOWEST_INTENSITY_VOXEL -> "LOWEST_INTENSITY_VOXEL";
            case BinaryTracer.SHORTEST_BRANCH -> "SHORTEST_BRANCH";
            case BinaryTracer.SHORTEST_EDGE -> "SHORTEST_EDGE";
            case BinaryTracer.PERIPHERAL_SEGMENTS -> "PERIPHERAL_SEGMENTS";
            case BinaryTracer.MOST_DISTAL -> "MOST_DISTAL";
            default -> "UNKNOWN(" + binaryTracer.getPruneMode() + ")";
        };
    }

    public void setPruneMode(final BinaryTracer skConverter) {
        if (loopSolvingChoice == null || loopSolvingChoice.isBlank()) {
            skConverter.setPruneMode(BinaryTracer.SHORTEST_BRANCH);
            return;
        }
        final String pMode = loopSolvingChoice.toLowerCase();
        if (pMode.contains("shortest segment") || pMode.contains("longest path"))
            skConverter.setPruneMode(BinaryTracer.SHORTEST_EDGE);
        else if (pMode.contains("peripheral segment") || pMode.contains("backbone"))
            skConverter.setPruneMode(BinaryTracer.PERIPHERAL_SEGMENTS);
        else if (pMode.contains("furthest") || pMode.contains("proximal"))
            skConverter.setPruneMode(BinaryTracer.MOST_DISTAL);
        else if (pMode.contains("intensity") || pMode.contains("dimmest"))
            skConverter.setPruneMode((pMode.contains("branch"))
                    ? BinaryTracer.LOWEST_INTENSITY_BRANCH : BinaryTracer.LOWEST_INTENSITY_VOXEL);
        else
            skConverter.setPruneMode(BinaryTracer.SHORTEST_BRANCH); // default, also matches "shortest"
    }

    private Roi getRoi(final ImagePlus... imps) {
        for (final ImagePlus imp : imps) {
            if (imp == null)
                continue;
            Roi roi = imp.getRoi();
            if (roi == null && imp.getOverlay() != null) {
                roi = imp.getOverlay().get(0);
                imp.setRoi(roi);
            }
            if (roi != null)
                return roi;
        }
        return null;
    }

    private void info(final String msg) {
        if (ui != null)
            ui.showMessage(msg, "Automated Tracing");
        else
            SNTUtils.log(msg);
    }

    @Override
    protected void error(final String msg) {
        super.error(msg);
        abortRun = true; // should not be needed but isCanceled() is not working as expected!?
        resolveAllInputs();
        if (ensureMaskImgVisibleOnAbort && chosenMaskImp != null) {
            chosenMaskImp.setTitle("Skeletonized_" + chosenMaskImp.getTitle().replace("DUP_", ""));
            chosenMaskImp.show();
        }
    }

    private void noImgError() {
        error("To run this command you must first open a pre-processed image from which paths can be extracted (i.e., "
                + "in which background pixels have been removed). E.g.:"
                + "<ul><li>A segmented (thresholded) image (8-bit)</li>"
                + "<li>A filtered image, as created by the <i>Secondary Layer Creation Wizard</i> in the <i>Auto-tracing</i> widget</li>"
                + "</ul>" + "<p>" + "<p>Related Scripts:</p>" + "<ul>" + "<li>Batch &rarr Filter Multiple Images</li>"
                + "<li>Skeletons and ROIs &rarr Reconstruction From Segmented Image</li>" + "</ul>" + "<p>To rerun:</p>"
                + "<ul>" + "<li>Utilities &rarr Autotrace Segmented Image... (opened images)</li>"
                + "<li>File &rarr AutoTrace Segmented Image File... (unopened files)</li>");
    }

    private void resolveAllInputs() { // ensures prompt is not displayed on error
        getInputs().keySet().forEach(this::resolveInput);
    }
}
