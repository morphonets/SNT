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

import net.imagej.ImageJ;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import sc.fiji.snt.*;
import sc.fiji.snt.annotation.InsectBrainCompartment;
import sc.fiji.snt.annotation.InsectBrainUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer3D;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Methods for retrieving reconstructions and annotations from the Insect Brain Database at
 * <a href=
 * "https://www.insectbraindb.org/app/">insectbraindb.org</a> *
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class InsectBrainLoader {

    private static final String BASE_URL = "https://insectbraindb.org/";

    private final int id;
    private JSONObject jsonData;
    private NeuronInfo neuronInfo;

    public InsectBrainLoader(final int id) {
        this.id = id;
        this.jsonData = null;
        this.neuronInfo = null;
    }

    /**
     * Checks whether a connection to the Insect Brain Database can be established.
     *
     * @return true, if an HTTP connection could be established
     */
    public static boolean isDatabaseAvailable() {
        boolean success;
        Response response = null;
        try {
            final OkHttpClient client = new OkHttpClient();
            final Request request = new Request.Builder().url(BASE_URL).build();
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

    /**
     * Checks whether the neuron to be loaded was found in the database.
     *
     * @return true, if the neuron id specified in the constructor was found in the
     * database
     */
    public boolean idExists() {
        return getJSON() != null;
    }

    /**
     * Gets the collection of Paths for the specified cell ID
     *
     * @return the data for the specified cell as a {@link Tree}, or null if data
     * could not be retrieved
     */
    public Tree getTree() {
        String url = getSWCUrl();
        if (url == null) {
            return null;
        }
		final PathAndFillManager pafm = new PathAndFillManager(1, 1, 1, GuiUtils.micrometer());
        pafm.setHeadless(true);
        neuronInfo = getNeuronInfo();
        Tree tree = null;
        if (pafm.importSWC(String.valueOf(this.id), url)) {
            tree = new Tree(pafm.getPaths());
            tree.setType(Path.SWC_UNDEFINED);
            tree.setLabel(neuronInfo.getFullName());
            tree.getProperties().setProperty(TreeProperties.KEY_SOURCE, "IBD");
            return tree;
        }
        pafm.dispose();
        return tree;
    }

    public List<OBJMesh> getMeshes() {
        List<OBJMesh> meshList = new ArrayList<>();
        for (InsectBrainCompartment compartment : getAnnotations()) {
            OBJMesh mesh = compartment.getMesh();
            if (mesh != null) {
                meshList.add(mesh);
            }
        }
        return meshList;
    }

    public List<InsectBrainCompartment> getAnnotations() {
        if (getJSON() == null) {
            return null;
        }
        JSONArray arborizationRegions = jsonData
                .getJSONObject("data")
                .getJSONArray("arborization_regions");
        if (arborizationRegions == null || arborizationRegions.isEmpty()) {
            return null;
        }
        List<Integer> structureIdList = new ArrayList<>();
        for (int i = 0; i < arborizationRegions.length(); i++) {
            JSONObject region = arborizationRegions.getJSONObject(i);
            JSONObject structure = region.optJSONObject("structure");
            if (structure == null || structure.isEmpty()) continue;
            int structureId = structure.getInt("id");
            structureIdList.add(structureId);
        }
        neuronInfo = getNeuronInfo();
        List<InsectBrainCompartment> allCompartmentsList = InsectBrainUtils.getBrainCompartments(
                neuronInfo.getSpeciesID(),
                neuronInfo.getSex()
        );
        if (allCompartmentsList == null || allCompartmentsList.isEmpty()) {
            return null;
        }
        Map<Integer, InsectBrainCompartment> idToCompartmentMap = new HashMap<>();
        for (InsectBrainCompartment compartment : allCompartmentsList) {
            idToCompartmentMap.put(compartment.id(), compartment);
        }
        List<InsectBrainCompartment> targetCompartments = new ArrayList<>();
        for (Integer structureId : structureIdList) {
            InsectBrainCompartment compartment = idToCompartmentMap.get(structureId);
            if (compartment != null) {
                targetCompartments.add(compartment);
            }
        }
        return targetCompartments;
    }

    private String getJSONUrl() {
    	HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
        urlBuilder.addPathSegments("archive/neuron/");
        urlBuilder.addQueryParameter("format", "json");
        urlBuilder.addQueryParameter("id", String.valueOf(id));
        return urlBuilder.build().toString();
    }

    private JSONObject getJSON() {
        if (jsonData == null) {
            String resStr = getResponseStr(getJSONUrl());
            if (resStr == null) {
                return null;
            }
            JSONArray jsonArray = new JSONArray(resStr);
            if (jsonArray.isEmpty()) {
                SNTUtils.log("Empty JSONArray received for neuron " + id);
                return null;
            }
            jsonData = jsonArray.getJSONObject(0);
        }
        return jsonData;
    }

    private String getSWCUrl() {
    	if (getJSON() == null) {
    		return null;
    	}
    	try {
    		JSONArray viewerFiles = jsonData
    				.getJSONObject("data")
    				.getJSONArray("reconstructions")
    				.getJSONObject(0)
    				.getJSONArray("viewer_files");
    		if (viewerFiles.isEmpty()) {
    			SNTUtils.log("Viewer files not found for neuron " + id);
    			return null;
    		}
    		String uuidString = viewerFiles
    				.getJSONObject(0)
    				.getString("uuid");
    		HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
    		urlBuilder.addPathSegments("filestore/download_url/");
    		urlBuilder.addQueryParameter("uuid", uuidString);
    		String downloadUrl = urlBuilder.build().toString();
    		String resStr = getResponseStr(downloadUrl);
    		if (resStr == null) {
    			return null;
    		}
    		final JSONObject urlObj = new JSONObject(resStr);
    		return urlObj.getString("url");
    	} catch (final JSONException ex) {
			SNTUtils.log("Error: " + ex.getMessage());
    		return null;
    	}
    }

    private Response getResponse(final String url) throws IOException {
        final OkHttpClient client = new OkHttpClient();
        final Request request = new Request.Builder() //
                .url(url)
                .build();
        return client.newCall(request).execute();
    }

    private String getResponseStr(final String url) {
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

    public NeuronInfo getNeuronInfo() {
        if (neuronInfo != null) {
            return neuronInfo;
        }
        if (getJSON() == null) {
            return null;
        }
        SNTUtils.log("Retrieving info for neuron " + id);
        JSONObject data = jsonData.getJSONObject("data");
        neuronInfo = new NeuronInfo();
        neuronInfo.hasReconstructions = !data.getJSONArray("reconstructions").isEmpty();
        neuronInfo.neuronId = data.getInt("id");
        JSONObject morphology = data.optJSONObject("morphology");
        if (morphology != null) {
            neuronInfo.somaLocation = morphology.optString("soma_location");
        }
        neuronInfo.hemisphere = data.optString("hemisphere");
        neuronInfo.sex = data.getString("sex");
        neuronInfo.fullName = data.getString("full_name");
        neuronInfo.shortName = data.getString("short_name");
        JSONObject speciesObj = data.getJSONObject("species");
        neuronInfo.speciesId = speciesObj.getInt("id");
        neuronInfo.speciesScientificName = speciesObj.getString("scientific_name");
        neuronInfo.speciesCommonName = speciesObj.getString("common_name");
        neuronInfo.dateUploaded = data.getString("date_uploaded");
        return neuronInfo;
    }

    public static class NeuronInfo {

        int neuronId;
        boolean hasReconstructions;
        String somaLocation;
        String hemisphere;
        String sex;
        String fullName;
        String shortName;
        int speciesId;
        String speciesScientificName;
        String speciesCommonName;
        String dateUploaded;

        private NeuronInfo() {
        }

        public boolean hasReconstructions() { return hasReconstructions; }

        public int getNeuronID() {
            return neuronId;
        }

        public String getSomaLocation() { return somaLocation; }

        public String getHemisphere() {
            return hemisphere;
        }

        public String getSex() {
            return sex;
        }

        public String getFullName() {
            return fullName;
        }

        public String getShortName() {
            return shortName;
        }

        public int getSpeciesID() {
            return speciesId;
        }

        public String getSpeciesScientificName() {
            return speciesScientificName;
        }

        public String getSpeciesCommonName() {
            return speciesCommonName;
        }

        public String getDateUploaded() {
            return dateUploaded;
        }

    }

    /*
     * IDE debug method
     *
     * @throws IOException
     */
    public static void main(final String... args) {
        GuiUtils.setLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService sntService = new SNTService();
        sntService.setContext(ij.getContext());
        sntService.initialize(true);
        System.out.println("Database available: " + InsectBrainLoader.isDatabaseAvailable());
        final int speciesId = 11;
        List<Integer> allIds = InsectBrainUtils.getSpeciesNeuronIDs(speciesId);
        if (allIds == null || allIds.isEmpty()) {
            System.out.println("No neurons could be retrieved...");
            return;
        }
        System.out.println("Total # neurons: " + allIds.size());
        List<Tree> trees = new ArrayList<>();
//        Set<InsectBrainCompartment> allCompartments = new HashSet<>();
        for (Integer id : allIds) {
            final InsectBrainLoader loader = new InsectBrainLoader(id);
            NeuronInfo neuronInfo = loader.getNeuronInfo();
            if (neuronInfo == null) continue;
            System.out.println("\nNeuron ID: " + neuronInfo.getNeuronID());
            System.out.println("Has reconstructions: " + neuronInfo.hasReconstructions());
            System.out.println("Neuron name: " + neuronInfo.getFullName());
            System.out.println("Neuron short name: " + neuronInfo.getShortName());
            System.out.println("Soma location: " + neuronInfo.getSomaLocation());
            System.out.println("Hemisphere: " + neuronInfo.getHemisphere());
            System.out.println("Sex: " + neuronInfo.getSex());
            System.out.println("Species scientific name: " + neuronInfo.getSpeciesScientificName());
            System.out.println("Species common name: " + neuronInfo.getSpeciesCommonName());
            System.out.println("Species ID: " + neuronInfo.getSpeciesID());
            System.out.println("Date uploaded: " + neuronInfo.getDateUploaded());
            if (neuronInfo.hasReconstructions) {
                Tree tree = loader.getTree();
                if (tree == null) continue;
                trees.add(tree);
//                List<InsectBrainCompartment> compartments = loader.getAnnotations();
//                if (compartments != null && !compartments.isEmpty()) {
//                    allCompartments.addAll(compartments);
//                }
            }
        }
        List<InsectBrainCompartment> brainCompartments = InsectBrainUtils.getBrainCompartments(speciesId, "MALE");
        Viewer3D viewer = new Viewer3D();
        viewer.add(brainCompartments.stream().map(InsectBrainCompartment::getMesh).collect(Collectors.toList()));
        viewer.addTrees(trees, "unique");
        viewer.show();
    }
}
