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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.jfree.chart.ChartFrame;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.LegendItemSource;
import org.jfree.chart.annotations.CategoryAnnotation;
import org.jfree.chart.annotations.CategoryTextAnnotation;
import org.jfree.chart.annotations.TextAnnotation;
import org.jfree.chart.annotations.XYAnnotation;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.Axis;
import org.jfree.chart.axis.CategoryAnchor;
import org.jfree.chart.plot.CategoryMarker;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.Marker;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BoxAndWhiskerRenderer;
import org.jfree.chart.renderer.category.CategoryItemRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.title.Title;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.Range;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.Dataset;
import org.jfree.data.xy.XYDataset;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.util.ColorRGB;

import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Extension of {@link ChartFrame} with convenience methods for plot annotations.
 *
 * @author Tiago Ferreira
 */
public class SNTChart extends ChartFrame {

	private static final long serialVersionUID = 5245298401153759551L;
	private static final Color BACKGROUND_COLOR = Color.WHITE;

	public SNTChart(final String title, final JFreeChart chart) {
		this(title, chart, new Dimension(400, 400));
	}

	protected SNTChart(final String title, final JFreeChart chart, final Dimension preferredSize) {
		super(title, chart);
		chart.setBackgroundPaint(BACKGROUND_COLOR);
		chart.setAntiAlias(true);
		chart.setTextAntiAlias(true);
		if (chart.getLegend() != null) {
			chart.getLegend().setBackgroundPaint(chart.getBackgroundPaint());
		}
		final ChartPanel cp = new ChartPanel(chart);
		// Tweak: Ensure chart is always drawn and not scaled to avoid rendering
		// artifacts
		cp.setMinimumDrawWidth(0);
		cp.setMaximumDrawWidth(Integer.MAX_VALUE);
		cp.setMinimumDrawHeight(0);
		cp.setMaximumDrawHeight(Integer.MAX_VALUE);
		cp.setBackground(BACKGROUND_COLOR);
		setBackground(BACKGROUND_COLOR); // provided contrast to otherwise transparent background
		setPreferredSize(preferredSize);
		pack();
	}

	private XYPlot getXYPlot() {
		return getChartPanel().getChart().getXYPlot();
	}

