/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt.annotation;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.viewer.OBJMesh;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;


/**
 * Tests for {@link AllenCompartment}
 *
 * @author Cameron Arshadi
 */
@RunWith(Enclosed.class)
public class AllenCompartmentTest {

    @RunWith(Parameterized.class)
    public static class ParameterizedTests {

        private static final String ROOT_UUID_STRING = "464cb1ee-4664-40dc-948f-85dd1feb3e40";
        private static final UUID ROOT_UUID = UUID.fromString(ROOT_UUID_STRING);
        private static final JSONObject ROOT_JSON_OBJECT = AllenUtils.getBrainAreasByUUID().getJSONObject(ROOT_UUID_STRING);
        private static final int ROOT_STRUCTURE_ID = 997;

        @Parameterized.Parameters
        public static Iterable<AllenCompartment> data() {
            return Arrays.asList(
                    new AllenCompartment(ROOT_UUID),
                    new AllenCompartment(ROOT_STRUCTURE_ID),
                    new AllenCompartment(ROOT_JSON_OBJECT, ROOT_UUID)
            );
        }

        @Parameterized.Parameter
        public AllenCompartment rootCompartment;

        @Test
        public void testName() {
            assertEquals("Whole Brain", rootCompartment.name());
        }

        @Test
        public void testUUID() {
            assertEquals(ROOT_UUID, rootCompartment.getUUID());
        }

        @Test
        public void testId() {
            assertEquals(ROOT_STRUCTURE_ID, rootCompartment.id());
        }

        @Test
        public void testDepth() {
            assertEquals(0, rootCompartment.depth());
        }

        @Test
        public void testAcronym() {
            assertEquals("brain", rootCompartment.acronym());
        }

        @Test
        public void testAliases() {
            assertEquals(0, rootCompartment.aliases().length);
        }

        @Test
        public void testIsMeshAvailable() {
            assertTrue(rootCompartment.isMeshAvailable());
        }

        @Test
        public void testGetParent() {
            assertEquals(500, new AllenCompartment(985).getParent().id());
            assertNull(rootCompartment.getParent());
        }

        @Test
        public void testGetChildren() {
            assertEquals(AllenUtils.getOntologies().size() - 1, rootCompartment.getChildren().size());
        }

        @Test
        public void testGetChildrenInteger() {
            final Set<Integer> expectedChildren = new HashSet<>(Arrays.asList(1024, 73, 1009, 8, 304325711));
            final Set<Integer> actualChildren = rootCompartment.getChildren(1).stream()
                    .map(AllenCompartment::id)
                    .collect(Collectors.toSet());
            assertEquals(expectedChildren, actualChildren);
        }

        @Test
        public void testEquals() {
            assertEquals(rootCompartment, rootCompartment);
            assertEquals(rootCompartment, new AllenCompartment(ROOT_STRUCTURE_ID));
            assertEquals(rootCompartment, new AllenCompartment(ROOT_UUID));
            assertEquals(rootCompartment, new AllenCompartment(ROOT_JSON_OBJECT, ROOT_UUID));
            assertNotEquals(rootCompartment, new AllenCompartment(1024));
        }

        @Test
        public void testHashCode() {
            assertEquals(rootCompartment.hashCode(), new AllenCompartment(ROOT_STRUCTURE_ID).hashCode());
            assertEquals(rootCompartment.hashCode(), new AllenCompartment(ROOT_UUID).hashCode());
            assertEquals(rootCompartment.hashCode(), new AllenCompartment(ROOT_JSON_OBJECT, ROOT_UUID).hashCode());
        }

    }

    public static class SingleTests {

        @Test
        public void testGetAncestorInteger() {
            final List<Integer> treePath = Arrays.asList(997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202, 480149206);
            Collections.reverse(treePath);
            // Rostrolateral lateral visual area, layer 1
            final AllenCompartment compartment = new AllenCompartment(480149206);
            for (int i = 1; i < compartment.getOntologyDepth() + 1; i++) {
                AllenCompartment an = compartment.getAncestor(i);
                assertEquals((int) treePath.get(i), an.id());
                an = compartment.getAncestor(-i);
                assertEquals((int) treePath.get(i), an.id());
            }
        }

        @Test
        public void testGetAncestors() {
            final List<Integer> ancestorsExpected = Arrays.asList(997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202);
            // Rostrolateral lateral visual area, layer 1
            final List<Integer> ancestorsActual = new AllenCompartment(480149206).getAncestors().stream()
                    .map(AllenCompartment::id)
                    .collect(Collectors.toList());
            assertEquals(ancestorsExpected, ancestorsActual);
        }

        @Test
        public void testGetTreePath() {
            final List<Integer> treePathExpected = Arrays.asList(
                    997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202, 480149206
            );
            // Rostrolateral lateral visual area, layer 1
            final List<Integer> treePathActual = new AllenCompartment(480149206).getTreePath().stream()
                    .map(AllenCompartment::id)
                    .collect(Collectors.toList());
            assertEquals(treePathExpected, treePathActual);
        }


        @Test
        public void testIsChildOf() {
            final List<Integer> treePath = Arrays.asList(997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202, 480149206);
            final List<AllenCompartment> compartmentList = treePath.stream()
                    .map(AllenCompartment::new)
                    .collect(Collectors.toList());
            for (int i = 0; i < compartmentList.size() - 1; i++) {
                final AllenCompartment parent = compartmentList.get(i);
                for (final AllenCompartment child : compartmentList.subList(i + 1, compartmentList.size())) {
                    assertTrue(child.isChildOf(parent));
                }
            }
            final AllenCompartment compartment = compartmentList.get(1);
            assertFalse(compartment.isChildOf(new AllenCompartment(treePath.get(1))));
            assertFalse(compartment.isChildOf(compartmentList.get(2)));
        }

        @Test
        public void testIsParentOf() {
            final List<Integer> treePath = Arrays.asList(997, 8, 567, 688, 695, 315, 453, 322, 329, 480149202, 480149206);
            final List<AllenCompartment> compartmentList = treePath.stream()
                    .map(AllenCompartment::new)
                    .collect(Collectors.toList());
            for (int i = 0; i < compartmentList.size() - 1; i++) {
                final AllenCompartment parent = compartmentList.get(i);
                for (final AllenCompartment child : compartmentList.subList(i + 1, compartmentList.size())) {
                    assertTrue(parent.isParentOf(child));
                }
            }
            final AllenCompartment compartment = compartmentList.get(1);
            assertFalse(compartment.isParentOf(new AllenCompartment(treePath.get(1))));
            assertFalse(compartment.isParentOf(compartmentList.get(0)));
        }

        @Test
        public void testGetOntologyDepth() {
            // Parabrachial nucleus, lateral division, dorsal lateral part
            final AllenCompartment compartment = new AllenCompartment(868);
            assertEquals(8, compartment.getOntologyDepth());
        }

        @Test
        public void testGetMesh() {
            assumeTrue(MouseLightLoader.isDatabaseAvailable());
            // Parabrachial nucleus, lateral division, dorsal lateral part
            AllenCompartment compartment = new AllenCompartment(868);
            assertFalse(compartment.isMeshAvailable());
            OBJMesh mesh = compartment.getMesh();
            assertNull(mesh);

            // Primary somatosensory area, upper limb, layer 5
            compartment = new AllenCompartment(625);
            assertTrue(compartment.isMeshAvailable());
            mesh = compartment.getMesh();
            assertNotNull(mesh);
            assertEquals(1305537356.8650255d, mesh.getVolume(), 0.0000001);
        }

    }

}
