/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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
import java.util.stream.IntStream;

import org.scijava.Context;
import org.scijava.NoSuchServiceException;
import org.scijava.io.IOService;
import org.scijava.io.location.FileLocation;
import org.scijava.table.Column;
import org.scijava.table.DefaultGenericTable;
import org.scijava.table.DefaultTableIOPlugin;
import org.scijava.table.DoubleColumn;
import org.scijava.table.GenericTable;
import org.scijava.table.Table;
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
	private DefaultTableIOPlugin tableIO;


	public SNTTable() {
		super();
	}

	public SNTTable(final String filePath) throws IOException {
		super();
		if (tableIO == null) {
			try (final Context context = new Context()) {  // Failure if new Context(IOservice.class); !?
				tableIO = context.getService(IOService.class).getInstance(DefaultTableIOPlugin.class);
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

	public void save(final File outputFile) throws IOException {
		if (tableIO == null) {
			SNTUtils.saveTable(this, ',', true, true, outputFile);
		} else {
			tableIO.save(this, outputFile.getAbsolutePath());
		}
		hasUnsavedData = false;
	}


	public static String toString(final GenericTable table, final int firstRow, final int lastRow) {
		final int fRow = Math.max(0, firstRow);
		final int lRow = Math.min(table.getRowCount() - 1, lastRow);
		final String sep = "\t";
		final StringBuilder sb = new StringBuilder();
		IntStream.range(0, table.getColumnCount()).forEach( col -> {
			sb.append(table.getColumnHeader(col)).append(sep);
		});
		sb.append("\n\r");
		IntStream.rangeClosed(fRow, lRow).forEach( row -> {
			IntStream.range(0, table.getColumnCount()).forEach( col -> {
				sb.append(table.get(col, row)).append(sep);
			});
			sb.append("\n\r");
		});
		return sb.toString().replaceAll("null", " ");
	}
}
