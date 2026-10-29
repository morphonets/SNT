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

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.mesh.Mesh;
import net.imglib2.mesh.Triangles;
import net.imglib2.mesh.Vertices;
import net.imglib2.mesh.alg.MarchingCubesRealType;
import net.imglib2.mesh.impl.naive.NaiveFloatMesh;
import net.imglib2.mesh.io.ply.PLYMeshIO;
import net.imglib2.mesh.io.stl.STLMeshIO;
import net.imglib2.type.BooleanType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import sc.fiji.snt.FillConverter;
import sc.fiji.snt.Tree;
import sc.fiji.snt.tracing.DefaultSearchNode;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.image.SearchImage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Utilities for converting masks, fills and trees to triangle meshes
 * ({@link net.imglib2.mesh.Mesh}) and for reading/writing meshes in Wavefront
 * OBJ, PLY and STL formats.
 * <p>
 * All {@code fromXXX} methods expect 3D input (use
 * {@link Views#hyperSlice(net.imglib2.RandomAccessible, int, long)} to select
 * a channel or time-point) and return meshes in physical coordinates, i.e.,
 * {@code origin + voxelIndex * spacing}. This is the convention of SNT paths,
 * so meshes align with reconstructions when {@code spacing} is the image
 * calibration and {@code origin} is zero.
 * </p>
 *
 * @author Tiago Ferreira
 */
public final class MeshUtils {

    // Marching cubes iso-level for 0/1 foreground fields
    private static final double ISO_LEVEL = 0.5;
    // Upper limit for the intermediate raster of fromTree (~4 GB as floats)
    private static final double MAX_RASTER_VOXELS = 1L << 30;
    // Vertices closer than 1/DEDUP_SCALE voxels are merged
    private static final double DEDUP_SCALE = 1e4;

    private MeshUtils() {
        // static utility class
    }

    /**
     * Converts a binary mask to a mesh.
     *
     * @param <T>       the mask type
     * @param mask      the 3D mask. {@code true} voxels are foreground
     * @param spacing   the voxel size (x, y, z). Null defaults to 1
     * @param origin    the physical coordinates of voxel (0, 0, 0). Null defaults
     *                  to 0
     * @param smoothing the sigma, in the units of {@code spacing}, of a Gaussian
     *                  blur applied to the mask before meshing. 0 disables smoothing.
     *                  Note that structures thinner than about twice this value
     *                  (e.g., fills of thin neurites) shrink and may vanish
     * @return the mesh (empty if the mask has no foreground)
     */
    public static <T extends BooleanType<T>> Mesh fromMask(
            final RandomAccessibleInterval<T> mask, final double[] spacing,
            final double[] origin, final double smoothing) {
        check3D(mask);
        final RandomAccessibleInterval<FloatType> field = Converters.convert(mask,
                (in, out) -> out.set(in.get() ? 1f : 0f), new FloatType());
        return fromField(field, ISO_LEVEL, spacing, origin, smoothing);
    }

    /**
     * Converts a grayscale image to a mesh, treating voxels above a threshold as
     * foreground. Useful for masks stored as integer images (e.g., 0/255).
     *
     * @param <T>       the image type
     * @param img       the 3D image
     * @param threshold voxels with values strictly above this value are
     *                  foreground
     * @param spacing   see {@link #fromMask}
     * @param origin    see {@link #fromMask}
     * @param smoothing see {@link #fromMask}
     * @return the mesh (empty if no voxel is above threshold)
     */
    public static <T extends RealType<T>> Mesh fromThresholded(
            final RandomAccessibleInterval<T> img, final double threshold,
            final double[] spacing, final double[] origin, final double smoothing) {
        check3D(img);
        final RandomAccessibleInterval<FloatType> field = Converters.convert(img,
                (in, out) -> out.set(in.getRealDouble() > threshold ? 1f : 0f),
                new FloatType());
        return fromField(field, ISO_LEVEL, spacing, origin, smoothing);
    }

    /**
     * Extracts the iso-surface of a scalar field, e.g., an occupancy or
     * probability map. Voxels outside the field are treated as 0, so the surface
     * is closed at the field boundaries.
     *
     * @param <T>       the field type
     * @param field     the 3D field
     * @param isoLevel  the value defining the surface. Voxels above are inside
     * @param spacing   see {@link #fromMask}
     * @param origin    see {@link #fromMask}
     * @param smoothing see {@link #fromMask}
     * @return the mesh (empty if no voxel is above the iso-level)
     */
    public static <T extends RealType<T>> Mesh fromScalarField(
            final RandomAccessibleInterval<T> field, final double isoLevel,
            final double[] spacing, final double[] origin, final double smoothing) {
        check3D(field);
        final RandomAccessibleInterval<FloatType> floats = Converters.convert(
                field, (in, out) -> out.set(in.getRealFloat()), new FloatType());
        return fromField(floats, isoLevel, spacing, origin, smoothing);
    }

    /**
     * Converts a tree to a mesh. The tree is rasterized with
     * {@link TreeToRaster} (frustum geometry, so node radii are honored) into a
     * partial-volume occupancy map, from which the iso-surface is extracted.
     * Overlapping branches are merged into a single watertight surface, but be
     * aware that:
     * <ul>
     * <li>Without node radii, {@code TreeToRaster} defaults to a radius of half a
     * voxel, producing constant-width tubes.</li>
     * <li>A neurite thinner than about one voxel never reaches an occupancy of
     * 0.5. Use a smaller {@code isoLevel} (e.g., 0.25) or a smaller voxel size
     * for such cases.</li>
     * <li>The raster spans the bounding box of the tree, so very large trees
     * require a coarser {@code voxelSize}.</li>
     * </ul>
     *
     * @param tree      the tree to be converted
     * @param voxelSize the isotropic voxel size of the intermediate raster, in
     *                  the tree's spatial units
     * @param isoLevel  the occupancy (0-1 exclusive) at which the surface is
     *                  placed. 0.5 places it at the nominal neurite boundary
     * @return the mesh, in the coordinate system of the tree
     * @throws IllegalArgumentException if any argument is invalid or the
     *                                  intermediate raster would be too large
     */
    public static Mesh fromTree(final Tree tree, final double voxelSize,
                                final double isoLevel) {
        if (tree == null || tree.isEmpty())
            throw new IllegalArgumentException("Tree is null or empty");
        if (!(voxelSize > 0)) throw new IllegalArgumentException(
                "voxelSize must be positive");
        if (!(isoLevel > 0 && isoLevel < 1)) throw new IllegalArgumentException(
                "isoLevel must be in the range (0, 1)");
        final TreeToRaster rasterizer = new TreeToRaster(tree).setLateralRes(
                voxelSize).setAxialRes(voxelSize);
        final long nVoxels = rasterizer.getRasterVoxelCount();
        if (nVoxels > MAX_RASTER_VOXELS) throw new IllegalArgumentException(String
                .format("Raster would require ~%.1e voxels. Increase voxelSize",
                        (double) nVoxels));
        final RandomAccessibleInterval<FloatType> field = ImgUtils.getCtSlice3d(
                rasterizer.rasterize(), 1, 1);
        return fromScalarField(field, isoLevel, new double[]{voxelSize, voxelSize,
                voxelSize}, rasterizer.getRasterOrigin(), 0);
    }

    /**
     * Converts a label image to meshes, one per non-zero label. Each label is
     * meshed within its own bounding box.
     *
     * @param <T>       the label image type
     * @param labels    the 3D label image
     * @param spacing   see {@link #fromMask}
     * @param origin    see {@link #fromMask}
     * @param smoothing see {@link #fromMask}
     * @return the meshes, mapped by label and sorted by label
     */
    public static <T extends IntegerType<T>> Map<Long, Mesh> fromLabels(
            final RandomAccessibleInterval<T> labels, final double[] spacing,
            final double[] origin, final double smoothing) {
        check3D(labels);
        final Map<Long, long[]> bounds = new TreeMap<>();
        final long[] pos = new long[3];
        final Cursor<T> cursor = Views.flatIterable(labels).localizingCursor();
        while (cursor.hasNext()) {
            final long label = cursor.next().getIntegerLong();
            if (label == 0) continue;
            cursor.localize(pos);
            final long[] b = bounds.computeIfAbsent(label, k -> new long[]{
                    Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MIN_VALUE, Long.MIN_VALUE});
            for (int d = 0; d < 3; d++) {
                b[d] = Math.min(b[d], pos[d]);
                b[3 + d] = Math.max(b[3 + d], pos[d]);
            }
        }
        final Map<Long, Mesh> meshes = new TreeMap<>();
        for (final Map.Entry<Long, long[]> e : bounds.entrySet()) {
            final long id = e.getKey();
            final long[] b = e.getValue();
            final Interval box = new FinalInterval(new long[]{b[0], b[1], b[2]},
                    new long[]{b[3], b[4], b[5]});
            final RandomAccessibleInterval<FloatType> field = Converters.convert(
                    Views.interval(labels, box), (in, out) -> out.set(
                            in.getIntegerLong() == id ? 1f : 0f), new FloatType());
            meshes.put(id, fromField(field, ISO_LEVEL, spacing, origin, smoothing));
        }
        return meshes;
    }

    /**
     * Converts the union of one or more fills to a single mesh. Only voxels
     * within each fill's threshold are included, as in
     * {@link FillConverter#convertBinary}.
     *
     * @param fillers   the fills
     * @param spacing   see {@link #fromMask}
     * @param origin    the physical coordinates of voxel (0, 0, 0) of the
     *                  canvas the fills were computed on. Fill nodes are
     *                  canvas pixel indices, so a canvas that does not start at
     *                  the world origin (e.g., a crop) requires its offset
     * @param smoothing see {@link #fromMask}
     * @return the mesh (empty if the fills have no voxels)
     * @throws IllegalArgumentException if the bounding box of the fills is too
     *                                  large to be rasterized
     */
    public static Mesh fromFills(final Collection<FillerThread> fillers,
                                 final double[] spacing, final double[] origin, final double smoothing) {
        final FillConverter converter = new FillConverter(fillers);
        final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] max = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (final SearchImage<DefaultSearchNode> slice : converter
                .getFillerStack()) {
            if (slice == null) continue;
            for (final DefaultSearchNode node : slice) {
                if (node == null) continue;
                min[0] = Math.min(min[0], node.x);
                min[1] = Math.min(min[1], node.y);
                min[2] = Math.min(min[2], node.z);
                max[0] = Math.max(max[0], node.x);
                max[1] = Math.max(max[1], node.y);
                max[2] = Math.max(max[2], node.z);
            }
        }
        if (min[0] == Long.MAX_VALUE) return new NaiveFloatMesh();
        final double nVoxels = (double) (max[0] - min[0] + 1) * (max[1] - min[1] + 1)
                * (max[2] - min[2] + 1);
        if (nVoxels > MAX_RASTER_VOXELS) throw new IllegalArgumentException(String
                .format("Fills span ~%.1e voxels. Too large to be meshed at once", nVoxels));
        // Rasterize only the bounding box of the fills, at their true position
        final Img<BitType> img = ArrayImgs.bits(max[0] - min[0] + 1,
                max[1] - min[1] + 1, max[2] - min[2] + 1);
        final IntervalView<BitType> shifted = Views.translate(img, min);
        converter.convertBinary(shifted);
        return fromMask(shifted, spacing, origin, smoothing);
    }

    /**
     * Reads a mesh from a file, with the format inferred from the file
     * extension: {@code .obj}, {@code .ply} or {@code .stl}.
     *
     * @param file the file to be read
     * @return the mesh
     * @throws IOException if the file could not be read or has an unsupported
     *           extension
     */
    public static Mesh read(final File file) throws IOException {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".obj")) return readObj(file);
        if (name.endsWith(".ply")) return PLYMeshIO.open(file.getAbsolutePath());
        if (name.endsWith(".stl")) return STLMeshIO.open(file.getAbsolutePath());
        throw new IOException("Unsupported mesh format: " + file.getName());
    }

    /**
     * Reads a Wavefront OBJ file. See {@link #readObj(InputStream)}.
     *
     * @param file the OBJ file
     * @return the mesh
     * @throws IOException if the file could not be read or parsed
     */
    public static Mesh readObj(final File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return readObj(in);
        }
    }

    /**
     * Reads a Wavefront OBJ from a URL. See {@link #readObj(InputStream)}.
     *
     * @param url the OBJ location
     * @return the mesh
     * @throws IOException if the content could not be read or parsed
     */
    public static Mesh readObj(final URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return readObj(in);
        }
    }

    /**
     * Reads a Wavefront OBJ. Only geometry is read: vertices ({@code v}) and
     * faces ({@code f}), with polygons triangulated as fans. Normals, texture
     * coordinates, materials and object/group boundaries are ignored, so
     * multi-object files are merged into a single mesh. Vertex order is
     * preserved. Face indices may be 1-based or negative (relative) and may
     * include texture/normal references ({@code v/vt/vn}).
     *
     * @param in the stream to read. Closed by the caller
     * @return the mesh
     * @throws IOException if the content could not be read or parsed
     */
    public static Mesh readObj(final InputStream in) throws IOException {
        final Mesh mesh = new NaiveFloatMesh();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        try {
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.length() < 2) continue;
                if (line.startsWith("v ") || line.startsWith("v\t")) {
                    final String[] t = line.split("\\s+");
                    mesh.vertices().add(Double.parseDouble(t[1]), Double.parseDouble(
                            t[2]), Double.parseDouble(t[3]));
                } else if (line.startsWith("f ") || line.startsWith("f\t")) {
                    final String[] t = line.split("\\s+");
                    final long nVertices = mesh.vertices().sizel();
                    final long first = objIndex(t[1], nVertices);
                    long prev = objIndex(t[2], nVertices);
                    for (int i = 3; i < t.length; i++) {
                        final long next = objIndex(t[i], nVertices);
                        mesh.triangles().add(first, prev, next);
                        prev = next;
                    }
                }
            }
        } catch (final NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IOException("Malformed OBJ at line " + lineNo, e);
        }
        return mesh;
    }

    // Converts an OBJ face token ("i", "i/j", "i//k", "i/j/k") to a 0-based
    // vertex index. Negative values are relative to the last vertex read
    private static long objIndex(final String token, final long nVertices)
            throws IOException {
        final int slash = token.indexOf('/');
        final long i = Long.parseLong(slash < 0 ? token : token.substring(0,
                slash));
        final long idx = (i < 0) ? nVertices + i : i - 1;
        if (idx < 0 || idx >= nVertices) throw new IOException(
                "Face refers to a non-existent vertex: " + token);
        return idx;
    }

    /**
     * Saves a mesh to a file, with the format inferred from the file extension:
     * {@code .obj}, {@code .ply} or {@code .stl}. If the extension is none of
     * these, {@code .obj} is appended.
     *
     * @param mesh the mesh to be saved
     * @param file the destination
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File save(final Mesh mesh, final File file)
            throws IOException {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ply")) return saveAsPly(mesh, file);
        if (name.endsWith(".stl")) return saveAsStl(mesh, file);
        return saveAsObj(mesh, file);
    }

    /**
     * Saves a mesh as a binary PLY file.
     *
     * @param mesh the mesh to be saved
     * @param file the destination. {@code .ply} is appended if missing
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File saveAsPly(final Mesh mesh, final File file)
            throws IOException {
        final File out = withExtension(file, ".ply");
        PLYMeshIO.save(mesh, out.getAbsolutePath());
        return out;
    }

    /**
     * Saves a mesh as a binary STL file. STL stores triangles only (no shared
     * vertices) and is unitless.
     *
     * @param mesh the mesh to be saved
     * @param file the destination. {@code .stl} is appended if missing
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File saveAsStl(final Mesh mesh, final File file)
            throws IOException {
        final File out = withExtension(file, ".stl");
        STLMeshIO.save(mesh, out.getAbsolutePath());
        return out;
    }

    private static File withExtension(final File file, final String ext) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(ext) ? file
                : new File(file.getParentFile(), file.getName() + ext);
    }

    /**
     * Saves a mesh as a Wavefront OBJ file (vertices, per-vertex normals and
     * triangular faces).
     * OBJ files are unitless: coordinates are written as they are in the mesh.
     *
     * @param mesh the mesh to be saved
     * @param file the destination. {@code .obj} is appended if missing
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File saveAsObj(final Mesh mesh, final File file)
            throws IOException {
        final String name = file.getName().replaceFirst("(?i)\\.obj$", "");
        return saveAsObj(Map.of(name, mesh), file);
    }

    /**
     * Saves several meshes to a single Wavefront OBJ file, each as a named
     * object ({@code o} statement). Objects are written in iteration order of
     * the map.
     *
     * @param meshes the meshes to be saved, mapped by object name
     * @param file   the destination. {@code .obj} is appended if missing
     * @return the file written
     * @throws IOException if the file could not be written
     */
    public static File saveAsObj(final Map<String, Mesh> meshes, final File file)
            throws IOException {
        return saveAsObj(meshes, null, file);
    }

    /**
     * A simple Wavefront material: a diffuse color and an opacity.
     *
     * @param r       red, in the range [0, 255]
     * @param g       green, in the range [0, 255]
     * @param b       blue, in the range [0, 255]
     * @param opacity 0 (fully transparent) to 1 (opaque)
     */
    public record Material(int r, int g, int b, double opacity) {

        /**
         * @return this material as an AWT color (opacity is the alpha channel)
         */
        public java.awt.Color toColor() {
            return new java.awt.Color(clamp(r), clamp(g), clamp(b), clamp((int) Math.round(opacity * 255)));
        }

        private static int clamp(final int v) {
            return Math.clamp(v, 0, 255);
        }
    }

    /**
     * Retrieves the material associated with a mesh file: the material library
     * ({@code .mtl}) referenced by an OBJ file, if any, otherwise a {@code .mtl}
     * file with the same name as the mesh in the same directory. Only the first
     * material of the library is considered.
     *
     * @param meshFile the mesh file (.obj, .ply, .stl)
     * @return the material, or null if no (valid) material file was found
     */
    public static Material readMaterial(final File meshFile) {
        if (meshFile == null || !meshFile.isFile()) return null;
        final File dir = meshFile.getAbsoluteFile().getParentFile();
        final String name = meshFile.getName();
        final List<File> candidates = new ArrayList<>();
        if (name.toLowerCase(Locale.ROOT).endsWith(".obj")) {
            final String ref = readMtllib(meshFile);
            if (ref != null && isSafeRelativeReference(ref)) candidates.add(new File(dir, ref));
        }
        candidates.add(new File(dir, name.replaceFirst("\\.[^.]*$", "") + ".mtl"));
        for (final File candidate : candidates) {
            if (!candidate.isFile()) continue;
            try {
                final Material m = readMaterial(candidate.toURI().toURL());
                if (m != null) return m;
            } catch (final IOException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /**
     * Reads the first material of a Wavefront material library ({@code .mtl}):
     * its diffuse color ({@code Kd}) and opacity ({@code d}, or {@code 1 - Tr}).
     *
     * @param mtlUrl the location of the .mtl file
     * @return the material, or null if the first material has no diffuse color
     * @throws IOException if the file could not be read or parsed
     */
    public static Material readMaterial(final URL mtlUrl) throws IOException {
        double[] kd = null;
        double alpha = 1d;
        boolean inFirst = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(mtlUrl.openStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] t = line.trim().split("\\s+");
                if ("newmtl".equals(t[0])) {
                    if (inFirst) break; // only the first material is used
                    inFirst = true;
                } else if (inFirst && "Kd".equals(t[0]) && t.length >= 4) {
                    kd = new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2]),
                            Double.parseDouble(t[3])};
                } else if (inFirst && "d".equals(t[0]) && t.length >= 2) {
                    alpha = Double.parseDouble(t[1]);
                } else if (inFirst && "Tr".equals(t[0]) && t.length >= 2) {
                    alpha = 1d - Double.parseDouble(t[1]);
                }
            }
        } catch (final NumberFormatException e) {
            throw new IOException("Malformed material file: " + mtlUrl, e);
        }
        if (kd == null) return null;
        return new Material((int) Math.round(kd[0] * 255), (int) Math.round(kd[1] * 255),
                (int) Math.round(kd[2] * 255), alpha);
    }

    /**
     * Checks that a file reference (e.g., the name of a material library in an
     * OBJ file) is relative and stays within the location of the referencing file.
     *
     * @param reference the file reference
     * @return false for empty, absolute, scheme-prefixed (e.g., URLs) or
     *         parent-directory ({@code ..}) references
     */
    public static boolean isSafeRelativeReference(final String reference) {
        return reference != null && !reference.isEmpty() && !reference.contains(":")
                && !reference.startsWith("/") && !reference.startsWith("\\") && !reference.contains("..");
    }

    /* The material library declared in the header of an OBJ file, or null */
    private static String readMtllib(final File objFile) {
        try (BufferedReader r = Files.newBufferedReader(objFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            for (int i = 0; i < 50 && (line = r.readLine()) != null; i++) {
                if (line.startsWith("mtllib ")) return line.substring(7).trim();
            }
        } catch (final IOException | RuntimeException ignored) {
            // no material
        }
        return null;
    }

    /**
     * Saves several meshes to a single Wavefront OBJ file, each as a named
     * object ({@code o} statement), with optional colors. Per-vertex normals
     * are written for all meshes. If {@code materials} is not empty, a companion
     * {@code .mtl} file (same name as the OBJ file) is also written, and objects
     * with an entry in {@code materials} reference it.
     *
     * @param meshes    the meshes to be saved, mapped by object name
     * @param materials the materials, mapped by object name (the same keys as
     *                  {@code meshes}). Null or empty to not write colors
     * @param file      the destination. {@code .obj} is appended if missing
     * @return the file written
     * @throws IOException if a file could not be written
     */
    public static File saveAsObj(final Map<String, Mesh> meshes,
                                 final Map<String, Material> materials, final File file)
            throws IOException {
        final File out = withExtension(file, ".obj");
        final boolean writeMtl = materials != null && !materials.isEmpty();
        final String mtlName = out.getName().replaceFirst("(?i)\\.obj$", "") + ".mtl";
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(),
                StandardCharsets.UTF_8)) {
            w.write("# Exported by SNT\n");
            if (writeMtl) w.write("mtllib " + mtlName.replaceAll("\\s+", "_") + "\n");
            // OBJ face indices are 1-based and global to the file
            long offset = 1;
            for (final Map.Entry<String, Mesh> e : meshes.entrySet()) {
                final Mesh mesh = e.getValue();
                final Vertices v = mesh.vertices();
                final Triangles t = mesh.triangles();
                final String name = e.getKey().replaceAll("\\s+", "_");
                w.write("o " + name + "\n");
                if (writeMtl && materials.containsKey(e.getKey()))
                    w.write("usemtl " + name + "\n");
                for (long i = 0; i < v.sizel(); i++) {
                    w.write("v " + fmt(v.x(i)) + " " + fmt(v.y(i)) + " " + fmt(v.z(i))
                            + "\n");
                }
                final double[] normals = vertexNormals(mesh);
                for (long i = 0; i < v.sizel(); i++) {
                    final int k = (int) (3 * i);
                    w.write("vn " + fmt(normals[k]) + " " + fmt(normals[k + 1]) + " "
                            + fmt(normals[k + 2]) + "\n");
                }
                for (long i = 0; i < t.sizel(); i++) {
                    final long a = offset + t.vertex0(i);
                    final long b = offset + t.vertex1(i);
                    final long c = offset + t.vertex2(i);
                    w.write("f " + a + "//" + a + " " + b + "//" + b + " " + c + "//" + c
                            + "\n");
                }
                offset += v.sizel();
            }
        }
        if (writeMtl) {
            final File mtl = new File(out.getParentFile(), mtlName.replaceAll("\\s+", "_"));
            try (BufferedWriter w = Files.newBufferedWriter(mtl.toPath(),
                    StandardCharsets.UTF_8)) {
                w.write("# Exported by SNT\n");
                for (final Map.Entry<String, Material> e : materials.entrySet()) {
                    final Material m = e.getValue();
                    w.write("newmtl " + e.getKey().replaceAll("\\s+", "_") + "\n");
                    w.write("Kd " + fmt(m.r() / 255d) + " " + fmt(m.g() / 255d) + " "
                            + fmt(m.b() / 255d) + "\n");
                    w.write("d " + fmt(m.opacity()) + "\n");
                }
            }
        }
        return out;
    }

    /** Area-weighted, normalized per-vertex normals (3 values per vertex) */
    private static double[] vertexNormals(final Mesh mesh) {
        final Vertices v = mesh.vertices();
        final Triangles t = mesh.triangles();
        final double[] n = new double[(int) (3 * v.sizel())];
        for (long i = 0; i < t.sizel(); i++) {
            final long a = t.vertex0(i);
            final long b = t.vertex1(i);
            final long c = t.vertex2(i);
            final double e1x = v.x(b) - v.x(a), e1y = v.y(b) - v.y(a), e1z = v.z(b) - v.z(a);
            final double e2x = v.x(c) - v.x(a), e2y = v.y(c) - v.y(a), e2z = v.z(c) - v.z(a);
            final double cx = e1y * e2z - e1z * e2y;
            final double cy = e1z * e2x - e1x * e2z;
            final double cz = e1x * e2y - e1y * e2x;
            for (final long idx : new long[]{a, b, c}) {
                final int k = (int) (3 * idx);
                n[k] += cx;
                n[k + 1] += cy;
                n[k + 2] += cz;
            }
        }
        for (int k = 0; k < n.length; k += 3) {
            final double len = Math.sqrt(n[k] * n[k] + n[k + 1] * n[k + 1] + n[k + 2] * n[k + 2]);
            if (len > 0) {
                n[k] /= len;
                n[k + 1] /= len;
                n[k + 2] /= len;
            } else {
                n[k + 2] = 1;
            }
        }
        return n;
    }

    /**
     * Meshes a 0/1 float field defined in voxel coordinates
     */
    private static Mesh fromField(final RandomAccessibleInterval<FloatType> field,
                                  final double isoLevel, final double[] spacing,
                                  final double[] origin, final double smoothing) {
        final double[] sp = spacing == null ? new double[]{1, 1, 1} : spacing;
        final double[] org = origin == null ? new double[3] : origin;
        if (sp.length != 3 || org.length != 3)
            throw new IllegalArgumentException("spacing and origin must have 3 elements");
        if (sp[0] <= 0 || sp[1] <= 0 || sp[2] <= 0)
            throw new IllegalArgumentException("spacing must be positive");

        // Padding closes the surface at image borders. When smoothing, it also
        // must accommodate the blur kernel
        final double[] sigmas = new double[3];
        int pad = 1;
        if (smoothing > 0) {
            for (int d = 0; d < 3; d++) {
                sigmas[d] = smoothing / sp[d];
                pad = Math.max(pad, (int) Math.ceil(3 * sigmas[d]) + 1);
            }
        }
        final long[] min = new long[3];
        final long[] max = new long[3];
        for (int d = 0; d < 3; d++) {
            min[d] = field.min(d) - pad;
            max[d] = field.max(d) + pad;
        }
        final Interval padded = new FinalInterval(min, max);

        final RandomAccessibleInterval<FloatType> input;
        if (smoothing > 0) {
            final long[] dims = new long[3];
            padded.dimensions(dims);
            final IntervalView<FloatType> target = Views.translate(ArrayImgs.floats(
                    dims), min);
            try {
                Gauss3.gauss(sigmas, Views.extendZero(field), target);
            } catch (final IncompatibleTypeException ex) {
                throw new IllegalStateException(ex);
            }
            input = target;
        } else {
            input = Views.interval(Views.extendZero(field), padded);
        }

        // Vertices are returned in the voxel coordinates of the input interval
        final Mesh raw = MarchingCubesRealType.calculate(input, isoLevel);
        return toPhysical(raw, sp, org);
    }

    /**
     * Copies a mesh to physical coordinates, merging coincident vertices
     * (marching cubes duplicates vertices shared between cubes) and dropping
     * degenerate triangles
     */
    private static Mesh toPhysical(final Mesh raw, final double[] spacing,
                                   final double[] origin) {
        final Vertices rv = raw.vertices();
        final Triangles rt = raw.triangles();
        final Mesh out = new NaiveFloatMesh();
        final long[] remap = new long[Math.toIntExact(rv.size())];
        final Map<VertexKey, Long> seen = new HashMap<>();
        for (int i = 0; i < remap.length; i++) {
            final double x = rv.x(i);
            final double y = rv.y(i);
            final double z = rv.z(i);
            final VertexKey key = new VertexKey(Math.round(x * DEDUP_SCALE), Math
                    .round(y * DEDUP_SCALE), Math.round(z * DEDUP_SCALE));
            Long idx = seen.get(key);
            if (idx == null) {
                idx = out.vertices().add(origin[0] + x * spacing[0], origin[1] + y
                        * spacing[1], origin[2] + z * spacing[2]);
                seen.put(key, idx);
            }
            remap[i] = idx;
        }
        for (long i = 0; i < rt.size(); i++) {
            final long a = remap[(int) rt.vertex0(i)];
            final long b = remap[(int) rt.vertex1(i)];
            final long c = remap[(int) rt.vertex2(i)];
            if (a != b && b != c && a != c) out.triangles().add(a, b, c);
        }
        return out;
    }

    private record VertexKey(long x, long y, long z) {
    }

    private static void check3D(final RandomAccessibleInterval<?> rai) {
        if (rai.numDimensions() != 3) throw new IllegalArgumentException(
                "Input must be 3D but has " + rai.numDimensions() + " dimensions");
    }

    // Float.toString is the shortest exact representation but switches to
    // scientific notation outside [1e-3, 1e7), which not all OBJ readers parse
    private static String fmt(final double d) {
        final float f = (float) d;
        final float abs = Math.abs(f);
        if (abs >= 1e-3f && abs < 1e7f) return Float.toString(f);
        return String.format(Locale.ROOT, "%.8f", d);
    }

}
