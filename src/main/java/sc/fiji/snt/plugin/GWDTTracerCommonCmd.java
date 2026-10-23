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
import net.imglib2.type.numeric.RealType;
import org.scijava.ItemVisibility;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
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
import sc.fiji.snt.gui.cmds.AutoTraceConfigDialog;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;
import sc.fiji.snt.tracing.auto.GWDTTracer;
import sc.fiji.snt.tracing.auto.GWDTTracerFactory;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.TreeUtils;
import sc.fiji.snt.gui.GuiUtils;

import javax.swing.JDialog;
import javax.swing.SwingWorker;
import java.awt.Cursor;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    protected static final String SOMA_AUTO = "None. Use auto-detection";
    protected static final String SOMA_ROI_AUTO_EDGE = "None. Auto-detect soma: One tree per primary neurite";
    private static final String SOMA_ROI_EDGE = "Area ROI around soma: One tree per primary neurite";
    private static final String SOMA_ROI_CENTROID = "Single tree rooted at ROI centroid";
    private static final String SOMA_ROI_CENTROID_WEIGHTED = "Single tree rooted at ROI weighted centroid";
    /**
     * Stream-mode-only strategy: seeds detection from BDV/BVV marker(s) ({@code M} key), since Stream mode
     * has no Roi support. Only offered when {@code snt.isStreamMode()}; see {@link #initForImage()}
     */
    protected static final String SOMA_BOOKMARK = "Selected Bookmarks/markers define somata";


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

    @Parameter
    protected PrefService prefService;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER1 = "<HTML><b>Input Image";

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
    private String HEADER2 = "<HTML>&nbsp;<b>Soma/Root Detection";

    @Parameter(required = false, label = "ROI strategy",
            choices = {SOMA_AUTO, SOMA_ROI_AUTO_EDGE, SOMA_ROI_EDGE, SOMA_ROI_CENTROID, SOMA_ROI_CENTROID_WEIGHTED},
            description = "<HTML>How to determine soma/root location:<dl>" +
                    "<dt><i>" + SOMA_AUTO + "</i></dt>" +
                    "<dd>Auto-detect soma. <b>Single tree</b> rooted at detected centroid. Ignores any ROIs</dd>" +
                    "<dt><i>" + SOMA_ROI_AUTO_EDGE + "</i></dt>" +
                    "<dd>Auto-detect soma contour. <b>Separate trees</b> for each neurite exiting the detected soma. No ROI needed</dd>" +
                    "<dt><i>" + SOMA_ROI_EDGE + "</i></dt>" +
                    "<dd>Area ROI delineates soma. <b>Separate trees</b> created for each exiting neurite</dd>" +
                    "<dt><i>" + SOMA_ROI_CENTROID + "</i></dt>" +
                    "<dd>ROI marks soma location. <b>Single tree</b> rooted at ROI centroid</dd>" +
                    "<dt><i>" + SOMA_ROI_CENTROID_WEIGHTED + "</i></dt>" +
                    "<dd>As above but uses intensity-weighted centroid of traced soma nodes</dd>" +
                    "</dl>")
    protected String somaStrategyChoice;

    @Parameter(label = "Active plane only",
            description = "<HTML>If checked, ROI applies only to its Z-slice.<br>" +
                    "If unchecked, ROI applies to all Z-slices.")
    private boolean roiPlaneOnly;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER3 = "<HTML><b>Thresholding";

    @Parameter(label = "Background threshold", min = "-1", style = "format:#.00",
            description = "<HTML>Intensity cutoff: pixels ≤ this value are background.<br>" +
                    "<b>Lower</b>: Traces more (may include noise).<br>" +
                    "<b>Higher</b>: Traces less (may miss dim neurites).<br>" +
                    "Range: [-1, image max]; Default: -1 (auto)")
    protected double backgroundThreshold = -1.00;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER4 = "<HTML><b>Branch Filtering and Scoring";

    @Parameter(label = "Score map filter", choices = {SCORE_MAP_NONE, SCORE_MAP_TUBENESS, SCORE_MAP_FRANGI, SCORE_MAP_OTHER},
            callback = "scoreMapFilterCallback",
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
    private String HEADER4c = "<HTML><b>Post-processing";

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
    private String HEADER5 = "<HTML><b>Smoothing &amp; Resampling";

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
    private String HEADER6 = "<HTML>&nbsp;<br><b>Options";

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

    private static final String DEBUG_MODE_DESCRIPTION = "<HTML>Enable verbose logging to Console";

    @Parameter(label = "Debug mode", persist = false, callback = "debugModeCallback",
            description = DEBUG_MODE_DESCRIPTION)
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

    // NB: Widgets follow declaration order, base class first. To have the button after all the parameters of the
    // command, each concrete command declares it (see APPLY_DEFAULTS_LABEL) and calls applyDefaults() in its callback
    /** Label of the "apply defaults" button, declared by each concrete command */
    protected static final String APPLY_DEFAULTS_LABEL = "  Apply Defaults  ";
    /** Description of the "apply defaults" button */
    protected static final String APPLY_DEFAULTS_DESCRIPTION = "<HTML>Resets all fields above to their default values,<br>"
            + "including values adopted from learned parameters.";

    protected boolean abortRun;
    protected ImgPlus<?> chosenImp;
    /** Populated by auto-detection; null when ROI-based strategy is used. */
    private SomaUtils.SomaResult detectedSoma;

    @SuppressWarnings("unused")
    private void debugModeCallback() {
        SNTUtils.setDebugMode(debugMode);
    }

    /**
     * Toggles visibility of secondary-image inputs that are only relevant when
     * {@code scoreMapFilter == SCORE_MAP_OTHER}. The base implementation handles
     * any {@code secondaryImageSuffix} input declared by subclasses; subclasses
     * may override to control additional related inputs. Safe to call when the
     * input is not declared (no-op in that case).
     */
    @SuppressWarnings("unused")
    protected void scoreMapFilterCallback() {
        final MutableModuleItem<String> suffixItem =
                getInfo().getMutableInput("secondaryImageSuffix", String.class);
        if (suffixItem != null) {
            suffixItem.setVisibility(SCORE_MAP_OTHER.equals(scoreMapFilter)
                    ? ItemVisibility.NORMAL : ItemVisibility.INVISIBLE);
        }
    }

    protected void initForImage() {
        super.init(true);
        if (!snt.accessToValidImageData()) {
            noValidImgError();
            return;
        }
        warnIfMaterializedCropActive();
        resolveInput("imgFileChoice");
        final MutableModuleItem<String> imgChoiceItem = getInfo().getMutableInput("imgChoice", String.class);
        final List<String> choices = new ArrayList<>();
        choices.add(String.format("%s [C%dT%d]", IMG_TRACED_CHOICE, snt.getChannel(), snt.getFrame()));
        choices.add("Secondary image layer");
        imgChoiceItem.setChoices(choices);
        imgChoice = choices.getFirst();
        // In Stream mode there is no ij.gui.Roi support (no canvas to draw one on)
        if (snt.isStreamMode()) {
            final MutableModuleItem<String> strategyItem = getInfo().getMutableInput("somaStrategyChoice", String.class);
            if (strategyItem != null) {
                strategyItem.setLabel("Defined positions");
                strategyItem.setChoices(List.of(SOMA_AUTO, SOMA_BOOKMARK));
                strategyItem.setDescription("<HTML>How to determine soma/root location:<dl>" +
                                "<dt><i>" + SOMA_AUTO + "</i></dt>" +
                                "<dd>Auto-detect soma. <b>Single tree</b> rooted at detected centroid. Ignores any markers</dd>" +
                                "<dt><i>" + SOMA_BOOKMARK + "</i></dt>" +
                                "<dd>The <b>selected</b> entry in the markers table defines the soma location. <b>Single tree</b> rooted at the marker's position</dd>" +
                                "</dl>");
                if (!SOMA_AUTO.equals(somaStrategyChoice) && !SOMA_BOOKMARK.equals(somaStrategyChoice)) {
                    somaStrategyChoice = SOMA_AUTO;
                }
            }
        }
        debugMode = SNTUtils.isDebugMode();
        readPreferences();
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
        readPreferences();
    }

    protected void readPreferences() {
        somaStrategyChoice = prefService.get(getClass(), "somaStrategyChoice", somaStrategyChoice);
        roiPlaneOnly = prefService.getBoolean(getClass(), "roiPlaneOnly", roiPlaneOnly);
        backgroundThreshold = prefService.getDouble(getClass(), "backgroundThreshold", backgroundThreshold);
        scoreMapFilter = prefService.get(getClass(), "scoreMapFilter", scoreMapFilter);
        lengthThreshold = prefService.getDouble(getClass(), "lengthThreshold", lengthThreshold);
        srRatio = prefService.getDouble(getClass(), "srRatio", srRatio);
        sphereOverlapThreshold = prefService.getDouble(getClass(), "sphereOverlapThreshold", sphereOverlapThreshold);
        leafPruneEnabled = prefService.getBoolean(getClass(), "leafPruneEnabled", leafPruneEnabled);
        branchTuneMaxAngle = prefService.getDouble(getClass(), "branchTuneMaxAngle", branchTuneMaxAngle);
        tipExtensionDistance = prefService.getDouble(getClass(), "tipExtensionDistance", tipExtensionDistance);
        zigzagRemovalEnabled = prefService.getBoolean(getClass(), "zigzagRemovalEnabled", zigzagRemovalEnabled);
        overshootRemovalEnabled = prefService.getBoolean(getClass(), "overshootRemovalEnabled", overshootRemovalEnabled);
        smoothWindowSize = prefService.getInt(getClass(), "smoothWindowSize", smoothWindowSize);
        resampleStep = prefService.getDouble(getClass(), "resampleStep", resampleStep);
        connectivityChoice = prefService.get(getClass(), "connectivityChoice", connectivityChoice);
        afterTracingChoice = prefService.get(getClass(), "afterTracingChoice", afterTracingChoice);
    }

    protected void savePreferences() {
        prefService.put(getClass(), "somaStrategyChoice", somaStrategyChoice);
        prefService.put(getClass(), "roiPlaneOnly", roiPlaneOnly);
        prefService.put(getClass(), "backgroundThreshold", backgroundThreshold);
        prefService.put(getClass(), "scoreMapFilter", scoreMapFilter);
        prefService.put(getClass(), "lengthThreshold", lengthThreshold);
        prefService.put(getClass(), "srRatio", srRatio);
        prefService.put(getClass(), "sphereOverlapThreshold", sphereOverlapThreshold);
        prefService.put(getClass(), "leafPruneEnabled", leafPruneEnabled);
        prefService.put(getClass(), "branchTuneMaxAngle", branchTuneMaxAngle);
        prefService.put(getClass(), "tipExtensionDistance", tipExtensionDistance);
        prefService.put(getClass(), "zigzagRemovalEnabled", zigzagRemovalEnabled);
        prefService.put(getClass(), "overshootRemovalEnabled", overshootRemovalEnabled);
        prefService.put(getClass(), "smoothWindowSize", smoothWindowSize);
        prefService.put(getClass(), "resampleStep", resampleStep);
        prefService.put(getClass(), "connectivityChoice", connectivityChoice);
        prefService.put(getClass(), "afterTracingChoice", afterTracingChoice);
    }

    @SuppressWarnings("unused")
    protected void applyDefaults() {
        somaStrategyChoice = SOMA_AUTO;
        roiPlaneOnly = false;
        backgroundThreshold = -1.00;
        scoreMapFilter = SCORE_MAP_TUBENESS;
        lengthThreshold = 5.0;
        srRatio = 0.3;
        sphereOverlapThreshold = 0.1;
        leafPruneEnabled = false;
        branchTuneMaxAngle = 90.0;
        tipExtensionDistance = 0;
        zigzagRemovalEnabled = true;
        overshootRemovalEnabled = true;
        smoothWindowSize = 5;
        resampleStep = 2.0;
        connectivityChoice = "Medium";
        afterTracingChoice = AFTER_DO_NOTHING;
        scoreMapFilterCallback(); // keep secondaryImageSuffix visibility in sync w/ reset scoreMapFilter
        // Learned values are gone: the window listing them would show stale 'current' values
        disposeLearnDialog();
        if (learnMessage != null) learnMessage.accept(" ");
    }

    /**
     * Creates and configures a tracer from the shared GUI parameters. Returns
     * {@code null} if configuration failed (e.g., missing secondary image for
     * score map).
     *
     * @param img the image to trace
     * @return configured tracer, or null on error
     */
    protected AbstractGWDTTracer<?> createAndConfigureTracer(final ImgPlus<?> img) {
        final boolean scoreMapEnabled = !SCORE_MAP_NONE.equals(scoreMapFilter);
        final AbstractGWDTTracer<?> tracer = GWDTTracerFactory.create(img);
        tracer.setStatusListener(this::setStatus);
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
            } else if (!configureSecondaryScoreMap(tracer)) {
                return null;
            }
        }
        return tracer;
    }

    /**
     * Hook for configuring the tracer's score map from a user-supplied secondary
     * image. Invoked only when {@code scoreMapFilter == SCORE_MAP_OTHER}.
     * <p>
     * Default implementation pulls from {@link SNT#getSecondaryData()} (the
     * secondary layer currently loaded in the GUI) and aborts the command via
     * {@link #error(String)} if none is available.
     * <p>
     * Subclasses may override to source the secondary from disk (or elsewhere)
     * and may choose to handle missing/incompatible inputs gracefully by
     * disabling score mapping ({@code tracer.setScoreMapEnabled(false)}) and
     * returning {@code true} so tracing proceeds without it.
     *
     * @param tracer the tracer being configured
     * @return {@code true} to continue (score map configured, or gracefully
     *         disabled); {@code false} to abort the command
     */
    protected boolean configureSecondaryScoreMap(final AbstractGWDTTracer<?> tracer) {
        final RandomAccessibleInterval<? extends RealType<?>> secLayer = snt.getSecondaryData();
        if (secLayer == null) {
            error("No secondary image has been defined. Please create or load one first.");
            return false;
        }
        tracer.setScoreMap(secLayer);
        return true;
    }

    protected void runCommand() {
        javax.swing.SwingUtilities.invokeLater(this::disposeLearnDialog); // no longer relevant
        try {
            chosenImp = getImgFromImgChoice();
            if (chosenImp == null || abortRun) return;

            status("Running GWDT tracing...", false);

            final AbstractGWDTTracer<?> tracer = createAndConfigureTracer(chosenImp);
            if (tracer == null) return;

            // Get ROI/marker and configure strategy
            final double[] seedPhysical;
            final String errorMsg;
            detectedSoma = null;
            if (SOMA_BOOKMARK.equals(somaStrategyChoice)) {
                // Stream-mode-only: seed from the viewer's marker manager instead of a Roi
                setStatus("Detecting soma at marker...");
                detectedSoma = detectSomaAtMarker(chosenImp);
                seedPhysical = seedPhysicalFromDetectedSoma(tracer, detectedSoma);
                errorMsg = "Could not detect a soma at the marker location. Place exactly one marker " +
                        "(\"M\" key) over a bright soma in the viewer, or re-run with a different strategy.";
            } else {
                final int seedStrategy = parseRoiStrategy();
                if (seedStrategy == GWDTTracer.ROI_UNSET) {
                    // Auto-detect soma using full SomaUtils pipeline (EDT×intensity)
                    setStatus("Detecting soma...");
                    detectedSoma = detectSoma(chosenImp);
                    seedPhysical = seedPhysicalFromDetectedSoma(tracer, detectedSoma);
                    errorMsg = "Automated detection of soma failed. Please Pause SNT, draw a " +
                            "ROI around the soma, and re-run with a ROI-based strategy.";
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

            applyWorldOriginOffsetIfAny(trees);
            handleTracedTrees(trees);
            savePreferences();

        } catch (final java.util.concurrent.CancellationException ce) {
            // Expected outcome of user-requested cancellation (SNTUI abort/exit); not an error
            SNTUtils.log("GWDT tracing cancelled: " + ce.getMessage());
            resetUI();
        } catch (final Throwable ex) {
            SNTUtils.error(ex);
            error("An exception occurred. " + GuiUtils.consoleHint() + ".");
        } finally {
            setStatus(null);
        }
    }

    /**
     * Shifts each tree by {@code snt}'s current {@link SNT#getWorldOriginOffset() world origin
     * offset}, if any (a no-op when that offset is all-zero, which is the common case). Called
     * once, right after tracing produces valid trees and before {@link #handleTracedTrees}, so
     * that every subclass/override of {@code handleTracedTrees} (whether it loads trees into
     * {@code PathAndFillManager}, exports them to disk, or both - see {@link GWDTTracerFileCmd})
     * sees already-corrected coordinates.
     * <p>
     * The offset is only non-zero when {@code chosenImp}'s pixel data was wired in from a source
     * whose own coordinate frame is not anchored at world (0,0,0) (see
     * {@code BigDataLoaderCmd#applyFallbackCalibration}); leaving it uncorrected would produce
     * shape-correct but uniformly mispositioned trees.
     * <p>
     * Translating the node coordinates alone is only half of the correction interactive tracing
     * applies (see {@code SNT#syncActivePathCanvasState}): every Path's own {@code canvasOffset}
     * must be stamped with the matching (negative, pixel-space) counterpart too, or {@code
     * Path#getXUnscaledDouble()} and friends resolve to the wrong voxel index against whatever RAI
     * {@code chosenImp} was read from, even though the Path's own world coordinates display and
     * measure correctly. {@link SNT#getActiveCanvasPixelOffset()} (not the crop-independent
     * {@link SNT#getDefaultCanvasPixelOffset()}) is used because {@code chosenImp} itself comes
     * from {@link SNT#getLoadedDataAsImg} - the crop-local grid whenever a crop happens to be
     * materialized, the raw streamed source's grid otherwise (see {@code initForImage()}).
     *
     * @param trees the traced trees to correct in place
     */
    protected void applyWorldOriginOffsetIfAny(final List<Tree> trees) {
        final double[] originOffset = snt.getWorldOriginOffset();
        if (originOffset[0] != 0 || originOffset[1] != 0 || originOffset[2] != 0) {
            final PointInCanvas canvasOffset = snt.getActiveCanvasPixelOffset();
            trees.forEach(tree -> {
                tree.translate(originOffset[0], originOffset[1], originOffset[2]);
                tree.list().forEach(path -> path.setCanvasOffset(canvasOffset));
            });
        }
    }

    /**
     * Called after tracing completes with valid trees (already corrected for any world origin
     * offset - see {@link #applyWorldOriginOffsetIfAny}). The default implementation loads trees
     * into PathAndFillManager and optionally triggers proofreading. Subclasses (e.g.,
     * {@link GWDTTracerFileCmd}) may override to export trees to disk instead.
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
            pafm.addTree(tree, "GWDT");
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

    // ---- Learning parameters from example paths (image-based commands) ----

    /** Label of the "learn" button, declared by each image-based command (see {@link #learnFromSelectedPaths}) */
    protected static final String LEARN_LABEL = "Learn Parameters...";
    /** Description of the "learn" button */
    protected static final String LEARN_DESCRIPTION = "<HTML>Derive tracing parameters from example paths: those currently<br>"
            + "selected in Path Manager, or reconstruction files (SWC, TRACES, etc.)<br>"
            + "dropped onto the window that opens. Radii, intensity, and branch<br>"
            + "geometry are sampled to auto-fill settings above.";

    private AutoTraceConfigDialog configDialog;
    private Consumer<String> learnMessage; // updates the message widget of the "learn" button

    /**
     * Closes the window listing learned parameters, if open
     */
    protected void disposeLearnDialog() {
        if (configDialog != null) configDialog.dispose();
        learnImg = null;
        learnImgFile = null;
    }

    // Updates the widgets of the prompt to reflect the current value of the fields of this command. NB: SciJava only
    // does so after the callback of a widget of the prompt. Neither the input panel (it is not a component) nor any
    // other means of refreshing it are reachable from here, so a widget callback is triggered by flipping the 'debug
    // mode' checkbox and restoring it right away (same EDT event: it is never painted in the flipped state). The
    // callback of this widget only sets SNTUtils' debug mode
    private void refreshPrompt() {
        final JDialog prompt = findPrompt();
        final javax.swing.JCheckBox box = (prompt == null) ? null
                : findCheckBox(prompt.getContentPane(), "verbose logging");
        if (box == null) return;
        final boolean value = box.isSelected();
        box.setSelected(!value);
        box.setSelected(value);
    }

    // Finds the checkbox whose tooltip contains the text
    private static javax.swing.JCheckBox findCheckBox(final java.awt.Component c, final String tooltip) {
        if (c instanceof javax.swing.JCheckBox box && box.getToolTipText() != null
                && box.getToolTipText().contains(tooltip)) return box;
        if (c instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                final javax.swing.JCheckBox box = findCheckBox(child, tooltip);
                if (box != null) return box;
            }
        }
        return null;
    }

    // The visible prompt of this command, or null (NB: Window titles are not unique in general, but are for prompts)
    private JDialog findPrompt() {
        final String title = getInfo().getLabel();
        for (final Window w : Window.getWindows()) {
            if (w instanceof JDialog d && d.isVisible() && d.getTitle() != null && d.getTitle().equals(title)) return d;
        }
        return null;
    }

    /**
     * Opens a window listing tracing parameters learned from example paths, from which the user can cherry-pick the
     * ones to apply to the prompt. If paths are selected in the Path Manager, they are used as the initial examples;
     * otherwise the window waits for reconstruction files to be dropped onto it or chosen with its file chooser. The
     * heavy work (loading, fitting radii, sampling intensities) runs off the EDT. To be called from the callback of
     * the "learn" button of image-based commands, which declare it together with a message widget
     *
     * @param message receives the text to display in the message widget of the prompt
     */
    protected void learnFromSelectedPaths(final Consumer<String> widgetMessage) {
        // Widgets of the prompt only refresh after a widget callback, but messages and values are also set by the
        // learning window and by background threads: refresh the prompt explicitly
        final Consumer<String> message = msg -> {
            widgetMessage.accept(msg);
            javax.swing.SwingUtilities.invokeLater(this::refreshPrompt);
        };
        learnMessage = message;
        disposeLearnDialog();
        final AutoTraceConfigDialog.Learner learner = new AutoTraceConfigDialog.Learner() {
            @Override
            public void learnFromFiles(final List<File> files, final Consumer<AutoTraceConfig> onDone,
                                       final Consumer<String> onError) {
                learn(null, files, message, onDone, onError);
            }

            @Override
            public void learnFromSelection(final Consumer<AutoTraceConfig> onDone, final Consumer<String> onError) {
                final Collection<Path> selected = sntService.getSelectedPaths();
                if (selected == null || selected.isEmpty()) {
                    onError.accept("No paths selected in Path Manager.");
                    return;
                }
                learn(new ArrayList<>(selected), null, message, onDone, onError);
            }

            @Override
            public void learnFromAll(final Consumer<AutoTraceConfig> onDone, final Consumer<String> onError) {
                final List<Path> all = sntService.getPaths();
                if (all == null || all.isEmpty()) {
                    onError.accept("Path Manager is empty.");
                    return;
                }
                learn(new ArrayList<>(all), null, message, onDone, onError);
            }
        };
        message.accept(" ");
        configDialog = new AutoTraceConfigDialog(findPrompt(), null, currentLearnableValues(), learner);
        configDialog.setApplyCallback(cfg -> applySelectedConfig(configDialog, cfg, message));
        configDialog.setVisible(true);
        final Collection<Path> selected = sntService.getSelectedPaths();
        if (selected != null && !selected.isEmpty()) configDialog.learnFromSelection();
    }

    // Examples beyond this number (longest first) are ignored: fitting radii to many paths is slow, and even
    // more so if the image is a lazily loaded stream
    private static final int MAX_EXAMPLE_PATHS = 200;
    private static final int MAX_EXAMPLE_PATHS_STREAMED = 30;

    // Image of file-based commands, kept while the learning window is open: loading it can be slow
    private ImgPlus<?> learnImg;
    private File learnImgFile;
    private long learnImgStamp;

    private ImgPlus<?> learnImage() {
        if (isFileMode()) {
            final File f = imgFileChoice;
            if (learnImg != null && f != null && f.equals(learnImgFile) && f.lastModified() == learnImgStamp)
                return learnImg;
            learnImg = getImgFromImgChoice();
            learnImgFile = f;
            learnImgStamp = (f == null) ? 0 : f.lastModified();
            return learnImg;
        }
        return getImgFromImgChoice();
    }

    private static List<Path> loadPaths(final List<File> files) {
        final List<Path> paths = new ArrayList<>();
        for (final File f : files) {
            for (final Tree tree : Tree.listFromFile(f.getAbsolutePath())) paths.addAll(tree.list());
        }
        return paths;
    }

    /**
     * Derives a configuration from either example paths or reconstruction files, reporting back on the EDT
     *
     * @param examples the example paths (null if files are specified)
     * @param files    the reconstruction files (ignored if examples are specified)
     */
    private void learn(final List<Path> examples, final List<File> files, final Consumer<String> message,
                       final Consumer<AutoTraceConfig> onDone, final Consumer<String> onError) {
        final ImgPlus<?> chosenImp;
        try {
            chosenImp = learnImage();
        } catch (final Exception ex) {
            learnFailed("Image could not be loaded: " + ex.getMessage(), message, onError);
            return;
        }
        if (chosenImp == null) {
            learnFailed("No valid image available.", message, onError);
            return;
        }
        @SuppressWarnings("unchecked") final RandomAccessibleInterval<? extends RealType<?>> source =
                (RandomAccessibleInterval<? extends RealType<?>>) chosenImp;
        final double[] spacing = ImgUtils.getSpacing(chosenImp);
        final boolean fromFiles = examples == null;

        // Paths are in true-world coordinates, but the image is not necessarily anchored at world origin: it may be
        // a materialized crop, or a stream with a world origin offset (same convention as in CostPalette)
        final double[] voxelOffset = new double[3];
        boolean streamedSource = false;
        if (!isFileMode() && snt != null) {
            final PointInCanvas o = snt.getActiveCanvasPixelOffset();
            if (o != null) {
                voxelOffset[0] = o.x;
                voxelOffset[1] = o.y;
                voxelOffset[2] = o.z;
            }
            streamedSource = snt.isStreamMode() && !snt.isMaterializedCrop();
        }
        final boolean streamed = streamedSource;
        final int maxPaths = streamed ? MAX_EXAMPLE_PATHS_STREAMED : MAX_EXAMPLE_PATHS;

        // Loading, fitting radii and sampling intensities can take a while (many paths, lazy data): do it off the
        // EDT, so that the prompt does not freeze, and show progress
        final JDialog prompt = findPrompt();
        final Cursor previousCursor = (prompt == null) ? null : prompt.getCursor();
        if (prompt != null) prompt.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        message.accept(" ");
        status("Learning parameters from example paths...", false);
        new SwingWorker<AutoTraceConfig, Void>() {
            private int nPaths;

            @Override
            protected AutoTraceConfig doInBackground() {
                List<Path> paths = fromFiles ? loadPaths(files) : examples;
                if (paths.isEmpty()) throw new IllegalArgumentException("No paths could be read from the file(s).");
                final double inside = AutoTraceConfig.fractionInBounds(paths, source, spacing, voxelOffset);
                if (fromFiles && (Double.isNaN(inside) || inside < 0.5)) {
                    throw new IllegalArgumentException(String.format("Only %.0f%% of the nodes fall within the image: "
                            + "the reconstruction(s) do not seem to match it.", 100 * (Double.isNaN(inside) ? 0 : inside)));
                }
                final int total = paths.size();
                if (total > maxPaths) {
                    paths = new ArrayList<>(paths);
                    paths.sort((p1, p2) -> Double.compare(p2.getLength(), p1.getLength()));
                    paths = paths.subList(0, maxPaths);
                }
                nPaths = paths.size();
                final AutoTraceConfig config = AutoTraceConfig.fromPaths(paths, source, spacing, voxelOffset, streamed);
                if (!Double.isNaN(inside) && inside < 0.95) {
                    config.addWarning(String.format("Only %.0f%% of the example nodes fall within the image: values "
                            + "sampled from it may be unreliable.", 100 * inside));
                }
                if (total > nPaths) {
                    config.addWarning("Using the " + nPaths + " longest of " + total + " example paths"
                            + (streamed ? " (the image is streamed: reading it is slow)." : "."));
                }
                return config;
            }

            @Override
            protected void done() {
                try {
                    final AutoTraceConfig config = get();
                    message.accept("<HTML><font color='green'>Learned from " + nPaths
                            + " path(s). Review the configuration window.</font>");
                    status("Learned parameters from " + nPaths + " path(s)", true);
                    SNTUtils.log("AutoTraceConfig derived:\n" + config.getSummary());
                    onDone.accept(config);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    onError.accept("Interrupted.");
                } catch (final Exception ex) {
                    final Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    status(null, true);
                    learnFailed("Parameters could not be learned: " + cause.getMessage(), message, onError);
                } finally {
                    if (prompt != null) prompt.setCursor(previousCursor);
                }
            }
        }.execute();
    }

    private void learnFailed(final String msg, final Consumer<String> message, final Consumer<String> onError) {
        message.accept("<HTML><font color='red'>" + msg + "</font>");
        SNTUtils.log("AutoTraceConfig error: " + msg);
        onError.accept(msg);
    }

    private void applySelectedConfig(final AutoTraceConfigDialog dialog, final AutoTraceConfig config,
                                     final Consumer<String> message) {
        if (dialog.isSelected("backgroundThreshold") && !Double.isNaN(config.getBackgroundThreshold())) {
            backgroundThreshold = config.getBackgroundThreshold();
        }
        if (dialog.isSelected("lengthThreshold") && !Double.isNaN(config.getMinBranchIntensityLength())) {
            lengthThreshold = config.getMinBranchIntensityLength();
        }
        if (dialog.isSelected("branchTuneMaxAngle") && !Double.isNaN(config.getBranchTuneMaxAngle())) {
            branchTuneMaxAngle = config.getBranchTuneMaxAngle();
        }
        if (dialog.isSelected("scoreMapEnabled") && config.getScoreMapScales() != null) {
            // Only enable the filter if it is off: do not replace the type (or secondary layer) already in use
            if (SCORE_MAP_NONE.equals(scoreMapFilter)) scoreMapFilter = SCORE_MAP_TUBENESS;
        }
        if (dialog.isSelected("reconnectMaxBridgeDist") && !Double.isNaN(config.getReconnectMaxBridgeDist())) {
            tipExtensionDistance = config.getReconnectMaxBridgeDist();
        }
        if (dialog.isSelected("sphereOverlapThreshold") && !Double.isNaN(config.getSphereOverlapThreshold())) {
            sphereOverlapThreshold = config.getSphereOverlapThreshold();
        }
        if (dialog.isSelected("resampleStep") && !Double.isNaN(config.getResampleStep())) {
            resampleStep = config.getResampleStep();
        }
        message.accept("<HTML><font color='green'>Applied selected parameters.</font>");
        SNTUtils.log("AutoTraceConfig: applied selected parameters to dialog");
    }

    // Current value of the parameters that a learned configuration can set, formatted as in the dialog
    private Map<String, String> currentLearnableValues() {
        final Map<String, String> current = new LinkedHashMap<>();
        current.put("backgroundThreshold", (backgroundThreshold < 0) ? "Auto"
                : SNTUtils.formatDouble(backgroundThreshold, 2));
        current.put("lengthThreshold", SNTUtils.formatDouble(lengthThreshold, 2));
        current.put("branchTuneMaxAngle", (branchTuneMaxAngle < 0) ? "Disabled"
                : SNTUtils.formatDouble(branchTuneMaxAngle, 2) + "°");
        current.put("scoreMapEnabled", switch (scoreMapFilter) {
            case SCORE_MAP_NONE -> "None";
            case SCORE_MAP_TUBENESS -> "Tubeness";
            case SCORE_MAP_FRANGI -> "Frangi";
            default -> "Secondary layer";
        });
        current.put("sphereOverlapThreshold", SNTUtils.formatDouble(sphereOverlapThreshold, 2));
        current.put("resampleStep", (resampleStep <= 0) ? "0 (disabled)"
                : SNTUtils.formatDouble(resampleStep, 2) + " voxels");
        current.put("reconnectMaxBridgeDist", (tipExtensionDistance <= 0) ? "0 (disabled)"
                : SNTUtils.formatDouble(tipExtensionDistance, 2) + " voxels");
        return current;
    }


    protected abstract boolean isFileMode();

    protected ImgPlus<?> getImgFromImgChoice() {
        if (isCanceled() || abortRun) return null; // honor init()'s cancel
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
            final boolean secLayer = imgChoice != null && !imgChoice.startsWith(IMG_TRACED_CHOICE);
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
            case SOMA_ROI_EDGE -> GWDTTracer.ROI_EDGE;
            case SOMA_ROI_CENTROID -> GWDTTracer.ROI_CENTROID;
            case SOMA_ROI_CENTROID_WEIGHTED -> GWDTTracer.ROI_CENTROID_WEIGHTED;
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
     * radius, and mask, or {@code null} if detection fails.
     */
    private SomaUtils.SomaResult detectSoma(final ImgPlus<?> img) {
        return SomaUtils.detectSoma(img);
    }

    /**
     * Characterizes the soma at the single marker placed in the tethered BVV/BDV viewer (see
     * {@link #getPixelPositionsOfBookmarks(boolean)}). Requires exactly one marker; returns {@code null}
     * (rather than guessing, e.g. "use the first one") if zero or more than one are present, so
     * the caller's generic "could not detect a soma" error message stays accurate.
     *
     * @param img the image being traced
     * @return the detected soma, or {@code null} if there isn't exactly one marker, or
     *         detection at that marker's location failed (e.g. it sits in the background)
     */
    private SomaUtils.SomaResult detectSomaAtMarker(final ImgPlus<?> img) {
        final List<long[]> seeds = getPixelPositionsOfBookmarks(true);
        if (seeds.size() != 1) return null;
        final long[] seed = seeds.getFirst();
        final int zSlice = (seed.length > 2 && seed[2] >= 0) ? (int) seed[2] : -1;
        return SomaUtils.detectSomaAt(img, seed[0], seed[1], -1, zSlice);
    }

    /**
     * Common tail shared by the auto-detection and marker-seeded strategies: given a
     * (possibly-null) detected soma, configures {@code tracer}'s soma Roi/contour (when a contour
     * was found) and converts the soma's pixel center to a physical seed point.
     *
     * @param tracer   the tracer being configured
     * @param detected the detected soma, or {@code null} if detection failed
     * @return the physical seed point, or {@code null} if {@code detected} is {@code null}
     */
    private double[] seedPhysicalFromDetectedSoma(final AbstractGWDTTracer<?> tracer,
                                                    final SomaUtils.SomaResult detected) {
        if (detected == null) return null;
        // Use the detected contour as a soma ROI for rooting.
        // ROI_AUTO_EDGE/ROI_MARKER: one tree per neurite; ROI_UNSET: single tree at centroid
        if (detected.hasContour()) {
            final int autoRoiStrategy = (SOMA_ROI_AUTO_EDGE.equals(somaStrategyChoice) || SOMA_BOOKMARK.equals(somaStrategyChoice))
                    ? GWDTTracer.ROI_EDGE : GWDTTracer.ROI_CENTROID;
            final Roi somaRoi = detected.createContourRoi();
            if (detected.zSlice() >= 0) somaRoi.setPosition(detected.zSlice() + 1);
            tracer.setSomaRoi(somaRoi, autoRoiStrategy);
            tracer.setSomaRoiZPosition(detected.zSlice());
        }
        // Convert center to physical coordinates for seed
        final double[] spacing = ImgUtils.getSpacing(chosenImp);
        final double[] seedPhysical = new double[spacing.length];
        final long[] center = detected.center();
        for (int d = 0; d < Math.min(center.length, spacing.length); d++) {
            seedPhysical[d] = center[d] * spacing[d];
        }
        // Handle Z for 3D images where center is 2D (from a slice)
        if (spacing.length > 2 && center.length <= 2 && detected.zSlice() >= 0) {
            seedPhysical[2] = detected.zSlice() * spacing[2];
        }
        return seedPhysical;
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
        setStatus(null);
        abortRun = true;
    }

    @Override
    protected void error(final String msg) {
        getInputs().keySet().forEach(this::resolveInput);
        abortRun = true;
        super.error(msg);
        setStatus(null);
    }
}
