/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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


import ij.CompositeImage;
import ij.ImagePlus;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import net.imglib2.display.ColorTable8;
import org.scijava.util.ColorRGB;

import java.awt.*;
import java.awt.image.IndexColorModel;

/**
 * Utilities for colormaps and IJ lookup tables
 */
public class ColorMaps {

    public static final ColorTable ICE = ColorTables.ICE; // convenience
    public static final ColorTable VIRIDIS = viridisColorTable();
    public static final ColorTable PLASMA = plasmaColorTable();

    static {
        net.imagej.patcher.LegacyInjector.preinit();
    } // required for _every_ class that imports ij. classes

    /**
     * Applies the "viridis" colormap to the specified (non-RGB) image
     *
     * @param imp A non-RGB image
     */
    public static void applyViridis(final ImagePlus imp) {
        applyViridis(imp, -1, false);
    }

    /**
     * Applies the "viridis" colormap to the specified (non-RGB) image
     *
     * @param imp            A non-RGB image
     * @param backgroundGray The gray value (8-bit scale) to be used as the first entry of
     *                       the LUT. It is ignored if negative.
     * @param inverted       If the LUT should be inverted
     */
    public static void applyViridis(final ImagePlus imp, final int backgroundGray, final boolean inverted) {
        applyLut(imp, viridis(backgroundGray, inverted));
    }

    /**
     * Applies the "plasma" colormap to the specified (non-RGB) image
     *
     * @param imp A non-RGB image
     */
    public static void applyPlasma(final ImagePlus imp) {
        applyPlasma(imp, -1, false);
    }

    /**
     * Applies the "plasma" colormap to the specified (non-RGB) image
     *
     * @param imp            A non-RGB image
     * @param backgroundGray The gray value (8-bit scale) to be used as the first entry of
     *                       the LUT. It is ignored if negative.
     * @param inverted       If the LUT should be inverted
     */
    public static void applyPlasma(final ImagePlus imp, final int backgroundGray, final boolean inverted) {
        applyLut(imp, plasma(backgroundGray, inverted));
    }

    /**
     * Returns a 'core' color table from its title
     * @param name the color table name (e.g., "fire", "viridis", etc)
     * @return the color table
     */
    public static ColorTable get(final String name) {
        if (name == null || name.isBlank())
            return null;
        return switch (name.split("\\.")[0].toLowerCase()) {
            case "blue" -> ColorTables.BLUE;
            case "cyan" -> ColorTables.CYAN;
            case "grays" -> ColorTables.GRAYS;
            case "green" -> ColorTables.GREEN;
            case "magenta" -> ColorTables.MAGENTA;
            case "red" -> ColorTables.RED;
            case "yellow" -> ColorTables.YELLOW;
            case "fire" -> ColorTables.FIRE;
            case "ice" -> ICE;
            case "plasma" -> PLASMA;
            case "red-green", "red green", "redgreen" -> ColorTables.REDGREEN;
            case "spectrum" -> ColorTables.SPECTRUM;
            case "viridis" -> VIRIDIS;
            case "glasbey", "glasbey-no-grays", "distinct" -> glasbeyNoGrays();
            default -> null;
        };
    }

