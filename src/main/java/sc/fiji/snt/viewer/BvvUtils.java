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

package sc.fiji.snt.viewer;

import bdv.util.AxisOrder;
import ij.ImagePlus;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Package-private utility methods shared across BVV-related classes
 * ({@link Bvv}, {@link ChannelUnmixingCard}, etc.).
 */
final class BvvUtils {

    /** Default camera distance (screen-pixel units) used before volume-derived params are available. */
    static final double DEFAULT_D_CAM = 2000;
    /** Default near-clip distance (screen-pixel units). */
    static final double DEFAULT_NEAR_CLIP = 1000;
    /** Default far-clip distance (screen-pixel units). */
    static final double DEFAULT_FAR_CLIP = 1000;

    /** Maximum value for an unsigned 8-bit pixel. */
    static final int MAX_UINT8 = 0xFF; // 255
    /** Maximum value for an unsigned 16-bit pixel. */
    static final int MAX_UINT16 = 0xFFFF; // 65535

    /** Default viewport width (px), used as fallback when the actual panel width is unavailable. */
    static final int DEFAULT_VIEWPORT_WIDTH = 1024;

    // --- BVV option defaults (shared between constructors) ---
    /** Preferred window size (px) for BVV viewers. */
    static final int DEFAULT_WINDOW_SIZE = 1024;
    /** GPU cache size in MB. */
    static final int DEFAULT_CACHE_SIZE_MB = 300;
    /** Default render target width (px). */
    static final int DEFAULT_RENDER_SIZE = 512;
    /** Default maximum render time budget (ms). */
    static final int DEFAULT_MAX_RENDER_MILLIS = 30;
    /** Default maximum ray-marching step size (voxels). */
    static final double DEFAULT_MAX_STEP_IN_VOXELS = 1.0;

    /**
     * Camera-to-Z-extent scaling factor. After initTransform, BDV maps the largest
     * XY dimension to fill the viewport. The camera must be far enough back along Z
     * to see the full physical depth of the volume plus some perspective margin.
     * Empirically, 2.5× the Z extent provides comfortable framing for typical
     * neuroscience volumes (deep stacks with high anisotropy).
     */
    static final double CAM_DISTANCE_SCALE = 2.5;

    /**
     * Clip-plane-to-Z-extent scaling factor. Near/far clip planes should extend
     * beyond the physical Z range to avoid clipping volume edges during rotation.
     * 1.5× provides a reasonable margin while keeping the depth buffer usable.
     */
    static final double CLIP_DISTANCE_SCALE = 1.5;

    private BvvUtils() {
    } // static utility class

    /**
     * Loads a boilerplate Groovy script template from the
     * {@code script_templates/Neuroanatomy/Boilerplate/} resource directory.
     *
     * @param scriptName the template file name (e.g. {@code "ChannelUnmixing.groovy"})
     * @return the script contents, or a comment describing the error on failure
     */
    static String loadBoilerPlateScript(final String scriptName) {
        try {
            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
            final InputStream is = cl.getResourceAsStream(
                    "script_templates/Neuroanatomy/Boilerplate/" + scriptName);
            if (is == null)
                return "// Error: " + scriptName + " template not found in resources";
            return new BufferedReader(new InputStreamReader(is))
                    .lines().collect(Collectors.joining("\n"));
        } catch (final Exception e) {
            return "// Error loading template: " + e.getMessage();
        }
    }

    /**
     * Parses an integer preference from SNTPrefs, returning a default on failure.
     */
    static int parseIntPref(final SNTPrefs prefs, final String key, final int def) {
        if (prefs == null) return def;
        try {
            return Integer.parseInt(prefs.getTemp(key, String.valueOf(def)));
        } catch (final NumberFormatException ignored) {
            return def;
        }
    }

    /**
     * Parses a double preference from SNTPrefs, returning a default on failure.
     */
    static double parseDoublePref(final SNTPrefs prefs, final String key, final double def) {
        if (prefs == null) return def;
        try {
            return Double.parseDouble(prefs.getTemp(key, String.valueOf(def)));
        } catch (final NumberFormatException ignored) {
            return def;
        }
    }

    /**
     * Derives the BDV {@link AxisOrder} from an ImagePlus's dimension flags.
     */
    static AxisOrder getAxisOrder(final ImagePlus imp) {
        final boolean hasZ = imp.getNSlices() > 1;
        final boolean hasC = imp.getNChannels() > 1;
        final boolean hasT = imp.getNFrames() > 1;
        if (!hasZ && !hasC && !hasT) return AxisOrder.XY;
        if (hasZ && !hasC && !hasT) return AxisOrder.XYZ;
        if (!hasZ && hasC && !hasT) return AxisOrder.XYC;
        if (!hasZ && !hasC) return AxisOrder.XYT;
        if (hasZ && hasC && !hasT) return AxisOrder.XYZC;
        if (!hasZ) return AxisOrder.XYCT;
        if (!hasC) return AxisOrder.XYZT;
        return AxisOrder.XYZCT; // hasZ && hasC && hasT
    }

    /**
     * Checks whether a volume's per-channel voxel count is within BVV's texture
     * capacity. BVV's {@code DefaultSimpleStackManager} computes the texture buffer
     * size as {@code width * height * depth * 2} using a 32-bit signed int; values
     * beyond ~1 billion voxels cause integer overflow and a fatal GL crash.
     *
     * @throws IllegalArgumentException if the volume exceeds ~1 Gvox/channel
     */
    static void checkVolumeSize(final long width, final long height, final long depth) {
        final long voxels = width * height * depth;
        if (voxels * 2L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(String.format(
                    "Volume too large for BVV's texture manager: %dx%dx%d = %.2f Gvox/channel " +
                            "(limit ~1.07 Gvox). For tiled datasets, open the native " +
                            "BDV/HDF5 or IMS source directly to use BVV's pyramid-aware cache.",
                    width, height, depth, voxels / 1e9));
        }
    }

    /**
     * Computes BVV camera parameters (depth, near clip, far clip) from raw
     * physical dimensions. BVV camera params are in units of screen pixels;
     * after initTransform the image is scaled so its largest XY dimension
     * fills the viewport width, so we derive depth extent in that space.
     *
     * @param sx          pixel width (calibrated)
     * @param sy          pixel height (calibrated)
     * @param sz          pixel depth (calibrated)
     * @param nZ          number of Z slices
     * @param maxXY       largest spatial dimension in pixels (max of width, height)
     * @param screenWidth viewport width in pixels; if &le; 0, defaults to 1024
     * @return double[] {dCam, dClipNear, dClipFar}
     */
    static double[] computeCamParams(final double sx, final double sy, final double sz,
                                     final long nZ, final long maxXY, final int screenWidth) {
        final double vpWidth = screenWidth > 0 ? screenWidth : DEFAULT_VIEWPORT_WIDTH;
        final double physZ = nZ * sz;
        final double scale = vpWidth / maxXY;
        final double zExtent = (physZ / ((sx + sy) / 2)) * scale;
        final double dCam = Math.max(DEFAULT_D_CAM, zExtent * CAM_DISTANCE_SCALE);
        final double dClip = Math.max(DEFAULT_NEAR_CLIP, zExtent * CLIP_DISTANCE_SCALE);
        SNTUtils.log(String.format("BVV camParams: physZ=%.1f zExtent=%.1f screen=%d → dCam=%.0f dClip=%.0f",
                physZ, zExtent, (int) vpWidth, dCam, dClip));
        return new double[]{dCam, dClip, dClip};
    }
}
