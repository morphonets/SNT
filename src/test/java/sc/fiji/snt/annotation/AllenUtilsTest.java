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

package sc.fiji.snt.annotation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link AllenUtils}
 *
 * @author Cameron Arshadi
 */
public class AllenUtilsTest {

    @Test
    public void testGetOntologies() {
        final List<AllenCompartment> ontologies = AllenUtils.getOntologies();
        assertEquals(1287, ontologies.size());
    }

    @Test
    public void testGetBrainAreasList() {
        JSONArray brainAreasList = AllenUtils.getBrainAreasList();
        assertEquals(1287, brainAreasList.length());
        for (int i = 0; i < brainAreasList.length(); i++) {
            JSONObject jsonObject = brainAreasList.getJSONObject(i);
            testJSONObject(jsonObject);
        }
    }

    @Test
    public void testGetBrainAreasByStructureId() {
        JSONObject brainAreas = AllenUtils.getBrainAreasByStructureId();
        assertEquals(1287, brainAreas.length());
        Iterator<String> ids = brainAreas.keys();
        while (ids.hasNext()) {
            String idString = ids.next();
            int idInt = Integer.parseInt(idString); // just check if an Integer is parsable
            JSONObject jsonObj = brainAreas.getJSONObject(idString);
            testJSONObject(jsonObj);
        }
    }

    @Test
    public void testGetBrainAreasByUUID() {
        JSONObject brainAreas = AllenUtils.getBrainAreasByUUID();
        assertEquals(1287, brainAreas.length());
        Iterator<String> uuids = brainAreas.keys();
        while (uuids.hasNext()) {
            String uuidString = uuids.next();
            JSONObject jsonObj = brainAreas.getJSONObject(uuidString);
            testJSONObject(jsonObj);
        }
    }

    private void testJSONObject(final JSONObject jsonObject) {
        assertFalse(jsonObject.getString("id").isEmpty());

        assertFalse(jsonObject.getString("name").isEmpty());

        assertTrue(jsonObject.get("structureId") instanceof Integer);

        assertTrue(jsonObject.get("depth") instanceof Integer);

        assertTrue(jsonObject.get("parentStructureId") instanceof Integer ||
                JSONObject.NULL.equals(jsonObject.get("parentStructureId")));

        assertFalse(jsonObject.getString("structureIdPath").isEmpty());

        assertFalse(jsonObject.getString("safeName").isEmpty());

        assertFalse(jsonObject.getString("acronym").isEmpty());

        assertTrue(jsonObject.get("aliases") instanceof JSONArray);

        assertTrue(jsonObject.get("atlasId") instanceof Integer ||
                JSONObject.NULL.equals(jsonObject.get("atlasId")));

        assertTrue(jsonObject.get("graphOrder") instanceof Integer ||
                JSONObject.NULL.equals(jsonObject.get("graphOrder")));

        assertFalse(jsonObject.getString("geometryFile").isEmpty());

        assertFalse(jsonObject.getString("geometryColor").isEmpty());

        assertTrue(jsonObject.get("geometryEnable") instanceof Boolean);

        assertTrue(JSONObject.NULL.equals(jsonObject.get("geometryVolume")) ||
                jsonObject.getDouble("geometryVolume") > 0);
    }

}
