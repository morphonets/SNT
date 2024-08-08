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

package sc.fiji.snt.analysis;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.MultiViewer2D;
import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Class for color coding groups of {@link Tree}s.
 * <p>
 * After a mapping property and a color table (LUT) are specified, the mapping
 * proceeds as follows: 1) Each Tree in the group is measured for the mapping
 * property; 2) each measurement is mapped to a LUT entry that is used to color
 * each Tree. Mapping limits can be optionally specified
 * </p>
 *
 * @author Tiago Ferreira
 */
public class MultiTreeColorMapper extends TreeColorMapper {

	/** Mapping property: Assigned Tree value */
	public static final String ASSIGNED_VALUE = MultiTreeStatistics.ASSIGNED_VALUE;
	/** Mapping property: Cable length */
	public static final String CABLE_LENGTH = MultiTreeStatistics.LENGTH;
	/** Mapping property (dummy): Each Tree in the collection is assigned an incremental LUT entry */
	public static final String ID = "Cell/id";
	/** Mapping property: Highest {@link sc.fiji.snt.Path#getOrder() path order} */
	public static final String HIGHEST_PATH_ORDER = MultiTreeStatistics.HIGHEST_PATH_ORDER;
	/** Mapping property: Count of all branches */
	public static final String N_BRANCHES = MultiTreeStatistics.N_BRANCHES;
	/** Mapping property: Count of all tips (end points) */
	public static final String N_TIPS = MultiTreeStatistics.N_TIPS;
	/** Mapping property: {@link StrahlerAnalyzer#getRootNumber() Horton-Strahler number} */
	public static final String STRAHLER_NUMBER = MultiTreeStatistics.STRAHLER_NUMBER;

	private static final String[] MULTI_TREE_FLAGS = { ASSIGNED_VALUE, CABLE_LENGTH, ID, HIGHEST_PATH_ORDER, N_BRANCHES,
			N_TIPS, STRAHLER_NUMBER};

	private final List<MappedTree> mappedTrees;
	private int internalCounter = 1;

	/**
	 * Instantiates the MultiTreeColorMapper.
	 *
	 * @param trees the group of trees to be mapped,
	 */
	public MultiTreeColorMapper(final Collection<Tree> trees) {
		mappedTrees = new ArrayList<>();
		for (final Tree tree : trees) {
			if (!tree.isEmpty()) mappedTrees.add(new MappedTree(tree));
		}
	}

	/**
	 * Gets the list of supported mapping metrics.
	 * @param type Either 'all' (MultiTreeColorMapper and TreeColorMapper metrics) or 'default' (MultiTreeColorMapper only)
	 * @return the list of mapping metrics.
	 */
	public static List<String> getMetrics(final String type) {
		if ("all".equalsIgnoreCase(type)) {
			return Stream.concat(Arrays.stream(MULTI_TREE_FLAGS), TreeColorMapper.getMetrics().stream())
					.collect(Collectors.toList());
		}
		else if ("gui".equalsIgnoreCase(type)) {
			return List.of(CABLE_LENGTH, ID, HIGHEST_PATH_ORDER, N_BRANCHES, N_TIPS, STRAHLER_NUMBER);
		}
		else if ("gui-all".equalsIgnoreCase(type)) {
			return Stream.concat(getMetrics("gui").stream(), TreeColorMapper.getMetrics().stream())
					.collect(Collectors.toList());
		}
		return List.of(MULTI_TREE_FLAGS);
	}

	/**
	 * Gets the list of single-value mapping metrics.
	 *
	 * @return the list of single-value mapping metrics.
	 */
	public static List<String> getSingleValueMetrics() {
		return Arrays.asList(MULTI_TREE_FLAGS);
	}
	/*
	 * (non-Javadoc)
	 *
	 * @see ColorMapper#map(java.lang.String,
	 * net.imglib2.display.ColorTable)
	 */
	@Override
	public void map(final String measurement, final ColorTable colorTable) {
		final String cMeasurement = getNormalizedMeasurementInternal(measurement);
		mapInternal(cMeasurement, colorTable);
	}

	public void mapRootDistanceToCentroid(final AllenCompartment compartment, final ColorTable colorTable) {
		if (compartment == null || colorTable == null) throw new IllegalArgumentException("compartment/colorTable cannot be null");
		final OBJMesh mesh = compartment.getMesh();
		if (mesh == null) throw new IllegalArgumentException("Cannot proceed: compartment mesh is not available");
		integerScale = false;
		this.colorTable = colorTable;
		for (final MappedTree mt : mappedTrees) {
			final PointInImage root = mt.tree.getRoot();
			final SNTPoint centroid = mesh.getCentroid( (AllenUtils.isLeftHemisphere(root)) ? "left" : "right");
			final PointInImage pimCentroid = new PointInImage(centroid.getX(),centroid.getY(), centroid.getZ());
			mt.value = root.distanceTo(pimCentroid);
		}
		assignMinMax();
		for (final MappedTree mt : mappedTrees) {
			mt.tree.setColor(getColorRGB(mt.value));
		}
	}

