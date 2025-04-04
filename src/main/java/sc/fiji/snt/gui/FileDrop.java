/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.io.*;


/**
 * This class makes it easy to drag and drop files from the operating system to
 * a Java program. Any <code>java.awt.Component</code> can be dropped onto, but only
 * <code>javax.swing.JComponent</code>s will indicate the drop event with a changed
 * border.
 * <p>
 * To use this class, construct a new <code>FileDrop</code> by passing it the target
 * component and a <code>Listener</code> to receive notification when file(s) have
 * been dropped. Here is an example:
 * </p>
 * 
 * <pre>
 *      JPanel myPanel = new JPanel();
 *      new FileDrop( myPanel, new FileDrop.Listener()
 *      {   public void filesDropped( java.io.File[] files )
 *          {   
 *              // handle file drop
 *              ...
 *          }   // end filesDropped
 *      }); // end FileDrop.Listener
 * </pre>
 * <p>
 * You can specify the border that will appear when files are being dragged by
 * calling the constructor with a <code>javax.swing.border.Border</code>. Only
 * <code>JComponent</code>s will show any indication with a border.
 * </p>
 * <p>
 * You can turn on some debugging features by passing a <code>PrintStream</code>
 * object (such as <code>System.out</code>) into the full constructor. A
 * <code>null</code> value will result in no extra debugging information being
 * output.
 * </p>
 *
 * <p>
 * I'm releasing this code into the Public Domain. Enjoy.
 * </p>
 * <p>
 * Original author: Robert Harder, rob@iharder.net
 * </p>
 * <p>
 * Additional support:
 * </p>
 * <ul>
 * <li>September 2007, Nathan Blomquist -- Linux (KDE/Gnome) support added.</li>
 * <li>December 2010, Joshua Gerth</li>
 * <li>June 2019, TF, Adjust defaultBorderColor. Code cleanup. Added the ability
 * to abort drop operation using Esc
 * </ul>
 *
 * @author Robert Harder
 * @author rharder@users.sf.net
 * @version 1.1.1
 */
public class FileDrop {
	private transient javax.swing.border.Border normalBorder;
	private transient java.awt.dnd.DropTargetListener dropListener;

	/** Discover if the running JVM is modern enough to have drag and drop. */
	private static Boolean supportsDnD;

	// Default border color
	private static final java.awt.Color defaultBorderColor = GuiUtils.getSelectionColor();

