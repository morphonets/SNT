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

package sc.fiji.snt.util;

import java.io.File;
import java.util.Collection;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ContrastEnhancer;
import ij.plugin.Duplicator;
import ij.plugin.ImagesToStack;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.ImageConverter;
import ij.process.StackConverter;

/**
 * Static utilities for handling and manipulation of {@link ImagePlus}s
 *
 * @author Tiago Ferreira
 *
 */
public class ImpUtils {

	private ImpUtils() {} // prevent class instantiation

	public static void removeIsolatedPixels(final ImagePlus binaryImp) {
		final ImageStack stack = binaryImp.getStack();
		for (int i = 1; i <= stack.getSize(); i++)
			((ByteProcessor) stack.getProcessor(i)).erode(8, 0);
	}

	public static ImagePlus getMIP(final ImagePlus imp) {
		final ImagePlus mip = ZProjector.run(imp, "max");
		if (mip.getNChannels() == 1)
			mip.setLut(imp.getLuts()[0]); // assume single-channel image
		mip.copyScale(imp);
		new ContrastEnhancer().stretchHistogram(mip, 0.35);
		return mip;
	}

	public static ImagePlus getMIP(final Collection<ImagePlus> imps) {
		return getMIP(toStack(imps));
	}

	public static ImagePlus toStack(final Collection<ImagePlus> imps) {
		return ImagesToStack.run(imps.toArray(new ImagePlus[0]));
	}

	public static void convertTo32bit(final ImagePlus imp) throws IllegalArgumentException {
		if (imp.getBitDepth() == 32)
			return;
		if (imp.getNSlices() == 1)
			new ImageConverter(imp).convertToGray32();
		else
			new StackConverter(imp).convertToGray32();
	}

	public static void convertTo8bit(final ImagePlus imp) {
		if (imp.getType() != ImagePlus.GRAY8) {
			final boolean doScaling = ImageConverter.getDoScaling();
			ImageConverter.setDoScaling(true);
			new ImageConverter(imp).convertToGray8();
			ImageConverter.setDoScaling(doScaling);
		}
	}

	public static ImagePlus open(final File file) {
		return open(file, null);
	}

	public static ImagePlus open(final File file, final String title) {
		final ImagePlus imp = IJ.openImage(file.getAbsolutePath());
		if (title != null)
			imp.setTitle(title);
		return imp;
	}

	public static boolean sameXYZDimensions(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getWidth() == imp2.getWidth() && imp1.getHeight() == imp2.getHeight()
				&& imp1.getNSlices() == imp2.getNSlices();
	}

	public static boolean sameCTDimensions(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getNChannels() == imp2.getNChannels() && imp2.getNFrames() == imp1.getNFrames();
	}

	public static boolean sameCalibration(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getCalibration().equals(imp2.getCalibration());
	}

	public static ImagePlus getFrame(final ImagePlus imp, final int frame) {
		imp.deleteRoi(); // will call saveRoi
		final ImagePlus extracted = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), frame, frame);
		extracted.setCalibration(imp.getCalibration());
		imp.restoreRoi();
		extracted.setRoi(imp.getRoi());
		extracted.setProp("extracted-frame", frame);
		return extracted;
	}
	
	public static ImagePlus getChannel(final ImagePlus imp, final int channel) {
		imp.deleteRoi(); // will call saveRoi
		final ImagePlus extracted = new Duplicator().run(imp, channel, channel, 1, imp.getNSlices(), 1, imp.getNFrames());
		extracted.setCalibration(imp.getCalibration());
		imp.restoreRoi();
		extracted.setRoi(imp.getRoi());
		extracted.setProp("extracted-channel", channel);
		return extracted;
	}

}
