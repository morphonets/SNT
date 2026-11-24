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
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.ImgPlus;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.module.ModuleService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.BookmarkManager;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.SomaPalette;
import sc.fiji.snt.gui.TipPalette;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.BigDataUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.AbstractBigViewer;
import net.imglib2.realtransform.AffineTransform3D;
import sc.fiji.snt.seed.SeedConfidence;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Command to automatically detect the soma (cell body) in neuronal images.
 * <p>
 * Uses a combined EDT-intensity approach to find the thickest, brightest
 * structure in the image, which typically corresponds to the soma.
 * </p>
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Detect Soma(s)...", initializer = "init")
public class SomaDetectorCmd extends CommonDynamicCmd implements Interactive {

    /** Traditional-mode outputs */
    private static final String OUTPUT_POINT_ROI = "Point ROI";
    private static final String OUTPUT_AREA_ROI = "Area ROI";
    private static final String OUTPUT_CIRCLE_ROI = "Circular ROI";

    /** outputs common to both traditional and stream modes */
    private static final String OUTPUT_PATH = "Single-node path";
    private static final String OUTPUT_BOOKMARK = "Bookmark/marker";
    /**
     * Adds a {@link SeedPoint} to {@link sc.fiji.snt.SeedManager}'s {@link SeedOverlay} instead of any ROI/path/bookmark.
     */
    public static final String OUTPUT_SEEDS = "Seed point";

    public static final String SCOPE_ALL = "All somata in image";
    private static final String SCOPE_BRIGHTEST = "Brightest/largest soma only";

    @Parameter(label = "Scope", choices = {SCOPE_BRIGHTEST, SCOPE_ALL},
            style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
            description = "<HTML>Detection scope:<br>" +
                    "<b>All somata</b>: Detect all cell bodies in image<br>" +
                    "<b>Brightest only</b>: Detect single brightest/largest soma")
    private String scopeChoice;

    @Parameter(label = "Threshold", min = "-1", required = false,
            description = "<HTML>Intensity threshold for soma detection.<br>" +
                    "-1 = auto (Otsu's method)")
    private double threshold = -1;

    /** Depth choices. Stream-mode-only: classic mode always has {@code imp.getZ()} to fall back on. */
    private static final String ZPOS_AUTO = "Auto-detect";
    private static final String ZPOS_SPECIFIED = "Use depth specified below:";

    @Parameter(label = "Depth", callback = "zPosChoiceChanged", required = false,
            choices = { ZPOS_AUTO, ZPOS_SPECIFIED },
            description = "<HTML>Z-depth (in <b>spatially calibrated</b> units) at which detection runs." +
                    "<dl>" +
                    "<dt><i>" + ZPOS_AUTO + "</i></dt>" +
                    "<dd>Middle slice (<b>" + SCOPE_BRIGHTEST + "</b>), or a max-intensity projection<br>" +
                    "across the whole volume (<b>" + SCOPE_ALL + "</b>) - can be slow for streamed data</dd>" +
                    "<dt><i>" + ZPOS_SPECIFIED + "</i></dt>" +
                    "<dd>Detection runs only at the depth specified below</dd>" +
                    "</dl>")
    private String zPosChoice = ZPOS_AUTO;

    @Parameter(label = "<html>&nbsp;", callback = "zPosChanged", stepSize = "0.1", style = "format:#.00", required = false)
    private double zPos = 0;

    /** Plane choices. Classic-mode-only: Stream mode uses the depth options above */
    private static final String PLANE_MIP = "Max projection";
    private static final String PLANE_CURRENT = "Current slice";

    @Parameter(label = "Plane", choices = { PLANE_MIP, PLANE_CURRENT }, required = false,
            description = "<HTML>Plane that detection runs on (3D images):<br>" +
                    "<b>" + PLANE_MIP + "</b>: Detects somata at any depth<br>" +
                    "<b>" + PLANE_CURRENT + "</b>: Detects on the displayed slice only<br>" +
                    "Only applies to <b>" + SCOPE_ALL + "</b>: single soma detection uses the current slice")
    private String planeChoice = PLANE_MIP;

    // Stream mode only. Choices are those of the data being streamed (see init())
    @Parameter(label = "Resolution level", required = false, choices = {"Level 0"},
            description = "<HTML>Resolution level (Stream mode) that is read, both by the preview and by detection.<br>" +
                    "Somata rarely need full resolution: coarser levels are much faster to read<br>" +
                    "and process, and the detections are placed in the full resolution space.<br>" +
                    "Parameters are in calibrated units, and independent of the level")
    private String levelChoice = "Level 0";

