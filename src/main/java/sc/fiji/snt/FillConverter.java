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
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.operators.SetOne;
import sc.fiji.snt.tracing.DefaultSearchNode;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;
import sc.fiji.snt.tracing.image.SupplierUtil;

import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Map filled nodes from a {@link Collection} of {@link FillerThread}s to and between {@link RandomAccessible}s.
 *
 * @author Cameron Arshadi
 */
public class FillConverter {

    private final Collection<FillerThread> fillers;
    private SearchImageStack<DefaultSearchNode> fillerStack;

	public enum ResultType {
		SAME, BINARY_MASK, DISTANCE, LABEL;

		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

    public FillConverter(final Collection<FillerThread> fillers) {
        this.fillers = fillers;
    }

    /**
     * Map values between the input and output at fill voxel positions.
     *
     * @param in  the input rai
     * @param out the output rai
     * @param <T>
     */
    public <T extends Type<T>> void convert(final RandomAccessible<T> in, final RandomAccessible<T> out) {
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

    /**
     * Set 1 at fill voxel positions.
     *
     * @param out the output rai
     * @param <T>
     */
    public <T extends SetOne> void convertBinary(final RandomAccessible<T> out) {
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

    /**
     * Map the node distance measure to fill voxel positions. This corresponds to the
     * g-score of a node assigned during the Dijkstra search. This value is stored as {@link Double}.
     *
     * @param out the output rai
     * @param <T>
     */
    public <T extends RealType<T>> void convertDistance(final RandomAccessible<T> out) {
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
                outAccess.setPositionAndGet(pos).setReal(node.g);
            }
        }
    }

    /**
     * Map the fill component label to fill voxel positions.
     * The concrete {@link IntegerType} should be chosen based on the cardinality of
     * the given {@link java.util.Collection} of {@link FillerThread}s.
     * For example, if there are less than 256 {@link FillerThread}s,
     * choose {@link net.imglib2.type.numeric.integer.UnsignedByteType}. If there are more than 255 but less than
     * 65536, choose {@link net.imglib2.type.numeric.integer.UnsignedShortType}, etc. Fill components are assigned
     * labels based on their order in the collection. If you want to ensure labels are assigned based on insertion
     * order, make sure to use an ordered collection such as {@link List} or {@link java.util.LinkedHashSet}.
     * The first component will have label == 1, the second label == 2, and so on.
     * The label 0 is not assigned to any voxel positions. 0-valued voxels may already exist in the output image.
     *
     * @param out the output rai
     * @param <T>
     */
    public <T extends IntegerType<T>> void convertLabels(final RandomAccessible<T> out) {
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
                outAccess.setPositionAndGet(pos).setInteger((long) node.f);
            }
        }
    }

    /**
     * Merges the input {@link FillerThread}s into a single {@link SearchImageStack}. When a filled voxel position
     * is present in multiple filler instances, the node with the lowest g-score is chosen for inclusion in the merged
     * stack.
     *
     * @return the merged filler stack
     */
    public SearchImageStack<DefaultSearchNode> getFillerStack() {
        if (fillerStack == null) {
            fillerStack = new SearchImageStack<>(new SupplierUtil.MapSearchImageSupplier<>());
            mergeFills(fillers, fillerStack);
        }
        return fillerStack;
    }

    private static void mergeFills(final Collection<FillerThread> fillers,
                                   final SearchImageStack<DefaultSearchNode> newStack) {
        // Merge the individuals fills into a single stack
        // labels start at 1, 0 is reserved for background
        long label = 1;
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
                    // HACK, use f-score field for label. This field is ignored for Dijkstra searches
                    node.f = label;
                    final DefaultSearchNode existingNode = newSlice.getValue(node.x, node.y);
                    if (existingNode == null) {
                        newSlice.setValue(node.x, node.y, node);
                    } else if (node.g < existingNode.g) {
                        // If there are two nodes with the same index, choose the one with lower g-score
                        newSlice.setValue(node.x, node.y, node);
                    }
                }
            }
            label++;
        }
    }

}
