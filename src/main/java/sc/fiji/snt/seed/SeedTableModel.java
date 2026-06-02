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

import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link javax.swing.table.TableModel} backed by a {@link SeedOverlay}. Seven
 * columns ({@code X, Y, Z, Conf, Radius, Type, Source}, plus an optional
 * {@code Status} column when an {@link SNT} instance is supplied so coverage
 * can be derived live from the active tracing).
 * <p>
 * All cells are read-only: to relocate a seed, change confidence/radius/type,
 * or relabel its provenance, open the {@link SeedPointEditDialog} (single mode
 * via double-click on a row or Alt+Click on the canvas; bulk mode via the
 * panel's Edit toolbar button).
 * <p>
 * The model remains a 1:1 view of the overlay (one row per seed). Confidence-
 * range filtering is exposed as a {@link RowFilter} via
 * {@link #confidenceRangeFilter()}, which the panel installs on the table's
 * {@link javax.swing.table.TableRowSorter}; out-of-range rows are hidden by
 * the sorter (not removed from the model), so view↔model row conversions
 * continue to work transparently for selection sync.
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 */
public class SeedTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    public static final int COL_X = 0;
    public static final int COL_Y = 1;
    public static final int COL_Z = 2;
    public static final int COL_CONFIDENCE = 3;
    public static final int COL_RADIUS = 4;
    public static final int COL_TYPE = 5;
    public static final int COL_SOURCE = 6;
    /**
     * Coverage status (derived). Only present when the model was built with
     * an {@link SNT} instance; otherwise {@code getColumnCount()} returns 7.
     */
    public static final int COL_STATUS = 7;

    private static final String[] COLUMN_NAMES_NO_STATUS = {
            "X", "Y", "Z", "Conf", "Radius", "Type", "Source"};
    private static final String[] COLUMN_NAMES_WITH_STATUS = {
            "X", "Y", "Z", "Conf", "Radius", "Type", "Source", "Status"};

    /**
     * Cell value when a seed has a path node within tolerance.
     */
    public static final String STATUS_COVERED = "covered";
    /**
     * Cell value when no path node lies within tolerance.
     */
    public static final String STATUS_UNCOVERED = "uncovered";
    /**
     * Cell value when there's no tracing context to compute against.
     */
    public static final String STATUS_UNKNOWN = "-";

    private final SeedOverlay overlay;
    /**
     * Optional, for Status compute. {@code null} → no Status column.
     */
    private final SNT snt;
    private final SeedOverlay.SeedOverlayListener listener;
    private final boolean includeStatus;

    /**
     * Cached coverage state, parallel to {@link SeedOverlay#list()}. Built
     * lazily; invalidated whenever the overlay fires (any add/remove/clear/
     * threshold change). Path-only changes don't trigger invalidation: user
     * actions on the seed table will see a slightly stale Status until the
     * overlay listener fires again.
     */
    private String[] cachedStatuses;

    /**
     * Row count observed at the previous listener fire. Used to decide whether
     * to emit a {@link #fireTableDataChanged()} or a {@link #fireTableRowsUpdated(int, int)}
     * (values may have changed but row identities are stable).
     */
    private int lastKnownRowCount = -1;

    /**
     * Builds a model without the Status column.
     */
    public SeedTableModel(final SeedOverlay overlay) {
        this(overlay, null);
    }

    /**
     * Builds a model with an optional Status column. {@code snt == null}
     * suppresses the column (matches the simpler constructor's behavior).
     */
    public SeedTableModel(final SeedOverlay overlay, final SNT snt) {
        this.overlay = overlay;
        this.snt = snt;
        this.includeStatus = (snt != null);
        this.listener = source -> SwingUtilities.invokeLater(this::onOverlayChanged);
        overlay.addListener(listener);
    }

    /**
     * Dispatches overlay-change notifications without wiping the JTable's
     * selection on every fire. Only emits a {@link #fireTableDataChanged()}
     * when the row count actually changes.
     */
    private void onOverlayChanged() {
        cachedStatuses = null;
        final int newCount = getRowCount();
        if (newCount != lastKnownRowCount) {
            lastKnownRowCount = newCount;
            fireTableDataChanged();
        } else if (newCount > 0) {
            fireTableRowsUpdated(0, newCount - 1);
        }
    }

    /**
     * Recomputes the coverage cache and fires a status-column update. Callers
     * (e.g. the panel) can invoke this after tracer runs that change paths
     * without going through the SeedOverlay listener.
     */
    public void recomputeStatuses() {
        SwingUtilities.invokeLater(() -> {
            cachedStatuses = null;
            if (includeStatus && getRowCount() > 0) {
                fireTableRowsUpdated(0, getRowCount() - 1);
            }
        });
    }

    /**
     * Unregisters the overlay listener and clears the status-cache reference.
     * Must be called when this model is no longer used: the listener holds
     * a strong reference back to this model, so failing to call {@code dispose()}
     * pins the model (and its enclosing UI) for as long as the overlay lives.
     */
    public void dispose() {
        overlay.removeListener(listener);
        cachedStatuses = null;
    }

    @Override
    public int getRowCount() {
        return overlay.size();
    }

    @Override
    public int getColumnCount() {
        return includeStatus ? COLUMN_NAMES_WITH_STATUS.length : COLUMN_NAMES_NO_STATUS.length;
    }

    @Override
    public String getColumnName(final int col) {
        final String[] names = includeStatus ? COLUMN_NAMES_WITH_STATUS : COLUMN_NAMES_NO_STATUS;
        return (col >= 0 && col < names.length) ? names[col] : "";
    }

    @Override
    public Class<?> getColumnClass(final int col) {
        return switch (col) {
            case COL_X, COL_Y, COL_Z, COL_CONFIDENCE, COL_RADIUS -> Double.class;
            case COL_TYPE, COL_SOURCE, COL_STATUS -> String.class;
            default -> Object.class;
        };
    }

    @Override
    public Object getValueAt(final int row, final int col) {
        if (row < 0 || row >= overlay.size()) return null;
        final SeedPoint s = overlay.get(row);
        return switch (col) {
            case COL_X -> s.x;
            case COL_Y -> s.y;
            case COL_Z -> s.z;
            case COL_CONFIDENCE -> s.confidence;
            case COL_RADIUS -> s.radius;
            case COL_TYPE -> s.type;
            case COL_SOURCE -> s.source;
            case COL_STATUS -> includeStatus ? statusForRow(row) : null;
            default -> null;
        };
    }

    /**
     * Coverage status of the seed at row {@code row}: {@code covered} if any
     * node of any path in the active {@link PathAndFillManager} lies within a
     * physical-distance tolerance derived from the seed's radius and the
     * image spacing. Otherwise {@code uncovered}. Returns {@code "-"} when no
     * SNT context is available.
     * <p>
     * Indexed by row rather than by {@code overlay.indexOf(seed)} because the
     * cache is parallel to {@link SeedOverlay#list()} and JTable repaints can
     * call {@link #getValueAt} thousands of times.
     */
    private String statusForRow(final int row) {
        if (snt == null) return STATUS_UNKNOWN;
        ensureStatusCache();
        if (cachedStatuses == null || row < 0 || row >= cachedStatuses.length) return STATUS_UNKNOWN;
        return cachedStatuses[row];
    }

    /**
     * Builds {@link #cachedStatuses} from scratch. O(N · log M) where N is the
     * number of seeds and M is the total number of path nodes (KD-tree query
     * per seed). Cheap enough for the smooth-handle target (≤50k seeds, ≤10k
     * nodes).
     */
    private void ensureStatusCache() {
        if (cachedStatuses != null) return;
        final int n = overlay.size();
        cachedStatuses = new String[n];
        if (n == 0) return;
        final KDTree<Object> pathTree = buildPathTree();
        if (pathTree == null) {
            // No paths yet → everything is uncovered.
            for (int i = 0; i < n; i++) cachedStatuses[i] = STATUS_UNCOVERED;
            return;
        }
        for (int i = 0; i < n; i++) {
            final SeedPoint seed = overlay.get(i);
            final double tolerance = coverageToleranceFor(seed);
            final Neighbor<double[], Object> hit = pathTree.nearest(
                    new double[]{seed.x, seed.y, seed.z});
            cachedStatuses[i] = (hit != null && hit.distance() <= tolerance)
                    ? STATUS_COVERED : STATUS_UNCOVERED;
        }
    }

    /**
     * Builds a Smile {@link KDTree} indexing every node of every path in
     * {@code snt}'s {@link PathAndFillManager}. Returns {@code null} if there
     * are no paths or no nodes. The value type is {@code Object} (we only
     * need the spatial query; we don't read the value back).
     */
    private KDTree<Object> buildPathTree() {
        final PathAndFillManager pafm = snt.getPathAndFillManager();
        if (pafm == null || pafm.size() == 0) return null;
        final List<double[]> coords = new ArrayList<>(1024);
        final List<Object> values = new ArrayList<>(1024);
        for (int i = 0; i < pafm.size(); i++) {
            final Path p = pafm.getPath(i);
            if (p == null) continue;
            final int sz = p.size();
            for (int j = 0; j < sz; j++) {
                coords.add(new double[]{p.getNode(j).x, p.getNode(j).y, p.getNode(j).z});
                values.add(Boolean.TRUE); // placeholder
            }
        }
        if (coords.isEmpty()) return null;
        final double[][] coordsArr = coords.toArray(new double[0][]);
        final Object[] valuesArr = values.toArray();
        return new KDTree<>(coordsArr, valuesArr);
    }

    /**
     * Physical-distance tolerance for declaring a seed "covered". Generous by
     * design: {@code max(seed.radius × 1.5, 3 × in-plane voxel size)}.
     */
    private double coverageToleranceFor(final SeedPoint seed) {
        final double seedScale = seed.radius * 1.5;
        final double voxelScale = 3.0 * Math.max(snt.getPixelWidth(), snt.getPixelHeight());
        return Math.max(seedScale, voxelScale);
    }

    /**
     * All cells are read-only. Edits go through {@link SeedPointEditDialog}
     * (opened via row double-click, canvas Alt+Click, or the panel's Edit
     * toolbar button), which rebuilds the immutable {@link SeedPoint} and
     * applies it via {@link SeedOverlay#replaceAt(int, SeedPoint)}.
     */
    @Override
    public boolean isCellEditable(final int row, final int col) {
        return false;
    }

    /**
     * @return a {@link RowFilter} that includes a row iff its seed's
     * confidence falls within the overlay's current
     * {@code [lowConfidence, highConfidence]} window. The filter
     * reads the bounds live, so re-evaluating it after a range
     * change (e.g., via the model's
     * {@code fireTableDataChanged()} firing on overlay changes) is
     * sufficient: the panel does not need to swap the filter.
     */
    public RowFilter<SeedTableModel, Integer> confidenceRangeFilter() {
        return new RowFilter<>() {
            @Override
            public boolean include(final Entry<? extends SeedTableModel, ? extends Integer> entry) {
                final int modelRow = entry.getIdentifier();
                if (modelRow < 0 || modelRow >= overlay.size()) return false;
                final double c = overlay.get(modelRow).confidence;
                return c >= overlay.getLowConfidence() && c <= overlay.getHighConfidence();
            }
        };
    }
}
