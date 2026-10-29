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

import java.io.File;
import java.io.IOException;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;

import sc.fiji.snt.SNTService;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Command saving the contents of a Reconstruction Viewer scene to a directory:
 * reconstructions as .traces files and meshes as .obj files.
 */
@Plugin(type = Command.class, visible = false, label = "Save Scene...")
public class SaveSceneCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	@Parameter(label = "Directory", style = FileWidget.DIRECTORY_STYLE,
		description = "Destination folder. Reconstructions are saved as .traces files and meshes as .obj files. Annotations are not saved")
	private File directory;

	@Parameter(label = "Visible items only", required = false,
		description = "If checked, items hidden in the scene are not saved")
	private boolean visibleOnly = true;

	@Parameter(required = false)
	private Viewer3D recViewer;

	@Override
	public void run() {
		try {
			if (recViewer == null) recViewer = sntService.getRecViewer();
		} catch (final UnsupportedOperationException exc) {
			cancel("SNT's Reconstruction Viewer is not open");
			return;
		}
		try {
			final int n = recViewer.saveScene(directory, visibleOnly);
			final GuiUtils gui = new GuiUtils();
			if (n == 0) gui.error("No files were saved. Is the scene empty or are all items hidden?");
			else gui.centeredMsg(n + " file(s) saved to " + directory.getAbsolutePath(), "Scene Saved");
		} catch (final IOException | IllegalArgumentException exc) {
			cancel("Scene could not be saved: " + exc.getMessage());
		}
	}
}
