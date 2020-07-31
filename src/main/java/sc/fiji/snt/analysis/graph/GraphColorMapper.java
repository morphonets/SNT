package sc.fiji.snt.analysis.graph;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.BiconnectivityInspector;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.Context;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.ColorMapper;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class GraphColorMapper extends ColorMapper {
    /**
     * Flag for {@value #BETWEENNESS_CENTRALITY} mapping.
     */
    public static final String BETWEENNESS_CENTRALITY = "Betweenness centrality";
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


    public static final int VERTICES = 1;
    public static final int EDGES = 2;
    public static final int VERTICES_AND_EDGES = 4;
    private int mappedState;
    private boolean minMaxSet = false;

    private static final String[] ALL_FLAGS = { //
            BETWEENNESS_CENTRALITY,
            CONNECTIVITY,
            EDGE_WEIGHT,
            PAGE_RANK,
            IN_DEGREE,
            OUT_DEGREE,
            INCOMING_WEIGHT,
            OUTGOING_WEIGHT
    };

    @Parameter
    private LUTService lutService;
    private Map<String, URL> luts;
    protected SNTGraph<Object, DefaultWeightedEdge> graph;
    private final int internalCounter = 1;

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

    public int map(SNTGraph<Object, DefaultWeightedEdge> graph, final String measurement, final String lut) {
        ColorTable colorTable = getColorTable(lut);
        this.graph = graph;
        mapToProperty(measurement, colorTable);
        return mappedState;
    }

    public int map(SNTGraph<Object, DefaultWeightedEdge> graph, final String measurement, final ColorTable colorTable) {
        this.graph = graph;
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
            case CONNECTIVITY:
                mappedState = EDGES;
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
            default:
                throw new IllegalArgumentException("Unknown metric");
        }
    }

    protected void mapToConnectivity(final ColorTable colorTable) {
        BiconnectivityInspector<Object, DefaultWeightedEdge> inspector = new BiconnectivityInspector<>(this.graph);
        Set<Graph<Object, DefaultWeightedEdge>> components = inspector.getConnectedComponents();
        setMinMax(0, components.size());
        int idx = 0;
        for (Graph<Object, DefaultWeightedEdge> comp : components) {
            for (DefaultWeightedEdge edge : comp.edgeSet()) {
                ColorRGB c = getColorRGB(idx);
                this.graph.setEdgeColor(edge, c);
            }
            ++idx;
        }
    }

    protected void mapToEdgeWeight(final ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (DefaultWeightedEdge edge : this.graph.edgeSet()) {
                double w = this.graph.getEdgeWeight(edge);
                if (w > max) {
                    max = w;
                }
                if (w < min) {
                    min = w;
                }
            }
            setMinMax(min, max);
        }
        for (DefaultWeightedEdge edge : this.graph.edgeSet()) {
            ColorRGB c = getColorRGB(this.graph.getEdgeWeight(edge));
            this.graph.setEdgeColor(edge, c);
        }
    }

    protected void mapToBetweennessCentrality(final ColorTable colorTable) {
        BetweennessCentrality<Object, DefaultWeightedEdge> bc = new BetweennessCentrality<>(this.graph, false);
        Map<Object, Double> scores = bc.getScores();
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double s : scores.values()) {
                if (s < min) {min = s;}
                if (s > max) {max = s;}
            }
            setMinMax(min, max);
        }
        for (Map.Entry<Object, Double> entry : scores.entrySet()) {
            ColorRGB c = getColorRGB(entry.getValue());
            this.graph.setVertexColor(entry.getKey(), c);
        }
    }

    protected void mapToPageRank(final ColorTable colorTable) {
        PageRank<Object, DefaultWeightedEdge> pr = new PageRank<>(this.graph);
        Map<Object, Double> scores = pr.getScores();
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double s : scores.values()) {
                if (s < min) {min = s;}
                if (s > max) {max = s;}
            }
            setMinMax(min, max);
        }
        for (Map.Entry<Object, Double> entry : scores.entrySet()) {
            ColorRGB c = getColorRGB(entry.getValue());
            this.graph.setVertexColor(entry.getKey(), c);
        }
    }

    protected void mapToInDegree(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Object vertex : graph.vertexSet()) {
                double d = graph.inDegreeOf(vertex);
                if (d < min) {min = d;}
                if (d > max) {max = d;}
            }
            setMinMax(min, max);
        }
        for (Object vertex : graph.vertexSet()) {
            ColorRGB c = getColorRGB(graph.inDegreeOf(vertex));
            graph.setVertexColor(vertex, c);
        }
    }

    protected void mapToOutDegree(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Object vertex : graph.vertexSet()) {
                double d = graph.outDegreeOf(vertex);
                if (d < min) {min = d;}
                if (d > max) {max = d;}
            }
            setMinMax(min, max);
        }
        for (Object vertex : graph.vertexSet()) {
            ColorRGB c = getColorRGB(graph.outDegreeOf(vertex));
            graph.setVertexColor(vertex, c);
        }
    }

    protected void mapToIncomingWeight(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Object vertex : graph.vertexSet()) {
                double sum = graph.incomingEdgesOf(vertex).stream().mapToDouble(e -> graph.getEdgeWeight(e)).sum();
                if (sum < min) {min = sum;}
                if (sum > max) {max = sum;}
            }
            setMinMax(min, max);
        }
        for (Object vertex : graph.vertexSet()) {
            ColorRGB c = getColorRGB(
                    graph.incomingEdgesOf(vertex).stream().mapToDouble(e -> graph.getEdgeWeight(e)).sum()
            );
            graph.setVertexColor(vertex, c);
        }
    }

    protected void mapToOutgoingWeight(ColorTable colorTable) {
        if (!minMaxSet) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Object vertex : graph.vertexSet()) {
                double sum = graph.outgoingEdgesOf(vertex).stream().mapToDouble(e -> graph.getEdgeWeight(e)).sum();
                if (sum < min) {min = sum;}
                if (sum > max) {max = sum;}
            }
            setMinMax(min, max);
        }
        for (Object vertex : graph.vertexSet()) {
            ColorRGB c = getColorRGB(
                    graph.outgoingEdgesOf(vertex).stream().mapToDouble(e -> graph.getEdgeWeight(e)).sum()
            );
            graph.setVertexColor(vertex, c);
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
