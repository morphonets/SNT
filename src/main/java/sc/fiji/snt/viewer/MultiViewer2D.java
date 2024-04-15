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

package sc.fiji.snt.viewer;

import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JFrame;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.CombinedRangeXYPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.PaintScaleLegend;
import org.jfree.chart.ui.RectangleEdge;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.parsers.TreeParser;

/**
 * Class for rendering {@link Tree}s as 2D plots that can be exported as SVG,
 * PNG or PDF.
 *
 * @author Tiago Ferreira
 */
public class MultiViewer2D {

	private final List<Viewer2D> viewers;
	private List<ChartPanel> rowPanels;
	private int gridCols;
	private Viewer2D colorLegendViewer;
	private PaintScaleLegend legend;
	private JFrame frame;
	private boolean gridVisible;
	private boolean axesVisible;
	private boolean outlineVisible;
	private double legendMin = Double.MAX_VALUE;
	private double legendMax = Double.MIN_VALUE;
	private String label;

	public MultiViewer2D(final List<Viewer2D> viewers) {
		if (viewers == null)
			throw new IllegalArgumentException("Cannot instantiate a grid from a null list of viewers");
		this.viewers = viewers;
		guessLayout(true);
		setAxesVisible(true);
		setGridlinesVisible(false);
		setOutlineVisible(true);
	}

	public MultiViewer2D(final Collection<Tree> trees) {
		viewers = new ArrayList<>();
		trees.forEach(tree -> {
			final Viewer2D v = new Viewer2D();
			v.add(tree);
			if (tree.getLabel() != null) {
				v.setTitle(tree.getLabel());
				v.getChart().annotate(tree.getLabel());
			
			}
			viewers.add(v);
		});
	
		guessLayout(true);
		setAxesVisible(true);
		setGridlinesVisible(false);
		setOutlineVisible(true);
	}

	private void guessLayout(final boolean padGridWithEmptyViewers) {
		gridCols = (viewers.size() < 11) ? viewers.size() : viewers.size() / 2;
		for (int i =0; i < viewers.size() % gridCols ; i++) {
			final Viewer2D dummy = new Viewer2D();
			final Tree tree = new Tree();
			tree.add(new Path());
			dummy.add(tree);
			dummy.setTitle("");
			viewers.add(dummy);
		}
	}

	public void setLayoutColumns(final int cols) {
		if (cols <= 0) {
			guessLayout(true);
		} else {
			gridCols = Math.min(cols, viewers.size());
		}
	}

	public void setGridlinesVisible(final boolean visible) {
		gridVisible = visible;
	}

	public void setAxesVisible(final boolean visible) {
		axesVisible = visible;
	}

	public void setOutlineVisible(final boolean visible) {
		outlineVisible = visible;
	}

	public void setColorBarLegend(final String lut, final double min, final double max) {
		final TreeColorMapper lutRetriever = new TreeColorMapper(SNTUtils.getContext());
		final ColorTable colorTable = lutRetriever.getColorTable(lut);
		setColorBarLegend(colorTable, min, max);
	}

	public void setColorBarLegend(final ColorTable colorTable, final double min, final double max) {
		if (colorTable == null || viewers == null) {
			throw new IllegalArgumentException("Cannot set legend from null viewers or null colorTable");
		}
		if (min >=max) {
			legendMin = Double.MAX_VALUE;
			legendMax = Double.MIN_VALUE;
			for (Viewer2D viewer: viewers) {
				final double[] minMax = viewer.getMinMax();
				legendMin = Math.min(minMax[0], legendMin);
				legendMax = Math.max(minMax[1], legendMax);
			}
			if (min >= max) return; //range determination failed. Do not add legend
		} else {
			legendMin = min;
			legendMax = max;
		}
		colorLegendViewer = viewers.get(viewers.size() - 1);
		legend = colorLegendViewer.getPaintScaleLegend(colorTable, legendMin, legendMax);
	}

	public void save(final String filePath) {
		final File f = new File(filePath);
		f.mkdirs();
		if (rowPanels == null) {
			// assemble rowPanels;
			frame = getJFrame();
		}
		int i = 1;
		for (final ChartPanel cPanel : rowPanels) {
			try {
				final OutputStream out = Files.newOutputStream(Paths.get(filePath + "-" + i + ".png"));
				ChartUtils.writeChartAsPNG(out, cPanel.getChart(), cPanel.getWidth(), cPanel.getHeight());
				i++;
			} catch (final IOException ex) {
				ex.printStackTrace();
			}
		}
	}

	public void setLabel(final String label) {
		this.label = label;
	}

	public JFrame show() {
		frame = getJFrame();
		frame.setTitle((label==null)?"Multi-Pane Reconstruction Plotter":label);
		frame.setVisible(true);
		return frame;
	}

