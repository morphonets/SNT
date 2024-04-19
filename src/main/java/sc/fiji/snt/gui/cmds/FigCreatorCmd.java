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

package sc.fiji.snt.gui.cmds;

import ij.ImagePlus;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.viewer.MultiViewer2D;
import sc.fiji.snt.viewer.MultiViewer3D;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.Collection;
import java.util.stream.Collectors;

@Plugin(type = Command.class, initializer = "init", label = "Create Figure...")
public class FigCreatorCmd extends CommonDynamicCmd {

	@Parameter(label = "Style:", choices = { "Single-pane", "multi-panel montage (1 cell/pane)" },
			style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String style;

	@Parameter(label = "Type:", choices = { "2D raster (bitmap) image", "2D scalable vector graphics",
			"3D " + "(interactive)" }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String type;

	@Parameter(label = "View:", choices = { "XY (default)", "XZ", "ZY" },
			style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String view;

	@Parameter(label = "Positioning:", choices = { "Absolute (original locations)",
			"Relative (somas at common origin)" }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String normalize;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg;

	@Parameter(required = true)
	Collection<Tree> trees;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (trees == null || trees.isEmpty()) {
			error("No reconstructions have been specified.");
			return;
		}
		if (trees.size() == 1) {
			msg = "NB: Only 1 reconstruction available. Montage options will be ignored...";
		} else {
			removeInput(getInfo().getInput("msg", String.class));
		}
	}

	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			error("No reconstructions have been specified.");
			return;
		}
		String transformationFlags = normalize;
		if (!type.toLowerCase().contains("3d"))
			transformationFlags += " " + view;
		final Collection<Tree> renderingTrees = getTreesForRendering(trees, transformationFlags);
		if (style.toLowerCase().contains("montage") && trees.size() > 1)
			montage(renderingTrees, type + " " + view);
		else
			render(renderingTrees, type + " " + view);
	}

	private static Viewer3D.ViewMode getView(final String flag) {
		if (flag.toLowerCase().contains("xz"))
			return Viewer3D.ViewMode.XZ;
		if (flag.toLowerCase().contains("zy"))
			return Viewer3D.ViewMode.YZ;
		return Viewer3D.ViewMode.XY;
	}

	private static Collection<Tree> getTreesForRendering(final Collection<Tree> trees, final String renderOptions) {
		final String options = renderOptions.toLowerCase();
		final boolean isZY = options.contains("zy");
		final boolean isXZ = options.contains("xz");
		final boolean center = options.contains("norm") || options.contains("relative") || options.contains("center");
		final Collection<Tree> renderingTrees;
		if (center || isZY || isXZ) {
			renderingTrees = trees.stream().map(Tree::clone).collect(Collectors.toList());
			renderingTrees.forEach(tree -> {
				if (center) {
					final PointInImage root = tree.getRoot();
					tree.translate(-root.getX(), -root.getY(), -root.getZ());
				}
				if (isZY)
					tree.swapAxes(Tree.X_AXIS, Tree.Z_AXIS);
				else if (isXZ)
					tree.swapAxes(Tree.Y_AXIS, Tree.Z_AXIS);
			});
		} else {
			renderingTrees = trees;
		}
		return renderingTrees;
	}

	private static Object montage(final Collection<Tree> renderingTrees, final String renderOptions) {
		final String flags = renderOptions.toLowerCase();
		if (flags.contains("skel") || flags.contains("raster") || flags.contains("image")) {
			final ImagePlus result = ImpUtils.combineSkeletons(renderingTrees, true);
			result.show();
			return result;
		} else if (flags.contains("2d") || flags.contains("vector")) {
			final MultiViewer2D result = new MultiViewer2D(renderingTrees);
			result.show();
			return result;
		} else if (flags.contains("3d") || flags.contains("interactive")) {
			final MultiViewer3D result = new MultiViewer3D(renderingTrees);
			result.setViewMode(getView(renderOptions));
			result.show();
			return result;
		} else {
			throw new IllegalArgumentException("Unrecognized option: '" + renderOptions + "'");
		}
	}

	private static Object singleScene(final Collection<Tree> renderingTrees, final String renderOptions) {
		final String flags = renderOptions.toLowerCase();
		if (flags.contains("skel") || flags.contains("raster") || flags.contains("image")) {
			final ImagePlus result = ImpUtils.combineSkeletons(renderingTrees);
			result.show();
			return result;
		} else if (renderOptions.contains("2d") || renderOptions.contains("view")) {
			final Viewer2D result = new Viewer2D();
			result.add(renderingTrees);
			result.show();
			return result;
		} else if (renderOptions.contains("3d")) {
			final Viewer3D result = new Viewer3D();
			result.add(renderingTrees);
			result.setViewMode(getView(renderOptions));
			result.show();
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
	 *                      <p>
	 *                      <tt>montage</tt>: whether a multi-panel montage (1 cell/pane) should be obtained in
	 *                      {@link  MultiViewer2D}/{@link  MultiViewer3D} as opposed to a single-scene<br>
	 *                      <tt>2d raster</tt>: trees are rendered as 2D skeletonized images<br>
	 *                      <tt>2d vector</tt>: trees are rendered in a static (non-interactive) Viewer2D<br>
	 *                      <tt>3d</tt>: trees are rendered in interactive Viewer3D canvas(es)<br>
	 *                      <tt>xz</tt>: whether trees should be rendered in a XZ view (default is XY)<br>
	 *                      <tt>zy</tt>: whether trees should be rendered in a ZY view (default is XY)<br>
	 *                      <tt>center</tt>: whether trees should be  translated so that their roots/somas are displayed at a
	 *                      common origin (0,0,0)
	 *                     </p>
	 */
	public static Object render(final Collection<Tree> trees, final String renderOptions) {
		final String flags = renderOptions.toLowerCase();
		final Collection<Tree> renderingTrees = getTreesForRendering(trees, flags);
		if (flags.contains("montage"))
			return montage(renderingTrees, flags);
		else
			return singleScene(renderingTrees, flags);
	}
}
