/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import net.imagej.ImageJ;
import net.imglib2.RealLocalizable;
import net.imglib2.display.ColorTable;
import net.imglib2.roi.geom.real.Polygon2D;
import org.jfree.chart.JFreeChart;
import org.scijava.Context;
import org.scijava.plot.*;
import org.scijava.plot.defaultplot.DefaultPlotService;
import org.scijava.plugin.Parameter;
import org.scijava.ui.swing.viewer.plot.jfreechart.XYPlotConverter;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.ColorMapper;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;

import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * Class for rendering {@link Tree}s as 2D plots that can be exported as SVG,
 * PNG or PDF.
 *
 * @author Tiago Ferreira
 */
public class Viewer2D extends TreeColorMapper {

	@Parameter
	private PlotService plotService;

	protected XYPlot plot;
	private String title;
	private SNTChart chart;
	private ColorRGB defaultColor = Colors.BLACK;
	private boolean visibleAxes;
	private boolean visibleGridLines;
	private boolean visibleOutline;
    private boolean equalizeAxes;

	/**
	 * Instantiates an empty 2D viewer using SNT's default context.
	 */
	public Viewer2D() {
		this(SNTUtils.getContext());
	}

	/**
	 * Instantiates an empty 2D viewer.
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the viewer.
	 */
	public Viewer2D(final Context context) {
		this(context, null);
	}

	/**
	 * Instantiates an empty 2D viewer.
	 *
	 * @param context  the SciJava application context providing the services
	 *                 required by the viewer.
	 * @param template a viewer instance from which properties (axes visibility,
	 *                 title, etc.) will be applied
	 */
	public Viewer2D(final Context context, final Viewer2D template) {
		super(context);
		if (template == null) {
			setAxesVisible(true);
			setGridlinesVisible(false);
			setOutlineVisible(true);
            setEqualizeAxes(true);
        } else {
			setAxesVisible(template.getAxesVisible());
			setGridlinesVisible(template.getGridlinesVisible());
			setOutlineVisible(template.getOutlineVisible());
            setEqualizeAxes(template.getEqualizeAxes());
			setTitle(template.getTitle());
			setDefaultColor(template.defaultColor);
		}
	}

	private void addPaths(final ArrayList<Path> paths) {
		this.paths = paths;
		plotPaths();
	}

	private void initPlot() {
		if (plot == null) {
			if (plotService == null) plotService = new DefaultPlotService();
			plot = plotService.newXYPlot();
		}
	}

