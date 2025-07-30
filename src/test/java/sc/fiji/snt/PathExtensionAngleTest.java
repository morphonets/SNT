/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import org.junit.Before;
import org.junit.Test;
import sc.fiji.snt.util.PointInImage;

import static org.junit.Assert.*;

/**
 * Test class for Path extension angle calculations.
 * SNT follows Navigation/Compass convention for absolut angles:
 * <p>
 * 0°: paths pointing North; 90°: paths pointing East; 180°: paths pointing South; 270°: paths pointing West.
 * </p>
 */
public class PathExtensionAngleTest {

    private static final double EPSILON = 1e-10;
    private Path path;

    @Before
    public void setUp() {
        // Create a path with 1μm isotropic spacing
        path = new Path(1.0, 1.0, 1.0, "μm");
    }

    @Test
    public void testHorizontalEastPath() {
        // Create a path pointing east (positive X direction)
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(10, 0, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("East-pointing path should have 90°", 90d, angle, EPSILON);
    }

    @Test
    public void testHorizontalWestPath() {
        // Create a path pointing west (negative X direction)
        path.addNode(new PointInImage(10, 0, 0));
        path.addNode(new PointInImage(0, 0, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("West-pointing path should have 270°", 270d, angle, EPSILON);
    }

    @Test
    public void testVerticalNorthPath() {
        // Create a path pointing north (negative Y in image coordinates, positive in math coordinates)
        path.addNode(new PointInImage(0, 10, 0));
        path.addNode(new PointInImage(0, 0, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("North-pointing path should have 0°", 0d, angle, EPSILON);
    }

    @Test
    public void testVerticalSouthPath() {
        // Create a path pointing south (positive Y in image coordinates, negative in math coordinates)
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(0, 10, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("South-pointing path should have 180°", 180d, angle, EPSILON);
    }

    @Test
    public void testNorthEastDiagonalPath() {
        // Create a path pointing northeast (45 degrees)
        path.addNode(new PointInImage(0, 10, 0));
        path.addNode(new PointInImage(10, 0, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("Northeast-pointing path should have 45°", 45d, angle, EPSILON);
    }


    @Test
    public void testSouthWestDiagonalPath() {
        // Create a path pointing southwest (225 degrees)
        path.addNode(new PointInImage(10, 0, 0));
        path.addNode(new PointInImage(0, 10, 0));
        double angle = path.getExtensionAngleXY();
        assertEquals("Southwest-pointing path should have 225°", 225d, angle, EPSILON);
    }

    @Test
    public void testNorthSouthDistinction() {
        // Test that north and south pointing paths have different angles
        Path northPath = new Path(1.0, 1.0, 1.0, "μm");
        northPath.addNode(new PointInImage(0, 10, 0));
        northPath.addNode(new PointInImage(0, 0, 0));

        Path southPath = new Path(1.0, 1.0, 1.0, "μm");
        southPath.addNode(new PointInImage(0, 0, 0));
        southPath.addNode(new PointInImage(0, 10, 0));

        double northAngle = northPath.getExtensionAngleXY();
        double southAngle = southPath.getExtensionAngleXY();

        assertNotEquals("North and south pointing paths should have different angles", northAngle, southAngle);
        assertEquals("North angle should be 0°", 0d, northAngle, EPSILON);
        assertEquals("South angle should be 180°", 180d, southAngle, EPSILON);
    }

    @Test
    public void testAngleRange() {
        // Test that all angles are in the range [0, 360[
        Path[] testPaths = {
                createPath(0, 0, 10, 0),    // East
                createPath(0, 0, 10, 10),   // Northeast
                createPath(0, 0, 0, -10),   // North (negative Y in image coords)
                createPath(0, 0, -10, -10), // Northwest
                createPath(0, 0, -10, 0),   // West
                createPath(0, 0, -10, 10),  // Southwest
                createPath(0, 0, 0, 10),    // South
                createPath(0, 0, 10, -10)   // Southeast
        };

        for (Path testPath : testPaths) {
            double angle = testPath.getExtensionAngleXY();
            assertTrue("Angle should be >= 0", angle >= 0d);
            assertTrue("Angle should be < 360", angle < 360d);
        }
    }

    @Test
    public void testSinglePointPath() {
        path.addNode(new PointInImage(0, 0, 0));
        double angle = path.getExtensionAngleXY();
        assertTrue("Single point path should return NaN", Double.isNaN(angle));
    }

    @Test
    public void test3DAngles() {
        // Test all 8 major compass directions in 3D
        // Note: In image coordinates, Y increases downward, so North is negative Y direction

        // North (0°) - negative Y direction in image coordinates
        Path northPath = create3DPath(0, 10, 0, 0, 0, 5);
        double[] northAngles = northPath.getExtensionAngles3D();
        assertNotNull("North 3D angles should not be null", northAngles);
        assertEquals("Should return azimuth and elevation", 2, northAngles.length);
        assertEquals("North azimuth should be 0°", 0.0, northAngles[0], EPSILON);

        // Northeast (45°) - negative Y, positive X
        Path northeastPath = create3DPath(0, 10, 0, 10, 0, 5);
        double[] northeastAngles = northeastPath.getExtensionAngles3D();
        assertEquals("Northeast azimuth should be 45°", 45.0, northeastAngles[0], EPSILON);

        // East (90°) - positive X direction
        Path eastPath = create3DPath(0, 0, 0, 10, 0, 5);
        double[] eastAngles = eastPath.getExtensionAngles3D();
        assertEquals("East azimuth should be 90°", 90.0, eastAngles[0], EPSILON);

        // Southeast (135°) - positive X, positive Y
        Path southeastPath = create3DPath(0, 0, 0, 10, 10, 5);
        double[] southeastAngles = southeastPath.getExtensionAngles3D();
        assertEquals("Southeast azimuth should be 135°", 135.0, southeastAngles[0], EPSILON);

        // South (180°) - positive Y direction in image coordinates
        Path southPath = create3DPath(0, 0, 0, 0, 10, 5);
        double[] southAngles = southPath.getExtensionAngles3D();
        assertEquals("South azimuth should be 180°", 180.0, southAngles[0], EPSILON);

        // Southwest (225°) - negative X, positive Y
        Path southwestPath = create3DPath(0, 0, 0, -10, 10, 5);
        double[] southwestAngles = southwestPath.getExtensionAngles3D();
        assertEquals("Southwest azimuth should be 225°", 225.0, southwestAngles[0], EPSILON);

        // West (270°) - negative X direction
        Path westPath = create3DPath(0, 0, 0, -10, 0, 5);
        double[] westAngles = westPath.getExtensionAngles3D();
        assertEquals("West azimuth should be 270°", 270.0, westAngles[0], EPSILON);

        // Northwest (315°) - negative X, negative Y
        Path northwestPath = create3DPath(0, 0, 0, -10, -10, 5);
        double[] northwestAngles = northwestPath.getExtensionAngles3D();
        assertEquals("Northwest azimuth should be 315°", 315.0, northwestAngles[0], EPSILON);

        // Test elevation angles are positive for upward paths
        for (double[] angles : new double[][]{northAngles, northeastAngles, eastAngles, southeastAngles,
                southAngles, southwestAngles, westAngles, northwestAngles}) {
            assertTrue("Elevation should be positive for upward paths", angles[1] > 0);
        }
    }

    @Test
    public void test3DCompassAngleEast() {
        // Create a 3D path pointing east
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(10, 0, 5));
        double angle = path.getExtensionAngle3D(false);
        assertEquals("East-pointing 3D path should have 90°", 90d, angle, EPSILON);
    }

    @Test
    public void test3DCompassAngleWest() {
        // Create a 3D path pointing west
        path.addNode(new PointInImage(10, 0, 0));
        path.addNode(new PointInImage(0, 0, 5));
        double angle = path.getExtensionAngle3D(false);
        assertEquals("West-pointing 3D path should have 270°", 270d, angle, EPSILON);
    }

    @Test
    public void test3DCompassAngleNorth() {
        // Create a 3D path pointing north (negative Y in image coordinates)
        path.addNode(new PointInImage(0, 10, 0));
        path.addNode(new PointInImage(0, 0, 5));
        double angle = path.getExtensionAngle3D(false);
        assertEquals("North-pointing 3D path should have 0°", 0d, angle, EPSILON);
    }

    @Test
    public void test3DCompassAngleSouth() {
        // Create a 3D path pointing south (positive Y in image coordinates)
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(0, 10, 5));
        double angle = path.getExtensionAngle3D(false);
        assertEquals("South-pointing 3D path should have 180°", 180d, angle, EPSILON);
    }

    @Test
    public void test3DCompassAngleNorthEast() {
        // Create a 3D path pointing northeast
        path.addNode(new PointInImage(0, 10, 0));
        path.addNode(new PointInImage(10, 0, 5));
        double angle = path.getExtensionAngle3D(false);
        assertEquals("Northeast-pointing 3D path should have 45°", 45d, angle, EPSILON);
    }

    @Test
    public void test3DCompassAngleSinglePoint() {
        path.addNode(new PointInImage(0, 0, 0));
        double angle = path.getExtensionAngle3D(false);
        assertTrue("Single point 3D path should return NaN", Double.isNaN(angle));
    }

    @Test
    public void test3DCompassAngleRange() {
        // Test that 3D compass angles are in the range [0, 360[
        Path[] testPaths = {
                create3DPath(0, 0, 0, 10, 0, 5),    // East
                create3DPath(0, 0, 0, 10, -10, 5),  // Northeast
                create3DPath(0, 0, 0, 0, -10, 5),   // North
                create3DPath(0, 0, 0, -10, -10, 5), // Northwest
                create3DPath(0, 0, 0, -10, 0, 5),   // West
                create3DPath(0, 0, 0, -10, 10, 5),  // Southwest
                create3DPath(0, 0, 0, 0, 10, 5),    // South
                create3DPath(0, 0, 0, 10, 10, 5)    // Southeast
        };

        for (Path testPath : testPaths) {
            double angle = testPath.getExtensionAngle3D(false);
            assertTrue("3D compass angle should be >= 0", angle >= 0d);
            assertTrue("3D compass angle should be < 360", angle < 360d);
        }
    }

    private Path createPath(double x1, double y1, double x2, double y2) {
        Path p = new Path(1.0, 1.0, 1.0, "μm");
        p.addNode(new PointInImage(x1, y1, 0));
        p.addNode(new PointInImage(x2, y2, 0));
        return p;
    }

    private Path create3DPath(double x1, double y1, double z1, double x2, double y2, double z2) {
        Path p = new Path(1.0, 1.0, 1.0, "μm");
        p.addNode(new PointInImage(x1, y1, z1));
        p.addNode(new PointInImage(x2, y2, z2));
        return p;
    }


    // ========== 3D RELATIVE ANGLE TESTS ==========
    @Test
    public void testExtensionAngle3D_RelativeNoParent() {
        // Test that relative 3D angle returns NaN when path has no parent
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(10, 0, 5));

        double relativeAngle = path.getExtensionAngle3D(true);
        assertTrue("3D relative angle should be NaN when path has no parent", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngle3D_RelativeParallel3D() {
        // Create parent path pointing northeast and upward
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 10, 0));
        parentPath.addNode(new PointInImage(10, 0, 10));

        // Create child path with same 3D direction (parallel)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 5, 5));
        childPath.addNode(new PointInImage(15, -5, 15));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 5, 5));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Parallel 3D paths should have 0° relative angle", 0.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3D_RelativePerpendicular3D() {
        // Create parent path pointing east horizontally
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path pointing straight up (perpendicular in 3D)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, 0, 10));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Perpendicular 3D paths should have 90° relative angle", 90.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3D_RelativeOpposite3D() {
        // Create parent path pointing northeast and upward
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 10, 0));
        parentPath.addNode(new PointInImage(10, 0, 10));

