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

package sc.fiji.snt.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;

import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.Path;

/**
 * Generates a simplified color widget holding both predetermined colors and
 * user-defined ones. It is based on Gerald Bauer's code released under GPL2
 * (http://www.java2s.com/Code/Java/Swing-JFC/ColorMenu.htm)
 */
public class ColorMenu extends JMenu {

	private static final long serialVersionUID = 1L;
	private final Map<SNTColor, ColorPane> _colorPanes;
	private ColorPane _selectedColorPane;
	private final Border _activeBorder;
	private final Border _selectedBorder;
	private final Border _unselectedBorder;

	public ColorMenu(final String name) {
		super(name);

		_unselectedBorder = new CompoundBorder(new MatteBorder(2, 2, 2, 2,
			getBackground()), new MatteBorder(1, 1, 1, 1, getForeground()));

		_selectedBorder = new CompoundBorder(new MatteBorder(1, 1, 1, 1,
			getForeground().brighter()), new MatteBorder(2, 2, 2, 2,
				getForeground()));

		_activeBorder = new CompoundBorder(new MatteBorder(2, 2, 2, 2,
			getBackground().darker()), new MatteBorder(1, 1, 1, 1, getBackground()));

		final Color[] hues = new Color[] { Color.RED, Color.GREEN, Color.BLUE,
			Color.MAGENTA, Color.CYAN, Color.YELLOW, Color.ORANGE }; // 7 elements
		final float[] colorRamp = new float[] { .75f, .5f, .3f };
		final float[] grayRamp = new float[] { 1, .8f, .6f, .4f, .2f, .0f }; // 6
																																					// elements

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
		// add the null color to the end of grayRamp row
		colors.add(null);

		for (final Color color : colors) {
			final ColorPane colorPane = new ColorPane(new SNTColor(color), false);
			defaultPanel.add(colorPane);
			_colorPanes.put(new SNTColor(color), colorPane);
		}
		addSeparator("Default:", false);
		add(defaultPanel);

		// Build the custom color row
		final JPanel customPanel = getGridPanel(1, 7);
		for (int i = 0; i < 7; i++) {
			final Color uniquePlaceHolderColor = new Color(getBackground().getRed(),
				getBackground().getGreen(), getBackground().getBlue(), 255 - i - 1);
			final ColorPane customColorPane = new ColorPane(new SNTColor(
				uniquePlaceHolderColor), true);
			customPanel.add(customColorPane);
			_colorPanes.put(new SNTColor(uniquePlaceHolderColor), customColorPane);
		}
		addSeparator("Custom (Right-click to Change):  ", true);
		add(customPanel);

		// Add Kelly distinct colors
		addSeparator();
		addSeparator("Contrast Hues:", true);
		final JPanel kellyPanel = getGridPanel(3, 7);
		final Color[] kellyColors = SNTColor.getDistinctColorsAWT(20);
		for (final Color color : kellyColors) {
			final ColorPane colorPane = new ColorPane(new SNTColor(color), false);
			kellyPanel.add(colorPane);
			_colorPanes.put(new SNTColor(color), colorPane);
		}
		add(kellyPanel);
	}

	private void addSeparator(final String title, final boolean gap) {
		final JPanel panelLabel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		panelLabel.setBackground(getBackground());
		final JLabel label = new JLabel(title);
		label.setForeground(getForeground());
		final double h = getFont().getSize() * .80; 
		final int vgap = (gap) ? (int)h : 0;
		label.setBorder(BorderFactory.createEmptyBorder(vgap, 4, 0, 4));
		label.setFont(getFont().deriveFont((float) h));
		panelLabel.add(label);
		add(panelLabel);
	}

	private JPanel getGridPanel(final int rows, final int cols) {
		final JPanel panel = new JPanel();
		panel.setBackground(getBackground());
		panel.setBorder(BorderFactory.createEmptyBorder());
		panel.setLayout(new GridLayout(rows, cols));
		return panel;
	}

	public void selectColor(final Color c) {
		selectSWCColor(new SNTColor(c));
	}

	public void selectSWCColor(final SNTColor c) {
		final Object obj = _colorPanes.get(c);
		if (obj == null) {
			selectNone();
			return;
		}
		if (_selectedColorPane != null) _selectedColorPane.setSelected(false);
		_selectedColorPane = (ColorPane) obj;
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

		private static final long serialVersionUID = 1L;
		private SNTColor swcColor;
		private boolean isSelected;
		private final boolean isCustomizable;

		public ColorPane(final SNTColor sColor, final boolean customizable) {
			swcColor = sColor;
			isCustomizable = customizable;
			setPanelSWCColor(swcColor);
			setBorder(_unselectedBorder);
			addMouseListener(this);
			final int size = GuiUtils.getMenuItemHeight();
			setPreferredSize(new Dimension(size, size));
		}

		private void setPanelSWCColor(final SNTColor sColor) {
			swcColor = sColor;
			if (sColor.color() == null) {
				setBackground(getBackground().brighter());
				setToolTipText("None (No Color)");
			}
			else {
				setBackground(swcColor.color());
				final String msg = (!swcColor.isTypeDefined()) ? ij.plugin.Colors.colorToString2(swcColor.color())
						: Path.getSWCtypeName(swcColor.type(), true);
				setToolTipText(msg);
			}
		}

		public void setSelected(final boolean isSelected) {
			this.isSelected = isSelected;
			if (this.isSelected) {
				setBorder(_selectedBorder);
				_selectedColorPane = this;
			}
			else {
				setBorder(_unselectedBorder);
			}
		}

		@Override
		public void paint(final Graphics g) {
			super.paint(g);
			if (swcColor.color() == null) {
				final Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(Color.RED); // SNTColor.contrastColor(getBackground()));
				g2.setStroke(new BasicStroke(2));
				g2.drawLine(3, 3, getWidth() - 4, getHeight() - 4);
			}
		}

		@Override
		public void mouseClicked(final MouseEvent ev) {}

		@Override
		public void mouseEntered(final MouseEvent ev) {
			setBorder(_activeBorder);
		}

		@Override
		public void mouseExited(final MouseEvent ev) {
			setBorder(isSelected ? _selectedBorder : _unselectedBorder);
		}

		@Override
		public void mousePressed(final MouseEvent ev) {

			assert SwingUtilities.isEventDispatchThread();

			// Ensure only this pane gets selected
			selectNone();
			setSelected(true);

			if (isCustomizable && (SwingUtilities.isRightMouseButton(ev) || ev
				.isPopupTrigger()))
			{

				// Remember menu path so that it can be restored after prompt
				final MenuElement[] path = MenuSelectionManager.defaultManager()
					.getSelectedPath();

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
					setBackground(c);
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