	private JFrame getJFrame() {
		// make all plots the same size
		int w = Integer.MIN_VALUE;
		int h = Integer.MIN_VALUE;
		for (final Viewer2D viewer : viewers) {
			if (viewer.plot == null)
				continue;
			if (viewer.plot.getPreferredWidth() > w)
				w = viewer.plot.getPreferredWidth();
			if (viewer.plot.getPreferredHeight() > h)
				h = viewer.plot.getPreferredHeight();
		}
		for (final Viewer2D viewer : viewers)
			viewer.setPreferredSize(w, h);

		final List<List<Viewer2D>> rows = new ArrayList<>();
		rowPanels = new ArrayList<>();
		for (int i = 0; i < viewers.size(); i += gridCols) {
			rows.add(viewers.subList(i, Math.min(i + gridCols, viewers.size())));
		}
		final JFrame frame = new JFrame();
		final GridLayout gridLayout = new GridLayout(rows.size(), 1);
		frame.setLayout(gridLayout);
		for (final List<Viewer2D> row : rows) {
			final SNTChart rowChart = getMergedChart(row, "col");
			final ChartPanel cPanel = rowChart;
			frame.add(cPanel);
			rowPanels.add(cPanel);
		}
		frame.pack();
		return frame;
	}

	private String getTitlesAsString(final List<Viewer2D> viewers) {
		if (viewers.stream().allMatch(v -> v.getTitle() != null)) {
			return viewers.stream().map(Viewer2D::getTitle).collect(Collectors.toList()).toString();
		}
		return null;
	}

	private SNTChart getMergedChart(final List<Viewer2D> viewers, final String style) {
		JFreeChart result;
		if (style != null && style.toLowerCase().startsWith("c")) { // column
			final CombinedRangeXYPlot mergedPlot = new CombinedRangeXYPlot();
			for (final Viewer2D viewer : viewers) {
				final XYPlot plot = viewer.getJFreeChart().getXYPlot();
				plot.getDomainAxis().setVisible(axesVisible);
				plot.getRangeAxis().setVisible(axesVisible);
				plot.setDomainGridlinesVisible(gridVisible);
				plot.setRangeGridlinesVisible(gridVisible);
				plot.setOutlineVisible(outlineVisible);
				mergedPlot.add(plot, 1);
			}
			result = new JFreeChart(null, mergedPlot);
		} else {
			final CombinedDomainXYPlot mergedPlot = new CombinedDomainXYPlot();
			for (final Viewer2D viewer : viewers) {
				mergedPlot.add(viewer.getChart().getChart().getXYPlot(), 1);
			}
			result = new JFreeChart(null, mergedPlot);
		}
		if (legend != null && viewers.contains(colorLegendViewer)) {
			if (gridCols >= this.viewers.size()) {
				legend.setPosition(RectangleEdge.RIGHT);
				legend.setMargin(50, 5, 50, 5);
			} else {
				legend.setPosition(RectangleEdge.BOTTOM);
				legend.setMargin(5, 50, 5, 50);
			}
			result.addSubtitle(legend);
		}
		final SNTChart chart = new SNTChart("", result);
		final String legend = getTitlesAsString(viewers);
		if (legend != null)
			chart.annotate(legend);
		return chart;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final List<Tree> trees = new SNTService().demoTrees();
		for (final Tree tree : trees) {
			tree.rotate(Tree.Z_AXIS, 210);
			final PointInImage root = tree.getRoot();
			tree.translate(-root.getX(), -root.getY(), -root.getZ());
		}

		// Color code each cell and assign a hue ramp to the group
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(trees);
		mapper.map("tips", ColorTables.ICE);

		// Assemble a multi-panel Viewer2D from the color mapper
		final MultiViewer2D viewer1 = mapper.getMultiViewer();
		viewer1.setLayoutColumns(0);
		viewer1.setGridlinesVisible(false);
		viewer1.setOutlineVisible(false);
		viewer1.setAxesVisible(false);
		viewer1.show();

		// Sholl mapping //TODO: The API for this feels clunky
		// and everything is really slow. Optimization needed!
		final List<Viewer2D> viewers = new ArrayList<>();
		double min = Double.MAX_VALUE;
		double max = Double.MIN_VALUE;
		for (final Tree tree : trees) {
			final TreeColorMapper tmapper = new TreeColorMapper();
			final TreeParser parser = new TreeParser(tree);
			parser.setCenter(TreeParser.ROOT_NODES_ANY);
			parser.setStepSize(0);
			parser.parse();
			final LinearProfileStats stats = new LinearProfileStats(parser.getProfile());
			min = Math.min(stats.getMin(), min);
			max = Math.max(stats.getMax(), max);
			tmapper.map(tree, stats, ColorTables.CYAN);
			final Viewer2D treeViewer = new Viewer2D(ij.getContext());
			treeViewer.add(tree);
			viewers.add(treeViewer);
		}

		final MultiViewer2D viewer2 = new MultiViewer2D(viewers);
		viewer2.setColorBarLegend(ColorTables.CYAN, min, max);
		viewer2.setLayoutColumns(0);
		viewer2.setGridlinesVisible(false);
		viewer2.setOutlineVisible(false);
		viewer2.setAxesVisible(false);
		viewer2.show();

	}

}
