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
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Imports candidate seed points from a CSV file into SNT's {@link SeedOverlay}.
 * <p>
 * The CSV must have a strict header (case-insensitive, whitespace-tolerant):
 * {@code x, y, z, confidence, radius}. Coordinates may be given in voxel
 * indices or physical (calibrated) units; voxel-indexed inputs are converted
 * to physical at parse time using the active image's spacing.
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
    private static final int OPT_CHANNEL = 0;
    private static final int OPT_FRAME = 1;
    private static final int OPT_TYPE = 2;
    private static final int OPT_SOURCE = 3;

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
    private String HEADER = "<HTML>NB: File must have header:<code>x,y,z,confidence,radius</code>, w/" +
            "<br>optional columns: <code>type,source,status,channel,frame</code>";

    @Parameter(label = "Coordinate units", choices = {UNITS_PHYSICAL, UNITS_VOXEL},
            description = "<HTML>How to interpret <i>x, y, z, radius</i> values:<dl>" +
                    "<dt><i>" + UNITS_PHYSICAL + "</i></dt>" +
                    "<dd>Already calibrated (the image's spacing units)</dd>" +
                    "<dt><i>" + UNITS_VOXEL + "</i></dt>" +
                    "<dd>Voxel indices; converted using image spacing at import time</dd>" +
                    "</dl>")
    private String unitsChoice = UNITS_PHYSICAL;

    @Parameter(label = "Append to existing seeds",
            description = "<HTML>If unchecked, the existing seed overlay is replaced.<br>" +
                    "If checked, imported seeds are appended.")
    private boolean append = false;

    @Override
    public void run() {
        super.init(true);
        if (isCanceled()) return;
        if (csvFile == null || !csvFile.canRead()) {
            error("File is not valid, does not exist, or cannot be read.");
            return;
        }

        status("Importing seed points...", false);
        final ParseResult parsed;
        try {
            parsed = parseCsv(csvFile);
        } catch (final ImportException ex) {
            error(ex.getMessage());
            return;
        } catch (final IOException ex) {
            error("Could not read file: " + ex.getMessage());
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
        if (append) {
            overlay.addAll(finalSeeds);
        } else {
            overlay.replaceAll(finalSeeds);
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

    // --- CSV parsing ---

    private ParseResult parseCsv(final File file) throws IOException, ImportException {
        final List<SeedPoint> seeds = new ArrayList<>();
        int skipped = 0;
        try (final BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            final String headerLine = readNonBlankLine(r);
            if (headerLine == null) {
                throw new ImportException("File is empty or contains only blank lines.");
            }
            final ColumnMap columns = parseHeader(headerLine);

            String line;
            int lineNo = 1; // header consumed
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.startsWith("#")) continue;
                final String[] tokens = splitCsv(line);
                final SeedPoint seed = parseRow(tokens, columns, lineNo);
                if (seed == null) {
                    skipped++;
                    continue;
                }
                seeds.add(seed);
            }
        }
        return new ParseResult(seeds, skipped);
    }

    private static String readNonBlankLine(final BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.isBlank() && !line.startsWith("#")) return line;
        }
        return null;
    }

    /**
     * Validates the header and returns a {@link ColumnMap} resolving required
     * + optional column positions. Required columns missing -> throws;
     * optional columns missing -> mapped to {@code -1} (consumed as defaults).
     */
    private static ColumnMap parseHeader(final String headerLine) throws ImportException {
        final String[] tokens = splitCsv(headerLine);
        final int[] required = new int[REQUIRED_HEADERS.length];
        final int[] optional = new int[OPTIONAL_HEADERS.length];
        Arrays.fill(required, -1);
        Arrays.fill(optional, -1);
        for (int col = 0; col < tokens.length; col++) {
            final String name = tokens[col].trim().toLowerCase();
            for (int i = 0; i < REQUIRED_HEADERS.length; i++) {
                if (REQUIRED_HEADERS[i].equals(name) && required[i] == -1) {
                    required[i] = col;
                    break;
                }
            }
            for (int i = 0; i < OPTIONAL_HEADERS.length; i++) {
                if (OPTIONAL_HEADERS[i].equals(name) && optional[i] == -1) {
                    optional[i] = col;
                    break;
                }
            }
        }
        final List<String> missing = new ArrayList<>();
        for (int i = 0; i < REQUIRED_HEADERS.length; i++) {
            if (required[i] == -1) missing.add(REQUIRED_HEADERS[i]);
        }
        if (!missing.isEmpty()) {
            throw new ImportException("CSV header is missing required column(s): " +
                    String.join(", ", missing) + ". Expected header: " +
                    String.join(",", REQUIRED_HEADERS));
        }
        return new ColumnMap(required, optional);
    }

    private SeedPoint parseRow(final String[] tokens, final ColumnMap columns, final int lineNo) {
        try {
            final double x = parseDouble(tokens, columns.required[0]);
            final double y = parseDouble(tokens, columns.required[1]);
            final double z = parseDouble(tokens, columns.required[2]);
            double conf = parseDouble(tokens, columns.required[3]);
            double radius = parseDouble(tokens, columns.required[4]);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                SNTUtils.log("Line " + lineNo + ": non-numeric coordinate; skipped.");
                return null;
            }
            if (Double.isNaN(conf)) conf = 1.0;
            if (Double.isNaN(radius)) radius = 0.0;
            // Clamp confidence and radius to sane ranges
            if (conf < 0) conf = 0;
            else if (conf > 1) conf = 1;
            if (radius < 0) radius = 0;
            final int channel = parseIntOrDefault(tokens, columns.optional[OPT_CHANNEL], SeedPoint.CT_UNSET);
            final int frame = parseIntOrDefault(tokens, columns.optional[OPT_FRAME], SeedPoint.CT_UNSET);
            final String type = parseStringOrDefault(tokens, columns.optional[OPT_TYPE], SeedPoint.TAG_UNSET);
            final String source = parseStringOrDefault(tokens, columns.optional[OPT_SOURCE], SeedPoint.TAG_UNSET);
            return new SeedPoint(x, y, z, conf, radius, channel, frame, type, source);
        } catch (final RuntimeException ex) {
            SNTUtils.log("Line " + lineNo + ": " + ex.getMessage() + "; skipped.");
            return null;
        }
    }

    private static double parseDouble(final String[] tokens, final int col) {
        if (col < 0 || col >= tokens.length) return Double.NaN;
        final String s = tokens[col].trim();
        if (s.isEmpty()) return Double.NaN;
        return Double.parseDouble(s);
    }

    /**
     * Parses an integer from {@code tokens[col]}; returns {@code fallback}
     * when the column is absent ({@code col == -1}), empty, or unparsable.
     */
    private static int parseIntOrDefault(final String[] tokens, final int col, final int fallback) {
        if (col < 0 || col >= tokens.length) return fallback;
        final String s = tokens[col].trim();
        if (s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static String parseStringOrDefault(final String[] tokens, final int col, final String fallback) {
        if (col < 0 || col >= tokens.length) return fallback;
        final String s = tokens[col].trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * RFC 4180-ish CSV splitter: respects double-quoted fields (so type/source
     * strings can contain commas), unescapes doubled quotes inside a quoted
     * field. Used both for the header line and data rows.
     */
    private static String[] splitCsv(final String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '"' && cur.isEmpty()) {
                inQuotes = true;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /**
     * Packed result of {@link #parseHeader}: column positions, with -1 for absent optionals.
     */
    private record ColumnMap(int[] required, int[] optional) {
    }

    private List<SeedPoint> convertVoxelToPhysical(final List<SeedPoint> voxelSeeds) {
        final double sx = snt.getPixelWidth();
        final double sy = snt.getPixelHeight();
        final double sz = snt.getPixelDepth();
        // For radius, use the minimum in-plane spacing as a conservative scalar
        // (radius is a scalar; the input is in voxel units, no anisotropy info).
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
     * Indicates a fatal parsing problem (header missing, file unreadable, ...).
     */
    private static final class ImportException extends Exception {
        ImportException(final String message) {
            super(message);
        }
    }
}
