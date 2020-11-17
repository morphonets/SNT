/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import ij.ImagePlus;
import ij.measure.Calibration;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SWCPoint;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

class InteractiveTracerCanvas extends TracerCanvas {

	private static final long serialVersionUID = 1L;
	private final SNT tracerPlugin;
	private JPopupMenu pMenu;
	private JCheckBoxMenuItem toggleEditModeMenuItem;
	private JMenuItem extendPathMenuItem;
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

	protected InteractiveTracerCanvas(final ImagePlus imp,
	                                  final SNT plugin, final int plane,
	                                  final PathAndFillManager pathAndFillManager)
	{
		super(imp, plugin, plane, pathAndFillManager);
		tracerPlugin = plugin;
		buildPpupMenu();
		super.disablePopupMenu(true); // so that handlePopupMenu is not triggered
	}

	private void buildPpupMenu() {
		pMenu = new JPopupMenu();
		pMenu.setLightWeightPopupEnabled(false); // Required because we are mixing
																							// lightweight and heavyweight
		// components?
		final AListener listener = new AListener();
		pMenu.add(menuItem(AListener.SELECT_NEAREST, listener));
		pMenu.add(menuItem(AListener.FORK_NEAREST, listener));
		extendPathMenuItem = menuItem(AListener.EXTEND_SELECTED, listener);
		pMenu.add(extendPathMenuItem);
		pMenu.addSeparator();

		toggleEditModeMenuItem = new JCheckBoxMenuItem(AListener.EDIT_TOGGLE);
		toggleEditModeMenuItem.addItemListener(listener);
		pMenu.add(toggleEditModeMenuItem);
		pMenu.add(menuItem(AListener.NODE_RESET, listener));
		pMenu.add(menuItem(AListener.NODE_DELETE, listener));
		pMenu.add(menuItem(AListener.NODE_INSERT, listener));
		pMenu.add(menuItem(AListener.NODE_MOVE, listener));
		pMenu.add(menuItem(AListener.NODE_MOVE_Z, listener));

		pMenu.addSeparator();
		pMenu.add(menuItem(AListener.NODE_SET_ROOT, listener));
		pMenu.addSeparator();

		connectToSecondaryEditingPath = menuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER, listener);
		pMenu.add(connectToSecondaryEditingPath);
		pMenu.add(helpOnConnectingMenuItem());
		pMenu.addSeparator();

		togglePauseSNTMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_SNT_TOGGLE);
		togglePauseSNTMenuItem.addItemListener(listener);
		pMenu.add(togglePauseSNTMenuItem);
		togglePauseTracingMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_TRACING_TOGGLE);
		togglePauseTracingMenuItem.addItemListener(listener);
		pMenu.add(togglePauseTracingMenuItem);
		pMenu.addSeparator();
		pMenu.add(menuItem(listener.START_SHOLL, listener));
	}

	private void showPopupMenu(final int x, final int y) {
		final Path activePath = tracerPlugin.getSingleSelectedPath();
		final boolean be = uiReadyForModeChange(SNTUI.EDITING);
		extendPathMenuItem.setText((activePath != null) ? "Continue Extending " + activePath
				.getName() : AListener.EXTEND_SELECTED);
		extendPathMenuItem.setEnabled(!(editMode || tracerPlugin.tracingHalted));
		toggleEditModeMenuItem.setEnabled(be);
		toggleEditModeMenuItem.setState(be && editMode);
		toggleEditModeMenuItem.setText((activePath != null) ? "Edit " + activePath
			.getName() : AListener.EDIT_TOGGLE);
		final boolean bp = uiReadyForModeChange(SNTUI.SNT_PAUSED);
		togglePauseSNTMenuItem.setEnabled(bp);
		togglePauseSNTMenuItem.setSelected(bp && tracerPlugin
			.getUIState() == SNTUI.SNT_PAUSED);
		togglePauseTracingMenuItem.setEnabled(!togglePauseSNTMenuItem.isSelected());
		togglePauseTracingMenuItem.setEnabled(bp);
		togglePauseTracingMenuItem.setSelected(tracerPlugin.tracingHalted);

		// Disable editing commands
		for (final MenuElement me : pMenu.getSubElements()) {
			if (me instanceof JMenuItem) {
				final JMenuItem mItem = ((JMenuItem) me);
				final String cmd = mItem.getActionCommand();

				if (cmd.startsWith(AListener.FORK_NEAREST)) {
					mItem.setText((tracerPlugin.requireShiftToFork) ? AListener.FORK_NEAREST_SHIFT_ALT
							: AListener.FORK_NEAREST_ALT);
				}

				if (togglePauseSNTMenuItem.isSelected() && !cmd.equals(
					AListener.PAUSE_SNT_TOGGLE))
				{
					mItem.setEnabled(false);
				}
				// commands only enabled in "Edit Mode"
				else if (cmd.equals(AListener.NODE_RESET) || cmd.equals(
					AListener.NODE_DELETE) || cmd.equals(AListener.NODE_INSERT) || cmd
						.equals(AListener.NODE_MOVE) || cmd.equals(AListener.NODE_MOVE_Z) || cmd
						.equals(AListener.NODE_SET_ROOT)  || 
						cmd.startsWith(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX))
				{
					mItem.setEnabled(be && editMode);
				}
				else {
					mItem.setEnabled(true);
				}

			}
		}

		updateConnectToSecondaryEditingPathMenuItem();
		pMenu.show(this, x, y);
	}

	protected void connectEditingPathToPreviousEditingPath() {
		updateConnectToSecondaryEditingPathMenuItem();
		if (!connectToSecondaryEditingPath.isEnabled()) {
			getGuiUtils().error(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + ": No connectable node exist.",
					"No Connectable Nodes");
			updateConnectToSecondaryEditingPathMenuItem();
			return;
		}
		final Path source = tracerPlugin.getEditingPath();
		final boolean startJoins = (source.getEditableNodeIndex() <= source.size() / 2);
		connectToEditingPath(source, tracerPlugin.getPreviousEditingPath(), startJoins);
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
		final String label = tracerPlugin.getPreviousEditingPath().getName() + " (node " + tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() +")";
		connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + label + 
				AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_SUFFIX );
	}


	private void connectToEditingPath(final Path source, final Path destination, final boolean startJoins) {
		final int nodeIndexP = destination.getEditableNodeIndex();
		final int nodeIndexS = source.getEditableNodeIndex();
		if (nodeIndexP * nodeIndexS != 0) {
			getGuiUtils().error("Cannot connect selected nodes: A loop would be created!",
					"Reconstruction Cannot Contain Loops");
			return;
		}
		final PointInImage tPim = destination.getNodeWithoutChecks(nodeIndexP);
		source.moveNode(source.getEditableNodeIndex(), tPim);
		if (startJoins) {
			if (source.getStartJoins() != null)
				source.unsetStartJoin();
			source.setStartJoin(destination, tPim);
		} else {
			if (source.getEndJoins() != null)
				source.unsetEndJoin();
			source.setEndJoin(destination, tPim);
		}
		destination.setEditableNode(-1);
		pathAndFillManager.resetListeners(null);
		tracerPlugin.updateAllViewers();
	}

	private JMenuItem helpOnConnectingMenuItem() {
		final String msg = "<HTML>To connect two paths in <i>Edit Mode</i>:<ol>" +
			"  <li>Select the source node on the path being edited by hovering over it</li>" +
			"  <li>Activate the 2nd path by pressing 'G'. Select the destination node on it</li>" +
			"  <li>Link the two by pressing 'C' (<u>C</u>onnect To... command)</li>" +
			"</ol>" +
			"Note that loop-forming connections are not allowed.";
		final GuiUtils guiUtils = new GuiUtils(this.getParent());
		final JMenuItem helpItem = new JMenuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + "Help...");
		helpItem.addActionListener(e -> guiUtils.centeredMsg(msg, AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + "Help"));
		return helpItem;
	}

	private JMenuItem menuItem(final String cmdName, final ActionListener lstnr) {
		final JMenuItem mi = new JMenuItem(cmdName);
		mi.addActionListener(lstnr);
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

	public void toggleJustNearSlices() {
		just_near_slices = !just_near_slices;
	}

	protected void fakeMouseMoved(final boolean shift_pressed,
		final boolean join_modifier_pressed)
	{
		tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
			plane, shift_pressed, join_modifier_pressed);
	}

	public void clickAtMaxPoint() {
		final int x = (int) Math.round(last_x_in_pane_precise);
		final int y = (int) Math.round(last_y_in_pane_precise);
		final int[] p = new int[3];
		tracerPlugin.findPointInStack(x, y, plane, p);
		SNTUtils.log("Clicking on x=" + x + " y= " + y + "on pane " + plane +
			" which corresponds to image position x=" + p[0] + ", y=" + p[1] + " z=" +
			p[2]);
		tracerPlugin.clickAtMaxPoint(x, y, plane);
		tracerPlugin.setZPositionAllPanes(p[0], p[1], p[2]);
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

	public void selectNearestPathToMousePointerOld(
		final boolean addToExistingSelection)
	{
		if (pathAndFillManager.size() == 0) {
			getGuiUtils().tempMsg("Nothing to select: There are no traced paths");
			return;
		}
		final NearPoint nearPoint = getNearPointToMousePointer();
		if (nearPoint == null) {
			getGuiUtils().tempMsg("No selectable paths in view");
		}
		else {
			tracerPlugin.selectPath(nearPoint.getPath(), addToExistingSelection);
			getGuiUtils().tempMsg(nearPoint.getPath().getName() + " selected");
		}
	}

	public void selectNearestPathToMousePointer(
			final boolean addToExistingSelection)
	{
		if (pathAndFillManager.size() == 0) {
			getGuiUtils().tempMsg("Nothing to select: There are no traced paths");
			return;
		}

		List<PointInCanvas> nodes = new ArrayList<>();
		for (final Path path : pathAndFillManager.getPaths()) {
			if (!path.isSelected()) {
				nodes.addAll(path.getUnscaledNodesInViewPort(this));
			}
		}		
		if (nodes.isEmpty()) {
			getGuiUtils().tempMsg("Nothing to select. No paths in view");
			return;
		}

		final double[] p = new double[3];
		tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
			last_y_in_pane_precise, plane, p);
		PointInCanvas cursor = new PointInCanvas(p[0], p[1], 0);

		final NearPointInCanvas nearPoint = NearPointInCanvas.nearestPointInCanvas(nodes, cursor);
		if (nearPoint == null) {
			getGuiUtils().tempMsg("No selectable paths in view");
		}
		else {
			tracerPlugin.selectPath(nearPoint.getPath(), addToExistingSelection);
			getGuiUtils().tempMsg(nearPoint.getPath().getName() + " selected");
		}
	}

	private NearPoint getNearPointToMousePointer() {

		if (pathAndFillManager.size() == 0) {
			return null;
		}

		System.out.println(last_x_in_pane_precise + ", " + last_y_in_pane_precise);
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
		System.out.println(SNTUtils.formatDouble(last_x_in_pane_precise, 2) + ", " +
			SNTUtils.formatDouble(last_y_in_pane_precise, 2) + " | dx:" + maxSquaredLength);

		// Find the nearest point on unselected Paths currently displayed in
		// viewPort
		final List<Path> paths = pathAndFillManager
			.getUnSelectedPathsRenderedInViewPort(this);
		if (paths.isEmpty()) {
			return null;
		}
		cursor.z = Double.NaN; // ignore Z-positioning of path nodes
		final NearPoint np = pathAndFillManager.nearestPointOnAnyPath(paths, cursor,
			maxSquaredLength, true);
		if (np == null) {
			return null;
		}
		return np;
	}

	@Override
	public void setCursor(final int sx, final int sy, final int ox,
		final int oy)
	{
		if (isEventsDisabled() || !tracerPlugin.isUIready() || !cursorLocked)
			super.setCursor(sx, sy, ox, oy);
	}

	@Override
	public void mouseMoved(final MouseEvent e) {

		super.mouseMoved(e);
		if (isEventsDisabled() || !tracerPlugin.isUIready()) return;

		last_x_in_pane_precise = myOffScreenXD(e.getX());
		last_y_in_pane_precise = myOffScreenYD(e.getY());

		boolean shift_key_down = e.isShiftDown();
		final boolean joiner_modifier_down = (tracerPlugin.requireShiftToFork) ? e.isShiftDown() && e.isAltDown() : e.isAltDown();

		if (!editMode && tracerPlugin.snapCursor &&
			plane == MultiDThreePanes.XY_PLANE && !joiner_modifier_down &&
			!shift_key_down && tracerPlugin.accessToValidImageData())
		{
			final double[] p = new double[3];
			tracerPlugin.findSnappingPointInXYview(last_x_in_pane_precise,
				last_y_in_pane_precise, p);
			last_x_in_pane_precise = p[0];
			last_y_in_pane_precise = p[1];
			// Always sync panes if Z-snapping is enabled
			shift_key_down = tracerPlugin.cursorSnapWindowZ > 0;
		}

		tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
			plane, shift_key_down, joiner_modifier_down);
		if (editMode) {
			setCursor((tracerPlugin.getEditingNode() == -1) ? defaultCursor
				: handCursor);
		}
		else {
			setCursor(crosshairCursor);
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
			.getModifiers() & Event.META_MASK) != 0));
	}

	@Override
	public void mousePressed(final MouseEvent me) {
		if (isPopupTrigger(me)) {
			showPopupMenu(me.getX(), me.getY());
			me.consume();
			return;
		}
		if (tracerPlugin.panMode || isEventsDisabled() || !tracerPlugin
			.isUIready())
		{
			super.mousePressed(me);
			return;
		}
	}

	@Override
	public void mouseReleased(final MouseEvent me) {
		if (tracerPlugin.panMode || isEventsDisabled()) {
			super.mouseReleased(me);
			return;
		}
	}

	@Override
	public void mouseClicked(final MouseEvent e) {

		if (pMenu.isShowing() || tracerPlugin.panMode || isEventsDisabled() ||
			isPopupTrigger(e))
		{
			return;
		}

		switch (tracerPlugin.getUI().getState()) {

			case SNTUI.LOADING:
			case SNTUI.SAVING:
			case SNTUI.TRACING_PAUSED:
				return; // Do nothing
			case SNTUI.EDITING:
				impossibleEdit(true);
				break;
			case SNTUI.WAITING_FOR_SIGMA_POINT_I:
			case SNTUI.WAITING_FOR_SIGMA_POINT_II:
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
		boolean invalid = !tracerPlugin.pathAndFillManager.isSelected(tracerPlugin
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
		SwingUtilities.invokeLater(() -> {
			getGuiUtils().tempMsg(msg);
		});
	}

	private void redrawEditingPath(final Graphics2D g) {
		tracerPlugin.getEditingPath().drawPathAsPoints(g, this, tracerPlugin);
	}

	@Override
	protected void drawOverlay(final Graphics2D g) {

		if (tracerPlugin.loading) return;

		final boolean drawDiametersXY = tracerPlugin.getDrawDiametersXY();
		final int sliceZeroIndexed = imp.getZ() - 1;
		int eitherSideParameter = eitherSide;
		if (!just_near_slices) eitherSideParameter = -1;

		final FillerThread filler = tracerPlugin.filler;
		if (filler != null) {
			filler.setDrawingColors(getFillColor(), getFillColor());
			filler.setDrawingThreshold(filler.getThreshold());
		}

		super.drawOverlay(g); // draw all paths, crosshair, etc.

		if (editMode && tracerPlugin.getEditingPath() != null) {
			redrawEditingPath(g);
			return; // no need to proceed: only editing path has been updated
		}

		// Now render temporary/incomplete paths
		final double spotDiameter = 2 * nodeDiameter();

		if (unconfirmedSegment != null) {
			unconfirmedSegment.drawPathAsPoints(this, g, getUnconfirmedPathColor(),
				plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);
			if (unconfirmedSegment.endJoins != null) {
				final PathNode pn = new PathNode(unconfirmedSegment, unconfirmedSegment
					.size() - 1, this);
				pn.setSize(spotDiameter);
				pn.draw(g, getUnconfirmedPathColor());
			}
		}

		final Path currentPathFromTracer = tracerPlugin.getCurrentPath();

		if (currentPathFromTracer != null) {
			currentPathFromTracer.drawPathAsPoints(this, g, getTemporaryPathColor(),
				plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);

			if (lastPathUnfinished && currentPath.size() == 0) { // first point in
																														// path
				final PointInImage p = new PointInImage(
					tracerPlugin.last_start_point_x * tracerPlugin.x_spacing,
					tracerPlugin.last_start_point_y * tracerPlugin.y_spacing,
					tracerPlugin.last_start_point_z * tracerPlugin.z_spacing);
				p.onPath = currentPath;
				final PathNode pn = new PathNode(p, this);
				pn.setSize(spotDiameter);
				pn.draw(g, getUnconfirmedPathColor());
			}
		}

	}

	private void enableEditMode(final boolean enable) {
		if (enable && !tracerPlugin.editModeAllowed(true)) return;
		tracerPlugin.enableEditMode(enable);
	}

	public void setTemporaryPathColor(final Color color) {
		this.temporaryColor = color;
	}

	public void setUnconfirmedPathColor(final Color color) {
		this.unconfirmedColor = color;
	}

	public void setFillColor(final Color color) {
		this.fillColor = color;
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

	/**
	 * This class implements ActionListeners for
	 * InteractiveTracerCanvas's contextual menu.
	 */
	private class AListener implements ActionListener, ItemListener {

		/* Listed shortcuts are specified in QueueJumpingKeyListener */
		public static final String FORK_NEAREST = 
			"Fork at Nearest Node  ";
		public static final String FORK_NEAREST_SHIFT_ALT = 
			FORK_NEAREST + "[Shift+Alt+Click]";
		public static final String FORK_NEAREST_ALT = 
			FORK_NEAREST + "[Alt+Click]";
		public static final String SELECT_NEAREST =
			"Select Nearest Path  [G] [Shift+G]";
		public static final String EXTEND_SELECTED = "Continue Extending Path";
		public static final String PAUSE_SNT_TOGGLE = "Pause SNT";
		public static final String PAUSE_TRACING_TOGGLE = "Pause Tracing";
		public static final String EDIT_TOGGLE = "Edit Path";
		private final static String NODE_RESET = "  Reset Active Node";
		private final static String NODE_DELETE =
			"  Delete Active Node  [D, Backspace]";
		private final static String NODE_INSERT =
			"  Insert New Node at Cursor Position  [I]";
		private final static String NODE_MOVE =
			"  Move Active Node to Cursor Position  [M]";
		private final static String NODE_MOVE_Z =
			"  Bring Active Node to Current Z-plane  [B]";
		private final static String NODE_SET_ROOT = "  Set Active Node as Tree Root...";
		private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX = "  Connect to ";
		private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_SUFFIX = "  [C]";
		private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER = NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX
				+ "Unselected Crosshair Node" + AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_SUFFIX;
		private final String START_SHOLL = "Sholl Analysis at Nearest Node  [Shift+Alt+A]";

		@Override
		public void itemStateChanged(final ItemEvent e) {
			if (e.getSource().equals(toggleEditModeMenuItem)) {
				enableEditMode(toggleEditModeMenuItem.getState());
			}
			else if (e.getSource().equals(togglePauseSNTMenuItem)) {
				tracerPlugin.pause(togglePauseSNTMenuItem.isSelected());
			}
			else if (e.getSource().equals(togglePauseTracingMenuItem)) {
				tracerPlugin.pauseTracing(togglePauseTracingMenuItem.isSelected(), true);
			}
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			if (e.getSource() == extendPathMenuItem) {

				if (tracerPlugin.tracingHalted) {
					getGuiUtils().tempMsg(
						"Tracing functions currently disabled");
					return;
				}
				else if (pathAndFillManager.size() == 0) {
					getGuiUtils().tempMsg(
						"There are no finished paths to extend");
					return;
				}
				else if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
					getGuiUtils().tempMsg(
						"Please finish current operation before extending path");
					return;
				}
				final Path activePath = tracerPlugin.getSingleSelectedPath();
				if (activePath == null) {
					getGuiUtils().tempMsg(
						"No path selected. Please select a single path to be extended");
					return;
				}
				tracerPlugin.replaceCurrentPath(activePath);
				return;

			}
			else if (e.getActionCommand().startsWith(FORK_NEAREST)) {

				if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
					getGuiUtils().tempMsg(
						"Please finish current operation before creating branch");
					return;
				}
				else if (pathAndFillManager.size() == 0) {
					getGuiUtils().tempMsg(
						"There are no finished paths to branch out from");
					return;
				}
				selectNearestPathToMousePointer(false);
				tracerPlugin.mouseMovedTo(last_x_in_pane_precise,
					last_y_in_pane_precise, plane, true, true);
				tracerPlugin.clickForTrace(last_x_in_pane_precise,
					last_y_in_pane_precise, plane, true);
				return;

			}
			else if (e.getActionCommand().equals(SELECT_NEAREST)) {
				final boolean add = ((e.getModifiers() & ActionEvent.SHIFT_MASK) > 0);
				selectNearestPathToMousePointer(add);
				return;
			}
			else if (e.getActionCommand().equals(START_SHOLL)) {
				if (pathAndFillManager.size() == 0) {
					getGuiUtils().error("There are no traced paths.");
					return;
				}
				startShollAnalysis();
				return;
			}
			else if (impossibleEdit(true)) return;

			// EDIT Commands below
			if (e.getActionCommand().startsWith(NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX)) {
				connectEditingPathToPreviousEditingPath();
				return;
			}
			switch (e.getActionCommand()) {
				case NODE_RESET:
					tracerPlugin.getEditingPath().setEditableNode(-1);
					break;
				case NODE_DELETE:
					deleteEditingNode(true);
					break;
				case NODE_INSERT:
					appendLastCanvasPositionToEditingNode(true);
					break;
				case NODE_MOVE:
					moveEditingNodeToLastCanvasPosition(true);
					break;
				case NODE_MOVE_Z:
					assignLastCanvasZPositionToEditNode(true);
					break;
				case NODE_SET_ROOT:
					assignTreeRootToEditingNode(true);
					break;
				default:
					SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
					return;
			}

		}
	}

	private GuiUtils getGuiUtils() {
		if (guiUtils == null)
			guiUtils = new GuiUtils(getParent());
		return guiUtils;
	}

	protected boolean isEditMode() {
		return editMode;
	}

	protected void setEditMode(final boolean editMode) {
		this.editMode = editMode;
	}

	protected void deleteEditingNode(final boolean warnOnFailure) {
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		if (editingPath.size() > 1) {
			try {
				editingPath.removeNode(editingPath.getEditableNodeIndex());
				redrawEditingPath("Node deleted");
			}
			catch (final IllegalArgumentException exc) {
				tempMsg("Node deletion failed!");
			}
		}
		else if (new GuiUtils(this.getParent()).getConfirmation("Delete " +
			editingPath + "?", "Delete Single-Point Path?"))
		{
			tracerPlugin.getPathAndFillManager().deletePath(editingPath);
			tracerPlugin.detectEditingPath();
			tracerPlugin.updateAllViewers();
		}
	}

	protected void appendLastCanvasPositionToEditingNode(
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
			editingPath.insertNode(editingNode, new PointInImage((p[0] - offset.x) *
				tracerPlugin.x_spacing, (p[1] - offset.y) * tracerPlugin.y_spacing,
				(p[2] - offset.z) * tracerPlugin.z_spacing));
			editingPath.setEditableNode(editingNode + 1);
			redrawEditingPath("New node inserted (N=" + editingNode + ")");
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Node insertion failed!");
		}
		return;
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
			redrawEditingPath("Node moved");
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Node displacement failed!");
		}
		return;
	}

	protected void assignLastCanvasZPositionToEditNode(
		final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		final int editingNode = editingPath.getEditableNodeIndex();
		final PointInCanvas offset = editingPath.getCanvasOffset();
		double newZ;
		switch (plane) {
			case MultiDThreePanes.XY_PLANE:
				newZ = (imp.getZ() - 1 - offset.z) * tracerPlugin.z_spacing;
				break;
			case MultiDThreePanes.XZ_PLANE:
				newZ = (last_y_in_pane_precise - offset.y) * tracerPlugin.y_spacing;
				break;
			case MultiDThreePanes.ZY_PLANE:
				newZ = (last_x_in_pane_precise - offset.x) * tracerPlugin.x_spacing;
				break;
			default:
				newZ = editingPath.precise_z_positions[editingNode];
				break;
		}
		try {
			editingPath.moveNode(editingNode, new PointInImage(
				editingPath.precise_x_positions[editingNode],
				editingPath.precise_y_positions[editingNode], newZ));
			redrawEditingPath("Node " + editingNode + "moved to Z=" + SNTUtils
				.formatDouble(newZ, 3));
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Adjustment of Z-position failed!");
		}
	}

	protected void assignTreeRootToEditingNode(
			final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		if (!guiUtils.getConfirmation(" <HTML><div WIDTH=600><p><b>Warning</b>: This destructive operation will " +
				"replace the Tree (i.e., single rooted structure) associated with the active node.</p> " +
				"<p>All color, metadata and morphometry tags and existing fits associated with the current Tree " +
				"will be discarded.</p>", "Confirm root change?")) {
			return;
		}
		final Path editingPath = tracerPlugin.getEditingPath();
		final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
		final int treeID = editingPath.getTreeID();
		final Tree editingTree = new Tree();
		for (final Path p : pathAndFillManager.getPaths()) {
			if (treeID == p.getTreeID()) {
				editingTree.add(p);
			}
		}
		final DirectedWeightedGraph editingGraph = editingTree.getGraph();
		/* FIXME:
		    currently, there is no efficient way to map a PointInImage to its corresponding SWCPoint in the graph.
		    Workaround is to find the SWCPoint in the graph closest to the query PointInImage.
		    This is likely to be slow with large or highly dense reconstructions. */
		SWCPoint editingSWCPoint = null;
		double minDist = Double.MAX_VALUE;
		for (final SWCPoint p : editingGraph.vertexSet()) {
			double d = p.distanceTo(editingNode);
			if (d < minDist) {
				minDist = d;
				editingSWCPoint = p;
			}
		}
		editingGraph.setRoot(editingSWCPoint);
		final Tree newTree = editingGraph.getTree(true);
		enableEditMode(false);
		final Calibration cal = tracerPlugin.getImagePlus().getCalibration(); // snt the instance of the plugin
		newTree.list().forEach(p -> p.setSpacing(cal));
		pathAndFillManager.deletePaths(editingTree.list());
		pathAndFillManager.addTree(newTree);
	}

}
