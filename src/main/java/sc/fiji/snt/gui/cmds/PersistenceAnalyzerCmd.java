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

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PersistenceAnalyzer;
import sc.fiji.snt.analysis.SNTTable;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GUI command persistence homology analyses.
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Persistence Homology")
public class PersistenceAnalyzerCmd extends ContextCommand {

	@Parameter
	private LogService logService;

	@Parameter(label = "Descriptor(s)", choices = {"TMD barcodes", "Persistence landscape",
			"TMD barcodes and Persistence landscape"})
	private String outputChoice;

	@Parameter(label = "Descriptor function", choices = {"Radial (default)", "Geodesic", "Centrifugal", "Path order",
			"X", "Y", "Z"})
	private String descriptorChoice;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<HTML>&nbsp;")
	private String SPACER;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = "<HTML><b>Landscape Options:")
	private String HEADER1;

	@Parameter(label = "No. of landscapes", min = "1", max = "500", style = NumberWidget.SCROLL_BAR_STYLE,
			description = "<HTML>The number of piecewise-linear functions.<br>Only considered if <i>Descriptor(s)</i>" +
					" includes persistence landscape")
	private int landscapeNum;

	@Parameter(label = "Resolution", min = "1", max = "500", style = NumberWidget.SCROLL_BAR_STYLE,
			description = "<HTML>The number of samples for all piecewise-linear functions.<br>" +
					"Only considered if <i>Descriptor(s)</i> includes persistence landscape")
	private int landscapeRes;

	@Parameter(required = false)
	private Collection<Tree> trees;

	boolean includeLandscape;
	boolean includeTMD;
	String descriptor;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			cancel("No valid reconstruction(s) to parse.");
			return;
		}
		includeTMD = outputChoice == null || outputChoice.toLowerCase().contains("tmd");
		includeLandscape = outputChoice != null && outputChoice.toLowerCase().contains("landscape");
		descriptor = descriptorChoice.split(" ")[0].toLowerCase();
		final SNTTable table = new SNTTable();
		final AtomicBoolean failure = new AtomicBoolean(false);
		trees.forEach(tree -> {if (!parseTree(tree, table)) failure.set(true);});
		table.fillEmptyCells(Double.NaN);
		table.replace("TMD", 0, Double.NaN);
		table.show(String.format("Persistence Homology Table [%s]", descriptorChoice));
		if (failure.get())
			logService.error("Some tree(s) could not be parsed... More details may be available in debug mode");
	}

	private boolean parseTree(final Tree tree, final SNTTable table) {
		try {
			final PersistenceAnalyzer analyzer = new PersistenceAnalyzer(tree);
			if (includeTMD) {
				table.addColumn(String.format("%s [TMD barcodes]", tree.getLabel()), analyzer.getBarcode(descriptor));
			}
			if (includeLandscape) {
				table.addColumn(String.format("%s [Landscape (%dx%d)]", tree.getLabel(), landscapeNum, landscapeRes),
						analyzer.getLandscape(descriptor, landscapeNum, landscapeRes));
			}
			return true;
		} catch (final IllegalArgumentException ex) {
			logService.error(String.format("%s: %s", tree.getLabel(), ex.getMessage()));
		}
		return false;
	}

}
