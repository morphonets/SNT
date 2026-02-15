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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.scijava.Context;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;

import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import net.imglib2.RealLocalizable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;

/**
 * Class for Convex Hull measurements of a {@link Tree}.
 * 
 * @see sc.fiji.snt.plugin.ConvexHullCmd
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class ConvexHullAnalyzer extends ContextCommand {

	public static final String BOUNDARY_SIZE = "Boundary size";
	public static final String BOXIVITY = "Boxivity";
	public static final String ELONGATION = "Elongation";
	public static final String ROUNDNESS = "Roundness";
	public static final String SIZE = "Size";
	public static final String COMPACTNESS_3D = "Compactness";
	public static final String ECCENTRICITY_2D = "Eccentricity";

	@Parameter
	private OpService opService;

	private final Tree tree;
	private AbstractConvexHull hull;
	private Map<String, Double> metrics;
	private String label;

	/**
	 * Instantiates a new convex hull analyzer.
	 *
	 * @param tree the Tree to be analyzed
	 */
	public ConvexHullAnalyzer(final Tree tree) {
		this.tree = tree;
		setLabel(tree.getLabel());
	}

	/**
	 * Instantiates a new convex hull analyzer.
	 *
	 * @param convexHull the Convex hull to be analyzed
	 */
	public ConvexHullAnalyzer(final AbstractConvexHull convexHull) {
		this.tree = null;
		this.hull = convexHull;
		setLabel(convexHull.toString());
		initContext();
	}

	@Override
	public Context context() {
		initContext();
		return super.context();
	}

	@Override
	public Context getContext() {
		return context();
	}

	@Override
	public void run() {
		System.out.println("Convex hull analysis for " + label() + ": ");
		getAnalysis().forEach((key, value) -> System.out.println("\t" + key + ":\t" + value));
	}

	/**
	 * Gets the list of metrics supported by ConvexHullAnalyzer.
	 *
	 * @return the list of supported metric names that can be computed by this analyzer
	 */
	public static List<String> supportedMetrics() {
		return List.of(BOUNDARY_SIZE, BOXIVITY, ELONGATION, ROUNDNESS, SIZE, COMPACTNESS_3D, ECCENTRICITY_2D);
	}

	/**
	 * Gets all computed convex hull analysis metrics.
	 * <p>
	 * Returns a map containing all the computed convex hull metrics and their values.
	 * The metrics are computed lazily and cached for subsequent calls. If the hull
	 * cannot be computed, all metrics return NaN.
	 * </p>
	 *
	 * @return a map of metric names to their computed values
	 */
	public Map<String, Double> getAnalysis() {
		if (metrics != null) {
			return metrics;
		}
		metrics = new LinkedHashMap<>();
		if (isComputable()) {
			initHull();
			metrics.put(BOUNDARY_SIZE, hull.boundarySize());
			metrics.put(BOXIVITY, computeBoxivity(hull));
			metrics.put(ELONGATION, computeElongation(hull));
			metrics.put(ROUNDNESS, computeRoundness(hull));
			metrics.put(SIZE, hull.size());
			metrics.put(COMPACTNESS_3D, getCompactness());
			metrics.put(ECCENTRICITY_2D, getEccentricity());
		} else {
			metrics.put(BOUNDARY_SIZE, Double.NaN);
			metrics.put(BOXIVITY, Double.NaN);
			metrics.put(ELONGATION, Double.NaN);
			metrics.put(ROUNDNESS, Double.NaN);
			metrics.put(SIZE, Double.NaN);
			metrics.put(COMPACTNESS_3D, Double.NaN);
			metrics.put(ECCENTRICITY_2D, Double.NaN);
		}
		return metrics;
	}

	public void dump(final SNTTable table) {
		if (table.getRowIndex(label()) < 0) table.insertRow(label());
		try {
			getAnalysis().forEach((k, v) -> table.appendToLastRow("Convex hull: " + k, v));
		} catch (final IllegalArgumentException ex) {
			getContext().getService(LogService.class).warn(ex);
		}
	}

	/**
	 * Gets the convex hull object being analyzed. The hull is initialized if needed.
	 *
	 * @return the convex hull object
	 */
	public AbstractConvexHull getHull() {
		initHull();
		return hull;
	}

	/**
	 * Gets the value of a specific convex hull metric.
	 * <p>
	 * Retrieves the computed value for the specified metric name. The metric
	 * must be one of the supported metrics returned by {@link #supportedMetrics()}.
	 * </p>
	 *
	 * @param metric the name of the metric to retrieve
	 * @return the computed value for the metric
	 * @see #supportedMetrics()
	 */
	public double get(final String metric) {
		return getAnalysis().get(metric);
	}

	public PointInImage getCentroid() {
		initHull();
		final RealLocalizable cntd = computeCentroid(hull);
		return new PointInImage(cntd.getDoublePosition(0), cntd.getDoublePosition(1),
				(hull instanceof ConvexHull3D) ? cntd.getDoublePosition(2) : 0);
	}

	/**
	 * Gets the boxivity of the convex hull, which measures how box-like the convex hull is.
	 * Values closer to 1 indicate a more box-like shape.
	 *
	 * @return the boxivity value
	 */
	public double getBoxivity() {
		initHull();
		return computeBoxivity(hull);
	}

	/**
	 * Gets the elongation of the convex hull, which measures how elongated the convex hull is.
	 * Higher values indicate more elongated shapes.
	 *
	 * @return the elongation value
	 */
	public double getElongation() {
		initHull();
		return computeElongation(hull);
	}

	/**
	 * Gets the roundness of the convex hull, which measures how round or circular the convex hull is.
	 * Values closer to 1 indicate a more round shape.
	 *
	 * @return the roundness value
	 */
	public double getRoundness() {
		initHull();
		return computeRoundness(hull);
	}

	/**
	 * Gets the size (area or volume) of the convex hull, which is the area for 2D hulls
	 * or the volume for 3D hulls.
	 *
	 * @return the size of the convex hull
	 */
	public double getSize() {
		initHull();
		return hull.size();
	}

	/**
	 * Gets the boundary size (perimeter for 2D hulls or surface area for 3D hulls) of the convex hull.
	 *
	 * @return the boundary size of the convex hull
	 */
	public double getBoundarySize() {
		initHull();
		return hull.boundarySize();
	}

	public double getCompactness() {
		initHull();
		if (hull instanceof ConvexHull3D hull3D)
			return opService.geom().compactness(hull3D.getMesh()).getRealDouble();
		return Double.NaN;
	}

	public double getEccentricity() {
		initHull();
		if (hull instanceof ConvexHull2D hull2D)
			return opService.geom().eccentricity(hull2D.getPolygon()).getRealDouble();
		return Double.NaN;
	}

	private void initContext() {
		if (super.getContext() == null)
			setContext(SNTUtils.getContext());
	}

	private void initHull() {
		if (hull == null)
			hull = computeHull(tree);
	}

	protected double computeRoundness(final AbstractConvexHull hull) {
		if (hull instanceof ConvexHull3D hull3D)
			return opService.geom().sphericity(hull3D.getMesh()).getRealDouble();
		else if (hull instanceof ConvexHull2D hull2D)
			return opService.geom().circularity(hull2D.getPolygon()).getRealDouble();
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected double computeBoxivity(final AbstractConvexHull hull) {

		try {
			if (hull instanceof ConvexHull3D hull3D)
				return opService.geom().boxivity(hull3D.getMesh()).getRealDouble();
			else if (hull instanceof ConvexHull2D hull2D)
				return opService.geom().boxivity(hull2D.getPolygon()).getRealDouble();
		} catch (final IllegalArgumentException iae) {
			// Known limitation: ImageJ Ops SmallestEnclosingBoundingBox not available for all mesh types
			SNTUtils.log("Boxivity unavailable (IJ Ops limitation)");
			return Double.NaN;
		}
		throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected RealLocalizable computeCentroid(final AbstractConvexHull hull) {
		if (hull instanceof ConvexHull3D hull3D)
			return opService.geom().centroid(hull3D.getMesh());
		else if (hull instanceof ConvexHull2D hull2D)
			return opService.geom().centroid(hull2D.getPolygon());
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected double computeElongation(final AbstractConvexHull hull) {
		if (hull instanceof ConvexHull3D hull3D)
			return opService.geom().mainElongation(hull3D.getMesh()).getRealDouble();
		else if (hull instanceof ConvexHull2D hull2D)
			return opService.geom().mainElongation(hull2D.getPolygon()).getRealDouble();
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected AbstractConvexHull computeHull(final Tree tree) {
		AbstractConvexHull hull;
		if (tree.is3D()) {
			hull = new ConvexHull3D(getContext(), tree.getNodes());
		} else {
			hull = new ConvexHull2D(getContext(), tree.getNodes());
		}
		hull.compute();
		return hull;
	}

	/**
	 * Sets the optional description for the analysis
	 * @param analysisLabel a string describing the analysis
	 */
	public void setLabel(final String analysisLabel) {
		this.label = analysisLabel;
	}

	private String label() {
		return label;
	}

	private boolean isComputable() {
		if (hull != null) return true;
		// There are edge cases where the entire computation stalls when parsing
		// single-path Trees that are 1D or tiny. There is no exception error,
		// just opService seems to stall!? without any feedback.
		// For now, we'll try to avoid any edge situation altogether.
		int nNodes = 0;
		final BoundingBox bbox = new BoundingBox();
		for (final Path p : tree.list()) {
			nNodes += p.size();
			bbox.compute(p.getNodes().iterator());
			if (nNodes > 3 && bbox.width() * bbox.height() > 0)
				return true;
		}
		return false;
	}

	/**
	 * Gets the tree being analyzed.
	 *
	 * @return the tree being analyzed, or null if the analyzer was created directly from a convex hull.
	 */
	public Tree getTree() {
		return tree;
	}

	/**
	 * Returns the physical unit associated with the specified metric.
	 * @param metric the supported metric to be queried (case-sensitive)
	 * @return physical unit
	 * @see #supportedMetrics()
	 */
	public String getUnit(final String metric) {
		return new TreeStatistics(tree).getUnit(metric);
	}

	/**
	 * Main method for testing and demonstration purposes.
	 * <p>
	 * Creates a ConvexHullAnalyzer instance using demo data and runs the analysis.
	 * This method is primarily used for development and debugging.
	 * </p>
	 *
	 * @param args command line arguments (not used)
	 * @throws InterruptedException if the execution is interrupted
	 */
	public static void main(final String[] args) throws InterruptedException {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final ConvexHullAnalyzer analyzer = new ConvexHullAnalyzer(tree);
		analyzer.run();
	}

}
