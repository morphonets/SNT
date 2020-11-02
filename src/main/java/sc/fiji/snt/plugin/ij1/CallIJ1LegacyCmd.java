/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
import net.imagej.legacy.LegacyService;
import sc.fiji.snt.gui.GUIHelper;

import java.util.HashMap;

import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;

/**
 * Command to invoke legacy neuroanatomy-related plugins
 *
 * @author Tiago Ferreira
 */
@SuppressWarnings("deprecation")
@Plugin(type = Command.class, visible=false, label="Run IJ1 Legacy Plugin")
public class CallIJ1LegacyCmd implements Command {

	private static final String SNT_LEGACY = "Simple Neurite Tracer...";
	private static final String SHOLL_IMG_LEGACY = "Sholl Analysis (Image)...";
	private static final String SHOLL_TRACES_LEGACY = "Sholl Analysis (Tracings)...";
	private static final String SHOLL_CSV_LEGACY = "Sholl Analysis (Existing Profile)...";
	private static final String SHOLL_OPTIONS_LEGACY = "Sholl Metrics & Options...";
	private static final HashMap<String, String[]> cmds = new HashMap<>();

	static {
		cmds.put(SNT_LEGACY, new String[] { "tracing.Simple_Neurite_Tracer", "skip" }); //FIXME: reflection
		cmds.put(SHOLL_IMG_LEGACY, new String[] { Sholl_Analysis.class.getName(), "image" });
		cmds.put(SHOLL_TRACES_LEGACY, new String[] { Sholl_Analysis.class.getName(), "" });
		cmds.put(SHOLL_CSV_LEGACY, new String[] { Sholl_Analysis.class.getName(), "csv" });
		cmds.put(SHOLL_OPTIONS_LEGACY, new String[] { ShollOptions.class.getName(), "" });
	}

	@Parameter
	private Context context;

	@Parameter
	private CommandService cmdService;

	@Parameter
	private LegacyService legacyService;

	@Parameter(choices = { SNT_LEGACY, SHOLL_IMG_LEGACY, SHOLL_TRACES_LEGACY, SHOLL_CSV_LEGACY,
			SHOLL_OPTIONS_LEGACY }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, //
			label = "<HTML><div style='width:400px;text-align: left;'>"
					+ "NB: These plugins are no longer maintained and <i>will</i> be removed "
					+ "in the future. Please reach out to <a href=\"https://forum.image.sc\">forum.image.sc</a> "
					+ "if your workflows need to be modernized.</div>")
	private String cmdChoice;

	@Override
	public void run() {
		final String[] cmdAndArg = cmds.get(cmdChoice);
		try {
			legacyService.runLegacyCommand(cmdAndArg[0], cmdAndArg[1]);
		} catch (final Exception ex) {
			new GUIHelper(context).error("An exception occured. Maybe the command is no longer available?",
					"Exception occured");
			ex.printStackTrace();
		}
	}

	/* IDE debugging method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CallIJ1LegacyCmd.class, true);
	}
}
