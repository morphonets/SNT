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

import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.TracerCanvas;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.util.PointInCanvas;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Attaches a lightweight {@code MouseListener} to a {@link TracerCanvas} that lets the user
 * click on a rendered seed to select it (mirroring {@code SeedOverlayBigViewerHandler}'s
 * plain-click behavior for Bvv/Bdv.Selection flows through {@link SeedOverlay#setSelectedSeeds}
 * so the Seed Manager table stays in sync), or Alt+Click it to open the per-seed edit dialog.
 * Right-clicks pass through to the existing tracing-canvas handlers unchanged.
 * <p>
 * Selection (plain click) is intentionally narrower-gated than editing (Alt+Click): the classic
 * canvas traces on plain click by default whenever the UI is idle, and - unlike Bvv/Bdv, where
 * tracing-by-click is an opt-in per-viewer toggle - {@code InteractiveTracerCanvas} handles that
 * in {@code mouseReleased}, which runs before this class's listener ever sees the click, so
 * {@code e.consume()} here cannot suppress it. {@link #onPlainClick} therefore only acts while
 * the UI is genuinely paused ({@code SNT_PAUSED}/{@code TRACING_PAUSED} - see
 * {@link #isPausedForSelection}), the same states in which {@code InteractiveTracerCanvas}
 * disables click-to-trace itself, so the two gestures never collide. {@link #onAltClick} keeps
 * the broader {@link #isUiReady} test since Alt is not used by tracing today.
 * <p>
 * The hit-test uses the {@link SeedOverlay}'s spatial index
 * ({@link SeedOverlay#nearest(double, double, double, double)}). Tolerance is
 * derived from the canvas magnification so it matches what the user
 * <i>sees</i> on screen.
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 * @see SeedPointEditDialog
 */
public final class SeedOverlayCanvasHandler {

    /** Screen-pixel tolerance for hit-testing on Alt+Click. */
    private static final double PIXEL_TOLERANCE = 12.0;

    private SeedOverlayCanvasHandler() {}

    /**
     * Installs the Alt+Click → edit-nearest-seed listener on the given canvas.
     */
    public static void install(final TracerCanvas canvas, final SNT snt) {
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() != 1 || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.isAltDown()) {
                    onAltClick(canvas, snt, e);
                } else {
                    onPlainClick(canvas, snt, e);
                }
            }
        });
    }

    /**
     * Checks whether the UI is idle enough for a seed-related click gesture (selection or edit) to proceed.
     * See {@code SeedOverlayBigViewerHandler#onAltClick()}.
     */
    private static boolean isUiReady(final SNT snt) {
        return snt.getUI() == null || snt.getUI().isReady();
    }

    /**
     * Narrower than {@link #isUiReady}: true only when the UI is genuinely paused ({@code SNT_PAUSED} or
     * {@code TRACING_PAUSED}), or absent (headless/no SNT instance attached). These are exactly the states in which
     * {@code InteractiveTracerCanvas} disables its own click-to-trace ({@code SNT_PAUSED} via
     * {@code isEventsDisabled()} in {@code mouseReleased}, {@code TRACING_PAUSED} via {@code handleCanvasClick}'s own
     * early-return switch) - so gating {@link #onPlainClick} on this, rather than the broader {@link #isUiReady}
     * (which also accepts READY/STREAMING, where click-to-trace is very much active), guarantees selecting a seed by
     * plain click never also drops a path point at the same location.
     */
    private static boolean isPausedForSelection(final SNT snt) {
        final SNTUI ui = snt.getUI();
        return ui == null || ui.getState() == SNTUI.TRACING_PAUSED || ui.getState() == SNTUI.SNT_PAUSED;
    }

    /**
     * Hit-tests {@code e}'s click position against {@code snt}'s {@link SeedOverlay}, within a
     * physical-space tolerance derived from {@link #PIXEL_TOLERANCE} and the canvas's current
     * magnification. Shared by {@link #onAltClick} and {@link #onPlainClick} so both gestures hit-
     * test identically. Returns {@code null} if the overlay is null/empty or nothing is within
     * tolerance.
     */
    private static SeedPoint hitTestSeed(final TracerCanvas canvas, final SNT snt, final MouseEvent e,
                                          final SeedOverlay overlay) {
        // Click -> voxel coords on this pane (axes depend on plane)
        final double paneVoxelX = canvas.myOffScreenXD(e.getX());
        final double paneVoxelY = canvas.myOffScreenYD(e.getY());
        // Cursor depth = current slice on the depth axis for this pane
        final int currentDepthSlice = canvas.getImage().getZ() - 1;

        // Resolve to (physicalX, physicalY, physicalZ) in image-physical/world space. This canvas may be indexed
        // by a local grid offset from world (a Stream-mode materialized  crop, or any source with a non-zero
        // SNT#getWorldOriginOffset()) - correct via SNT#getActiveCanvasPixelOffset()
        final double[] phys = paneToPhysical(canvas.getPlane(),
                paneVoxelX, paneVoxelY, currentDepthSlice,
                snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth(),
                snt.getActiveCanvasPixelOffset());

        // Physical tolerance ≈ PIXEL_TOLERANCE / magnification × in-plane spacing.
        // We use the larger of the two in-plane spacings to be forgiving.
        final double inPlanePxSize = Math.max(snt.getPixelWidth(), snt.getPixelHeight());
        final double physTolerance = PIXEL_TOLERANCE / Math.max(0.1, canvas.getMagnification()) * inPlanePxSize;

        return overlay.nearest(phys[0], phys[1], phys[2], physTolerance);
    }

    private static void onAltClick(final TracerCanvas canvas, final SNT snt, final MouseEvent e) {
        // Use only Alt+Click while the UI is idle, so it doesn't compete with the tracer's active-mode mouse handlers
        // see isUiReady()
        if (!isUiReady(snt)) {
            return;
        }
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            return;
        }

        final SeedPoint hit = hitTestSeed(canvas, snt, e, overlay);
        if (hit == null) {
            return;
        }

        // Suppress event propagation so the regular tracer click handlers don't
        // also act on this Alt+Click. (Alt is not used by tracing today, but
        // defensive consumption keeps the gesture private to seeds.)
        e.consume();
        // Open the modal on the EDT (we may already be on it; invokeLater is
        // extra safety in case future SNT changes route mouse events off-EDT).
        javax.swing.SwingUtilities.invokeLater(() -> {
            final int idx = overlay.indexOf(hit);
            if (idx >= 0) SeedPointEditDialog.editAt(canvas.getImage().getWindow(), overlay, idx);
        });
    }

    /**
     * Plain left-click on a rendered seed selects it, mirroring {@code SeedOverlayBigViewerHandler#onPlainClick()}'s
     * behavior for Bvv/Bdv: the click flows through {@link SeedOverlay#setSelectedSeeds}, so the Seed Manager table
     * selection and the highlight ring drawn by {@code SeedOverlayRenderer} (both already wired to that selection
     * set) update automatically.  {@code SeedOverlay}'s own change listener already calls a {@code repaintAllPanes()}
     * on every selection change. Misses (no seed under the click) are ignored like {@link #onAltClick}.
     * <p>
     * Gated by {@link #isPausedForSelection}, not the broader {@link #isUiReady} that {@link #onAltClick} uses: it
     * keeps this selection gesture confined to the states where {@code InteractiveTracerCanvas}'s own click-to-trace
     * is disabled, so the two never compete for the same plain click.
     */
    private static void onPlainClick(final TracerCanvas canvas, final SNT snt, final MouseEvent e) {
        if (!isPausedForSelection(snt)) {
            return;
        }
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            return;
        }
        final SeedPoint hit = hitTestSeed(canvas, snt, e, overlay);
        if (hit == null) {
            return;
        }
        overlay.setSelectedSeeds(List.of(hit));
    }

    /**
     * Translates {@code (paneVoxelX, paneVoxelY, depthSlice)} on a given
     * canvas plane to physical {@code (x, y, z)} in image-calibrated units.
     * Mirrors {@code MultiDThreePanes.findPointInStackPrecise} but stays in
     * physical space so the result feeds {@link SeedOverlay#nearest} directly.
     */
    private static double[] paneToPhysical(final int plane,
                                           final double paneX, final double paneY, final int depth,
                                           final double sx, final double sy, final double sz,
                                           final PointInCanvas offset) {
        // world = (voxel - canvasOffset) * spacing, the inverse of SeedOverlayRenderer's
        // voxel = world / spacing + canvasOffset
        return switch (plane) {
            case MultiDThreePanes.XZ_PLANE -> new double[]{
                    (paneX - offset.x) * sx, (depth - offset.y) * sy, (paneY - offset.z) * sz};
            case MultiDThreePanes.ZY_PLANE -> new double[]{
                    (depth - offset.x) * sx, (paneY - offset.y) * sy, (paneX - offset.z) * sz};
            default -> new double[]{
                    (paneX - offset.x) * sx, (paneY - offset.y) * sy, (depth - offset.z) * sz}; // MultiDThreePanes.XY_PLANE
        };
    }
}