    public static ColorRGB[] discreteColors(final ColorTable colorTable, final int n) {
        if (n > colorTable.getLength()) {
            throw new IllegalArgumentException("n must be less than or equal to the number of colors in the color table");
        }
        final ColorRGB[] rgb = new ColorRGB[n];
        for (int i = 0; i < n; i++) {
            final int idx = Math.round((float) ((colorTable.getLength() - 1) * i) / n);
            rgb[i] = new ColorRGB(colorTable.get(ColorTable.RED, idx), colorTable.get(
                    ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
        }
        return rgb;
    }

    public static Color[] discreteColorsAWT(final ColorTable colorTable, final int n) {
        final ColorRGB[] cRGB = discreteColors(colorTable, n);
        final Color[] cAWT = new Color[n];
        for (int i = 0; i < n; i++)  cAWT[i] = new Color(cRGB[i].getRed(), cRGB[i].getGreen(), cRGB[i].getBlue());
        return cAWT;
    }

    public static Color[] glasbeyColorsAWT(final int n) {
        final ColorTable colorTable = glasbeyNoGrays();
        if (n > colorTable.getLength()) {
            throw new IllegalArgumentException("n must be less than or equal to " + colorTable.getLength());
        }
        final Color[] cAWT = new Color[n];
        for (int i = 0; i < n; i++)
            cAWT[i] = new Color(colorTable.get(ColorTable.RED, i), colorTable.get(ColorTable.GREEN, i), colorTable.get(ColorTable.BLUE, i));
        return cAWT;
    }

    private static ColorTable glasbeyNoGrays() {
        return custom(glasbey_bw_minc_20());
    }


    private static ColorTable viridisColorTable() {
        return custom(viridis());
    }

    private static ColorTable plasmaColorTable() {
        return custom(plasma());
    }

    /**
     * Applies a ColorModel to a non-RGB image
     */
    private static void applyLut(final ImagePlus imp, final IndexColorModel cm) {
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
     * @param backgroundGray the positive gray value (8-bit scale) to be used as the first
     *                       entry of the LUT. It is ignored if negative.
     * @param inverted       If the LUT should be inverted
     * @return The "viridis" LUT with the specified background entry
     */
    private static IndexColorModel viridis(final int backgroundGray, final boolean inverted) {
        return getICM(viridis(), backgroundGray, inverted);
    }

    /**
     * Returns an IndexColorModel similar to Matplotlib's "plasma" color map.
     *
     * @param backgroundGray the positive gray value (8-bit scale) to be used as the first
     *                       entry of the LUT. It is ignored if negative.
     * @param inverted       If the LUT should be inverted
     * @return The "plasma" LUT with the specified background entry
     */
    private static IndexColorModel plasma(final int backgroundGray, final boolean inverted) {
        return getICM(plasma(), backgroundGray, inverted);

    }

    private static int[][] glasbey_bw_minc_20() { // from holoviz colorcet
        final int[] r = {215, 140, 2, 0, 152, 255, 108, 255, 88, 0, 0, 0, 161, 188, 149, 192, 100, 121, 7, 254, 0, 143,
                255, 238, 94, 155, 236, 166, 90, 4, 158, 156, 203, 113, 0, 131, 93, 57, 253, 190, 219, 147, 228, 47,
                196, 85, 197, 4, 105, 128, 109, 78, 134, 254, 194, 197, 118, 1, 0, 218, 249, 106, 195, 225, 218, 187,
                146, 160, 87, 211, 55, 151, 142, 255, 200, 174, 110, 191, 140, 120, 255, 169, 255, 95, 103, 255, 76, 83,
                170, 2, 0, 97, 144, 191, 81, 78, 124, 255, 131, 77, 137, 123, 0, 170, 129, 98, 195, 205, 255, 195, 33,
                0, 99, 137, 152, 206, 210, 97, 153, 176, 242, 0, 205, 69, 122, 114, 147, 0, 173, 63, 95, 0, 124, 152,
                57, 184, 255, 255, 128, 33, 161, 79, 159, 51, 194, 199, 108, 118, 165, 218, 216, 251, 75, 214, 122, 76,
                70, 163, 234, 255, 71, 162, 145, 79, 231, 159, 88, 176, 134, 193, 228, 185, 57, 227, 173, 169, 105, 148,
                176, 7, 0, 90, 91, 48, 229, 95, 115, 75, 201, 156, 200, 5, 167, 231, 96, 242, 119, 96, 104, 121, 13,
                157, 132, 142, 227, 185, 122, 255, 76, 226, 255, 214, 191, 215, 189, 136, 255, 255, 51, 184, 108, 188,
                27, 161, 163, 109, 137, 90, 249, 231, 112, 25, 23, 0, 248, 122, 177, 126, 0, 122, 218, 219, 243, 163,
                138, 102, 232, 216, 224, 254, 117, 152, 228, 140, 119, 47};
        final int[] g = {0, 60, 136, 172, 255, 127, 0, 165, 59, 87, 0, 253, 117, 183, 181, 4, 84, 0, 116, 245, 75, 122,
                114, 185, 126, 228, 0, 123, 0, 198, 75, 59, 196, 130, 175, 136, 55, 0, 192, 231, 109, 184, 82, 83, 102,
                98, 159, 130, 231, 39, 180, 51, 163, 3, 166, 87, 88, 104, 214, 224, 255, 104, 152, 205, 150, 3, 82, 0,
                155, 140, 69, 165, 141, 70, 255, 109, 208, 255, 84, 54, 160, 0, 28, 17, 151, 95, 103, 146, 113, 207,
                196, 53, 212, 213, 69, 35, 90, 206, 2, 253, 0, 82, 116, 131, 113, 101, 52, 41, 154, 93, 104, 142, 128,
                135, 221, 127, 183, 0, 84, 199, 255, 236, 134, 0, 157, 113, 255, 84, 148, 164, 58, 76, 184, 42, 110, 0,
                128, 210, 48, 52, 94, 181, 159, 124, 65, 232, 5, 188, 197, 84, 143, 124, 100, 195, 46, 143, 136, 0, 163,
                188, 72, 199, 162, 105, 94, 145, 81, 94, 109, 110, 0, 183, 46, 126, 59, 187, 181, 210, 141, 95, 152, 15,
                125, 88, 101, 64, 74, 83, 122, 50, 230, 171, 107, 176, 255, 222, 68, 37, 127, 159, 233, 248, 0, 109, 65,
                73, 74, 208, 93, 179, 76, 240, 96, 163, 125, 111, 48, 233, 88, 140, 135, 148, 230, 60, 81, 0, 101, 88,
                143, 215, 108, 89, 38, 216, 161, 150, 167, 208, 203, 71, 255, 5, 221, 228, 19, 104, 253, 171, 186, 83,
                174, 51, 115, 89, 71, 62};
        final int[] b = {0, 255, 0, 199, 0, 209, 79, 48, 0, 89, 221, 207, 106, 255, 120, 185, 116, 0, 216, 144, 0, 0,
                102, 185, 102, 255, 119, 185, 164, 0, 0, 80, 0, 152, 138, 255, 59, 0, 255, 192, 1, 182, 255, 130, 144,
                32, 114, 135, 128, 144, 255, 255, 2, 203, 197, 70, 61, 66, 213, 255, 0, 176, 0, 156, 255, 253, 130, 115,
                85, 143, 39, 195, 95, 0, 250, 255, 167, 140, 177, 25, 121, 31, 69, 35, 148, 148, 116, 204, 49, 254, 108,
                93, 47, 124, 162, 12, 0, 68, 207, 255, 61, 92, 157, 152, 143, 254, 137, 71, 181, 187, 2, 101, 35, 191,
                213, 88, 91, 110, 68, 219, 210, 2, 189, 197, 127, 71, 186, 193, 236, 22, 128, 51, 211, 0, 100, 91, 61,
                233, 90, 0, 111, 176, 70, 61, 0, 62, 232, 80, 169, 110, 56, 255, 73, 235, 54, 165, 255, 196, 213, 120,
                0, 255, 234, 147, 178, 176, 43, 212, 225, 114, 227, 139, 0, 164, 48, 76, 131, 144, 70, 120, 137, 2, 128,
                38, 59, 41, 188, 107, 221, 145, 242, 235, 154, 0, 99, 0, 1, 65, 203, 176, 162, 219, 118, 73, 48, 108,
                134, 182, 199, 146, 238, 165, 38, 181, 0, 178, 161, 176, 76, 122, 82, 210, 254, 114, 168, 152, 123, 139,
                139, 124, 1, 255, 255, 86, 253, 60, 213, 221, 176, 60, 230, 177, 255, 111, 35, 131, 112, 232, 213, 105,
                155, 224, 126, 38, 105, 168};
        return new int[][]{r, g, b};
    }

    private static int[][] viridis() {
        final int[] r = {68, 68, 69, 69, 70, 70, 70, 70, 71, 71, 71, 71, 71, 72, 72, 72, 72, 72, 72, 72, 72, 72, 72,
                72, 72, 72, 72, 72, 72, 72, 71, 71, 71, 71, 71, 70, 70, 70, 70, 69, 69, 69, 68, 68, 68, 67, 67, 66, 66,
                66, 65, 65, 64, 64, 63, 63, 62, 62, 62, 61, 61, 60, 60, 59, 59, 58, 58, 57, 57, 56, 56, 55, 55, 54, 54,
                53, 53, 52, 52, 51, 51, 50, 50, 49, 49, 49, 48, 48, 47, 47, 46, 46, 46, 45, 45, 44, 44, 44, 43, 43, 42,
                42, 42, 41, 41, 41, 40, 40, 39, 39, 39, 38, 38, 38, 37, 37, 37, 36, 36, 35, 35, 35, 34, 34, 34, 33, 33,
                33, 33, 32, 32, 32, 31, 31, 31, 31, 31, 31, 31, 30, 30, 30, 31, 31, 31, 31, 31, 31, 32, 32, 33, 33, 34,
                34, 35, 36, 37, 37, 38, 39, 40, 41, 42, 44, 45, 46, 47, 49, 50, 52, 53, 55, 56, 58, 59, 61, 63, 64, 66,
                68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 99, 101, 103, 105, 108, 110, 112, 115, 117,
                119, 122, 124, 127, 129, 132, 134, 137, 139, 142, 144, 147, 149, 152, 155, 157, 160, 162, 165, 168, 170,
                173, 176, 178, 181, 184, 186, 189, 192, 194, 197, 200, 202, 205, 208, 210, 213, 216, 218, 221, 223, 226,
                229, 231, 234, 236, 239, 241, 244, 246, 248, 251, 253};
        final int[] g = {1, 2, 4, 5, 7, 8, 10, 11, 13, 14, 16, 17, 19, 20, 22, 23, 24, 26, 27, 28, 29, 31, 32, 33, 35,
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
                228, 228, 229, 229, 229, 229, 230, 230, 230, 231, 231};
        final int[] b = {84, 86, 87, 89, 90, 92, 93, 94, 96, 97, 99, 100, 101, 103, 104, 105, 106, 108, 109, 110, 111,
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
                25, 25, 24, 24, 24, 25, 25, 26, 27, 28, 29, 30, 32, 33, 35, 37};
        return new int[][]{r, g, b};
    }

    private static int[][] plasma() {
        final int[] r = {12, 16, 19, 21, 24, 27, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49, 51, 52, 54, 56, 58, 59,
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
                249, 249, 248, 248, 247, 247, 246, 246, 245, 245, 244, 243, 243, 242, 242, 241, 240, 240, 239};
        final int[] g = {7, 7, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 2, 2,
                2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 3, 3,
                4, 4, 5, 6, 7, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                33, 34, 35, 36, 37, 38, 39, 40, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53, 54, 55, 56, 57, 59, 60, 61,
                62, 63, 64, 65, 66, 68, 69, 70, 71, 72, 73, 74, 75, 77, 78, 79, 80, 81, 82, 83, 85, 86, 87, 88, 89, 90,
                91, 93, 94, 95, 96, 97, 98, 100, 101, 102, 103, 104, 106, 107, 108, 109, 110, 112, 113, 114, 115, 116,
                118, 119, 120, 121, 123, 124, 125, 126, 128, 129, 130, 132, 133, 134, 135, 137, 138, 139, 141, 142, 143,
                145, 146, 147, 149, 150, 152, 153, 154, 156, 157, 159, 160, 162, 163, 164, 166, 167, 169, 170, 172, 173,
                175, 176, 178, 179, 181, 182, 184, 185, 187, 188, 190, 192, 193, 195, 196, 198, 199, 201, 203, 204, 206,
                208, 209, 211, 213, 214, 216, 217, 219, 221, 223, 224, 226, 228, 229, 231, 233, 234, 236, 238, 240, 241,
                243, 245, 246, 248};
        final int[] b = {134, 135, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 148, 149, 150, 151, 152,
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
                38, 38, 38, 38, 38, 38, 37, 35, 33};
        return new int[][]{r, g, b};
    }

    private static ColorTable custom(final int[][] v) {
        // from net.imagej.display.ColorTables
        final byte[][] values = new byte[v.length][];
        for (int j = 0; j < v.length; j++) {
            values[j] = new byte[v[j].length];
            for (int i = 0; i < v[j].length; i++) {
                values[j][i] = (byte) v[j][i];
            }
        }
        return new ColorTable8(values);
    }

    private static IndexColorModel getICM(final int[][] rgb, final int backgroundGray, final boolean inverted) {
        // Cast elements
        final byte[] reds = new byte[256];
        final byte[] greens = new byte[256];
        final byte[] blues = new byte[256];
        for (int i = 0; i < 256; i++) {
            final int idx = (inverted) ? 255 - i : i;
            reds[idx] = (byte) rgb[0][i];
            greens[idx] = (byte) rgb[1][i];
            blues[idx] = (byte) rgb[2][i];
        }
        // Set background color
        if (backgroundGray > -1)
            reds[0] = greens[0] = blues[0] = (byte) backgroundGray;
        return new IndexColorModel(8, 256, reds, greens, blues);
    }
}
