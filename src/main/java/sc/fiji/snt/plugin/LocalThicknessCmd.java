/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImpUtils;

/**
 * Convenience command for running Fiji's "Local Thickness" plugin from SNT
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
					+ "value (half of image max)")
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
	protected final void error(final String msg) {
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
		if (imp.getNSlices() > 1) {
			minZ = Math.max(1, minZ);
			maxZ = Math.min(imp.getNSlices(), maxZ);
			imp = imp.crop("" + minZ + "-" + maxZ);
			imp = ImpUtils.getMIP(imp);
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
			displayHistogram(imp);
		} catch (final RuntimeException ex) {
			error("An exception occured while calling the Local Thickness plugin. See Console for details");
			ex.printStackTrace();
		} finally {
			resetUI();
		}
	}

	private void displayHistogram(final ImagePlus imp) { // it is assumed imp is always 32-bit
		final float[] pixels = (float[]) imp.getProcessor().getPixels();
		final DescriptiveStatistics da = new DescriptiveStatistics(pixels.length);
		for (int i = 0; i < pixels.length; i++) {
			final float value = pixels[i];
			if (value > 0) da.addValue(value);
		}

		final long n = da.getN();
		if (n == 0) return;

		// Freedman–Diaconis rule
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
