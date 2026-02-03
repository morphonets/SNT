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

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.FileChooser;
import sc.fiji.snt.gui.GuiUtils;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages Tracing Backup files in the workspace.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Manage Tracing Backups...", initializer = "init")
public class BackupManagerCmd extends CommonDynamicCmd {

    private static final String ALL_IMAGES = "All Images";
    private static final long BYTES_PER_MB = 1024 * 1024;

    @Parameter(required = false, label = "<HTML><b>Backup Location:", visibility = ItemVisibility.MESSAGE, persist = false,
            description = """
                    The backup folder inside the workspace directory.
                    The workspace directory can be changed in Preferences,
                    or File>Reveal Directory>Change Workspace...""")
    private String HEADER1;

    @Parameter(required = false, label = "<HTML>&nbsp;", visibility = ItemVisibility.MESSAGE, persist = false)
    private String locationInfo;

    @Parameter(label = "Reveal", callback = "revealDir",
            description = "Opens the backup directory")
    private Button revealButton;

    @Parameter(required = false, label = "<HTML><b>Summary:", visibility = ItemVisibility.MESSAGE, persist = false)
    private String HEADER2;

    @Parameter(required = false, label = "Total backups:", visibility = ItemVisibility.MESSAGE, persist = false)
    private String totalBackupsInfo;

    @Parameter(required = false, label = "Total size:", visibility = ItemVisibility.MESSAGE, persist = false)
    private String totalSizeInfo;

    @Parameter(required = false, label = "<HTML><br><b>Filter:", visibility = ItemVisibility.MESSAGE)
    private String HEADER3;

    @Parameter(label = "Image", choices = {}, style = ChoiceWidget.LIST_BOX_STYLE,
            description = "Select which image's backups to manage", callback="updateFilteredInfo")
    private String selectedImage;

    @Parameter(required = false, label = "Backups for selection:", visibility = ItemVisibility.MESSAGE, persist = false)
    private String filteredBackupsInfo;

    @Parameter(required = false, label = "<HTML><br><b>Cleanup Options:", visibility = ItemVisibility.MESSAGE)
    private String HEADER4;

    @Parameter(label = "Keep only last N backups", min = "1", max = "100",
            description = "Number of most recent backups to keep per image")
    private int keepLastN = 10;

    @Parameter(label = "Apply", callback = "deleteByCount",
            description = "Keep only the last N backups for the selected image(s)")
    private Button deleteByCountButton;

    @Parameter(label = "Keep only newer than N days", min = "1", max = "365",
            description = "Keep backups from the last N days, delete older ones")
    private int keepNewerThanDays = 30;

    @Parameter(label = "Apply", callback = "deleteByAge",
            description = "Delete backups older than the specified days")
    private Button deleteByAgeButton;

    @Parameter(required = false, persist = false, label = "<HTML>&nbsp;", visibility = ItemVisibility.MESSAGE)
    private String SPACER;

    @Parameter(label = "Delete All", callback = "deleteAllForSelection",
            description = "Delete all backups for the selected image")
    private Button deleteAllButton;

    private File backupDir;
    private Map<String, List<File>> backupsByImage;

    @SuppressWarnings("unused")
    private void init() {
        super.init(false);
        if (snt == null) {
            error("SNT is not running.");
            return;
        }

        backupDir = snt.getPrefs().getQuickBackupDir();
        scanBackups();
        updateSummary();
        updateImageChoices();
    }

    private void scanBackups() {
        backupsByImage = new LinkedHashMap<>();

        if (backupDir == null || !backupDir.exists()) {
            return;
        }

        // Check for subdirectory structure
        final File[] subdirs = backupDir.listFiles(File::isDirectory);
        if (subdirs != null && subdirs.length > 0) {
            for (final File subdir : subdirs) {
                final List<File> backups = getBackupsInDir(subdir);
                if (!backups.isEmpty()) {
                    backupsByImage.put(subdir.getName(), backups);
                }
            }
        }

        // Also check root directory
        final List<File> rootBackups = getBackupsInDir(backupDir);
        if (!rootBackups.isEmpty()) {
            // Group by image prefix
            for (final File backup : rootBackups) {
                final String prefix = extractImagePrefix(backup.getName());
                backupsByImage.computeIfAbsent(prefix, k -> new ArrayList<>()).add(backup);
            }
        }

        // Sort each list by date (most recent first)
        for (final List<File> backups : backupsByImage.values()) {
            backups.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        }
    }

    private void revealDir() {
        try {
            FileChooser.reveal(backupDir);
        } catch (final Throwable throwable) {
            msg("Could not open directory.", "Error");
        }
    }
    private List<File> getBackupsInDir(File dir) {
        final File[] files = dir.listFiles((d, name) ->
                name.endsWith(".traces") || name.endsWith(".traces.gz"));
        if (files == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(files));
    }

    /**
     * Extracts the image prefix from a backup filename.
     * Backup filenames follow the pattern: {imagePrefix}_{timestamp}.traces
     * where timestamp matches SNTUtils.TIMESTAMP_REGEX (e.g., 2026-01-30_10-00-00).
     *
     * Example: "OP_1_2026-01-30_10-00-00.traces" -> "OP_1"
     *
     * @param filename the backup filename
     * @return the image prefix, or "unknown" if pattern doesn't match
     */
    private String extractImagePrefix(String filename) {
        // Pattern: underscore + timestamp + .traces extension
        // SNTUtils.TIMESTAMP_REGEX matches: YYYY-MM-DD_HH-MM-SS
        final String pattern = "_" + SNTUtils.TIMESTAMP_REGEX + "\\.traces$";
        final String prefix = filename.replaceAll(pattern, "");
        // If nothing was replaced, the pattern didn't match
        return prefix.equals(filename) ? "unknown" : prefix;
    }

