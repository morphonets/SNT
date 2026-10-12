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

import org.scijava.util.PlatformUtils;
import sc.fiji.snt.analysis.curation.PlausibilityCheck;
import sc.fiji.snt.analysis.curation.PlausibilityMonitor;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ContextMenuCommand;
import sc.fiji.snt.gui.NodeEditCommand;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.tracing.artist.FillerThreadArtist;
import sc.fiji.snt.tracing.artist.SearchArtist;
import sc.fiji.snt.util.*;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Arc2D;
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
    private JCheckBoxMenuItem togglePaintModeMenuItem;
    private JMenuItem extendPathMenuItem;
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
    private boolean paintMode; // sub-mode of edit: drag to sculpt path
    private boolean paintStrokeActive; // true while a paint drag is in progress
    private boolean paintRadiusDragging; // true while Alt+drag radius smearing is in progress
    private boolean paintBrushCursorVisible; // transient flag for brush circle during Ctrl+scroll
    private String paintCursorLabel; // transient label rendered below the paint brush circle
    private javax.swing.Timer paintBrushCursorTimer;
    private int paintBrushRadius = 10; // number of flanking nodes affected on each side
    private static final int MIN_BRUSH_RADIUS = 1;
    private static final int MAX_BRUSH_RADIUS = 100;

    private Color temporaryColor;
    private Color unconfirmedColor;
    private Color fillColor;
    private GuiUtils guiUtils;
    protected static String EDIT_MODE_LABEL = "Edit Mode";
    protected static String PAINT_MODE_LABEL = "Edit Mode (Paint)";
    protected static String SNT_PAUSED_LABEL = "SNT Paused";
    protected static String TRACING_PAUSED_LABEL = "Tracing Paused";

    // Preview path for connection visualization
    private Path connectionPreview;

    /* fixes for 'failed' clicks, see https://forum.image.sc/t/snt-mouse-clicks-often-fail-when-tracing/115544 */
    private int pressedX, pressedY;
    private boolean clickHandledByRelease;
    private boolean popupTriggered;
    private boolean altDraggingNode;
    private boolean scrollRadiusUndoPushed; // true once undo is pushed for the current scroll gesture
    private Timer scrollRadiusUndoResetTimer; // resets the flag after scroll gesture ends

    // Squared pixel tolerance for click detection (5 pixels). This accommodates
    // slight mouse/trackpad movement during click, especially on macOS trackpads.
    private static final int CLICK_TOLERANCE_SQ = 25;

    private List<PointInImage> warningLocations = new ArrayList<>();
    private List<Path> warningAffectedPaths = new ArrayList<>();
    private PlausibilityCheck.Severity warningMaxSeverity = null;
    private boolean warningViewportTint = false;
    private long warningFlashStart = 0;
    private static final long FLASH_DURATION_MS = 3000;
    private static final long TINT_DURATION_MS = 400;
    private static final Color WARNING_RING_COLOR = new Color(255, 165, 0); // orange
    private static final Color ERROR_RING_COLOR = new Color(255, 80, 80); // red
    private static final Color VIEWPORT_TINT_COLOR = new Color(255, 60, 60, 50); // faint red wash

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
        final String accelerator = (tracerPlugin.getPrefs().getRequireShiftToFork()) ? " (or Alt+Shift+Click)" : " (or Alt+Click)";
        forkNearestMenuItem.setText(AListener.FORK_NEAREST + accelerator);
    }

    private void buildPopupMenu() {
        pMenu = new JPopupMenu();
        pMenu.putClientProperty("owner", this); // see SNTCommandFinder#revealMenuItem()
        // Required because we are mixing lightweight and heavyweight components?
        pMenu.setLightWeightPopupEnabled(false);

        final AListener listener = new AListener();
        final JMenuItem selectNearest = menuItem(ContextMenuCommand.GRAB, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_G, 0));
        selectNearest.setToolTipText("Select nearest path to cursor");
        pMenu.add(selectNearest);
        final JMenuItem appendNearest = menuItem(ContextMenuCommand.APPEND, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.SHIFT_DOWN_MASK));
        appendNearest.setToolTipText("Add nearest path to current selection");
        pMenu.add(appendNearest);
        final JMenuItem selectByRoi = getSelectRoiMenuItem();
        pMenu.add(selectByRoi);
        pMenu.addSeparator();
        pMenu.add(menuItem(ContextMenuCommand.HIDE, listener, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0)));
        pMenu.add(menuItem(ContextMenuCommand.ORIENTATION, listener, KeyStroke.getKeyStroke(KeyEvent.VK_O, 0)));
        pMenu.addSeparator();

        pMenu.add(menuItem(ContextMenuCommand.BOOKMARK, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.SHIFT_DOWN_MASK)));
        pMenu.add(menuItem(ContextMenuCommand.SHOLL, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.SHIFT_DOWN_MASK + KeyEvent.ALT_DOWN_MASK)));
        pMenu.addSeparator();

        JMenuItem mi = menuItem(ContextMenuCommand.BRIGHTEST, listener, KeyStroke.getKeyStroke(KeyEvent.VK_V, 0));
        pMenu.add(mi);
        extendPathMenuItem = menuItem(ContextMenuCommand.EXTEND, listener, null);
        pMenu.add(extendPathMenuItem);
        mi = menuItem(ContextMenuCommand.FORK, listener, null);
        mi.setToolTipText("Branches off a child path at nearest node"); // alternative discovery text for command palette
        pMenu.add(mi);

        pMenu.addSeparator();
        undoSegmentMenuItem = menuItem(ContextMenuCommand.UNDO_SEGMENT, listener, KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0));
        pMenu.add(undoSegmentMenuItem);
        final JMenuItem finishPathMenuItem = menuItem(ContextMenuCommand.FINISH, listener,
                KeyStroke.getKeyStroke(KeyEvent.VK_F, 0));
        pMenu.add(finishPathMenuItem);

        pMenu.addSeparator();
        toggleEditModeMenuItem = new JCheckBoxMenuItem(NodeEditCommand.editToggleLabel(null),
                IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        toggleEditModeMenuItem.addItemListener(listener);
        toggleEditModeMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift E"));
        toggleEditModeMenuItem.setMnemonic(KeyEvent.VK_E);
        pMenu.add(toggleEditModeMenuItem);

        // Node commands: same entries, labels and order as in the Bdv/Bvv context menu (see NodeEditCommand)
        for (final NodeEditCommand cmd : NodeEditCommand.values()) {
            if (cmd == NodeEditCommand.UNDO) continue; // see undoSegmentMenuItem
            final JMenuItem item = cmd.createMenuItem(listener);
            item.setText(AListener.INDENT + item.getText());
            pMenu.add(item);
        }
        // Canvas-only commands
        pMenu.add(menuItem(AListener.NODE_MOVE_Z, listener, KeyEvent.VK_B, null));
        connectToSecondaryEditingPath = menuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER, listener,
                null, null);
        connectToSecondaryEditingPath.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0));
        connectToSecondaryEditingPath.setMnemonic(KeyEvent.VK_C);
        pMenu.add(connectToSecondaryEditingPath);
        pMenu.add(helpOnConnectingMenuItem());
        togglePaintModeMenuItem = new JCheckBoxMenuItem(AListener.NODE_PAINT);
        togglePaintModeMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0));
        togglePaintModeMenuItem.setMnemonic(KeyEvent.VK_P);
        togglePaintModeMenuItem.addItemListener(listener);
        pMenu.add(togglePaintModeMenuItem);

        // Pause tracing/SNT
        pMenu.addSeparator();
        togglePauseTracingMenuItem = new JCheckBoxMenuItem(ContextMenuCommand.PAUSE_TRACING.label());
        ContextMenuCommand.PAUSE_TRACING.applyIcon(togglePauseTracingMenuItem);
        togglePauseTracingMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift P"));
        togglePauseTracingMenuItem.setMnemonic(KeyEvent.VK_P);
        togglePauseTracingMenuItem.addItemListener(listener);
        pMenu.add(togglePauseTracingMenuItem);
        togglePauseSNTMenuItem = new JCheckBoxMenuItem(ContextMenuCommand.PAUSE_SNT.label());
        ContextMenuCommand.PAUSE_SNT.applyIcon(togglePauseSNTMenuItem);
        togglePauseSNTMenuItem.addItemListener(listener);
        pMenu.add(togglePauseSNTMenuItem);
    }

    private JMenuItem getSelectRoiMenuItem() {
        final JMenuItem selectByRoi = new JMenuItem(ContextMenuCommand.LASSO.label());
        ContextMenuCommand.LASSO.applyIcon(selectByRoi);
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
                canvasInfo("Draw ROI around paths to be selected. Current tool: " + IJ.getToolName());
            }
        });
        return selectByRoi;
    }

    private void showPopupMenu(final int x, final int y) {
        final Path activePath = tracerPlugin.getSingleSelectedPath();
        final boolean be = canvasReadyForModeChange(SNTUI.EDITING);
        final boolean bp = canvasReadyForModeChange(SNTUI.SNT_PAUSED);
        final boolean tracingActive = !editMode && !tracerPlugin.tracingHalted;

        extendPathMenuItem.setText(
                (activePath != null) ? "Continue Extending " + NodeEditCommand.shortName(activePath) : AListener.EXTEND_SELECTED);
        toggleEditModeMenuItem.setText(NodeEditCommand.editToggleLabel(
                (activePath == null) ? null : NodeEditCommand.shortName(activePath)));
        toggleEditModeMenuItem.setState(be && editMode);
        togglePaintModeMenuItem.setSelected(paintMode);
        togglePaintModeMenuItem.setEnabled(be && editMode);
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
                mItem.setEnabled(tracingActive);
            }
            else if (cmd.equals(AListener.FINISH_PATH)) {
                mItem.setEnabled(tracingActive && tracerPlugin.currentPath != null);
            }
            else if (undoSegmentMenuItem == mItem) {
                mItem.setText((editMode) ? AListener.UNDO_LAST_EDIT : AListener.UNDO_LAST_SEGMENT);
                mItem.setEnabled(
                        tracingActive && !tracerPlugin.confirmedSegmentSizes.isEmpty() // condition 1: tracing mode
                                || editMode && tracerPlugin.getNodeEditor().canUndo() // condition 2: edit mode
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
            // Stream mode: bookmarks are created exclusively via 'M' in the Bvv/Bdv
            // viewer; disable the display canvas's own entry point to avoid two
            // independent bookmark-creation paths with different coordinate spaces
            else if (cmd.equals(AListener.BOOKMARK_CURSOR)) {
                mItem.setEnabled(!tracerPlugin.isStreamMode());
            }
            // Edit mode node commands
            else if (cmd.equals(AListener.APPEND_NEAREST) || cmd.equals(AListener.SELECT_BY_ROI)) {
                mItem.setEnabled(!editMode); // In edit mode, only one path selected at any given time
            }
            else if (cmd.equals(AListener.NODE_MOVE_Z)) {
                mItem.setEnabled(be && editMode && !tracerPlugin.is2D());
            }
            else if (NodeEditCommand.fromActionId(cmd) != null || cmd.equals(AListener.NODE_CONNECT_HELP)
                    || cmd.equals(AListener.NODE_PAINT)) {
                if (cmd.equals(NodeEditCommand.LOCK.actionId()))
                    mItem.setText(AListener.INDENT + NodeEditCommand.LOCK.label(editMode && editor().isActiveNodeLocked()));
                mItem.setEnabled(be && editMode);
            }
            // Everything else always enabled (SELECT_NEAREST, APPEND_NEAREST,
            // HIDE_ALL, SHOW_ARROWS, START_SHOLL, pan, etc.)
            else {
                mItem.setEnabled(true);
            }
        }
        updateConnectToSecondaryEditingPathMenuItem();
        pMenu.show(this, x, y);
    }

    protected void connectEditingPathToPreviousEditingPath(final boolean showDialog) {
        final PathNodeEditor ed = editor();
        if (!editMode) {
            getGuiUtils().error(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + ": No connectable node exist.",
                    "No Connectable Nodes");
            return;
        }
        final Path childPath = ed.getPath();
        final Path parentPath = ed.getSecondaryPath();
        final boolean previewable = childPath != null && parentPath != null //
                && childPath.getEditableNodeIndex() != -1 && parentPath.getEditableNodeIndex() != -1 //
                && !tracerPlugin.isEditingFittedPath();
        final boolean previousArrowState = PathNodeCanvas.isShowDirectionArrows();
        if (previewable) {
            // Preview path from parent to child (showing direction of connection). Uses canvas (unscaled)
            // coordinates to avoid offset issues between paths
            final PointInCanvas parentUnscaled = parentPath.getUnscaledNodes().get(parentPath.getEditableNodeIndex());
            final PointInCanvas childUnscaled = childPath.getUnscaledNodes().get(childPath.getEditableNodeIndex());
            connectionPreview = new Path(tracerPlugin.x_spacing, tracerPlugin.y_spacing, tracerPlugin.z_spacing,
                    tracerPlugin.spacing_units);
            // Convert canvas coords back to world coords for the preview (with zero offset)
            connectionPreview.addNode(new PointInImage(parentUnscaled.x * tracerPlugin.x_spacing,
                    parentUnscaled.y * tracerPlugin.y_spacing, parentUnscaled.z * tracerPlugin.z_spacing));
            connectionPreview.addNode(new PointInImage(childUnscaled.x * tracerPlugin.x_spacing,
                    childUnscaled.y * tracerPlugin.y_spacing, childUnscaled.z * tracerPlugin.z_spacing));
            // Enable direction arrows and repaint to show preview
            PathNodeCanvas.setShowDirectionArrows(true);
            tracerPlugin.updateTracingViewers(false);
        }
        try {
            // Validation, prompt and connection are handled by the shared editor
            final PathNodeEditor.Result r = ed.connectToSecondaryPath(getGuiUtils(), showDialog);
            connectionPreview = null; // Clear preview before updating the viewers to avoid duplicate rendering
            report(r, true);
        } finally {
            // Always restore state and clear preview (in case of early return)
            connectionPreview = null;
            PathNodeCanvas.setShowDirectionArrows(previousArrowState);
            tracerPlugin.updateAllViewers();
        }
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
        final String label = NodeEditCommand.shortName(tracerPlugin.getPreviousEditingPath()) + " (node "
                + tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() + ")";
        connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + label);
    }

    private JMenuItem helpOnConnectingMenuItem() {
        final boolean[] canvasActivationState = new boolean[1];
        return GuiUtils.MenuItems.connectHelp(this::getGuiUtils, () -> {
            canvasActivationState[0] = tracerPlugin.getPrefs().isCanvasAutoActivationEnabled();
            // this will not update the checkbox state in SNTUI, but ensures the help dialog will maintain its
            // frontmost state
            tracerPlugin.getPrefs().setCanvasAutoActivation(false);
        }, () -> tracerPlugin.getPrefs().setCanvasAutoActivation(canvasActivationState[0]));
    }

    /** Menu item for a command shared with the Bdv/Bvv context menu: label and icon come from the enum */
    private JMenuItem menuItem(final ContextMenuCommand cmd, final ActionListener lstnr, final KeyStroke keystroke) {
        final JMenuItem mi = GuiUtils.MenuItems.menuItem(cmd.label(), lstnr, keystroke, (IconFactory.GLYPH) null);
        cmd.applyIcon(mi);
        return mi;
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final int keycode,
                               final IconFactory.GLYPH glyph) {
        return GuiUtils.MenuItems.menuItem(cmdName, lstnr, KeyStroke.getKeyStroke(keycode, 0), glyph);
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke,
                               final IconFactory.GLYPH glyph) {
        return GuiUtils.MenuItems.menuItem(cmdName, lstnr, keystroke, glyph);
    }

    private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke,
                               final char symbol, final boolean solid, final Color color) {
        return GuiUtils.MenuItems.menuItem(cmdName, lstnr, keystroke, symbol, solid, color);
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

    /**
     * Canvas-local readiness test for this popup menu's own mode-changing commands (Edit Mode, Pause
     * SNT, Extend Path, Fork Nearest, ...) - NOT the same as, and not related to, {@link SNT#uiReadyForModeChange()}
     * (no-arg, on {@code tracerPlugin}): that one is the generic guard used by {@code SNT#pause}/
     * {@code SNT#pauseTracing}'s own validation and by {@code SNT#editModeAllowed}; this one is specific
     * to what this classic canvas's own context menu should currently allow, and additionally requires a
     * real, materialized canvas to exist at all (see {@code nonStreamedCanvasExists} below) - the two are
     * easy to conflate by name alone, so read carefully if working on either.
     * <p>
     * {@code tracingHalted ||} is a deliberate escape hatch, not dead weight: once tracing is halted,
     * Edit Mode and Pause SNT should stay freely reachable regardless of the nominal {@code state} - e.g.
     * {@code SNT_PAUSED} can be entered on top of an already-{@code TRACING_PAUSED} session (see
     * {@code SNT#pause}'s resume path, which restores tracingHalted's pre-pause value via
     * {@code pauseTracing(tracingHalted, false)}), and a paused user should still be able to edit existing
     * paths. It is, however, inert at the two call sites in this class that already check
     * {@code tracerPlugin.tracingHalted} and return earlier when it is true ({@link AListener#handleExtendPath()},
     * {@link AListener#handleForkNearest()}) - by the time either reaches this method, tracingHalted is
     * always false there, so only {@code state == WAITING_TO_START_PATH} can make it true.
     *
     * @param mode the target {@code SNTUI} state the caller wants to confirm is reachable right now
     */
    private boolean canvasReadyForModeChange(final int mode) {
        if (!tracerPlugin.isUIready()) return false;
        final int state = tracerPlugin.getUIState();
        // This popup only applies to a real, paintable canvas: traditional mode, or a materialized crop
        // while streaming - never a live Bvv/Bdv-only scene, which has no classic canvas to right-click
        final boolean nonStreamedCanvasExists = !tracerPlugin.isStreamMode() || tracerPlugin.isMaterializedCrop();
        return nonStreamedCanvasExists &&
                (tracerPlugin.tracingHalted || state == SNTUI.WAITING_TO_START_PATH || state == mode);
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
            canvasWarning("This option requires valid image data to be loaded.");
            return;
        }
        final int x = (int) Math.round(last_x_in_pane_precise);
        final int y = (int) Math.round(last_y_in_pane_precise);
        final int[] p = new int[3];
        tracerPlugin.findPointInStack(x, y, plane, p);
        tracerPlugin.clickAtMaxPoint(x, y, plane, join_modifier_pressed);
    }

    /**
     * Sets the warning overlay state for the canvas.
     *
     * @param locations     spatial locations of warnings (may be null/empty)
     * @param affectedPaths paths involved in warnings, highlighted with a thick overlay
     * @param maxSeverity   the highest severity among the warnings (drives visual tier)
     * @param viewportTint  whether to flash a brief viewport tint (for ERRORs when panel not visible)
     */
    public void setWarningOverlay(final List<PointInImage> locations,
                                  final List<Path> affectedPaths,
                                  final PlausibilityCheck.Severity maxSeverity,
                                  final boolean viewportTint) {
        this.warningLocations = (locations != null) ? new ArrayList<>(locations) : new ArrayList<>();
        this.warningAffectedPaths = (affectedPaths != null) ? new ArrayList<>(affectedPaths) : new ArrayList<>();
        this.warningMaxSeverity = maxSeverity;
        this.warningViewportTint = viewportTint;
        this.warningFlashStart = System.currentTimeMillis();
        repaint();
    }

    public void clearWarningOverlay() {
        warningLocations.clear();
        warningAffectedPaths.clear();
        warningMaxSeverity = null;
        warningViewportTint = false;
        repaint();
    }

    @Override
    public void repaint() {
        super.repaint();
        // Schedule repaints for pulsing animation while flash is active
        if (!warningLocations.isEmpty() || !warningAffectedPaths.isEmpty()) {
            final long elapsed = System.currentTimeMillis() - warningFlashStart;
            if (elapsed < FLASH_DURATION_MS) {
                super.repaint(50);
            }
        }
    }

    // Tracks the single node (if any) highlighted by this canvas to indicate the resolved
    // Sholl focal point. Kept narrowly scoped (only ever touches the one node it itself set)
    // See https://forum.image.sc/t/121682
    private Path shollHighlightPath;

    protected void startShollAnalysis() {
        PointInImage centerScaled = null;
        Path centerPath = null;
        int centerIndex = -1;
        if (pathAndFillManager.anySelected()) {
            final double[] p = new double[3];
            tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                    last_y_in_pane_precise, plane, p);
            // p[] is a pane-local pixel index; correct against the active canvas offset before
            // comparing to existing paths' true-world node coordinates (see SNT#getActiveCanvasPixelOffset()).
            final PointInCanvas offset = tracerPlugin.getActiveCanvasPixelOffset();
            // The version in use (fitted or not) is the one displayed and analyzed by the Sholl command
            final PathAndFillManager.NodeRef ref = pathAndFillManager.nearestNodeInUse(
                    pathAndFillManager.getSelectedPaths(), (p[0] - offset.x) * tracerPlugin.x_spacing,
                    (p[1] - offset.y) * tracerPlugin.y_spacing, (p[2] - offset.z) * tracerPlugin.z_spacing);
            if (ref != null) {
                centerPath = ref.path();
                centerIndex = ref.index();
                centerScaled = centerPath.getNode(centerIndex);
            }
        }
        else {
            final NearPoint np = getNearPointToMousePointer();
            if (np != null) {
                centerScaled = np.getNode();
                centerPath = np.getPath();
                centerIndex = np.indexInPath;
            }
        }
        if (centerScaled == null) {
            canvasWarning("No selectable nodes in view");
            return;
        }
        highlightShollCenterNode(centerPath, centerIndex);
        // Repaint so the highlighted focal node is visible before/while the Sholl prompt opens. Only the
        // classic canvas panes render editableNodeIndex, so a full updateAllViewers() is unnecessary here
        tracerPlugin.repaintAllPanes();
        tracerPlugin.startSholl(centerScaled);
    }

    private void highlightShollCenterNode(final Path path, final int index) {
        if (shollHighlightPath != null) shollHighlightPath.setEditableNode(-1);
        shollHighlightPath = path;
        if (path != null) path.setEditableNode(index);
    }

    /**
     * Selects the path nearest to the current mouse pointer position.
     *
     * @param addToExistingSelection if true, adds to current selection; if false, replaces it
     * @return the selected path, or null if no path could be selected
     */
    public Path selectNearestPathToMousePointer(final boolean addToExistingSelection) {
        if (pathAndFillManager.size() == 0) {
            canvasWarning("Nothing to select: There are no traced paths");
            return null;
        }

        final List<PointInCanvas> nodes = new ArrayList<>();
        if (pathAndFillManager.size() == 1) {
            // There is only one path, presumably from a just-finished tracing operation.
            // NB: Selection status of such path depends on the "Finishing a path selects
            // it" checkbox in the GUI. We'll force select it.
            final Path onlyPath = pathAndFillManager.getPath(0);
            tracerPlugin.selectPath(onlyPath, addToExistingSelection);
            canvasInfo(onlyPath + " selected");
            return onlyPath;
        } else {
            final boolean allSelected = pathAndFillManager.getSelectedPaths().isEmpty() ||
                    pathAndFillManager.getSelectedPaths().size() == pathAndFillManager.getPaths().size();
            for (final Path path : pathAndFillManager.getPaths()) {
                if (allSelected || !path.isSelected()) {
                    nodes.addAll(path.getUnscaledNodesInViewPort(this));
                }
            }
        }
        if (nodes.isEmpty()) {
            if (pathAndFillManager.getSelectedPaths().isEmpty())
                canvasWarning("Nothing to select. No paths in view");
            // else the closest path to the pointer is an already pre-selected path in view
            return null;
        }

        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise, last_y_in_pane_precise, plane, p);
        final PointInCanvas cursor = new PointInCanvas(p[0], p[1], 0);

        final NearPointInCanvas<PointInCanvas> nearPointOverall = NearPointInCanvas.nearestPointInCanvas(nodes, cursor);
        if (nearPointOverall == null) {
            canvasWarning("No selectable paths in view");
            return null;
        }
        // If a single arbor is isolated, favor it: a node in another arbor only wins if it is closer by more than
        // NEAREST_PATH_ISOLATION_BIAS, so a pick that just grazes a neighboring structure still resolves to the arbor
        // already in focus (mirrors AbstractBigViewer#findNearestRenderedPath, used by the Bdv/Bvv G/Shift+G shortcuts)
        NearPointInCanvas<PointInCanvas> nearPoint = nearPointOverall;
        if (tracerPlugin.isTreeIsolationActive()) {
            final int isolatedID = tracerPlugin.getIsolatedTreeID();
            final List<PointInCanvas> isolatedNodes = new ArrayList<>();
            for (final PointInCanvas node : nodes) {
                if (node.onPath.getTreeID() == isolatedID) isolatedNodes.add(node);
            }
            final NearPointInCanvas<PointInCanvas> nearPointIsolated = isolatedNodes.isEmpty() ? null
                    : NearPointInCanvas.nearestPointInCanvas(isolatedNodes, cursor);
            if (nearPointIsolated != null) {
                final double biasSq = SNTPrefs.NEAREST_PATH_ISOLATION_BIAS * SNTPrefs.NEAREST_PATH_ISOLATION_BIAS;
                nearPoint = (nearPointOverall.getDistanceSquared() * biasSq < nearPointIsolated.getDistanceSquared())
                        ? nearPointOverall : nearPointIsolated;
            }
        }
        final Path selectedPath = nearPoint.getPath();
        tracerPlugin.selectPath(selectedPath, addToExistingSelection);
        canvasInfo(NodeEditCommand.shortName(selectedPath) + " selected");
        return selectedPath;
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
        if (editMode && !impossibleEdit(false))
            setCursor(handCursor);
    }

    protected void onAltKeyUp() {
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
                (tracerPlugin.getPrefs().getRequireShiftToFork()) ? e.isShiftDown() && e.isAltDown() : e.isAltDown()
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
        if (tracerPlugin.getPrefs().isCanvasAutoActivationEnabled()) imp.getWindow().toFront();
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
        if (altDraggingNode || paintStrokeActive || paintRadiusDragging) {
            if (paintRadiusDragging) paintCursorLabel = null; // clear transient radius label
            altDraggingNode = false;
            paintStrokeActive = false;
            paintRadiusDragging = false;
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
        if (!e.isControlDown() || isEventsDisabled() || !tracerPlugin.isUIready()
                || tracerPlugin.manualRadius == SNT.SCROLL_DIAMETER_DISABLED) {
            // Forward to parent (ImageWindow/StackWindow) for IJ's default zoom/scroll.
            // We consume first to suppress AWT's automatic propagation, which on Windows
            // would deliver the event twice (once via propagation, once via our call).
            // See https://github.com/morphonets/SNT/issues/271
            // NB: Direct method call avoids reentrancy and consumed-event filtering.
            // See https://forum.image.sc/t/119476/5
            e.consume();
            if (getParent() instanceof MouseWheelListener)
                ((MouseWheelListener) getParent()).mouseWheelMoved(e); // doesn't care about consumed flag
            return;
        }
        final int state = tracerPlugin.getUIState();
        final boolean validState = editMode || state == SNTUI.WAITING_TO_START_PATH || state == SNTUI.PARTIAL_PATH;
        if (!validState) {
            e.consume();
            if (getParent() instanceof MouseWheelListener)
                ((MouseWheelListener) getParent()).mouseWheelMoved(e);
            return;
        }
        e.consume(); // prevent IJ from also handling this Ctrl+wheel event

        // In paint mode, Ctrl+scroll adjusts brush radius instead of node radius
        if (paintMode) {
            final int delta = (e.getWheelRotation() < 0) ? 1 : -1; // scroll up: larger
            paintBrushRadius = Math.clamp(paintBrushRadius + delta, MIN_BRUSH_RADIUS, MAX_BRUSH_RADIUS);
            updatePaintModeLabel();
            // Show transient label below brush circle
            final Path ep = tracerPlugin.getEditingPath();
            if (ep != null && ep.size() >= 2) {
                final double avgSpacing = ep.getLength() / (ep.size() - 1);
                paintCursorLabel = String.format("r=%d (≈%.1f %s)",
                        paintBrushRadius, avgSpacing * paintBrushRadius, tracerPlugin.spacing_units);
            }
            paintBrushCursorVisible = true;
            if (paintBrushCursorTimer == null) {
                paintBrushCursorTimer = new javax.swing.Timer(800, ae -> {
                    paintBrushCursorVisible = false;
                    paintCursorLabel = null;
                    repaint();
                });
                paintBrushCursorTimer.setRepeats(false);
            }
            paintBrushCursorTimer.restart();
            repaint(); // refresh brush circle overlay
            return;
        }

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
        if (!scrollRadiusUndoPushed) {
            pushEditUndo();
            scrollRadiusUndoPushed = true;
        }
        // Reset the debounce timer so the flag clears ~600ms after the last scroll tick
        if (scrollRadiusUndoResetTimer == null) {
            scrollRadiusUndoResetTimer = new Timer(600, ae -> scrollRadiusUndoPushed = false);
            scrollRadiusUndoResetTimer.setRepeats(false);
        }
        scrollRadiusUndoResetTimer.restart();
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
            canvasWarning("No paths selected. Invalid selection ROI?");
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
        if (IJ.spaceBarDown()) {
            // Spacebar always pans, even mid-stroke
            super.mouseDragged(e);
            return;
        }
        if (editMode && (e.isAltDown() || paintMode) && !impossibleEdit(false)) {
            final Path editingPath = tracerPlugin.getEditingPath();
            if (!paintMode && editingPath.isEditableNodeLocked()) {
                if (!altDraggingNode) // show message only once, not on every drag event
                    canvasWarning("Node is locked. Unlock it first (L)");
                return;
            }
            // Guard: skip paint if path is not in the current viewport
            if (paintMode && !paintStrokeActive && !paintRadiusDragging
                    && !editingPath.containsUnscaledNodesInViewPort(this)) {
                canvasWarning("Path not in view. Pan back to resume painting.");
                return;
            }
            if (!altDraggingNode && !paintStrokeActive && !paintRadiusDragging) {
                // First drag event: snapshot before any movement
                pushEditUndo();
                if (paintMode) {
                    if (e.isAltDown()) paintRadiusDragging = true;
                    else paintStrokeActive = true;
                } else {
                    altDraggingNode = true;
                }
            }
            last_x_in_pane_precise = myOffScreenXD(e.getX());
            last_y_in_pane_precise = myOffScreenYD(e.getY());
            if (paintMode) {
                if (paintRadiusDragging)
                    brushBlendRadii(editingPath);
                else
                    brushMoveNodes(editingPath);
                repaint();
                tracerPlugin.notifyEditingPathChanged(); // Bdv/Bvv
            } else {
                moveEditingNodeToLastCanvasPosition(false, false); // silent: no tempMsg per drag event. Undo pushed once, above
            }
            return; // don't pan or do anything else
        }
        altDraggingNode = false;
        paintStrokeActive = false;
        paintRadiusDragging = false;
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
                canvasWarning("You must close the sigma palette to continue");
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
        tracerPlugin.getUI().launchSigmaPaletteAround(myOffScreenX(canvasX), myOffScreenY(canvasY),
                tracerPlugin.getImagePlus().getZ());
        restoreDefaultCursor();
    }

    private boolean impossibleEdit(final boolean displayError) {
        boolean invalid = !tracerPlugin.getPathAndFillManager().isSelected(tracerPlugin
                .getEditingPath());
        if (invalid && displayError) canvasWarning("Editing path not selected");
        if (!invalid) {
            invalid = (tracerPlugin.getEditingNode() == -1);
            if (invalid && displayError) canvasWarning("No node selected");
        }
        return invalid;
    }

    /**
     * Returns true (and optionally warns) if the current edit operation is
     * unsupported on fitted paths. Structural edits (insert, delete) and
     * tree-level operations (split, re-root, connect, tag) cannot meaningfully
     * propagate between fitted and unfitted representations, so they are
     * blocked when the path is displayed as fitted.
     */
    private boolean unsupportedOnFittedPath(final String operationLabel) {
        if (!tracerPlugin.isEditingFittedPath()) return false;
        tracerPlugin.showCanvasWarning(operationLabel + " not supported on fitted paths. Un-fit first.");
        return true;
    }

    private void redrawEditingPath(final String msg) {
        redrawEditingPath(getGraphics2D(getGraphics()));
        repaint();
        tracerPlugin.notifyEditingPathChanged(); // Bdv/Bvv
        if (msg != null) canvasInfo(msg);
    }

    private void canvasInfo(final String msg) {
        tracerPlugin.showCanvasInfo(msg);
    }

    private void canvasWarning(final String msg) {
        tracerPlugin.showCanvasWarning(msg);
    }

    private void redrawEditingPath(final Graphics2D g) {
        final Path editingPath = tracerPlugin.getEditingPath();
        // Draw the same variant the main render loop uses (fitted when
        // getUseFitted), so the editable-node highlight aligns with
        // what the user sees. The editableNodeIndex lives on editingPath
        // (always the main/unfitted path), so we temporarily propagate
        // it to the fitted copy for rendering, then clear it afterwards.
        final Path drawPath = editingPath.getVersionInUse();
        if (drawPath != editingPath) {
            drawPath.setEditableNode(editingPath.getEditableNodeIndex());
            drawPath.setEditableNodeLocked(editingPath.isEditableNodeLocked());
        }
        drawPath.drawPathAsPoints(g, this, tracerPlugin);
        if (drawPath != editingPath) {
            // Clear transient state so the main render loop doesn't
            // double-draw the highlight
            drawPath.setEditableNode(-1);
            drawPath.setEditableNodeLocked(false);
        }
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
            if (last_x_in_pane_precise != Double.MIN_VALUE) {
                if (paintRadiusDragging || paintBrushCursorVisible)
                    drawPaintBrushCursor(g);
                else if (!paintMode && tracerPlugin.manualRadius > 0)
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

        // Warning overlay (tiered by severity)
        drawWarningOverlay(g);
    }

    private void drawWarningOverlay(final Graphics2D g) {
        if (warningLocations.isEmpty() && warningAffectedPaths.isEmpty()) return;
        final long elapsed = System.currentTimeMillis() - warningFlashStart;
        if (elapsed >= FLASH_DURATION_MS) return;

        final Stroke savedStroke = g.getStroke();
        final Color savedColor = g.getColor();
        final Composite savedComposite = g.getComposite();

        // Pulsing alpha: 600ms cycle, sinusoidal
        final double phase = (elapsed % 600) / 600.0;
        final float pulseAlpha = (float) (0.4 + 0.6 * Math.abs(Math.sin(phase * Math.PI)));
        final Color baseColor = (warningMaxSeverity == PlausibilityCheck.Severity.ERROR)
                ? ERROR_RING_COLOR : WARNING_RING_COLOR;

        // Tier 1 (all severities): Affected path highlights (simplified thick lines)
        if (!warningAffectedPaths.isEmpty()) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pulseAlpha * 0.5f));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(baseColor);
            for (final Path path : warningAffectedPaths) {
                drawPathSimplified(g, path);
            }
        }

        // Tier 1 (all severities): Pulsing rings at warning locations
        if (!warningLocations.isEmpty()) {
            final int ringRadius = Math.max(10, (int) (12 * getMagnification() / 4));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pulseAlpha));
            // loc is a true-world coordinate; convert to this canvas's own local pixel grid the same way
            // Path#getXUnscaledDouble()/PathNodeCanvas do, or these rings render off the path they are meant to
            // highlight whenever activeCanvasPixelOffset is non-zero
            final PointInCanvas ringOffset = tracerPlugin.getActiveCanvasPixelOffset();
            for (final PointInImage loc : warningLocations) {
                final int sx = myScreenXD(loc.x / tracerPlugin.x_spacing + ringOffset.x);
                final int sy = myScreenYD(loc.y / tracerPlugin.y_spacing + ringOffset.y);
                // Dark outline ring
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(3f));
                g.drawOval(sx - ringRadius, sy - ringRadius, ringRadius * 2, ringRadius * 2);
                // Colored ring
                g.setColor(baseColor);
                g.setStroke(new BasicStroke(2f));
                g.drawOval(sx - ringRadius, sy - ringRadius, ringRadius * 2, ringRadius * 2);
            }
        }

        // Tier 3 (ERROR + panel not visible): Brief viewport tint
        if (warningViewportTint && elapsed < TINT_DURATION_MS) {
            final float tintAlpha = 1f - (float) elapsed / TINT_DURATION_MS; // fade out
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tintAlpha));
            g.setColor(VIEWPORT_TINT_COLOR);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        g.setStroke(savedStroke);
        g.setColor(savedColor);
        g.setComposite(savedComposite);
    }

    /** Draws a path as connected line segments without delegating to Path's own drawing logic. */
    private void drawPathSimplified(final Graphics2D g, final Path path) {
        if (path == null || path.size() < 2) return;
        final int sliceZeroIndexed = imp.getZ() - 1;
        final double xSpacing = tracerPlugin.x_spacing;
        final double ySpacing = tracerPlugin.y_spacing;
        final double zSpacing = tracerPlugin.z_spacing;
        // path.canvasOffset (not tracerPlugin.getActiveCanvasPixelOffset()): matches the convention
        // PathNodeCanvas#getScreenCoordinateX/Y uses for this same Path's normal rendering - the two
        // should be numerically identical for any path currently in pathAndFillManager, but using the
        // Path's own field keeps this correct even in the same edge cases that invariant protects against
        final PointInCanvas offset = path.canvasOffset;
        int prevSx = Integer.MIN_VALUE, prevSy = Integer.MIN_VALUE;
        for (int i = 0; i < path.size(); i++) {
            final PointInImage p = path.getNodeWithoutChecks(i);
            // Skip nodes far from current Z slice (tolerance: ±2 slices). sliceZeroIndexed is a local
            // canvas pixel index, so nodeSlice must be corrected by offset.z to compare against it
            final int nodeSlice = (int) Math.round(p.z / zSpacing + offset.z);
            if (just_near_slices && Math.abs(nodeSlice - sliceZeroIndexed) > Math.max(eitherSide, 2))
                continue;
            final int sx = myScreenXD(p.x / xSpacing + offset.x);
            final int sy = myScreenYD(p.y / ySpacing + offset.y);
            if (prevSx != Integer.MIN_VALUE) {
                g.drawLine(prevSx, prevSy, sx, sy);
            }
            prevSx = sx;
            prevSy = sy;
        }
    }

    /**
     * Draws a dashed circle around the cursor position. Shared by manual-radius
     * cursor (tracing/edit) and paint-brush cursor (paint mode).
     */
    private static final Font CURSOR_LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    private void drawDashedCircleCursor(final Graphics2D g, final double screenRadius,
                                        final Color color, final String cursorLabel) {
        final double sx = myScreenXDprecise(last_x_in_pane_precise);
        final double sy = myScreenYDprecise(last_y_in_pane_precise);

        final Stroke savedStroke = g.getStroke();
        final Color savedColor = g.getColor();
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[]{4f, 3f}, 0f)); // dashed
        g.setColor(color);
        g.draw(new Ellipse2D.Double(sx - screenRadius, sy - screenRadius, 2 * screenRadius, 2 * screenRadius));
        g.setStroke(savedStroke);
        if (cursorLabel != null) {
            // Draw label at fixed size, anchored below the circle
            final Font savedFont = g.getFont();
            g.setFont(CURSOR_LABEL_FONT);
            final FontMetrics fm = g.getFontMetrics();
            final float lx = (float) (sx - fm.stringWidth(cursorLabel) / 2.0);
            final float ly = (float) (sy + screenRadius + fm.getAscent() + 2);
            g.drawString(cursorLabel, lx, ly);
            g.setFont(savedFont);
        }
        g.setColor(savedColor);
    }

    /**
     * Draws two opposing dashed arcs (yoke-like brackets) around the cursor
     * position. Used by paint mode to visually distinguish the brush extent
     * from the full-circle radius indicator used during tracing.
     */
    private void drawDashedArcsCursor(final Graphics2D g, final double screenRadius,
                                      final Color color, final String cursorLabel) {
        final double sx = myScreenXDprecise(last_x_in_pane_precise);
        final double sy = myScreenYDprecise(last_y_in_pane_precise);
        final double d = 2 * screenRadius;

        final Stroke savedStroke = g.getStroke();
        final Color savedColor = g.getColor();
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[]{4f, 3f}, 0f));
        g.setColor(color);
        // Left arc: 120° centered at 180° (i.e., 120°–240°)
        g.draw(new Arc2D.Double(sx - screenRadius, sy - screenRadius, d, d, 120, 120, Arc2D.OPEN));
        // Right arc: 120° centered at 0° (i.e., -60°–60°)
        g.draw(new Arc2D.Double(sx - screenRadius, sy - screenRadius, d, d, -60, 120, Arc2D.OPEN));
        g.setStroke(savedStroke);
        if (cursorLabel != null) {
            final Font savedFont = g.getFont();
            g.setFont(CURSOR_LABEL_FONT);
            final FontMetrics fm = g.getFontMetrics();
            final float lx = (float) (sx - fm.stringWidth(cursorLabel) / 2.0);
            final float ly = (float) (sy + screenRadius + fm.getAscent() + 2);
            g.drawString(cursorLabel, lx, ly);
            g.setFont(savedFont);
        }
        g.setColor(savedColor);
    }

    private void drawManualRadiusCursor(final Graphics2D g) {
        final double mag = getMagnification();
        // Physical radius → screen pixels (use x-spacing for XY/XZ, z-spacing for ZY x-axis)
        final double screenRadius = switch (plane) {
            case MultiDThreePanes.ZY_PLANE -> tracerPlugin.manualRadius / tracerPlugin.z_spacing * mag;
            default ->                        tracerPlugin.manualRadius / tracerPlugin.x_spacing * mag;
        };
        drawDashedCircleCursor(g, screenRadius, getUnconfirmedPathColor(),
                String.format("⌀= %.3f %s", tracerPlugin.manualRadius * 2, tracerPlugin.spacing_units));
    }

    private void drawPaintBrushCursor(final Graphics2D g) {
        final Path editingPath = tracerPlugin.getEditingPath();
        if (editingPath == null || editingPath.size() < 2) return;
        // Estimate physical extent: average inter-node spacing × brush radius
        final double pathLength = editingPath.getLength();
        final double avgSpacing = pathLength / (editingPath.size() - 1);
        final double physicalRadius = avgSpacing * paintBrushRadius;
        final double mag = getMagnification();
        final double screenRadius = switch (plane) {
            case MultiDThreePanes.ZY_PLANE -> physicalRadius / tracerPlugin.z_spacing * mag;
            default ->                        physicalRadius / tracerPlugin.x_spacing * mag;
        };
        drawDashedArcsCursor(g, screenRadius, getAnnotationsColor(), paintCursorLabel);
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
        getGuiUtils().showKeyTip(tracerPlugin.getPrefs(), key, msg);

        timer.start();
    }

    protected void bookmarkCursorLocation() {
        if (tracerPlugin.isStreamMode()) return; // see QueueJumpingKeyListener.BookmarkCommand
        final int[] p = new int[3];
        tracerPlugin.findPointInStack((int) Math.round(last_x_in_pane_precise), (int) Math.round(last_y_in_pane_precise), plane, p);
        tracerPlugin.getUI().getBookmarkManager().add(p[0], p[1], p[2], getImage());
        if (!tracerPlugin.getUI().getBookmarkManager().isShowing())
            canvasInfo("Bookmark added");
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
        private static final String UNDO_LAST_SEGMENT = ContextMenuCommand.Labels.UNDO_SEGMENT;
        private static final String UNDO_LAST_EDIT = NodeEditCommand.UNDO_LABEL;
        private static final String FINISH_PATH = ContextMenuCommand.Labels.FINISH;
        private static final String HIDE_ALL = ContextMenuCommand.Labels.HIDE;
        private static final String SHOW_ARROWS = ContextMenuCommand.Labels.ORIENTATION;
        private static final String CLICK_AT_MAX = ContextMenuCommand.Labels.BRIGHTEST;
        private static final String FORK_NEAREST = ContextMenuCommand.Labels.FORK;
        private static final String BOOKMARK_CURSOR = ContextMenuCommand.Labels.BOOKMARK;
        private static final String SELECT_NEAREST = ContextMenuCommand.Labels.GRAB;
        private static final String APPEND_NEAREST = ContextMenuCommand.Labels.APPEND;
        private static final String EXTEND_SELECTED = ContextMenuCommand.Labels.EXTEND;
        private static final String PAUSE_SNT_TOGGLE = ContextMenuCommand.Labels.PAUSE_SNT;
        private static final String PAUSE_TRACING_TOGGLE = ContextMenuCommand.Labels.PAUSE_TRACING;
        private final static String INDENT = NodeEditCommand.MENU_INDENT;
        private final static String NODE_MOVE_Z = INDENT + "Bring Active Node to Current Z-plane";
        private final static String NODE_PAINT = INDENT + "Paint Mode";
        private final static String NODE_CONNECT_HELP = GuiUtils.MenuItems.CONNECT_HELP_LABEL;
        private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX = INDENT + "Connect to ";
        private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER = NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX
                + "...";
        private final static String START_SHOLL = ContextMenuCommand.Labels.SHOLL;
        private final static String SELECT_BY_ROI = ContextMenuCommand.Labels.LASSO;

        @Override
        public void itemStateChanged(final ItemEvent e) {
            if (e.getSource().equals(toggleEditModeMenuItem)) {
                enableEditMode(toggleEditModeMenuItem.getState());
            } else if (e.getSource().equals(togglePaintModeMenuItem)) {
                togglePaintMode();
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
                canvasWarning("Tracing functions currently disabled");
                return;
            }
            if (pathAndFillManager.size() == 0) {
                canvasWarning("There are no finished paths to extend");
                return;
            }
            if (!canvasReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
                canvasWarning("Please finish current operation before extending path");
                return;
            }
            final Path activePath = tracerPlugin.getSingleSelectedPath();
            if (activePath == null) {
                canvasWarning("No path selected. Please select a single path to be extended");
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
                    toggleKeyWarning("O", ContextMenuCommand.ORIENTATION_KEY_TIP);
                    return true;
                case HIDE_ALL:
                    toggleKeyWarning("H", ContextMenuCommand.HIDE_KEY_TIP);
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
                canvasWarning("Tracing functions currently disabled");
                return true; // handled, just failed
            }
            if (!canvasReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
                canvasWarning("Please finish current operation before creating branch");
                return true;
            }
            if (pathAndFillManager.size() == 0) {
                canvasWarning("There are no finished paths to branch out from");
                return true;
            }
            selectNearestPathToMousePointer(false);
            tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise, plane, true, true);
            // Verify a valid join point exists before committing. p[] is a pane-local pixel index;
            // correct against the active canvas offset before comparing to existing paths' true-world
            // node coordinates (see SNT#getActiveCanvasPixelOffset())
            final double[] p = new double[3];
            tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise, last_y_in_pane_precise, plane, p);
            final PointInCanvas forkOffset = tracerPlugin.getActiveCanvasPixelOffset();
            final PointInImage joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths(
                    p[0] - forkOffset.x, p[1] - forkOffset.y, p[2] - forkOffset.z);
            if (joinPoint == null) {
                canvasWarning("No fork point found. Move cursor closer to a path node");
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
            if (NODE_MOVE_Z.equals(command)) {
                assignLastCanvasZPositionToEditNode(true);
                return true;
            }
            final NodeEditCommand nec = NodeEditCommand.fromActionId(command);
            if (nec == null) return false; // Command not recognized as an edit command
            switch (nec) {
                case DELETE -> deleteEditingNode(true);
                case INSERT -> appendLastCanvasPositionToEditingNode(true);
                case MOVE -> moveEditingNodeToLastCanvasPosition(true);
                case LOCK -> toggleEditingNode(true);
                case RADIUS -> assignRadiusToEditingNode(true);
                case TAG -> assignColorToEditingNode();
                case SET_ROOT -> assignTreeRootToEditingNode(true);
                case SPLIT -> splitTreeAtEditingNode(true);
                case UNDO -> undoLastEditOperation();
            }
            return true;
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

    private static final String MANUAL_RADIUS_TEMP_KEY = "manualRadiusBeforeEdit";

    protected void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (editMode) {
            // Stash tracing-cursor radius and clear it so the circle doesn't carry over
            tracerPlugin.getPrefs().setTemp(MANUAL_RADIUS_TEMP_KEY,
                    String.valueOf(tracerPlugin.manualRadius));
            tracerPlugin.manualRadius = 0;
        } else {
            setPaintMode(false);
            altDraggingNode = false;
            // Restore tracing-cursor radius
            final String saved = tracerPlugin.getPrefs().getTemp(MANUAL_RADIUS_TEMP_KEY, "0");
            try { tracerPlugin.manualRadius = Double.parseDouble(saved); }
            catch (final NumberFormatException ignored) { tracerPlugin.manualRadius = 0; }
            scrollRadiusUndoPushed = false;
            if (scrollRadiusUndoResetTimer != null) scrollRadiusUndoResetTimer.stop();
        }
    }

    protected void togglePaintMode() {
        if (!editMode) {
            if (togglePaintModeMenuItem.isSelected() != paintMode)
                togglePaintModeMenuItem.setSelected(paintMode);
            return;
        }
        final Path editingPath = tracerPlugin.getEditingPath();
        if (editingPath == null || !tracerPlugin.getPathAndFillManager().isSelected(editingPath)) {
            canvasWarning("Editing path not selected");
            if (togglePaintModeMenuItem.isSelected() != paintMode)
                togglePaintModeMenuItem.setSelected(paintMode);
            return;
        }
        // Auto-select nearest node if none is active yet (e.g., user pressed P
        // right after Shift+E without moving the mouse over the path)
        if (editingPath.getEditableNodeIndex() == -1) {
            final Path matchPath = editingPath.getVersionInUse();
            int nearest = matchPath.indexNearestToCanvasPosition2D(
                    last_x_in_pane_precise, last_y_in_pane_precise, nodeDiameter());
            if (nearest == -1) nearest = 0; // fallback to first node
            editingPath.setEditableNode(nearest);
        }
        setPaintMode(!paintMode);
    }

    private boolean updatingPaintMode;

    private void setPaintMode(final boolean enable) {
        if (updatingPaintMode) return;
        updatingPaintMode = true;
        try {
            paintMode = enable;
            paintStrokeActive = false;
            paintRadiusDragging = false;
            paintBrushCursorVisible = false;
            paintCursorLabel = null;
            if (paintBrushCursorTimer != null) paintBrushCursorTimer.stop();
            if (togglePaintModeMenuItem.isSelected() != enable)
                togglePaintModeMenuItem.setSelected(enable);
            updatePaintModeLabel();
        } finally {
            updatingPaintMode = false;
        }
    }

    private void updatePaintModeLabel() {
        if (paintMode)
            tracerPlugin.setCanvasLabelAllPanes(PAINT_MODE_LABEL + " r=" + paintBrushRadius);
        else if (editMode)
            tracerPlugin.setCanvasLabelAllPanes(EDIT_MODE_LABEL);
        else
            tracerPlugin.setCanvasLabelAllPanes(null);
    }

    protected boolean isPaintMode() {
        return paintMode;
    }

    protected int getPaintBrushRadius() {
        return paintBrushRadius;
    }

    private PathNodeEditor editor() {
        return tracerPlugin.getNodeEditor();
    }

    /**
     * Reports the outcome of an operation of the shared node editor: redraws (and informs the Bdv/Bvv) on success,
     * warns on failure
     *
     * @param r the outcome
     * @param showMessage whether the message of a successful operation should be displayed
     */
    private void report(final PathNodeEditor.Result r, final boolean showMessage) {
        if (r == null) return;
        r.dispatch(res -> {
            // Paths were added/removed/replaced: the editing path is no longer valid. Leave Edit Mode on all
            // front-ends (canvas, Bdv and Bvv), as the viewers do (see BigViewerEditor#report)
            tracerPlugin.setEditingPath(null);
            tracerPlugin.updateAllViewers();
            tracerPlugin.exitEditMode();
            repaint();
            if (res.message() != null) {
                if (!res.ok()) getGuiUtils().error(res.message()); // failure after the path was released
                else if (showMessage) canvasInfo(res.message());
            }
        }, res -> redrawEditingPath((showMessage) ? res.message() : null), res -> {
            // Silent callers (e.g., node drags, which may fail on every mouse event) must not open dialogs
            if (res.message() == null) return;
            if (res.modal() && showMessage) getGuiUtils().error(res.message());
            else canvasWarning(res.message());
        });
    }

    /** @return the last canvas position, in the pixel grid of the nodes of {@code path} (i.e., without canvas offset) */
    private double[] lastCanvasPositionInPathPixels(final Path path) {
        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise, last_y_in_pane_precise, plane, p);
        final PointInCanvas offset = path.getCanvasOffset();
        return new double[] { p[0] - offset.x, p[1] - offset.y, p[2] - offset.z };
    }

    /** Records the editing path in the undo history shared with the Bdv/Bvv, see {@link PathNodeEditor#pushUndo()} */
    private void pushEditUndo() {
        if (impossibleEdit(false)) return;
        editor().pushUndo();
    }

    protected void undoLastEditOperation() {
        final PathNodeEditor.Result r = editor().undo();
        if (r.ok()) redrawEditingPath(r.message());
        else canvasWarning(r.message());
    }

    protected void toggleEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        report(editor().toggleLock(), true);
    }

    protected void deleteEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        report(editor().deleteNodeOrPath(getGuiUtils()), true);
    }

    protected void appendLastCanvasPositionToEditingNode(
            final boolean warnOnFailure)
    {
        if (impossibleEdit(warnOnFailure)) return;
        final double[] p = lastCanvasPositionInPathPixels(tracerPlugin.getEditingPath());
        report(editor().insertNode(p[0] * tracerPlugin.x_spacing, p[1] * tracerPlugin.y_spacing,
                p[2] * tracerPlugin.z_spacing), true);
    }

    protected void assignColorToEditingNode() {
        if (impossibleEdit(true)) return;
        if (unsupportedOnFittedPath("Tag node")) return;
        final PathNodeEditor ed = editor();
        final Path edPath = ed.getPath();
        final int edNode = ed.getActiveNode();
        // The active node follows the cursor while the popup is open: tagNode restores the one it was opened for
        final JPopupMenu popup = GuiUtils.MenuItems.colorTagPopup(this,
                chosen -> report(ed.tagNode(edPath, edNode, chosen), true));
        final Point p = MouseInfo.getPointerInfo().getLocation();
        SwingUtilities.convertPointFromScreen(p, this);
        popup.show(this, p.x, p.y);
    }

    protected void assignRadiusToEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        report(editor().promptAndSetRadius(getGuiUtils()), true);
    }

    /**
     * Moves nodes around the cursor using a brush with cosine-squared falloff.
     * The nearest node moves fully to the cursor position; flanking nodes within
     * {@code paintBrushRadius} are displaced proportionally.
     */
    private void brushMoveNodes(final Path editingPath) {
        // Resolve fitted path for nearest-node lookup (no distance threshold
        // so fast drags don't outrun the nodes and stall painting)
        final Path matchPath = editingPath.getVersionInUse();
        final int centerNode = matchPath.indexNearestToCanvasPosition2D(
                last_x_in_pane_precise, last_y_in_pane_precise, Double.MAX_VALUE);
        if (centerNode == -1 || centerNode >= editingPath.size()) return;
        editingPath.setEditableNode(centerNode);

        // Compute cursor position in world coordinates
        final double[] p = new double[3];
        tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
                last_y_in_pane_precise, plane, p);
        final PointInCanvas offset = editingPath.getCanvasOffset();
        final double cursorX = (p[0] - offset.x) * editingPath.x_spacing;
        final double cursorY = (p[1] - offset.y) * editingPath.y_spacing;
        final double cursorZ = (p[2] - offset.z) * editingPath.z_spacing;

        // Displacement vector from the center node's current position
        final PointInImage centerPos = editingPath.getNodeWithoutChecks(centerNode);
        final double dx = cursorX - centerPos.x;
        final double dy = cursorY - centerPos.y;
        final double dz = cursorZ - centerPos.z;

        editingPath.brushMove(centerNode, paintBrushRadius, dx, dy, dz);
    }

    private void brushBlendRadii(final Path editingPath) {
        final Path matchPath = editingPath.getVersionInUse();
        final int centerNode = matchPath.indexNearestToCanvasPosition2D(
                last_x_in_pane_precise, last_y_in_pane_precise, Double.MAX_VALUE);
        if (centerNode == -1 || centerNode >= editingPath.size()) return;
        editingPath.setEditableNode(centerNode);
        editingPath.brushBlendRadii(centerNode, paintBrushRadius);
        // Show center node radius as label below the brush circle
        final double centerRadius = editingPath.getNodeRadius(centerNode);
        paintCursorLabel = String.format("r=%.2f %s", centerRadius, tracerPlugin.spacing_units);
    }

    protected void moveEditingNodeToLastCanvasPosition(final boolean warnOnFailure) {
        moveEditingNodeToLastCanvasPosition(warnOnFailure, true);
    }

    /**
     * @param warnOnFailure whether to warn if there is no active node, and to display a message on success
     * @param recordUndo false if the caller has already recorded the undo history for the gesture (e.g., a drag)
     */
    protected void moveEditingNodeToLastCanvasPosition(final boolean warnOnFailure, final boolean recordUndo) {
        if (impossibleEdit(warnOnFailure)) return;
        final Path editingPath = tracerPlugin.getEditingPath();
        final double[] p = lastCanvasPositionInPathPixels(editingPath);
        report(editor().moveNode(p[0] * editingPath.x_spacing, p[1] * editingPath.y_spacing,
                p[2] * editingPath.z_spacing, recordUndo), warnOnFailure);
    }

    protected void assignLastCanvasZPositionToEditNode(
            final boolean warnOnFailure)
    {
        if (impossibleEdit(warnOnFailure)) return;
        final Path editingPath = tracerPlugin.getEditingPath();
        final int editingNode = editingPath.getEditableNodeIndex();
        final PointInCanvas offset = editingPath.getCanvasOffset();
        final PointInImage currentNode = editingPath.getNodeWithoutChecks(editingNode);
        final double newZ = switch (plane) {
            case MultiDThreePanes.XY_PLANE -> (imp.getZ() - 1 - offset.z) * tracerPlugin.z_spacing;
            case MultiDThreePanes.XZ_PLANE -> (last_y_in_pane_precise - offset.y) * tracerPlugin.y_spacing;
            case MultiDThreePanes.ZY_PLANE -> (last_x_in_pane_precise - offset.x) * tracerPlugin.x_spacing;
            default -> currentNode.z;
        };
        // Undo history, validity checks and plausibility monitoring are handled by the shared editor
        final PathNodeEditor.Result r = editor().moveNode(currentNode.x, currentNode.y, newZ);
        if (r.ok()) redrawEditingPath(String.format("Node %d moved to Z=%3f", editingNode, newZ));
        else canvasWarning("Adjustment of Z-position failed!");
    }

    protected void assignTreeRootToEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        report(editor().setActiveNodeAsRoot(getGuiUtils()), true);
    }

    protected void splitTreeAtEditingNode(final boolean warnOnFailure) {
        if (impossibleEdit(warnOnFailure)) return;
        report(editor().splitTreeAtActiveNode(getGuiUtils()), true);
    }

    public void setLookAndFeel(final String lookAndFeelName) {
        GuiUtils.LAF.setLookAndFeel(lookAndFeelName, false, pMenu);
    }
}
