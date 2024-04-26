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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.SortedMap;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plot.PlotService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.gui.Plot;
import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.SNTColor;

/**
 * Command to retrieve node profiles (plots of voxel intensities sampled across
 * Path nodes).
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Node Profiler", initializer = "init")
public class NodeProfiler extends CommonDynamicCmd {

	@Parameter
	private PlotService plotService;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Sampling Neighborhood:")
	private String HEADER1;

	@Parameter(label = "Shape (centered at each node)", description = "<HTML>The image neighborhood to be measured around each Path node.", choices = {
			PathProfiler.NONE, PathProfiler.CIRCLE2D, PathProfiler.DISK2D,
			PathProfiler.SPHERE }, callback = "shapeStrChanged")
	private String shapeStr;

	@Parameter(label = "Radius (in pixels)", description = "<HTML>The size of the sampling shape.<br>"
			+ "A value >= 1 sets a fixed radius for shape neighborhood.<br>"
			+ "A value of 0 instructs the profiler to adopt the radius at each Path's node.", min = "0", callback = "updateMsg")
	private int radius;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Sampling Frequency:")
	private String HEADER2;

	@Parameter(required = true, label = "Sample every Nth node", min = "1", stepSize = "1", callback = "updateMsg")
	private int nodeStep;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = EMPTY_LABEL, callback = "updateMsg")
	private String msg = "Radius: 99.99microns; Freq.: 99.99microns"; // ensure dialog is wide enough for msg

	@Parameter(label = "Output", choices = { "Plot (Mean±SD)", "Detailed table(s)" })
	private String outChoice;

	@Parameter(label = "path")
	private Path path;

	@Parameter(label = "dataset")
	private Dataset dataset;

	private ProfileProcessor.Shape shape = ProfileProcessor.Shape.HYPERSPHERE;
	private double avgSep;
	private String unit;

	/**
	 * Empty constructor used by CommandService before calling {@link #run()}.
	 * Should not be called directly.
	 */
	public NodeProfiler() {
		super(); // required for calling run() from CommandService
	}

	/**
	 * Instantiates a new Profiler.
	 *
	 * @param dataset the image from which pixel intensities will be retrieved. Note
	 *                that no effort is made to ensure that the image is suitable
	 *                for profiling.
	 */
	public NodeProfiler(final Dataset dataset) {
		if (dataset == null)
			throw new IllegalArgumentException("Dataset cannot be null");
		this.dataset = dataset;
	}

	private void updateMsg() {
		if (avgSep == 0) {
			msg = "";
		} else {
			final StringBuilder sb = new StringBuilder("Radius: ");
			if (radius == 0)
				sb.append("Path radii");
			else
				sb.append(SNTUtils.formatDouble(avgSep * radius, 2)).append(unit);
			sb.append("; Freq.: ").append(SNTUtils.formatDouble(avgSep * nodeStep, 2)).append(unit);
			msg = sb.toString();
		}
	}

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (path == null || path.size() == 0) {
			error("A valid Path is required but none was found.");
			return;
		}
		if (dataset == null) {
			error("A valid Dataset is required but none was found.");
			return;
		}
		initAvgStepAndUnitAsNeeded(path);
		// adjust 2D/3D options
		final MutableModuleItem<String> mi = getInfo().getMutableInput("shapeStr", String.class);
		if (dataset.getDepth() > 1) {
			mi.setChoices(
					List.of(PathProfiler.LINE3D, PathProfiler.CIRCLE3D, PathProfiler.DISK3D, PathProfiler.SPHERE));
		} else {
			mi.setChoices(List.of(PathProfiler.LINE2D, PathProfiler.CIRCLE2D, PathProfiler.DISK2D));
		}
		// adjust node step
		final MutableModuleItem<Integer> mis = getInfo().getMutableInput("nodeStep", Integer.class);
		mis.setMinimumValue(1);
		mis.setMaximumValue(path.size());
		if (nodeStep >= path.size())
			mis.setValue(this, 1);
		resolveInput("imp");
		resolveInput("dataset");
		updateMsg();
	}

	private void initAvgStepAndUnitAsNeeded(final Path path) {
		if (unit == null || avgSep == 0) {
			final Calibration cal = path.getCalibration();
			unit = cal.getUnit();
			if (dataset.getDepth() > 1)
				avgSep = (cal.pixelWidth + cal.pixelHeight) / 2;
			else
				avgSep = (cal.pixelWidth + cal.pixelHeight + cal.pixelDepth) / 3;
		}
	}

	private void evalParameters() {
		switch (shapeStr) {
		case PathProfiler.SPHERE:
			shape = ProfileProcessor.Shape.HYPERSPHERE;
			break;
		case PathProfiler.CIRCLE2D:
		case PathProfiler.CIRCLE3D:
			shape = ProfileProcessor.Shape.CIRCLE;
			break;
		case PathProfiler.DISK2D:
		case PathProfiler.DISK3D:
			shape = ProfileProcessor.Shape.DISK;
			break;
		case PathProfiler.LINE2D:
		case PathProfiler.LINE3D:
			shape = ProfileProcessor.Shape.LINE;
			break;
		default:
			throw new IllegalArgumentException("Unsupported/Unknown shape: " + shapeStr);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (getContext() == null) {
			throw new IllegalArgumentException(
					"No context has been set. Note that this method should only be called from CommandService.");
		}
		status("Retrieving cross profile...", false);
		evalParameters();
		if (radius == 0 && !path.hasRadii()
				&& !new GuiUtils(snt.getUI().getPathManager())
						.getConfirmation(
								"You have instructed the profiler to adopt the radius at each Path's node (radius = 0),"
										+ " but the selected Path has no radii. Proceed Nevertheless?",
								"Path Has No Radii")) {
			return;
		}
		try {
			if (outChoice.toLowerCase().contains("plot")) {
				getPlot(path).show();
			} else {
				for (int ch = 0; ch < dataset.getChannels(); ch++)
					getTable(path, ch).show(getTitle(path, ch));
			}
		} catch (final Exception ex) {
			if (ex instanceof ArrayIndexOutOfBoundsException)
				error("Profile could not be retrieved. Do Path nodes lay outside image bounds?");
			ex.printStackTrace();
		} finally {
			resetUI();
		}
	}

	private String getTitle(final Path path, final int channel) {
		final StringBuilder sb = new StringBuilder(path.getName());
		sb.append(" Cross Profile");
		if (channel < 0)
			sb.append(" [All Channels]");
		else
			sb.append(" [C").append(channel + 1).append("]");
		return sb.toString();
	}

	private void validateChannelRange(final int channel) {
		if (channel < 0 || channel > dataset.getChannels())
			throw new IllegalArgumentException(
					"Specified channel " + channel + " out of range: Only [0-" + dataset.getChannels() + "[ allowed");
	}

	private String getXAxisLabel(final Path path) {
		initAvgStepAndUnitAsNeeded(path);
		return String.format("Distance to center (%s) [%s%s]", unit, shape.toString().toLowerCase(),
				(radius == 0 && path != null && path.hasRadii()) ? "; path radii" : "");
	}

	private String getYAxisLabel(final Path path, final int channel) {
		final StringBuilder sb = new StringBuilder();
		if (channel > 0 && dataset.getChannels() > 1) {
			sb.append("Ch ").append(channel + 1).append(" ");
		}
		if (dataset.getFrames() > 1) {
			sb.append("Frame ").append(path.getFrame()).append(" ");
		}
		sb.append(dataset.getValidBits()).append("-bit ");
		sb.append("Intensity");
		return sb.toString();
	}

	/**
	 * Gets the profile for the specified path as a map of lists of pixel
	 * intensities (profile indices as keys)
	 *
	 * @param p the path to be profiled, using its CT positions in the image
	 * @return The profile
	 */
	public <T extends RealType<T>> SortedMap<Integer, List<Double>> getValues(final Path p) {
		return getValues(p, p.getChannel() - 1, p.getFrame() - 1);
	}

	/**
	 * Gets the profile for the specified path as a map of lists of pixel
	 * intensities (profile indices as keys)
	 *
	 * @param p       the path to be profiled
	 * @param channel the channel to be profiled (0-based index)
	 * @param frame   the frame to be profiled (0-based index)
	 * @return The profile
	 */
	public <T extends RealType<T>> SortedMap<Integer, List<Double>> getValues(final Path p, final int channel,
			final int frame) throws ArrayIndexOutOfBoundsException {
		validateChannelRange(channel);
		final RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice(dataset, channel, frame);
		final ProfileProcessor<T> processor = new ProfileProcessor<>(rai, p);
		processor.setShape(shape);
		processor.setRadius(radius);
		return processor.getRawValues(nodeStep);
	}

	/**
	 *
	 * @param path the path to be profiled, using its CT positions in the image
	 * @return The profile (Mean±SD of all the profiled data)
	 */
	public Plot getPlot(final Path path) {
		final Plot plot = new Plot(getTitle(path, -1), getXAxisLabel(path), getYAxisLabel(path, -1));
		final Color[] colors = SNTColor.getDistinctColorsAWT((int) dataset.getChannels());
		StringBuilder legend = new StringBuilder();
		for (int ch = 0; ch < dataset.getChannels(); ch++) {
			System.out.println("Processing " + ch);
			final SNTTable table = getTable(path, ch);
			final ArrayList<Double> ymeans = new ArrayList<>();
			final ArrayList<Double> ystds = new ArrayList<>();
			for (int row = 0; row < table.getRowCount(); row++) {
				final SummaryStatistics stats = table.geRowStats(row, 0, table.getColumnCount() - 1);
				ymeans.add(stats.getMean());
				ystds.add(stats.getStandardDeviation());
			}
			final ArrayList<Double> xvalues = assembleXValuesFromProfiledValues(table);
			plot.setColor(colors[ch], colors[ch]);
			plot.addPoints(xvalues, ymeans, ystds, Plot.CONNECTED_CIRCLES);
			legend.append("Ch ").append(ch + 1).append("\n"); // + " Mean±SD\n";
		}
		plot.setColor(Color.BLACK, null);
		plot.setLegend(legend.toString(), Plot.AUTO_POSITION);
		plot.setLimitsToFit(true);
		return plot;
	}

	private ArrayList<Double> assembleXValuesFromProfiledValues(final SNTTable table) {
		final ArrayList<Double> xvalues = new ArrayList<>();
		final int centerIndex = (int) (table.getRowCount() / 2);
		for (int row = -centerIndex; row <= centerIndex; row++)
			xvalues.add((avgSep > 0) ? row * avgSep : row);
		return xvalues;
	}

	/**
	 * 
	 * @param path    the path to be profiled
	 * @param channel the channel to be profiled (0-based index)
	 * @return The profile (Mean±SD of all the profiled data)
	 */
	public Plot getPlot(final Path path, final int channel) {
		final Plot plot = new Plot(getTitle(path, channel), getXAxisLabel(path), getYAxisLabel(path, channel));
		final SNTTable table = getTable(path, channel);
		final ArrayList<Double> ymeans = new ArrayList<>();
		final ArrayList<Double> ystds = new ArrayList<>();
		for (int row = 0; row < table.getRowCount(); row++) {
			final SummaryStatistics stats = table.geRowStats(row, 0, table.getColumnCount() - 1);
			ymeans.add(stats.getMean());
			ystds.add(stats.getStandardDeviation());
		}
		final ArrayList<Double> xvalues = assembleXValuesFromProfiledValues(table);
		plot.setColor((path.getColor() == null) ? SNTColor.getDistinctColorsAWT(1)[0] : path.getColor(), null);
		plot.addPoints(xvalues, ymeans, ystds, Plot.CONNECTED_CIRCLES);
		plot.setColor(Color.BLACK, null);
		plot.setLegend("Mean±SD", Plot.AUTO_POSITION);
		plot.setLimitsToFit(true);
		return plot;
	}

	/**
	 * 
	 * @param path the path to be profiled, using its CT positions in the image
	 * @return the profiled raw data in tabular form (1 column-per profiled node)
	 */
	public SNTTable getTable(final Path path) {
		return getTable(path, path.getChannel() - 1);
	}

	/**
	 * @param path    the path to be profiled, using its CT positions in the image
	 * @param channel the channel to be profiled (0-based index)
	 * @return the profiled raw data in tabular form (1 column-per profiled node)
	 */
	public SNTTable getTable(final Path path, final int channel) {
		final SNTTable table = new SNTTable();
		final SortedMap<Integer, List<Double>> profileMap = getValues(path, channel, path.getFrame() - 1);
		profileMap.forEach((position, profile) -> table.addColumn("Node#" + position, profile));
		table.fillEmptyCells(Double.NaN);
		return table;
	}

	/**
	 * Sets the shape of the iterating cursor.
	 *
	 * @param shape A {@link ProfileProcessor.Shape}
	 */
	public void setShape(final ProfileProcessor.Shape shape) {
		if (shape == ProfileProcessor.Shape.NONE)
			throw new IllegalArgumentException("Unsupported Shape: Only " + ProfileProcessor.Shape.LINE + ", "
					+ ProfileProcessor.Shape.CIRCLE + ", " + ProfileProcessor.Shape.DISK + ", etc. supported.");
		this.shape = shape;
	}

	/**
	 * 
	 * @param radius the radius (in pixels) of sampling shape
	 */
	public void setRadius(final int radius) {
		this.radius = radius;
	}

	/**
	 * 
	 * @param nodeStep sets the sampling frequency. I.e., if 10, each 10th node is
	 *                 sampled.
	 */
	public void setNodeStep(final int nodeStep) {
		this.nodeStep = nodeStep;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService snt = new SNTService();
		final Tree tree = snt.demoTree("OP_1");
		final ImagePlus imp = snt.demoImage("OP_1");
		tree.assignImage(imp);

		// Run GUI command w/ prompt
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("path", tree.get(0));
		inputs.put("dataset", ImpUtils.toDataset(imp));
		final CommandService cmdService = SNTUtils.getContext().getService(CommandService.class);
		cmdService.run(NodeProfiler.class, true, inputs);

		// Run via scripting
		final NodeProfiler np = new NodeProfiler(ImpUtils.toDataset(imp));
		np.setNodeStep(10);
		np.setRadius(4);
		np.setShape(ProfileProcessor.Shape.LINE);
		np.getPlot(tree.get(0)).show();
	}
}
