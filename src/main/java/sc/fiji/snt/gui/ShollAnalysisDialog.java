/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

package sc.fiji.snt.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import sc.fiji.snt.SNT;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.plugin.ij1.Sholl_Analysis;
import util.FindConnectedRegions;

@Deprecated
public class ShollAnalysisDialog extends JDialog implements ActionListener {

	private static final long serialVersionUID = 1L;

	private final double x_start, y_start, z_start;

	private final ButtonGroup pathsGroup;
	private final JRadioButton useAllPathsCheckbox;
	private final JRadioButton useSelectedPathsCheckbox;

	private final JButton swcTypesButton = new JButton("SWC Type Filtering...");
	private final JPopupMenu swcTypesMenu = new JPopupMenu();
	private final ArrayList<String> filteredTypes = Path.getSWCtypeNames();
	private final JLabel filteredTypesWarningLabel = new JLabel();

	private final JButton makeShollImageButton = new JButton("Sholl Image");
	private final JButton exportProfileButton = new JButton("Save Profile...");
	private final JButton drawShollGraphButton = new JButton("Preview Plot");
	private final JButton analyzeButton = new JButton(
		"Analyze Profile (Sholl Analysis v" + SNTUtils.VERSION + ")...");

	private final ButtonGroup axesGroup;
	private final JRadioButton normalAxes;
	private final JRadioButton semiLogAxes;
	private final JRadioButton logLogAxes;

	private final ButtonGroup normalizationGroup;
	private final JRadioButton noNormalization;
	private final JRadioButton normalizationForSphereVolume;
	private String exportPath;
	private final int margin = 10;

	private GraphFrame graphFrame;
	private int numberOfSelectedPaths;
	private int numberOfAllPaths;
	private double sampleSeparation;
	private final ArrayList<ShollPoint> shollPointsAllPaths;
	private final ArrayList<ShollPoint> shollPointsSelectedPaths;
	private final PathAndFillManager shollpafm;
	private final ResultsPanel resultsPanel = new ResultsPanel();
	private final GuiUtils gUtils;
	private final SNT plugin;

	public ShollAnalysisDialog(final double x_start, final double y_start,
		final double z_start, final SNT plugin)
	{

		super(plugin.getUI(), "Sholl Analysis Dialog", false);

		this.x_start = x_start;
		this.y_start = y_start;
		this.z_start = z_start;
		this.plugin = plugin;
		gUtils = new GuiUtils(this);
		shollPointsAllPaths = new ArrayList<>();
		shollPointsSelectedPaths = new ArrayList<>();
		shollpafm = plugin.getPathAndFillManager();
		reloadPaths();

		pathsGroup = new ButtonGroup();
		useAllPathsCheckbox = new JRadioButton("Include all paths (" +
			numberOfAllPaths + ")", true);
		useSelectedPathsCheckbox = new JRadioButton(
			"Include only selected path(s) (" + numberOfSelectedPaths + ")", false);
		pathsGroup.add(useAllPathsCheckbox);
		pathsGroup.add(useSelectedPathsCheckbox);

		axesGroup = new ButtonGroup();
		normalAxes = new JRadioButton("Standard (linear) axes");
		semiLogAxes = new JRadioButton("Semi-log axes");
		logLogAxes = new JRadioButton("Log-log axes");
		normalAxes.setSelected(true);
		axesGroup.add(normalAxes);
		axesGroup.add(semiLogAxes);
		axesGroup.add(logLogAxes);

		normalizationGroup = new ButtonGroup();
		noNormalization = new JRadioButton("No normalization of intersections",
			true);
		normalizationForSphereVolume = new JRadioButton(
			"[BUG: should be set in the constructor]", false);
		normalizationGroup.add(noNormalization);
		normalizationGroup.add(normalizationForSphereVolume);

		setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.NONE;

		c.insets = new Insets(margin, margin, 0, margin);
		useAllPathsCheckbox.addActionListener(this);
		add(useAllPathsCheckbox, c);

		++c.gridy;
		c.insets = new Insets(0, margin, margin, margin);
		add(useSelectedPathsCheckbox, c);
		useSelectedPathsCheckbox.addActionListener(this);

		++c.gridy;
		c.insets = new Insets(0, margin, 0, margin);
		buildTypeFilteringMenu();
		add(swcTypesButton, c);
		++c.gridy;
		c.insets = new Insets(0, margin, margin, margin);
		add(filteredTypesWarningLabel, c);
		makePromptInteractive(true);

		++c.gridy;
		c.insets = new Insets(margin, margin, 0, margin);
		add(normalAxes, c);
		normalAxes.addActionListener(this);
		++c.gridy;
		c.insets = new Insets(0, margin, 0, margin);
		add(semiLogAxes, c);
		semiLogAxes.addActionListener(this);
		++c.gridy;
		c.insets = new Insets(0, margin, margin, margin);
		add(logLogAxes, c);
		logLogAxes.addActionListener(this);

		++c.gridy;
		c.insets = new Insets(margin, margin, 0, margin);
		add(noNormalization, c);
		noNormalization.addActionListener(this);
		++c.gridy;
		c.insets = new Insets(0, margin, margin, margin);
		if (plugin.is2D()) normalizationForSphereVolume.setText(
			"Normalize for area of sampling circle");
		else normalizationForSphereVolume.setText(
			"Normalize for volume of sampling sphere");
		add(normalizationForSphereVolume, c);
		normalizationForSphereVolume.addActionListener(this);

		++c.gridy;
		c.gridx = 0;
		c.insets = new Insets(margin, margin, 0, margin);
		final JPanel separationPanel = new JPanel();
		separationPanel.setBorder(null);
		final JSpinner spinner = GuiUtils.doubleSpinner(0, 0,
			getLargestDimension() / 2, plugin.getMinimumSeparation(), 2);
		spinner.addChangeListener(e -> {
			sampleSeparation = (double) spinner.getValue();
			updateResults();
		});
		separationPanel.add(new JLabel("Radius step size:"));
		separationPanel.add(spinner);
		final String unit = plugin.getImagePlus().getCalibration().getUnit();
		if (unit != null && !unit.equals("unknown") && !unit.equals("pixel"))
			separationPanel.add(new JLabel(unit));
		add(separationPanel, c);
		c.gridx = 0;
		++c.gridy;
		c.insets = new Insets(0, margin, margin, margin);
		add(new JLabel("(0 for continuous sampling)"), c);
		++c.gridy;

		c.insets = new Insets(margin * 2, margin, margin * 2, margin);
		add(resultsPanel, c);

		++c.gridy;
		final JPanel buttonsPanel = new JPanel();
		buttonsPanel.setLayout(new BorderLayout());
		final JPanel topRow = new JPanel();
		final JPanel middleRow = new JPanel();
		final JPanel bottomRow = new JPanel();

		topRow.add(makeShollImageButton);
		makeShollImageButton.addActionListener(this);

		topRow.add(drawShollGraphButton);
		drawShollGraphButton.addActionListener(this);

		topRow.add(exportProfileButton);
		exportProfileButton.addActionListener(this);

		middleRow.add(analyzeButton);
		analyzeButton.addActionListener(this);

		buttonsPanel.add(topRow, BorderLayout.NORTH);
		buttonsPanel.add(middleRow, BorderLayout.CENTER);
		buttonsPanel.add(bottomRow, BorderLayout.SOUTH);

		add(buttonsPanel, c);
		updateResults();
		pack();
		setLocationRelativeTo(plugin.getUI());
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setVisible(true);
		toFront();
	}

