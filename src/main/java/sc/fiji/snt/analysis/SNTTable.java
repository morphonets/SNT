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

package sc.fiji.snt.analysis;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.Context;
import org.scijava.NoSuchServiceException;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.io.location.FileLocation;
import org.scijava.plugin.Parameter;
import org.scijava.table.Column;
import org.scijava.table.DefaultGenericTable;
import org.scijava.table.DoubleColumn;
import org.scijava.table.GenericTable;
import org.scijava.table.Table;
import org.scijava.table.io.DefaultTableIOPlugin;
import org.scijava.table.io.TableIOOptions;

import sc.fiji.snt.SNTUtils;

/**
 * Extension of {@code DefaultGenericTable} with (minor) scripting conveniences.
 *
 * @author Tiago Ferreira
 */
public class SNTTable extends DefaultGenericTable {

	private static final long serialVersionUID = 1L;
	private boolean hasUnsavedData;

	@Parameter
	private DefaultTableIOPlugin tableIO;

	@Parameter
	private DisplayService displayService;

	public SNTTable() {
		super();
	}

	public SNTTable(final String filePath) throws IOException {
		super();
		if (tableIO == null) {
			try { // Failure if new Context(IOservice.class); !?
				tableIO = SNTUtils.getContext().getService(IOService.class).getInstance(DefaultTableIOPlugin.class);
			} catch (final Exception e) {
				SNTUtils.error(e.getMessage(), e);
			}
		}
		if (tableIO == null) {
			throw new NoSuchServiceException("Failed to initialize IOService");
		}
		final TableIOOptions options = new TableIOOptions().readColumnHeaders(true).readColumnHeaders(true);
		final Table<?, ?> openedTable = tableIO.open(new FileLocation(filePath), options);
		for (int col = 0; col < openedTable.getColumnCount(); ++col) {
			add(openedTable.get(col));
		}
		hasUnsavedData = false;
	}

	protected void validate() {
		int maxRows = 0;
		for (int col = 0; col < getColumnCount(); ++col) {
			final int nRows = get(col).size();
			if (nRows > maxRows) maxRows = nRows;
		}
		for (int col = 0; col < getColumnCount(); ++col) {
			get(col).setSize(maxRows);
		}
	}

	public void fillEmptyCells(final Object value) {
		validate();
		for (int col = 0; col < getColumnCount(); ++col) {
			for (int row = 0; row < getRowCount(); ++row) {
				if (get(col, row) == null) {
					set(col, row, value);
				}
			}
		}
	}

	public boolean hasUnsavedData() {
		return getRowCount() > 0 && hasUnsavedData;
	}

	public void appendToLastRow(final String colHeader, final Object value) {
		if (getRowCount() == 0) appendRow();
		set(getCol(colHeader), getRowCount() - 1, value);
	}

	public void addColumn(final String colHeader, final double[] array) {
		final DoubleColumn col = new DoubleColumn(colHeader);
		col.fill(array);
		add(col);
	}

	public void set(final String colHeader, final String rowHeader, final Object value) {
		set(getCol(colHeader), getRow(rowHeader), value);
	}

	private int getRow(final String header) {
		int idx = getRowIndex(header);
		if (idx == -1) {
			appendRow(header);
			idx = getRowCount() - 1;
		}
		return idx;
	}

	@Override
	public void clear() {
		// Bypass super.clear not wiping row labels
		super.setRowCount(0);
		super.setColumnCount(0);
		super.clear();
	}

	public void addColumn(final String colHeader, final Collection<Double> array) {
		final DoubleColumn col = new DoubleColumn(colHeader);
		col.addAll(array);
		add(col);
	}

	public int insertRow(final String header) {
		appendRow(header);
		return Math.max(0, getRowCount() - 1);
	}

	public void set(final String colHeader, final int row, final Object value) {
		set(getCol(colHeader), row, value);
	}

	@Override
	public void set(final int col, final int row, final Object value) {
		super.set(col, row, value);
		hasUnsavedData = true;
	}

