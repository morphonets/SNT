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
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.analysis.detection.AlongPathDetector;
import sc.fiji.snt.analysis.detection.Detection;
import sc.fiji.snt.util.ImgUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SciJava command for detecting boutons/varicosities along traced paths using
 * longitudinal radius and intensity profiles. Provides the GUI dialog for
 * {@link AlongPathDetector}.
 *
 * @author Tiago Ferreira
 * @see AlongPathDetector
 * @see PeripathDetectorCmd
 */
@Plugin(type = Command.class, label = "Detect Swellings Along Paths", initializer = "init")
public class AlongPathDetectorCmd extends CommonDynamicCmd {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    @Parameter(label = "Swelling factor", min = "1.01", max = "10.0", stepSize = "0.1",
            description = "<HTML>A path node is flagged as 'swelled' when its radius exceeds the average<br>"
                    + "of its neighbors by at least this factor. Default: 1.5")
    private double swellingFactor = 1.5;

    @Parameter(label = "No. of neighbors", min = "2", max = "100", stepSize = "2",
            description = "<HTML>Number of neighboring nodes used to compute the local average radius.<br>"
                    + "Neighbors are split evenly on each side of the node being tested.")
    private int numNeighbors = 10;

    @Parameter(label = "Min. intensity", min = "-1",
            description = "<HTML>Minimum centerline intensity for a detection to be accepted.<br>"
                    + "<b>0</b>: disable intensity filtering (radius-only detection).<br>"
                    + "<b>-1</b>: auto-threshold (midpoint brightness of image dynamic range).")
    private double minIntensity = 0;

    @Parameter(label = "Intensity channel", min = "1",
            description = "Image channel for intensity sampling (1-based index)")
    private int channel;

    @Parameter(label = "Merging distance (0 = auto)", min = "0",
            description = "<HTML>Minimum distance between detections (physical units).<br>"
                    + "Adjacent detections are merged, keeping the brightest.<br>"
                    + "Set to 0 for automatic, i.e., 2× mean radius).")
    private double mergingDistance = 0;

    @Parameter(label = "Exclude junctions/tips",
            description = "<HTML>Exclude nodes near branch points and path tips from detection.<br>"
                    + "These locations may have naturally enlarged radii and may produce false positives.")
    private boolean excludeJunctions = true;

    @Parameter(label = "Output", choices = {"ROIs", "Bookmarked locations"})
    private String outputChoice;

    @Parameter(label = "Paths", required = false, persist = false)
    private Collection<Path> paths;

    private final boolean ASSIGN_TO_NEAREST_PATH = true;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (snt == null) return;
        if (paths == null || paths.isEmpty()) {
            paths = snt.getUI().getPathManager().getSelectedPaths(true);
        }

        // If selected paths have no radii but their fitted flavors do have radii, use those instead
        paths = paths.stream()
                .map(p -> (!p.hasRadii() && p.getFitted() != null && p.getFitted().hasRadii()) ? p.getFitted() : p)
                .collect(Collectors.toList());

        channel = snt.getChannel();

        final boolean hasImage = snt.accessToValidImageData()
                && snt.getImagePlus() != null;

