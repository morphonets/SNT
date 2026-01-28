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

import ij.plugin.PlugIn;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.thread.ThreadService;
import org.scijava.ui.awt.AWTWindows;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.FileDrop;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.plugin.*;
import sc.fiji.snt.util.TreeUtils;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

/**
 * A command that displays a shortcut window of most popular commands, inspired
 * by the Bio-Formats Plugins Shortcut Window.
 * 
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = {
		@Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
		@Menu(label = "Neuroanatomy"), // default weights work fine
		@Menu(label = "Neuroanatomy Shortcut Window") })
public class ShortcutWindowCmd extends ContextCommand implements PlugIn {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final String HTML_TOOLTIP = "<html>";

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
		shortcuts.add(new Shortcut("Rec. Plotter...", PlotterCmd.class,
				"Create a 2D rendering of a reconstruction file (traces/json/swc)"));
		shortcuts.add(new Shortcut("Rec. Viewer", ReconstructionViewerCmd.class,
				"Initialize SNT's neuroanatomy viewer. For analysis/visualization start here."));
		addButtons(shortcuts);
		final ScriptInstaller si = new ScriptInstaller(getContext(), getFrame());
		buttons.add(null);
		addShollButton(si);
		addStrahlerButton(si);
		buttons.add(null);
		addScriptsButton();
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

	private void addStrahlerButton(final ScriptInstaller si) {
		final JPopupMenu popup = new JPopupMenu();
		final JButton button = getPopupButton(popup, "Strahler Analysis",
				"Tools for skeletonized images and reconstructions");
		JMenuItem jmi = new JMenuItem("Strahler Analysis (Image)...");
		jmi.setToolTipText("Performs analysis directly from a (skeletonized) 2D/3D image");
		jmi.addActionListener(e -> {
			try {  // FIXME: We need to adopt SciJavaCommands for this
				ij.IJ.runPlugIn(sc.fiji.snt.plugin.ij1.Strahler.class.getCanonicalName(), "");
			}
			catch (final Exception ex) {
				new GuiUtils(getFrame()).error("An error occurred. See Console for details.");
				ex.printStackTrace();
			}
		});
		popup.add(jmi);
		jmi = getScriptMenuItem(si, "Analysis", "Strahler_Analysis.py");
		jmi.setText("Strahler Analysis (Tracings)...");
		jmi.setToolTipText("Performs analysis directly from reconstruction");
		popup.add(jmi);
		addScriptsSeparator(popup);
		//popup.add(getScriptMenuItem("Analysis", "Strahler_Analysis.py")); // repeated entry
		popup.add(getScriptMenuItem(si, "Batch", "Strahler_Bulk_Analysis_(From_Reconstructions).py"));
		buttons.add(button);
	}

	private void addShollButton(final ScriptInstaller si) {
		final JPopupMenu popup = new JPopupMenu();
		final JButton button = getPopupButton(popup, "Sholl Analysis",
				"Tools for thresholded images and reconstructions");
		ArrayList<Shortcut> shortcuts = new ArrayList<>();
		shortcuts.add(new Shortcut("Sholl Analysis (Image)...", ShollAnalysisImgInteractiveCmd.class,
				"Performs Sholl Analysis directly from a 2D/3D image.<br>Interactive version for single images."));
		shortcuts.add(new Shortcut("Sholl Analysis (Tracings)...", ShollAnalysisTreeInteractiveCmd.class,
				"Performs Sholl Analysis on reconstruction file(s) (traces/json/swc).<br>Interactive version."));
		addSeparator("Interactive:", popup);
		getMenuItems(shortcuts).forEach(popup::add);
		shortcuts = new ArrayList<>();
		shortcuts.add(new Shortcut("Sholl Analysis (Image)...", ShollAnalysisImgCmd.class,
				"Performs Sholl Analysis directly from a 2D/3D image.<br>Macro recordable prompt."));
		shortcuts.add(new Shortcut("Sholl Analysis (Tracings)...", ShollAnalysisTreeCmd.class,
				"Performs Sholl Analysis on reconstruction file(s) (traces/json/swc).<br>Macro recordable prompt."));
		addSeparator("Macro Recordable:", popup);
		getMenuItems(shortcuts).forEach(popup::add);
		addScriptsSeparator(popup);
		popup.add(getScriptMenuItem(si, "Batch", "Sholl_Bulk_Analysis_(From_Reconstructions).groovy"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Bitmap_vs_Tracing_Comparison.groovy"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Convex_Hull_As_Center.groovy"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Extensive_Stats_Demo.groovy"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Extract_Profile_From_Image_Demo.py"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Merge_Grouped_Profiles.py"));
		popup.add(getScriptMenuItem(si, "Analysis", "Sholl_Rasterize_Shells.py"));
		buttons.add(button);
	}

	private JMenuItem getScriptMenuItem(final ScriptInstaller si, final String scriptDirectory, final String scriptFileName) {
		final String scriptName = scriptFileName.substring(0, scriptFileName.indexOf(".")).replace("_", " ");
		final JMenuItem jmi = new JMenuItem(scriptName);
		jmi.setToolTipText("Click holding Shift to open script");
		jmi.addActionListener(e -> {
			try {
				if ((e.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0)
					si.openScript(scriptDirectory, scriptName);
				else
					si.runScript(scriptDirectory, scriptName);
			} catch (final IllegalArgumentException ex){
				new GuiUtils(getFrame()).error(ex.getMessage());
			}
		});
		return jmi;
	}

	private void addScriptsSeparator(final JPopupMenu menu) {
		addSeparator("Related Scripts:", menu);
	}

	private void addSeparator(final String text, final JPopupMenu menu) {
		final JLabel label = GuiUtils.leftAlignedLabel(text, false);
		if (menu.getComponentCount() > 1) menu.addSeparator();
		menu.add(label);
	}

	private void addButtons(final Collection<Shortcut> shortcuts) {
		shortcuts.forEach(shrtct -> {
			final JButton b = new JButton(shrtct.label);
			b.setToolTipText(HTML_TOOLTIP + shrtct.description);
			b.addActionListener(e -> threadService.queue(() -> cmdService.run(shrtct.cmd, true)));
			makeSmallerButton(b);
			buttons.add(b);
		});
	}

	private JButton getPopupButton(final JPopupMenu popup, final String label, final String tooltip) {
		final JButton button = new JButton("<HTML>" + label + " &#9657;");
		button.setToolTipText(HTML_TOOLTIP + tooltip);
		button.addActionListener( e -> popup.show(button, button.getWidth() / 2, button.getHeight() / 2));
		makeSmallerButton(button);
		return button;
	}

	private ArrayList<JMenuItem> getMenuItems(final ArrayList<Shortcut> shortcuts) {
		final ArrayList<JMenuItem> menuItems = new ArrayList<>();
		shortcuts.forEach(shrtct -> {
			final JMenuItem jmi = new JMenuItem(shrtct.label);
			jmi.setToolTipText(HTML_TOOLTIP + shrtct.description);
			jmi.addActionListener(e -> threadService.queue(() -> cmdService.run(shrtct.cmd, true)));
			menuItems.add(jmi);
		});
		return menuItems;
	}

	private void addScriptsButton() {
		final ScriptInstaller installer = new ScriptInstaller(getContext(), getFrame());
		final JButton button = new JButton("<HTML>Scripts &#9657;");
		button.setToolTipText(HTML_TOOLTIP + "All of SNT scripts: Bulk measurements, conversions, multi-panel figures, etc.");
		final JPopupMenu sMenu = installer.getScriptsMenu().getPopupMenu();
		button.addActionListener(e -> sMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		makeSmallerButton(button);
		buttons.add(button);
	}

	private void addHelpButton() {
		final JButton button = new JButton("<HTML>Help & Resources &#9657;");
		final JPopupMenu hMenu = GuiUtils.MenuItems.helpMenu(null).getPopupMenu();
		button.addActionListener(e -> hMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		makeSmallerButton(button);
		buttons.add(button);
	}

	private void makeSmallerButton(final JButton button) {
        final Font currentFont = button.getFont();
		if (currentFont != null) {
			button.setFont(currentFont.deriveFont(currentFont.getSize() * 0.95f));
		}
        // too harsh
        //button.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
    }

	private JFrame getFrame() {
		if (frame == null) {
			frame = new JFrame("Neuroanatomy Commands");
			frame.getRootPane().putClientProperty("Window.style", "small"); // this may only work on macOS
		}
		return frame;
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
		addFileDrop(frame.getContentPane(), new GuiUtils(frame));
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

	@SuppressWarnings("unused")
	public static void resetFrameLocation() {
		ij.Prefs.saveLocation(WIN_LOC, null); // convenience methods for macro access
		if (frame != null) AWTWindows.centerWindow(frame);
	}

	public static boolean isVisible() {
		return frame != null && frame.isVisible() && frame.getState() != Frame.ICONIFIED;
	}

	@SuppressWarnings("unused")
	public static void toggleVisibility() {
		if (frame == null) {
			final ShortcutWindowCmd swc = new ShortcutWindowCmd();
			swc.setContext(SNTUtils.getContext());
			swc.run();
		} else {
			frame.setVisible(!isVisible());
			if (frame.isVisible() && frame.getState() == Frame.ICONIFIED)
				frame.setState(Frame.NORMAL);
		}
	}

	private static class Shortcut {

		final String label;
		final Class<? extends Command> cmd;
		String description;

		Shortcut(final String label, final Class<? extends Command> cmd, final String description) {
			this.label = label;
			this.cmd = cmd;
			this.description = description;
		}

	}


	private void addFileDrop(final Component component, final GuiUtils guiUtils) {
		new FileDrop(component, new FileDrop.Listener() {

			@Override
			public void filesDropped(final File[] files) {
				if (files.length == 0) { // Is this even possible?
					guiUtils.error("Dropped file(s) not recognized.");
					return;
				}
				if (files.length > 1) {
					guiUtils.error("Ony a single file can be imported using drag-and-drop.");
					return;
				}
				if (!supported(files[0])) {
					guiUtils.error(files[0].getName() + " does not seem to be a reconstruction file.");
					return;
				}
				importFile(files[0]);

			}

			private boolean supported(final File file) {
				final String filename = file.getName().toLowerCase();
				return (filename.endsWith(".traces")) || (filename.endsWith("swc")) || (filename.endsWith(".json") || (filename.endsWith(".ndf")));
			}

			void importFile(final File file) {
				final Collection<Tree> trees = Tree.listFromFile(file.getAbsolutePath());
				if (trees == null || trees.isEmpty()) {
					guiUtils.error(file.getName() + " does not seem to contain valid reconstruction(s).");
					return;
				}
				class Opener extends SwingWorker<Object, Object> {

					@Override
					public Object doInBackground() {
						if (trees.stream().anyMatch(Tree::is3D)) {
							final Viewer3D v3d = new Viewer3D(true);
							TreeUtils.assignUniqueColors(trees);
							v3d.add(trees);
							return v3d;
						} else {
							final Viewer2D v2d = new Viewer2D();
							v2d.setGridlinesVisible(false);
							v2d.add(trees);
							v2d.show();
							return v2d;
						}
					}

					@Override
					protected void done() {
						try {
							final Object viewer = get();
							if (viewer instanceof Viewer2D)
								((Viewer2D)viewer).show();
							else if (viewer instanceof Viewer3D)
								((Viewer3D)viewer).show();
						} catch (final Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}

				(new Opener()).execute();
			}
		});
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
