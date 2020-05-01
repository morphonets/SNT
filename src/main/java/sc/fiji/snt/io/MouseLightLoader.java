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

package sc.fiji.snt.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.scijava.util.ColorRGB;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.annotation.AllenCompartment;

/**
 * Methods for retrieving reconstructions from MouseLight's online database at
 * <a href=
 * "https://ml-neuronbrowser.janelia.org/">ml-neuronbrowser.janelia.org</a> *
 *
 * @author Tiago Ferreira
 */
public class MouseLightLoader {

	/** The Constant AXON. */
	public static final String AXON = MouseLightQuerier.AXON;

	/** The Constant DENDRITE. */
	public static final String DENDRITE = MouseLightQuerier.DENDRITE;

	/** The Constant SOMA. */
	public static final String SOMA = MouseLightQuerier.SOMA;

	private static final String JSON_URL = "https://ml-neuronbrowser.janelia.org/json";
	private static final String SWC_URL = "https://ml-neuronbrowser.janelia.org/swc";
	private static final int MIN_CHARS_IN_VALID_RESPONSE_BODY = 150;
	private final static MediaType MEDIA_TYPE = MediaType.parse("application/json");

	private final String id;
	private JSONObject jsonData;

	/**
	 * Instantiates a new loader.
	 *
	 * @param id the neuron id (e.g., "AA0001"). Note that DOIs are not allowed
	 */
	public MouseLightLoader(final String id) {
		this.id = id;
	}

	private Response getResponse(final String url) throws IOException {
		final OkHttpClient client = new OkHttpClient();
		final RequestBody body = RequestBody.create("{\"ids\": [\"" + id + "\"]}", MEDIA_TYPE);
		final Request request = new Request.Builder() //
				.url(url) //
				.post(body).addHeader("Content-Type", "application/json") //
				.addHeader("cache-control", "no-cache") //
				.build(); //
		return client.newCall(request).execute();
	}

	private JSONObject getJSON(final String url) {
		try {
			final Response response = getResponse(url);
			final String responseBody = response.body().string();
			if (responseBody.length() < MIN_CHARS_IN_VALID_RESPONSE_BODY) {
				return null;
			}
			final JSONObject json = new JSONObject(responseBody);
			if (json != null && url.equals(JSON_URL)
					&& json.getJSONObject("contents").getJSONArray("neurons").isEmpty())
				return null;
			return json;
		} catch (final IOException e) {
			SNTUtils.error("Failed to retrieve id " + id, e);
		}
		return null;
	}

	public String getID() {
		return id;
	}

	/**
	 * Extracts the nodes (single-point soma, axonal and dendritic arbor) of the
	 * loaded neuron.
	 *
	 * @return the list of nodes of the neuron as {@link SWCPoint}s.
	 */
	public TreeSet<SWCPoint> getNodes() {
		return getNodes("all");
	}

	/**
	 * Script-friendly method to extract the nodes of a cellular compartment.
	 *
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive). All
	 *                    nodes are retrieved if {@code compartment} is not
	 *                    recognized
	 * @return the set of nodes of the neuron as {@link SWCPoint}s.
	 */
	public TreeSet<SWCPoint> getNodes(final String compartment) {
		final String normCompartment = (compartment == null) ? "" : compartment.toLowerCase();
		jsonData = getJSON();
		if (jsonData == null) return null;
		final JSONObject neuron = jsonData.getJSONObject("contents").getJSONArray("neurons").optJSONObject(0);
		final TreeSet<SWCPoint> nodes = extractNodesFromJSONObject(normCompartment, neuron);
		return nodes;
	}

	public SWCPoint getSomaLocation() {
		return getNodes(SOMA).first();
	}

	public AllenCompartment getSomaCompartment() {
		return (AllenCompartment) getSomaLocation().getAnnotation();
	}

	/**
	 * Extracts reconstruction(s) from a JSON file.
	 *
	 * @param jsonFile    the JSON file to be parsed
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive). All
	 *                    nodes are retrieved if {@code compartment} is not
	 *                    recognized
	 * @return the map containing the reconstruction nodes as {@link Tree}s
	 * @throws FileNotFoundException if file could not be retrieved
	 * @see #extractNodesFromJSONObject(String, JSONObject)
	 */
	public static Map<String, Tree> extractTrees(final File jsonFile, final String compartment) throws FileNotFoundException {
		final Map<String, TreeSet<SWCPoint>> nodesMap = extractNodes(jsonFile, compartment);
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		return pafm.importNeurons(nodesMap, null, null);
	}

	public static Map<String, Tree> extractTrees(final InputStream stream, final String compartment) {
		final Map<String, TreeSet<SWCPoint>> nodesMap = extractNodes(stream, compartment);
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		return pafm.importNeurons(nodesMap, null, null);
	}

