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

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import sc.fiji.snt.Path;
import sc.fiji.snt.PathNodeCanvas;
import sc.fiji.snt.PathNodeEditor;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.NodeEditCommand;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;

/**
 * 'Edit Mode' for {@link Bdv}/{@link Bvv}: node-level editing of a single, selected path, mirroring the Edit-path
 * commands of the classic canvas (see {@link PathNodeEditor} for the operations themselves). This class only
 * handles the interaction layer:
 * <ul>
 * <li><b>Activation</b>: gated on a single selected path. While active, tracing is suspended in the viewer.</li>
 * <li><b>Active node</b>: set by hovering over the nodes of the editing path (screen-space hit test against a
 * dedicated {@link AbstractBigViewer.AnnotationOverlay} layer, so it is perspective-correct in Bvv). A locked node
 * stays active irrespective of the cursor.</li>
 * <li><b>Commands</b>: single-key shortcuts (same keys as the classic canvas) and a context menu opened by a
 * right-click that is not a drag (so that right-drag panning is unaffected). The menu is always available (node
 * commands are disabled unless in Edit Mode).</li>
 * </ul>
 * Not covered (yet): paint mode and "bring node to current Z-plane" (no meaningful analogue in a 3D scene).
 *
 * @author Tiago Ferreira
 */
final class BigViewerEditor {

	/** Annotation layer holding all the nodes of the editing path (hit-test target) */
	private static final class NodesLayer {}

	/** Annotation layer holding the active node only (cheap to refresh on hover) */
	private static final class ActiveLayer {}

	/** Annotation layer holding the active node of the secondary editing path (see {@link #connect()}) */
	private static final class SecondaryLayer {}

	private static final float NODE_SCALE = 0.5f;
	private static final float ACTIVE_SCALE = 1.2f;
	private static final double NODE_ALPHA_PERCENT = 80;
	private static final Color SECONDARY_COLOR = new Color(255, 140, 0, 230);


	/** Node markers use the color of selected paths, as the nodes of the tracing canvas. Resolved on demand: users can change it */
	private static Color nodeColor() {
		return SNTColor.alphaColor(SNTPrefs.selectedPathColor(), NODE_ALPHA_PERCENT);
	}

	/** The active node stands out against the other nodes, whatever the selection color is */
	private static Color activeColor() {
		return SNTColor.contrastHueColor(SNTPrefs.selectedPathColor());
	}

	private static Color lockedColor() {
		return PathNodeCanvas.lockColor(activeColor());
	}

	private final AbstractBigViewer viewer;
	private final SNT snt;
	private final PathNodeEditor ed;
	private boolean active;
	private boolean popupShowing;
	private JPopupMenu popup;
	private String exitMessage;

	/** Tracks whether one of our popups is showing: active node picking is suspended while it is */
	private final PopupMenuListener popupStateListener = new PopupMenuListener() {
		@Override
		public void popupMenuWillBecomeVisible(final PopupMenuEvent ev) {
			popupShowing = true;
		}

		@Override
		public void popupMenuWillBecomeInvisible(final PopupMenuEvent ev) {
			popupShowing = false;
		}

		@Override
		public void popupMenuCanceled(final PopupMenuEvent ev) {
			popupShowing = false;
		}
	};

	/** Active node picking. Only attached while in Edit Mode */
	private final MouseAdapter hoverHandler = new MouseAdapter() {
		@Override
		public void mouseMoved(final MouseEvent e) {
			onHover(e);
		}
	};

	BigViewerEditor(final AbstractBigViewer viewer, final SNT snt) {
		this.viewer = viewer;
		this.snt = snt;
		this.ed = snt.getNodeEditor(); // shared with the tracing canvas and other viewers
	}

	boolean isActive() {
		return active;
	}

	/**
	 * @return a message explaining why Edit Mode cannot be entered from this viewer at the moment, or null
	 */
	private String entryBlocker() {
		final String blocker = snt.getEditModeBlocker();
		if (blocker != null) return blocker;
		if (viewer.annotations() == null || viewer.getViewerCanvas() == null) return "Edit Mode unavailable: no annotation layer";
		if (viewer.isPathInProgress()) return "Edit Mode: Finish or discard the current path first";
		return null;
	}

