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

    @Parameter(label = "Output", choices = {"Bookmarked locations", "ROIs"})
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

        // Build list of open images that pass label-image heuristics
        labelCandidates = new LinkedHashMap<>();
        for (final ImagePlus imp : ImpUtils.getNonSNTOpenImages()) {

            if (imp.isHyperStack() || ImpUtils.isBinary(imp))
                continue; // skip binary and hyperstacks; only first C/T would be used

            // Quick dimension check: label image should match tracing image spatially
            // NB: not snt.getImagePlus() != null -- that is null in Stream mode unless a materialized
            // crop is active, which would let every open image through unfiltered in that case.
            // getFullImageDimensions() resolves correctly in every mode (Classic, Stream, materialized crop)
            if (snt.accessToValidImageData()) {
                final int[] fullDims = snt.getFullImageDimensions();
                if (imp.getWidth() != fullDims[0] || imp.getHeight() != fullDims[1]) {
                    continue;
                }
                if (fullDims[2] > 1 && imp.getNSlices() != fullDims[2]) {
                    continue;
                }
            }
            labelCandidates.put(imp.getTitle(), imp);
        }

        final MutableModuleItem<String> labelItem = getInfo().getMutableInput("labelImageChoice", String.class);
        if (labelCandidates.isEmpty()) {
            labelItem.setChoices(Collections.singletonList(NO_LABEL_IMAGES));
        } else {
            labelItem.setChoices(new ArrayList<>(labelCandidates.keySet()));
        }
    }

    @Override
    public void run() {
        if (paths == null || paths.isEmpty()) {
            error("No paths selected for analysis.");
            return;
        }
        if (labelCandidates == null || labelCandidates.isEmpty() || NO_LABEL_IMAGES.equals(labelImageChoice)) {
            error("""
                    No suitable label images are open. Please open a segmentation
                    image (e.g., from Weka, Labkit, or cellpose) before running
                    this command.""");
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

        // Dimension check against tracing data
        // NB: not snt.getImagePlus() != null -- see the same note in init() above
        if (snt.accessToValidImageData()) {
            final int[] fullDims = snt.getFullImageDimensions();
            final boolean dimMatch = labelImg.dimension(0) == fullDims[0]
                    && labelImg.dimension(1) == fullDims[1]
                    && (labelImg.numDimensions() <= 2 || labelImg.dimension(2) == fullDims[2]);
            if (!dimMatch) {
                msg("Label image dimensions do not match the tracing image.\n"
                        + "Results may be inaccurate.", "Dimension Mismatch");
            }
        }

        // Build config
        final LabelProximityDetector.Config cfg = new LabelProximityDetector.Config()
                .distanceThreshold(distanceThreshold)
                .mergingDistance(mergingDistance)
                .assignToNearestPath(true);
        SNTUtils.log("LabelProximityDetectorCmd: " + cfg);

        // Run detection
        // Temporarily stamp each path's canvasOffset/spacing to the crop-independent baseline (see
        // SNT#getDefaultCanvasPixelOffset()), NOT the live/possibly-crop-relative
        // snt.getActiveCanvasPixelOffset(): labelImg is always matched against
        // snt.getFullImageDimensions() (the full dataset), never the small crop-local grid, so sampling
        // it must always use the full-dataset-relative offset even while a crop is materialized (whose
        // live offset is crop-relative - see SNT#installMaterializedCrop()). Also needed because
        // bulk-added paths (addTree()/SWC import/.traces loading) never get canvasOffset/spacing stamped
        // otherwise - same idiom as SNT#makePathVolume()
        final List<PointInCanvas> originalOffsets = new ArrayList<>(paths.size());
        final List<ij.measure.Calibration> originalSpacings = new ArrayList<>(paths.size());
        final PointInCanvas defaultOffset = snt.getDefaultCanvasPixelOffset();
        final ij.measure.Calibration liveCal = snt.getCalibration();
        for (final Path p : paths) {
            originalOffsets.add(p.getCanvasOffset());
            originalSpacings.add(p.getCalibration());
            p.setCanvasOffset(defaultOffset);
            p.setSpacing(liveCal);
        }
        final List<Detection> results;
        try {
            results = LabelProximityDetector.detect(paths, labelImg, cfg);
        } catch (final IllegalArgumentException ex) {
            error(ex.getMessage());
            return;
        } finally {
            int i = 0;
            for (final Path p : paths) {
                p.setCanvasOffset(originalOffsets.get(i));
                p.setSpacing(originalSpacings.get(i));
                i++;
            }
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

        if (ui != null && outputChoice.toLowerCase().contains("bookmark")) {

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
            final ImagePlus imp = (snt != null) ? snt.getImagePlus() : null;
            if (imp == null) {
                error("ROI output requires an image to be loaded.");
                return;
            }
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
    }
}
