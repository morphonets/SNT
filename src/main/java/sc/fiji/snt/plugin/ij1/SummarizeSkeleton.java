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
package sc.fiji.snt.plugin.ij1;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import org.apache.commons.math3.stat.StatUtils;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class implements the {@code Summarize Skeleton} command. It takes a
 * <a href="https://github.com/fiji/Skeletonize3D">skeletonized image</a> and aggregates combined statistics
 * for all the connected components in the image. Measurements are displayed to a dedicated table. Useful
 * when a skeletonized mask of a single structure is not contiguous but still needs to be measured as a single
 * structure. It was originally part of <a href="https://github.com/tferr/hIPNAT">hINAPT</a>.
 *
 * @author Tiago Ferreira
 */
public class SummarizeSkeleton implements PlugInFilter {

	private ImagePlus imp;

    @Override
	public int setup(final String arg, final ImagePlus imp) {
		this.imp = imp;
		return DOES_8G | NO_CHANGES;
	}

	@Override
	public void run(final ImageProcessor ignored) {

		// Analyze skeleton
		final AnalyzeSkeleton_ as = new AnalyzeSkeleton_();
		as.setup("", imp);
		final SkeletonResult sr = as.run();

		// Abort if there are obvious signs that image is not a skeleton
		if (sr.getBranches() == null || sr.getNumOfTrees() < 1 || IntStream.of(sr.getBranches()).sum() == 0) {
			Strahler.error("Summarize Skeleton", "Image does not seem to be a branched skeleton.", imp);
			return;
		}

        final String TABLE_TITLE = "Skeleton Stats";
        final ResultsTable rt = Strahler.getTable(TABLE_TITLE);
		try {

			// Log image and details on connected components
			rt.incrementCounter();
			rt.addValue("Image", imp.getTitle());
			rt.addValue("Length unit", imp.getCalibration().getUnits());
			rt.addValue("Total foreground length ", getLengthSum(sr));
			rt.addValue("# Foreground pixels", IntStream.of(sr.calculateNumberOfVoxels()).sum());
			rt.addValue("# Components", sr.getNumOfTrees());
			IntStream.rangeClosed(1, 2).forEachOrdered( i ->
					rt.addValue(String.format("# %d-pixel comp.", i), countTreesOfSize(sr, i)));

			// Cull data from isolated pixels, see https://forum.image.sc/t/skeleton-summary-strange-behaviour/114150
			final int MIN_NO_OF_PIXELS_IN_TREES = 2;
			final List<Integer> indicesOfValidTrees = getIndicesOfValidTrees(sr, MIN_NO_OF_PIXELS_IN_TREES);
			if (sr.getNumOfTrees() > indicesOfValidTrees.size()) {
				sr.setAverageBranchLength(filteredArray(sr.getAverageBranchLength(), indicesOfValidTrees));
				sr.setBranches(filteredArray(sr.getBranches(), indicesOfValidTrees));
				sr.setEndPoints(filteredArray(sr.getEndPoints(), indicesOfValidTrees));
				sr.setJunctions(filteredArray(sr.getJunctions(), indicesOfValidTrees));
				sr.setMaximumBranchLength(filteredArray(sr.getMaximumBranchLength(), indicesOfValidTrees));
				sr.setNumberOfVoxels(filteredArray(sr.getNumberOfVoxels(), indicesOfValidTrees));
				sr.setNumOfTrees(indicesOfValidTrees.size());
				sr.setQuadruples(filteredArray(sr.getQuadruples(), indicesOfValidTrees));
				sr.setSlabs(filteredArray(sr.getSlabs(), indicesOfValidTrees));
				sr.setTriples(filteredArray(sr.getTriples(), indicesOfValidTrees));
				sr.setGraph(filteredArray(sr.getGraph(), indicesOfValidTrees));
				sr.setListOfEndPoints(filteredList(sr.getListOfEndPoints(), indicesOfValidTrees));
				sr.setListOfJunctionVoxels(filteredList(sr.getListOfJunctionVoxels(), indicesOfValidTrees));
				sr.setListOfStartingSlabVoxels(filteredList(sr.getListOfStartingSlabVoxels(), indicesOfValidTrees));
				sr.setListOfSlabVoxels(filteredList(sr.getListOfSlabVoxels(), indicesOfValidTrees));
				//NB: we are not changing shortestPathList not spStartPosition since we do not summarize those
				rt.addValue(" ", String.format("Remaining cols. ignore components <%d pixels:", MIN_NO_OF_PIXELS_IN_TREES));
			}

			// Log skeleton stats
			rt.addValue("Total length", getLengthSum(sr));
			rt.addValue("Max branch length", StatUtils.max(sr.getMaximumBranchLength()));
			rt.addValue("Mean branch length", StatUtils.mean(sr.getAverageBranchLength()));
			rt.addValue("# Trees", sr.getNumOfTrees());
			rt.addValue("# Branches", IntStream.of(sr.getBranches()).sum());
			rt.addValue("# Junctions", IntStream.of(sr.getJunctions()).sum());
			rt.addValue("# End-points", IntStream.of(sr.getEndPoints()).sum());
			rt.addValue("# Triple Points", IntStream.of(sr.getTriples()).sum());
			rt.addValue("# Quadruple Points", IntStream.of(sr.getQuadruples()).sum());
			rt.addValue("Sum of voxels", IntStream.of(sr.calculateNumberOfVoxels()).sum());

		} catch (final Exception ignored1) {

			Strahler.error("Summarize Skeleton", "Some statistics could not be calculated", imp);

		} finally {

			rt.show(TABLE_TITLE);

		}

	}

