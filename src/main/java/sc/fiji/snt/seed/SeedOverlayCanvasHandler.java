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

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Attaches a lightweight {@code MouseListener} to a {@link TracerCanvas} that
 * lets the user click on a rendered seed (with {@code Alt} held) to open the
 * per-seed edit dialog. Read-only on the canvas otherwise: regular clicks /
 * right-clicks pass through to the existing tracing-canvas handlers
 * unchanged.
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
     * Safe to call once per canvas (no de-duplication; SNT installs each canvas
     * exactly once during {@code initialize}).
     */
    public static void install(final TracerCanvas canvas, final SNT snt) {
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() != 1) return;
                if (e.getButton() != MouseEvent.BUTTON1) return;
                if (!e.isAltDown()) return;
                onAltClick(canvas, snt, e);
            }
        });
    }

    private static void onAltClick(final TracerCanvas canvas, final SNT snt, final MouseEvent e) {
        //Use only Alt+Click on a paused state so it doesn't compete with the
        // tracer's active-mode mouse handlers. Headless / UI-less callers
        // (snt.getUI() == null) ignore this
        if (snt.getUI() != null) {
            final int state = snt.getUI().getState();
            if (state != SNTUI.TRACING_PAUSED && state != SNTUI.SNT_PAUSED) return;
        }
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) return;

        // Click -> voxel coords on this pane (axes depend on plane)
        final double paneVoxelX = canvas.myOffScreenXD(e.getX());
        final double paneVoxelY = canvas.myOffScreenYD(e.getY());
        // Cursor depth = current slice on the depth axis for this pane
        final int currentDepthSlice = canvas.getImage().getZ() - 1;

        // Resolve to (physicalX, physicalY, physicalZ) in image-physical space
        final double[] phys = paneToPhysical(canvas.getPlane(),
                paneVoxelX, paneVoxelY, currentDepthSlice,
                snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth());

        // Physical tolerance ≈ PIXEL_TOLERANCE / magnification × in-plane spacing.
        // We use the larger of the two in-plane spacings to be forgiving.
        final double inPlanePxSize = Math.max(snt.getPixelWidth(), snt.getPixelHeight());
        final double physTolerance = PIXEL_TOLERANCE / Math.max(0.1, canvas.getMagnification()) * inPlanePxSize;

        final SeedPoint hit = overlay.nearest(phys[0], phys[1], phys[2], physTolerance);
        if (hit == null) return;

        // Suppress event propagation so the regular tracer click handlers don't
        // also act on this Alt+Click. (Alt is not used by tracing today, but
        // defensive consumption keeps the gesture private to seeds.)
        e.consume();
        // Open the modal on the EDT (we may already be on it; invokeLater is
        // extra safety in case future SNT changes route mouse events off-EDT).
        javax.swing.SwingUtilities.invokeLater(() -> {
            final int idx = overlay.indexOf(hit);
            if (idx >= 0) SeedPointEditDialog.editAt(canvas, overlay, idx);
        });
    }

    /**
     * Translates {@code (paneVoxelX, paneVoxelY, depthSlice)} on a given
     * canvas plane to physical {@code (x, y, z)} in image-calibrated units.
     * Mirrors {@code MultiDThreePanes.findPointInStackPrecise} but stays in
     * physical space so the result feeds {@link SeedOverlay#nearest} directly.
     */
    private static double[] paneToPhysical(final int plane,
                                           final double paneX, final double paneY, final int depth,
                                           final double sx, final double sy, final double sz) {
        return switch (plane) {
            case MultiDThreePanes.XZ_PLANE -> new double[]{paneX * sx, depth * sy, paneY * sz};
            case MultiDThreePanes.ZY_PLANE -> new double[]{depth * sx, paneY * sy, paneX * sz};
            default -> new double[]{paneX * sx, paneY * sy, depth * sz}; // MultiDThreePanes.XY_PLANE
        };
    }
}
