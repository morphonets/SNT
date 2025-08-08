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

package sc.fiji.snt.analysis;

import net.imagej.ImageJ;
import net.imglib2.display.ColorTable;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.text.WordUtils;
import org.scijava.Context;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.util.ShollPoint;
import sc.fiji.snt.viewer.MultiViewer2D;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Class for color coding {@link Tree}s.
 *
 * @author Tiago Ferreira
 */
public class TreeColorMapper extends ColorMapper {

	/* For convenience keep references to TreeStatistics fields */
	/** Mapping property: Internode angle */
	public static final String INTER_NODE_ANGLE = MultiTreeStatistics.INTER_NODE_ANGLE;
	/** Mapping property: Internode distance */
	public static final String INTER_NODE_DISTANCE = MultiTreeStatistics.INTER_NODE_DISTANCE;
	/** Mapping property: Path spine/varicosity density */
	public static final String PATH_AVG_SPINE_DENSITY = TreeStatistics.PATH_SPINE_DENSITY;
	/** Mapping property: Path frame */
	public static final String PATH_FRAME = "Path frame";
	/** Mapping property: Path order */
	public static final String PATH_ORDER = TreeStatistics.PATH_ORDER;
	/** Mapping property: Path's extension angle */
	public static final String PATH_EXT_ANGLE = TreeStatistics.PATH_EXT_ANGLE;
	/** Mapping property: Path's extension angle (Rel.) */
	public static final String PATH_EXT_ANGLE_REL = TreeStatistics.PATH_EXT_ANGLE_REL;
	/** Mapping property: Path's extension angle (XY plane) */
	public static final String PATH_EXT_ANGLE_XY = TreeStatistics.PATH_EXT_ANGLE_XY;
	/** Mapping property: Path's extension angle (XZ plane) */
	public static final String PATH_EXT_ANGLE_XZ = TreeStatistics.PATH_EXT_ANGLE_XZ;
	/** Mapping property: Path's extension angle (ZY plane) */
	public static final String PATH_EXT_ANGLE_ZY = TreeStatistics.PATH_EXT_ANGLE_ZY;
	/** Mapping property: Path distance to soma */
	public static final String PATH_DISTANCE = "Path distance to soma";
	/** Mapping property: Path length */
	public static final String PATH_LENGTH = TreeStatistics.PATH_LENGTH;
	/** Mapping property: Path mean radius */
	public static final String PATH_MEAN_RADIUS = TreeStatistics.PATH_MEAN_RADIUS;
	/** Mapping property: No. of branch points */
	public static final String N_BRANCH_POINTS = TreeStatistics.N_BRANCH_POINTS;
	/** Mapping property: No. of nodes */
	public static final String N_NODES = TreeStatistics.N_NODES;
	/** Mapping property: No. of spines/varicosities */
	public static final String N_SPINES =  TreeStatistics.N_SPINES;
	/** Mapping property: Node radius */
	public static final String NODE_RADIUS = TreeStatistics.NODE_RADIUS;
	/** Mapping property: X coordinates */
	public static final String X_COORDINATES = TreeStatistics.X_COORDINATES;
	/** Mapping property: Y coordinates */
	public static final String Y_COORDINATES = TreeStatistics.Y_COORDINATES;
	/** Mapping property: Z coordinates */
	public static final String Z_COORDINATES = TreeStatistics.Z_COORDINATES;
	/** Mapping property: Sholl inters. counts */
	public static final String SHOLL_COUNTS = "Sholl inters. (root centered)"; //FIXME: getNormalizedMeasurement() will not allow '-'
	/** Mapping property: Horton-Strahler branch orders */
	public static final String STRAHLER_ORDERS = "Horton-Strahler orders";
	/** Mapping property: Node intensity nodes */
	public static final String VALUES = TreeStatistics.VALUES;
	/** Mapping property: Tags/filename */
	public static final String TAG_FILENAME = "Tags/filename";

	private static final String INTERNAL_COUNTER = "Id";

	private static final String[] ALL_FLAGS = {
			INTER_NODE_ANGLE, INTER_NODE_DISTANCE, N_BRANCH_POINTS, N_NODES, N_SPINES, NODE_RADIUS,
			PATH_AVG_SPINE_DENSITY, PATH_DISTANCE, PATH_EXT_ANGLE, PATH_EXT_ANGLE_REL,
			PATH_EXT_ANGLE_XY, PATH_EXT_ANGLE_XZ, PATH_EXT_ANGLE_ZY,
			PATH_FRAME, PATH_LENGTH, PATH_MEAN_RADIUS, PATH_ORDER, SHOLL_COUNTS, STRAHLER_ORDERS,
			TAG_FILENAME, VALUES, X_COORDINATES, Y_COORDINATES, Z_COORDINATES
	};

