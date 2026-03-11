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

package sc.fiji.snt.annotation;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.OBJMesh;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;
import java.util.UUID;

import org.scijava.util.ColorRGB;

/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class InsectBrainCompartment implements BrainAnnotation {

    private static final String BASE_URL = "https://insectbraindb.org/";

    private int id;
    private String name;
    private String acronym;

    @SuppressWarnings("unused")
	private String[] aliases;

    private UUID uuid;
    private UUID fileUUID;
    private String objPath;
    private String objColor;

    @SuppressWarnings("unused")
	private Object hemisphere;
    @SuppressWarnings("unused")
	private int speciesId;
    @SuppressWarnings("unused")
	private String sex;
    @SuppressWarnings("unused")
	private String type;
    @SuppressWarnings("unused")
	private BrainAnnotation parent;

    protected InsectBrainCompartment() {
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String acronym() {
        return acronym;
    }

    @Override
    public String[] aliases() {
        return new String[0];
    }

    private String getMeshURL() {
        if (fileUUID != null) {
            // Use the filestore API to get a signed download URL
            final HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
            urlBuilder.addPathSegments("filestore/download_url/");
            urlBuilder.addQueryParameter("uuid", fileUUID.toString());
            final String apiUrl = urlBuilder.build().toString();
            try {
                final OkHttpClient client = new OkHttpClient();
                final Request request = new Request.Builder().url(apiUrl).build();
                try (final Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        final org.json.JSONObject json = new org.json.JSONObject(response.body().string());
                        return json.getString("url");
                    }
                }
            } catch (final IOException | org.json.JSONException e) {
                SNTUtils.log("Could not resolve download URL via API for " + name + ": " + e.getMessage());
            }
        }
        return null;
    }

    @Override
    public OBJMesh getMesh() {
        final String urlPath = getMeshURL();
        if (urlPath == null) {
            SNTUtils.log("Could not resolve download URL for mesh: " + name);
            return null;
        }
        try {
            final URL url = new URI(urlPath).toURL();
            final OBJMesh mesh = new OBJMesh(url, GuiUtils.micrometer());
            mesh.setColor(objColor, 95f);
            mesh.setLabel(name);
            return mesh;
        } catch (final IllegalArgumentException | IOException | URISyntaxException e) {
            SNTUtils.error("Could not retrieve mesh ", e);
        }
        return null;
    }

    @Override
    public boolean isChildOf(BrainAnnotation annotation) {
        return false;
    }

    @Override
    public boolean isParentOf(BrainAnnotation parentCompartment) {
        return false;
    }

    @Override
    public int getOntologyDepth() {
        return 0;
    }

    @Override
    public boolean isMeshAvailable() {
        return objPath != null;
    }

    @Override
    public BrainAnnotation getAncestor(int level) {
        return null;
    }

    @Override
    public BrainAnnotation getParent() { return null; }

    protected void setID(int id) {
        this.id = id;
    }

    protected void setName(String name) {
        this.name = name;
    }

    protected void setAcronym(String acronym) {
        this.acronym = acronym;
    }

    protected void setAliases(String[] aliases) {
        this.aliases = aliases;
    }

    protected void setUUID(UUID uuid) {
        this.uuid = uuid;
    }

    protected void setObjPath(String objPath) {
        this.objPath = objPath;
    }

    protected void setFileUUID(UUID fileUUID) {
        this.fileUUID = fileUUID;
    }

    protected void setObjColor(String objColor) {
        this.objColor = objColor;
    }

    protected void setHemisphere(Object hemisphere) {
        this.hemisphere = hemisphere;
    }

    protected void setSpeciesID(int speciesId) {
        this.speciesId = speciesId;
    }

    protected void setSex(String sex) {
        this.sex = sex;
    }

    protected void setType(String type) {
        this.type = type;
    }

    protected void setParent(BrainAnnotation parent) {
        this.parent = parent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InsectBrainCompartment that = (InsectBrainCompartment) o;
        return uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

	@Override
	public ColorRGB color() {
		return (null==objColor) ? null : new ColorRGB(objColor);
	}
}
