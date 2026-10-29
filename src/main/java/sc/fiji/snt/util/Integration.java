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

package sc.fiji.snt.util;

import org.scijava.util.Types;

import java.util.Arrays;
import java.util.List;

/**
 * Optional software SNT can interact with, but does not depend on at compile
 * time. Availability is determined at runtime by the presence of classes, which
 * is more reliable than checking update-site subscriptions.
 *
 * @see sc.fiji.snt.gui.cmds.EnableIntegrationCmd
 */
public enum Integration {

	/** sciview (3D visualization using scenery) */
	SCIVIEW("sciview", "sciview", "https://docs.scenery.graphics/sciview",
			"sc.iview.SciView", "graphics.scenery.Scene"),

	/** BigVolumeBrowser (BVB) */
	BVB("BigVolumeBrowser", "BigVolumeBrowser",
			"https://github.com/UU-cellbiology/bigvolumebrowser/wiki", "bvb.core.BigVolumeBrowser"),

	/** TrakEM2 (interaction through {@link sc.fiji.snt.event.SNTEvent}s) */
	TRAKEM2("TrakEM2", "TrakEM2", "https://imagej.net/plugins/trakem2/", "ini.trakem2.Project"),

	/** Tubular Geodesics (fast marching search) */
	TUBULAR_GEODESICS("TubularGeodesics", null,
			"https://imagej.net/plugins/snt/extending#tubular-geodesics", "FijiITKInterface.TubularGeodesics");

	private final String displayName;
	private final String updateSite;
	private final String docsUrl;
	private final String[] classNames;

	Integration(final String displayName, final String updateSite, final String docsUrl,
			final String... classNames) {
		this.displayName = displayName;
		this.updateSite = updateSite;
		this.docsUrl = docsUrl;
		this.classNames = classNames;
	}

	/**
	 * @return the integrations that are currently available
	 */
	public static List<Integration> available() {
		return Arrays.stream(values()).filter(Integration::isAvailable).toList();
	}

	/**
	 * @return the integrations that are currently not available
	 */
	public static List<Integration> missing() {
		return Arrays.stream(values()).filter(i -> !i.isAvailable()).toList();
	}

	/** @return the name of the software, as shown to users */
	public String getDisplayName() {
		return displayName;
	}

	/** @return the name of the Fiji update site providing the software, or null if it is not distributed through one */
	public String getUpdateSite() {
		return updateSite;
	}

	/** @return the URL of the documentation, or null if undefined */
	public String getDocsUrl() {
		return docsUrl;
	}

	/**
	 * @return true if all the classes defining this integration can be loaded
	 */
	public boolean isAvailable() {
		for (final String name : classNames) {
			if (Types.load(name) == null) return false;
		}
		return true;
	}

	/**
	 * Loads a class of this integration at runtime. Useful in scripts, where
	 * classes imported at compile time would prevent the script from running
	 * if the integration is missing.
	 *
	 * @param className the fully qualified class name
	 * @return the class, or null if it could not be loaded
	 */
	public Class<?> load(final String className) {
		return Types.load(className);
	}
}
