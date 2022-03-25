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

package sc.fiji.snt.analysis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.scijava.Context;
import org.scijava.command.ContextCommand;
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

	@Parameter
	private OpService opService;

	private final Tree tree;
	private AbstractConvexHull hull;
	private Map<String, Double> metrics;

	/**
	 * Instantiates a new convex hull analyzer.
	 *
	 * @param tree the Tree to be analyzed
	 */
	public ConvexHullAnalyzer(final Tree tree) {
		this.tree = tree;
	}

	@Override
	public Context context() {
		initContext();
		return super.context();
	}

	@Override
	public Context getContext() {
		initContext();
		return super.getContext();
	}

	@Override
	public void run() {
		System.out.println("Convex hull analysis for " + tree.getLabel() + ": ");
		getAnalysis().forEach((key, value) -> System.out.println("\t" + key + ":\t" + value));
	}

	public static List<String> supportedMetrics() {
		return Arrays.asList(BOUNDARY_SIZE, BOXIVITY, ELONGATION, ROUNDNESS, SIZE);
	}

	public Map<String, Double> getAnalysis() {
		if (metrics != null) {
			return metrics;
		}
		metrics = new LinkedHashMap<>();
		if (isComputable()) {
			initHull();
			metrics.put(BOUNDARY_SIZE, hull.boundarySize());
			metrics.put(BOXIVITY, (tree.is3D()) ? Double.NaN : computeBoxivity(hull));
			metrics.put(ELONGATION, computeElongation(hull));
			metrics.put(ROUNDNESS, computeRoundness(hull));
			metrics.put(SIZE, hull.size());
		} else {
			metrics.put(BOUNDARY_SIZE, Double.NaN);
			metrics.put(BOXIVITY, Double.NaN);
			metrics.put(ELONGATION, Double.NaN);
			metrics.put(ROUNDNESS, Double.NaN);
			metrics.put(SIZE, Double.NaN);
		}
		return metrics;
	}

	public void dump(final SNTTable table) {
		if (table.getRowIndex(tree.getLabel()) < 0)
			table.insertRow(tree.getLabel());
		getAnalysis().forEach((k, v) -> {
			table.appendToLastRow("Convex hull: " + k, v);
		});
	}

	public AbstractConvexHull getHull() {
		initHull();
		return hull;
	}

	public double get(final String metric) {
		return getAnalysis().get(metric);
	}

	public PointInImage getCentroid() {
		initHull();
		final RealLocalizable cntd = computeCentroid(hull);
		return new PointInImage(cntd.getDoublePosition(0), cntd.getDoublePosition(1),
				(tree.is3D()) ? cntd.getDoublePosition(2) : 0);
	}

	public double getBoxivity() {
		initHull();
		return computeBoxivity(hull);
	}

	public double getElongation() {
		initHull();
		return computeElongation(hull);
	}

	public double getRoundness() {
		initHull();
		return computeRoundness(hull);
	}

	public double getSize() {
		initHull();
		return hull.size();
	}

	public double getBoundarySize() {
		initHull();
		return hull.boundarySize();
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
		if (hull instanceof ConvexHull3D)
			return opService.geom().sphericity(((ConvexHull3D) hull).getMesh()).getRealDouble();
		else if (hull instanceof ConvexHull2D)
			return opService.geom().circularity(((ConvexHull2D) hull).getPolygon()).getRealDouble();
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected double computeBoxivity(final AbstractConvexHull hull) { // FIXME this does not work in 3D??
		if (hull instanceof ConvexHull3D)
			return opService.geom().boxivity(((ConvexHull3D) hull).getMesh()).getRealDouble();
		else if (hull instanceof ConvexHull2D)
			return opService.geom().boxivity(((ConvexHull2D) hull).getPolygon()).getRealDouble();
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected RealLocalizable computeCentroid(final AbstractConvexHull hull) {
		if (hull instanceof ConvexHull3D)
			return opService.geom().centroid(((ConvexHull3D) hull).getMesh());
		else if (hull instanceof ConvexHull2D)
			return opService.geom().centroid(((ConvexHull2D) hull).getPolygon());
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected double computeElongation(final AbstractConvexHull hull) {
		if (hull instanceof ConvexHull3D)
			return opService.geom().mainElongation(((ConvexHull3D) hull).getMesh()).getRealDouble();
		else if (hull instanceof ConvexHull2D)
			return opService.geom().mainElongation(((ConvexHull2D) hull).getPolygon()).getRealDouble();
		else
			throw new IllegalArgumentException("Unsupported type:" + hull.getClass());
	}

	protected AbstractConvexHull computeHull(final Tree tree) {
		AbstractConvexHull hull;
		if (tree.is3D()) {
			hull = new ConvexHull3D(getContext(), tree.getNodes(), true);
		} else {
			hull = new ConvexHull2D(getContext(), tree.getNodes(), true);
		}
		hull.compute();
		return hull;
	}

	private boolean isComputable() {
		// There are edge cases where the entire computation stalls when parsing
		// single-path Trees that are 1D or extremely small. There is no exception
		// error, just opService seems to stall!? without any feedback. For now,
		// we'll try to avoid any edge situation altogether.
		int nNodes = 0;
		for (final Path p : tree.list()) {
			nNodes += p.size();
			final BoundingBox bbox = new BoundingBox();
			bbox.compute(p.getNodes().iterator());
			if (nNodes > 3 && bbox.width() * bbox.height() > 0)
				return true;
		}
		return false;
	}

	public static void main(final String[] args) throws InterruptedException {
		final ImageJ ij = new ImageJ();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final ConvexHullAnalyzer analyzer = new ConvexHullAnalyzer(tree);
		analyzer.run();
	}

	public Tree getTree() {
		return tree;
	}

}
