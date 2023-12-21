/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import ij.ImagePlus;
import ij.plugin.CompositeConverter;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;

/**
 * Command class for GUI commands extending DynamicCommand
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class)
public class CommonDynamicCmd extends DynamicCommand {

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
		startLoopProgress(recViewer);
	}

	protected void startLoopProgress(final Viewer3D recViewer) {
		if (recViewer != null && recViewer.getManagerPanel() != null) {
			recViewer.getManagerPanel().showProgress(-1, -1);
		}
	}

	protected void resetProgress(final Viewer3D recViewer) {
		if (recViewer != null && recViewer.getManagerPanel() != null) {
			recViewer.getManagerPanel().showProgress(0, 0);
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

	protected void resetUI(final Viewer3D recViewer) {
		resetUI();
		resetProgress(recViewer);
	}

	protected void notifyExternalDataLoaded() { //TODO: Implement listener
		// If a display canvas is being used notify plugin
		snt.updateDisplayCanvases();
		snt.updateAllViewers();
		snt.getPrefs().setTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, true);
	}

	protected ImagePlus comvertInPlaceToCompositeAsNeeded(ImagePlus imp) {
		if (imp.getType() == ImagePlus.COLOR_RGB && new GuiUtils(ui).getConfirmation(
				"RGB images are (intentionally) not supported. You can however convert " + imp.getTitle()
						+ " to a multichannel image. Would you like to do it now? (Import will abort if you choose \"No\")",
				"Convert to Multichannel?")) {
			imp.hide();
			imp = CompositeConverter.makeComposite(imp);
			imp.show();
		}
		return imp;
	}

	@Override
	public void run() {
		// do nothing by default
	}

}
