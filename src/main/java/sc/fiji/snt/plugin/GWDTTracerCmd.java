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
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.AbstractAutoTracer;
import sc.fiji.snt.tracing.auto.GWDTTracer;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.TreeUtils;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Command providing a GUI for {@link GWDTTracer}, SNT's intensity-guided
 * autotracing class based on Gray-Weighted Distance Transform and Fast Marching.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Automated Tracing: Tree from Grayscale Image (GWDT)...", initializer = "init")
public class GWDTTracerCmd extends CommonDynamicCmd {

    // ROI strategy constants
    private static final String ROI_UNSET = "None. Use auto-detection";
    private static final String ROI_EDGE = "Area ROI around soma: One tree per primary neurite";
    private static final String ROI_CENTROID = "Single tree rooted at ROI centroid";
    private static final String ROI_CENTROID_WEIGHTED = "Single tree rooted at ROI weighted centroid";
    private static final String IMG_TRACED_CHOICE = "Image being traced";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private final String msg1 = "<HTML>This command traces neurons directly from grayscale images using Gray-Weighted<br>" +
            "Distance Transform (GWDT) and Fast Marching. It does not require binarization.";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER1 = "<HTML>&nbsp;<br><b>I. Input Image";

    @Parameter(label = "Grayscale image", required = false,
            description = "<HTML>The grayscale image to trace. Should have bright foreground<br>" +
                    "structures on a dark background.",
            style = ChoiceWidget.LIST_BOX_STYLE)
    private String imgChoice;

    @Parameter(label = "Path to image", required = false,
            description = "<HTML>Path to grayscale image file",
            style = FileWidget.OPEN_STYLE)
    private File imgFileChoice;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER2 = "<HTML>&nbsp;<br><b>II. Soma/Root Detection";

    @Parameter(required = false, label = "ROI strategy", choices = {ROI_UNSET, ROI_EDGE, ROI_CENTROID, ROI_CENTROID_WEIGHTED},
            description = "<HTML>How to use any active ROI for seed/root detection:<dl>" +
                    "<dt><i>" + ROI_UNSET + "</i></dt>" +
                    "<dd>Auto-detect soma/root at thickest/brightest region. Ignore any ROIs</dd>" +
                    "<dt><i>" + ROI_EDGE + "</i></dt>" +
                    "<dd>Area ROI delineates soma. <b>Separate trees</b> created for each exiting neurite</dd>" +
                    "<dt><i>" + ROI_CENTROID + "</i></dt>" +
                    "<dd>ROI marks soma location. <b>Single tree</b> rooted at ROI centroid</dd>" +
                    "<dt><i>" + ROI_CENTROID_WEIGHTED + "</i></dt>" +
                    "<dd>As above but uses intensity-weighted centroid of traced soma nodes</dd>" +
                    "</dl>")
    private String somaStrategyChoice;

    @Parameter(label = "Active plane only",
            description = "<HTML>If checked, ROI applies only to its Z-slice.<br>" +
                    "If unchecked, ROI applies to all Z-slices.")
    private boolean roiPlaneOnly;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER3 = "<HTML>&nbsp;<br><b>III. Thresholding";

    @Parameter(label = "Background threshold", min = "-1", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Intensity cutoff: pixels ≤ this value are background.<br>" +
                    "<b>Lower</b>: traces more (including noise).<br?" +
                    "<b>Higher</b>: traces less (may miss dim neurites).<br>" +
                    "Range: [-1, image max]; Default: -1 (auto: [~5% above image minimum/90th percentile])")
    private double backgroundThreshold = -1;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4 = "<HTML>&nbsp;<br><b>IV. Branch Filtering";

    @Parameter(label = "Min. branch length", min = "0", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Minimum intensity-weighted length for a branch to be kept.<br>" +
                    "<b>Lower</b>: keeps more short branches.<br>" +
                    "<b>Higher</b>: removes short branches.<br>" +
                    "Range: [0, ∞); Default: 5.0")
    private double lengthThreshold = 5.0;

    @Parameter(label = "Branch sensitivity", min = "0", max = "1", stepSize = "0.05",
            style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>How much signal a branch needs relative to already-traced regions.<br>" +
                    "<b>Lower</b>: keeps more branches (permissive).<br>" +
                    "<b>Higher</b>: keeps fewer (strict).<br>" +
                    "Range: [0, 1]; Default: 0.3")
    private double srRatio = 0.3;

