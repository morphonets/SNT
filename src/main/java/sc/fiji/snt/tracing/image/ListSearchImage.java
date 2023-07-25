/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;

/**
 * @author Cameron Arshadi
 */
public class ListSearchImage<V> implements SearchImage<V> {

    private final ListImg<V> img;
    private final ListRandomAccess<V> access;

    public ListSearchImage(final Class<V> clazz, final int width, final int height)
            throws IllegalAccessException, InstantiationException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {

        this.img = new ListImg<>(new long[]{width, height}, clazz.getDeclaredConstructor().newInstance()); // FIXME: Is this correct??
        this.access = img.randomAccess();
    }

    @Override
    public V getValue(final int x, final int y) {
        return access.setPositionAndGet(x, y);
    }

    @Override
    public void setValue(final int x, final int y, final V value) {
        access.setPosition(new int[]{x, y});
        access.set(value);
    }

    @NotNull
    @Override
    public Iterator<V> iterator() {
        return img.iterator();
    }
}
