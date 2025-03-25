/*
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
import org.scijava.command.Command;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menu = {
        @Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
        @Menu(label = "Neuroanatomy"), //
        @Menu(label = "Sholl"), //
        @Menu(label = "Sholl Analysis (From Image)...")}, //
        initializer = "init")
public class ShollAnalysisImgCmd extends ShollAnalysisImgCommonCmd {

    @Override
    protected void init() {
        analysisAction = "Analyze image";
        super.init(); // will evaluate the image, etc.
        if (!validRequirements(false)) {
            cancel(null);
            return;
        }
        resolveInput("centerButton");
        resolveInput("headerBeforeActions");
        resolveInput("analysisAction");
        resolveInput("analyzeButton");
    }

    @Override
    protected void cancelAndFreezeUI(final String cancelReason) {
        String uiMsg;
        switch (cancelReason) {
            case NO_IMAGE:
                analysisAction = "Change image...";
                setAnalysisScope();
                uiMsg = NO_IMAGE;
                break;
            case NO_CENTER:
                uiMsg = "Please set a ROI indicating the center of analysis and re-run.";
                break;
            case NO_RADII:
                uiMsg = "Ending radius and Radius step size must be within range.";
                break;
            case NO_THRESHOLD:
                uiMsg = "Image is not segmented. Please adjust threshold levels";
                if (imp.getType() == ImagePlus.COLOR_RGB)
                    uiMsg += ".<br><br>Since applying a threshold to an RGB image is an ambiguous operation, "
                            + "you will need to first convert the image to a multichannel composite using IJ's "
                            + " 'Channels Tool'. This will allow single channels to be parsed";
                break;
            default:
                if (cancelReason.contains(",")) {
                    uiMsg = "Image cannot be analyzed. Multiple invalid requirements:<br>- "
                            + cancelReason.replace(", ", "<br>- ");
                } else {
                    uiMsg = cancelReason;
                }
                break;
        }
        cancel(cancelReason);
        helper.setParentToActiveWindow();
        helper.error(uiMsg + ".", null);
    }

    @Override
    public void run() {
        if (isCanceled()) return; // criteria are not met
        try {
            scope = SCOPE_IMP;
            analysisAction = "Analyze image";
            runAnalysis();
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
