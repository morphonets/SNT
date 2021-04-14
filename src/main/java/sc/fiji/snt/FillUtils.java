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
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import sc.fiji.snt.util.HashTableSearchImage;
import sc.fiji.snt.util.SearchImage;
import sc.fiji.snt.util.SearchImageStack;
import sc.fiji.snt.util.SupplierUtil;

import java.util.Collection;

/**
 * Static utilities for handling of {@link Fill}s
 *
 * @author Cameron Arshadi
 */
public class FillUtils {

    private FillUtils() {}

    public static ImagePlus fillsAsImagePlus(final Collection<FillerThread> fillers, final ImagePlus originalImp,
                                             final boolean realData, final int imageType)
    {
        if (fillers.isEmpty()) return null;

        final int width = originalImp.getWidth();
        final int height = originalImp.getHeight();
        final int depth = originalImp.getNSlices();
        byte[][] slices_data_b = null;
        short[][] slices_data_s = null;
        float[][] slices_data_f = null;
        {
            final ImageStack s = originalImp.getStack();
            switch (imageType) {
                case ImagePlus.GRAY8:
                case ImagePlus.COLOR_256:
                    slices_data_b = new byte[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_b[z] = (byte[]) s.getPixels(z + 1);
                    break;
                case ImagePlus.GRAY16:
                    slices_data_s = new short[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_s[z] = (short[]) s.getPixels(z + 1);
                    break;
                case ImagePlus.GRAY32:
                    slices_data_f = new float[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_f[z] = (float[]) s.getPixels(z + 1);
                    break;
            }
        }

        // Merge the individuals fills into a single stack
        final SearchImageStack<DefaultSearchNode> newStack =
                new SearchImageStack<>(depth, new SupplierUtil.SparseMatrixSupplier<>());

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

        final byte[][] new_slice_data_b = new byte[depth][];
        final short[][] new_slice_data_s = new short[depth][];
        final float[][] new_slice_data_f = new float[depth][];

        for (int z = 0; z < depth; ++z) {
            switch (imageType) {
                case ImagePlus.GRAY8:
                case ImagePlus.COLOR_256:
                    new_slice_data_b[z] = new byte[width * height];
                    break;
                case ImagePlus.GRAY16:
                    new_slice_data_s[z] = new short[width * height];
                    break;
                case ImagePlus.GRAY32:
                    new_slice_data_f[z] = new float[width * height];
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported image type");
            }
        }

        final ImageStack stack = new ImageStack(width, height);
        final double threshold = fillers.iterator().next().getThreshold();
        for (int z = 0; z < depth; ++z) {
            final SearchImage<DefaultSearchNode> slice = newStack.getSlice(z);
            if (slice != null) for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    final DefaultSearchNode s = slice.getValue(x,y);
                    if ((s != null) && (s.g <= threshold)) {
                        switch (imageType) {
                            case ImagePlus.GRAY8:
                            case ImagePlus.COLOR_256:
                                new_slice_data_b[z][y * width + x] = realData
                                        ? slices_data_b[z][y * width + x] : (byte) 255;
                                break;
                            case ImagePlus.GRAY16:
                                new_slice_data_s[z][y * width + x] = realData
                                        ? slices_data_s[z][y * width + x] : 255;
                                break;
                            case ImagePlus.GRAY32:
                                new_slice_data_f[z][y * width + x] = realData
                                        ? slices_data_f[z][y * width + x] : 255;
                                break;
                            default:
                                break;
                        }
                    }
                }
            }

            switch (imageType) {
                case ImagePlus.GRAY8:
                case ImagePlus.COLOR_256:
                    final ByteProcessor bp = new ByteProcessor(width, height);
                    bp.setPixels(new_slice_data_b[z]);
                    stack.addSlice(null, bp);
                    break;
                case ImagePlus.GRAY16:
                    final ShortProcessor sp = new ShortProcessor(width, height);
                    sp.setPixels(new_slice_data_s[z]);
                    stack.addSlice(null, sp);
                    break;
                case ImagePlus.GRAY32:
                    final FloatProcessor fp = new FloatProcessor(width, height);
                    fp.setPixels(new_slice_data_f[z]);
                    stack.addSlice(null, fp);
                    break;
                default:
                    break;
            }

        }

        final ImagePlus imp = new ImagePlus("filled neuron", stack);

        imp.setCalibration(originalImp.getCalibration());

        return imp;

    }

}
