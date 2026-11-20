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


package sc.fiji.snt.tracing.auto;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Parameters of a tip detection run, saved so that the run can be repeated without training paths (see
 * {@link TipDetectionConfig}). Each run is stored as a properties file in a folder (e.g., the tip detection folder
 * of the SNT workspace).
 *
 * @param file           the file the parameters were read from
 * @param image          the name of the image the parameters were learned on
 * @param timestamp      when the parameters were saved (milliseconds since the epoch)
 * @param channel        the (1-based) channel of the signal
 * @param level          the resolution level that was processed (streamed data)
 * @param skipEmpty      whether blocks without signal were skipped
 * @param deviationFactor the number of deviations above the median defining signal (when skipping empty blocks)
 * @param config         the parameters
 * @author Tiago Ferreira
 */
public record SavedTipDetection(File file, String image, long timestamp, int channel, int level, boolean skipEmpty,
                                double deviationFactor, TipDetectionConfig config) {

    /**
     * Saves the parameters of a run
     *
     * @param dir             the folder (created if needed)
     * @param image           the name of the image
     * @param channel         the (1-based) channel of the signal
     * @param level           the resolution level
     * @param skipEmpty       whether blocks without signal are skipped
     * @param deviationFactor the number of deviations above the median defining signal
     * @param config          the parameters
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File save(final File dir, final String image, final int channel, final int level,
                            final boolean skipEmpty, final double deviationFactor, final TipDetectionConfig config)
            throws IOException {
        Files.createDirectories(dir.toPath());
        final long now = System.currentTimeMillis();
        final Properties p = config.toProperties();
        p.setProperty("image", image);
        p.setProperty("timestamp", Long.toString(now));
        p.setProperty("channel", Integer.toString(channel));
        p.setProperty("level", Integer.toString(level));
        p.setProperty("skipEmpty", Boolean.toString(skipEmpty));
        p.setProperty("deviationFactor", Double.toString(deviationFactor));
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(now));
        final File file = new File(dir, image.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + stamp + ".properties");
        try (final Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            p.store(w, "SNT tip detection parameters");
        }
        return file;
    }

    /**
     * Reads a saved run
     *
     * @param file the file
     * @return the saved run
     * @throws IOException              if the file could not be read
     * @throws IllegalArgumentException if the file does not hold valid parameters
     */
    public static SavedTipDetection load(final File file) throws IOException {
        final Properties p = new Properties();
        try (final Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        try {
            return new SavedTipDetection(file, p.getProperty("image", "?"),
                    Long.parseLong(p.getProperty("timestamp", Long.toString(file.lastModified()))),
                    Integer.parseInt(p.getProperty("channel", "1")), Integer.parseInt(p.getProperty("level", "0")),
                    Boolean.parseBoolean(p.getProperty("skipEmpty", "false")),
                    Double.parseDouble(p.getProperty("deviationFactor", "2")), TipDetectionConfig.fromProperties(p));
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid tip detection parameters: " + ex.getMessage(), ex);
        }
    }

    /**
     * Lists the saved runs of a folder, skipping files that cannot be read
     *
     * @param dir the folder
     * @return the saved runs, most recent first
     */
    public static List<SavedTipDetection> list(final File dir) {
        final List<SavedTipDetection> runs = new ArrayList<>();
        final File[] files = (dir == null) ? null : dir.listFiles((d, name) -> name.endsWith(".properties"));
        if (files != null) {
            for (final File f : files) {
                try {
                    runs.add(load(f));
                } catch (final IOException | RuntimeException ignored) {
                    // Not a valid file: skipped
                }
            }
        }
        runs.sort(Comparator.comparingLong(SavedTipDetection::timestamp).reversed());
        return runs;
    }

    /** @return a description of the run, e.g., to choose it from a list */
    public String label() {
        return String.format("%s (%s, C%d, radius %.3g, min. score %.3g)", image,
                new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(timestamp)), channel,
                config.getAnalysisRadius(), config.getMinScore());
    }
}
