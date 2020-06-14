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

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.IntStream;
import java.util.TreeSet;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;

/**
 * Importer for retrieving reconstructions from MouseLight's online database at
 * <a href=
 * "https://ml-neuronbrowser.janelia.org/">ml-neuronbrowser.janelia.org</a>
 *
 * @author Tiago Ferreira
 */
public class MouseLightQuerier {

	/** The Constant AXON. */
	public static final String AXON = "axon";

	/** The Constant DENDRITE. */
	public static final String DENDRITE = "dendrite";

	/** The Constant SOMA. */
	public static final String SOMA = "soma";

	private final static MediaType MEDIA_TYPE = MediaType.parse("application/json");

	private final static String TRACINGS_URL =
		"https://ml-neuronbrowser.janelia.org/tracings";
	private final static String GRAPHQL_URL =
		"https://ml-neuronbrowser.janelia.org/graphql";
	private final static String GRAPHQL_BODY = "{\n" + //
		"    \"query\": \"query QueryData($filters: [FilterInput!]) {\\n  queryData(filters: $filters) {\\n    totalCount\\n    queryTime\\n    nonce\\n    error {\\n      name\\n      message\\n      __typename\\n    }\\n    neurons {\\n      id\\n      idString\\n      brainArea {\\n        id\\n        acronym\\n        __typename\\n      }\\n      tracings {\\n        id\\n        tracingStructure {\\n          id\\n          name\\n          value\\n          __typename\\n        }\\n        soma {\\n          id\\n          x\\n          y\\n          z\\n          radius\\n          parentNumber\\n          sampleNumber\\n          brainAreaId\\n          structureIdentifierId\\n          __typename\\n        }\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\",\n" +
		"    \"variables\": {\n" + //
		"        \"filters\": [\n" + //
		"            {\n" + //
		"            	\"tracingIdsOrDOIs\": [\"%s\"],\n" + //
		"                \"tracingIdsOrDOIsExactMatch\": true,\n" + //
		"                \"tracingStructureIds\": [\"\"],\n" + //
		"                \"nodeStructureIds\": [\"\"],\n" + //
		"                \"operatorId\": \"\",\n" + //
		"                \"amount\": null,\n" + //
		"                \"brainAreaIds\": [\"\"],\n" + //
		"                \"arbCenter\": {\n" + //
		"                    \"x\": null,\n" + //
		"                    \"y\": null,\n" + //
		"                    \"z\": null\n" + //
		"                },\n" + //
		"                \"arbSize\": null,\n" + //
		"                \"invert\": false,\n" + //
		"                \"composition\": null,\n" + //
		"                \"nonce\": \"\"\n" + //
		"            }\n" + //
		"        ]\n" + //
		"    },\n" + //
		"    \"operationName\": \"QueryData\"\n" + //
		"}";

	/* Maps tracingStructure name to tracingStructure id */
	private Map<String, UUID> nameMap;
	/* Maps tracingStructure id to its SWC-type flag */
	private Map<UUID, Integer> swcTypeMap;
	/* The 1-point soma of the cell */
	private SWCPoint soma = null;
	/* The public id or DOI of the cell */
	private final String publicID;
	private boolean initialized = false;

	/**
	 * Instantiates a new loader.
	 *
	 * @param id the neuron id (e.g., "AA0001") or DOI (e.g.,
	 *          "10.25378/janelia.5527672") of the neuron to be loaded
	 */
	public MouseLightQuerier(final String id) {
		this.publicID = id;
	}

	/**
	 * Checks whether the neuron to be loaded was found in the database.
	 *
	 * @return true, if the neuron id specified in the constructor was found in
	 *         the database
	 */
	public boolean idExists() {
		if (!initialized) {
			try {
				initialize();
			}
			catch (final JSONException | IllegalArgumentException ignored) {
				return false;
			}
		}
		return nameMap != null && !nameMap.isEmpty();
	}

	private void initialize() {
		initialize(true);
	}

