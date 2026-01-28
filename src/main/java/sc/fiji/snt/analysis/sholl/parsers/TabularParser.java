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
 * A {@link Parser} for extracting Sholl profiles from tabular data.
 * <p>
 * TabularParser processes data from tables (CSV files, ResultsTables, etc.) containing
 * radial distance and intersection count columns to generate Sholl analysis profiles.
 * This is useful for analyzing pre-computed Sholl data or importing results from
 * external analysis tools.
 * </p>
 * <p>
 * The parser supports both ImageJ1 ({@link ij.measure.ResultsTable}) and ImageJ2
 * ({@link org.scijava.table.DoubleTable}) table formats, and can automatically
 * detect spatial calibration units from column headers.
 * </p>
 * Example usage:
 * <pre>
 * // Parse from CSV file
 * TabularParser parser = new TabularParser("data.csv", "Distance_um", "Intersections");
 * parser.parse();
 * Profile profile = parser.getProfile();
 * 
 * // Parse subset of rows from ResultsTable
 * TabularParser parser2 = new TabularParser(resultsTable, "Radius", "Count", 5, 25);
 * parser2.parse();
 * </pre>
 *
 * @author Tiago Ferreira
 * @see Parser
 * @see Profile
 */
public class TabularParser implements Parser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

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

	/**
	 * Constructs a TabularParser from a table file.
	 * <p>
	 * Loads and parses a table file (CSV, TSV, etc.) containing Sholl analysis data.
	 * The file should have columns for radial distances and intersection counts.
	 * </p>
	 *
	 * @param table the File containing the tabular data
	 * @param radiiColumnHeader the header name of the column containing radial distances
	 * @param countsColumnHeader the header name of the column containing intersection counts
	 * @throws IOException if the file cannot be read or parsed
	 * @throws IllegalArgumentException if the specified column headers are not found
	 */
	public TabularParser(final File table, final String radiiColumnHeader, final String countsColumnHeader)
			throws IOException {
		this(ResultsTable.open(table.getAbsolutePath()), radiiColumnHeader, countsColumnHeader, -1, -1);
		tableName = table.getName();
	}

	/**
	 * Constructs a TabularParser from a table file path.
	 * <p>
	 * Convenience constructor that accepts a file path string instead of a File object.
	 * </p>
	 *
	 * @param filePath the path to the file containing the tabular data
	 * @param radiiColumnHeader the header name of the column containing radial distances
	 * @param countsColumnHeader the header name of the column containing intersection counts
	 * @throws IOException if the file cannot be read or parsed
	 * @throws IllegalArgumentException if the specified column headers are not found
	 */
	public TabularParser(final String filePath, final String radiiColumnHeader, final String countsColumnHeader)
			throws IOException {
		this(new File(filePath), radiiColumnHeader, countsColumnHeader);
	}

	/**
	 * Constructs a TabularParser from an ImageJ1 ResultsTable with row range specification.
	 * <p>
	 * This constructor allows parsing a specific subset of rows from the table,
	 * which is useful for analyzing partial data or excluding outliers.
	 * </p>
	 *
	 * @param table the ImageJ1 ResultsTable containing the data
	 * @param radiiColumnHeader the header name of the column containing radial distances
	 * @param countsColumnHeader the header name of the column containing intersection counts
	 * @param startRow the first row to include in parsing (0-based, -1 for start of table)
	 * @param endRow the last row to include in parsing (0-based, -1 for end of table)
	 * @throws IllegalArgumentException if the table is null/empty or column headers are not found
	 */
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

	/**
	 * Constructs a TabularParser from an ImageJ2 ResultsTable.
	 * <p>
	 * Convenience constructor for ImageJ2 ResultsTable objects.
	 * </p>
	 *
	 * @param table the ImageJ2 ResultsTable containing the data
	 * @param radiiColumnHeader the header name of the column containing radial distances
	 * @param countsColumnHeader the header name of the column containing intersection counts
	 * @throws IllegalArgumentException if the table is null/empty or column headers are not found
	 */
	public TabularParser(final net.imagej.table.ResultsTable table, final String radiiColumnHeader,
			final String countsColumnHeader) {
		this((DoubleTable)table, radiiColumnHeader, countsColumnHeader);
	}

	/**
	 * Constructs a TabularParser from a DoubleTable.
	 * <p>
	 * This constructor works with SciJava DoubleTable objects, providing compatibility
	 * with the ImageJ2 table framework.
	 * </p>
	 *
	 * @param table the DoubleTable containing the data
	 * @param radiiColumnHeader the header name of the column containing radial distances
	 * @param countsColumnHeader the header name of the column containing intersection counts
	 * @throws IllegalArgumentException if the table is null/empty or column headers are not found
	 */
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

	/**
	 * Parses the tabular data to extract the Sholl profile.
	 * <p>
	 * This method reads the specified columns from the table, creates ProfileEntry
	 * objects for each row, and builds a complete Sholl profile. It also attempts
	 * to automatically detect spatial calibration units from the radii column header.
	 * </p>
	 * The parsing process:
	 * <ol>
	 * <li>Creates a new Profile object</li>
	 * <li>Reads data from the specified radii and counts columns</li>
	 * <li>Creates ProfileEntry objects for each data row</li>
	 * <li>Sets profile metadata and properties</li>
	 * <li>Attempts to detect and set spatial calibration</li>
	 * </ol>
	 */
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
			final ProfileEntry entry = new ProfileEntry(radii[i], counts[i]);
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
			final ProfileEntry entry = new ProfileEntry(radiiColumn.get(i), countsColumn.get(i));
			profile.add(entry);
			if (!running)
				break;
		}
	}

	private Calibration guessCalibrationFromHeading(final String colHeading) {
		if (colHeading == null)
			return null;
		final String[] tokens = colHeading.toLowerCase().split("\\W");
		final String[] knownUnits = "µm um micron microns mm cm pixels".split(" ");
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

    @SuppressWarnings("unused")
	public void restrictToSubset(final int firstRow, final int lastRow) {
		if (successful())
			throw new UnsupportedOperationException("restrictToSubset() must be called before parsing data");
		this.startRow = firstRow;
		this.endRow = lastRow;
	}

	@Override
	public boolean successful() {
		return profile != null && !profile.isEmpty();
	}

	@Override
	public void terminate() {
		running = false;
	}

	@Override
	public Profile getProfile() {
        if (profile == null) parse();
        return profile;
	}

	public static void main(final String... args) {
		final TabularParser parser = new TabularParser(ShollUtils.csvSample(), "radii_um", "counts");
		parser.parse();
		parser.getProfile().plot().show();
	}

}
