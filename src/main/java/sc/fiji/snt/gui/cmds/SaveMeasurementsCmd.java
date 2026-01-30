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

package sc.fiji.snt.gui.cmds;

import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.PlotWindow;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.display.DisplayService;
import org.scijava.module.DefaultMutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.TableDisplay;
import org.scijava.table.io.TableIOOptions;
import org.scijava.table.io.TableIOService;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.FileChooser;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves all open tables, plots, and charts to a specified directory.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Save Tables & Analysis Plots...", initializer = "init")
public class SaveMeasurementsCmd extends CommonDynamicCmd {

    private static final String TABLE_CATEGORY = "Tables";
    private static final String CHART_CATEGORY = "Charts & Plots";
    private final List<Saveable> saveables = new ArrayList<>();

    @Parameter(required = false, label = "<HTML><br><b>Table Options:", visibility = ItemVisibility.MESSAGE)
    private String HEADER_TABLE_OPTIONS;

    @Parameter(required = false, label = "Column delimiter", choices = {"Comma", "Tab", "Semicolon", "Space"},
            style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE,
            description = "<HTML>NB: 'Space' and 'Semicolon' are not supported by ImageJ1 tables")
    private String columnDelimiter = "Comma";

    @Parameter(required = false, label = "Save column headers")
    private boolean writeColHeaders = true;

    @Parameter(required = false, label = "Save row headers")
    private boolean writeRowHeaders = true;

    @Parameter(required = false, label = "<HTML><br><b>Output Options:", visibility = ItemVisibility.MESSAGE)
    private String HEADER_OUTPUT;

    @Parameter(required = false, label = "Directory", style = FileWidget.DIRECTORY_STYLE,
            description = "Saving directory (will be created if it does not exist)")
    private File outputDir;

    @Parameter(required = false, label = "Override existing files",
            description = "Override data if similar files exist in the directory?")
    private boolean override = false;

    @Parameter(required = false, label = "Close after saving",
            description = "Close file(s) after being successfully saved?")
    private boolean close = true;

	@Parameter(required = false, label = "Reveal directory",
			description = "Open directory after all files have been saved?")
	private boolean reveal = false;

