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

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchNode;

import java.util.function.Supplier;


/**
 * Static utilities for creation of {@link Supplier}s of {@link SearchImage}s
 *
 * @author Cameron Arshadi
 */
public class SupplierUtil {

    private SupplierUtil() {}

    public static class MapSearchImageSupplier<V extends SearchNode> implements Supplier<SearchImage<V>> {

        private final int width;
        private final int height;

        public MapSearchImageSupplier(final int width, final int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public MapSearchImage<V> get() {
            return new MapSearchImage<>(width, height);
        }

    }

    public static class ArraySearchImageSupplier<V extends SearchNode> implements Supplier<SearchImage<V>> {

        private final int width;
        private final int height;
        private final Class<V> c;

        public ArraySearchImageSupplier(Class<V> c, final int width, final int height) {
            this.c = c;
            this.width = width;
            this.height = height;
        }

        @Override
        public ArraySearchImage<V> get() {
            return new ArraySearchImage<>(c, width, height);
        }

    }

    public static class ListSearchImageSupplier<V extends SearchNode> implements Supplier<SearchImage<V>> {

        private final int width;
        private final int height;
        private final Class<V> c;

        public ListSearchImageSupplier(Class<V> c, final int width, final int height) {
            this.c = c;
            this.width = width;
            this.height = height;
        }

        @Override
        public SearchImage<V> get() {
            try {
                return new ListSearchImage<>(c, width, height);
            } catch (IllegalAccessException | InstantiationException e) {
                SNTUtils.error("Failed to create ListSearchImage with node Class " + c.getName(), e);
                return null;
            }
        }
    }

    public static <V extends SearchNode> Supplier<SearchImage<V>> createSupplier(
            Class<? extends SearchImage> clazz,
            Class<V> searchNodeClass,
            int width, int height)
    {
        if (clazz == ArraySearchImage.class) {
            return new ArraySearchImageSupplier<>(searchNodeClass, width, height);
        } else if (clazz == ListSearchImage.class) {
            return new ListSearchImageSupplier<>(searchNodeClass, width, height);
        } else if (clazz == MapSearchImage.class) {
            return new MapSearchImageSupplier<>(width, height);
        }
        else {
            throw new IllegalArgumentException("Unrecognized SearchImage Class");
        }

    }

}
