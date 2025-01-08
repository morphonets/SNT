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
 * Performs persistent homology analysis on a {@link Tree}. For an overview see
 * Kanari, L. et al. A Topological Representation of Branching Neuronal
 * Morphologies. Neuroinform 16, 3–13 (2018).
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class PersistenceAnalyzer {

    protected static final int FUNC_UNKNOWN = -1;
    /**
     * The geodesic (i.e., "path") distance from a node to the tree root
     */
    protected static final int FUNC_0_GEODESIC = 0;
    /**
     * The Euclidean (i.e., "straight line") distance between a node and the tree root
     */
    protected static final int FUNC_1_RADIAL = 1;
    /**
     * The centrifugal branch order of a node
     */
    protected static final int FUNC_2_CENTRIFUGAL = 2;
    /**
     * The {@link Path} order of a node.
     *
     * @see Path#getOrder
     */
    protected static final int FUNC_3_PATH_ORDER = 3;
    /**
     * The X coordinate of a node
     */
    protected static final int FUNC_4_X = 4;
    /**
     * The Y coordinate of a node
     */
    protected static final int FUNC_5_Y = 5;
    /**
     * The Z coordinate of a node
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

    public PersistenceAnalyzer(final Tree tree) {
        this.tree = tree;
    }

    /**
     * Gets a list of supported descriptor functions.
     *
     * @return the list of available descriptors.
     */
    public static List<String> getDescriptors() {
        return Arrays.asList(FUNC_STRINGS);
    }

    /**
     * Generate Persistence Diagram using the base algorithm described by Kanari, L.,
     * Dłotko, P., Scolamiero, M. et al. A Topological Representation of Branching
     * Neuronal Morphologies. Neuroinform 16, 3–13 (2018).
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
     * Gets the persistence diagram.
     *
     * @param descriptor A descriptor for the filter function as per
     *                   {@link #getDescriptors()} (case-insensitive), such as
     *                   {@code radial}, {@code geodesic}, {@code centrifugal}
     *                   (reverse Strahler), etc.
     * @return the persistence diagram
     * @throws UnknownMetricException   If {@code descriptor} is not valid
     * @throws IllegalArgumentException If the {@code tree}'s graph could not be
     *                                  obtained
     */
    public List<List<Double>> getDiagram(final String descriptor) throws UnknownMetricException, IllegalArgumentException {
        if (persistenceDiagramMap.get(descriptor) == null || persistenceDiagramMap.get(descriptor).isEmpty()) {
            compute(descriptor);
        }
        return persistenceDiagramMap.get(descriptor);
    }

    /**
     * Gets the 'barcode' for the specified filter function.
     *
     * @param descriptor A descriptor for the filter function as per
     *                   {@link #getDescriptors()} (case-insensitive), such as
     *                   {@code radial}, {@code geodesic}, {@code centrifugal}
     *                   (reverse Strahler), etc.
     * @return the barcode
     * @throws UnknownMetricException   If {@code descriptor} is not valid
     * @throws IllegalArgumentException If the {@code tree}'s graph could not be
     *                                  obtained
     */
    public List<Double> getBarcode(final String descriptor) throws UnknownMetricException, IllegalArgumentException {
        final List<List<Double>> diag = getDiagram(descriptor);
        final ArrayList<Double> barcodes = new ArrayList<>(diag.size());
        diag.forEach(point -> barcodes.add(Math.abs(point.get(1) - point.get(0))));
        return barcodes;
    }

    /**
     * Gets the persistence diagram nodes.
     *
     * @param descriptor A descriptor for the filter function as per
     *                   {@link #getDescriptors()} (case-insensitive), such as
     *                   {@code radial}, {@code geodesic}, {@code centrifugal}
     *                   (reverse Strahler), etc.
     * @return the persistence diagram nodes.
     * @throws UnknownMetricException   If {@code descriptor} is not valid.
     * @throws IllegalArgumentException If the {@code tree}'s graph could not be
     *                                  obtained
     */
    public List<List<SWCPoint>> getDiagramNodes(final String descriptor) {
        if (persistenceNodesMap.get(descriptor) == null || persistenceNodesMap.get(descriptor).isEmpty())
            compute(descriptor);
        return persistenceNodesMap.get(descriptor);
    }

    /**
     * Gets the persistence landscape as an N-dimensional vector, where N == numLandscapes x resolution.
     * For an overview of persistence landscapes, see
     * Bubenik, P. (2015). Statistical topological data analysis using persistence landscapes.
     * Journal of Machine Learning Research. 16. 77-102.
     *
     * @param descriptor    A descriptor for the filter function as per
     *                      {@link #getDescriptors()} (case-insensitive), such as
     *                      {@code radial}, {@code geodesic}, {@code centrifugal}
     *                      (reverse Strahler), etc.
     * @param numLandscapes the number of piecewise-linear functions to output.
     * @param resolution    the number of samples for all piecewise-linear functions.
     */
    public double[] getLandscape(final String descriptor, final int numLandscapes, final int resolution) {
        if (persistenceDiagramMap.get(descriptor) == null || persistenceDiagramMap.get(descriptor).isEmpty()) {
            compute(descriptor);
        }
        final List<List<Double>> diagram = persistenceDiagramMap.get(descriptor);
        return landscapeTransform(diagram, numLandscapes, resolution);
    }

    private double descriptorFunc(final DirectedWeightedGraph graph, final SWCPoint node, final int func) throws UnknownMetricException {
        switch (func) {
            case FUNC_0_GEODESIC:
                return geodesicDistanceToRoot(graph, node);
            case FUNC_1_RADIAL:
                return radialDistanceToRoot(graph, node);
            case FUNC_2_CENTRIFUGAL:
                if (!nodeValuesAssigned) {
                    StrahlerAnalyzer.classify(graph, true);
                    nodeValuesAssigned = true;
                }
                return node.v;
            case FUNC_3_PATH_ORDER:
                return node.getPath().getOrder();
            case FUNC_4_X:
                return node.getX();
            case FUNC_5_Y:
                return node.getY();
            case FUNC_6_Z:
                return node.getZ();
            default:
                throw new UnknownMetricException("Unrecognized Descriptor");
        }
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
