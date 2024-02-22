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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.Button;

import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;

/**
 * Command for importing MouseLight reconstructions
 *
 * @see MouseLightLoader
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class,
	label = "Import MouseLight Reconstructions", initializer = "init")
public class MLImporterCmd extends CommonDynamicCmd {

	private static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final String CHOICE_AXONS = "Axons";
	private static final String CHOICE_DENDRITES = "Dendrites";
	private static final String CHOICE_SOMA = "Soma";
	private static final String CHOICE_BOTH = "All compartments";
	private static final String CHOICE_BOTH_COMBINED = "All compartments (as single object)";
	private static final String CHOICE_BOTH_SPLIT = "All compartments (splitted objects)";
	private final static String DOI_MATCHER = ".*\\d+/janelia\\.\\d+.*";
	private final static String ID_MATCHER = "[A-Z]{2}\\d{4}";

	@Parameter(required = true, persist = true,
		label = "IDs / DOIs (comma- or space- separated list)",
		description = "e.g., AA0001 or 10.25378/janelia.5527672")
	private String query;

	@Parameter(required = false, persist = true, label = "Structures to import",
		choices = { CHOICE_BOTH, CHOICE_AXONS, CHOICE_DENDRITES, CHOICE_SOMA })
	private String arborChoice;

	@Parameter(required = false, label = "Colors", choices = {
		"Distinct (each id labelled uniquely)", "Common color specified below" })
	private String colorChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorRGB commonColor;

	@Parameter(required = false, persist = true, label = "Replace existing paths")
	private boolean clearExisting;

	@Parameter(label = "Validate IDs", callback = "validateIDs")
	private Button validate;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String validationMsg = EMPTY_LABEL;

	@Parameter(label = "Check Database Access", callback = "pingServer")
	private Button ping;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String pingMsg;

	@Parameter(persist = false, required = false,
		visibility = ItemVisibility.INVISIBLE)
	private Viewer3D recViewer;

	private PathAndFillManager pafm;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		if (recViewer == null && !sntService.isActive()) {
			error("No active instance of SNT was found.");
			return;
		}
		final List<String> ids = getIdsFromQuery(query);
		if (ids == null) {
			error("Invalid query. No reconstructions retrieved.");
			return;
		}
		if (!MouseLightLoader.isDatabaseAvailable()) {
			error(getPingMsg(false));
			return;
		}

		notifyLoadingStart(recViewer);
		status("Retrieving ids... Please wait...", false);
		final int lastExistingPathIdx = pafm.size() - 1;
		final Map<String, TreeSet<SWCPoint>> inMap = new HashMap<>();
		final String compartment = (!arborChoice.contains(" ")) ? arborChoice
				: arborChoice.substring(0, arborChoice.indexOf(" "));
		for (final String id : ids) {
			final MouseLightLoader loader = new MouseLightLoader(id);
			inMap.put(id, (loader.idExists()) ? loader.getNodes(compartment) : null);
		}
		final Map<String, Tree> result = pafm.importNeurons(inMap, getColor(), GuiUtils.micrometer());
		final List<Tree> filteredResult = result.values().stream().filter(tree -> (tree != null && !tree.isEmpty()))
				.collect(Collectors.toList());
		if (filteredResult.isEmpty()) {
			resetUI(false);
			if (recViewer != null)
				notifyLoadingEnd(recViewer);
			status("Error... No reconstructions imported", true);
			error("No reconstructions could be retrieved: Invalid Query?");
			return;
		}

		if (recViewer == null) {
			// We are importing into a functional SNTUI with Path Manager
			if (clearExisting) {
				final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx)
						.toArray();
					pafm.deletePaths(indices);
			}
			if (snt != null) notifyExternalDataLoaded();
			
		} else {
			// A 'stand-alone' Reconstruction Viewer was specified
			if (clearExisting) recViewer.removeAllTrees();
			final boolean splitState = recViewer.isSplitDendritesFromAxons();
			recViewer.setSplitDendritesFromAxons(arborChoice.toLowerCase().contains("split"));
			recViewer.addTrees(filteredResult, ""); // colors have already been assigned
			recViewer.setSplitDendritesFromAxons(splitState);
		}

		notifyLoadingEnd(recViewer);
		resetUI(recViewer == null);
		if (filteredResult.size() < result.size()) {
			status("Partially successful import...", true);
			error(String.format("Only %d of %d reconstructions could be retrieved.",
				filteredResult.size(), result.size()));
		}
		else {
			status("Successful imported " + result.size() + " reconstruction(s)...", true);
		}
	}

	/**
	 * Parses input query to retrieve the list of cell ids.
	 *
	 * @param query the input query (comma- or space- separated list)
	 * @return the list of cell ids, or null if input query is not valid
	 */
	private List<String> getIdsFromQuery(final String query) {
		final List<String> ids;
		if (query == null) return null;
		ids = new LinkedList<>(Arrays.asList(query.split("\\s*(,|\\s)\\s*")));
		ids.removeIf(id -> !(id.matches(ID_MATCHER) || id.matches(DOI_MATCHER)));
		if (ids.isEmpty()) return null;
		Collections.sort(ids);
		return ids;
	}

	/**
	 * Extracts a valid {@link MouseLightLoader} compartment flag from input choice
	 *
	 * @param choice the input choice
	 * @return a valid {@link MouseLightLoader} flag
	 */
	protected String getCompartment(final String choice) {
		if (choice == null) return null;
		switch (choice) {
			case CHOICE_AXONS:
				return MouseLightLoader.AXON;
			case CHOICE_DENDRITES:
				return MouseLightLoader.DENDRITE;
			case CHOICE_SOMA:
				return MouseLightLoader.SOMA;
			default:
				return "all";
		}
	}

	protected void init() {
		if (recViewer != null) {
			// If a stand-alone viewer was specified, customize options specific
			// to the SNT UI
			final MutableModuleItem<String> arborChoiceInput = getInfo()
					.getMutableInput("arborChoice", String.class);
			arborChoiceInput.setChoices(Arrays.asList(CHOICE_BOTH_COMBINED,
					CHOICE_BOTH_SPLIT, CHOICE_AXONS, CHOICE_DENDRITES, CHOICE_SOMA));
			final MutableModuleItem<Boolean> clearExistingInput = getInfo()
				.getMutableInput("clearExisting", Boolean.class);
			clearExistingInput.setLabel("Clear existing reconstructions");
			pafm = new PathAndFillManager(1, 1, 1, GuiUtils.micrometer());
			pafm.setHeadless(true);
		} else if (sntService.isActive()) {
			snt = sntService.getInstance();
			ui = sntService.getUI();
			pafm = sntService.getPathAndFillManager();
			if (ui != null) ui.changeState(SNTUI.RUNNING_CMD);
		}
		if (query == null || query.isEmpty()) query = "AA0001";
		pingMsg =
			"Internet connection required. Retrieval of long lists may be rather slow... ";
	}

	@SuppressWarnings("unused")
	private void validateIDs() {
		validationMsg = "Validating....";
		final List<String> list = getIdsFromQuery(query);
		if (list == null) validationMsg =
			"Query does not seem to contain valid IDs!";
		else validationMsg = "Query seems to contain " + list.size() +
			" valid ID(s).";
	}

	private ColorRGB getColor() {
		return (colorChoice.contains("unique")) ? null : commonColor;
	}

	@SuppressWarnings("unused")
	private void pingServer() {
		pingMsg = getPingMsg(MouseLightLoader.isDatabaseAvailable());
	}

	private String getPingMsg(final boolean pingResponse) {
		return (pingResponse) ? "Successfully connected to the MouseLight database."
			: "MouseLight server not reached. It is either down or you have no internet access.";
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(MLImporterCmd.class, true);
	}

}