	/**
	 * Extracts reconstruction(s) from a JSON file.
	 *
	 * @param jsonFile    the JSON file to be parsed
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive). All
	 *                    nodes are retrieved if {@code compartment} is not
	 *                    recognized
	 * @return the map containing the reconstruction nodes as {@link SWCPoint}s 
	 * @throws JSONException if file is malformed
	 * @throws FileNotFoundException if file could not be retrieved
	 * @see #extractTrees(File, String)
	 */
	public static Map<String, TreeSet<SWCPoint>> extractNodes(final File jsonFile, final String compartment) throws JSONException, FileNotFoundException {
		final JSONTokener tokener = new JSONTokener(new FileInputStream(jsonFile));
		return extractNodes(tokener, compartment);
	}

	public static Map<String, TreeSet<SWCPoint>> extractNodes(final InputStream stream, final String compartment) throws JSONException {
		final JSONTokener tokener = new JSONTokener(stream);
		return extractNodes(tokener, compartment);
	}

	private static Map<String, TreeSet<SWCPoint>> extractNodes(final JSONTokener tokener, final String compartment) throws JSONException {
		final JSONObject json = new JSONObject(tokener);
		final String normCompartment = (compartment == null) ? "" : compartment.toLowerCase();
		JSONArray neuronArray;
		// There are 3 flavors of MLJSON files. We'll have to try all!
		try {
			neuronArray = json.getJSONArray("neurons");
		} catch (final JSONException ignored1) {
			try {
				neuronArray = json.getJSONObject("contents").getJSONArray("neurons");
			} catch (final JSONException ignored2) {
				return extractNodes(json.getJSONObject("neuron"), normCompartment); // will throw JSONException if object not found
			}
		}
		if (neuronArray == null) return null;
		final Map<String, TreeSet<SWCPoint>> map = new HashMap<>();
		for (int i = 0; i < neuronArray.length(); i++) {
			final JSONObject neuron = neuronArray.optJSONObject(i);
			final String identifier = neuron.optString("idString", "Neuron "+ i);
			map.put(identifier, extractNodesFromJSONObject(normCompartment, neuron));
		}
		return map;
	}

	private static Map<String, TreeSet<SWCPoint>> extractNodes(final JSONObject neuron, final String compartment) {
		final String normCompartment = (compartment == null) ? "" : compartment.toLowerCase();
		final Map<String, TreeSet<SWCPoint>> map = new HashMap<>();
		final String identifier = neuron.optString("idString", "Neuron");
		map.put(identifier, extractNodesFromJSONObject(normCompartment, neuron));
		return map;
	}

	private static TreeSet<SWCPoint> extractNodesFromJSONObject(final String normCompartment, final JSONObject neuron) {
		if (neuron == null) return null;
		final TreeSet<SWCPoint> nodes = new TreeSet<>();
		switch (normCompartment) {
		case SOMA:
		case "cell body":
			// single point soma
			final JSONObject sNode = neuron.getJSONObject("soma");
			nodes.add(jsonObjectToSWCPoint(sNode, Path.SWC_SOMA));
			break;
		case DENDRITE:
		case "dendrites":
			final JSONArray dNodeList = neuron.getJSONArray(DENDRITE);
			for (int n = 0; n < dNodeList.length(); n++) {
				nodes.add(jsonObjectToSWCPoint(dNodeList.getJSONObject(n), Path.SWC_DENDRITE));
			}
			break;
		case AXON:
		case "axons":
			final JSONArray aNodeList = neuron.getJSONArray(AXON);
			for (int n = 0; n < aNodeList.length(); n++) {
				nodes.add(jsonObjectToSWCPoint(aNodeList.getJSONObject(n), Path.SWC_AXON));
			}
			break;
		default:
			int sn = 1;
			final JSONObject somaNode = neuron.getJSONObject("soma");
			nodes.add(jsonObjectToSWCPoint(somaNode, Path.SWC_SOMA));
			sn++;
			final JSONArray dendriteList = neuron.getJSONArray(DENDRITE);
			for (int n = 1; n < dendriteList.length(); n++) {
				final SWCPoint node = jsonObjectToSWCPoint(dendriteList.getJSONObject(n), Path.SWC_DENDRITE);
				node.id = sn++;
				nodes.add(node);
			}
			final JSONArray axonList = neuron.getJSONArray(AXON);
			final int parentOffset = nodes.size() - 1;
			for (int n = 1; n < axonList.length(); n++) {
				final SWCPoint node = jsonObjectToSWCPoint(axonList.getJSONObject(n), Path.SWC_AXON);
				if (n > 1) node.parent += parentOffset;
				node.id = sn++;
				nodes.add(node);
			}
			break;
		}
		return nodes;
	}

