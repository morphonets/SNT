/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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
package sc.fiji.snt.analysis.graph;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.BiconnectivityInspector;
import org.jgrapht.alg.decomposition.HeavyPathDecomposition;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.alg.shortestpath.GraphMeasurer;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.Context;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.ColorMapper;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class GraphColorMapper<V, E extends DefaultWeightedEdge> extends ColorMapper {
    /**
     * Flag for {@value #BETWEENNESS_CENTRALITY} mapping.
     */
    public static final String BETWEENNESS_CENTRALITY = "Betweenness centrality";
    /**
     * Flag for {@value #ECCENTRICITY} mapping.
     */
    public static final String ECCENTRICITY = "Eccentricity";
    /**
     * Flag for {@value #CONNECTIVITY} mapping.
     */
    public static final String CONNECTIVITY = "Connected components";
    /**
     * Flag for {@value #EDGE_WEIGHT} mapping.
     */
    public static final String EDGE_WEIGHT = "Edge weight";
    /**
     * Flag for {@value #PAGE_RANK} mapping.
     */
    public static final String PAGE_RANK = "Page rank";
    /**
     * Flag for {@value #IN_DEGREE} mapping.
     */
    public static final String IN_DEGREE = "In degree";
    /**
     * Flag for {@value #OUT_DEGREE} mapping.
     */
    public static final String OUT_DEGREE = "Out degree";
    /**
     * Flag for {@value #INCOMING_WEIGHT} mapping.
     */
    public static final String INCOMING_WEIGHT = "Incoming weight";
    /**
     * Flag for {@value #OUTGOING_WEIGHT} mapping.
     */
    public static final String OUTGOING_WEIGHT = "Outgoing weight";
    /**
     * Flag for {@value #HEAVY_PATH_DECOMPOSITION} mapping.
     */
    public static final String HEAVY_PATH_DECOMPOSITION = "Heavy path decomposition";

    public static final int VERTICES = 1;
    public static final int EDGES = 2;
    public static final int VERTICES_AND_EDGES = 4;
    private int mappedState;
    private boolean minMaxSet = false;

    private static final String[] ALL_FLAGS = { //
            BETWEENNESS_CENTRALITY,
            ECCENTRICITY,
            CONNECTIVITY,
            EDGE_WEIGHT,
            PAGE_RANK,
            IN_DEGREE,
            OUT_DEGREE,
            INCOMING_WEIGHT,
            OUTGOING_WEIGHT,
            HEAVY_PATH_DECOMPOSITION
    };

    @Parameter
    private LUTService lutService;
    private Map<String, URL> luts;
    protected SNTGraph<V, E> graph;
    protected AsSubgraph<V, E> subgraph;


    public GraphColorMapper(final Context context) {
        this();
        context.inject(this);
    }

    public GraphColorMapper() {

    }

    /**
     * Gets the list of supported mapping metrics.
     *
     * @return the list of mapping metrics.
     */
    public static List<String> getMetrics() {

        return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
    }

    private void initLuts() {
        if (luts == null) luts = lutService.findLUTs();
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

    public ColorTable getColorTable(final String lut) {
        initLuts();
        for (final Map.Entry<String, URL> entry : luts.entrySet()) {
            if (entry.getKey().contains(lut)) {
                try {
                    return lutService.loadLUT(entry.getValue());
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public int map(SNTGraph<V, E> graph, final String measurement, final String lut) {
        map(graph, new AsSubgraph<>(graph), measurement, getColorTable(lut));
        return mappedState;
    }

    public int map(SNTGraph<V, E> graph, final String measurement, final ColorTable colorTable) {
        map(graph, new AsSubgraph<>(graph), measurement, colorTable);
        return mappedState;
    }

    public int map(SNTGraph<V, E> graph, AsSubgraph<V, E> subgraph, final String measurement, final ColorTable colorTable) {
        this.graph = graph;
        this.subgraph = subgraph;
        mapToProperty(measurement, colorTable);
        return mappedState;
    }

    protected void mapToProperty(final String measurement,
                                 final ColorTable colorTable) {
        map(measurement, colorTable);
        switch (measurement) {
            case EDGE_WEIGHT:
                mappedState = EDGES;
                mapToEdgeWeight(colorTable);
                break;
            case BETWEENNESS_CENTRALITY:
                mappedState = VERTICES;
                mapToBetweennessCentrality(colorTable);
                break;
            case ECCENTRICITY:
                mappedState = VERTICES;
                mapToEccentricity(colorTable);
                break;
            case CONNECTIVITY:
                mappedState = VERTICES_AND_EDGES;
                mapToConnectivity(colorTable);
                break;
            case PAGE_RANK:
                mappedState = VERTICES;
                mapToPageRank(colorTable);
                break;
            case IN_DEGREE:
                mappedState = VERTICES;
                mapToInDegree(colorTable);
                break;
            case OUT_DEGREE:
                mappedState = VERTICES;
                mapToOutDegree(colorTable);
                break;
            case INCOMING_WEIGHT:
                mappedState = VERTICES;
                mapToIncomingWeight(colorTable);
                break;
            case OUTGOING_WEIGHT:
                mappedState = VERTICES;
                mapToOutgoingWeight(colorTable);
                break;
            case HEAVY_PATH_DECOMPOSITION:
                mappedState = EDGES;
                mapToHeavyPathDecomposition(colorTable);
                break;
            default:
                throw new IllegalArgumentException("Unknown metric");
        }
    }

    protected void mapToConnectivity(final ColorTable colorTable) {
        BiconnectivityInspector<V, E> inspector = new BiconnectivityInspector<>(subgraph);
        Set<Graph<V, E>> components = inspector.getConnectedComponents();
        setMinMax(0, components.size());
        int idx = 0;
        for (Graph<V, E> comp : components) {
            ColorRGB c = getColorRGB(idx);
            for (E edge : comp.edgeSet()) {
                graph.setEdgeColor(edge, c);
            }
            for (V vertex : comp.vertexSet()) {
                graph.setVertexColor(vertex, c);
            }
            ++idx;
        }
    }

    protected void mapToEdgeWeight(final ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (E edge : subgraph.edgeSet()) {
                double w = subgraph.getEdgeWeight(edge);
                if (w > max) {
                    max = w;
                }
                if (w < min) {
                    min = w;
                }
            }
            setMinMax(min, max);
        }
        for (E edge : subgraph.edgeSet()) {
            ColorRGB c = getColorRGB(subgraph.getEdgeWeight(edge));
            graph.setEdgeColor(edge, c);
        }
    }

    protected void mapToBetweennessCentrality(final ColorTable colorTable) {
        BetweennessCentrality<V, E> bc = new BetweennessCentrality<>(subgraph, false);
        Map<V, Double> scores = bc.getScores();
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double s : scores.values()) {
                if (s < min) {min = s;}
                if (s > max) {max = s;}
            }
            setMinMax(min, max);
        }
        for (Map.Entry<V, Double> entry : scores.entrySet()) {
            ColorRGB c = getColorRGB(entry.getValue());
            graph.setVertexColor(entry.getKey(), c);
            graph.setVertexValue(entry.getKey(),entry.getValue() / getMinMax()[1]);
        }
    }

    protected void mapToEccentricity(ColorTable colorTable) {
        BiconnectivityInspector<V, E> inspector = new BiconnectivityInspector<>(subgraph);
        Set<Graph<V, E>> components = inspector.getConnectedComponents();
        List<Map<V, Double>> eccentricityMaps = new ArrayList<>();
        for (Graph<V, E> comp : components) {
            GraphMeasurer<V, E> measurer = new GraphMeasurer<>(comp);
            Map<V, Double> scores = measurer.getVertexEccentricityMap();
            eccentricityMaps.add(scores);
        }
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Map<V, Double> eMap : eccentricityMaps) {
                for (Double s : eMap.values()) {
                    if (s < min) {min = s;}
                    if (s > max) {max = s;}
                }
            }
            setMinMax(min, max);
        }
        for (Map<V, Double> eMap : eccentricityMaps) {
            for (Map.Entry<V, Double> entry : eMap.entrySet()) {
                ColorRGB c = getColorRGB(entry.getValue());
                graph.setVertexColor(entry.getKey(), c);
                graph.setVertexValue(entry.getKey(),entry.getValue() / getMinMax()[1]);
            }
        }
    }

    protected void mapToPageRank(final ColorTable colorTable) {
        PageRank<V, E> pr = new PageRank<>(subgraph);
        Map<V, Double> scores = pr.getScores();
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double s : scores.values()) {
                if (s < min) {min = s;}
                if (s > max) {max = s;}
            }
            setMinMax(min, max);
        }
        for (Map.Entry<V, Double> entry : scores.entrySet()) {
            ColorRGB c = getColorRGB(entry.getValue());
            graph.setVertexColor(entry.getKey(), c);
            graph.setVertexValue(entry.getKey(),entry.getValue() / getMinMax()[1]);
        }
    }

    protected void mapToInDegree(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (V vertex : subgraph.vertexSet()) {
                double d = subgraph.inDegreeOf(vertex);
                if (d < min) {min = d;}
                if (d > max) {max = d;}
            }
            setMinMax(min, max);
        }
        for (V vertex : subgraph.vertexSet()) {
            ColorRGB c = getColorRGB(subgraph.inDegreeOf(vertex));
            graph.setVertexColor(vertex, c);
            graph.setVertexValue(vertex,subgraph.inDegreeOf(vertex) / getMinMax()[1]);
        }
    }

    protected void mapToOutDegree(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (V vertex : subgraph.vertexSet()) {
                double d = subgraph.outDegreeOf(vertex);
                if (d < min) {min = d;}
                if (d > max) {max = d;}
            }
            setMinMax(min, max);
        }
        for (V vertex : subgraph.vertexSet()) {
            ColorRGB c = getColorRGB(subgraph.outDegreeOf(vertex));
            graph.setVertexColor(vertex, c);
            graph.setVertexValue(vertex,subgraph.outDegreeOf(vertex) / getMinMax()[1]);
        }
    }

    protected void mapToIncomingWeight(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (V vertex : subgraph.vertexSet()) {
                double sum = subgraph.incomingEdgesOf(vertex).stream().mapToDouble(e -> subgraph.getEdgeWeight(e)).sum();
                if (sum < min) {min = sum;}
                if (sum > max) {max = sum;}
            }
            setMinMax(min, max);
        }
        for (V vertex : subgraph.vertexSet()) {
            double sum = subgraph.incomingEdgesOf(vertex).stream().mapToDouble(e -> subgraph.getEdgeWeight(e)).sum();
            ColorRGB c = getColorRGB(sum);
            graph.setVertexColor(vertex, c);
            graph.setVertexValue(vertex, sum / getMinMax()[1]);
        }
    }

    protected void mapToOutgoingWeight(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (V vertex : subgraph.vertexSet()) {
                double sum = subgraph.outgoingEdgesOf(vertex).stream().mapToDouble(e -> subgraph.getEdgeWeight(e)).sum();
                if (sum < min) {min = sum;}
                if (sum > max) {max = sum;}
            }
            setMinMax(min, max);
        }
        for (V vertex : subgraph.vertexSet()) {
            double sum = subgraph.outgoingEdgesOf(vertex).stream().mapToDouble(e -> subgraph.getEdgeWeight(e)).sum();
            ColorRGB c = getColorRGB(sum);
            graph.setVertexColor(vertex, c);
            graph.setVertexValue(vertex, sum / getMinMax()[1]);
        }
    }

    protected void mapToHeavyPathDecomposition(ColorTable colorTable) {
        if (!minMaxSet) {
            setMinMax(0, 1);
        }
        V root = subgraph.vertexSet().stream().filter(v -> subgraph.inDegreeOf(v) == 0).findFirst().orElse(null);
        if (root == null) {
            return;
        }
        HeavyPathDecomposition<V, E> decomp = new HeavyPathDecomposition<V, E>(subgraph, root);
        Set<E> heavyEdges = decomp.getHeavyEdges();
        Set<E> lightEdges = decomp.getLightEdges();
        for (E edge : heavyEdges) {
            ColorRGB c = getColorRGB(1);
            graph.setEdgeColor(edge, c);
        }
        for (E edge : lightEdges) {
            ColorRGB c = getColorRGB(0);
            graph.setEdgeColor(edge, c);
        }
    }

    @Override
    public void setMinMax(double min, double max) {
        super.setMinMax(min, max);
        minMaxSet = true;
    }

    public void resetMinMax() {
        setMinMax(Double.NaN, Double.NaN);
        minMaxSet = false;
    }
}
