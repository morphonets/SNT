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

import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.ConverterSetups;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SourceGroup;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.animate.SimilarityTransformAnimator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import sc.fiji.snt.SNTUtils;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Viewer-agnostic keyframe capture/playback/recording logic shared by {@link Bvv} and {@link Bdv}.
 * Viewer-specific behavior is confined to the hooks declared in {@link AbstractBigViewer}
 * ({@code getViewerState()}, {@code getCamParams()}, {@code applyCamParams()}, {@code awaitRender()}).
 */
final class KeyframeRecorder {

    private final AbstractBigViewer viewer;
    private volatile long settleMillis = 250;

    KeyframeRecorder(final AbstractBigViewer viewer) {
        this.viewer = viewer;
    }

    /** See {@link #setSettleMillis(long)} */
    long getSettleMillis() {
        return settleMillis;
    }

    /**
     * Sets the time without new render passes after which a frame is considered fully refined and is captured. Viewers
     * render progressively (coarse resolution first), so values that are too small may yield low-resolution frames,
     * while large values slow down recording. Default is 250 ms.
     *
     * @param millis the quiet period in milliseconds (non-negative)
     */
    void setSettleMillis(final long millis) {
        if (millis < 0) throw new IllegalArgumentException("Settle time must be non-negative");
        settleMillis = millis;
    }

    private SynchronizedViewerState state() {
        final SynchronizedViewerState state = viewer.getViewerState();
        if (state == null) throw new IllegalStateException("No viewer active");
        return state;
    }

    /** Snapshots the current viewer state */
    Keyframe capture() {
        final SynchronizedViewerState state = state();
        final double[] cam = viewer.getCamParams();
        final Set<String> actors = new LinkedHashSet<>();
        final List<SourceGroup> groups = state.getGroups();
        for (int g = 0; g < groups.size(); g++) {
            final SourceGroup grp = groups.get(g);
            if (state.isGroupActive(grp)) actors.add("vol:" + groupKey(state, grp, g));
        }
        // Every active source is recorded, grouped or not: applyState() sets the active flag of
        // all sources, so omitting grouped ones would deactivate them on playback
        final List<? extends SourceAndConverter<?>> srcs = state.getSources();
        for (int i = 0; i < srcs.size(); i++) {
            if (state.isSourceActive(srcs.get(i))) actors.add("src:" + i);
        }
        if (viewer.isPathRenderingEnabled()) actors.add("paths");
        final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
        if (ann != null && ann.isVisible()) actors.add("annotations");
        final Keyframe kf = new Keyframe(viewer.getViewerTransform(), cam[0], cam[1], cam[2], actors, 0);
        kf.timepoint = viewer.getCurrentTimepoint();
        kf.width = viewer.getViewerWidth();
        kf.height = viewer.getViewerHeight();
        final ConverterSetups setups = viewer.getConverterSetups();
        if (setups != null) {
            for (int i = 0; i < srcs.size(); i++) {
                final ConverterSetup cs = setups.getConverterSetup(srcs.get(i));
                if (cs == null) continue;
                final int rgb = (cs.supportsColor() && cs.getColor() != null) ? cs.getColor().get() & 0xFFFFFF : -1;
                kf.display.put(i, new Keyframe.SourceDisplay(cs.getDisplayRangeMin(), cs.getDisplayRangeMax(), rgb));
            }
        }
        return kf;
    }

    private static String groupKey(final SynchronizedViewerState state, final SourceGroup grp, final int idx) {
        final String name = state.getGroupName(grp);
        return name != null ? name : "group" + idx;
    }

