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
package sc.fiji.snt;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.util.PointInImage;

/**
 * JUnit tests for Path connectivity functionality, focusing on the index-based
 * branch point system and tree transformations.
 */
public class PathConnectivityTest {

    private Path parentPath;
    private Path childPath1;
    private Path childPath2;
    private Tree tree;

    @Before
    public void setUp() {
        // Create a parent path with several nodes
        parentPath = new Path(1.0, 1.0, 1.0, "um");
        parentPath.addNode(new PointInImage(0, 0, 0));
        parentPath.addNode(new PointInImage(10, 0, 0));
        parentPath.addNode(new PointInImage(20, 0, 0));
        parentPath.addNode(new PointInImage(30, 0, 0));

        // Create first child path branching from parent at node 1 (10, 0, 0)
        childPath1 = new Path(1.0, 1.0, 1.0, "um");
        childPath1.addNode(new PointInImage(10, 10, 0));
        childPath1.addNode(new PointInImage(10, 20, 0));

        // Create second child path branching from parent at node 2 (20, 0, 0)
        childPath2 = new Path(1.0, 1.0, 1.0, "um");
        childPath2.addNode(new PointInImage(20, 10, 0));
        childPath2.addNode(new PointInImage(20, 20, 0));

        // Set up branching relationships
        childPath1.setBranchFrom(parentPath, new PointInImage(10, 0, 0));
        childPath2.setBranchFrom(parentPath, new PointInImage(20, 0, 0));

        // Create tree
        tree = new Tree();
        tree.add(parentPath);
        tree.add(childPath1);
        tree.add(childPath2);
    }

    @Test
    public void testBasicBranchPointSetup() {
        // Verify branch point indices are set correctly
        assertEquals(1, childPath1.getBranchPointIndex());
        assertEquals(2, childPath2.getBranchPointIndex());

        // Verify branch points reference actual parent nodes
        assertSame(parentPath.getNode(1), childPath1.getBranchPoint());
        assertSame(parentPath.getNode(2), childPath2.getBranchPoint());

        // Verify coordinates match
        PointInImage bp1 = childPath1.getBranchPoint();
        PointInImage bp2 = childPath2.getBranchPoint();
        
        assertEquals(10.0, bp1.x, 0.001);
        assertEquals(0.0, bp1.y, 0.001);
        assertEquals(20.0, bp2.x, 0.001);
        assertEquals(0.0, bp2.y, 0.001);
    }

    @Test
    public void testBranchPointSynchronizationAfterMovement() {
        // Move parent node 1 to a new location
        PointInImage newLocation = new PointInImage(15, 5, 2);
        parentPath.moveNode(1, newLocation);

        // Verify child1's branch point automatically reflects the change
        PointInImage bp1 = childPath1.getBranchPoint();
        assertEquals(15.0, bp1.x, 0.001);
        assertEquals(5.0, bp1.y, 0.001);
        assertEquals(2.0, bp1.z, 0.001);

        // Verify it's still the same object reference
        assertSame(parentPath.getNode(1), childPath1.getBranchPoint());

        // Verify child2's branch point is unaffected
        PointInImage bp2 = childPath2.getBranchPoint();
        assertEquals(20.0, bp2.x, 0.001);
        assertEquals(0.0, bp2.y, 0.001);
    }

    @Test
    public void testTreeTranslationPreservesBranchPoints() {
        // Record original branch point coordinates
        PointInImage originalBp1 = childPath1.getBranchPoint();
        PointInImage originalBp2 = childPath2.getBranchPoint();
        double origX1 = originalBp1.x, origY1 = originalBp1.y, origZ1 = originalBp1.z;
        double origX2 = originalBp2.x, origY2 = originalBp2.y, origZ2 = originalBp2.z;

        // Translate the entire tree
        double dx = 100, dy = 200, dz = 300;
        tree.translate(dx, dy, dz);

        // Verify branch points moved correctly
        PointInImage newBp1 = childPath1.getBranchPoint();
        PointInImage newBp2 = childPath2.getBranchPoint();

        assertEquals(origX1 + dx, newBp1.x, 0.001);
        assertEquals(origY1 + dy, newBp1.y, 0.001);
        assertEquals(origZ1 + dz, newBp1.z, 0.001);

        assertEquals(origX2 + dx, newBp2.x, 0.001);
        assertEquals(origY2 + dy, newBp2.y, 0.001);
        assertEquals(origZ2 + dz, newBp2.z, 0.001);

        // Verify branch points still reference the correct parent nodes
        assertSame(parentPath.getNode(1), childPath1.getBranchPoint());
        assertSame(parentPath.getNode(2), childPath2.getBranchPoint());

        // Verify branch point indices unchanged
        assertEquals(1, childPath1.getBranchPointIndex());
        assertEquals(2, childPath2.getBranchPointIndex());
    }

    @Test
    public void testTreeScalingPreservesBranchPoints() {
        // Scale the tree
        double scaleX = 2.0, scaleY = 3.0, scaleZ = 0.5;
        tree.scale(scaleX, scaleY, scaleZ);

        // Verify branch points scaled correctly
        PointInImage bp1 = childPath1.getBranchPoint();
        PointInImage bp2 = childPath2.getBranchPoint();

        assertEquals(20.0, bp1.x, 0.001);
        assertEquals(0.0, bp1.y, 0.001);
        assertEquals(0.0, bp1.z, 0.001);

        assertEquals(40.0, bp2.x, 0.001);
        assertEquals(0.0, bp2.y, 0.001);
        assertEquals(0.0, bp2.z, 0.001);

        // Verify connectivity is maintained
        assertSame(parentPath.getNode(1), childPath1.getBranchPoint());
        assertSame(parentPath.getNode(2), childPath2.getBranchPoint());
    }