	private CategoryPlot getCategoryPlot() {
		return getChartPanel().getChart().getCategoryPlot();
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

	public void setAxesVisible(final boolean visible) {
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChartPanel().getChart().getPlot());
			plot.getDomainAxis().setVisible(visible);
			plot.getRangeAxis().setVisible(visible);
		} else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = (CategoryPlot)(getChartPanel().getChart().getCategoryPlot());
			plot.getDomainAxis().setVisible(visible);
			plot.getRangeAxis().setVisible(visible);
		}
	}

	public void setGridlinesVisible(final boolean visible) {
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChartPanel().getChart().getPlot());
			plot.setDomainGridlinesVisible(false);
			plot.setRangeGridlinesVisible(false);
		} else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = (CategoryPlot)(getChartPanel().getChart().getCategoryPlot());
			plot.setDomainGridlinesVisible(false);
			plot.setRangeGridlinesVisible(false);
		}
	}

	public void setOutlineVisible(final boolean visible) {
		getChartPanel().getChart().getPlot().setOutlineVisible(visible);
	}

	/**
	 * Annotates the specified category (Category plots only)
	 *
	 * @param category the category to be annotated. Ignored if it does not exits in
	 *                 category axis.
	 * @param label    the annotation label
	 */
	public void annotateCategory(final String category, final String label) {
		annotateCategory(category, label, "blue");
	}

	/**
	 * Annotates the specified category (Category plots only).
	 *
	 * @param category the category to be annotated. Ignored if it does not exits in
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
		if (catPlot.getCategories().contains(category)) {
			if (label != null && !label.isEmpty()) {
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
		getChartPanel().getChart().getPlot()
				.setNoDataMessageFont(getChartPanel().getChart().getPlot().getNoDataMessageFont().deriveFont(size));
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
			if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
				Font font = getXYPlot().getDomainAxis().getTickLabelFont().deriveFont(size);
				getXYPlot().getDomainAxis().setTickLabelFont(font);
				font = getXYPlot().getRangeAxis().getTickLabelFont().deriveFont(size);
				getXYPlot().getRangeAxis().setTickLabelFont(font);
			}
			else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
				Font font = getCategoryPlot().getDomainAxis().getTickLabelFont().deriveFont(size);
				getCategoryPlot().getDomainAxis().setTickLabelFont(font);
				font = getCategoryPlot().getRangeAxis().getTickLabelFont().deriveFont(size);
				getCategoryPlot().getRangeAxis().setTickLabelFont(font);
			}
			break;
		case "legend":
		case "legends":
		case "subtitle":
		case "subtitles":
			final LegendTitle legend = getChartPanel().getChart().getLegend();
			if (legend != null)
				legend.setItemFont(legend.getItemFont().deriveFont(size));
			for (int i = 0; i < getChartPanel().getChart().getSubtitleCount(); i++) {
				final Title title = getChartPanel().getChart().getSubtitle(i);
				if (title instanceof TextTitle) {
					final TextTitle tt = (TextTitle) title;
					tt.setFont(tt.getFont().deriveFont(size));
				} else if (title instanceof LegendTitle) {
					final LegendTitle lt = (LegendTitle) title;
					lt.setItemFont(lt.getItemFont().deriveFont(size));
				}
			}
			break;
		default: // labels  annotations
			if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
				Font font = getXYPlot().getDomainAxis().getLabelFont().deriveFont(size);
				getXYPlot().getDomainAxis().setLabelFont(font);
				font = getXYPlot().getRangeAxis().getLabelFont().deriveFont(size);
				getXYPlot().getRangeAxis().setLabelFont(font);
				final List<?> annotations = getXYPlot().getAnnotations();
				if (annotations != null) {
					for (int i = 0; i < getXYPlot().getAnnotations().size(); i++) {
						final XYAnnotation annotation = (XYAnnotation) getXYPlot().getAnnotations().get(i);
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
			else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
				Font font = getCategoryPlot().getDomainAxis().getLabelFont().deriveFont(size);
				getCategoryPlot().getDomainAxis().setLabelFont(font);
				font = getCategoryPlot().getRangeAxis().getLabelFont().deriveFont(size);
				getCategoryPlot().getRangeAxis().setLabelFont(font);
				final List<?> annotations = getCategoryPlot().getAnnotations();
				if (annotations != null) {
					for (int i = 0; i < annotations.size(); i++) {
						final CategoryAnnotation annotation = (CategoryAnnotation) annotations.get(i);
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
			break;
		}
	}

	public void saveAsPNG(final File file) throws IOException {
		ChartUtils.saveChartAsPNG(file, getChartPanel().getChart(), getChartPanel().getWidth(),  getChartPanel().getHeight());
	}

	public void saveAsPNG(final String filePath) throws IOException {
		final File f = new File((filePath.toLowerCase().endsWith(".png")) ? filePath : filePath + ".png");
		f.getParentFile().mkdirs();
		saveAsPNG(f);
	}

	private void adjustMarkersFont(final Collection<?> markers, final float size) {
		if (markers != null) {
			markers.forEach(marker -> {
				((Marker) marker).setLabelFont(((Marker) marker).getLabelFont().deriveFont(size));
			});
		}
	}

	private void replaceBackground(final Color oldColor, final Color newColor) {
		if (this.getBackground() == oldColor)
			this.setBackground(newColor);
		if (getChartPanel().getBackground() == oldColor)
			getChartPanel().setBackground(newColor);
		if (getChartPanel().getChart().getBackgroundPaint() == oldColor)
			getChartPanel().getChart().setBackgroundPaint(newColor);
		final LegendTitle legend = getChartPanel().getChart().getLegend();
		if (legend != null && legend.getBackgroundPaint() == oldColor) {
			legend.setBackgroundPaint(newColor);
		}
		for (int i = 0; i < getChartPanel().getChart().getSubtitleCount(); i++) {
			final Title title = getChartPanel().getChart().getSubtitle(i);
			if (title instanceof TextTitle) {
				final TextTitle tt = (TextTitle) title;
				if (tt.getBackgroundPaint() == oldColor)
					tt.setBackgroundPaint(newColor);
			} else if (title instanceof LegendTitle) {
				final LegendTitle lt = (LegendTitle) title;
				if (lt.getBackgroundPaint() == oldColor)
					lt.setBackgroundPaint(newColor);
			}
		}
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChartPanel().getChart().getPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
			XYItemRenderer render = plot.getRenderer();
		} else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = (CategoryPlot)(getChartPanel().getChart().getCategoryPlot());
			if (plot.getBackgroundPaint() == oldColor)
				plot.setBackgroundPaint(newColor);
			CategoryItemRenderer render = plot.getRenderer();
		}
	}

	private void replaceForegroundColor(final Color oldColor, final Color newColor) {
		if (this.getForeground() == oldColor)
			this.setForeground(newColor);
		if (getChartPanel().getForeground() == oldColor)
			getChartPanel().setForeground(newColor);
		if (getChartPanel().getChart().getBorderPaint() == oldColor)
			getChartPanel().getChart().setBorderPaint(newColor);
		final LegendTitle legend = getChartPanel().getChart().getLegend();
		if (legend != null && legend.getItemPaint() == oldColor) {
			legend.setItemPaint(newColor);
		}
		for (int i = 0; i < getChartPanel().getChart().getSubtitleCount(); i++) {
			final Title title = getChartPanel().getChart().getSubtitle(i);
			if (title instanceof TextTitle) {
				((TextTitle) title).setPaint(newColor);
			} else if (title instanceof LegendTitle) {
				((LegendTitle) title).setItemPaint(newColor);
			}
		}
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			final XYPlot plot = (XYPlot)(getChartPanel().getChart().getPlot());
			setForegroundColor(plot.getDomainAxis(), newColor);
			setForegroundColor(plot.getRangeAxis(), newColor);
			replaceForegroundColor(plot.getRenderer(), oldColor, newColor);
		} else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			final CategoryPlot plot = (CategoryPlot)(getChartPanel().getChart().getCategoryPlot());
			setForegroundColor(plot.getDomainAxis(), newColor);
			setForegroundColor(plot.getRangeAxis(), newColor);
			replaceForegroundColor(plot.getRenderer(), oldColor, newColor);
		}
	}

	private void replaceForegroundColor(final LegendItemSource render, final Color oldColor, final Color newColor) {
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
		if (render instanceof BoxAndWhiskerRenderer) {
			final BoxAndWhiskerRenderer rndr = ((BoxAndWhiskerRenderer)render);
			rndr.setDefaultItemLabelPaint(newColor);
			rndr.setDefaultLegendTextPaint(newColor);
			rndr.setArtifactPaint(newColor);
			if (rndr.getDefaultFillPaint()  == oldColor)
				rndr.setDefaultFillPaint(newColor);
			if (rndr.getDefaultOutlinePaint()  == oldColor)
				rndr.setDefaultOutlinePaint(newColor);
			for (int series = 0; series < rndr.getRowCount(); series++) {
				if (rndr.getSeriesFillPaint(series) == oldColor)
					rndr.setSeriesFillPaint(series, newColor);
				if (rndr.getSeriesOutlinePaint(series) == oldColor)
					rndr.setSeriesOutlinePaint(series, newColor);
				if (rndr.getSeriesItemLabelPaint(series) == oldColor)
					rndr.setSeriesItemLabelPaint(series, newColor);
			}
		}
		
	}

	private void setForegroundColor(final Axis axis, final Color newColor) {
		axis.setAxisLinePaint(newColor);
		axis.setLabelPaint(newColor);
		axis.setTickLabelPaint(newColor);
		axis.setTickMarkPaint(newColor);
	}

	private Color getColorFromString(final String string) {
		if (string == null) return Color.BLACK;
		final ColorRGB c = new ColorRGB(string);
		return (c==null) ? Color.BLACK : new Color(c.getRed(), c.getGreen(), c.getBlue());
	}

	/**
	 * Adds a subtitle to the chart.
	 *
	 * @param label the subtitle text
	 */
	public void annotate(final String label) {
		final TextTitle tLabel = new TextTitle(label);
		tLabel.setFont(tLabel.getFont().deriveFont(Font.PLAIN));
		tLabel.setPosition(RectangleEdge.BOTTOM);
		getChartPanel().getChart().addSubtitle(tLabel);
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

	public void show(final int width, final int height) {
		setPreferredSize(new Dimension(width, height));
		pack();
		show();
	}

	@Override
	@SuppressWarnings("deprecation")
	public void show() {
		if (getChartPanel() != null && getChartPanel().getPopupMenu() != null) {
			final JMenuItem mi = new JMenuItem("Data (as CSV)...");
			mi.addActionListener(e -> {
				final JFileChooser fileChooser = new JFileChooser();
				fileChooser.setDialogTitle("Export to CSV (Experimental)");
				final FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV files (*.csv)", "csv");
				fileChooser.addChoosableFileFilter(csvFilter);
				fileChooser.setFileFilter(csvFilter);
				if (fileChooser.showSaveDialog(getChartPanel()) == JFileChooser.APPROVE_OPTION) {
					File file = fileChooser.getSelectedFile();
					if (file == null)
						return;
					if (!file.getName().toLowerCase().endsWith("csv")) {
						file = new File(file.toString() + ".csv");
					}
					try {
						exportAsCSV(file);
					} catch (final IllegalStateException ise) {
						new GuiUtils(this).error("Could not save data. See Console for details");
						ise.printStackTrace();
					}
				}
			});
			final JMenu saveAs = getMenu(getChartPanel().getPopupMenu(), "Save as");
			if (saveAs != null)
				saveAs.add(mi);
			else
				getChartPanel().getPopupMenu().add(mi);
			addCustomizationPanel(getChartPanel().getPopupMenu());
		}
		AWTWindows.centerWindow(this);
		SwingUtilities.invokeLater(() -> super.show());
	}

	private void addCustomizationPanel(final JPopupMenu popup) {
		final JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark Mode", false);
		dark.addItemListener( e -> {
			if (dark.isSelected()) {
				replaceBackground(Color.WHITE, Color.BLACK);
				replaceForegroundColor(Color.BLACK, Color.WHITE);
			} else {
				replaceBackground(Color.BLACK, Color.WHITE);
				replaceForegroundColor(Color.WHITE, Color.BLACK);
			}
		});
		popup.addSeparator();
		popup.add(dark);

		final float DEF_FONT_SIZE = defFontSize();
		final JSpinner spinner = GuiUtils.doubleSpinner(1, 0.5, 4, 0.5, 1);
		spinner.addChangeListener(e -> {
			setFontSize( ((Double)spinner.getValue()).floatValue() * DEF_FONT_SIZE );
		});
		final JPanel p = new JPanel();
		p.setBackground(popup.getBackground());
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridwidth = 2;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel(" Scaling: ", true));
		c.gridx = 1;
		p.add(spinner, c);
		popup.addSeparator();
		popup.add(p);
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
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			return getXYPlot().getDomainAxis().getLabelFont().getSize2D();
		}
		else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			return getCategoryPlot().getDomainAxis().getLabelFont().getSize2D();
		}
		return getChartPanel().getChart().getPlot().getNoDataMessageFont().getSize2D();
	}

	/* Experimental: Not all types of data are supported */
	protected void exportAsCSV(final File file) throws IllegalStateException {
		// https://stackoverflow.com/a/58530238
		final ArrayList<String> csv = new ArrayList<>();
		if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
			final Dataset dataset = getXYPlot().getDataset();
			final XYDataset xyDataset = (XYDataset) dataset;
			final int seriesCount = xyDataset.getSeriesCount();
			for (int i = 0; i < seriesCount; i++) {
				final int itemCount = xyDataset.getItemCount(i);
				for (int j = 0; j < itemCount; j++) {
					final Comparable<?> key = xyDataset.getSeriesKey(i);
					final Number x = xyDataset.getX(i, j);
					final Number y = xyDataset.getY(i, j);
					csv.add(String.format("%s, %s, %s", key, x, y));
				}
			}
		} else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
			final Dataset dataset = getCategoryPlot().getDataset();
			final CategoryDataset categoryDataset = (CategoryDataset) dataset;
			final int columnCount = categoryDataset.getColumnCount();
			final int rowCount = categoryDataset.getRowCount();
			for (int i = 0; i < rowCount; i++) {
				for (int j = 0; j < columnCount; j++) {
					final Comparable<?> key1 = categoryDataset.getRowKey(i);
					final Comparable<?> key2 = categoryDataset.getColumnKey(j);
					final Number n = categoryDataset.getValue(i, j);
					csv.add(String.format("%s, %s, %s", key1, key2, n));
				}
			}
		} else {
			throw new IllegalStateException("This type of dataset is not supported.");
		}
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file));) {
			for (final String line : csv) {
				writer.append(line);
				writer.newLine();
			}
		} catch (final IOException e) {
			throw new IllegalStateException("Could not write dataset", e);
		}
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final Tree tree = new SNTService().demoTrees().get(0);
		final TreeStatistics treeStats = new TreeStatistics(tree);
		final SNTChart chart = treeStats.getHistogram("contraction");
		chart.annotatePoint(0.75, 0.15, "No data here", "green");
		chart.annotateXline(0.80, "Start of slope", "blue");
		chart.annotateYline(0.05, "5% mark", "red");
		chart.annotate("Annotation");
		chart.setFontSize(18);
		chart.show();
	}

}
