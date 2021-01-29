/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import org.junit.Test;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.viewer.OBJMesh;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class AllenCompartmentTest {

    @Test
    public void testInitializationUUID() {
        String uuidString = "464cb1ee-4664-40dc-948f-85dd1feb3e40";
        UUID uuid = UUID.fromString(uuidString);
        AllenCompartment compartment = new AllenCompartment(uuid);
        assertEquals("Whole Brain", compartment.name());
        assertEquals(uuid, compartment.getUUID());
        assertEquals(997, compartment.id());
        assertEquals(0, compartment.getOntologyDepth());
        assertEquals("brain", compartment.acronym());
        assertEquals(0, compartment.aliases().length);
        assertTrue(compartment.isMeshAvailable());
    }

    @Test
    public void testInitializationStructureId() {
        int id = 997;
        AllenCompartment compartment = new AllenCompartment(id);
        assertEquals("Whole Brain", compartment.name());
        assertEquals(UUID.fromString("464cb1ee-4664-40dc-948f-85dd1feb3e40"), compartment.getUUID());
        assertEquals(id, compartment.id());
        assertEquals(0, compartment.getOntologyDepth());
        assertEquals("brain", compartment.acronym());
        assertEquals(0, compartment.aliases().length);
        assertTrue(compartment.isMeshAvailable());
    }

    @Test
    public void testGetParent() {
        int id = 985; // Primary motor area
        AllenCompartment compartment = new AllenCompartment(id);
        AllenCompartment parent = compartment.getParent();
        assertEquals(500, parent.id());
    }

    @Test
    public void testGetAncestorLevel() {
        int id = 480149206; // Rostrolateral lateral visual area, layer 1
        int[] ancestors = new int[]{997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202};
        AllenCompartment compartment = new AllenCompartment(id);
        int depth = compartment.getOntologyDepth();
        int j = depth - 1;
        for (int i = 1; i < depth + 1; i++) {
            AllenCompartment an = compartment.getAncestor(i);
            assertEquals(ancestors[j], an.id());
            j--;
        }
    }

    @Test
    public void testGetAncestors() {
        int id = 480149206; // Rostrolateral lateral visual area, layer 1
        int[] ancestorsExpected = new int[]{997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202};
        AllenCompartment compartment = new AllenCompartment(id);
        List<AllenCompartment> ancestorsActual = compartment.getAncestors();
        assertEquals(ancestorsExpected.length, ancestorsActual.size());
        for (int i = 0; i < ancestorsExpected.length; i++) {
            assertEquals(ancestorsExpected[i], ancestorsActual.get(i).id());
        }
    }

    @Test
    public void testGetTreePath() {
        int id = 480149206; // Rostrolateral lateral visual area, layer 1
        int[] treePathExpected = new int[]{997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202, 480149206};
        AllenCompartment compartment = new AllenCompartment(id);
        List<AllenCompartment> treePathActual = compartment.getTreePath();
        assertEquals(treePathExpected.length, treePathActual.size());
        for (int i = 0; i < treePathExpected.length; i++) {
            assertEquals(treePathExpected[i], treePathActual.get(i).id());
        }
    }

    @Test
    public void testGetChildren() {
        final int totalNumCompartments = AllenUtils.getOntologies().size();
        AllenCompartment compartment = new AllenCompartment(997);
        final int numWholeBrainChildren = compartment.getChildren().size();
        assertEquals(totalNumCompartments - 1, numWholeBrainChildren);
    }

    @Test
    public void testGetChildrenLevel() {
        List<Integer> expectedChildren = Arrays.asList(1024, 73, 1009, 8, 304325711);
        AllenCompartment compartment = new AllenCompartment(997);
        List<AllenCompartment> children = compartment.getChildren(1);
        assertEquals(expectedChildren.size(), children.size());
        for (AllenCompartment child : children) {
            assertTrue(expectedChildren.contains(child.id()));
        }
    }

    @Test
    public void testIsChildOf() {
        AllenCompartment parent = new AllenCompartment(997);
        AllenCompartment child = new AllenCompartment(1024);
        assertTrue(child.isChildOf(parent));
        assertFalse(child.isChildOf(child));
    }

    @Test
    public void testIsParentOf() {
        AllenCompartment parent = new AllenCompartment(997);
        AllenCompartment child = new AllenCompartment(1024);
        assertTrue(parent.isParentOf(child));
        assertFalse(parent.isParentOf(parent));
    }

    @Test
    public void testGetOntologyDepth() {
        int id = 868; // Parabrachial nucleus, lateral division, dorsal lateral part
        AllenCompartment compartment = new AllenCompartment(id);
        assertEquals(8, compartment.getOntologyDepth());
    }

    @Test
    public void testGetMesh() {
        int id = 868; // Parabrachial nucleus, lateral division, dorsal lateral part
        AllenCompartment compartment = new AllenCompartment(id);
        assertFalse(compartment.isMeshAvailable());
        OBJMesh mesh = compartment.getMesh();
        assertNull(mesh);

        id = 625;  // Primary somatosensory area, upper limb, layer 5
        compartment = new AllenCompartment(id);
        assertTrue(compartment.isMeshAvailable());
        mesh = compartment.getMesh();
        assertNotNull(mesh);
        assertEquals(1305537356.8650255d, mesh.getVolume(), 0.0000001);
    }

    @Test
    public void testEquals() {
        int id = 997;
        AllenCompartment compartment = new AllenCompartment(id);
        AllenCompartment compartmentClone = new AllenCompartment(id);
        assertEquals(compartment, compartment);
        assertEquals(compartment, compartmentClone);
    }

}
