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
import org.scijava.module.ModuleItem;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.ColorMapper;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.viewer.MultiViewer2D;
import sc.fiji.snt.viewer.MultiViewer3D;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.ArrayList;
import java.util.Collection;

@Plugin(type = Command.class, initializer = "init", label = "Create Figure...")
public class FigCreatorCmd extends CommonDynamicCmd {

	@Parameter(label = "Style:", choices = { "Single-pane", "multi-panel montage (1 cell/pane)" },
			style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String style;

	@Parameter(label = "Type:", choices = { "2D raster (bitmap image)", "2D scalable (vector graphics)",
			"3D (interactive)" }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String type;

	@Parameter(label = "View:", choices = { "XY (default)", "XZ", "ZY" },
			style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String view;

	@Parameter(label = "Positioning:", choices = { "Absolute (original coordinates)",
			"Relative (translate soma(s) to common origin)" }, style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String rootTranslation;

	@Parameter(label = "Rotation:", choices = { "None", "Rotate to upright orientation (longest geodesic)",
			"Rotate to upright orientation (tips)"},
			description = "<HTML>Tries to rotate the arbor to a 'vertical' position." +
					"<dl>" +
					"<dt>None</dt>" +
					"<dd>No rotation is performed</dd>" +
					"<dt>Longest geodesic</dt>" +
					"<dd>Assumes the longest shortest path in the arbor reflects its overall orientation</dd>" +
					"<dt>Tips</dt>" +
					"<dd>Assumes the vector defined by the soma and the centroid of all tips in the <br>" +
					"arbor reflects its overall orientation</dd></dl>",
			style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE)
	private String uprightRotation;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg;

	@Parameter(required = true)
	Collection<Tree> trees;

	@Parameter(required = false)
	ColorMapper mapper;

	@Parameter(required = false)
	boolean noRasterOutput;

	@Parameter(required = false)
	boolean noGeodesicTransformation;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (trees == null || trees.isEmpty()) {
			error("No reconstructions have been specified.");
			return;
		}
		if (trees.size() == 1) {
			final ModuleItem<String> mi = getInfo().getInput("style", String.class);
			mi.setValue(this, mi.getChoices().get(0));
			msg = "NB: Only 1 reconstruction available. Montage options will be ignored...";
		} else {
			removeInput(getInfo().getInput("msg", String.class));
		}
		if (noRasterOutput) {
			final MutableModuleItem<String> mi = (MutableModuleItem<String>) getInfo().getInput("type", String.class);
			final ArrayList<String> choices = new ArrayList<>(mi.getChoices());
			choices.remove(0);
			mi.setChoices(choices);
		}
		if (noGeodesicTransformation) {
			final MutableModuleItem<String> mi = (MutableModuleItem<String>) getInfo().getInput("uprightRotation", String.class);
			final ArrayList<String> choices = new ArrayList<>(mi.getChoices());
			choices.remove(1);
			mi.setChoices(choices);
		}
	}


	@Override
	public void run() {
		if (trees == null) {
			error("No reconstructions have been specified.");
			return;
		}
		try {
			trees.removeIf(tree -> tree == null || tree.isEmpty());
		} catch (final UnsupportedOperationException ignored) {
			// do nothing: ImmutableCollection
		}
		if (trees.isEmpty()) { // may happen with auto-traced structures!?
			error("No valid reconstructions exist.");
			return;
		}
		String transformationFlags = "";
		if (rootTranslation.toLowerCase().contains("relative"))
			transformationFlags += "zero-origin";
		if (uprightRotation.toLowerCase().contains("upright"))
			transformationFlags += " upright";
		if (uprightRotation.toLowerCase().contains("tips"))
			transformationFlags += " tips";
		if (!type.toLowerCase().contains("3d"))
			transformationFlags += " " + view;
		final Collection<Tree> renderingTrees = Tree.transform(trees, transformationFlags, false);
		final Object result;
		final boolean montage = style.toLowerCase().contains("montage") && trees.size() > 1;
		if (montage)
			result = montage(renderingTrees, type + " " + view, false);
		else
			result = singleScene(renderingTrees, type + " " + view, false);
		if (result instanceof ImagePlus) {
			((ImagePlus) result).show();
			final int minSize = (montage) ? 200 / trees.size() : 200;
			if (((ImagePlus) result).getWidth() < minSize || ((ImagePlus) result).getHeight() < minSize) {
			msg("Created figure may not have enough detail (paths were digitized at 1µm/pixel). It " +
					"may be preferable to use scalable vector graphics instead.", "Coarse Result?");
			}
		} else if (result instanceof Viewer2D) {
			if (mapper != null) ((Viewer2D) result).addColorBarLegend(mapper);
			((Viewer2D) result).show();
		} else if (result instanceof MultiViewer2D) {
			if (mapper != null) ((MultiViewer2D) result).setColorBarLegend(mapper);
			((MultiViewer2D) result).show();
		} else if (result instanceof Viewer3D) {
			if (mapper != null) ((Viewer3D) result).addColorBarLegend(mapper);
			((Viewer3D) result).show();
		} else if (result instanceof MultiViewer3D) {
			if (mapper != null) ((MultiViewer3D) result).addColorBarLegend(mapper);
			((MultiViewer3D) result).show();
		}
		resetUI();
	}

	private static Viewer3D.ViewMode getView(final String flag) {
		final String lcFlag = flag.toLowerCase();
		if (lcFlag.contains("xz") || lcFlag.contains("zx"))
			return Viewer3D.ViewMode.XZ;
		if (lcFlag.contains("yz") || lcFlag.contains("zy"))
			return Viewer3D.ViewMode.YZ;
		return Viewer3D.ViewMode.DEFAULT;
	}

	private static Object montage(final Collection<Tree> renderingTrees, final String renderOptions, final boolean display) {
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

	private static Object singleScene(final Collection<Tree> renderingTrees, final String renderOptions, final boolean display) {
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
	 *                      <p>
	 *                      <tt>montage</tt>: whether a multi-panel montage (1 cell/pane) should be obtained in
	 *                      {@link  MultiViewer2D}/{@link  MultiViewer3D} as opposed to a single-scene<br>
	 *                      <tt>2d raster</tt>: trees are rendered as 2D skeletonized images<br>
	 *                      <tt>2d vector</tt>: trees are rendered in a static (non-interactive) Viewer2D<br>
	 *                      <tt>3d</tt>: trees are rendered in interactive Viewer3D canvas(es)<br>
	 *                      <tt>xz</tt>: whether trees should be displayed in a XZ view (default is XY)<br>
	 *                      <tt>zy</tt>: whether trees should be displayed in a ZY view (default is XY)<br>
	 *                      <tt>zero-origin</tt>: whether trees should be  translated so that their roots/somas are displayed
	 *                      at a common origin (0,0,0)<br>
	 *                      <tt>upright</tt>: whether each tree should be rotated to vertically align its graph geodesic
	 *                      <tt>upright:centroid</tt>: whether each tree should be rotated to vertically align its root-centroid vector
	 *                      </p>
	 * @return a reference to the displayed viewer
	 * @see Tree#transform(Collection, String, boolean)
	 */
	public static Object render(final Collection<Tree> trees, final String renderOptions) {
		final String flags = renderOptions.toLowerCase();
		final Collection<Tree> renderingTrees = Tree.transform(trees, flags, false);
		if (flags.contains("montage"))
			return montage(renderingTrees, flags, true);
		else
			return singleScene(renderingTrees, flags, true);
	}
}
