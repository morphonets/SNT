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

package sc.fiji.snt.viewer;

import ij.ImagePlus;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.ImpUtils;

import java.util.Collection;

/**
 * Convenience entry point for rendering collections of {@link Tree}s as figures, in 2D (raster or vector) or
 * interactive 3D, as a single scene or as a multi-panel montage. Programmatic counterpart of the "Create Figure..."
 * command.
 */
public final class FigureUtils {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private FigureUtils() {}

	private static Viewer3D.ViewMode getView(final String flag) {
		final String lcFlag = flag.toLowerCase();
		if (lcFlag.contains("xz") || lcFlag.contains("zx"))
			return Viewer3D.ViewMode.XZ;
		if (lcFlag.contains("yz") || lcFlag.contains("zy"))
			return Viewer3D.ViewMode.YZ;
		return Viewer3D.ViewMode.DEFAULT;
	}

	/**
	 * Renders trees in a multi-panel montage (1 cell/pane). Trees are used as is, i.e., no transformations are applied
	 * (see {@link #render(Collection, String)} for a version that handles transformation flags).
	 *
	 * @param renderingTrees the (already transformed) trees to be rendered
	 * @param renderOptions  flags specifying the viewer type and view (see {@link #render(Collection, String)})
	 * @param display        whether the result should be displayed
	 * @return an {@link ImagePlus}, {@link MultiViewer2D} or {@link MultiViewer3D}
	 */
	public static Object montage(final Collection<Tree> renderingTrees, final String renderOptions, final boolean display) {
		final String flags = renderOptions.toLowerCase();
		if (flags.contains("skel") || flags.contains("raster") || flags.contains("image")) {
			final ImagePlus result = ImpUtils.combineSkeletons(renderingTrees, true);
			if (display) result.show();
			return result;
		} else if (flags.contains("2d") || flags.contains("vector")) {
			final MultiViewer2D result = new MultiViewer2D(renderingTrees);
			if (display) result.show();
			return result;
		} else if (flags.contains("3d") || flags.contains("interactive")) {
			final MultiViewer3D result = new MultiViewer3D(renderingTrees);
			result.setViewMode(getView(renderOptions));
			if (display) result.show();
			return result;
		} else {
			throw new IllegalArgumentException("Unrecognized option: '" + renderOptions + "'");
		}
	}

	/**
	 * Renders trees in a single scene. Trees are used as is, i.e., no transformations are applied
	 * (see {@link #render(Collection, String)} for a version that handles transformation flags).
	 *
	 * @param renderingTrees the (already transformed) trees to be rendered
	 * @param renderOptions  flags specifying the viewer type and view (see {@link #render(Collection, String)})
	 * @param display        whether the result should be displayed
	 * @return an {@link ImagePlus}, {@link Viewer2D} or {@link Viewer3D}
	 */
	public static Object singleScene(final Collection<Tree> renderingTrees, final String renderOptions, final boolean display) {
		final String flags = renderOptions.toLowerCase();
		if (flags.contains("skel") || flags.contains("raster") || flags.contains("image")) {
			final ImagePlus result = ImpUtils.combineSkeletons(renderingTrees);
			if (display) result.show();
			return result;
		} else if (flags.contains("2d") || flags.contains("view")) {
			final Viewer2D result = new Viewer2D();
			result.add(renderingTrees);
			if (display) result.show();
			return result;
		} else if (flags.contains("3d") || flags.contains("interactive")) {
			final Viewer3D result = new Viewer3D();
			result.add(renderingTrees);
			result.setViewMode(getView(renderOptions));
            result.zoomTo("visible");
			if (display) result.show();
			return result;
		} else {
			throw new IllegalArgumentException("Unrecognized option: '" + renderOptions + "'");
		}
	}

	/**
	 * Convenience method to quickly display a collection of {@link Tree}s
	 *
	 * @param trees         the collection of trees to be rendered
	 * @param renderOptions A string of flags (comma or space separated) specifying rendering options:
	 *                      Example: "montage,2d-raster,xy,zero-origin,upright-geodesic,show"
	 *                      <p>
	 *                      <b>Multi-panel flag:</b><br>
	 *                      <code>montage</code>: whether a multi-panel montage (1 cell/pane) should be obtained in
	 *                      {@link  MultiViewer2D}/{@link  MultiViewer3D} as opposed to a single-scene<br>
	 *                      <b>Viewer-type flag (only one allowed):</b><br>
	 *                      <code>2d-raster</code>: trees are rendered as 2D skeletonized images<br>
	 *                      <code>2d-vector</code>: trees are rendered in a static (non-interactive) Viewer2D<br>
	 *                      <code>3d</code>: trees are rendered in interactive Viewer3D canvas(es)<br>
	 *                      <b>View flag (only one allowed):</b><br>
	 *                      <code>xz</code>: whether trees should be displayed in a XZ view (default is XY)<br>
	 *                      <code>zy</code>: whether trees should be displayed in a ZY view (default is XY)<br>
	 *                      <b>Translation flag:</b><br>
	 *                      <code>zero-origin</code>: whether trees should be  translated so that their roots/somas are
	 *                      displayed at a common origin (0,0,0)<br>
	 *                      <b>Rotation flags (only one allowed):</b><br>
	 *                      <code>upright-geodesic</code>: whether each tree should be rotated to vertically align its graph
	 *                      geodesic<br>
	 *                      <code>upright-tips</code>: whether each tree should be rotated to vertically align its
	 *                      [root, tips centroid] vector<br>
	 *                      <code>"r#"</code>: With # specifying a positive integer (e.g., r90): each tree is rotated by the
	 *                      specified angle (in degrees)<br>
	 *                      <b>Other flags:</b><br>
	 *                      <code>in-place</code>: whether any transformation should be applied in-place (i.e., without
	 *                      duplicating the input collection)<br>
	 *                      <code>show</code>: whether the viewer should be immediately displayed
	 *                      </p>
	 * @return a reference to the viewer. Either an {@link ImagePlus}, {@link Viewer2D}, {@link Viewer3D},
	 *         {@link MultiViewer2D} or {@link MultiViewer3D}
	 * @see Tree#transform(Collection, String, boolean)
	 */
	public static Object render(final Collection<Tree> trees, final String renderOptions) {
		final String flags = renderOptions.toLowerCase();
		final Collection<Tree> renderingTrees = Tree.transform(trees, flags, flags.contains("in-place"));
		if (flags.contains("montage"))
			return montage(renderingTrees, flags, flags.contains("show"));
		else
			return singleScene(renderingTrees, flags, flags.contains("show"));
	}
}