	/**
	 * Constructs a {@link FileDrop} with a default light-blue border and, if
	 * <var>c</var> is a {@link java.awt.Container}, recursively sets all
	 * elements contained within as drop targets, though only the top level
	 * container will change borders.
	 *
	 * @param c
	 *            Component on which files will be dropped.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	public FileDrop(final java.awt.Component c, final Listener listener) {
		this(null, // Logging stream
				c, // Drop target
				javax.swing.BorderFactory.createMatteBorder(2, 2, 2, 2,
						defaultBorderColor), // Drag border
				true, // Recursive
				listener);
	} // end constructor

	/**
	 * Constructor with a default border and the option to recursively set drop
	 * targets. If your component is a <code>java.awt.Container</code>, then each of
	 * its children components will also listen for drops, though only the
	 * parent will change borders.
	 *
	 * @param c
	 *            Component on which files will be dropped.
	 * @param recursive
	 *            Recursively set children as drop targets.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.awt.Component c, final boolean recursive,
			final Listener listener) {
		this(null, // Logging stream
				c, // Drop target
				javax.swing.BorderFactory.createMatteBorder(2, 2, 2, 2,
						defaultBorderColor), // Drag border
				recursive, // Recursive
				listener);
	} // end constructor

	/**
	 * Constructor with a default border and debugging optionally turned on.
	 * With Debugging turned on, more status messages will be displayed to
	 * <code>out</code>. A common way to use this constructor is with
	 * <code>System.out</code> or <code>System.err</code>. A <code>null</code> value for the
	 * parameter <code>out</code> will result in no debugging output.
	 *
	 * @param out
	 *            PrintStream to record debugging info or null for no debugging.
	 * @param c
	 *            Component on which files will be dropped.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.io.PrintStream out, final java.awt.Component c,
			final Listener listener) {
		this(out, // Logging stream
				c, // Drop target
				javax.swing.BorderFactory.createMatteBorder(2, 2, 2, 2,
						defaultBorderColor), false, // Recursive
				listener);
	} // end constructor

	/**
	 * Constructor with a default border, debugging optionally turned on and the
	 * option to recursively set drop targets. If your component is a
	 * <code>java.awt.Container</code>, then each of its children components will
	 * also listen for drops, though only the parent will change borders. With
	 * Debugging turned on, more status messages will be displayed to
	 * <code>out</code>. A common way to use this constructor is with
	 * <code>System.out</code> or <code>System.err</code>. A <code>null</code> value for the
	 * parameter <code>out</code> will result in no debugging output.
	 *
	 * @param out
	 *            PrintStream to record debugging info or null for no debugging.
	 * @param c
	 *            Component on which files will be dropped.
	 * @param recursive
	 *            Recursively set children as drop targets.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.io.PrintStream out, final java.awt.Component c,
			final boolean recursive, final Listener listener) {
		this(out, // Logging stream
				c, // Drop target
				javax.swing.BorderFactory.createMatteBorder(2, 2, 2, 2,
						defaultBorderColor), // Drag border
				recursive, // Recursive
				listener);
	} // end constructor

	/**
	 * Constructor with a specified border
	 *
	 * @param c
	 *            Component on which files will be dropped.
	 * @param dragBorder
	 *            Border to use on <code>JComponent</code> when dragging occurs.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.awt.Component c,
			final javax.swing.border.Border dragBorder, final Listener listener) {
		this(null, // Logging stream
				c, // Drop target
				dragBorder, // Drag border
				false, // Recursive
				listener);
	} // end constructor

	/**
	 * Constructor with a specified border and the option to recursively set
	 * drop targets. If your component is a <code>java.awt.Container</code>, then
	 * each of its children components will also listen for drops, though only
	 * the parent will change borders.
	 *
	 * @param c
	 *            Component on which files will be dropped.
	 * @param dragBorder
	 *            Border to use on <code>JComponent</code> when dragging occurs.
	 * @param recursive
	 *            Recursively set children as drop targets.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.awt.Component c,
			final javax.swing.border.Border dragBorder,
			final boolean recursive, final Listener listener) {
		this(null, c, dragBorder, recursive, listener);
	} // end constructor

	/**
	 * Constructor with a specified border and debugging optionally turned on.
	 * With Debugging turned on, more status messages will be displayed to
	 * <code>out</code>. A common way to use this constructor is with
	 * <code>System.out</code> or <code>System.err</code>. A <code>null</code> value for the
	 * parameter <code>out</code> will result in no debugging output.
	 *
	 * @param out
	 *            PrintStream to record debugging info or null for no debugging.
	 * @param c
	 *            Component on which files will be dropped.
	 * @param dragBorder
	 *            Border to use on <code>JComponent</code> when dragging occurs.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.io.PrintStream out, final java.awt.Component c,
			final javax.swing.border.Border dragBorder, final Listener listener) {
		this(out, // Logging stream
				c, // Drop target
				dragBorder, // Drag border
				false, // Recursive
				listener);
	} // end constructor

	/**
	 * Full constructor with a specified border and debugging optionally turned
	 * on. With Debugging turned on, more status messages will be displayed to
	 * <code>out</code>. A common way to use this constructor is with
	 * <code>System.out</code> or <code>System.err</code>. A <code>null</code> value for the
	 * parameter <code>out</code> will result in no debugging output.
	 *
	 * @param out
	 *            PrintStream to record debugging info or null for no debugging.
	 * @param c
	 *            Component on which files will be dropped.
	 * @param dragBorder
	 *            Border to use on <code>JComponent</code> when dragging occurs.
	 * @param recursive
	 *            Recursively set children as drop targets.
	 * @param listener
	 *            Listens for <code>filesDropped</code>.
	 * @since 1.0
	 */
	protected FileDrop(final java.io.PrintStream out, final java.awt.Component c,
			final javax.swing.border.Border dragBorder,
			final boolean recursive, final Listener listener) {

		if (supportsDnD()) { // Make a drop listener
			dropListener = new java.awt.dnd.DropTargetListener() {
				@Override
				public void dragEnter(final java.awt.dnd.DropTargetDragEvent evt) {
					log(out, "FileDrop: dragEnter event.");

					// Is this an acceptable drag event?
					if (isDragOk(out, evt) && c.isEnabled()) {
						// If it's a Swing component, set its border
						if (c instanceof javax.swing.JComponent) {
							final javax.swing.JComponent jc = (javax.swing.JComponent) c;
							if (dragBorder != null && normalBorder == null) {
								normalBorder = jc.getBorder();
							} // end if: border not yet saved
							log(out, "FileDrop: normal border saved.");
							if (dragBorder != null)
								jc.setBorder(dragBorder);
							log(out, "FileDrop: drag border set.");
						} // end if: JComponent

						// Acknowledge that it's okay to enter
						// evt.acceptDrag(
						// java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE );
						evt.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);
						log(out, "FileDrop: event accepted.");
					} // end if: drag ok
					else { // Reject the drag event
						evt.rejectDrag();
						log(out, "FileDrop: event rejected.");
					} // end else: drag not ok
				} // end dragEnter

				@Override
				public void dragOver(final java.awt.dnd.DropTargetDragEvent evt) { // This
																				// is
																				// called
																				// continually
																				// as
																				// long
																				// as
																				// the
																				// mouse
																				// is
																				// over
																				// the
																				// drag
																				// target.
				} // end dragOver

				@Override
				public void drop(final java.awt.dnd.DropTargetDropEvent evt) {
					log(out, "FileDrop: drop event.");
					try { // Get whatever was dropped
						final java.awt.datatransfer.Transferable tr = evt
								.getTransferable();

						// Is it a file list?
						if (tr.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
							// Say we'll take it.
							// evt.acceptDrop (
							// java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE );
							evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
							log(out, "FileDrop: file list accepted.");

							// Get a useful list
							final java.util.List<?> fileList = (java.util.List<?>) tr
									.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
							//final java.util.Iterator<?> iterator = fileList.iterator();

							// Convert list to array
							final java.io.File[] filesTemp = new java.io.File[fileList
									.size()];
							fileList.toArray(filesTemp);
							final java.io.File[] files = filesTemp;

							// Alert listener to drop.
							if (listener != null)
								listener.filesDropped(files);

							// Mark that drop is completed.
							evt.getDropTargetContext().dropComplete(true);
							log(out, "FileDrop: drop complete.");
						} // end if: file list
						else // this section will check for a reader flavor.
						{
							// Thanks, Nathan!
							// BEGIN 2007-09-12 Nathan Blomquist -- Linux
							// (KDE/Gnome) support added.
							final DataFlavor[] flavors = tr.getTransferDataFlavors();
							boolean handled = false;
							for (int zz = 0; zz < flavors.length; zz++) {
								if (flavors[zz].isRepresentationClassReader()) {
									// Say we'll take it.
									// evt.acceptDrop (
									// java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE
									// );
									evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
									log(out, "FileDrop: reader accepted.");

									final Reader reader = flavors[zz]
											.getReaderForText(tr);

									final BufferedReader br = new BufferedReader(
											reader);

									if (listener != null)
										listener.filesDropped(createFileArray(
												br, out));

									// Mark that drop is completed.
									evt.getDropTargetContext().dropComplete(
											true);
									log(out, "FileDrop: drop complete.");
									handled = true;
									break;
								}
							}
							if (!handled) {
								log(out,
										"FileDrop: not a file list or reader - abort.");
								evt.rejectDrop();
							}
							// END 2007-09-12 Nathan Blomquist -- Linux
							// (KDE/Gnome) support added.
						} // end else: not a file list
					} // end try
					catch (final java.io.IOException io) {
						logToSNT(out, "FileDrop: IOException - abort:");
						io.printStackTrace(out);
						evt.rejectDrop();
					} // end catch IOException
					catch (final java.awt.datatransfer.UnsupportedFlavorException ufe) {
						logToSNT(out,
								"FileDrop: UnsupportedFlavorException - abort:");
						ufe.printStackTrace(out);
						evt.rejectDrop();
					} // end catch: UnsupportedFlavorException
					finally {
						// If it's a Swing component, reset its border
						if (c instanceof javax.swing.JComponent) {
							final javax.swing.JComponent jc = (javax.swing.JComponent) c;
							if (dragBorder != null) jc.setBorder(normalBorder);
							log(out, "FileDrop: normal border restored.");
						} // end if: JComponent
					} // end finally
				} // end drop

				@Override
				public void dragExit(final java.awt.dnd.DropTargetEvent evt) {
					log(out, "FileDrop: dragExit event.");
					// If it's a Swing component, reset its border
					if (c instanceof javax.swing.JComponent) {
						final javax.swing.JComponent jc = (javax.swing.JComponent) c;
						if (dragBorder != null) jc.setBorder(normalBorder);
						log(out, "FileDrop: normal border restored.");
					} // end if: JComponent
				} // end dragExit

				@Override
				public void dropActionChanged(
						final java.awt.dnd.DropTargetDragEvent evt) {
					log(out, "FileDrop: dropActionChanged event.");
					// Is this an acceptable drag event?
					if (isDragOk(out, evt)) { // evt.acceptDrag(
												// java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE
												// );
						evt.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);
						log(out, "FileDrop: event accepted.");
					} // end if: drag ok
					else {
						evt.rejectDrag();
						log(out, "FileDrop: event rejected.");
					} // end else: drag not ok
				} // end dropActionChanged
			}; // end DropTargetListener

