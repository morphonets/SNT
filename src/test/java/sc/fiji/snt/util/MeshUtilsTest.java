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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.imglib2.RandomAccess;
import net.imglib2.RealInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.mesh.Mesh;
import net.imglib2.mesh.MeshStats;
import net.imglib2.mesh.Meshes;
import net.imglib2.mesh.Triangles;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

/**
 * Tests for {@link MeshUtils}
 */
public class MeshUtilsTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final double[] SPACING = { 0.5, 0.5, 2 };

	private static Img<BitType> sphere(final int size, final int radius) {
		final Img<BitType> img = ArrayImgs.bits(size, size, size);
		final RandomAccess<BitType> ra = img.randomAccess();
		final int c = size / 2;
		for (int z = 0; z < size; z++)
			for (int y = 0; y < size; y++)
				for (int x = 0; x < size; x++) {
					final int dx = x - c, dy = y - c, dz = z - c;
					if (dx * dx + dy * dy + dz * dz <= radius * radius)
						ra.setPositionAndGet(x, y, z).set(true);
				}
		return img;
	}

	@Test
	public void sphereIsWatertightAndCalibrated() {
		final Mesh mesh = MeshUtils.fromMask(sphere(40, 12), SPACING, null, 0);
		assertTrue(mesh.triangles().size() > 0);

		// Each undirected edge of a closed manifold is shared by two triangles
		final Triangles t = mesh.triangles();
		final Map<String, Integer> edges = new HashMap<>();
		for (long i = 0; i < t.size(); i++) {
			final long[] v = { t.vertex0(i), t.vertex1(i), t.vertex2(i) };
			for (int k = 0; k < 3; k++) {
				final long a = Math.min(v[k], v[(k + 1) % 3]);
				final long b = Math.max(v[k], v[(k + 1) % 3]);
				edges.merge(a + "-" + b, 1, Integer::sum);
			}
		}
		assertTrue(edges.values().stream().allMatch(n -> n == 2));

		// Surface lies half a voxel beyond the outermost foreground voxels, i.e.,
		// extent is 2*radius+1 voxels. Tolerance is 1.5 voxels to avoid a
		// floating point tie at the boundary
		final RealInterval bb = Meshes.boundingBox(mesh);
		for (int d = 0; d < 3; d++) {
			final double extent = bb.realMax(d) - bb.realMin(d);
			assertEquals(24 * SPACING[d], extent, 1.5 * SPACING[d]);
		}
	}

	@Test
	public void smoothingKeepsSurfaceClosed() {
		final Mesh mesh = MeshUtils.fromMask(sphere(40, 12), SPACING, null, 1);
		assertTrue(mesh.triangles().size() > 0);
	}

	@Test
	public void labelsYieldOneMeshEach() {
		final Img<UnsignedByteType> labels = ArrayImgs.unsignedBytes(30, 30, 30);
		final RandomAccess<UnsignedByteType> ra = labels.randomAccess();
		for (int i = 0; i < 6; i++)
			for (int j = 0; j < 6; j++)
				for (int k = 0; k < 6; k++) {
					ra.setPositionAndGet(2 + i, 2 + j, 2 + k).set(1);
					ra.setPositionAndGet(20 + i, 20 + j, 20 + k).set(2);
				}
		final Map<Long, Mesh> meshes = MeshUtils.fromLabels(labels, null, null, 0);
		assertEquals(List.of(1L, 2L), List.copyOf(meshes.keySet()));
		final RealInterval bb = Meshes.boundingBox(meshes.get(2L));
		// Cube spans voxels 20-25: surface lies between 19.5 and 25.5
		assertEquals(19.5, bb.realMin(0), 1e-3);
		assertEquals(25.5, bb.realMax(0), 1e-3);
	}

	@Test
	public void objRoundTripCounts() throws IOException {
		final Mesh mesh = MeshUtils.fromMask(sphere(20, 6), null, null, 0);
		final File f = MeshUtils.saveAsObj(mesh, new File(tmp.getRoot(), "sphere"));
		assertTrue(f.getName().endsWith(".obj"));
		final List<String> lines = Files.readAllLines(f.toPath());
		assertEquals(mesh.vertices().size(), lines.stream().filter(l -> l
				.startsWith("v ")).count());
		assertEquals(mesh.triangles().size(), lines.stream().filter(l -> l
				.startsWith("f ")).count());
	}

	@Test
	public void objWritesNormalsAndMaterial() throws IOException {
		final Mesh mesh = MeshUtils.fromMask(sphere(20, 6), null, null, 0);
		final File f = MeshUtils.saveAsObj(Map.of("ball", mesh), Map.of("ball",
				new MeshUtils.Material(255, 128, 0, 0.5)), new File(tmp.getRoot(), "ball"));
		final List<String> obj = Files.readAllLines(f.toPath());
		assertEquals(mesh.vertices().size(), obj.stream().filter(l -> l
				.startsWith("vn ")).count());
		assertTrue(obj.contains("mtllib ball.mtl"));
		assertTrue(obj.contains("usemtl ball"));
		final List<String> mtl = Files.readAllLines(new File(tmp.getRoot(),
				"ball.mtl").toPath());
		assertTrue(mtl.contains("newmtl ball"));
		assertTrue(mtl.stream().anyMatch(l -> l.startsWith("Kd 1.0 0.5")));
		assertTrue(mtl.contains("d 0.5"));
		// normals and material must not break reading
		assertEquals(mesh.triangles().size(), MeshUtils.readObj(f).triangles().size());
	}

	@Test
	public void materialIsReadBack() throws IOException {
		final Mesh mesh = MeshUtils.fromMask(sphere(20, 6), null, null, 0);
		final MeshUtils.Material m = new MeshUtils.Material(255, 128, 0, 0.5);
		final File f = MeshUtils.saveAsObj(Map.of("ball", mesh), Map.of("ball", m),
				new File(tmp.getRoot(), "ball"));
		assertEquals(m, MeshUtils.readMaterial(f));
		// same-name sidecar of a mesh in another format
		final File ply = MeshUtils.saveAsPly(mesh, new File(tmp.getRoot(), "ball"));
		assertEquals(m, MeshUtils.readMaterial(ply));
		assertEquals(new java.awt.Color(255, 128, 0, 128), m.toColor());
		assertNull(MeshUtils.readMaterial(new File(tmp.getRoot(), "none.obj")));
	}

	@Test
	public void unsafeMaterialReferencesAreRejected() {
		assertFalse(MeshUtils.isSafeRelativeReference("../x.mtl"));
		assertFalse(MeshUtils.isSafeRelativeReference("/etc/x.mtl"));
		assertFalse(MeshUtils.isSafeRelativeReference("http://host/x.mtl"));
		assertTrue(MeshUtils.isSafeRelativeReference("sub/x.mtl"));
	}

	@Test
	public void plyAndStlRoundTrip() throws IOException {
		final Mesh mesh = MeshUtils.fromMask(sphere(20, 6), null, null, 0);
		final File ply = MeshUtils.save(mesh, new File(tmp.getRoot(), "s.ply"));
		final Mesh fromPly = net.imglib2.mesh.io.ply.PLYMeshIO.open(ply
				.getAbsolutePath());
		assertEquals(mesh.triangles().sizel(), fromPly.triangles().sizel());
		final File stl = MeshUtils.save(mesh, new File(tmp.getRoot(), "s.stl"));
		final Mesh fromStl = net.imglib2.mesh.io.stl.STLMeshIO.open(stl
				.getAbsolutePath());
		assertEquals(mesh.triangles().sizel(), fromStl.triangles().sizel());
		assertTrue(MeshUtils.save(mesh, new File(tmp.getRoot(), "x")).getName()
				.endsWith(".obj"));
	}

	@Test
	public void objRoundTripGeometry() throws IOException {
		final Mesh mesh = MeshUtils.fromMask(sphere(20, 6), SPACING, null, 0);
		final File f = MeshUtils.saveAsObj(mesh, new File(tmp.getRoot(), "rt"));
		final Mesh read = MeshUtils.read(f);
		assertEquals(mesh.vertices().sizel(), read.vertices().sizel());
		assertEquals(mesh.triangles().sizel(), read.triangles().sizel());
		for (int d = 0; d < 3; d++) {
			final RealInterval a = Meshes.boundingBox(mesh);
			final RealInterval b = Meshes.boundingBox(read);
			assertEquals(a.realMin(d), b.realMin(d), 1e-4);
			assertEquals(a.realMax(d), b.realMax(d), 1e-4);
		}
	}

	@Test
	public void objParserHandlesQuadsRefsAndNegativeIndices() throws IOException {
		final String obj = "# test\nv 0 0 0\nv 1 0 0  \nv 1 1 0\nv 0 1 0\n"
				+ "vt 0 0\nvn 0 0 1\n"
				// quad with vt/vn references: fan gives 2 triangles
				+ "f 1/1/1 2/1/1 3/1/1 4/1/1\n"
				// triangle with negative (relative) indices
				+ "f -4 -3 -2\n";
		final Mesh mesh = MeshUtils.readObj(new java.io.ByteArrayInputStream(obj
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertEquals(4, mesh.vertices().sizel());
		assertEquals(3, mesh.triangles().sizel());
	}

	@Test(expected = IOException.class)
	public void objParserRejectsDanglingFace() throws IOException {
		MeshUtils.readObj(new java.io.ByteArrayInputStream("v 0 0 0\nf 1 2 3\n"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private static Tree tube(final double radius) {
		final Path path = new Path(1.0, 1.0, 1.0, "um");
		for (final double x : new double[] { 100, 120, 140 })
			path.addNode(new PointInImage(x, 50, 20));
		if (radius > 0) path.setRadius(radius);
		final Tree tree = new Tree();
		tree.add(path);
		return tree;
	}

	@Test
	public void treeTubeVolumeAndPlacement() {
		final Mesh mesh = MeshUtils.fromTree(tube(2), 0.5, 0.5);
		assertTrue(mesh.triangles().sizel() > 0);
		// Cylinder volume, plus up to two spherical caps (~7%)
		final double expected = Math.PI * 2 * 2 * 40;
		assertEquals(expected, Math.abs(MeshStats.volume(mesh)), 0.15 * expected);
		// The mesh must sit on the tree, not on the raster origin
		final RealInterval bb = Meshes.boundingBox(mesh);
		assertEquals(48, bb.realMin(1), 0.5);
		assertEquals(52, bb.realMax(1), 0.5);
		assertEquals(18, bb.realMin(2), 0.5);
		assertEquals(22, bb.realMax(2), 0.5);
		assertTrue(bb.realMin(0) > 97 && bb.realMin(0) < 101);
		assertTrue(bb.realMax(0) > 139 && bb.realMax(0) < 143);
	}

	@Test
	public void treeWithoutRadiiNeedsLowIso() {
		// Default radius is half a voxel: too thin for the 0.5 iso-level
		assertTrue(MeshUtils.fromTree(tube(0), 1, 0.1).triangles().sizel() > 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void treeRejectsInvalidIsoLevel() {
		MeshUtils.fromTree(tube(2), 0.5, 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void treeRejectsHugeRaster() {
		MeshUtils.fromTree(tube(2), 1e-3, 0.5);
	}

}
