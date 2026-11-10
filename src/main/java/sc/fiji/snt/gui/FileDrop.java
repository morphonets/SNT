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

import sc.fiji.snt.SNTUtils;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Makes it easy to drag and drop files from the operating system to a Java
 * program. Any {@link java.awt.Component} displayed in a Swing window can be
 * dropped onto. While dragging, a frame is painted over the target without
 * affecting its border or layout.
 * <p>
 * To use this class, construct a new {@code FileDrop} by passing it the target
 * component and a {@link Listener} to receive notification when file(s) have
 * been dropped. Dropped files are delivered on the Event Dispatch Thread, after
 * the drop operation has been completed, so listeners are free to display
 * dialogs or start long running tasks. Example:
 * </p>
 *
 * <pre>
 * new FileDrop(myPanel, files -&gt; {
 *     // handle file drop
 * });
 * </pre>
 * <p>
 * Released into the Public Domain by the original author, Robert Harder
 * (rob@iharder.net).
 * </p>
 * <p>
 * Additional support:
 * </p>
 * <ul>
 * <li>September 2007, Nathan Blomquist: Linux (KDE/Gnome) support</li>
 * <li>December 2010, Joshua Gerth</li>
 * <li>June 2019, TF: Adjusted default border color, code cleanup</li>
 * <li>2026, TF: Modernization (single drop handler, headless awareness,
 * uri-list parsing, asynchronous listener dispatch, uninstall support, layout
 * neutral drag highlight)</li>
 * </ul>
 *
 * @author Robert Harder
 * @author rharder@users.sf.net
 */
public class FileDrop {

	private static final String URI_LIST_MIME = "text/uri-list";

	private final Component target;
	private final Listener listener;
	private final DropTargetListener dropListener = new Handler();
	private final List<Installed> installed = new ArrayList<>();

	// Frame painted over the target while a drag is active
	private Highlight highlight;

	/**
	 * Constructs a {@link FileDrop} that recursively sets all the components
	 * contained in <var>c</var> as drop targets, though only <var>c</var> is
	 * highlighted while dragging.
	 *
	 * @param c the component on which files will be dropped
	 * @param listener notified when files are dropped
	 */
	public FileDrop(final Component c, final Listener listener) {
		this(c, true, listener);
	}

	/**
	 * Constructs a {@link FileDrop}. In headless mode this constructor is a
	 * no-op.
	 *
	 * @param c the component on which files will be dropped
	 * @param recursive if {@code true}, and <var>c</var> is a
	 *          {@link java.awt.Container}, its children will also be set as drop
	 *          targets, though only <var>c</var> is highlighted while dragging
	 * @param listener notified when files are dropped
	 */
	public FileDrop(final Component c, final boolean recursive,
			final Listener listener) {
		this.target = Objects.requireNonNull(c);
		this.listener = Objects.requireNonNull(listener);
		if (GraphicsEnvironment.isHeadless()) {
			SNTUtils.log("FileDrop: headless mode, drag and drop is disabled");
			return;
		}
		install(c, recursive);
	}

	/**
	 * Removes the drag-and-drop hooks installed by this instance, restoring any
	 * drop target the components had before. Call this if you add and remove
	 * components after you've set up the drag-and-drop.
	 */
	public void uninstall() {
		for (final Installed i : installed) {
			if (i.component().getDropTarget() == i.ours())
				i.component().setDropTarget(i.previous());
		}
		installed.clear();
		hideHighlight();
	}

	private void install(final Component c, final boolean recursive) {
		final DropTarget previous = c.getDropTarget();
		// The DropTarget is attached to the component by its constructor
		final DropTarget ours = new DropTarget(c, DnDConstants.ACTION_COPY,
				dropListener, true);
		installed.add(new Installed(c, previous, ours));
		if (recursive && c instanceof Container cont) {
			for (final Component child : cont.getComponents())
				install(child, true);
		}
	}

	private void showHighlight() {
		if (highlight != null) return;
		final JRootPane root = SwingUtilities.getRootPane(target);
		if (root == null || !target.isShowing()) return;
		// The layered pane has no layout manager: adding the highlight to it
		// leaves the layout and the border of the target untouched
		final JLayeredPane layers = root.getLayeredPane();
		final Point p = SwingUtilities.convertPoint(target, 0, 0, layers);
		highlight = new Highlight(GuiUtils.Colors.selectionColor());
		highlight.setBounds(p.x, p.y, target.getWidth(), target.getHeight());
		layers.add(highlight, JLayeredPane.DRAG_LAYER);
	}

	private void hideHighlight() {
		if (highlight == null) return;
		final Container parent = highlight.getParent();
		if (parent != null) {
			parent.remove(highlight);
			parent.repaint(highlight.getX(), highlight.getY(), highlight.getWidth(),
					highlight.getHeight());
		}
		highlight = null;
	}

	private void notifyListener(final File[] files) {
		try {
			listener.filesDropped(files);
		} catch (final RuntimeException ex) {
			SNTUtils.error("FileDrop: listener failed", ex);
		}
	}

