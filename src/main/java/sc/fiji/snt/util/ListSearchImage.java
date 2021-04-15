package sc.fiji.snt.util;

import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

public class ListSearchImage<V> implements SearchImage<V> {

    private final ListImg<V> img;
    private final ListRandomAccess<V> access;

    public ListSearchImage(final Class<V> clazz, final int width, final int height)
            throws IllegalAccessException, InstantiationException {

        this.img = new ListImg<>(new long[]{width, height}, clazz.newInstance()); // FIXME: Is this correct??
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
