/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import net.imglib2.util.LinAlgHelpers;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.LinAlgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.OBJMesh;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility methods for accessing/handling {@link AllenCompartment}s
 * 
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class AllenUtils {

	/** The version of the Common Coordinate Framework currently used by SNT */
	private static final String V2_5 = "2.5"; // the legacy MouseLight atlas
	private static final String V3 = "3";
	public static final String VERSION = V3;
	private static final Map<String, String> brainAreasByCCFVersion = createBrainAreasResourcePathsMap();
	protected static final int BRAIN_ROOT_ID = 997;

	private static JSONArray areaList;
	private static JSONObject areaObjectFromStructureId;
	private static JSONObject areaObjectFromUUID;

	private AllenUtils() {
	}

	private static Map<String, String> createBrainAreasResourcePathsMap() {
		Map<String,String> brainAreasPaths = new HashMap<>();
		brainAreasPaths.put(V3, "ml/brainAreas_v3.json");
		brainAreasPaths.put(V2_5, "ml/brainAreas_v2.5.json");
		return brainAreasPaths;
	}

	private static JSONObject getJSONfile(final String resourcePath) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(resourcePath);
		final JSONTokener tokener = new JSONTokener(is);
		final JSONObject json = new JSONObject(tokener);
		try {
			is.close();
		} catch (final IOException ignored) {// do nothing

		}
		return json;
	}

	protected static JSONArray getBrainAreasList() {
		if (areaList == null) {
			final JSONObject json = getJSONfile(brainAreasByCCFVersion.get(VERSION));
			areaList = json.getJSONObject("data").getJSONArray("brainAreas");
		}
		return areaList;
	}
	
	protected static String hostedMeshesLocation() {
		switch(VERSION) {
		case V3:
			return "https://ml-neuronbrowser.janelia.org/static/ccf-2017/obj/";
		case V2_5:
			return "https://ml-neuronbrowser.janelia.org/static/allen/obj/";
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	protected static JSONObject getBrainAreasByStructureId() {
		if (areaObjectFromStructureId == null) {
			final JSONObject json = getJSONfile(brainAreasByCCFVersion.get(VERSION));
			areaObjectFromStructureId = json.getJSONObject("data").getJSONObject("brainAreasByStructureId");
		}
		return areaObjectFromStructureId;
	}
	
	protected static JSONObject getBrainAreasByUUID() {
		if (areaObjectFromUUID == null) {
			final JSONObject json = getJSONfile(brainAreasByCCFVersion.get(VERSION));
			areaObjectFromUUID = json.getJSONObject("data").getJSONObject("brainAreasByUUID");
		}
		return areaObjectFromUUID;
	}

	@SuppressWarnings("unused")
	private static AllenCompartment getCompartment(final UUID uuid) {
		areaObjectFromUUID = getBrainAreasByUUID();
		if (areaObjectFromUUID.has(uuid.toString())) {
			return new AllenCompartment(areaObjectFromUUID.getJSONObject(uuid.toString()), uuid);
		}
		return null;
	}

	/**
	 * Constructs a compartment from its CCF id
	 *
	 * @param id the integer identifier
	 * @return the compartment matching the id or null if id is not valid
	 */
	public static AllenCompartment getCompartment(final int id) {
		areaObjectFromStructureId = getBrainAreasByStructureId();
		String idString = String.valueOf(id);
		if (areaObjectFromStructureId.has(idString)) {
			return new AllenCompartment(id);
		}
		return null;
	}

	/**
	 * Constructs a compartment from its CCF name or acronym
	 *
	 * @param nameOrAcronym the name or acronym (case-insensitive) identifying the
	 *                      compartment
	 * @return the compartment whose name or acronym matches the specified string or
	 *         null if no match was found
	 */
	public static AllenCompartment getCompartment(final String nameOrAcronym) {
		areaList = getBrainAreasList();
		for (int n = 0; n < areaList.length(); n++) {
			final JSONObject area = (JSONObject) areaList.get(n);
			if (nameOrAcronym.equalsIgnoreCase(area.getString("name"))
					|| nameOrAcronym.equalsIgnoreCase(area.getString("acronym"))) {
				return new AllenCompartment(area, UUID.fromString(area.getString("id")));
			}
		}
		return null;
	}

	/**
	 * Reflect the Tree across the mid-sagittal plane passing through the brain
	 * barycentre.
	 */
	private static void mirrorTree(final Tree tree) {
		final double[] p = new double[] { brainCenter().getX(), brainCenter().getY(), brainCenter().getZ() };
		final double[] q = new double[] { p[0], p[1], p[2]};
		final double[] r = new double[] { p[0], p[1], p[2]};
		// Choose two vectors defining the sagittal plane.
		switch (VERSION) {
			case V3:
				// Axes> X: Anterior-Posterior; Y: Inferior-Superior; Z:Left-Right
				// XY is sagittal plane
				q[0] += 100;
				r[1] += 100;
				break;
			case V2_5:
				// Axes> Z: Anterior-Posterior; Y: Inferior-Superior; X:Left-Right
				// ZY is sagittal plane
				q[1] += 100;
				r[2] += 100;
				break;
			default:
				throw new IllegalArgumentException("Unrecognized CCF version");
		}
		final double[] pq = new double[3];
		LinAlgHelpers.subtract(q, p, pq);
		final double[] pr = new double[3];
		LinAlgHelpers.subtract(r, p, pr);
		final double[] crossed = new double[3];
		LinAlgHelpers.cross(pq, pr, crossed);
		LinAlgHelpers.normalize(crossed);
		final double[][] A = LinAlgUtils.reflectionMatrix(p, crossed);
		final double[] oldCoords = new double[4];
		final double[] newCoords = new double[4];
		for (final Path path : tree.list()) {
			for (int i = 0; i < path.size(); i++) {
				final PointInImage node = path.getNode(i);
				oldCoords[0] = node.getX();
				oldCoords[1] = node.getY();
				oldCoords[2] = node.getZ();
				// Use augmented matrix
				oldCoords[3] = 1d;
				LinAlgHelpers.mult(A, oldCoords, newCoords);
				path.moveNode(i, new PointInImage(newCoords[0], newCoords[1], newCoords[2]));
			}
		}
	}

	public static void assignToLeftHemisphere(final Tree tree) {
		final PointInImage root = tree.getRoot();
		if (root == null || isLeftHemisphere(root))
			return;
		mirrorTree(tree);
	}

	public static void assignToRightHemisphere(final Tree tree) {
		final PointInImage root = tree.getRoot();
		if (root == null || !isLeftHemisphere(root))
			return;
		mirrorTree(tree);
	}

	/**
	 * Checks the hemisphere a neuron belongs to.
	 *
	 * @param tree the Tree to be tested
	 * @return true, if soma (or root node) is in the left hemisphere, false
	 *         otherwise
	 */
	public static boolean isLeftHemisphere(final Tree tree) {
		return isLeftHemisphere(tree.getRoot());
	}

	public static void assignHemisphereTags(final DirectedWeightedGraph graph) {
		graph.vertexSet().forEach(node ->
				node.setHemisphere(
						isLeftHemisphere(node) ? BrainAnnotation.LEFT_HEMISPHERE : BrainAnnotation.RIGHT_HEMISPHERE
				));
	}

	/**
	 * Gets the axis defining the sagittal plane.
	 *
	 * @return the axis defining the sagittal plane where X=0; Y=1; Z=2;
	 */
	public static int getAxisDefiningSagittalPlane() {
		switch(VERSION) {
		case V3:
			return 2; // Z axis
		case V2_5:
			return 0; // X axis
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	public static void assignHemisphereTags(final Tree tree) {
		//TODO: Currently we have to tag both the tree nodes and graph vertices. This needs to be simplified!
		tree.getNodes().forEach(node ->
				node.setHemisphere(
						isLeftHemisphere(node) ? BrainAnnotation.LEFT_HEMISPHERE : BrainAnnotation.RIGHT_HEMISPHERE
				));
		assignHemisphereTags(tree.getGraph());
	}

	public static List<DirectedWeightedSubgraph> splitByHemisphere(final DirectedWeightedGraph graph) {
		assignHemisphereTags(graph);
		final DirectedWeightedSubgraph leftGraph = graph.getSubgraph(graph.vertexSet(BrainAnnotation.LEFT_HEMISPHERE));
		leftGraph.setLabel("Left hemi.");
		final DirectedWeightedSubgraph rightGraph = graph.getSubgraph(graph.vertexSet(BrainAnnotation.RIGHT_HEMISPHERE));
		rightGraph.setLabel("Right hemi.");
		return Arrays.asList(leftGraph, rightGraph);
	}

	/**
	 * Checks the hemisphere a reconstruction node belongs to.
	 *
	 * @param point the point
	 * @return true, if is left hemisphere, false otherwise
	 */
	public static boolean isLeftHemisphere(final SNTPoint point) {
		switch(VERSION) {
		case V3:
			return point.getZ() <= brainCenter().getZ();
		case V2_5:
			return point.getX() <= brainCenter().getX();
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	public static boolean isLeftHemisphere(final double x, final double y, final double z) {
		switch(VERSION) {
		case V3:
			return z <= brainCenter().getZ();
		case V2_5:
			return x <= brainCenter().getX();
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	/**
	 * Returns the spatial centroid of the Allen CCF.
	 *
	 * @return the SNT point defining the (X,Y,Z) center of the ARA
	 */
	public static SNTPoint brainCenter() {
		switch(VERSION) {
		case V3:
			return new PointInImage(6587.8352f, 3849.0851f, 5688.1643f); // precomputed
		case V2_5:
			return new PointInImage(5687.5435f, 3849.6099f, 6595.3813f); // precomputed
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	/**
	 * Gets the maximum number of ontology levels in the Allen CCF.
	 * 
	 * @return the max number of ontology levels.
	 */
	public static int getHighestOntologyDepth() {
		switch(VERSION) {
		case V3:
		case V2_5:
			return 10; // as per computeHighestOntologyDepth()
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
	}

	@SuppressWarnings("unused")
	private static int computeHighestOntologyDepth() {
		int maxLevel = 0;
		for (final AllenCompartment c : getOntologies()) {
			maxLevel = Math.max(maxLevel, c.getOntologyDepth());
		}
		return maxLevel;
	}

	/**
	 * Retrieves the Allen CCF hierarchical tree data.
	 *
	 * @param meshesOnly Whether only compartments with known meshes should be
	 *                   included
	 * @return the Allen CCF tree data model
	 */
	public static DefaultTreeModel getTreeModel(final boolean meshesOnly) {
		return new AllenTreeModel().getTreeModel(meshesOnly);
	}

	/**
	 * Gets the Allen CCF as a flat (non-hierarchical) collection of ontologies.
	 *
	 * @return the "flattened" ontologies list
	 */
	public static List<AllenCompartment> getOntologies() {
		return new AllenTreeModel().getOntologies();
	}


	/**
	 * Gets a flat (non-hierarchical) list of all the compartments of the specified
	 * ontology depth.
	 * 
	 * @param depth  the ontology depth
	 * @param meshes If true, only compartments with known meshes are retrieved
	 * @return the "flattened" list of compartment
	 */
	public static List<AllenCompartment> getOntologies(final int depth, final boolean meshes) {
		if (depth < 0 || depth > getHighestOntologyDepth()) {
			throw new IllegalArgumentException("depth must be within 0-getHighestOntologyDepth()");
		}
		if (meshes) {
			return new AllenTreeModel().getOntologies().stream()
					.filter(c -> c.getOntologyDepth() == depth && c.isMeshAvailable()).collect(Collectors.toList());
		} else {
			return new AllenTreeModel().getOntologies().stream().filter(c -> c.getOntologyDepth() == depth)
					.collect(Collectors.toList());
		}
	}

	private static class AllenTreeModel {

		private static final int ROOT_ID = 997;
		private final JSONArray areaList;
		private DefaultMutableTreeNode root;

		private AllenTreeModel() {
			areaList = getBrainAreasList();
		}

		private AllenCompartment getAreaListCompartment(final int idx) {
			final JSONObject area = (JSONObject) areaList.get(idx);
			return new AllenCompartment(UUID.fromString(area.getString("id")));
		}

		private List<AllenCompartment> getOntologies() {
			final List<AllenCompartment> list = new ArrayList<>(areaList.length());
			for (int n = 0; n < areaList.length(); n++) {
				list.add(getAreaListCompartment(n));
			}
			return list;
		}

		private DefaultTreeModel getTreeModel(final boolean meshesOnly) {
			final TreeSet<AllenCompartment> all = new TreeSet<>(
					Comparator.comparing(AllenCompartment::getStructureIdPath));
			final Map<Integer, AllenCompartment> idsMap = new HashMap<>();
			final Set<Integer> visitedIds = new HashSet<>();
			root = new DefaultMutableTreeNode();
			for (int n = 0; n < areaList.length(); n++) {
				final AllenCompartment ac = getAreaListCompartment(n);
				if (ac.id() == ROOT_ID) {
					root.setUserObject(ac);
					visitedIds.add(ac.id());
				} else {
					idsMap.put(ac.id(), ac);
				}
				all.add(ac);
			}

			for (final AllenCompartment ac : all) {
				final String path = ac.getStructureIdPath();
				DefaultMutableTreeNode node = root;
				for (final String structureID : path.split("/")) {
					if (structureID.isEmpty())
						continue;
					final int id = Integer.parseInt(structureID);
					if (visitedIds.contains(id))
						continue;
					final AllenCompartment c = idsMap.get(id);
					if (meshesOnly && !c.isMeshAvailable())
						continue;
					final AllenCompartment pc = idsMap.get(c.getParentStructureId());
					final DefaultMutableTreeNode parentNode = getParentNode(pc);
					if (parentNode != null) {
						node = parentNode;
					}
					node.add(new DefaultMutableTreeNode(c));
					visitedIds.add(id);
				}
			}
			assert (visitedIds.size() == idsMap.size() + 1);
			return new DefaultTreeModel(root);
		}

		private DefaultMutableTreeNode getParentNode(final AllenCompartment parentStructure) {
			final Enumeration<TreeNode> en = root.depthFirstEnumeration();
			while (en.hasMoreElements()) {
				DefaultMutableTreeNode node = (DefaultMutableTreeNode) en.nextElement();
				final AllenCompartment structure = (AllenCompartment) node.getUserObject();
				if (structure.equals(parentStructure)) {
					return node;
				}
			}
			return null;
		}
	}

	/**
	 * Retrieves the surface contours for the Allen Mouse Brain Atlas (CCF), bundled
	 * with SNT.
	 *
	 * @param color the color to be assigned to the mesh
	 * @return a reference to the retrieved mesh
	 */
	public static OBJMesh getRootMesh(final ColorRGB color) {
		final String meshLabel = "Whole Brain";
		String meshPath;
		double volume;
		switch (VERSION) {
		case V3:
			meshPath = "meshes/MouseBrainAllen3.obj";
			volume = 513578693035.138d;  // pre-computed in trimesh
			break;
		case V2_5:
			meshPath = "meshes/MouseBrainAllen2.5.obj";
			volume = 513578693035.138d; // pre-computed in trimesh
			break;
		default:
			throw new IllegalArgumentException("Unrecognized CCF version");
		}
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		final URL url = loader.getResource(meshPath);
		if (url == null)
			throw new IllegalArgumentException(meshLabel + " not found");
		final OBJMesh mesh = new OBJMesh(url, GuiUtils.micrometer());
		mesh.setColor(color, 95f);
		mesh.setVolume(volume);
		mesh.setLabel(meshLabel);
		mesh.setSymmetryAxis(getAxisDefiningSagittalPlane());
		return mesh;
	}

	/**
	 * 
	 * @return the anatomical descriptions associated with the Cartesian X,Y,Z axes
	 */
	public static String[] getXYZLabels() {
		// return new String[] { "Anterior-Posterior", "Inferior-Superior", "Left-Right" };
		return new String[] { "Anterior-Posterior", "Dorsal-Ventral", "Left-Right (ML)" };
	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		final AllenCompartment compartmentOfInterest = AllenUtils.getCompartment("CA3");
		System.out.println(compartmentOfInterest);
		System.out.println(compartmentOfInterest.getTreePath());
		final javax.swing.JTree tree = new javax.swing.JTree(getTreeModel(false));
		final javax.swing.JScrollPane treeView = new javax.swing.JScrollPane(tree);
		final javax.swing.JFrame f = new javax.swing.JFrame();
		f.add(treeView);
		f.pack();
		f.setVisible(true);
	}

}