        if (hasImage) {
            if (snt.getImagePlus().getNChannels() == 1) {
                resolveInput("channel");
            } else {
                final MutableModuleItem<Integer> mItem = getInfo()
                        .getMutableInput("channel", Integer.class);
                mItem.setMaximumValue(snt.getImagePlus().getNChannels());
            }
        } else {
            // No image: force intensity filtering off and hide related params
            minIntensity = 0;
            final MutableModuleItem<Double> mItem = getInfo()
                    .getMutableInput("minIntensity", Double.class);
            mItem.setMinimumValue(0.0);
            mItem.setMaximumValue(0.0);
            resolveInput("channel");
        }
    }

    @Override
    public void run() {
        if (paths == null || paths.isEmpty()) {
            error("No paths selected for analysis.");
            return;
        }

        // Check radii availability
        final long withRadii = paths.stream().filter(Path::hasRadii).count();
        if (withRadii == 0) {
            error("Swelling detection requires paths with fitted radii.\n"
                    + "Please fit radii first (right-click paths → Refine/Fit → Fit Radii).");
            return;
        }
        final long withoutRadii = paths.size() - withRadii;
        if (withoutRadii > 0) {
            SNTUtils.log("AlongPathDetectorCmd: " + withoutRadii + " of "
                    + paths.size() + " paths lack radii and will be skipped");
            msg(withoutRadii + " of " + paths.size()
                    + " selected paths lack radii and will be skipped.",
                    "Partial Radii");
        }

        // Resolve intensity threshold: 0 = disabled, <0 = auto, >0 = explicit
        // Treat all negative values as auto-threshold
        final boolean useIntensity = minIntensity != 0;
        final RandomAccessibleInterval<? extends RealType<?>> intensityImg;
        double resolvedMinIntensity = minIntensity;
        if (useIntensity) {
            if (snt == null || !snt.accessToValidImageData() || snt.getImagePlus() == null) {
                error("Intensity filtering requires valid image data to be loaded.");
                return;
            }
            if (minIntensity < 0) {
                // Auto-threshold: midpoint of image dynamic range
                final double min = snt.getStats().min;
                final double max = snt.getStats().max;
                resolvedMinIntensity = (max + min) / 2.0;
                SNTUtils.log("AlongPathDetectorCmd: auto-threshold = " + resolvedMinIntensity
                        + " (range " + min + "–" + max + ")");
            }
            if (snt.getChannel() == channel) {
                intensityImg = snt.getLoadedData();
            } else {
                intensityImg = ImgUtils.getCtSlice3d(snt.getImagePlus(), channel, snt.getFrame());
            }
        } else {
            intensityImg = null;
        }

        // Build config
        final AlongPathDetector.Config cfg = new AlongPathDetector.Config()
                .swellingFactor(swellingFactor)
                .windowSize(Math.max(1, numNeighbors / 2))
                .minIntensity(resolvedMinIntensity)
                .excludeJunctions(excludeJunctions)
                .assignToNearestPath(ASSIGN_TO_NEAREST_PATH);
        if (mergingDistance > 0) {
            cfg.mergingDistance(mergingDistance);
        }
        SNTUtils.log("AlongPathDetectorCmd: " + cfg);

        // Run detection
        final List<Detection> results = AlongPathDetector.detect(paths, intensityImg, cfg);

        if (results.isEmpty()) {
            error("No swellings detected with current parameters.");
            return;
        }

        SNTUtils.log("Detected " + results.size() + " swellings");

        // Group results by path, resolving fitted paths back to their
        // unfitted originals so that labels and counts match PathManagerUI
        final Map<Path, List<Detection>> resultsByPath = results.stream()
                .collect(Collectors.groupingBy(v ->
                    v.path.isFittedVersionOfAnotherPath()
                            ? v.path.getUnfitted() : v.path
                ));

        // Update spine/varicosity counts on the (unfitted) paths
        resultsByPath.forEach((p, detections) -> p.setSpineOrVaricosityCount(
                p.getSpineOrVaricosityCount() + detections.size()));

        if (ui != null)
            ui.getPathManager().applyDefaultTags("No. of Spine/Varicosity Markers");

        if (ui != null && outputChoice.toLowerCase().contains("bookmark")) {

            resultsByPath.forEach((path, detections) -> {
                final List<double[]> locs = detections.stream()
                        .map(Detection::xyzct)
                        .collect(Collectors.toList());
                ui.getBookmarkManager().add(path.getName() + " Swelling ", locs, path.getColor());
            });
            resetUI();
            ui.selectTab("Bookmarks");
            ui.showStatus(results.size() + " swellings added to Bookmark Manager.", true);

        } else {
            final ImagePlus imp = (snt != null) ? snt.getImagePlus() : null;
            if (imp == null) {
                error("ROI output requires an image to be loaded.");
                return;
            }
            RoiManager rm = RoiManager.getInstance2();
            if (rm == null) rm = new RoiManager();
            for (final Map.Entry<Path, List<Detection>> entry : resultsByPath.entrySet()) {
                final Path path = entry.getKey();
                final String name = path.getName() + " (" + entry.getValue().size() + " swellings)";
                rm.addRoi(RoiConverter.toPointRoi(entry.getValue(), imp, name, path.getColor()));
            }
            resetUI();
            rm.runCommand("sort");
            rm.runCommand("show all");
        }
    }
}