    @Parameter(label = "Overlap tolerance", min = "0", max = "1", stepSize = "0.05",
            style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>How much a node can overlap existing traces before being pruned.<br>" +
                    "<b>Lower</b>: less overlap allowed (stricter).<br>" +
                    "<b>Higher</b>: more overlap allowed (permissive).<br>" +
                    "Range: [0, 1]; Default: 0.1")
    private double sphereOverlapThreshold = 0.1;

    @Parameter(label = "Strict tip filtering",
            description = "<HTML>Removes terminal branches overlapping thicker parent structures.<br>" +
                    "<b>Enable</b>: cleaner results, may truncate thin neurites near large somas.<br>" +
                    "<b>Disable</b>: preserves thin branches, may include spurious tips.<br>" +
                    "Default: disabled")
    private boolean leafPruneEnabled;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER5 = "<HTML>&nbsp;<br><b>V. Smoothing &amp; Resampling";

    @Parameter(label = "Smooth paths",
            description = "<HTML>Apply moving-average smoothing to reduce jaggedness.<br>" +
                    "Default: enabled")
    private boolean smoothEnabled = true;

    @Parameter(label = "Smoothing window", min = "3", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Number of neighboring nodes used for smoothing.<br>" +
                    "<b>Lower</b>: preserves detail.<br>" +
                    "<b>Higher</b>: smoother curves.<br>" +
                    "Range: [3, 15]; Default: 5")
    private int smoothWindowSize = 5;

    @Parameter(label = "Resample paths",
            description = "<HTML>Reduce point density by resampling at regular intervals.<br>" +
                    "Default: enabled")
    private boolean resampleEnabled = true;

    @Parameter(label = "Resample interval", min = "0.5", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Spacing between resampled points (in voxels).<br>" +
                    "<b>Lower</b>: more points, finer detail.<br>" +
                    "<b>Higher</b>: fewer points, smaller files.<br>" +
                    "Range: [0.5, 10]; Default: 2.0")
    private double resampleStep = 2.0;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER6 = "<HTML>&nbsp;<br><b>VI. Options";

    @Parameter(label = "Connectivity", choices = {"Low", "Medium", "High"},
            description = "<HTML>Neighborhood connectivity for Fast Marching:<br><br>" +
                    "<b>3D images:</b><br>" +
                    "&nbsp;Low = 6-connected (face neighbors)<br>" +
                    "&nbsp;Medium = 18-connected (face + edge)<br>" +
                    "&nbsp;High = 26-connected (all neighbors)<br><br>" +
                    "<b>2D images:</b><br>" +
                    "&nbsp;Low = 4-connected (edge neighbors)<br>" +
                    "&nbsp;Medium/High = 8-connected (edge + corner)<br><br>" +
                    "Higher connectivity is more thorough but slower.<br>" +
                    "Default: Medium")
    private String connectivityChoice = "Medium";

    @Parameter(label = "Replace existing paths",
            description = "<HTML>Whether to clear existing paths before adding new ones")
    private boolean clearExisting;

    @Parameter(label = "Apply distinct colors",
            description = "<HTML>Whether paths should be assigned unique colors")
    private boolean assignDistinctColors = true;

    @Parameter(label = "Activate 'Edit Mode'",
            description = "<HTML>Whether SNT's 'Edit Mode' should be activated after tracing")
    private boolean editMode;

    @Parameter(label = "Debug mode", persist = false, callback = "debugModeCallback",
            description = "<HTML>Enable verbose logging to Console")
    private boolean debugMode;

    @Parameter(required = false, persist = false)
    private boolean useFileChoosers;

