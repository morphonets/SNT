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

import ij.ImagePlus;
import ij.gui.PlotWindow;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.frame.RoiManager;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.ImpUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Saves a tracing session including traces, bookmarks, notes, tables, charts,
 * plots, and RM ROIs.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Save Session...", initializer = "init")
public class SaveSessionCmd extends CommonDynamicCmd {

    @Parameter(required = false, label = "<HTML><b>Include:", visibility = ItemVisibility.MESSAGE)
    private String HEADER;

    @Parameter(label = "Tracings (.traces)")
    private boolean saveTraces = true;

    @Parameter(label = "Bookmarks (.csv)")
    private boolean saveBookmarks = true;

    @Parameter(label = "Notes (.md)")
    private boolean saveNotes = true;

    @Parameter(label = "Tables (.csv)")
    private boolean saveTables = true;

    @Parameter(label = "Charts/plots (.svg/.tif)")
    private boolean saveCharts = true;

    @Parameter(label = "ROIs (.zip)")
    private boolean saveROIs = true;

    @Parameter(label = "Image/session info (.md)")
    private boolean saveSessionInfo = true;

    private File sessionDir;
    private int failures = 0;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (snt == null) return;
        final File workspaceDir = ui.getOrPromptForWorkspace();
        if (workspaceDir == null) {
            cancel();
            getInputs().keySet().forEach(this::resolveInput);
            return;
        }
        sessionDir = snt.getPrefs().getSessionsDir();
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionDir = new File(sessionDir, "SNT_Session_" + timestamp);
        sessionDir.mkdirs();
    }

    @Override
    public void run() {
        if (sessionDir == null || isCanceled() || snt == null || ui == null) {
            return; // error message was already displayed
        }
        if (!sessionDir.exists()) {
            error("Could not access session directory.");
            return;
        }

        final ImagePlus imp = (snt.accessToValidImageData()) ? snt.getImagePlus() : null;
        // Save components
        saveManifest(imp, snt.getPrefs().getAutosaveFile());
        if (saveTraces) saveTracesFile();
        if (saveBookmarks) saveBookmarksFile();
        if (saveNotes) saveNotesFile();
        if (saveTables) saveAllTables();
        if (saveCharts) saveAllCharts();
        if (saveROIs) saveROIs(imp);
        if (saveSessionInfo) saveSessionInfoFile(imp);

        // Report results
        if (failures > 0) {
            error(String.format("%d item(s) could not be saved. See Console for details.", failures));
        } else {
            final GuiUtils gUtils = new GuiUtils(ui);
            if (gUtils.getConfirmation("Session saved to:\n" + sessionDir.getAbsolutePath(),
                    "Session Saved", "Open Folder", "OK")) {
                gUtils.showDirectory(sessionDir);
            }
        }
        resetUI();
    }

    private void saveManifest(final ImagePlus imp, final File tracingsFile) {
        final File manifest = new File(sessionDir, "session.properties");
        try (final PrintWriter pw = new PrintWriter(manifest)) {
            pw.println("current_image=" + ((imp == null) ? "N/A" : imp.getTitle()));
            pw.println("image_path=" + ((imp == null) ? "N/A" : ImpUtils.getFilePath(imp)));
            pw.println("tracings_file=" + ((tracingsFile == null) ? "N/A" : tracingsFile.getAbsolutePath()));
            pw.println("created=" + Instant.now().toString());
            pw.println("snt_version=" + SNTUtils.VERSION);
        } catch (final FileNotFoundException e) {
            SNTUtils.error("Failed to save session properties", e);
            failures++;
        }
    }

    private void saveTracesFile() {
        if (snt.getPathAndFillManager().size() == 0) return;
        try {
            final File file = new File(sessionDir, "_session_tracings.traces");
            sntService.save(file.getAbsolutePath());
            SNTUtils.log("Saved traces: " + file);
        } catch (final Exception e) {
            SNTUtils.error("Failed to save traces", e);
            failures++;
        }
    }

    private void saveBookmarksFile() {
        if (snt.getUI().getBookmarkManager().getCount() == 0) return;
        try {
            final File file = new File(sessionDir, "bookmarks.csv");
            snt.getUI().getBookmarkManager().save(file);
            SNTUtils.log("Saved bookmarks: " + file);
        } catch (final Exception e) {
            SNTUtils.error("Failed to save bookmarks", e);
            failures++;
        }
    }

    private void saveNotesFile() {
        if (snt.getUI().getNotesPane().getEditor().getText().isBlank()) return;
        try {
            final File file = new File(sessionDir, "notes.md");
            snt.getUI().getNotesPane().save(file);
            SNTUtils.log("Saved notes: " + file);
        } catch (final Exception e) {
            SNTUtils.error("Failed to save notes", e);
            failures++;
        }
    }

    private void saveAllTables() {
        final SNTTable mainTable = snt.getUI().getTable();
        if (mainTable == null || mainTable.isEmpty())
            return;
        final File tablesDir = new File(sessionDir, "tables");
        if (!tablesDir.exists() && !tablesDir.mkdirs()) {
            SNTUtils.error("Could not create tables directory");
            failures++;
            return;
        }
        // Save SNT's main table if it exists and has data
        try {
            final File file = new File(tablesDir, "SNT_Measurements.csv");
            mainTable.save(file);
            SNTUtils.log("Saved table: " + file);
        } catch (final IOException e) {
            SNTUtils.error("Failed to save main table", e);
            failures++;
        }
    }

    private void saveAllCharts() {
        final List<SNTChart> charts = SNTChart.openCharts();
        if (charts.isEmpty()) return;
        final File chartsDir = new File(sessionDir, "charts");
        if (!chartsDir.exists() && !chartsDir.mkdirs()) {
            SNTUtils.error("Could not create charts directory");
            failures++;
            return;
        }
        for (final SNTChart chart : charts) {
            try {
                final String filename = sanitizeFilename(chart.getTitle()) + ".svg";
                final File file = new File(chartsDir, filename);
                chart.saveAsSVG(file.getAbsolutePath());
                SNTUtils.log("Saved chart: " + file);
            } catch (final IOException e) {
                SNTUtils.error("Failed to save chart: " + chart.getTitle(), e);
                failures++;
            }
        }
        final List<PlotWindow> plots = SaveMeasurementsCmd.collectPlots();
        if (plots.isEmpty()) return;
        for (final PlotWindow pw : plots) {
            final String name = pw.getTitle();
            final String filename = name.endsWith(".tif") ? name : name + ".tif";
            final File file = new File(chartsDir, sanitizeFilename(filename));
            if (SaveMeasurementsCmd.savePlot(pw, chartsDir, true)) {
                SNTUtils.log("Saved plot: " + file);
            } else {
                failures++;
            }
        }
    }

    private File outputROIsDir() {
        final File roisDir = new File(sessionDir, "rois");
        if (!roisDir.exists() && !roisDir.mkdirs()) {
            SNTUtils.error("Could not create ROIs directory");
            failures++;
            return null;
        }
        return roisDir;
    }

    private void saveROIs(final ImagePlus imp) {
        final File outDir = outputROIsDir();
        if (outDir == null) return; // no ROI can be saved. Exit immediately

        // Delineations
        if (ui.getDelineationsManager() != null) {
            final List<Roi> rois = ui.getDelineationsManager().getDelineationROIs();
            if (rois != null && !rois.isEmpty()) {
                final File outFile1 = new File(outDir, "ROIs-delineations.zip");
                if (!RoiConverter.saveRoisToZip(rois, outFile1)) {
                    SNTUtils.error("Could not save delineations ROIs");
                    failures++;
                }
            }
        }

        // Image Overlay
        if (imp != null && imp.getOverlay() != null && imp.getOverlay().size() > 0) {
            final File outFile2 = new File(outDir, "ROIs-overlay.zip");
            if (!RoiConverter.saveRoisToZip(Arrays.asList(imp.getOverlay().toArray()), outFile2)) {
                SNTUtils.error("Could not save overlay ROIs");
                failures++;
            }
        }

        // Active ROI
        if (imp != null && imp.getRoi() != null) {
            final File outFile3 = new File(outDir, "ROI-active.zip");
            if (!RoiConverter.saveRoisToZip(List.of(imp.getRoi() ), outFile3)) {
                SNTUtils.error("Could not save active ROI");
                failures++;
            }
        }
        // ROI Manager
        final RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            return;
        }
        final int[] selected = rm.getSelectedIndexes();
        rm.deselect();
        final File outFile4 = new File(outDir, "ROIs-RM.zip");
        if (!rm.save(outFile4.getAbsolutePath())) {
            SNTUtils.error("Could not save ROI Manager ROIs");
            failures++;
        }
        rm.setSelectedIndexes(selected);
    }

    private void saveSessionInfoFile(final ImagePlus imp) {
        final File file = new File(sessionDir, "session_info.md");
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("# SNT Session Info");
            pw.println();

            // Session details
            pw.println("## Session Details");
            pw.println();
            pw.println("- **Created:** " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("- **SNT Version:** " + SNTUtils.VERSION);
            pw.println("- **Sessions Directory:** " + snt.getPrefs().getSessionsDir().getAbsolutePath());
            pw.println("- **Last Used Directory:** " + snt.getPrefs().getRecentDir().getAbsolutePath());
            pw.println();

            // Primary image info
            pw.println("## Primary Image");
            pw.println();
            if (imp != null) {
                writeImageInfo(pw, imp);
            } else {
                pw.println("- No image loaded");
            }
            pw.println();

            // Secondary image info (if present)
            if (snt.isSecondaryDataAvailable()) {
                pw.println("## Secondary Layer");
                pw.println();
                final ImagePlus secImp = snt.getSecondaryDataAsImp();
                if (secImp != null) {
                    writeImageInfo(pw, secImp);
                } else {
                    pw.println("- Secondary layer active but image info unavailable");
                }
                pw.println();
            }

            if (imp != null) {
                pw.println("## Computation Settings");
                pw.println();
                pw.println("```");
                pw.println(ui.getNotesPane().computationSettings());
                pw.println("```");
                pw.println();
            }

            // Summary of saved content
            pw.println("## Contents");
            pw.println();
            pw.println("- **Paths:** " + snt.getPathAndFillManager().size());
            if (new File(sessionDir, "bookmarks.csv").exists()) {
                pw.println("- **Bookmarks:** Included");
            }
            if (new File(sessionDir, "notes.md").exists()) {
                pw.println("- **Notes:** Included");
            }
            final File tablesDir = new File(sessionDir, "tables");
            if (tablesDir.exists() && tablesDir.listFiles() != null) {
                pw.println("- **Tables:** " + Objects.requireNonNull(tablesDir.listFiles()).length + " file(s)");
            }
            final File chartsDir = new File(sessionDir, "charts");
            if (chartsDir.exists() && chartsDir.listFiles() != null) {
                pw.println("- **Charts:** " + Objects.requireNonNull(chartsDir.listFiles()).length + " file(s)");
            }

            SNTUtils.log("Saved session info: " + file);
        } catch (final IOException e) {
            SNTUtils.error("Failed to save session info", e);
            failures++;
        }
    }

    private void writeImageInfo(final PrintWriter pw, final ImagePlus imp) {
        pw.println("- **Title:** " + imp.getTitle());
        pw.println("- **Path:** " + ImpUtils.getFilePath(imp));
        pw.println(String.format("- **Dimensions:** %d × %d × %d (XYZ)",
                imp.getWidth(), imp.getHeight(), imp.getNSlices()));
        pw.println("- **Channels:** " + imp.getNChannels());
        pw.println("- **Frames:** " + imp.getNFrames());
        final Calibration cal = imp.getCalibration();
        if (cal != null && cal.scaled()) {
            pw.println(String.format("- **Voxel Size:** %s × %s × %s %s",
                    SNTUtils.formatDouble(cal.pixelWidth, 4),
                    SNTUtils.formatDouble(cal.pixelHeight, 4),
                    SNTUtils.formatDouble(cal.pixelDepth, 4),
                    cal.getUnit()));
        }
        pw.println("- **Bit Depth:** " + imp.getBitDepth() + "-bit");
    }

    private String sanitizeFilename(final String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

}
