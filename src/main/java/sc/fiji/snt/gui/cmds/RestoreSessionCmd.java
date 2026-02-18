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
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.io.IOService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.gui.GuiUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Restores a tracing session including traces, bookmarks, and notes.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Restore Session...", initializer = "init")
public class RestoreSessionCmd extends CommonDynamicCmd {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    @Parameter
    private UIService uiService;

    @Parameter
    private IOService ioService;

    @Parameter(required = false, label = "Session", persist = false, style = FileWidget.DIRECTORY_STYLE,
            description = "The session folder to restore from")
    private File sessionDir;

    @Parameter(required = false, label = "<HTML><b>Restore:", visibility = ItemVisibility.MESSAGE)
    private String HEADER;

    @Parameter(required = false, label = "Tracings (.traces)")
    private boolean restoreTraces = true;

    @Parameter(required = false, label = "Bookmarks (.csv)")
    private boolean restoreBookmarks = true;

    @Parameter(required = false, label = "Notes (.md)")
    private boolean restoreNotes = true;

    @Parameter(required = false, label = "ROIs (.zip)")
    private boolean restoreROIs = true;

    private int failures = 0;
    private int successes = 0;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (snt == null) return;
        final File workspaceDir = ui.getOrPromptForWorkspace();
        if (snt.getPrefs().workspaceIsValid()) {
            final File sessionsDir = snt.getPrefs().getSessionsDir();
            final File latestSession = Arrays.stream(Objects.requireNonNull(sessionsDir.listFiles()))
                    .filter(f -> f.isDirectory() && f.getName().startsWith("SNT_Session_"))
                    .max(Comparator.comparing(File::getName))
                    .orElse(null);
            sessionDir = (latestSession == null) ? sessionsDir : latestSession;
        }
    }

    @Override
    public void run() {
        if (isCanceled() || snt == null || ui == null) {
            return; // error message was already displayed
        }
        if (sessionDir == null || !SNTUtils.fileAvailable(sessionDir)) {
            error("Invalid session directory.");
            return;
        }

        // Fallback: check properties file (future-proofing)
        final File manifest = new File(sessionDir, "session.properties");
        if (!manifest.exists()) {
            error("Directory does not contain a properties file. Session was not restored.");
            return;
        }
        // Restore components
        if (restoreTraces) restoreTracesFile();
        if (restoreBookmarks) restoreBookmarksFile();
        if (restoreNotes) restoreNotesFile();
        if (restoreROIs) restoreROIsFile();

        // Show session info if available
        showSessionInfo();

        // Update views
        snt.updateAllViewers();

        // Report results
        if (failures > 0 && successes == 0) {
            error("Restore failed. See Console for details.");
        } else if (failures > 0) {
            msg(String.format("Restored %d item(s), %d failed. See Console for details.",
                    successes, failures), "Partial Restore");
        } else if (successes > 0) {
            msg("Session restored successfully.", "Restore Complete");
        } else {
            msg("No items were selected for restore.", "Nothing Restored");
        }

        resetUI();
    }

    private void restoreTracesFile() {
        final File file = new File(sessionDir, "_session_tracings.traces");
        if (!file.exists()) {
            SNTUtils.log("No traces file found in session");
            return;
        }
        try {
            if ((snt.getPathAndFillManager().size() > 0
                    && new GuiUtils(ui).getConfirmation(
                    "There are existing paths. Clear them before loading session paths?",
                    "Delete Existing Path(s)?"))) {
                snt.getPathAndFillManager().clear();
            }
            final boolean success = snt.getPathAndFillManager().load(file.getAbsolutePath());
            if (success) {
                SNTUtils.log("Restored traces: " + file);
                successes++;
            } else {
                SNTUtils.error("Failed to load traces file");
                failures++;
            }
        } catch (final Exception e) {
            SNTUtils.error("Failed to restore traces", e);
            failures++;
        }
    }

    private void restoreBookmarksFile() {
        final File file = new File(sessionDir, "bookmarks.csv");
        if (!file.exists()) {
            SNTUtils.log("No bookmarks file found in session");
            return;
        }
        try {
            snt.getUI().getBookmarkManager().load(file);
            SNTUtils.log("Restored bookmarks: " + file);
            successes++;
        } catch (final Exception e) {
            SNTUtils.error("Failed to restore bookmarks", e);
            failures++;
        }
    }

    private void restoreNotesFile() {
        final File file = new File(sessionDir, "notes.md");
        if (!file.exists()) {
            SNTUtils.log("No notes file found in session");
            return;
        }
        try {
            snt.getUI().getNotesPane().load(file);
            SNTUtils.log("Restored notes: " + file);
            successes++;
        } catch (final Exception e) {
            SNTUtils.error("Failed to restore notes", e);
            failures++;
        }
    }

    private void restoreROIsFile() {
        final File roisDir = new File(sessionDir, "rois");
        if (!roisDir.exists()) {
            SNTUtils.log("No ROIs directory found in session");
            failures++;
            return;
        }
        try {
            // Delineations
            final File delineationROIs = new File(roisDir, "ROIs-delineations.zip");
            if (delineationROIs.exists()) {
                final List<Roi> rois = RoiConverter.loadRoisFromZip(delineationROIs);
                final int result = ui.getDelineationsManager().load(rois);
                if (result < 1) {
                    failures++;
                    SNTUtils.log("Delineation ROIs have no path associations");
                } else {
                    successes++;
                    SNTUtils.log("Restored delineation ROIs");
                }
            }

            // ROI Manager
            RoiManager rm = RoiManager.getInstance2();
            if (rm == null) rm = new RoiManager();
            final File rmROIs = new File(roisDir, "ROIs-RM.zip");
            if (rmROIs.exists()) {
                if (rm.open(rmROIs.getAbsolutePath())) {
                    SNTUtils.log("Restored ROI Manager ROIs");
                    successes++;
                } else {
                    SNTUtils.log("Manager ROIs not restored");
                    failures++;
                }
            }

            final ImagePlus imp = snt.getImagePlus();
            if (imp == null) return;

            // Image Overlay
            final File overlayROIs = new File(roisDir, "ROIs-overlay.zip");
            if (overlayROIs.exists()) {
                final List<Roi> rois = RoiConverter.loadRoisFromZip(overlayROIs);
                if (rois.isEmpty()) {
                    SNTUtils.log("Overlay ROIs not restored");
                    failures++;
                } else {
                    final Overlay overlay = new Overlay();
                    rois.forEach(overlay::add);
                    imp.setOverlay(overlay);
                    SNTUtils.log("Restored overlay ROIs");
                    successes++;
                }
            }

            // Active ROI
            final File activeROI = new File(roisDir, "ROI-active.zip");
            if (activeROI.exists()) {
                final List<Roi> rois = RoiConverter.loadRoisFromZip(activeROI);
                if (rois.isEmpty()) {
                    SNTUtils.log("Active ROI not restored");
                    failures++;
                } else {
                    imp.setRoi(rois.getFirst());
                    SNTUtils.log("Restored active ROI");
                    successes++;
                }
            }
        } catch (final Exception e) {
            SNTUtils.error("Failed to restore notes", e);
            failures++;
        }
    }

    private void showSessionInfo() {
        final File file = new File(sessionDir, "session_info.md");
        if (!file.exists()) return;

        // Show a hint about the original image
        final GuiUtils guiUtils = new GuiUtils(snt.getUI());
        final boolean show = guiUtils.getConfirmation(
                "This session contains image information. Would you like to view it?",
                "Session Information Available");

        if (show) {
            try {
                uiService.show(ioService.open(file.getAbsolutePath()));
            } catch (final Exception e) {
                SNTUtils.error("Failed to read session info", e);
            }
        }
    }

}