	private int getCol(final String header) {
		int idx = getColumnIndex(header);
		if (idx == -1) {
			appendColumn(header);
			idx = getColumnCount() - 1;
		}
		return idx;
	}

	public Column<? extends Object> removeColumn(final String header) {
		// do not throw exception if column not found
		return (getColumnIndex(header) == -1) ? null : super.removeColumn(header);
	}

	public boolean save(final String filePath) {
		if (filePath == null || filePath.trim().isEmpty()) {
			throw new IllegalArgumentException("filePath is not valid");
		}
		try {
			final String fPath = (filePath.toLowerCase().endsWith(".csv")) ? filePath : filePath + ".csv";
			save(new File(fPath));
			return true;
		} catch (final ArrayIndexOutOfBoundsException | NullPointerException | IOException ignored) {
			return false;
		}
	}

	/**
	 * Sets a SciJava context to this table.
	 * @param context the SciJava application context
	 */
	public void setContext(final Context context) throws IllegalArgumentException {
		context.inject(this);
		if (tableIO == null)
			tableIO = context.getService(IOService.class).getInstance(DefaultTableIOPlugin.class);
	}

	public boolean isSummarized() {
		final int meanRowIdx = getRowIndex("Mean");
		return meanRowIdx > 1 && meanRowIdx <= getRowCount() - 6 && getRowIndex("SD") == meanRowIdx+1;
	}

	public void removeSummary() {
		if (isSummarized()) removeRows( getRowIndex("Mean")-1, 8);
	}

	public SummaryStatistics geRowStats(final String rowHeader, final int startColumnIndex, final int endColumnIndex) {
		final int row = this.getRowIndex(rowHeader);
		if (row == -1)
			throw new IllegalArgumentException("Row header not found");
		return geRowStats(row, startColumnIndex, endColumnIndex);
	}

	public SummaryStatistics geRowStats(final int rowIndex, final int startColumnIndex, final int endColumnIndex) {
		if (rowIndex < 0 || rowIndex >= getRowCount() || startColumnIndex < 0 || endColumnIndex >= getColumnCount())
			throw new IllegalArgumentException("Column index, start column, or end column out of range");
		final SummaryStatistics rStats = new SummaryStatistics();
		for (int col = startColumnIndex; col <= endColumnIndex; col++)
			addValueToStats(col, rowIndex, rStats);
		return rStats;

	}
	public SummaryStatistics geColumnStats(final String colHeader, final int startRowIndex, final int endRowIndex) {
		final int col = getColumnIndex(colHeader);
		if (col == -1)
			throw new IllegalArgumentException("Column header not found");
		return geColumnStats(col, startRowIndex, endRowIndex);
	}

	public SummaryStatistics geColumnStats(final int columnIndex, final int startRowIndex, final int endRowIndex) {
		if (columnIndex < 0 || columnIndex >= getColumnCount() || startRowIndex < 0 || endRowIndex >= getRowCount())
			throw new IllegalArgumentException("Column index, start row or end row out of range");
		final SummaryStatistics cStats = new SummaryStatistics();
		for (int row = startRowIndex; row <= endRowIndex; row++)
			addValueToStats(columnIndex, row, cStats);
		return cStats;
	}

	private void addValueToStats(final int col, final int row, final SummaryStatistics stats) {
		try {
			final double value = ((Number) get(col, row)).doubleValue();
			if (!Double.isNaN(value)) stats.addValue(value);
		} catch (final NullPointerException ignored) {
			// do nothing. Empty cell!?
		} catch (final ClassCastException ignored) {
			// Cell with text!? We could add Double.NAN, or skip it altogether
			// skipping for now, in case cells above and below are valid
		}
	}

