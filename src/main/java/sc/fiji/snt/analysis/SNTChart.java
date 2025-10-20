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

package sc.fiji.snt.analysis;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import net.imglib2.roi.geom.real.Polygon2D;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jfree.chart.*;
import org.jfree.chart.annotations.*;
import org.jfree.chart.axis.*;
import org.jfree.chart.entity.*;
import org.jfree.chart.plot.*;
import org.jfree.chart.plot.flow.FlowPlot;
import org.jfree.chart.renderer.AbstractRenderer;
import org.jfree.chart.renderer.DefaultPolarItemRenderer;
import org.jfree.chart.renderer.LookupPaintScale;
import org.jfree.chart.renderer.category.*;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.title.*;
import org.jfree.chart.ui.*;
import org.jfree.chart.util.ExportUtils;
import org.jfree.data.Range;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.PieDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.jfree.data.xy.XYDataset;
import org.scijava.command.CommandService;
import org.scijava.plot.CategoryChart;
import org.scijava.table.Column;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.ui.swing.viewer.plot.jfreechart.*;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import ij.ImagePlus;
import ij.plugin.ImagesToStack;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.SaveMeasurementsCmd;
import sc.fiji.snt.util.SNTColor;

/**
 * Extension of {@link ChartPanel} modified for scientific publications and
 * convenience methods for plot annotations.
 *
 * @author Tiago Ferreira
 */
public class SNTChart extends ChartPanel {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final long serialVersionUID = 5245298401153759551L;
	private static final Color BACKGROUND_COLOR = Color.WHITE;
	private static final List<SNTChart> openInstances = new ArrayList<>();
	private List<SNTChart> otherCombinedCharts;
	private JFrame frame;
	private String title;
    private boolean equalizedXY;

	static double scalingFactor = 1;

    public SNTChart(final String title, final JFreeChart chart) {
		this(title, chart, new Dimension((int)(400 * scalingFactor), (int)(400 * scalingFactor)));
	}

	public SNTChart(final String title, final org.scijava.plot.XYPlot xyplot) {
		this(title, new XYPlotConverter().convert(xyplot, JFreeChart.class));
	}

	public SNTChart(final String title, final CategoryChart categoryChart) {
		this(title, new CategoryChartConverter().convert(categoryChart, JFreeChart.class));
	}

	protected SNTChart(final String title, final JFreeChart chart, final Dimension preferredSize) {
		super(chart);
		setTitle(title);
		if (chart != null) {
			chart.setBackgroundPaint(BACKGROUND_COLOR);
			chart.setAntiAlias(true);
			chart.setTextAntiAlias(true);
			if (chart.getLegend() != null) {
				chart.getLegend().setBackgroundPaint(chart.getBackgroundPaint());
			}
			setFontSize(GuiUtils.uiFontSize());
		}
		// Tweak: Ensure chart is always drawn and not scaled to avoid rendering artifacts
		setMinimumDrawWidth(0);
		setMaximumDrawWidth(Integer.MAX_VALUE);
		setMinimumDrawHeight(0);
		setMaximumDrawHeight(Integer.MAX_VALUE);
		setBackground(BACKGROUND_COLOR); // provided contrast to otherwise transparent background
		if (chart != null) {
			customizePopupMenu();
			setPreferredSize(preferredSize);
		}
		try {
			setDefaultDirectoryForSaveAs(SNTPrefs.lastknownDir());
		} catch (final Exception ignored) {
			// Workaround reports of System.getProperty("user.home") not being a valid directory
			// (presumably due to modified PATH variables [reported from S Windows 10/11])
			SNTUtils.log("SNTChart: Could not set default directory: " + SNTPrefs.lastknownDir());
		}
		addChartMouseListener(new ChartListener());
	}

