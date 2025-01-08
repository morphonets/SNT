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

package sc.fiji.snt.io;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Importer for retrieving reconstructions from MouseLight's online database at
 * <a href=
 * "https://ml-neuronbrowser.janelia.org/">ml-neuronbrowser.janelia.org</a>
 *
 * @author Tiago Ferreira
 */
public class MouseLightQuerier {

	private final static String SOMA_UUID = "6afcafa5-ec7f-4899-8941-3e1f812682ce";
	private final static MediaType MEDIA_TYPE = MediaType.parse("application/json");
	private final static String TRACINGS_URL = "https://ml-neuronbrowser.janelia.org/tracings";
	private final static String GRAPHQL_URL = "https://ml-neuronbrowser.janelia.org/graphql";
	private final static String CCF_25 = "brainAreaIdCcfV25";
	private final static String CCF_30 = "brainAreaIdCcfV30";
	private static String querySpace = CCF_30;

	private MouseLightQuerier() {
		// Do not instantiate private class
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
		} catch (final IOException ignored) {
			success = false;
		} finally {
			if (response != null)
				response.close();
		}
		return success;
	}

	public static List<String> getIDs(final AllenCompartment compartment) {
		if (compartment.getOntologyDepth() == 0) return getAllIDs();
		return getIDs(new BodyBuilder().somaLocationQuery(compartment));
	}
 
	public static List<String> getIDs(final Collection<AllenCompartment> compartments) {
		return getIDs(new BodyBuilder().somaLocationQuery(compartments));
	}

	/**
	 * Returns a list of IDs associated with the specified identifier
	 *
	 * @param idOrDOI the neuron id (e.g., "AA0001") or DOI (e.g.,
	 *                "10.25378/janelia.5527672") of the neuron to be loaded
	 * @param exactMatch If true, only exact matches will be considered
	 */
	public static List<String> getIDs(final String idOrDOI, final boolean exactMatch) {
		return getIDs(new BodyBuilder().idQuery(idOrDOI, exactMatch));
	}

	public static List<String> getIDs(final Collection<String> idsOrDOIs, final boolean exactMatch) {
		return getIDs(new BodyBuilder().idQuery(idsOrDOIs, exactMatch));
	}

	public static List<String> getAllIDs() {
		return getIDs(new BodyBuilder().allIDsQuery());
	}

	static List<JSONObject> getData(final AllenCompartment compartment) {
		if (compartment.getOntologyDepth() == 0) return getAllData();
		return getJSONs(new BodyBuilder().somaLocationQuery(compartment));
	}

	static List<JSONObject> getData(final Collection<AllenCompartment> compartments) {
		return getJSONs(new BodyBuilder().somaLocationQuery(compartments));
	}

	static List<JSONObject> getData(final String idOrDOI, final boolean exactMatch) {
		return getJSONs(new BodyBuilder().idQuery(idOrDOI, exactMatch));
	}

	static List<JSONObject> getData(final Collection<String> idsOrDOIs, final boolean exactMatch) {
		return getJSONs(new BodyBuilder().idQuery(idsOrDOIs, exactMatch));
	}

	static List<JSONObject> getAllData() {
		return getJSONs(new BodyBuilder().allIDsQuery());
	}

	private static JSONObject getJSON(final RequestBody body) {
		try {
			final OkHttpClient client = new OkHttpClient();
			final Request request = new Request.Builder().url(GRAPHQL_URL).post(body)
					.addHeader("Content-Type", "application/json").addHeader("Cache-Control", "no-cache").build();
			final Response response = client.newCall(request).execute();
			final String resStr = response.body().string();
			response.close();
			return new JSONObject(resStr);
		} catch (final IOException | JSONException exc) {
			SNTUtils.error("Failed to initialize query", exc);
		}
		return null;
	}

	private static List<String> getIDs(final RequestBody query) throws JSONException {
		final JSONObject json = getJSON(query);
		if (json == null) return null;
		final JSONArray neuronsArray = json.getJSONObject("data").getJSONObject("queryData").getJSONArray("neurons");
		final ArrayList<String> ids = new ArrayList<>();
		if (neuronsArray.isEmpty()) return ids;
		for (int n = 0; n < neuronsArray.length(); n++) {
			final JSONObject neuron = (JSONObject) neuronsArray.get(n);
			ids.add(neuron.optString("idString"));
		}
		Collections.sort(ids);
		return ids;
	}

	private static List<JSONObject> getJSONs(final RequestBody query) throws JSONException {
		final JSONObject json = getJSON(query);
		if (json == null) return null;
		final JSONArray neuronsArray = json.getJSONObject("data").getJSONObject("queryData").getJSONArray("neurons");
		final ArrayList<JSONObject> jsons = new ArrayList<>();
		if (neuronsArray.isEmpty()) return jsons;
		for (int n = 0; n < neuronsArray.length(); n++) {
			final JSONObject neuron = (JSONObject) neuronsArray.get(n);
			jsons.add(neuron);
		}
		return jsons;
	}

	/**
	 * Sets the version of the Common Coordinate Framework to be used by the Querier.
	 *
	 * @param version Either "3" (the default), or "2.5" (MouseLight legacy)
	 */
	public static void setCCFVersion(final String version) {
		switch (version) {
		case CCF_30:
		case "3":
			querySpace = CCF_30;
			break;
		case CCF_25:
		case "2.5":
			querySpace = CCF_25;
			break;
		default:
			throw new IllegalArgumentException("Unrecognized CCF version " + version);
		}
	}

	private static class BodyBuilder {
		final static String GRAPHQL_BODY = "{\n" + //
				"    \"query\": \"query QueryData($filters: [FilterInput!]) {\\n  queryData(filters: $filters) {\\n    totalCount\\n    queryTime\\n    nonce\\n    error {\\n      name\\n      message\\n      __typename\\n    }\\n    neurons {\\n      id\\n      idString\\n      brainArea {\\n        id\\n        acronym\\n        __typename\\n      }\\n      tracings {\\n        id\\n        tracingStructure {\\n          id\\n          name\\n          value\\n          __typename\\n        }\\n        soma {\\n          id\\n          x\\n          y\\n          z\\n          radius\\n          parentNumber\\n          sampleNumber\\n          "//
				+ querySpace //
				+ "\\n          structureIdentifierId\\n          __typename\\n        }\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\",\n" //
				+ "    \"variables\": {\n" + //
				"        \"filters\": [\n" + //
				"            {\n" + //
				"            	\"tracingIdsOrDOIs\": %s,\n" + //
				"                \"tracingIdsOrDOIsExactMatch\": %s,\n" + //
				"                \"tracingStructureIds\": %s,\n" + //
				"                \"nodeStructureIds\": %s,\n" + //
				"                \"operatorId\": null,\n" + //
				"                \"amount\": null,\n" + //
				"                \"brainAreaIds\": %s,\n" + //
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
		final String EMPTY_ARRAY = "[]";

		private static String quote(final String string) {
			return "\"" + string + "\"";
		}

		private static String asList(final String query) {
			return "[" + quote(query) + "]";
		}

		RequestBody allIDsQuery() {
			final AllenCompartment wholeBrain = AllenUtils.getCompartment("Whole Brain");
			return fullQuery(EMPTY_ARRAY, String.valueOf(false), EMPTY_ARRAY, EMPTY_ARRAY,
					asList(wholeBrain.getUUID().toString()));
		}

		RequestBody somaLocationQuery(final AllenCompartment compartment) {
			return fullQuery(EMPTY_ARRAY, String.valueOf(false), EMPTY_ARRAY, asList(SOMA_UUID),
					asList(compartment.getUUID().toString()));
		}

		RequestBody somaLocationQuery(final Collection<AllenCompartment> compartments) {
			final ArrayList<String> compartmentsID = new ArrayList<>(compartments.size());
			for (final AllenCompartment compartment : compartments) {
				compartmentsID.add(quote(compartment.getUUID().toString()));
			}
			final String compartmentArray = compartmentsID.stream().collect(Collectors.joining(",", "[", "]"));
			return fullQuery(EMPTY_ARRAY, String.valueOf(false), EMPTY_ARRAY, asList(SOMA_UUID), compartmentArray);
		}

		RequestBody idQuery(final Collection<String> idsOrDOIs, final boolean exactMatch) {
			final String idOrDoisArray = idsOrDOIs.stream().map(id -> quote(id))
					.collect(Collectors.joining(",", "[", "]"));
			return fullQuery(idOrDoisArray, String.valueOf(exactMatch), EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY);
		}

		RequestBody idQuery(final String idOrDOI, final boolean exactMatch) {
			return fullQuery(asList(idOrDOI), String.valueOf(exactMatch), EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY);
		}

		RequestBody fullQuery(final String cellIDs, final String exactMatch, final String tracingStructureIds,
				final String nodeStructureIds, final String brainAreaIds) {
			// see https://github.com/morphonets/SNT/issues/26
			return RequestBody.create(String.format(GRAPHQL_BODY, //
					cellIDs, exactMatch, tracingStructureIds, nodeStructureIds, brainAreaIds), MEDIA_TYPE);
		}
	}

	/**
	 * Gets the number of cells publicly available in the MouseLight database.
	 *
	 * @return the number of available cells, or -1 if the database could not be
	 *         reached.
	 */
	public static int getNeuronCount() {
		return MouseLightLoader.getNeuronCount();
	}

	/* IDE debug method */
	public static void main(final String... args) {
		List<String> ids = getIDs(AllenUtils.getCompartment("CA2"));
		System.out.println(ids.get(0).equals("AA0960"));
		System.out.println(ids.get(1).equals("AA0997"));
		ids = getIDs(AllenUtils.getCompartment("SNr"));
		System.out.println(ids.get(0).equals("AA1044"));
		System.out.println(getAllIDs().size() == getNeuronCount());
		//assertTrue("AA0100".equals(getIDs(" AA0100", false).get(0)));
		System.out.println("done");
	}

}
