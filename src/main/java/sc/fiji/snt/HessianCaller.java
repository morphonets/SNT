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

import features.ComputeCurvatures;
import ij.ImagePlus;

/**
 * This class is responsible for initiating Heassian analysis on both the
 * <i>primary</i> (main) and the <i>secondary</i> image. Currently computations
 * are performed by {@link features.ComputeCurvatures}, but could be extended to
 * adopt other approaches.
 * 
 * @author Tiago Ferreira
 */
public class HessianCaller {

	private final SNT snt;
	static final int PRIMARY = 0;
	static final int SECONDARY = 1;
	static final double DEFAULT_MULTIPLIER = 4;

	public static final byte TUBENESS = 0;
	public static final byte FRANGI = 1;

	private final int type;
	double sigma = -1;
	double multiplier = DEFAULT_MULTIPLIER;
	protected HessianAnalyzer hessian;
	protected float[][] cachedTubeness;
	private ImagePlus imp;
	private byte analysisType = TUBENESS;

	HessianCaller(final SNT snt, final int type) {
		this.snt = snt;
		this.type = type;
	}

	public void setAnalysisType(final byte analysisType) {
		this.analysisType = analysisType;
	}

	public byte getAnalysisType() {
		return analysisType;
	}

	public void setSigmaAndMax(final double sigmaInCalibratedUnits, final double max) {
		if (sigma != sigmaInCalibratedUnits)
			hessian = null;
		this.sigma = sigmaInCalibratedUnits;
		this.multiplier = impMax() / max;
		if (snt.ui != null) snt.ui.updateHessianPanel(this);
		SNTUtils.log("Hessian parameters adjusted "+ toString());
	}

	protected double getSigma(final boolean physicalUnits) {
		if (sigma == -1) sigma = (physicalUnits) ? getDefaultSigma() : 1;
		return (physicalUnits) ? sigma : Math.round(sigma / snt.getAverageSeparation());
	}

	protected double getMultiplier() {
		return multiplier;
	}

	protected double getMax() {
		return Math.min(impMax(), 256) / multiplier;
	}

	protected double getDefaultMax() {
		return Math.min(impMax(), 256) / DEFAULT_MULTIPLIER;
	}

	protected double getDefaultSigma() {
		final double minSep = snt.getMinimumSeparation();
		final double avgSep = snt.getAverageSeparation();
		return (minSep == avgSep) ? 2 * minSep : avgSep;
	}

	private double impMax() {
		return (type == PRIMARY) ? snt.stackMax : snt.stackMaxSecondary;
	}

	public double[] impRange() {
		return (type == PRIMARY) ? new double[] { snt.stackMin, snt.stackMax }
				: new double[] { snt.stackMinSecondary, snt.stackMaxSecondary };
	}

	public boolean isGaussianComputed() {
		return hessian != null;
	}

	public Thread start() {
		snt.changeUIState((type == PRIMARY) ? SNTUI.CALCULATING_GAUSSIAN_I : SNTUI.CALCULATING_GAUSSIAN_II);
		if (sigma == -1)
			sigma = getDefaultSigma();
		setImp();
		hessian = new HessianAnalyzer(imp);
		Thread thread;
		if (analysisType == TUBENESS) {
			thread = new Thread(() -> hessian.processTubeness(sigma, false));
		} else if (analysisType == FRANGI) {
			// TODO: allow multi-selection in sigma palette and input dialog
			thread = new Thread(() -> hessian.processFrangi(new double[]{sigma * 0.5, sigma, sigma * 2}, true));
		} else {
			throw new IllegalArgumentException("BUG: Unknown analysis type");
		}
		thread.start();
		return thread;
	}

	private void setImp() {
		if (imp == null) imp = (type == PRIMARY) ? snt.getLoadedDataAsImp() : snt.getSecondaryDataAsImp();
	}

	public ImagePlus getImp() {
		setImp();
		return imp;
	}

	protected void cancelGaussianGeneration() {
		// TODO
//		if (hessian != null) hessian.cancelGaussianGeneration();
	}

	void nullify() {
		hessian = null;
		sigma = -1;
		multiplier = DEFAULT_MULTIPLIER;
		cachedTubeness = null;
		imp = null;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder((type == PRIMARY) ? "(main" : "(secondary");
		sb.append(" image): sigma=").append(sigma).append(", m=").append(multiplier);
		return sb.toString();
	}
}