	public JFrame getFrame() {
		if (frame == null) {
			GuiUtils.setLookAndFeel();
			frame = new JFrame(getTitle());
			frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			frame.setLocationByPlatform(true);
			if (isCombined()) {
				SwingUtilities.invokeLater(() -> otherCombinedCharts.forEach(chart -> {
                    if (chart.frame != null)
                        chart.frame.setVisible(false);
                }));
			}
			frame.setContentPane(this);
			frame.setBackground(SNTChart.BACKGROUND_COLOR); // provided contrast to otherwise transparent background
			frame.setMinimumSize(new Dimension(500,500));
			frame.pack();
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowOpened(final WindowEvent e) {
					openInstances.add(SNTChart.this);
				}

				@Override
				public void windowClosing(final WindowEvent e) {
					if (isCombined()) otherCombinedCharts.forEach(SNTChart::dispose);
					dispose();
				}
			});
		}
		return frame;
	}

	private XYPlot getXYPlot() {
		return getChart().getXYPlot();
	}

	private CategoryPlot getCategoryPlot() {
		return getChart().getCategoryPlot();
	}

	/**
	 * Annotates the specified X-value (XY plots and histograms).
	 *
	 * @param xValue the X value to be annotated.
	 * @param label the annotation label
	 */
	public void annotateXline(final double xValue, final String label) {
		annotateXline(xValue, label, null);
	}

	/**
	 * Annotates the specified X-value (XY plots and histograms).
	 *
	 * @param xValue the X value to be annotated.
	 * @param label the annotation label
	 * @param color the font color
	 */
	public void annotateXline(final double xValue, final String label, final String color) {
		final Marker marker = new ValueMarker(xValue);
		final Color c = getColorFromString(color);
		marker.setPaint(c);
		marker.setLabelBackgroundColor(new Color(255,255,255,0));
		if (label != null && !label.isEmpty()) {
			marker.setLabelPaint(c);
			marker.setLabel(label);
			marker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
			marker.setLabelTextAnchor(TextAnchor.TOP_RIGHT);
			marker.setLabelFont(getXYPlot().getDomainAxis().getTickLabelFont());
		}
		getXYPlot().addDomainMarker(marker);
	}

	/**
	 * Annotates the specified Y-value (XY plots and histograms).
	 *
	 * @param yValue the Y value to be annotated.
	 * @param label the annotation label
	 */
	public void annotateYline(final double yValue, final String label) {
		annotateYline(yValue, label, null);
	}

	/**
	 * Annotates the specified Y-value (XY plots and histograms).
	 *
	 * @param yValue the Y value to be annotated.
	 * @param label the annotation label
	 * @param color the font color
	 */
	public void annotateYline(final double yValue, final String label, final String color) {
		final Color c = getColorFromString(color);
		final Marker marker = new ValueMarker(yValue);
		marker.setPaint(c);
		marker.setLabelBackgroundColor(new Color(255,255,255,0));
		if (label != null && !label.isEmpty()) {
			marker.setLabelPaint(c);
			marker.setLabel(label);
			marker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
			marker.setLabelTextAnchor(TextAnchor.BOTTOM_RIGHT);
			marker.setLabelFont(getXYPlot().getRangeAxis().getTickLabelFont());
		}
		getXYPlot().addRangeMarker(marker);
	}

	/**
	 * Sets the visibility of chart axes.
	 * <p>
	 * Controls whether the domain (X) and range (Y) axes are visible in the chart.
	 * This affects both XY plots and category plots.
	 * </p>
	 *
	 * @param visible true to show axes, false to hide them
	 */
	public void setAxesVisible(final boolean visible) {
		if (getChart().getPlot() instanceof XYPlot plot) {
			plot.getDomainAxis().setVisible(visible);
			plot.getRangeAxis().setVisible(visible);
		} else if (getChart().getPlot() instanceof CategoryPlot plot) {
			plot.getDomainAxis().setVisible(visible);
			plot.getRangeAxis().setVisible(visible);
		}
	}

	public boolean isLegendVisible() {
		try {
			return getChart().getLegend().isVisible();
		} catch (NullPointerException ignored) {
			return false;
		}
	}

	/**
	 * Checks if the plot outline is visible.
	 * <p>
	 * Returns true if the plot's border outline is currently visible.
	 * </p>
	 *
	 * @return true if outline is visible, false otherwise
	 */
	public boolean isOutlineVisible() {
		return getChart().getPlot().isOutlineVisible();
	}

	public boolean isGridlinesVisible() {
		if (getChart().getPlot() instanceof XYPlot plot) {
			return plot.isDomainGridlinesVisible() || plot.isRangeGridlinesVisible();
		} else if (getChart().getPlot() instanceof CategoryPlot plot) {
			return plot.isDomainGridlinesVisible() || plot.isRangeGridlinesVisible();
		} else if (getChart().getPlot() instanceof PolarPlot plot) {
            return plot.isRadiusGridlinesVisible();
		}
		return false;
	}

	public void setGridlinesVisible(final boolean visible) {
		if (getChart().getPlot() instanceof CombinedRangeXYPlot) {
			final List<XYPlot> plots = ((CombinedRangeXYPlot)(getChart().getXYPlot())).getSubplots();
			for (final XYPlot plot : plots) {
				// CombinedRangeXYPlot do not have domain axis!?
				plot.setRangeGridlinesVisible(visible);
				plot.setRangeMinorGridlinesVisible(visible);
			}
		} else if (getChart().getPlot() instanceof XYPlot plot) {
			if (plot.getDomainGridlinePaint()==BACKGROUND_COLOR) plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
			if (plot.getRangeGridlinePaint()==BACKGROUND_COLOR) plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
			plot.setDomainGridlinesVisible(visible);
			//plot.setDomainMinorGridlinesVisible(visible);
			plot.setRangeGridlinesVisible(visible);
			//plot.setRangeMinorGridlinesVisible(visible);
		} else if (getChart().getPlot() instanceof CategoryPlot plot) {
			if (plot.getDomainGridlinePaint()==plot.getBackgroundPaint())
				plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
			plot.setDomainGridlinesVisible(visible);
			plot.setRangeGridlinesVisible(visible);
			//plot.setRangeMinorGridlinesVisible(visible);
		} else if (getChart().getPlot() instanceof PolarPlot plot) {
            plot.setRadiusGridlinesVisible(visible);
			plot.setRadiusMinorGridlinesVisible(visible);
			plot.setAngleGridlinesVisible(visible);
		}
	}

	/**
	 * Sets the visibility of the plot outline.
	 * <p>
	 * Controls whether the border outline around the plot area is visible.
	 * </p>
	 *
	 * @param visible true to show outline, false to hide it
	 */
	public void setOutlineVisible(final boolean visible) {
		getChart().getPlot().setOutlineVisible(visible);
	}

    /**
     * Sets whether the axes of the underlying XY plot should be equalized (same scale).
     * <p>
     * When enabled, both X and Y axes will use the same scale to maintain
     * equal aspect ratio. When disabled, each axis maximizes its range.
     * </p>
     *
     * @param equalize true to equalize axes, false otherwise
     * @throws IllegalArgumentException if the underlying plot is not a XY plot
     */
    public void setEqualizeAxes(final boolean equalize) {
        if (!(getChart().getPlot() instanceof XYPlot)) {
            throw new IllegalArgumentException("Equalized axes apply only to XY plots");
        }
        this.equalizedXY = equalize;
        if (!equalize) {
            getXYPlot().getDomainAxis().setAutoRange(true);
            getXYPlot().getRangeAxis().setAutoRange(true);
            return;
        }
        final ValueAxis xAxis = getXYPlot().getDomainAxis();
        final ValueAxis yAxis = getXYPlot().getRangeAxis();
        if (xAxis.getRange().getLength() < yAxis.getRange().getLength()) {
            double idealXRange = yAxis.getRange().getLength();
            double currXRange = xAxis.getRange().getLength();
            double xDelta = (idealXRange - currXRange) / 2;
            double xLower = xAxis.getLowerBound();
            double xUpper = xAxis.getUpperBound();
            xAxis.setRange(new Range(xLower - xDelta, xUpper + xDelta), true, true);
        } else if (xAxis.getRange().getLength() > yAxis.getRange().getLength()) {
            double idealYRange = xAxis.getRange().getLength();
            double currYRange = yAxis.getRange().getLength();
            double yDelta = (idealYRange - currYRange) / 2;
            double yLower = yAxis.getLowerBound();
            double yUpper = yAxis.getUpperBound();
            yAxis.setRange(new Range(yLower - yDelta, yUpper + yDelta), true, true);
        }
    }

	/**
	 * Checks if the axes of the underlying XY plot are equalized.
	 * <p>
	 * Returns true if both X and Y axes use the same scale.
	 * </p>
	 *
	 * @return true if axes are equalized, false otherwise
	 */
	public boolean isEqualized() {
        return (getChart().getPlot() instanceof XYPlot) && equalizedXY;
	}

	public void setLegendVisible(final boolean visible) {
		if (getChart().getLegend() != null)
			getChart().getLegend().setVisible(visible);
	}

	/**
	 * Annotates the specified category (Category plots only)
	 *
	 * @param category the category to be annotated. Ignored if it does not exist in
	 *                 category axis.
	 * @param label    the annotation label
	 */
	public void annotateCategory(final String category, final String label) {
		annotateCategory(category, label, "blue");
	}

	/**
	 * Annotates the specified category (Category plots only).
	 *
	 * @param category the category to be annotated. Ignored if it does not exist in
	 *                 category axis.
	 * @param label    the annotation label
	 * @param color    the annotation color
	 */
	public void annotateCategory(final String category, final String label, final String color) {
		final CategoryPlot catPlot = getCategoryPlot();
		final Color c = getColorFromString(color);
		final CategoryMarker marker = new CategoryMarker(category, c, new BasicStroke(1.0f,
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1.0f, new float[] { 6.0f, 6.0f }, 0.0f));
		marker.setDrawAsLine(true);
		catPlot.addDomainMarker(marker, Layer.BACKGROUND);
		if (catPlot.getCategories().contains(category) && (label != null && !label.isEmpty())) {
			final Range range = catPlot.getRangeAxis().getRange();
			final double labelYloc = range.getUpperBound() * 0.50 + range.getLowerBound();
			final CategoryTextAnnotation annot = new CategoryTextAnnotation(label, category, labelYloc);
			annot.setPaint(c);
			annot.setFont(catPlot.getRangeAxis().getTickLabelFont());
			annot.setCategoryAnchor(CategoryAnchor.END);
			annot.setTextAnchor(TextAnchor.BOTTOM_CENTER);
			catPlot.addAnnotation(annot);
		}
	}

	/**
	 * (Re)colors existing dataset series
	 *
	 * @param colors The series colors
	 */
	public void setColors(final String... colors) {
		final Plot plot = getChart().getPlot();
		if (plot instanceof CategoryPlot) {
			final CategoryItemRenderer renderer = ((CategoryPlot) plot).getRenderer();
			final int nSeries = ((CategoryPlot) plot).getDataset().getRowCount();
			setDatasetColors(renderer, nSeries, getColors(nSeries, colors));
		} else if (plot instanceof XYPlot) {
			final XYItemRenderer renderer = ((XYPlot) plot).getRenderer();
			final int nSeries = ((XYPlot) plot).getDataset().getSeriesCount();
			setDatasetColors(renderer, nSeries, getColors(nSeries, colors));
		}
	}

	/**
	 * (Re)colors existing dataset series
	 *
	 * @param colorTable The colorTable used to recolor series
	 */
	public void setColors(final ColorTable colorTable) {
		final Plot plot = getChart().getPlot();
		if (plot instanceof CategoryPlot) {
			final CategoryItemRenderer renderer = ((CategoryPlot) plot).getRenderer();
			final int nSeries = ((CategoryPlot) plot).getDataset().getRowCount();
			setDatasetColors(renderer, nSeries, getColors(nSeries, colorTable));
		} else if (plot instanceof XYPlot) {
			final XYItemRenderer renderer = ((XYPlot) plot).getRenderer();
			final int nSeries = ((XYPlot) plot).getDataset().getSeriesCount();
			setDatasetColors(renderer, nSeries, getColors(nSeries, colorTable));
		}
	}

	/**
	 * Changes the line width of all series in a XY plot
	 *
	 * @param width the stroke width
	 * @throws IllegalArgumentException if this SNChart is not an XYPlot
	 */
	public void setLineWidth(final int width) {
		final Plot plot = getChart().getPlot();
		if (! (plot instanceof XYPlot)) {
			throw new IllegalArgumentException("Only XYPlots support line width changes");
		}
		final XYItemRenderer renderer = ((XYPlot) plot).getRenderer();
		final int nSeries = ((XYPlot) plot).getDataset().getSeriesCount();
		for (int series = 0; series < nSeries; series++) {
			renderer.setSeriesStroke(series, new BasicStroke(width), series==nSeries-1);
		}

	}

	public void setChartTitle(final String title) {
		try {
			super.getChart().setTitle(title);
		} catch (final NullPointerException ignored) {
			//ignored
		}
	}

	/**
	 * Replaces the current chart with the specified instance
	 *
	 * @param other the instance replacing current contents
	 */
	public void replace(final SNTChart other) {
		setChart(other.getChart());
	}

	private void setDatasetColors(CategoryItemRenderer renderer, int nSeries, Color[] colors) {
		for (int series = 0; series < nSeries; series++) {
			renderer.setSeriesPaint(series, colors[series]);
			renderer.setSeriesOutlinePaint(series, colors[series]);
			renderer.setSeriesItemLabelPaint(series, colors[series]);
		}
	}

	private void setDatasetColors(XYItemRenderer renderer, int nSeries, Color[] colors) {
		for (int series = 0; series < nSeries; series++) {
			renderer.setSeriesPaint(series, colors[series]);
			renderer.setSeriesOutlinePaint(series, colors[series]);
			renderer.setSeriesItemLabelPaint(series, colors[series]);
		}
	}

	private Color[] getColors(final int n, final String... colors) {
		final Color[] baseColors = new Color[colors.length];
		for (int i = 0; i < colors.length; i++) {
			final ColorRGB crgb = Colors.getColor(colors[i]);
			baseColors[i] = new Color(crgb.getRed(), crgb.getGreen(), crgb.getBlue());
		}
		if (n < baseColors.length) {
			return Arrays.copyOfRange(baseColors, 0, n);
		}
		final Color[] paddedColors = Arrays.copyOf(baseColors, n);
		for (int last = baseColors.length; last != 0 && last < n; last <<= 1) {
			System.arraycopy(paddedColors, 0, paddedColors, last, Math.min(last << 1, n) - last);
		}
		return paddedColors;
	}

	private Color[] getColors(final int n, final ColorTable colortable) {
		final Color[] colors = new Color[n];
		for (int i = 0; i < n; i++) {
			final int idx = Math.round((float) ((colortable.getLength() - 1) * i) / n);
			colors[i] = new Color(colortable.get(ColorTable.RED, idx), colortable.get(ColorTable.GREEN, idx),
					colortable.get(ColorTable.BLUE, idx));
		}
		return colors;
	}

	/**
	 * Sets the font size to all components of this chart.
	 *
	 * @param size  the new font size
	 */
	public void setFontSize(final float size) {
		setFontSize(size, "axis");
		setFontSize(size, "labels");
		setFontSize(size, "legend");
		getChart().getPlot()
				.setNoDataMessageFont(getChart().getPlot().getNoDataMessageFont().deriveFont(size));
	}

	/**
	 * Sets the font size for this chart.
	 *
	 * @param size  the new font size
	 * @param scope which components should be modified. Either "axes", "legends",
	 *              or "labels" (singular/plural allowed)
	 */
	public void setFontSize(final float size, final String scope) {
		switch(scope.toLowerCase()) {
		case "axis":
		case "axes":
		case "ticks":
			if (getChart().getPlot() instanceof XYPlot) {
				if (getXYPlot().getDomainAxis() != null) {
					final Font font = getXYPlot().getDomainAxis().getTickLabelFont().deriveFont(size);
					getXYPlot().getDomainAxis().setTickLabelFont(font);
					getXYPlot().getDomainAxis().setLabelFont(font);
				}
				if (getXYPlot().getRangeAxis() != null) {
					final Font font = getXYPlot().getRangeAxis().getTickLabelFont().deriveFont(size);
					getXYPlot().getRangeAxis().setTickLabelFont(font);
					getXYPlot().getRangeAxis().setLabelFont(font);
				}
			}
			else if (getChart().getPlot() instanceof CategoryPlot) {
				Font font = getCategoryPlot().getDomainAxis().getTickLabelFont().deriveFont(size);
				getCategoryPlot().getDomainAxis().setTickLabelFont(font);
				font = getCategoryPlot().getRangeAxis().getTickLabelFont().deriveFont(size);
				getCategoryPlot().getRangeAxis().setTickLabelFont(font);
				getCategoryPlot().getDomainAxis().setLabelFont(font);
				getCategoryPlot().getRangeAxis().setLabelFont(font);
			}
			else if (getChart().getPlot() instanceof PolarPlot plot) {
                for (int i = 0; i < plot.getAxisCount(); i++) {
					final Font font = plot.getAxis(i).getTickLabelFont().deriveFont(size);
					plot.getAxis(i).setTickLabelFont(font);
				}
				plot.setAngleLabelFont(plot.getAngleLabelFont().deriveFont(size));
			}
			break;
		case "legend":
		case "legends":
		case "subtitle":
		case "subtitles":
			final LegendTitle legend = getChart().getLegend();
			if (legend != null)
				legend.setItemFont(legend.getItemFont().deriveFont(size));
			for (int i = 0; i < getChart().getSubtitleCount(); i++) {
				final Title title = getChart().getSubtitle(i);
				if (title instanceof PaintScaleLegend) {
					final PaintScaleLegend lt = (PaintScaleLegend) title;
					lt.getAxis().setLabelFont(lt.getAxis().getLabelFont().deriveFont(size));
					lt.getAxis().setTickLabelFont(lt.getAxis().getTickLabelFont().deriveFont(size));
				}
				else if (title instanceof TextTitle tt) {
                    tt.setFont(tt.getFont().deriveFont(size));
				} else if (title instanceof LegendTitle lt) {
                    lt.setItemFont(lt.getItemFont().deriveFont(size));
				}
			}
			break;
		default: // labels  annotations
			if (getChart().getPlot() instanceof XYPlot) {
				final List<?> annotations = getXYPlot().getAnnotations();
				if (annotations != null) {
					for (int i = 0; i < getXYPlot().getAnnotations().size(); i++) {
						final XYAnnotation annotation = getXYPlot().getAnnotations().get(i);
						if (annotation instanceof XYTextAnnotation) {
							((XYTextAnnotation) annotation)
									.setFont(((XYTextAnnotation) annotation).getFont().deriveFont(size));
						}
					}
				}
				adjustMarkersFont(getXYPlot().getDomainMarkers(Layer.FOREGROUND), size);
				adjustMarkersFont(getXYPlot().getDomainMarkers(Layer.BACKGROUND), size);
				adjustMarkersFont(getXYPlot().getRangeMarkers(Layer.FOREGROUND), size);
				adjustMarkersFont(getXYPlot().getRangeMarkers(Layer.BACKGROUND), size);
			}
			else if (getChart().getPlot() instanceof CategoryPlot) {
				final List<?> annotations = getCategoryPlot().getAnnotations();
				if (annotations != null) {
                    for (Object o : annotations) {
                        final CategoryAnnotation annotation = (CategoryAnnotation) o;
                        if (annotation instanceof TextAnnotation) {
                            ((TextAnnotation) annotation)
                                    .setFont(((TextAnnotation) annotation).getFont().deriveFont(size));
                        }
                    }
				}
				adjustMarkersFont(getCategoryPlot().getDomainMarkers(Layer.FOREGROUND), size);
				adjustMarkersFont(getCategoryPlot().getDomainMarkers(Layer.BACKGROUND), size);
				adjustMarkersFont(getCategoryPlot().getRangeMarkers(Layer.FOREGROUND), size);
				adjustMarkersFont(getCategoryPlot().getRangeMarkers(Layer.BACKGROUND), size);
			}
			else if (isFlowPlot()) {
				final FlowPlot plot = (FlowPlot)(getChart().getPlot());
				plot.setDefaultNodeLabelFont(plot.getDefaultNodeLabelFont().deriveFont(Font.PLAIN, size));
			}
			else if (getChart().getPlot() instanceof RingPlot ringPlot) {
				ringPlot.setLabelFont(ringPlot.getLabelFont().deriveFont(size));
			}
			break;
		}
	}

	private int getFontSize(final String scope) {
		switch(scope.toLowerCase()) {
		case "axis":
		case "axes":
		case "ticks":
			if (getChart().getPlot() instanceof XYPlot)
				return getXYPlot().getDomainAxis().getTickLabelFont().getSize();
			else if (getChart().getPlot() instanceof CategoryPlot)
				return getCategoryPlot().getDomainAxis().getTickLabelFont().getSize();
			else if (getChart().getPlot() instanceof PolarPlot)
				return ((PolarPlot)getChart().getPlot()).getAxis().getTickLabelFont().getSize();
			break;
		case "legend":
		case "legends":
		case "subtitle":
		case "subtitles":
			final LegendTitle legend = getChart().getLegend();
			if (legend != null)
				return legend.getItemFont().getSize();
			for (int i = 0; i < getChart().getSubtitleCount(); i++) {
				final Title title = getChart().getSubtitle(i);
				if (title instanceof TextTitle) {
					return ((TextTitle) title).getFont().getSize();
				} else if (title instanceof LegendTitle) {
					return ((LegendTitle) title).getItemFont().getSize();
				}
			}
			break;
		default: // labels  annotations
			if (getChart().getPlot() instanceof XYPlot) {
				return getXYPlot().getDomainAxis().getLabelFont().getSize();
			}
			else if (getChart().getPlot() instanceof CategoryPlot) {
				return getCategoryPlot().getDomainAxis().getLabelFont().getSize();
			}
		}
		return getFont().getSize();
	}

	/**
	 * Gets an ImagePlus representation of the chart.
	 * <p>
	 * Creates an ImagePlus object containing a rasterized version of the chart
	 * at default scaling (1.0x).
	 * </p>
	 *
	 * @return an ImagePlus containing the chart image
	 */
	public ImagePlus getImage() {
		return getImages(1f).getFirst();
	}

	public List<ImagePlus> getImages(final float scalingFactor) {
		final List<ImagePlus> imps = new ArrayList<>();
		if (isCombined()) {
			int counter = 1;
			for (final Component component : getFrame().getContentPane().getComponents()) {
				if (component instanceof ChartPanel) {
					final ImagePlus imp = getImagePlus((SNTChart) component, scalingFactor);
					if ("SNTChart".equals(imp.getTitle()))
						imp.setTitle("Sub-chart " + counter++);
					imps.add(imp);
				}
			}
		} else {
			final ImagePlus imp = getImagePlus(this, scalingFactor);
			if ("SNTChart".equals(imp.getTitle()))
				imp.setTitle(getTitle());
			imps.add(imp);
		}
		return imps;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getTitle() {
		return title;
	}

	private static ImagePlus getImagePlus(final SNTChart cp, final float scalingFactor) {
		final ImagePlus imp = ij.IJ.createImage(
				(cp.getChart().getTitle() == null) ? "SNTChart" : cp.getChart().getTitle().getText(), "RGB",
				(int) scalingFactor * cp.getValidWidth(false), (int) scalingFactor * cp.getValidHeight(false), 1);
		final java.awt.image.BufferedImage image = imp.getBufferedImage();
		cp.getChart().draw(image.createGraphics(),
				new java.awt.geom.Rectangle2D.Float(0, 0, imp.getWidth(), imp.getHeight()));
		imp.setImage(image);
		return imp;
	}

    /**
     * Saves this chart.
     * @param file the output file (null not permitted). The extension of its filename (".svg", ".png", ".pdf"),
     *             determines the file format
     * @return true if file was successfully saved, false otherwise
     */
    public boolean save(final File file) {
        try {
            final String name = file.getName().toLowerCase();
            if (name.endsWith("svg")) {
                saveAsSVG(file, 1);
            } else if (name.endsWith("pdf")) {
                saveAsPDF(file, 1);
            } else {
                saveAsPNG(file, 1);
            }
            return true;
        } catch (final IOException ignored) {
            return false;
        }
    }

    /**
     * Saves this chart.
     * @param filePath the path of the output file (null not permitted). Its filename extension (".svg", ".png", ".pdf"),
     *             determines the file format.
     * @return true if file was successfully saved, false otherwise
     */
    public boolean save(final String filePath, final double scalingFactor) {
        try {
            final String name = filePath.toLowerCase();
            if (name.endsWith("svg")) {
                saveAsSVG(filePath, scalingFactor);
            } else if (name.endsWith("pdf")) {
                saveAsPDF(filePath, scalingFactor);
            } else {
                saveAsPNG(filePath, scalingFactor);
            }
            return true;
        } catch (final IOException ignored) {
            return false;
        }
    }

    public void saveAsPNG(final File file) throws IOException {
        saveAsPNG(file, 1);
    }

    public void saveAsPNG(final File file, final double scalingFactor) throws IOException {
        if (isCombined()) {
            for (int i = 0; i < otherCombinedCharts.size(); i++) {
                final SNTChart chart = otherCombinedCharts.get(i);
                final File candidateFile = new File(file.getParentFile(), SNTUtils.stripExtension(file.getName()) + "-" + (i + 1) + ".png");
                final File outputFile = SNTUtils.getUniquelySuffixedFile(candidateFile, ".png");
                ChartUtils.saveChartAsPNG(outputFile, chart.getChart(),
                        (int) (chart.getValidWidth(false) * scalingFactor),
                        (int) (chart.getValidHeight(false) * scalingFactor));
            }
        } else {
            final File f = (file.isDirectory()) ? new File(file, getTitle() + ".png") : file;
            final File outputFile = SNTUtils.getUniquelySuffixedFile(f, ".png");
            ChartUtils.saveChartAsPNG(outputFile, getChart(),
                    (int) (getValidWidth(false) * scalingFactor),
                    (int) (getValidHeight(false) * scalingFactor));

        }
    }

    public void saveAsPNG(final String filePath) throws IOException {
        saveAsPNG(filePath, 1);
    }

    public void saveAsPNG(final String filePath, final double scalingFactor) throws IOException {
        final File f = new File((filePath.toLowerCase().endsWith(".png")) ? filePath : filePath + ".png");
        if(!f.getParentFile().exists()) f.getParentFile().mkdirs();
        saveAsPNG(f, scalingFactor);
    }

    public void saveAsPDF(final String filePath) throws IOException {
        saveAsPDF(filePath, 1);
    }

    public void saveAsPDF(final String filePath, final double scalingFactor) throws IOException {
        final File f = new File((filePath.toLowerCase().endsWith(".pdf")) ? filePath : filePath + ".pdf");
        if(!f.getParentFile().exists()) f.getParentFile().mkdirs();
        saveAsPDF(f, scalingFactor);
    }

    public void saveAsPDF(final File file, final double scalingFactor) throws IOException {
        if (isCombined()) {
            for (int i = 0; i < otherCombinedCharts.size(); i++) {
                final SNTChart chart = otherCombinedCharts.get(i);
                final File candidateFile = new File(file.getParentFile(), SNTUtils.stripExtension(file.getName()) + "-" + (i + 1) + ".pdf");
                final File outputFile = SNTUtils.getUniquelySuffixedFile(candidateFile, ".pdf");
                ExportUtils.writeAsPDF(chart.getChart(),
                        (int) (chart.getValidWidth(false) * scalingFactor),
                        (int) (chart.getValidHeight(false) * scalingFactor), outputFile);
            }
        } else {
            final File f = (file.isDirectory()) ? new File(file, getTitle() + ".pdf") : file;
            final File outputFile = SNTUtils.getUniquelySuffixedFile(f, ".pdf");
            ExportUtils.writeAsPDF(getChart(),
                    (int) (getValidWidth(false) * scalingFactor),
                    (int) (getValidHeight(false) * scalingFactor), outputFile);
        }
    }

    public void saveAsSVG(final String filePath) throws IOException {
        saveAsSVG(filePath, 1);
    }

    public void saveAsSVG(final String filePath, final double scalingFactor) throws IOException {
        final File f = new File((filePath.toLowerCase().endsWith(".svg")) ? filePath : filePath + ".svg");
        if(!f.getParentFile().exists()) f.getParentFile().mkdirs();
        saveAsSVG(f, scalingFactor);
    }

    public void saveAsSVG(final File file, final double scalingFactor) throws IOException {
        if (isCombined()) {
            for (int i = 0; i < otherCombinedCharts.size(); i++) {
                final SNTChart chart = otherCombinedCharts.get(i);
                final File candidateFile = new File(file.getParentFile(), SNTUtils.stripExtension(file.getName()) + "-" + (i + 1) + ".svg");
                final File outputFile = SNTUtils.getUniquelySuffixedFile(candidateFile, ".svg");
                ExportUtils.writeAsSVG(chart.getChart(),
                        (int) (chart.getValidWidth(false) * scalingFactor),
                        (int) (chart.getValidHeight(false) * scalingFactor), outputFile);
            }
        } else {
            final File f = (file.isDirectory()) ? new File(file, getTitle() + ".svg") : file;
            final File outputFile = SNTUtils.getUniquelySuffixedFile(f, ".svg");
            ExportUtils.writeAsPDF(getChart(),
                    (int) (getValidWidth(false) * scalingFactor),
                    (int) (getValidHeight(false) * scalingFactor), outputFile);
        }
    }

	private void adjustMarkersFont(final Collection<?> markers, final float size) {
		if (markers != null) {
			markers.forEach(marker -> ((Marker) marker).setLabelFont(((Marker) marker).getLabelFont().deriveFont(size)));
		}
	}

	private void replaceBackground(final Color oldColor, final Color newColor) {
		if (this.getBackground() == oldColor)
			this.setBackground(newColor);
		if (getBackground() == oldColor)
			setBackground(newColor);
		if (getChart().getBackgroundPaint() == oldColor)
			getChart().setBackgroundPaint(newColor);
		final LegendTitle legend = getChart().getLegend();
		if (legend != null && legend.getBackgroundPaint() == oldColor) {
			legend.setBackgroundPaint(newColor);
		}
		for (int i = 0; i < getChart().getSubtitleCount(); i++) {
			final Title title = getChart().getSubtitle(i);
			if (title instanceof TextTitle tt) {
                if (tt.getBackgroundPaint() == oldColor)
					tt.setBackgroundPaint(newColor);
			} else if (title instanceof LegendTitle lt) {
                if (lt.getBackgroundPaint() == oldColor)
					lt.setBackgroundPaint(newColor);
			}
		}
		if (getChart().getPlot() instanceof CombinedRangeXYPlot) {
			final List<XYPlot> plots = ((CombinedRangeXYPlot)(getChart().getXYPlot())).getSubplots();
			for (final XYPlot plot : plots) {
				if (plot.getBackgroundPaint() == oldColor)
					plot.setBackgroundPaint(newColor);
			}
		} else if (getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChart().getPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
		} else if (getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = (getChart().getCategoryPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
		} else if (getChart().getPlot() instanceof PolarPlot) {
			final PolarPlot plot = (PolarPlot)(getChart().getPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
		} else if (isFlowPlot()) {
			final FlowPlot plot = (FlowPlot)(getChart().getPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
		}
	}

	private void replaceForegroundColor(final Color oldColor, final Color newColor) {
		if (this.getForeground() == oldColor)
			this.setForeground(newColor);
		if (getForeground() == oldColor)
			setForeground(newColor);
		if (getChart().getBorderPaint() == oldColor)
			getChart().setBorderPaint(newColor);
		if (getChart().getTitle() != null)
			getChart().getTitle().setPaint(newColor);
		final LegendTitle legend = getChart().getLegend();
		if (legend != null && legend.getItemPaint() == oldColor) {
			legend.setItemPaint(newColor);
		}
		for (int i = 0; i < getChart().getSubtitleCount(); i++) {
			final Title title = getChart().getSubtitle(i);
			if (title instanceof TextTitle) {
				((TextTitle) title).setPaint(newColor);
			} else if (title instanceof LegendTitle) {
				((LegendTitle) title).setItemPaint(newColor);
			} else if (title instanceof PaintScaleLegend) {
				((PaintScaleLegend) title).setStripOutlinePaint(newColor);
				((PaintScaleLegend) title).getAxis().setAxisLinePaint(newColor);
				((PaintScaleLegend) title).getAxis().setLabelPaint(newColor);
				((PaintScaleLegend) title).getAxis().setTickMarkPaint(newColor);
				((PaintScaleLegend) title).getAxis().setTickLabelPaint(newColor);
			}
		}
		if (getChart().getPlot() instanceof CombinedRangeXYPlot comb) {
			comb.getSubplots().forEach( plot -> replaceForegroundColorOfXYPlot(plot, oldColor, newColor));
		} else if (getChart().getPlot() instanceof XYPlot xyPlot) {
			replaceForegroundColorOfXYPlot(xyPlot, oldColor, newColor);
		} else if (getChart().getPlot() instanceof CategoryPlot catPlot) {
			for (int i = 0; i < catPlot.getDomainAxisCount() ; i++)
				setForegroundColor(catPlot.getDomainAxis(i), newColor);
			for (int i = 0; i < catPlot.getRangeAxisCount() ; i++)
				setForegroundColor(catPlot.getRangeAxis(i), newColor);
			for (int i = 0; i < catPlot.getRendererCount(); i++) {
				replaceForegroundColor(catPlot.getRenderer(i), oldColor, newColor);
				replaceSeriesColor(catPlot.getRenderer(i), oldColor, newColor);
			}
		} else if (getChart().getPlot() instanceof PolarPlot polarPlot) {
			for (int i = 0; i < polarPlot.getAxisCount(); i++)
				setForegroundColor(polarPlot.getAxis(i), newColor);
			polarPlot.setAngleGridlinePaint(newColor);
			polarPlot.setAngleLabelPaint(newColor);
			final DefaultPolarItemRenderer render = (DefaultPolarItemRenderer) polarPlot.getRenderer();
			for (int series = 0; series < polarPlot.getDatasetCount(); series++) {
				if (render.getSeriesOutlinePaint(series) == oldColor)
					render.setSeriesOutlinePaint(series, newColor);
				if (render.getSeriesPaint(series) == oldColor)
					render.setSeriesPaint(series, newColor);
			}
		} else if (isFlowPlot()) {
			final FlowPlot plot = (FlowPlot)(getChart().getPlot());
			plot.setOutlinePaint(newColor);
			plot.setDefaultNodeLabelPaint(newColor);
		} else if (getChart().getPlot() instanceof RingPlot ringPlot) {
			ringPlot.setLabelPaint(newColor);
			ringPlot.setLabelLinkPaint(newColor);
		}

	}

	private void replaceForegroundColorOfXYPlot(final XYPlot plot, final Color oldColor, final Color newColor) {
		for (int i = 0; i < plot.getDomainAxisCount() ; i++)
			setForegroundColor(plot.getDomainAxis(i), newColor);
		for (int i = 0; i < plot.getRangeAxisCount() ; i++)
			setForegroundColor(plot.getRangeAxis(i), newColor);
		for (int i = 0; i < plot.getRendererCount(); i++) {
			replaceForegroundColor(plot.getRenderer(i), oldColor, newColor);
			replaceSeriesColor(plot.getRenderer(i), oldColor, newColor);
		}
	}

	private void replaceForegroundColor(final LegendItemSource render, final Color oldColor, final Color newColor) {
		if (render == null) return;
		for (int i = 0; i < render.getLegendItems().getItemCount(); i++) {
			final LegendItem item = render.getLegendItems().get(i);
			item.setLabelPaint(newColor);
			if (item.getFillPaint() == oldColor)
				item.setFillPaint(newColor);
			if (item.getLinePaint() == oldColor)
				item.setLinePaint(newColor);
			if (item.getOutlinePaint() == oldColor)
				item.setOutlinePaint(newColor);
		}
		if (render instanceof AbstractRenderer rndr) {
            rndr.setDefaultItemLabelPaint(newColor);
			rndr.setDefaultLegendTextPaint(newColor);
			if (rndr.getDefaultFillPaint()  == oldColor)
				rndr.setDefaultFillPaint(newColor);
			if (rndr.getDefaultOutlinePaint()  == oldColor)
				rndr.setDefaultOutlinePaint(newColor);
		}
		if (render instanceof AbstractCategoryItemRenderer rndr) {
            for (int series = 0; series < rndr.getRowCount(); series++) {
				if (rndr.getSeriesFillPaint(series) == oldColor)
					rndr.setSeriesFillPaint(series, newColor);
				if (rndr.getSeriesOutlinePaint(series) == oldColor)
					rndr.setSeriesOutlinePaint(series, newColor);
				if (rndr.getSeriesItemLabelPaint(series) == oldColor)
					rndr.setSeriesItemLabelPaint(series, newColor);
			}
		}
		if (render instanceof BoxAndWhiskerRenderer)
			((BoxAndWhiskerRenderer)render).setArtifactPaint(newColor);
	}

	private void replaceSeriesColor(final CategoryItemRenderer renderer, final Color oldColor, final Color newColor) {
		final int nSeries = renderer.getPlot().getDataset().getRowCount();
		for (int series = 0; series < nSeries; series++) {
			if (renderer.getSeriesFillPaint(series) == oldColor)
				renderer.setSeriesFillPaint(series, newColor);
			if (renderer.getSeriesOutlinePaint(series) == oldColor)
				renderer.setSeriesOutlinePaint(series, newColor);
			if (renderer.getSeriesItemLabelPaint(series) == oldColor)
				renderer.setSeriesItemLabelPaint(series, newColor);
		}
	}

	private void replaceSeriesColor(final XYItemRenderer renderer, final Color oldColor, final Color newColor) {
		if (renderer != null) {
			final int nSeries = renderer.getPlot().getDataset().getSeriesCount();
			for (int series = 0; series < nSeries; series++) {
				if (renderer.getSeriesFillPaint(series) == oldColor)
					renderer.setSeriesFillPaint(series, newColor);
				if (renderer.getSeriesOutlinePaint(series) == oldColor)
					renderer.setSeriesOutlinePaint(series, newColor);
				if (renderer.getSeriesItemLabelPaint(series) == oldColor)
					renderer.setSeriesItemLabelPaint(series, newColor);
			}
		}
	}

	private void setForegroundColor(final Axis axis, final Color newColor) {
		if (axis != null) {
			axis.setAxisLinePaint(newColor);
			axis.setLabelPaint(newColor);
			axis.setTickLabelPaint(newColor);
			axis.setTickMarkPaint(newColor);
		}
	}

	private Color getColorFromString(final String string) {
		if (string == null) return Color.BLACK;
		final ColorRGB c = new ColorRGB(string);
		return new Color(c.getRed(), c.getGreen(), c.getBlue());
	}

	public void applyStyle(final SNTChart template) {
		// misc
		setPreferredSize(template.getPreferredSize());
		setSize(template.getSize());
		setGridlinesVisible(template.isGridlinesVisible());
		setOutlineVisible(template.isOutlineVisible());

		// colors (non-exhaustive)
		setBackground(template.getBackground());
		setForeground(template.getForeground());
		setBackground(template.getBackground());
		setForeground(template.getForeground());
		getChart().setBackgroundPaint(template.getChart().getBackgroundPaint());
		getChart().setBorderPaint(template.getChart().getBorderPaint());
		if (getChart().getLegend() != null && template.getChart().getLegend() != null) {
			getChart().getLegend().setBackgroundPaint(template.getChart().getLegend().getBackgroundPaint());
			getChart().getLegend().setItemPaint(template.getChart().getLegend().getItemPaint());
		}
		getChart().getPlot().setBackgroundPaint(template.getChart().getPlot().getBackgroundPaint());
		getChart().getPlot().setOutlinePaint(template.getChart().getPlot().getOutlinePaint());
		getChart().getPlot().setNoDataMessagePaint(template.getChart().getPlot().getNoDataMessagePaint());
		setZoomOutlinePaint(template.getZoomOutlinePaint());
		setZoomFillPaint(template.getZoomFillPaint());
		if (getChart().getPlot() instanceof XYPlot && template.getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChart().getPlot());
			final XYPlot tPlot = (XYPlot)(template.getChart().getPlot());
			if (tPlot.getDomainAxis().getAxisLinePaint() instanceof Color)
				setForegroundColor(plot.getDomainAxis(), (Color)tPlot.getDomainAxis().getAxisLinePaint());
			if (tPlot.getRangeAxis().getAxisLinePaint() instanceof Color)
				setForegroundColor(plot.getRangeAxis(), (Color)tPlot.getRangeAxis().getAxisLinePaint());
		} else if (getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = getChart().getCategoryPlot();
			final CategoryPlot tPlot = (CategoryPlot)(template.getChart().getPlot());
			if (tPlot.getDomainAxis().getAxisLinePaint() instanceof Color)
				setForegroundColor(plot.getDomainAxis(), (Color)tPlot.getDomainAxis().getAxisLinePaint());
			if (tPlot.getRangeAxis().getAxisLinePaint() instanceof Color)
				setForegroundColor(plot.getRangeAxis(), (Color)tPlot.getRangeAxis().getAxisLinePaint());
		}
		// fonts
		setFontSize(template.getFontSize("axis"), "axis");
		setFontSize(template.getFontSize("labels"), "labels");
		setFontSize(template.getFontSize("legend"), "legend");
		getChart().getPlot()
				.setNoDataMessageFont(template.getChart().getPlot().getNoDataMessageFont());
	}

	public void addPolygon(final Polygon2D poly, final String lineColor, final String fillColor) {
		final ColorRGB lColor = (lineColor == null || lineColor.isBlank()) ? null : ColorRGB.fromHTMLColor(lineColor);
		final ColorRGB fColor = (fillColor == null || fillColor.isBlank()) ? null : ColorRGB.fromHTMLColor(fillColor);
		addPolygon(poly, lColor, fColor);
	}

	public void addPolygon(final Polygon2D poly, final ColorRGB lineColor, final ColorRGB fillColor) {
		final double[] cc = new double[poly.numVertices() * 2];
		int counter =0;
		for (int i = 0; i < poly.numVertices(); i++) {
			cc[counter++] = poly.vertex(i).getDoublePosition(0);
			cc[counter++] = poly.vertex(i).getDoublePosition(1);
		}
		final Stroke lineStroke = (lineColor == null) ? null : new BasicStroke(1f);
		final Color lColor = (lineColor == null) ? null :
				SNTColor.alphaColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue()), 50);
		final Color fColor = (fillColor == null) ? null :
				SNTColor.alphaColor(new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue()), 25);
		final XYPolygonAnnotation annot = new XYPolygonAnnotation(cc, lineStroke, lColor, fColor);
		//annot.setToolTipText("Polygon2D");
		getXYPlot().getRenderer().addAnnotation(annot);
	}

	/**
	 * Adds a subtitle to the chart.
	 *
	 * @param label the subtitle text
	 */
	public void annotate(final String label) {
		annotate(label, null, "center");
	}

	/**
	 * Adds a subtitle to the chart.
	 *
	 * @param label    the subtitle text
	 * @param tooltip the tooltip text. {@code null} permitted
	 * @param alignment either 'left', 'center', or 'right'
	 */
	public void annotate(final String label, final String tooltip, final String alignment) {
		final TextTitle tLabel = new TextTitle(label);
		tLabel.setFont(tLabel.getFont().deriveFont(Font.PLAIN, getFontSize("legend")));
		tLabel.setPosition(RectangleEdge.BOTTOM);
		tLabel.setToolTipText(tooltip);
		switch (alignment.toLowerCase()) {
		case "left":
			tLabel.setHorizontalAlignment(HorizontalAlignment.LEFT);
			tLabel.setTextAlignment(HorizontalAlignment.LEFT);
			break;
		case "right":
			tLabel.setHorizontalAlignment(HorizontalAlignment.RIGHT);
			tLabel.setTextAlignment(HorizontalAlignment.RIGHT);
			break;
		default:
			tLabel.setHorizontalAlignment(HorizontalAlignment.CENTER);
			tLabel.setTextAlignment(HorizontalAlignment.CENTER);
		}
		getChart().addSubtitle(tLabel);
	}

	/**
	 * Highlights a point in a histogram/XY plot by drawing a labeled arrow at the
	 * specified location.
	 * 
	 * @param x     the x-coordinate
	 * @param y     the y-coordinate
	 * @param label the annotation label
	 */
	public void annotatePoint(final double x, final double y, final String label) {
		annotatePoint(x,y,label, null);
	}

	/**
	 * Highlights a point in a histogram/XY plot by drawing a labeled arrow at the
	 * specified location.
	 *
	 * @param x     the x-coordinate
	 * @param y     the y-coordinate
	 * @param label the annotation label
	 * @param color the annotation color
	 */
	public void annotatePoint(final double x, final double y, final String label, final String color) {
		final XYPointerAnnotation annot = new XYPointerAnnotation(label, x, y, -Math.PI / 2.0);
		final Font font = getXYPlot().getDomainAxis().getTickLabelFont();
		final Color c = getColorFromString(color);
		annot.setLabelOffset(font.getSize());
		annot.setPaint(c);
		annot.setArrowPaint(c);
		annot.setFont(font);
		getXYPlot().addAnnotation(annot);
	}

	/**
	 * Highlights a point in a histogram/XY plot by drawing a labeled arrow at the
	 * specified location.
	 *
	 * @param coordinates the array holding the focal point coordinates of the
	 *                    profile
	 * @param label       the annotation label
	 * @param color       the annotation color
	 */
	public void annotatePoint(final double[] coordinates, final String label, final String color) {
		annotatePoint(coordinates[0], coordinates[1], label, color);
	}

	/** {@inheritDoc} */
	@Override
	public File getDefaultDirectoryForSaveAs() {
		return (super.getDefaultDirectoryForSaveAs() == null) ? SNTPrefs.lastknownDir()
				: super.getDefaultDirectoryForSaveAs();
	}

	/**
	 * Shows this chart on a dedicated frame
	 *
	 * @param width the preferred frame width
	 * @param height the preferred frame height
	 */
	public void show(final int width, final int height) {
		getFrame().setPreferredSize(new Dimension(width, height));
		frame.pack();
		show();
	}

	/**
	 * Disposes of this chart and releases its resources.
	 * <p>
	 * Closes the chart window and removes it from the list of open instances.
	 * This method should be called when the chart is no longer needed to
	 * prevent memory leaks.
	 * </p>
	 */
	public void dispose() {
		disposeInternal();
		openInstances.remove(this);
	}

	private void disposeInternal() {
		if (frame != null) frame.dispose();
		removeAll();
		setChart(null);
	}

	/**
	 * Checks if the chart contains valid data.
	 * <p>
	 * Returns true if the chart object exists and presumably contains data
	 * that can be displayed.
	 * </p>
	 *
	 * @return whether chart contains valid Data
	 */
	public boolean containsValidData() {
		return getChart() != null;
	}

	/**
	 * Sets whether a normal distribution curve should be overlaid over histogram frequencies.
	 *
	 * @param visible whether curve should be displayed. Ignored if current chart is not a histogram
	 */
	public void setGaussianFitVisible(final boolean visible) {
		try {
			AnalysisUtils.getNormalCurveRenderers(getChart().getXYPlot()).forEach( r-> r.setDefaultSeriesVisible(visible));
		} catch (final NullPointerException | ClassCastException ignored) {
			// ignored
		}
	}

	/**
	 * Sets whether a GMM distribution curve should be overlaid over histogram frequencies.
	 *
	 * @param visible whether curve should be displayed. Ignored if current chart is not a histogram
	 */
	public void setGMMFitVisible(final boolean visible) {
		try {
			AnalysisUtils.getGMMCurveRenderers(getChart().getXYPlot()).forEach( r-> r.setDefaultSeriesVisible(visible));
		} catch (final NullPointerException | ClassCastException ignored) {
			// ignored
		}
	}

	/**
	 * Gets whether quartile markers (Q1, Median, Q3) are being overlaid over histogram frequencies
	 *
	 * @return true if current chart is a histogram with overlaid quartile markers
	 */
	public boolean isQuartilesVisible() {
		try {
			return AnalysisUtils.getQuartileMarkersRenderer(getChart().getXYPlot()).getDefaultSeriesVisible();
		} catch (final NullPointerException | ClassCastException ignored) {
			// do nothing
		}
		return false;
	}

	/**
	 * Sets whether quartile markers (Q1, Median, Q3) should be overlaid over histogram frequencies.
	 *
	 * @param visible whether markers should be displayed. Ignored if current chart is not a histogram
	 */
	public void setQuartilesVisible(final boolean visible) {
		try {
			AnalysisUtils.getQuartileMarkersRenderer(getChart().getXYPlot()).setDefaultSeriesVisible(visible);
		} catch (final NullPointerException | ClassCastException ignored) {
			// ignored
		}
	}

	/**
	 * Gets whether a normal distribution curve is being overlaid over histogram frequencies
	 *
	 * @return true if current chart is a histogram with overlaid normal curve
	 */
	public boolean isNormDistributionVisible() {
		try {
			for (final XYItemRenderer renderer : AnalysisUtils.getNormalCurveRenderers(getChart().getXYPlot()))
				if (renderer.getDefaultSeriesVisible()) return true;
		} catch (final NullPointerException | ClassCastException ignored) {
			// do nothing
		}
		return false;
	}

	/**
	 * Gets whether the GMM distribution curve is being overlaid over histogram frequencies
	 *
	 * @return true if current chart is a histogram with overlaid GMM curve
	 */
	public boolean isGMMDistributionVisible() {
		try {
			for (final XYItemRenderer renderer : AnalysisUtils.getGMMCurveRenderers(getChart().getXYPlot()))
				if (renderer.getDefaultSeriesVisible()) return true;
		} catch (final NullPointerException | ClassCastException ignored) {
			// do nothing
		}
		return false;
	}

	@Override
	@Deprecated
	public void setVisible(final boolean b) { // for backwards compatibility
		getFrame().setVisible(b);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void show() {
//		if (GraphicsEnvironment.isHeadless()) {
//			this.saveAsPNG(getDefaultDirectoryForSaveAs());;
//			return;
//		}
		if (!getFrame().isVisible()) {
			if (openInstances.isEmpty())
				AWTWindows.centerWindow(this.getFrame());
			else
				getFrame().setLocationRelativeTo(openInstances.getLast());
		}
		SwingUtilities.invokeLater(() -> getFrame().show());
	}

    private int getValidWidth(final boolean preferred) {
        // e.g., width will be unset in headless calls from pysnt
        return (getWidth() < 1) ? (int) (400 * scalingFactor) : (preferred) ? getPreferredSize().width : getWidth();
    }

    private int getValidHeight(final boolean preferred) {
        // e.g., width will be unset in headless calls from pysnt
        return (getHeight() < 1) ? (int) (400 * scalingFactor) : (preferred) ? getPreferredSize().height : getHeight();
    }

	public void show(final String title) {
		setTitle(title);
		show();
	}

    private void customizePopupMenu() {
        if (getPopupMenu() == null) {
            return;
        }
        addExportDataOption(getPopupMenu());
        if (getChart().getPlot() instanceof XYPlot) {
            addSquarifyOption(getPopupMenu());
        }
        addCustomizationPanel(getPopupMenu());
    }

    private void addSquarifyOption(final JPopupMenu popupMenu) {
        final JCheckBoxMenuItem squarify = new JCheckBoxMenuItem("Equalize Axes", isEqualized());
        squarify.addItemListener( e -> setEqualizeAxes(squarify.isSelected()));
        final JMenu autoRange = getMenu(popupMenu, "Auto Range");
        if (autoRange != null) {
            autoRange.addSeparator();
            autoRange.add(squarify);
        } else
            popupMenu.add(squarify);
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                squarify.setSelected(isEqualized());
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // do nothing
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // do nothing
            }
        });
    }

    private void addExportDataOption(final JPopupMenu popupMenu) {
        final JMenuItem mi = new JMenuItem("Data (as CSV)...");
        mi.addActionListener(e -> {
            final String filename = (getTitle() == null) ? "SNTChartData" : getTitle();
            final File file = new GuiUtils(frame).getSaveFile("Export to CSV (Experimental)",
                    new File(getDefaultDirectoryForSaveAs(), filename + ".csv"), "csv");
            if (file == null)
                return;
            try {
                exportAsCSV(file);
            } catch (final IllegalStateException ise) {
                new GuiUtils(frame).error("Could not save data. See Console for details.");
                ise.printStackTrace();
            }
        });
        final JMenu saveAs = getMenu(popupMenu, "Save as");
        if (saveAs != null) {
            saveAs.addSeparator();
            saveAs.add(mi);
        } else {
            popupMenu.add(mi);
        }
    }

    private void addCustomizationPanel(final JPopupMenu popup) {
        final JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark Theme", false);
        final Paint DEF_ZOP = getZoomOutlinePaint();
        final Paint DEF_ZFP = getZoomFillPaint();
        dark.addItemListener( e -> {
            if (dark.isSelected()) {
                replaceBackground(Color.WHITE, Color.BLACK);
                replaceForegroundColor(Color.BLACK, Color.WHITE);
                if (DEF_ZOP instanceof Color)
                    setZoomOutlinePaint(((Color) DEF_ZOP).brighter());
                if (DEF_ZFP instanceof Color)
                    setZoomOutlinePaint(((Color) DEF_ZFP).brighter());
            } else {
                replaceBackground(Color.BLACK, Color.WHITE);
                replaceForegroundColor(Color.WHITE, Color.BLACK);
                setZoomOutlinePaint(DEF_ZOP);
                setZoomOutlinePaint(DEF_ZFP);
            }
        });
        popup.addSeparator();

        final JMenu cMenu = new JMenu("Contents & Curve Fitting");
        popup.add(cMenu);
        GuiUtils.addSeparator(cMenu, "General:");
        final JCheckBoxMenuItem grid = new JCheckBoxMenuItem("Grid Lines", isGridlinesVisible());
        grid.setEnabled(!isFlowPlot() && !isCombinedPlot()); // somehow the current toggle does not work with combined plots!?
        grid.addItemListener( e -> setGridlinesVisible(grid.isSelected()));
        cMenu.add(grid);
        final JCheckBoxMenuItem legend = new JCheckBoxMenuItem("Legend", isLegendVisible());
        legend.addItemListener( e -> setLegendVisible(legend.isSelected()));
        legend.setEnabled(getChart().getLegend() != null);
        cMenu.add(legend);
        final JCheckBoxMenuItem outline = new JCheckBoxMenuItem("Outline", isOutlineVisible());
        outline.addItemListener( e -> setOutlineVisible(outline.isSelected()));
        cMenu.add(outline);
        GuiUtils.addSeparator(cMenu, "Histograms:");
        final JCheckBoxMenuItem fit1 = new JCheckBoxMenuItem("Gaussian");
        fit1.setEnabled(getChart().getPlot() instanceof XYPlot);
        fit1.addActionListener(e -> {
            try {
                final List<XYItemRenderer> renders = AnalysisUtils.getNormalCurveRenderers(getChart().getXYPlot());
                if (renders.isEmpty()) {
                    new GuiUtils(frame).error("Fitting to Normal distribution is not available.", "Option Not Available");
                    fit1.setSelected(false);
                } else {
                    //AnalysisUtils.getGMMCurveRenderer(getChart().getXYPlot()).forEach(r -> r.setDefaultSeriesVisible(false));
                    renders.forEach(r -> r.setDefaultSeriesVisible(fit1.isSelected()));
                }
            } catch (final NullPointerException | IllegalArgumentException | ClassCastException ignored) {
                new GuiUtils(frame).error("This option requires a (non-polar) histogram.", "Option Not Available");
                fit1.setSelected(false);
            }
        });
        cMenu.add(fit1);
        final JCheckBoxMenuItem fit2 = new JCheckBoxMenuItem("Gaussian Mixture Model");
        fit2.setEnabled(getChart().getPlot() instanceof XYPlot);
        fit2.addActionListener( e -> {
            try {
                final List<XYItemRenderer> gmmRenders = AnalysisUtils.getGMMCurveRenderers(getChart().getXYPlot());
                if (gmmRenders.isEmpty()) {
                    new GuiUtils(frame).error("Gaussian mixture model is not available. Note that at least 20 data"
                            + " points are required for GMM computation.", "Option Not Available");
                    fit2.setSelected(false);
                } else {
                    //AnalysisUtils.getNormalCurveRenderer(getChart().getXYPlot()).forEach(r -> r.setDefaultSeriesVisible(false));
                    gmmRenders.forEach(r -> r.setDefaultSeriesVisible(fit2.isSelected()));
                }
            } catch (final NullPointerException | IllegalArgumentException | ClassCastException ignored) {
                new GuiUtils(frame).error("This option requires a (non-polar) histogram.", "Option Not Available");
                fit2.setSelected(false);
            }
        });
        cMenu.add(fit2);
        final JCheckBoxMenuItem fit3 = new JCheckBoxMenuItem("Quartile Marks");
        fit3.setEnabled(getChart().getPlot() instanceof XYPlot);
        fit3.addActionListener(e -> {
            try {
                final XYItemRenderer r = AnalysisUtils.getQuartileMarkersRenderer(getChart().getXYPlot());
                r.setDefaultSeriesVisible(fit3.isSelected());
            } catch (final NullPointerException | ClassCastException ignored) {
                new GuiUtils(frame).error("This option requires a (non-polar) histogram.", "Option Not Available");
                fit3.setSelected(false);
            }
        });
        cMenu.add(fit3);
        GuiUtils.addSeparator(cMenu, "Polar Plots:");
        JMenuItem jmi = new JMenuItem("Clockwise/Counterclockwise");
        jmi.addActionListener( e -> {
            if (getChart().getPlot() instanceof PolarPlot) {
                ((PolarPlot) getChart().getPlot()).setCounterClockwise(!((PolarPlot) getChart().getPlot()).isCounterClockwise());
                getChart().fireChartChanged();
            } else {
                new GuiUtils(frame).error("This option requires a polar plot.", "Option Not Available");
            }
        });
        cMenu.add(jmi);
        popup.add(cMenu);

        final JMenu utils = new JMenu("Utilities");
        jmi = GuiUtils.MenuItems.combineCharts();
        jmi.addActionListener(e -> new GuiUtils(frame).combineSNTChartPrompt());
        utils.add(jmi);
        GuiUtils.addSeparator(utils, "Operations on All Open Charts:");
        jmi = new JMenuItem("Close...");
        utils.add(jmi);
        jmi.addActionListener( e -> {
            if (new GuiUtils(frame).getConfirmation("Close all open plots? (Undoable operation)", "Close All Plots?"))
                SNTChart.closeAll();
        });
        jmi = new JMenuItem("Merge Into ImageJ Stack...");
        utils.add(jmi);
        jmi.addActionListener( e -> {
            if (new GuiUtils(frame).getConfirmation("Merge all plots? (Undoable operation)", "Merge Into Stack?")) {
                SNTChart.combineAsImagePlus(openInstances).show();
                SNTChart.closeAll();
            }
        });
        jmi = new JMenuItem("Tile");
        utils.add(jmi);
        jmi.addActionListener(e -> SNTChart.tileAll());
        utils.add(jmi);
        utils.addSeparator();
        jmi = new JMenuItem("Save All...");
        utils.add(jmi);
        jmi.addActionListener( e -> {
            try {
                SNTUtils.getContext().getService(CommandService.class)
                        .run(SaveMeasurementsCmd.class, true, (Map<String, Object>) null);
            } catch (final Exception ex) {
                new GuiUtils(frame).error("Could not proceed with operation. " +
                        "Please run \"Save Tables & Analysis Plots...\" manually.", "Command Not Found");
            }
        });
        popup.addSeparator();
        jmi = new JMenuItem("Frame Size...");
        jmi.addActionListener(
                e -> new GuiUtils(frame).adjustComponentThroughPrompt((frame == null) ? SNTChart.this : frame));
        popup.add(jmi);
        popup.add(fontScalingMenu());
        popup.addSeparator();
        popup.add(dark);
        popup.addSeparator();
        popup.add(utils);
    }

	private JMenu fontScalingMenu() {
		final JMenu fontScaleMenu = new JMenu("Font Scaling");
		final float DEF_FONT_SIZE = defFontSize();
		final ButtonGroup buttonGroup = new ButtonGroup();
		boolean matched = false;
		for (final float size : new float[] { .5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f }) {
			final JRadioButtonMenuItem item = new JRadioButtonMenuItem(size + "×");
			if (!matched) {
				matched = Math.abs(size - scalingFactor) < 0.05;
				item.setSelected(matched);
			}
			item.addActionListener(event -> setFontSize(size * DEF_FONT_SIZE));
			buttonGroup.add(item);
			fontScaleMenu.add(item);
		}
		fontScaleMenu.addSeparator();
		final JRadioButtonMenuItem chooseScale = new JRadioButtonMenuItem("Other...", false);
		chooseScale.addActionListener(e -> {
			final GuiUtils gUtils = new GuiUtils(frame);
			final Double newScale = gUtils.getDouble("Scaling factor:", "Scaling Factor", scalingFactor);
			if (newScale == null)
				return;
			if (newScale.isNaN() || newScale <= 0) {
				gUtils.error("Invalid scaling factor.");
				return;
			}
			setFontSize((float) (newScale * DEF_FONT_SIZE));
			setDefaultFontScale(newScale);
			chooseScale.setText("Other... (" + SNTUtils.formatDouble(newScale, 1) + "×)");
		});
		if (!matched) {
			chooseScale.setText("Other... (" + SNTUtils.formatDouble(scalingFactor, 1) + "×)");
			chooseScale.setSelected(true);
		}
		buttonGroup.add(chooseScale);
		fontScaleMenu.add(chooseScale);
		return fontScaleMenu;
	}

	private JMenu getMenu(final JPopupMenu popup, final String menuName) {
		for (final MenuElement element : popup.getSubElements()) {
			if (element instanceof JMenu && menuName.equalsIgnoreCase(((JMenu) element).getText())) {
				return (JMenu) element;
			}
		}
		return null;
	}

	private float defFontSize() {
		if (getChart().getPlot() instanceof XYPlot) {
			return getXYPlot().getRangeAxis().getLabelFont().getSize2D();
		}
		else if (getChart().getPlot() instanceof CategoryPlot) {
			return getCategoryPlot().getDomainAxis().getLabelFont().getSize2D();
		} else if (isFlowPlot()) {
			final FlowPlot plot = (FlowPlot)(getChart().getPlot());
			return plot.getDefaultNodeLabelFont().getSize2D();
		}
		return getChart().getPlot().getNoDataMessageFont().getSize2D();
	}

	/* Experimental: Not all types of data are supported */
	protected void exportAsCSV(final File file) throws IllegalStateException {
		// https://stackoverflow.com/a/58530238
		final ArrayList<String> csv = new ArrayList<>();
        switch (getChart().getPlot()) {
            case XYPlot xyplot -> {
                for (int d = 0; d < xyplot.getDatasetCount(); d++) {
                    final XYDataset xyDataset = xyplot.getDataset(d);
                    if (!xyplot.getRenderer(d).getDefaultSeriesVisible())
                        continue;
                    csv.add(String.format("Dataset%02d,Xaxis,Yaxis", (d + 1)));
                    final int seriesCount = xyDataset.getSeriesCount();
                    for (int i = 0; i < seriesCount; i++) {
                        final int itemCount = xyDataset.getItemCount(i);
                        for (int j = 0; j < itemCount; j++) {
                            final Comparable<?> key = xyDataset.getSeriesKey(i);
                            final Number x = xyDataset.getX(i, j);
                            final Number y = xyDataset.getY(i, j);
                            csv.add(String.format("%s,%s,%s", key, x, y));
                        }
                    }
                }
            }
            case PolarPlot polarPlot -> {
                csv.add("Series,Angle,Radius");
                final XYDataset dataset = polarPlot.getDataset();
                for (int series = 0; series < dataset.getSeriesCount(); series++) {
                    final String seriesName = (String) dataset.getSeriesKey(series);
                    for (int item = 0; item < dataset.getItemCount(series); item++) {
                        final double angle = dataset.getXValue(series, item);
                        final double radius = dataset.getYValue(series, item);
                        csv.add(String.format("%s,%s,%s", seriesName, angle, radius));
                    }
                }
            }
            case CategoryPlot categoryPlotPlot -> {
                for (int d = 0; d < categoryPlotPlot.getDatasetCount(); d++) {
                    final CategoryDataset categoryDataset = categoryPlotPlot.getDataset(d);
                    if (!categoryPlotPlot.getRenderer(d).getDefaultSeriesVisible())
                        continue;
                    csv.add(String.format("Dataset%02d,Xaxis,Yaxis", (d + 1)));
                    final int columnCount = categoryDataset.getColumnCount();
                    final int rowCount = categoryDataset.getRowCount();
                    for (int i = 0; i < rowCount; i++) {
                        for (int j = 0; j < columnCount; j++) {
                            final Comparable<?> key1 = categoryDataset.getRowKey(i);
                            final Comparable<?> key2 = categoryDataset.getColumnKey(j);
                            final Number n = categoryDataset.getValue(i, j);
                            csv.add(String.format("%s,%s,%s", key1, key2, n));
                        }
                    }
                }
            }
            case RingPlot ringPlot -> {
                final PieDataset<?> dataset = ringPlot.getDataset();
                for (int i = 0; i < dataset.getItemCount(); i++) {
                    csv.add(String.format("%s,%s", dataset.getKey(i), dataset.getValue(i)));
                }
            }
            case null, default -> throw new IllegalStateException("Export of this type of dataset is not supported.");
        }
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
			for (final String line : csv) {
				writer.append(line);
				writer.newLine();
			}
		} catch (final IOException e) {
			throw new IllegalStateException("Could not write dataset", e);
		}
	}

	private boolean isFlowPlot() {
		return getChart().getPlot() instanceof FlowPlot;
	}

	private boolean isCombinedPlot() {
		return getChart().getPlot() instanceof CombinedRangeXYPlot ||  getChart().getPlot() instanceof CombinedDomainXYPlot;
	}

	/**
	 * Checks if this chart is part of a multi-panel montage
	 *
	 * @return true, if is combined
	 */
	public boolean isCombined() {
		return otherCombinedCharts != null && !otherCombinedCharts.isEmpty();
	}

	/**
	 * Adds a color bar legend (LUT ramp).
	 *
	 * @param title the color bar label
     * @param colorTable the color table
     * @param min the minimum value in the color table
	 * @param max the maximum value in the color table
	 * @param nDecimalPlaces the number of decimal places in the legend labels
	 */
	public void addColorBarLegend(final String title, final ColorTable colorTable, double min, double max, final int nDecimalPlaces) {
		getChart().addSubtitle(getPaintScaleLegend(title, colorTable, min, max, nDecimalPlaces));
	}

	private class ChartListener implements ChartMouseListener {

		@Override
		public void chartMouseClicked(final ChartMouseEvent event) {

			if (event.getTrigger().getClickCount() != 2)
				return;
			final ChartEntity ce = event.getEntity();
			if (ce instanceof TitleEntity) {
				editTitle((TitleEntity) ce);
			} else if (ce instanceof XYAnnotationEntity) {
				editAnnotation((XYAnnotationEntity) ce);
			} else if (ce instanceof XYItemEntity) {
				editSeries((XYItemEntity) ce);
			} else if (ce instanceof AxisEntity) {
				editAxisTitle((AxisEntity) ce);
			} else if (ce instanceof PlotEntity) {
				editPlotBackground((PlotEntity) ce);
			} else if (!(ce instanceof JFreeChartEntity)) {
				showUneditableMessage();
			} else {
				SNTUtils.log(ce.toString());
			}
		}

        private void editTitle(TitleEntity titleEntity) {
			final Title title = titleEntity.getTitle();
			if (title instanceof TextTitle) {
				final String newLabel = getCustomString(((TextTitle) title).getText());
				if (newLabel != null)
					((TextTitle) title).setText(newLabel);
			}
		}

		private void editAnnotation(XYAnnotationEntity annotEntity) {
			final int idx = annotEntity.getRendererIndex();
			final XYAnnotation annot = getXYPlot().getAnnotations().get(idx);
			if (annot instanceof XYTextAnnotation) {
				final String newLabel = getCustomString(((XYTextAnnotation) annot).getText());
				if (newLabel != null)
					((XYTextAnnotation) annot).setText(newLabel);
			}
		}
		
		private void editSeries(XYItemEntity itemEntity) {
			final int idx = itemEntity.getSeriesIndex();
			final String[] options = {"Change Color", "Change Line Style" };
			final String choice = new GuiUtils(frame).getChoice("Edit Series", 
				"What would you like to edit?", options, options[0]);
			if (choice == null) return;
			switch (choice) {
				case "Change Color":
					editSeriesColor(idx);
					break;
				case "Change Line Style":
					editSeriesStroke(idx);
					break;
				case "All Properties":
					doEditChartProperties(); // Use existing comprehensive editor
					break;
			}
		}
		
		private void editAxisTitle(final AxisEntity axisEntity) {
            final String newLabel = getCustomString(axisEntity.getAxis().getLabel());
            if (newLabel != null) {
                axisEntity.getAxis().setLabel(newLabel);
            }
		}

		private void editPlotBackground(final PlotEntity plotEntity) {
			final Color newColor = getCustomColor("New Background Color");
			if (newColor != null) {
				getChart().getPlot().setBackgroundPaint(newColor);
			}
		}
		
		private void showUneditableMessage() {
			new GuiUtils(frame).error(
					"This component cannot be edited by double-click. "
					+ "Please use options in right-click menu.");
		}
		
		// Helper methods for series editing
		private void editSeriesColor(int seriesIndex) {
			final Color newColor = getCustomColor("New Color for Series " + (seriesIndex+1));
			if (newColor != null) {
				getXYPlot().getRenderer().setSeriesPaint(seriesIndex, SNTColor.alphaColor(newColor, 60));
			}
		}
		
		private void editSeriesStroke(int seriesIndex) {
			final Stroke newStroke = getCustomStroke("Adjust Line Style for Series " + (seriesIndex+1));
			if (newStroke != null) {
				getXYPlot().getRenderer().setSeriesStroke(seriesIndex, newStroke);
			}
		}
		
		private Stroke getCustomStroke(final String promptTitle) {
			final String[] options = {"Thin", "Normal", "Thick", "Dashed", "Dotted"};
			final String choice = new GuiUtils(frame).getChoice("Line Style",
                    promptTitle, options, options[1]);
			if (choice == null) return null;
            return switch (choice) {
                case "Thin" -> new BasicStroke(1.0f);
                case "Thick" -> new BasicStroke(4.0f);
                case "Dashed" -> new BasicStroke(2.0f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10.0f, new float[]{5.0f}, 0.0f);
                case "Dotted" -> new BasicStroke(2.0f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f}, 0.0f);
                default -> new BasicStroke(2.0f);
            };
		}

		String getCustomString(final String old) {
            return new GuiUtils(frame).getString("", "Edit Label", old);
		}

		Color getCustomColor(final String prompt) {
			return new GuiUtils(frame).getColor(prompt, Color.DARK_GRAY, (String[]) null);
		}

		@Override
		public void chartMouseMoved(final ChartMouseEvent event) {
			// do nothing
		}

	}

	public static PaintScaleLegend getPaintScaleLegend(final String title, final ColorTable colorTable, double min, double max, final int nDecimalPlaces) {
		if (min >= max || colorTable == null) {
			throw new IllegalArgumentException("Invalid scale: min must be smaller than max and colorTable not null");
		}
		final LookupPaintScale paintScale = new LookupPaintScale(min, max, Color.BLACK);
		for (int i = 0; i < colorTable.getLength(); i++) {
			final Color color = new Color(colorTable.get(ColorTable.RED, i), colorTable.get(ColorTable.GREEN, i),
					colorTable.get(ColorTable.BLUE, i));

			final double value = min + (i * (max - min)  / colorTable.getLength());
			paintScale.add(value, color);
		}
		final NumberAxis numberAxis = new NumberAxis();
		if (nDecimalPlaces == 0) {
			numberAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
			numberAxis.setNumberFormatOverride(new DecimalFormat("#"));
		} else {
			numberAxis.setNumberFormatOverride(SNTUtils.getDecimalFormat(max, nDecimalPlaces));
		}
		numberAxis.setAutoRangeIncludesZero(min <=0 && max >= 0);
		numberAxis.setRange(min, max);
		numberAxis.setAutoTickUnitSelection(true);
		numberAxis.centerRange((max+min)/2);
		numberAxis.setLabelFont(numberAxis.getLabelFont().deriveFont(GuiUtils.uiFontSize()));
		numberAxis.setTickLabelFont(numberAxis.getTickLabelFont().deriveFont(GuiUtils.uiFontSize()));
        if (title != null) numberAxis.setLabel(title);

        final PaintScaleLegend psl = new PaintScaleLegend(paintScale, numberAxis);
		psl.setBackgroundPaint(null); // transparent
		psl.setPosition(RectangleEdge.RIGHT);
		psl.setAxisLocation(AxisLocation.TOP_OR_RIGHT);
		psl.setHorizontalAlignment(HorizontalAlignment.CENTER);
		psl.setMargin(50, 5, 50, 5);
		return psl;
	}

	/**
	 * @return a list of all opened charts
	 */
	public static List<SNTChart> openCharts() {
		return openInstances;
	}

	/** Closes all open charts */
	public static void closeAll() {
        for (SNTChart openInstance : openInstances) {
            openInstance.disposeInternal();
        }
		openInstances.clear();
	}

    /**
     * Tiles all open charts displaying them on a grid. Charts's windows are made visible if not displayed.
     */
    public static void tileAll() {
        tile(openInstances);
    }

    /**
     * Tiles specified charts displaying them on a grid. Charts's windows are made visible if not displayed.
     *
     * @param charts the charts to be tiled
     */
    public static void tile(final Collection<SNTChart> charts) {
        final List<JFrame> frames = new ArrayList<>();
        charts.forEach(oi -> frames.add(oi.getFrame()));
        GuiUtils.tile(frames);
    }

	/**
	 * Combines a collection of charts into a ImageJ1 stack.
	 *
	 * @param charts input charts
	 * @return the stack as an ImagePlus (RGB)
	 */
	public static ImagePlus combineAsImagePlus(final Collection<SNTChart> charts) {
		final List<ImagePlus> images = new ArrayList<>();
		charts.forEach( chart -> images.addAll(chart.getImages(1f)));
		final ImagePlus imp = ImagesToStack.run(images.toArray(new ImagePlus[0]));
		imp.setTitle("Combined SNTChart");
		return imp;
	}

	/**
	 * Combines a collection of charts into a multipanel montage. Number of rows and
	 * columns is automatically determined.
	 *
	 * @param charts input charts
	 * @return the frame containing the montage.
	 */
	public static SNTChart combine(final Collection<SNTChart> charts) {
		return combine(charts, -1, -1, false);
	}

	/**
	 * Combines a collection of charts into a multipanel montage. Number of rows and
	 * columns is automatically determined.
	 *
	 * @param charts      input charts
	 * @param labelPanels whether each panel in the montage should be labeled
	 * @return the frame containing the montage.
	 */
	public static SNTChart combine(final Collection<SNTChart> charts, final boolean labelPanels) {
		return combine(charts, -1, -1, labelPanels);
	}

    /**
     * Combines a collection of charts into a multipanel montage.
     *
     * @param charts      input charts
     * @param rows        the number of rows in the montage
     * @param cols        the number of columns in the montage
     * @param labelPanels whether each panel in the montage should be labeled
     * @return the frame containing the montage
     */
    public static SNTChart combine(final Collection<SNTChart> charts, final int rows, final int cols,
                                   final boolean labelPanels) {
        return combine(charts, null, rows, cols, labelPanels);
    }

	/**
	 * Combines a collection of charts into a multipanel montage.
	 *
	 * @param charts      input charts
     * @param commonTitle Common title for the montage
	 * @param rows        the number of rows in the montage
	 * @param cols        the number of columns in the montage
	 * @param labelPanels whether each panel in the montage should be labeled
	 * @return the frame containing the montage
	 */
	public static SNTChart combine(final Collection<SNTChart> charts, final String commonTitle, final int rows, final int cols,
			final boolean labelPanels) {
        final MultiSNTChart mChart =  new MultiSNTChart(charts, rows, cols, labelPanels);
        if (commonTitle != null && !commonTitle.isBlank())
            mChart.setLabel(commonTitle);
        return mChart.getChart();
	}

	/**
	 * Shows a bivariate histogram (two-dimensional histogram) from a matrix. The number of
	 * bins is automatically determined using the Freedman-Diaconis rule.
	 * @param data the matrix holding the two distributions to be plotted
	 * @param colorTable the color table (LUT) used to color histogram bars (null allowed)
	 * @param prob	Whether frequencies should be normalized to probabilities
	 * @param axisLabels Labels for the axes (optional)
	 * @throws InterruptedException if the histogram cannot be displayed
	 * @throws InvocationTargetException if the histogram cannot be displayed
	 */
	public static void showHistogram3D(final double[][] data, final ColorTable colorTable, final boolean prob, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		final int nBins1 = AnalysisUtils.computeNBins(new DescriptiveStatistics(data[0]));
		final int nBins2 = AnalysisUtils.computeNBins(new DescriptiveStatistics(data[1]));
		showSmilePlot(smile.plot.swing.Histogram3D.class, data, nBins1, nBins2, prob, colorTable, axisLabels);
	}

	/**
	 * Shows a two-dimensional heatmap from a two-dimensional data matrix.
	 * @param data the two-dimensional matrix holding the data to be plotted
	 * @param colorTable the heatmap color table (LUT) (null not allowed)
	 * @param axisLabels Labels for the axes (optional)
	 * @throws InterruptedException if the heatmap cannot be displayed
	 * @throws InvocationTargetException if the heatmap cannot be displayed
	 */
	public static void showHeatmap(final double[][] data, final ColorTable colorTable, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		showSmilePlot(smile.plot.swing.Heatmap.class, data, -1, -1, false, colorTable, axisLabels);
	}

	/**
	 * Shows a bivariate histogram (two-dimensional histogram) from two collections of values. The number of
	 * bins is automatically determined using the Freedman-Diaconis rule.
	 * @param values1 the values of the first distribution to be plotted
	 * @param values2 the values of the second distribution to be plotted
	 * @param colorTable the color table (LUT) used to color histogram bars (null allowed)
	 * @param axisLabels Labels for the axes (optional)
	 * @throws InterruptedException if the histogram cannot be displayed
	 * @throws InvocationTargetException if the histogram cannot be displayed
	 */
	public static void showHistogram3D(final Collection<Double> values1, final Collection<Double> values2,
									   final ColorTable colorTable, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		final double[] v1 = values1.stream().mapToDouble(Double::doubleValue).toArray();
		final double[] v2 = values2.stream().mapToDouble(Double::doubleValue).toArray();
		final double[][] data = IntStream.range(0, Math.min(v1.length, v2.length))
				.mapToObj(i -> new double[]{v1[i], v2[i]}).toArray(double[][]::new);
		final int nBins1 = AnalysisUtils.computeNBins(new DescriptiveStatistics(v1));
		final int nBins2 = AnalysisUtils.computeNBins(new DescriptiveStatistics(v2));
		showSmilePlot(smile.plot.swing.Histogram3D.class, data, nBins1, nBins2, false, colorTable, axisLabels);
	}

	/**
	 * Shows a two-dimensional heatmap for a two-dimensional data matrix assembled from two collections
	 * @param values1 the values of the first matrix column
	 * @param values2 the values of the second matrix column
	 * @param colorTable the heatmap color table (LUT) (null not allowed)
	 * @param axisLabels Labels for the axes (optional)
	 * @throws InterruptedException if the heatmap cannot be displayed
	 * @throws InvocationTargetException if the heatmap cannot be displayed
	 */
	public static void showHeatmap(final Collection<Double> values1, final Collection<Double> values2,
									   final ColorTable colorTable, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		final double[] v1 = values1.stream().mapToDouble(Double::doubleValue).toArray();
		final double[] v2 = values2.stream().mapToDouble(Double::doubleValue).toArray();
		final double[][] data = IntStream.range(0, Math.min(v1.length, v2.length))
				.mapToObj(i -> new double[]{v1[i], v2[i]}).toArray(double[][]::new);
		showSmilePlot(smile.plot.swing.Heatmap.class, data, -1, -1, false, colorTable, axisLabels);
	}

	/**
	 * Shows a bivariate histogram (two-dimensional histogram) from two DescriptiveStatistics objects. The number of
	 * bins is automatically determined using the Freedman-Diaconis rule.
	 * @param stats1 DescriptiveStatistics for the first distribution
	 * @param stats2 DescriptiveStatistics for the second distribution
	 * @param colorTable the color table (LUT) used to color histogram bars (Null allowed)
	 * @param axisLabels Labels for the axes (optional)
	 * @throws InterruptedException if the histogram cannot be displayed
	 * @throws InvocationTargetException if the histogram cannot be displayed
	 */
	public static void showHistogram3D(final DescriptiveStatistics stats1, final DescriptiveStatistics stats2,
									   final ColorTable colorTable, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		showSmileHistogram(smile.plot.swing.Histogram3D.class, stats1, stats2, colorTable, axisLabels);
	}

	private static <T extends smile.plot.swing.Plot> void showSmileHistogram(final Class<T> smilePlotClass, final DescriptiveStatistics stats1, final DescriptiveStatistics stats2,
									   final ColorTable colorTable, final String... axisLabels) throws InterruptedException, InvocationTargetException {
		final double[] v1 = stats1.getValues();
		final double[] v2 = stats2.getValues();
		final double[][] data = IntStream.range(0, Math.min(v1.length, v2.length))
				.mapToObj(i -> new double[]{v1[i], v2[i]}).toArray(double[][]::new);
		final int nBins1 = AnalysisUtils.computeNBins(stats1);
		final int nBins2 = AnalysisUtils.computeNBins(stats2);
		showSmilePlot(smilePlotClass, data, nBins1, nBins2, false, colorTable, axisLabels);
	}

	private static <T extends smile.plot.swing.Plot> void showSmilePlot(final Class<T> smilePlotClass,
												final double[][] data, // data to be plotted
												final int nBins1, final int nBins2, // no. of bins. Ignored if not Histogram3D
												final boolean prob, // normalize frequencies? Ignored if not Histogram3D
												final ColorTable colorTable,
												final String... axisLabels) throws InterruptedException, InvocationTargetException {
		final Color[] palette = alphaColorsFromColorTable(colorTable);
		smile.plot.swing.Figure figure;
		String title;
		if (smile.plot.swing.Histogram3D.class.equals(smilePlotClass)) {
            figure = new smile.plot.swing.Histogram3D(data, nBins1, nBins2, prob, palette).figure();
			title = "Two-Dimensional Histogram";
		} else if (smile.plot.swing.Heatmap.class.equals(smilePlotClass)) {
            figure = smile.plot.swing.Heatmap.of(data, palette).figure();
			title = "Heatmap";
		} else {
			throw new IllegalArgumentException("Unsupported plot type: " + smilePlotClass);
		}
		if (axisLabels != null && axisLabels.length > 0)
            figure.setAxisLabels(Arrays.copyOf(axisLabels, figure.getAxisLabels().length));
        figure.setTitle(title);
        figure.show();
	}

	private static Color[] alphaColorsFromColorTable(final ColorTable colorTable) {
		if (colorTable == null)
			return new Color[]{SNTColor.alphaColor(Color.LIGHT_GRAY, 75)};
		final Color[] palette = new Color[colorTable.getLength()];
		for (int i = 0; i < colorTable.getLength(); i++) {
			palette[i] = SNTColor.alphaColor(new Color(colorTable.get(ColorTable.RED, i), colorTable.get(ColorTable.GREEN, i),
					colorTable.get(ColorTable.BLUE, i)), 75);
		}
		return palette;
	}

	private static class MultiSNTChart {

		final Collection<SNTChart> charts;
		int[] grid;
		String label;
		boolean labelPanels;
		SNTChart holdingPanel;

		MultiSNTChart(final Collection<SNTChart> charts, final int rows, final int cols, final boolean labelPanels) {
			if (charts == null || charts.isEmpty())
				throw new IllegalArgumentException("Invalid chart list");
			this.charts = charts;
			if (rows == -1 || cols == -1)
				grid = splitIntoParts(charts.size(), 2);
			else
				grid = new int[] { rows, cols };
			this.labelPanels = labelPanels;
		}

		@SuppressWarnings("unused")
		void setLabel(final String label) {
			this.label = label;
		}

		String getLabel() {
			return (label == null) ? "Combined Charts" : label;
		}

		SNTChart getChart() {
			// make all plots the same size. Not sure if this is needed!?
			int w = Integer.MIN_VALUE;
			int h = Integer.MIN_VALUE;
			for (final SNTChart chart : charts) {
                final int w1 = chart.getValidWidth(true);
                final int h1 = chart.getValidHeight(true);
                if (w1 > w) w = w1;
                if (h1 > h) w = h1;
			}
			for (final SNTChart chart : charts)
				chart.setPreferredSize(new Dimension(w, h));
			holdingPanel = new SNTChart(getLabel(), (JFreeChart)null);
			holdingPanel.setLayout(new GridLayout(grid[0], grid[1]));
			holdingPanel.setToolTipText("Use the \"Save Tables & Analysis Plots...\" command to save panel(s)");
			holdingPanel.otherCombinedCharts = new ArrayList<>();
			charts.forEach(chart -> {
				if (labelPanels) {
                    final String title = (chart.title == null) ? chart.getChart().getTitle().getText() : chart.title;
                    if (title != null) {
                        chart.setChartTitle(title);
                        chart.getChart().getTitle().setFont(chart.getChart().getTitle().getFont().deriveFont(Font.PLAIN)
                                .deriveFont(chart.defFontSize()));
                    }
                }
				holdingPanel.add(chart);
				holdingPanel.otherCombinedCharts.add(chart);
			});
			// panel.setBackground(charts.get(charts.size()-1).getBackground());
            if (!GraphicsEnvironment.isHeadless()) {
                final Dimension sSize = Toolkit.getDefaultToolkit().getScreenSize();
                holdingPanel.setPreferredSize(scale(holdingPanel.getPreferredSize(), sSize.width * .85, sSize.height * .85));
            }
			return holdingPanel;
		}

		int[] splitIntoParts(final int whole, final int parts) {
			if (whole < 4) return new int[] { 1,  whole };
			// https://stackoverflow.com/a/32543184
			final int[] arr = new int[parts];
			int remain = whole;
			int partsLeft = parts;
			for (int i = 0; partsLeft > 0; i++) {
				final int size = (remain + partsLeft - 1) / partsLeft;
				arr[i] = size;
				remain -= size;
				partsLeft--;
			}
			return arr;
		}

		Dimension scale(Dimension dim1, final double maxW, final double maxH) {
			int width1 = dim1.width;
			int height1 = dim1.height;
			int newWidth = width1;
			int newHeight = height1;
			if (width1 > maxW) {
				newWidth = (int) maxW;
				newHeight = (newWidth * height1) / width1;
			}
			if (newHeight > maxH) {
				newHeight = (int) maxH;
				newWidth = (newHeight * width1) / height1;
			}
			return new Dimension(newWidth, newHeight);
		}

	}

	public static void setDefaultFontScale(final double scalingFactor) {
		SNTChart.scalingFactor = scalingFactor;
	}

	/**
	 * Creates a histogram from the given table. The number of bins is automatically determined using the
	 * Freedman-Diaconis rule.
	 *
	 * @param table the table holding the data to be plotted
	 * @param columnHeaders the headers of the columns to be plotted (case-sensitive)
	 * @param polar whether the histogram should be polar. Data expected in degrees ]0, 360]
	 * @return the histogram chart
	 */
	public static SNTChart getHistogram(final SNTTable table, final Collection<String> columnHeaders, final boolean polar) {
		final List<Integer> columnIndices = new ArrayList<>();
		final List<String> allHeaders = table.geColumnHeaders();
		for (final String columnHeader : columnHeaders) {
			columnIndices.add(allHeaders.indexOf(columnHeader));
		}
		final int[] columnIndicesArray = columnIndices.stream().mapToInt(Integer::intValue).toArray();
		return getHistogram(table, columnIndicesArray, polar);
	}

	/**
	 * Creates a histogram from the given table. The number of bins is automatically determined using the
	 * Freedman-Diaconis rule.
	 *
	 * @param table the table holding the data to be plotted
	 * @param columnIndices the indices (0-based) of the columns to be plotted
	 * @param polar whether the histogram should be polar. Data expected in degrees ]0, 360]
	 * @return the histogram chart
	 */
	public static SNTChart getHistogram(final SNTTable table, final int[] columnIndices, final boolean polar) {
		final LinkedHashMap<String, AnalysisUtils.HistogramDatasetPlus> hdpMap = new LinkedHashMap<>();
		int nBins = 2;
		final boolean isSummarized = table.isSummarized();
		table.removeSummary();
		final double[] limits = new double[] {Double.MAX_VALUE, Double.MIN_VALUE};
		for (final int colIdx : columnIndices) {
			try {
				final Column<?> col = table.get(colIdx);
				final DescriptiveStatistics stats = new DescriptiveStatistics();
				col.forEach(row -> {
					if (row != null && !Double.isNaN((double) row))
						stats.addValue((Double) row);
				});
				final double max = stats.getMax();
				final double min = stats.getMin();
				if (min < limits[0]) limits[0] = min;
				if (max > limits[1]) limits[1] = max;
				final AnalysisUtils.HistogramDatasetPlus hdp = new AnalysisUtils.HistogramDatasetPlus(stats, table.getColumnHeader(colIdx));
				hdp.compute();
				nBins = Math.max(nBins, hdp.nBins);
				hdpMap.put(table.getColumnHeader(colIdx), hdp);
			} catch (final IndexOutOfBoundsException ex) {
				throw new IllegalArgumentException("Invalid column header. Available headers: "
						+ table.geColumnHeaders().toString());
			} finally {
				if (isSummarized) table.summarize();
			}
		}
		final HistogramDataset dataset = new HistogramDataset();
		dataset.setType(HistogramType.RELATIVE_FREQUENCY);
		int finalNBins = nBins;
		final AtomicInteger index = new AtomicInteger();
		hdpMap.forEach((label, hdp) ->
				dataset.addSeries((label==null) ? index.getAndIncrement() : label, hdp.values(), finalNBins, limits[0], limits[1]));
		final String title = table.getTitle();
		final String axisLabel = (columnIndices.length == 1) ? table.getColumnHeader(columnIndices[0]) : "";
		final SNTChart chart = (polar) ?
				AnalysisUtils.createPolarHistogram(axisLabel, "", dataset, hdpMap.size(), nBins)
				: AnalysisUtils.createHistogram(axisLabel, "", hdpMap.size(), dataset,
				new ArrayList<>(hdpMap.values()));
		chart.setTitle(title);
		return chart;
	}

	public static SNTChart getHistogram(final SNTTable table, final boolean polar) {
		return getHistogram(table, IntStream.range(0, table.getColumnCount()).toArray(), polar);
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final Tree tree = new SNTService().demoTrees().getFirst();
		final TreeStatistics treeStats = new TreeStatistics(tree);
		final SNTChart chart = treeStats.getHistogram("contraction");
		chart.annotatePoint(0.75, 0.15, "No data here", "green");
		chart.annotateXline(0.80, "Start of slope", "blue");
		chart.annotateYline(0.05, "5% mark", "red");
		chart.annotate("Annotation");
		//chart.setFontSize(18);
		chart.show();
	}

}
