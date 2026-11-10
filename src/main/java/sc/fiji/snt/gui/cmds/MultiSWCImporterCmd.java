/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.IOUtils;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Command for importing a folder (or a zip archive) of SWC files.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Import Directory of SWC files")
public class MultiSWCImporterCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	// NB: the parameter name is kept for backwards compatibility (e.g., recorded commands)
	@Parameter(style = "both", label = "Directory or zip", //
		description = "<html>A folder of SWC files, or a zip archive of SWC files (subfolders are searched).")
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
		try {
			importFiles();
		} finally {
			IOUtils.deleteRecursively(extracted); // no-op if dir was not an archive
		}
	}

	private void importFiles() {
		final SNTUI ui = sntService.getUI();
		final PathAndFillManager pafm = sntService.getPathAndFillManager();

		final Map<String, String> importMap = getImportMap();
		if (importMap == null || importMap.isEmpty()) {
			cancel("No matching files found in " + ((dir != null && dir.isFile()) ? "archive." : "directory."));
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

	// Temporary folder holding the extracted contents of dir, if dir is a zip archive
	private File extracted;

	private Map<String, String> getImportMap() {
		if (dir == null || !dir.exists()) return null;
		final File root;
		if (dir.isDirectory()) {
			root = dir;
		} else if (IOUtils.getType(dir) == IOUtils.FileType.ZIP) {
			try {
				extracted = IOUtils.extractZip(dir, IOUtils::isSWCEntry);
			} catch (final IOException ex) {
				SNTUtils.error("Could not read " + dir.getName(), ex);
				return null;
			}
			root = extracted;
		} else {
			return null;
		}
		final String substring = (pattern == null) ? "" : pattern.toLowerCase(Locale.ROOT);
		// Folders: top level only. Archives: subfolders are also searched
		final File[] top = (root == extracted) ? null : root.listFiles(File::isFile);
		final List<File> files = (root == extracted) ? IOUtils.listFiles(Integer.MAX_VALUE, IOUtils::isSWCFile, root)
			: (top == null) ? List.of() : List.of(top);
		final Map<String, String> map = new LinkedHashMap<>();
		files.stream().filter(f -> !f.isHidden() && IOUtils.isSWCFile(f) && f.getName().toLowerCase(Locale.ROOT)
			.contains(substring)).sorted(Comparator.comparing(File::getPath)).forEach(f -> {
				String label = SNTUtils.stripExtension(f.getName());
				// Files in different subfolders of an archive may share a name, and a.swc and a.eswc a label
				if (map.containsKey(label)) {
					final String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
					final String base = (rel.contains("/") ? SNTUtils.stripExtension(rel) : f.getName());
					label = base;
					for (int n = 2; map.containsKey(label); n++)
						label = base + " (" + n + ")";
				}
				map.put(label, f.getAbsolutePath());
			});
		return map.isEmpty() ? null : map;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.LAF.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(MultiSWCImporterCmd.class, true);
	}

}
