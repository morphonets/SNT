/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.viewer.OBJMesh;

/**
 * Defines an Allen Reference Atlas (ARA) [Allen Mouse Common Coordinate
 * Framework] annotation. A Compartment is defined by either a UUID (as per
 * MouseLight's database) or its unique integer identifier. To improve
 * performance, a compartment's metadata (reference to its mesh, its aliases,
 * etc.) are not loaded at initialization, but retrieved only when such getters
 * are called.
 * 
 * @author Tiago Ferreira
 *
 */
public class AllenCompartment implements BrainAnnotation {

	private String name;
	private String acronym;
	private String[] aliases;
	private int structureId;
	private UUID uuid;
	private JSONObject jsonObj;
	private ArrayList<AllenCompartment> parentStructure;

	/**
	 * Instantiates a new ARA annotation from an UUID (as used by MouseLight's
	 * database).
	 *
	 * @param uuid the ML UUID identifying the annotation
	 */
	public AllenCompartment(final UUID uuid) {
		this(null, uuid);
		//initializeAsNeeded();
	}

	/**
	 * Instantiates a new ARA annotation from its identifier.
	 *
	 * @param id the integer identifying the annotation
	 */
	public AllenCompartment(final int id) {
		this(null, null);
		structureId = id;
		initializeAsNeeded();
	}

	protected AllenCompartment(final JSONObject jsonObj, final UUID uuid) {
		this.jsonObj = jsonObj;
		this.uuid = uuid;
		if (uuid != null)
			initializeAsNeeded();
	}

	private void loadJsonObj() {
		if (jsonObj != null) return;
		final JSONArray areaList = AllenUtils.getBrainAreasList();
		for (int n = 0; n < areaList.length(); n++) {
			final JSONObject area = (JSONObject) areaList.get(n);
			final UUID areaUUID = UUID.fromString(area.getString("id"));
			if (areaUUID.equals(uuid) || structureId ==  area.optInt("structureId")) {
				jsonObj = area;
				break;
			}
		}
	}

	private void initializeAsNeeded() {
		if (name != null) return;
		loadJsonObj();
		name = jsonObj.getString("name");
		acronym = jsonObj.getString("acronym");
		if (structureId == 0) structureId = jsonObj.optInt("structureId");
		if (uuid == null) uuid = UUID.fromString(jsonObj.getString("id"));
	}

	private String[] getArray(final JSONArray jArray) {
		final String[] array = new String[jArray.length()];
		for (int i = 0; i < jArray.length(); i++) {
			array[i] = (String) jArray.get(i);
		}
		return array;
	}

	protected int depth() {
		initializeAsNeeded();
		return jsonObj.getInt("depth");
	}

	protected int graphOrder() {
		return jsonObj.getInt("graphOrder");
	}

	protected String getStructureIdPath() {
		initializeAsNeeded();
		return jsonObj.optString("structureIdPath");
	}

	protected int getParentStructureId() {
		return jsonObj.optInt("parentStructureId");
	}

	/**
	 * Assesses if this annotation is a child of a specified compartment.
	 *
	 * @param childCompartment the compartment to be tested
	 * @return true, if successful, i.e., {@code parentCompartment} is not this
	 *         compartment and {@link #getTreePath()} contains
	 *         {@code parentCompartment}
	 */
	@Override
	public boolean isChildOf(final BrainAnnotation childCompartment) {
		if (childCompartment == null || !(childCompartment instanceof AllenCompartment))
			return false;
		final AllenCompartment cCompartment = (AllenCompartment) childCompartment;
		return id() != cCompartment.id() && getStructureIdPath().contains(String.valueOf(cCompartment.id()));
	}

