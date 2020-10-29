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

package sc.fiji.snt.gui.cmds;

import java.awt.Dimension;
import java.util.ArrayList;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.thread.ThreadService;
import org.scijava.ui.awt.AWTWindows;

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
@Plugin(type = Command.class, menuPath = "Plugins>Neuroanatomy>Neuroanatomy Cmd Window")
public class ShortcutWindowCmd extends ContextCommand {

	@Parameter
	private CommandService cmdService;

	@Parameter
	private ThreadService threadService;
	
	private JFrame frame;

	private JPanel getPanel() {

		final Shortcut spacer = new Shortcut("spacer", null, null);
		final ArrayList<Shortcut> map = new ArrayList<>();
		map.add(new Shortcut("SNT", SNTLoaderCmd.class,
				"Initialize the complete SNT frontend. For tracing start here."));
		map.add(new Shortcut("Reconstruction Viewer", ReconstructionViewerCmd.class,
				"Initialize SNT's neuroanatomy viewer. For analysis/visualization start here."));
		map.add(new Shortcut("Reconstruction Plotter", PlotterCmd.class,
				"Create a 2D rendering of a reconstruction file (traces/json/swc)"));
		map.add(spacer);
		map.add(new Shortcut("Sholl Analysis (Image)", ShollAnalysisImgCmd.class,
				"Performs Sholl Analysis directly from a 2D/3D image"));
		map.add(new Shortcut("Sholl Analysis (Tracings)", ShollAnalysisTreeCmd.class,
				"Performs Sholl Analysis on reconstruction file(s) (traces/json/swc)"));
		map.add(spacer);
		map.add(new Shortcut("Deprecated IJ1 Cmds", CallIJ1LegacyCmd.class,
				"Runs a legacy ImageJ1-based plugin (here only for backwards compatibility)"));

		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		final Dimension prefSize = new Dimension(-1, -1);

		final ArrayList<JButton> buttons = new ArrayList<>();
		map.forEach(shrtct -> {
			if (spacer.equals(shrtct)) {
				addSpacer(panel);
			} else {
				final JButton b = new JButton(shrtct.label);
				b.setToolTipText("<html><body><div style='width:500px'>" + shrtct.description);
				setNormDimensions(prefSize, b);
				b.addActionListener(e -> {
					threadService.queue(() -> cmdService.run(shrtct.cmd, true));
				});
				panel.add(b);
				buttons.add(b);
			}
		});

		final JButton sButton = getScriptsButton();
		setNormDimensions(prefSize, sButton);
		panel.add(sButton);
		buttons.add(sButton);
		addSpacer(panel);
		final JButton hButton = getHelpButton();
		setNormDimensions(prefSize, hButton);
		panel.add(hButton);
		buttons.add(hButton);

		final Dimension maxSize = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
		buttons.forEach(b -> {
			b.setPreferredSize(prefSize);
			b.setMaximumSize(maxSize);
		});

		return panel;
	}

	private void setNormDimensions(final Dimension normSize, final JButton b) {
		final Dimension d = b.getPreferredSize();
		if (d.width > normSize.width)
			normSize.width = d.width;
		if (d.height > normSize.height)
			normSize.height = d.height;
	}

	private void addSpacer(final JPanel panel) {
		panel.add(new JLabel("<HTML>&nbsp;"));
	}

	private JButton getScriptsButton() {
		final ScriptInstaller installer = new ScriptInstaller(getContext(), getFrame());
		final JButton button = new JButton("Scripts...");
		final JPopupMenu sMenu = installer.getScriptsMenu().getPopupMenu();
		button.addActionListener(e -> sMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		return button;
	}

	private JButton getHelpButton() {
		final JButton button = new JButton("Help & Resources...");
		final JPopupMenu hMenu = GuiUtils.helpMenu().getPopupMenu();
		button.addActionListener(e -> hMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		return button;
	}

	private JFrame getFrame() {
		return (frame == null) ? new JFrame("Neuroanatomy Commands") : frame;
	}

	@Override
	public void run() {
		GuiUtils.setSystemLookAndFeel();
		frame = getFrame();
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setContentPane(getPanel());
		frame.pack();
		AWTWindows.centerWindow(frame);
		frame.setVisible(true);
		SwingUtilities.invokeLater(() -> frame.setVisible(true));
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
}
