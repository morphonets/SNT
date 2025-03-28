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
package sc.fiji.snt.analysis.sholl.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.measure.Measurements;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.util.Tools;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.math.ShollStats;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.ShollPoint;

/**
 * @author Tiago Ferreira
 *
 */
public class ShollPlot extends Plot {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	/** Default colors for plotting sampled data */
	private final Color SDATA_COLOR = Color.GRAY;
	private final Color SDATA_ANNOT_COLOR = Color.LIGHT_GRAY;

	/** Default colors for plotting fitted data */
	private final Color FDATA_COLOR1 = Color.BLUE;
	private final Color FDATA_ANNOT_COLOR1 = new Color(0, 120, 255);
	// private final Color FDATA_COLOR2 = Color.RED;
	// private final Color FDATA_ANNOT_COLOR2 = new Color(260, 160, 0);

	/** Flag for plotting points with a thicker solid line */
	public static final int THICK_LINE = -1;

	private final static int DEF_FLAGS = X_FORCE2GRID + X_TICKS + X_NUMBERS + Y_FORCE2GRID + Y_TICKS + Y_NUMBERS;
	private final static double[] DUMMY_VALUES = null;

	private boolean annotate;
	private boolean preferCumulativeFrequencies;
	private ShollStats stats;
	private LinearProfileStats linearStats;
	private NormalizedProfileStats normStats;
	private StringBuffer tempLegend;
	private double xMin, xMax, yMin, yMax;

	/**
	 * Constructs a new empty Plot.
	 * 
	 * @param title  the plot title
	 * @param xLabel the x-axis label
	 * @param yLabel the y-axis label
	 */
	public ShollPlot(String title, String xLabel, String yLabel) {
		super(title, xLabel, yLabel);
	}

	public ShollPlot(final Profile profile) {
		this(new LinearProfileStats(profile), false);
	}

	public ShollPlot(final ShollStats stats) {
		this(stats, false);
	}

	public ShollPlot(final ShollStats stats, final boolean cumulativeFrequencies) {
		this(defaultTitle(stats), defaultXtitle(stats), defaultYtitle(stats), stats, true, cumulativeFrequencies);
	}

	public ShollPlot(final Profile... profiles) {
		super("Combined Sholl Plot", "Distance", "No. Intersections");
		final Color[] colors = SNTColor.getDistinctColorsAWT(profiles.length);
		final StringBuilder legend = new StringBuilder();
		setLineWidth(1.75f);
		for (int i = 0; i < profiles.length; i++) {
			final Profile p = profiles[i];
			setColor(colors[i]);
			addPoints(p.radii(), p.counts(), LINE);
			legend.append(p.identifier()).append("\n");
		}
		setLimitsToFit(false);
		setColor(Color.WHITE);
		setLegend(legend.toString(), AUTO_POSITION | LEGEND_TRANSPARENT);
		resetDrawing();
	}

