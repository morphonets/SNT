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

package sc.fiji.snt.tracing.image;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import sc.fiji.snt.tracing.SearchNode;

import java.util.*;
import java.util.function.Supplier;

/**
 * A stack of {@link SearchImage}s, backed by a {@link Int2ObjectOpenHashMap}. Slices may be inserted and accessed
 * at arbitrary Integer values.
 *
 * @author Cameron Arshadi
 */
public class SearchImageStack<V extends SearchNode> implements Iterable<SearchImage<V>> {

    private final Int2ObjectOpenHashMap<SearchImage<V>> stack;
    private final Supplier<SearchImage<V>> searchImageSupplier;

    public SearchImageStack() {
        this.searchImageSupplier = new SupplierUtil.MapSearchImageSupplier<>();
        this.stack = new Int2ObjectOpenHashMap<>();
    }

    public SearchImageStack(final Supplier<SearchImage<V>> supplier) {
        this.searchImageSupplier = supplier;
        this.stack = new Int2ObjectOpenHashMap<>();
    }

    public SearchImage<V> getSlice(final int z) {
        return stack.get(z);
    }

    public SearchImage<V> newSlice(final int z) {
        SearchImage<V> slice = searchImageSupplier.get();
        stack.put(z, slice);
        return slice;
    }

    public int size() {
        return stack.size();
    }

    public Set<Integer> keySet() {
        return stack.keySet();
    }

    @NotNull
    @Override
    public Iterator<SearchImage<V>> iterator() {
        return stack.values().iterator();
    }

}