	protected ArrayList<Path> paths;
	private int internalCounter = 1;
	private final List<Tree> mappedTrees;
	private boolean nodeMapping;

	/**
	 * Instantiates the mapper.
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 */
	public TreeColorMapper(final Context context) {
		mappedTrees = new ArrayList<>();
		context.inject(this);
	}

	/**
	 * Instantiates the mapper. Note that because the instance is not aware of
	 * any context, script-friendly methods that use string as arguments may fail
	 * to retrieve referenced Scijava objects.
	 */
	public TreeColorMapper() {
		this(SNTUtils.getContext());
	}

	/**
	 * Gets the list of supported mapping metrics.
	 *
	 * @return the list of mapping metrics.
	 */
	public static List<String> getMetrics() {
		return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
	}

	public ColorTable getColorTable(final String lut) {
		initLuts();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			if (entry.getKey().contains(lut)) {
				try {
					return lutService.loadLUT(entry.getValue());
				}
				catch (final IOException e) {
					e.printStackTrace();
				}
			}
		}
		return null;
	}

	protected void mapToProperty(final String measurement,
		final ColorTable colorTable)
	{
		map(measurement, colorTable);
		final String cMeasurement = getNormalizedMeasurement(measurement);
		switch (cMeasurement) {
			case STRAHLER_ORDERS:
				assignStrahlerOrderToNodeValues();
				integerScale = true;
				mapToNodeProperty(VALUES);
				break;
			case SHOLL_COUNTS:
				final Tree tree = new Tree(paths);
				final TreeParser parser = new TreeParser(tree);
				parser.setCenter(tree.getRoot());
				parser.setStepSize(0);
				parser.parse();
				map(tree, new LinearProfileStats(parser.getProfile()), colorTable);
				integerScale = true;
				break;
			case PATH_DISTANCE:
				final TreeParser dummy = new TreeParser(new Tree(paths));
				try {
					dummy.setCenter(TreeParser.ROOT_NODES_SOMA);
				}
				catch (final IllegalArgumentException ignored) {
					SNTUtils.log(
						"No soma attribute found... Defaulting to average of all root nodes");
					dummy.setCenter(TreeParser.ROOT_NODES_ANY);
				}
				final PointInImage center = dummy.getCenter();
				final PointInImage root = new PointInImage(center.x, center.y,
					center.z);
				mapPathDistances(root);
				break;
			case PATH_AVG_SPINE_DENSITY:
			case PATH_EXT_ANGLE:
			case PATH_EXT_ANGLE_REL:
			case PATH_EXT_ANGLE_XY:
			case PATH_EXT_ANGLE_XZ:
			case PATH_EXT_ANGLE_ZY:
			case PATH_FRAME:
			case PATH_LENGTH:
			case PATH_MEAN_RADIUS:
			case PATH_ORDER:
			case N_BRANCH_POINTS:
			case N_NODES:
			case N_SPINES:
			case INTERNAL_COUNTER:
			case TAG_FILENAME:
				mapToPathProperty(cMeasurement);
				break;
			case X_COORDINATES:
			case Y_COORDINATES:
			case Z_COORDINATES:
			case NODE_RADIUS:
			case VALUES:
			case INTER_NODE_DISTANCE:
			case INTER_NODE_ANGLE:
				mapToNodeProperty(cMeasurement);
				break;
			default:
				throw new IllegalArgumentException("Unknown parameter");
		}
	}

	private void assignStrahlerOrderToNodeValues() {
		final StrahlerAnalyzer sa = new StrahlerAnalyzer(new Tree(paths));
		sa.getNodes().forEach((order, nodeList) -> {
			for (final SWCPoint node : nodeList) {
				for (final Path p : paths) {
					if (!p.equals(node.getPath())) {
						continue;
					}
					for (int i = 0; i < p.size(); i++) {
						if (node.isSameLocation(p.getNode(i))) {
							p.setNodeValue(order, i);
						}
					}
				}
			}
		});
	}

	private void mapToPathProperty(final String measurement)
	{
		final List<MappedPath> mappedPaths = new ArrayList<>();
		switch (measurement) {
			case PATH_ORDER:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) p.getOrder()));
				break;
			case PATH_EXT_ANGLE:
			case PATH_EXT_ANGLE_REL:
				integerScale = true;
				final boolean rel = PATH_EXT_ANGLE_REL.equals(measurement);
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getExtensionAngle3D(rel)));
				break;
			case PATH_EXT_ANGLE_XY:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getExtensionAngleXY()));
				break;
			case PATH_EXT_ANGLE_XZ:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getExtensionAngleXZ()));
				break;
			case PATH_EXT_ANGLE_ZY:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getExtensionAngleZY()));
				break;
			case PATH_LENGTH:
				integerScale = false;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getLength()));
				break;
			case PATH_MEAN_RADIUS:
				integerScale = false;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getMeanRadius()));
				break;
			case PATH_AVG_SPINE_DENSITY:
				integerScale = false;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, p.getSpineOrVaricosityCount()/p.getLength()));
				break;
			case N_NODES:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) p.size()));
				break;
			case N_BRANCH_POINTS:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) p.getBranchPoints()
						.size()));
				break;
			case N_SPINES:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) p.getSpineOrVaricosityCount()));
				break;
			case INTERNAL_COUNTER:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) internalCounter));
				break;
			case TAG_FILENAME:
				integerScale = true;
				final List<MappedTaggedPath> mappedTaggedPaths = new ArrayList<>();
				final TreeSet<String> tags = new TreeSet<>();
				for (final Path p : paths) {
					final MappedTaggedPath mp = new MappedTaggedPath(p);
					mappedTaggedPaths.add(mp);
					tags.add(mp.mappedTag);
				}
				final int nTags = tags.size();
				for (final MappedTaggedPath p : mappedTaggedPaths) {
					mappedPaths.add(new MappedPath(p.path, (double) tags.headSet(
						p.mappedTag).size() / nTags));
				}
				break;
			case PATH_FRAME:
				integerScale = true;
				for (final Path p : paths)
					mappedPaths.add(new MappedPath(p, (double) p.getFrame()));
				break;
			default:
				throw new IllegalArgumentException("Unknown parameter");
		}
		for (final MappedPath mp : mappedPaths) {
			mp.path.setNodeColors(null);
			mp.path.setColor(getColor(mp.mappedValue));
		}
		nodeMapping = false;
	}

	private void mapToNodeProperty(final String measurement)
	{
		if (Double.isNaN(min) || Double.isNaN(max) || min > max) {
			final TreeStatistics tStats = new TreeStatistics(new Tree(paths));
			final SummaryStatistics sStats = tStats.getSummaryStats(measurement);
			setMinMax(sStats.getMin(), sStats.getMax());
		}
		for (final Path p : paths) {
			final Color[] colors = new Color[p.size()];
			for (int node = 0; node < p.size(); node++) {
				double value;
				switch (measurement) {
					case X_COORDINATES:
						value = p.getNode(node).x;
						break;
					case Y_COORDINATES:
						value = p.getNode(node).y;
						break;
					case Z_COORDINATES:
						value = p.getNode(node).z;
						break;
					case NODE_RADIUS:
						value = p.getNodeRadius(node);
						break;
					case VALUES:
						value = p.getNodeValue(node);
						break;
					case INTER_NODE_DISTANCE:
						if (node == 0)
							value = Double.NaN;
						else
							value = p.getNode(node).distanceTo(p.getNode(node-1));
						break;
					case INTER_NODE_ANGLE:
						value = p.getAngle(node);
						break;
					default:
						throw new IllegalArgumentException("Unknow parameter");
				}
				colors[node] = getColor(value);
			}
			p.setNodeColors(colors);
		}
		nodeMapping = true;
	}

	private void mapPathDistances(final PointInImage root) {
		if (root == null) {
			throw new IllegalArgumentException("source point cannot be null");
		}

		final boolean setLimits = (Double.isNaN(min) || Double.isNaN(max) ||
			min > max);
		if (setLimits) {
			min = Float.MAX_VALUE;
			max = 0f;
		}
		SNTUtils.log("Node values will be wiped after distance calculations");

		// 1st pass: Calculate distances for primary paths.
		for (final Path p : paths) {
			if (p.isPrimary()) {
				double dx = p.getNode(0).distanceTo(root);
				p.setNodeValue(dx, 0);
				for (int i = 1; i < p.size(); ++i) {
					final double dxPrev = p.getNodeValue(i - 1);
					final PointInImage prev = p.getNode(i - 1);
					final PointInImage curr = p.getNode(i);
					dx = curr.distanceTo(prev) + dxPrev;
					p.setNodeValue(dx, i);
					if (setLimits) {
						if (dx > max) max = dx;
						if (dx < min) min = dx;
					}
				}
			}
		}

		// 2nd pass: Calculate distances for remaining paths
		for (final Path p : paths) {
			if (p.isPrimary()) continue;
			double dx = p.getParentPath().getNodeValue(p.getParentPath().getNodeIndex(p.getBranchPoint())); // very inefficient
			p.setNodeValue(dx + p.getNode(0).distanceTo(p.getBranchPoint()), 0);
			for (int i = 1; i < p.size(); ++i) {
				final double dxPrev = p.getNodeValue(i - 1);
				final PointInImage prev = p.getNode(i - 1);
				final PointInImage curr = p.getNode(i);
				dx = curr.distanceTo(prev) + dxPrev;
				p.setNodeValue(dx, i);
				if (setLimits) {
					if (dx > max) max = dx;
					if (dx < min) min = dx;
				}
			}
		}

		// now color nodes
		if (setLimits) setMinMax(min, max);
		SNTUtils.log("Coloring nodes by path distance to " + root);
		SNTUtils.log("Range of mapped distances: " + min + "-" + max);
		for (final Path p : paths) {
			for (int node = 0; node < p.size(); node++) {
				// if (p.isPrimary()) System.out.println(p.getNodeValue(node));
				p.setNodeColor(getColor(p.getNodeValue(node)), node);
			}
		}

		// Wipe node values so that computed distances don't
		// get mistakenly interpreted as pixel intensities
		paths.forEach(p -> p.setNodeValues(null));
		nodeMapping = true;
	}


	/**
	 * Colorizes a tree using Sholl data.
	 *
	 * @param tree the tree to be colorized
	 * @param profile the mapping Sholl profile
	 * @param colorTable the color table specifying the color mapping. Null not allowed.
	 */
	public void map(final Tree tree, final Profile profile, final ColorTable colorTable) {
		map(tree, new LinearProfileStats(profile), colorTable);
	}

	/**
	 * Colorizes a tree using Sholl data.
	 *
	 * @param tree the tree to be colorized
	 * @param stats the LinearProfileStats instance containing the mapping
	 *          profile. if a polynomial fit has been successfully performed,
	 *          mapping is done against the fitted data, otherwise sampled
	 *          intersections are used.
	 * @param colorTable the color table specifying the color mapping. Null not
	 *          allowed.
	 */
	public void map(final Tree tree, final LinearProfileStats stats,
		final ColorTable colorTable)
	{
		final ShollPoint uCenter = stats.getProfile().center();
		if (uCenter == null) {
			throw new IllegalArgumentException("Center unknown");
		}
		paths = tree.list();
		this.colorTable = colorTable;
		final boolean useFitted = stats.validFit();
		SNTUtils.log("Mapping to fitted values: " + useFitted);
		setMinMax(stats.getMin(useFitted), stats.getMax(useFitted));
		final double stepSize = stats.getProfile().stepSize();
		for (final ProfileEntry entry : stats.getProfile().entries()) {
			for (final Path p : tree.list()) {
				for (int node = 0; node < p.size(); node++) {
					final double dx = uCenter.distanceTo(p.getNode(node));
					if (dx >= entry.radius && dx < entry.radius + stepSize) {
						p.setNodeColor(getColor(entry.count), node);
					}
				}
			}
		}
		// second pass: Resolve remaining non-mapped nodes
		// https://github.com/morphonets/SNT/issues/176
		for (final Path p : tree.list()) {
			SNTColor.interpolateNullEntries(p.getNodeColors());
		}
		mappedTrees.add(tree);
		nodeMapping = true;
	}

	/**
	 * Colorizes a tree after the specified measurement.
	 *
	 * @param tree the tree to be mapped
	 * @param measurement the measurement ({@link #PATH_ORDER} }{@link #PATH_LENGTH},
	 *          etc.)
	 * @param colorTable the color table specifying the color mapping. Null not
	 *          allowed.
	 */
	public void map(final Tree tree, final String measurement,
		final ColorTable colorTable)
	{
		try {
			this.paths = tree.list();
			mapToProperty(measurement, colorTable);
			mappedTrees.add(tree);
		} catch (final IllegalArgumentException ignored) {
			final String educatedGuess = tryReallyHardToGuessMetric(measurement);
			System.out.println("Mapping to \""+ measurement +"\" failed. Assuming \""+ educatedGuess+"\"");
			if ("unknown".equals(educatedGuess))
				throw new IllegalArgumentException("Unknown parameter: "+ measurement);
			else {
				mapToProperty(educatedGuess, colorTable);
				mappedTrees.add(tree);
			}
		}
	}

	/**
	 * Colorizes a collection of points after the specified measurement. Mapping
	 * bounds are automatically determined. This is a convenience method to extend
	 * color mapping to point clouds.
	 *
	 * @param points      the points to be mapped
	 * @param measurement the measurement ({@link #Z_COORDINATES}, etc.). Note that
	 *                    if {@code points} do not encode a valid Tree only
	 *                    metrics applicable to coordinates are expected.
	 * @param lut         the lookup table specifying the color mapping
	 */
	public void map(final Collection<? extends PointInImage> points, final String measurement,
			final String lut)
	{
		final boolean swcPoints = points.stream().anyMatch(p -> p instanceof SWCPoint);
		@SuppressWarnings("unchecked")
		Tree tree = (swcPoints) ? new Tree((Collection<SWCPoint>) points, "") : null;
		if (tree == null) {
			// We'll just assemble a Tree from the points and hope for the best
			tree = new Tree();
			for (PointInImage point : points) {
				if (point.onPath != null)
					tree.add(point.onPath);
				else {
					Path p = new Path(1, 1, 1, "NA");
					p.addNode(point);
					tree.add(p);
				}
			}
		}
		map(tree, measurement, lut);
	}

	private static String tryReallyHardToGuessMetric(final String guess) {
		final String normGuess = guess.toLowerCase();
		if (normGuess.contains("inter") && normGuess.contains("node")) {
			return (normGuess.contains("angle")) ? INTER_NODE_ANGLE : INTER_NODE_DISTANCE;
		}
		if (normGuess.contains("soma") || normGuess.contains("path d")) {
			return PATH_DISTANCE;
		}
		if (normGuess.contains("length") || normGuess.contains("cable")) {
			return PATH_LENGTH;
		}
		if (normGuess.contains("strahler") || normGuess.contains("horton") || normGuess.contains("h-s")) {
			return STRAHLER_ORDERS;
		}
		if (normGuess.contains("sholl") || normGuess.contains("inters")) {
			return SHOLL_COUNTS;
		}
		if (normGuess.contains("path") && normGuess.contains("order")) {
			return PATH_ORDER;
		}
		if (normGuess.contains("path") && normGuess.contains("angle")) {
			if (normGuess.contains("xz"))
				return PATH_EXT_ANGLE_XZ;
			else if (normGuess.contains("zy"))
				return PATH_EXT_ANGLE_ZY;
			else
				return PATH_EXT_ANGLE_XY;
		}
		if (normGuess.contains("bp") || normGuess.contains("branch points") || normGuess.contains("junctions")) {
			return N_BRANCH_POINTS;
		}
		if (normGuess.contains("nodes")) {
			return N_NODES;
		}
		if (normGuess.contains("radi")) {
			if (normGuess.contains("mean") || normGuess.contains("avg") || normGuess.contains("average")) {
				return PATH_MEAN_RADIUS;
			}
			else {
				return NODE_RADIUS;
			}
		}
		if (normGuess.contains("spines") || normGuess.contains("varicosities")) {
			if (normGuess.contains("mean") || normGuess.contains("avg") || normGuess.contains("average") || normGuess.contains("dens")) {
				return PATH_AVG_SPINE_DENSITY;
			}
			else {
				return N_SPINES;
			}
		}
		if (normGuess.contains("values") || normGuess.contains("intensit")) {
			return VALUES;
		}
		if (normGuess.contains("tag") || normGuess.contains("name") || normGuess.contains("label")) {
			return TAG_FILENAME;
		}
		if (normGuess.contains("frame")) {
			return PATH_FRAME;
		}
		if (normGuess.matches(".*\\bx\\b.*")) {
			return X_COORDINATES;
		}
		if (normGuess.matches(".*\\by\\b.*")) {
			return Y_COORDINATES;
		}
		if (normGuess.matches(".*\\bz\\b.*")) {
			return Z_COORDINATES;
		}
		return "unknown";
	}

	protected static String getNormalizedMeasurement(final String measurement) {
		if (Arrays.stream(ALL_FLAGS).anyMatch(measurement::equalsIgnoreCase)) {
			// This is just so that we can use capitalized strings in the GUI
			// and lower case strings in scripts
			return WordUtils.capitalize(measurement, '-');
		}
		final String normMeasurement = tryReallyHardToGuessMetric(measurement);
		if (!measurement.equals(normMeasurement)) {
			SNTUtils.log("\"" + normMeasurement + "\" assumed");
			if ("unknown".equals(normMeasurement)) {
				throw new IllegalArgumentException("Unrecognizable measurement! "
						+ "Maybe you meant one of the following?: " + Arrays.toString(ALL_FLAGS));
			}
		}
		return normMeasurement;
	}

	/**
	 * Colorizes a tree after the specified measurement. Mapping bounds are
	 * automatically determined.
	 *
	 * @param tree the tree to be mapped
	 * @param measurement the measurement ({@link #PATH_ORDER} }{@link #PATH_LENGTH},
	 *          etc.)
	 * @param lut the lookup table specifying the color mapping
	 */
	public void map(final Tree tree, final String measurement, final String lut) {
		map(tree, measurement, getColorTable(lut));
	}

	/**
	 * Colorizes a list of trees, with each tree being assigned a LUT index.
	 *
	 * @param trees the list of trees to be colorized
	 * @param lut the lookup table specifying the color mapping
	 */
	public void mapTrees(final List<Tree> trees, final String lut) {
		setMinMax(1, trees.size());
		for (final ListIterator<Tree> it = trees.listIterator(); it.hasNext();) {
			map(it.next(), INTERNAL_COUNTER, lut);
			internalCounter = it.nextIndex();
		}
	}

	/**
	 * Gets the available LUTs.
	 *
	 * @return the set of keys, corresponding to the set of LUTs available
	 */
	public Set<String> getAvailableLuts() {
		initLuts();
		return luts.keySet();
	}

	/**
	 * Assembles a {@link MultiViewer2D Multi-pane viewer} using all the Trees
	 * mapped so far.
	 *
	 * @return the multi-viewer instance
	 */
	public MultiViewer2D getMultiViewer() {
		final List<Viewer2D> viewers = new ArrayList<>(mappedTrees.size());
		mappedTrees.forEach(tree -> {
			final Viewer2D viewer = new Viewer2D(SNTUtils.getContext());
			viewer.add(tree);
			viewers.add(viewer);
		});
		final MultiViewer2D multiViewer = new MultiViewer2D(viewers);
		if (colorTable != null && !mappedTrees.isEmpty())
			multiViewer.setColorBarLegend(this);
		return multiViewer;
	}

	public boolean isNodeMapping() {
		return nodeMapping;
	}

	private class MappedPath {

		private final Path path;
		private final Double mappedValue;

		private MappedPath(final Path path, final Double mappedValue) {
			this.path = path;
			this.mappedValue = mappedValue;
			if (mappedValue > max) max = mappedValue;
			if (mappedValue < min) min = mappedValue;
		}
	}

	private static class MappedTaggedPath {

        private final Path path;
		private final String mappedTag;

		private MappedTaggedPath(final Path path) {
			this.path = path;
            final Pattern pattern = Pattern.compile("\\{(\\w+)\\b");
            final Matcher matcher = pattern.matcher(path.getName());
			mappedTag = (matcher.find()) ? matcher.group(1) : "";
		}
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
//		final List<Tree> trees = new ArrayList<>();
//		for (int i = 0; i < 10; i++) {
//			final Tree tree = new Tree(SNTUtils.randomPaths());
//			tree.rotate(Tree.Z_AXIS, i * 20);
//			trees.add(tree);
//		}
//		final Viewer2D plot = new Viewer2D(ij.context());
//		plot.addTrees(trees, "Ice.lut");
//		plot.addColorBarLegend();
//		plot.showPlot();
//		
		final SNTService sntService = ij.context().getService(SNTService.class);
		final List<Tree> trees = sntService.demoTrees();
		TreeColorMapper mapper = new TreeColorMapper(ij.context());
		//mapper.setMinMax(1000, 20000);
		final Viewer3D viewer = new Viewer3D(ij.context());
		final Viewer2D viewer2 = new Viewer2D(ij.context());

		for (Tree tree : trees) {
			mapper.map(tree, SHOLL_COUNTS, "Ice.lut");
			viewer.add(tree);
			viewer2.add(tree);
		}
		viewer.show();
		viewer2.show();
	}

}
