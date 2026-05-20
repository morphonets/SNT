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
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import org.scijava.ItemVisibility;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.GWDTTracer;
import sc.fiji.snt.tracing.auto.GWDTTracerFactory;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.TreeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Base command providing shared parameters and logic for GWDT autotracing.
 * Subclassed by {@link GWDTTracerCmd} (interactive, image already loaded) and
 * {@link GWDTTracerFileCmd} (non-interactive, file-based).
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCmd
 * @see GWDTTracerFileCmd
 */
public abstract class GWDTTracerCommonCmd extends CommonDynamicCmd {

    // ROI strategy constants
    private static final String ROI_UNSET = "None. Use auto-detection";
    private static final String ROI_AUTO_EDGE = "None. Auto-detect soma: One tree per primary neurite";
    private static final String ROI_EDGE = "Area ROI around soma: One tree per primary neurite";
    private static final String ROI_CENTROID = "Single tree rooted at ROI centroid";
    private static final String ROI_CENTROID_WEIGHTED = "Single tree rooted at ROI weighted centroid";
    // Image choice constants
    static final String IMG_TRACED_CHOICE = "Image being traced";
    // Score map strategy constants
    static final String SCORE_MAP_NONE = "None. Disable score mapping";
    static final String SCORE_MAP_TUBENESS = "Tubeness (default)";
    static final String SCORE_MAP_FRANGI = "Frangi";
    static final String SCORE_MAP_OTHER = "Secondary image layer";
    // After-tracing action constants
    static final String AFTER_DO_NOTHING = "Do nothing";
    private static final String AFTER_REPLACE = "Replace existing paths";
    private static final String AFTER_PROOFREAD = "Prepare for proofreading";
    private static final String AFTER_REPLACE_AND_PROOFREAD = "Replace existing paths & prepare for proofreading";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER1 = "<HTML><b>I. Input Image";

    @Parameter(label = "Grayscale image", required = false,
            description = "<HTML>The grayscale image to trace. Should have bright foreground<br>" +
                    "structures on a dark background.",
            style = ChoiceWidget.LIST_BOX_STYLE)
    protected String imgChoice;

    @Parameter(label = "Path to image(s)", required = false,
            description = "<HTML>Path to a grayscale image file or a directory of images",
            style = FileWidget.OPEN_STYLE)
    protected File imgFileChoice;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER2 = "<HTML>&nbsp;<br><b>II. Soma/Root Detection";

