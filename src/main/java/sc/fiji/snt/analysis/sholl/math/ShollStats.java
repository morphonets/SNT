/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import sc.fiji.snt.analysis.sholl.Profile;

/**
 * Methods and constants common to Profile analyzers.
 * 
 * @author Tiago Ferreira
 *
 */
public interface ShollStats {

	/** Flag for area normalization (Semi-log/Log-log method) */
	final static int AREA = 2;
	/** Flag for perimeter normalization (Semi-log/Log-log method) */
	static int PERIMETER = 4;
	/** Flag for annulus normalization (Semi-log/Log-log method) */
	final static int ANNULUS = 8;
	/** Flag for volume normalization (Semi-log/Log-log method) */
	final static int VOLUME = 16;
	/** Flag for surface normalization (Semi-log/Log-log method) */
	final static int SURFACE = 32;
	/** Flag for spherical shell normalization (Semi-log/Log-log method) */
	static final int S_SHELL = 64;

	/** Flag for imposing Semi-log analysis */
	final static int SEMI_LOG = 128;
	/** Flag for imposing Log-log analysis */
	final static int LOG_LOG = 256;
	/** Flag for automatic choice between Semi-log/Log-log analysis */
	final static int GUESS_SLOG = 512;

	double[] getXvalues();

	double[] getYvalues();

	double[] getYvalues(final boolean asCumulativeFrequencies);

	// double[] getFitXvalues();

	double[] getFitYvalues();

	double[] getFitYvalues(final boolean asCumulativeFrequencies);

	boolean validFit();

	int getN();

	Profile getProfile();

}
