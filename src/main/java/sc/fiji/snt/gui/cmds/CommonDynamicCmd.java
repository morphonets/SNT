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

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Command class for GUI commands extending DynamicCommand
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class)
public class CommonDynamicCmd extends DynamicCommand {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	protected static final String HEADER_HTML = "<html><body><div style='font-weight:bold;'>";
	protected static final String EMPTY_LABEL = "<html>&nbsp;";

	@Parameter
	protected StatusService statusService;

	@Parameter
	protected UIService uiService;

	@Parameter
	protected SNTService sntService;

	protected SNT snt;
	protected SNTUI ui;

	protected void init(final boolean abortIfInactive) {
		if (abortIfInactive && !sntService.isActive()) {
			error("SNT is not running.");
			return;
		}
		snt = sntService.getInstance();
		ui = sntService.getUI();
		if (ui != null) ui.changeState(SNTUI.RUNNING_CMD);
	}

	protected void status(final String statusMsg, final boolean temporaryMsg) {
		if (ui == null) {
			statusService.showStatus(statusMsg);
		}
		else {
			ui.showStatus(statusMsg, temporaryMsg);
		}
	}

	@Override
	public boolean isCanceled() {
		return super.isCanceled() || (ui != null && SNTUI.READY == ui.getState());
	}

	@Override
	public void cancel() {
		resetUI();
		super.cancel();
	}

	@Override
	public void cancel(final String reason) {
		resetUI();
		super.cancel(reason);
	}

	protected void error(final String msg) {
		if (ui != null) {
			ui.error(msg);
			cancel();
		}
		else {
			cancel("<HTML>"+msg);
			cancel();
		}
	}

	protected void msg(final String msg, final String title) {
		if (ui != null) {
			ui.showMessage(msg, title);
		}
		else {
			uiService.showDialog(msg, title);
		}
	}

	protected void notifyLoadingStart(final Viewer3D recViewer) {
		if (ui != null) ui.changeState(SNTUI.LOADING);
		if (recViewer != null) {
			recViewer.setSceneUpdatesEnabled(false);
			if (recViewer.getManagerPanel() != null)
				recViewer.getManagerPanel().showProgress(-1, -1);
		}
	}

	protected void notifyLoadingEnd(final Viewer3D recViewer) {
		if (recViewer != null) {
			if (recViewer.getManagerPanel() != null)
				recViewer.getManagerPanel().showProgress(0, 0);
			recViewer.setSceneUpdatesEnabled(true);
		}
	}

	protected void resetUI() {
		resetUI(false);
	}

	protected void resetUI(final boolean validateDimensions) {
		resetUI(validateDimensions, SNTUI.READY);
	}

	protected void resetUI(final boolean validateDimensions, final int state) {
		if (ui != null) {
			ui.changeState(state);
			if (validateDimensions && !isCanceled())
				ui.runCommand("validateImgDimensions");
		}
		statusService.clearStatus();
	}

	/**
	 * Finds the SciJava-generated prompt dialog by its title and attaches a
	 * {@link WindowAdapter} that resets the UI state to {@link SNTUI#READY} when
	 * the dialog is closed. This is necessary because {@link #init(boolean)} sets
	 * the state to {@link SNTUI#RUNNING_CMD}, but if the user dismisses the
	 * dialog without running the command, nothing else resets it.
	 * <p>
	 * Subclasses implementing {@link org.scijava.command.Interactive} should call
	 * this method early (e.g., from {@link #run()}) to ensure the listener is
	 * attached before the user can close the dialog. The dialog is cached: repeated
	 * calls are no-ops once it has been found.
	 *
	 * @param title  the exact title set via {@code getInfo().setLabel()} — must be
	 *               unique across open dialogs
	 * @return the dialog, or {@code null} if not yet created by SciJava
	 */
	protected JDialog getPromptWithCloseHandler(final String title) {
		return getPromptWithCloseHandler(title, null);
	}

	/**
	 * Variant of {@link #getPromptWithCloseHandler(String)} that runs an extra
	 * action when the dialog is closing (e.g., cleaning up listeners).
	 *
	 * @param title         the dialog title
	 * @param extraOnClose  additional action to run on {@code windowClosing}, or
	 *                      {@code null} for none. Runs <em>before</em> the UI
	 *                      state is reset.
	 * @return the dialog, or {@code null} if not yet created
	 */
	protected JDialog getPromptWithCloseHandler(final String title, final Runnable extraOnClose) {
		if (cachedPrompt == null) {
			for (final Window w : JDialog.getWindows()) {
				if (w instanceof JDialog && title.equals(((JDialog) w).getTitle())) {
					cachedPrompt = (JDialog) w;
					cachedPrompt.addWindowListener(new WindowAdapter() {
						@Override
						public void windowClosing(final WindowEvent ignored) {
							if (extraOnClose != null) extraOnClose.run();
							if (ui != null) ui.changeState(SNTUI.READY);
						}
					});
					break;
				}
			}
		}
		return cachedPrompt;
	}

	private JDialog cachedPrompt;

	protected void notifyExternalDataLoaded() { //TODO: Implement listener
		// If a display canvas is being used notify plugin
		snt.updateDisplayCanvases();
		snt.updateAllViewers();
		snt.getPrefs().setTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, true);
	}

	@Override
	public void run() {
		// do nothing by default
	}

}
