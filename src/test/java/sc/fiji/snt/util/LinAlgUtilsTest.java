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

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link LinAlgUtils}.
 */
public class LinAlgUtilsTest {

	private static final double DELTA = 1e-9;

	@Test
	public void testReflectionMatrix_xyPlane() {
		// Reflect across the XY plane (z=0). Normal = (0,0,1), point = (0,0,0)
		final double[] planePoint = {0, 0, 0};
		final double[] planeNormal = {0, 0, 1};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);

		// Reflecting (0,0,1) through z=0 should give (0,0,-1)
		// M is 4x4 homogeneous; apply to (0,0,1,1)
		final double x = M[0][0]*0 + M[0][1]*0 + M[0][2]*1 + M[0][3]*1;
		final double y = M[1][0]*0 + M[1][1]*0 + M[1][2]*1 + M[1][3]*1;
		final double z = M[2][0]*0 + M[2][1]*0 + M[2][2]*1 + M[2][3]*1;

		assertEquals(0.0, x, DELTA);
		assertEquals(0.0, y, DELTA);
		assertEquals(-1.0, z, DELTA);
	}

	@Test
	public void testReflectionMatrix_xzPlane() {
		// Reflect across the XZ plane (y=0). Normal = (0,1,0), point = (0,0,0)
		final double[] planePoint = {0, 0, 0};
		final double[] planeNormal = {0, 1, 0};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);

		// Reflecting (0,1,0) through y=0 should give (0,-1,0)
		final double x = M[0][0]*0 + M[0][1]*1 + M[0][2]*0 + M[0][3]*1;
		final double y = M[1][0]*0 + M[1][1]*1 + M[1][2]*0 + M[1][3]*1;
		final double z = M[2][0]*0 + M[2][1]*1 + M[2][2]*0 + M[2][3]*1;

		assertEquals(0.0, x, DELTA);
		assertEquals(-1.0, y, DELTA);
		assertEquals(0.0, z, DELTA);
	}

	@Test
	public void testReflectionMatrix_pointOnPlane_unchanged() {
		// A point on the plane should be unchanged after reflection
		// Plane: z=5, normal = (0,0,1), point = (0,0,5)
		final double[] planePoint = {0, 0, 5};
		final double[] planeNormal = {0, 0, 1};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);

		// Point (3,4,5) is on the plane z=5
		final double px = 3, py = 4, pz = 5;
		final double rx = M[0][0]*px + M[0][1]*py + M[0][2]*pz + M[0][3]*1;
		final double ry = M[1][0]*px + M[1][1]*py + M[1][2]*pz + M[1][3]*1;
		final double rz = M[2][0]*px + M[2][1]*py + M[2][2]*pz + M[2][3]*1;

		assertEquals(px, rx, DELTA);
		assertEquals(py, ry, DELTA);
		assertEquals(pz, rz, DELTA);
	}

	@Test
	public void testReflectionMatrix_isInvolutory() {
		// Reflecting twice should return the original point
		final double[] planePoint = {0, 0, 0};
		final double[] planeNormal = {0, 0, 1};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);

		final double px = 2, py = 3, pz = 7;
		// First reflection
		final double rx1 = M[0][0]*px + M[0][1]*py + M[0][2]*pz + M[0][3];
		final double ry1 = M[1][0]*px + M[1][1]*py + M[1][2]*pz + M[1][3];
		final double rz1 = M[2][0]*px + M[2][1]*py + M[2][2]*pz + M[2][3];
		// Second reflection
		final double rx2 = M[0][0]*rx1 + M[0][1]*ry1 + M[0][2]*rz1 + M[0][3];
		final double ry2 = M[1][0]*rx1 + M[1][1]*ry1 + M[1][2]*rz1 + M[1][3];
		final double rz2 = M[2][0]*rx1 + M[2][1]*ry1 + M[2][2]*rz1 + M[2][3];

		assertEquals(px, rx2, DELTA);
		assertEquals(py, ry2, DELTA);
		assertEquals(pz, rz2, DELTA);
	}

	@Test
	public void testReflectionMatrix_matrixSize() {
		final double[] planePoint = {0, 0, 0};
		final double[] planeNormal = {0, 0, 1};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);
		assertEquals(4, M.length);
		for (final double[] row : M) {
			assertEquals(4, row.length);
		}
	}

	@Test
	public void testReflectionMatrix_lastRow() {
		// Last row of homogeneous matrix should be [0, 0, 0, 1]
		final double[] planePoint = {1, 2, 3};
		final double[] planeNormal = {1, 0, 0};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);
		assertEquals(0.0, M[3][0], DELTA);
		assertEquals(0.0, M[3][1], DELTA);
		assertEquals(0.0, M[3][2], DELTA);
		assertEquals(1.0, M[3][3], DELTA);
	}

	@Test
	public void testReflectionMatrix_yzPlane() {
		// Reflect across the YZ plane (x=0). Normal = (1,0,0), point = (0,0,0)
		final double[] planePoint = {0, 0, 0};
		final double[] planeNormal = {1, 0, 0};
		final double[][] M = LinAlgUtils.reflectionMatrix(planePoint, planeNormal);

		// Reflecting (1,0,0) through x=0 should give (-1,0,0)
		final double x = M[0][0]*1 + M[0][1]*0 + M[0][2]*0 + M[0][3]*1;
		final double y = M[1][0]*1 + M[1][1]*0 + M[1][2]*0 + M[1][3]*1;
		final double z = M[2][0]*1 + M[2][1]*0 + M[2][2]*0 + M[2][3]*1;

		assertEquals(-1.0, x, DELTA);
		assertEquals(0.0, y, DELTA);
		assertEquals(0.0, z, DELTA);
	}
}
