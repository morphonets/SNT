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

package sc.fiji.snt;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Toolbar;
import ij.measure.Calibration;

import org.apache.commons.lang3.StringUtils;
import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.tracing.artist.FillerThreadArtist;
import sc.fiji.snt.tracing.artist.SearchArtist;
import sc.fiji.snt.util.*;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.List;

/**
 * A canvas that allows interactive tracing of paths.
 */
class InteractiveTracerCanvas extends TracerCanvas implements MouseWheelListener {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private static final long serialVersionUID = 1L;
    private final SNT tracerPlugin;
    private JPopupMenu pMenu;
    private JCheckBoxMenuItem toggleEditModeMenuItem;
    private JMenuItem extendPathMenuItem;
    private JMenuItem finishPathMenuItem;
    private JMenuItem undoSegmentMenuItem;
    private JCheckBoxMenuItem togglePauseTracingMenuItem;
    private JCheckBoxMenuItem togglePauseSNTMenuItem;
    private JMenuItem connectToSecondaryEditingPath;
    private double last_x_in_pane_precise = Double.MIN_VALUE;
    private double last_y_in_pane_precise = Double.MIN_VALUE;
    private boolean fillTransparent = false;
    private Path unconfirmedSegment;
    private Path currentPath;
    private boolean lastPathUnfinished;
    private boolean editMode; // convenience flag to monitor SNT's edit mode

    private Color temporaryColor;
    private Color unconfirmedColor;
    private Color fillColor;
    private GuiUtils guiUtils;
    protected static String EDIT_MODE_LABEL = "Edit Mode";
    protected static String SNT_PAUSED_LABEL = "SNT Paused";
    protected static String TRACING_PAUSED_LABEL = "Tracing Paused";

    // Preview path for connection visualization
    private Path connectionPreview;

    /* fixes for 'failed' clicks, see https://forum.image.sc/t/snt-mouse-clicks-often-fail-when-tracing/115544 */
    private int pressedX, pressedY;
    private boolean clickHandledByRelease;
    private boolean popupTriggered;
    private boolean altDraggingNode;
    private boolean autoEnteredEditMode;
    private final Deque<Path> editUndoStack = new ArrayDeque<>();

    // Squared pixel tolerance for click detection (5 pixels). This accommodates
    // slight mouse/trackpad movement during click, especially on macOS trackpads.
    private static final int CLICK_TOLERANCE_SQ = 25;

    protected InteractiveTracerCanvas(final ImagePlus imp,
                                      final SNT plugin, final int plane,
                                      final PathAndFillManager pathAndFillManager)
    {
        super(imp, plugin, plane, pathAndFillManager);
        tracerPlugin = plugin;
        buildPopupMenu();
        super.disablePopupMenu(true); // so that handlePopupMenu is not triggered
        addMouseWheelListener(this); // explicit registration. ImageCanvas does not implement WheelListener
    }

    private void updateForkPointMenuItem(final JMenuItem forkNearestMenuItem) {
        // NB: mouse button gestures cannot be set as Swing accelerators (KeyStroke only supports keyboard keys),
        // so the shortcut is documented in the label only
        final String accelerator = (tracerPlugin.requireShiftToFork) ? " (or Alt+Shift+Click)" : " (or Alt+Click)";
        forkNearestMenuItem.setText(AListener.FORK_NEAREST + accelerator);
    }

