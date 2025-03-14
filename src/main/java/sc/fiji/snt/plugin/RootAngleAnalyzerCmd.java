/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.*;
import sc.fiji.snt.analysis.*;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.gui.cmds.FigCreatorCmd;
import sc.fiji.snt.viewer.Viewer3D;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command to perform Root angle analysis on a collection of {@link Tree}s
 *
 * @author Tiago Ferreira
 * @see RootAngleAnalyzer
 */
@Plugin(type = Command.class, label="Root Angle Analysis", initializer = "init")
public class RootAngleAnalyzerCmd extends CommonDynamicCmd {

	@Parameter
	private DisplayService displayService;
	@Parameter
	private LUTService lutService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private SNTService sntService;

	@Parameter(required = false)
	private Collection<Tree> trees;
	@Parameter(required = false)
	private Viewer3D recViewer;

	// Define the GUI
	@Parameter(label = "Color mapping", callback = "lutChoiceChanged")
	private String lutChoice;
	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorTable colorTable;
	@Parameter(required=false, label = "Display plots")
	private boolean plots;
	@Parameter(required=false, label = "Display table")
	private boolean table;

	private Map<String, URL> luts;
	private SNTTable summaryTable;
	private List<RootAngleParser> parsers;

	/**
	 * Instantiates a new RootAngleAnalyzerCmd. Trees to be analyzed expected as input
	 * {@code @parameter}
	 */
	public RootAngleAnalyzerCmd() {
		// tree or trees expected as @parameter
	}

	/**
	 * Instantiates a new RootAngleAnalyzerCmd.
	 *
	 * @param trees the collection of Trees to be analyzed
	 */
	public RootAngleAnalyzerCmd(final Collection<Tree> trees) {
		this.trees = trees;
		initParsersAsNeeded();
	}

	/**
	 * Instantiates a new RootAngleAnalyzerCmd for a single Tree
	 *
	 * @param tree the single Tree to be analyzed
	 */
	public RootAngleAnalyzerCmd(final Tree tree) {
		this(Collections.singleton(tree));
	}

	// callbacks
	private void init() {
		super.init(false);
		if (lutChoice == null) lutChoice = "None";
		setLUTs();
		lutChoiceChanged();
	}

	private void setLUTs() {
		luts = lutService.findLUTs();
		final ArrayList<String> choices = new ArrayList<>();
		if (luts != null) {
			for (final Map.Entry<String, URL> entry : luts.entrySet())
				choices.add(entry.getKey());
			Collections.sort(choices);
		}
		choices.addFirst("None");
		if (lutChoice == null || !choices.contains(lutChoice))
			lutChoice = choices.getFirst();
		else
			lutChoice = prefService.get(getClass(), "lutChoice", "None"); // otherwise color table is out of sync
		final MutableModuleItem<String> input = getInfo().getMutableInput("lutChoice", String.class);
		input.setChoices(choices);
	}

