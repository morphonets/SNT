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

package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Command for (re)setting SNT Preferences.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", label = "SNT Preferences")
public class PrefsCmd extends ContextCommand {

	@Parameter
	private PrefService prefService;

	@Parameter
	protected SNTService sntService;

	@Parameter(label = "Look and feel (L&F)", required = false, persist = false,
			description = "How should SNT look? NB: This may also affect other Swing-based dialogs in Fiji.", choices = {
			GuiUtils.LAF_LIGHT, GuiUtils.LAF_LIGHT_INTJ, GuiUtils.LAF_DARK, GuiUtils.LAF_DARCULA })
	private String laf;

	@Parameter(label="Managing Themes...", callback="lafHelp")
	private Button lafHelpButton;

	@Parameter(label="Remember window locations", description="Whether position of dialogs should be preserved across restarts")
	private boolean persistentWinLoc;

	@Parameter(label="Prefer 2D display canvases", description="When no valid image exists, adopt 2D or 3D canvases?")
	private boolean force2DDisplayCanvas;

	@Parameter(label="Use compression when saving traces", description="Wheter Gzip compression should be use when saving .traces files")
	private boolean compressTraces;

	@Parameter(label="No. parallel threads",
			description="<HTML><div WIDTH=500>The max. no. of parallel threads to be used by SNT, as specified in "
					+ "IJ's Edit>Options>Memory &amp; Threads... Set it to 0 to use the available processors on your computer")
	private int nThreads;

	@Parameter(label="Reset All Preferences...", callback="reset")
	private Button resetButton;

	private SNT snt;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		snt.getPrefs().setSaveWinLocations(persistentWinLoc);
		snt.getPrefs().setSaveCompressedTraces(compressTraces);
		snt.getPrefs().set2DDisplayCanvas(force2DDisplayCanvas);
		SNTPrefs.setThreads(Math.max(0, nThreads));
		final String existingLaf = SNTPrefs.getLookAndFeel();
		SNTPrefs.setLookAndFeel(laf);
		if (!existingLaf.equals(laf) && snt.getUI() != null) {
			final int ans = new GuiUtils(snt.getUI()).yesNoDialog("It is recommended that you restart SNT for changes to take effect. "
							+ "Alternatively, you can attempt to apply the new Look and Feel now, but some widgets/icons may not display properly. "
							+ "Do you want to try nevertheless?", "Restart Suggested", "Yes. Apply now.", "No. I will restart.");
			if (ans == JOptionPane.YES_OPTION)
				snt.getUI().setLookAndFeel(laf);
		}
	}

	private void init() {
		try {
			snt = sntService.getInstance();
			persistentWinLoc = snt.getPrefs().isSaveWinLocations();
			force2DDisplayCanvas = snt.getPrefs().is2DDisplayCanvas();
			compressTraces = snt.getPrefs().isSaveCompressedTraces();
			nThreads = SNTPrefs.getThreads();
			laf = GuiUtils.LAF_DEFAULT;
		} catch (final NullPointerException npe) {
			cancel("SNT is not running.");
		}
	}

	@SuppressWarnings("unused")
	private void reset() {
		final boolean confirm = new GuiUtils().getConfirmation(
			"Reset preferences to defaults? (a restart may be required)", "Reset?");
		if (confirm) {
			clearAll();
			init(); // update prompt;
			new GuiUtils().centeredMsg("Preferences reset. You should now restart"
					+ " SNT for changes to take effect.", "Restart Required");
		}
	}

	@SuppressWarnings("unused")
	private void lafHelp() {
		laf = GuiUtils.LAF_DEFAULT;
		new GuiUtils().showHTMLDialog("<HTML>"
				+ "This option is now outdated. SNT's <i>Look and Feel</i> (L&F) preference has been integrated into Fiji. "
				+ "Please set SNT's L&F to 'Default' and use Fiji's <i>Edit>Look and Feel...</i> prompt instead.<br><br>"
				+ "Note that setting a L&F does not affect AWT widgets. Thus, while a dark theme can be applied "
				+ "to SNT (and other Fiji components like the Script Editor), it is currently not possible to "
				+ "apply a dark theme to ImageJ's built-in dialogs, macro prompts, and dialogs of certain legacy plugins.",
				"Managing Themes", true);
	}

	/** Clears all of SNT preferences. */
	public void clearAll() {

		final String[] packages = new String[] { //
				"sc.fiji.snt", //
				"sc.fiji.snt.analysis", //
				"sc.fiji.snt.analysis.graph", //
				"sc.fiji.snt.analysis.sholl", //
				"sc.fiji.snt.analysis.sholl.gui", //
				"sc.fiji.snt.analysis.sholl.math", //
				"sc.fiji.snt.analysis.sholl.parsers", //
				"sc.fiji.snt.annotation", //
				"sc.fiji.snt.event", //
				"sc.fiji.snt.filter", //
				"sc.fiji.snt.gui", //
				"sc.fiji.snt.gui.cmds", //
				"sc.fiji.snt.hyperpanes", //
				"sc.fiji.snt.io", //
				"sc.fiji.snt.plugin", //
				"sc.fiji.snt.plugin.ij1", //
				"sc.fiji.snt.tracing", //
				"sc.fiji.snt.tracing.artist", //
				"sc.fiji.snt.tracing.cost", //
				"sc.fiji.snt.tracing.heuristic", //
				"sc.fiji.snt.tracing.image", //
				"sc.fiji.snt.util", //
				"sc.fiji.snt.viewer", //
				"sc.fiji.snt.viewer.geditor" //
		};
		for (final String pkg : packages) {
			SNTUtils.log("Deleting prefs for " + pkg + ".*");
			findClasses(pkg).forEach(c -> prefService.clear(c));
		}
		// Legacy (IJ1-based) preferences
		SNTPrefs.clearAll();
	}

	private Set<Class<?>> findClasses(final String packageName) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(packageName.replaceAll("[.]", "/"));
		final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		final Set<Class<?>> set = reader.lines().filter(line -> line.endsWith(".class") && !line.contains("$"))
				.map(line -> getClass(line, packageName)).collect(Collectors.toSet());
		set.remove(null);
		return set;
	}

	private Class<?> getClass(final String className, final String packageName) {
		try {
			final Class<?> c = Class.forName(packageName + "." + className.substring(0, className.lastIndexOf('.')));
			return (c.isAnnotation() || c.isInterface()) ? null : c;
		} catch (final ClassNotFoundException ignored) {
			// do nothing
		}
		return null;
	}

	public static void wipe() {
		final PrefsCmd prefs = new PrefsCmd();
		final Context ctx = new Context(PrefService.class, SNTService.class);
		prefs.setContext(ctx);
		prefs.clearAll();
		ctx.dispose();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		SNTUtils.setDebugMode(true);
		ij.command().run(PrefsCmd.class, true);
	}

}
