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
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.seed.LabelsToSeeds;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;

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
        populateImpChoices(null);
    }

    @Override
    public void run() {

        if (impMap == null || choice == null || impMap.isEmpty() || isCanceled()) {
            resetUI();
            return;
        }
        final ImagePlus labels = impMap.get(choice);
        if (labels == null) {
            resetUI();
            return;
        }

        status("Generating seeds from " + labels.getTitle() + "...", false);
        final String src = "labels-image:" + labels.getTitle();
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

}
