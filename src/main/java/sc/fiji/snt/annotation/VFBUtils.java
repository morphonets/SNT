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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.OBJMesh;

/**
 * Utility methods for accessing/handling Virtual Fly Brain (VFB) annotations
 * 
 * @author Tiago Ferreira
 */
public class VFBUtils {

	private static final String HOME_DIR = "http://www.virtualflybrain.org/site/vfb_site/home.htm";
	private static final String DATA_DIR = "http://www.virtualflybrain.org/data/VFB/i/";
	private final static String JFRC2018_MESH_LABEL = "JFRC 2018 Template";
	private final static String JFRC2_MESH_LABEL = "JFRC2 (VFB) Template";
	private final static String JFRC3_MESH_LABEL = "JFRC3 Template";
	private final static String FCWB_MESH_LABEL = "FCWB Template";
	private final static PointInImage JFRC2018_BRAIN_BARYCENTRE = new PointInImage(312.1580f, 150.0717f, 89.4155f);
	private final static PointInImage JFRC2_BRAIN_BARYCENTRE = new PointInImage(321.3978f, 154.8180f, 69.0848f);
	private final static PointInImage JFRC3_BRAIN_BARYCENTRE = new PointInImage(276.5773f, 133.8614f, 82.6505f);
	private final static PointInImage FCWB_BRAIN_BARYCENTRE = new PointInImage(281.7975f, 154.2765f, 53.6835f);


	private VFBUtils() {
	}

