/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.IntStream;

import org.json.JSONException;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;

import net.imagej.ImageJ;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;

/**
 * Command for importing a (MouseLight) JSON file
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Import ML JSON Data")
public class JSONImporterCmd extends CommonDynamicCmd {

	@Parameter(label = "File")
	private File file;

	@Parameter(required = false, label = "Colors", choices = {
		"Random (from distinct set)", "Specified below" })
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

		super.init(true);
		final PathAndFillManager pafm = sntService.getPathAndFillManager();

		status("Importing file. Please wait...", false);
		SNTUtils.log("Importing file " + file);

		final int lastExistingPathIdx = pafm.size() - 1;
		try {

			final Map<String, TreeSet<SWCPoint>> nodes = MouseLightLoader.extractNodes(file, "all");
			final Map<String, Tree> result = pafm.importNeurons(nodes, getColor(), null);
			if (pafm.size() - 1 == lastExistingPathIdx) {
				error("No reconstructions could be retrieved. Invalid file?");
				status("Error... No reconstructions imported", true);
				return;
			}

			if (clearExisting) {
				final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx).toArray();
				pafm.deletePaths(indices);
			}

			if (snt != null) notifyExternalDataLoaded();
			status("Successfully imported " + result.size() + " reconstruction(s)...", true);
	
		} catch (final FileNotFoundException | IllegalArgumentException | JSONException e) {
			error(e.getMessage());
		} finally {
			resetUI(pafm.size() > lastExistingPathIdx);
		}

	}

	private ColorRGB getColor() {
		return (colorChoice.contains("distinct")) ? null : color;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(JSONImporterCmd.class, true);
	}

}
