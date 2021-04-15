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

import org.jetbrains.annotations.NotNull;
import sc.fiji.snt.SearchNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * A stack of {@link SearchImage}s, backed by a List.
 *
 * @author Cameron Arshadi
 */
public class SearchImageStack<V extends SearchNode> implements Iterable<SearchImage<V>> {

    List<SearchImage<V>> stack;
    Supplier<SearchImage<V>> searchImageSupplier;

    public SearchImageStack(final int nSlices) {
        this.searchImageSupplier = new SupplierUtil.TableSearchImageSupplier<>();
        initNullStack(nSlices);
    }

    public SearchImageStack(final int nSlices, final Supplier<SearchImage<V>> supplier) {
        this.searchImageSupplier = supplier;
        initNullStack(nSlices);
    }

    public SearchImage<V> getSlice(final int z) {
        return stack.get(z);
    }

    public SearchImage<V> newSlice(final int z) {
        SearchImage<V> slice = searchImageSupplier.get();
        stack.set(z, slice);
        return slice;
    }

    private void initNullStack(final int nSlices) {
        stack = new ArrayList<>(nSlices);
        for (int i=0; i < nSlices; i++) {
            stack.add(null);
        }
    }

    public int size() {
        return stack.size();
    }

    @NotNull
    @Override
    public Iterator<SearchImage<V>> iterator() {
        return stack.iterator();
    }

}
