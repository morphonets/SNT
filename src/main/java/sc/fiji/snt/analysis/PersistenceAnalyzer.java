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

package sc.fiji.snt.analysis;

import net.imagej.ImageJ;
import org.jgrapht.Graphs;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;

/**
 * Performs persistent homology analysis on neuronal {@link Tree}s.
 * <p>
 * This class implements the algorithm described in Kanari, L. et al. "A Topological Representation of 
 * Branching Neuronal Morphologies" (Neuroinformatics 16, 3–13, 2018) to extract topological features 
 * from neuronal morphologies.
 * </p>
 * 
 * <strong>Core Concepts</strong>
 * <p>
 * <strong>Filter Functions:</strong> Mathematical functions that assign scalar values to each node in the tree
 * based on various morphological properties (distance from root, branch order, spatial coordinates, etc.).
 * </p>
 * <p>
 * <strong>Persistence Diagram:</strong> A collection of 2D points (birth, death) representing when topological 
 * features (branches) appear and disappear during a filtration process. Each point corresponds to a branch 
 * in the neuronal tree.
 * </p>
 * <p>
 * <strong>Persistence:</strong> The "lifespan" of a topological feature, calculated as death - birth. 
 * High persistence indicates morphologically significant branches, while low persistence may represent 
 * noise or minor branches.
 * </p>
 * 
 * <strong>Supported Filter Functions</strong>
 * <ul>
 * <li><strong>Geodesic:</strong> Path distance from node to root along the tree structure</li>
 * <li><strong>Radial:</strong> Euclidean (straight-line) distance from node to root</li>
 * <li><strong>Centrifugal:</strong> Reverse Strahler number (branch order from tips)</li>
 * <li><strong>Path Order:</strong> SNT path order hierarchy</li>
 * <li><strong>X, Y, Z:</strong> Spatial coordinates for directional analysis</li>
 * </ul>
 * 
 * <strong>Usage Example</strong>
 * <pre>{@code
 * // Create analyzer for a neuronal tree
 * PersistenceAnalyzer analyzer = new PersistenceAnalyzer(tree);
 * 
 * // Get persistence diagram using geodesic distance
 * List<List<Double>> diagram = analyzer.getDiagram("geodesic");
 * 
 * // Each inner list contains [birth, death] values
 * for (List<Double> pair : diagram) {
 *     double birth = pair.get(0);
 *     double death = pair.get(1);
 *     double persistence = death - birth;
 *     System.out.println("Branch: birth=" + birth + ", death=" + death + ", persistence=" + persistence);
 * }
 * 
 * // Get persistence landscape
 * double[] landscape = analyzer.getLandscape("geodesic", 5, 100);
 * }</pre>
 * 
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see Tree
 * @see <a href="https://doi.org/10.1007/s12021-017-9341-1">Kanari et al. (2018) Neuroinformatics</a>
 */
public class PersistenceAnalyzer {

    protected static final int FUNC_UNKNOWN = -1;
    /**
     * The geodesic (i.e., "path") distance from a node to the tree root.
     * <p>
     * This filter function computes the cumulative distance along the tree structure from each node 
     * to the root. For trees with edge weights representing physical distances, this gives the actual 
     * cable length from root to node.High persistence indicates branches that extend far from their
     * branching point along the tree structure.
     * </p>
     */
    protected static final int FUNC_0_GEODESIC = 0;
    
    /**
     * The Euclidean (i.e., "straight line") distance between a node and the tree root.
     * <p>
     * This filter function computes the direct 3D Euclidean distance from each node to the root, 
     * ignoring the tree structure. This can reveal spatial organization patterns that differ from 
     * the branching structure. High persistence indicates branches that extend far from the
     * root in straight-line distance, regardless of the path taken through the tree.
     * </p>
     */
    protected static final int FUNC_1_RADIAL = 1;
    
    /**
     * The centrifugal branch order of a node (reverse Strahler number).
     * <p>
     * This filter function assigns branch order values starting from the tips (order 1) and 
     * increasing toward the root. This is the reverse of the traditional Strahler ordering and 
     * emphasizes the branching complexity from the perspective of terminal branches. High
     * persistence indicates branches with complex sub-branching patterns.
     * </p>
     */
    protected static final int FUNC_2_CENTRIFUGAL = 2;
    
