/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.DefaultGenericTable;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.PathAnalyzer;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

/**
 * Command for measuring Paths in isolation.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label="Measure Path(s)...", initializer = "init")
public class PathAnalyzerCmd extends CommonDynamicCmd {

	private static final String TABLE_TITLE = "SNT Measurements";

	@Parameter(label = MultiTreeStatistics.LENGTH)
	private boolean cableLength;

	@Parameter(label = "Average path length")
	private boolean avgLength;

	@Parameter(label = MultiTreeStatistics.N_BRANCH_POINTS)
	private boolean nBranchPoints;

	@Parameter(label = MultiTreeStatistics.N_TIPS)
	private boolean nTips;

	@Parameter(label = MultiTreeStatistics.AVG_CONTRACTION)
	private boolean avgContraction;

	@Parameter(label = MultiTreeStatistics.AVG_FRAGMENTATION)
	private boolean avgFragmentation;

	@Parameter(label = MultiTreeStatistics.AVG_FRACTAL_DIMENSION)
	private boolean avgFractalDimension;

	@Parameter(label = MultiTreeStatistics.HIGHEST_PATH_ORDER)
	private boolean highestPathOrder;

	@Parameter(label = MultiTreeStatistics.MEAN_RADIUS)
	private boolean meanRadius;

	@Parameter(label = MultiTreeStatistics.N_PATHS)
	private boolean nPaths;

	@Parameter(label = MultiTreeStatistics.WIDTH)
	private boolean width;

	@Parameter(label = MultiTreeStatistics.HEIGHT)
	private boolean height;

	@Parameter(label = MultiTreeStatistics.DEPTH)
	private boolean depth;

	@Parameter(label = "Select", choices = {"Choose", "Select All", "Select None"}, callback="actionChoiceSelected")
	private String actionChoice;

	// Options
	@Parameter(label = "<HTML>&nbsp", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(label = "Description", persist = false, description = "An optional identifier describing the path(s) being measured")
	private String label;

	@Parameter(required = true)
	private Collection<Path> paths;
	@Parameter(required = false)
	private DefaultGenericTable table;
	@Parameter(required = false)
	private String proposedLabel;


	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (table == null) {
			table = (sntService.isActive()) ? sntService.getTable() : new DefaultGenericTable();
			if (table == null) table = new DefaultGenericTable();
		}
		resolveInput("table");
		label = (proposedLabel == null) ? "" : proposedLabel;
		resolveInput("proposedLabel");
	}

	@SuppressWarnings("unused")
	private void actionChoiceSelected() {
		if (actionChoice.contains("All")) {
			setAllCheckboxesEnabled(true);
		} else if (actionChoice.contains("None")) {
			setAllCheckboxesEnabled(false);
		}
	}

	private void setAllCheckboxesEnabled(final boolean enable) {
		avgContraction = enable;
		avgFractalDimension = enable;
		avgFragmentation = enable;
		avgLength = enable;
		cableLength = enable;
		highestPathOrder = enable;
		meanRadius = enable;
		nBranchPoints = enable;
		nTips = enable;
		nPaths = enable;
		width = enable;
		height = enable;
		depth = enable;
		actionChoice = "Choose";
	}

	@Override
	public void run() {

		final List<String> metrics = new ArrayList<>();
		if(cableLength) metrics.add(MultiTreeStatistics.LENGTH);
		if (avgLength) metrics.add(MultiTreeStatistics.AVG_BRANCH_LENGTH);
		if(meanRadius) metrics.add(MultiTreeStatistics.MEAN_RADIUS);
		if (nPaths) metrics.add(MultiTreeStatistics.N_PATHS);
		if (nBranchPoints) metrics.add(MultiTreeStatistics.N_BRANCH_POINTS);
		if (nTips) metrics.add(MultiTreeStatistics.N_TIPS);
		if (highestPathOrder) metrics.add(MultiTreeStatistics.HIGHEST_PATH_ORDER);
		if (avgContraction) metrics.add(MultiTreeStatistics.AVG_CONTRACTION);
		if(avgFractalDimension) metrics.add(MultiTreeStatistics.AVG_FRACTAL_DIMENSION);
		if (avgFragmentation) metrics.add(MultiTreeStatistics.AVG_FRAGMENTATION);
		if (width) metrics.add(MultiTreeStatistics.WIDTH);
		if (height) metrics.add(MultiTreeStatistics.HEIGHT);
		if (depth) metrics.add(MultiTreeStatistics.DEPTH);
		if (metrics.isEmpty()) {
			error("No metrics chosen.");
			return;
		}
		final PathAnalyzer analyzer = new PathAnalyzer(paths, (label == null) ? "" : label);
		analyzer.setContext(getContext());
		analyzer.setTable(table, TABLE_TITLE);
		analyzer.measure(label, metrics, false); // will display table
		resetUI();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", tree);
		ij.command().run(PathAnalyzerCmd.class, true, (Map<String, Object>)null);
	}
}
