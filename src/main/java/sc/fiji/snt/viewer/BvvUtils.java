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
import bdv.viewer.Source;
import bvv.core.VolumeViewerPanel;
import bvv.core.util.MatrixMath;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;

import java.awt.Point;
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
     * Returns the world-space endpoints of the perspective ray through the
     * current mouse cursor position in the given viewer panel.
     *
     * <p>BVV uses the formula {@code pf = dCam / (dCam + viewerZ)} to project
     * world points onto the screen. Inverting that relationship gives the viewer-space
     * position for any (screenX, screenY, viewerZ) triple:
     * <pre>
     *   viewerX = centerX + (screenX - centerX) * (1 + viewerZ / dCam)
     *   viewerY = centerY + (screenY - centerY) * (1 + viewerZ / dCam)
     * </pre>
     * Evaluating at the near clip plane (viewerZ = -nearClip) and far clip plane
     * (viewerZ = +farClip) gives two viewer-space points; applying the inverse
     * viewer transform converts them to world space.
     *
     * @param vp       the BVV viewer panel
     * @param dCam     camera distance in screen-pixel units (from OverlayRenderer.dCam)
     * @param nearClip near clip distance in screen-pixel units (>0, <dCam)
     * @param farClip  far clip distance in screen-pixel units (>0)
     * @return a 2x3 array { nearWorld, farWorld } in world coordinates, or null
     * if the mouse is outside the display or the viewer state is unavailable
     */
    static double[][] findClickRay(final VolumeViewerPanel vp,
                                   final double dCam,
                                   final double nearClip,
                                   final double farClip) {
        if (vp == null || dCam <= 0) return null;

        // Mouse position in display coordinates.
        final Point mouse = vp.getDisplay().getComponent().getMousePosition();
        if (mouse == null) return null;

        final AffineTransform3D t = new AffineTransform3D();
        vp.state().getViewerTransform(t);

        // Screen center = perspective vanishing point.
        final double cx = vp.getDisplay().getWidth() / 2.0;
        final double cy = vp.getDisplay().getHeight() / 2.0;

        // Offset of the cursor from screen center.
        final double dx = mouse.x - cx;
        final double dy = mouse.y - cy;

        // Viewer-space X,Y scale factor at each clip plane:
        //   at near (viewerZ = -nearClip): scale = 1 - nearClip/dCam
        //   at far  (viewerZ = +farClip):  scale = 1 + farClip/dCam
        final double nearScale = 1.0 - nearClip / dCam;
        final double farScale = 1.0 + farClip / dCam;

        // Viewer-space points on the near and far clip planes.
        final double[] nearV = {cx + dx * nearScale, cy + dy * nearScale, -nearClip};
        final double[] farV = {cx + dx * farScale, cy + dy * farScale, farClip};

        // Convert from viewer space to world space.
        final double[] nearW = new double[3];
        final double[] farW = new double[3];
        t.applyInverse(nearW, nearV);
        t.applyInverse(farW, farV);

        return new double[][]{nearW, farW};
    }

    /**
     * Walks the world-space ray from nearW to farW and returns the world-space
     * position of the intensity maximum near the focal plane. Sub-voxel accuracy
     * is achieved via a 3-point parabola fit.
     *
     * <p>The search is restricted to a window of +/-{@code FOCAL_WINDOW} around
     * {@code focalT} (the parametric t in [0,1] where the focal plane intersects
     * the ray). This prevents returning a brighter-but-invisible structure behind
     * the one the user is visually clicking on. The window is clamped to [0,1].
     *
     * <p>The coarsest mip level that still produces at least {@code MIN_STEPS}
     * samples is used, so that all voxels are guaranteed to be in the cache rather
     * than returning fill-zeros for unloaded fine-resolution tiles.
     *
     * @param nearW     ray origin in world space (near clip)
     * @param farW      ray end in world space (far clip)
     * @param src       the volume source to sample
     * @param timePoint current time point index
     * @param focalT    parametric position of the focal plane along the ray,
     *                  in [0,1]; typically nearClip / (nearClip + farClip)
     * @return world-space position of the intensity maximum, or null if the ray
     *         misses the volume entirely (all samples in the window are zero)
     */
    /**
     * Returns the mip level BVV would choose for the focal plane, replicating
     * BVV's own MipmapSizes.bestLevel logic exactly.
     *
     * <p>BVV selects the level where one source voxel (projected perpendicular
     * to the view ray) matches one screen pixel. This method builds the same
     * {@code pvm = screenPerspective * viewerT * srcT_0} matrix BVV uses
     * internally, derives the pixel footprint in source space at the focal
     * plane depth, and picks the closest matching level.
     *
     * <p>Because this replicates BVV's own decision, the returned level's tiles
     * are the ones BVV is loading for rendering -- making them the safest choice
     * for CPU-side intensity sampling.
     *
     * @param vp        the viewer panel
     * @param src       the source being queried
     * @param timePoint current time point
     * @param dCam      camera distance (screen pixels)
     * @param nearClip  near clip distance (screen pixels)
     * @param farClip   far clip distance (screen pixels)
     * @return the best mip level index (0 = finest)
     */
    static int bestMipLevel(final VolumeViewerPanel vp,
                            final Source<?> src, final int timePoint,
                            final double dCam,
                            final double nearClip, final double farClip) {
        final int nLevels = src.getNumMipmapLevels();

        // Build pvm = screenPerspective(dCam, near, far, W, H, 0) * viewerT * srcT_0
        // -- the same matrix BVV passes to MipmapSizes.init().
        final AffineTransform3D viewerT = new AffineTransform3D();
        vp.state().getViewerTransform(viewerT);
        final AffineTransform3D srcT0 = new AffineTransform3D();
        src.getSourceTransform(timePoint, 0, srcT0);

        final int W = vp.getDisplay().getWidth();
        final int H = vp.getDisplay().getHeight();

        final Matrix4f pv = MatrixMath.screenPerspective(
                dCam, nearClip, farClip, W, H, 0, new Matrix4f())
                .mul(MatrixMath.affine(viewerT, new Matrix4f()));
        final Matrix4f pvm = new Matrix4f(pv).mul(MatrixMath.affine(srcT0, new Matrix4f()));
        final Matrix4f NDCtoSrc = pvm.invert(new Matrix4f());

        // Pixel width (in source / level-0 voxel units) at near and far NDC planes.
        final float pixW = 2f / W;
        final Vector3f pNear = NDCtoSrc.transformProject(0, 0, -1, new Vector3f());
        final Vector3f pFar  = NDCtoSrc.transformProject(0, 0,  1, new Vector3f());
        final float sn = NDCtoSrc.transformProject(pixW, 0, -1, new Vector3f()).sub(pNear).length();
        final float sf = NDCtoSrc.transformProject(pixW, 0,  1, new Vector3f()).sub(pFar).length();

        // Ray direction in source space -- used to project voxel axes perpendicular to ray.
        final Vector3f dir = pFar.sub(pNear, new Vector3f()).normalize();
        final float v0x = (float) Math.sqrt(Math.max(0.0, 1.0 - dir.dot(1, 0, 0)));
        final float v0y = (float) Math.sqrt(Math.max(0.0, 1.0 - dir.dot(0, 1, 0)));
        final float v0z = (float) Math.sqrt(Math.max(0.0, 1.0 - dir.dot(0, 0, 1)));

        // Voxel footprint at each level in source (level-0) coordinates.
        // Downsampling factors r[] are derived from the ratio of column magnitudes
        // between level lv and level 0 source transforms.
        final double[] col0 = colMagnitudes(srcT0);
        final float[] sls = new float[nLevels];
        for (int lv = 0; lv < nLevels; lv++) {
            final AffineTransform3D srcTlv = new AffineTransform3D();
            src.getSourceTransform(timePoint, lv, srcTlv);
            final double[] colLv = colMagnitudes(srcTlv);
            final int rx = Math.max(1, (int) Math.round(colLv[0] / col0[0]));
            final int ry = Math.max(1, (int) Math.round(colLv[1] / col0[1]));
            final int rz = Math.max(1, (int) Math.round(colLv[2] / col0[2]));
            sls[lv] = Math.max(rx * v0x, Math.max(ry * v0y, rz * v0z));
        }

        // Pixel footprint in source space at the focal plane (t = nearClip / (near+far)).
        final float focalT = (float) (nearClip / (nearClip + farClip));
        final float sd = focalT * sf + (1 - focalT) * sn;

        // Pick the level where sd is closest to sls[l] -- direct port of MipmapSizes.bestLevel.
        for (int l = 0; l < sls.length; l++) {
            if (sd <= sls[l]) {
                if (l == 0) return 0;
                return (sls[l] - sd < sd - sls[l - 1]) ? l : (l - 1);
            }
        }
        return nLevels - 1;
    }

    /**
     * Returns the interpolated intensity of {@code src} at {@code worldPt},
     * used to compare peaks found across multiple channels.
     */
    static double peakValue(final double[] worldPt,
                            final Source<?> src, final int timePoint, final int level) {
        final RandomAccessibleInterval<?> rai = src.getSource(timePoint, level);
        if (rai == null) return 0;
        final AffineTransform3D srcT = new AffineTransform3D();
        src.getSourceTransform(timePoint, level, srcT);
        final double[] voxPt = new double[3];
        srcT.applyInverse(voxPt, worldPt);
        return sampleAt(rai, voxPt);
    }

    @SuppressWarnings("unchecked")
    private static <T extends RealType<T>> double sampleAt(final RandomAccessibleInterval<?> raiRaw, final double[] voxPt) {
        final RandomAccessibleInterval<T> rai = (RandomAccessibleInterval<T>) raiRaw;
        final net.imglib2.RealRandomAccess<T> ra = Views.interpolate(Views.extendZero(rai),
                new NLinearInterpolatorFactory<T>()).realRandomAccess();
        ra.setPosition(voxPt);
        return ra.get().getRealDouble();
    }

    /** Column magnitudes of the 3x3 linear part of an AffineTransform3D. */
    private static double[] colMagnitudes(final AffineTransform3D t) {
        final double[] mag = new double[3];
        for (int c = 0; c < 3; c++) {
            double sum = 0;
            for (int r = 0; r < 3; r++) { final double v = t.get(r, c); sum += v * v; }
            mag[c] = Math.sqrt(sum);
        }
        return mag;
    }

    static double[] rayMaxima(final double[] nearW, final double[] farW,
                              final Source<?> src, final int timePoint,
                              final double focalT,
                              final int level) {
        if (nearW == null || farW == null || src == null) return null;

        final double dx = farW[0] - nearW[0];
        final double dy = farW[1] - nearW[1];
        final double dz = farW[2] - nearW[2];
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return null;

        final AffineTransform3D srcT = new AffineTransform3D();
        src.getSourceTransform(timePoint, level, srcT);

        double minVoxel = Double.MAX_VALUE;
        for (int c = 0; c < 3; c++) {
            double colLen = 0;
            for (int r = 0; r < 3; r++) { final double v = srcT.get(r, c); colLen += v * v; }
            final double d = Math.sqrt(colLen);
            if (d > 0 && d < minVoxel) minVoxel = d;
        }
        if (minVoxel == Double.MAX_VALUE || minVoxel <= 0) return null;

        // Step size: 0.5 voxels at chosen level, capped to at most len/2000.
        final double step = Math.max(minVoxel * 0.5, len / 2000.0);
        final int nSteps = (int) Math.ceil(len / step);

        // Search window: +/- FOCAL_WINDOW of the ray around focalT.
        final double FOCAL_WINDOW = 0.35;
        final int i0 = Math.max(0,       (int) ((focalT - FOCAL_WINDOW) * nSteps));
        final int i1 = Math.min(nSteps,  (int) Math.ceil((focalT + FOCAL_WINDOW) * nSteps));

        final RandomAccessibleInterval<?> rai = src.getSource(timePoint, level);
        if (rai == null) return null;

        return sampleRay(nearW, farW, rai, srcT, dx, dy, dz, len, step, nSteps, i0, i1);
    }

    /**
     * Typed inner worker for rayMaxima. The unchecked cast is safe because any
     * imglib2 source pixel type is a RealType at runtime; T is captured here so
     * that Views. Interpolate and NLinearInterpolatorFactory unify without raw types.
     */
    @SuppressWarnings("unchecked")
    private static <T extends RealType<T>> double[] sampleRay(
            final double[] nearW, final double[] farW,
            final RandomAccessibleInterval<?> raiRaw,
            final AffineTransform3D srcT,
            final double dx, final double dy, final double dz,
            final double len, final double step, final int nSteps,
            final int i0, final int i1) {

        final RandomAccessibleInterval<T> rai = (RandomAccessibleInterval<T>) raiRaw;
        final net.imglib2.RealRandomAccess<T> ra =
                Views.interpolate(
                        Views.extendZero(rai),
                        new NLinearInterpolatorFactory<T>()
                ).realRandomAccess();

        final double[] worldPt = new double[3];
        final double[] voxPt = new double[3];

        double maxVal = 0;
        int maxIdx = -1;

        for (int i = i0; i < i1; i++) {
            final double t = (i * step) / len;
            worldPt[0] = nearW[0] + t * dx;
            worldPt[1] = nearW[1] + t * dy;
            worldPt[2] = nearW[2] + t * dz;
            srcT.applyInverse(voxPt, worldPt);
            ra.setPosition(voxPt);
            final double val = ra.get().getRealDouble();
            if (val > maxVal) {
                maxVal = val;
                maxIdx = i;
            }
        }

        if (maxIdx < 0 || maxVal == 0) return null;

        // 3-point parabola refinement for sub-voxel accuracy (if not at endpoints).
        double refinedT;
        if (maxIdx > 0 && maxIdx < nSteps - 1) {
            final double tPrev = ((maxIdx - 1) * step) / len;
            final double tNext = ((maxIdx + 1) * step) / len;
            final double[] wPrev = {
                    nearW[0] + tPrev * dx, nearW[1] + tPrev * dy, nearW[2] + tPrev * dz};
            final double[] wNext = {
                    nearW[0] + tNext * dx, nearW[1] + tNext * dy, nearW[2] + tNext * dz};
            srcT.applyInverse(voxPt, wPrev);
            ra.setPosition(voxPt);
            final double vPrev = ra.get().getRealDouble();
            srcT.applyInverse(voxPt, wNext);
            ra.setPosition(voxPt);
            final double vNext = ra.get().getRealDouble();
            // Parabola vertex: offset = (vPrev - vNext) / (2*(vPrev - 2*vPeak + vNext))
            final double denom = vPrev - 2 * maxVal + vNext;
            final double tPeak = (maxIdx * step) / len;
            if (denom < 0) {
                final double offset = (vPrev - vNext) / (2 * denom);
                // offset is in units of one step; convert to [0,1] ray fraction.
                refinedT = tPeak + offset * (step / len);
                refinedT = Math.max(0, Math.min(1, refinedT));
            } else {
                refinedT = tPeak;
            }
        } else {
            refinedT = (maxIdx * step) / len;
        }

        return new double[]{
                nearW[0] + refinedT * dx,
                nearW[1] + refinedT * dy,
                nearW[2] + refinedT * dz
        };
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
