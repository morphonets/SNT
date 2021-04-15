package sc.fiji.snt.util;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchNode;

import java.util.function.Supplier;

public class SupplierUtil {

    public static class TableSearchImageSupplier<V extends SearchNode> implements Supplier<SearchImage<V>> {

        @Override
        public TableSearchImage<V> get() {
            return new TableSearchImage<>();
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
                SNTUtils.error("Failed to create ListImgSearchImage with Class " + c.getName(), e);
                return null;
            }
        }
    }

    public static <V extends SearchNode> Supplier<SearchImage<V>> createSupplier(
            Class<? extends SearchImage> clazz,
            Class<V> searchNodeClass,
            int width, int height)
    {
        if (clazz == TableSearchImage.class) {
            return new TableSearchImageSupplier<>();
        } else if (clazz == ArraySearchImage.class) {
            return new ArraySearchImageSupplier<>(searchNodeClass, width, height);
        } else if (clazz == ListSearchImage.class) {
            return new ListSearchImageSupplier<>(searchNodeClass, width, height);
        }
        else {
            throw new IllegalArgumentException("Unrecognized SearchImageSupplier Class");
        }

    }

}
