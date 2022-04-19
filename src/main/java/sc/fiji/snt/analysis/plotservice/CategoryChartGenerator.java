/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt.analysis.plotservice;

import org.scijava.plot.*;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.*;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset;
import org.scijava.ui.awt.AWTColors;
import org.scijava.util.ColorRGB;

import static sc.fiji.snt.analysis.plotservice.Utils.*;

import java.util.*;


/**
 * @author Matthias Arzt
 * @author Tiago Ferreira
 */
class CategoryChartGenerator {

	private final CategoryChart chart;

	private final SortedLabelFactory labelFactory = new SortedLabelFactory();

	private final CategoryPlot jfcPlot = new CategoryPlot();

	private final LineAndBarDataset lineData;

	private final LineAndBarDataset barData;

	private final BoxDataset boxData;

	private CategoryChartGenerator(CategoryChart chart) {
		this.chart = chart;
		List<SortedLabel> categoryList = setupCategoryList();
		lineData = new LineAndBarDataset(new LineAndShapeRenderer(), categoryList);
		barData = new LineAndBarDataset(createFlatBarRenderer(), categoryList);
		boxData = new BoxDataset(categoryList);
	}

	static JFreeChart run(CategoryChart chart) {
		return new CategoryChartGenerator(chart).getJFreeChart();
	}

	private List<SortedLabel> setupCategoryList() {
		List<Object> categories = chart.getCategories();
		List<SortedLabel> categoryList = new ArrayList<>(categories.size());
		SortedLabelFactory categoryFactory = new SortedLabelFactory();
		for(Object category : categories)
			categoryList.add(categoryFactory.newLabel(category));
		return categoryList;
	}

	private JFreeChart getJFreeChart() {
		jfcPlot.setDomainAxis(new CategoryAxis(chart.categoryAxis().getLabel()));
		jfcPlot.getDomainAxis().setCategoryLabelPositions(CategoryLabelPositions.UP_45);
		jfcPlot.setRangeAxis(getJFreeChartAxis(chart.numberAxis()));
		processAllSeries();
		lineData.addDatasetToPlot(0);
		boxData.addDatasetToPlot(1);
		barData.addDatasetToPlot(2);
		return Utils.setupJFreeChart(chart.getTitle(), jfcPlot);
	}

	static private BarRenderer createFlatBarRenderer() {
		BarRenderer jfcBarRenderer = new BarRenderer();
		jfcBarRenderer.setBarPainter(new StandardBarPainter());
		jfcBarRenderer.setShadowVisible(false);
		return jfcBarRenderer;
	}

	private void processAllSeries() {
		for(CategoryChartItem series : chart.getItems()) {
			if(series instanceof BarSeries)
				barData.addSeries((BarSeries) series);
			if(series instanceof LineSeries)
				lineData.addSeries((LineSeries) series);
			if(series instanceof BoxSeries)
				boxData.addBoxSeries((BoxSeries) series);
		}
	}

	private class BoxDataset {

		private final DefaultBoxAndWhiskerCategoryDataset jfcDataset;

		private final BoxAndWhiskerRenderer jfcRenderer;

		private final List<SortedLabel> categoryList;

		BoxDataset(List<SortedLabel> categoryList) {
			jfcDataset = new DefaultBoxAndWhiskerCategoryDataset();
			jfcRenderer = new BoxAndWhiskerRenderer();
			jfcRenderer.setFillBox(false);
			this.categoryList = categoryList;
			setCategories();
		}

		void addBoxSeries(BoxSeries series) {
			SortedLabel uniqueLabel = labelFactory.newLabel(series.getLabel());
			setSeriesData(uniqueLabel, series.getValues());
			setSeriesVisibility(uniqueLabel, true, series.getLegendVisible());
			setSeriesColor(uniqueLabel, series.getColor());
		}


		private void setCategories() {
			SortedLabel uniqueLabel = labelFactory.newLabel("dummy");
			for(SortedLabel category : categoryList)
				jfcDataset.add(Collections.emptyList(), uniqueLabel, category);
			setSeriesVisibility(uniqueLabel, false, false);
		}

