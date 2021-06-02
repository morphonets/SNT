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

import ij.ImagePlus;

import java.util.Arrays;
import java.util.List;

/**
 * This class is responsible for initiating Hessian analysis on both the
 * <i>primary</i> (main) and the <i>secondary</i> image. Currently computations
 * are performed by {@link HessianProcessor}, but could be extended to
 * adopt other approaches.
 * 
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class HessianCaller {

	private final SNT snt;
	static final int PRIMARY = 0;
	static final int SECONDARY = 1;

	public static final byte TUBENESS = 0;
	public static final byte FRANGI = 1;

	private final int imageType;
	double[] sigmas;
	protected HessianProcessor hessian;
	protected float[][] cachedTubeness;
	private ImagePlus imp;
	private byte analysisType = TUBENESS;

	HessianCaller(final SNT snt, final int type) {
		this.snt = snt;
		this.imageType = type;
	}

	public void setAnalysisType(final byte analysisType) {
		this.analysisType = analysisType;
	}

	public byte getAnalysisType() {
		return analysisType;
	}

	public void setSigmas(final List<Double> scaleSettings) {
		this.sigmas = new double[scaleSettings.size()];
		for (int i = 0; i < scaleSettings.size(); ++i) {
			this.sigmas[i] = scaleSettings.get(i);
		}
	}

	protected double[] getSigmas(final boolean physicalUnits) {
		if (sigmas == null) {
			return (physicalUnits) ? getDefaultSigma() : new double[]{1.0};
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
		final double minSep = snt.getMinimumSeparation();
		final double avgSep = snt.getAverageSeparation();
		return (minSep == avgSep) ? new double[]{2 * minSep} : new double[]{avgSep};
	}

	public boolean isHessianComputed() {
		return hessian != null;
	}

	public Thread start() {
		snt.changeUIState((imageType == PRIMARY) ? SNTUI.CALCULATING_HESSIAN_I : SNTUI.CALCULATING_HESSIAN_II);
		if (sigmas == null)
			sigmas = getDefaultSigma();
		setImp();
		hessian = new HessianProcessor(imp, snt);
		Thread thread;
		if (analysisType == TUBENESS) {
			thread = new Thread(() -> hessian.processTubeness(sigmas, true));
		} else if (analysisType == FRANGI) {
			thread = new Thread(() -> hessian.processFrangi(sigmas, true));
		} else {
			throw new IllegalArgumentException("BUG: Unknown analysis type");
		}
		thread.start();
		return thread;
	}

	private void setImp() {
		if (imp == null) imp = (imageType == PRIMARY) ? snt.getLoadedDataAsImp() : snt.getSecondaryDataAsImp();
	}

	public ImagePlus getImp() {
		setImp();
		return imp;
	}

	protected void cancelHessianGeneration() {
		// TODO
	}

	void nullify() {
		hessian = null;
		sigmas = null;
		cachedTubeness = null;
		imp = null;
	}

	@Override
	public String toString() {
		return ((imageType == PRIMARY) ? "(main" : "(secondary") + " image): sigmas=" + Arrays.toString(sigmas);
	}
}
