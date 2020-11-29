/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
import sc.fiji.snt.viewer.OBJMesh;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.UUID;

/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class InsectBrainCompartment implements BrainAnnotation {

    private static final String BASE_OBJ_DOWNLOAD_URL = "https://s3.eu-central-1.amazonaws.com/ibdb-file-storage/";

    private int id;
    private String name;
    private String acronym;
    private String[] aliases;
    private UUID uuid;
    private String objPath;
    private String objColor;
    private Object hemisphere;
    private int speciesId;
    private String sex;
    private String type;
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
        @SuppressWarnings("ConstantConditions")
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_OBJ_DOWNLOAD_URL).newBuilder();
        urlBuilder.addPathSegments(objPath);
        return urlBuilder.build().toString();
    }

    @Override
    public OBJMesh getMesh() {
        OBJMesh mesh = null;
        String urlPath = getMeshURL();
        try {
            final OkHttpClient client = new OkHttpClient();
            final Request request = new Request.Builder() //
                    .url(urlPath)
                    .build();
            final Response response = client.newCall(request).execute();
            final boolean success = response.isSuccessful();
            response.close();
            if (!success) {
                System.out.println(
                        "Server is not reachable. Mesh(es) could not be retrieved. Check your internet connection..."
                );
                return null;
            }
            final URL url = new URL(urlPath);
            mesh = new OBJMesh(url, "um");
            mesh.setColor(objColor, 95f);
            mesh.setLabel(name);
        } catch (final IllegalArgumentException | IOException e) {
            SNTUtils.error("Could not retrieve mesh ", e);
        }
        return mesh;
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
}