	/** Checks viewer-specific prerequisites, and asks SNT to enable Edit Mode (see {@link #onSntEditModeChanged}) */
	private void requestEnter() {
		final String blocker = entryBlocker();
		if (blocker != null) {
			viewer.getGuiUtils().error(blocker);
			return;
		}
		final Path single = snt.getSingleSelectedPath();
		if (single != null) snt.enterEditMode(single);
		// On success, SNT calls onSntEditModeChanged(true)
	}

	/** Called (via the viewer) when SNT enables/disables Edit Mode, regardless of the front-end that triggered it */
	void onSntEditModeChanged(final boolean enabled) {
		if (enabled) activate();
		else deactivate();
	}

	void toggle() {
		if (snt.isEditModeEnabled()) exit(null);
		else requestEnter();
	}

	private void activate() {
		if (active) return;
		// Tracing must be suspended even if the interaction layer is unavailable (SNT is in Edit Mode regardless).
		// It is unlocked when SNT leaves Edit Mode (see SNT#resetStateAfterEditMode)
		viewer.setTracingLockedByPause(true);
		final Component canvas = viewer.getViewerCanvas();
		if (canvas == null || viewer.annotations() == null) return;
		active = true;
		canvas.addMouseMotionListener(hoverHandler);
		viewer.installEditKeybindings(buildInputMap(), buildActionMap());
		refreshNodes();
	}

	/**
	 * Exits Edit Mode on all front-ends
	 *
	 * @param msg message to be displayed by the viewer once exited, or null
	 */
	void exit(final String msg) {
		exitMessage = msg;
		if (snt.isEditModeEnabled()) snt.exitEditMode(); // SNT calls back onSntEditModeChanged(false)
		else deactivate();
	}

	/**
	 * Removes the viewer-side interaction layer. The editing path, active node and the tracing state are managed by
	 * SNT (see {@code SNT#enableEditMode(boolean)}), so they are not touched here
	 */
	private void deactivate() {
		deactivate(true);
	}

	/** @param notify false if the viewer is being disposed: only listeners are released */
	private void deactivate(final boolean notify) {
		if (!active) return;
		active = false;
		final Component canvas = viewer.getViewerCanvas();
		if (canvas != null) {
			canvas.removeMouseMotionListener(hoverHandler);
		}
		if (popup != null) popup.setVisible(false);
		if (!notify) return;
		viewer.removeEditKeybindings();
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann != null) {
			ann.clear(NodesLayer.class);
			ann.clear(ActiveLayer.class);
			ann.clear(SecondaryLayer.class);
		}
		viewer.showViewerMessage((exitMessage == null) ? "Edit Mode off" : exitMessage);
		exitMessage = null;
	}

	/**
	 * Called when the viewer is being disposed. In Stream mode (no tracing canvas) the viewers are the only
	 * front-ends: closing the last one that is editing ends Edit Mode, so that SNT is not left in an unreachable state
	 */
	void onViewerDisposed() {
		final boolean wasActive = active;
		deactivate(false);
		if (!wasActive || !snt.isStreamMode()) return;
		final sc.fiji.snt.SNTUI ui = snt.getUI();
		if (ui != null && ui.isOtherBigViewerEditing(viewer)) return;
		if (SwingUtilities.isEventDispatchThread()) snt.exitEditMode();
		else SwingUtilities.invokeLater(snt::exitEditMode);
	}

