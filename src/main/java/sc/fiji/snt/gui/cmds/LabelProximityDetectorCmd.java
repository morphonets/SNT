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

package sc.fiji.snt.gui.cmds;

import ij.ImagePlus;
import ij.plugin.frame.RoiManager;
import net.imagej.ImgPlus;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.analysis.detection.Detection;
import sc.fiji.snt.analysis.detection.LabelProximityDetector;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInCanvas;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SciJava command for detecting proximity contacts between traced paths and labeled surfaces (segmentation images).
 * Provides the GUI dialog for {@link LabelProximityDetector}.
 *
 * @author Tiago Ferreira
 * @see LabelProximityDetector
 */
@Plugin(type = Command.class, label = "Detect Label Proximity", initializer = "init")
public class LabelProximityDetectorCmd extends CommonDynamicCmd {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    private static final String NO_LABEL_IMAGES = "<No label images available>";
    private static final String OUTPUT_BOOKMARKS = "Bookmarked locations";
    private static final String OUTPUT_ROIS = "ROIs";

    @Parameter(label = "Label image",
            description = "<HTML>A segmentation/label image with integer labels.<br>"
                    + "Background should be 0; each label value defines a surface.")
    private String labelImageChoice;

    @Parameter(label = "Distance threshold", min = "0",
            description = "<HTML>Maximum distance (calibrated units) from label boundary<br>"
                    + "for a node to be flagged as a contact point.<br>"
                    + "<b>0</b>: closest-approach mode (one detection per path–label pair).")
    private double distanceThreshold = 0;

    @Parameter(label = "Merging distance (0 = disable)", min = "0",
            description = "<HTML>Minimum distance between detections (calibrated units).<br>"
                    + "Nearby detections are merged, keeping the one closest to the boundary.<br>"
                    + "Set to 0 to disable merging.")
    private double mergingDistance = 0;

    @Parameter(label = "Output", choices = {OUTPUT_BOOKMARKS, OUTPUT_ROIS})
    private String outputChoice;

    @Parameter(label = "Paths", required = false, persist = false)
    private Collection<Path> paths;

    /**
     * Map of display name -> ImagePlus for label image candidates
     */
    private Map<String, ImagePlus> labelCandidates;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (snt == null) return;
        if (paths == null || paths.isEmpty()) {
            paths = snt.getUI().getPathManager().getSelectedPaths(true);
        }

        if (noPathsError()) return;

        // Build list of open images that pass label-image heuristics
        labelCandidates = new LinkedHashMap<>();
        for (final ImagePlus imp : ImpUtils.getNonSNTOpenImages()) {

            if (imp.isHyperStack() || ImpUtils.isBinary(imp))
                continue; // skip binary and hyperstacks; only first C/T would be used

            // Quick dimension check: label image should match either the full dataset's own raw grid, or, in Stream
            // mode, the currently materialized crop's own small grid: see matchesFullOrCropDims()/resolveLabelImageOffset()
            // NB: not snt.getImagePlus() != null: that is null in Stream mode unless a materialized crop is active
            if (!matchesFullOrCropDims(imp.getWidth(), imp.getHeight(), imp.getNSlices())) {
                continue;
            }
            labelCandidates.put(imp.getTitle(), imp);
        }