	private void mapInternal(final String measurement, final ColorTable colorTable) {
		super.map(measurement, colorTable);
		if (Arrays.asList(MULTI_TREE_FLAGS).contains(measurement)) {
			for (final MappedTree mt : mappedTrees) {
				final TreeAnalyzer analyzer = new TreeAnalyzer(mt.tree);
				switch (measurement) {
					case ASSIGNED_VALUE:
						integerScale = false;
						mt.value = mt.tree.getAssignedValue();
						break;
					case CABLE_LENGTH:
						integerScale = false;
						mt.value = analyzer.getCableLength();
						break;
					case HIGHEST_PATH_ORDER:
						integerScale = true;
						mt.value = analyzer.getHighestPathOrder();
						break;
					case ID:
						integerScale = true;
						mt.value = internalCounter++;
						break;
					case N_BRANCHES:
						integerScale = true;
						mt.value = analyzer.getNBranches();
						break;
					case N_TIPS:
						integerScale = true;
						mt.value = analyzer.getTips().size();
						break;
					case STRAHLER_NUMBER:
						integerScale = true;
						mt.value = analyzer.getStrahlerNumber();
						break;
					default:
						super.map(measurement, colorTable);
						throw new IllegalArgumentException("Unknown parameter: " + measurement);
				}
			}
			assignMinMax();
			for (final MappedTree mt : mappedTrees) {
				mt.tree.setColor(getColorRGB(mt.value));
			}
		} else  {
			min = Double.MAX_VALUE;
			max = Double.MIN_VALUE;
			for (final MappedTree mt : mappedTrees) {
				final TreeColorMapper tMapper = new TreeColorMapper();
				tMapper.map(mt.tree, measurement, colorTable);
				final double[] minMax = tMapper.getMinMax();
				if (minMax[0] < min) min = minMax[0];
				if (minMax[1] > max) max = minMax[1];
				mt.value = Double.NaN;
			}
		}
	}

	 private String getNormalizedMeasurementInternal(final String measurement) {
		for (final String s : MULTI_TREE_FLAGS) {
			if (s.equalsIgnoreCase(measurement)) return s;
		}
		final String normMeasurement = tryReallyHardToGuessMetric(measurement);
		if (!measurement.equals(normMeasurement)) {
			SNTUtils.log("\"" + normMeasurement + "\" assumed");
			if ("unknonwn".equals(normMeasurement)) {
				throw new IllegalArgumentException("Unrecognizable measurement! "
						+ "Maybe you meant one of the following?: " + Arrays.toString(MULTI_TREE_FLAGS));
			}
		}
		return normMeasurement;
	}

	private String tryReallyHardToGuessMetric(final String guess) {
		if (guess.toLowerCase().contains("assign"))
			return ASSIGNED_VALUE;
		else if (guess.toLowerCase().contains("cable"))
			return CABLE_LENGTH;
		else if (guess.toLowerCase().contains("id"))
			return ID;
		else if (guess.toLowerCase().contains("horton") || guess.toLowerCase().contains("strahler") || guess.toLowerCase().contains("root"))
			return STRAHLER_NUMBER;
		else
			return TreeColorMapper.getNormalizedMeasurement(guess);
	}

	public List<Tree> sortedMappedTrees() {
		mappedTrees.sort(Comparator.comparingDouble(t -> t.value));
		final List<Tree> sortedTrees = new ArrayList<>(mappedTrees.size());
		mappedTrees.forEach(mp -> sortedTrees.add(mp.tree));
		return sortedTrees;
	}

	private void assignMinMax() {
		if (Double.isNaN(min) || Double.isNaN(max) || min > max) {
			min = Double.MAX_VALUE;
			max = Double.MIN_VALUE;
			for (final MappedTree mt : mappedTrees) {
				if (mt.value < min) min = mt.value;
				if (mt.value > max) max = mt.value;
			}
		}
	}

	public MultiViewer2D getMultiViewer() {
		final List<Viewer2D> viewers = new ArrayList<>(mappedTrees.size());
		sortedMappedTrees().forEach(tree -> {
			final Viewer2D viewer = new Viewer2D(SNTUtils.getContext());
			viewer.add(tree);
			viewers.add(viewer);
		});
		final MultiViewer2D multiViewer = new MultiViewer2D(viewers);
		if (colorTable != null && !mappedTrees.isEmpty()) {
			double groupMin = Double.MAX_VALUE;
			double groupMax = Double.MIN_VALUE;
			for (final MappedTree mt : mappedTrees) {
				if (mt.value < groupMin) groupMin = mt.value;
				if (mt.value > groupMax) groupMax = mt.value;
			}
			multiViewer.setColorBarLegend(colorTable, groupMin, groupMax);
		}
		return multiViewer;
	}

	private static class MappedTree {

		public final Tree tree;
		public double value;

		public MappedTree(final Tree tree) {
			this.tree = tree;
		}
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final List<Tree> trees = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final Tree tree = new Tree(SNTUtils.randomPaths());
			tree.rotate(Tree.Z_AXIS, i * 20);
			trees.add(tree);
		}
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(trees);
		mapper.map("length", ColorTables.ICE);
		final Viewer3D viewer = new Viewer3D();
		for (final Tree tree : trees)
			viewer.addTree(tree);
		final double[] limits = mapper.getMinMax();
		viewer.addColorBarLegend(ColorTables.ICE, (float) limits[0],
			(float) limits[1]);
		viewer.show();
	}

}