	private double getLargestDimension() {
		final BoundingBox box = plugin.getPathAndFillManager().getBoundingBox(
			false);
		final double[] dims = box.getDimensions(true);
		return Math.max(dims[0], Math.max(dims[1], dims[2]));
	}

	@Override
	public void actionPerformed(final ActionEvent e) {
		final Object source = e.getSource();
		final ShollResults results;
		synchronized (this) {
			results = getCurrentResults();
		}
		if (results == null) {
			gUtils.error("The sphere separation field must be a valid number");
			return;
		}
		if (source == makeShollImageButton) {
			results.makeShollCrossingsImagePlus(plugin.getImagePlus());

		}
		else if (source == analyzeButton) {

			final Thread newThread = new Thread(() -> {
				analyzeButton.setEnabled(false);
				analyzeButton.setText("Running Analysis. Please wait...");
				results.analyzeWithShollAnalysisPlugin(getExportPath(), shollpafm
					.getPathsStructured().length);
				analyzeButton.setText("Analyze Profile (Sholl Analysis v" + SNTUtils.VERSION + ")...");
				analyzeButton.setEnabled(true);
			});
			newThread.start();
			return;

		}
		else if (source == exportProfileButton) {

			// We only only to save the detailed profile. Summary profile will
			// be handled by sholl.Sholl_Analysis

			final SaveDialog sd = new SaveDialog("Export data as...", getExportPath(),
				plugin.getImagePlus().getTitle() + "-sholl" + results
					.getSuggestedSuffix(), ".csv");

			if (sd.getFileName() == null) {
				return;
			}

			final File saveFile = new File(exportPath = sd.getDirectory(), sd
				.getFileName());
			if ((saveFile != null) && saveFile.exists()) {
				if (!gUtils.getConfirmation("Export data...", "The file " + saveFile
					.getAbsolutePath() + " already exists.\n" +
					"Do you want to replace it?")) return;
			}

			plugin.getUI().showStatus("Exporting CSV to " + saveFile
				.getAbsolutePath(), false);

			try {
				results.exportDetailToCSV(saveFile);
			}
			catch (final IOException ioe) {
				gUtils.error("Saving to " + saveFile.getAbsolutePath() + " failed");
				return;
			}
			plugin.getUI().showStatus("", false);

		}
		else if (source == drawShollGraphButton) {
			graphFrame.setVisible(true);
		}
		else {
			updateResults();
		}
	}