    /** Applies everything but the transform: cam/slab, visibility, timepoint */
    void applyState(final Keyframe kf) {
        final SynchronizedViewerState state = state();
        viewer.applyCamParams(kf.dCam, kf.nearClip, kf.farClip);
        viewer.syncOverlays();
        final List<SourceGroup> groups = state.getGroups();
        for (int g = 0; g < groups.size(); g++) {
            final SourceGroup grp = groups.get(g);
            state.setGroupActive(grp, kf.visibleActors.contains("vol:" + groupKey(state, grp, g)));
        }
        final List<? extends SourceAndConverter<?>> srcs = state.getSources();
        for (int i = 0; i < srcs.size(); i++)
            state.setSourceActive(srcs.get(i), kf.visibleActors.contains("src:" + i));
        viewer.setPathRenderingEnabled(kf.visibleActors.contains("paths"));
        final AbstractBigViewer.AnnotationOverlay ann = viewer.annotations();
        if (ann != null) ann.setVisible(kf.visibleActors.contains("annotations"));
        if (kf.timepoint > 0) viewer.setCurrentTimepoint(kf.timepoint);
        applyDisplay(kf.display);
    }

    /** Applies levels and LUT colors to the sources at the given indices */
    private void applyDisplay(final Map<Integer, Keyframe.SourceDisplay> display) {
        final ConverterSetups setups = viewer.getConverterSetups();
        if (setups == null || display.isEmpty()) return;
        final List<? extends SourceAndConverter<?>> srcs = state().getSources();
        display.forEach((idx, d) -> {
            if (idx < 0 || idx >= srcs.size()) return;
            final ConverterSetup cs = setups.getConverterSetup(srcs.get(idx));
            if (cs == null) return;
            cs.setDisplayRange(d.min(), d.max());
            if (d.rgb() >= 0 && cs.supportsColor()) cs.setColor(new ARGBType(0xFF000000 | d.rgb()));
        });
    }

