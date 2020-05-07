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

import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for plotting distributions of morphometric properties
 * (branch-related) of {@link Tree}s
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Distribution Analysis (Branch Properties)", initializer = "init")
public class DistributionBPCmd extends CommonDynamicCmd {

	@Parameter
	private PrefService prefService;

	@Parameter(required = true, label = "Measurement")
	private String measurementChoice;

	// Allowed inputs are a single Tree, or a Collection of Trees
	@Parameter(required = false)
	private Tree tree;

	@Parameter(required = false)
	private Collection<Tree> trees;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean calledFromPathManagerUI;

	private boolean imgDataAvailable;

	protected void init() {
		super.init(false);
		final MutableModuleItem<String> measurementChoiceInput = getInfo()
			.getMutableInput("measurementChoice", String.class);
		final List<String> choices = TreeStatistics.getMetrics();
		imgDataAvailable = calledFromPathManagerUI && sntService.getPlugin().accessToValidImageData();
		if (!imgDataAvailable) choices.remove(TreeStatistics.VALUES);
		Collections.sort(choices);
		measurementChoiceInput.setChoices(choices);
		measurementChoiceInput.setValue(this, prefService.get(getClass(),
			"measurementChoice", TreeStatistics.CONTRACTION));
		if (tree != null) {
			trees = Collections.singletonList(tree);
			resolveInput("trees");
		} else {
			resolveInput("tree");
		}
	}

	@Override
	public void run() {
		if (imgDataAvailable && TreeStatistics.VALUES.equals(
			measurementChoice))
		{
			SNTUtils.log("Assigning values...");
			trees.forEach( tree -> {
				final PathProfiler profiler = new PathProfiler(tree, sntService
						.getPlugin().getLoadedDataAsImp());
					profiler.assignValues();
			});

		}
		try {
			final TreeStatistics treeStats = TreeStatistics.fromCollection(trees, measurementChoice);
			treeStats.getHistogram(measurementChoice).show();
			resetUI();
		} catch (final IllegalArgumentException | NullPointerException ex) {
			String error = "It was not possible to retrieve valid histogram data.";
			if (calledFromPathManagerUI) {
				error += "\nNote that some distributions can only be computed on \n"
						+ "structures with a single root without disconnected paths.\n"
						+ "Please re-run the command with a valid selection.";
			}
			cancel(error);
			ex.printStackTrace();
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Tree tree = new Tree(SNTUtils.randomPaths());
		tree.setLabel("Bogus test");
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", tree);
		ij.command().run(DistributionBPCmd.class, true, input);
	}

}