    private boolean abortRun;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);

        if (useFileChoosers) {
            resolveInput("imgChoice");
        } else {
            if (!snt.accessToValidImageData()) {
                noValidImgError();
                return;
            }
            resolveInput("imgFileChoice");
            final MutableModuleItem<String> imgChoiceItem = getInfo().getMutableInput("imgChoice", String.class);
            final List<String> choices = new ArrayList<>();
            choices.add(String.format("%s [C%dT%d]", IMG_TRACED_CHOICE, snt.getChannel(), snt.getFrame()));
            choices.add("Secondary image layer");
            imgChoiceItem.setChoices(choices);
            imgChoice = choices.getFirst();
        }
        debugMode = SNTUtils.isDebugMode();
    }

    private void noValidImgError() {
        error("This option requires valid image data to be loaded. " +
                "The image should have bright foreground structures on a dark background.");
    }

    private boolean binaryImgError(final ImgPlus<?> img) {
        if (ImgUtils.isBinary(img)) {
            error("Image is not grayscale. Binary (thresholded) images can be<br>" +
                    "parsed using the 'Auto-trace Segmented Image...' command.");
            return true;
        }
        return false;
    }

    private ImgPlus<?> getImgFromImgChoice() {
        ImgPlus<?> chosenImp;
        if (useFileChoosers) {
            if (!SNTUtils.fileAvailable(imgFileChoice)) {
                error("File path is invalid.");
                return null;
            }
            SNTUtils.log("Loading " + imgFileChoice.getAbsolutePath());
            chosenImp = ImgUtils.open(imgFileChoice);
            if (chosenImp == null) {
                error("Could not load image.");
                return null;
            }
            if (ImgUtils.isRGB(chosenImp) || ImgUtils.isMultiChannelRGB(chosenImp)) {
                error("RGB images are not supported. Please convert to grayscale first.");
                return null;
            }
        } else {
            final boolean secLayer = !imgChoice.startsWith(IMG_TRACED_CHOICE);
            chosenImp = snt.getLoadedDataAsImg(secLayer);
            if (chosenImp == null) {
                if (secLayer)
                    error("No secondary image has been defined. Please create or load one first.");
                else
                    noValidImgError();
                return null;
            }
        }
        return (binaryImgError(chosenImp)) ? null : chosenImp;
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

        try {
            final ImgPlus<?> chosenImp = getImgFromImgChoice();
            if (chosenImp == null || abortRun) return;

            status("Running GWDT tracing...", false);
            snt.setCanvasLabelAllPanes("GWDT Autotracing...");

            // Create tracer
            final GWDTTracer<?> tracer = GWDTTracer.create(chosenImp);
            tracer.setVerbose(debugMode);
            tracer.setBackgroundThreshold(backgroundThreshold);
            tracer.setMinSegmentLengthVoxels(lengthThreshold);
            tracer.setSrRatio(srRatio);
            tracer.setSphereOverlapThreshold(sphereOverlapThreshold);
            tracer.setLeafPruneEnabled(leafPruneEnabled);
            tracer.setSmoothEnabled(smoothEnabled);
            tracer.setSmoothWindowSize(smoothWindowSize);
            tracer.setResampleEnabled(resampleEnabled);
            tracer.setResampleStep(resampleStep);
            tracer.setConnectivityType(parseConnectivity(connectivityChoice));
            tracer.setVerbose(debugMode);

            // Get ROI and configure strategy
            final int seedStrategy = parseRoiStrategy();
            final double[] seedPhysical;
            final String errorMsg;
            if (seedStrategy == GWDTTracer.ROI_UNSET) { // Auto-detect
                seedPhysical = findSomaCenter(chosenImp);
                errorMsg = "Automated detection of soma failed. Please Pause SNT, draw a " +
                        "ROI around the soma, and re-run with a ROI-based strategy.";
            } else { // Read from ROI
                final Roi roi = getRoiFromSNT();
                if (seedStrategy == GWDTTracer.ROI_EDGE && (roi == null || !roi.isArea())) {
                    errorMsg = "The chosen ROI strategy requires an area ROI but none exists.";
                    seedPhysical = null;
                } else {
                    if (roi != null) tracer.setSomaRoi(roi, seedStrategy);
                    seedPhysical = getRoiCentroid(roi, chosenImp, getActiveZPosFromSNT());
                    errorMsg = "Could not infer soma from ROI. Please provide a valid soma contour, " +
                            "or re-run with an automated detection strategy.";
                }
            }
            if (seedPhysical == null) {
                error(errorMsg);
                return;
            }

            tracer.setSeedPhysical(seedPhysical);

            // Run tracing - may return multiple trees with ROI_EDGE
            final List<Tree> trees = tracer.traceTrees();
            if (trees == null || trees.isEmpty() || (trees.size() == 1 && trees.getFirst().size() < 2)) {
                error("No paths could be extracted. Check parameters and re-run.");
                return;
            }

            // Add to PathAndFillManager
            final PathAndFillManager pafm = sntService.getPathAndFillManager();
            if (clearExisting) {
                pafm.clear();
            }

            // Assign image calibration; set channel/frame position and unique colors; and add to PathAndFillManager
            for (final Tree tree : trees) {
                tree.assignImage(chosenImp);
                tree.list().forEach(path -> path.setCTposition(snt.getChannel(), snt.getFrame()));
                if (assignDistinctColors) TreeUtils.assignUniqueColors(tree, "dim");
                pafm.addTree(tree, "GWDT Autotraced");

            }

            if (trees.size() > 1) {
                ui.getPathManager().applyDefaultTags("Arbor ID");
            }

            if (editMode && ui != null && pafm.size() > 0) {
                if (!trees.isEmpty() && !trees.getFirst().isEmpty()) {
                    ui.getPathManager().setSelectedPaths(Collections.singleton(trees.getFirst().get(0)), this);
                }
                ui.setVisibilityFilter("all", false);
                resetUI(false, SNTUI.EDITING);
            } else {
                resetUI(false, SNTUI.READY);
            }

            status("Successfully traced " + trees.size() + " tree(s)", true);

        } catch (final Throwable ex) {
            ex.printStackTrace();
            error("An exception occurred. See Console for details.");
        } finally {
            snt.setCanvasLabelAllPanes(null);
        }
    }

    /**
     * Gets ROI from image or overlay
     */
    private Roi getRoiFromSNT() {
        final ImagePlus imp = snt.getImagePlus();
        if (imp == null) return null;
        Roi roi = imp.getRoi();
        if (roi == null && imp.getOverlay() != null && imp.getOverlay().size() > 0) {
            roi = imp.getOverlay().get(0);
        }
        return roi;
    }

    private int getActiveZPosFromSNT() {
        final ImagePlus imp = snt.getImagePlus();
        return (imp == null) ? -1 : imp.getZ() - 1; // 0-based index
    }

    /**
     * Parses the ROI strategy choice to GWDTTracer constant.
     */
    private int parseRoiStrategy() {
        if (somaStrategyChoice == null) return GWDTTracer.ROI_UNSET;
        return switch (somaStrategyChoice) {
            case ROI_EDGE -> GWDTTracer.ROI_EDGE;
            case ROI_CENTROID -> GWDTTracer.ROI_CENTROID;
            case ROI_CENTROID_WEIGHTED -> GWDTTracer.ROI_CENTROID_WEIGHTED;
            default -> GWDTTracer.ROI_UNSET;
        };
    }

    /**
     * Gets center point from any ROI in physical coordinates.
     */
    private double[] getRoiCentroid(final Roi roi, final ImgPlus<?> imgPlus, final int activeZ) {
        if (roi == null) return null;

        final double[] pixelCoords;

        if (roi.getType() == Roi.POINT) {
            // Point ROI: use direct coordinates
            pixelCoords = new double[]{roi.getXBase(), roi.getYBase()};
        } else if (roi.isArea()) {
            // Area ROI: use contour centroid
            final double[] centroid = roi.getContourCentroid();
            if (centroid == null || centroid.length < 2) return null;
            pixelCoords = centroid;
        } else {
            // Line or other: use bounds center
            final Rectangle2D bounds = roi.getFloatBounds();
            pixelCoords = new double[]{bounds.getCenterX(), bounds.getCenterY()};
        }

        final double[] spacing = ImgUtils.getSpacing(imgPlus);
        final int zDim = imgPlus.dimensionIndex(Axes.Z);
        final boolean is3D = zDim >= 0 && imgPlus.dimension(zDim) > 1;

        final double x = pixelCoords[0] * spacing[0];
        final double y = pixelCoords[1] * spacing[1];

        if (is3D) {
            final long nSlices = imgPlus.dimension(zDim);
            final int zSlice;
            if (roi.getZPosition() > 0) {
                zSlice = roi.getZPosition() - 1;
            } else if (activeZ >= 0 && activeZ < nSlices) {
                zSlice = activeZ;
            } else {
                zSlice = (int) (nSlices / 2);
            }
            final double z = zSlice * spacing[zDim];
            return new double[]{x, y, z};
        }

        return new double[]{x, y};
    }

    @SuppressWarnings("unchecked")
    private double[] findSomaCenter(final ImgPlus<?> img) {
        final RandomAccessibleInterval<? extends RealType<?>> rai = (RandomAccessibleInterval<? extends RealType<?>>) img;
        // Find the thickest point Otsu thresholding & EDT
        return AbstractAutoTracer.findRootPhysical(rai, ImgUtils.getSpacing(img));
    }

    private int parseConnectivity(final String choice) {
        return switch (choice.toLowerCase()) {
            case "low" -> 1;
            case "high" -> 3;
            default -> 2;  // Medium
        };
    }

    @Override
    protected void error(final String msg) {
        getInputs().keySet().forEach(this::resolveInput); // resolve all inputs
        abortRun = true;
        super.error(msg);
        snt.setCanvasLabelAllPanes(null);
    }
}
