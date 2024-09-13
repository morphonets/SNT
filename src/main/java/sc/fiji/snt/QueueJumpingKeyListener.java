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

package sc.fiji.snt;

import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.SwingUtilities;

import org.scijava.vecmath.Point3d;

import ij.gui.Toolbar;
import ij3d.Content;
import ij3d.DefaultUniverse;
import ij3d.Image3DUniverse;
import ij3d.behaviors.InteractiveBehavior;
import ij3d.behaviors.Picker;
import sc.fiji.snt.gui.GuiUtils;

class QueueJumpingKeyListener implements KeyListener {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final int CTRL_CMD_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
	private final SNT tracerPlugin;
	private final InteractiveTracerCanvas canvas;
	private final Image3DUniverse univ;

	private final ArrayList<KeyListener> listeners = new ArrayList<>();

	private static final int DOUBLE_PRESS_INTERVAL = 300; // ms
	private long timeKeyDown = 0; // last time key was pressed
	private int lastKeyPressedCode;

	/* Define which keys are always parsed by IJ listeners */
	private static final int[] W_KEYS = new int[] { //
			// Zoom keys
			KeyEvent.VK_EQUALS, KeyEvent.VK_MINUS, KeyEvent.VK_UP, KeyEvent.VK_DOWN, //
			// Stack navigation keys
			KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, //
			// Extra navigation/zoom keys
			KeyEvent.VK_PLUS, KeyEvent.VK_LESS, KeyEvent.VK_GREATER, KeyEvent.VK_TAB };

	public QueueJumpingKeyListener(final SNT tracerPlugin,
		final InteractiveTracerCanvas canvas)
	{
		this.tracerPlugin = tracerPlugin;
		this.canvas = canvas;
		univ = null;
	}

	public QueueJumpingKeyListener(final SNT tracerPlugin,
		final Image3DUniverse univ)
	{
		this.tracerPlugin = tracerPlugin;
		this.canvas = null;
		this.univ = univ;
		univ.addInteractiveBehavior(new PointSelectionBehavior(univ));
	}