    /**
     * Becomes {@code true} the first time the user edits {@link #zPos} directly (see  {@link #zPosChanged()}).
     * Distinct from {@link #zPos}'s own value because a world Z-coordinate has no value that can double as "unset"
     * sentinel (negative depths are common and legitimate). This flag is what lets {@link #zPosChoiceChanged()} tell
     * "never touched, needs a default" apart from "user specified a value, even a negative one".
     */
    private boolean zPosUserSet;

    @Parameter(label = "Output type",
            choices = { OUTPUT_BOOKMARK, OUTPUT_PATH, OUTPUT_SEEDS, OUTPUT_AREA_ROI, OUTPUT_CIRCLE_ROI, OUTPUT_POINT_ROI },
            description = "<HTML>Type of output:<br>" +
                    "<b>Bookmark/marker</b>: Bookmarked position at soma center w/ size from distance transform<br>" +
                    "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>" +
                    "<b>Seed point</b>: Seed Manager entry at soma center w/ radius from distance transform<br>" +
                    "<b>Area ROI</b>: Contour from thresholding + wand selection<br>" +
                    "<b>Circular ROI</b>: Circle w/ radius from distance transform<br>" +
                    "<b>Point ROI</b>: Single point at soma center<br>")
    private String outputChoice;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER = "<HTML><b>Multi-soma Detection:";

    @Parameter(label = "Min. radius", min = "0", required = false,
            description = "<HTML>Minimum soma radius in <b>spatially calibrated</b> units.<br>" +
                    "Only applies when detecting <b>" + SCOPE_ALL +"</b>.<br>" +
                    "Smaller detections are filtered out as noise.<br>" +
                    "0 = no filtering (default)")
    private double minRadius = 0;

    @Parameter(label = "Min. inter-soma distance", min = "0", required = false,
            description = "<HTML>Minimum distance between soma centers in <b>spatially calibrated</b> units.<br>" +
                    "Non-maximum suppression removes detections that are<br>" +
                    "too close together, keeping only the strongest.<br>" +
                    "Only applies when detecting <b>" + SCOPE_ALL + "</b>.<br>" +
                    "0 = auto (2x Min. radius, when set) otherwise no filtering")
    private double minSomaDistance = 0;

    @Parameter(label = "Expected no. of somata", min = "0", required = false,
            description = "<HTML><b>Experimental</b>: Expected number of cell bodies.<br>" +
                    "When &gt; 0, keeps only the top-N detections ranked by<br>" +
                    "EDT thickness. May not work well for images with large<br>" +
                    "connected bright regions. <i>Min. inter-soma distance</i><br>" +
                    "is typically more reliable.<br>" +
                    "Only applies when detecting <b>" + SCOPE_ALL + "</b>.<br>" +
                    "0 = no count-based filtering (default)")
    private int nSomas = 0;

    @Parameter(label = "Learn Parameters...", callback = "tuneCallback",
            description = "<HTML>Previews multi-soma detection on a single plane, to adjust<br>" +
                    "<i>Threshold</i>, <i>Min. radius</i>, <i>Min. inter-soma distance</i> interactively")
    private Button tuneButton;

    @Parameter(label = "Percentile clip (%)", min = "0", max = "45", required = false,
            description = "<HTML>Robustness clip for the per-soma confidence assigned when exporting as <b>" +
                    OUTPUT_SEEDS + "</b>.<br>" +
                    "Each detection's integrated density of fluorescence (summed intensity within its<br>" +
                    "mask) is linearly mapped so the <i>N</i>th/(100-<i>N</i>)th percentiles of density<br>" +
                    "across the current batch land on confidence <i>N</i>/100 and 1-<i>N</i>/100; detections<br>" +
                    "beyond those percentiles extrapolate past that sub-range but are curbed (clamped) to<br>" +
                    "[0,1]. This minimizes the contribution of dim/bright outliers.<br>" +
                    "Only meaningful when detecting <b>" + SCOPE_ALL + "</b> with more than one soma<br>")
    private double percentileClip = 10.0;

    @Parameter(label = "     Run     ", callback = "detectCallback", description = "<HTML>Runs soma detection")
    private Button detectButton;

