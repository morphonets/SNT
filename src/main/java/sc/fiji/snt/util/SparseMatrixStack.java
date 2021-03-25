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
 * A container for storing and indexing a collection of {@link SparseMatrix}, backed by an
 * int to Object open addressed hash map.
 *
 * @author Cameron Arshadi
 */
public class SparseMatrixStack<V> implements Iterable<SparseMatrix<V>> {

    final public Int2ObjectOpenHashMap<SparseMatrix<V>> stack;

    public SparseMatrixStack(final int nSlices) {
        this.stack = new Int2ObjectOpenHashMap<>(nSlices * 2, 0.55f);
    }

    public SparseMatrix<V> getSlice(final int z) {
        return stack.get(z);
    }

    public SparseMatrix<V> newSlice(final int z) {
        SparseMatrix<V> slice = new SparseMatrix<>();
        stack.put(z, slice);
        return slice;
    }

    @NotNull
    @Override
    public Iterator<SparseMatrix<V>> iterator() {
        return stack.values().iterator();
    }

}