    /**
     * The {@link Path} order of a node as defined by SNT's path hierarchy.
     * <p>
     * This filter function uses SNT's internal path ordering system, where primary paths have order 1, 
     * their immediate children have order 2, and so on. This reflects the reconstruction order and 
     * hierarchical organization of the traced paths. High persistence indicates branches that are far
     * from primary paths in the reconstruction hierarchy.
     * </p>
     *
     * @see Path#getOrder
     */
    protected static final int FUNC_3_PATH_ORDER = 3;
    
    /**
     * The X coordinate of a node in the spatial coordinate system.
     * <p>
     * This filter function uses the X spatial coordinate directly as the filter value. This can 
     * reveal directional growth patterns and spatial organization along the X-axis. High
     * persistence indicates branches that extend significantly in the positive X direction
     * from their branching points.
     * </p>
     */
    protected static final int FUNC_4_X = 4;
    
    /**
     * The Y coordinate of a node in the spatial coordinate system.
     * <p>
     * This filter function uses the Y spatial coordinate directly as the filter value. This can 
     * reveal directional growth patterns and spatial organization along the Y-axis. High
     * persistence indicates branches that extend significantly in the positive Y direction
     * from their branching points.
     * </p>
     */
    protected static final int FUNC_5_Y = 5;
    
    /**
     * The Z coordinate of a node in the spatial coordinate system.
     * <p>
     * This filter function uses the Z spatial coordinate directly as the filter value. This can 
     * reveal directional growth patterns and spatial organization along the Z-axis (depth). High
     * persistence indicates branches that extend significantly in the positive Z direction from
     * their branching points.
     * </p>
     */
    protected static final int FUNC_6_Z = 6;

    private static final String[] FUNC_STRINGS = new String[7];

    static {
        FUNC_STRINGS[FUNC_0_GEODESIC] = "geodesic";
        FUNC_STRINGS[FUNC_1_RADIAL] = "radial";
        FUNC_STRINGS[FUNC_2_CENTRIFUGAL] = "centrifugal";
        FUNC_STRINGS[FUNC_3_PATH_ORDER] = "path order";
        FUNC_STRINGS[FUNC_4_X] = "x";
        FUNC_STRINGS[FUNC_5_Y] = "y";
        FUNC_STRINGS[FUNC_6_Z] = "z";
    }

    private final Tree tree;

    private final HashMap<String, List<List<Double>>> persistenceDiagramMap = new HashMap<>();
    private final HashMap<String, List<List<SWCPoint>>> persistenceNodesMap = new HashMap<>();

    private boolean nodeValuesAssigned;

    /**
     * Creates a new PersistenceAnalyzer for the specified neuronal tree.
     * <p>
     * The analyzer will perform topological data analysis on the tree structure to extract 
     * persistence diagrams, barcodes, and landscape representations. The tree must have a 
     * valid graph structure with a single root for the analysis to succeed.
     * </p>>
     *
     * @param tree the neuronal tree to analyze. Must be a valid Tree object with proper structure
     *             (no cycles, proper parent-child relationships, at least one tip, and a single
     *             root).
     * 
     * @throws NullPointerException if tree is null
     * 
     * @see Tree for tree structure requirements
     * @see #getDiagram(String) to begin analysis
     */
    public PersistenceAnalyzer(final Tree tree) {
        if (tree == null) {
            throw new NullPointerException("Tree cannot be null");
        }
        this.tree = tree;
    }

    /**
     * Gets a list of supported descriptor functions for persistence analysis.
     * <p>
     * Returns the string identifiers for all available filter functions that can be used 
     * with {@link #getDiagram(String)}, {@link #getBarcode(String)}, and other analysis methods.
     * These descriptors are case-insensitive when used in method calls.
     * </p>
     *
     * @return the list of available descriptors: ["geodesic", "radial", "centrifugal", 
     *         "path order", "x", "y", "z"]
     * 
     * @see #getDiagram(String)
     * @see #getBarcode(String)
     * @see #getLandscape(String, int, int)
     */
    public static List<String> getDescriptors() {
        return Arrays.asList(FUNC_STRINGS);
    }

