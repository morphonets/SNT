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

package sc.fiji.snt.tracing.auto;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInCanvas;

/**
 * Derives an {@link AutoTraceConfig} from example paths (or reconstruction files), applying the safeguards shared by
 * the commands and wizards that learn from examples: the examples must match the image, and their number is capped
 * as fitting radii to many paths is slow, even more so for lazily loaded images. May take long: do not call on the
 * EDT
 *
 * @author Tiago Ferreira
 * @see AutoTraceConfig#fromPaths
 */
public final class AutoTraceTrainer {

	/** Examples beyond this number (longest first) are ignored */
	public static final int MAX_EXAMPLE_PATHS = 200;
	/** Same, for streamed images */
	public static final int MAX_EXAMPLE_PATHS_STREAMED = 30;

	private AutoTraceTrainer() {
	}

	/**
	 * The outcome of a training
	 *
	 * @param config the derived configuration
	 * @param nPaths the number of example paths that were used
	 */
	public record Result(AutoTraceConfig config, int nPaths) {
	}

	/**
	 * Trains on the image traced in an SNT session (primary data, active channel and frame)
	 *
	 * @param snt      the session
	 * @param examples the example paths (null if files are specified)
	 * @param files    the reconstruction files (ignored if examples are specified)
	 * @return the result
	 * @throws IllegalStateException    if there is no image data
	 * @throws IllegalArgumentException if the image is not an intensity image, if there are no paths, or if the
	 *                                  paths read from files do not match the image
	 */
	public static Result train(final SNT snt, final List<Path> examples, final List<File> files) {
		final ImgPlus<?> img = snt.getLoadedDataAsImg(false);
		if (img == null) throw new IllegalStateException("No valid image available.");
		// Not checked for streamed images: scanning for unique values may read much of the data
		if (!isStreamedSource(snt) && ImgUtils.isBinary(img)) throw new IllegalArgumentException("The image is "
				+ "binary: there are no intensity values to learn from.");
		@SuppressWarnings("unchecked") final RandomAccessibleInterval<? extends RealType<?>> source =
				(RandomAccessibleInterval<? extends RealType<?>>) img;
		return train(source, ImgUtils.getSpacing(img), voxelOffset(snt), isStreamedSource(snt), examples, files);
	}

	/**
	 * Trains on an image
	 *
	 * @param source      the intensity image
	 * @param spacing     voxel dimensions [x, y, z] in physical units
	 * @param voxelOffset offset [x, y, z], in voxels, between the coordinate frame of the paths and the grid of the
	 *                    image (null: none), see {@link #voxelOffset(SNT)}
	 * @param streamed    whether the image is a lazily loaded stream
	 * @param examples    the example paths (null if files are specified)
	 * @param files       the reconstruction files (ignored if examples are specified)
	 * @return the result
	 * @throws IllegalArgumentException if there are no paths, or if the paths read from files do not match the image
	 */
	public static Result train(final RandomAccessibleInterval<? extends RealType<?>> source, final double[] spacing,
							   final double[] voxelOffset, final boolean streamed, final List<Path> examples,
							   final List<File> files) {
		final boolean fromFiles = examples == null;
		List<Path> paths = fromFiles ? loadPaths(files) : examples;
		if (paths.isEmpty()) throw new IllegalArgumentException("No paths could be read from the file(s).");
		final double inside = AutoTraceConfig.fractionInBounds(paths, source, spacing, voxelOffset);
		if (fromFiles && (Double.isNaN(inside) || inside < 0.5)) {
			throw new IllegalArgumentException(String.format("Only %.0f%% of the nodes fall within the image: "
					+ "the reconstruction(s) do not seem to match it.", 100 * (Double.isNaN(inside) ? 0 : inside)));
		}
		final int maxPaths = streamed ? MAX_EXAMPLE_PATHS_STREAMED : MAX_EXAMPLE_PATHS;
		final int total = paths.size();
		if (total > maxPaths) {
			paths = new ArrayList<>(paths);
			paths.sort((p1, p2) -> Double.compare(p2.getLength(), p1.getLength()));
			paths = paths.subList(0, maxPaths);
		}
		final int nPaths = paths.size();
		final AutoTraceConfig config = AutoTraceConfig.fromPaths(paths, source, spacing, voxelOffset, streamed);
		if (!Double.isNaN(inside) && inside < 0.95) {
			config.addWarning(String.format("Only %.0f%% of the example nodes fall within the image: values "
					+ "sampled from it may be unreliable.", 100 * inside));
		}
		if (total > nPaths) {
			config.addWarning("Using the " + nPaths + " longest of " + total + " example paths"
					+ (streamed ? " (the image is streamed: reading it is slow)." : "."));
		}
		return new Result(config, nPaths);
	}

	/**
	 * Fraction of the nodes of paths that fall within the image traced in an SNT session
	 *
	 * @param snt   the session
	 * @param paths the paths
	 * @return the fraction (0-1). NaN if it could not be determined, e.g., if there is no image data
	 */
	public static double fractionInBounds(final SNT snt, final List<Path> paths) {
		final ImgPlus<?> img = snt.getLoadedDataAsImg(false);
		if (img == null || paths == null || paths.isEmpty()) return Double.NaN;
		@SuppressWarnings("unchecked") final RandomAccessibleInterval<? extends RealType<?>> source =
				(RandomAccessibleInterval<? extends RealType<?>>) img;
		return AutoTraceConfig.fractionInBounds(paths, source, ImgUtils.getSpacing(img), voxelOffset(snt));
	}

	/**
	 * Paths are in true-world coordinates, but the image is not necessarily anchored at world origin: it may be a
	 * materialized crop, or a stream with a world origin offset
	 *
	 * @param snt the session
	 * @return the offset [x, y, z], in voxels, between the coordinate frame of the paths and the grid of the image
	 */
	public static double[] voxelOffset(final SNT snt) {
		final double[] offset = new double[3];
		final PointInCanvas o = (snt == null) ? null : snt.getActiveCanvasPixelOffset();
		if (o != null) {
			offset[0] = o.x;
			offset[1] = o.y;
			offset[2] = o.z;
		}
		return offset;
	}

	/**
	 * @param snt the session
	 * @return whether the image traced is a (lazily loaded) stream, i.e., not a materialized crop
	 */
	public static boolean isStreamedSource(final SNT snt) {
		return snt != null && snt.isStreamMode() && !snt.isMaterializedCrop();
	}

	/**
	 * @param files reconstruction files
	 * @return all the paths in the files
	 */
	public static List<Path> loadPaths(final List<File> files) {
		final List<Path> paths = new ArrayList<>();
		for (final File f : files) {
			for (final Tree tree : Tree.listFromFile(f.getAbsolutePath())) paths.addAll(tree.list());
		}
		return paths;
	}

}