	private synchronized void updateResults() {
		JFreeChart chart;

		if (numberOfAllPaths <= 0) {

			makePromptInteractive(false);
			if (graphFrame != null) {
				chart = graphFrame.chartPanel.getChart();
				if (chart != null) {
					chart.setNotify(false);
					final TextTitle currentitle = chart.getTitle();
					if (currentitle != null) currentitle.setText("");
					final XYPlot plot = chart.getXYPlot();
					if (plot != null) plot.setDataset(null);
					chart.setNotify(true);
				}
			}

		}
		else { // valid paths to be analyzed

			makePromptInteractive(true);
			final ShollResults results = getCurrentResults();
			resultsPanel.updateFromResults(results);
			chart = results.createGraph();
			if (chart == null) {
				gUtils.error("Invalid data. Please revise options");
				return;
			}
			if (graphFrame == null) graphFrame = new GraphFrame(chart, results
				.getSuggestedSuffix());
			else graphFrame.updateWithNewChart(chart, results.getSuggestedSuffix());
		}

	}

	private void makePromptInteractive(final boolean interactive) {
		if (!interactive) {
			final String noData = " ";
			resultsPanel.criticalValuesLabel.setText(noData);
			resultsPanel.dendriteMaximumLabel.setText(noData);
			resultsPanel.shollsRegressionCoefficientLabel.setText(noData);
			resultsPanel.shollsRegressionInterceptLabel.setText(noData);
			resultsPanel.shollsRegressionRSquaredLabel.setText(noData);
			filteredTypesWarningLabel.setText("No paths matching current filter(s).");
			filteredTypesWarningLabel.setForeground(java.awt.Color.RED);
		}
		else {
			filteredTypesWarningLabel.setText("" + filteredTypes.size() +
				" type(s) are currently selected");
			filteredTypesWarningLabel.setForeground(java.awt.Color.DARK_GRAY);
		}
		drawShollGraphButton.setEnabled(interactive);
		exportProfileButton.setEnabled(interactive);
		analyzeButton.setEnabled(interactive);
		makeShollImageButton.setEnabled(interactive);
	}

	private ShollResults getCurrentResults() {
		List<ShollPoint> pointsToUse;
		String description = "Sholl analysis ";
		final String postDescription = " for " + plugin.getImagePlus().getTitle();
		final boolean useAllPaths = !useSelectedPathsCheckbox.isSelected();
		if (useAllPaths) {
			pointsToUse = shollPointsAllPaths;
			description += "of all paths" + postDescription;
		}
		else {
			pointsToUse = shollPointsSelectedPaths;
			description += "of selected paths " + postDescription;
		}

		int axes = 0;
		if (normalAxes.isSelected()) axes = AXES_NORMAL;
		else if (semiLogAxes.isSelected()) axes = AXES_SEMI_LOG;
		else if (logLogAxes.isSelected()) axes = AXES_LOG_LOG;
		else throw new RuntimeException(
			"BUG: somehow no axis checkbox was selected");

		int normalization = 0;
		if (noNormalization.isSelected()) normalization = NOT_NORMALIZED;
		else if (normalizationForSphereVolume.isSelected()) normalization =
			NORMALIZED_FOR_SPHERE_VOLUME;
		else throw new RuntimeException(
			"BUG: somehow no normalization checkbox was selected");

		return new ShollResults(pointsToUse, plugin
			.getImagePlus(), useAllPaths, useAllPaths ? numberOfAllPaths
				: numberOfSelectedPaths, x_start, y_start, z_start, description, axes,
			normalization, sampleSeparation, plugin.is2D());
	}

	private JDialog getDialog() {
		return this;
	}

	private class GraphFrame extends JFrame implements ActionListener {

		private static final long serialVersionUID = 1L;
		private final JButton exportButton;
		private JFreeChart chart = null;
		private ChartPanel chartPanel = null;
		private final JPanel mainPanel;
		private String suggestedSuffix;

		private void updateWithNewChart(final JFreeChart chart,
			final String suggestedSuffix)
		{
			updateWithNewChart(chart, suggestedSuffix, false);
		}

		synchronized private void updateWithNewChart(final JFreeChart chart,
			final String suggestedSuffix, final boolean setSize)
		{
			this.suggestedSuffix = suggestedSuffix;
			if (chartPanel != null) remove(chartPanel);
			chartPanel = null;
			this.chart = chart;
			chartPanel = new ChartPanel(chart);
			if (setSize) chartPanel.setPreferredSize(new java.awt.Dimension(800,
				600));
			mainPanel.add(chartPanel, BorderLayout.CENTER);
			validate();
		}

		private GraphFrame(final JFreeChart chart, final String suggestedSuffix) {
			super();

			this.suggestedSuffix = suggestedSuffix;

			mainPanel = new JPanel();
			mainPanel.setLayout(new BorderLayout());

			updateWithNewChart(chart, suggestedSuffix, true);

			final JPanel buttonsPanel = new JPanel();
			exportButton = new JButton("Export graph as SVG");
			exportButton.addActionListener(this);
			buttonsPanel.add(exportButton);
			mainPanel.add(buttonsPanel, BorderLayout.SOUTH);

			setContentPane(mainPanel);
			validate();
			setSize(new java.awt.Dimension(500, 270));
			setLocationRelativeTo(getDialog());
			setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			final Object source = e.getSource();
			if (source == exportButton) {
				exportGraphAsSVG();
			}
		}

