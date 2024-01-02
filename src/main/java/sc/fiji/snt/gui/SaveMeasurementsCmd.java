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

package sc.fiji.snt.gui;

import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.display.DisplayService;
import org.scijava.log.LogService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.TableDisplay;
import org.scijava.table.io.TableIOOptions;
import org.scijava.table.io.TableIOService;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;

import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.PlotWindow;
import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

/**
 * Implements the 'Save Tables and Analysis Plots...' command: A single prompt for saving all
 * tables, plots and charts currently open.
 *
 * @author Tiago Ferreira
 */
@Plugin(initializer = "init", type = Command.class, label = "Save Tables & Analysis Plots...")
public class SaveMeasurementsCmd extends CommonDynamicCmd {

	private static final int MAX_N = 5;

	// HACK: Currently there is no scijava widget for multi-choice selection:
	// Workaround by assembling list of checkboxes
	@Parameter(label = "<HTML><b>Table(s) to Save:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER_TABLE1;
	@Parameter(required = false, persist = false)
	private boolean sciTable1;
	@Parameter(required = false, persist = false)
	private boolean sciTable2;
	@Parameter(required = false, persist = false)
	private boolean sciTable3;
	@Parameter(required = false, persist = false)
	private boolean sciTable4;
	@Parameter(required = false, persist = false)
	private boolean sciTable5;
	@Parameter(required = false, persist = false)
	private boolean ij1Table1;
	@Parameter(required = false, persist = false)
	private boolean ij1Table2;
	@Parameter(required = false, persist = false)
	private boolean ij1Table3;
	@Parameter(required = false, persist = false)
	private boolean ij1Table4;
	@Parameter(required = false, persist = false)
	private boolean ij1Table5;

	@Parameter(label = "<HTML><br><b>Plot(s) and Chart(s) to Save:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER_CHART1;
	@Parameter(required = false, persist = false)
	private boolean sntChart1;
	@Parameter(required = false, persist = false)
	private boolean sntChart2;
	@Parameter(required = false, persist = false)
	private boolean sntChart3;
	@Parameter(required = false, persist = false)
	private boolean sntChart4;
	@Parameter(required = false, persist = false)
	private boolean sntChart5;
	@Parameter(required = false, persist = false)
	private boolean plotWin1;
	@Parameter(required = false, persist = false)
	private boolean plotWin2;
	@Parameter(required = false, persist = false)
	private boolean plotWin3;
	@Parameter(required = false, persist = false)
	private boolean plotWin4;
	@Parameter(required = false, persist = false)
	private boolean plotWin5;

	@Parameter(label = "<HTML><br><b>Table Saving Options:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER_TABLE2;

	@Parameter(required = false, label = "Column delimiter", choices = { "Comma", "Space", "Semicolon",
			"Tab" }, style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE, 
			description = "<HTML>NB: 'Space' and 'Semicolon' are not supported by ImageJ1 tables")
	private String columnDelimiter;

	@Parameter(required = false, label = "Save column headers")
	private boolean writeColHeaders = true;

	@Parameter(required = false, label = "Save row headers")
	private boolean writeRowHeaders = true;

	@Parameter(label = "<HTML><br><b>Destination and I/O Options:", required = false, visibility = ItemVisibility.MESSAGE)
	private String HEADER_GENERAL;

	@Parameter(label = "Directory", required = false, style = FileWidget.DIRECTORY_STYLE,
			description = "<HTML>Saving directory (will be created if it does not exist)")
	private File outputFile;

	@Parameter(required = false, label = "Override existing file(s)",
			description = "<HTML>Override data if similar files exist in the directory?<br>"
					+ "Otherwise, assign a unique sufix to filenames so that no data is overridden")
	private boolean override;

	@Parameter(required = false, label = "Close after saving",
			description = "<HTML>Close file(s) after being successfully saved?")
	private boolean close = true;

	@Parameter
	private LogService log;

	@Parameter
	private DisplayService displayService;

	@Parameter
	private TableIOService tableIO;

	private List<TableDisplay> sciTables;
	private List<IJ1Table> ij1Tables;
	private List<SNTChart> sntCharts;
	private List<PlotWindow> ij1Plots;
	private int nSciTables;
	private int nIJ1Tables;
	private int nSNTCharts;
	private int nIJ1Plots;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);

		// get SciJava and IJ1 tables
		sciTables = displayService.getDisplaysOfType(TableDisplay.class);
		nSciTables = Math.min(MAX_N, (sciTables == null) ? 0 : sciTables.size());
		ij1Tables = getIJ1Tables();
		nIJ1Tables = Math.min(MAX_N, ij1Tables.size());

		// get SNTCharts and IJ1Plots
		sntCharts = SNTChart.openCharts();
		nSNTCharts = Math.min(MAX_N, sntCharts.size());
		ij1Plots = getIJ1Plots();
		nIJ1Plots = Math.min(MAX_N, ij1Plots.size());

		final boolean tablesExist = nSciTables + nIJ1Tables > 0;
		final boolean plotsExist = nSNTCharts + nIJ1Plots > 0;

		if (!(tablesExist || plotsExist)) {
			error("No table(s) or plot(s) seem to be currently open.");
			getInputs().keySet().forEach(k -> resolveInput(k));
			return;
		}

		if (!tablesExist) {
			resolveInput("HEADER_TABLE1");
			resolveInput("HEADER_TABLE2");
			resolveInput("columnDelimiter");
			resolveInput("writeColHeaders");
			resolveInput("writeRowHeaders");
		}
		if (!plotsExist) {
			resolveInput("HEADER_CHART1");
		}

		// set SciJavaTables
		for (int i = 0; i < nSciTables; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sciTable" + (i + 1), Boolean.class);
			input.setLabel(getTableLabel(sciTables.get(i)));
			input.setDescription("SciJava table. Will be saved with a .CSV extension independently of chosen delimiter");
			input.setValue(this, true);
		}
		for (int i = sciTables.size(); i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sciTable" + (i + 1), Boolean.class);
			input.setValue(this, false);
			resolveInput("sciTable" + (i + 1));
		}
		// set IJ1 tables
		for (int i = 0; i < nIJ1Tables; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("ij1Table" + (i + 1), Boolean.class);
			input.setLabel(ij1Tables.get(i).title);
			input.setDescription("ImageJ1 table. Will be saved with a .CSV or .TSV extension");
			input.setValue(this, true);
		}
		for (int i = ij1Tables.size(); i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("ij1Table" + (i + 1), Boolean.class);
			input.setValue(this, false);
			resolveInput("ij1Table" + (i + 1));
		}
		// set SNTCharts
		for (int i = 0; i < nSNTCharts; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sntChart" + (i + 1), Boolean.class);
			input.setLabel(sntCharts.get(i).getTitle());
			input.setDescription("SNTChart: Will be saved as PNG. Right-click on chart for other saving options");
			input.setValue(this, true);
		}
		for (int i = sntCharts.size(); i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sntChart" + (i + 1), Boolean.class);
			input.setValue(this, false);
			resolveInput("sntChart" + (i + 1));
		}
		// set PlotWindows
		for (int i = 0; i < nIJ1Plots; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("plotWin" + (i + 1), Boolean.class);
			input.setLabel(ij1Plots.get(i).getTitle());
			input.setDescription("ImageJ Plot: Will be saved as TIFF");
			input.setValue(this, true);
		}
		for (int i = ij1Plots.size(); i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("plotWin" + (i + 1), Boolean.class);
			input.setValue(this, false);
			resolveInput("plotWin" + (i + 1));
		}

		// prettify prompt
		if (nSciTables + nIJ1Tables == 1 && !plotsExist) {
			resolveInput("HEADER_TABLE1");
			resolveInput("sciTable1");
			resolveInput("ij1Table1");
		}
		if (nSNTCharts + nIJ1Plots == 1 && !tablesExist) {
			resolveInput("HEADER_CHART1");
			resolveInput("sntChart1");
			resolveInput("plotWin1");
		}
		if (nSciTables + nIJ1Tables + nSNTCharts + nIJ1Plots == 1) {
			if (nSciTables == 1)
				getInfo().setLabel("Save " + getTableLabel(sciTables.get(0)) + "...");
			else if (nIJ1Tables == 1)
				getInfo().setLabel("Save " + ij1Tables.get(0).title + "...");
			else if (nSNTCharts == 1)
				getInfo().setLabel("Save " + sntCharts.get(0).getTitle() + "...");
			else if (nIJ1Plots == 1)
				getInfo().setLabel("Save " + ij1Plots.get(0).getTitle() + "...");
		}

		// adjust destination
		if (snt == null)
			outputFile = new File(System.getProperty("user.home"));
		else
			outputFile = snt.getPrefs().getRecentDir();
		if (outputFile.isFile())
			outputFile = outputFile.getParentFile();
	}

	private List<IJ1Table> getIJ1Tables() {
		ij1Tables = new ArrayList<>();
		for (final Frame w : WindowManager.getNonImageWindows()) {
			if (w instanceof ij.text.TextWindow) {
				ij.text.TextWindow rtWindow = (ij.text.TextWindow) w;
				ij.measure.ResultsTable rt = rtWindow.getTextPanel().getResultsTable();
				if (rt != null) {
					ij1Tables.add(new IJ1Table(rtWindow.getTitle(), rt));
				}
			}
		}
		return ij1Tables;
	}

	private List<PlotWindow> getIJ1Plots() {
		ij1Plots = new ArrayList<>();
		final int[] ids = WindowManager.getIDList();
		if (ids != null) {
			for (final int id : WindowManager.getIDList()) {
				final ImageWindow win = WindowManager.getImage(id).getWindow();
				if (win != null && win instanceof PlotWindow) {
					ij1Plots.add((PlotWindow) win);
				}
			}
		}
		return ij1Plots;
	}

	private String getTableLabel(final TableDisplay tableDisplay) {
		final String name = tableDisplay.getName();
		return (name == null) ? tableDisplay.getIdentifier() : name;
	}

	private boolean saveTable(final TableDisplay tableDisplay) {
		final File file = getFile(getTableLabel(tableDisplay) + ".csv");
		SNTUtils.log("Saving SciJava table: " + file);
		try { // FIXME: This seems to only accept csv as extension
			try {
				final TableIOOptions options = new TableIOOptions().writeColumnHeaders(writeColHeaders)
						.writeRowHeaders(writeRowHeaders).columnDelimiter(getDelimiter(columnDelimiter));
				tableIO.save(tableDisplay.get(0), file.getAbsolutePath(), options);
			} catch (final UnsupportedOperationException exc1) {
				// TODO: this should no longer be needed once this is solved:
				// https://forum.image.sc/t/unsupportedoperationexception-defaulttableioservice/54197/
				SNTUtils.saveTable(tableDisplay.get(0), getDelimiter(columnDelimiter),
						writeColHeaders, writeRowHeaders, file);
			}
			if (close)
				tableDisplay.close();
		} catch (final IOException exc) {
			log.error("\t" + exc.getMessage());
			return false;
		}
		return true;
	}

	private boolean saveTable(final IJ1Table ij1Table, final String ext) {
		final File file = getFile(ij1Table.title + ext);
		SNTUtils.log("Saving IJ1 table: " + file);
		try {
			// NB: The only way to change col separators is to change file extension
			ij1Table.rt.saveColumnHeaders(writeColHeaders);
			ij1Table.rt.showRowNumbers(writeRowHeaders);
			ij1Table.rt.saveAs(file.getAbsolutePath());
			if (close) {
				final Window win = WindowManager.getWindow(ij1Table.title);
				if (win != null && win instanceof ij.text.TextWindow) {
					((ij.text.TextWindow)win).close();
				}
			}
		} catch (final IOException exc) {
			log.error("\t" + exc.getMessage());
			return false;
		}
		return true;
	}

	private boolean saveChart(final SNTChart sntChart) {
		final File file = getFile(sntChart.getTitle() + ".png");
		SNTUtils.log("Saving SNTChart: " + file);
		try {
			sntChart.saveAsPNG(file);
			if (close)
				sntChart.dispose();
		} catch (final IOException exc) {
			log.error("\t" + exc.getMessage());
			return false;
		}
		return true;
	}

	private boolean savePlotWindow(final PlotWindow pw) {
		final String filename = pw.getTitle().endsWith(".tif") ? pw.getTitle() : pw.getTitle() + ".tif";
		File file = (outputFile.isDirectory()) ? new File(outputFile, filename) : outputFile;
		SNTUtils.log("Saving PlotWindow: " + file);
		if (!override)
			file = SNTUtils.getUniquelySuffixedTifFile(file);
		final boolean result = ij.IJ.saveAsTiff(pw.getImagePlus(), file.getAbsolutePath());
		if (!result)
			log.error("\tCould not save " + file.getAbsolutePath());
		else if (close)
			pw.close();
		return result;
	}

	private File getFile(final String proposedFilename) {
		File file = (outputFile.isDirectory()) ? new File(outputFile, proposedFilename) : outputFile;
		if (!override)
			file = SNTUtils.getUniquelySuffixedFile(file);
		return file;
	}

	private char getDelimiter(final String descriptor) {
		switch (descriptor.toLowerCase()) {
		case "tab":
			return '\t';
		case "semicolon":
			return ';';
		case "space":
			return ' ';
		default:
			return ',';
		}
	}

	private String getTableExtension(final String delimiterDescriptor) {
		if (delimiterDescriptor != null && delimiterDescriptor.equalsIgnoreCase("tab"))
			return ".tsv";
		return ".csv";
	}

	@Override
	public void run() {
		if (nSciTables + nIJ1Tables + nSNTCharts + nIJ1Plots == 0)
			return;
		if (outputFile == null) {
			error("Specified path is not valid.");
			return;
		}
		try {
			outputFile.mkdirs();
		} catch (final SecurityException ignored) {
			error("Directory does not seem to be writable...");
			return;
		}
		int failures = 0;
		for (int i = 0; i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sciTable" + (i + 1), Boolean.class);
			if (input.getValue(this) && !saveTable(sciTables.get(i))) {
				failures++;
			}
		}
		final String ext = getTableExtension(columnDelimiter);
		for (int i = 0; i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("ij1Table" + (i + 1), Boolean.class);
			if (input.getValue(this) && !saveTable(ij1Tables.get(i), ext)) {
				failures++;
			}
		}
		for (int i = 0; i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("sntChart" + (i + 1), Boolean.class);
			if (input.getValue(this) && !saveChart(sntCharts.get(i))) {
				failures++;
			}
		}
		for (int i = 0; i < MAX_N; i++) {
			final MutableModuleItem<Boolean> input = getInfo().getMutableInput("plotWin" + (i + 1), Boolean.class);
			if (input.getValue(this) && !savePlotWindow(ij1Plots.get(i))) {
				failures++;
			}
		}
		if (failures > 0) {
			error(String.format("%d table(s) could not be saved. See Console for details.", failures));
		} else {
			status("Tables Successful saved ", true);
		}
		if (snt != null)
			snt.getPrefs().setRecentDir(outputFile);
	}

	private static class IJ1Table {
		private ij.measure.ResultsTable rt;
		private String title;

		private IJ1Table(final String title, final ij.measure.ResultsTable rt) {
			this.title = title;
			this.rt = rt;
		}

	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		IntStream.range(1, 2).forEach(i -> {
			ij.display().createDisplay("SNTTable " + i, new SNTTable());
			new ij.measure.ResultsTable().show("ResultsTable " + i);
			ShollUtils.demoProfile().plot().show();
			ij.context().getService(SNTService.class).demoTree("fractal").show2D();
		});
		ij.command().run(SaveMeasurementsCmd.class, true, (Map<String, Object>) null);
	}
}
