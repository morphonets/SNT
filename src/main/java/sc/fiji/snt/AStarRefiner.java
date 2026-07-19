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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import sc.fiji.snt.util.SNTPoint;

/**
 * Post-hoc A* re-tracing of a single, already-existing {@link Path}: treats the path's current
 * nodes as waypoints and re-derives the geometry between them via {@link SNT#autoTraceSync(List,
 * sc.fiji.snt.util.PointInImage, SNT.SearchSettingsSnapshot)}, using either the cost
 * function/search parameters currently configured on the active {@link SNT} instance, or a frozen
 * {@link SNT.SearchSettingsSnapshot} shared across a whole batch (see {@link #AStarRefiner(SNT,
 * Path, SNT.SearchSettingsSnapshot)}).
 * <p>
 * Intended for manually-traced paths against data streamed from disk/network, where running A*
 * interactively may be too slow to be practical. See {@code PathManagerUI}'s "Refine"
 * menu ("Re-trace with A*...").
 * <p>
 * {@link #call()} is thread-safe for parallel execution across paths (it does not modify the
 * original path, and calls {@link SNT#autoTraceSync} rather than the interactive tracing entry
 * points, so concurrent workers do not serialize behind the single-threaded pool used by
 * interactive tracing). {@link #apply()} must be called sequentially afterward to commit the
 * result via {@link Path#replaceNodes(Path)}, which also reconciles this path's own branch point
 * (if it has a parent) and any of its children's branch points against the new geometry.
 *
 * @author Tiago Ferreira
 * @see sc.fiji.snt.analysis.MultiSpectralRefiner
 * @see PathFitter
 */
public class AStarRefiner implements Callable<Path> {

    /** Suffix appended to path names after a successful re-trace. */
    private static final String RETRACED_SUFFIX = " [Retraced*]";

    private final SNT snt;
    private final Path path;
    private final SNT.SearchSettingsSnapshot settings;

    // Channel/frame the live SNT instance was set to when this refiner was built. Used as a
    // defensive backstop in call(): if these no longer match by the time the search completes,
    // something changed the image data out from under this worker
    // (e.g. SNT#getBatchRetraceChannelFrame())
    private final int expectedChannel;
    private final int expectedFrame;

    private Path retraced;
    private boolean succeeded;
    private String failureReason;

    /**
     * Creates a refiner for a single path, reading search settings (cost function, data structure,
     * secondary/hessian image) live from {@code snt} at the time {@link #call()} actually runs.
     * <p>
     * Prefer {@link #AStarRefiner(SNT, Path, SNT.SearchSettingsSnapshot)} for batches of more than
     * one path, so all paths share identical settings even if the live A* controls are changed
     * mid-batch (they remain enabled during a batch by design).
     *
     * @param snt  the live SNT instance whose current A* search parameters (cost function,
     *             hessian, image data) are used
     * @param path the path to re-trace; must have at least 2 nodes
     * @throws IllegalArgumentException if path is null or has fewer than 2 nodes
     */
    public AStarRefiner(final SNT snt, final Path path) {
        this(snt, path, null);
    }

    /**
     * Creates a refiner for a single path, using a frozen {@link SNT.SearchSettingsSnapshot} instead
     * of the live search settings.
     *
     * @param snt      the live SNT instance whose image data is used
     * @param path     the path to re-trace; must have at least 2 nodes
     * @param settings a snapshot from {@link SNT#snapshotSearchSettings()}, or null to read the live
     *                 settings at call-time (equivalent to {@link #AStarRefiner(SNT, Path)})
     * @throws IllegalArgumentException if path is null or has fewer than 2 nodes
     */
    public AStarRefiner(final SNT snt, final Path path, final SNT.SearchSettingsSnapshot settings) {
        if (path == null || path.size() < 2)
            throw new IllegalArgumentException("Path must have at least 2 nodes");
        this.snt = snt;
        this.path = path;
        this.settings = settings;
        this.expectedChannel = snt.getChannel();
        this.expectedFrame = snt.getFrame();
    }

    /**
     * No-op: kept for consistency with {@link PathFitter}/{@link sc.fiji.snt.analysis.MultiSpectralRefiner},
     * which read persisted preferences here. A* re-tracing simply reuses whichever search
     * parameters are already configured on the live {@link SNT} instance.
     */
    public void readPreferences() {
        // no-op
    }

    /**
     * No-op: kept for consistency with the other {@code AbstractRefineHelper} workers; there are no
     * per-worker settings to propagate here (see {@link #readPreferences()}).
     *
     * @param ref the reference refiner (unused)
     */
    public void applySettings(final AStarRefiner ref) {
        // no-op
    }

    /**
     * Returns the original path being re-traced.
     *
     * @return the path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Whether the re-trace succeeded. Only meaningful after {@link #call()}.
     *
     * @return true if a valid re-traced path was produced
     */
    public boolean succeeded() {
        return succeeded;
    }

    /**
     * Human-readable reason {@link #call()} failed, or null if it succeeded (or hasn't run yet).
     *
     * @return the failure reason, or null
     */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Runs the A* re-trace on this path's current waypoints. Thread-safe: does not modify the
     * original path. Call {@link #apply()} afterward (sequentially) to commit results.
     *
     * @return the re-traced path, or null if re-tracing failed
     */
    @Override
    public Path call() {
        SNTUtils.log("AStarRefiner: Re-tracing '" + path.getName() + "' (" + path.size() + " waypoints)");
        try {
            final List<SNTPoint> waypoints = new ArrayList<>(path.getNodes());
            retraced = snt.autoTraceSync(waypoints, null, settings);
            if (retraced == null || retraced.size() < 2) {
                failureReason = "Search produced no result (out of bounds, or no path found through the data)";
                succeeded = false;
                return null;
            }
            if (snt.getChannel() != expectedChannel || snt.getFrame() != expectedFrame) {
                failureReason = "Image source (channel/frame) changed while this path was being re-traced";
                succeeded = false;
                retraced = null;
                return null;
            }
            succeeded = true;
            return retraced;
        } catch (final Exception e) {
            failureReason = e.getMessage();
            SNTUtils.log("AStarRefiner: FAILED '" + path.getName() + "': " + e.getMessage());
            succeeded = false;
            return null;
        }
    }

    /**
     * Applies the re-traced geometry to the original path via {@link Path#replaceNodes(Path)}.
     * Must be called sequentially (not thread-safe), typically on the EDT after all parallel
     * {@link #call()} invocations have completed.
     *
     * @throws IllegalStateException if {@link #call()} has not been run or did not succeed
     */
    public void apply() {
        if (retraced == null || !succeeded)
            throw new IllegalStateException("No re-trace result to apply");
        synchronized (path) {
            path.replaceNodes(retraced);
            if (!path.getName().contains(RETRACED_SUFFIX)) {
                path.setName(path.getName() + RETRACED_SUFFIX);
            }
        }
    }
}
