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
import net.imagej.legacy.LegacyService;
import net.imagej.updater.UpdateService;
import net.imagej.updater.UpdateSite;
import net.imagej.updater.UpdaterUI;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.Integration;

import java.util.List;

/**
 * Prompts users to install optional software (see {@link Integration}) through
 * Fiji's updater if it is not available.
 */
@Plugin(type = Command.class, visible = false)
public class EnableIntegrationCmd implements Command {

	@Parameter
	private UpdateService updateService;

	@Parameter
	private CommandService cmdService;

	@Parameter
	private LegacyService legacyService;

	@Parameter(label = "Integration", description = "Name of an Integration constant, e.g., BVB")
	private String integration;

	@Override
	public void run() {
		final Integration target = Integration.valueOf(integration);
		if (target.isAvailable()) return;
		final GuiUtils gUtils = new GuiUtils();
		if (target.getUpdateSite() == null) { // not available through an update site
			gUtils.error(target.getDisplayName() + " does not seem to be installed in your system. "
					+ "Please see the documentation for installation instructions.",
					target.getDisplayName() + " Not Installed", target.getDocsUrl());
			return;
		}
		final UpdateSite updateSite = updateService.getUpdateSite(target.getUpdateSite());
		if (updateSite != null && updateSite.isActive()) {
			// Subscribed, but files are missing: the user needs to apply changes and restart
			SNTUtils.log(target.getDisplayName() + " subscription detected but classes are missing");
			if (gUtils.getConfirmation("The " + target.getUpdateSite() + " update site is enabled but "
					+ target.getDisplayName() + " does not seem to be installed. Please run Fiji's updater, "
					+ "apply all changes, and restart Fiji. Would you like to run the updater now?",
					target.getDisplayName() + " Not Installed")) {
				runUpdater();
			}
			return;
		}
		final String msg = target.getDisplayName() + " does not seem to be installed in your system. "
				+ "Would you like to run Fiji's updater to install it? (Update site: " + target.getUpdateSite() + ")";
		final String title = target.getDisplayName() + " Not Installed";
		if (target.getDocsUrl() == null) {
			if (gUtils.getConfirmation(msg, title)) runUpdater();
			return;
		}
		final boolean[] prompts = gUtils.getConfirmationAndOption(msg, title,
				"Open " + target.getDisplayName() + " documentation", true);
		if (prompts[0])
			runUpdater();
		if (prompts[1])
			legacyService.runLegacyCommand("ij.plugin.BrowserLauncher", target.getDocsUrl());
	}

	private void runUpdater() {
		// This allows us to call the updater without having to depend on
		// net.imagej.ui.swing. See net.imagej.updater.CheckForUpdates
		final List<CommandInfo> updaters = cmdService.getCommandsOfType(UpdaterUI.class);
		if (!updaters.isEmpty()) {
			cmdService.run(updaters.getFirst(), true);
		} else {
			new GuiUtils().error("No updater plugins found! Please check your installation.");
		}
	}

	/**
	 * Checks if an integration is available and, if not, prompts the user to
	 * install it.
	 *
	 * @param integration the integration
	 * @param cmdService  the CommandService
	 * @return true if the integration is available, false if it is not (and the
	 *         user has been prompted)
	 */
	public static boolean requireOrPrompt(final Integration integration, final CommandService cmdService) {
		if (integration.isAvailable()) return true;
		cmdService.run(EnableIntegrationCmd.class, true, "integration", integration.name());
		return false;
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(EnableIntegrationCmd.class, true, "integration", Integration.SCIVIEW.name());
	}

}