		private void exportGraphAsSVG() {

			final SaveDialog sd = new SaveDialog("Export graph as...", "sholl" +
				suggestedSuffix, ".svg");

			if (sd.getFileName() == null) {
				return;
			}

			final File saveFile = new File(sd.getDirectory(), sd.getFileName());
			if ((saveFile != null) && saveFile.exists()) {
				if (!gUtils.getConfirmation("Export graph...", "The file " + saveFile
					.getAbsolutePath() + " already exists.\n" +
					"Do you want to replace it?")) return;
			}

			try {
				plugin.getUI().showStatus("Exporting graph to " + saveFile
					.getAbsolutePath(), false);
				exportChartAsSVG(chart, chartPanel.getBounds(), saveFile);
			}
			catch (final IOException ioe) {
				gUtils.error("Saving to " + saveFile.getAbsolutePath() + " failed");
				return;
			}
			plugin.getUI().showStatus("", false);

		}

		/**
		 * Exports a JFreeChart to a SVG file.
		 *
		 * @param chart JFreeChart to export
		 * @param bounds the dimensions of the viewport
		 * @param svgFile the output file.
		 * @throws IOException if writing the svgFile fails. This method is taken
		 *           from: http://dolf.trieschnigg.nl/jfreechart/
		 */
		private void exportChartAsSVG(final JFreeChart chart,
			final Rectangle bounds, final File svgFile) throws IOException
		{

			// Get a DOMImplementation and create an XML document
			final DOMImplementation domImpl = GenericDOMImplementation
				.getDOMImplementation();
			final Document document = domImpl.createDocument(null, "svg", null);

			// Create an instance of the SVG Generator
			final SVGGraphics2D svgGenerator = new SVGGraphics2D(document);

			// draw the chart in the SVG generator
			chart.draw(svgGenerator, bounds);

			// Write svg file
			final OutputStream outputStream = Files.newOutputStream(svgFile.toPath());
			final Writer out = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
			svgGenerator.stream(out, true /* use css */);
			outputStream.flush();
			outputStream.close();
		}
	}

	public static final int AXES_NORMAL = 1;
	private static final int AXES_SEMI_LOG = 2;
	private static final int AXES_LOG_LOG = 3;

	private static final String[] axesParameters = { null, "normal", "semi-log",
		"log-log" };

	public static final int NOT_NORMALIZED = 1;
	public static final int NORMALIZED_FOR_SPHERE_VOLUME = 2;

	private static final String[] normalizationParameters = { null,
		"not-normalized", "normalized" };

	@Deprecated
	public static class ShollResults {

		private final double[] squaredRangeStarts;
		private final int[] crossingsPastEach;
		private final int n;
		private final double x_start, y_start, z_start;
		/* maxCrossings is the same as the "Dendrite Maximum". */
		private int maxCrossings = Integer.MIN_VALUE;
		private double criticalValue = Double.MIN_VALUE;
		private final String description;
		private final int axes;
		private final int normalization;
		private final double sphereSeparation;
		private final double[] x_graph_points;
		private final double[] y_graph_points;
		private double minY;
		private double maxY;
		private final int graphPoints;
		private String yAxisLabel;
		private final String xAxisLabel;
		private double regressionGradient = Double.MIN_VALUE;
		private double regressionIntercept = Double.MIN_VALUE;
		private double regressionRSquare = Double.NaN;
		private int n_samples;
		private double[] sampled_distances;
		private double[] sampled_counts;
		String parametersSuffix;

		public double[] getSampledDistances() {
			return sampled_distances;
		}

		public double[] getSampledCounts() {
			return sampled_counts;
		}

		public int getDendriteMaximum() {
			return maxCrossings;
		}

		public double getCriticalValue() {
			return criticalValue;
		}

		public double getRegressionGradient() {
			return regressionGradient;
		}

		public double getShollRegressionCoefficient() {
			return -regressionGradient;
		}

		public double getRegressionIntercept() {
			return regressionIntercept;
		}

		public double getRegressionRSquare() {
			return regressionRSquare;
		}

		public double getMaxDistanceSquared() {
			return squaredRangeStarts[n - 1];
		}

		public String getSuggestedSuffix() {
			return parametersSuffix;
		}

		/*
		 * Instructs the Sholl Analysis plugin to analyze the profile sampled by .
		 */
		public void analyzeWithShollAnalysisPlugin(final String exportDir,
			final double primaryBranches)
		{

			final Sholl_Analysis sa = new Sholl_Analysis();
			sa.setDescription("Tracings for " + originalImage.getTitle(), false);
			final Calibration cal = originalImage.getCalibration();
			if (cal != null) {
				final int pX = (int) cal.getRawX(x_start);
				final int pY = (int) cal.getRawY(y_start, originalImage.getHeight());
				final int pZ = (int) (z_start / cal.pixelDepth + cal.zOrigin);
				sa.setCenter(pX, pY, pZ);
				sa.setUnit(cal.getUnit());
			}
			sa.setStepRadius(sphereSeparation);
			sa.setPrimaryBranches(primaryBranches);
			sa.setExportPath(exportDir);
			sa.analyzeProfile(sampled_distances, sampled_counts, !twoDimensional);

		}