    /**
     * Computes the persistence diagram using the algorithm from Kanari et al. (2018).
     * <p>
     * This method implements the core topological data analysis algorithm that processes the 
     * neuronal tree to extract persistence diagrams. The algorithm performs a filtration process 
     * where topological features (branches) appear and disappear based on the specified filter function.
     * </p>
     * <p>
     * <strong>Output:</strong> Populates internal data structures with:
     * <ul>
     * <li>Persistence diagram (birth-death pairs)</li>
     * <li>Associated tree nodes for each topological feature</li>
     * <li>Cached results for subsequent method calls</li>
     * </ul>
     * </p>
     *
     * @param func the string identifier for the filter function to use (case-insensitive)
     * 
     * @throws IllegalArgumentException if the descriptor is not recognized, or if the tree's 
     *                                  graph structure is invalid (e.g., multiple roots, empty tree)
     * 
     * @see <a href="https://doi.org/10.1007/s12021-017-9341-1">Kanari et al. (2018) Neuroinformatics</a>
     */
    private void compute(final String func) throws IllegalArgumentException {

        final int function = getNormFunction(func);
        if (function == FUNC_UNKNOWN) {
            throw new IllegalArgumentException("Unrecognizable descriptor \"" + func + "\". "
                    + "Maybe you meant one of the following?: \"" + String.join(", ", getDescriptors() + "\""));
        }

        final List<List<SWCPoint>> persistenceNodes = new ArrayList<>();
        final List<List<Double>> persistenceDiagram = new ArrayList<>();

        SNTUtils.log("Retrieving graph...");
        // Use simplified graph since geodesic distances are preserved as edge weights
        // This provides a significant performance boost over the full Graph.
        DirectedWeightedGraph graph = tree.getGraph().getSimplifiedGraph(); // IllegalArgumentException if i.e, tree has multiple roots
        final HashMap<SWCPoint, Double> descriptorMap = new HashMap<>();
        for (final SWCPoint node : graph.vertexSet()) {
            descriptorMap.put(node, descriptorFunc(graph, node, function));
        }
        final Set<SWCPoint> openSet = new HashSet<>();
        Map<SWCPoint, SWCPoint> parentMap = new HashMap<>();
        final List<SWCPoint> tips = graph.getTips();
        SWCPoint maxTip = tips.get(0);
        for (final SWCPoint t : tips) {
            openSet.add(t);
            t.v = descriptorMap.get(t);
            if (t.v > maxTip.v) {
                maxTip = t;
            }
        }
        final SWCPoint root = graph.getRoot();

        while (!openSet.contains(root)) {
            final List<SWCPoint> toRemove = new ArrayList<>();
            final List<SWCPoint> toAdd = new ArrayList<>();
            for (final SWCPoint l : openSet) {
                if (toRemove.contains(l)) continue;
                final SWCPoint p = Graphs.predecessorListOf(graph, l).get(0);
                final List<SWCPoint> children = Graphs.successorListOf(graph, p);
                if (openSet.containsAll(children)) {
                    //noinspection OptionalGetWithoutIsPresent
                    final SWCPoint survivor = children.stream().max(Comparator.comparingDouble(n -> n.v)).get();
                    toAdd.add(p);
                    for (final SWCPoint child : children) {
                        toRemove.add(child);
                        if (!child.equals(survivor)) {
                            persistenceDiagram.add(new ArrayList<>(Arrays.asList(descriptorMap.get(p), child.v)));
                            persistenceNodes.add(new ArrayList<>(Arrays.asList(p, backtrackToTip(child, parentMap))));
                        }
                    }
                    p.v = survivor.v;
                    parentMap.put(p, survivor);
                }
            }
            openSet.addAll(toAdd);
            toRemove.forEach(openSet::remove);
        }

        persistenceDiagram.add(new ArrayList<>(Arrays.asList(descriptorMap.get(root), root.v)));
        root.v = 0;
        persistenceNodes.add(new ArrayList<>(Arrays.asList(root, backtrackToTip(root, parentMap))));

        persistenceDiagramMap.put(func, persistenceDiagram);
        persistenceNodesMap.put(func, persistenceNodes);
        nodeValuesAssigned = false; // reset field so that it can be recycled by a different func
    }