	@Override
	public void keyPressed(final KeyEvent e) {

		if (!tracerPlugin.isUIready() || (canvas != null && canvas
			.isEventsDisabled()))
		{
			waiveKeyPress(e);
			return;
		}

		final int keyCode = e.getKeyCode();
		if (keyCode == KeyEvent.VK_SPACE) {// IJ's pan tool shortcut
			if (canvas != null) tracerPlugin.panMode = true;
			waiveKeyPress(e);
			return;
		}

		// NB: we don't want accidental keystrokes to interfere with SNT tasks
		// so we'll block any keys from reaching other listeners, unless: 1)
		// the keystroke is white-listed, 2) the user pressed a modifier key,
		// 3) it is a numeric keypad or 'action' (eg, fn) key
		final boolean doublePress = isDoublePress(e);
		final boolean ctrl_down = (e.getModifiersEx() & CTRL_CMD_MASK) != 0;
		final boolean shift_down = e.isShiftDown();

		if (keyCode == KeyEvent.VK_ESCAPE && canvas != null) {
			// Esc is the documented wand>hand tool toggle for the Legacy 3D viewer
			if (doublePress) tracerPlugin.getUI().reset();
			else tracerPlugin.getUI().abortCurrentOperation();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_ENTER) {
			tracerPlugin.getUI().toFront();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_4) {
			tracerPlugin.unzoomAllPanes();
			e.consume();
			return;
		}
		else if (keyCode == KeyEvent.VK_5) {
			tracerPlugin.zoom100PercentAllPanes();
			e.consume();
			return;
		}
		else if (ctrl_down && shift_down && keyCode == KeyEvent.VK_P && tracerPlugin.getUI() != null) {
			tracerPlugin.getUI().runCustomCommand("cmdPalette");
			e.consume();
			return;
		}
		else if (ctrl_down && keyCode == KeyEvent.VK_S && tracerPlugin.getUI() != null) {
			tracerPlugin.getUI().saveToXML(shift_down);
			e.consume();
			return;
		}
		// Exceptions above aside, do not intercept white-listed keystrokes,
		// combinations that include the OS shortcut key, action keys, or
		// numpad keys (so that users can still access IJ built-in shortcuts
		// and/or custom shortcuts for their macros)
		else if ( ctrl_down || e.isActionKey()
				|| e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD
				|| Arrays.stream(W_KEYS).anyMatch(i -> i == keyCode))
		{
			waiveKeyPress(e);
			return;
		}

		final char keyChar = e.getKeyChar();
		final boolean alt_down = e.isAltDown();
		final boolean join_modifier_down = (tracerPlugin.requireShiftToFork) ? shift_down && alt_down : alt_down;

		// SNT Hotkeys that do not override defaults
		if (join_modifier_down && keyCode == KeyEvent.VK_A)
		{
			startShollAnalysis();
			e.consume();
		}

		// SNT Keystrokes that override IJ defaults. These
		// are common to both tracing and edit mode
		else if (canvas != null && keyChar == '\u0000' && (shift_down || alt_down)) {

			// This case is just so that when someone starts holding down
			// the modified immediately see the effect, rather than having
			// to wait for the next mouse move event
			canvas.fakeMouseMoved(shift_down, join_modifier_down);
			e.consume();
		}
		else if (shift_down && (keyChar == 'b' || keyChar == 'B')) {
			// IJ1 built-in: Shift+B Blobs Sample Image
			canvas.bookmarkCursorLocation();
			e.consume();
		}
		else if (shift_down && (keyChar == 'e' || keyChar == 'E')) {
			// IJ1 built-in: Shift+E Restore Selection
			canvas.toggleEditMode();
			e.consume();
		}
		else if (shift_down && (keyChar == 'p' || keyChar == 'P')) {
			// IJ1 built-in: Shift+P Image Properties
			canvas.togglePauseTracing();
			e.consume();
		}
		else if (keyChar == 'g' || keyChar == 'G') {
			// IJ1 built-in: Shift+G Take a screenshot
			selectNearestPathToMousePointer(shift_down);
			e.consume();
		}

		// Hotkeys common to all modes
		else if (keyChar == '1') {
			// IJ1 built-in: Select First Lane
			tracerPlugin.getUI().togglePathsChoice();
			e.consume();
		}

		else if (keyChar == '2') {
			// IJ1 built-in: Select Next Lane
			tracerPlugin.getUI().togglePartsChoice();
			e.consume();
		}
		else if (keyChar == '3') {
			// IJ1 built-in: Plot Lanes
			tracerPlugin.getUI().toggleChannelAndFrameChoice();
			e.consume();
		}

		// Keystrokes exclusive to edit mode (NB: Currently these only work
		// with InteractiveCanvas). We'll skip hasty keystrokes to avoid
		// mis-editing
		else if (canvas != null && canvas.isEditMode()) {
			if (doublePress) {
				e.consume();
				return;
			}
			if (keyChar == 'd' || keyChar == 'D' || keyCode == KeyEvent.VK_BACK_SPACE
					|| keyCode == KeyEvent.VK_DELETE) {
				canvas.deleteEditingNode(false);
			}
			else if (keyChar == 'i' || keyChar == 'I')
			{
				canvas.appendLastCanvasPositionToEditingNode(false);
			}
			else if (keyChar == 'r' || keyChar == 'R')
			{
				canvas.assignRadiusToEditingNode(false);
			}
			else if (keyChar == 'l' || keyChar == 'L') {
				canvas.toggleEditingNode(true);
			}
			else if (keyChar == 'm' || keyChar == 'M') {
				canvas.moveEditingNodeToLastCanvasPosition(false);
			}
			else if (keyChar == 'b' || keyChar == 'B') {
				canvas.assignLastCanvasZPositionToEditNode(false);
			}
			else if (keyChar == 'c' || keyChar == 'C') {
				canvas.connectEditingPathToPreviousEditingPath();
			}
			else if (keyChar == 'x' || keyChar == 'X') {
				canvas.splitTreeAtEditingNode(false);
			}
			else if (keyChar == 'v' || keyChar == 'V') {
				canvas.clickAtMaxPoint(false);
			}
			e.consume();
			return;
		}

		// Keystrokes exclusive to tracing mode
		else if (canvas != null && !canvas.isEditMode() &&
			!tracerPlugin.tracingHalted)
		{

			if (keyChar == 'y' || keyChar == 'Y') {
				// IJ1 built-in: ROI Properties...
				if (tracerPlugin.getUI().finishOnDoubleConfimation && doublePress)
					tracerPlugin.finishedPath();
				else tracerPlugin.confirmTemporary(true);
				e.consume();
			}
			else if (keyChar == 'n' || keyChar == 'N') {
				// IJ1 built-in: New Image
				if (tracerPlugin.getUI().discardOnDoubleCancellation && doublePress)
					tracerPlugin.cancelPath();
				else tracerPlugin.cancelTemporary();
				e.consume();
			}
			else if (keyChar == 'c' || keyChar == 'C') {
				// IJ1 built-in: Copy
				if (tracerPlugin.getUIState() == SNTUI.PARTIAL_PATH) tracerPlugin
					.cancelPath();
				else if (doublePress) tracerPlugin.getUI().abortCurrentOperation();
				e.consume();
			}
			else if (keyChar == 'f' || keyChar == 'F') {
				// IJ1 built-in: Fill
				tracerPlugin.finishedPath();
				e.consume();
			}
			else if (keyChar == 'l' || keyChar == 'L') {
				// IJ1 built-in: commands/Find commands
				tracerPlugin.getUI().toggleSecondaryLayerTracing();
				e.consume();
			}
			else if (keyChar == 'v' || keyChar == 'V') {
				// IJ1 built-in: Measure
				canvas.clickAtMaxPoint(join_modifier_down);
				e.consume();
			}
			else if (keyChar == 's' || keyChar == 'S') {
				// IJ1 built-in: Save
				tracerPlugin.toggleSnapCursor();
				e.consume();
			}
		}

		// Uncomment below to pass on any other key press to existing listeners
		// else waiveKeyPress(e);

	}

