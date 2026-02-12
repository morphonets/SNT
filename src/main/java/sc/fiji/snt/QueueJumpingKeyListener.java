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

import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.SwingUtilities;

import org.jogamp.vecmath.Point3d;

import ij.gui.Toolbar;
import ij3d.Content;
import ij3d.DefaultUniverse;
import ij3d.Image3DUniverse;
import ij3d.behaviors.InteractiveBehavior;
import ij3d.behaviors.Picker;

class QueueJumpingKeyListener implements KeyListener {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final int CTRL_CMD_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
	private static final int DOUBLE_PRESS_INTERVAL = 300; // ms
	/* Define the keys that are always parsed by IJ listeners */
	private static final int[] IJ_KEYS = new int[]{ //
			// Zoom keys
			KeyEvent.VK_EQUALS, KeyEvent.VK_MINUS, KeyEvent.VK_UP, KeyEvent.VK_DOWN, //
			// Stack navigation keys
			KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, //
			// Extra navigation/zoom keys
			KeyEvent.VK_PLUS, KeyEvent.VK_LESS, KeyEvent.VK_GREATER, KeyEvent.VK_TAB};

	private final SNT tracerPlugin;
	private final InteractiveTracerCanvas canvas;
	private final Image3DUniverse univ;
	private final ArrayList<KeyListener> listeners = new ArrayList<>();
	// Command registry
	private final ArrayList<KeyCommand> keyCommands = new ArrayList<>();
	private long timeKeyDown = 0; // last time key was pressed
	private int lastKeyPressedCode;

	public QueueJumpingKeyListener(final SNT tracerPlugin, final InteractiveTracerCanvas canvas) {
		this.tracerPlugin = tracerPlugin;
		this.canvas = canvas;
		univ = null;
		initializeKeyCommands();
	}

	public QueueJumpingKeyListener(final SNT tracerPlugin, final Image3DUniverse univ) {
		this.tracerPlugin = tracerPlugin;
		this.canvas = tracerPlugin.getTracingCanvas();
		this.univ = univ;
		univ.addInteractiveBehavior(new PointSelectionBehavior(univ));
		initializeKeyCommands();
	}

	// Initialize key commands
	private void initializeKeyCommands() {
		// System commands (highest priority)
		keyCommands.add(new EscapeKeyCommand());
		keyCommands.add(new EnterKeyCommand());
		keyCommands.add(new ZoomUnzoomCommand());
		keyCommands.add(new SaveCommand());
		keyCommands.add(new CommandPaletteCommand());

		// Allowlisted keys that should be passed through
		keyCommands.add(new AllowListedKeysCommand());

		// SNT hotkeys that don't override defaults
		keyCommands.add(new ShollAnalysisCommand());

		// Common SNT keystrokes
		keyCommands.add(new ModifierVisualizationCommand());
		keyCommands.add(new BookmarkCommand());
		keyCommands.add(new EditModeToggleCommand());
		keyCommands.add(new PauseTracingCommand());
		keyCommands.add(new SelectNearestPathCommand());
		keyCommands.add(new UIToggleCommands());

		// Edit mode specific commands
		keyCommands.add(new EditModeCommands());

		// Tracing mode specific commands
		keyCommands.add(new TracingModeCommands());
	}

	@Override
	public void keyPressed(final KeyEvent e) {
		// Early validation
		if (KeyEvent.KEY_PRESSED != e.getID() || !tracerPlugin.isUIready() ||
				(canvas != null && canvas.isEventsDisabled())) {
			waiveKeyPress(e);
			return;
		}
        // Special case #1: Handle 'hide' key
        if (e.getKeyCode() == KeyEvent.VK_H && canvas != null) {
            tracerPlugin.setAnnotationsVisible(false);
            e.consume();
            return;
        }
		// Special case #2: Handle the 'Orientation' key
		if (e.getKeyCode() == KeyEvent.VK_O && canvas != null) {
			PathNodeCanvas.setShowDirectionArrows(true);
			tracerPlugin.repaintAllPanes();
			e.consume();
			return;
		}
        // Special case #3: Handle space key (pan mode)
		if (e.getKeyCode() == KeyEvent.VK_SPACE) {
			if (canvas != null) tracerPlugin.panMode = true;
			waiveKeyPress(e);
			return;
		}

		// Create context for key commands
		final boolean doublePress = isDoublePress(e);
		final KeyContext context = new KeyContext(e, doublePress, tracerPlugin.requireShiftToFork);

		// Process key commands in order of priority
		for (KeyCommand command : keyCommands) {
			if (command.canHandle(e, context)) {
				command.execute(e, context);
				return;
			}
		}

		// If no command handled the key, don't pass it on to avoid interference
		// Uncomment below to pass on any other key press to existing listeners
		// waiveKeyPress(e);
	}

