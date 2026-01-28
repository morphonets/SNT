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
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.SomaUtils;

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

    @Parameter(label = "Output type", choices = {OUTPUT_PATH, OUTPUT_POINT_ROI, OUTPUT_AREA_ROI, OUTPUT_CIRCLE_ROI},
            style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
            description = "<HTML>Type output<br>" +
                    "<b>Single-node path</b>: Single node path at soma center w/ radius from distance transform<br>" +
                    "<b>Point ROI</b>: Single point at soma center<br>" +
                    "<b>Area ROI</b>: Contour from thresholding + wand selection<br>" +
                    "<b>Circular ROI</b>: Circle w/ radius from distance transform")
    private String outputChoice;

    @Parameter(label = "Threshold", min = "-1", required = false,
            description = "<HTML>Intensity threshold for soma detection.<br>" +
                    "-1 = auto (Otsu's method)")
    private double threshold = -1;


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
    }

    @Override
    public void run() {
        if (imp == null || img == null) {
            return;
        }
        final SomaUtils.SomaResult somaResult = SomaUtils.detectSoma(img, threshold, imp.getZ() - 1);
        if (OUTPUT_PATH.equals(outputChoice)) {
            final double[] spacing = { snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth() };
            final Path path = somaResult.toPath(spacing);
            super.snt.getPathAndFillManager().addPath(path);
            status("Soma added to Path Manager", true);
        } else {
            final Roi roi = createOutputRoi(somaResult, outputChoice);
            if (roi == null) {
                error("Could not create ROI. Soma detection failed");
            } else {
                imp.setRoi(roi);
            }

        }
        SNTUtils.log(somaResult.toString());
        resetUI();
    }

    private Roi createOutputRoi(final SomaUtils.SomaResult somaResult, final String outputChoice) {
        return switch (outputChoice) {
            case OUTPUT_POINT_ROI -> somaResult.createPointRoi();
            case OUTPUT_AREA_ROI -> somaResult.createContourRoi();
            case OUTPUT_CIRCLE_ROI -> somaResult.createCircleRoi();
            default -> null;
        };
    }
}