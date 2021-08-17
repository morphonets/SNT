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

package sc.fiji.snt.gui.cmds;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import features.TubenessProcessor;
import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements the "Cache All Hessian Computations" command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init")
public class ComputeTubenessImg extends CommonDynamicCmd {

	@Parameter
	private OpService ops;

	private double sigma;

	//@Parameter(type = ItemIO.OUTPUT) // uncomment to have image displayed
	private ImagePlus tubenessImp;

	protected void init() {
		super.init(true);
		if (!snt.accessToValidImageData()) {
			error("Valid image data is required for computation.");
			return;
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		status("Computing whole-image Hessian data...", false);
		// TODO this class may not be necessary anymore
		sigma = snt.getHessianSigma(true)[0];
		SNTUtils.log("Generating Tubeness image: sigma=" + sigma);
		final ImagePlus inputImp = sntService.getPlugin().getLoadedDataAsImp();
		processUsingIJ1(inputImp); //processUsingOps(inputImp);
		snt.loadTubenessImage(tubenessImp);
		resetUI();
		status("Computation completed.", true);
	}

	private void processUsingIJ1(final ImagePlus inputImp) {
		final TubenessProcessor tp = new TubenessProcessor(true);
		tp.setSigma(sigma);
		tubenessImp = tp.generateImage(inputImp);
	}

	@SuppressWarnings("unused")
	private void processUsingOps(final ImagePlus inputImp) {
		final Calibration cal = inputImp.getCalibration(); // never null
		final Img<FloatType> in = ImageJFunctions.convertFloat(inputImp);
		final Img<DoubleType> out = ops.create().img(in, new DoubleType());
		ops.filter().tubeness(out, in, sigma, cal.pixelWidth, cal.pixelHeight,
				cal.pixelDepth);
		tubenessImp = ImageJFunctions.wrap(out, String.format("Tubeness: Sigma=%.1f", sigma));
		// Somehow metadata seems to be lost, so we'll ensure result is correct
		tubenessImp.setDimensions(inputImp.getNChannels(), inputImp.getNSlices(), inputImp.getNFrames());
		tubenessImp.copyScale(inputImp);
	}

	public ImagePlus getTubenessImp() {
		return tubenessImp;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ComputeTubenessImg.class, true);
	}
}