    @Parameter
    private DisplayService displayService;
    @Parameter
    private TableIOService tableIOService;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.INVISIBLE)
    private boolean sntChartsOnly;

    private int headerCount = 0;


    @SuppressWarnings("unused")
    private void init() {
        super.init(false);

        if (sntChartsOnly) {
            collectSNTCharts();
            resolveInput("HEADER_TABLE_OPTIONS");
            resolveInput("columnDelimiter");
            resolveInput("writeColHeaders");
            resolveInput("writeRowHeaders");
        } else {
            // Collect all saveable items
            collectSciJavaTables();
            collectIJ1Tables();
            collectSNTCharts();
            collectIJ1Plots();
        }

        if (saveables.isEmpty()) {
			resolveAllInputs();
			error("No tables or charts are currently open.");
            return;
        }

        // Add checkboxes dynamically, grouped by category
        String currentCategory = "";
        for (int i = 0; i < saveables.size(); i++) {
            final Saveable s = saveables.get(i);
            if (!s.category.equals(currentCategory)) {
                addCategoryHeader(s.category);
                currentCategory = s.category;
            }
            addItemCheckbox(s, i);
        }

        // Set default directory
        if (snt != null) {
            outputDir = snt.getPrefs().getRecentDir();
        } else {
            outputDir = new File(System.getProperty("user.home"));
        }
        if (outputDir.isFile()) {
            outputDir = outputDir.getParentFile();
        }

        // Update label if only one item
        if (saveables.size() == 1) {
            getInfo().setLabel("Save " + saveables.getFirst().name + "...");
        }
    }

    private void resolveAllInputs() {
        getInputs().keySet().forEach(this::resolveInput);
    }

    private void collectSciJavaTables() {
        final List<TableDisplay> tables = displayService.getDisplaysOfType(TableDisplay.class);
        if (tables == null) return;
        for (final TableDisplay td : tables) {
            saveables.add(new SciJavaTableSaveable(td));
        }
    }

    private void collectIJ1Tables() {
        for (final Frame w : WindowManager.getNonImageWindows()) {
            if (w instanceof ij.text.TextWindow rtWindow) {
                final ij.measure.ResultsTable rt = rtWindow.getTextPanel().getResultsTable();
                if (rt != null) {
                    saveables.add(new IJ1TableSaveable(rtWindow.getTitle(), rt));
                }
            }
        }
    }

    private void collectSNTCharts() {
        for (final SNTChart chart : SNTChart.openCharts()) {
            saveables.add(new SNTChartSaveable(chart));
        }
    }

    private void collectIJ1Plots() {
        collectPlots().forEach( win -> saveables.add(new IJ1PlotSaveable(win)));
    }

    private void addCategoryHeader(final String category) {
        final DefaultMutableModuleItem<String> header = new DefaultMutableModuleItem<>(
                getInfo(), "categoryHeader" + (headerCount++), String.class);
        header.setLabel("<HTML><br><b>" + category + ":");
        header.setVisibility(ItemVisibility.MESSAGE);
        header.setPersisted(false);
        header.setRequired(false);
        getInfo().addInput(header);
    }

    private void addItemCheckbox(final Saveable s, final int index) {
        final DefaultMutableModuleItem<Boolean> item = new DefaultMutableModuleItem<>(
                getInfo(), "saveItem" + index, Boolean.class);
        item.setLabel(truncateLabel(s.name));
        item.setDescription(s.description);
        item.setValue(this, true);
        item.setPersisted(false);
        item.setRequired(false);
        getInfo().addInput(item);
    }

    private String truncateLabel(final String label) {
        if (label != null && label.length() > 80) {
            return label.substring(0, 78) + "...";
        }
        return label;
    }

    @Override
    public void run() {
        if (saveables.isEmpty()) return;

        if (outputDir == null || outputDir.getAbsolutePath().trim().isEmpty()) {
            error("Specified path is not valid.");
            return;
        }

        try {
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
        } catch (final SecurityException e) {
            error("Directory does not seem to be writable.");
            return;
        }

        int saved = 0;
        int failures = 0;

        for (int i = 0; i < saveables.size(); i++) {
            final Boolean selected = (Boolean) getInput("saveItem" + i);
            if (Boolean.TRUE.equals(selected)) {
                if (saveables.get(i).save()) {
                    saved++;
                } else {
                    failures++;
                }
            }
        }

        if (failures > 0) {
            error(String.format("%d of %d item(s) could not be saved. See Console for details.",
                    failures, saved + failures));
        } else if (saved > 0) {
            msg(String.format("%d item(s) saved to: %s", saved, outputDir.getAbsolutePath()), "Done");
        }

		if (reveal) {
			try {
				FileChooser.reveal(outputDir);
			} catch (final IOException ignored) {
				// do nothing
			}
		}
        if (snt != null) {
            snt.getPrefs().setRecentDir(outputDir);
			resetUI();
        }
    }

    private File getOutputFile(final String proposedFilename) {
        File file = new File(outputDir, proposedFilename);
        if (!override) {
            file = SNTUtils.getUniquelySuffixedFile(file);
        }
        return file;
    }

    private char getDelimiterChar() {
        return switch (columnDelimiter.toLowerCase()) {
            case "tab" -> '\t';
            case "semicolon" -> ';';
            case "space" -> ' ';
            default -> ',';
        };
    }

    private String getTableExtension() {
        return "tab".equalsIgnoreCase(columnDelimiter) ? ".tsv" : ".csv";
    }

    private String sanitizeFilename(final String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9.\\-]", "_");
    }

    private abstract static class Saveable {
        final String name;
        final String category;
        final String description;

        Saveable(final String name, final String category, final String description) {
            this.name = name;
            this.category = category;
            this.description = description;
        }

        abstract boolean save();
    }

    private class SciJavaTableSaveable extends Saveable {
        private final TableDisplay tableDisplay;

        SciJavaTableSaveable(final TableDisplay tableDisplay) {
            super(getTableDisplayName(tableDisplay), TABLE_CATEGORY,
                    "SciJava table. Will be saved as CSV.");
            this.tableDisplay = tableDisplay;
        }

        private static String getTableDisplayName(final TableDisplay td) {
            final String name = td.getName();
            return (name != null) ? name : td.getIdentifier();
        }

        @Override
        boolean save() {
            final File file = getOutputFile(name + ".csv");
            SNTUtils.log("Saving SciJava table: " + file);
            try {
                try {
                    final TableIOOptions options = new TableIOOptions()
                            .writeColumnHeaders(writeColHeaders)
                            .writeRowHeaders(writeRowHeaders)
                            .columnDelimiter(getDelimiterChar());
                    tableIOService.save(tableDisplay.getFirst(), file.getAbsolutePath(), options);
                } catch (final UnsupportedOperationException e) {
                    // Fallback for older scijava-table versions
                    SNTTable.save(tableDisplay.getFirst(), getDelimiterChar(),
                            writeColHeaders, writeRowHeaders, file);
                }
                if (close) {
                    tableDisplay.close();
                }
                return true;
            } catch (final IOException e) {
                SNTUtils.error("Failed to save table: " + name, e);
                return false;
            }
        }
    }

    private class IJ1TableSaveable extends Saveable {
        private final String title;
        private final ij.measure.ResultsTable rt;

        IJ1TableSaveable(final String title, final ij.measure.ResultsTable rt) {
            super(title, TABLE_CATEGORY, "ImageJ1 table. Will be saved as CSV/TSV.");
            this.title = title;
            this.rt = rt;
        }

        @Override
        boolean save() {
            final File file = getOutputFile(title + getTableExtension());
            SNTUtils.log("Saving IJ1 table: " + file);
            try {
                rt.saveColumnHeaders(writeColHeaders);
                rt.showRowNumbers(writeRowHeaders);
                rt.saveAs(file.getAbsolutePath());
                if (close) {
                    final Window win = WindowManager.getWindow(title);
                    if (win instanceof ij.text.TextWindow) {
                        ((ij.text.TextWindow) win).close();
                    }
                }
                return true;
            } catch (final IOException e) {
                SNTUtils.error("Failed to save table: " + title, e);
                return false;
            }
        }
    }

    // ---- IJ1 PlotWindow ----

    private class SNTChartSaveable extends Saveable {
        private final SNTChart chart;

        SNTChartSaveable(final SNTChart chart) {
            super(chart.getTitle(), CHART_CATEGORY,
                    "SNT Chart. Will be saved as PNG. Right-click on chart for more options.");
            this.chart = chart;
        }

        @Override
        boolean save() {
			File file = getOutputFile(sanitizeFilename(name) + ".svg");
            try {
				try {
					SNTUtils.log("Saving SNTChart: " + file);
					chart.saveAsSVG(file.getAbsolutePath());
					if (close) chart.dispose();
				} catch (final IllegalArgumentException | NullPointerException e) {
					file = getOutputFile(sanitizeFilename(name) + ".png");
					SNTUtils.log("... Failed. Saving as " + file);
					chart.saveAsPNG(file.getAbsolutePath());
					if (close) chart.dispose();
				}
                return true;
            } catch (final IOException | NullPointerException e) {
                SNTUtils.error("Failed to save chart: " + name, e);
                return false;
            }
        }
    }

    private class IJ1PlotSaveable extends Saveable {
        private final PlotWindow plotWindow;

        IJ1PlotSaveable(final PlotWindow plotWindow) {
            super(plotWindow.getTitle(), CHART_CATEGORY,
                    "ImageJ1 plot. Will be saved as TIFF.");
            this.plotWindow = plotWindow;
        }

        @Override
        boolean save() {
            final boolean saved = savePlot(plotWindow, outputDir, override);
            if (close && saved)
                plotWindow.close();
            return saved;
        }
    }

    public static List<PlotWindow> collectPlots() {
        final List<PlotWindow> windows = new ArrayList<>();
        final int[] ids = WindowManager.getIDList();
        if (ids == null) return windows;
        for (final int id : ids) {
            final ImageWindow win = WindowManager.getImage(id).getWindow();
            if (win instanceof PlotWindow) {
                windows.add((PlotWindow) win);
            }
        }
        return windows;
    }

    public static boolean savePlot(final PlotWindow plotWindow, final File outputDir, final boolean override) {
        final String name = plotWindow.getTitle();
        final String filename = name.endsWith(".tif") ? name : name + ".tif";
        File file = new File(outputDir, filename);
        if (!override) {
            file = SNTUtils.getUniquelySuffixedTifFile(file);
        }
        SNTUtils.log("Saving PlotWindow: " + file);
        final boolean result = ij.IJ.saveAsTiff(plotWindow.getImagePlus(), file.getAbsolutePath());
        if (!result) {
            SNTUtils.error("Could not save plot: " + file.getAbsolutePath());
            return false;
        }
        return true;
    }

}
