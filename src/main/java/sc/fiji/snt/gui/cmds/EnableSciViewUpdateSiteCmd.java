package sc.fiji.snt.gui.cmds;

import java.util.List;

/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.Types;
import net.imagej.ImageJ;
import net.imagej.updater.UpdateService;
import net.imagej.updater.UpdateSite;
import net.imagej.updater.UpdaterUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;

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

	@Override
	public void run() {
		final UpdateSite updateSite = updateService.getUpdateSite("SciView");
		if (updateSite != null && updateSite.isActive()) {
			SNTUtils.log("SciView subscription detected");
			return;
		}
		if (!new GuiUtils().getConfirmation(
				"SciView does not seem to installed in your system. Install it now? (Restart may be required)",
				"Run Updater?")) {
			return;
		}
		instructionsMsg();
		runUpdater();
	}

	private void runUpdater() {
		// This allows us to call the updater without having to depend on
		// net.imagej.ui.swing. See net.imagej.updater.CheckForUpdates
		final List<CommandInfo> updaters = cmdService.getCommandsOfType(UpdaterUI.class);
		if (updaters.size() > 0) {
			cmdService.run(updaters.get(0), true);
		} else {
			new GuiUtils().error("No updater plugins found! Please check your installation.");
		}
	}

	private void instructionsMsg() {
		final String msg =
				"Please subscribe to the Sciview <em>Bleeding Edge</em> update site:</p>\n" + "<ol start='' >\n"
						+ "<li><p>In the updater --Currently running--, add the following entry to the <em>Manage update sites</em> table, by clicking on <em>Add update site</em>:</p>\n"
						+ "<figure><table>\n" + "<thead>\n"
						+ "<tr><th style='text-align:left;' >Name</th><th style='text-align:left;' >URL</th></tr></thead>\n"
						+ "<tbody><tr><td style='text-align:left;' >SciView-edge</td><td style='text-align:left;' ><a href='https://sites.imagej.net/SciView-Unstable/' target='_blank' class='url'>https://sites.imagej.net/SciView-Unstable/</a></td></tr></tbody>\n"
						+ "</table></figure>\n" + "</li>\n"
						+ "<li><p>Activate the newly added <em>SciView-edge</em> checkbox and unselect the <em>SciView</em> checkbox if you currently subscribing to Sciview&#39;s regular channel </p>\n"
						+ "</li>\n" + "<li><p>Click <em>Apply changes</em> and restart Fiji</p>\n" + "</li>\n" + "</ol>"
						+ "See <a href='https://imagej.net/SNT#Installation'>documentation</a> for details.";
		GuiUtils.showHTMLDialog(msg, "Instructions");
	}

	public static boolean isSciViewAvailable() {
		return Types.load("sc.iview.SciView") != null && Types.load("graphics.scenery.backends.Renderer") != null;
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(EnableSciViewUpdateSiteCmd.class, true);
	}

}
