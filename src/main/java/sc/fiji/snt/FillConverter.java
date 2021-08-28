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

package sc.fiji.snt;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.operators.SetOne;
import sc.fiji.snt.tracing.DefaultSearchNode;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;
import sc.fiji.snt.tracing.image.SupplierUtil;

import java.util.Collection;

/**
 * Map filled nodes from a {@link Collection} of {@link FillerThread}s to a {@link RandomAccessible}.
 *
 * @author Cameron Arshadi
 */
public class FillConverter {

    public enum ResultType {SAME, BINARY_MASK, DISTANCE}

    private final Collection<FillerThread> fillers;
    private SearchImageStack<DefaultSearchNode> fillerStack;

    public FillConverter(final Collection<FillerThread> fillers) {
        this.fillers = fillers;
    }

    public <T extends Type<T>> void convert(final RandomAccessible<T> in, 
                                            final RandomAccessible<T> out)
    {
        final RandomAccess<T> inAccess = in.randomAccess();
        final RandomAccess<T> outAccess = out.randomAccess();
        final SearchImageStack<DefaultSearchNode> newStack = getFillerStack();
        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                if (node == null) continue;
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                outAccess.setPositionAndGet(pos).set(inAccess.setPositionAndGet(pos));
            }
        }
    }

    public <T extends SetOne> void convertBinary(final RandomAccessible<T> out)
    {
        final RandomAccess<T> outAccess = out.randomAccess();
        final SearchImageStack<DefaultSearchNode> newStack = getFillerStack();
        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                if (node == null) continue;
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                outAccess.setPositionAndGet(pos).setOne();
            }
        }
    }

    public void convertDistance(final RandomAccessible<FloatType> out)
    {
        final RandomAccess<FloatType> outAccess = out.randomAccess();
        final SearchImageStack<DefaultSearchNode> newStack = getFillerStack();
        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                if (node == null) continue;
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                outAccess.setPositionAndGet(pos).set((float)node.g);
            }
        }
    }

    public SearchImageStack<DefaultSearchNode> getFillerStack() {
        if (fillerStack == null) {
            fillerStack = new SearchImageStack<>(new SupplierUtil.MapSearchImageSupplier<>());
            mergeFills(fillers, fillerStack);
        }
        return fillerStack;
    }

    private static void mergeFills(final Collection<FillerThread> fillers,
                                   final SearchImageStack<DefaultSearchNode> newStack)
    {
        // Merge the individuals fills into a single stack
        for (final FillerThread filler : fillers) {
            for (Integer sliceIdx : filler.getNodesAsImage().keySet()) {
                SearchImage<DefaultSearchNode> slice = filler.getNodesAsImage().getSlice(sliceIdx);
                if (slice == null) continue;
                SearchImage<DefaultSearchNode> newSlice = newStack.getSlice(sliceIdx);
                if (newSlice == null) {
                    newSlice = newStack.newSlice(sliceIdx);
                }
                for (final DefaultSearchNode node : slice) {
                    if (node == null || node.g > filler.getThreshold()) continue;
                    final DefaultSearchNode existingNode = newSlice.getValue(node.x, node.y);
                    if (existingNode == null) {
                        newSlice.setValue(node.x, node.y, node);
                    } else if (node.g < existingNode.g) {
                        // If there are two nodes with the same index, choose the one with lower g-score
                        newSlice.setValue(node.x, node.y, node);
                    }
                }
            }
        }
    }

}