	private void bookmarkCursorLocation() {
		if (calledFromUniv(false)) {
			final Point3d p = getNearestPickedPoint();
			if (p != null) {
				tracerPlugin.getUI().getBookmarkManager().add((int) p.x, (int) p.y, (int) p.z,
						tracerPlugin.getChannel(), tracerPlugin.getFrame());
				if (!tracerPlugin.getUI().getBookmarkManager().isShowing())
					showStatus("Bookmark added");
			}
		} else if (canvas != null) {
			canvas.bookmarkCursorLocation();
		}
	}

	private void startShollAnalysis() {
		if (calledFromUniv(false)) {
			new Thread(() -> {
				final NearPoint np = getNearestPickedPointOnAnyPath();
				if (np != null) tracerPlugin.startSholl(np.getNode());
			}).start();
		} else if (canvas != null) {
			canvas.startShollAnalysis();
		}
	}

	private Point3d getNearestPickedPoint() {
		final Point p = univ.getCanvas().getMousePosition();
		if (p == null) return null;
		final Picker picker = univ.getPicker();
		final Content c = picker.getPickedContent(p.x, p.y);
		if (null == c) {
			showStatus("No content available at cursor location");
			return null;
		}
		return picker.getPickPointGeometry(c, p.x, p.y);
	}

	private NearPoint getNearestPickedPointOnAnyPath() {
		final Point3d point = getNearestPickedPoint();
		if (null == point) return null;
		final double diagonalLength = tracerPlugin.getImpDiagonalLength(true, false);
		final NearPoint np = tracerPlugin.getPathAndFillManager()
				.nearestPointOnAnyPath(point.x, point.y, point.z, diagonalLength);
		if (np == null) {
			SNTUtils.error("BUG: No nearby path was found within " + diagonalLength +
					" of the pointer");
		}
		return np;
	}

	private void selectNearestPathToMousePointer(final boolean shift_down) {
		if (calledFromUniv(false)) {
			new Thread(() -> {
				final NearPoint np = getNearestPickedPointOnAnyPath();
				if (np != null) {
					final Path path = np.getPath();
					tracerPlugin.selectPath(path, shift_down);
				}
			}).start();
		} else if (canvas != null) {
			canvas.selectNearestPathToMousePointer(shift_down);
		}
	}

	private void waiveKeyPress(final KeyEvent e) {
		for (final KeyListener kl : listeners) {
			if (e.isConsumed()) break;
			kl.keyPressed(e);
		}
	}

	@Override
	public void keyReleased(final KeyEvent e) {
        // Special case #1: Handle 'hide' key
        if (e.getKeyCode() == KeyEvent.VK_H && canvas != null) {
            tracerPlugin.setAnnotationsVisible(true);
            e.consume();
            return;
        }
		// Special case #2: Handle 'Orientation' key
		if (e.getKeyCode() == KeyEvent.VK_O && canvas != null) {
			PathNodeCanvas.setShowDirectionArrows(false);
			tracerPlugin.repaintAllPanes();
			e.consume();
			return;
		}
        // Special case #3: Handle space key (pan mode)
        if (e.getKeyCode() == KeyEvent.VK_SPACE && canvas != null) {
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

	void showStatus(final String msg) {
		//NB: using new GuiUtils(univ.getWindow()).tempMsg(msg) steals focus from viewer canvas and thus this listener
		tracerPlugin.getUI().showStatus(msg, true);
	}

	boolean calledFromUniv(final boolean warnIfNonApplicableKey) {
		if (univ != null && warnIfNonApplicableKey)
			showStatus("Shortcut does not apply to Leg. 3D Viewer...");
		return univ != null;
	}

	// Key command interface for handling key operations
	private interface KeyCommand {
		boolean canHandle(KeyEvent e, KeyContext context);

		void execute(KeyEvent e, KeyContext context);
	}

	// Context object to pass key state information
	private static class KeyContext {
		final boolean doublePress;
		final boolean ctrlDown;
		final boolean shiftDown;
		final boolean altDown;
		final boolean joinModifierDown;
		final int keyCode;
		final char keyChar;

		KeyContext(KeyEvent e, boolean doublePress, boolean requireShiftToFork) {
			this.doublePress = doublePress;
			this.ctrlDown = (e.getModifiersEx() & CTRL_CMD_MASK) != 0;
			this.shiftDown = e.isShiftDown();
			this.altDown = e.isAltDown();
			this.joinModifierDown = requireShiftToFork ? shiftDown && altDown : altDown;
			this.keyCode = e.getKeyCode();
			this.keyChar = e.getKeyChar();
		}
	}

	// Key command implementations
	private class EscapeKeyCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.keyCode == KeyEvent.VK_ESCAPE;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			if (context.doublePress) {
				tracerPlugin.getUI().reset();
			} else {
				tracerPlugin.getUI().abortCurrentOperation();
			}
			e.consume();
		}
	}

