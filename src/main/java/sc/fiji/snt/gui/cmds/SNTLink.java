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
import ij.io.FileInfo;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import sc.fiji.snt.SNT;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bdv;
import sc.fiji.snt.viewer.Bvv;

/**
 * Schema helpers for {@code snt://} links: parsing of link parameters, source
 * normalization and the pre-flight check that decides how a link should be
 * handled given the current state of SNT. Only URL references are supported
 * for images and tracings. Local resources must be absolute paths or
 * {@code file:} URIs.
 * <p>
 * Parameters (all optional):
 * </p>
 * <pre>
 *   image=URI       image/dataset to open (or to match against the open one)
 *   stream=boolean  forces (or disables) SNT Stream for the given image
 *   viewer=bvv|bdv  viewer to use for streamed images (default: bvv)
 *   tracer=boolean  false for a plain viewer, with no SNT session attached
 *   tracings=URI    reconstruction(s) to load
 *   view=x,y,z[,t]  location (world coordinates) to fly to, and timepoint
 * </pre>
 *
 * @author Tiago Ferreira
 */
public final class SNTLink {

    /**
     * What a link requires, given the current state of SNT
     */
    enum Outcome {
        /**
         * Nothing is open: start SNT as requested
         */
        LAUNCH,
        /**
         * Requested source is open (or none specified): apply view/tracings
         */
        FLY_ONLY,
        /**
         * Other mode is open: close it (after unsaved check), then launch
         */
        CLOSE_THEN_LAUNCH,
        /**
         * A different stream source is open: append it or restart Fiji
         */
        APPEND_OR_RESTART,
        /**
         * Request cannot be fulfilled in this session: restart Fiji
         */
        RESTART
    }

    private static final String KEY_IMAGE = "image";
    private static final String KEY_STREAM = "stream";
    private static final String KEY_VIEW = "view";

    private SNTLink() {
    }

