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
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.gui.GuiUtils;

import java.awt.*;
import java.io.File;

/**
 * Command for highlighting the correspondences between two reconstructions in
 * the Legacy 3D Viewer
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Show Correspondences...")
public class ShowCorrespondencesCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	@Parameter(label = "SWC file", style = "extensions:swc/eswc")
	private File file;

	@Parameter(label = "Distance for node correspondence",
		description = "The confinement distance for node correspondence between the two traced structures.")
	private double distance;

	@Parameter(label = "Color", description = "Color for rendering imported file")
	private ColorRGB color;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@SuppressWarnings("deprecation")
	@Override
	public void run() {
		try {
			sntService.getPlugin().showCorrespondencesTo(file, new Color(color
				.getARGB()), distance);
		}
		catch (final UnsupportedOperationException | NullPointerException exc) {
			cancel("SNT's Legacy Viewer is not open");
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ShowCorrespondencesCmd.class, true);
	}

}