    private SWCPoint backtrackToTip(SWCPoint p, Map<SWCPoint, SWCPoint> parentMap) {
        SWCPoint current = p;
        while (parentMap.containsKey(current)) {
            current = parentMap.get(current);
        }
        return current;
    }

    /**
     * Gets the persistence diagram for the specified filter function.
     * <p>
     * The persistence diagram is the core output of the analysis, consisting of birth-death pairs
     * that represent the "lifespan" of topological features (branches) during the filtration process.
     * Each point in the diagram corresponds to a branch in the neuronal tree.
     * </p>
     * <strong>Structure:</strong> Returns a list where each inner list contains exactly two values:
     * <ul>
     * <li><strong>Birth [0]:</strong> The filter value where the branch appears (branch point)</li>
     * <li><strong>Death [1]:</strong> The filter value where the branch disappears (tip)</li>
     * </ul>
     * <strong>Properties:</strong>
     * <ul>
     * <li>Number of points = Number of tips in the tree</li>
     * <li>All values are non-negative</li>
     * <li>For geodesic descriptor: sum of all (death-birth) = total cable length</li>
     * <li>High persistence (death-birth) indicates morphologically significant branches</li>
     * </ul>
     *
     * <strong>Example usage:</strong>
     * <pre>{@code
     * List<List<Double>> diagram = analyzer.getDiagram("geodesic");
     * for (List<Double> point : diagram) {
     *     double birth = point.get(0);
     *     double death = point.get(1);
     *     double persistence = death - birth;
     *     System.out.println("Branch: persistence = " + persistence);
     * }
     * }</pre>
     *
     * @param descriptor A descriptor for the filter function as per {@link #getDescriptors()} 
     *                   (case-insensitive). Supported values: "geodesic", "radial", "centrifugal", 
     *                   "path order", "x", "y", "z". Alternative names like "reverse strahler" 
     *                   for "centrifugal" are also accepted.
     * 
     * @return the persistence diagram as a list of [birth, death] pairs. Each inner list contains 
     *         exactly two Double values representing the birth and death of a topological feature.
     * 
     * @throws UnknownMetricException   If {@code descriptor} is not recognized as a valid filter function
     * @throws IllegalArgumentException If the tree's graph could not be obtained (e.g., tree has 
     *                                  multiple roots or is empty)
     * 
     * @see #getBarcode(String) for persistence values only
     * @see #getDiagramNodes(String) for associated tree nodes
     * @see #getDescriptors() for available descriptors
     */
    public List<List<Double>> getDiagram(final String descriptor) throws UnknownMetricException, IllegalArgumentException {
        if (persistenceDiagramMap.get(descriptor) == null || persistenceDiagramMap.get(descriptor).isEmpty()) {
            compute(descriptor);
        }
        return persistenceDiagramMap.get(descriptor);
    }

    /**
     * Gets the persistence barcode for the specified filter function.
     * <p>
     * The barcode is a simplified representation of the persistence diagram that contains only 
     * the persistence values (death - birth) for each topological feature, akin to a
     * one-dimensional summary of branch significance.
     * </p>
     * <strong>Interpretation:</strong>
     * <ul>
     * <li><strong>High values:</strong> Morphologically significant branches</li>
     * <li><strong>Low values:</strong> Minor branches or potential noise</li>
     * <li><strong>Distribution:</strong> The spread of values indicates branching complexity</li>
     * </ul>
     * <strong>Special Properties:</strong>
     * <ul>
     * <li>All values are non-negative (|death - birth|)</li>
     * <li>For geodesic descriptor: sum of all values equals total cable length</li>
     * <li>Number of values equals number of tips in the tree</li>
     * </ul>
     * 
     * <strong>Example Usage:</strong>
     * <pre>{@code
     * List<Double> barcode = analyzer.getBarcode("geodesic");
     * 
     * // Find most significant branches
     * barcode.sort(Collections.reverseOrder());
     * System.out.println("Top 5 most persistent branches:");
     * for (int i = 0; i < Math.min(5, barcode.size()); i++) {
     *     System.out.println("Branch " + (i+1) + ": " + barcode.get(i));
     * }
     * }</pre>
     *
     * @param descriptor A descriptor for the filter function as per {@link #getDescriptors()} 
     *                   (case-insensitive). Supported values: "geodesic", "radial", "centrifugal", 
     *                   "path order", "x", "y", "z".
     * 
     * @return the barcode as a list of persistence values (death - birth). Each value represents 
     *         the "lifespan" or significance of a topological feature (branch).
     * 
     * @throws UnknownMetricException   If {@code descriptor} is not recognized as a valid filter function
     * @throws IllegalArgumentException If the tree's graph could not be obtained (e.g., tree has 
     *                                  multiple roots or is empty)
     * 
     * @see #getDiagram(String) for full birth-death pairs
     * @see #getDiagramNodes(String) for associated tree nodes
     */
    public List<Double> getBarcode(final String descriptor) throws UnknownMetricException, IllegalArgumentException {
        final List<List<Double>> diag = getDiagram(descriptor);
        final ArrayList<Double> barcodes = new ArrayList<>(diag.size());
        diag.forEach(point -> barcodes.add(Math.abs(point.get(1) - point.get(0))));
        return barcodes;
    }

