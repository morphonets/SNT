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
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
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

    public ImagePlus getGreyImp()
    {
        final int width = originalImp.getWidth();
        final int height = originalImp.getHeight();
        final int depth = originalImp.getNSlices();

        final int imageType = originalImp.getType();
        byte[][] slices_data_b = null;
        short[][] slices_data_s = null;
        float[][] slices_data_f = null;
        Img<UnsignedByteType> newImgB = null;
        Img<UnsignedShortType> newImgS = null;
        Img<FloatType> newImgF = null;
        RandomAccess<UnsignedByteType> newImgBAccess = null;
        RandomAccess<UnsignedShortType> newImgSAccess = null;
        RandomAccess<FloatType> newImgFAccess = null;
        switch (imageType) {
            case ImagePlus.GRAY8:
            case ImagePlus.COLOR_256:
                newImgB = ArrayImgs.unsignedBytes(width, height, depth);
                newImgBAccess = newImgB.randomAccess();
                slices_data_b = fillers.iterator().next().slices_data_b;
                break;
            case ImagePlus.GRAY16:
                newImgS = ArrayImgs.unsignedShorts(width, height, depth);
                newImgSAccess = newImgS.randomAccess();
                slices_data_s = fillers.iterator().next().slices_data_s;
                break;
            case ImagePlus.GRAY32:
                newImgF = ArrayImgs.floats(width, height, depth);
                newImgFAccess = newImgF.randomAccess();
                slices_data_f = fillers.iterator().next().slices_data_f;
                break;
            default:
                throw new IllegalArgumentException("Unsupported image type");
        }

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
                switch (imageType) {
                    case ImagePlus.GRAY8:
                    case ImagePlus.COLOR_256:
                        newImgBAccess.setPositionAndGet(pos).set(slices_data_b[node.z][node.y * width + node.x] & 0xFF);
                        break;
                    case ImagePlus.GRAY16:
                        newImgSAccess.setPositionAndGet(pos).set(slices_data_s[node.z][node.y * width + node.x] & 0xFFFF);
                        break;
                    case ImagePlus.GRAY32:
                        newImgFAccess.setPositionAndGet(pos).set(slices_data_f[node.z][node.y * width + node.x]);
                        break;
                    default:
                        break;
                }
            }
        }

        // Swapping 'C' and 'Z' requires permuting the last two dimensions
        ImagePlus imp = null;
        switch (imageType) {
            case ImagePlus.GRAY8:
            case ImagePlus.COLOR_256:
                imp = ImageJFunctions.wrap(
                        Views.permute(
                                Views.addDimension(newImgB, 0, 0),
                                2, 3),
                        "Fill");
                break;
            case ImagePlus.GRAY16:
                imp = ImageJFunctions.wrap(
                        Views.permute(
                                Views.addDimension(newImgS, 0, 0),
                                2, 3),
                        "Fill");
                break;
            case ImagePlus.GRAY32:
                imp = ImageJFunctions.wrap(
                        Views.permute(
                                Views.addDimension(newImgF, 0, 0),
                                2, 3),
                        "Fill");
                break;
            default:
                break;
        }

        final ImageStatistics stats = imp.getStatistics(ImageStatistics.MIN_MAX);
        imp.setDisplayRange(stats.min, stats.max);
        imp.setCalibration(originalImp.getCalibration());

        return imp;

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