    /**
     * Normalizes a path or URI so that references to the same source compare
     * equal: {@code file:} URIs become absolute paths, and trailing separators
     * are dropped
     *
     * @param src the path or URI. Can be null
     * @return the normalized string, or null if {@code src} is null/blank
     */
    static String normalize(final String src) {
        if (src == null || src.isBlank()) return null;
        String s = src.trim();
        try {
            if (s.regionMatches(true, 0, "file:", 0, 5)) {
                s = new File(URI.create(s)).getAbsolutePath();
            }
        } catch (final IllegalArgumentException ignored) {
            // malformed file URI: compare as is
        }
        while (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Parses a {@code view=x,y,z[,t]} value
     *
     * @param value the raw parameter value
     * @return {x, y, z, t} with t defaulting to -1 (current timepoint), or null
     * if the value is missing, malformed or not finite
     */
    static double[] parseView(final String value) {
        if (value == null || value.isBlank()) return null;
        final String[] tokens = value.split(",");
        if (tokens.length != 3 && tokens.length != 4) return null;
        final double[] view = {0, 0, 0, -1};
        try {
            for (int i = 0; i < tokens.length; i++) {
                view[i] = Double.parseDouble(tokens[i].trim());
                if (!Double.isFinite(view[i])) return null;
            }
        } catch (final NumberFormatException ex) {
            return null;
        }
        if (tokens.length == 4 && (view[3] < 1 || view[3] != Math.rint(view[3]))) {
            return null;
        }
        return view;
    }

    /**
     * Formats a {@code view} value (t &lt; 1 is omitted)
     */
    static String formatView(final double x, final double y, final double z,
                             final int t) {
        final String xyz = String.format(Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z);
        return (t < 1) ? xyz : xyz + "," + t;
    }

    /**
     * Gets whether the link targets SNT Stream: explicit {@code stream}
     * parameter if present, otherwise inferred from the image extension
     */
    static boolean wantsStream(final Map<String, String> query) {
        final String flag = query.get(KEY_STREAM);
        if (flag != null && !flag.isBlank()) return Boolean.parseBoolean(flag);
        final String image = normalize(query.get(KEY_IMAGE));
        if (image == null) return false;
        final String s = image.toLowerCase(Locale.ROOT);
        return s.contains(".zarr") || s.contains(".n5") || s.endsWith(".ims") ||
                s.endsWith(".xml") || s.startsWith("s3:") || s.startsWith("gs:");
    }

    /**
     * Gets the big viewer a link should act on: SNT's active viewer if SNT is
     * running, otherwise a standalone (SNT-less) Bvv/Bdv, if any
     *
     * @param snt the running instance of SNT (can be null)
     * @return an open viewer, or null
     */
    public static AbstractBigViewer activeViewer(final SNT snt) {
        AbstractBigViewer v;
        if (snt != null && snt.getUI() != null) {
            v = snt.getUI().getActiveBigViewer();
        } else {
            v = Bvv.getInstance();
            if (v == null) v = Bdv.getInstance();
        }
        return (v == null || v.isDisposed()) ? null : v;
    }

    /**
     * Decides how a link should be handled. Always run before touching any
     * state, so that a link that cannot be fulfilled leaves SNT untouched
     *
     * @param query   the parsed link parameters
     * @param current the running instance of SNT, or null (a standalone viewer
     *                is treated as an open stream)
     * @return the outcome
     */
    static Outcome preflight(final Map<String, String> query, final SNT current) {
        final boolean sntRunning = current != null && current.getUI() != null;
        final AbstractBigViewer viewer = activeViewer(current);
        final boolean streaming = sntRunning ? current.isStreamMode()
                : viewer != null;
        if (!sntRunning && viewer == null) return Outcome.LAUNCH;
        final String image = normalize(query.get(KEY_IMAGE));
        if (image == null) return Outcome.FLY_ONLY;
        final boolean wantsStream = wantsStream(query);
        if (streaming != wantsStream) return Outcome.CLOSE_THEN_LAUNCH;
        if (wantsStream) {
            final boolean same = viewer != null && viewer.getSourcePaths().stream()
                    .map(SNTLink::normalize).anyMatch(p -> Objects.equals(p, image));
            return same ? Outcome.FLY_ONLY : Outcome.APPEND_OR_RESTART;
        }
        final String open = normalize(imagePath(current));
        return Objects.equals(open, image) ? Outcome.FLY_ONLY
                : Outcome.CLOSE_THEN_LAUNCH;
    }

    /**
     * Builds a link that opens {@code source} (if not already open) and flies to
     * a location. Local sources are written as {@code file:} URIs
     *
     * @param source the image/dataset path or URL (see {@link #sourceOf})
     * @param viewer the viewer displaying {@code source} (can be null)
     * @param tracer whether the viewer is tethered to SNT (SNT Stream)
     * @param x      the X coordinate (world units)
     * @param y      the Y coordinate (world units)
     * @param z      the Z coordinate (world units)
     * @param t      the 1-based timepoint (&lt; 1 for the current one)
     * @return the {@code fiji://snt/open?...} link. The {@code fiji://} form is
     * used on all platforms, as {@code snt://} cannot be registered on
     * macOS, and links must be shareable across operating systems
     */
    public static String locationLink(final String source,
                                      final AbstractBigViewer viewer, final boolean tracer, final double x,
                                      final double y, final double z, final int t) {
        final boolean fileUri = source.regionMatches(true, 0, "file:", 0, 5);
        final String uri = (isLocal(source) && !fileUri) ? new File(source).toURI()
                .toString() : source.trim();
        final StringBuilder sb = new StringBuilder("fiji://snt/open?image=")
                .append(encode(uri));
        if (!wantsStream(Map.of(KEY_IMAGE, uri))) sb.append("&stream=true");
        if (viewer != null) {
            sb.append("&viewer=").append((viewer instanceof Bvv) ? "bvv" : "bdv");
        }
        sb.append(tracer ? "&tracer=true" : "&tracer=false");
        return sb.append("&view=").append(formatView(x, y, z, t)).toString();
    }

    /**
     * Gets whether a source is a bundled demo, i.e., {@code demo:<name>}
     */
    public static boolean isDemo(final String source) {
        return source != null && source.trim().regionMatches(true, 0, "demo:", 0, 5);
    }

    /**
     * Gets whether a path/URL refers to a local file or directory
     */
    public static boolean isLocal(final String source) {
        if (source == null || isDemo(source)) return false;
        final String s = source.trim();
        if (s.regionMatches(true, 0, "file:", 0, 5)) return true;
        final int colon = s.indexOf("://");
        return colon < 2; // no scheme, or a Windows drive letter
    }

    /**
     * Gets the source of the data currently displayed
     *
     * @param snt    the running instance of SNT (can be null)
     * @param viewer the big viewer (can be null, in which case SNT's active
     *               viewer is used if in stream mode)
     * @return the source path/URL, or null if unknown
     */
    public static String sourceOf(final SNT snt, final AbstractBigViewer viewer) {
        final AbstractBigViewer v = (viewer != null) ? viewer
                : (snt == null || snt.isStreamMode()) ? activeViewer(snt) : null;
        if (v != null) {
            return v.getSourcePaths().stream().sorted().findFirst().orElse(null);
        }
        return (snt == null) ? null : imagePath(snt);
    }

    /**
     * Parses the query of a link. Unlike {@code Links#query(URI)}, which splits
     * the already-decoded query, the raw query is split first and each key/value
     * is decoded afterwards. This way, encoded {@code &} and {@code =} (e.g., in
     * a source URL with its own query string) are not mistaken for delimiters.
     * Only percent-escapes are decoded: a literal {@code +} is kept as is
     *
     * @param uri the link
     * @return the decoded parameters, in order of appearance (a parameter
     * without {@code =} maps to null)
     */
    static Map<String, String> query(final URI uri) {
        final Map<String, String> map = new LinkedHashMap<>();
        final String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (final String token : raw.split("&")) {
            if (token.isEmpty()) continue;
            final String[] kv = token.split("=", 2);
            map.put(decode(kv[0]), (kv.length > 1) ? decode(kv[1]) : null);
        }
        return map;
    }

    private static String decode(final String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException ex) {
            return s; // malformed escape: keep as is
        }
    }

    private static String encode(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Path of the image open in traditional mode, or null if unknown
     */
    private static String imagePath(final SNT snt) {
        final ImagePlus imp = snt.getImagePlus();
        final FileInfo fi = (imp == null) ? null : imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.fileName == null) return null;
        return new File(fi.directory, fi.fileName).getAbsolutePath();
    }

    /**
     * Gets whether the link requests the Big Volume Viewer (the default)
     */
    static boolean wantsBvv(final Map<String, String> query) {
        return !"bdv".equalsIgnoreCase(query.get("viewer"));
    }

    /**
     * Gets whether the link requests SNT Stream (the default) or a plain viewer
     */
    static boolean wantsTracer(final Map<String, String> query) {
        return !"false".equalsIgnoreCase(query.get("tracer"));
    }

    /**
     * Gets the (raw) {@code view} parameter
     */
    static String view(final Map<String, String> query) {
        return query.get(KEY_VIEW);
    }

}