    /**
     * Gets the tree nodes associated with each point in the persistence diagram.
     * <p>
     * This method returns the actual {@link SWCPoint} nodes from the neuronal tree that correspond 
     * to each birth-death pair in the persistence diagram. This allows you to map topological 
     * features back to specific locations in the original morphology.
     * </p>
     * <strong>Structure:</strong> Returns a list where each inner list contains exactly two nodes:
     * <ul>
     * <li><strong>Birth Node [0]:</strong> The branch point where the topological feature appears</li>
     * <li><strong>Death Node [1]:</strong> The tip node where the topological feature disappears</li>
     * </ul>
     * <strong>Correspondence:</strong> The order of node pairs matches the order of birth-death pairs 
     * returned by {@link #getDiagram(String)}, allowing direct correlation between topological 
     * features and their spatial locations.
     * 
     * <strong>Example Usage:</strong>
     * <pre>{@code
     * List<List<Double>> diagram = analyzer.getDiagram("geodesic");
     * List<List<SWCPoint>> nodes = analyzer.getDiagramNodes("geodesic");
     * 
     * for (int i = 0; i < diagram.size(); i++) {
     *     List<Double> birthDeath = diagram.get(i);
     *     List<SWCPoint> nodesPair = nodes.get(i);
     *     
     *     double persistence = birthDeath.get(1) - birthDeath.get(0);
     *     SWCPoint branchPoint = nodesPair.get(0);
     *     SWCPoint tipPoint = nodesPair.get(1);
     *     
     *     System.out.printf("Branch with persistence %.2f: from (%.1f,%.1f,%.1f) to (%.1f,%.1f,%.1f)%n",
     *                       persistence, 
     *                       branchPoint.getX(), branchPoint.getY(), branchPoint.getZ(),
     *                       tipPoint.getX(), tipPoint.getY(), tipPoint.getZ());
     * }
     * }</pre>
     *
     * @param descriptor A descriptor for the filter function as per {@link #getDescriptors()} 
     *                   (case-insensitive). Supported values: "geodesic", "radial", "centrifugal", 
     *                   "path order", "x", "y", "z".
     * 
     * @return the persistence diagram nodes as a list of [birth_node, death_node] pairs. Each inner 
     *         list contains exactly two SWCPoint objects representing the spatial locations of the 
     *         topological feature.
     * 
     * @throws UnknownMetricException   If {@code descriptor} is not recognized as a valid filter function
     * @throws IllegalArgumentException If the tree's graph could not be obtained (e.g., tree has 
     *                                  multiple roots or is empty)
     * 
     * @see #getDiagram(String) for corresponding birth-death values
     * @see #getBarcode(String) for persistence values only
     * @see SWCPoint for node properties and methods
     */
    public List<List<SWCPoint>> getDiagramNodes(final String descriptor) {
        if (persistenceNodesMap.get(descriptor) == null || persistenceNodesMap.get(descriptor).isEmpty())
            compute(descriptor);
        return persistenceNodesMap.get(descriptor);
    }

