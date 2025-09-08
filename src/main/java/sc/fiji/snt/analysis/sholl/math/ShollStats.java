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
package sc.fiji.snt.analysis.sholl.math;

import org.apache.commons.lang3.StringUtils;
import sc.fiji.snt.analysis.sholl.Profile;

/**
 * Methods and constants common to Profile analyzers.
 * 
 * @author Tiago Ferreira
 *
 */
public interface ShollStats {

	/** Flag for area normalization (Semi-log/Log-log method) */
	int AREA = 2;
	/** Flag for perimeter normalization (Semi-log/Log-log method) */
	int PERIMETER = 4;
	/** Flag for annulus normalization (Semi-log/Log-log method) */
	int ANNULUS = 8;
	/** Flag for volume normalization (Semi-log/Log-log method) */
	int VOLUME = 16;
	/** Flag for surface normalization (Semi-log/Log-log method) */
	int SURFACE = 32;
	/** Flag for spherical shell normalization (Semi-log/Log-log method) */
    int S_SHELL = 64;

	/** Flag for imposing Semi-log analysis */
	int SEMI_LOG = 128;
	/** Flag for imposing Log-log analysis */
	int LOG_LOG = 256;
	/** Flag for automatic choice between Semi-log/Log-log analysis */
	int GUESS_SLOG = 512;

	double[] getXValues();

	double[] getYValues();

	double[] getYValues(final boolean asCumulativeFrequencies);

	// double[] getFitXvalues();

	double[] getFitYValues();

	double[] getFitYValues(final boolean asCumulativeFrequencies);

	boolean validFit();

	int getN();

	Profile getProfile();

    DataMode getDataMode();

    void setDataMode(final DataMode mode);

    enum DataMode {
        INTERSECTIONS, LENGTH;

        @Override
        public String toString() {
            return StringUtils.capitalize(super.toString().toLowerCase());
        }

        public static DataMode fromString(final String string) {
            if (string == null || string.isBlank() || string.toLowerCase().contains("int"))
                return INTERSECTIONS;
            return LENGTH;
        }

    }

}
