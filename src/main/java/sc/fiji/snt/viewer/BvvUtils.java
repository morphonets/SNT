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
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.VolatileView;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bvv.core.VolumeViewerPanel;
import bvv.core.blocks.TileAccess;
import bvv.core.multires.SourceStacks;
import bvv.core.util.MatrixMath;
import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imglib2.cache.img.optional.CacheOptions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.ImgUtils;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Package-private utility methods shared across BVV-related classes
 * ({@link Bvv}, {@link ChannelUnmixingCard}, etc.).
 */
public final class BvvUtils {

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
    /** BVV render quality presets (offscreen render size in px, and max. render time per frame in ms) */
    public enum RenderQuality {
        LOW("Low (256x256, 15ms)", 256, 15, "Lowest quality, fastest rendering. Best for slow GPUs"),
        MEDIUM("Medium (512x512, 30ms)", 512, 30, "Balanced quality and performance (default)"),
        HIGH("High (768x768, 60ms)", 768, 60, "Higher quality. Recommended for analysis and screenshots"),
        MAX("Max (1024x1024, 100ms)", 1024, 100, "Maximum quality, slowest rendering. For high-end GPUs");

        public final String label;
        public final int size;
        public final int millis;
        public final String description;

        RenderQuality(final String label, final int size, final int millis, final String description) {
            this.label = label;
            this.size = size;
            this.millis = millis;
            this.description = description;
        }

        public static RenderQuality fromLabel(final String label) {
            for (final RenderQuality q : values()) if (q.label.equals(label)) return q;
            return MEDIUM;
        }

        public static String[] labels() {
            final String[] l = new String[values().length];
            for (int i = 0; i < l.length; i++) l[i] = values()[i].label;
            return l;
        }
    }

    private static final String QUALITY_PREF_KEY = "snt.bvv.renderQuality";

    /** @return the persisted render quality preset (applies the next time a BVV window is opened) */
    public static RenderQuality getRenderQuality() {
        try {
            return RenderQuality.valueOf(ij.Prefs.get(QUALITY_PREF_KEY, RenderQuality.MEDIUM.name()));
        } catch (final IllegalArgumentException ignored) {
            return RenderQuality.MEDIUM;
        }
    }

    public static void setRenderQuality(final RenderQuality quality) {
        ij.Prefs.set(QUALITY_PREF_KEY, (quality == null ? RenderQuality.MEDIUM : quality).name());
    }

    /** Hard ceiling (MB) for the GPU tile cache */
    public static final int MAX_CACHE_SIZE_MB = 2048;
    /** Rough GPU tile budget (MB) a streamed pyramid needs per channel for a full-screen view without thrashing */
    public static final int CACHE_MB_PER_CHANNEL = 1024;
    private static final String CACHE_PREF_KEY = "snt.bvv.cacheSizeMB";
    private static final String CACHE_NO_ASK_KEY = "snt.bvv.cacheNoAsk";
    private static volatile int sessionCacheMB = 0;