    private void buildPopupMenu() {
        pMenu = new JPopupMenu();
        // Required because we are mixing lightweight and heavyweight components?
        pMenu.setLightWeightPopupEnabled(false);

        final AListener listener = new AListener();
        pMenu.add(menuItem(AListener.SELECT_NEAREST, listener, KeyStroke.getKeyStroke(KeyEvent.VK_G, 0),
                '\uf076', true, IconFactory.defaultColor()));
        pMenu.add(menuItem(AListener.APPEND_NEAREST, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.SHIFT_DOWN_MASK),
                '\uf076', true, IconFactory.selectedColor()));
        final JMenuItem selectByRoi = getSelectRoiMenuItem();
        pMenu.add(selectByRoi);
        pMenu.addSeparator();
        pMenu.add(menuItem(AListener.HIDE_ALL, listener, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0),
                IconFactory.GLYPH.EYE));
        pMenu.add(menuItem(AListener.SHOW_ARROWS, listener, KeyStroke.getKeyStroke(KeyEvent.VK_O, 0),
                IconFactory.GLYPH.NAVIGATE));
        pMenu.addSeparator();

        pMenu.add(menuItem(AListener.BOOKMARK_CURSOR, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.SHIFT_DOWN_MASK),
                '\uf02e', false, IconFactory.defaultColor()));
        pMenu.add(menuItem(AListener.START_SHOLL, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.SHIFT_DOWN_MASK + KeyEvent.ALT_DOWN_MASK),
                IconFactory.GLYPH.BULLSEYE));
        pMenu.addSeparator();

        JMenuItem mi = menuItem(AListener.CLICK_AT_MAX, listener, KeyEvent.VK_V, IconFactory.GLYPH.SUN);
        pMenu.add(mi);
        extendPathMenuItem = menuItem(AListener.EXTEND_SELECTED, listener, null, IconFactory.GLYPH.TAPE);
        pMenu.add(extendPathMenuItem);
        mi = menuItem(AListener.FORK_NEAREST, listener, null,
                '\ue13b', true, IconFactory.defaultColor());
        mi.setToolTipText("Branches off a child path at nearest node"); // alternative discovery text for command palette
        pMenu.add(mi);

        pMenu.addSeparator();
        undoSegmentMenuItem = menuItem(AListener.UNDO_LAST_SEGMENT, listener, KeyEvent.VK_Z, IconFactory.GLYPH.UNDO);
        pMenu.add(undoSegmentMenuItem);
        finishPathMenuItem = menuItem(AListener.FINISH_PATH, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_F, 0),
                '\uf11e', true, IconFactory.defaultColor());
        pMenu.add(finishPathMenuItem);

        pMenu.addSeparator();
        toggleEditModeMenuItem = new JCheckBoxMenuItem(AListener.EDIT_TOGGLE_FORMATTER,
                IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        toggleEditModeMenuItem.addItemListener(listener);
        toggleEditModeMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift E"));
        toggleEditModeMenuItem.setMnemonic(KeyEvent.VK_E);
        pMenu.add(toggleEditModeMenuItem);

        pMenu.add(menuItem(AListener.NODE_MOVE_Z, listener, KeyEvent.VK_B, null));
        connectToSecondaryEditingPath = menuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER, listener,
                null, null);
        connectToSecondaryEditingPath.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0));
        connectToSecondaryEditingPath.setMnemonic(KeyEvent.VK_C);
        pMenu.add(connectToSecondaryEditingPath);
        pMenu.add(helpOnConnectingMenuItem());
        pMenu.add(menuItem(AListener.NODE_DELETE, listener, KeyEvent.VK_D, null));
        pMenu.add(menuItem(AListener.NODE_INSERT, listener, KeyEvent.VK_I, null));
        pMenu.add(menuItem(AListener.NODE_LOCK, listener, KeyEvent.VK_L, null));
        pMenu.add(menuItem(AListener.NODE_MOVE, listener, KeyEvent.VK_M, null));
        final JMenuItem jmi = menuItem(AListener.NODE_COLOR, listener, KeyStroke.getKeyStroke(KeyEvent.VK_T, 0), null);
        jmi.setToolTipText("<HTML>Assigns a unique color to active node.<br>"
                + "NB: Overall rendering of path may change once tag is applied");
        pMenu.add(jmi);

        // Structural operations (require confirmation, these are not undoable)
        pMenu.add(menuItem(AListener.NODE_RADIUS, listener, KeyEvent.VK_R, null));
        pMenu.add(menuItem(AListener.NODE_SET_ROOT, listener, null, null));
        pMenu.add(menuItem(AListener.NODE_SPLIT, listener, KeyEvent.VK_X, null));

        // Pause tracing/SNT
        pMenu.addSeparator();
        togglePauseTracingMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_TRACING_TOGGLE);
        IconFactory.assignIcon(togglePauseTracingMenuItem, '\uf04c', true, IconFactory.defaultColor());
        togglePauseTracingMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift P"));
        togglePauseTracingMenuItem.setMnemonic(KeyEvent.VK_P);
        togglePauseTracingMenuItem.addItemListener(listener);
        pMenu.add(togglePauseTracingMenuItem);
        togglePauseSNTMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_SNT_TOGGLE,
                IconFactory.menuIcon('\uf04d', true));
        togglePauseSNTMenuItem.addItemListener(listener);
        pMenu.add(togglePauseSNTMenuItem);
    }

    private JMenuItem getSelectRoiMenuItem() {
        final JMenuItem selectByRoi = new JMenuItem(AListener.SELECT_BY_ROI);
        IconFactory.assignIcon(selectByRoi, '\uf248', false, IconFactory.defaultColor());
        selectByRoi.addActionListener( e -> {
            if (pathAndFillManager.size() == 0) {
                getGuiUtils().error("There are no traced paths.", "Nothing to Select");
                return;
            }
            if (getImage().getRoi() != null && selectPathsByRoi()) {
                return; // a ROI existed, and we successfully used it to select paths
            } else {
                // User still has to create ROI
                waitingForRoiDrawing = true;
                if (unsuitableToolForRoiSelection()) IJ.setTool("freehand");
                getGuiUtils().tempMsg("Draw ROI around paths to be selected. Current tool: " + IJ.getToolName());
            }
        });
        return selectByRoi;
    }

    private void showPopupMenu(final int x, final int y) {
        final Path activePath = tracerPlugin.getSingleSelectedPath();
        final boolean be = uiReadyForModeChange(SNTUI.EDITING);
        final boolean bp = uiReadyForModeChange(SNTUI.SNT_PAUSED);
        final boolean tracingActive = !editMode && !tracerPlugin.tracingHalted;

        extendPathMenuItem.setText(
                (activePath != null) ? "Continue Extending " + getShortName(activePath) : AListener.EXTEND_SELECTED);
        toggleEditModeMenuItem.setText(String.format(AListener.EDIT_TOGGLE_FORMATTER,
                (activePath == null) ? " Mode" : getShortName(activePath)));
        toggleEditModeMenuItem.setState(be && editMode);
        togglePauseSNTMenuItem.setSelected(bp && tracerPlugin.getUIState() == SNTUI.SNT_PAUSED);
        togglePauseTracingMenuItem.setSelected(tracerPlugin.tracingHalted);

        for (final MenuElement me : pMenu.getSubElements()) {
            if (!(me instanceof JMenuItem mItem)) continue;
            final String cmd = mItem.getActionCommand();

            if (cmd.startsWith(AListener.FORK_NEAREST))
                updateForkPointMenuItem(mItem);

            // SNT fully paused: disable everything except the toggle itself
            if (togglePauseSNTMenuItem.isSelected() && !cmd.equals(AListener.PAUSE_SNT_TOGGLE)) {
                mItem.setEnabled(false);
            }
            // Edit mode toggle
            else if (mItem == toggleEditModeMenuItem) {
                mItem.setEnabled(be);
            }
            // Pause tracing toggle
            else if (mItem == togglePauseTracingMenuItem) {
                mItem.setEnabled(bp);
            }
            // Tracing-only commands: disabled in edit mode and when tracing halted
            else if (extendPathMenuItem == mItem) {
                mItem.setEnabled(tracingActive );
            }
            else if (cmd.equals(AListener.FINISH_PATH)) {
                mItem.setEnabled(tracingActive && tracerPlugin.currentPath != null);
            }
            else if (undoSegmentMenuItem == mItem) {
                mItem.setText((editMode) ? AListener.UNDO_LAST_EDIT : AListener.UNDO_LAST_SEGMENT);
                mItem.setEnabled(
                        tracingActive && !tracerPlugin.confirmedSegmentSizes.isEmpty() // condition 1: tracing mode
                                || editMode && !editUndoStack.isEmpty() // condition 2: edit mode
                );
            }
            else if (cmd.startsWith(AListener.FORK_NEAREST)) {
                mItem.setEnabled(!editMode && !tracerPlugin.tracingHalted
                        && tracerPlugin.getUIState() == SNTUI.WAITING_TO_START_PATH
                        && pathAndFillManager.size() > 0);
            }
            else if (cmd.equals(AListener.CLICK_AT_MAX)) {
                mItem.setEnabled(tracingActive && !tracerPlugin.is2D());
            }
            // Edit mode node commands
            else if (cmd.equals(AListener.APPEND_NEAREST) || cmd.equals(AListener.SELECT_BY_ROI)) {
                mItem.setEnabled(!editMode); // In edit mode, only one path selected at any given time
            }
            else if (cmd.equals(AListener.NODE_MOVE_Z)) {
                mItem.setEnabled(be && editMode && !tracerPlugin.is2D());
            }
            else if (cmd.equals(AListener.NODE_DELETE) || cmd.equals(AListener.NODE_INSERT)
                    || cmd.equals(AListener.NODE_LOCK) || cmd.equals(AListener.NODE_MOVE)
                    || cmd.equals(AListener.NODE_SET_ROOT) || cmd.equals(AListener.NODE_SPLIT)
                    || cmd.equals(AListener.NODE_RADIUS) || cmd.equals(AListener.NODE_COLOR)
                    || cmd.equals(AListener.NODE_CONNECT_HELP)) {
                mItem.setEnabled(be && editMode);
            }
            // Everything else always enabled (SELECT_NEAREST, APPEND_NEAREST,
            // HIDE_ALL, SHOW_ARROWS, BOOKMARK_CURSOR, START_SHOLL, pan, etc.)
            else {
                mItem.setEnabled(true);
            }
        }
        updateConnectToSecondaryEditingPathMenuItem();
        pMenu.show(this, x, y);
    }

    protected void connectEditingPathToPreviousEditingPath(final boolean showDialog) {
        // Validate preconditions
        if (!editMode || //
                tracerPlugin.getEditingPath() == null || //
                tracerPlugin.getEditingPath().getEditableNodeIndex() == -1 || //
                tracerPlugin.getPreviousEditingPath() == null || //
                tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() == -1) //
        {
            getGuiUtils().error(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + ": No connectable node exist.",
                    "No Connectable Nodes");
            return;
        }

        // Default assignment: current path = child, previous path = parent
        Path childPath = tracerPlugin.getEditingPath();
        Path parentPath = tracerPlugin.getPreviousEditingPath();

        // Check for loop creation
        if (wouldCreateLoop(childPath, parentPath)) {
            getGuiUtils().error("Cannot connect selected nodes: A loop would be created!",
                    "Reconstruction Cannot Contain Loops");
            return;
        }

        // Get connection points
        final int childNodeIdx = childPath.getEditableNodeIndex();
        final int parentNodeIdx = parentPath.getEditableNodeIndex();

        /// Create preview path from parent to child (showing direction of connection)
        // Use canvas (unscaled) coordinates to avoid offset issues between paths
        final PointInCanvas parentUnscaled = parentPath.getUnscaledNodes().get(parentNodeIdx);
        final PointInCanvas childUnscaled = childPath.getUnscaledNodes().get(childNodeIdx);

        connectionPreview = new Path(tracerPlugin.x_spacing, tracerPlugin.y_spacing,
                tracerPlugin.z_spacing, tracerPlugin.spacing_units);
        // Convert canvas coords back to world coords for the preview (with zero offset)
        connectionPreview.addNode(new PointInImage(
                parentUnscaled.x * tracerPlugin.x_spacing,
                parentUnscaled.y * tracerPlugin.y_spacing,
                parentUnscaled.z * tracerPlugin.z_spacing));
        connectionPreview.addNode(new PointInImage(
                childUnscaled.x * tracerPlugin.x_spacing,
                childUnscaled.y * tracerPlugin.y_spacing,
                childUnscaled.z * tracerPlugin.z_spacing));

        // Enable direction arrows and repaint to show preview
        final boolean previousArrowState = PathNodeCanvas.isShowDirectionArrows();
        PathNodeCanvas.setShowDirectionArrows(true);
        tracerPlugin.updateTracingViewers(false);

        try {
            // Auto-swap heuristics
            boolean shouldSwap = false;
            final int childMaxOrder = TreeUtils.getMaxOrder(childPath);
            final int parentMaxOrder = TreeUtils.getMaxOrder(parentPath);
            if (childMaxOrder != parentMaxOrder) {
                // Deeper tree (higher max order) is likely the main structure
                shouldSwap = childMaxOrder > parentMaxOrder;
            } else {
                // Same depth: larger path is likely the parent
                shouldSwap = childPath.size() > parentPath.size();
            }

            if (showDialog) {
                // Analyze connection for prompt
                final boolean childAtStart = childNodeIdx == 0;
                final boolean childAtEnd = childNodeIdx == childPath.size() - 1;
                final boolean childAtEndpoint = childAtStart || childAtEnd;

                // Build detailed prompt
                final StringBuilder msgBuilder = new StringBuilder("<HTML>");
                msgBuilder.append("<b>Connection Summary:</b><br>");
                msgBuilder.append("<b>Parent:</b> ").append(StringUtils.abbreviate(parentPath.getName(), 30));
                msgBuilder.append(" (branch at node #").append(parentNodeIdx).append(")<br>");
                msgBuilder.append("<b>Child:</b> ").append(StringUtils.abbreviate(childPath.getName(), 30));
                msgBuilder.append(" (connect from node #").append(childNodeIdx).append(")<br>");
                msgBuilder.append("<b>Note:</b><br>");

                if (!childAtEndpoint && childPath.size() > 2) {
                    msgBuilder.append("&nbsp;&nbsp;- Child connects from a mid-path node<br>");
                }
                msgBuilder.append("&nbsp;&nbsp;- This operation cannot be undone<br><br>");

                // Show dialog with checkbox for swapping
                final boolean[] result = getGuiUtils().getConfirmationAndOption(
                        msgBuilder.toString(),
                        "Connect Paths",
                        "Swap parent ↔ child",
                        shouldSwap,
                        new String[]{"Connect", "Cancel"});

                if (result == null || !result[0]) {
                    return; // User cancelled
                }

                if (result[1]) { // Swap if requested
                    final Path temp = childPath;
                    childPath = parentPath;
                    parentPath = temp;
                    if (wouldCreateLoop(childPath, parentPath)) {
                        getGuiUtils().error("Cannot connect with swapped parent ↔ child: A loop would be created!",
                                "Reconstruction Cannot Contain Loops");
                        return;
                    }
                }
            }
            connectionPreview = null; // Clear preview before executing connection to avoid duplicate rendering
            connectToEditingPath(childPath, parentPath);
            // Trigger UI refresh to show updated hierarchy
            if (tracerPlugin.getUI() != null)
                tracerPlugin.getUI().getPathManager().setPathList(null, null, false);
        } finally {
            // Always restore state and clear preview (in case of early return)
            connectionPreview = null;
            PathNodeCanvas.setShowDirectionArrows(previousArrowState);
            tracerPlugin.updateAllViewers();
        }
    }

    /**
     * Checks if connecting child to parent would create a loop.
     */
    private boolean wouldCreateLoop(final Path child, final Path parent) {
        if (child == parent) {
            return true;
        }
        if (child.getChildren().isEmpty()) {
            return false;
        }
        return TreeUtils.isInSubtree(parent, child);
    }

    private void updateConnectToSecondaryEditingPathMenuItem() {
        if (!editMode || //
                tracerPlugin.getEditingPath() == null || //
                tracerPlugin.getPreviousEditingPath() == null || //
                tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() == -1) //
        {
            connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER);
            connectToSecondaryEditingPath.setEnabled(false);
            return;
        }
        connectToSecondaryEditingPath.setEnabled(true);
        final String label = getShortName(tracerPlugin.getPreviousEditingPath()) + " (node "
                + tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() + ")";
        connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + label);
    }

    /**
     * Connects child path to parent path, creating physical connection segment
     * and proper T-junction topology when needed.
     * <p>
     * If the child's selected node is a slab (middle) node, the child path is split
     * at that point to create proper branching topology.
     *
     * @param child  The path to be connected as a child branch
     * @param parent The path where the child will branch from
     */
    private void connectToEditingPath(final Path child, final Path parent) {
        try {
            long start = System.currentTimeMillis();

            final int childNodeIdx = child.getEditableNodeIndex();
            final int parentNodeIdx = parent.getEditableNodeIndex();
            final PointInImage parentPoint = parent.getNode(parentNodeIdx);

            // Check if child's selected node is a slab (middle) node
            final boolean childAtStart = childNodeIdx == 0;
            final boolean childAtEnd = childNodeIdx == child.size() - 1;
            final boolean childAtSlab = !childAtStart && !childAtEnd && child.size() > 2;

            final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
            pathAndFillManager.enableUIupdates = false;

            if (childAtSlab) {
                // T-JUNCTION: Split child path and handle directly without graph transformation
                connectWithTJunction(child, parent, childNodeIdx, parentNodeIdx, parentPoint);
            } else {
                // ENDPOINT CONNECTION: Use direct path manipulation
                connectAtEndpoint(child, parent, childAtStart, parentNodeIdx, parentPoint);
            }

            tracerPlugin.setEditingPath(null);
            SNTUtils.log("Finished connection in " + (System.currentTimeMillis() - start) + "ms");
            pathAndFillManager.enableUIupdates = existingEnableUiUpdates;

        } catch (final IllegalArgumentException e) {
            getGuiUtils().error(
                    "<HTML>Cannot connect these paths:<br><br>" +
                            "<i>" + e.getMessage() + "</i>",
                    "Connection Failed");
        }
    }

    /**
     * Connects child path to parent at an endpoint (start or end of child).
     */
    private void connectAtEndpoint(final Path child, final Path parent,
                                   final boolean childAtStart,
                                   final int parentNodeIdx, final PointInImage parentPoint) {

        // Sync canvas offset before any operations
        TreeUtils.syncCanvasOffset(child, parent);

        // Check if we need to reverse the child to connect from its start
        Path childToConnect = child;
        if (!childAtStart) {
            // Child connects from end - reverse it so it connects from start
            childToConnect = child.reversed();

            // Transfer children with updated branch points
            for (final Path grandchild : new ArrayList<>(child.getChildren())) {
                final int oldBranchIdx = grandchild.getBranchPointIndex();
                final int newBranchIdx = child.size() - 1 - oldBranchIdx;
                grandchild.detachFromParent();
                grandchild.setBranchFrom(childToConnect, childToConnect.getNode(newBranchIdx));
            }

            // Remove original and add reversed
            pathAndFillManager.deletePath(child);
            pathAndFillManager.addPath(childToConnect, true, true);
        }

        // Check if we need a bridging node
        final double distance = childToConnect.firstNode().distanceTo(parentPoint);
        final double threshold = tracerPlugin.getMinimumSeparation() * 2;
        if (distance > threshold) {
            // Insert bridging node at start of child
            final PointInImage bridgePoint = new PointInImage(parentPoint.x, parentPoint.y, parentPoint.z);
            childToConnect.insertNode(0, bridgePoint);
        }

        // Connect child to parent
        childToConnect.setBranchFrom(parent, parent.getNode(parentNodeIdx));
        // Update tree ID to match parent's tree
        childToConnect.setIDs(childToConnect.getID(), parent.getTreeID());
        // Also update any grandchildren's tree IDs
        for (final Path gc : childToConnect.getChildren()) {
            gc.setIDs(gc.getID(), parent.getTreeID());
        }
    }

    /**
     * Connects child path to parent by splitting child at a mid-path node (T-junction).
     */
    private void connectWithTJunction(final Path child, final Path parent,
                                      final int childNodeIdx, final int parentNodeIdx,
                                      final PointInImage parentPoint) {

        // Sync canvas offset before any operations
        TreeUtils.syncCanvasOffset(child, parent);

        // Store children info before we modify anything
        final List<Path> grandchildren = new ArrayList<>(child.getChildren());
        final Map<Path, Integer> grandchildBranchIndices = new HashMap<>();
        for (final Path gc : grandchildren) {
            grandchildBranchIndices.put(gc, gc.getBranchPointIndex());
            gc.detachFromParent();
        }

        // Create segment 1: nodes [0, childNodeIdx]
        final Path segment1 = child.createPath();
        for (int i = 0; i <= childNodeIdx; i++) {
            segment1.addNode(child.getNode(i));
        }
        // Create segment 2: nodes [childNodeIdx, end] - includes junction node for continuity
        Path segment2 = null;
        if (childNodeIdx < child.size() - 1) {
            segment2 = child.createPath();
            for (int i = childNodeIdx; i < child.size(); i++) {
                segment2.addNode(child.getNode(i));
            }
        }

        // Check if we need a bridging node for the connection to parent
        final PointInImage junctionPoint = segment1.lastNode();
        final double distance = junctionPoint.distanceTo(parentPoint);
        final double threshold = tracerPlugin.getMinimumSeparation() * 2;
        if (distance > threshold) {
            // Add bridging node at the END of segment1 (after junction point)
            // This extends segment1 toward the parent
            final PointInImage bridgePoint = new PointInImage(parentPoint.x, parentPoint.y, parentPoint.z);
            segment1.addNode(bridgePoint);
        }

        // Delete original child path
        pathAndFillManager.deletePath(child);

        // Add segment1 and connect it to parent
        pathAndFillManager.addPath(segment1, true, true);
        segment1.setBranchFrom(parent, parent.getNode(parentNodeIdx));
        // Update tree ID to match parent's tree
        segment1.setIDs(segment1.getID(), parent.getTreeID());

        // Add segment2 and connect it to segment1 at the junction (before the bridge if present)
        if (segment2 != null) {
            pathAndFillManager.addPath(segment2, true, true);
            // segment2 branches from segment1 at the junction point
            // The junction is at index childNodeIdx in segment1 (which has nodes 0..childNodeIdx, possibly + bridge)
            segment2.setBranchFrom(segment1, segment1.getNode(childNodeIdx));
            segment2.setIDs(segment2.getID(), parent.getTreeID());
        }

        // Reassign grandchildren to appropriate segment
        final int parentTreeID = parent.getTreeID();
        for (final Path gc : grandchildren) {
            final int origBranchIdx = grandchildBranchIndices.get(gc);

            if (origBranchIdx <= childNodeIdx) {
                // Grandchild branches from segment1 portion
                // Index in segment1 is same as original
                gc.setBranchFrom(segment1, segment1.getNode(origBranchIdx));
            } else if (segment2 != null) {
                // Grandchild branches from segment2 portion
                // Index in segment2: origBranchIdx - childNodeIdx (since segment2 starts at childNodeIdx)
                final int newIdx = origBranchIdx - childNodeIdx;
                gc.setBranchFrom(segment2, segment2.getNode(newIdx));
            }
            // Update tree ID to match parent's tree
            gc.setIDs(gc.getID(), parentTreeID);
        }
    }

    private static String getShortName(final Path p) {
        return StringUtils.abbreviate(p.getName(), 30);
    }

    private JMenuItem helpOnConnectingMenuItem() {
        final String msg = "<HTML>To connect two paths in <i>Edit Mode</i>:<ol>" +
                "  <li>Press <tt>Shift+E</tt> to enter Edit Mode</li>" +
                "  <li>Select the <b>parent</b> path using <tt>G</tt> (<u>G</u>roup paths around cursor)</li>" +
                "  <li>Hover over the node where the child should branch from</li>" +
                "  <li>Press <tt>G</tt> to switch to the <b>child</b> path</li>" +
                "  <li>Select the child's connection node (typically a start or end node)</li>" +
                "  <li>Press <tt>C</tt> to <u>C</u>onnect</li>" +
                "</ol>Notes:<ul>" +
                "  <li>A confirmation dialog shows the parent/child assignment with option to swap roles</li>" +
                "  <li>Child paths are automatically re-oriented to maintain proper tree structure</li>" +
                "  <li>If connection points are distant, a bridging segment is created automatically</li>" +
                "  <li>If child's selected node is mid-path, a T-junction is created</li>" +
                "  <li>Loop-forming connections are not allowed</li>" +
                "  <li>To concatenate or combine paths end-to-end, use Path Manager's Edit menu</li>" +
                "</ul>";
        final JMenuItem helpItem = new JMenuItem(AListener.NODE_CONNECT_HELP);
        helpItem.addActionListener(e -> {
            final boolean canvasActivationState = tracerPlugin.autoCanvasActivation;
            tracerPlugin.enableAutoActivation(false); // this will not update the checkbox state in SNTUI, but
            // ensures the help dialog will maintain its frontmost state
            getGuiUtils().showHTMLDialog(msg, AListener.NODE_CONNECT_HELP, false)
                    .addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(final WindowEvent e) {
                            tracerPlugin.enableAutoActivation(canvasActivationState);
                        }
                    });
        });
        return helpItem;
    }


    private JMenuItem initMenuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke) {
        final JMenuItem mi = GuiUtils.MenuItems.itemWithoutAccelerator(cmdName);
        mi.setText(cmdName);
        mi.addActionListener(lstnr);
        if (keystroke != null) {
            mi.setAccelerator(keystroke);
            mi.setMnemonic(keystroke.getKeyCode());
        }
        return mi;
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final int keycode,
                               final IconFactory.GLYPH glyph) {
        return menuItem(cmdName, lstnr, KeyStroke.getKeyStroke(keycode, 0), glyph);
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke,
                               final IconFactory.GLYPH glyph) {
        final JMenuItem mi = initMenuItem(cmdName, lstnr, keystroke);
        if (glyph != null) IconFactory.assignIcon(mi, glyph);
        return mi;
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke,
                               final char symbol, final boolean solid, final Color color) {
        final JMenuItem mi = initMenuItem(cmdName, lstnr, keystroke);
        if (symbol != '\u0000') IconFactory.assignIcon(mi, symbol, solid, color);
        return mi;
    }

    public void setFillTransparent(final boolean transparent) {
        this.fillTransparent = transparent;
        if (transparent && fillColor != null) setFillColor(SNTColor.alphaColor(
                fillColor, 50));
    }

    public void setPathUnfinished(final boolean unfinished) {
        this.lastPathUnfinished = unfinished;
    }

    public void setTemporaryPath(final Path path) {
        this.unconfirmedSegment = path;
    }

    public void setCurrentPath(final Path path) {
        this.currentPath = path;
    }

    private boolean uiReadyForModeChange(final int mode) {
        if (!tracerPlugin.isUIready()) return false;
        return tracerPlugin.tracingHalted || tracerPlugin
                .getUIState() == SNTUI.WAITING_TO_START_PATH || tracerPlugin
                .getUIState() == mode;
    }

    protected void fakeMouseMoved(final boolean shift_pressed,
                                  final boolean join_modifier_pressed)
    {
        tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
                plane, shift_pressed, join_modifier_pressed);
    }

    private void triggerRubberBandSearch() {
        if (tracerPlugin.currentSearchThread != null
                || tracerPlugin.getUIState() != SNTUI.PARTIAL_PATH
                || !tracerPlugin.isAstarEnabled())
            return;
        tracerPlugin.startRubberBandSearch(last_x_in_pane_precise, last_y_in_pane_precise, plane);
    }

    protected void clickAtMaxPoint(final boolean join_modifier_pressed) {
        if (!tracerPlugin.accessToValidImageData()) {
            tempMsg("This option requires valid image data to be loaded.");
            return;
        }
        final int x = (int) Math.round(last_x_in_pane_precise);
        final int y = (int) Math.round(last_y_in_pane_precise);
        final int[] p = new int[3];
        tracerPlugin.findPointInStack(x, y, plane, p);
        tracerPlugin.clickAtMaxPoint(x, y, plane, join_modifier_pressed);
    }

    protected void startShollAnalysis() {
        PointInImage centerScaled = null;
        if (pathAndFillManager.anySelected()) {
            final double[] p = new double[3];
            tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                    last_y_in_pane_precise, plane, p);
            centerScaled = pathAndFillManager.nearestJoinPointOnSelectedPaths(p[0],
                    p[1], p[2]);
        }
        else {
            final NearPoint np = getNearPointToMousePointer();
            if (np != null) {
                centerScaled = np.getNode();
            }
        }
        if (centerScaled == null) {
            getGuiUtils().tempMsg("No selectable nodes in view");
            return;
        }
        tracerPlugin.startSholl(centerScaled);
    }

    /**
     * Selects the path nearest to the current mouse pointer position.
     *
     * @param addToExistingSelection if true, adds to current selection; if false, replaces it
     * @return the selected path, or null if no path could be selected
     */
    public Path selectNearestPathToMousePointer(final boolean addToExistingSelection) {
        if (pathAndFillManager.size() == 0) {
            getGuiUtils().tempMsg("Nothing to select: There are no traced paths");
            return null;
        }

        final List<PointInCanvas> nodes = new ArrayList<>();
        if (pathAndFillManager.size() == 1) {
            // There is only one path, presumably from a just-finished tracing operation.
            // NB: Selection status of such path depends on the "Finishing a path selects
            // it" checkbox in the GUI. We'll force select it.
            final Path onlyPath = pathAndFillManager.getPath(0);
            tracerPlugin.selectPath(onlyPath, addToExistingSelection);
            getGuiUtils().tempMsg(onlyPath + " selected");
            return onlyPath;
        } else for (final Path path : pathAndFillManager.getPaths()) {
            if (!path.isSelected()) {
                nodes.addAll(path.getUnscaledNodesInViewPort(this));
            }
        }
        if (nodes.isEmpty()) {
            if (pathAndFillManager.getSelectedPaths().isEmpty())
                getGuiUtils().tempMsg("Nothing to select. No paths in view");
            // else the closest path to the pointer is an already pre-selected path in view
            return null;
        }

        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                last_y_in_pane_precise, plane, p);
        final PointInCanvas cursor = new PointInCanvas(p[0], p[1], 0);

        final NearPointInCanvas<PointInCanvas> nearPoint = NearPointInCanvas.nearestPointInCanvas(nodes, cursor);
        if (nearPoint == null) {
            getGuiUtils().tempMsg("No selectable paths in view");
            return null;
        }
        else {
            final Path selectedPath = nearPoint.getPath();
            tracerPlugin.selectPath(selectedPath, addToExistingSelection);
            getGuiUtils().tempMsg(getShortName(selectedPath) + " selected");
            return selectedPath;
        }
    }

    private NearPoint getNearPointToMousePointer() {

        if (pathAndFillManager.size() == 0) {
            return null;
        }

//		System.out.println(last_x_in_pane_precise + ", " + last_y_in_pane_precise);
        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                last_y_in_pane_precise, plane, p);

        // FIXME: We are going to ignore Z coordinates. Not sure why I
        // decided this was a good idea. Perhaps needs to be revised?
        final Rectangle rect = super.getSrcRect();
        final PointInImage rectMin = new PointInImage(rect.getMinX(), rect
                .getMinY(), 0);
        final PointInImage rectMax = new PointInImage(rect.getMaxX(), rect
                .getMaxY(), 0);
        final PointInImage cursor = new PointInImage(p[0], p[1], 0);
        final double maxSquaredLength = Math.max(cursor.distanceSquaredTo(rectMin),
                cursor.distanceSquaredTo(rectMax));
