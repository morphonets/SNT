/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

package sc.fiji.snt.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatAbstractIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.geom.*;
import java.io.File;

/**
 * Improvements to JFileChooser, namely:
 * <pre>
 * - Confirmation dialog when overriding files
 * - Accessory panel with FlatLaf buttons to:
 *   - Toggle visibility of hidden files
 *   - Filter file list by custom pattern
 *   - Reveal contents in native file explorer
 * </pre>
 * Other fixes (current directory always the root folder on linux) and tweaks
 * (drag and drop, etc.) are provided by GuiUtils to keep dependencies to a
 * minimum. TODO: submit this upstream porting to SciJava
 */
public class FileChooser extends JFileChooser {

	private static final long serialVersionUID = 9398079702362074L;
	private JToggleButton toggleHiddenFilesButton;
	private String userFilterPattern;

	public FileChooser() {
		setAcceptAllFileFilterUsed(true);
		attachAccessoryPanel();
	}

	@Override
	public void setFileHidingEnabled(final boolean b) {
		if (toggleHiddenFilesButton != null)
			toggleHiddenFilesButton.setSelected(!b);
		super.setFileHidingEnabled(b);
	}

	@Override
	public void approveSelection() {
		final File f = getSelectedFile();
		if (f.exists() && getDialogType() == SAVE_DIALOG) {
			final int result = JOptionPane.showConfirmDialog(this,
					String.format("%s already exists.%nOverwrite?", f.getName()), "Override File?",
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			switch (result) {
			case JOptionPane.YES_OPTION:
				super.approveSelection();
				return;
			case JOptionPane.CANCEL_OPTION:
				cancelSelection();
				return;
			default:
				return;
			}
		}
		super.approveSelection();
	}

	private void attachAccessoryPanel() {
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		toggleHiddenFilesButton = new JToggleButton(new HiddenFilesIcon());
		toggleHiddenFilesButton.setToolTipText("Toggle visibility of hidden files");
		panel.add(toggleHiddenFilesButton);
		toggleHiddenFilesButton.addItemListener(e -> setFileHidingEnabled(!toggleHiddenFilesButton.isSelected()));
		final JButton rescanButton = new JButton(new ReloadFilesIcon());
		rescanButton.setToolTipText("Refresh contents");
		rescanButton.addActionListener(e -> rescanCurrentDirectory());
		panel.add(rescanButton);
		final JButton filterButton = new JButton(new FilterFilesIcon());
		filterButton.setToolTipText("Filter current file list");
		filterButton.addActionListener(e -> applyFilterPattern());
		panel.add(filterButton);
		final JButton revealButton = new JButton(new RevealFilesIcon());
		revealButton.setToolTipText("Show current directory in native file explorer");
		revealButton.addActionListener(e -> {
			final File f = (getSelectedFile() == null || isMultiSelectionEnabled()) ? getCurrentDirectory()
					: getSelectedFile();
			reveal(f);
		});
		panel.add(revealButton);
		mod(toggleHiddenFilesButton, rescanButton, filterButton, revealButton);
		setAccessory(panel);
	}

	private void mod(final AbstractButton... buttons) {
		// as per FlatFileChooser.* icons
		final Insets margin = UIManager.getInsets("Button.margin");
		for (final AbstractButton b : buttons) {
			b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
			b.setMargin(margin);
			b.setFocusable(false);
		}
	}

	private void applyFilterPattern() {
		if (userFilterPattern == null)
			userFilterPattern = "";
		final String result = (String) JOptionPane.showInputDialog(this, "Only show filenames containing:",
				"Filter by Pattern", JOptionPane.PLAIN_MESSAGE, null, null, userFilterPattern);
		if (result != null && !result.isEmpty()) {
			userFilterPattern = result;
			final FileFilter filter = new FileNamePatternFilter(userFilterPattern);
			addChoosableFileFilter(filter);
			setFileFilter(filter);
		}
	}

	private void error(final String msg) {
		JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
	}

	private void fileNotAccessibleError(final File dir) {
		if (dir == null)
			error("Directory does not seem to be accessible.");
		else
			error("Could not access\n" + dir.getAbsolutePath());
	}

	protected void reveal(final File file) {
		if (file == null) {
			fileNotAccessibleError(file);
			return;
		}
		final File dir = (file.isDirectory()) ? file : file.getParentFile();
		try {
			Desktop.getDesktop().browseFileDirectory(file);
		} catch (final UnsupportedOperationException ue) {
			if (SystemInfo.isLinux)
				try {
					Runtime.getRuntime().exec(new String[] { "xdg-open", dir.getAbsolutePath() });
				} catch (final Exception ignored) {
					fileNotAccessibleError(dir);
				}
		} catch (final NullPointerException | IllegalArgumentException iae) {
			fileNotAccessibleError(dir);
		}
	}

	private static class FileNamePatternFilter extends FileFilter {
		final String pattern;

		FileNamePatternFilter(final String pattern) {
			this.pattern = pattern;
		}

		@Override
		public boolean accept(final File f) {
			return f.getName().contains(pattern);
		}

		@Override
		public String getDescription() {
			return "Files and Folders containing '" + pattern + "'";
		}

	}

	/* Icon definitions, mostly duplicated from FlatLaf */
	private static class RevealFilesIcon extends FlatAbstractIcon {

		public RevealFilesIcon() {
			// from FlatFileChooser.* icons
			super(16, 16, UIManager.getColor("Actions.Grey"));
		}

		void prepGraphics(final Graphics2D g) {
			// from FlatFileViewComputerIcon
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		}

		@Override
		protected void paintIcon(final Component c, final Graphics2D g) {
			prepGraphics(g);
			// from FlatFileViewComputerIcon
			g.draw(new RoundRectangle2D.Float(2.5f, 3.5f, 11, 7, 2, 2));
			g.drawLine(8, 11, 8, 12);
			g.draw(new Line2D.Float(4.5f, 12.5f, 11.5f, 12.5f));
		}
	}

	private static class HiddenFilesIcon extends RevealFilesIcon {

		@Override
		protected void paintIcon(final Component c, final Graphics2D g) {
			prepGraphics(g);
			// from FlatRevealIcon
			final Path2D path = new Path2D.Float(Path2D.WIND_EVEN_ODD);
			path.append(new Ellipse2D.Float(5.15f, 6.15f, 5.7f, 5.7f), false);
			path.append(new Ellipse2D.Float(6, 7, 4, 4), false);
			g.fill(path);
			final Path2D path2 = new Path2D.Float(Path2D.WIND_EVEN_ODD);
			path2.append(new Ellipse2D.Float(2.15f, 4.15f, 11.7f, 11.7f), false);
			path2.append(new Ellipse2D.Float(3, 5, 10, 10), false);
			final Area area = new Area(path2);
			area.subtract(new Area(new Rectangle2D.Float(0, 9.5f, 16, 16)));
			g.fill(area);
		}
	}

	private static class FilterFilesIcon extends RevealFilesIcon {
		private Area area;

		@Override
		protected void paintIcon(final Component c, final Graphics2D g) {
			prepGraphics(g);
			// from FlatSearchIcon
			if (area == null) {
				area = new Area(new Ellipse2D.Float(2, 2, 10, 10));
				area.subtract(new Area(new Ellipse2D.Float(3, 3, 8, 8)));
				area.add(new Area(FlatUIUtils.createPath(10.813, 9.75, 14, 12.938, 12.938, 14, 9.75, 10.813)));
			}
			g.fill(area);
		}
	}

	private static class ReloadFilesIcon extends RevealFilesIcon {

		@Override
		protected void paintIcon(final Component c, final Graphics2D g) {
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setStroke(new BasicStroke(0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			// converted from FlatLaf /demo/icons/refresh.svg (16px viewBox) scaled to 14px
			final Path2D p = new Path2D.Float();
			p.moveTo(10.820312, 10.605469);
			p.curveTo(9.796875, 11.6875, 8.328125, 12.332031, 6.722656, 12.246094);
			p.curveTo(4.136719, 12.109375, 2.085938, 10.125, 1.785156, 7.640625);
			p.lineTo(3.410156, 7.78125);
			p.curveTo(3.75, 9.363281, 5.117188, 10.582031, 6.808594, 10.671875);
			p.curveTo(7.945312, 10.730469, 8.988281, 10.265625, 9.703125, 9.488281);
			p.lineTo(7.886719, 7.667969);
			p.lineTo(12.261719, 7.667969);
			p.lineTo(12.261719, 12.042969);
			p.closePath();
			g.fill(p);
			p.moveTo(3.183594, 3.398438);
			p.curveTo(4.203125, 2.320312, 5.671875, 1.675781, 7.273438, 1.761719);
			p.curveTo(9.824219, 1.894531, 11.855469, 3.828125, 12.199219, 6.265625);
			p.lineTo(10.570312, 6.125);
			p.curveTo(10.195312, 4.589844, 8.851562, 3.417969, 7.191406, 3.328125);
			p.curveTo(6.054688, 3.269531, 5.011719, 3.734375, 4.296875, 4.511719);
			p.lineTo(6.121094, 6.335938);
			p.lineTo(1.746094, 6.335938);
			p.lineTo(1.746094, 1.960938);
			p.lineTo(3.183594, 3.398438);
			p.closePath();
			g.fill(p);
		}
	}

}