    private ImagePlus imp;
    private ImgPlus<?> img;
    // Title of the prompt: must be unique for getPrompt() to work
    private String promptTitle;
    private SomaPalette wizard;
    // Stream mode: levels of the data (labels as in the choices of levelChoice), and the level detection runs on
    private List<BigDataUtils.LevelInfo> levels;
    private List<String> levelLabels;
    // Detection at a coarser level (null: full resolution): spacing of the level, and the shift of its grid
    private double[] levelSpacing;
    private double[] levelShift;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        imp = snt.getImagePlus();
        img = snt.getLoadedDataAsImg(false);
        if (imp == null && img == null) {
            error("No valid image data available.");
            return;
        }
        warnIfMaterializedCropActive();
        if (snt != null && snt.isStreamMode()) {
            // Preserve an outputChoice already resolved by a caller (e.g. SeedManager's "Create Seeds from Somas..."
            // pre-resolving outputChoice=OUTPUT_SEEDS via CommandService#run); only fall back to  OUTPUT_BOOKMARK when
            // nothing valid for Stream mode was supplied
            final List<String> streamModeOutputs = List.of(OUTPUT_BOOKMARK, OUTPUT_PATH, OUTPUT_SEEDS);
            if (outputChoice == null || !streamModeOutputs.contains(outputChoice)) {
                outputChoice = OUTPUT_BOOKMARK;
            }
            final MutableModuleItem<String> outputItem = getInfo().getMutableInput("outputChoice", String.class);
            if (outputItem != null) {
                outputItem.setChoices(streamModeOutputs);
                outputItem.setDescription("<HTML>Type of output:<br>" +
                        "<b>Bookmark/marker</b>: Bookmarked position at soma center w/ size from distance transform<br>" +
                        "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>" +
                        "<b>Seed point</b>: Seed Manager entry at soma center w/ radius from distance transform<br>");
            }
        } else {
            // Classic mode always has imp.getZ() to fall back on: the Depth override is moot there
            resolveInput("zPosChoice");
            resolveInput("zPos");
        }
        initLevelChoice();
        // The plane only matters for 3D data, and Stream mode has its own depth options
        if (snt.isStreamMode() || img == null || img.numDimensions() < 3) resolveInput("planeChoice");
        promptTitle = String.format("Detect Soma(s) [C=%d;T=%d%s]...", snt.getChannel(), snt.getFrame(), zLabelSuffix());
        getInfo().setLabel(promptTitle);
        resolveInput("percentileClip"); // simplify prompt for now. Adopt P10 default
        percentileClip = 10d;
    }

    // Offers the resolution levels of the streamed data (unless a materialized crop, which is full resolution, is active)
    private void initLevelChoice() {
        levels = null;
        if (snt.isStreamMode() && !snt.isMaterializedCrop() && ui != null) {
            final AbstractBigViewer viewer = ui.getActiveBigViewer();
            final List<BigDataUtils.LevelInfo> all = (viewer == null) ? List.of()
                    : viewer.getSourceLevels(snt.getChannel() - 1);
            if (all.size() > 1) {
                levels = all;
                final int rec = SomaPalette.recommendedLevel(all);
                levelLabels = new ArrayList<>();
                for (int i = 0; i < all.size(); i++)
                    levelLabels.add(TipPalette.describeLevel(all.get(i)) + ((i == rec) ? " (recommended)" : ""));
                final MutableModuleItem<String> item = getInfo().getMutableInput("levelChoice", String.class);
                item.setChoices(levelLabels);
                if (levelChoice == null || !levelLabels.contains(levelChoice)) levelChoice = levelLabels.get(rec);
                return;
            }
        }
        resolveInput("levelChoice");
    }

    // The level (0: full resolution) chosen in the prompt, or -1 if there is no choice to make
    private int selectedLevel() {
        return (levels == null || levelLabels == null) ? -1 : Math.max(0, levelLabels.indexOf(levelChoice));
    }

    private String zLabelSuffix() {
        if (imp != null) {
            return (imp.getNSlices() > 1) ? String.format(";Z=%d", imp.getZ()) : "";
        }
        if (img != null && img.numDimensions() > 2) {
            return ";Z=auto";
        }
        return "";
    }

    @SuppressWarnings("unused")
    /* Callback for zPosChoice */
    private void zPosChoiceChanged() {
        // zPos is simply unused while Auto-detect is active: no need to reset it. Only fill in a
        // default the first time the user switches to "specified", so a value they already typed
        // (including a negative one) survives toggling back and forth
        if (ZPOS_SPECIFIED.equals(zPosChoice) && !zPosUserSet) {
            zPos = defaultZPos();
        }
    }

    @SuppressWarnings("unused")
    /* Callback for zPos */
    private void zPosChanged() {
        zPosUserSet = true;
        zPosChoice = ZPOS_SPECIFIED;
    }

    /**
     * Sensible starting depth (world/calibrated Z) to pre-fill {@link #zPos} with when the user
     * switches to {@value #ZPOS_SPECIFIED}: the center of the viewer's active slab if one is set
     * (see {@link AbstractBigViewer.PathRenderingOptions#isSlabActive()}), otherwise the world Z
     * currently in focus at the center of the viewer's camera (inverting
     * {@link AbstractBigViewer#getViewerTransform()}). Returns 0 if there is no active Stream-mode
     * viewer to query.
     */
    private double defaultZPos() {
        final AbstractBigViewer viewer = (ui == null) ? null : ui.getActiveBigViewer();
        if (viewer == null) return 0;
        final AbstractBigViewer.PathRenderingOptions opts = viewer.getRenderingOptions();
        if (opts.isSlabActive()) {
            return (opts.getSlabZMin() + opts.getSlabZMax()) / 2.0;
        }
        final AffineTransform3D inverse = viewer.getViewerTransform().inverse();
        final double[] world = new double[3];
        inverse.apply(new double[]{viewer.getViewerWidth() / 2.0, viewer.getViewerHeight() / 2.0, 0}, world);
        return world[2];
    }

    private JDialog getPrompt() {
        if (promptTitle == null) return null; // init() aborted
        return getPromptWithCloseHandler(promptTitle, () -> {
            if (wizard != null) wizard.dismiss();
        });
    }

    @Override
    public void run() {
        // Called on widget changes: do nothing. Detection is triggered by detectCallback()
        getPrompt(); // ensure the close handler is attached early
    }

    @SuppressWarnings("unused")
    /* Callback for detectButton */
    private void detectCallback() {
        // The prompt is non-modal: the framework stores the inputs before the user edits them
        try {
            context().getService(ModuleService.class).saveInputs(this);
        } catch (final RuntimeException ex) {
            SNTUtils.log("SomaDetectorCmd: could not persist inputs: " + ex.getMessage());
        }
        if (wizard != null) wizard.dismiss();
        final JDialog prompt = getPrompt();
        if (prompt != null) prompt.dispose();
        final SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                runDetection();
                return null;
            }

            @Override
            protected void done() {
                // Unregister regardless of outcome, so that a stale reference is never canceled later
                if (ui != null) ui.registerActiveCmdWorker(null);
            }
        };
        // Registered so that SNTUI can cooperatively cancel the worker, even though the prompt has closed
        if (ui != null) ui.registerActiveCmdWorker(worker);
        worker.execute();
    }

    @SuppressWarnings("unused")
    /* Callback for tuneButton */
    private void tuneCallback() {
        if (wizard != null) {
            wizard.focus();
            return;
        }
        if (!SCOPE_ALL.equals(scopeChoice)) {
            msg("The wizard previews multi-soma detection. Set <i>Scope</i> to <i>" + SCOPE_ALL + "</i> first.",
                    "Scope Not Applicable");
            return;
        }
        // The wizard starts on the plane of the prompt, and goes back to the displayed slice if MIP is unchecked
        final SomaPalette w = new SomaPalette(snt, new SomaPalette.Params(threshold, minRadius, minSomaDistance,
                resolveZSlice()), (imp == null) ? -1 : imp.getZ() - 1, selectedLevel());
        w.setParent(getPrompt());
        final SomaPalette.Params[] applied = new SomaPalette.Params[1];
        // The callback waits for the wizard so that, once it returns, SciJava refreshes the widgets of the prompt
        // with the values set below. On the EDT, events are still processed while waiting
        final boolean onEdt = EventQueue.isDispatchThread();
        final SecondaryLoop loop = (onEdt) ? Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop()
                : null;
        final CountDownLatch latch = new CountDownLatch(1);
        w.addListener(new SomaPalette.Listener() {
            @Override
            public void applied(final SomaPalette.Params params) {
                applied[0] = params;
            }

            @Override
            public void dismissed() {
                // Queued, so that it cannot run before the loop is entered (we are on the EDT until then)
                if (loop != null) EventQueue.invokeLater(loop::exit);
                else latch.countDown();
            }
        });
        wizard = w;
        w.show();
        if (loop != null) {
            loop.enter();
        } else {
            try {
                latch.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        wizard = null;
        final SomaPalette.Params p = applied[0];
        if (p != null) {
            threshold = p.threshold();
            minRadius = p.minRadius();
            minSomaDistance = p.minDistance();
            applyPlane(p.zSlice());
        }
    }

    // Sets the plane of the prompt (z: 0-based slice of the full resolution grid, or -1 for a max projection)
    private void applyPlane(final int z) {
        if (snt.isStreamMode()) {
            if (z < 0) {
                zPosChoice = ZPOS_AUTO;
            } else {
                final double pixelDepth = (snt.getPixelDepth() > 0) ? snt.getPixelDepth() : 1;
                zPosChoice = ZPOS_SPECIFIED;
                zPosUserSet = true;
                zPos = z * pixelDepth + snt.getWorldOriginOffset()[2];
            }
        } else if (img != null && img.numDimensions() > 2) {
            if (z < 0) {
                planeChoice = PLANE_MIP;
            } else {
                planeChoice = PLANE_CURRENT;
                // The displayed slice is what "Current slice" means
                if (imp != null && imp.getZ() != z + 1) SwingUtilities.invokeLater(() -> imp.setZ(z + 1));
            }
        }
    }

    /**
     * Index (0-based) of the slice detection runs on, or -1 for the automatic option (see {@link SomaUtils})
     */
    private int resolveZSlice() {
        final int zSlice;
        if (imp != null) {
            final boolean mip = SCOPE_ALL.equals(scopeChoice) && PLANE_MIP.equals(planeChoice) && img != null
                    && img.numDimensions() > 2;
            zSlice = (mip) ? -1 : imp.getZ() - 1;  // Convert to 0-indexed
        } else if (!ZPOS_SPECIFIED.equals(zPosChoice)) {
            // No ImagePlus in Stream mode, so there is no "currently displayed slice" to read, and the
            // user hasn't specified a depth either: -1 defers to SomaUtils' own "automatic" fallback
            // instead of guessing: middle slice for single-soma detection (detectSoma), max-intensity
            // projection for multi-soma detection (detectAllSomas)
            zSlice = -1;
        } else {
            // User-specified depth (calibrated units): convert to a pixel slice index, correcting for
            // the world-origin offset the same way BookmarkManager#worldToPixel does
            final double pixelDepth = (snt.getPixelDepth() > 0) ? snt.getPixelDepth() : 1;
            zSlice = (int) Math.round((zPos - snt.getWorldOriginOffset()[2]) / pixelDepth);
        }
        return zSlice;
    }

    // Largest level (voxels) that is read into memory before detection: smaller ones are processed much faster
    private static final long MAX_LEVEL_VOXELS_IN_MEMORY = 64_000_000L;
    private boolean levelInMemory;

    // Stream mode: if a coarse level was chosen, detection runs on it (instead of the full resolution data)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean prepareLevel() {
        levelSpacing = null;
        levelShift = null;
        levelInMemory = false;
        final int chosen = selectedLevel();
        if (chosen <= 0) return true;
        final AbstractBigViewer viewer = (ui == null) ? null : ui.getActiveBigViewer();
        if (viewer == null) {
            error("No active BDV/BVV viewer to read the resolution level from.");
            return false;
        }
        final BigDataUtils.LevelInfo info = levels.get(chosen);
        net.imglib2.RandomAccessibleInterval rai = viewer.getSourceData(snt.getChannel() - 1, info.level());
        if (rai == null || rai.numDimensions() != 3) {
            error("The image data of the active viewer is not available at resolution level " + info.level());
            return false;
        }
        rai = net.imglib2.view.Views.zeroMin(rai);
        if (info.dims()[0] * info.dims()[1] * info.dims()[2] <= MAX_LEVEL_VOXELS_IN_MEMORY) {
            setStatus("Reading resolution level " + info.level() + "...");
            rai = ImgUtils.copyToMemory(rai);
            levelInMemory = true;
        }
        img = ImgUtils.wrapWithSpacing(rai, info.spacing(), snt.getSpacingUnits());
        levelSpacing = info.spacing();
        final double[] origin0 = levels.getFirst().origin();
        levelShift = new double[]{info.origin()[0] - origin0[0], info.origin()[1] - origin0[1],
                info.origin()[2] - origin0[2]};
        SNTUtils.log("Soma detection on resolution level " + info.level() + ": " + java.util.Arrays.toString(
                info.dims()) + " voxels");
        return true;
    }

    // Offset to be added to the (calibrated) position of detections to obtain their world position
    private double[] outputOffset() {
        final double[] offset = snt.getWorldOriginOffset();
        if (levelShift == null) return offset;
        return new double[]{offset[0] + levelShift[0], offset[1] + levelShift[1], offset[2] + levelShift[2]};
    }

    // Detections at a coarser level are on the grid of that level, which is shifted relative to the full resolution one
    private void shiftToFullResolutionGrid(final Path path) {
        if (levelShift == null) return;
        final Path.PathNode node = path.getNode(0);
        path.moveNode(0, node.x + levelShift[0], node.y + levelShift[1], node.z + levelShift[2]);
    }

    private void runDetection() {
        if (imp == null && img == null || isCanceled()) {
            resetUI();
            return;
        }
        int zSlice = resolveZSlice();
        if (!prepareLevel()) {
            resetUI();
            return;
        }
        if (levelSpacing != null && zSlice >= 0) {
            // Slice of the level that contains the slice of the full resolution grid
            final double zRatio = levels.getFirst().spacing()[2] / levelSpacing[2];
            zSlice = (int) Math.clamp(Math.round((zSlice + 0.5) * zRatio - 0.5), 0, img.dimension(2) - 1);
        }

        // detectAllSomas() with zSlice < 0 on a 3D image computes a max-intensity projection across every
        // Z-plane: on a lazily-loaded Stream-mode volume that means touching the entire dataset. A
        // user-specified depth (zSlice >= 0) bypasses this, so the warning is only needed when none was given
        if (SCOPE_ALL.equals(scopeChoice) && zSlice < 0 && snt.isStreamMode() && !levelInMemory && img != null
                && img.numDimensions() > 2 && !getConfirmationEdtSafe(
                        "Detecting all somata on a streamed volume requires computing a max-intensity "
                                + "projection across the entire dataset. Depending on dataset size and "
                                + "network/disk speed, this can take a long time. Continue?",
                        "Run on Streamed Image?")) {
            resetUI();
            return;
        }
        final double[] spacing = (levelSpacing != null) ? levelSpacing
                : new double[]{snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        if (SCOPE_ALL.equals(scopeChoice)) {
            runAllSomas(zSlice, spacing);
        } else {
            runSingleSoma(zSlice, spacing);
        }
        resetUI();
    }

    private void runSingleSoma(final int zSlice, final double[] spacing) {
        final SomaUtils.SomaResult result = SomaUtils.detectSoma(img, threshold, zSlice);
        if (result == null) {
            error("Could not detect soma. Try adjusting the threshold.");
            return;
        }
        outputSingleSomaResult(result, spacing);
    }

    private void outputSingleSomaResult(final SomaUtils.SomaResult result, final double[] spacing) {
        switch (outputChoice) {
            case OUTPUT_PATH -> {
                final Path path = result.toPath(spacing);
                shiftToFullResolutionGrid(path);
                snt.getPathAndFillManager().addPath(path);
                status("Soma added to Manager", true);
            }
            case OUTPUT_BOOKMARK -> {
                addSomaMarker(result, spacing, null);
                status("Soma added as marker", true);
                if (ui != null) ui.selectTab("Bookmarks"); // selects markers table in stream mode
            }
            case OUTPUT_SEEDS -> {
                snt.getSeedOverlay().add(toSeedPoint(result, spacing, 1.0));
                status("Soma added as seed", true);
                if (ui != null) ui.selectTab("Seeds");
            }
            case null, default -> {
                Roi roi = createOutputRoi(result);
                if (roi == null && (OUTPUT_AREA_ROI.equals(outputChoice) || OUTPUT_CIRCLE_ROI.equals(outputChoice))) {
                    roi = result.createPointRoi();
                }
                if (roi == null) {
                    error("Could not create ROI. Soma detection failed.");
                } else {
                    if (imp != null) {
                        imp.setRoi(roi);
                    } else {
                        RoiManager rm = RoiManager.getInstance2();
                        if (rm == null) rm = new RoiManager();
                        rm.addRoi(roi);
                    }
                    status("Soma ROI created", true);
                    if (roi instanceof PointRoi && !OUTPUT_POINT_ROI.equals(outputChoice))
                        error(outputChoice + " could not be created. A point ROI was created instead.");
                }
            }
        }
        SNTUtils.log(result.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runAllSomas(final int zSlice, final double[] spacing) {
        setStatus("Detecting somata....");
        // Convert calibrated distances to pixel units for detection
        final double avgXYSpacing = (spacing[0] + spacing[1]) / 2.0;
        final double minRadiusPx = (minRadius > 0) ? minRadius / avgXYSpacing : 0;
        final double minSomaDistancePx = (minSomaDistance > 0) ? minSomaDistance / avgXYSpacing : 0;
        List<SomaUtils.SomaResult> results = SomaUtils.detectAllSomas(img, threshold, zSlice, minRadiusPx, minSomaDistancePx);
        if (results.isEmpty()) {
            setStatus(null);
            error("No somata detected. Try adjusting the threshold and/or min. radius.");
            return;
        }
        // Apply count-based filtering if nSomas is set
        if (nSomas > 0 && results.size() > nSomas) {
            final net.imglib2.RandomAccessibleInterval rai;
            if (img.numDimensions() > 2) {
                final int effectiveZ = (zSlice >= 0 && zSlice < img.dimension(2))
                        ? zSlice : (int) (img.dimension(2) / 2);
                rai = net.imglib2.view.Views.hyperSlice(img, 2, effectiveZ);
            } else {
                rai = img;
            }
            results = SomaUtils.selectTopSomasByThickness(results, rai, nSomas);
            SNTUtils.log("Top-" + nSomas + " selection: " + results.size() + " soma(s) kept");
        }
        outputMultipleSomaResults(results, spacing);
        setStatus(null);
    }

    private void outputMultipleSomaResults(final List<SomaUtils.SomaResult> results, final double[] spacing) {
        final Color[] colors = SNTColor.getDistinctColorsAWT(results.size());
        int idx = 0;
        if (OUTPUT_PATH.equals(outputChoice)) {
            final List<Tree> somas = new ArrayList<>();
            for (final SomaUtils.SomaResult result : results) {
                final Path path = result.toPath(spacing);
                shiftToFullResolutionGrid(path);
                path.setColor(colors[idx++]);
                path.setName(String.format("Soma %02d", idx));
                somas.add(new Tree(List.of(path)));

            }
            snt.getPathAndFillManager().addTrees(somas);
            status(results.size() + " soma(s) added to Manager", true);
        } else if (OUTPUT_BOOKMARK.equals(outputChoice) ) {
            for (final SomaUtils.SomaResult result : results) {
                addSomaMarker(result, spacing, colors[idx++]);
            }
            if (ui != null) ui.selectTab("Bookmarks"); // selects markers table in stream mode
            status(results.size() + " soma(s) added as markers", true);
        } else if (OUTPUT_SEEDS.equals(outputChoice)) {
            final SeedOverlay overlay = snt.getSeedOverlay();
            // Per-soma confidence: robust (percentile-clipped) normalization of integrated density
            // of fluorescence (summed intensity within each soma's mask) across this batch. See
            // SeedConfidence#percentileClipNormalize and #toSeedPoint javadoc.
            final double[] densities = new double[results.size()];
            for (int i = 0; i < results.size(); i++) densities[i] = results.get(i).integratedDensity();
            final double[] confidences = SeedConfidence.percentileClipNormalize(densities, percentileClip);
            // addAll() fires SeedOverlay's listeners once, not once per soma: add() in a loop
            // let this background command thread keep mutating overlay's seed list while
            // SeedManager's EDT-deferred refresh iterated it, occasionally throwing a
            // ConcurrentModificationException (see PeripathDetectorCmd's identical fix)
            final List<SeedPoint> newSeeds = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                newSeeds.add(toSeedPoint(results.get(i), spacing, confidences[i]));
            }
            overlay.addAll(newSeeds);
            if (ui != null) ui.selectTab("Seeds");
            status(results.size() + " soma(s) added as seeds", true);
        } else if (imp != null) {
            Overlay overlay = imp.getOverlay();
            if (overlay == null) {
                overlay = new Overlay();
            }
            for (final SomaUtils.SomaResult result : results) {
                final Roi roi = createOutputRoi(result);
                if (roi != null) {
                    roi.setStrokeColor(colors[idx++]);
                    overlay.add(roi);
                }
            }
            if (overlay.size() > 0) {
                imp.setOverlay(overlay);
                imp.setHideOverlay(false);
                imp.updateAndDraw();
            }
            status(overlay.size() + " soma ROI(s) added to overlay", true);
        } else {
            // No ImagePlus (Stream mode): mirrors the equivalent fallback in runSingleSoma()
            RoiManager rm = RoiManager.getInstance2();
            if (rm == null) rm = new RoiManager();
            int added = 0;
            for (final SomaUtils.SomaResult result : results) {
                final Roi roi = createOutputRoi(result);
                if (roi != null) {
                    roi.setStrokeColor(colors[idx++]);
                    rm.addRoi(roi);
                    added++;
                }
            }
            status(added + " soma ROI(s) added to ROI Manager", true);
        }
        SNTUtils.log("Detected " + results.size() + " soma(s)");
        for (final SomaUtils.SomaResult result : results) {
            SNTUtils.log("  " + result.toString());
        }
    }

    /**
     * Converts {@code result} into a {@link SeedPoint} at its centroid, in world/physical coordinates.
     * Mirrors {@link #addSomaMarker(SomaUtils.SomaResult, double[], Color)}'s Stream-mode handling: {@code
     * result.toNode(spacing)} gives a physical center relative to the loaded data's own raw grid, so the
     * world-origin offset (see {@code SNT#getWorldOriginOffset()}) must be added back in Stream mode for
     * the seed to land at the correct world position (a {@link SeedOverlay} always stores true-world
     * coordinates, same convention as {@link sc.fiji.snt.seed.SeedRois}/{@code ImportSeedPointsCmd}).
     *
     * @param result     the detected soma
     * @param spacing    the image's [x, y, z] spacing
     * @param confidence confidence to assign the seed, in {@code [0, 1]}. {@link #outputMultipleSomaResults}
     *                   derives this by percentile-clipped-normalizing {@link SomaUtils.SomaResult#integratedDensity()}
     *                   across the current batch (clip width set by {@link #percentileClip}); a lone
     *                   detection (see {@link #outputSingleSomaResult}) has nothing to normalize against
     *                   and passes {@code 1.0}, same as a user-placed/imported seed with no stated confidence.
     */
    private SeedPoint toSeedPoint(final SomaUtils.SomaResult result, final double[] spacing, final double confidence) {
        final Path.PathNode centroid = result.toNode(spacing);
        double x = centroid.x;
        double y = centroid.y;
        double z = centroid.z;
        if (snt.isStreamMode()) {
            final double[] offset = outputOffset();
            x += offset[0];
            y += offset[1];
            z += offset[2];
        }
        return new SeedPoint(x, y, z, confidence, centroid.radius, snt.getChannel(), snt.getFrame(), "soma",
                "soma-detector");
    }

    /**
     * Adds a marker for {@code result} to whichever {@link BookmarkManager} is relevant for the
     * current session: the tethered viewer's marker manager (Stream mode) or {@code
     * ui.getBookmarkManager()} (classic mode). Handles the coordinate-space difference between
     * the two internally, so callers don't need to.
     * <p>
     * {@code result.toNode(spacing)} gives a physical/calibrated center (voxelIndex*spacing).
     * In Stream mode, {@code BookmarkManager.add()} stores its arguments verbatim as world
     * coordinates, so the world-origin offset (non-zero for the N5Sources fallback case - see
     * {@code SNT#getWorldOriginOffset()}) must be added; this is the inverse of the subtraction
     * {@code CommonDynamicCmd#getPixelPositionsOfBookmarks} applies when reading markers back, keeping the
     * detect-as-marker/read-as-seed round trip correct. In classic mode, {@code
     * ui.getBookmarkManager()} stores <em>pixel</em> coordinates instead (no world-origin concept
     * applies there), so the physical center is divided back down by {@code spacing}.
     *
     * @param result  the detected soma
     * @param spacing the image's [x, y, z] spacing, as passed to {@link SomaUtils.SomaResult#toNode}
     * @param color   marker color, or {@code null} for the viewer/manager default
     */
    private void addSomaMarker(final SomaUtils.SomaResult result, final double[] spacing, final Color color) {
        final boolean stream = snt.isStreamMode();
        final BookmarkManager mManager = snt.getUI().getBookmarkManager();
        final Path.PathNode centroid = result.toNode(spacing);
        if (stream) {
            final double[] offset = outputOffset();
            mManager.add("detected soma", centroid.x + offset[0], centroid.y + offset[1], centroid.z + offset[2],
                    color, (float) centroid.radius);
        } else {
            mManager.add("detected soma", centroid.x, centroid.y, centroid.z, color, (float) centroid.radius);
        }
    }

    private Roi createOutputRoi(final SomaUtils.SomaResult result) {
        return switch (outputChoice) {
            case OUTPUT_POINT_ROI -> result.createPointRoi();
            case OUTPUT_AREA_ROI -> result.createContourRoi();
            case OUTPUT_CIRCLE_ROI -> result.createCircleRoi();
            default -> null;
        };
    }
}
