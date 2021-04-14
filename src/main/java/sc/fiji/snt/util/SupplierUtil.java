package sc.fiji.snt.util;

import sc.fiji.snt.SearchNode;

import java.util.function.Supplier;

public class SupplierUtil {

    public interface SearchImageSupplier<V extends SearchNode> extends Supplier<SearchImage<V>> {
        SearchImage<V> get();
    }

    public static class SparseMatrixSupplier<V extends SearchNode> implements SearchImageSupplier<V> {

        @Override
        public HashTableSearchImage<V> get() {
            return new HashTableSearchImage<>();
        }

    }

    public static class SearchNodeArraySupplier<V extends SearchNode> implements SearchImageSupplier<V> {

        private final int width;
        private final int height;
        private final Class<V> c;

        public SearchNodeArraySupplier(Class<V> c, final int width, final int height) {
            this.c = c;
            this.width = width;
            this.height = height;
        }

        @Override
        public ArraySearchImage<V> get() {
            return new ArraySearchImage<>(c, width, height);
        }

    }


    public static <V extends SearchNode> SearchImageSupplier<V> createSupplier(
            Class<? extends SearchImage> clazz,
            Class<V> searchNodeClass,
            int width, int height)
    {
        if (clazz == HashTableSearchImage.class) {
            return new SparseMatrixSupplier<>();
        } else if (clazz == ArraySearchImage.class) {
            return new SearchNodeArraySupplier<>(searchNodeClass, width, height);
        } else {
            throw new IllegalArgumentException("Unrecognized SearchImageSupplier Class");
        }

    }

}
