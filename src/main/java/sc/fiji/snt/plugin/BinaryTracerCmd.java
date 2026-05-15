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

import ij.gui.Roi;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.tracing.auto.BinaryTracer;

import java.util.List;

/**
 * Interactive command providing a GUI for {@link BinaryTracer}-based
 * autotracing when an image is already loaded in SNT. Uses choice widgets
 * to select from open images.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see BinaryTracerCommonCmd
 * @see BinaryTracerFileCmd
 */
@Plugin(type = Command.class, label = "Automated Tracing: Tree(s) from Segmented Image...", initializer = "init")
public class BinaryTracerCmd extends BinaryTracerCommonCmd {

    @SuppressWarnings("unused")
    private void init() {
        initForImage();
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    @Override
    public void run() {
        if (abortRun || isCanceled()) return;
        runCommand();
    }

    @Override
    protected boolean validateBeforeTracing(final Roi roi, final boolean inferRootFromRoi,
                                            final boolean isSame, final boolean isSegmented,
                                            final boolean isValidOrigImg, final boolean isSameDim,
                                            final boolean isCompatible, final boolean isValidConnectDist,
                                            final boolean isValidRoi) {
        final int width = GuiUtils
                .renderedWidth("      Warning: Images do not share the same spatial calibration<");
        final StringBuilder sb = new StringBuilder("<HTML><div WIDTH=").append(Math.max(550, width))
                .append("><p>The following issue(s) were detected:</p><ul>");
        if (isSame) {
            sb.append("<li>Warning: Choices for segmented and original image point to the same image</li>");
        }
        if (!isValidOrigImg) {
            sb.append("<li>Warning: Original image is not valid and will be ignored</li>");
        }
        if (!isSameDim) {
            sb.append("<li>Warning: Images do not share the same dimensions. Algorithm will likely fail</li>");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isSegmented) {
            sb.append(
                    "<li>Info: Image is not thresholded: Non-zero intensities will be used as foreground</li>");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isCompatible) {
            sb.append("<li>Warning: Images do not share the same spatial calibration</li>.");
            ensureMaskImgVisibleOnAbort = true;
        }
        if (!isValidRoi && inferRootFromRoi) {
            sb.append(
                    "<li>Warning: Image does not contain an active area ROI. Root detection will be disabled</li>");
        }
        if (!isValidConnectDist && connectComponents) {
            sb.append(
                    "<li>Warning: Max. connection distance must be > 0. Connection of components will be disabled</li>");
        }
        sb.append("</ul>");
        sb.append("<p>Would you like to proceed? If you abort, ");
        if (ensureMaskImgVisibleOnAbort) {
            sb.append(" segmented image will be displayed so that you can edit it accordingly. You can then rerun");
        } else {
            sb.append(" you can rerun later on");
        }
        sb.append(" using <i>Utilities > Extract Paths From Segmented Image...</i>");
        sb.append("</p>");
        if (!new GuiUtils().getConfirmation(sb.toString(), "Proceed Despite Warnings?",
                "Proceed. I'm Feeling Lucky", "Abort")) {
            if (ensureMaskImgVisibleOnAbort)
                chosenMaskImp.show();
            resetUI(false, SNTUI.SNT_PAUSED); // waive img to IJ for easier drawing of ROIS, etc.
            cancel();
            return false;
        }
        // Adjust flags
        connectComponents = connectComponents && isValidConnectDist;
        return true;
    }

    @Override
    protected void handleTracedTrees(final List<Tree> trees) {
        // If no display canvas exists or no image is being traced, adopt the
        // chosen image as tracing canvas before the base method adds paths
        if (snt.getImagePlus() == null) {
            // Suppress the 'auto-tracing' prompt for this image. This
            // will be reset once SNT initializes with the new data
            snt.getPrefs().setTemp("autotracing-prompt-armed", false);
            snt.initialize(chosenMaskImp);
        }
        super.handleTracedTrees(trees);
    }
}
