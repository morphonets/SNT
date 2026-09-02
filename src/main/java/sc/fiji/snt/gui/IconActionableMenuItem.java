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

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.MenuSelectionManager;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link JMenuItem} whose icon reacts to clicks independently of the item's main action. Useful for entries that
 * need a secondary action (e.g., a "reveal" or "info" shortcut) triggered only when the icon itself is  clicked,
 * leaving the rest of the item free to fire the default action. While hovered, the icon gets a hand cursor and a
 * subtle highlight so it reads as its own clickable control.
 *
 * @author Tiago Ferreira
 */
public class IconActionableMenuItem extends JMenuItem {

	private final List<ActionListener> iconActionListeners = new ArrayList<>();
	private boolean iconHovered;

	public IconActionableMenuItem(final String text, final Icon icon) {
		super(text, icon);
		installHoverFeedback();
	}

	/** Registers a listener fired only when this item's icon is clicked */
	public void addIconActionListener(final ActionListener listener) {
		iconActionListeners.add(listener);
	}

	public void removeIconActionListener(final ActionListener listener) {
		iconActionListeners.remove(listener);
	}

	private Rectangle iconBounds() {
		final Icon icon = getIcon();
		if (icon == null) return new Rectangle();
		final Insets in = getInsets();
		return new Rectangle(in.left, (getHeight() - icon.getIconHeight()) / 2,
				icon.getIconWidth(), icon.getIconHeight());
	}

	private void installHoverFeedback() {
		final MouseAdapter hoverTracker = new MouseAdapter() {
			@Override
			public void mouseMoved(final MouseEvent e) {
				setIconHovered(iconBounds().contains(e.getPoint()));
			}

			@Override
			public void mouseEntered(final MouseEvent e) {
				setIconHovered(iconBounds().contains(e.getPoint()));
			}

			@Override
			public void mouseExited(final MouseEvent e) {
				setIconHovered(false);
			}
		};
		addMouseMotionListener(hoverTracker);
		addMouseListener(hoverTracker);
	}

	private void setIconHovered(final boolean hovered) {
		if (hovered == iconHovered) return;
		iconHovered = hovered;
		setCursor(hovered ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
		repaint();
	}

	private Color hoverHighlightColor() {
		final Color base = UIManager.getColor("MenuItem.selectionBackground");
		final Color c = (base != null) ? base : getForeground();
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), 90);
	}

	@Override
	public void paint(final Graphics g) {
		super.paint(g);
		if (!iconHovered) return;
		final Rectangle b = iconBounds();
		if (b.isEmpty()) return;
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(hoverHighlightColor());
		final int pad = 2;
		g2.fillRoundRect(b.x - pad, b.y - pad, b.width + 2 * pad, b.height + 2 * pad, 6, 6);
		g2.dispose();
	}

	@Override
	protected void processMouseEvent(final MouseEvent e) {
		if (e.getID() == MouseEvent.MOUSE_RELEASED && !iconActionListeners.isEmpty()
				&& iconBounds().contains(e.getPoint())) {
			fireIconAction();
			// close the menu without triggering the item's default action
			MenuSelectionManager.defaultManager().clearSelectedPath();
			e.consume();
			return;
		}
		super.processMouseEvent(e);
	}

	private void fireIconAction() {
		final ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, getActionCommand());
		iconActionListeners.forEach(l -> l.actionPerformed(event));
	}
}