	@SuppressWarnings("deprecation")
	public ShollPlot(final String title, final String xLabel, final String yLabel, final ShollStats stats,
			final boolean annotate, final boolean useCumulativeFrequencies) {

		// initialize empty plot, so that sampled data can be plotted with a
		// custom shape, otherwise the default Plot.Line would be used
		super(title, xLabel, (useCumulativeFrequencies) ? "Cumulative Frequency" : yLabel, DUMMY_VALUES, DUMMY_VALUES, DEF_FLAGS);
		this.stats = stats;
        switch (stats) {
            case null -> throw new IllegalArgumentException("Stats instance cannot be null");
            case LinearProfileStats linearProfileStats -> {
                linearStats = linearProfileStats;
                normStats = null;
            }
            case NormalizedProfileStats normalizedProfileStats -> {
                normStats = normalizedProfileStats;
                linearStats = null;
            }
            default -> throw new IllegalArgumentException("Unrecognized ShollStats implementation");
        }

        this.annotate = annotate;
		preferCumulativeFrequencies = useCumulativeFrequencies;
		tempLegend = new StringBuffer();

		// Set plot limits without grid lines
		final double[] xValues = stats.getXvalues();
		final double[] yValues = stats.getYvalues(preferCumulativeFrequencies);
		xMin = StatUtils.min(xValues);
		xMax = StatUtils.max(xValues);
		yMin = StatUtils.min(yValues);
		yMax = StatUtils.max(yValues);
		final boolean gridState = PlotWindow.noGridLines;
		PlotWindow.noGridLines = false;
		// Axis Limits cannot be the same: if there are no
		// differences between axes' min & max, the constructor
		// will hang for several minutes trying to optimize boundaries
		if (xMin == xMax) {xMin--; xMax++;}
		if (yMin == yMax) {yMin--; yMax++;}
		setLimits(xMin, xMax, yMin, yMax);
		PlotWindow.noGridLines = gridState;

		// Add sampled data
		setColor(SDATA_COLOR);
		addPoints(xValues, yValues, Plot.CROSS);
		if (linearStats != null)
			annotateLinearProfile(false);

		// Add fitted data
		setColor(FDATA_COLOR1);
		if (linearStats != null && linearStats.validFit()) {
			addPoints(linearStats.getXvalues(), linearStats.getFitYvalues(preferCumulativeFrequencies), THICK_LINE);
			annotateLinearProfile(!preferCumulativeFrequencies);
		}
		if (normStats != null && normStats.validFit()) {
			final SimpleRegression reg = normStats.getRegression();
			final double y1 = reg.predict(xMin);
			final double y2 = reg.predict(xMax);

			// Plot regression: NB: with drawLine(x1, y1, x2, y2); line
			// will not have a label and will not be listed on plot's table
			addPoints(new double[] { xMin, xMax }, new double[] { y1, y2 }, THICK_LINE);
			annotateNormalizedProfile(reg);
		}

		// Append finalized legend
		final int flagPos = (annotate) ? AUTO_POSITION | LEGEND_TRANSPARENT : 0;
		final StringBuilder finalLegend = new StringBuilder("Sampled data\n");
		finalLegend.append(tempLegend);
		setLineWidth(1);
		setColor(Color.WHITE);
		setLegend(finalLegend.toString(), flagPos);
		updateImage();
		resetDrawing();
	}

	public void rebuild() {
		rebuild(stats);
	}

	public void rebuild(final ShollStats stats) {
		final PlotWindow pw = (PlotWindow) getImagePlus().getWindow();
		if (pw == null || !pw.isVisible())
			return;
		if (isFrozen())
			return;
		final ShollPlot newPlot = new ShollPlot(defaultTitle(stats), defaultXtitle(stats), defaultYtitle(stats), stats,
				annotate, preferCumulativeFrequencies);
		String title = pw.getTitle();
		if (title != null && title.contains(" (")) {
			String statsLabel = "";
			if (stats instanceof LinearProfileStats) {
				statsLabel = "(Linear)";
			} else if (stats instanceof NormalizedProfileStats) {
				statsLabel = "("+ ((NormalizedProfileStats) stats).getMethodDescription() +")";
			}
			title = title.replaceAll("\\(.*\\)", statsLabel);
			pw.setTitle(title);
		}
		pw.drawPlot(newPlot);
	}

	public void enableLegend(final boolean enable) {
		final StringBuilder sb = new StringBuilder();
		for (final String d : getDataObjectDesignations()) {
			if (d == null || d.trim().isEmpty())
				continue;
			if (!d.endsWith(" data points)") && d.contains(": ")) {
				sb.append(d.substring(d.indexOf(": ") + 2)).append("\n");
			} else {
				sb.append(d).append("\n");
			}
		}
		setColor(Color.WHITE);
		setLegend(sb.toString(), (enable) ? LEGEND_TRANSPARENT + AUTO_POSITION : 0);
		resetDrawing();
	}

	private static String defaultTitle(final ShollStats stats) {
		String plotTitle = "Sholl Profile";
		if (stats.getProfile().isIntDensityProfile())
			plotTitle += " (IntDen)";
		else if (stats instanceof LinearProfileStats)
			plotTitle += " (Linear)";
		else if (stats instanceof NormalizedProfileStats)
			plotTitle += " ("+ ((NormalizedProfileStats) stats).getMethodDescription() +")";
		final String identifier = stats.getProfile().identifier();
		if (identifier != null && !identifier.isEmpty())
			plotTitle += " for "+ identifier;
		return WindowManager.getUniqueName(plotTitle); //displayService.isUniqueName(plotTitle);
	}

	private static String defaultXtitle(final Profile profile) {
		final StringBuilder sb = new StringBuilder();
		if (profile.is2D())
			sb.append("2D ");
		sb.append("Distance");
		final ShollPoint center = profile.center();
		if (center != null)
			sb.append(" from ").append(center.toString());
		if (profile.scaled())
			sb.append(" (").append(profile.spatialCalibration().getUnit()).append(")");
		return sb.toString();
	}

