/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

/**
 * A sparse matrix implementation backed by a nested int to Object open addressed hash map
 *
 * @author Cameron Arshadi
 */
public class SparseMatrix<V> implements Iterable<Int2ObjectOpenHashMap<V>> {

    public final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<V>> doubleMap;

    public SparseMatrix() {
        this.doubleMap = new Int2ObjectOpenHashMap<>();
    }

    public Int2ObjectOpenHashMap<V> getRow(final int row) {
        return doubleMap.get(row);
    }

    public Int2ObjectOpenHashMap<V> newRow(final int row) {
        Int2ObjectOpenHashMap<V> newRow = new Int2ObjectOpenHashMap<>();
        doubleMap.put(row, newRow);
        return newRow;
    }

    public V getValue(final int x, final int y) {
        Int2ObjectOpenHashMap<V> row = getRow(y);
        if (row == null) {
            return null;
        }
        return row.get(x);
    }

    public void setValue(final int x, final int y, final V value) {
        Int2ObjectOpenHashMap<V> row = getRow(y);
        if (row == null) {
            row = new Int2ObjectOpenHashMap<>();
            doubleMap.put(y, row);
        }
        row.put(x, value);
    }

    public void setValueWithoutChecks(final int x, final int y, final V value) {
        doubleMap.get(y).put(x, value);
    }

    @NotNull
    @Override
    public Iterator<Int2ObjectOpenHashMap<V>> iterator() {
        return doubleMap.values().iterator();
    }
}