// picking and markers

	private void onHover(final MouseEvent e) {
		if (!active || popupShowing || ed.isActiveNodeLocked()) return;
		// Like the classic canvas, the active node persists when the cursor moves away from it
		// (so that 'Move' and 'Insert' can target a cursor position away from the node)
		final int idx = pick(e.getX(), e.getY());
		if (idx >= 0 && idx != ed.getActiveNode()) {
			ed.setActiveNode(idx);
			refreshActiveMarker();
			syncActiveNode();
		}
	}

	/** Makes the tracing canvas and the other viewer (if any) reflect the active node/lock state */
	private void syncActiveNode() {
		snt.repaintAllPanes();
		snt.notifyActiveNodeChanged(viewer);
	}

	private int pick(final int screenX, final int screenY) {
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		return (ann == null) ? -1 : ann.hitTest(NodesLayer.class, screenX, screenY);
	}

	/** Rebuilds the overlay layer of all the nodes of the editing path, and the active marker */
	void refreshNodes() {
		final Path p = ed.getPath();
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann == null) return;
		// As in the tracing canvas, a deselected editing path is not shown as being edited: the node markers
		// use the color of selected paths and would otherwise make it look selected
		if (p == null || !p.isSelected()) {
			ann.clear(NodesLayer.class);
			ann.clear(ActiveLayer.class);
			ann.clear(SecondaryLayer.class);
			return;
		}
		final float size = viewer.getDefaultMarkerSize() * NODE_SCALE;
		final Color nodeColor = nodeColor();
		final List<SNTPoint> points = new ArrayList<>(p.size());
		final List<Float> sizes = new ArrayList<>(p.size());
		final List<Color> colors = new ArrayList<>(p.size());
		for (int i = 0; i < p.size(); i++) {
			points.add(p.getNode(i));
			sizes.add(size);
			colors.add(nodeColor);
		}
		ann.replaceAll(NodesLayer.class, points, sizes, colors);
		refreshActiveMarker();
		refreshSecondaryMarker();
	}

	/** Highlights the active node of the secondary path: the target of {@link #connect()} */
	private void refreshSecondaryMarker() {
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann == null) return;
		final Path sec = ed.getSecondaryPath();
		final int idx = (sec == null) ? -1 : sec.getEditableNodeIndex();
		if (sec == null || sec == ed.getPath() || idx < 0 || idx >= sec.size()) {
			ann.clear(SecondaryLayer.class);
			return;
		}
		ann.replaceAll(SecondaryLayer.class, List.of(sec.getNode(idx)),
				List.of(viewer.getDefaultMarkerSize() * ACTIVE_SCALE), List.of(SECONDARY_COLOR));
	}

	void refreshActiveMarker() {
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann == null) return;
		final Path p = ed.getPath();
		final int idx = ed.getActiveNode();
		if (p == null || !p.isSelected() || idx < 0 || idx >= p.size()) {
			ann.clear(ActiveLayer.class);
			return;
		}
		ann.replaceAll(ActiveLayer.class, List.of(p.getNode(idx)), List.of(viewer.getDefaultMarkerSize() * ACTIVE_SCALE),
				List.of(ed.isActiveNodeLocked() ? lockedColor() : activeColor()));
	}

	// commands

	/** Reports the outcome of an operation and refreshes the viewer as needed */
	private void report(final PathNodeEditor.Result r) {
		if (r == null) return;
		r.dispatch(res -> {
			// Editing path no longer valid (e.g., re-root/split/delete). Re-sync paths and leave edit mode
			viewer.syncPathManagerList();
			if (!res.ok() && res.message() != null) {
				viewer.getGuiUtils().error(res.message()); // failure after the path was released
				exit(null);
			} else {
				exit(res.message());
			}
		}, res -> {
			viewer.syncPathManagerList();
			viewer.invalidatePathGeometry(); // moved/inserted nodes do not alter the cached fingerprint
			snt.repaintAllPanes(); // tracing canvas, if any
			refreshNodes();
			snt.notifyEditingPathChanged(); // other viewers (throttled)
			showMessage(res);
		}, res -> {
			refreshActiveMarker();
			showMessage(res);
		});
	}

	private void showMessage(final PathNodeEditor.Result r) {
		if (r.message() == null) return;
		if (r.modal()) viewer.getGuiUtils().error(r.message());
		else viewer.showViewerMessage(r.message());
	}

	private void doDelete() {
		report(ed.deleteNodeOrPath(viewer.getGuiUtils()));
	}

	private void doInsert(final double[] pos) {
		if (pos == null) {
			viewer.showViewerMessage("Could not resolve cursor position");
			return;
		}
		report(ed.insertNode(pos[0], pos[1], pos[2]));
	}

	private void doMove(final double[] pos) {
		if (pos == null) {
			viewer.showViewerMessage("Could not resolve cursor position");
			return;
		}
		report(ed.moveNode(pos[0], pos[1], pos[2]));
	}

	private void doLock() {
		final PathNodeEditor.Result r = ed.toggleLock();
		refreshActiveMarker(); // no geometry change: no need to re-sync the scene
		syncActiveNode();
		if (r.message() != null) viewer.showViewerMessage(r.message());
	}

	private void doRadius() {
		report(ed.promptAndSetRadius(viewer.getGuiUtils()));
	}

	private void doTag(final Component anchor, final int x, final int y) {
		if (ed.getActiveNode() < 0) {
			viewer.showViewerMessage("No node selected");
			return;
		}
		final int node = ed.getActiveNode(); // snapshot: hovering other nodes while choosing must not retarget the tag
		final Path nodePath = ed.getPath();
		final JPopupMenu tagMenu = GuiUtils.MenuItems.colorTagPopup(anchor,
				chosen -> report(ed.tagNode(nodePath, node, chosen)));
		tagMenu.setLightWeightPopupEnabled(false);
		tagMenu.addPopupMenuListener(popupStateListener);
		tagMenu.show(anchor, x, y);
	}

	private void doTagAtCursor() {
		final Component c = viewer.getViewerCanvas();
		if (c == null) return;
		Point p = c.getMousePosition();
		if (p == null) p = new Point(c.getWidth() / 2, c.getHeight() / 2);
		doTag(c, p.x, p.y);
	}

	/** Connects the active node to the active node of the secondary path (the one edited previously) */
	private void connect() {
		if (editingPathDeselected()) return;
		report(ed.connectToSecondaryPath(viewer.getGuiUtils(), true));
	}

	private void doSetRoot() {
		report(ed.setActiveNodeAsRoot(viewer.getGuiUtils()));
	}

	private void doSplit() {
		report(ed.splitTreeAtActiveNode(viewer.getGuiUtils()));
	}

	/** @return whether there are edits to undo (always false if Edit Mode is off) */
	boolean canUndo() {
		return active && ed.canUndo();
	}

	/** Undoes the last edit */
	void undo() {
		report(ed.undo());
	}

	// key bindings

	private InputMap buildInputMap() {
		final InputMap im = NodeEditCommand.inputMap();
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "snt-edit-connect");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "snt-edit-exit");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.SHIFT_DOWN_MASK), "snt-edit-exit");
		return im;
	}

	private ActionMap buildActionMap() {
		final ActionMap am = NodeEditCommand.actionMap(
				cmd -> run(cmd, viewer::resolveCursorWorldPosition, this::doTagAtCursor));
		am.put("snt-edit-connect", action("Connect", this::connect));
		am.put("snt-edit-exit", action("Exit", () -> exit(null)));
		return am;
	}

	/**
	 * Executes a command. The cursor position is only resolved by the commands that need it
	 *
	 * @param cmd the command
	 * @param pos supplies the cursor position (calibrated coordinates)
	 * @param tag opens the tag chooser at the appropriate location
	 */
	private void run(final NodeEditCommand cmd, final Supplier<double[]> pos, final Runnable tag) {
		if (editingPathDeselected()) return;
		switch (cmd) {
			case DELETE -> doDelete();
			case INSERT -> doInsert(pos.get());
			case MOVE -> doMove(pos.get());
			case LOCK -> doLock();
			case RADIUS -> doRadius();
			case TAG -> tag.run();
			case SET_ROOT -> doSetRoot();
			case SPLIT -> doSplit();
			case UNDO -> undo();
		}
	}

	/**
	 * Whether the editing path is not selected (e.g., deselected in Path Manager): its node markers are hidden (see
	 * {@link #refreshNodes()}), so node commands are no-ops rather than acting on a path that is not shown as edited.
	 * Edit Mode is kept so that the path can be re-selected, or another one grabbed (e.g., when connecting paths)
	 */
	private boolean editingPathDeselected() {
		final Path p = ed.getPath();
		if (p == null || p.isSelected()) return false;
		viewer.showViewerMessage("The path being edited is not selected");
		return true;
	}

	private static AbstractAction action(final String name, final Runnable r) {
		return new AbstractAction(name) {
			@Override
			public void actionPerformed(final java.awt.event.ActionEvent e) {
				r.run();
			}
		};
	}

	// context menu

	/** The 'Connect' entry: enabled if the editing and secondary paths each have an active node */
	private JMenuItem connectMenuItem() {
		final Path sec = ed.getSecondaryPath();
		final boolean enabled = active && ed.getPath() != null && ed.getActiveNode() >= 0 && ed.hasSecondaryNode();
		final String label = (active && sec != null && sec.getEditableNodeIndex() != -1)
				? "Connect to " + NodeEditCommand.shortName(sec) + " (node " + sec.getEditableNodeIndex() + ")"
				: "Connect to Secondary Editing Path...";
		final JMenuItem item = new JMenuItem(NodeEditCommand.MENU_INDENT + label);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0));
		item.setEnabled(enabled);
		item.setToolTipText("<html>To connect two paths: Grab the parent path (G), hover over the node to branch from,<br>"
				+ "grab the child path (G), hover over the node to connect, and press C");
		item.addActionListener(ev -> connect());
		return item;
	}

	/**
	 * Appends the Edit Mode section to the viewer's context menu (see {@link BigViewerMenu})
	 *
	 * @param menu     the menu
	 * @param e        the event that opened the menu
	 * @param clickPos the cursor position (calibrated coordinates) captured when the menu was opened, or null
	 */
	void addMenuItems(final JPopupMenu menu, final MouseEvent e, final double[] clickPos) {
		if (active) {
			// Snapshot target node and cursor position at the time of the click: neither may change while the menu is
			// open (hovering over other nodes en route to a menu item must not retarget the command)
			final int picked = pick(e.getX(), e.getY());
			if (picked >= 0) ed.setActiveNode(picked); // no-op if locked. Otherwise keeps the previous active node
			refreshActiveMarker();
		}
		final boolean hasNode = active && ed.getActiveNode() >= 0;
		final Component source = (Component) e.getSource();
		final Path single = snt.getSingleSelectedPath();
		final JCheckBoxMenuItem toggle = new JCheckBoxMenuItem(NodeEditCommand.editToggleLabel((single == null) ? null : NodeEditCommand.shortName(single)), active);
		IconFactory.assignIcon(toggle, IconFactory.GLYPH.PEN);
		toggle.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.SHIFT_DOWN_MASK));
		final String blocker = (active) ? null : entryBlocker();
		toggle.setEnabled(blocker == null);
		if (blocker != null) toggle.setToolTipText(blocker);
		toggle.addActionListener(ev -> toggle());
		menu.add(toggle);
		NodeEditCommand.addMenuItems(menu,
				new NodeEditCommand.MenuState(hasNode, active && ed.isActiveNodeLocked(), active && ed.canUndo()),
				cmd -> run(cmd, () -> clickPos, () -> doTag(source, e.getX(), e.getY())));
		// Canvas-only commands
		menu.add(BigViewerMenu.cropOnlyItem(NodeEditCommand.MENU_INDENT + "Bring Active Node to Current Z-plane", KeyEvent.VK_B));
		menu.add(connectMenuItem());
		menu.add(GuiUtils.MenuItems.connectHelp(viewer::getGuiUtils, null, null));
		menu.add(BigViewerMenu.cropOnlyItem(NodeEditCommand.MENU_INDENT + "Paint Mode", KeyEvent.VK_P));
		menu.addPopupMenuListener(popupStateListener);
		popup = menu;
	}
}
