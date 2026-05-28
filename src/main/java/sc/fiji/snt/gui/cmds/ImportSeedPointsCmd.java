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
import org.scijava.widget.FileWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;

import java.io.File;
import java.util.*;

/**
 * Imports candidate seed points from a CSV file into SNT's {@link SeedOverlay}.
 * <p>
 * The CSV must have a header (case-insensitive, whitespace-tolerant) with the
 * required columns {@code x, y, z, confidence, radius}. Optional columns
 * {@code channel, frame, type, source} are read when present. Coordinates may
 * be given in voxel indices or physical (calibrated) units; voxel-indexed
 * inputs are converted to physical at import time using the active image's
 * spacing.
 *
 * @author Tiago Ferreira
 * @see SeedPoint
 * @see SeedOverlay
 */
@Plugin(type = Command.class, label = "Import Seed Points (CSV)...")
public class ImportSeedPointsCmd extends CommonDynamicCmd {

    public static final String UNITS_PHYSICAL = "Physical (image-calibrated)";
    static final String UNITS_VOXEL = "Voxel indices";

    /**
     * Required CSV column names, in canonical order.
     */
    private static final String[] REQUIRED_HEADERS = {"x", "y", "z", "confidence", "radius"};
    /**
     * Optional CSV columns read when present; defaults applied when absent.
     */
    private static final String[] OPTIONAL_HEADERS = {"channel", "frame", "type", "source"};

    /**
     * Seed counts above this threshold trigger an interactive guardrail
     * (Import all / Top-K / Cancel) when SNT is running with a UI. Keep in
     * sync with the renderer's subsample cap if possible.
     */
    static final int LARGE_IMPORT_THRESHOLD = 50_000;

    @Parameter(label = "CSV file", style = FileWidget.OPEN_STYLE,
            description = "<HTML>CSV file with header.<br>" +
                    "Confidence is clamped to [0,1];<br>Radius is clamped to &ge;0.")
    private File csvFile;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String HEADER = "<HTML>File must have header:<code>x,y,z,confidence,radius</code>, w/" +
            "<br>optional columns: <code>type,source,channel,frame</code>";

    @Parameter(label = "Coordinate units", choices = {UNITS_PHYSICAL, UNITS_VOXEL},
            description = "<HTML>How to interpret <i>x, y, z, radius</i> values:<dl>" +
                    "<dt><i>" + UNITS_PHYSICAL + "</i></dt>" +
                    "<dd>Already calibrated (the image's spacing units)</dd>" +
                    "<dt><i>" + UNITS_VOXEL + "</i></dt>" +
                    "<dd>Voxel indices; converted using image spacing at import time</dd>" +
                    "</dl>")
    private String unitsChoice = UNITS_PHYSICAL;

    @Parameter(label = "Replace existing seeds",
            description = "<HTML>If checked, the existing seed overlay is replaced.<br>" +
                    "If unchecked, imported seeds are appended.")
    private boolean replace;