	public void summarize() {
		if (getRowCount() < 2 || getColumnCount() < 1)
			return;
		final int indexOfLastSummaryRow = getRowIndex("Sum");
		final boolean summaryExists = indexOfLastSummaryRow > -1;
		// if no summary exists, summarize from first row onwards, otherwise
		// from two rows below the last "Sum" row (ie, below its spacer row)
		final int firstRowToBeSummarized = (summaryExists) ? indexOfLastSummaryRow + 2 : 0;
		if (firstRowToBeSummarized == getRowCount())
			return;
		final SummaryStatistics[] sStas = new SummaryStatistics[getColumnCount()];
		for (int col = 0; col < getColumnCount(); col++) {
			sStas[col] = geColumnStats(col, firstRowToBeSummarized, getRowCount()-1);
		}
		final int lastRowIndex = getRowCount();
		insertRows(getRowCount(), new String[] { " ", "Mean", "SD", "N", "Min", "Max", "Sum", " " });
		for (int col = 0; col < getColumnCount(); col++) {
			final double min = sStas[col].getMin();
			final double max = sStas[col].getMax();
			final boolean nonNumericColumn = Double.isNaN(min) && Double.isNaN(max);
			set(col, lastRowIndex + 1, (nonNumericColumn) ? "" : sStas[col].getMean());
			set(col, lastRowIndex + 2, (nonNumericColumn) ? "" : sStas[col].getStandardDeviation());
			set(col, lastRowIndex + 3, (nonNumericColumn) ? "" : sStas[col].getN());
			set(col, lastRowIndex + 4, (nonNumericColumn) ? "" : min);
			set(col, lastRowIndex + 5, (nonNumericColumn) ? "" : max);
			set(col, lastRowIndex + 6, (nonNumericColumn) ? "" : sStas[col].getSum());
		}
		hasUnsavedData = true;
	}

	public void save(final File outputFile) throws IOException {
		if (tableIO == null) {
			SNTUtils.saveTable(this, ',', true, true, outputFile);
		} else {
			tableIO.save(this, outputFile.getAbsolutePath());
		}
		hasUnsavedData = false;
	}

	public void show() {
		createOrUpdateDisplay();
	}

	public void show(final String windowTitle) {
		createDisplay(windowTitle);
	}

	private void createDisplay(final String windowTitle) {
		initDisplayService();
		displayService.createDisplay(windowTitle, this);
	}

	public void updateDisplay() {
		updateDisplay(false);
	}

	public void createOrUpdateDisplay() {
		updateDisplay(true);
	}

	private void updateDisplay(final boolean createDisplayAsNeeded) {
		initDisplayService();
		final List<Display<?>> displays = displayService.getDisplays(this);
		if (displays == null || displays.isEmpty()) {
			if (createDisplayAsNeeded) createDisplay("SNT Measurements");
			return;
		}
		displays.forEach(d -> {
			if (d != null) d.update();
		});
	}

	private void initDisplayService() {
		if (displayService == null) {
			try {
				displayService = SNTUtils.getContext().getService(DisplayService.class);
			} catch (final Exception e) {
				SNTUtils.error(e.getMessage(), e);
			}
		}
	}

//	@Override
//	public String toString() {
//		return toString(this, 0, getRowCount() - 1);
//	}

	public static String toString(final GenericTable table) {
		return toString(table, 0, table.getRowCount() - 1);
	}

	public static String toString(final GenericTable table, final int firstRow, final int lastRow) {
		final int fRow = Math.max(0, firstRow);
		final int lRow = Math.min(table.getRowCount() - 1, lastRow);
		final String sep = "\t";
		final StringBuilder sb = new StringBuilder();
		final boolean hasRowHeaders = table.getRowCount() > 0 && table.getRowHeader(0) != null;
		if (hasRowHeaders)
			sb.append("-").append(sep); // column header for row labels
		IntStream.range(0, table.getColumnCount()).forEach( col -> {
			sb.append(table.getColumnHeader(col)).append(sep);
		});
		sb.append("\n\r");
		IntStream.rangeClosed(fRow, lRow).forEach( row -> {
			if (hasRowHeaders)
				sb.append(table.getRowHeader(row)).append(sep);
			IntStream.range(0, table.getColumnCount()).forEach( col -> {
				sb.append(table.get(col, row)).append(sep);
			});
			sb.append("\n\r");
		});
		return sb.toString().replaceAll("null", " ");
	}
}
