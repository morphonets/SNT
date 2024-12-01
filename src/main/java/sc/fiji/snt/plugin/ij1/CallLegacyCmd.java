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

package sc.fiji.snt.plugin.ij1;

import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.LSystemsTree;

import java.util.HashMap;

/**
 * Command to invoke legacy neuroanatomy-related plugins
 *
 * @author Tiago Ferreira
 */
@SuppressWarnings("deprecation")
@Plugin(type = Command.class, visible=false, label="Run Legacy Commands")
public class CallLegacyCmd implements Command {

	private static final String SAMPLE_IMG1 = "Sample: ddaC_Neuron (581k)";
	private static final String SAMPLE_IMG2 = "Sample: Fractal Tree (23K)";
	private static final String SHOLL_IMG_LEGACY = "Sholl Analysis (Image)...";
	private static final String SHOLL_TRACES_LEGACY = "Sholl Analysis (Tracings)...";
	private static final String SHOLL_CSV_LEGACY = "Sholl Analysis (Existing Profile)...";
	private static final String SHOLL_OPTIONS_LEGACY = "Sholl Metrics & Options...";
	private static final HashMap<String, String[]> cmds = new HashMap<>();

	static {
		cmds.put(SAMPLE_IMG1, null);
		cmds.put(SAMPLE_IMG2, new String[] { LSystemsTree.class.getName(), "" });
		cmds.put(SHOLL_IMG_LEGACY, new String[] { Sholl_Analysis.class.getName(), "image" });
		cmds.put(SHOLL_TRACES_LEGACY, new String[] { ShollAnalysisPlugin.class.getName(), "" });
		cmds.put(SHOLL_CSV_LEGACY, new String[] { Sholl_Analysis.class.getName(), "csv" });
		cmds.put(SHOLL_OPTIONS_LEGACY, new String[] { ShollOptions.class.getName(), "" });
	}

	@Parameter(choices = { SAMPLE_IMG1, SAMPLE_IMG2, SHOLL_CSV_LEGACY, SHOLL_IMG_LEGACY, SHOLL_TRACES_LEGACY,
			SHOLL_OPTIONS_LEGACY }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, //
			label = "<HTML>"
					+ "These plugins are no longer maintained<br>"
					+ "and <i>will</i> be removed in the future. Please<br>"
					+ "reach out at <a href=\"https://forum.image.sc\">forum.image.sc</a> if your<br>"
					+ "workflows need to be modernized.")
	private String cmdChoice;

	@Override
	public void run() {
		try {
			if (SAMPLE_IMG1.equals(cmdChoice)) {
				ShollUtils.sampleImage().show();
			} else {
				final String[] cmdAndArg = cmds.get(cmdChoice);
				ij.IJ.runPlugIn(cmdAndArg[0], cmdAndArg[1]);
			}
		} catch (final Exception ex) {
			new GuiUtils().error("An exception occurred. Maybe the command is no longer available?",
					"Exception occurred");
			ex.printStackTrace();
		}
	}

	/* IDE debugging method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CallLegacyCmd.class, true);
	}
}