    private void updateSummary() {
        locationInfo = backupDir != null ? backupDir.getAbsolutePath() : "Not set";
        final int MAX_LENGTH = 80;
        if (locationInfo.length() > MAX_LENGTH) {
            int startIndex = locationInfo.length() - MAX_LENGTH;
            locationInfo = "..."+ locationInfo.substring(startIndex);
        }
        final int totalCount = backupsByImage.values().stream().mapToInt(List::size).sum();
        totalBackupsInfo = String.valueOf(totalCount);
        final long totalBytes = backupsByImage.values().stream()
                .flatMap(List::stream)
                .mapToLong(File::length)
                .sum();
        totalSizeInfo = formatSize(totalBytes);
    }

    private void updateImageChoices() {
        final List<String> choices = new ArrayList<>();
        choices.add(ALL_IMAGES);
        choices.addAll(backupsByImage.keySet());
        getInfo().getMutableInput("selectedImage", String.class).setChoices(choices);
        if (selectedImage == null || !choices.contains(selectedImage)) {
            selectedImage = ALL_IMAGES;
        }
        updateFilteredInfo();
    }

    @SuppressWarnings("unused")
    private void updateFilteredInfo() {
        if (selectedImage == null) {
            filteredBackupsInfo = "0 backups";
            return;
        }
        final List<File> filtered = getSelectedBackups();
        final long size = filtered.stream().mapToLong(File::length).sum();
        filteredBackupsInfo = String.format("%d backup(s), %s", filtered.size(), formatSize(size));
    }

    private List<File> getSelectedBackups() {
        if (ALL_IMAGES.equals(selectedImage)) {
            return backupsByImage.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
        }
        return backupsByImage.getOrDefault(selectedImage, Collections.emptyList());
    }

    private String formatSize(long bytes) {
        if (bytes < BYTES_PER_MB) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (double) BYTES_PER_MB);
    }

    private boolean noBackupToDeleteError() {
        final boolean noBackupToDelete = getSelectedBackups().isEmpty();
        if (noBackupToDelete)
            msg("No backups currently exist.", "Nothing to Delete");
        return noBackupToDelete;
    }

    @SuppressWarnings("unused")
    private void deleteByCount() {
        if (noBackupToDeleteError()) return;
        if (!confirmDeletion("keep only the last " + keepLastN + " backup(s)")) return;

        int deleted = 0;
        if (ALL_IMAGES.equals(selectedImage)) {
            for (String image : backupsByImage.keySet()) {
                deleted += deleteKeepingLastN(image, keepLastN);
            }
        } else {
            deleted = deleteKeepingLastN(selectedImage, keepLastN);
        }

        showResult(deleted);
    }

    private int deleteKeepingLastN(String image, int n) {
        final List<File> backups = backupsByImage.get(image);
        if (backups == null || backups.size() <= n) return 0;
        int deleted = 0;
        // Backups are sorted most recent first, so delete from index n onwards
        for (int i = n; i < backups.size(); i++) {
            if (backups.get(i).delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    @SuppressWarnings("unused")
    private void deleteByAge() {
        if (noBackupToDeleteError()) return;
        if (!confirmDeletion("delete backups older than " + keepNewerThanDays + " day(s)")) return;

        final long cutoff = System.currentTimeMillis() - (keepNewerThanDays * 24L * 60L * 60L * 1000L);
        int deleted = 0;
        final List<File> toCheck = getSelectedBackups();
        for (File backup : toCheck) {
            if (backup.lastModified() < cutoff) {
                if (backup.delete()) {
                    deleted++;
                }
            }
        }
        showResult(deleted);
    }

    @SuppressWarnings("unused")
    private void deleteAllForSelection() {
        if (noBackupToDeleteError()) return;
        final String target = ALL_IMAGES.equals(selectedImage) ? "ALL backups" : "all backups for " + selectedImage;
        if (!confirmDeletion("delete " + target)) return;

        int deleted = 0;
        final List<File> toDelete = new ArrayList<>(getSelectedBackups());
        for (File backup : toDelete) {
            if (backup.delete()) {
                deleted++;
            }
        }
        // Also delete empty subdirectories
        if (!ALL_IMAGES.equals(selectedImage)) {
            final File subDir = new File(backupDir, selectedImage);
            if (subDir.isDirectory()) {
                String[] remaining = subDir.list();
                if (remaining != null && remaining.length == 0) {
                    subDir.delete();
                }
            }
        }
        showResult(deleted);
    }

    private boolean confirmDeletion(String action) {
        return new GuiUtils(ui).getConfirmation(
                "Are you sure you want to " + action + "?\nThis cannot be undone.",
                "Confirm Deletion");
    }

    private void showResult(int deleted) {
        scanBackups();
        updateSummary();
        updateFilteredInfo();
        updateImageChoices();
        if (deleted > 0) {
            msg(deleted + " backup(s) deleted.", "Cleanup Complete");
        } else {
            msg("No backups were deleted.", "Nothing to Delete");
        }
    }

    @Override
    public void run() {
        // Dialog handles everything via buttons/callbacks
    }
}
