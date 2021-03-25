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
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import sc.fiji.snt.util.SparseMatrix;
import sc.fiji.snt.util.SparseMatrixStack;

import java.util.*;

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
        final SparseMatrixStack<DefaultSearchNode> newStack = new SparseMatrixStack<>(depth);
        for (final FillerThread filler : fillers) {
            for (final int sliceIdx : filler.nodes_as_image_from_start.stack.keySet()) {
                final SparseMatrix<DefaultSearchNode> slice = filler.nodes_as_image_from_start.getSlice(sliceIdx);
                if (slice == null) continue;
                SparseMatrix<DefaultSearchNode> newSlice = newStack.getSlice(sliceIdx);
                if (newSlice == null) {
                    newSlice = newStack.newSlice(sliceIdx);
                }
                for (final int rowIdx : slice.doubleMap.keySet()) {
                    final Int2ObjectOpenHashMap<DefaultSearchNode> row = slice.getRow(rowIdx);
                    if (row == null) continue;
                    Int2ObjectOpenHashMap<DefaultSearchNode> newRow = newSlice.getRow(rowIdx);
                    if (newRow == null) {
                        newRow = newSlice.newRow(rowIdx);
                    }
                    for (final DefaultSearchNode node : row.values()) {
                        // If there are two nodes with the same index, choose the one with lower g-score
                        newRow.merge(node.x, node, (v1, v2) -> v1.g == Math.min(v1.g, v2.g) ? v1 : v2);
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
            final SparseMatrix<DefaultSearchNode> slice = newStack.getSlice(z);
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
