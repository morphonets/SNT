/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.plugin;

import java.util.HashMap;
import java.util.Map;

import net.imagej.ImageJ;

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

/**
 * Command providing a GUI for {@link RoiConverter} and allowing export of
 * {@link Path}s to the IJ1 ROI Manager.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "ROI Exporter")
public class ROIExporterCmd implements Command {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	private UIService uiService;

	@Parameter
	private LogService logService;

	@Parameter
	private StatusService statusService;

	@Parameter(required = false, label = "Convert", choices = { "Path segments",
		"Branch Points", "Tips", "All" })
	private String roiChoice;

	@Parameter(required = false, label = "View", choices = { "XY (default)", "XZ",
		"ZY" })
	private String viewChoice;

	@Parameter(required = false, label = "Impose SWC colors")
	private boolean useSWCcolors;

	@Parameter(required = false, label = "Adopt path diameter as line thickness")
	private boolean avgWidth;

	@Parameter(required = false, label = "Discard existing ROIs in ROI Manager")
	private boolean discardExisting;

	@Parameter(required = true)
	private Tree tree;

	@Parameter(required = false)
	private ImagePlus imp;

    private boolean warningsExist = false;

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

        RoiConverter converter = (imp == null) ? new RoiConverter(tree) : new RoiConverter(tree, imp);
		if (converter.getParsedTree().isEmpty()) {
			warnUser("None of the input paths could be converted to ROIs.");
			return;
		}

		logService.info("Converting paths to ROIs...");
		statusService.showStatus("Converting paths to ROIs...");
		if (imp == null) {
			warn("Since no valid image data exists C,Z,T positions of ROIs may not ne set properly.");
			warn("If Path(s) are associated with a multi-dimensional image, you may need to load it before conversion");
		}
		final int skippedPaths = tree.size() - converter.getParsedTree().size();
		if (skippedPaths > 0) warn("" + skippedPaths +
			" were rejected and will not be converted");

		converter.useSWCcolors(useSWCcolors);
		converter.setStrokeWidth((avgWidth) ? -1 : 0);
        Overlay overlay = new Overlay();

		if (viewChoice.contains("XZ")) converter.setView(RoiConverter.XZ_PLANE);
		else if (viewChoice.contains("ZY")) converter.setView(
			RoiConverter.ZY_PLANE);
		else converter.setView(RoiConverter.XY_PLANE);

		roiChoice = roiChoice.toLowerCase();
		if (roiChoice.contains("all")) roiChoice = "tips branch points segments";

		int size = 0;
		if (roiChoice.contains("tips")) {
			size = overlay.size();
			converter.convertTips(overlay);
			if (overlay.size() == size) warn(noConversion("tips"));
		}
		if (roiChoice.contains("branch points")) {
			size = overlay.size();
			converter.convertBranchPoints(overlay);
			if (overlay.size() == size) warn(noConversion("branch points"));
		}
		if (roiChoice.contains("segments")) {
			size = overlay.size();
			converter.convertPaths(overlay);
			if (overlay.size() == size) warn(noConversion("segments"));
		}

		if (overlay.size() == 0) {
			warnUser("None of the input paths could be converted to ROIs.");
			return;
		}

		RoiManager rm = RoiManager.getInstance2();
		if (rm == null) rm = new RoiManager();
		else if (discardExisting) rm.reset();
		// Prefs.showAllSliceOnly = !plugin.is2D();
		// rm.setEditMode(plugin.getImagePlus(), false);
		for (final Roi roi : overlay.toArray())
			rm.addRoi(roi);
		rm.runCommand("sort");
		// rm.setEditMode(plugin.getImagePlus(), true);
		rm.runCommand("show all without labels");
		statusService.clearStatus();

		if (warningsExist) {
			warnUser(
				"ROIs generated but some exceptions occured.\nPlease see Console for details.");
		}

	}

	private String noConversion(final String roiType) {
		return "Conversion did not generated valid " + roiType +
			". Specified features do not exist on input path(s)?";
	}

	private void warn(final String msg) {
		warningsExist = true;
		logService.warn(msg);
	}

	private void warnUser(final String msg) {
		uiService.getDefaultUI().dialogPrompt(msg, "Warning",
			DialogPrompt.MessageType.WARNING_MESSAGE,
			DialogPrompt.OptionType.DEFAULT_OPTION).prompt();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", new Tree());
		ij.command().run(ROIExporterCmd.class, true, input);
	}

}