    /**
     * Gets the persistence landscape as a vectorized representation.
     * <p>
     * Persistence landscapes transform persistence diagrams into a vector space representation that 
     * The landscape is a collection of piecewise-linear functions that capture the "shape" of the
     * persistence diagram in a stable, vectorized format.
     * </p>
     * <p>
     * <strong>Mathematical Background:</strong> Each point (birth, death) in the persistence diagram 
     * contributes a "tent" function to the landscape. The k-th landscape function at any point is 
     * the k-th largest value among all tent functions at that point. This creates a stable, 
     * multi-resolution representation of the topological features.
     * </p>
     * <p>
     * <strong>Output Structure:</strong> Returns a 1D array of length {@code numLandscapes × resolution} 
     * where the first {@code resolution} values represent the first landscape function, the next 
     * {@code resolution} values represent the second landscape function, and so on.
     * </p>
     *
     * @param descriptor    A descriptor for the filter function as per {@link #getDescriptors()} 
     *                      (case-insensitive). Supported values: "geodesic", "radial", "centrifugal", 
     *                      "path order", "x", "y", "z".
     * @param numLandscapes the number of piecewise-linear landscape functions to compute. Higher values 
     *                      capture more detailed topological information but increase computational cost. 
     *                      Typical range: 3-10.
     *                      Higher values increase computational cost and vector dimensionality.
     * @param resolution    the number of sample points for each landscape function. Higher values provide 
     *                      more precision but increase vector dimensionality. Typical range: 50-200.
     *                      Higher values increase computational cost and vector dimensionality.
     * 
     * @return the persistence landscape as a 1D array of length {@code numLandscapes × resolution}. 
     *         All values are non-negative and scaled by √2 for proper L² normalization.
     * 
     * @throws UnknownMetricException   If {@code descriptor} is not recognized as a valid filter function
     * @throws IllegalArgumentException If the tree's graph could not be obtained, or if numLandscapes 
     *                                  or resolution are non-positive
     * 
     * @see #getDiagram(String) for the underlying persistence diagram
     * @see <a href="https://jmlr.org/papers/v16/bubenik15a.html">Bubenik (2015) JMLR</a> for mathematical details
     */
    public double[] getLandscape(final String descriptor, final int numLandscapes, final int resolution) {
        if (persistenceDiagramMap.get(descriptor) == null || persistenceDiagramMap.get(descriptor).isEmpty()) {
            compute(descriptor);
        }
        final List<List<Double>> diagram = persistenceDiagramMap.get(descriptor);
        return landscapeTransform(diagram, numLandscapes, resolution);
    }

    private double descriptorFunc(final DirectedWeightedGraph graph, final SWCPoint node, final int func) throws UnknownMetricException {
        return switch (func) {
            case FUNC_0_GEODESIC -> geodesicDistanceToRoot(graph, node);
            case FUNC_1_RADIAL -> radialDistanceToRoot(graph, node);
            case FUNC_2_CENTRIFUGAL -> {
                if (!nodeValuesAssigned) {
                    StrahlerAnalyzer.classify(graph, true);
                    nodeValuesAssigned = true;
                }
                yield node.v;
            }
            case FUNC_3_PATH_ORDER -> node.getPath().getOrder();
            case FUNC_4_X -> node.getX();
            case FUNC_5_Y -> node.getY();
            case FUNC_6_Z -> node.getZ();
            default -> throw new UnknownMetricException("Unrecognized Descriptor");
        };
    }

    private int getNormFunction(final String func) {
        if (func == null || func.trim().isEmpty()) return FUNC_UNKNOWN;
        for (int i = 0; i < FUNC_STRINGS.length; i++) {
            if (FUNC_STRINGS[i].equalsIgnoreCase(func)) return i;
        }
        final String normFunc = func.toLowerCase();
        if ((normFunc.contains("reverse") && normFunc.contains("strahler"))) {
            return FUNC_2_CENTRIFUGAL;
        }
        if (normFunc.contains("path") && normFunc.contains("order")) {
            return FUNC_3_PATH_ORDER;
        }
        if (!normFunc.contains("depth")) {
            return FUNC_6_Z;
        }
        return FUNC_UNKNOWN;
    }

