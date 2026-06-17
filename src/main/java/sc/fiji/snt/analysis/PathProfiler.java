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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.imagej.axis.Axes;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plot.LineStyle;
import org.scijava.plot.MarkerStyle;
import org.scijava.plot.PlotService;
import org.scijava.plot.XYPlot;
import org.scijava.plot.XYSeries;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import ij.ImagePlus;
import ij.gui.Plot;
import ij.plugin.filter.MaximumFinder;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.ProfileProcessor.Shape;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.TreeUtils;

/**
 * Command to retrieve Path profiles (plots of voxel intensities values along a
 * Path)
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, label = "Path Profiler", initializer = "init")
public class PathProfiler extends CommonDynamicCmd {

	protected static final String CIRCLE2D = "Circle (hollow)";
	protected static final String CIRCLE3D = "Circle (hollow, in orthogonal plane)";
	protected static final String DISK2D = "Disk (filled)";
	protected static final String DISK3D = "Disk (filled, in orthogonal plane)";
	protected static final String SPHERE = "Sphere (filled)";
	protected static final String LINE2D = "Line (orthoghonal)";
	protected static final String LINE3D = "2D Line (orthoghonal, in node's plane)";

	protected static final String NONE = "None. Path centerline only";

	/** Key for retrieving distances from {@link #getValues(Path)} */
	public final static String X_VALUES = "x-values";

	/** Key for retrieving intensities from {@link #getValues(Path)} */
	public final static String Y_VALUES = "y-values";

	@Parameter
	private PlotService plotService;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "Sampling Neighborhood:")
	private String HEADER1;

	@Parameter(
			label = "Shape (centered at each node)",
			description = "<HTML>The image neighborhood to be measured around each Path node.",
			choices = {LINE2D, CIRCLE2D, DISK2D, SPHERE, NONE},
			callback = "shapeStrChanged")
	private String shapeStr;

	@Parameter(
			label = "Radius (in pixels)", callback = "updateMsg",
			description = "<HTML>The size of the sampling shape.<br>"
					+ "A value >= 1 sets a fixed radius for shape neighborhood.<br>"
					+ "A value of 0 instructs the profiler to adopt the radius at each Path's node.<br>"
					+ "Ignored when shape is '" + NONE + "'",
			min="0")
	private int radius;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = EMPTY_LABEL, callback = "updateMsg")
	private String msg = "Aprox. radius: 99.99microns"; // ensure dialog is wide enough for msg

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE,
			label = HEADER_HTML + "Options:")
	private String HEADER2;

	@Parameter(label = "Integration metric:" ,
			description = "<HTML>Statistic to compute for each neighborhood.<br>"
					+ "Ignored when shape is '" + NONE + "'",
			choices = {"Sum", "Min", "Max", "Mean", "Mean±SD", "Median", "Variance", "Standard deviation", "CV", "N/A"},
			callback = "metricStrChanged")
	private String metricStr;

	@Parameter(required  = false, label = "Channel(s) to be profiled",
			description = "<HTML>List of channel(s) to  be profiled (comma-separated).<br>"
					+ "Leave empty or type 'all' to profile all channels")
	private String channelString;

	@Parameter(required  = false, label = "Spatially calibrated distances")
	protected boolean usePhysicalUnits;

	@Parameter(label = "tree")
    protected Tree tree;

	@Parameter(label = "dataset")
    protected Dataset dataset;

	@Parameter(label = "imp", required = false)
	private ImagePlus imp;

	private ProfileProcessor.Shape shape = ProfileProcessor.Shape.HYPERSPHERE;
	private ProfileProcessor.Metric metric = ProfileProcessor.Metric.MEAN;
	private boolean valuesAssignedToTree;
	private int lastprofiledChannel = -1;
	private boolean nodeIndices = false;
	private double avgSep;
	private String unit;

	/**
	 * Empty constructor used by CommandService before calling {@link #run()}.
	 * Should not be called directly.
	 */
	public PathProfiler() {
		super(); // required for calling run() from CommandService
	}

	/**
	 * @deprecated Use {@link #PathProfiler(Tree, Dataset)}
	 */
	@Deprecated
	public PathProfiler(final Tree tree, final ImagePlus imp) {
		if (tree == null)
			throw new IllegalArgumentException("Tree cannot be null");
		this.tree = tree;
		initContextAsNeeded();
		dataset = getDatasetFromImp(imp);
		setSpatialUnitAndAvgSept();
		setRadius(0);
		setShape(ProfileProcessor.Shape.LINE);
		setMetric(ProfileProcessor.Metric.MEAN);
	}


	/**
	 * @deprecated Use {@link #PathProfiler(Path, Dataset)}
	 */
	@Deprecated
	public PathProfiler(final Path path, final ImagePlus imp) {
		this(new Tree(Collections.singleton(path)), imp);
	}

	/**
	 * Instantiates a new Profiler.
	 *
	 * @param tree    the Tree to be profiled
	 * @param dataset the dataset from which pixel intensities will be retrieved.
	 *                Note that no effort is made to ensure that the image is
	 *                suitable for profiling.
	 */
	public PathProfiler(final Tree tree, final Dataset dataset) {
		if (dataset == null)
			throw new IllegalArgumentException("Dataset cannot be null");
		if (tree == null)
			throw new IllegalArgumentException("Tree cannot be null");
		this.tree = tree;
		this.dataset = dataset;
		setSpatialUnitAndAvgSept();
		setRadius(0);
		setShape(ProfileProcessor.Shape.LINE);
		setMetric(ProfileProcessor.Metric.MEAN);
	}

	/**
	 * Instantiates a new Profiler from a single path.
	 *
	 * @param path    the path to be profiled
	 * @param dataset the dataset from which pixel intensities will be
	 *                retrieved.Note that no effort is made to ensure that the image
	 *                is suitable for profiling
	 */
	public PathProfiler(final Path path, final Dataset dataset) {
		this(new Tree(Collections.singleton(path)), dataset);
	}

	private void updateMsg() {
		if (avgSep == 0) {
			msg = "";
		} else {
			if (radius <= 0)
				msg = "Radius set by path radii";
			else
				msg = String.format("Aprox.: %.2f%s", (avgSep * radius), unit);
		}
	}

	@SuppressWarnings("unused")
    protected void init() {
		initContextAsNeeded();
		super.init(false);
		if (dataset == null)
			dataset = getDatasetFromImp(imp);
		setSpatialUnitAndAvgSept();
		try {
			// adjust shapeStr options
			final MutableModuleItem<String> mi = getInfo().getMutableInput("shapeStr", String.class);
			if (dataset.getDepth() > 1) {
				mi.setChoices(List.of(LINE3D, CIRCLE3D, DISK3D, SPHERE, NONE));
			} else {
				mi.setChoices(List.of(LINE2D, CIRCLE2D, DISK2D, NONE));
			}
			// adjust channel options
			if (dataset.getChannels() < 2 || tree.size() == 1) {
				resolveInput("channelString");
				channelString = "";
			}
			resolveInput("imp");
			resolveInput("dataset");
		} catch (final Exception ex) {
			// command initialized without context!?
			ex.printStackTrace();
		}
		updateMsg();
	}

	private Dataset getDatasetFromImp(final ImagePlus imp) {
		if (imp == null)
			throw new IllegalArgumentException("ImagePlus is null");
		final ConvertService convertService = getContext().service(ConvertService.class);
		dataset = convertService.convert(imp, Dataset.class);
		if (dataset == null)
			throw new UnsupportedOperationException("BUG: Could not convert ImagePlus to Dataset");
		return dataset;
	}

	private void setSpatialUnitAndAvgSept() {
		unit = dataset.axis(dataset.dimensionIndex(Axes.X)).unit();
		avgSep = 0;
		int nSpatialAxes = 0;
		for (int i = 0; i < dataset.numDimensions(); i++) {
			if (!dataset.axis(i).type().isSpatial()) continue;
			avgSep += dataset.axis(i).calibratedValue(1);
			nSpatialAxes++;
		}
		avgSep /= nSpatialAxes;
	}

	private void initContextAsNeeded() {
		if (getContext() == null) {
			setContext(SNTUtils.getContext());
		}
	}

	@SuppressWarnings("unused")
	private void shapeStrChanged() {
		if (NONE.equals(shapeStr)) {
			metricStr = "N/A";
		} else if ("N/A".equals(metricStr)) {
			metricStr = "Mean";
		}
	}
	@SuppressWarnings("unused")
	private void metricStrChanged() {
		if ("N/A".equals(metricStr)) {
			shapeStr = NONE;
		}
	}

	protected void evalParameters() {
		switch (metricStr.toLowerCase()) {
			case "sum" -> metric = ProfileProcessor.Metric.SUM;
			case "min" -> metric = ProfileProcessor.Metric.MIN;
			case "max" -> metric = ProfileProcessor.Metric.MAX;
			case "mean", "n/a", "mean±sd" -> metric = ProfileProcessor.Metric.MEAN;
			case "median" -> metric = ProfileProcessor.Metric.MEDIAN;
			case "variance" -> metric = ProfileProcessor.Metric.VARIANCE;
			case "standard deviation", "sd" -> metric = ProfileProcessor.Metric.SD;
			case "cv" -> metric = ProfileProcessor.Metric.CV;
			default -> throw new IllegalArgumentException("Unknown metric: " + metricStr);
		}
		switch (shapeStr) {
			case SPHERE:
				shape = ProfileProcessor.Shape.HYPERSPHERE;
				break;
			case CIRCLE2D:
			case CIRCLE3D:
				shape = ProfileProcessor.Shape.CIRCLE;
				break;
			case DISK2D:
			case DISK3D:
				shape = ProfileProcessor.Shape.DISK;
				break;
			case PathProfiler.LINE2D:
			case PathProfiler.LINE3D:
				shape = ProfileProcessor.Shape.LINE;
				break;
			case NONE:
				shape = ProfileProcessor.Shape.NONE;
				break;
			default:
				throw new IllegalArgumentException("Unknown shape: " + shapeStr);
		}
		setNodeIndicesAsDistances(!usePhysicalUnits);
	}

	/**
	 *
	 * @param shape Either {@link ProfileProcessor.Shape}
	 */
	public void setShape(final ProfileProcessor.Shape shape) {
		this.shape = shape;
	}

	public void setMetric(final ProfileProcessor.Metric metric) {
		this.metric = metric;
	}

	public void setRadius(final int radius) {
		this.radius = radius;
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
		if (tree.isEmpty()) {
			super.error("No path(s) to profile");
			return;
		}
		status("Retrieving profile(s)...", false);
		evalParameters();
		try {
			if (tree.size() == 1) { // all channels are retrieved
				if (metricStr.contains("±"))
					getMeanSDPlot(tree.get(0)).show();
				else
					getPlot(tree.get(0)).show();
			} else {
				if (metricStr.contains("±"))
					getChannels().forEach(ch -> getMeanSDPlot(ch).show());
				else
					getChannels().forEach(ch -> getPlot(ch).show());
			}
		} catch (final Exception ex) {
			if (ex instanceof ArrayIndexOutOfBoundsException)
				error("Profile could not be retrieved. Do Path nodes lay outside image bounds?");
			ex.printStackTrace();
		} finally {
			super.resetUI();
		}
	}

	protected List<Integer> getChannels() {
		if (channelString == null || channelString.trim().isEmpty() || "all".equalsIgnoreCase(channelString.trim()))
			return getAllChannels();
		final List<String> stringChannels = new ArrayList<>(Arrays.asList(channelString.split("\\s*([,\\s])\\s*")));
		final List<Integer> validChannels = new ArrayList<>();
		for (final String chString : stringChannels) {
			try {
				final int ch = Integer.parseInt(chString) - 1;
				if (ch >= 0 && ch < dataset.getChannels())
					validChannels.add(ch);
			} catch (final NumberFormatException ignored) {
				SNTUtils.log("Path Profiler: Ignoring channel " + chString);
			}
		}
		if (validChannels.isEmpty()) {
			error("None of the specified channel(s) are valid.");
		}
		return validChannels;
	}

	private List<Integer> getAllChannels() {
		return IntStream.range(0, (int) dataset.getChannels()).boxed().collect(Collectors.toList());
	}

	private String getPlotTitle(final int channel) {
		if (tree.size() == 1)
			return tree.get(0).getName() + " Profile";
		final StringBuilder sb = new StringBuilder();
		if (tree.getLabel() == null)
			sb.append("Path Profile");
		else
			sb.append(tree.getLabel()).append(" Path Profile");
		if (channel < 0)
			sb.append(" [All Channels]");
		else
			sb.append(" [C").append(channel + 1).append("]");
		return sb.toString();
	}

	private void validateChannelRange(final int channel) {
		if (channel < 0 || channel > dataset.getChannels())
			throw new IllegalArgumentException(
					"Specified channel " + channel + " out of range. Only [0-" + dataset.getChannels() + "[ allowed.");
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

	/**
	 * Retrieves pixel intensities at each node of the Path storing them as Path
	 * {@code values}
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * @throws IllegalArgumentException if image does not contain the path's channel
	 */
	public void assignValues(final int channel) throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
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
		assignValues(p, p.getChannel() - 1);
	}

	/**
	 * Retrieves pixel intensities at each node of the Path storing them as Path
	 * {@code values}
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * @param p       the Path to be profiled
	 * @see Path#setNodeValues(double[])
	 * 
	 * @throws IllegalArgumentException if image does not contain the path's channel
	 */
	public <T extends RealType<T>> void assignValues(final Path p, final int channel) throws ArrayIndexOutOfBoundsException {
		assignValues(p, channel, p.getFrame()-1);
		//ImgUtils.getCtSlice3d(dataset, channel, channel);
	}

	/**
	 * Retrieves pixel intensities at each node of the Path storing them as Path
	 * {@code values}
	 *
	 * @param channel the channel to be parsed (base-0 index)
	 * @param frame the frame to be parsed (base-0 index)
	 * @param p       the Path to be profiled
	 * @see Path#setNodeValues(double[])
	 *
	 * @throws IllegalArgumentException if image does not contain the path's channel
	 */
	public <T extends RealType<T>> void assignValues(final Path p, final int channel, final int frame) throws ArrayIndexOutOfBoundsException {
		validateChannelRange(channel);
		final RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice(dataset, channel, frame);
		final ProfileProcessor<T> processor = new ProfileProcessor<>(rai, p);
		processor.setShape(shape);
		processor.setRadius(radius);
		processor.setMetric(metric);
		p.setNodeValues(processor.call());
		//ImgUtils.getCtSlice3d(dataset, channel, channel);
	}

	@SuppressWarnings("unused")
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
	 * Finds the maxima in the profile of the specified path.
	 * <p>
	 * A maxima (peak) will only be considered if protruding more than the profile's
	 * standard deviation from the ridge to a higher maximum
	 * </p>
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * @return the indices of the maxima
	 */
	public int[] findMaxima(final Path path, final int channel) {
		final double[] profile = getValues(path, channel).get(PathProfiler.Y_VALUES).stream().mapToDouble(d -> d).toArray();
		final SummaryStatistics stats = new SummaryStatistics();
		Arrays.stream(profile).forEach(stats::addValue);
		return MaximumFinder.findMaxima(profile, stats.getStandardDeviation(), false);
	}

	/**
	 * Finds the minima in the profile of the specified path.
	 * <p>
	 * A maxima (peak) will only be considered if protruding less than the profile's
	 * standard deviation from the ridge to a lower minimum
	 * </p>
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * @return the indices of the minima
	 */
	public int[] findMinima(final Path path, final int channel) {
		final double[] profile = getValues(path, channel).get(PathProfiler.Y_VALUES).stream().mapToDouble(d -> d).toArray();
		final SummaryStatistics stats = new SummaryStatistics();
		Arrays.stream(profile).forEach(stats::addValue);
		return MaximumFinder.findMinima(profile, stats.getStandardDeviation(), false);
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
		return getValues(p, p.getChannel() - 1);
	}

	/**
	 * Gets the profile for the specified path as a map of lists, with distances (or
	 * indices) stored under {@link #X_VALUES} ({@value #X_VALUES}) and intensities
	 * under {@link #Y_VALUES} ({@value #Y_VALUES}). If dataset is a time-lapse, the
	 * path's assigned frame is profiled.
	 * 
	 * @param p       the path to be profiled
	 * @param channel the channel to be parsed (base-0 index)
	 * @return the profile map
	 */
	public Map<String, List<Double>> getValues(final Path p, final int channel) {
		return getValues(p, channel, p.getFrame()-1);
	}

	/**
	 * Gets the profile for the specified path as a map of lists, with distances (or
	 * indices) stored under {@link #X_VALUES} ({@value #X_VALUES}) and intensities
	 * under {@link #Y_VALUES} ({@value #Y_VALUES}).
	 *
	 * @param p       the path to be profiled
	 * @param channel the channel to be parsed (base-0 index)
	 * @param frame   the frame to be parsed (base-0 index)
	 * @return the profile map
	 */
	public Map<String, List<Double>> getValues(final Path p, final int channel, final int frame) {

		if (!p.hasNodeValues()) assignValues(p, channel, frame);
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
		final ColorRGB color, final boolean setLegend)
	{
		final XYSeries series = plot.addXYSeries();
		final Map<String, List<Double>> values = getValues(p);
		series.setLabel(p.getName());
		series.setStyle(plotService.newSeriesStyle(color, LineStyle.SOLID,
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

	protected String getXAxisLabel() {
		return (nodeIndices) ? "Node indices"
				: String.format("Distance (%s)", tree.getProperties().getProperty(Tree.KEY_SPATIAL_UNIT, "? units"));
	}

	protected String getYAxisLabel(final int channel) {
		final boolean detailed = shape != Shape.NONE;
		final StringBuilder sb = new StringBuilder();
		if (channel > 0 && dataset.getChannels() > 1) {
			sb.append("Ch ").append(channel + 1).append(" ");
		}
		sb.append(dataset.getValidBits()).append("-bit ");
		if (detailed) {
			sb.append("Int. (").append(metric).append("; ");
			sb.append(shape).append(", r=");
			if (radius <=0) {
				sb.append("Node radius");
			} else {
				sb.append(String.format("%.2f", (usePhysicalUnits) ?  (avgSep * radius) : radius))
								.append((usePhysicalUnits) ?  unit : "px");
			}
			sb.append(")");
		} else {
			sb.append("Intensity");
		}
		return sb.toString();
	}

	/**
	 * Gets the plot profile as an ImageJ {@link Plot} (all channels).
	 *
	 * @return the plot
	 */
	public Plot getPlot() throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
		return (tree.size()==1) ? getPlot(tree.get(0)) : getPlot(-1);
	}

	private Plot getMeanSDPlot(final int channel) {
		final Plot plot = new Plot(getPlotTitle(channel), getXAxisLabel(), getYAxisLabel(channel));
		final Color[] colors = getSeriesColorsAWT();
		final StringBuilder legend = new StringBuilder();
		for (int i = 0; i < tree.size(); i++) {
			final Path p = tree.get(i);
			legend.append(p.getName()).append("\n");
			metric = ProfileProcessor.Metric.MEAN;
			valuesAssignedToTree = false;
			final Map<String, double[]> meansMap = getValuesAsArray(p, channel);
			metric = ProfileProcessor.Metric.SD;
			valuesAssignedToTree = false;
			final Map<String, double[]> sdMap = getValuesAsArray(p, channel);
			plot.setColor(colors[i], colors[i]);
			plot.addPoints(meansMap.get(X_VALUES), meansMap.get(Y_VALUES), sdMap.get(Y_VALUES), Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		final int flags = (tree.size() < 7) ? Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION : Plot.LEGEND_TRANSPARENT;
		plot.setLegend(legend.toString(), flags);
		return plot;
	}

	/**
	 * Gets the plot profile as an ImageJ plot (single-channel).
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * 
	 * @return the plot
	 */
	public Plot getPlot(final int channel) throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
		if (!valuesAssignedToTree || (channel > 0 && channel != lastprofiledChannel) ) {
			assignValues(channel);
		}
		final Plot plot = new Plot(getPlotTitle(channel), getXAxisLabel(), getYAxisLabel(channel));
		final Color[] colors = getSeriesColorsAWT();
		final StringBuilder legend = new StringBuilder();
		for (int i = 0; i < tree.size(); i++) {
			final Path p = tree.get(i);
			legend.append(p.getName()).append("\n");
			final Map<String, double[]> values = getValuesAsArray(p, channel);
			plot.setColor(colors[i], colors[i]);
			plot.addPoints(values.get(X_VALUES), values.get(Y_VALUES),
				Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		final int flags = (tree.size() < 7) ? Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION : Plot.LEGEND_TRANSPARENT;
		plot.setLegend(legend.toString(), flags);
		return plot;
	}

	private Plot getMeanSDPlot(final Path path) {
		final Plot plot = new Plot(getPlotTitle(-1), getXAxisLabel(), getYAxisLabel(-1));
		final Color[] colors = SNTColor.getDistinctColorsAWT((int) dataset.getChannels());
		final StringBuilder legend = new StringBuilder();
		for (int i = 1; i <= dataset.getChannels(); i++) {
			legend.append("Ch").append(i).append("\n");
			metric = ProfileProcessor.Metric.MEAN;
			final Map<String, double[]> meansMap = getValuesAsArray(path, i);
			metric = ProfileProcessor.Metric.SD;
			final Map<String, double[]> sdMap = getValuesAsArray(path, i);
			plot.setColor(colors[i - 1], colors[i - 1]);
			plot.addPoints(meansMap.get(X_VALUES), meansMap.get(Y_VALUES), sdMap.get(Y_VALUES), Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		plot.setLegend(legend.toString(), Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION);
		return plot;
	}

	/**
	 * Gets the plot profile as an ImageJ plot (all channels included).
	 * 
	 * @return the plot
	 */
	public Plot getPlot(final Path path) {
		final Plot plot = new Plot(getPlotTitle(-1), getXAxisLabel(), getYAxisLabel(-1));
		final Color[] colors = SNTColor.getDistinctColorsAWT((int) dataset.getChannels());
		final StringBuilder legend = new StringBuilder();
		for (int i = 0; i < dataset.getChannels(); i++) {
			legend.append("Ch").append(i).append("\n");
			final Map<String, double[]> values = getValuesAsArray(path, i);
			plot.setColor(colors[i], colors[i]);
			plot.addPoints(values.get(X_VALUES), values.get(Y_VALUES), Plot.CONNECTED_CIRCLES);
		}
		plot.setColor(Color.BLACK, null);
		plot.setLegend(legend.toString(), Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION);
		return plot;
	}

	/**
	 * Gets the plot profile as an {@link PlotService} plot. It is recommended to
	 * call {@link #setContext(org.scijava.Context)} beforehand.
	 *
	 * @return the plot
	 */
	public XYPlot getXYPlot() {
		return getXYPlot(-1);
	}

	/**
	 * Gets the plot profile as an {@link PlotService} plot. It is recommended to
	 * call {@link #setContext(org.scijava.Context)} beforehand.
	 * 
	 * @param channel the channel to be parsed (base-0 index)
	 * @return the plot
	 */
	public XYPlot getXYPlot(final int channel) throws IllegalArgumentException {
		if (!valuesAssignedToTree || (channel >= 0 && channel != lastprofiledChannel) ) {
			assignValues(channel);
		}
		if (plotService == null) initContextAsNeeded();
		final XYPlot plot = plotService.newXYPlot();
		final boolean setLegend = tree.size() > 1;
		final ColorRGB[] colors = getSeriesColorsRGB();
		int colorIdx = 0;
		for (final Path p : tree.list()) {
			addSeries(plot, p, colors[colorIdx++], setLegend);
		}
		plot.xAxis().setLabel(getXAxisLabel());
		plot.yAxis().setLabel(getYAxisLabel(channel));
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

	/**
	 * Resamples the intensity profile of a path onto a uniform distance grid via
	 * linear interpolation. Entries beyond the path's actual length are set to
	 * {@link Double#NaN}, which allows variable-length paths to be compared in
	 * the same matrix without padding with zeros.
	 *
	 * @param p        the path to profile (values are assigned if not yet set)
	 * @param channel  the channel to sample (base-0 index)
	 * @param nSamples the number of output grid points (&gt;= 2)
	 * @param gridMax  the upper bound of the distance grid. Use the path's actual
	 *                 length for a per-path-normalized grid (0..length), or a
	 *                 shared maximum across all paths for an absolute grid
	 * @return array of length {@code nSamples} with interpolated intensities
	 */
	public double[] getResampledValues(final Path p, final int channel,
	                                   final int nSamples, final double gridMax) {
		if (nSamples < 2)
			throw new IllegalArgumentException("nSamples must be >= 2");
		assignValues(p, channel); // uses p.getFrame()-1
		return resampleAssignedValues(getValues(p, channel), nSamples, gridMax);
	}

	/**
	 * Resamples the intensity profile of a path onto a uniform distance grid via
	 * linear interpolation. Entries beyond the path's actual length are set to
	 * {@link Double#NaN}, which allows variable-length paths to be compared in
	 * the same matrix without padding with zeros.
	 *
	 * @param p        the path to profile (values are assigned if not yet set)
	 * @param channel  the channel to sample (base-0 index)
	 * @param frame    the frame to sample (base-0 index)
	 * @param nSamples the number of output grid points (&gt;= 2)
	 * @param gridMax  the upper bound of the distance grid. Use the path's actual
	 *                 length for a per-path-normalized grid (0..length), or a
	 *                 shared maximum across all paths for an absolute grid
	 * @return array of length {@code nSamples} with interpolated intensities
	 */
	public double[] getResampledValues(final Path p, final int channel, final int frame,
	                                   final int nSamples, final double gridMax) {
		if (nSamples < 2)
			throw new IllegalArgumentException("nSamples must be >= 2");
		assignValues(p, channel, frame);
		return resampleAssignedValues(getValues(p, channel, frame), nSamples, gridMax);
	}

	private double[] resampleAssignedValues(final Map<String, List<Double>> values,
											final int nSamples, final double gridMax) {
		if (nSamples < 2)
			throw new IllegalArgumentException("nSamples must be >= 2");
		// Always assign values explicitly: PointInImage.v defaults to 0.0, not NaN,
		// so hasNodeValues() returns true for unsampled paths, causing getValues()
		// to silently skip assignValues() and return all-zero intensities.
		final List<Double> srcX = values.get(X_VALUES);
		final List<Double> srcY = values.get(Y_VALUES);
		final double pathLen = srcX.getLast();
		final double step = gridMax / (nSamples - 1);
		final double[] result = new double[nSamples];
		int j = 0; // pointer into srcX/srcY
		for (int i = 0; i < nSamples; i++) {
			final double x = i * step;
			if (x > pathLen + 1e-9) {
				result[i] = Double.NaN;
				continue;
			}
			// advance j so srcX[j] <= x < srcX[j+1]
			while (j < srcX.size() - 2 && srcX.get(j + 1) <= x) j++;
			if (j >= srcX.size() - 1) {
				result[i] = srcY.get(srcX.size() - 1);
			} else {
				final double x0 = srcX.get(j), x1 = srcX.get(j + 1);
				final double y0 = srcY.get(j), y1 = srcY.get(j + 1);
				result[i] = (x1 == x0) ? y0 : y0 + (y1 - y0) * (x - x0) / (x1 - x0);
			}
		}
		return result;
	}

	/**
	 * Builds a multi-frame intensity profile matrix from a list of matched paths
	 * (one per time frame, ordered by frame number), suitable for kymograph-style
	 * visualization via {@link SNTChart#showHeatmap}.
	 * <p>
	 * The returned matrix has dimensions [nPaths][nSamples], where
	 * {@code matrix[i][j]} is the interpolated intensity at distance sample j for
	 * the i-th path. Entries beyond a path's actual length are {@link Double#NaN}.
	 * </p>
	 *
	 * @param paths             ordered list of paths, one per time frame
	 * @param channel           the channel to sample (base-0 index)
	 * @param nSamples          number of distance samples (columns in the result)
	 * @param normalizeDistance if true, distances are normalized to [0,1] so all
	 *                          rows span the full width regardless of path length;
	 *                          if false, absolute distances are used and the grid
	 *                          max equals the longest path in the list
	 * @return a 2D array [nPaths][nSamples] of intensity values
	 */
	public double[][] getMultiFrameProfile(final List<Path> paths, final int channel,
			final int nSamples, final boolean normalizeDistance) {
		if (paths == null || paths.isEmpty())
			throw new IllegalArgumentException("Paths list cannot be null or empty");
		// For absolute distances, share a single grid max so all rows are comparable.
		// For normalized distances, each path is resampled over its OWN length so
		// the grid always spans [0, pathLen] and maps to [0, 1] conceptually --
		// passing gridMax = 1.0 is wrong because source x-values are in physical
		// units (e.g. um), which would confine sampling to the first sub-unit segment.
		final double sharedGridMax;
		if (normalizeDistance) {
			sharedGridMax = -1; // unused; each path uses its own length below
		} else {
			double maxLen = 0;
			for (final Path p : paths) {
				final double len = p.getLength();
				if (len > maxLen) maxLen = len;
			}
			sharedGridMax = maxLen;
		}
		final double[][] matrix = new double[paths.size()][nSamples];
		for (int i = 0; i < paths.size(); i++) {
			final Path p = paths.get(i);
			final double gridMax = normalizeDistance ? p.getLength() : sharedGridMax;
			matrix[i] = getResampledValues(p, channel, nSamples, gridMax);
		}
		return matrix;
	}

	/**
	 * Gets the time profile for the specified path as a list of {@link #getValues(Path, int, int)} profiles,
	 * with one entry per frame of the dataset.
	 *
	 * @param path    the path to be profiled
	 * @param channel the channel to be parsed (base-0 index)
	 * @return the list of profile values (one entry per frame)
	 */
	public List<Map<String, List<Double>>> getTimeProfile(final Path path, final int channel) {
		if (path == null || path.size()==0)
			throw new IllegalArgumentException("Path cannot be null or empty");
		final Map<Path, double[]> nodeValuesSnapshot = TreeUtils.snapshotNodeValues(new Tree(List.of(path)));
		final List<Map<String, List<Double>>> result = new ArrayList<>();
		try {
			for (int frame = 0; frame < dataset.getFrames(); frame++) {
				assignValues(path, channel, frame);
				result.add(getValues(path, channel, frame));
			}
		} finally {
			TreeUtils.restoreNodeValues(nodeValuesSnapshot);
		}
		return result;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService snt = new SNTService();
		final Tree tree = snt.demoTree("OP_1");
		final ImagePlus imp = snt.demoImage("OP_1");
		tree.assignImage(imp);
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("tree", tree);
		inputs.put("imp", imp);
		final CommandService cmdService = SNTUtils.getContext().getService(CommandService.class);
		cmdService.run(PathProfiler.class, true, inputs);
	}
}
