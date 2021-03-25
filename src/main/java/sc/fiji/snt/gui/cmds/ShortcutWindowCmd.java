/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.thread.ThreadService;
import org.scijava.ui.awt.AWTWindows;

import ij.IJ;
import ij.plugin.PlugIn;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.plugin.PlotterCmd;
import sc.fiji.snt.plugin.ShollAnalysisImgCmd;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.plugin.ij1.CallIJ1LegacyCmd;

/**
 * A command that displays a shortcut window of most popular commands, inspired
 * by the Bio-Formats Plugins Shortcut Window.
 * 
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = {
		@Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
		@Menu(label = "Neuroanatomy", weight = GuiUtils.DEFAULT_MENU_WEIGHT), //
		@Menu(label = "Neuroanatomy Shortcut Window") }, //
		headless = false)
public class ShortcutWindowCmd extends ContextCommand implements PlugIn {

	private static final String HTML_TOOLTIP = "<html><body><div style='width:500px'>";

	@Parameter
	private CommandService cmdService;

	@Parameter
	private ThreadService threadService;

	private static JFrame frame;
	private final ArrayList<JButton> buttons = new ArrayList<>();
	private static final String WIN_LOC = "snt.sw.loc";


	private JPanel getPanel() {
		final ArrayList<Shortcut> shortcuts = new ArrayList<>();
		shortcuts.add(new Shortcut("SNT...", SNTLoaderCmd.class,
				"Initialize the complete SNT frontend. For tracing start here."));
		shortcuts.add(new Shortcut("Reconstruction Plotter...", PlotterCmd.class,
				"Create a 2D rendering of a reconstruction file (traces/json/swc)"));
		shortcuts.add(new Shortcut("Reconstruction Viewer", ReconstructionViewerCmd.class,
				"Initialize SNT's neuroanatomy viewer. For analysis/visualization start here."));
		addButtons(shortcuts);
		buttons.add(null);
		addShollButton();
		addStrahlerButton();
		addScriptsButton();
		buttons.add(null);
		addButton(new Shortcut("Deprecated Cmds...", CallIJ1LegacyCmd.class,
				"Runs a legacy ImageJ1-based plugin (here only for backwards compatibility)"));
		buttons.add(null);
		addHelpButton();

		// Assemble GUI
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		final Dimension prefSize = new Dimension(-1, -1);
		buttons.forEach(button -> {
			if (button == null) {
				panel.add(new JLabel("<HTML>&nbsp;")); // spacer
			} else {
				final Dimension d = button.getPreferredSize();
				if (d.width > prefSize.width)
					prefSize.width = d.width;
				if (d.height > prefSize.height)
					prefSize.height = d.height;
				panel.add(button);
			}
		});
		final Dimension maxSize = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
		buttons.forEach(b -> {
			if (b != null) {
				b.setPreferredSize(prefSize);
				b.setMaximumSize(maxSize);
			}
		});

		return panel;
	}

	private void addStrahlerButton() {
		final JPopupMenu popup = new JPopupMenu();
		final JButton button = getPopupButton(popup, "Strahler Analysis",
				"Single file analysis (skeletonized image or reconstruction). For bulk processing see Scripts>Batch>");
		JMenuItem jmi = new JMenuItem("Strahler Analysis (Image)...");
		jmi.addActionListener(e -> {
			try {  // FIXME: We need to adopt SciJavaCommands for this
				Class.forName("ipnat.skel.Strahler");
				IJ.runPlugIn("ipnat.skel.Strahler", "");
			}
			catch (final Exception ignored) {
				new GuiUtils(getFrame()).error("Plugin was not found. Please run Fiji's updater to retrieve missing files.");
			}
		});
		popup.add(jmi);
		jmi = new JMenuItem("Strahler Analysis (Tracings)...");
		jmi.addActionListener(e -> {
			try {
				new ScriptInstaller(getContext(), getFrame()).runScript("Analysis", "Strahler Analysis");
			} catch (final IllegalArgumentException ignored){
				new GuiUtils(getFrame()).error(ignored.getMessage());
			}
		});
		popup.add(jmi);
	
		buttons.add(button);
	}

	private void addShollButton() {
		final JPopupMenu popup = new JPopupMenu();
		final JButton button = getPopupButton(popup, "Sholl Analysis",
				"Single file analysis (segmentable image or reconstruction). For bulk processing see Scripts>Batch>");
		final ArrayList<Shortcut> shortcuts = new ArrayList<>();
		shortcuts.add(new Shortcut("Sholl Analysis (Image)...", ShollAnalysisImgCmd.class,
				"Performs Sholl Analysis directly from a 2D/3D image"));
		shortcuts.add(new Shortcut("Sholl Analysis (Tracings)...", ShollAnalysisTreeCmd.class,
				"Performs Sholl Analysis on reconstruction file(s) (traces/json/swc)"));
		getMenuItems(shortcuts).forEach(mi -> popup.add(mi));
		buttons.add(button);
	}

	private void addButton(final Shortcut shortcut) {
		addButtons(Collections.singletonList(shortcut));
	}

	private void addButtons(final Collection<Shortcut> shortcuts) {
		shortcuts.forEach(shrtct -> {
			final JButton b = new JButton(shrtct.label);
				b.setToolTipText(HTML_TOOLTIP + shrtct.description);
				b.addActionListener(e -> {
					threadService.queue(() -> cmdService.run(shrtct.cmd, true));
				});
				buttons.add(b);
		});
	}

	private JButton getPopupButton(final JPopupMenu popup, final String label, final String tooltip) {
		final JButton button = new JButton("<HTML>" + label + " &#9657;");
		button.setToolTipText(HTML_TOOLTIP + tooltip);
		button.addActionListener( e -> {
			popup.show(button, button.getWidth() / 2, button.getHeight() / 2);
		});
		return button;
	}

	private ArrayList<JMenuItem> getMenuItems(final ArrayList<Shortcut> shortcuts) {
		final ArrayList<JMenuItem> menuItems = new ArrayList<>();
		shortcuts.forEach(shrtct -> {
			final JMenuItem jmi = new JMenuItem(shrtct.label);
			jmi.setToolTipText(HTML_TOOLTIP + shrtct.description);
			jmi.addActionListener(e -> {
				threadService.queue(() -> cmdService.run(shrtct.cmd, true));
			});
			menuItems.add(jmi);
		});
		return menuItems;
	}

	private void addScriptsButton() {
		final ScriptInstaller installer = new ScriptInstaller(getContext(), getFrame());
		final JButton button = new JButton("<HTML>Scripts &#9657;");
		button.setToolTipText(HTML_TOOLTIP + "Bulk measurements, conversions, multi-panel figures, etc.");
		final JPopupMenu sMenu = installer.getScriptsMenu().getPopupMenu();
		button.addActionListener(e -> sMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		buttons.add(button);
	}

	private void addHelpButton() {
		final JButton button = new JButton("<HTML>Help & Resources &#9657;");
		final JPopupMenu hMenu = GuiUtils.helpMenu().getPopupMenu();
		button.addActionListener(e -> hMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		buttons.add(button);
	}

	private JFrame getFrame() {
		return (frame == null) ? new JFrame("Neuroanatomy Commands") : frame;
	}

	@Override
	public void run() {

		if (frame != null) {
			frame.setVisible(true);
			frame.toFront();
			return;
		}

		GuiUtils.setLookAndFeel(); // needs to be called here because frame uses swing
		frame = getFrame();
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setContentPane(getPanel());
		frame.pack();
		//TODO: use ij1 for now because it detects if the location is valid. 
		final Point loc = ij.Prefs.getLocation(WIN_LOC);
		if (loc == null) 
			AWTWindows.centerWindow(frame);
		else
			frame.setLocation(loc);
		frame.setVisible(true);
		frame.addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				ij.Prefs.saveLocation(WIN_LOC, frame.getLocation());
				super.windowClosing(e);
			}
		});
		SwingUtilities.invokeLater(() -> frame.setVisible(true));
	}

	public static void resetFrameLocation() {
		ij.Prefs.saveLocation(WIN_LOC, null);
	}

	private class Shortcut {

		final String label;
		final Class<? extends Command> cmd;
		String description;

		Shortcut(final String label, final Class<? extends Command> cmd, final String description) {
			this.label = label;
			this.cmd = cmd;
			this.description = description;
		}

	}

	@Override
	public void run(final String ignored) {
		// As sad as it is, this IJ1 code is just so that we can register this command as
		// an IJ1 plugin in th plugins menu so that the Neuroanatomy menu is sorted properly. See e.g.
		// https://github.com/imagej/imagej-legacy/issues/179
		final ShortcutWindowCmd swc = new ShortcutWindowCmd();
		// get the existing Context from the running Fiji instance. 
		swc.setContext(SNTUtils.getContext());
		swc.run();
	}
}
