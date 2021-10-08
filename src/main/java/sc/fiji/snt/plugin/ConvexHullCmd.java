/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.AbstractConvexHull;
import sc.fiji.snt.analysis.ConvexHull2D;
import sc.fiji.snt.analysis.ConvexHull3D;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.viewer.Annotation3D;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, label = "Convex Hull Analysis...")
public class ConvexHullCmd extends ContextCommand {

    @Parameter
    private OpService opService;
    
    @Parameter
    private SNTService sntService;

    @Parameter
    private DisplayService displayService;

    @Parameter
    private Tree tree;


	@Parameter(label = "<HTML><b>Options:",
			required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER1;

	@Parameter(label = "Display Convex Hull", description = "Should the convex hull be displayed "
			+ "in Reconstruction Viewer/Plotter?")
	private boolean showResult;

	@Parameter(label = "Distinguish Compartments", description = "<HTML><div WIDTH=500>Whether a convex hull should be "
			+ "computed for each cellular compartment (e.g., \"axon\", " + "\"dendrites\", etc.), if any.")
	private boolean splitCompartments;

	@Parameter(label = "<HTML><b>Measurements:",
			required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER2;

    @Parameter(label = "Boundary Size")
    private boolean doBoundarySize;

    @Parameter(label = "Main Elongation")
    private boolean doMainElongation;

    @Parameter(label = "Roundness")
    private boolean doRoundness;

    @Parameter(label = "Size")
    private boolean doSize;


    @Override
    public void run() {
        final boolean is3D = tree.is3D();
        Tree axon;
        Tree dendrite = null;
        splitCompartments = splitCompartments && tree.getSWCTypes().size() > 1;
        if (splitCompartments) {
            axon = tree.subTree("axon");
            dendrite = tree.subTree("dendrite");
            if (axon.isEmpty() && dendrite.isEmpty()) {
                splitCompartments = false;
                axon = tree;
            }
        } else {
            axon = tree;
        }
        SNTTable table = sntService.getTable();
        if (table == null)
        	table = new SNTTable();
        AbstractConvexHull axonHull = null;
        AbstractConvexHull dendriteHull = null;
        final String baseLabel = (tree.getLabel() == null || tree.getLabel().isEmpty()) ? "Tree" : tree.getLabel();
		if (axon != null && !axon.isEmpty()) {
			axonHull = computeHull(axon);
			measure(axonHull, table,  (splitCompartments) ? baseLabel + " Axon" : baseLabel);
		}
		if (dendrite != null && !dendrite.isEmpty()) {
			dendriteHull = computeHull(dendrite);
			measure(dendriteHull, table, baseLabel + " Dendrites");
		}
		if (showResult) {
            if (is3D) {
                final Viewer3D v = sntService.getRecViewer();
                final boolean inputTreeDisplayed = v.getTrees().contains(tree);
                if (axonHull != null) {
                    final String aColor = axon.getProperties().getProperty(Tree.KEY_COLOR, "cyan");
                    if (!inputTreeDisplayed) {
                        if ("cyan".equals(aColor))
                            axon.setColor("cyan");
                        v.add(axon);
                    }
                    final Annotation3D surface = new Annotation3D(((ConvexHull3D)axonHull).getMesh(),
                    		new ColorRGB(aColor), "Convex Hull " + baseLabel + " [axon]");
                    v.add(surface);
                }
                if (dendriteHull != null) {
                    final String dColor = dendrite.getProperties().getProperty(Tree.KEY_COLOR, "orange");
                    if (!inputTreeDisplayed) {
                        if ("orange".equals(dColor))
                            dendrite.setColor("orange");
                        v.add(dendrite);
                    }
                    final Annotation3D surface = new Annotation3D(((ConvexHull3D)dendriteHull).getMesh(),
                    		new ColorRGB(dColor), "Convex Hull " + baseLabel + " [dendrite]");
                    v.add(surface);
                }
                v.show();
            } else {
                final Viewer2D v = new Viewer2D(getContext());
                if (axonHull != null) {
                	v.setDefaultColor(Colors.RED);
                    v.addPolygon(((ConvexHull2D)axonHull).getPoly(), "Convex hull (axon)");
                    v.add(axon, "blue");  // do not change tree color
                }
                if (dendriteHull != null) {
                	v.setDefaultColor(Colors.DARKGREEN);
                    v.addPolygon(((ConvexHull2D)dendriteHull).getPoly(), "Convex hull (dend)");
                    v.add(dendrite, "magenta");
                }
                v.show();
            }
        }
        if (!table.isEmpty()) {
    		final List<Display<?>> displays = displayService.getDisplays(table);
    		if (displays != null && !displays.isEmpty()) {
    			displays.forEach( d -> d.update());
    		} else {
    			displayService.createDisplay("SNT Measurements", table);
    		}
        }
    }

	private void measure(final AbstractConvexHull hull, final SNTTable table, final String rowLabel) {
		table.insertRow(rowLabel);
		if (doSize)
			table.appendToLastRow("Convex hull: Size", hull.size());
		if (doBoundarySize)
			table.appendToLastRow("Convex hull: Boundary size", hull.boundarySize());
		if (doRoundness)
			table.appendToLastRow("Convex hull: Roundness", computeRoundness(hull));
		if (doMainElongation)
			table.appendToLastRow("Convex hull: Elongation", computeElongation(hull));
	}

    private double computeRoundness(AbstractConvexHull hull) {
        if (hull instanceof ConvexHull3D)
            return opService.geom().sphericity(((ConvexHull3D) hull).getMesh()).getRealDouble();
        else if (hull instanceof ConvexHull2D)
            return opService.geom().circularity(((ConvexHull2D) hull).getPoly()).getRealDouble();
        else
            throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
    }

    @SuppressWarnings("unused")
	private double computeBoxivity(AbstractConvexHull hull) {
        // FIXME this does not work in 3D??
        if (hull instanceof ConvexHull3D)
            return opService.geom().boxivity(((ConvexHull3D) hull).getMesh()).getRealDouble();
        else if (hull instanceof ConvexHull2D)
            return opService.geom().boxivity(((ConvexHull2D) hull).getPoly()).getRealDouble();
        else
            throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
    }

    private double computeElongation(AbstractConvexHull hull) {
        if (hull instanceof ConvexHull3D)
            return opService.geom().mainElongation(((ConvexHull3D) hull).getMesh()).getRealDouble();
        else if (hull instanceof ConvexHull2D)
            return opService.geom().mainElongation(((ConvexHull2D) hull).getPoly()).getRealDouble();
        else
            throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
    }

    private AbstractConvexHull computeHull(Tree tree) {
        AbstractConvexHull hull;
        if (tree.is3D()) {
            hull = new ConvexHull3D(getContext(), tree.getNodes(), true);
        } else {
            hull = new ConvexHull2D(getContext(), tree.getNodes(), true);
        }
        hull.compute();
        return hull;
    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService snt = ij.get(SNTService.class);
        Tree t = snt.demoTree("fractal");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("tree", t);
        CommandService cmd = ij.get(CommandService.class);
        cmd.run(ConvexHullCmd.class, true, inputs);
    }

}