    private int countTreesOfSize(final SkeletonResult sr, final int numberOfVoxels) {
		int count = 0;
		final int[] nVoxels = sr.calculateNumberOfVoxels();
        for (final int nVoxel : nVoxels) {
            if (nVoxel == numberOfVoxels) count++;
        }
		return count;
	}

	private List<Integer> getIndicesOfValidTrees(final SkeletonResult sr, final int minimumPixelSize) {
		final List<Integer> validIndices = new ArrayList<>();
		final int[] nVoxels = sr.calculateNumberOfVoxels();
		assert nVoxels.length == sr.getNumOfTrees();
		for (int i = 0; i < nVoxels.length; i++) {
			if (nVoxels[i] >= minimumPixelSize) validIndices.add(i);
		}
		return validIndices;
	}

	private int[] filteredArray(final int[] array, final List<Integer> indicesToKeep) {
		return IntStream.range(0, array.length).filter(indicesToKeep::contains).map(idx -> array[idx]).toArray();
	}

	private double[] filteredArray(final double[] array, final List<Integer> indicesToKeep) {
		return IntStream.range(0, array.length).filter(indicesToKeep::contains).mapToDouble(idx -> array[idx]).toArray();
	}

	private Graph[] filteredArray(final Graph[] array, final List<Integer> indicesToKeep) {
		final Graph[] filteredArray = new Graph[indicesToKeep.size()];
		for (int i = 0, j = 0; i < array.length; i++) {
			if (indicesToKeep.contains(i)) filteredArray[j++] = array[i];
		}
		return filteredArray;
	}

	private ArrayList<Point> filteredList(final List<Point> list, final List<Integer> indicesToKeep) {
		return IntStream.range(0, list.size()).filter(indicesToKeep::contains).mapToObj(list::get)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	private double getLengthSum(final SkeletonResult sr) {
		double sumLength = 0d;
		final double[] avgLengths = sr.getAverageBranchLength();
		final int[] branches = sr.getBranches();
		for (int i = 0; i < sr.getNumOfTrees(); i++)
			sumLength += avgLengths[i] * branches[i];
		return sumLength;
	}
}
