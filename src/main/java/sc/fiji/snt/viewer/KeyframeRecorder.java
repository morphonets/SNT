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

/**
 * Viewer-agnostic keyframe capture/playback/recording logic shared by {@link Bvv} and {@link Bdv}.
 * Viewer-specific behavior is confined to the hooks declared in {@link AbstractBigViewer}
 * ({@code getViewerState()}, {@code getCamParams()}, {@code applyCamParams()}, {@code awaitRender()}).
 */
final class KeyframeRecorder {

    private final AbstractBigViewer viewer;

    KeyframeRecorder(final AbstractBigViewer viewer) {
        this.viewer = viewer;
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
        });
    }

    private static boolean saveFrame(final Component canvas, final File dir, final int index) {
        final BufferedImage bi = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        try {
            SwingUtilities.invokeAndWait(() -> canvas.paint(bi.getGraphics()));
        } catch (final Exception e) {
            SNTUtils.log("Screenshot failed at frame " + index);
            return false;
        }
        final File out = new File(dir, String.format("frame_%05d.png", index));
        try {
            ImageIO.write(bi, "PNG", out);
            return true;
        } catch (final IOException e) {
            SNTUtils.log("Failed to write " + out.getName() + ": " + e.getMessage());
            return false;
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
        final int cX = canvas.getWidth() / 2;
        final int cY = canvas.getHeight() / 2;
        int globalFrame = 0;
        try {
            for (int k = 1; k < keyframes.size(); k++) {
                final Keyframe from = keyframes.get(k - 1);
                final Keyframe to = keyframes.get(k);
                final int nFrames = to.frames;
                final SimilarityTransformAnimator animator = new SimilarityTransformAnimator(
                        from.transform, to.transform, cX, cY, 0);
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
                    if (save) saveFrame(canvas, dir, globalFrame);
                    globalFrame++;
                }
            }
            final Keyframe last = keyframes.getLast();
            applyState(last);
            showAndWait(last.transform, last.timepoint, last.display);
            if (save) {
                Thread.sleep(100); // allow final render
                saveFrame(canvas, dir, globalFrame);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            SNTUtils.log("Movie render interrupted at frame " + globalFrame);
            return;
        } catch (final IllegalStateException e) {
            SNTUtils.log("Movie render failed at frame " + globalFrame + ": " + e.getMessage());
            return;
        }
        if (save)
            System.out.println("Movie: " + (globalFrame + 1) + " frames saved to " + dir.getAbsolutePath());
        else
            System.out.println("Playback complete: " + (globalFrame + 1) + " frames");
    }
}
