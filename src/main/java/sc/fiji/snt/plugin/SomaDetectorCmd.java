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
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.BookmarkManager;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.AbstractBigViewer;
import net.imglib2.realtransform.AffineTransform3D;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Command to automatically detect the soma (cell body) in neuronal images.
 * <p>
 * Uses a combined EDT-intensity approach to find the thickest, brightest
 * structure in the image, which typically corresponds to the soma.
 * </p>
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Detect Soma...", initializer = "init")
public class SomaDetectorCmd extends CommonDynamicCmd {

    /** Traditional-mode outputs */
    private static final String OUTPUT_POINT_ROI = "Point ROI";
    private static final String OUTPUT_AREA_ROI = "Area ROI";
    private static final String OUTPUT_CIRCLE_ROI = "Circular ROI";

    /** outputs common to both traditional and stream modes */
    private static final String OUTPUT_PATH = "Single-node path";
    private static final String OUTPUT_BOOKMARK = "Bookmark/marker";

    private static final String SCOPE_ALL = "All somata in image";
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

    /**
     * Becomes {@code true} the first time the user edits {@link #zPos} directly (see  {@link #zPosChanged()}).
     * Distinct from {@link #zPos}'s own value because a world Z-coordinate has no value that can double as "unset"
     * sentinel (negative depths are common and legitimate). This flag is what lets {@link #zPosChoiceChanged()} tell
     * "never touched, needs a default" apart from "user specified a value, even a negative one".
     */
    private boolean zPosUserSet;

    @Parameter(label = "Output type",
            choices = { OUTPUT_BOOKMARK, OUTPUT_PATH, OUTPUT_AREA_ROI, OUTPUT_CIRCLE_ROI, OUTPUT_POINT_ROI },
            description = "<HTML>Type of output:<br>" +
                    "<b>Bookmark/marker</b>: Bookmarked position at soma center w/ size from distance transform<br>" +
                    "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>" +
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
                    "When &gt; 0, non-maximum suppression removes detections<br>" +
                    "that are too close together, keeping only the strongest.<br>" +
                    "Only applies when detecting <b>" + SCOPE_ALL + "</b>.<br>" +
                    "0 = no distance-based filtering (default)")
    private double minSomaDistance = 0;

    @Parameter(label = "Expected no. of somata", min = "0", required = false,
            description = "<HTML><b>[Experimental]</b> Expected number of cell bodies.<br>" +
                    "When &gt; 0, keeps only the top-N detections ranked by<br>" +
                    "EDT thickness. May not work well for images with large<br>" +
                    "connected bright regions. <i>Min. inter-soma distance</i><br>" +
                    "is typically more reliable.<br>" +
                    "Only applies when detecting <b>" + SCOPE_ALL + "</b>.<br>" +
                    "0 = no count-based filtering (default)")
    private int nSomas = 0;

    private ImagePlus imp;
    private ImgPlus<?> img;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        imp = snt.getImagePlus();
        img = snt.getLoadedDataAsImg(false);
        if (imp == null && img == null) {
            error("No valid image data available.");
        }
        warnIfMaterializedCropActive();
        if (snt != null && snt.isStreamMode()) {
            outputChoice = OUTPUT_BOOKMARK;
            final MutableModuleItem<String> outputItem = getInfo().getMutableInput("outputChoice", String.class);
            if (outputItem != null) {
                outputItem.setChoices(List.of(OUTPUT_BOOKMARK, OUTPUT_PATH));
                outputItem.setDescription("<HTML>Type of output:<br>" +
                        "<b>Bookmark/marker</b>: Bookmarked position at soma center w/ size from distance transform<br>" +
                        "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>");
            }
        } else {
            // Classic mode always has imp.getZ() to fall back on: the Depth override is moot there
            resolveInput("zPosChoice");
            resolveInput("zPos");
        }
        getInfo().setLabel(String.format("Detect Soma [C=%d;T=%d%s]...", snt.getChannel(), snt.getFrame(), zLabelSuffix()));
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

    @Override
    public void run() {
        if (imp == null && img == null) {
            return;
        }
        final int zSlice;
        if (imp != null) {
            zSlice = imp.getZ() - 1;  // Convert to 0-indexed
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

        // detectAllSomas() with zSlice < 0 on a 3D image computes a max-intensity projection across every
        // Z-plane: on a lazily-loaded Stream-mode volume that means touching the entire dataset. A
        // user-specified depth (zSlice >= 0) bypasses this, so the warning is only needed when none was given
        if (SCOPE_ALL.equals(scopeChoice) && zSlice < 0 && img != null && img.numDimensions() > 2
                && !getConfirmationEdtSafe(
                        "Detecting all somata on a streamed volume requires computing a max-intensity "
                                + "projection across the entire dataset. Depending on dataset size and "
                                + "network/disk speed, this can take a long time. Continue?",
                        "Run on Streamed Image?")) {
            resetUI();
            return;
        }
        final double[] spacing = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
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
        if (OUTPUT_PATH.equals(outputChoice)) {
            final Path path = result.toPath(spacing);
            snt.getPathAndFillManager().addPath(path);
            status("Soma added to Manager", true);
        } else if (OUTPUT_BOOKMARK.equals(outputChoice) ) {
            addSomaMarker(result, spacing, null);
            status("Soma added as marker", true);
            if (ui != null) ui.selectTab("Bookmarks"); // selects markers table in stream mode
        } else {
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
            final double[] offset = snt.getWorldOriginOffset(); // only set in stream mode
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
