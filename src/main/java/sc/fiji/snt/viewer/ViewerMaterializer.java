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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import java.util.ArrayList;
import java.util.List;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import bdv.viewer.SourceAndConverter;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;

/**
 * Materialization of a region of the sources displayed in a {@link AbstractBigViewer}
 * as a real, in-memory image, for viewers that are not linked to an SNT session
 * (for those, see {@link SNT#buildMaterializedCrop(BoundingBox)}). The region is
 * read from the full resolution level of the current source, or of every source
 * (each becoming a channel), assuming that all sources share the same voxel grid
 *
 * @author Tiago Ferreira
 */
public final class ViewerMaterializer {

	private static final double MEM_FRACTION = 0.8d;

	private ViewerMaterializer() {
	}

	/**
	 * @param viewer the viewer
	 * @return the number of sources currently displayed
	 */
	public static int sourceCount(final AbstractBigViewer viewer) {
		final var state = viewer.getViewerState();
		return (state == null) ? 0 : state.getSources().size();
	}

	/**
	 * @param viewer the viewer
	 * @return the spatial calibration of the viewer's data. Unit voxels if unknown
	 */
	public static Calibration calibration(final AbstractBigViewer viewer) {
		final Calibration cal = new Calibration();
		final double[] spacing = viewer.getCalibration();
		if (spacing != null && spacing.length >= 3 && spacing[0] > 0 && spacing[1] > 0 && spacing[2] > 0) {
			cal.pixelWidth = spacing[0];
			cal.pixelHeight = spacing[1];
			cal.pixelDepth = spacing[2];
		}
		cal.setUnit((viewer.calUnit == null) ? "pixel" : BoundingBox.sanitizedUnit(viewer.calUnit));
		return cal;
	}

	/**
	 * Resolves a region in world (calibrated) coordinates to voxel indices,
	 * clamped to the extent shared by the sources in scope.
	 *
	 * @param viewer   the viewer
	 * @param worldBox the region
	 * @param all      whether every source is in scope, instead of the current one
	 * @return the voxel bounds
	 * @throws IllegalStateException    if there is no image data
	 * @throws IllegalArgumentException if the region does not overlap the data
	 */
	public static SNT.VoxelBounds resolveVoxelBounds(final AbstractBigViewer viewer, final BoundingBox worldBox,
			final boolean all) {
		final List<SourceAndConverter<?>> sources = sources(viewer, all);
		if (sources.isEmpty()) throw new IllegalStateException("No image data available to materialize from");
		final Calibration cal = calibration(viewer);
		final long[][] raw = ImgUtils.worldToVoxelBounds(worldBox, viewer.primarySourceTranslation(),
				new double[] { cal.pixelWidth, cal.pixelHeight, cal.pixelDepth });
		final long[] min = raw[0];
		final long[] max = raw[1];
		final int t = timepoint(viewer);
		boolean clamped = false;
		for (int d = 0; d < 3; d++) {
			long lo = Long.MIN_VALUE;
			long hi = Long.MAX_VALUE;
			for (final SourceAndConverter<?> sac : sources) {
				final RandomAccessibleInterval<?> rai = sac.getSpimSource().getSource(t, 0);
				lo = Math.max(lo, rai.min(d));
				hi = Math.min(hi, rai.max(d));
			}
			final long clampedMin = Math.max(min[d], lo);
			final long clampedMax = Math.min(max[d], hi);
			if (clampedMin != min[d] || clampedMax != max[d]) clamped = true;
			min[d] = clampedMin;
			max[d] = clampedMax;
			if (max[d] < min[d]) throw new IllegalArgumentException("Requested region does not overlap the data");
		}
		return new SNT.VoxelBounds(min, max, clamped);
	}

	/**
	 * @param viewer the viewer
	 * @param width  the width of the region, in voxels
	 * @param height the height of the region, in voxels
	 * @param depth  the depth of the region, in voxels
	 * @param all    whether every source is in scope, instead of the current one
	 * @return the memory needed to materialize the region, in bytes. -1 if unknown
	 */
	public static long estimateBytes(final AbstractBigViewer viewer, final long width, final long height,
			final long depth, final boolean all) {
		final List<SourceAndConverter<?>> sources = sources(viewer, all);
		if (sources.isEmpty()) return -1;
		final Object type = sources.getFirst().getSpimSource().getType();
		final int bytesPerPixel = (type instanceof UnsignedByteType) ? 1
				: (type instanceof UnsignedShortType) ? 2 : 4; // 4: float, RGB, and other types
		return width * height * depth * bytesPerPixel * sources.size();
	}