    /** Sets transform (and timepoint, if positive, and display settings, if any) on the EDT and waits for the render */
    private void showAndWait(final AffineTransform3D t, final int timepoint,
                             final Map<Integer, Keyframe.SourceDisplay> display) throws InterruptedException {
        viewer.awaitRender(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    viewer.getViewerState().setViewerTransform(t);
                    if (display != null) applyDisplay(display);
                    if (timepoint > 0 && timepoint != viewer.getCurrentTimepoint())
                        viewer.setCurrentTimepoint(timepoint);
                    viewer.repaint();
                });
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }, settleMillis);
    }

    /**
     * Creates the animator interpolating between two transforms. The animator is probed at both ends: if
     * it does not reproduce the keyframes (e.g., because it adds the center offset {@code cX/cY} to the
     * translation, shifting the whole scene), the inputs are pre-shifted to compensate.
     */
    private static SimilarityTransformAnimator animatorFor(final AffineTransform3D a, final AffineTransform3D b,
                                                           final double cX, final double cY) {
        SimilarityTransformAnimator animator = new SimilarityTransformAnimator(a, b, cX, cY, 0);
        final double[] d0 = offset(animator.get(0), a);
        final double[] d1 = offset(animator.get(1), b);
        if (Math.abs(d0[0]) > 1e-3 || Math.abs(d0[1]) > 1e-3 || Math.abs(d1[0]) > 1e-3 || Math.abs(d1[1]) > 1e-3) {
            animator = new SimilarityTransformAnimator(shifted(a, d0), shifted(b, d1), cX, cY, 0);
        }
        return animator;
    }

    private static double[] offset(final AffineTransform3D actual, final AffineTransform3D expected) {
        return new double[] { actual.get(0, 3) - expected.get(0, 3), actual.get(1, 3) - expected.get(1, 3) };
    }

    private static AffineTransform3D shifted(final AffineTransform3D t, final double[] delta) {
        final AffineTransform3D copy = t.copy();
        copy.set(t.get(0, 3) - delta[0], 0, 3);
        copy.set(t.get(1, 3) - delta[1], 1, 3);
        return copy;
    }

    /**
     * Grabs the canvas on the EDT and hands the PNG encoding/writing to {@code writer}, so that
     * rendering of the next frame is not blocked by disk I/O
     */
    private static boolean saveFrame(final Component canvas, final File dir, final int index,
                                     final ExecutorService writer) {
        final BufferedImage bi = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        try {
            SwingUtilities.invokeAndWait(() -> canvas.paint(bi.getGraphics()));
        } catch (final Exception e) {
            SNTUtils.log("Screenshot failed at frame " + index);
            return false;
        }
        final File out = new File(dir, String.format("frame_%05d.png", index));
        writer.execute(() -> {
            try {
                ImageIO.write(bi, "PNG", out);
            } catch (final IOException e) {
                SNTUtils.log("Failed to write " + out.getName() + ": " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Creates the pool that writes frames. The queue is bounded and the caller runs the task when
     * it is full, which throttles rendering instead of accumulating frames in memory
     */
    private static ExecutorService newFrameWriter() {
        final int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2 * n),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Waits for pending frames to be written */
    private static void flush(final ExecutorService writer) {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.MINUTES))
                SNTUtils.log("Timed out waiting for frames to be written");
        } catch (final InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** See {@link AbstractBigViewer#renderFrames(List, String)} */
    void render(final List<Keyframe> keyframes, final String outputDir) {
        if (!viewer.isOpen()) throw new IllegalStateException("No viewer active");
        if (keyframes.size() < 2) throw new IllegalArgumentException("Need at least 2 keyframes");
        final boolean save = outputDir != null;
        final File dir = save ? new File(outputDir) : null;
        if (save && !dir.exists() && !dir.mkdirs())
            throw new IllegalArgumentException("Cannot create output directory: " + outputDir);

        final Component canvas = viewer.getViewerCanvas();
        // Use the viewer's own notion of its size (the one its transform is centered on), which
        // is not necessarily the size of the canvas component, and adjust keyframes captured at another size
        final int vw = viewer.getViewerWidth();
        final int vh = viewer.getViewerHeight();
        final int cX = vw / 2;
        final int cY = vh / 2;
        final AffineTransform3D[] transforms = new AffineTransform3D[keyframes.size()];
        for (int k = 0; k < transforms.length; k++) {
            final Keyframe kf = keyframes.get(k);
            transforms[k] = kf.transformFor(vw, vh);
        }
        final ExecutorService writer = save ? newFrameWriter() : null;
        int globalFrame = 0;
        try {
            for (int k = 1; k < keyframes.size(); k++) {
                final Keyframe from = keyframes.get(k - 1);
                final Keyframe to = keyframes.get(k);
                final int nFrames = to.frames;
                final SimilarityTransformAnimator animator = animatorFor(transforms[k - 1], transforms[k], cX, cY);
                final boolean interpolateT = from.timepoint > 0 && to.timepoint > 0;
                // Visibility and cam/slab snap at keyframe boundaries
                applyState(from);
                if (!save) {
                    viewer.showViewerMessage(String.format("Keyframe %d to %d  (%d frames, %s)",
                            k - 1, k, nFrames, to.getAccelName()));
                }
                for (int d = 0; d < nFrames; d++) {
                    final double eased = Keyframe.accel((double) d / nFrames, to.accelType);
                    final int tp = interpolateT
                            ? (int) Math.round(from.timepoint + eased * (to.timepoint - from.timepoint))
                            : -1;
                    showAndWait(animator.get(eased), tp, Keyframe.interpolateDisplay(from, to, eased));
                    if (save) saveFrame(canvas, dir, globalFrame, writer);
                    globalFrame++;
                }
            }
            final Keyframe last = keyframes.getLast();
            applyState(last);
            showAndWait(transforms[transforms.length - 1], last.timepoint, last.display);
            if (save) {
                Thread.sleep(100); // allow final render
                saveFrame(canvas, dir, globalFrame, writer);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            SNTUtils.log("Movie render interrupted at frame " + globalFrame);
            return;
        } catch (final IllegalStateException e) {
            SNTUtils.log("Movie render failed at frame " + globalFrame + ": " + e.getMessage());
            return;
        } finally {
            if (writer != null) flush(writer);
        }
        if (save) {
            VideoInstructions.write(dir, 30, "frame_%05d.png");
            System.out.println("Movie: " + (globalFrame + 1) + " frames saved to " + dir.getAbsolutePath()
                    + " (see " + VideoInstructions.FILE_NAME + " to assemble the video)");
        }
        else
            System.out.println("Playback complete: " + (globalFrame + 1) + " frames");
    }
}