		public void addToResultsTable() {
			final ResultsTable rt = Analyzer.getResultsTable();
			if (!Analyzer.resetCounter()) return;
			rt.incrementCounter();
			rt.addValue("Filename", getOriginalFilename());
			rt.addValue("All paths used", String.valueOf(useAllPaths));
			rt.addValue("Paths used", numberOfPathsUsed);
			rt.addValue("Sphere separation", sphereSeparation);
			rt.addValue("Normalization", normalization);
			rt.addValue("Axes", axes);
			rt.addValue("Max inters. radius", getCriticalValue());
			rt.addValue("Max inters.", getDendriteMaximum());
			rt.addValue("Regression coefficient", getShollRegressionCoefficient());
			rt.addValue("Regression gradient", getRegressionGradient());
			rt.addValue("Regression intercept", getRegressionIntercept());
			rt.show("Results");
		}

		boolean twoDimensional;
		ImagePlus originalImage;
		boolean useAllPaths;
		int numberOfPathsUsed;

		public ShollResults(final List<ShollPoint> shollPoints,
			final ImagePlus originalImage, final boolean useAllPaths,
			final int numberOfPathsUsed, final double x_start, final double y_start,
			final double z_start, final String description, final int axes,
			final int normalization, final double sphereSeparation,
			final boolean twoDimensional)
		{
			parametersSuffix = "_" + axesParameters[axes] + "_" +
				normalizationParameters[normalization] + "_" + sphereSeparation;
			this.originalImage = originalImage;
			this.useAllPaths = useAllPaths;
			this.numberOfPathsUsed = numberOfPathsUsed;
			this.x_start = x_start;
			this.y_start = y_start;
			this.z_start = z_start;
			this.description = description;
			this.axes = axes;
			this.normalization = normalization;
			this.sphereSeparation = sphereSeparation;
			this.twoDimensional = twoDimensional;
			Collections.sort(shollPoints);
			n = shollPoints.size();
			squaredRangeStarts = new double[n];
			crossingsPastEach = new int[n];
			int currentCrossings = 0;
			for (int i = 0; i < n; ++i) {
				final ShollPoint p = shollPoints.get(i);
				if (p.nearer) ++currentCrossings;
				else--currentCrossings;
				squaredRangeStarts[i] = p.distanceSquared;
				crossingsPastEach[i] = currentCrossings;
				if (currentCrossings > maxCrossings) {
					maxCrossings = currentCrossings;
					criticalValue = Math.sqrt(p.distanceSquared);
				}
				// System.out.println("Range starting at:
				// "+Math.sqrt(p.distanceSquared)+" has crossings:
				// "+currentCrossings);
			}

			// Retrieve the data points for the sampled profile
			if (sphereSeparation > 0) { // Discontinuous sampling

				n_samples = (int) Math.ceil(Math.sqrt(getMaxDistanceSquared()) /
					sphereSeparation);
				sampled_distances = new double[n_samples];
				sampled_counts = new double[n_samples];
				for (int i = 0; i < n_samples; ++i) {
					final double x = i * sphereSeparation;
					sampled_distances[i] = x;
					sampled_counts[i] = crossingsAtDistanceSquared(x * x);
				}

			}
			else { // Continuous sampling

				// We'll ensure we are not keeping duplicated data points so
				// we'll store unique distances in a temporary LinkedHashSet
				final LinkedHashSet<Double> uniqueDistancesSquared =
					new LinkedHashSet<>();
				for (int i = 0; i < n; ++i)
					uniqueDistancesSquared.add(squaredRangeStarts[i]);
				n_samples = uniqueDistancesSquared.size();
				sampled_distances = new double[n_samples];
				sampled_counts = new double[n_samples];
				final Iterator<Double> it = uniqueDistancesSquared.iterator();
				int idx = 0;
				while (it.hasNext()) {
					final double distanceSquared = it.next();
					sampled_distances[idx] = Math.sqrt(distanceSquared);
					sampled_counts[idx++] = crossingsAtDistanceSquared(distanceSquared);
				}
			}

			// At this point what has been sampled is what is set to be plotted
			// in a non-normalized linear plot
			graphPoints = n_samples;
			x_graph_points = Arrays.copyOf(sampled_distances, n_samples);
			y_graph_points = Arrays.copyOf(sampled_counts, n_samples);

			xAxisLabel = "Distance from (" + SNTUtils.formatDouble(x_start, 3) + ", " + SNTUtils
				.formatDouble(y_start, 3) + ", " + SNTUtils.formatDouble(z_start, 3) + ")";
			yAxisLabel = "N. of Intersections";

			if (normalization == NORMALIZED_FOR_SPHERE_VOLUME) {
				for (int i = 0; i < graphPoints; ++i) {
					final double x = x_graph_points[i];
					final double distanceSquared = x * x;
					if (twoDimensional) y_graph_points[i] /= (Math.PI * distanceSquared);
					else y_graph_points[i] /= ((4.0 * Math.PI * x * distanceSquared) /
						3.0);
				}
				if (twoDimensional) yAxisLabel = "Inters./Area";
				else yAxisLabel = "Inters./Volume";
			}

			final SimpleRegression regression = new SimpleRegression();

			maxY = Double.MIN_VALUE;
			minY = Double.MAX_VALUE;
			for (int i = 0; i < graphPoints; ++i) {
				final double x = x_graph_points[i];
				final double y = y_graph_points[i];
				double x_for_regression = x;
				double y_for_regression = y;
				if (!(Double.isInfinite(y) || Double.isNaN(y))) {
					if (y > maxY) maxY = y;
					if (y < minY) minY = y;
					if (axes == AXES_SEMI_LOG) {
						if (y <= 0) continue;
						y_for_regression = Math.log(y);
					}
					else if (axes == AXES_LOG_LOG) {
						if (x <= 0 || y <= 0) continue;
						x_for_regression = Math.log(x);
						y_for_regression = Math.log(y);
					}
					regression.addData(x_for_regression, y_for_regression);
				}
			}
			regressionGradient = regression.getSlope();
			regressionIntercept = regression.getIntercept();
			// Retrieve r-squared, i.e., the square of the Pearson regression
			// coefficient
			regressionRSquare = regression.getRSquare();

			if (maxY == Double.MIN_VALUE) throw new RuntimeException(
				"[BUG] Somehow there were no valid points found");
		}