    @Override
    public void run() {
        super.init(true);
        if (isCanceled()) return;
        if (csvFile == null || !csvFile.canRead()) {
            error("File is not valid, does not exist, or cannot be read.");
            return;
        }

        status("Importing seed points...", false);
        // SNTTable.fromFile returns null on I/O failure (and logs); also catch
        // header-validation problems thrown by parseTable below
        final SNTTable table = SNTTable.fromFile(csvFile.getAbsolutePath());
        if (table == null) {
            error("Could not read " + csvFile.getName() + " (see Console for details).");
            return;
        }

        final ParseResult parsed;
        try {
            parsed = parseTable(table);
        } catch (final ImportException ex) {
            error(ex.getMessage());
            return;
        }
        if (parsed.seeds.isEmpty()) {
            error("No valid seed points were found in " + csvFile.getName() + ".");
            return;
        }

        List<SeedPoint> finalSeeds = (UNITS_VOXEL.equals(unitsChoice))
                ? convertVoxelToPhysical(parsed.seeds)
                : parsed.seeds;

        // Guardrail: large seed sets risk degrading UI responsiveness. When a UI is present,
        // prompt the user to choose. Without a UI (headless scripts), we silently import everything
        final int truncated;
        if (finalSeeds.size() > LARGE_IMPORT_THRESHOLD && ui != null) {
            final String topK = String.format("Top %,d by confidence", LARGE_IMPORT_THRESHOLD);
            final String all = "Import all";
            final String cancel = "Cancel";
            final String[] choices = {all, topK, cancel};
            final String choice = new GuiUtils(ui).getChoice(
                    String.format("CSV contains %,d seeds. Importing more than %,d may<br>" +
                                    "degrade UI responsiveness. What would you like to do?",
                            finalSeeds.size(), LARGE_IMPORT_THRESHOLD),
                    "Large seed set",
                    choices, topK);
            if (choice == null || cancel.equals(choice)) {
                cancel("Import cancelled.");
                return;
            }
            if (topK.equals(choice)) {
                finalSeeds.sort(Comparator.comparingDouble((SeedPoint s) -> s.confidence).reversed());
                truncated = finalSeeds.size() - LARGE_IMPORT_THRESHOLD;
                finalSeeds = new ArrayList<>(finalSeeds.subList(0, LARGE_IMPORT_THRESHOLD));
            } else {
                truncated = 0;
            }
        } else {
            truncated = 0;
        }

        final SeedOverlay overlay = snt.getSeedOverlay();
        if (replace) {
            overlay.replaceAll(finalSeeds);
        } else {
            overlay.addAll(finalSeeds);
        }

        final String summary = (truncated > 0)
                ? String.format("Imported top %,d seeds by confidence (%,d skipped due to size, %,d row%s skipped due to parse errors) from %s.",
                finalSeeds.size(), truncated, parsed.skipped, parsed.skipped == 1 ? "" : "s", csvFile.getName())
                : String.format("Imported %,d seed%s (%,d row%s skipped) from %s.",
                finalSeeds.size(), finalSeeds.size() == 1 ? "" : "s",
                parsed.skipped, parsed.skipped == 1 ? "" : "s", csvFile.getName());
        SNTUtils.log(summary);
        status(summary, true);
        resetUI();
    }

    /**
     * Walks {@code table}, validates the required columns are present, and
     * builds one {@link SeedPoint} per row. Cell values are coerced via
     * {@link #asDouble}, {@link #asInt}, and {@link #asString} so the
     * downstream code doesn't care whether {@link SNTTable} parsed a column
     * as {@code Double}, {@code Long}, or {@code String}.
     *
     * @throws ImportException if a required column is missing from the header.
     */
    private static ParseResult parseTable(final SNTTable table) throws ImportException {
        // Build canonical header index (lowercase, trimmed) -> column index.
        final Map<String, Integer> headerIndex = new HashMap<>(table.getColumnCount() * 2);
        for (int c = 0; c < table.getColumnCount(); c++) {
            final String h = table.getColumnHeader(c);
            if (h == null) continue;
            final String key = h.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) headerIndex.putIfAbsent(key, c);
        }
        // Validate required columns.
        final List<String> missing = new ArrayList<>();
        for (final String req : REQUIRED_HEADERS) {
            if (!headerIndex.containsKey(req)) missing.add(req);
        }
        if (!missing.isEmpty()) {
            throw new ImportException("CSV header is missing required column(s): " +
                    String.join(", ", missing) + ". Expected header: " +
                    String.join(",", REQUIRED_HEADERS));
        }
        // Required column positions
        final int colX = headerIndex.get("x");
        final int colY = headerIndex.get("y");
        final int colZ = headerIndex.get("z");
        final int colConf = headerIndex.get("confidence");
        final int colRad = headerIndex.get("radius");
        // Optional column positions (-1 when absent)
        final int colC = headerIndex.getOrDefault(OPTIONAL_HEADERS[0], -1);
        final int colT = headerIndex.getOrDefault(OPTIONAL_HEADERS[1], -1);
        final int colType = headerIndex.getOrDefault(OPTIONAL_HEADERS[2], -1);
        final int colSrc = headerIndex.getOrDefault(OPTIONAL_HEADERS[3], -1);