	private void initialize(final boolean assembleSwcTypeMap) {
		try {
			final OkHttpClient client = new OkHttpClient();
			//TODO: Update for okhttp4: final RequestBody body = RequestBody.create(String.format(GRAPHQL_BODY, publicID), MEDIA_TYPE);
			//see https://github.com/morphonets/SNT/issues/26
			final RequestBody body = RequestBody.create(MEDIA_TYPE, String.format(GRAPHQL_BODY, publicID));
			final Request request = new Request.Builder().url(GRAPHQL_URL).post(body)
				.addHeader("Content-Type", "application/json").addHeader(
					"Cache-Control", "no-cache").build();
			final Response response = client.newCall(request).execute();
			final String resStr = response.body().string();
			response.close();
			// Parse response
			final JSONObject json = new JSONObject(resStr);
			final JSONArray neuronsArray = json.getJSONObject("data").getJSONObject(
				"queryData").getJSONArray("neurons");
			if (neuronsArray == null || neuronsArray.length() == 0)
				throw new IllegalArgumentException(
					"No tracing structures available for " + publicID);

			nameMap = new HashMap<>();
			for (int n = 0; n < neuronsArray.length(); n++) {
				final JSONArray tracingsArray = neuronsArray.getJSONObject(n)
					.getJSONArray("tracings");

				for (int t = 0; t < tracingsArray.length(); t++) {
					final JSONObject compartment = tracingsArray.getJSONObject(t);
					if (soma == null) {
						final JSONObject jsonSoma = compartment.getJSONObject(SOMA);
						final double sX = jsonSoma.getDouble("x");
						final double sY = jsonSoma.getDouble("y");
						final double sZ = jsonSoma.getDouble("z");
						final double sRadius = jsonSoma.getDouble("radius");
						final int parent = jsonSoma.getInt("parentNumber"); // always -1
						soma = new SWCPoint(0, Path.SWC_SOMA, sX, sY, sZ, sRadius, parent);
						final String areaId = jsonSoma.optString("brainAreaId");
						if (!areaId.isEmpty()) soma.setAnnotation(new AllenCompartment(UUID.fromString(areaId)));
					}
					final JSONObject tracingStructure = compartment.getJSONObject(
						"tracingStructure");
					final String name = tracingStructure.getString("name");
					final UUID compartmentID = UUID.fromString(compartment.getString(
						"id"));
					nameMap.put(name, compartmentID);
				}
			}
			if (assembleSwcTypeMap) assembleSwcTypeMap();
		}
		catch (final IOException | JSONException exc) {
			SNTUtils.error("Failed to initialize loader", exc);
			initialized = false;
		}
		initialized = true;
		if (assembleSwcTypeMap && SNTUtils.isDebugMode()) {
			SNTUtils.log("Retrieving compartment UUIDs for ML neuron " + publicID);
			if (nameMap == null) {
				SNTUtils.log("Failed... " + publicID + " does not exist?");
				return;
			}
			for (final Entry<String, UUID> entry : nameMap.entrySet()) {
				SNTUtils.log(entry.toString());
			}
		}
	}

	private void assembleSwcTypeMap() throws IllegalArgumentException {
		if (nameMap == null) throw new IllegalArgumentException(
			"nameMap undefined");
		swcTypeMap = new HashMap<>();
		for (final Entry<String, UUID> entry : nameMap.entrySet()) {
			final String key = entry.getKey();
			if (key == null) throw new IllegalArgumentException(
				"structureIdentifierId UUIDs unset");
			switch (key) {
				case AXON:
					swcTypeMap.put(entry.getValue(), Path.SWC_AXON);
					break;
				case DENDRITE:
					swcTypeMap.put(entry.getValue(), Path.SWC_DENDRITE);
					break;
				default:
					swcTypeMap.put(entry.getValue(), Path.SWC_UNDEFINED);
					break;
			}
		}
	}

	/**
	 * Checks whether a connection to the MouseLight database can be established.
	 *
	 * @return true, if an HHTP connection could be established
	 */
	public static boolean isDatabaseAvailable() {
		boolean success;
		Response response = null;
		try {
			final OkHttpClient client = new OkHttpClient();
			final Request request = new Request.Builder().url(TRACINGS_URL).build();
			response = client.newCall(request).execute();
			success = response.isSuccessful();
		}
		catch (final IOException ignored) {
			success = false;
		}
		finally {
			if (response != null) response.close();
		}
		return success;
	}

	private String normalizedStructure(final String structure) {
		switch (structure.toLowerCase()) {
			case "dendrite":
			case "dendrites":
				return DENDRITE;
			case "axon":
			case "axons":
				return AXON;
			default:
				throw new IllegalArgumentException("Unrecognized compartment");
		}

	}

	/**
	 * Gets a traced compartment of the loaded cell.
	 *
	 * @param structure either {@link #AXON} or {@link #DENDRITE}
	 * @return the specified compartment as a JSON object
	 * @throws IllegalArgumentException if retrieval of data for this neuron is
	 *           not possible or {@code structure} was not recognized
	 */
	public JSONObject getCompartment(final String structure)
		throws IllegalArgumentException
	{
		if (!initialized) initialize();
		final UUID structureID = nameMap.get(normalizedStructure(structure));
		if (structureID == null) throw new IllegalArgumentException(
			"Structure name not recognized: " + structure);

		final OkHttpClient client = new OkHttpClient();
		//TODO: Update for okhttp4: final RequestBody body = RequestBody.create("{\n\"ids\": [\n\"" + structureID + "\"\n]\n}", MEDIA_TYPE);
		//see https://github.com/morphonets/SNT/issues/26
		final RequestBody body = RequestBody.create(MEDIA_TYPE, "{\n\"ids\": [\n\"" + structureID + "\"\n]\n}");
		try {
			final Request request = new Request.Builder().url(TRACINGS_URL).post(body)
				.addHeader("Content-Type", "application/json").addHeader(
					"Cache-Control", "no-cache").build();
			final Response response = client.newCall(request).execute();
			final String resStr = response.body().string();
			response.close();
			return new JSONObject(resStr);
		}
		catch (final IOException | JSONException exc) {
			exc.printStackTrace();
			return null;
		}
	}

