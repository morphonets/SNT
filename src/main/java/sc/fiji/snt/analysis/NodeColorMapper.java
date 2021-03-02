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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.scijava.Context;
import org.scijava.plugin.Parameter;

import net.imagej.display.ColorTables;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Class for color coding of {@link NodeStatistics} results.
 *
 * @author Tiago Ferreira
 */
public class NodeColorMapper extends ColorMapper {

	/** Flag for {@value #BRANCH_LENGTH} analysis. */
	public static final String BRANCH_LENGTH = TreeStatistics.BRANCH_LENGTH;

	/** Flag for {@value #BRANCH_ORDER} statistics. */
	public static final String BRANCH_ORDER = NodeStatistics.BRANCH_ORDER;

	/** Flag for {@value #RADIUS} statistics. */
	public static final String RADIUS = TreeStatistics.NODE_RADIUS;

	/** Flag for {@value #X_COORDINATES} statistics. */
	public static final String X_COORDINATES = TreeStatistics.X_COORDINATES;

	/** Flag for {@value #Y_COORDINATES} statistics. */
	public static final String Y_COORDINATES = TreeStatistics.Y_COORDINATES;

	/** Flag for {@value #Z_COORDINATES} statistics. */
	public static final String Z_COORDINATES = TreeStatistics.Z_COORDINATES;

	/** Flag for statistics on node {@value #VALUES} */
	public static final String VALUES = TreeStatistics.VALUES;

	private static final String[] ALL_FLAGS = { //
			BRANCH_LENGTH, //
			RADIUS, //
			BRANCH_ORDER, //
			VALUES, //
			X_COORDINATES, //
			Y_COORDINATES, //
			Z_COORDINATES, //
	};

	@Parameter
	private LUTService lutService;

	private final NodeStatistics<?> nodeStatistics;
	private final TreeColorMapper tColorMapper;
	private Tree singlePointPaths;
	private Tree pointBranches;
	private boolean autoLimits;

	/**
	 * Instantiates the mapper.
	 * 
	 * @param nodeStatistics the NodeStatistics instance holding the nodes to be
	 *                       mapped
	 * @param context        the SciJava application context providing the services
	 *                       required by the class
	 */
	public NodeColorMapper(final NodeStatistics<?> nodeStatistics, final Context context) {
		tColorMapper = new TreeColorMapper(context);
		this.nodeStatistics = nodeStatistics;
		autoLimits = true;
	}

	/**
	 * Instantiates the mapper. Note that because the instance is not aware of any
	 * context, script-friendly methods that use string as arguments may fail to
	 * retrieve referenced Scijava objects.
	 * 
	 * @param nodeStatistics the NodeStatistics instance holding the nodes to be
	 *                       mapped
	 */
	public NodeColorMapper(final NodeStatistics<?> nodeStatistics) {
		tColorMapper = new TreeColorMapper();
		this.nodeStatistics = nodeStatistics;
		autoLimits = true;
	}

	/**
	 * Gets the list of supported mapping metrics.
	 *
	 * @return the list of mapping metrics.
	 */
	public static List<String> getMetrics() {
		return Arrays.stream(ALL_FLAGS).collect(Collectors.toList());
	}

	private void initSinglePointPaths() {
		if (singlePointPaths == null)
			singlePointPaths = AnalysisUtils.convertPointsToSinglePointPaths(nodeStatistics.points);
	}

	private void initPointBranches() {
		if (pointBranches == null)
			pointBranches = AnalysisUtils.createTreeFromPointAssociatedPaths(nodeStatistics.points);
	}

	private void resetAutoLimits() {
		if(autoLimits) {
			tColorMapper.min = Double.MAX_VALUE;
			tColorMapper.max = Double.MIN_VALUE;
		}
	}

	/**
	 * Maps nodes after the specified measurement. Mapping bounds are automatically
	 * determined.
	 *
	 * @param measurement the measurement ({@link #X_COORDINATES},
	 *                    {@link #Y_COORDINATES}, etc.)
	 * @param colorTable  the color table specifying the color mapping. Null not
	 *                    allowed.
	 */
	@Override
	public void map(final String measurement, final ColorTable colorTable) {
		final String normMeasurement = NodeStatistics.getStandardizedMetric(measurement);
		if (normMeasurement.equalsIgnoreCase("unknown"))
			throw new UnknownMetricException("Unrecognized metric: " + measurement);
		if (BRANCH_LENGTH.equals(normMeasurement) || BRANCH_ORDER.equals(normMeasurement)) {
			nodeStatistics.assessIfBranchesHaveBeenAssigned();
			if (!nodeStatistics.isBranchesAssigned()) {
				throw new IllegalArgumentException("NodeStatistics#assignBranches() has not been called.");
			}
			initPointBranches();
			resetAutoLimits();
			if (BRANCH_LENGTH.equals(normMeasurement)) {
				tColorMapper.map(pointBranches, TreeColorMapper.LENGTH, colorTable);
			}
			else if (BRANCH_ORDER.equals(normMeasurement)) {
				tColorMapper.map(pointBranches, TreeColorMapper.PATH_ORDER, colorTable);
			}
		} else {
			initSinglePointPaths();
			resetAutoLimits();
			tColorMapper.map(singlePointPaths, normMeasurement, colorTable);
		}
	}

	/**
	 * Maps nodes after the specified measurement. Mapping bounds are automatically
	 * determined.
	 *
	 * @param measurement    the measurement ({@link #X_COORDINATES},
	 *                       {@link #Y_COORDINATES}, etc.)
	 * @param lut            the lookup table specifying the color mapping. Null not
	 *                       allowed.
	 */
	public void map(final String measurement, final String lut) {
		map(measurement, getColorTable(lut));
	}

	public Collection<? extends SNTPoint> getNodes() {
		return nodeStatistics.points;
	}

	public ColorTable getColorTable(final String lut) {
		return tColorMapper.getColorTable(lut);
	}

	/**
	 * Gets the available LUTs.
	 *
	 * @return the set of keys, corresponding to the set of LUTs available
	 */
	public Set<String> getAvailableLuts() {
		return tColorMapper.getAvailableLuts();
	}

	@Override
	public void setMinMax(final double min, final double max) {
		tColorMapper.setMinMax(min, max);
		autoLimits = Double.isNaN(min) || Double.isNaN(max);
	}

	@Override
	public double[] getMinMax() {
		return tColorMapper.getMinMax();
	}

	@Override
	public ColorTable getColorTable() {
		return tColorMapper.getColorTable();
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final Tree tree = new SNTService().demoTrees().get(0);
		final List<PointInImage> nodes = tree.getNodes();
		final NodeStatistics<?> nodeStats = new NodeStatistics<>(nodes);
		final NodeColorMapper mapper = new NodeColorMapper(nodeStats);
		mapper.map("x-coord", ColorTables.ICE);
		final Viewer3D viewer1 = new Viewer3D();
		viewer1.annotatePoints(nodes, "dummy annotation");
		//viewer.annotatePoints(mapper.getNodes(), "dummy annotation");
		nodeStats.assignBranches(tree);
		mapper.map("length", ColorTables.ICE);
		final Viewer3D viewer2 = new Viewer3D();
		viewer2.annotatePoints(nodes, "dummy annotation");
		viewer1.show();
		viewer2.show();
	}

}