	/**
	 * Checks whether a connection to the Virtual Fly Brain database can be
	 * established.
	 *
	 * @return true, if an HHTP connection could be established
	 */
	public static boolean isDatabaseAvailable() {
		boolean success;
		Response response = null;
		try {
			final OkHttpClient client = new OkHttpClient();
			final Request request = new Request.Builder().url(HOME_DIR).build();
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
	 * Returns the spatial centroid of an adult Drosophila template brain.
	 * 
	 * @param templateBrain the template brain to be loaded (case-insensitive).
	 *                      Either "JFRC2" (AKA JFRC2010, VFB), "JFRC3" (AKA
	 *                      JFRC2013), "JFRC2018" or "FCWB" (FlyCircuit Whole Brain
	 *                      Template)
	 * @return the SNT point defining the (X,Y,Z) center of brain mesh.
	 */
	public static SNTPoint brainBarycentre(final String templateBrain) {
		switch (getNormalizedTemplateLabel(templateBrain)) {
		case JFRC2018_MESH_LABEL:
			return JFRC2018_BRAIN_BARYCENTRE;
		case JFRC2_MESH_LABEL:
			return JFRC2_BRAIN_BARYCENTRE;
		case JFRC3_MESH_LABEL:
			return JFRC3_BRAIN_BARYCENTRE;
		case FCWB_MESH_LABEL:
			return FCWB_BRAIN_BARYCENTRE;
		default:
			throw new IllegalArgumentException("Invalid argument");
		}
	}

	private static String getNormalizedTemplateLabel(final String templateBrain) {
		final String inputType = (templateBrain == null) ? null : templateBrain.toLowerCase();
		switch (inputType) {
		case "jfrc2018":
		case "jfrc 2018":
		case "jfrctemplate2018":
			return JFRC2018_MESH_LABEL;
		case "jfrc2":
		case "jfrc2010":
		case "jfrctemplate2010":
		case "vfb":
			return JFRC2_MESH_LABEL;
		case "jfrc3":
		case "jfrc2013":
		case "jfrctemplate2013":
			return JFRC3_MESH_LABEL;
		case "fcwb":
		case "flycircuit":
			return FCWB_MESH_LABEL;
		default:
			throw new IllegalArgumentException("Invalid argument");
		}
	}

	/**
	 * Retrieves the surface mesh of an adult Drosophila template brain. No Internet
	 * connection is required, as these meshes (detailed on the <a href=
	 * "https://www.rdocumentation.org/packages/nat.templatebrains/">nat.flybrains</a>
	 * documentation) are bundled with SNT.
	 *
	 * @param templateBrain the template brain to be loaded (case-insensitive).
	 *                      Either "JFRC2" (AKA JFRC2010, VFB), "JFRC3" (AKA
	 *                      JFRC2013), "JFRC2018", or "FCWB" (FlyCircuit Whole Brain
	 *                      Template)
	 * @return the template mesh.
	 * @throws IllegalArgumentException if templateBrain is not recognized
	 */
	public static OBJMesh getRefBrain(final String templateBrain) throws IllegalArgumentException {
		switch (getNormalizedTemplateLabel(templateBrain)) {
		case JFRC2018_MESH_LABEL:
			return getBundledMesh(JFRC2018_MESH_LABEL, "meshes/JFRCtemplate2018.obj");
		case JFRC2_MESH_LABEL:
			return getBundledMesh(JFRC2_MESH_LABEL, "meshes/JFRCtemplate2010.obj");
		case JFRC3_MESH_LABEL:
			return getBundledMesh(JFRC3_MESH_LABEL, "meshes/JFRCtemplate2013.obj");
		case FCWB_MESH_LABEL:
			return getBundledMesh(FCWB_MESH_LABEL, "meshes/FCWB.obj");
		default:
			throw new IllegalArgumentException("Invalid argument");
		}
	}

	private static String[] getResourcePathAndLabel(final String resourceIdentifier, final String filename) {
		final String regex = "(^VFB_)?(\\d{8})";
		final Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
		final Matcher matcher = pattern.matcher(resourceIdentifier);
		if (matcher.matches() && matcher.groupCount() > 1) {
			final String id = matcher.group(2);
			final StringBuilder str = new StringBuilder(DATA_DIR);
			str.append(id.substring(0, 4));
			str.append("/");
			str.append(id.substring(4, 8));
			str.append("/").append(filename);
			return new String[] { str.toString(), "VFB_" + id };
		}
		return null;
	}

	private static String[] getOBJPathAndLabel(final String identifier) {
		return getResourcePathAndLabel(identifier, "volume_man.obj");
	}

	private static OBJMesh getBundledMesh(final String meshLabel, final String meshPath) {
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		final URL url = loader.getResource(meshPath);
		if (url == null)
			throw new IllegalArgumentException(meshLabel + " not found");
		final OBJMesh mesh = new OBJMesh(url, "um");
		mesh.setColor(Colors.WHITE, 95f);
		mesh.setLabel(meshLabel);
		return mesh;
	}

	/**
	 * Retrieves the mesh associated with the specified VFB id.
	 *
	 * @param vfbId the VFB id, e.g., VFB_00017894, 00017894
	 * @return a reference to the retrieved mesh
	 */
	public static OBJMesh getMesh(final String vfbId) {
		return getMesh(vfbId, Colors.WHITE);
	}

	/**
	 * Retrieves the mesh associated with the specified VFB id.
	 *
	 * @param vfbId the VFB id, e.g., VFB_00017894, 00017894
	 * @param color the color to be assigned to the mesh
	 * @return a reference to the retrieved mesh
	 */
	public static OBJMesh getMesh(final String vfbId, final ColorRGB color) {
		try {
			final String[] pathAndLabel = getOBJPathAndLabel(vfbId);
			if (pathAndLabel == null) {
				SNTUtils.log("Cannot retrieve mesh. Invalid id: " + vfbId);
				return null;
			}
			final URL url = new URL(pathAndLabel[0]);
			final OBJMesh mesh = new OBJMesh(url, "um");
			mesh.setColor(color, 95f);
			mesh.setLabel(pathAndLabel[1]);
			return mesh;
		} catch (final MalformedURLException e) {
			SNTUtils.error(e.getMessage(), e);
			return null;
		}

	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		final String input = "00049000";
		final String[] path = getOBJPathAndLabel(input);
		System.out.println("VFB reachable: " + isDatabaseAvailable());
		System.out.println(path[1] + ": " + path[0]);
	}

}
