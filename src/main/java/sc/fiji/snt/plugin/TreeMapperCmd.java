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

import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.util.ColorRGB;
import org.scijava.widget.Button;

import sc.fiji.snt.analysis.*;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.gui.cmds.FigCreatorCmd;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

/**
 * Command for color coding trees according to their properties using
 * {@link MultiTreeColorMapper} and {@link TreeColorMapper}
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

	@Parameter(required = true, label = "Color by") // dynamically generated
	private String measurementChoice;

	@Parameter(label = "LUT", callback = "lutChoiceChanged")
	private String lutChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorTable colorTable;

	@Parameter(required = false, label = "Undefined value color",
			description="<HTML>If a metric is undefined (i.e., <i>NaN</i>) for a particular segment, " +
					"how should that segment be colored?")
	private ColorRGB nanColor;

	@Parameter(required = false, label = "Make figure from mapping")
	private boolean runFigCreator;

	@Parameter(required = false, label = "Remove Existing Color Coding",
		callback = "removeColorCoding",
		description="After removing exiting color coding, press 'Cancel' to dismiss this dialog")
	private Button removeColorCoding;

	@Parameter(required = true)
	private Collection<Tree> trees;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private Dataset dataset;
	
	@Parameter(required = false, persist = false, visibility = ItemVisibility.INVISIBLE)
	private boolean onlyConnectivitySafeMetrics = false;

 	private Map<String, URL> luts;

	@Override
	public void run() {

		statusService.showStatus("Applying Color Code...");
		SNTUtils.log("Color Coding Tree (" + measurementChoice + ") using " + lutChoice);

		if (dataset != null && TreeColorMapper.VALUES.equals(measurementChoice)) {
			SNTUtils.log("Assigning values...");
			for (final Tree tree : trees) {
				final PathProfiler profiler = new PathProfiler(tree, dataset);
				profiler.setRadius(0);
				profiler.setShape(ProfileProcessor.Shape.LINE);
				profiler.setMetric(ProfileProcessor.Metric.MEAN);
				profiler.assignValues();
			}
		}

		final TreeColorMapper mapper;
		try {
			if (trees.size() == 1) {
				mapper = new TreeColorMapper(context());
				mapper.setMinMax(Double.NaN, Double.NaN);
				if (nanColor != null)
					mapper.setNaNColor(new Color(nanColor.getRed(), nanColor.getGreen(), nanColor.getBlue()));
				mapper.map(trees.iterator().next(), measurementChoice, colorTable);
			} else {
				mapper = new MultiTreeColorMapper(trees);
				mapper.setMinMax(Double.NaN, Double.NaN);
				if (nanColor != null)
					mapper.setNaNColor(new Color(nanColor.getRed(), nanColor.getGreen(), nanColor.getBlue()));
				mapper.map(measurementChoice, colorTable);
			}
		} catch (final IllegalArgumentException exc) {
				error(exc.getMessage());
				return;
		}
		sntService.updateViewers();
		if (runFigCreator) {
			final Map<String, Object> inputs = new HashMap<>();
			inputs.put("trees", trees);
			inputs.put("mapper", mapper);
			inputs.put("noRasterOutput", true);
			inputs.put("noGeodesicTransformation", onlyConnectivitySafeMetrics);
			getContext().getService(CommandService.class).run(FigCreatorCmd.class, true, inputs);
		}
		SNTUtils.log("Finished...");
		resetUI();
	}

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		if (trees == null || trees.isEmpty()) {
			error("Invalid input tree(s)");
		}
		resolveInput("onlyConnectivitySafeMetrics");
		if (lutChoice == null) lutChoice = prefService.get(getClass(), "lutChoice",
			"mpl-viridis.lut");
		setChoices();
		setLUTs();
	}

	private void setChoices() {
		final List<String> choices = (trees.size() == 1) ? TreeColorMapper.getMetrics() :
				MultiTreeColorMapper.getMetrics("gui-all");
		if (dataset == null)
			choices.remove(TreeColorMapper.VALUES);
		if (onlyConnectivitySafeMetrics) {
			choices.remove(MultiTreeColorMapper.N_BRANCHES);
			choices.remove(MultiTreeColorMapper.STRAHLER_NUMBER);
			choices.remove(TreeColorMapper.STRAHLER_ORDERS);
		}
		Collections.sort(choices);
		final MutableModuleItem<String> measurementChoiceInput = getInfo().getMutableInput("measurementChoice", String.class);
		measurementChoiceInput.setChoices(choices);
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
		trees.forEach(ColorMapper::unMap);
		snt.updateAllViewers();
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
