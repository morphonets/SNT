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
import org.scijava.Context;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
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
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.SNTColor;

/**
 * Command to retrieve node profiles (plots of voxel intensities sampled at Path
 * nodes).
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
			PathProfiler.CIRCLE, PathProfiler.DISK, PathProfiler.SPHERE }, callback = "shapeStrChanged")
	private String shapeStr;

	@Parameter(label = "Radius (in pixels)", description = "<HTML>The size of the sampling shape.<br>"
			+ "A value >= 1 sets a fixed radius for shape neighborhood.<br>"
			+ "A value of 0 instructs the profiler to adopt the radius at each Path's node.", min = "0", callback = "updateMsg")
	private int radius;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Sampling Frequency:")
	private String HEADER2;

	@Parameter(required = true, label = "Sample every Nth node", min = "1", stepSize = "1", callback = "updateMsg")
	private int nodeStep;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Sampled Data:")
	private String HEADER3;

	@Parameter(required = false, label = "Channel to be profiled", min = "1", stepSize = "1")
	private int channel;

	@Parameter(required = false, label = "Frame to be profiled", min = "1", stepSize = "1")
	private int frame;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = EMPTY_LABEL, callback = "updateMsg")
	private String msg = "Radius: 99.99microns; Freq.: 99.99microns"; // ensure dialog is wide enough for msg

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = EMPTY_LABEL)
	private String HEADER4;

	@Parameter(label = "Output", choices = { "Plot (averaged profile)", "Detailed table" })
	private String outChoice;

	@Parameter(label = "path")
	private Path path;

	@Parameter(label = "dataset")
	private Dataset dataset;

	@Parameter(label = "imp", required = false)
	private ImagePlus imp;

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
	 * @param imp the image from which pixel intensities will be retrieved. Note
	 *            that no effort is made to ensure that the image is suitable for
	 *            profiling.
	 */
	public NodeProfiler(final ImagePlus imp) {
		if (imp == null)
			throw new IllegalArgumentException("Image cannot be null");
		this.dataset = getDatasetFromImp(imp);
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
			dataset = getDatasetFromImp(imp);
			if (dataset == null) {
				error("A valid Dataset is required but none was found.");
				return;
			}
		}
		final MutableModuleItem<Integer> mis = getInfo().getMutableInput("nodeStep", Integer.class);
		mis.setMinimumValue(1);
		mis.setMaximumValue(path.size());
		if (nodeStep >= path.size())
			mis.setValue(this, 1);
		initAvgStepAndUnitAsNeeded(path);
		// adjust channel/frame options
		if (dataset.getChannels() < 2) {
			resolveInput("channel");
			channel = 1;
		} else {
			final MutableModuleItem<Integer> mic = getInfo().getMutableInput("channel", Integer.class);
			mic.setMinimumValue(1);
			mic.setMaximumValue((int) dataset.getChannels());
			if (channel > dataset.getChannels())
				mic.setValue(this, (int) dataset.getChannels());
		}
		if (dataset.getFrames() < 2) {
			resolveInput("frame");
			frame = 1;
		} else {
			final MutableModuleItem<Integer> mif = getInfo().getMutableInput("frame", Integer.class);
			mif.setMinimumValue(1);
			mif.setMaximumValue((int) dataset.getFrames());
			if (frame > dataset.getFrames())
				mif.setValue(this, (int) dataset.getFrames());
		}
		if (dataset.getChannels() < 2 && dataset.getFrames() < 2)
			resolveInput("HEADER3");
		resolveInput("imp");
		resolveInput("dataset");
		updateMsg();
	}

	private Dataset getDatasetFromImp(final ImagePlus imp) {
		if (imp == null)
			return null;
		final ConvertService convertService = getContext().service(ConvertService.class);
		dataset = convertService.convert(imp, Dataset.class);
		if (dataset == null)
			throw new UnsupportedOperationException("BUG!?: Could not convert ImagePlus to Dataset!?");
		return dataset;
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
		case PathProfiler.CIRCLE:
			shape = ProfileProcessor.Shape.CIRCLE;
			break;
		case PathProfiler.DISK:
			shape = ProfileProcessor.Shape.DISK;
			break;
		default:
			throw new IllegalArgumentException("Unsupported/Unknown shape: " + shapeStr);
		}
	}

	@Override
	public Context getContext() {
		if (super.getContext() == null)
			setContext(SNTUtils.getContext());
		return super.getContext();
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
			if (outChoice.toLowerCase().contains("plot"))
				getPlot(path, path.getChannel(), path.getFrame()).show();
			else
				getTable(path, channel, frame).show(getTitle(path, channel, frame));
		} catch (final Exception ex) {
			if (ex instanceof ArrayIndexOutOfBoundsException)
				error("Profile could not be retrieved. Do Path nodes lay outside image bounds?");
			ex.printStackTrace();
		} finally {
			resetUI();
		}
	}

	private String getTitle(final Path path, final int channel, final int frame) {
		final StringBuilder sb = new StringBuilder(path.getName());
		sb.append(" Cross Profile").append(" [C").append(channel).append("T").append(frame).append("]");
		return sb.toString();
	}

	private void validateChannelRange(final int channel) {
		if (channel < 1 || channel > dataset.getChannels())
			throw new IllegalArgumentException(
					"Specified channel " + channel + " out of range: Only 1-" + dataset.getChannels() + " allowed");
	}

	private String getXAxisLabel(final Path path) {
		initAvgStepAndUnitAsNeeded(path);
		return String.format("Distance to center [%s%s] (%s)", shape.toString().toLowerCase(),
				(radius == 0 && path != null && path.hasRadii()) ? "; path radii" : "", unit);
	}

	private String getYAxisLabel(final int channel, final int frame) {
		final StringBuilder sb = new StringBuilder();
		if (channel > 0 && dataset.getChannels() > 1) {
			sb.append("Ch ").append(channel).append(" ");
		}
		if (frame > 0 && dataset.getFrames() > 1) {
			sb.append("Frame ").append(frame).append(" ");
		}
		sb.append(dataset.getValidBits()).append("-bit ");
		sb.append("Intensity");
		return sb.toString();
	}

	public <T extends RealType<T>> SortedMap<Integer, List<Double>> getValues(final Path p) {
		return getValues(p, p.getChannel(), p.getFrame());
	}

	public <T extends RealType<T>> SortedMap<Integer, List<Double>> getValues(final Path p, final int channel,
			final int frame) throws ArrayIndexOutOfBoundsException {
		validateChannelRange(channel);
		final RandomAccessibleInterval<T> rai = ImgUtils.getCtSlice(dataset, channel - 1, frame - 1);
		final ProfileProcessor<T> processor = new ProfileProcessor<>(rai, p);
		processor.setShape(shape);
		processor.setRadius(radius);
		return processor.getRawValues(nodeStep);
	}

	/**
	 * 
	 * @param path the path to be profiled, using its CT positions in the image
	 * @return The profile (mean+-SD of all the profiled data)
	 */
	public Plot getPlot(final Path path) {
		return getPlot(path, path.getChannel(), path.getFrame());
	}

	/**
	 * 
	 * @param path    the path to be profiled
	 * @param channel the channel to be profiled
	 * @param frame   the frame to be profiled
	 * @return The profile (mean+-SD of all the profiled data)
	 */
	public Plot getPlot(final Path path, final int channel, final int frame) {
		final Plot plot = new Plot(getTitle(path, channel, frame), getXAxisLabel(path), getYAxisLabel(channel, frame));
		final SNTTable table = getTable(path, channel, frame);
		final ArrayList<Double> ymeans = new ArrayList<>();
		final ArrayList<Double> ystds = new ArrayList<>();
		for (int row = 0; row < table.getRowCount(); row++) {
			final SummaryStatistics stats = table.geRowStats(row, 0, table.getColumnCount() - 1);
			ymeans.add(stats.getMean());
			ystds.add(stats.getStandardDeviation());
		}
		final ArrayList<Double> xvalues = new ArrayList<>();
		final int centerIndex = (int) (table.getRowCount() / 2);
		for (int row = -centerIndex; row <= centerIndex; row++)
			xvalues.add((avgSep > 0) ? row * avgSep : row);
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
		return getTable(path, path.getChannel(), path.getFrame());
	}

	/**
	 * @param path    the path to be profiled, using its CT positions in the image
	 * @param channel the channel to be profiled
	 * @param frame   the frame to be profiled
	 * @return the profiled raw data in tabular form (1 column-per profiled node)
	 */
	public SNTTable getTable(final Path path, final int channel, final int frame) {
		final SNTTable table = new SNTTable();
		final SortedMap<Integer, List<Double>> profileMap = getValues(path, channel, frame);
		profileMap.forEach((position, profile) -> table.addColumn("Node#" + position, profile));
		table.fillEmptyCells(Double.NaN);
		return table;
	}

	/**
	 * 
	 * @param shape Either {@link ProfileProcessor.Shape.CIRCLE},
	 *              {@link ProfileProcessor.Shape.DISK}, or
	 *              {@link ProfileProcessor.Shape.HYPERSPHERE}
	 */
	public void setShape(final ProfileProcessor.Shape shape) {
		if (shape == ProfileProcessor.Shape.CENTERLINE)
			throw new IllegalArgumentException("Unsupported Shape: Only " + ProfileProcessor.Shape.CIRCLE + ", "
					+ ProfileProcessor.Shape.DISK + ", " + ProfileProcessor.Shape.HYPERSPHERE + ", etc. supported.");
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
		final Path path = new SNTService().demoTree("OP_1").get(0);
		final ImagePlus imp = new SNTService().demoImage("OP_1");

		// Run GUI command w/ prompt
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("path", path);
		inputs.put("imp", imp);
		final CommandService cmdService = SNTUtils.getContext().getService(CommandService.class);
		cmdService.run(NodeProfiler.class, true, inputs);

		// Run via scripting
		final NodeProfiler cp = new NodeProfiler(imp);
		cp.setNodeStep(10);
		cp.setRadius(4);
		cp.setShape(ProfileProcessor.Shape.DISK);
		cp.getPlot(path).show();
	}
}
