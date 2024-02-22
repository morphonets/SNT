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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements Reconstruction Viewer's 'Color coding' commands.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Tree Color Coder",
	initializer = "init")
public class ColorMapReconstructionCmd extends CommonDynamicCmd {

	@Parameter
	private LUTService lutService;

	@Parameter
	private PrefService prefService;

	@Parameter(required = true, label = "Measurement", choices = { //
		TreeColorMapper.PATH_ORDER, TreeColorMapper.LENGTH,
		TreeColorMapper.N_BRANCH_POINTS, //
		TreeColorMapper.N_NODES, //
		TreeColorMapper.N_SPINES, //
		TreeColorMapper.PATH_DISTANCE, //
		TreeColorMapper.MEAN_RADIUS, //
		TreeColorMapper.AVG_SPINE_DENSITY, //
		TreeColorMapper.NODE_RADIUS, //
		TreeColorMapper.INTER_NODE_DISTANCE, //
		TreeColorMapper.X_COORDINATES, //
		TreeColorMapper.Y_COORDINATES, //
		TreeColorMapper.Z_COORDINATES })
	private String measurementChoice;

	@Parameter(label = "Min", required = false,
		description = "Set both limits to zero for automatic scaling")
	private float min;

	@Parameter(label = "Max", required = false,
		description = "Set both limits to zero for automatic scaling")
	private float max;

	@Parameter(label = "LUT", callback = "lutChoiceChanged")
	private String lutChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;", persist = false)
	private ColorTable colorTable;

	@Parameter(required = false)
	private Viewer3D recViewer;

	@Parameter(required = false)
	private List<String> multiTreeMappingLabels;

	@Parameter(required = false)
	private List<String> treeMappingLabels;

	private Map<String, URL> luts;

	private void lutChoiceChanged() {
		try {
			colorTable = lutService.loadLUT(luts.get(lutChoice));
		}
		catch (final IOException ignored) {
			// move on
		}
	}

	protected void init() {
		// see net.imagej.lut.LUTSelector
		luts = lutService.findLUTs();
		final ArrayList<String> luTChoices = new ArrayList<>();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			luTChoices.add(entry.getKey());
		}
		Collections.sort(luTChoices);
		final MutableModuleItem<String> input = getInfo().getMutableInput(
			"lutChoice", String.class);
		input.setChoices(luTChoices);

		// we want the LUT ramp to update when the dialog is shown. For this
		// to happen it seems we've to load the  persisted LUT choice now
		lutChoice = prefService.get(getClass(), "lutChoice", "mpl-viridis.lut");
		if (lutChoice == null || lutChoice.isEmpty() || !luTChoices.contains(
			lutChoice)) lutChoice = luTChoices.get(0);
		lutChoiceChanged();

		List<String> mChoices = null;
		final MutableModuleItem<String> mInput = getInfo().getMutableInput(
			"measurementChoice", String.class);

		if (treeMappingLabels == null && multiTreeMappingLabels == null) {
			// No trees to be color coded: Show only options for color bar legend
			getInfo().setLabel("Add Color Legend");
			mChoices = new ArrayList<>();
			mChoices.add("");
			mInput.setLabel("<HTML>&nbsp;");
			mInput.setVisibility(ItemVisibility.MESSAGE);
			mInput.setPersisted(false);
			final MutableModuleItem<Float> minInput = getInfo().getMutableInput("min",
				Float.class);
			minInput.setDescription("");
			final MutableModuleItem<Float> maxInput = getInfo().getMutableInput("max",
				Float.class);
			maxInput.setDescription("");
			resolveInput("treeMappingLabels");
			resolveInput("multiTreeMappingLabels");
		}
		else if (treeMappingLabels != null) {
			// Color code single trees
			mChoices = TreeColorMapper.getMetrics();
			Collections.sort(mChoices);
			resolveInput("multiTreeMappingLabels");
		}
		else {
			mChoices = Arrays.asList(MultiTreeColorMapper.PROPERTIES);
			resolveInput("treeMappingLabels");
		}
		mInput.setChoices(mChoices);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		try {
			if (recViewer == null) {
				recViewer = sntService.getRecViewer();
			}
			final boolean setProgress = recViewer.getManagerPanel() != null;

			// FIXME: This is not suitable for measurements with negative values
			final boolean validMin = !Double.isNaN(min) && min <= max;
			final boolean validMax = !Double.isNaN(max) && max > 0 && max >= min;

			if (treeMappingLabels == null && multiTreeMappingLabels == null) {
				// No trees to be color coded. Just add the color bar
				if (!(validMin && validMax)) {
					error("Invalid Limits " + min + "-" + max);
					return;
				}
				recViewer.addColorBarLegend(colorTable, min, max);
				return;
			}

			recViewer.setSceneUpdatesEnabled(false);
			double[] limits = { 0d, 0d };
			if (treeMappingLabels != null) {
				// Color code single trees
				limits[0] = Double.MAX_VALUE;
				limits[1] = Double.MIN_VALUE;
				int idx = 0;
				for (final String l : treeMappingLabels) {
					if (setProgress) recViewer.getManagerPanel().showProgress(idx++, treeMappingLabels.size());
					final double[] minMax = recViewer.colorCode(l, measurementChoice,
						colorTable);
					if (minMax[0] < limits[0]) limits[0] = minMax[0];
					if (minMax[1] > limits[1]) limits[1] = minMax[1];
				}
			}
			else if (multiTreeMappingLabels != null) {
				// Color group of trees
				if (setProgress) recViewer.getManagerPanel().showProgress(-1, -1);
				limits = recViewer.colorCode(multiTreeMappingLabels, measurementChoice,
					colorTable);
			}
			recViewer.setSceneUpdatesEnabled(true);
			if (!validMin) min = (float) limits[0];
			if (!validMax) max = (float) limits[1];
			recViewer.addColorBarLegend(colorTable, min, max);
			if (setProgress) recViewer.getManagerPanel().showProgress(0, 0);

		}
		catch (final UnsupportedOperationException | NullPointerException exc) {
			error("SNT's Reconstruction Viewer is not available");
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ColorMapReconstructionCmd.class, true);
	}

}
