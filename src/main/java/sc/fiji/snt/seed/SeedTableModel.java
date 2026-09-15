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

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/**
 * {@link javax.swing.table.TableModel} backed by a {@link SeedOverlay}. Seven
 * columns: {@code X, Y, Z, Conf, Radius, Type, Source}.
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
 * <p>
 * Coverage against the active tracing (whether a seed already has a nearby
 * traced path node) is not tracked here: it is a point-in-time snapshot, not
 * a live table column, and is computed on demand by the panel's "Select by
 * Coverage" action, which selects the matching seeds directly on the overlay.
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

    private static final String[] COLUMN_NAMES = {
            "X", "Y", "Z", "Conf", "Radius", "Type", "Source"};

    private final SeedOverlay overlay;
    private final SeedOverlay.SeedOverlayListener listener;

    /**
     * Row count observed at the previous listener fire. Used to decide whether
     * to emit a {@link #fireTableDataChanged()} or a {@link #fireTableRowsUpdated(int, int)}
     * (values may have changed but row identities are stable).
     */
    private int lastKnownRowCount = -1;

    public SeedTableModel(final SeedOverlay overlay) {
        this.overlay = overlay;
        this.listener = source -> SwingUtilities.invokeLater(this::onOverlayChanged);
        overlay.addListener(listener);
    }

    /**
     * Dispatches overlay-change notifications without wiping the JTable's
     * selection on every fire. Only emits a {@link #fireTableDataChanged()}
     * when the row count actually changes.
     */
    private void onOverlayChanged() {
        final int newCount = getRowCount();
        if (newCount != lastKnownRowCount) {
            lastKnownRowCount = newCount;
            fireTableDataChanged();
        } else if (newCount > 0) {
            fireTableRowsUpdated(0, newCount - 1);
        }
    }

    /**
     * Unregisters the overlay listener. Must be called when this model is no
     * longer used: the listener holds a strong reference back to this model,
     * so failing to call {@code dispose()} pins the model (and its enclosing
     * UI) for as long as the overlay lives.
     */
    public void dispose() {
        overlay.removeListener(listener);
    }

    @Override
    public int getRowCount() {
        return overlay.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(final int col) {
        return (col >= 0 && col < COLUMN_NAMES.length) ? COLUMN_NAMES[col] : "";
    }

    @Override
    public Class<?> getColumnClass(final int col) {
        return switch (col) {
            case COL_X, COL_Y, COL_Z, COL_CONFIDENCE, COL_RADIUS -> Double.class;
            case COL_TYPE, COL_SOURCE -> String.class;
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
            default -> null;
        };
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
