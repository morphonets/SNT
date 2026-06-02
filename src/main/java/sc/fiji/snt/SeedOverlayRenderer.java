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

package sc.fiji.snt;

import net.imglib2.display.ColorTable;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedOverlay.ColorMode;
import sc.fiji.snt.seed.SeedPoint;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stateless renderer that draws a {@link SeedOverlay}'s seeds onto a
 * {@link TracerCanvas}'s {@link Graphics2D}. Invoked from
 * {@link TracerCanvas#drawOverlay(Graphics2D)} after paths are drawn so
 * seeds appear on top.
 * <p>
 * Visual conventions:
 * <ul>
 *   <li><b>Color</b> is looked up from the overlay's active
 *       {@link ColorTable} using the seed's confidence normalized to
 *       {@code [low, high]}. Seeds outside that range are not drawn.</li>
 *   <li><b>Radius</b> on screen is the seed's physical radius converted to
 *       voxels (using the active image's in-plane spacing) and then to
 *       canvas pixels via the current magnification. A small floor is
 *       applied so high-zoom-out seeds remain clickable.</li>
 *   <li><b>Alpha</b> falls off with distance from the current depth slice
 *       (per-plane); seeds outside the canvas's {@code eitherSide} band
 *       (when {@code just_near_slices} is on) are skipped.</li>
 *   <li>Seeds whose 2D projection falls outside the visible canvas
 *       rectangle are skipped (the dominant performance optimization when
 *       the user is zoomed in).</li>
 *   <li>When the visible post-cull set exceeds
 *       {@link #SUBSAMPLE_RENDER_CAP}, only the top-K seeds by confidence
 *       are drawn (full set remains available for queries).</li>
 * </ul>
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 */
public final class SeedOverlayRenderer {

    /**
     * Minimum on-screen radius in pixels, so far-zoomed-out seeds remain visible.
     */
    private static final double MIN_PIXEL_RADIUS = 2.0;
    /**
     * Maximum slice distance that still receives a non-zero alpha when not filtering.
     */
    private static final int DEFAULT_DEPTH_FALLOFF_SLICES = 8;
    /**
     * Alpha at full confidence + on-slice.
     */
    private static final int MAX_ALPHA = 220;
    /**
     * Alpha floor (zero-confidence on-slice seed).
     */
    private static final int MIN_ALPHA = 40;

    /**
     * If the post-cull visible set exceeds this many seeds, fall back to
     * rendering the top-K-by-confidence subset to keep paint times bounded.
     */
    static final int SUBSAMPLE_RENDER_CAP = 20_000;

    private static final BasicStroke OUTLINE_STROKE = new BasicStroke(1.0f);
    /**
     * Stroke used to ring selected seeds. Thicker than the high-confidence ring.
     */
    private static final BasicStroke SELECTION_STROKE = new BasicStroke(2.0f);
    /**
     * Outline color for selected seeds (bright, neutral against any LUT).
     */
    private static final Color SELECTION_OUTLINE = new Color(255, 255, 0);
    /**
     * Halo color drawn underneath selected seeds for contrast on bright LUTs.
     */
    private static final Color SELECTION_HALO = new Color(0, 0, 0, 180);

    private SeedOverlayRenderer() {
    }

    /**
     * Draws the visible seeds of {@code overlay} on {@code canvas}. No-op if
     * {@code overlay} is empty, hidden, or {@code snt} is {@code null}.
     *
     * @param g       the graphics context (treated as a 2D context; AA enabled)
     * @param canvas  the destination canvas (drives plane, slice, magnification)
     * @param overlay the seed store
     * @param snt     the active SNT instance (for image spacing)
     */
    static void draw(final Graphics2D g, final TracerCanvas canvas,
                     final SeedOverlay overlay, final SNT snt) {
        if (overlay == null || overlay.isEmpty() || !overlay.isVisible() || snt == null) return;

        final double low = overlay.getLowConfidence();
        final double high = overlay.getHighConfidence();
        final double transparency = overlay.getTransparency();
        if (transparency <= 0) return; // fully transparent → nothing to draw
        final ColorTable colorTable = overlay.getColorTable();
        final Set<SeedPoint> selected = overlay.getSelectedSeeds();
        // Resolve seeds to draw: if the effective post-filter draw count is
        // huge, fall back to top-K-by-confidence.
        // Selected seeds bypass the confidence filter (the renderer below
        // forces them through) AND are unioned into the subsample, so they
        // count toward the effective total. Without this, a large selection
        // could push the actual draw count well over SUBSAMPLE_RENDER_CAP
        // without subsampling triggering.
        final int total = overlay.size();
        final boolean inRangeFull = (low <= 0 && high >= 1);
        final int inRange = inRangeFull ? total : overlay.visibleSize();
        // Upper bound: an out-of-range selected seed adds to the draw count.
        // Worst case is `selected.size()` extra seeds; in practice many will
        // already be in-range, so this can over-count. Acceptable: erring on
        // the side of "subsample sooner" is safer than ballooning the paint
        // loop. Cap at `total` to avoid silly values.
        final int rangedCount = Math.min(total, inRange + selected.size());
        final List<SeedPoint> seedsToConsider;
        if (rangedCount > SUBSAMPLE_RENDER_CAP) {
            final List<SeedPoint> topK = overlay.topKByConfidence(SUBSAMPLE_RENDER_CAP);
            if (selected.isEmpty()) {
                seedsToConsider = topK;
            } else {
                // Union top-K with the selection so selected seeds are never hidden
                // by subsampling. LinkedHashSet to preserve top-K order + dedupe.
                final LinkedHashSet<SeedPoint> union = new LinkedHashSet<>(topK);
                union.addAll(selected);
                seedsToConsider = new ArrayList<>(union);
            }
        } else {
            seedsToConsider = overlay.list();
        }

        // Pre-build any lookup maps the active color mode needs. INDEX uses
        // the seed's position in the full overlay list (stable per-render);
        // TYPE/SOURCE use a sorted-key -> ordinal map for maximum LUT
        // separation between distinct categories.
        final ColorMode colorMode = overlay.getColorMode();
        final Map<SeedPoint, Integer> seedIndexMap;
        final Map<String, Integer> categoryOrdinals;
        if (colorMode == ColorMode.INDEX) {
            final List<SeedPoint> all = overlay.list();
            seedIndexMap = new HashMap<>(Math.max(16, all.size() * 2));
            for (int i = 0; i < all.size(); i++) seedIndexMap.put(all.get(i), i);
            categoryOrdinals = null;
        } else if (colorMode == ColorMode.TYPE || colorMode == ColorMode.SOURCE) {
            final TreeSet<String> keys = new TreeSet<>();
            if (colorMode == ColorMode.TYPE) {
                for (final SeedPoint sp : overlay.list()) keys.add(sp.type);
            } else {
                for (final SeedPoint sp : overlay.list()) keys.add(sp.source);
            }
            categoryOrdinals = new HashMap<>(Math.max(16, keys.size() * 2));
            int i = 0;
            for (final String k : keys) categoryOrdinals.put(k, i++);
            seedIndexMap = null;
        } else {
            seedIndexMap = null;
            categoryOrdinals = null;
        }

        final int plane = canvas.getPlane();
        final int currentSlice = canvas.getImage().getZ() - 1;
        // When just_near_slices is active, hide seeds beyond eitherSide; otherwise
        // fade gently within DEFAULT_DEPTH_FALLOFF_SLICES on either side.
        final boolean strictBand = canvas.just_near_slices;
        final int band = strictBand ? Math.max(0, canvas.eitherSide) : DEFAULT_DEPTH_FALLOFF_SLICES;

        final double sx = snt.getPixelWidth();
        final double sy = snt.getPixelHeight();
        final double sz = snt.getPixelDepth();
        final double mag = canvas.getMagnification();
        final double inPlaneSpacing = inPlaneSpacing(plane, sx, sy, sz);

        // Canvas screen bounds for visible-window culling
        final int canvasW = canvas.getWidth();
        final int canvasH = canvas.getHeight();

        // Save graphics state we mutate
        final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        final Stroke oldStroke = g.getStroke();
        final Color oldColor = g.getColor();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        final Ellipse2D.Double ellipse = new Ellipse2D.Double();
        try {
            for (final SeedPoint s : seedsToConsider) {
                final boolean isSelected = selected.contains(s);
                // Selected seeds bypass the range filter so the user can always
                // see what they explicitly picked from the table.
                if (!isSelected && (s.confidence < low || s.confidence > high)) continue;

                final int seedSlice = seedSliceForPlane(s, plane, sx, sy, sz);
                final int depthDiff = Math.abs(seedSlice - currentSlice);
                if (strictBand && depthDiff > band) continue;

                final double[] screen = screenCoordsForPlane(s, canvas, plane, sx, sy, sz);
                final double screenX = screen[0];
                final double screenY = screen[1];

                // Pixel radius from the seed's physical radius. Floor so the marker stays visible at low zoom
                double pixelRadius = (s.radius > 0 ? s.radius : inPlaneSpacing) / inPlaneSpacing * mag;
                if (pixelRadius < MIN_PIXEL_RADIUS) pixelRadius = MIN_PIXEL_RADIUS;

                // Visible-window cull using this seed's own radius: skip only when the entire ellipse
                // lies off-canvas. Java2D handles the clipping for seeds that straddle the edge
                if (screenX + pixelRadius < 0 || screenX - pixelRadius > canvasW
                        || screenY + pixelRadius < 0 || screenY - pixelRadius > canvasH) continue;

                final double depthFalloff = depthFalloff(depthDiff, band);
                final Color color = colorForSeed(colorTable, colorMode, s,
                        low, high, depthFalloff * transparency,
                        seedIndexMap, categoryOrdinals);

                ellipse.setFrame(screenX - pixelRadius, screenY - pixelRadius,
                        2 * pixelRadius, 2 * pixelRadius);
                g.setColor(color);
                g.fill(ellipse);
                if (isSelected) {
                    // Selected: dark halo + bright yellow ring slightly outside
                    // the seed circle so it remains visible at any LUT/conf.
                    g.setStroke(SELECTION_STROKE);
                    g.setColor(SELECTION_HALO);
                    final Ellipse2D.Double halo = new Ellipse2D.Double(
                            screenX - pixelRadius - 3, screenY - pixelRadius - 3,
                            2 * pixelRadius + 6, 2 * pixelRadius + 6);
                    g.draw(halo);
                    g.setColor(SELECTION_OUTLINE);
                    g.draw(ellipse);
                } else if (colorMode == ColorMode.CONFIDENCE && s.confidence >= 0.8) {
                    // White outline on high-confidence seeds is only meaningful
                    // when color encodes confidence; in other modes it would
                    // arbitrarily single out seeds that happen to score high
                    g.setStroke(OUTLINE_STROKE);
                    g.setColor(new Color(255, 255, 255, color.getAlpha()));
                    g.draw(ellipse);
                }
            }
        } finally {
            g.setColor(oldColor);
            g.setStroke(oldStroke);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
        }
    }

    /**
     * Voxel size of the in-plane "width" axis for screen-radius scaling.
     */
    private static double inPlaneSpacing(final int plane, final double sx, final double sy, final double sz) {
        return switch (plane) {
            case MultiDThreePanes.XY_PLANE -> sx;       // X horizontal
            case MultiDThreePanes.XZ_PLANE -> sx;       // X horizontal
            case MultiDThreePanes.ZY_PLANE -> sz;       // Z horizontal
            default -> sx;
        };
    }

    /**
     * Returns the voxel-space depth-axis slice index of {@code s} for the given plane.
     */
    private static int seedSliceForPlane(final SeedPoint s, final int plane,
                                         final double sx, final double sy, final double sz) {
        return switch (plane) {
            case MultiDThreePanes.XY_PLANE -> (int) Math.round(s.z / sz);
            case MultiDThreePanes.XZ_PLANE -> (int) Math.round(s.y / sy);
            case MultiDThreePanes.ZY_PLANE -> (int) Math.round(s.x / sx);
            default -> 0;
        };
    }

    /**
     * Returns {@code {screenX, screenY}} for the given seed on the given canvas.
     */
    private static double[] screenCoordsForPlane(final SeedPoint s, final TracerCanvas canvas,
                                                 final int plane,
                                                 final double sx, final double sy, final double sz) {
        final double vx = s.x / sx;
        final double vy = s.y / sy;
        final double vz = s.z / sz;
        return switch (plane) {
            case MultiDThreePanes.XY_PLANE -> new double[]{
                    canvas.myScreenXDprecise(vx), canvas.myScreenYDprecise(vy)};
            case MultiDThreePanes.XZ_PLANE -> new double[]{
                    canvas.myScreenXDprecise(vx), canvas.myScreenYDprecise(vz)};
            case MultiDThreePanes.ZY_PLANE -> new double[]{
                    canvas.myScreenXDprecise(vz), canvas.myScreenYDprecise(vy)};
            default -> new double[]{
                    canvas.myScreenXDprecise(vx), canvas.myScreenYDprecise(vy)};
        };
    }

    /**
     * Returns a multiplier in {@code [0.15, 1]} that fades alpha by depth.
     * On-slice ({@code depthDiff == 0}) yields {@code 1}; at the band edge
     * yields {@code 0.15}.
     */
    private static double depthFalloff(final int depthDiff, final int band) {
        if (band <= 0) return depthDiff == 0 ? 1.0 : 0.15;
        final double t = Math.min(1.0, (double) depthDiff / band);
        return 1.0 - 0.85 * t;
    }

    /**
     * Dispatches to the per-mode color computation. CONFIDENCE keeps the
     * legacy confidence-position behaviour (alpha rides on confidence);
     * INDEX / TYPE / SOURCE use a categorical key and full opacity so every
     * seed contributes equal visual weight.
     * <p>
     * Public so that non-canvas consumers (e.g. the Seeds table's swatch
     * column) can compute the exact same color a seed would receive on the
     * canvas, ensuring row⇄canvas correspondence is visually identical.
     * Stateless: pass {@code depthFalloff = 1.0} when there's no slice-distance
     * concept (table rows have no Z).
     */
    public static Color colorForSeed(final ColorTable table, final ColorMode mode,
                                     final SeedPoint s, final double low, final double high,
                                     final double depthFalloff,
                                     final Map<SeedPoint, Integer> seedIndexMap,
                                     final Map<String, Integer> categoryOrdinals) {
        return switch (mode) {
            case CONFIDENCE -> colorFromTable(table, s.confidence, low, high, depthFalloff);
            case INDEX -> {
                final Integer idx = (seedIndexMap == null) ? null : seedIndexMap.get(s);
                yield colorFromTableByIndex(table, (idx != null) ? idx : 0,
                        MAX_ALPHA, depthFalloff);
            }
            case TYPE -> {
                final Integer ord = (categoryOrdinals == null) ? null : categoryOrdinals.get(s.type);
                yield colorFromTableByIndex(table, (ord != null) ? ord : 0,
                        MAX_ALPHA, depthFalloff);
            }
            case SOURCE -> {
                final Integer ord = (categoryOrdinals == null) ? null : categoryOrdinals.get(s.source);
                yield colorFromTableByIndex(table, (ord != null) ? ord : 0,
                        MAX_ALPHA, depthFalloff);
            }
        };
    }

    /**
     * Returns an RGBA color sampled from {@code table} based on where
     * {@code conf} sits in the active {@code [low, high]} window. Alpha is
     * derived from confidence × depthFalloff so dim seeds and far-from-slice
     * seeds both fade gracefully.
     */
    private static Color colorFromTable(final ColorTable table,
                                        final double conf, final double low, final double high,
                                        final double depthFalloff) {
        final int len = table.getLength();
        final double span = (high > low) ? (high - low) : 1.0;
        final double t = Math.max(0, Math.min(1, (conf - low) / span));
        int idx = (int) Math.round(t * (len - 1));
        if (idx < 0) idx = 0;
        else if (idx >= len) idx = len - 1;
        final int r = table.get(ColorTable.RED, idx);
        final int gr = table.get(ColorTable.GREEN, idx);
        final int b = table.get(ColorTable.BLUE, idx);
        // Alpha rides on the normalized position within the active window: so a
        // seed at the bottom of the window is faded, at the top is solid, and
        // then attenuated by depthFalloff.
        final int alpha = (int) Math.round((MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * t) * depthFalloff);
        final int clamped = Math.max(0, Math.min(255, alpha));
        return new Color(r, gr, b, clamped);
    }

    /**
     * Returns an RGBA color sampled from {@code table} at
     * {@code rawIndex mod table.getLength()}. Alpha is
     * {@code baseAlpha × depthFalloff}; the result is suitable for
     * categorical color modes (INDEX / TYPE / SOURCE) where every seed
     * should be equally bright regardless of confidence.
     */
    private static Color colorFromTableByIndex(final ColorTable table, final int rawIndex,
                                               final int baseAlpha, final double depthFalloff) {
        final int len = table.getLength();
        int idx = rawIndex % len;
        if (idx < 0) idx += len;
        final int r = table.get(ColorTable.RED, idx);
        final int gr = table.get(ColorTable.GREEN, idx);
        final int b = table.get(ColorTable.BLUE, idx);
        final int alpha = (int) Math.round(baseAlpha * depthFalloff);
        final int clamped = Math.max(0, Math.min(255, alpha));
        return new Color(r, gr, b, clamped);
    }
}
