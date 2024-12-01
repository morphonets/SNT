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
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import org.scijava.widget.Button;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.io.RemoteSWCLoader;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Command for importing SWC reconstructions from remote databases
 *
 * @see NeuroMorphoLoader
 * @see FlyCircuitLoader
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init")
public class RemoteSWCImporterCmd extends CommonDynamicCmd {

	@Parameter(required = true, persist = true,
		label = "IDs (comma- or space- separated list)")
	private String query;

	@Parameter(required = false, persist = true, label = "Version",
			choices= {"CNG", "Source"})
	private String version;

	@Parameter(required = false, label = "Colors", choices = {
		"Distinct (each cell labelled uniquely)", "Common color specified below" })
	private String colorChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorRGB commonColor;

//	@Parameter(required = false, label = "Load Template brain")
//	private boolean loadMesh;

	@Parameter(required = false, label = "Replace existing paths")
	private boolean clearExisting;

	@Parameter(label = "Check Database Access", callback = "pingServer")
	private Button ping;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String pingMsg;

	@Parameter(persist = false, required = false,
		visibility = ItemVisibility.INVISIBLE)
	private Viewer3D recViewer;

	@Parameter(persist = false, required = true,
		visibility = ItemVisibility.INVISIBLE)
	private RemoteSWCLoader loader;


    private String placeholderQuery;
	private String database;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final boolean standAloneViewer = recViewer != null;
		if (!standAloneViewer && !sntService.isActive()) {
			error(
				"No Reconstruction Viewer specified and no active instance of SNT was found.");
			return;
		}

		if (loader instanceof NeuroMorphoLoader) {
			((NeuroMorphoLoader)loader).enableSourceVersion(version != null && version.toLowerCase().contains("source"));
		}
		final LinkedHashMap<String, String> urlsMap = getURLmapFromQuery(query);
		if (urlsMap == null || urlsMap.isEmpty()) {
			error("Invalid query. No reconstructions retrieved.");
			return;
		}
		if (!loader.isDatabaseAvailable()) {
			error(getPingMsg(false));
			return;
		}

        PathAndFillManager pafm;
        if (standAloneViewer) {
			pafm = new PathAndFillManager();
		}
		else if (sntService.isActive()) {
			snt = sntService.getInstance();
			ui = sntService.getUI();
			pafm = sntService.getPathAndFillManager();
			recViewer = (ui == null) ? null : ui.getReconstructionViewer(false);
		}
		else {
			throw new IllegalArgumentException(
				"Somehow neither a Viewer nor a SNT instance are available");
		}

		notifyLoadingStart(recViewer);
		status("Retrieving cells. Please wait...", false);
		SNTUtils.log(database + " import: Downloading from URL(s)...");
		final int lastExistingPathIdx = pafm.size() - 1;
		final List<Tree> rawResult = pafm.importSWCs(urlsMap, getColor());
		final List<Tree> filteredResult = rawResult.stream().filter(tree -> tree != null && !tree
			.isEmpty()).collect(Collectors.toList());
		if (filteredResult.isEmpty()) {
			resetUI(recViewer == null);
			notifyLoadingEnd(recViewer);
			error("No reconstructions could be retrieved. Invalid ID(s)?");
			status("Error... No reconstructions imported", true);
			return;
		}

		if (clearExisting) {
			if (standAloneViewer) {
				recViewer.removeAllTrees();
			}
			else {
				// We are importing into a functional SNTUI with Path Manager
				final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx)
					.toArray();
				pafm.deletePaths(indices);
			}
		}

		if (standAloneViewer) {
			final boolean loaderUsesBuggySWCTypes = (loader instanceof FlyCircuitLoader);
			final boolean splitState = recViewer.isSplitDendritesFromAxons();
			if (loaderUsesBuggySWCTypes) recViewer.setSplitDendritesFromAxons(false);
			recViewer.addTrees(filteredResult, ""); // will call recViewer.validate()
			if (loaderUsesBuggySWCTypes) recViewer.setSplitDendritesFromAxons(splitState);
		} else if (snt != null) {
			notifyExternalDataLoaded();
		}

		final boolean validateImgDimensions = !standAloneViewer && pafm.size() > lastExistingPathIdx;
		resetUI(validateImgDimensions);
		notifyLoadingEnd(recViewer);

		if (filteredResult.size() < rawResult.size()) {
			SNTUtils.log("Import failed for the following queried morphologies:");
			if (SNTUtils.isDebugMode()) {
				rawResult.forEach(tree -> {
					if (!filteredResult.contains(tree)) SNTUtils.log(tree.getLabel());
				});
			}
			status("Partially successful import...", true);
			error(String.format("Only %d of %d reconstructions could be retrieved.",
					filteredResult.size(), rawResult.size()));
		}
		else {
			status("Successful imported " + filteredResult.size() + " reconstruction(s)...",
				true);
		}

	}

	private ColorRGB getColor() {
		if (colorChoice.contains("unique"))
			return null;
		if (commonColor == null && recViewer != null)
			return (recViewer.isDarkModeOn()) ? Colors.WHITE : Colors.BLACK;
		return commonColor;
	}

	private LinkedHashMap<String, String> getURLmapFromQuery(final String query) {
		if (query == null) return null;
		final List<String> ids = new LinkedList<>(Arrays.asList(query.split(
			"\\s*(,|\\s)\\s*")));
		if (ids.isEmpty()) return null;
		Collections.sort(ids);
		final LinkedHashMap<String, String> map =
			new LinkedHashMap<>();
		ids.forEach(id -> map.put(id, loader.getReconstructionURL(id)));
		return map;
	}

	protected void init() {
		super.init(false);
		makeMeDatabaseFriendly();
		if (query == null || query.isEmpty()) query = placeholderQuery;
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

	private void makeMeDatabaseFriendly() {
		if (loader instanceof NeuroMorphoLoader) {
			placeholderQuery = "cnic_001";
			database = "NeuroMorpho.org";
		}
		else if (loader instanceof FlyCircuitLoader) {
			placeholderQuery = "VGlut-F-400787";
			database = "FlyCircuit.tw";
			resolveInput("version");
		}
		getInfo().setLabel("Import " + database + " Reconstructions");
		final MutableModuleItem<String> queryInput = getInfo().getMutableInput("query", String.class);
		queryInput.setDescription("Cell id(s). E.g., " + placeholderQuery);
	}

	@SuppressWarnings("unused")
	private void pingServer() {
		pingMsg = getPingMsg(loader.isDatabaseAvailable());
	}

	private String getPingMsg(final boolean pingResponse) {
		return (pingResponse) ? "Successfully connected to " + database +
			" database." : database +
				" not reached. It is either down or you have no internet access.";
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(RemoteSWCImporterCmd.class, true);
	}

}
