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

package sc.fiji.snt.viewer;

import net.imglib2.realtransform.AffineTransform3D;
import sc.fiji.snt.SNTUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A snapshot of a {@link Bvv} or {@link Bdv} viewer state at a particular moment, used as a keyframe
 * for movie recording. Captures the viewer transform, camera/slab parameters ({@code dCam},
 * {@code nearClip}, {@code farClip}), the current timepoint, and which "actors" (volume channels,
 * paths, annotations) are visible.
 * <p>
 * Keyframes are serialized/deserialized via {@link #toString()} and {@link #fromString(String)} so
 * they can be dumped to the console with the {@code K} hotkey and pasted into scripts. Camera/slab
 * parameters are only meaningful in BVV: BDV ignores them on playback.
 *
 * @see AbstractBigViewer#captureKeyframe()
 * @see AbstractBigViewer#renderFrames(java.util.List, String)
 */
public class Keyframe {

    /** Viewer transform (camera position + zoom + rotation). */
    public final AffineTransform3D transform;
    /** Camera depth parameter (perspective). */
    public final double dCam;
    /** Near clipping distance (slab front). */
    public final double nearClip;
    /** Far clipping distance (slab back). */
    public final double farClip;
    /** Names of visible actors (e.g. "vol:Sample#1", "paths", "annotations"). */
    public final Set<String> visibleActors;
    /**
     * Timepoint (1-based) displayed at this keyframe. Interpolated linearly (and rounded) between
     * keyframes. A value {@code <= 0} means "unspecified": the viewer's timepoint is left untouched.
     */
    public int timepoint = -1;
    /**
     * Viewer (canvas) size in pixels at capture, or {@code -1} if unspecified. The viewer transform is
     * in screen pixels, so it depends on the canvas size: see {@link #transformFor(int, int)}.
     */
    public int width = -1, height = -1;

    /**
     * Per-source display settings (levels and LUT color), keyed by 0-based source index. Interpolated
     * between keyframes for sources present in both; sources absent from the map are left untouched.
     */
    public final Map<Integer, SourceDisplay> display = new LinkedHashMap<>();

    /**
     * Display settings of a single source.
     *
     * @param min the display range minimum
     * @param max the display range maximum
     * @param rgb the LUT color as 0xRRGGBB, or -1 if unspecified/unsupported
     */
    public record SourceDisplay(double min, double max, int rgb) {

        /** Linear interpolation; color is interpolated per RGB channel if both are specified */
        SourceDisplay lerp(final SourceDisplay to, final double t) {
            int c = (t < 0.5) ? rgb : to.rgb;
            if (rgb >= 0 && to.rgb >= 0) {
                c = 0;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    final double a = (rgb >> shift) & 0xFF;
                    final double b = (to.rgb >> shift) & 0xFF;
                    c |= ((int) Math.round(a + t * (b - a)) & 0xFF) << shift;
                }
            }
            return new SourceDisplay(min + t * (to.min - min), max + t * (to.max - max), c);
        }
    }

    /**
     * Display settings at progress {@code t} (eased, in [0, 1]) between two keyframes, for the
     * sources specified in both.
     */
    static Map<Integer, SourceDisplay> interpolateDisplay(final Keyframe from, final Keyframe to,
                                                          final double t) {
        final Map<Integer, SourceDisplay> result = new LinkedHashMap<>();
        from.display.forEach((idx, a) -> {
            final SourceDisplay b = to.display.get(idx);
            if (b != null) result.put(idx, a.lerp(b, t));
        });
        return result;
    }

    /**
     * Easing type for the transition <em>into</em> this keyframe (0-5). Can be set by name via
     * {@link #setAccel(String)}.
     *
     * @see #ACCEL_SYMMETRIC
     * @see #ACCEL_SLOW_START
     * @see #ACCEL_SLOW_END
     * @see #ACCEL_SOFT_SYMMETRIC
     * @see #ACCEL_SOFT_SLOW_START
     * @see #ACCEL_SOFT_SLOW_END
     */
    public int accelType;

    public static final int ACCEL_SYMMETRIC       = 0;
    public static final int ACCEL_SLOW_START      = 1;
    public static final int ACCEL_SLOW_END        = 2;
    public static final int ACCEL_SOFT_SYMMETRIC  = 3;
    public static final int ACCEL_SOFT_SLOW_START = 4;
    public static final int ACCEL_SOFT_SLOW_END   = 5;

    private static final Map<String, Integer> ACCEL_NAMES = new LinkedHashMap<>();
    static {
        ACCEL_NAMES.put("symmetric",       ACCEL_SYMMETRIC);
        ACCEL_NAMES.put("slow start",      ACCEL_SLOW_START);
        ACCEL_NAMES.put("slow end",        ACCEL_SLOW_END);
        ACCEL_NAMES.put("soft symmetric",  ACCEL_SOFT_SYMMETRIC);
        ACCEL_NAMES.put("soft slow start", ACCEL_SOFT_SLOW_START);
        ACCEL_NAMES.put("soft slow end",   ACCEL_SOFT_SLOW_END);
    }

    /**
     * Number of frames for the transition from the previous keyframe into this one. Ignored for the
     * first keyframe in a sequence. Defaults to 60 (~2 s at 30 fps).
     */
    public int frames;

    /**
     * Convenience constructor that deserializes a keyframe from a string. Equivalent to
     * {@link #fromString(String)} but usable as {@code new Keyframe("transform=...|cam=...|...")}
     * in scripts.
     *
     * @param serialized the string produced by {@link #toString()}
     * @throws IllegalArgumentException if parsing fails
     */
    public Keyframe(final String serialized) {
        final Keyframe parsed = fromString(serialized);
        if (parsed == null) throw new IllegalArgumentException("Invalid keyframe string");
        this.transform = parsed.transform;
        this.dCam = parsed.dCam;
        this.nearClip = parsed.nearClip;
        this.farClip = parsed.farClip;
        this.visibleActors = parsed.visibleActors;
        this.accelType = parsed.accelType;
        this.frames = parsed.frames;
        this.timepoint = parsed.timepoint;
        this.display.putAll(parsed.display);
        this.width = parsed.width;
        this.height = parsed.height;
    }

    public Keyframe(final AffineTransform3D transform, final double dCam, final double nearClip,
                    final double farClip, final Set<String> visibleActors, final int accelType) {
        this(transform, dCam, nearClip, farClip, visibleActors, accelType, 60);
    }

    public Keyframe(final AffineTransform3D transform, final double dCam, final double nearClip,
                    final double farClip, final Set<String> visibleActors, final int accelType,
                    final int frames) {
        this.transform = new AffineTransform3D();
        this.transform.set(transform);
        this.dCam = dCam;
        this.nearClip = nearClip;
        this.farClip = farClip;
        this.visibleActors = new LinkedHashSet<>(visibleActors);
        this.accelType = accelType;
        this.frames = frames;
    }

    /** Normalizes an accel name: lowercase, trim, underscores to spaces */
    private static String normalizeAccelName(final String name) {
        return name.toLowerCase().trim().replace('_', ' ');
    }

    /**
     * Sets the easing type by name. Accepted values (case-insensitive, spaces or underscores):
     * "symmetric", "slow_start" / "slow start", "slow_end", "soft_symmetric", "soft_slow_start",
     * "soft_slow_end".
     *
     * @param name the easing name
     * @throws IllegalArgumentException if the name is not recognized
     */
    public void setAccel(final String name) {
        final Integer type = ACCEL_NAMES.get(normalizeAccelName(name));
        if (type == null)
            throw new IllegalArgumentException("Unknown accel type: '" + name
                    + "'. Valid: " + String.join(", ", ACCEL_NAMES.keySet()));
        this.accelType = type;
    }

    /**
     * Returns the current easing type as a human-readable name.
     *
     * @return the easing name, e.g. "slow start"
     */
    public String getAccelName() {
        for (final Map.Entry<String, Integer> e : ACCEL_NAMES.entrySet())
            if (e.getValue() == accelType) return e.getKey();
        return "symmetric";
    }

    /** Returns the easing name in serialization-safe form (underscores, no spaces) */
    private String getAccelNameSerialized() {
        return getAccelName().replace(' ', '_');
    }

    /**
     * Returns a copy of the viewer transform adjusted to a canvas of the given size. Mirrors what the
     * viewer does when resized (the scene center stays at the canvas center): the translation is
     * shifted by half the size difference. The transform is returned unchanged if this keyframe has
     * no recorded size.
     *
     * @param canvasWidth  the current canvas width in pixels
     * @param canvasHeight the current canvas height in pixels
     * @return a new transform
     */
    public AffineTransform3D transformFor(final int canvasWidth, final int canvasHeight) {
        final AffineTransform3D t = transform.copy();
        if (width > 0 && height > 0) {
            t.set(t.get(0, 3) + 0.5 * (canvasWidth - width), 0, 3);
            t.set(t.get(1, 3) + 0.5 * (canvasHeight - height), 1, 3);
        }
        return t;
    }

    /**
     * Sets the transition length (chainable, for scripts).
     *
     * @param frames number of frames of the transition into this keyframe
     * @return this keyframe
     */
    public Keyframe frames(final int frames) {
        this.frames = frames;
        return this;
    }

    /**
     * Sets the easing type by name (chainable, for scripts).
     *
     * @param name the easing name, see {@link #setAccel(String)}
     * @return this keyframe
     */
    public Keyframe accel(final String name) {
        setAccel(name);
        return this;
    }

    /**
     * Serializes this keyframe to a single-line string:
     * {@code transform=d0,d1,...,d11|cam=dCam,near,far|visible=a;b;c|accel=name|frames=N|t=N|size=WxH|display=i:min,max,#RRGGBB;...}
     * (the timepoint, size and display entries are omitted if unspecified).
     */
    @Override
    public String toString() {
        return toString(true);
    }

    /**
     * Serializes this keyframe, optionally leaving out the timing entries ({@code accel} and
     * {@code frames}), e.g., when they are set separately through {@link #frames(int)} and
     * {@link #accel(String)}.
     *
     * @param includeTiming whether to include the {@code accel} and {@code frames} entries
     * @return the serialized keyframe
     */
    public String toString(final boolean includeTiming) {
        final StringBuilder sb = new StringBuilder("transform=");
        final double[] m = transform.getRowPackedCopy();
        for (int i = 0; i < m.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(m[i]);
        }
        sb.append("|cam=").append(dCam).append(',').append(nearClip).append(',').append(farClip);
        // Sanitize actor names: replace ';' to avoid breaking the delimiter
        sb.append("|visible=").append(visibleActors.stream().map(a -> a.replace(';', '_'))
                .collect(Collectors.joining(";")));
        if (includeTiming) {
            sb.append("|accel=").append(getAccelNameSerialized());
            sb.append("|frames=").append(frames);
        }
        if (timepoint > 0) sb.append("|t=").append(timepoint);
        if (width > 0 && height > 0) sb.append("|size=").append(width).append('x').append(height);
        if (!display.isEmpty()) {
            sb.append("|display=").append(display.entrySet().stream().map(e -> {
                final SourceDisplay d = e.getValue();
                return e.getKey() + ":" + d.min() + "," + d.max()
                        + (d.rgb() >= 0 ? String.format(",#%06X", d.rgb()) : "");
            }).collect(Collectors.joining(";")));
        }
        return sb.toString();
    }

    /**
     * Deserializes a keyframe from the string produced by {@link #toString()}.
     *
     * @param s the serialised keyframe string
     * @return a new Keyframe, or {@code null} if parsing fails
     */
    public static Keyframe fromString(final String s) {
        try {
            final Map<String, String> parts = new LinkedHashMap<>();
            for (final String part : s.split("\\|")) {
                final int eq = part.indexOf('=');
                if (eq > 0) parts.put(part.substring(0, eq), part.substring(eq + 1));
            }
            // Transform
            final String[] td = parts.get("transform").split(",");
            final double[] m = new double[12];
            for (int i = 0; i < 12; i++) m[i] = Double.parseDouble(td[i]);
            final AffineTransform3D t = new AffineTransform3D();
            t.set(m);
            // Camera / slab params
            final String camStr = parts.getOrDefault("cam", "");
            double dc = BvvUtils.DEFAULT_D_CAM, nc = BvvUtils.DEFAULT_NEAR_CLIP, fc = BvvUtils.DEFAULT_FAR_CLIP;
            if (!camStr.isBlank()) {
                final String[] cp = camStr.split(",");
                dc = Double.parseDouble(cp[0]);
                nc = Double.parseDouble(cp[1]);
                fc = Double.parseDouble(cp[2]);
            }
            // Visible actors
            final Set<String> vis = new LinkedHashSet<>();
            final String visStr = parts.getOrDefault("visible", "");
            if (!visStr.isBlank()) Collections.addAll(vis, visStr.split(";"));
            // Accel (accepts "slow_start", "slow start", or int "1")
            final String accelStr = parts.getOrDefault("accel", "symmetric");
            int accel;
            final Integer named = ACCEL_NAMES.get(normalizeAccelName(accelStr));
            if (named != null) accel = named;
            else try { accel = Integer.parseInt(accelStr.trim()); } catch (final NumberFormatException nf) { accel = 0; }
            final int frames = Integer.parseInt(parts.getOrDefault("frames", "60"));
            final Keyframe kf = new Keyframe(t, dc, nc, fc, vis, accel, frames);
            kf.timepoint = Integer.parseInt(parts.getOrDefault("t", "-1"));
            final String sizeStr = parts.getOrDefault("size", "");
            if (!sizeStr.isBlank()) {
                final String[] wh = sizeStr.split("x");
                kf.width = Integer.parseInt(wh[0].trim());
                kf.height = Integer.parseInt(wh[1].trim());
            }
            final String dispStr = parts.getOrDefault("display", "");
            if (!dispStr.isBlank()) {
                for (final String entry : dispStr.split(";")) {
                    final String[] idxAndVals = entry.split(":");
                    final String[] v = idxAndVals[1].split(",");
                    final int rgb = (v.length > 2) ? Integer.parseInt(v[2].substring(1), 16) : -1;
                    kf.display.put(Integer.parseInt(idxAndVals[0].trim()),
                            new SourceDisplay(Double.parseDouble(v[0]), Double.parseDouble(v[1]), rgb));
                }
            }
            return kf;
        } catch (final Exception e) {
            SNTUtils.log("Keyframe parse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Easing function for keyframe transitions, adapted from BDV movie recorder.
     *
     * @param t    progress value in [0, 1]
     * @param type easing type: 0 = symmetric, 1 = slow start, 2 = slow end, 3 = soft symmetric,
     *             4 = soft slow start, 5 = soft slow end
     * @return eased progress value in [0, 1]
     * @see <a href="https://github.com/maarzt/bigdataviewer-core-movie">BDV movie recorder</a>
     */
    public static double accel(final double t, final int type) {
        return switch (type) {
            case 1 -> cos(t * t);                                // slow start
            case 2 -> 1.0 - cos(Math.pow(1.0 - t, 2));           // slow end
            case 3 -> cos(cos(t));                               // soft symmetric
            case 4 -> cos(cos(t * t));                           // soft slow start
            case 5 -> 1.0 - cos(cos(Math.pow(1.0 - t, 2)));      // soft slow end
            default -> cos(t);                                   // symmetric (type 0)
        };
    }

    /** Cosine easing function (symmetric) */
    private static double cos(final double x) {
        return 0.5 - 0.5 * Math.cos(Math.PI * x);
    }
}
