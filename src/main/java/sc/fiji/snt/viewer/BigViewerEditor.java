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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.PathNodeEditor;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.NodeEditCommand;
import sc.fiji.snt.util.PointInImage;
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

	/** Annotation layer holding the node 'Quick Connect' would connect to (see {@link #beginConnect(Point)}) */
	private static final class TargetLayer {}

	private static final Color TARGET_COLOR = new Color(0, 200, 120, 230);
	private static final Color BLOCKED_COLOR = SNTColor.alphaColor(GuiUtils.Colors.errorColor(), 90);
	private static final Color RUBBER_BAND_COLOR = new Color(0, 200, 120, 140);
	private static final int RUBBER_BAND_DOTS = 12;
	/** Distance (pixels) by which the current target is favored over nodes that are about as close to the cursor */
	private static final double TARGET_STICKY_PX = 6;
	/** Distance (pixels) within which the cursor is considered to be 'over' a path that cannot be connected to */
	private static final double BLOCKED_RADIUS_PX = 20;
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

	// 'Quick Connect' state: the editing path is the child and its active node stays fixed, while the
	// cursor snaps to the closest node of any other rendered path (the target)
	private boolean connecting;
	private Path connectingPath;
	private Set<Path> connectExcluded;
	private Path targetPath;
	private int targetIdx = -1;
	private Bvv.OverlayRenderer.NodeHit targetHit; // as displayed (may be the fitted copy of targetPath)
	private String lastHint; // last hint shown while connecting, so that it is not repeated on every mouse move
	private boolean cursorBlocked; // whether the 'forbidden' cursor is set (see setBlockedCursor)
	private java.awt.Cursor savedCursor; // the cursor of the canvas before it was set to 'forbidden' (null if none)
	private int lastHoverX = Integer.MIN_VALUE;
	private int lastHoverY = Integer.MIN_VALUE;

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

		@Override
		public void mouseClicked(final MouseEvent e) {
			if (connecting && e.getButton() == MouseEvent.BUTTON1 && !popupShowing) {
				updateTarget(e.getX(), e.getY(), true);
				commitConnect();
			}
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
		final String blocker = snt.getEditModeBlocker(true); // path nearest to the cursor is selected if needed
		if (blocker != null) return blocker;
		if (viewer.annotations() == null || viewer.getViewerCanvas() == null) return "Edit Mode unavailable: no annotation layer";
		if (viewer.isPathInProgress()) return "Edit Mode: Finish or discard the current path first";
		return null;
	}

	/**
	 * Checks viewer-specific prerequisites, and asks SNT to enable Edit Mode (see {@link #onSntEditModeChanged}).
	 * As in the tracing canvas, the path nearest to the cursor is selected if there is no single selected path
	 *
	 * @param pos the cursor position (calibrated coordinates), or null to resolve it
	 */
	private void requestEnter(final double[] pos) {
		final String blocker = entryBlocker();
		if (blocker != null) {
			viewer.getGuiUtils().error(blocker);
			return;
		}
		Path toEdit = snt.getSingleSelectedPath();
		if (toEdit == null) {
			if (snt.getPathAndFillManager().size() == 0) {
				viewer.showViewerMessage("Nothing to select: There are no traced paths");
				return;
			}
			final double[] p = (pos != null) ? pos : viewer.resolveCursorWorldPosition();
			final AbstractBigViewer.AbstractTracer tracer = viewer.menuTracer();
			final Path nearest = (p == null || tracer == null) ? null : tracer.findNearestRenderedPath(p);
			if (nearest == null) {
				viewer.showViewerMessage((p == null) ? "Could not resolve cursor position" : "No rendered paths near cursor");
				return;
			}
			toEdit = ed.resolvePath(nearest); // the topology is held by the un-fitted original
		}
		snt.enterEditMode(toEdit);
		// On success, SNT calls onSntEditModeChanged(true)
	}

	/** Called (via the viewer) when SNT enables/disables Edit Mode, regardless of the front-end that triggered it */
	void onSntEditModeChanged(final boolean enabled) {
		if (enabled) activate();
		else deactivate();
	}

	void toggle() {
		toggle(null);
	}

	/** @param pos the cursor position (calibrated coordinates) used to select the path to edit, or null to resolve it */
	void toggle(final double[] pos) {
		if (snt.isEditModeEnabled()) exit(null);
		else requestEnter(pos);
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
		canvas.addMouseListener(hoverHandler);
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
		endConnect(notify);
		final Component canvas = viewer.getViewerCanvas();
		if (canvas != null) {
			canvas.removeMouseMotionListener(hoverHandler);
			canvas.removeMouseListener(hoverHandler);
		}
		if (popup != null) popup.setVisible(false);
		if (!notify) return;
		viewer.removeEditKeybindings();
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann != null) {
			ann.clear(NodesLayer.class);
			ann.clear(ActiveLayer.class);
			ann.clear(SecondaryLayer.class);
			ann.clear(TargetLayer.class);
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
		if (connecting) { // active node is fixed
			if (!popupShowing) updateTarget(e.getX(), e.getY(), false);
			return;
		}
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
			ann.clear(TargetLayer.class);
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

	// quick connect

	/**
	 * Enters 'Quick Connect': the cursor snaps to the closest node of any other rendered path, which is
	 * highlighted. A click (or the shortcut again) connects the active node of the editing path to it, and the
	 * editing path becomes the child (no confirmation). The active node is fixed while snapping
	 */
	private void beginConnect(final Point screenPos) {
		if (editingPathDeselected()) return;
		selectNearestNode(screenPos);
		if (ed.getActiveNode() < 0) {
			viewer.showViewerMessage("No node to connect from");
			return;
		}
		connectingPath = ed.getPath();
		connectExcluded = Collections.newSetFromMap(new IdentityHashMap<>());
		collectSubtree(connectingPath, connectExcluded); // connecting to a descendant would create a loop
		connecting = true;
		lastHint = null;
		clearTarget();
		dimUnconnectable();
		viewer.showViewerMessage("Quick Connect: Click a node to connect to (Esc to cancel)");
		final Component c = viewer.getViewerCanvas();
		final Point mouse = (c == null) ? null : c.getMousePosition();
		if (mouse != null) updateTarget(mouse.x, mouse.y, true);
	}

	/**
	 * Makes the node of the editing path that is nearest to a screen position the active node (unless it is locked)
	 *
	 * @param screenPos the position in viewer-display pixels, or null to keep the active node
	 */
	private void selectNearestNode(final Point screenPos) {
		final Bvv.PathOverlay po = viewer.pathOverlay;
		final Path editing = ed.getPath();
		if (screenPos == null || po == null || editing == null || ed.isActiveNodeLocked()) return;
		final Bvv.OverlayRenderer.NodeHit hit = po.overlayRenderer.nearestNode(screenPos.x, screenPos.y,
				Double.MAX_VALUE, p -> ed.resolvePath(p) == editing, null, 0);
		if (hit == null) return;
		final int idx = ed.resolveNode(hit.path(), hit.index()).index();
		if (idx == ed.getActiveNode()) return;
		ed.setActiveNode(idx);
		refreshActiveMarker();
		syncActiveNode();
	}

	private static void collectSubtree(final Path root, final Set<Path> into) {
		into.add(root);
		for (final Path child : root.getChildren()) collectSubtree(child, into);
	}

	/** Leaves 'Quick Connect' without connecting. No-op if not connecting */
	private void endConnect() {
		endConnect(true);
	}

	/** @param notify false if the viewer is being disposed */
	private void endConnect(final boolean notify) {
		if (!connecting) return;
		connecting = false;
		connectingPath = null;
		connectExcluded = null;
		clearTarget();
		releaseConnectVisuals();
		if (notify) viewer.showViewerMessage("Quick Connect canceled");
	}

	/** Draws the branches of the editing path (which cannot be connected to: it would create a loop) dimmed */
	private void dimUnconnectable() {
		final Bvv.PathOverlay po = viewer.pathOverlay;
		if (po == null) return;
		final List<Path> dimmed = new ArrayList<>();
		for (final Path p : connectExcluded) {
			if (p == connectingPath) continue; // the path being edited keeps its appearance
			dimmed.add(p);
			if (p.getFitted() != null) dimmed.add(p.getFitted()); // the version displayed may be the fitted copy
		}
		if (!dimmed.isEmpty()) po.overlayRenderer.setDimmedPaths(dimmed);
	}

	/**
	 * Shows the 'forbidden' cursor while the cursor is over a path that cannot be connected to, and restores the
	 * previous one otherwise
	 */
	private void setBlockedCursor(final boolean blocked) {
		if (blocked == cursorBlocked) return;
		final Component c = viewer.getViewerCanvas();
		if (c == null) return;
		if (blocked) {
			savedCursor = c.isCursorSet() ? c.getCursor() : null;
			c.setCursor(java.awt.dnd.DragSource.DefaultMoveNoDrop);
		} else {
			c.setCursor(savedCursor); // null: inherited from the parent
			savedCursor = null;
		}
		cursorBlocked = blocked;
	}

	/** Restores the regular appearance of paths and the cursor */
	private void releaseConnectVisuals() {
		final Bvv.PathOverlay po = viewer.pathOverlay;
		if (po != null) po.overlayRenderer.setDimmedPaths(null);
		setBlockedCursor(false);
	}

	/** Displays a hint, unless it is the one displayed last */
	private void showHint(final String msg) {
		if (msg.equals(lastHint)) return;
		lastHint = msg;
		viewer.showViewerMessage(msg);
	}

	private void clearTarget() {
		targetPath = null;
		targetIdx = -1;
		targetHit = null;
		lastHoverX = lastHoverY = Integer.MIN_VALUE;
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann != null) ann.clear(TargetLayer.class);
	}

	/**
	 * Snaps to the closest node (screen space) of the rendered paths
	 *
	 * @param force if false, tiny cursor movements are ignored (this runs on every mouse move)
	 */
	private void updateTarget(final int x, final int y, final boolean force) {
		if (ed.getPath() != connectingPath) { // another path was grabbed meanwhile
			endConnect();
			return;
		}
		if (!force && lastHoverX != Integer.MIN_VALUE
				&& Math.abs(x - lastHoverX) + Math.abs(y - lastHoverY) < 2) return;
		lastHoverX = x;
		lastHoverY = y;
		final Bvv.PathOverlay po = viewer.pathOverlay;
		final Bvv.OverlayRenderer.NodeHit hit = (po == null) ? null
				: po.overlayRenderer.nearestNode(x, y, SNTPrefs.getBigViewerPickRadius(),
						p -> !connectExcluded.contains(ed.resolvePath(p)), // p may be a fitted copy
						targetHit, TARGET_STICKY_PX);
		// Cursor over a path that cannot be connected to: nothing to snap to (rather than snapping to a more distant
		// valid node, which would not be what the cursor is pointing at)
		final Bvv.OverlayRenderer.NodeHit blocker = (po == null) ? null
				: po.overlayRenderer.nearestNode(x, y, BLOCKED_RADIUS_PX,
						p -> connectExcluded.contains(ed.resolvePath(p)), null, 0);
		if (blocker != null && (hit == null || blocker.distPx() <= hit.distPx())) {
			clearTarget();
			lastHoverX = x;
			lastHoverY = y;
			setBlockedCursor(true);
			final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
			if (ann != null) { // flag the node the cursor is over
				ann.replaceAll(TargetLayer.class, List.of(blocker.path().getNode(blocker.index())),
						List.of(viewer.getDefaultMarkerSize() * ACTIVE_SCALE), List.of(BLOCKED_COLOR));
			}
			showHint((ed.resolvePath(blocker.path()) == connectingPath)
					? "Cannot connect a path to itself"
					: "Cannot connect to a branch of the path being edited (loop)");
			return;
		}
		if (hit == null) {
			if (targetPath != null) {
				clearTarget();
				lastHoverX = x;
				lastHoverY = y;
			}
			setBlockedCursor(false);
			showHint(String.format("No node within %.0f pixels of the cursor", SNTPrefs.getBigViewerPickRadius()));
			return;
		}
		lastHint = null;
		setBlockedCursor(false);
		// The topology lives in the un-fitted original, but the node shown is the displayed one
		final PathAndFillManager.NodeRef ref = ed.resolveNode(hit.path(), hit.index());
		if (ref.path() == targetPath && ref.index() == targetIdx) return;
		targetPath = ref.path();
		targetIdx = ref.index();
		targetHit = hit;
		final Path.PathNode targetNode = hit.path().getNode(hit.index());
		final int activeIdx = ed.getActiveNode();
		final Path shown = connectingPath.getVersionInUse(); // the child as displayed
		final Path.PathNode source = ((activeIdx < shown.size()) ? shown : connectingPath).getNode(activeIdx);
		drawTarget(source, targetNode);
		final boolean slab = activeIdx > 0 && activeIdx < connectingPath.size() - 1 && connectingPath.size() > 2;
		viewer.showViewerMessage(String.format("Connect to %s (node %d), gap %.2f%s%s",
				NodeEditCommand.shortName(targetPath), targetIdx, source.distanceTo(targetNode),
				(viewer.calUnit == null) ? "" : " " + viewer.calUnit,
				(slab) ? ", splitting at node " + activeIdx : ""));
	}

	/** Highlights the target and draws a dotted line to it from the active node of the editing path */
	private void drawTarget(final SNTPoint source, final SNTPoint target) {
		final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
		if (ann == null) return;
		final List<SNTPoint> points = new ArrayList<>(RUBBER_BAND_DOTS + 1);
		final List<Float> sizes = new ArrayList<>(RUBBER_BAND_DOTS + 1);
		final List<Color> colors = new ArrayList<>(RUBBER_BAND_DOTS + 1);
		final float dotSize = viewer.getDefaultMarkerSize() * NODE_SCALE * 0.5f;
		for (int k = 1; k <= RUBBER_BAND_DOTS; k++) {
			final double t = k / (RUBBER_BAND_DOTS + 1d);
			points.add(new PointInImage(source.getX() + t * (target.getX() - source.getX()),
					source.getY() + t * (target.getY() - source.getY()),
					source.getZ() + t * (target.getZ() - source.getZ())));
			sizes.add(dotSize);
			colors.add(RUBBER_BAND_COLOR);
		}
		points.add(target); // last: drawn on top of the dots
		sizes.add(viewer.getDefaultMarkerSize() * ACTIVE_SCALE);
		colors.add(TARGET_COLOR);
		ann.replaceAll(TargetLayer.class, points, sizes, colors);
	}

	/** Connects to the current target, or reports that there is none (staying in 'Quick Connect') */
	private void commitConnect() {
		if (targetPath == null) {
			viewer.getGuiUtils().error(String.format("<html>No connectable node within %.0f pixels of the cursor."
					+ "<br>Move closer to the path you want to connect to.</html>",
					SNTPrefs.getBigViewerPickRadius()),
					"No Connectable Node");
			return;
		}
		final Path parent = targetPath;
		final int idx = targetIdx;
		connecting = false; // connected (or failed): do not announce a cancellation
		connectingPath = null;
		connectExcluded = null;
		clearTarget();
		releaseConnectVisuals();
		report(ed.connectToNode(parent, idx, viewer.getGuiUtils()));
	}

	/** Shortcut/menu action: starts 'Quick Connect' or, if active, connects to the node under the cursor */
	private void toggleQuickConnect() {
		final Component c = viewer.getViewerCanvas();
		final Point mouse = (c == null) ? null : c.getMousePosition();
		if (!connecting) {
			beginConnect(mouse);
			return;
		}
		if (mouse != null) updateTarget(mouse.x, mouse.y, true);
		commitConnect();
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
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK), "snt-edit-connect-quick");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "snt-edit-connect-commit");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "snt-edit-exit");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.SHIFT_DOWN_MASK), "snt-edit-exit");
		return im;
	}

	private ActionMap buildActionMap() {
		final ActionMap am = NodeEditCommand.actionMap(
				cmd -> run(cmd, viewer::resolveCursorWorldPosition, this::doTagAtCursor));
		am.put("snt-edit-connect", action("Connect", this::connect));
		am.put("snt-edit-connect-quick", action("Quick Connect", this::toggleQuickConnect));
		am.put("snt-edit-connect-commit", action("Quick Connect", () -> {
			if (connecting) toggleQuickConnect(); // while connecting, this commits
		}));
		am.put("snt-edit-exit", action("Exit", () -> {
			if (connecting) endConnect();
			else exit(null);
		}));
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

	/** The 'Quick Connect' entry: enabled in Edit Mode, unless already connecting */
	private JMenuItem quickConnectMenuItem(final Point screenPos) {
		final JMenuItem item = new JMenuItem(NodeEditCommand.MENU_INDENT + "Quick Connect...");
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK));
		item.setEnabled(active && ed.getPath() != null && !connecting);
		item.setToolTipText("<html>Connects the node of the editing path nearest to the cursor to another path:<br>"
				+ "move the cursor over the node to connect to and click (or press Enter). Esc cancels");
		item.addActionListener(ev -> beginConnect(screenPos));
		return item;
	}

	/** The 'Connect' entry: enabled in Edit Mode if the secondary path has an active node (as in the tracing canvas) */
	private JMenuItem connectMenuItem() {
		final Path sec = ed.getSecondaryPath();
		final boolean enabled = active && ed.getPath() != null && ed.hasSecondaryNode(); // as in InteractiveTracerCanvas
		final String label = (active && sec != null && sec.getEditableNodeIndex() != -1)
				? "Connect To " + NodeEditCommand.shortName(sec) + " (Node " + sec.getEditableNodeIndex() + ")"
				: "Connect To...";
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
	 * @param pickNode whether the node at the event's position becomes the active node (false for menus that are
	 *                 not opened by a click, so that the active node is not retargeted)
	 */
	void addMenuItems(final JPopupMenu menu, final MouseEvent e, final double[] clickPos, final boolean pickNode) {
		if (active && pickNode) {
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
		toggle.addActionListener(ev -> toggle(clickPos));
		menu.add(toggle);
		NodeEditCommand.addMenuItems(menu,
				new NodeEditCommand.MenuState(hasNode, active && ed.isActiveNodeLocked(), active && ed.canUndo()),
				cmd -> run(cmd, () -> clickPos, () -> doTag(source, e.getX(), e.getY())));
		// Canvas-only commands
		menu.add(BigViewerMenu.cropOnlyItem(NodeEditCommand.MENU_INDENT + "Bring Active Node to Current Z-plane", KeyEvent.VK_B));
		menu.add(connectMenuItem());
		menu.add(quickConnectMenuItem(new Point(e.getX(), e.getY())));
		final JMenuItem helpItem = GuiUtils.MenuItems.connectHelp(viewer::getGuiUtils, null, null);
		helpItem.setEnabled(active); // as in the tracing canvas
		menu.add(helpItem);
		menu.add(BigViewerMenu.cropOnlyItem(NodeEditCommand.MENU_INDENT + "Paint Mode", KeyEvent.VK_P));
		menu.addPopupMenuListener(popupStateListener);
		popup = menu;
	}
}
