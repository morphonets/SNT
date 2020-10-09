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
package sc.fiji.snt.analysis.sholl.parsers;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.scijava.table.DoubleColumn;
import org.scijava.table.DoubleTable;

import ij.measure.Calibration;
import ij.measure.ResultsTable;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.ShollUtils;

/**
 * @author Tiago Ferreira
 *
 */
public class TabularParser implements Parser {

	private Profile profile;
	private final ij.measure.ResultsTable ij1table;
	private final DoubleTable ij2table;
	private final int radiiCol;
	private final int countsCol;
	private int startRow = -1;
	private int endRow = -1;
	private String tableName;
	private final String radiiColumnHeader;
	private volatile boolean running = true;

	public TabularParser(final File table, final String radiiColumnHeader, final String countsColumnHeader)
			throws IOException {
		this(ResultsTable.open(table.getAbsolutePath()), radiiColumnHeader, countsColumnHeader, -1, -1);
		tableName = table.getName();
	}

	public TabularParser(final String filePath, final String radiiColumnHeader, final String countsColumnHeader)
			throws IOException {
		this(new File(filePath), radiiColumnHeader, countsColumnHeader);
	}

	public TabularParser(final ij.measure.ResultsTable table, final String radiiColumnHeader,
			final String countsColumnHeader, final int startRow, final int endRow) {

		if (table == null || table.getCounter() == 0)
			throw new IllegalArgumentException("Table does not contain valid data");

		ij2table = null;
		ij1table = table;
		radiiCol = table.getColumnIndex(radiiColumnHeader);
		countsCol = table.getColumnIndex(countsColumnHeader);
		if (radiiCol == ResultsTable.COLUMN_NOT_FOUND || countsCol == ResultsTable.COLUMN_NOT_FOUND)
			throw new IllegalArgumentException(
					"Specified headings do not match existing ones: " + table.getColumnHeadings());
		this.radiiColumnHeader = radiiColumnHeader;
		this.startRow = startRow;
		this.endRow = endRow;
	}

	public TabularParser(final net.imagej.table.ResultsTable table, final String radiiColumnHeader,
			final String countsColumnHeader) {
		this((DoubleTable)table, radiiColumnHeader, countsColumnHeader);
	}

	public TabularParser(final DoubleTable table, final String radiiColumnHeader,
		final String countsColumnHeader) {
	if (table == null || table.isEmpty())
		throw new IllegalArgumentException("Table does not contain valid data");
	radiiCol = table.getColumnIndex(radiiColumnHeader);
	countsCol  = table.getColumnIndex(countsColumnHeader);
	if (radiiCol == -1 || countsCol == -1)
		throw new IllegalArgumentException("Specified headings do not match existing ones");
	ij1table = null;
	ij2table = table;
	this.radiiColumnHeader = radiiColumnHeader;
}

	@Override
	public void parse() {
		profile = new Profile();
		if (ij1table == null)
			buildProfileFromIJ2Table();
		else
			buildProfileFromIJ1Table();
		final Properties properties = new Properties();
		if (tableName != null)
			properties.setProperty(KEY_ID, tableName);
		properties.setProperty(KEY_SOURCE, SRC_TABLE);
		profile.setProperties(properties);
		final Calibration cal = guessCalibrationFromHeading(radiiColumnHeader);
		if (cal != null)
			profile.setSpatialCalibration(cal);

	}

	private int[] getFilteredRowRange(final int lastRow) {
		final int filteredStartRow = (startRow == -1) ? 0 : startRow;
		final int filteredEndRow = (endRow == -1) ? lastRow : endRow;
		if (filteredStartRow > filteredEndRow || filteredEndRow > lastRow)
			throw new IllegalArgumentException("Specified rows are out of range");
		return new int[] { filteredStartRow, filteredEndRow };
	}

	private void buildProfileFromIJ1Table() {
		final int lastRow = ij1table.getCounter() - 1;
		final int[] rowRange = getFilteredRowRange(lastRow);
		final double[] radii = ij1table.getColumnAsDoubles(radiiCol);
		final double[] counts = ij1table.getColumnAsDoubles(countsCol);
		for (int i = rowRange[0]; i <= rowRange[1]; i++) {
			final ProfileEntry entry = new ProfileEntry(radii[i], counts[i], null);
			profile.add(entry);
			if (!running)
				break;
		}
	}

	private void buildProfileFromIJ2Table() {
		final DoubleColumn radiiColumn = ij2table.get(radiiCol);
		final DoubleColumn countsColumn = ij2table.get(countsCol);
		if (radiiColumn == null || countsColumn == null)
			throw new IllegalArgumentException("Specified headings do not match existing ones");
		final int[] rowRange = getFilteredRowRange(ij2table.getRowCount() - 1);
		for (int i = rowRange[0]; i <= rowRange[1]; i++) {
			final ProfileEntry entry = new ProfileEntry(radiiColumn.get(i), countsColumn.get(i), null);
			profile.add(entry);
			if (!running)
				break;
		}
	}

	private Calibration guessCalibrationFromHeading(final String colHeading) {
		if (colHeading == null)
			return null;
		final String[] tokens = colHeading.toLowerCase().split("\\W");
		final String[] knownUnits = "\u00B5 um micron mm cm pixels".split(" ");
		for (final String token : tokens) {
			for (final String unit : knownUnits) {
				if (token.contains(unit)) {
					final Calibration cal = new Calibration();
					cal.setUnit(unit);
					return cal;
				}
			}
		}
		return null;
	}

	public void restrictToSubset(final int firstRow, final int lastRow) {
		if (successful())
			throw new UnsupportedOperationException("restrictToSubset() must be called before parsing data");
		this.startRow = firstRow;
		this.endRow = lastRow;
	}

	@Override
	public boolean successful() {
		return profile != null && profile.size() > 0;
	}

	@Override
	public void terminate() {
		running = false;
	}

	@Override
	public Profile getProfile() {
		return profile;
	}

	public static void main(final String... args) {
		final TabularParser parser = new TabularParser(ShollUtils.csvSample(), "radii_um", "counts");
		parser.parse();
		parser.getProfile().plot().show();
	}

}
