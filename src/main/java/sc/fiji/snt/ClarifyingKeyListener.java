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

package sc.fiji.snt;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import ij.ImagePlus;
import sc.fiji.snt.gui.GuiUtils;

/**
 * This class listens for key presses in the SNT windows and their children.
 * It is used to capture special keys and to prevent the user from starting operations on the wrong window.
 * It has no scripting value.
 */
class ClarifyingKeyListener implements KeyListener, ContainerListener {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private final SNT plugin;
	private static final int DOUBLE_PRESS_INTERVAL = 300; // ms
	private long timeKeyDown = 0; // last time key was pressed
	private int lastKeyPressedCode;

	public ClarifyingKeyListener(final SNT plugin) {
		this.plugin = plugin;
	}

	public void addKeyAndContainerListenerRecursively(final Component c) {
		c.addKeyListener(this);
		if (c instanceof Container container) {
            container.addContainerListener(this);
			for (final Component child : container.getComponents()) {
				addKeyAndContainerListenerRecursively(child);
			}
		}
	}

	private void removeKeyAndContainerListenerRecursively(final Component c) {
		c.removeKeyListener(this);
		if (c instanceof Container container) {
            container.removeContainerListener(this);
			for (final Component child : container.getComponents()) {
				removeKeyAndContainerListenerRecursively(child);
			}
		}
	}

	@Override
	public void componentAdded(final ContainerEvent e) {
		addKeyAndContainerListenerRecursively(e.getChild());
	}

	@Override
	public void componentRemoved(final ContainerEvent e) {
		removeKeyAndContainerListenerRecursively(e.getChild());
	}

	@Override
	public void keyPressed(final KeyEvent e) {

		if (!plugin.isUIready()) return;

		final int keyCode = e.getKeyCode();

		if (keyCode == KeyEvent.VK_ESCAPE) {
			if (isDoublePress(e)) plugin.getUI().reset();
			else plugin.getUI().abortCurrentOperation();
		}

		else if (keyCode == KeyEvent.VK_ENTER && !(e.getSource() instanceof javax.swing.text.JTextComponent)) {
			final ImagePlus imp = plugin.getImagePlus();
			if (imp != null && imp.isVisible()) imp.getWindow().toFront();
		}

		else if (e.isShiftDown() && e.isAltDown() && (keyCode == KeyEvent.VK_A)) {
			new GuiUtils(e.getComponent().getParent()).error(
				"You seem to be trying to start Sholl analysis, but the focus is on the wrong window.\n" +
					"Bring the tracing image to the foreground and try again.");
			e.consume();
		}

	}

	@Override
	public void keyReleased(final KeyEvent e) {}

	@Override
	public void keyTyped(final KeyEvent e) {}

	private boolean isDoublePress(final KeyEvent ke) {
		if (lastKeyPressedCode == ke.getKeyCode() && ((ke.getWhen() -
			timeKeyDown) < DOUBLE_PRESS_INTERVAL)) return true;
		timeKeyDown = ke.getWhen();
		lastKeyPressedCode = ke.getKeyCode();
		return false;
	}
}