	/**
	 * Extracts the nodes (single-point soma, axonal and dendritic arbor) of the
	 * loaded neuron.
	 *
	 * @return the list of nodes of the neuron as {@link SWCPoint}s. Note that the
	 *         first point in the set (the soma) has an SWC sample number of 0.
	 * @throws IllegalArgumentException if retrieval of data for this neuron is
	 *           not possible
	 */
	public TreeSet<SWCPoint> getNodes() throws IllegalArgumentException {
		if (!initialized) initialize();
		final TreeSet<SWCPoint> points = new TreeSet<>();
		if (soma != null) points.add(soma);
		int idOffset = 0;
		for (final Entry<String, UUID> entry : nameMap.entrySet()) {
			final JSONObject c = getCompartment(entry.getKey());
			this.assignNodes(c, points, idOffset);
			idOffset += points.last().id;
		}
		return points;
	}

	/**
	 * Extracts the nodes of the axonal arbor of loaded neuron.
	 *
	 * @return the list of nodes of the axonal arbor as {@link SWCPoint}s
	 * @throws IllegalArgumentException if retrieval of data for this neuron is
	 *           not possible
	 */
	public TreeSet<SWCPoint> getAxonNodes() throws IllegalArgumentException {
		return getNodesInternal(AXON);
	}

	/**
	 * Extracts the nodes of the dendritic arbor of loaded neuron.
	 *
	 * @return the list of nodes of the dendritic arbor as {@link SWCPoint}s
	 * @throws IllegalArgumentException if retrieval of data for this neuron is
	 *           not possible
	 */
	public TreeSet<SWCPoint> getDendriteNodes() throws IllegalArgumentException {
		return getNodesInternal(DENDRITE);
	}

	/**
	 * Script-friendly method to extract the nodes of a compartment.
	 *
	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive)
	 * @return the list of nodes of the neuron as {@link SWCPoint}s. All nodes are
	 *         retrieved if compartment was not recognized.
	 * @throws IllegalArgumentException if compartment is not recognized or
	 *           retrieval of data for this neuron is not possible
	 */
	public TreeSet<SWCPoint> getNodes(final String compartment)
		throws IllegalArgumentException
	{
		if (compartment == null || compartment.trim().isEmpty())
			throw new IllegalArgumentException("Invalid compartment" + compartment);
		if (!initialized) initialize();
		final String comp = compartment.toLowerCase();
		if (SOMA.equals(comp) || nameMap.containsKey(comp)) return getNodesInternal(
			comp);
		return getNodes();
	}
//
//	/**
//	 * Script-friendly method to extract a compartment as a collection of Paths.
//	 *
//	 * @param compartment 'soma', 'axon', 'dendrite', 'all' (case insensitive)
//	 * @param color the color to be applied to the Tree. Null not expected.
//	 * @return the compartment as a {@link Tree}, or null if data could not be
//	 *         retrieved
//	 * @throws IllegalArgumentException if compartment is not recognized or
//	 *           retrieval of data for this neuron is not possible
//	 */
//	public Tree getTree(final String compartment, final ColorRGB color)
//		throws IllegalArgumentException
//	{
//		if (compartment == null || compartment.trim().isEmpty())
//			throw new IllegalArgumentException("Invalid compartment" + compartment);
//		if (!initialized) initialize();
//		final String comp = compartment.toLowerCase();
//		final PathAndFillManager pafm = new PathAndFillManager();
//		pafm.setHeadless(true);
//		final Map<String, Tree> map = pafm.importMLNeurons(Collections
//			.singletonList(publicID), comp, color);
//		return map.get(publicID);
//	}

	private TreeSet<SWCPoint> getNodesInternal(final String compartment) {
		if (!initialized) initialize();
		final TreeSet<SWCPoint> points = new TreeSet<>();
		if (SOMA.equals(compartment)) {
			points.add(getSoma());
		}
		else {
			assignNodes(getCompartment(compartment), points);
		}
		return points;
	}

	/**
	 * Retrieves the soma (single-point representation) of the loaded neuron.
	 *
	 * @return the soma as {@link SWCPoint}. Note that point has an SWC sample
	 *         number of 0.
	 * @throws IllegalArgumentException if retrieval of data for this neuron is
	 *           not possible
	 */
	public SWCPoint getSomaLocation() throws IllegalArgumentException {
		if (!initialized) initialize();
		return soma;
	}

