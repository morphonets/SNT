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

package sc.fiji.snt.gui.cmds;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;

import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

/**
 * Command for plotting distributions of whole-cell morphometric properties of
 * {@link Tree}s
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Distribution Analysis (Multiple Cells)", initializer = "init")
public class DistributionCPCmd extends CommonDynamicCmd {

	@Parameter
	private PrefService prefService;

	@Parameter(required = true, label = "Measurement")
	private String measurementChoice;

	@Parameter(required = true, label = "Compartment", choices= {"All", "Axon", "Dendrites"})
	private String compartment;

	@Parameter(required = false, label = "Polar histogram", description = "Creates a polar histogram. Assumes a data range between 0 and 360")
	private boolean polar;

	@Parameter(required = true)
	private Collection<Tree> trees;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean calledFromPathManagerUI;

	protected void init() {
		super.init(false);
		if (trees == null || trees.isEmpty()) {
			error("Collection of Trees required but none found.");
		}
		final MutableModuleItem<String> measurementChoiceInput = getInfo()
			.getMutableInput("measurementChoice", String.class);
		final List<String> choices = TreeStatistics.getMetrics("common"); // sMultiTreeStatistics.getMetrics() + common Sholl metrics
		if (!calledFromPathManagerUI) choices.remove(MultiTreeStatistics.VALUES);
		Collections.sort(choices);
		measurementChoiceInput.setChoices(choices);
		measurementChoiceInput.setValue(this, prefService.get(getClass(),
			"measurementChoice", MultiTreeStatistics.LENGTH));
		resolveInput("setValuesFromSNTService");
	}

	@Override
	public void run() {
		String failures = "";
		String explanation = "";
		final boolean exactMatchState = MultiTreeStatistics.isExactMetricMatch();
		MultiTreeStatistics.setExactMetricMatch(true);
		MultiTreeStatistics mStats = null;
		if  ("All".equals(compartment)) {
			try {
				mStats = new MultiTreeStatistics(trees);
				mStats.setLabel("All Processes");
			} catch (final java.util.NoSuchElementException | IllegalArgumentException | NullPointerException ignored) {
				failures = "all";
			}
		} else {
			explanation = " Perhaps branches have not been tagged as dendrites/axons? If this is the case, "
					+ "you can re-run the analysis using 'All' as compartment choice.";
			try {
				if (compartment.contains("De")) {
					mStats = new MultiTreeStatistics(trees, "dendrites");
					mStats.setLabel("Dendrites");
				}
			} catch (final java.util.NoSuchElementException | IllegalArgumentException | NullPointerException ignored) {
				failures += "dendritic";
			}
			try {
				if (compartment.contains("Ax")) {
					mStats = new MultiTreeStatistics(trees, "axon");
					mStats.setLabel("Axons");
				}
			} catch (final java.util.NoSuchElementException | IllegalArgumentException | NullPointerException ignored) {
				failures += (failures.isEmpty()) ? "axonal" : " or axonal";
			}
		}

		if (mStats != null) {
			if (polar)
				mStats.getPolarHistogram(measurementChoice).show();
			else
				mStats.getHistogram(measurementChoice).show();
		}
		if (!failures.isEmpty()) {
			String error = "It was not possible to access data for " + failures + " compartment(s).";
			error += explanation;
			if (calledFromPathManagerUI) {
				error += "Note that some distributions can only be computed on "
						+ "structures with a single root without disconnected paths. "
						+ "Please re-run the command with a valid selection.";
			}
			error(error);
		}
		MultiTreeStatistics.setExactMetricMatch(exactMatchState);
		resetUI();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		GuiUtils.setLookAndFeel();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		input.put("trees", new SNTService().demoTrees());
		ij.command().run(DistributionCPCmd.class, true, input);
	}
}
