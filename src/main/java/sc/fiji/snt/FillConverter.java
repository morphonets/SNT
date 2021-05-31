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

import ij.ImagePlus;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.util.SearchImage;
import sc.fiji.snt.util.SearchImageStack;
import sc.fiji.snt.util.SupplierUtil;

import java.util.Collection;

/**
 * Convert a collection of {@link FillerThread}s to an {@link ImagePlus}.
 *
 * @author Cameron Arshadi
 */
public class FillConverter {

    final Collection<FillerThread> fillers;
    final ImagePlus originalImp;
    SearchImageStack<DefaultSearchNode> newStack;

    public FillConverter(final Collection<FillerThread> fillers, final ImagePlus originalImp) {
        this.fillers = fillers;
        this.originalImp = originalImp;
    }

    public <T extends RealType<T> & NativeType<T>> ImagePlus getGreyImp() {
        final int width = originalImp.getWidth();
        final int height = originalImp.getHeight();
        final int depth = originalImp.getNSlices();

        Img<T> img = ImageJFunctions.wrap(originalImp);
        RandomAccess<T> imgAccess = img.randomAccess();
        Img<T> newImg = img.factory().create(width, height, depth);
        RandomAccess<T> newImgAccess = newImg.randomAccess();

        if (newStack == null) {
            newStack = new SearchImageStack<>(depth, new SupplierUtil.MapSearchImageSupplier<>(width, height));
            mergeFills(fillers, newStack);
        }

        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                newImgAccess.setPositionAndGet(pos).set(imgAccess.setPositionAndGet(pos));
            }
        }

        ImagePlus newImp = ImageJFunctions.wrap(
                Views.permute(
                        Views.addDimension(newImg, 0, 0),
                        2, 3),
                "Fill");

        final ImageStatistics stats = newImp.getStatistics(ImageStatistics.MIN_MAX);
        newImp.setDisplayRange(stats.min, stats.max);
        newImp.setCalibration(originalImp.getCalibration());

        return newImp;
    }

    public ImagePlus getBinaryImp()
    {
        final int width = originalImp.getWidth();
        final int height = originalImp.getHeight();
        final int depth = originalImp.getNSlices();

        if (newStack == null) {
            newStack = new SearchImageStack<>(depth, new SupplierUtil.MapSearchImageSupplier<>(width, height));
            mergeFills(fillers, newStack);
        }

        final Img<UnsignedByteType> binaryImg = ArrayImgs.unsignedBytes(width, height, depth);
        final RandomAccess<UnsignedByteType> binaryAccess = binaryImg.randomAccess();

        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                binaryAccess.setPositionAndGet(pos).set(255);
            }
        }

        final ImagePlus imp = ImageJFunctions.wrap(
                Views.permute(
                        Views.addDimension(binaryImg, 0, 0),
                        2, 3),
                "Fill");

        final ImageStatistics stats = imp.getStatistics(ImageStatistics.MIN_MAX);
        imp.setDisplayRange(stats.min, stats.max);
        imp.setCalibration(originalImp.getCalibration());

        return imp;
    }

    public ImagePlus getDistanceImp()
    {
        final int width = originalImp.getWidth();
        final int height = originalImp.getHeight();
        final int depth = originalImp.getNSlices();

        if (newStack == null) {
            newStack = new SearchImageStack<>(depth, new SupplierUtil.MapSearchImageSupplier<>(width, height));
            mergeFills(fillers, newStack);
        }

        final Img<FloatType> distanceImg = ArrayImgs.floats(width, height, depth);
        final RandomAccess<FloatType> distanceAccess = distanceImg.randomAccess();

        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                distanceAccess.setPositionAndGet(pos).set((float)node.g);
            }
        }

        final ImagePlus imp = ImageJFunctions.wrap(
                Views.permute(
                        Views.addDimension(distanceImg, 0, 0),
                        2, 3),
                "Fill");
        final ImageStatistics stats = imp.getStatistics(ImageStatistics.MIN_MAX);
        imp.setDisplayRange(stats.min, stats.max);
        imp.setCalibration(originalImp.getCalibration());

        return imp;

    }

    public static void mergeFills(final Collection<FillerThread> fillers,
                                     final SearchImageStack<DefaultSearchNode> newStack)
    {
        // Merge the individuals fills into a single stack
        for (final FillerThread filler : fillers) {
            for (int sliceIdx = 0; sliceIdx < filler.nodes_as_image_from_start.size(); sliceIdx++) {
                final SearchImage<DefaultSearchNode> slice = filler.nodes_as_image_from_start.getSlice(sliceIdx);
                if (slice == null) continue;
                SearchImage<DefaultSearchNode> newSlice = newStack.getSlice(sliceIdx);
                if (newSlice == null) {
                    newSlice = newStack.newSlice(sliceIdx);
                }
                for (final DefaultSearchNode node : slice) {
                    if (node == null) continue;
                    DefaultSearchNode existingNode = newSlice.getValue(node.x, node.y);
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
