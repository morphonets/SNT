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

package sc.fiji.snt;

/**
 * Allows standardized metadata to be associated to a {@link Tree}.
 * 
 * @author Tiago Ferreira
 */
public interface TreeProperties {

	String KEY_ID = "id";
	String KEY_LABEL = "label";
	String KEY_SOURCE = "source";
	String KEY_IMG = "img";
	String KEY_IMG_CHANNEL = "channel";
	String KEY_IMG_FRAME = "frame";
	String KEY_FRAME_POS = "frame";
	String KEY_COMPARTMENT = "compartment";
	String KEY_SPATIAL_UNIT = "unit";
	String KEY_COLOR = "color";
	String KEY_CANVAS_OFFSET = "canvasOffset";

	String DENDRITIC = "dendritic";
	String AXONAL = "axonal";
	String SOMATIC = "somatic";

	String UNSET = "?";

	static String getStandardizedCompartment(final String description) {
		if (description == null || description.trim().isEmpty()) return UNSET;
		final String nType = description.toLowerCase();
		if (nType.contains("dend") || nType.contains("dnd")) return DENDRITIC;
		if (nType.contains("axon") || nType.contains("axn")) return AXONAL;
		if (nType.contains("soma") || nType.contains("cell body")) return SOMATIC;
		return UNSET;
	}
}
