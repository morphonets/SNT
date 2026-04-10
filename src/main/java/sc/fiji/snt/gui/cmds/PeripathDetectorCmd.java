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
import ij.gui.PointRoi;
import ij.plugin.frame.RoiManager;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.BookmarkManager;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.PeripathDetector;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.util.ImgUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SciJava command for detecting varicosities/spines/puncta flanking traced paths.
 * Provides the GUI dialog for {@link PeripathDetector}.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Detect Maxima Around Paths", initializer = "init")
public class PeripathDetectorCmd extends CommonDynamicCmd {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private static final String RADIUS_ABSOLUTE = "Absolute (physical units)";
    private static final String RADIUS_MULTIPLIER = "Multiplier of node radii";

    @Parameter(label = "Detection channel", min = "1",
            description = "Image channel for maxima detection (1-based index)")
    private int channel;

    @Parameter(label = "Inner radius", stepSize = "0.1")
    private double innerRadiusMultiplierOrAbsolute;

    @Parameter(label = "Inner radius mode", choices = {RADIUS_ABSOLUTE, RADIUS_MULTIPLIER},
            callback = "innerRadiusModeChanged",
            style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE,
            description = "<HTML>"
                    + "<b>" + RADIUS_MULTIPLIER + "</b>:<br>"
                    + "Inner boundary as a fraction of each node's radius.<br>"
                    + "E.g., 0.5 starts searching at half the local dendrite thickness.<br>"
                    + "Use values &lt; 1 to detect maxima near the membrane edge.<br><br>"
                    + "<b>" + RADIUS_ABSOLUTE + "</b>:<br>"
                    + "Fixed inner radius in calibrated units.")
    private String innerRadiusMode;

    @Parameter(label = "Outer radius", stepSize = "0.1")
    private double outerRadiusMultiplierOrAbsolute;

    @Parameter(label = "Outer radius mode", choices = {RADIUS_ABSOLUTE, RADIUS_MULTIPLIER},
            callback = "outerRadiusModeChanged",
            style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE,
            description = "<HTML>"
                    + "<b>" + RADIUS_MULTIPLIER + "</b>:<br>"
                    + "Outer search radius as a multiple of each node's radius.<br>"
                    + "E.g., 2.0 searches up to 2× the local dendrite thickness.<br><br>"
                    + "<b>" + RADIUS_ABSOLUTE + "</b>:<br>"
                    + "Fixed outer search radius in calibrated units.")
    private String outerRadiusMode;

    @Parameter(label = "Prominence", min = "0",
            description = "Noise tolerance for maxima detection. Higher values = fewer, "
                    + "more prominent detections. In image intensity units.")
    private double prominence = 10.0;

    @Parameter(label = "Merging distance (0 = auto)", min = "0",
            description = "Minimum distance between detections (physical units). "
                    + "Nearby detections are merged, keeping the brightest. "
                    + "Set to 0 for automatic (= outer radius).")
    private double mergingDistance = 0;

    @Parameter(label = "Output", choices = {"ROIs", "Bookmarked locations"})
    private String outputChoice;

    @Parameter(label = "Paths", required = false, persist = false)
    private Collection<Path> paths;