        final MutableModuleItem<String> labelItem = getInfo().getMutableInput("labelImageChoice", String.class);
        if (labelCandidates.isEmpty()) {
            labelItem.setChoices(List.of(NO_LABEL_IMAGES));
            noSuitableCandidatesError();
        } else {
            labelItem.setChoices(new ArrayList<>(labelCandidates.keySet()));
        }
        final MutableModuleItem<String> outputChoiceItem = getInfo().getMutableInput("outputChoice", String.class);
        // ROI output needs a classic canvas to attach the PointRoi to. Without one, only Bookmarked locations work
        if (snt.getImagePlus() == null)
            outputChoiceItem.setChoices(List.of(OUTPUT_BOOKMARKS));

    }

    /**
     * Whether the given (uncalibrated) dimensions match either the full dataset's own raw grid
     * ({@link sc.fiji.snt.SNT#getFullImageDimensions()}) or, while a crop is materialized (Stream
     * mode only - see {@link sc.fiji.snt.SNT#isMaterializedCrop()}), that crop's own small grid
     * ({@link sc.fiji.snt.SNT#getWidth()}/{@link sc.fiji.snt.SNT#getHeight()}/
     * {@link sc.fiji.snt.SNT#getDepth()}, which reflect the crop's size for as long as one is
     * installed). No reference image data: anything passes (matches prior behavior).
     */
    private boolean matchesFullOrCropDims(final int w, final int h, final int d) {
        if (snt == null || !snt.accessToValidImageData()) return true;
        if (dimsMatch(w, h, d, snt.getFullImageDimensions())) return true;
        return snt.isMaterializedCrop()
                && dimsMatch(w, h, d, new int[]{snt.getWidth(), snt.getHeight(), snt.getDepth()});
    }

    private static boolean dimsMatch(final int w, final int h, final int d, final int[] ref) {
        if (w != ref[0] || h != ref[1]) return false;
        return ref[2] <= 1 || d == ref[2];
    }

    /**
     * Resolves the canvasOffset needed to correctly sample {@code labelImg}: the full-dataset-relative
     * {@link sc.fiji.snt.SNT#getDefaultCanvasPixelOffset()} if its dimensions match the full dataset,
     * the crop-relative {@link sc.fiji.snt.SNT#getActiveCanvasPixelOffset()} if they instead match a
     * currently materialized crop (Stream mode only), or the full-dataset offset accompanied by a
     * "Dimension Mismatch" warning if neither matches.
     */
    private PointInCanvas resolveLabelImageOffset(final ImgPlus<? extends RealType<?>> labelImg) {
        if (!snt.accessToValidImageData()) return snt.getDefaultCanvasPixelOffset();
        final int w = (int) labelImg.dimension(0);
        final int h = (int) labelImg.dimension(1);
        final int d = (labelImg.numDimensions() > 2) ? (int) labelImg.dimension(2) : 1;
        if (dimsMatch(w, h, d, snt.getFullImageDimensions())) {
            return snt.getDefaultCanvasPixelOffset();
        }
        if (snt.isMaterializedCrop()
                && dimsMatch(w, h, d, new int[]{snt.getWidth(), snt.getHeight(), snt.getDepth()})) {
            return snt.getActiveCanvasPixelOffset();
        }
        msg("Label image dimensions do not match the tracing image"
                + (snt.isMaterializedCrop() ? " or the materialized crop" : "") + ".\n"
                + "Results may be inaccurate.", "Dimension Mismatch");
        return snt.getDefaultCanvasPixelOffset();
    }

    private boolean noSuitableCandidatesError() {
        if (labelCandidates == null || labelCandidates.isEmpty() || NO_LABEL_IMAGES.equals(labelImageChoice)) {
            error("""
                    No suitable label images are open. Please open a segmentation
                    image (e.g., from TWS, Labkit, or cellpose) before running
                    this command.""");
            return true;
        }
        return false;
    }

    private boolean noPathsError() {
        if (paths == null || paths.isEmpty()) {
            error("No paths selected for analysis.");
            return true;
        }
        return false;
    }

    @Override
    public void run() {
        if (isCanceled() || noPathsError() || noSuitableCandidatesError()) return;

        // Short-circuit ROI output as early as possible, before running (possibly expensive)
        // detection: matches the fall-through condition of the ROI branch further below (anything
        // that isn't "Bookmarked locations" picked with a UI present, since headless invocations
        // have no BookmarkManager to add to either)
        final boolean roiOutput = !(ui != null && OUTPUT_BOOKMARKS.equals(outputChoice));
        if (roiOutput && (snt == null || snt.getImagePlus() == null)) {
            error(String.format("ROI output requires a %s. Use 'Bookmarked locations' output instead.",
                    (snt != null && snt.isStreamMode() ? "materialized crop" : "valid image")));
            return;
        }

        final ImagePlus labelImp = labelCandidates.get(labelImageChoice);
        if (labelImp == null) {
            error("Selected label image is no longer available.");
            return;
        }

        // Convert to RAI (first channel/frame only)
        final ImgPlus<? extends RealType<?>> labelImg = ImpUtils.toImgPlus3D(labelImp, 1, 1);

        // Validate it's actually a label image
        if (!ImgUtils.isLabelImage(labelImg)) {
            error("'" + labelImageChoice + "' does not appear to be a valid label image.\n"
                    + "Expected: non-negative integer values, 0 = background,\n"
                    + "bounded number of unique classes (≤ 500).");
            return;
        }

        // Build config
        final LabelProximityDetector.Config cfg = new LabelProximityDetector.Config()
                .distanceThreshold(distanceThreshold)
                .mergingDistance(mergingDistance)
                .assignToNearestPath(true);
        SNTUtils.log("LabelProximityDetectorCmd: " + cfg);

        // Run detection
        // Temporarily stamp each path's canvasOffset/spacing so LabelProximityDetector's sampling
        // (and, further down, Detection#xyzct()/RoiConverter's world->pixel conversion for
        // bookmark/ROI output) read/write the correct voxel in labelImg. Which offset that is
        // depends on which grid labelImg actually matches - see resolveLabelImageOffset(). Also
        // needed because bulk-added paths (addTree()/SWC import/.traces loading) never get
        // canvasOffset/spacing stamped otherwise - same idiom as SNT#makePathVolume()
        final List<PointInCanvas> originalOffsets = new ArrayList<>(paths.size());
        final List<ij.measure.Calibration> originalSpacings = new ArrayList<>(paths.size());
        final PointInCanvas labelOffset = resolveLabelImageOffset(labelImg);
        final ij.measure.Calibration liveCal = snt.getCalibration();
        for (final Path p : paths) {
            originalOffsets.add(p.getCanvasOffset());
            originalSpacings.add(p.getCalibration());
            p.setCanvasOffset(labelOffset);
            p.setSpacing(liveCal);
        }
        try {
            final List<Detection> results;
            try {
                results = LabelProximityDetector.detect(paths, labelImg, cfg);
            } catch (final IllegalArgumentException ex) {
                error(ex.getMessage());
                return;
            }

            if (results.isEmpty()) {
                error("No proximity contacts detected with current parameters.");
                return;
            }

            SNTUtils.log("Detected " + results.size() + " proximity contacts");

            // Group results by path+label, resolving fitted paths back to unfitted originals
            final Map<String, List<Detection>> resultsByPathAndLabel = results.stream()
                    .collect(Collectors.groupingBy(d -> {
                        final Path p = d.path.isFittedVersionOfAnotherPath()
                                ? d.path.getUnfitted() : d.path;
                        return p.getName() + (d.labelValue >= 0 ? " [Label " + d.labelValue + "]" : "");
                    }, LinkedHashMap::new, Collectors.toList()));

            if (ui != null && OUTPUT_BOOKMARKS.equals(outputChoice)) {

                // NB: Detection#xyzct()/RoiConverter (ROI branch below) read each Path's CURRENT
                // canvasOffset/calibration to convert back to pixel space, so this must run while paths
                // are still stamped with the offset set above - see the identical NB in PeripathDetectorCmd#run()
                resultsByPathAndLabel.forEach((groupName, detections) -> {
                    final List<double[]> locs = detections.stream()
                            .map(Detection::xyzct)
                            .collect(Collectors.toList());
                    final Path refPath = detections.getFirst().path;
                    ui.getBookmarkManager().add(groupName, locs, refPath.getColor());
                });
                resetUI();
                ui.selectTab("Bookmarks");
                ui.showStatus(results.size() + " proximity contacts added to Bookmark Manager.", true);

            } else {
                // imp/roiOutput already validated up front, before detection ran
                final ImagePlus imp = snt.getImagePlus();
                RoiManager rm = RoiManager.getInstance2();
                if (rm == null) rm = new RoiManager();
                for (final Map.Entry<String, List<Detection>> entry : resultsByPathAndLabel.entrySet()) {
                    final String name = entry.getKey() + " (" + entry.getValue().size() + " contacts)";
                    final Path refPath = entry.getValue().getFirst().path;
                    rm.addRoi(RoiConverter.toPointRoi(entry.getValue(), imp, name, refPath.getColor()));
                }
                resetUI();
                rm.runCommand("sort");
                rm.runCommand("show all");
            }
        } finally {
            // Restore each Path's original canvasOffset/spacing only now that all output (bookmarks/
            // ROIs) has been built from the offset-stamped state above
            int i = 0;
            for (final Path p : paths) {
                p.setCanvasOffset(originalOffsets.get(i));
                p.setSpacing(originalSpacings.get(i));
                i++;
            }
        }
    }
}
