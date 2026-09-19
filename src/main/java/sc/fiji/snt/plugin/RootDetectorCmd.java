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

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.RootDetector;

import java.util.Collection;
import java.util.List;

/**
 * Runs {@link RootDetector} on a collection of {@link Tree}s adding detected root-convergence candidates to
 * SNT's {@link sc.fiji.snt.SeedManager}/{@link SeedOverlay}. Useful for neurites whose own soma wasn't imaged:
 * several unconnected primary paths that converge on a shared, unimaged origin are flagged there rather than
 * reported once per structure.
 *
 * @author Tiago Ferreira
 * @see RootDetector
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Detect Roots...")
public class RootDetectorCmd extends CommonDynamicCmd {

    @Parameter(label = "Convergence radius", min = "0", stepSize = "0.5", style = NumberWidget.SPINNER_STYLE,
            description = "<HTML>Distance threshold (in physical units) within which two unconnected,<br>" +
                    "non-soma primary paths are considered candidates for a shared, unimaged origin.<br>" +
                    "0: Min voxel separation.")
    private double radius = 15.0;

    @Parameter(label = "Weight by node radii",
            description = "<HTML>If checked, a thicker root stub counts more toward a convergence than a<br>" +
                    "thin one, using whatever radii are already recorded. Nodes without a radius<br>" +
                    "simply don't contribute, so this is harmless but useless on paths without radii.")
    private boolean weightByThickness = true;

    @Parameter(label = "Percentile clip (%)", min = "0", max = "45", required = false,
            description = "<HTML>Robustness margin for seed confidence normalization. The Nth/(100-N)th<br>"
                    + "percentile of detection scores are mapped to confidence N/100 and (1-N/100),<br>"
                    + "with outliers beyond that range clamped to [0,1]. Set to 0 for plain min-max<br>"
                    + "normalization.")
    private double percentileClip = 10.0;

    @Parameter(label = "Replace existing seeds",
            description = "<HTML>If checked, existing seeds are replaced.<br>"
                    + "If unchecked, detected convergences are appended.")
    private boolean replace;

    @Parameter
    private Collection<Tree> trees;

    protected void init() {
        super.init(true);
        if (trees == null || trees.isEmpty()) {
            error("No structure to scan.");
        }
        resolveInput("percentileClip"); // simplify prompt for now. Adopt P10 default
        percentileClip = 10d;
    }

    @Override
    public void run() {
        if (isCanceled()) return;
        if (trees == null || trees.isEmpty()) {
            error("No structure to scan.");
            return;
        }
        try {
            status("Detecting root convergences...", false);
            final RootDetector detector = new RootDetector(Math.max(radius, snt.getAverageSeparation()));
            detector.setWeightByThickness(weightByThickness);
            detector.setPercentileClip(percentileClip);
            final List<SeedPoint> seeds = detector.detect(trees);
            if (!seeds.isEmpty()) {
                final SeedOverlay overlay = snt.getSeedOverlay();
                if (replace) overlay.clear();
                overlay.addAll(seeds);
            }
            msg(String.format("%d root convergence candidate(s) detected across %d structure(s).", seeds.size(),
                    trees.size()), "Detection Complete");
        } finally {
            resetUI();
        }
    }
}
