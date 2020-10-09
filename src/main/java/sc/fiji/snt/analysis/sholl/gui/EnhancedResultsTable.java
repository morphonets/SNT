/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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
package sc.fiji.snt.analysis.sholl.gui;

import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import ij.IJ;
import ij.ImageJ;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import ij.plugin.MeasurementsWriter;
import ij.text.TextWindow;
import sc.fiji.snt.gui.EnhancedGenericDialog;

/**
 * Slight improvements to {@link ij.measure.ResultsTable}. This class is only
 * used by IJ1 plugins and is thus deprecated.
 * @deprecated Use {@linkplain ShollTable} instead
 */
@Deprecated
public class EnhancedResultsTable extends ResultsTable {

	private boolean unsavedMeasurements;
	private boolean listenerAdded;
	private boolean isShowing;
	private String title;

	/**
	 * Calls the default {@code incrementCounter()} while monitoring unsaved
	 * measurements
	 */
	@Override
	public synchronized void incrementCounter() {
		super.incrementCounter();
		setUnsavedMeasurements(true);
	}

	/**
	 * Deletes the specified row and setting the unsaved measurements flag.
	 */
	@Override
	public synchronized void deleteRow(final int row) {
		super.deleteRow(row);
		setUnsavedMeasurements(true);
	}

	/**
	 * Calls the default {@code show()} method while attaching a WindowListener
	 * used to prompt users to save unsaved measurements when closing
	 * (non-programmatically) the ResultsTable window
	 *
	 * @param windowTitle
	 *            the title of the window displaying the ResultsTable
	 */
	@Override
	public void show(final String windowTitle) {

		super.show(windowTitle);

		if (listenerAdded)
			return;
		final TextWindow window = getWindow(windowTitle);
		if (window == null)
			return;
		window.addWindowListener(new WindowAdapter() {
			@Override
			public void windowDeactivated(final WindowEvent e) {
				final TextWindow tw = (TextWindow) e.getWindow();
				if (tw != null)
					title = tw.getTitle();
			}

			@Override
			public void windowClosed(final WindowEvent e) {
				isShowing = false;
			}

			@Override
			public void windowClosing(final WindowEvent e) {
				try {
					final TextWindow tw = (TextWindow) e.getWindow();
					final EnhancedResultsTable ert = (EnhancedResultsTable) tw.getTextPanel().getResultsTable();
					if (!(ert.getUnsavedMeasurements() && ert.getCounter() > 0))
						return;
					final ImageJ ij = IJ.getInstance();
					final boolean macro = (IJ.macroRunning()) || Interpreter.isBatchMode();
					if (!macro && ij != null && !ij.quitting()) {
						promptForSave(windowTitle);
					}
				} catch (final Exception exc) {
					IJ.log(">>>> An error occurred when closing table:\n" + exc);
				}
			}

		});
		listenerAdded = true;
		isShowing = true;
	}

	/**
	 * Updates the ResultsTable and displays it in its own window if this
	 * ResultsTable is already being displayed, otherwise displays the contents
	 * of this ResultsTable in new window with the specified title.
	 *
	 * @param fallBackTitle
	 *            The window title of the new TextWindow to be opened if current
	 *            table is not being displayed
	 *
	 */
	public void update(final String fallBackTitle) {
		if (title != null && isShowing()) {
			listenerAdded = true;
			show(title);
		} else {
			listenerAdded = false;
			show(fallBackTitle);
		}
	}

	@Override
	public boolean save(final String path) {
		final boolean result = super.save(path);
		setUnsavedMeasurements(!result);
		return result;
	}

	private synchronized boolean promptForSave(final String tableTitle) {
		final GenericDialog gd = new GenericDialog("Unsaved Data");
		gd.addMessage("Save measurements in " + tableTitle + "?", new Font("SansSerif", Font.BOLD, 12));
		gd.addMessage("Data will be discarded if you dismiss this prompt!", new Font("SansSerif", Font.PLAIN, 12),
				EnhancedGenericDialog.getDisabledComponentColor());
		gd.setCancelLabel("No. Discard measurements");
		gd.setOKLabel("Yes. Save to...");
		gd.showDialog();
		if (gd.wasOKed() && (new MeasurementsWriter()).save("")) {
			setUnsavedMeasurements(false);
		}
		return false;
	}

	private TextWindow getWindow(final String title) {
		return (TextWindow) WindowManager.getWindow(title);
	}

	public void setUnsavedMeasurements(final boolean unsavedMeasurements) {
		this.unsavedMeasurements = unsavedMeasurements;
	}

	private boolean getUnsavedMeasurements() {
		return unsavedMeasurements;
	}

	public boolean isShowing() {
		return isShowing;
	}

}