	private static String defaultXtitle(final ShollStats stats) {
		if (stats instanceof NormalizedProfileStats
				&& (((NormalizedProfileStats) stats)).getMethod() == ShollStats.LOG_LOG) {
			return "log[ " + defaultXtitle(stats.getProfile()) + " ]";
		}
		return defaultXtitle(stats.getProfile());
	}

	private static String defaultYtitle(final ShollStats stats) {
		final boolean intensities = stats.getProfile().isIntDensityProfile();
		if (stats instanceof NormalizedProfileStats) {
			final int normMethod = (((NormalizedProfileStats) stats)).getMethod();
            return switch (normMethod) {
                case ShollStats.ANNULUS -> (intensities) ? "log(N. Int. Dens./Annulus)" : "log(No. Inters./Annulus)";
                case ShollStats.AREA -> (intensities) ? "log(N. Int. Dens./Area)" : "log(No. Inters./Area)";
                case ShollStats.PERIMETER ->
                        (intensities) ? "log(N. Int. Dens./Perimeter)" : "log(No. Inters./Perimeter)";
                case ShollStats.S_SHELL ->
                        (intensities) ? "log(N. Int. Dens./Spherical Shell)" : "log(No. Inters./Spherical Shell)";
                case ShollStats.SURFACE -> (intensities) ? "log(N. Int. Dens./Surface)" : "log(No. Inters./Surface)";
                case ShollStats.VOLUME -> (intensities) ? "log(N. Int. Dens./Volume)" : "log(No. Inters./Volume)";
                default -> "Normalized Inters.";
            };
		}
		return (stats.getProfile().isIntDensityProfile()) ? "Norm. Integrated Density" : "No. Intersections";
	}

	private void drawDottedLine(final double x1, final double y1, final double x2, final double y2) {
		final int DASH_STEP = 4;
		drawDottedLine(x1, y1, x2, y2, DASH_STEP);
	}

	private void annotateNormalizedProfile(final SimpleRegression regression) {
		if (!annotate || regression == null)
			return;

		// mark slope
		final double xCenter = (xMin + xMax) / 2;
		final double ySlope = regression.predict(xCenter);
		drawDottedLine(xMin, ySlope, xCenter, ySlope);
		drawDottedLine(xCenter, yMin, xCenter, ySlope);

		// mark intercept
		if (regression.hasIntercept())
			markPoint(new ShollPoint(0, regression.getIntercept()), DOT, 8);

		// assemble legend
		final double rsqred = regression.getRSquare();
		final double k = -regression.getSlope();
		tempLegend.append("k= ").append(ShollUtils.d2s(k));
		tempLegend.append(" (R\u00B2= ").append(ShollUtils.d2s(rsqred)).append(")\n");
	}

	private void annotateLinearProfile(final boolean fittedData) {

		if (!annotate || linearStats == null)
			return;

		final ShollPoint centroid = linearStats.getCentroid(fittedData);
		final ShollPoint pCentroid = linearStats.getPolygonCentroid(fittedData);
		final ShollPoint max = linearStats.getCenteredMaximum(fittedData);
		final double primary = linearStats.getPrimaryBranches(fittedData);
		final double mv;
		Color color;
		if (fittedData) {
			mv = linearStats.getMeanValueOfPolynomialFit(xMin, xMax);
			color = FDATA_ANNOT_COLOR1;
		} else {
			mv = centroid.y;
			color = SDATA_ANNOT_COLOR;
		}

		setLineWidth(1);
		setColor(color);

		// highlight centroids
		markPoint(pCentroid, CROSS, 8);
		setColor(color);
		drawDottedLine(xMin, centroid.y, centroid.x, centroid.y);
		drawDottedLine(centroid.x, yMin, centroid.x, centroid.y);

		// highlight mv
		if (fittedData && mv != centroid.y)
			drawDottedLine(xMin, mv, centroid.x, mv);

		// highlight primary branches
		drawDottedLine(xMin, primary, max.x, primary);

		// highlight max
		drawDottedLine(xMin, max.y, max.x, max.y);
		drawDottedLine(max.x, yMin, max.x, max.y);

		// build label
		if (fittedData) {
			final double rsqred = linearStats.getRSquaredOfFit(true);
			final String polyType = linearStats.getPolynomialAsString();
			tempLegend.append(polyType).append(" fit (");
			tempLegend.append("R\u00B2= ").append(ShollUtils.d2s(rsqred)).append(")\n");
		}

	}