    @Test
    public void testTreeRotationPreservesBranchPoints() {
        // Rotate 90 degrees around Z axis
        tree.rotate(Tree.Z_AXIS, 90.0);

        // After 90° rotation around Z: (x,y,z) -> (-y,x,z)
        // Parent node 1: (10,0,0) -> (0,10,0)
        // Parent node 2: (20,0,0) -> (0,20,0)
        
        PointInImage bp1 = childPath1.getBranchPoint();
        PointInImage bp2 = childPath2.getBranchPoint();

        assertEquals(0.0, bp1.x, 0.001);
        assertEquals(10.0, bp1.y, 0.001);
        assertEquals(0.0, bp1.z, 0.001);

        assertEquals(0.0, bp2.x, 0.001);
        assertEquals(20.0, bp2.y, 0.001);
        assertEquals(0.0, bp2.z, 0.001);

        // Verify connectivity is maintained
        assertSame(parentPath.getNode(1), childPath1.getBranchPoint());
        assertSame(parentPath.getNode(2), childPath2.getBranchPoint());
    }

    @Test
    public void testParentNodeRemovalUpdatesBranchPointIndices() {
        // Remove parent node 0, which should shift all indices down
        parentPath.removeNode(0);

        // Child1 was branching from node 1, now should branch from node 0
        // Child2 was branching from node 2, now should branch from node 1
        assertEquals(0, childPath1.getBranchPointIndex());
        assertEquals(1, childPath2.getBranchPointIndex());

        // Verify branch points still reference the correct nodes (same coordinates)
        PointInImage bp1 = childPath1.getBranchPoint();
        PointInImage bp2 = childPath2.getBranchPoint();

        assertEquals(10.0, bp1.x, 0.001);
        assertEquals(20.0, bp2.x, 0.001);
    }

    @Test
    public void testDetachChildFromParent() {
        // Verify initial connection
        assertSame(parentPath, childPath1.getParentPath());
        assertNotNull(childPath1.getBranchPoint());

        // Detach child1 from parent
        childPath1.detachFromParent();

        // Verify disconnection
        assertNull(childPath1.getParentPath());
        assertNull(childPath1.getBranchPoint());
        assertEquals(-1, childPath1.getBranchPointIndex());

        // Verify parent's children list updated
        assertFalse(parentPath.getChildren().contains(childPath1));
        assertTrue(parentPath.getChildren().contains(childPath2));

        // Verify child2 unaffected
        assertSame(parentPath, childPath2.getParentPath());
        assertNotNull(childPath2.getBranchPoint());
    }

    @Test
    public void testPathConnectivityQueries() {
        // Test isConnectedTo method
        assertTrue(childPath1.isConnectedTo(parentPath));
        assertTrue(childPath2.isConnectedTo(parentPath));
        assertFalse(childPath1.isConnectedTo(childPath2));

        // Test isPrimary method
        assertTrue(parentPath.isPrimary());
        assertFalse(childPath1.isPrimary());
        assertFalse(childPath2.isPrimary());

        // Test getChildren method
        assertEquals(2, parentPath.getChildren().size());
        assertTrue(parentPath.getChildren().contains(childPath1));
        assertTrue(parentPath.getChildren().contains(childPath2));
        assertEquals(0, childPath1.getChildren().size());
        assertEquals(0, childPath2.getChildren().size());
    }

    @Test
    public void testBranchPointWithInvalidIndex() {
        // Create a child with an invalid branch point index
        Path invalidChild = new Path(1.0, 1.0, 1.0, "um");
        invalidChild.addNode(new PointInImage(0, 0, 0));
        
        // Manually set invalid branch point index (this shouldn't happen in normal usage)
        invalidChild.parentPath = parentPath;
        invalidChild.branchPointIndex = 999; // Invalid index

        // getBranchPoint should return null for invalid indices
        assertNull(invalidChild.getBranchPoint());
        assertEquals(999, invalidChild.getBranchPointIndex());
    }

    @Test
    public void testTreeCloningPreservesConnectivity() {
        // Clone the tree
        Tree clonedTree = tree.clone();

        // Verify cloned tree has same structure
        assertEquals(3, clonedTree.size());

        // Find corresponding paths in cloned tree
        Path clonedParent = null;
        Path clonedChild1 = null;
        Path clonedChild2 = null;

        for (Path p : clonedTree.list()) {
            if (p.isPrimary()) {
                clonedParent = p;
            } else if (p.getBranchPointIndex() == 1) {
                clonedChild1 = p;
            } else if (p.getBranchPointIndex() == 2) {
                clonedChild2 = p;
            }
        }

        assertNotNull(clonedParent);
        assertNotNull(clonedChild1);
        assertNotNull(clonedChild2);

        // Verify connectivity in cloned tree
        assertSame(clonedParent, clonedChild1.getParentPath());
        assertSame(clonedParent, clonedChild2.getParentPath());

        // Verify branch points reference correct nodes in cloned tree
        assertSame(clonedParent.getNode(1), clonedChild1.getBranchPoint());
        assertSame(clonedParent.getNode(2), clonedChild2.getBranchPoint());

        // Verify independence from original tree
        assertNotSame(parentPath, clonedParent);
        assertNotSame(childPath1, clonedChild1);
        assertNotSame(childPath2, clonedChild2);
    }
}
