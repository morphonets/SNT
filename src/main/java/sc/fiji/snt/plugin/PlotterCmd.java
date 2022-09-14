/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.log.LogService;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import org.scijava.widget.FileWidget;
import org.scijava.widget.NumberWidget;

import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.ConvexHull2D;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Implements Reconstruction Plotter, a command wrapper for interactively
 * plotting trees using {@link Viewer2D}
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = {
		@Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
		@Menu(label = "Neuroanatomy", weight = GuiUtils.DEFAULT_MENU_WEIGHT), //
		@Menu(label = "Reconstruction Plotter...") }, //
		initializer = "init")
public class PlotterCmd extends CommonDynamicCmd implements Interactive {

	@Parameter
	LogService logService;

	@Parameter(required = false, label = "X rotation",
		description = "Rotation angle in degrees", min = "0", max = "360",
		stepSize = "10", style = NumberWidget.SCROLL_BAR_STYLE,
		callback = "currentXangleChanged")
	private double angleX = 0;

	@Parameter(required = false, label = "Y rotation",
		description = "Rotation angle in degrees", min = "0", max = "360",
		stepSize = "10", style = NumberWidget.SCROLL_BAR_STYLE,
		callback = "currentYangleChanged")
	private double angleY = 0;

	@Parameter(required = false, label = "Z rotation",
		description = "Rotation angle in degrees", min = "0", max = "360",
		stepSize = "10", style = NumberWidget.SCROLL_BAR_STYLE,
		callback = "currentZangleChanged")
	private double angleZ = 0;

	@Parameter(required = false, label = "Color", callback = "colorChanged")
	private ColorRGB color;

	@Parameter(required = false, persist = false, label = "Actions", choices = {
			ACTION_NONE, ACTION_ADD_CONVEXHULL, ACTION_FLIP_H, ACTION_FLIP_V, ACTION_RESET_ROT,
			ACTION_RESET_COLOR, ACTION_SNAPSHOT}, callback = "runAction")
	private String actionChoice = ACTION_NONE;

	@Parameter(required = false, persist = false, label = "Preview",
		callback = "updatePlot",
		description = "NB: UI may become sluggish while previewing large reconstructions...")
	private boolean preview = true;

	@Parameter(required = false, persist = false,
		visibility = ItemVisibility.MESSAGE)
	private String msg = "";

	@Parameter(required = true)
	private Tree tree;

	private static final String ACTION_NONE = "Choose...";
	private static final String ACTION_RESET_ROT = "Reset rotation";
	private static final String ACTION_RESET_COLOR = "Reset color";
	private static final String ACTION_FLIP_H = "Flip horizontally";
	private static final String ACTION_FLIP_V = "Flip vertically";
	private static final String ACTION_SNAPSHOT = "Snapshot [w/ color-mapping (if any)]";
	private static final String ACTION_ADD_CONVEXHULL = "Add convex hull";
	private static final ColorRGB DEF_COLOR = Colors.BLACK;
	private static final String BUSY_MSG = "Rendering. Please wait...";

	private Viewer2D viewer;
	private SNTChart chart;
	private Tree plottingTree;
	private Tree snapshotTree;
	private ConvexHull2D hull2D;
	private int previousXangle = 0;
	private int previousYangle = 0;
	private int previousZangle = 0;

	protected void init() {
		if (tree == null) {
			resolveInput("tree");
			statusService.showStatus(
				"Please select one or more reconstruction files");
			final FileFilter filter = (file) -> {
				final String lName = file.getName().toLowerCase();
				return lName.endsWith("swc") || lName.endsWith(".traces") || lName
					.endsWith(".json");
			};
			final List<File> files = uiService.chooseFiles(new File(System
				.getProperty("user.home")), new ArrayList<File>(), filter,
				FileWidget.OPEN_STYLE);
			if (files == null || files.isEmpty()) {
				cancel("");
				return;
			}
			tree = new Tree();
			final ColorRGB[] colors = SNTColor.getDistinctColors(files.size());
			int counter = 0;
			for (final File f : files) {
				try {
					final Tree tempTree = new Tree(f.getAbsolutePath());
					if (tempTree.isEmpty()) throw new IllegalArgumentException(
						"Invalid file?");
					tempTree.setColor(colors[counter++]);
					tree.merge(tempTree);
				}
				catch (final IllegalArgumentException exc) {
					logService.warn(f.getName() + ": " + exc.getMessage());
				}
			}
			if (tree.isEmpty()) {
				error("No reconstructions imported.");
			}
			else {
				logService.info("" + counter +
					" reconstruction(s) successfully imported ");
			}
		}
		if (color == null)
			color = DEF_COLOR;
	}