	/**
	 * Highlights a point on a plot without listing it on the Plot's table. Does
	 * nothing if point is {@code null}
	 *
	 * @param pCentroid
	 *            the point to be drawn (defined in calibrated coordinates)
	 * @param markShape
	 *            either X, CROSS or DOT. Other shapes are not supported.
	 * @param markSize
	 *            the mark size in pixels
	 */
	public void markPoint(final ShollPoint pCentroid, final int markShape, final int markSize) {
		if (pCentroid == null)
			return;

		final double x = pCentroid.x;
		final double y = pCentroid.y;
		final double xStart = descaleX((int) (scaleXtoPxl(x) - (markSize / 2) + 0.5));
		final double yStart = descaleY((int) (scaleYtoPxl(y) - (markSize / 2) + 0.5));
		final double xEnd = descaleX((int) (scaleXtoPxl(x) + (markSize / 2) + 0.5));
		final double yEnd = descaleY((int) (scaleYtoPxl(y) + (markSize / 2) + 0.5));

		draw();
		switch (markShape) {
		case X:
			drawLine(xStart, yStart, xEnd, yEnd);
			drawLine(xEnd, yStart, xStart, yEnd);
			break;
		case CROSS:
			drawLine(xStart, y, xEnd, y);
			drawLine(x, yStart, x, yEnd);
			break;
		case DOT:
			setLineWidth(markSize);
			drawLine(x, y, x, y);
			setLineWidth(1);
			break;
		case BOX:
			drawLine(xStart, yStart, xEnd, yStart);
			drawLine(xEnd, yStart, xEnd, yEnd);
			drawLine(xEnd, yEnd, xStart, yEnd);
			drawLine(xStart, yEnd, xStart, yStart);
			break;
		default:
			throw new IllegalArgumentException("Currently only the shapes BOX, CROSS, DOT, X are supported");
		}
	}

	/**
	 * Highlights a point on a plot using the default marker. Does nothing if
	 * point is null.
	 *
	 * @param point the point to be drawn (defined in calibrated coordinates).
	 * @param color the drawing color. This will not affect consequent objects
	 */
	public void markPoint(final ShollPoint point, final Color color) {
		if (point != null) {
			setColor(color);
			markPoint(point, CROSS, 8);
			resetDrawing();
		}
	}

	/**
	 * Draws a label at the less crowded corner of an ImageJ plot. Height and
	 * width of label is measured so that text remains within the plot's frame.
	 * Text is added to the first free position in this sequence: NE, NW, SE,
	 * SW.
	 *
	 * @param label
	 *            Label contents
	 * @param color
	 *            Foreground color of text. Note that this will also set the
	 *            drawing color for the next objects to be added to the plot
	 */
	public void drawLabel(final String label, final Color color) {

		final ImageProcessor ip = getProcessor();

		int maxLength = 0;
		String maxLine = "";
		final String[] lines = Tools.split(label, "\n");
        for (final String line : lines) {
            final int length = line.length();
            if (length > maxLength) {
                maxLength = length;
                maxLine = line;
            }
        }

		final Font font = new Font("Helvetica", Font.PLAIN, PlotWindow.fontSize);
		ip.setFont(font);
		setFont(font);
		final FontMetrics metrics = ip.getFontMetrics();
		final int textWidth = metrics.stringWidth(maxLine);
		final int textHeight = metrics.getHeight() * lines.length;

		final Rectangle r = getDrawingFrame();
		final int padding = 4; // space between label and axes
		final int yTop = r.y + 1 + padding + metrics.getHeight(); // FIXME:
																	// Since
																	// 1.51n
																	// top-padding
																	// is offset
																	// by 1line
		final int yBottom = r.y + r.height - textHeight - padding;
		final int xLeft = r.x + 1 + padding;
		final int xRight = r.x + r.width - textWidth - padding;

		final double northEast = meanRoiValue(ip, xLeft, yTop, textWidth, textHeight);
		final double northWest = meanRoiValue(ip, xRight, yTop, textWidth, textHeight);
		final double southEast = meanRoiValue(ip, xLeft, yBottom, textWidth, textHeight);
		final double southWest = meanRoiValue(ip, xRight, yBottom, textWidth, textHeight);
		final double pos = Math.max(Math.max(northEast, northWest), Math.max(southEast, southWest));

		ip.setColor(0);
		setColor(color);
		// We'll use the ImageProcessor and PlotObjects so that we can 'brand'
		// the pizmultiple labels can be added without
		// overlap
		if (pos == northEast) {
			ip.drawString(label, xLeft, yTop);
			addText(label, descaleX(xLeft), descaleY(yTop));
		} else if (pos == northWest) {
			ip.drawString(label, xRight, yTop);
			addText(label, descaleX(xRight), descaleY(yTop));
		} else if (pos == southEast) {
			ip.drawString(label, xLeft, yBottom);
			addText(label, descaleX(xLeft), descaleY(yBottom));
		} else {
			ip.drawString(label, xRight, yBottom);
			addText(label, descaleX(xRight), descaleY(yBottom));
		}
		resetDrawing();
	}