    // By default, reassign each detection to the nearest path when paths are close together.
    private final boolean ASSIGN_TO_NEAREST_PATH = true;


    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (snt == null) return;
        if (paths == null || paths.isEmpty()) {
            paths = snt.getUI().getPathManager().getSelectedPaths(true);
        }
        channel = snt.getChannel();
        if (snt.accessToValidImageData()) {
            if (snt.getImagePlus() != null && snt.getImagePlus().getNChannels() == 1) {
                // Only 1 channel available
                resolveInput("channel");
            } else {
                // Set channel max from loaded image
                final MutableModuleItem<Integer> mItem = getInfo().getMutableInput("channel", Integer.class);
                mItem.setMaximumValue((snt.getImagePlus() != null) ? snt.getImagePlus().getNChannels() : 1);
            }
            // Pre-fill prominence from image stats if available
            final double[] stats = new double[]{snt.getStats().min, snt.getStats().max};
            prominence = (stats[1] - stats[0]) * 0.05; // 5% of dynamic range
        }
        innerRadiusModeChanged();
        outerRadiusModeChanged();

    }

    @SuppressWarnings("unused")
    private void innerRadiusModeChanged() {
        final MutableModuleItem<Double> mItem = getInfo().getMutableInput("innerRadiusMultiplierOrAbsolute", Double.class);
        if (innerRadiusMode == null || RADIUS_ABSOLUTE.equals(innerRadiusMode)) {
            mItem.setMinimumValue(0.0);
        } else {
            mItem.setMinimumValue(0.0);
            mItem.setMaximumValue(1.0);
            if (innerRadiusMultiplierOrAbsolute == 0) innerRadiusMultiplierOrAbsolute = 1.0;
        }
    }

    @SuppressWarnings("unused")
    private void outerRadiusModeChanged() {
        final MutableModuleItem<Double> mItem = getInfo().getMutableInput("outerRadiusMultiplierOrAbsolute", Double.class);
        if (outerRadiusMode == null || RADIUS_ABSOLUTE.equals(outerRadiusMode)) {
            mItem.setMinimumValue(0.01);
            if (outerRadiusMultiplierOrAbsolute == 0) outerRadiusMultiplierOrAbsolute = 5d;
        } else {
            mItem.setMinimumValue(1.01);
            mItem.setMaximumValue(20d);
            if (outerRadiusMultiplierOrAbsolute == 0) outerRadiusMultiplierOrAbsolute = 2d;
        }
    }

    @Override
    public void run() {
        if (paths == null || paths.isEmpty()) {
            error("No paths selected for analysis.");
            return;
        }
        if (snt == null || !snt.accessToValidImageData() || snt.getImagePlus() == null) {
            error("Maxima detection requires valid image data to be loaded.");
            return;
        }

        // Extract the detection channel
        final RandomAccessibleInterval<? extends RealType<?>> detectionImg;
        if (snt.getChannel() == channel) {
            detectionImg = snt.getLoadedData();
        } else {
            detectionImg = ImgUtils.getCtSlice3d(snt.getImagePlus(), channel, snt.getFrame());
        }

        // Build config
        final PeripathDetector.Config cfg = new PeripathDetector.Config()
                .prominence(prominence)
                .assignToNearestPath(ASSIGN_TO_NEAREST_PATH);
        if (RADIUS_ABSOLUTE.equals(innerRadiusMode)) {
            cfg.innerRadius(innerRadiusMultiplierOrAbsolute);
        } else {
            cfg.innerRadiusMultiplier(innerRadiusMultiplierOrAbsolute);
        }
        if (RADIUS_ABSOLUTE.equals(outerRadiusMode)) {
            cfg.outerRadius(outerRadiusMultiplierOrAbsolute);
        } else {
            cfg.outerRadiusMultiplier(outerRadiusMultiplierOrAbsolute);
        }
        if (mergingDistance > 0) {
            cfg.mergingDistance(mergingDistance);
        }
        // Sanity check: inner must be less than outer (only checkable in absolute mode)
        if (RADIUS_ABSOLUTE.equals(innerRadiusMode) && RADIUS_ABSOLUTE.equals(outerRadiusMode)
                && innerRadiusMultiplierOrAbsolute >= outerRadiusMultiplierOrAbsolute) {
            error("Inner radius must be less than outer radius.");
            return;
        }
        SNTUtils.log("PeripathDetectorCmd: " + cfg);

        // Run detection
        final List<PeripathDetector.Detection> results = PeripathDetector.detect(paths, detectionImg, cfg);

        if (results.isEmpty()) {
            error("No maxima detected with current parameters.");
            return;
        }

        SNTUtils.log("Detected " + results.size() + " maxima");

        // Group results by path for labeling and color tagging
        final Map<Path, List<PeripathDetector.Detection>> resultsByPath = results.stream()
                .collect(Collectors.groupingBy(v -> v.path));

        // Update spine/varicosity counts on paths
        resultsByPath.forEach((p, detections) -> p.setSpineOrVaricosityCount(
                p.getSpineOrVaricosityCount() + detections.size()));

        if (ui != null && outputChoice.toLowerCase().contains("bookmark")) {

            // Add to bookmark manager, tagged per path
            resultsByPath.forEach((path, detections) -> {
                final List<double[]> locs = detections.stream()
                        .map(PeripathDetector.Detection::xyzct)
                        .collect(Collectors.toList());
                ui.getBookmarkManager().add(path.getName() + " Max. ", locs, path.getColor());
            });
            resetUI();
            ui.selectTab("Bookmarks");
            ui.showStatus(results.size() + " maxima added to Bookmark Manager.", true);

        } else {
            // Add to ROI Manager: one grouped PointRoi per path
            final ImagePlus imp = snt.getImagePlus();
            RoiManager rm = RoiManager.getInstance2();
            if (rm == null) rm = new RoiManager();
            for (final Map.Entry<Path, List<PeripathDetector.Detection>> entry : resultsByPath.entrySet()) {
                final Path path = entry.getKey();
                final String name = path.getName() + " (" + entry.getValue().size() + " maxima)";
                rm.addRoi(RoiConverter.toPointRoi(entry.getValue(), imp, name, path.getColor()));
            }
            resetUI();
            rm.runCommand("sort");
            rm.runCommand("show all");
        }
    }
}
