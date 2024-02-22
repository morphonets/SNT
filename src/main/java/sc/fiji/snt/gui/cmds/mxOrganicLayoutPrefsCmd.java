/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.view.mxGraph;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;
import org.scijava.widget.Button;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for setting parameters of the mxOrganicLayout Graph layout used in GraphEditor.
 * Parameter descriptions taken directly from the
 * <a href="https://jgraph.github.io/mxgraph/java/docs/com/mxgraph/layout/mxOrganicLayout.html">mxOrganicLayout javadocs</a>
 *
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, label = "Organic Layout Options")
public class mxOrganicLayoutPrefsCmd extends ContextCommand {

    @Parameter
    private UIService uiService;

    @Parameter
    private PrefService prefService;

    /**
     * The factor by which the <code>moveRadius</code> is multiplied by after
     * every iteration. A value of 0.75 is a good balance between performance
     * and aesthetics. Increasing the value provides more chances to find
     * minimum energy positions and decreasing it causes the minimum radius
     * termination condition to occur more quickly.
     */
    @Parameter(
            label = "Radius Scale Factor",
            min = "0.0",
            description =
                    "<html>" +
                        "The factor by which the <code>moveRadius</code> is multiplied by after<br>" +
                        "every iteration. A value of 0.75 is a good balance between performance<br>" +
                        "and aesthetics. Increasing the value provides more chances to find<br>" +
                        "minimum energy positions and decreasing it causes the minimum radius<br>" +
                        "termination condition to occur more quickly." +
                    "</html>"
    )
    protected double radiusScaleFactor = 0.75;

    /**
     * The radius below which fine-tuning of the layout should start
     * This involves allowing the distance between nodes and edges to be
     * taken into account in the total energy calculation. If this is set to
     * zero, the layout will automatically determine a suitable value
     */
    @Parameter(
            label = "Fine Tuning Radius",
            min = "0.0",
            description =
                    "<html>" +
                        "The radius below which fine tuning of the layout should start<br>" +
                        "This involves allowing the distance between nodes and edges to be<br>" +
                        "taken into account in the total energy calculation. If this is set to<br>" +
                        "zero, the layout will automatically determine a suitable value." +
                    "</html>"
    )
    protected double fineTuningRadius = 40.0;

    /**
     * Limit to the number of iterations that may take place. This is only
     * reached if one of the termination conditions does not occur first.
     */
    @Parameter(
            label = "Max Iterations",
            min = "1",
            description =
                    "<html>" +
                        "Limit to the number of iterations that may take place. This is only<br>" +
                        "reached if one of the termination conditions does not occur first." +
                    "</html>"
    )
    protected int maxIterations = 1000;

    /**
     * Cost factor applied to energy calculations involving the distance
     * nodes and edges. Increasing this value tends to cause nodes to move away
     * from edges, at the partial cost of other graph aesthetics.
     * <code>isOptimizeEdgeDistance</code> must be true for edge to nodes
     * distances to be taken into account.
     */
    @Parameter(
            label="Edge Distance Cost Factor",
            min="0.0",
            description =
                    "<html>" +
                        "Cost factor applied to energy calculations involving the distance<br>" +
                        "nodes and edges. Increasing this value tends to cause nodes to move away<br>" +
                        "from edges, at the partial cost of other graph aesthetics<br>" +
                        "<code>isOptimizeEdgeDistance</code> must be true for edge to nodes<br>" +
                        "distances to be taken into account." +
                    "</html>"
    )
    protected double edgeDistanceCostFactor = 3000;

    /**
     * Cost factor applied to energy calculations involving edges that cross
     * over one another. Increasing this value tends to result in fewer edge
     * crossings, at the partial cost of other graph aesthetics.
     * <code>isOptimizeEdgeCrossing</code> must be true for edge crossings
     * to be taken into account.
     */
    @Parameter(
            label = "Edge Crossing Cost Factor",
            min = "0.0",
            description =
                    "<html>" +
                        "Cost factor applied to energy calculations involving edges that cross<br>" +
                        "over one another. Increasing this value tends to result in fewer edge<br>" +
                        "crossings, at the partial cost of other graph aesthetics.<br>" +
                        "<code>isOptimizeEdgeCrossing</code> must be true for edge crossings<br>" +
                        "to be taken into account." +
                    "</html>"
    )
    protected double edgeCrossingCostFactor = 6000;

    /**
     * Cost factor applied to energy calculations involving the general node
     * distribution of the graph. Increasing this value tends to result in
     * a better distribution of nodes across the available space, at the
     * partial cost of other graph aesthetics.
     * <code>isOptimizeNodeDistribution</code> must be true for this general
     * distribution to be applied.
     */
    @Parameter(
            label = "Node Distribution Cost Factor",
            min = "0.0",
            description =
                    "<html>" +
                        "Cost factor applied to energy calculations involving the general node<br>" +
                        "distribution of the graph. Increasing this value tends to result in<br>" +
                        "a better distribution of nodes across the available space, at the<br>" +
                        "partial cost of other graph aesthetics.<br>" +
                        "<code>isOptimizeNodeDistribution</code> must be true for this general<br>" +
                        "distribution to be applied." +
                    "</html>"
    )
    protected double nodeDistributionCostFactor = 30000;