        final int rows = table.getRowCount();
        final List<SeedPoint> seeds = new ArrayList<>(rows);
        int skipped = 0;
        for (int r = 0; r < rows; r++) {
            final SeedPoint seed = parseRow(table, r, colX, colY, colZ, colConf, colRad,
                    colC, colT, colType, colSrc);
            if (seed == null) {
                skipped++;
            } else {
                seeds.add(seed);
            }
        }
        return new ParseResult(seeds, skipped);
    }

    private static SeedPoint parseRow(final SNTTable t, final int row,
                                      final int cX, final int cY, final int cZ,
                                      final int cConf, final int cRad,
                                      final int cChannel, final int cFrame,
                                      final int cType, final int cSource) {
        try {
            final double x = asDouble(t.get(cX, row));
            final double y = asDouble(t.get(cY, row));
            final double z = asDouble(t.get(cZ, row));
            // Reject non-finite coordinates outright: they'd produce nonsense canvas positions
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                SNTUtils.log("Row " + (row + 1) + ": non-finite coordinate; skipped.");
                return null;
            }
            double conf = asDouble(t.get(cConf, row));
            double radius = asDouble(t.get(cRad, row));
            if (Double.isNaN(conf)) conf = 1.0;
            if (Double.isNaN(radius) || !Double.isFinite(radius)) radius = 0.0;
            // Clamp confidence and radius to sane ranges
            if (conf < 0) conf = 0;
            else if (conf > 1) conf = 1;
            if (radius < 0) radius = 0;
            final int channel = (cChannel < 0) ? SeedPoint.CT_UNSET : asInt(t.get(cChannel, row), SeedPoint.CT_UNSET);
            final int frame = (cFrame < 0) ? SeedPoint.CT_UNSET : asInt(t.get(cFrame, row), SeedPoint.CT_UNSET);
            final String type = (cType < 0) ? SeedPoint.TAG_UNSET : asString(t.get(cType, row), SeedPoint.TAG_UNSET);
            final String source = (cSource < 0) ? SeedPoint.TAG_UNSET : asString(t.get(cSource, row), SeedPoint.TAG_UNSET);
            return new SeedPoint(x, y, z, conf, radius, channel, frame, type, source);
        } catch (final RuntimeException ex) {
            SNTUtils.log("Row " + (row + 1) + ": " + ex.getMessage() + "; skipped.");
            return null;
        }
    }

    // SNTTable cells are typed Object
    private static double asDouble(final Object cell) {
        if (cell == null) return Double.NaN;
        if (cell instanceof Number n) return n.doubleValue();
        final String s = cell.toString().trim();
        if (s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (final NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static int asInt(final Object cell, final int fallback) {
        if (cell == null) return fallback;
        if (cell instanceof Number n) return n.intValue();
        final String s = cell.toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException ex) {
            // Tolerate a Double-shaped integer ("1.0" -> 1)
            try {
                return (int) Double.parseDouble(s);
            } catch (final NumberFormatException ex2) {
                return fallback;
            }
        }
    }

    private static String asString(final Object cell, final String fallback) {
        if (cell == null) return fallback;
        final String s = cell.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private List<SeedPoint> convertVoxelToPhysical(final List<SeedPoint> voxelSeeds) {
        final double sx = snt.getPixelWidth();
        final double sy = snt.getPixelHeight();
        final double sz = snt.getPixelDepth();
        // For radius, use the minimum in-plane spacing as a conservative scalar
        // (radius is a scalar; the input is in voxel units, no anisotropy info)
        final double sr = Math.min(sx, sy);
        final List<SeedPoint> out = new ArrayList<>(voxelSeeds.size());
        for (final SeedPoint v : voxelSeeds) {
            out.add(new SeedPoint(v.x * sx, v.y * sy, v.z * sz, v.confidence, v.radius * sr,
                    v.channel, v.frame, v.type, v.source));
        }
        return out;
    }

    private record ParseResult(List<SeedPoint> seeds, int skipped) {
    }

    /**
     * Indicates a fatal parsing problem (missing required header, etc.).
     */
    private static final class ImportException extends Exception {
        ImportException(final String message) {
            super(message);
        }
    }
}
