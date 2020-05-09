/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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
import java.util.List;

import javax.swing.SwingUtilities;

import org.jfree.chart.ChartFrame;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.CategoryTextAnnotation;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.axis.CategoryAnchor;
import org.jfree.chart.plot.CategoryMarker;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.Marker;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.title.Title;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.Range;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.util.ColorRGB;

import sc.fiji.snt.Tree;

/**
 * Extension of {@link ChartFrame} with convenience methods for plot annotations.
 *
 * @author Tiago Ferreira
 */
public class SNTChart extends ChartFrame {

	private static final long serialVersionUID = 5245298401153759551L;
	private static final Color BACKGROUND_COLOR = Color.WHITE;

	protected SNTChart(final String title, final JFreeChart chart) {
		this(title, chart, new Dimension(400, 400));
	}

	public SNTChart(String title, JFreeChart chart, Dimension preferredSize) {
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

	public void annotateXline(final double xValue, final String label) {
		annotateXline(xValue, label, null);
	}

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

	public void annotateYline(final double yValue, final String label) {
		annotateYline(yValue, label, null);
	}

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

	public void annotateCategory(final String category, final String label) {
		annotateCategory(category, label, null);
	}

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
	}

	/**
	 * Sets the font size for this chart.
	 *
	 * @param size  the new font size
	 * @param scope which components should be modified. Either "axes", "legends",
	 *              or "labels".
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
			LegendTitle legend = getChartPanel().getChart().getLegend();
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
		default: // labels
			if (getChartPanel().getChart().getPlot() instanceof XYPlot) {
				Font font = getXYPlot().getDomainAxis().getLabelFont().deriveFont(size);
				getXYPlot().getDomainAxis().setLabelFont(font);
				font = getXYPlot().getRangeAxis().getLabelFont().deriveFont(size);
				getXYPlot().getRangeAxis().setLabelFont(font);
			}
			else if (getChartPanel().getChart().getPlot() instanceof CategoryPlot) {
				Font font = getCategoryPlot().getDomainAxis().getLabelFont().deriveFont(size);
				getCategoryPlot().getDomainAxis().setLabelFont(font);
				font = getCategoryPlot().getRangeAxis().getLabelFont().deriveFont(size);
				getCategoryPlot().getRangeAxis().setLabelFont(font);
			}
			break;
		}
	}

	private Color getColorFromString(final String string) {
		if (string == null) return Color.BLACK;
		final ColorRGB c = new ColorRGB(string);
		return (c==null) ? Color.BLACK : new Color(c.getRed(), c.getGreen(), c.getBlue());
	}

	public void annotate(final String label) {
		final TextTitle tLabel = new TextTitle(label);
		tLabel.setFont(tLabel.getFont().deriveFont(Font.PLAIN));
		tLabel.setPosition(RectangleEdge.BOTTOM);
		getChartPanel().getChart().addSubtitle(tLabel);
	}

	public void annotatePoint(final double x, final double y, final String label) {
		annotatePoint(x,y,label, null);
	}

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

	@Override
	@SuppressWarnings("deprecation")
	public void show() {
		AWTWindows.centerWindow(this);
		SwingUtilities.invokeLater(() -> super.show());
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		final Tree tree = new Tree("/home/tferr/code/test-files/AA0100.swc");
		final TreeStatistics treeStats = new TreeStatistics(tree);
		final SNTChart chart = treeStats.getHistogram("contraction");
		chart.annotatePoint(0.5, 0.15, "No data here", "green");
		chart.annotateXline(0.275, "Start of slope", "blue");
		chart.annotateYline(0.050, "5% mark", "red");
		chart.show();
	}

}
