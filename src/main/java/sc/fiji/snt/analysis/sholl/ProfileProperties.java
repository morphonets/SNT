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
package sc.fiji.snt.analysis.sholl;

/**
 * Associates metadata to a Sholl Profile
 * 
 * @author Tiago Ferreira
 */
public interface ProfileProperties {

	final String KEY_ID = "id";
	final String KEY_2D3D = "n-dimensions";
	final String KEY_SOURCE = "source";
	final String KEY_HEMISHELLS = "hemishells";
	final String KEY_NSAMPLES = "samples-per-radius";
	final String KEY_NSAMPLES_INTG = "samples-integration";
	final String KEY_CALIBRATION = "calibration";
	final String KEY_CENTER = "center";
	final String KEY_CHANNEL_POS = "channel";
	final String KEY_SLICE_POS = "slice";
	final String KEY_FRAME_POS = "frame";
	final String KEY_THRESHOLD_RANGE = "threshold-range";
    final String KEY_EFFECTIVE_STEP_SIZE = "effective.step.size";
    final String KEY_EXTRA_MEASUREMENT = "extra-measurement";

	final String SRC_TABLE = "table";
	final String SRC_TRACES = "tracings";
	final String SRC_IMG = "image";

	final String HEMI_NONE = "none";
	final String HEMI_NORTH = "north";
	final String HEMI_SOUTH = "south";
	final String HEMI_WEST = "west";
	final String HEMI_EAST = "east";

	final String INTG_MEAN = "mean";
	final String INTG_MEDIAN = "median";
	final String INTG_MODE = "mode";

	final String UNSET = "?";

}