	public boolean isVisible() {
		final PlotWindow pw = (PlotWindow) getImagePlus().getWindow();
		return (pw != null && pw.isVisible());
	}

	public boolean isUsingCumulativeFrequencies() {
		return preferCumulativeFrequencies;
	}

	public boolean save(String filepath) {
		return save(new File(filepath));
	}

	public boolean save(final File file) {
		if (file == null) return false;
		final File outFile = (file.isDirectory())
				? SNTUtils.getUniquelySuffixedTifFile(new File(file, getImagePlus().getTitle()))
				: file;
		return IJ.saveAsTiff(getImagePlus(), outFile.getAbsolutePath());
	}

	public ShollStats getStats() {
		return stats;
	}

	/** Returns the mean value of a rectangular ROI */
	private double meanRoiValue(final ImageProcessor ip, final int x, final int y, final int width, final int height) {
		ip.setRoi(x, y, width, height);
		return ImageStatistics.getStatistics(ip, Measurements.MEAN, null).mean;
	}

	@Override
	public void addPoints(final double[] x, final double[] y, final int shape) {
		if (shape == THICK_LINE) {
			setLineWidth(2);
			super.addPoints(x, y, LINE);
			setLineWidth(1);
		} else {
			super.addPoints(x, y, shape);
		}
	}

	public void addPoints(final double[] x, final double[] y, final double[] yErrorBars, final String label) {
		addPoints(Tools.toFloat(x), Tools.toFloat(y), Tools.toFloat(yErrorBars),
				(yErrorBars == null) ? LINE : CONNECTED_CIRCLES, label);
	}

	public void addPoints(final double[] x, final double[] y, final String label) {
		addPoints(x, y, null, label);
	}

	public void addPoints(List<double[]> points, final String label) {
		final float[] x = new float[points.size()];
		final float[] y = new float[points.size()];
		int idx = 0;
		for (double[] point : points) {
			x[idx] = (float)point[0];
			y[idx++] = (float)point[1];
		}
		setLineWidth(8);
		addPoints(x, y, null, CIRCLE, label);
		setLineWidth(1);
	}

	public void addPoints(final List<Number> xValues, final List<Number> yValues, final String label) {
		addPoints(xValues, yValues, null, label);
		this.addPoints(DUMMY_VALUES, DUMMY_VALUES, DUMMY_VALUES, label);
	}

	public void addPoints(final List<Number> xValues, final List<Number> yValues, final List<Number> yErrorBars,
			final String label) {
		super.addPoints(toArray(xValues), toArray(yValues), toArray(yErrorBars),
				(yErrorBars == null) ? LINE : CONNECTED_CIRCLES, label);
	}

	private float[] toArray(final List<Number> values) {
		if (values == null)
			return null;
		final float[] fArray = new float[values.size()];
		for (int i = 0; i < values.size(); i++)
			fArray[i] = values.get(i).floatValue();
		return fArray;
	}

	private void resetDrawing() {
		setLineWidth(1);
		setColor(Color.BLACK);
	}

	public static void show(final List<ShollPlot> plots, final String title) {
		if (plots == null || plots.isEmpty())
			return;
		if (plots.size() == 1) {
			plots.getFirst().show();
		} else {
			ShollPlot ref = plots.getFirst();
			for (int i = 1; i < plots.size() ; i++) {
				ref.addObjectFromPlot(plots.get(i), i);
			}
		//	plots.sort(Comparator.comparing(Plot::getTitle));
			final List<ImagePlus> imps = new ArrayList<>();
			plots.forEach(p -> imps.add(p.getImagePlus()));
			ImagePlus res = ImpUtils.toStack(imps);
			res.setTitle(title);
			res.show();
		}
	}
}
