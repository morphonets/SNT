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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;

import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for importing a folder of SWC files.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Import Directory of SWC files")
public class MultiSWCImporterCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	@Parameter(style = "directory", label = "Directory")
	private File dir;

	@Parameter(label = "Filenames containing", required = false, //
		description = "<html>Only files containing this string will be considered." +
			"<br>Leave blank to consider all SWC files in the directory.")
	private String pattern;

	@Parameter(required = false, label = "Colors", choices = {
		"Distinct (each file labelled uniquely)", "Common color specified below" })
	private String colorChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;")
	private ColorRGB color;

	@Parameter(required = false, label = "Replace existing paths")
	private boolean clearExisting;


	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final SNTUI ui = sntService.getUI();
		final PathAndFillManager pafm = sntService.getPathAndFillManager();

		final Map<String, String> importMap = getImportMap();
		if (importMap == null || importMap.isEmpty()) {
			cancel("No matching files found in directory.");
			return;
		}

		ui.showStatus("Importing directory. Please wait...", false);
		SNTUtils.log("Importing directory " + dir);

		final int lastExistingPathIdx = pafm.size() - 1;
		final List<Tree> result = pafm.importSWCs(importMap, getColor());
		final long failures = result.stream().filter(Tree::isEmpty)
			.count();
		if (failures == result.size()) {
			ui.error("No reconstructions could be retrieved. Invalid directory?");
			ui.showStatus("Error... No reconstructions imported", true);
			return;
		}

		if (clearExisting) {
			final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx)
				.toArray();
			pafm.deletePaths(indices);
		}

		// see CommonDynamicCmd#notifyExternalDataLoaded();
		sntService.getInstance().updateDisplayCanvases();
		sntService.getInstance().updateAllViewers();
		sntService.getInstance().getPrefs().setTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, true);

		if (failures > 0) {
			ui.error(String.format("%d/%d reconstructions could not be retrieved.",
				failures, result.size()));
			ui.showStatus("Partially successful import...", true);
			SNTUtils.log("Import failed for the following files:");
			result.forEach(tree -> {
				if (tree.isEmpty()) SNTUtils.log(tree.getLabel());
			});
		}
		else {
			ui.showStatus("Successful imported " + result.size() +
				" reconstruction(s)...", true);
		}
		ui.runCommand("validateImgDimensions");
	}

	private ColorRGB getColor() {
		return (colorChoice.contains("unique")) ? null : color;
	}

	private Map<String, String> getImportMap() {
		if (dir == null || !dir.isDirectory() || !dir.exists()) return null;
		final File[] files = dir.listFiles(file -> {
			if (file.isHidden()) return false;
			final String fName = file.getName().toLowerCase();
			if (!fName.endsWith("swc")) return false;
			return pattern == null || pattern.isEmpty() || fName.contains(pattern);
		});
		if (files == null || files.length == 0) return null;
		final Map<String, String> map = new HashMap<>();
		for (final File file : files) {
			map.put(SNTUtils.stripExtension(file.getName()), file.getAbsolutePath());
		}
		return map;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(MultiSWCImporterCmd.class, true);
	}

}