        // Create child path pointing in opposite 3D direction
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 5, 5));
        childPath.addNode(new PointInImage(-5, 15, -5));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 5, 5));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Opposite 3D paths should have 180° relative angle", 180.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3DRelative_45Degree3D() {
        // Create parent path pointing east horizontally
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path at 45° angle in 3D space (east and up equally)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(15, 0, 10)); // 45° elevation from horizontal

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("45° 3D branching should have 45° relative angle", 45.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3D_RelativeVerticalToHorizontal() {
        // Create parent path pointing straight up
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(0, 0, 10));

        // Create child path pointing horizontally east
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(0, 0, 5));
        childPath.addNode(new PointInImage(10, 0, 5));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(0, 0, 5));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Vertical to horizontal should have 90° relative angle", 90.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3D_RelativeComplexBranching() {
        // Create parent path pointing northeast and upward (complex 3D direction)
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 10, 0));
        parentPath.addNode(new PointInImage(6, 2, 8)); // Northeast, up, slight south

        // Create child path branching at specific angle
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(3, 6, 4));
        childPath.addNode(new PointInImage(9, 6, 12)); // East and up

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(3, 6, 4));

        double relativeAngle = childPath.getExtensionAngle3D(true);

        // The angle should be between 0 and 180 degrees (acute angle)
        assertTrue("Complex 3D branching angle should be >= 0", relativeAngle >= 0);
        assertTrue("Complex 3D branching angle should be <= 180", relativeAngle <= 180);
        assertFalse("Complex 3D branching angle should not be NaN", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngle3D_RelativeVsAbsolute() {
        // Test that relative=false returns compass bearing, relative=true returns angle to parent
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0)); // East

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, -10, 0)); // North
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double absoluteAngle = childPath.getExtensionAngle3D(false);
        double relativeAngle = childPath.getExtensionAngle3D(true);

        assertEquals("Absolute angle should be 0° (North)", 0.0, absoluteAngle, EPSILON);
        assertEquals("Relative angle should be 90° (perpendicular to east)", 90.0, relativeAngle, EPSILON);

        // They should be different for this configuration
        assertNotEquals("Absolute and relative angles should differ", absoluteAngle, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3D_RelativeRange() {
        // Test that 3D relative angles are always in [0, 180] range
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 5)); // East and up

        // Test various 3D child directions
        double[][] childDirections = {
                {10, 0, 5},     // Same as parent (parallel)
                {10, -10, 5},   // Northeast and up
                {0, -10, 5},    // North and up
                {-10, -10, 5},  // Northwest and up
                {-10, 0, 5},    // West and up
                {-10, 10, 5},   // Southwest and up
                {0, 10, 5},     // South and up
                {10, 10, 5},    // Southeast and up
                {0, 0, 10},     // Straight up
                {0, 0, -10},    // Straight down
                {10, 0, -5}     // East and down
        };

        for (double[] direction : childDirections) {
            Path childPath = new Path(1.0, 1.0, 1.0, "μm");
            childPath.addNode(new PointInImage(5, 0, 2.5));
            childPath.addNode(new PointInImage(5 + direction[0], direction[1], 2.5 + direction[2]));
            childPath.setStartJoin(parentPath, new PointInImage(5, 0, 2.5));

            double relativeAngle = childPath.getExtensionAngle3D(true);

            assertTrue("3D relative angle should be >= 0", relativeAngle >= 0);
            assertTrue("3D relative angle should be <= 180", relativeAngle <= 180);
            assertFalse("3D relative angle should not be NaN", Double.isNaN(relativeAngle));
        }
    }

    @Test
    public void testExtensionAngle3D_RelativeSinglePoint() {
        // Test single-point child path
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 5));

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 2.5)); // Single point
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 2.5));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertTrue("Single-node child should return NaN for 3D relative angle", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngle3D_RelativeSinglePointParent() {
        // Test when parent has single point (should return NaN)
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0)); // Single point parent

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(0, 0, 0));
        childPath.addNode(new PointInImage(10, 0, 5));
        childPath.setStartJoin(parentPath, new PointInImage(0, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertTrue("Child w/ single node parent should return NaN for 3D relative angle", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngle3D_RelativeSymmetry() {
        // Test that the angle between A->B is the same as B->A
        Path pathA = new Path(1.0, 1.0, 1.0, "μm");
        pathA.addNode(new PointInImage(0, 0, 0));
        pathA.addNode(new PointInImage(10, 0, 0)); // East

        Path pathB = new Path(1.0, 1.0, 1.0, "μm");
        pathB.addNode(new PointInImage(5, 0, 0));
        pathB.addNode(new PointInImage(5, -10, 5)); // North and up

        // Test A as parent, B as child
        pathB.setStartJoin(pathA, new PointInImage(5, 0, 0));
        double angleAtoB = pathB.getExtensionAngle3D(true);

        // Reset and test B as parent, A as child (reverse relationship)
        pathB.unsetStartJoin();
        Path pathA2 = new Path(1.0, 1.0, 1.0, "μm");
        pathA2.addNode(new PointInImage(5, 0, 0));
        pathA2.addNode(new PointInImage(15, 0, 0)); // Continue east from branch point
        pathA2.setStartJoin(pathB, new PointInImage(5, 0, 0));
        double angleBtoA = pathA2.getExtensionAngle3D(true);

        // The angles should be the same (symmetric)
        assertEquals("3D relative angles should be symmetric", angleAtoB, angleBtoA, EPSILON);
    }

    // ========== 2D RELATIVE ANGLE TESTS ==========

    @Test
    public void testExtensionAngleXY_RelativeNoParent() {
        // Test that relative XY angle returns NaN when path has no parent
        path.addNode(new PointInImage(0, 0, 0));
        path.addNode(new PointInImage(10, 0, 0));

        double relativeAngle = path.getExtensionAngle3D(true);
        assertTrue("XY relative angle should be NaN when path has no parent", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngleXY_RelativeParallel() {
        // Create parent path pointing east
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path with same direction (parallel)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(15, 0, 0));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Parallel XY paths should have 0° relative angle", 0.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngleXY_RelativePerpendicular() {
        // Create parent path pointing east
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path pointing north (perpendicular)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, -10, 0));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Perpendicular XY paths should have 90° relative angle", 90.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngleXY_RelativeOpposite() {
        // Create parent path pointing east
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path pointing west (opposite)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(-5, 0, 0));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("Opposite XY paths should have 180° relative angle", 180.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle3DRelative_45Degree() {
        // Create parent path pointing east
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        // Create child path at 45° angle (northeast)
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(15, -10, 0));

        // Set up parent-child relationship
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("45° XY branching should have 45° relative angle", 45.0, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngleXY_RelativeVsAbsolute() {
        // Test that relative=false returns compass bearing, relative=true returns angle to parent
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0)); // East

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, -10, 0)); // North
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double absoluteAngle = childPath.getExtensionAngle3D(false);
        double relativeAngle = childPath.getExtensionAngle3D(true);

        assertEquals("Absolute XY angle should be 0° (North)", 0.0, absoluteAngle, EPSILON);
        assertEquals("Relative XY angle should be 90° (perpendicular to east)", 90.0, relativeAngle, EPSILON);

        // They should be different for this configuration
        assertNotEquals("Absolute and relative XY angles should differ", absoluteAngle, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngleXYRelative_Range() {
        // Test that XY relative angles are always in [0, 180] range
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0)); // East

        // Test various child directions
        double[][] childDirections = {
                {10, 0},     // Same as parent (parallel)
                {10, -10},   // Northeast
                {0, -10},    // North
                {-10, -10},  // Northwest
                {-10, 0},    // West
                {-10, 10},   // Southwest
                {0, 10},     // South
                {10, 10}     // Southeast
        };

        for (double[] direction : childDirections) {
            Path childPath = new Path(1.0, 1.0, 1.0, "μm");
            childPath.addNode(new PointInImage(5, 0, 0));
            childPath.addNode(new PointInImage(5 + direction[0], direction[1], 0));
            childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

            double relativeAngle = childPath.getExtensionAngle3D(true);

            assertTrue("XY relative angle should be >= 0", relativeAngle >= 0);
            assertTrue("XY relative angle should be <= 180", relativeAngle <= 180);
            assertFalse("XY relative angle should not be NaN", Double.isNaN(relativeAngle));
        }
    }

    @Test
    public void testExtensionAngle3DRelative_Basic() {
        // Test XZ plane relative angles
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0)); // East in XZ plane

        // Child pointing up in Z direction
        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, 0, 10));
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertEquals("XZ perpendicular paths should have 90° relative angle", 90.0, relativeAngle, EPSILON);
    }


    @Test
    public void testAzimuthAndElevationAngles() {
        Path path = new Path(1.0, 1.0, 1.0, "μm");
        path.addNode(new PointInImage(0, 0, 0));
        // Second point to achieve 45° azimuth and 30° elevation
        // direction.x = 1, direction.y = -1, direction.z = sqrt(2/3) ≈ 0.8165
        path.addNode(new PointInImage(1, -1, Math.sqrt(2.0/3.0)));
        double[] angles = path.getExtensionAngles3D();
        assertEquals("azimuth angle should be", 45d, angles[0], EPSILON);
        assertEquals("elevation angle should be", 30d, angles[1], EPSILON);
        assertEquals("azimuth angle should be same as XY angle", angles[0], path.getExtensionAngleXY(), EPSILON);
    }

    @Test
    public void testExtensionAngleZY_Basic() {
        // Test ZY plane relative angles using 3D relative angle method
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(0, 0, 10)); // Up in ZY plane
        double relativeAngle = parentPath.getExtensionAngleZY();
        assertEquals("ZY path should have 90°", 90d, relativeAngle, EPSILON);
    }

    @Test
    public void testExtensionAngle2D_SinglePoint() {
        // Test single-point child path
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0)); // Single point

        double relativeAngleXY = childPath.getExtensionAngleXY();
        double relativeAngleXZ = childPath.getExtensionAngleXZ();
        double relativeAngleZY = childPath.getExtensionAngleZY();

        assertTrue("Single-node child should return NaN for XY relative angle", Double.isNaN(relativeAngleXY));
        assertTrue("Single-node child should return NaN for XZ relative angle", Double.isNaN(relativeAngleXZ));
        assertTrue("Single-node child should return NaN for ZY relative angle", Double.isNaN(relativeAngleZY));
    }

    @Test
    public void testExtensionAngle3DRelative_SinglePointParent() {
        // Test when parent has single point
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0)); // Single point parent

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(0, 0, 0));
        childPath.addNode(new PointInImage(10, 0, 0));
        childPath.setStartJoin(parentPath, new PointInImage(0, 0, 0));

        double relativeAngle = childPath.getExtensionAngle3D(true);
        assertTrue("Child w/ single node parent should return NaN for XY relative angle", Double.isNaN(relativeAngle));
    }

    @Test
    public void testExtensionAngle2D_ConsistencyWith3D() {
        // Test that 2D relative angles are consistent with 3D calculations
        // when paths are coplanar
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0)); // East

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(5, 0, 0));
        childPath.addNode(new PointInImage(5, -10, 0)); // North (coplanar in XY)
        childPath.setStartJoin(parentPath, new PointInImage(5, 0, 0));

        double relativeAngleXY = childPath.getExtensionAngleXY();
        double relativeAngle3D = childPath.getExtensionAngle3D(false);

        // For coplanar paths, 2D and 3D relative angles should be the same
        assertEquals("2D XY and 3D relative angles should match for coplanar paths", 
                     relativeAngle3D, relativeAngleXY, EPSILON);
    }

    @Test
    public void testExtensionAngleXY_ComplexBranching() {
        // Test complex branching scenarios in 2D
        Path parentPath = new Path(1.0, 1.0, 1.0, "μm");
        parentPath.addNode(new PointInImage(0, 10, 0));
        parentPath.addNode(new PointInImage(6, 2, 0)); // Northeast

        Path childPath = new Path(1.0, 1.0, 1.0, "μm");
        childPath.addNode(new PointInImage(3, 6, 0));
        childPath.addNode(new PointInImage(9, 6, 0)); // East
        childPath.setStartJoin(parentPath, new PointInImage(3, 6, 0));

        double relativeAngle = childPath.getExtensionAngleXY();

        // The angle should be between 0 and 180 degrees (acute angle)
        assertTrue("Complex 2D branching angle should be >= 0", relativeAngle >= 0);
        assertTrue("Complex 2D branching angle should be <= 180", relativeAngle <= 180);
    }
}