    @Parameter(required = false, label = "ROI strategy", choices = {ROI_UNSET, ROI_AUTO_EDGE, ROI_EDGE, ROI_CENTROID, ROI_CENTROID_WEIGHTED},
            description = "<HTML>How to determine soma/root location:<dl>" +
                    "<dt><i>" + ROI_UNSET + "</i></dt>" +
                    "<dd>Auto-detect soma. <b>Single tree</b> rooted at detected centroid. Ignores any ROIs</dd>" +
                    "<dt><i>" + ROI_AUTO_EDGE + "</i></dt>" +
                    "<dd>Auto-detect soma contour. <b>Separate trees</b> for each neurite exiting the detected soma. No ROI needed</dd>" +
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
    private String HEADER3 = "<HTML><b>III. Thresholding";

    @Parameter(label = "Background threshold", min = "-1", style = "format:#.00",
            description = "<HTML>Intensity cutoff: pixels ≤ this value are background.<br>" +
                    "<b>Lower</b>: Traces more (may include noise).<br>" +
                    "<b>Higher</b>: Traces less (may miss dim neurites).<br>" +
                    "Range: [-1, image max]; Default: -1 (auto)")
    protected double backgroundThreshold = -1.00;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4 = "<HTML><b>IV. Branch Filtering and Scoring";

    @Parameter(label = "Score map filter", choices = {SCORE_MAP_NONE, SCORE_MAP_TUBENESS, SCORE_MAP_FRANGI, SCORE_MAP_OTHER},
            description = "<HTML>Compute a vesselness (Tubeness/Frangi) score map to prune<br>" +
                    "low-confidence segments. Expensive but effective for noisy data.<br>" +
                    "Tubeness is faster; Frangi may be more selective.<br>" +
                    "Default: Tubeness")
    protected String scoreMapFilter = SCORE_MAP_TUBENESS;

    @Parameter(label = "Min. branch score", min = "0", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Minimum branch significance for a branch to be kept.<br>" +
                    "Measured as the sum of normalized intensities along the branch,<br>" +
                    "so brighter/longer branches score higher.<br>" +
                    "<b>Lower</b>: Keeps more short/dim branches.<br>" +
                    "<b>Higher</b>: Removes short/dim branches.<br>" +
                    "Range: [0, ∞); Default: 5.0")
    protected double lengthThreshold = 5.0;

    @Parameter(label = "Branch sensitivity", min = "0", max = "1", stepSize = "0.05",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>How much signal a branch needs relative to already-traced regions.<br>" +
                    "<b>Lower</b>: Keeps more branches (permissive).<br>" +
                    "<b>Higher</b>: Keeps fewer branches (strict).<br>" +
                    "Range: [0, 1]; Default: 0.3")
    private double srRatio = 0.3;

    @Parameter(label = "Overlap tolerance", min = "0", max = "1", stepSize = "0.05",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>How much a node can overlap existing traces before being pruned.<br>" +
                    "<b>Lower</b>: Less overlap allowed (stricter).<br>" +
                    "<b>Higher</b>: More overlap allowed (permissive).<br>" +
                    "Range: [0, 1]; Default: 0.1")
    private double sphereOverlapThreshold = 0.1;

    @Parameter(label = "Strict tip filtering",
            description = "<HTML>Remove terminal branches that overlap thicker parent structures.<br>" +
                    "<b>On</b>: Fewer spurious tips, may truncate thin neurites near large structures (e.g., the soma).<br>" +
                    "<b>Off</b>: Preserves thin branches, may include spurious tips.<br>" +
                    "Default: off")
    private boolean leafPruneEnabled;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4c = "<HTML><b>V. Post-processing";

    @Parameter(label = "Max. branching angle (°)", min = "-1", max = "180", style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>Maximum angle (degrees) for branch-point parent re-assignment.<br>" +
                    "Set to -1 to disable branch tuning.<br>" +
                    "Range: [-1, 180]; Default: 90°")
    protected double branchTuneMaxAngle = 90.0;

    @Parameter(label = "Tip extension distance (voxels)",
            description = "<HTML>Maximum distance (in voxels) for A*-based tip extension.<br>" +
                    "Extends leaf tips across gaps larger than the FM gap bridge can handle.<br>" +
                    "Set to 0 to disable. Default: 0 (disabled, experimental)")
    private double tipExtensionDistance = 0;

    @Parameter(label = "Remove zigzags",
            description = "<HTML>Iteratively smooth out zigzag artifacts at branch junctions.<br>" +
                    "Default: on")
    private boolean zigzagRemovalEnabled = true;

    @Parameter(label = "Remove overshoots",
            description = "<HTML>Trim nodes that extend past branch points into<br>" +
                    "the parent segment.<br>" +
                    "Default: on")
    private boolean overshootRemovalEnabled = true;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER5 = "<HTML>&nbsp;<br><b>VI. Smoothing &amp; Resampling";

    @Parameter(label = "Smoothing window", min = "1", max = "15", stepSize = "2",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>Moving-average window size for path smoothing.<br>" +
                    "<b>1</b>: Disabled (no smoothing).<br>" +
                    "<b>3–15</b>: Higher = smoother curves.<br>" +
                    "Default: 5")
    private int smoothWindowSize = 5;

    @Parameter(label = "Resample interval", min = "0", max = "10", stepSize = "1",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>Spacing between resampled points (in voxels).<br>" +
                    "<b>0</b>: Disabled (keep original point density).<br>" +
                    "<b>1–10</b>: Higher = fewer points, smaller files.<br>" +
                    "Default: 2")
    private double resampleStep = 2.0;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER6 = "<HTML>&nbsp;<br><b>VII. Options";

    @Parameter(label = "Connectivity", choices = {"Low", "Medium", "High"},
            style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE,
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

    @Parameter(label = "Debug mode", persist = false, callback = "debugModeCallback",
            description = "<HTML>Enable verbose logging to Console")
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
    String afterTracingChoice = AFTER_DO_NOTHING;

    protected boolean abortRun;
    protected ImgPlus<?> chosenImp;
    /** Populated by auto-detection; null when ROI-based strategy is used. */
    private SomaUtils.SomaResult detectedSoma;

    @SuppressWarnings("unused")
    private void debugModeCallback() {
        SNTUtils.setDebugMode(debugMode);
    }

    // --- Shared init helpers ---

    protected void initForImage() {
        super.init(true);
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
        debugMode = SNTUtils.isDebugMode();
    }

    protected void initForFile() {
        super.init(true);
        HEADER1 += "(s)";
        resolveInput("imgChoice");
        resolveInput("HEADER2");
        resolveInput("somaStrategyChoice"); // No active ROI in file mode: always auto-detect
        resolveInput("roiPlaneOnly");
        resolveInput("debugMode"); // debug mode is always enabled to report progress to console
        debugMode = SNTUtils.isDebugMode();
    }

    // --- Core tracing logic ---

    protected void runCommand() {
        try {
            chosenImp = getImgFromImgChoice();
            if (chosenImp == null || abortRun) return;

            status("Running GWDT tracing...", false);

            // Create tracer
            final boolean scoreMapEnabled = !SCORE_MAP_NONE.equals(scoreMapFilter);
            final AbstractGWDTTracer<?> tracer = GWDTTracerFactory.create(chosenImp);
            tracer.setStatusListener(snt::setCanvasLabelAllPanes);
            tracer.setVerbose(debugMode);
            tracer.setBackgroundThreshold(backgroundThreshold);
            tracer.setMinBranchIntensityLength(lengthThreshold);
            tracer.setSrRatio(srRatio);
            tracer.setSphereOverlapThreshold(sphereOverlapThreshold);
            tracer.setLeafPruneOverlap(leafPruneEnabled ? 0.9 : 0);
            tracer.setSmoothWindowSize(smoothWindowSize);
            tracer.setResampleStep(resampleStep);
            tracer.setConnectivityType(parseConnectivity(connectivityChoice));
            tracer.setTipExtensionDistance(tipExtensionDistance);
            tracer.setZigzagRemovalEnabled(zigzagRemovalEnabled);
            tracer.setOvershootRemovalEnabled(overshootRemovalEnabled);
            tracer.setBranchTuneMaxAngle(branchTuneMaxAngle < 0 ? Double.NaN : branchTuneMaxAngle);
            tracer.setScoreMapEnabled(scoreMapEnabled);
            if (scoreMapEnabled) {
                if (!SCORE_MAP_OTHER.equals(scoreMapFilter)) {
                    tracer.setScoreMapFilterType(
                            SCORE_MAP_FRANGI.equals(scoreMapFilter) ? SNT.FilterType.FRANGI : SNT.FilterType.TUBENESS);
                } else {
                    final RandomAccessibleInterval<?> secLayer = snt.getSecondaryData();
                    if (secLayer == null) {
                        error("No secondary image has been defined. Please create or load one first.");
                        return;
                    }
                    tracer.setScoreMap(snt.getSecondaryData());
                }
            }

            // Get ROI and configure strategy
            final int seedStrategy = parseRoiStrategy();
            final double[] seedPhysical;
            final String errorMsg;
            detectedSoma = null;
            if (seedStrategy == GWDTTracer.ROI_UNSET) {
                // Auto-detect soma using full SomaUtils pipeline (EDT×intensity)
                snt.setCanvasLabelAllPanes("Detecting soma...");
                detectedSoma = detectSoma(chosenImp);
                if (detectedSoma != null) {
                    // Use the detected contour as a soma ROI for rooting.
                    // ROI_AUTO_EDGE: one tree per neurite; ROI_UNSET: single tree at centroid
                    if (detectedSoma.hasContour()) {
                        final int autoRoiStrategy = ROI_AUTO_EDGE.equals(somaStrategyChoice)
                                ? GWDTTracer.ROI_EDGE : GWDTTracer.ROI_CENTROID;
                        final Roi somaRoi = detectedSoma.createContourRoi();
                        if (detectedSoma.zSlice() >= 0) somaRoi.setPosition(detectedSoma.zSlice() + 1);
                        tracer.setSomaRoi(somaRoi, autoRoiStrategy);
                        tracer.setSomaRoiZPosition(detectedSoma.zSlice());
                    }
                    // Convert center to physical coordinates for seed
                    final double[] spacing = ImgUtils.getSpacing(chosenImp);
                    seedPhysical = new double[spacing.length];
                    final long[] center = detectedSoma.center();
                    for (int d = 0; d < Math.min(center.length, spacing.length); d++) {
                        seedPhysical[d] = center[d] * spacing[d];
                    }
                    // Handle Z for 3D images where center is 2D (from a slice)
                    if (spacing.length > 2 && center.length <= 2 && detectedSoma.zSlice() >= 0) {
                        seedPhysical[2] = detectedSoma.zSlice() * spacing[2];
                    }
                    errorMsg = null;
                } else {
                    seedPhysical = null;
                    errorMsg = "Automated detection of soma failed. Please Pause SNT, draw a " +
                            "ROI around the soma, and re-run with a ROI-based strategy.";
                }
            } else {
                final Roi roi = getRoiFromSNT();
                if (seedStrategy == GWDTTracer.ROI_EDGE && (roi == null || !roi.isArea())) {
                    errorMsg = "The chosen ROI strategy requires an area ROI but none exists.";
                    seedPhysical = null;
                } else {
                    if (roi != null) {
                        tracer.setSomaRoi(roi, seedStrategy);
                        if (roiPlaneOnly) {
                            // Constrain ROI to the active Z-plane (or the ROI's own Z if set)
                            final int activeZ = getActiveZPosFromSNT();
                            tracer.setSomaRoiZPosition(
                                    (roi.getZPosition() > 0) ? roi.getZPosition() - 1 : Math.max(activeZ, 0));
                        } else {
                            tracer.setSomaRoiZPosition(-1); // apply to all Z-slices
                        }
                    }
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

            // Run tracing
            final List<Tree> trees = tracer.traceTrees();
            if (trees == null || trees.isEmpty() || (trees.size() == 1 && trees.getFirst().size() < 2)) {
                error("No paths could be extracted. Check parameters and re-run.");
                return;
            }

            handleTracedTrees(trees);

        } catch (final Throwable ex) {
            ex.printStackTrace();
            error("An exception occurred. See Console for details.");
        } finally {
            snt.setCanvasLabelAllPanes(null);
        }
    }

    /**
     * Called after tracing completes with valid trees. The default implementation
     * loads trees into PathAndFillManager and optionally triggers proofreading.
     * Subclasses (e.g., {@link GWDTTracerFileCmd}) may override to export trees
     * to disk instead.
     *
     * @param trees the traced trees (guaranteed non-empty)
     */
    protected void handleTracedTrees(final List<Tree> trees) {
        final boolean replace = AFTER_REPLACE.equals(afterTracingChoice) || AFTER_REPLACE_AND_PROOFREAD.equals(afterTracingChoice);
        final boolean proofread = AFTER_PROOFREAD.equals(afterTracingChoice) || AFTER_REPLACE_AND_PROOFREAD.equals(afterTracingChoice);
        final PathAndFillManager pafm = sntService.getPathAndFillManager();
        if (replace) {
            pafm.clear();
        }
        // Add a soma-tagged path when auto-detection provided a full result
        if (detectedSoma != null) {
            final Path somaPath = detectedSoma.toPath(ImgUtils.getSpacing(chosenImp));
            somaPath.setCTposition(snt.getChannel(), snt.getFrame());
            pafm.addPath(somaPath, false, true);
        }
        for (final Tree tree : trees) {
            tree.assignImage(chosenImp);
            tree.list().forEach(path -> path.setCTposition(snt.getChannel(), snt.getFrame()));
            if (proofread) TreeUtils.assignUniqueColors(tree, "dim");
            pafm.addTree(tree, "GWDT Autotraced");
        }
        if (trees.size() > 1) {
            ui.getPathManager().applyDefaultTags("Arbor ID");
        }
        resetUI(false, SNTUI.READY);
        if (proofread && ui != null) {
            ui.getCurationManager().calibrateFromTrees(trees);
        }
        status("Successfully traced " + trees.size() + " tree(s)", true);
    }

    // --- Image loading ---

    protected abstract boolean isFileMode();

    protected ImgPlus<?> getImgFromImgChoice() {
        ImgPlus<?> chosenImp;
        if (isFileMode()) {
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

    // --- Helpers ---

    protected void noValidImgError() {
        error("This option requires valid image data to be loaded. " +
                "The image should have bright foreground structures on a dark background.");
    }

    private boolean binaryImgError(final ImgPlus<?> img) {
        if (ImgUtils.isBinary(img)) {
            error("Image is not grayscale. Binary (thresholded) images can be " +
                    "parsed using the 'Auto-trace Segmented Image...' command.");
            return true;
        }
        return false;
    }

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
        return (imp == null) ? -1 : imp.getZ() - 1;
    }

    private int parseRoiStrategy() {
        if (somaStrategyChoice == null) return GWDTTracer.ROI_UNSET;
        return switch (somaStrategyChoice) {
            case ROI_EDGE -> GWDTTracer.ROI_EDGE;
            case ROI_CENTROID -> GWDTTracer.ROI_CENTROID;
            case ROI_CENTROID_WEIGHTED -> GWDTTracer.ROI_CENTROID_WEIGHTED;
            // Both ROI_UNSET and ROI_AUTO_EDGE trigger auto-detection; they differ
            // in which rooting strategy is applied to the detected contour
            default -> GWDTTracer.ROI_UNSET;
        };
    }

    private double[] getRoiCentroid(final Roi roi, final ImgPlus<?> imgPlus, final int activeZ) {
        final double[] pixelCoords = RoiConverter.get2dCentroid(roi);
        if (pixelCoords == null) return null;

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
            return new double[]{x, y, zSlice * spacing[zDim]};
        }
        return new double[]{x, y};
    }

    /**
     * Detects the soma in the given image using EDT×intensity scoring.
     * Returns a full {@link SomaUtils.SomaResult} with center, contour,
     * radius, and mask — or {@code null} if detection fails.
     */
    private SomaUtils.SomaResult detectSoma(final ImgPlus<?> img) {
        return SomaUtils.detectSoma(img);
    }

    private int parseConnectivity(final String choice) {
        return switch (choice.toLowerCase()) {
            case "low" -> 1;
            case "high" -> 3;
            default -> 2;
        };
    }

    @Override
    public void cancel(final String reason) {
        super.cancel(reason);
        snt.setCanvasLabelAllPanes(null);
        abortRun = true;
    }

    @Override
    protected void error(final String msg) {
        getInputs().keySet().forEach(this::resolveInput);
        abortRun = true;
        super.error(msg);
        snt.setCanvasLabelAllPanes(null);
    }
}