	private void startShollAnalysis() {
		if (canvas != null) {
			canvas.startShollAnalysis();
			return;
		}
		new Thread(() -> {
			final NearPoint np = getNearestPickedPoint();
			if (np != null) tracerPlugin.startSholl(np.getNode());
		}).start();
	}

	private NearPoint getNearestPickedPoint() {
		final Point p = univ.getCanvas().getMousePosition();
		if (p == null) return null;
		final Picker picker = univ.getPicker();
		final Content c = picker.getPickedContent(p.x, p.y);
		if (null == c) return null;
		final Point3d point = picker.getPickPointGeometry(c, p.x, p.y);
		final double diagonalLength = tracerPlugin.getImpDiagonalLength(true,
			false);
		final NearPoint np = tracerPlugin.getPathAndFillManager()
			.nearestPointOnAnyPath(point.x, point.y, point.z, diagonalLength);
		if (np == null) {
			SNTUtils.error("BUG: No nearby path was found within " + diagonalLength +
				" of the pointer");
		}
		return np;
	}

	private void selectNearestPathToMousePointer(final boolean shift_down) {
		if (canvas != null) {
			canvas.selectNearestPathToMousePointer(shift_down);
			return;
		}
		new Thread(() -> {
			final NearPoint np = getNearestPickedPoint();
			if (np == null) {
				return;
			}
			final Path path = np.getPath();
			tracerPlugin.selectPath(path, shift_down);
		}).start();
	}

	private void waiveKeyPress(final KeyEvent e) {
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyPressed(e);
		}
	}

	@Override
	public void keyReleased(final KeyEvent e) {
		if (e.getKeyCode() == KeyEvent.VK_SPACE && canvas != null) { // IJ's pan
																																	// tool
																																	// shortcut
			tracerPlugin.panMode = false;
		}
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyReleased(e);
		}
	}

	@Override
	public void keyTyped(final KeyEvent e) {
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyTyped(e);
		}
	}

	private boolean isDoublePress(final KeyEvent ke) {
		if (lastKeyPressedCode == ke.getKeyCode() && ((ke.getWhen() -
			timeKeyDown) < DOUBLE_PRESS_INTERVAL)) return true;
		timeKeyDown = ke.getWhen();
		lastKeyPressedCode = ke.getKeyCode();
		return false;
	}

	/**
	 * This method should add the other key listeners in 'laterKeyListeners' that
	 * will be called for 'source' if this key listener isn't interested in the
	 * key press.
	 */
	public void addOtherKeyListeners(final KeyListener[] laterKeyListeners) {
		final ArrayList<KeyListener> newListeners = new ArrayList<>(Arrays.asList(
			laterKeyListeners));
		for (KeyListener listener : newListeners) {
			if (!listeners.contains(listener)) listeners.add(listener);
		}
	}

	private class PointSelectionBehavior extends InteractiveBehavior {

		private final GuiUtils gUtils;

		public PointSelectionBehavior(final DefaultUniverse univ) {
			super(univ);
			this.gUtils = new GuiUtils(univ.getCanvas());
		}

		@Override
		public void doProcess(final KeyEvent e) {
			SwingUtilities.invokeLater(() -> {
				final char keyChar = e.getKeyChar();
				if (keyChar == 'w' || keyChar == 'W') {
					Toolbar.getInstance().setTool(Toolbar.WAND);
					gUtils.tempMsg("Wand Tool selected");
				}
				else if (keyChar == 'h' || keyChar == 'H') {
					Toolbar.getInstance().setTool(Toolbar.HAND);
					gUtils.tempMsg("Hand Tool selected");
				}
				else {
					keyPressed(e);
				}
			});
		}

		@Override
		public void doProcess(final MouseEvent me) {

			if (!tracerPlugin.isUIready() || Toolbar.getToolId() != Toolbar.WAND || me
				.getID() != MouseEvent.MOUSE_PRESSED)
			{
				super.doProcess(me);
				return;
			}

			final Picker picker = univ.getPicker();
			final Content c = picker.getPickedContent(me.getX(), me.getY());
			if (null == c) {
				gUtils.tempMsg("No content picked!");
				return;
			}

			gUtils.tempMsg("Retrieving content...");
			final Point3d point = picker.getPickPointGeometry(c, me.getX(), me
				.getY());
			gUtils.tempMsg(SNTUtils.formatDouble(point.x, 3) + ", " + SNTUtils.formatDouble(
				point.y, 3) + ", " + SNTUtils.formatDouble(point.z, 3));
			final boolean joiner_modifier_down = me.isAltDown();
			SwingUtilities.invokeLater(() -> tracerPlugin.clickForTrace(point,
				joiner_modifier_down));
		}
	}
}