	/**
	 * Gets the tree path of this compartment. The TreePath is the list of parent
	 * compartments that uniquely identify this compartment in the ontologies
	 * hierarchical tree. The elements of the list are ordered with the root ('Whole
	 * Brain") as the first element of the list. In practice, this is equivalent to
	 * appending this compartment to the the list returned by {@link #getAncestors()}.
	 *
	 * @return the tree path that uniquely identifies this compartment as a node in
	 *         the CCF ontologies tree
	 */
	public List<AllenCompartment> getTreePath() {
		if (parentStructure != null) return parentStructure;
		final String path = getStructureIdPath();
		parentStructure = new ArrayList<>();
		for (final String structureID : path.split("/")) {
			if (structureID.isEmpty())
				continue;
			parentStructure.add(AllenUtils.getCompartment(Integer.parseInt(structureID)));
		}
		return parentStructure;
	}

	/**
	 * Gets the ontology depth of this compartment.
	 *
	 * @return the ontological depth of this compartment, i.e., its ontological
	 *         distance relative to the root (e.g., a compartment of hierarchical
	 *         level {@code 9}, has a depth of {@code 8}).
	 */
	public int getOntologyDepth() {
		return depth();
	}

	/**
	 * Gets the parent of this compartment.
	 *
	 * @return the parent of this compartment, of null if this compartment is root.
	 */
	public AllenCompartment getParent() {
		if (getTreePath().size() < 2) return null;
		final int lastIdx = Math.max(0, parentStructure.size() - 2);
		return parentStructure.get(lastIdx);
	}

	/**
	 * Gets the ancestor ontologies of this compartment as a flat (non-hierarchical)
	 * list.
	 *
	 * @return the "flattened" list of ancestors
	 * @see #getTreePath()
	 */
	public List<AllenCompartment> getAncestors() {
		return parentStructure.subList(0, parentStructure.size()-1);
	}

	/**
	 * Gets the nth ancestor of this compartment.
	 *
	 * @param level the ancestor level as negative 1-based index. E.g., {@code -1}
	 *              retrieves the last ancestor (parent), {@code -2} retrieves the
	 *              second to last, etc, all the way down to
	 *              {@code -getOntologyDepth()}, which retrieves the root ontology
	 *              ("Whole Brain")
	 * @return the nth ancestor
	 */
	public AllenCompartment getAncestor(final int level) {
		if (level == 0) return getParent();
		int normLevel = (level > 0) ? -level : level;
		final int idx = getTreePath().size() - 1 + normLevel;
		if (idx < 0 || idx >=  parentStructure.size() - 1)
			throw new IllegalArgumentException ("Ancestor level out of range. Compartment has "+ getOntologyDepth() + " ancestors.");
		return parentStructure.get(idx);
	}

	/**
	 * Gets the child ontologies of this compartment as a flat (non-hierarchical)
	 * list.
	 *
	 * @return the "flattened" ontologies list
	 */
	public List<AllenCompartment> getChildren() {
		final ArrayList<AllenCompartment> children = new ArrayList<>();
		final Collection<AllenCompartment> allCompartments = AllenUtils.getOntologies();
		for (final AllenCompartment c : allCompartments) {
			if (c.isChildOf(this)) children.add(c);
		}
		return children;
	}

	/**
	 * Gets the child ontologies of this compartment as a flat (non-hierarchical)
	 * list.
	 *
	 * @param level maximum depth that should be considered.
	 * @return the "flattened" ontologies list
	 */
	public List<AllenCompartment> getChildren(final int level) {
		final int maxLevel = getTreePath().size() + level;
		final ArrayList<AllenCompartment> children = new ArrayList<>();
		final Collection<AllenCompartment> allCompartments = AllenUtils.getOntologies();
		for (AllenCompartment c :  allCompartments) {
			if (isChildOf(c) && c.getTreePath().size() <= maxLevel)
				children.add(c);
		}
		return children;
	}

	@Override
	public int id() {
		initializeAsNeeded();
		return structureId;
	}

	@Override
	public String name() {
		initializeAsNeeded();
		return name;
	}

	@Override
	public String acronym() {
		initializeAsNeeded();
		return acronym;
	}

	@Override
	public String[] aliases() {
		initializeAsNeeded();
		aliases = getArray(jsonObj.getJSONArray("aliases"));
		return aliases;
	}

