/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
package sc.fiji.snt.util;


import java.awt.image.IndexColorModel;

import ij.CompositeImage;
import ij.ImagePlus;

/** Utilities for colormaps and IJ lookup tables */
//TODO: Support Scijava ColorTables
public class ColorMaps {

	/**
	 * Applies the "viridis" colormap to the specified (non-RGB) image
	 * @param imp
	 *            A non-RGB image
	 */
	public static void applyViridisColorMap(final ImagePlus imp) {
		applyViridisColorMap(imp, -1, false);
	}

	/**
	 * Applies the "viridis" colormap to the specified (non-RGB) image
	 *
	 * @param imp
	 *            A non-RGB image
	 * @param backgroundGray
	 *            The gray value (8-bit scale) to be used as the first entry of
	 *            the LUT. It is ignored if negative.
	 * @param inverted
	 *            If the LUT should be inverted
	 */
	public static void applyViridisColorMap(final ImagePlus imp, final int backgroundGray, final boolean inverted) {
		applyLut(imp, viridisColorMap(backgroundGray, inverted));
	}

	/**
	 * Applies the "magma" colormap to the specified (non-RGB) image
	 *
	 * @param imp
	 *            A non-RGB image
	 */
	public static void applyMagmaColorMap(final ImagePlus imp) {
		applyMagmaColorMap(imp, -1, false);
	}

	/**
	 * Applies the "magma" colormap to the specified (non-RGB) image
	 *
	 * @param imp
	 *            A non-RGB image
	 * @param backgroundGray
	 *            The gray value (8-bit scale) to be used as the first entry of
	 *            the LUT. It is ignored if negative.
	 * @param inverted
	 *            If the LUT should be inverted
	 */
	public static void applyMagmaColorMap(final ImagePlus imp, final int backgroundGray, final boolean inverted) {
		applyLut(imp, plasmaColorMap(backgroundGray, inverted));
	}

	/** Applies a ColorModel to a non-RGB image */
	static void applyLut(final ImagePlus imp, final IndexColorModel cm) {
		if (imp != null && imp.getType() != ImagePlus.COLOR_RGB) {
			if (imp.isComposite()) {
				((CompositeImage) imp).setChannelColorModel(cm);
			} else {
				imp.getProcessor().setColorModel(cm);
				imp.updateAndDraw();
			}
		}
	}