//		System.out.println(SNTUtils.formatDouble(last_x_in_pane_precise, 2) + ", " +
//			SNTUtils.formatDouble(last_y_in_pane_precise, 2) + " | dx:" + maxSquaredLength);

        // Find the nearest point on unselected Paths currently displayed in
        // viewPort
        final List<Path> paths = pathAndFillManager
                .getUnSelectedPathsRenderedInViewPort(this);
        if (paths.isEmpty()) {
            return null;
        }
        cursor.z = Double.NaN; // ignore Z-positioning of path nodes
        return pathAndFillManager.nearestPointOnAnyPath(paths, cursor,
                maxSquaredLength, true);
    }

    @Override
    public void setCursor(final int sx, final int sy, final int ox,
                          final int oy)
    {
        if (isEventsDisabled() || !tracerPlugin.isUIready() || !cursorLocked)
            super.setCursor(sx, sy, ox, oy);
    }

    protected void onAltKeyDown() {
        if (editMode) {
            // already in edit mode: just update cursor if node is active
            if (!impossibleEdit(false)) setCursor(handCursor);
            return;
        }
        // Auto-enter edit mode if UI is idle and a single path is selected
        if (tracerPlugin.uiReadyForModeChange() && tracerPlugin.editModeAllowed(false)) {
            tracerPlugin.enableEditMode(true);
            autoEnteredEditMode = true;
            // mouseMoved hasn't fired yet so trigger a fake move to highlight nearest node
            fakeMouseMoved(false, false);
            if (!impossibleEdit(false)) setCursor(handCursor);
        }
    }

    protected void onAltKeyUp() {
        if (autoEnteredEditMode && !altDraggingNode) {
            autoEnteredEditMode = false;
            tracerPlugin.enableEditMode(false);
            setCursor(crosshairCursor);
            return;
        }
        autoEnteredEditMode = false;
        if (!altDraggingNode)
            setCursor(editMode ? defaultCursor : crosshairCursor);
    }

    @Override
    public void mouseMoved(final MouseEvent e) {

        super.mouseMoved(e);
        if (isEventsDisabled() || !tracerPlugin.isUIready()) return;

        last_x_in_pane_precise = myOffScreenXD(e.getX());
        last_y_in_pane_precise = myOffScreenYD(e.getY());

        boolean shift_key_down = e.isShiftDown();
        final boolean joiner_modifier_down = !editMode && (
                (tracerPlugin.requireShiftToFork) ? e.isShiftDown() && e.isAltDown() : e.isAltDown()
        );

        if (!editMode && tracerPlugin.snapCursor &&
                plane == MultiDThreePanes.XY_PLANE && !joiner_modifier_down &&
                !shift_key_down && tracerPlugin.accessToValidImageData())
        {
            final double[] p = new double[3];
            tracerPlugin.findSnappingPointInXView(last_x_in_pane_precise,
                    last_y_in_pane_precise, p);
            last_x_in_pane_precise = p[0];
            last_y_in_pane_precise = p[1];
            // Always sync panes if Z-snapping is enabled
            shift_key_down = tracerPlugin.cursorSnapWindowZ > 0;
        }

        tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
                plane, shift_key_down, joiner_modifier_down);

        if (editMode) {
            setCursor((tracerPlugin.getEditingNode() == -1) ? defaultCursor : handCursor);
        } else if (e.isAltDown() && !impossibleEdit(false)) {
            setCursor(handCursor); // signal: draggable node available
        } else {
            setCursor(crosshairCursor);
        }

        if (tracerPlugin.rubberBandTracing && !editMode) {
            triggerRubberBandSearch(); // has its only validators
        }
    }

    @Override
    public void mouseEntered(final MouseEvent e) {

        if (super.isEventsDisabled() || !tracerPlugin.isUIready()) {
            super.mouseEntered(e);
            return;
        }
        if (tracerPlugin.autoCanvasActivation) imp.getWindow().toFront();
    }

    /* See ImageCanvas#handlePopupMenu(me); */
    private boolean isPopupTrigger(final MouseEvent me) {
        return (me.isPopupTrigger() || (!PlatformUtils.isMac() && (me
                .getModifiersEx() & MouseEvent.META_DOWN_MASK) != 0));
    }

    @Override
    public void mousePressed(final MouseEvent me) {
        pressedX = me.getX();
        pressedY = me.getY();
        clickHandledByRelease = false;

        if (me.getButton() == MouseEvent.BUTTON2) {
            IJ.setKeyDown(KeyEvent.VK_SPACE);
            super.mousePressed(me);
            return;
        }
        if (isPopupTrigger(me)) {
            popupTriggered = true;
            clickHandledByRelease = true;
            showPopupMenu(me.getX(), me.getY());
            me.consume();
        } else if (tracerPlugin.panMode || isEventsDisabled() || !tracerPlugin.isUIready()) {
            super.mousePressed(me);
        }
    }

    @Override
    public void mouseReleased(final MouseEvent me) {
        if (me.getButton() == MouseEvent.BUTTON2) {
            IJ.setKeyUp(KeyEvent.VK_SPACE);
            super.mouseReleased(me);
            return;
        }
        if (altDraggingNode) {
            altDraggingNode = false;
            autoEnteredEditMode = false;  // commit: don't exit on key release
            return;
        }
        if (popupTriggered) {
            popupTriggered = false; // avoid simultaneous popupmenu showing and click action on MacOS
            return;
        }

        // When waiting for ROI drawing, let ImageJ handle the event first,
        // then check if a completed ROI exists for path selection.
        // On macOS, ROI stays in CONSTRUCTING state until another click,
        // so we trigger selection on any mouse release while drawing.
        if (waitingForRoiDrawing) {
            super.mouseReleased(me);
            if (getImage().getRoi() != null) {
                // Small delay to let ImageJ finalize the ROI coordinates
                SwingUtilities.invokeLater(() -> {
                    if (waitingForRoiDrawing && getImage().getRoi() != null) {
                        selectPathsByRoi();
                    }
                });
            }
            return;
        }
        if (tracerPlugin.panMode || isEventsDisabled()) {
            super.mouseReleased(me);
        } else if (isPopupTrigger(me)) { // somehow on windows (Java 21, IJ 1.44p) MouseEvent#isPopupTrigger() occurs on mouse release!?
            popupTriggered = true;
            showPopupMenu(me.getX(), me.getY());
        } else {
            if (me.getButton() == MouseEvent.BUTTON1 && me.getClickCount() == 2
                    && tracerPlugin.getUIState() == SNTUI.PARTIAL_PATH) {
                clickHandledByRelease = true; // prevent mouseClicked fallback from firing
                tracerPlugin.finishedPath();
                return;
            }
            // Check if mouse movement is within tolerance to treat as a click.
            // This accommodates slight movement during click (common on trackpads!?)
            final int dx = me.getX() - pressedX;
            final int dy = me.getY() - pressedY;
            if (me.getButton() == MouseEvent.BUTTON1 && (dx * dx + dy * dy) <= CLICK_TOLERANCE_SQ) {
                clickHandledByRelease = true;
                handleCanvasClick(me);
            }
        }
    }

    @Override
    public void mouseWheelMoved(final MouseWheelEvent e) {
        if (!e.isControlDown() || isEventsDisabled() || !tracerPlugin.isUIready()) {
            // Do not consume: re-dispatch to ImageWindow so IJ's zoom/scroll still works
            getParent().dispatchEvent(e);
            return;
        }
        final int state = tracerPlugin.getUIState();
        final boolean validState = editMode || state == SNTUI.WAITING_TO_START_PATH || state == SNTUI.PARTIAL_PATH;
        if (!validState) {
            getParent().dispatchEvent(e);
            return;
        }
        e.consume(); // maybe not needed?

        // Seed manualRadius: in edit mode, prefer the node's existing radius
        if (tracerPlugin.manualRadius <= 0) {
            if (editMode && !impossibleEdit(false)) {
                final Path ep = tracerPlugin.getEditingPath();
                final double nodeR = ep.getNodeRadius(ep.getEditableNodeIndex());
                tracerPlugin.manualRadius = (nodeR > 0) ? nodeR : tracerPlugin.getMinimumSeparation() / 2;
            } else {
                tracerPlugin.manualRadius = tracerPlugin.getMinimumSeparation() / 2;
            }
        }

        // Step = half a voxel width per notch (fine-grained)
        final double step = tracerPlugin.getMinimumSeparation() * 0.5 * e.getPreciseWheelRotation();
        tracerPlugin.manualRadius = Math.max(tracerPlugin.getMinimumSeparation() * 0.5,
                tracerPlugin.manualRadius - step); // scroll up: larger; down: smaller

        if (editMode) {
            applyManualRadiusToEditNode();
            repaint(); // no full viewer update, avoids triggering state resets
        } else {
            tracerPlugin.updateAllViewers(); // needed to refresh the cursor circle on all panes
        }

        // Always sync spinner after any change
        if (tracerPlugin.getUI() != null)
            tracerPlugin.getUI().updateAssignDiameterSpinner(); // already called on the EDT
    }


    private void applyManualRadiusToEditNode() {
        if (impossibleEdit(false)) return;
        final Path ep = tracerPlugin.getEditingPath();
        ep.setRadius(tracerPlugin.manualRadius, ep.getEditableNodeIndex());
        redrawEditingPath((String)null); // suppress msg; tempMsg already shows it
    }

    private boolean selectPathsByRoi() {
        final Collection<Path> paths = pathAndFillManager.getPathsInROI((getImage().getRoi()));
        if (!paths.isEmpty()) {
            if (tracerPlugin.getUI() != null)
                tracerPlugin.getUI().getPathManager().setSelectedPaths(paths, this);
            else
                tracerPlugin.setSelectedPaths(paths, this);
        }
        getImage().deleteRoi();
        waitingForRoiDrawing = false;
        if (paths.isEmpty())
            getGuiUtils().tempMsg("No paths selected. Invalid selection ROI?");
        return !paths.isEmpty();
    }

    private boolean unsuitableToolForRoiSelection() {
        return Toolbar.getToolId() > 3;
    }

    @Override
    public void mouseClicked(final MouseEvent e) {
        // Click handling is primarily done in mouseReleased with tolerance check.
        // This method serves as a fallback for edge cases where mouseReleased
        // didn't handle the click (e.g., exact zero movement on some platforms).
        if (clickHandledByRelease || popupTriggered || tracerPlugin.panMode ||
                isEventsDisabled() || isPopupTrigger(e))
        {
            super.mouseClicked(e);
            return;
        }
        handleCanvasClick(e);
    }

    @Override
    public void mouseDragged(final MouseEvent e) {
        if (editMode && e.isAltDown() && !impossibleEdit(false)) {
            if (tracerPlugin.getEditingPath().isEditableNodeLocked()) {
                if (!altDraggingNode) // show message only once, not on every drag event
                    tempMsg("Node is locked. Unlock it first (L)");
                return;
            }
            if (!altDraggingNode) {
                // First drag event: snapshot before any movement
                pushEditUndo();
                altDraggingNode = true;
            }
            last_x_in_pane_precise = myOffScreenXD(e.getX());
            last_y_in_pane_precise = myOffScreenYD(e.getY());
            moveEditingNodeToLastCanvasPosition(false); // silent — no tempMsg per drag event
            return; // don't pan or do anything else
        }
        altDraggingNode = false;
        super.mouseDragged(e);
    }

    /**
     * Handles click events for tracing operations. Called from both mouseReleased
     * (with tolerance) and mouseClicked (as fallback).
     */
    private void handleCanvasClick(final MouseEvent e) {
        switch (tracerPlugin.getUI().getState()) {

            case SNTUI.LOADING:
            case SNTUI.SAVING:
            case SNTUI.TRACING_PAUSED:
                return; // Do nothing
            case SNTUI.EDITING:
                impossibleEdit(true);
                break;
            case SNTUI.WAITING_FOR_SIGMA_POINT_I:
                startSigmaWizard(e.getX(), e.getY());
                break;
            case SNTUI.WAITING_FOR_SIGMA_CHOICE:
                getGuiUtils().tempMsg(
                        "You must close the sigma palette to continue");
                break;

            default:
                final boolean join = e.isAltDown();
                if (tracerPlugin.snapCursor && !join && !e.isShiftDown()) {
                    tracerPlugin.clickForTrace(last_x_in_pane_precise,
                            last_y_in_pane_precise, plane, false);
                }
                else {
                    tracerPlugin.clickForTrace(myOffScreenXD(e.getX()), myOffScreenYD(e
                            .getY()), plane, join);
                }
                break;
        }

    }

    private void startSigmaWizard(final int canvasX, final int canvasY) {
        tracerPlugin.getUI().launchSigmaPaletteAround(myOffScreenX(canvasX), myOffScreenY(canvasY));
        restoreDefaultCursor();
    }

    private boolean impossibleEdit(final boolean displayError) {
        boolean invalid = !tracerPlugin.getPathAndFillManager().isSelected(tracerPlugin
                .getEditingPath());
        if (invalid && displayError) getGuiUtils().tempMsg(
                "Editing path not selected");
        if (!invalid) {
            invalid = (tracerPlugin.getEditingNode() == -1);
            if (invalid && displayError) getGuiUtils().tempMsg("No node selected");
        }
        return invalid;
    }

    private void redrawEditingPath(final String msg) {
        redrawEditingPath(getGraphics2D(getGraphics()));
        repaint();
        if (msg != null) tempMsg(msg);
    }

    private void tempMsg(final String msg) {
        SwingUtilities.invokeLater(() -> InteractiveTracerCanvas.this.getGuiUtils().tempMsg(msg));
    }

    private void redrawEditingPath(final Graphics2D g) {
        tracerPlugin.getEditingPath().drawPathAsPoints(g, this, tracerPlugin);
    }

    @Override
    protected void addSearchArtist(final SearchArtist a) {
        super.addSearchArtist(a);
        if (a instanceof FillerThreadArtist) {
            ((FillerThreadArtist) a).setOpenColor(getFillColor());
            ((FillerThreadArtist) a).setClosedColor(getFillColor());
        }
    }

    @Override
    protected void drawOverlay(final Graphics2D g) {
        if (!tracerPlugin.getPathAndFillManager().enableUIupdates) return;

        final boolean drawDiametersXY = tracerPlugin.getDrawDiameters();
        final int sliceZeroIndexed = imp.getZ() - 1;
        int eitherSideParameter = eitherSide;
        if (!just_near_slices) eitherSideParameter = -1;

        super.drawOverlay(g); // draw all paths, crosshair, etc.

        if (editMode && tracerPlugin.getEditingPath() != null) {
            redrawEditingPath(g);
            if (tracerPlugin.manualRadius > 0 && last_x_in_pane_precise != Double.MIN_VALUE) {
                drawManualRadiusCursor(g);
            }
            if (connectionPreview != null) { // Draw connection preview if active
                connectionPreview.drawPathAsPoints(this, g, getAnnotationsColor(),
                        plane, false, sliceZeroIndexed, eitherSideParameter);
            }
            return; // no need to proceed: only editing path has been updated
        }

        // Now render temporary/incomplete paths
        final double spotDiameter = 2 * nodeDiameter();

        if (unconfirmedSegment != null) {
            unconfirmedSegment.drawPathAsPoints(this, g, getUnconfirmedPathColor(),
                    plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);
        }

        final Path currentPathFromTracer = tracerPlugin.getCurrentPath();

        if (currentPathFromTracer != null) {
            currentPathFromTracer.drawPathAsPoints(this, g, getTemporaryPathColor(),
                    plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);

            if (lastPathUnfinished && currentPath.size() == 0) { // first point in path
                final PointInImage p = new PointInImage(
                        tracerPlugin.last_start_point_x * tracerPlugin.x_spacing,
                        tracerPlugin.last_start_point_y * tracerPlugin.y_spacing,
                        tracerPlugin.last_start_point_z * tracerPlugin.z_spacing);
                p.onPath = currentPath;
                final PathNodeCanvas pn = new PathNodeCanvas(p, 0, this);
                pn.setSize(spotDiameter);
                pn.draw(g, getUnconfirmedPathColor());
            }
        }
        if (tracerPlugin.manualRadius > 0 && last_x_in_pane_precise != Double.MIN_VALUE) {
            drawManualRadiusCursor(g);
        }
    }

    private void drawManualRadiusCursor(final Graphics2D g) {
        final double mag = getMagnification();
        // Physical radius → screen pixels (use x-spacing for XY/XZ, z-spacing for ZY x-axis)
        final double screenRadius = switch (plane) {
            case MultiDThreePanes.ZY_PLANE -> tracerPlugin.manualRadius / tracerPlugin.z_spacing * mag;
            default ->                        tracerPlugin.manualRadius / tracerPlugin.x_spacing * mag;
        };
        final double sx = myScreenXDprecise(last_x_in_pane_precise);
        final double sy = myScreenYDprecise(last_y_in_pane_precise);

        final Stroke savedStroke = g.getStroke();
        final Color savedColor = g.getColor();
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[]{4f, 3f}, 0f)); // dashed
        g.setColor(getUnconfirmedPathColor()); // cyan by default — consistent with unconfirmed path
        g.draw(new Ellipse2D.Double(sx - screenRadius, sy - screenRadius, 2 * screenRadius, 2 * screenRadius));
        g.setStroke(savedStroke);
        g.setColor(savedColor);
        setCursorText(String.format("⌀= %.3f %s", tracerPlugin.manualRadius * 2, tracerPlugin.spacing_units));
    }

    private void enableEditMode(final boolean enable) {
        if (enable) {
            // Get currently selected path, or auto-select nearest if none
            Path pathToEdit = tracerPlugin.getSingleSelectedPath();
            if (pathToEdit == null) {
                pathToEdit = selectNearestPathToMousePointer(false);
                if (pathToEdit == null) {
                    return; // selectNearestPathToMousePointer already showed error
                }
            }
            if (!tracerPlugin.editModeAllowed(true, pathToEdit)) return;
        }
        tracerPlugin.enableEditMode(enable);
        // Ensure checkbox state matches actual edit mode state
        if (toggleEditModeMenuItem.isSelected() != enable) {
            toggleEditModeMenuItem.setSelected(enable);
        }
    }

    public void setTemporaryPathColor(final Color color) {
        this.temporaryColor = color;
    }

    public void setUnconfirmedPathColor(final Color color) {
        this.unconfirmedColor = color;
    }

    public void setFillColor(final Color color) {
        fillColor = color;
        for (SearchArtist artist : searchArtists) {
            if (artist instanceof FillerThreadArtist) {
                ((FillerThreadArtist) artist).setOpenColor(getFillColor());
                ((FillerThreadArtist) artist).setClosedColor(getFillColor());
            }
        }
    }

    public Color getTemporaryPathColor() {
        return (temporaryColor == null) ? Color.RED : temporaryColor;
    }

    public Color getUnconfirmedPathColor() {
        return (unconfirmedColor == null) ? Color.CYAN : unconfirmedColor;
    }

    public Color getFillColor() {
        if (fillColor == null) fillColor = new Color(0, 128, 0);
        if (fillTransparent) fillColor = SNTColor.alphaColor(fillColor, 50);
        return fillColor;
    }

    public JPopupMenu getComponentPopupMenu() {
        return pMenu;
    }

    protected void toggleEditMode() {
        toggleEditModeMenuItem.doClick();
    }


    private void toggleKeyWarning(final String key, final String msg) {
        final Timer timer = new Timer(500, ae -> {
            // Restore state after delay
            if ("H".equals(key)) {
                tracerPlugin.setAnnotationsVisible(true);
            } else if ("O".equals(key)) {
                PathNodeCanvas.setShowDirectionArrows(false);
                tracerPlugin.repaintAllPanes();
            }
        });
        timer.setRepeats(false);
        // Trigger the action immediately
        if ("H".equals(key)) {
            tracerPlugin.setAnnotationsVisible(false);
        } else if ("O".equals(key)) {
            PathNodeCanvas.setShowDirectionArrows(true);
            tracerPlugin.repaintAllPanes();
        }
        // Show tip if not suppressed
        if (!tracerPlugin.getPrefs().getTemp("key-skipnag" + key, false)) {
            final Boolean skipNag = getGuiUtils().getPersistentWarning(
                    "Tip: Hold \"" + key + "\" to " + msg + ".", "Keyboard Operation");
            if (skipNag != null) tracerPlugin.getPrefs().setTemp("key-skipnag" + key, skipNag);
        }

        timer.start();
    }

    protected void bookmarkCursorLocation() {
        final int[] p = new int[3];
        tracerPlugin.findPointInStack((int) Math.round(last_x_in_pane_precise), (int) Math.round(last_y_in_pane_precise), plane, p);
        tracerPlugin.getUI().getBookmarkManager().add(p[0], p[1], p[2], getImage());
        if (!tracerPlugin.getUI().getBookmarkManager().isShowing())
            tempMsg("Bookmark added");
    }

    protected void togglePauseTracing() {
        togglePauseTracingMenuItem.doClick();
    }

    protected void synchronizeControls() {
        toggleEditModeMenuItem.setSelected(tracerPlugin.isEditModeEnabled());
        togglePauseTracingMenuItem.setSelected(tracerPlugin.tracingHalted);
        togglePauseSNTMenuItem.setSelected(isEventsDisabled());
    }

    /** This class implements ActionListeners for InteractiveTracerCanvas's contextual menu. */
    private class AListener implements ActionListener, ItemListener {

        /* Listed shortcuts are specified in QueueJumpingKeyListener */
        private static final String UNDO_LAST_SEGMENT = "Undo Last Segment";
        private static final String UNDO_LAST_EDIT= "Undo Last Edit";
        private static final String FINISH_PATH = "Finish Path";
        private static final String HIDE_ALL = "Hide Paths (Hold H)";
        private static final String SHOW_ARROWS = "Path Orientation (Hold O)";
        private static final String CLICK_AT_MAX = "Click on Brightest Voxel Above/Below Cursor";
        private static final String FORK_NEAREST = "Fork at Nearest Node";
        private static final String BOOKMARK_CURSOR = "Bookmark Cursor Position";
        private static final String SELECT_NEAREST = "Select Nearest Path";
        private static final String APPEND_NEAREST = "Add Nearest Path to Selection";
        private static final String EXTEND_SELECTED = "Continue Extending Path";
        private static final String PAUSE_SNT_TOGGLE = "Pause SNT";
        private static final String PAUSE_TRACING_TOGGLE = "Pause Tracing";
        private static final String EDIT_TOGGLE_FORMATTER = "Edit %s";
        private final static String NODE_DELETE = "  Delete Active Node";
        private final static String NODE_INSERT = "  Insert New Node at Cursor Position";
        private final static String NODE_LOCK = "  Lock Active Node";
        private final static String NODE_MOVE = "  Move Active Node to Cursor Position";
        private final static String NODE_MOVE_Z = "  Bring Active Node to Current Z-plane";
        private final static String NODE_RADIUS = "  Set Active Node Radius...";
        private final static String NODE_COLOR = "  Tag Active Node";
        private final static String NODE_SET_ROOT = "  Set Active Node as Tree Root...";
        private final static String NODE_SPLIT = "  Split Tree at Active Node...";
        private final static String NODE_CONNECT_HELP = "  Connect to Help...";
        private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX = "  Connect to ";
        private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER = NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX
                + "...";
        private final static String START_SHOLL = "Sholl Analysis at Nearest Node";
        private final static String SELECT_BY_ROI = "Lasso Select Paths";

        @Override
        public void itemStateChanged(final ItemEvent e) {
            if (e.getSource().equals(toggleEditModeMenuItem)) {
                enableEditMode(toggleEditModeMenuItem.getState());
            } else if (e.getSource().equals(togglePauseSNTMenuItem)) {
                final boolean pause = togglePauseSNTMenuItem.isSelected();
                tracerPlugin.pause(pause, false);
            } else if (e.getSource().equals(togglePauseTracingMenuItem)) {
                tracerPlugin.pauseTracing(togglePauseTracingMenuItem.isSelected(), true);
            }
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            try {
                // Handle special case of extend path menu item
                if (e.getSource() == extendPathMenuItem) {
                    handleExtendPath();
                    return;
                }
                // Handle all other commands by action command string
                final String command = e.getActionCommand();
                if (command == null) {
                    SNTUtils.error("Received action event with null command: " + e);
                    return;
                }
                // Dispatch to appropriate handler based on command type
                if (handleGeneralCommands(command, e)) {
                    return;
                }
                if (handleEditCommands(command, e)) {
                    return;
                }
                // If we reach here, the command was not recognized
                SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);

            } catch (Exception ex) {
                SNTUtils.error("Error handling action event: " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        /** Handles the extend path menu item action. */
        private void handleExtendPath() {
            if (tracerPlugin.tracingHalted) {
                tempMsg("Tracing functions currently disabled");
                return;
            }
            if (pathAndFillManager.size() == 0) {
                tempMsg("There are no finished paths to extend");
                return;
            }
            if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
                tempMsg("Please finish current operation before extending path");
                return;
            }
            final Path activePath = tracerPlugin.getSingleSelectedPath();
            if (activePath == null) {
                getGuiUtils().tempMsg("No path selected. Please select a single path to be extended");
                return;
            }
            tracerPlugin.replaceCurrentPath(activePath);
        }

        /**
         * Handles general (non-edit) commands.
         * @param command The action command string
         * @param e The original action event
         * @return true if the command was handled, false otherwise
         */
        private boolean handleGeneralCommands(final String command, final ActionEvent e) {
            switch (command) {
                case SHOW_ARROWS:
                    toggleKeyWarning("O", "show path orientation (start → end)");
                    return true;
                case HIDE_ALL:
                    toggleKeyWarning("H", "temporarily hide paths");
                    return true;
                case BOOKMARK_CURSOR:
                    bookmarkCursorLocation();
                    return true;
                case CLICK_AT_MAX:
                    clickAtMaxPoint(false);
                    return true;
                case SELECT_NEAREST:
                    final boolean addToSelection = (e.getModifiers() & ActionEvent.SHIFT_MASK) > 0;
                    selectNearestPathToMousePointer(addToSelection);
                    return true;
                case APPEND_NEAREST:
                    selectNearestPathToMousePointer(true);
                    return true;
                case START_SHOLL:
                    return handleStartSholl();
                case FINISH_PATH:
                    tracerPlugin.finishedPath();
                    return true;
                case UNDO_LAST_SEGMENT:
                case UNDO_LAST_EDIT:
                    if (tracerPlugin.isEditModeEnabled())
                        undoLastEditOperation();
                    else
                        tracerPlugin.undoLastSegment();
                    return true;
                default:
                    // Check for commands that start with specific prefixes
                    if (command.startsWith(FORK_NEAREST)) {
                        return handleForkNearest();
                    }
                    return false;
            }
        }

        private boolean handleStartSholl() {
            if (pathAndFillManager.size() == 0) {
                getGuiUtils().error("There are no traced paths.");
            } else {
                startShollAnalysis();
            }
            return true;
        }

        private boolean handleForkNearest() {
            if (tracerPlugin.tracingHalted) {
                tempMsg("Tracing functions currently disabled");
                return true; // handled, just failed
            }
            if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
                tempMsg("Please finish current operation before creating branch");
                return true;
            }
            if (pathAndFillManager.size() == 0) {
                tempMsg("There are no finished paths to branch out from");
                return true;
            }
            selectNearestPathToMousePointer(false);
            tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise, plane, true, true);
            // Verify a valid join point exists before committing
            final double[] p = new double[3];
            tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise, last_y_in_pane_precise, plane, p);
            final PointInImage joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths(p[0], p[1], p[2]);
            if (joinPoint == null) {
                tempMsg("No fork point found. Move cursor closer to a path node");
                return true;
            }
            tracerPlugin.clickForTrace(last_x_in_pane_precise, last_y_in_pane_precise, plane, true);
            return true;
        }

        /**
         * Handles edit-related commands.
         * @param command The action command string
         * @param e The original action event
         * @return true if the command was handled, false otherwise
         */
        private boolean handleEditCommands(final String command, final ActionEvent e) {
            // Check for connection command first
            if (command.startsWith(NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX)) {
                final boolean fromMenu = e.getSource() instanceof JMenuItem;
                connectEditingPathToPreviousEditingPath(fromMenu);
                return true;
            }

            // For all other edit commands, check if editing is possible
            if (impossibleEdit(true)) {
                return true; // Command was handled (by showing error message)
            }

            // Handle specific edit commands
            return switch (command) {
                case NODE_DELETE -> {
                    deleteEditingNode(true);
                    yield true;
                }
                case NODE_INSERT -> {
                    appendLastCanvasPositionToEditingNode(true);
                    yield true;
                }
                case NODE_LOCK -> {
                    toggleEditingNode(true);
                    yield true;
                }
                case NODE_MOVE -> {
                    moveEditingNodeToLastCanvasPosition(true);
                    yield true;
                }
                case NODE_RADIUS -> {
                    assignRadiusToEditingNode(true);
                    yield true;
                }
                case NODE_COLOR -> {
                    assignColorToEditingNode();
                    yield true;
                }
                case NODE_MOVE_Z -> {
                    assignLastCanvasZPositionToEditNode(true);
                    yield true;
                }
                case NODE_SET_ROOT -> {
                    assignTreeRootToEditingNode(true);
                    yield true;
                }
                case NODE_SPLIT -> {
                    splitTreeAtEditingNode(true);
                    yield true;
                }
                default -> false; // Command not recognized as an edit command
            };
        }
    }

    private GuiUtils getGuiUtils() {
        if (imp == null || !imp.isVisible())
            return new GuiUtils(tracerPlugin.getUI());
        if (guiUtils == null)
            guiUtils = new GuiUtils(this);
        return guiUtils;
    }

    protected boolean isEditMode() {
        return editMode;
    }

    protected void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (!editMode) editUndoStack.clear();
    }

    private void pushEditUndo() {
        if (impossibleEdit(false)) return;
        editUndoStack.push(tracerPlugin.getEditingPath().clone());
        if (editUndoStack.size() > SNTPrefs.MAX_UNDO_STEPS)
            editUndoStack.removeLast();
    }

    protected void undoLastEditOperation() {
        if (editUndoStack.isEmpty()) {
            tempMsg("Nothing to undo");
            return;
        }
        tracerPlugin.getEditingPath().replaceNodes(editUndoStack.pop());
        redrawEditingPath("Edit undone");
    }

    protected void toggleEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        final Path editingPath = tracerPlugin.getEditingPath();
        if (editingPath.getEditableNodeIndex() < 0) {
            tempMsg("No editable node detected!");
        } else {
            editingPath.setEditableNodeLocked(!editingPath.isEditableNodeLocked());
            redrawEditingPath("Lock toggled on active node");
        }
    }

    protected void deleteEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        pushEditUndo();
        final Path editingPath = tracerPlugin.getEditingPath();
        final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
        if (editingPath.size() > 2) {
            if (editingPath.getBranchPoints().stream().anyMatch(n -> n.equals(editingNode))) {
                tempMsg("Cannot delete junction node. Try to split instead.");
                return;
            }
            try {
                editingPath.removeNode(editingPath.getEditableNodeIndex());
                redrawEditingPath("Node deleted");
            }
            catch (final IllegalArgumentException exc) {
                tempMsg("Node deletion failed!");
            }
        }
        else if (new GuiUtils(this.getParent()).getConfirmation("Delete " +
                editingPath + "?", "Delete Path?"))
        {
            boolean rebuild = false;
            for (final Path p : editingPath.connectedPaths) {
                if (p.getParentPath() == editingPath) {
                    rebuild = true;
                    break;
                }
            }
            editingPath.disconnectFromAll(); // Fixes ghost connection at canvas origin after deleting last node
            // in a forked path
            tracerPlugin.getPathAndFillManager().deletePath(editingPath);
            if (rebuild) tracerPlugin.getPathAndFillManager().rebuildRelationships();
            tracerPlugin.setEditingPath(null);
            tracerPlugin.updateAllViewers();
        }
    }

    protected void appendLastCanvasPositionToEditingNode(
            final boolean warnOnFailure)
    {
        if (impossibleEdit(warnOnFailure)) return;
        pushEditUndo();
        final Path editingPath = tracerPlugin.getEditingPath();
        final int editingNode = editingPath.getEditableNodeIndex();
        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                last_y_in_pane_precise, plane, p);
        final PointInCanvas offset = editingPath.getCanvasOffset();
        try {
            editingPath.insertNode(editingNode, new PointInImage((p[0] - offset.x) *
                    tracerPlugin.x_spacing, (p[1] - offset.y) * tracerPlugin.y_spacing,
                    (p[2] - offset.z) * tracerPlugin.z_spacing));
            editingPath.setEditableNode(editingNode + 1);
            redrawEditingPath("New node inserted (N=" + editingNode + ")");
        }
        catch (final IllegalArgumentException exc) {
            tempMsg("Node insertion failed!");
        }
    }

    protected void assignColorToEditingNode() {
        if (impossibleEdit(true)) return;
        final Path edPath = tracerPlugin.getEditingPath();
        final int edNode = edPath.getEditableNodeIndex();
        final JPopupMenu popup = GuiUtils.MenuItems.colorTagPopup(this, chosen -> {
            pushEditUndo();
            Color[] nodeColors = edPath.getNodeColors();
            if (nodeColors == null) {
                nodeColors = new Color[edPath.size()];
                Arrays.fill(nodeColors, edPath.getColor() != null
                        ? edPath.getColor() : tracerPlugin.selectedColor.darker());
            }
            nodeColors[edNode] = chosen; // null = remove tag
            edPath.setNodeColors(nodeColors);
            redrawEditingPath((chosen == null) ? String.format("Tag removed from node #%d", edNode) : null);
            if (tracerPlugin.getUI() != null) {
                tracerPlugin.getUI().getPathManager().update(true);
                final BookmarkManager bm = tracerPlugin.getUI().getBookmarkManager();
                if (chosen != null) bm.add(edPath, edNode);
            }
        });
        final Point p = MouseInfo.getPointerInfo().getLocation();
        SwingUtilities.convertPointFromScreen(p, this);
        popup.show(this, p.x, p.y);
    }

    protected void assignRadiusToEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure))
            return;
        final Path edPath = tracerPlugin.getEditingPath();
        final int edNode = edPath.getEditableNodeIndex();
        final boolean hasRadii = edPath.hasRadii();
        final String[] defChoices = new String[(hasRadii) ? 5 : 3];
        defChoices[0] = "<HTML>Assign <b>average of flanking nodes</b> (if any)";
        defChoices[1] = "<HTML>Assign half of <b>minimum voxel separation";
        defChoices[2] = "<HTML>Assign <b>value</b> specified below:";
        if (hasRadii) {
            defChoices[3] = "<HTML>Multiply existing radius by <b>multiplier<b> specified below:";
            defChoices[4] = "<HTML>Assign <b>mean path radius</b>";
        }
        String msg = "<HTML>Which value should be set as radius for node #" + edNode + "?<br>";
        if (hasRadii)
            msg += String.format("(Current radius is %02f)", edPath.getNodeRadius(edNode));
        else
            msg += "NB: No nodes in <i>" + edPath.getName() + "</i> have assigned radii!";

        final String prevChoice = tracerPlugin.getPrefs().getTemp("lastRadChoice", defChoices[0]);
        final String prevDouble = tracerPlugin.getPrefs().getTemp("lastRadDouble", null);
        final Object[] usrChoice = getGuiUtils().getChoiceAndDouble(msg, edPath.getName() + ": Ad hoc Radius",
                defChoices, prevChoice,
                (prevDouble == null) ? edPath.getNodeRadius(edNode) : Double.parseDouble(prevDouble));
        if (usrChoice == null)
            return;

        double r = Double.NaN;
        if (defChoices[0].equals(usrChoice[0])) {
            if (edPath.size() == 1) {
                guiUtils.error("Path has only one node. No flanking nodes exist.");
                return;
            } else {
                final int n1 = Math.max(0, edNode - 1);
                final int n2 = Math.min(edPath.size() - 1, edNode + 1);
                r = (edPath.getNodeRadius(n1) + edPath.getNodeRadius(n2)) / 2;
            }
        } else if (defChoices[1].equals(usrChoice[0])) {
            r = tracerPlugin.getMinimumSeparation() / 2;
        } else if (defChoices[2].equals(usrChoice[0])) {
            r = (double) usrChoice[1];
            tracerPlugin.getPrefs().setTemp("lastRadDouble", Double.toString((double) usrChoice[1]));
        } else if (hasRadii && defChoices[3].equals(usrChoice[0])) {
            r = edPath.getNodeRadius(edNode) * (double) usrChoice[1];
            tracerPlugin.getPrefs().setTemp("lastRadDouble", Double.toString((double) usrChoice[1]));
        } else if (hasRadii && defChoices[4].equals(usrChoice[0])) {
            r = edPath.getMeanRadius();
        }
        tracerPlugin.getPrefs().setTemp("lastRadChoice", (String) usrChoice[0]);
        if (Double.isNaN(r) || r < 0d) {
            guiUtils.error("Invalid radius. Must be a positive, floating-point value.");
        } else {
            pushEditUndo();
            edPath.setRadius(r, edNode);
            redrawEditingPath(String.format("Radius set to %02f", r));
        }
    }

    protected void moveEditingNodeToLastCanvasPosition(
            final boolean warnOnFailure)
    {
        if (impossibleEdit(warnOnFailure)) return;
        final Path editingPath = tracerPlugin.getEditingPath();
        final int editingNode = editingPath.getEditableNodeIndex();
        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                last_y_in_pane_precise, plane, p);
        final PointInCanvas offset = editingPath.getCanvasOffset();
        try {
            editingPath.moveNode(editingNode, new PointInImage((p[0] - offset.x) *
                    editingPath.x_spacing, (p[1] - offset.y) * editingPath.y_spacing,
                    (p[2] - offset.z) * editingPath.z_spacing));
            redrawEditingPath((warnOnFailure) ? "Node moved" : null);
        }
        catch (final IllegalArgumentException exc) {
            tempMsg("Node displacement failed!");
        }
    }

    protected void assignLastCanvasZPositionToEditNode(
            final boolean warnOnFailure)
    {
        if (impossibleEdit(warnOnFailure)) return;
        pushEditUndo();
        final Path editingPath = tracerPlugin.getEditingPath();
        final int editingNode = editingPath.getEditableNodeIndex();
        final PointInCanvas offset = editingPath.getCanvasOffset();
        final PointInImage currentNode = editingPath.getNodeWithoutChecks(editingNode);
        double newZ = switch (plane) {
            case MultiDThreePanes.XY_PLANE -> (imp.getZ() - 1 - offset.z) * tracerPlugin.z_spacing;
            case MultiDThreePanes.XZ_PLANE -> (last_y_in_pane_precise - offset.y) * tracerPlugin.y_spacing;
            case MultiDThreePanes.ZY_PLANE -> (last_x_in_pane_precise - offset.x) * tracerPlugin.x_spacing;
            default -> currentNode.z;
        };
        try {
            editingPath.moveNode(editingNode, new PointInImage(
                    currentNode.x,
                    currentNode.y, newZ));
            redrawEditingPath(String.format("Node %d moved to Z=%3f", editingNode, newZ));
        }
        catch (final IllegalArgumentException exc) {
            tempMsg("Adjustment of Z-position failed!");
        }
    }

    protected void assignTreeRootToEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        if (!getGuiUtils().getConfirmation(
                "Set active node as tree root? This operation cannot be undone.",
                "Confirm Re-root"))
            return;
        final Path editingPath = tracerPlugin.getEditingPath();

        try {
            long start = System.currentTimeMillis();

            // Use getConnectedTree - it gets exactly the tree containing the editing path
            final Tree editingTree = TreeUtils.getConnectedTree(editingPath);

            final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
            pathAndFillManager.enableUIupdates = false;

            final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
            final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
            SWCPoint newRoot = getMatchingPointInGraph(editingNode, editingGraph);

            if (newRoot == null) {
                SWCPoint nearest = getNearestPointInGraph(editingNode, editingGraph);
                if (nearest == null) {
                    pathAndFillManager.enableUIupdates = existingEnableUiUpdates;
                    getGuiUtils().error("Could not find a valid node for the new root.");
                    return;
                }
                final int uniqueId = editingGraph.vertexSet().stream().mapToInt(v -> v.id).max().orElse(-2) + 1;
                newRoot = new SWCPoint(uniqueId, nearest.type, editingNode.x, editingNode.y, editingNode.z,
                        editingPath.getNodeRadius(editingPath.getEditableNodeIndex()), nearest.id);
                newRoot.setPath(editingPath);
                editingGraph.addVertex(newRoot);
                editingGraph.addEdge(nearest, newRoot);
                if (editingPath.size() == 1)
                    pathAndFillManager.deletePath(editingPath);
                else
                    editingPath.removeNode(editingPath.getEditableNodeIndex());
            }

            editingGraph.setRoot(newRoot);
            final Tree newTree = editingGraph.getTreeWithSamePathStructure();
            tracerPlugin.setEditingPath(null);

            final Calibration cal = tracerPlugin.getCalibration();
            pathAndFillManager.deletePaths(editingTree.list());
            newTree.list().forEach(p -> {
                p.setSpacing(cal);
                pathAndFillManager.addPath(p, false, true);
            });

            pathAndFillManager.enableUIupdates = existingEnableUiUpdates;
            SNTUtils.log("Finished re-root in " + (System.currentTimeMillis() - start) + "ms");

        } catch (final IllegalArgumentException e) {
            getGuiUtils().error("<HTML>Could not re-root tree:<br><i>" + e.getMessage() + "</i>",
                    "Re-root Failed");
        }
    }

    protected void splitTreeAtEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        final Path editingPath = tracerPlugin.getEditingPath();

        try {
            long start = System.currentTimeMillis();

            // Use getConnectedTree to avoid issues with disconnected paths sharing tree ID
            final Tree editingTree = TreeUtils.getConnectedTree(editingPath);
            // Warn user that this operation cannot be undone
            if (!getGuiUtils().getConfirmation(
                    "Split tree at active node? This operation cannot be undone.",
                    "Confirm Split"))
                return;
            final PointInImage editingPoint = editingPath.getNode(editingPath.getEditableNodeIndex());

            if (editingTree.getRoot().equals(editingPoint)) {
                getGuiUtils().tempMsg("Cannot split tree at root node.");
                return;
            }

            final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
            final SWCPoint editingVertex = getMatchingPointInGraph(editingPoint, editingGraph);

            if (editingVertex == null) {
                getGuiUtils().error("Could not locate the selected node in the tree graph.");
                return;
            }

            final Set<SWCWeightedEdge> incomingEdges = editingGraph.incomingEdgesOf(editingVertex);
            if (incomingEdges.isEmpty()) {
                getGuiUtils().error("Selected node has no incoming edges. Cannot split here.");
                return;
            }

            editingGraph.removeEdge(incomingEdges.iterator().next());
            final DepthFirstIterator<SWCPoint, SWCWeightedEdge> depthFirstIterator =
                    editingGraph.getDepthFirstIterator(editingVertex);
            final Set<SWCPoint> descendantVertexSet = new HashSet<>();
            while (depthFirstIterator.hasNext()) {
                descendantVertexSet.add(depthFirstIterator.next());
            }

            final DirectedWeightedSubgraph descendantSubgraph = editingGraph.getSubgraph(descendantVertexSet);
            final DirectedWeightedGraph descendantGraph = new DirectedWeightedGraph();
            Graphs.addGraph(descendantGraph, descendantSubgraph);
            editingGraph.removeAllVertices(descendantVertexSet);

            final Tree ancestorTree = editingGraph.getTreeWithSamePathStructure();
            final Tree descendantTree = descendantGraph.getTreeWithSamePathStructure();

            tracerPlugin.setEditingPath(null);

            final Calibration cal = tracerPlugin.getCalibration();
            ancestorTree.list().forEach(p -> p.setSpacing(cal));
            descendantTree.list().forEach(p -> p.setSpacing(cal));

            final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
            pathAndFillManager.enableUIupdates = false;
            pathAndFillManager.deletePaths(editingTree.list());
            ancestorTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
            descendantTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
            pathAndFillManager.enableUIupdates = existingEnableUiUpdates;

            SNTUtils.log("Finished split in " + (System.currentTimeMillis() - start) + "ms");

        } catch (final IllegalArgumentException e) {
            getGuiUtils().error("<HTML>Could not split tree:<br><i>" + e.getMessage() + "</i>",
                    "Split Failed");
        }
    }

    private SWCPoint getNearestPointInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
        double distanceSquaredToNearestParentPoint = Double.MAX_VALUE;
        SWCPoint nearest = null;
        for (final SWCPoint p : graph.vertexSet()) {
            final double distanceSquared = point.distanceSquaredTo(p.x, p.y, p.z);
            if (distanceSquared < distanceSquaredToNearestParentPoint) {
                nearest = p;
                distanceSquaredToNearestParentPoint = distanceSquared;
            }
        }
        return nearest;
    }

    private SWCPoint getMatchingPointInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
        for (final SWCPoint p : graph.vertexSet()) {
            if (p.isSameLocation(point) && p.getPath().equals(point.getPath())) {
                return p;
            }
        }
        return null;
    }

    public void setLookAndFeel(final String lookAndFeelName) {
        GuiUtils.setLookAndFeel(lookAndFeelName, false, pMenu);
    }
}
