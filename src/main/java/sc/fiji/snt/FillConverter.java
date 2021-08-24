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
import ij.measure.Calibration;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import sc.fiji.snt.tracing.DefaultSearchNode;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.image.SearchImage;
import sc.fiji.snt.tracing.image.SearchImageStack;
import sc.fiji.snt.tracing.image.SupplierUtil;

import java.util.Collection;

/**
 * Convert a collection of {@link FillerThread}s to an {@link Img} or {@link ImagePlus}.
 *
 * @author Cameron Arshadi
 */
public class FillConverter {

    public enum ResultType {SAME, BINARY_MASK, DISTANCE}

    final Collection<FillerThread> fillers;
    @SuppressWarnings("rawtypes")
    final Img img;
    final Calibration calibration;
    final long[] imgDimensions;
    SearchImageStack<DefaultSearchNode> newStack;

    public FillConverter(final Collection<FillerThread> fillers, final ImagePlus originalImp) {
        this.fillers = fillers;
        this.img = ImageJFunctions.wrap(originalImp);
        this.imgDimensions = Intervals.dimensionsAsLongArray(this.img);
        this.calibration = originalImp.getCalibration();
    }

    public <T extends NumericType<T> & NativeType<T>> FillConverter(final Collection<FillerThread> fillers,
                                                                    final Img<T> originalImg,
                                                                    final Calibration calibration)
    {
        this.fillers = fillers;
        this.img = originalImg;
        this.imgDimensions = Intervals.dimensionsAsLongArray(originalImg);
        this.calibration = calibration;
    }

    public <T extends NumericType<T> & NativeType<T>> Img<T> getImg() {
        return createImg(ResultType.SAME);
    }

    public ImagePlus getImp() {
        return createImp(ResultType.SAME);
    }

    public Img<BitType> getBinaryImg() {
        return createImg(ResultType.BINARY_MASK);
    }

    public ImagePlus getBinaryImp() {
        return createImp(ResultType.BINARY_MASK);
    }

    public Img<FloatType> getDistanceImg() {
        return createImg(ResultType.DISTANCE);
    }

    public ImagePlus getDistanceImp() {
        return createImp(ResultType.DISTANCE);
    }

    @SuppressWarnings({"unchecked"})
    private <T extends NumericType<T> & NativeType<T>> Img<T> createImg(final ResultType resultType) {
        final Img<T> fillImg;
        switch (resultType) {
            case SAME:
                fillImg = (Img<T>) img.factory().create(imgDimensions);
                break;
            case BINARY_MASK:
                fillImg = img.factory().imgFactory(new BitType()).create(imgDimensions);
                break;
            case DISTANCE:
                fillImg = img.factory().imgFactory(new FloatType()).create(imgDimensions);
                break;
            default:
                throw new IllegalArgumentException("Unknown Fill image type " + resultType);
        }
        final RandomAccess<T> imgAccess = img.randomAccess();
        final RandomAccess<T> fillImgAccess = fillImg.randomAccess();
        final SearchImageStack<DefaultSearchNode> newStack = getStack();
        final int[] pos = new int[3];
        for (final SearchImage<DefaultSearchNode> slice : newStack) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                if (node == null) continue;
                pos[0] = node.x;
                pos[1] = node.y;
                pos[2] = node.z;
                switch (resultType) {
                    case SAME:
                        fillImgAccess.setPositionAndGet(pos).set(imgAccess.setPositionAndGet(pos));
                        break;
                    case BINARY_MASK:
                        fillImgAccess.setPositionAndGet(pos).setOne();
                        break;
                    case DISTANCE:
                        fillImgAccess.setPositionAndGet(pos).set((T) new FloatType((float)node.g));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown result type " + resultType);
                }
            }
        }
        return fillImg;
    }

    private <T extends NumericType<T> & NativeType<T>> ImagePlus createImp(final ResultType resultType) {
        final Img<T> img = createImg(resultType);
        final ImagePlus fillImp;
        if (img.numDimensions() == 3) {
            // Swap C and T
            fillImp = ImageJFunctions.wrap(
                    Views.permute(
                            Views.addDimension(
                                    img,
                                    0,
                                    0),
                            2,
                            3),
                    "Fill");
        } else {
            fillImp = ImageJFunctions.wrap(img, "Fill");
        }
        fillImp.resetDisplayRange();
        fillImp.setCalibration(calibration);
        return fillImp;
    }

    private SearchImageStack<DefaultSearchNode> getStack() {
        if (newStack == null) {
            newStack = new SearchImageStack<>(new SupplierUtil.MapSearchImageSupplier<>());
            mergeFills(fillers, newStack);
        }
        return newStack;
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
