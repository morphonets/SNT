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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import bdv.tools.transformation.TransformedSource;
import bdv.viewer.SourceAndConverter;
import bvv.vistools.BvvSource;
import bvv.vistools.BvvStackSource;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import sc.fiji.snt.SNTUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of {@link BvvSource} objects that are treated as a logical unit:
 * display properties (color, range, active state) and manual transforms applied
 * to the <em>leader</em> source are propagated to all <em>follower</em> sources.
 *
 * <p>This is particularly useful for:
 * <ul>
 *   <li>Multi-channel images where each channel is a separate BVV source but
 *       should move together during manual registration (press {@code T} in BVV
 *       to enter manual transform mode).</li>
 *   <li>Multi-image workflows where multiple volumes must be grouped and
 *       manipulated as one.</li>
 * </ul>
 *
 * <p><b>Transform synchronization</b> can operate in two modes:
 * <ul>
 *   <li><b>Live (auto)</b>: a {@code renderTransformListeners} listener
 *       propagates each incremental transform to followers on every render
 *       frame. May cause visual lag on slow GPUs with many followers.</li>
 *   <li><b>Manual (triggered)</b>: {@link #syncTransforms()} is called
 *       explicitly (e.g., from a toolbar button) to apply the most recently
 *       cached leader transform to all followers.</li>
 * </ul>
 */
public class BvvMultiSource {

    private final BvvStackSource<?> leader;
    private final List<BvvStackSource<?>> followers;
    private final AffineTransform3D cachedLeaderTransform = new AffineTransform3D();
    private boolean liveSync = true;

    /**
     * Creates a group with a designated leader and zero or more followers.
     *
     * @param leader    the primary source; its transform drives the group
     * @param followers additional sources that mirror the leader's transform
     */
    public BvvMultiSource(final BvvStackSource<?> leader,
                          final List<BvvStackSource<?>> followers) {
        this.leader = leader;
        this.followers = new ArrayList<>(followers);
        installTransformListener();
    }

    /**
     * Convenience constructor for a single source (no followers).
     * Useful for keeping a consistent API when only one source exists.
     */
    public BvvMultiSource(final BvvStackSource<?> single) {
        this(single, Collections.emptyList());
    }

    // ── Transform synchronization ──────────────────────────────────────────

    /**
     * Installs a {@code renderTransformListeners} listener on the leader's
     * viewer panel. The listener caches the leader's current fixed transform
     * and, if {@link #liveSync} is enabled, immediately propagates it to all
     * followers.
     */
    private void installTransformListener() {
        leader.getBvvHandle().getViewerPanel().renderTransformListeners().add(t -> {
            // Cache the leader's per-source fixed transform (not the viewer transform)
            getSourceTransform(leader, cachedLeaderTransform);
            if (liveSync) {
                applyToFollowers(cachedLeaderTransform);
            }
        });
    }

    /**
     * Applies the most recently cached leader transform to all followers.
     * Call this explicitly when {@link #liveSync} is {@code false}, e.g.
     * from a "Sync Transforms" toolbar button.
     */
    public void syncTransforms() {
        getSourceTransform(leader, cachedLeaderTransform);
        applyToFollowers(cachedLeaderTransform);
        leader.getBvvHandle().getViewerPanel().requestRepaint();
        SNTUtils.log("BvvMultiSource: transform synced to " + followers.size() + " follower(s)");
    }

    /**
     * Applies the given transform as the fixed transform to the leader and all
     * followers. This is the primary entry point for loading a saved transform.
     *
     * @param transform the transform to apply
     */
    public void applyTransform(final AffineTransform3D transform) {
        cachedLeaderTransform.set(transform);
        applyToFollowers(transform);
        // Also apply to leader
        final List<? extends SourceAndConverter<?>> sacs = leader.getSources();
        if (sacs != null) {
            for (final SourceAndConverter<?> sac : sacs) {
                if (sac.getSpimSource() instanceof TransformedSource<?> ts)
                    ts.setFixedTransform(transform);
            }
        }
        leader.getBvvHandle().getViewerPanel().requestRepaint();
    }

    /**
     * Returns a copy of the most recently cached leader fixed transform.
     *
     * @param result set to the cached transform
     */
    public void getLeaderTransform(final AffineTransform3D result) {
        getSourceTransform(leader, result);
    }

    /**
     * Sets whether transforms are propagated to followers on every render
     * frame ({@code true}) or only on explicit {@link #syncTransforms()} calls
     * ({@code false}).
     *
     * @param live {@code true} for live sync, {@code false} for manual trigger
     */
    public void setLiveSync(final boolean live) {
        this.liveSync = live;
    }

    /** @return {@code true} if live transform sync is enabled */
    public boolean isLiveSync() {
        return liveSync;
    }

    // ── Display property delegation ────────────────────────────────────────

    /**
     * Sets the display range for all sources in the group.
     *
     * @param min minimum display value
     * @param max maximum display value
     */
    public void setDisplayRange(final double min, final double max) {
        leader.setDisplayRange(min, max);
        followers.forEach(f -> f.setDisplayRange(min, max));
    }

    /**
     * Sets the color for all sources in the group.
     *
     * @param color the ARGB color
     */
    public void setColor(final ARGBType color) {
        leader.setColor(color);
        followers.forEach(f -> f.setColor(color));
    }

    /**
     * Sets the active/visible state for all sources in the group.
     *
     * @param active {@code true} to show, {@code false} to hide
     */
    public void setActive(final boolean active) {
        leader.setActive(active);
        followers.forEach(f -> f.setActive(active));
    }

    /**
     * Removes all sources in the group from the viewer.
     */
    public void removeFromBvv() {
        followers.forEach(BvvSource::removeFromBdv);
        leader.removeFromBdv();
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /** @return the leader source */
    public BvvStackSource<?> getLeader() {
        return leader;
    }

    /** @return unmodifiable view of the follower sources */
    public List<BvvStackSource<?>> getFollowers() {
        return Collections.unmodifiableList(followers);
    }

    /** @return all sources (leader + followers) as an unmodifiable list */
    public List<BvvStackSource<?>> getSources() {
        final List<BvvStackSource<?>> all = new ArrayList<>();
        all.add(leader);
        all.addAll(followers);
        return Collections.unmodifiableList(all);
    }

    /** @return number of sources in the group (leader + followers) */
    public int size() {
        return 1 + followers.size();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Reads the current fixed transform of the first underlying
     * {@link TransformedSource} in a {@link BvvStackSource}.
     */
    private static void getSourceTransform(final BvvStackSource<?> source,
                                           final AffineTransform3D result) {
        final List<? extends SourceAndConverter<?>> sacs = source.getSources();
        if (sacs == null || sacs.isEmpty()) return;
        final Object spim = sacs.get(0).getSpimSource();
        if (spim instanceof TransformedSource<?> ts) {
            ts.getFixedTransform(result);
        }
    }

    /**
     * Applies {@code transform} as the fixed transform to all follower sources.
     */
    private void applyToFollowers(final AffineTransform3D transform) {
        for (final BvvStackSource<?> follower : followers) {
            final List<? extends SourceAndConverter<?>> sacs = follower.getSources();
            if (sacs == null) continue;
            for (final SourceAndConverter<?> sac : sacs) {
                final Object spim = sac.getSpimSource();
                if (spim instanceof TransformedSource<?> ts) {
                    ts.setFixedTransform(transform);
                }
            }
        }
    }
}
