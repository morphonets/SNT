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

package sc.fiji.snt.plugin;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.tracing.auto.ImageProbe;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.SNTUtils;

/**
 * Convenience command for running Fiji's "Local Thickness" plugin from SNT, enabling
 * estimation of radii from binary skeleton images.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init",
label = "Estimate Radii (Local Thickness)")
public class LocalThicknessCmd extends CommonDynamicCmd {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final String CMD_LABEL = "Local Thickness (complete process)";

	@Parameter(required = false, label = "First Z-slice",
		description = "First Z-slice to be considered in the estimation", min = "1")
	private int minZ;

	@Parameter(required = false, label = "Last Z-slice",
			description = "Last Z-slice to be considered in the estimation", min = "1")
	private int maxZ;

	@Parameter(required = false, label = "Dimmest intensity (approx.)",
			description = "<HTML>Pixel values below this value are treated as background when<br>"
					+ "computing the distance map. Use -1 to adopt the default cutoff<br>"
					+ "value (estimated from the background and signal levels of the<br>"
					+ "image, or half of image max if those cannot be estimated)")
	private double thres;

	@Parameter(required = false, label = "Defaults", callback="applyDefaults")
	private Button defaults;

	@Parameter(required = false, label = "Image", choices = { "Primary (Main)", "Secondary" },
			description = "Estimate radii on the primary (main) or the secondary image?")
	private String imgChoice ;

	@Parameter(label = "<HTML>&nbsp;", persist = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER = "<HTML>&nbsp;";

	@Parameter(label = "<HTML>&nbsp;", persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "<HTML>This command runs Fiji's \"Local Thickness\" plugin<br>"
			+ "to estimate radii of processes across the image.";

	@Parameter(required = false, label = "Local Thickness Documentation", callback="helpButtonPressed")
	private Button help ;

	private ImagePlus imp;

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		if (!localThicknessAvailable()) {
			error("Somehow the \"Local Thickness\" plugin was not found in your Fiji installation.");
			return;
		}
		if (!snt.accessToValidImageData()) {
			error("No valid image data exists.");
			return;
		}
		imp = snt.getImagePlus();
		if (imp == null) {
			error("No valid image data exists.");
			return;
		}
//		final Roi roi = imp.getRoi();
//		if ((roi == null || !roi.isArea()) && !new GuiUtils(ui).getConfirmation(
//				"No area ROI was detected. Proceed with analysis of full width and height of image?",
//				"Proceed Without ROI Filtering?")) {
//			return;
//		}
		// adjust prompt:
		if (imp.getNSlices() == 1) {
			resolveInput("minZ");
			resolveInput("maxZ");
		} else {
			final MutableModuleItem<Integer> maxZinput = getInfo().getMutableInput("maxZ",
					Integer.class);
			maxZinput.setMaximumValue(maxZ);
		}
		if (!snt.isSecondaryDataAvailable()) {
			resolveInput("imgChoice");
		}
		if (thres == 0) applyDefaults();
	}

	private void applyDefaults() {
		minZ = 1;
		maxZ = imp.getNSlices();
		thres = -1;
	}

	@SuppressWarnings("unused")
	private void helpButtonPressed() {
		GuiUtils.openURL("https://imagej.net/plugins/local-thickness");
	}

	@Override
	protected void error(final String msg) {
		resolveInput("minZ");
		resolveInput("maxZ");
		resolveInput("thres");
		resolveInput("defaults");
		resolveInput("imgChoice");
		resolveInput("SPACER");
		resolveInput("msg");
		resolveInput("help");
		super.error(msg);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		//TODO: local thickness is an IJ1 plugin, so we keep pretty much
		// everything here in IJ1 land. We should probably re-write this
		// further to at least rely on legacyService rather than ij.IJ;
		if (imp == null) return; // initialization requirements not fulfilled

		if (imgChoice != null && imgChoice.toLowerCase().contains("secondary"))
			imp = snt.getSecondaryDataAsImp();
		final Calibration cal = imp.getCalibration();
		ImageProbe.Result probe;
		if (imp.getNSlices() > 1) {
			minZ = Math.max(1, minZ);
			maxZ = Math.min(imp.getNSlices(), maxZ);
			imp = imp.crop("" + minZ + "-" + maxZ);
			probe = probe(imp); // before the projection, to also account for depth
			imp = ImpUtils.getMIP(imp);
		} else {
			probe = probe(imp);
		}
		// Seed the cutoff (in original intensity units, rescaled below if image is converted)
		if (thres <= 0 && probe != null && !probe.binary() && probe.backgroundThreshold() > 0) {
			thres = probe.backgroundThreshold();
			if (ui != null) ui.showStatus("Intensity cutoff estimated from image", true);
		}
		if (imp.getType() != ImagePlus.GRAY8) {
			final double originalMax = imp.getStatistics(ImageStatistics.MIN_MAX).max;
			ImpUtils.convertTo8bit(imp);
			final double scaledMax = imp.getStatistics(ImageStatistics.MIN_MAX).max;
			thres = thres * scaledMax / originalMax;
		}
		// Use default value
		if (thres <= 0) {
			if (ui!= null) ui.showStatus("Applying default intensity cutoff", true);
			thres = 128;
		}
		try {
			ij.IJ.run(imp, CMD_LABEL, "threshold=" + thres);
			imp = ij.IJ.getImage(); // may display a dialog if image not available
			imp.getProcessor().multiply( 0.5 * (cal.pixelWidth + cal.pixelHeight) / 2); // half of spatially calibrated diameters
			imp.setTitle("Estimated-Radii");
			imp.setCalibration(cal);
			crossCheck(probe, displayHistogram(imp));
		} catch (final RuntimeException ex) {
			error("An exception occurred while calling the Local Thickness plugin. " + GuiUtils.consoleHint());
			SNTUtils.error(ex);
		} finally {
			resetUI();
		}
	}

	private static ImageProbe.Result probe(final ImagePlus source) {
		try {
			return probeImp(source);
		} catch (final RuntimeException ex) {
			SNTUtils.log("Image probe failed: " + ex.getMessage());
			return null;
		}
	}

	private static <T extends RealType<T>> ImageProbe.Result probeImp(final ImagePlus source) {
		final RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice3d(source, Math.max(1, source.getChannel()),
				Math.max(1, source.getFrame()));
		final Calibration c = source.getCalibration();
		return ImageProbe.probe(rai, new double[] { c.pixelWidth, c.pixelHeight, c.pixelDepth });
	}

	// Compares the radius of the probe with the distribution of local thickness radii
	private void crossCheck(final ImageProbe.Result probe, final double[] quartiles) {
		if (probe == null || quartiles == null || Double.isNaN(probe.radius())) return;
		final double median = quartiles[1];
		final String msg = String.format(java.util.Locale.US,
				"Radii: median %.3g (IQR %.3g-%.3g). Image probe: approx. %.3g", median, quartiles[0],
				quartiles[2], probe.radius());
		final double ratio = probe.radius() / median;
		SNTUtils.log(msg);
		if (ui != null) ui.showStatus(msg + ((ratio > 2 || ratio < 0.5)
				? ". Values disagree: check the intensity cutoff and Z range" : ""), true);
	}

	// It is assumed imp is always 32-bit. Returns the 25th, 50th and 75th percentiles of the radii (or null)
	private double[] displayHistogram(final ImagePlus imp) {
		final float[] pixels = (float[]) imp.getProcessor().getPixels();
		final DescriptiveStatistics da = new DescriptiveStatistics(pixels.length);
        for (final float value : pixels) {
            if (value > 0) da.addValue(value);
        }

		final long n = da.getN();
		if (n == 0) return null;

		// Freedman-Diaconis rule
		final double iqr = da.getPercentile(75) - da.getPercentile(25);
		final double binWidth = 2 * iqr / Math.cbrt(n);
		final double min = da.getMin();
		final double max = da.getMax();
		final int nBins;
		if (binWidth == 0) {
			nBins = (int) Math.round(Math.sqrt(n));
		} else {
			nBins = (int) Math.ceil((max - min) / binWidth);
		}
		imp.setDisplayRange(min, max);
		ij.IJ.run(imp, "Histogram", "bins=" + nBins + " x_min=" + min + " x_max=" + max + " y_max=Auto");
		return new double[] { da.getPercentile(25), da.getPercentile(50), da.getPercentile(75) };

		// Now assign an ROI in case user wants to activate the Histogram live mode
//		final int size = Math.min(imp.getWidth(), imp.getHeight()) / 8;
//		final int w = imp.getWidth() / 2 - size / 2;
//		final int h = imp.getHeight() / 2 - size / 2;
//		imp.setRoi(w, h, size, size);
	}

	private boolean localThicknessAvailable() {
		try {
			ij.IJ.getClassLoader().loadClass("sc.fiji.localThickness.Local_Thickness_Driver");
		} catch (final NullPointerException | ClassNotFoundException e) {
			return ij.Menus.getCommands().get(CMD_LABEL) != null;
		}
		return true;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(LocalThicknessCmd.class, true);
	}

}
