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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

/**
 * A sparse matrix implementation backed by a map
 *
 * @author Cameron Arshadi
 */
public class MapSearchImage<V> implements SearchImage<V> {

    private final Long2ObjectOpenHashMap<V> map;

    public MapSearchImage() {
        this.map = new Long2ObjectOpenHashMap<>(); // TODO: worry about initial capacity / load factor?
    }

    @Override
    public V getValue(final int x, final int y) {
        return map.get(pairingFunction(x, y));
    }

    @Override
    public void setValue(final int x, final int y, final V value) {
        map.put(pairingFunction(x, y), value);
    }

    @NotNull
    @Override
    public Iterator<V> iterator() {
        return map.values().iterator();
    }

    // http://szudzik.com/ElegantPairing.pdf
    private long pairingFunction(long a, long b) {
        return a >= b ? a * a + a + b : a + b * b;
    }

}
