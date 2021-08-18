/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import org.apache.commons.lang3.StringUtils;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;

import static sc.fiji.snt.SigmaHelper.Analysis.TUBENESS;


@Deprecated
public class SigmaHelper {

	private final SNT snt;

	public enum Analysis {
		TUBENESS, FRANGI, GAUSS;

		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

	private Analysis analysisType = TUBENESS;

	double[] sigmas;
	protected float[][] cachedTubeness;


	SigmaHelper(final SNT snt) {
		this.snt = snt;
	}

	public void setAnalysisType(final Analysis analysisType) {
		this.analysisType = analysisType;
	}

	public Analysis getAnalysisType() {
		return analysisType;
	}

	public void setSigmas(final List<Double> scaleSettings) {
		this.sigmas = new double[scaleSettings.size()];
		for (int i = 0; i < scaleSettings.size(); ++i) {
			this.sigmas[i] = scaleSettings.get(i);
		}
		if (snt.getUI() != null) snt.getUI().refresh();
	}

	protected double[] getSigmas(final boolean physicalUnits) {
		if (sigmas == null) {
			return null;
		}
		if (physicalUnits) {
			return sigmas;
		}
		double[] unscaledSigmas = new double[sigmas.length];
		for (int i = 0; i < sigmas.length; ++i) {
			unscaledSigmas[i] = (double)Math.round(sigmas[i] / snt.getAverageSeparation());
		}
		return unscaledSigmas;
	}

	protected double[] getDefaultSigma() {
		final double avgSep = snt.getAverageSeparation();
		final double step = avgSep / 2;
		return new double[]{step, 2 * step, 3 * step};
	}

	void nullify() {
		sigmas = null;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append((snt.getSecondaryData() == null) ? "Disabled" : "Enabled");
		sb.append("; \u03C3=");
		if (sigmas == null)
			sb.append("NaN");
		else {
			final DecimalFormat df = new DecimalFormat("0.00");
			sb.append("[");
			Arrays.stream(sigmas).forEach(s -> sb.append(df.format(s)).append(", "));
			sb.setLength(sb.length() - 2);
			sb.append("]");
		}
		sb.append("; type: ").append(analysisType);
		return sb.toString();
	}
}
