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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.plot.LineStyle;
import net.imagej.plot.MarkerStyle;
import net.imagej.plot.PlotService;
import net.imagej.plot.XYPlot;
import net.imagej.plot.XYSeries;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import ij.ImagePlus;
import ij.gui.Plot;
import ij.measure.Calibration;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;

/**
 * Command to retrieve Profile plots (plots of voxel intensities values along a
 * Path) from reconstructions.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, label = "Path Profiler", initializer = "init")
public class PathProfiler extends CommonDynamicCmd {

	/** Flag for retrieving distances from {@link #getValues(Path)} */
	public final static String X_VALUES = "x-values";

	/** Flag for retrieving intensities from {@link #getValues(Path)} */
	public final static String Y_VALUES = "y-values";

	@Parameter
	private PlotService plotService;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "Sampling Cursor:")
	private String HEADER1;

	@Parameter(label = "Radius (in pixels)",  min="0")
	private int radius;

	@Parameter(label = "Shape (centered at each node)", choices = {"Circle", "Disk", "HyperSphere", "None. Path coordinates only"})
	private String shapeStr;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "Y axis:")
	private String HEADER2;

	@Parameter(label = "Integration metric:" , choices = {"Sum", "Min", "Max", "Mean", "Median", "Variance", "N/A"})
	private String metricStr;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "X axis:")
	private String HEADER3;

	@Parameter(required  = false, label = "Spatially calibrated distances")
	private boolean usePhysicalUnits;

	@Parameter(label = "tree")
	private Tree tree;

	@Parameter(label = "dataset")
	private Dataset dataset;

	@Parameter(label = "imp", required = false)
	private ImagePlus imp;

	private ProfileProcessor.Shape shape = ProfileProcessor.Shape.HYPERSPHERE;
	private ProfileProcessor.Metric metric = ProfileProcessor.Metric.SUM;

	private BoundingBox impBox;
	private boolean valuesAssignedToTree;
	private int lastprofiledChannel = -1;
	private boolean nodeIndices = false;
	private int frame = 1;

	/**
	 * Instantiates a new Profiler
	 *
	 * @param tree the Tree to be profiled
	 * @param imp the image from which pixel intensities will be retrieved. Note
	 *          that no effort is made to ensure that the image is suitable for
	 *          profiling, if Tree nodes lay outside the image dimensions, pixels
	 *          intensities will be retrieved as {@code Float#NaN}
	 */
	public PathProfiler(final Tree tree, final ImagePlus imp) {
		if (imp == null){
			throw new IllegalArgumentException("Image cannot be null");
		}
		if (tree == null){
			throw new IllegalArgumentException("Tree cannot be null");
		}
		this.tree = tree;
		this.imp = imp;
		this.frame = imp.getFrame();
		impBox = getImpBoundingBox(imp);
		init();
		// refractored constructor: ensure backwards compatibility
		setRadius(1);
		setShape(ProfileProcessor.Shape.PATH);
		setNodeIndicesAsDistances(false);
	}

	public PathProfiler(final Tree tree, final Dataset dataset) {
		if (dataset == null){
			throw new IllegalArgumentException("Dataset cannot be null");
		}
		if (tree == null){
			throw new IllegalArgumentException("Tree cannot be null");
		}
		this.tree = tree;
		this.dataset = dataset;
		impBox = getImpBoundingBox(dataset);
		init();
	}

	public PathProfiler() {
		super(); // required for calling run() from CommandService
	}

	protected void init() {
		initContextAsNeeded();
		super.init(false);
		// FIXME
		if (dataset == null) {
			if (imp == null) {
				throw new IllegalArgumentException("Both dataset and imp are null");
			}
			final ConvertService convertService = getContext().service(ConvertService.class);
			this.dataset = convertService.convert(imp, Dataset.class);
			if (dataset == null) {
				throw new UnsupportedOperationException("BUG: Could not convert ImagePlus to Dataset");
			}
		}
	}

	private void initContextAsNeeded() {
		if (getContext() == null) {
			setContext(SNTUtils.getContext());
		}
	}

	private void evalParameters() {
		switch (metricStr) {
			case "Sum":
				metric = ProfileProcessor.Metric.SUM;
				break;
			case "Min":
				metric = ProfileProcessor.Metric.MIN;
				break;
			case "Max":
				metric = ProfileProcessor.Metric.MAX;
				break;
			case "Mean":
			case "N/A":
				metric = ProfileProcessor.Metric.MEAN;
				break;
			case "Median":
				metric = ProfileProcessor.Metric.MEDIAN;
				break;
			case "Variance":
				metric = ProfileProcessor.Metric.VARIANCE;
				break;
			default:
				throw new IllegalArgumentException("Unknown metric: " + metricStr);
		}
		switch (shapeStr) {
			case "HyperSphere":
				shape = ProfileProcessor.Shape.HYPERSPHERE;
				break;
			case "Circle":
				shape = ProfileProcessor.Shape.CIRCLE;
				break;
			case "Disk":
				shape = ProfileProcessor.Shape.DISK;
				break;
			case "Path":
			case "None. Path coordinates only":
				shape = ProfileProcessor.Shape.PATH;
				break;
			default:
				throw new IllegalArgumentException("Unknown shape: " + shapeStr);
		}
		setNodeIndicesAsDistances(!usePhysicalUnits);
	}

	/**
	 * Instantiates a new Profiler from a single path
	 *
	 * @param path the path to be profiled
	 * @param imp the image from which pixel intensities will be retrieved. Note
	 *          that no effort is made to ensure that the image is suitable for
	 *          profiling, if Tree nodes lay outside the image dimensions, pixels
	 *          intensities will be retrieved as {@code Float#NaN}
	 */
	public PathProfiler(final Path path, final ImagePlus imp) {
		this(new Tree(Collections.singleton(path)), imp);
	}

	public PathProfiler(final Path path, final Dataset dataset) {
		this(new Tree(Collections.singleton(path)), dataset);
	}

	public void setShape(ProfileProcessor.Shape shape) {
		this.shape = shape;
	}

	public void setMetric(ProfileProcessor.Metric metric) {
		this.metric = metric;
	}

	public void setRadius(int radius) {
		this.radius = radius;
	}

	public void setFrame(final int frame) {
		this.frame = frame;
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (getContext() == null) {
			// since this is a scriptable class, provide feedback in case run() is called out-of-context
			throw new IllegalArgumentException("No context has been set. Note that this method should only be called from CommandService.");
		}
		if (tree.size() == 0) {
			super.error("No path(s) to profile");
			return;
		}
		super.status("Retrieving profile(s)...", false);
		evalParameters();
		getPlot().show();
		super.resetUI();
	}

	private String getPlotTitle() {
		if (tree.size() == 1) return tree.get(0).getName() + " Profile";
		return (tree.getLabel() == null) ? "Path Profile" : tree.getLabel() +
			" Path Profile";
	}

	private BoundingBox getImpBoundingBox(final ImagePlus imp) {
		final BoundingBox impBox = new BoundingBox();
		final Calibration cal = imp.getCalibration();
		impBox.setOrigin(new PointInImage(cal.xOrigin, cal.yOrigin, cal.zOrigin));
		impBox.setDimensions(imp.getWidth(), imp.getHeight(), imp.getNSlices());
		impBox.setSpacing(cal.pixelWidth, cal.pixelHeight, cal.pixelDepth, cal
			.getUnit());
		return impBox;
	}

	private BoundingBox getImpBoundingBox(final Dataset dataset) {
		final BoundingBox impBox = new BoundingBox();
		long[] dims = new long[dataset.numDimensions()];
		switch(dims.length) {
		case 1:
			impBox.setDimensions((int) dims[0], 0, 0);
			break;
		case 2:
			impBox.setDimensions((int) dims[0], (int) dims[1], 0);
			break;
		default:
			impBox.setDimensions((int) dims[0], (int) dims[1], (int) dims[2]);
			break;
		}
		return impBox;
	}

	/**
	 * Checks whether the specified image contains all the nodes of the profiled
	 * Tree/Path.
	 *
	 * @return true, if successful, false if Tree has nodes outside the image
	 *         boundaries.
	 */
	public boolean validImage() {
		BoundingBox bbox = tree.getBoundingBox(false);
		if (bbox == null) bbox = tree.getBoundingBox(true);
		return impBox.contains(bbox);
	}

	private void validateChannelRange(final int channel) {
		if (channel < 1 || channel > dataset.getChannels())
			throw new IllegalArgumentException(
					"Specified channel " + channel + " out of range: Only 1-" + dataset.getChannels() + " allowed");
	}

	/**
	 * Calls {@link #assignValues(Path)} on the Paths of the profiled Tree
	 */
	public void assignValues() throws IllegalArgumentException {
		for (final Path p : tree.list())
			assignValues(p);
		lastprofiledChannel = -1;
		valuesAssignedToTree = true;
	}

	public void assignValues(final int channel) throws IllegalArgumentException {
		if (channel == -1) {
			assignValues();
			return;
		}
		validateChannelRange(channel);
		for (final Path p : tree.list())
			assignValues(p, channel);
		lastprofiledChannel = channel;
		valuesAssignedToTree = true;
	}

	/**
	 * Retrieves pixel intensities at each node of the Path storing them as Path
	 * {@code values}
	 * 
	 * @see Path#setNodeValues(double[])
	 * @param p the Path to be profiled
	 * @throws IllegalArgumentException if image does not contain the path's channel
	 */
	public void assignValues(final Path p) throws IllegalArgumentException {
		assignValues(p, p.getChannel());
	}

	public <T extends RealType<T>> void assignValues(final Path p, final int channel) {
		validateChannelRange(channel);
//		System.out.println(channel - 1);
//		System.out.println(frame - 1);
		RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice(dataset, channel - 1, frame - 1);
		ProfileProcessor<T> processor = new ProfileProcessor<>(rai, p);
		processor.setShape(shape);
		processor.setRadius(radius);
		processor.setMetric(metric);
		p.setNodeValues(processor.call());
	}

	private Map<String, double[]> getValuesAsArray(final Path p) {
		if (!p.hasNodeValues()) assignValues(p);
		return getValuesAsArray(p, p.getChannel());

	}
	private Map<String, double[]> getValuesAsArray(final Path p, final int channel) {
		if (!valuesAssignedToTree || channel != lastprofiledChannel)
			assignValues(p, channel);
		final double[] xList = new double[p.size()];
		final double[] yList = new double[p.size()];
		final Map<String, List<Double>> values = getValues(p);
		int i = 0;
		for (final double d : values.get(X_VALUES))
			xList[i++] = d;
		i = 0;
		for (final double d : values.get(Y_VALUES))
			yList[i++] = d;
		final Map<String, double[]> map = new HashMap<>();
		map.put(X_VALUES, xList);
		map.put(Y_VALUES, yList);
		return map;
	}

	/**
	 * Sets whether the profile abscissae should be reported in real-word units
	 * (the default) or node indices (zero-based). Must be called before calling
	 * {@link #getValues(Path)}, {@link #getPlot()} or {@link #getXYPlot()}.
	 *
	 * @param nodeIndices If true, distances will be reported as indices.
	 */
	public void setNodeIndicesAsDistances(final boolean nodeIndices) {
		this.nodeIndices = nodeIndices;
	}

	/**
	 * Gets the profile for the specified path as a map of lists, with distances (or
	 * indices) stored under {@link #X_VALUES} ({@value #X_VALUES}) and intensities
	 * under {@link #Y_VALUES} ({@value #Y_VALUES}).
	 *
	 * @param p the path to be profiled
	 * @return the profile
	 */
	public Map<String, List<Double>> getValues(final Path p) {
		return getValues(p, p.getChannel());
	}

	public Map<String, List<Double>> getValues(final Path p, final int channel) throws IllegalArgumentException {
		if (!p.hasNodeValues()) assignValues(p, channel);
		final List<Double> xList = new ArrayList<>();
		final List<Double> yList = new ArrayList<>();

		if (nodeIndices) {
			for (int i = 0; i < p.size(); i++) {
				xList.add((double)i); 
				yList.add(p.getNodeValue(i));
			}
		} else {

			// Add data for first node
			xList.add(0d);
			yList.add(p.getNodeValue(0));

			// Add data for remaining nodes
			PointInImage previousNode = p.getNode(0);
			double cumulativePathLength = 0d;
			for (int i = 1; i < p.size(); i++) {
				final PointInImage node = p.getNode(i);
				cumulativePathLength += node.distanceTo(previousNode);
				xList.add(cumulativePathLength);
				previousNode = node;
				yList.add(p.getNodeValue(i));
			}
		}

		assert xList.size() == yList.size();
		final Map<String, List<Double>> map = new HashMap<>();
		map.put(X_VALUES, xList);
		map.put(Y_VALUES, yList);
		return map;
	}

	private XYSeries addSeries(final XYPlot plot, final Path p,
		final ColorRGB color, boolean setLegend)
	{
		final XYSeries series = plot.addXYSeries();
		final Map<String, List<Double>> values = getValues(p);
		series.setLabel(p.getName());
		series.setStyle(plot.newSeriesStyle(color, LineStyle.SOLID,
			MarkerStyle.CIRCLE));
		series.setValues(values.get(X_VALUES), values.get(Y_VALUES));
		series.setLegendVisible(setLegend);
		return series;
	}

	private Color[] getSeriesColorsAWT() {
		final Color[] colors = new Color[tree.size()];
		if (treeIsColorMapped()) {
			for (int i = 0; i < tree.size(); i++) {
				colors[i] = tree.get(i).getColor();
				if (colors[i] == null) colors[i] = Color.BLACK;
			}
		}
		else {
			final ColorRGB[] colorsRGB = SNTColor.getDistinctColors(tree.size());
			for (int i = 0; i < tree.size(); i++) {
				colors[i] = new Color(colorsRGB[i].getARGB());
			}
		}
		return colors;
	}

	private ColorRGB[] getSeriesColorsRGB() {
		final ColorRGB[] colors;
		if (treeIsColorMapped()) {
			colors = new ColorRGB[tree.size()];
			for (int i = 0; i < tree.size(); i++) {
				colors[i] = tree.get(i).getColorRGB();
				if (colors[i] == null) colors[i] = Colors.BLACK;
			}
		}
		else {
			colors = SNTColor.getDistinctColors(tree.size());
		}
		return colors;
	}

	private String getXAxisLabel() {
		return (nodeIndices) ? "Node indices" : "Distance";
	}

	private String getYAxisLabel() {
		return "Intensity (" + dataset.getValidBits() + "-bit)";
	}

	/**
	 * Gets the plot profile as an IJ1 {@link Plot}.
	 *
	 * @return the plot
	 */
	public Plot getPlot() {
		return (tree.size()==1) ? getPlot(tree.get(0)) : getPlot(-1);
	}

	public Plot getPlot(final int channel) throws IllegalArgumentException {
		if (!valuesAssignedToTree || (channel > 0 && channel != lastprofiledChannel) ) {
			assignValues(channel);
		}
		String yAxisLabel = getYAxisLabel();
		if (channel > -1) yAxisLabel += " (Ch " + channel + ")";
		final Plot plot = new Plot(getPlotTitle(), getXAxisLabel(), yAxisLabel);
		final Color[] colors = getSeriesColorsAWT();
		final StringBuilder legend = new StringBuilder();
		for (int i = 0; i < tree.size(); i++) {
			final Path p = tree.get(i);
			legend.append(p.getName()).append("\n");
			final Map<String, double[]> values = getValuesAsArray(p);
			plot.setColor(colors[i], colors[i]);
			plot.addPoints(values.get(X_VALUES), values.get(Y_VALUES),
				Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		plot.setLegend(legend.toString(), Plot.LEGEND_TRANSPARENT);
		return plot;
	}

	public Plot getPlot(final Path path) {
		final Plot plot = new Plot(getPlotTitle(), getXAxisLabel(), getYAxisLabel());
		final Color[] colors = new Color[(int)dataset.getChannels()];
			final ColorRGB[] colorsRGB = SNTColor.getDistinctColors((int)dataset.getChannels());
			for (int i = 0; i < dataset.getChannels(); i++)
				colors[i] = new Color(colorsRGB[i].getARGB());
		final StringBuilder legend = new StringBuilder();
		for (int i = 1; i <= dataset.getChannels(); i++) {
			legend.append("Ch").append(i).append("\n");
			final Map<String, double[]> values = getValuesAsArray(path, i);
			plot.setColor(colors[i-1], colors[i-1]);
			plot.addPoints(values.get(X_VALUES), values.get(Y_VALUES),
				Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		plot.setLegend(legend.toString(), Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION);
		return plot;
	}

	/**
	 * Gets the plot profile as an {@link PlotService} plot. Requires
	 * {@link #setContext(org.scijava.Context)} to be called beforehand.
	 *
	 * @return the plot
	 */
	public XYPlot getXYPlot() {
		return getXYPlot(-1);
	}

	public XYPlot getXYPlot(final int channel) throws IllegalArgumentException {
		if (!valuesAssignedToTree || (channel > 0 && channel != lastprofiledChannel) ) {
			assignValues(channel);
		}
		final XYPlot plot = plotService.newXYPlot();
		final boolean setLegend = tree.size() > 1;
		final ColorRGB[] colors = getSeriesColorsRGB();
		int colorIdx = 0;
		for (final Path p : tree.list()) {
			addSeries(plot, p, colors[colorIdx++], setLegend);
		}
		plot.xAxis().setLabel(getXAxisLabel());
		plot.yAxis().setLabel(getYAxisLabel());
		return plot;
	}

	private boolean treeIsColorMapped() {
		final ColorRGB refColor = tree.get(0).getColorRGB();
		for (final Path path : tree.list()) {
			if (path.hasNodeColors()) return true;
			if (refColor != null && !refColor.equals(path.getColorRGB())) return true;
		}
		return false;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService snt = ij.context().getService(SNTService.class);
		final Tree tree = snt.demoTree("OP_1");
		final ImagePlus imp = snt.demoImage("OP_1");
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("tree", tree);
		inputs.put("imp", imp);
		final CommandService cmdService = ij.context().getService(CommandService.class);
		cmdService.run(PathProfiler.class, true, inputs);

	}
}