    /** @return the user's GPU tile cache preference (MB). 0 means automatic */
    public static int getCachePrefMB() {
        try {
            return Math.max(0, Integer.parseInt(ij.Prefs.get(CACHE_PREF_KEY, "0").trim()));
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }

    /** Persists the GPU tile cache preference (MB). 0 means automatic. Applies next time a BVV window is opened */
    public static void setCachePrefMB(final int mb) {
        ij.Prefs.set(CACHE_PREF_KEY, String.valueOf(Math.max(0, mb)));
    }

    /** Overrides the preference for the current session only (0 clears the override) */
    public static void setSessionCacheMB(final int mb) {
        sessionCacheMB = Math.max(0, mb);
    }

    public static boolean isCachePromptSuppressed() {
        return "true".equals(ij.Prefs.get(CACHE_NO_ASK_KEY, "false"));
    }

    public static void setCachePromptSuppressed(final boolean suppress) {
        ij.Prefs.set(CACHE_NO_ASK_KEY, String.valueOf(suppress));
    }

    /** @return the largest GPU tile cache (MB) we allow: {@link #MAX_CACHE_SIZE_MB} or 25% of the JVM max heap */
    public static int maxCacheMB() {
        return (int) Math.min(MAX_CACHE_SIZE_MB,
                Math.max(DEFAULT_CACHE_SIZE_MB, Runtime.getRuntime().maxMemory() / (4L * 1024 * 1024)));
    }

    /** @return the automatic GPU tile cache size (MB) for a streamed pyramid with {@code nChannels} channels */
    public static int recommendedCacheMB(final int nChannels) {
        return (int) Math.max(DEFAULT_CACHE_SIZE_MB,
                Math.min(maxCacheMB(), (long) Math.max(1, nChannels) * CACHE_MB_PER_CHANNEL));
    }

    /**
     * @param nChannels number of channels to be displayed
     * @param streaming whether the data is a streamed pyramid (N5/Zarr/IMS). Plain images ignore the cache
     * @return the GPU tile cache size (MB) to use: session override, else user preference, else automatic
     */
    public static int effectiveCacheMB(final int nChannels, final boolean streaming) {
        final int max = maxCacheMB();
        if (sessionCacheMB > 0) return Math.min(sessionCacheMB, max);
        final int pref = getCachePrefMB();
        if (pref > 0) return Math.min(pref, max);
        return streaming ? recommendedCacheMB(nChannels) : DEFAULT_CACHE_SIZE_MB;
    }

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
     * Loads a Groovy recipe template from the
     * {@code script_templates/Neuroanatomy/Recipes/} resource directory.
     *
     * @param scriptName the template file name (e.g. {@code "ChannelUnmixing.groovy"})
     * @return the script contents, or a comment describing the error on failure
     */
    static String loadRecipeScript(final String scriptName) {
        try {
            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
            final InputStream is = cl.getResourceAsStream(
                    "script_templates/Neuroanatomy/Recipes/" + scriptName);
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
     * Returns the world-space endpoints of the perspective ray through an arbitrary screen
     * point in the given viewer panel.
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
     * @param screenX  screen-space X of the point the ray passes through
     * @param screenY  screen-space Y of the point the ray passes through
     * @return a 2x3 array { nearWorld, farWorld } in world coordinates, or null
     * if the viewer state is unavailable
     */
    private static double[][] rayFromScreenPoint(final VolumeViewerPanel vp,
                                   final double dCam,
                                   final double nearClip,
                                   final double farClip,
                                   final double screenX,
                                   final double screenY) {
        if (vp == null || dCam <= 0) return null;

        final AffineTransform3D t = new AffineTransform3D();
        vp.state().getViewerTransform(t);

        // Screen center = perspective vanishing point.
        final double cx = vp.getDisplay().getWidth() / 2.0;
        final double cy = vp.getDisplay().getHeight() / 2.0;

        // Offset of the requested point from screen center.
        final double dx = screenX - cx;
        final double dy = screenY - cy;

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

        if (SNTUtils.isDebugMode()) {
            SNTUtils.log(String.format(
                    "BVV ray: screen=(%.1f,%.1f) center=(%.1f,%.1f) dCam=%.1f near=%.1f far=%.1f%n"
                            + "  nearW=(%.3f,%.3f,%.3f) farW=(%.3f,%.3f,%.3f)",
                    screenX, screenY, cx, cy, dCam, nearClip, farClip,
                    nearW[0], nearW[1], nearW[2], farW[0], farW[1], farW[2]));
        }

        return new double[][]{nearW, farW};
    }

    /**
     * Returns the world-space endpoints of the perspective ray through the
     * current mouse cursor position in the given viewer panel.
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
        if (vp == null) return null;
        final Point mouse = vp.getDisplay().getComponent().getMousePosition();
        if (mouse == null) return null;
        return rayFromScreenPoint(vp, dCam, nearClip, farClip, mouse.x, mouse.y);
    }

    /**
     * Returns the world-space endpoints of the perspective ray through the center of the
     * viewer panel's display, i.e. its optical axis. Unlike {@link #findClickRay}, this
     * does not depend on the mouse cursor and is defined even when the pointer is outside
     * the display (or the viewer has no focus).
     *
     * @param vp       the BVV viewer panel
     * @param dCam     camera distance in screen-pixel units (from OverlayRenderer.dCam)
     * @param nearClip near clip distance in screen-pixel units (>0, <dCam)
     * @param farClip  far clip distance in screen-pixel units (>0)
     * @return a 2x3 array { nearWorld, farWorld } in world coordinates, or null
     * if the viewer state is unavailable
     */
    static double[][] findCenterRay(final VolumeViewerPanel vp,
                                   final double dCam,
                                   final double nearClip,
                                   final double farClip) {
        if (vp == null) return null;
        final double cx = vp.getDisplay().getWidth() / 2.0;
        final double cy = vp.getDisplay().getHeight() / 2.0;
        return rayFromScreenPoint(vp, dCam, nearClip, farClip, cx, cy);
    }

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
     * Returns the interpolated intensity of {@code src} at {@code worldPt}, normalized to [0,1] via
     * {@code (raw - displayMin) / (displayMax - displayMin)}.
     *
     * <p>Normalization matters when comparing peaks across multiple channels/sources (see
     * {@code Bvv#searchPeakAcrossSources}): raw pixel values are not comparable across sources with different bit
     * depths or contrast settings, so comparing raw intensities would bias the result toward whichever source has the
     * largest raw values rather than whichever is actually the brightest/most visible one on screen
     *
     * @param displayMin display-range minimum for {@code src} (e.g. from its {@code ConverterSetup})
     * @param displayMax display-range maximum for {@code src}
     */
    static double peakValue(final double[] worldPt, final Source<?> src, final int timePoint,
                            final int level, final double displayMin, final double displayMax) {
        final RandomAccessibleInterval<?> rai = src.getSource(timePoint, level);
        if (rai == null) return 0;
        final AffineTransform3D srcT = new AffineTransform3D();
        src.getSourceTransform(timePoint, level, srcT);
        final double[] voxPt = new double[3];
        srcT.applyInverse(voxPt, worldPt);
        final double raw = sampleAt(rai, voxPt);
        final double range = displayMax - displayMin;
        return (range > 0) ? (raw - displayMin) / range : raw;
    }

    @SuppressWarnings("unchecked")
    private static <T extends RealType<T>> double sampleAt(final RandomAccessibleInterval<?> raiRaw, final double[] voxPt) {
        final RandomAccessibleInterval<T> rai = (RandomAccessibleInterval<T>) raiRaw;
        final net.imglib2.RealRandomAccess<T> ra = Views.interpolate(Views.extendZero(rai),
                new NLinearInterpolatorFactory<>()).realRandomAccess();
        ra.setPosition(voxPt);
        return ra.get().getRealDouble();
    }

    /** Samples {@code ra} at ray-parameter index {@code i}, reusing the given scratch buffers */
    private static <T extends RealType<T>> double sampleRayValue(
            final double[] nearW, final double dx, final double dy, final double dz,
            final double len, final double step, final AffineTransform3D srcT,
            final net.imglib2.RealRandomAccess<T> ra, final double[] worldPt, final double[] voxPt,
            final int i) {
        final double t = (i * step) / len;
        worldPt[0] = nearW[0] + t * dx;
        worldPt[1] = nearW[1] + t * dy;
        worldPt[2] = nearW[2] + t * dz;
        srcT.applyInverse(voxPt, worldPt);
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

    /**
     * Physical search radius (in voxels of the sampled level) around the focal point within which
     * {@link #rayMaxima} looks for the intensity maximum. Deliberately a fixed physical quantity
     * rather than a fraction of the ray's total length: the ray's endpoints come from the near/far
     * clip planes, which are fixed *viewer-space* (screen-pixel) distances that get inverse-transformed
     * through the *current* (zoom-dependent) viewer transform. A fraction of the ray's length
     * would correspond to a physical search window that balloons at low zoom (small transform scale)
     * and shrinks at high zoom. A fixed physical radius keeps the search tightly and consistently localized
     * around the clicked point regardless of zoom and is independent of the near/far clip values themselves,
     * which users can adjust via the slab-clip sliders.
     */
    static final double RAY_SEARCH_RADIUS_VOXELS = 15;
    /**
     * Wider fallback radius (voxels), tried by {@code Bvv#findClickRayMaxima()} only when the tight
     * {@link #RAY_SEARCH_RADIUS_VOXELS} window finds no peak at all (e.g. click landed slightly off
     * a thin/dim structure). Wide enough to forgive modest click imprecision, still tight enough to
     * stay tethered to the clicked point.
     */
    static final double RAY_SEARCH_RADIUS_VOXELS_WIDE = 60;

    /**
     * Sentinel passed to {@link #rayMaxima} to search the ray's *entire* near-to-far extent, bypassing the focal-plane
     * window entirely. Tried by {@code Bvv#findClickRayMaxima()} only when both  {@link #RAY_SEARCH_RADIUS_VOXELS} and
     * {@link #RAY_SEARCH_RADIUS_VOXELS_WIDE} find nothing, which happens whenever the visible structure sits far (in
     * ray-depth) from wherever the view's current focal plane happens to be, e.g. right after opening a dataset or
     * navigating without having scrolled the depth to match what's on screen. In that situation tethering to the focal
     * plane is actively wrong: the visible structure the user clicked on is somewhere else along the ray
     */
    static final double RAY_SEARCH_RADIUS_VOXELS_FULL = Double.POSITIVE_INFINITY;

    /**
     * Walks the world-space ray from nearW to farW and returns the world-space
     * position of the intensity maximum near the focal plane. Sub-voxel accuracy
     * is achieved via a 3-point parabola fit.
     *
     * <p>The search is restricted to a fixed physical radius ({@code searchRadiusVoxels}, see
     * {@link #RAY_SEARCH_RADIUS_VOXELS}/{@link #RAY_SEARCH_RADIUS_VOXELS_WIDE}) around {@code focalT}
     * (the parametric t in [0,1] where the focal plane intersects the ray). This prevents returning a
     * brighter-but-invisible structure behind the one the user is visually clicking on, without the
     * window's physical size depending on the current zoom level or on the near/far clip (slab)
     * settings. The window is clamped to the ray's own extent.
     *
     * <p>The coarsest mip level that still produces at least {@code MIN_STEPS}
     * samples is used, so that all voxels are guaranteed to be in the cache rather
     * than returning fill-zeros for unloaded fine-resolution tiles.
     *
     * @param nearW            ray origin in world space (near clip)
     * @param farW             ray end in world space (far clip)
     * @param src              the volume source to sample
     * @param timePoint        current time point index
     * @param focalT           parametric position of the focal plane along the ray,
     *                         in [0,1]; typically nearClip / (nearClip + farClip)
     * @param searchRadiusVoxels physical search radius, in voxels of the sampled level, around the
     *                           focal point (see {@link #RAY_SEARCH_RADIUS_VOXELS}), or
     *                           {@link #RAY_SEARCH_RADIUS_VOXELS_FULL} to search the whole ray
     * @return world-space position of the intensity maximum, or null if the ray
     *         misses the volume entirely (all samples in the window are zero)
     */
    static double[] rayMaxima(final double[] nearW, final double[] farW,
                              final Source<?> src, final int timePoint,
                              final double focalT,
                              final int level,
                              final double searchRadiusVoxels) {
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

        // Search window: fixed physical radius around the focal index, converted to a step count
        // at the current level/zoom rather than a fraction of the (zoom-dependent) ray length.
        // RAY_SEARCH_RADIUS_VOXELS_FULL (infinite) bypasses this entirely and covers [0,nSteps]
        final int focalIdx = Math.max(0, Math.min(nSteps, (int) Math.round(focalT * nSteps)));
        final int windowSteps;
        if (Double.isInfinite(searchRadiusVoxels)) {
            windowSteps = nSteps;
        } else {
            final double searchRadiusWorld = searchRadiusVoxels * minVoxel;
            windowSteps = Math.max(1, (int) Math.ceil(searchRadiusWorld / step));
        }
        final int i0 = Math.max(0,      focalIdx - windowSteps);
        final int i1 = Math.min(nSteps, focalIdx + windowSteps);

        final RandomAccessibleInterval<?> rai = src.getSource(timePoint, level);
        if (rai == null) return null;

        if (SNTUtils.isDebugMode()) {
            SNTUtils.log(String.format(
                    "BVV rayMaxima['%s']: level=%d len=%.3f minVoxel=%.4f step=%.4f nSteps=%d%n"
                            + "  focalT=%.4f focalIdx=%d radiusVox=%.1f window=[%d,%d)",
                    src.getName(), level, len, minVoxel, step, nSteps,
                    focalT, focalIdx, searchRadiusVoxels, i0, i1));
        }

        return sampleRay(nearW, farW, rai, srcT, dx, dy, dz, len, step, nSteps, i0, i1, src.getName());
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
            final int i0, final int i1, final String debugName) {

        final RandomAccessibleInterval<T> rai = (RandomAccessibleInterval<T>) raiRaw;
        final net.imglib2.RealRandomAccess<T> ra =
                Views.interpolate(
                        Views.extendZero(rai),
                        new NLinearInterpolatorFactory<T>()
                ).realRandomAccess();

        final double[] worldPt = new double[3];
        final double[] voxPt = new double[3];

        // Buffered so a tied-maximum run (see below) can be detected after the fact,
        // without re-sampling the ray a second time
        final double[] vals = new double[i1 - i0];
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
            vals[i - i0] = val;
            if (val > maxVal) {
                maxVal = val;
                maxIdx = i;
            }
        }

        if (maxIdx < 0 || maxVal == 0) return null;

        // A saturated or otherwise flat-topped structure produces a contiguous run of samples tied at
        // maxVal, not a single sample. The scan above keeps whichever one it meets *first*, which is the
        // leading edge of that run in ray-order. Since successive clicks sample the ray at a slightly
        // different phase against the voxel grid, the leading edge shifts unpredictably from click to
        // click, making an otherwise straight/saturated structure zig-zag
        final double plateauEps = maxVal * 1e-6; // tolerate float/interpolation noise, not real dips
        int runStart = maxIdx, runEnd = maxIdx;
        while (runStart > i0 && vals[runStart - 1 - i0] >= maxVal - plateauEps) runStart--;
        while (runEnd < i1 - 1 && vals[runEnd + 1 - i0] >= maxVal - plateauEps) runEnd++;
        final int runStartInWindow = runStart, runEndInWindow = runEnd;

        // A structure whose extent along the ray exceeds the search window (e.g. a large/saturated soma vs. the tight
        // RAY_SEARCH_RADIUS_VOXELS) gets its plateau cut off by i0/i1 rather than  by the actual data. Taking the
        // midpoint of that truncated run biases the result toward  whichever side the window happened to cut off;
        // continue outward past the window, still following the same contiguous plateau, until the real edges are found
        // (or the ray ends)
        while (runStart > 0 && sampleRayValue(nearW, dx, dy, dz, len, step, srcT, ra, worldPt, voxPt, runStart - 1) >= maxVal - plateauEps)
            runStart--;
        while (runEnd < nSteps - 1 && sampleRayValue(nearW, dx, dy, dz, len, step, srcT, ra, worldPt, voxPt, runEnd + 1) >= maxVal - plateauEps)
            runEnd++;
        final int anchorIdx = (runStart + runEnd) / 2;

        if (SNTUtils.isDebugMode()) {
            SNTUtils.log(String.format(
                    "BVV rayMaxima['%s']: maxIdx=%d maxVal=%.3f%n runInWindow=[%d,%d] runExtended=[%d,%d]%s anchorIdx=%d",
                    debugName, maxIdx, maxVal, runStartInWindow, runEndInWindow, runStart, runEnd,
                    (runStart != runStartInWindow || runEnd != runEndInWindow) ? " [plateau extended past search window]" : "",
                    anchorIdx));
        }

        // 3-point parabola refinement for sub-voxel accuracy (if not at endpoints). Harmless on a
        // genuine plateau: the immediate neighbors of its midpoint are themselves part of the flat
        // run, so denom ~ 0 and tPeak remains unrefined, same as at the endpoints
        double refinedT;
        if (anchorIdx > 0 && anchorIdx < nSteps - 1) {
            final double tPrev = ((anchorIdx - 1) * step) / len;
            final double tNext = ((anchorIdx + 1) * step) / len;
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
            final double tPeak = (anchorIdx * step) / len;
            if (denom < 0) {
                final double offset = (vPrev - vNext) / (2 * denom);
                // offset is in units of one step; convert to [0,1] ray fraction.
                refinedT = tPeak + offset * (step / len);
                refinedT = Math.clamp(refinedT, 0, 1);
            } else {
                refinedT = tPeak;
            }
        } else {
            refinedT = (anchorIdx * step) / len;
        }

        final double[] result = {
                nearW[0] + refinedT * dx,
                nearW[1] + refinedT * dy,
                nearW[2] + refinedT * dz
        };
        if (SNTUtils.isDebugMode()) {
            SNTUtils.log(String.format("BVV rayMaxima['%s']: refinedT=%.5f result=(%.3f,%.3f,%.3f)",
                    debugName, refinedT, result[0], result[1], result[2]));
        }
        return result;
    }

    /**
     * Wraps a single-resolution {@link Source} in a synthetic mipmap pyramid. BVV's {@code VolumeRenderer} requires
     * multiple resolution levels to pick a LOD; without one it throws on every repaint. Use this for non-pyramidal
     * N5/Zarr sources that cannot be re-exported with a real pyramid.
     * <p>
     * Level 0 is either a local, cell-based copy of the source (see {@link ImgUtils#materializeTiled}), or, if
     * {@code copyLocally} is false, the source itself (as a zero-min view: a non-zero min of the source is moved into the
     * returned source's transform, so its position in world space is unchanged). Each extra level doubles the previous step size along X/Y/Z,
     * matching how a real N5/Zarr multiscale pyramid is laid out. Extra levels are averaged (2x2x2) from the previous
     * level (see {@link #averagedLevel}) and computed lazily, on demand, so they cost no heap up front
     *
     * @param source      the single-level source to wrap
     * @param nLevels     number of extra downsampled levels to synthesize
     * @param copyLocally whether to copy level 0 into memory. Worth it for small remote sources (avoids repeated
     *                    network reads), but needs the full volume in heap (see {@link #estimateLocalCopyBytes})
     * @param <T>         pixel type
     * @return a multi-resolution {@link Source} wrapping {@code source}, or {@code source} unchanged if it already has
     * more than one level
     */
    @SuppressWarnings("unchecked")
    public static <T extends NumericType<T> & NativeType<T>> Source<T> synthesizeMipmapPyramid(
            final Source<T> source, final int nLevels, final boolean copyLocally) {
        if (source.getNumMipmapLevels() > 1) return source;
        SNTUtils.log("BVV: synthesizing " + nLevels + " mipmap level(s) for '" + source.getName() + "'"
                + (copyLocally ? " (copying level 0 locally; this may take a while for a remote source)"
                : " (lazy, no local copy)"));
        final RandomAccessibleInterval<T>[] levels = new RandomAccessibleInterval[nLevels + 1];
        final double[][] scales = new double[nLevels + 1][3];
        final RandomAccessibleInterval<T> base = source.getSource(0, 0);
        // Every level is zero-min, as the coarser ones (see #averagedLevel) are: a level 0 that kept a non-zero min
        // would be misregistered with them. The offset is compensated in the source transform below
        levels[0] = copyLocally ? ImgUtils.materializeTiled(base, PYRAMID_CELL_SIZE) : Views.zeroMin(base);
        scales[0] = new double[]{1, 1, 1};
        RandomAccessibleInterval<T> previous = levels[0];
        for (int l = 1; l <= nLevels; l++) {
            final long step = 1L << l; // 2, 4, 8...
            levels[l] = averagedLevel(previous);
            previous = levels[l];
            scales[l] = new double[]{step, step, step};
        }
        final AffineTransform3D transform = new AffineTransform3D();
        source.getSourceTransform(0, 0, transform);
        if (base.min(0) != 0 || base.min(1) != 0 || base.min(2) != 0) {
            // voxel x of the zero-min level 0 is voxel x + min of the source
            transform.concatenate(new net.imglib2.realtransform.Translation3D(base.min(0), base.min(1), base.min(2)));
        }
        SNTUtils.log("BVV: synthetic pyramid ready for '" + source.getName() + "'");
        return new RandomAccessibleIntervalMipmapSource<>(levels, source.getType(),
                scales, source.getVoxelDimensions(), transform, source.getName());
    }

    /**
     * Estimates the heap needed to copy level 0 of {@code source} into memory (see
     * {@link #synthesizeMipmapPyramid})
     *
     * @param source the source to estimate
     * @return the estimated size in bytes
     */
    public static long estimateLocalCopyBytes(final Source<?> source) {
        final RandomAccessibleInterval<?> rai = source.getSource(0, 0);
        long voxels = 1;
        for (int d = 0; d < rai.numDimensions(); d++) voxels *= rai.dimension(d);
        final Object type = source.getType();
        final long bytes = (type instanceof RealType<?> rt) ? Math.max(1, rt.getBitsPerPixel() / 8) : 4;
        return voxels * bytes;
    }

    /**
     * Largest per-channel voxel count BVV's single-texture path can handle: its buffer is sized as
     * {@code voxels * 2} with a 32-bit signed int. Independent of {@code GL_MAX_3D_TEXTURE_SIZE}
     */
    public static final long MAX_SINGLE_TEXTURE_VOXELS = Integer.MAX_VALUE / 2L;

    /** Edge length (voxels) of the tiles BVV uploads to the GPU cache (padded by +2 on each axis internally) */
    public static final int GPU_BLOCK_SIZE = 32;

    /**
     * Edge length (voxels) of the cells BVV streams from disk. Also the chunk size of exported OME-Zarr
     * arrays, so that loading a cell reads exactly one chunk. Must be a multiple of {@link #GPU_BLOCK_SIZE}.
     */
    public static final int CELL_SIZE = 64;
    static {
        assert CELL_SIZE % GPU_BLOCK_SIZE == 0 : "CELL_SIZE must be a multiple of GPU_BLOCK_SIZE";
    }

    /** Source chunks smaller than this (per axis) are read in whole multiples: cells are {@link #CELL_SIZE} then */
    private static final int MIN_CELL_SIZE = 32;

    /** Cells never exceed this edge length (voxels), whatever the source chunk size */
    private static final int MAX_CELL_SIZE = 128;

    /** Edge length of the cells streamed for a source with chunks of {@code chunk} voxels along an axis */
    static int cellSizeFor(final int chunk) {
        if (chunk < MIN_CELL_SIZE) return CELL_SIZE;
        return Math.min(chunk, MAX_CELL_SIZE);
    }

    /**
     * Logs how the cells streamed to BVV relate to the source chunks and, if the chunk layout is poorly suited to
     * interactive 3D viewing, queues a notice for the notification center. Does nothing if the chunk shape is unknown
     *
     * @param name       the display name of the data
     * @param chunkShape the chunk shape of the full-resolution array in N5 axis order (x, y, z, ...), or null
     */
    public static void reportChunking(final String name, final int[] chunkShape) {
        if (chunkShape == null || chunkShape.length < 3) return;
        final StringBuilder cells = new StringBuilder();
        boolean bad = false;
        for (int d = 0; d < 3; d++) {
            cells.append(d > 0 ? "x" : "").append(cellSizeFor(chunkShape[d]));
            bad |= chunkShape[d] < 8 || chunkShape[d] > 256;
        }
        final String chunks = String.join("x", java.util.stream.IntStream.of(chunkShape).limit(3)
                .mapToObj(String::valueOf).toArray(String[]::new));
        SNTUtils.log(String.format("BVV: '%s' source chunks %s -> streamed cells %s", name, chunks, cells));
        if (bad) {
            GuiUtils.Notices.queueNotice("<HTML><b>Unfavorable chunk layout (" + chunks + ").</b><br>"
                    + "'" + name + "' may feel sluggish in BVV. Re-saving it with ~" + CELL_SIZE + "^3 chunks "
                    + "(e.g., via the CONVERT option) would improve navigation.", null, null,
                    GuiUtils.Notices.PendingNotice.WARN);
        }
    }

    /**
     * Whether a level of {@code nVoxels} voxels is held in memory with strong references (so its cells are never
     * evicted and reloaded while navigating, which produced visible flicker). Levels that fit a fraction of the heap
     * are pinned, larger ones use soft references
     */
    private static boolean pinInMemory(final long nVoxels) {
        // 2 bytes/voxel assumed
        return nVoxels <= CellBackedSource.PIN_MAX_VOXELS || nVoxels * 2L <= Runtime.getRuntime().maxMemory() / 6;
    }

    /**
     * Creates the next, 2x coarser level of a pyramid by averaging 2x2x2 neighborhoods of {@code previous}, computed
     * lazily cell by cell. A coarse voxel then sits half a step into its block, which is the convention of BDV's
     * default mipmap transforms ({@link bdv.util.MipmapTransforms#getMipmapTransformDefault}); nearest-neighbor
     * subsampling would be misregistered between levels and drops thin structures.
     * Pixel types not supported by the block downsampler (e.g., ARGB) fall back to nearest-neighbor subsampling
     *
     * @param previous the finer level (any min)
     * @param <T>      pixel type
     * @return a cell-backed image of ceil(n/2) voxels per axis
     */
    private static <T extends NumericType<T> & NativeType<T>> RandomAccessibleInterval<T> averagedLevel(
            final RandomAccessibleInterval<T> previous) {
        final RandomAccessibleInterval<T> zeroMin = Views.zeroMin(previous);
        final long[] dims = new long[3];
        final int[] cellDims = new int[3];
        long nVoxels = 1;
        long nCells = 1;
        for (int d = 0; d < 3; d++) {
            dims[d] = (zeroMin.dimension(d) + 1) / 2;
            cellDims[d] = (int) Math.max(1, Math.min(PYRAMID_CELL_SIZE, dims[d]));
            nVoxels *= dims[d];
            nCells *= (dims[d] + cellDims[d] - 1) / cellDims[d];
        }
        // The options are immutable: each call returns a new instance
        final net.imglib2.cache.img.ReadOnlyCachedCellImgOptions base =
                net.imglib2.cache.img.ReadOnlyCachedCellImgOptions.options().cellDimensions(cellDims);
        final net.imglib2.cache.img.ReadOnlyCachedCellImgOptions opts = pinInMemory(nVoxels)
                ? base.cacheType(CacheOptions.CacheType.BOUNDED).maxCacheSize(nCells)
                : base.cacheType(CacheOptions.CacheType.SOFTREF);
        try {
            final net.imglib2.algorithm.blocks.BlockSupplier<T> averaged = net.imglib2.algorithm.blocks.BlockSupplier
                    .of(Views.extendBorder(zeroMin))
                    .andThen(net.imglib2.algorithm.blocks.downsample.Downsample.downsample(
                            net.imglib2.algorithm.blocks.ComputationType.AUTO,
                            net.imglib2.algorithm.blocks.downsample.Downsample.Offset.HALF_PIXEL,
                            new boolean[]{true, true, true}))
                    .threadSafe();
            return new net.imglib2.cache.img.ReadOnlyCachedCellImgFactory(opts).create(dims, averaged.getType(),
                    net.imglib2.algorithm.blocks.BlockAlgoUtils.cellLoader(averaged));
        } catch (final RuntimeException ex) {
            SNTUtils.log("BVV: cannot average pixel type " + zeroMin.getType().getClass().getSimpleName() + " ("
                    + ex.getMessage() + "); using nearest-neighbor subsampling");
            final RandomAccessibleInterval<T> sub = Views.subsample(zeroMin, 2, 2, 2);
            final net.imglib2.cache.img.CellLoader<T> loader = cell ->
                    LoopBuilder.setImages(Views.interval(sub, cell), cell).forEachPixel((a, b) -> b.set(a));
            return sc.fiji.snt.filter.Lazy.createImg(sub, cellDims, zeroMin.getType().createVariable(), loader);
        }
    }

    /** Edge length (voxels) of the cells of the pyramids built by {@link #buildTiledPyramid} */
    static final int PYRAMID_CELL_SIZE = CELL_SIZE;

    /**
     * Estimates the heap needed by {@link #buildTiledPyramid}: the full XYZ volume of every channel, plus ~15% for
     * the coarser levels. Doubled if {@code img} is not lazily loaded, since it stays in memory during the copy
     *
     * @param img an image with X, Y, Z leading, and optionally a channel axis
     * @return the estimated size in bytes
     */
    public static long estimateTiledPyramidBytes(final ImgPlus<?> img) {
        final int cDim = img.dimensionIndex(net.imagej.axis.Axes.CHANNEL);
        final long nC = (cDim >= 0) ? img.dimension(cDim) : 1;
        final long voxels = img.dimension(0) * img.dimension(1) * img.dimension(2);
        final long bytesPerVoxel = Math.max(1, ((RealType<?>) img.firstElement()).getBitsPerPixel() / 8);
        // A source that is already fully in memory stays resident while its tiled copy is made
        final boolean lazy = img.getImg() instanceof net.imglib2.cache.img.CachedCellImg;
        return (long) (voxels * nC * bytesPerVoxel * 1.15 * (lazy ? 1 : 2));
    }

    /**
     * Builds one pyramid-backed {@link Source} per channel of an in-memory image, such that BVV renders it with its
     * tile-streaming path ({@code MultiResolutionStack3D}) instead of uploading the whole volume as a single 3D
     * texture, which is capped by {@code GL_MAX_3D_TEXTURE_SIZE}. Level 0 and every coarser level is copied into its
     * own {@link net.imglib2.img.cell.CellImg}, because BVV only streams sources backed by cell images. Level 0 is
     * copied in memory (see {@link ImgUtils#materializeTiled}), which costs the full volume in heap (see
     * {@link #estimateTiledPyramidBytes}). Coarser levels (step 2, 4, 8...) are averaged from the previous level
     * (see {@link #averagedLevel}) and computed lazily, on demand
     * <p>
     * Meant for view-only sessions: the returned sources are independent copies, so an image also held for tracing
     * would be stored twice
     *
     * @param img     image with X, Y, Z leading, an optional channel axis, and no time axis (see
     *                {@link ImgUtils#normalizeToXYZ})
     * @param nLevels number of extra coarser levels
     * @param <T>     pixel type
     * @return one source per channel, ready for {@link sc.fiji.snt.viewer.Bvv#show(SpimDataUtils.N5Sources)}
     * @throws IllegalArgumentException if the axes layout is not XYZ[C]
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends RealType<T> & NativeType<T>> SpimDataUtils.N5Sources buildTiledPyramid(
            final ImgPlus<T> img, final int nLevels) {
        final int cDim = img.dimensionIndex(net.imagej.axis.Axes.CHANNEL);
        final int nC = (cDim >= 0) ? (int) img.dimension(cDim) : 1;
        if (!ImgUtils.hasXYZLeading(img) || img.numDimensions() != ((cDim >= 0) ? 4 : 3)) {
            throw new IllegalArgumentException("Tiled pyramids require X, Y, Z[, C] axes: " + ImgUtils.axisReport(img));
        }
        final double[] cal = {img.averageScale(0), img.averageScale(1), img.averageScale(2)};
        final String unit = (img.axis(0).unit() != null && !img.axis(0).unit().isBlank()) ? img.axis(0).unit()
                : "pixel";
        final String name = (img.getName() != null && !img.getName().isBlank()) ? img.getName() : "Image";
        final AffineTransform3D transform = new AffineTransform3D();
        transform.set(cal[0], 0, 0);
        transform.set(cal[1], 1, 1);
        transform.set(cal[2], 2, 2);
        final java.util.List<bdv.viewer.SourceAndConverter<?>> sources = new java.util.ArrayList<>();
        for (int c = 0; c < nC; c++) {
            final long start = System.currentTimeMillis();
            final RandomAccessibleInterval<T> channel = (cDim >= 0) ? Views.hyperSlice(img, cDim, c) : img;
            final RandomAccessibleInterval<T>[] levels = new RandomAccessibleInterval[nLevels + 1];
            final double[][] scales = new double[nLevels + 1][];
            levels[0] = ImgUtils.materializeTiled(channel, PYRAMID_CELL_SIZE);
            scales[0] = new double[]{1, 1, 1};
            for (int l = 1; l <= nLevels; l++) {
                final long step = 1L << l; // 2, 4, 8...
                levels[l] = averagedLevel(levels[l - 1]);
                scales[l] = new double[]{step, step, step};
            }
            final T type = img.firstElement().createVariable();
            final RandomAccessibleIntervalMipmapSource<T> source = new RandomAccessibleIntervalMipmapSource<>(levels,
                    type, scales, new mpicbg.spim.data.sequence.FinalVoxelDimensions(unit, cal), transform,
                    (nC > 1) ? name + "_ch" + (c + 1) : name);
            sources.add(new bdv.viewer.SourceAndConverter(source, bdv.BigDataViewer.createConverterToARGB(type)));
            SNTUtils.log("BVV: tiled pyramid for channel " + (c + 1) + "/" + nC + " built in "
                    + (System.currentTimeMillis() - start) + "ms");
        }
        return new SpimDataUtils.N5Sources(sources, 1, name);
    }

    /**
     * Fetches and locally caches whichever mipmap level BVV will actually render first for {@code source}, by touching
     * every pixel on the calling thread. Call {@link #preferMultiResolutionIfSafe} first so the stack type is already
     * decided when this runs
     * <p>
     * {@code SimpleStack3D} always uploads level 0 (full resolution) as a single texture on first paint (see
     * {@code bvv.core.render.DefaultSimpleStackManager}) so that upload is what must be warmed for it.
     * {@code MultiResolutionStack3D} streams blocks progressively and never blocks the EDT regardless of what is cached,
     * so warming its coarsest level here is only a courtesy (a faster first frame).
     * <p>
     * Call this on a background thread before {@code bvv.show(...)} so the EDT only ever sees already-cached data
     *
     * @param source    the source to warm up
     * @param timepoint the timepoint to warm up
     * @param <T>       pixel type
     */
    public static <T> void prefetchForShow(final Source<T> source, final int timepoint) {
        final boolean multiRes =
                SourceStacks.getSourceStackType(source) == SourceStacks.SourceStackType.MULTIRESOLUTION;
        final int level = multiRes ? source.getNumMipmapLevels() - 1 : 0;
        final RandomAccessibleInterval<T> rai = source.getSource(timepoint, level);
        final long start = System.currentTimeMillis();
        SNTUtils.log("BVV: prefetching '" + source.getName() + "' level " + level + " ("
                + (multiRes ? "multi-resolution" : "single texture") + ", " + java.util.Arrays.toString(rai.dimensionsAsLongArray()) + ")");
        LoopBuilder.setImages(rai).multiThreaded().forEachPixel(t -> {
        });
        SNTUtils.log("BVV: prefetch of '" + source.getName() + "' took " + (System.currentTimeMillis() - start)
                + "ms");
    }

    /**
     * Makes sure BVV can stream {@code soc} tile by tile. BVV only does so if level 0 of the source is backed by an
     * {@link AbstractCellImg} (see {@link #preferMultiResolutionIfSafe}). Otherwise it uploads the entire
     * full-resolution volume as a single texture, which fails outright for volumes beyond the texture limits.
     * A common offender is a multichannel OME-Zarr: each channel is a {@code hyperSlice} (a plain view) of one
     * 4D array. In that case every level is re-exposed as a lazily-filled {@link AbstractCellImg} that copies
     * tiles from the original on demand, so the memory footprint stays bounded.
     *
     * @param soc the source about to be shown in BVV
     * @return {@code soc} itself if no wrapping is needed (or possible), or an equivalent, cell-backed one
     */
    public static SourceAndConverter<?> ensureCellBacked(final SourceAndConverter<?> soc) {
        return ensureCellBacked(soc, null);
    }

    /**
     * As {@link #ensureCellBacked(SourceAndConverter)}, sizing the cells after the chunks of the underlying data
     *
     * @param soc        the source about to be shown in BVV
     * @param chunkShape the chunk shape of the data in N5 axis order (x, y, z, ...), or null if unknown, in which
     *                   case cells have {@link #CELL_SIZE} voxels per side
     * @return {@code soc} itself if no wrapping is needed (or possible), or an equivalent, cell-backed one
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SourceAndConverter<?> ensureCellBacked(final SourceAndConverter<?> soc, final int[] chunkShape) {
        final Source<?> source = soc.getSpimSource();
        final Object type = source.getType();
        if (source.getNumMipmapLevels() <= 1 || !TileAccess.isSupportedType(type)
                || !(type instanceof NumericType) || !(type instanceof NativeType)
                || type instanceof net.imglib2.Volatile) {
            return soc;
        }
        Object rai = source.getSource(0, 0);
        if (rai instanceof VolatileView) rai = ((VolatileView) rai).getVolatileViewData().getImg();
        if (rai instanceof AbstractCellImg) return soc; // already streamable
        final RandomAccessibleInterval<?> level0 = source.getSource(0, 0);
        for (int d = 0; d < level0.numDimensions(); d++) {
            if (level0.min(d) != 0) { // would need a (non-cell) translation view
                SNTUtils.log("BVV: '" + source.getName() + "' has a non-zero min; cannot make it cell-backed");
                return soc;
            }
        }
        SNTUtils.log("BVV: '" + source.getName() + "' levels are views (level 0 is a "
                + rai.getClass().getSimpleName() + "); exposing them as lazily-loaded cell images for BVV");
        final CellBackedSource<?> wrapped = new CellBackedSource(source, chunkShape);
        // BVV renders the volatile twin (if any) so that tiles load asynchronously, instead of blocking its
        // render threads on every cache miss
        final SourceAndConverter<?> vsoc = soc.asVolatile();
        if (vsoc == null) return new SourceAndConverter(wrapped, soc.getConverter());
        final VolatileCellBackedSource vsource = new VolatileCellBackedSource(wrapped, vsoc.getSpimSource(),
                loadingQueue());
        return new SourceAndConverter(wrapped, soc.getConverter(),
                new SourceAndConverter(vsource, vsoc.getConverter()));
    }

    /** Live cell-backed sources (weakly held), so their caches can be released on demand */
    private static final java.util.Set<CellBackedSource<?>> LIVE =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Releases the in-memory cell caches of all streamed (N5/Zarr/IMS) sources shown in BVV. Open viewers keep working:
     * tiles are simply reloaded on demand. The GPU tile cache is not affected (it is freed when its window closes)
     *
     * @return the number of cells that were resident (loaded) and have now been released
     */
    public static long clearCaches() {
        final java.util.List<CellBackedSource<?>> sources;
        synchronized (LIVE) {
            sources = new java.util.ArrayList<>(LIVE);
        }
        long n = 0;
        for (final CellBackedSource<?> source : sources) n += source.invalidate();
        SNTUtils.log("BVV: released " + n + " cached cells from " + sources.size() + " sources");
        return n;
    }

    private static bdv.cache.SharedQueue sharedLoadingQueue;

    private static synchronized bdv.cache.SharedQueue loadingQueue() {
        if (sharedLoadingQueue == null)
            sharedLoadingQueue = new bdv.cache.SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return sharedLoadingQueue;
    }

    /** {@link Source} delegating to another one, but exposing its levels as lazily-filled cell images */
    private static final class CellBackedSource<T extends NumericType<T> & NativeType<T>> implements Source<T> {
        private static final java.util.concurrent.atomic.AtomicInteger IN_FLIGHT =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final java.util.concurrent.atomic.AtomicLong LOADS =
                new java.util.concurrent.atomic.AtomicLong();
        /** Levels up to this many voxels (uint16: ~256 MB) are pinned in memory */
        private static final long PIN_MAX_VOXELS = 128L * 1024 * 1024;
        /** Cells loaded since the last {@link #invalidate()} (an upper bound if cells were evicted meanwhile) */
        private final java.util.concurrent.atomic.AtomicLong residentCells =
                new java.util.concurrent.atomic.AtomicLong();
        private final Source<T> delegate;
        private final java.util.Map<Long, RandomAccessibleInterval<T>> levels =
                new java.util.concurrent.ConcurrentHashMap<>();

        /** Chunk shape of the underlying data (N5 axis order), or null if unknown */
        private final int[] chunkShape;

        CellBackedSource(final Source<T> delegate, final int[] chunkShape) {
            this.delegate = delegate;
            this.chunkShape = chunkShape == null ? null : chunkShape.clone();
            synchronized (LIVE) {
                LIVE.add(this);
            }
        }

        /** Drops every cached cell (they are reloaded from the delegate on demand) */
        long invalidate() {
            for (final RandomAccessibleInterval<T> rai : levels.values()) {
                if (rai instanceof net.imglib2.cache.img.CachedCellImg<?, ?> img) img.getCache().invalidateAll();
            }
            return residentCells.getAndSet(0);
        }

        @Override
        public boolean isPresent(final int t) {
            return delegate.isPresent(t);
        }

        @Override
        public RandomAccessibleInterval<T> getSource(final int t, final int level) {
            return levels.computeIfAbsent(((long) t << 32) | level, k -> {
                final RandomAccessibleInterval<T> rai = delegate.getSource(t, level);
                final int[] cellDims = new int[rai.numDimensions()];
                for (int d = 0; d < cellDims.length; d++) {
                    final int edge = (chunkShape != null && d < 3 && chunkShape[d] > 0)
                            ? cellSizeFor(chunkShape[d]) : CELL_SIZE;
                    cellDims[d] = (int) Math.max(1, Math.min(edge, rai.dimension(d)));
                }
                final net.imglib2.cache.img.CellLoader<T> loader = cell -> {
                    LoopBuilder.setImages(Views.interval(rai, cell), cell).forEachPixel((s, c) -> c.set(s));
                    residentCells.incrementAndGet();
                };
                // Memory-only, read-only cache (no disk spill of 'dirty' cells). Levels small enough to fit in a
                // modest memory budget are held with strong references so they are never evicted and reloaded
                // while navigating (re-loading of evicted coarse cells produced visible flicker). Larger levels
                // use soft references
                long nCells = 1;
                long nVoxels = 1;
                for (int d = 0; d < cellDims.length; d++) {
                    nCells *= (rai.dimension(d) + cellDims[d] - 1) / cellDims[d];
                    nVoxels *= rai.dimension(d);
                }
                // pin (keep in memory) any level that fits in a fraction of the heap (2 bytes/voxel assumed)
                final boolean pin = pinInMemory(nVoxels);
                net.imglib2.cache.img.ReadOnlyCachedCellImgOptions opts =
                        net.imglib2.cache.img.ReadOnlyCachedCellImgOptions.options().cellDimensions(cellDims);
                opts = pin ? opts.cacheType(CacheOptions.CacheType.BOUNDED).maxCacheSize(nCells)
                        : opts.cacheType(CacheOptions.CacheType.SOFTREF);
                if (SNTUtils.isDebugMode())
                    SNTUtils.log(String.format("BVV-DEBUG '%s' level %d: %d cells, %s", delegate.getName(), level,
                            nCells, pin ? "pinned in memory" : "soft-referenced"));
                return new net.imglib2.cache.img.ReadOnlyCachedCellImgFactory(opts).create(
                        net.imglib2.util.Intervals.dimensionsAsLongArray(rai), delegate.getType().createVariable(),
                        loader);
            });
        }

        @Override
        public net.imglib2.RealRandomAccessible<T> getInterpolatedSource(final int t, final int level,
                                                                         final bdv.viewer.Interpolation method) {
            return delegate.getInterpolatedSource(t, level, method);
        }

        @Override
        public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
            delegate.getSourceTransform(t, level, transform);
        }

        @Override
        public T getType() {
            return delegate.getType();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public mpicbg.spim.data.sequence.VoxelDimensions getVoxelDimensions() {
            return delegate.getVoxelDimensions();
        }

        @Override
        public int getNumMipmapLevels() {
            return delegate.getNumMipmapLevels();
        }
    }

    /**
     * Volatile counterpart of {@link CellBackedSource}: exposes the very same (shared) cell images, but wrapped so
     * that cells load asynchronously (see {@link VolatileViews#wrapAsVolatile}). Everything else, including the
     * volatile pixel type, is delegated to the original volatile source
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class VolatileCellBackedSource implements Source {
        private final CellBackedSource cells;
        private final Source original;
        private final bdv.cache.SharedQueue queue;
        private final java.util.Map<Long, RandomAccessibleInterval> levels =
                new java.util.concurrent.ConcurrentHashMap<>();

        VolatileCellBackedSource(final CellBackedSource cells, final Source original,
                                 final bdv.cache.SharedQueue queue) {
            this.cells = cells;
            this.original = original;
            this.queue = queue;
        }

        @Override
        public boolean isPresent(final int t) {
            return original.isPresent(t);
        }

        @Override
        public RandomAccessibleInterval getSource(final int t, final int level) {
            return levels.computeIfAbsent(((long) t << 32) | level, k ->
                    VolatileViews.wrapAsVolatile((RandomAccessibleInterval) cells.getSource(t, level), queue));
        }

        @Override
        public net.imglib2.RealRandomAccessible getInterpolatedSource(final int t, final int level,
                                                                      final bdv.viewer.Interpolation method) {
            return original.getInterpolatedSource(t, level, method);
        }

        @Override
        public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
            original.getSourceTransform(t, level, transform);
        }

        @Override
        public Object getType() {
            return original.getType();
        }

        @Override
        public String getName() {
            return original.getName();
        }

        @Override
        public mpicbg.spim.data.sequence.VoxelDimensions getVoxelDimensions() {
            return original.getVoxelDimensions();
        }

        @Override
        public int getNumMipmapLevels() {
            return original.getNumMipmapLevels();
        }
    }

    /**
     * Forces BVV to render {@code source} with its pyramid-aware, block-streaming path
     * ({@code bvv.core.multires.MultiResolutionStack3D}) instead of the naive single-texture path
     * ({@code bvv.core.multires.SimpleStack3D}), when it is safe to do so.
     * <p>
     * BVV auto-detects which path to use ({@code bvv.core.multires.SourceStacks#inferSourceStackType}):
     * it only picks the multi-resolution path when {@code source}'s pixel type is
     * {@code TileAccess}-supported AND {@code source.getSource(timepoint, 0)} is (or wraps, via
     * {@code VolatileView}) an {@code AbstractCellImg}. Many BDV/N5 source builders wrap their
     * levels in a plain {@code Views}-based interval (not an {@code AbstractCellImg}), which makes
     * BVV fall back to {@code SimpleStack3D} even for a genuinely multi-resolution, remote source.
     * {@code SimpleStack3D} uploads the entire full-resolution volume as one texture on first paint,
     * fetching all of it synchronously
     * <p>
     * This mirrors BVV's own {@code inferSourceStackType} check before overriding it, so it never
     * forces multi-resolution rendering on a source that would actually fail it (which would throw
     * {@code UnsupportedOperationException} from {@code TileAccess.create} on the render thread).
     * If the check fails, this method does nothing and BVV falls back to its own (slower) default
     *
     * @param source    the source about to be shown in BVV
     * @param timepoint the timepoint to inspect
     */
    @SuppressWarnings("rawtypes")
    public static void preferMultiResolutionIfSafe(final Source<?> source, final int timepoint) {
        if (source.getNumMipmapLevels() <= 1) return; // nothing to gain, only one level exists
        if (SourceStacks.getSourceStackType(source) != SourceStacks.SourceStackType.UNDEFINED)
            return; // already decided (e.g. a previous show() call already rendered this source)
        if (!TileAccess.isSupportedType(source.getType())) {
            SNTUtils.log("BVV: '" + source.getName() + "' pixel type is not supported by BVV's "
                    + "multi-resolution renderer; leaving stack type inference to BVV");
            return;
        }
        Object rai = source.getSource(timepoint, 0);
        if (rai instanceof VolatileView) rai = ((VolatileView) rai).getVolatileViewData().getImg();
        if (rai instanceof AbstractCellImg) {
            SourceStacks.setSourceStackType(source, SourceStacks.SourceStackType.MULTIRESOLUTION);
            SNTUtils.log("BVV: '" + source.getName() + "' will use pyramid-aware, block-streaming "
                    + "rendering (avoids fetching the full volume up front)");
        } else {
            // Decide SIMPLE explicitly (rather than leaving it UNDEFINED for BVV's own lazy
            // inference to set later) so prefetchForShow() knows, right now, which level to warm
            SourceStacks.setSourceStackType(source, SourceStacks.SourceStackType.SIMPLE);
            SNTUtils.log("BVV: '" + source.getName() + "' pyramid levels are not directly backed by "
                    + "a CellImg (level 0 is a " + rai.getClass().getSimpleName() + "); BVV will fall "
                    + "back to uploading the full volume as a single texture, which may block the GUI "
                    + "for remote sources");
        }
    }

    /**
     * Read-only counterpart to {@link #preferMultiResolutionIfSafe} for {@link
     * mpicbg.spim.data.generic.AbstractSpimData} sources (BDV-XML/HDF5, IMS): logs a warning if
     * {@code source} looks likely to fall back to BVV's non-pyramid-aware {@code SimpleStack3D}
     * renderer, without attempting to prevent it.
     * <p>
     * Unlike the {@code SpimDataUtils.N5Sources} path, {@code BvvFunctions.show(AbstractSpimData,
     * BvvOptions)} builds its own {@code Source} instances internally (via {@code
     * BigDataViewer#initSetups}), so there is no hook to call {@link #preferMultiResolutionIfSafe}
     * on the actual instance before it first renders. {@code inferSourceStackType}'s check is a
     * pure function of the source's structural properties (pixel type, whether level 0 is an
     * {@code AbstractCellImg}), not of instance identity or any per-instance cached state, so
     * running the same check here - on the {@code Source} SNT already has a handle to after {@code
     * show()} returns - still gives an accurate answer; it just can't change the outcome
     * <p>
     * This is diagnostic only: it neither prefetches nor forces a stack type, so it carries none of
     * {@link #preferMultiResolutionIfSafe}/{@link #prefetchForShow}'s risk of misbehaving on a
     * source shape this hasn't been exercised against - it only makes a slow first paint traceable
     * in the log after the fact, for whichever {@code AbstractSpimData} backend produced it
     *
     * @param source    the (already-shown) source to inspect
     * @param timepoint the timepoint to inspect (0 is fine for this purely structural check)
     */
    public static void warnIfLikelySimpleStack(final Source<?> source, final int timepoint) {
        if (source.getNumMipmapLevels() <= 1) return; // BVV falls back to SIMPLE regardless; nothing to warn about
        if (!TileAccess.isSupportedType(source.getType())) {
            SNTUtils.log("BVV: '" + source.getName() + "' pixel type is not supported by BVV's "
                    + "multi-resolution renderer; it will use the single-texture SimpleStack3D path, "
                    + "which may block the GUI on first paint for large or remote data");
            return;
        }
        Object rai = source.getSource(timepoint, 0);
        if (rai instanceof VolatileView) rai = ((VolatileView) rai).getVolatileViewData().getImg();
        if (!(rai instanceof AbstractCellImg)) {
            SNTUtils.log("BVV: '" + source.getName() + "' pyramid levels are not directly backed by "
                    + "a CellImg (level 0 is a " + rai.getClass().getSimpleName() + "); BVV will likely "
                    + "use its non-pyramid-aware SimpleStack3D renderer for this source, uploading the "
                    + "full volume as a single texture, which may block the GUI on first paint for "
                    + "large or remote data");
        }
    }

    /**
     * Diagnostic-only warning for the plain {@code ImgPlus} fallback path (see {@link
     * SpimDataUtils#resolvePathToSource(String)}). Unlike {@link #preferMultiResolutionIfSafe}/
     * {@link #warnIfLikelySimpleStack}, an {@code ImgPlus} always has a single mipmap level, so BVV
     * always renders it via the non-pyramid-aware {@code SimpleStack3D} path regardless of pixel
     * type or backing storage - there is no "is it structurally eligible for MULTIRESOLUTION"
     * question to ask here the way there is for {@code AbstractSpimData}/{@code N5Sources}.
     * <p>
     * {@code resolvePathToSource} already knows this at resolution time - a remote {@code ImgPlus}
     * is only ever produced by its own URL fallback branch ({@code ImgUtils.open(url)}) - so this
     * simply carries that signal forward rather than trying to re-derive it by introspecting the
     * RAI (which, for a lazily-opened remote image, may not even be a recognizable cache type)
     *
     * @param img       the resolved {@code ImgPlus} about to be shown in BVV
     * @param pathOrUrl the original path or URL {@code img} was resolved from
     */
    public static void warnIfLikelyRemoteImgPlus(final ImgPlus<?> img, final String pathOrUrl) {
        if (!SpimDataUtils.isRemoteUrl(pathOrUrl)) return;
        SNTUtils.log("BVV: '" + img.getName() + "' was opened from a remote URL as a plain image "
                + "(no pyramid); BVV will upload the full volume as a single texture, which may "
                + "block the GUI on first paint while it downloads");
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
        if (voxels > MAX_SINGLE_TEXTURE_VOXELS) {
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
