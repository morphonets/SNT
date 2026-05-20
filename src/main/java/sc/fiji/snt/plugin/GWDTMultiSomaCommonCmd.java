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

import net.imagej.ImgPlus;
import org.scijava.ItemVisibility;
import org.scijava.plugin.Parameter;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.TreeUtils;

import java.util.List;

/**
 * Abstract base for multi-soma GWDT autotracing commands. Provides shared
 * parameters (soma detection, territory control, multi-cell options) and
 * the core {@link #runMultiSoma()} workflow. Subclassed by
 * {@link GWDTMultiSomaCmd} (interactive) and {@link GWDTMultiSomaFileCmd}
 * (batch/file-based).
 *
 * @author Tiago Ferreira
 * @see GWDTMultiSomaCmd
 * @see GWDTMultiSomaFileCmd
 */
public abstract class GWDTMultiSomaCommonCmd extends GWDTTracerCommonCmd {

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER_SOMA = "<HTML><b>Soma Detection";

    @Parameter(label = "Min. soma radius", min = "0", style = "format:#.00",
            description = "<HTML>Minimum soma radius in <b>spatially calibrated</b> units.<br>" +
                    "Smaller detections are filtered out as noise.<br>" +
                    "0 = no filtering (default)")
    private double minSomaRadius = 0;

    @Parameter(label = "Min. inter-soma distance", min = "0", style = "format:#.00",
            description = "<HTML>Minimum distance between soma centers in <b>spatially calibrated</b> units.<br>" +
                    "When &gt; 0, non-maximum suppression removes detections<br>" +
                    "that are too close together, keeping only the strongest.<br>" +
                    "0 = no distance-based filtering (default)")
    private double minSomaDistance = 0;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER_TERRITORY = "<HTML><b>Territory Control";

    @Parameter(label = "Territory reach", min = "-1", max = "1.5", stepSize = "0.1",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>How far each cell's tracing can extend toward its nearest neighbor,<br>" +
                    "as a fraction of the inter-soma distance.<br>" +
                    "<b>0.5</b>: Territories just touch at midpoint.<br>" +
                    "<b>&gt; 0.5</b>: Territories overlap (exclusion mask still prevents re-tracing).<br>" +
                    "<b>-1</b>: Disable territory limits (unlimited spread).<br>" +
                    "Default: 0.5")
    private double caliperFraction = 0.5;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER_MULTI_OPTIONS = "<HTML><b>Multi-Cell Options";

    @Parameter(label = "Exclusion buffer (voxels)", min = "0", max = "20", stepSize = "1",
            style = NumberWidget.SCROLL_BAR_STYLE,
            description = "<HTML>Dilation radius applied around traced regions between passes.<br>" +
                    "After tracing one soma, traced voxels are expanded by this buffer<br>" +
                    "before being excluded from subsequent runs.<br>" +
                    "<b>0</b>: No buffer (only exact traced voxels excluded).<br>" +
                    "Default: 5")
    private int exclusionBuffer = 5;

    @Parameter(label = "Min. paths per cell", min = "1",
            description = "<HTML>Minimum number of paths a traced cell must have to be kept.<br>" +
                    "Cells with fewer paths (e.g., soma-only traces with no neurites)<br>" +
                    "are discarded. Default: 2")
    private int minPathsPerCell = 2;

    /**
     * Initializes multi-soma commands by hiding the single-cell soma/ROI
     * parameters inherited from the parent.
     */
    protected void initMultiSoma() {
        resolveInput("HEADER2");
        resolveInput("somaStrategyChoice");
        resolveInput("roiPlaneOnly");
    }

    @SuppressWarnings("unchecked")
    protected void runMultiSoma() {
        try {
            chosenImp = getImgFromImgChoice();
            if (chosenImp == null || abortRun) return;

            status("Running multi-soma GWDT tracing...", false);
            final double[] spacing = ImgUtils.getSpacing(chosenImp);

            // Step 1: Detect all somas
            snt.setCanvasLabelAllPanes("Detecting somata...");
            final double avgXYSpacing = (spacing[0] + spacing[1]) / 2.0;
            final double minRadiusPx = (minSomaRadius > 0) ? minSomaRadius / avgXYSpacing : 0;
            final double minDistancePx = (minSomaDistance > 0) ? minSomaDistance / avgXYSpacing : 0;

            final int zSlice = (snt.getImagePlus() != null) ? snt.getImagePlus().getZ() - 1 : -1;
            final List<SomaUtils.SomaResult> somas = SomaUtils.detectAllSomas(
                    (ImgPlus) chosenImp, -1, zSlice, minRadiusPx, minDistancePx);
            if (somas.isEmpty()) {
                error("No somata detected. Try adjusting the min. soma radius.");
                return;
            }
            SNTUtils.log("Detected " + somas.size() + " soma(s)");

            // Step 2: Configure tracer (shared parameters from parent + multi-soma specific)
            final AbstractGWDTTracer<?> tracer = createAndConfigureTracer(chosenImp);
            if (tracer == null) return;
            tracer.setCaliperFraction(caliperFraction);
            tracer.setTracedRegionBuffer(exclusionBuffer);

            // Step 3: Trace all cells
            List<Tree> trees = tracer.traceMultiSoma(somas);
            if (trees == null || trees.isEmpty()) {
                error("No paths could be extracted. Check parameters and re-run.");
                return;
            }

            // Step 4: Filter out failed traces
            if (minPathsPerCell > 1) {
                trees = TreeUtils.filterBySize(trees, minPathsPerCell, -1);
            }
            if (trees.isEmpty()) {
                error("All traced cells were filtered out. Try lowering 'Min. paths per cell'.");
                return;
            }

            handleTracedTrees(trees);

        } catch (final Throwable ex) {
            ex.printStackTrace();
            error("An exception occurred. See Console for details.");
        } finally {
            snt.setCanvasLabelAllPanes(null);
        }
    }
}