    private double geodesicDistanceToRoot(final DirectedWeightedGraph graph, SWCPoint node) {
        double distance = 0;
        if (node.parent == -1)
            return 0.0;
        while (node.parent != -1) {
            final SWCPoint p = Graphs.predecessorListOf(graph, node).get(0);
            final SWCWeightedEdge incomingEdge = graph.getEdge(p, node);
            final double weight = incomingEdge.getWeight();
            distance += weight;
            node = p;
        }
        return distance;
    }

    private double radialDistanceToRoot(final DirectedWeightedGraph graph, final SWCPoint node) {
        return graph.getRoot().distanceTo(node);
    }

    private double[] getMinMax(List<List<Double>> diagram) {
        double minX = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (List<Double> point : diagram) {
            if (point.get(0) < minX) minX = point.get(0);
            if (point.get(1) > maxY) maxY = point.get(1);
        }
        return new double[]{minX, maxY};
    }

    private double[] landscapeTransform(List<List<Double>> diagram, int numLandscapes, int resolution) {
        double[] sampleRange = getMinMax(diagram);
        Linspace xValues = new Linspace(sampleRange[0], sampleRange[1], resolution);
        double stepX = xValues.step;

        double[][] ls = new double[numLandscapes][resolution];
//      for (double[] l : ls) { Arrays.fill(l, 0); } // redundant: double[] arrays already 0-filled
        List<List<Double>> events = new ArrayList<>();
        for (int j = 0; j < resolution; j++) {
            events.add(new ArrayList<>());
        }
        for (List<Double> doubles : diagram) {
            double px = doubles.get(0);
            double py = doubles.get(1);
            int minIndex = Math.min(Math.max((int) Math.ceil((px - sampleRange[0]) / stepX), 0), resolution);
            int midIndex = Math.min(Math.max((int) Math.ceil((0.5 * (py + px) - sampleRange[0]) / stepX), 0), resolution);
            int maxIndex = Math.min(Math.max((int) Math.ceil((py - sampleRange[0]) / stepX), 0), resolution);
            if (minIndex < resolution && maxIndex > 0) {
                double landscapeValue = sampleRange[0] + minIndex * stepX - px;
                for (int k = minIndex; k < midIndex; k++) {
                    events.get(k).add(landscapeValue);
                    landscapeValue += stepX;
                }

                landscapeValue = py - sampleRange[0] - midIndex * stepX;
                for (int k = midIndex; k < maxIndex; k++) {
                    events.get(k).add(landscapeValue);
                    landscapeValue -= stepX;
                }
            }
        }
        for (int j = 0; j < resolution; j++) {
            events.get(j).sort(Collections.reverseOrder());
            int range = Math.min(numLandscapes, events.get(j).size());
            for (int k = 0; k < range; k++) {
                ls[k][j] = events.get(j).get(k);
            }
        }
        double[] landscape = Arrays.stream(ls)
                .flatMapToDouble(Arrays::stream)
                .toArray();
        for (int i = 0; i < landscape.length; i++) {
            landscape[i] *= Math.sqrt(2);
        }
        return landscape;
    }

    private static class Linspace {
        private final double end;
        private final double step;
        private double current;

        Linspace(double start, double end, double totalCount) {
            this.current = start;
            this.end = end;
            this.step = (end - start) / totalCount;
        }

        @SuppressWarnings("unused")
        boolean hasNext() {
            return current < (end + step / 2);
        }

        @SuppressWarnings("unused")
        double getNextDouble() {
            current += step;
            return current;
        }
    }

    /* IDE debug method */
    public static void main(final String[] args) {
        final ImageJ ij = new ImageJ();
        final SNTService sntService = ij.context().getService(SNTService.class);
        final Tree tree = sntService.demoTree("fractal");
        final PersistenceAnalyzer analyzer = new PersistenceAnalyzer(tree);
        final List<List<Double>> diagram = analyzer.getDiagram("radial");
        for (final List<Double> point : diagram) {
            System.out.println(point);
        }
        System.out.println(diagram.size());
        double[] landscape = analyzer.getLandscape("radial", 5, 100);
        System.out.println(landscape.length);
    }

}