	private static boolean isUriList(final DataFlavor flavor) {
		return flavor.isRepresentationClassReader() && flavor.isMimeTypeEqual(
				URI_LIST_MIME);
	}

	private static boolean isDragOk(final DataFlavor[] flavors) {
		return Arrays.stream(flavors).anyMatch(f -> DataFlavor.javaFileListFlavor
				.equals(f) || isUriList(f));
	}

	private static File[] readFileList(final Transferable tr)
			throws IOException, UnsupportedFlavorException {
		final Object data = tr.getTransferData(DataFlavor.javaFileListFlavor);
		if (!(data instanceof List<?> list)) return new File[0];
		return list.stream().filter(File.class::isInstance).map(File.class::cast)
				.toArray(File[]::new);
	}

	private static File[] readUriList(final DataFlavor flavor,
			final Transferable tr) throws IOException, UnsupportedFlavorException {
		try (BufferedReader br = new BufferedReader(flavor.getReaderForText(tr))) {
			return parseUriList(br.lines().toList());
		}
	}

	private static File[] parseUriList(final List<String> lines) {
		final List<File> files = new ArrayList<>();
		for (final String raw : lines) {
			// KDE may append NUL chars, and uri-lists (RFC 2483) may hold comments
			final String line = raw.replace("\0", "").strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			try {
				final URI uri = new URI(line);
				if ("file".equalsIgnoreCase(uri.getScheme())) files.add(new File(uri));
				else SNTUtils.log("FileDrop: ignoring non-file URI: " + line);
			} catch (final URISyntaxException | RuntimeException ex) {
				SNTUtils.log("FileDrop: could not parse " + line + ": " + ex
						.getMessage());
			}
		}
		return files.toArray(File[]::new);
	}

	@SuppressWarnings("serial")
	private static final class Highlight extends JComponent {

		private static final int THICKNESS = 2;
		private final Color color;

		Highlight(final Color color) {
			this.color = color;
			setOpaque(false);
			setFocusable(false);
		}

		// Let mouse events (and drop target lookups) go through
		@Override
		public boolean contains(final int x, final int y) {
			return false;
		}

		@Override
		protected void paintComponent(final Graphics g) {
			final int w = getWidth();
			final int h = getHeight();
			g.setColor(color);
			g.fillRect(0, 0, w, THICKNESS);
			g.fillRect(0, h - THICKNESS, w, THICKNESS);
			g.fillRect(0, THICKNESS, THICKNESS, h - 2 * THICKNESS);
			g.fillRect(w - THICKNESS, THICKNESS, THICKNESS, h - 2 * THICKNESS);
		}
	}

	private record Installed(Component component, DropTarget previous,
			DropTarget ours) {}

	private final class Handler extends DropTargetAdapter {

		@Override
		public void dragEnter(final DropTargetDragEvent evt) {
			if (target.isEnabled() && isDragOk(evt.getCurrentDataFlavors())) {
				showHighlight();
				evt.acceptDrag(DnDConstants.ACTION_COPY);
			} else {
				evt.rejectDrag();
			}
		}

		@Override
		public void dropActionChanged(final DropTargetDragEvent evt) {
			if (target.isEnabled() && isDragOk(evt.getCurrentDataFlavors()))
				evt.acceptDrag(DnDConstants.ACTION_COPY);
			else evt.rejectDrag();
		}

		@Override
		public void dragExit(final DropTargetEvent evt) {
			hideHighlight();
		}

		@Override
		public void drop(final DropTargetDropEvent evt) {
			boolean accepted = false;
			try {
				final Transferable tr = evt.getTransferable();
				final boolean fileList = tr.isDataFlavorSupported(
						DataFlavor.javaFileListFlavor);
				final DataFlavor uriFlavor = fileList ? null : Arrays.stream(tr
						.getTransferDataFlavors()).filter(FileDrop::isUriList)
						.findFirst().orElse(null);
				if (!fileList && uriFlavor == null) {
					SNTUtils.log("FileDrop: not a file list or uri list, aborting");
					evt.rejectDrop();
					return;
				}
				evt.acceptDrop(DnDConstants.ACTION_COPY);
				accepted = true;
				final File[] files = fileList ? readFileList(tr) : readUriList(
						uriFlavor, tr);
				evt.dropComplete(files.length > 0);
				// Dispatch after the drop completes so that the drag source (e.g.,
				// the OS file browser) is not blocked by dialogs or long tasks
				if (files.length > 0) SwingUtilities.invokeLater(() -> notifyListener(
						files));
			} catch (final IOException | UnsupportedFlavorException
					| RuntimeException ex) {
				SNTUtils.error("FileDrop: drop aborted", ex);
				if (accepted) evt.dropComplete(false);
				else evt.rejectDrop();
			} finally {
				hideHighlight();
			}
		}
	}

	/**
	 * Implement this interface to listen for when files are dropped.
	 */
	@FunctionalInterface
	public interface Listener {

		/**
		 * Called on the Event Dispatch Thread when files have been successfully
		 * dropped.
		 *
		 * @param files the (non-empty) array of files that were dropped
		 */
		void filesDropped(File[] files);

	}

}
