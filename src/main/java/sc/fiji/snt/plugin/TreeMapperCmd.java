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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;

import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

/**
 * Command for color coding trees according to their properties using
 * {@link TreeColorMapper} with options to display result in {@link Viewer2D}
 * and {@link Viewer3D}
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Color Mapper",
	initializer = "init")
public class TreeMapperCmd extends CommonDynamicCmd {

	@Parameter
	private PrefService prefService;

	@Parameter
	private LUTService lutService;

	@Parameter(required = true, label = "Color by")
	private String measurementChoice;

	@Parameter(label = "LUT", callback = "lutChoiceChanged")
	private String lutChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorTable colorTable;

	@Parameter(required = false, label = "Rec. Viewer Color Map")
	private boolean showInRecViewer = false;

	@Parameter(required = false, label = "Rec. Plotter Color Map")
	private boolean showPlot = false;

	@Parameter(required = false, label = "Remove Existing Color Coding",
		callback = "removeColorCoding",
		description="After removing exiting color coding, press 'Cancel' to dismiss this dialog")
	private Button removeColorCoding;

	@Parameter(required = true)
	private Tree tree;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean setValuesFromSNTService;
	
	@Parameter(required = false, persist = false, visibility = ItemVisibility.INVISIBLE)
	private boolean onlyConnectivitySafeMetrics = false;

	private Map<String, URL> luts;
	private Viewer2D plot;

	@Override
	public void run() {
		if (!sntService.isActive()) error("SNT not running?");
		if (tree == null || tree.isEmpty()) error("Invalid input tree");
		statusService.showStatus("Applying Color Code...");
		SNTUtils.log("Color Coding Tree (" + measurementChoice + ") using " + lutChoice);
		final TreeColorMapper colorizer = new TreeColorMapper(context());
		if (setValuesFromSNTService && TreeColorMapper.VALUES.equals(
			measurementChoice))
		{
			SNTUtils.log("Assigning values...");
			final PathProfiler profiler = new PathProfiler(tree, sntService
				.getPlugin().getLoadedDataAsImp());
			profiler.assignValues();
		}
		colorizer.setMinMax(Double.NaN, Double.NaN);
		try {
			colorizer.map(tree, measurementChoice, colorTable);
		}
		catch (final IllegalArgumentException exc) {
			error(exc.getMessage());
			return;
		}
		final double[] minMax = colorizer.getMinMax();
		if (showInRecViewer) {
			final Viewer3D recViewer = sntService.getRecViewer();
			recViewer.addColorBarLegend(colorTable, (float) minMax[0], (float) minMax[1]);
		}
		sntService.updateViewers();
		if (showPlot && colorizer.isNodeMapping() && tree.getNodesCount() > 10000) {
			showPlot = new GuiUtils().getConfirmation("Render more than 10k nodes uniquely in Reconstruction Plotter "
					+ "could become a CPU-intensive operation. Alternatively, you may want to render mapped nodes in "
					+ "Reconstruction Viewer (significantly faster), or downsample the structure before the mapping. "
					+ "Proceed nevertheless?", "Confirm Slow Operation?");
		}
		if (showPlot) {
			SNTUtils.log("Creating 2D plot...");
			plot = new Viewer2D(context());
			plot.add(tree);
			plot.addColorBarLegend(colorTable, minMax[0], minMax[1]);
			plot.show();
		}
		SNTUtils.log("Finished...");
		resetUI();
	}

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		final List<String> choices = TreeColorMapper.getMetrics();
		if (!setValuesFromSNTService) choices.remove(TreeColorMapper.VALUES);
		if (onlyConnectivitySafeMetrics) {
			choices.remove(TreeColorMapper.STRAHLER_NUMBER);
		}
		Collections.sort(choices);
		final MutableModuleItem<String> measurementChoiceInput = getInfo()
				.getMutableInput("measurementChoice", String.class);
		measurementChoiceInput.setChoices(choices);
		// Do not set value, otherwise we'll overwrite any input passed to CommandService
//		measurementChoiceInput.setValue(this, prefService.get(getClass(),
//			"measurementChoice", TreeColorMapper.STRAHLER_NUMBER));
		resolveInput("setValuesFromSNTService");
		resolveInput("onlyConnectivitySafeMetrics");
		if (lutChoice == null) lutChoice = prefService.get(getClass(), "lutChoice",
			"mpl-viridis.lut");
		setLUTs();
	}

	private void setLUTs() {
		luts = lutService.findLUTs();
		if (luts.isEmpty()) {
			error("This command requires at least one LUT to be installed.");
		}
		final ArrayList<String> choices = new ArrayList<>();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			choices.add(entry.getKey());
		}

		// define a valid LUT choice
		Collections.sort(choices);
		if (lutChoice == null || !choices.contains(lutChoice)) {
			lutChoice = choices.get(0);
		}

		final MutableModuleItem<String> input = getInfo().getMutableInput(
			"lutChoice", String.class);
		input.setChoices(choices);
		input.setValue(this, lutChoice);
		lutChoiceChanged();
	}

	private void lutChoiceChanged() {
		try {
			colorTable = lutService.loadLUT(luts.get(lutChoice));
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	private void removeColorCoding() {
		for (final Path p : tree.list()) {
			p.setColor((java.awt.Color)null);
			p.setNodeColors(null);
		}
		statusService.showStatus("Color code removed...");
	}

	/* IDE debug method **/
	public static void main(final String[] args) throws IOException {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		input.put("tree", tree);
		ij.command().run(TreeMapperCmd.class, true, input);
	}

}