	private class EnterKeyCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.keyCode == KeyEvent.VK_ENTER;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			tracerPlugin.getUI().toFront();
			e.consume();
		}
	}

	private class ZoomUnzoomCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.keyCode == KeyEvent.VK_4 || context.keyCode == KeyEvent.VK_5;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			if (!calledFromUniv(true)) {
				if (context.keyCode == KeyEvent.VK_4) {
					tracerPlugin.unzoomAllPanes();
				} else {
					tracerPlugin.zoom100PercentAllPanes();
				}
			}
			e.consume();
		}
	}

	private class SaveCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.ctrlDown && context.keyCode == KeyEvent.VK_S && tracerPlugin.getUI() != null;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			tracerPlugin.getUI().saveToXML(context.shiftDown);
			e.consume();
		}
	}

	private class CommandPaletteCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.ctrlDown && context.shiftDown && context.keyCode == KeyEvent.VK_P &&
					tracerPlugin.getUI() != null;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			tracerPlugin.getUI().runCustomCommand("cmdPalette");
			e.consume();
		}
	}

	private class AllowListedKeysCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.ctrlDown || e.isActionKey() ||
					e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD ||
					Arrays.stream(IJ_KEYS).anyMatch(i -> i == context.keyCode);
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			waiveKeyPress(e);
		}
	}

	private class ShollAnalysisCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.joinModifierDown && context.keyCode == KeyEvent.VK_A;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			startShollAnalysis();
			e.consume();
		}
	}

	private class ModifierVisualizationCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return canvas != null && context.keyChar == '\u0000' &&
					(context.shiftDown || context.altDown);
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			canvas.fakeMouseMoved(context.shiftDown, context.joinModifierDown);
			e.consume();
		}
	}

	private class BookmarkCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.shiftDown && (context.keyChar == 'b' || context.keyChar == 'B');
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			bookmarkCursorLocation();
			e.consume();
		}
	}

	private class EditModeToggleCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.shiftDown && (context.keyChar == 'e' || context.keyChar == 'E');
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			if (canvas != null && !calledFromUniv(true)) {
				canvas.toggleEditMode();
			}
			e.consume();
		}
	}

	private class PauseTracingCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.shiftDown && (context.keyChar == 'p' || context.keyChar == 'P');
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			if (canvas != null && !calledFromUniv(true)) {
				canvas.togglePauseTracing();
			}
			e.consume();
		}
	}

	private class SelectNearestPathCommand implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.keyChar == 'g' || context.keyChar == 'G';
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			selectNearestPathToMousePointer(context.shiftDown);
			e.consume();
		}
	}

	private class UIToggleCommands implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return context.keyChar == '1' || context.keyChar == '2' || context.keyChar == '3';
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			switch (context.keyChar) {
				case '1' -> tracerPlugin.getUI().togglePathsChoice();
				case '2' -> tracerPlugin.getUI().togglePartsChoice();
				case '3' -> tracerPlugin.getUI().toggleChannelAndFrameChoice();
			}
			e.consume();
		}
	}

	private class EditModeCommands implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return canvas != null && canvas.isEditMode() && !context.doublePress && !calledFromUniv(true);
		}

		@Override
		public void execute(final KeyEvent e, final KeyContext context) {
			switch (context.keyChar) {
				case 'd', 'D' -> canvas.deleteEditingNode(false);
				case 'i', 'I' -> canvas.appendLastCanvasPositionToEditingNode(false);
				case 'r', 'R' -> canvas.assignRadiusToEditingNode(false);
				case 'l', 'L' -> canvas.toggleEditingNode(true);
				case 'm', 'M' -> canvas.moveEditingNodeToLastCanvasPosition(false);
				case 'b', 'B' -> canvas.assignLastCanvasZPositionToEditNode(false);
				case 'c', 'C' -> canvas.connectEditingPathToPreviousEditingPath(true);
				case 'x', 'X' -> canvas.splitTreeAtEditingNode(false);
				case 'v', 'V' -> canvas.clickAtMaxPoint(false);
			}

			// Handle delete keys
			if (context.keyCode == KeyEvent.VK_BACK_SPACE || context.keyCode == KeyEvent.VK_DELETE) {
				canvas.deleteEditingNode(false);
			}

			e.consume();
		}
	}

	private class TracingModeCommands implements KeyCommand {
		@Override
		public boolean canHandle(KeyEvent e, KeyContext context) {
			return canvas != null && !canvas.isEditMode() && !tracerPlugin.tracingHalted;
		}

		@Override
		public void execute(KeyEvent e, KeyContext context) {
			switch (context.keyChar) {
				case 'y', 'Y' -> {
					if (tracerPlugin.getUI().finishOnDoubleConfimation && context.doublePress) {
						tracerPlugin.finishedPath();
					} else {
						tracerPlugin.confirmTemporary(true);
					}
				}
				case 'n', 'N' -> {
					if (tracerPlugin.getUI().discardOnDoubleCancellation && context.doublePress) {
						tracerPlugin.cancelPath();
					} else {
						tracerPlugin.cancelTemporary();
					}
				}
				case 'c', 'C' -> {
					if (tracerPlugin.getUIState() == SNTUI.PARTIAL_PATH) {
						tracerPlugin.cancelPath();
					} else if (context.doublePress) {
						tracerPlugin.getUI().abortCurrentOperation();
					}
				}
				case 'f', 'F' -> tracerPlugin.finishedPath();
				case 'l', 'L' -> tracerPlugin.getUI().toggleSecondaryLayerTracing();
				case 'v', 'V' -> {
					if (!calledFromUniv(true)) {
						canvas.clickAtMaxPoint(context.joinModifierDown);
					}
				}
				case 's', 'S' -> {
					if (!calledFromUniv(true)) {
						tracerPlugin.toggleSnapCursor();
					}
				}
			}
			e.consume();
		}
	}

	private class PointSelectionBehavior extends InteractiveBehavior {


		public PointSelectionBehavior(final DefaultUniverse univ) {
			super(univ);
		}

		@Override
		public void doProcess(final KeyEvent e) {
			SwingUtilities.invokeLater(() -> {
				final char keyChar = e.getKeyChar();
				if (keyChar == 'w' || keyChar == 'W') {
					Toolbar.getInstance().setTool(Toolbar.WAND);
					showStatus("Wand Tool selected");
				} else if (keyChar == 'h' || keyChar == 'H') {
					Toolbar.getInstance().setTool(Toolbar.HAND);
					showStatus("Hand Tool selected");
				} else {
					keyPressed(e);
				}
			});
		}

		@Override
		public void doProcess(final MouseEvent me) {

			if (!tracerPlugin.isUIready() || Toolbar.getToolId() != Toolbar.WAND || me.getID() != MouseEvent.MOUSE_PRESSED) {
				super.doProcess(me);
				return;
			}
			final Picker picker = univ.getPicker();
			final Content c = picker.getPickedContent(me.getX(), me.getY());
			if (null == c) {
				showStatus("No content picked!");
				return;
			}
			showStatus("Retrieving content...");
			final Point3d point = picker.getPickPointGeometry(c, me.getX(), me.getY());
			showStatus(String.format("%.3f, %.3f, %.3f", point.x, point.y, point.z));
			final boolean joiner_modifier_down = me.isAltDown();
			SwingUtilities.invokeLater(() -> tracerPlugin.clickForTrace(point, joiner_modifier_down));
		}
	}
}
