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
import net.imagej.legacy.LegacyService;
import net.imagej.updater.UpdateService;
import net.imagej.updater.UpdateSite;
import net.imagej.updater.UpdaterUI;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.Types;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;

import java.util.List;

/**
 * Implements the 'EnableSciViewUpdateSite' command.
 * 
 * @author Tiago Ferreira
 * @author Kyle Harrington
 */
@Plugin(type = Command.class, visible = false)
public class EnableSciViewUpdateSiteCmd implements Command {

	@Parameter
	private UpdateService updateService;

	@Parameter
	private CommandService cmdService;

	@Parameter
	LegacyService legacyService;

	@Override
	public void run() {
		final UpdateSite updateSite = updateService.getUpdateSite("sciview");
		if (updateSite != null && updateSite.isActive()) {
			SNTUtils.log("sciview subscription detected");
			return;
		}
		final boolean[] prompts = new GuiUtils().getConfirmationAndOption(
				"sciview does not seem to installed in your system. Would you like to run Fiji's updater to install it?",
				"sciview Not Installed", "Open sciview documentation", true);
		if (prompts[0])
			runUpdater();
		if (prompts[1])
			legacyService.runLegacyCommand("ij.plugin.BrowserLauncher","https://docs.scenery.graphics/sciview");
	}

	private void runUpdater() {
		// This allows us to call the updater without having to depend on
		// net.imagej.ui.swing. See net.imagej.updater.CheckForUpdates
		final List<CommandInfo> updaters = cmdService.getCommandsOfType(UpdaterUI.class);
		if (!updaters.isEmpty()) {
			cmdService.run(updaters.get(0), true);
		} else {
			new GuiUtils().error("No updater plugins found! Please check your installation.");
		}
	}

	public static boolean isSciViewAvailable() {
		return Types.load("sc.iview.SciView") != null && Types.load("graphics.scenery.Scene") != null;
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(EnableSciViewUpdateSiteCmd.class, true);
	}

}
