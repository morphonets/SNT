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

import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.ui.UIService;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.AbstractConvexHull;
import sc.fiji.snt.analysis.ConvexHull2D;
import sc.fiji.snt.analysis.ConvexHull3D;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.viewer.Annotation3D;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Cameron Arshadi
 */
public class ConvexHullCmd extends ContextCommand {

    @Parameter
    private UIService uiService;

    @Parameter
    private OpService opService;

    @Parameter
    private Tree tree;

    @Parameter(label = "Distinguish Compartments")
    private boolean splitCompartments;

    @Parameter(label = "Display Hull")
    private boolean showResult;

    @Parameter(label = "Size")
    private boolean doSize;

    @Parameter(label = "Boundary Size")
    private boolean doBoundarySize;

    @Parameter(label = "Roundness")
    private boolean doRoundness;

    @Parameter(label = "Main Elongation")
    private boolean doMainElongation;

    @Override
    public void run() {
        final boolean is3D = tree.is3D();
        Tree axon;
        Tree dendrite = null;
        if (splitCompartments) {
            axon = tree.subTree("axon");
            dendrite = tree.subTree("dendrite");
            if (axon.isEmpty() && dendrite.isEmpty())
                axon = tree;
        } else {
            axon = tree;
        }
        SNTTable table = new SNTTable();
        AbstractConvexHull axonHull = null;
        AbstractConvexHull dendriteHull = null;
        if (axon != null && !axon.isEmpty()) {
            axonHull = computeHull(axon);
            String label = splitCompartments ? "Axon" :
                    ((axon.getLabel() == null || axon.getLabel().isEmpty()) ? "Tree" : axon.getLabel());
            table.insertRow(label);
            if (doSize)
                table.set("Convex hull: size", label, axonHull.size());
            if (doBoundarySize)
                table.set("Convex hull: boundary size", label, axonHull.boundarySize());
            if (doRoundness)
                table.set("Convex hull: roundness", label, computeRoundness(axonHull));
            if (doMainElongation)
                table.set("Convex hull: elongation", label, computeElongation(axonHull));
        }
        if (dendrite != null && !dendrite.isEmpty()) {
            dendriteHull = computeHull(dendrite);
            table.insertRow("Dendrite");
            if (doSize)
                table.set("Convex hull: size", "Dendrite", dendriteHull.size());
            if (doBoundarySize)
                table.set("Convex hull: boundary size", "Dendrite", dendriteHull.boundarySize());
            if (doRoundness)
                table.set("Convex hull: roundness", "Dendrite", computeRoundness(dendriteHull));
            if (doMainElongation)
                table.set("Convex hull: elongation", "Dendrite", computeElongation(dendriteHull));
        }
        if (showResult) {
            if (is3D) {
                Viewer3D v = new Viewer3D(getContext());
                if (axonHull != null)
                    axon.setColor("cyan");
                    v.add(axon);
                    v.add(Annotation3D.meshToDrawable(((ConvexHull3D)axonHull).getMesh()));
                if (dendriteHull != null) {
                    dendrite.setColor("orange");
                    v.add(dendrite);
                    v.add(Annotation3D.meshToDrawable(((ConvexHull3D)dendriteHull).getMesh()));
                }
                v.show();
            } else {
                Viewer2D v = new Viewer2D(getContext());
                if (axonHull != null) {
                    v.addPolygon(((ConvexHull2D)axonHull).getPoly(), "Convex hull");
                    axon.setColor("cyan");
                    v.add(axon);
                }
                if (dendriteHull != null) {
                    v.addPolygon(((ConvexHull2D)dendriteHull).getPoly(), "Convex hull");
                    dendrite.setColor("orange");
                    v.add(dendrite);
                }
                v.show();
            }
        }
        if (!table.isEmpty())
            uiService.show(table);
    }

    private double computeRoundness(AbstractConvexHull hull) {
        if (hull instanceof ConvexHull3D)
            return opService.geom().sphericity(((ConvexHull3D) hull).getMesh()).getRealDouble();
        else if (hull instanceof ConvexHull2D)
            return opService.geom().circularity(((ConvexHull2D) hull).getPoly()).getRealDouble();
        else
            throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
    }

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