			// Make the component (and possibly children) drop targets
			makeDropTarget(out, c, recursive);
		} // end if: supports dnd
		else {
			logToSNT(out, "FileDrop: Drag and drop is not supported with this JVM");
		} // end else: does not support DnD
	} // end constructor

	private static boolean supportsDnD() { // Static Boolean
		if (supportsDnD == null) {
			boolean support = false;
			try {
				Class.forName("java.awt.dnd.DnDConstants");
				support = true;
			} // end try
			catch (final Exception e) {
				support = false;
			} // end catch
			supportsDnD = Boolean.valueOf(support);
		} // end if: first time through
		return supportsDnD.booleanValue();
	} // end supportsDnD

	// BEGIN 2007-09-12 Nathan Blomquist -- Linux (KDE/Gnome) support added.
	private static String ZERO_CHAR_STRING = "" + (char) 0;

	private static File[] createFileArray(final BufferedReader bReader,
			final PrintStream out) {
		try {
			final java.util.List<File> list = new java.util.ArrayList<>();
			java.lang.String line = null;
			while ((line = bReader.readLine()) != null) {
				try {
					// kde seems to append a 0 char to the end of the reader
					if (ZERO_CHAR_STRING.equals(line))
						continue;

					final java.io.File file = new java.io.File(new java.net.URI(line));
					list.add(file);
				} catch (final Exception ex) {
					log(out, "Error with " + line + ": " + ex.getMessage());
				}
			}

			return list.toArray(new File[0]);
		} catch (final IOException ex) {
			logToSNT(out, "FileDrop: IOException");
		}
		return new File[0];
	}

	// END 2007-09-12 Nathan Blomquist -- Linux (KDE/Gnome) support added.

	private void makeDropTarget(final java.io.PrintStream out,
			final java.awt.Component c, final boolean recursive) {
		// Make drop target
		final java.awt.dnd.DropTarget dt = new java.awt.dnd.DropTarget();
		try {
			dt.addDropTargetListener(dropListener);
		} // end try
		catch (final java.util.TooManyListenersException e) {
			e.printStackTrace();
			log(out,
					"FileDrop: Drop will not work due to previous error. Do you have another listener attached?");
		} // end catch

		// Listen for hierarchy changes and remove the drop target when the
		// parent gets cleared out.
		// end hierarchyChanged
		c.addHierarchyListener(evt -> {
			log(out, "FileDrop: Hierarchy changed.");
			final java.awt.Component parent = c.getParent();
			if (parent == null) {
				c.setDropTarget(null);
				log(out, "FileDrop: Drop target cleared from component.");
			} // end if: null parent
			else {
				new java.awt.dnd.DropTarget(c, dropListener);
				log(out, "FileDrop: Drop target added to component.");
			} // end else: parent not null
		}); // end hierarchy listener
		if (c.getParent() != null)
			new java.awt.dnd.DropTarget(c, dropListener);

		if (recursive && (c instanceof java.awt.Container)) {
			// Get the container
			final java.awt.Container cont = (java.awt.Container) c;

			// Get it's components
			final java.awt.Component[] comps = cont.getComponents();

			// Set it's components as listeners also
			for (int i = 0; i < comps.length; i++)
				makeDropTarget(out, comps[i], recursive);
		} // end if: recursively set components as listener
	} // end dropListener

	/** Determine if the dragged data is a file list. */
	private boolean isDragOk(final java.io.PrintStream out,
			final java.awt.dnd.DropTargetDragEvent evt) {
		boolean ok = false;

		// Get data flavors being dragged
		final java.awt.datatransfer.DataFlavor[] flavors = evt
				.getCurrentDataFlavors();

		// See if any of the flavors are a file list
		int i = 0;
		while (!ok && i < flavors.length) {
			// BEGIN 2007-09-12 Nathan Blomquist -- Linux (KDE/Gnome) support
			// added.
			// Is the flavor a file list?
			final DataFlavor curFlavor = flavors[i];
			if (curFlavor
					.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
					|| curFlavor.isRepresentationClassReader()) {
				ok = true;
			}
			// END 2007-09-12 Nathan Blomquist -- Linux (KDE/Gnome) support
			// added.
			i++;
		} // end while: through flavors

		// If logging is enabled, show data flavors
		if (out != null) {
			if (flavors.length == 0)
				log(out, "FileDrop: no data flavors.");
			for (i = 0; i < flavors.length; i++)
				log(out, flavors[i].toString());
		} // end if: logging enabled

		return ok;
	} // end isDragOk

	/** Outputs <code>message</code> to <code>out</code> if it's not null. */
	private static void log(final java.io.PrintStream out, final String message) { // Log
																		// message
																		// if
																		// requested
		if (out != null)
			out.println(message);
	} // end log

	private static void logToSNT(final java.io.PrintStream out, final String message) { // Log
		if (out != null)
			out.println(message);
		SNTUtils.log(message);
	}
	/**
	 * Removes the drag-and-drop hooks from the component and optionally from
	 * the all children. You should call this if you add and remove components
	 * after you've set up the drag-and-drop. This will recursively unregister
	 * all components contained within <var>c</var> if <var>c</var> is a
	 * {@link java.awt.Container}.
	 *
	 * @param c
	 *            The component to unregister as a drop target
	 * @since 1.0
	 */
	public static boolean remove(final java.awt.Component c) {
		return remove(null, c, true);
	} // end remove

	/**
	 * Removes the drag-and-drop hooks from the component and optionally from
	 * the all children. You should call this if you add and remove components
	 * after you've set up the drag-and-drop.
	 *
	 * @param out
	 *            Optional {@link java.io.PrintStream} for logging drag and drop
	 *            messages
	 * @param c
	 *            The component to unregister
	 * @param recursive
	 *            Recursively unregister components within a container
	 * @since 1.0
	 */
	protected static boolean remove(final java.io.PrintStream out, final java.awt.Component c,
			final boolean recursive) { // Make sure we support dnd.
		if (supportsDnD()) {
			log(out, "FileDrop: Removing drag-and-drop hooks.");
			c.setDropTarget(null);
			if (recursive && (c instanceof java.awt.Container)) {
				final java.awt.Component[] comps = ((java.awt.Container) c)
						.getComponents();
				for (int i = 0; i < comps.length; i++)
					remove(out, comps[i], recursive);
				return true;
			} // end if: recursive
			else
				return false;
		} // end if: supports DnD
		else
			return false;
	} // end remove

	/* ******** I N N E R I N T E R F A C E L I S T E N E R ******** */

	/**
	 * Implement this inner interface to listen for when files are dropped. For
	 * example your class declaration may begin like this:
	 * <pre>
	 *      public class MyClass implements FileDrop.Listener
	 *      ...
	 *      public void filesDropped( java.io.File[] files )
	 *      {
	 *          ...
	 *      }   // end filesDropped
	 *      ...
	 * </pre>
	 *
	 * @since 1.1
	 */
	public static interface Listener {

		/**
		 * This method is called when files have been successfully dropped.
		 *
		 * @param files
		 *            An array of files that were dropped.
		 * @since 1.0
		 */
		public abstract void filesDropped(java.io.File[] files);

	} // end inner-interface Listener

	/* ******** I N N E R C L A S S ******** */

	/**
	 * This is the event that is passed to the
	 * {@link FileDrop.Listener#filesDropped filesDropped(...)} method in your
	 * {@link FileDrop.Listener} when files are dropped onto a registered drop
	 * target.
	 *
	 * <p>
	 * I'm releasing this code into the Public Domain. Enjoy.
	 * </p>
	 * 
	 * @author Robert Harder
	 * @author rob@iharder.net
	 * @version 1.2
	 */
	@SuppressWarnings("serial")
	static class Event extends java.util.EventObject {

		private final java.io.File[] files;

		/**
		 * Constructs an {@link Event} with the array of files that were dropped
		 * and the {@link FileDrop} that initiated the event.
		 *
		 * @param files
		 *            The array of files that were dropped
		 * @param source
		 *            The event source
		 * @since 1.1
		 */
		public Event(final java.io.File[] files, final Object source) {
			super(source);
			this.files = files;
		} // end constructor

		/**
		 * Returns an array of files that were dropped on a registered drop
		 * target.
		 *
		 * @return array of files that were dropped
		 * @since 1.1
		 */
		public java.io.File[] getFiles() {
			return files;
		} // end getFiles

	} // end inner class Event

	/* ******** I N N E R C L A S S ******** */

	/**
	 * At last an easy way to encapsulate your custom objects for dragging and
	 * dropping in your Java programs! When you need to create a
	 * {@link java.awt.datatransfer.Transferable} object, use this class to wrap
	 * your object. For example:
	 * 
	 * <pre>
	 *      ...
	 *      MyCoolClass myObj = new MyCoolClass();
	 *      Transferable xfer = new TransferableObject( myObj );
	 *      ...
	 * </pre>
	 * 
	 * Or if you need to know when the data was actually dropped, like when
	 * you're moving data out of a list, say, you can use the
	 * {@link TransferableObject.Fetcher} inner class to return your object Just
	 * in Time. For example:
	 * 
	 * <pre>
	 *      ...
	 *      final MyCoolClass myObj = new MyCoolClass();
	 * 
	 *      TransferableObject.Fetcher fetcher = new TransferableObject.Fetcher()
	 *      {   public Object getObject(){ return myObj; }
	 *      }; // end fetcher
	 * 
	 *      Transferable xfer = new TransferableObject( fetcher );
	 *      ...
	 * </pre>
	 *
	 * The {@link java.awt.datatransfer.DataFlavor} associated with
	 * {@link TransferableObject} has the representation class
	 * <code>net.iharder.dnd.TransferableObject.class</code> and MIME type
	 * <code>application/x-net.iharder.dnd.TransferableObject</code>. This data
	 * flavor is accessible via the static {@link #DATA_FLAVOR} property.
	 *
	 *
	 * <p>
	 * I'm releasing this code into the Public Domain. Enjoy.
	 * </p>
	 * 
	 * @author Robert Harder
	 * @author rob@iharder.net
	 * @version 1.2
	 */
	static class TransferableObject implements
			java.awt.datatransfer.Transferable {
		/**
		 * The MIME type for {@link #DATA_FLAVOR} is
		 * <code>application/x-net.iharder.dnd.TransferableObject</code>.
		 *
		 * @since 1.1
		 */
		public final static String MIME_TYPE = "application/x-net.iharder.dnd.TransferableObject";

		/**
		 * The default {@link java.awt.datatransfer.DataFlavor} for
		 * {@link TransferableObject} has the representation class
		 * <code>net.iharder.dnd.TransferableObject.class</code> and the MIME type
		 * <code>application/x-net.iharder.dnd.TransferableObject</code>.
		 *
		 * @since 1.1
		 */
		public final static java.awt.datatransfer.DataFlavor DATA_FLAVOR = new java.awt.datatransfer.DataFlavor(
				FileDrop.TransferableObject.class, MIME_TYPE);

		private Fetcher fetcher;
		private Object data;

		private java.awt.datatransfer.DataFlavor customFlavor;

		/**
		 * Creates a new {@link TransferableObject} that wraps <var>data</var>.
		 * Along with the {@link #DATA_FLAVOR} associated with this class, this
		 * creates a custom data flavor with a representation class determined
		 * from <code>data.getClass()</code> and the MIME type
		 * <code>application/x-net.iharder.dnd.TransferableObject</code>.
		 *
		 * @param data
		 *            The data to transfer
		 * @since 1.1
		 */
		public TransferableObject(final Object data) {
			this.data = data;
			this.customFlavor = new java.awt.datatransfer.DataFlavor(
					data.getClass(), MIME_TYPE);
		} // end constructor

		/**
		 * Creates a new {@link TransferableObject} that will return the object
		 * that is returned by <var>fetcher</var>. No custom data flavor is set
		 * other than the default {@link #DATA_FLAVOR}.
		 *
		 * @see Fetcher
		 * @param fetcher
		 *            The {@link Fetcher} that will return the data object
		 * @since 1.1
		 */
		public TransferableObject(final Fetcher fetcher) {
			this.fetcher = fetcher;
		} // end constructor

		/**
		 * Creates a new {@link TransferableObject} that will return the object
		 * that is returned by <var>fetcher</var>. Along with the
		 * {@link #DATA_FLAVOR} associated with this class, this creates a
		 * custom data flavor with a representation class <var>dataClass</var>
		 * and the MIME type
		 * <code>application/x-net.iharder.dnd.TransferableObject</code>.
		 *
		 * @see Fetcher
		 * @param dataClass
		 *            The {@link java.lang.Class} to use in the custom data
		 *            flavor
		 * @param fetcher
		 *            The {@link Fetcher} that will return the data object
		 * @since 1.1
		 */
		public TransferableObject(final Class<?> dataClass, final Fetcher fetcher) {
			this.fetcher = fetcher;
			this.customFlavor = new java.awt.datatransfer.DataFlavor(dataClass,
					MIME_TYPE);
		} // end constructor

		/**
		 * Returns the custom {@link java.awt.datatransfer.DataFlavor}
		 * associated with the encapsulated object or <code>null</code> if the
		 * {@link Fetcher} constructor was used without passing a
		 * {@link java.lang.Class}.
		 *
		 * @return The custom data flavor for the encapsulated object
		 * @since 1.1
		 */
		public java.awt.datatransfer.DataFlavor getCustomDataFlavor() {
			return customFlavor;
		} // end getCustomDataFlavor

		/* ******** T R A N S F E R A B L E M E T H O D S ******** */

		/**
		 * Returns a two- or three-element array containing first the custom
		 * data flavor, if one was created in the constructors, second the
		 * default {@link #DATA_FLAVOR} associated with
		 * {@link TransferableObject}, and third the
		 * {@link java.awt.datatransfer.DataFlavor#stringFlavor}.
		 *
		 * @return An array of supported data flavors
		 * @since 1.1
		 */
		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
			if (customFlavor != null)
				return new java.awt.datatransfer.DataFlavor[] { customFlavor,
						DATA_FLAVOR,
						java.awt.datatransfer.DataFlavor.stringFlavor }; // end
																			// flavors
																			// array
			else
				return new java.awt.datatransfer.DataFlavor[] { DATA_FLAVOR,
						java.awt.datatransfer.DataFlavor.stringFlavor }; // end
																			// flavors
																			// array
		} // end getTransferDataFlavors

		/**
		 * Returns the data encapsulated in this {@link TransferableObject}. If
		 * the {@link Fetcher} constructor was used, then this is when the
		 * {@link Fetcher#getObject getObject()} method will be called. If the
		 * requested data flavor is not supported, then the
		 * {@link Fetcher#getObject getObject()} method will not be called.
		 *
		 * @param flavor
		 *            The data flavor for the data to return
		 * @return The dropped data
		 * @since 1.1
		 */
		@Override
		public Object getTransferData(final java.awt.datatransfer.DataFlavor flavor)
				throws java.awt.datatransfer.UnsupportedFlavorException,
				java.io.IOException {
			// Native object
			if (flavor.equals(DATA_FLAVOR))
				return fetcher == null ? data : fetcher.getObject();

			// String
			if (flavor.equals(java.awt.datatransfer.DataFlavor.stringFlavor))
				return fetcher == null ? data.toString() : fetcher.getObject()
						.toString();

			// We can't do anything else
			throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
		} // end getTransferData

		/**
		 * Returns <code>true</code> if <var>flavor</var> is one of the supported
		 * flavors. Flavors are supported using the <code>equals(...)</code>
		 * method.
		 *
		 * @param flavor
		 *            The data flavor to check
		 * @return Whether or not the flavor is supported
		 * @since 1.1
		 */
		@Override
		public boolean isDataFlavorSupported(
				final java.awt.datatransfer.DataFlavor flavor) {
			// Native object
			if (flavor.equals(DATA_FLAVOR))
				return true;

			// String
			if (flavor.equals(java.awt.datatransfer.DataFlavor.stringFlavor))
				return true;

			// We can't do anything else
			return false;
		} // end isDataFlavorSupported

		/* ******** I N N E R I N T E R F A C E F E T C H E R ******** */

		/**
		 * Instead of passing your data directly to the
		 * {@link TransferableObject} constructor, you may want to know exactly
		 * when your data was received in case you need to remove it from its
		 * source (or do anyting else to it). When the {@link #getTransferData
		 * getTransferData(...)} method is called on the
		 * {@link TransferableObject}, the {@link Fetcher}'s {@link #getObject
		 * getObject()} method will be called.
		 *
		 * @author Robert Harder
		 * @version 1.1
		 * @since 1.1
		 */
		public static interface Fetcher {
			/**
			 * Return the object being encapsulated in the
			 * {@link TransferableObject}.
			 *
			 * @return The dropped object
			 * @since 1.1
			 */
			public abstract Object getObject();
		} // end inner interface Fetcher

	} // end class TransferableObject

} // end class FileDrop