		private JFreeChart createGraph() {

			if (graphPoints < 2) return null;
			XYSeriesCollection data = null;
			double minX = Double.MAX_VALUE;
			double maxX = Double.MIN_VALUE;

			final XYSeries series = new XYSeries("Intersections");
			for (int i = 0; i < graphPoints; ++i) {
				final double x = x_graph_points[i];
				final double y = y_graph_points[i];
				if (Double.isInfinite(y) || Double.isNaN(y)) continue;
				if (axes == AXES_SEMI_LOG || axes == AXES_LOG_LOG) {
					if (y <= 0) continue;
				}
				if (axes == AXES_LOG_LOG) {
					if (x <= 0) continue;
				}
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				series.add(x, y);
			}
			data = new XYSeriesCollection(series);

			ValueAxis xAxis = null;
			ValueAxis yAxis = null;
			if (axes == AXES_NORMAL) {
				xAxis = new NumberAxis(xAxisLabel);
				yAxis = new NumberAxis(yAxisLabel);
			}
			else if (axes == AXES_SEMI_LOG) {
				xAxis = new NumberAxis(xAxisLabel);
				yAxis = new LogAxis(yAxisLabel);
			}
			else if (axes == AXES_LOG_LOG) {
				xAxis = new LogAxis(xAxisLabel);
				yAxis = new LogAxis(yAxisLabel);
			}

			try {
				xAxis.setRange(minX, maxX);
				if (axes == AXES_NORMAL) yAxis.setRange(0, maxY);
				else yAxis.setRange(minY, maxY);
			}
			catch (final IllegalArgumentException iae) {
				yAxis.setAutoRange(true);
			}

			XYItemRenderer renderer = null;
			if (sphereSeparation > 0) {
				renderer = new XYLineAndShapeRenderer();
			}
			else {
				final XYBarRenderer barRenderer = new XYBarRenderer();
				barRenderer.setShadowVisible(false);
				// barRenderer.setGradientPaintTransformer(null);
				barRenderer.setDrawBarOutline(false);
				barRenderer.setBarPainter(new StandardXYBarPainter());
				renderer = barRenderer;
			}
			renderer.setSeriesVisibleInLegend(0, false);
			final XYPlot plot = new XYPlot(data, xAxis, yAxis, renderer);

			return new JFreeChart(description, plot);
		}

		public int crossingsAtDistanceSquared(final double distanceSquared) {

			int minIndex = 0;
			int maxIndex = n - 1;

			if (distanceSquared < squaredRangeStarts[minIndex]) return 1;
			else if (distanceSquared > squaredRangeStarts[maxIndex]) return 0;

			while (maxIndex - minIndex > 1) {

				final int midPoint = (maxIndex + minIndex) / 2;

				if (distanceSquared < squaredRangeStarts[midPoint]) maxIndex = midPoint;
				else minIndex = midPoint;
			}
			return crossingsPastEach[minIndex];
		}

		public ImagePlus makeShollCrossingsImagePlus(final ImagePlus original) {
			final int width = original.getWidth();
			final int height = original.getHeight();
			final int depth = original.getNSlices(); // FIXME: Check hyperstack
																								// support
			final Calibration c = original.getCalibration();
			double x_spacing = 1;
			double y_spacing = 1;
			double z_spacing = 1;
			if (c != null) {
				x_spacing = c.pixelWidth;
				y_spacing = c.pixelHeight;
				z_spacing = c.pixelDepth;
			}
			final ImageStack stack = new ImageStack(width, height);
			for (int z = 0; z < depth; ++z) {
				final short[] pixels = new short[width * height];
				for (int y = 0; y < height; ++y) {
					for (int x = 0; x < width; ++x) {
						final double xdiff = x_spacing * x - x_start;
						final double ydiff = y_spacing * y - y_start;
						final double zdiff = z_spacing * z - z_start;
						final double distanceSquared = xdiff * xdiff + ydiff * ydiff +
							zdiff * zdiff;
						pixels[y * width + x] = (short) crossingsAtDistanceSquared(
							distanceSquared);
					}
				}
				final ShortProcessor sp = new ShortProcessor(width, height);
				sp.setPixels(pixels);
				stack.addSlice("", sp);
			}
			final ImagePlus result = new ImagePlus(description, stack);
			result.show();
			final IndexColorModel icm = FindConnectedRegions.backgroundAndSpectrum(
				255);
			stack.setColorModel(icm);
			final ImageProcessor ip = result.getProcessor();
			if (ip != null) {
				ip.setColorModel(icm);
				ip.setMinAndMax(0, maxCrossings);
			}
			result.updateAndDraw();

			if (c != null) result.setCalibration(c);
			return result;
		}

