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

package sc.fiji.snt.annotation;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.io.InsectBrainLoader;
import sc.fiji.snt.viewer.OBJMesh;

import java.io.IOException;
import java.util.*;

/**
 * Utility methods for retrieving species, brain, and neuron data from
 * the Insect Brain Database
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public final class InsectBrainUtils {

    private static final String BASE_URL = "https://insectbraindb.org/";

    private InsectBrainUtils() {

    }

    private static String getAllSpeciesURL() {
        @SuppressWarnings("ConstantConditions") HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
        urlBuilder.addPathSegments("api/species/min/");
        return urlBuilder.build().toString();
    }

    private static JSONArray getAllSpeciesJSON() {
        String url = getAllSpeciesURL();
        String resStr = getResponseStr(url);
        if (resStr == null) {
            return null;
        }
        return new JSONArray(resStr);
    }

    public static List<Map<String, Object>> getAllSpecies() {
        JSONArray speciesArray = getAllSpeciesJSON();
        if (speciesArray == null) {
            return null;
        }
        List<Map<String, Object>> speciesMapList = new ArrayList<>();
        for (int i = 0; i < speciesArray.length(); i++) {
            speciesMapList.add(speciesArray.getJSONObject(i).toMap());
        }
        return speciesMapList;
    }


    //FIXME: This is extremely slow!?
	protected static Map<String, List<Integer>> getAllNeuronIDsOrganizedBySpecies() {

		final Map<Integer, String> speciesIDNameMap = new HashMap<>();
		for (final Map<String, Object> species : InsectBrainUtils.getAllSpecies()) {

			if (!(boolean) species.get("searchable"))
				continue;

			final int id = (int) species.get("id");
			final String name = (String) species.get("scientific_name");
			speciesIDNameMap.put(id, name);
		}

		final List<Integer> allIds = getAllNeuronIDs();
		if (allIds == null) {
			return null;
		}

		final Map<String, List<Integer>> speciesIDsMap = new HashMap<>();
		for (final int id : allIds) {
			final InsectBrainLoader loader = new InsectBrainLoader(id);
			final InsectBrainLoader.NeuronInfo info = loader.getNeuronInfo();
			if (info == null) {
				continue;
			}
			final String speciesName = speciesIDNameMap.get(info.getSpeciesID());
			if (speciesName != null) {
				if (speciesIDsMap.get(speciesName) == null)
					speciesIDsMap.put(speciesName, new ArrayList<>());
				speciesIDsMap.get(speciesName).add(id);
			}
		}

		return speciesIDsMap;
	}
 
    public static List<Integer> getAllNeuronIDs() {
        @SuppressWarnings("ConstantConditions") HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
        urlBuilder.addPathSegments("api/neurons/base/");
        urlBuilder.addQueryParameter("format", "json");
        String neuronsUrl = urlBuilder.build().toString();
        String resStr = getResponseStr(neuronsUrl);
        if (resStr == null) {
            return null;
        }
        JSONArray json = new JSONArray(resStr);
        List<Integer> idList = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject neuron = json.getJSONObject(i);
            idList.add(neuron.getInt("id"));
        }
        return idList;
    }

    public static List<Integer> getSpeciesNeuronIDs(final int speciesId) {
        List<Integer> allIds = getAllNeuronIDs();
        if (allIds == null) {
            return null;
        }
        List<Integer> speciesIds = new ArrayList<>();
        for (int id : allIds) {
            InsectBrainLoader loader = new InsectBrainLoader(id);
            InsectBrainLoader.NeuronInfo info = loader.getNeuronInfo();
            if (info == null) {
                continue;
            }
            if (info.getSpeciesID() == speciesId) {
                speciesIds.add(id);
            }
        }
        return speciesIds;
    }

    private static String getBrainURL(final int speciesId) {
        @SuppressWarnings("ConstantConditions") HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
        urlBuilder.addPathSegments("archive/species/most_current_permitted/");
        urlBuilder.addQueryParameter("species_id", String.valueOf(speciesId));
        return urlBuilder.build().toString();
    }

    public static JSONObject getBrainJSON(final int speciesId) {
        String resStr = getResponseStr(getBrainURL(speciesId));
        if (resStr == null) {
            return null;
        }
        return new JSONObject(resStr);
    }

    private static JSONArray getBrainViewerFiles(final int speciesId, final String sex) {
        JSONObject brainObj = getBrainJSON(speciesId);
        if (brainObj == null) {
            return null;
        }
        JSONArray reconstructions = brainObj.optJSONArray("reconstructions");
        if (reconstructions == null || reconstructions.isEmpty()) {
            return null;
        }
        JSONArray viewerFiles = null;
        for (int i = 0; i < reconstructions.length(); i++) {
            JSONObject json = reconstructions.getJSONObject(i);
            String jsonSex = json.getString("sex");
            if (sex.toLowerCase().equals(jsonSex.toLowerCase())) {
                viewerFiles = json.getJSONArray("viewer_files");
            }
        }
        return viewerFiles;
    }

    public static List<InsectBrainCompartment> getBrainCompartments(final int speciesId, final String sex) {
        /* TODO: Establish parent-child relationships, either here or elsewhere.
            Decide how to better handle compartments with viewer files but no structure information */
        JSONArray viewerFiles = getBrainViewerFiles(speciesId, sex);
        if (viewerFiles == null) {
            return null;
        }
        List<InsectBrainCompartment> compartmentList = new ArrayList<>();
        for (int i = 0; i < viewerFiles.length(); i++) {
            JSONObject fileJson = viewerFiles.getJSONObject(i);
            JSONObject pFile = fileJson.getJSONObject("p_file");
            String objPath = pFile.getString("path");
            JSONArray structures = fileJson.optJSONArray("structures");
            if (structures == null || structures.isEmpty()) {
                InsectBrainCompartment compartment = new InsectBrainCompartment();
                compartment.setObjPath(objPath);
                compartment.setObjColor("#D3D3D3");
                compartment.setName("Mesh");
                compartment.setUUID(UUID.fromString(pFile.getString("uuid")));
                compartmentList.add(compartment);
                continue;
            }
            for (int j = 0; j < structures.length(); j++) {
                InsectBrainCompartment compartment = new InsectBrainCompartment();
                compartment.setObjPath(objPath);
                JSONObject structure = structures.getJSONObject(j);
                String hemisphere = structure.optString("hemisphere", "null");
                compartment.setHemisphere(hemisphere);
                String objColor = structure.getJSONObject("structure").getString("color");
                compartment.setObjColor(objColor);
                String objName = structure.getJSONObject("structure").getString("name");
                compartment.setName(objName);
                String objAcronym = structure.getJSONObject("structure").getString("abbreviation");
                compartment.setAcronym(objAcronym);
                int structureId = structure.getJSONObject("structure").getInt("id");
                compartment.setID(structureId);
                UUID structureUUID = UUID.fromString(structure.getJSONObject("structure").getString("uuid"));
                compartment.setUUID(structureUUID);
                String structureType = structure.getJSONObject("structure").getString("type");
                compartment.setType(structureType);
                compartmentList.add(compartment);
            }
        }
        return compartmentList;
    }

    public static List<OBJMesh> getBrainMeshes(final int speciesId, final String sex){
        List<InsectBrainCompartment> compartmentList = getBrainCompartments(speciesId, sex);
        if (compartmentList == null || compartmentList.isEmpty()) {
            return null;
        }
        List<OBJMesh> meshList = new ArrayList<>();
        for (InsectBrainCompartment compartment : compartmentList) {
            OBJMesh mesh = compartment.getMesh();
            if (mesh != null) {
                meshList.add(mesh);
            }
        }
        return meshList;
    }

    private static Response getResponse(final String url) throws IOException {
        final OkHttpClient client = new OkHttpClient();
        final Request request = new Request.Builder() //
                .url(url)
                .build();
        return client.newCall(request).execute();
    }

    private static String getResponseStr(final String url) {
        try (Response response = getResponse(url)) {
            if (!response.isSuccessful()) {
                SNTUtils.log("Unsuccessful response from url: " + url
                        + "\nResponse: " + response);
                return null;
            }
            return response.body().string();
        } catch (final IOException e) {
            SNTUtils.error("Invalid response from url " + url, e);
        }
        return null;
    }

}
