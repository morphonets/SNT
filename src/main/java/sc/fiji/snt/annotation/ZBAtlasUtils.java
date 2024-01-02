/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import java.net.URL;

import org.scijava.util.Colors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.OBJMesh;

/**
 * Utility methods for accessing the Max Plank Zebrafish Brain Atlas (ZBA) at
 * <a href="https://fishatlas.neuro.mpg.de">fishatlas.neuro.mpg.de</a>.
 * 
 * @author Tiago Ferreira
 */
public class ZBAtlasUtils {

	private static final String HOME_DIR = "https://fishatlas.neuro.mpg.de/";
	private final static PointInImage BRAIN_BARYCENTRE = new PointInImage(0.274314f, 0.511951f, 0.178172f);

	private ZBAtlasUtils() {
	}

	/**
	 * Checks whether a connection to the FishAtlas database can be
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
	 * Returns the spatial centroid of the template brain.
	 *
	 * @return the SNT point defining the (X,Y,Z) center of the brain outline.
	 */
	public static SNTPoint brainBarycentre() {
		return BRAIN_BARYCENTRE;
	}

	/**
	 * Retrieves the surface mesh (outline) of the zebrafish template brain.
	 * 
	 * @return the outline mesh.
	 * @throws IllegalArgumentException if mesh could not be retrieved.
	 */
	public static OBJMesh getRefBrain() throws IllegalArgumentException {
		return getBundledMesh("Outline", "meshes/ZfKunst2019.obj");
	}

	private static OBJMesh getBundledMesh(final String meshLabel, final String meshPath) {
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		final URL url = loader.getResource(meshPath);
		if (url == null)
			throw new IllegalArgumentException(meshLabel + " not found");
		final OBJMesh mesh = new OBJMesh(url, GuiUtils.micrometer());
		mesh.setColor(Colors.WHITE, 95f);
		mesh.setLabel(meshLabel);
		return mesh;
	}

}


