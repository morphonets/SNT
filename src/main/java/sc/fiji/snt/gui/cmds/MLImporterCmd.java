/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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
@Plugin(type = Command.class, visible = false,
	label = "Import MouseLight Reconstructions", initializer = "init")
public class MLImporterCmd extends CommonDynamicCmd {

	private static final String EMPTY_LABEL = "<html>&nbsp;";
	private static final String CHOICE_AXONS = "Axons";
	private static final String CHOICE_DENDRITES = "Dendrites";
	private static final String CHOICE_SOMA = "Soma";
	private static final String CHOICE_BOTH = "All compartments";
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

		if (ui != null) ui.changeState(SNTUI.LOADING);
		status("Retrieving ids... Please wait...", false);
		final int lastExistingPathIdx = pafm.size() - 1;
		final Map<String, TreeSet<SWCPoint>> inMap = new HashMap<>();
		for (final String id : ids) {
			final MouseLightLoader loader = new MouseLightLoader(id);
			inMap.put(id, (loader.idExists()) ? loader.getNodes(arborChoice) : null);
		}
		final Map<String, Tree> result = pafm.importNeurons(inMap, getColor(), "um");
		final long failures = result.values().stream().filter(
			tree -> (tree == null || tree.isEmpty())).count();
		if (failures == ids.size()) {
			error("No reconstructions could be retrieved: Invalid Query?");
			status("Error... No reconstructions imported", true);
			return;
		}
		if (clearExisting) {
			if (recViewer == null) {
				// We are importing into a functional SNTUI with Path Manager
				final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx)
					.toArray();
				pafm.deletePaths(indices);
			}
			else {
				// We are importing into a stand-alone Reconstruction Viewer
				recViewer.removeAllTrees();
			}
		}

		if (snt != null) notifyExternalDataLoaded();

		if (recViewer != null) {
			// A 'stand-alone' Reconstruction Viewer was specified
			recViewer.setSceneUpdatesEnabled(false);
			result.forEach((id, tree) -> {
				if (tree != null) recViewer.addTree(tree);
			});
			recViewer.setSceneUpdatesEnabled(true);
			recViewer.validate();
		}
		if (failures > 0) {
			error(String.format("%d/%d reconstructions could not be retrieved.",
				failures, result.size()));
			status("Partially successful import...", true);
		}
		else {
			status("Successful imported " + result.size() + " reconstruction(s)...",
				true);
		}
		resetUI(recViewer == null);
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
		if (sntService.isActive()) {
			snt = sntService.getPlugin();
			ui = sntService.getUI();
			pafm = sntService.getPathAndFillManager();
			if (ui != null) ui.changeState(SNTUI.RUNNING_CMD);
		}
		else {
			pafm = new PathAndFillManager();
			pafm.setHeadless(true);
		}
		if (query == null || query.isEmpty()) query = "AA0001";
		pingMsg =
			"Internet connection required. Retrieval of long lists may be rather slow...           ";
		if (recViewer != null) {
			// If a stand-alone viewer was specified, customize options specific
			// to the SNT UI
			final MutableModuleItem<Boolean> clearExistingInput = getInfo()
				.getMutableInput("clearExisting", Boolean.class);
			clearExistingInput.setLabel("Clear existing reconstructions");
		}
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
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(MLImporterCmd.class, true);
	}

}
