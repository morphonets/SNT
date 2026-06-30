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

import ij.ImagePlus;
import ij.gui.Roi;
import org.scijava.ItemVisibility;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.ChooseDatasetCmd;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.BinaryTracer;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.TreeUtils;

import java.io.File;
import java.util.*;

/**
 * Abstract base command providing shared parameters and logic for {@link BinaryTracer}-based autotracing. Subclassed by
 * {@link BinaryTracerCmd} (interactive, image already loaded) and file-based variants (non-interactive,
 * batch processing).
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public abstract class BinaryTracerCommonCmd extends CommonDynamicCmd {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    // ROI strategy constants
    public static final String ROI_AUTO_DETECT = "None. Use auto-detection";
    public static final String ROI_UNSET = "None. Ignore any ROIs";
    public static final String ROI_CONTAINED = "ROI marks a single root";
    public static final String ROI_EDGE = "Path(s) branch out from ROI edge";
    public static final String ROI_CENTROID = "Path(s) branch out from ROI simple centroid";
    public static final String ROI_CENTROID_WEIGHTED = "Path(s) branch out from ROI weighted centroid";

    // Image choice constants
    static final String IMG_NONE = "None";
    static final String IMG_UNAVAILABLE_CHOICE = "No other image open";
    static final String IMG_TRACED_CHOICE = "Image being traced";
    static final String IMG_TRACED_DUP_CHOICE = "Image being traced (duplicate)";
    static final String IMG_TRACED_SEC_LAYER_CHOICE = "Secondary image layer";
    static final String AFTER_DO_NOTHING = "Do nothing";
    static final String AFTER_REPLACE = "Replace existing paths";
    static final String AFTER_PROOFREAD = "Prepare for proofreading";
    static final String AFTER_REPLACE_AND_PROOFREAD = "Replace existing paths & prepare for proofreading";

    // I. Input Image(s)
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER1 = "<HTML><b> I. Input Image(s)";

    @Parameter(label = "Segmented Image", required = false, description = "<HTML>Image from which paths will be extracted. Will be skeletonized by the algorithm.<br>"
            + "If thresholded, only highlighted pixels are considered, otherwise all non-zero<br>intensities will be taken into account", style = ChoiceWidget.LIST_BOX_STYLE)
    String maskImgChoice;

    @Parameter(label = "Input directory", required = false, style = "directory",
            description = "<HTML>Directory containing segmented images to be traced.<br>" +
                    "Images will be skeletonized by the algorithm assuming neurites are defined by non-zero intensities")
    protected File inputDir;

    @Parameter(label = "Intensity image", required = false,
            description = "<HTML>Optional. Original (un-processed) image used to resolve loops<br>"//
                    + "in the segmented image using brightness criteria.", style = ChoiceWidget.LIST_BOX_STYLE)
    protected String originalImgChoice;

    // II. Soma/Root Detection
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER2 = "<HTML>&nbsp;<br><b> II. Soma/Root Detection";

    @Parameter(required = false, label = "ROI strategy", choices = {ROI_AUTO_DETECT, ROI_UNSET, ROI_EDGE, ROI_CENTROID, ROI_CENTROID_WEIGHTED, ROI_CONTAINED}, //
            description = "<HTML>Defines how the root(s) of the structure are determined.<br><dl>" //
                    + "<dt><i>" + ROI_AUTO_DETECT + "</i></dt>" //
                    + "<dd>Automatically detects the soma using EDT×intensity scoring and roots the tree at its <b>centroid</b></dd>" //
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
    protected String rootChoice;

    @Parameter(required = false, label = "Active plane only", description = "<HTML>Assumes that the root(s) highlighted by the ROI occur at the<br>"
            + "ROI's Z-plane. Ensures other possible end-/junction- points above or below<br>the ROI are not considered. Ignored if image is 2D")
    protected boolean roiPlane;

    // III. Loop Resolution
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER3 = "<HTML><b> III. Loop Resolution";

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
    protected String loopSolvingChoice;

    // IV. Gaps & Disconnected Components
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4 = "<HTML>&nbsp;<br><b> IV. Gaps &amp; Disconnected Components";

    @Parameter(label = "Prune small components", description = "<HTML>Whether to ignore disconnected components below sub-threshold length")
    protected boolean pruneByLength;

    @Parameter(label = "Length threshold", description = "<HTML>Disconnected structures below this cable length will be discarded.<br>"
            + "Increase this value if the algorithm produces too many isolated branches.<br>Decrease it to enrich for larger, contiguous structures.<br><br>"
            + "This value is only used if \"Prune small components\" is enabled.")
    protected double lengthThreshold;

    @Parameter(label = "Bridge gaps", description = "<HTML>If the segmented image is fragmented into multiple components:<br>"
            + "Should the algorithm attempt to connect nearby components?")
    protected boolean connectComponents;

    @Parameter(label = "Max. connection distance", min = "0.0", description = "<HTML>The maximum allowable distance between disconnected "
            + "components to be merged.<br>"
            + "Increase this value if the algorithm produces too many gaps.<br>Decrease it to minimize spurious connections.<br><br>"
            + "This value is only used if \"Bridge gaps\" is enabled. Merges<br>"
            + "occur only between end-points and only when the operation does not introduce loops")
    protected double maxConnectDist;

    @Parameter(label = "Prune single-node paths", description = "<HTML>If checked, any single-point paths without any children are never created")
    protected boolean cullSingleNodePaths;

    // V. Options
    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER5 = "<HTML><b>V. Options";

    @Parameter(label = "Debug mode", persist = false, callback = "debugModeCallback", description = "<HTML>Enable SNT's debug mode for verbose Console logs?")
    protected boolean debugMode;

    @Parameter(label = "After tracing",
            choices = {AFTER_DO_NOTHING, AFTER_REPLACE, AFTER_PROOFREAD, AFTER_REPLACE_AND_PROOFREAD},
            description = "<HTML>What to do after tracing completes:<dl>" +
                    "<dt><i>" + AFTER_DO_NOTHING + "</i></dt>" +
                    "<dd>Keep existing paths; add new traces alongside them</dd>" +
                    "<dt><i>" + AFTER_REPLACE + "</i></dt>" +
                    "<dd>Clear existing paths before adding new ones</dd>" +
                    "<dt><i>" + AFTER_PROOFREAD + "</i></dt>" +
                    "<dd>Keep existing paths, assign unique colors to new traces,<br>" +
                    "auto-calibrate the Curation Manager, and enable live monitoring</dd>" +
                    "<dt><i>" + AFTER_REPLACE_AND_PROOFREAD + "</i></dt>" +
                    "<dd>Replace, assign unique colors, auto-calibrate the<br>" +
                    "Curation Manager from the traced result, and enable<br>" +
                    "live monitoring for immediate proofreading</dd>" +
                    "</dl>")
    protected String afterTracingChoice = AFTER_DO_NOTHING;

    @Parameter(required = false, persist = false)
    protected boolean headless;

    // Protected fields
    protected HashMap<String, ImagePlus> impMap;
    protected ImagePlus chosenMaskImp;
    protected boolean abortRun;
    protected boolean ensureMaskImgVisibleOnAbort;
    protected File maskImgFileBeingProcessed;

    /**
     * Returns whether this command operates in file mode (loading images
     * from file paths) or in image-choice mode (selecting from open images).
     *
     * @return {@code true} for file-based operation, {@code false} for
     * choice-widget operation
     */
    protected abstract boolean isFileMode();

    @SuppressWarnings("unused")
    private void debugModeCallback() {
        SNTUtils.setDebugMode(debugMode);
    }

    /**
     * Initializes the command for interactive (image-choice) mode. Populates
     * choice widgets from open images and resolves file chooser fields.
     */
    protected void initForImage() {
        super.init(true);
        resolveInput("headless");
        resolveInput("inputDir");

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

        debugMode = debugMode || SNTUtils.isDebugMode();
        if (headless) {
            resolveAllInputs();
        }
    }

    /**
     * Initializes the command for file-based (batch) mode. Hides choice widgets
     * and resolves fields that are only relevant in interactive mode. Debug mode
     * is always enabled in batch mode.
     */
    protected void initForFile() {
        super.init(true);
        resolveInput("headless");
        resolveInput("maskImgChoice");
        resolveInput("originalImgChoice");
        SNTUtils.setDebugMode(debugMode = true);
        if (headless) {
            resolveAllInputs();
        }
    }

    /**
     * Executes the core tracing pipeline: loads images (from file or choice
     * widgets based on {@link #isFileMode()}), validates inputs, runs
     * {@link BinaryTracer}, and post-processes the resulting trees.
     */
    protected void runCommand() {
        if (abortRun || isCanceled()) {
            return;
        }

        ImagePlus chosenOrigImp = null;
        boolean isValidOrigImg = true;

        try {

            if (isFileMode()) {

                if (!SNTUtils.fileAvailable(maskImgFileBeingProcessed)) {
                    error("File path of segmented image is invalid.");
                    return;
                }
                SNTUtils.log("Loading " + maskImgFileBeingProcessed.getAbsolutePath());
                chosenMaskImp = ImpUtils.open(maskImgFileBeingProcessed);
                ensureMaskImgVisibleOnAbort = true;

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
                } else if (impMap != null) {
                    chosenOrigImp = impMap.get(originalImgChoice);
                }
            }

            // Abort if images remain ill-defined at this point
            if (chosenMaskImp == null) {
                if (IMG_TRACED_SEC_LAYER_CHOICE.equals(maskImgChoice)) {
                    final String msg1 = "No secondary layer image exists. Please load one or create it using the "
                            + "<i>Built-in Filters</i> wizard in the <i>Auto-tracing</i> widget.";
                    final String msg2 = " retry automated tracing using <i>Utilities > Extract Paths From Segmented Image...";
                    error(msg1 + "<br><br>You can always" + msg2);
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

            // Aggregate unexpected settings for validation
            final boolean isSame = !isFileMode() && (maskImgChoice != null && maskImgChoice.equals(originalImgChoice));
            final boolean isSegmented = isSegmented(chosenMaskImp);
            final boolean isCompatible = chosenOrigImp == null || ImpUtils.sameCalibration(chosenMaskImp, chosenOrigImp);
            final boolean isSameDim = chosenOrigImp == null || ImpUtils.sameXYZDimensions(chosenOrigImp, chosenMaskImp);
            final boolean isValidConnectDist = maxConnectDist > 0d;
            final boolean autoDetectSoma = ROI_AUTO_DETECT.equals(rootChoice);

            // Retrieve ROI. If not found, look for it in the image overlay or on second image
            final Roi roi = (autoDetectSoma) ? null : getRoi(chosenMaskImp, chosenOrigImp, (snt != null) ? snt.getImagePlus() : null);
            final boolean isValidRoi = roi != null && roi.isArea();
            boolean inferRootFromRoi = !ROI_UNSET.equals(rootChoice) && !autoDetectSoma;

            if (isSame || !isValidOrigImg || !isSegmented || !isSameDim || !isCompatible || (!isValidRoi && inferRootFromRoi)
                    || (!isValidConnectDist && connectComponents)) {
                if (!validateBeforeTracing(roi, inferRootFromRoi, isSame, isSegmented, isValidOrigImg,
                        isSameDim, isCompatible, isValidConnectDist, isValidRoi)) {
                    return;
                }
                // Update flags: validateBeforeTracing may have modified connectComponents
                // directly, and inferRootFromRoi is derived from the validation result
                connectComponents = connectComponents && isValidConnectDist;
                inferRootFromRoi = inferRootFromRoi && isValidRoi;
            }

            SNTUtils.log("Segmented image: " + chosenMaskImp.getTitle());
            SNTUtils.log("Segmented image thresholded/binarized: " + isSegmented);
            SNTUtils.log("Original image: " + ((chosenOrigImp == null) ? null : chosenOrigImp.getTitle()));
            SNTUtils.log("ROI strategy: " + rootChoice);
            SNTUtils.log("ROI: " + roi);

            // Skeletonize all images again, just to ensure we are indeed dealing with skeletons
            snt.setCanvasLabelAllPanes("Skeletonizing..");
            BinaryTracer.skeletonize(chosenMaskImp, chosenMaskImp.getNSlices() == 1);

            // Now we can finally run the conversion!
            snt.setCanvasLabelAllPanes("Autotracer running...");
            status("Creating Trees from Skeleton...", false);
            final BinaryTracer converter = createAndConfigureConverter(chosenMaskImp, chosenOrigImp);

            if (autoDetectSoma) {
                // Auto-detect soma from the segmented image using EDT×intensity scoring
                snt.setCanvasLabelAllPanes("Detecting soma...");
                final SomaUtils.SomaResult somaResult = SomaUtils.detectSoma(
                        ImpUtils.toImgPlus(chosenMaskImp), -1d, -1);
                if (somaResult != null && somaResult.hasContour()) {
                    SNTUtils.log("Auto-detected soma: " + somaResult);
                    final Roi somaRoi = somaResult.createContourRoi();
                    if (somaRoi != null && somaResult.zSlice() >= 0) {
                        somaRoi.setPosition(somaResult.zSlice() + 1);
                        converter.setRootRoi(somaRoi, BinaryTracer.ROI_CENTROID);
                        chosenMaskImp.setRoi(somaRoi);
                    }
                    SNTUtils.log("Auto-detected soma ROI: " + somaRoi);
                } else {
                    SNTUtils.log("Soma auto-detection did not find a soma. Using arbitrary root.");
                }
            } else if (inferRootFromRoi) {
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
                converter.setPruneMode(BinaryTracer.PERIPHERAL_SEGMENTS);
                try {
                    trees = converter.getTrees();
                } catch (final IllegalStateException ex) {
                    error(ex.getMessage() + ".<br>The ROI strategy may be creating unsolvable loops in the " +
                            "structure. It may be beneficial to adopt a less restrictive option.");
                    SNTUtils.error("", ex);
                    return;
                }
            } catch (final IllegalArgumentException iae) {
                error(iae.getMessage() +".");
                SNTUtils.error("", iae);
                return;
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
                for (final Iterator<sc.fiji.snt.Path> it = tree.list().iterator(); it.hasNext(); ) {
                    final sc.fiji.snt.Path path = it.next();
                    path.setCTposition(snt.getChannel(), snt.getFrame());
                    if (cullSingleNodePaths && path.size() == 1 && path.getChildren().isEmpty())
                        it.remove();
                }
            });

            handleTracedTrees(trees);

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

    /**
     * Validates configuration before tracing begins. The default (non-interactive)
     * implementation logs warnings and adjusts flags as needed without prompting
     * the user. Interactive subclasses may override this to display a confirmation
     * dialog.
     * <p>
     * This method may modify {@link #connectComponents} and
     * {@link #ensureMaskImgVisibleOnAbort} directly.
     *
     * @param roi                the active ROI (may be {@code null})
     * @param inferRootFromRoi   whether root inference from ROI is requested
     * @param isSame             whether mask and original image are the same
     * @param isSegmented        whether the mask image is binary/thresholded
     * @param isValidOrigImg     whether the original image path is valid
     * @param isSameDim          whether mask and original share dimensions
     * @param isCompatible       whether images share spatial calibration
     * @param isValidConnectDist whether the max connection distance is valid
     * @param isValidRoi         whether the ROI is a valid area ROI
     * @return {@code true} to proceed with tracing, {@code false} to abort
     */
    protected boolean validateBeforeTracing(final Roi roi, final boolean inferRootFromRoi,
                                            final boolean isSame, final boolean isSegmented,
                                            final boolean isValidOrigImg, final boolean isSameDim,
                                            final boolean isCompatible, final boolean isValidConnectDist,
                                            final boolean isValidRoi) {
        // Log every warning regardless of UI presence
        if (isSame) {
            SNTUtils.log("Warning: Choices for segmented and original image point to the same image");
        }
        if (!isValidOrigImg) {
            SNTUtils.log("Warning: Original image is not valid and will be ignored");
        }
        if (!isSameDim) {
            SNTUtils.log("Warning: Images do not share the same dimensions. Algorithm will likely fail");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isSegmented) {
            SNTUtils.log("Info: Image is not thresholded: Non-zero intensities will be used as foreground");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isCompatible) {
            SNTUtils.log("Warning: Images do not share the same spatial calibration");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isValidRoi && inferRootFromRoi) {
            SNTUtils.log("Warning: Image does not contain an active area ROI. Root detection will be disabled");
        }
        if (!isValidConnectDist && connectComponents) {
            SNTUtils.log("Warning: Max. connection distance must be > 0. Connection of components will be disabled");
        }
        // Headless / file-based mode (no UI): proceed after logging.
        if (ui == null) {
            connectComponents = connectComponents && isValidConnectDist;
            return true;
        }
        // UI present: present the warnings as a confirmation dialog.
        final int width = GuiUtils.renderedWidth(
                "      Warning: Images do not share the same spatial calibration<");
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
        }
        if (!isSegmented) {
            sb.append("<li>Info: Image is not thresholded: Non-zero intensities will be used as foreground</li>");
        }
        if (!isCompatible) {
            sb.append("<li>Warning: Images do not share the same spatial calibration</li>");
        }
        if (!isValidRoi && inferRootFromRoi) {
            sb.append("<li>Warning: Image does not contain an active area ROI. Root detection will be disabled</li>");
        }
        if (!isValidConnectDist && connectComponents) {
            sb.append("<li>Warning: Max. connection distance must be > 0. Connection of components will be disabled</li>");
        }
        sb.append("</ul><p>Would you like to proceed? If you abort, ");
        if (ensureMaskImgVisibleOnAbort) {
            sb.append("the segmented image will be displayed so that you can edit it accordingly. You can then rerun");
        } else {
            sb.append("you can rerun later");
        }
        sb.append(" using <i>Utilities &gt; Extract Paths From Segmented Image...</i></p>");
        if (!new GuiUtils().getConfirmation(sb.toString(), "Proceed Despite Warnings?",
                "Proceed. I'm Feeling Lucky", "Abort")) {
            if (ensureMaskImgVisibleOnAbort && chosenMaskImp != null) chosenMaskImp.show();
            resetUI(false, SNTUI.SNT_PAUSED); // waive image to IJ for easier ROI editing, etc.
            cancel();
            return false;
        }
        // Adjust flags
        connectComponents = connectComponents && isValidConnectDist;
        return true;
    }

    /**
     * Focused pre-flight for seed-driven subclasses that bypass {@link #runCommand}
     * (and therefore {@link #validateBeforeTracing}). Confirms with the user
     * before treating a non-segmented (grayscale) image as a tracing mask.
     * Returns {@code true} (proceed) when the image is already segmented, when
     * running headless, or when the user accepts the prompt.
     *
     * @param mask the candidate mask image
     * @return {@code true} to proceed with tracing, {@code false} to abort
     */
    protected boolean confirmIfNotSegmented(final ImagePlus mask) {
        if (mask == null || isSegmented(mask)) return true;
        SNTUtils.log("Info: Image is not thresholded: Non-zero intensities will be used as foreground");
        if (ui == null) return true;
        return new GuiUtils().getConfirmation(
                "<HTML><div WIDTH=550>The chosen image (<i>" + mask.getTitle() + "</i>) is not thresholded " +
                        "or binarized. Non-zero intensities will be used as foreground, which is rarely what " +
                        "you want when tracing skeleton-style structures.<br><br>Proceed anyway?",
                "Image Not Segmented",
                "Proceed", "Abort");
    }

    /**
     * Builds and configures a {@link BinaryTracer} for the supplied images. Wraps the constructor with the standard
     * configuration  (prune mode, length threshold, component-connection options, loop-solving strategy,
     * original-intensity image), so {@link #runCommand}  and seed-driven subclasses
     * (e.g. {@code AutotraceFromBinarySeedsCmd}) can share the same configuration pipeline without duplicating it.
     * <p>
     * Caller is expected to supply a mask image that has already been skeletonized; this method does <em>not</em>
     * re-skeletonize. The  intensity image is optional: when {@code null}, intensity-based loop solving falls back to
     * {@link BinaryTracer#PERIPHERAL_SEGMENTS}. Root placement (via {@code setRootRoi} / {@code setRoots}) is the
     * caller's responsibility — this method leaves the tracer's root unset so the same flow can serve both ROI-driven
     * and seed-driven runs.
     *
     * @param maskImp the skeletonized mask image (required).
     * @param origImp the original intensity image (optional; may be {@code null}).
     * @return a configured but unrooted {@link BinaryTracer}; caller adds a root and invokes
     * {@link BinaryTracer#getTrees()}.
     */
    protected BinaryTracer createAndConfigureConverter(final ImagePlus maskImp,
                                                       final ImagePlus origImp) {
        final BinaryTracer converter = new BinaryTracer(maskImp, false);
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

        // Loop-solving fallback when intensity image is not available
        final int fallbackLoopSolvingStrategy = BinaryTracer.PERIPHERAL_SEGMENTS;
        final int chosenMode = converter.getPruneMode();
        if (origImp == null) {
            if (chosenMode == BinaryTracer.LOWEST_INTENSITY_BRANCH || chosenMode == BinaryTracer.LOWEST_INTENSITY_VOXEL) {
                converter.setPruneMode(fallbackLoopSolvingStrategy);
                SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter) +
                        " (fallback; intensity image not available)");
            } else {
                SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter));
            }
        } else {
            converter.setOrigIP(origImp);
            SNTUtils.log("Loop-resolving method: " + pruneModeToString(converter));
        }
        return converter;
    }

    /**
     * Called after tracing completes with valid trees. The default implementation honors {@link #afterTracingChoice}:
     * optionally replaces existing paths, assigns colors (per-tree "dim" palette when proofreading, inter-tree
     * otherwise), adds trees via PathAndFillManager, and optionally calibrates the Curation Manager for proofreading.
     * Subclasses may override to export trees to disk or adopt the mask image as tracing canvas.
     *
     * @param trees the traced trees (guaranteed non-null and non-empty)
     */
    protected void handleTracedTrees(final List<Tree> trees) {
        // Decode the composite afterTracingChoice into independent flags
        final boolean replace = AFTER_REPLACE.equals(afterTracingChoice) || AFTER_REPLACE_AND_PROOFREAD.equals(afterTracingChoice);
        final boolean proofread = AFTER_PROOFREAD.equals(afterTracingChoice) || AFTER_REPLACE_AND_PROOFREAD.equals(afterTracingChoice);
        final PathAndFillManager pafm = sntService.getPathAndFillManager();
        if (replace) {
            pafm.clear();
        }
        if (proofread) {
            trees.forEach(tree -> TreeUtils.assignUniqueColors(tree, "dim"));
        } else {
            TreeUtils.assignUniqueColors(trees);
        }
        pafm.addTrees(trees, "BinaryTracer");
        if (ui != null && trees.size() > 1)
            ui.getPathManager().applyDefaultTags("Arbor ID");
        resetUI(false, SNTUI.READY);
        if (proofread && ui != null) {
            ui.getCurationManager().calibrateFromTrees(trees);
        }
        status("Successfully created " + trees.size() + " Tree(s)...", true);
    }

    /**
     * Returns the {@link BinaryTracer} root strategy constant corresponding to the current {@link #rootChoice} selection.
     *
     * @return the root strategy constant
     */
    protected int getRootStrategy() {
        return switch (rootChoice) {
            case ROI_CENTROID -> BinaryTracer.ROI_CENTROID;
            case ROI_CENTROID_WEIGHTED -> BinaryTracer.ROI_CENTROID_WEIGHTED;
            case ROI_EDGE -> BinaryTracer.ROI_EDGE;
            case ROI_CONTAINED -> BinaryTracer.ROI_CONTAINED;
            case null, default -> BinaryTracer.ROI_UNSET;
        };
    }

    /**
     * Assigns or clears the Z-position on the given ROI based on  {@link #roiPlane} and the current mask image.
     *
     * @param roi the ROI to configure
     */
    protected void assignRoiZPosition(final Roi roi) {
        if (roiPlane && roi.getZPosition() == 0)
            roi.setPosition(chosenMaskImp);
        else if (!roiPlane)
            roi.setPosition(0);
    }

    /**
     * Checks whether the given image is binary (segmented) or thresholded.
     *
     * @param imp the image to check
     * @return {@code true} if the image is binary or has an active threshold
     */
    protected boolean isSegmented(final ImagePlus imp) { return ImpUtils.isBinary(imp) || imp.isThreshold(); }

    private boolean isHyperstack(final ImagePlus imp) {
        return imp.getNChannels() > 1 || imp.getNFrames() > 1;
    }

    /**
     * Configures the prune mode on a {@link BinaryTracer} based on the  current {@link #loopSolvingChoice} selection.
     *
     * @param skConverter the tracer to configure
     */
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

    /**
     * Returns a human-readable string for the current prune mode of the given tracer.
     *
     * @param binaryTracer the tracer to inspect
     * @return a string describing the prune mode
     */
    protected String pruneModeToString(final BinaryTracer binaryTracer) {
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

    /**
     * Retrieves the first non-null ROI from the supplied images. Falls back to the first ROI in each image's overlay if
     * no direct ROI is set.
     *
     * @param imps the images to search for ROIs
     * @return the first ROI found, or {@code null} if none exists
     */
    protected Roi getRoi(final ImagePlus... imps) {
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

    /**
     * Displays an informational message via the SNT UI, or logs it if no UI is available.
     *
     * @param msg the message to display
     */
    protected void info(final String msg) {
        if (ui != null)
            ui.showMessage(msg, "Automated Tracing");
        else
            SNTUtils.log(msg);
    }

    protected void noImgError() {
        error("To run this command you must first open a pre-processed image from which paths can be extracted (i.e., "
                + "in which background pixels have been removed). E.g.:"
                + "<ul><li>A segmented (thresholded) image (8-bit)</li>"
                + "<li>A filtered image, as created by the <i>Secondary Layer Creation Wizard</i> in the <i>Auto-tracing</i> widget</li>"
                + "</ul>" + "<p>" + "<p>Related Scripts:</p>" + "<ul>" + "<li>Batch &rarr Filter Multiple Images</li>"
                + "<li>Skeletons and ROIs &rarr Reconstruction From Segmented Image</li>" + "</ul>" + "<p>To rerun:</p>"
                + "<ul>" + "<li>Utilities &rarr Autotrace Segmented Image... (opened images)</li>"
                + "<li>File &rarr AutoTrace Segmented Image File... (unopened files)</li>");
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
    protected void error(final String msg) {
        super.error(msg);
        abortRun = true; // should not be needed but isCanceled() is not working as expected!?
        resolveAllInputs();
        if (ensureMaskImgVisibleOnAbort && chosenMaskImp != null) {
            chosenMaskImp.setTitle("Skeletonized_" + chosenMaskImp.getTitle().replace("DUP_", ""));
            chosenMaskImp.show();
        }
    }

    private void resolveAllInputs() { // ensures prompt is not displayed on error
        getInputs().keySet().forEach(this::resolveInput);
    }
}
