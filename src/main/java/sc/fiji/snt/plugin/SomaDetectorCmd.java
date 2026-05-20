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
import net.imagej.ImgPlus;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.SNTColor;

import java.awt.*;
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

    private static final String OUTPUT_POINT_ROI = "Point ROI";
    private static final String OUTPUT_AREA_ROI = "Area ROI";
    private static final String OUTPUT_CIRCLE_ROI = "Circular ROI";
    private static final String OUTPUT_PATH = "Single-node path";

    private static final String SCOPE_ALL = "All somata in image";
    private static final String SCOPE_BRIGHTEST = "Brightest/largest soma only";

    @Parameter(label = "Output type", choices = {OUTPUT_PATH, OUTPUT_AREA_ROI, OUTPUT_CIRCLE_ROI, OUTPUT_POINT_ROI},
            style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
            description = "<HTML>Type of output:<br>" +
                    "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>" +
                    "<b>Point ROI</b>: Single point at soma center<br>" +
                    "<b>Area ROI</b>: Contour from thresholding + wand selection<br>" +
                    "<b>Circular ROI</b>: Circle w/ radius from distance transform")
    private String outputChoice;

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
        if (imp == null || img == null) {
            error("No valid image data available.");
        }
        getInfo().setLabel(String.format("Detect Soma [C=%d;T=%d]...", snt.getChannel(), snt.getFrame()));
    }

    @Override
    public void run() {
        if (imp == null || img == null) {
            return;
        }
        final int zSlice = imp.getZ() - 1;  // Convert to 0-indexed
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
        if (OUTPUT_PATH.equals(outputChoice)) {
            final Path path = result.toPath(spacing);
            snt.getPathAndFillManager().addPath(path);
            status("Soma added to Manager", true);
        } else {
            Roi roi = createOutputRoi(result);
            if (roi == null && (OUTPUT_AREA_ROI.equals(outputChoice) || OUTPUT_CIRCLE_ROI.equals(outputChoice))) {
                roi = result.createPointRoi();
            }
            if (roi == null) {
                error("Could not create ROI. Soma detection failed.");
            } else {
                imp.setRoi(roi);
                status("Soma ROI created", true);
                if (roi instanceof PointRoi && !OUTPUT_POINT_ROI.equals(outputChoice))
                    error(outputChoice + " could not be created. A point ROI was created instead.");
            }
        }
        SNTUtils.log(result.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runAllSomas(final int zSlice, final double[] spacing) {
        snt.setCanvasLabelAllPanes("Detecting somata....");
        // Convert calibrated distances to pixel units for detection
        final double avgXYSpacing = (spacing[0] + spacing[1]) / 2.0;
        final double minRadiusPx = (minRadius > 0) ? minRadius / avgXYSpacing : 0;
        final double minSomaDistancePx = (minSomaDistance > 0) ? minSomaDistance / avgXYSpacing : 0;
        List<SomaUtils.SomaResult> results = SomaUtils.detectAllSomas(img, threshold, zSlice, minRadiusPx, minSomaDistancePx);
        if (results.isEmpty()) {
            snt.setCanvasLabelAllPanes(null);
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
        final Color[] colors = SNTColor.getDistinctColorsAWT(results.size());
        int idx = 0;
        if (OUTPUT_PATH.equals(outputChoice)) {
            final Tree paths = new Tree();
            for (final SomaUtils.SomaResult result : results) {
                final Path path = result.toPath(spacing);
                path.setColor(colors[idx++]);
                path.setName(String.format("Soma %02d", idx));
                paths.add(path);

            }
            snt.getPathAndFillManager().addTree(paths);
            status(results.size() + " soma(s) added to Manager", true);
        } else {
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
        }
        snt.setCanvasLabelAllPanes(null);
        SNTUtils.log("Detected " + results.size() + " soma(s)");
        for (final SomaUtils.SomaResult result : results) {
            SNTUtils.log("  " + result.toString());
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
