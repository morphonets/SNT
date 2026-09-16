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
import java.util.Set;
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
 * Alt+Click on a rendered seed (while the SNT UI is idle - see {@code SNTUI#isReady()} - or when no SNT
 * instance is attached) opens the same {@link SeedPointEditDialog} used by the canvas handler.
 * </p>
 * <p>
 * Alt is also {@code AbstractBigViewer.AbstractTracer}'s fork-a-path modifier: whenever the viewer's own
 * Start/Stop Tracing toggle is on, {@code AbstractTracer} reacts to every click - Alt or not - in
 * {@code mouseReleased}, which AWT always dispatches before this handler's {@code mouseClicked}. So by the
 * time {@link #onAltClick} would run, a fork/trace attempt from the very same click may already have fired
 * (at best a spurious "No Fork Point Found" dialog; at worst a real node inserted into a path, or a segment
 * traced) - {@code e.consume()} here cannot undo that. {@link #onAltClick} therefore additionally requires
 * {@link AbstractBigViewer#isTracingEnabled()} to be {@code false} for that viewer, so seed-editing via
 * Alt+Click and fork-via-Alt+Click never both act on one click; toggle tracing off in that viewer to edit
 * a seed by Alt+Click. {@link #onPlainClick} shares the same underlying hazard - a plain click also drives
 * {@code AbstractTracer} in {@code mouseReleased} (starting/extending a path), fork or not - so it requires
 * {@link AbstractBigViewer#isTracingEnabled()} to be {@code false} too, unlike {@code BookmarkManager}'s
 * plain-click-to-select, which does not gate on it.
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
     * Seeds (among {@link #pushed}) whose highlight was last reflected onto the viewer's shared
     * {@code AnnotationOverlay#setSelectedIndex} slot. Tracked so {@link #syncHighlight} only
     * touches that slot when this handler's own selection actually changed, rather than on every
     * unrelated overlay refresh (new/removed seeds, confidence-range changes, etc.) - the slot is
     * shared across owners (e.g. {@link sc.fiji.snt.BookmarkManager}), so an unconditional write on
     * every refresh could silently clear another owner's active highlight.
     */
    private Set<SeedPoint> lastHighlighted = Set.of();

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
        // NB: viewer.annotations() is not cached - Bdv/Bvv initialize their AnnotationOverlay lazily (on attach()), so
        // it may still be null when this handler is installed (see SNTUI#setBdvOnEDT/#setBvvOnEDT). Fetch it fresh on
        // every use instead, mirroring BookmarkManager's pattern
        viewer.addMouseListenerToDisplay(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() != 1 || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.isAltDown()) {
                    onAltClick(e);
                } else {
                    onPlainClick(e);
                }
            }
        });
        overlay.addListener(listener);
        sync(overlay);
    }

    /** Detaches from {@code overlay} and clears the viewer's annotations */
    public void dispose() {
        overlay.removeListener(listener);
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations != null) {
            annotations.clear(SeedOverlayBigViewerHandler.class);
            clearHighlightIfOwned(annotations);
        }
    }

    private void onAltClick(final MouseEvent e) {
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations == null) {
            return;
        }
        final SNT snt = viewer.getSNT();
        // NB: TRACING_PAUSED alone is not a valid "idle" test: in stream mode without a materialized crop, the UI sits
        // in STREAMING and does not go to TRACING_PAUSED. SNTUI#isReady() is the shared "UI is idle enough to
        // trace/edit/analyze" test (covers READY/TRACING_PAUSED/SNT_PAUSED/STREAMING)
        final boolean uiReady = (snt == null) || (snt.getUI() == null) || snt.getUI().isReady();
        if (!uiReady) {
            return;
        }
        // Alt is also the fork-a-path modifier for AbstractTracer#handleClick, which already ran (in mouseReleased) by
        // the time this mouseClicked-based listener sees the event. Back off entirely while this viewer's own
        // tracing-by-click is enabled, rather than risk silently editing a seed on the same click that just
        // forked/extended a path.
        if (viewer.isTracingEnabled()) {
            return;
        }
        // Owner-scoped: restricts the hit-test to this handler's own layer and returns an index local to it
        // (matching pushed's indexing), so a click on e.g. a bookmark marker sharing
        // the same overlay is not mistaken for a seed hit
        final int idx = annotations.hitTest(SeedOverlayBigViewerHandler.class, e.getX(), e.getY());
        if (idx < 0 || idx >= pushed.size()) {
            return;
        }
        e.consume();
        final SeedPoint hit = pushed.get(idx);
        SwingUtilities.invokeLater(() -> {
            final int overlayIdx = overlay.indexOf(hit);
            if (overlayIdx >= 0) SeedPointEditDialog.editAt(viewer.getViewerFrame(), overlay, overlayIdx);
        });
    }

    /**
     * Plain left-click on a rendered seed selects it, mirroring {@link sc.fiji.snt.BookmarkManager}'s
     * click-to-select behavior: the click flows through {@link SeedOverlay#setSelectedSeeds} so the Seed Manager table
     * selection and the classic 2D canvas highlight (see {@code SeedOverlayRenderer}) stay in sync; {@link #syncOnEdt}
     * then reflects the new selection as the viewer's highlighted annotation. Misses (no seed under the click) are
     * ignored, exactly like {@code BookmarkManager}, so plain clicks used to pan/rotate/recenter the viewer are not
     * hijacked into clearing the selection.
     * <p>
     * Unlike {@code BookmarkManager}, this also requires {@link AbstractBigViewer#isTracingEnabled()}
     * to be {@code false}, see its {@link #onAltClick}'s identical rationale.
     * </p>
     */
    private void onPlainClick(final MouseEvent e) {
        final AbstractBigViewer.AnnotationOverlay annotations = viewer.annotations();
        if (annotations == null) {
            return;
        }
        // Mirrors onAltClick()'s gate: a plain click also drives AbstractTracer#handleClick in
        // mouseReleased (which always runs before this mouseClicked-based listener), so selecting a
        // seed here could otherwise coincide with starting/extending/forking a path on the same click.
        if (viewer.isTracingEnabled())
            return;
        // Owner-scoped: see onAltClick()'s identical rationale
        final int idx = annotations.hitTest(SeedOverlayBigViewerHandler.class, e.getX(), e.getY());
        if (idx < 0 || idx >= pushed.size()) return;
        e.consume();
        overlay.setSelectedSeeds(List.of(pushed.get(idx)));
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
            annotations.clear(SeedOverlayBigViewerHandler.class);
            clearHighlightIfOwned(annotations);
            return;
        }
        List<SeedPoint> seeds = source.filtered();
        if (seeds.size() > RENDER_CAP) seeds = source.topKByConfidence(RENDER_CAP);
        pushed = seeds;

        final ColorTable table = source.getColorTable();
        final Color unknownConfidenceColor = source.getUnknownConfidenceColor();
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
            colors.add(SeedOverlayRenderer.colorForSeed(table, unknownConfidenceColor, mode, s, low, high,
                    transparency, seedIndexMap, categoryOrdinals));
        }
        annotations.replaceAll(SeedOverlayBigViewerHandler.class, points, sizes, colors);
        syncHighlight(annotations, source);
    }

    /**
     * Reflects {@code source}'s current selection onto the viewer's shared highlight slot
     * ({@code AnnotationOverlay#setSelectedIndex}), but only when this handler's own selection has actually changed
     * since the last call
     * @see #lastHighlighted
     */
    private void syncHighlight(final AbstractBigViewer.AnnotationOverlay annotations, final SeedOverlay source) {
        final Set<SeedPoint> selected = new java.util.LinkedHashSet<>(source.getSelectedSeeds());
        selected.retainAll(pushed);
        if (selected.equals(lastHighlighted)) return;
        lastHighlighted = selected;
        int idx = -1;
        if (!selected.isEmpty()) {
            for (int i = 0; i < pushed.size(); i++) {
                if (selected.contains(pushed.get(i))) { idx = i; break; }
            }
        }
        annotations.setSelectedIndex(SeedOverlayBigViewerHandler.class, idx);
    }

    private void clearHighlightIfOwned(final AbstractBigViewer.AnnotationOverlay annotations) {
        if (lastHighlighted.isEmpty()) return;
        lastHighlighted = Set.of();
        annotations.setSelectedIndex(SeedOverlayBigViewerHandler.class, -1);
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