	/**
	 * Returns an IndexColorModel similar to Matplotlib's viridis color map.
	 *
	 * @param backgroundGray
	 *            the positive gray value (8-bit scale) to be used as the first
	 *            entry of the LUT. It is ignored if negative.
	 * @param inverted
	 *            If the LUT should be inverted
	 * @return The "viridis" LUT with the specified background entry
	 */
	private static IndexColorModel viridisColorMap(final int backgroundGray, final boolean inverted) {

		final int[] r = { 68, 68, 69, 69, 70, 70, 70, 70, 71, 71, 71, 71, 71, 72, 72, 72, 72, 72, 72, 72, 72, 72, 72,
				72, 72, 72, 72, 72, 72, 72, 71, 71, 71, 71, 71, 70, 70, 70, 70, 69, 69, 69, 68, 68, 68, 67, 67, 66, 66,
				66, 65, 65, 64, 64, 63, 63, 62, 62, 62, 61, 61, 60, 60, 59, 59, 58, 58, 57, 57, 56, 56, 55, 55, 54, 54,
				53, 53, 52, 52, 51, 51, 50, 50, 49, 49, 49, 48, 48, 47, 47, 46, 46, 46, 45, 45, 44, 44, 44, 43, 43, 42,
				42, 42, 41, 41, 41, 40, 40, 39, 39, 39, 38, 38, 38, 37, 37, 37, 36, 36, 35, 35, 35, 34, 34, 34, 33, 33,
				33, 33, 32, 32, 32, 31, 31, 31, 31, 31, 31, 31, 30, 30, 30, 31, 31, 31, 31, 31, 31, 32, 32, 33, 33, 34,
				34, 35, 36, 37, 37, 38, 39, 40, 41, 42, 44, 45, 46, 47, 49, 50, 52, 53, 55, 56, 58, 59, 61, 63, 64, 66,
				68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 99, 101, 103, 105, 108, 110, 112, 115, 117,
				119, 122, 124, 127, 129, 132, 134, 137, 139, 142, 144, 147, 149, 152, 155, 157, 160, 162, 165, 168, 170,
				173, 176, 178, 181, 184, 186, 189, 192, 194, 197, 200, 202, 205, 208, 210, 213, 216, 218, 221, 223, 226,
				229, 231, 234, 236, 239, 241, 244, 246, 248, 251, 253 };
		final int[] g = { 1, 2, 4, 5, 7, 8, 10, 11, 13, 14, 16, 17, 19, 20, 22, 23, 24, 26, 27, 28, 29, 31, 32, 33, 35,
				36, 37, 38, 40, 41, 42, 44, 45, 46, 47, 48, 50, 51, 52, 53, 55, 56, 57, 58, 59, 61, 62, 63, 64, 65, 66,
				68, 69, 70, 71, 72, 73, 74, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 88, 89, 90, 91, 92, 93, 94, 95,
				96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 113, 114, 115,
				116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 130, 131, 132, 133, 134, 135,
				136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155,
				156, 157, 158, 159, 160, 161, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 173, 174,
				175, 176, 177, 178, 179, 180, 181, 182, 182, 183, 184, 185, 186, 187, 188, 188, 189, 190, 191, 192, 193,
				193, 194, 195, 196, 197, 197, 198, 199, 200, 200, 201, 202, 203, 203, 204, 205, 205, 206, 207, 208, 208,
				209, 209, 210, 211, 211, 212, 213, 213, 214, 214, 215, 215, 216, 216, 217, 217, 218, 218, 219, 219, 220,
				220, 221, 221, 222, 222, 222, 223, 223, 223, 224, 224, 225, 225, 225, 226, 226, 226, 227, 227, 227, 228,
				228, 228, 229, 229, 229, 229, 230, 230, 230, 231, 231 };
		final int[] b = { 84, 86, 87, 89, 90, 92, 93, 94, 96, 97, 99, 100, 101, 103, 104, 105, 106, 108, 109, 110, 111,
				112, 113, 115, 116, 117, 118, 119, 120, 121, 122, 122, 123, 124, 125, 126, 126, 127, 128, 129, 129, 130,
				131, 131, 132, 132, 133, 133, 134, 134, 135, 135, 136, 136, 136, 137, 137, 137, 138, 138, 138, 138, 139,
				139, 139, 139, 140, 140, 140, 140, 140, 140, 141, 141, 141, 141, 141, 141, 141, 141, 141, 142, 142, 142,
				142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142,
				142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 142, 141, 141, 141, 141, 141,
				141, 141, 140, 140, 140, 140, 140, 139, 139, 139, 139, 138, 138, 138, 137, 137, 137, 136, 136, 136, 135,
				135, 134, 134, 133, 133, 133, 132, 131, 131, 130, 130, 129, 129, 128, 127, 127, 126, 125, 124, 124, 123,
				122, 121, 121, 120, 119, 118, 117, 116, 115, 114, 113, 112, 111, 110, 109, 108, 107, 106, 105, 104, 103,
				101, 100, 99, 98, 96, 95, 94, 92, 91, 90, 88, 87, 86, 84, 83, 81, 80, 78, 77, 75, 73, 72, 70, 69, 67,
				65, 64, 62, 60, 59, 57, 55, 54, 52, 50, 48, 47, 45, 43, 41, 40, 38, 37, 35, 33, 32, 31, 29, 28, 27, 26,
				25, 25, 24, 24, 24, 25, 25, 26, 27, 28, 29, 30, 32, 33, 35, 37 };

		// Cast elements
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];
		for (int i = 0; i < 256; i++) {
			final int idx = (inverted) ? 255 - i : i;
			reds[idx] = (byte) r[i];
			greens[idx] = (byte) g[i];
			blues[idx] = (byte) b[i];
		}

		// Set background color
		if (backgroundGray > 0)
			reds[0] = greens[0] = blues[0] = (byte) backgroundGray;

