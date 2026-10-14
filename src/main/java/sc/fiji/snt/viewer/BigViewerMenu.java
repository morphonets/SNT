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

package sc.fiji.snt.viewer;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Predicate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.gui.ContextMenuCommand;
import sc.fiji.snt.gui.NodeEditCommand;
import sc.fiji.snt.gui.IconFactory;

/**
 * Context menu of {@link Bdv}/{@link Bvv}: the counterpart of the contextual menu of the tracing canvas, with the same
 * entries, order and labels. It opens with a right-click that is not a drag (BDV/BVV use right-drag to pan). Commands
 * without a viewer equivalent are disabled and flagged with a 'crop' icon (they only apply to a materialized crop).
 * The 'Edit Mode' section is provided by {@link BigViewerEditor}.
 *
 * @author Tiago Ferreira
 */
final class BigViewerMenu {

	static final String CROP_ONLY_TIP = "Applies only to a materialized crop, not to the live Bvv/Bdv scene";

	private final AbstractBigViewer viewer;
	private final SNT snt;
	private final BigViewerEditor editor;
	private Component host; // the display the listener is attached to
	private Point rightPress;

	private final MouseAdapter listener = new MouseAdapter() {
		@Override
		public void mousePressed(final MouseEvent e) {
			rightPress = SwingUtilities.isRightMouseButton(e) ? e.getPoint() : null;
		}

		@Override
		public void mouseReleased(final MouseEvent e) {
			final Point start = rightPress;
			rightPress = null;
			if (start == null || !SwingUtilities.isRightMouseButton(e)) return;
			if (start.distance(e.getPoint()) > AbstractBigViewer.CLICK_MOVE_TOLERANCE_PX) return; // a drag
			final JPopupMenu menu = buildMenu(e, true);
			menu.show((Component) e.getSource(), e.getX(), e.getY());
		}
	};

	BigViewerMenu(final AbstractBigViewer viewer, final SNT snt, final BigViewerEditor editor) {
		this.viewer = viewer;
		this.snt = snt;
		this.editor = editor;
	}

	/** Attaches the menu to the viewer's display (once per display) */
	void install() {
		final Component canvas = viewer.getViewerCanvas();
		if (canvas == null || canvas == host) return;
		host = canvas;
		viewer.addMouseListenerToDisplay(listener);
	}

	/** A right-click at the center of the display, for programmatic use */
	private MouseEvent centerEvent() {
		return new MouseEvent(host, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, host.getWidth() / 2,
				host.getHeight() / 2, 1, true, MouseEvent.BUTTON3);
	}

	/**
	 * Runs a command of the menu without displaying it, e.g., from the Command Palette
	 *
	 * @return null if the command was run, otherwise the reason why it was not
	 */
	String run(final ContextMenuCommand cmd) {
		return run(mi -> ContextMenuCommand.of(mi) == cmd);
	}

	/**
	 * Runs the item equivalent to a tracing canvas menu item, see {@link #matcher(JMenuItem)}
	 *
	 * @return null if the item was run, otherwise the reason why it was not
	 */
	String run(final JMenuItem canvasItem) {
		return run(matcher(canvasItem));
	}

	private String run(final Predicate<JMenuItem> matcher) {
		if (host == null || !host.isDisplayable()) return "Viewer is not available";
		final JMenuItem mi = find(buildMenu(centerEvent(), false), matcher);
		if (mi == null) return "Command is not available";
		if (!mi.isEnabled()) {
			return (CROP_ONLY_TIP.equals(mi.getToolTipText())) ? "Command requires a materialized crop"
					: "Command is currently disabled. Cannot be executed.";
		}
		mi.doClick(0);
		return null;
	}

	/** Displays the menu at the center of the display, with the item of a command armed */
	void reveal(final ContextMenuCommand cmd) {
		reveal(mi -> ContextMenuCommand.of(mi) == cmd);
	}

	/** Displays the menu at the center of the display, with the equivalent of a canvas menu item armed */
	void reveal(final JMenuItem canvasItem) {
		reveal(matcher(canvasItem));
	}

