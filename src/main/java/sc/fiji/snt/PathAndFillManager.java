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

package sc.fiji.snt;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij3d.Content;
import ij3d.UniverseListener;
import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;
import org.json.JSONException;
import org.scijava.java3d.View;
import org.scijava.util.ColorRGB;
import org.scijava.vecmath.Color3f;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.util.*;
import util.Bresenham3D;
import util.XMLFunctions;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The PathAndFillManager is responsible for importing, handling and managing of
 * Paths and Fills. Typically, a PathAndFillManager is accessed from a
 * {@link SNT} instance, but accessing a PathAndFillManager directly is useful
 * for batch/headless operations.
 * 
 * @author Mark Longair
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class PathAndFillManager extends DefaultHandler implements
	UniverseListener
{

	protected static final int TRACES_FILE_TYPE_COMPRESSED_XML = 1;
	protected static final int TRACES_FILE_TYPE_UNCOMPRESSED_XML = 2;
	protected static final int TRACES_FILE_TYPE_SWC = 3;
	protected static final int TRACES_FILE_TYPE_ML_JSON = 4;

	private static final DecimalFormat fileIndexFormatter = new DecimalFormat(
		"000");

	protected SNT plugin;
	private boolean headless = false;
	private double x_spacing;
	private double y_spacing;
	private double z_spacing;
	private String spacing_units;
	/** BoundingBox for existing Paths */
	private BoundingBox boundingBox;

	private final ArrayList<Path> allPaths;
	private final Map<Integer, Path> pathIdMap;
	private final Map<String, Path> pathNameMap;
	private final Map<String, Path> pathNameLowercaseMap;
	private final ArrayList<Fill> allFills;
	private final Map<Fill, FillerThread> loadedFills;
	private final ArrayList<PathAndFillListener> listeners;
	private final HashSet<Path> selectedPathsSet;
	private int maxUsedPathID = 0;
	private int maxUsedTreeID = 0;

	private Fill current_fill;
	private Path current_path;
	private HashMap<Integer, Integer> startJoins;
	private HashMap<Integer, Integer> startJoinsIndices;
	private HashMap<Integer, PointInImage> startJoinsPoints;

	@Deprecated
	private HashMap<Integer, Integer> endJoins;
	@Deprecated
	private HashMap<Integer, Integer> endJoinsIndices;
	@Deprecated
	private HashMap<Integer, PointInImage> endJoinsPoints;

	private HashMap<Integer, Boolean> useFittedFields;
	private HashMap<Integer, Integer> fittedFields;
	private HashMap<Integer, Integer> fittedVersionOfFields;
	private ArrayList<int[]> sourcePathIDForFills;

	private int last_fill_node_id;
	private int last_fill_id;
	private HashSet<Integer> foundIDs;
	protected boolean enableUIupdates = true;

	/**
	 * Instantiates a new PathAndFillManager using default values. Voxel
	 * dimensions are inferred from the first imported set of nodes.
	 */
	public PathAndFillManager() {
		allPaths = new ArrayList<>();
		pathIdMap = new HashMap<>();
		pathNameMap = new HashMap<>();
		pathNameLowercaseMap = new HashMap<>();
		allFills = new ArrayList<>();
		loadedFills = new HashMap<>();
		listeners = new ArrayList<>();
		selectedPathsSet = new HashSet<>();
		resetSpatialSettings(false);
	}

	protected PathAndFillManager(final SNT plugin) {
		this();
		this.plugin = plugin;
		syncSpatialSettingsWithPlugin();
		addPathAndFillListener(plugin);
	}

	/**
	 * Instantiates a new PathAndFillManager imposing specified pixel dimensions,
	 * which may be required for pixel operations. New {@link Path}s created under
	 * this instance, will adopt the specified spacing details.
	 *
	 * @param x_spacing the 'voxel width'
	 * @param y_spacing the 'voxel height'
	 * @param z_spacing the 'voxel depth'
	 * @param spacing_units the spacing units (e.g., 'um', 'mm)
	 */
	public PathAndFillManager(final double x_spacing, final double y_spacing,
		final double z_spacing, final String spacing_units)
	{
		this();
		boundingBox.setSpacing(x_spacing, y_spacing, z_spacing, spacing_units);
		boundingBox.setOrigin(new PointInImage(0, 0, 0));
		this.x_spacing = x_spacing;
		this.y_spacing = y_spacing;
		this.z_spacing = z_spacing;
		this.spacing_units = boundingBox.getUnit();
	}

	protected void syncSpatialSettingsWithPlugin() {
		x_spacing = plugin.x_spacing;
		y_spacing = plugin.y_spacing;
		z_spacing = plugin.z_spacing;
		spacing_units = plugin.spacing_units;
		boundingBox.setOrigin(new PointInImage(0, 0, 0));
		boundingBox.setSpacing(x_spacing, y_spacing, z_spacing,
			spacing_units);
		boundingBox.setDimensions(plugin.width, plugin.height, plugin.depth);
	}

	protected void assignSpatialSettings(final ImagePlus imp) {
		final Calibration cal = imp.getCalibration();
		x_spacing = cal.pixelWidth;
		y_spacing = cal.pixelHeight;
		z_spacing = cal.pixelDepth;
		spacing_units = SNTUtils.getSanitizedUnit(cal.getUnit());
		boundingBox.setOrigin(new PointInImage(0, 0, 0));
		boundingBox.setSpacing(x_spacing, y_spacing, z_spacing,
			spacing_units);
		boundingBox.setDimensions(imp.getWidth(), imp.getHeight(), imp.getNSlices());
		if (size() > 0) {
			final PointInCanvas zeroOffset = new PointInCanvas(0, 0, 0);
			getPaths().forEach(path -> {
				path.setSpacing(cal);
				path.setCanvasOffset(zeroOffset);
			});
		}
	}

	protected void resetSpatialSettings(final boolean alsoResetPaths) {
		boundingBox = new BoundingBox();
		x_spacing = boundingBox.xSpacing;
		y_spacing = boundingBox.ySpacing;
		z_spacing = boundingBox.zSpacing;
		spacing_units = boundingBox.getUnit();
		if (alsoResetPaths && size() > 0) {
			final Calibration cal = boundingBox.getCalibration();
			getPaths().forEach(path -> path.setSpacing(cal));
		}
	}

	/**
	 * Rebuild relationships. Will wipe existing fitted flavors.
	 */
	protected void rebuildRelationships() {

		Path[] primaryPaths = getPathsStructured(allPaths);
		if (primaryPaths == null || primaryPaths.length == 0) {
			return;
		}

		enableUIupdates = false;
		// Reset tree labels, discard all fitted paths, including imported ones
		allPaths.forEach( p -> {
			p.discardFit();
			p.fittedVersionOf = null;
			p.setTreeLabel(null);
		});

		resetIDs();
		for (Path p : primaryPaths) {
			++maxUsedTreeID;
			p.setOrder(1);
			Stack<Path> pathStack = new Stack<>();
			pathStack.push(p);
			while (!pathStack.isEmpty()) {
				Path current = pathStack.pop();
				current.setIDs(++maxUsedPathID, maxUsedTreeID);
				//String tags = PathManagerUI.extractTagsFromPath(current);
				//String newName = getDefaultName(current);
				//current.setName((tags.isEmpty()) ? newName : newName + "{" + tags + "}");
				current.discardFit();
				for (Path child : current.children) {
					child.setOrder(current.getOrder() +1);
					pathStack.push(child);
				}
			}

		}

		// Delete any rogue stand-alone paths that may still exist
		while (allPaths.remove(null));
		enableUIupdates = true;
		resetListeners(null);
	}

	/**
	 * Returns the number of Paths in the PathAndFillManager list.
	 *
	 * @return the the number of Paths
	 */
	public int size() {
		return allPaths.size();
	}

	/**
	 * Adds a PathAndFillListener. This is used by the interface to have changes
	 * in the path manager reported so that they can be reflected in the UI.
	 *
	 * @param listener the listener
	 */
	public synchronized void addPathAndFillListener(
		final PathAndFillListener listener)
	{
		listeners.add(listener);
	}

	/**
	 * Returns the Path at the specified position in the PathAndFillManager list.
	 *
	 * @param i the index of the Path
	 * @return the Path at the specified index
	 */
	public synchronized Path getPath(final int i) {
		return allPaths.get(i);
	}

	/**
	 * Returns the Path with the specified name.
	 *
	 * @param name the name of the Path to be retrieved
	 * @param caseSensitive If true, case considerations are ignored
	 * @return the Path with the specified name, or null if name was not found.
	 */
	public synchronized Path getPathFromName(final String name,
		final boolean caseSensitive)
	{
		return caseSensitive ? pathNameMap.get(name) : pathNameLowercaseMap.get(name.toLowerCase(Locale.ROOT));
	}

	protected synchronized Path getPathFrom3DViewerName(final String name) {
		for (final Path p : allPaths) {
			if (p.nameWhenAddedToViewer == null) continue;
			if (name.equals(p.nameWhenAddedToViewer)) return p;
		}
		return null;
	}

	/**
	 * Returns the Path with the specified id.
	 *
	 * @param id the id of the Path to be retrieved
	 * @return the Path with the specified id, or null if id was not found.
	 */
	public synchronized Path getPathFromID(final int id) {
		return pathIdMap.get(id);
	}

	/*
	 * This is called to update the PathAndFillManager's idea of which paths are
	 * currently selected. This is also propagated to:
	 *
	 * (a) Each Path object (so that the 3D viewer can reflect the change, for
	 * instance.)
	 *
	 * (b) All the registered PathAndFillListener objects.
	 */
	protected synchronized void setSelected(final Collection<Path> selectedPaths,
		final Object sourceOfMessage)
	{
		selectedPathsSet.clear();
		if (selectedPaths != null) {
			// selectedPathsSet.addAll(selectedPaths);
			selectedPaths.forEach(p -> {
				Path pathToSelect = p;
				if (pathToSelect.getUseFitted()) pathToSelect = pathToSelect.getFitted();
				// pathToSelect.setSelected(true);
				selectedPathsSet.add(pathToSelect);
			});
		}
		for (final PathAndFillListener pafl : listeners) {
			if (pafl != sourceOfMessage)
				// The source of the message already knows the states:
				pafl.setSelectedPaths(selectedPathsSet, this);
		}
		if (plugin != null) {
			plugin.updateTracingViewers(true);
		}
	}

	/**
	 * Checks if a Path is currently selected in the GUI.
	 *
	 * @param path the path to be checked
	 * @return true, if is selected
	 */
	public synchronized boolean isSelected(final Path path) {
		return selectedPathsSet.contains(path);
	}

	/**
	 * Gets all paths selected in the GUI
	 *
	 * @return the collection of selected paths
	 */
	public Set<Path> getSelectedPaths() {
		return selectedPathsSet;
	}

	public Collection<Path> getPathsInROI(final Roi roi) {
		final List<Path> paths = new ArrayList<>();
		for (final Path p : getPaths()) {
			if (containedByRoi(p, roi))
				paths.add(p);
		}
		return paths;
	}

	private boolean containedByRoi(final Path p, final Roi roi) {
		for (int i = 0; i < p.size(); i++) {
			if (roi.contains(p.getXUnscaled(i), p.getYUnscaled(i)))
				return true;
		}
		return false;
	}

	/**
	 * Checks whether at least one Path is currently selected in the UI.
	 *
	 * @return true, if successful
	 */
	public boolean anySelected() {
		return selectedPathsSet.size() > 0;
	}

	protected File getSWCFileForIndex(final String prefix, final int index) {
		return new File(prefix + "-" + fileIndexFormatter.format(index) + ".swc");
	}

	/**
	 * Exports all as Paths as SWC file(s). Multiple files are created if multiple
	 * Trees exist.
	 *
	 * @param baseFilename the file path (including common basename) for exported
	 *                     files. The {@code .swc} extension can be omitted. Each
	 *                     traced structure will be saved into a dedicated file
	 *                     using a 3-digits identifier. E.g., if only a single
	 *                     structure exists, specifying {@code /path/to/results},
	 *                     generates a {@code /path/to/results-000.swc} file.
	 * @return true, if successful
	 */
	public synchronized boolean exportAllPathsAsSWC(final String baseFilename) {
		return exportAllPathsAsSWC(getPathsStructured(), baseFilename);
	}

	public synchronized boolean exportTree(final int treeIndex, final File file) {
		return exportConnectedStructureAsSWC(getPathsStructured()[treeIndex], file);
	}

	protected synchronized boolean savetoFileOrFileSeries(final File file) {
		final Path[] pathsStructured = getPathsStructured();
		return (pathsStructured.length == 1) ? exportTree(0, file) : exportAllPathsAsSWC(pathsStructured, file.getAbsolutePath());
	}

	protected synchronized boolean exportAllPathsAsSWC(final Path[] primaryPaths, final String baseFilename) {
		final String prefix = SNTUtils.stripExtension(baseFilename);
		int i = 0;
		for (final Path primaryPath : primaryPaths) {
			final File swcFile = getSWCFileForIndex(prefix, i);
			if (exportConnectedStructureAsSWC(primaryPath, swcFile)) ++i;
		}
		return i > 0;
	}
	protected synchronized boolean exportConnectedStructureAsSWC(final Path primaryPath, final File swcFile) {
		{
			final HashSet<Path> connectedPaths = new HashSet<>();
			final LinkedList<Path> nextPathsToConsider = new LinkedList<>();
			nextPathsToConsider.add(primaryPath);
			while (nextPathsToConsider.size() > 0) {
				final Path currentPath = nextPathsToConsider.removeFirst();
				connectedPaths.add(currentPath);
				for (final Path joinedPath : currentPath.somehowJoins) {
					if (!connectedPaths.contains(joinedPath)) nextPathsToConsider.add(
						joinedPath);
				}
			}

			List<SWCPoint> swcPoints = null;
			try {
				swcPoints = getSWCFor(connectedPaths);
			}
			catch (final SWCExportException see) {
				error("" + see.getMessage());
				return false;
			}

			if (plugin != null) plugin.showStatus(0, 0, "Exporting SWC data to " +
				swcFile.getAbsolutePath());

			try {
				final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
					new FileOutputStream(swcFile), StandardCharsets.UTF_8));
				flushSWCPoints(swcPoints, pw);
			}
			catch (final IOException ioe) {
				error("Saving to " + swcFile.getAbsolutePath() + " failed.");
				SNTUtils.error("IOException", ioe);
				return false;
			}
		}
		if (plugin != null) plugin.showStatus(0, 0, "Export finished.");
		return true;
	}

	/**
	 * Sets whether this PathAndFillManager instance should run headless.
	 *
	 * @param headless true to activate headless calls, otherwise false
	 */
	public void setHeadless(final boolean headless) {
		this.headless = headless;
	}

	private void errorStatic(final String msg) {
		if (headless || GraphicsEnvironment.isHeadless()) {
			SNTUtils.error(msg);
		}
		else {
			GuiUtils.errorPrompt(msg);
		}
	}

	private void error(final String msg) {
		if (!headless && plugin != null && plugin.isUIready()) plugin.error(msg);
		else errorStatic(msg);
	}

	protected void flushSWCPoints(final List<SWCPoint> swcPoints,
		final PrintWriter pw)
	{
		pw.println("# Exported from SNT v" +
			SNTUtils.VERSION + " on " + LocalDateTime.of(LocalDate.now(), LocalTime
				.now()));
		pw.println("# https://imagej.net/SNT");
		pw.println("#");
		if (plugin != null && plugin.accessToValidImageData()) {
			pw.println("# All positions and radii in " + spacing_units);
			if (usingNonPhysicalUnits())
				pw.println("# WARNING: Usage of pixel coordinates does not respect the SWC specification");
			else
				pw.println("# Voxel separation (x,y,z): " + x_spacing + ", " + y_spacing + ", " + z_spacing);
			pw.println("#");
		}
		SWCPoint.flush(swcPoints, pw);
		pw.close();
	}

	protected boolean usingNonPhysicalUnits() {
		return (new Calibration().getUnits()).equals(spacing_units) || (SNTUtils
			.getSanitizedUnit(null).equals(spacing_units) && x_spacing * y_spacing *
				z_spacing == 1d);
	}

	protected void updateBoundingBox() {
		final BoundingBox previousBox = getBoundingBox(false);
		boundingBox = getBoundingBox(true);
		if (plugin != null) {
			plugin.getPrefs().setTemp(SNTPrefs.RESIZE_REQUIRED, !previousBox.contains(boundingBox));
		}
	}

	/**
	 * Returns the BoundingBox enclosing all nodes of all existing Paths.
	 *
	 * @param compute If true, BoundingBox dimensions will be computed for all the
	 *          existing Paths. If false, the last computed BoundingBox will be
	 *          returned. Also, if BoundingBox is not scaled, its spacing will be
	 *          computed from the smallest inter-node distance of an arbitrary '
	 *          large' Path. Computations of Path boundaries typically occur
	 *          during import operations.
	 * @return the BoundingBox enclosing existing nodes, or an 'empty' BoundingBox
	 *         if no computation of boundaries has not yet occurred. Output is
	 *         never null.
	 */
	public BoundingBox getBoundingBox(final boolean compute) {
		if (boundingBox == null) boundingBox = new BoundingBox();
		if (!compute || getPaths().size() == 0) return boundingBox;
		SNTUtils.log("Computing bounding box...");
		final Iterator<PointInImage> allPointsIt = allPointsIterator();
		boundingBox.compute(allPointsIt);
		return boundingBox;
	}

	protected boolean allPathsShareSameSpatialCalibration() {
		if (!getPaths().isEmpty()) {
			final Calibration ref = getPaths().get(0).getCalibration();
			for (int i = 1; i < getPaths().size(); i++) {
				if (!ref.equals(getPaths().get(i).getCalibration()))
					return false;
			}
		}
		return true;
	}

	/*
	 * This method returns an array of the "primary paths", which should be
	 * displayed at the top of a tree-like hierarchy.
	 *
	 * The paths actually form a graph, of course, but most UIs will want to display
	 * the graph as a tree.
	 */
	public synchronized Path[] getPathsStructured() {
		return getPathsStructured(allPaths);
	}

	/**
	 * Returns all the paths associated with this PathAndFillManager instance,
	 * grouped by Tree.
	 *
	 * @return the Trees associated with this PathAndFillManager instance
	 */
	public Collection<Tree> getTrees() {
		final HashMap<String, Tree> map = new HashMap<>();
		allPaths.forEach(p->{
			final Path pathToUse = (p.getUseFitted()) ? p.getFitted() : p;
			String treeID = pathToUse.getTreeLabel();
			if (map.get(treeID) == null) {
				final Tree tree = new Tree();
				tree.setLabel(treeID);
				tree.add(pathToUse);
				map.put(treeID, tree);
			} else {
				map.get(treeID).add(pathToUse);
			}
		});
		map.values().forEach( tree -> renameTreeAfterPrimaryPath(tree));
		return map.values();
	}

	protected boolean multipleTreesExist() {
		if (allPaths.isEmpty()) return false;
		final int refID = allPaths.get(0).getTreeID();
		for (int i = 1; i < allPaths.size(); i++) {
			final int id = allPaths.get(i).getTreeID();
			if (id != refID) return true;
		}
		return false;
	}

	private void renameTreeAfterPrimaryPath(final Tree tree) {
		final String label = tree.getLabel(); // never null here
		if (label.startsWith("Cell ")) {
			for (final Path p : tree.list()) {
				if (p.isPrimary()) {
					tree.setLabel(tree.getLabel() + " rooted in " + p.getName());
					return;
				}
			}
		}
	}

	public synchronized Path[] getPathsStructured(final Collection<Path> paths) {
		final ArrayList<Path> primaryPaths = new ArrayList<>();

		/*
		 * Some paths may be explicitly marked as primary, so extract those and
		 * everything connected to them first. If you encounter another path marked as
		 * primary when exploring from these then that's an error...
		 */

		final Map<Path, List<Path>> pathChildrenMap = new HashMap<>();
		for (final Path p : paths) {
			if (p.isPrimary()) {
				primaryPaths.add(p);
				continue;
			}
			pathChildrenMap.putIfAbsent(p.getStartJoins(), new ArrayList<>());
			pathChildrenMap.get(p.getStartJoins()).add(p);
		}
		for (final Path p : pathChildrenMap.keySet()) {
			p.children.clear();
			p.children.addAll(pathChildrenMap.get(p));
		}
		return primaryPaths.toArray(new Path[] {});
	}

	public synchronized List<SWCPoint> getSWCFor(final Collection<Path> paths)
			throws SWCExportException
	{
		final Map<Path, List<Path>> pathChildrenMap = new HashMap<>();
		final Set<PointInImage> joinPointSet = new HashSet<>();
		final Set<Path> primaryPaths = new HashSet<>();
		for (final Path path : paths) {
			final Path startJoins = path.getStartJoins();
			if (startJoins == null) {
				primaryPaths.add(path);
				continue;
			}
			// HACK: this will let us find the SWCPoint id of the startJoinsPoint later
			// PointInImage equality depends on both xyz location and onPath
			// clone the point so we don't change the onPath property of the input startJoinsPoint
			final PointInImage startJoinsPoint = path.getStartJoinsPoint().clone();
			startJoinsPoint.onPath = startJoins;
			joinPointSet.add(startJoinsPoint);
			pathChildrenMap.putIfAbsent(startJoins, new ArrayList<>());
			pathChildrenMap.get(startJoins).add(path);
		}
		if (primaryPaths.isEmpty()) {
			throw new SWCExportException("The paths you select for SWC export must include a primary path " +
					"(i.e., one at the top level in the Path Manager tree)");
		}
		else if (primaryPaths.size() > 1) {
			throw new SWCExportException("Multiple primary Paths detected");
		}
		int swcPointId = 1;
		final Set<Path> pathsAlreadyDone = new HashSet<>();
		final List<SWCPoint> result = new ArrayList<>();
		final Map<PointInImage, Integer> joinPointIdMap = new HashMap<>();
		final Deque<Path> pathStack = new ArrayDeque<>();
		pathStack.push(primaryPaths.iterator().next());
		while (!pathStack.isEmpty()) {
			final Path path = pathStack.pop();
			final boolean hasAnnotations = path.hasNodeAnnotations();
			final boolean hasHemisphereFlags = path.hasNodeHemisphereFlags();
			final Color pathColor = path.getColor();
			final String pathTags = PathManagerUI.extractTagsFromPath(path);
			final boolean hasNodeValues = path.hasNodeValues();
			for (int i = 0; i < path.size(); ++i) {
				final PointInImage pim = path.getNode(i);
				// This is where our hack comes into play
				if (joinPointSet.contains(pim)) {
					joinPointIdMap.put(pim, swcPointId);
				}
				int parentId;
				if (i == 0) {
					if (path.getStartJoins() == null) {
						parentId = -1;
					} else {
						// HACK so that equality comparisons work
						final PointInImage startJoinsPoint = path.getStartJoinsPoint().clone();
						startJoinsPoint.onPath = path.getStartJoins();
						parentId = joinPointIdMap.get(startJoinsPoint);
					}
				} else {
					parentId = swcPointId - 1;
				}
				final SWCPoint swcPoint = new SWCPoint(
						swcPointId,
						path.getSWCType(),
						pim.getX(),
						pim.getY(),
						pim.getZ(),
						path.getNodeRadius(i),
						parentId);
				swcPoint.setPath(path);
				// Only use Path color, node colors are ignored
				swcPoint.setColor(pathColor);
				swcPoint.setTags(pathTags);
				if (hasNodeValues) swcPoint.v = path.getNodeValue(i);
				if (hasAnnotations) swcPoint.setAnnotation(path.getNodeAnnotation(i));
				if (hasHemisphereFlags) swcPoint.setHemisphere(path.getNodeHemisphereFlag(i));
				result.add(swcPoint);
				swcPointId++;
			}
			final List<Path> children = pathChildrenMap.get(path);
			if (children != null) {
				children.forEach(pathStack::push);
			}
			pathsAlreadyDone.add(path);
		}

		// Now check that all selectedPaths are in pathsAlreadyDone, otherwise
		// give an error:
		Path disconnectedExample = null;
		int selectedAndNotConnected = 0;
		for (final Path selectedPath : paths) {
			if (!pathsAlreadyDone.contains(selectedPath)) {
				++selectedAndNotConnected;
				if (disconnectedExample == null) disconnectedExample = selectedPath;
			}
		}
		if (selectedAndNotConnected > 0) throw new SWCExportException(
				"You must select all the connected paths\n(" + selectedAndNotConnected +
						" paths (e.g. \"" + disconnectedExample + "\") were not connected.)");

		return result;
	}

	/**
	 * Gets the list of SWCPoints associated with a collection of Paths.
	 *
	 * @param paths the paths from which to retrieve the list
	 * @return the list of SWCPoints, corresponding to all the nodes of
	 *         {@code paths}
	 * @throws SWCExportException if list could not be retrieved
	 */
	public synchronized List<SWCPoint> getSWCForOld(final Collection<Path> paths)
		throws SWCExportException
	{

		/*
		 * Turn the primary paths into a Set. This call also ensures that the
		 * Path.children and Path.somehowJoins relationships are set up correctly:
		 */
		final Set<Path> structuredPathSet = new HashSet<>(Arrays.asList(
			getPathsStructured(paths)));

		/*
		 * Check that there's only one primary path in selectedPaths by taking the
		 * intersection and checking there's exactly one element in it:
		 */
		structuredPathSet.retainAll(new HashSet<>(paths));

		if (structuredPathSet.size() == 0) throw new SWCExportException(
			"The paths you select for SWC export must include a primary path (i.e., one at the top level in the Path Manager tree)");
		if (structuredPathSet.size() > 1) throw new SWCExportException(
			"You can only select one connected set of paths for SWC export");

		/*
		 * So now we definitely only have one primary path. All the connected paths must
		 * also be selected, but we'll check that as we go along:
		 */

		final ArrayList<SWCPoint> result = new ArrayList<>();

		int currentPointID = 1;

		/*
		 * nextPathsToAdd is the queue of Paths to add points from, and pathsAlreadyDone
		 * is the set of Paths that have already had their points added
		 */

		final LinkedList<Path> nextPathsToAdd = new LinkedList<>();
		final Set<Path> pathsAlreadyDone = new HashSet<>();

		final Path firstPath = structuredPathSet.iterator().next();
		if (firstPath.size() == 0) throw new SWCExportException(
			"The primary path contained no points!");
		nextPathsToAdd.add(firstPath);

		while (nextPathsToAdd.size() > 0) {

			final Path currentPath = nextPathsToAdd.removeFirst();

			if (!paths.contains(currentPath)) {
				SNTUtils.error(
						"The path \"" + currentPath +
						"\" is connected to other paths, but wasn't itself included.");
			}

			/*
			 * The paths we're dealing with specify connectivity, but we might be using the
			 * fitted versions - take them for the point positions:
			 */

			Path pathToUse = currentPath;
			if (currentPath.getUseFitted()) {
				pathToUse = currentPath.getFitted();
			}

			Path parent = null;

			for (final Path possibleParent : currentPath.somehowJoins) {
				if (pathsAlreadyDone.contains(possibleParent)) {
					parent = possibleParent;
					break;
				}
			}

			int indexToStartAt = 0;
			int nearestParentSWCPointID = -1;
			PointInImage connectingPoint = null;
			if (parent != null) {
				if (currentPath.getStartJoins() != null && currentPath
					.getStartJoins() == parent) connectingPoint =
						currentPath.startJoinsPoint;
				else if (parent.getStartJoins() != null && parent
					.getStartJoins() == currentPath) connectingPoint =
						parent.startJoinsPoint;
				else throw new SWCExportException(
					"Couldn't find the link between parent \"" + parent +
						"\"\nand child \"" + currentPath + "\" which are somehow joined");

				/* Find the SWC point ID on the parent which is nearest: */

				double distanceSquaredToNearestParentPoint = Double.MAX_VALUE;
				for (final SWCPoint s : result) {
					if (s.getPath() != parent) continue;
					final double distanceSquared = connectingPoint.distanceSquaredTo(s.x,
						s.y, s.z);
					if (distanceSquared < distanceSquaredToNearestParentPoint) {
						nearestParentSWCPointID = s.id;
						distanceSquaredToNearestParentPoint = distanceSquared;
					}
				}

				/*
				 * Now find the index of the point on this path which is nearest
				 */
				indexToStartAt = pathToUse.indexNearestTo(connectingPoint.x,
					connectingPoint.y, connectingPoint.z);
			}

			SWCPoint firstSWCPoint = null;

			final boolean realRadius = pathToUse.hasRadii();
			final boolean hasAnnotations = pathToUse.hasNodeAnnotations();
			final boolean hasHemisphereFlags = pathToUse.hasNodeHemisphereFlags();
			final Color pathColor = pathToUse.getColor();
			final String pathTags = PathManagerUI.extractTagsFromPath(pathToUse);
			final boolean hasNodeValues = pathToUse.hasNodeValues();
			for (int i = indexToStartAt; i < pathToUse.size(); ++i) {

				final boolean firstPoint = firstSWCPoint == null;

				// The first node of a child path is the same as the forked point
				// on its parent, so we'll skip it if this is a child path
				if (firstPoint && !currentPath.isPrimary()) i++;

				final SWCPoint swcPoint = new SWCPoint(currentPointID, // id
						pathToUse.getSWCType(), // type
						pathToUse.precise_x_positions[i], // x
						pathToUse.precise_y_positions[i], // y
						pathToUse.precise_z_positions[i], // z
						(realRadius) ? pathToUse.radii[i] : 0, // radius
						(firstPoint) ? nearestParentSWCPointID : currentPointID - 1 // parent
				);
				swcPoint.setPath(currentPath);
				// Only use Path color, node colors are ignored
				swcPoint.setColor(pathColor);
				swcPoint.setTags(pathTags);
				if (hasNodeValues) {
					swcPoint.v = pathToUse.getNodeValue(i);
				}
				if (hasAnnotations) swcPoint.setAnnotation(pathToUse.getNodeAnnotation(i));
				if (hasHemisphereFlags) swcPoint.setHemisphere(pathToUse.getNodeHemisphereFlag(i));

				result.add(swcPoint);
				++currentPointID;
				if (firstSWCPoint == null) firstSWCPoint = swcPoint;
			}

			boolean firstOfOtherBranch = true;
			for (int i = indexToStartAt - 1; i >= 0; --i) {
				int previousPointID = currentPointID - 1;
				if (firstOfOtherBranch) {
					firstOfOtherBranch = false;
					previousPointID = firstSWCPoint.id;
				}
				double radius = 0;
				if (realRadius) radius = pathToUse.radii[i];
				final SWCPoint swcPoint = new SWCPoint(currentPointID, pathToUse
					.getSWCType(), pathToUse.precise_x_positions[i],
					pathToUse.precise_y_positions[i], pathToUse.precise_z_positions[i],
					radius, previousPointID);
				swcPoint.setPath(currentPath);
				if (hasAnnotations) swcPoint.setAnnotation(pathToUse.getNodeAnnotation(i));
				result.add(swcPoint);
				++currentPointID;
			}

			pathsAlreadyDone.add(currentPath);

			/*
			 * Add all the connected paths that aren't already in pathsAlreadyDone
			 */

			for (final Path connectedPath : currentPath.somehowJoins) {
				if (!pathsAlreadyDone.contains(connectedPath)) {
					nextPathsToAdd.add(connectedPath);
				}
			}
		}

		// Now check that all selectedPaths are in pathsAlreadyDone, otherwise
		// give an error:

		Path disconnectedExample = null;
		int selectedAndNotConnected = 0;
		for (final Path selectedPath : paths) {
			if (!pathsAlreadyDone.contains(selectedPath)) {
				++selectedAndNotConnected;
				if (disconnectedExample == null) disconnectedExample = selectedPath;
			}
		}
		if (selectedAndNotConnected > 0) throw new SWCExportException(
			"You must select all the connected paths\n(" + selectedAndNotConnected +
				" paths (e.g. \"" + disconnectedExample + "\") were not connected.)");


		// FIXME: DUP NODES: These should have never occurred in the first place
//		Iterator<SWCPoint> it = result.iterator();
//		Set<Integer> idsToRemove = new HashSet<>();
//		for (int i = 0; i < result.size(); i++) {
//			final SWCPoint node1 = result.get(i);
//			for (int j = 1; j < result.size(); j++) {
//				if (i == j) continue;
//				final SWCPoint node2 = result.get(j);
//				if (node2.isSameLocation(node1) && node2.parent == node1.parent) {
//					idsToRemove.add(node2.id);
//					System.out.println("Removing #### ");
//					System.out.println(node2);
//					System.out.println(node1);
//				}
//			}
//		}
//
//		while (it.hasNext()) {
//			final SWCPoint node = it.next();
//			if (idsToRemove.contains(node.id)) {
//				it.remove();
//			}
//		}
//		for (SWCPoint node: result) {
//			if (idsToRemove.contains(node.parent))
//				node.parent = -1;
//		}

		return result;
	}

	public synchronized void resetListeners(final Path justAdded) {
		resetListeners(justAdded, false);
	}

	protected synchronized void resetListeners(final Path justAdded,
		final boolean expandAll)
	{
		if (enableUIupdates) {
			for (final PathAndFillListener listener : listeners)
				listener.setPathList(allPaths, justAdded, expandAll);
			for (final PathAndFillListener pafl : listeners)
				pafl.setFillList(allFills);
		}
	}

	/**
	 * Adds a {@link Tree}.
	 *
	 * @param tree the collection of paths to be added
	 */
	public void addTree(final Tree tree) {
		tree.list().forEach(p -> addPath(p, true, true));
	}

	/**
	 * Adds a new path.
	 *
	 * @param p the Path to be added
	 */
	public void addPath(final Path p) {
		addPath(p, false, false);
	}

	public void addPath(final Path p, final boolean retainTags) {
		final String tags = PathManagerUI.extractTagsFromPath(p);
		addPath(p, true, true);
		if (retainTags) {
			p.setName((tags.isEmpty()) ? p.getName() : p.getName() + "{" + tags + "}");
		}
	}

	public synchronized void addPath(final Path p,
			final boolean forceNewName, final boolean forceNewId)
		{
		addPath(p, forceNewName, forceNewId, true);
	}

	protected synchronized void addPath(final Path p,
		final boolean forceNewName, final boolean forceNewId, final boolean assumeMaxUsedTreeID)
	{
		final boolean isPrimary = p.isPrimary();
		if (isPrimary) ++maxUsedTreeID;
		if (!forceNewId && getPathFromID(p.getID()) != null) throw new IllegalArgumentException(
				"Attempted to add a path with an ID that was already added");

		// By default the latest tree ID is assigned to the added Path. That is a reasonable
		// assumption when adding paths in bulk, but in an interactive session, we need to
		// ensure the path is being assigned the correct tree ID.
		int treeID = maxUsedTreeID;
		if (!assumeMaxUsedTreeID && !isPrimary) {
			if (p.getStartJoins() != null)
				treeID = p.getStartJoins().getTreeID();
		}
		p.setIDs((forceNewId || p.getID() < 0) ? ++maxUsedPathID : p.getID(), treeID);
		if (maxUsedPathID < p.getID()) maxUsedPathID = p.getID();

		if (p.getName() == null || forceNewName) {
			final String suggestedName = getDefaultName(p);
			p.setName(suggestedName);
		}
		addPathInternal(p);
	}

	public synchronized void addPath(final Path p, final int id, final int treeID) {
		addPathInternal(p);
		p.setIDs(id, treeID);
		if (maxUsedPathID < id)
			maxUsedPathID = id;
		if (maxUsedTreeID < treeID)
			maxUsedTreeID = treeID;
	}

	@SuppressWarnings("deprecation")
	private void addPathInternal(final Path p) {
		// Check if there's already a path with this name.
		// If so, try adding numbered suffixes:
		final String originalName = p.getName();
		String candidateName = originalName;
		int numberSuffix = 2;
		while (getPathFromName(candidateName, false) != null) {
			candidateName = originalName + " (" + numberSuffix + ")";
			++numberSuffix;
		}
		p.setName(candidateName);
		/*
		 * Generate a new content3D, since it matters that the path is added with the
		 * right name via update3DViewerContents:
		 */
		if (plugin != null && plugin.use3DViewer) {
			p.removeFrom3DViewer(plugin.univ);
			p.addTo3DViewer(plugin.univ, plugin.deselectedColor3f, plugin.colorImage);
		}
		allPaths.add(p);
		pathIdMap.put(p.getID(), p);
		pathNameMap.put(p.getName(), p);
		pathNameLowercaseMap.put(p.getName().toLowerCase(Locale.ROOT), p);
		resetListeners(p);
	}

	/*
	 * Find the default name for a new path, making sure it doesn't collide with any
	 * of the existing names:
	 */
	protected String getDefaultName(final Path p) {
		if (p.getID() < 0) throw new RuntimeException(
			"A path's ID should never be negative");
		return "Path (" + p.getID() + ")";
	}

	/**
	 * Deletes a Path by index
	 *
	 * @param index the index (zero-based) of the Path to be deleted
	 * @return true, if index is valid and Path was successfully deleted
	 */
	public synchronized boolean deletePath(final int index) {
		return deletePath(index, enableUIupdates);
	}

	/**
	 * Deletes a path.
	 *
	 * @param p the path to be deleted
	 * @return true, if path was found and successfully deleted
	 */
	public synchronized boolean deletePath(final Path p) {
		final int i = getPathIndex(p);
		if (i < 0) return false;
		return deletePath(i);
	}

	/**
	 * Deletes a collection of paths. If present, the UI is only updated once
	 * operation completes.
	 *
	 * @param paths the collection to be deleted
	 * @return true, if all Paths in the collection were found and successfully
	 *         deleted
	 */
	public synchronized boolean deletePaths(final Collection<Path> paths) {
		final ArrayList<Integer> indices = new ArrayList<>(paths.size());
		for (final Path path : paths) {
			final int index = getPathIndex(path);
			if (index != -1) indices.add(index);
		}
		deletePaths(indices.stream().mapToInt(i->i).toArray());
		return indices.size() == paths.size();
	}

	/**
	 * Gets the index of a Path.
	 *
	 * @param p the Path for which the index should be retrieved
	 * @return the path index, or -1 if p was not found
	 */
	public synchronized int getPathIndex(final Path p) {
		int i = 0;
		for (i = 0; i < allPaths.size(); ++i) {
			if (p == allPaths.get(i)) return i;
		}
		return -1;
	}

	private synchronized boolean deletePath(final int index,
		final boolean updateInterface)
	{

		final Path originalPathToDelete = allPaths.get(index);

		Path unfittedPathToDelete = null;
		Path fittedPathToDelete = null;

		if (originalPathToDelete.fittedVersionOf == null) {
			unfittedPathToDelete = originalPathToDelete;
			fittedPathToDelete = originalPathToDelete.getFitted();
		}
		else {
			unfittedPathToDelete = originalPathToDelete.fittedVersionOf;
			fittedPathToDelete = originalPathToDelete;
		}

		boolean removed = allPaths.remove(unfittedPathToDelete);
		pathIdMap.remove(unfittedPathToDelete.getID());
		pathNameMap.remove(unfittedPathToDelete.getName());
		pathNameLowercaseMap.remove(unfittedPathToDelete.getName().toLowerCase(Locale.ROOT));
		if (fittedPathToDelete != null) {
			removed = removed || allPaths.remove(fittedPathToDelete);
			pathIdMap.remove(fittedPathToDelete.getID());
			pathNameMap.remove(fittedPathToDelete.getName());
			pathNameLowercaseMap.remove(fittedPathToDelete.getName().toLowerCase(Locale.ROOT));
		}
		if (removed && plugin != null) plugin.unsavedPaths = true;

		// We don't just delete; have to fix up the references
		// in other paths (for start and end joins):
		for (final Path p : unfittedPathToDelete.somehowJoins) {
			if (p.getStartJoins() == unfittedPathToDelete) {
				p.startJoins = null;
				p.startJoinsPoint = null;
			}
		}

		selectedPathsSet.remove(fittedPathToDelete);
		selectedPathsSet.remove(unfittedPathToDelete);

		if (plugin != null && plugin.use3DViewer) {
			if (fittedPathToDelete != null && fittedPathToDelete.content3D != null)
				fittedPathToDelete.removeFrom3DViewer(plugin.univ);
			if (unfittedPathToDelete.content3D != null) unfittedPathToDelete
				.removeFrom3DViewer(plugin.univ);
		}

		if (updateInterface) resetListeners(null);
		return removed;
	}

	/**
	 * Delete paths by position.
	 *
	 * @param indices the indices to be deleted
	 */
	public void deletePaths(final int[] indices) {
		Arrays.sort(indices);
		enableUIupdates = false;
		for (int i = indices.length - 1; i >= 0; --i) {
			deletePath(indices[i], false);
		}
		enableUIupdates = true;
		resetListeners(null);
	}

	protected void addFill(final Fill fill) {
		allFills.add(fill);
		for (final PathAndFillListener pafl : listeners)
			pafl.setFillList(allFills);
	}

	protected void deleteFills(final int[] indices) {
		Arrays.sort(indices);
		for (int i = indices.length - 1; i >= 0; --i) {
			deleteFill(indices[i], false);
		}
		for (final PathAndFillListener pafl : listeners)
			pafl.setFillList(allFills);
	}

	private synchronized void deleteFill(final int index, final boolean updateInterface) {
		allFills.remove(index);
		if (updateInterface)
			for (final PathAndFillListener pafl : listeners)
				pafl.setFillList(allFills);
	}

	public Map<Fill, FillerThread> getLoadedFills() {
		return loadedFills;
	}

	protected void reloadFills(int[] selectedIndices) {
		for (int ind : selectedIndices) {
			Fill fill = allFills.get(ind);
			FillerThread filler = FillerThread.fromFill(
					plugin.getLoadedData(),
					plugin.getImagePlus().getCalibration(),
					plugin.getStats(),
					fill);
			loadedFills.put(fill, filler);
			plugin.addFillerThread(filler);
		}
	}

	// FIXME: should probably use XMLStreamWriter instead of this ad-hoc
	// approach:
	synchronized protected void writeXML(final String fileName,
		final boolean compress) throws IOException
	{

		PrintWriter pw = null;

		try {
			if (compress) pw = new PrintWriter(new OutputStreamWriter(
				new GZIPOutputStream(new FileOutputStream(fileName)), StandardCharsets.UTF_8));
			else pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
				fileName), StandardCharsets.UTF_8));

			pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
			pw.println("<!DOCTYPE tracings [");
			pw.println(
				"  <!ELEMENT tracings       (samplespacing,imagesize,path*,fill*)>");
			pw.println("  <!ELEMENT imagesize      EMPTY>");
			pw.println("  <!ELEMENT samplespacing  EMPTY>");
			pw.println("  <!ELEMENT path           (point+)>");
			pw.println("  <!ELEMENT point          EMPTY>");
			pw.println("  <!ELEMENT fill           (node*)>");
			pw.println("  <!ELEMENT node           EMPTY>");
			pw.println(
				"  <!ATTLIST samplespacing  x                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST samplespacing  y                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST samplespacing  z                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST samplespacing  units             CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST imagesize      width             CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST imagesize      height            CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST imagesize      depth             CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST path           id                CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST path           primary           CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           name              CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           startson          CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           startsindex       CDATA           #IMPLIED>"); // deprecated
			pw.println(
				"  <!ATTLIST path           startsx           CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           startsy           CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           startsz           CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           endson            CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           endsindex         CDATA           #IMPLIED>"); // deprecated
			pw.println(
				"  <!ATTLIST path           endsx             CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           endsy             CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           endsz             CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           reallength        CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           usefitted         (true|false)    #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           fitted            CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           fittedversionof   CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           swctype           CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           color             CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST path           channel           CDATA           #IMPLIED>");
			pw.println(
					"  <!ATTLIST path         frame             CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          x                 CDATA           #REQUIRED>"); // deprecated
			pw.println(
				"  <!ATTLIST point          y                 CDATA           #REQUIRED>"); // deprecated
			pw.println(
				"  <!ATTLIST point          z                 CDATA           #REQUIRED>"); // deprecated
			pw.println(
				"  <!ATTLIST point          xd                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          yd                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          zd                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          tx                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          ty                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          tz                CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST point          r                 CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST fill           id                CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST fill           frompaths         CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST fill           metric            CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST fill           threshold         CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST fill           volume            CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST node           id                CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST node           x                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST node           y                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST node           z                 CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST node           previousid        CDATA           #IMPLIED>");
			pw.println(
				"  <!ATTLIST node           distance          CDATA           #REQUIRED>");
			pw.println(
				"  <!ATTLIST node           status            (open|closed)   #REQUIRED>");
			pw.println("]>");
			pw.println("");

			pw.println("<tracings>");

			pw.println("  <samplespacing x=\"" + x_spacing + "\" " + "y=\"" +
				y_spacing + "\" " + "z=\"" + z_spacing + "\" " + "units=\"" +
				spacing_units + "\"/>");

			if (plugin != null) pw.println("  <imagesize width=\"" + plugin.width +
				"\" height=\"" + plugin.height + "\" depth=\"" + plugin.depth + "\"/>");

			for (final Path p : allPaths) {
				writePathToXML(pw, p);
				if (p.getFitted() != null)
					writePathToXML(pw, p.getFitted());
			}
			// Now output the fills:
			int fillIndex = 0;
			for (final Fill f : allFills) {
				f.writeXML(pw, fillIndex);
				++fillIndex;
			}
			pw.println("</tracings>");
		}
		finally {
			if (pw != null) pw.close();
		}
	}

	private void writePathToXML(final PrintWriter pw, final Path p) {
		// This probably should be a String returning
		// method of Path.
		pw.print("  <path id=\"" + p.getID() + "\"");
				pw.print(" swctype=\"" + p.getSWCType() + "\"");
				pw.print(" color=\"" + SNTUtils.getColorString(p.getColor()) + "\"");
				pw.print(" channel=\"" + p.getChannel() + "\"");
				pw.print(" frame=\"" + p.getFrame() + "\"");
				pw.print(" spines=\"" + p.getSpineOrVaricosityCount() + "\"");

				String startsString = "";
				String endsString = "";
				if (p.startJoins != null) {
					final int startPathID = p.startJoins.getID();
					// Find the nearest index for backward compatibility:
					int nearestIndexOnStartPath = -1;
					if (p.startJoins.size() > 0) {
						nearestIndexOnStartPath = p.startJoins.indexNearestTo(
							p.startJoinsPoint.x, p.startJoinsPoint.y, p.startJoinsPoint.z);
					}
					startsString = " startson=\"" + startPathID + "\"" + " startx=\"" +
						p.startJoinsPoint.x + "\"" + " starty=\"" + p.startJoinsPoint.y +
						"\"" + " startz=\"" + p.startJoinsPoint.z + "\"";
					if (nearestIndexOnStartPath >= 0) startsString += " startsindex=\"" +
						nearestIndexOnStartPath + "\"";
				}
				if (p.isPrimary()) pw.print(" primary=\"true\"");
				pw.print(" usefitted=\"" + p.getUseFitted() + "\"");
				if (p.getFitted() != null) {
					pw.print(" fitted=\"" + p.getFitted().getID() + "\"");
				}
				if (p.fittedVersionOf != null) {
					pw.print(" fittedversionof=\"" + p.fittedVersionOf.getID() + "\"");
		}
		pw.print(startsString);
		pw.print(endsString);
		pw.print(" name=\"" + XMLFunctions.escapeForXMLAttributeValue(p.getName()) + "\"");
		pw.print(" reallength=\"" + p.getLength() + "\"");
		pw.println(">");

				for (int i = 0; i < p.size(); ++i) {
					final int px = p.getXUnscaled(i);
					final int py = p.getYUnscaled(i);
					final int pz = p.getZUnscaled(i);
					final double pxd = p.precise_x_positions[i];
					final double pyd = p.precise_y_positions[i];
					final double pzd = p.precise_z_positions[i];
					String attributes = "x=\"" + px + "\" " + "y=\"" + py + "\" z=\"" +
						pz + "\" " + "xd=\"" + pxd + "\" yd=\"" + pyd + "\" zd=\"" + pzd +
						"\"";
					if (p.hasRadii()) {
						attributes += " tx=\"" + p.tangents_x[i] + "\"";
						attributes += " ty=\"" + p.tangents_y[i] + "\"";
						attributes += " tz=\"" + p.tangents_z[i] + "\"";
						attributes += " r=\"" + p.radii[i] + "\"";
					}
					pw.println("    <point " + attributes + "/>");
		}
		pw.println("  </path>");
	}

	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
	 */
	@Override
	public void startElement(final String uri, final String localName,
		final String qName, final Attributes attributes)
		throws TracesFileFormatException
	{

		if (boundingBox == null) boundingBox = new BoundingBox();
		switch (qName) {
			case "tracings":

				startJoins = new HashMap<>();
				startJoinsIndices = new HashMap<>();
				startJoinsPoints = new HashMap<>();
				endJoins = new HashMap<>();
				endJoinsIndices = new HashMap<>();
				endJoinsPoints = new HashMap<>();
				useFittedFields = new HashMap<>();
				fittedFields = new HashMap<>();
				fittedVersionOfFields = new HashMap<>();

				sourcePathIDForFills = new ArrayList<>();
				foundIDs = new HashSet<>();

				last_fill_id = -1;

				/*
				 * We need to remove the old paths and fills before loading the ones:
				 */
				SNTUtils.log("Clearing old paths and fills...");
				clear();

				break;
			case "imagesize":

				try {
					final int parsed_width = Integer.parseInt(attributes.getValue("width"));
					final int parsed_height = Integer.parseInt(attributes.getValue(
							"height"));
					final int parsed_depth = Integer.parseInt(attributes.getValue("depth"));
					boundingBox.setOrigin(new PointInImage(0, 0, 0));
					boundingBox.setDimensions(parsed_width, parsed_height, parsed_depth);
					if (plugin != null && (parsed_width != plugin.width ||
							parsed_height != plugin.height || parsed_depth != plugin.depth)) {
						SNTUtils.warn(
								"The image size in the traces file didn't match - it's probably for another image");
						checkForAppropriateImageDimensions();
					}
				} catch (final NumberFormatException e) {
					throw new TracesFileFormatException(
							"There was an invalid attribute to <imagesize/>: " + e);
				}

				break;
			case "samplespacing":

				try {
					final boolean spacingIsUnset = !boundingBox.isScaled();
					final String xString = attributes.getValue("x");
					final String yString = attributes.getValue("y");
					final String zString = attributes.getValue("z");
					final String spacingUnits = attributes.getValue("units");
					boundingBox.setUnit(spacingUnits);
					boundingBox.xSpacing = Double.parseDouble(xString);
					boundingBox.ySpacing = Double.parseDouble(yString);
					boundingBox.zSpacing = Double.parseDouble(zString);
					if (spacingIsUnset) {
						x_spacing = boundingBox.xSpacing;
						y_spacing = boundingBox.ySpacing;
						z_spacing = boundingBox.zSpacing;
						spacing_units = spacingUnits;
					}
				} catch (final NumberFormatException e) {
					throw new TracesFileFormatException(
							"There was an invalid attribute to <samplespacing/>: " + e);
				}

				break;
			case "path":

				final String idString = attributes.getValue("id");

				final String swcTypeString = attributes.getValue("swctype");
				final String colorString = attributes.getValue("color");
				final String channelString = attributes.getValue("channel");
				final String frameString = attributes.getValue("frame");
				final String spineString = attributes.getValue("spines");
				final String useFittedString = attributes.getValue("usefitted");
				final String fittedIDString = attributes.getValue("fitted");
				final String fittedVersionOfIDString = attributes.getValue(
						"fittedversionof");
				final String startsonString = attributes.getValue("startson");
				final String startsindexString = attributes.getValue("startsindex");
				final String startsxString = attributes.getValue("startsx");
				final String startsyString = attributes.getValue("startsy");
				final String startszString = attributes.getValue("startsz");
				final String endsonString = attributes.getValue("endson");
				final String endsindexString = attributes.getValue("endsindex");
				final String endsxString = attributes.getValue("endsx");
				final String endsyString = attributes.getValue("endsy");
				final String endszString = attributes.getValue("endsz");
				final String nameString = attributes.getValue("name");
				final String primaryString = attributes.getValue("primary");

				if (startsxString == null && startsyString == null &&
						startszString == null) {
				} else if (startsxString != null && startsyString != null &&
						startszString != null) {
				} else {
					throw new TracesFileFormatException(
							"If one of starts[xyz] is specified, all of them must be.");
				}

				if (endsxString == null && endsyString == null && endszString == null) {
				} else if (endsxString != null && endsyString != null &&
						endszString != null) {
				} else {
					throw new TracesFileFormatException(
							"If one of ends[xyz] is specified, all of them must be.");
				}

				final boolean accurateStartProvided = startsxString != null;
				final boolean accurateEndProvided = endsxString != null;

				if (startsonString != null && (startsindexString == null &&
						!accurateStartProvided)) {
					throw new TracesFileFormatException(
							"If startson is specified for a path, then startsindex or starts[xyz] must also be specified.");
				}

				if (endsonString != null && (endsindexString == null &&
						!accurateEndProvided)) {
					throw new TracesFileFormatException(
							"If endson is specified for a path, then endsindex or ends[xyz] must also be specified.");
				}

				current_path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

				int startson, endson, endsindex;
				Integer startsOnInteger = null;
				Integer startsIndexInteger = null;
				PointInImage startJoinPoint = null;
				Integer endsOnInteger = null;
				Integer endsIndexInteger = null;
				PointInImage endJoinPoint = null;

				Integer fittedIDInteger = null;
				Integer fittedVersionOfIDInteger = null;
				int id = -1;

				try {

					id = Integer.parseInt(idString);
					if (foundIDs.contains(id)) {
						throw new TracesFileFormatException(
								"There is more than one path with ID " + id);
					}

					if (swcTypeString != null) {
						final int swcType = Integer.parseInt(swcTypeString);
						current_path.setSWCType(swcType, false);
					}

					if (colorString != null) {
						current_path.setColor(SNTUtils.getColor(colorString));
					}
					if (channelString != null && frameString != null) {
						current_path.setCTposition(Integer.parseInt(channelString), Integer
								.parseInt(frameString));
					}
					if (spineString != null) {
						current_path.setSpineOrVaricosityCount(Integer.parseInt(spineString));
					}

					if (startsonString == null) {
						startson = -1;
					} else {
						startson = Integer.parseInt(startsonString);
						startsOnInteger = startson;

						if (startsxString == null) {
							// The index (older file format) was supplied:
							startsIndexInteger = Integer.valueOf(startsindexString);
						} else {
							startJoinPoint = new PointInImage(Double.parseDouble(startsxString),
									Double.parseDouble(startsyString), Double.parseDouble(
									startszString));
						}
					}

					if (endsonString == null) endson = endsindex = -1;
					else {
						endson = Integer.parseInt(endsonString);
						endsOnInteger = endson;

						if (endsxString != null) {
							endJoinPoint = new PointInImage(Double.parseDouble(endsxString),
									Double.parseDouble(endsyString), Double.parseDouble(endszString));
						} else {
							// The index (older file format) was supplied:
							endsindex = Integer.parseInt(endsindexString);
							endsIndexInteger = endsindex;
						}
					}

					if (fittedVersionOfIDString != null) fittedVersionOfIDInteger =
							Integer.parseInt(fittedVersionOfIDString);
					if (fittedIDString != null) fittedIDInteger = Integer
							.parseInt(fittedIDString);

				} catch (final NumberFormatException e) {
					e.printStackTrace();
					throw new TracesFileFormatException(
							"There was an invalid attribute in <path/>: " + e);
				}

				// Assign ID
				if (startsOnInteger == null) {
					current_path.setIsPrimary(true);
					++maxUsedTreeID;
				} else if (primaryString != null && primaryString.equals("true")) {
					current_path.setIsPrimary(true);
				}
				current_path.setIDs(id, maxUsedTreeID);
				if (id > maxUsedPathID) maxUsedPathID = id;
				current_path.setName(nameString); // default name if null

				if (startsOnInteger != null) startJoins.put(id, startsOnInteger);
				if (endsOnInteger != null) endJoins.put(id, endsOnInteger);

				if (startJoinPoint != null) startJoinsPoints.put(id, startJoinPoint);
				if (endJoinPoint != null) endJoinsPoints.put(id, endJoinPoint);

				if (startsIndexInteger != null) {
					startJoinsIndices.put(id, startsIndexInteger);
				}
				if (endsIndexInteger != null) endJoinsIndices.put(id, endsIndexInteger);

				if (useFittedString == null) useFittedFields.put(id, false);
				else {
					if (useFittedString.equals("true")) useFittedFields.put(id, true);
					else if (useFittedString.equals("false")) useFittedFields.put(id,
							false);
					else {
						throw new TracesFileFormatException(
								"Unknown value for 'fitted' attribute: '" + useFittedString + "'");
					}
				}

				if (fittedIDInteger != null) fittedFields.put(id, fittedIDInteger);
				if (fittedVersionOfIDInteger != null) fittedVersionOfFields.put(id,
						fittedVersionOfIDInteger);

				break;
			case "point":

				try {

					double parsed_xd, parsed_yd, parsed_zd;

					final String xdString = attributes.getValue("xd");
					final String ydString = attributes.getValue("yd");
					final String zdString = attributes.getValue("zd");

					final String xString = attributes.getValue("x");
					final String yString = attributes.getValue("y");
					final String zString = attributes.getValue("z");

					if (xdString != null && ydString != null && zdString != null) {
						parsed_xd = Double.parseDouble(xdString);
						parsed_yd = Double.parseDouble(ydString);
						parsed_zd = Double.parseDouble(zdString);
					} else if (xdString != null || ydString != null || zdString != null) {
						throw new TracesFileFormatException(
								"If one of the attributes xd, yd or zd to the point element is specified, they all must be.");
					} else if (xString != null && yString != null && zString != null) {
						parsed_xd = boundingBox.xSpacing * Integer.parseInt(xString);
						parsed_yd = boundingBox.ySpacing * Integer.parseInt(yString);
						parsed_zd = boundingBox.zSpacing * Integer.parseInt(zString);
					} else if (xString != null || yString != null || zString != null) {
						throw new TracesFileFormatException(
								"If one of the attributes x, y or z to the point element is specified, they all must be.");
					} else {
						throw new TracesFileFormatException(
								"Each point element must have at least the attributes (x, y and z) or (xd, yd, zd)");
					}

					current_path.addPointDouble(parsed_xd, parsed_yd, parsed_zd);

					final int lastIndex = current_path.size() - 1;
					final String radiusString = attributes.getValue("r");
					final String tXString = attributes.getValue("tx");
					final String tYString = attributes.getValue("ty");
					final String tZString = attributes.getValue("tz");

					if (radiusString != null && tXString != null && tYString != null &&
							tZString != null) {
						if (lastIndex == 0)
							// Then we've just started, create the arrays in Path:
							current_path.createCircles();
						else if (!current_path.hasRadii())
							throw new TracesFileFormatException("The point at index " +
									lastIndex + " had a fitted circle, but none previously did");
						current_path.tangents_x[lastIndex] = Double.parseDouble(tXString);
						current_path.tangents_y[lastIndex] = Double.parseDouble(tYString);
						current_path.tangents_z[lastIndex] = Double.parseDouble(tZString);
						current_path.radii[lastIndex] = Double.parseDouble(radiusString);
					} else if (radiusString != null || tXString != null || tYString != null ||
							tZString != null) throw new TracesFileFormatException(
							"If one of the r, tx, ty or tz attributes to the point element is specified, they all must be");
					else {
						// All circle attributes are null:
						if (current_path.hasRadii()) throw new TracesFileFormatException(
								"The point at index " + lastIndex +
										" had no fitted circle, but all previously did");
					}

				} catch (final NumberFormatException e) {
					throw new TracesFileFormatException(
							"There was an invalid attribute to <imagesize/>");
				}

				break;
			case "fill":

				try {

					String[] sourcePaths = {};
					final String fromPathsString = attributes.getValue("frompaths");
					if (fromPathsString != null) sourcePaths = fromPathsString.split(", *");

					current_fill = new Fill();

					// TODO: serialize the cost somehow?
					final String metric = attributes.getValue("metric");
					if (Objects.equals(metric, SNT.CostType.RECIPROCAL.toString())) {
						current_fill.setMetric(SNT.CostType.RECIPROCAL);
					} else if (Objects.equals(metric, SNT.CostType.DIFFERENCE.toString())) {
						current_fill.setMetric(SNT.CostType.DIFFERENCE);
					} else if (Objects.equals(metric, SNT.CostType.DIFFERENCE_SQUARED.toString())){
						current_fill.setMetric(SNT.CostType.DIFFERENCE_SQUARED);
					} else if (Objects.equals(metric, SNT.CostType.PROBABILITY.toString())) {
						current_fill.setMetric(SNT.CostType.PROBABILITY);
					} else {
						current_fill.setMetric(SNT.CostType.RECIPROCAL);
					}

					last_fill_node_id = -1;

					final String fill_id_string = attributes.getValue("id");

					int fill_id = Integer.parseInt(fill_id_string);

					if (fill_id < 0) {
						throw new TracesFileFormatException(
								"Can't have a negative id in <fill>");
					}

					if (fill_id != (last_fill_id + 1)) {
						SNTUtils.log("Out of order id in <fill> (" + fill_id +
								" when we were expecting " + (last_fill_id + 1) + ")");
						fill_id = last_fill_id + 1;
					}

					final int[] sourcePathIndices = new int[sourcePaths.length];

					for (int i = 0; i < sourcePaths.length; ++i)
						sourcePathIndices[i] = Integer.parseInt(sourcePaths[i]);

					sourcePathIDForFills.add(sourcePathIndices);

					last_fill_id = fill_id;

					final String thresholdString = attributes.getValue("threshold");
					final double fillThreshold = Double.parseDouble(thresholdString);

					current_fill.setThreshold(fillThreshold);

				} catch (final NumberFormatException e) {
					throw new TracesFileFormatException(
							"There was an invalid attribute to <fill>");
				}

				break;
			case "node":

				try {

					final String xString = attributes.getValue("x");
					final String yString = attributes.getValue("y");
					final String zString = attributes.getValue("z");
					final String nIdString = attributes.getValue("id");
					final String distanceString = attributes.getValue("distance");
					final String previousString = attributes.getValue("previousid");

					final int parsed_x = Integer.parseInt(xString);
					final int parsed_y = Integer.parseInt(yString);
					final int parsed_z = Integer.parseInt(zString);
					final int parsed_id = Integer.parseInt(nIdString);
					final double parsed_distance = Double.parseDouble(distanceString);
					int parsed_previous;
					if (previousString == null) parsed_previous = -1;
					else parsed_previous = Integer.parseInt(previousString);

					if (parsed_id != (last_fill_node_id + 1)) {
						throw new TracesFileFormatException(
								"Fill node IDs weren't consecutive integers");
					}

					final String openString = attributes.getValue("status");

					current_fill.add(parsed_x, parsed_y, parsed_z, parsed_distance,
							parsed_previous, openString.equals("open"));

					last_fill_node_id = parsed_id;

				} catch (final NumberFormatException e) {
					throw new TracesFileFormatException(
							"There was an invalid attribute to <node/>: " + e);
				}

				break;
			default:
				throw new TracesFileFormatException("Unknown element: '" + qName + "'");
		}

	}

	@SuppressWarnings("deprecation")
	private void addImportedPathsTo3DViewer() {
		if (plugin != null && plugin.use3DViewer) {
			for (final Path p : allPaths) {
				if (p.isFittedVersionOfAnotherPath() || p.size() < 2)
					continue;
				Path pathToAdd;
				if (p.getUseFitted()) pathToAdd = p.getFitted();
				else pathToAdd = p;
				pathToAdd.addTo3DViewer(plugin.univ, plugin.deselectedColor,
					plugin.colorImage);
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void endElement(final String uri, final String localName,
		final String qName) throws TracesFileFormatException
	{

		switch (qName) {
			case "path":

				allPaths.add(current_path);
				pathIdMap.put(current_path.getID(), current_path);
				pathNameMap.put(current_path.getName(), current_path);
				pathNameLowercaseMap.put(current_path.getName().toLowerCase(Locale.ROOT), current_path);

				break;
			case "fill":

				allFills.add(current_fill);

				break;
			case "tracings":

				// Then we've finished...

				for (final Path p : new ArrayList<>(allPaths)) {
					final Integer startID = startJoins.get(p.getID());
					final Integer startIndexInteger = startJoinsIndices.get(p.getID());
					PointInImage startJoinPoint = startJoinsPoints.get(p.getID());
					final Integer endID = endJoins.get(p.getID());
					final Integer endIndexInteger = endJoinsIndices.get(p.getID());
					PointInImage endJoinPoint = endJoinsPoints.get(p.getID());
					final Integer fittedID = fittedFields.get(p.getID());
					final Integer fittedVersionOfID = fittedVersionOfFields.get(p.getID());
					final Boolean useFitted = useFittedFields.get(p.getID());

					if (startID != null) {
						final Path startPath = getPathFromID(startID);
						if (startJoinPoint == null) {
							// Then we have to get it from startIndexInteger:
							startJoinPoint = startPath.getNodeWithoutChecks(startIndexInteger);
						}
						p.setStartJoin(startPath, startJoinPoint);
					}
					if (endID != null) {
						Path endPath = getPathFromID(endID);
						if (endJoinPoint == null) {
							// Then we have to get it from endIndexInteger:
							endJoinPoint = endPath.getNodeWithoutChecks(endIndexInteger);
						}
						// We no longer support end point joining, so we'll reverse everything
						// and apply a 'start join';
						SNTUtils.log("End joining no longer supported: Establishing a star-join' on reversed path");
						Path pReversed = p.reversed();
						// replace the path in all data structures...
						allPaths.remove(p);
						pathIdMap.remove(p.getID(), p);
						pathNameMap.remove(p.getName(), p);
						pathNameLowercaseMap.remove(p.getName().toLowerCase(Locale.ROOT), p);

						allPaths.add(pReversed);
						pathIdMap.put(pReversed.getID(), pReversed);
						pathNameMap.put(pReversed.getName(), pReversed);
						pathNameLowercaseMap.put(pReversed.getName().toLowerCase(Locale.ROOT), pReversed);

						pReversed.setStartJoin(endPath, endJoinPoint);

						for (final Path join : new ArrayList<>(p.somehowJoins)) {
							if (join.getStartJoins() == p) {
								final PointInImage joinPoint = join.getStartJoinsPoint();
								join.unsetStartJoin();
								join.setStartJoin(pReversed, joinPoint);
							}
						}

					}
					if (fittedID != null) {
						final Path fitted = getPathFromID(fittedID);
						p.fitted = fitted;
						p.setUseFitted(useFitted);
					}
					if (fittedVersionOfID != null) {
						final Path fittedVersionOf = getPathFromID(fittedVersionOfID);
						p.fittedVersionOf = fittedVersionOf;
					}
				}

				// Do some checks that the fitted and fittedVersionOf fields match
				// up:
				for (final Path p : allPaths) {
					if (p.getFitted() != null) {
						if (p.getFitted().fittedVersionOf == null)
							throw new TracesFileFormatException(
									"Malformed traces file: p.fitted.fittedVersionOf was null");
						else if (p != p.getFitted().fittedVersionOf)
							throw new TracesFileFormatException(
									"Malformed traces file: p didn't match p.fitted.fittedVersionOf");
					} else if (p.fittedVersionOf != null) {
						if (p.fittedVersionOf.getFitted() == null)
							throw new TracesFileFormatException(
									"Malformed traces file: p.fittedVersionOf.fitted was null");
						else if (p != p.fittedVersionOf.getFitted())
							throw new TracesFileFormatException(
									"Malformed traces file: p didn't match p.fittedVersionOf.fitted");
					}
					else if (p.getUseFitted()) {
						throw new TracesFileFormatException(
								"Malformed traces file: p.useFitted was true but p.fitted was null");
					}
				}

				// allPaths have been used to store all imported paths, including fitted ones.
				// Fitted paths are accessed through the Path#fitted field of each path, not
				// through this manager, so now we'll delete those. //FIXME: this is not efficient
				for (final Iterator<Path> itr = allPaths.iterator(); itr.hasNext();) {
					final Path p = itr.next();
					if (p.isFittedVersionOfAnotherPath()) {
						itr.remove();
						pathIdMap.remove(p.getID(), p);
						pathNameMap.remove(p.getName(), p);
						pathNameLowercaseMap.remove(p.getName().toLowerCase(Locale.ROOT), p);
					}
				}

				// Now turn the source paths into real paths...
				for (int i = 0; i < allFills.size(); ++i) {
					final Fill f = allFills.get(i);
					final Set<Path> realSourcePaths = new HashSet<>();
					final int[] sourcePathIDs = sourcePathIDForFills.get(i);
					for (int sourcePathID : sourcePathIDs) {
						final Path sourcePath = getPathFromID(sourcePathID);
						if (sourcePath != null) realSourcePaths.add(sourcePath);
					}
					f.setSourcePaths(realSourcePaths);
				}

				// FIXME: after import there still can be different Cell IDs within the same Tree,
				//  so do a final rebuild to ensure everything makes sense. This is a workaround until
				//  we can figure out why it is happening in the first place.
				//rebuildRelationships(); // will remove imported Fits

				addImportedPathsTo3DViewer(); // Now we're safe to add them all to the 3D Viewer
				if (plugin != null) plugin.updateTracingViewers(true);
				resetListeners(null, true);
				break;
		}

	}

	/**
	 * Creates a PathAndFillManager instance from imported data
	 *
	 * @param filePath the absolute path of the file to be imported as per
	 *          {@link #load(String, int...)}
	 * @return the PathAndFillManager instance, or null if file could not be
	 *         imported
	 */
	public static PathAndFillManager createFromFile(final String filePath, final int... swcTypes) {
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		if (pafm.load(filePath, swcTypes)) return pafm;
		else return null;
	}

	/**
	 * Creates a PathAndFillManager instance from a collection of reconstruction
	 * nodes.
	 *
	 * @param nodes the collection of reconstruction nodes. Nodes will be sorted by
	 *              id and any duplicate entries pruned.
	 * @return the PathAndFillManager instance, or null if file could not be
	 *         imported
	 */
	public static PathAndFillManager createFromNodes(final Collection<SWCPoint> nodes) {
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		final TreeSet<SWCPoint> set = (nodes instanceof TreeSet) ? (TreeSet<SWCPoint>) nodes
				: new TreeSet<SWCPoint>(nodes);
		if (pafm.importNodes(null, set, null, false))
			return pafm;
		else
			return null;
	}

	/**
	 * Create a new PathAndFillManager instance from the graph.
	 *
	 * @param graph                 The input graph
	 * @param keepTreePathStructure Whether to maintain the path hierarchy of the Tree backing the input graph.
	 *                              If this is set to true, the graph must have been created from operations on
	 *                              at least one Tree.
	 *                              Note that even if the graph is backed by a Tree, this may not always be possible.
	 *                              For instance, consider the case where two graphs oriented in opposing directions
	 *                              are merged at an edge. One graph will need to be re-oriented, which will change
	 *                              the Path hierarchy of the result.
	 */
	public static PathAndFillManager createFromGraph(final DirectedWeightedGraph graph,
													 final boolean keepTreePathStructure) {
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		if (keepTreePathStructure) {
			pafm.importGraphWithPathStructure(graph);
		} else {
			pafm.importGraph(graph);
		}
		return pafm;
	}

	protected void importGraph(final DirectedWeightedGraph graph) {
		final boolean existingEnableUIupdates = enableUIupdates;
		this.enableUIupdates = false;
		final SWCPoint root = graph.getRoot();
		final DepthFirstIterator<SWCPoint, SWCWeightedEdge> depthFirstIterator = graph.getDepthFirstIterator(root);
		Path currentPath = new Path(1d, 1d, 1d, "? units");
		currentPath.createCircles();
		currentPath.setIsPrimary(true);
		boolean addStartJoin = false;
		while (depthFirstIterator.hasNext()) {
			final SWCPoint point = depthFirstIterator.next();
			if (addStartJoin) {
				final SWCPoint previousPoint = Graphs.predecessorListOf(graph, point).get(0);
				currentPath.setStartJoin(previousPoint.onPath, previousPoint.clone());
				addStartJoin = false;
			}
			currentPath.addNode(point);
			point.setPath(currentPath);
			if (graph.outDegreeOf(point) == 0) {
				currentPath.setIDs(currentPath.getID(), maxUsedTreeID);
				currentPath.setColor(point.getColor());
				addPath(currentPath);
				final String tags = point.getTags();
				if (tags != null && !tags.isEmpty()) {
					currentPath.setName(currentPath.getName() + "{" + tags + "}");
				}
				currentPath.setSWCType(point.type);
				currentPath.setGuessedTangents(2);
				currentPath = new Path(1d, 1d, 1d, "? units");
				currentPath.createCircles();
				addStartJoin = true;
			}
		}
		this.enableUIupdates = existingEnableUIupdates;
		resetListeners(null, true);

		// Infer fields for when an image has not been specified. We'll assume
		// the image dimensions to be those of the coordinates bounding box.
		// This allows us to open a SWC file without a source image
		{
			if (boundingBox == null)
				boundingBox = new BoundingBox();
			boundingBox.append(graph.vertexSet().iterator());
			checkForAppropriateImageDimensions();
		}
	}

	protected void importGraphWithPathStructure(final DirectedWeightedGraph graph) {
		final boolean existingEnableUIupdates = enableUIupdates;
		this.enableUIupdates = false;
		final SWCPoint root = graph.getRoot();
		if (root.getPath() == null) {
			throw new IllegalArgumentException("The root vertex does not have an associated Path. " +
					"Was this graph created from a Tree?");
		}
		Path currentPath = root.getPath().createPath();
		currentPath.setName(root.getPath().getName());
		currentPath.createCircles();
		currentPath.setIsPrimary(true);
		final Deque<SWCPoint> stack = new ArrayDeque<>();
		stack.push(root);
		boolean addStartJoin = false;
		while (!stack.isEmpty()) {
			final SWCPoint point = stack.pop();
			if (addStartJoin) {
				final SWCPoint previousPoint = Graphs.predecessorListOf(graph, point).get(0);
				currentPath.setStartJoin(previousPoint.onPath, previousPoint.clone());
				addStartJoin = false;
			}
			currentPath.addNode(point);
			point.setPath(currentPath);
			final Set<SWCPoint> children = graph.outgoingEdgesOf(point).stream()
					.map(SWCWeightedEdge::getTarget)
					.collect(Collectors.toSet());
			if (children.size() == 1) {
				final SWCPoint child = children.iterator().next();
				stack.push(child);
				if (child.getPath().getID() != currentPath.getID() ||
						child.getPath().getTreeID() != currentPath.getTreeID()) {
					currentPath.setIDs(currentPath.getID(), maxUsedTreeID);
					addPath(currentPath, false, true);
					currentPath.setGuessedTangents(2);
					currentPath = child.getPath().createPath();
					currentPath.setName(child.getPath().getName());
					currentPath.createCircles();
					addStartJoin = true;
				}

			} else if (children.size() > 1) {
				Set<SWCPoint> addLast = new HashSet<>();
				for (final SWCPoint child : children) {
					if (child.getPath().getID() == currentPath.getID() &&
							child.getPath().getTreeID() == currentPath.getTreeID()) {
						addLast.add(child);
						continue;
					}
					stack.push(child);
				}
				if (!addLast.isEmpty()) {
					addLast.forEach(stack::push);
				} else {
					currentPath.setIDs(currentPath.getID(), maxUsedTreeID);
					addPath(currentPath, false, true);
					currentPath.setGuessedTangents(2);
					final SWCPoint peeked = stack.peek();
					if (peeked != null) {
						currentPath = peeked.getPath().createPath();
						currentPath.setName(peeked.getPath().getName());
						currentPath.createCircles();
						addStartJoin = true;
					}
				}

			} else {
				currentPath.setIDs(currentPath.getID(), maxUsedTreeID);
				addPath(currentPath, false, true);
				currentPath.setGuessedTangents(2);
				final SWCPoint peeked = stack.peek();
				if (peeked != null) {
					currentPath = peeked.getPath().createPath();
					currentPath.setName(peeked.getPath().getName());
					currentPath.createCircles();
					addStartJoin = true;
				}
			}
		}
		this.enableUIupdates = existingEnableUIupdates;
		resetListeners(null, true);

		// Infer fields for when an image has not been specified. We'll assume
		// the image dimensions to be those of the coordinates bounding box.
		// This allows us to open a SWC file without a source image
		{
			if (boundingBox == null)
				boundingBox = new BoundingBox();
			boundingBox.append(graph.vertexSet().iterator());
			checkForAppropriateImageDimensions();
		}
	}

	private boolean load(final InputStream is) {

		final boolean existingEnableUiUpdates = enableUIupdates;
		try {

			enableUIupdates = false;
			final SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setValidating(true);
			final SAXParser parser = factory.newSAXParser();

			if (is != null) parser.parse(is, this);
			else if (null != null) {
				final InputSource inputSource = new InputSource((Reader) null);
				parser.parse(inputSource, this);
			}

		}
		catch (final javax.xml.parsers.ParserConfigurationException e) {

			clear();
			SNTUtils.error("ParserConfigurationException", e);
			return false;

		}
		catch (final SAXException e) {

			clear();
			error(e.getMessage());
			return false;

		}
		catch (final FileNotFoundException e) {

			clear();
			SNTUtils.error("FileNotFoundException", e);
			e.printStackTrace();
			return false;

		}
		catch (final IOException e) {

			clear();
			error(
				"There was an IO exception while reading the file. See console for details");
			e.printStackTrace();
			return false;

		} finally {
			enableUIupdates = existingEnableUiUpdates;
		}
		resetListeners(null, true);
		return true;

	}

	/**
	 * Deletes all paths and fills.
	 */
	public void clear() {
		resetIDs();
		if (plugin != null && plugin.use3DViewer) {
			for (final Path p : allPaths)
				p.removeFrom3DViewer(plugin.univ);
		}
		allPaths.clear();
		pathIdMap.clear();
		pathNameMap.clear();
		pathNameLowercaseMap.clear();
		allFills.clear();
		if (plugin == null || (plugin != null && !plugin.accessToValidImageData()))
			resetSpatialSettings(false);
		resetListeners(null);
	}

	protected void resetIDs() {
		// NB: a path id should not be zero so that its fitted version can have an ID of -pathID
		maxUsedPathID = 0;
		maxUsedTreeID = 0;
	}

	/**
	 * Imports an SWC file using default settings.
	 * @param descriptor the identifier for the imported data
	 * @param urlOrFilePath the URL pointing to the SWC file or the absolute file
	 *          path of a local file. Note that with URLs, https may not be
	 *          supported.
	 * @return true, if import was successful
	 * @see NeuroMorphoLoader
	 * @see #importSWC(String, boolean)
	 */
	public boolean importSWC(final String descriptor, final String urlOrFilePath) {
		if (SNTUtils.isValidURL(urlOrFilePath)) {
			try {
				final URL url = new URL(urlOrFilePath);
				final InputStream is = url.openStream();
				return importSWC(descriptor, new BufferedReader(new InputStreamReader(is)));
			}
			catch (final IOException e) {
				return false;
			}
		}
		else {
			return importSWC(urlOrFilePath, false);
		}
	}

	/**
	 * Imports a group of SWC files (Remote URLs supported).
	 *
	 * @param swcs the HashMap containing the absolute file paths (or URLs) of
	 *          files to be imported as values and a file descriptor as keys.
	 * @param color the color to be applied to imported Paths. If null, paths from
	 *          each file will assigned unique colors
	 * @return the List of imported {@link Tree}s labeled after the file
	 *         descriptor. The returned list will not contain null elements: If a
	 *         file was not successfully imported an empty Tree will be generated
	 * @see Tree#isEmpty()
	 * @see Tree#getLabel()
	 * @see SNTColor#getDistinctColors(int)
	 */
	public List<Tree> importSWCs(final Map<String, String> swcs,
		final ColorRGB color)
	{
		final List<Tree> result = new ArrayList<>();
		final ColorRGB[] colors;
		if (color == null) {
			colors = SNTColor.getDistinctColors(swcs.size());
		}
		else {
			colors = new ColorRGB[swcs.size()];
			Arrays.fill(colors, color);
		}
		final boolean headlessState = headless;
		setHeadless(true);
		final int[] colorIdx = { 0 };
		swcs.forEach((treeDescription, filePath) -> {
			SNTUtils.log("Loading " + treeDescription + ": " + filePath);
			final Tree tree = new Tree();
			tree.setLabel(treeDescription);
			tree.setColor(colors[colorIdx[0]]);
			result.add(tree);
			final int firstImportedPathIdx = size();
			if (!importSWC(treeDescription, filePath)) {
				return; // here means 'continue;'
			}
			for (int i = firstImportedPathIdx; i < size(); i++) {
				final Path p = getPath(i);
				//p.setTreeLabel(id);
				p.setColor(colors[colorIdx[0]]);
				tree.add(p);
			}
			colorIdx[0]++;
		});
		setHeadless(headlessState);
		return result;
	}

	private boolean importSWC(final String descriptor, final BufferedReader br) throws IOException
	{
		return importSWC(br, descriptor, false, 0, 0, 0, 1, 1, 1, false);
	}

	/**
	 * Imports SWC data with advanced settings. The SWC format is described in
	 * <a href="https://www.ncbi.nlm.nih.gov/pubmed/9821633">PMID 9821633</a> and
	 * e.g.,
	 * <a href="http://www.neuromorpho.org/myfaq.jsp#qr3">neuromorpho.org</a> It
	 * is named after the initials of Stockley, Wheal, and Cole, who earlier
	 * developed a pioneer system for morphometric reconstructions
	 * (<a href="https://www.ncbi.nlm.nih.gov/pubmed/8321013">PMID 8321013</a>).
	 * <p>
	 * Annoyingly, While the SWC specification details the usage of of world
	 * coordinates in microns, some published SWC files have adopted image (pixel)
	 * coordinates, which is inappropriate and less useful (an example of the
	 * latter seems to part of the DIADEM Challenge data set). In addition, it's
	 * not clear what the "radius" column is meant to mean in such files.
	 * </p>
	 * @param descriptor the label describing the Tree associated with the data
	 * @param br the character stream containing the data
	 * @param assumeCoordinatesInVoxels If true, the SWC coordinates are assumed
	 *          to be in image coordinates ("pixels"). Note that in this case,
	 *          radii will be scaled by the minimum voxel separation. This
	 *          workaround seems to be required to properly import unscaled files
	 * @param xOffset the offset to be applied to all X coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param yOffset the offset to be applied to all Y coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param zOffset the offset to be applied to all Z coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param xScale the scaling factor for all X coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param yScale the scaling factor for all Y coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param zScale the scaling factor for all Z coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param replaceAllPaths If true, all existing Paths will be deleted before
	 *          the import. Default is false.
	 * @return true, if import was successful
	 */
	protected boolean importSWC(final BufferedReader br,
		final String descriptor,
		final boolean assumeCoordinatesInVoxels, final double xOffset,
		final double yOffset, final double zOffset, final double xScale,
		final double yScale, final double zScale, final boolean replaceAllPaths, final int... swcTypes)
	{

		if (replaceAllPaths) clear();

		final Pattern pEmpty = Pattern.compile("^\\s*$");
		final Pattern pComment = Pattern.compile("^([^#]*)#.*$");

		final TreeSet<SWCPoint> nodes = new TreeSet<>();
		String line;
		try {
			while ((line = br.readLine()) != null) {
				final Matcher mComment = pComment.matcher(line);
				line = mComment.replaceAll("$1").trim();
				final Matcher mEmpty = pEmpty.matcher(line);
				if (mEmpty.matches()) continue;
				final String[] fields = line.split("\\s+");
				if (fields.length < 7) {
					error("Wrong number of fields (" + fields.length + ") in line: " +
						line);
					return false;
				}
				try {
					final int type = Integer.parseInt(fields[1]);
					if (matchesType(type, swcTypes)) {
						final int id = Integer.parseInt(fields[0]);
						final double x = xScale * Double.parseDouble(fields[2]) + xOffset;
						final double y = yScale * Double.parseDouble(fields[3]) + yOffset;
						final double z = zScale * Double.parseDouble(fields[4]) + zOffset;
						double radius;
						try {
							radius = Double.parseDouble(fields[5]);
						} catch (final NumberFormatException ignored) {
							radius = 0; // files in which radius is set to NaN
						}
						final int previous = Integer.parseInt(fields[6]);
						nodes.add(new SWCPoint(id, type, x, y, z, radius, previous));
					}
				}
				catch (final NumberFormatException nfe) {
					error("There was a malformed number in line: " + line);
					return false;
				}
			}
		}
		catch (final IOException exc) {
			SNTUtils.error("IO ERROR", exc);
			return false;
		}
		return importNodes(descriptor, nodes, null, assumeCoordinatesInVoxels);
	}

	private boolean matchesType(final int type, final int... swcTypes) {
		return swcTypes.length == 0 || Arrays.stream(swcTypes).anyMatch(t -> t == type);
	}

	private boolean importNodes(final String descriptor,
	                            final TreeSet<SWCPoint> points, final ColorRGB color,
	                            final boolean assumeCoordinatesInVoxels)
	{

		final Map<Integer, SWCPoint> idToSWCPoint = new HashMap<>();
		final List<SWCPoint> primaryPoints = new ArrayList<>();

		for (final SWCPoint point : points) {
			idToSWCPoint.put(point.id, point);
			if (point.parent == -1) {
				primaryPoints.add(point);
			}
			else {
				final SWCPoint previousPoint = idToSWCPoint.get(point.parent);
				if (previousPoint != null) {
					point.setPreviousPoint(previousPoint);
					previousPoint.getNextPoints().add(point);
				}
			}
		}

		// We'll now iterate (again!) through the points to fix some ill-assembled
		// files that do exist in the wild defined in pixel coordinates!
		if (assumeCoordinatesInVoxels) {
			final double minimumVoxelSpacing = Math.min(Math.abs(x_spacing),
					Math.min(Math.abs(y_spacing), Math.abs(z_spacing)));

			final Iterator<SWCPoint> it = points.iterator();
			while (it.hasNext()) {
				final SWCPoint point = it.next();

				point.x *= x_spacing;
				point.y *= y_spacing;
				point.z *= z_spacing;
				// this just seems to be the convention in the broken files we've came
				// across
				point.radius *= minimumVoxelSpacing;

				// If the radius is set to near zero, then artificially set it to half
				// of the voxel spacing so that something* appears in the 3D Viewer!
				if (Math.abs(point.radius) < 0.0000001)
					point.radius = minimumVoxelSpacing / 2;
			}
		}

		// FIXME: This is slow with large SWC files
		final boolean existingEnableUIupdates = enableUIupdates;
		this.enableUIupdates = false;
		final HashMap<SWCPoint, Path> pointToPath = new HashMap<>();
		final PriorityQueue<SWCPoint> backtrackTo = new PriorityQueue<>(
			primaryPoints);
		final HashMap<Path, SWCPoint> pathStartsOnSWCPoint = new HashMap<>();
		final HashMap<Path, PointInImage> pathStartsAtPointInImage =
			new HashMap<>();
		final List<Path> pathList = new ArrayList<>();

		SWCPoint start;
		Path currentPath;
		while ((start = backtrackTo.poll()) != null) {
			currentPath = new Path(x_spacing, y_spacing, z_spacing, spacing_units);
			currentPath.createCircles();
			final SWCPoint beforeStart = start.getPreviousPoint();
			if (beforeStart != null) {
				pathStartsOnSWCPoint.put(currentPath, beforeStart);
				pathStartsAtPointInImage.put(currentPath, beforeStart);
			}

			// Now we can start adding points to the path:
			SWCPoint currentPoint = start;
			while (currentPoint != null) {
				currentPath.addNode(currentPoint);
				pointToPath.put(currentPoint, currentPath);

				if (currentPoint.getNextPoints().size() > 0) {
					final SWCPoint newCurrentPoint = currentPoint.getNextPoints().get(0);
					currentPoint.getNextPoints().remove(0);
					backtrackTo.addAll(currentPoint.getNextPoints());
					currentPoint = newCurrentPoint;
				}
				else {
					currentPath.setSWCType(currentPoint.type); // Assign point
					// type to path
					currentPoint = null;
				}
			}

			// FIXME: DUP NODES: Paths contain duplicated nodes!! Remove them here:
//			for (int i = 0; i < currentPath.size(); i++) {
//				final PointInImage node1 = currentPath.getNode(i);
//				for (int j = 1; j < currentPath.size(); j++) {
//					if (i == j) continue;
//					final PointInImage node2 = currentPath.getNode(j);
//					if (node2.isSameLocation(node1)) {
//						System.out.println("Removing "+ node2);
//						currentPath.removeNode(j);
//					}
//				}
//			}
			currentPath.setGuessedTangents(2);
			currentPath.setIDs(currentPath.getID(), maxUsedTreeID);
			pathList.add(currentPath);

		}

		// Set the start joins:
		for (Path p : pathList) {
			final SWCPoint swcPoint = pathStartsOnSWCPoint.get(p);
			if (descriptor != null) {
				p.setTreeLabel(descriptor);
				p.setColor(color);
			}
			if (swcPoint == null) {
				p.setIsPrimary(true);
				continue;
			}
			final Path previousPath = pointToPath.get(swcPoint);
			final PointInImage pointInImage = pathStartsAtPointInImage.get(p);
			p.setStartJoin(previousPath, pointInImage);
		}

		// Add paths after setting all joins to ensure treeIDs are computed correctly
		for (Path p : pathList) {
			addPath(p);
		}

		this.enableUIupdates = existingEnableUIupdates;
		resetListeners(null, true);

		// Infer fields for when an image has not been specified. We'll assume
		// the image dimensions to be those of the coordinates bounding box.
		// This allows us to open a SWC file without a source image
		{
			if (boundingBox == null)
				boundingBox = new BoundingBox();
			boundingBox.append(((TreeSet<? extends SNTPoint>) points).iterator());
			checkForAppropriateImageDimensions();
		}
		return true;
	}

	/**
	 * Import neuron(s) as a collection of reconstruction nodes (SWC points)
	 *
	 * @param map         the input map of reconstruction nodes
	 * @param color       the color to be applied to imported Paths. If null, paths
	 *                    from each ID will assigned unique colors
	 * @param spatialUnit the spatial unit (um, mm, etc) associated with imported
	 *                    nodes. If null, "um" are assumed
	 * @return the map mapping imported ids to imported Trees. A null Tree will be
	 *         assigned if a morphology could not be imported
	 * @see SNTColor#getDistinctColors(int)
	 */
	public Map<String, Tree> importNeurons(final Map<String, TreeSet<SWCPoint>> map, final ColorRGB color, final String spatialUnit)
	{
		final Map<String, Tree> result = importMap(map, color);
		if (result.values().stream().anyMatch(tree -> tree != null && !tree.isEmpty())) {
			if (boundingBox == null) // should never happen
				boundingBox = new BoundingBox();
			boundingBox.setUnit((spatialUnit == null) ? "um" : spatialUnit);
			updateBoundingBox();
		}
		return result;
	}

	private Map<String, Tree> importMap(final Map<String, TreeSet<SWCPoint>> map,
		final ColorRGB color)
	{
		final Map<String, Tree> result = new HashMap<>();
		final ColorRGB[] colors;
		if (color == null) {
			colors = SNTColor.getDistinctColors(map.size());
		}
		else {
			colors = new ColorRGB[map.size()];
			Arrays.fill(colors, color);
		}
		final int[] colorIdx = { 0 };
		map.forEach((k, points) -> {
			if (points == null) {
				SNTUtils.error("Importing " + k + "... failed. Invalid structure?");
				result.put(k, null);
			}
			else {
				final int firstImportedPathIdx = size();
				SNTUtils.log("Importing " + k + "...");
				final boolean success = importNodes(k, points, colors[colorIdx[0]],
						false);
				SNTUtils.log("Successful import: " + success);
				final Tree tree = new Tree();
				tree.setLabel(k);
				tree.setColor(colors[colorIdx[0]]);
				for (int i = firstImportedPathIdx; i < size(); i++)
					tree.add(getPath(i));
				result.put(k, tree);
			}
			colorIdx[0]++;
		});
		return result;
	}

	/**
	 * Imports an SWC file.
	 *
	 * @param filePath the absolute path of the file to be imported
	 * @param ignoreCalibration the ignore calibration
	 * @return true, if import was successful
	 */
	protected boolean importSWC(final String filePath,
		final boolean ignoreCalibration)
	{
		return importSWC(filePath, ignoreCalibration, 0, 0, 0, 1, 1, 1, false);
	}

	/**
	 * Imports an SWC file using advanced options.
	 *
	 * @param filePath the absolute file path to the imported file
	 * @param assumeCoordinatesInVoxels If true, the SWC coordinates are assumed
	 *          to be in image coordinates ("pixels"). Note that in this case,
	 *          radii will be scaled by the minimum voxel separation. This
	 *          workaround seems to be required to properly import unscaled files
	 * @param xOffset the offset to be applied to all X coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param yOffset the offset to be applied to all Y coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param zOffset the offset to be applied to all Z coordinates. May be useful
	 *          to import data obtained from multiple "un-stitched" fields of
	 *          view. Default is 0.
	 * @param xScale the scaling factor for all X coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param yScale the scaling factor for all Y coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param zScale the scaling factor for all Z coordinates. Useful to import
	 *          data onto downsampled images. Default is 1.
	 * @param replaceAllPaths If true, all existing Paths will be deleted before
	 *          the import.
	 * @return true, if import was successful
	 */
	public boolean importSWC(final String filePath,
		final boolean assumeCoordinatesInVoxels, final double xOffset,
		final double yOffset, final double zOffset, final double xScale,
		final double yScale, final double zScale, final boolean replaceAllPaths,
		final int... swcTypes)
	{

		if (filePath == null) return false;
		final File f = new File(filePath);
		if (!SNTUtils.fileAvailable(f)) {
			error("The traces file '" + filePath + "' is not available.");
			return false;
		}

		InputStream is = null;
		boolean result = false;

		try {

			is = new BufferedInputStream(new FileInputStream(filePath));
			final BufferedReader br = new BufferedReader(new InputStreamReader(is,
					StandardCharsets.UTF_8));

			result = importSWC(br, SNTUtils.stripExtension(f.getName()), assumeCoordinatesInVoxels, xOffset, yOffset,
				zOffset, xScale, yScale, zScale, replaceAllPaths, swcTypes);

			if (is != null) is.close();

		}
		catch (final IOException ioe) {
			error("Could not read " + filePath);
			return false;
		}

		return result;

	}

	protected int guessTracesFileType(final String filename) {
		try {
			return guessTracesFileType(new FileInputStream(filename), true);
		} catch (IOException e) {
			errorStatic("The file '" + filename + "' could not be parsed.");
			return -1;
		}
	}

	private int guessTracesFileType(InputStream is, final boolean closeStreamAfterGuess) throws IOException {
		/*
		 * Look at the magic bytes at the start of the file:
		 *
		 * If this looks as if it's gzip compressed, assume it's a compressed traces
		 * file. If it begins "<?xml", assume it's an uncompressed traces file. If it
		 * begins with '{"' assume it is a ML JSON file, otherwise assume it's an SWC
		 * file.
		 */
		if (!headless)
			SNTUtils.log("Guessing file type...");
		if (is.markSupported())
			is.mark(-1);
		final byte[] buf = new byte[8];
		is.read(buf, 0, 8);
		if (closeStreamAfterGuess)
			is.close();
		else if (is.markSupported()) {
			is.reset();
		}
		//System.out.println("buf[0]: " + (byte)buf[0] + ", buf[1]: " + (byte)buf[1] + ", buf[2]: " + buf[2] + ", buf[3]: " + buf[3] + ", buf[4]: " + buf[4]);
		if(buf[ 0 ] == (byte) 0x1f && buf[ 1 ] == (byte) 0x8b ) { //check if matches standard gzip magic number
			return TRACES_FILE_TYPE_COMPRESSED_XML;
		} else if (((buf[0] == '<') && (buf[1] == '?') && (buf[2] == 'x') && (buf[3] == 'm') && (buf[4] == 'l')
				&& (buf[5] == ' '))) {
			return TRACES_FILE_TYPE_UNCOMPRESSED_XML;
		} else if (((char) (buf[0] & 0xFF) == '{')) {
			return TRACES_FILE_TYPE_ML_JSON;
		}
		return TRACES_FILE_TYPE_SWC;
	}

	protected boolean loadCompressedXML(final String filename) {
		try {
			SNTUtils.log("Loading gzipped file...");
			return load(new GZIPInputStream(new BufferedInputStream(
				new FileInputStream(filename))));
		}
		catch (final IOException ioe) {
			error("Could not read file '" + filename +
				"' (it was expected to be compressed XML)");
			return false;
		}
	}

	protected boolean loadUncompressedXML(final String filename, final int...swcTypes) {
		try {
			SNTUtils.log("Loading uncompressed file...");
			return load(new BufferedInputStream(new FileInputStream(filename)));
		}
		catch (final IOException ioe) {
			error("Could not read '" + filename + "' (it was expected to be XML)");
			return false;
		}
	}

	private boolean loadJSON(final String filename, final int... swcTypes) {
		String compartment = "all";
		if (swcTypes.length == 1 && swcTypes[0] == Path.SWC_AXON) {
			compartment = "axon";
		}
		else if (swcTypes.length == 1 && (swcTypes[0] == Path.SWC_DENDRITE || swcTypes[0] == Path.SWC_APICAL_DENDRITE)
				|| swcTypes.length == 2
						&& Arrays.stream(swcTypes).sum() == Path.SWC_DENDRITE + Path.SWC_APICAL_DENDRITE) {
			compartment = "dendrite";
		}
		try {
			final Map<String, TreeSet<SWCPoint>> nMap = MouseLightLoader.extractNodes(new File(filename), compartment);
			final Map<String, Tree> outMap = importNeurons(nMap, null, "um");
			return outMap.values().stream().anyMatch(tree -> tree != null && !tree.isEmpty());
		} catch (final FileNotFoundException | IllegalArgumentException | JSONException e) {
			error("Failed to read file: '" + filename + "' (" + e.getMessage() +")");
			return false;
		}
	}

	/**
	 * Imports a reconstruction file (any supported extension).
	 *
	 * @param filePath the absolute path to the file (.Traces, SWC or JSON) to be
	 *                 imported
	 * @param swcTypes (Optional) The SWC type(s) (e.g., {@link Path#SWC_AXON},
	 *                 {@link Path#SWC_DENDRITE}, etc.) to be considered in the
	 *                 import. Ignored if {@code filePath} encodes a .TRACES file.
	 * @return true, if successful
	 */
	public boolean load(final String filePath, final int... swcTypes) {
		final boolean existingEnableUiUpdates = enableUIupdates;
		enableUIupdates = false;
		final int guessedType = guessTracesFileType(filePath);
		boolean result;
		switch (guessedType) {
			case TRACES_FILE_TYPE_COMPRESSED_XML:
				result = loadCompressedXML(filePath);
				break;
			case TRACES_FILE_TYPE_UNCOMPRESSED_XML:
				result = loadUncompressedXML(filePath);
				break;
			case TRACES_FILE_TYPE_ML_JSON:
				result = loadJSON(filePath, swcTypes);
				break;
			case TRACES_FILE_TYPE_SWC:
				result = importSWC(filePath, false, 0, 0, 0, 1, 1, 1, true, swcTypes);
				break;
			default:
				SNTUtils.warn("guessTracesFileType() return an unknown type" + guessedType);
				return false;
		}
		enableUIupdates = existingEnableUiUpdates;
		if (result) {
			final File file = new File(filePath);
			if (getPlugin() != null) getPlugin().getPrefs().setRecentDir(file);
			if (boundingBox != null) boundingBox.info = file.getName();
			resetListeners(null, true);
		}
		return result;
	}

	public boolean loadGuessingType(final String optionalDescription, final InputStream is) throws IOException {
		final BufferedInputStream bis = (is instanceof BufferedInputStream) ? ((BufferedInputStream)is) : new BufferedInputStream(is);
		final int guessedType = guessTracesFileType(bis, false);
		final boolean existingEnableUiUpdates = enableUIupdates;
		enableUIupdates = false;
		boolean result;
		switch (guessedType) {
		case TRACES_FILE_TYPE_COMPRESSED_XML:
			SNTUtils.log("Loading gzipped file...");
			result = load(new GZIPInputStream(bis));
			break;
		case TRACES_FILE_TYPE_UNCOMPRESSED_XML:
			SNTUtils.log("Loading uncompressed file...");
			result = load(bis);
			break;
		case TRACES_FILE_TYPE_ML_JSON:
			final Map<String, TreeSet<SWCPoint>> nMap = MouseLightLoader.extractNodes(bis, "all");
			final Map<String, Tree> outMap = importNeurons(nMap, null, "um");
			result = outMap.values().stream().anyMatch(tree -> tree != null && !tree.isEmpty());
			break;
		case TRACES_FILE_TYPE_SWC:
			final BufferedReader br = new BufferedReader(new InputStreamReader(bis, StandardCharsets.UTF_8));
			result = plugin.getPathAndFillManager().importSWC(br, optionalDescription, false, 0, 0, 0, 1, 1, 1, true);
			break;
		default:
			SNTUtils.warn("guessTracesFileType() return an unknown type" + guessedType);
			result = false;
			break;
		}
		enableUIupdates = existingEnableUiUpdates;
		resetListeners(null, true);
		return result;
	}

	private void checkForAppropriateImageDimensions() {
		if (plugin != null && plugin.getImagePlus() != null) {
			// If a plugin exists, warn user if its image cannot render imported nodes
			final BoundingBox pluginBoundingBox = new BoundingBox();
			pluginBoundingBox.setOrigin(new PointInImage(0, 0, 0));
			pluginBoundingBox.setDimensions(plugin.width, plugin.height, plugin.depth);
			if (!pluginBoundingBox.contains(boundingBox)) {
				plugin.getPrefs().setTemp(SNTPrefs.RESIZE_REQUIRED, true);
				SNTUtils.warn("Some nodes lay outside the image volume: you may need to "
						+ "adjust import options or resize current image canvas");
			}
		}
	}

	/*
	 * This method will set all the points in array that correspond to points on one
	 * of the paths to 255, leaving everything else as it is. This is useful for
	 * creating stacks that can be used in skeleton analysis plugins that expect a
	 * stack of this kind.
	 */
	synchronized void setPathPointsInVolume(final Collection<Path> paths,
		final short[][] slices, final int pixelIntensity, final int width, final int height, final int depth)
	{
		for (final Path topologyPath : paths) {
			Path p = topologyPath;
			if (topologyPath.getUseFitted()) {
				p = topologyPath.getFitted();
			}
			final int n = p.size();

			final ArrayList<Bresenham3D.IntegerPoint> pointsToJoin =
				new ArrayList<>();

			if (p.startJoins != null) {
				final PointInImage s = p.startJoinsPoint;
				final Path sp = p.startJoins;
				final int spi = sp.indexNearestTo(s.x, s.y, s.z);
				pointsToJoin.add(new Bresenham3D.IntegerPoint(sp.getXUnscaled(spi), sp
					.getYUnscaled(spi), sp.getZUnscaled(spi)));
			}

			for (int i = 0; i < n; ++i) {
				pointsToJoin.add(new Bresenham3D.IntegerPoint(p.getXUnscaled(i), p
					.getYUnscaled(i), p.getZUnscaled(i)));
			}

			Bresenham3D.IntegerPoint previous = null;
			for (final Bresenham3D.IntegerPoint current : pointsToJoin) {
				if (previous == null) {
					previous = current;
					continue;
				}

				/*
				 * If we don't actually need to draw a line, just put a point:
				 */
				if (current.diagonallyAdjacentOrEqual(previous)) {
					try {
						slices [current.z][current.y * width + current.x] = (short) pixelIntensity;
					} catch (final ArrayIndexOutOfBoundsException ignored) {
						SNTUtils.warn(String.format("Bresenham3D: Out-of-bounds point at [%d,%d,%d]", current.x,
								current.y * width, current.z));
					}
				}
				else {
					/*
					 * Otherwise draw a line with the 3D version of Bresenham's algorithm:
					 */
					final List<Bresenham3D.IntegerPoint> pointsToDraw = Bresenham3D
						.bresenham3D(previous, current);
					for (final Bresenham3D.IntegerPoint ip : pointsToDraw) {
						try {
							slices[ip.z][ip.y * width + ip.x] = (short) pixelIntensity;
						}
						catch (final ArrayIndexOutOfBoundsException ignored) {
							SNTUtils.warn(String.format("Bresenham3D: Out-of-bounds point at [%d,%d,%d]", ip.x,
									ip.y * width, ip.z));
						}
					}
				}

				previous = current;
			}
		}
	}

	synchronized PointInImage nearestJoinPointOnSelectedPaths(final double x,
		final double y, final double z)
	{

		PointInImage result = null;
		double minimumDistanceSquared = Double.MAX_VALUE;

		for (final Path p : allPaths) {

			if (!selectedPathsSet.contains(p)) continue;

			if (0 == p.size()) continue;

			final int i = p.indexNearestTo(x * x_spacing, y * y_spacing, z *
					z_spacing);

			final PointInImage nearestOnPath = p.getNodeWithoutChecks(i);

			final double distanceSquared = nearestOnPath.distanceSquaredTo(x *
					x_spacing, y * y_spacing, z * z_spacing);

			if (distanceSquared < minimumDistanceSquared) {
				result = nearestOnPath;
				minimumDistanceSquared = distanceSquared;
			}
		}

		return result;
	}

	/**
	 * Returns all the paths.
	 *
	 * @return the paths associated with this PathAndFillManager instance.
	 */
	public ArrayList<Path> getPaths() {
		return allPaths;
	}

	/**
	 * Returns the 'de facto' Paths.
	 *
	 * @return the paths associated with this PathAndFillManager instance excluding
	 *         those that are null or fitted version of o paths.
	 */
	public List<Path> getPathsFiltered() { // FIXME: this is no longer needed
		return (List<Path>) getPaths().stream().filter(p -> p != null && !p.isFittedVersionOfAnotherPath())
				.collect(Collectors.toList());
	}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#transformationStarted(org.scijava.java3d.View)
	 */
	// Methods we need to implement for UniverseListener:
	@Override
	public void transformationStarted(final View view) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#transformationUpdated(org.scijava.java3d.View)
	 */
	@Override
	public void transformationUpdated(final View view) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#transformationFinished(org.scijava.java3d.View)
	 */
	@Override
	public void transformationFinished(final View view) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#contentAdded(ij3d.Content)
	 */
	@Override
	@Deprecated
	public void contentAdded(final Content c) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#contentRemoved(ij3d.Content)
	 */
	@Override
	@Deprecated
	public void contentRemoved(final Content c) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#contentChanged(ij3d.Content)
	 */
	@Override
	@Deprecated
	public void contentChanged(final Content c) {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#contentSelected(ij3d.Content)
	 */
	@Override
	@Deprecated
	public void contentSelected(final Content c) {
		if (c == null) return;
		final String contentName = c.getName();
		final Path selectedPath = getPathFrom3DViewerName(contentName);
		if (plugin != null && selectedPath != null) plugin.selectPath(selectedPath,
			false);
	}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#canvasResized()
	 */
	@Override
	@Deprecated
	public void canvasResized() {}

	/* (non-Javadoc)
	 * @see ij3d.UniverseListener#universeClosed()
	 */
	@Override
	@Deprecated
	public void universeClosed() {
		if (plugin != null) {
			plugin.use3DViewer = false;
			plugin.univ = null;
		}
	}
	// ... end of methods for UniverseListener

	public NearPoint nearestPointOnAnyPath(final double x, final double y,
		final double z, final double distanceLimit)
	{
		return nearestPointOnAnyPath(allPaths, new PointInImage(x, y, z), Math.sqrt(
			distanceLimit), false);
	}

	protected List<Path> getAllPathsRenderedInViewPort(
		final TracerCanvas canvas)
	{
		final List<Path> paths = getUnSelectedPathsRenderedInViewPort(canvas);
		paths.addAll(getSelectedPathsRenderedInViewPort(canvas));
		return paths;
	}

	protected List<Path> getSelectedPathsRenderedInViewPort(
		final TracerCanvas canvas)
	{
		final List<Path> paths = new ArrayList<>();
		for (final Path path : allPaths) {
			if (path.isSelected() && path.containsUnscaledNodesInViewPort(canvas))
				paths.add(path);
		}
		return paths;
	}

	protected List<Path> getUnSelectedPathsRenderedInViewPort(
		final TracerCanvas canvas)
	{
		final List<Path> paths = new ArrayList<>();
		for (final Path path : allPaths) {
			if (!path.isSelected() && path.containsUnscaledNodesInViewPort(canvas))
				paths.add(path);
		}
		return paths;
	}

	public NearPoint nearestPointOnAnyPath(final Collection<Path> paths, final PointInCanvas pic,
			final double distanceLimit) {
		return nearestPointOnAnyPath(paths, pic, distanceLimit * distanceLimit, true);
	}

	protected NearPoint nearestPointOnAnyPath(final Collection<Path> paths,
		final PointInImage pim, final double distanceLimitSquared,
		final boolean unScaledPositions)
	{

		// Order all points in all paths by their Euclidean distance to pim:
		final PriorityQueue<NearPoint> pq = new PriorityQueue<>();

		for (final Path path : paths) {
			if (!path.versionInUse()) continue;
			for (int j = 0; j < path.size(); ++j) {
				pq.add(new NearPoint(pim, path, j, unScaledPositions));
			}
		}

		while (true) {

			final NearPoint np = pq.poll();
			if (np == null) return null;

			/*
			 * Don't bother looking at points that are more than distanceLimit away. Since
			 * we get them in the order closest to furthest away, if we exceed this limit
			 * returned:
			 */
			if (np.distanceToPathPointSquared() > distanceLimitSquared) return null;

			final double distanceToPath = np.distanceToPathNearPoint();
			if (distanceToPath >= 0) return np;
		}
	}

	public Iterator<PointInImage> allPointsIterator() {
		return new AllPointsIterator();
	}

	/*
	 * NB: this returns the number of points in the currently used version of each
	 * path.
	 */
	@SuppressWarnings("unused")
	private int pointsInAllPaths() {
		final Iterator<PointInImage> a = allPointsIterator();
		int points = 0;
		while (a.hasNext()) {
			a.next();
			++points;
		}
		return points;
	}

	/**
	 * For each point in this PathAndFillManager, find the corresponding point on
	 * the other one. If there's no corresponding one, include a null instead. *
	 *
	 * @param other the other PathAndFillManager holding the corresponding Paths
	 * @param maxDistance the distance limit below which the NearPoint is
	 *          considered
	 * @return the cloud of {@link NearPoint} correspondences
	 */
	public List<NearPoint> getCorrespondences(final PathAndFillManager other,
		final double maxDistance)
	{

		final ArrayList<NearPoint> result = new ArrayList<>();

		final Iterator<PointInImage> i = allPointsIterator();
		while (i.hasNext()) {
			final PointInImage p = i.next();
			final NearPoint np = other.nearestPointOnAnyPath(p.x, p.y, p.z,
				maxDistance);
			result.add(np);
		}
		return result;
	}

	protected List<Fill> getAllFills() {
		return allFills;
	}

	/**
	 * Export fills as CSV.
	 *
	 * @param outputFile the output file
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void exportFillsAsCSV(final File outputFile) throws IOException {

		final String[] headers = new String[] { "FillID", "SourcePaths",
			"Threshold", "Metric", "Volume", "LengthUnits" };

		final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
			new FileOutputStream(outputFile.getAbsolutePath()), StandardCharsets.UTF_8));
		final int columns = headers.length;
		for (int c = 0; c < columns; ++c) {
			SNTUtils.csvQuoteAndPrint(pw, headers[c]);
			if (c < (columns - 1)) pw.print(",");
		}
		pw.print("\r\n");
		for (int i = 0; i < allFills.size(); ++i) {
			final Fill f = allFills.get(i);
			SNTUtils.csvQuoteAndPrint(pw, i);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, f.getSourcePathsStringMachine());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, f.getThreshold());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, f.getMetric());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, f.getVolume());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, f.spacing_units);
			pw.print("\r\n");
		}
		pw.close();
	}

	/**
	 * Output some potentially useful information about all the Paths managed by
	 * this instance as a CSV (comma separated values) file.
	 *
	 * @param outputFile the output file
	 * @throws IOException if data could not be stored
	 */
	public void exportToCSV(final File outputFile) throws IOException {
		// FIXME: also add statistics on volumes of fills and
		// reconstructions...
		final String[] headers = { "PathID", "PathName", "SWCType", "PrimaryPath",
			"PathLength", "PathLengthUnits", "StartsOnPath", "EndsOnPath",
			"ConnectedPathIDs", "ChildPathIDs", "StartX", "StartY", "StartZ", "EndX",
			"EndY", "EndZ", "ApproximateFittedVolume" };

		final Path[] primaryPaths = getPathsStructured();
		final HashSet<Path> h = new HashSet<>();
		Collections.addAll(h, primaryPaths);

		final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
			new FileOutputStream(outputFile.getAbsolutePath()), StandardCharsets.UTF_8));
		final int columns = headers.length;
		for (int c = 0; c < columns; ++c) {
			SNTUtils.csvQuoteAndPrint(pw, headers[c]);
			if (c < (columns - 1)) pw.print(",");
		}
		pw.print("\r\n");
		for (final Path p : allPaths) {
			Path pForLengthAndName = p;
			if (p.getUseFitted()) {
				pForLengthAndName = p.getFitted();
			}
			if (p.fittedVersionOf != null) continue;
			SNTUtils.csvQuoteAndPrint(pw, p.getID());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, pForLengthAndName.getName());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, Path.getSWCtypeName(p.getSWCType(), false));
			pw.print(",");
			final boolean primary = h.contains(p);
			SNTUtils.csvQuoteAndPrint(pw, primary);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, pForLengthAndName.getLength());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, p.spacing_units);
			pw.print(",");
			if (p.startJoins != null) pw.print("" + p.startJoins.getID());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, p.somehowJoinsAsString());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, p.childrenAsString());
			pw.print(",");

			final double[] startPoint = new double[3];
			final double[] endPoint = new double[3];

			pForLengthAndName.getPointDouble(0, startPoint);
			pForLengthAndName.getPointDouble(pForLengthAndName.size() - 1, endPoint);

			pw.print("" + startPoint[0]);
			pw.print(",");
			pw.print("" + startPoint[1]);
			pw.print(",");
			pw.print("" + startPoint[2]);
			pw.print(",");
			pw.print("" + endPoint[0]);
			pw.print(",");
			pw.print("" + endPoint[1]);
			pw.print(",");
			pw.print("" + endPoint[2]);

			pw.print(",");
			final double fittedVolume = pForLengthAndName.getApproximatedVolume();
			pw.print(fittedVolume);
			pw.print("\r\n");
			pw.flush();
		}
		pw.close();
	}

	/*
	 * Whatever the state of the paths, update the 3D viewer to make sure that
	 * they're the right colour, the right version (fitted or unfitted) is being
	 * used, whether the line or surface representation is being used, or whether
	 * the path should be displayed at all (it shouldn't if the "Show only selected
	 * paths" option is set.)
	 */

	@SuppressWarnings("deprecation")
	protected void update3DViewerContents() {
		if (plugin != null && !plugin.use3DViewer) return;
		final boolean showOnlySelectedPaths = plugin.isOnlySelectedPathsVisible();
		// Now iterate over all the paths:

		allPaths.forEach(p -> {

			if (p.fittedVersionOf != null) return; // here interpreted as 'continue'

			final boolean selected = p.isSelected();
			final boolean customColor = plugin.displayCustomPathColors && p.hasCustomColor();
			Color3f color3f;
			if (customColor) color3f = new Color3f(p.getColor());
			else if (selected) color3f = plugin.selectedColor3f;
			else color3f = plugin.deselectedColor3f;

			p.updateContent3D(plugin.univ, // The appropriate 3D universe
				(selected || !showOnlySelectedPaths), // Visible at all?
				plugin.getPaths3DDisplay(), // How to display?
				color3f, plugin.colorImage); // Colour?

			// If path is being rendered with its own custom color, highlight it
			// somehow if is being selected
			if (p.getUseFitted()) p = p.getFitted();
			if (p.content3D != null) p.content3D.setShaded(!(customColor &&
				selected));

		});
	}

	/*
	 * A base class for all the methods we might want to use to transform paths.
	 */

	// Note that this will transform fitted Paths but lose the radii

	public PathAndFillManager transformPaths(final PathTransformer transformation,
		final ImagePlus templateImage, final ImagePlus modelImage)
	{

		double pixelWidth = 1;
		double pixelHeight = 1;
		double pixelDepth = 1;
		String units = "pixels";

		final Calibration templateCalibration = templateImage.getCalibration();
		if (templateCalibration != null) {
			pixelWidth = templateCalibration.pixelWidth;
			pixelHeight = templateCalibration.pixelHeight;
			pixelDepth = templateCalibration.pixelDepth;
			units = templateCalibration.getUnits();
		}

		final PathAndFillManager pafmResult = new PathAndFillManager(pixelWidth,
			pixelHeight, pixelDepth, units);

		final int[] startJoinsIndices = new int[size()];
		final PointInImage[] startJoinsPoints = new PointInImage[size()];
		final Path[] addedPaths = new Path[size()];

		int i = 0;
		for (final Path p : allPaths) {

			final Path startJoin = p.getStartJoins();
			if (startJoin == null) {
				startJoinsIndices[i] = -1;
			}
			else {
				startJoinsIndices[i] = allPaths.indexOf(startJoin);
				final PointInImage transformedPoint = p.getStartJoinsPoint().transform(
					transformation);
				if (transformedPoint.isReal()) startJoinsPoints[i] = transformedPoint;
			}

			final Path transformedPath = p.transform(transformation, templateImage,
				modelImage);
			if (transformedPath.size() >= 2) {
				addedPaths[i] = transformedPath;
				pafmResult.addPath(transformedPath);
			}

			++i;
		}

		for (i = 0; i < size(); ++i) {
			final int si = startJoinsIndices[i];
			if (addedPaths[i] != null) {
				if (si >= 0 && addedPaths[si] != null && startJoinsPoints[i] != null)
					addedPaths[i].setStartJoin(addedPaths[si], startJoinsPoints[i]);
			}
		}

		return pafmResult;
	}

	/**
	 * Downsamples alls path using Ramer–Douglas–Peucker simplification.
	 * Downsampling occurs only between branch points and terminal points.
	 *
	 * @param maximumPermittedDistance the maximum permitted distance between
	 *          nodes.
	 */
	public void downsampleAll(final double maximumPermittedDistance) {
		for (final Path p : allPaths) {
			p.downsample(maximumPermittedDistance);
		}
	}

	/**
	 * Gets the SNT instance.
	 *
	 * @return the {@link SNT} instance associated with this
	 *         PathManager (if any)
	 */
	public SNT getPlugin() {
		return plugin;
	}

	public class AllPointsIterator implements Iterator<PointInImage> {

		public AllPointsIterator() {
			numberOfPaths = allPaths.size();
			currentPath = null;
			currentPathIndex = -1;
			currentPointIndex = -1;
		}

		int numberOfPaths;
		// These should all be set to be appropriate to the
		// last point that was returned:
		Path currentPath;
		int currentPathIndex;
		int currentPointIndex;

		/* (non-Javadoc)
		 * @see java.util.Iterator#hasNext()
		 */
		@Override
		public boolean hasNext() {
			if (currentPath == null || currentPointIndex == currentPath.size() - 1) {
				/*
				 * Find out if there is a non-empty path after this:
				 */
				int tmpPathIndex = currentPathIndex + 1;
				while (tmpPathIndex < numberOfPaths) {
					final Path p = allPaths.get(tmpPathIndex);
					if (p.size() > 0 && p.versionInUse()) return true;
					++tmpPathIndex;
				}
				return false;
			}
			/*
			 * So we know that there's a current path and we're not at the end of it, so
			 * there must be another point:
			 */
			return true;
		}

		/* (non-Javadoc)
		 * @see java.util.Iterator#next()
		 */
		@Override
		public PointInImage next() {
			if (currentPath == null || currentPointIndex == currentPath.size() - 1) {
				currentPointIndex = 0;
				/* Move to the next non-empty path: */
				do {
					++currentPathIndex;
					if (currentPathIndex == numberOfPaths)
						throw new java.util.NoSuchElementException();
					currentPath = allPaths.get(currentPathIndex);
				} while (currentPath.size() <= 0 || !currentPath.versionInUse());
			}
			else++currentPointIndex;
			return currentPath.getNodeWithoutChecks(currentPointIndex);
		}

		/* (non-Javadoc)
		 * @see java.util.Iterator#remove()
		 */
		@Override
		public void remove() {
			throw new UnsupportedOperationException(
				"AllPointsIterator does not allow the removal of points");
		}

	}

}

@SuppressWarnings("serial")
class TracesFileFormatException extends SAXException {

	public TracesFileFormatException(final String message) {
		super(message);
	}
}

@SuppressWarnings("serial")
class SWCExportException extends Exception {

	public SWCExportException(final String message) {
		super(message);
	}
}
