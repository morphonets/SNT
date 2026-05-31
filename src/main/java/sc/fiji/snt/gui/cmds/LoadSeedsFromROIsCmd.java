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

import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.seed.SeedRois;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;

import java.util.List;

/**
 * Generates {@link SeedPoint}s from the contents of the {@link RoiManager}.
 * One seed per area / line / point-like ROI (point ROIs expand to one seed per
 * contained point). Geometry rules and coordinate conventions live in {@link SeedRois}.
 *
 * @author Tiago Ferreira
 * @see SeedRois
 */
@Plugin(type = Command.class, label = "Generate Seeds from ROI Manager...")
public class LoadSeedsFromROIsCmd extends CommonDynamicCmd {

    @Parameter(label = "Type label",
            description = "<HTML>Value assigned to each seed's <code>type</code> field "
                    + "(e.g. <i>soma</i>, <i>endpoint</i>, <i>waypoint</i>).")
    private String typeLabel = "user-drawn";

    @Parameter(label = "Confidence", min = "0", max = "1",
            stepSize = "0.05", style = NumberWidget.SLIDER_STYLE,
            description = "<HTML>Confidence assigned to every produced seed. "
                    + "Defaults to <code>1.0</code>;<br>"
                    + "Lower it to visually separate ROI-derived seeds from "
                    + "detector-derived ones in the confidence filter.")
    private double confidence = 1.0;

    @Parameter(label = "Replace existing seeds",
            description = "<HTML>If checked, the existing seed overlay is replaced.<br>"
                    + "If unchecked, new seeds are appended.")
    private boolean replace;

    @Override
    public void run() {
        super.init(true);
        if (isCanceled()) return;

        final RoiManager rm = RoiManager.getInstance2();
        if (rm == null || rm.getCount() == 0) {
            error("ROI Manager is either closed or empty.");
            return;
        }
        final Roi[] rois = rm.getRoisAsArray();
        status("Generating seeds from " + rois.length + " ROI(s)...", false);

        final List<SeedPoint> seeds;
        try {
            seeds = SeedRois.toSeeds(rois, snt.getImagePlus(), confidence, typeLabel, "roi");
        } catch (final RuntimeException ex) {
            error("Failed to convert ROIs to seeds: " + ex.getMessage());
            return;
        }
        if (seeds.isEmpty()) {
            error("No seeds could be produced from the " + rois.length
                    + " ROI(s) in the manager.");
            return;
        }

        final SeedOverlay overlay = snt.getSeedOverlay();
        if (replace) overlay.clear();
        overlay.addAll(seeds);
        status(seeds.size() + " seed(s) generated from " + rois.length + " ROI(s).", true);
        resetUI();
    }
}
