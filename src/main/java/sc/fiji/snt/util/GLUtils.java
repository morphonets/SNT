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

import com.jogamp.opengl.*;
import sc.fiji.snt.SNTUtils;

/**
 * Helpers for querying GPU/OpenGL capabilities.
 *
 * @author Tiago Ferreira
 */
public class GLUtils {

    private static final int GL_MAX_3D_TEXTURE_SIZE = 0x8073; // OpenGL constant
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872; // fragment shader samplers
    private static final int FALLBACK_MAX_TEXTURE_IMAGE_UNITS = 16; // minimum guaranteed by OpenGL 3.x/4.x
    private static final int FALLBACK_MAX_TEXTURE_3D_SIZE = 2048; // conservative default (used on macOS!?)
    private static volatile Info cachedInfo;

    private GLUtils() {
    } // prevent class instantiation

    /**
     * @param vendor           {@code GL_VENDOR}, e.g., "NVIDIA Corporation"
     * @param renderer         {@code GL_RENDERER}, e.g., "NVIDIA GeForce RTX 3080/PCIe/SSE2"
     * @param version          {@code GL_VERSION}, e.g., "4.6.0 NVIDIA 551.23"
     * @param maxTexture3DSize {@code GL_MAX_3D_TEXTURE_SIZE}, or a conservative default if unknown
     * @param maxTextureImageUnits {@code GL_MAX_TEXTURE_IMAGE_UNITS}, i.e., the number of samplers a fragment shader
     *                         can use (a conservative default if unknown). Limits the number of sources BVV can render
     *                         at once
     * @param available        false if no GL context could be created (headless, no/broken drivers, etc)
     */
    public record Info(String vendor, String renderer, String version, int maxTexture3DSize,
                       int maxTextureImageUnits, boolean available) {
    }

    private static final Info UNAVAILABLE = new Info("Unknown", "OpenGL unavailable", "N/A",
            FALLBACK_MAX_TEXTURE_3D_SIZE, FALLBACK_MAX_TEXTURE_IMAGE_UNITS, false);

    /**
     * Queries GPU/OpenGL info via a 1x1 offscreen drawable. The result is cached after the first call, since neither
     * the active GPU nor its driver change mid-session. Note that on hybrid-graphics systems (e.g., NVIDIA Optimus,
     * AMD Switchable Graphics, macOS automatic graphics switching) this reports whichever GPU the OS bound to this JVM
     * process, not necessarily every GPU installed
     *
     * @return the (cached) result of the query; {@link Info#available()} is false if no GL context could be created
     */
    public static Info getInfo() {
        Info info = cachedInfo;
        if (info == null) {
            synchronized (GLUtils.class) {
                info = cachedInfo;
                if (info == null) info = cachedInfo = queryInfo();
            }
        }
        return info;
    }

    /**
     * Picks a Core Profile (3.2+) when available. {@link GLProfile#getDefault()} resolves to the legacy/compatibility
     * profile, which on macOS is capped at OpenGL 2.1 regardless of hardware, since Apple's OpenGL-on-Metal layer only
     * exposes its full version (4.1, the ceiling on every Apple Silicon chip so far) to Core Profile contexts. Falls
     * back to the default profile on setups without a Core Profile (e.g., old GPUs, some software rasterizers)
     */
    private static GLProfile pickProfile() {
        try {
            return GLProfile.getMaxProgrammableCore(true);
        } catch (final Exception e) {
            return GLProfile.getDefault();
        }
    }

    private static Info queryInfo() {
        try {
            final GLProfile profile = pickProfile();
            final GLCapabilities caps = new GLCapabilities(profile);
            final GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            final GLOffscreenAutoDrawable drawable = factory.createOffscreenAutoDrawable(null, caps, null, 1, 1);
            drawable.display();
            drawable.getContext().makeCurrent();
            try {
                final GL gl = drawable.getContext().getGL();
                final int[] val = new int[1];
                gl.glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE, val, 0);
                final int maxTex3D = val[0] > 0 ? val[0] : FALLBACK_MAX_TEXTURE_3D_SIZE;
                val[0] = 0; // a failed query leaves the array untouched
                gl.glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, val, 0);
                final int units = val[0] > 0 ? val[0] : FALLBACK_MAX_TEXTURE_IMAGE_UNITS;
                return new Info(gl.glGetString(GL.GL_VENDOR), gl.glGetString(GL.GL_RENDERER),
                        gl.glGetString(GL.GL_VERSION), maxTex3D, units, true);
            } finally {
                drawable.getContext().release();
                drawable.destroy();
            }
        } catch (final Exception e) {
            SNTUtils.log("GLUtils: offscreen GL query failed (" + e.getMessage() + ")");
            return UNAVAILABLE;
        }
    }
}
