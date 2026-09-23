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

package sc.fiji.snt.gui;

import net.imglib2.realtransform.AffineTransform3D;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.viewer.AbstractBigViewer;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Drives a short, click-through tutorial over a live {@link AbstractBigViewer} (Bdv or Bvv, including Stream mode):
 * flies the camera to a region of interest, shows a {@link CalloutManager} callout describing what to do there, and
 * only advances once a  caller-supplied check confirms the user did it. A failed check re-shows an earlier (by default,
 * the same) step instead of silently moving on.
 * <p>
 * This class owns no tracing logic and reads only public SNT/{@link PathAndFillManager} state, so the same tutorial
 * works whether the viewer is Bdv or Bvv, and regardless of the data backing it (in-memory image or Stream mode).
 * </p>
 * <p>
 * Positioning relies on {@link AbstractBigViewer#flyTo(BoundingBox)} always centering its target on screen:
 * a single reused, invisible anchor kept at the canvas's center is enough to point a  callout at "wherever flyTo just
 * centered", with no per-frame tracking of the camera transform. The anchor's screen position is read from
 * {@link AbstractBigViewer#getViewerCanvas()}.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class GuidedTutorial {

    private final AbstractBigViewer viewer;
    private final List<Step> steps;
    private final String group;
    private JComponent anchor;
    private Runnable stateListener;
    private Runnable preAction;
    private long preActionSettleMs;
    private Runnable postAction;
    private int index;
    private int stepRunId; // bumped by every showCurrentStep() call; invalidates any Timer from a stale one
    private boolean stopped = true; // true until start(); also guards against a duplicate start()/stop()

    /**
     * @param viewer the live viewer to fly around and anchor callouts to
     * @param steps  the tutorial, in order; see {@link Step}
     */
    public GuidedTutorial(final AbstractBigViewer viewer, final List<Step> steps) {
        if (steps == null || steps.isEmpty())
            throw new IllegalArgumentException("steps == null or empty");
        this.viewer = viewer;
        this.steps = steps;
        this.group = "tutorial-" + System.identityHashCode(this);
    }

    /**
     * Sets a one-off action to run at the top of {@link #start()}, before the first step is shown, e.g. switching the
     * viewer into whatever tracing mode the tutorial expects. Replaces any previously set action; {@code null} (the
     * default) runs nothing.
     *
     * @return this, for chaining onto the constructor call
     */
    public GuidedTutorial setPreAction(final Runnable preAction) {
        return setPreAction(preAction, 0);
    }

    /**
     * As {@link #setPreAction(Runnable)}, but waits {@code settleMs} after running {@code preAction} before showing the
     * first step, instead of showing it immediately. Useful when {@code preAction} triggers window  arranging/resizing
     * or anything else whose on-screen effects don't land synchronously.
     *
     * @return this, for chaining onto the constructor call
     */
    public GuidedTutorial setPreAction(final Runnable preAction, final long settleMs) {
        this.preAction = preAction;
        this.preActionSettleMs = settleMs;
        return this;
    }

    /**
     * Sets a one-off action to run at the end of {@link #stop()}, once the tutorial's own teardown is done e.g., to
     * undo whatever {@link #setPreAction(Runnable)} changed. Runs exactly once per  {@link #start()}/{@link #stop()}
     * pair, even though {@link #stop()} itself is safe to call more than once; runs even if the teardown above throws
     * Replaces any previously set action; {@code null} (the default) runs nothing.
     *
     * @return this, for chaining onto the constructor call
     */
    public GuidedTutorial setPostAction(final Runnable postAction) {
        this.postAction = postAction;
        return this;
    }

    /**
     * Runs {@link #setPreAction}, waits its settle delay (if any), then starts the tutorial from its first step
     */
    public void start() {
        if (!stopped) return;
        stopped = false;
        if (preAction != null) preAction.run();
        index = 0;
        stateListener = this::onCalloutStateChanged;
        CalloutManager.addStateListener(stateListener, group);
        if (preActionSettleMs > 0) {
            final Timer timer = new Timer((int) preActionSettleMs, e -> {
                if (!stopped) showCurrentStep();
            });
            timer.setRepeats(false);
            timer.start();
        } else {
            showCurrentStep();
        }
    }

    /**
     * Aborts the tutorial: hides the current callout, forgets the anchor, unregisters the state listener, then runs
     * {@link #setPostAction}. Called automatically once the last step is dismissed.
     */
    public void stop() {
        if (stopped) return;
        stopped = true;
        try {
            CalloutManager.clearGroup(group);
            if (stateListener != null) CalloutManager.removeStateListener(stateListener);
            if (anchor != null && anchor.getParent() != null) anchor.getParent().remove(anchor);
            anchor = null;
        } finally {
            if (postAction != null) postAction.run();
        }
    }

    private void showCurrentStep() {
        final int myRunId = ++stepRunId;
        final Step step = steps.get(index);
        final boolean flying = step.roi() != null && viewer.flyTo(step.roi());
        if (step.cameraAction() != null) {
            if (flying) {
                // flyTo() just started its own animation (see AbstractBigViewer#FLY_TO_DURATION_MS) by installing a new
                // transform animator; running cameraAction() right now would  read the pre-flyTo transform (no frame of
                // that animation has rendered yet) and then immediately replace it with cameraAction()'s own animator
                // which cancels an in-flight one outright (see Bvv/Bdv#setViewerTransform), so the camera would pivot
                // on wherever it was before this step, and would never actually visit the roi. Deferring until flyTo()
                // has settled lets cameraAction() pivot on the roi it framed.
                // Guarded by myRunId/stopped: if the tutorial advances past (or stops) this step before the 300ms elapse,
                // e.g. isDone() was already true and the user dismissed  the callout right away, this stale action must
                // not fire against a later step
                final Timer timer = new Timer((int) AbstractBigViewer.FLY_TO_DURATION_MS,
                        e -> {
                            if (!stopped && myRunId == stepRunId) step.cameraAction().accept(viewer);
                        });
                timer.setRepeats(false);
                timer.start();
            } else {
                step.cameraAction().accept(viewer);
            }
        }
        final Component target = (step.calloutTarget() == null) ? null : step.calloutTarget().apply(viewer);
        // CalloutManager.add() only replaces a PRIOR registration in place when it is the exact same owner instance:
        // most steps share the one reused anchor() component, so e-adding for those collapses into a single slot but a
        // step using Step#pointingAt() (or one
        // that follows it) registers a genuinely different owner, which add() instead keeps as an extra, permanent
        // entry alongside it. Left alone, entriesFor(group) then hands showAll() BOTH the stale previous-step
        // registration and the new one, rendered as a "1/2"-style chain with the stale one shown first.
        // clearRegistrations() (unlike clearGroup()) drops those stale entries without also unregistering stateListener
        // below, which drives our own advancement
        CalloutManager.clearRegistrations(group);
        CalloutManager.add((target != null) ? target : anchor(), CalloutManager.AUTO, step.message(), group);
        CalloutManager.showAll(group);
    }

    // Fires whenever this tutorial's callout chain starts, ends, or is paused/resumed. A single-entry chain going
    // inactive means the user just dismissed the step that was showing (isActive() is checked, not assumed, since
    // Escape only pauses the chain)
    private void onCalloutStateChanged() {
        if (CalloutManager.isActive(group)) return;
        final Step finished = steps.get(index);
        if (finished.isDone().getAsBoolean()) {
            if (++index >= steps.size()) {
                stop();
                return;
            }
        } else {
            index = (finished.retryIndex() < 0) ? index : finished.retryIndex();
        }
        // Deferred rather than called right here: this method itself runs synchronously off CalloutPanel#onClosed() ->
        // fireStateChanged(), nested inside CalloutPanel#gotIt(), which still has cleanup of its own to run
        // (PREFS.putBoolean(), then its own stale onDismiss callback) after this method returns. That stale onDismiss
        // ends up in showChain()'s chain-exhausted branch, which unconditionally clears CalloutManager's activeChains
        // entry for this group - if the next step's callout were installed synchronously right here, that stale cleanup
        // would run straight afterward and clobber it. invokeLater() lets gotIt()'s whole call stack (including that
        // stale tail code) unwind first
        SwingUtilities.invokeLater(() -> {
            if (!stopped) showCurrentStep();
        });
    }

    // A single reused, invisible anchor kept centered on the viewer canvas: flyTo() always
    // centers its target on screen, so re-centering this once per step is all that is needed
    private JComponent anchor() {
        final JFrame frame = viewer.getViewerFrame();
        if (anchor == null) {
            anchor = new JPanel();
            anchor.setOpaque(false);
            anchor.setFocusable(false);
            frame.getLayeredPane().add(anchor, JLayeredPane.PALETTE_LAYER);
        }
        final Component canvas = viewer.getViewerCanvas();
        final Point center = (canvas != null && canvas.isShowing())
                // canvas's own center, converted straight into the layered pane's coordinate space: exact even when a
                // side panel leaves the canvas off-center within the frame's content pane
                ? SwingUtilities.convertPoint(canvas, canvas.getWidth() / 2, canvas.getHeight() / 2,
                frame.getLayeredPane())
                : fallbackCenter(frame); // canvas not showing yet (e.g. frame still opening)
        anchor.setBounds(center.x - 1, center.y - 1, 2, 2);
        return anchor;
    }

    // Pre-canvas fallback: approximates the canvas as centered in the content pane, which is only
    // wrong by the (static) width of a side panel, if any - immaterial here since this is only
    // ever used before the canvas has a size to get wrong in the first place
    private Point fallbackCenter(final JFrame frame) {
        final Dimension paneSize = frame.getContentPane().getSize();
        final int cw = viewer.getViewerWidth(), ch = viewer.getViewerHeight();
        return new Point(Math.max(0, (paneSize.width - cw) / 2) + cw / 2,
                Math.max(0, (paneSize.height - ch) / 2) + ch / 2);
    }

    /**
     * One step of a {@link GuidedTutorial}.
     *
     * @param message       the (HTML-capable) callout text
     * @param roi           world-coordinate region to fly the camera to before showing the callout ({@code null} keeps
     *                      the current view, e.g. for a step that reuses the previous one's framing)
     * @param isDone        checked once the user dismisses this step's callout; {@code true} advances to the next step,
     *                      {@code false} re-shows {@link #retryIndex()}
     * @param retryIndex    the (0-based) step index to fall back to when {@code isDone} fails; a negative value retries
     *                      this same step (see {@link #of})
     * @param calloutTarget resolves a real on-screen control to anchor the callout to instead of the roi's on-screen
     *                      center (see {@link #pointingAt}); {@code null} (the common case) keeps the default
     *                      canvas-center anchor. {@code roi}, if any, still just frames the camera either way
     */
    public record Step(String message, BoundingBox roi, BooleanSupplier isDone, int retryIndex,
                       Consumer<AbstractBigViewer> cameraAction,
                       Function<AbstractBigViewer, Component> calloutTarget) {

        /**
         * A step that, on failure, simply retries itself (the common case), with no camera action
         */
        public static Step of(final String message, final BoundingBox roi, final BooleanSupplier isDone) {
            return new Step(message, roi, isDone, -1, null, null);
        }

        /**
         * Same as {@link #of(String, BoundingBox, BooleanSupplier)}, but also runs
         * {@code cameraAction} (e.g. {@link GuidedTutorial#rotate(int, double, long)}) right after
         * {@code roi} is flown to.
         */
        public static Step of(final String message, final BoundingBox roi, final BooleanSupplier isDone,
                              final Consumer<AbstractBigViewer> cameraAction) {
            return new Step(message, roi, isDone, -1, cameraAction, null);
        }

        /**
         * Same step, but anchoring the callout to a real on-screen control - e.g.
         * {@code v -> v.getNavigationModeButton()} - instead of the roi's on-screen center. Useful
         * for a step that explains a UI control rather than a location in the 3D scene; {@code roi}
         * (if any) still just frames the camera as usual. A resolver that returns {@code null} (the
         * control not built/showing yet) falls back to the default canvas-center anchor.
         */
        public Step pointingAt(final Function<AbstractBigViewer, Component> calloutTarget) {
            return new Step(message, roi, isDone, retryIndex, cameraAction, calloutTarget);
        }
    }

    /**
     * Returns a {@link Step} camera action that orbits the view by {@code degrees} around
     * {@code axis} (0 = X, i.e. tumbling around the horizontal screen axis; 1 = Y, tumbling around
     * the vertical screen axis; 2 = Z, spinning in-plane around the line of sight), pivoting on the
     * current screen center - i.e. on whatever the step's own {@code roi} just centered via
     * {@link AbstractBigViewer#flyTo(BoundingBox)} - so the framed region stays put and only
     * orbits, rather than swinging off-screen. Animates over {@code durationMs} (0 = immediate).
     * <p>
     * The pivot-preserving matrix is composed by hand (plain {@code get}/{@code set} arithmetic)
     * rather than via {@code AffineTransform3D#rotate}, which pivots on the transform's own
     * (world) origin, not on whatever is currently centered on screen.
     * </p>
     */
    public static Consumer<AbstractBigViewer> rotate(final int axis, final double degrees, final long durationMs) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        return viewer -> {
            final double rad = Math.toRadians(degrees);
            final double cos = Math.cos(rad), sin = Math.sin(rad);
            final double[][] r = switch (axis) {
                case 0 -> new double[][]{{1, 0, 0}, {0, cos, -sin}, {0, sin, cos}};
                case 1 -> new double[][]{{cos, 0, sin}, {0, 1, 0}, {-sin, 0, cos}};
                default -> new double[][]{{cos, -sin, 0}, {sin, cos, 0}, {0, 0, 1}};
            };
            // p: the screen point the rotation must leave fixed (the viewport center); t: the
            // translation that keeps it fixed, i.e. t = p - R*p
            final double[] p = {viewer.getViewerWidth() / 2.0, viewer.getViewerHeight() / 2.0, 0};
            final double[] t = new double[3];
            for (int i = 0; i < 3; i++) {
                t[i] = p[i];
                for (int j = 0; j < 3; j++)
                    t[i] -= r[i][j] * p[j];
            }
            // target = A * current, with A = [r | t] the pivot-preserving rotation above
            final AffineTransform3D current = viewer.getViewerTransform();
            final AffineTransform3D target = new AffineTransform3D();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 4; j++) {
                    double v = 0;
                    for (int k = 0; k < 3; k++)
                        v += r[i][k] * current.get(k, j);
                    if (j == 3) v += t[i];
                    target.set(v, i, j);
                }
            }
            viewer.setViewerTransform(target, durationMs);
        };
    }
}