	private static SWCPoint jsonObjectToSWCPoint(final JSONObject node, final int swcType) {
		final int sn = node.optInt("sampleNumber", 1);
		final double x = node.getDouble("x");
		final double y = node.getDouble("y");
		final double z = node.getDouble("z");
		final double radius = node.optDouble("radius", 1);
		final int parent = node.optInt("parentNumber", -1);
		final SWCPoint point = new SWCPoint(sn, swcType, x, y, z, radius, parent)  {
			@Override
			public String toString() {
				return String.valueOf(id);
			}
		};
		final int allenId = node.optInt("allenId", -1);
		point.setAnnotation((allenId==-1)?null:new AllenCompartment(allenId));
		return point;
	}

	/**
	 * Gets all the data associated with this reconstruction as a JSON object.
	 * 
	 * @return the JSON data (null if data could not be retrieved).
	 */
	public JSONObject getJSON() {
		if (jsonData == null) jsonData = getJSON(JSON_URL);
		return jsonData;
	}

	public boolean save(final String path) {
		return save(new File(path));
	}

	/**
	 * Convenience method to save JSON data.
	 *
	 * @param file the output directory or the output file
	 * @return true, if successful
	 * @see #saveAsJSON(String)
	 */
	public boolean save(final File file) {
		if (file == null) throw new IllegalArgumentException("Invalid file");
		File jsonFile = (file.isDirectory()) ? new File(file, id + ".json") : file;
		try {
			FileWriter writer = new FileWriter(jsonFile);
			writer.write(getJSON().getJSONObject("contents").toString());
			writer.close();
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Checks whether the neuron to be loaded was found in the database.
	 *
	 * @return true, if the neuron id specified in the constructor was found in the
	 *         database
	 */
	public boolean idExists() {
		return getJSON() != null;
	}

	/**
	 * Gets all the data associated with this reconstruction in the SWC format.
	 * 
	 * @return the SWC data (null if data could not be retrieved).
	 */
	public String getSWC() {
		final JSONObject json = getJSON(SWC_URL);
		if (json == null) return null;
		final String jsonContents = json.get("contents").toString();
		final byte[] decodedContents = Base64.getDecoder().decode(jsonContents);
		return new String(decodedContents, StandardCharsets.UTF_8);
	}

	/**
	 * Convenience method to save SWC data to a local directory.
	 *
	 * @param outputDirectory the output directory
	 * @return true, if successful
	 * @throws IOException if an I/O exception occurred during saving
	 */
	public boolean saveAsSWC(final String outputDirectory) throws IOException {
		return saveJSONContents(getSWC(), outputDirectory, id + ".swc");
	}

	/**
	 * Convenience method to save JSON data to a local directory.
	 *
	 * @param outputDirectory the output directory
	 * @return true, if successful
	 * @throws IOException if an I/O exception occurred during saving
	 */
	public boolean saveAsJSON(final String outputDirectory) throws IOException {
		final String jsonContents = getJSON().getJSONObject("contents").toString();
		return saveJSONContents(jsonContents, outputDirectory, id + ".json");
	}

	private boolean saveJSONContents(final String jsonContents, final String dirPath, final String filename)
			throws IOException {
		if (jsonContents == null)
			return false;
		final File dir = new File(dirPath);
		if (!dir.exists() && !dir.mkdirs() || !dir.isDirectory()) {
			return false;
		}
		final File file = new File(dir, filename);
		final PrintWriter out = new PrintWriter(file);
		out.print(jsonContents);
		out.close();
		return true;
	}

	/**
	 * Extracts a cell compartment as a collection of Paths.
	 *
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive)
	 * @param color the color to be applied to the Tree. Null not expected.
	 * @return the compartment as a {@link Tree}, or null if data could not be
	 *         retrieved
	 * @throws IllegalArgumentException if compartment is not recognized or
	 *           retrieval of data for this neuron is not possible
	 */
	public Tree getTree(final String compartment, final ColorRGB color)
		throws IllegalArgumentException
	{
		if (compartment == null || compartment.trim().isEmpty())
			throw new IllegalArgumentException("Invalid compartment" + compartment);
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		final Map<String, TreeSet<SWCPoint>> inMap = new HashMap<>();
		inMap.put(id, getNodes(compartment));
		final Map<String, Tree> outMap = pafm.importNeurons(inMap, color, "um");
		final Tree tree = outMap.get(id);
		if (!compartment.startsWith("all") && !compartment.startsWith("full"))
			tree.setLabel(""+ id + " ("+ compartment + ")");
		else
			tree.setLabel(""+ id);
		return tree;
	}

	/**
	 * Script-friendly method to extract a compartment as a collection of Paths.
	 *
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive)
	 * @return the compartment as a {@link Tree}, or null if data could not be
	 *         retrieved
	 * @throws IllegalArgumentException if compartment is not recognized or
	 *           retrieval of data for this neuron is not possible
	 */
	public Tree getTree(final String compartment) throws IllegalArgumentException
	{
		return getTree(compartment, null);
	}

	/**
	 * Script-friendly method to extract the entire neuron as a collection of Paths.
	 *
	 * @return the neuron as a {@link Tree}, or null if data could not be retrieved
	 * @throws IllegalArgumentException if retrieval of data for this neuron is not
	 *                                  possible
	 */
	public Tree getTree() throws IllegalArgumentException
	{
		return getTree("all", null);
	}

	/**
	 * Checks whether a connection to the MouseLight database can be established.
	 *
	 * @return true, if an HHTP connection could be established
	 */
	public static boolean isDatabaseAvailable() {
		return MouseLightQuerier.isDatabaseAvailable();
	}

	/**
	 * Gets the number of cells publicly available in the MouseLight database.
	 *
	 * @return the number of available cells, or -1 if the database could not be
	 *         reached.
	 */
	public static int getNeuronCount() {
		int count = -1;
		final OkHttpClient client = new OkHttpClient();
		final RequestBody body = RequestBody.create("{\"query\":\"{systemSettings{neuronCount}}\"}", MEDIA_TYPE);
		final Request request = new Request.Builder() //
				.url("https://ml-neuronbrowser.janelia.org/graphql") //
				.post(body) //
				.addHeader("Content-Type", "application/json") //
				.addHeader("cache-control", "no-cache") //
				.build();
		try {
			final Response response = client.newCall(request).execute();
			final JSONObject json = new JSONObject(response.body().string());
			count = json.getJSONObject("data").getJSONObject("systemSettings").getInt("neuronCount");
		} catch (IOException | JSONException ignored) {
			// do nothing
		}
		return count;
	}

	/**
	 * Gets the loaders for all the cells publicly available in the MouseLight database.
	 *
	 * @return the list of loaders or an empty list if the ML database could not be reached.
	 */
	public static List<MouseLightLoader> getAllLoaders() {
		final List<MouseLightLoader> list = new ArrayList<>();
		IntStream.rangeClosed(0, getNeuronCount()).forEach( neuron -> {
			final String id = "AA" + new DecimalFormat("0000").format(neuron);
			final MouseLightLoader loader = new MouseLightLoader(id);
			if (loader.idExists()) list.add(loader);
		});
		return list;
	}

	/**
	 * Convenience method to obtain a consecutive range of cell IDs from a range of
	 * indices. E.g., {@code getRangeOfIDs(1, getNeuronCount())} would return the
	 * list of IDs for all the cells in the MouseLight database.
	 *
	 * @param first the first index in the range (inclusive, 1-based)
	 * @param last  the last index in the range (inclusive)
	 * @return the list of cell IDs or an empty list if database could not be
	 *         reached.
	 */
	public static List<String> getRangeOfIDs(final int first, final int last) {
		final List<String> list = new ArrayList<>();
		final int maxN = getNeuronCount();
		if (maxN == -1) return list;
		if (maxN > 9999) {
			throw new IllegalArgumentException(
					"This method needs to be updated to accomodate the large # of neurons in database. "
					+ "Please contact the developers");
		}
		final int adjFirst = Math.max(1, first);
		final int adjLast = Math.min(last, maxN);
		IntStream.rangeClosed(adjFirst, adjLast).forEach( neuron -> {
			final String id = "AA" + new DecimalFormat("0000").format(neuron);
			//if (new MouseLightLoader(id).idExists())
			list.add(id);
		});
		return list;
	}

	/* IDE debug method */
	public static void main(final String... args) throws IOException {
		final String dir = "/home/tferr/Desktop/testjson/";
		final String id = "AA0360";
		System.out.println("# starting");
		final MouseLightLoader loader = new MouseLightLoader(id);
		System.out.println(loader.idExists());
		System.out.println(MouseLightLoader.isDatabaseAvailable());
		try (PrintWriter out = new PrintWriter(dir + id + "manual.swc")) {
			final StringReader reader = SWCPoint.collectionAsReader(loader.getNodes());
			try (BufferedReader br = new BufferedReader(reader)) {
				br.lines().forEach(out::println);
			} catch (final IOException e) {
				e.printStackTrace();
			}
			out.println("# End of Tree ");
		} catch (final FileNotFoundException | IllegalArgumentException e) {
			e.printStackTrace();
		}
		System.out.println("# All done");
		System.out.println("Saving bogus id: " + new MouseLightLoader("AA01").saveAsSWC(dir));
		System.out.println("Saving valid id: " + new MouseLightLoader("AA0360").saveAsSWC(dir));
		System.out.println("# done");
	}

}
