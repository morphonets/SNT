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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;

import org.apache.commons.lang3.StringUtils;

import sc.fiji.snt.Path;

/**
 * Node-editing commands of 'Edit Mode': single source of truth for their labels, default shortcuts and
 * enabled-state logic, so that every front-end (classic canvas, Bdv, Bvv) exposes the same menu and key bindings.
 * The operations themselves live in {@link sc.fiji.snt.PathNodeEditor}; hosts only map a command to a call and
 * supply the cursor position (see {@code BigViewerEditor}).
 *
 * @author Tiago Ferreira
 */
public enum NodeEditCommand {

	DELETE("Delete Active Node", KeyEvent.VK_D),
	INSERT("Insert New Node at Cursor Position", KeyEvent.VK_I),
	MOVE("Move Active Node to Cursor Position", KeyEvent.VK_M),
	LOCK("Lock Active Node", KeyEvent.VK_L),
	RADIUS("Set Active Node Radius...", KeyEvent.VK_R),
	TAG("Tag Active Node", KeyEvent.VK_T, "<HTML>Assigns a unique color to active node.<br>"
			+ "NB: Overall rendering of path may change once tag is applied"),
	SET_ROOT("Set Active Node as Tree Root...", 0),
	SPLIT("Split Tree at Active Node...", KeyEvent.VK_X),
	UNDO(NodeEditCommand.UNDO_LABEL, KeyEvent.VK_Z);

	/** Label of {@link #UNDO} as a compile-time constant (e.g., for {@code switch} dispatch) */
	public static final String UNDO_LABEL = "Undo Last Edit";
	private static final String UNLOCK_LABEL = "Unlock Active Node";

	private final String label;
	private final int keyCode;
	private final String tooltip;

	NodeEditCommand(final String label, final int keyCode) {
		this(label, keyCode, null);
	}

	NodeEditCommand(final String label, final int keyCode, final String tooltip) {
		this.label = label;
		this.keyCode = keyCode;
		this.tooltip = tooltip;
	}

	/**
	 * @param nodeLocked whether the active node is locked
	 * @return the menu label, which for {@link #LOCK} depends on the state of the active node
	 */
	public String label(final boolean nodeLocked) {
		return (this == LOCK && nodeLocked) ? UNLOCK_LABEL : label;
	}

	/** @return the tooltip, or null */
	public String tooltip() {
		return tooltip;
	}

	/**
	 * @param id an id as returned by {@link #actionId()}
	 * @return the matching command, or null
	 */
	public static NodeEditCommand fromActionId(final String id) {
		if (id != null) {
			for (final NodeEditCommand cmd : values()) {
				if (cmd.actionId().equals(id)) return cmd;
			}
		}
		return null;
	}

	/**
	 * Creates a menu item for this command (label, shortcut and tooltip). Its action command is {@link #actionId()}
	 *
	 * @param listener the listener to be notified when the item is chosen
	 * @return the menu item
	 */
	public JMenuItem createMenuItem(final ActionListener listener) {
		final JMenuItem item = new JMenuItem(label);
		item.setActionCommand(actionId());
		if (keyCode != 0) item.setAccelerator(KeyStroke.getKeyStroke(keyCode, 0));
		if (tooltip != null) item.setToolTipText(tooltip);
		item.addActionListener(listener);
		return item;
	}

	/** @return the menu label */
	public String label() {
		return label;
	}

	/** @return the default (unmodified) key code, or 0 if the command has no shortcut */
	public int keyCode() {
		return keyCode;
	}

	/** @return the id used to register the command in an {@link InputMap}/{@link ActionMap} */
	public String actionId() {
		return "snt-edit-" + name().toLowerCase().replace('_', '-');
	}

	/**
	 * The editor state that determines which commands are enabled
	 *
	 * @param hasNode    whether there is an active node
	 * @param nodeLocked whether the active node is locked
	 * @param canUndo    whether there are edits that can be undone
	 */
	public record MenuState(boolean hasNode, boolean nodeLocked, boolean canUndo) {
		/** @return whether {@code cmd} is currently applicable */
		public boolean isEnabled(final NodeEditCommand cmd) {
			return (cmd == UNDO) ? canUndo : hasNode;
		}
	}

	/**
	 * @param pathName the (abbreviated) name of the path to be edited, or null
	 * @return the label of the Edit Mode toggle in context menus
	 */
	public static String editToggleLabel(final String pathName) {
		return "Edit " + ((pathName == null) ? "Mode" : pathName);
	}

	/**
	 * @param p the path
	 * @return the (abbreviated) name of the path, for use in menu labels
	 */
	public static String shortName(final Path p) {
		return StringUtils.abbreviate(p.getName(), 30);
	}

	/** Prefix used to visually nest edit commands under the Edit Mode entry of a menu */
	public static final String MENU_INDENT = "  ";

	/**
	 * Appends the node-editing commands to a menu, grouped by separators. Commands that are not applicable in
	 * the given state are disabled, not omitted
	 *
	 * @param menu    the menu to be populated
	 * @param state   the editor state
	 * @param handler the callback executed when an item is chosen
	 */
	public static void addMenuItems(final JPopupMenu menu, final MenuState state,
			final Consumer<NodeEditCommand> handler) {
		for (final NodeEditCommand cmd : values()) {
			if (cmd == UNDO) continue; // shared with the tracing 'Undo Last Segment' entry (mode-aware)
			final JMenuItem item = cmd.createMenuItem(e -> handler.accept(cmd));
			item.setText(MENU_INDENT + cmd.label(state.nodeLocked()));
			item.setEnabled(state.isEnabled(cmd));
			menu.add(item);
		}
	}

	/** @return a new map with the default shortcut of every command that has one */
	public static InputMap inputMap() {
		final InputMap im = new InputMap();
		for (final NodeEditCommand cmd : values()) {
			if (cmd.keyCode != 0) im.put(KeyStroke.getKeyStroke(cmd.keyCode, 0), cmd.actionId());
		}
		return im;
	}

	/**
	 * @param handler the callback executed when a command is triggered
	 * @return a new map associating every command id of {@link #inputMap()} with an action calling {@code handler}
	 */
	public static ActionMap actionMap(final Consumer<NodeEditCommand> handler) {
		final ActionMap am = new ActionMap();
		for (final NodeEditCommand cmd : values()) {
			am.put(cmd.actionId(), new AbstractAction(cmd.label) {
				@Override
				public void actionPerformed(final ActionEvent e) {
					handler.accept(cmd);
				}
			});
		}
		return am;
	}
}