		private void setSeriesData(SortedLabel uniqueLabel, Map<?, ? extends Collection<Double>> data) {
			for(SortedLabel category : categoryList) {
				Collection<Double> value = data.get(category.getLabel());
				if(value != null)
					jfcDataset.add(new ArrayList<>(value), uniqueLabel, category);
			}
		}

		private void setSeriesColor(SortedLabel uniqueLabel, ColorRGB color) {
			if(color == null)
				return;
			int index = jfcDataset.getRowIndex(uniqueLabel);
			if(index < 0)
				return;
			jfcRenderer.setSeriesPaint(index, AWTColors.getColor(color));
		}

		private void setSeriesVisibility(SortedLabel uniqueLabel, boolean seriesVsisible, boolean legendVisible) {
			int index = jfcDataset.getRowIndex(uniqueLabel);
			if(index < 0)
				return;
			jfcRenderer.setSeriesVisible(index, seriesVsisible, false);
			jfcRenderer.setSeriesVisibleInLegend(index, legendVisible, false);
		}


		void addDatasetToPlot(int datasetIndex) {
			jfcPlot.setDataset(datasetIndex, jfcDataset);
			jfcPlot.setRenderer(datasetIndex, jfcRenderer);
		}

	}


	private class LineAndBarDataset {

		private final DefaultCategoryDataset jfcDataset;

		private final AbstractCategoryItemRenderer jfcRenderer;

		private final List<SortedLabel> categoryList;

		LineAndBarDataset(AbstractCategoryItemRenderer renderer, List<SortedLabel> categoryList) {
			jfcDataset = new DefaultCategoryDataset();
			jfcRenderer = renderer;
			this.categoryList = categoryList;
			setCategories();
		}

		private void setCategories() {
			SortedLabel uniqueLabel = labelFactory.newLabel("dummy");
			for(SortedLabel category : categoryList)
				jfcDataset.addValue(0.0, uniqueLabel, category);
			setSeriesVisibility(uniqueLabel, false, false);
		}

		void addSeries(BarSeries series) {
			SortedLabel uniqueLabel = labelFactory.newLabel(series.getLabel());
			addSeriesData(uniqueLabel, series.getValues());
			setSeriesColor(uniqueLabel, series.getColor());
			setSeriesVisibility(uniqueLabel, true, series.getLegendVisible());
		}

		void addSeries(LineSeries series) {
			SortedLabel uniqueLabel = labelFactory.newLabel(series.getLabel());
			addSeriesData(uniqueLabel, series.getValues());
			setSeriesStyle(uniqueLabel, series.getStyle());
			setSeriesVisibility(uniqueLabel, true, series.getLegendVisible());
		}

		private void setSeriesVisibility(SortedLabel uniqueLabel, boolean seriesVsisible, boolean legendVisible) {
			int index = jfcDataset.getRowIndex(uniqueLabel);
			if(index < 0)
				return;
			jfcRenderer.setSeriesVisible(index, seriesVsisible, false);
			jfcRenderer.setSeriesVisibleInLegend(index, legendVisible, false);
		}

		private void addSeriesData(SortedLabel uniqueLabel, Map<?, Double> values) {
			for(SortedLabel category : categoryList) {
				Double value = values.get(category.getLabel());
				if(value != null)
					jfcDataset.addValue(value, uniqueLabel, category);
			}
		}

		private void setSeriesStyle(SortedLabel uniqueLabel, SeriesStyle style) {
			if(style == null)
				return;
			int index = jfcDataset.getRowIndex(uniqueLabel);
			if(index < 0)
				return;
			RendererModifier.wrap(jfcRenderer).setSeriesStyle(index, style);
		}

		private void setSeriesColor(SortedLabel uniqueLabel, ColorRGB style) {
			if(style == null)
				return;
			int index = jfcDataset.getRowIndex(uniqueLabel);
			if(index < 0)
				return;
			RendererModifier.wrap(jfcRenderer).setSeriesColor(index, style);
		}

		void addDatasetToPlot(int datasetIndex) {
			jfcPlot.setDataset(datasetIndex, jfcDataset);
			jfcPlot.setRenderer(datasetIndex, jfcRenderer);
		}

	}

}