		return new IndexColorModel(8, 256, reds, greens, blues);

	}

	/**
	 * Returns an IndexColorModel similar to Matplotlib's "plasma" color map.
	 *
	 * @param backgroundGray
	 *            the positive gray value (8-bit scale) to be used as the first
	 *            entry of the LUT. It is ignored if negative.
	 * @param inverted
	 *            If the LUT should be inverted
	 * @return The "plasma" LUT with the specified background entry
	 */
	private static IndexColorModel plasmaColorMap(final int backgroundGray, final boolean inverted) {

		final int[] r = { 12, 16, 19, 21, 24, 27, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49, 51, 52, 54, 56, 58, 59,
				61, 63, 64, 66, 68, 69, 71, 73, 74, 76, 78, 79, 81, 82, 84, 86, 87, 89, 90, 92, 94, 95, 97, 98, 100,
				101, 103, 104, 106, 108, 109, 111, 112, 114, 115, 117, 118, 120, 121, 123, 124, 126, 127, 129, 130, 132,
				133, 134, 136, 137, 139, 140, 142, 143, 144, 146, 147, 149, 150, 151, 153, 154, 155, 157, 158, 159, 160,
				162, 163, 164, 165, 167, 168, 169, 170, 172, 173, 174, 175, 176, 177, 178, 180, 181, 182, 183, 184, 185,
				186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206,
				207, 208, 209, 209, 210, 211, 212, 213, 214, 215, 215, 216, 217, 218, 219, 220, 220, 221, 222, 223, 223,
				224, 225, 226, 227, 227, 228, 229, 229, 230, 231, 232, 232, 233, 234, 234, 235, 236, 236, 237, 237, 238,
				239, 239, 240, 240, 241, 242, 242, 243, 243, 244, 244, 245, 245, 246, 246, 246, 247, 247, 248, 248, 248,
				249, 249, 250, 250, 250, 250, 251, 251, 251, 252, 252, 252, 252, 252, 252, 253, 253, 253, 253, 253, 253,
				253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 252, 252, 252, 252, 252, 251, 251, 251, 250, 250, 250,
				249, 249, 248, 248, 247, 247, 246, 246, 245, 245, 244, 243, 243, 242, 242, 241, 240, 240, 239 };
		final int[] g = { 7, 7, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 2, 2,
				2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 3, 3,
				4, 4, 5, 6, 7, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 31,
				33, 34, 35, 36, 37, 38, 39, 40, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53, 54, 55, 56, 57, 59, 60, 61,
				62, 63, 64, 65, 66, 68, 69, 70, 71, 72, 73, 74, 75, 77, 78, 79, 80, 81, 82, 83, 85, 86, 87, 88, 89, 90,
				91, 93, 94, 95, 96, 97, 98, 100, 101, 102, 103, 104, 106, 107, 108, 109, 110, 112, 113, 114, 115, 116,
				118, 119, 120, 121, 123, 124, 125, 126, 128, 129, 130, 132, 133, 134, 135, 137, 138, 139, 141, 142, 143,
				145, 146, 147, 149, 150, 152, 153, 154, 156, 157, 159, 160, 162, 163, 164, 166, 167, 169, 170, 172, 173,
				175, 176, 178, 179, 181, 182, 184, 185, 187, 188, 190, 192, 193, 195, 196, 198, 199, 201, 203, 204, 206,
				208, 209, 211, 213, 214, 216, 217, 219, 221, 223, 224, 226, 228, 229, 231, 233, 234, 236, 238, 240, 241,
				243, 245, 246, 248 };
		final int[] b = { 134, 135, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 148, 149, 150, 151, 152,
				152, 153, 154, 154, 155, 156, 156, 157, 158, 158, 159, 159, 160, 161, 161, 162, 162, 163, 163, 163, 164,
				164, 165, 165, 165, 166, 166, 166, 167, 167, 167, 167, 167, 168, 168, 168, 168, 168, 168, 168, 168, 168,
				168, 168, 167, 167, 167, 167, 167, 166, 166, 166, 165, 165, 164, 164, 164, 163, 163, 162, 161, 161, 160,
				160, 159, 158, 158, 157, 156, 155, 155, 154, 153, 152, 151, 151, 150, 149, 148, 147, 146, 145, 144, 143,
				143, 142, 141, 140, 139, 138, 137, 136, 135, 134, 133, 132, 131, 130, 129, 128, 128, 127, 126, 125, 124,
				123, 122, 121, 120, 119, 118, 117, 117, 116, 115, 114, 113, 112, 111, 110, 109, 109, 108, 107, 106, 105,
				104, 103, 102, 102, 101, 100, 99, 98, 97, 96, 96, 95, 94, 93, 92, 91, 90, 90, 89, 88, 87, 86, 85, 84,
				84, 83, 82, 81, 80, 79, 78, 77, 77, 76, 75, 74, 73, 72, 71, 71, 70, 69, 68, 67, 66, 65, 65, 64, 63, 62,
				61, 60, 59, 58, 58, 57, 56, 55, 54, 53, 53, 52, 51, 50, 49, 49, 48, 47, 46, 45, 45, 44, 43, 43, 42, 41,
				41, 40, 40, 39, 38, 38, 38, 37, 37, 37, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 37, 37, 37, 38, 38,
				38, 38, 38, 38, 38, 38, 37, 35, 33 };

		// Cast elements
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];

		for (int i = 0; i < 256; i++) {
			final int idx = (inverted) ? 255 - i : i;
			reds[idx] = (byte) r[i];
			greens[idx] = (byte) g[i];
			blues[idx] = (byte) b[i];
		}

		// Set background color
		if (backgroundGray > -1)
			reds[0] = greens[0] = blues[0] = (byte) backgroundGray;

		return new IndexColorModel(8, 256, reds, greens, blues);

	}

}