		public String getOriginalFilename() {
			final FileInfo originalFileInfo = originalImage.getOriginalFileInfo();
			if (originalFileInfo.directory == null) return "[unknown]";
			else return new File(originalFileInfo.directory,
				originalFileInfo.fileName).getAbsolutePath();

		}

		public void exportSummaryToCSV(final File outputFile) throws IOException {
			final String[] headers = new String[] { "Filename", "All paths used",
				"Paths used", "Sphere separation", "Normalization", "Axes",
				"Max inters. radius", "Max inters.", "Regression coefficient",
				"Regression gradient", "Regression intercept" };

			final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
					Files.newOutputStream(Paths.get(outputFile.getAbsolutePath())), StandardCharsets.UTF_8));
			final int columns = headers.length;
			for (int c = 0; c < columns; ++c) {
				SNTUtils.csvQuoteAndPrint(pw, headers[c]);
				if (c < (columns - 1)) pw.print(",");
			}
			pw.print("\r\n");
			SNTUtils.csvQuoteAndPrint(pw, getOriginalFilename());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, useAllPaths);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, numberOfPathsUsed);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, sphereSeparation);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, normalizationParameters[normalization]);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, axesParameters[axes]);
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, getCriticalValue());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, getDendriteMaximum());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, getShollRegressionCoefficient());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, getRegressionGradient());
			pw.print(",");
			SNTUtils.csvQuoteAndPrint(pw, getRegressionIntercept());
			pw.print("\r\n");

			pw.close();
		}

		public void exportDetailToCSV(final File outputFile) throws IOException {
			String[] headers;
			headers = new String[] { "Radius", "Inters.", (twoDimensional)
				? "Inters./Area" : "Inters./Volume" };

			final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
					Files.newOutputStream(Paths.get(outputFile.getAbsolutePath())), StandardCharsets.UTF_8));
			final int columns = headers.length;
			for (int c = 0; c < columns; ++c) {
				SNTUtils.csvQuoteAndPrint(pw, headers[c]);
				if (c < (columns - 1)) pw.print(",");
			}
			pw.print("\r\n");

			for (int i = 0; i < n_samples; ++i) {
				double normalizedCrossings = -Double.MIN_VALUE;
				final double x = sampled_distances[i];
				final double y = sampled_counts[i];
				final double distanceSquared = x * x;
				if (twoDimensional) normalizedCrossings = y / (Math.PI *
					distanceSquared);
				else normalizedCrossings = y / ((4.0 * Math.PI * x * distanceSquared) /
					3.0);
				SNTUtils.csvQuoteAndPrint(pw, x);
				pw.print(",");
				SNTUtils.csvQuoteAndPrint(pw, y);
				pw.print(",");
				SNTUtils.csvQuoteAndPrint(pw, normalizedCrossings);
				pw.print("\r\n");
			}
			pw.close();
		}

	}

	@Deprecated
	public static class ShollPoint implements Comparable<ShollPoint> {

		private final boolean nearer;
		private final double distanceSquared;

		@Override
		public int compareTo(final ShollPoint other) {
			return Double.compare(this.distanceSquared, other.distanceSquared);
		}

		ShollPoint(final double distanceSquared, final boolean nearer) {
			this.distanceSquared = distanceSquared;
			this.nearer = nearer;
		}
	}

	public static void addPathPointsToShollList(final Path p,
		final double x_start, final double y_start, final double z_start,
		final List<ShollPoint> shollPointsList)
	{

		for (int i = 0; i < p.size() - 1; ++i) {
			final PointInImage pim1 = p.getNode(i);
			final PointInImage pim2 = p.getNode(i + 1);
			final double xdiff_first = pim1.x - x_start;
			final double ydiff_first = pim1.y - y_start;
			final double zdiff_first = pim1.z - z_start;
			final double xdiff_second = pim2.x - x_start;
			final double ydiff_second = pim2.y - y_start;
			final double zdiff_second = pim2.z - z_start;
			final double distanceSquaredFirst = xdiff_first * xdiff_first +
				ydiff_first * ydiff_first + zdiff_first * zdiff_first;
			final double distanceSquaredSecond = xdiff_second * xdiff_second +
				ydiff_second * ydiff_second + zdiff_second * zdiff_second;
			shollPointsList.add(new ShollPoint(distanceSquaredFirst,
				distanceSquaredFirst < distanceSquaredSecond));
			shollPointsList.add(new ShollPoint(distanceSquaredSecond,
				distanceSquaredFirst >= distanceSquaredSecond));
		}

	}

	private void reloadPaths() {

		// Reset analysis
		numberOfAllPaths = 0;
		numberOfSelectedPaths = 0;
		shollPointsAllPaths.clear();
		shollPointsSelectedPaths.clear();

		// load paths considering only those whose type has been chosen by user
		for (Path p : shollpafm.getPaths()) {
			final boolean selected = p.isSelected();
			if (p.getUseFitted()) {
				p = p.getFitted();
			}
			else if (p.isFittedVersionOfAnotherPath()) continue;

			if (filteredTypes.contains(Path.getSWCtypeName(p.getSWCType(), false))) {
				addPathPointsToShollList(p, x_start, y_start, z_start,
					shollPointsAllPaths);
				++numberOfAllPaths;
				if (selected) {
					addPathPointsToShollList(p, x_start, y_start, z_start,
						shollPointsSelectedPaths);
					++numberOfSelectedPaths;
				}
			}
		}

	}

	private void buildTypeFilteringMenu() {
		swcTypesButton.addActionListener(e -> {
			if (!swcTypesMenu.isVisible()) {
				final Point p = swcTypesButton.getLocationOnScreen();
				swcTypesMenu.setInvoker(swcTypesButton);
				swcTypesMenu.setLocation((int) p.getX(), (int) p.getY() + swcTypesButton
					.getHeight());
				swcTypesMenu.setVisible(true);
			}
			else {
				swcTypesMenu.setVisible(false);
			}
		});
		for (final String swcType : Path.getSWCtypeNames()) {
			final JMenuItem mi = new JCheckBoxMenuItem(swcType, true);
			mi.addActionListener(e -> swcTypesMenu.show(swcTypesButton, 0,
				swcTypesButton.getHeight()));
			mi.addItemListener(e -> {
				if (filteredTypes.contains(mi.getText()) && !mi.isSelected()) {
					filteredTypes.remove(mi.getText());
				}
				else if (!filteredTypes.contains(mi.getText()) && mi.isSelected()) {
					filteredTypes.add(mi.getText());
				}
				reloadPaths();
				updateResults();
			});
			swcTypesMenu.add(mi);
		}

	}

	private class ResultsPanel extends JPanel {

		private static final long serialVersionUID = 1L;
		private final JLabel headingLabel = new JLabel("Results Preview:");
		private final String defaultText = "[Not calculated yet]";
		private final JLabel criticalValuesLabel = new JLabel(defaultText,
			SwingConstants.RIGHT);
		private final JLabel centerLabel = new JLabel(defaultText,
			SwingConstants.RIGHT);
		private final JLabel dendriteMaximumLabel = new JLabel(defaultText,
			SwingConstants.RIGHT);
		private final JLabel shollsRegressionCoefficientLabel = new JLabel(
			defaultText, SwingConstants.RIGHT);
		private final JLabel shollsRegressionInterceptLabel = new JLabel(
			defaultText, SwingConstants.RIGHT);
		private final JLabel shollsRegressionRSquaredLabel = new JLabel(defaultText,
			SwingConstants.RIGHT);

		private ResultsPanel() {
			super();
			setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.anchor = GridBagConstraints.LINE_START;
			c.ipadx = margin;
			c.gridx = 0;
			c.gridy = 0;
			c.gridwidth = 2;
			add(headingLabel, c);
			c.anchor = GridBagConstraints.LINE_END;
			++c.gridy;
			c.gridwidth = 1;
			c.gridx = 0;
			add(new JLabel("Center (X,Y,Z): "), c);
			c.gridx = 1;
			add(centerLabel, c);
			++c.gridy;
			c.gridx = 0;

			add(new JLabel("Max inters. radius: "), c);
			c.gridx = 1;
			add(criticalValuesLabel, c);
			c.gridx = 0;
			++c.gridy;
			add(new JLabel("Max inters.: "), c);
			c.gridx = 1;
			add(dendriteMaximumLabel, c);
			// c.gridx = 0;
			// ++ c.gridy;
			// add(new Label("Schoenen Ramification Index:"),c);
			// c.gridx = 1;
			// add(schoenenRamificationIndexLabel,c);
			c.gridx = 0;
			++c.gridy;
			add(new JLabel("Regression coeff.: "), c);
			c.gridx = 1;
			add(shollsRegressionCoefficientLabel, c);
			c.gridx = 0;
			++c.gridy;
			add(new JLabel("Regression intercept: "), c);
			c.gridx = 1;
			add(shollsRegressionInterceptLabel, c);
			c.gridx = 0;
			++c.gridy;
			add(new JLabel("Regression R2: "), c);
			c.gridx = 1;
			add(shollsRegressionRSquaredLabel, c);
		}

		private void updateFromResults(final ShollResults results) {
			dendriteMaximumLabel.setText("" + results.getDendriteMaximum());
			criticalValuesLabel.setText(SNTUtils.formatDouble(results.getCriticalValue(),
				3));
			centerLabel.setText(SNTUtils.formatDouble(x_start, 1) + ", " + SNTUtils
				.formatDouble(y_start, 1) + ", " + SNTUtils.formatDouble(z_start, 1));
			shollsRegressionCoefficientLabel.setText(SNTUtils.formatDouble(results
				.getShollRegressionCoefficient(), -3));
			shollsRegressionInterceptLabel.setText(SNTUtils.formatDouble(results
				.getRegressionIntercept(), 3));
			shollsRegressionRSquaredLabel.setText(SNTUtils.formatDouble(results
				.getRegressionRSquare(), 3));
		}
	}

	private String getExportPath() {
		if (this.exportPath == null && plugin.getImagePlus() != null) {
			final FileInfo fi = plugin.getImagePlus().getOriginalFileInfo();
			if (fi != null && fi.directory != null) this.exportPath = fi.directory;
		}
		return this.exportPath;
	}

}
