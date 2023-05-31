/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import java.util.Collection;
import java.util.Collections;
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
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.AbstractConvexHull;
import sc.fiji.snt.analysis.ConvexHullAnalyzer;
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
	private SNTService sntService;

	@Parameter
	private DisplayService displayService;

	@Parameter
	private Collection<Tree> trees;
	@Parameter(required = false)
	private Viewer3D recViewer;
	@Parameter(required = false, persist = false)
	private boolean calledFromRecViewerInstance;
	@Parameter(required = false)
	private Viewer2D recPlotter;
	@Parameter(required = false)
	private SNTTable table;

	@Parameter(label = "<HTML><b>Options:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER1;

	@Parameter(label = "Display Convex Hull", description = "Should the convex hull be displayed "
			+ "in Reconstruction Viewer/Plotter?")
	private boolean showResult;

	@Parameter(label = "Distinguish Compartments", description = "<HTML><div WIDTH=500>Whether a convex hull should be "
			+ "computed for each cellular compartment (e.g., \"axon\", " + "\"dendrites\", etc.), if any.")
	private boolean splitCompartments;

	@Parameter(label = "<HTML><b>Measurements:", required = false, visibility = ItemVisibility.MESSAGE)
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
		if (trees == null || trees.isEmpty()) {
			cancel("At least a reconstruction is required but none was found.");
			return;
		}
		if (table == null) {
			table = (sntService.isActive()) ? table = sntService.getTable() : new SNTTable();
		}
		trees.forEach(tree -> run(tree));
		if (showResult) {
			if (recPlotter != null)
				recPlotter.show();
			if (recViewer != null)
				recViewer.show();
		}
	}

	private void run(final Tree tree) {
		final boolean is3D = tree.is3D();
		Tree axon;
		Tree dendrite = null;
		boolean splitByType = splitCompartments && tree.getSWCTypes(false).size() > 1;
		if (splitByType) {
			axon = tree.subTree("axon");
			dendrite = tree.subTree("dendrite");
			if (axon.isEmpty() && dendrite.isEmpty()) {
				splitByType = false;
				axon = tree;
			}
		} else {
			axon = tree;
		}

		ConvexHullAnalyzer axonAnalyzer = null;
		ConvexHullAnalyzer dendriteAnalyzer = null;
		final String baseLabel = (tree.getLabel() == null || tree.getLabel().isEmpty()) ? "Tree" : tree.getLabel();
		if (axon != null && !axon.isEmpty()) {
			axonAnalyzer = getAnalyzer(axon);
			measure(axonAnalyzer, table, (splitByType) ? baseLabel + " Axon" : baseLabel);
		}
		if (dendrite != null && !dendrite.isEmpty()) {
			dendriteAnalyzer = getAnalyzer(dendrite);
			measure(dendriteAnalyzer, table, baseLabel + " Dendrites");
		}
		if (showResult) {
			if (is3D || calledFromRecViewerInstance) {
				if (recViewer == null) { // should never happen if calledFromRecViewerInstance
					recViewer = sntService.getRecViewer();
				}
				final boolean inputTreeDisplayed = recViewer.getTrees().contains(tree);
				if (axonAnalyzer != null) {
					final String aColor = axon.getProperties().getProperty(Tree.KEY_COLOR, "cyan");
					if (!inputTreeDisplayed) {
						recViewer.add(axon);
					}
					addHullToRecViewer(axonAnalyzer.getHull(), (splitByType) ? baseLabel + " Axon" : baseLabel, aColor);
				}
				if (dendriteAnalyzer != null) {
					final String dColor = dendrite.getProperties().getProperty(Tree.KEY_COLOR, "orange");
					if (!inputTreeDisplayed) {
						recViewer.addTree(dendrite);
					}
					addHullToRecViewer(dendriteAnalyzer.getHull(), baseLabel + " Dendrites", dColor);
				}
			} else {
				if (recPlotter == null) {
					recPlotter = new Viewer2D(getContext());
				}
				if (axonAnalyzer != null) {
					recPlotter.setDefaultColor(Colors.RED);
					recPlotter.addPolygon(((ConvexHull2D) axonAnalyzer.getHull()).getPolygon(), "Convex hull (axon)");
					recPlotter.add(axon, "blue");
				}
				if (dendriteAnalyzer != null) {
					recPlotter.setDefaultColor(Colors.DARKGREEN);
					recPlotter.addPolygon(((ConvexHull2D) dendriteAnalyzer.getHull()).getPolygon(),
							"Convex hull (dend)");
					recPlotter.add(dendrite, "magenta");
				}
			}
		}
		if (!table.isEmpty()) {
			final List<Display<?>> displays = displayService.getDisplays(table);
			if (displays != null && !displays.isEmpty()) {
				displays.forEach(d -> d.update());
			} else {
				displayService.createDisplay("SNT Measurements", table);
			}
		}
	}

	private void addHullToRecViewer(final AbstractConvexHull hull, final String identifier, final String color) {
		Annotation3D surface;
		if (hull instanceof ConvexHull3D) {
			surface = new Annotation3D(((ConvexHull3D) hull).getMesh(), new ColorRGB(color),
					"Convex Hull " + identifier);
		} else if (hull instanceof ConvexHull2D) {
			surface = new Annotation3D(((ConvexHull2D) hull).getPolygon(), new ColorRGB(color),
					"Convex Hull " + identifier);
		} else {
			throw new IllegalArgumentException("Unsupported ConvexHull");
		}
		recViewer.add(surface);
	}

	private void measure(final ConvexHullAnalyzer analyzer, final SNTTable table, final String rowLabel) {
		try {
			table.insertRow(rowLabel);
			final String unit = (String) analyzer.getTree().getProperties().getOrDefault(Tree.KEY_SPATIAL_UNIT,
					"? units");
			final boolean is3D = analyzer.getHull() instanceof ConvexHull3D;
			if (doSize)
				table.appendToLastRow("Convex hull: Size (" + unit + ((is3D) ? "^3" : "^2") + ")", analyzer.getSize());
			if (doBoundarySize)
				table.appendToLastRow("Convex hull: Boundary size (" + unit + ((is3D) ? "^2" : "") + ")",
						analyzer.getBoundarySize());
			if (doRoundness)
				table.appendToLastRow("Convex hull: Roundness", analyzer.getRoundness());
			if (doMainElongation)
				table.appendToLastRow("Convex hull: Boundary size (" + unit + ")",
						analyzer.getElongation());
		} catch (final IndexOutOfBoundsException | IllegalArgumentException ex) {
			ex.printStackTrace();
		}
	}

	private ConvexHullAnalyzer getAnalyzer(Tree tree) {
		final ConvexHullAnalyzer analyzer = new ConvexHullAnalyzer(tree);
		analyzer.setContext(getContext());
		return analyzer;
	}

	public static void main(String[] args) {
		ImageJ ij = new ImageJ();
		ij.ui().showUI();
		SNTService snt = ij.get(SNTService.class);
		Tree t = snt.demoTree("fractal");
		Map<String, Object> inputs = new HashMap<>();
		inputs.put("trees", Collections.singleton(t));
		CommandService cmd = ij.get(CommandService.class);
		cmd.run(ConvexHullCmd.class, true, inputs);
	}

}
