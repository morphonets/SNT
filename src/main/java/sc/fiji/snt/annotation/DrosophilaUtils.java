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

package sc.fiji.snt.annotation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Utility methods for accessing the Drosophila adult brain ontology
 * derived from FBbt (Drosophila Anatomy Ontology).
 *
 * @author Tiago Ferreira
 */
public final class DrosophilaUtils {

    /**
     * The resource path for the brain areas JSON file.
     */
    private static final String RESOURCE_PATH = "ml/drosophilaBrainAreas.json";

    /**
     * Root FBbt numeric ID for "adult brain".
     */
    public static final int BRAIN_ROOT_ID = 3624;

    private static JSONArray areaList;

    private DrosophilaUtils() {
    }

    private static JSONObject getJSONResource() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        classLoader = (classLoader != null) ? classLoader : DrosophilaUtils.class.getClassLoader();
        final InputStream is = classLoader.getResourceAsStream(RESOURCE_PATH);
        if (is == null) throw new IllegalStateException("Resource not found: " + RESOURCE_PATH);
        final JSONTokener tokener = new JSONTokener(is);
        final JSONObject json = new JSONObject(tokener);
        try {
            is.close();
        } catch (final IOException ignored) {
        }
        return json;
    }

    /**
     * Returns the list of brain area entries from the bundled JSON.
     *
     * @return the JSON array of brain area objects
     */
    public static JSONArray getBrainAreasList() {
        if (areaList == null) {
            final JSONObject json = getJSONResource();
            areaList = json.getJSONArray("brainAreas");
        }
        return areaList;
    }

    /**
     * Returns a {@link DrosophilaCompartment} for the given FBbt numeric ID.
     *
     * @param id the numeric FBbt ID (e.g., 3624 for "adult brain")
     * @return the compartment, or {@code null} if not found
     */
    public static DrosophilaCompartment getCompartment(final int id) {
        ensureMapPopulated();
        return DrosophilaCompartment.compartmentMap.get(id);
    }

    /**
     * Returns all compartments in the ontology.
     *
     * @return an unmodifiable collection of all compartments
     */
    public static Collection<DrosophilaCompartment> getAllCompartments() {
        ensureMapPopulated();
        return Collections.unmodifiableCollection(
                DrosophilaCompartment.compartmentMap.values());
    }

    /**
     * Builds a {@link DefaultTreeModel} representing the Drosophila adult
     * brain ontology hierarchy.
     *
     * @return the tree model
     */
    public static DefaultTreeModel getTreeModel() {
        final JSONArray areas = getBrainAreasList();
        final Map<Integer, DrosophilaCompartment> map = new LinkedHashMap<>();
        final Map<Integer, DefaultMutableTreeNode> nodeMap = new HashMap<>();

        // First pass: create all compartments and nodes
        for (int i = 0; i < areas.length(); i++) {
            final JSONObject obj = areas.getJSONObject(i);
            final DrosophilaCompartment dc = new DrosophilaCompartment();
            dc.id = obj.getInt("id");
            dc.name = obj.getString("name");
            dc.acronym = obj.isNull("acronym") ? null : obj.getString("acronym");
            dc.parentId = obj.isNull("parentId") ? -1 : obj.getInt("parentId");
            dc.depth = obj.getInt("depth");
            final JSONArray syns = obj.getJSONArray("synonyms");
            dc.synonyms = new String[syns.length()];
            for (int s = 0; s < syns.length(); s++) {
                dc.synonyms[s] = syns.getString(s);
            }
            map.put(dc.id, dc);
            nodeMap.put(dc.id, new DefaultMutableTreeNode(dc));
        }

        // Populate the static map for parent lookups
        DrosophilaCompartment.compartmentMap = map;

        // Second pass: build tree
        final DefaultMutableTreeNode root = nodeMap.get(BRAIN_ROOT_ID);
        if (root == null) {
            throw new IllegalStateException("Root node (id=" + BRAIN_ROOT_ID + ") not found in JSON");
        }

        for (final DrosophilaCompartment dc : map.values()) {
            if (dc.id == BRAIN_ROOT_ID) continue;
            final DefaultMutableTreeNode node = nodeMap.get(dc.id);
            final DefaultMutableTreeNode parentNode = nodeMap.get(dc.parentId);
            // Orphan: attach to root
            Objects.requireNonNullElse(parentNode, root).add(node);
        }

        return new DefaultTreeModel(root);
    }

    private static void ensureMapPopulated() {
        if (DrosophilaCompartment.compartmentMap.isEmpty()) {
            getTreeModel(); // populates the map as a side effect
        }
    }
}