	/**
	 * Checks whether a mesh is known to be available for this compartment.
	 *
	 * @return true, if a mesh is available.
	 */
	public boolean isMeshAvailable() {
		initializeAsNeeded();
		return jsonObj.getBoolean("geometryEnable");
	}

	@Override
	public OBJMesh getMesh() {
		if (id() == AllenUtils.BRAIN_ROOT_ID) return AllenUtils.getRootMesh(Colors.WHITE);
		final ColorRGB geometryColor = ColorRGB.fromHTMLColor("#" + jsonObj.optString("geometryColor", "ffffff"));
		OBJMesh mesh = null;
		if (!isMeshAvailable()) return null;
		final String file = jsonObj.getString("geometryFile");
		if (file == null || !file.endsWith(".obj")) return null;
		try {
			final String urlPath = "https://ml-neuronbrowser.janelia.org/static/allen/obj/" + file;
			final OkHttpClient client = new OkHttpClient();
			final Request request = new Request.Builder().url(urlPath).build();
			final Response response = client.newCall(request).execute();
			final boolean success = response.isSuccessful();
			response.close();
			if (!success) {
				System.out.println("MouseLight server is not reachable. Mesh(es) could not be retrieved. Check your internet connection...");
				return null;
			}
			final URL url = new URL(urlPath);
			mesh = new OBJMesh(url, "um");
			mesh.setColor(geometryColor, 87.5f);
			mesh.setLabel(name);
		} catch (final JSONException | IllegalArgumentException | IOException e) {
			SNTUtils.error("Could not retrieve mesh ", e);
		}
		return mesh;
	}

	/**
	 * Assesses if this annotation is the parent of the specified compartment.
	 *
	 * @param childCompartment the compartment to be tested
	 * @return true, if successful, i.e., {@code childCompartment} is not this
	 *         compartment and is present in {@link #getChildren()}
	 */
	@Override
	public boolean isParentOf(final BrainAnnotation childCompartment) {
		if (childCompartment == null || !(childCompartment instanceof AllenCompartment))
			return false;
		final AllenCompartment cCompartment = (AllenCompartment) childCompartment;
		return id() != cCompartment.id() && cCompartment.getStructureIdPath().contains(String.valueOf(id()));
	}

	@Override
	public String toString() {
		return name() + " [" + acronym + "]";
	}

	@Override
	public int hashCode() {
		return Objects.hash(uuid);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof AllenCompartment)) {
			return false;
		}
		AllenCompartment other = (AllenCompartment) obj;
		return Objects.equals(uuid, other.uuid);
	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		final AllenCompartment child = AllenUtils.getCompartment("CA3");
		final AllenCompartment parent = AllenUtils.getCompartment("Hippocampal Formation");
		System.out.println("CA3.isChildOf(HPF):\t"+child.isChildOf(parent));
		System.out.println("HPF.isChildOf(CA3):\t" +parent.isChildOf(child));
		System.out.println("CA3.isParentOf(CA3):\t" +child.isParentOf(child));
		System.out.println("CA3.isParentOf(HPF):\t" +child.isParentOf(parent));
		System.out.println("HPF.isParentOf(CA3):\t" +parent.isParentOf(child));
		System.out.println("HPF.isChildOf(HPF):\t" +parent.isChildOf(parent));

		System.out.println("CA3.getChildren():\t" +child.getChildren());
		System.out.println("CA3.getTreePath():\t" + child.getTreePath());
		System.out.println("CA3.getOntologyDepth():\t" + child.getOntologyDepth());
		System.out.println("CA3 # ancestors:\t" + child.getAncestors().size());

		System.out.println("CA3 up-down ancestors:");
		IntStream.rangeClosed(-child.getOntologyDepth(), 0).forEach(level -> {
			System.out.println("\tancestor " + level + ": " + child.getAncestor(level));
		});
		System.out.println("CA3 down-up ancestors:");
		IntStream.rangeClosed(0, child.getOntologyDepth()).forEach(level -> {
			System.out.println("\tancestor " + level + ": " + child.getAncestor(level));
		});
	}
}