	@Override
	public void run() {

		if (tree == null || tree.isEmpty()) {
			error("No paths to plot");
			return;
		}
		GuiUtils.setLookAndFeel();
		status("Building Plot...", false);
		// Tree rotation occurs in place so we'll copy plotting coordinates
		// to a new Tree. To avoid rotation lags we'll keep it monochrome,
		// We'll store input colors to be restored by the 'snapshot' action
		plottingTree = tree.clone();
		snapshotTree = tree.clone();
		plottingTree.setColor(color);
		buildPlot();
		chart = viewer.getChart();
		chart.setSize(500, 500);
		chart.addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				GuiUtils.restoreLookAndFeel();
				super.windowClosing(e);
			}
		});
		chart.show();
		status(null, false);

	}

	private void buildPlot() {
		viewer = new Viewer2D(context(), viewer);
		viewer.add(plottingTree);
		if (hull2D != null) {
			hull2D.compute();
			viewer.setDefaultColor(color);
			viewer.addPolygon(hull2D.getPolygon(), plottingTree.getLabel());
		}
	}

	private void updatePlot(final boolean resetConvexHull) {
		if (preview && msg.isEmpty() && hasInitialized()) {
			msg = BUSY_MSG;
			plottingTree.setColor(color);
			if (resetConvexHull)
				hull2D = null;
			buildPlot();
			chart.replace(viewer.getChart());
			chart.setVisible(true); // re-open frame if it has been closed
			viewer.setGridlinesVisible(chart.isGridlinesVisible());
			viewer.setOutlineVisible(chart.isOutlineVisible());
			//frame.toFront();
			msg = "";
		}
	}

	private void resetRotation() {
		plottingTree.rotate(Tree.X_AXIS, -angleX);
		plottingTree.rotate(Tree.Y_AXIS, -angleY);
		plottingTree.rotate(Tree.Z_AXIS, -angleZ);
		updatePlot(true);
		angleX = 0;
		angleY = 0;
		angleZ = 0;
		previousXangle = 0;
		previousYangle = 0;
		previousZangle = 0;
	}

	@SuppressWarnings("unused")
	private void currentXangleChanged() {
		if (hasInitialized()) {
			plottingTree.rotate(Tree.X_AXIS, angleX - previousXangle);
			previousXangle = (int)angleX;
			updatePlot(true);
		}
	}

	@SuppressWarnings("unused")
	private void currentYangleChanged() {
		if (hasInitialized()) {
			plottingTree.rotate(Tree.Y_AXIS, angleY - previousYangle);
			previousYangle = (int)angleY;
			updatePlot(true);
		}
	}

	@SuppressWarnings("unused")
	private void currentZangleChanged() {
		if (hasInitialized()) {
			plottingTree.rotate(Tree.Z_AXIS, angleZ - previousZangle);
			previousZangle = (int)angleZ;
			updatePlot(true);
		}
	}

	@SuppressWarnings("unused")
	private void colorChanged() {
		updatePlot(false);
	}

	private boolean hasInitialized() {
		if (plottingTree == null) {
			new GuiUtils().error("Somehow Plotter could not be initialized. "
					+ "Chosen structure may not be renderable as is, and paths may need to be rebuild?");
			return false;
		}
		return true;
	}

	private int getBoundedAngle(int angle) {
		if (angle >= 360) angle -= 360;
		if (angle < 0) angle += 360;
		return angle;
	}

	@SuppressWarnings("unused")
	private void runAction() {
		if (!hasInitialized()) return;
		switch (actionChoice) {
			case ACTION_NONE:
				return;
			case ACTION_RESET_ROT:
				resetRotation();
				actionChoice = ACTION_NONE;
				return;
			case ACTION_RESET_COLOR:
				color = DEF_COLOR;
				updatePlot(false);
				actionChoice = ACTION_NONE;
				return;
			case ACTION_SNAPSHOT:
				snapshot();
				actionChoice = ACTION_NONE;
				return;
			case ACTION_FLIP_V:
				angleX = getBoundedAngle(180 + (int)angleX);
				plottingTree.rotate(Tree.X_AXIS, angleX);
				previousXangle = (int)angleX;
				updatePlot(true);
				break;
			case ACTION_FLIP_H:
				angleY = getBoundedAngle(180 + (int)angleY);
				plottingTree.rotate(Tree.Y_AXIS, angleY);
				previousYangle = (int)angleY;
				updatePlot(true);
				break;
			case ACTION_ADD_CONVEXHULL:
				hull2D = new ConvexHull2D(plottingTree.getNodes(), true);
				updatePlot(false);
				break;
			default:
				throw new IllegalArgumentException("Invalid action");
		}
		actionChoice = ACTION_NONE;
	}

	private void snapshot() {
		// apply input tree colors
		msg = BUSY_MSG;
		for (int i = 0; i < plottingTree.size(); i++) {
			final Path plottingPath = plottingTree.list().get(i);
			final Path inputPath = snapshotTree.list().get(i);
			plottingPath.setColor(inputPath.getColor());
			plottingPath.setNodeColors(inputPath.getNodeColors());
		}
		buildPlot();
		viewer.setTitle("[X " + angleX + "deg Y " + angleY + "deg Z " + angleZ +
			"deg]");
		final SNTChart snapshotChart = viewer.getChart();
		snapshotChart.applyStyle(chart);
		snapshotChart.show();
		// make tree monochrome
		for (final Path p : plottingTree.list()) {
			p.setColor((java.awt.Color)null);
			p.setNodeColors(null);
		}
		msg = "";
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		final Tree tree = sntService.demoTrees().get(0);
		input.put("tree", tree);
		ij.command().run(PlotterCmd.class, true, input);
	}

}
