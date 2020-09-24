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

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.util.ColorRGB;
import org.scijava.widget.NumberWidget;

import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for loading an OBJ file in Reconstruction Viewer
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Load OBJ File(s)...")
public class LoadObjCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	@Parameter
	private UIService uiService;

	@Parameter(label = "File/Directory Path", required = true,
		description = "Path to OBJ file, or directory containing multiple OBJ files")
	private File file;

	@Parameter(label = "Transparency (%)", required = false, min = "0.5",
		max = "100", style = NumberWidget.SCROLL_BAR_STYLE,
		description = "Transparency of imported mesh")
	private double transparency;

	@Parameter(label = "Color", required = false,
		description = "Rendering color of imported mesh(es)")
	private ColorRGB color;

	@Parameter(label = "Render bounding box", required = false)
	private boolean box;

	@Parameter(required = false)
	private Viewer3D recViewer;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		try {
			if (recViewer == null) recViewer = sntService.getRecViewer();
		}
		catch (final UnsupportedOperationException exc) {
			cancel("SNT's Reconstruction Viewer is not open");
		}

		if (!file.exists()) cancel(file.getAbsolutePath() +
			" is no longer available");

		if (transparency <= 0d) transparency = 5;
		if (file.isFile()) {
			try {
				if (loadMesh(file.getAbsolutePath()))
					recViewer.validate();
				else
					cancel(file.getName() + " could not be loaded.");
			}
			catch (final IllegalArgumentException exc) {
				cancel(getExitMsg(exc.getMessage()));
			}
			return;
		}

		if (file.isDirectory()) {
			final File[] files = file.listFiles((dir, name) -> name
				.toLowerCase().endsWith("obj"));
			recViewer.setSceneUpdatesEnabled(false);
			int failures = 0;
			for (final File file : files) {
				try {
					if (!loadMesh(file.getAbsolutePath())) failures++;
				}
				catch (final IllegalArgumentException exc) {
					failures++;
				}
			}
			if (failures == files.length) {
				cancel(getExitMsg("No files imported. Invalid Directory?"));
			}
			recViewer.setSceneUpdatesEnabled(true);
			recViewer.validate();
			final String msg = "" + (files.length - failures) + "/" + files.length +
				" files successfully imported.";
			uiService.showDialog(msg, (failures == 0) ? "All Meshes Imported"
				: "Partially Successful Import");
		}
	}

	private boolean loadMesh(final String filePath) {
		final OBJMesh objMesh = new OBJMesh(filePath);
		objMesh.setColor(color, transparency);
		if (box) objMesh.setBoundingBoxColor(color);
		return recViewer.addMesh(objMesh);
	}

	private String getExitMsg(final String msg) {
		return "<HTML><body><div style='width:" + 500 + ";'> " + msg +
			" Note that the import of complex meshes is currently " +
			"not supported. If you think the specified file(s) are " +
			"valid, you could try to simplify them using, e.g., MeshLab " +
			"(http://www.meshlab.net/).";
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(LoadObjCmd.class, true);
	}

}