    /**
     * Cost factor applied to energy calculations for node proximity to the
     * notional border of the graph. Increasing this value results in
     * nodes tending towards the centre of the drawing space, at the
     * partial cost of other graph aesthetics.
     * <code>isOptimizeBorderLine</code> must be true for border
     * repulsion to be applied.
     */
    @Parameter(
            label = "Borderline Cost Factor",
            min = "0.0",
            description =
                    "<html>" +
                        "Cost factor applied to energy calculations for node proximity to the<br>" +
                        "notional border of the graph. Increasing this value results in<br>" +
                        "nodes tending towards the centre of the drawing space, at the<br>" +
                        "partial cost of other graph aesthetics.<br>" +
                        "<code>isOptimizeBorderLine</code> must be true for border<br>" +
                        "repulsion to be applied." +
                    "</html>"
    )
    protected double borderLineCostFactor = 5;

    /**
     * Cost factor applied to energy calculations for the edge lengths.
     * Increasing this value results in the layout attempting to shorten all
     * edges to the minimum edge length, at the partial cost of other graph
     * aesthetics.
     * <code>isOptimizeEdgeLength</code> must be true for edge length
     * shortening to be applied.
     */
    @Parameter(
            label = "Edge Length Cost Factor", min = "0.0",
            description =
                    "<html>" +
                        "Cost factor applied to energy calculations for the edge lengths.<br>" +
                        "Increasing this value results in the layout attempting to shorten all<br> " +
                        "edges to the minimum edge length, at the partial cost of other graph aesthetics." +
                    "</html>"
    )
    protected double edgeLengthCostFactor = 0.02;

    /**
     *  Specifies if the STYLE_NOEDGESTYLE flag should be set on edges that are
     * modified by the result. Default is true.
     */
    @Parameter(
            label="Disable Edge Style",
            description =
                    "<html>" +
                        "Specifies if the STYLE_NOEDGESTYLE flag should be set on edges that are<br>" +
                        "modified by the result. Default is true." +
                    "</html>"
    )
    protected boolean disableEdgeStyle = true;

    /**
     * Specifies if all edge points of traversed edges should be removed.
     * Default is true.
     */
    @Parameter(
            label = "Reset Edges",
            description =
                    "<html>" +
                        "Specifies if all edge points of traversed edges should be removed.<br>" +
                        "Default is true." +
                    "</html>"
    )
    protected boolean resetEdges = true;

    @Parameter(label = "adapter", required = false, persist = false)
    private mxGraph adapter;

    @Parameter(label="Preview", callback = "previewLayout")
    private Button preview;

    @Parameter(label="Reset All Preferences...", callback="reset")
    private Button reset;

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        prefService.put(this.getClass(), "radiusScaleFactor", radiusScaleFactor);
        prefService.put(this.getClass(), "fineTuningRadius", fineTuningRadius);
        prefService.put(this.getClass(), "maxIterations", maxIterations);
        prefService.put(this.getClass(), "edgeDistanceCostFactor", edgeDistanceCostFactor);
        prefService.put(this.getClass(), "edgeCrossingCostFactor", edgeCrossingCostFactor);
        prefService.put(this.getClass(), "nodeDistributionCostFactor", nodeDistributionCostFactor);
        prefService.put(this.getClass(), "borderLineCostFactor",  borderLineCostFactor);
        prefService.put(this.getClass(), "edgeLengthCostFactor", edgeLengthCostFactor);
        prefService.put(this.getClass(), "disableEdgeStyle", disableEdgeStyle);
        prefService.put(this.getClass(), "resetEdges", resetEdges);
    }

    @SuppressWarnings("unused")
    private void previewLayout() {
        if (adapter == null) {
            uiService.showDialog("Cannot preview. Invalid Graph.");
            return;
        }
        Object cell = adapter.getSelectionCell();

        if (cell == null || adapter.getModel().getChildCount(cell) == 0) {
            cell = adapter.getDefaultParent();
        }

        adapter.getModel().beginUpdate();
        try {
            mxOrganicLayout organicLayout = new mxOrganicLayout(adapter);
            organicLayout.setRadiusScaleFactor(radiusScaleFactor);
            organicLayout.setFineTuningRadius(fineTuningRadius);
            organicLayout.setMaxIterations(maxIterations);
            organicLayout.setEdgeDistanceCostFactor(edgeDistanceCostFactor);
            organicLayout.setEdgeCrossingCostFactor(edgeCrossingCostFactor);
            organicLayout.setNodeDistributionCostFactor(nodeDistributionCostFactor);
            organicLayout.setBorderLineCostFactor(borderLineCostFactor);
            organicLayout.setEdgeLengthCostFactor(edgeLengthCostFactor);
            organicLayout.setDisableEdgeStyle(disableEdgeStyle);
            organicLayout.setResetEdges(resetEdges);
            organicLayout.execute(cell);
        } finally {
            adapter.getModel().endUpdate();
        }

    }

    @SuppressWarnings("unused")
    private void reset() {
        final DialogPrompt.Result result = uiService.showDialog(
                "Reset parameters to defaults?",
                DialogPrompt.MessageType.QUESTION_MESSAGE);
        if (DialogPrompt.Result.YES_OPTION == result || DialogPrompt.Result.OK_OPTION == result) {
            setDefaults();
        }
    }

    private void setDefaults() {
        radiusScaleFactor = 0.75;
        fineTuningRadius = 40.0;
        maxIterations = 1000;
        edgeDistanceCostFactor = 3000;
        edgeCrossingCostFactor = 6000;
        nodeDistributionCostFactor = 30000;
        borderLineCostFactor = 5;
        edgeLengthCostFactor = 0.02;
        disableEdgeStyle = true;
        resetEdges = true;
    }

    /* IDE debug method **/
    public static void main(final String[] args) {
        GuiUtils.setLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ij.command().run(mxOrganicLayoutPrefsCmd.class, true);
    }

}
