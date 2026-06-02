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
import ij.gui.Roi;
import ij.process.FloatPolygon;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.CostPalette;
import sc.fiji.snt.util.PointInImage;

import java.util.Collection;

/**
 * Entry-point command for the cost-function wizard. Resolves a pair of points then launches {@link CostPalette}.
 * Endpoint pair can be from two sources:
 * <ol>
 *   <li>A {@link PointRoi} on the active image with at least 2 points</li>
 *   <li>The first / last node of the user's selected (or most-recently added) path</li>
 * </ol>
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init", label = "Cost Function Selection Wizard...")
public class CostFunctionSelectionCmd extends CommonDynamicCmd {

    protected void init() {
        super.init(true);
    }

    @Override
    public void run() {
        // The command relies entirely on side state (image / selection / path  manager); no @Parameter inputs.
        // There is no harvester dialog because the input map is empty
        if (isCanceled()) return;
        if (snt == null || !snt.accessToValidImageData() || snt.getImagePlus() == null) {
            error("This option requires valid image data to be loaded.");
            return;
        }
        final ImagePlus imp = snt.getImagePlus();
        final PathAndFillManager pafm = snt.getPathAndFillManager();

        // 1. Extract a pair of points from PointRoi
        final Roi roi = imp.getRoi();
        PointInImage a = null;
        PointInImage b = null;
        Path probePath = null;
        if (roi instanceof PointRoi pr && pr.size() >= 2) {
            a = pointFromRoi(pr, pr.size() - 1, imp);
            b = pointFromRoi(pr, pr.size() - 2, imp);
            if (pr.size() > 2) {
                SNTUtils.log("Cost-function wizard: multi-point ROI has "
                        + pr.size() + " points; using the last two.");
            }
        } else {
            // 2. Path: selected if any, else most-recently added
            probePath = resolveProbePath();
            if (probePath != null) {
                if (probePath.size() < 2) {
                    error("The selected path is too short (at least 2 nodes needed).");
                    return;
                }
                a = probePath.firstNode();
                b = probePath.lastNode();
            }
        }

        if (a == null || b == null) {
            error("No probe path found. The <i>Cost Function Selection Wizard</i> needs a "
                    + "representative neurite segment between two endpoints to run its A* comparison. "
                    + "You can either:</p>"
                    + "<ul>"
                    + "<li>Pause tracing in SNT and create a multi-point ROI (2+ points) across a representative "
                    + "neurite segment</li>"
                    + "<li>Trace a short path over a relevant feature</li>"
                    + "<li>Select a path in the Path Manager</li>"
                    + "</ul>"
                    + "<p>If no path is selected, the most recently added one will be used. "
                    + "Then re-launch the wizard.");
            return;
        }

        // 3. Launch the wizard
        final Path replaceTarget = probePath; // may be null in ROI mode
        final CostPalette wizard = new CostPalette(snt, a, b);
        wizard.addListener((chosen, chosenPath) -> applyChoice(chosen, chosenPath, replaceTarget, pafm));
        wizard.show(); // UI STATE: is currently RUNNING_CMD. wizard's dismiss will reset it
    }

    private void applyChoice(final SNT.CostType chosen, final Path chosenPath,
                             final Path replaceTarget, final PathAndFillManager pafm) {
        snt.setCostType(chosen);
        if (chosenPath == null) {
            SNTUtils.log("Cost-function wizard: " + chosen + " selected; no path produced.");
            return;
        }
        if (replaceTarget != null) {
            // Preserve a minimal amount of identity: name + tags
            chosenPath.setName(replaceTarget.getName());
            chosenPath.setColor(replaceTarget.getColor());
            pafm.deletePath(replaceTarget);
        }
        pafm.addPath(chosenPath);
        status("Cost function set to " + chosen + "; "
                + (replaceTarget != null ? "replaced " + chosenPath.getName() : "added new path"), true);
    }

    private Path resolveProbePath() {
        if (snt.getPathAndFillManager() == null) return null;
        if (ui != null) {
            final Collection<Path> sel = ui.getPathManager().getSelectedPaths(true);
            if (sel != null && !sel.isEmpty()) return sel.iterator().next();
        }
        return (snt.getPathAndFillManager().size() == 0) ? null : snt.getPathAndFillManager().getPath(snt.getPathAndFillManager().size() - 1);
    }

    private static PointInImage pointFromRoi(final PointRoi pr, final int i, final ImagePlus imp) {
        final FloatPolygon fp = pr.getFloatPolygon();
        final ij.measure.Calibration cal = imp.getCalibration();
        final int slice = (pr.getPointPosition(i) > 0) ? pr.getPointPosition(i) : imp.getZ();
        return new PointInImage(
                fp.xpoints[i] * cal.pixelWidth,
                fp.ypoints[i] * cal.pixelHeight,
                (slice - 1) * cal.pixelDepth); // 1-based index
    }
}