	public void addPolygon(final Polygon2D poly, final String label) {
		initPlot();
		final XYSeries series = plot.addXYSeries();
		series.setLabel(label);
		final List<Double> xc = new ArrayList<>();
		final List<Double> yc = new ArrayList<>();
		for (RealLocalizable l : poly.vertices()) {
			xc.add(l.getDoublePosition(0));
			yc.add(l.getDoublePosition(1));
		}
		// Close the polygon
		xc.add(poly.vertex(0).getDoublePosition(0));
		yc.add(poly.vertex(0).getDoublePosition(1));
		series.setValues(xc, yc);
		series.setLegendVisible(false);
		final ColorRGB color = defaultColor;
		series.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID, MarkerStyle.NONE));
	}

	private void plotPaths() {
		if (paths == null || paths.isEmpty()) {
			throw new IllegalArgumentException("No paths to plot");
		}
		initPlot();
		for (final Path p : paths) {
			final XYSeries series = plot.addXYSeries();
			series.setLegendVisible(false);
			series.setLabel(p.getName());
			final List<Double> xc = new ArrayList<>();
			final List<Double> yc = new ArrayList<>();
			if (p.getBranchPoint() != null) {
				xc.add(p.getBranchPoint().x);
				yc.add(p.getBranchPoint().y);
			}
			for (int node = 0; node < p.size(); node++) {
				final PointInImage pim = p.getNode(node);
				xc.add(pim.x);
				yc.add(pim.y);
			}
			series.setValues(xc, yc);
			final ColorRGB color = (p.getColor() == null) ? defaultColor
				: new ColorRGB(p.getColor().getRed(), p.getColor().getGreen(), p
					.getColor().getBlue());
			series.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID,
				MarkerStyle.NONE));
		}
		plotColoredNodePathsFast(); // will do nothing if no color nodes exist
	}

	private Map<Color, List<PointInImage>> getNodesColorMapFast() {
		final Map<Color, List<PointInImage>> map = new HashMap<>();
		for (final Path p : paths) {
			if (!p.hasNodeColors()) continue;
			final Color[] pathColors = p.getNodeColors();
			for (int node = 0; node < pathColors.length; node++) {
				if (pathColors[node] == null)
					continue;
				final PointInImage pim = p.getNode(node);
				if (map.get(pathColors[node]) == null) {
					final List<PointInImage> pims = new ArrayList<>();
					pims.add(pim);
					map.put(pathColors[node], pims);
				} else {
					map.get(pathColors[node]).add(pim);
				}
			}
		}
		return map;
	}

	private void plotColoredNodePathsFast() {
		getNodesColorMapFast().forEach( (c, pimList) -> {
			final XYSeries series = plot.addXYSeries();
			series.setLegendVisible(false);
			series.setLabel(c.toString());
			final List<Double> xc = new ArrayList<>();
			final List<Double> yc = new ArrayList<>();
			pimList.forEach( pim -> {
				xc.add(pim.getX());
				yc.add(pim.getY());
			});
			series.setValues(xc, yc);
			final ColorRGB cc = new ColorRGB(c.getRed(), c.getGreen(), c.getBlue());
			series.setStyle(plotService.newSeriesStyle(cc, LineStyle.NONE,
				MarkerStyle.FILLEDCIRCLE));
		});
	}

	protected void addColorBarLegend(final String colorTable, final double min,
			final double max)
		{
		addColorBarLegend(getColorTable(colorTable), min, max);
		}

	/**
	 * Adds a color bar legend (LUT ramp).
	 *
	 * @param colorTable the color table
	 * @param min the minimum value in the color table
	 * @param max the maximum value in the color table
	 */
	public void addColorBarLegend(final ColorTable colorTable, final double min,
		final double max)
	{
		final double previousMin = this.min;
		final double previousMax = this.max;
		final ColorTable previousColorTable = this.colorTable;
		this.min = min;
		this.max = max;
		this.colorTable = colorTable;
		addColorBarLegend();
		this.min = previousMin;
		this.max = previousMax;
		this.colorTable = previousColorTable;
	}

	/**
	 * Adds a color bar legend (LUT ramp) from a {@link ColorMapper}.
	 *
	 * @param colorMapper the class extending ColorMapper ({@link TreeColorMapper}, etc.)
	 */
	public <T extends ColorMapper> void addColorBarLegend(final T colorMapper)
	{
		final double[] minMax = colorMapper.getMinMax();
		final boolean prevIntegerScale = integerScale;
		this.integerScale = colorMapper.isIntegerScale();
		addColorBarLegend(colorMapper.getColorTable(), minMax[0], minMax[1]);
		this.integerScale = prevIntegerScale;
	}

	/**
	 * Adds a color bar legend (LUT ramp) to the viewer. Does nothing if no
	 * measurement mapping occurred successfully. Note that when performing
	 * mapping to different measurements, the legend reflects only the last mapped
	 * measurement.
	 */
	public void addColorBarLegend() {
		if (min >= max || colorTable == null) {
			return;
		}
		chart = getChart();
		chart.addColorBarLegend(null, colorTable, min, max, (integerScale) ? 0 : 2);
	}

	/**
	 * Appends a tree to the viewer using default options.
	 * 
	 * @deprecated Use {@link #add(Object)} instead
	 * @param tree the Collection of paths to be plotted
	 */
	@Deprecated 
	public void addTree(final Tree tree) {
		add(tree);
	}

	/**
	 * Adds a collection of trees. Each tree will be rendered using a unique color.
	 *
	 * @param trees the list of trees to be plotted
	 * @deprecated use {@link #add(Object)} instead
	 */
	@Deprecated
	public void addTrees(final Collection<Tree> trees) {
		add(trees);
	}

	private void addTrees(final Collection<Tree> trees, final ColorRGB color) {
		final ColorRGB[] colors;
		if (color == null) {
			colors = SNTColor.getDistinctColors(trees.size());
		} else {
			colors = new ColorRGB[trees.size()];
			Arrays.fill(colors, color);
		}
		final ColorRGB prevDefaultColor = defaultColor;
		int i = 0;
        for (Tree tree : trees) {
            if (colors != null) setDefaultColor(colors[i++]);
            add(tree);
        }
		setDefaultColor(prevDefaultColor);
	}

	/**
	 * Appends a single tree, a single Polygon2D, a collection of Trees, or a collection of Polygon2D to the viewer
	 * using default options.
	 *
	 * @param object the tree(s) or Polygon2D(s) to be added
	 */
	@SuppressWarnings("unchecked")
	public void add(final Object object) {
		if (object instanceof Collection ) {
			if (!((Collection<?>) object).isEmpty() && ((Collection<?>) object).iterator().next() instanceof Polygon2D) {
				addPolygons((Collection<Polygon2D>) object);
			} else if (!((Collection<?>) object).isEmpty() && ((Collection<?>) object).iterator().next() instanceof Tree) {
				addTrees((Collection<Tree>) object, null);
			}
		}
		else if (object instanceof Tree) {
			addPaths(((Tree) object).list());
		}
		else if (object instanceof Polygon2D) {
			addPolygon((Polygon2D) object, "");
		}
	}

	private void addPolygons(final Collection<Polygon2D> polys) {
		final ColorRGB[] colors = SNTColor.getDistinctColors(polys.size());
		final ColorRGB prevDefaultColor = defaultColor;
		int i = 0;
        for (final Polygon2D poly : polys) {
            setDefaultColor(colors[i++]);
            addPolygon(poly, "CH" + (i + 1));
        }
		setDefaultColor(prevDefaultColor);
	}
	/**
	 * Adds a list of trees while assigning each tree to a LUT index.
	 *
	 * @param trees the list of trees to be plotted
	 * @param lut the lookup table specifying the color mapping
	 */
	public void add(final List<Tree> trees, final String lut) {
		mapTrees(trees, lut);
        for (final Tree tree : trees) {
            addTree(tree);
        }
	}

	/**
	 * Appends a tree to the viewer.
	 *
	 * @param tree the Collection of paths to be plotted
	 * @param color the color to render the Tree
	 */
	public void add(final Tree tree, final ColorRGB color) {
		final ColorRGB prevDefaultColor = defaultColor;
		setDefaultColor(color);
		addPaths(tree.list());
		setDefaultColor(prevDefaultColor);
	}

	/**
	 * Appends a tree to the viewer.
	 *
	 * @param tree  the Collection of paths to be plotted
	 * @param color a string representation of the color to render the Tree
	 */
	public void add(final Tree tree, final String color) {
		add(tree, new ColorRGB(color));
	}

	/**
	 * Appends a tree to the viewer rendered after the specified measurement.
	 *
	 * @param tree the tree to be plotted
	 * @param measurement the measurement ({@link #PATH_ORDER} }{@link #PATH_LENGTH},
	 *          etc.)
	 * @param colorTable the color table specifying the color mapping
	 * @param min the mapping lower bound (i.e., the highest measurement value for
	 *          the LUT scale)
	 * @param max the mapping upper bound (i.e., the highest measurement value for
	 *          the LUT scale)
	 */
	public void add(final Tree tree, final String measurement,
		final ColorTable colorTable, final double min, final double max)
	{
		this.paths = tree.list();
		setMinMax(min, max);
		mapToProperty(measurement, colorTable);
		plotPaths();
	}

	/**
	 * Appends a tree to the viewer rendered after the specified measurement.
	 * Mapping bounds are automatically determined.
	 *
	 * @param tree the tree to be plotted
	 * @param measurement the measurement ({@link #PATH_ORDER} }{@link #PATH_LENGTH},
	 *          etc.)
	 * @param lut the lookup table specifying the color mapping
	 */
	public void add(final Tree tree, final String measurement,
		final String lut)
	{
		add(tree, measurement, getColorTable(lut), Double.NaN, Double.NaN);
	}

	/**
	 * Appends a tree to the viewer rendered after the specified measurement.
	 *
	 * @param tree the tree to be plotted
	 * @param measurement the measurement ({@link #PATH_ORDER} }{@link #PATH_LENGTH},
	 *          etc.)
	 * @param lut the lookup table specifying the color mapping
	 * @param min the mapping lower bound (i.e., the highest measurement value for
	 *          the LUT scale)
	 * @param max the mapping upper bound (i.e., the highest measurement value for
	 *          the LUT scale)
	 */
	public void add(final Tree tree, final String measurement,
		final String lut, final double min, final double max)
	{
		add(tree, measurement, getColorTable(lut), min, max);
	}

	/**
	 * Gets the current viewer as a {@link JFreeChart} object
	 *
	 * @return the converted viewer
	 */
	public JFreeChart getJFreeChart() {
		return getChart().getChart();
	}

	/**
	 * Gets the current viewer as a {@link SNTChart} object
	 *
	 * @return the converted viewer
	 */
	public SNTChart getChart() {
		if (chart == null) {
			initPlot();
			final XYPlotConverter converter = new XYPlotConverter();
			chart = new SNTChart(
					(getTitle() == null || getTitle().trim().isEmpty()) ? "Reconstruction Plotter" : getTitle(),
					converter.convert(plot, JFreeChart.class));
		}
		chart.setEqualizeAxes(getEqualizeAxes());
		chart.setAxesVisible(getAxesVisible());
		chart.setOutlineVisible(getOutlineVisible());
		chart.setGridlinesVisible(getGridlinesVisible());
		return chart;
	}

	/**
	 * Gets the current plot as a {@link XYPlot} object
	 *
	 * @return the current plot
	 */
	public XYPlot getPlot() {
		initPlot();
		plot.yAxis().setAutoRange();
		plot.xAxis().setAutoRange();
		return plot;
	}

	/**
	 * Sets the default (fallback) color for plotting paths.
	 *
	 * @param color null not allowed
	 */
	public void setDefaultColor(final ColorRGB color) {
		if (color != null) defaultColor = color;
	}

	/**
	 * Sets the preferred size of the plot to a constant value.
	 *
	 * @param width the preferred width
	 * @param height the preferred height
	 */
	public void setPreferredSize(final int width, final int height) {
		initPlot();
		plot.setPreferredSize(width, height);
	}

	/**
	 * Sets the plot display title.
	 *
	 * @param title the new title
	 */
	public void setTitle(final String title) {
		if (chart != null && chart.getFrame() != null) {
			chart.getFrame().setTitle(title);
		}
		this.title = title;
	}

	/**
	 * Gets the plot display title.
	 *
	 * @return the current display title
	 */
	public String getTitle() {
		return title;
	}

	/**
	 * Sets a manual range for the viewers' X-axis. Calling {@code setXrange(-1, -1)} enables auto-range (the default).
	 * Must be called before Viewer is fully assembled.
	 *
	 * @param xMin the lower-limit for the X-axis
	 * @param xMax the upper-limit for the X-axis
	 */
	public void setXrange(final double xMin, final double xMax) {
		if (xMin == xMax && xMin == -1)
			plot.xAxis().setAutoRange();
		else
			plot.xAxis().setManualRange(xMin, xMax);
	}

	/**
	 * Sets a manual range for the viewers' Y-axis. Calling {@code setYrange(-1, -1)} enables auto-range (the default).
	 * Must be called before Viewer is fully assembled.
	 *
	 * @param yMin the lower-limit for the Y-axis
	 * @param yMax the upper-limit for the Y-axis
	 */
	public void setYrange(final double yMin, final double yMax) {
		if (yMin == yMax && yMin == -1)
			plot.yAxis().setAutoRange();
		else
			plot.yAxis().setManualRange(yMin, yMax);
	}

	/**
	 * @deprecated Use {@link #show()} instead.
	 */
	@Deprecated
	public void showPlot() {
		show();
	}

	/** Displays the current plot on a dedicated frame */
	public void show() {
		getChart().show();
	}

	/**
	 * Displays the current plot on a dedicated frame *
	 * 
	 * @param width  the preferred frame width
	 * @param height the preferred frame height
	 */
	public void show(final int width, final int height) {
        getChart().show(width, height);
	}

	public void setGridlinesVisible(final boolean visible) {
        visibleGridLines = visible;
	}

	public void setAxesVisible(final boolean visible) {
        visibleAxes = visible;
	}

	public void setOutlineVisible(final boolean visible) {
		visibleOutline = visible;
	}

    /**
     /**
     * Sets whether the axes should be equalized (same scale).
     * <p>
     * When enabled, both X and Y axes will use the same scale to maintain
     * equal aspect ratio. When disabled, each axis maximizes its range.
     * </p>
     * @param equalizeAxes true to equalize axes, false otherwise
     */
    public void setEqualizeAxes(final boolean equalizeAxes) {
        this.equalizeAxes = equalizeAxes;
    }

	private boolean getGridlinesVisible() {
		return visibleGridLines;
	}

	private boolean getAxesVisible() {
		return visibleAxes;
	}

	private boolean getOutlineVisible() {
		return visibleOutline;
	}

    private boolean getEqualizeAxes() {
        return equalizeAxes;
    }

	/* IDE debug method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Viewer2D pplot = new Viewer2D(ij.context());
		final Tree tree = new SNTService().demoTree("fractal");
		tree.rotate(Tree.Z_AXIS, 180);
		final SNTPoint root = tree.getRoot();
		tree.translate(-root.getX(), -root.getY(), -root.getZ());
		pplot.add(tree, "red");
		pplot.setOutlineVisible(true);
		pplot.show();
		final SNTChart sntChart = pplot.getChart();
		sntChart.setAxesVisible(false);
		sntChart.setOutlineVisible(false);
		sntChart.setGridlinesVisible(false);
		sntChart.show();
	}

}
