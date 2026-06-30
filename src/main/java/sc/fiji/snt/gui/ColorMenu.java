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

package sc.fiji.snt.gui;

import sc.fiji.snt.Path;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a simplified color widget holding both predetermined colors and
 * user-defined ones. It is based on Gerald Bauer's code released under GPL2
 * (<a href="http://www.java2s.com/Code/Java/Swing-JFC/ColorMenu.htm">...</a>)
 */
public class ColorMenu extends JMenu {

	@Serial
	private static final long serialVersionUID = 1L;
	private final Map<SNTColor, ColorPane> _colorPanes;
	private ColorPane _selectedColorPane;
	private final Color selectedColor;
	private final Color focusedColor;
	private final BasicStroke selectedStroke;
	private final BasicStroke focusedStroke;
	private final BasicStroke baseStroke;

	public ColorMenu(final String name) {
		super(name);
		selectedColor = GuiUtils.getSelectionColor();
		focusedColor = UIManager.getColor("Component.focusColor");
		selectedStroke = new BasicStroke(2f);
		focusedStroke = new BasicStroke(1.5f);
		baseStroke = new BasicStroke(.75f);

		final Color[] hues = new Color[] { Color.RED, Color.GREEN, Color.BLUE,
			Color.MAGENTA, Color.CYAN, Color.YELLOW, Color.ORANGE }; // 7 elements
		final float[] colorRamp = new float[] { .75f, .5f, .3f };
		final float[] grayRamp = new float[] { 1, .8f, .6f, .4f, .2f, .0f }; // 6 elements
		final JPanel defaultPanel = getGridPanel(8, 7);
		_colorPanes = new HashMap<>();

		final List<Color> colors = new ArrayList<>();
		for (final Color h : hues) {
			colors.add(h);
			final float[] hsbVals = Color.RGBtoHSB(h.getRed(), h.getGreen(), h
				.getBlue(), null);
			for (final float s : colorRamp) { // lighter colors
				final Color color = Color.getHSBColor(hsbVals[0], s * hsbVals[1],
					hsbVals[2]);
				colors.add(color);
			}
			for (final float s : colorRamp) { // darker colors
				final Color color = Color.getHSBColor(hsbVals[0], hsbVals[1], s *
					hsbVals[2]);
				colors.add(color);
			}
		}
		for (final float s : grayRamp) {
			final Color color = Color.getHSBColor(0, 0, s);
			colors.add(color);
		}
		colors.add(null); // add the null color to the end of grayRamp row

		for (final Color color : colors) {
			final ColorPane colorPane = new ColorPane(new SNTColor(color), false);
			defaultPanel.add(colorPane);
			_colorPanes.put(new SNTColor(color), colorPane);
		}
		addSeparator("Default Hues");

		add(defaultPanel);

		// Build the custom color row
//		final JPanel customPanel = getGridPanel(1, 7);
//		for (int i = 0; i < 7; i++) {
//			final Color uniquePlaceHolderColor = new Color(getBackground().getRed(),
//				getBackground().getGreen(), getBackground().getBlue(), 255 - i - 1);
//			final ColorPane customColorPane = new ColorPane(new SNTColor(
//				uniquePlaceHolderColor), true);
//			customPanel.add(customColorPane);
//			_colorPanes.put(new SNTColor(uniquePlaceHolderColor), customColorPane);
//		}
//		GuiUtils.addSeparator(this, "Custom (Right-click to Change)");
//		add(customPanel);

		// Add Kelly distinct colors
		addSeparator();
		addSeparator("Contrast Hues");
		final JPanel kellyPanel = getGridPanel(3, 7);
		final Color[] kellyColors = SNTColor.getDistinctColorsAWT(21);
		kellyColors[20] = null; // add the null color to the end of the row
		for (final Color color : kellyColors) {
			final ColorPane colorPane = new ColorPane(new SNTColor(color), false);
			kellyPanel.add(colorPane);
			_colorPanes.put(new SNTColor(color), colorPane);
		}
		add(kellyPanel);

		// mouseExited does not fire when the popup is dismissed by Escape or an
		// outside click, leaving isHovered=true on the last-hovered pane. Reset all
		// hover states whenever the popup hides.
		getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {}
			@Override public void popupMenuCanceled(final PopupMenuEvent e) {}
			@Override
			public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
				_colorPanes.values().forEach(cp -> {
					if (cp.isHovered) {
						cp.isHovered = false;
						cp.repaint();
					}
				});
			}
		});
	}

	private void addSeparator(final String header) {
		final JMenuItem sep = new JMenuItem(header);
		sep.setEnabled(false);
		sep.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR, GuiUtils.getDisabledComponentColor()));
		sep.setDisabledIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR, GuiUtils.getDisabledComponentColor()));
		add(sep);
	}

	private JPanel getGridPanel(final int rows, final int cols) {
		final JPanel panel = new JPanel();
		panel.setBorder(BorderFactory.createEmptyBorder());
		panel.setLayout(new GridLayout(rows, cols));
		return panel;
	}

	public void selectColor(final Color c) {
		selectSWCColor(new SNTColor(c));
	}

	public void selectSWCColor(final SNTColor c) {
		final ColorPane cp = _colorPanes.get(c);
		if (cp == null) {
			selectNone();
			return;
		}
		if (_selectedColorPane != null) _selectedColorPane.setSelected(false);
		_selectedColorPane = cp;
		_selectedColorPane.setSelected(true);
	}

	public SNTColor getSelectedSWCColor() {
		if (_selectedColorPane == null) return null;
		return _selectedColorPane.swcColor;
	}

	public void selectNone() {
		for (final Map.Entry<SNTColor, ColorPane> entry : _colorPanes.entrySet()) {
			entry.getValue().setSelected(false);
		}
	}

	@SuppressWarnings("unused")
	private Color getCurrentColorForSWCType(final int swcType) {
		for (final Map.Entry<SNTColor, ColorPane> entry : _colorPanes.entrySet()) {
			if (entry.getKey().type() == swcType) return entry.getKey().color();
		}
		return null;
	}

	private void doSelection() {
		fireActionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
			getActionCommand()));
	}

	private class ColorPane extends JPanel implements MouseListener {

		@Serial
		private static final long serialVersionUID = 1L;
		private SNTColor swcColor;
		private Color fillColor; // cached fill; null means "no color" swatch
		private boolean isSelected;
		private boolean isHovered;
		private final boolean isCustomizable;
		private final int baseArc;

		public ColorPane(final SNTColor sColor, final boolean customizable) {
			swcColor = sColor;
			isCustomizable = customizable;
			baseArc = UIManager.getInt("Component.arc");
			setOpaque(false); // parent paints through corners outside the rounded rect
			setPanelSWCColor(swcColor);
			addMouseListener(this);
			final int size = GuiUtils.MenuItems.defaultHeight();
			setPreferredSize(new Dimension(size, size));
		}

		private void setPanelSWCColor(final SNTColor sColor) {
			swcColor = sColor;
			if (sColor.color() == null) {
				// Use panel background for the "no color" swatch fill
				fillColor = null;
				setToolTipText("None (No Color)");
			}
			else {
				fillColor = swcColor.color();
				final String msg = (!swcColor.isTypeDefined()) ? ij.plugin.Colors.colorToString2(swcColor.color())
						: Path.getSWCtypeName(swcColor.type(), true);
				setToolTipText(msg);
			}
		}

		public void setSelected(final boolean isSelected) {
			this.isSelected = isSelected;
			if (this.isSelected) {
				_selectedColorPane = this;
			}
			repaint();
		}

		@Override
		protected void paintComponent(final Graphics g) {
			final Graphics2D g2 = (Graphics2D) g;
			GuiUtils.setRenderingHints(g2);
			final int arc = Math.min(baseArc, getWidth() / 2);
			final int pad = 1;

			// fill: use panel background as placeholder for the "no color" swatch
			g2.setColor(fillColor == null ? getBackground() : fillColor);
			g2.fillRoundRect(pad, pad, getWidth() - pad * 2 - 1, getHeight() - pad * 2 - 1, arc, arc);

			// null-color diagonal
			if (fillColor == null) {
				g2.setColor(Color.RED);
				g2.setStroke(new BasicStroke(2f));
				g2.drawLine(pad + 2, pad + 2, getWidth() - pad - 3, getHeight() - pad - 3);
			}

			// outline: hover -> focus color, selected -> accent color, default -> foreground
			final Color outline;
			BasicStroke stroke;
			if (isSelected) {
				outline = selectedColor;
				stroke = selectedStroke;
			} else if (isHovered) {
				outline = focusedColor;
				stroke = focusedStroke;
			} else {
				outline = getForeground();
				stroke = baseStroke;
			}
			g2.setColor(outline != null ? outline : getForeground());
			g2.setStroke(stroke);
			g2.drawRoundRect(pad, pad, getWidth() - pad * 2 - 1, getHeight() - pad * 2 - 1, arc, arc);
		}

		@Override
		public void mouseClicked(final MouseEvent ev) {}

		@Override
		public void mouseEntered(final MouseEvent ev) {
			isHovered = true;
			repaint();
		}

		@Override
		public void mouseExited(final MouseEvent ev) {
			isHovered = false;
			repaint();
		}

		@Override
		public void mousePressed(final MouseEvent ev) {

			assert SwingUtilities.isEventDispatchThread();

			// Ensure only this pane gets selected
			selectNone();
			setSelected(true);

			if (isCustomizable && (SwingUtilities.isRightMouseButton(ev) || ev.isPopupTrigger())) {

				// Remember menu path so that it can be restored after prompt
				final MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();

				// Prompt user for new color
				final GuiUtils gUtils = new GuiUtils(getTopLevelAncestor());
				final String promptTitle = (!swcColor.isTypeDefined()) ? "New Color"
					: "New color for SWC Type: " + Path.getSWCtypeName(swcColor.type(),
						true);
				MenuSelectionManager.defaultManager().clearSelectedPath();
				final Color c = gUtils.getColor(promptTitle, swcColor.color(), (String[])null);
				if (c != null && !c.equals(swcColor.color())) {
					// New color choice: refresh panel
					swcColor.setAWTColor(c);
					fillColor = c;
					repaint();
				}
				// Restore menu
				MenuSelectionManager.defaultManager().setSelectedPath(path);

			}
			else { // Dismiss menu
				MenuSelectionManager.defaultManager().clearSelectedPath();
			}

			doSelection();

		}

		@Override
		public void mouseReleased(final MouseEvent ev) {}
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final javax.swing.JFrame f = new javax.swing.JFrame();
		final javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
		final ColorMenu menu = new ColorMenu("Test");
		menu.addActionListener(e -> {
			System.out.println(e);
			System.out.println(menu.getSelectedSWCColor().color());
			System.out.println("Type: " + menu.getSelectedSWCColor().type());
			System.out.println(Path.getSWCtypeName(menu.getSelectedSWCColor().type(),
				false));
		});
		menuBar.add(menu);
		f.setJMenuBar(menuBar);
		f.pack();
		f.setVisible(true);
	}

}
