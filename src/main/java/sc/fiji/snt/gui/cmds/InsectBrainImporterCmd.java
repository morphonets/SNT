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
package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.Button;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.InsectBrainLoader;
import sc.fiji.snt.util.TreeUtils;
import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Command for importing InsectBrain reconstructions
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class,
	label = "Import InsectBrain Data", initializer = "init")
public class InsectBrainImporterCmd extends CommonDynamicCmd {


	/* Prompt */
	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Neuron(s):")
	private String HEADER2;
	@Parameter(required = true, persist = true, label="ID(s)", 
			description= "NIN as listed at https://insectbraindb.org/app/ . E.g.: 205, 210, 211")
	private String neuronIDsChoice;
	@Parameter(required = false, label = "Colors", choices = {
		"Distinct (each neuron labelled uniquely)", "Common color specified below" })
	private String colorChoice;
	@Parameter(required = false, label = EMPTY_LABEL)
	private ColorRGB commonColor;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "<br>Options:")
	private String HEADER3;
	@Parameter(label = "Load associated meshes")
	private boolean loadMeshes;
	@Parameter(required = false)
	private boolean clearExisting;

	@Parameter(label = "Check Database Access", callback = "pingServer")
	private Button ping;
	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String pingMsg;

	/* Internal Fields */
	@Parameter(persist = false, required = false, visibility = ItemVisibility.INVISIBLE)
	private Viewer3D recViewer;

	private boolean runningFromMainSNT;

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
		final List<Integer> ids = getIdsFromChoice(neuronIDsChoice);
		if (ids == null) {
			error("Invalid ID(s). No reconstructions retrieved.");
			return;
		}
		if (!InsectBrainLoader.isDatabaseAvailable()) {
			error(getPingMsg(false));
			return;
		}

		notifyLoadingStart(recViewer);
		status("Retrieving ids... Please wait...", false);
		final List<Tree> trees = new ArrayList<>();
		final List<OBJMesh> meshes = (loadMeshes) ? new ArrayList<>() : null;
		int failures = 0;
		for (final int id : ids) {
			final InsectBrainLoader loader = new InsectBrainLoader(id);
			final Tree tree = (loader.idExists()) ? loader.getTree() : null;
			if (tree == null) {
				failures++;
			} else {
				trees.add(tree);
				if (loadMeshes) meshes.addAll(loader.getMeshes());
			}
		}
		if (failures == ids.size()) {
			resetUI(false);
			status("Error... No reconstructions imported", true);
			error("No reconstructions could be retrieved: Invalid ID(s)?");
			return;
		}

		// Customize trees
		if (colorChoice.contains("unique")) {
			TreeUtils.assignUniqueColors(trees);
		} else {
			trees.forEach(tree -> tree.setColor(commonColor));
		}

		// Import trees
		if (runningFromMainSNT) {
			final PathAndFillManager pafm = sntService.getPathAndFillManager();
			if (clearExisting) pafm.clear();
			trees.forEach( tree -> pafm.addTree(tree));
			if (loadMeshes) {
				sntService.getRecViewer().add(meshes);
				try {
					sntService.getRecViewer().syncPathManagerList();
				} catch (final IllegalArgumentException ex) {
					// do nothing
				}
			}
			notifyExternalDataLoaded();
		}
		else {
			// We are importing into a stand-alone Reconstruction Viewer
			if (clearExisting) recViewer.removeAllTrees();
			recViewer.add(trees);
			if (loadMeshes) recViewer.add(meshes);
			// recViewer.validate(); // Not needed: will be called by recViewer.add(Collection)
		}

		notifyLoadingEnd(recViewer);
		resetUI(recViewer == null);
		if (failures > 0) {
			error(String.format("%d/%d reconstructions could not be retrieved.",
				failures, trees.size()));
			status("Partially successful import...", true);
		}
		else {
			status("Successful imported " + trees.size() + " reconstruction(s)...", true);
		}
	}

	/**
	 * Parses input neuronIDsChoice to retrieve the list of neuron ids.
	 *
	 * @param string the input String (comma- or space- separated list)
	 * @return the list of cell ids, or null if string does not make sense
	 */
	private List<Integer> getIdsFromChoice(final String string) {
		final List<Integer> ids;
		if (string == null) return null;
		final List<String> stringIDs = new ArrayList<>(Arrays.asList(string.split("\\s*(,|\\s)\\s*")));
		ids = new ArrayList<>(stringIDs.size());
		for(String s : stringIDs) {
			try {
				ids.add(Integer.valueOf(s));
			} catch (final NumberFormatException ignored) {
				SNTUtils.log("InsectBrain import: Ignoring " + s);
			}
		}
		if (ids.isEmpty()) return null;
		Collections.sort(ids);
		return ids;
	}

	protected void init() {

		if (sntService.isActive()) {
			snt = sntService.getInstance();
			ui = sntService.getUI();
			if (ui != null) ui.changeState(SNTUI.RUNNING_CMD);
			runningFromMainSNT = true;
		}
		// If a recViewer instance has been specified, then we've been called from
		// a standalone Reconstruction Viewer even if SNT is currently running
		runningFromMainSNT = runningFromMainSNT && recViewer == null;

		if (!runningFromMainSNT && recViewer == null) {
			error("Neither an SNT instance nor a standalone Reconstruction viewer seem available");
		}
		pingMsg = "Internet connection required. Retrieval of long lists may be rather slow...           ";

		// Customize prompt depending from how the command is being called.
		final MutableModuleItem<Boolean> clearExistingInput = getInfo()
				.getMutableInput("clearExisting", Boolean.class);
		clearExistingInput.setLabel((runningFromMainSNT) ? "Clear existing paths" : "Clear existing reconstructions");

		// Assign some defaults, in the case it is the first time the user is running this command
		if (neuronIDsChoice == null || neuronIDsChoice.trim().isEmpty())
			neuronIDsChoice = "205, 210, 211";

		// We could instead provide users with a choice list, but then
		// we would probably have to provide a choice list for species?
	}

	@SuppressWarnings("unused")
	private void pingServer() {
		pingMsg = getPingMsg(InsectBrainLoader.isDatabaseAvailable());
	}

	private String getPingMsg(final boolean pingResponse) {
		return (pingResponse) ? "Successfully connected to the InsectBrain database."
			: "InsectBrain server not reached. It is either down or you have no internet access.";
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(InsectBrainImporterCmd.class, true);
	}

}
