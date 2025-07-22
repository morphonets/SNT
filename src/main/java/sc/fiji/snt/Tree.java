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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.imagej.Dataset;
import org.scijava.util.ColorRGB;
import org.scijava.util.ColorRGBA;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import sc.fiji.snt.analysis.*;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.*;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Utility class to access a Collection of Paths (typically a complete
 * reconstruction). A Tree is the preferred way to group, access and manipulate
 * {@link Path}s that share something in common, specially when scripting SNT.
 * Note that a "Tree" here is literally a collection of {@link Path}s. Very few
 * restrictions are imposed on its topology, although it is generally assumed
 * that the Collection of paths describes a single-rooted structure with no
 * loops.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class Tree implements TreeProperties {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	public static final int X_AXIS = 1;
	public static final int Y_AXIS = 2;
	public static final int Z_AXIS = 4;

	private ArrayList<Path> tree;
	private String label;
	private ColorRGB color;
	private TreeBoundingBox box;
	private PathAndFillManager pafm;
	private DirectedWeightedGraph graph;
	private DirectedWeightedGraph simplifiedGraph;
	private double value;
	private Properties properties;

	/**
	 * Instantiates a new empty Tree.
	 */
	public Tree() {
		tree = new ArrayList<>();
	}

	/**
	 * Instantiates a new Tree from a set of paths.
	 *
	 * @param paths the Collection of paths forming this tree. Null not allowed.
	 *          Note that when a Path has been fitted and
	 *          {@link Path#getUseFitted()} is true, its fitted 'flavor' is used.
	 */
	public Tree(final Collection<Path> paths) {
		if (paths == null) throw new IllegalArgumentException(
			"Cannot instantiate a new tree from a null collection");
		tree = new ArrayList<>(paths.size());
		for (final Path p : paths) {
			if (p == null) continue;
			Path pathToAdd;
			// If fitted flavor of path exists use it instead
			if (p.getUseFitted() && p.getFitted() != null) {
				pathToAdd = p.getFitted();
			} else {
				pathToAdd = p;
			}
			tree.add(pathToAdd);
		}
		final String unit = getCommonUnit(paths);
		if (unit != null)
			getProperties().setProperty(TreeProperties.KEY_SPATIAL_UNIT, unit);
	}

	private String getCommonUnit(final Collection<Path> paths) {
		if (paths.isEmpty()) 
			return null;
		final Iterator<Path> it = paths.iterator();
		final String ref = it.next().spacing_units;
		while (it.hasNext()) {
			if (!ref.equals(it.next().spacing_units))
				return null;
		}
		return ref;
	}

	/**
	 * Instantiates a Tree from a collection of reconstruction nodes.
	 *
	 * @param nodes the collection of reconstruction nodes. Nodes will be sorted by
	 *              id and any duplicate entries pruned.
	 * @param label the identifying label for this Tree.
	 */
	public Tree(final Collection<SWCPoint> nodes, final String label) {
		pafm = PathAndFillManager.createFromNodes(nodes);
		tree = (pafm == null) ? new ArrayList<>() : pafm.getPaths();
		setLabel(label);
	}

	public Tree(final DirectedWeightedGraph graph, final String label) {
		this(graph, label, false);
	}

	public Tree(final DirectedWeightedGraph graph, final String label, final boolean keepTreePathStructure) {
		pafm = PathAndFillManager.createFromGraph(graph, keepTreePathStructure);
		tree = pafm.getPaths();
		setLabel(label);
	}

	/**
	 * Instantiates a new tree from a SWC, TRACES or JSON file.
	 *
	 * @param filename the absolute file path of the imported file
	 * @throws IllegalArgumentException if file path is not valid
	 */
	public Tree(final String filename) throws IllegalArgumentException {
		this(filename, "");
	}

	/**
	 * Instantiates a new tree from a SWC, TRACES or JSON file with filtering.
	 *
	 * @param filename    the absolute file path of the imported file
	 * @param compartment A case-insensitive string with at least 2 characters
	 *                    describing the sub-cellular compartment (axonal or
	 *                    dendritic) to be imported (e.g., 'axon', 'dendrites',
	 *                    'axn', 'dnd', etc.). It is ignored if {@code filename}
	 *                    encodes a .TRACES file.
	 * @throws IllegalArgumentException if file path is not valid
	 */
	public Tree(final String filename, final String compartment) throws IllegalArgumentException {
		final File f = getFile(filename, true);
		initPathAndFillManagerFromFile(f.getAbsolutePath(), compartment);
		tree = pafm.getPaths();
		setLabel(SNTUtils.stripExtension(f.getName()));
	}

	private void initPathAndFillManagerFromFile(final String filename, final String compartment) throws IllegalArgumentException {
		final String normCompartment = (compartment == null || compartment.length() < 2) ? "all"
				: compartment.toLowerCase().substring(0, 2);
		switch (normCompartment) {
		case "ax":
			pafm = PathAndFillManager.createFromFile(filename, Path.SWC_AXON);
			break;
		case "ap":
			pafm = PathAndFillManager.createFromFile(filename, Path.SWC_APICAL_DENDRITE);
			break;
		case "ba":
		case "(b":
			pafm = PathAndFillManager.createFromFile(filename, Path.SWC_DENDRITE);
			break;
		case "de":
		case "dn":
			pafm = PathAndFillManager.createFromFile(filename, Path.SWC_APICAL_DENDRITE, Path.SWC_DENDRITE);
			break;
		default:
			pafm = PathAndFillManager.createFromFile(filename);
			break;
		}
		if (pafm == null)
			throw new IllegalArgumentException("No paths extracted from " + filename + " Invalid file/compartment?");
		if (getProperties().get(Tree.KEY_SPATIAL_UNIT) == null)
			getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, pafm.getBoundingBox(false).getUnit());
	}

	/**
	 * Instantiates a new tree from a filtered SWC, TRACES or JSON file.
	 *
	 * @param filename the absolute file path of the imported file
	 * @param swcTypes only paths matching the specified SWC type(s) (e.g.,
	 *                 {@link Path#SWC_AXON}, {@link Path#SWC_DENDRITE}, etc.) will
	 *                 be imported. Ignored if {@code filename} encodes a .TRACES
	 *                 file.
	 * @throws IllegalArgumentException if file path is not valid
	 */
	public Tree(final String filename, final int... swcTypes) throws IllegalArgumentException {
		final File f = getFile(filename, true);
		if (filename.toLowerCase().endsWith(".traces") && swcTypes != null)
			SNTUtils.log("Importing TRACES file: swcTypes will be ignored!");
		pafm = PathAndFillManager.createFromFile(filename, swcTypes);
		if (pafm == null)
			throw new IllegalArgumentException("No paths extracted from " + filename + " Invalid file/flags?");
		tree = pafm.getPaths();
		setLabel(SNTUtils.stripExtension(f.getName()));
	}

	/**
	 * Adds a new Path to this Tree.
	 *
	 * @param p the Path to be added
	 * @return true, if Path successful added
	 */
	public boolean add(final Path p) {
		final boolean added = tree.add(p);
		if (added) nullifyGraphsAndPafm();
		return added;
	}

	/**
	 * Appends all paths of a specified {@link Tree} to this one.
	 *
	 * @param tree the Tree to be merged
	 * @return true if this Tree changed as a result of the merge
	 */
	public boolean merge(final Tree tree) {
		setLabel(((label == null) ? "" : label) + " " + tree.getLabel());
		final boolean addedAll = this.tree.addAll(tree.list());
		if (addedAll) nullifyGraphsAndPafm();
		return addedAll;
	}

	/**
	 * Replaces all Paths in this Tree.
	 *
	 * @param paths the replacing Paths
	 */
	public void replaceAll(final List<Path> paths) {
		tree = new ArrayList<>(paths);
		nullifyGraphsAndPafm();
	}

	/**
	 * Returns the Path at the specified position.
	 *
	 * @param index index of the element to return
	 * @return the element at the specified position
	 */
	public Path get(final int index) {
		return tree.get(index);
	}

	/**
	 * Returns the index of the specified Path in this Tree.
	 *
	 * @param path the Path to be searched for
	 * @return the path index, or -1 if it was not found
	 */
	public int indexOf(final Path path) {
		return tree.indexOf(path);
	}

	/**
	 * Removes a path from this tree.
	 *
	 * @param p the Path to be removed
	 * @return true if this tree contained p
	 */
	public boolean remove(final Path p) {
		boolean removed = tree.remove(p);
		if (removed) nullifyGraphsAndPafm();
		return removed;
	}

	/**
	 * Gets all the paths from this tree.
	 *
	 * @return the paths forming this tree
	 */
	public ArrayList<Path> list() {
		return tree;
	}

	/**
	 * Checks if this Tree is empty.
	 *
	 * @return true if this tree contains no Paths, false otherwise
	 */
	public boolean isEmpty() {
		return tree.isEmpty();
	}

	/**
	 * Checks if the nodes of this Tree have been assigned
	 * {@link sc.fiji.snt.annotation.BrainAnnotation BrainAnnotation}s (neuropil
	 * labels).
	 *
	 * @return true if at least one node in the Tree has a valid annotation, false
	 *         otherwise
	 */
	public boolean isAnnotated() {
		for (final Path p : tree) {
			for (int i = 0; i < p.size(); ++i) {
				if (p.getNodeAnnotation(i) != null)
					return true;
			}
		}
		return false;
	}

	/**
	 * Downsamples the tree, i.e., reduces the density of its nodes by increasing internode spacing.
	 * <p>
	 * Note that 1) upsampling is not supported (see {{@link #upsample(double)}}, and 2) the
	 * position of nodes at branch points and tips remains unaltered during downsampling.
	 * </p>
	 *
	 * @param maximumAllowedDeviation the maximum allowed distance between path nodes.
	 * @see PathDownsampler
	 * @see #upsample(double)
	 */
	public void downsample(final double maximumAllowedDeviation) {
		tree.parallelStream().forEach(p -> p.downsample(maximumAllowedDeviation));
		nullifyGraphsAndPafm();
	}

	/**
	 * @deprecated Use {@link #downsample(double)} instead.
	 */
	@Deprecated
	public void downSample(final double maximumAllowedDeviation) {
		downsample(maximumAllowedDeviation);
	}

	/**
	 * Downsamples the tree, i.e., increases the density of its nodes by decreasing internode spacing.
	 * <p>
	 * The upscaling will include all the original nodes plus additional interpolated nodes
	 * placed at regular intervals between them, approximately equal to the specified
	 * distance. The original nodes are preserved in the upsampled tree. If the distance
	 * between two adjacent original nodes is less than the specified pacing, no additional
	 * nodes are added between them.
	 * </p>
	 *
	 * @param internodeSpacing the desired distance between adjacent nodes in the upsampled path
	 * @throws IllegalArgumentException if spacing is less than or equal to zero
	 * @see #downsample(double)
	 * @see Path#upsample(double)
	 */
	public void upsample(final double internodeSpacing) {
		tree.parallelStream().forEach(p -> p.upsample(internodeSpacing));
		nullifyGraphsAndPafm();
	}

	/**
	 * Extracts the subset of paths matching the specified criteria (script friendly
	 * method)
	 *
	 * @param swcTypes SWC type(s) a string with at least 2 characters describing
	 *                 the SWC type allowed in the subtree (e.g., 'soma', 'axn', or
	 *                 'dendrite')
	 * @return the subset of paths matching the filtering criteria, or an empty Tree
	 *         if no hits were retrieved
	 */
	public Tree subTree(final String... swcTypes) {
		if (swcTypes.length == 1 && "all".equalsIgnoreCase(swcTypes[0]))
			return this;
		final Set<Integer> types = new HashSet<>();
        for (final String swcType : swcTypes) {
            switch (swcType.toLowerCase().substring(0, 2)) {
                case "ax":
                    types.add(Path.SWC_AXON);
                    break;
                case "so":
                    types.add(Path.SWC_SOMA);
                    break;
                case "ap":
                    types.add(Path.SWC_APICAL_DENDRITE);
                    break;
                case "ba":
                case "(b":
                    types.add(Path.SWC_DENDRITE);
                    break;
                case "de":
                case "dn":
                    types.add(Path.SWC_APICAL_DENDRITE);
                    types.add(Path.SWC_DENDRITE);
                    break;
                default:
                    types.add(Path.SWC_UNDEFINED);
                    break;
            }
        }
		final Tree subTree = subTree(types.stream().mapToInt(Integer::intValue).toArray());
		if (getLabel() != null) 
			subTree.setLabel(getLabel() + " " + Arrays.toString(swcTypes));
		subTree.setColor(color);
		return subTree;
	}

	/**
	 * Extracts the subset of paths matching the specified criteria.
	 *
	 * @param swcTypes SWC type(s) (e.g., {@link Path#SWC_AXON},
	 *          {@link Path#SWC_DENDRITE}, etc.) allowed in the subtree
	 * @return the subset of paths matching the filtering criteria, or an empty
	 *         Tree if no hits were retrieved
	 */
	public Tree subTree(final int... swcTypes) {
		try {
			return subTreeNodeConversion(swcTypes);
		} catch(final IllegalArgumentException ignored) {
			return new Tree();
		}
		//return subTreePathBased2(swcTypes);
	}

	/**
	 * @param swcTypes the swc types
	 * @return the tree
	 * @throws IllegalArgumentException If Tree does not contain selected types
	 */
	Tree subTreeNodeConversion(final int... swcTypes) throws IllegalArgumentException {
		// this is ~4x slower than Path-based conversion but accurate
		final List<SWCPoint> nodes = getNodesAsSWCPoints();
		//System.out.println("nNodes: "+ nodes.size());

		Iterator<SWCPoint> it = nodes.iterator();
		Set<Integer> idsToRemove = new HashSet<>();

		// Remove filtered nodes
		while (it.hasNext()) {
			final SWCPoint node = it.next();
			if (!matchesType(node, swcTypes))
				idsToRemove.add(node.id);
		}
		if (!idsToRemove.isEmpty()) {
			it = nodes.iterator();
			while (it.hasNext()) {
				final SWCPoint node = it.next();
				if (idsToRemove.contains(node.id)) {
					it.remove();
				}
			}
			it = nodes.iterator();
			while (it.hasNext()) {
				final SWCPoint node = it.next();
				if (idsToRemove.contains(node.parent))
					node.parent = -1;
			}
		}

		//System.out.println("Returning subtree with nNodes: "+ nodes.size());
		final String[] types = new String[swcTypes.length];
		for (int i = 0; i < swcTypes.length; i++)
			types[i] = Path.getSWCtypeName(swcTypes[i], true);
		final Tree subTree = new Tree(nodes, getLabel() + " " + Arrays.toString(types));
		subTree.applyProperties(this);
		return subTree;
	}

	private boolean matchesType(final SWCPoint node, final int... swcTypes) {
		for (int type : swcTypes) {
			if (node.type == type) return true;
		}
		return false;
	}

	/**
	 * Applies properties from another Tree to this Tree.
	 *
	 * @param tree the Tree whose properties should be copied
	 */
	public void applyProperties(final Tree tree) {
		getProperties().putAll(tree.getProperties());
	}

	/**
	 * Assigns an SWC type label to all the Paths in this Tree.
	 *
	 * @param type the SWC type (e.g., {@link Path#SWC_AXON},
	 *          {@link Path#SWC_DENDRITE}, etc.)
	 */
	public void setType(final int type) {
		tree.forEach(p -> p.setSWCType(type));
		if (graph != null) graph.vertexSet().forEach(v -> v.type = type);
		if (simplifiedGraph != null) simplifiedGraph.vertexSet().forEach(v -> v.type = type);
		switch(type) {
		case Path.SWC_APICAL_DENDRITE:
		case Path.SWC_DENDRITE:
			getProperties().setProperty(TreeProperties.KEY_COMPARTMENT, TreeProperties.DENDRITIC);
			break;
		case Path.SWC_AXON:
			getProperties().setProperty(TreeProperties.KEY_COMPARTMENT, TreeProperties.AXONAL);
			break;
		default:
			getProperties().setProperty(TreeProperties.KEY_COMPARTMENT, TreeProperties.UNSET);
		}
	}

	/**
	 * Assigns an SWC type label to all the Paths in this Tree.
	 *
	 * @param type the SWC type (e.g., "soma", "axon", "(basal) dendrite", "apical
	 *          dendrite", etc.)
	 */
	public void setSWCType(final String type) {
		String inputType = (type == null) ? Path.SWC_UNDEFINED_LABEL : type.trim()
			.toLowerCase();
		switch (inputType) {
			case "apical dendrite":
			case "(apical) dendrite":
				inputType = Path.SWC_APICAL_DENDRITE_LABEL;
				break;
			case "basal dendrite":
			case "(basal) dendrite":
			case "dendrite":
			case "dend":
				inputType = Path.SWC_DENDRITE_LABEL;
				break;
			case "":
			case "none":
			case "unknown":
			case "undefined":
			case "undef":
				inputType = Path.SWC_UNDEFINED_LABEL;
				break;
			case "custom":
				inputType = Path.SWC_CUSTOM_LABEL;
				break;
			default:
				break; // keep input
		}
		final int labelIdx = Path.getSWCtypeNames().indexOf(inputType);
		if (labelIdx == -1) throw new IllegalArgumentException(
			"Unrecognized SWC-type label:" + type);
		final int intType = Path.getSWCtypes().get(labelIdx);
		setType(intType);
	}

	/**
	 * Extracts the SWC-type flags present in this Tree.
	 *
	 * @return the set of SWC type(s) (e.g., {@link Path#SWC_AXON},
	 *         {@link Path#SWC_DENDRITE}, etc.) present in the tree
	 */
	public Set<Integer> getSWCTypes() {
		return getSWCTypes(true); // backwardsCompatibility
	}

	public Set<Integer> getSWCTypes(final boolean includeSoma) {
		final HashSet<Integer> types = new HashSet<>();
        for (final Path path : tree) {
            types.add(path.getSWCType());
        }
		if (!includeSoma) types.remove(Path.SWC_SOMA);
		return types;
	}

	/**
	 * Displays this Tree in Viewer3D.
	 *
	 * @return the {@link Viewer3D} instance displaying this Tree
	 */
	public Viewer3D show3D() {
		final Viewer3D viewer = new Viewer3D();
		viewer.addTree(this);
		if (getLabel() != null) {
			final java.awt.Frame frame = viewer.show();
			frame.setTitle(frame.getTitle().replace("Reconstruction Viewer", getLabel()));
		}
		return viewer;
	}

	/**
	 * Displays this Tree in Viewer2D.
	 *
	 * @return the {@link Viewer2D} instance displaying this Tree
	 */
	public Viewer2D show2D() {
		final Viewer2D viewer = new Viewer2D();
		viewer.add(this);
		if (getLabel() != null) {
			viewer.setTitle(getLabel());
		}
		viewer.show();
		return viewer;
	}

	/**
	 * Displays this Tree in an appropriate viewer (3D if the tree has depth, 2D otherwise).
	 */
	public void show() {
		if (is3D())
			show3D();
		else 
			show2D();
	}

	/**
	 * Gets the list of all nodes tagged as {@link Path#SWC_SOMA}.
	 *
	 * @return the soma nodes or null if no Paths are tagged as soma.
	 * @see #getRoot()
	 */
	public List<PointInImage> getSomaNodes() {
		final List<PointInImage> points = new ArrayList<>();
		for (final Path p : tree) {
			if (p.isPrimary() && p.getSWCType() == Path.SWC_SOMA) {
				points.add(p.getNode(0));
			}
		}
		return (points.isEmpty()) ? null : points;
	}

	/**
	 * Checks whether this Tree has a valid soma annotation, i.e., only a single
	 * primary path tagged with {@link Path#SWC_SOMA}.
	 *
	 * @return Returns true, if soma annotation is valid.
	 */
	public boolean validSoma() {
		final List<Path> somas = tree.stream().filter(path -> Path.SWC_SOMA == path.getSWCType()).toList();
		return somas.size() == 1 && somas.stream().allMatch(Path::isPrimary);
	}

	/**
	 * Gets the first node of the main primary path of this tree
	 *
	 * @return the root node, or null if the main primary path is undefined for this
	 *         tree.
	 */
	public PointInImage getRoot() {
		if (isEmpty()) return null;
		Path rootPath = get(0);
		if (size() == 1) return rootPath.getNodeWithoutChecks(0);
		if (!rootPath.isPrimary()) { // how would this ever happen?
			rootPath = null;
			for (final Path p : list()) {
				if (p.isPrimary()) {
					rootPath = p;
					break;
				}
			}
		}
		return (rootPath == null) ? null : rootPath.getNodeWithoutChecks(0);
	}

	/**
	 * Specifies the offset to be used when rendering this Tree in a
	 * {@link TracerCanvas}. Path coordinates remain unaltered.
	 *
	 * @param xOffset the x offset (in pixels)
	 * @param yOffset the y offset (in pixels)
	 * @param zOffset the z offset (in pixels)
	 */
	public void applyCanvasOffset(final double xOffset, final double yOffset,
		final double zOffset)
	{
		final PointInCanvas offset = new PointInCanvas(xOffset, yOffset, zOffset);
		tree.forEach(p -> p.setCanvasOffset(offset));
	}

	/**
	 * Translates the tree by the specified offset.
	 *
	 * @param xOffset the x offset
	 * @param yOffset the y offset
	 * @param zOffset the z offset
	 */
	public void translate(final double xOffset, final double yOffset,
		final double zOffset)
	{
		tree.forEach(p -> {
			for (int node = 0; node < p.size(); node++) {
				final PointInImage current = p.getNodeWithoutChecks(node);
				// Path#moveNode will take care of startJoins etc.
				p.moveNode(node, new PointInImage(current.x + xOffset, current.y + yOffset, current.z + zOffset));
			}
		});
		if (box != null) {
			box.origin().x += xOffset;
			box.origin().y += yOffset;
			box.origin().z += zOffset;
			box.originOpposite().x += xOffset;
			box.originOpposite().y += yOffset;
			box.originOpposite().z += zOffset;
		}
		nullifyGraphsAndPafm();
	}

	/**
	 * Scales the tree by the specified factors.
	 *
	 * @param xScale the scaling factor for x coordinates
	 * @param yScale the scaling factor for y coordinates
	 * @param zScale the scaling factor for z coordinates
	 */
	public void scale(final double xScale, final double yScale,
		final double zScale)
	{
		tree.forEach(p -> {
			for (int node = 0; node < p.size(); node++) {
				final PointInImage current = p.getNodeWithoutChecks(node);
				// Path#moveNode will take care of startJoins etc.
				p.moveNode(node, new PointInImage(current.x * xScale, current.y * yScale, current.z * zScale));
			}
		});
		if (box != null) {
			box.origin().x *= xScale;
			box.origin().y *= yScale;
			box.origin().z *= zScale;
			box.originOpposite().x *= xScale;
			box.originOpposite().y *= yScale;
			box.originOpposite().z *= zScale;
		}
		nullifyGraphsAndPafm();
	}

	/**
	 * Scales the tree by the specified factors.
	 *
	 * @param xScale the scaling factor for x coordinates
	 * @param yScale the scaling factor for y coordinates
	 * @param zScale the scaling factor for z coordinates
	 * @param radiusScale the scaling factor for node radii.
	 */
	public void scale(final double xScale, final double yScale,
		final double zScale, final double radiusScale)
	{
		scale(xScale, yScale, zScale);
		tree.forEach(p -> {
			if (p.hasRadii()) {
				for (int node = 0; node < p.size(); node++) {
					p.radii[node] *= radiusScale;
				}
			}
		});
		nullifyGraphsAndPafm();
	}

	/**
	 * Rotates the tree.
	 *
	 * @param axis the rotation axis. Either {@link #X_AXIS}, {@link #Y_AXIS}, or
	 *          {@link #Z_AXIS}.
	 * @param angle the rotation angle in degrees. Ignored if 0.
	 */
	public void rotate(final int axis, final double angle) {
		if (Double.isNaN(angle)) throw new IllegalArgumentException("Invalid angle");
		if (angle == 0d) return;
		final double radAngle = Math.toRadians(angle);
		final double sin = Math.sin(radAngle);
		final double cos = Math.cos(radAngle);
		tree.forEach(p -> {
			for (int node = 0; node < p.size(); node++) {
				final PointInImage current = p.getNodeWithoutChecks(node);
				// Path#moveNode will take care of startJoins etc.
				p.moveNode(node, rotate(current, cos, sin, axis));
			}
		});
		if (box != null) box.setComputationNeeded(true);
		nullifyGraphsAndPafm();
	}

	/**
	 * Gets all the nodes (path points) forming this tree.
	 *
	 * @return the points
	 */
	public List<PointInImage> getNodes() {
		final List<PointInImage> list = new ArrayList<>();
		for (final Path p : tree) {
			// The first node of a child path is the same as the forked point
			// on its parent, so we'll skip it if this is a child path
			for (int i = 0; i < p.size(); ++i) {
				list.add(p.getNodeWithoutChecks(i));
			}
		}
		return list;
	}

	/**
	 * Gets the total number of nodes across all paths in this Tree.
	 *
	 * @return the total node count
	 */
	public long getNodesCount() {
		return tree.stream().mapToLong(Path::size).sum();
	}

	/**
	 * Assesses whether this Tree has depth.
	 *
	 * @return true, if is 3D
	 * @throws IllegalArgumentException if tree is empty
	 */
	public boolean is3D() throws IllegalArgumentException {
		if (isEmpty())
			throw new IllegalArgumentException("Tree is empty");
		final double zRef = tree.getFirst().getNodeWithoutChecks(0).getZ();
		for (final Path p : tree) {
			for (int i = 0; i < p.size(); ++i) {
				if (p.getNodeWithoutChecks(i).getZ() != zRef) return true;
			}
		}
		return false;
	}

	/**
	 * Associates a bounding box to this tree.
	 *
	 * @param box the BoundingBox, typically referring to the image associated
	 *          with this tree
	 */
	public void setBoundingBox(final BoundingBox box) {
		this.box = (box == null) ? null : new TreeBoundingBox(box);
	}

	/**
	 * Gets the bounding box associated with this tree.
	 *
	 * @return the BoundingBox. It will be computed if no boundaries have been set.
	 */
	public BoundingBox getBoundingBox() {
		return getBoundingBox(box == null);
	}

	/**
	 * Gets the bounding box associated with this tree.
	 *
	 * @param computeIfUnset if {@code true} no BoundingBox has been explicitly
	 *          set, and, a BoundingBox will be computed from all the nodes of this
	 *          Tree
	 * @return the BoundingBox
	 */
	public BoundingBox getBoundingBox(final boolean computeIfUnset) {
		final boolean compute = box == null || computeIfUnset || box.isComputationNeeded();
		if (box == null) 
			box = new TreeBoundingBox();
		if (compute) {
			box.compute(getNodes().iterator());
			if (getProperties().get(KEY_SPATIAL_UNIT) != null)
				box.setUnit(getProperties().getProperty(KEY_SPATIAL_UNIT));
		}
		return box;
	}

	/**
	 * Gets an empty image capable of holding the skeletonized version of this tree.
	 *
	 * @param multiDThreePaneView the pane flag indicating the SNT view for this
	 *                            image e.g., {@link MultiDThreePanes#XY_PLANE}
	 * @param bitDepth            8, 16 or 32 (float)
	 * 
	 * @return the empty {@link ImagePlus} container
	 */
	public ImagePlus getImpContainer(final int multiDThreePaneView, final int bitDepth) {
		if (tree.isEmpty()) throw new IllegalArgumentException(
			"tree contains no paths");
		final BoundingBox bBox = getBoundingBox(true);
		final Path referencePath = tree.getFirst();
		bBox.origin().onPath = referencePath;
		bBox.originOpposite().onPath = referencePath;
		final PointInCanvas bound1 = bBox.origin().getUnscaledPoint(multiDThreePaneView);
		final PointInCanvas bound2 = bBox.originOpposite().getUnscaledPoint(multiDThreePaneView);

		// Padding is required to accommodate "rounding errors"
		// in PathAndFillManager.setPathPointsInVolume()
		final int xyPadding = 6; // 3 extra pixels on each margin
		final int zPadding = 2; // 1 slice above / below last point
		final int w = (int) Math.round(bound2.x - bound1.x) + xyPadding;
		final int h = (int) Math.round(bound2.y - bound1.y) + xyPadding;
		int d = (int) Math.round(bound2.z - bound1.z);
		if (d < 1) d = 1;
		if (d > 1) d += zPadding;
		return ImpUtils.create(null, w, h, d, bitDepth);
	}

	/**
	 * Skeletonizes (rasterizes) this tree on the specified image using Bresenham’s
	 * Algorithm (3D).
	 *
	 * @param destinationImp the destination image (16-bit). It is assumed that the
	 *                       image dimensions are suitable, and that the spatial
	 *                       calibration of the image is compatible with that of
	 *                       this tree. Out of bound locations will be silently
	 *                       ignored.
	 * @param value          the pixel intensity of the skeleton
	 * @throws IllegalArgumentException If image is not 16-bit grayscale (unsigned)
	 */
	public void skeletonize(final ImagePlus destinationImp, final int value) throws IllegalArgumentException {
		if (destinationImp.getType() != ImagePlus.GRAY16) {
			throw new IllegalArgumentException("Only 16-bit images supported");
		}
		final int width = destinationImp.getWidth();
		final int depth = destinationImp.getNSlices();
		final int channel = destinationImp.getC();
		final int frame = destinationImp.getT();
		final ImageStack s = destinationImp.getStack();
		final short[][] slices_data = new short[depth][];
		for (int z = 0; z < depth; ++z) {
			slices_data[z] = (short[]) s.getPixels(destinationImp.getStackIndex(channel, z + 1, frame));
		}
		initPathAndFillManager();
		pafm.setPathPointsInVolume(list(), slices_data, value, width);
	}

	/**
	 * Retrieves the rasterized skeleton of this tree at 1:1 scaling.
	 *
	 * @return the skeletonized 8-bit binary image: (skeleton: 255, background: 0).
	 * @see #skeletonize(ImagePlus, int)
	 */
	public ImagePlus getSkeleton() {
		final ImagePlus imp =  getSkeleton(65535);
		ImpUtils.convertTo8bit(imp);
		return imp;
	}

	/**
	 * Retrieves the rasterized skeleton of this tree at 1:1 scaling.
	 *
	 * @param pixelValue the voxel intensities of the skeleton. If {@code -1}, each
	 *                   path in the tree is rendered uniquely (labels image)
	 * @return the skeletonized 16-bit binary image
	 * @see #skeletonize(ImagePlus, int)
	 */
	public ImagePlus getSkeleton(final int pixelValue) {
		return getSkeletonInternal(pixelValue, false);
	}

	private ImagePlus getSkeletonInternal(final int pixelValue, final boolean ignoreDepth) {
		// Find what is the offset of the tree relative to (0,0,0).
		// We'll set padding margins similarly to getImpContainer()
		SNTUtils.log("Skeletonizing "+ getLabel());
		box = (TreeBoundingBox) getBoundingBox(true);
		final double width = box.width();
		final double height = box.height();
		final double depth = box.depth();
		final boolean threeD = depth > 0 && !ignoreDepth;
		final int xyMargin = 3;
		final int zMargin = (threeD) ? 1 : 0;
		final double xOffset = box.origin().getX() - xyMargin;
		final double yOffset = box.origin().getY() - xyMargin;
		final double zOffset = box.origin().getZ() - zMargin;
		SNTUtils.log("  Tree boundaries: " + width + "x" + height + "x" + depth);

		// Apply the translation offset to each Path as canvas offset and
		// map the path scaling to 1. We'll keep track of existing values
		// so that we can restore them after skeletonization
		final ArrayList<PointInCanvas> pics = new ArrayList<>(size());
		final ArrayList<Calibration> spacings = new ArrayList<>(size());
		for (final Path p : list()) {
			pics.add(p.getCanvasOffset());
			p.setCanvasOffset(new PointInCanvas(-xOffset, -yOffset, -zOffset));
			spacings.add(p.getCalibration());
			p.setSpacing(new Calibration()); // 1,1,1 pixel spacing
		}

		// Define image dimensions
		final int w = (int) Math.round(width) + (2 * xyMargin);
		final int h = (int) Math.round(height) + (2 * xyMargin);
		int d = (int) Math.round(depth);
		if (d < 1) d = 1;
		if (d > 1) d += (2 * zMargin);
		SNTUtils.log("  Allocating " + w + "x" + h + "x" + d + " pixels (16-bit)");
		final ImagePlus imp = ImpUtils.create("Skel " + getLabel(), w, h, (threeD)?d:1, 16);

		// Skeletonize
		skeletonize(imp, pixelValue);
		imp.getLocalCalibration().setUnit(getBoundingBox().getUnit());

		// Restore initial state
		SNTUtils.log("  Skeletonization complete");
		for (int i = 0; i < size(); i++) {
			get(i).setCanvasOffset(pics.get(i));
			get(i).setSpacing(spacings.get(i));
		}

		return imp;
	}

	/**
	 * Retrieves a 2D projection of the rasterized skeleton of this tree at 1:1
	 * scaling.
	 *
	 * @return the skeletonized 8-bit binary image: (skeleton: 255, background: 0).
	 * @see #getSkeleton()
	 */
	public ImagePlus getSkeleton2D() {
		final ImagePlus imp =  getSkeleton2D(65535);
		ImpUtils.convertTo8bit(imp);
		return imp;
	}

	/**
	 * Retrieves a 2D projection of the rasterized skeleton of this tree at 1:1
	 * scaling.
	 * 
	 * @param pixelValue the pixel intensities of the skeleton. If {@code -1}, each
	 *                   path in the tree is rendered uniquely (labels image)
	 * @return the skeletonized 16-bit binary image
	 * @see #getSkeleton(int)
	 */
	public ImagePlus getSkeleton2D(final int pixelValue) {
		return getSkeletonInternal(pixelValue, true);
	}

	/**
	 * Retrieves the number of paths in this tree.
	 *
	 * @return Returns the number of paths in this tree.
	 */
	public int size() {
		return tree.size();
	}

	/**
	 * Assigns a color to all the paths in this tree. Note that assigning a
	 * non-null color will remove node colors from Paths.
	 *
	 * @param color the color to be applied.
	 * @see Path#hasNodeColors()
	 */
	public void setColor(final ColorRGB color) {
		tree.forEach(p -> {
			p.setColor(color);
			if (color != null) p.setNodeColors(null);
		});
		this.color = color;
		if (color == null)
			getProperties().remove(TreeProperties.KEY_COLOR);
		else if (color instanceof ColorRGBA)
			getProperties().setProperty(TreeProperties.KEY_COLOR, color + "," + color.getAlpha());
		else
			getProperties().setProperty(TreeProperties.KEY_COLOR, color.toString());
	}

	/**
	 * Assigns a color to all the paths in this tree. Note that assigning a non-null
	 * color will remove node colors from Paths.
	 * 
	 * @param color the color to be applied, either a 1) HTML color codes starting
	 *              with hash ({@code #}), a color preset ("red", "blue", etc.), or
	 *              integer triples of the form {@code r,g,b} and range
	 *              {@code [0, 255]}
	 */
	public void setColor(final String color) {
		setColor(new ColorRGB(color));
	}

	/**
	 * Assigns a color to all the paths in this tree. Note that assigning a non-null
	 * color will remove node colors from Paths.
	 * 
	 * @param color               the color to be applied, either a 1) HTML color
	 *                            codes starting with hash ({@code #}), a color
	 *                            preset ("red", "blue", etc.), or integer triples
	 *                            of the form {@code r,g,b} and range
	 *                            {@code [0, 255]}
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final String color, final double transparencyPercent) {
		final ColorRGB baseColor = new ColorRGB(color);
		final ColorRGBA finalColor = new ColorRGBA(baseColor.getRed(), baseColor.getGreen(),
				baseColor.getBlue(), (int) Math.round((100 - transparencyPercent) * 255 / 100));
		setColor(finalColor);
	}

	/**
	 * Gets the color assigned to this Tree.
	 *
	 * @return the Tree color, or null if no color has been assigned
	 */
	public ColorRGB getColor() {
		final String sColor = getProperties().getProperty(Tree.KEY_COLOR);
		return (sColor == null) ? null : new ColorRGB(sColor);
	}

	/**
	 * Assigns a fixed radius to all the nodes in this tree.
	 *
	 * @param r the radius to be assigned. Setting it to 0 or Double.NaN removes
	 *          the radius attribute from the Tree
	 */
	public void setRadii(final double r) {
		tree.forEach(p -> p.setRadius(r));
		if (graph != null) graph.vertexSet().forEach(v -> v.radius = r);
		if (simplifiedGraph != null) simplifiedGraph.vertexSet().forEach(v -> v.radius = r);
	}

	/**
	 * Returns the Properties instance holding the persistent set of properties.
	 * Useful to associate metadata to this tree. E.g.
	 * 
	 * <pre>
	 * {@code
	 * getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, "um");
	 * String unit = getProperties().getProperty(Tree.KEY_SPATIAL_UNIT);
	 * getProperties().setProperty(Tree.KEY_COMPARTMENT, Tree.DENDRITIC);
	 * }
	 * </pre>
	 *
	 * @return the Properties instance
	 */
	public Properties getProperties() {
		if (properties == null) properties = new Properties();
		return properties;
	}

	/**
	 * Sets an identifying label for this Tree.
	 *
	 * @param label the identifying string
	 */
	public void setLabel(final String label) {
		this.label = label;
		if (label != null)
			getProperties().setProperty(TreeProperties.KEY_LABEL, label);
	}

	/**
	 * Returns the identifying label of this tree. When importing files, the label
	 * typically defaults to the imported filename,
	 *
	 * @return the Tree label (or null) if none has been set.
	 */
	public String getLabel() {
		return label;
	}

	public List<SWCPoint> getNodesAsSWCPoints() throws IllegalArgumentException {
		if (isEmpty()) return new ArrayList<>();
		initPathAndFillManager();
		try {
			return pafm.getSWCFor(tree);
		} catch (final SWCExportException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	/**
	 * Assembles a DirectedGraph from this Tree.
	 *
	 * @return the Tree's graph with edge weights corresponding to inter-node
	 *         distances
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public DirectedWeightedGraph getGraph() throws IllegalArgumentException {
		if (graph == null) graph = new DirectedWeightedGraph(this);
		return graph;
	}

	/**
	 * Re-assembles the DirectedGraph object returned by {@link #getGraph()}
	 * ensuring that no untracked changes exist. Usually, calling this method is not
	 * necessary since most changes to this Tree percolate to its graph
	 * representation.
	 */
	public void rebuildGraph() {
		nullifyGraphsAndPafm();
		graph = new DirectedWeightedGraph(this);
	}

	private void nullifyGraphsAndPafm() {
		graph = null;
		simplifiedGraph = null;
		pafm = null;
	}

	/**
	 * Assembles a DirectedGraph from this Tree.
	 *
	 * @param simplify if true, graph will be simplified so that Tree is only
	 *                 represented by root, branch-points and tips.
	 * @return the Tree's graph with edge weights corresponding to branch lengths
	 * @throws IllegalArgumentException if tree contains multiple roots or loops
	 */
	public DirectedWeightedGraph getGraph(final boolean simplify) throws IllegalArgumentException {
		if (simplify) {
			if (simplifiedGraph == null) simplifiedGraph = getGraph().getSimplifiedGraph();
			return simplifiedGraph;
		}
		return getGraph();
	}

	/**
	 * Script-friendly method for creating a Tree from a reconstruction file.
	 *
	 * @param filePath the absolute path to the file (.Traces, (e)SWC or JSON) to be
	 *                 imported
	 * @return the Tree instance, or null if file could not be imported
	 */
	public static Tree fromFile(final String filePath) {
		try {
			return new Tree(filePath);
		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Retrieves a list of {@link Tree}s from a single file.
	 * 
	 *
	 * @param tracesOrJsonFile the file containing the reconstructions (typically a
	 *                         .traces or .json extension). A directory is also
	 *                         supported.
	 * @return the collection of imported {@link Tree}s. An empty list is retrieved
	 *         if {@code tracesOrJsonFile} is not a valid, readable file.
	 */
	public static Collection<Tree> listFromFile(final String tracesOrJsonFile) throws IllegalArgumentException {
		File f = getFile(tracesOrJsonFile, true);
		if (f.isDirectory()) {
			return listFromDir(tracesOrJsonFile);
		}
		Collection<Tree> trees;
		final String baseName;
		try {
			if (f.getName().toLowerCase().endsWith(".json")) {
				return MouseLightLoader.extractTrees(f, "all").values();
			} else {
				final PathAndFillManager pafm = new PathAndFillManager();
				pafm.setHeadless(true);
				baseName = SNTUtils.stripExtension(f.getName());
				pafm.loadGuessingType(baseName, Files.newInputStream(f.toPath()));
				trees = pafm.getTrees();
			}
		} catch (final IOException e) {
			SNTUtils.error("File not parsed", e);
			return new ArrayList<>();
		}
        if (trees.size() == 1)
            trees.iterator().next().setLabel(baseName);
        else
            trees.forEach(t -> t.setLabel(baseName + " " + t.getLabel()));
        return trees;
	}

	/**
	 * Retrieves a list of {@link Tree}s from reconstruction files stored in a
	 * common directory.
	 *
	 * @param dir the directory containing the reconstruction files (.(e)swc, .traces,
	 *            .json extension)
	 * @return the list of imported {@link Tree}s. An empty list is retrieved if
	 *         {@code dir} is not a valid, readable directory.
	 */
	public static List<Tree> listFromDir(final String dir) {
		return listFromDir(dir, "");
	}

	/**
	 * Retrieves a list of {@link Tree}s from reconstruction files stored in a
	 * common directory matching the specified criteria.
	 *
	 * @param dir     the directory containing the reconstruction files (.(e)swc,
	 *                .traces, .json extension)
	 * @param pattern the filename substring (case-sensitive) to be matched. Only
	 *                filenames containing {@code pattern} will be imported from the
	 *                directory. {@code null} allowed.
	 * @return the list of imported {@link Tree}s. An empty list is retrieved if
	 *         {@code dir} is not a valid, readable directory.
	 */
	public static List<Tree> listFromDir(final String dir, final String pattern) {
		return listFromDir(dir, pattern, (String[])null);
	}

	/**
	 * Retrieves a list of {@link Tree}s from reconstruction files stored in a
	 * common directory matching the specified criteria.
	 *
	 * @param dir     the directory containing the reconstruction files (.(e)swc,
	 *                .traces, .json extension)
	 * @param pattern the filename substring (case-sensitive) to be matched. Only
	 *                filenames containing {@code pattern} will be imported from the
	 *                directory. {@code null} allowed.
	 * @param swcTypes SWC type(s) a string with at least 2 characters describing
	 *                 the SWC type allowed in the subtree (e.g., 'soma', 'axn', or
	 *                 'dendrite'). Ignored when {@code null}, or 'all'.
	 * @return the list of imported {@link Tree}s. An empty list is retrieved if
	 *         {@code dir} is not a valid, readable directory.
	 */
	public static List<Tree> listFromDir(final String dir, final String pattern, final String... swcTypes) {
		final List<Tree> trees = new ArrayList<>();
		if (dir == null) return trees;
		final File dirFile = getFile(dir, true);
		final File[] treeFiles = SNTUtils.getReconstructionFiles(dirFile, pattern);
		if (treeFiles == null) {
			return trees;
		}
		for (final File treeFile : treeFiles) {
			final Collection<Tree> treesInFile = Tree.listFromFile(treeFile.getAbsolutePath());
			if (treesInFile != null) {
				if (swcTypes == null || (swcTypes.length == 1 && "all".equalsIgnoreCase(swcTypes[0]))) {
					trees.addAll(treesInFile);
				} else {
					treesInFile.forEach(t -> trees.add(t.subTree(swcTypes)));
				}
			}
		}
		return trees;
	}

	/**
	 * Returns the SWC Type flags used by SNT.
	 * 
	 * @return the map mapping swct type flags (e.g., {@link Path#SWC_AXON},
	 *         {@link Path#SWC_DENDRITE}, etc.) and their respective labels
	 */
	public static Map<Integer, String> getSWCTypeMap() {
		final HashMap<Integer, String> map = new HashMap<>();
		map.put(Path.SWC_UNDEFINED, Path.SWC_UNDEFINED_LABEL);
		map.put(Path.SWC_SOMA, Path.SWC_SOMA_LABEL);
		map.put(Path.SWC_AXON, Path.SWC_AXON_LABEL);
		map.put(Path.SWC_DENDRITE, Path.SWC_DENDRITE_LABEL);
		map.put(Path.SWC_APICAL_DENDRITE, Path.SWC_APICAL_DENDRITE_LABEL);
		map.put(Path.SWC_CUSTOM, Path.SWC_CUSTOM_LABEL);
		map.put(Path.SWC_UNSPECIFIED, Path.SWC_UNSPECIFIED_LABEL);
		map.put(Path.SWC_GLIA_PROCESS, Path.SWC_GLIA_PROCESS_LABEL);
		map.put(Path.SWC_CUSTOM2, Path.SWC_CUSTOM2_LABEL);
		return map;
	}

	/**
	 * Saves this Tree to an SWC file.
	 *
	 * @param filePath the absolute path of the output file. {@code .swc} is
	 *                 automatically appended if {@code filePath} does not include
	 *                 an extension. If a label has been assigned, {@code filePath}
	 *                 can also be a directory. If this Tree contains multiple
	 *                 roots, each rooted structure will be saved on a series of
	 *                 files with 3-digit identifiers appended to the specified file
	 *                 path (e.g., -000.swc, -001.swc, etc.).
	 * @return true, if file successfully saved.
	 * @see #setLabel(String)
	 */
	public boolean saveAsSWC(final String filePath) {
		if (list() == null || list().isEmpty() || filePath == null || filePath.isEmpty())
			return false;
		initPathAndFillManager();
		File file = getFile(filePath, false);
		if (file.isDirectory() && getLabel() != null) {
			final String fName = (getLabel().toLowerCase().endsWith(".swc")) ? getLabel() : getLabel() + ".swc";
			file = new File(file.getAbsolutePath(), fName);
		} else if (!filePath.toLowerCase().endsWith(".swc")) {
			file = new File(filePath + ".swc");
		}
		return pafm.savetoFileOrFileSeries(file);
	}

	/**
	 * Saves this Tree to a .TRACES (XML, compressed) file.
	 *
	 * @param filePath the absolute path of the output file. {@code .traces} is
	 *                 automatically appended if {@code filePath} does not include
	 *                 an extension. If a label has been assigned, {@code filePath}
	 *                 can also be a directory.
	 * @return true, if file successfully saved.
	 * @see #setLabel(String)
	 */
	public boolean save(final String filePath) {
		if (list() == null || list().isEmpty() || filePath == null || filePath.isEmpty())
			return false;
		initPathAndFillManager();
		File file = getFile(filePath, false);
		if (file.isDirectory() && getLabel() != null) {
			final String fName = (getLabel().toLowerCase().endsWith(".traces")) ? getLabel() : getLabel() + ".traces";
			file = new File(file.getAbsolutePath(), fName);
		} else if (!filePath.toLowerCase().endsWith(".traces")) {
			file = new File(filePath + ".traces");
		}
		try {
			pafm.writeXML(file.getAbsolutePath(), true);
		} catch (final IOException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	/**
	 * Assigns a numeric property to this Tree.
	 * 
	 * @param value the value to be assigned to this Tree.
	 * @see #getAssignedValue()
	 */
	public void assignValue(final double value) {
		this.value = value;
	}

	/**
	 * Retrieves an approximate estimate of Tree's volume by approximating the
	 * volume of each path, and summing to total. The volume of each path is
	 * computed assuming the volume of each of inter-node segment to be that of a
	 * truncated cone (Frustum).
	 * 
	 * @return the approximate volume or NaN if this Tree's paths have no radius
	 * @see Path#getApproximatedVolume()
	 */
	public double getApproximatedVolume() {
		return tree.stream().mapToDouble(Path::getApproximatedVolume).sum();
	}

	/**
	 * Retrieves an approximate estimate of Tree's surface are by approximating the
	 * surface area of each path, and summing to total. The surface of each path is
	 * computed assuming the lateral surface area of a conical frustum between
	 * nodes.
	 * 
	 * @return the approximate surface area or NaN if this Tree's paths have no
	 *         radius
	 * @see Path#getApproximatedSurface()
	 */
	public double getApproximatedSurface() {
		return tree.stream().mapToDouble(Path::getApproximatedSurface).sum();
	}

	/**
	 * Retrieves the numeric property assigned to this Tree.
	 * 
	 * @return the assigned value.
	 * @see #assignValue(double)
	 */
	public double getAssignedValue() {
		return value;
	}

	private void initPathAndFillManager() {
		if (pafm == null) {
			pafm = new PathAndFillManager();
			// Since the paths of this tree may be associated with other pafm instances and
			// paths are passed to a pafm by reference, we must ensure path is added to the
			// pafm without changes to (path ID, tree ID, etc)
			for (final Path p : list()) {
				pafm.addPath(p, p.getID(), p.getTreeID());
			}
		}
	}

	@SuppressWarnings("unused")
	private Tree cloneOld() {
		final Tree clone = new Tree();
		clone.setLabel(getLabel());
		clone.setBoundingBox(box);
		for (final Path path : list()) clone.add(path.clone());
		return clone;
	}

	@Override
	public Tree clone() {
        final Tree clone = new Tree();
		clone.setLabel(getLabel());
		clone.applyProperties(this);
		clone.setColor(getColor());
		clone.assignValue(getAssignedValue());
		final Map<Integer, Path> idToPathMap = new HashMap<>();
		for (final Path path : list()) {
			final Path clonePath = path.clone();
			idToPathMap.put(clonePath.getID(), clonePath);
			// Clear these, but don't unset startJoin yet
			clonePath.somehowJoins.clear();
			clonePath.children.clear();
			clone.add(clonePath);
		}
		for (final Path path : clone.list()) {
			if (path.getStartJoins() == null) continue;
			final Path join = idToPathMap.get(path.getStartJoins().getID());
			final PointInImage joinPoint = path.getStartJoinsPoint().clone();
			if (join != null && joinPoint != null) {
				path.unsetStartJoin();
				path.setStartJoin(join, joinPoint);
			}
		}
		return clone;
	}

	private static class TreeBoundingBox extends BoundingBox {

		private boolean dimensionsNeedToBeComputed;

		TreeBoundingBox(final BoundingBox box) {
			this.origin = box.origin();
			this.originOpposite = box.originOpposite();
			this.xSpacing = box.xSpacing;
			this.ySpacing = box.ySpacing;
			this.zSpacing = box.zSpacing;
			this.info = box.info;
			this.spacingUnit = box.getUnit();
		}

		TreeBoundingBox() {
			super();
		}

		void setComputationNeeded(final boolean bool) {
			dimensionsNeedToBeComputed = bool;
			if (bool) reset();
		}

		boolean isComputationNeeded() {
			return dimensionsNeedToBeComputed;
		}

		@Override
		public String toString() {
			return "[TreeBoundingBox: origin: " + this.origin + ", originOpposite: " + this.originOpposite + "]";
		}

	}

	/**
	 * Assigns distinct colors to a collection of Trees.
	 *
	 * @see SNTColor#getDistinctColors(int)
	 */
	public static void assignUniqueColors(final Collection<Tree> trees) {
		final ColorRGB[] colors = SNTColor.getDistinctColors(trees.size());
		int i = 0;
		for (Iterator<Tree> it = trees.iterator(); it.hasNext(); i++) {
			it.next().setColor(colors[i]);
		}
	}

	/**
	 * Assigns distinct colors to a collection of Trees.
	 *
	 * @param excludedHue an optional string defining a hue to be excluded. Either 'red', 'green', or 'blue'.
	 * @see SNTColor#getDistinctColors(int, String)
	 */
	public static void assignUniqueColors(final Collection<Tree> trees, final String excludedHue) {
		final ColorRGB[] colors = SNTColor.getDistinctColors(trees.size(), excludedHue);
		int i = 0;
		for (Iterator<Tree> it = trees.iterator(); it.hasNext(); i++) {
			it.next().setColor(colors[i]);
		}
	}
	/**
	 * Assigns the spatial calibration of an image to this Tree.
	 *
	 * @param imp the image providing the spatial calibration. Null allowed.
	 */
	public void assignImage(final ImagePlus imp) {
		initPathAndFillManager();
		Calibration cal;
		if (imp == null) {
			pafm.resetSpatialSettings(true);
			cal = new Calibration();
		} else {
			pafm.assignSpatialSettings(imp);
			cal = imp.getCalibration();
			getProperties().setProperty(TreeProperties.KEY_IMG, imp.getTitle());
		}
		list().forEach(path -> path.setSpacing(cal));
		getProperties().setProperty(KEY_SPATIAL_UNIT, cal.getUnit());
	}

	/**
	 * Assigns spatial calibration from a Dataset to this Tree.
	 *
	 * @param dataset the Dataset providing the spatial calibration. Null allowed.
	 */
	public void assignImage(final Dataset dataset) {
		initPathAndFillManager();
		Calibration cal;
		if (dataset == null) {
			pafm.resetSpatialSettings(true);
			cal = new Calibration();
		} else {
			cal = pafm.assignSpatialSettings(dataset);
		}
		list().forEach(path -> path.setSpacing(cal));
		getProperties().setProperty(KEY_SPATIAL_UNIT, cal.getUnit());
	}

	/**
	 * Swaps the coordinates of two axes in this Tree.
	 *
	 * @param axis1 the first axis. Either {@link #X_AXIS}, {@link #Y_AXIS}, or
	 *              {@link #Z_AXIS}
	 * @param axis2 the second axis. Either {@link #X_AXIS}, {@link #Y_AXIS}, or
	 *              {@link #Z_AXIS}
	 */
	public void swapAxes(final int axis1, final int axis2) {
		final List<Integer> validAxes = Arrays.asList(X_AXIS, Y_AXIS, Z_AXIS);
		if (!validAxes.contains(axis1) || !validAxes.contains(axis2)) {
			throw new IllegalArgumentException("Unrecognized axis");
		}
		if (axis1 == axis2) {
			return;
		}
		// The axis that remains unchanged
		final int axis3 = X_AXIS + Y_AXIS + Z_AXIS - axis1 - axis2;
		for (final Path p : this.list()) {
			for (int i = 0; i < p.size(); i++) {
				final PointInImage current = p.getNodeWithoutChecks(i);
				// swap axis1 and axis2
				p.moveNode(i, swap(current, axis1, axis2, axis3));
			}
		}
		nullifyGraphsAndPafm();
	}

	private void projectXZ() {
		for (final Path p : this.list()) {
			for (int i = 0; i < p.size(); i++) {
				final PointInImage xy = p.getNodeWithoutChecks(i);
				p.moveNode(i, new PointInImage(xy.x, xy.z, xy.y));
			}
		}
		nullifyGraphsAndPafm();
	}

	private void projectZY() {
		for (final Path p : this.list()) {
			for (int i = 0; i < p.size(); i++) {
				final PointInImage xy = p.getNodeWithoutChecks(i);
				p.moveNode(i, new PointInImage(xy.z, xy.y, xy.x));
			}
		}
		nullifyGraphsAndPafm();
	}

	private PointInImage swap(final PointInImage pim, int swapAxis1, int swapAxis2, int unchangedAxis) {
		// swap axis1 and axis2
		final Map<Integer, Double> coordMap = new HashMap<>();
		coordMap.put(swapAxis1, pim.getCoordinateOnAxis(swapAxis2));
		coordMap.put(swapAxis2, pim.getCoordinateOnAxis(swapAxis1));
		coordMap.put(unchangedAxis, pim.getCoordinateOnAxis(unchangedAxis));
		return new PointInImage(coordMap.get(X_AXIS), coordMap.get(Y_AXIS), coordMap.get(Z_AXIS));
	}

	private PointInImage rotate(final PointInImage pim, final double cos, final double sin, final int untouchedAxis) {
        return switch (untouchedAxis) {
            // See http://www.petercollingridge.appspot.com/3D-tutorial
            case Z_AXIS -> new PointInImage(pim.x * cos - pim.y * sin, pim.y * cos + pim.x * sin, pim.z);
            case Y_AXIS -> new PointInImage(pim.x * cos - pim.z * sin, pim.y, pim.z * cos + pim.x * sin);
            case X_AXIS -> new PointInImage(pim.x, pim.y * cos - pim.z * sin, pim.z * cos + pim.y * sin);
            default -> throw new IllegalArgumentException("Unrecognized rotation axis" + untouchedAxis);
        };
	}

	private static File getFile(final String filename, final boolean checkIfAvailable) {
		File f;
		if (filename.startsWith("~" + File.separator))
			f = new File(filename.replaceFirst("^~", System.getProperty("user.home")));
		else
			f = new File(filename);
		if (checkIfAvailable && !SNTUtils.fileAvailable(f))
			throw new IllegalArgumentException("File is not available: " + filename);
		return f;
	}

	/**
	 * Transforms this tree in place using standardized flags.
	 *
	 * @param transformOptions Space-separated String indicating <code>projection</code>, <code>translation</code>, and
	 *                         <code>rotation</code>. E.g., <code>"zero-origin upright"</code>.
	 *                         <code>
	 *                         projection flags:
	 *                         - "zy": ZY projection
	 *                         - "xz": XZ projection
	 *                         translation flags:
	 *                         - "zero-origin": each tree is translated so that its root has (0,0,0) coordinates
	 *                         rotation flags:
	 *                         - "upright": each tree is rotated to vertically align its graph geodesic
	 *                         - "r#": With # specifying a positive integer (e.g., r90): each tree is rotated by the
	 *                         specified angle (in degrees)
	 *                         </code>
	 * @see #transform(Collection, String, boolean)
	 */
	public void transform(final String transformOptions) {
		transform(Collections.singleton(this), transformOptions, true).iterator().next();
	}

	/**
	 * Retrieves a transformed duplicate of this tree.
	 *
	 * @param transformOptions see {@link #transform(String)}
	 * @return the duplicated copy of this Tree transformed according to transformOptions
	 */
	public Tree transformedCopy(final String transformOptions) {
		return transform(Collections.singleton(this), transformOptions, true).iterator().next();
	}

	/**
	 * Transforms a collection of trees using standardized flags.
	 *
	 * @param trees            the collection of Trees to be transformed
	 * @param transformOptions Space-separated String indicating <code>projection</code>, <code>translation</code>, and
	 *                         <code>rotation</code>. E.g., <code>"zero-origin upright"</code>.
	 *                         <code>
	 *                         projection flags:
	 *                         - "zy": ZY projection
	 *                         - "xz": XZ projection
	 *                         translation flags:
	 *                         - "zero-origin": each tree is translated so that its root has (0,0,0) coordinates
	 *                         rotation flags:
	 *                         - "upright-geodesic": each tree is rotated to vertically align its graph geodesic
	 *                         - "upright-tips": each tree is rotated to vertically align its [root, tips centroid] vector
	 *                         - "r#": With # specifying a positive integer (e.g., r90): each tree is rotated by the
	 *                         specified angle (in degrees)
	 *                         </code>
	 * @param inPlace          If false, input trees are not affected, and transformations occur on a duplicated collection.
	 *                         Ignored if transformationOptions are not valid
	 * @return the collection with transformed trees, or the input collection if transformOptions were invalid
	 */
	public static Collection<Tree>
	transform(final Collection<Tree> trees, final String transformOptions, final boolean inPlace) {
		final String options = transformOptions.toLowerCase();
		final boolean isZY = options.contains("zy") || options.contains("yz");
		final boolean isXZ = options.contains("xz") || options.contains("zx");
		final boolean zeroRoot = options.contains("zero") || options.contains("origin");
		final boolean straighten = options.contains("upright");
		final boolean centroid = options.contains("tips");
		int rotAngle = 0;
		final Matcher m = Pattern.compile("r(\\d+)").matcher(options);
		while (m.find()) rotAngle += Integer.parseInt(m.group(1));
		final Collection<Tree> renderingTrees;
		int rotationAxis;
		if (isXZ)
			rotationAxis = Tree.Y_AXIS;
		else if (isZY)
			rotationAxis = Tree.X_AXIS;
		else
			rotationAxis = Tree.Z_AXIS;
		if (zeroRoot || isZY || isXZ || straighten || rotAngle != 0) {
			renderingTrees = (inPlace) ? trees : trees.stream().map(Tree::clone).collect(Collectors.toList());
			final int finalRotAngle = rotAngle;
			renderingTrees.forEach(tree -> {
				double angle = 0;
				if (finalRotAngle != 0 || straighten) {
					final Path refPath;
					if (centroid) {
						refPath = tree.get(0).createPath();
						refPath.addNode(tree.getRoot());
						final Set<PointInImage> tips = new TreeStatistics(tree).getTips();
						tips.remove(tree.getRoot());
						if (!tips.isEmpty()) refPath.addNode(SNTPoint.average(tips));
					} else {
						refPath = tree.getGraph().getLongestPath(true);
					}
					if (straighten && isXZ) {
						angle = refPath.getExtensionAngleXZ(false);
					} else if (straighten && isZY) {
						// NB: must be negative for ImagePlus and Viewer2D!???
						angle = -refPath.getExtensionAngleZY(false);
					} else if (straighten) {
						angle = refPath.getExtensionAngleXY(false);
					}
					if (!Double.isNaN(angle)) {
						// since angle is relative to horizontal, we need to add 90 degrees
						if (angle != 0) angle += 90;
						tree.rotate(rotationAxis, -angle + finalRotAngle); // does nothing if angle is 0
					}
				}
				if (isZY)
					tree.projectZY();
				else if (isXZ)
					tree.projectXZ();
				if (zeroRoot) {
					final PointInImage root = tree.getRoot();
					tree.translate(-root.getX(), -root.getY(), -root.getZ());
				}
			});
		} else {
			renderingTrees = trees;
		}
		return renderingTrees;
	}

	/**
	 * Retrieves the convex hull defined by all the nodes of this tree.
	 *
	 * @return the convex hull. Either {@link ConvexHull2D} (if this tree is two-dimensional) or
	 * {@link ConvexHull3D} (if this tree is three-dimensional)
	 */
	public AbstractConvexHull getConvexHull() {
		return getConvexHull("all");
	}

	/**
	 * Retrieves the convex hull of this tree.
	 *
	 * @param type the description of the point cloud defining the convex hull (i.e., "tips"/"end-points",
	 *             "junctions"/"branch points", or "all" (default))
	 * @return the convex hull. Either {@link ConvexHull2D} (if this tree is two-dimensional) or
	 * {@link ConvexHull3D} (if this tree is three-dimensional)
	 */
	public AbstractConvexHull getConvexHull(final String type) {
		return switch (type.toLowerCase().trim()) {
			case "tips", "endings", "end points", "end-points", "terminals" ->
					getConvexHull(new TreeStatistics(this).getTips());
			case "bps", "forks", "junctions", "fork points", "junction points", "branch points" ->
					getConvexHull(new TreeStatistics(this).getBranchPoints());
			default -> getConvexHull(getNodes());
		};
	}

	private AbstractConvexHull getConvexHull(final Collection<PointInImage> nodes) {
		AbstractConvexHull hull;
		if (is3D()) {
			hull = new ConvexHull3D(SNTUtils.getContext(), nodes);
		} else {
			hull = new ConvexHull2D(SNTUtils.getContext(), nodes);
		}
		return hull;
	}

	/* IDE debug method */
	public static void main(final String[] args) {

		//final Tree tree = new Tree("/home/tferr/Downloads/test_swc.swc");
		final Tree tree = new SNTService().demoTree("fractal");
		tree.swapAxes(Z_AXIS, Y_AXIS);
		TreeStatistics analyzer = new TreeStatistics(tree);
		System.out.println("Creating graph...");

		DirectedWeightedGraph graph = tree.getGraph();
		final int nodes = tree.getNodes().size();
		final int points = tree.getNodesAsSWCPoints().size();
		final int vertices = graph.vertexSet().size();
		final int bps = analyzer.getBranchPoints().size();
		final int tips = analyzer.getTips().size();
		final int branches = analyzer.getBranches().size();
		final int primaryBranches = analyzer.getPrimaryBranches().size();
		final double primaryLength = analyzer.getPrimaryLength();

		System.out.println("Creating reference subtree...");
		MouseLightLoader loader = new MouseLightLoader("AA0174");
		Tree refSubTree = loader.getTree("axon");
		DirectedWeightedGraph refGraph = refSubTree.getGraph();
		TreeStatistics refAnalyzer = new TreeStatistics(refSubTree);

		System.out.println("Creating Subtree...");
		Tree aTree = tree.subTree("axon");
		double total = 0;
		final int nTimes = 100;
		for (int i = 0; i < nTimes; i++) {
			long before = System.currentTimeMillis();
			aTree = tree.subTree("axon");
			long after = System.currentTimeMillis();
			total += after-before;
		}
		System.out.println("Avg time (all runs): " + SNTUtils.formatDouble(total % 1000 /nTimes, 4));

		graph = aTree.getGraph();
		analyzer = new TreeStatistics(aTree);
		System.out.println("Subtree properties:");
		System.out.println("Label:     \t\t" + aTree.getLabel());
		System.out.println("nPaths:    \t\t" + analyzer.getNPaths() + "/" + refAnalyzer.getNPaths());
		System.out.println("vertices:  \t\t" + graph.vertexSet().size() + "/" + refGraph.vertexSet().size());
		System.out.println("nodes:     \t\t" + aTree.getNodes().size() + "/" +  refSubTree.getNodes().size() );
		System.out.println("SWCPoints: \t\t" + aTree.getNodesAsSWCPoints().size() + "/" + refSubTree.getNodesAsSWCPoints().size());
		System.out.println("I branches:\t\t" + analyzer.getPrimaryBranches().size() + "/" + refAnalyzer.getPrimaryBranches().size() );
		System.out.println("I length:  \t\t" + analyzer.getPrimaryLength() + "/" + refAnalyzer.getPrimaryLength());
		System.out.println("Branches:  \t\t" + analyzer.getBranches().size() + "/" + refAnalyzer.getBranches().size());

		// Did the filtering affect original tree?
		analyzer = new TreeStatistics(tree);
		graph = tree.getGraph();
		System.out.println("\nOriginal Tree Before/After Filtering:");
		System.out.println("Label:      \t\t" + tree.getLabel());
		System.out.println("vertices:   \t\t" + graph.vertexSet().size() + "/" + vertices);
		System.out.println("nodes:      \t\t" + tree.getNodes().size() + "/" + nodes);
		System.out.println("SWCPoints:  \t\t" + tree.getNodesAsSWCPoints().size() + "/" + points);
		System.out.println("I branches: \t\t" + analyzer.getPrimaryBranches().size() + "/" + primaryBranches);
		System.out.println("I length:   \t\t" + analyzer.getPrimaryLength() + "/" + primaryLength);
		System.out.println("Branches:   \t\t" + analyzer.getBranches().size() + "/" + branches);
		System.out.println("Tips:       \t\t" + analyzer.getTips().size() + "/" + tips);
		System.out.println("BPs:        \t\t" + analyzer.getBranchPoints().size() + "/" + bps);

	}

}
