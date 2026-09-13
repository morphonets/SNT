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

package sc.fiji.snt.seed;

import net.imglib2.display.ColorTable;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SeedOverlayRenderer;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.AbstractBigViewer;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bdv/Bvv counterpart of {@link SeedOverlayCanvasHandler}.
 * <p>
 * Mirrors a {@link SeedOverlay} into an {@link AbstractBigViewer}'s {@link AbstractBigViewer.AnnotationOverlay}, which
 * already provides viewer-agnostic marker rendering and hit-testing for both {@code Bdv} and {@code Bvv} (see
 * {@link sc.fiji.snt.BookmarkManager} for the same pattern applied to bookmarks). No new rendering code is added here;
 * coloring reuses {@link SeedOverlayRenderer#colorForSeed} so seeds look identical across the classic canvas and the
 * big-viewer overlay.
 * </p>
 * <p>
 * Alt+Click on a rendered seed (while tracing is paused, or when no SNT instance is attached) opens the same
 * {@link SeedPointEditDialog} used by the canvas handler.
 * </p>
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 * @see AbstractBigViewer.AnnotationOverlay
 */
public final class SeedOverlayBigViewerHandler {

    /** Hard cap on seeds pushed to the viewer per sync, mirrors SeedOverlayRenderer */
    private static final int RENDER_CAP = 20_000;

    private final AbstractBigViewer viewer;
    private final SeedOverlay overlay;
    private final SeedOverlay.SeedOverlayListener listener = this::sync;

    /** Seeds last pushed to the viewer's annotation overlay, in push order (index-addressable) */
    private List<SeedPoint> pushed = List.of();

    /**
     * Attaches a handler to {@code viewer}, bridging {@code overlay} into its
     * annotation overlay. No-op (beyond bookkeeping) if the viewer has no
     * annotation support.
     *
     * @param viewer  the big-viewer instance ({@code Bdv} or {@code Bvv})
     * @param overlay the seed store to mirror
     * @return the handler, retained only if the caller needs to {@link #dispose()} it
     */
    public static SeedOverlayBigViewerHandler install(final AbstractBigViewer viewer, final SeedOverlay overlay) {
        return new SeedOverlayBigViewerHandler(viewer, overlay);
    }

    private SeedOverlayBigViewerHandler(final AbstractBigViewer viewer, final SeedOverlay overlay) {
        this.viewer = viewer;
        this.overlay = overlay;
        // NB: viewer.annotations() is not cached - Bdv/Bvv initialize their AnnotationOverlay
        // lazily (on attach()), so it may still be null when this handler is installed (see
        // SNTUI#setBdvOnEDT/#setBvvOnEDT). Fetch it fresh on every use instead, mirroring
        // BookmarkManager's pattern
        viewer.addMouseListenerToDisplay(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() != 1 || e.getButton() != MouseEvent.BUTTON1 || !e.isAltDown()) return;
                onAltClick(e);
            }
        });
        overlay.addListener(listener);
        sync(overlay);
    }

    /** Detaches from {@code overlay} and clears the viewer's annotations */
    public void dispose() {
        overlay.removeListener(listener);
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations != null) annotations.clear();
    }

    private void onAltClick(final MouseEvent e) {
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations == null) return;
        final SNT snt = viewer.getSNT();
        if (snt != null && snt.getUI() != null && snt.getUI().getState() != SNTUI.TRACING_PAUSED) return;
        final int idx = annotations.hitTest(e.getX(), e.getY());
        if (idx < 0 || idx >= pushed.size()) return;
        e.consume();
        final SeedPoint hit = pushed.get(idx);
        SwingUtilities.invokeLater(() -> {
            final int overlayIdx = overlay.indexOf(hit);
            if (overlayIdx >= 0) SeedPointEditDialog.editAt(viewer.getViewerFrame(), overlay, overlayIdx);
        });
    }

    /**
     * Recomputes the pushed annotation list from {@code source}'s current state. Dispatched onto
     * the EDT (running inline if already there): {@code source}'s {@code fireChanged()} runs
     * synchronously on whatever thread calls e.g. {@code overlay.add()}/{@code addAll()}, which for
     * PeripathDetectorCmd is a background command thread, not the EDT. Mutating the viewer's
     * AnnotationOverlay from that thread would race with the PainterThread's own render pass over
     * the same overlay (see Bvv.AnnRenderer's screenData field)
     */
    private void sync(final SeedOverlay source) {
        if (SwingUtilities.isEventDispatchThread()) {
            syncOnEdt(source);
        } else {
            SwingUtilities.invokeLater(() -> syncOnEdt(source));
        }
    }

    private void syncOnEdt(final SeedOverlay source) {
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations == null) return;
        if (source.isEmpty() || !source.isVisible() || source.getTransparency() <= 0) {
            pushed = List.of();
            annotations.clear();
            return;
        }
        List<SeedPoint> seeds = source.filtered();
        if (seeds.size() > RENDER_CAP) seeds = source.topKByConfidence(RENDER_CAP);
        pushed = seeds;

        final ColorTable table = source.getColorTable();
        final SeedOverlay.ColorMode mode = source.getColorMode();
        final double low = source.getLowConfidence();
        final double high = source.getHighConfidence();
        final double transparency = source.getTransparency();
        final Map<SeedPoint, Integer> seedIndexMap = (mode == SeedOverlay.ColorMode.INDEX)
                ? indexMap(source.list()) : null;
        final Map<String, Integer> categoryOrdinals =
                (mode == SeedOverlay.ColorMode.TYPE || mode == SeedOverlay.ColorMode.SOURCE)
                        ? categoryOrdinals(source.list(), mode) : null;

        final List<SNTPoint> points = new ArrayList<>(pushed.size());
        final List<Float> sizes = new ArrayList<>(pushed.size());
        final List<Color> colors = new ArrayList<>(pushed.size());
        for (final SeedPoint s : pushed) {
            points.add(s.toPointInImage());
            sizes.add((float) (s.radius > 0 ? s.radius : viewer.getDefaultMarkerSize()));
            // No slice concept in a 3D viewer, so depthFalloff is always 1
            colors.add(SeedOverlayRenderer.colorForSeed(table, mode, s, low, high,
                    transparency, seedIndexMap, categoryOrdinals));
        }
        annotations.replaceAll(points, sizes, colors);
    }

    private static Map<SeedPoint, Integer> indexMap(final List<SeedPoint> all) {
        final Map<SeedPoint, Integer> map = new HashMap<>(Math.max(16, all.size() * 2));
        for (int i = 0; i < all.size(); i++) map.put(all.get(i), i);
        return map;
    }

    private static Map<String, Integer> categoryOrdinals(final List<SeedPoint> all, final SeedOverlay.ColorMode mode) {
        final TreeSet<String> keys = new TreeSet<>();
        for (final SeedPoint sp : all) keys.add(mode == SeedOverlay.ColorMode.TYPE ? sp.type : sp.source);
        final Map<String, Integer> ordinals = new HashMap<>(Math.max(16, keys.size() * 2));
        int i = 0;
        for (final String k : keys) ordinals.put(k, i++);
        return ordinals;
    }
}
