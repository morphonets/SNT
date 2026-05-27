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
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.seed.LabelsToSeeds;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.ImpUtils;

import java.util.List;

/**
 * Generates {@link SeedPoint}s from an open labels image (e.g. cellpose,
 * Labkit, StarDist masks). One seed is produced per non-zero label: centroid
 * in physical coordinates, sphere-equivalent radius, and a confidence that
 * scales linearly with the label's volume (smallest -> {@code minConfidence},
 * largest -> {@code 1.0}). Seeds are added to SNT's {@link SeedOverlay}..
 *
 * @author Tiago Ferreira
 * @see LabelsToSeeds
 */
@Plugin(type = Command.class, initializer="init", label = "Generate Seeds from Labels Image...")
public class LoadSeedsFromLabelsImageCmd extends ChooseDatasetCmd {

    @Parameter(label = "Type label",
            description = "<HTML>Value assigned to each seed's <code>type</code> field "
                    + "(e.g. <i>soma</i>, <i>endpoint</i>).")
    private String typeLabel = "soma";

    @Parameter(label = "Min. confidence", min = "0", max = "1",
            stepSize = "0.05", style = NumberWidget.SLIDER_STYLE,
            description = "<HTML>Confidence assigned to the smallest label. "
                    + "The largest label gets <code>1.0</code> and intermediate "
                    + "labels are linearly interpolated by volume.")
    private double minConfidence = 0.1;

    @Parameter(label = "Replace existing seeds",
            description = "<HTML>If checked, the existing seed overlay is replaced.<br>"
                    + "If unchecked, imported seeds are appended.")
    private boolean replace;


    @Override
    protected void init() {
        // Bypass ChooseDatasetCmd.init() because its secondary-layer etc. does not apply to labels images.
        super.init(true);
        if (isCanceled()) return; // snt would be null
        super.secondaryLayer = false;
        resolveInput("secondaryLayer");
        resolveInput("validateCalibration");
        resolveInput("restoreImg");
        getInfo().setLabel("Generate Seeds from Labels Image");
        final MutableModuleItem<String> mItem = getInfo().getMutableInput("choice", String.class);
        mItem.setLabel("Labels/Masks Image");
        if (!populateImpChoices(null)) {
            // No candidate images: populateImpChoices has already shown the
            // error dialog and called cancel() via noImgsOpenError(), which
            // also resolves the ChooseDatasetCmd inputs. We still need to
            // resolve our own subclass inputs here so the harvester doesn't
            // pop a form for them on top of the error.
            resolveInput("typeLabel");
            resolveInput("minConfidence");
            resolveInput("replace");
        }
    }

    @Override
    public void run() {

        if (impMap == null || choice == null || impMap.isEmpty() || isCanceled()) {
            resetUI();
            return;
        }
        ImagePlus labels = impMap.get(choice);
        if (labels == null) {
            resetUI();
            return;
        }

        // Binary masks (one distinct non-zero value) would otherwise collapse
        // into a single nonsensical seed from the union of all foreground
        // voxels. Detect that case and let the user split the mask into
        // distinct objects via connected-components analysis. The check
        // bails as soon as a second distinct non-zero is observed
        String src = "labels-image:" + labels.getTitle();
        if (LabelsToSeeds.isBinaryMask(labels)) {
            final BinaryMaskChoice userChoice = askAboutBinaryMask(labels.getTitle());
            switch (userChoice) {
                case CANCEL -> {
                    status("Cancelled.", true);
                    resetUI();
                    return;
                }
                case CCA -> {
                    status("Running connected-components on " + labels.getTitle() + "...", false);
                    try {
                        labels = LabelsToSeeds.connectedComponents(labels, true);
                    } catch (final RuntimeException ex) {
                        error("Connected-components analysis failed: " + ex.getMessage());
                        return;
                    }
                    ImpUtils.applyColorTable(labels, ColorMaps.get("glasbey-on-dark"));
                    labels.resetDisplayRange();
                    labels.show(); // so the user can verify the split
                    src = "labels-image:" + labels.getTitle() + " (CCA from binary mask)";
                    SNTUtils.log("Binary mask: extracted " + countDistinctLabels(labels)
                            + " connected component(s) via 26/8-connectivity.");
                }
                case AS_IS -> { /* fall through, user is intentionally importing one seed */ }
            }
        }

        status("Generating seeds from " + labels.getTitle() + "...", false);
        final List<SeedPoint> seeds;
        try {
            seeds = LabelsToSeeds.compute(labels, minConfidence, typeLabel, src);
        } catch (final RuntimeException ex) {
            error("Failed to process labels image: " + ex.getMessage());
            return;
        }
        if (seeds.isEmpty()) {
            error("No non-zero labels found in " + labels.getTitle() + ".");
            return;
        }

        final SeedOverlay overlay = snt.getSeedOverlay();
        if (replace) overlay.clear();
        overlay.addAll(seeds);
        status(seeds.size() + " seed(s) generated from " + labels.getTitle() + ".", true);
        resetUI();
    }

    /**
     * Outcome of the binary-mask prompt. Kept enum-typed so "treat as single label"
     * remains distinct from "cancel"
     */
    private enum BinaryMaskChoice { CCA, AS_IS, CANCEL }

    /**
     * Asks the user how to handle a binary-mask input. {@code Yes} runs
     * connected-components, {@code No} accepts the single-label semantics
     * (current behavior: one seed from the foreground union),
     * {@code Cancel} aborts the import.
     */
    private BinaryMaskChoice askAboutBinaryMask(final String imageTitle) {
        final String msg = "<HTML><body style='width:400px'>"
                + "<b>" + imageTitle + "</b> looks like a binary mask (only one distinct non-zero value).<br><br>"
                + "Running this command as-is would produce a single seed from the union of all foreground voxels, "
                + "which is quite unusual. What would you like to do?<br><br>"
                + "<b>Split Into Components</b>: Split into distinct objects via connected-components (26-connectivity "
                + "in 3D, 8-connectivity in 2D), then extract one seed per object<br>"
                + "<b>Import As-Is</b>: One single seed for the whole mask<br>"
                + "<b>Dismiss</b>: Abort the import";
        final Boolean res = new GuiUtils().getConfirmation2(msg, "Binary Mask Detected",
                "Split Into Components", "Import As-Is");
        if (res == null) return BinaryMaskChoice.CANCEL;
        return (res) ? BinaryMaskChoice.CCA :BinaryMaskChoice.AS_IS;
    }

    /**
     * Counts the number of distinct non-zero values in a label image. Used
     * only for the post-CCA status log; not perf-critical.
     */
    private static int countDistinctLabels(final ImagePlus labels) {
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        final ij.ImageStack stack = labels.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            final ij.process.ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < ip.getHeight(); y++) {
                for (int x = 0; x < ip.getWidth(); x++) {
                    final int v = (int) ip.getPixelValue(x, y);
                    if (v != 0) seen.add(v);
                }
            }
        }
        return seen.size();
    }

}
