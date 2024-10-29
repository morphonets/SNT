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

package sc.fiji.snt.plugin;

import java.util.*;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.DefaultGenericTable;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathStatistics;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

/**
 * Command for measuring Paths in isolation.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label="Measure Path(s)...", initializer = "init")
public class PathAnalyzerCmd extends CommonDynamicCmd {

	private static final String TABLE_TITLE = "SNT Measurements";

	@Parameter(label = "CT position")
	private boolean ct;

	@Parameter(label = "Convex hull")
	private boolean convexHull;

	@Parameter(label = "Contraction")
	private boolean pathContraction;

	@Parameter(label = "Fractal dimension")
	private boolean fractalDimension;

	@Parameter(label = "Extension angles")
	private boolean extensionAngles;

	@Parameter(label = "Length")
	private boolean pathLength;

	@Parameter(label = "Mean radius")
	private boolean pathMeanRadius;

	@Parameter(label = "No. of branch points")
	private boolean nBranchPoints;

	@Parameter(label = "No. of children")
	private boolean nChildren;

	@Parameter(label = "No. of nodes (fragmentation)")
	private boolean pathFragmentation;

	@Parameter(label = "No. of spines/varicosities")
	private boolean pathNSpines;

	@Parameter(label = "Path order")
	private boolean pathOrder;

	@Parameter(label = "Spine/varicosity density")
	private boolean pathSpineDensity;

	@Parameter(label = "Surface area")
	private boolean pathSurfaceArea;

	@Parameter(label = "Volume")
	private boolean pathVolume;

	@Parameter(label = "Width, Height, and Depth")
	private boolean widthHeightDepth;

	@Parameter(label = "Select", choices = {"Choose", "Select All", "Select None", "Reverse Selection"}, callback="actionChoiceSelected")
	private String actionChoice;

	// Options
	@Parameter(label = "<HTML>&nbsp", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(label = "Summarize", description = "<HTML>Retrieve Mean±SD, etc. for selected measurements")
	private boolean summarize;

	@Parameter(required = true)
	private Collection<Path> paths;
	@Parameter(required = false)
	private DefaultGenericTable table;


	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (table == null) {
			table = (sntService.isActive()) ? sntService.getTable() : new SNTTable();
			if (table == null) table = new SNTTable();
		}
		resolveInput("table");
	}

	@SuppressWarnings("unused")
	private void actionChoiceSelected() {
		if (actionChoice.contains("All")) {
			setAllCheckboxesEnabled(true);
		} else if (actionChoice.contains("None")) {
			setAllCheckboxesEnabled(false);
		} else if (actionChoice.contains("Reverse")) {
			toggleCheckboxes();
		}
	}

	private void setAllCheckboxesEnabled(final boolean enable) {
		ct = enable;
		convexHull = enable;
		fractalDimension = enable;
		nBranchPoints = enable;
		nChildren = enable;
		pathContraction = enable;
		pathFragmentation = enable;
		extensionAngles = enable;
		pathLength = enable;
		pathMeanRadius = enable;
		pathNSpines = enable;
		pathOrder = enable;
		pathSpineDensity = enable;
		pathSurfaceArea = enable;
		pathVolume = enable;
		widthHeightDepth = enable;
		actionChoice = "Choose";
	}

	private void toggleCheckboxes() {
		ct = !ct;
		convexHull = !convexHull;
		fractalDimension = !fractalDimension;
		nBranchPoints = !nBranchPoints;
		nChildren = !nChildren;
		pathContraction = !pathContraction;
		pathFragmentation = !pathFragmentation;
		extensionAngles= !extensionAngles;
		pathLength = !pathLength;
		pathMeanRadius = !pathMeanRadius;
		pathNSpines = !pathNSpines;
		pathOrder = !pathOrder;
		pathSpineDensity = !pathSpineDensity;
		pathSurfaceArea = !pathSurfaceArea;
		pathVolume = !pathVolume;
		widthHeightDepth = !widthHeightDepth;
		actionChoice = "Choose";
	}

	@Override
	public void run() {

		final List<String> metrics = new ArrayList<>();
		if (ct) {
			metrics.add(PathStatistics.PATH_CHANNEL);
			metrics.add(PathStatistics.PATH_FRAME);
		}
		if (convexHull) {
			metrics.add(PathStatistics.CONVEX_HULL_SIZE);
			metrics.add(PathStatistics.CONVEX_HULL_ELONGATION);
			metrics.add(PathStatistics.CONVEX_HULL_ROUNDNESS);
		}
		if (fractalDimension) metrics.add(PathStatistics.PATH_FRACTAL_DIMENSION);
		if (nBranchPoints) metrics.add(PathStatistics.N_BRANCH_POINTS);
		if (nChildren) metrics.add(PathStatistics.N_CHILDREN);
		if (pathContraction) metrics.add(PathStatistics.PATH_CONTRACTION);
		if (pathFragmentation) metrics.add(PathStatistics.N_PATH_NODES);
		if (extensionAngles) {
			metrics.add(PathStatistics.PATH_EXT_ANGLE_XY);
			metrics.add(PathStatistics.PATH_EXT_ANGLE_XZ);
			metrics.add(PathStatistics.PATH_EXT_ANGLE_ZY);
			metrics.add(PathStatistics.PATH_EXT_ANGLE_REL_XY);
			metrics.add(PathStatistics.PATH_EXT_ANGLE_REL_XZ);
			metrics.add(PathStatistics.PATH_EXT_ANGLE_REL_ZY);
		}
		if (pathLength) metrics.add(PathStatistics.PATH_LENGTH);
		if (pathMeanRadius) metrics.add(PathStatistics.PATH_MEAN_RADIUS);
		if (pathOrder) metrics.add(PathStatistics.PATH_ORDER);
		if (pathNSpines) metrics.add(PathStatistics.PATH_N_SPINES);
		if (pathSpineDensity) metrics.add(PathStatistics.PATH_SPINE_DENSITY);
		if (pathSurfaceArea) metrics.add(PathStatistics.SURFACE_AREA);
		if (pathVolume) metrics.add(PathStatistics.VOLUME);
		if (widthHeightDepth) {
			metrics.add(PathStatistics.WIDTH);
			metrics.add(PathStatistics.DEPTH);
			metrics.add(PathStatistics.HEIGHT);
		}
		if (metrics.isEmpty()) {
			error("No metrics chosen.");
			return;
		}
		final PathStatistics analyzer = new PathStatistics(paths, "");
		analyzer.setContext(getContext());
		analyzer.setTable(table, TABLE_TITLE);
		Collections.sort(metrics);
		analyzer.measureIndividualPaths(metrics, summarize); // will display table
		resetUI();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		GuiUtils.setLookAndFeel();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final ArrayList<Path> collection = new ArrayList<>();
		collection.add(tree.get(0));
		collection.add(tree.get(5));
		collection.add(tree.get(20));
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", collection);
		ij.command().run(PathAnalyzerCmd.class, true, input);
	}
}
