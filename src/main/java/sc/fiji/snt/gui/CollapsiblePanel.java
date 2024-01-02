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

import java.awt.Component;
import java.awt.Window;
import java.awt.event.ItemEvent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.icons.FlatCheckBoxIcon;

/**
 * A user-triggered collapsible panel.
 */
public class CollapsiblePanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private final JCheckBox checkbox;
	private final Component contents;

	public CollapsiblePanel(final String header, final Component contents) {
		this(header, contents, true);
	}

	/**
	 * Default constructor.
	 * 
	 * @param header         the label for the collapsible button (Implemented as a
	 *                       checkbox)
	 * @param contents       the Component to be collapsed (typically a JPanel)
	 * @param collapsedState Whether {@contents} should be displayed collapsed by
	 *                       default.
	 */
	public CollapsiblePanel(final String header, final Component contents, final boolean collapsedState) {
		checkbox = new JCheckBox(header, !collapsedState);
		checkbox.addItemListener(e -> setCollapsed(e.getStateChange() == ItemEvent.DESELECTED));
		this.contents = contents;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		add(leftJustifiedCheckbox());
		add(indentedContents());
		setCollapsed(collapsedState);
	}

	private Component leftJustifiedCheckbox() {
		final Box b = Box.createHorizontalBox();
		b.add(checkbox);
		b.add(Box.createHorizontalGlue());
		return b;
	}

	private Component indentedContents() {
		final Box b = Box.createHorizontalBox();
		final int indent = new FlatCheckBoxIcon().getIconWidth() + 2 * checkbox.getIconTextGap();
		b.add(Box.createHorizontalStrut(indent));
		b.add(contents);
		return b;
	}

	public void setCollapsed(final boolean collapse) {
		contents.setVisible(!collapse);
		final Window parent = SwingUtilities.windowForComponent(this);
		if (parent != null)
			parent.pack();
	}

	public boolean isCollapsed() {
		return contents.isVisible();
	}
	
	public void setTooltipText(final String text) {
		checkbox.setToolTipText(text);
	}
}