	/**
	 * @deprecated Use {@link #getSomaLocation()} instead.
	 */
	@Deprecated
	public SWCPoint getSoma() throws IllegalArgumentException {
		return getSomaLocation();
	}

	public AllenCompartment getSomaCompartment() {
		if (!initialized) {
			try {
				initialize(false);
			} catch (final IllegalArgumentException ignored) {
				return null;
			}
		}
		final AllenCompartment comp = (initialized && soma != null) ? (AllenCompartment) soma.getAnnotation(): null;
		return comp;
	}

	private void assignNodes(final JSONObject compartment,
		final TreeSet<SWCPoint> points)
	{
		assignNodes(compartment, points, 0);
	}

	private void assignNodes(final JSONObject compartment,
		final TreeSet<SWCPoint> points, final int idOffset)
	{
		if (compartment == null) throw new IllegalArgumentException(
			"Cannot extract nodes from null compartment");
		if (!initialized) initialize();
		try {
			final JSONArray tracings = compartment.getJSONArray("tracings");
			for (int traceIdx = 0; traceIdx < tracings.length(); traceIdx++) {
				final JSONObject tracing = tracings.getJSONObject(traceIdx);
				final int type = getSWCflag(UUID.fromString(tracing.getString("id")));
				final JSONArray nodesArray = tracing.getJSONArray("nodes");
				for (int nodeIdx = 0; nodeIdx < nodesArray.length(); nodeIdx++) {
					final JSONObject node = (JSONObject) nodesArray.get(nodeIdx);
					final int sn = idOffset + node.getInt("sampleNumber");
					final double x = node.getDouble("x");
					final double y = node.getDouble("y");
					final double z = node.getDouble("z");
					final double radius = node.getDouble("radius");
					int parent = node.getInt("parentNumber");
					if (parent > -1) parent += idOffset;
					final SWCPoint point = new SWCPoint(sn, type, x, y, z, radius, parent);
					point.setAnnotation(new AllenCompartment(UUID.fromString(node.getString("brainAreaId"))));
					points.add(point);
				}
			}

		}
		catch (final JSONException exc) {
			SNTUtils.error("Error while extracting nodes", exc);
		}
	}

	/* Gets the SWC type flag from a JSON 'tracingStructure' UUID */
	private int getSWCflag(final UUID tracingStructureUUID) {
		try {
			return swcTypeMap.get(tracingStructureUUID);
		}
		catch (final NullPointerException nep) {
			return Path.SWC_UNDEFINED;
		}
	}

	/**
	 * Gets the IDs of the cells publicly available in the MouseLight database having
	 * the soma associated with the specified compartment.
	 *
	 * @return the list of cell IDs associated with {@code compartment} or an empty
	 *         list if no cells were found
	 */
	public static List<String> getIDs(final AllenCompartment compartment) {
		final List<String> list = Collections.synchronizedList(new ArrayList<>());
		final ProgressBarBuilder pbb = new ProgressBarBuilder();
		pbb.setStyle(ProgressBarStyle.ASCII);
		pbb.setTaskName("Retrieving IDs");
		ProgressBar.wrap(IntStream.rangeClosed(0, MouseLightLoader.getNeuronCount()).parallel(), pbb).forEach(neuron -> {
			final String id = "AA" + new DecimalFormat("0000").format(neuron);
			final MouseLightQuerier querier = new MouseLightQuerier(id);
			final AllenCompartment sCompartment = querier.getSomaCompartment();
			if (sCompartment != null && (sCompartment.equals(compartment) || sCompartment.isChildOf(compartment))) {
				list.add(id);
			}
		});
		if (!list.isEmpty()) list.sort(String.CASE_INSENSITIVE_ORDER);
		return list;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		System.out.println("# Retrieving neuron");
		getIDs(AllenUtils.getCompartment("Isocortex"));
//		final String id = "10.25378/janelia.5527672"; // 10.25378/janelia.5527672";
//		final MouseLightLoader loader = new MouseLightLoader(id);
//		try (PrintWriter out = new PrintWriter("/home/tferr/Desktop/" + id
//			.replaceAll("/", "-") + ".swc"))
//		{
//			final StringReader reader = SWCPoint.collectionAsReader(loader
//				.getNodes());
//			try (BufferedReader br = new BufferedReader(reader)) {
//				br.lines().forEach(out::println);
//			}
//			catch (final IOException e) {
//				e.printStackTrace();
//			}
//			out.println("# End of Tree ");
//		}
//		catch (final FileNotFoundException | IllegalArgumentException e) {
//			e.printStackTrace();
//		}
	}

}