	private void reveal(final Predicate<JMenuItem> matcher) {
		if (host == null || !host.isDisplayable()) return;
		final MouseEvent e = centerEvent();
		final JPopupMenu menu = buildMenu(e, false);
		menu.show(host, e.getX(), e.getY()); // show() first: it resets the selected path
		final JMenuItem mi = find(menu, matcher);
		if (mi != null) {
			MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] { menu, mi });
			mi.setArmed(true);
		}
	}

	/**
	 * Matches the viewer equivalent of a tracing canvas item (for items not tied to a ContextMenuCommand): same
	 * accelerator (labels may be dynamic) or same label
	 */
	private static Predicate<JMenuItem> matcher(final JMenuItem ref) {
		final KeyStroke ks = ref.getAccelerator();
		final String text = (ref.getText() == null) ? "" : ref.getText().trim();
		return mi -> (ks != null && ks.equals(mi.getAccelerator()))
				|| (!text.isEmpty() && text.equals(mi.getText() == null ? "" : mi.getText().trim()));
	}

	private static JMenuItem find(final JPopupMenu menu, final Predicate<JMenuItem> matcher) {
		for (final MenuElement me : menu.getSubElements()) {
			if (me instanceof JMenuItem mi && matcher.test(mi)) return mi;
		}
		return null;
	}

	/**
	 * @param pickNode whether the node at the event's position becomes the active node in Edit Mode (only for a
	 *                 real click)
	 */
	private JPopupMenu buildMenu(final MouseEvent e, final boolean pickNode) {
		final SNTUI ui = snt.getUI();
		final boolean sntPaused = ui != null && ui.getState() == SNTUI.SNT_PAUSED;
		final boolean editing = editor.isActive();
		final AbstractBigViewer.AbstractTracer tracer = viewer.menuTracer();
		final boolean tracingPaused = snt.isTracingPaused();
		final boolean tracingAvailable = tracer != null && !editing && !tracingPaused;
		final Path single = snt.getSingleSelectedPath();
		final boolean hasPaths = snt.getPathAndFillManager().size() > 0;

		final JPopupMenu menu = new JPopupMenu();
		menu.setLightWeightPopupEnabled(false); // BVV renders on a heavyweight GL canvas
		// Snapshot of the click position: the live cursor position is unreliable once the menu is open
		final double[] clickPos = viewer.resolveCursorWorldPosition();

		// Selection
		final JMenuItem grab = item(ContextMenuCommand.GRAB, KeyEvent.VK_G, 0, tracer != null && hasPaths,
				ev -> tracer.selectNearestPath(clickPos, false));
		grab.setToolTipText("Select nearest path to where the menu was opened");
		menu.add(grab);
		final JMenuItem append = item(ContextMenuCommand.APPEND, KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK,
				tracer != null && hasPaths && !editing, ev -> tracer.selectNearestPath(clickPos, true));
		append.setToolTipText("Add nearest path to current selection");
		menu.add(append);
		menu.add(item(ContextMenuCommand.DESELECT_LAST, KeyEvent.VK_G, InputEvent.ALT_DOWN_MASK,
				tracer != null && hasPaths && !editing, ev -> tracer.deselectPaths(false)));
		menu.add(item(ContextMenuCommand.SELECT_NONE, KeyEvent.VK_G,
				InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK, tracer != null && hasPaths && !editing,
				ev -> tracer.deselectPaths(true)));
		menu.add(cropOnlyItem(ContextMenuCommand.LASSO, 0));
		menu.addSeparator();

		// Display
		final JMenuItem hide = item(ContextMenuCommand.HIDE, KeyEvent.VK_H, 0, true,
				ev -> viewer.getGuiUtils().showKeyTip(snt.getPrefs(), "H", ContextMenuCommand.HIDE_KEY_TIP));
		menu.add(hide);
		menu.add(cropOnlyItem(ContextMenuCommand.ORIENTATION, KeyEvent.VK_O));
		menu.addSeparator();

		// Bookmarks and analysis
		final JMenuItem bookmark = item(ContextMenuCommand.BOOKMARK, KeyEvent.VK_M, 0, true,
				ev -> viewer.placeMarker(clickPos));
		menu.add(bookmark);
		final JMenuItem sholl = item(ContextMenuCommand.SHOLL, KeyEvent.VK_A,
				InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK, hasPaths && !editing,
				ev -> viewer.startShollAtNearestNode(clickPos));
		menu.add(sholl);
		menu.addSeparator();

		// Tracing
		menu.add(cropOnlyItem(ContextMenuCommand.BRIGHTEST, KeyEvent.VK_V));
		final JMenuItem extend = item(ContextMenuCommand.EXTEND, 0, 0,
				tracingAvailable && viewer.isTracingEnabled() && single != null && !viewer.isPathInProgress(),
				(tracer == null) ? null : tracer.getExtendSelectedPathAction());
		if (single != null) extend.setText("Continue Extending " + NodeEditCommand.shortName(single));
		menu.add(extend);
		final JMenuItem fork = item(ContextMenuCommand.FORK, 0, 0,
				tracingAvailable && viewer.isTracingEnabled() && hasPaths && !viewer.isPathInProgress(),
				(tracer == null) ? null : tracer.getForkAction(clickPos));
		// NB: mouse gestures cannot be Swing accelerators, so the shortcut is documented in the label only
		// (same as InteractiveTracerCanvas)
		fork.setText(ContextMenuCommand.FORK.label() + (viewer.getRenderingOptions().requireShiftToFork
				? " (or Alt+Shift+Click)" : " (or Alt+Click)"));
		fork.setToolTipText("In Bvv/Bdv: " + ((viewer.getRenderingOptions().requireShiftToFork)
				? "Alt+Shift+Click" : "Alt+Click") + " on a path while tracing");
		menu.add(fork);
		menu.addSeparator();
		final JMenuItem undo = (editing)
				? item(NodeEditCommand.UNDO.label(), KeyEvent.VK_Z, 0, editor.canUndo(), ev -> editor.undo())
				: item(ContextMenuCommand.UNDO_SEGMENT, KeyEvent.VK_Z, 0, tracingAvailable && tracer.canUndoSegment(),
						(tracer == null) ? null : tracer.getUndoSegmentAction());
		ContextMenuCommand.UNDO_SEGMENT.applyIcon(undo); // also if the edit-mode variant was chosen
		ContextMenuCommand.UNDO_SEGMENT.tag(undo);
		menu.add(undo);
		final JMenuItem finish = item(ContextMenuCommand.FINISH, KeyEvent.VK_ENTER, 0,
				tracingAvailable && viewer.isPathInProgress(),
				(tracer == null) ? null : tracer.getFinishPathAction());
		menu.add(finish);
		menu.addSeparator();

		// Edit Mode
		editor.addMenuItems(menu, e, clickPos, pickNode);

		// Pause
		menu.addSeparator();
		final JCheckBoxMenuItem pauseTracing = new JCheckBoxMenuItem(ContextMenuCommand.PAUSE_TRACING.label(),
				tracingPaused);
		ContextMenuCommand.PAUSE_TRACING.applyIcon(pauseTracing);
		ContextMenuCommand.PAUSE_TRACING.tag(pauseTracing);
		pauseTracing.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.SHIFT_DOWN_MASK));
		pauseTracing.setEnabled(ui != null && !editing && !viewer.isPathInProgress());
		pauseTracing.addActionListener(ev -> {
			if (ui != null) ui.pauseTracing(pauseTracing.isSelected());
		});
		menu.add(pauseTracing);
		final JCheckBoxMenuItem pauseSNT = new JCheckBoxMenuItem(ContextMenuCommand.PAUSE_SNT.label(), sntPaused);
		ContextMenuCommand.PAUSE_SNT.applyIcon(pauseSNT);
		ContextMenuCommand.PAUSE_SNT.tag(pauseSNT);
		pauseSNT.setEnabled(ui != null && !editing && !viewer.isPathInProgress());
		pauseSNT.addActionListener(ev -> {
			if (ui != null) ui.pause(pauseSNT.isSelected());
		});
		menu.add(pauseSNT);

		// SNT fully paused: disable everything except the toggle itself
		if (sntPaused) {
			for (final MenuElement me : menu.getSubElements()) {
				if (me instanceof JMenuItem mi && mi != pauseSNT) mi.setEnabled(false);
			}
			pauseSNT.setEnabled(true);
		}
		return menu;
	}

	/** As {@link #item(String, int, int, boolean, ActionListener)}, with label and icon of a shared command */
	private static JMenuItem item(final ContextMenuCommand cmd, final int keyCode, final int modifiers,
			final boolean enabled, final ActionListener l) {
		final JMenuItem item = item(cmd.label(), keyCode, modifiers, enabled, l);
		cmd.applyIcon(item);
		return cmd.tag(item);
	}

	private static JMenuItem item(final String label, final int keyCode, final int modifiers, final boolean enabled,
			final ActionListener l) {
		final JMenuItem item = new JMenuItem(label);
		if (keyCode != 0) item.setAccelerator(KeyStroke.getKeyStroke(keyCode, modifiers));
		item.setEnabled(enabled && l != null);
		if (l != null) item.addActionListener(l);
		return item;
	}

	/** As {@link #cropOnlyItem(String, int)}, for a shared command */
	private static JMenuItem cropOnlyItem(final ContextMenuCommand cmd, final int keyCode) {
		return cmd.tag(cropOnlyItem(cmd.label(), keyCode));
	}

	/**
	 * A disabled item for a canvas command that has no equivalent in the viewers
	 *
	 * @param label   the menu label
	 * @param keyCode the shortcut of the canvas command, or 0
	 */
	static JMenuItem cropOnlyItem(final String label, final int keyCode) {
		final JMenuItem item = item(label, keyCode, 0, false, null);
		if (label.startsWith(NodeEditCommand.MENU_INDENT)) {
			// indented icon needed
			IconFactory.assignDoubleIcon(item, null, null, IconFactory.GLYPH.CROP);
		} else {
			IconFactory.assignIcon(item, IconFactory.GLYPH.CROP);
		}
		item.setToolTipText(CROP_ONLY_TIP);
		return item;
	}
}