	/** @return the number of bytes available to materialize a region: a fraction of the free memory */
	public static long memoryBudget() {
		final long memMax = IJ.maxMemory();
		if (memMax <= 0) return Long.MAX_VALUE;
		return (long) (MEM_FRACTION * (memMax - IJ.currentMemory()));
	}

	/**
	 * Reads a region into a new image: single channel if the current source is
	 * in scope, multichannel otherwise. May take long, and does not need to run
	 * on the EDT. The image is neither displayed nor registered anywhere.
	 *
	 * @param viewer   the viewer
	 * @param worldBox the region, in world (calibrated) coordinates
	 * @param all      whether every source is in scope, instead of the current one
	 * @param fast     whether to copy the pixel data in a single multithreaded pass
	 *                 (falling back to a slower copy if it fails)
	 * @return the calibrated image, which holds metadata locating it in the source
	 * @throws IllegalStateException    if there is no image data
	 * @throws IllegalArgumentException if the region does not overlap the data, or
	 *                                  does not fit in memory
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static ImagePlus crop(final AbstractBigViewer viewer, final BoundingBox worldBox, final boolean all,
			final boolean fast) {
		final SNT.VoxelBounds bounds = resolveVoxelBounds(viewer, worldBox, all);
		final List<SourceAndConverter<?>> sources = sources(viewer, all);
		final long[] min = bounds.min();
		final long[] max = bounds.max();
		final long needed = estimateBytes(viewer, max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1,
				all);
		final long available = memoryBudget();
		if (needed > available) {
			throw new IllegalArgumentException(String.format(
					"The region (%.2f GB) exceeds the memory available (%.2f GB). Select a smaller region%s.",
					needed / 1e9, available / 1e9, (sources.size() > 1) ? " or fewer sources" : ""));
		}
		final Calibration cal = calibration(viewer);
		final int t = timepoint(viewer);
		final List<ImagePlus> channels = new ArrayList<>();
		for (int i = 0; i < sources.size(); i++) {
			final RandomAccessibleInterval rai = sources.get(i).getSpimSource().getSource(t, 0);
			final RandomAccessibleInterval view = Views.interval(rai, new FinalInterval(min, max));
			final String title = "C" + (i + 1);
			ImagePlus imp = null;
			if (fast) {
				try {
					imp = ImgUtils.raiToImpFast(view, title);
				} catch (final Throwable ex) {
					SNTUtils.log("raiToImpFast() failed (" + ex + "); falling back to raiToImp().duplicate()");
				}
			}
			if (imp == null) imp = ImgUtils.raiToImp(view, title).duplicate();
			imp.setCalibration(cal);
			channels.add(imp);
		}
		final ImagePlus result = (channels.size() > 1) ? ImpUtils.mergeChannels(channels) : channels.getFirst();
		result.setTitle("Materialized Region");
		result.setCalibration(cal);
		final double[] offset = viewer.primarySourceTranslation();
		ImpUtils.setSliceLabels(result.getStack(), String.format("Origin: %.2f,%.2f,%.2f", // flanked by ()
				min[0] * cal.pixelWidth + offset[0], min[1] * cal.pixelHeight + offset[1],
				min[2] * cal.pixelDepth + offset[2]));
		result.resetDisplayRange();
		final int channel = (all || sources.size() != 1) ? 1 : sourceIndex(viewer, sources.getFirst()) + 1;
		ImpUtils.setCropMetadata(result, min, channel, t + 1);
		return result;
	}

	private static int timepoint(final AbstractBigViewer viewer) {
		return viewer.getViewerState().getCurrentTimepoint();
	}

	private static int sourceIndex(final AbstractBigViewer viewer, final SourceAndConverter<?> sac) {
		final int idx = new ArrayList<>(viewer.getViewerState().getSources()).indexOf(sac);
		return Math.max(idx, 0);
	}

	private static List<SourceAndConverter<?>> sources(final AbstractBigViewer viewer, final boolean all) {
		final var state = viewer.getViewerState();
		if (state == null || state.getSources().isEmpty()) return List.of();
		if (all) return new ArrayList<>(state.getSources());
		final SourceAndConverter<?> current = viewer.getCurrentSource();
		return List.of((current != null) ? current : state.getSources().getFirst());
	}

}
