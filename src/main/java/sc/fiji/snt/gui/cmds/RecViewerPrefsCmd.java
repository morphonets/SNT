/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import javax.swing.JOptionPane;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.NumberWidget;

import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Command implementing Reconstruction Viewer 'Preferences...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Preferences",
	initializer = "init")
public class RecViewerPrefsCmd extends ContextCommand {

	public static String DEF_SNAPSHOT_DIR = System.getProperty("user.home") +
		File.separator + "Desktop" + File.separator + "SNTsnapshots";
	public static float DEF_ROTATION_ANGLE = 360f;
	public static double DEF_ROTATION_DURATION = 12;
	public static int DEF_ROTATION_FPS = 30;
	public static String DEF_CONTROLS_SENSITIVITY = "High";
	public static String DEF_SCRIPT_EXTENSION = ".groovy";

	@Parameter(
		label = "<HTML><b>I. Snapshot Recordings:",
		required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER1;

	@Parameter(label = "Save Directory", style = "directory",
		callback = "snapshotDirChanged",
		description = "Directory where static snapshots and animated sequences will be saved")
	private File snapshotDir;

	@Parameter(label = "Animated Rotations:", required = false,
		visibility = ItemVisibility.MESSAGE)
	private String SUB_HEADER1;

	@Parameter(required = false, persist = true, label = "Rotation degrees",
		style = NumberWidget.SCROLL_BAR_STYLE, stepSize = "10", min = "30",
		max = "360d")
	private double rotationAngle;

	@Parameter(label = "Duration (s)")
	private double rotationDuration;

	@Parameter(label = "Frames per second (FPS)")
	private int rotationFPS;

	@Parameter(label = "<HTML>&nbsp;", required = false,
		visibility = ItemVisibility.MESSAGE)
	private String msg1;

	@Parameter(label = "<HTML><b>II. Keyboard &amp; Mouse Controls:",
		required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER2;

	@Parameter(label = "Default sensitivity", required = false,
		description = "The default (startup) sensivity for panning, zooming and rotating using hotkeys",
		choices = { "Low", "Medium", "High", "Highest" })
	private String sensitivity;

	@Parameter(label = "<HTML>&nbsp;", required = false,
		visibility = ItemVisibility.MESSAGE)
	private String msg2;

	@Parameter(label = "<HTML><b>III. Scripting:",
			required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER3;

	@Parameter(label = "Preferred Language", required = false,
			description = "The scripting language to be used when running \"Script This Viewer...\"",
			choices = { ".bsh", ".groovy", ".py" })
		private String scriptExtension;

	@Parameter(label = "<HTML>&nbsp;", required = false,
		visibility = ItemVisibility.MESSAGE)
	private String msg3;

	@Parameter(label = "<HTML><b>IV: Look and Feel:",
			required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER4;

	@Parameter(label = "<HTML>&nbsp;", required = false, description = "How should SNT tools look? (Restart may be required)", choices = {
			GuiUtils.LAF_DEFAULT, GuiUtils.LAF_LIGHT, GuiUtils.LAF_LIGHT_INTJ, GuiUtils.LAF_DARK,
			GuiUtils.LAF_DARCULA })
	private String laf;

	@Parameter(label = "<HTML>&nbsp;", required = false,
			visibility = ItemVisibility.MESSAGE)
	private String msg4;

	@Parameter(label = "Defaults", callback = "defaults")
	private Button defaults;

	@Parameter(label = "   Reset  ", callback = "reset")
	private Button reset;

	@Parameter(required = false)
	private Viewer3D recViewer;

	private void init() {
		if (snapshotDir == null) snapshotDir = new File(DEF_SNAPSHOT_DIR);
		snapshotDirChanged();
		if (rotationAngle == 0) rotationAngle = DEF_ROTATION_ANGLE;
		if (rotationDuration == 0) rotationDuration = DEF_ROTATION_DURATION;
		if (rotationFPS == 0) rotationFPS = DEF_ROTATION_FPS;
		if (sensitivity == null) sensitivity = DEF_CONTROLS_SENSITIVITY;
		if (scriptExtension == null) scriptExtension = DEF_SCRIPT_EXTENSION;
		laf = SNTPrefs.getLookAndFeel();
	}

	private void defaults() {
		snapshotDir = null;
		rotationAngle = 0;
		rotationDuration = 0;
		rotationFPS = 0;
		sensitivity = null;
		scriptExtension = null;
		laf = SNTPrefs.getDefaultLookAndFeel();
		init();
	}

	@SuppressWarnings("unused")
	private void reset() {
		final PrefsCmd pCmd = new PrefsCmd();
		pCmd.setContext(getContext());
		final boolean reset = new GuiUtils().getConfirmation(
				"Reset preferences to defaults?\nThis will also reset all of SNT preferences!", "Reset?");
			if (reset) {
				pCmd.clearAll();
				defaults();
				new GuiUtils().centeredMsg("Preferences reset.\nYou may need to restart"
						+ " for changes to take effect.", "Restart May Be Required");
			}
	}

	private void snapshotDirChanged() {
		if (snapshotDir != null && !snapshotDir.exists()) {
			msg1 = "<HTML>Path does not exist and will be created.";
		}
		else if (snapshotDir != null && !snapshotDir.canWrite()) {
			msg1 = "<HTML><font color='red'>Directory is not writable!";
		}
		else {
			msg1 = "<HTML>&nbsp;";
		}
	}


	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final String existingLaf = SNTPrefs.getLookAndFeel();
		if (!existingLaf.equals(laf) && recViewer != null) {
			final int ans = new GuiUtils(recViewer.getManagerPanel()).yesNoDialog(
					"It is <i>highly</i> recommended that you restart Reconstruction Viewer for changes to take effect. "
							+ "Alternatively, you can attempt to apply the new Look and Feel now, but widgets may display erratically. "
							+ "Do you want to try nevertheless?",
					"Restart Suggested", "Yes. Apply now.", "No. I will restart.");
			if (ans == JOptionPane.YES_OPTION && recViewer != null)
				recViewer.setLookAndFeel(laf);
		}

		// The sole purpose of this run() is to store the parameters using the
		// PrefService mechanism. Thus, we don't need to do nothing here
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(RecViewerPrefsCmd.class, true);
	}

}
