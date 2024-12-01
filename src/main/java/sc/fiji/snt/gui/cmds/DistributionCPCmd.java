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

import net.imagej.ImageJ;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.GroupedTreeStatistics;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;

import java.util.*;

/**
 * Command for plotting distributions of whole-cell morphometric properties of
 * {@link Tree}s
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class,
	label = "Distribution Analysis (Multiple Cells)", initializer = "init")
public class DistributionCPCmd extends CommonDynamicCmd {

	@Parameter
	private PrefService prefService;

	@Parameter(required = true, label = "Measurement")
	private String measurementChoice;

	@Parameter(required = true, label = "Compartment", choices= {"All", "Axon", "Dendrites"})
	private String compartment;

	@Parameter(required = false, label = "Histogram style:", style= ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
			choices = {"Data from all cells in a single series", "Data from each cell on a separated series"})
	private String histogramChoice;

	@Parameter(required = false, label = "Histogram type:", style= ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE,
					choices = {"Linear", "Polar"}, description = "Polar histogram assumes a data range between 0 and 360")
	private String histogramType;

	@Parameter(required = true)
	private Collection<Tree> trees;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean calledFromPathManagerUI;

	private boolean polar;
	private String swcTypes;
	private String failures = "";
	private String failureExplanation = "";

	protected void init() {
		super.init(false);
		if (trees == null || trees.isEmpty()) {
			error("At least one Tree required but none found.");
			return;
		}
		if (trees.size() == 1) {
			resolveInput("histogramChoice");
			histogramChoice = "Data from all cells in a single series";
		}
		final MutableModuleItem<String> measurementChoiceInput = getInfo()
			.getMutableInput("measurementChoice", String.class);
		final List<String> choices = TreeStatistics.getAllMetrics();
		if (!calledFromPathManagerUI) choices.remove(MultiTreeStatistics.VALUES);
		Collections.sort(choices);
		measurementChoiceInput.setChoices(choices);
		measurementChoiceInput.setValue(this, prefService.get(getClass(),
			"measurementChoice", MultiTreeStatistics.LENGTH));
	}

	private void runSingleSeriesStats() {
		if ("All".equals(compartment)) {
			failureExplanation = " Perhaps branches have not been tagged as dendrites/axons? If this is the case, "
					+ "you can re-run the analysis using 'All' as compartment choice.";
		}
		try {
			final MultiTreeStatistics mStats = new MultiTreeStatistics(trees, swcTypes);
			mStats.setLabel(swcTypes);
			if (polar)
				mStats.getPolarHistogram(measurementChoice).show();
			else
				mStats.getHistogram(measurementChoice).show();
		} catch (final java.util.NoSuchElementException | IllegalArgumentException | NullPointerException ignored) {
			failures += String.format(" %s", swcTypes);
		}
	}

	private void runMultiSeriesStats() {
		final GroupedTreeStatistics gStats = new GroupedTreeStatistics();
		trees.forEach( tree -> {
			try {
				gStats.addGroup(Collections.singletonList(tree), tree.getLabel(), swcTypes);
			} catch (final java.util.NoSuchElementException | IllegalArgumentException | NullPointerException ignored) {
				failures += String.format(" %s:%s;", tree.getLabel(), swcTypes);
			}
		});
		if (!gStats.getGroups().isEmpty()) {
			if (polar)
				gStats.getPolarHistogram(measurementChoice).show();
			else
				gStats.getHistogram(measurementChoice).show();
		}
	}

	@Override
	public void run() {

		polar = histogramType.toLowerCase().contains("polar");
		if (compartment.toLowerCase().contains("de")) {
			swcTypes = "dendrites";
		} else if (compartment.toLowerCase().contains("ax")) {
			swcTypes = "axon";
		} else {
			swcTypes = "all";
		}
		final boolean exactMatchState = MultiTreeStatistics.isExactMetricMatch();
		MultiTreeStatistics.setExactMetricMatch(true);
		if (histogramChoice.toLowerCase().contains("single series")) {
			runSingleSeriesStats();
		} else {
			runMultiSeriesStats();
		}
		if (!failures.isEmpty()) {
			String error = "It was not possible to access data for " + failures + " compartment(s).";
			error += failureExplanation;
			if (calledFromPathManagerUI) {
				error += " Note that some distributions can only be computed on "
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