	private void lutChoiceChanged() {
		try {
			if ("None".equals(lutChoice))
				colorTable = ShollUtils.constantLUT(GuiUtils.getDisabledComponentColor());
			else
				colorTable = lutService.loadLUT(luts.get(lutChoice));
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}

	private void initParsersAsNeeded() {
		if (parsers == null) {
			parsers = new ArrayList<>();
			trees.forEach(tree -> parsers.add(new RootAngleParser(tree)));
		}
	}

	@Override
	public void run() {
		if (!plots && !table && "None".equals(lutChoice)) {
			error("No output specified.");
			return;
		}
		if (trees == null || trees.isEmpty()) {
			error("No valid reconstruction(s) to parse.");
			return;
		}
		initParsersAsNeeded();
		if (!parsers.stream().allMatch(RootAngleParser::valid)) {
			if (sntService.isActive() && mixedPaths()) {
				cancel("""
                        None of the reconstruction(s) could be parsed. This is likely caused by
                        mixing fitted and non-fitted paths which may mask original connectivity.
                        You may need to apply (or discard) fits more coherently.""");
			} else {
				cancel("None of the reconstruction(s) could be parsed. Invalid topologies?");
			}
			return;
		}
		if (table) showTabularSummary();
		if (plots) showPlots();
		if ("None".equals(lutChoice))
			return;
		if (recViewer != null) { // then we don't need to create a new figure
			final List<Tree> taggedTrees = getTaggedTrees();
			final MultiTreeColorMapper mapper = getAndApplyMapper(taggedTrees);
			// unfortunately tagged trees are duplicates of the original trees, so we need to remove the original ones
			recViewer.setSceneUpdatesEnabled(false);
			recViewer.remove(getTaggableTrees());
			recViewer.add(taggedTrees);
			recViewer.addColorBarLegend(mapper);
			recViewer.setSceneUpdatesEnabled(true);
		} else {
			runFigCreator();
		}
	}

	private void showPlots() {
		if (trees.size() == 1) {
			final RootAngleAnalyzer analyzer = parsers.getFirst().analyzer;
			SNTChart chart = SNTChart.combine(List.of(analyzer.getHistogram(true), analyzer.getDensityPlot()));
			chart.setTitle(String.format("Root Angle Distributions (%s)", trees.iterator().next().getLabel()));
			chart.show();
		} else {
			final SNTChart c1 = RootAngleAnalyzer.getHistogram(parsers.stream().map(p -> p.analyzer).collect(Collectors.toList()), true);
			final String title = c1.getTitle();
			final SNTChart c2 = RootAngleAnalyzer.getDensityPlot(parsers.stream().map(p -> p.analyzer).collect(Collectors.toList()));
			final SNTChart finalChart = SNTChart.combine(List.of(c1, c2));
			finalChart.setTitle(title);
			finalChart.show();
		}
	}

	private void showTabularSummary() {
		final Display<?> tableDisplay = displayService.getDisplay("SNT Root Angles Table");
		boolean newTableRequired = summaryTable == null || tableDisplay == null || !tableDisplay.isDisplaying(summaryTable);
		if (newTableRequired) {
			summaryTable = new SNTTable();
		}
		populateSummaryTable(summaryTable);
		if (newTableRequired) {
			displayService.createDisplay("SNT Root Angle Table", summaryTable);
		} else {
			tableDisplay.update();
		}
	}

	private void runFigCreator() {
		initParsersAsNeeded();
		final List<Tree> taggedTrees = getTaggedTrees();
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("trees", taggedTrees);
		inputs.put("mapper", getAndApplyMapper(taggedTrees));
		inputs.put("noRasterOutput", true);
		getContext().getService(CommandService.class).run(FigCreatorCmd.class, true, inputs);
	}

	private MultiTreeColorMapper getAndApplyMapper(final List<Tree> taggedTrees) {
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(taggedTrees);
		mapper.map(TreeColorMapper.VALUES, colorTable);
		return mapper;
	}

	private List<Tree> getTaggedTrees() {
		final List<Tree> taggedTrees = new ArrayList<>();
		for (final RootAngleParser parser : parsers) {
			if (parser.valid()) taggedTrees.add(parser.taggedTree());
		}
		return taggedTrees;
	}

	private List<Tree> getTaggableTrees() {
		final List<Tree> taggableTrees = new ArrayList<>();
		for (final RootAngleParser parser : parsers) {
			if (parser.valid()) taggableTrees.add(parser.tree);
		}
		return taggableTrees;
	}

	private boolean mixedPaths() {
		final boolean ref = trees.iterator().next().get(0).isFittedVersionOfAnotherPath();
		for (final Tree tree : trees) {
			for (final Path path : tree.list()) {
				if (path.isFittedVersionOfAnotherPath() != ref)
					return true;
			}
		}
		return false;
	}

	/**
	 * Gets the summary table containing the tabular results of the analysis.
	 *
	 * @return the summary table
	 */
	public SNTTable getSummaryTable() {
		final SNTTable table = new SNTTable();
		populateSummaryTable(table);
		return table;
	}

	private void populateSummaryTable(final SNTTable table) {
		initParsersAsNeeded();
		for (final RootAngleParser parser : parsers) {
			int row = table.getRowIndex(parser.label());
			if (row < 0)
				row = table.insertRow(parser.label());
			if (parser.valid()) {
				table.set("Mean direction (°)", row, parser.analyzer.meanDirection());
				table.set("Centripetal bias", row, parser.analyzer.centripetalBias());
				table.set("Balancing factor", row, parser.analyzer.balancingFactor());
				table.set("Comments", row, "N/A");
			} else {
				table.set("Comments", row, "Not parseable");
			}
		}
	}

	private static class RootAngleParser {
		final Tree tree;
		RootAngleAnalyzer analyzer;

		RootAngleParser(final Tree tree) {
			this.tree = tree;
			try {
				analyzer = new RootAngleAnalyzer(tree);
			} catch (final IllegalArgumentException ignored){
				analyzer = null;
			}
		}

		String label() {
			return tree.getLabel();
		}

		boolean valid() {
			return analyzer != null;
		}

		Tree taggedTree() {
			return analyzer.getTaggedTree((String)null);
		}
	}
}
