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

package sc.fiji.snt.analysis.curation;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitors tracing and editing operations for morphological plausibility.
 * <p>
 * The monitor maintains two tiers of checks:
 * <ul>
 *   <li>{@link PlausibilityCheck.LiveCheck}: lightweight checks run inline
 *       during interactive tracing at four hook points (fork initiation,
 *       segment completion, path finalization, node editing).</li>
 *   <li>{@link PlausibilityCheck.DeepCheck}: heavier checks that scan the
 *       full reconstruction on demand via {@link #runDeepScan(Collection)}.</li>
 * </ul>
 * <p>
 * Listeners are notified whenever the warning list changes so that UI
 * components (status bar, canvas overlays, assistant panel) can react.
 * </p>
 *
 * @author Tiago Ferreira
 * @see PlausibilityCheck
 */
public class PlausibilityMonitor {

    /** Listener interface for warning list changes. */
    public interface WarningListener {
        /**
         * Called whenever the monitor's warning list is updated.
         * @param warnings the current (immutable) list of warnings
         */
        void warningsUpdated(List<PlausibilityCheck.Warning> warnings);
    }

    private final List<PlausibilityCheck.LiveCheck> liveChecks;
    private final List<PlausibilityCheck.DeepCheck> deepChecks;
    private final List<PlausibilityCheck.Warning> currentWarnings;
    private final List<WarningListener> listeners;

    private boolean enabled;

    // Cached parent context from fork initiation (Hook 1)
    private Path cachedParent;
    private int cachedBranchIndex = -1;

    public PlausibilityMonitor() {
        liveChecks = new ArrayList<>();
        deepChecks = new ArrayList<>();
        currentWarnings = new ArrayList<>();
        listeners = new CopyOnWriteArrayList<>();
        enabled = false;
        initDefaultChecks();
    }

    private void initDefaultChecks() {
        // Live checks: ordered: geometry, radius, structural, meta
        liveChecks.add(new PlausibilityCheck.BranchAngle());
        liveChecks.add(new PlausibilityCheck.DirectionContinuity());
        liveChecks.add(new PlausibilityCheck.RadiusContinuity());
        liveChecks.add(new PlausibilityCheck.ConstantRadii());
        liveChecks.add(new PlausibilityCheck.TerminalBranchLength());
        liveChecks.add(new PlausibilityCheck.SomaDistance());
        liveChecks.add(new PlausibilityCheck.TortuosityConsistency());

        // Deep checks: ordered: most impactful first
        deepChecks.add(new PlausibilityCheck.PathOverlap());
        deepChecks.add(new PlausibilityCheck.RadiusJumps());
        deepChecks.add(new PlausibilityCheck.RadiusMonotonicity());
    }

    /**
     * <b>Hook 1: Fork initiation.</b> Called from {@code SNT.startPath()} when
     * the user Alt-clicks to start a branch. Caches the parent path and branch
     * point index for later comparison.
     *
     * @param parent      the parent path being forked from
     * @param branchIndex the node index in the parent at the fork point
     */
    public void onForkInitiated(final Path parent, final int branchIndex) {
        cachedParent = parent;
        cachedBranchIndex = branchIndex;
        clearWarnings();
        SNTUtils.log("PlausibilityMonitor: cached fork context at parent node " + branchIndex);
    }

    /**
     * <b>Hook 2: Segment completion.</b> Called from {@code SNT.searchFinished()}
     * when A* returns a candidate segment, before the user sees the keep/junk
     * dialog.
     *
     * @param candidateChild the candidate child path segment
     * @return the list of warnings (empty if plausible)
     */
    public List<PlausibilityCheck.Warning> onSegmentCompleted(final Path candidateChild) {
        if (!enabled || cachedParent == null || candidateChild == null) {
            return Collections.emptyList();
        }
        return runLiveChecks(cachedParent, candidateChild, cachedBranchIndex);
    }

    /**
     * <b>Hook 3: Path finalization.</b> Called when the user completes an entire
     * path ({@code SNT.finishPath()}). Runs a holistic check on the full child
     * path against its parent.
     *
     * @param completedChild the finished child path
     * @return the list of warnings (empty if plausible)
     */
    public List<PlausibilityCheck.Warning> onPathFinalized(final Path completedChild) {
        if (!enabled || completedChild == null) return Collections.emptyList();
        final Path parent = completedChild.getParentPath();
        if (parent == null) return Collections.emptyList();
        final int branchIdx = completedChild.getBranchPointIndex();
        return runLiveChecks(parent, completedChild, branchIdx);
    }

    /**
     * <b>Hook 4: Node editing.</b> Called after a node is moved, inserted, or
     * deleted during edit mode. Re-checks the local neighborhood.
     *
     * @param editedPath the path being edited
     * @param nodeIndex  the index of the affected node
     * @return the list of warnings (empty if plausible)
     */
    public List<PlausibilityCheck.Warning> onNodeEdited(final Path editedPath, final int nodeIndex) {
        if (!enabled || editedPath == null) return Collections.emptyList();
        final Path parent = editedPath.getParentPath();
        if (parent == null) return Collections.emptyList();
        final int branchIdx = editedPath.getBranchPointIndex();
        // Only re-check if the edit is near the fork (within ~5 nodes)
        if (nodeIndex > 5) return Collections.emptyList();
        return runLiveChecks(parent, editedPath, branchIdx);
    }

    /**
     * Clears the cached fork context. Should be called when tracing is
     * cancelled or a non-forking path is started.
     */
    public void clearForkContext() {
        cachedParent = null;
        cachedBranchIndex = -1;
        clearWarnings();
    }

    private List<PlausibilityCheck.Warning> runLiveChecks(final Path parent, final Path child,
                                                          final int branchIndex) {
        synchronized (currentWarnings) {
            currentWarnings.clear();
            for (final PlausibilityCheck.LiveCheck check : liveChecks) {
                if (check.isEnabled()) {
                    try {
                        currentWarnings.addAll(check.check(parent, child, branchIndex));
                    } catch (final Exception e) {
                        SNTUtils.log("PlausibilityMonitor: live check '" + check.getName() +
                                "' threw: " + e.getMessage());
                    }
                }
            }
            Collections.sort(currentWarnings); // highest severity first
        }
        fireWarningsUpdated();
        return getCurrentWarnings();
    }

    /**
     * Runs all enabled {@link PlausibilityCheck.DeepCheck}s against the given
     * paths.
     *
     * @param paths the paths to scan (e.g., all paths in the current Tree)
     * @return the list of warnings (empty if no issues found)
     */
    public List<PlausibilityCheck.Warning> runDeepScan(final Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) return Collections.emptyList();
        synchronized (currentWarnings) {
            currentWarnings.clear();
            for (final PlausibilityCheck.DeepCheck check : deepChecks) {
                if (check.isEnabled()) {
                    try {
                        currentWarnings.addAll(check.scan(paths));
                    } catch (final Exception e) {
                        SNTUtils.log("PlausibilityMonitor: deep check '" + check.getName() +
                                "' threw: " + e.getMessage());
                    }
                }
            }
            Collections.sort(currentWarnings);
        }
        fireWarningsUpdated();
        return getCurrentWarnings();
    }

    /**
     * Runs <b>all</b> enabled checks (both live and deep) against the given
     * paths. Live checks are applied to every parent–child fork in the
     * collection; deep checks scan the collection as a whole.
     * <p>
     * This is the method behind "Run Full Scan" in the Curation
     * Assistant panel.
     *
     * @param paths the paths to scan (e.g., all paths in the current Tree)
     * @return the combined list of warnings (empty if no issues found)
     */
    public List<PlausibilityCheck.Warning> runFullScan(final Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) return Collections.emptyList();
        synchronized (currentWarnings) {
            currentWarnings.clear();

            // Live checks: iterate every parent–child relationship
            for (final Path path : paths) {
                final Path parent = path.getParentPath();
                if (parent == null) continue;
                final int branchIdx = path.getBranchPointIndex();
                for (final PlausibilityCheck.LiveCheck check : liveChecks) {
                    if (check.isEnabled()) {
                        try {
                            currentWarnings.addAll(check.check(parent, path, branchIdx));
                        } catch (final Exception e) {
                            SNTUtils.log("PlausibilityMonitor: live check '" + check.getName() +
                                    "' threw (full scan): " + e.getMessage());
                        }
                    }
                }
            }

            // Deep checks: whole-collection scan
            for (final PlausibilityCheck.DeepCheck check : deepChecks) {
                if (check.isEnabled()) {
                    try {
                        currentWarnings.addAll(check.scan(paths));
                    } catch (final Exception e) {
                        SNTUtils.log("PlausibilityMonitor: deep check '" + check.getName() +
                                "' threw (full scan): " + e.getMessage());
                    }
                }
            }

            Collections.sort(currentWarnings);
        }
        fireWarningsUpdated();
        return getCurrentWarnings();
    }

    private void clearWarnings() {
        synchronized (currentWarnings) {
            if (currentWarnings.isEmpty()) return;
            currentWarnings.clear();
        }
        fireWarningsUpdated();
    }

    public void addWarningListener(final WarningListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeWarningListener(final WarningListener listener) {
        listeners.remove(listener);
    }

    private void fireWarningsUpdated() {
        final List<PlausibilityCheck.Warning> snapshot;
        synchronized (currentWarnings) {
            snapshot = Collections.unmodifiableList(new ArrayList<>(currentWarnings));
        }
        for (final WarningListener l : listeners) {
            try {
                l.warningsUpdated(snapshot);
            } catch (final Exception e) {
                SNTUtils.log("PlausibilityMonitor: listener threw: " + e.getMessage());
            }
        }
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        if (!enabled) clearWarnings();
    }

    /** @return the (modifiable) list of registered live checks */
    public List<PlausibilityCheck.LiveCheck> getLiveChecks() { return liveChecks; }

    /** @return the (modifiable) list of registered deep checks */
    public List<PlausibilityCheck.DeepCheck> getDeepChecks() { return deepChecks; }

    /** @return the current (immutable) list of active warnings */
    public List<PlausibilityCheck.Warning> getCurrentWarnings() {
        synchronized (currentWarnings) {
            return Collections.unmodifiableList(new ArrayList<>(currentWarnings));
        }
    }

    /**
     * Convenience method to get a specific live check by type.
     * @param type the check class
     * @return the check instance, or {@code null} if not registered
     */
    @SuppressWarnings("unchecked")
    public <T extends PlausibilityCheck.LiveCheck> T getLiveCheck(final Class<T> type) {
        for (final PlausibilityCheck.LiveCheck check : liveChecks) {
            if (type.isInstance(check)) return (T) check;
        }
        return null;
    }

    /**
     * Convenience method to get a specific deep check by type.
     * @param type the check class
     * @return the check instance, or {@code null} if not registered
     */
    @SuppressWarnings("unchecked")
    public <T extends PlausibilityCheck.DeepCheck> T getDeepCheck(final Class<T> type) {
        for (final PlausibilityCheck.DeepCheck check : deepChecks) {
            if (type.isInstance(check)) return (T) check;
        }
        return null;
    }
}
